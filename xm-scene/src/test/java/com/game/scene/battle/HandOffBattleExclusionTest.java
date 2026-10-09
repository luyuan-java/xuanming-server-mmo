package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.api.proto.WorldChannel;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.BattleSettlementData;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.MessageContent;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakeSwitchTargets.PendingSelect;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 回合制战斗冻结与 5.2 跨节点换图（交出冻结）的互斥（scene-battle-spec §7.13「世界内部」第 1、2 条与「与 5.2 的互斥一览」表、§10.5；
 * §13.2 HandOffBattleExclusionTest 一行逐条）。冻结快照与内存一致是交出的资产不变量：交出冻结期间战斗这边不得改任何可持久化状态。
 * <pre>
 * 当前状态 ＼ 事件      63 远端去向        prepareBattle   结算到达                 确认到达
 * RESOLVING（不冻结）   3014               1006            照常（无冻结就按锁）      可以重建 FIGHTING；随后选中远端时中止换图、推 23 {3023}
 * FREEZING              3014               1006            DEFERRED，零副作用        只跑 CONFIRM，不挂冻结；目标节点进场恢复时重建
 * 战斗 PREPARING/FIGHTING  3023，先于 3014  1006            §7.10                    §7.7
 * 资产通道              27003（冻结）优先于 27002（战斗）
 * </pre>
 * 「目标节点」由同一个世界扮演（假 Redis 属于这一套装配）：交出提交后源实例已移除，用冻结快照 + 新 epoch 以 {@code transfer = true} 再进场。
 * 原地解冻后重跑恢复的细节（代际、PENDING 窗口）在 {@code BattleUnfreezeRecoveryTest}；备战在过期 RESOLVING 槽上的放行在 {@code PrepareBattleTest}。
 */
class HandOffBattleExclusionTest {

    private static final int SESSION = 11;
    /** 「目标节点」上的会话号（同一条 gate 会话改绑过去；这里换个号，避开源节点留下的交出墓碑）。 */
    private static final int TARGET_SESSION = 21;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;
    private static final String SECRET = "handoff-battle-test-secret-0123456789";

    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int INVALID_PARAMETER = 1005;
    private static final int ENTER_FAILED = 3023;
    private static final int CHANGING_SCENE = 3014;
    private static final int ATTRIBUTE_IN_BATTLE = 25011;
    private static final int ASSET_IN_BATTLE = 27002;
    private static final int ASSET_FROZEN = 27003;

    private final BattleFixture f = new BattleFixture();

    static Stream<Phase> phases() {
        return Stream.of(Phase.PREPARING, Phase.FIGHTING);
    }

    // ================================================================== 战斗中 → 不得起交出

    /** 战斗中的 63 指向别的节点上的场景：处理器第一步回 3023，不进 RESOLVING、不向 scene-manager 选目标。 */
    @ParameterizedTest
    @MethodSource("phases")
    void 战斗中63指向远端_回3023_不进RESOLVING_不选目标(Phase phase) throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        freeze(phase);

        long requestId = f.enterScene(player, BattleFixture.REMOTE_SCENE, 0);

