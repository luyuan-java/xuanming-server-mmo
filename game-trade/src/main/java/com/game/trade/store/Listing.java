package com.game.trade.store;

import java.util.Objects;

/**
 * 一条商品（{@code trade_listing} 的一行；字段与 Java 自有 {@code TradeListingRow} / 基线 TradeListingRecord 的 19 个字段一一对应、
 * 同序，trade_table.proto:38-56）。领域对象，不可变。
 *
 * <p>整数字段一律是位模式：{@code long} 是 uint64（≥ 2^63 时为负数），{@code int} 的 marketZone / sellerZoneAtListing / subcategory / level
 * 是 uint32（≥ 2^31 时为负数）；比较用 {@link Long#compareUnsigned} / {@link Integer#compareUnsigned}（trade-spec §5.9 第 1 条）。
 * {@code category} / {@code status} 是有符号 int（列是 {@code int}），取值见契约 ListingCategory 与 {@link ListingStatuses}。
 *
 * <p>文本字段不为 null：MEDIUMTEXT 列可为 NULL（DDL 没有 NOT NULL），读回时由存储层统一换成 {@code ""}（§1.5、§5.9 第 8 条）。
 * 列表查询（浏览 / 货架）不取 description，此时 {@code description} 为 {@code ""}（listing_repo.go:70-78）。
 */
public record Listing(
        long listingId,
        long sellerPlayerId,
        String sellerAccount,
        int marketZone,
        int sellerZoneAtListing,
        int category,
        int subcategory,
        String title,
        int level,
        long priceFen,
        int status,
        String summary,
        String description,
        String iconKey,
        long noticeEndMs,
        long saleEndMs,
        long createdMs,
        long updatedMs,
        long version) {

    public Listing {
        Objects.requireNonNull(sellerAccount, "sellerAccount");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(iconKey, "iconKey");
    }

    /** 列表形状：去掉 description（浏览 / 货架的查询不取这一列，测试拿全行与列表结果对比时用）。 */
    public Listing withoutDescription() {
        return new Listing(listingId, sellerPlayerId, sellerAccount, marketZone, sellerZoneAtListing, category, subcategory, title,
                level, priceFen, status, summary, "", iconKey, noticeEndMs, saleEndMs, createdMs, updatedMs, version);
    }
}
