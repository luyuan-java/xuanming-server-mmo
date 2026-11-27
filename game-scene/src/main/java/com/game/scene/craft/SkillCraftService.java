package com.game.scene.craft;

import static com.game.scene.world.SceneMessageIds.tip;
import static com.game.table.CommonErrorTip.common_error.*;
import static com.game.table.EquipErrorTip.equip_error.*;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.CraftedSkillBookEntryState;
import com.game.player.store.state.PlayerCraftState;
import com.game.proto.AckCraftProxyDigestResponse;
import com.game.proto.CraftProxyDigestSummary;
import com.game.proto.CraftProxyStallInfo;
import com.game.proto.CraftSkillBookRequest;
import com.game.proto.CraftSkillBookResponse;
import com.game.proto.CraftedSkillBookEntry;
import com.game.proto.DismantleSkillBookRequest;
import com.game.proto.DismantleSkillBookResponse;
import com.game.proto.GetSkillCraftingStateResponse;
import com.game.proto.LearnSkillBookRequest;
import com.game.proto.LearnSkillBookResponse;
import com.game.proto.ListCraftProxyStallsResponse;
import com.game.proto.SetCraftProxyStallRequest;
import com.game.proto.SetCraftProxyStallResponse;
import com.game.proto.SkillCraftMasterySnapshot;
import com.game.proto.SkillCraftingStateSnapshot;
import com.game.proto.SkillScrollCatalogInfo;
import com.game.scene.attribute.AttributeService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.equip.EquipCraftQualityRules;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.mail.PlayerMailOutboxService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.pet.PetService;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.Wallet;
import com.game.scene.skill.ClassSkillCatalog;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.table.SkillScrollCraftTable;
import com.game.table.SkillTable;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * 率土式分卷宗永久技能制书、研习、拆页返还与主城宗师原子代打摊位（459–464；对齐 C++ {@code PlayerCraftSystem}）。
 * 仅在场景逻辑线程调用。
 */
public final class SkillCraftService {

    private static final int OK = 0;

    public record CraftOutcome(CraftSkillBookResponse response, boolean mutated, ScenePlayer creditedProxyMaster) {
    }

    public record LearnOutcome(LearnSkillBookResponse response, boolean mutated, boolean pushPanel, boolean pushPets) {
    }

    public record DismantleOutcome(DismantleSkillBookResponse response, boolean mutated) {
    }

    public record StallOutcome(SetCraftProxyStallResponse response, boolean mutated) {
    }

    public record GetStateOutcome(GetSkillCraftingStateResponse response, boolean mutated) {
    }

    public record AckOutcome(AckCraftProxyDigestResponse response, boolean mutated) {
    }

    private final SkillCraftTables tables;
    private final BagService bags;
    private final CurrencyService currency;
    private final AttributeService attributes;
    private final PetService pets;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private CraftProxyStallDirectory proxyDirectory;
    private PlayerMailOutboxService mailOutbox;
    private boolean craftUnlockGateEnabled;
    private IntUnaryOperator craftRoll;

