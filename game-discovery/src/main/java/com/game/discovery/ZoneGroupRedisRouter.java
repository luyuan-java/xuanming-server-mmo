package com.game.discovery;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;
import org.redisson.api.RedissonClient;

/**
 * 控制面按 zone 化与控制域 Redis 按 zone 组分实例（对齐主仓 {@code control-plane-zone-scoping-20261105.md}）：
 * <ul>
 *   <li>每个 zone 映射到一个控制域 Redis 实例（同组共享实例但按 zone 键严格隔离，零跨 zone 原子操作）；</li>
 *   <li>支持进程声明单 zone 作用域（{@code scopedZoneId > 0}），禁止读写其他 zone 的控制状态；</li>
 *   <li>周期全量重拉间隔拉长到分钟级并加抖动（{@code 60s..120s}），常态依赖增量事件；</li>
 *   <li>内置注册中心修订号倒退守卫（{@link RegistryEpochGuard}），检测换集群或备份恢复引起的修订倒退并推进纪元。</li>
 * </ul>
 */
public final class ZoneGroupRedisRouter {

    public static final Duration BASE_FULL_RESYNC_INTERVAL = Duration.ofSeconds(60);
    public static final Duration FULL_RESYNC_JITTER = Duration.ofSeconds(60);

    private final RedissonClient fallback;
    private final IntUnaryOperator zoneToGroup;
    private final Map<Integer, RedissonClient> groupClients = new ConcurrentHashMap<>();
    private final int scopedZoneId;

    /** 单实例/集群缺省路由（全部 zone 共用同一 Redis 集群，仍按 zone 隔离）。 */
    public ZoneGroupRedisRouter(RedissonClient defaultClient) {
        this(defaultClient, z -> 0, Map.of(), 0);
    }

    /** 按 zone 组分实例路由；{@code scopedZoneId > 0} 时只允许访问本 zone，碰其他 zone 立即抛异常。 */
    public ZoneGroupRedisRouter(RedissonClient fallback,
                                IntUnaryOperator zoneToGroup,
                                Map<Integer, RedissonClient> groupClients,
                                int scopedZoneId) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.zoneToGroup = Objects.requireNonNull(zoneToGroup, "zoneToGroup");
        this.groupClients.putAll(Objects.requireNonNull(groupClients, "groupClients"));
        this.scopedZoneId = scopedZoneId;
    }

    public int scopedZoneId() {
        return scopedZoneId;
    }

    /** 返回负责该 zone 控制域状态的 Redis 实例；若设置了单 zone 作用域且越界则拒绝访问。 */
    public RedissonClient clientForZone(int zoneId) {
        if (scopedZoneId != 0 && zoneId != scopedZoneId) {
            throw new IllegalStateException("控制面按 zone 隔离：本进程仅负责 zone=" + scopedZoneId + "，禁止操作 zone=" + zoneId);
        }
        int group = zoneToGroup.applyAsInt(zoneId);
        return groupClients.getOrDefault(group, fallback);
    }

    /** 计算下一次分钟级抖动全量重拉间隔（60s + [0, 60s) 抖动）。 */
    public static Duration nextFullResyncDelay() {
        return nextFullResyncDelay(ThreadLocalRandom.current().nextDouble());
    }

    public static Duration nextFullResyncDelay(double randomUnit) {
        double clamped = Math.max(0.0, Math.min(1.0, randomUnit));
        return BASE_FULL_RESYNC_INTERVAL.plusMillis((long) (FULL_RESYNC_JITTER.toMillis() * clamped));
    }

    /**
     * 注册中心修订号倒退守卫（对齐主仓 {@code scene-node-ownership-reconcile.md} §4.6）：
     * 记下本 zone 观测到的最大修订号；若线性一致读到的当前修订号小于已知最大值，说明发生换集群或旧备份恢复，
     * 立即递增纪元（epoch）并返回 {@code true} 供调用方触发告警 {@code SceneManagerEtcdRevisionRegressed}。
     */
    public static final class RegistryEpochGuard {
        private final AtomicLong maxObservedRevision = new AtomicLong(0L);
        private final AtomicLong epoch = new AtomicLong(1L);

        public synchronized boolean observeRevision(long currentRevision) {
            long prevMax = maxObservedRevision.get();
            if (currentRevision < prevMax) {
                epoch.incrementAndGet();
                maxObservedRevision.set(currentRevision);
                return true;
            }
            if (currentRevision > prevMax) {
                maxObservedRevision.set(currentRevision);
            }
            return false;
        }

        public long epoch() {
            return epoch.get();
        }

        public long maxObservedRevision() {
            return maxObservedRevision.get();
        }
    }
}
