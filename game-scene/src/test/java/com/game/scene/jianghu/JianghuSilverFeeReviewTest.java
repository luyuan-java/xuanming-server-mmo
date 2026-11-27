package com.game.scene.jianghu;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.JianghuDuelPrepareFee;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.eBattleOutcome;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.battle.BattleLocks;
import com.game.scene.battle.BattleSettlementService;
import com.game.scene.battle.PlayerBattleService;
import com.game.scene.battle.PlayerBattleService.BattleTerminalVerdict;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.Wallet;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.WorldTestAccess;
import com.game.table.CommonErrorTip;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 江湖生死状普通战书银两单通道退费与 SQL 终局复核单测（PARITY 批 X / r2-jh#1 / r2-jh#2）：
 * <ul>
 *   <li>别的局待退未结清时拒绝新备战（kFeatureUnavailable），不覆盖旧标记；</li>
 *   <li>退费先入账再清标记，玩家冻结或超上限入账失败时保留标记；获取封禁不拦退费；</li>
 *   <li>取消备战忽略已废弃的 skip_jianghu_silver_refund=true，照常经 scene 单通道退还一次；</li>
 *   <li>reaper 触发 SQL 终局复核：PENDING 留标记，ABORTED / NOT_FOUND 退还并清标记，COMPLETE 且本地已应用时清标记兜底不退。</li>
 * </ul>
 */
final class JianghuSilverFeeReviewTest {

    private static ConfigTables shipped;