    public SkillCraftService(SkillCraftTables tables, BagService bags, CurrencyService currency,
                             AttributeService attributes, PetService pets, SceneClock clock, SceneMetrics metrics) {
        this.tables = Objects.requireNonNull(tables);
        this.bags = Objects.requireNonNull(bags);
        this.currency = Objects.requireNonNull(currency);
        this.attributes = Objects.requireNonNull(attributes);
        this.pets = pets;
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public SkillCraftTables tables() {
        return tables;
    }

    /** 绑定跨节点/离线名匠代打共享摊位目录与待寄个人邮件出箱服务。 */
    public SkillCraftService withProxyInfrastructure(CraftProxyStallDirectory proxyDirectory,
                                                     PlayerMailOutboxService mailOutbox) {
        this.proxyDirectory = proxyDirectory;
        this.mailOutbox = mailOutbox;
        return this;
    }

    public boolean isCraftUnlockGateEnabled() {
        return craftUnlockGateEnabled;
    }

    /** 默认关闭以兼容首测直接修习验收；开启后未研习对应技能书的新学请求回 kSkillCraftBookRequired。 */
    public void setCraftUnlockGateForTest(boolean enabled) {
        this.craftUnlockGateEnabled = enabled;
    }

    public void setCraftRollForTest(IntUnaryOperator craftRoll) {
        this.craftRoll = craftRoll;
    }

    private int rollBelow(int bound) {
        if (bound <= 1) {
            return 0;
        }
        if (craftRoll != null) {
            return Integer.remainderUnsigned(craftRoll.applyAsInt(bound), bound);
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }

    private int checkWritable(ScenePlayer player) {
        if (player.frozen() || player.switchPhase() != SwitchPhase.NONE) {
            return kSkillUpgradeBusy_VALUE;
        }
        if (player.inBattle() || player.battle().recovery() != com.game.scene.battle.PlayerBattle.Recovery.READY) {
            metrics.battleGateReject(SceneMetrics.BattleGate.SKILL);
            return kSkillUpgradeBusy_VALUE;
        }
        return OK;
    }

    private static CraftedSkillBookEntry toProtoBook(CraftedSkillBookEntryState s) {
        return CraftedSkillBookEntry.newBuilder()
                .setBookId(s.getBookId())
                .setSkillTableId(s.getSkillTableId())
                .setScrollId(s.getScrollId())
                .setRarityTier(s.getRarityTier())
                .setBound(s.getBound())
                .setCrafterName(s.getCrafterName())
                .setCrafterPlayerId(s.getCrafterPlayerId())
                .build();
    }

    public SkillCraftingStateSnapshot buildStateSnapshot(ScenePlayer player) {
        PlayerCraftState craft = player.craft().state();
        Bag inventory = player.bags().bag(BagType.INVENTORY);

        int forgeExp = craft.getForgeMasteryExp();
        int forgeTier = EquipCraftQualityRules.masteryTierForExp(forgeExp);
        SkillCraftMasterySnapshot forgeSnap = SkillCraftMasterySnapshot.newBuilder()
                .setTier(forgeTier)
                .setTierName(EquipCraftQualityRules.masteryTierName(forgeTier))
                .setExp(forgeExp)
                .setNextTierExp(EquipCraftQualityRules.masteryNextExp(forgeExp))
                .setBonusRateBps(EquipCraftQualityRules.forgeTopQualityRateBps(forgeTier))
                .build();

        int scrollExp = craft.getScrollMasteryExp();
        int scrollTier = EquipCraftQualityRules.masteryTierForExp(scrollExp);
        SkillCraftMasterySnapshot scrollSnap = SkillCraftMasterySnapshot.newBuilder()
                .setTier(scrollTier)
                .setTierName(EquipCraftQualityRules.masteryTierName(scrollTier))
                .setExp(scrollExp)
                .setNextTierExp(EquipCraftQualityRules.masteryNextExp(scrollExp))
                .setBonusRateBps(EquipCraftQualityRules.scrollBonusFragmentRateBps(scrollTier))
                .build();

        SkillCraftingStateSnapshot.Builder out = SkillCraftingStateSnapshot.newBuilder()
                .setScrollCraftRevision(craft.getScrollCraftRevision())
                .setForgeMastery(forgeSnap)
                .setScrollMastery(scrollSnap);

        for (SkillScrollCraftTable row : tables.enabledScrolls()) {
            SkillScrollCatalogInfo.Builder entry = SkillScrollCatalogInfo.newBuilder()
                    .setScrollId(row.getId())
                    .setName(row.getName())
                    .setDescription(row.getDescription())
                    .setRarityTier(row.getRarityTier())
                    .setFragmentItemId(row.getFragmentItemId())
                    .setRandomFragmentCount(row.getRandomFragmentCount())
                    .setRandomGoldCost(row.getRandomGoldCost())
                    .setTargetedFragmentCount(row.getTargetedFragmentCount())
                    .setTargetedGoldCost(row.getTargetedGoldCost())
                    .setDismantleFragmentCount(row.getDismantleFragmentCount())
                    .setRareScrollItemId(row.getRareScrollItemId())
                    .setRareScrollCount(row.getRareScrollCount())
                    .setRareInkItemId(row.getRareInkItemId())
                    .setRareInkCount(row.getRareInkCount())
                    .setRareGoldCost(row.getRareGoldCost())
                    .addAllSkillTableIds(tables.scrollSkillIds(row.getId()));
            out.addScrolls(entry);
        }

        List<CraftedSkillBookEntryState> books = new ArrayList<>(craft.getSkillBooksList());
        books.sort(Comparator.comparing(CraftedSkillBookEntryState::getBookId, Long::compareUnsigned));
        for (CraftedSkillBookEntryState b : books) {
            out.addSkillBooks(toProtoBook(b));
        }

        TreeSet<Integer> unlocked = new TreeSet<>(Integer::compareUnsigned);
        unlocked.addAll(craft.getBookUnlockedSkillIdsList());
        out.addAllBookUnlockedSkillIds(unlocked);

        out.setGoldBalance(player.wallet().balance(Wallet.GOLD))
                .setFragmentOwned(inventory.total(EquipCraftQualityRules.SCROLL_FRAGMENT_CONFIG_ID))
                .setRareScrollOwned(inventory.total(EquipCraftQualityRules.RARE_SCROLL_PAGE_CONFIG_ID))
                .setRareInkOwned(inventory.total(EquipCraftQualityRules.RARE_SCROLL_INK_CONFIG_ID))
                .setProxyStallOpen(craft.getProxyStallOpen())
                .setProxyForgeFeeGold(craft.getProxyForgeFeeGold())
                .setProxyScrollFeeGold(craft.getProxyScrollFeeGold())
                .setCraftUnlockGateEnabled(craftUnlockGateEnabled)
                .setProxyOrdersCompleted(craft.getProxyOrdersCompleted())
                .setSupremeForgedCount(craft.getSupremeForgedCount())
                .setRareBooksCraftedCount(craft.getRareBooksCraftedCount())
                .setMasterShowcaseTag(craft.getMasterShowcaseTag())
                .setEquippedCraftTitleId(craft.getEquippedCraftTitleId())
                .addAllUnlockedCraftTitleIds(EquipCraftQualityRules.unlockedCraftTitleIds(
                        forgeExp, scrollExp, craft.getSupremeForgedCount(),
                        craft.getRareBooksCraftedCount(), craft.getProxyOrdersCompleted()));
        if (craft.getUnreadProxyDigestOrders() != 0
                || craft.getUnreadProxyDigestNetGold() != 0L
                || craft.getUnreadProxyDigestNewTitlesMask() != 0) {
            out.setUnreadProxyDigest(CraftProxyDigestSummary.newBuilder()
                    .setOrdersCompleted(craft.getUnreadProxyDigestOrders())
                    .setSupremeForgedCount(craft.getUnreadProxyDigestSupreme())
                    .setRareBooksCraftedCount(craft.getUnreadProxyDigestRare())
                    .setNetGoldEarned(craft.getUnreadProxyDigestNetGold())
                    .setLastBuyerName(craft.getUnreadProxyDigestLastBuyer())
                    .setNewlyUnlockedTitleMask(craft.getUnreadProxyDigestNewTitlesMask())
                    .build());
        }
        return out.build();
    }

    /** 459 查询分卷宗制书快照、未学技能书架、手艺熟练度与代打摊位配置（同时回账离线/跨线待结算口碑）。 */
    public GetStateOutcome getStateWithDrain(ScenePlayer player) {
        if (!player.clientRequestLedger().isValid()) {
            return new GetStateOutcome(GetSkillCraftingStateResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).build(), false);
        }
        boolean drained = proxyDirectory != null && proxyDirectory.drainPendingReputation(player);
        return new GetStateOutcome(GetSkillCraftingStateResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setState(buildStateSnapshot(player))
                .build(), drained);
    }

    public GetSkillCraftingStateResponse getState(ScenePlayer player) {
        return getStateWithDrain(player).response();
    }

    /** 573 宗师确认查阅离线/跨线代打营业战报（幂等清空 unread_proxy_digest_*，不重复扣费）。 */
    public AckOutcome ackProxyDigest(ScenePlayer player) {
        if (!player.clientRequestLedger().isValid()) {
            return new AckOutcome(AckCraftProxyDigestResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).build(), false);
        }
        int busy = checkWritable(player);
        if (busy != OK) {
            return new AckOutcome(AckCraftProxyDigestResponse.newBuilder()
                    .setErrorMessage(tip(busy))
                    .setState(buildStateSnapshot(player))
                    .build(), false);
        }
        boolean drained = proxyDirectory != null && proxyDirectory.drainPendingReputation(player);
        PlayerCraftState craft = player.craft().state();
        boolean hasUnread = craft.getUnreadProxyDigestOrders() != 0
                || craft.getUnreadProxyDigestSupreme() != 0
                || craft.getUnreadProxyDigestRare() != 0
                || craft.getUnreadProxyDigestNetGold() != 0L
                || !craft.getUnreadProxyDigestLastBuyer().isEmpty()
                || craft.getUnreadProxyDigestNewTitlesMask() != 0;
        if (hasUnread) {
            player.craft().replace(craft.toBuilder()
                    .setUnreadProxyDigestOrders(0)
                    .setUnreadProxyDigestSupreme(0)
                    .setUnreadProxyDigestRare(0)
                    .setUnreadProxyDigestNetGold(0L)
                    .clearUnreadProxyDigestLastBuyer()
                    .setUnreadProxyDigestNewTitlesMask(0)
                    .build());
        }
        return new AckOutcome(AckCraftProxyDigestResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setState(buildStateSnapshot(player))
                .build(), drained || hasUnread);
    }

