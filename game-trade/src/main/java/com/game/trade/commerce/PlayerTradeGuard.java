package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.INVALID;
import static com.game.trade.commerce.CommerceRules.NOT_TRADABLE;

import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.TradeAssetSelection;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 游戏内当面交易防骗与低价偷渡门禁：
 * <ul>
 *   <li>方案 A（{@code strict_consumables}）：仅放行白名单耗材与单日限额内的银两互助，严禁唯一装备/宠物/大宗材料当面转移；</li>
 *   <li>方案 B（{@code acquaintance_locked}）：除小额耗材互换外，强制校验权威好友时长（默认 7 天）与近 7 日中位参考价偏离保护（拒绝或触发 48 小时冷静锁）。</li>
 * </ul>
 */
public final class PlayerTradeGuard {
    public static final String MODE_STRICT_CONSUMABLES = "strict_consumables";
    public static final String MODE_ACQUAINTANCE_LOCKED = "acquaintance_locked";

    public static final long DEFAULT_MIN_FRIEND_DURATION_MS = 7L * 24 * 3600 * 1000;
    public static final long DEFAULT_COOLING_LOCK_DURATION_MS = 48L * 3600 * 1000;
    public static final int DEFAULT_MAX_REFERENCE_DEVIATION_PCT = 50;
    public static final long DAY_MS = 24L * 3600 * 1000;
    static final int MAX_DAILY_SCAN_ROWS = 1000;

    /** 权威好友关系与持续时长查询结果（绝不信客户端备注）。 */
    public record FriendshipStatus(boolean isFriend, long durationMs) {}

    /** 查询两名角色在权威好友服务中的系统好友关系与持续时长。 */
    @FunctionalInterface
    public interface FriendRelationReader {
        FriendshipStatus friendshipDurationMs(long playerA, long playerB, long nowMs) throws SQLException;
    }

    /** 查询指定角色当日已通过游戏内当面交易流出的指定币种总额，堵死拆单绕限额漏洞。 */
    @FunctionalInterface
    public interface DailyPlayerTradeTracker {
        long dailyCurrencyOutflow(long playerId, int currencyType, long dayStartMs) throws SQLException;

        /** 在已持锁的交易 SQL 事务连接内复用查询，避免开启第二连接及消除 TOCTOU 竞争。 */
        default long dailyCurrencyOutflow(Connection c, long playerId, int currencyType, long dayStartMs)
                throws SQLException {
            return dailyCurrencyOutflow(playerId, currencyType, dayStartMs);
        }
    }

    /** 游戏内当面交易防骗策略参数。 */
    public record Policy(
            String mode,
            Set<Integer> consumableItems,
            long maxDailyCurrencyAmount,
            int referenceCurrencyType,
            long minFriendDurationMs,
            int maxReferenceDeviationPct,
            long coolingLockDurationMs,
            boolean allowAcquaintanceCoolingLock) {

        public Policy {
            mode = mode == null ? "" : mode;
            consumableItems = consumableItems == null ? Set.of() : Set.copyOf(consumableItems);
        }

        public static Policy disabled() {
            return new Policy("", Set.of(), 0L, CurrencyType.CURRENCY_TYPE_GOLD_VALUE, 0L, 0, 0L, false);
        }

        /** 启动与装配期配置校验：模式只接受空、方案 A 或方案 B，偏离阈值必须小于 100。 */
        public void validate() {
            if (mode.isEmpty()) {
                return;
            }
            if (!MODE_STRICT_CONSUMABLES.equals(mode) && !MODE_ACQUAINTANCE_LOCKED.equals(mode)) {
                throw new IllegalArgumentException(
                        "game.trade.commerce.player-trade.mode(" + mode
                                + ") 必须为空、strict_consumables 或 acquaintance_locked");
            }
            if (maxReferenceDeviationPct < 0 || maxReferenceDeviationPct >= 100) {
                throw new IllegalArgumentException(
                        "game.trade.commerce.player-trade.max-reference-deviation-pct("
                                + maxReferenceDeviationPct + ") 必须在 [0, 99] 内");
            }
        }
    }

    /** 当面交易防骗评估结果。 */
    public record EvaluationResult(boolean requireCoolingLock, long coolingLockDurationMs, String reason) {
        static final EvaluationResult PASS = new EvaluationResult(false, 0L, "");
    }

