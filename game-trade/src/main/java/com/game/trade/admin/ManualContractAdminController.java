package com.game.trade.admin;

import com.game.common.deadline.Deadline;
import com.game.proto.common.base.ServiceCallAuth;
import com.game.proto.trade.ListManualContractsRequest;
import com.game.proto.trade.ListManualContractsResponse;
import com.game.proto.trade.ManualContractMode;
import com.game.proto.trade.ManualContractVerdict;
import com.game.proto.trade.ResolveManualContractRequest;
import com.game.proto.trade.ResolveManualContractResponse;
import com.game.trade.commerce.ManualContractService;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易转人工合同管理端口（对齐 Go {@code TradeAdmin.ListManualContracts} / {@code ResolveManualContract}）：
 * <ul>
 *   <li>缺省关闭（{@code game.trade.manual-resolution.enabled=false}）；启用时须同时配置非空环境名与独立密钥（≥32 字节）；</li>
 *   <li>专用调用方 {@code ops-commerce} 的 HMAC-SHA256 鉴权（{@link ServiceCallAuth}）与时间窗口校验（±300s），与普通运维令牌隔离；</li>
 *   <li>{@code EXECUTE} 要求携带有效双人复核批准（{@code approval.proposer == operator}、{@code approver != operator}），除非本机显式开启 {@code allow-unapproved}。</li>
 * </ul>
 */
@RestController
public class ManualContractAdminController {

    public static final String LIST_PATH = "/admin/trade/manual/list";
    public static final String RESOLVE_PATH = "/admin/trade/manual/resolve";
    public static final String ALLOWED_CALLER = "ops-commerce";
    public static final String SECRET_ENV = "GAME_TRADE_MANUAL_SECRET";
    static final int MIN_SECRET_BYTES = 32;
    static final long AUTH_SKEW_SECONDS = 300L;

