package com.game.scene.merchant;

import com.game.proto.MysteryMerchantShelfEntry;
import com.game.scene.player.Wallet;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.MysteryMerchantGoodsTable;
import com.game.table.MysteryMerchantRuleTable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 神秘商人不可变配表视图（{@code MysteryMerchantRule} / {@code MysteryMerchantGoods} / {@code Item}）。
 */
public final class MysteryMerchantTables {

    public static final int DEFAULT_INTERACT_RANGE = 15;
    public static final int MAX_BUY_COUNT = 99;
    public static final int MIN_DISCOUNT_PERCENT = 1;
    public static final int MAX_DISCOUNT_PERCENT = 100;
    public static final long MAX_GOODS_PRICE = 1_000_000_000L;

    public record ResolvedGoods(int goodsId, int itemConfigId, int itemCount, String name, String description,
                                String iconKey, int currencyType, long originalPrice, long price,
                                int discountPercent, int limitCount) {
    }

    private final int interactRange;
    private final Map<Integer, MysteryMerchantGoodsTable> goodsById;
    private final Map<Integer, ItemTable> itemsById;

    public MysteryMerchantTables(List<MysteryMerchantRuleTable> rules, List<MysteryMerchantGoodsTable> goods,
                                 List<ItemTable> items) {
        int range = DEFAULT_INTERACT_RANGE;
        if (!rules.isEmpty() && rules.getFirst().getInteractRange() > 0) {
            range = rules.getFirst().getInteractRange();
        }
        this.interactRange = range;
        Map<Integer, MysteryMerchantGoodsTable> gMap = new LinkedHashMap<>();
        for (MysteryMerchantGoodsTable row : goods) {
            gMap.putIfAbsent(row.getId(), row);
        }
        this.goodsById = Collections.unmodifiableMap(gMap);
        Map<Integer, ItemTable> iMap = new LinkedHashMap<>();
        for (ItemTable row : items) {
            iMap.putIfAbsent(row.getId(), row);
        }
        this.itemsById = Collections.unmodifiableMap(iMap);
    }

    public static MysteryMerchantTables from(ConfigTables tables) {
        return new MysteryMerchantTables(tables.mysteryMerchantRule().all(),
                tables.mysteryMerchantGoods().all(), tables.item().all());
    }

    public int interactRange() {
        return interactRange;
    }

    public MysteryMerchantGoodsTable goods(int goodsId) {
        return goodsById.get(goodsId);
    }

    /** 向上取整折后价：{@code ceil(price * percent / 100)}。 */
    public static long discountedPrice(long originalPrice, int percent) {
        return (originalPrice * percent + 99L) / 100L;
    }

    /** 解析一条货架记录为当前可售商品；配表不合法返回 null。 */
    public ResolvedGoods resolve(MysteryMerchantShelfEntry entry) {
        if (entry == null) {
            return null;
        }
        int pct = entry.getDiscountPercent();
        if (pct < MIN_DISCOUNT_PERCENT || pct > MAX_DISCOUNT_PERCENT) {
            return null;
        }
        MysteryMerchantGoodsTable row = goodsById.get(entry.getGoodsId());
        if (row == null || row.getItemId() == 0 || row.getItemCount() == 0 || row.getLimitCount() == 0
                || Integer.compareUnsigned(row.getLimitCount(), MAX_BUY_COUNT) > 0) {
            return null;
        }
        int currencyType = row.getCurrencyType();
        // 神秘商人货品入包可交易，严禁绑定灵石/绑定银两购买（防绑定产出洗白）
        if (currencyType != Wallet.GOLD && currencyType != Wallet.DIAMOND) {
            return null;
        }
        long originalPrice = row.getPrice();
        if (originalPrice <= 0 || Long.compareUnsigned(originalPrice, MAX_GOODS_PRICE) > 0) {
            return null;
        }
        ItemTable item = itemsById.get(row.getItemId());
        if (item == null || item.getMaxStackSize() == 0) {
            return null;
        }
        String name = !row.getName().isEmpty() ? row.getName() : item.getName();
        long price = discountedPrice(originalPrice, pct);
        return new ResolvedGoods(row.getId(), row.getItemId(), row.getItemCount(), name,
                item.getDescription(), item.getIconKey(), currencyType, originalPrice, price, pct, row.getLimitCount());
    }
}
