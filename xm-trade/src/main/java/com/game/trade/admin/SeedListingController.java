package com.game.trade.admin;

import com.game.common.deadline.Deadline;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.game.trade.TradeProperties;
import com.game.trade.service.SeedListingService;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev 播种接口 {@code POST /admin/trade/seed-listing}（trade-spec §4.8 方案 A、§5.8，T5 / Q2；代替基线 gRPC 直连 {@code TradeAdmin.SeedListing}，
 * admin_logic.go:41-127）。挂在管理端口（缺省 18111，只绑本机），鉴权与每次调用一行审计在 {@link TradeAdminAuthFilter}。
 *
 * <p>请求体是 {@code trade.SeedListingRequest} 的 protobuf 二进制，应答 200 带 {@code SeedListingResponse} 二进制（{@value #CONTENT_TYPE}；
 * 不引入新的 JSON 依赖、uint64 无歧义）。HTTP 状态（§4.8 末尾）：
 * <ol>
 *   <li>过滤器：令牌没配 503、令牌错 401、缺操作人 400；</li>
 *   <li>运行模式不是 dev / test → <b>403</b>（对应基线 PermissionDenied），计 seed rejected，<b>先于解析请求体</b>、不碰库不发号；</li>
 *   <li>请求体超过 {@value #MAX_BODY_BYTES} 字节或不是 SeedListingRequest → 400；</li>
 *   <li>其余（含参数非法 1005、卖家无归属区 20001、存储 / 归属区 / 发号故障 1003）一律 200 + in-band {@code error_message}。</li>
 * </ol>
 * 整请求预算从受理时刻起算（{@code xm.trade.request-budget}，同客户端请求）；在 Tomcat 线程上执行（只在 dev/test 生效、低频，§5.4）。
 * 每次真正执行了播种的调用另记一行运维审计（操作人、卖家、结果），与过滤器那一行互补。
 */
@RestController
public class SeedListingController {

    public static final String PATH = "/admin/trade/seed-listing";
    public static final String CONTENT_TYPE = "application/x-protobuf";
    /** 请求体上限：合法请求的文本字段加起来不超过 3 KiB（64 + 128 + 512 个码点 + 64 字节图标键），留足余量。 */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private static final MediaType PROTOBUF = MediaType.parseMediaType(CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(TradeAdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(SeedListingController.class);

    private final SeedListingService seeds;
    private final long budgetMillis;

    public SeedListingController(SeedListingService seeds, TradeProperties props) {
        this.seeds = seeds;
        this.budgetMillis = props.requestBudget().toMillis();
    }

    @PostMapping(PATH)
    public ResponseEntity<byte[]> seedListing(HttpServletRequest request) throws IOException {
        Deadline deadline = Deadline.after(budgetMillis);
        if (!seeds.admit()) {
            return text(HttpStatus.FORBIDDEN, "SeedListing 只在运行模式 dev / test 下开放（当前 " + seeds.runMode() + "）");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return text(HttpStatus.BAD_REQUEST, "请求体超过 " + MAX_BODY_BYTES + " 字节");
        }
        SeedListingRequest seed;
        try {
            seed = SeedListingRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[trade] 播种请求体不是 SeedListingRequest: {}", e.getMessage());
            return text(HttpStatus.BAD_REQUEST, "请求体不是 trade.SeedListingRequest 的 protobuf 二进制");
        }
        SeedListingResponse out = seeds.seed(seed, deadline);
        audit.info("admin trade seed-listing operator={} seller={} category={} listing_id={} market_zone={} tip={}",
                TradeAdminAuthFilter.operator(request.getHeader(TradeAdminAuthFilter.OPERATOR_HEADER)),
                Long.toUnsignedString(seed.getSellerPlayerId()), seed.getCategoryValue(), Long.toUnsignedString(out.getListingId()),
                Integer.toUnsignedString(out.getMarketZone()), out.getErrorMessage().getId());
        return ResponseEntity.ok().contentType(PROTOBUF).body(out.toByteArray());
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
