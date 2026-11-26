package com.game.scene.equip;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BuyMysteryMerchantGoodsRequest;
import com.game.proto.BuyMysteryMerchantGoodsResponse;
import com.game.proto.ClaimEquipmentMaterialRewardRequest;
import com.game.proto.ClaimEquipmentMaterialRewardResponse;
import com.game.proto.CommitEquipmentCraftRequest;
import com.game.proto.CommitEquipmentCraftResponse;
import com.game.proto.EquipmentCraftChoice;
import com.game.proto.GetEquipmentCraftRulesRequest;
import com.game.proto.GetEquipmentCraftRulesResponse;
import com.game.proto.GetEquipmentMaterialRewardRequest;
import com.game.proto.GetEquipmentMaterialRewardResponse;
import com.game.proto.GetMysteryMerchantInfoRequest;
import com.game.proto.GetMysteryMerchantInfoResponse;
import com.game.proto.MessageContent;
import com.game.proto.MysteryMerchantShelfEntry;
import com.game.proto.MysteryMerchantState;
import com.game.proto.PreviewEquipmentCraftRequest;
import com.game.proto.PreviewEquipmentCraftResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.merchant.MysteryMerchantTables;
import com.game.scene.player.BagType;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 切片 8e 验收：神秘商人（314–316）、装备打造/首次安装固定词条（404–406）与装备材料自选领取（407–408）。
 */
class MysteryMerchantAndEquipmentCraftTest {

    private static final String MERCHANT_SERVICE = "SceneMysteryMerchantClientPlayer";
    private static final String BAG_SERVICE = "SceneBagClientPlayer";

