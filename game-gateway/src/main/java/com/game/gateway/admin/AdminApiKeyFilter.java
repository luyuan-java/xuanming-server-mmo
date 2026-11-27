package com.game.gateway.admin;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 网关管理面（{@code /admin/**}）口令闸（对齐主仓 {@code AdminApiKeyFilter}）：
 * <ul>
 *   <li>同时按容器规范化路径与原始 URI 判定管理路径，先剥离 {@code ;x=1} 路径参数、合并连续斜杠、转小写，杜绝路径参数绕过；</li>
 *   <li>口令按 SHA-256 摘要做常量时间比较，未配置口令时 fail-closed 拒绝全部管理请求；</li>
 *   <li>写操作（POST/PUT/PATCH/DELETE）强制携带唯一合规 {@code X-Admin-Operator} 并记 AUDIT 日志；</li>
 *   <li>可选机器口令（至少 32 字节且不同于人工口令）限定 {@code /admin/tables/**} 且操作人必须为 {@code table-bundle}。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminApiKeyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
    static final String HEADER = "X-Admin-Key";
    static final String OPERATOR_HEADER = "X-Admin-Operator";
    static final String MACHINE_OPERATOR = "table-bundle";
    private static final String ADMIN_PREFIX = "/admin";
    private static final String MACHINE_PREFIX = "/admin/tables";
    private static final Pattern OPERATOR = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final byte[] expectedDigest;
    private final byte[] machineDigest;

    public AdminApiKeyFilter(String apiKey) {
        this(apiKey, "");
    }

    @Autowired
    public AdminApiKeyFilter(
            @Value("${game.gateway.admin.api-key:${GAME_GATEWAY_ADMIN_API_KEY:}}") String apiKey,
            @Value("${game.gateway.admin.machine-key:${GAME_GATEWAY_ADMIN_MACHINE_KEY:}}") String machineKey) {
        this.expectedDigest = (apiKey == null || apiKey.isBlank()) ? null : sha256(apiKey);
        boolean hasMachine = machineKey != null && !machineKey.isBlank();
        if (hasMachine && (machineKey.getBytes(StandardCharsets.UTF_8).length < 32 || machineKey.equals(apiKey))) {
            throw new IllegalStateException("admin machine-key must be at least 32 bytes and differ from admin api-key");
        }
        this.machineDigest = hasMachine ? sha256(machineKey) : null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        if (!isAdminPath(httpReq)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String provided = httpReq.getHeader(HEADER);
        boolean human = matches(expectedDigest, provided);
        boolean machine = !human && matches(machineDigest, provided);
        if (!human && !machine) {
            reject(httpResp, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }
        if (machine && !isUnder(httpReq, MACHINE_PREFIX)) {
            reject(httpResp, HttpServletResponse.SC_FORBIDDEN, "machine key is limited to /admin/tables");
            return;
        }
        boolean write = WRITE_METHODS.contains(httpReq.getMethod().toUpperCase(Locale.ROOT));
        String operator = operator(httpReq);
        if (write && (operator == null || machine != MACHINE_OPERATOR.equals(operator))) {
            reject(httpResp, HttpServletResponse.SC_BAD_REQUEST,
                    machine ? "machine key requests must send X-Admin-Operator: table-bundle"
                            : "write requests need one valid X-Admin-Operator");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (write) {
                log.warn("AUDIT gateway admin write method={} path={} status={} operator={} key={} remote={}",
                        httpReq.getMethod(),
                        normalize((httpReq.getServletPath() == null ? "" : httpReq.getServletPath())
                                + (httpReq.getPathInfo() == null ? "" : httpReq.getPathInfo())),
                        httpResp.getStatus(), operator, machine ? "machine" : "admin", request.getRemoteAddr());
            }
        }
    }

    private static boolean matches(byte[] digest, String provided) {
        return digest != null && provided != null && MessageDigest.isEqual(digest, sha256(provided));
    }

    static String operator(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(OPERATOR_HEADER));
        if (values.size() != 1 || values.getFirst() == null) {
            return null;
        }
        String value = values.getFirst();
        return OPERATOR.matcher(value).matches() ? value : null;
    }

    private static void reject(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    static boolean isUnder(HttpServletRequest request, String prefix) {
        String servletPath = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        for (String path : List.of(normalize(servletPath),
                normalize(request.getRequestURI() == null ? "" : request.getRequestURI()))) {
            if (!(path.equals(prefix) || path.startsWith(prefix + "/")) || path.contains("/..") || path.contains("%")) {
                return false;
            }
        }
        return true;
    }

    static boolean isAdminPath(HttpServletRequest request) {
        String servletPath = (request.getServletPath() == null ? "" : request.getServletPath())
                + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return looksLikeAdmin(servletPath) || looksLikeAdmin(request.getRequestURI());
    }

    static boolean looksLikeAdmin(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String normalized = normalize(path);
        return normalized.equals(ADMIN_PREFIX) || normalized.startsWith(ADMIN_PREFIX + "/");
    }

    private static String normalize(String path) {
        return path.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/").toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
