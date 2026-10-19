package com.game.inbox.maintenance;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 收件箱后台维护的领导锁（{@code <ns>:inbox:leader}，STRING = 持有者令牌，PX = TTL）：清理、提醒补发、扫描型 gauge、对账只在持有者上跑
 * （mmorpg 用 {@code go/shared/leader} 在核心 Redis 上选，设计 §10）。写法同频道计划的领导锁（{@code RedissonWorldChannelStore} 的三段 Lua）：
 * 竞选 = 没人持有就 SET NX PX、已是本令牌就续期；放锁只删仍属于本令牌的键。
 *
 * <p>本类只管 Redis 命令，有效期（持有中且距最近一次成功续期不足 TTL 的 2/3）由调用方按单调时钟判。领导者切换期间两个副本可能重叠执行一小段，
 * 后台的每一步都是 CAS / 条件删 / 只读，重叠无害（设计 §10）。线程安全；命令阻塞，不在 I/O 线程上调。
 */
public class LeaderLease {

    /** 竞选或续期。KEYS[1] = 锁；ARGV[1] = 令牌，ARGV[2] = TTL 毫秒。回 1 = 持有。 */
    static final String ACQUIRE_OR_RENEW = """
            local holder = redis.call('GET', KEYS[1])
            if holder == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if holder then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """;

    /** 放锁。KEYS[1] = 锁；ARGV[1] = 令牌。 */
    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedissonClient redis;
    private final String key;
    private final String token;
    private final Duration ttl;

    public LeaderLease(RedissonClient redis, RedisKeys keys, String token, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.key = keys.inboxMaintenanceLeader();
        this.token = Objects.requireNonNull(token, "token");
        if (token.isEmpty()) {
            throw new IllegalArgumentException("领导锁令牌不能为空");
        }
        if (ttl.toMillis() < 3) {
            throw new IllegalArgumentException("领导锁 TTL 太短: " + ttl);
        }
        this.ttl = ttl;
    }

    /** 测试替身用。 */
    protected LeaderLease(String token, Duration ttl) {
        this.redis = null;
        this.key = null;
        this.token = token;
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }

    public String token() {
        return token;
    }

    /** 竞选或续期一次：true = 本副本持有。Redis 出错原样抛出（调用方按「本次没续上」处理）。 */
    public boolean acquireOrRenew() {
        Long held = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ACQUIRE_OR_RENEW, RScript.ReturnType.INTEGER,
                List.<Object>of(key), ascii(token), ascii(Long.toString(ttl.toMillis())));
        return held != null && held == 1L;
    }

    /** 只删仍属于本令牌的锁（停服时调）。 */
    public void release() {
        redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, RELEASE, RScript.ReturnType.INTEGER, List.<Object>of(key),
                ascii(token));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
