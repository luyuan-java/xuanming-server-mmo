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
 * 对齐 {@code spirit-shop-and-starter-packs-v1.md §2.3}，非法行 fail-closed 拒下发拒购）。
 */
public final class SpiritShopTables {

    private static final Logger log = LoggerFactory.getLogger(SpiritShopTables.class);

    public static final int MAX_BUY_PER_REQUEST = 99;
    public static final long MAX_GOODS_PRICE = 1_000_000_000L;
    public static final int MAX_GROUP_WEIGHT = 1_000_000;

    public record ItemGrant(int itemConfigId, int itemCount, boolean bound) {
    }

    public record ResolvedGroup(int groupId, String name, int resetKind, int maxWeight) {
    }

    public record ResolvedGoods(int goodsId, int tabId, int sortOrder, String name, String description,
                                String iconKey, String badgeText, int currencyType, long unitPrice,
                                int maxBuyPerRequest, List<ItemGrant> items,
                                int limitResetKind, int limitCount,
                                int limitGroupId, int limitGroupWeight) {
        public boolean allBound() {
            for (ItemGrant g : items) {
                if (!g.bound()) {
                    return false;
                }
            }
            return true;
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

        int itemId1 = row.getItemId1();
        int itemCount1 = row.getItemCount1();
        int itemBound1 = row.getItemBound1();
        ItemTable item1 = itemsById.get(itemId1);
        if (itemId1 == 0 || itemCount1 == 0 || (itemBound1 != 0 && itemBound1 != 1)
                || item1 == null || item1.getMaxStackSize() == 0) {
            log.error("[SpiritShop] invalid item1 goods_id={} item_id_1={} count_1={} bound_1={}",
                    Integer.toUnsignedString(id), itemId1, itemCount1, itemBound1);
            return null;
        }
        List<ItemGrant> grants = new ArrayList<>(2);
        grants.add(new ItemGrant(itemId1, itemCount1, itemBound1 == 1));

        int itemId2 = row.getItemId2();
        if (itemId2 != 0) {
            int itemCount2 = row.getItemCount2();
            int itemBound2 = row.getItemBound2();
            ItemTable item2 = itemsById.get(itemId2);
            if (itemCount2 == 0 || (itemBound2 != 0 && itemBound2 != 1)
                    || item2 == null || item2.getMaxStackSize() == 0
                    || (itemId2 == itemId1 && itemBound2 == itemBound1)) {
                log.error("[SpiritShop] invalid item2 goods_id={} item_id_2={} count_2={} bound_2={}",
                        Integer.toUnsignedString(id), itemId2, itemCount2, itemBound2);
                return null;
            }
            grants.add(new ItemGrant(itemId2, itemCount2, itemBound2 == 1));
        }

        String name = row.getName();
        if (name.isEmpty()) {
            if (grants.size() > 1) {
                log.error("[SpiritShop] multi-item goods missing name goods_id={}", Integer.toUnsignedString(id));
                return null;
            }
            name = item1.getName();
        }
        String iconKey = row.getIconKey().isEmpty() ? item1.getIconKey() : row.getIconKey();
        return new ResolvedGoods(
                id, row.getTabId(), row.getSortOrder(), name, row.getDescription(), iconKey, row.getBadgeText(),
                currencyType, price, maxBuy, List.copyOf(grants),
                resetKind, row.getLimitCount(), groupId, groupWeight);
    }
}
