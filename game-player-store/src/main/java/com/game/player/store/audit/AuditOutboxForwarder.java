package com.game.player.store.audit;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计发件箱分片转发器（PARITY 2026-11-12 R13）：
 * <ul>
 *   <li>按 64 片 HRW（{@link WorkShard}）在稳定副本间互斥分工（无成员或过期时回落全片）；</li>
 *   <li>支持部署期不停服暂停开关（{@link #setPaused(boolean)}），审计 topic 换代时可随时停发、恢复后自动追平；</li>
 *   <li>严格先等待 Kafka {@code acks=all} broker 确认，成功后再按主键删除；投递失败留行重试绝不丢数；</li>
 *   <li>损坏载荷校验不通过时计坏行并跳过清理，不阻塞同片后续正常流水；</li>
 *   <li>维护低基数可观测计数（积压最老年龄、转发成功、坏行跳过、暂停态、负责分片数）与 {@link LoopLiveness} 循环活性。</li>
 * </ul>
 */
public final class AuditOutboxForwarder {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxForwarder.class);

    public interface Backend {
        List<AuditOutboxEntry> pollDue(WorkShard.SqlFilter filter, int limit) throws Exception;

        void deleteByIds(List<Long> outboxIds) throws Exception;

        long oldestCreatedMs(WorkShard.SqlFilter filter) throws Exception;
    }

    /** 下游 Kafka 发送接口（必须开启 acks=all，返回即代表 broker 多数派确认落盘；异常表示未确认）。 */
    public interface Publisher {
        void publishSync(AuditOutboxEntry entry) throws Exception;
    }

    /** 载荷结构校验器（返回 false 表示永久损坏的坏行，记计数并摘除防队头阻塞）。 */
    @FunctionalInterface
    public interface PayloadValidator {
        boolean isValid(AuditOutboxEntry entry);
    }

    private final Backend backend;
    private final Publisher publisher;
    private final PayloadValidator validator;
    private final WorkShard.Membership membership;
    private final int batchLimit;
    private final LongSupplier clockMs;
    private final LoopLiveness liveness;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong forwardedCount = new AtomicLong();
    private final AtomicLong badRowsSkipped = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong oldestBacklogAgeSeconds = new AtomicLong();
    private final AtomicLong ownedShardsGauge = new AtomicLong();

    public AuditOutboxForwarder(Backend backend, Publisher publisher, PayloadValidator validator,
                                WorkShard.Membership membership, int batchLimit,
                                LongSupplier clockMs, LoopLiveness liveness) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.validator = validator == null ? e -> e.payload().length > 0 : validator;
        this.membership = Objects.requireNonNull(membership, "membership");
        this.batchLimit = Math.max(1, batchLimit);
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.liveness = liveness;
    }

    public void setPaused(boolean paused) {
        this.paused.set(paused);
    }

    public boolean isPaused() {
        return paused.get();
    }

    public long forwardedCount() {
        return forwardedCount.get();
    }

    public long badRowsSkipped() {
        return badRowsSkipped.get();
    }

    public long publishFailures() {
        return publishFailures.get();
    }

    public long oldestBacklogAgeSeconds() {
        return oldestBacklogAgeSeconds.get();
    }

    public long ownedShards() {
        return ownedShardsGauge.get();
    }

    /** 执行一轮分片转发；由定时任务周期驱动，通过 {@link LoopLiveness#runGuarded} 兜底异常并上报活性。 */
    public int runOnce() {
        AtomicInteger forwarded = new AtomicInteger();
        Runnable round = () -> forwarded.set(doRunOnce());
        if (liveness != null) {
            liveness.runGuarded(round);
        } else {
            round.run();
        }
        return forwarded.get();
    }

    private int doRunOnce() {
        long nowMs = clockMs.getAsLong();
        WorkShard.SqlFilter filter = membership.sqlFilter(PlayerAuditOutboxStore.SHARD_KIND, "shard");
        ownedShardsGauge.set(membership.ownedCount(PlayerAuditOutboxStore.SHARD_KIND));
        if (filter.isNone()) {
            oldestBacklogAgeSeconds.set(0L);
            return 0;
        }
        try {
            long oldest = backend.oldestCreatedMs(filter);
            oldestBacklogAgeSeconds.set(oldest > 0L && nowMs > oldest ? (nowMs - oldest) / 1000L : 0L);
        } catch (Exception e) {
            log.warn("audit_outbox_backlog_probe_failed err={}", e.getMessage());
        }
        if (paused.get()) {
            return 0;
        }
        int forwarded = 0;
        try {
            List<AuditOutboxEntry> due = backend.pollDue(filter, batchLimit);
            List<Long> ackedIds = new ArrayList<>(due.size());
            for (AuditOutboxEntry entry : due) {
                if (!validator.isValid(entry)) {
                    badRowsSkipped.incrementAndGet();
                    ackedIds.add(entry.outboxId());
                    log.error("audit_outbox_bad_row_skipped outbox_id={} player={} kind={}",
                            entry.outboxId(), Long.toUnsignedString(entry.playerId()), entry.kind());
                    continue;
                }
                try {
                    publisher.publishSync(entry);
                    ackedIds.add(entry.outboxId());
                    forwarded++;
                    forwardedCount.incrementAndGet();
                } catch (Exception pubErr) {
                    publishFailures.incrementAndGet();
                    log.warn("audit_outbox_publish_failed outbox_id={} player={} err={}",
                            entry.outboxId(), Long.toUnsignedString(entry.playerId()), pubErr.getMessage());
                    break;
                }
            }
            if (!ackedIds.isEmpty()) {
                backend.deleteByIds(ackedIds);
            }
        } catch (Exception e) {
            throw new IllegalStateException("audit outbox forward round failed", e);
        }
        return forwarded;
    }
}
