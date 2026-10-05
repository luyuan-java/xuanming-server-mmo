package com.game.trade.metrics;

import com.game.trade.dispatch.TradeMethods;
import com.game.trade.store.JdbcListingStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * xm-trade 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；trade-spec §6.2，T11）。指标名与标签只在这里定义。
 *
 * <ul>
 *   <li>{@code xm_trade_requests_seconds{method, result}} ← grpcstats + serverbase 的 {@code rpc_duration_seconds} / {@code rpc_inband_*}：
 *       {@code method} 只取 {@link TradeMethods#CLIENT_REQUESTS} 的 4 个方法、{@link TradeMethods#SEED_LISTING}（199 上行，只会计 forbidden /
 *       bad_request / unauthenticated）与 {@link #UNROUTED}；</li>
 *   <li>{@code xm_trade_home_zone_lookups_total{result=ok|unmapped|error}} ← {@code trade_home_zone_lookup_total}（home_zone.go:46-61）；</li>
 *   <li>{@code xm_trade_seed_listings_total{result=ok|rejected|error}} ← {@code trade_seed_listing_total}（admin_logic.go:57、:66-77、:123）；</li>
 *   <li>{@code xm_trade_favorite_retries_total}（Java 增项）：收藏写入撞 1213 / 1205 / 9007 整条重跑的次数；</li>
 *   <li>{@code xm_trade_admin_requests_total{op, status}}（Java 增项）：播种接口的 HTTP 审计，op 只取已知接口；
 *       工作线程池 {@code executor_*{name="trade-worker"}} 由池自己绑定。</li>
 * </ul>
 *
 * <p>全部可能出现的标签组合启动时预建为 0（基线 StartMetrics，servicecontext.go:377-386：不预建的话「从没出错」与「指标不存在」分不开，
 * {@code rate(...) > 0} 的告警既不报警也不报错）。<b>标签基数</b>（AGENTS.md §5）：不以 player_id / listing_id / zone 作标签。线程安全、不抛异常。
 */
public final class TradeMetrics implements JdbcListingStore.Events {

    static final String REQUESTS = "xm.trade.requests";
    static final String HOME_ZONE_LOOKUPS = "xm.trade.home.zone.lookups";
    static final String SEED_LISTINGS = "xm.trade.seed.listings";
    static final String FAVORITE_RETRIES = "xm.trade.favorite.retries";
    static final String ADMIN_REQUESTS = "xm.trade.admin.requests";

    /** 不认识的消息号（契约里没有，或有但不归 trade）。 */
    public static final String UNROUTED = "unrouted";

    /** 播种接口的 op 标签（{@code /admin/trade/seed-listing}）；其余路径一律 {@link #ADMIN_OP_OTHER}。 */
    public static final String ADMIN_OP_SEED_LISTING = "seed_listing";
    public static final String ADMIN_OP_OTHER = "other";

    /** 预建的 HTTP 状态（过滤器的 503 / 401 / 400，控制器的 403 / 400 / 200，处理中抛异常的 500）；别的状态首次出现时再建（状态码本身有界）。 */
    static final List<String> ADMIN_STATUSES = List.of("200", "400", "401", "403", "500", "503");

    /** 与 gate / login / friend / guild 同一套 SLO 桶（5ms～10s）。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求的结果（{@code xm.trade.requests{result}}，trade-spec §5.3）。 */
    public enum RequestResult {
        /** 应答体没有 error_message（成功）。 */
        OK,
        /** 应答体是非故障的 tip（in-band 业务拒绝：1005 / 20000–20003）。 */
        BUSINESS_ERROR,
        /** 应答体是 in-band 1003：存储 / 归属区故障、范围配置非法、预算到期、处理器异常。 */
        INTERNAL_ERROR,
        /** 工作队列满，或请求在队列里等过了预算（in-band 1003，不带原因串；T7）。 */
        OVERLOADED,
        /** 请求体解析失败（含 proto3 非法 UTF-8；信封 1003）。 */
        BAD_REQUEST,
        /** 会话没有绑定玩家（player_id = 0；信封 1003，T6）。 */
        UNAUTHENTICATED,
        /** 上行 199 TradeAdmin.SeedListing（信封 1003；基线 PermissionDenied）。 */
        FORBIDDEN,
        /** 不认识的号（信封 1013 / 1006）。 */
        UNSUPPORTED
    }

    /** 一次归属区查询的结局（home_zone.go:46-61）。 */
    public enum LookupResult { OK, UNMAPPED, ERROR }

    /** 一次播种调用的结局（admin_logic.go：非 dev/test、参数非法、卖家无归属区 → rejected；归属区 / 发号 / 插入故障 → error）。 */
    public enum SeedResult { OK, REJECTED, ERROR }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> requests = new ConcurrentHashMap<>();
    private final Map<LookupResult, Counter> lookups = new EnumMap<>(LookupResult.class);
    private final Map<SeedResult, Counter> seeds = new EnumMap<>(SeedResult.class);
    private final Counter favoriteRetries;
    private final ConcurrentHashMap<String, Counter> adminRequests = new ConcurrentHashMap<>();

    public TradeMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 请求：只预建可能出现的组合（4 个 C2S 进工作线程池；199 只会被拒；不认识的号只会 unsupported）
        for (String method : TradeMethods.CLIENT_REQUESTS) {
            for (RequestResult result : List.of(RequestResult.OK, RequestResult.BUSINESS_ERROR, RequestResult.INTERNAL_ERROR,
                    RequestResult.OVERLOADED, RequestResult.BAD_REQUEST, RequestResult.UNAUTHENTICATED)) {
                timer(method, result);
            }
        }
        for (RequestResult result : List.of(RequestResult.FORBIDDEN, RequestResult.BAD_REQUEST, RequestResult.UNAUTHENTICATED)) {
            timer(TradeMethods.SEED_LISTING, result);
        }
        timer(UNROUTED, RequestResult.UNSUPPORTED);

        for (LookupResult result : LookupResult.values()) {
            lookups.put(result, Counter.builder(HOME_ZONE_LOOKUPS)
                    .description("归属区查询（xm_java.player.zone_id）：ok = 查到，unmapped = 缺行或 zone_id 为 0（回 20001），error = 读失败或预算用完（回 1003）")
                    .tag("result", tagValue(result)).register(registry));
        }
        for (SeedResult result : SeedResult.values()) {
            seeds.put(result, Counter.builder(SEED_LISTINGS)
                    .description("dev 播种 POST /admin/trade/seed-listing：rejected = 非 dev/test、参数非法或卖家无归属区，error = 归属区 / 发号 / 插入故障")
                    .tag("result", tagValue(result)).register(registry));
        }
        this.favoriteRetries = Counter.builder(FAVORITE_RETRIES)
                .description("收藏写入撞 1213 / 1205 / 9007 后整条重跑的次数（ODKU 之后只剩 InnoDB 固有情形，长期非 0 需要排查）")
                .register(registry);
        for (String op : List.of(ADMIN_OP_SEED_LISTING, ADMIN_OP_OTHER)) {
            for (String status : ADMIN_STATUSES) {
                adminCounter(op, status);
            }
        }
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /** 记一次客户端请求的耗时与结果（从受理到应答，含工作队列排队）。 */
    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        sample.stop(timer(method, result));
    }

    public void homeZoneLookup(LookupResult result) {
        lookups.get(result).increment();
    }

    public void seedListing(SeedResult result) {
        seeds.get(result).increment();
    }

    @Override
    public void favoriteRetry() {
        favoriteRetries.increment();
    }

    /** 管理端口 /admin/** 的一次调用（op 由调用方从已知路径映射，不得传任意路径）。 */
    public void adminRequest(String op, int status) {
        adminCounter(ADMIN_OP_SEED_LISTING.equals(op) ? ADMIN_OP_SEED_LISTING : ADMIN_OP_OTHER, Integer.toString(status)).increment();
    }

    private Timer timer(String method, RequestResult result) {
        String key = method + '\0' + result.name();
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("trade 处理客户端请求的耗时与结果（从受理到应答，含工作队列排队）")
                    .tag("method", method).tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        return timer;
    }

    private Counter adminCounter(String op, String status) {
        String key = op + '\0' + status;
        Counter counter = adminRequests.get(key);
        if (counter == null) {
            counter = adminRequests.computeIfAbsent(key, k -> Counter.builder(ADMIN_REQUESTS)
                    .description("管理端口 /admin/** 的调用（含鉴权失败），按 HTTP 状态计")
                    .tag("op", op).tag("status", status).register(registry));
        }
        return counter;
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