    private final Policy policy;
    private final FriendRelationReader friends;
    private final ReferencePrice.Reader prices;
    private final DailyPlayerTradeTracker dailyOut;

    public PlayerTradeGuard(
            Policy policy,
            FriendRelationReader friends,
            ReferencePrice.Reader prices,
            DailyPlayerTradeTracker dailyOut) {
        this.policy = policy == null ? Policy.disabled() : policy;
        this.friends = friends;
        this.prices = prices;
        this.dailyOut = dailyOut;
    }

    Policy policy() {
        return policy;
    }

    ReferencePrice.Reader prices() {
        return prices;
    }

    DailyPlayerTradeTracker dailyOut() {
        return dailyOut;
    }

    /**
     * 校验当面交易是否符合已启用的防骗策略；Mode 为空时不介入，启用方案 A/B 或遇到未知模式时严格 fail-closed。
     */
    public EvaluationResult evaluate(
            long sellerId,
            long buyerId,
            TradeAssetSelection sellerAssets,
            TradeAssetSelection buyerAssets,
            long nowMs) throws SQLException {
        return evaluate(null, sellerId, buyerId, sellerAssets, buyerAssets, nowMs);
    }

    /** 同 {@link #evaluate(long, long, TradeAssetSelection, TradeAssetSelection, long)}，可在已有 SQL 事务内复用连接校验当日限额。 */
    public EvaluationResult evaluate(
            Connection txConnection,
            long sellerId,
            long buyerId,
            TradeAssetSelection sellerAssets,
            TradeAssetSelection buyerAssets,
            long nowMs) throws SQLException {
        if (policy.mode().isEmpty()) {
            return EvaluationResult.PASS;
        }
        if (sellerId == 0 || buyerId == 0 || sellerId == buyerId) {
            throw new CommerceRules.Refusal(INVALID);
        }
        if (selectionEmpty(sellerAssets) && selectionEmpty(buyerAssets)) {
            throw new CommerceRules.Refusal(INVALID);
        }
        return switch (policy.mode()) {
            case MODE_STRICT_CONSUMABLES ->
                evalStrictConsumables(txConnection, sellerId, buyerId, sellerAssets, buyerAssets, nowMs);
            case MODE_ACQUAINTANCE_LOCKED ->
                evalAcquaintanceLocked(txConnection, sellerId, buyerId, sellerAssets, buyerAssets, nowMs);
            default -> throw new CommerceRules.Refusal(NOT_TRADABLE);
        };
    }

    private EvaluationResult evalStrictConsumables(
            Connection txConnection,
            long sellerId, long buyerId, TradeAssetSelection sellerAssets, TradeAssetSelection buyerAssets, long nowMs)
            throws SQLException {
        if (!isConsumableOnly(sellerAssets) || !isConsumableOnly(buyerAssets)) {
            throw new CommerceRules.Refusal(NOT_TRADABLE);
        }
        checkCurrencyCap(txConnection, sellerId, sellerAssets, nowMs);
        checkCurrencyCap(txConnection, buyerId, buyerAssets, nowMs);
        return new EvaluationResult(false, 0L, "strict_consumables_ok");
    }

