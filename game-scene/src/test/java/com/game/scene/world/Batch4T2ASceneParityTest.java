package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.audit.proto.TransactionLogRecord;
import com.game.proto.BagInfo;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CraftProxyStallInfo;
import com.game.proto.eBattleOutcome;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.audit.PlayerAuditOutboxBuffer;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleFreeze;
import com.game.scene.battle.BattleSettlementService;
import com.game.scene.battle.BattleSnapshots;
import com.game.scene.battle.PlayerBattleService;
import com.game.scene.craft.CraftProxyStallDirectory;
import com.game.scene.mail.PlayerMailOutboxService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerMailOutbox;
import com.game.scene.testing.ManualClock;
import com.game.table.AssetErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.ItemTable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 覆盖 Batch 4T-2A 在 game-scene 内的全部 5 项核心对齐：
 * 1) 西市热榜近 24 小时 UTC 小时分桶 + 25h TTL + 停写 :24o/:24s；
 * 2) AOI 单向关注（显式多来源、隐身与侦测授权、权重淘汰与回补、O(度) 离场清理）；
 * 3) 异常收益窗口按玩家重置（不影响其他玩家与阈值，空/重复安全）；
 * 4) 提交绑定审计发件箱暂存（仅在存盘确认后放行，围栏拒绝/夺权丢弃，单一玩家不变量与 8MiB 上限）；
 * 5) 战斗道具预留 + 续冻上限后交易 RETRY / 穿戴暂不可用 + BagInfo.held_items + 两包满改投邮件（16种/4096件分封、上限32回落延后、items_mailed）。
 */
class Batch4T2ASceneParityTest {

