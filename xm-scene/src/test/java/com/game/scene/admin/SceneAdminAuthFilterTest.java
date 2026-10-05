package com.game.scene.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.metrics.SceneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * scene 管理端口 {@code /admin/**} 的鉴权（批次 5.3，dungeon-mirror-spec §6.13；语义同 xm-trade 播种）：令牌没配 503、令牌错 401、
 * 缺操作人 400、放行；每次调用计 {@code xm.scene.admin.requests{op, status}}，op 只取已知接口（任意路径不变成标签值）。
 */
class SceneAdminAuthFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 模拟容器：请求头按 ISO-8859-1 解字节（Tomcat 的行为）。 */
    private static String asTomcatHeader(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private int call(String configuredToken, String path, String token, String operator, FilterChain chain)
            throws Exception {
        SceneAdminAuthFilter filter = new SceneAdminAuthFilter(configuredToken, new SceneMetrics(meters));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader(SceneAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(SceneAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    private int call(String configuredToken, String token, String operator, FilterChain chain) throws Exception {
        return call(configuredToken, SceneAdminController.CREATE_PATH, token, operator, chain);
    }

    private double counted(String op, String status) {
        return meters.get("xm.scene.admin.requests").tag("op", op).tag("status", status).counter().count();
    }

    @Test
    void 令牌未配置503_令牌不对401_缺操作人或含控制字符或超长400_都放行不了() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (req, res) -> passed.set(true);

        assertThat(call("", "x", "ops", chain)).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(call(null, "x", "ops", chain)).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(call("secret", null, "ops", chain)).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(call("secret", "wrong", "ops", chain)).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(call("secret", "secret", null, chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "  ", chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "a\u0001b", chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "x".repeat(65), chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(passed).isFalse();

        assertThat(counted(SceneMetrics.ADMIN_OP_INSTANCE_CREATE, "503")).isEqualTo(2);
        assertThat(counted(SceneMetrics.ADMIN_OP_INSTANCE_CREATE, "401")).isEqualTo(2);
        assertThat(counted(SceneMetrics.ADMIN_OP_INSTANCE_CREATE, "400")).isEqualTo(4);
    }

    @Test
    void 令牌与操作人都对_放行_中文操作人按UTF8还原_状态取下游的() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();

        int status = call("secret", SceneAdminController.DESTROY_PATH, "secret", asTomcatHeader("张三"), (req, res) -> {
            passed.set(true);
            ((HttpServletResponse) res).setStatus(403);
        });

        assertThat(status).isEqualTo(403);
        assertThat(passed).isTrue();
        assertThat(counted(SceneMetrics.ADMIN_OP_INSTANCE_DESTROY, "403")).isEqualTo(1);
        assertThat(SceneAdminAuthFilter.operator(asTomcatHeader("张三"))).isEqualTo("张三");
    }

    @Test
    void 未知路径的op记other_不让任意路径变成标签值() throws Exception {
        call("secret", "/admin/scene/anything-" + System.nanoTime(), "secret", "ops", (req, res) -> { });

        assertThat(counted(SceneMetrics.ADMIN_OP_OTHER, "200")).isEqualTo(1);
        assertThat(SceneAdminAuthFilter.opOf(SceneAdminController.CREATE_PATH))
                .isEqualTo(SceneMetrics.ADMIN_OP_INSTANCE_CREATE);
    }

    @Test
    void 下游抛异常_按500计_审计照写_异常照抛() {
        assertThatThrownBy(() -> call("secret", "secret", "ops", (req, res) -> {
            throw new ServletException("炸了");
        })).isInstanceOf(ServletException.class);

        assertThat(counted(SceneMetrics.ADMIN_OP_INSTANCE_CREATE, "500")).isEqualTo(1);
    }

    @Test
    void 审计日志里的控制字符换成问号() {
        assertThat(SceneAdminAuthFilter.printable("/admin/x\ny")).isEqualTo("/admin/x?y");
        assertThat(SceneAdminAuthFilter.printable(null)).isNull();
    }
}
