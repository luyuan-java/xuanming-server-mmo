package com.game.trade.commerce;

import com.game.proto.trade.TradeAssetSelection;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 服务端真实成交中位参考价（p50）：基于已结案撮合子单剔除同账号对敲与小样本，防止黑商伪造行情或 1 银两偷渡贵重资产。
 */
public final class ReferencePrice {
    /** 默认参考价统计窗口：近 7 天（毫秒）。 */
    public static final long DEFAULT_REFERENCE_WINDOW_MS = 7L * 24 * 3600 * 1000;
    /** 默认最小有效样本数：不足时明确标为样本不足，不返回虚假行情。 */
    public static final int DEFAULT_REFERENCE_MIN_SAMPLES = 10;
    static final int MAX_REFERENCE_SCAN_ROWS = 5000;

    private ReferencePrice() {}

    /** 单笔已结案真实市场成交样本。 */
    public record MarketFillSample(
            int itemConfigId,
            int currencyType,
            long unitPrice,
            int quantity,
            String sellerAccount,
            String buyerAccount,
            long settledAtMs) {}

    /** 指定标准品与币种在近期窗口内的中位成交参考价视图。 */
    public record View(
            int itemConfigId,
            int currencyType,
            long medianUnitPrice,
            int sampleCount,
            boolean insufficientSample) {}

    /** 一侧交易资产选择的参考估值；{@code hasUnpriced=true} 代表包含无法按标准品公允计价的资产（fail-closed）。 */
    public record SelectionValuation(long total, boolean hasUnpriced) {}

    /** 查询指定标准品在指定币种下的近期真实中位成交价。 */
    @FunctionalInterface
    public interface Reader {
        View referencePrice(int itemConfigId, int currencyType, long nowMs) throws SQLException;
    }

    /**
     * 剔除同账号对敲、越界与无效样本后计算成交单价的 p50 中位数；有效样本低于 {@code minSamples} 时置 {@code insufficientSample=true}。
     */
    public static View compute(
            List<MarketFillSample> samples,
            int itemConfigId,
            int currencyType,
            long nowMs,
            long windowMs,
            int minSamples) {
        long effectiveWindow = windowMs <= 0 ? DEFAULT_REFERENCE_WINDOW_MS : windowMs;
        int effectiveMin = minSamples <= 0 ? DEFAULT_REFERENCE_MIN_SAMPLES : minSamples;
        long floorMs = Long.compareUnsigned(nowMs, effectiveWindow) > 0 ? nowMs - effectiveWindow : 0L;
        long[] prices = new long[samples == null ? 0 : samples.size()];
        int count = 0;
        if (samples != null) {
            for (var s : samples) {
                if (s == null || s.itemConfigId() != itemConfigId || s.currencyType() != currencyType) {
                    continue;
                }
                if (s.unitPrice() == 0 || s.quantity() == 0) {
                    continue;
                }
                if (Long.compareUnsigned(s.settledAtMs(), floorMs) < 0
                        || Long.compareUnsigned(s.settledAtMs(), nowMs) > 0) {
                    continue;
                }
                if (s.sellerAccount() != null && !s.sellerAccount().isEmpty()
                        && s.sellerAccount().equals(s.buyerAccount())) {
                    continue;
                }
                prices[count++] = s.unitPrice();
            }
        }
        if (count < effectiveMin) {
            return new View(itemConfigId, currencyType, 0L, count, true);
        }
        long[] valid = Arrays.copyOf(prices, count);
        sortUnsigned(valid);
        long median = (count & 1) == 1
                ? valid[count / 2]
                : valid[count / 2 - 1] + Long.divideUnsigned(valid[count / 2] - valid[count / 2 - 1], 2L);
        return new View(itemConfigId, currencyType, median, count, false);
    }

    /**
     * 按参考价 {@code reader} 估算一侧资产选择在指定币种下的公允参考总额；
     * 含唯一装备实例、宠物、异币种货币或任一物品参考价样本不足时一律返回 {@code hasUnpriced=true}。
     */
    public static SelectionValuation estimateSelectionValue(
            Reader reader, TradeAssetSelection sel, int currencyType, long nowMs) throws SQLException {
        if (sel == null) {
            return new SelectionValuation(0L, false);
        }
        boolean hasUnpriced = sel.getItemUuidsCount() > 0 || sel.getPetId() != 0;
        long total = 0L;
        if (sel.getCurrencyAmount() != 0) {
            if (sel.getCurrencyType() != currencyType) {
                hasUnpriced = true;
            } else {
                total = sel.getCurrencyAmount();
            }
        }
        if (sel.getItemsCount() == 0) {
            return new SelectionValuation(total, hasUnpriced);
        }
        if (reader == null) {
            return new SelectionValuation(total, true);
        }
        for (var entry : sel.getItemsMap().entrySet()) {
            int rawCount = entry.getValue();
            if (rawCount == 0) {
                continue;
            }
            View ref = reader.referencePrice(entry.getKey(), currencyType, nowMs);
            if (ref == null || ref.insufficientSample() || ref.medianUnitPrice() == 0) {
                hasUnpriced = true;
                continue;
            }
            long qty = Integer.toUnsignedLong(rawCount);
            if (Long.compareUnsigned(ref.medianUnitPrice(), Long.divideUnsigned(-1L, qty)) > 0) {
                return new SelectionValuation(-1L, true);
            }
            long part = ref.medianUnitPrice() * qty;
            if (Long.compareUnsigned(total, -1L - part) > 0) {
                return new SelectionValuation(-1L, true);
            }
            total += part;
        }
        return new SelectionValuation(total, hasUnpriced);
    }

