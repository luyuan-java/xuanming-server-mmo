package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.metrics.DataMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthFilterTest {

    private static final String PATH = AuditQueryController.TRANSACTION_LOG_PATH;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 模拟容器：请求头按 ISO-8859-1 解字节（Tomcat 的行为）。 */
    private static String asTomcatHeader(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private int call(String configuredToken, String token, String operator, FilterChain chain) throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(configuredToken, new DataMetrics(meters));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.setServletPath(PATH);
        if (token != null) {
            request.addHeader(AdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(AdminAuthFilter.OPERATOR_HEADER, operator);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    private double counted(String status) {
        return meters.get("xm.data.admin.requests").tag("op", "transaction_log").tag("result", status).counter().count();
    }

    @Test
    void 令牌未配置503_令牌不对401_缺操作人或含控制字符或超长400_都放行不了() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (req, res) -> passed.set(true);

        assertThat(call("", "x", "ops", chain)).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(call("secret", null, "ops", chain)).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(call("secret", "wrong", "ops", chain)).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(call("secret", "secret", null, chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "a\u0001b", chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "x".repeat(65), chain)).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(call("secret", "secret", "ÿþ", chain)).as("不是合法 UTF-8").isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(passed).isFalse();
    }

    @Test
    void 令牌与操作人都对_放行_中文操作人按UTF8还原() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();

        assertThat(call("secret", "secret", asTomcatHeader("张三"), (req, res) -> passed.set(true)))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(passed).isTrue();
        assertThat(AdminAuthFilter.operator(asTomcatHeader("陈晨"))).isEqualTo("陈晨");
        assertThat(counted("200")).isEqualTo(1);
    }

    @Test
    void 处理中抛异常_照样记审计与500指标_异常继续抛出() {
        FilterChain failing = (req, res) -> {
            throw new ServletException("库不可达");
        };

        assertThatThrownBy(() -> call("secret", "secret", "ops", failing)).isInstanceOf(ServletException.class);
        assertThat(counted("500")).isEqualTo(1);
    }

    @Test
    void 指标op只取已知接口的规范路径() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("secret", new DataMetrics(meters));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/whatever-12345");
        request.setServletPath("/admin/whatever-12345");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(meters.get("xm.data.admin.requests").tag("op", "other").counter().count()).isEqualTo(1);
        assertThat(meters.find("xm.data.admin.requests").tag("op", "/admin/whatever-12345").counter()).isNull();
    }

    @Test
    void 指标op标签_已知接口固定值_封禁子路径归一_其余other() {
        assertThat(AdminAuthFilter.opOf("/admin/transaction-log")).isEqualTo("transaction_log");
        assertThat(AdminAuthFilter.opOf("/admin/player-snapshots")).isEqualTo("player_snapshots");
        assertThat(AdminAuthFilter.opOf("/admin/gain-blocks")).isEqualTo("gain_blocks");
        assertThat(AdminAuthFilter.opOf("/admin/gain-blocks/currency/123456")).isEqualTo("gain_blocks");
        assertThat(AdminAuthFilter.opOf("/admin/gain-blocksX")).isEqualTo("other");
        assertThat(AdminAuthFilter.opOf("/admin/zones/3/maintenance")).isEqualTo("zones");
        assertThat(AdminAuthFilter.opOf("/admin/announcements")).isEqualTo("announcements");
        assertThat(AdminAuthFilter.opOf("/admin/whitelist/1/robot_0001")).isEqualTo("whitelist");
        assertThat(AdminAuthFilter.opOf("/admin/zonesX")).isEqualTo("other");
        assertThat(AdminAuthFilter.opOf("/admin/gates/drain/1/3")).isEqualTo("gates");
        assertThat(AdminAuthFilter.opOf("/admin/gatesX")).isEqualTo("other");
        assertThat(AdminAuthFilter.opOf("/admin/killswitch")).isEqualTo("killswitch");
        // 批次 7.2a：带路径参数的运维面接口按前缀归类，路径本身绝不变成标签值
        assertThat(AdminAuthFilter.opOf("/admin/player-snapshots/18446744073709551615")).isEqualTo("player_snapshots");
        assertThat(AdminAuthFilter.opOf("/admin/players/1001/snapshot-diff")).isEqualTo("players");
        assertThat(AdminAuthFilter.opOf("/admin/items/42/trace")).isEqualTo("items");
        assertThat(AdminAuthFilter.opOf("/admin/recalls")).isEqualTo("recalls");
        assertThat(AdminAuthFilter.opOf("/admin/player-snapshotsX")).isEqualTo("other");
        assertThat(AdminAuthFilter.opOf("/admin/playersX/1")).isEqualTo("other");
        // 批次 7.2b：回档、整区维护前快照、作业查询 / 取消
        assertThat(AdminAuthFilter.opOf("/admin/rollbacks")).isEqualTo("rollbacks");
        assertThat(AdminAuthFilter.opOf("/admin/zone-snapshots")).isEqualTo("zone_snapshots");
        assertThat(AdminAuthFilter.opOf("/admin/ops-jobs")).isEqualTo("ops_jobs");
        assertThat(AdminAuthFilter.opOf("/admin/ops-jobs/123/players")).isEqualTo("ops_jobs");
        assertThat(AdminAuthFilter.opOf("/admin/ops-jobs/123/cancel")).isEqualTo("ops_jobs");
        assertThat(AdminAuthFilter.opOf("/admin/rollbacksX")).isEqualTo("other");
        assertThat(AdminAuthFilter.printable("/admin/whitelist/1/a\nforged")).isEqualTo("/admin/whitelist/1/a?forged");
        assertThat(AdminAuthFilter.printable(null)).isNull();
    }
}
