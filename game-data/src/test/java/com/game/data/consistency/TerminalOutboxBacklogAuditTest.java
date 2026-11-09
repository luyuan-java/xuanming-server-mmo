package com.game.data.consistency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerminalOutboxBacklogAuditTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
    private final AtomicLong nanoClock = new AtomicLong(1_000);
    private TerminalOutboxBacklogAudit audit;

    @BeforeEach void prepareWorker() throws Exception { when(worker.awaitTermination(anyLong(), any())).thenReturn(true); }
    @AfterEach void close() { if (audit != null) audit.close(); metrics.close(); }
    private TerminalOutboxBacklogAudit make(ConsistencySqlReader sql) { return make(sql, settings(true, 3_000)); }
    private TerminalOutboxBacklogAudit make(ConsistencySqlReader sql, TerminalOutboxBacklogProperties properties) {
        if (audit != null) audit.close();
        metrics.clear();
        audit = new TerminalOutboxBacklogAudit(sql, properties, metrics, worker, nanoClock::get);
        return audit;
    }
    private static TerminalOutboxBacklogProperties settings(boolean enabled, long budgetMillis) {
        return new TerminalOutboxBacklogProperties(enabled, Duration.ofSeconds(60), Duration.ofMillis(budgetMillis), Duration.ofSeconds(150));
    }
    private static Map<String, Object> row(String kind, Object count, Object oldest, Object now) {
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", kind); row.put("pending_rows", count); row.put("oldest_ms", oldest); row.put("db_now", now);
        return row;
    }
    private static List<Map<String, Object>> empty() {
        return List.of(row("result", 0L, null, 100_250L), row("first", 0L, null, 100_250L), row("waiting", 0L, null, 100_250L));
    }
    private static List<Map<String, Object>> pending() {
        return List.of(row("result", 2L, 90_000L, 100_250L), row("first", 0L, null, 100_250L), row("waiting", 40L, 250L, 100_250L));
    }
    private static final class Scripted implements ConsistencySqlReader {
        final ArrayDeque<Object> replies = new ArrayDeque<>();
        int calls;
        Scripted(Object... replies) { this.replies.addAll(List.of(replies)); }
        @Override @SuppressWarnings("unchecked") public List<Map<String, Object>> query(String sql, List<Object> args,
                int maxRows, ConsistencyBudget budget) {
            assertThat(sql).startsWith("SELECT ").doesNotContain("FOR UPDATE", "INSERT ", "UPDATE ", "DELETE ");
            assertThat(args).isEmpty();
            assertThat(maxRows).isEqualTo(3);
            assertThat(budget.remainingMillis()).isBetween(1L, 3_000L);
            calls++;
            Object reply = replies.removeFirst();
            if (reply instanceof RuntimeException failure) throw failure;
            if (reply instanceof Supplier<?> supplier) reply = supplier.get();
            return (List<Map<String, Object>>) reply;
        }
    }
    private void assertUnknown(String reason) {
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.UNKNOWN);
        assertThat(audit.observation().reason()).isEqualTo(reason);
        assertThat(audit.observation().readings()).isEmpty();
        for (var kind : TerminalOutboxBacklogAudit.Kind.values()) {
            assertThat(audit.observation().rows(kind)).isNaN();
            assertThat(audit.observation().oldestAgeSeconds(kind)).isNaN();
        }
    }

    @Test void initialAndDisabledStatesNeverAdvertiseHealthyZero() {
        var sql = mock(ConsistencySqlReader.class);
        make(sql);
        assertUnknown("not_started");
        make(sql, settings(false, 3_000)).start();
        audit.round();
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.DISABLED);
        assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isNaN();
        verifyNoInteractions(sql);
        verify(worker, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test void allKindsPublishTogetherFromExactlyOneReadAndUseDatabaseAge() {
        var sql = new Scripted(pending());
        make(sql).round();
        var observed = audit.observation();
        assertThat(observed.state()).isEqualTo(TerminalOutboxBacklogAudit.State.OBSERVED);
        assertThat(observed.rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isEqualTo(2);
        assertThat(observed.oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.RESULT)).isEqualTo(10.25);
        assertThat(observed.rows(TerminalOutboxBacklogAudit.Kind.FIRST)).isZero();
        assertThat(observed.oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.FIRST)).isZero();
        assertThat(observed.rows(TerminalOutboxBacklogAudit.Kind.WAITING)).isEqualTo(40);
        assertThat(observed.oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.WAITING)).isEqualTo(100);
        assertThat(sql.calls).isEqualTo(1);
        assertThatThrownBy(() -> observed.readings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void successfulEmptySnapshotReplacesOldBacklogWithZeroForEveryKind() {
        make(new Scripted(pending(), empty())).round();
        audit.round();
        for (var kind : TerminalOutboxBacklogAudit.Kind.values()) {
            assertThat(audit.observation().rows(kind)).isZero();
            assertThat(audit.observation().oldestAgeSeconds(kind)).isZero();
        }
    }

    @Test void futureArchiveTimestampClampsOnlyAgeAndDoesNotEraseNonzeroRows() {
        var rows = new ArrayList<>(empty());
        rows.set(0, row("result", 1L, 100_251L, 100_250L));
        make(new Scripted(rows)).round();
        assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isEqualTo(1);
        assertThat(audit.observation().oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.RESULT)).isZero();
    }

    @Test void missingSqlOrDependencyFailureInvalidatesAllPreviousReadings() {
        make(null).round();
        assertUnknown("sql_not_configured");
        for (String reason : List.of("schema_unavailable", "query_timeout", "namespace_mismatch", "strong_read_unproved")) {
            make(new Scripted(pending(), new ConsistencyBudget.Failure(reason))).round();
            audit.round();
            assertUnknown(reason);
        }
    }

    @Test void exceptionDetailsCannotBecomeMetricLabelsOrObservationReasons() {
        make(new Scripted(new IllegalStateException("fixture account secret and SQL text"))).round();
        assertUnknown("read_failed");
        assertThat(audit.observation().toString()).doesNotContain("account", "secret", "SQL");
    }

    @Test void malformedCountOrArchiveTimeRejectsWholeSnapshot() {
        var invalidRows = List.of(
                row("result", -1L, 10L, 100_250L),
                row("result", new BigDecimal("1.5"), 10L, 100_250L),
                row("result", new BigDecimal("18446744073709551615"), 10L, 100_250L),
                row("result", 1L, null, 100_250L),
                row("result", 1L, 0L, 100_250L),
                row("result", 1L, -1L, 100_250L),
                row("result", 1L, new BigDecimal("18446744073709551615"), 100_250L),
                row("result", 0L, 10L, 100_250L),
                row("result", true, 10L, 100_250L),
                row("result", 1L, 10L, 0L));
        for (var invalid : invalidRows) {
            var rows = new ArrayList<>(empty()); rows.set(0, invalid);
            make(new Scripted(rows)).round();
            assertUnknown("row_decode_failed");
        }
    }

    @Test void MissingDuplicateUnknownKindsOrDifferentSqlTimesNeverPartiallyPublish() {
        var missing = empty().subList(0, 2);
        var duplicate = List.of(empty().getFirst(), empty().getFirst(), empty().getLast());
        var unknown = List.of(row("player-123", 1L, 1L, 100_250L), empty().get(1), empty().get(2));
        var otherTime = List.of(row("result", 0L, null, 100_251L), empty().get(1), empty().get(2));
        for (var rows : List.of(missing, duplicate, unknown, otherTime)) {
            make(new Scripted(rows)).round();
            assertUnknown("row_decode_failed");
        }
        make(new Scripted((Supplier<Object>) () -> null)).round();
        assertUnknown("row_decode_failed");
    }

    @Test void numericDriverRepresentationsMustBeExactAndNonnegative() {
        var rows = List.of(row("result", new BigDecimal("2"), "90000", new BigDecimal("100250")),
                row("first", 0, null, 100_250L), row("waiting", "0", null, 100_250L));
        make(new Scripted(rows)).round();
        assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isEqualTo(2);
        assertThat(audit.observation().oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.RESULT)).isEqualTo(10.25);
    }

    @Test void realLateReturnAfterRoundBudgetIsUnknownEvenWhenRowsLookHealthy() {
        ConsistencySqlReader delayed = (sql, parameters, maxRows, budget) -> {
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return empty();
        };
        make(delayed, settings(true, 100)).round();
        assertUnknown("budget_exhausted");
    }

    @Test void monotonicFreshnessExpiresBothGaugesAndNextReadCanRecover() {
        make(new Scripted(pending(), empty())).round();
        nanoClock.addAndGet(Duration.ofSeconds(151).toNanos());
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.STALE);
        assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isNaN();
        assertThat(audit.observation().oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.RESULT)).isNaN();
        audit.round();
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.OBSERVED);
        assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isZero();
    }

    @Test void realWorkerStartsOnlyOnceAndCloseWaitsForItsTermination() throws Exception {
        var called = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var actualWorker = Executors.newSingleThreadScheduledExecutor();
        audit = new TerminalOutboxBacklogAudit((sql, args, size, budget) -> {
            calls.incrementAndGet(); called.countDown(); return empty();
        }, settings(true, 3_000), metrics, actualWorker, System::nanoTime);
        audit.start(); audit.start();
        assertThat(called.await(1, TimeUnit.SECONDS)).isTrue();
        audit.close();
        assertThat(actualWorker.isTerminated()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.STOPPED);
        assertThat(metrics.get("game.data.match.outbox.worker.terminated").gauge().value()).isEqualTo(1);
        audit.start(); audit.round();
        assertThat(calls).hasValue(1);
    }

    @Test void stopPreventsLatePublishEvenWhenRealWorkerIgnoresInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actualWorker = Executors.newSingleThreadScheduledExecutor();
        audit = new TerminalOutboxBacklogAudit((sql, args, size, budget) -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* 模拟未响应取消的驱动，测试必须显式释放。 */ }
            }
            return pending();
        }, settings(true, 100), metrics, actualWorker, System::nanoTime);
        try {
            audit.start();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            audit.close();
            assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.STOPPED);
            assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isNaN();
            assertThat(metrics.get("game.data.match.outbox.worker.terminated").gauge().value()).isZero();
        } finally {
            release.countDown(); actualWorker.shutdownNow();
            assertThat(actualWorker.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.STOPPED);
        assertThat(audit.observation().oldestAgeSeconds(TerminalOutboxBacklogAudit.Kind.WAITING)).isNaN();
        assertThat(metrics.get("game.data.match.outbox.worker.terminated").gauge().value()).isEqualTo(1);
    }

    @Test void PrometheusExportsOnlyFixedKindsAndStatesAndInvalidatesOnStop() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            audit = new TerminalOutboxBacklogAudit(new Scripted(pending()), settings(true, 3_000), registry, worker, nanoClock::get);
            audit.round();
            String scrape = registry.scrape();
            assertThat(scrape).contains("game_data_match_outbox_backlog_rows{kind=\"result\"} 2.0",
                    "game_data_match_outbox_oldest_pending_age_seconds{kind=\"result\"} 10.25",
                    "game_data_match_outbox_backlog_rows{kind=\"first\"} 0.0",
                    "game_data_match_outbox_backlog_rows{kind=\"waiting\"} 40.0")
                    .doesNotContain("seconds_seconds", "battle_id", "player_id", "owner_account");
            assertThat(registry.getMeters()).hasSize(12).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("kind", "state");
                    if (tag.getKey().equals("kind")) assertThat(tag.getValue()).isIn("result", "first", "waiting");
                    else assertThat(tag.getValue()).isIn("unknown", "observed", "stale", "stopped", "disabled");
                });
            });
            audit.close();
            assertThat(registry.scrape()).contains("game_data_match_outbox_backlog_rows{kind=\"result\"} NaN",
                    "game_data_match_outbox_oldest_pending_age_seconds{kind=\"waiting\"} NaN");
        } finally {
            registry.close();
        }
    }
}