    @BeforeAll
    static void loadConfig() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        shipped = ConfigTables.load(dir);
    }

    private static final class Fixture {
        final ManualClock clock = new ManualClock();
        final FakePlayerRepository repo = new FakePlayerRepository();
        final RecordingSink sink = new RecordingSink();
        final CurrencyService currency;
        final BagService bags;
        final JianghuService jianghu;
        final PlayerBattleService battle;
        final SceneWorld world;
        final ScenePlayer player;
        final AtomicReference<BattleTerminalVerdict> nextVerdict = new AtomicReference<>(BattleTerminalVerdict.PENDING);
        final AtomicInteger reviewCalls = new AtomicInteger();

        Fixture() {
            AssetAudit noopAudit = org.mockito.Mockito.mock(AssetAudit.class);
            AtomicLong itemGuidGen = new AtomicLong(90_000);
            AtomicLong petGuidGen = new AtomicLong(70_000);
            com.game.scene.player.ItemGuids itemGuids = n -> {
                long[] ids = new long[n];
                for (int i = 0; i < n; i++) ids[i] = itemGuidGen.incrementAndGet();
                return ids;
            };
            com.game.scene.player.ItemGuids petGuids = n -> {
                long[] ids = new long[n];
                for (int i = 0; i < n; i++) ids[i] = petGuidGen.incrementAndGet();
                return ids;
            };
            this.currency = new CurrencyService(noopAudit);
            this.bags = new BagService(BagTables.from(shipped), itemGuids,
                    noopAudit, org.mockito.Mockito.mock(com.game.scene.audit.GainAnomalyDetector.class),
                    SceneMetrics.noop());
            this.jianghu = new JianghuService(currency, bags, () -> null, clock::epochMillis);
            PetService pets = new PetService(PetTables.from(shipped), currency,
                    petGuids, clock, new java.util.Random(1), SceneMetrics.noop());
            SceneBattleTables battleTables = SceneBattleTables.from(shipped);
            BattleSettlementService settlements = new BattleSettlementService(currency, bags, pets,
                    org.mockito.Mockito.mock(com.game.scene.mission.MissionService.class),
                    battleTables, SceneBattleMetrics.noop(), clock, 0)
                    .withJianghuService(jianghu);
            BattleLocks locks = new BattleLocks() {
                @Override
                public CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId,
                                                             long deadlineMs, long prepareDeadlineMs, long ttlSec) {
                    return CompletableFuture.completedFuture("0");
                }

                @Override
                public CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec) {
                    return CompletableFuture.completedFuture(Map.of());
                }

                @Override
                public CompletableFuture<Long> deletePreparingIfMatch(long playerId, long battleId) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<Long> deleteIfMatch(long playerId, long battleId) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<Long> cancelOffline(long playerId, long battleId) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<Long> hold(long playerId, long battleId, long holdSec) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<com.game.discovery.battle.BattleRedis.EnterRead> enterRead(long playerId) {
                    return CompletableFuture.completedFuture(new com.game.discovery.battle.BattleRedis.EnterRead(
                            Map.of(), -2L, java.util.List.of()));
                }

                @Override
                public CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String phase,
                                                     long deadlineMs, long prepareDeadlineMs) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<byte[]> readSettlement(long playerId, long battleId) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<Long> readLockBattleId(long playerId) {
                    return CompletableFuture.completedFuture(0L);
                }

                @Override
                public CompletableFuture<Long> deleteSettlementField(long playerId, byte[] rawFieldName) {
                    return CompletableFuture.completedFuture(1L);
                }

                @Override
                public CompletableFuture<Long> ack(long playerId, long battleId) {
                    return CompletableFuture.completedFuture(1L);
                }
            };
            this.battle = new PlayerBattleService(locks, settlements, battleTables, pets,
                    p -> BattleRouting.newBuilder().setSessionId(p.session().sessionId()).setZoneId(1).build(),
                    TeamFollow.NONE, SceneBattleMetrics.noop(), clock, Runnable::run, 0, 0)
                    .withJianghuService(jianghu)
                    .withBattleTerminalReviewer(bid -> {
                        reviewCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(nextVerdict.get());
                    });
            this.world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                    new AtomicLong(5000)::incrementAndGet, clock, SceneMetrics.noop(),
                    p -> {
                        bags.initializeOnLoad(p);
                        p.wallet().add(Wallet.GOLD, 2_000L);
                    }, PlayerSnapshots.NONE, null, TeamFollow.NONE, null, null, battle, null, null, null);
            battle.attach(world);
            Scene scene = world.createScene(1);
            repo.putNewPlayer(1001L, 1L);
            world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                    .setSessionId(11).setPlayerId(1001L).setSceneId(scene.sceneId()).setOwnerEpoch(1L).build());
            repo.completeAll();
            this.player = WorldTestAccess.player(world, 1L, 11);
        }

        PrepareBattleResponse prepareSilverDuel(long battleId, long inviteId) {
            long now = clock.epochMillis();
            return battle.prepare(PrepareBattleRequest.newBuilder()
                    .setPlayerId(player.playerId())
                    .setBattleId(battleId)
                    .setBattleNodeId(1)
                    .setDeadlineMs(now + 300_000L)
                    .setPrepareDeadlineMs(now + 30_000L)
                    .setMatchMode(com.game.battle.engine.BattleConstants.MATCH_MODE_JIANGHU_DUEL)
                    .setJianghuDuelFee(JianghuDuelPrepareFee.newBuilder()
                            .setDuelInviteId(inviteId)
                            .setWritTier(1)
                            .setSilverCost(500L))
                    .build()).join();
        }
    }

    @Test
    void 别局未结清拒新备战_取消忽略废弃skip标志单通道退费_获取封禁不挡退费() {
        Fixture f = new Fixture();
        // 1. 局 1001 备战成功扣 500 银两并登记待退标记
        PrepareBattleResponse p1 = f.prepareSilverDuel(1001L, 5001L);
        assertThat(p1.hasErrorMessage()).isFalse();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1_500L);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isEqualTo(1001L);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeAmount()).isEqualTo(500L);

        // 2. 强制清冻结模拟备战锁已丢但待退标记仍在：局 1002 必须被拒 kFeatureUnavailable 且旧标记 1001 不变
        f.clock.advanceMillis(31_000L);
        f.battle.reap(); // 仅解冻 PREPARING；reviewAfter = effectiveDeadline(30_000+...) 尚未到或 verdict=PENDING
        assertThat(f.player.inBattle()).isFalse();
        PrepareBattleResponse p2 = f.prepareSilverDuel(1002L, 5002L);
        assertThat(p2.hasErrorMessage()).isTrue();
        assertThat(p2.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable_VALUE);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isEqualTo(1001L);
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1_500L);

        // 3. 开启货币获取封禁后，终局 ABORTED 复核仍能退还 500 银两（退费豁免获取封禁）
        f.player.wallet().block(Wallet.GOLD);
        f.nextVerdict.set(BattleTerminalVerdict.ABORTED);
        f.clock.advanceMillis(600_000L);
        f.battle.reap();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(2_000L);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isZero();
        f.player.wallet().unblock(Wallet.GOLD);

        // 4. 新局 1003 备战后取消备战且携带废弃字段 skip_jianghu_silver_refund=true：scene 照常单通道退一次
        PrepareBattleResponse p3 = f.prepareSilverDuel(1003L, 5003L);
        assertThat(p3.hasErrorMessage()).isFalse();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1_500L);
        f.battle.cancel(CancelBattlePrepareRequest.newBuilder()
                .setPlayerId(1001L).setBattleId(1003L).setSkipJianghuSilverRefund(true).build()).join();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(2_000L);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isZero();
    }

    @Test
    void 冻结时退费不动标记_作废结算入账失败保留标记_复核NOT_FOUND退一次_COMPLETE本地已应用清标记不退() {
        Fixture f = new Fixture();
        assertThat(f.prepareSilverDuel(2001L, 6001L).hasErrorMessage()).isFalse();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(1_500L);

        // 玩家交接冻结期间退费必须拒绝且保留标记
        WorldTestAccess.startFreezing(f.player);
        assertThat(f.jianghu.refundPendingSilverFee(f.player, 2001L)).isFalse();
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isEqualTo(2001L);
        WorldTestAccess.clearSwitch(f.player);

        // VOID 结算入账因余额超上限失败时必须保留标记不吞银两
        f.player.wallet().add(Wallet.GOLD, Long.MAX_VALUE - f.player.wallet().balance(Wallet.GOLD));
        BattleSettlementData voidSettlement = BattleSettlementData.newBuilder()
                .setBattleId(2001L)
                .setPlayerId(1001L)
                .setMatchMode(com.game.battle.engine.BattleConstants.MATCH_MODE_JIANGHU_DUEL)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .setTotalRounds(0)
                .setJianghuDuel(JianghuDuelBattleContext.newBuilder().setDuelInviteId(6001L).setWritTier(1))
                .build();
        f.jianghu.applyDuelSettlement(f.player, voidSettlement);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isEqualTo(2001L);

        // 腾出余额空间后，reaper 终局复核 NOT_FOUND 退还一次并清标记
        f.player.wallet().deduct(Wallet.GOLD, 1000L);
        long beforeRefund = f.player.wallet().balance(Wallet.GOLD);
        f.clock.advanceMillis(600_000L);
        f.nextVerdict.set(BattleTerminalVerdict.NOT_FOUND);
        f.battle.reap();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(beforeRefund + 500L);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isZero();

        // 缺陷兜底：SQL 终局 COMPLETE 且本地账本已记录该局 -> 复核清标记但不退钱
        assertThat(f.prepareSilverDuel(2002L, 6002L).hasErrorMessage()).isFalse();
        long afterDebit = f.player.wallet().balance(Wallet.GOLD);
        f.player.battleLedger().record(2002L, f.clock.epochMillis());
        f.clock.advanceMillis(600_000L);
        f.nextVerdict.set(BattleTerminalVerdict.COMPLETE);
        f.battle.reap();
        assertThat(f.player.wallet().balance(Wallet.GOLD)).isEqualTo(afterDebit);
        assertThat(f.jianghu.stateOf(1001L).getPendingSilverFeeBattleId()).isZero();
    }
}
