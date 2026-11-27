package com.game.scene.equip;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BagItemInfo;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimEquipmentMaterialRewardRequest;
import com.game.proto.EnhanceEquipRequest;
import com.game.proto.EquipmentMaterialRewardSnapshot;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagFeature;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.EquipErrorTip.equip_error;
import com.game.table.ItemTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 统一经济契约测试：V1 装备 +1..+10 必成与 +11 概率硬保底幂等强化、两阶段 660/60 + 每周 150 绑定材料账本与防挂机。 */
class EnhancePityAndMaterialLedgerTest {

    @Test
    void V1基础装备前十级必成_加十一失败不掉级保底计数递增_乐观校验与幂等重放_第二十次必成归零() {
        BattleFixture f = new BattleFixture();
        // 使用确定性掷骰（总是返回 9999，使 +11 的 10% 概率落败直到触发第 20 次硬保底）
        EquipmentRules fixedLoseRules = new EquipmentRules(f.equipment.rules().tables(), bound -> bound - 1L);
        EquipmentService service = new EquipmentService(fixedLoseRules, f.bags, f.currency, f.attributes,
                new com.game.scene.metrics.SceneMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        EquipmentDisplay display = new EquipmentDisplay(fixedLoseRules);

        ScenePlayer player = f.enter(11, 8801L);
        ItemTable v1Weapon = BattleFixture.CONFIG.item().all().stream()
                .filter(row -> row.getEquipProfile() == 1 && row.getEquipKind() == 11)
                .findFirst().orElseThrow();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(v1Weapon.getId(), 1L),
                AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();
        BagItem weapon = player.bags().bag(BagType.INVENTORY).items().stream()
                .filter(i -> i.configId() == v1Weapon.getId()).findFirst().orElseThrow();
        weapon.equipment(EquipmentState.newBuilder().setEnhanceLevel(9).build());
        // +9->+10 需 15 颗，+10->+11 满 20 次硬保底需 20 × 100 = 2000 颗，合计 2015 颗。
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(EquipmentCraftService.ENHANCEMENT_STONE_ID, 2015L),
                AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();

        // +9 -> +10：100% 必成（即使随机源返回最大值 9999，10000 > 9999 仍必成，pity_limit=0）
        var to10 = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(9).setClientRequestId(1001L).build());
        assertThat(to10.tip()).isZero();
        assertThat(to10.success()).isTrue();
        assertThat(to10.enhanceLevel()).isEqualTo(10);
        assertThat(to10.enhancePityLimit()).isZero();
        assertThat(to10.enhancePityCount()).isZero();

        // 同键重放命中首次成功结果，不重复扣石
        long stonesAfter10 = player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID);
        var replay10 = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(9).setClientRequestId(1001L).build());
        assertThat(replay10.tip()).isZero();
        assertThat(replay10.replayed()).isTrue();
        assertThat(replay10.success()).isTrue();
        assertThat(replay10.enhanceLevel()).isEqualTo(10);
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(stonesAfter10);

        // +10 -> +11 第 1 次失败：不掉级（仍为 +10），pity_count 增至 1，pity_limit=20
        var fail1 = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(0).setClientRequestId(2001L).build());
        assertThat(fail1.tip()).isZero();
        assertThat(fail1.success()).isFalse();
        assertThat(fail1.enhanceLevel()).isEqualTo(10);
        assertThat(fail1.enhancePityCount()).isEqualTo(1);
        assertThat(fail1.enhancePityLimit()).isEqualTo(20);

        // 乐观 expected_pity_count 过期被拒（kEquipEnhanceLevelChanged）
        var stalePity = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(0).setClientRequestId(2002L).build());
        assertThat(stalePity.tip()).isEqualTo(equip_error.kEquipEnhanceLevelChanged_VALUE);

        // 连续再失败 18 次（累计 19 次失败）
        for (int attempt = 2; attempt <= 19; attempt++) {
            var res = service.enhance(player, EnhanceEquipRequest.newBuilder()
                    .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(attempt - 1)
                    .setClientRequestId(2000L + attempt).build());
            assertThat(res.tip()).isZero();
            assertThat(res.success()).isFalse();
            assertThat(res.enhancePityCount()).isEqualTo(attempt);
        }

        // 展示视图明确公示 material_count（随契约表同步）、pity_limit=20、current_pity_count=19、pity_guaranteed_next=true
        int expectedPlus11Cost = BattleFixture.CONFIG.equipEnhance().all().stream()
                .filter(row -> row.getProfileId() == 1 && row.getLevel() == 11)
                .findFirst().orElseThrow().getMaterialCount();
        BagItem liveWeapon = player.bags().bag(BagType.INVENTORY).item(weapon.guid());
        BagItemInfo.Builder view = BagItemInfo.newBuilder();
        display.item(liveWeapon, view);
        assertThat(view.getEnhancePityCount()).isEqualTo(19);
        assertThat(view.getEnhanceNext().getMaterialCount()).isEqualTo(expectedPlus11Cost);
        assertThat(view.getEnhanceNext().getPityLimit()).isEqualTo(20);
        assertThat(view.getEnhanceNext().getCurrentPityCount()).isEqualTo(19);
        assertThat(view.getEnhanceNext().getPityGuaranteedNext()).isTrue();

        // 第 20 次触发硬保底 100% 必成 +11，保底归零，消耗精确等于 +10(15) + 20 * expectedPlus11Cost，总加成 2400 万分比（+24%）
        var pityWin = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(19).setClientRequestId(2020L).build());
        assertThat(pityWin.tip()).isZero();
        assertThat(pityWin.success()).isTrue();
        assertThat(pityWin.enhanceLevel()).isEqualTo(11);
        assertThat(pityWin.enhancePityCount()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID))
                .isEqualTo(2015L - 15L - 20L * expectedPlus11Cost);
        assertThat(fixedLoseRules.bonus(1, 11)).isEqualTo(2400L);
    }

    @Test
    void 两阶段材料账本覆盖首套660可交易60_困难与PVP胜加5_防挂机拒发_二阶段周一05重置防回拨() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(12, 8802L);
        BagFeature bagView = new BagFeature(f.bags);
        // 2026-11-23 06:00:00 UTC+8（周一 05:00 后 1 小时）
        long monWeek1 = 1_795_384_800_000L;
        long[] week1 = EquipmentCraftService.resolveWeek(monWeek1);
        assertThat(week1).isNotNull();

        // 1. 困难 PVE 胜局（difficulty=2）-> +15（1 可交易 + 14 绑定）
        BattleSettlementData hardPveWin = BattleSettlementData.newBuilder()
                .setBattleId(1L).setPlayerId(player.playerId())
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(0)
                .setTotalRounds(4).setBattleDifficulty(2).build();
        var d1 = EquipmentCraftService.creditBattleMaterialRewardDelta(player, hardPveWin, monWeek1);
        assertThat(d1.totalUnitsAdded()).isEqualTo(15);
        assertThat(d1.tradableUnitsAdded()).isEqualTo(1);
        assertThat(d1.pvpLossIneligible()).isFalse();

        // 2. PVP 负局未达防挂机门槛（仅 1 次手动回合）-> +0，pvpLossIneligible=true，且不消耗 660 池
        BattleSettlementData afkLoss = BattleSettlementData.newBuilder()
                .setBattleId(2L).setPlayerId(player.playerId())
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN).setPlayerTeamIndex(0)
                .setTotalRounds(3).setManualActionRounds(1).setEffectiveCombatContribution(true).build();
        f.fighting(player.playerId(), 2L);
        f.deliver(afkLoss);
        f.drain();
        f.world.saveDuePlayers(1);
        f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        int battleEndId = com.game.scene.testing.Contracts.REGISTRY.requireId("BattleClientPlayer", "NotifyBattleEnd");
        var battleEndFrames = f.pushes(player, battleEndId);
        BattleEndS2C endPush = BattleEndS2C.parseFrom(battleEndFrames.getLast().getSerializedMessage());
        assertThat(endPush.getMaterialCreditUnitsAdded()).isZero();
        assertThat(endPush.getMaterialTradableUnitsAdded()).isZero();
        assertThat(endPush.getMaterialPvpLossIneligible()).isTrue();
        assertThat(player.clientRequestLedger().materialReward().getLifetimeEarned()).isEqualTo(15);

        // 3. PVP 胜局 -> +15（含 1 可交易），总余额 30（含 2 可交易），领取 1 打造 + 19 强化石 -> 1 打造可交易 + 1 强化石可交易 + 18 强化石绑定
        BattleSettlementData pvpWin = BattleSettlementData.newBuilder()
                .setBattleId(3L).setPlayerId(player.playerId())
                .setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(0)
                .setTotalRounds(5).setManualActionRounds(4).setEffectiveCombatContribution(true).build();
        var d3 = EquipmentCraftService.creditBattleMaterialRewardDelta(player, pvpWin, monWeek1);
        assertThat(d3.totalUnitsAdded()).isEqualTo(15);
        assertThat(d3.tradableUnitsAdded()).isEqualTo(1);
        var claimRes = f.craft.claimMaterialReward(player, ClaimEquipmentMaterialRewardRequest.newBuilder()
                .setClientRequestId(9101L).setExpectedRevision(0L)
                .setCraftingMaterialCount(1).setEnhancementStoneCount(19).build(), bagView, monWeek1);
        assertThat(claimRes.getErrorMessage().getId()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(1);
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(1);
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(19);

        // 4. 推至阶段一尾段（lifetime=655，tradable=60）-> 再胜一场仅补满 660（+5）且 0 可交易
        player.clientRequestLedger().setMaterialReward(EquipmentMaterialRewardLedgerState.newBuilder()
                .setMigratedToV2(true)
                .setLifetimeEarned(655)
                .setTradableEarnedTotal(60)
                .setAvailableUnits(0)
                .setAvailableTradableUnits(0)
                .setRevision(1L)
                .build());
        var capStage1 = EquipmentCraftService.creditBattleMaterialRewardDelta(player, hardPveWin.toBuilder().setBattleId(4L).build(), monWeek1);
        assertThat(capStage1.totalUnitsAdded()).isEqualTo(5);
        assertThat(capStage1.tradableUnitsAdded()).isZero();
        assertThat(player.clientRequestLedger().materialReward().getLifetimeEarned()).isEqualTo(660);

        // 5. 自动进入阶段二（weekly_phase_active=true）：本周累计至 150 全绑定后封顶；时钟回拨到上周不重开；跨入下周一 05:00 后重新开放
        EquipmentMaterialRewardSnapshot phase2Snap = f.craft.getMaterialReward(player, monWeek1).getSnapshot();
        assertThat(phase2Snap.getWeeklyPhaseActive()).isTrue();
        assertThat(phase2Snap.getWeeklyLimit()).isEqualTo(150);

        int weekEarned = 0;
        for (int i = 0; i < 9; i++) {
            var dw = EquipmentCraftService.creditBattleMaterialRewardDelta(
                    player, hardPveWin.toBuilder().setBattleId(10L + i).build(), monWeek1);
            assertThat(dw.tradableUnitsAdded()).isZero();
            assertThat(dw.totalUnitsAdded()).isEqualTo(15);
            weekEarned += dw.totalUnitsAdded();
        }
        BattleSettlementData normalPveWin = hardPveWin.toBuilder().setBattleId(19L).setBattleDifficulty(1).build();
        var dwNorm = EquipmentCraftService.creditBattleMaterialRewardDelta(player, normalPveWin, monWeek1);
        assertThat(dwNorm.totalUnitsAdded()).isEqualTo(10);
        weekEarned += dwNorm.totalUnitsAdded();
        // 145 -> 150 截断补满最后 5 份，后续再胜得 0。
        var dwCap = EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(20L).build(), monWeek1);
        assertThat(dwCap.totalUnitsAdded()).isEqualTo(5);
        assertThat(dwCap.tradableUnitsAdded()).isZero();
        weekEarned += dwCap.totalUnitsAdded();
        assertThat(weekEarned).isEqualTo(150);
        assertThat(EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(21L).build(), monWeek1).totalUnitsAdded()).isZero();
        // 时钟回拨到前一周：拒绝重新入账
        long prevWeekMs = monWeek1 - 7L * 86_400_000L;
        assertThat(EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(22L).build(), prevWeekMs).totalUnitsAdded()).isZero();
        // 跨入下一周（+7 天）：上周已满 150 无余量，本周新池仍为 150
        long nextWeekMs = monWeek1 + 7L * 86_400_000L;
        var nextWeekDelta = EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(23L).build(), nextWeekMs);
        assertThat(nextWeekDelta.totalUnitsAdded()).isEqualTo(15);
        assertThat(nextWeekDelta.tradableUnitsAdded()).isZero();
        assertThat(player.clientRequestLedger().materialReward().getStage2PoolCap()).isEqualTo(150);

        // 跳过 2 周（+14 天）：上周剩 135 + 2*150 = 435，滚存蓄水；再跳过 10 周封顶 4 周 = 600
        long week4Ms = nextWeekMs + 14L * 86_400_000L;
        assertThat(f.craft.getMaterialReward(player, week4Ms).getSnapshot().getWeeklyLimit()).isEqualTo(435);
        var week4Delta = EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(24L).build(), week4Ms);
        assertThat(week4Delta.totalUnitsAdded()).isEqualTo(15);
        assertThat(player.clientRequestLedger().materialReward().getStage2PoolCap()).isEqualTo(435);

        long week14Ms = week4Ms + 70L * 86_400_000L;
        assertThat(f.craft.getMaterialReward(player, week14Ms).getSnapshot().getWeeklyLimit()).isEqualTo(600);
        var week14Delta = EquipmentCraftService.creditBattleMaterialRewardDelta(
                player, hardPveWin.toBuilder().setBattleId(25L).build(), week14Ms);
        assertThat(week14Delta.totalUnitsAdded()).isEqualTo(15);
        assertThat(player.clientRequestLedger().materialReward().getStage2PoolCap()).isEqualTo(600);
    }

    @Test
    void 强化严格TB隔离与绑定传染_纯T足量不误绑_混绑二次确认后无论成败传染() {
        BattleFixture f = new BattleFixture();
        // 确定性掷骰总是落败，验证 +11 失败扣绑定石同样传染变绑
        EquipmentRules fixedLoseRules = new EquipmentRules(f.equipment.rules().tables(), bound -> bound - 1L);
        EquipmentService service = new EquipmentService(fixedLoseRules, f.bags, f.currency, f.attributes,
                new com.game.scene.metrics.SceneMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        BagFeature bagView = new BagFeature(f.bags);

        ScenePlayer player = f.enter(11, 8810L);
        ItemTable v1Weapon = BattleFixture.CONFIG.item().all().stream()
                .filter(row -> row.getEquipProfile() == 1 && row.getEquipKind() == 11)
                .findFirst().orElseThrow();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(v1Weapon.getId(), 1L),
                false, AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();
        BagItem weapon = player.bags().bag(BagType.INVENTORY).items().stream()
                .filter(i -> i.configId() == v1Weapon.getId()).findFirst().orElseThrow();
        assertThat(weapon.bound()).isFalse();

        // 背包同时持有 10 颗绑定百炼石 + 20 颗可交易百炼石：+0->+1 仅需 2 颗，纯 T 足量，哪怕传 allow_bound_materials=true 也不误绑且仅扣 T 堆
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(EquipmentCraftService.ENHANCEMENT_STONE_ID, 10L),
                true, AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(EquipmentCraftService.ENHANCEMENT_STONE_ID, 20L),
                false, AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();

        var snapBefore = bagView.snapshot(player, BagType.INVENTORY).getItemsList().stream()
                .filter(i -> i.getItemId() == weapon.guid()).findFirst().orElseThrow();
        assertThat(snapBefore.getEnhanceNext().getTradableMaterialAvailable()).isEqualTo(20L);
        assertThat(snapBefore.getEnhanceNext().getWillBindOnEnhance()).isFalse();

        var to1 = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(0).setAllowBoundMaterials(true).setClientRequestId(5101L).build());
        assertThat(to1.tip()).isZero();
        assertThat(to1.enhanceLevel()).isEqualTo(1);
        assertThat(player.bags().bag(BagType.INVENTORY).item(weapon.guid()).bound()).isFalse();
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(18L);
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(28L);

        // 切到 +10->+11：可交易石不足（仅 18 颗），补发 100 颗绑定石 -> 快照提示 will_bind_on_enhance=true，未确认被拦 28027
        player.bags().bag(BagType.INVENTORY).item(weapon.guid())
                .equipment(EquipmentState.newBuilder().setEnhanceLevel(10).build());
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(EquipmentCraftService.ENHANCEMENT_STONE_ID, 100L),
                true, AssetAudit.Reason.GM_GRANT, 0L, "").ok()).isTrue();
        var snapPlus11 = bagView.snapshot(player, BagType.INVENTORY).getItemsList().stream()
                .filter(i -> i.getItemId() == weapon.guid()).findFirst().orElseThrow();
        assertThat(snapPlus11.getEnhanceNext().getWillBindOnEnhance()).isTrue();

        var blocked = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(0).setClientRequestId(5102L).build());
        assertThat(blocked.tip()).isEqualTo(equip_error.kEquipBindConfirmRequired_VALUE);
        assertThat(player.bags().bag(BagType.INVENTORY).item(weapon.guid()).bound()).isFalse();

        // 确认 allow_bound_materials=true：即使 +11 掷骰失败，消耗了绑定石也立刻传染变绑，杜绝洗白套现
        var confirmedFail = service.enhance(player, EnhanceEquipRequest.newBuilder()
                .setItemId(weapon.guid()).setExpectedLevel(10).setExpectedPityCount(0)
                .setAllowBoundMaterials(true).setClientRequestId(5103L).build());
        assertThat(confirmedFail.tip()).isZero();
        assertThat(confirmedFail.success()).isFalse();
        assertThat(confirmedFail.enhancePityCount()).isEqualTo(1);
        assertThat(player.bags().bag(BagType.INVENTORY).item(weapon.guid()).bound()).isTrue();
    }

    @Test
    void 账号级60可交易跨角色防刷_未确认领取FailClosed_二阶段月卡解锁可交易不增总量() {
        BattleFixture f = new BattleFixture();
        BagFeature bagView = new BagFeature(f.bags);
        long monWeek1 = 1_795_384_800_000L;
        // 模拟账号级 60 份配额表（主号已用 60，小号建角后不可再超发；同时支持手动释放回调验证 fail-closed）
        java.util.concurrent.atomic.AtomicInteger accountUsed = new java.util.concurrent.atomic.AtomicInteger(60);
        java.util.List<com.game.scene.testing.FakePlayerRepository.PendingStage1QuotaSync> pendingSyncs = new java.util.ArrayList<>();
        f.repo.setStage1QuotaSyncHandler((sync, repo) -> pendingSyncs.add(sync));

        ScenePlayer alt = f.enter(11, 8820L);
        // 进场自动发起了一次账号配额同步，先挂起不回包，模拟同步尚未完成时小号胜局入账
        assertThat(pendingSyncs).hasSize(1);
        BattleSettlementData hardPveWin = BattleSettlementData.newBuilder()
                .setBattleId(901L).setPlayerId(alt.playerId())
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(0)
                .setTotalRounds(4).setBattleDifficulty(2).build();
        var delta = EquipmentCraftService.creditBattleMaterialRewardDelta(alt, hardPveWin, monWeek1);
        assertThat(delta.totalUnitsAdded()).isEqualTo(15);
        assertThat(delta.tradableUnitsAdded()).isEqualTo(1);

        // 未经账号目录确认前，领取含可交易份数的批次必须 fail-closed 拒回 kServiceUnavailable，并附带可用快照供客户端识别核验中提示
        var blockedClaim = f.craft.claimMaterialReward(alt, ClaimEquipmentMaterialRewardRequest.newBuilder()
                .setClientRequestId(9201L).setExpectedRevision(0L)
                .setCraftingMaterialCount(10).setEnhancementStoneCount(5).build(), bagView, monWeek1, f.world);
        assertThat(blockedClaim.getErrorMessage().getId())
                .isEqualTo(com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        assertThat(blockedClaim.getOutcome()).isEqualTo(com.game.proto.ClaimEquipmentMaterialRewardResponse.Outcome.UNKNOWN);
        assertThat(blockedClaim.getSnapshot().getAvailable()).isTrue();

        // 账号目录回包：该账号已用 60/60，本小号确认额度 0 -> 本地超额未确认可交易额平滑降级为绑定自用（总份数 15 不变）
        pendingSyncs.removeFirst().complete(new com.game.player.store.PlayerStore.Stage1TradableQuotaOutcome(true, 0, accountUsed.get()));
        assertThat(alt.clientRequestLedger().materialReward().getTradableEarnedTotal()).isZero();
        assertThat(alt.clientRequestLedger().materialReward().getAvailableTradableUnits()).isZero();
        assertThat(alt.clientRequestLedger().materialReward().getAvailableUnits()).isEqualTo(15L);
        assertThat(f.craft.getMaterialReward(alt, monWeek1).getSnapshot().getTradableEarnedTotal()).isEqualTo(60);

        // 降级后可正常领取全部 15 份绑定材料（0 可交易）
        var boundClaim = f.craft.claimMaterialReward(alt, ClaimEquipmentMaterialRewardRequest.newBuilder()
                .setClientRequestId(9202L).setExpectedRevision(0L)
                .setCraftingMaterialCount(10).setEnhancementStoneCount(5).build(), bagView, monWeek1, f.world);
        assertThat(boundClaim.getErrorMessage().getId()).isZero();
        assertThat(alt.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isZero();
        assertThat(alt.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(10L);

        // 下一场胜局因本场景已知账号已达 60/60，直接产生 0 可交易
        var altWin2 = EquipmentCraftService.creditBattleMaterialRewardDelta(
                alt, hardPveWin.toBuilder().setBattleId(902L).build(), monWeek1);
        assertThat(altWin2.totalUnitsAdded()).isEqualTo(15);
        assertThat(altWin2.tradableUnitsAdded()).isZero();

        // 推入二阶段（lifetime=660）：开通有效仙途轻享卡后，每周 150 池胜局含 1 份可交易且不增总额度
        alt.clientRequestLedger().setMaterialReward(EquipmentMaterialRewardLedgerState.newBuilder()
                .setMigratedToV2(true)
                .setLifetimeEarned(660)
                .setTradableEarnedTotal(0)
                .setStage1TradableConfirmed(0)
                .setAvailableUnits(0)
                .setAvailableTradableUnits(0)
                .setRevision(1L)
                .build());
        alt.setAfkPassExpiresAt(monWeek1 / 1000L + 86_400L);
        var stage2CardWin = EquipmentCraftService.creditBattleMaterialRewardDelta(
                alt, hardPveWin.toBuilder().setBattleId(903L).build(), monWeek1);
        assertThat(stage2CardWin.totalUnitsAdded()).isEqualTo(15);
        assertThat(stage2CardWin.tradableUnitsAdded()).isEqualTo(1);
        assertThat(alt.clientRequestLedger().isValid()).isTrue();
        var stage2Claim = f.craft.claimMaterialReward(alt, ClaimEquipmentMaterialRewardRequest.newBuilder()
                .setClientRequestId(9203L).setExpectedRevision(1L)
                .setCraftingMaterialCount(5).setEnhancementStoneCount(10).build(), bagView, monWeek1, f.world);
        assertThat(stage2Claim.getErrorMessage().getId()).isZero();
        assertThat(alt.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(1L);
    }
}