    private EvaluationResult evalAcquaintanceLocked(
            Connection txConnection,
            long sellerId, long buyerId, TradeAssetSelection sellerAssets, TradeAssetSelection buyerAssets, long nowMs)
            throws SQLException {
        boolean oneSided = selectionEmpty(sellerAssets) || selectionEmpty(buyerAssets);
        long sellerCurrency = sellerAssets == null ? 0L : sellerAssets.getCurrencyAmount();
        long buyerCurrency = buyerAssets == null ? 0L : buyerAssets.getCurrencyAmount();
        boolean smallConsumableSwap = !oneSided
                && isConsumableOnly(sellerAssets)
                && isConsumableOnly(buyerAssets)
                && (policy.maxDailyCurrencyAmount() == 0
                        || (Long.compareUnsigned(sellerCurrency, policy.maxDailyCurrencyAmount()) <= 0
                                && Long.compareUnsigned(buyerCurrency, policy.maxDailyCurrencyAmount()) <= 0));
        if (smallConsumableSwap && passesCurrencyCapForBoth(txConnection, sellerId, sellerAssets, buyerId, buyerAssets, nowMs)) {
            return new EvaluationResult(false, 0L, "small_consumable_swap_ok");
        }

        // 硬锁 ①：涉及贵重品、超单日小额上限或单方赠送时，必须查系统权威好友且满足最短好友时长（默认 7 天）。
        if (friends == null) {
            throw new CommerceRules.Refusal(NOT_TRADABLE);
        }
        long minFriendMs = policy.minFriendDurationMs() == 0 ? DEFAULT_MIN_FRIEND_DURATION_MS : policy.minFriendDurationMs();
        FriendshipStatus relation = friends.friendshipDurationMs(sellerId, buyerId, nowMs);
        if (relation == null || !relation.isFriend() || Long.compareUnsigned(relation.durationMs(), minFriendMs) < 0) {
            throw new CommerceRules.Refusal(NOT_TRADABLE);
        }

        // 硬锁 ②：近 7 日中位参考价偏离保护（防 0/1 银两偷渡贵重品与高差价逃税）。
        int refCurrency = policy.referenceCurrencyType() == 0
                ? CurrencyType.CURRENCY_TYPE_GOLD_VALUE
                : policy.referenceCurrencyType();
        int maxDevPct = (policy.maxReferenceDeviationPct() <= 0 || policy.maxReferenceDeviationPct() >= 100)
                ? DEFAULT_MAX_REFERENCE_DEVIATION_PCT
                : policy.maxReferenceDeviationPct();
        var sellerVal = ReferencePrice.estimateSelectionValue(prices, sellerAssets, refCurrency, nowMs);
        var buyerVal = ReferencePrice.estimateSelectionValue(prices, buyerAssets, refCurrency, nowMs);

        boolean deviated = oneSided || sellerVal.hasUnpriced() || buyerVal.hasUnpriced();
        if (!deviated) {
            long low = sellerVal.total();
            long high = buyerVal.total();
            if (Long.compareUnsigned(low, high) > 0) {
                long tmp = low;
                low = high;
                high = tmp;
            }
            long keepPct = 100L - maxDevPct;
            if (high == 0) {
                deviated = true;
            } else if (Long.compareUnsigned(high, Long.divideUnsigned(-1L, keepPct)) > 0
                    || Long.compareUnsigned(low, Long.divideUnsigned(-1L, 100L)) > 0) {
                deviated = Long.compareUnsigned(low, Long.divideUnsigned(high, 100L) * keepPct) < 0;
            } else {
                deviated = Long.compareUnsigned(low * 100L, high * keepPct) < 0;
            }
        }

        if (!deviated) {
            return new EvaluationResult(false, 0L, "acquaintance_fair_value_ok");
        }
        if (!policy.allowAcquaintanceCoolingLock()) {
            throw new CommerceRules.Refusal(NOT_TRADABLE);
        }
        long lockMs = policy.coolingLockDurationMs() == 0 ? DEFAULT_COOLING_LOCK_DURATION_MS : policy.coolingLockDurationMs();
        return new EvaluationResult(true, lockMs, "acquaintance_deviation_cooling_lock");
    }

    private boolean passesCurrencyCapForBoth(
            Connection txConnection,
            long sellerId, TradeAssetSelection sellerAssets, long buyerId, TradeAssetSelection buyerAssets, long nowMs)
            throws SQLException {
        try {
            checkCurrencyCap(txConnection, sellerId, sellerAssets, nowMs);
            checkCurrencyCap(txConnection, buyerId, buyerAssets, nowMs);
            return true;
        } catch (CommerceRules.Refusal exceeded) {
            // 超单日小额互助上限时不再视作免检小额，转入熟人硬锁裁决。
            return false;
        }
    }

