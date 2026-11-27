package com.game.discovery.workshard;

import com.game.common.workshard.WorkShard;
import com.game.discovery.RedisKeys;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Redis ZSET 的 64 片 HRW 后台分工活跃成员跟踪器。
 *
 * <p>stable 副本每 5 秒续期一次（TTL 15 秒）并刷新本地 {@link WorkShard.Membership}；
 * canary 副本（{@code track="canary"}）不入成员表，避免未承载常规后台工作的灰度实例占片；
 * Redis 暂时抖动超过 30 秒时 {@link WorkShard.Membership} 自动回落到全片保活。
 */
public final class RedisWorkShardMembership implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisWorkShardMembership.class);
    static final long LEASE_TTL_MS = 15_000L;

    private final RedissonClient redis;
    private final String key;
    private final String instanceId;
    private final boolean registerSelf;
    private final LongSupplier clockMs;
    private final WorkShard.Membership membership;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisWorkShardMembership(RedissonClient redis, RedisKeys keys, String service, int zoneId,
            String instanceId, String track) {
        this(redis, keys, service, zoneId, instanceId, track, System::currentTimeMillis);
    }

    RedisWorkShardMembership(RedissonClient redis, RedisKeys keys, String service, int zoneId,
            String instanceId, String track, LongSupplier clockMs) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.key = Objects.requireNonNull(keys, "keys").workShardMembers(service, zoneId);
        this.instanceId = instanceId == null ? "" : instanceId.strip();
        this.registerSelf = !this.instanceId.isEmpty() && !"canary".equalsIgnoreCase(track == null ? "" : track.strip());
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.membership = new WorkShard.Membership(WorkShard.DEFAULT_STALE_AFTER_MS, this.clockMs);
        this.membership.setSelf(this.instanceId);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("workshard-" + service + "-" + zoneId).factory());
    }

    public WorkShard.Membership membership() {
        return membership;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        refreshQuietly();
        scheduler.scheduleWithFixedDelay(this::refreshQuietly,
                WorkShard.DEFAULT_REFRESH_INTERVAL_MS, WorkShard.DEFAULT_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 执行一次续期与活跃成员拉取；供调度线程与单元测试调用。 */
    public void refresh() {
        if (closed.get()) {
            return;
        }
        long now = clockMs.getAsLong();
        RScoredSortedSet<String> set = redis.getScoredSortedSet(key, StringCodec.INSTANCE);
        if (registerSelf) {
            set.add(now + LEASE_TTL_MS, instanceId);
        }
        set.removeRangeByScore(0, true, now, true);
        Collection<String> alive = set.valueRange(now, false, Double.POSITIVE_INFINITY, true);
        membership.updateMembers(alive);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn("workshard member refresh failed key={}", key, failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        if (registerSelf) {
            try {
                redis.getScoredSortedSet(key, StringCodec.INSTANCE).remove(instanceId);
            } catch (RuntimeException ignored) {
                // 停机时 Redis 不可达可忽略，最多等 LEASE_TTL_MS 后自然过期。
            }
        }
    }
}