    private static void sortUnsigned(long[] values) {
        // 将无符号 64 位整体平移 Long.MIN_VALUE 后复用 JDK 原生 DualPivotQuicksort，零装箱且严格保序。
        for (int i = 0; i < values.length; i++) {
            values[i] ^= Long.MIN_VALUE;
        }
        Arrays.sort(values);
        for (int i = 0; i < values.length; i++) {
            values[i] ^= Long.MIN_VALUE;
        }
    }

    /**
     * 直接复读 {@code trade_commerce} 中已结案的市场撮合子单（{@code kind=4, side=0, status=11}）计算中位参考价，
     * 复用单一成交事实源，不额外引入缓存假行情。
     */
    public static final class SqlReader implements Reader {
        private static final String QUERY =
                "SELECT unit_price,quantity,updated_ms,workflow_proto FROM trade_commerce "
                        + "WHERE kind=4 AND side=0 AND status=? AND item_config_id=? AND currency_type=? "
                        + "AND updated_ms>=? AND updated_ms<=? "
                        + "ORDER BY updated_ms DESC,commerce_id DESC LIMIT ?";

        private final DataSource source;
        private final long windowMs;
        private final int minSamples;

        public SqlReader(DataSource source) {
            this(source, DEFAULT_REFERENCE_WINDOW_MS, DEFAULT_REFERENCE_MIN_SAMPLES);
        }

        public SqlReader(DataSource source, long windowMs, int minSamples) {
            this.source = Objects.requireNonNull(source, "参考价存储未装配");
            this.windowMs = windowMs <= 0 ? DEFAULT_REFERENCE_WINDOW_MS : windowMs;
            this.minSamples = minSamples <= 0 ? DEFAULT_REFERENCE_MIN_SAMPLES : minSamples;
        }

        @Override
        public View referencePrice(int itemConfigId, int currencyType, long nowMs) throws SQLException {
            long floorMs = Long.compareUnsigned(nowMs, windowMs) > 0 ? nowMs - windowMs : 0L;
            var samples = new java.util.ArrayList<MarketFillSample>(64);
            try (Connection c = source.getConnection();
                    var stmt = c.prepareStatement(QUERY)) {
                stmt.setQueryTimeout(2);
                stmt.setInt(1, CommerceRules.SETTLED);
                stmt.setInt(2, itemConfigId);
                stmt.setInt(3, currencyType);
                stmt.setObject(4, floorMs < 0 ? new BigInteger(Long.toUnsignedString(floorMs)) : floorMs);
                stmt.setObject(5, nowMs < 0 ? new BigInteger(Long.toUnsignedString(nowMs)) : nowMs);
                stmt.setInt(6, MAX_REFERENCE_SCAN_ROWS);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long unitPrice = Long.parseUnsignedLong(rs.getString(1));
                        int qty = Integer.parseUnsignedInt(rs.getString(2));
                        long updatedMs = Long.parseUnsignedLong(rs.getString(3));
                        byte[] raw = rs.getBytes(4);
                        String sellerAcct = "";
                        String buyerAcct = "";
                        if (raw != null && raw.length > 0) {
                            try {
                                TradeCommerceWorkflow w = TradeCommerceWorkflow.parseFrom(raw);
                                sellerAcct = w.getSellerIdentity().getAccount();
                                buyerAcct = w.getBuyerIdentity().getAccount();
                            } catch (InvalidProtocolBufferException ignored) {
                                // 单条历史扩展字节解析失败时保留价格样本，不阻断行情统计（与 Go 侧保持一致）。
                            }
                        }
                        samples.add(new MarketFillSample(
                                itemConfigId, currencyType, unitPrice, qty, sellerAcct, buyerAcct, updatedMs));
                    }
                }
            }
            return compute(samples, itemConfigId, currencyType, nowMs, windowMs, minSamples);
        }
    }
}
