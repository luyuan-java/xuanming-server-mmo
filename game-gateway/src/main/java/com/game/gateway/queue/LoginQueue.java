package com.game.gateway.queue;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.RedisKeys;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 登录排队的 Redis 存储（同 mmorpg loginqueue.Queue 的模型，键走 {@link RedisKeys}）：
 * <ul>
 *   <li>区队列 ZSET（排队号按入队毫秒排）；条目元数据 Hash（区、入队时刻、是否已放行），TTL = 排队条目有效期；</li>
 *   <li>已放行占位 ZSET：成员 = 排队号或快速通道的 {@code fast:<uuid>}，<b>分数 = 占位到期毫秒</b>，计数前清掉过期的——
 *       基线用 SET + 整体 TTL，每次 SADD 都把 TTL 续上，持续有人登录时占位只增不减、空位归零；</li>
 *   <li>放行：从队头弹出、写放行槽（选好的 gate，TTL = 放行有效期）并记占位，<b>弹出与放行在同一段 Lua 里</b>，空位也在脚本里算；
 *       客户端下次轮询时取走放行槽、才签 gate 令牌（令牌有效期从客户端拿到时算起）。</li>
 * </ul>
 * 每一步都是一段 Lua（原子）。Redisson 在响应超时后会把同一段 EVAL 重发一次，所以每段脚本重跑都不出错：
 * 入队与快速通道的成员号在 Java 侧生成（重跑是同一个成员）；放行重跑只会在空位以内再放一批（这一批同样原子地写好了放行槽）；
 * 取走放行槽带本次调用的请求号，重跑认得出是同一次。
 *
 * <p>线程安全；阻塞（Redis），只在允许阻塞的线程上调用。只支持单节点 Redis（与其余键一样，脚本按前缀拼出条目的键）。
 */
public final class LoginQueue {

    /**
     * 放行槽取走后占位再留多久：客户端拿到 gate 令牌到连上 gate、gate 把人数发布出来（每 5 s）之前，这个人不在任何 gate 的
     * 在线人数里，占位得替它占着；基线取走即撤占位，开区时每轮都按「在线 0」再放一满批，能放进几倍容量。
     */
    static final Duration TAKEN_GRACE = Duration.ofSeconds(15);

    /** 一次入队的结果。{@code rank} 从 0 起（0 = 下一个）。 */
    public record Enqueued(String queueId, long rank, long total) {
    }

    /** 一次放行的结果：弹出的个数 = 放行 + 已过期（元数据不在，客户端早走了）。 */
    public record Dispatched(int admitted, int expired) {
        public int popped() {
            return admitted + expired;
        }
    }

    /** 轮询的结局。 */
    public sealed interface Lookup permits Admitted, Waiting, Expired {
    }

    /** 已放行：取走了放行槽（只取一次）。 */
    public record Admitted(GateNodeInfo gate, long enqueuedAtMs) implements Lookup {
    }

    /** 还在排：{@code rank} 从 0 起。 */
    public record Waiting(long rank, long total) implements Lookup {
    }

    /** 条目不在了（过期、放行后超时没来取、已经取走）：客户端从 assign-gate 重来。 */
    public record Expired() implements Lookup {
    }

    private static final String ENQUEUE = """
            redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])
            redis.call('pexpire', KEYS[1], ARGV[3])
            redis.call('del', KEYS[2])
            redis.call('hset', KEYS[2], 'zone', ARGV[4], 'created', ARGV[2], 'admitted', '0')
            redis.call('pexpire', KEYS[2], ARGV[3])
            return {redis.call('zrank', KEYS[1], ARGV[1]), redis.call('zcard', KEYS[1])}
            """;

    /** 清掉过期占位后的占位数。ARGV: now_ms。 */
    private static final String ADMITTED_COUNT = """
            redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1])
            return redis.call('zcard', KEYS[1])
            """;

    /** 快速通道原子占位（同基线 reserveFastPathSlotScript）：占位数没到预算才占（1），否则 0。ARGV: now, budget, member, expiry, ttl_ms。 */
    private static final String RESERVE_FAST = """
            redis.call('zremrangebyscore', KEYS[1], '-inf', ARGV[1])
            if redis.call('zcard', KEYS[1]) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('zadd', KEYS[1], ARGV[4], ARGV[3])
            if redis.call('pttl', KEYS[1]) < tonumber(ARGV[5]) then
              redis.call('pexpire', KEYS[1], ARGV[5])
            end
            return 1
            """;