    @Test
    void 神秘商人_状态发布查询购买限购禁获与幂等重放() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);
        var goodsRow = BattleFixture.CONFIG.mysteryMerchantGoods().all().getFirst();
        MysteryMerchantShelfEntry shelf = MysteryMerchantShelfEntry.newBuilder()
                .setGoodsId(goodsRow.getId()).setDiscountPercent(60).build();
        MysteryMerchantTables.ResolvedGoods resolved = f.merchantTables.resolve(shelf);
        assertThat(resolved).isNotNull();
        assertThat(f.currency.add(player, resolved.currencyType(), resolved.price() * 20, Reason.GM_GRANT).ok()).isTrue();
        long now = f.clock.epochMillis();

        // 1. 先落在 scene2：发一次 316 公告；重复同版本不发；玩家在 scene1 查询 here=false 且不下发货架
        MysteryMerchantState stateOnScene2 = MysteryMerchantState.newBuilder()
                .setRoundId(1_700_000_000L).setRevision(1)
                .setAppearAtMs(now - 5_000L).setExpireAtMs(now + 600_000L).setIntervalSeconds(3600)
                .setSceneId(f.scene2.sceneId()).setSceneConfigId(f.scene2.configId())
                .setPosition(vec(5, 5, 0))
                .addShelf(shelf).build();
        assertThat(f.merchant.applyState(stateOnScene2, now)).isPresent();
        assertThat(f.merchant.applyState(stateOnScene2, now)).isEmpty();
        GetMysteryMerchantInfoResponse offScene = call(f, player, MERCHANT_SERVICE, "GetMysteryMerchantInfo",
                GetMysteryMerchantInfoRequest.getDefaultInstance(), GetMysteryMerchantInfoResponse.parser());
        assertThat(offScene.getErrorMessage().getId()).isZero();
        assertThat(offScene.getInfo().getActive()).isTrue();
        assertThat(offScene.getInfo().getHere()).isFalse();
        assertThat(offScene.getInfo().getGoodsList()).isEmpty();

        // 2. 换线到 scene1（revision=2）；随后来的旧 revision=1 被丢弃；玩家距离过远拒 30002
        MysteryMerchantState farOnScene1 = stateOnScene2.toBuilder()
                .setRevision(2)
                .setSceneId(f.scene1.sceneId()).setSceneConfigId(f.scene1.configId())
                .setPosition(vec(500, 500, 0)).build();
        assertThat(f.merchant.applyState(farOnScene1, now)).isPresent();
        assertThat(f.merchant.applyState(stateOnScene2, now)).isEmpty();
        BuyMysteryMerchantGoodsRequest validBuy = BuyMysteryMerchantGoodsRequest.newBuilder()
                .setRoundId(1_700_000_000L).setGoodsId(resolved.goodsId()).setCount(1)
                .setExpectedPrice(resolved.price()).setExpectedCurrencyType(resolved.currencyType())
                .setExpectedItemConfigId(resolved.itemConfigId()).setExpectedItemCount(resolved.itemCount())
                .setClientRequestId(9001L).build();
        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods", validBuy,
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isEqualTo(30002);

        // 3. 移近玩家身旁（revision=3）：查询 here=true 带货架；报价错拒 30003；正常购买成功并扣款加物
        MysteryMerchantState nearOnScene1 = farOnScene1.toBuilder()
                .setRevision(3).setPosition(vec(player.position().x(), player.position().y(), player.position().z())).build();
        assertThat(f.merchant.applyState(nearOnScene1, now)).isPresent();
        GetMysteryMerchantInfoResponse onScene = call(f, player, MERCHANT_SERVICE, "GetMysteryMerchantInfo",
                GetMysteryMerchantInfoRequest.getDefaultInstance(), GetMysteryMerchantInfoResponse.parser());
        assertThat(onScene.getInfo().getHere()).isTrue();
        assertThat(onScene.getInfo().getGoodsList()).hasSize(1);
        assertThat(onScene.getInfo().getGoods(0).getPrice()).isEqualTo(resolved.price());

        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy.toBuilder().setExpectedPrice(resolved.price() + 1).setClientRequestId(9002L).build(),
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isEqualTo(30003);

        long balanceBefore = player.wallet().balance(resolved.currencyType());
        long itemBefore = player.bags().bag(BagType.INVENTORY).total(resolved.itemConfigId());
        BuyMysteryMerchantGoodsResponse bought = call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy, BuyMysteryMerchantGoodsResponse.parser());
        assertThat(bought.getErrorMessage().getId()).isZero();
        assertThat(bought.getReplayed()).isFalse();
        assertThat(bought.getInfo().getGoods(0).getBoughtCount()).isEqualTo(1);
        assertThat(player.wallet().balance(resolved.currencyType())).isEqualTo(balanceBefore - resolved.price());
        assertThat(player.bags().bag(BagType.INVENTORY).total(resolved.itemConfigId()))
                .isEqualTo(itemBefore + resolved.itemCount());

        // 4. 相同 client_request_id 同参重放：replayed=true 且不二次扣款加物；同键异参拒 1005
        BuyMysteryMerchantGoodsResponse replayed = call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy, BuyMysteryMerchantGoodsResponse.parser());
        assertThat(replayed.getErrorMessage().getId()).isZero();
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(player.wallet().balance(resolved.currencyType())).isEqualTo(balanceBefore - resolved.price());
        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy.toBuilder().setCount(2).build(),
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isEqualTo(1005);

        // 5. 个人禁获拒 30010；解除禁获后买满限购，超出拒 30004；切新轮次清零限购
        f.bags.blockItemGain(player, resolved.itemConfigId());
        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy.toBuilder().setClientRequestId(9003L).build(),
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isEqualTo(30010);
        f.bags.unblockItemGain(player, resolved.itemConfigId());

        int remaining = resolved.limitCount() - 1;
        if (remaining > 0) {
            assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                    validBuy.toBuilder().setCount(remaining).setClientRequestId(9004L).build(),
                    BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isZero();
        }
        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy.toBuilder().setCount(1).setClientRequestId(9005L).build(),
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isEqualTo(30004);

        MysteryMerchantState nextRound = nearOnScene1.toBuilder().setRoundId(1_700_003_600L).setRevision(1).build();
        assertThat(f.merchant.applyState(nextRound, now)).isPresent();
        assertThat(call(f, player, MERCHANT_SERVICE, "BuyMysteryMerchantGoods",
                validBuy.toBuilder().setRoundId(1_700_003_600L).setCount(1).setClientRequestId(9006L).build(),
                BuyMysteryMerchantGoodsResponse.parser()).getErrorMessage().getId()).isZero();
    }

    @Test
    void 装备打造404到406_预览打造安装固定词条幂等与版本校验() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1002);
        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        assertThat(rules.getErrorMessage().getId()).isZero();
        assertThat(rules.getRulesFingerprint().size()).isEqualTo(16);
        assertThat(rules.getRecipesList()).isNotEmpty();
        assertThat(rules.getAffixesList()).isNotEmpty();

        // 找一个支持固定词条（equip_profile == 1 或 2）的配方
        var recipe = rules.getRecipesList().stream()
                .filter(r -> r.getProduct().getEquipProfile() == 1 || r.getProduct().getEquipProfile() == 2)
                .findFirst().orElseThrow();
        int needLevel = Math.max(1, recipe.getProduct().getEquipLevel());
        assertThat(f.attributes.gmSetLevel(player, needLevel)).isZero();
        int expectedPool = recipe.getProduct().getEquipProfile() == 1 ? 1 : 3;
        var affix = rules.getAffixesList().stream()
                .filter(a -> a.getPool() == expectedPool)
                .findFirst().orElseThrow();

        // 发放打造与装词条材料
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(recipe.getMaterialConfigId(), Integer.toUnsignedLong(recipe.getMaterialCount()) * 2),
                Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(affix.getMaterialConfigId(), Integer.toUnsignedLong(affix.getMaterialCount()) * 2),
                Reason.GM_GRANT, 0, "").tip()).isZero();

        // 405 预览打造
        EquipmentCraftChoice createChoice = EquipmentCraftChoice.newBuilder()
                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                .setConfigId(recipe.getProduct().getConfigId()).build();
        PreviewEquipmentCraftResponse preview = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(createChoice).build(),
                PreviewEquipmentCraftResponse.parser());
        assertThat(preview.getErrorMessage().getId()).isZero();
        assertThat(preview.getPreview().getAvailable()).isTrue();

        // 406 提交打造 + 重放 + 过期水位 UNKNOWN
        CommitEquipmentCraftRequest createReq = CommitEquipmentCraftRequest.newBuilder()
                .setClientRequestId(7001L)
                .setRulesFingerprint(rules.getRulesFingerprint())
                .setExpectedForgeRevision(0L)
                .setChoice(createChoice).build();
        CommitEquipmentCraftResponse crafted = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                createReq, CommitEquipmentCraftResponse.parser());
        assertThat(crafted.getErrorMessage().getId()).isZero();
        assertThat(crafted.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        assertThat(crafted.getReplayed()).isFalse();
        assertThat(crafted.getItemId()).isNotZero();
        assertThat(crafted.getResultForgeRevision()).isEqualTo(1L);

        CommitEquipmentCraftResponse replayed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                createReq, CommitEquipmentCraftResponse.parser());
        assertThat(replayed.getErrorMessage().getId()).isZero();
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(replayed.getItemId()).isEqualTo(crafted.getItemId());

        CommitEquipmentCraftResponse stale = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                createReq.toBuilder().setClientRequestId(7002L).setExpectedForgeRevision(0L).build(),
                CommitEquipmentCraftResponse.parser());
        assertThat(stale.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.UNKNOWN);
        assertThat(stale.getErrorMessage().getId()).isEqualTo(28022);
        assertThat(stale.getCurrentForgeRevision()).isEqualTo(1L);

        // 406 首次安装固定词条（slot 1）+ 禁止覆盖已有槽位
        EquipmentCraftChoice installChoice = EquipmentCraftChoice.newBuilder()
                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                .setItemId(crafted.getItemId())
                .setAffixSlot(1)
                .setAffixId(affix.getAffixId()).build();
        CommitEquipmentCraftResponse installed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(7003L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(installChoice).build(),
                CommitEquipmentCraftResponse.parser());
        assertThat(installed.getErrorMessage().getId()).isZero();
        assertThat(installed.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        assertThat(installed.getResultForgeRevision()).isEqualTo(2L);
        var updatedItem = player.bags().bag(BagType.INVENTORY).item(crafted.getItemId());
        assertThat(updatedItem).isNotNull();
        assertThat(updatedItem.equipment().getFixedAffixesList()).hasSize(1);
        assertThat(updatedItem.equipment().getFixedAffixes(0).getAffixId()).isEqualTo(affix.getAffixId());

        CommitEquipmentCraftResponse overwrite = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(7004L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(2L)
                        .setChoice(installChoice).build(),
                CommitEquipmentCraftResponse.parser());
        assertThat(overwrite.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.REJECTED);
        assertThat(overwrite.getErrorMessage().getId()).isEqualTo(1005);
    }

    @Test
    void 装备材料自选407与408_战斗结算每日上限跨日刷新与幂等领取() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1003);

        // 第 1、2 场合格普通 PVE 胜利在阶段一（永久 660 总池，无日限）各记 +10（含 1 份可交易）；第 3 场逃跑不新增
        for (long battleId = 101; battleId <= 103; battleId++) {
            f.fighting(player.playerId(), battleId);
            f.deliver(BattleFixture.settlement(player.playerId(), battleId, 10)
                    .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                    .setPlayerTeamIndex(0)
                    .setTotalRounds(3)
                    .setFled(battleId == 103)
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .build());
            f.drain();
            f.world.saveDuePlayers(1);
            f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        }
        GetEquipmentMaterialRewardResponse snap1 = call(f, player, BAG_SERVICE, "GetEquipmentMaterialReward",
                GetEquipmentMaterialRewardRequest.getDefaultInstance(), GetEquipmentMaterialRewardResponse.parser());
        assertThat(snap1.getErrorMessage().getId()).isZero();
        assertThat(snap1.getSnapshot().getLifetimeEarned()).isEqualTo(20);
        assertThat(snap1.getSnapshot().getAvailableUnits()).isEqualTo(20);
        assertThat(snap1.getSnapshot().getAvailableTradableUnits()).isEqualTo(2);
        assertThat(snap1.getSnapshot().getBatchUnits()).isEqualTo(20);
        assertThat(snap1.getSnapshot().getBatchTradableUnits()).isEqualTo(2);
        assertThat(snap1.getSnapshot().getCanClaim()).isTrue();

        // 跨日后打一场达防挂机门槛的 1V1 败局额外记 +10（全绑定）-> 共 30（单次批量上限 20）
        f.advance(86_400_000L);
        f.fighting(player.playerId(), 104);
        f.deliver(BattleFixture.settlement(player.playerId(), 104, 10)
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setPlayerTeamIndex(1)
                .setTotalRounds(3)
                .setManualActionRounds(2)
                .setEffectiveCombatContribution(true)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .build());
        f.drain();
        f.world.saveDuePlayers(1);
        f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        GetEquipmentMaterialRewardResponse snap2 = call(f, player, BAG_SERVICE, "GetEquipmentMaterialReward",
                GetEquipmentMaterialRewardRequest.getDefaultInstance(), GetEquipmentMaterialRewardResponse.parser());
        assertThat(snap2.getSnapshot().getLifetimeEarned()).isEqualTo(30);
        assertThat(snap2.getSnapshot().getAvailableUnits()).isEqualTo(30);
        assertThat(snap2.getSnapshot().getAvailableTradableUnits()).isEqualTo(2);
        assertThat(snap2.getSnapshot().getBatchUnits()).isEqualTo(20);
        assertThat(snap2.getSnapshot().getBatchTradableUnits()).isEqualTo(2);

        // 408 非法拆分拒绝（总和不等于 batch_units）
        assertThat(call(f, player, BAG_SERVICE, "ClaimEquipmentMaterialReward",
                ClaimEquipmentMaterialRewardRequest.newBuilder().setClientRequestId(8001L).setExpectedRevision(0L)
                        .setCraftingMaterialCount(10).setEnhancementStoneCount(5).build(),
                ClaimEquipmentMaterialRewardResponse.parser()).getErrorMessage().getId()).isEqualTo(1005);

        // 408 正常领取 12 打造材料 + 8 强化石（2 份可交易优先分给打造材料，其余分堆绑定）；重放幂等；旧水位回 UNKNOWN + 1006
        ClaimEquipmentMaterialRewardRequest claimReq = ClaimEquipmentMaterialRewardRequest.newBuilder()
                .setClientRequestId(8002L).setExpectedRevision(0L)
                .setCraftingMaterialCount(12).setEnhancementStoneCount(8).build();
        ClaimEquipmentMaterialRewardResponse claimed = call(f, player, BAG_SERVICE, "ClaimEquipmentMaterialReward",
                claimReq, ClaimEquipmentMaterialRewardResponse.parser());
        assertThat(claimed.getErrorMessage().getId()).isZero();
        assertThat(claimed.getOutcome()).isEqualTo(ClaimEquipmentMaterialRewardResponse.Outcome.APPLIED);
        assertThat(claimed.getReplayed()).isFalse();
        assertThat(claimed.getResultRevision()).isEqualTo(1L);
        assertThat(claimed.getSnapshot().getAvailableUnits()).isEqualTo(10);
        assertThat(claimed.getSnapshot().getAvailableTradableUnits()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(12);
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(2);
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isEqualTo(8);
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipmentCraftService.ENHANCEMENT_STONE_ID)).isZero();

        ClaimEquipmentMaterialRewardResponse replayed = call(f, player, BAG_SERVICE, "ClaimEquipmentMaterialReward",
                claimReq, ClaimEquipmentMaterialRewardResponse.parser());
        assertThat(replayed.getErrorMessage().getId()).isZero();
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(12);

        ClaimEquipmentMaterialRewardResponse stale = call(f, player, BAG_SERVICE, "ClaimEquipmentMaterialReward",
                claimReq.toBuilder().setClientRequestId(8003L).setExpectedRevision(0L)
                        .setCraftingMaterialCount(5).setEnhancementStoneCount(5).build(),
                ClaimEquipmentMaterialRewardResponse.parser());
        assertThat(stale.getOutcome()).isEqualTo(ClaimEquipmentMaterialRewardResponse.Outcome.UNKNOWN);
        assertThat(stale.getErrorMessage().getId()).isEqualTo(1006);
    }

    @Test
    void 装备V1原地无损升档_纯银两保留强化保底词条品相署名与可交易状态() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1099);
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(EquipmentCraftService.CRAFTING_MATERIAL_ID, 100L),
                Reason.GM_GRANT, 1L, "").tip()).isZero();

        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        assertThat(rules.getErrorMessage().getId()).isZero();

        // 打造 1 级可交易武器 (3101) 并装上固定词条、设置强化+10/保底7/品相4/打磨保底5/宗师署名
        CommitEquipmentCraftResponse crafted = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(9101L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(3101).build()).build(),
                CommitEquipmentCraftResponse.parser());
        assertThat(crafted.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        long weaponId = crafted.getItemId();
        var item = player.bags().bag(BagType.INVENTORY).item(weaponId);
        assertThat(item).isNotNull();
        item.setBound(false);
        item.equipment(com.game.player.store.state.EquipmentState.newBuilder()
                .setEnhanceLevel(10)
                .setEnhancePityCount(7)
                .setCrafterPlayerId(42L)
                .setCrafterName("天工宗师")
                .addFixedAffixes(com.game.player.store.state.EquipmentFixedAffixState.newBuilder()
                        .setSlot(1).setAffixId(101).setQualityTier(4).setPolishPityCount(5))
                .build());

        EquipmentCraftChoice upgradeTo30 = EquipmentCraftChoice.newBuilder()
                .setOperation(EquipmentCraftChoice.Operation.UPGRADE_TIER)
                .setItemId(weaponId)
                .setConfigId(3131).build();

        // 1. 等级不足 (Lv1 -> Lv30) 预览不可用，但准确给出 300 银两且无打造材料消耗
        player.setLevel(1);
        PreviewEquipmentCraftResponse lowLevel = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(upgradeTo30).build(),
                PreviewEquipmentCraftResponse.parser());
        assertThat(lowLevel.getPreview().getAvailable()).isFalse();
        assertThat(lowLevel.getPreview().getGoldCost()).isEqualTo(300L);
        assertThat(lowLevel.getPreview().hasMaterial()).isFalse();

        // 2. 30级且银两充足（350）提交原地无损升档：扣 300 银两、0 打造材料、全部养成与纯可交易 100% 保留
        player.setLevel(30);
        f.currency.add(player, com.game.scene.player.Wallet.GOLD, 350L, Reason.GM_GRANT, 2L);
        long matBefore = player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.CRAFTING_MATERIAL_ID);
        CommitEquipmentCraftRequest upgradeReq = CommitEquipmentCraftRequest.newBuilder()
                .setClientRequestId(9102L)
                .setRulesFingerprint(rules.getRulesFingerprint())
                .setExpectedForgeRevision(crafted.getResultForgeRevision())
                .setChoice(upgradeTo30).build();
        CommitEquipmentCraftResponse upgraded = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                upgradeReq, CommitEquipmentCraftResponse.parser());
        assertThat(upgraded.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        assertThat(upgraded.getItemId()).isEqualTo(weaponId);
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.GOLD)).isEqualTo(50L);
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipmentCraftService.CRAFTING_MATERIAL_ID)).isEqualTo(matBefore);

        var after = player.bags().bag(BagType.INVENTORY).item(weaponId);
        assertThat(after).isNotNull();
        assertThat(after.configId()).isEqualTo(3131);
        assertThat(after.bound()).isFalse();
        assertThat(after.equipment().getEnhanceLevel()).isEqualTo(10);
        assertThat(after.equipment().getEnhancePityCount()).isEqualTo(7);
        assertThat(after.equipment().getCrafterPlayerId()).isEqualTo(42L);
        assertThat(after.equipment().getCrafterName()).isEqualTo("天工宗师");
        assertThat(after.equipment().getFixedAffixes(0).getAffixId()).isEqualTo(101);
        assertThat(after.equipment().getFixedAffixes(0).getQualityTier()).isEqualTo(4);
        assertThat(after.equipment().getFixedAffixes(0).getPolishPityCount()).isEqualTo(5);

        // 3. 幂等重放不重复扣银两
        CommitEquipmentCraftResponse replayed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                upgradeReq, CommitEquipmentCraftResponse.parser());
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(player.wallet().balance(com.game.scene.player.Wallet.GOLD)).isEqualTo(50L);

        // 4. 拒绝降级（3131->3101）与跨部位（3131->3132）
        assertThat(call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(EquipmentCraftChoice.newBuilder()
                        .setOperation(EquipmentCraftChoice.Operation.UPGRADE_TIER)
                        .setItemId(weaponId).setConfigId(3101).build()).build(),
                PreviewEquipmentCraftResponse.parser()).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(EquipmentCraftChoice.newBuilder()
                        .setOperation(EquipmentCraftChoice.Operation.UPGRADE_TIER)
                        .setItemId(weaponId).setConfigId(3132).build()).build(),
                PreviewEquipmentCraftResponse.parser()).getErrorMessage().getId()).isEqualTo(1005);
    }

    private static <T extends Message> T call(BattleFixture f, ScenePlayer player, String service, String method,
                                              Message req, com.google.protobuf.Parser<T> parser)
            throws InvalidProtocolBufferException {
        int id = Contracts.REGISTRY.requireId(service, method);
        long reqId = f.request(player, id, req);
        MessageContent reply = f.pushes(player, id).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }

    private static com.game.proto.Vector3 vec(double x, double y, double z) {
        return com.game.proto.Vector3.newBuilder().setX(x).setY(y).setZ(z).build();
    }
}
