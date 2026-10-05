package com.game.trade.service;

import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.common.player.HomeZones;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.game.trade.dispatch.TradeMethods;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.metrics.TradeMetrics.SeedResult;
import com.game.trade.rules.ListingRules;
import com.game.trade.rules.TradeTip;
import com.game.trade.service.HomeZoneResolver.Resolution;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import com.game.trade.store.ListingStore;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * dev 播种：造一条已上架商品，只用于本地联调与 robot 冒烟，不移动任何资产（基线 AdminLogic.SeedListing，admin_logic.go:41-127；trade-spec §3.6、§5.8）。
 * Java 的入口是管理端口 {@code POST /admin/trade/seed-listing}（T5；控制器在 {@code admin} 包），不是 Dubbo / 客户端消息 199。
 *
 * <p>执行顺序（照基线）：
 * <ol>
 *   <li>运行模式闸门 {@link #admit()}：不是 dev / test（{@link RunMode#allowsGmCommands()}，T12：认 development / local / testing 等别名）→ 拒绝、
 *       计 rejected、零 I/O、不消耗号（:55-60）。放在方法里而不是「只在 dev 注册接口」：接口总注册，非 dev/test 回 403（对应基线 PermissionDenied）；</li>
 *   <li>{@link ListingRules#validSeedRequest} 不过 → in-band 1005，计 rejected，零 I/O（:66-68）；</li>
 *   <li>查卖家归属区：未映射 → 20001（计 rejected）；故障 → 1003（计 error）（:71-78）。{@code market_zone} 不来自请求（proto 里刻意没有该字段，
 *       trade_admin.proto:22）：与正式上架同一条推导路径；</li>
 *   <li>发号：租约无效、时钟回拨或拿到 0 → 1003（计 error）（:80-93）；</li>
 *   <li>按 §1.2 组装行（{@code seller_account = ""}、{@code status = LISTED}、{@code notice_end = now + notice}、{@code sale_end = notice_end + sale}、
 *       {@code created = updated = now}、{@code version = 0}）并插入；失败 → 1003（计 error）（:95-121）；</li>
 *   <li>成功：计 ok，INFO 日志，回 {@code {listing_id, market_zone}}（:123-126）。</li>
 * </ol>
 * <b>不幂等</b>：每次调用都发新号（调用方按 nonce 标题与 listing_id 找自己的商品）。业务结果一律 in-band（只设 error_message，tip 不带 parameters）。
 *
 * <p>阻塞（JDBC），在播种接口的 Tomcat 线程上执行（只在 dev/test 生效、低频；trade-spec §5.4），受调用方给的整请求预算约束。线程安全。
 */
public final class SeedListingService {

    private static final Logger log = LoggerFactory.getLogger(SeedListingService.class);

    private final ListingStore store;
    private final HomeZoneResolver homeZones;
    private final LongSupplier listingIds;
    private final TradeMetrics metrics;
    private final RunMode runMode;
    private final LongSupplier clockMs;

    /**
     * @param listingIds 发号（生产 {@code ListingIds::nextId}：租约无效 / 时钟回拨抛 IllegalStateException）
     * @param runMode    进程运行模式（{@code xm.run-mode}）
     * @param clockMs    服务端时间（Unix 毫秒）
     */
    public SeedListingService(ListingStore store, HomeZones homeZones, LongSupplier listingIds, TradeMetrics metrics,
                              RunMode runMode, LongSupplier clockMs) {
        this.store = store;
        this.homeZones = new HomeZoneResolver(homeZones, metrics);
        this.listingIds = listingIds;
        this.metrics = metrics;
        this.runMode = runMode;
        this.clockMs = clockMs;
    }

    /** 播种是否开放（运行模式 dev / test）。 */
    public boolean enabled() {
        return runMode.allowsGmCommands();
    }

    public RunMode runMode() {
        return runMode;
    }

    /**
     * 运行模式闸门（admin_logic.go:55-60）：开放时返回 true；否则计 rejected、打 ERROR 并返回 false（调用方回 403，不解析请求体、不碰任何依赖）。
     */
    public boolean admit() {
        if (enabled()) {
            return true;
        }
        metrics.seedListing(SeedResult.REJECTED);
        log.error("[trade] 拒绝 SeedListing：运行模式 {} 不是 dev / test", runMode);
        return false;
    }

    /**
     * 造一条商品（第 2–6 步；调用方须已经 {@link #admit()} 通过）。
     *
     * @throws IllegalStateException 运行模式不开放播种（第二道防线：调用方漏了 {@link #admit()} 也不会写库、不消耗号）
     */
    public SeedListingResponse seed(SeedListingRequest in, Deadline deadline) {
        final String method = TradeMethods.SEED_LISTING;
        if (!enabled()) {
            throw new IllegalStateException("运行模式 " + runMode + " 不开放播种（调用方应先 admit）");
        }
        if (!ListingRules.validSeedRequest(in)) {
            return reject(TradeTip.INVALID_SEED_REQUEST, SeedResult.REJECTED);
        }
        long seller = in.getSellerPlayerId();

        Resolution zone = homeZones.resolve(seller, deadline, method, TradeTip.SELLER_HOME_ZONE_UNKNOWN);
        if (zone.rejected()) {
            return reject(zone.reject(), zone.reject().fault() ? SeedResult.ERROR : SeedResult.REJECTED);
        }
        int homeZone = zone.zone();

        long listingId;
        try {
            listingId = listingIds.getAsLong();
        } catch (RuntimeException e) {
            log.error("[trade] {}: 领 listing_id 失败 seller={}", method, Long.toUnsignedString(seller), e);
            return reject(TradeTip.LISTING_ID_UNAVAILABLE, SeedResult.ERROR);
        }
        if (listingId == 0) {
            // 发号器保证不返回 0；真出现就是发号源 bug，绝不能写进主键（:89-93）
            log.error("[trade] {}: 发号返回了 listing_id=0 seller={}", method, Long.toUnsignedString(seller));
            return reject(TradeTip.LISTING_ID_UNAVAILABLE, SeedResult.ERROR);
        }

        long nowMs = Math.max(0, clockMs.getAsLong());
        long noticeEndMs = nowMs + in.getNoticeDurationMs();
        Listing row = new Listing(
                listingId,
                seller,
                "",                         // seller_account：P3 判「同账号不能自买」时才需要（:100；N6 不采纳）
                homeZone,                   // market_zone
                homeZone,                   // seller_zone_at_listing（合服不改，审计用）
                in.getCategoryValue(),
                in.getSubcategory(),
                in.getTitle(),              // 原值（不 trim）
                in.getLevel(),
                in.getPriceFen(),
                ListingStatuses.LISTED,     // P1 唯一会被写入的状态
                in.getSummary(),
                in.getDescription(),
                in.getIconKey(),
                noticeEndMs,                // 等于上架时刻 = 无公示期
                noticeEndMs + in.getSaleDurationMs(),
                nowMs,
                nowMs,
                0);                         // version：P3 起做状态迁移 CAS
        try {
            store.insertListing(row, deadline);
        } catch (RuntimeException e) {
            log.error("[trade] {}: InsertListing 失败 listing_id={} seller={}", method, Long.toUnsignedString(listingId),
                    Long.toUnsignedString(seller), e);
            return reject(TradeTip.STORE_FAULT, SeedResult.ERROR);
        }

        metrics.seedListing(SeedResult.OK);
        log.info("[trade] {} 成功 listing_id={} seller={} market_zone={} category={} notice_end_ms={} sale_end_ms={}", method,
                Long.toUnsignedString(listingId), Long.toUnsignedString(seller), Integer.toUnsignedString(homeZone),
                row.category(), Long.toUnsignedString(row.noticeEndMs()), Long.toUnsignedString(row.saleEndMs()));
        return SeedListingResponse.newBuilder().setListingId(listingId).setMarketZone(homeZone).build();
    }

    private SeedListingResponse reject(TradeTip tip, SeedResult result) {
        metrics.seedListing(result);
        return SeedListingResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }
}
