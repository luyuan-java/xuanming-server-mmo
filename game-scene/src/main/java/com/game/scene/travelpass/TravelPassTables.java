package com.game.scene.travelpass;

import com.game.scene.player.PlayerTravelPass;
import com.game.scene.player.Wallet;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.TravelPassSeasonTable;
import com.game.table.TravelPassTierTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 青云游历手记不可变配表视图（{@code TravelPassSeason} / {@code TravelPassTier} / {@code Item}；
 * 对齐 C++ {@code PlayerTravelPassSystem::ValidateTables}，非法行 fail-closed 拒开放拒发奖）。
 */
public final class TravelPassTables {

    private static final Logger log = LoggerFactory.getLogger(TravelPassTables.class);

    public static final long MAX_UNIT_PRICE = 1_000_000_000L;

    public record ResolvedSeason(
            int id,
            int enabled,
            String name,
            String description,
            String iconKey,
            String bannerAssetKey,
            long startsAtMs,
            long endsAtMs,
            int maxPoints,
            int pveWinPoints,
            int hardOrPvpWinPoints,
            int pvpQualifiedOtherPoints,
            int premiumCurrencyType,
            long premiumPrice,
            int configRevision,
            int sortOrder,
            boolean archived,
            List<ResolvedTier> tiers) {
    }

    public record ResolvedTier(
            int tierId,
            int passId,
            int tierIndex,
            int requiredPoints,
            int freeItemId,
            int freeItemCount,
            String freeItemName,
            String freeItemIconKey,
            long freeGold,
            String freeCosmeticKey,
            String freeCosmeticName,
            int premiumItemId,
            int premiumItemCount,
            String premiumItemName,
            String premiumItemIconKey,
            long premiumGold,
            String premiumCosmeticKey,
            String premiumCosmeticName) {

        public boolean hasFreeReward() {
            return freeItemId != 0 || freeGold != 0L || !freeCosmeticKey.isEmpty();
        }

        public boolean hasPremiumReward() {
            return premiumItemId != 0 || premiumGold != 0L || !premiumCosmeticKey.isEmpty();
        }
    }

    private final Map<Integer, ResolvedSeason> seasonsById;
    private final List<ResolvedSeason> enabledSeasons;
    private final Map<Integer, ItemTable> itemsById;

    public TravelPassTables(List<TravelPassSeasonTable> seasonRows,
                            List<TravelPassTierTable> tierRows,
                            List<ItemTable> itemRows) {
        Map<Integer, ItemTable> items = new LinkedHashMap<>();
        for (ItemTable item : itemRows) {
            items.putIfAbsent(item.getId(), item);
        }
        this.itemsById = Collections.unmodifiableMap(items);

        Map<Integer, List<TravelPassTierTable>> rawTiersByPass = new LinkedHashMap<>();
        for (TravelPassTierTable row : tierRows) {
            rawTiersByPass.computeIfAbsent(row.getPassId(), k -> new ArrayList<>()).add(row);
        }

        Map<Integer, ResolvedSeason> validSeasons = new LinkedHashMap<>();
        List<ResolvedSeason> enabled = new ArrayList<>();
        for (TravelPassSeasonTable row : seasonRows) {
            ResolvedSeason resolved = validateSeason(row, rawTiersByPass.getOrDefault(row.getId(), List.of()), items);
            if (resolved == null) {
                continue;
            }
            if (validSeasons.containsKey(resolved.id())) {
                log.error("[TravelPass] duplicate TravelPassSeason id={}", Integer.toUnsignedString(resolved.id()));
                continue;
            }
            validSeasons.put(resolved.id(), resolved);
            if (resolved.enabled() != 0) {
                enabled.add(resolved);
            }
        }

        // 永不绝版书架制允许多个已启用期次并存，玩家每次仅选定 1 册在研累计游历点，不再拒绝时间窗重叠。
        for (TravelPassTierTable tier : tierRows) {
            if (!validSeasons.containsKey(tier.getPassId())) {
                log.error("[TravelPass] TravelPassTier references unknown/invalid pass_id tier_id={} pass_id={}",
                        Integer.toUnsignedString(tier.getId()), Integer.toUnsignedString(tier.getPassId()));
            }
        }

        enabled.sort(Comparator.comparingInt(ResolvedSeason::sortOrder).thenComparingInt(ResolvedSeason::id));
        this.seasonsById = Collections.unmodifiableMap(validSeasons);
        this.enabledSeasons = List.copyOf(enabled);
    }

