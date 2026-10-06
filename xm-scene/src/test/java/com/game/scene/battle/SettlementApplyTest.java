package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import com.game.scene.audit.AssetAudit;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.BattleSettlementService.Outcome;
import com.game.scene.battle.BattleSettlementService.Result;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.metrics.SceneBattleMetrics.SettlementResult;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerMissions;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.Wallet;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakePlayerRepository.PendingProgress;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 结算应用的「恰好一次」（scene-battle-spec §7.11、§7.12；§13.2 {@code SettlementApplyTest} 一行）：<b>逐条对照</b>基线
 * {@code cpp/tests/bag_test/player_battle_settlement_test.cpp}（规格里的 {@code t_settle}），方法名前缀就是基线用例的行号：
 * <pre>
 * :120 DuplicateSuccessfulCallbacksAndReentryPayAndProgressOnce   重复回调与重入只付一次、只推一次进度（Java：重入抛断言）
 * :141 CurrencyFailureLeavesPendingSideEffectsUntouchedAndCanRetry 金币失败时 HP / 宝宝 / 任务都不动，解封后应用一次
 * :157 ReloginOldPendingDoesNotPayAgainOrRemoveNewBattle           重登读到旧记录不再发奖、不碰新一局的冻结与锁
 * :185 CacheHitButLedgerMissReappliesExactlyOnce                   Java 对应：实例 A 应用后存盘被围栏拒绝、被移除 → 实例 B 恰好应用一次
 * :203 MissingAttributesCanRetryWithoutPrematureGoldCredit         不适用（ScenePlayer 总有属性对象），见 {@link #t203_不适用_Java的玩家实例总有属性对象}
 * :213 WrongPlayerCannotApplyOrPoisonValidRetry                    错玩家不应用、不毒化合法重投
 * :233 AppliedSettlementIsRecordedInPersistedLedger                账本进入 toSave()
 * :245 LedgerFromDiskBlocksReapplyAfterRestart                     带账本加载 → ALREADY_APPLIED、无 150
 * :262 FrozenPlayerDefersSettlementWithNoGoldOrItems               FREEZING → DEFERRED（没有金币也没有道具的结算同样整笔延后）
 * :282 TravelHandoffDefersWholeSettlement                          FREEZING → DEFERRED、无金币 / 道具
 * :295 AckDefersClearWhileAppliedButNotDurable                     未落盘不 ACK
 * :308 AckClearsOnceLedgerEntryIsDurable                           onProgressSaved(SAVED) 后 ACK、成功后 forget
 * :406 / :419 / :431 / :442 / :455                                 道具：扣与发、按持有夹紧、没有不报错、非战斗道具拒扣不销毁、重复应用不重扣不重发
 * </pre>
 * 账本的纯规则（基线 {@code :325-360}）在 {@code BattleLedgerTest}。
 *
 * <p>基线用一份固定的结算（battle 701、气血 80、法力 20、金币 12、两只怪）；这里同值。击杀事实用正式配表里的任务 7（击杀 1 号怪 8 次）的进度观察：
 * 结算带 2 只 1 号怪，应用一次进度 +2。新号（职业 3、1 级）进场后气血 550 / 法力 840（正式配表的现值，变了这些断言会指出来）。
 */
class SettlementApplyTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    /** 基线用例的 battle_id。 */
    private static final long X = 701;
    private static final long GOLD = 12;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    private static final long FULL_HEALTH = 550;
    private static final long FULL_MANA = 840;
    private static final int KILL_MISSION = 7;
    /** 正式物品表：10、11 可叠加（999）且能在战斗里用；15 可叠加但不是战斗道具。 */
    private static final int POTION = 10;
    private static final int DROP = 11;
    private static final int NON_BATTLE_ITEM = 15;
    private static final long PET = 9_001;

    private final BattleFixture f = new BattleFixture();

    // ------------------------------------------------------------------ 造数据

    /** 基线那份结算：气血 80、法力 20、金币 12；1 号怪 2 只（任务 7 关注）、2 号怪 1 只（没有任务关注）。 */
    private static BattleSettlementData.Builder base(long battleId) {
        return BattleSettlementData.newBuilder().setPlayerId(PLAYER).setBattleId(battleId)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setHealth(80).setMana(20).setGoldGain(GOLD)
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(1).setCount(2))
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(2).setCount(1));
    }

    private static BattleItemEntry item(int configId, long count) {
        return BattleItemEntry.newBuilder().setItemTableId(configId).setCount(count).build();
    }

    private static BagItemState stack(long guid, int configId, int size, int pos) {
        return BagItemState.newBuilder().setItemUuid(guid).setConfigId(configId).setStackSize(size).setPos(pos)
                .setBagType(BagType.INVENTORY.code()).setAcquireSeq(guid).build();
    }

    private static PlayerState bagOf(BagItemState... stacks) {
        return PlayerState.newBuilder().setBag(BagState.newBuilder().addAllItems(List.of(stacks))).build();
    }

    private static BattleLedgerState ledgerOf(long battleId, long appliedAtMs) {
        return BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(battleId).setAppliedAtMs(appliedAtMs)).build();
    }

    /** 接下任务 7（击杀 1 号怪 8 次），之后用 {@link #kills} 看收到了几条击杀事实。 */
    private void watchKills(ScenePlayer player) {
        assertThat(f.missions.accept(player, 0, KILL_MISSION)).as("任务 7 应可接").isZero();
        assertThat(kills(player)).isZero();
    }

    private static long kills(ScenePlayer player) {
        PlayerMissions.Active active = player.missions().active(KILL_MISSION);
        assertThat(active).as("任务 7 应在进行中").isNotNull();
        return active.progress(0);
    }

    private static long held(ScenePlayer player, int configId) {
        return player.bags().bag(BagType.INVENTORY).total(configId);
    }

    private Result apply(ScenePlayer player, BattleSettlementData settlement) {
        return f.settlements.apply(f.world, player, settlement);
    }

    private SceneBattleReply delivered(BattleSettlementData settlement) {
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();
        assertThat(reply).isCompleted();
        return BattleFixture.done(reply);
    }

    // ------------------------------------------------------------------ :120

    /**
     * 基线 {@code :120}：同一份结算的重复回调只付一次、只推一次进度。基线的重入返回 false（应用缓存的 InFlight）；Java 没有应用缓存，
     * 同一玩家应用中再进来是程序错误 → 抛 {@code IllegalStateException}（D18），外层那次应用照常完成。
     * 重入的注入点：掉落入包铸号的那一刻（应用 i 步中途，{@code applying} 为真）。
     */
    @Test
    void t120_重复回调与重入_只付一次_只推一次进度_重入抛断言() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        watchKills(player);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).addItemsGained(item(DROP, 1)).build();
        f.store(settlement);
        List<Object> reentry = new ArrayList<>();
        f.beforeItemMint = () -> {
            f.beforeItemMint = null;
            try {
                reentry.add(apply(player, settlement));
            } catch (IllegalStateException e) {
                reentry.add(e);
            }
        };
        f.sink.clear();

        SceneBattleReply first = delivered(settlement);

        assertThat(reentry).as("应用中途确实重入了一次").hasSize(1);
        assertThat(reentry.get(0)).as("重入不是「再应用一遍」也不是悄悄返回，而是抛断言")
                .isInstanceOf(IllegalStateException.class);
        assertThat(((IllegalStateException) reentry.get(0)).getMessage()).contains("重入").contains("1001");
        assertThat(first.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(first.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(kills(player)).as("2 只 1 号怪 = 2 条击杀事实").isEqualTo(2);
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(player.attributes().mana()).isEqualTo(20);
        assertThat(held(player, DROP)).as("重入抛出之后，外层的掉落照常入包").isEqualTo(1);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(player.battle().applying()).as("应用结束，不重入标记已复位").isFalse();

        // 重复回调：锁还指着这一局（留到落盘），第二次投递走到同一个应用入口，被账本挡住
        player.attributes().setHealth(70);
        SceneBattleReply second = delivered(settlement);

        assertThat(second.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(second.getSettlement()).as("这一局已应用：调用方可以解冻并尝试销账，但奖是上一次发的")
                .isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(apply(player, settlement)).isEqualTo(Result.ALREADY);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(kills(player)).isEqualTo(2);
        assertThat(player.attributes().health()).as("气血不再被这份结算改写").isEqualTo(70);
        assertThat(held(player, DROP)).isEqualTo(1);
        assertThat(f.battleEnds(player)).as("150 只推一次").hasSize(1);
        assertThat(f.audit.currencies).as("金币流水只有一条").hasSize(1);

        // 下一局不依赖 id 单调递增：更小的 battle_id 照常应用
        assertThat(apply(player, base(700).build())).isEqualTo(Result.APPLIED);
        assertThat(f.gold(player)).isEqualTo(2 * GOLD);
        assertThat(kills(player)).isEqualTo(4);
        assertThat(player.battleLedger().battleIds()).containsExactly(700L, X);
    }

    // ------------------------------------------------------------------ :141

    /**
     * 基线 {@code :141}：金币是唯一会正常失败的入账，排在一切副作用之前。金币被拒（这里是 GM 封禁获取）→ 整笔延后：气血法力、宝宝、道具、
     * 任务、账本、冻结、Redis 都不动；解封后的重投应用一次。
     */
    @Test
    void t141_金币入账失败_气血宝宝道具任务都不动_冻结保留_解封后应用一次() {
        PlayerState state = bagOf(stack(501, POTION, 5, 0)).toBuilder().setPets(PetState.newBuilder().setActivePetId(PET)
                .addPets(PetEntry.newBuilder().setPetId(PET).setPetTableId(1).setLevel(1).setHealth(100).setMana(50))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, state);
        watchKills(player);
        assertThat(f.currency.block(player, Wallet.GOLD)).isZero();
        f.fighting(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        BattleSettlementData settlement = base(X)
                .addPets(BattlePetSettlementData.newBuilder().setPetId(PET).setHealth(300).setMana(200))
                .addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).build();
        f.store(settlement);
        f.sink.clear();
        f.locks.clearCalls();
        f.audit.currencies.clear();
        f.audit.items.clear();
        Pet pet = player.pets().find(PET);

        SceneBattleReply refused = delivered(settlement);

        assertThat(refused.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(refused.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
        assertThat(f.gold(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);
        assertThat(pet.health()).isEqualTo(100);
        assertThat(pet.mana()).isEqualTo(50);
        assertThat(kills(player)).isZero();
        assertThat(held(player, POTION)).isEqualTo(5);
        assertThat(held(player, DROP)).isZero();
        assertThat(player.battle().freeze()).as("延后：冻结保留").isSameAs(freeze);
        assertThat(player.battleLedger().has(X)).as("没有应用就不登记").isFalse();
        assertThat(f.messageIds(player)).as("不推 184 / 150").isEmpty();
        assertThat(f.locks.calls()).as("不续锁、不销账").isEmpty();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.repo.pendingProgress()).as("没有改动，不压存盘").isZero();
        assertThat(f.audit.currencies).isEmpty();
        assertThat(f.audit.items).isEmpty();
        assertThat(f.settlementsCounted("online", "deferred_currency")).isEqualTo(1);

        assertThat(f.currency.unblock(player, Wallet.GOLD)).isZero();
        SceneBattleReply applied = delivered(settlement);

        assertThat(applied.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(player.attributes().mana()).isEqualTo(20);
        assertThat(pet.health()).isEqualTo(300);
        assertThat(pet.mana()).isEqualTo(200);
        assertThat(kills(player)).isEqualTo(2);
        assertThat(held(player, POTION)).isEqualTo(3);
        assertThat(held(player, DROP)).isEqualTo(3);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.messageIds(player)).containsExactly(f.petListChangedId, f.battleEndId);

        assertThat(delivered(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).as("延后不是丢弃，也不是多发").isEqualTo(GOLD);
        assertThat(kills(player)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ :157

    /**
     * 基线 {@code :157}：真实重登——资产与账本来自同一份落库记录，账本和金币一起回来。登录补应用与随后发件箱的再一次投递都被挡住，
     * 且不碰玩家新一局（702）的冻结与锁：销账脚本只删 701 的记录，锁的 b 是 702，不动。
     */
    @Test
    void t157_重登读到旧记录_不再发奖_不碰新一局的冻结与锁() {
        PlayerState saved = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(GOLD))
                .setBattleLedger(ledgerOf(X, 1234)).build();
        BattleSettlementData old = base(X).build();
        f.store(old);
        long deadline = f.deadline();
        f.locks.putLock(PLAYER, 702, NODE, BattleRedis.STATE_FIGHTING, deadline, 0, 360);

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, saved);
        f.drain();

        assertThat(f.settlementsCounted("login", "already_applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("login", "applied")).isZero();
        assertThat(f.gold(player)).as("不再发奖").isEqualTo(GOLD);
        assertThat(player.attributes().health()).as("气血不被旧结算改写").isEqualTo(FULL_HEALTH);
        assertThat(f.battleEnds(player)).as("已应用过的不推 150").isEmpty();
        assertThat(f.audit.currencies).isEmpty();
        Call ack = f.locks.last(Op.ACK);
        assertThat(ack.battleId()).isEqualTo(X);
        assertThat(ack.lastReply()).as("只删了 701 的记录（位 1），702 的锁没放（位 2 不置）").isEqualTo(1L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(player.battleLedger().has(X)).as("销账回来，条目摘掉").isFalse();
        BattleFreeze current = player.battle().freeze();
        assertThat(current).as("新一局按锁重建").isNotNull();
        assertThat(current.battleId()).isEqualTo(702);
        assertThat(current.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(702L);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(702);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).as("701 的 HOLD 没碰 702 的锁").isEqualTo(360);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);

        // 发件箱的再一次投递（两次）：玩家已在下一局，丢弃并销账，不动 702
        for (int delivery = 0; delivery < 2; delivery++) {
            f.locks.clearCalls();
            SceneBattleReply again = delivered(old);

            assertThat(again.getStatus()).as("delivery %s", delivery).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
            assertThat(again.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
            assertThat(f.gold(player)).as("delivery %s", delivery).isEqualTo(GOLD);
            assertThat(player.battle().freeze()).as("delivery %s：新一局的冻结还是那个对象", delivery).isSameAs(current);
            assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(702);
            assertThat(f.locks.ops()).containsExactly(Op.ACK);
            assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
            assertThat(f.locks.last(Op.ACK).lastReply()).as("记录早已销掉、锁不是这一局").isEqualTo(0L);
        }
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.settlementsCounted("online", "discarded_mismatch")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ :185

    /**
     * 基线 {@code :185} 的 Java 对应。基线：进程内应用缓存说「应用过」、实体账本里却没有（那次应用没落盘、实体被重建）→ 这一次投递必须重新应用，且只能一次。
     * Java 没有应用缓存，同一情形是：实例 A 应用后，在线存盘被归属围栏拒绝（FENCED）→ A 被移除并踢掉，内存里的奖励连同账本条目一起作废，
     * 待结算记录仍在 Redis（没落盘就没销账）→ 下一个持有者 B 进场时恰好应用一次。
     */
    @Test
    void t185_实例A应用后存盘被围栏拒绝被移除_实例B进场恰好应用一次() {
        ScenePlayer a = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).build();
        f.store(settlement);
        assertThat(delivered(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(a)).isEqualTo(GOLD);
        assertThat(a.battleLedger().has(X)).isTrue();
        assertThat(f.repo.pendingProgress()).isEqualTo(1);

        f.repo.takeProgress().complete(ProgressResult.FENCED);
        f.drain();

        assertThat(f.world.playerById(PLAYER)).as("A 已失去数据归属：被移除").isNull();
        assertThat(f.sink.kicks()).hasSize(1);
        assertThat(f.locks.count(Op.ACK)).as("没落盘就没销账").isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("待结算记录还在").isTrue();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);

        // B：新的归属（epoch 2），库里是 A 应用之前的样子（A 的那笔写被围栏拒绝了）
        ScenePlayer b = f.load(SESSION + 1, PLAYER, 2, f.scene1, PlayerState.getDefaultInstance());
        assertThat(f.gold(b)).isZero();
        f.drain();

        assertThat(b).isNotSameAs(a);
        assertThat(f.gold(b)).as("B 上恰好应用一次").isEqualTo(GOLD);
        assertThat(b.attributes().health()).isEqualTo(80);
        assertThat(b.battleLedger().has(X)).isTrue();
        assertThat(f.battleEnds(b)).hasSize(1);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(b.inBattle()).as("这一局已有结算记录，不按锁重建").isFalse();

        // 再投一次：账本挡住
        assertThat(delivered(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(b)).isEqualTo(GOLD);
        assertThat(f.battleEnds(b)).hasSize(1);

        // B 落盘后才销账；之后的重投（墓碑挡住再落库，投递本身按「锁不在」丢弃）也不会再应用
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(b.battleLedger().has(X)).isFalse();
        assertThat(f.store(settlement)).as("已销账的局不再落库").isEqualTo(BattleRedis.STORE_ALREADY_SETTLED);
        assertThat(delivered(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(b)).isEqualTo(GOLD);
        assertThat(f.audit.currencies).as("两条金币流水：A 的那条随实例作废，B 的这条落了盘").hasSize(2);
    }

    // ------------------------------------------------------------------ :203

    /**
     * 基线 {@code :203}（缺 {@code BaseAttributesComp} → 返回 false、金币不提前入账、补上组件后可重试）<b>在 Java 不适用</b>：
     * {@code ScenePlayer} 构造时就建好属性对象（{@code PlayerAttributes.empty()} 或从存档恢复），没有「缺属性组件」这个状态，
     * 应用里也就没有对应的失败分支。这里只钉住前提：一个从没写过属性的全新号，属性对象在、进场规整后有上限，结算可以直接应用。
     */
    @Test
    void t203_不适用_Java的玩家实例总有属性对象() {
        ScenePlayer fresh = f.enter(SESSION, PLAYER);

        assertThat(fresh.attributes()).isNotNull();
        assertThat(BattleFixture.persistentState(fresh).hasAttribute()).as("从没写过属性的新号").isFalse();
        assertThat(fresh.attributes().derived().maxHealth()).isEqualTo(FULL_HEALTH);

        assertThat(apply(fresh, base(X).build())).isEqualTo(Result.APPLIED);
        assertThat(f.gold(fresh)).isEqualTo(GOLD);
        assertThat(fresh.attributes().health()).isEqualTo(80);
    }

    // ------------------------------------------------------------------ :213

    /** 基线 {@code :213}：归属不符（或 battle_id 为 0）的结算不应用、不销账、不登记账本——随后合法的那份照常应用。 */
    @Test
    void t213_错玩家的结算不应用不销账_不毒化随后合法的重投() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        watchKills(player);

        Result wrong = apply(player, base(X).setPlayerId(PLAYER + 1).build());
        Result zero = apply(player, base(0).build());

        assertThat(wrong).isEqualTo(new Result(Outcome.DISCARDED, SettlementResult.DISCARDED_INVALID));
        assertThat(zero).isEqualTo(new Result(Outcome.DISCARDED, SettlementResult.DISCARDED_INVALID));
        assertThat(f.gold(player)).isZero();
        assertThat(kills(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.battleLedger().has(X)).as("没有把这一局记成已应用").isFalse();
        assertThat(player.battleLedger().size()).isZero();
        assertThat(f.locks.calls()).as("不销账").isEmpty();
        assertThat(f.repo.pendingProgress()).isZero();

        assertThat(apply(player, base(X).build())).isEqualTo(Result.APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(kills(player)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ :233

    /**
     * 基线 {@code :233}：「这一局已应用」与资产写在同一份记录里。应用成功后账本条目进入要持久化的状态（{@code toSave()} 的内容），
     * 并立刻压一次存盘——那一笔写里金币与账本条目同在。
     */
    @Test
    void t233_应用后账本条目进入写回内容_与金币在同一笔存盘里() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        long appliedAt = f.clock.epochMillis();

        assertThat(delivered(base(X).build()).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);

        BattleLedgerEntry entry = BattleLedgerEntry.newBuilder().setBattleId(X).setAppliedAtMs(appliedAt).build();
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(BattleFixture.persistentState(player).getBattleLedger().getAppliedList()).containsExactly(entry);
        assertThat(f.repo.pendingProgress()).as("应用成功后立刻压一次存盘，且只有一笔").isEqualTo(1);
        PendingProgress write = f.repo.takeProgress();
        assertThat(write.save().ownerEpoch()).as("带归属围栏").isEqualTo(1);
        assertThat(write.save().state().getBattleLedger().getAppliedList()).containsExactly(entry);
        assertThat(write.save().state().getCurrency().getBalancesList()).as("金币在同一笔写里").startsWith(GOLD);
        assertThat(write.save().state().getVitals().getHealth()).isEqualTo(80);
        assertThat(BattleLedger.persistedHas(player.persistedState(), X)).as("还没落盘").isFalse();

        write.complete(ProgressResult.SAVED);

        assertThat(BattleLedger.persistedHas(player.persistedState(), X)).as("落库快照里有了").isTrue();
    }

    // ------------------------------------------------------------------ :245

    /**
     * 基线 {@code :245}：重启 / 实例重建之后，「已应用」只能靠从库里回来的账本知道。带账本加载、待结算记录还在 → 登录补应用命中账本：
     * 不发金币、不改气血、不推进任务、不推 150，只销账。
     */
    @Test
    void t245_带账本加载_待结算记录还在_按已应用处理_不发奖不推150_只销账() {
        long battle = 7031;
        f.store(base(battle).build());
        PlayerState saved = PlayerState.newBuilder().setBattleLedger(ledgerOf(battle, 1234)).build();

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, saved);
        f.drain();

        assertThat(f.settlementsCounted("login", "already_applied")).isEqualTo(1);
        assertThat(f.gold(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.attributes().mana()).isEqualTo(FULL_MANA);
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.audit.currencies).isEmpty();
        assertThat(f.repo.pendingProgress()).as("没有新改动").isZero();
        assertThat(f.locks.ops()).as("加载自库的条目天然已落盘：当场销账").containsExactly(Op.ENTER_READ, Op.HOLD, Op.ACK);
        assertThat(f.locks.hasSettlement(PLAYER, battle)).isFalse();
        assertThat(player.battleLedger().has(battle)).isFalse();
        assertThat(f.acks("login", "released")).isEqualTo(1);
    }

    /** 同上，但销账一直失败（账本条目留着）：之后的在线投递同样被这份从库里回来的账本挡住，回 ALREADY_APPLIED。 */
    @Test
    void t245_带账本加载_销账失败条目保留_之后的投递仍被账本挡住() {
        long battle = 7031;
        BattleSettlementData settlement = base(battle).build();
        f.store(settlement);
        f.locks.putLock(PLAYER, battle, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.locks.failAlways(Op.ACK, new RuntimeException("销账脚本失败"));
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBattleLedger(ledgerOf(battle, 1234)).build());
        assertThat(player.battleLedger().has(battle)).as("销账失败，条目保留").isTrue();
        assertThat(player.inBattle()).as("锁指向一局已有结算记录的局：不重建").isFalse();
        assertThat(apply(player, settlement)).isEqualTo(Result.ALREADY);

        SceneBattleReply reply = delivered(settlement);

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(BattleFixture.persistentState(player).getBattleLedger().getAppliedList())
                .as("命中不刷新时间戳").containsExactly(BattleLedgerEntry.newBuilder().setBattleId(battle).setAppliedAtMs(1234).build());
    }

    // ------------------------------------------------------------------ :262 / :282

    /**
     * 基线 {@code :262}：冻结期必须整笔延后，而不是只挡金币和道具。一份「只有气血 + 击杀事实、没有金币也没有掉落」的结算（打空怪，完全正常）
     * 在交出冻结中同样不应用：任务不推进、气血不改、账本不登记、不压存盘；解冻后照常应用——延后不是丢弃。
     */
    @Test
    void t262_交出冻结中_没有金币也没有道具的结算同样整笔延后_解冻后照常应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        watchKills(player);
        BattleSettlementData settlement = base(X).setGoldGain(0).build();
        PendingHandOff handOff = f.freezeForHandOff(player);
        f.locks.clearCalls();

        Result frozen = apply(player, settlement);
        SceneBattleReply reply = delivered(settlement);

        assertThat(frozen).isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_FROZEN));
        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(kills(player)).isZero();
        assertThat(player.attributes().health()).isEqualTo(FULL_HEALTH);
        assertThat(player.battleLedger().has(X)).as("这一局绝不能被登记").isFalse();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(f.locks.calls()).as("零 Redis 调用").isEmpty();
        assertThat(f.messageIds(player)).doesNotContain(f.battleEndId);

        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();
        assertThat(player.frozen()).isFalse();

        assertThat(apply(player, settlement)).isEqualTo(Result.APPLIED);
        assertThat(kills(player)).isEqualTo(2);
        assertThat(player.attributes().health()).isEqualTo(80);
        assertThat(f.gold(player)).isZero();
        assertThat(player.battleLedger().has(X)).as("金币为 0 也走过 d 步：照样登记").isTrue();
    }

    /**
     * 基线 {@code :282}：归属交接在途时写盘被跳过，此时应用等于改了一份永远落不了盘的内存态，必须整笔延后。Java 的跨节点换图与跨 zone 交接
     * 共用同一个 FREEZING 状态。带金币、消耗、掉落的结算：一样都不动。
     */
    @Test
    void t282_交出冻结中_带金币与道具的结算整笔延后_无金币无道具() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 5, 0)));
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = base(X).addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).build();
        f.store(settlement);
        f.freezeForHandOff(player);
        f.locks.clearCalls();
        f.sink.clear();
        PlayerState before = BattleFixture.persistentState(player);

        SceneBattleReply reply = delivered(settlement);

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(apply(player, settlement)).isEqualTo(new Result(Outcome.DEFERRED, SettlementResult.DEFERRED_FROZEN));
        assertThat(f.gold(player)).isZero();
        assertThat(held(player, POTION)).isEqualTo(5);
        assertThat(held(player, DROP)).isZero();
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(BattleFixture.persistentState(player)).as("冻结中玩家的可变状态与冻结快照一致").isEqualTo(before);
        assertThat(f.audit.currencies).isEmpty();
        assertThat(f.audit.items).isEmpty();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.settlementsCounted("online", "deferred_frozen")).isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录留着，等重投或目标节点补应用").isTrue();
    }

    // ------------------------------------------------------------------ :295 / :308

    /**
     * 基线 {@code :295}：「已应用但未落盘」时销账必须延后——这一刻销账，崩溃即永久丢奖励。冻结照常解除（玩家不会被卡在已结束的战斗里），
     * 锁续到 180 s 留到落盘，账本条目不摘，零 ACK。存盘失败后仍不销账。
     */
    @Test
    void t295_已应用但未落盘_不销账_冻结照常解除_锁留到落盘() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).build();
        f.store(settlement);
        // 让锁的剩余 TTL 短于 180 s，看得出 HOLD 把它续上来了
        f.advance(BattleFixture.BATTLE_MILLIS - 5_000);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(65);
        f.locks.clearCalls();

        assertThat(delivered(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);

        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).as("冻结当场解除").isFalse();
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.locks.ops()).as("只续锁，不销账").containsExactly(Op.HOLD);
        assertThat(f.locks.last(Op.HOLD).longArg("hold")).isEqualTo(BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC).isEqualTo(180);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(180);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.repo.pendingProgress()).as("应用压的那一笔在途，销账延后不再压第二笔").isEqualTo(1);
        assertThat(f.acks("apply", "deferred")).isEqualTo(1);
        assertThat(f.acks("apply", "released")).isZero();

        // 存盘失败：库里是什么不再确定，仍不销账
        f.completeSaves(ProgressResult.FAILED);

        assertThat(player.persistedState()).isNull();
        assertThat(f.locks.count(Op.ACK)).isZero();
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
    }

    /**
     * 基线 {@code :308}：落库快照里已有这一局 = 字节确实在盘上，走销账。在线存盘 SAVED 的回调里当场 ACK（快路径）：删记录、放锁，
     * 成功回来后才 forget 账本条目（随下一次存盘从库里去掉）；放了锁再补一次组队跟随。
     */
    @Test
    void t308_落盘回调里销账_删记录放锁_成功后才摘账本条目() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).build();
        f.store(settlement);
        delivered(settlement);
        f.locks.clearCalls();
        f.follows.clear();
        f.locks.hold(Op.ACK);

        f.repo.takeProgress().complete(ProgressResult.SAVED);

        assertThat(BattleLedger.persistedHas(player.persistedState(), X)).isTrue();
        Call ack = f.locks.take(Op.ACK);
        assertThat(ack.battleId()).isEqualTo(X);
        assertThat(player.battleLedger().has(X)).as("销账的结局回来之前不摘").isTrue();
        assertThat(f.follows.freezeCleared).isEmpty();

        ack.complete();
        f.drain();

        assertThat(ack.lastReply()).as("位 1 删了记录 + 位 2 放了锁").isEqualTo(3L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().has(X)).as("成功后 forget").isFalse();
        assertThat(BattleFixture.persistentState(player).hasBattleLedger()).as("下一次存盘不再带这一条").isFalse();
        assertThat(f.acks("persisted", "released")).isEqualTo(1);
        assertThat(f.follows.freezeCleared).as("锁真正放掉时补一次组队跟随").containsExactly(PLAYER);
        assertThat(f.repo.pendingProgress()).as("没有不 durable 的条目，不链式重存").isZero();
    }

    /**
     * 销账回调的实例守卫（§7.12「实例仍是这个」才动账本、才补跟随）：销账在途时同 epoch 重进，旧实例的销账回来时它已不在世界里——
     * 不得再改它的账本，也不得拿这个已被移除的实例去补组队跟随。新实例沿用过来的条目由它自己的落盘与销账收掉。
     */
    @Test
    void t308_销账在途时同epoch重进_旧实例的销账回来_不动旧实例不补跟随_新实例的条目由它自己销() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).build();
        f.store(settlement);
        delivered(settlement);
        f.locks.hold(Op.ACK);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        Call staleAck = f.locks.take(Op.ACK);
        assertThat(staleAck.battleId()).isEqualTo(X);

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();

        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.battleLedger().has(X)).as("账本条目随旧实例内存沿用").isTrue();
        assertThat(f.settlementsCounted("login", "already_applied")).as("恢复读带回的记录命中账本").isEqualTo(1);
        assertThat(f.locks.pending(Op.ACK)).as("新实例的条目还没落盘，没有发销账：在途的只有旧实例那一次").containsExactly(staleAck);
        f.follows.clear();

        staleAck.complete();
        f.drain();

        assertThat(staleAck.lastReply()).as("脚本本身照常删了记录、放了锁").isEqualTo(3L);
        assertThat(f.acks("persisted", "released")).isEqualTo(1);
        assertThat(old.battleLedger().has(X)).as("已被移除的旧实例不再被改动").isTrue();
        assertThat(f.follows.freezeCleared).as("不拿已被移除的实例去补跟随").isEmpty();
        assertThat(fresh.battleLedger().has(X)).as("新实例的条目等它自己的销账").isTrue();

        // 新实例那笔存盘落库 → 它自己销一次（记录与锁都已不在，返回 0）→ 摘条目；奖励始终只有一份
        f.locks.release(Op.ACK);
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);

        assertThat(f.locks.last(Op.ACK).lastReply()).isEqualTo(0L);
        assertThat(fresh.battleLedger().has(X)).isFalse();
        assertThat(f.gold(fresh)).isEqualTo(GOLD);
        assertThat(f.audit.currencies).as("金币流水只有旧实例应用时的那一条").hasSize(1);
    }

    /** 销账脚本失败：账本条目保留（它仍挡着重复发奖），reaper 下一轮重试，成功后才摘。 */
    @Test
    void t308_落盘后销账脚本失败_账本条目保留_reaper重试成功后才摘() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = base(X).build();
        f.store(settlement);
        delivered(settlement);
        f.locks.failNext(Op.ACK, new RuntimeException("Redis 超时"));

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);

        assertThat(f.acks("persisted", "error")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(delivered(settlement).getSettlement()).as("期间的重投仍被账本挡住")
                .isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);

        f.reap();

        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
    }

    /**
     * 第一笔存盘的快照拍在第二局应用之前：SAVED 回来时只有第一局 durable——只销第一局，并为第二局链式再压一笔；第二笔落盘后才销第二局。
     */
    @Test
    void t308_落盘时还有不在这份快照里的条目_只销已落盘的_再压一笔存盘() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(apply(player, base(X).build())).isEqualTo(Result.APPLIED);
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        assertThat(apply(player, base(702).build())).isEqualTo(Result.APPLIED);
        assertThat(f.repo.pendingProgress()).as("同一玩家至多一笔在途").isEqualTo(1);

        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();

        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).as("只销已落盘的那一局").containsExactly(X);
        assertThat(player.battleLedger().battleIds()).containsExactly(702L);
        assertThat(f.repo.pendingProgress()).as("为还没落盘的条目再压一笔").isEqualTo(1);

        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();

        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).containsExactly(X, 702L);
        assertThat(player.battleLedger().size()).isZero();
    }

    // ------------------------------------------------------------------ 道具 :406 – :455

    /** 基线 {@code :406}：消耗被扣、掉落进人物背包，金币在同一次应用里照常入账。 */
    @Test
    void t406_消耗被扣_掉落进人物背包_金币照常入账() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 5, 0)));
        f.fighting(PLAYER, X);
        f.audit.items.clear();

        SceneBattleReply reply = delivered(base(X).addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).build());

        assertThat(reply.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(held(player, POTION)).isEqualTo(3);
        assertThat(held(player, DROP)).isEqualTo(3);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.audit.items).extracting(i -> i.reason()).containsExactly(AssetAudit.Reason.ITEM_DESTROY, AssetAudit.Reason.ITEM_AWARD);
        BattleEndS2C end = f.battleEnds(player).get(0);
        assertThat(end.getSettlement().getItemsGainedList()).as("150 带着本人视角的整份结算").containsExactly(item(DROP, 3));
    }

    /** 基线 {@code :419}：账面消耗多于实际持有 → 按实际持有扣到 0，整笔结算照常成功（不足不报错）。 */
    @Test
    void t419_消耗按实际持有夹紧_扣到0_整笔照常成功() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 1, 0)));

        Result result = apply(player, base(X).addItemsConsumed(item(POTION, 4)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(held(player, POTION)).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).itemCount()).as("扣空的实例当场回收").isZero();
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_clamped")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isTrue();
    }

    /** 基线 {@code :431}：消耗的道具玩家一件都没有 → 不让结算失败；掉落与金币照常。 */
    @Test
    void t431_消耗的道具玩家没有_不报错_掉落与金币照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        Result result = apply(player, base(X).addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 1)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(held(player, POTION)).isZero();
        assertThat(held(player, DROP)).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_clamped")).isEqualTo(1);
        assertThat(f.audit.items).as("没有销毁流水，只有掉落入包").singleElement().satisfies(entry -> {
            assertThat(entry.gained()).isTrue();
            assertThat(entry.configId()).isEqualTo(DROP);
        });
    }

    /**
     * 基线 {@code :442}：反向校验。快照只会把战斗道具放进副本，结算的消耗里出现别的物品只可能是陈旧的 battle 节点或伪造——
     * 放行等于让战斗服点名销毁玩家的任意物品。拒扣、一件不少，其余照常。
     */
    @Test
    void t442_消耗里的非战斗道具_拒扣_一件不少_其余照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, NON_BATTLE_ITEM, 3, 0), stack(502, POTION, 5, 1)));
        assertThat(f.tables.battleUsable(NON_BATTLE_ITEM)).isFalse();

        Result result = apply(player,
                base(X).addItemsConsumed(item(NON_BATTLE_ITEM, 3)).addItemsConsumed(item(POTION, 1)).build());

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(held(player, NON_BATTLE_ITEM)).as("一件都没被扣").isEqualTo(3);
        assertThat(held(player, POTION)).as("同一份结算里合法的消耗照扣").isEqualTo(4);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.count("xm.scene.battle.items", "kind", "consume_rejected")).isEqualTo(1);
        assertThat(f.audit.items).extracting(i -> i.configId()).as("没有非战斗道具的销毁流水").containsExactly(POTION);
    }

    /** 基线 {@code :455}：同一 (玩家, 局) 的重投由持久账本挡掉——道具不再扣一次、也不再发一次。 */
    @Test
    void t455_重复应用_道具不重扣不重发() {
        ScenePlayer player = f.enter(SESSION, PLAYER, bagOf(stack(501, POTION, 5, 0)));
        BattleSettlementData settlement = base(X).addItemsConsumed(item(POTION, 2)).addItemsGained(item(DROP, 3)).build();

        assertThat(apply(player, settlement)).isEqualTo(Result.APPLIED);
        int flows = f.audit.items.size();
        assertThat(apply(player, settlement)).isEqualTo(Result.ALREADY);
        assertThat(apply(player, settlement)).isEqualTo(Result.ALREADY);

        assertThat(held(player, POTION)).isEqualTo(3);
        assertThat(held(player, DROP)).isEqualTo(3);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.audit.items).as("没有新的道具流水").hasSize(flows);
    }
}
