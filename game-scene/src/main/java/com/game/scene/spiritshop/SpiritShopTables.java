package com.game.scene.spiritshop;

import com.game.scene.player.PlayerSpiritShop;
import com.game.scene.player.Wallet;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.SpiritShopGoodsTable;
import com.game.table.SpiritShopLimitGroupTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 官方灵石百宝阁不可变配表视图（{@code SpiritShopLimitGroup} / {@code SpiritShopGoods} / {@code Item}；
 * 对齐 {@code spirit-shop-and-starter-packs-v1.md §2.3} 与 {@code spirit-shop-v2-festival-and-bundles.md §2.2}，
 * 非法行 fail-closed 拒下发拒购）。
 */
public final class SpiritShopTables {

    private static final Logger log = LoggerFactory.getLogger(SpiritShopTables.class);

    public static final int MAX_BUY_PER_REQUEST = 99;
    public static final long MAX_GOODS_PRICE = 1_000_000_000L;
    public static final int MAX_GROUP_WEIGHT = 1_000_000;
    public static final int GRANT_MODE_ALL = 0;
    public static final int GRANT_MODE_CHOOSE_ONE = 1;
    public static final int MAX_GOODS_ITEMS = 4;
    private static final long MAX_U32 = 0xFFFF_FFFFL;

    public record ItemGrant(int itemConfigId, int itemCount, boolean bound) {
    }

    public record ResolvedGroup(int groupId, String name, int resetKind, int maxWeight) {
    }

    public record ResolvedGoods(int goodsId, int tabId, int sortOrder, String name, String description,
                                String iconKey, String badgeText, int currencyType, long unitPrice,
                                int maxBuyPerRequest, List<ItemGrant> items,
                                int limitResetKind, int limitCount,
                                int limitGroupId, int limitGroupWeight,
                                long startTimeMs, long endTimeMs, int grantMode,
                                long originalPrice, int minLevel, int maxLevel) {
        public boolean allBound() {
            return allBound(items);
        }

        public static boolean allBound(List<ItemGrant> grants) {
            for (ItemGrant g : grants) {
                if (!g.bound()) {
                    return false;
                }
            }
            return true;
        }

        /** 定时上下架判定：左闭右开 [startTimeMs, endTimeMs)；任一端为 0 表示该侧不限。 */
        public boolean isInSaleWindow(long nowMs) {
            if (startTimeMs != 0L && Long.compareUnsigned(nowMs, startTimeMs) < 0) {
                return false;
            }
            if (endTimeMs != 0L && Long.compareUnsigned(nowMs, endTimeMs) >= 0) {
                return false;
            }
            return true;
        }

        /** 角色有效等级阶段解锁判定：闭区间 [minLevel, maxLevel]，任一端为 0 表示该侧不限。 */
        public boolean isLevelEligible(int effectiveLevel) {
            if (minLevel != 0 && Integer.compareUnsigned(effectiveLevel, minLevel) < 0) {
                return false;
            }
            if (maxLevel != 0 && Integer.compareUnsigned(effectiveLevel, maxLevel) > 0) {
                return false;
            }
            return true;
        }

        /** 根据发放模式解析本次实际发入背包的物品列表；下标非法返回 null。 */
        public List<ItemGrant> resolveSelectedItems(int chosenItemIndex) {
            if (items.isEmpty()) {
                return null;
            }
            if (grantMode == GRANT_MODE_ALL) {
                return chosenItemIndex == 0 ? items : null;
            }
            if (grantMode == GRANT_MODE_CHOOSE_ONE) {
                if (items.size() < 2 || chosenItemIndex < 0 || chosenItemIndex >= items.size()) {
                    return null;
                }
                return List.of(items.get(chosenItemIndex));
            }
            return null;
        }
    }

    private final Map<Integer, ResolvedGroup> groupsById;
    private final List<ResolvedGroup> orderedGroups;
    private final Map<Integer, ResolvedGoods> sellableById;
    private final List<ResolvedGoods> orderedSellable;

