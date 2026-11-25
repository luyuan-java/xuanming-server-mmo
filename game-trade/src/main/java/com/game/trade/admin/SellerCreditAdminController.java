package com.game.trade.admin;

import com.game.proto.TipInfoMessage;
import com.game.proto.trade.RecordSellerEnvironmentRequest;
import com.game.proto.trade.RecordSellerEnvironmentResponse;
import com.game.proto.trade.RecordSellerViolationRequest;
import com.game.proto.trade.RecordSellerViolationResponse;
import com.game.trade.commerce.CommerceRules;
import com.game.trade.commerce.SellerCreditService;
import com.game.trade.rules.TradeTips;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卖家信用内部运维接口（对齐 Go {@code TradeAdmin.RecordSellerEnvironment} 496 与 {@code TradeAdmin.RecordSellerViolation} 497）：
 * 挂在管理端口（受 {@link TradeAdminAuthFilter} 保护且二次校验已认证操作人）；
 * 严禁在审计日志或错误消息中打印原始 IP、设备指纹或卖家账号原文。
 */
@RestController
public class SellerCreditAdminController {

    public static final String ENVIRONMENT_PATH = "/admin/trade/seller-environment";
    public static final String VIOLATION_PATH = "/admin/trade/seller-violation";
    public static final String CONTENT_TYPE = SeedListingController.CONTENT_TYPE;
    static final int MAX_BODY_BYTES = SeedListingController.MAX_BODY_BYTES;

    private static final MediaType PROTOBUF = MediaType.parseMediaType(CONTENT_TYPE);
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger audit = LoggerFactory.getLogger(TradeAdminAuthFilter.AUDIT_LOGGER);
    private static final Logger log = LoggerFactory.getLogger(SellerCreditAdminController.class);

    private final SellerCreditService credits;
    private final LongSupplier clock;

    @Autowired
    public SellerCreditAdminController(ObjectProvider<SellerCreditService> credits) {
        this(credits.getIfAvailable(), System::currentTimeMillis);
    }

    public SellerCreditAdminController(SellerCreditService credits, LongSupplier clock) {
        this.credits = credits;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    @PostMapping(ENVIRONMENT_PATH)
    public ResponseEntity<byte[]> recordSellerEnvironment(HttpServletRequest request) throws IOException {
        String operator = TradeAdminAuthFilter.authenticatedOperator(request);
        byte[] body = readBody(request);
        if (body == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体超过 " + MAX_BODY_BYTES + " 字节");
        }
        RecordSellerEnvironmentRequest in;
        try {
            in = RecordSellerEnvironmentRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[trade] 环境信号请求体不是 RecordSellerEnvironmentRequest: {}", e.getMessage());
            return text(HttpStatus.BAD_REQUEST, "请求体不是 trade.RecordSellerEnvironmentRequest 的 protobuf 二进制");
        }
        RecordSellerEnvironmentResponse.Builder out = RecordSellerEnvironmentResponse.newBuilder();
        if (credits == null) {
            out.setErrorMessage(tip(TradeTips.FEATURE_DISABLED));
        } else {
            long observedMs = in.getObservedAtMs() == 0 ? clock.getAsLong() : in.getObservedAtMs();
            try {
                var update = credits.recordSellerEnvironment(new SellerCreditService.EnvironmentEvent(
                        in.getSellerPlayerId(),
                        in.getOwnerAccount().trim(),
                        in.getOwnerRevision(),
                        in.getClientIp().trim(),
                        in.getDeviceId().trim(),
                        observedMs));
                out.setEnvCreditCleared(update.cleared()).setProfile(SellerCreditService.toView(update.profile()));
            } catch (CommerceRules.Refusal denied) {
                out.setErrorMessage(tip(denied.tip()));
            } catch (SQLException | RuntimeException err) {
                log.error("[trade] RecordSellerEnvironment 失败 seller_id={}",
                        Long.toUnsignedString(in.getSellerPlayerId()), err);
                out.setErrorMessage(tip(TradeTips.SERVICE_UNAVAILABLE));
            }
        }
        RecordSellerEnvironmentResponse built = out.build();
        // 严禁记录 client_ip / device_id / owner_account 原文
        audit.info("admin trade seller-environment operator={} seller={} owner_revision={} cleared={} tip={}",
                operator, Long.toUnsignedString(in.getSellerPlayerId()),
                Long.toUnsignedString(in.getOwnerRevision()),
                built.getEnvCreditCleared(), built.getErrorMessage().getId());
        return ResponseEntity.ok().contentType(PROTOBUF).body(built.toByteArray());
    }

    @PostMapping(VIOLATION_PATH)
    public ResponseEntity<byte[]> recordSellerViolation(HttpServletRequest request) throws IOException {
        String operator = TradeAdminAuthFilter.authenticatedOperator(request);
        byte[] body = readBody(request);
        if (body == null) {
            return text(HttpStatus.BAD_REQUEST, "请求体超过 " + MAX_BODY_BYTES + " 字节");
        }
        RecordSellerViolationRequest in;
        try {
            in = RecordSellerViolationRequest.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            log.warn("[trade] 违约记录请求体不是 RecordSellerViolationRequest: {}", e.getMessage());
            return text(HttpStatus.BAD_REQUEST, "请求体不是 trade.RecordSellerViolationRequest 的 protobuf 二进制");
        }
        RecordSellerViolationResponse.Builder out = RecordSellerViolationResponse.newBuilder();
        if (credits == null) {
            out.setErrorMessage(tip(TradeTips.FEATURE_DISABLED));
        } else {
            long recordedMs = in.getRecordedAtMs() == 0 ? clock.getAsLong() : in.getRecordedAtMs();
            try {
                var profile = credits.recordViolation(new SellerCreditService.ViolationRecord(
                        in.getCaseId().trim(),
                        in.getOrderId().trim(),
                        in.getSellerPlayerId(),
                        in.getSellerAccount().trim(),
                        in.getSellerOwnerRevision(),
                        in.getReason().trim(),
                        recordedMs));
                out.setProfile(SellerCreditService.toView(profile));
            } catch (CommerceRules.Refusal denied) {
                out.setErrorMessage(tip(denied.tip()));
            } catch (SQLException | RuntimeException err) {
                log.error("[trade] RecordSellerViolation 失败 seller_id={} case_id={}",
                        Long.toUnsignedString(in.getSellerPlayerId()), in.getCaseId(), err);
                out.setErrorMessage(tip(TradeTips.SERVICE_UNAVAILABLE));
            }
        }
        RecordSellerViolationResponse built = out.build();
        audit.info("admin trade seller-violation operator={} seller={} owner_revision={} case_id={} tip={}",
                operator, Long.toUnsignedString(in.getSellerPlayerId()),
                Long.toUnsignedString(in.getSellerOwnerRevision()),
                TradeAdminAuthFilter.printable(in.getCaseId().trim()), built.getErrorMessage().getId());
        return ResponseEntity.ok().contentType(PROTOBUF).body(built.toByteArray());
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static TipInfoMessage tip(int code) {
        return TipInfoMessage.newBuilder().setId(code).build();
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message.getBytes(StandardCharsets.UTF_8));
    }
}