    private static final MediaType PROTOBUF = MediaType.parseMediaType(SeedListingController.CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(ManualContractAdminController.class);
    private static final Logger audit = LoggerFactory.getLogger(TradeAdminAuthFilter.AUDIT_LOGGER);

    private final ManualContractService service;
    private final boolean enabled;
    private final byte[] secret;
    private final boolean allowUnapproved;
    private final java.util.function.LongSupplier clockMs;

    @Autowired
    public ManualContractAdminController(
            ObjectProvider<ManualContractService> service,
            @Value("${game.trade.manual-resolution.enabled:false}") boolean enabled,
            @Value("${game.trade.manual-resolution.environment:}") String environment,
            @Value("${game.trade.manual-resolution.allow-unapproved:false}") boolean allowUnapproved) {
        this(service.getIfAvailable(), enabled, environment, System.getenv(SECRET_ENV), allowUnapproved, System::currentTimeMillis);
    }

    public ManualContractAdminController(ManualContractService service, boolean enabled, String environment,
            String secretValue, boolean allowUnapproved, java.util.function.LongSupplier clockMs) {
        this.service = service;
        byte[] rawSecret = secretValue == null ? new byte[0] : secretValue.getBytes(StandardCharsets.UTF_8);
        boolean envValid = environment != null && !environment.isBlank();
        if (enabled && (rawSecret.length < MIN_SECRET_BYTES || !envValid || service == null)) {
            log.error("trade manual resolution enabled but missing valid >=32B secret or environment; staying closed");
            this.enabled = false;
            this.secret = new byte[0];
        } else {
            this.enabled = enabled;
            this.secret = rawSecret;
        }
        this.allowUnapproved = allowUnapproved;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    @PostMapping(LIST_PATH)
    public ResponseEntity<byte[]> listManual(HttpServletRequest request) throws IOException {
        TradeAdminAuthFilter.authenticatedOperator(request);
        if (!enabled) {
            return text(HttpStatus.FORBIDDEN, "交易人工处置入口未开启");
        }
        byte[] body = readBody(request);
        if (body == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体过大");
        }
        ListManualContractsRequest in;
        try {
            in = ListManualContractsRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException bad) {
            return text(HttpStatus.BAD_REQUEST, "请求体不是 ListManualContractsRequest");
        }
        if (!verifyServiceAuth(in.getAuth(), "trade.TradeAdmin/ListManualContracts",
                in.toBuilder().clearAuth().build())) {
            return text(HttpStatus.FORBIDDEN, "ops-commerce 专用鉴权未通过");
        }
        ListManualContractsResponse out = service.listManual(in);
        return ResponseEntity.ok().contentType(PROTOBUF).body(out.toByteArray());
    }

    @PostMapping(RESOLVE_PATH)
    public ResponseEntity<byte[]> resolveManual(HttpServletRequest request) throws IOException {
        String headerOperator = TradeAdminAuthFilter.authenticatedOperator(request);
        if (!enabled) {
            return text(HttpStatus.FORBIDDEN, "交易人工处置入口未开启");
        }
        byte[] body = readBody(request);
        if (body == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体过大");
        }
        ResolveManualContractRequest in;
        try {
            in = ResolveManualContractRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException bad) {
            return text(HttpStatus.BAD_REQUEST, "请求体不是 ResolveManualContractRequest");
        }
        if (!verifyServiceAuth(in.getAuth(), "trade.TradeAdmin/ResolveManualContract",
                in.toBuilder().clearAuth().build())) {
            return text(HttpStatus.FORBIDDEN, "ops-commerce 专用鉴权未通过");
        }
        if (in.getMode() == ManualContractMode.MANUAL_CONTRACT_MODE_EXECUTE && !allowUnapproved) {
            String refusal = checkDualControl(in);
            if (refusal != null) {
                ResolveManualContractResponse denied = ResolveManualContractResponse.newBuilder()
                        .setVerdict(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_REFUSED)
                        .setTarget(service.environment())
                        .setRefusal(refusal)
                        .build();
                audit.warn("[CommerceManual] mode=EXECUTE target=\"{}\" commerce_id={} operator=\"{}\" verdict=REFUSED refusal=\"{}\"",
                        service.environment(), Long.toUnsignedString(in.getCommerceId()), headerOperator, refusal);
                return ResponseEntity.ok().contentType(PROTOBUF).body(denied.toByteArray());
            }
        }
        ResolveManualContractResponse out = service.resolve(in, Deadline.after(10_000));
        if (!out.getAuditLine().isEmpty()) {
            audit.info("{}", out.getAuditLine());
        }
        return ResponseEntity.ok().contentType(PROTOBUF).body(out.toByteArray());
    }

    private static String checkDualControl(ResolveManualContractRequest in) {
        if (!in.hasApproval()) {
            return "EXECUTE 必须携带双人复核批准记录";
        }
        var approval = in.getApproval();
        if (approval.getApprovalId().isBlank() || approval.getProposer().isBlank()
                || approval.getApprover().isBlank() || approval.getApprovedAtMs() == 0L) {
            return "双人复核批准缺少 approval_id / proposer / approver / approved_at_ms";
        }
        if (!approval.getProposer().equals(in.getOperator())) {
            return "双人复核发起人与 operator 不一致";
        }
        if (approval.getProposer().equals(approval.getApprover())) {
            return "双人复核发起人与批准人不能为同一人";
        }
        return null;
    }

    boolean verifyServiceAuth(ServiceCallAuth auth, String fullMethod, com.google.protobuf.Message payloadWithoutAuth) {
        if (auth == null || !ALLOWED_CALLER.equals(auth.getCaller())) {
            return false;
        }
        long nowMs = clockMs.getAsLong();
        if (Math.abs(nowMs - auth.getTimestampMs()) > AUTH_SKEW_SECONDS * 1000L) {
            return false;
        }
        String expectedHex = signHex(secret, fullMethod, auth.getCaller(), auth.getTimestampMs(), payloadWithoutAuth);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                auth.getSignatureHex().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** 按 {@code service_call_auth.proto} 规范串生成 HMAC-SHA256 小写十六进制签名。 */
    public static String signHex(byte[] secret, String fullMethod, String caller, long timestampMs,
            com.google.protobuf.Message payloadWithoutAuth) {
        try {
            byte[] det = deterministicBytes(payloadWithoutAuth);
            String payloadSha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(det));
            String canonical = "v1\n" + fullMethod + "\n" + caller + "\n"
                    + Long.toUnsignedString(timestampMs) + "\n" + payloadSha;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] deterministicBytes(com.google.protobuf.Message msg) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        com.google.protobuf.CodedOutputStream coded = com.google.protobuf.CodedOutputStream.newInstance(out);
        coded.useDeterministicSerialization();
        msg.writeTo(coded);
        coded.flush();
        return out.toByteArray();
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(SeedListingController.MAX_BODY_BYTES + 1);
            return body.length > SeedListingController.MAX_BODY_BYTES ? null : body;
        }
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
