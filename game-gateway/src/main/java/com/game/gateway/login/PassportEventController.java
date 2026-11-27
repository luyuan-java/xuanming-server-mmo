package com.game.gateway.login;

import com.game.api.AccountLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方通行证（Casdoor）回调入口 {@code POST /internal/passport/events}（对齐主仓 {@code PassportEventController}）：
 * <ul>
 *   <li>未配 {@code game.gateway.passport.webhook-secret}（或 {@code passport.webhook-secret}）时恒回 404；已配但不足 32 字节拒启；</li>
 *   <li>请求头 {@value #SECRET_HEADER} 按 SHA-256 摘要常量时间比较，不符回 401；</li>
 *   <li>鉴权通过后按 {@value #MAX_BODY_BYTES} 字节上限读取载荷，空或超限回 400；</li>
 *   <li>转发 {@link AccountLoginService#handlePassportEvent}：&ge; 0 回 200，-1 回 400，-2 或调用异常回 503（Casdoor 重投）。</li>
 * </ul>
 */
@RestController
public class PassportEventController {

    private static final Logger log = LoggerFactory.getLogger(PassportEventController.class);
    public static final String SECRET_HEADER = "X-Passport-Webhook-Secret";
    public static final int MAX_BODY_BYTES = 64 * 1024;
    static final int MIN_SECRET_BYTES = 32;

    private final AccountLoginService login;
    private final byte[] secretDigest;

    public PassportEventController(AccountLoginService login,
                                   @Value("${game.gateway.passport.webhook-secret:${passport.webhook-secret:}}") String secret) {
        boolean enabled = secret != null && !secret.isBlank();
        if (enabled && secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("passport webhook-secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.login = login;
        this.secretDigest = enabled ? sha256(secret) : null;
    }

    @PostMapping("/internal/passport/events")
    public CompletableFuture<ResponseEntity<Void>> handle(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            HttpServletRequest request) throws IOException {
        if (secretDigest == null) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        if (secret == null || !MessageDigest.isEqual(secretDigest, sha256(secret))) {
            log.warn("passport webhook rejected: missing or wrong {}", SECRET_HEADER);
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        long declaredBytes = request.getContentLengthLong();
        byte[] body = declaredBytes > MAX_BODY_BYTES ? null : request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            log.warn("passport webhook rejected: body size {}", body == null ? declaredBytes : body.length);
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }
        CompletableFuture<Integer> call;
        try {
            call = login.handlePassportEvent(body);
        } catch (RuntimeException e) {
            log.warn("passport webhook dispatch failed: {}", e.getClass().getSimpleName());
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return call.handle((checked, failure) -> {
            if (failure != null || checked == null || checked <= -2) {
                log.warn("passport webhook temporarily unavailable, passport will redeliver");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<Void>build();
            }
            if (checked == -1) {
                log.warn("passport webhook rejected by login: not a Casdoor record");
                return ResponseEntity.badRequest().<Void>build();
            }
            log.info("passport webhook handled checked_users={}", checked);
            return ResponseEntity.ok().<Void>build();
        });
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
