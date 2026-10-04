package com.game.gateway.queue;

import java.time.Duration;

/**
 * 登录排队配置（{@code xm.gateway.queue.*}，同基线 login.yaml 的 Queue 段）。缺省关闭（同基线 Enabled=false）：
 * 关闭时 assign-gate 直接分配、{@code queue_token} 照收不用，{@code /api/queue-status} 一律 410。
 *
 * @param enabled           打开排队
 * @param entryTtl          排队条目有效期（基线 QueueEntryTTL 1 h）
 * @param admitTtl          放行有效期：放行后多久内来取；也是占位存活时长（基线 AdmitTTL 60 s）
 * @param retryAfterMs      排队应答建议的轮询间隔（基线 DefaultRetryAfterMs 2000）
 * @param softCapMultiplier 区服容量为 0 时的软上限倍数（基线 SoftCapMultiplier 1.5）
 * @param dispatchInterval  放行循环间隔（基线 DispatchInterval 1 s）
 */
public record QueueSettings(boolean enabled, Duration entryTtl, Duration admitTtl, Long retryAfterMs,
                            Double softCapMultiplier, Duration dispatchInterval) {

    public QueueSettings {
        entryTtl = entryTtl == null ? Duration.ofHours(1) : entryTtl;
        admitTtl = admitTtl == null ? Duration.ofSeconds(60) : admitTtl;
        retryAfterMs = retryAfterMs == null ? 2000L : retryAfterMs;
        softCapMultiplier = softCapMultiplier == null ? 1.5 : softCapMultiplier;
        dispatchInterval = dispatchInterval == null ? Duration.ofSeconds(1) : dispatchInterval;
        if (entryTtl.isNegative() || entryTtl.isZero() || admitTtl.isNegative() || admitTtl.isZero()
                || dispatchInterval.isNegative() || dispatchInterval.isZero() || retryAfterMs <= 0) {
            throw new IllegalArgumentException("xm.gateway.queue：时长与轮询间隔必须为正");
        }
    }

    public static QueueSettings disabled() {
        return new QueueSettings(false, null, null, null, null, null);
    }
}
