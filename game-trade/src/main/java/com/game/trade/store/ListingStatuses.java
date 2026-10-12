package com.game.trade.store;

/**
 * 商品的存储状态 {@code trade_listing.status}（基线 trade_table.proto:15-26 的 ListingStatus；trade-spec §1.4）。
 *
 * <p>Java 版的库表编码自己持有（trade_tables.proto 的 status 是 int32），不引用同步来的契约产物；数值与契约枚举逐值相等，
 * {@code TradeTablesTest} 对拍。公示 / 寄售不是存储状态，而是 LISTED 按时间推导出的展示阶段（ListingRules.phase）。
 *
 * <p>P1 唯一会被写入的状态是 {@link #LISTED}（播种）；但读路径已经按 {@link #LOCKED} 写好（寄售列表含 LOCKED、详情对 LOCKED 可见），
 * Java 原样保留，P3 直接复用（§1.4）。
 */
public final class ListingStatuses {

    public static final int UNSPECIFIED = 0;
    /** P3：托管扣出中。 */
    public static final int ESCROWING = 1;
    /** 已上架：公示或寄售，按 notice_end_ms / sale_end_ms 推导。 */
    public static final int LISTED = 2;
    /** P3：有未完成订单。 */
    public static final int LOCKED = 3;
    /** P3。 */
    public static final int SOLD = 4;
    /** P3：回退交付中。 */
    public static final int RETURNING = 5;
    /** P3。 */
    public static final int RETURNED = 6;
    /** P3。 */
    public static final int ESCROW_REJECTED = 7;

    private ListingStatuses() {
    }
}