    /**
     * 放行一批：空位 = 预算 − 未过期占位，从队头弹出 min(空位, 上限) 个，元数据还在的写放行槽、记占位、标记已放行，
     * 元数据不在的（条目过期 / 客户端早走了）丢掉。弹出与放行同一段脚本：没有「弹出了还没放行」的中间态，
     * 快速通道看到的占位数也已含这一批。返回 {放行数, 过期数}。
     * KEYS: queue, admitted；ARGV: now, budget, max, admit_ttl_ms, slot, admit_prefix, meta_prefix。
     */
    private static final String DISPATCH = """
            local now = tonumber(ARGV[1])
            redis.call('zremrangebyscore', KEYS[2], '-inf', now)
            local n = math.min(tonumber(ARGV[2]) - redis.call('zcard', KEYS[2]), tonumber(ARGV[3]))
            if n <= 0 then
              return {0, 0}
            end
            local popped = redis.call('zpopmin', KEYS[1], n)
            local expiry = now + tonumber(ARGV[4])
            local admitted, expired = 0, 0
            for i = 1, #popped, 2 do
              local id = popped[i]
              local meta = ARGV[7] .. id
              if redis.call('exists', meta) == 1 then
                redis.call('set', ARGV[6] .. id, ARGV[5], 'PX', ARGV[4])
                redis.call('zadd', KEYS[2], expiry, id)
                redis.call('hset', meta, 'admitted', '1')
                admitted = admitted + 1
              else
                expired = expired + 1
              end
            end
            if admitted > 0 and redis.call('pttl', KEYS[2]) < tonumber(ARGV[4]) then
              redis.call('pexpire', KEYS[2], ARGV[4])
            end
            return {admitted, expired}
            """;

    /**
     * 轮询：有放行槽就取走 → {1, slot, created}——占位改为再留 {@link #TAKEN_GRACE}，元数据记下本次请求号与放行槽，
     * 同一请求被 Redisson 重发时认得出来、照样回 {1, ...}（别的请求再拿同一令牌回 {3}，放行槽只取一次）；
     * 还在队里 → {2, rank, total}；其余（过期、已取走、放行槽超时没取）→ {3}。
     * KEYS: admit, admitted, queue, meta；ARGV: queue_id, request_id, grace_expiry_ms, grace_ms。
     */
    private static final String LOOKUP = """
            local slot = redis.call('get', KEYS[1])
            if slot then
              redis.call('del', KEYS[1])
              redis.call('zadd', KEYS[2], 'XX', ARGV[3], ARGV[1])
              if redis.call('pttl', KEYS[2]) < tonumber(ARGV[4]) then
                redis.call('pexpire', KEYS[2], ARGV[4])
              end
              if redis.call('exists', KEYS[4]) == 1 then
                redis.call('hset', KEYS[4], 'taken_by', ARGV[2], 'slot', slot)
              end
              return {1, slot, redis.call('hget', KEYS[4], 'created') or '0'}
            end
            if redis.call('hget', KEYS[4], 'taken_by') == ARGV[2] then
              return {1, redis.call('hget', KEYS[4], 'slot'), redis.call('hget', KEYS[4], 'created') or '0'}
            end
            local rank = redis.call('zrank', KEYS[3], ARGV[1])
            if rank then
              return {2, rank, redis.call('zcard', KEYS[3])}
            end
            return {3}
            """;

    private final RedissonClient redis;
    private final LongSupplier nowMs;
    private final Duration entryTtl;
    private final Duration admitTtl;

    /**
     * @param entryTtl 排队条目有效期（基线 QueueEntryTTL 1 h）
     * @param admitTtl 放行有效期：放行后多久内来取，也是没取走时占位的存活时长（基线 AdmitTTL 60 s）
     */
    public LoginQueue(RedissonClient redis, LongSupplier nowMs, Duration entryTtl, Duration admitTtl) {
        this.redis = redis;
        this.nowMs = nowMs;
        this.entryTtl = entryTtl;
        this.admitTtl = admitTtl;
    }

    public Duration entryTtl() {
        return entryTtl;
    }