    private void checkCurrencyCap(Connection txConnection, long playerId, TradeAssetSelection sel, long nowMs) throws SQLException {
        if (sel == null || sel.getCurrencyAmount() == 0) {
            return;
        }
        long cap = policy.maxDailyCurrencyAmount();
        if (cap == 0 || Long.compareUnsigned(sel.getCurrencyAmount(), cap) > 0) {
            throw new CommerceRules.Refusal(NOT_TRADABLE);
        }
        if (dailyOut != null) {
            long dayStart = Long.divideUnsigned(nowMs, DAY_MS) * DAY_MS;
            long used = txConnection != null
                    ? dailyOut.dailyCurrencyOutflow(txConnection, playerId, sel.getCurrencyType(), dayStart)
                    : dailyOut.dailyCurrencyOutflow(playerId, sel.getCurrencyType(), dayStart);
            if (Long.compareUnsigned(used, cap) > 0
                    || Long.compareUnsigned(sel.getCurrencyAmount(), cap - used) > 0) {
                throw new CommerceRules.Refusal(NOT_TRADABLE);
            }
        }
    }

    private boolean isConsumableOnly(TradeAssetSelection v) {
        if (selectionEmpty(v)) {
            return true;
        }
        if (v.getItemUuidsCount() > 0 || v.getPetId() != 0) {
            return false;
        }
        for (int itemId : v.getItemsMap().keySet()) {
            if (!policy.consumableItems().contains(itemId)) {
                return false;
            }
        }
        return true;
    }

    static boolean selectionEmpty(TradeAssetSelection v) {
        return v == null
                || (v.getItemsCount() == 0 && v.getItemUuidsCount() == 0 && v.getPetId() == 0
                        && v.getCurrencyAmount() == 0);
    }

    /** 无符号 64 位饱和加法，溢出时返回 {@code -1L}（即 uint64 最大值）。 */
    static long saturatingAddUnsigned(long a, long b) {
        if (Long.compareUnsigned(a, -1L - b) > 0) {
            return -1L;
        }
        return a + b;
    }

    static long workflowCurrencyOutflow(byte[] rawWorkflow, int currencyType, boolean asSeller) throws SQLException {
        if (rawWorkflow == null || rawWorkflow.length == 0) {
            return 0L;
        }
        TradeCommerceWorkflow w;
        try {
            w = TradeCommerceWorkflow.parseFrom(rawWorkflow);
        } catch (InvalidProtocolBufferException corrupted) {
            throw new SQLException("当面交易单日限额流水 workflow_proto 损坏", corrupted);
        }
        TradeAssetSelection sel = asSeller ? w.getSellerAssets() : w.getBuyerAssets();
        if (sel == null || sel.getCurrencyAmount() == 0 || sel.getCurrencyType() != currencyType) {
            return 0L;
        }
        return sel.getCurrencyAmount();
    }

    /**
     * 直接复读 {@code trade_commerce} 中当日在途与已结案的游戏内当面交易（{@code kind=2}），
     * 按买卖双侧分别累加流出总额；单侧触达 1000 笔上限或遇到坏字节一律 fail-closed 拒放。
     */
    public static final class SqlDailyTracker implements DailyPlayerTradeTracker {
        private static final String SELLER_QUERY =
                "SELECT workflow_proto FROM trade_commerce "
                        + "WHERE seller_player_id=? AND kind=2 AND status IN (1,3,4,5,10,11) AND created_ms>=? "
                        + "ORDER BY commerce_id DESC LIMIT ?";
        private static final String BUYER_QUERY =
                "SELECT workflow_proto FROM trade_commerce "
                        + "WHERE buyer_player_id=? AND kind=2 AND status IN (1,3,4,5,10,11) AND created_ms>=? "
                        + "ORDER BY commerce_id DESC LIMIT ?";

        private final DataSource source;

        public SqlDailyTracker(DataSource source) {
            this.source = Objects.requireNonNull(source, "当面交易单日限额存储未装配");
        }

        @Override
        public long dailyCurrencyOutflow(long playerId, int currencyType, long dayStartMs) throws SQLException {
            if (playerId == 0 || CurrencyType.forNumber(currencyType) == null) {
                throw new CommerceRules.Refusal(INVALID);
            }
            try (Connection c = source.getConnection()) {
                return dailyCurrencyOutflow(c, playerId, currencyType, dayStartMs);
            }
        }

