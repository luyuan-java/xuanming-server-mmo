package com.game.match.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.RunMode;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.AdminOp;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * dev / test 管理口的鉴权（match-spec §9.10）：令牌没配 503、令牌错 401、缺 / 坏操作人 400、运行模式不是 dev / test 时 dev 口 403、都过了才放行；
 * 判定次序固定（令牌是第一道闸：没带对令牌的人在 prod 看到的是 401 而不是 403）；每次调用一行审计日志、一次计数（op 只取已知接口）。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchAdminAuthFilterTest {

    private static final String RATING = "/admin/match/dev/rating/1001";
    private static final String ACTIVITY = "/admin/match/dev/activity-battle";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));

    private MatchAdminAuthFilter filter(String token, RunMode runMode) {
        return new MatchAdminAuthFilter(token, runMode, metrics);
    }

    private static MockHttpServletRequest request(String method, String path, String token, String operator) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (token != null) {
            request.addHeader(MatchAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.addHeader(MatchAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        return request;
    }

    private static int run(MatchAdminAuthFilter filter, MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    private double admin(String op, int status) {
        return meters.get("xm.match.admin.requests").tag("op", op).tag("status", Integer.toString(status)).counter().count();
    }

    // ================================================================ 四道闸

    @Test
    void 令牌没配_一律503_哪怕请求什么都带对了() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        assertThat(run(filter(null, RunMode.DEV), request("GET", RATING, "t", "op"), chain)).isEqualTo(503);
        assertThat(run(filter("", RunMode.TEST), request("GET", RATING, "", "op"), new MockFilterChain())).as("空令牌配空头也不行").isEqualTo(503);
        assertThat(chain.getRequest()).as("没有进到控制器").isNull();
        assertThat(filter("", RunMode.DEV).tokenConfigured()).isFalse();
        assertThat(filter("tok", RunMode.DEV).tokenConfigured()).isTrue();
        assertThat(admin("rating", 503)).isEqualTo(2);
    }

    @Test
    void 令牌错或没带401_缺操作人400_dev模式下都对了才放行() throws Exception {
        MatchAdminAuthFilter filter = filter("tok", RunMode.DEV);

        assertThat(run(filter, request("GET", RATING, null, "op"), new MockFilterChain())).isEqualTo(401);
        assertThat(run(filter, request("GET", RATING, "TOK", "op"), new MockFilterChain())).as("区分大小写").isEqualTo(401);
        assertThat(run(filter, request("GET", RATING, "tok ", "op"), new MockFilterChain())).isEqualTo(401);
        assertThat(run(filter, request("GET", RATING, "tok", null), new MockFilterChain())).isEqualTo(400);
        assertThat(run(filter, request("GET", RATING, "tok", "   "), new MockFilterChain())).isEqualTo(400);
        MockFilterChain chain = new MockFilterChain();
        assertThat(run(filter, request("GET", RATING, "tok", "op"), chain)).isEqualTo(200);
        assertThat(chain.getRequest()).as("放行到控制器").isNotNull();
        assertThat(admin("rating", 401)).isEqualTo(3);
        assertThat(admin("rating", 400)).isEqualTo(2);
        assertThat(admin("rating", 200)).isEqualTo(1);
    }

    @Test
    void 运行模式不是dev或test_dev口一律403_test模式放行() throws Exception {
        MockFilterChain prodChain = new MockFilterChain();

        assertThat(run(filter("tok", RunMode.PROD), request("GET", RATING, "tok", "op"), prodChain)).isEqualTo(403);
        assertThat(run(filter("tok", RunMode.PROD), request("POST", ACTIVITY, "tok", "op"), new MockFilterChain())).isEqualTo(403);
        assertThat(prodChain.getRequest()).isNull();
        assertThat(filter("tok", RunMode.PROD).devEndpointsOpen()).isFalse();

        MockFilterChain testChain = new MockFilterChain();
        assertThat(run(filter("tok", RunMode.TEST), request("POST", ACTIVITY, "tok", "op"), testChain)).isEqualTo(200);
        assertThat(testChain.getRequest()).isNotNull();
        assertThat(filter("tok", RunMode.TEST).devEndpointsOpen()).isTrue();
        assertThat(admin("rating", 403)).isEqualTo(1);
        assertThat(admin("activity_battle", 403)).isEqualTo(1);
        assertThat(admin("activity_battle", 200)).isEqualTo(1);
    }

    @Test
    void 判定次序_令牌先于操作人先于运行模式_prod下没带对令牌的人看不出dev口存在() throws Exception {
        MatchAdminAuthFilter prod = filter("tok", RunMode.PROD);

        assertThat(run(prod, request("GET", RATING, "wrong", null), new MockFilterChain())).as("令牌错 + 缺操作人 + prod → 401").isEqualTo(401);
        assertThat(run(prod, request("GET", RATING, "tok", null), new MockFilterChain())).as("令牌对 + 缺操作人 + prod → 400").isEqualTo(400);
        assertThat(run(prod, request("GET", RATING, "tok", "op"), new MockFilterChain())).isEqualTo(403);
        assertThat(run(filter(null, RunMode.PROD), request("GET", RATING, "tok", "op"), new MockFilterChain())).as("令牌没配压过一切").isEqualTo(503);
    }

    @Test
    void 运行模式的闸只管dev前缀之下的路径_按规范路径判() throws Exception {
        MatchAdminAuthFilter prod = filter("tok", RunMode.PROD);
        MockFilterChain chain = new MockFilterChain();

        assertThat(run(prod, request("GET", "/admin/match/other", "tok", "op"), chain)).as("不在 dev 前缀下：放行（没有控制器就是 404，那是后面的事）").isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(run(prod, request("GET", "/admin/match/dev/anything/else", "tok", "op"), new MockFilterChain())).isEqualTo(403);
        assertThat(MatchAdminAuthFilter.isDevPath("/admin/match/dev/rating/1")).isTrue();
        assertThat(MatchAdminAuthFilter.isDevPath("/admin/match/dev")).as("前缀带结尾的斜杠").isFalse();
        assertThat(MatchAdminAuthFilter.isDevPath("/admin/match/developer")).isFalse();
        // 原始 URI 里夹了 ;参数：容器给的规范路径才是判定依据
        MockHttpServletRequest tricky = request("GET", "/admin/match/dev;x=1/rating/1", "tok", "op");
        tricky.setServletPath("/admin/match/dev/rating/1");
        assertThat(run(prod, tricky, new MockFilterChain())).isEqualTo(403);
    }

    // ================================================================ 审计与指标

    @Test
    void 每次调用一行审计日志_带操作人方法规范路径与状态_被拒的也记(CapturedOutput output) throws Exception {
        MatchAdminAuthFilter filter = filter("tok", RunMode.DEV);
        String chinese = new String("运维小王".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        run(filter, request("GET", RATING, "tok", chinese), new MockFilterChain());
        run(filter, request("POST", ACTIVITY, "bad", "op"), new MockFilterChain());
        run(filter, request("GET", RATING, "tok", "a\nb"), new MockFilterChain());

        assertThat(output.getOut())
                .contains("admin operator=运维小王 method=GET path=/admin/match/dev/rating/1001 query=null remote=127.0.0.1 status=200")
                .contains("admin operator=op method=POST path=/admin/match/dev/activity-battle query=null remote=127.0.0.1 status=401")
                .contains("admin operator=<invalid> method=GET path=/admin/match/dev/rating/1001 query=null remote=127.0.0.1 status=400");
    }

    @Test
    void 控制器抛异常_按500记审计与指标_异常照常往外抛(CapturedOutput output) {
        MatchAdminAuthFilter filter = filter("tok", RunMode.DEV);
        FilterChain failing = (request, response) -> {
            throw new ServletException("控制器炸了");
        };

        assertThatThrownBy(() -> run(filter, request("GET", RATING, "tok", "op"), failing)).isInstanceOf(ServletException.class);

        assertThat(output.getOut()).contains("path=/admin/match/dev/rating/1001").contains("status=500");
        assertThat(admin("rating", 500)).isEqualTo(1);
    }

    @Test
    void 指标的op标签只取已知接口_其余一律other_任意路径不会变成标签值() throws Exception {
        assertThat(MatchAdminAuthFilter.opOf("/admin/match/dev/rating/1001")).isEqualTo(AdminOp.RATING);
        assertThat(MatchAdminAuthFilter.opOf("/admin/match/dev/rating/")).isEqualTo(AdminOp.RATING);
        assertThat(MatchAdminAuthFilter.opOf("/admin/match/dev/rating")).as("不带玩家号的不是那个接口").isEqualTo(AdminOp.OTHER);
        assertThat(MatchAdminAuthFilter.opOf("/admin/match/dev/activity-battle")).isEqualTo(AdminOp.ACTIVITY_BATTLE);
        assertThat(MatchAdminAuthFilter.opOf("/admin/match/dev/activity-battle/x")).isEqualTo(AdminOp.OTHER);
        assertThat(MatchAdminAuthFilter.opOf("/admin/whatever/" + "x".repeat(200))).isEqualTo(AdminOp.OTHER);

        run(filter("tok", RunMode.DEV), request("GET", "/admin/zzz/" + System.nanoTime(), "nope", "op"), new MockFilterChain());
        assertThat(admin("other", 401)).isEqualTo(1);
        assertThat(meters.find("xm.match.admin.requests").counters()).as("标签值是有界的：op ∈ {rating, activity_battle, other}")
                .allSatisfy(counter -> assertThat(counter.getId().getTag("op")).isIn("rating", "activity_battle", "other"));
    }

    // ================================================================ 操作人与日志净化

    @Test
    void 操作人按UTF8还原_拒控制字符_超长_空白_非法UTF8() {
        String chinese = new String("运维小王".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        assertThat(MatchAdminAuthFilter.operator(chinese)).isEqualTo("运维小王");
        assertThat(MatchAdminAuthFilter.operator("alice")).isEqualTo("alice");
        assertThat(MatchAdminAuthFilter.operator(null)).isNull();
        assertThat(MatchAdminAuthFilter.operator("a\nb")).isNull();
        assertThat(MatchAdminAuthFilter.operator("x".repeat(65))).isNull();
        assertThat(MatchAdminAuthFilter.operator("x".repeat(64))).hasSize(64);
        assertThat(MatchAdminAuthFilter.operator("   ")).isNull();
        assertThat(MatchAdminAuthFilter.operator(new String(new byte[] {(byte) 0xC3, (byte) 0x28}, StandardCharsets.ISO_8859_1)))
                .as("不是合法的 UTF-8").isNull();
        assertThat(MatchAdminAuthFilter.printable("a\nb\rc")).isEqualTo("a?b?c");
        assertThat(MatchAdminAuthFilter.printable(null)).isNull();
    }

    @Test
    void 头名与令牌环境变量名同全仓其它管理口() {
        assertThat(MatchAdminAuthFilter.TOKEN_ENV).isEqualTo("XM_ADMIN_TOKEN");
        assertThat(MatchAdminAuthFilter.TOKEN_HEADER).isEqualTo("X-Xm-Admin-Token");
        assertThat(MatchAdminAuthFilter.OPERATOR_HEADER).isEqualTo("X-Xm-Operator");
        assertThat(MatchAdminAuthFilter.AUDIT_LOGGER).isEqualTo("xm.audit.admin");
        assertThat(MatchAdminAuthFilter.DEV_PREFIX).isEqualTo("/admin/match/dev/");
    }
}