    public Enqueued enqueue(int zoneId) {
        String queueId = QueueTokens.newQueueId();
        List<Object> result = eval(ENQUEUE, RScript.ReturnType.MULTI,
                List.of(RedisKeys.loginQueue(zoneId), RedisKeys.loginQueueMeta(queueId)),
                ascii(queueId), number(nowMs.getAsLong()), number(entryTtl.toMillis()), number(zoneId));
        return new Enqueued(queueId, asLong(result.get(0)), asLong(result.get(1)));
    }

    public long queueLength(int zoneId) {
        Long n = eval("return redis.call('zcard', KEYS[1])", RScript.ReturnType.INTEGER,
                List.of(RedisKeys.loginQueue(zoneId)));
        return n == null ? 0 : n;
    }

    /** 清掉过期占位后的占位数（测试与排查用）。 */
    long admittedCount(int zoneId) {
        Long n = eval(ADMITTED_COUNT, RScript.ReturnType.INTEGER, List.of(RedisKeys.loginQueueAdmitted(zoneId)),
                number(nowMs.getAsLong()));
        return n == null ? 0 : n;
    }

    /** 快速通道占位：占位数没到 {@code budget} 才占。 */
    public boolean tryReserveFastPath(int zoneId, long budget) {
        if (budget <= 0) {
            return false;
        }
        long now = nowMs.getAsLong();
        Long reserved = eval(RESERVE_FAST, RScript.ReturnType.INTEGER, List.of(RedisKeys.loginQueueAdmitted(zoneId)),
                number(now), number(budget), ascii("fast:" + UUID.randomUUID()), number(now + admitTtl.toMillis()),
                number(admitTtl.toMillis()));
        return reserved != null && reserved == 1;
    }

    /**
     * 放行一批到 {@code gate}：至多 {@code max} 个，且不超过 {@code budget}（容量 − 在线）减未过期占位。
     *
     * @return 放行数与弹出的过期条目数；弹出数小于 {@code max} 说明队列空了或空位用完了
     */
    public Dispatched dispatch(int zoneId, long budget, int max, GateNodeInfo gate) {
        if (budget <= 0 || max <= 0) {
            return new Dispatched(0, 0);
        }
        List<Object> result = eval(DISPATCH, RScript.ReturnType.MULTI,
                List.of(RedisKeys.loginQueue(zoneId), RedisKeys.loginQueueAdmitted(zoneId)),
                number(nowMs.getAsLong()), number(budget), number(max), number(admitTtl.toMillis()), gate.toByteArray(),
                ascii(RedisKeys.loginQueueAdmit("")), ascii(RedisKeys.loginQueueMeta("")));
        return new Dispatched((int) asLong(result.get(0)), (int) asLong(result.get(1)));
    }

    public Lookup lookup(int zoneId, String queueId) {
        // 每次调用一个请求号：Redisson 重发的是同一段 EVAL（同一个请求号），客户端再次轮询是新的请求号
        return lookup(zoneId, queueId, UUID.randomUUID().toString());
    }

    /** 测试用：指定请求号（模拟 Redisson 重发同一段 EVAL）。 */
    Lookup lookup(int zoneId, String queueId, String requestId) {
        List<Object> result = eval(LOOKUP, RScript.ReturnType.MULTI, List.of(RedisKeys.loginQueueAdmit(queueId),
                RedisKeys.loginQueueAdmitted(zoneId), RedisKeys.loginQueue(zoneId), RedisKeys.loginQueueMeta(queueId)),
                ascii(queueId), ascii(requestId), number(nowMs.getAsLong() + TAKEN_GRACE.toMillis()),
                number(TAKEN_GRACE.toMillis()));
        long kind = asLong(result.get(0));
        if (kind == 1) {
            try {
                GateNodeInfo gate = GateNodeInfo.parseFrom((byte[]) result.get(1));
                return new Admitted(gate, Long.parseLong(text(result.get(2))));
            } catch (InvalidProtocolBufferException | NumberFormatException e) {
                throw new IllegalStateException("放行槽损坏 queue_id=" + queueId, e);
            }
        }
        if (kind == 2) {
            return new Waiting(asLong(result.get(1)), asLong(result.get(2)));
        }
        return new Expired();
    }

    private <T> T eval(String script, RScript.ReturnType type, List<Object> keys, Object... args) {
        return redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script, type, keys, args);
    }

    private static long asLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        return Long.parseLong(text(value));
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.US_ASCII) : String.valueOf(value);
    }

    private static byte[] number(long value) {
        return ascii(Long.toString(value));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
