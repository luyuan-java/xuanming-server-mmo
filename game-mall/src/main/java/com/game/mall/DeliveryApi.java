package com.game.mall;

import java.util.List;

/** 内部游戏交付与退款待办契约；付款事实仍只来自渠道认证面，资产证明仅由 trade 提交。 */
public final class DeliveryApi {
    private DeliveryApi() {}

    public record Poll(int limit, String cursor) {
        public Poll(int limit) { this(limit, ""); }
    }

    public record Intent(String orderId, String buyerId, String buyerAccount, String buyerOwnerRevision,
                         long skuId, int quantity, int itemConfigId, int itemCount,
                         String title, long amountMinor, String currency) {}

    /** 页游标属于扫描包装，不进入原 Intent 或游戏合同摘要。 */
    public record Entry(Intent intent, String cursor) {}

    public record PollPage(List<Entry> entries) {}

    /** 退款待办分页请求（/internal/refunds/poll，payment 角色）。 */
    public record RefundPoll(int limit, String cursor) {
        public RefundPoll() { this(100, ""); }
        public RefundPoll(String cursor) { this(100, cursor == null ? "" : cursor); }
    }

    public record RefundPage(List<MallApi.PendingRefund> entries, String nextCursor) {}

    public record OrderPage(String afterOrderId, int limit, String buyerAccount, String buyerOwnerRevision) {}

    public record SkuPage(long afterSkuId, int limit) {}

    public record Receipt(String orderId, String tradeOrderId, String state, String proof) {}

    public record Sku(long skuId, String title, long priceMinor, String currency, long available) {}

    public record Skus(List<Sku> skus) {}

    /** 扣回待办（/internal/reversals/poll，delivery 角色）：渠道撤销或恢复付款后，由 trade 把发放收敛到最新修订号。 */
    public record ReversalEntry(Intent intent, int revision, boolean entitled, String cursor) {}

    public record ReversalPage(List<ReversalEntry> entries) {}

    /** 扣回回执（/internal/reversals/receipt，delivery 角色）：state 取 REVERSED / DELIVERED / MANUAL。 */
    public record ReversalReceipt(String orderId, int revision, String tradeOrderId, String state, String proof) {}
}
