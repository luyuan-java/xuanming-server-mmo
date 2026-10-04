package com.game.gateway.queue;

import com.game.api.proto.GateNodeInfo;
import com.game.gateway.gate.GatePicker;
import com.game.gateway.gate.GateSource;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 排队放行循环（同 mmorpg loginqueue.Dispatcher）：只有拿到选主锁的那一个 xm-gateway 放行；每轮对每个开放、队列非空的区
 * 算预算（{@link QueueCapacity}，容量 − 在线），按 {@link #CHUNK} 个一段调 {@link LoginQueue#dispatch}——弹出与放行在同一段 Lua 里，
 * 空位（预算 − 未过期占位）也在脚本里算，直到队列空或空位用完。
 *
 * <p>与基线不同：基线一轮里每个人都按同一份 gate 目录挑「人数最少」的那台，整批都落到同一个 gate（人数每 5 s 才发布一次，
 * 接下来几轮也一样）；这里按段挑 gate，挑的时候把这一轮已分给它的人数算进去，一轮的放行摊在各 gate 上。
 *
 * <p>{@link #tick()} 由单个调度线程每秒调用（选主锁是 Redisson 的 RLock，归属于拿锁的线程，所以拿锁 / 放锁都在这个线程上）。
 */
public final class QueueDispatcher {

    private static final Logger log = LoggerFactory.getLogger(QueueDispatcher.class);

    /** 一段放行的人数：一段一次 Lua、落到同一个 gate。 */
    static final int CHUNK = 50;

    /** 选主：当前线程持有（或刚拿到）锁为 true。 */
    public interface Leadership {
        boolean holdOrAcquire();

        void release();
    }

    private final LoginQueue queue;
    private final QueueCapacity capacity;
    private final ZoneDirectory zones;
    private final GateSource gates;
    private final Leadership leadership;
    private final LongConsumer onAdmit;
    private boolean leader;

    /** @param onAdmit 每段放行后以放行人数调用（计数） */
    public QueueDispatcher(LoginQueue queue, QueueCapacity capacity, ZoneDirectory zones, GateSource gates,
                           Leadership leadership, LongConsumer onAdmit) {
        this.queue = queue;
        this.capacity = capacity;
        this.zones = zones;
        this.gates = gates;
        this.leadership = leadership;
        this.onAdmit = onAdmit;
    }

    /**
     * 一轮。不是主就什么都不做；任何异常只记日志（下一秒照常）。连 {@link Error} 也接住：从调度任务里抛出去会让
     * {@code scheduleWithFixedDelay} 悄悄停掉后续每一轮，而 Redisson 看门狗还在续选主锁——别的副本永远接不了手，全部区的排队停摆。
     */
    public void tick() {
        try {
            boolean nowLeader = leadership.holdOrAcquire();
            if (nowLeader != leader) {
                log.info(nowLeader ? "成为排队放行的主" : "不再是排队放行的主");
                leader = nowLeader;
            }
            if (nowLeader) {
                dispatchOnce();
            }
        } catch (RuntimeException e) {
            log.warn("排队放行这一轮出错，下一秒照常: {}", e.toString());
        } catch (Throwable t) {
            log.error("排队放行这一轮出现严重错误，下一秒照常", t);
        }
    }

    /** 停服：放掉选主锁（别的副本约一个锁 TTL 内接手；这里放掉让它立即接手）。 */
    public void stop() {
        if (leader) {
            try {
                leadership.release();
            } catch (RuntimeException e) {
                log.warn("释放排队放行选主锁失败: {}", e.toString());
            }
            leader = false;
        }
    }

    void dispatchOnce() {
        for (ZoneRow zone : zones.zones()) {
            if (zone.status() != ZoneManualStatus.OPEN) {
                continue;
            }
            try {
                dispatchZone(zone);
            } catch (RuntimeException e) {
                log.warn("排队放行出错 zone={}: {}", zone.zoneId(), e.toString());
            }
        }
    }

    private void dispatchZone(ZoneRow zone) {
        int zoneId = zone.zoneId();
        long queued = queue.queueLength(zoneId);
        if (queued == 0) {
            return;
        }
        List<GateNodeInfo> candidates = gates.listGates(zoneId);
        List<GateNodeInfo> eligible = GatePicker.eligible(zoneId, candidates);
        if (eligible.isEmpty()) {
            log.warn("排队中的区没有可放行的 gate zone={} 排队={}", zoneId, queued);
            return;
        }
        long budget = capacity.budget(zone, candidates);
        if (budget <= 0) {
            return;
        }
        // 这一轮已分给各 gate 的人数（gate 目录里的人数要等 gate 下次发布才含这些人）
        Map<Integer, Long> assigned = new HashMap<>();
        Comparator<GateNodeInfo> load = Comparator
                .comparingLong((GateNodeInfo g) -> Integer.toUnsignedLong(g.getPlayerCount())
                        + assigned.getOrDefault(g.getNodeId(), 0L))
                .thenComparingLong(g -> Integer.toUnsignedLong(g.getNodeId()));
        int admitted = 0;
        int expired = 0;
        // 上限：开头的队长按段数走完（之后新入队的下一轮再放）
        long chunks = queued / CHUNK + 1;
        for (long i = 0; i < chunks; i++) {
            GateNodeInfo gate = eligible.stream().min(load).orElseThrow();
            LoginQueue.Dispatched d = queue.dispatch(zoneId, budget, CHUNK, gate);
            admitted += d.admitted();
            expired += d.expired();
            if (d.admitted() > 0) {
                assigned.merge(gate.getNodeId(), (long) d.admitted(), Long::sum);
                onAdmit.accept(d.admitted());
            }
            if (d.popped() < CHUNK) {
                break;
            }
        }
        if (admitted > 0 || expired > 0) {
            log.info("排队放行 zone={} 放行={} 丢弃过期={} 预算={} 排队={} 分到 gate={}", zoneId, admitted, expired, budget, queued,
                    assigned);
        }
    }

    /** 测试用：当前是不是主。 */
    boolean isLeader() {
        return leader;
    }

    /** 测试用：总是主。 */
    static Leadership always() {
        return new Leadership() {
            @Override
            public boolean holdOrAcquire() {
                return true;
            }

            @Override
            public void release() {
            }
        };
    }

    /** 测试用：按给定条件当主。 */
    static Leadership when(BooleanSupplier condition) {
        return new Leadership() {
            @Override
            public boolean holdOrAcquire() {
                return condition.getAsBoolean();
            }

            @Override
            public void release() {
            }
        };
    }
}
