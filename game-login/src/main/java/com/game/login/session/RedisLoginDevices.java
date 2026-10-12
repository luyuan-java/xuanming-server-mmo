package com.game.login.session;

import com.game.discovery.RedisKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link LoginDevices} 的 Redis 实现：每账号一个 ZSET {@link RedisKeys#loginDevices}（成员是会话键、分数是它的过期毫秒），
 * 每会话一个反查键 {@link RedisKeys#loginDeviceSession}（值是会话当前计在哪个账号下）。判定与登记在一个脚本里完成
 * （多个 login 实例并发登录同一账号也不会超额）。线程安全、阻塞。
 */
public final class RedisLoginDevices implements LoginDevices {

    /**
     * KEYS[1] 账号名单，KEYS[2] 会话反查键；ARGV：now 毫秒、会话键、这次登记的过期毫秒、TTL 毫秒、上限。返回 1 放行、0 拒绝。
     * 放行时顺带续反查键的 TTL（键不存在时 PEXPIRE 不做事）。
     */
    static final String ADMIT_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1])
            if not redis.call('ZSCORE', KEYS[1], ARGV[2]) and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[5]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return 1
            """;

    private final RedissonClient redis;
    private final Clock clock;
    private final Duration ttl;
    private final int max;

    /**
     * @param ttl 一次登记的有效期（基线 SessionExpireMin = 30 分钟）
     * @param max 名单上限（基线 3）
     */
    public RedisLoginDevices(RedissonClient redis, Clock clock, Duration ttl, int max) {
        this.redis = redis;
        this.clock = clock;
        this.ttl = ttl;
        this.max = max;
    }

    @Override
    public boolean admit(String account, String sessionKey) {
        long now = clock.millis();
        Long admitted = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ADMIT_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(RedisKeys.loginDevices(account), RedisKeys.loginDeviceSession(sessionKey)),
                String.valueOf(now), sessionKey, String.valueOf(now + ttl.toMillis()), String.valueOf(ttl.toMillis()),
                String.valueOf(max));
        return admitted != null && admitted == 1L;
    }

    @Override
    public void bind(String account, String sessionKey, String previousAccount) {
        RBatch batch = redis.createBatch(BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
        if (!previousAccount.isEmpty() && !previousAccount.equals(account)) {
            batch.getScoredSortedSet(RedisKeys.loginDevices(previousAccount), StringCodec.INSTANCE).removeAsync(sessionKey);
        }
        batch.getBucket(RedisKeys.loginDeviceSession(sessionKey), StringCodec.INSTANCE).setAsync(account, ttl);
        batch.execute();
    }

    @Override
    public void revoke(String account, String sessionKey) {
        redis.getScoredSortedSet(RedisKeys.loginDevices(account), StringCodec.INSTANCE).remove(sessionKey);
    }

    @Override
    public void leave(String sessionKey, String accountHint) {
        String recorded = redis.<String>getBucket(RedisKeys.loginDeviceSession(sessionKey), StringCodec.INSTANCE)
                .getAndDelete();
        if (recorded != null) {
            redis.getScoredSortedSet(RedisKeys.loginDevices(recorded), StringCodec.INSTANCE).remove(sessionKey);
        }
        if (!accountHint.isEmpty() && !accountHint.equals(recorded)) {
            redis.getScoredSortedSet(RedisKeys.loginDevices(accountHint), StringCodec.INSTANCE).remove(sessionKey);
        }
    }
}