    public SpiritShopTables(List<SpiritShopLimitGroupTable> groups, List<SpiritShopGoodsTable> goods,
                            List<ItemTable> items) {
        Map<Integer, ItemTable> itemsById = new LinkedHashMap<>();
        for (ItemTable item : items) {
            itemsById.putIfAbsent(item.getId(), item);
        }

        Map<Integer, ResolvedGroup> validGroups = new LinkedHashMap<>();
        for (SpiritShopLimitGroupTable row : groups) {
            int id = row.getId();
            int resetKind = row.getResetKind();
            int maxWeight = row.getMaxWeight();
            if (id == 0 || validGroups.containsKey(id)
                    || (resetKind != PlayerSpiritShop.RESET_LIFETIME && resetKind != PlayerSpiritShop.RESET_WEEKLY)
                    || maxWeight == 0) {
                log.error("[SpiritShop] invalid SpiritShopLimitGroup row id={} reset_kind={} max_weight={}",
                        Integer.toUnsignedString(id), resetKind, Integer.toUnsignedString(maxWeight));
                continue;
            }
            validGroups.put(id, new ResolvedGroup(id, row.getName(), resetKind, maxWeight));
        }
        List<ResolvedGroup> sortedGroups = new ArrayList<>(validGroups.values());
        sortedGroups.sort(Comparator.comparingInt(ResolvedGroup::groupId).thenComparing(ResolvedGroup::name));
        this.groupsById = Collections.unmodifiableMap(validGroups);
        this.orderedGroups = List.copyOf(sortedGroups);

        Map<Integer, ResolvedGoods> validGoods = new LinkedHashMap<>();
        for (SpiritShopGoodsTable row : goods) {
            int enabled = row.getEnabled();
            if (enabled == 0) {
                continue;
            }
            ResolvedGoods resolved = validateGoods(row, enabled, validGroups, itemsById, validGoods);
            if (resolved != null) {
                validGoods.put(resolved.goodsId(), resolved);
            }
        }
        List<ResolvedGoods> sortedGoods = new ArrayList<>(validGoods.values());
        sortedGoods.sort(Comparator
                .comparingInt(ResolvedGoods::tabId)
                .thenComparingInt(ResolvedGoods::sortOrder)
                .thenComparingInt(ResolvedGoods::goodsId));
        this.sellableById = Collections.unmodifiableMap(validGoods);
        this.orderedSellable = List.copyOf(sortedGoods);
    }

    public static SpiritShopTables from(ConfigTables tables) {
        return new SpiritShopTables(
                tables.spiritShopLimitGroup().all(),
                tables.spiritShopGoods().all(),
                tables.item().all());
    }

    public static SpiritShopTables empty() {
        return new SpiritShopTables(List.of(), List.of(), List.of());
    }

    public List<ResolvedGroup> groups() {
        return orderedGroups;
    }

    public ResolvedGroup group(int groupId) {
        return groupsById.get(groupId);
    }

    public List<ResolvedGoods> sellableGoods() {
        return orderedSellable;
    }

    public ResolvedGoods sellableGoods(int goodsId) {
        return sellableById.get(goodsId);
    }