        assertThat(enterSceneTip(player, requestId)).isEqualTo(ENTER_FAILED);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.targets.pendingCount()).as("没有向 scene-manager 选目标").isZero();
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(phase);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "enter_scene")).isEqualTo(1);
        assertThat(switchResolves("in_battle")).as("63 已先拦下，没走到 begin 的闸").isZero();
    }

    /**
     * {@code beginRemoteSwitch} 自己也拒绝战斗中的玩家（handoff-spec :106 的契约；以后新增的交出发起方不必各自记得判战斗）：
     * 不进 RESOLVING、不选目标，计 {@code switch_resolve{in_battle}}。解冻后同一个调用照常进 RESOLVING。
     */
    @ParameterizedTest
    @MethodSource("phases")
    void beginRemoteSwitch直接调用_战斗中的玩家被拒_不进RESOLVING(Phase phase) {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        freeze(phase);

        WorldTestAccess.beginRemoteSwitch(f.world, player, BattleFixture.REMOTE_SCENE, 0);

        assertThat(player.switchPhase()).as("不进 RESOLVING").isEqualTo(SwitchPhase.NONE);
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(switchResolves("in_battle")).isEqualTo(1);
        assertThat(f.messageIds(player)).as("63 的应答由处理器负责，这里什么都不推").isEmpty();

        BattleFixture.setFreeze(player, null);
        WorldTestAccess.beginRemoteSwitch(f.world, player, BattleFixture.REMOTE_SCENE, 0);

        assertThat(player.switchPhase()).as("对照：不在战斗时照常发起").isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.targets.pendingCount()).isEqualTo(1);
        assertThat(switchResolves("in_battle")).isEqualTo(1);
    }

    // ================================================================== RESOLVING（选目标中，不冻结）

    /** 选目标中：备战 1006（零冻结、零 Redis 调用）；再发 63 回 3014；结算照常（无冻结就按锁）应用——之后拍的冻结快照会包含它。 */
    @Test
    void 选目标中_备战回1006_再发63回3014_结算按锁照常应用() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.resolveRemote(player);
        f.locks.clearCalls();

        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, 8);

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).as("拒绝时零 Redis 调用").isEmpty();
        assertThat(f.prepares("switching")).isEqualTo(1);
        assertThat(enterSceneTip(player, f.enterScene(player, BattleFixture.REMOTE_SCENE, 0))).isEqualTo(CHANGING_SCENE);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement());
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
        assertThat(player.switchPhase()).as("结算不动换图状态").isEqualTo(SwitchPhase.RESOLVING);
    }

    /** 63 镜像分支的取号在途同样占着 RESOLVING 槽（5.3）：备战照样 1006、零冻结、零 Redis 调用；槽清掉后放行。 */
    @Test
    void 镜像取号中_同样占RESOLVING槽_备战回1006_槽清掉后放行() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        WorldTestAccess.startMirrorResolving(player);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);

        CompletableFuture<PrepareBattleResponse> rejected = f.prepare(PLAYER, X);

        assertThat(BattleFixture.tipOf(BattleFixture.done(rejected))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("switching")).isEqualTo(1);

        WorldTestAccess.clearSwitch(player);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, X))).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
    }

    /**
     * 选目标期间迟到的确认按锁重建了 FIGHTING 冻结（RESOLVING 不冻结，挡不住）；随后选目标回来、选中<b>别的节点</b>：起交出前复查，
     * 中止换图（回 NONE）、推 23 {3023}、计 {@code switch_resolve{in_battle}}，<b>不</b>提交交出。战斗冻结原样。
     */
    @Test
    void 选目标期间迟到确认重建FIGHTING_随后选中远端_中止换图推3023_不提交交出() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        PendingSelect select = f.resolveRemote(player);
        f.sink.clear();

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).as("RESOLVING 不挡迟到重建").isNotNull();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.confirms("rebuilt")).isEqualTo(1);

        select.chosen(BattleFixture.TARGET_NODE, BattleFixture.REMOTE_SCENE, 2);

        assertThat(player.switchPhase()).as("换图中止").isEqualTo(SwitchPhase.NONE);
        assertThat(player.frozen()).isFalse();
        assertThat(f.repo.pendingHandOffs()).as("没有调 repository.handOff").isZero();
        assertThat(f.pushedTips(player)).containsExactly(ENTER_FAILED);
        assertThat(switchResolves("in_battle")).isEqualTo(1);
        assertThat(switchResolves("remote")).isZero();
        assertThat(player.battle().freeze()).as("战斗冻结不受影响").isSameAs(freeze);
        assertThat(player.scene()).isSameAs(f.scene1);
        assertThat(f.reconnectHints(player)).as("144 没有多推").containsExactly(X);
    }

    /** 同上，但选中的是<b>本节点</b>的另一个场景：照常同步换场景（同基线，同节点路由不查战斗），冻结随人走。 */
    @Test
    void 选目标期间迟到确认重建FIGHTING_随后选中本节点_照常换场景() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        PendingSelect select = f.resolveRemote(player);
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        f.sink.clear();

        select.chosen(BattleFixture.LOCAL_NODE, f.scene2.sceneId(), f.scene2.configId());

        assertThat(player.scene()).as("本节点分支照旧同步换场景").isSameAs(f.scene2);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(switchResolves("local")).isEqualTo(1);
        assertThat(switchResolves("in_battle")).isZero();
        assertThat(f.pushedTips(player)).isEmpty();
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(player.battle().freeze()).isSameAs(freeze);
    }

    // ================================================================== FREEZING（交出事务在途）

    /**
     * 交出冻结中：备战 1006；再发 63 回 3014；确认只跑 CONFIRM（续锁、标 F）不挂冻结；全程内存与冻结快照一致。交出提交后，
     * 目标节点的进场恢复据锁重建 FIGHTING 并推 144（源节点没挂的冻结在这里补上）。
     */
    @Test
    void 交出冻结中_备战回1006_再发63回3014_确认只续锁不挂冻结_交出提交后目标节点按锁重建并推144() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long deadline = f.deadline();
        PendingHandOff handOff = freezeWithPreparingLockLeft(player, deadline);

        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, 8);
        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(f.prepares("switching")).isEqualTo(1);
        assertThat(f.locks.calls()).as("备战被拒时零 Redis 调用").isEmpty();
        assertThat(enterSceneTip(player, f.enterScene(player, f.scene2.sceneId(), f.scene2.configId()))).isEqualTo(CHANGING_SCENE);

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(player.inBattle()).as("两种冻结互斥：交出冻结中不挂战斗冻结").isFalse();
        assertThat(f.locks.ops()).as("只跑一段 CONFIRM").containsExactly(Op.CONFIRM);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo(BattleRedis.STATE_FIGHTING);
        assertThat(f.locks.lock(PLAYER)).containsEntry(BattleRedis.FIELD_DEADLINE, String.valueOf(deadline));
        assertThat(f.confirms("frozen_extended")).isEqualTo(1);
        assertThat(f.reconnectHints(player)).as("源节点不推 144").isEmpty();
        assertThat(BattleFixture.persistentState(player)).as("冻结快照与内存一致").isEqualTo(handOff.frozen().state());

        handOff.complete(new HandOffOutcome.HandedOff(2));

        assertThat(f.count("xm.scene.transfer.post.freeze.mutations")).as("冻结期间没有任何入口改过状态").isZero();
        assertThat(f.sink.transfers()).hasSize(1);
        assertThat(f.world.playerById(PLAYER)).as("源实例已移除").isNull();

        ScenePlayer onTarget = enterOnTargetNode(handOff.frozen(), 2);

        BattleFreeze rebuilt = onTarget.battle().freeze();
        assertThat(rebuilt).as("目标节点进场恢复据锁重建").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.battleId()).isEqualTo(X);
        assertThat(rebuilt.deadlineMs()).isEqualTo(deadline);
        assertThat(f.reconnectHints(onTarget)).containsExactly(X);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(f.hints("login")).isEqualTo(1);
    }

    /**
     * 交出冻结中到达的结算：DEFERRED、零副作用（金币、背包、账本、Redis 都不动，快照与内存一致）。交出提交后，待结算记录由目标节点的
     * 进场恢复补应用恰好一次并推 150；这一局已有结算记录，不再按锁重建。
     */
    @Test
    void 交出冻结中_结算延后零副作用_快照与内存一致_交出提交后目标节点进场补应用恰好一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        PendingHandOff handOff = freezeWithPreparingLockLeft(player, f.deadline());
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        f.store(settlement());
        f.locks.clearCalls();
        int audits = f.audit.currencies.size() + f.audit.items.size();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement());

        assertThat(reply).as("当场回，不等 Redis").isCompleted();
        assertThat(BattleFixture.done(reply).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(f.locks.calls()).as("零 Redis 调用：不 HOLD、不销账").isEmpty();
        assertThat(f.gold(player)).isZero();
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.audit.currencies.size() + f.audit.items.size()).isEqualTo(audits);
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.settlementsCounted("online", "deferred_frozen")).isEqualTo(1);
        assertThat(BattleFixture.persistentState(player)).as("冻结快照与内存一致").isEqualTo(handOff.frozen().state());
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录留在 Redis").isTrue();

        handOff.complete(new HandOffOutcome.HandedOff(2));
        assertThat(f.count("xm.scene.transfer.post.freeze.mutations")).isZero();
        ScenePlayer onTarget = enterOnTargetNode(handOff.frozen(), 2);

        assertThat(f.gold(onTarget)).as("目标节点补应用恰好一次").isEqualTo(GOLD);
        assertThat(f.battleEnds(onTarget)).hasSize(1);
        assertThat(onTarget.battleLedger().has(X)).isTrue();
        assertThat(onTarget.inBattle()).as("这一局已有结算记录，不按锁重建").isFalse();
        assertThat(f.reconnectHints(onTarget)).isEmpty();
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
    }

    /**
     * 资产通道：交出冻结中是 27003（第 5 步），<b>不是</b>战斗的 27002（第 7 步）。两种冻结互斥，正常流程里不会同时成立；
     * 这里硬摆一个战斗冻结钉住判定次序（冻结先判），客户端入口同理（168 回冻结的 1005 而不是 25011）。原地解冻后才轮到 27002。
     */
    @Test
    void 交出冻结中_资产通道回27003不是27002_冻结先判_原地解冻后才是27002() throws Exception {
        AssetOpService assetOps = new AssetOpService(f.world, f.currency, f.bags, new AssetOpAuth(caller -> SECRET), f.clock,
                f.sceneMetrics);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.currency.add(player, Wallet.GOLD, 1000, Reason.GM_GRANT).ok()).isTrue();
        // 锁在（这一局还在打），但本实例没有内存冻结：这是交出冻结期间战斗的正常形态
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        PendingHandOff handOff = f.freezeForHandOff(player);

        AssetOpResponse frozen = assetOps.handle(AssetRpc.DEBIT, signedDebit(1, 30));

        assertThat(frozen.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(frozen.getReason()).isEqualTo(ASSET_FROZEN);
        assertThat(f.count("xm.scene.frozen.rejections", "kind", "asset_op")).isEqualTo(1);

        // 纵深防御的次序：两种冻结（硬摆）同时成立时仍是冻结先判
        BattleFixture.setFreeze(player, new BattleFreeze(X, BattleFixture.BATTLE_NODE, Phase.FIGHTING, f.deadline(), 0, false));
        AssetOpResponse both = assetOps.handle(AssetRpc.DEBIT, signedDebit(2, 30));
        assertThat(both.getReason()).as("27003（冻结）优先于 27002（战斗）").isEqualTo(ASSET_FROZEN);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "asset")).isZero();
        assertThat(allocateTip(player)).as("客户端入口同理：冻结的 1005 先于战斗的 25011").isEqualTo(INVALID_PARAMETER);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "attribute")).isZero();
        assertThat(f.gold(player)).isEqualTo(1000);
        assertThat(BattleFixture.persistentState(player)).isEqualTo(handOff.frozen().state());

        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();

        assertThat(player.frozen()).isFalse();
        assertThat(player.inBattle()).isTrue();
        AssetOpResponse inBattle = assetOps.handle(AssetRpc.DEBIT, signedDebit(3, 30));
        assertThat(inBattle.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(inBattle.getReason()).isEqualTo(ASSET_IN_BATTLE);
        assertThat(allocateTip(player)).isEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(f.gold(player)).isEqualTo(1000);
    }

    /**
     * 销账结果在交出冻结期间回来：<b>不</b>把账本条目摘掉（冻结中玩家的可变状态必须与冻结快照一致），条目随冻结快照交给目标节点；
     * 交出提交时快照比对一致（{@code post_freeze_mutations} 为 0）。目标节点进场恢复为这一局（已应用、加载自库即 durable）再销一次账后摘除，
     * 不重复发奖、不再推 150。
     */
    @Test
    void 交出冻结中销账回来_不改账本_冻结快照与内存一致_交出提交后目标节点进场为已应用的局销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.hold(Op.ACK);
        CompletableFuture<SceneBattleReply> applied = f.deliver(settlement());
        f.drain();
        assertThat(BattleFixture.done(applied).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.completeSaves(ProgressResult.SAVED)).as("应用后压的那次存盘落库 → 快路径发出销账").isEqualTo(1);
        assertThat(f.locks.pending(Op.ACK)).hasSize(1);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(player.inBattle()).isFalse();

        // 销账在途时玩家发 63 去别的节点：选中远端 → 冻结，快照里带着账本条目
        PendingHandOff handOff = f.freezeForHandOff(player);
        assertThat(BattleLedger.persistedHas(handOff.frozen().state(), X)).as("冻结快照带着这一局的账本条目").isTrue();
        f.follows.clear();

        f.locks.take(Op.ACK).complete();
        f.drain();

        assertThat(f.acks("persisted", "released")).as("销账本身成功了（锁已放）").isEqualTo(1);
        assertThat(f.locks.lockBattleId(PLAYER)).isZero();
        assertThat(player.battleLedger().has(X)).as("冻结中不 forget").isTrue();
        assertThat(BattleFixture.persistentState(player)).as("冻结快照与内存一致").isEqualTo(handOff.frozen().state());

        handOff.complete(new HandOffOutcome.HandedOff(2));

        assertThat(f.count("xm.scene.transfer.post.freeze.mutations")).isZero();
        assertThat(f.count("xm.scene.transfers", "reason", "player", "result", "handed_off")).isEqualTo(1);
        assertThat(f.world.playerById(PLAYER)).isNull();
        f.locks.release(Op.ACK);
        f.locks.clearCalls();

        ScenePlayer onTarget = enterOnTargetNode(handOff.frozen(), 2);

        assertThat(f.locks.ops()).as("恢复读 → 为带过来的条目销账").containsExactly(Op.ENTER_READ, Op.ACK);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(f.acks("login", "not_ours")).as("源节点那次已经删过，这次什么都没删到").isEqualTo(1);
        assertThat(onTarget.battleLedger().has(X)).as("销账回来后摘除").isFalse();
        assertThat(f.gold(onTarget)).as("奖励随快照带过来，没有重复发").isEqualTo(GOLD);
        assertThat(f.battleEnds(onTarget)).isEmpty();
        assertThat(onTarget.inBattle()).isFalse();
        assertThat(onTarget.battle().recovery()).isEqualTo(Recovery.READY);
    }

    // ================================================================== 排空改派（5.1）

    /**
     * 排空中的频道改派在场玩家时跳过战斗中的（备战与战斗中都算），每次推进各计一次 {@code channel_relocations{in_battle}}；
     * 不在战斗的照常改派。解冻后下一次推进才改派，人走空了频道才销毁（D26）。
     */
    @Test
    void 排空改派_跳过战斗中的玩家_每次推进计in_battle_解冻后下一次推进才改派() {
        long sibling = 0x8000_0000_0000_0B02L;
        assertThat(f.world.applyChannelPlan(1, List.of(channel(f.scene1.sceneId(), 1, ChannelState.CHANNEL_ACTIVE),
                channel(sibling, 1, ChannelState.CHANNEL_ACTIVE), channel(f.scene2.sceneId(), 2, ChannelState.CHANNEL_ACTIVE)))).isTrue();
        ScenePlayer fighting = f.enter(SESSION, PLAYER);
        ScenePlayer preparing = f.enter(SESSION + 1, 1002);
        ScenePlayer idle = f.enter(SESSION + 2, 1003);
        f.fighting(PLAYER, X);
        f.prepared(1002, 8);
        Scene draining = f.scene1;

        f.world.applyChannelPlan(2, List.of(channel(draining.sceneId(), 1, ChannelState.CHANNEL_DRAINING),
                channel(sibling, 1, ChannelState.CHANNEL_ACTIVE), channel(f.scene2.sceneId(), 2, ChannelState.CHANNEL_ACTIVE)));

        assertThat(idle.scene().sceneId()).as("不在战斗的照常改派").isEqualTo(sibling);
        assertThat(fighting.scene()).as("战斗中的留在原频道").isSameAs(draining);
        assertThat(preparing.scene()).as("备战中的同样留下").isSameAs(draining);
        assertThat(relocations("in_battle")).isEqualTo(2);
        assertThat(relocations("same_map")).isEqualTo(1);
        assertThat(draining.playerCount()).isEqualTo(2);

        assertThat(f.world.drainStep()).as("还有人，不销毁").isZero();
        assertThat(relocations("in_battle")).as("下次推进再看，再计一次").isEqualTo(4);
        assertThat(fighting.scene()).isSameAs(draining);

        // 战斗中的由结算解冻、备战中的被取消
        f.deliver(BattleFixture.settlement(PLAYER, X, GOLD).build());
        f.cancel(1002, 8);
        f.drain();
        assertThat(fighting.inBattle()).isFalse();
        assertThat(preparing.inBattle()).isFalse();
        assertThat(fighting.scene()).as("解冻本身不改派，等下一次推进").isSameAs(draining);

        assertThat(f.world.drainStep()).as("人走空了，频道销毁").isEqualTo(1);

        assertThat(fighting.scene().sceneId()).isEqualTo(sibling);
        assertThat(preparing.scene().sceneId()).isEqualTo(sibling);
        assertThat(relocations("same_map")).isEqualTo(3);
        assertThat(relocations("in_battle")).isEqualTo(4);
    }

    // ================================================================== 工具

    private void freeze(Phase phase) {
        if (phase == Phase.PREPARING) {
            f.prepared(PLAYER, X);
        } else {
            f.fighting(PLAYER, X);
        }
        f.sink.clear();
        f.locks.clearCalls();
    }

    /**
     * 走真实流程摆出「交出冻结中、备战锁还在」：在本实例备战 → 备战到期被 reaper 摘掉冻结（锁保留到 TTL，给迟到的确认留重建余地）→
     * 玩家发 63 去别的节点 → 选中远端 → 冻结。返回挂起的交出；出站与已回复的 Redis 调用记录已清。
     */
    private PendingHandOff freezeWithPreparingLockLeft(ScenePlayer player, long deadline) {
        CompletableFuture<PrepareBattleResponse> prepared =
                f.battle.prepare(f.prepareRequest(PLAYER, X, deadline, f.prepareDeadline()));
        f.drain();
        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isZero();
        f.advance(BattleFixture.PREPARE_MILLIS + 1);
        f.reap();
        assertThat(player.inBattle()).as("备战到期：冻结已摘").isFalse();
        assertThat(f.locks.lockState(PLAYER)).as("锁保留到 TTL").isEqualTo(BattleRedis.STATE_PREPARING);
        PendingHandOff handOff = f.freezeForHandOff(player);
        f.sink.clear();
        f.locks.clearCalls();
        return handOff;
    }

    /**
     * 「目标节点」进场：库里是源节点提交的冻结快照（新 epoch），gate 以 {@code transfer = true} 带着新 epoch 进场；进场恢复跑完后返回新实例。
     */
    private ScenePlayer enterOnTargetNode(PlayerSave frozen, long newEpoch) {
        f.repo.put(new PlayerData(PLAYER, newEpoch, 3, 1, "look-" + PLAYER, frozen.level(), f.scene1.configId(), frozen.position(),
                frozen.state(), "玩家" + PLAYER));
        f.world.onPlayerEnter(BattleFixture.LINK, PlayerEnter.newBuilder().setSessionId(TARGET_SESSION).setPlayerId(PLAYER)
                .setSceneId(f.scene1.sceneId()).setOwnerEpoch(newEpoch).setTransfer(true).build());
        f.repo.completeAll();
        ScenePlayer onTarget = f.world.playerById(PLAYER);
        assertThat(onTarget).as("目标节点上的新实例").isNotNull();
        assertThat(f.count("xm.scene.transfer.enters", "result", "ok")).as("确实是交出进场").isEqualTo(1);
        f.drain();
        return onTarget;
    }

    private static BattleSettlementData settlement() {
        return BattleFixture.settlement(PLAYER, X, GOLD).build();
    }

    private int enterSceneTip(ScenePlayer player, long requestId) throws InvalidProtocolBufferException {
        MessageContent reply = f.pushes(player, Contracts.IDS.enterScene()).stream().filter(m -> m.getId() == requestId).findFirst()
                .orElseThrow(() -> new AssertionError("63 没有应答 request_id=" + requestId));
        return EnterSceneC2SResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId();
    }

    /** 发一条合法的 168（角色池 1、力量维度 103 加到 1 点），返回应答里的码。 */
    private int allocateTip(ScenePlayer player) throws InvalidProtocolBufferException {
        int messageId = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "AllocateAttributePoints");
        long requestId = f.request(player, messageId, AllocateAttributePointsRequest.newBuilder().setPoolId(1).putAllocated(103, 1).build());
        MessageContent reply = f.pushes(player, messageId).stream().filter(m -> m.getId() == requestId).findFirst()
                .orElseThrow(() -> new AssertionError("168 没有应答 request_id=" + requestId));
        return AllocateAttributePointsResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId();
    }

    private AssetOpRequest signedDebit(long seq, long amount) {
        AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT)
                .setSeq(seq).setStreamEpoch(1_700_000_000_000L).setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(Wallet.GOLD).setAmount(amount)))
                .build();
        return AssetOpSignatures.sign(AssetRpc.DEBIT, request, AssetOpSignatures.CALLER_GUILD, SECRET, f.clock.epochMillis());
    }

    private static WorldChannel channel(long sceneId, int configId, ChannelState state) {
        return WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(configId).setNodeId(BattleFixture.LOCAL_NODE).setState(state)
                .setKind(ChannelKind.CHANNEL_KIND_WORLD).build();
    }

    private double switchResolves(String result) {
        return f.count("xm.scene.switch.resolves", "result", result);
    }

    private double relocations(String result) {
        return f.count("xm.scene.channel.relocations", "result", result);
    }
}
