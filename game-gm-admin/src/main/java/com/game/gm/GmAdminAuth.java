package com.game.gm;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web GM 管理员认证与 RBAC 鉴权（替代 JeecgBoot/Shiro，零外部认证框架依赖）。
 *
 * <p>收紧用户名校验（对齐 PARITY 2026-11-20 审计口径）：仅允许 {@code [A-Za-z0-9._@-]} 且 UTF-8 长度 ≤64，禁止 {@code |} 与控制字符。
 */
public final class GmAdminAuth {

    public static final Set<String> ALL_PERMISSIONS = Set.of(
            "gm:zone:read", "gm:player:read",
            "gm:mail:prepare", "gm:mail:send",
            "gm:system-mail:prepare", "gm:system-mail:send",
            "gm:audit:read",
            "gm:currency:add", "gm:currency:deduct", "gm:currency:block", "gm:currency:unblock",
            "gm:player:level", "gm:pet:grant", "gm:item:grant",
            "gm:battle-script:review");

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    public record Principal(String username, Set<String> permissions) {
        public boolean isPermitted(String permission) {
            return permissions.contains("*") || permissions.contains(permission);
        }

        public boolean isPermittedAny(Collection<String> perms) {
            for (String p : perms) {
                if (isPermitted(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Entry(byte[] tokenBytes, Principal principal) {
    }

    private final List<Entry> entries;

    public GmAdminAuth(Map<String, GmAdminProperties.OperatorEntry> operators) {
        if (operators == null || operators.isEmpty()) {
            this.entries = List.of();
            return;
        }
        this.entries = operators.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null)
                .map(e -> {
                    String username = e.getValue().username() == null ? "" : e.getValue().username().strip();
                    Set<String> perms = e.getValue().permissions() == null
                            ? Set.of() : Set.copyOf(e.getValue().permissions());
                    return new Entry(e.getKey().strip().getBytes(StandardCharsets.UTF_8), new Principal(username, perms));
                })
                .toList();
    }

    public Principal authenticate(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录管理员账号");
        }
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        Principal matched = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.tokenBytes(), presented)) {
                matched = entry.principal();
            }
        }
        if (matched == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员凭证无效");
        }
        validateUsername(matched.username());
        return matched;
    }

    public String require(HttpServletRequest request, String permission) {
        Principal principal = authenticate(request);
        if (!principal.isPermitted(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此 GM 操作权限");
        }
        return principal.username();
    }

    public static void validateUsername(String username) {
        if (username == null || username.isBlank()
                || username.getBytes(StandardCharsets.UTF_8).length > 64
                || username.contains("|")
                || username.codePoints().anyMatch(Character::isISOControl)
                || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员身份无效或名称非法");
        }
    }

    private static String extractToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).strip();
        }
        String header = request.getHeader("X-Gm-Token");
        return header == null ? null : header.strip();
    }
}
