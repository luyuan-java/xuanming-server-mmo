package com.game.match.challenge;

/**
 * 切磋存储的三段 Lua（match-spec §6.3、§9.4 的 S_CH_INVITE / S_CH_DEL / S_CH_CONSUME）。约定：
 * <ul>
 *   <li>只访问 {@code KEYS} 里声明的键（三类键都带 {@code {match}}，同槽）；键由 {@code RedisKeys} 生成，不在脚本里拼。</li>
 *   <li>challenge_id / player_id 是 64 位号，<b>一律当字符串比较与存取</b>，不转成 Lua 数字（double 只精确到 2^53）。</li>
 *   <li>时间取 Redis {@code TIME}（毫秒），格式化成不带小数、不带指数的十进制串（{@code %.0f}）。</li>
 *   <li>返回值一律是字符串数组（首元素是结局标签），Java 侧用 StringCodec + MULTI 读。</li>
 *   <li>每段都可被 Redisson 重发（{@code retryAttempts}）：见各段注释里的「重放」。</li>
 * </ul>
 */
final class ChallengeScripts {

    /** 记录 / 墓碑 HASH 的字段名（记录的四个字段与基线 {@code chl.go:21-26} 同名）。 */
    static final String FIELD_CHALLENGER = "challenger";
    static final String FIELD_TARGET = "target";
    static final String FIELD_CONFIG = "config";
    static final String FIELD_EXPIRES_AT = "expires_at_ms";

    /** 返回数组的首元素。 */
    static final String OK = "ok";
    static final String PENDING = "pending";
    static final String GONE = "gone";
    static final String NOT_TARGET = "not_target";

    /**
     * 发起。KEYS[1] = 记录，KEYS[2] = 目标的占坑；ARGV[1] = challenge_id，ARGV[2] = 发起者，ARGV[3] = 目标，ARGV[4] = config，ARGV[5] = TTL 毫秒。
     * 返回 {@code {"ok", expires_at_ms}} 或 {@code {"pending"}}。
     * 重放：占坑的值已是本 id 且记录还在 → 返回记录里的过期时刻，不重写、不续期。
     */
    static final String INVITE = """
            local held = redis.call('GET', KEYS[2])
            if held then
              if held ~= ARGV[1] then
                return {'pending'}
              end
              local expires = redis.call('HGET', KEYS[1], 'expires_at_ms')
              if expires then
                return {'ok', expires}
              end
            end
            local t = redis.call('TIME')
            local expires = string.format('%.0f', t[1] * 1000 + math.floor(t[2] / 1000) + tonumber(ARGV[5]))
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[5])
            redis.call('HSET', KEYS[1], 'challenger', ARGV[2], 'target', ARGV[3], 'config', ARGV[4], 'expires_at_ms', expires)
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return {'ok', expires}
            """;

    /**
     * 清理。KEYS[1] = 记录，KEYS[2] = 目标的占坑；ARGV[1] = challenge_id。占坑的值等于本 id 才摘。幂等。
     */
    static final String DELETE = """
            redis.call('DEL', KEYS[1])
            if redis.call('GET', KEYS[2]) == ARGV[1] then
              redis.call('DEL', KEYS[2])
            end
            return 1
            """;

    /**
     * 消费。KEYS[1] = 记录，KEYS[2] = <b>应答者</b>的占坑，KEYS[3] = 墓碑；ARGV[1] = challenge_id，ARGV[2] = 应答者，ARGV[3] = 请求 nonce，
     * ARGV[4] = 墓碑 TTL 毫秒。返回 {@code {"ok", challenger, target, config, expires_at_ms, now_ms}}、{@code {"gone"}} 或 {@code {"not_target"}}。
     * 重放：墓碑存在且 nonce 相同 → 原样返回墓碑里的字段（含第一次执行时的 now_ms，过期判定因此不变）。
     * 墓碑存在但 nonce 不同 = 已被别的请求消费：记录已不在，落到 gone。
     */
    static final String CONSUME = """
            local function s(v)
              if v then return v end
              return ''
            end
            local done = redis.call('HMGET', KEYS[3], 'nonce', 'challenger', 'target', 'config', 'expires_at_ms', 'now_ms')
            if done[1] and done[1] == ARGV[3] then
              return {'ok', s(done[2]), s(done[3]), s(done[4]), s(done[5]), s(done[6])}
            end
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return {'gone'}
            end
            local rec = redis.call('HMGET', KEYS[1], 'challenger', 'target', 'config', 'expires_at_ms')
            if rec[2] ~= ARGV[2] then
              return {'not_target'}
            end
            local t = redis.call('TIME')
            local now = string.format('%.0f', t[1] * 1000 + math.floor(t[2] / 1000))
            redis.call('DEL', KEYS[1])
            if redis.call('GET', KEYS[2]) == ARGV[1] then
              redis.call('DEL', KEYS[2])
            end
            redis.call('HSET', KEYS[3], 'nonce', ARGV[3], 'challenger', s(rec[1]), 'target', s(rec[2]), 'config', s(rec[3]),
                'expires_at_ms', s(rec[4]), 'now_ms', now)
            redis.call('PEXPIRE', KEYS[3], ARGV[4])
            return {'ok', s(rec[1]), s(rec[2]), s(rec[3]), s(rec[4]), now}
            """;

    private ChallengeScripts() {
    }
}