    @Test
    void 西市热榜按UTC小时分桶滑出24小时归零_且待回账停写24o与24s() {
        ManualClock clock = new ManualClock();
        clock.advanceSeconds(1000L * 3600L);
        List<String> repSuffixes = new ArrayList<>();
        List<Long> hotHours = new ArrayList<>();
        CraftProxyStallDirectory dir = new CraftProxyStallDirectory(new CraftProxyStallDirectory.BackingStore() {
            @Override public CompletionStage<Void> putAsync(long masterPlayerId, byte[] payload) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> removeAsync(long masterPlayerId) { return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> incrReputationAsync(long masterPlayerId, String fieldSuffix, long delta) {
                repSuffixes.add(fieldSuffix);
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<Void> incrHotBucketAsync(long utcHour, long masterPlayerId, int ordersDelta, int supremeDelta) {
                hotHours.add(utcHour);
                return CompletableFuture.completedFuture(null);
            }
        }, Runnable::run).withClock(clock);

        CraftProxyStallInfo stall = CraftProxyStallInfo.newBuilder()
                .setMasterPlayerId(5001L)
                .setMasterName("宗师甲")
                .setForgeMasteryTier(com.game.scene.equip.EquipCraftQualityRules.MASTERY_MASTER)
                .setProxyForgeFeeGold(com.game.scene.equip.EquipCraftQualityRules.PROXY_FEE_MIN_GOLD)
                .build();
        dir.applyRemoteSnapshot(Map.of("5001", stall.toByteArray()));

        dir.recordOrderReputation(5001L, 1, 0, 950L, "买家一", null);
        assertThat(repSuffixes).doesNotContain("24o", "24s");
        assertThat(hotHours).containsExactly(CraftProxyStallDirectory.utcHourOfMillis(clock.epochMillis()));
        assertThat(dir.listStalls().getFirst().getRecent24HOrders()).isEqualTo(1);
        assertThat(dir.listStalls().getFirst().getRecent24HSupreme()).isEqualTo(1);

        // 推进 23 小时仍在近 24 小时窗内
        clock.advanceSeconds(23L * 3600L);
        assertThat(dir.listStalls().getFirst().getRecent24HOrders()).isEqualTo(1);

        // 再推进 1 小时（满 24 小时边界外）旧桶滑出归零
        clock.advanceSeconds(3600L);
        assertThat(dir.listStalls().getFirst().getRecent24HOrders()).isZero();
        assertThat(dir.listStalls().getFirst().getRecent24HSupreme()).isZero();

        // 验证 Lua 平坦回复解析
        Map<String, String> parsed = CraftProxyStallDirectory.parseHot24hLuaReply(List.of("5001:o", "7", "5001:s", "2"));
        assertThat(parsed).containsEntry("5001:o", "7").containsEntry("5001:s", "2");
    }

    @Test
    void AOI单向关注_显式多来源_隐身侦测_权重淘汰与空位回补() {
        ViewIndex index = new ViewIndex(2);
        ScenePlayer obs = WorldTestAccess.player(101L);
        ScenePlayer near1 = WorldTestAccess.player(102L);
        ScenePlayer near2 = WorldTestAccess.player(103L);
        ScenePlayer farTarget = WorldTestAccess.player(104L);
        obs.setPosition(new Vec3(0, 0, 0));
        near1.setPosition(new Vec3(5, 0, 0));
        near2.setPosition(new Vec3(6, 0, 0));
        farTarget.setPosition(new Vec3(500, 0, 0)); // 远在网格外

        index.enter(obs);
        index.enter(near1);
        index.enter(near2);
        index.enter(farTarget);
        assertThat(index.watching(obs)).containsExactlyInAnyOrder(near1, near2);
        assertThat(index.watching(farTarget)).isEmpty();

        // 1. 显式关注远端目标（权重 100 > 普通 10）：淘汰一个普通邻居，且为单向（farTarget 看不到 obs）
        ViewChanges ch1 = new ViewChanges();
        assertThat(index.addExplicitInterest(obs, farTarget, new ViewIndex.ExplicitSource(1, 10L, 100, false), ch1)).isTrue();
        assertThat(index.watching(obs)).contains(farTarget).hasSize(2);
        assertThat(index.watchers(farTarget)).containsExactly(obs);
        assertThat(index.watching(farTarget)).doesNotContain(obs);

        // 2. 叠加第二份带侦测隐身授权的显式来源：客户端无重复添加，进入隐身时仍可见
        ViewChanges ch2 = new ViewChanges();
        assertThat(index.addExplicitInterest(obs, farTarget, new ViewIndex.ExplicitSource(2, 20L, 120, true), ch2)).isFalse();
        ViewChanges stealthCh = new ViewChanges();
        index.setStealth(farTarget, true, stealthCh);
        assertThat(index.watching(obs)).as("有侦测授权的观察者不受隐身移除").contains(farTarget);

        // 3. 撤除侦测授权来源后，仅余非侦测来源 -> 立刻被隐身移除；解除隐身后按最新位置重现
        ViewChanges ch3 = new ViewChanges();
        index.removeExplicitInterest(obs, farTarget, 2, 20L, ch3);
        assertThat(index.watching(obs)).doesNotContain(farTarget);
        farTarget.setPosition(new Vec3(600, 0, 0));
        ViewChanges unstealthCh = new ViewChanges();
        index.setStealth(farTarget, false, unstealthCh);
        assertThat(index.watching(obs)).contains(farTarget);

        // 4. 权重淘汰与空位回补：新增一个高权重目标挤掉较低显式目标，离场空出名额后自动回补暂隐目标
        ScenePlayer vip = WorldTestAccess.player(105L);
        vip.setPosition(new Vec3(700, 0, 0));
        index.enter(vip);
        ViewChanges evictCh = new ViewChanges();
        index.addExplicitInterest(obs, vip, new ViewIndex.ExplicitSource(3, 30L, 200, false), evictCh);
        assertThat(index.watching(obs)).contains(vip).hasSize(2);

        // 离场清理不扫全节点且自动回补暂隐显式目标
        ViewChanges backfill = new ViewChanges();
        index.leave(vip, backfill);
        assertThat(index.watchers(vip)).isEmpty();
        assertThat(index.watching(obs)).contains(farTarget);
    }

    @Test
    void 异常收益窗口仅重置指定玩家_不影响阈值与其他玩家() {
        ManualClock clock = new ManualClock();
        GainAnomalyDetector detector = new GainAnomalyDetector(
                new GainAnomalyDetector.Threshold(Duration.ofSeconds(600), 2, 0),
                Map.of(), Map.of(), clock, SceneMetrics.noop());
        ScenePlayer p1 = WorldTestAccess.player(201L);
        ScenePlayer p2 = WorldTestAccess.player(202L);

        assertThat(detector.currencyGained(p1, 0, 10)).isFalse();
        assertThat(detector.currencyGained(p1, 0, 10)).isFalse();
        assertThat(detector.currencyGained(p2, 0, 10)).isFalse();
        assertThat(detector.currencyGained(p2, 0, 10)).isFalse();

        // 只重置 p1（含空句柄和重复调用安全）
        detector.resetPlayer(null);
        detector.resetPlayer(p1);
        detector.resetPlayer(p1);

        // p1 从零计数（第 1 次不越线），p2 保留原计数（第 3 次越线）
        assertThat(detector.currencyGained(p1, 0, 10)).isFalse();
        assertThat(detector.currencyGained(p2, 0, 10)).isTrue();
    }

    @Test
    void 审计发件箱暂存_单一玩家不变量_仅存盘提交后放行_围栏拒绝丢弃() {
        List<TransactionLogRecord> released = new ArrayList<>();
        PlayerAuditOutboxBuffer buffer = new PlayerAuditOutboxBuffer(released::add, 256);

        // 拒收无玩家与双玩家流水
        assertThat(buffer.stage(TransactionLogRecord.newBuilder().setCurrencyDelta(10).build())).isFalse();
        assertThat(buffer.stage(TransactionLogRecord.newBuilder().setFromPlayer(1L).setToPlayer(2L).build())).isFalse();
        assertThat(buffer.dropCount(PlayerAuditOutboxBuffer.DropReason.NO_SINGLE_PLAYER)).isEqualTo(2L);

        // 正常暂存：提交前绝不外发
        TransactionLogRecord r1 = TransactionLogRecord.newBuilder().setToPlayer(1001L).setCurrencyDelta(50).build();
        TransactionLogRecord r2 = TransactionLogRecord.newBuilder().setToPlayer(1001L).setCurrencyDelta(80).build();
        assertThat(buffer.stage(r1)).isTrue();
        assertThat(buffer.stage(r2)).isTrue();
        assertThat(released).isEmpty();

        var batch = buffer.captureBatchForSave(1001L);
        assertThat(batch.records()).hasSize(2);
        // 确认提交后才一次性放行
        assertThat(buffer.onSaveCommitted(1001L, batch.upToSeq())).isEqualTo(2);
        assertThat(released).containsExactly(r1, r2);
        assertThat(buffer.pendingCount(1001L)).isZero();

        // 围栏拒绝 / 失去归属丢弃未提交流水
        assertThat(buffer.stage(TransactionLogRecord.newBuilder().setToPlayer(1001L).setCurrencyDelta(99).build())).isTrue();
        assertThat(buffer.discardPlayer(1001L)).isEqualTo(1);
        assertThat(released).hasSize(2);
    }

    @Test
    void 战斗道具预留_快照剔除他局_续冻上限保留预留_交易RETRY_穿戴暂不可用_heldItems与满包改投邮件() {
        BattleFixture f = new BattleFixture();
        f.settlements.withMailOutboxService(f.mailOutbox);
        ScenePlayer p = f.enter(11, 1001L);

        // 找一个正式配表里的 battle_usable 消耗品与一个可穿戴装备
        int potionId = BattleFixture.CONFIG.item().all().stream()
                .filter(i -> i.getBattleUsable() != 0 && i.getMaxStackSize() > 1 && i.getEquipKind() == 0)
                .mapToInt(ItemTable::getId).findFirst().orElseThrow();
        int equipId = BattleFixture.CONFIG.item().all().stream()
                .filter(i -> i.getEquipKind() != 0 && i.getMaxStackSize() == 1
                        && i.getEquipLevel() <= 1 && (i.getEquipClass() == 0 || i.getEquipClass() == p.classId()))
                .mapToInt(ItemTable::getId).findFirst().orElseThrow();

        // 发放 5 瓶战斗药与 2 件同款装备
        assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(potionId, 5L), AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(equipId, 2L), AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();

        // 1. 备战第 101 局：自动建立 5 瓶药的预留，BagInfo.held_items 仅人物背包显示 5
        var prep1 = f.prepare(1001L, 101L);
        f.drain();
        assertThat(BattleFixture.done(prep1).hasErrorMessage()).isFalse();
        assertThat(p.battleReservations().totalReserved(potionId)).isEqualTo(5L);

        BagFeature bagFeature = new BagFeature(f.bags);
        BagInfo invInfo = bagFeature.snapshot(p, BagType.INVENTORY);
        BagInfo tempInfo = bagFeature.snapshot(p, BagType.TEMPORARY);
        assertThat(invInfo.getHeldItemsList()).singleElement().satisfies(h -> {
            assertThat(h.getConfigId()).isEqualTo(potionId);
            assertThat(h.getCount()).isEqualTo(5L);
        });
        assertThat(tempInfo.getHeldItemsList()).isEmpty();

        // 2. 快照扣除他局预留：给第 202 局造快照时，第 101 局已押的 5 瓶药不出现在第 202 局快照中
        BattlePlayerSnapshot snap202 = BattleSnapshots.build(p, f.tables, List.of(),
                com.game.proto.BattleRouting.getDefaultInstance(), 0, f.clock.epochMillis(), 202L);
        assertThat(snap202.getItemsList().stream().filter(i -> i.getItemTableId() == potionId)).isEmpty();

        // 3. 确认开战并推进到 15 分钟续冻上限：冻结解开，但预留仍在！
        f.battle.withTerminalOutcomeReader((pid, bid) -> PlayerBattleService.SqlTerminalVerdict.HOLD_AND_EXTEND);
        f.confirm(1001L, 101L, f.deadline());
        f.drain();
        f.clock.advanceMillis(BattleFixture.BATTLE_MILLIS + BattleFixture.OUTCOME_WINDOW_MILLIS
                + BattleFreeze.MAX_FIGHTING_EXTEND_MS + 5_000L);
        f.battle.reap();
        assertThat(p.inBattle()).as("达 15 分钟上限只解冻").isFalse();
        assertThat(p.battleReservations().has(101L)).as("达上限绝不释放道具预留").isTrue();

        // 4. 解冻后交易扣出被预留的药回 RETRY + kAssetInBattle(27002)
        String secret = "0123456789abcdef0123456789abcdef";
        AssetOpService assetOps = new AssetOpService(f.world, f.currency, f.bags, new AssetOpAuth(c -> secret), f.clock);
        long nowMs = f.clock.epochMillis();
        AssetOpRequest debitReq = AssetOpSignatures.sign(AssetRpc.DEBIT,
                AssetOpRequest.newBuilder()
                        .setPlayerId(1001L)
                        .setStream(AssetStream.ASSET_STREAM_TRADE_DEBIT)
                        .setStreamEpoch(1L)
                        .setSeq(1L)
                        .setTxType(3)
                        .setCorrelationId(9001L)
                        .setBundle(AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(potionId).setCount(1)))
                        .build(),
                "trade", secret, nowMs);
        AssetOpResponse debitResp = assetOps.handle(AssetRpc.DEBIT, debitReq);
        assertThat(debitResp.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(debitResp.getReason()).isEqualTo(AssetErrorTip.asset_error.kAssetInBattle_VALUE);

        // 5. 穿戴检查：两件同款装备押一件，第一件能穿、第二件回暂不可用且两包不动，释放后第二件能穿
        p.battleReservations().putVerified(303L, f.clock.epochMillis() + 60_000L, Map.of(equipId, 1L));
        List<Long> equipGuids = p.bags().bag(BagType.INVENTORY).items().stream()
                .filter(i -> i.configId() == equipId).map(BagItem::guid).toList();
        assertThat(equipGuids).hasSize(2);
        assertThat(f.equipment.equip(p, equipGuids.get(0)).tip()).isZero();
        var secondWear = f.equipment.equip(p, equipGuids.get(1));
        assertThat(secondWear.tip()).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        assertThat(p.bags().bag(BagType.INVENTORY).item(equipGuids.get(1))).isNotNull();
        p.battleReservations().release(303L);
        assertThat(f.equipment.unequip(p, equipGuids.get(0)).tip()).isZero();
        assertThat(f.equipment.equip(p, equipGuids.get(1)).tip()).isZero();

        // 6. 结算保护他局预留 + 满包（人物包 + 临时格全满）改投个人邮件 + items_mailed=true + 拆封规则
        p.battleReservations().putVerified(404L, f.clock.epochMillis() + 60_000L, Map.of(potionId, 2L));
        // 塞满人物背包与临时格（不可叠加装备填满剩余格子）
        Bag inv = p.bags().bag(BagType.INVENTORY);
        Bag temp = p.bags().bag(BagType.TEMPORARY);
        while (inv.freeCells() > 0) {
            assertThat(f.bags.addItems(p, BagType.INVENTORY, Map.of(equipId, 1L), AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        }
        while (temp.freeCells() > 0) {
            assertThat(f.bags.addItems(p, BagType.TEMPORARY, Map.of(equipId, 1L), AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        }
        // 第 101 局消耗 5 瓶药（只能动用本局可用的 5-2=3 瓶，保住第 404 局的 2 瓶！），并掉落 1 件装备触发两包满转邮件
        BattleSettlementData settlement = BattleSettlementData.newBuilder()
                .setBattleId(101L)
                .setPlayerId(1001L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setHealth(100)
                .setMana(50)
                .addItemsConsumed(BattleItemEntry.newBuilder().setItemTableId(potionId).setCount(5))
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(equipId).setCount(1))
                .build();
        BattleSettlementService.Result res = f.settlements.apply(f.world, p, settlement);
        assertThat(res.outcome()).isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(p.bags().bag(BagType.INVENTORY).total(potionId)).as("第 404 局预留的 2 瓶药绝不能被第 101 局扣走").isEqualTo(2L);
        assertThat(p.battleReservations().has(101L)).as("本局结算成功释放本局预留").isFalse();
        assertThat(p.battleReservations().has(404L)).as("他局预留不受影响").isTrue();
        assertThat(p.mailOutbox().entries()).singleElement().satisfies(mail -> {
            assertThat(mail.getSource()).isEqualTo(PlayerMailOutbox.SOURCE_BATTLE_OVERFLOW);
            assertThat(mail.getTitle()).isEqualTo("战斗奖励");
            assertThat(mail.getItemsList()).singleElement().satisfies(it -> {
                assertThat(it.getConfigId()).isEqualTo(equipId);
                assertThat(it.getCount()).isEqualTo(1L);
            });
        });
        assertThat(p.battleResults().findByBattleId(101L).getItemsMailed()).isTrue();

        // 7. 复核环：第 404 局到期且终局为 SAFE_TO_RELEASE 时释放预留
        f.battle.withTerminalOutcomeReader((pid, bid) -> PlayerBattleService.SqlTerminalVerdict.SAFE_TO_RELEASE);
        f.clock.advanceSeconds(120L);
        f.battle.reap();
        assertThat(p.battleReservations().has(404L)).isFalse();

        // 8. 拆封规则校验：超过 16 种与超过 4096 件自动分多封
        Map<Integer, Long> huge = new java.util.TreeMap<>();
        for (int i = 1; i <= 17; i++) {
            huge.put(1000 + i, 1L);
        }
        assertThat(PlayerMailOutboxService.splitBattleOverflowItems(huge)).hasSize(2);
        assertThat(PlayerMailOutboxService.splitBattleOverflowItems(Map.of(1001, 5000L))).hasSize(2);
    }
}
