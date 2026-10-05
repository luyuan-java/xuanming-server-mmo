package com.game.data.testing;

import com.game.data.ops.OpsIds;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 测试用号源：替身租约（worker 固定、有效性可切换），已领到租约才返回。 */
public final class TestIds {

    /** 替身租约。 */
    public static final class FakeLease implements OpsIds.WorkerLease {
        public final int worker;
        public final AtomicBoolean valid = new AtomicBoolean(true);
        public final AtomicBoolean lost = new AtomicBoolean();
        public final AtomicBoolean closed = new AtomicBoolean();
        public volatile Runnable onLost;

        public FakeLease(int worker) {
            this.worker = worker;
        }

        @Override
        public int workerId() {
            return worker;
        }

        @Override
        public boolean isValid() {
            return valid.get() && !lost.get();
        }

        @Override
        public boolean isLost() {
            return lost.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        /** 模拟确认丢失（同 NodeIdLease 的 onLost 回调）。 */
        public void lose() {
            lost.set(true);
            onLost.run();
        }
    }

    private TestIds() {
    }

    /** 一个已经领到替身租约（worker = {@code worker}）的号源。 */
    public static OpsIds ready(FakeLease lease) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("test-ops-ids").daemon(true).factory());
        OpsIds ids = new OpsIds(onLost -> {
            lease.onLost = onLost;
            return lease;
        }, scheduler, System::currentTimeMillis, Duration.ofMillis(20));
        ids.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ids.workerId().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (ids.workerId().isEmpty()) {
            throw new IllegalStateException("替身租约没领到");
        }
        return ids;
    }

    public static OpsIds ready(int worker) {
        return ready(new FakeLease(worker));
    }
}
