package com.game.mall;
import java.util.List;
/** 内部游戏交付契约；付款事实仍只来自渠道认证面，资产证明仅由trade提交。 */
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
    public record OrderPage(String afterOrderId, int limit, String buyerAccount, String buyerOwnerRevision) {}
    public record SkuPage(long afterSkuId, int limit) {}
    public record Receipt(String orderId, String tradeOrderId, String state, String proof) {}
    public record Sku(long skuId, String title, long priceMinor, String currency, long available) {}
    public record Skus(List<Sku> skus) {}
}
