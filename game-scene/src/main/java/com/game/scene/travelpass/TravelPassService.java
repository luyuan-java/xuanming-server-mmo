package com.game.scene.travelpass;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.battle.engine.BattleConstants;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimTravelPassRewardRequest;
import com.game.proto.ClaimTravelPassRewardResponse;
import com.game.proto.EquipTravelPassCosmeticRequest;
import com.game.proto.EquipTravelPassCosmeticResponse;
import com.game.proto.GetTravelPassStateResponse;
import com.game.proto.PlayerActivityCategory;
import com.game.proto.PlayerActivityEntryKind;
import com.game.proto.PlayerActivityInfo;
import com.game.proto.PlayerActivityRewardItem;
import com.game.proto.PlayerActivityRewardType;
import com.game.proto.PlayerActivityStatus;
import com.game.proto.PlayerAppearanceInfo;
import com.game.proto.SelectActiveTravelPassRequest;
import com.game.proto.SelectActiveTravelPassResponse;
import com.game.proto.SpiritShopInfo;
import com.game.proto.SpiritShopLimitGroupView;
import com.game.proto.TravelPassCatalogEntry;
import com.game.proto.TravelPassSnapshot;
import com.game.proto.TravelPassTierView;
import com.game.proto.UnlockTravelPassPremiumRequest;
import com.game.proto.UnlockTravelPassPremiumResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.appearance.AppearanceService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerTravelPass;
import com.game.scene.player.PlayerTravelPass.SeasonProgress;
import com.game.scene.player.Wallet;
import com.game.scene.spiritshop.SpiritShopService;
import com.game.scene.travelpass.TravelPassTables.ResolvedSeason;
import com.game.scene.travelpass.TravelPassTables.ResolvedTier;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.ItemTable;
import com.game.table.TravelPassErrorTip;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 青云游历手记（不肝不逼氪双轨活动）场景侧服务（{@code SceneActivityClientPlayer} 526–529 与 190 目录聚合；
 * 对齐 C++ {@code PlayerTravelPassSystem}）。
 *
 * <p>核心不变量：零日常/零签到/零周清；正常合格战斗自动原子涨点；先玩后买随时补领；解锁典藏（仅扣真实充值灵石，
 * 严禁扣绑定灵石）后永不过期；全部发放物品强制 {@code bound=true}，0 战力上限突破；红点仅在有真实可领档位时点亮。
 */
public final class TravelPassService {

    public static final int ACTIVITY_ID_TRAVEL_PASS = 900001;
    public static final int ACTIVITY_ID_APPEARANCE_SHOWCASE = 900002;
    public static final int ACTIVITY_ID_SPIRIT_SHOP_SHOWCASE = 900003;

    public static final int PVP_MIN_ROUNDS_FOR_LOSS = 3;
    public static final int PVP_MIN_MANUAL_ROUNDS_FOR_LOSS = 2;

    private static final long MAX_U32 = 0xFFFF_FFFFL;

    public static final int OK = 0;
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    public static final int UNAVAILABLE = TravelPassErrorTip.travel_pass_error.kTravelPassUnavailable_VALUE;
    public static final int NOT_OPEN = TravelPassErrorTip.travel_pass_error.kTravelPassNotOpen_VALUE;
    public static final int ALREADY_UNLOCKED = TravelPassErrorTip.travel_pass_error.kTravelPassAlreadyUnlocked_VALUE;
    public static final int PRICE_CHANGED = TravelPassErrorTip.travel_pass_error.kTravelPassPriceChanged_VALUE;
    public static final int CURRENCY_NOT_ENOUGH = TravelPassErrorTip.travel_pass_error.kTravelPassCurrencyNotEnough_VALUE;
    public static final int NOT_REACHED = TravelPassErrorTip.travel_pass_error.kTravelPassNotReached_VALUE;
    public static final int PREMIUM_REQUIRED = TravelPassErrorTip.travel_pass_error.kTravelPassPremiumRequired_VALUE;
    public static final int NOTHING_TO_CLAIM = TravelPassErrorTip.travel_pass_error.kTravelPassNothingToClaim_VALUE;
    public static final int BAG_FULL = TravelPassErrorTip.travel_pass_error.kTravelPassBagFull_VALUE;
    public static final int ITEM_BLOCKED = TravelPassErrorTip.travel_pass_error.kTravelPassItemBlocked_VALUE;
    public static final int FROZEN = TravelPassErrorTip.travel_pass_error.kTravelPassFrozen_VALUE;
    public static final int IN_BATTLE = TravelPassErrorTip.travel_pass_error.kTravelPassInBattle_VALUE;
    public static final int COSMETIC_NOT_OWNED = TravelPassErrorTip.travel_pass_error.kTravelPassCosmeticNotOwned_VALUE;

    private final TravelPassTables tables;
    private final BagService bags;
    private final CurrencyService currency;
    private final SceneClock clock;
    private final SceneMetrics metrics;

    public TravelPassService(TravelPassTables tables, BagService bags, CurrencyService currency,
                             SceneClock clock, SceneMetrics metrics) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    public TravelPassTables tables() {
        return tables;
    }

    /** 526 GetTravelPassState：只读查询当期或历史可延续期次快照。 */
    public GetTravelPassStateResponse getState(ScenePlayer player, long nowMs, int requestedPassId) {
        if (!player.travelPass().isValid()) {
            return GetTravelPassStateResponse.newBuilder().setErrorMessage(tip(UNAVAILABLE)).build();
        }
        return GetTravelPassStateResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(buildSnapshot(player, nowMs, requestedPassId))
                .build();
    }

