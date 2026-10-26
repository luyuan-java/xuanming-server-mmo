package com.game.guild.asset;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 清理水位的 Redis 实现。两条脚本都访问主节点；十进制字符串比较避免 Lua double 丢精度。 */
public final class RedissonTerminalCleanupWatermark implements TerminalCleanupWatermark {

    static final String ADVANCE = """
            local function valid(v)
              return v == '0' or (string.match(v, '^[1-9][0-9]*$') and
                (#v < 19 or (#v == 19 and v <= '9223372036854775807')))
            end
            local offered = ARGV[1]
            local current = redis.call('GET', KEYS[1]) or '0'
            if not valid(current) or not valid(offered) then
              return redis.error_reply('invalid terminal cleanup watermark')
            end
            if #offered > #current or (#offered == #current and offered > current) then
              redis.call('SET', KEYS[1], offered)
            end
            -- 旧键即使误带 TTL，确认清理前也必须恢复永久围栏。
            redis.call('PERSIST', KEYS[1])
            return 1
            """;
    static final String READ = "return redis.call('GET', KEYS[1])";

    private final RedissonClient redis;
    private final String key;

    public RedissonTerminalCleanupWatermark(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.key = Objects.requireNonNull(keys, "keys").guildTerminalCleanupWatermark();
    }

    @Override
    public void advance(long cutoffMs, Deadline deadline) {
        if (cutoffMs < 0) {
            throw new IllegalArgumentException("终态指令清理截止不能为负");
        }
        if (deadline.expired()) {
            throw new Deadline.DependencyException("推进终态指令清理水位已超时");
        }
        Long result = deadline.await(redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE,
                ADVANCE, RScript.ReturnType.INTEGER, List.<Object>of(key), Long.toString(cutoffMs)), "推进终态指令清理水位");
        if (result == null || result != 1L) {
            throw new Deadline.DependencyException("推进终态指令清理水位没有确认");
        }
    }

    @Override
    public long read(Deadline deadline) {
        if (deadline.expired()) {
            throw new Deadline.DependencyException("读取终态指令清理水位已超时");
        }
        String raw = deadline.await(redis.getScript(StringCodec.INSTANCE).<String>evalAsync(RScript.Mode.READ_WRITE,
                READ, RScript.ReturnType.VALUE, List.<Object>of(key)), "读取终态指令清理水位");
        return parse(raw);
    }

    static long parse(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            long value = Long.parseLong(raw);
            if (value < 0 || !Long.toString(value).equals(raw)) {
                throw new NumberFormatException("不是规范的非负十进制时间戳");
            }
            return value;
        } catch (NumberFormatException bad) {
            throw new Deadline.DependencyException("终态指令清理水位损坏", bad);
        }
    }
}
