package com.game.gateway.queue;

import java.time.Duration;

/**
 * 登录排队配置（{@code game.gateway.queue.*}，同基线 login.yaml 的 Queue 段）。缺省关闭（同基线 Enabled=false）：
 * 关闭时 assign-gate 直接分配、{@code queue_token} 照收不用，{@code /api/queue-status} 一律 410。
 *
 * @param enabled            打开排队
 * @param entryTtl           排队条目有效期（基线 QueueEntryTTL 1 h）
 * @param admitTtl           放行有效期：放行后多久内来取；也是占位存活时长（基线 AdmitTTL 60 s）
 * @param retryAfterMs       排队应答建议的轮询间隔（基线 DefaultRetryAfterMs 2000）
 * @param softCapMultiplier  区服容量为 0 时的软上限倍数（基线 SoftCapMultiplier 1.5）
 * @param dispatchInterval   放行循环间隔（基线 DispatchInterval 1 s）
 * @param maxQueueLength     单区队列长度上限（缺省 1,000,000，0 = 不设上限；满时回 100 ratelimit 30~40s 不入队）
 * @param maxDispatchPerTick 单区每拍放行上限（缺省 2,000）
 */
public record QueueSettings(boolean enabled, Duration entryTtl, Duration admitTtl, Long retryAfterMs,
                            Double softCapMultiplier, Duration dispatchInterval,
                            Integer maxQueueLength, Integer maxDispatchPerTick) {

    public static final int DEFAULT_MAX_QUEUE_LENGTH = 1_000_000;
    public static final int DEFAULT_MAX_DISPATCH_PER_TICK = 2_000;

    public QueueSettings(boolean enabled, Duration entryTtl, Duration admitTtl, Long retryAfterMs,
                         Double softCapMultiplier, Duration dispatchInterval) {
        this(enabled, entryTtl, admitTtl, retryAfterMs, softCapMultiplier, dispatchInterval, null, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public QueueSettings {
        entryTtl = entryTtl == null ? Duration.ofHours(1) : entryTtl;
        admitTtl = admitTtl == null ? Duration.ofSeconds(60) : admitTtl;
        retryAfterMs = retryAfterMs == null ? 2000L : retryAfterMs;
        softCapMultiplier = softCapMultiplier == null ? 1.5 : softCapMultiplier;
        dispatchInterval = dispatchInterval == null ? Duration.ofSeconds(1) : dispatchInterval;
        maxQueueLength = maxQueueLength == null ? DEFAULT_MAX_QUEUE_LENGTH : maxQueueLength;
        maxDispatchPerTick = maxDispatchPerTick == null ? DEFAULT_MAX_DISPATCH_PER_TICK : maxDispatchPerTick;
        if (entryTtl.isNegative() || entryTtl.isZero() || admitTtl.isNegative() || admitTtl.isZero()
                || dispatchInterval.isNegative() || dispatchInterval.isZero() || retryAfterMs <= 0
                || maxQueueLength < 0 || maxDispatchPerTick <= 0) {
            throw new IllegalArgumentException("game.gateway.queue：时长、轮询间隔与每拍上限必须为正，队列上限不能为负");
        }
    }

    public static QueueSettings disabled() {
        return new QueueSettings(false, null, null, null, null, null, null, null);
    }
}
