package com.game.mall;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 可信网关与支付适配器分别验签；不接受玩家自行声明买家号或付款成功。 */
public final class MallAuthentication extends OncePerRequestFilter {
    static final String SUBJECT = MallAuthentication.class.getName() + ".subject";
    static final String ROLE = MallAuthentication.class.getName() + ".role";
    private static final int MAX_BODY_BYTES = 16_384;
    private static final Map<String, String> INTERNAL_ROLES = Map.ofEntries(
            Map.entry("/internal/payments", "payment"),
            Map.entry("/internal/payments/order-view", "payment"),
            Map.entry("/internal/payments/skus", "payment"),
            Map.entry("/internal/mock/pay", "payment"),
            Map.entry("/internal/mock/reconcile", "payment"),
            Map.entry("/internal/mock/withdraw", "payment"),
            Map.entry("/internal/mock/withdrawals/reconcile", "payment"),
            Map.entry("/internal/consignments/register", "delivery"),
        Map.entry("/internal/consignments/list", "delivery"),
        Map.entry("/internal/consignments/get", "delivery"),
            Map.entry("/internal/consignments/cancel", "delivery"),
            Map.entry("/internal/consignments/buy", "delivery"),
            Map.entry("/internal/consignments/orders/get", "delivery"),
            Map.entry("/internal/consignments/orders/list", "delivery"),
            Map.entry("/internal/consignments/orders/cancel", "delivery"),
            Map.entry("/internal/consignments/deliveries/poll", "delivery"),
            Map.entry("/internal/consignments/returns/poll", "delivery"),
            Map.entry("/internal/consignments/returns/receipt", "delivery"),
            Map.entry("/internal/consignments/incomes", "delivery"),
            Map.entry("/internal/consignments/withdrawals/create", "delivery"),
            Map.entry("/internal/consignments/withdrawals/get", "delivery"),
            Map.entry("/internal/refunds/poll", "payment"),
            Map.entry("/internal/refunds/receipt", "payment"),
            Map.entry("/internal/deliveries/poll", "delivery"),
            Map.entry("/internal/deliveries/receipt", "delivery"),
            Map.entry("/internal/admin/skus/list", "admin"),
            Map.entry("/internal/admin/skus/create", "admin"),
            Map.entry("/internal/admin/skus/update", "admin"),
            Map.entry("/internal/admin/skus/stock", "admin"),
            Map.entry("/internal/admin/order-view", "admin"));
    private final MallProperties properties;
    private final MallNonceStore nonces;

    public MallAuthentication(MallProperties properties, MallNonceStore nonces) {
        this.properties = properties;
        this.nonces = nonces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 独立管理端口的两条只读探针不属于交易 HMAC 域；其余路径继续逐请求验签。
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) && request.getQueryString() == null
                && ("/actuator/health/readiness".equals(path) || "/actuator/health/liveness".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest authenticated;
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new MallException(413, "请求体过大");
            }
            String path = request.getRequestURI();
            if (request.getQueryString() != null || path.contains("%") || path.contains(";") || path.contains("\\")
                    || path.contains("//") || path.contains("..")) {
                throw new MallException(400, "请求路径非法");
            }
            // 业务路径由容器剥离 context 与 servlet 前缀；HMAC 仍绑定完整原始 URI。
            String route = request.getPathInfo() == null ? request.getServletPath() : request.getPathInfo();
            String role = route == null ? null : INTERNAL_ROLES.get(route);
            if (role == null && route != null && route.startsWith("/internal/")) {
                throw new MallException(404, "接口不存在");
            }
            if (role == null) {
                role = "gateway";
            }
            String subject = singleHeader(request, "X-Commerce-Subject");
            if (role.equals("gateway")) {
                requirePlayer(subject);
            } else {
                String expectedSubject = switch (role) {
                    case "delivery" -> "trade";
                    case "admin" -> properties.adminSubject();
                    default -> properties.paymentSubject();
                };
                if (!expectedSubject.equals(subject)) {
                    throw new MallException(401, "调用方认证失败");
                }
            }
            String timestamp = singleHeader(request, "X-Commerce-Timestamp");
            String nonce = singleHeader(request, "X-Commerce-Nonce");
            String signature = singleHeader(request, "X-Commerce-Signature");
            if (!timestamp.matches("[0-9]{1,15}") || !nonce.matches("[A-Za-z0-9_-]{16,128}") || !signature.matches("[0-9a-f]{64}")) {
                throw new MallException(401, "调用方认证失败");
            }
            String secret = switch (role) {
                case "payment" -> properties.paymentSecret();
                case "delivery" -> properties.deliverySecret();
                case "admin" -> properties.adminSecret();
                default -> properties.gatewaySecret();
            };
            if(secret==null||secret.getBytes(StandardCharsets.UTF_8).length<32)throw new MallException(401,"调用方认证失败");
            byte[] expected = HexFormat.of().parseHex(sign(secret, role, subject, timestamp, nonce, request.getMethod(), path, body));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                throw new MallException(401, "调用方认证失败");
            }
            nonces.claim(role, nonce, Long.parseLong(timestamp));
            request.setAttribute(SUBJECT, subject);
            request.setAttribute(ROLE, role);
            authenticated = new BodyRequest(request, body);
        } catch (MallException rejected) {
            response.setStatus(rejected.status());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + rejected.getMessage() + "\"}");
            return;
        } catch (DataAccessException unavailable) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"认证存储暂不可用\"}");
            return;
        }
        chain.doFilter(authenticated, response);
    }

    /** 与另一版 HTTP 适配器保持同一签名格式；命名空间隔离通过独立密钥和数据库实现。 */
    public static String sign(String secret, String role, String subject, String timestamp, String nonce,
                              String method, String path, byte[] body) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "v1", role, subject, timestamp, nonce, method, path, digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("JDK加密能力不可用");
        }
    }

    static void requirePlayer(String subject) {
        try {
            if (subject == null || !subject.matches("[1-9][0-9]{0,19}")
                    || !Long.toUnsignedString(Long.parseUnsignedLong(subject)).equals(subject)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            throw new MallException(401, "调用方认证失败");
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().length() > 256) {
            throw new MallException(401, "调用方认证失败");
        }
        return values.getFirst();
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("只支持同步JSON请求");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