    public static TravelPassTables from(ConfigTables tables) {
        return new TravelPassTables(
                tables.travelPassSeason().all(),
                tables.travelPassTier().all(),
                tables.item().all());
    }

    public static TravelPassTables empty() {
        return new TravelPassTables(List.of(), List.of(), List.of());
    }

    public ResolvedSeason season(int passId) {
        return seasonsById.get(passId);
    }

    public List<ResolvedSeason> enabledSeasons() {
        return enabledSeasons;
    }

    public ItemTable item(int itemId) {
        return itemsById.get(itemId);
    }

    public static boolean isWindowOpen(long startsAtMs, long endsAtMs, long nowMs) {
        if (startsAtMs != 0L && Long.compareUnsigned(nowMs, startsAtMs) < 0) {
            return false;
        }
        if (endsAtMs != 0L && Long.compareUnsigned(nowMs, endsAtMs) >= 0) {
            return false;
        }
        return true;
    }

    public static boolean windowsOverlap(long startA, long endA, long startB, long endB) {
        long effEndA = endA == 0L ? -1L : endA;
        long effEndB = endB == 0L ? -1L : endB;
        return Long.compareUnsigned(startA, effEndB) < 0 && Long.compareUnsigned(startB, effEndA) < 0;
    }

    private static ResolvedSeason validateSeason(TravelPassSeasonTable row,
                                                 List<TravelPassTierTable> tierRows,
                                                 Map<Integer, ItemTable> itemsById) {
        int id = row.getId();
        int enabled = row.getEnabled();
        if (id == 0 || (enabled != 0 && enabled != 1) || row.getName().isEmpty()
                || row.getMaxPoints() == 0 || row.getPveWinPoints() == 0
                || row.getHardOrPvpWinPoints() == 0 || row.getPvpQualifiedOtherPoints() == 0
                || row.getConfigRevision() == 0) {
            log.error("[TravelPass] invalid TravelPassSeason header id={}", Integer.toUnsignedString(id));
            return null;
        }
        long startsAt = row.getStartsAtMs();
        long endsAt = row.getEndsAtMs();
        if (endsAt != 0L && Long.compareUnsigned(endsAt, startsAt) <= 0) {
            log.error("[TravelPass] invalid TravelPassSeason window id={}", Integer.toUnsignedString(id));
            return null;
        }
        int currencyType = row.getPremiumCurrencyType();
        long price = row.getPremiumPrice();
        // 典藏手记仅允许真实灵石解锁，禁止绑定灵石或绑定银两绕过付费门槛
        if (currencyType != Wallet.DIAMOND
                || price == 0L || Long.compareUnsigned(price, MAX_UNIT_PRICE) > 0) {
            log.error("[TravelPass] invalid TravelPassSeason price id={} currency={} price={}",
                    Integer.toUnsignedString(id), currencyType, Long.toUnsignedString(price));
            return null;
        }
        if (tierRows.isEmpty()) {
            log.error("[TravelPass] TravelPassSeason has no tiers id={}", Integer.toUnsignedString(id));
            return null;
        }

        List<ResolvedTier> resolvedTiers = new ArrayList<>(tierRows.size());
        for (TravelPassTierTable tr : tierRows) {
            ResolvedTier t = validateTier(tr, row.getMaxPoints(), itemsById);
            if (t == null) {
                log.error("[TravelPass] invalid TravelPassTier id={} pass_id={}",
                        Integer.toUnsignedString(tr.getId()), Integer.toUnsignedString(id));
                return null;
            }
            resolvedTiers.add(t);
        }
        resolvedTiers.sort(Comparator.comparingInt(ResolvedTier::tierIndex));
        for (int i = 1; i < resolvedTiers.size(); i++) {
            ResolvedTier prev = resolvedTiers.get(i - 1);
            ResolvedTier cur = resolvedTiers.get(i);
            if (cur.tierIndex() == prev.tierIndex()
                    || Integer.compareUnsigned(cur.requiredPoints(), prev.requiredPoints()) <= 0) {
                log.error("[TravelPass] non-monotonic tier order pass_id={} tier_index={}",
                        Integer.toUnsignedString(id), cur.tierIndex());
                return null;
            }
        }

        return new ResolvedSeason(
                id, enabled, row.getName(), row.getDescription(), row.getIconKey(), row.getBannerAssetKey(),
                startsAt, endsAt, row.getMaxPoints(), row.getPveWinPoints(),
                row.getHardOrPvpWinPoints(), row.getPvpQualifiedOtherPoints(),
                currencyType, price, row.getConfigRevision(),
                row.getSortOrder(), row.getIsArchived() != 0,
                List.copyOf(resolvedTiers));
    }

