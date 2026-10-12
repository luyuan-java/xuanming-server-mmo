package com.game.discovery;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 节点号租约：在 {@code [minId, maxId]} 里占一个空闲号（Redis {@code SET NX PX}），定时续期。
 *
 * <p>节点号用于雪花 worker 与 gate 的 session 高位，所以同一 (节点类型, zone) 内同一时刻只能有一个持有者：
 * <ul>
 *   <li>续期是「值仍是本实例才续」的原子 Lua，不会续到别人的号上；每 TTL/3 续一次；</li>
 *   <li>续期连续失败超过一个 TTL（按单调时钟计），或发现号已被别人占用，即回调 {@code onLost}，
 *       调用方必须停止用这个号发号 / 接客（fail-closed）；</li>
 *   <li>{@link #close()} 只删除仍属于本实例的键。</li>
 * </ul>
 *
 * <p><b>{@link #isValid()} 的界</b>：未丢失，且距最近一次<b>成功</b>占号 / 续期不足 TTL 的 2/3（单调时钟）。
 * 这个时刻取的是<b>发出</b>命令之前的本地时间，Redis 执行 {@code SET NX PX} / {@code PEXPIRE} 只会更晚，
 * 所以键在 Redis 里至少存活到「该时刻 + TTL」；本地在「该时刻 + 2/3 TTL」就判无效，留出 1/3 TTL
 * （TTL 15s 时为 5s）的余量，吸收「判定有效之后、真正用号之前」的停顿（GC、调度）与两端时钟速率偏差。
 * 结论：只要 {@code isValid()} 为真时立即用号，用号时刻这个号一定还在本实例名下，别的实例拿不到它。
 * 反过来，Redis 抖动超过 2/3 TTL 时 {@code isValid()} 会先于 {@code onLost} 变假，调用方应把它当作「暂停发号」，
 * 续期恢复后自动变回真。
 *
 * 线程模型：续期在调用方给的调度线程上跑；{@link #nodeId()}、{@link #isLost()}、{@link #isValid()} 可从任意线程读。
 */
public final class NodeIdLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeIdLease.class);

    private static final String RENEW_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedissonClient redis;
    private final String key;
    private final int nodeId;
    private final long leaseEpoch;
    private final String instanceId;
    private final Duration ttl;
    private final long validityNanos;
    private final Runnable onLost;
    private final LongSupplier nanoClock;
    private final ScheduledFuture<?> renewTask;
    private final AtomicBoolean lost = new AtomicBoolean();
    /** 最近一次成功占号 / 续期的命令<b>发出前</b>的单调时钟读数。 */
    private volatile long lastRenewedNanos;

    private NodeIdLease(RedissonClient redis, String key, int nodeId, long leaseEpoch, String instanceId, Duration ttl,
                        Runnable onLost, ScheduledExecutorService scheduler, LongSupplier nanoClock, long acquiredAtNanos) {
        this.redis = redis;
        this.key = key;
        this.nodeId = nodeId;
        this.leaseEpoch = leaseEpoch;
        this.instanceId = instanceId;
        this.ttl = ttl;
        this.validityNanos = ttl.toNanos() / 3 * 2;
        this.onLost = onLost;
        this.nanoClock = nanoClock;
        this.lastRenewedNanos = acquiredAtNanos;
        long periodMs = Math.max(1, ttl.toMillis() / 3);
        this.renewTask = scheduler.scheduleAtFixedRate(this::renew, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 占号。号段内全部被占用时抛 {@link IllegalStateException}。
     *
     * @param onLost 租约丢失回调（在调度线程上调用，只调一次）
     */
    public static NodeIdLease acquire(RedissonClient redis, ScheduledExecutorService scheduler, String nodeType,
                                      int zoneId, int minId, int maxId, String instanceId, Duration ttl, Runnable onLost) {
        return acquire(redis, scheduler, nodeType, zoneId, minId, maxId, instanceId, ttl, onLost, System::nanoTime);
    }

    /** 同上，单调时钟可注入（测试用）。 */
    static NodeIdLease acquire(RedissonClient redis, ScheduledExecutorService scheduler, String nodeType, int zoneId,
                               int minId, int maxId, String instanceId, Duration ttl, Runnable onLost,
                               LongSupplier nanoClock) {
        for (int id = minId; id <= maxId; id++) {
            String key = RedisKeys.nodeId(nodeType, zoneId, id);
            long sentAt = nanoClock.getAsLong();
            boolean taken = redis.<String>getBucket(key, StringCodec.INSTANCE).setIfAbsent(instanceId, ttl);
            if (taken) {
                long epoch = nextLeaseEpoch(redis, key, instanceId, RedisKeys.nodeIdEpoch(nodeType, zoneId, id));
                log.info("节点号租约已获取 type={} zone={} nodeId={} leaseEpoch={} instance={}",
                        nodeType, zoneId, id, epoch, instanceId);
                return new NodeIdLease(redis, key, id, epoch, instanceId, ttl, onLost, scheduler, nanoClock, sentAt);
            }
        }
        throw new IllegalStateException("节点号已占满 type=" + nodeType + " zone=" + zoneId + " 区间=[" + minId + "," + maxId + "]");
    }

    /**
     * 占号成功后领一个防护代次（INCR，永不回退）。领不到就放弃这个号（删掉仍属于本实例的租约键）并失败：
     * 没有代次的持有者无法向下游证明自己比旧持有者新。
     */
    private static long nextLeaseEpoch(RedissonClient redis, String key, String instanceId, String epochKey) {
        try {
            return redis.getAtomicLong(epochKey).incrementAndGet();
        } catch (RuntimeException e) {
            try {
                redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RELEASE_LUA,
                        RScript.ReturnType.INTEGER, List.of(key), instanceId);
            } catch (RuntimeException releaseError) {
                e.addSuppressed(releaseError);
            }
            throw new IllegalStateException("领取节点号防护代次失败 key=" + epochKey, e);
        }
    }

    public int nodeId() {
        return nodeId;
    }

    /**
     * 本次占号的防护代次（fencing token）：同一 (类型, zone, 节点号) 每被占一次严格加一。
     * 下游（如 scene 接受 gate 链路）据此拒绝代次更小的旧持有者。
     */
    public long leaseEpoch() {
        return leaseEpoch;
    }

    public boolean isLost() {
        return lost.get();
    }

    /**
     * 现在能否用这个号发号 / 接客：未丢失，且距最近一次成功续期不足 TTL 的 2/3（界的推导见类注释）。
     * 为假时调用方必须拒绝用号（fail-closed）；续期恢复后会重新为真，除非已丢失。
     */
    public boolean isValid() {
        return !lost.get() && nanoClock.getAsLong() - lastRenewedNanos < validityNanos;
    }

    /** 续期一次（调度线程上周期执行；包内可见供测试直接驱动）。 */
    void renew() {
        if (lost.get()) {
            return;
        }
        long sentAt = nanoClock.getAsLong();
        try {
            Long renewed = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RENEW_LUA,
                    RScript.ReturnType.INTEGER, List.of(key), instanceId, Long.toString(ttl.toMillis()));
            if (renewed != null && renewed == 1L) {
                lastRenewedNanos = sentAt;
                return;
            }
            markLost("号已不属于本实例");
        } catch (RuntimeException e) {
            // Redis 暂时不可达：还在 TTL 内就等下一轮（期间 isValid 可能已为假），超过 TTL 视为丢失。
            if (nanoClock.getAsLong() - lastRenewedNanos > ttl.toNanos()) {
                markLost("续期失败超过 TTL: " + e);
            } else {
                log.warn("节点号续期失败，下一轮重试 key={}", key, e);
            }
        }
    }

    private void markLost(String reason) {
        if (lost.compareAndSet(false, true)) {
            log.error("节点号租约丢失 key={} 原因={}", key, reason);
            renewTask.cancel(false);
            onLost.run();
        }
    }

    @Override
    public void close() {
        renewTask.cancel(false);
        if (lost.get()) {
            return;
        }
        try {
            redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RELEASE_LUA,
                    RScript.ReturnType.INTEGER, List.of(key), instanceId);
        } catch (RuntimeException e) {
            log.warn("释放节点号失败（TTL 到期后自动回收） key={}", key, e);
        }
    }
}
