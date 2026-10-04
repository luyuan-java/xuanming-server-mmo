package com.game.gateway.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 限流的 Redis 存储：令牌桶（同 Bucket4j 的贪心补充：容量 = burst、每秒补 rps）与冷却（SET NX PX）。
 * IP 桶与区桶在同一段 Lua 里判（一次往返、原子）；时间取 Redis 的 TIME——桶由全部 gateway 共享，各副本的时钟偏差会让
 * 「上次补充时刻」被时钟快的副本推到将来、别的副本补不上。桶键闲置 {@link #IDLE_TTL} 后过期（IP 桶键随客户端 IP 增长、靠它收）；
 * 冷却键带冷却时长的 TTL。
 *
 * <p>Redisson 在响应超时后会把同一段 EVAL 重发一次：第一次其实执行了时多扣一个令牌（偏严，可接受，不像登录排队那样做成可重跑）；
 * 冷却走 {@code SET NX}，Redisson 不重发。线程安全；阻塞（Redis），只在 Servlet 请求线程上调用。
 */
public final class RedisRateLimitStore implements RateLimitStore {

    static final Duration IDLE_TTL = Duration.ofHours(1);

    /**
     * KEYS: ip[, zone]；ARGV: now_ms（空串 = Redis 服务器时间）, idle_ttl_ms, ip_rps, ip_burst[, zone_rps, zone_burst]。
     * 桶是 Hash（t = 余量 ×1000 定点数，免得浮点漂移；ms = 上次补充毫秒）。
     * 返回 {0, 取后余量}（放行）/ {1}（IP 桶空）/ {2, 还要等多少毫秒（向下取整，同 Bucket4j 的纳秒 / 10^6）, 区桶余量}。
     */
    private static final String ADMIT = """
            local now
            if ARGV[1] == '' then
              local tm = redis.call('time')
              now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
            else
              now = tonumber(ARGV[1])
            end
            local function load(key, rps, burst)
              local t = tonumber(redis.call('hget', key, 't'))
              local last = tonumber(redis.call('hget', key, 'ms'))
              if not t or not last then
                t = burst
                last = now
              end
              if now > last then
                t = math.min(burst, t + (now - last) * rps)
                last = now
              end
              return t, last
            end
            local function save(key, t, last)
              redis.call('hset', key, 't', t, 'ms', last)
              redis.call('pexpire', key, ARGV[2])
            end
            local ipT, ipLast = load(KEYS[1], tonumber(ARGV[3]), tonumber(ARGV[4]) * 1000)
            if ipT < 1000 then
              save(KEYS[1], ipT, ipLast)
              return {1}
            end
            if #KEYS < 2 then
              save(KEYS[1], ipT - 1000, ipLast)
              return {0, math.floor((ipT - 1000) / 1000)}
            end
            local zoneRps = tonumber(ARGV[5])
            local zoneT, zoneLast = load(KEYS[2], zoneRps, tonumber(ARGV[6]) * 1000)
            if zoneT < 1000 then
              save(KEYS[2], zoneT, zoneLast)
              save(KEYS[1], ipT, ipLast)
              return {2, math.floor((1000 - zoneT) / zoneRps), math.floor(zoneT / 1000)}
            end
            save(KEYS[2], zoneT - 1000, zoneLast)
            save(KEYS[1], ipT - 1000, ipLast)
            return {0, math.floor((zoneT - 1000) / 1000)}
            """;

    private final RedissonClient redis;

    public RedisRateLimitStore(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public Admission admit(Bucket ip, Bucket zone) {
        return eval(new byte[0], ip, zone);
    }

    /** 测试用：指定当前毫秒（代替 Redis 服务器时间）。 */
    Admission admitAt(long nowMs, Bucket ip, Bucket zone) {
        return eval(number(nowMs), ip, zone);
    }

    private Admission eval(byte[] now, Bucket ip, Bucket zone) {
        List<Object> keys = zone == null ? List.of(ip.key()) : List.of(ip.key(), zone.key());
        byte[][] args = zone == null
                ? new byte[][] {now, number(IDLE_TTL.toMillis()), number(ip.rps()), number(ip.burst())}
                : new byte[][] {now, number(IDLE_TTL.toMillis()), number(ip.rps()), number(ip.burst()),
                        number(zone.rps()), number(zone.burst())};
        List<Object> r = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ADMIT,
                RScript.ReturnType.MULTI, keys, (Object[]) args);
        long kind = (Long) r.get(0);
        if (kind == 1) {
            return new Admission(Admission.Kind.IP_EMPTY, 0, 0);
        }
        if (kind == 2) {
            return new Admission(Admission.Kind.ZONE_EMPTY, (Long) r.get(1), (Long) r.get(2));
        }
        return new Admission(Admission.Kind.OK, 0, (Long) r.get(1));
    }

    @Override
    public boolean tryCooldown(String key, Duration cooldown) {
        if (cooldown.isZero()) {
            return true;
        }
        Boolean set = redis.getBucket(key, ByteArrayCodec.INSTANCE).setIfAbsent(new byte[] {1}, cooldown);
        return Boolean.TRUE.equals(set);
    }

    private static byte[] number(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
