package com.game.login.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.login.metrics.LoginMetrics.AssignResult;
import com.game.login.metrics.LoginMetrics.EnterRoute;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4 在 login 指标里加的东西（zone-travel-spec §7.2）：{@code xm_login_enter_routes_total{route}}（七个去向全量预建）、
 * {@code xm_login_ticket_holder_rejected_total}、登录期重定向签票的调用耗时（{@code xm_login_backend_calls{method="redirectToZone"}}）。
 * 固化导出名与标签：改名或换 Micrometer 版本时这里先红。
 */
class LoginMetricsTest {

    private static final String[] ROUTES = {"first", "resume", "awaiting", "pinned", "redirect", "redirect_fallback",
            "location_error"};

    @Test
    void 去向计数_七个标签启动即注册_导出名与标签_发射不新增序列() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            LoginMetrics metrics = new LoginMetrics(prometheus);
            String before = prometheus.scrape();
            for (String route : ROUTES) {
                assertThat(before).contains("xm_login_enter_routes_total{route=\"" + route + "\"} 0");
            }
            assertThat(series(before, "xm_login_enter_routes_total")).as("七个，不多不少").isEqualTo(ROUTES.length);
            assertThat(EnterRoute.values()).hasSize(ROUTES.length);

            metrics.enterRouted(EnterRoute.AWAITING);
            metrics.enterRouted(EnterRoute.REDIRECT);
            metrics.enterRouted(EnterRoute.REDIRECT);
            metrics.enterRouted(EnterRoute.REDIRECT_FALLBACK);

            String after = prometheus.scrape();
            assertThat(after).contains(
                    "xm_login_enter_routes_total{route=\"awaiting\"} 1",
                    "xm_login_enter_routes_total{route=\"redirect\"} 2",
                    "xm_login_enter_routes_total{route=\"redirect_fallback\"} 1",
                    "xm_login_enter_routes_total{route=\"first\"} 0");
            assertThat(series(after, "xm_login_enter_routes_total")).as("发射不新增时间序列").isEqualTo(ROUTES.length);
            assertThat(labelNames(after, "xm_login_enter_routes_total")).as("不带 player / session / zone")
                    .containsExactly("route");
        } finally {
            prometheus.close();
        }
    }

    @Test
    void 持票者不符计数_启动即注册_没有标签() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            LoginMetrics metrics = new LoginMetrics(prometheus);
            assertThat(prometheus.scrape()).contains("xm_login_ticket_holder_rejected_total 0");

            metrics.ticketHolderRejected();
            metrics.ticketHolderRejected();

            String after = prometheus.scrape();
            assertThat(after).contains("xm_login_ticket_holder_rejected_total 2");
            assertThat(labelNames(after, "xm_login_ticket_holder_rejected_total")).isEmpty();
        } finally {
            prometheus.close();
        }
    }

    @Test
    void 签票调用耗时_与分配同一个指标_method标签区分_三种结果启动即注册() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            LoginMetrics metrics = new LoginMetrics(prometheus);
            String before = prometheus.scrape();
            for (String method : new String[]{"assign", "redirectToZone"}) {
                for (String result : new String[]{"ok", "rejected", "error"}) {
                    assertThat(before).contains("xm_login_backend_calls_seconds_count{backend=\"scene-manager\",method=\""
                            + method + "\",result=\"" + result + "\"} 0");
                }
            }

            metrics.sceneRedirectCompleted(AssignResult.OK, TimeUnit.MILLISECONDS.toNanos(30));
            metrics.sceneRedirectCompleted(AssignResult.REJECTED, TimeUnit.MILLISECONDS.toNanos(5));

            String after = prometheus.scrape();
            assertThat(after).contains(
                    "xm_login_backend_calls_seconds_count{backend=\"scene-manager\",method=\"redirectToZone\",result=\"ok\"} 1",
                    "xm_login_backend_calls_seconds_count{backend=\"scene-manager\",method=\"redirectToZone\",result=\"rejected\"} 1",
                    "xm_login_backend_calls_seconds_count{backend=\"scene-manager\",method=\"assign\",result=\"ok\"} 0");
        } finally {
            prometheus.close();
        }
    }

    /** 某个指标在导出文本里的样本行数（不含 # 注释行）。 */
    private static long series(String scrape, String name) {
        return scrape.lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " ")).count();
    }

    /** 某个指标的样本行上出现过的全部标签名。 */
    private static Set<String> labelNames(String scrape, String name) {
        Set<String> names = new TreeSet<>();
        Pattern label = Pattern.compile("([a-z_]+)=\"");
        scrape.lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " ")).forEach(line -> {
            int open = line.indexOf('{');
            if (open < 0) {
                return;
            }
            Matcher m = label.matcher(line.substring(open, line.indexOf('}')));
            while (m.find()) {
                names.add(m.group(1));
            }
        });
        return names;
    }
}
