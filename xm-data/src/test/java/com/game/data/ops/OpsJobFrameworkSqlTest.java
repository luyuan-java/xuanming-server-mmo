package com.game.data.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsJobRunner.JobBody;
import com.game.data.ops.OpsJobRunner.JobResult;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobKind;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.testing.DataSqlFixture;
import com.game.data.testing.TestIds;
import com.game.data.testing.TestIds.FakeLease;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

/** 作业框架（T-J1、T-A1）：受理一个事务（槽 + 作业 + STARTED）、号源失效零变更、心跳丢失自停、清扫器、RESULT 后才收尾。 */
class OpsJobFrameworkSqlTest {

    private DataSqlFixture db;
    private FakeLease lease;
    private OpsIds ids;
    private ScheduledExecutorService fence;
    private SimpleMeterRegistry meters;
    private OpsJobRunner runner;
    private OpsJobService service;

    @BeforeEach
    void setUp() throws Exception {
        db = DataSqlFixture.create();
        lease = new FakeLease(9);
        ids = TestIds.ready(lease);
        fence = Executors.newSingleThreadScheduledExecutor();
        meters = new SimpleMeterRegistry();
        runner = new OpsJobRunner(db.jobs(), db.tx(), new ObjectMapper(), new DataMetrics(meters),
                Clock.systemUTC(), "test-runner", Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMinutes(1),
                fence);
        service = new OpsJobService(db.jobs(), ids, runner, db.tx(), new ObjectMapper(), Clock.systemUTC());
    }