    public TravelPassSnapshot buildSnapshot(ScenePlayer player, long nowMs, int requestedPassId) {
        PlayerTravelPass comp = player.travelPass();
        ResolvedSeason season = selectTargetSeason(comp, nowMs, requestedPassId);
        if (season == null) {
            return TravelPassSnapshot.newBuilder()
                    .setAvailable(false)
                    .setUnavailableReason("游历手记暂未开放")
                    .setServerNowMs(nowMs)
                    .setPassRevision(comp.passRevision())
                    .setDiamondBalance(player.wallet().balance(Wallet.DIAMOND))
                    .setBindDiamondBalance(player.wallet().balance(Wallet.BOUND_DIAMOND))
                    .setEquippedFrameKey(comp.equippedFrameKey())
                    .setEquippedTitleKey(comp.equippedTitleKey())
                    .build();
        }
        return fillSnapshot(player, nowMs, comp, season);
    }

    /** 527 UnlockTravelPassPremium：严按幂等→冻结/战斗→时间窗→报价→防重解→真实灵石余额→原子扣款顺序执行。 */
    public UnlockTravelPassPremiumResponse unlockPremium(SceneWorld world, ScenePlayer player,
                                                         UnlockTravelPassPremiumRequest req, long nowMs) {
        long clientRequestId = req.getClientRequestId();
        if (clientRequestId == 0L || req.getPassId() == 0 || !req.getUnknownFields().asMap().isEmpty()) {
            return rejectUnlock(INVALID_PARAMETER);
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return rejectUnlock(SERVICE_UNAVAILABLE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(clientRequestId, PlayerClientRequestLedger.KIND_UNLOCK_TRAVEL_PASS_PREMIUM, fingerprint);
        if (check.verdict() == Verdict.MISMATCH) {
            return rejectUnlock(INVALID_PARAMETER);
        }
        if (check.verdict() == Verdict.REPLAY) {
            return UnlockTravelPassPremiumResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setSnapshot(buildSnapshot(player, nowMs, req.getPassId()))
                    .setReplayed(true)
                    .build();
        }

        if (player.frozen()) {
            return rejectUnlock(FROZEN);
        }
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.TRAVEL_PASS);
            return rejectUnlock(IN_BATTLE);
        }

        ResolvedSeason season = tables.season(req.getPassId());
        if (season == null || season.enabled() == 0
                || (!season.archived() && !TravelPassTables.isWindowOpen(season.startsAtMs(), season.endsAtMs(), nowMs))) {
            return rejectUnlock(NOT_OPEN);
        }
        if (req.getExpectedCurrencyType() != season.premiumCurrencyType()
                || req.getExpectedPrice() != season.premiumPrice()
                || req.getExpectedConfigRevision() != season.configRevision()) {
            return rejectUnlock(PRICE_CHANGED);
        }

        PlayerTravelPass comp = player.travelPass();
        if (!comp.isValid()) {
            return rejectUnlock(UNAVAILABLE);
        }
        SeasonProgress existing = comp.season(season.id());
        if (existing != null && existing.premiumUnlocked()) {
            return rejectUnlock(ALREADY_UNLOCKED);
        }

        long cost = season.premiumPrice();
        // 严格校验配置指定币种（灵石），绝不拿绑定灵石抵扣。
        if (Long.compareUnsigned(player.wallet().balance(season.premiumCurrencyType()), cost) < 0) {
            return rejectUnlock(CURRENCY_NOT_ENOUGH);
        }

        PlayerTravelPass candidate = comp.copy();
        SeasonProgress target = candidate.findOrAddSeason(season.id());
        if (target == null || !candidate.bumpRevision()) {
            return rejectUnlock(UNAVAILABLE);
        }
        target.setPremiumUnlocked(true);
        target.setPremiumUnlockedAtMs(nowMs);
        target.setLastUpdatedAtMs(nowMs);

        Wallet.Change deducted = currency.deduct(player, season.premiumCurrencyType(), cost,
                AssetAudit.Reason.TRAVEL_PASS_UNLOCK, clientRequestId);
        if (!deducted.ok()) {
            return rejectUnlock(deducted.tipId() == Wallet.FROZEN ? FROZEN : CURRENCY_NOT_ENOUGH);
        }

        comp.replaceFrom(candidate);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_UNLOCK_TRAVEL_PASS_PREMIUM)
                .setFingerprint(fingerprint));
        if (world != null) {
            world.requestKeyProgressSave(player);
        }
        return UnlockTravelPassPremiumResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(fillSnapshot(player, nowMs, comp, season))
                .setReplayed(false)
                .build();
    }

    /** 528 ClaimTravelPassReward：支持单档领奖与 tier_index==0 一键领取，全部物品强制 bound=true，已达档位永不过期。 */
    public ClaimTravelPassRewardResponse claimReward(SceneWorld world, ScenePlayer player,
                                                     ClaimTravelPassRewardRequest req, BagFeature bagView, long nowMs) {
        long clientRequestId = req.getClientRequestId();
        if (clientRequestId == 0L || req.getPassId() == 0 || !req.getUnknownFields().asMap().isEmpty()) {
            return rejectClaim(INVALID_PARAMETER);
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return rejectClaim(SERVICE_UNAVAILABLE);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(clientRequestId, PlayerClientRequestLedger.KIND_CLAIM_TRAVEL_PASS_REWARD, fingerprint);
        if (check.verdict() == Verdict.MISMATCH) {
            return rejectClaim(INVALID_PARAMETER);
        }
        if (check.verdict() == Verdict.REPLAY) {
            ClaimTravelPassRewardResponse.Builder rb = ClaimTravelPassRewardResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setSnapshot(buildSnapshot(player, nowMs, req.getPassId()))
                    .setClaimedCount(check.record().getTravelPassClaimedCount())
                    .setReplayed(true);
            if (bagView != null) {
                rb.setBag(bagView.snapshot(player, BagType.INVENTORY));
            }
            return rb.build();
        }

        if (player.frozen()) {
            return rejectClaim(FROZEN);
        }
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.TRAVEL_PASS);
            return rejectClaim(IN_BATTLE);
        }

        ResolvedSeason season = tables.season(req.getPassId());
        if (season == null) {
            return rejectClaim(NOT_OPEN);
        }
        PlayerTravelPass comp = player.travelPass();
        if (!comp.isValid()) {
            return rejectClaim(UNAVAILABLE);
        }

        SeasonProgress existing = comp.season(season.id());
        int currentPoints = existing == null ? 0 : Math.min(existing.progressPoints(), season.maxPoints());
        boolean premiumUnlocked = existing != null && existing.premiumUnlocked();
        long freeMask = existing == null ? 0L : existing.freeClaimedMask();
        long premiumMask = existing == null ? 0L : existing.premiumClaimedMask();

        record Target(ResolvedTier tier, boolean premiumTrack) { }
        List<Target> targets = new ArrayList<>();
        if (req.getTierIndex() == 0) {
            for (ResolvedTier tier : season.tiers()) {
                if (Integer.compareUnsigned(currentPoints, tier.requiredPoints()) < 0) {
                    continue;
                }
                if (tier.hasFreeReward() && !PlayerTravelPass.isTierClaimed(freeMask, tier.tierIndex())) {
                    targets.add(new Target(tier, false));
                }
                if (premiumUnlocked && tier.hasPremiumReward()
                        && !PlayerTravelPass.isTierClaimed(premiumMask, tier.tierIndex())) {
                    targets.add(new Target(tier, true));
                }
            }
            if (targets.isEmpty()) {
                return rejectClaim(NOTHING_TO_CLAIM);
            }
        } else {
            ResolvedTier found = null;
            for (ResolvedTier tier : season.tiers()) {
                if (tier.tierIndex() == req.getTierIndex()) {
                    found = tier;
                    break;
                }
            }
            if (found == null) {
                return rejectClaim(INVALID_PARAMETER);
            }
            if (Integer.compareUnsigned(currentPoints, found.requiredPoints()) < 0) {
                return rejectClaim(NOT_REACHED);
            }
            if (req.getPremiumTrack()) {
                if (!premiumUnlocked) {
                    return rejectClaim(PREMIUM_REQUIRED);
                }
                if (!found.hasPremiumReward() || PlayerTravelPass.isTierClaimed(premiumMask, found.tierIndex())) {
                    return rejectClaim(NOTHING_TO_CLAIM);
                }
                targets.add(new Target(found, true));
            } else {
                if (!found.hasFreeReward() || PlayerTravelPass.isTierClaimed(freeMask, found.tierIndex())) {
                    return rejectClaim(NOTHING_TO_CLAIM);
                }
                targets.add(new Target(found, false));
            }
        }

        // 聚合绑定物品、银两与零战力纪念外显键
        Map<Integer, Long> mergedItems = new LinkedHashMap<>();
        long totalGold = 0L;
        List<String> cosmeticsToUnlock = new ArrayList<>();
        for (Target target : targets) {
            ResolvedTier t = target.tier();
            int itemId = target.premiumTrack() ? t.premiumItemId() : t.freeItemId();
            int itemCount = target.premiumTrack() ? t.premiumItemCount() : t.freeItemCount();
            long gold = target.premiumTrack() ? t.premiumGold() : t.freeGold();
            String cosmeticKey = target.premiumTrack() ? t.premiumCosmeticKey() : t.freeCosmeticKey();

            if (itemId != 0 && itemCount > 0) {
                if (bags.isItemGainBlocked(player, itemId)) {
                    return rejectClaim(ITEM_BLOCKED);
                }
                long prev = mergedItems.getOrDefault(itemId, 0L);
                long next = prev + Integer.toUnsignedLong(itemCount);
                if (next > MAX_U32) {
                    return rejectClaim(UNAVAILABLE);
                }
                mergedItems.put(itemId, next);
            }
            if (gold > 0L) {
                if (gold > Long.MAX_VALUE - totalGold) {
                    return rejectClaim(UNAVAILABLE);
                }
                totalGold += gold;
            }
            if (!cosmeticKey.isEmpty()) {
                cosmeticsToUnlock.add(cosmeticKey);
            }
        }

        long nowSeconds = nowMs / 1000L;
        if (totalGold > 0L) {
            int goldCheck = currency.checkAdd(player, Wallet.GOLD, totalGold, nowSeconds);
            if (goldCheck != 0) {
                return rejectClaim(goldCheck == Wallet.BLOCKED ? ITEM_BLOCKED
                        : goldCheck == Wallet.FROZEN ? FROZEN : UNAVAILABLE);
            }
        }

        // 候选背包规划（全部物品强制 bound=true）
        PlayerBags candidateBags = null;
        List<Bag.Written> written = List.of();
        if (!mergedItems.isEmpty()) {
            candidateBags = PlayerBags.restore(player.bags().toState());
            candidateBags.normalize(bags.tables());
            Bag.AddResult addRes = bags.prepareEquipmentGrant(player, candidateBags, mergedItems, true);
            if (!addRes.ok()) {
                int tipId = addRes.tip() == Bag.NO_SPACE ? BAG_FULL
                        : addRes.tip() == BagService.ITEM_BLOCKED ? ITEM_BLOCKED
                        : addRes.tip() == BagService.REFUSED ? (player.frozen() ? FROZEN : ITEM_BLOCKED)
                        : UNAVAILABLE;
                return rejectClaim(tipId);
            }
            written = addRes.written();
        }

        // 候选手记进度规划
        PlayerTravelPass candidateComp = comp.copy();
        SeasonProgress targetSeason = candidateComp.findOrAddSeason(season.id());
        if (targetSeason == null || !candidateComp.bumpRevision()) {
            return rejectClaim(UNAVAILABLE);
        }
        for (Target target : targets) {
            long bit = PlayerTravelPass.tierBit(target.tier().tierIndex());
            if (target.premiumTrack()) {
                targetSeason.setPremiumClaimedMask(targetSeason.premiumClaimedMask() | bit);
            } else {
                targetSeason.setFreeClaimedMask(targetSeason.freeClaimedMask() | bit);
            }
        }
        for (String key : cosmeticsToUnlock) {
            if (!targetSeason.addCosmeticKey(key)) {
                return rejectClaim(UNAVAILABLE);
            }
        }
        targetSeason.setLastUpdatedAtMs(nowMs);

        // 原子提交（预检全部通过后同帧发布）
        int claimedCount = targets.size();
        String extra = "{\"source\":\"travel_pass\",\"pass_id\":" + Integer.toUnsignedString(season.id())
                + ",\"claimed_tracks\":" + claimedCount + ",\"gold\":" + totalGold + "}";
        if (totalGold > 0L) {
            Wallet.Change added = currency.add(player, Wallet.GOLD, totalGold,
                    AssetAudit.Reason.TRAVEL_PASS_REWARD, clientRequestId, nowSeconds);
            if (!added.ok()) {
                return rejectClaim(added.tipId() == Wallet.BLOCKED ? ITEM_BLOCKED
                        : added.tipId() == Wallet.FROZEN ? FROZEN : UNAVAILABLE);
            }
        }
        if (candidateBags != null) {
            player.bags().commitPrepared(candidateBags);
            bags.auditEquipment(player, List.of(), written, AssetAudit.Reason.TRAVEL_PASS_REWARD, clientRequestId, extra);
        }
        comp.replaceFrom(candidateComp);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_CLAIM_TRAVEL_PASS_REWARD)
                .setFingerprint(fingerprint)
                .setTravelPassClaimedCount(claimedCount));
        if (world != null) {
            world.requestKeyProgressSave(player);
        }

        ClaimTravelPassRewardResponse.Builder out = ClaimTravelPassRewardResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(fillSnapshot(player, nowMs, comp, season))
                .setClaimedCount(claimedCount)
                .setReplayed(false);
        if (bagView != null) {
            out.setBag(bagView.snapshot(player, BagType.INVENTORY));
        }
        return out.build();
    }

    /** 529 EquipTravelPassCosmetic：切换或卸下已解锁的零战力纪念头像框/称谓。 */
    public EquipTravelPassCosmeticResponse equipCosmetic(SceneWorld world, ScenePlayer player,
                                                         EquipTravelPassCosmeticRequest req, long nowMs) {
        int slot = req.getCosmeticSlot();
        if (slot != PlayerTravelPass.COSMETIC_SLOT_FRAME && slot != PlayerTravelPass.COSMETIC_SLOT_TITLE) {
            return rejectEquip(INVALID_PARAMETER);
        }
        if (player.frozen()) {
            return rejectEquip(FROZEN);
        }
        PlayerTravelPass comp = player.travelPass();
        if (!comp.isValid()) {
            return rejectEquip(UNAVAILABLE);
        }
        String key = req.getCosmeticKey();
        if (!key.isEmpty()) {
            boolean validForSlot = slot == PlayerTravelPass.COSMETIC_SLOT_FRAME
                    ? PlayerTravelPass.isFrameKey(key) : PlayerTravelPass.isTitleKey(key);
            if (!validForSlot) {
                return rejectEquip(INVALID_PARAMETER);
            }
            if (!comp.hasCosmeticKey(key)) {
                return rejectEquip(COSMETIC_NOT_OWNED);
            }
        }
        if (slot == PlayerTravelPass.COSMETIC_SLOT_FRAME) {
            comp.setEquippedFrameKey(key);
        } else {
            comp.setEquippedTitleKey(key);
        }
        comp.bumpRevision();
        if (world != null) {
            world.requestKeyProgressSave(player);
        }
        return EquipTravelPassCosmeticResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(buildSnapshot(player, nowMs, 0))
                .build();
    }

    /** 591 SelectActiveTravelPass：切换永不绝版书架的当前在研手记（仅 1 册累计游历点，零消耗随时切换）。 */
    public SelectActiveTravelPassResponse selectActivePass(SceneWorld world, ScenePlayer player,
                                                           SelectActiveTravelPassRequest req, long nowMs) {
        int passId = req.getPassId();
        if (passId == 0 || !req.getUnknownFields().asMap().isEmpty()) {
            return SelectActiveTravelPassResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (player.frozen()) {
            return SelectActiveTravelPassResponse.newBuilder().setErrorMessage(tip(FROZEN)).build();
        }
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.TRAVEL_PASS);
            return SelectActiveTravelPassResponse.newBuilder().setErrorMessage(tip(IN_BATTLE)).build();
        }
        PlayerTravelPass comp = player.travelPass();
        if (!comp.isValid()) {
            return SelectActiveTravelPassResponse.newBuilder().setErrorMessage(tip(UNAVAILABLE)).build();
        }
        ResolvedSeason season = tables.season(passId);
        SeasonProgress prog = comp.season(passId);
        boolean unlocked = prog != null && prog.premiumUnlocked();
        if (season == null || !isSeasonOpenForPlayer(season, nowMs, unlocked)) {
            return SelectActiveTravelPassResponse.newBuilder().setErrorMessage(tip(NOT_OPEN)).build();
        }
        if (comp.activePassId() != passId) {
            comp.setActivePassId(passId);
            comp.bumpRevision();
            if (world != null) {
                world.requestKeyProgressSave(player);
            }
        }
        return SelectActiveTravelPassResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(fillSnapshot(player, nowMs, comp, season))
                .build();
    }

    private static boolean isSeasonOpenForPlayer(ResolvedSeason season, long nowMs, boolean unlocked) {
        if (season.enabled() == 0) {
            return unlocked;
        }
        return unlocked || season.archived() || TravelPassTables.isWindowOpen(season.startsAtMs(), season.endsAtMs(), nowMs);
    }

    private ResolvedSeason resolveActiveSeason(PlayerTravelPass comp, long nowMs) {
        List<ResolvedSeason> enabled = tables.enabledSeasons();
        if (enabled.isEmpty()) {
            return null;
        }
        int chosenId = comp.activePassId();
        if (chosenId != 0) {
            for (ResolvedSeason s : enabled) {
                if (s.id() == chosenId) {
                    SeasonProgress p = comp.season(s.id());
                    boolean unlocked = p != null && p.premiumUnlocked();
                    if (isSeasonOpenForPlayer(s, nowMs, unlocked)) {
                        return s;
                    }
                    break;
                }
            }
        }
        for (ResolvedSeason s : enabled) {
            SeasonProgress p = comp.season(s.id());
            int pts = p == null ? 0 : Math.min(p.progressPoints(), s.maxPoints());
            boolean unlocked = p != null && p.premiumUnlocked();
            if (isSeasonOpenForPlayer(s, nowMs, unlocked) && Integer.compareUnsigned(pts, s.maxPoints()) < 0) {
                return s;
            }
        }
        for (ResolvedSeason s : enabled) {
            SeasonProgress p = comp.season(s.id());
            boolean unlocked = p != null && p.premiumUnlocked();
            if (isSeasonOpenForPlayer(s, nowMs, unlocked)) {
                return s;
            }
        }
        return null;
    }

    /** 战斗结算原子推进游历点（M-1 单册在研防多期同时吃满；满档自动切下一未满册）。 */
    public boolean creditBattlePoints(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        if (!isEligibleBattle(settlement)) {
            return false;
        }
        PlayerTravelPass comp = player.travelPass();
        if (!comp.isValid()) {
            return false;
        }
        ResolvedSeason active = resolveActiveSeason(comp, nowMs);
        if (active == null) {
            return false;
        }
        int gain = computeBattlePoints(active, settlement);
        if (gain <= 0) {
            return false;
        }
        SeasonProgress prog = comp.findOrAddSeason(active.id());
        if (prog == null) {
            return false;
        }
        int before = Math.min(prog.progressPoints(), active.maxPoints());
        if (before >= active.maxPoints()) {
            return false;
        }
        int after = before + Math.min(gain, active.maxPoints() - before);
        prog.setProgressPoints(after);
        prog.setLastUpdatedAtMs(nowMs);
        if (after >= active.maxPoints()) {
            for (ResolvedSeason nxt : tables.enabledSeasons()) {
                if (nxt.id() == active.id()) {
                    continue;
                }
                SeasonProgress np = comp.season(nxt.id());
                int nPts = np == null ? 0 : Math.min(np.progressPoints(), nxt.maxPoints());
                boolean nUnlocked = np != null && np.premiumUnlocked();
                if (isSeasonOpenForPlayer(nxt, nowMs, nUnlocked) && Integer.compareUnsigned(nPts, nxt.maxPoints()) < 0) {
                    comp.setActivePassId(nxt.id());
                    break;
                }
            }
        }
        comp.bumpRevision();
        return true;
    }

    public static boolean isEligibleBattle(BattleSettlementData settlement) {
        if (settlement == null || settlement.getBattleId() == 0L || settlement.getPlayerId() == 0L
                || settlement.getFled() || settlement.getTotalRounds() == 0
                || Integer.compareUnsigned(settlement.getPlayerTeamIndex(), 1) > 0
                || BattleConstants.isNpcIsolatedEncounterKind(settlement.getBossTrialKind())) {
            return false;
        }
        eBattleOutcome outcome = settlement.getOutcome();
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_DRAW) {
            return false;
        }
        boolean won = playerWon(settlement);
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE -> won;
            case MatchMode.MATCH_MODE_1V1_VALUE,
                    MatchMode.MATCH_MODE_5V5_VALUE,
                    MatchMode.MATCH_MODE_PVP_TEAM_VALUE,
                    MatchMode.MATCH_MODE_POLARITY_5V5_VALUE,
                    MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE,
                    MatchMode.MATCH_MODE_GUILD_WAR_SIEGE_VALUE -> {
                if (!settlement.getParticipated()) {
                    yield false;
                }
                if (won) {
                    yield true;
                }
                yield Integer.compareUnsigned(settlement.getTotalRounds(), PVP_MIN_ROUNDS_FOR_LOSS) >= 0
                        && Integer.compareUnsigned(settlement.getManualActionRounds(), PVP_MIN_MANUAL_ROUNDS_FOR_LOSS) >= 0
                        && settlement.getEffectiveCombatContribution();
            }
            default -> false;
        };
    }

    public static int computeBattlePoints(ResolvedSeason season, BattleSettlementData settlement) {
        if (!isEligibleBattle(settlement)) {
            return 0;
        }
        boolean won = playerWon(settlement);
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE ->
                    won ? (settlement.getBattleDifficulty() == 2 ? season.hardOrPvpWinPoints() : season.pveWinPoints()) : 0;
            case MatchMode.MATCH_MODE_1V1_VALUE,
                    MatchMode.MATCH_MODE_5V5_VALUE,
                    MatchMode.MATCH_MODE_PVP_TEAM_VALUE,
                    MatchMode.MATCH_MODE_POLARITY_5V5_VALUE,
                    MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE,
                    MatchMode.MATCH_MODE_GUILD_WAR_SIEGE_VALUE ->
                    won ? season.hardOrPvpWinPoints() : season.pvpQualifiedOtherPoints();
            default -> 0;
        };
    }

    /** 190 GetActivityList 目录聚合：追加 900001 游历手记 + 900002 免费试穿 + 900003 灵石百宝阁轻便利。 */
    public void appendActivityCatalogEntries(ScenePlayer player, long nowMs,
                                             AppearanceService appearanceService,
                                             SpiritShopService spiritShopService,
                                             List<PlayerActivityInfo> out) {
        // 1. 900001 青云游历手记（仅在有真实可领档位时亮红点）
        if (player.travelPass().isValid()) {
            TravelPassSnapshot snap = buildSnapshot(player, nowMs, 0);
            if (snap.getAvailable()) {
                int bookshelfClaimable = 0;
                for (TravelPassCatalogEntry b : snap.getBookshelfList()) {
                    bookshelfClaimable += b.getClaimableCount();
                }
                int claimableTotal = bookshelfClaimable > 0
                        ? bookshelfClaimable
                        : (snap.getClaimableFreeCount() + snap.getClaimablePremiumCount());
                String desc = claimableTotal > 0
                        ? "有 " + claimableTotal + " 档游历好礼待领取 · 免费轨可领，典藏轨可先玩满再补票"
                        : snap.getPremiumUnlocked()
                                ? "典藏手记已解锁（永不过期）· 正常打试炼/会武自动涨游历印，无每日打卡"
                                : "无每日打卡、正常战斗自动涨点；免费领定星保底符，满点后可自愿 180 灵石领典藏外观";
                PlayerActivityInfo.Builder entry = PlayerActivityInfo.newBuilder()
                        .setActivityId(ACTIVITY_ID_TRAVEL_PASS)
                        .setName(snap.getName())
                        .setDescription(desc)
                        .setIconKey(snap.getIconKey())
                        .setStatus(PlayerActivityStatus.PLAYER_ACTIVITY_OPEN)
                        .setStartsAtMs(snap.getStartsAtMs())
                        .setEndsAtMs(snap.getEndsAtMs())
                        .setCanParticipate(true)
                        .setDefinitionKey("activity.travel_pass." + Integer.toUnsignedString(snap.getPassId()))
                        .setConfigurationRevision(snap.getConfigRevision())
                        .setCategory(PlayerActivityCategory.PLAYER_ACTIVITY_CATEGORY_BENEFIT)
                        .addRewardTypes(PlayerActivityRewardType.PLAYER_ACTIVITY_REWARD_ITEM)
                        .setEntryKind(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_TRAVEL_PASS)
                        .setCompletionCountKnown(true)
                        .setCompletedCount(snap.getProgressPoints())
                        .setCompletionLimit(snap.getMaxPoints())
                        .setCompletionPeriod("整期累计 · 无日清")
                        .setHasClaimableReward(claimableTotal > 0);
                for (int itemId : new int[]{3405, 3402}) {
                    ItemTable itemRow = tables.item(itemId);
                    if (itemRow == null) {
                        continue;
                    }
                    entry.addRewards(PlayerActivityRewardItem.newBuilder()
                            .setItemConfigId(itemId)
                            .setCount(itemId == 3402 ? 2 : 1)
                            .setName(itemRow.getName())
                            .setIconKey(itemRow.getIconKey())
                            .setBindingDescription("绑定仅自用"));
                }
                out.add(entry.build());
            }
        }

        // 2. 900002 留灯风物 · 免费试穿（恒定零红点）
        if (appearanceService != null) {
            PlayerAppearanceInfo appInfo = appearanceService.buildInfo(player, nowMs / 1000L);
            int totalCount = appInfo.getAppearancesCount();
            if (totalCount > 0) {
                int ownedCount = 0;
                int trialAvailableCount = 0;
                for (var app : appInfo.getAppearancesList()) {
                    if (app.getOwned()) {
                        ownedCount++;
                    } else if (!app.getTrialUsed()) {
                        trialAvailableCount++;
                    }
                }
                String desc = trialAvailableCount > 0
                        ? "还有 " + trialAvailableCount + " 位角色可免费试穿 1 小时，阅读人物志并随时自由切换（0 数值加成）"
                        : "永久收藏衣柜与人物志合篇 · 已拥有形象随时免费切换，0 战力加成";
                out.add(PlayerActivityInfo.newBuilder()
                        .setActivityId(ACTIVITY_ID_APPEARANCE_SHOWCASE)
                        .setName("留灯风物 · 免费试穿")
                        .setDescription(desc)
                        .setIconKey("UI/Ugui/TravelPassV1/showcase_appearance_icon")
                        .setStatus(PlayerActivityStatus.PLAYER_ACTIVITY_OPEN)
                        .setCanParticipate(true)
                        .setDefinitionKey("activity.showcase.appearance.v1")
                        .setConfigurationRevision(1)
                        .setCategory(PlayerActivityCategory.PLAYER_ACTIVITY_CATEGORY_BENEFIT)
                        .addRewardTypes(PlayerActivityRewardType.PLAYER_ACTIVITY_REWARD_ITEM)
                        .setEntryKind(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_APPEARANCE_SHOWCASE)
                        .setCompletionCountKnown(true)
                        .setCompletedCount(ownedCount)
                        .setCompletionLimit(totalCount)
                        .setCompletionPeriod("常驻体验 · 零强迫")
                        .setHasClaimableReward(false)
                        .build());
            }
        }

        // 3. 900003 灵石百宝阁 · 轻便利（恒定零红点）
        if (spiritShopService != null && player.spiritShop().isValid()) {
            SpiritShopInfo shopInfo = spiritShopService.buildInfo(player, nowMs);
            if (shopInfo.getGoodsCount() > 0) {
                int starterLimit = 0;
                int starterRemaining = 0;
                for (SpiritShopLimitGroupView g : shopInfo.getLimitGroupsList()) {
                    if (g.getGroupId() == 1) {
                        starterLimit = g.getMaxWeight();
                        starterRemaining = Math.max(0, g.getMaxWeight() - g.getUsedWeight());
                        break;
                    }
                }
                out.add(PlayerActivityInfo.newBuilder()
                        .setActivityId(ACTIVITY_ID_SPIRIT_SHOP_SHOWCASE)
                        .setName("灵石百宝阁 · 轻便利")
                        .setDescription("明码标价 6–30 元绑定提速包、定星保底符、角色形象卡与轻享卡，0 战力上限加成")
                        .setIconKey("UI/Ugui/TravelPassV1/showcase_spirit_shop_icon")
                        .setStatus(PlayerActivityStatus.PLAYER_ACTIVITY_OPEN)
                        .setCanParticipate(true)
                        .setDefinitionKey("activity.showcase.spirit_shop.v1")
                        .setConfigurationRevision(1)
                        .setCategory(PlayerActivityCategory.PLAYER_ACTIVITY_CATEGORY_BENEFIT)
                        .addRewardTypes(PlayerActivityRewardType.PLAYER_ACTIVITY_REWARD_ITEM)
                        .setEntryKind(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_SPIRIT_SHOP_SHOWCASE)
                        .setCompletionCountKnown(starterLimit > 0)
                        .setCompletedCount(Math.max(0, starterLimit - starterRemaining))
                        .setCompletionLimit(starterLimit)
                        .setCompletionPeriod("终身限额 · 按需自愿")
                        .setHasClaimableReward(false)
                        .build());
            }
        }
    }

    private ResolvedSeason selectTargetSeason(PlayerTravelPass comp, long nowMs, int requestedPassId) {
        if (requestedPassId != 0) {
            return tables.season(requestedPassId);
        }
        ResolvedSeason active = resolveActiveSeason(comp, nowMs);
        if (active != null) {
            return active;
        }
        ResolvedSeason bestCarryover = null;
        for (SeasonProgress prog : comp.seasons()) {
            ResolvedSeason s = tables.season(prog.passId());
            if (s == null) {
                continue;
            }
            boolean retain = (prog.premiumUnlocked() && Integer.compareUnsigned(prog.progressPoints(), s.maxPoints()) < 0)
                    || hasUnclaimedReward(s, prog);
            if (!retain) {
                continue;
            }
            if (bestCarryover == null || Integer.compareUnsigned(s.id(), bestCarryover.id()) > 0) {
                bestCarryover = s;
            }
        }
        return bestCarryover;
    }

    private static int countClaimable(ResolvedSeason season, SeasonProgress progress) {
        if (progress == null) {
            return 0;
        }
        int count = 0;
        for (ResolvedTier tier : season.tiers()) {
            if (Integer.compareUnsigned(progress.progressPoints(), tier.requiredPoints()) < 0) {
                continue;
            }
            if (tier.hasFreeReward() && !PlayerTravelPass.isTierClaimed(progress.freeClaimedMask(), tier.tierIndex())) {
                count++;
            }
            if (progress.premiumUnlocked() && tier.hasPremiumReward()
                    && !PlayerTravelPass.isTierClaimed(progress.premiumClaimedMask(), tier.tierIndex())) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasUnclaimedReward(ResolvedSeason season, SeasonProgress progress) {
        return countClaimable(season, progress) > 0;
    }

    private TravelPassSnapshot fillSnapshot(ScenePlayer player, long nowMs, PlayerTravelPass comp, ResolvedSeason season) {
        SeasonProgress progress = comp.season(season.id());
        int points = progress == null ? 0 : Math.min(progress.progressPoints(), season.maxPoints());
        boolean premiumUnlocked = progress != null && progress.premiumUnlocked();
        long freeMask = progress == null ? 0L : progress.freeClaimedMask();
        long premiumMask = progress == null ? 0L : progress.premiumClaimedMask();
        boolean windowOpen = season.enabled() != 0
                && (season.archived() || TravelPassTables.isWindowOpen(season.startsAtMs(), season.endsAtMs(), nowMs));
        ResolvedSeason activeSeason = resolveActiveSeason(comp, nowMs);
        int activePassId = activeSeason == null ? 0 : activeSeason.id();

        TravelPassSnapshot.Builder out = TravelPassSnapshot.newBuilder()
                .setAvailable(true)
                .setServerNowMs(nowMs)
                .setPassRevision(comp.passRevision())
                .setPassId(season.id())
                .setConfigRevision(season.configRevision())
                .setName(season.name())
                .setDescription(season.description())
                .setIconKey(season.iconKey())
                .setBannerAssetKey(season.bannerAssetKey())
                .setStartsAtMs(season.startsAtMs())
                .setEndsAtMs(season.endsAtMs())
                .setOpenForProgress(windowOpen || (premiumUnlocked && Integer.compareUnsigned(points, season.maxPoints()) < 0))
                .setOpenForUnlock(windowOpen && !premiumUnlocked)
                .setProgressPoints(points)
                .setMaxPoints(season.maxPoints())
                .setPveWinPoints(season.pveWinPoints())
                .setHardOrPvpWinPoints(season.hardOrPvpWinPoints())
                .setPvpQualifiedOtherPoints(season.pvpQualifiedOtherPoints())
                .setPremiumUnlocked(premiumUnlocked)
                .setPremiumUnlockedAtMs(progress == null ? 0L : progress.premiumUnlockedAtMs())
                .setPremiumCurrencyType(season.premiumCurrencyType())
                .setPremiumPrice(season.premiumPrice())
                .setDiamondBalance(player.wallet().balance(Wallet.DIAMOND))
                .setBindDiamondBalance(player.wallet().balance(Wallet.BOUND_DIAMOND))
                .setActivePassId(activePassId);
        for (ResolvedSeason shelf : tables.enabledSeasons()) {
            SeasonProgress sp = comp.season(shelf.id());
            boolean sUnlocked = sp != null && sp.premiumUnlocked();
            if (!isSeasonOpenForPlayer(shelf, nowMs, sUnlocked)) {
                continue;
            }
            out.addBookshelf(TravelPassCatalogEntry.newBuilder()
                    .setPassId(shelf.id())
                    .setName(shelf.name())
                    .setSortOrder(shelf.sortOrder())
                    .setIsArchived(shelf.archived())
                    .setIsActive(shelf.id() == activePassId)
                    .setProgressPoints(sp == null ? 0 : Math.min(sp.progressPoints(), shelf.maxPoints()))
                    .setMaxPoints(shelf.maxPoints())
                    .setPremiumUnlocked(sUnlocked)
                    .setClaimableCount(countClaimable(shelf, sp))
                    .setIconKey(shelf.iconKey())
                    .setBannerAssetKey(shelf.bannerAssetKey()));
        }

        int claimableFree = 0;
        int claimablePremium = 0;
        int retroactivePremium = 0;
        for (ResolvedTier tier : season.tiers()) {
            boolean reached = Integer.compareUnsigned(points, tier.requiredPoints()) >= 0;
            boolean freeClaimed = PlayerTravelPass.isTierClaimed(freeMask, tier.tierIndex());
            boolean freeCanClaim = tier.hasFreeReward() && reached && !freeClaimed;
            if (freeCanClaim) {
                claimableFree++;
            }
            boolean premClaimed = PlayerTravelPass.isTierClaimed(premiumMask, tier.tierIndex());
            boolean premCanClaim = tier.hasPremiumReward() && reached && premiumUnlocked && !premClaimed;
            if (premCanClaim) {
                claimablePremium++;
            } else if (tier.hasPremiumReward() && reached && !premiumUnlocked && !premClaimed) {
                retroactivePremium++;
            }
            out.addTiers(TravelPassTierView.newBuilder()
                    .setTierId(tier.tierId())
                    .setTierIndex(tier.tierIndex())
                    .setRequiredPoints(tier.requiredPoints())
                    .setReached(reached)
                    .setFreeItemId(tier.freeItemId())
                    .setFreeItemCount(tier.freeItemCount())
                    .setFreeItemName(tier.freeItemName())
                    .setFreeItemIconKey(tier.freeItemIconKey())
                    .setFreeGold(tier.freeGold())
                    .setFreeCosmeticKey(tier.freeCosmeticKey())
                    .setFreeCosmeticName(tier.freeCosmeticName())
                    .setFreeClaimed(freeClaimed)
                    .setFreeCanClaim(freeCanClaim)
                    .setPremiumItemId(tier.premiumItemId())
                    .setPremiumItemCount(tier.premiumItemCount())
                    .setPremiumItemName(tier.premiumItemName())
                    .setPremiumItemIconKey(tier.premiumItemIconKey())
                    .setPremiumGold(tier.premiumGold())
                    .setPremiumCosmeticKey(tier.premiumCosmeticKey())
                    .setPremiumCosmeticName(tier.premiumCosmeticName())
                    .setPremiumClaimed(premClaimed)
                    .setPremiumCanClaim(premCanClaim));
        }

        out.setClaimableFreeCount(claimableFree)
                .setClaimablePremiumCount(claimablePremium)
                .setRetroactivePremiumCount(retroactivePremium)
                .addAllUnlockedCosmeticKeys(comp.allUnlockedCosmeticsSorted())
                .setEquippedFrameKey(comp.equippedFrameKey())
                .setEquippedTitleKey(comp.equippedTitleKey());
        return out.build();
    }

    private static boolean playerWon(BattleSettlementData settlement) {
        return (settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                || (settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1);
    }

    private static UnlockTravelPassPremiumResponse rejectUnlock(int tipId) {
        return UnlockTravelPassPremiumResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }

    private static ClaimTravelPassRewardResponse rejectClaim(int tipId) {
        return ClaimTravelPassRewardResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }

    private static EquipTravelPassCosmeticResponse rejectEquip(int tipId) {
        return EquipTravelPassCosmeticResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }
}
