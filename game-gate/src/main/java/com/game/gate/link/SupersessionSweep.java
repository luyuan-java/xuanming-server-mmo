package com.game.gate.link;

import com.game.api.proto.SceneNodeInfo;
import java.util.List;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 与建链寻址共用执行器，扫描至多一笔排队或在途；目录短暂失败不能误踢现有会话。 */
public final class SupersessionSweep implements Runnable, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SupersessionSweep.class);
    private final Supplier<List<SceneNodeInfo>> directory;
    private final SceneLinkManager links;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private ScheduledFuture<?> timer;
    private volatile boolean closed;
    public SupersessionSweep(Supplier<List<SceneNodeInfo>> directory, SceneLinkManager links) {
        this.directory = directory; this.links = links;
    }
    @Override public void run() {
        if (closed) return;
        try {
            var entries = directory.get();
            if (!closed) links.inspectDirectory(entries);
        }
        catch (RuntimeException failure) {
            if (!closed) links.directoryUnavailable();
            log.warn("scene 链路换代扫描读取目录失败，保留现有链路", failure);
        }
    }

    public synchronized void start(ScheduledExecutorService scheduler, Executor resolveExecutor, Duration period) {
        if (timer != null || closed) throw new IllegalStateException("换代扫描已启动或关闭");
        if (period.toMillis() < 1) throw new IllegalArgumentException("扫描间隔不得短于 1ms");
        timer = scheduler.scheduleWithFixedDelay(() -> {
            // 宽限清理只读内存，不依赖 Redis 扫描完成；目录阻塞时旧进程也不能无限续命。
            if (!closed) links.expireSuperseded();
            if (closed || !inFlight.compareAndSet(false, true)) return;
            try {
                resolveExecutor.execute(() -> { try { run(); } finally { inFlight.set(false); } });
            } catch (RejectedExecutionException rejected) { inFlight.set(false); }
        }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void close() {
        closed = true;
        if (timer != null) { timer.cancel(false); timer = null; }
    }
}
