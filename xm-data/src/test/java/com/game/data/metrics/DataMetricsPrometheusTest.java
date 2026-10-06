package com.game.data.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.data.rollback.RollbackJob;
import com.game.data.rollback.RollbackService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 运维面指标按 Prometheus 导出格式核对（data-ops-spec §8.2；审计 OPS-16）。其余用例都用 SimpleMeterRegistry、按 Micrometer 名取指标，
 * 钉不住导出名：两个 Timer 的常量名自带 {@code .seconds}，命名约定又会给 Timer 补 {@code _seconds}——这里钉住导出名里恰好只有一截；
 * 以及逐玩家结局的 {@code outcome} 取值集合（告警规则按这些值写）。
 */
class DataMetricsPrometheusTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final DataMetrics metrics = new DataMetrics(registry);

    /** 导出文本里的样本：「名字{标签}」→ 值（注释行不算）。 */
    private Map<String, Double> samples() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : registry.scrape().split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            out.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
        }
        return out;
    }

    @Test
    void 作业耗时与帮会检查耗时_导出名是xm_data_ops_job_seconds与xm_data_rollback_guild_check_seconds_不多一截seconds() {
        metrics.opsJob("rollback", "succeeded", Duration.ofMillis(1500).toNanos());
        metrics.opsJob("rollback", "failed", Duration.ofMillis(500).toNanos());
        metrics.opsJob("zone_snapshot", "succeeded", Duration.ofMillis(250).toNanos());
        metrics.guildCheckTime(Duration.ofMillis(250).toNanos());

        Map<String, Double> samples = samples();

        assertThat(samples)
                .containsEntry("xm_data_ops_job_seconds_count{kind=\"rollback\"}", 2.0)
                .containsEntry("xm_data_ops_job_seconds_sum{kind=\"rollback\"}", 2.0)
                .containsEntry("xm_data_ops_job_seconds_max{kind=\"rollback\"}", 1.5)
                .containsEntry("xm_data_ops_job_seconds_count{kind=\"zone_snapshot\"}", 1.0)
                .containsEntry("xm_data_rollback_guild_check_seconds_count", 1.0)
                .containsEntry("xm_data_rollback_guild_check_seconds_sum", 0.25)
                // 作业结局计数与耗时是两个指标：同一次 opsJob 各记一笔
                .containsEntry("xm_data_ops_jobs_total{kind=\"rollback\",outcome=\"succeeded\"}", 1.0)
                .containsEntry("xm_data_ops_jobs_total{kind=\"rollback\",outcome=\"failed\"}", 1.0)
                .containsEntry("xm_data_ops_jobs_total{kind=\"zone_snapshot\",outcome=\"succeeded\"}", 1.0);
        assertThat(registry.scrape()).doesNotContain("seconds_seconds");
        // 耗时不按结局分标签（结局在 jobs_total 上）
        assertThat(samples.keySet()).filteredOn(name -> name.startsWith("xm_data_ops_job_seconds"))
                .allSatisfy(name -> assertThat(name).doesNotContain("outcome"));
    }

    private static final Pattern PLAYERS = Pattern.compile(
            "xm_data_ops_players_total\\{kind=\"rollback\",outcome=\"([a-z_]+)\"}");

    private Map<String, Double> playerOutcomes() {
        Map<String, Double> out = new LinkedHashMap<>();
        samples().forEach((name, value) -> {
            Matcher m = PLAYERS.matcher(name);
            if (m.matches()) {
                out.put(m.group(1), value);
            }
        });
        return out;
    }

    @Test
    void 逐玩家结局_outcome取值恰好是回档的固定集合_装配时全部预建为0_含战斗锁的两个结局() {
        // 生产装配：RollbackService 构造时登记（这里只给它指标，其余依赖用不到）
        new RollbackService(new RollbackJob.Deps(null, null, null, null, null, null, null, null, null, null, metrics, null),
                null, null, null, null);

        Set<String> expected = Set.of("restored", "player_not_found", "snapshot_not_found", "no_snapshot",
                "created_after_target", "player_online", "player_busy", "in_battle", "battle_lock_unknown", "snapshot_gone",
                "state_invalid", "unknown_sections", "fence_lost", "id_unavailable", "failed", "not_executed", "rejected",
                "cancelled");
        assertThat(new TreeSet<>(RollbackJob.PLAYER_OUTCOMES)).as("代码里的固定集合").isEqualTo(new TreeSet<>(expected));
        assertThat(RollbackJob.PLAYER_OUTCOMES).doesNotHaveDuplicates();
        // 还没有任何作业：每个取值都已经导出、值为 0（「从没发生」与「指标不存在」分得开，rate(...) > 0 的告警才写得出来）
        assertThat(playerOutcomes().keySet()).isEqualTo(expected);
        assertThat(playerOutcomes().values()).containsOnly(0.0);

        metrics.opsPlayer("rollback", "in_battle");
        metrics.opsPlayer("rollback", "battle_lock_unknown");
        metrics.opsPlayer("rollback", "battle_lock_unknown");
        metrics.opsPlayer("rollback", "restored");

        Map<String, Double> after = playerOutcomes();
        assertThat(after.keySet()).as("计数不会长出新的标签值").isEqualTo(expected);
        assertThat(after).containsEntry("in_battle", 1.0).containsEntry("battle_lock_unknown", 2.0)
                .containsEntry("restored", 1.0).containsEntry("player_online", 0.0);
        // 标签只有 kind / outcome 两个，不带玩家号 / 作业号（AGENTS.md §5）
        assertThat(samples().keySet()).filteredOn(name -> name.startsWith("xm_data_ops_players_total"))
                .allSatisfy(name -> assertThat(name).matches(PLAYERS.pattern()));
    }

    @Test
    void 两个Gauge装配时就在_夺权与墓碑计数的导出名() {
        metrics.registerOps();
        metrics.opsJobRunning(true);
        metrics.fenceHeld(3);
        metrics.opsClaim("online");
        metrics.fenceLost(2);
        metrics.locationTombstone("stale");
        metrics.divergenceCheck("guild", "post_write_failed");
        metrics.divergenceRows("ledger", true, 4);

        assertThat(samples())
                .containsEntry("xm_data_ops_jobs_running", 1.0)
                .containsEntry("xm_data_ops_fence_held", 3.0)
                .containsEntry("xm_data_ops_claims_total{outcome=\"online\"}", 1.0)
                .containsEntry("xm_data_ops_fence_lost_total", 2.0)
                .containsEntry("xm_data_location_tombstones_total{result=\"stale\"}", 1.0)
                .containsEntry("xm_data_rollback_divergence_check_total{result=\"post_write_failed\",source=\"guild\"}", 1.0)
                .containsEntry("xm_data_rollback_divergence_rows_total{accepted=\"true\",source=\"ledger\"}", 4.0);
    }
}
