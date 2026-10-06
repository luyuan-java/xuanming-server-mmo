package com.game.data.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运维作业的执行器（data-ops-spec §7.4）：{@code data-ops} 单线程执行（与全集群单飞一致，阻塞 JDBC / Redis 可以——它不是 Netty I/O
 * 或场景逻辑线程，AGENTS.md §3）；{@code data-ops-fence} 线程每 {@code heartbeat} 更新 {@code ops_active.heartbeat_ms}；
 * {@code data-ops-sweeper} 线程（每个副本都跑）把心跳超过 {@code staleAfter} 的作业在一个事务里按心跳值 CAS 删掉单飞槽、改成 INTERRUPTED、
 * 追加 INTERRUPTED 事件（作业已终结、只是槽没让出的，只收回槽）。
 *
 * <ul>
 *   <li><b>作业不绑定 HTTP 请求</b>：受理线程提交后就返回 202，调用方断开不影响执行（对应基线的脱钩 ctx）。</li>
 *   <li><b>开始执行先核对</b>：一个事务里刷新心跳（槽仍属于它）并 QUEUED → RUNNING；排队期间已被清扫器中断的作业一步也不执行。</li>
 *   <li><b>心跳丢失即自停</b>：槽被清扫器收走后 {@link JobContext#aborted()} 为真，作业体在下一个检查点抛 {@link JobAbortedException}，
 *       之后不再写任何玩家；作业行已由清扫器改成 INTERRUPTED，这里不再覆盖，只追加一条 RESULT 事件留痕（作业行还没终结就兜底改成 INTERRUPTED）。</li>
 *   <li><b>作业行按列、带状态条件更新</b>（{@link OpsJobStore#markRunning} / {@link OpsJobStore#finishJob}）：不覆盖受理线程改的取消标志，
 *       不把清扫器写的 INTERRUPTED 改回去；谁把作业行改成终态谁记 {@code jobs_total}，同一作业不重复计数。</li>
 *   <li><b>RESULT</b> 在执行线程自己的事务里写（与终态作业行同事务），失败重试至多 {@link #RESULT_RETRY_BUDGET}；仍失败只记 ERROR、
 *       不让出单飞槽（停止心跳，由清扫器改 INTERRUPTED 并告警）。RESULT 之后才调 {@link JobBody#afterResult}（释放归属：
 *       「栅栏持到 RESULT 之后」，同基线 {@code rollback_logic.go:137-139}）。</li>
 *   <li><b>不自动续跑</b>：中断的作业由人看明细决定、用新幂等键重提。</li>
 * </ul>
 */
public final class OpsJobRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OpsJobRunner.class);
    /** 运维作业审计（data-ops-spec §8.4）。 */
    static final Logger audit = LoggerFactory.getLogger("xm.audit.ops");

    /** RESULT 事件的重试预算（同基线 {@code rollback_logic.go:106-111} 的 30 s 上限）。 */
    static final Duration RESULT_RETRY_BUDGET = Duration.ofSeconds(30);
    /** 事件载荷的上限（ops_job_event.payload_json ≤ 64 KB）。 */
    static final int MAX_PAYLOAD = 60_000;
    /** 停服时等在跑的作业多久（作业不会在这个时限内做完：之后由清扫器改成 INTERRUPTED）。 */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    /** 作业体：在 {@code data-ops} 线程上执行。 */
    public interface JobBody {

        /** 执行；抛异常按 FAILED（{@code snapshot_db_error}）处理，{@link JobAbortedException} 按中断处理。 */
        JobResult run(JobContext ctx) throws Exception;

        /** RESULT 写完之后（无论成败、是否中断）：释放归属等。不抛异常。 */
        default void afterResult(JobContext ctx) {
        }
    }

    /** 心跳丢失（槽已被清扫器收走）：作业体必须立即停下、不再写任何玩家。 */
    public static final class JobAbortedException extends RuntimeException {
        public JobAbortedException(String message) {
            super(message);
        }
    }

    /**
     * 作业结局。
     *
     * @param summary 摘要（进 ops_job.summary_json 与 RESULT 事件）
     */
    public record JobResult(OpsJobStatus status, String resultCode, int playersPlanned, int playersAffected,
                            int playersFailed, int divergenceRows, int unprovablePlayers, boolean acceptedDivergence,
                            boolean acceptedRecallReversal, Map<String, Object> summary) {

        public static JobResult of(OpsJobStatus status, String resultCode, Map<String, Object> summary) {
            return new JobResult(status, resultCode, 0, 0, 0, 0, 0, false, false, summary);
        }
    }

    private final OpsJobStore jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final DataMetrics metrics;
    private final Clock clock;
    private final String runner;
    private final Duration heartbeat;
    private final Duration staleAfter;
    private final Duration jobTimeout;
    /** RESULT 写不进时最多重试多久；生产恒为 {@link #RESULT_RETRY_BUDGET}，只有测试经包内构造器调小。 */
    private final Duration resultRetryBudget;
    private final ExecutorService executor;
    private final ScheduledExecutorService fence;
    private final ScheduledExecutorService sweeper;
    private final AtomicInteger queued = new AtomicInteger();
    private volatile JobContext current;
    private volatile boolean running;

    /**
     * @param fence 心跳在它上面跑（同一线程还跑栅栏续约，{@code AdminOwnership}）；由装配方创建与关闭
     */
    public OpsJobRunner(OpsJobStore jobs, TransactionTemplate tx, ObjectMapper json, DataMetrics metrics, Clock clock,
                        String runner, Duration heartbeat, Duration staleAfter, Duration jobTimeout,
                        ScheduledExecutorService fence) {
        this(jobs, tx, json, metrics, clock, runner, heartbeat, staleAfter, jobTimeout, fence, RESULT_RETRY_BUDGET);
    }

    /**
     * 包内接缝（只给测试用）：{@code resultRetryBudget} 可调小，免得「RESULT 一直写不进」的用例等满 30 s。
     * 生产装配只走上面的公开构造器，重试预算恒为 {@link #RESULT_RETRY_BUDGET}。
     */
    OpsJobRunner(OpsJobStore jobs, TransactionTemplate tx, ObjectMapper json, DataMetrics metrics, Clock clock,
                 String runner, Duration heartbeat, Duration staleAfter, Duration jobTimeout,
                 ScheduledExecutorService fence, Duration resultRetryBudget) {
        if (resultRetryBudget == null || resultRetryBudget.isNegative()) {
            throw new IllegalArgumentException("RESULT 重试预算不能为负：" + resultRetryBudget);
        }
        this.resultRetryBudget = resultRetryBudget;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
        this.runner = runner;
        this.heartbeat = heartbeat;
        this.staleAfter = staleAfter;
        this.jobTimeout = jobTimeout;
        this.fence = fence;
        this.executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("data-ops").daemon(true).factory());
        this.sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("data-ops-sweeper").daemon(true).factory());
        metrics.registerOps();
    }

    public String runnerId() {
        return runner;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void start() {
        running = true;
        long beat = heartbeat.toMillis();
        fence.scheduleWithFixedDelay(this::beat, beat, beat, TimeUnit.MILLISECONDS);
        sweeper.scheduleWithFixedDelay(this::sweepQuietly, beat, beat, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        sweeper.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                JobContext ctx = current;
                if (ctx != null) {
                    // 不打断执行线程（打断会让一笔写档事务半途失败）；进程退出后心跳停止，清扫器把它改成 INTERRUPTED
                    log.warn("停服时运维作业 job={} 仍在执行：不等它做完；心跳停止后由清扫器改成 INTERRUPTED", ctx.jobIdText());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 停机次序（Spring 按阶段<b>从大到小</b>停，{@code DefaultLifecycleProcessor.stopBeans}）：Web 服务器（优雅停机
     * {@code DEFAULT_PHASE − 1024}、启停 {@code DEFAULT_PHASE − 2048}）&gt; 本执行器（{@code OpsIds.PHASE + 1}）&gt; 发号租约
     * （{@link OpsIds#PHASE}）。即：Web 服务器先停（不再受理新作业）→ 本执行器停（停清扫、等在跑的作业至多 {@link #SHUTDOWN_WAIT}）
     * → 最后交还发号租约（等待的这段时间里在跑的作业还能发号）。启动次序相反。{@code OpsLifecycleOrderTest} 钉住。
     */
    @Override
    public int getPhase() {
        return OpsIds.PHASE + 1;
    }

    // ------------------------------------------------------------------ 提交与执行

    /** 本实例正在执行的作业（排障用）；空闲为空。 */
    public Optional<Long> currentJobId() {
        JobContext ctx = current;
        return ctx == null ? Optional.empty() : Optional.of(ctx.jobId());
    }

    /**
     * 提交一个已受理（{@code ops_active} + {@code ops_job} QUEUED + STARTED 事件已提交）的作业。执行器已关时抛
     * {@link RejectedExecutionException}，调用方把作业改成 FAILED 并让出单飞槽。
     */
    public void submit(OpsJobRow job, JobBody body) {
        queued.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    execute(job, body);
                } finally {
                    queued.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            throw e;
        }
    }

    /** RESULT 落库的结局。 */
    private enum ResultWrite {
        /** RESULT 事件 + 作业行终态都是本线程写的（本线程负责记作业结局指标）。 */
        FINALIZED,
        /** 只追加了 RESULT 事件：作业行已由别人（清扫器）改成终态，不覆盖、不重复记指标。 */
        EVENT_ONLY,
        /** 重试预算内没写进：不让槽、停心跳，由清扫器改 INTERRUPTED 并记指标。 */
        NOT_WRITTEN
    }

    /** 在调用线程上执行（测试与 {@link #submit} 共用）。 */
    void execute(OpsJobRow queuedJob, JobBody body) {
        long startNanos = System.nanoTime();
        JobContext ctx = new JobContext(this, queuedJob, startNanos + jobTimeout.toNanos());
        current = ctx;
        metrics.opsJobRunning(true);
        String kind = kindName(queuedJob);
        JobResult result;
        try {
            ctx.initSeq(jobs.maxEventSeq(queuedJob.getJobId()));
            long startedMs = clock.millis();
            if (!start(ctx.jobId(), startedMs)) {
                // 排队期间心跳过期被清扫器中断（槽已收走、作业行已是 INTERRUPTED）：一步也不执行，免得在别的作业占着槽时夺权 / 踢人
                ctx.abort();
                throw new JobAbortedException("作业 " + ctx.jobIdText() + " 开始执行时已不占有单飞槽或已不是 QUEUED：不执行");
            }
            ctx.job = queuedJob.toBuilder().setStatus(OpsJobStatus.OPS_JOB_RUNNING).setStartedMs(startedMs)
                    .setRunner(runner).build();
            result = body.run(ctx);
        } catch (JobAbortedException e) {
            log.error("运维作业 job={} 心跳丢失、已被清扫器改成 INTERRUPTED：执行线程自停（{}）", ctx.jobIdText(), e.getMessage());
            result = JobResult.of(OpsJobStatus.OPS_JOB_INTERRUPTED, "interrupted", Map.of("message", e.getMessage()));
        } catch (Exception | Error e) {
            log.error("运维作业 job={} 执行失败", ctx.jobIdText(), e);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("message", String.valueOf(e.getMessage()));
            summary.put("exception", e.getClass().getName());
            result = JobResult.of(OpsJobStatus.OPS_JOB_FAILED, OpsException.SNAPSHOT_DB_ERROR, summary);
        }
        ResultWrite written = writeResult(ctx, result);
        try {
            body.afterResult(ctx);
        } catch (RuntimeException e) {
            log.error("运维作业 job={} 收尾（释放归属）出错", ctx.jobIdText(), e);
        }
        if (written != ResultWrite.NOT_WRITTEN) {
            // 只删属于本作业的那一行：槽早被收走（心跳丢失）时是空操作；开始执行就被拒、槽仍归本作业时把它让出来
            try {
                jobs.deleteActive(ctx.jobId());
            } catch (RuntimeException e) {
                log.error("运维作业 job={} 让出单飞槽失败（心跳停止后由清扫器收走）", ctx.jobIdText(), e);
            }
        }
        current = null;
        metrics.opsJobRunning(false);
        String outcome = ctx.aborted() ? "interrupted" : outcomeName(result.status());
        if (written == ResultWrite.FINALIZED) {
            // 谁把作业行改成终态谁记结局指标：清扫器改的由清扫器记，同一个作业不重复计数
            metrics.opsJob(kind, outcome, System.nanoTime() - startNanos);
        }
        audit.info("[OpsJob] RESULT job={} kind={} status={} code={} planned={} affected={} failed={} row={} operator={}",
                ctx.jobIdText(), kind, result.status().name(), result.resultCode(), result.playersPlanned(),
                result.playersAffected(), result.playersFailed(), written.name().toLowerCase(Locale.ROOT),
                printable(queuedJob.getOperator()));
    }

    /**
     * 开始执行：一个事务里刷新心跳（槽必须仍属于这个作业）并把作业行 QUEUED → RUNNING。任一不成立 → 回滚、返回 false。
     * 心跳刷新之后清扫器至少 {@code staleAfter} 内不会收走它，所以作业体不会在「槽已被收走、第一拍心跳还没到」的窗口里夺权 / 踢人。
     */
    private boolean start(long jobId, long startedMs) {
        Boolean ok = tx.execute(status -> {
            if (jobs.heartbeat(jobId, startedMs) && jobs.markRunning(jobId, startedMs, runner)) {
                return true;
            }
            status.setRollbackOnly();
            return false;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * RESULT 事件 + 终态作业行（一个事务），失败重试至多 {@link #RESULT_RETRY_BUDGET}。作业行按列、带状态条件写（不覆盖取消标志，
     * 不改回已被清扫器写成的 INTERRUPTED）。心跳丢失时：作业行本应已被清扫器改成 INTERRUPTED；还没终结（清扫器没改到）就由这里兜底改成
     * INTERRUPTED，免得作业行永远停在 RUNNING。
     */
    private ResultWrite writeResult(JobContext ctx, JobResult r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", r.status().name());
        payload.put("code", r.resultCode());
        payload.put("playersPlanned", r.playersPlanned());
        payload.put("playersAffected", r.playersAffected());
        payload.put("playersFailed", r.playersFailed());
        payload.put("divergenceRows", r.divergenceRows());
        payload.put("unprovablePlayers", r.unprovablePlayers());
        payload.put("acceptedDivergence", r.acceptedDivergence());
        payload.put("acceptedRecallReversal", r.acceptedRecallReversal());
        payload.put("summary", r.summary());
        if (ctx.aborted()) {
            payload.put("aborted", true);
        }
        String payloadJson = boundedJson(payload);
        long deadline = System.nanoTime() + resultRetryBudget.toNanos();
        long backoffMs = 200;
        for (int attempt = 1; ; attempt++) {
            try {
                long now = clock.millis();
                OpsJobRow finished = ctx.job.toBuilder()
                        .setStatus(r.status())
                        .setResultCode(r.resultCode())
                        .setPlayersPlanned(r.playersPlanned())
                        .setPlayersAffected(r.playersAffected())
                        .setPlayersFailed(r.playersFailed())
                        .setDivergenceRows(r.divergenceRows())
                        .setUnprovablePlayers(r.unprovablePlayers())
                        .setAcceptedDivergence(r.acceptedDivergence())
                        .setAcceptedRecallReversal(r.acceptedRecallReversal())
                        .setFinishedMs(now)
                        .setSummaryJson(boundedJson(r.summary()))
                        .build();
                int seq = ctx.nextSeq();
                boolean aborted = ctx.aborted();
                Boolean finalized = tx.execute(status -> {
                    jobs.insertEvent(OpsJobEventRow.newBuilder().setJobId(ctx.jobId()).setSeq(seq)
                            .setType(OpsJobEventType.OPS_JOB_EVENT_RESULT).setPayloadJson(payloadJson).setAtMs(now).build());
                    return aborted ? jobs.markInterrupted(ctx.jobId(), now, payloadJson) : jobs.finishJob(finished);
                });
                if (Boolean.TRUE.equals(finalized)) {
                    if (aborted) {
                        log.error("运维作业 job={} 心跳丢失但作业行还没终结（清扫器没改到）：由执行线程兜底改成 INTERRUPTED", ctx.jobIdText());
                    } else {
                        ctx.job = finished;
                    }
                    return ResultWrite.FINALIZED;
                }
                if (!aborted) {
                    log.warn("运维作业 job={} 的作业行已被改成终态（清扫器在心跳检查之前收走了它）：执行线程的结局只追加为 RESULT 事件，"
                            + "不覆盖", ctx.jobIdText());
                }
                return ResultWrite.EVENT_ONLY;
            } catch (DuplicateKeyException e) {
                // 事件序号撞了（清扫器按库里的最大序号追加过 INTERRUPTED）：按库里的最大序号重排，立即重试
                if (System.nanoTime() > deadline) {
                    log.error("运维作业 job={} 的 RESULT 写不进（{} 次重试后放弃）：结果={}", ctx.jobIdText(), attempt, payloadJson, e);
                    ctx.stopHeartbeat();
                    return ResultWrite.NOT_WRITTEN;
                }
                resyncSeq(ctx);
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    log.error("运维作业 job={} 的 RESULT 写不进（{} 次重试后放弃）：作业留在 RUNNING，由清扫器改成 INTERRUPTED；结果={}",
                            ctx.jobIdText(), attempt, payloadJson, e);
                    ctx.stopHeartbeat();
                    return ResultWrite.NOT_WRITTEN;
                }
                log.warn("运维作业 job={} 写 RESULT 失败（第 {} 次），{} ms 后重试：{}", ctx.jobIdText(), attempt, backoffMs,
                        e.toString());
                sleepQuietly(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 5000);
            }
        }
    }

    // ------------------------------------------------------------------ 心跳与清扫

    private void beat() {
        JobContext ctx = current;
        if (ctx == null || ctx.heartbeatStopped()) {
            return;
        }
        try {
            if (!jobs.heartbeat(ctx.jobId(), clock.millis())) {
                ctx.abort();
                log.error("运维作业 job={} 的单飞槽已不属于它（被清扫器收走）：执行线程将在下一个检查点自停、不再写任何玩家",
                        ctx.jobIdText());
            }
        } catch (RuntimeException e) {
            log.warn("运维作业 job={} 心跳失败（超过 {} 未更新会被清扫器中断）：{}", ctx.jobIdText(), staleAfter, e.toString());
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("运维作业清扫失败（下一拍重试）：{}", e.toString());
        }
    }

    /** 一次清扫的结局。 */
    enum Sweep {
        /** 没有要收的（空闲、心跳新鲜、或 CAS 时心跳又更新过）。 */
        NONE,
        /** 作业已终结、只是槽没让出（让槽失败或收尾期间停机）：只收回槽，不追加事件、不记中断。 */
        RECLAIMED,
        /** 未终结的作业：槽收回 + 作业行改 INTERRUPTED + INTERRUPTED 事件（同一事务）。 */
        INTERRUPTED
    }

    /** 清扫撞事件序号（与仍活着的执行线程同时追加事件）时整个事务重来的次数。 */
    static final int SWEEP_ATTEMPTS = 3;

    /**
     * 心跳过期的作业，<b>一个事务</b>：按心跳值 CAS 删除单飞槽 → 作业行改 INTERRUPTED（只改未终结的）→ 改到了才追加 INTERRUPTED 事件。
     * 任一步失败整体回滚（槽还在，下一拍重来），不会出现「槽没了、作业行永远停在 RUNNING」。返回是否收走了一个槽。
     */
    boolean sweep() {
        Optional<OpsActiveRow> active = jobs.active();
        if (active.isEmpty()) {
            return false;
        }
        OpsActiveRow row = active.get();
        long now = clock.millis();
        if (row.getHeartbeatMs() >= now - staleAfter.toMillis()) {
            return false;
        }
        String jobId = Long.toUnsignedString(row.getJobId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sweptBy", runner);
        payload.put("runner", row.getRunner());
        payload.put("heartbeatMs", row.getHeartbeatMs());
        payload.put("staleAfterMs", staleAfter.toMillis());
        payload.put("note", "执行实例心跳超时：作业中断、不自动续跑；已写的玩家完整（每人一个事务），没来得及复查的已写玩家转人工");
        String payloadJson = boundedJson(payload);
        Sweep outcome;
        for (int attempt = 1; ; attempt++) {
            try {
                outcome = tx.execute(status -> {
                    if (!jobs.sweepActive(row.getJobId(), row.getHeartbeatMs())) {
                        return Sweep.NONE;
                    }
                    if (!jobs.markInterrupted(row.getJobId(), now, payloadJson)) {
                        return Sweep.RECLAIMED;
                    }
                    jobs.insertEvent(OpsJobEventRow.newBuilder().setJobId(row.getJobId())
                            .setSeq(jobs.maxEventSeq(row.getJobId()) + 1).setType(OpsJobEventType.OPS_JOB_EVENT_INTERRUPTED)
                            .setPayloadJson(payloadJson).setAtMs(now).build());
                    return Sweep.INTERRUPTED;
                });
                break;
            } catch (DuplicateKeyException e) {
                // 执行线程还活着、刚追加了同序号的事件：整个事务已回滚（槽还在），按新的最大序号重来
                if (attempt >= SWEEP_ATTEMPTS) {
                    throw e;
                }
            }
        }
        if (outcome == null || outcome == Sweep.NONE) {
            return false;
        }
        if (outcome == Sweep.RECLAIMED) {
            log.warn("运维作业 job={}（实例 {}）已终结但单飞槽没让出、心跳超过 {}：已收回单飞槽（作业结局不变）", jobId, row.getRunner(),
                    staleAfter);
            audit.info("[OpsJob] RECLAIMED job={} runner={} sweptBy={}", jobId, row.getRunner(), runner);
            return true;
        }
        log.error("运维作业 job={}（实例 {}）心跳超过 {} 未更新：已改成 INTERRUPTED、收走单飞槽（告警：人工看明细决定是否用新幂等键重提）",
                jobId, row.getRunner(), staleAfter);
        audit.info("[OpsJob] INTERRUPTED job={} runner={} sweptBy={}", jobId, row.getRunner(), runner);
        String kind;
        try {
            kind = jobs.findJob(row.getJobId()).map(OpsJobRunner::kindName).orElse("unknown");
        } catch (RuntimeException e) {
            kind = "unknown";
        }
        metrics.opsJob(kind, "interrupted", 0);
        return true;
    }

    /** 事件序号按库里的最大值重排（与清扫器撞号之后）。读失败就留着本地计数，下一次重试再撞再来。 */
    private void resyncSeq(JobContext ctx) {
        try {
            ctx.initSeq(jobs.maxEventSeq(ctx.jobId()));
        } catch (RuntimeException e) {
            log.warn("运维作业 job={} 重排事件序号失败：{}", ctx.jobIdText(), e.toString());
        }
    }

    // ------------------------------------------------------------------ 工具

    String boundedJson(Object value) {
        String text;
        try {
            text = json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            text = "{\"error\":\"摘要序列化失败\"}";
        }
        if (text.length() <= MAX_PAYLOAD) {
            return text;
        }
        try {
            return json.writeValueAsString(Map.of("truncated", true, "bytes", text.length(),
                    "head", text.substring(0, MAX_PAYLOAD / 2)));
        } catch (JsonProcessingException e) {
            return "{\"truncated\":true}";
        }
    }

    OpsJobStore jobs() {
        return jobs;
    }

    TransactionTemplate tx() {
        return tx;
    }

    Clock clock() {
        return clock;
    }

    static String kindName(OpsJobRow job) {
        String name = job.getKind().name();
        return name.startsWith("OPS_JOB_") ? name.substring("OPS_JOB_".length()).toLowerCase(Locale.ROOT)
                : name.toLowerCase(Locale.ROOT);
    }

    static String outcomeName(OpsJobStatus status) {
        String name = status.name();
        return name.startsWith("OPS_JOB_") ? name.substring("OPS_JOB_".length()).toLowerCase(Locale.ROOT)
                : name.toLowerCase(Locale.ROOT);
    }

    static String printable(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", "?");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
