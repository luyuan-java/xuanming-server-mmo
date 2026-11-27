package com.game.scene.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.EquipmentFixedAffixState;
import com.game.player.store.state.EquipmentGemState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerCraftState;
import com.game.proto.BagItemInfo;
import com.game.proto.CommitEquipmentCraftRequest;
import com.game.proto.CommitEquipmentCraftResponse;
import com.game.proto.CraftSkillBookRequest;
import com.game.proto.CraftSkillBookResponse;
import com.game.proto.DismantleSkillBookRequest;
import com.game.proto.DismantleSkillBookResponse;
import com.game.proto.EquipmentCraftChoice;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.GetEquipmentCraftRulesRequest;
import com.game.proto.GetEquipmentCraftRulesResponse;
import com.game.proto.GetSkillCraftingStateRequest;
import com.game.proto.GetSkillCraftingStateResponse;
import com.game.proto.LearnSkillBookRequest;
import com.game.proto.LearnSkillBookResponse;
import com.game.proto.ListCraftProxyStallsRequest;
import com.game.proto.ListCraftProxyStallsResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MessageContent;
import com.game.proto.PreviewEquipmentCraftRequest;
import com.game.proto.PreviewEquipmentCraftResponse;
import com.game.proto.SetCraftProxyStallRequest;
import com.game.proto.SetCraftProxyStallResponse;
import com.game.proto.UpgradeSkillRequest;
import com.game.proto.UpgradeSkillResponse;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.equip.EquipCraftQualityRules;
import com.game.scene.equip.EquipmentTables;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.game.table.EquipErrorTip.equip_error;
import com.game.table.SkillErrorTip.skill_error;
import com.game.table.SkillScrollCraftTable;
import com.game.table.SkillTable;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 装备 5 档词条品相打磨重铸 + 率土式分卷宗永久技能制书 + 主城宗师原子代打组件闭环测试。
 */
class EquipQualityAndSkillCraftTest {

    private static final String BAG_SERVICE = "SceneBagClientPlayer";
    private static final String SKILL_SERVICE = "SceneSkillClientPlayer";

    private static <T extends Message> T call(BattleFixture f, ScenePlayer player, String service, String method,
                                              Message req, Parser<T> parser) throws Exception {
        int messageId = Contracts.REGISTRY.requireId(service, method);
        long reqId = f.request(player, messageId, req);
        MessageContent reply = f.pushes(player, messageId).stream()
                .filter(m -> m.getId() == reqId)
                .findFirst()
                .orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }

