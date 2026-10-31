package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.PlayerLeave;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.PlayerState;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.player.Wallet;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import org.junit.jupiter.api.Test;

/**
 * FIGHTING 冻结覆盖「战斗期限 + Match 终局宽限 + 收件箱投递余量」（2026-10-30，与 mmorpg C++ 同批）：Match 在期限之后的终局宽限
 * （{@link MatchBudgets#TERMINAL_OUTCOME_GRACE_MS}）内仍接受 COMPLETE，个人结算随后才经收件箱到达。这段窗口里冻结必须还在——reaper 不判废、
 * 锁 TTL 覆盖窗口（重登能按锁重建）、资产通道（只看冻结）照旧回 RETRY 27002；否则玩家可先把本局消耗的物品经交易 / 帮会托管转走，
 * 结算到达时消耗被夹紧，形成可逐局重复的资产复制。
 */
class FightingOutcomeWindowTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long BATTLE = BattleFixture.BATTLE_MILLIS;
    private static final long WINDOW = BattleFixture.OUTCOME_WINDOW_MILLIS;
    private static final long GRACE = BattleRedis.FIGHTING_EXPIRY_GRACE.toMillis();
    private static final long LOCK_EXTRA = BattleRedis.LOCK_EXTRA_TTL_SEC * 1000;
    private static final String SECRET = "fighting-outcome-window-test-secret-0123456789";
    private static final int ASSET_IN_BATTLE = 27002;

    private final BattleFixture f = new BattleFixture();

    private ScenePlayer fightingPlayer() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        f.sink.clear();
        f.follows.clear();
        return player;
    }

    private double expired(BattleFixture fixture) {
        return fixture.count("game.scene.battle.freeze.expired", "phase", "fighting");
    }

    @Test
    void 战斗期限后终局宽限加投递余量内不解冻_越过后才判废() {
        ScenePlayer player = fightingPlayer();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(WINDOW).as("窗口 = Match 终局宽限 + 投递余量，两者同源常量").isEqualTo(MatchBudgets.TERMINAL_OUTCOME_GRACE_MS
                + BattleFreeze.INBOX_DELIVERY_MARGIN_MS);
        assertThat(freeze.effectiveDeadlineMs()).isEqualTo(freeze.deadlineMs() + WINDOW);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("锁覆盖 期限 + 窗口 + 60 s").isEqualTo(BattleFixture.FIGHTING_LOCK_TTL_SEC);
        assertThat(f.locks.lock(PLAYER)).as("锁里的 d 仍是原始战斗期限")
                .containsEntry(BattleRedis.FIELD_DEADLINE, Long.toUnsignedString(freeze.deadlineMs()));

        // 刚过战斗期限、刚过 Match 终局宽限、到有效期限 + 宽限那一刻：每一轮都不解冻、不发 rescue 读、锁一直在
        f.advance(BATTLE + 1);
        f.reap();
        assertThat(player.battle().freeze()).as("期限刚过：Match 仍可提交 COMPLETE").isSameAs(freeze);
        f.advance(MatchBudgets.TERMINAL_OUTCOME_GRACE_MS);
        f.reap();
        assertThat(player.battle().freeze()).as("终局宽限刚过：个人结算还在收件箱路上").isSameAs(freeze);
        f.advance(BattleFreeze.INBOX_DELIVERY_MARGIN_MS + GRACE - 1);
        f.reap();
        assertThat(player.battle().freeze()).as("有效期限 + 宽限那一刻还不读").isSameAs(freeze);
        assertThat(f.locks.calls()).as("窗口内零 Redis 调用").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁 TTL 覆盖窗口").isEqualTo(X);
        assertThat(expired(f)).isZero();

        // 越过有效期限 + 宽限：rescue 读不到本局记录才判废（解冻 + 条件删锁）
        f.advance(1);
        f.reap();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT, Op.DELETE_IF_MATCH);
        assertThat(f.rescues("miss")).isEqualTo(1);
        assertThat(expired(f)).isEqualTo(1);
        assertThat(f.locks.lock(PLAYER)).isNull();

        // 另一条路：rescue 读一直出错，过了有效期限 + 60 s 不读直接判废（之前的每一轮都不判废）
        BattleFixture g = new BattleFixture();
        ScenePlayer other = g.enter(SESSION, PLAYER);
        g.fighting(PLAYER, X);
        g.locks.failAlways(Op.READ_SETTLEMENT, new RuntimeException("Redis 超时"));
        g.advance(BATTLE + WINDOW + GRACE + 1);
        g.reap();
        assertThat(other.inBattle()).as("读错不判废").isTrue();
        g.advance(LOCK_EXTRA - GRACE - 1);
        g.reap();
        assertThat(other.inBattle()).as("有效期限 + 60 s 那一刻还冻着").isTrue();
        g.advance(1);
        g.reap();
        assertThat(other.inBattle()).isFalse();
        assertThat(g.rescues("gave_up")).isEqualTo(1);
        assertThat(expired(g)).isEqualTo(1);
    }

    /** 期限 + 90 s 离场重登（实例已移除，同 epoch）：锁的 TTL 覆盖窗口，进场恢复按锁重建出 FIGHTING 冻结，之后同样按有效期限判。 */
    @Test
    void 窗口内重登按锁重建FIGHTING冻结() {
        fightingPlayer();
        long deadline = f.world.playerById(PLAYER).battle().freeze().deadlineMs();
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();

        f.advance(BATTLE + 90_000);
        assertThat(f.locks.lockBattleId(PLAYER)).as("期限 + 90 s：旧口径（期限 + 60 s）锁早已过期，新口径仍在").isEqualTo(X);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");

        ScenePlayer back = f.enter(SESSION + 1, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());

        assertThat(back.inBattle()).as("按锁重建").isTrue();
        BattleFreeze rebuilt = back.battle().freeze();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.deadlineMs()).as("重建用锁里的原始期限").isEqualTo(deadline);
        assertThat(rebuilt.effectiveDeadlineMs()).isEqualTo(deadline + WINDOW);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("复核续出的 TTL = 有效期限剩余 + 60 s")
                .isEqualTo((WINDOW - 90_000) / 1000 + BattleRedis.LOCK_EXTRA_TTL_SEC);

        f.reap();
        assertThat(back.battle().freeze()).as("还在窗口内：同一判据，不判废").isSameAs(rebuilt);
        assertThat(f.locks.calls()).isEmpty();
    }

    /**
     * 资产通道只看冻结：期限 + 30 s（Match 仍可提交 COMPLETE）扣款回 RETRY 27002、不记账，钱与账本都不变；判废解冻之后同一个 seq 照常扣。
     */
    @Test
    void 窗口内资产通道扣款回RETRY27002不记账_解冻后同seq照常() {
        AssetOpService assetOps = new AssetOpService(f.world, f.currency, f.bags, new AssetOpAuth(caller -> SECRET), f.clock,
                f.sceneMetrics);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.currency.add(player, Wallet.GOLD, 1000, Reason.GM_GRANT).ok()).isTrue();
        f.fighting(PLAYER, X);

        f.advance(BATTLE + 30_000);
        f.reap();
        assertThat(player.inBattle()).isTrue();
        PlayerState before = BattleFixture.persistentState(player);

        AssetOpResponse refused = assetOps.handle(AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(1, 30)));

        assertThat(refused.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(refused.getReason()).isEqualTo(ASSET_IN_BATTLE);
        assertThat(BattleFixture.persistentState(player)).as("不记账：账本、钱包都不变").isEqualTo(before);
        assertThat(f.gold(player)).isEqualTo(1000);

        // 越过有效期限 + 宽限：判废解冻（没有本局记录）
        f.advance(WINDOW - 30_000 + GRACE + 1);
        f.reap();
        assertThat(player.inBattle()).isFalse();

        AssetOpResponse applied = assetOps.handle(AssetRpc.DEBIT, signed(AssetRpc.DEBIT, debit(1, 30)));
        assertThat(applied.getOutcome()).as("被 RETRY 的 seq 没有被记成拒绝，解冻后照常扣").isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(f.gold(player)).isEqualTo(970);
    }

    private static AssetOpRequest debit(long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(seq)
                .setStreamEpoch(1_700_000_000_000L).setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(amount)))
                .build();
    }

    private AssetOpRequest signed(AssetRpc rpc, AssetOpRequest request) {
        return AssetOpSignatures.sign(rpc, request, AssetOpSignatures.CALLER_GUILD, SECRET, f.clock.epochMillis());
    }
}
