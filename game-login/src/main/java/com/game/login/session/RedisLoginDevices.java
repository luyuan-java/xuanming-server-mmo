package com.game.login.session;

import com.game.discovery.RedisKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link LoginDevices} 的 Redis 实现：每账号一个 ZSET {@link RedisKeys#loginDevices}（成员是会话键、分数是它的过期毫秒），
 * 每会话一个反查键 {@link RedisKeys#loginDeviceSession}（值是会话当前计在哪个账号下；会话结束后是关闭墓碑 {@link #CLOSED_MARKER}，
 * 有效期 {@link LoginDevices#CLOSED_TOMBSTONE_TTL}）。判定与登记、判墓碑与记归属各在一个脚本里完成
 * （多个 login 实例并发登录同一账号也不会超额；墓碑落下之后的迟到写一定看得见它）。线程安全、阻塞。
 */
public final class RedisLoginDevices implements LoginDevices {

    /**
     * 反查键上的关闭墓碑值：空串。账号永远非空（认证通过的账号才会 {@link #bind}，续期对空账号直接返回），
     * 所以它不会与任何「记下的账号」相同。
     */
    static final String CLOSED_MARKER = "";

    /**
     * 单键脚本（Redis Cluster 槽位安全）：KEYS[1] = 账号名单；ARGV：now 毫秒、会话键、过期毫秒、TTL 毫秒、上限。
     * 返回 1 已登记/续期、0 名单已满。
     */
    static final String ADMIT_ACCOUNT_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1])
            if not redis.call('ZSCORE', KEYS[1], ARGV[2]) and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[5]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """;

    /**
     * 单键脚本（Redis Cluster 槽位安全）：KEYS[1] = 会话反查键；ARGV：TTL 毫秒、关闭墓碑、归属账号（故障恢复时重建缺失的反查键）。
     * 返回 -1 会话已关闭（墓碑不动），1 未关闭（反查键存在时续期；若因 Redis 故障/重启过期缺失则用 ARGV[3] 重建）。
     */
    static final String TOUCH_SESSION_SCRIPT = """
            local v = redis.call('GET', KEYS[1])
            if v == ARGV[2] then
              return -1
            end
            if v then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            elseif ARGV[3] and ARGV[3] ~= '' then
              redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[1])
            end
            return 1
            """;

    /**
     * 单键脚本（Redis Cluster 槽位安全）：KEYS[1] = 会话反查键；ARGV：账号、TTL 毫秒、关闭墓碑。
     * 返回 1 已记下归属；0 会话已关闭（墓碑不动）。
     */
    static final String BIND_SESSION_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[3] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """;

    /** KEYS[1] 会话反查键；ARGV[1] 关闭墓碑。取走并删掉记下的账号；是墓碑（或不存在）时原样留着、返回 nil。 */
    static final String LEAVE_SCRIPT = """
            local recorded = redis.call('GET', KEYS[1])
            if recorded and recorded ~= ARGV[1] then
              redis.call('DEL', KEYS[1])
              return recorded
            end
            return false
            """;

    /** KEYS[1] 会话反查键；ARGV：关闭墓碑、墓碑有效期毫秒。写墓碑，返回原先记下的账号（没有或已是墓碑时 nil）。 */
    static final String CLOSE_SCRIPT = """
            local recorded = redis.call('GET', KEYS[1])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            if recorded and recorded ~= ARGV[1] then
              return recorded
            end
            return false
            """;

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    private final Clock clock;
    private final Duration ttl;
    private final int max;

    /**
     * @param keys 名单键与反查键的出处（带部署命名空间，装配方注入）
     * @param ttl  一次登记的有效期（基线 SessionExpireMin = 30 分钟）
     * @param max  名单上限（基线 3）
     */
    public RedisLoginDevices(RedissonClient redis, RedisKeys keys, Clock clock, Duration ttl, int max) {
        this.redis = redis;
        this.redisKeys = Objects.requireNonNull(keys, "keys");
        this.clock = clock;
        this.ttl = ttl;
        this.max = max;
    }

    @Override
    public Admission admit(String account, String sessionKey) {
        String sessionRedisKey = redisKeys.loginDeviceSession(sessionKey);
        if (CLOSED_MARKER.equals(redis.getBucket(sessionRedisKey, StringCodec.INSTANCE).get())) {
            return Admission.CLOSED;
        }
        long now = clock.millis();
        long ttlMs = ttl.toMillis();
        Long verdict = script().eval(RScript.Mode.READ_WRITE, ADMIT_ACCOUNT_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(redisKeys.loginDevices(account)),
                String.valueOf(now), sessionKey, String.valueOf(now + ttlMs), String.valueOf(ttlMs),
                String.valueOf(max));
        if (verdict == null || verdict != 1L) {
            return Admission.FULL;
        }
        Long sessionState = script().eval(RScript.Mode.READ_WRITE, TOUCH_SESSION_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(sessionRedisKey), String.valueOf(ttlMs), CLOSED_MARKER, account);
        if (sessionState != null && sessionState == -1L) {
            // 并发 close 在两次单键调用间落下墓碑：立即从账号名单撤销，避免残留。
            revoke(account, sessionKey);
            return Admission.CLOSED;
        }
        return Admission.ADMITTED;
    }

    @Override
    public boolean bind(String account, String sessionKey, String previousAccount) {
        Long bound = script().eval(RScript.Mode.READ_WRITE, BIND_SESSION_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(redisKeys.loginDeviceSession(sessionKey)),
                account, String.valueOf(ttl.toMillis()), CLOSED_MARKER);
        if (bound == null || bound != 1L) {
            revoke(account, sessionKey);
            return false;
        }
        if (!previousAccount.isEmpty() && !previousAccount.equals(account)) {
            revoke(previousAccount, sessionKey);
        }
        return true;
    }

    @Override
    public void revoke(String account, String sessionKey) {
        redis.getScoredSortedSet(redisKeys.loginDevices(account), StringCodec.INSTANCE).remove(sessionKey);
    }

    @Override
    public void leave(String sessionKey, String accountHint) {
        String recorded = script().eval(RScript.Mode.READ_WRITE, LEAVE_SCRIPT, RScript.ReturnType.VALUE,
                List.of(redisKeys.loginDeviceSession(sessionKey)), CLOSED_MARKER);
        removeFromLists(sessionKey, recorded, accountHint);
    }

    @Override
    public void close(String sessionKey, String accountHint) {
        String recorded = script().eval(RScript.Mode.READ_WRITE, CLOSE_SCRIPT, RScript.ReturnType.VALUE,
                List.of(redisKeys.loginDeviceSession(sessionKey)), CLOSED_MARKER,
                String.valueOf(CLOSED_TOMBSTONE_TTL.toMillis()));
        removeFromLists(sessionKey, recorded, accountHint);
    }

    /** 从记下的账号与 gate 给的账号的名单里注销（两者不同或记录已过期时都覆盖到）。 */
    private void removeFromLists(String sessionKey, String recorded, String accountHint) {
        if (recorded != null) {
            redis.getScoredSortedSet(redisKeys.loginDevices(recorded), StringCodec.INSTANCE).remove(sessionKey);
        }
        if (!accountHint.isEmpty() && !accountHint.equals(recorded)) {
            redis.getScoredSortedSet(redisKeys.loginDevices(accountHint), StringCodec.INSTANCE).remove(sessionKey);
        }
    }

    private RScript script() {
        return redis.getScript(StringCodec.INSTANCE);
    }
}