    /** jobs_total{outcome=…} 的计数（没注册过为 0）。 */
    private double jobsTotal(String outcome) {
        Counter c = meters.find("xm.data.ops.jobs").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /** 直接造一个作业行（+ STARTED 事件）。 */
    private OpsJobRow job(long jobId, OpsJobStatus status) {
        long now = System.currentTimeMillis();
        OpsJobRow row = OpsJobRow.newBuilder().setJobId(jobId).setIdemKey("k" + jobId).setKind(OpsJobKind.OPS_JOB_ROLLBACK)
                .setStatus(status).setOperator("ops").setCreatedMs(now).build();
        db.jobs().insertJob(row);
        db.jobs().insertEvent(OpsJobEventRow.newBuilder().setJobId(jobId).setSeq(1)
                .setType(OpsJobEventType.OPS_JOB_EVENT_STARTED).setPayloadJson("{}").setAtMs(now).build());
        return row;
    }

    private void slot(long jobId, long heartbeatMs) {
        db.jobs().insertActive(OpsActiveRow.newBuilder().setSlot(1).setJobId(jobId).setRunner("dead")
                .setHeartbeatMs(heartbeatMs).build());
    }

    private List<OpsJobEventType> eventTypes(long jobId) {
        return db.jobs().events(jobId).stream().map(OpsJobEventRow::getType).toList();
    }

    @AfterEach
    void tearDown() throws Exception {
        runner.stop();
        fence.shutdownNow();
        ids.close();
        db.close();
    }

    private OpsJobService.Submission submission(String key, JobBody body) {
        return new OpsJobService.Submission(OpsJobKind.OPS_JOB_ROLLBACK, "/admin/rollbacks", "c=" + key,
                Map.of("k", key), "ops", "测试", key, 1, body);
    }

    private OpsJobRow await(long jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            OpsJobRow job = db.jobs().findJob(jobId).orElseThrow();
            if (job.getStatus() != OpsJobStatus.OPS_JOB_QUEUED && job.getStatus() != OpsJobStatus.OPS_JOB_RUNNING
                    && db.jobs().active().isEmpty()) {
                return job;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("作业没有终结");
    }

    @Test
    void 受理_槽作业STARTED同一事务_执行完RESULT之后才收尾并让出槽() throws Exception {
        AtomicBoolean resultBeforeAfter = new AtomicBoolean();
        long[] jobId = new long[1];
        Map<String, Object> accepted = service.submit(submission("k1", new JobBody() {
            @Override
            public JobResult run(JobContext ctx) {
                assertThat(db.jobs().active()).isPresent();
                return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of("x", 1));
            }

            @Override
            public void afterResult(JobContext ctx) {
                resultBeforeAfter.set(db.jobs().events(ctx.jobId()).stream()
                        .anyMatch(e -> e.getType() == OpsJobEventType.OPS_JOB_EVENT_RESULT));
                jobId[0] = ctx.jobId();
            }
        }));
        long id = Long.parseUnsignedLong((String) accepted.get("jobId"));
        OpsJobRow job = await(id);
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getRunner()).isEqualTo("test-runner");
        assertThat(job.getSummaryJson()).isEqualTo("{\"x\":1}");
        assertThat(resultBeforeAfter).isTrue();
        assertThat(jobId[0]).isEqualTo(id);
        assertThat(db.jobs().events(id).stream().map(OpsJobEventRow::getType).toList())
                .containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED, OpsJobEventType.OPS_JOB_EVENT_RESULT);
        assertThat(db.jobs().active()).isEmpty();
    }

    @Test
    void 号源无效_503_id_unavailable_零变更() {
        lease.valid.set(false);
        assertThatThrownBy(() -> service.submit(submission("k1", ctx -> JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok",
                Map.of()))))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("id_unavailable");
                });
        assertThat(db.count("ops_job")).isZero();
        assertThat(db.count("ops_active")).isZero();
        assertThat(db.count("ops_job_event")).isZero();
    }

    @Test
    void 已占槽时受理失败_作业行与STARTED都不留() {
        db.jobs().insertActive(OpsActiveRow.newBuilder().setSlot(1).setJobId(77).setRunner("other")
                .setHeartbeatMs(System.currentTimeMillis()).build());
        assertThatThrownBy(() -> service.submit(submission("k1", ctx -> JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok",
                Map.of()))))
                .isInstanceOfSatisfying(OpsException.class, e -> {
                    assertThat(e.code()).isEqualTo("ops_busy");
                    assertThat(e.details().get("runningJobId")).isEqualTo("77");
                });
        assertThat(db.count("ops_job")).isZero();
        assertThat(db.count("ops_job_event")).isZero();
    }

    @Test
    void 心跳丢失_执行线程自停_作业行保持清扫器写的INTERRUPTED_仍追加RESULT并收尾() throws Exception {
        runner.start();
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean cleanedUp = new AtomicBoolean();
        Map<String, Object> accepted = service.submit(submission("k1", new JobBody() {
            @Override
            public JobResult run(JobContext ctx) {
                running.countDown();
                while (true) {
                    ctx.pause(Duration.ofMillis(50));
                }
            }

            @Override
            public void afterResult(JobContext ctx) {
                cleanedUp.set(true);
            }
        }));
        long id = Long.parseUnsignedLong((String) accepted.get("jobId"));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        // 别的副本的清扫器收走了槽、把作业改成 INTERRUPTED
        OpsActiveRow active = db.jobs().active().orElseThrow();
        assertThat(db.jobs().sweepActive(id, active.getHeartbeatMs()) || db.jobs().deleteActive(id)).isTrue();
        db.jobs().markInterrupted(id, System.currentTimeMillis(), "{}");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cleanedUp.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(cleanedUp).isTrue();
        OpsJobRow job = db.jobs().findJob(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        List<OpsJobEventRow> events = db.jobs().events(id);
        assertThat(events.get(events.size() - 1).getType()).isEqualTo(OpsJobEventType.OPS_JOB_EVENT_RESULT);
        assertThat(events.get(events.size() - 1).getPayloadJson()).contains("\"aborted\":true");
    }

    @Test
    void 清扫器_心跳过期的作业按心跳值CAS删槽_改INTERRUPTED_追加事件_新鲜的不动() throws Exception {
        long now = System.currentTimeMillis();
        db.jobs().insertJob(OpsJobRow.newBuilder().setJobId(55).setIdemKey("k55").setKind(OpsJobKind.OPS_JOB_ROLLBACK)
                .setStatus(OpsJobStatus.OPS_JOB_RUNNING).setCreatedMs(now).build());
        db.jobs().insertEvent(OpsJobEventRow.newBuilder().setJobId(55).setSeq(1)
                .setType(OpsJobEventType.OPS_JOB_EVENT_STARTED).setPayloadJson("{}").setAtMs(now).build());
        db.jobs().insertActive(OpsActiveRow.newBuilder().setSlot(1).setJobId(55).setRunner("dead")
                .setHeartbeatMs(now).build());
        assertThat(runner.sweep()).isFalse();

        db.jdbc().update("UPDATE ops_active SET heartbeat_ms = ? WHERE slot = 1", now - 10_000);
        assertThat(runner.sweep()).isTrue();
        assertThat(db.jobs().active()).isEmpty();
        OpsJobRow job = db.jobs().findJob(55).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        assertThat(job.getResultCode()).isEqualTo("interrupted");
        List<OpsJobEventRow> events = db.jobs().events(55);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getSeq()).isEqualTo(2);
        assertThat(events.get(1).getType()).isEqualTo(OpsJobEventType.OPS_JOB_EVENT_INTERRUPTED);
        // 终态的作业不会被改回来
        assertThat(db.jobs().markInterrupted(55, now, "{}")).isFalse();
    }

    @Test
    void 作业体抛异常_FAILED_snapshot_db_error_照样收尾让槽() throws Exception {
        AtomicBoolean cleaned = new AtomicBoolean();
        Map<String, Object> accepted = service.submit(submission("k1", new JobBody() {
            @Override
            public JobResult run(JobContext ctx) {
                throw new IllegalStateException("库挂了");
            }

            @Override
            public void afterResult(JobContext ctx) {
                cleaned.set(true);
            }
        }));
        OpsJobRow job = await(Long.parseUnsignedLong((String) accepted.get("jobId")));
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_FAILED);
        assertThat(job.getResultCode()).isEqualTo("snapshot_db_error");
        assertThat(cleaned).isTrue();
    }

    @Test
    void 清扫器_改作业行或追加事件失败_整个事务回滚_槽还在_下一拍重来() {
        job(57, OpsJobStatus.OPS_JOB_RUNNING);
        slot(57, System.currentTimeMillis() - 10_000);
        // 让「追加 INTERRUPTED 事件」这一步失败：事件表暂时不在
        db.jdbc().execute("ALTER TABLE ops_job_event RENAME TO ops_job_event_off");
        try {
            assertThatThrownBy(runner::sweep).isInstanceOf(DataAccessException.class);
        } finally {
            db.jdbc().execute("ALTER TABLE ops_job_event_off RENAME TO ops_job_event");
        }
        // 槽删除与作业行改动一起回滚：不会出现「槽没了、作业行永远停在 RUNNING」
        assertThat(db.jobs().active()).map(OpsActiveRow::getJobId).contains(57L);
        assertThat(db.jobs().findJob(57).orElseThrow().getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_RUNNING);
        assertThat(jobsTotal("interrupted")).isZero();

        assertThat(runner.sweep()).isTrue();
        assertThat(db.jobs().active()).isEmpty();
        assertThat(db.jobs().findJob(57).orElseThrow().getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        assertThat(eventTypes(57)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED,
                OpsJobEventType.OPS_JOB_EVENT_INTERRUPTED);
        assertThat(jobsTotal("interrupted")).isEqualTo(1);
    }

    @Test
    void 清扫器_作业已终结只是槽没让出_只收回槽_不追加事件_不记中断() {
        job(56, OpsJobStatus.OPS_JOB_SUCCEEDED);
        slot(56, System.currentTimeMillis() - 10_000);

        assertThat(runner.sweep()).isTrue();

        assertThat(db.jobs().active()).isEmpty();
        assertThat(db.jobs().findJob(56).orElseThrow().getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(eventTypes(56)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED);
        assertThat(jobsTotal("interrupted")).isZero();
    }

    @Test
    void 排队期间已被清扫器中断_开始执行时不跑作业体_作业行保持INTERRUPTED_只追加RESULT_中断只计一次() {
        OpsJobRow queued = job(58, OpsJobStatus.OPS_JOB_QUEUED);
        slot(58, System.currentTimeMillis() - 10_000);
        assertThat(runner.sweep()).isTrue();
        assertThat(jobsTotal("interrupted")).isEqualTo(1);
        // 别的作业随即占了槽
        slot(77, System.currentTimeMillis());

        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean cleaned = new AtomicBoolean();
        runner.execute(queued, new JobBody() {
            @Override
            public JobResult run(JobContext ctx) {
                ran.set(true);
                return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of());
            }

            @Override
            public void afterResult(JobContext ctx) {
                cleaned.set(true);
            }
        });

        assertThat(ran).isFalse();
        assertThat(cleaned).isTrue();
        OpsJobRow job = db.jobs().findJob(58).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        assertThat(job.getStartedMs()).isZero();
        assertThat(eventTypes(58)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED,
                OpsJobEventType.OPS_JOB_EVENT_INTERRUPTED, OpsJobEventType.OPS_JOB_EVENT_RESULT);
        assertThat(db.jobs().events(58).get(2).getPayloadJson()).contains("\"aborted\":true");
        // 别人的槽不动；同一个中断不重复计数
        assertThat(db.jobs().active()).map(OpsActiveRow::getJobId).contains(77L);
        assertThat(jobsTotal("interrupted")).isEqualTo(1);
    }

    @Test
    void 开始执行时槽不属于它而作业行仍是QUEUED_不跑作业体_兜底改INTERRUPTED并计一次中断() {
        OpsJobRow queued = job(59, OpsJobStatus.OPS_JOB_QUEUED);
        slot(77, System.currentTimeMillis());
        AtomicBoolean ran = new AtomicBoolean();

        runner.execute(queued, ctx -> {
            ran.set(true);
            return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of());
        });

        assertThat(ran).isFalse();
        OpsJobRow job = db.jobs().findJob(59).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        assertThat(job.getResultCode()).isEqualTo("interrupted");
        assertThat(eventTypes(59)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED,
                OpsJobEventType.OPS_JOB_EVENT_RESULT);
        assertThat(db.jobs().active()).map(OpsActiveRow::getJobId).contains(77L);
        assertThat(jobsTotal("interrupted")).isEqualTo(1);
    }

    @Test
    void 取消标志不被执行线程覆盖_排队时的取消保留_重复取消仍报已请求_终结后报无效() {
        OpsJobRow queued = job(60, OpsJobStatus.OPS_JOB_QUEUED);
        slot(60, System.currentTimeMillis());
        assertThat(service.cancel(60).get("cancelRequested")).isEqualTo(true);
        AtomicBoolean seenCancel = new AtomicBoolean();
        Object[] secondCancel = new Object[1];

        runner.execute(queued, ctx -> {
            seenCancel.set(ctx.cancelRequested());
            // 已在写阶段的作业照常做完；运维再点一次取消（标志本来就是 1，useAffectedRows=true 下 UPDATE 数出 0 行）
            secondCancel[0] = service.cancel(60).get("cancelRequested");
            return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of("x", 1));
        });

        assertThat(seenCancel).isTrue();
        assertThat(secondCancel[0]).isEqualTo(true);
        OpsJobRow job = db.jobs().findJob(60).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        assertThat(job.getCancelRequested()).isTrue();
        assertThat(job.getRunner()).isEqualTo("test-runner");
        assertThat(job.getStartedMs()).isPositive();
        assertThat(job.getSummaryJson()).isEqualTo("{\"x\":1}");
        assertThat(db.jobs().active()).isEmpty();
        assertThat(jobsTotal("succeeded")).isEqualTo(1);
        assertThat(service.cancel(60).get("cancelRequested")).isEqualTo(false);
    }

    @Test
    void 作业已被清扫器终结后执行线程才写结局_不覆盖INTERRUPTED_只追加RESULT_不重复计数() {
        OpsJobRow queued = job(61, OpsJobStatus.OPS_JOB_QUEUED);
        slot(61, System.currentTimeMillis());

        runner.execute(queued, ctx -> {
            // 作业体跑完、心跳还没察觉之前，别的副本的清扫器收走了槽、改了作业行
            db.jobs().deleteActive(61);
            db.jobs().markInterrupted(61, System.currentTimeMillis(), "{}");
            return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of());
        });

        OpsJobRow job = db.jobs().findJob(61).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
        assertThat(eventTypes(61)).endsWith(OpsJobEventType.OPS_JOB_EVENT_RESULT);
        assertThat(jobsTotal("succeeded")).isZero();
    }

    @Test
    void 同一毫秒连续心跳_仍判定槽属于它() {
        slot(62, 1000);
        assertThat(db.jobs().heartbeat(62, 1000)).isTrue();
        assertThat(db.jobs().heartbeat(62, 1000)).isTrue();
        assertThat(db.jobs().active().orElseThrow().getHeartbeatMs()).isGreaterThan(1000);
        assertThat(db.jobs().heartbeat(63, 5000)).isFalse();
    }

    // ------------------------------------------------------------------ 审计 OPS-12：RESULT 写失败（T-A1）

    /** 作业表访问包一层：RESULT 事件的前 {@code failures} 次插入抛异常（{@code attempts} 记下试了几次）；其余事件与方法原样落库。 */
    private OpsJobStore resultWritesFailing(int failures, AtomicInteger attempts) {
        OpsJobStore real = db.jobs();
        OpsJobStore wrapped = mock(OpsJobStore.class, delegatesTo(real));
        doAnswer(inv -> {
            OpsJobEventRow row = inv.getArgument(0);
            if (row.getType() == OpsJobEventType.OPS_JOB_EVENT_RESULT && attempts.incrementAndGet() <= failures) {
                throw new DataAccessResourceFailureException("注入：RESULT 写不进 #" + attempts.get());
            }
            real.insertEvent(row);
            return null;
        }).when(wrapped).insertEvent(any());
        return wrapped;
    }

    /**
     * 作业体：立刻成功；收尾（RESULT 之后）先等 150 ms（让写 RESULT 那一刻可能已经在途的一拍心跳落定），再停留 450 ms（4 拍多心跳），
     * 记下这 450 ms 前后的心跳值。
     */
    private static final class LingeringCleanup implements JobBody {
        final long[] heartbeats = new long[2];
        final CountDownLatch cleaned = new CountDownLatch(1);
        private final OpsJobStore jobs;

        LingeringCleanup(OpsJobStore jobs) {
            this.jobs = jobs;
        }

        @Override
        public JobResult run(JobContext ctx) {
            return JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED, "ok", Map.of("x", 1));
        }

        @Override
        public void afterResult(JobContext ctx) {
            try {
                Thread.sleep(150);
                heartbeats[0] = jobs.active().orElseThrow().getHeartbeatMs();
                Thread.sleep(450);
                heartbeats[1] = jobs.active().orElseThrow().getHeartbeatMs();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cleaned.countDown();
            }
        }
    }

    private static double jobsTotal(SimpleMeterRegistry registry, String outcome) {
        Counter c = registry.find("xm.data.ops.jobs").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void RESULT一直写不进_预算用完就放弃_不让槽_停心跳_作业行留在RUNNING_收尾照做_清扫器接手改INTERRUPTED_中断只计一次() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OpsJobStore store = resultWritesFailing(Integer.MAX_VALUE, attempts);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor();
        // 重试预算调到 300 ms（生产 30 s）：第 1 次立刻、第 2 次 200 ms 后、第 3 次再过 400 ms（已过预算）放弃
        OpsJobRunner flaky = new OpsJobRunner(store, db.tx(), new ObjectMapper(), new DataMetrics(registry), Clock.systemUTC(),
                "test-runner", Duration.ofMillis(100), Duration.ofSeconds(60), Duration.ofMinutes(1), beats,
                Duration.ofMillis(300));
        OpsJobService accepting = new OpsJobService(store, ids, flaky, db.tx(), new ObjectMapper(), Clock.systemUTC());
        flaky.start();
        try {
            LingeringCleanup body = new LingeringCleanup(db.jobs());
            long id = Long.parseUnsignedLong((String) accepting.submit(submission("k1", body)).get("jobId"));
            assertThat(body.cleaned.await(10, TimeUnit.SECONDS)).as("RESULT 写不进也照样收尾（释放归属）").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (flaky.currentJobId().isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(flaky.currentJobId()).isEmpty();

            assertThat(attempts.get()).as("退避重试过，不是试一次就放弃").isBetween(2, 4);
            // 停心跳：收尾的 450 ms 里心跳值没再动（对照见下一个用例：RESULT 写进了的作业收尾期间心跳照常）
            assertThat(body.heartbeats[1]).isEqualTo(body.heartbeats[0]);
            // 不让槽、作业行不动：别的作业进不来，等清扫器接手
            assertThat(db.jobs().active()).map(OpsActiveRow::getJobId).contains(id);
            OpsJobRow stuck = db.jobs().findJob(id).orElseThrow();
            assertThat(stuck.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_RUNNING);
            assertThat(stuck.getFinishedMs()).isZero();
            assertThat(eventTypes(id)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED);
            assertThat(jobsTotal(registry, "succeeded")).as("作业行不是它改成终态的：不记结局").isZero();
            assertThatThrownBy(() -> accepting.submit(submission("k2", ctx -> JobResult.of(OpsJobStatus.OPS_JOB_SUCCEEDED,
                    "ok", Map.of())))).isInstanceOfSatisfying(OpsException.class, e -> {
                        assertThat(e.code()).isEqualTo("ops_busy");
                        assertThat(e.details().get("runningJobId")).isEqualTo(Long.toUnsignedString(id));
                    });
            assertThat(jobsTotal(registry, "interrupted")).isZero();

            // 心跳停了：过了 stale-after 之后清扫器（这里把心跳值改旧来模拟时间流逝）收走槽、改 INTERRUPTED、追加事件
            db.jdbc().update("UPDATE ops_active SET heartbeat_ms = ? WHERE slot = 1", System.currentTimeMillis() - 120_000);
            OpsJobRow swept = await(id);
            assertThat(swept.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_INTERRUPTED);
            assertThat(swept.getResultCode()).isEqualTo("interrupted");
            assertThat(db.jobs().active()).isEmpty();
            assertThat(eventTypes(id)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED,
                    OpsJobEventType.OPS_JOB_EVENT_INTERRUPTED);
            assertThat(jobsTotal(registry, "interrupted")).isEqualTo(1);
            assertThat(jobsTotal(registry, "succeeded")).isZero();
        } finally {
            flaky.stop();
            beats.shutdownNow();
        }
    }

    @Test
    void RESULT头两次写不进_退避重试第三次写进_照常终结让槽_只有一条RESULT_收尾期间心跳照常() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OpsJobStore store = resultWritesFailing(2, attempts);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor();
        // 公开构造器 = 生产的 30 s 重试预算
        OpsJobRunner flaky = new OpsJobRunner(store, db.tx(), new ObjectMapper(), new DataMetrics(registry), Clock.systemUTC(),
                "test-runner", Duration.ofMillis(100), Duration.ofSeconds(60), Duration.ofMinutes(1), beats);
        OpsJobService accepting = new OpsJobService(store, ids, flaky, db.tx(), new ObjectMapper(), Clock.systemUTC());
        flaky.start();
        try {
            LingeringCleanup body = new LingeringCleanup(db.jobs());
            long id = Long.parseUnsignedLong((String) accepting.submit(submission("k1", body)).get("jobId"));

            OpsJobRow job = await(id);

            assertThat(attempts).hasValue(3);
            assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
            assertThat(job.getSummaryJson()).isEqualTo("{\"x\":1}");
            assertThat(eventTypes(id)).containsExactly(OpsJobEventType.OPS_JOB_EVENT_STARTED,
                    OpsJobEventType.OPS_JOB_EVENT_RESULT);
            assertThat(db.jobs().active()).isEmpty();
            assertThat(jobsTotal(registry, "succeeded")).isEqualTo(1);
            // RESULT 之后、让槽之前（收尾释放归属的那段时间）心跳照常在走
            assertThat(body.cleaned.getCount()).isZero();
            assertThat(body.heartbeats[1]).isGreaterThan(body.heartbeats[0]);
        } finally {
            flaky.stop();
            beats.shutdownNow();
        }
    }
}