    private static ResolvedTier validateTier(TravelPassTierTable row, int maxPoints, Map<Integer, ItemTable> itemsById) {
        int tierIndex = row.getTierIndex();
        int reqPoints = row.getRequiredPoints();
        if (tierIndex <= 0 || tierIndex > PlayerTravelPass.MAX_TIERS_PER_SEASON
                || reqPoints == 0 || Integer.compareUnsigned(reqPoints, maxPoints) > 0) {
            return null;
        }
        String freeName = "";
        String freeIcon = "";
        if (row.getFreeItemId() != 0) {
            ItemTable item = itemsById.get(row.getFreeItemId());
            if (row.getFreeItemCount() == 0 || item == null || item.getMaxStackSize() == 0) {
                return null;
            }
            freeName = item.getName();
            freeIcon = item.getIconKey();
        } else if (row.getFreeItemCount() != 0) {
            return null;
        }
        if (!row.getFreeCosmeticKey().isEmpty()
                && (!PlayerTravelPass.isValidCosmeticKey(row.getFreeCosmeticKey()) || row.getFreeCosmeticName().isEmpty())) {
            return null;
        }

        String premName = "";
        String premIcon = "";
        if (row.getPremiumItemId() != 0) {
            ItemTable item = itemsById.get(row.getPremiumItemId());
            if (row.getPremiumItemCount() == 0 || item == null || item.getMaxStackSize() == 0) {
                return null;
            }
            premName = item.getName();
            premIcon = item.getIconKey();
        } else if (row.getPremiumItemCount() != 0) {
            return null;
        }
        if (!row.getPremiumCosmeticKey().isEmpty()
                && (!PlayerTravelPass.isValidCosmeticKey(row.getPremiumCosmeticKey()) || row.getPremiumCosmeticName().isEmpty())) {
            return null;
        }

        ResolvedTier tier = new ResolvedTier(
                row.getId(), row.getPassId(), tierIndex, reqPoints,
                row.getFreeItemId(), row.getFreeItemCount(), freeName, freeIcon, row.getFreeGold(),
                row.getFreeCosmeticKey(), row.getFreeCosmeticName(),
                row.getPremiumItemId(), row.getPremiumItemCount(), premName, premIcon, row.getPremiumGold(),
                row.getPremiumCosmeticKey(), row.getPremiumCosmeticName());
        if (!tier.hasFreeReward() && !tier.hasPremiumReward()) {
            return null;
        }
        return tier;
    }
}
