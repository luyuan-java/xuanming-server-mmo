package com.game.gate.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.gate.metrics.GateMetrics.DisconnectReason;
import com.game.gate.metrics.GateMetrics.RedirectResult;
import com.game.gate.metrics.GateMetrics.RedirectSource;
import com.game.gate.metrics.GateMetrics.RequestResult;
import com.game.gate.metrics.GateMetrics.SceneTransferResult;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4 先行件在 gate 指标里加的东西：{@code xm_gate_redirects_total{source, result}}（全量预建）、断开原因 {@code redirect_linger}、
 * 请求去向 {@code redirected}。固化导出名与标签：改名或换 Micrometer 版本时这里先红。
 */
class GateMetricsTest {

    @Test
    void 重定向指标_两个来源乘四种结局启动即注册_导出名与标签_发射不新增序列() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            GateMetrics metrics = new GateMetrics(prometheus);
            String before = prometheus.scrape();
            String[] sources = {"travel", "login"};
            String[] results = {"sent", "invalid", "stale", "orphan"};
            for (String source : sources) {
                for (String result : results) {
                    assertThat(before).contains(
                            "xm_gate_redirects_total{result=\"" + result + "\",source=\"" + source + "\"} 0");
                }
            }
            assertThat(redirectSeries(before)).as("2 × 4，不多不少").isEqualTo(8);
            assertThat(RedirectSource.values()).hasSize(sources.length);
            assertThat(RedirectResult.values()).hasSize(results.length);

            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.SENT);
            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.SENT);
            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.ORPHAN);
            metrics.redirect(RedirectSource.LOGIN, RedirectResult.INVALID);

            String after = prometheus.scrape();
            assertThat(after).contains(
                    "xm_gate_redirects_total{result=\"sent\",source=\"travel\"} 2",
                    "xm_gate_redirects_total{result=\"orphan\",source=\"travel\"} 1",
                    "xm_gate_redirects_total{result=\"invalid\",source=\"login\"} 1",
                    "xm_gate_redirects_total{result=\"sent\",source=\"login\"} 0",
                    "xm_gate_redirects_total{result=\"stale\",source=\"travel\"} 0");
            assertThat(redirectSeries(after)).as("发射不新增时间序列").isEqualTo(8);
            assertThat(labelNames(after, "xm_gate_redirects")).as("不带 player / session / zone / 节点号")
                    .containsExactly("result", "source");
        } finally {
            prometheus.close();
        }
    }

    /** 重定向帧不计进改绑指标：{@code xm.gate.scene.transfers} 与 5.2 改绑的 handed_off 勾稽，两套计数互不相干。 */
    @Test
    void 重定向计数不动改绑指标_改绑指标的取值集合没变() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            GateMetrics metrics = new GateMetrics(prometheus);
            for (RedirectSource source : RedirectSource.values()) {
                for (RedirectResult result : RedirectResult.values()) {
                    metrics.redirect(source, result);
                }
            }

            String scrape = prometheus.scrape();
            for (SceneTransferResult result : SceneTransferResult.values()) {
                assertThat(scrape).contains(
                        "xm_gate_scene_transfers_total{result=\"" + GateMetrics.tagValue(result) + "\"} 0");
            }
            assertThat(SceneTransferResult.values()).as("改绑的八种结局没有因为重定向多出取值").hasSize(8);
            assertThat(labelNames(scrape, "xm_gate_scene_transfers")).containsExactly("result");
        } finally {
            prometheus.close();
        }
    }

    @Test
    void 断开原因多了redirect_linger_启动即注册_请求去向多了redirected() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            GateMetrics metrics = new GateMetrics(prometheus);
            String before = prometheus.scrape();
            assertThat(before).contains("xm_gate_disconnects_total{reason=\"redirect_linger\"} 0");
            assertThat(before).as("请求去向按方法首次出现才注册，启动时没有").doesNotContain("result=\"redirected\"");

            metrics.disconnected(DisconnectReason.REDIRECT_LINGER);
            metrics.request("scene", "SceneSkillClientPlayer.ListSkills", RequestResult.REDIRECTED);
            metrics.request("scene", "SceneSkillClientPlayer.ListSkills", RequestResult.REDIRECTED);
            metrics.request(null, null, RequestResult.REDIRECTED);

            String after = prometheus.scrape();
            assertThat(after).contains("xm_gate_disconnects_total{reason=\"redirect_linger\"} 1",
                    "xm_gate_client_requests_total{method=\"SceneSkillClientPlayer.ListSkills\",result=\"redirected\",route=\"scene\"} 2",
                    "xm_gate_client_requests_total{method=\"unknown\",result=\"redirected\",route=\"unknown\"} 1");
            assertThat(after).contains("xm_gate_disconnects_total{reason=\"transfer_failed\"} 0");
        } finally {
            prometheus.close();
        }
    }

    private static long redirectSeries(String scrape) {
        return scrape.lines().filter(line -> line.startsWith("xm_gate_redirects_total{")).count();
    }

    private static Set<String> labelNames(String scrape, String prefix) {
        Set<String> names = new TreeSet<>();
        Pattern label = Pattern.compile("([a-z_]+)=\"");
        for (String line : scrape.split("\n")) {
            int brace = line.indexOf('{');
            if (!line.startsWith(prefix) || brace < 0) {
                continue;
            }
            Matcher m = label.matcher(line.substring(brace));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
