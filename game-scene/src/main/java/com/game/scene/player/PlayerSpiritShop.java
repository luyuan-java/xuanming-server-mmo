package com.game.scene.player;

import com.game.player.store.state.PlayerSpiritShopState;
import com.game.player.store.state.SpiritShopGoodsPurchaseState;
import com.game.player.store.state.SpiritShopGroupQuotaState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * 官方灵石百宝阁的个人与共享组限购持久运行态（对齐 C++ {@code PlayerSpiritShopComp}，
 * 见 {@code spirit-shop-and-starter-packs-v1.md §2.4}）。
 *
 * <p>容量与唯一性 fail-closed：读到超限（商品 > 512 / 组 > 128）或重复 ID 时标记 {@link #isValid()} 为 false，
 * 业务入口一律拒回 {@code kSpiritShopUnavailable}，绝不静默清空历史限购。
 */
public final class PlayerSpiritShop {

    public static final int MAX_GOODS_RECORDS = 512;
    public static final int MAX_GROUP_RECORDS = 128;

    public static final int RESET_LIFETIME = 0;
    public static final int RESET_WEEKLY = 1;

    private static final long MAX_U32 = 0xFFFF_FFFFL;

    public static final class GoodsRecord {
        private int lifetimeBought;
        private long weekStartsAtMs;
        private int weeklyBought;
        private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

        GoodsRecord() {
        }

        GoodsRecord(int lifetimeBought, long weekStartsAtMs, int weeklyBought, UnknownFieldSet unknownFields) {
            this.lifetimeBought = lifetimeBought;
            this.weekStartsAtMs = weekStartsAtMs;
            this.weeklyBought = weeklyBought;
            this.unknownFields = unknownFields == null ? UnknownFieldSet.getDefaultInstance() : unknownFields;
        }

        public int lifetimeBought() {
            return lifetimeBought;
        }

        public long weekStartsAtMs() {
            return weekStartsAtMs;
        }

        public int weeklyBought() {
            return weeklyBought;
        }
    }

    public static final class GroupRecord {
        private int lifetimeUsed;
        private long weekStartsAtMs;
        private int weeklyUsed;
        private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

        GroupRecord() {
        }

        GroupRecord(int lifetimeUsed, long weekStartsAtMs, int weeklyUsed, UnknownFieldSet unknownFields) {
            this.lifetimeUsed = lifetimeUsed;
            this.weekStartsAtMs = weekStartsAtMs;
            this.weeklyUsed = weeklyUsed;
            this.unknownFields = unknownFields == null ? UnknownFieldSet.getDefaultInstance() : unknownFields;
        }

        public int lifetimeUsed() {
            return lifetimeUsed;
        }

        public long weekStartsAtMs() {
            return weekStartsAtMs;
        }

        public int weeklyUsed() {
            return weeklyUsed;
        }
    }

    private final TreeMap<Integer, GoodsRecord> goodsRecords = new TreeMap<>(Integer::compareUnsigned);
    private final TreeMap<Integer, GroupRecord> groupRecords = new TreeMap<>(Integer::compareUnsigned);
    private long shopRevision;
    private boolean valid = true;
    private PlayerSpiritShopState rawInvalidState;
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerSpiritShop() {
    }

    public static PlayerSpiritShop empty() {
        return new PlayerSpiritShop();
    }

    public static PlayerSpiritShop restore(PlayerSpiritShopState state) {
        PlayerSpiritShop out = new PlayerSpiritShop();
        if (state == null) {
            return out;
        }
        out.shopRevision = state.getShopRevision();
        out.unknownFields = state.getUnknownFields();
        if (state.getGoodsRecordsCount() > MAX_GOODS_RECORDS || state.getGroupRecordsCount() > MAX_GROUP_RECORDS) {
            out.valid = false;
            out.rawInvalidState = state;
            return out;
        }
        for (SpiritShopGoodsPurchaseState r : state.getGoodsRecordsList()) {
            if (r.getGoodsId() == 0 || out.goodsRecords.containsKey(r.getGoodsId())) {
                out.valid = false;
                out.rawInvalidState = state;
                return out;
            }
            out.goodsRecords.put(r.getGoodsId(), new GoodsRecord(
                    r.getLifetimeBought(), r.getWeekStartsAtMs(), r.getWeeklyBought(), r.getUnknownFields()));
        }
        for (SpiritShopGroupQuotaState r : state.getGroupRecordsList()) {
            if (r.getGroupId() == 0 || out.groupRecords.containsKey(r.getGroupId())) {
                out.valid = false;
                out.rawInvalidState = state;
                return out;
            }
            out.groupRecords.put(r.getGroupId(), new GroupRecord(
                    r.getLifetimeUsed(), r.getWeekStartsAtMs(), r.getWeeklyUsed(), r.getUnknownFields()));
        }
        return out;
    }

    public boolean isValid() {
        return valid;
    }

    public long shopRevision() {
        return shopRevision;
    }

    /** 读取单商品在本周期或终身已购份数（周限购遇时钟回拨保守沿用不重置）。 */
    public long boughtCount(int goodsId, int resetKind, long currentWeekStartMs) {
        GoodsRecord r = goodsRecords.get(goodsId);
        if (r == null) {
            return 0L;
        }
        if (resetKind == RESET_LIFETIME) {
            return Integer.toUnsignedLong(r.lifetimeBought);
        }
        if (r.weekStartsAtMs == 0L) {
            return 0L;
        }
        // 时钟回拨（currentWeekStartMs < r.weekStartsAtMs）或同一周均保留原计数，严禁清零。
        if (currentWeekStartMs == 0L || Long.compareUnsigned(currentWeekStartMs, r.weekStartsAtMs) <= 0) {
            return Integer.toUnsignedLong(r.weeklyBought);
        }
        return 0L;
    }

    /** 读取共享组在本周期或终身已用权重（周限购遇时钟回拨保守沿用不重置）。 */
    public long groupUsedWeight(int groupId, int resetKind, long currentWeekStartMs) {
        GroupRecord r = groupRecords.get(groupId);
        if (r == null) {
            return 0L;
        }
        if (resetKind == RESET_LIFETIME) {
            return Integer.toUnsignedLong(r.lifetimeUsed);
        }
        if (r.weekStartsAtMs == 0L) {
            return 0L;
        }
        if (currentWeekStartMs == 0L || Long.compareUnsigned(currentWeekStartMs, r.weekStartsAtMs) <= 0) {
            return Integer.toUnsignedLong(r.weeklyUsed);
        }
        return 0L;
    }

    /** 预检记账是否会触发容量上限或 uint32 溢出（提交前调用，避免部分写入）。 */
    public boolean canRecordPurchase(int goodsId, long count, int goodsResetKind,
                                     int groupId, long addWeight, int groupResetKind, long currentWeekStartMs) {
        if (!valid || goodsId == 0 || count <= 0) {
            return false;
        }
        GoodsRecord gr = goodsRecords.get(goodsId);
        if (gr == null && goodsRecords.size() >= MAX_GOODS_RECORDS) {
            return false;
        }
        long lifetimeAfter = (gr == null ? 0L : Integer.toUnsignedLong(gr.lifetimeBought)) + count;
        if (lifetimeAfter > MAX_U32) {
            return false;
        }
        if (goodsResetKind == RESET_WEEKLY) {
            if (currentWeekStartMs == 0L) {
                return false;
            }
            long weeklyAfter = boughtCount(goodsId, RESET_WEEKLY, currentWeekStartMs) + count;
            if (weeklyAfter > MAX_U32) {
                return false;
            }
        }
        if (groupId != 0) {
            if (addWeight <= 0) {
                return false;
            }
            GroupRecord gpr = groupRecords.get(groupId);
            if (gpr == null && groupRecords.size() >= MAX_GROUP_RECORDS) {
                return false;
            }
            long groupLifetimeAfter = (gpr == null ? 0L : Integer.toUnsignedLong(gpr.lifetimeUsed)) + addWeight;
            if (groupLifetimeAfter > MAX_U32) {
                return false;
            }
            if (groupResetKind == RESET_WEEKLY) {
                if (currentWeekStartMs == 0L) {
                    return false;
                }
                long groupWeeklyAfter = groupUsedWeight(groupId, RESET_WEEKLY, currentWeekStartMs) + addWeight;
                if (groupWeeklyAfter > MAX_U32) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 记录一次成功成交并递增 {@code shop_revision}；调用前必须通过 {@link #canRecordPurchase} 预检。 */
    public void recordPurchase(int goodsId, long count, int goodsResetKind,
                               int groupId, long addWeight, int groupResetKind, long currentWeekStartMs) {
        GoodsRecord gr = goodsRecords.computeIfAbsent(goodsId, k -> new GoodsRecord());
        gr.lifetimeBought = (int) (Integer.toUnsignedLong(gr.lifetimeBought) + count);
        if (goodsResetKind == RESET_WEEKLY) {
            if (gr.weekStartsAtMs == 0L || Long.compareUnsigned(currentWeekStartMs, gr.weekStartsAtMs) > 0) {
                gr.weekStartsAtMs = currentWeekStartMs;
                gr.weeklyBought = (int) count;
            } else {
                gr.weeklyBought = (int) (Integer.toUnsignedLong(gr.weeklyBought) + count);
            }
        }
        if (groupId != 0) {
            GroupRecord gpr = groupRecords.computeIfAbsent(groupId, k -> new GroupRecord());
            gpr.lifetimeUsed = (int) (Integer.toUnsignedLong(gpr.lifetimeUsed) + addWeight);
            if (groupResetKind == RESET_WEEKLY) {
                if (gpr.weekStartsAtMs == 0L || Long.compareUnsigned(currentWeekStartMs, gpr.weekStartsAtMs) > 0) {
                    gpr.weekStartsAtMs = currentWeekStartMs;
                    gpr.weeklyUsed = (int) addWeight;
                } else {
                    gpr.weeklyUsed = (int) (Integer.toUnsignedLong(gpr.weeklyUsed) + addWeight);
                }
            }
        }
        shopRevision++;
    }

    public boolean isPristine() {
        return valid && shopRevision == 0 && goodsRecords.isEmpty() && groupRecords.isEmpty()
                && unknownFields.asMap().isEmpty();
    }

    public PlayerSpiritShopState toState() {
        if (!valid && rawInvalidState != null) {
            return rawInvalidState;
        }
        PlayerSpiritShopState.Builder b = PlayerSpiritShopState.newBuilder()
                .setUnknownFields(unknownFields)
                .setShopRevision(shopRevision);
        for (Map.Entry<Integer, GoodsRecord> e : goodsRecords.entrySet()) {
            GoodsRecord r = e.getValue();
            b.addGoodsRecords(SpiritShopGoodsPurchaseState.newBuilder()
                    .setUnknownFields(r.unknownFields)
                    .setGoodsId(e.getKey())
                    .setLifetimeBought(r.lifetimeBought)
                    .setWeekStartsAtMs(r.weekStartsAtMs)
                    .setWeeklyBought(r.weeklyBought));
        }
        for (Map.Entry<Integer, GroupRecord> e : groupRecords.entrySet()) {
            GroupRecord r = e.getValue();
            b.addGroupRecords(SpiritShopGroupQuotaState.newBuilder()
                    .setUnknownFields(r.unknownFields)
                    .setGroupId(e.getKey())
                    .setLifetimeUsed(r.lifetimeUsed)
                    .setWeekStartsAtMs(r.weekStartsAtMs)
                    .setWeeklyUsed(r.weeklyUsed));
        }
        return b.build();
    }
}
