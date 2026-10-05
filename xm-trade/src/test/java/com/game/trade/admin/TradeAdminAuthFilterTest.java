package com.game.trade.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.trade.metrics.TradeMetrics;
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
 * 播种接口鉴权过滤器（trade-spec §5.8、§9.5；语义同 xm-data AdminAuthFilterTest）：令牌没配 503、令牌错 401、缺操作人 400、放行；
 * 每次调用计 {@code xm.trade.admin.requests{op, status}}，op 只取已知接口。
 */
class TradeAdminAuthFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 模拟容器：请求头按 ISO-8859-1 解字节（Tomcat 的行为）。 */
    private static String asTomcatHeader(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private int call(String configuredToken, String path, String token, String operator, FilterChain chain) throws Exception {
        TradeAdminAuthFilter filter = new TradeAdminAuthFilter(configuredToken, new TradeMetrics(meters));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader(TradeAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(TradeAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    private int call(String configuredToken, String token, String operator, FilterChain chain) throws Exception {
        return call(configuredToken, SeedListingController.PATH, token, operator, chain);
    }

    private double counted(String op, String status) {
        return meters.get("xm.trade.admin.requests").tag("op", op).tag("status", status).counter().count();
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
        assertThat(call("secret", "secret", "ÿþ", chain)).as("不是合法 UTF-8").isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(passed).isFalse();

        assertThat(counted(TradeMetrics.ADMIN_OP_SEED_LISTING, "503")).isEqualTo(2);
        assertThat(counted(TradeMetrics.ADMIN_OP_SEED_LISTING, "401")).isEqualTo(2);
        assertThat(counted(TradeMetrics.ADMIN_OP_SEED_LISTING, "400")).isEqualTo(5);
    }

    @Test
    void 令牌与操作人都对_放行_中文操作人按UTF8还原() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();

        assertThat(call("secret", "secret", asTomcatHeader("张三"), (req, res) -> passed.set(true)))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(passed).isTrue();
        assertThat(TradeAdminAuthFilter.operator(asTomcatHeader("陈晨"))).isEqualTo("陈晨");
        assertThat(TradeAdminAuthFilter.operator("x".repeat(64))).hasSize(64);
        assertThat(counted(TradeMetrics.ADMIN_OP_SEED_LISTING, "200")).isEqualTo(1);
    }

    @Test
    void 处理中抛异常_照样记审计与500指标_异常继续抛出() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("库不可达");
        };

        assertThatThrownBy(() -> call("secret", "secret", "ops", failing)).isInstanceOf(ServletException.class);
        assertThat(counted(TradeMetrics.ADMIN_OP_SEED_LISTING, "500")).isEqualTo(1);
    }

    @Test
    void 指标的op只取已知接口_任意路径归成other() throws Exception {
        call("secret", "/admin/whatever/" + "x".repeat(200), "secret", "ops", (req, res) -> { });

        assertThat(TradeAdminAuthFilter.opOf(SeedListingController.PATH)).isEqualTo(TradeMetrics.ADMIN_OP_SEED_LISTING);
        assertThat(TradeAdminAuthFilter.opOf("/admin/trade/seed-listing/")).isEqualTo(TradeMetrics.ADMIN_OP_OTHER);
        assertThat(counted(TradeMetrics.ADMIN_OP_OTHER, "200")).isEqualTo(1);
        assertThat(meters.get("xm.trade.admin.requests").counters()).as("op 只有两个取值").allMatch(c ->
                c.getId().getTag("op").equals(TradeMetrics.ADMIN_OP_SEED_LISTING) || c.getId().getTag("op").equals(TradeMetrics.ADMIN_OP_OTHER));
    }

    @Test
    void 审计日志里的控制字符换成问号() {
        assertThat(TradeAdminAuthFilter.printable("a\nb\u0000c")).isEqualTo("a?b?c");
        assertThat(TradeAdminAuthFilter.printable(null)).isNull();
    }
}
