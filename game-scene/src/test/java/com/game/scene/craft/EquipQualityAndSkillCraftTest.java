package com.game.scene.craft;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.EquipmentFixedAffixState;
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
        assertThat(master.wallet().balance(Wallet.GOLD)).isEqualTo(masterGoldBefore + 95L);
        assertThat(buyer.craft().forgeMasteryExp()).isEqualTo(buyerForgeExpBeforeProxy); // 代打不涨买家熟练度
        BagItem signedEquip = buyer.bags().bag(BagType.INVENTORY).item(baseEquip.getItemId());
        assertThat(signedEquip.equipment().getCrafterPlayerId()).isEqualTo(master.playerId());
        assertThat(signedEquip.equipment().getCrafterName()).isNotEmpty();

        // 买家使用宗师代制常规技能书（触发宗师 10% 额外返 2 张绑定残页 + 宗师署名 + 80g 扣款 / 76g 净入账）
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
        assertThat(master.wallet().balance(Wallet.GOLD)).isEqualTo(masterGold2 + 76L); // 80 - ceil(4) = 76
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
}
