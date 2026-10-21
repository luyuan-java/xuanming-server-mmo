package com.game.data.admin;

import com.game.data.mall.MallAdminClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理令牌和操作人沿用数据服务认证；只代理固定商城命令，页面不持有商城签名密钥。 */
@RestController
public final class MallAdminController {
    public static final String PATH = "/admin/mall";
    private final MallAdminClient client;

    public MallAdminController(MallAdminClient client) { this.client = client; }

    @GetMapping(value = "/mall-admin", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> page() throws IOException {
        return asset(MediaType.TEXT_HTML_VALUE)
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                .body(new ClassPathResource("mall-admin.html").getContentAsByteArray());
    }

    @GetMapping(value = "/mall-admin.js", produces = "text/javascript")
    public ResponseEntity<byte[]> script() throws IOException { return resource("mall-admin.js", "text/javascript"); }

    @GetMapping(value = "/mall-admin.css", produces = "text/css")
    public ResponseEntity<byte[]> style() throws IOException { return resource("mall-admin.css", "text/css"); }

    private static ResponseEntity.BodyBuilder asset(String contentType) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(contentType));
    }

    private static ResponseEntity<byte[]> resource(String name, String contentType) throws IOException {
        return asset(contentType).body(new ClassPathResource(name).getContentAsByteArray());
    }

    @PostMapping({PATH + "/skus/list", PATH + "/skus/create", PATH + "/skus/update", PATH + "/skus/stock", PATH + "/order-view"})
    public ResponseEntity<byte[]> execute(HttpServletRequest request) throws IOException {
        // 必须经过本次请求的管理过滤器；公开入口 forward 不能冒充已认证管理调用。
        AdminAuthFilter.authenticatedOperator(request);
        String route = request.getPathInfo() == null ? request.getServletPath() : request.getPathInfo();
        String prefix = PATH + "/";
        if (route == null || !route.startsWith(prefix) || request.getQueryString() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理路径非法");
        }
        byte[] body = request.getInputStream().readNBytes(16_385);
        if (body.length > 16_384) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "管理请求体过大"); }
        var reply = client.execute(route.substring(prefix.length()), body);
        return ResponseEntity.status(reply.status()).header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).header("Cache-Control", "no-store")
                .body(Map.of("error", failure.getReason() == null ? "商城管理请求失败" : failure.getReason()));
    }
}
