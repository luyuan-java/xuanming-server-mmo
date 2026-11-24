package com.game.login.compliance;

import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 所有 login 副本共享的 NPPA 调用配额（Redis GCRA Lua，对齐 Go {@code nppa/quota.go}）。
 * 额定值留二成余量：Check 80/s burst 10（额定 100），Query 240/s burst 20（额定 300），Loginout 8/s（额定 10）。
 */
public interface NppaQuota {

    record Rule(String name, int perSecond, int burst) {
    }

    Rule CHECK_QUOTA = new Rule("check", 80, 10);
    Rule QUERY_QUOTA = new Rule("query", 240, 20);
    Rule LOGINOUT_QUOTA = new Rule("loginout", 8, 0);

    boolean allow(Rule rule);

    /** Redis GCRA 实现（使用 Redis TIME 消除多副本时钟差）。 */
    final class Redis implements NppaQuota {

        private static final String GCRA_SCRIPT = """
                local t = redis.call('TIME')
                local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
                local interval = tonumber(ARGV[1])
                local tat = tonumber(redis.call('GET', KEYS[1]) or '0')
                if tat < now then
                  tat = now
                end
                if tat - now > tonumber(ARGV[2]) then
                  return 0
                end
                local later = tat + interval
                redis.call('SET', KEYS[1], string.format('%.0f', later), 'PX', math.ceil((later - now) / 1000) + 1000)
                return 1
                """;

        private final RedissonClient redis;
        private final NppaRedisKeys keys;

        public Redis(RedissonClient redis, NppaRedisKeys keys) {
            this.redis = Objects.requireNonNull(redis, "redis");
            this.keys = Objects.requireNonNull(keys, "keys");
        }

        @Override
        public boolean allow(Rule rule) {
            long intervalUs = 1_000_000L / rule.perSecond();
            long burstUs = intervalUs * rule.burst();
            Long res = redis.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, GCRA_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(keys.quota(rule.name())),
                    Long.toString(intervalUs), Long.toString(burstUs));
            return res != null && res == 1L;
        }
    }
}
