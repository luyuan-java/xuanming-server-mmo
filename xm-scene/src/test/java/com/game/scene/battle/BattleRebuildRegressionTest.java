package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.PlayerState;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 按锁重建冻结、144 重连提示与续锁的几处修正（审计 FRZ-3 / FRZ-4 / FRZ-5 / RDS-8 / RDS-9 / OPS-13）：
 * <ul>
 *   <li>进场重建的 TOUCH 复核按<b>重建那一刻</b>的阶段决定推不推 144；复核出错也推（冻结保留，提示就该给）；同一个冻结 144 恰好一次；</li>
 *   <li>TOUCH 返回 2（锁上已是 F、入参是 P、什么都没改）按命中保留冻结；</li>
 *   <li>迟到确认回来时已有同局的备战冻结 → 按正常确认升级；迟到确认不带期限时不把续期记成已确认、当场补一次续期；</li>
 *   <li>「锁指向一局已有结算记录的局」三种情形分开计数。</li>
 * </ul>
 * §13.2 的 {@code BattleRecoveryTest} / {@code ConfirmBattleTest} 其余用例另写，这里只钉修过的点。
 */
class BattleRebuildRegressionTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final int NODE = BattleFixture.BATTLE_NODE;

    private final BattleFixture f = new BattleFixture();

    /** 带着 Redis 里现成的锁进场（不清出站记录）：进场恢复按锁重建。 */
    private ScenePlayer loadWithLock(String state, long ttlSec) {
        f.locks.putLock(PLAYER, X, NODE, state, f.deadline(), f.prepareDeadline(), ttlSec);
        return f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
    }

    // ------------------------------------------------------------------ 复核与 144（FRZ-3 / FRZ-4 / RDS-8）

    @Test
    void 重建FIGHTING_复核命中_推144一次_在进场下行之后() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_FIGHTING, 360);
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).as("复核结局回来之前不推").isEmpty();
        Call touch = f.locks.take(Op.TOUCH);
        assertThat(touch.args()).containsEntry("state", "F").containsEntry("deadline", freeze.deadlineMs())
                .containsEntry("prepareDeadline", freeze.prepareDeadlineMs()).containsEntry("ttl", BattleFixture.BATTLE_MILLIS / 1000 + 60);

        touch.complete();
        f.drain();

        assertThat(f.reconnectHints(player)).containsExactly(X);
        List<Integer> ids = f.messageIds(player);
        assertThat(ids.indexOf(f.reconnectHintId)).as("144 在 79 / 21 之后").isGreaterThan(ids.indexOf(Contracts.IDS.notifyEnterScene()))
                .isGreaterThan(ids.indexOf(Contracts.IDS.notifyActorCreate()));
        assertThat(f.hints("login")).isEqualTo(1);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(freeze.lockExtended()).isTrue();

        // 之后的补发确认：零 Redis、不再推
        f.locks.clearCalls();
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.reconnectHints(player)).hasSize(1);
        assertThat(f.confirms("idempotent")).isEqualTo(1);
    }

    /**
     * FRZ-3：复核脚本出错时冻结保守保留——玩家被冻在一场进行中的战斗里，重连提示必须给（基线重建后不等复核结果就推）。
     * 续期结局不明，所以 lockExtended 置回假，下一次补发确认再续一次锁；144 仍只有一条。
     */
    @Test
    void 重建FIGHTING_复核出错_冻结保留_144照样推一次_下一次确认再续一次锁() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_FIGHTING, 360);
        f.drain();
        BattleFreeze freeze = player.battle().freeze();

        f.locks.take(Op.TOUCH).fail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(player.battle().freeze()).as("保守保留").isSameAs(freeze);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.hints("login")).isEqualTo(1);
        assertThat(f.rebuilds("login", "error")).isEqualTo(1);
        assertThat(freeze.lockExtended()).as("续期没确认").isFalse();

        f.locks.clearCalls();
        long deadline = f.deadline();
        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.ops()).as("再续一次").containsExactly(Op.CONFIRM);
        assertThat(f.confirms("reextended")).isEqualTo(1);
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.reconnectHints(player)).as("144 仍只有一条").hasSize(1);

        f.locks.clearCalls();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.locks.calls()).as("续期成功后的重复确认零 Redis").isEmpty();
    }

    @Test
    void 重建PREPARING_复核出错_冻结保留_不推144() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_PREPARING, 120);
        f.drain();

        f.locks.take(Op.TOUCH).fail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.reconnectHints(player)).as("PREPARING 永不推").isEmpty();
        assertThat(f.rebuilds("login", "error")).isEqualTo(1);
    }

    /**
     * FRZ-4 / RDS-8：重建出 PREPARING、复核在途时确认到达。确认升级那一下推了 144（冻结不是本实例备战的）；随后复核回来时冻结已是 FIGHTING，
     * 修之前按回调时刻的阶段又推一条。
     */
    @Test
    void 重建PREPARING_复核在途时确认到达_144恰好一条() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_PREPARING, 120);
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        long extendedTtl = f.locks.lockTtlSec(PLAYER);
        assertThat(extendedTtl).isEqualTo(BattleFixture.BATTLE_MILLIS / 1000 + 60);

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).as("TOUCH(P) 落在 CONFIRM 之后：不降级、什么都没改").isEqualTo(BattleRedis.TOUCH_KEPT_FIGHTING);
        assertThat(f.reconnectHints(player)).as("同一局 144 恰好一条").containsExactly(X);
        assertThat(f.hints("confirm")).isEqualTo(1);
        assertThat(f.hints("login")).isZero();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).as("TTL 没被缩回备战期").isEqualTo(extendedTtl);
    }

    @Test
    void 重建PREPARING_复核先执行确认后到_复核回复最后回来_144仍只有一条() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_PREPARING, 120);
        f.drain();
        Call touch = f.locks.take(Op.TOUCH).execute();
        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_HIT);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        touch.reply();
        f.drain();

        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
    }

    @Test
    void 重建PREPARING_确认升级之后复核出错_不补推第二条144() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_PREPARING, 120);
        f.drain();
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.lockExtended()).isTrue();

        f.locks.take(Op.TOUCH).fail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(freeze.lockExtended()).as("确认的续期已成功，不因备战复核出错而作废").isTrue();
    }

    // ------------------------------------------------------------------ TOUCH 返回 2 与迟到确认的升级（FRZ-5）

    /**
     * 快照里锁还是 P，TOUCH 到达时锁已被（别处处理的）确认标成 F：脚本回 2、什么都不改。scene 按命中保留这个 PREPARING 冻结，
     * 不撤销、不升级、不推 144；下一次补发确认照常升级并推一次。
     */
    @Test
    void 重建PREPARING_复核时锁上已是F_返回2按命中保留_不撤销不升级不推_之后的确认照常升级() {
        f.locks.hold(Op.ENTER_READ, Op.TOUCH);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        Call read = f.locks.take(Op.ENTER_READ).execute();
        // 快照之后、TOUCH 之前，锁被标成 F 并续到正式期限（旧节点的离线确认）
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), f.prepareDeadline(), 360);
        read.reply();
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_KEPT_FIGHTING);
        assertThat(player.battle().freeze()).as("按命中保留，不撤销").isSameAs(freeze);
        assertThat(freeze.phase()).as("不擅自升级").isEqualTo(Phase.PREPARING);
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(360);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
    }

    @Test
    void 重建后复核没命中_撤销冻结_锁不动() {
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = loadWithLock(BattleRedis.STATE_FIGHTING, 360);
        f.drain();
        assertThat(player.inBattle()).isTrue();
        // 锁在快照之后被别的局顶掉了
        f.locks.putLock(PLAYER, 99, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);

        f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(99);
        assertThat(f.locks.count(Op.DELETE_IF_MATCH) + f.locks.count(Op.DELETE_PREPARING)).isZero();
    }

    /**
     * FRZ-5：恢复读在途时确认到达（无冻结 → 发 CONFIRM，锁标 F）；恢复读按更早的快照（s = P）重建出 PREPARING；CONFIRM 的回复回来时
     * 已有同局的备战冻结。修之前这条确认被丢弃，内存停在 PREPARING 而锁已是 F，要等下一次补发才追上。
     */
    @Test
    void 恢复读在途时确认到达_回调时已有同局备战冻结_按正常确认升级并推一次144() {
        f.locks.hold(Op.ENTER_READ, Op.CONFIRM, Op.TOUCH);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        Call read = f.locks.take(Op.ENTER_READ).execute();
        long deadline = f.deadline() + 5_000;
        f.confirm(PLAYER, X, deadline);
        Call confirm = f.locks.take(Op.CONFIRM).execute();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        read.reply();
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).as("按旧快照重建的是 PREPARING").isEqualTo(Phase.PREPARING);

        confirm.reply();
        f.drain();

        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).as("期限取确认事件的").isEqualTo(deadline);
        assertThat(freeze.lockExtended()).as("这段 CONFIRM 已把锁续到正式期限").isTrue();
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.confirms("upgraded")).isEqualTo(1);
        assertThat(f.confirms("idempotent")).isZero();

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_KEPT_FIGHTING);
        assertThat(f.reconnectHints(player)).as("复核回来不再推").containsExactly(X);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        f.locks.clearCalls();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.locks.calls()).as("之后的补发确认零 Redis").isEmpty();
    }

    @Test
    void 迟到确认回来时已有别的局的冻结_不动它() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        Call confirm = f.locks.take(Op.CONFIRM).execute();
        BattleFreeze other = new BattleFreeze(99, NODE, Phase.PREPARING, f.deadline(), f.prepareDeadline(), true);
        BattleFixture.setFreeze(player, other);

        confirm.reply();
        f.drain();

        assertThat(player.battle().freeze()).isSameAs(other);
        assertThat(other.phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.confirms("idempotent")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 迟到确认不带期限（RDS-9）

    /**
     * RDS-9：确认事件 deadline_ms = 0 时，迟到确认那段 CONFIRM 只把锁标成 F、不写 d、不续 TTL（锁停在备战 TTL）。
     * 修之前仍把 lockExtended 置真，之后的补发全走零 Redis 的幂等分支，锁在战斗结束前过期。现在当场按锁里的期限补一次续期。
     */
    @Test
    void 迟到确认不带期限_重建后当场按锁里的期限补一次续期_成功才算续期已确认() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long lockDeadline = f.deadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, lockDeadline, f.prepareDeadline(), 120);
        f.locks.hold(Op.CONFIRM);

        f.confirm(PLAYER, X, 0);
        Call first = f.locks.take(Op.CONFIRM).complete();
        assertThat(first.args()).containsEntry("deadline", 0L).containsEntry("ttl", 0L);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).as("这段 CONFIRM 没有续期，锁还停在备战 TTL").isEqualTo(120);
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).as("期限取锁里的 d").isEqualTo(lockDeadline);
        assertThat(freeze.lockExtended()).as("补的那次续期还没回来，不能算已确认").isFalse();
        assertThat(f.reconnectHints(player)).containsExactly(X);
        Call second = f.locks.take(Op.CONFIRM);
        assertThat(second.args()).containsEntry("deadline", lockDeadline).containsEntry("ttl", BattleFixture.BATTLE_MILLIS / 1000 + 60);

        second.complete();
        f.drain();

        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lockTtlSec(PLAYER)).as("锁 TTL 不短于正式期限剩余 + 60").isEqualTo(BattleFixture.BATTLE_MILLIS / 1000 + 60);
        assertThat(f.locks.count(Op.CONFIRM)).isEqualTo(2);
    }

    @Test
    void 迟到确认不带期限_补的那次续期失败_下一次补发确认再续() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.confirm(PLAYER, X, 0);
        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.lockExtended()).isFalse();
        assertThat(f.confirms("error")).isEqualTo(1);

        f.confirm(PLAYER, X, 0);
        f.drain();

        assertThat(f.confirms("reextended")).isEqualTo(1);
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(BattleFixture.BATTLE_MILLIS / 1000 + 60);
        assertThat(f.reconnectHints(player)).hasSize(1);
    }

    @Test
    void 迟到确认带期限_重建即视为续期已确认_不多发续期() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        long deadline = f.deadline() + 5_000;

        f.confirm(PLAYER, X, deadline);
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.battleNodeId()).isEqualTo(NODE);
        assertThat(freeze.preparedHere()).as("144 已推过").isTrue();
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.ops()).containsExactly(Op.CONFIRM);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.hints("late_confirm")).isEqualTo(1);
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 重建指标的口径（OPS-13）

    /** 离线结算后在期限内登录的常态：锁还指着这一局、记录也在，本轮应用后不重建——计 ledger_hit，不能计成 skipped_corrupt。 */
    @Test
    void 锁指向本轮刚应用的局_不重建_计ledger_hit不计skipped_corrupt() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(BattleFixture.settlement(PLAYER, X, 100).build());

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.gold(player)).isEqualTo(100);
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
        assertThat(f.rebuilds("login", "skipped_corrupt")).isZero();
        assertThat(f.rebuilds("login", "skipped_pending")).isZero();
        assertThat(f.locks.count(Op.TOUCH)).isZero();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    @Test
    void 锁指向坏记录_不重建_计skipped_corrupt_只删那个字段() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.locks.storeSettlement(PLAYER, X, "不是 protobuf".getBytes(StandardCharsets.UTF_8));
        f.locks.storeSettlement(PLAYER, 9, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "skipped_corrupt")).isEqualTo(1);
        assertThat(f.rebuilds("login", "ledger_hit")).isZero();
        assertThat(f.count("xm.scene.battle.pending.corrupt")).isEqualTo(2);
        assertThat(f.locks.calls(Op.DELETE_FIELD)).extracting(c -> new String((byte[]) c.args().get("field"), StandardCharsets.UTF_8))
                .containsExactlyInAnyOrder("7", "9");
        assertThat(f.locks.settlementCount(PLAYER)).isZero();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁不动").isEqualTo(X);
        assertThat(f.locks.count(Op.ACK)).isZero();
    }

    /** 金币为坏值（超出 int64）的记录被延后：锁指向它时不重建，计 skipped_pending；恢复置 RETRY。 */
    @Test
    void 锁指向被延后的局_不重建_计skipped_pending() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(BattleFixture.settlement(PLAYER, X, -1L).build());

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.rebuilds("login", "skipped_pending")).isEqualTo(1);
        assertThat(f.rebuilds("login", "skipped_corrupt")).isZero();
        assertThat(f.rebuilds("login", "ledger_hit")).isZero();
        assertThat(f.settlementsCounted("login", "deferred_currency")).isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("延后的记录留着").isTrue();
    }

    @Test
    void 锁指向排在延后那一局之后还没轮到的局_不重建_计skipped_pending() {
        f.locks.putLock(PLAYER, 9, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(BattleFixture.settlement(PLAYER, X, -1L).build());
        f.store(BattleFixture.settlement(PLAYER, 9, 100).build());

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.gold(player)).as("不越过延后的那一局应用更新的局").isZero();
        assertThat(f.rebuilds("login", "skipped_pending")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
    }

    @Test
    void 锁指向账本里已有的局_销账不重建_计ledger_hit() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        PlayerState state = PlayerState.newBuilder().setBattleLedger(com.game.player.store.state.BattleLedgerState.newBuilder()
                .addApplied(com.game.player.store.state.BattleLedgerEntry.newBuilder().setBattleId(X).setAppliedAtMs(1))).build();

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, state);
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.ACK);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("放了锁").isEqualTo(2L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().has(X)).isFalse();
    }
}
