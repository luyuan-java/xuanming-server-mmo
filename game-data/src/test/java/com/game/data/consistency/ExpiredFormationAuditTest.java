package com.game.data.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExpiredFormationAuditTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
    private final AtomicLong clock = new AtomicLong(1_000);
    private ExpiredFormationAudit audit;
    @AfterEach void close() { if (audit != null) audit.close(); metrics.close(); }
    private ExpiredFormationAudit make(ConsistencySqlReader reader, int rows) {
        audit = new ExpiredFormationAudit(reader, settings(true, rows), metrics, worker, clock::get);
        return audit;
    }
    static ExpiredFormationAuditProperties settings(boolean enabled, int rows) {
        return new ExpiredFormationAuditProperties(enabled, Duration.ofSeconds(60), Duration.ofSeconds(3),
                Duration.ofSeconds(150), 1, rows);
    }
    static Map<String, Object> key(String id) { return Map.of("k1", new BigDecimal(id), "k2", BigDecimal.ZERO); }
    static Map<String, Object> row(String id) {
        var row = new LinkedHashMap<String, Object>(key(id));
        row.put("operation_id", "operation"); row.put("instance_id", "instance"); row.put("state", 3);
        row.put("deadline_ms", 1L); row.put("create_length", 1L); row.put("db_now", 100_000L);
        row.put("completion_missing", true); row.put("terminal_decision", null);
        row.put("placement_operation_id", null); row.put("target_instance_id", null);
        row.put("outcome_protocol_version", null); row.put("preflight_supported_version", null);
        row.put("preflight_instance_id", null); row.put("adopted_outcome_protocol_version", null);
        row.put("adopted_outcome_observed", null);
        return row;
    }
    static Map<String, Object> terminal(String id) {
        var row = row(id); row.put("completion_missing", false); row.put("terminal_decision", 1); return row;
    }
    static Map<String, Object> deepUnknown(String id) {
        var row = row(id); row.put("completion_missing", false); row.put("terminal_decision", 0);
        row.put("placement_operation_id", "operation"); row.put("target_instance_id", "instance");
        row.put("outcome_protocol_version", 1); row.put("preflight_supported_version", 1);
        row.put("preflight_instance_id", "instance"); row.put("adopted_outcome_protocol_version", 0);
        row.put("adopted_outcome_observed", false); return row;
    }
    static final class Scripted implements ConsistencySqlReader {
        final ArrayDeque<Object> replies = new ArrayDeque<>();
        final List<String> statements = new ArrayList<>();
        final List<List<Object>> arguments = new ArrayList<>();
        Scripted(Object... values) { replies.addAll(List.of(values)); }
        @Override @SuppressWarnings("unchecked") public List<Map<String, Object>> query(String sql, List<Object> args,
                int limit, ConsistencyBudget budget) {
            assertThat(sql).startsWith("SELECT ").doesNotContain("FOR UPDATE", "INSERT", "UPDATE ", "DELETE", "CALL ");
            assertThat(limit).isBetween(1, 256);
            assertThat(budget.remainingMillis()).isBetween(1L, 3_000L);
            statements.add(sql); arguments.add(args);
            Object value = replies.removeFirst();
            if (value instanceof RuntimeException failure) throw failure;
            if (value instanceof Supplier<?> supplier) value = supplier.get();
            return (List<Map<String, Object>>) value;
        }
    }
    @Test void emptyWindowStillChecksDependencySchemaAndMakesNoWrites() {
        var sql = new Scripted(List.of(), List.of());
        make(sql, 2).round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.OBSERVED);
        assertThat(audit.observation().windowFinished()).isTrue();
        assertThat(audit.observation().badLowerBound()).isZero();
        assertThat(sql.statements).hasSize(2);
        assertThat(sql.statements.getFirst()).contains("LEFT JOIN match_battle_completion", "WHERE 1=0");
    }
    @Test void missingSqlAndSchemaFailureNeverPublishHealthyZero() {
        make(null, 2).round();
        assertThat(audit.observation().reason()).isEqualTo("sql_not_configured");
        assertThat(audit.observation().badLowerBound()).isNaN();
        audit.close(); metrics.clear();
        make(new Scripted(new ConsistencyBudget.Failure("schema_unavailable")), 2).round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        assertThat(audit.observation().badLowerBound()).isNaN();
    }
    @Test void stableMissingCompletionIsConfirmedAndOnlyFixedTagsAreEmitted() {
        var broken = row("18446744073709551615");
        make(new Scripted(List.of(), List.of(key("18446744073709551615")), List.of(broken), List.of(broken)), 1).round();
        assertThat(audit.observation().confirmedBad()).isEqualTo(1);
        assertThat(audit.observation().windowFinished()).isTrue();
        assertThat(audit.observation().toString()).doesNotContain("18446744073709551615", "operation", "instance");
        assertThat(metrics.get("game.data.match.expired.bad.lowerbound").gauge().value()).isEqualTo(1);
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("state")));
    }
    @Test void changedOrDisappearedBadRowIsUnknownInsteadOfConfirmed() {
        var before = row("1"); var after = row("1"); after.put("create_length", 2L);
        make(new Scripted(List.of(), List.of(key("1")), List.of(before), List.of(after)), 1).round();
        assertThat(audit.observation().confirmedBad()).isZero();
        assertThat(audit.observation().uncertain()).isEqualTo(1);
        assertThat(audit.observation().badLowerBound()).isNaN();
    }
    @Test void disappearingBadRowCannotBecomeConfirmedOrCompleteHealthyObservation() {
        make(new Scripted(List.of(), List.of(key("1")), List.of(row("1")), List.of()), 1).round();
        assertThat(audit.observation().confirmedBad()).isZero();
        assertThat(audit.observation().uncertain()).isEqualTo(1);
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        assertThat(audit.observation().badLowerBound()).isNaN();
    }
    @Test void validSqlShapeRemainsUnknownWithoutCreateDeepProof() {
        var sql = new Scripted(List.of(), List.of(key("1")), List.of(deepUnknown("1")));
        make(sql, 1).round();
        assertThat(audit.observation().reason()).isEqualTo("unproved_rows");
        assertThat(audit.observation().uncertain()).isEqualTo(1);
        assertThat(audit.observation().badLowerBound()).isNaN();
        assertThat(sql.statements).hasSize(3);
    }
    @Test void persistentUnsignedCursorReachesLaterRowsWithoutSummingOldRounds() {
        var sql = new Scripted(List.of(), List.of(key("18446744073709551615")), List.of(terminal("9223372036854775808")),
                List.of(), List.of(row("18446744073709551615")), List.of(row("18446744073709551615")),
                List.of(), List.of());
        make(sql, 1).round();
        assertThat(audit.observation().windowFinished()).isFalse();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        audit.round();
        assertThat(sql.arguments.get(4)).containsExactly(new BigDecimal("9223372036854775808"), new BigDecimal("18446744073709551615"));
        assertThat(audit.observation().scanned()).isEqualTo(1);
        assertThat(audit.observation().confirmedBad()).isEqualTo(1);
        audit.round();
        assertThat(audit.observation().confirmedBad()).isZero();
        assertThat(audit.observation().windowFinished()).isTrue();
        assertThat(sql.statements.stream().filter(s -> s.contains("ORDER BY battle_id DESC"))).hasSize(2);
    }
    @Test void failedConfirmationConsumesCursorButDoesNotBlockNextRowForever() {
        var sql = new Scripted(List.of(), List.of(key("2")), List.of(row("1")), new ConsistencyBudget.Failure("query_timeout"),
                List.of(), List.of(terminal("2")));
        make(sql, 1).round();
        assertThat(audit.observation().reason()).isEqualTo("query_timeout");
        assertThat(audit.observation().confirmedBad()).isZero();
        audit.round();
        assertThat(sql.arguments.get(5)).containsExactly(new BigDecimal("1"), new BigDecimal("2"));
        assertThat(audit.observation().windowFinished()).isTrue();
    }
    @Test void laterQueryFailureRetainsKnownBadLowerBoundAndUnknownCoverage() {
        var broken = row("1");
        make(new Scripted(List.of(), List.of(key("2")), List.of(broken), List.of(broken),
                new ConsistencyBudget.Failure("budget_exhausted")), 2).round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        assertThat(audit.observation().confirmedBad()).isEqualTo(1);
        assertThat(audit.observation().badLowerBound()).isEqualTo(1);
        assertThat(audit.observation().reason()).isEqualTo("budget_exhausted");
    }
    @Test void staleOrStoppedObservationCannotRetainFreshZeroOrBeRevived() {
        make(new Scripted(List.of(), List.of()), 1).round();
        clock.addAndGet(Duration.ofSeconds(151).toNanos());
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.STALE);
        assertThat(audit.observation().badLowerBound()).isNaN();
        audit.close(); audit.round(); audit.start(); audit.close();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.STOPPED);
        assertThat(audit.observation().badLowerBound()).isNaN();
        verify(worker, times(1)).shutdownNow();
        assertThat(metrics.get("game.data.match.expired.worker.terminated").gauge().value()).isZero();
    }
    @Test void lateSqlReplyAfterCloseCannotPublishObservation() {
        Supplier<List<Map<String, Object>>> late = () -> { audit.close(); return List.of(); };
        make(new Scripted(late), 1).round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.STOPPED);
        assertThat(audit.observation().badLowerBound()).isNaN();
    }
    @Test void workerStartsOnlyOnceAndDisabledModeNeverSchedules() {
        make(null, 1).start(); audit.start();
        verify(worker).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(60_000L), eq(TimeUnit.MILLISECONDS));
        audit.close(); metrics.clear(); clearInvocations(worker);
        audit = new ExpiredFormationAudit(null, settings(false, 1), metrics, worker, clock::get);
        audit.start(); audit.round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.DISABLED);
        verifyNoInteractions(worker);
    }
    @Test void malformedRowsAndUnexpectedMessagesAreRedactedAndUnknown() {
        make(new Scripted(List.of(), List.of(key("1")), List.of(Map.of("k1", "not-a-number"))), 1).round();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        assertThat(audit.observation().reason()).isEqualTo("row_decode_failed");
        audit.close(); metrics.clear();
        make(new Scripted(new ConsistencyBudget.Failure("secret://do-not-echo")), 1).round();
        assertThat(audit.observation().reason()).isEqualTo("read_failed");
        assertThat(audit.observation().toString()).doesNotContain("secret");
    }
    @Test void concurrentTickDoesNotEnqueueOrRunAnotherDatabaseScan() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Supplier<List<Map<String, Object>>> held = () -> {
            entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("测试未及时释放"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return List.of();
        };
        var sql = new Scripted(held, List.of());
        make(sql, 1);
        Thread first = Thread.ofPlatform().daemon(true).start(audit::round);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            audit.round();
            assertThat(sql.statements).hasSize(1);
        } finally { release.countDown(); first.join(2_000); }
        assertThat(first.isAlive()).isFalse();
        assertThat(sql.statements).hasSize(2);
        assertThat(audit.observation().windowFinished()).isTrue();
    }
    @Test void actualLateReturnPastRoundBudgetCannotPublishSuccessfulObservation() {
        var properties = new ExpiredFormationAuditProperties(true, Duration.ofSeconds(60), Duration.ofMillis(100),
                Duration.ofSeconds(150), 1, 1);
        ConsistencySqlReader slow = (sql, parameters, limit, budget) -> {
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return List.of();
        };
        audit = new ExpiredFormationAudit(slow, properties, metrics, worker, clock::get);
        audit.round();
        assertThat(audit.observation().reason()).isEqualTo("budget_exhausted");
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.UNKNOWN);
        assertThat(audit.observation().badLowerBound()).isNaN();
        assertThat(audit.observation().windowFinished()).isFalse();
    }
    @Test void actualScheduledWorkerRunsAndClosesWithoutLeavingLiveThread() throws Exception {
        var seen = new CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("test-expired-formation-audit").factory());
        ConsistencySqlReader reader = (sql, parameters, limit, budget) -> { seen.countDown(); return List.of(); };
        audit = new ExpiredFormationAudit(reader, settings(true, 1), metrics, executor, System::nanoTime);
        try {
            audit.start();
            assertThat(seen.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { audit.close(); }
        assertThat(executor.isTerminated()).isTrue();
        assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.STOPPED);
        assertThat(metrics.get("game.data.match.expired.worker.terminated").gauge().value()).isEqualTo(1);
    }
}