        @Override
        public long dailyCurrencyOutflow(Connection c, long playerId, int currencyType, long dayStartMs)
                throws SQLException {
            if (playerId == 0 || CurrencyType.forNumber(currencyType) == null) {
                throw new CommerceRules.Refusal(INVALID);
            }
            long sellerSum = sumSide(c, SELLER_QUERY, playerId, currencyType, dayStartMs, true);
            long buyerSum = sumSide(c, BUYER_QUERY, playerId, currencyType, dayStartMs, false);
            return saturatingAddUnsigned(sellerSum, buyerSum);
        }

        private static long sumSide(
                Connection c, String sql, long playerId, int currencyType, long dayStartMs, boolean asSeller)
                throws SQLException {
            long total = 0L;
            int scanned = 0;
            try (var stmt = c.prepareStatement(sql)) {
                stmt.setQueryTimeout(2);
                stmt.setObject(1, playerId < 0 ? new BigInteger(Long.toUnsignedString(playerId)) : playerId);
                stmt.setObject(2, dayStartMs < 0 ? new BigInteger(Long.toUnsignedString(dayStartMs)) : dayStartMs);
                stmt.setInt(3, MAX_DAILY_SCAN_ROWS);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        scanned++;
                        long part = workflowCurrencyOutflow(rs.getBytes(1), currencyType, asSeller);
                        total = saturatingAddUnsigned(total, part);
                    }
                }
            }
            if (scanned >= MAX_DAILY_SCAN_ROWS) {
                // 单日单侧当面交易笔数触顶直接饱和拒放，防止刷海量小单绕过累计限额。
                return -1L;
            }
            return total;
        }
    }

    /**
     * 直查权威 {@code friend} 表校验两名角色的双向系统好友边与最短建交时长（对齐 friend_tables.proto 双向边不变式）。
     * 任一侧缺边、{@code since_ms==0} 或时钟回拨均按非好友 / 0 时长处理（fail-closed）。
     */
    public static final class SqlFriendRelationReader implements FriendRelationReader {
        private static final String QUERY =
                "SELECT player_id, since_ms FROM friend "
                        + "WHERE (player_id=? AND friend_player_id=?) OR (player_id=? AND friend_player_id=?)";

        private final DataSource source;

        public SqlFriendRelationReader(DataSource source) {
            this.source = Objects.requireNonNull(source, "好友关系数据源未装配");
        }

        @Override
        public FriendshipStatus friendshipDurationMs(long playerA, long playerB, long nowMs) throws SQLException {
            if (playerA == 0L || playerB == 0L || playerA == playerB) {
                return new FriendshipStatus(false, 0L);
            }
            Object aObj = toUnsignedParam(playerA);
            Object bObj = toUnsignedParam(playerB);
            boolean hasAB = false;
            boolean hasBA = false;
            long sinceAB = 0L;
            long sinceBA = 0L;
            try (Connection c = source.getConnection(); var stmt = c.prepareStatement(QUERY)) {
                stmt.setQueryTimeout(2);
                stmt.setObject(1, aObj);
                stmt.setObject(2, bObj);
                stmt.setObject(3, bObj);
                stmt.setObject(4, aObj);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long pid = readUnsignedLong(rs.getObject(1));
                        long since = readUnsignedLong(rs.getObject(2));
                        if (pid == playerA) {
                            hasAB = true;
                            sinceAB = since;
                        } else if (pid == playerB) {
                            hasBA = true;
                            sinceBA = since;
                        }
                    }
                }
            }
            if (!hasAB || !hasBA) {
                return new FriendshipStatus(false, 0L);
            }
            // 取两侧较晚的建交时刻作为保守口径，防止残缺脏数据抬高好友时长。
            long since = Long.compareUnsigned(sinceAB, sinceBA) >= 0 ? sinceAB : sinceBA;
            if (since == 0L || Long.compareUnsigned(nowMs, since) <= 0) {
                return new FriendshipStatus(true, 0L);
            }
            return new FriendshipStatus(true, nowMs - since);
        }

        private static Object toUnsignedParam(long value) {
            return value < 0L ? new BigInteger(Long.toUnsignedString(value)) : value;
        }

        private static long readUnsignedLong(Object raw) {
            if (raw == null) {
                return 0L;
            }
            if (raw instanceof BigInteger bi) {
                return bi.longValue();
            }
            if (raw instanceof Number n) {
                return n.longValue();
            }
            return Long.parseUnsignedLong(raw.toString().trim());
        }
    }
}