    /** 460 拓印/定向制书（含巧匠/宗师额外返 2 张绑定残页、主城宗师原子代制与 client_request_id 幂等）。 */
    public CraftOutcome craftSkillBook(ScenePlayer player, CraftSkillBookRequest request, SceneWorld world) {
        long reqId = request.getClientRequestId();
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return new CraftOutcome(CraftSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).setClientRequestId(reqId).build(), false, null);
        }
        if (reqId == 0L) {
            return failCraft(player, reqId, kInvalidParameter_VALUE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_SKILL_SCROLL_CRAFT, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            ClientRequestRecordState first = check.record();
            CraftSkillBookResponse.Builder resp = CraftSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setClientRequestId(reqId)
                    .setReplayed(true)
                    .setBonusFragmentsGranted(first.getScrollBonusFragments())
                    .setState(buildStateSnapshot(player));
            CraftedSkillBookEntryState existing = findBook(player.craft().state(), first.getCraftedSkillBookId());
            if (existing != null) {
                resp.setCraftedBook(toProtoBook(existing));
            } else {
                resp.setCraftedBook(CraftedSkillBookEntry.newBuilder()
                        .setBookId(first.getCraftedSkillBookId())
                        .setSkillTableId(first.getCraftedSkillTableId()));
            }
            return new CraftOutcome(resp.build(), false, null);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return failCraft(player, reqId, kInvalidParameter_VALUE);
        }

        int busy = checkWritable(player);
        if (busy != OK) {
            return failCraft(player, reqId, busy);
        }
        PlayerCraftState craft = player.craft().state();
        long revBefore = craft.getScrollCraftRevision();
        if (revBefore == -1L || ledger.nextSeq() == -1L) {
            return failCraft(player, reqId, kServiceUnavailable_VALUE);
        }
        if (request.getExpectedRevision() != revBefore) {
            return failCraft(player, reqId, kSkillCraftRevisionChanged_VALUE);
        }
        SkillScrollCraftTable scroll = tables.scroll(request.getScrollId());
        if (scroll == null || scroll.getEnabled() == 0) {
            return failCraft(player, reqId, kSkillScrollInvalid_VALUE);
        }
        List<Integer> pool = tables.scrollSkillIds(scroll.getId());
        if (pool.isEmpty()) {
            return failCraft(player, reqId, kSkillScrollInvalid_VALUE);
        }

        CraftSkillBookRequest.CraftMode mode = request.getMode();
        int chosenSkillId;
        Map<Integer, Long> consumed = new LinkedHashMap<>();
        long baseGoldCost;
        if (mode == CraftSkillBookRequest.CraftMode.RANDOM_REGULAR) {
            if (scroll.getRarityTier() != 0 || request.getTargetSkillTableId() != 0) {
                return failCraft(player, reqId, kSkillScrollInvalid_VALUE);
            }
            chosenSkillId = pool.get(rollBelow(pool.size()));
            consumed.merge(scroll.getFragmentItemId(), Integer.toUnsignedLong(scroll.getRandomFragmentCount()), Long::sum);
            baseGoldCost = scroll.getRandomGoldCost();
        } else if (mode == CraftSkillBookRequest.CraftMode.TARGETED_REGULAR) {
            if (scroll.getRarityTier() != 0 || !pool.contains(request.getTargetSkillTableId())) {
                return failCraft(player, reqId, kSkillScrollInvalid_VALUE);
            }
            chosenSkillId = request.getTargetSkillTableId();
            consumed.merge(scroll.getFragmentItemId(), Integer.toUnsignedLong(scroll.getTargetedFragmentCount()), Long::sum);
            baseGoldCost = scroll.getTargetedGoldCost();
        } else if (mode == CraftSkillBookRequest.CraftMode.TARGETED_RARE) {
            if (scroll.getRarityTier() != 1 || !pool.contains(request.getTargetSkillTableId())) {
                return failCraft(player, reqId, kSkillScrollInvalid_VALUE);
            }
            chosenSkillId = request.getTargetSkillTableId();
            consumed.merge(scroll.getRareScrollItemId(), Integer.toUnsignedLong(scroll.getRareScrollCount()), Long::sum);
            consumed.merge(scroll.getRareInkItemId(), Integer.toUnsignedLong(scroll.getRareInkCount()), Long::sum);
            baseGoldCost = scroll.getRareGoldCost();
        } else {
            return failCraft(player, reqId, kInvalidParameter_VALUE);
        }

        // 解析可选主城宗师原子代制摊位（支持跨节点/离线 24 小时营业，只读共享目录宗师档位，95% 净银两走邮件出箱异步入账）。
        long proxyMasterId = request.getProxyMasterPlayerId();
        long proxyFeeGold = 0L;
        long proxyNetGold = 0L;
        String proxyMasterName = "";
        int effectiveScrollTier = EquipCraftQualityRules.masteryTierForExp(craft.getScrollMasteryExp());
        if (proxyMasterId != 0L) {
            CraftProxyStallInfo stall = resolveValidProxyScrollStall(player, proxyMasterId);
            if (stall == null) {
                return failCraft(player, reqId, kEquipProxyMasterUnavailable_VALUE);
            }
            proxyFeeGold = stall.getProxyScrollFeeGold();
            proxyNetGold = EquipCraftQualityRules.proxyNetGold(proxyFeeGold);
            if (proxyNetGold > 0L && (mailOutbox == null || !mailOutbox.canAccept(player))) {
                return failCraft(player, reqId, kEquipProxyMasterUnavailable_VALUE);
            }
            proxyMasterName = stall.getMasterName().isEmpty()
                    ? ("宗师#" + Long.toUnsignedString(proxyMasterId)) : stall.getMasterName();
            effectiveScrollTier = EquipCraftQualityRules.MASTERY_MASTER;
        }

        // 防洗白硬隔离：代工费严格只扣流通银两，仅 TARGETED_RARE（产物必绑定）系统费允许优先扣绑定银两。
        boolean allowBindForBaseFee = mode == CraftSkillBookRequest.CraftMode.TARGETED_RARE;
        long strictGold = proxyFeeGold + (allowBindForBaseFee ? 0L : baseGoldCost);
        long preferBindGold = allowBindForBaseFee ? baseGoldCost : 0L;
        if ((strictGold > 0L || preferBindGold > 0L)
                && (strictGold < 0L || preferBindGold < 0L
                || !player.wallet().canAffordSplitGold(strictGold, preferBindGold))) {
            return failCraft(player, reqId, kSkillGoldNotEnough_VALUE);
        }

        Bag inventory = player.bags().bag(BagType.INVENTORY);
        for (Map.Entry<Integer, Long> entry : consumed.entrySet()) {
            int configId = entry.getKey();
            long needed = entry.getValue();
            if (needed <= 0L) {
                continue;
            }
            if (bags.tables().hasUnknownItemSchema(configId)) {
                return failCraft(player, reqId, kFeatureUnavailable_VALUE);
            }
            if (inventory.plainQuantity(configId) < needed) {
                return failCraft(player, reqId, inventory.total(configId) >= needed
                        ? kFeatureUnavailable_VALUE : kSkillCraftMaterialNotEnough_VALUE);
            }
        }

        CurrencyService.PreparedDeduction payment = null;
        if (strictGold > 0L || preferBindGold > 0L) {
            payment = currency.prepareSplitGoldDeduction(player, strictGold, preferBindGold);
            if (payment.tipId() != 0) {
                return failCraft(player, reqId, kSkillGoldNotEnough_VALUE);
            }
        }

        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        Bag candInv = candidate.bag(BagType.INVENTORY);
        List<Bag.Drawn> removed = new ArrayList<>();
        boolean consumedBound = false;
        for (Map.Entry<Integer, Long> entry : consumed.entrySet()) {
            if (entry.getValue() <= 0L) {
                continue;
            }
            Bag.DrainOutcome outcome = candInv.drainPlainExact(entry.getKey(), entry.getValue(), false);
            removed.addAll(outcome.drawn());
            consumedBound |= outcome.consumedBound();
        }

        // 巧匠/宗师拓印常规书时按 5%/10% 概率返还 2 张绑定悟道残页（不提高稀世产出；包满静默跳过返页）。
        int bonusFragments = 0;
        List<Bag.Written> gained = List.of();
        if (scroll.getRarityTier() == 0) {
            int bonusRate = EquipCraftQualityRules.scrollBonusFragmentRateBps(effectiveScrollTier);
            if (bonusRate > 0 && rollBelow(10_000) < bonusRate) {
                Bag.AddResult bonusAdded = bags.prepareEquipmentGrant(player, candidate,
                        Map.of(scroll.getFragmentItemId(), (long) EquipCraftQualityRules.SCROLL_BONUS_FRAGMENTS_COUNT),
                        true);
                if (bonusAdded.ok()) {
                    bonusFragments = EquipCraftQualityRules.SCROLL_BONUS_FRAGMENTS_COUNT;
                    gained = bonusAdded.written();
                }
            }
        }

        long bookId = craft.getNextBookSeq() == 0L ? 1L : craft.getNextBookSeq();
        CraftedSkillBookEntryState.Builder newBook = CraftedSkillBookEntryState.newBuilder()
                .setBookId(bookId)
                .setSkillTableId(chosenSkillId)
                .setScrollId(scroll.getId())
                .setRarityTier(scroll.getRarityTier())
                // 防洗白硬隔离：稀世绝学产物强制仅自用绑定；常规书按实扣材料绑定性决定。
                .setBound(mode == CraftSkillBookRequest.CraftMode.TARGETED_RARE || consumedBound);
        if (effectiveScrollTier == EquipCraftQualityRules.MASTERY_MASTER) {
            long signerId = proxyMasterId != 0L ? proxyMasterId : player.playerId();
            newBook.setCrafterPlayerId(signerId)
                    .setCrafterName(proxyMasterId != 0L ? proxyMasterName : EquipmentCraftService.playerDisplayName(player));
        }
        CraftedSkillBookEntryState builtBook = newBook.build();

        if (payment != null) {
            payment.commit(candidate, AssetAudit.Reason.SKILL_SCROLL_CRAFT, reqId, "{\"operation\":\"skill_scroll_craft\"}");
        } else {
            player.bags().commitPrepared(candidate);
        }
        if (proxyMasterId != 0L && proxyNetGold > 0L && mailOutbox != null) {
            // 95% 净银两写入买家同事务 PlayerState.mail_outbox，落盘确认后经 game-mail -> asset_op 异步入账。
            mailOutbox.enqueueProxyFee(player, proxyMasterId, proxyNetGold, reqId,
                    EquipmentCraftService.playerDisplayName(player));
        }
        ScenePlayer creditedProxyMaster = null;
        if (proxyMasterId != 0L && proxyDirectory != null) {
            ScenePlayer onlineMaster = world == null ? null : world.playerById(proxyMasterId);
            creditedProxyMaster = proxyDirectory.recordOrderReputation(
                    proxyMasterId,
                    0,
                    mode == CraftSkillBookRequest.CraftMode.TARGETED_RARE ? 1 : 0,
                    proxyNetGold,
                    EquipmentCraftService.playerDisplayName(player),
                    onlineMaster);
        }

        PlayerCraftState.Builder nextCraft = craft.toBuilder()
                .addSkillBooks(builtBook)
                .setNextBookSeq(bookId + 1L)
                .setScrollCraftRevision(revBefore + 1L);
        if (proxyMasterId == 0L) {
            if (craft.getScrollMasteryExp() != -1) {
                nextCraft.setScrollMasteryExp(craft.getScrollMasteryExp() + 1);
            }
            if (mode == CraftSkillBookRequest.CraftMode.TARGETED_RARE
                    && craft.getRareBooksCraftedCount() != -1) {
                nextCraft.setRareBooksCraftedCount(craft.getRareBooksCraftedCount() + 1);
            }
        }
        player.craft().replace(nextCraft.build());

        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_SKILL_SCROLL_CRAFT)
                .setFingerprint(fingerprint)
                .setCraftedSkillBookId(bookId)
                .setCraftedSkillTableId(chosenSkillId)
                .setScrollCraftRevision(revBefore + 1L)
                .setScrollBonusFragments(bonusFragments));
        bags.auditEquipment(player, removed, gained, AssetAudit.Reason.SKILL_SCROLL_CRAFT, reqId,
                "{\"operation\":\"skill_scroll_craft\"}");

        return new CraftOutcome(CraftSkillBookResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setCraftedBook(toProtoBook(builtBook))
                .setBonusFragmentsGranted(bonusFragments)
                .setState(buildStateSnapshot(player))
                .build(), true, creditedProxyMaster);
    }

    /** 461 研习未学技能书：永久解锁技能并给人物或宝宝直接获得 1 重，绝不顶掉任何已有技能。 */
    public LearnOutcome learnSkillBook(ScenePlayer player, LearnSkillBookRequest request) {
        long reqId = request.getClientRequestId();
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return new LearnOutcome(LearnSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).setClientRequestId(reqId).build(), false, false, false);
        }
        if (reqId == 0L || request.getBookId() == 0L) {
            return failLearn(player, reqId, kInvalidParameter_VALUE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_LEARN_SKILL_BOOK, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            return new LearnOutcome(LearnSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setClientRequestId(reqId)
                    .setReplayed(true)
                    .setLearnedSkillTableId(check.record().getCraftedSkillTableId())
                    .setState(buildStateSnapshot(player))
                    .build(), false, false, false);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return failLearn(player, reqId, kInvalidParameter_VALUE);
        }

        int busy = checkWritable(player);
        if (busy != OK) {
            return failLearn(player, reqId, busy);
        }
        PlayerCraftState craft = player.craft().state();
        long revBefore = craft.getScrollCraftRevision();
        if (revBefore == -1L || ledger.nextSeq() == -1L) {
            return failLearn(player, reqId, kServiceUnavailable_VALUE);
        }
        if (request.getExpectedRevision() != revBefore) {
            return failLearn(player, reqId, kSkillCraftRevisionChanged_VALUE);
        }

        int bookIdx = findBookIndex(craft, request.getBookId());
        if (bookIdx < 0) {
            return failLearn(player, reqId, kSkillBookNotFound_VALUE);
        }
        int skillId = craft.getSkillBooks(bookIdx).getSkillTableId();
        SkillTable row = tables.skill(skillId);
        if (row == null || row.getSeasonId() != 0 || row.getBattleModel() != 1) {
            return failLearn(player, reqId, kInvalidTableId_VALUE);
        }

        int requiredMask = request.getPetId() == 0L ? 1 : 2;
        if ((row.getCasterMask() & requiredMask) == 0) {
            return failLearn(player, reqId, kSkillNotInClass_VALUE);
        }
        if (player.craft().isSkillBookUnlocked(skillId)) {
            return failLearn(player, reqId, kSkillAlreadyBookUnlocked_VALUE);
        }

        if (request.getPetId() != 0L) {
            if (pets == null) {
                return failLearn(player, reqId, kServiceUnavailable_VALUE);
            }
            int petErr = pets.checkCanUnlockSkillFromBook(player, request.getPetId());
            if (petErr != OK) {
                return failLearn(player, reqId, petErr);
            }
        }

        PlayerCraftState.Builder nextCraft = removeBookAt(craft, bookIdx)
                .addBookUnlockedSkillIds(skillId)
                .setScrollCraftRevision(revBefore + 1L);
        player.craft().replace(nextCraft.build());

        boolean pushPanel = false;
        boolean pushPets = false;
        if (request.getPetId() == 0L) {
            if (player.learnedSkills().rank(skillId) == 0) {
                player.learnedSkills().setRank(skillId, 1);
                com.game.scene.skill.SkillLoadoutRules.tryAutoSlotNewlyLearned(
                        player.skillCatalog(), player.learnedSkills(), row);
            }
            if (ClassSkillCatalog.passive(row)) {
                attributes.skillsChanged(player);
                pushPanel = true;
            }
        } else {
            pets.unlockSkillFromBook(player, request.getPetId(), skillId);
            pushPets = true;
        }

        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_LEARN_SKILL_BOOK)
                .setFingerprint(fingerprint)
                .setCraftedSkillBookId(request.getBookId())
                .setCraftedSkillTableId(skillId)
                .setScrollCraftRevision(revBefore + 1L));

        return new LearnOutcome(LearnSkillBookResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setLearnedSkillTableId(skillId)
                .setState(buildStateSnapshot(player))
                .build(), true, pushPanel, pushPets);
    }

    /** 462 拆解多余未学常规技能书：固定返还 80%（16 张）悟道残页，严格保持原书绑定性防洗白。 */
    public DismantleOutcome dismantleSkillBook(ScenePlayer player, DismantleSkillBookRequest request) {
        long reqId = request.getClientRequestId();
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return new DismantleOutcome(DismantleSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).setClientRequestId(reqId).build(), false);
        }
        if (reqId == 0L || request.getBookId() == 0L) {
            return failDismantle(player, reqId, kInvalidParameter_VALUE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_DISMANTLE_SKILL_BOOK, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            return new DismantleOutcome(DismantleSkillBookResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setClientRequestId(reqId)
                    .setReplayed(true)
                    .setRefundedFragments(check.record().getScrollBonusFragments())
                    .setState(buildStateSnapshot(player))
                    .build(), false);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return failDismantle(player, reqId, kInvalidParameter_VALUE);
        }

        int busy = checkWritable(player);
        if (busy != OK) {
            return failDismantle(player, reqId, busy);
        }
        PlayerCraftState craft = player.craft().state();
        long revBefore = craft.getScrollCraftRevision();
        if (revBefore == -1L || ledger.nextSeq() == -1L) {
            return failDismantle(player, reqId, kServiceUnavailable_VALUE);
        }
        if (request.getExpectedRevision() != revBefore) {
            return failDismantle(player, reqId, kSkillCraftRevisionChanged_VALUE);
        }

        int bookIdx = findBookIndex(craft, request.getBookId());
        if (bookIdx < 0) {
            return failDismantle(player, reqId, kSkillBookNotFound_VALUE);
        }
        CraftedSkillBookEntryState book = craft.getSkillBooks(bookIdx);
        if (book.getRarityTier() != 0) {
            return failDismantle(player, reqId, kSkillBookNotDismantlable_VALUE);
        }
        SkillScrollCraftTable scroll = tables.scroll(book.getScrollId());
        if (scroll == null || scroll.getDismantleFragmentCount() == 0
                || !tables.validStackableMaterial(scroll.getFragmentItemId(), scroll.getDismantleFragmentCount())) {
            return failDismantle(player, reqId, kSkillBookNotDismantlable_VALUE);
        }

        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        Bag.AddResult added = bags.prepareEquipmentGrant(player, candidate,
                Map.of(scroll.getFragmentItemId(), Integer.toUnsignedLong(scroll.getDismantleFragmentCount())),
                book.getBound());
        if (!added.ok()) {
            int tipId = added.tip() == Bag.NO_SPACE ? kEquipBagFull_VALUE : added.tip();
            return failDismantle(player, reqId, tipId);
        }

        player.bags().commitPrepared(candidate);
        player.craft().replace(removeBookAt(craft, bookIdx)
                .setScrollCraftRevision(revBefore + 1L)
                .build());
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_DISMANTLE_SKILL_BOOK)
                .setFingerprint(fingerprint)
                .setCraftedSkillBookId(book.getBookId())
                .setCraftedSkillTableId(book.getSkillTableId())
                .setScrollCraftRevision(revBefore + 1L)
                .setScrollBonusFragments(scroll.getDismantleFragmentCount()));
        bags.auditEquipment(player, List.of(), added.written(), AssetAudit.Reason.SKILL_SCROLL_CRAFT, reqId,
                "{\"operation\":\"skill_book_dismantle\"}");

        return new DismantleOutcome(DismantleSkillBookResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setRefundedFragments(scroll.getDismantleFragmentCount())
                .setState(buildStateSnapshot(player))
                .build(), true);
    }

    /** 463 宗师开启/关闭主城原子代打摊位（铸器/制卷至少一项满宗师 >=60 熟练度，手工费 50..100 银两；支持招牌与已解锁雅号）。 */
    public StallOutcome setProxyStall(ScenePlayer player, SetCraftProxyStallRequest request) {
        if (!player.clientRequestLedger().isValid()) {
            return new StallOutcome(SetCraftProxyStallResponse.newBuilder()
                    .setErrorMessage(tip(kServiceUnavailable_VALUE)).build(), false);
        }
        int busy = checkWritable(player);
        if (busy != OK) {
            return failStall(player, busy);
        }
        boolean drained = proxyDirectory != null && proxyDirectory.drainPendingReputation(player);
        PlayerCraftState craft = player.craft().state();
        int showcaseTag = request.getMasterShowcaseTag();
        if (Integer.compareUnsigned(showcaseTag, EquipCraftQualityRules.MAX_MASTER_SHOWCASE_TAG) > 0) {
            return failStall(player, kInvalidParameter_VALUE, drained);
        }
        int craftTitleId = request.getEquippedCraftTitleId();
        if (craftTitleId != 0 && !EquipCraftQualityRules.isCraftTitleUnlocked(
                craftTitleId, craft.getForgeMasteryExp(), craft.getScrollMasteryExp(),
                craft.getSupremeForgedCount(), craft.getRareBooksCraftedCount(),
                craft.getProxyOrdersCompleted())) {
            return failStall(player, kInvalidParameter_VALUE, drained);
        }
        if (!request.getOpen()) {
            player.craft().replace(craft.toBuilder()
                    .setProxyStallOpen(false)
                    .setMasterShowcaseTag(showcaseTag)
                    .setEquippedCraftTitleId(craftTitleId)
                    .build());
            if (proxyDirectory != null) {
                proxyDirectory.syncPlayer(player);
            }
            return new StallOutcome(SetCraftProxyStallResponse.newBuilder()
                    .setErrorMessage(tip(OK)).setState(buildStateSnapshot(player)).build(), true);
        }

        boolean forgeMaster = EquipCraftQualityRules.masteryTierForExp(craft.getForgeMasteryExp())
                >= EquipCraftQualityRules.MASTERY_MASTER;
        boolean scrollMaster = EquipCraftQualityRules.masteryTierForExp(craft.getScrollMasteryExp())
                >= EquipCraftQualityRules.MASTERY_MASTER;
        if (!forgeMaster && !scrollMaster) {
            return failStall(player, kSkillProxyStallRequiresMaster_VALUE, drained);
        }
        if (forgeMaster && !EquipCraftQualityRules.validProxyFee(request.getProxyForgeFeeGold())) {
            return failStall(player, kSkillProxyFeeInvalid_VALUE, drained);
        }
        if (!forgeMaster && request.getProxyForgeFeeGold() != 0L
                && !EquipCraftQualityRules.validProxyFee(request.getProxyForgeFeeGold())) {
            return failStall(player, kSkillProxyFeeInvalid_VALUE, drained);
        }
        if (scrollMaster && !EquipCraftQualityRules.validProxyFee(request.getProxyScrollFeeGold())) {
            return failStall(player, kSkillProxyFeeInvalid_VALUE, drained);
        }
        if (!scrollMaster && request.getProxyScrollFeeGold() != 0L
                && !EquipCraftQualityRules.validProxyFee(request.getProxyScrollFeeGold())) {
            return failStall(player, kSkillProxyFeeInvalid_VALUE, drained);
        }

        player.craft().replace(craft.toBuilder()
                .setProxyStallOpen(true)
                .setProxyForgeFeeGold(forgeMaster ? request.getProxyForgeFeeGold() : 0L)
                .setProxyScrollFeeGold(scrollMaster ? request.getProxyScrollFeeGold() : 0L)
                .setMasterShowcaseTag(showcaseTag)
                .setEquippedCraftTitleId(craftTitleId)
                .build());
        if (proxyDirectory != null) {
            proxyDirectory.syncPlayer(player);
        }
        return new StallOutcome(SetCraftProxyStallResponse.newBuilder()
                .setErrorMessage(tip(OK)).setState(buildStateSnapshot(player)).build(), true);
    }

    /** 464 查询同区跨节点/离线营业中的宗师原子代打摊位列表（按玩家 ID 无符号升序）。 */
    public ListCraftProxyStallsResponse listProxyStalls(SceneWorld world) {
        ListCraftProxyStallsResponse.Builder out = ListCraftProxyStallsResponse.newBuilder().setErrorMessage(tip(OK));
        if (proxyDirectory != null) {
            for (CraftProxyStallInfo stall : proxyDirectory.listStalls()) {
                out.addStalls(stall);
            }
            return out.build();
        }
        if (world == null) {
            return out.build();
        }
        List<ScenePlayer> masters = new ArrayList<>();
        for (ScenePlayer p : world.playersSnapshot()) {
            if (p.playerId() == 0L || p.frozen() || p.switchPhase() != SwitchPhase.NONE) {
                continue;
            }
            if (CraftProxyStallDirectory.buildStallInfo(p) != null) {
                masters.add(p);
            }
        }
        masters.sort(Comparator.comparing(ScenePlayer::playerId, Long::compareUnsigned));
        for (ScenePlayer p : masters) {
            CraftProxyStallInfo info = CraftProxyStallDirectory.buildStallInfo(p);
            if (info != null) {
                out.addStalls(info);
            }
        }
        return out.build();
    }

    // ------------------------------------------------------------------ 内部辅助

    private CraftProxyStallInfo resolveValidProxyScrollStall(ScenePlayer buyer, long masterId) {
        if (masterId == 0L || masterId == buyer.playerId() || proxyDirectory == null) {
            return null;
        }
        CraftProxyStallInfo stall = proxyDirectory.find(masterId);
        if (stall == null
                || stall.getScrollMasteryTier() < EquipCraftQualityRules.MASTERY_MASTER
                || !EquipCraftQualityRules.validProxyFee(stall.getProxyScrollFeeGold())) {
            return null;
        }
        long netFee = EquipCraftQualityRules.proxyNetGold(stall.getProxyScrollFeeGold());
        if (netFee > 0L && currency.isGloballyBlocked(Wallet.GOLD)) {
            return null;
        }
        return stall;
    }

    private static int findBookIndex(PlayerCraftState craft, long bookId) {
        for (int i = 0; i < craft.getSkillBooksCount(); i++) {
            if (craft.getSkillBooks(i).getBookId() == bookId) {
                return i;
            }
        }
        return -1;
    }

    private static CraftedSkillBookEntryState findBook(PlayerCraftState craft, long bookId) {
        int idx = findBookIndex(craft, bookId);
        return idx < 0 ? null : craft.getSkillBooks(idx);
    }

    private static PlayerCraftState.Builder removeBookAt(PlayerCraftState craft, int idx) {
        return craft.toBuilder().removeSkillBooks(idx);
    }

    private CraftOutcome failCraft(ScenePlayer player, long reqId, int tipId) {
        return new CraftOutcome(CraftSkillBookResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setClientRequestId(reqId)
                .setState(buildStateSnapshot(player))
                .build(), false, null);
    }

    private LearnOutcome failLearn(ScenePlayer player, long reqId, int tipId) {
        return new LearnOutcome(LearnSkillBookResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setClientRequestId(reqId)
                .setState(buildStateSnapshot(player))
                .build(), false, false, false);
    }

    private DismantleOutcome failDismantle(ScenePlayer player, long reqId, int tipId) {
        return new DismantleOutcome(DismantleSkillBookResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setClientRequestId(reqId)
                .setState(buildStateSnapshot(player))
                .build(), false);
    }

    private StallOutcome failStall(ScenePlayer player, int tipId) {
        return failStall(player, tipId, false);
    }

    private StallOutcome failStall(ScenePlayer player, int tipId, boolean mutated) {
        return new StallOutcome(SetCraftProxyStallResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setState(buildStateSnapshot(player))
                .build(), mutated);
    }
}
