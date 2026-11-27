package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.PlayerInboxService;
import com.game.match.global.GlobalMatchStore;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-06 Match 个人结算首投积压两个 gauge（有积压 / 无积压写 0 / 读失败保留读数）与告警清单静态核对。 */
class BattleCompletionBacklogMetricsTest {

    @Test
    void 首投积压Gauge覆盖有积压_读失败保留上次读数_清空后写0_且标签仅含kind() throws Exception {
        AtomicLong nowMs = new AtomicLong(1_700_000_100_000L);
        AtomicLong mockCount = new AtomicLong(3L);
        AtomicLong mockMinIssued = new AtomicLong(1_700_000_025_000L); // 75s age
        AtomicBoolean failDb = new AtomicBoolean(false);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenAnswer(inv -> {
            if (failDb.get()) {
                throw new SQLException("simulated db outage");
            }
            return conn;
        });
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenAnswer(inv -> mockCount.get());
        when(rs.getString(2)).thenAnswer(inv -> mockCount.get() == 0 ? null : Long.toUnsignedString(mockMinIssued.get()));

        BattleCompletionStore store = new BattleCompletionStore(ds, c -> nowMs.get());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MatchMetrics metrics = new MatchMetrics(registry, new MetricLabels(id -> false));

        try (BattleCompletionReplay replay = new BattleCompletionReplay(
                store, mock(GlobalMatchStore.class), mock(PlayerInboxService.class),
                (e, orig) -> CompletableFuture.completedFuture(null),
                "0123456789abcdef0123456789abcdef", nowMs::get, metrics)) {
            Gauge rowsGauge = registry.get(MatchMetrics.OUTCOME_OUTBOX_BACKLOG_ROWS).tag("kind", "first").gauge();
            Gauge ageGauge = registry.get(MatchMetrics.OUTCOME_OUTBOX_OLDEST_PENDING_AGE).tag("kind", "first").gauge();
            assertThat(rowsGauge.getId().getTags()).hasSize(1);
            assertThat(ageGauge.getId().getTags()).hasSize(1);

            // 1. 有积压：3 行，最老 75 秒
            replay.sampleBacklog();
            assertThat(rowsGauge.value()).isEqualTo(3.0);
            assertThat(ageGauge.value()).isEqualTo(75.0);

            // 2. 读库失败：保留上一次读数（3 与 75.0），不重置为 0，不抛异常
            failDb.set(true);
            replay.sampleBacklog();
            assertThat(rowsGauge.value()).isEqualTo(3.0);
            assertThat(ageGauge.value()).isEqualTo(75.0);

            // 3. 恢复且清空积压：写 0 与 0.0
            failDb.set(false);
            mockCount.set(0L);
            replay.sampleBacklog();
            assertThat(rowsGauge.value()).isEqualTo(0.0);
            assertThat(ageGauge.value()).isEqualTo(0.0);
        } finally {
            registry.close();
        }
    }

    @Test
    void 告警规则文件静态核对_指标名与注册一致且阈值遵守60s投递余量与max聚合() throws Exception {
        Path alertFile = Path.of("deploy/k8s/match-alerts.yaml");
        if (!Files.exists(alertFile)) {
            alertFile = Path.of("../deploy/k8s/match-alerts.yaml");
        }
        assertThat(alertFile).exists();
        String yaml = Files.readString(alertFile);

        String promAgeMetric = MatchMetrics.OUTCOME_OUTBOX_OLDEST_PENDING_AGE.replace('.', '_') + "_seconds";
        String promRowsMetric = MatchMetrics.OUTCOME_OUTBOX_BACKLOG_ROWS.replace('.', '_');
        assertThat(yaml).contains("max(" + promAgeMetric + "{kind=\"first\"}) > 60");
        assertThat(yaml).contains("for: 2m");
        assertThat(yaml).contains("max(" + promAgeMetric + "{kind=\"first\"}) > 300");
        assertThat(yaml).contains("for: 1m");
        assertThat(yaml).contains(promRowsMetric);
        assertThat(yaml).contains("game_match_rating_unpublished_total");
    }
}
