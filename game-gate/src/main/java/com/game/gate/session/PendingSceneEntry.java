package com.game.gate.session;

import com.game.api.proto.PlayerEnter;
import com.game.gate.link.EnterFailure;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/** 一条连接上尚未收到派发通知的原进场；只由该连接 EventLoop 修改，不保存或重造 SQL 身份。 */
final class PendingSceneEntry {
    static final long BUDGET_NANOS = Duration.ofSeconds(20).toNanos();
    static final long DISCOVERY_NANOS = Duration.ofSeconds(3).toNanos();
    static final int MAX_ATTEMPTS = 16;
    final int nodeId;
    final PlayerEnter enter;
    final long started;
    long deadline;
    long generation;
    int attempts;
    /** 上次失败已证明帧从未 write；退避期间不用再向死链路请求取消。 */
    boolean knownUnsent;
    ScheduledFuture<?> retry;
    ScheduledFuture<?> expiry;

    PendingSceneEntry(int nodeId, PlayerEnter enter, long now) {
        this.nodeId = nodeId; this.enter = enter; this.started = now; this.deadline = now + BUDGET_NANOS;
    }

    boolean matches(int node, long gen, PlayerEnter original) {
        return nodeId == node && generation == gen && enter.equals(original);
    }

    long retryDelay(EnterFailure failure, long now) {
        // 单调时钟允许负值和回绕，比较原预算长度，不能对绝对 deadline 做有符号 min。
        if (failure == EnterFailure.NODE_MISSING && deadline - started > DISCOVERY_NANOS) {
            deadline = started + DISCOVERY_NANOS;
        }
        if (!failure.retryable() || attempts >= MAX_ATTEMPTS || now - deadline >= 0) return -1;
        long backoff = Duration.ofMillis(250L << Math.min(3, Math.max(0, attempts - 1))).toNanos();
        return Math.min(backoff, deadline - now);
    }

    void cancelTimers() {
        if (retry != null) { retry.cancel(false); retry = null; }
        if (expiry != null) { expiry.cancel(false); expiry = null; }
    }
}