    private static ResolvedGoods validateGoods(SpiritShopGoodsTable row, int enabled,
                                               Map<Integer, ResolvedGroup> groups,
                                               Map<Integer, ItemTable> itemsById,
                                               Map<Integer, ResolvedGoods> seen) {
        int id = row.getId();
        if (enabled != 1 || id == 0 || seen.containsKey(id) || row.getTabId() == 0) {
            log.error("[SpiritShop] invalid SpiritShopGoods header id={} enabled={} tab_id={}",
                    Integer.toUnsignedString(id), enabled, row.getTabId());
            return null;
        }
        int currencyType = row.getCurrencyType();
        if (Integer.compareUnsigned(currencyType, Wallet.TYPE_COUNT) >= 0) {
            log.error("[SpiritShop] invalid currency_type goods_id={} currency_type={}",
                    Integer.toUnsignedString(id), currencyType);
            return null;
        }
        long price = row.getPrice();
        if (price <= 0 || Long.compareUnsigned(price, MAX_GOODS_PRICE) > 0) {
            log.error("[SpiritShop] invalid price goods_id={} price={}",
                    Integer.toUnsignedString(id), Long.toUnsignedString(price));
            return null;
        }
        int maxBuy = row.getMaxBuyPerRequest();
        if (maxBuy == 0 || Integer.compareUnsigned(maxBuy, MAX_BUY_PER_REQUEST) > 0) {
            log.error("[SpiritShop] invalid max_buy_per_request goods_id={} max_buy={}",
                    Integer.toUnsignedString(id), maxBuy);
            return null;
        }
        int resetKind = row.getLimitResetKind();
        if (resetKind != PlayerSpiritShop.RESET_LIFETIME && resetKind != PlayerSpiritShop.RESET_WEEKLY) {
            log.error("[SpiritShop] invalid limit_reset_kind goods_id={} reset_kind={}",
                    Integer.toUnsignedString(id), resetKind);
            return null;
        }
        long startTimeMs = row.getStartTimeMs();
        long endTimeMs = row.getEndTimeMs();
        if (startTimeMs != 0L && endTimeMs != 0L && Long.compareUnsigned(endTimeMs, startTimeMs) <= 0) {
            log.error("[SpiritShop] invalid sale window goods_id={} start={} end={}",
                    Integer.toUnsignedString(id), Long.toUnsignedString(startTimeMs), Long.toUnsignedString(endTimeMs));
            return null;
        }
        int grantMode = row.getGrantMode();
        if (grantMode != GRANT_MODE_ALL && grantMode != GRANT_MODE_CHOOSE_ONE) {
            log.error("[SpiritShop] invalid grant_mode goods_id={} grant_mode={}",
                    Integer.toUnsignedString(id), grantMode);
            return null;
        }
        long originalPrice = row.getOriginalPrice();
        if (originalPrice != 0L && (Long.compareUnsigned(originalPrice, price) <= 0
                || Long.compareUnsigned(originalPrice, MAX_GOODS_PRICE) > 0)) {
            log.error("[SpiritShop] invalid original_price goods_id={} price={} original_price={}",
                    Integer.toUnsignedString(id), Long.toUnsignedString(price), Long.toUnsignedString(originalPrice));
            return null;
        }
        int minLevel = row.getMinLevel();
        int maxLevel = row.getMaxLevel();
        if (minLevel != 0 && maxLevel != 0 && Integer.compareUnsigned(maxLevel, minLevel) < 0) {
            log.error("[SpiritShop] invalid level range goods_id={} min_level={} max_level={}",
                    Integer.toUnsignedString(id), Integer.toUnsignedString(minLevel), Integer.toUnsignedString(maxLevel));
            return null;
        }
        int groupId = row.getLimitGroupId();
        int groupWeight = row.getLimitGroupWeight();
        if (groupId != 0) {
            if (!groups.containsKey(groupId) || groupWeight == 0
                    || Integer.compareUnsigned(groupWeight, MAX_GROUP_WEIGHT) > 0) {
                log.error("[SpiritShop] invalid limit_group goods_id={} group_id={} weight={}",
                        Integer.toUnsignedString(id), groupId, groupWeight);
                return null;
            }
        } else if (groupWeight != 0) {
            log.error("[SpiritShop] non-zero limit_group_weight without group goods_id={} weight={}",
                    Integer.toUnsignedString(id), groupWeight);
            return null;
        }

        int[][] rawSlots = new int[][]{
                {row.getItemId1(), row.getItemCount1(), row.getItemBound1()},
                {row.getItemId2(), row.getItemCount2(), row.getItemBound2()},
                {row.getItemId3(), row.getItemCount3(), row.getItemBound3()},
                {row.getItemId4(), row.getItemCount4(), row.getItemBound4()},
        };
        List<ItemGrant> grants = new ArrayList<>(MAX_GOODS_ITEMS);
        ItemTable firstItem = null;
        boolean seenEmpty = false;
        long maxBuyLong = Integer.toUnsignedLong(maxBuy);
        for (int i = 0; i < rawSlots.length; i++) {
            int itemId = rawSlots[i][0];
            int itemCount = rawSlots[i][1];
            int itemBound = rawSlots[i][2];
            if (itemBound != 0 && itemBound != 1) {
                log.error("[SpiritShop] invalid item_bound goods_id={} slot={} bound={}",
                        Integer.toUnsignedString(id), i + 1, itemBound);
                return null;
            }
            if (itemId == 0) {
                if (itemCount != 0 || itemBound != 0) {
                    log.error("[SpiritShop] empty item slot has non-zero count or bound goods_id={} slot={}",
                            Integer.toUnsignedString(id), i + 1);
                    return null;
                }
                seenEmpty = true;
                continue;
            }
            if (seenEmpty) {
                log.error("[SpiritShop] item slots must not contain gaps goods_id={} slot={}",
                        Integer.toUnsignedString(id), i + 1);
                return null;
            }
            ItemTable item = itemsById.get(itemId);
            if (itemCount == 0 || item == null || item.getMaxStackSize() == 0
                    || Integer.toUnsignedLong(itemCount) * maxBuyLong > MAX_U32) {
                log.error("[SpiritShop] invalid item goods_id={} slot={} item_id={} count={}",
                        Integer.toUnsignedString(id), i + 1, itemId, itemCount);
                return null;
            }
            boolean bound = itemBound == 1;
            for (ItemGrant existing : grants) {
                if (grantMode == GRANT_MODE_ALL) {
                    if (existing.itemConfigId() == itemId && existing.bound() == bound) {
                        log.error("[SpiritShop] duplicate (item_id, bound) in ALL bundle goods_id={} slot={} item_id={}",
                                Integer.toUnsignedString(id), i + 1, itemId);
                        return null;
                    }
                } else {
                    if (existing.itemConfigId() == itemId && existing.itemCount() == itemCount
                            && existing.bound() == bound) {
                        log.error("[SpiritShop] duplicate choice in CHOOSE_ONE bundle goods_id={} slot={} item_id={}",
                                Integer.toUnsignedString(id), i + 1, itemId);
                        return null;
                    }
                }
            }
            if (firstItem == null) {
                firstItem = item;
            }
            grants.add(new ItemGrant(itemId, itemCount, bound));
        }
        if (firstItem == null || grants.isEmpty()) {
            log.error("[SpiritShop] missing item_id_1 goods_id={}", Integer.toUnsignedString(id));
            return null;
        }
        if (grantMode == GRANT_MODE_CHOOSE_ONE && grants.size() < 2) {
            log.error("[SpiritShop] CHOOSE_ONE goods requires at least 2 items goods_id={} count={}",
                    Integer.toUnsignedString(id), grants.size());
            return null;
        }

        String name = row.getName();
        if (name.isEmpty()) {
            if (grants.size() > 1) {
                log.error("[SpiritShop] multi-item goods missing name goods_id={}", Integer.toUnsignedString(id));
                return null;
            }
            name = firstItem.getName();
        }
        String iconKey = row.getIconKey().isEmpty() ? firstItem.getIconKey() : row.getIconKey();
        return new ResolvedGoods(
                id, row.getTabId(), row.getSortOrder(), name, row.getDescription(), iconKey, row.getBadgeText(),
                currencyType, price, maxBuy, List.copyOf(grants),
                resetKind, row.getLimitCount(), groupId, groupWeight,
                startTimeMs, endTimeMs, grantMode, originalPrice, minLevel, maxLevel);
    }
}
