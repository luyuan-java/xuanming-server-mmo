package com.game.data.ops;

import com.game.data.ops.OpsJobRunner.JobAbortedException;
import com.game.data.ops.pb.OpsJobEventRow;
import com.game.data.ops.pb.OpsJobEventType;
import com.game.data.ops.pb.OpsJobRow;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DuplicateKeyException;

/**
 * 一个正在执行的运维作业的上下文（只在 {@code data-ops} 线程上用；心跳线程只改两个 volatile 标志）。作业体经它追加事件、
 * 在阶段边界检查心跳丢失 / 取消 / 超时，并做可被中断的等待。
 */
public final class JobContext {

    /** 等待时每隔这么久检查一次心跳丢失。 */
    private static final long PAUSE_STEP_MS = 200;

    private final OpsJobRunner runner;
    private final long deadlineNanos;
    private final AtomicInteger seq = new AtomicInteger();
    /** 最新的作业行（执行线程读写）。 */
    volatile OpsJobRow job;
    private volatile boolean aborted;
    private volatile boolean heartbeatStopped;

    JobContext(OpsJobRunner runner, OpsJobRow job, long deadlineNanos) {
        this.runner = runner;
        this.job = job;
        this.deadlineNanos = deadlineNanos;
    }

    public long jobId() {
        return job.getJobId();
    }

    public String jobIdText() {
        return Long.toUnsignedString(job.getJobId());
    }

    public OpsJobRow job() {
        return job;
    }

    public String operator() {
        return job.getOperator();
    }

    public String reason() {
        return job.getReason();
    }

    public long nowMs() {
        return runner.clock().millis();
    }

    void initSeq(int maxSeq) {
        seq.set(maxSeq);
    }

    int nextSeq() {
        return seq.incrementAndGet();
    }

    /**
     * 追加一条事件（在调用方的事务里——有的话——否则自动提交）。写不进抛异常：ACCEPTED 事件写不进就零写入（§8.1），调用方据此停下。
     */
    public void event(OpsJobEventType type, Map<String, Object> payload) {
        String payloadJson = runner.boundedJson(payload);
        long at = nowMs();
        try {
            insertEvent(type, payloadJson, at);
        } catch (DuplicateKeyException e) {
            // 序号撞了（清扫器按库里的最大序号追加过事件）：按库里的最大序号重排、再试一次
            initSeq(runner.jobs().maxEventSeq(jobId()));
            insertEvent(type, payloadJson, at);
        }
        OpsJobRunner.audit.info("[OpsJob] {} job={} {}", type.name().substring("OPS_JOB_EVENT_".length()), jobIdText(),
                OpsJobRunner.printable(payloadJson));
    }

    private void insertEvent(OpsJobEventType type, String payloadJson, long at) {
        runner.jobs().insertEvent(OpsJobEventRow.newBuilder().setJobId(jobId()).setSeq(nextSeq()).setType(type)
                .setPayloadJson(payloadJson).setAtMs(at).build());
    }

    /** 尽力追加事件：写不进只记日志（PLANNED / CLAIMED / CHECK / WRITE 这类过程事件）。 */
    public void eventQuietly(OpsJobEventType type, Map<String, Object> payload) {
        try {
            event(type, payload);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(JobContext.class)
                    .warn("运维作业 job={} 追加 {} 事件失败（过程事件，继续）：{}", jobIdText(), type, e.toString());
        }
    }

    /** 心跳丢失（槽被清扫器收走）：之后不得再写任何玩家。 */
    public boolean aborted() {
        return aborted;
    }

    void abort() {
        aborted = true;
    }

    void stopHeartbeat() {
        heartbeatStopped = true;
    }

    boolean heartbeatStopped() {
        return heartbeatStopped;
    }

    /** 检查点：心跳丢失就抛 {@link JobAbortedException}。 */
    public void checkpoint() {
        if (aborted) {
            throw new JobAbortedException("作业 " + jobIdText() + " 的单飞槽已被清扫器收走");
        }
    }

    /** 作业时限（{@code xm.data.ops.job-timeout}）已到。 */
    public boolean timedOut() {
        return System.nanoTime() - deadlineNanos > 0;
    }

    /** 运维请求了取消（读库：取消请求可能落在别的副本上）。读失败按「没请求」。 */
    public boolean cancelRequested() {
        try {
            return runner.jobs().findJob(jobId()).map(OpsJobRow::getCancelRequested).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 等待（每 200 ms 检查一次心跳丢失）；被中断时恢复中断标志并按心跳丢失处理。 */
    public void pause(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (true) {
            checkpoint();
            long left = until - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(PAUSE_STEP_MS, Math.max(1, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobAbortedException("作业 " + jobIdText() + " 的执行线程被中断");
            }
        }
    }
}