    @Test
    void 五档品相打磨只升不降_第8次硬保底_定星符防染毒与重铸无损保进度() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 2001L);
        assertThat(f.attributes.gmSetLevel(player, 60)).isZero();
        assertThat(f.currency.add(player, Wallet.GOLD, 10_000L, Reason.GM_GRANT).ok()).isTrue();

        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        assertThat(rules.getErrorMessage().getId()).isZero();
        assertThat(rules.getQualityTiersList()).hasSize(5);
        assertThat(rules.getQualityTiers(0).getRatioPct()).isEqualTo(80);
        assertThat(rules.getQualityTiers(4).getRatioPct()).isEqualTo(100);
        assertThat(rules.getForgeMastery().getTier()).isEqualTo(EquipCraftQualityRules.MASTERY_APPRENTICE);

        // 找一件 profile=2（饰品，槽 1 & 2 共享池 3，同时包含 G02 速度词条与非速度普通词条）
        var accessoryRecipe = rules.getRecipesList().stream()
                .filter(r -> r.getProduct().getEquipProfile() == 2)
                .findFirst().orElseThrow();
        var normalAffixes = rules.getAffixesList().stream()
                .filter(a -> a.getPool() == 3 && !a.getPolishExempt())
                .toList();
        assertThat(normalAffixes).hasSizeGreaterThanOrEqualTo(2);
        var affixA = normalAffixes.get(0);
        var affixB = normalAffixes.get(1);
        var speedAffix = rules.getAffixesList().stream()
                .filter(a -> a.getPool() == 3 && a.getPolishExempt())
                .findFirst().orElseThrow();

        // 发放可交易打造/装条/打磨材料，及 2 张绑定定星保底符（3405）
        Map<Integer, Long> mats = new java.util.LinkedHashMap<>();
        mats.merge(accessoryRecipe.getMaterialConfigId(), 100L, Long::sum);
        mats.merge(affixA.getMaterialConfigId(), 100L, Long::sum);
        mats.merge(affixB.getMaterialConfigId(), 100L, Long::sum);
        mats.merge(speedAffix.getMaterialConfigId(), 100L, Long::sum);
        mats.merge(EquipCraftQualityRules.POLISH_SAND_CONFIG_ID, 30L, Long::sum);
        assertThat(f.bags.addItems(player, BagType.INVENTORY, mats, false, Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(EquipCraftQualityRules.GUARANTEE_CHARM_CONFIG_ID, 2L), true, Reason.GM_GRANT, 0, "").tip()).isZero();

        // 1. 打造一件可交易饰品
        CommitEquipmentCraftResponse crafted = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1001L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(accessoryRecipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(crafted.getErrorMessage().getId()).isZero();
        long itemId = crafted.getItemId();
        assertThat(player.bags().bag(BagType.INVENTORY).item(itemId).bound()).isFalse();
        assertThat(crafted.getForgeMastery().getExp()).isEqualTo(1);

        // 2. 装入非豁免词条 affixA，强制掷出 1 档凡品（roll=0）
        f.craft.setQualityRollForTest(bound -> 0);
        CommitEquipmentCraftResponse installed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1002L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setAffixId(affixA.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(installed.getErrorMessage().getId()).isZero();
        assertThat(installed.getResultQualityTier()).isEqualTo(1);
        assertThat(installed.getResultPolishPityCount()).isZero();

        // 核对 GetBag 展示的品相缩放值 = max * 80 / 100
        GetBagResponse bagSnap = call(f, player, BAG_SERVICE, "GetBag",
                GetBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build(), GetBagResponse.parser());
        BagItemInfo shown = bagSnap.getBag().getItemsList().stream()
                .filter(i -> i.getItemId() == itemId).findFirst().orElseThrow();
        var shownAffix = shown.getFixedAffixSlots(0).getSelected();
        assertThat(shownAffix.getQualityTier()).isEqualTo(1);
        assertThat(shownAffix.getQualityName()).isEqualTo("凡品");
        assertThat(shownAffix.getQualityRatioPct()).isEqualTo(80);
        assertThat(shownAffix.getValueBps()).isEqualTo(EquipCraftQualityRules.scaleValueByQuality(affixA.getMaxValueBps(), 80));

        // 3. 打磨升至 3 档上品（roll=7000 -> tier 3），再掷 1 档（roll=0）只升不降保持 3 档、保底涨到 2
        f.craft.setQualityRollForTest(bound -> 7000);
        CommitEquipmentCraftResponse polish1 = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1003L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(2L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setExpectedPolishPityCount(0))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(polish1.getErrorMessage().getId()).isZero();
        assertThat(polish1.getResultQualityTier()).isEqualTo(3);
        assertThat(polish1.getResultPolishPityCount()).isEqualTo(1);

        // 乐观保底计数不匹配直接拒 28028
        CommitEquipmentCraftResponse stalePity = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1004L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(3L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setExpectedPolishPityCount(0))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(stalePity.getErrorMessage().getId()).isEqualTo(equip_error.kEquipPolishPityChanged_VALUE);

        f.craft.setQualityRollForTest(bound -> 0);
        CommitEquipmentCraftResponse polish2 = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1005L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(3L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setExpectedPolishPityCount(1))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(polish2.getErrorMessage().getId()).isZero();
        assertThat(polish2.getResultQualityTier()).isEqualTo(3); // 只升不降
        assertThat(polish2.getResultPolishPityCount()).isEqualTo(2);

        // 4. 同槽重铸换 affixB：100% 无损保留 3 档与 pity=2；换同名回 28026
        CommitEquipmentCraftResponse sameReforge = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1006L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(4L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.REFORGE_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setAffixId(affixA.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(sameReforge.getErrorMessage().getId()).isEqualTo(equip_error.kEquipAffixReforgeSame_VALUE);

        CommitEquipmentCraftResponse reforged = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1007L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(4L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.REFORGE_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setAffixId(affixB.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(reforged.getErrorMessage().getId()).isZero();
        assertThat(reforged.getResultQualityTier()).isEqualTo(3);
        assertThat(reforged.getResultPolishPityCount()).isEqualTo(2);

        // 5. 连续打磨到第 8 次硬保底必达极品（当前 pity=2，再磨 6 次中第 6 次触发 8 次保底）
        long rev = 5L;
        for (int step = 0; step < 6; step++) {
            CommitEquipmentCraftRequest req = CommitEquipmentCraftRequest.newBuilder()
                    .setClientRequestId(1100L + step)
                    .setRulesFingerprint(rules.getRulesFingerprint())
                    .setExpectedForgeRevision(rev)
                    .setChoice(EquipmentCraftChoice.newBuilder()
                            .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                            .setItemId(itemId).setAffixSlot(1).setExpectedPolishPityCount(2 + step))
                    .build();
            CommitEquipmentCraftResponse r = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                    req, CommitEquipmentCraftResponse.parser());
            assertThat(r.getErrorMessage().getId()).isZero();
            rev++;
            if (step == 5) {
                assertThat(r.getResultQualityTier()).isEqualTo(5);
                assertThat(r.getResultPolishPityCount()).isZero();
                assertThat(r.getGuaranteedByPity()).isTrue();
                // 重放返回同样保底结果且不重复扣材料
                CommitEquipmentCraftResponse replayed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                        req, CommitEquipmentCraftResponse.parser());
                assertThat(replayed.getReplayed()).isTrue();
                assertThat(replayed.getResultQualityTier()).isEqualTo(5);
                assertThat(replayed.getGuaranteedByPity()).isTrue();
            } else {
                assertThat(r.getResultPolishPityCount()).isEqualTo(3 + step);
            }
        }
        // 已满极品继续打磨回 28023
        CommitEquipmentCraftResponse maxRejected = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1200L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(1))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(maxRejected.getErrorMessage().getId()).isEqualTo(equip_error.kEquipAffixAlreadyMaxQuality_VALUE);

        // 6. 槽 2 装 G02 速度词条：豁免随机直接满值 5 档，禁打磨(28024)；重铸成普通词条 affixA 防白嫖重新掷品相(tier=1)
        CommitEquipmentCraftResponse speedInstalled = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1201L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev++)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(itemId).setAffixSlot(2).setAffixId(speedAffix.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(speedInstalled.getResultQualityTier()).isEqualTo(5);
        assertThat(call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1202L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(2))
                        .build(), CommitEquipmentCraftResponse.parser()).getErrorMessage().getId())
                .isEqualTo(equip_error.kEquipAffixPolishExempt_VALUE);

        CommitEquipmentCraftResponse antiCheese = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1203L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev++)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.REFORGE_AFFIX)
                                .setItemId(itemId).setAffixSlot(2).setAffixId(affixA.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(antiCheese.getErrorMessage().getId()).isZero();
        assertThat(antiCheese.getResultQualityTier()).isEqualTo(1);

        // 7. 绑定定星保底符防染毒拦截(28027)，二次确认 allow_bound_materials=true 后直出极品并变绑
        PreviewEquipmentCraftResponse charmPreview = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder()
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(2).setUseGuaranteeCharm(true))
                        .build(), PreviewEquipmentCraftResponse.parser());
        assertThat(charmPreview.getPreview().getAvailable()).isFalse();
        assertThat(charmPreview.getPreview().getUnavailableTip()).isEqualTo(equip_error.kEquipBindConfirmRequired_VALUE);
        assertThat(charmPreview.getPreview().getWillBindItem()).isTrue();

        CommitEquipmentCraftResponse blockedCharm = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1204L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(2).setUseGuaranteeCharm(true))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(blockedCharm.getErrorMessage().getId()).isEqualTo(equip_error.kEquipBindConfirmRequired_VALUE);
        assertThat(player.bags().bag(BagType.INVENTORY).item(itemId).bound()).isFalse();

        CommitEquipmentCraftResponse confirmedCharm = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(1205L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(rev)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.POLISH_AFFIX)
                                .setItemId(itemId).setAffixSlot(2)
                                .setUseGuaranteeCharm(true).setAllowBoundMaterials(true))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(confirmedCharm.getErrorMessage().getId()).isZero();
        assertThat(confirmedCharm.getResultQualityTier()).isEqualTo(5);
        assertThat(confirmedCharm.getGuaranteedByPity()).isTrue();
        assertThat(player.bags().bag(BagType.INVENTORY).item(itemId).bound()).isTrue();

        // 8. 旧存档 quality_tier==0 兼容满值 100%
        BagItem legacyItem = player.bags().bag(BagType.INVENTORY).item(itemId);
        legacyItem.equipment(EquipmentState.newBuilder()
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                        .setSlot(1).setAffixId(affixA.getAffixId()).setQualityTier(0))
                .build());
        GetBagResponse legacyBag = call(f, player, BAG_SERVICE, "GetBag",
                GetBagRequest.newBuilder().setBagType(BagType.INVENTORY.code()).build(), GetBagResponse.parser());
        BagItemInfo legacyShown = legacyBag.getBag().getItemsList().stream()
                .filter(i -> i.getItemId() == itemId).findFirst().orElseThrow();
        assertThat(legacyShown.getFixedAffixSlots(0).getSelected().getQualityTier()).isEqualTo(5);
        assertThat(legacyShown.getFixedAffixSlots(0).getSelected().getValueBps()).isEqualTo(affixA.getMaxValueBps());
    }

    @Test
    void 率土式分卷宗技能制书_稀世10红线_防洗白_拆页与研习不顶旧技能() throws Exception {
        BattleFixture f = new BattleFixture();
        assertThat(f.skillCraftTables.validateTables()).isTrue();

        // 验证稀世占比 > 10% 时配表校验拒绝
        SkillScrollCraftTable rareScroll = f.skillCraftTables.enabledScrolls().stream()
                .filter(s -> s.getRarityTier() == 1).findFirst().orElseThrow();
        SkillScrollCraftTable regularScroll = f.skillCraftTables.enabledScrolls().stream()
                .filter(s -> s.getRarityTier() == 0).findFirst().orElseThrow();
        List<SkillTable> badSkills = new ArrayList<>();
        badSkills.add(SkillTable.newBuilder().setId(99001).setBattleModel(1).setScrollId(regularScroll.getId()).setRarityTier(0).build());
        badSkills.add(SkillTable.newBuilder().setId(99002).setBattleModel(1).setScrollId(rareScroll.getId()).setRarityTier(1).build());
        SkillCraftTables overCap = new SkillCraftTables(
                List.of(regularScroll, rareScroll), badSkills,
                BattleFixture.CONFIG.item().all(), EquipmentTables.from(BattleFixture.CONFIG));
        assertThat(overCap.validateTables()).isFalse();

        ScenePlayer player = f.enter(11, 2002L);
        assertThat(f.attributes.gmSetLevel(player, 60)).isZero();
        assertThat(f.currency.add(player, Wallet.GOLD, 10_000L, Reason.GM_GRANT).ok()).isTrue();
        var grantedPet = f.pets.grant(player, 1);
        assertThat(grantedPet.tip()).isZero();
        long petId = grantedPet.petId();

        // 发放可交易残页 3406、可交易绝学残卷 3407、绑定天工秘墨 3408
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(
                EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID, 200L,
                EquipCraftQualityRules.RARE_SCROLL_PAGE_CONFIG_ID, 5L), false, Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(
                EquipCraftQualityRules.RARE_SCROLL_INK_CONFIG_ID, 5L), true, Reason.GM_GRANT, 0, "").tip()).isZero();

        // 459 查询初始状态
        GetSkillCraftingStateResponse st0 = call(f, player, SKILL_SERVICE, "GetSkillCraftingState",
                GetSkillCraftingStateRequest.getDefaultInstance(), GetSkillCraftingStateResponse.parser());
        assertThat(st0.getErrorMessage().getId()).isZero();
        assertThat(st0.getState().getScrollsList()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(st0.getState().getCraftUnlockGateEnabled()).isFalse();

        int regularSkill = f.skillCraftTables.scrollSkillIds(regularScroll.getId()).stream()
                .filter(id -> (f.skillCraftTables.skill(id).getCasterMask() & 1) != 0)
                .findFirst().orElseThrow();
        int petSkill = f.skillCraftTables.scrollSkillIds(regularScroll.getId()).stream()
                .filter(id -> id != regularSkill && (f.skillCraftTables.skill(id).getCasterMask() & 2) != 0)
                .findFirst().orElseThrow();
        int rareSkill = f.skillCraftTables.scrollSkillIds(rareScroll.getId()).getFirst();

        // 460 常规随机拓印（纯可交易残页 -> 产物可交易）+ 拆页返还 16 可交易残页
        f.skillCraft.setCraftRollForTest(bound -> 0);
        CraftSkillBookResponse randomCraft = call(f, player, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.RANDOM_REGULAR)
                        .setScrollId(regularScroll.getId())
                        .setExpectedRevision(0L)
                        .setClientRequestId(3001L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(randomCraft.getErrorMessage().getId()).isZero();
        assertThat(randomCraft.getCraftedBook().getBound()).isFalse();
        long fragAfterRandom = player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID);
        assertThat(fragAfterRandom).isEqualTo(200L - regularScroll.getRandomFragmentCount());

        DismantleSkillBookResponse dismantled = call(f, player, SKILL_SERVICE, "DismantleSkillBook",
                DismantleSkillBookRequest.newBuilder()
                        .setBookId(randomCraft.getCraftedBook().getBookId())
                        .setExpectedRevision(1L)
                        .setClientRequestId(3002L)
                        .build(), DismantleSkillBookResponse.parser());
        assertThat(dismantled.getErrorMessage().getId()).isZero();
        assertThat(dismantled.getRefundedFragments()).isEqualTo(regularScroll.getDismantleFragmentCount());
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID))
                .isEqualTo(fragAfterRandom + regularScroll.getDismantleFragmentCount());

        // 460 稀世定向制书 -> 强制绑定 + 不可拆页(7016)
        CraftSkillBookResponse rareCraft = call(f, player, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_RARE)
                        .setScrollId(rareScroll.getId())
                        .setTargetSkillTableId(rareSkill)
                        .setExpectedRevision(2L)
                        .setClientRequestId(3003L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(rareCraft.getErrorMessage().getId()).isZero();
        assertThat(rareCraft.getCraftedBook().getBound()).isTrue();
        assertThat(rareCraft.getCraftedBook().getRarityTier()).isEqualTo(1);

        DismantleSkillBookResponse dismantleRare = call(f, player, SKILL_SERVICE, "DismantleSkillBook",
                DismantleSkillBookRequest.newBuilder()
                        .setBookId(rareCraft.getCraftedBook().getBookId())
                        .setExpectedRevision(3L)
                        .setClientRequestId(3004L)
                        .build(), DismantleSkillBookResponse.parser());
        assertThat(dismantleRare.getErrorMessage().getId()).isEqualTo(skill_error.kSkillBookNotDismantlable_VALUE);

        // 开启制书门槛：未研习书直接修习 regularSkill 回 kSkillCraftBookRequired (7019)
        f.skillCraft.setCraftUnlockGateForTest(true);
        ScenePlayer realBefore = com.game.scene.world.WorldTestAccess.playerWithCatalog(
                player.playerId(), player.classId(), 60,
                BattleFixture.persistentState(player),
                com.game.scene.skill.ClassSkillCatalog.from(BattleFixture.CONFIG));
        f.bags.initializeOnLoad(realBefore);
        f.attributes.initializeOnLoad(realBefore);
        ListSkillsResponse catalogBefore = com.game.scene.skill.ClassSkillService.book(
                realBefore, 0, f.skillCraft.isCraftUnlockGateEnabled());
        var entryBefore = catalogBefore.getCatalogList().stream()
                .filter(e -> e.getSkillTableId() == regularSkill).findFirst().orElseThrow();
        assertThat(entryBefore.getCraftUnlockRequired()).isTrue();
        assertThat(entryBefore.getUnlockedByBook()).isFalse();
        assertThat(entryBefore.getUpgradeBlockTipId()).isEqualTo(skill_error.kSkillCraftBookRequired_VALUE);

        UpgradeSkillResponse blockedUpgrade = f.classSkill.upgrade(realBefore,
                UpgradeSkillRequest.newBuilder()
                        .setSkillTableId(regularSkill).setExpectedRank(0).setCount(1).build());
        assertThat(blockedUpgrade.getErrorMessage().getId()).isEqualTo(skill_error.kSkillCraftBookRequired_VALUE);

        // 定向制 regularSkill + 研习 -> 永久解锁且获得 1 重，不顶掉任何已有技能
        int existingSkillsCount = player.skills().size();
        CraftSkillBookResponse craftedRegular = call(f, player, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_REGULAR)
                        .setScrollId(regularScroll.getId())
                        .setTargetSkillTableId(regularSkill)
                        .setExpectedRevision(3L)
                        .setClientRequestId(3005L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(craftedRegular.getErrorMessage().getId()).isZero();

        LearnSkillBookResponse learned = call(f, player, SKILL_SERVICE, "LearnSkillBook",
                LearnSkillBookRequest.newBuilder()
                        .setBookId(craftedRegular.getCraftedBook().getBookId())
                        .setPetId(0L)
                        .setExpectedRevision(4L)
                        .setClientRequestId(3006L)
                        .build(), LearnSkillBookResponse.parser());
        assertThat(learned.getErrorMessage().getId()).isZero();
        assertThat(learned.getLearnedSkillTableId()).isEqualTo(regularSkill);
        assertThat(player.learnedSkills().rank(regularSkill)).isEqualTo(1);
        assertThat(player.skills().size()).isGreaterThanOrEqualTo(existingSkillsCount);
        assertThat(player.craft().isSkillBookUnlocked(regularSkill)).isTrue();

        // 研习后升级 regularSkill (1 -> 2) 放行成功
        ScenePlayer realAfter = com.game.scene.world.WorldTestAccess.playerWithCatalog(
                player.playerId(), player.classId(), 60,
                BattleFixture.persistentState(player),
                com.game.scene.skill.ClassSkillCatalog.from(BattleFixture.CONFIG));
        f.bags.initializeOnLoad(realAfter);
        f.attributes.initializeOnLoad(realAfter);
        UpgradeSkillResponse allowedUpgrade = f.classSkill.upgrade(realAfter,
                UpgradeSkillRequest.newBuilder()
                        .setSkillTableId(regularSkill).setExpectedRank(1).setCount(1).build());
        assertThat(allowedUpgrade.getErrorMessage().getId()).isZero();
        assertThat(realAfter.learnedSkills().rank(regularSkill)).isEqualTo(2);

        // 宝宝研习 petSkill：不顶掉任何原有宝宝技能
        CraftSkillBookResponse craftedPet = call(f, player, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_REGULAR)
                        .setScrollId(regularScroll.getId())
                        .setTargetSkillTableId(petSkill)
                        .setExpectedRevision(5L)
                        .setClientRequestId(3007L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(craftedPet.getErrorMessage().getId()).isZero();
        LearnSkillBookResponse learnedPet = call(f, player, SKILL_SERVICE, "LearnSkillBook",
                LearnSkillBookRequest.newBuilder()
                        .setBookId(craftedPet.getCraftedBook().getBookId())
                        .setPetId(petId)
                        .setExpectedRevision(6L)
                        .setClientRequestId(3008L)
                        .build(), LearnSkillBookResponse.parser());
        assertThat(learnedPet.getErrorMessage().getId()).isZero();
        assertThat(player.pets().find(petId).skillRanks().getOrDefault(petSkill, 0)).isEqualTo(1);
    }

    @Test
    void 主城宗师原子代打摊位_5税销毁_宗师署名_巧匠返页与买家不涨熟练度() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer master = f.enter(11, 3001L);
        ScenePlayer buyer = f.enter(12, 3002L);
        assertThat(f.attributes.gmSetLevel(buyer, 60)).isZero();
        assertThat(f.currency.add(buyer, Wallet.GOLD, 10_000L, Reason.GM_GRANT).ok()).isTrue();

        // 非宗师开摊被拒 7021
        SetCraftProxyStallResponse nonMaster = call(f, master, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder().setOpen(true).setProxyForgeFeeGold(60L).setProxyScrollFeeGold(60L).build(),
                SetCraftProxyStallResponse.parser());
        assertThat(nonMaster.getErrorMessage().getId()).isEqualTo(skill_error.kSkillProxyStallRequiresMaster_VALUE);

        // 提升为双料宗师（forge=60, scroll=60）；非法定价被拒 7022
        master.craft().replace(PlayerCraftState.newBuilder()
                .setForgeMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .setScrollMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .build());
        SetCraftProxyStallResponse badFee = call(f, master, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder().setOpen(true).setProxyForgeFeeGold(150L).setProxyScrollFeeGold(80L).build(),
                SetCraftProxyStallResponse.parser());
        assertThat(badFee.getErrorMessage().getId()).isEqualTo(skill_error.kSkillProxyFeeInvalid_VALUE);

        // 合法开摊（铸器 100g，制卷 80g）-> ListCraftProxyStalls 可见
        SetCraftProxyStallResponse opened = call(f, master, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder().setOpen(true).setProxyForgeFeeGold(100L).setProxyScrollFeeGold(80L).build(),
                SetCraftProxyStallResponse.parser());
        assertThat(opened.getErrorMessage().getId()).isZero();
        ListCraftProxyStallsResponse stalls = call(f, buyer, SKILL_SERVICE, "ListCraftProxyStalls",
                ListCraftProxyStallsRequest.getDefaultInstance(), ListCraftProxyStallsResponse.parser());
        assertThat(stalls.getStallsList()).singleElement().satisfies(s -> {
            assertThat(s.getMasterPlayerId()).isEqualTo(master.playerId());
            assertThat(s.getForgeMasteryTier()).isEqualTo(EquipCraftQualityRules.MASTERY_MASTER);
            assertThat(s.getScrollMasteryTier()).isEqualTo(EquipCraftQualityRules.MASTERY_MASTER);
            assertThat(s.getProxyForgeFeeGold()).isEqualTo(100L);
            assertThat(s.getProxyScrollFeeGold()).isEqualTo(80L);
        });

        // 买家使用宗师代打装词条直出极品（roll=9999 -> tier 5）：买家扣 100g，宗师实收 95g（5g 税销毁），刻宗师署名，买家熟练度保持 0
        GetEquipmentCraftRulesResponse rules = call(f, buyer, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var recipe = rules.getRecipesList().stream().filter(r -> r.getProduct().getEquipProfile() == 1).findFirst().orElseThrow();
        var affix = rules.getAffixesList().stream().filter(a -> a.getPool() == 1 && !a.getPolishExempt()).findFirst().orElseThrow();
        Map<Integer, Long> buyerMats = new java.util.LinkedHashMap<>();
        buyerMats.merge(recipe.getMaterialConfigId(), 50L, Long::sum);
        buyerMats.merge(affix.getMaterialConfigId(), 50L, Long::sum);
        buyerMats.merge(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID, 100L, Long::sum);
        assertThat(f.bags.addItems(buyer, BagType.INVENTORY, buyerMats, false, Reason.GM_GRANT, 0, "").tip()).isZero();

        CommitEquipmentCraftResponse baseEquip = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(4001L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(recipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(baseEquip.getErrorMessage().getId()).isZero();
        int buyerForgeExpBeforeProxy = buyer.craft().forgeMasteryExp();
        long buyerGoldBefore = buyer.wallet().balance(Wallet.GOLD);
        long masterGoldBefore = master.wallet().balance(Wallet.GOLD);

        f.craft.setQualityRollForTest(bound -> 9999);
        CommitEquipmentCraftResponse proxyInstall = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(4002L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(baseEquip.getItemId())
                                .setAffixSlot(1)
                                .setAffixId(affix.getAffixId())
                                .setProxyMasterPlayerId(master.playerId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(proxyInstall.getErrorMessage().getId()).isZero();
        assertThat(proxyInstall.getResultQualityTier()).isEqualTo(5);
        assertThat(buyer.wallet().balance(Wallet.GOLD)).isEqualTo(buyerGoldBefore - 100L);
        assertThat(master.wallet().balance(Wallet.GOLD)).isEqualTo(masterGoldBefore); // 95g 走异步出箱入账，不直接改他人内存钱包
        assertThat(buyer.mailOutbox().size()).isEqualTo(1);
        assertThat(buyer.craft().forgeMasteryExp()).isEqualTo(buyerForgeExpBeforeProxy); // 代打不涨买家熟练度
        BagItem signedEquip = buyer.bags().bag(BagType.INVENTORY).item(baseEquip.getItemId());
        assertThat(signedEquip.equipment().getCrafterPlayerId()).isEqualTo(master.playerId());
        assertThat(signedEquip.equipment().getCrafterName()).isNotEmpty();

        // 买家使用宗师代制常规技能书（触发宗师 10% 额外返 2 张绑定残页 + 宗师署名 + 80g 扣款 / 76g 净银两入出箱）
        SkillScrollCraftTable regularScroll = f.skillCraftTables.enabledScrolls().stream()
                .filter(s -> s.getRarityTier() == 0).findFirst().orElseThrow();
        int targetSkill = f.skillCraftTables.scrollSkillIds(regularScroll.getId()).getFirst();
        long buyerGold2 = buyer.wallet().balance(Wallet.GOLD);
        long masterGold2 = master.wallet().balance(Wallet.GOLD);
        AtomicInteger rollSeq = new AtomicInteger(0);
        f.skillCraft.setCraftRollForTest(bound -> rollSeq.getAndIncrement() == 0 ? 0 : 0);

        CraftSkillBookResponse proxyBook = call(f, buyer, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_REGULAR)
                        .setScrollId(regularScroll.getId())
                        .setTargetSkillTableId(targetSkill)
                        .setExpectedRevision(0L)
                        .setClientRequestId(4003L)
                        .setProxyMasterPlayerId(master.playerId())
                        .build(), CraftSkillBookResponse.parser());
        assertThat(proxyBook.getErrorMessage().getId()).isZero();
        assertThat(proxyBook.getBonusFragmentsGranted()).isEqualTo(EquipCraftQualityRules.SCROLL_BONUS_FRAGMENTS_COUNT);
        assertThat(proxyBook.getCraftedBook().getCrafterPlayerId()).isEqualTo(master.playerId());
        assertThat(buyer.craft().scrollMasteryExp()).isZero(); // 代制不涨买家制卷熟练度
        assertThat(buyer.wallet().balance(Wallet.GOLD))
                .isEqualTo(buyerGold2 - regularScroll.getTargetedGoldCost() - 80L);
        assertThat(master.wallet().balance(Wallet.GOLD)).isEqualTo(masterGold2);
        assertThat(buyer.mailOutbox().size()).isEqualTo(2);

        // 落盘确认前绝不投递（防回档刷钱）；落盘确认后 Tick 逐条投递 95g 与 76g 给宗师并清空出箱
        f.mailOutbox.tick(f.world);
        f.drain();
        assertThat(f.sentOutboxMails).isEmpty();
        for (int i = 0; i < 6; i++) {
            f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
            f.mailOutbox.tick(f.world);
            f.drain();
        }
        assertThat(buyer.mailOutbox().isEmpty()).isTrue();
        assertThat(f.sentOutboxMails).hasSize(2);
        assertThat(f.sentOutboxMails.get(0).getRecipientPlayerId()).isEqualTo(master.playerId());
        assertThat(f.sentOutboxMails.get(0).getAttachments().getCurrencies(0).getAmount()).isEqualTo(95L);
        assertThat(f.sentOutboxMails.get(1).getRecipientPlayerId()).isEqualTo(master.playerId());
        assertThat(f.sentOutboxMails.get(1).getAttachments().getCurrencies(0).getAmount()).isEqualTo(76L);
        // 返还的 2 张悟道残页必须为绑定堆
        long boundFrags = buyer.bags().bag(BagType.INVENTORY).total(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID)
                - buyer.bags().bag(BagType.INVENTORY).totalTradable(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID);
        assertThat(boundFrags).isEqualTo(2L);

        // 宗师关摊后代打拒绝 28030
        SetCraftProxyStallResponse closed = call(f, master, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder().setOpen(false).build(), SetCraftProxyStallResponse.parser());
        assertThat(closed.getErrorMessage().getId()).isZero();
        CraftSkillBookResponse afterClose = call(f, buyer, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_REGULAR)
                        .setScrollId(regularScroll.getId())
                        .setTargetSkillTableId(targetSkill)
                        .setExpectedRevision(1L)
                        .setClientRequestId(4004L)
                        .setProxyMasterPlayerId(master.playerId())
                        .build(), CraftSkillBookResponse.parser());
        assertThat(afterClose.getErrorMessage().getId()).isEqualTo(equip_error.kEquipProxyMasterUnavailable_VALUE);
    }

    @Test
    void 既有纯T装备在可交易料充足时即使包里有绑定堆且传允许绑定也不误绑() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 2099L);
        assertThat(f.attributes.gmSetLevel(player, 60)).isZero();
        assertThat(f.currency.add(player, Wallet.GOLD, 10_000L, Reason.GM_GRANT).ok()).isTrue();

        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var accessoryRecipe = rules.getRecipesList().stream()
                .filter(r -> r.getProduct().getEquipProfile() == 2)
                .findFirst().orElseThrow();
        var normalAffix = rules.getAffixesList().stream()
                .filter(a -> a.getPool() == 3 && !a.getPolishExempt())
                .findFirst().orElseThrow();

        // 同时拥有充足可交易料 + 额外绑定料
        Map<Integer, Long> tradableMats = new java.util.LinkedHashMap<>();
        tradableMats.merge(accessoryRecipe.getMaterialConfigId(), (long) accessoryRecipe.getMaterialCount(), Long::sum);
        tradableMats.merge(normalAffix.getMaterialConfigId(), (long) normalAffix.getMaterialCount(), Long::sum);
        assertThat(f.bags.addItems(player, BagType.INVENTORY, tradableMats,
                false, Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY, Map.of(
                normalAffix.getMaterialConfigId(), 5L), true, Reason.GM_GRANT, 0, "").tip()).isZero();

        CommitEquipmentCraftResponse crafted = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(5001L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(accessoryRecipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(crafted.getErrorMessage().getId()).isZero();
        long itemId = crafted.getItemId();
        assertThat(player.bags().bag(BagType.INVENTORY).item(itemId).bound()).isFalse();

        // 预览与提交（哪怕携带 allow_bound_materials=true）：因纯 T 词条料充足，will_bind_item 必须为 false，提交仅扣 T 堆、装备不误绑
        PreviewEquipmentCraftResponse preview = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder()
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setAffixId(normalAffix.getAffixId())
                                .setAllowBoundMaterials(true))
                        .build(), PreviewEquipmentCraftResponse.parser());
        assertThat(preview.getErrorMessage().getId()).isZero();
        assertThat(preview.getPreview().getWillBindItem()).isFalse();
        assertThat(preview.getPreview().getResult().getBound()).isFalse();

        CommitEquipmentCraftResponse installed = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(5002L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(itemId).setAffixSlot(1).setAffixId(normalAffix.getAffixId())
                                .setAllowBoundMaterials(true))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(installed.getErrorMessage().getId()).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).item(itemId).bound()).isFalse();
        // 5 份绑定料应完整保留，只扣了可交易料
        assertThat(player.bags().bag(BagType.INVENTORY).total(normalAffix.getMaterialConfigId())).isEqualTo(5L);
        assertThat(player.bags().bag(BagType.INVENTORY).plainTradableQuantity(normalAffix.getMaterialConfigId())).isZero();
    }

    @Test
    void 解封剥离符清空绑定养成恢复纯T原胚_且流派副套继承符无损继承并强制转绑() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 2105L);
        assertThat(f.attributes.gmSetLevel(player, 60)).isZero();

        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var weaponRecipe = rules.getRecipesList().stream()
                .filter(r -> r.getProduct().getEquipProfile() == 1)
                .findFirst().orElseThrow();
        var affix = rules.getAffixesList().stream()
                .filter(a -> a.getPool() == 1)
                .findFirst().orElseThrow();

        // 发放打造材料 + 1 张绑定解封剥离符(3409) + 1 张绑定流派副套继承符(3410)
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(weaponRecipe.getMaterialConfigId(), (long) weaponRecipe.getMaterialCount() * 2L),
                false, Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                Map.of(EquipCraftQualityRules.UNSEAL_TALISMAN_CONFIG_ID, 1L,
                        EquipCraftQualityRules.INHERIT_TALISMAN_CONFIG_ID, 1L),
                true, Reason.GM_GRANT, 0, "").tip()).isZero();

        // 打造两件同部位同等级段武器：源武器(设为绑定 +5 / pity=3 / 含词条与宗师署名) 与 副套武器(纯可交易 +0)
        CommitEquipmentCraftResponse craftSrc = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(6001L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(weaponRecipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(craftSrc.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        long srcId = craftSrc.getItemId();

        CommitEquipmentCraftResponse craftAlt = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(6002L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(weaponRecipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(craftAlt.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        long altId = craftAlt.getItemId();

        BagItem srcItem = player.bags().bag(BagType.INVENTORY).item(srcId);
        srcItem.setBound(true);
        srcItem.equipment(srcItem.equipment().toBuilder()
                .setEnhanceLevel(5)
                .setEnhancePityCount(3)
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                        .setSlot(1).setAffixId(affix.getAffixId()).setQualityTier(5))
                .setCrafterPlayerId(999L)
                .setCrafterName("测试宗师")
                .addGems(EquipmentGemState.newBuilder().setSocket(1).setGemConfigId(3201))
                .build());
        int masteryBefore = player.craft().forgeMasteryExp();

        // 1. 流派副套继承符(3410)：预览 will_bind_item=true、result.bound=true、enhance_level=5；提交后副套 +5/pity=3 且强制转绑，源武器不动
        EquipmentCraftChoice inheritChoice = EquipmentCraftChoice.newBuilder()
                .setOperation(EquipmentCraftChoice.Operation.INHERIT_ENHANCE)
                .setItemId(altId)
                .setSourceItemId(srcId)
                .build();
        PreviewEquipmentCraftResponse inheritPreview = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(inheritChoice).build(),
                PreviewEquipmentCraftResponse.parser());
        assertThat(inheritPreview.getErrorMessage().getId()).isZero();
        assertThat(inheritPreview.getPreview().getAvailable()).isTrue();
        assertThat(inheritPreview.getPreview().getWillBindItem()).isTrue();
        assertThat(inheritPreview.getPreview().getResult().getBound()).isTrue();
        assertThat(inheritPreview.getPreview().getResult().getEnhanceLevel()).isEqualTo(5);

        CommitEquipmentCraftResponse inheritResp = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(6003L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(2L)
                        .setChoice(inheritChoice)
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(inheritResp.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        BagItem altAfter = player.bags().bag(BagType.INVENTORY).item(altId);
        assertThat(altAfter.bound()).isTrue();
        assertThat(altAfter.equipment().getEnhanceLevel()).isEqualTo(5);
        assertThat(altAfter.equipment().getEnhancePityCount()).isEqualTo(3);
        assertThat(player.craft().forgeMasteryExp()).isEqualTo(masteryBefore); // 继承不涨铸器熟练度

        // 2. 解封剥离符(3409)：镶嵌宝石时拒绝；摘除宝石后预览与提交成功：强化/保底/词条/署名全清空、转为纯可交易 T，即便消耗绑定剥离符也不回绑
        EquipmentCraftChoice unsealChoice = EquipmentCraftChoice.newBuilder()
                .setOperation(EquipmentCraftChoice.Operation.UNSEAL_TO_TRADABLE)
                .setItemId(srcId)
                .build();
        CommitEquipmentCraftResponse blockedUnseal = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(6004L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(3L)
                        .setChoice(unsealChoice)
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(blockedUnseal.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.REJECTED);

        srcItem = player.bags().bag(BagType.INVENTORY).item(srcId);
        srcItem.equipment(srcItem.equipment().toBuilder().clearGems().build());

        PreviewEquipmentCraftResponse unsealPreview = call(f, player, BAG_SERVICE, "PreviewEquipmentCraft",
                PreviewEquipmentCraftRequest.newBuilder().setChoice(unsealChoice).build(),
                PreviewEquipmentCraftResponse.parser());
        assertThat(unsealPreview.getErrorMessage().getId()).isZero();
        assertThat(unsealPreview.getPreview().getAvailable()).isTrue();
        assertThat(unsealPreview.getPreview().getWillBindItem()).isFalse();
        assertThat(unsealPreview.getPreview().getResult().getBound()).isFalse();
        assertThat(unsealPreview.getPreview().getResult().getEnhanceLevel()).isZero();

        CommitEquipmentCraftResponse unsealResp = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(6005L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(3L)
                        .setChoice(unsealChoice)
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(unsealResp.getOutcome()).isEqualTo(CommitEquipmentCraftResponse.Outcome.APPLIED);
        BagItem srcAfter = player.bags().bag(BagType.INVENTORY).item(srcId);
        assertThat(srcAfter.bound()).isFalse();
        assertThat(srcAfter.equipment().getEnhanceLevel()).isZero();
        assertThat(srcAfter.equipment().getEnhancePityCount()).isZero();
        assertThat(srcAfter.equipment().getFixedAffixesCount()).isZero();
        assertThat(srcAfter.equipment().getCrafterName()).isEmpty();
        assertThat(srcAfter.equipment().getCrafterPlayerId()).isZero();
        assertThat(player.craft().forgeMasteryExp()).isEqualTo(masteryBefore); // 解封不涨铸器熟练度
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipCraftQualityRules.UNSEAL_TALISMAN_CONFIG_ID)).isZero();
        assertThat(player.bags().bag(BagType.INVENTORY).total(EquipCraftQualityRules.INHERIT_TALISMAN_CONFIG_ID)).isZero();
    }

    @Test
    void 跨节点与离线宗师代打_24小时营业_本地掷品相署名与异步邮件分成() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer master = f.enter(11, 3101L);
        ScenePlayer buyer = f.enter(12, 3102L);
        assertThat(f.attributes.gmSetLevel(buyer, 60)).isZero();
        assertThat(f.currency.add(buyer, Wallet.GOLD, 10_000L, Reason.GM_GRANT).ok()).isTrue();

        master.craft().replace(PlayerCraftState.newBuilder()
                .setForgeMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .setScrollMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .build());
        SetCraftProxyStallResponse opened = call(f, master, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder().setOpen(true).setProxyForgeFeeGold(100L).setProxyScrollFeeGold(80L).build(),
                SetCraftProxyStallResponse.parser());
        assertThat(opened.getErrorMessage().getId()).isZero();

        // 宗师下线离场（不再位于本 scene 节点内存中）
        f.world.onPlayerLeave(BattleFixture.LINK, com.game.api.proto.PlayerLeave.newBuilder()
                .setSessionId(11).setPlayerId(master.playerId()).build());
        f.repo.completeAll();
        assertThat(f.world.playerById(3101L)).isNull();

        // 共享目录依然展示离线宗师摊位
        ListCraftProxyStallsResponse stalls = call(f, buyer, SKILL_SERVICE, "ListCraftProxyStalls",
                ListCraftProxyStallsRequest.getDefaultInstance(), ListCraftProxyStallsResponse.parser());
        assertThat(stalls.getStallsList()).singleElement().satisfies(s -> {
            assertThat(s.getMasterPlayerId()).isEqualTo(3101L);
            assertThat(s.getProxyForgeFeeGold()).isEqualTo(100L);
            assertThat(s.getProxyScrollFeeGold()).isEqualTo(80L);
        });

        // 买家向离线宗师下装备代打单：本地原子掷品相与署名 + 扣 100g 流通银两 + 95g 进买家待寄出箱
        GetEquipmentCraftRulesResponse rules = call(f, buyer, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var recipe = rules.getRecipesList().stream().filter(r -> r.getProduct().getEquipProfile() == 1).findFirst().orElseThrow();
        var affix = rules.getAffixesList().stream().filter(a -> a.getPool() == 1 && !a.getPolishExempt()).findFirst().orElseThrow();
        Map<Integer, Long> mats = new java.util.LinkedHashMap<>();
        mats.merge(recipe.getMaterialConfigId(), 50L, Long::sum);
        mats.merge(affix.getMaterialConfigId(), 50L, Long::sum);
        assertThat(f.bags.addItems(buyer, BagType.INVENTORY, mats, false, Reason.GM_GRANT, 0, "").tip()).isZero();

        CommitEquipmentCraftResponse base = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(7001L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(recipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(base.getErrorMessage().getId()).isZero();

        long goldBefore = buyer.wallet().balance(Wallet.GOLD);
        f.craft.setQualityRollForTest(bound -> 9200); // 宗师档 9200 命中 5 档极品
        CommitEquipmentCraftResponse proxyInstall = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(7002L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(base.getItemId()).setAffixSlot(1).setAffixId(affix.getAffixId())
                                .setProxyMasterPlayerId(3101L))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(proxyInstall.getErrorMessage().getId()).isZero();
        assertThat(proxyInstall.getResultQualityTier()).isEqualTo(5);
        assertThat(buyer.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore - 100L);
        assertThat(buyer.bags().bag(BagType.INVENTORY).item(base.getItemId()).equipment().getCrafterPlayerId()).isEqualTo(3101L);
        assertThat(buyer.mailOutbox().size()).isEqualTo(1);

        // 模拟收件人已删角(kMailRecipientDeleted)：出箱干净摘除该条，不永久卡死买家队列
        f.nextOutboxReplyTip = com.game.table.MailErrorTip.mail_error.kMailRecipientDeleted_VALUE;
        for (int i = 0; i < 4; i++) {
            f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
            f.mailOutbox.tick(f.world);
            f.drain();
        }
        assertThat(f.sentOutboxMails).hasSize(1);
        assertThat(f.sentOutboxMails.get(0).getRecipientPlayerId()).isEqualTo(3101L);
        assertThat(f.sentOutboxMails.get(0).getAttachments().getCurrencies(0).getAmount()).isEqualTo(95L);
        assertThat(buyer.mailOutbox().isEmpty()).isTrue();
    }

    @Test
    void 控辅强化公平受益_气血与奶盾控场克制放大且速度永不随强化放大() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 3201L);
        assertThat(f.attributes.gmSetLevel(player, 80)).isZero();

        // 穿戴四件 80 级基础装（3181 武器 / 3182 帽子 / 3183 衣服 / 3184 鞋子），均装满品相 D03 施放治疗(103, 800 bps)
        int[] configs = {3181, 3182, 3183, 3184};
        long[] ids = new long[configs.length];
        for (int i = 0; i < configs.length; i++) {
            var add = f.bags.addItems(player, BagType.INVENTORY, Map.of(configs[i], 1L), false, Reason.GM_GRANT, 0, "");
            assertThat(add.tip()).isZero();
            ids[i] = add.written().getFirst().guids().getFirst();
            BagItem it = player.bags().bag(BagType.INVENTORY).item(ids[i]);
            it.equipment(EquipmentState.newBuilder()
                    .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                            .setSlot(1).setAffixId(103).setQualityTier(5))
                    .build());
            assertThat(f.equipment.equip(player, ids[i]).tip()).isZero();
        }

        // +0 时 4 件 D03 = 3200 bps，与旧口径完全一致
        assertThat(player.attributes().equipment().effects().getOutgoingHealBps()).isEqualTo(3200);
        assertThat(com.game.battle.engine.SupportEffectRules.begin(player.attributes().equipment().effects())
                .build().getHealing().getEquipmentCapBps()).isEqualTo(3200);

        // 单件武器升到 +10（+20% * 50% = +10% -> 800->880，其余三件仍 +0=2400，总计 3280，有效上限 3520）
        BagItem weapon = player.bags().bag(BagType.EQUIPMENT).item(ids[0]);
        weapon.equipment(weapon.equipment().toBuilder().setEnhanceLevel(10).build());
        f.attributes.equipmentChanged(player);
        assertThat(player.attributes().equipment().effects().getOutgoingHealBps()).isEqualTo(3280);
        var equipBagSnap = call(f, player, BAG_SERVICE, "GetBag",
                GetBagRequest.newBuilder().setBagType(BagType.EQUIPMENT.code()).build(), GetBagResponse.parser());
        BagItemInfo weaponInfo = equipBagSnap.getBag().getItemsList().stream()
                .filter(i -> i.getItemId() == ids[0]).findFirst().orElseThrow();
        assertThat(weaponInfo.getFixedAffixSlots(0).getSelected().getValueBps()).isEqualTo(880);

        // 四件全 +11（+24% * 50% = +12% -> 单件 896，四件 3584，不被旧 3200 上限吞掉）
        for (long id : ids) {
            BagItem piece = player.bags().bag(BagType.EQUIPMENT).item(id);
            piece.equipment(piece.equipment().toBuilder().setEnhanceLevel(11).build());
        }
        f.attributes.equipmentChanged(player);
        assertThat(player.attributes().equipment().effects().getOutgoingHealBps()).isEqualTo(3584);
        assertThat(com.game.battle.engine.SupportEffectRules.begin(player.attributes().equipment().effects())
                .build().getHealing().getEquipmentCapBps()).isEqualTo(3584);

        // 鞋子(3184) +11 速度红线：防御按 +24% 放大，基础速度(attr 11)增幅严格为 0！
        var shoesSnap = call(f, player, BAG_SERVICE, "GetBag",
                GetBagRequest.newBuilder().setBagType(BagType.EQUIPMENT.code()).build(), GetBagResponse.parser());
        BagItemInfo shoesInfo = shoesSnap.getBag().getItemsList().stream()
                .filter(i -> i.getItemId() == ids[3]).findFirst().orElseThrow();
        assertThat(shoesInfo.getBaseAttrsList()).anySatisfy(line -> {
            assertThat(line.getAttrId()).isEqualTo(8); // 防御
            assertThat(line.getEnhanceBonus()).isGreaterThan(0L);
        });
        assertThat(shoesInfo.getBaseAttrsList()).anySatisfy(line -> {
            assertThat(line.getAttrId()).isEqualTo(11); // 速度
            assertThat(line.getEnhanceBonus()).isZero();
        });

        // 控场命中(D05=105, 400 bps) 在 +11 武器上按 25% 份额受益：400 -> 424；纯输出 D01(101, 800 bps) 保持 800 不重复放大
        weapon.equipment(weapon.equipment().toBuilder().clearFixedAffixes()
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder().setSlot(1).setAffixId(105).setQualityTier(5))
                .build());
        f.attributes.equipmentChanged(player);
        assertThat(player.attributes().equipment().effects().getAilmentHitBpsOrDefault(1, 0)).isEqualTo(424);

        weapon.equipment(weapon.equipment().toBuilder().clearFixedAffixes()
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder().setSlot(1).setAffixId(101).setQualityTier(5))
                .build());
        f.attributes.equipmentChanged(player);
        assertThat(player.attributes().equipment().effects().getPhysicalAttackBps()).isEqualTo(800);
    }

    @Test
    void playerCraft恢复修改存盘保留未知字段往返() {
        var unknown = com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(9999, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(123456L).build())
                .build();
        PlayerCraftState original = PlayerCraftState.newBuilder()
                .setForgeMasteryExp(60)
                .setUnreadProxyDigestOrders(2)
                .setUnknownFields(unknown)
                .build();
        var craft = com.game.scene.player.PlayerCraft.restore(original);
        craft.replace(craft.state().toBuilder()
                .setUnreadProxyDigestOrders(0)
                .setProxyOrdersCompleted(2)
                .build());
        PlayerCraftState saved = craft.toState();
        assertThat(saved.getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(saved.getUnreadProxyDigestOrders()).isZero();
        assertThat(saved.getUnknownFields().getField(9999).getVarintList()).containsExactly(123456L);
    }

    @Test
    void 离线名匠口碑_百炼宗师雅号解锁_未读战报幂等确认与西市极品热榜Top3() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer master1 = f.enter(11, 4101L);
        ScenePlayer master2 = f.enter(12, 4102L);
        ScenePlayer master3 = f.enter(13, 4103L);
        ScenePlayer master4 = f.enter(14, 4104L);
        ScenePlayer buyer = f.enter(15, 4201L);
        assertThat(f.attributes.gmSetLevel(buyer, 60)).isZero();
        assertThat(f.currency.add(buyer, Wallet.GOLD, 50_000L, Reason.GM_GRANT).ok()).isTrue();

        // master1 已有 4 件极品（再出 1 件极品解锁 2 号雅号“百炼宗师”）
        master1.craft().replace(PlayerCraftState.newBuilder()
                .setForgeMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .setScrollMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .setSupremeForgedCount(4)
                .build());
        for (ScenePlayer m : List.of(master2, master3, master4)) {
            m.craft().replace(PlayerCraftState.newBuilder()
                    .setForgeMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                    .setScrollMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                    .build());
        }
        for (ScenePlayer m : List.of(master1, master2, master3, master4)) {
            SetCraftProxyStallResponse opened = call(f, m, SKILL_SERVICE, "SetCraftProxyStall",
                    SetCraftProxyStallRequest.newBuilder()
                            .setOpen(true).setProxyForgeFeeGold(100L).setProxyScrollFeeGold(80L).build(),
                    SetCraftProxyStallResponse.parser());
            assertThat(opened.getErrorMessage().getId()).isZero();
        }
        f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);

        // master1 下线离场（模拟离线/跨线状态）
        f.world.onPlayerLeave(BattleFixture.LINK, com.game.api.proto.PlayerLeave.newBuilder()
                .setSessionId(11).setPlayerId(master1.playerId()).build());
        f.repo.completeAll();
        assertThat(f.world.playerById(4101L)).isNull();

        // 准备买家装备与稀世书材料
        GetEquipmentCraftRulesResponse rules = call(f, buyer, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var recipe = rules.getRecipesList().stream().filter(r -> r.getProduct().getEquipProfile() == 1).findFirst().orElseThrow();
        var affix = rules.getAffixesList().stream().filter(a -> a.getPool() == 1 && !a.getPolishExempt()).findFirst().orElseThrow();
        SkillScrollCraftTable rareScroll = f.skillCraftTables.enabledScrolls().stream()
                .filter(s -> s.getRarityTier() == 1).findFirst().orElseThrow();
        int rareSkillId = f.skillCraftTables.scrollSkillIds(rareScroll.getId()).getFirst();

        Map<Integer, Long> mats = new java.util.LinkedHashMap<>();
        mats.merge(recipe.getMaterialConfigId(), 200L, Long::sum);
        mats.merge(affix.getMaterialConfigId(), 200L, Long::sum);
        mats.merge(rareScroll.getRareScrollItemId(), 10L, Long::sum);
        mats.merge(rareScroll.getRareInkItemId(), 10L, Long::sum);
        assertThat(f.bags.addItems(buyer, BagType.INVENTORY, mats, false, Reason.GM_GRANT, 0, "").tip()).isZero();

        // 造 3 件白板装分别找 master1(离线极品)、master2(在线极品)、master3(在线凡品) 代打
        long[] equipIds = new long[3];
        long forgeRev = 0L;
        for (int i = 0; i < 3; i++) {
            CommitEquipmentCraftResponse base = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                    CommitEquipmentCraftRequest.newBuilder()
                            .setClientRequestId(8001L + i)
                            .setRulesFingerprint(rules.getRulesFingerprint())
                            .setExpectedForgeRevision(forgeRev++)
                            .setChoice(EquipmentCraftChoice.newBuilder()
                                    .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                    .setConfigId(recipe.getProduct().getConfigId()))
                            .build(), CommitEquipmentCraftResponse.parser());
            assertThat(base.getErrorMessage().getId()).isZero();
            equipIds[i] = base.getItemId();
        }

        // 1) master1 离线极品单（95 净银两，score += 4）
        f.craft.setQualityRollForTest(bound -> 9999);
        CommitEquipmentCraftResponse r1 = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(8011L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(forgeRev++)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(equipIds[0]).setAffixSlot(1).setAffixId(affix.getAffixId())
                                .setProxyMasterPlayerId(4101L))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(r1.getErrorMessage().getId()).isZero();
        assertThat(r1.getResultQualityTier()).isEqualTo(5);

        // 2) master1 离线稀世制书单（76 净银两，score += 1 -> 总 score=5）
        CraftSkillBookResponse rRare = call(f, buyer, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_RARE)
                        .setScrollId(rareScroll.getId())
                        .setTargetSkillTableId(rareSkillId)
                        .setExpectedRevision(0L)
                        .setClientRequestId(8012L)
                        .setProxyMasterPlayerId(4101L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(rRare.getErrorMessage().getId()).isZero();

        // 3) master2 在线极品单（score = 4）
        f.craft.setQualityRollForTest(bound -> 9999);
        CommitEquipmentCraftResponse r2 = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(8013L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(forgeRev++)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(equipIds[1]).setAffixSlot(1).setAffixId(affix.getAffixId())
                                .setProxyMasterPlayerId(4102L))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(r2.getErrorMessage().getId()).isZero();

        // 4) master3 在线凡品单（score = 1）；master4 无单（score = 0）
        f.craft.setQualityRollForTest(bound -> 0);
        CommitEquipmentCraftResponse r3 = call(f, buyer, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(8014L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(forgeRev++)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(equipIds[2]).setAffixSlot(1).setAffixId(affix.getAffixId())
                                .setProxyMasterPlayerId(4103L))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(r3.getErrorMessage().getId()).isZero();

        // 查询西市名匠摊位：离线 master1 实时叠加待回账口碑与 Top 1 热榜，master2=Top 2，master3=Top 3，master4=0
        ListCraftProxyStallsResponse stalls = call(f, buyer, SKILL_SERVICE, "ListCraftProxyStalls",
                ListCraftProxyStallsRequest.getDefaultInstance(), ListCraftProxyStallsResponse.parser());
        assertThat(stalls.getStallsList()).hasSize(4);
        var byId = new java.util.HashMap<Long, com.game.proto.CraftProxyStallInfo>();
        for (var s : stalls.getStallsList()) byId.put(s.getMasterPlayerId(), s);
        assertThat(byId.get(4101L).getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(byId.get(4101L).getSupremeForgedCount()).isEqualTo(5);
        assertThat(byId.get(4101L).getRareBooksCraftedCount()).isEqualTo(1);
        assertThat(byId.get(4101L).getRecent24HOrders()).isEqualTo(2);
        assertThat(byId.get(4101L).getRecent24HSupreme()).isEqualTo(1);
        assertThat(byId.get(4101L).getHotRank()).isEqualTo(1);
        assertThat(byId.get(4102L).getHotRank()).isEqualTo(2);
        assertThat(byId.get(4103L).getHotRank()).isEqualTo(3);
        assertThat(byId.get(4104L).getHotRank()).isZero();

        // master1 重新上线登录（携带离线前落盘的存档与新 epoch=2）：自动回账待结算口碑，解锁百炼宗师(2)，生成未读战报
        com.game.player.store.state.PlayerState savedMaster1 = f.repo.saves().stream()
                .filter(s -> s.playerId() == 4101L)
                .reduce((first, second) -> second)
                .orElseThrow()
                .state();
        ScenePlayer master1Back = f.enter(19, 4101L, 2L, f.scene1, savedMaster1);
        GetSkillCraftingStateResponse stBack = call(f, master1Back, SKILL_SERVICE, "GetSkillCraftingState",
                GetSkillCraftingStateRequest.getDefaultInstance(), GetSkillCraftingStateResponse.parser());
        assertThat(stBack.getErrorMessage().getId()).isZero();
        var snap = stBack.getState();
        assertThat(snap.getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(snap.getSupremeForgedCount()).isEqualTo(5);
        assertThat(snap.getRareBooksCraftedCount()).isEqualTo(1);
        assertThat(snap.getUnlockedCraftTitleIdsList()).contains(1, 2, 4, 6);
        assertThat(snap.hasUnreadProxyDigest()).isTrue();
        assertThat(snap.getUnreadProxyDigest().getOrdersCompleted()).isEqualTo(2);
        assertThat(snap.getUnreadProxyDigest().getSupremeForgedCount()).isEqualTo(1);
        assertThat(snap.getUnreadProxyDigest().getRareBooksCraftedCount()).isEqualTo(1);
        assertThat(snap.getUnreadProxyDigest().getNetGoldEarned()).isEqualTo(171L);
        assertThat(snap.getUnreadProxyDigest().getLastBuyerName()).isNotEmpty();
        assertThat(snap.getUnreadProxyDigest().getNewlyUnlockedTitleMask() & (1 << 1)).isNotZero();

        // 佩戴新解锁的“百炼宗师”(2) + 招牌(1)
        SetCraftProxyStallResponse equipTitle = call(f, master1Back, SKILL_SERVICE, "SetCraftProxyStall",
                SetCraftProxyStallRequest.newBuilder()
                        .setOpen(true).setProxyForgeFeeGold(100L).setProxyScrollFeeGold(80L)
                        .setMasterShowcaseTag(1).setEquippedCraftTitleId(2).build(),
                SetCraftProxyStallResponse.parser());
        assertThat(equipTitle.getErrorMessage().getId()).isZero();
        assertThat(equipTitle.getState().getEquippedCraftTitleId()).isEqualTo(2);
        assertThat(equipTitle.getState().getMasterShowcaseTag()).isEqualTo(1);

        // 幂等确认战报（573）：首次清空，二次调用保持幂等成功且口碑不回退
        com.game.proto.AckCraftProxyDigestResponse ack1 = call(f, master1Back, SKILL_SERVICE, "AckCraftProxyDigest",
                com.game.proto.AckCraftProxyDigestRequest.getDefaultInstance(),
                com.game.proto.AckCraftProxyDigestResponse.parser());
        assertThat(ack1.getErrorMessage().getId()).isZero();
        assertThat(ack1.getState().hasUnreadProxyDigest()).isFalse();
        assertThat(ack1.getState().getProxyOrdersCompleted()).isEqualTo(2);

        com.game.proto.AckCraftProxyDigestResponse ack2 = call(f, master1Back, SKILL_SERVICE, "AckCraftProxyDigest",
                com.game.proto.AckCraftProxyDigestRequest.getDefaultInstance(),
                com.game.proto.AckCraftProxyDigestResponse.parser());
        assertThat(ack2.getErrorMessage().getId()).isZero();
        assertThat(ack2.getState().hasUnreadProxyDigest()).isFalse();
    }

    @Test
    void 远端原子Lua取走口碑防双倍入账且离场或存盘失败原样还回() {
        BattleFixture f = new BattleFixture();
        ScenePlayer master = f.enter(11, 5101L);
        master.craft().replace(PlayerCraftState.newBuilder()
                .setForgeMasteryExp(EquipCraftQualityRules.MASTER_EXP_THRESHOLD)
                .setProxyStallOpen(true)
                .setProxyForgeFeeGold(100L)
                .build());

        // 模拟远端 Redis 共享口碑账本（先取走返回数值，并发二次取走返回空；还回累加回账本）
        class FakeRemoteLedger implements CraftProxyStallDirectory.BackingStore {
            long o, s, r, g;
            String b = "";
            int takeCalls;
            final List<java.util.concurrent.CompletableFuture<java.util.Optional<CraftProxyStallDirectory.TakenReputation>>> pendingTakes =
                    new ArrayList<>();

            @Override
            public java.util.concurrent.CompletionStage<Void> putAsync(long masterPlayerId, byte[] payload) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> removeAsync(long masterPlayerId) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> incrReputationAsync(long masterPlayerId, String fieldSuffix, long delta) {
                switch (fieldSuffix) {
                    case "o" -> o += delta;
                    case "s" -> s += delta;
                    case "r" -> r += delta;
                    case "g" -> g += delta;
                    default -> { }
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> setLastBuyerAsync(long masterPlayerId, String buyerName) {
                b = buyerName;
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean hasRemoteReputationLedger() {
                return true;
            }

            @Override
            public java.util.concurrent.CompletionStage<java.util.Optional<CraftProxyStallDirectory.TakenReputation>> takeReputationAsync(
                    long masterPlayerId) {
                takeCalls++;
                var fut = new java.util.concurrent.CompletableFuture<java.util.Optional<CraftProxyStallDirectory.TakenReputation>>();
                pendingTakes.add(fut);
                return fut;
            }

            void completeOldestAtomicTake() {
                var fut = pendingTakes.removeFirst();
                var taken = new CraftProxyStallDirectory.TakenReputation((int) o, (int) s, (int) r, g, b);
                o = s = r = g = 0L;
                b = "";
                fut.complete(java.util.Optional.of(taken));
            }
        }

        FakeRemoteLedger remote = new FakeRemoteLedger();
        AtomicInteger saveCalls = new AtomicInteger();
        boolean[] failSave = {false};
        CraftProxyStallDirectory dir = new CraftProxyStallDirectory(remote, Runnable::run)
                .withReputationCallbacks(id -> f.world.playerById(id), p -> {
                    if (failSave[0]) {
                        throw new IllegalStateException("模拟存盘排队失败");
                    }
                    saveCalls.incrementAndGet();
                });

        // 1. 远端快照带来 2 单待回账；并发两次 drain 只发 1 次单飞 Lua，仅按原子取走结果入账一次并触发存盘
        remote.o = 2; remote.s = 1; remote.r = 1; remote.g = 190L; remote.b = "买家甲";
        dir.applyRemoteReputationSnapshot(Map.of(
                "5101:o", "2", "5101:s", "1", "5101:r", "1", "5101:g", "190", "5101:b", "买家甲"));
        assertThat(dir.drainPendingReputation(master)).isFalse();
        assertThat(dir.drainPendingReputation(master)).isFalse();
        assertThat(remote.takeCalls).isEqualTo(1);
        remote.completeOldestAtomicTake();
        assertThat(master.craft().state().getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(master.craft().state().getSupremeForgedCount()).isEqualTo(1);
        assertThat(master.craft().state().getRareBooksCraftedCount()).isEqualTo(1);
        assertThat(master.craft().state().getUnreadProxyDigestNetGold()).isEqualTo(190L);
        assertThat(saveCalls.get()).isEqualTo(1);

        // 2. 陈旧远端镜像再送回旧值，但 Redis 已被取空 -> 二次 Lua 返回 0，严禁重复入账
        dir.applyRemoteReputationSnapshot(Map.of("5101:o", "2", "5101:g", "190"));
        dir.drainPendingReputation(master);
        assertThat(remote.takeCalls).isEqualTo(2);
        remote.completeOldestAtomicTake();
        assertThat(master.craft().state().getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(master.craft().state().getUnreadProxyDigestNetGold()).isEqualTo(190L);
        assertThat(saveCalls.get()).isEqualTo(1);

        // 3. 存盘回调抛异常：回滚内存并原样还回 Redis
        remote.o = 1; remote.g = 95L; remote.b = "买家乙";
        dir.applyRemoteReputationSnapshot(Map.of("5101:o", "1", "5101:g", "95", "5101:b", "买家乙"));
        failSave[0] = true;
        dir.drainPendingReputation(master);
        remote.completeOldestAtomicTake();
        assertThat(master.craft().state().getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(remote.o).isEqualTo(1L);
        assertThat(remote.g).isEqualTo(95L);
        assertThat(remote.b).isEqualTo("买家乙");

        // 4. 宗师取走途中冻结：直接原样还回 Redis
        failSave[0] = false;
        dir.drainPendingReputation(master);
        com.game.scene.world.WorldTestAccess.startFreezing(master);
        remote.completeOldestAtomicTake();
        assertThat(master.craft().state().getProxyOrdersCompleted()).isEqualTo(2);
        assertThat(remote.o).isEqualTo(1L);
        assertThat(remote.g).isEqualTo(95L);

        // 5. parseTakenReputation 校验：负数归零、长度异常返回 empty
        assertThat(CraftProxyStallDirectory.parseTakenReputation(List.of("-3", "2", "", "100", "张三")))
                .hasValue(new CraftProxyStallDirectory.TakenReputation(0, 2, 0, 100L, "张三"));
        assertThat(CraftProxyStallDirectory.parseTakenReputation(List.of("1", "2"))).isEmpty();
    }

    @Test
    void 本人打造极品与自制稀世累计成就_摆摊校验失败不丢回账存盘_零单有银两仍显战报() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 5201L);
        assertThat(f.attributes.gmSetLevel(player, 60)).isZero();
        assertThat(f.currency.add(player, Wallet.GOLD, 50_000L, Reason.GM_GRANT).ok()).isTrue();

        // 1. 本人打造装备并安装极品非豁免词条 -> supreme_forged_count 自增
        GetEquipmentCraftRulesResponse rules = call(f, player, BAG_SERVICE, "GetEquipmentCraftRules",
                GetEquipmentCraftRulesRequest.getDefaultInstance(), GetEquipmentCraftRulesResponse.parser());
        var recipe = rules.getRecipesList().getFirst();
        var affix = rules.getAffixesList().stream()
                .filter(a -> !a.getPolishExempt() && a.getPool() == 1)
                .findFirst().orElseThrow();
        SkillScrollCraftTable rareScroll = f.skillCraftTables.enabledScrolls().stream()
                .filter(s -> s.getRarityTier() == 1).findFirst().orElseThrow();
        int rareSkillId = f.skillCraftTables.scrollSkillIds(rareScroll.getId()).getFirst();

        Map<Integer, Long> mats = new java.util.LinkedHashMap<>();
        mats.merge(recipe.getMaterialConfigId(), 50L, Long::sum);
        mats.merge(affix.getMaterialConfigId(), 50L, Long::sum);
        mats.merge(rareScroll.getRareScrollItemId(), 10L, Long::sum);
        mats.merge(rareScroll.getRareInkItemId(), 10L, Long::sum);
        assertThat(f.bags.addItems(player, BagType.INVENTORY, mats, false, Reason.GM_GRANT, 0, "").tip()).isZero();

        CommitEquipmentCraftResponse base = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(9101L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(0L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.CREATE_ITEM)
                                .setConfigId(recipe.getProduct().getConfigId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(base.getErrorMessage().getId()).isZero();

        f.craft.setQualityRollForTest(bound -> 9999);
        CommitEquipmentCraftResponse supreme = call(f, player, BAG_SERVICE, "CommitEquipmentCraft",
                CommitEquipmentCraftRequest.newBuilder()
                        .setClientRequestId(9102L)
                        .setRulesFingerprint(rules.getRulesFingerprint())
                        .setExpectedForgeRevision(1L)
                        .setChoice(EquipmentCraftChoice.newBuilder()
                                .setOperation(EquipmentCraftChoice.Operation.INSTALL_AFFIX)
                                .setItemId(base.getItemId()).setAffixSlot(1).setAffixId(affix.getAffixId()))
                        .build(), CommitEquipmentCraftResponse.parser());
        assertThat(supreme.getErrorMessage().getId()).isZero();
        assertThat(supreme.getResultQualityTier()).isEqualTo(EquipCraftQualityRules.MAX_QUALITY_TIER);
        assertThat(player.craft().state().getSupremeForgedCount()).isEqualTo(1);

        // 2. 本人定向制书稀世绝学 -> rare_books_crafted_count 自增
        CraftSkillBookResponse rareBook = call(f, player, SKILL_SERVICE, "CraftSkillBook",
                CraftSkillBookRequest.newBuilder()
                        .setMode(CraftSkillBookRequest.CraftMode.TARGETED_RARE)
                        .setScrollId(rareScroll.getId())
                        .setTargetSkillTableId(rareSkillId)
                        .setExpectedRevision(0L)
                        .setClientRequestId(9103L)
                        .build(), CraftSkillBookResponse.parser());
        assertThat(rareBook.getErrorMessage().getId()).isZero();
        assertThat(rareBook.getState().getRareBooksCraftedCount()).isEqualTo(1);

        // 3. setProxyStall 先回账待结算口碑后参数校验失败：outcome.mutated 必须为 true，防止丢失回账存盘
        f.proxyDirectory.recordOrderReputation(player.playerId(), 0, 0, 95L, "离线买家", null);
        SkillCraftService.StallOutcome rejectedAfterDrain = f.skillCraft.setProxyStall(player,
                SetCraftProxyStallRequest.newBuilder().setOpen(true).setMasterShowcaseTag(999).build());
        assertThat(rejectedAfterDrain.response().getErrorMessage().getId()).isNotZero();
        assertThat(rejectedAfterDrain.mutated()).isTrue();
        assertThat(player.craft().state().getProxyOrdersCompleted()).isEqualTo(1);

        // 4. unread_proxy_digest 仅 net_gold > 0 且 orders == 0 时也必须回传战报快照
        player.craft().replace(player.craft().state().toBuilder()
                .setUnreadProxyDigestOrders(0)
                .setUnreadProxyDigestNetGold(95L)
                .setUnreadProxyDigestNewTitlesMask(0)
                .build());
        GetSkillCraftingStateResponse st = f.skillCraft.getState(player);
        assertThat(st.getState().hasUnreadProxyDigest()).isTrue();
        assertThat(st.getState().getUnreadProxyDigest().getNetGoldEarned()).isEqualTo(95L);
    }
}
