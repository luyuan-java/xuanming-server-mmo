package com.game.login.compliance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 16 分片 Redis 防沉迷会话账本（对齐 Go {@code nppa/ledger.go}）：保证报过上线的会话必报下线，且进程退出不丢事件。
 */
public final class NppaLedger {

    static final Duration CLAIM_VISIBILITY = Duration.ofSeconds(15);
    private static final int SWEEP_BATCH = 200;
    private static final long SWEEP_BACKFILL_SECONDS = 120L;

    private static final String LUA_PRELUDE = """
            local t = redis.call('TIME')
            local now_s = tonumber(t[1])
            local now_ms = now_s * 1000 + math.floor(tonumber(t[2]) / 1000)
            local function num(n) return string.format('%.0f', n) end
            local function emit(outbox, kind, ot, si, pi)
              redis.call('ZADD', outbox, num(now_ms), kind .. '|' .. num(ot) .. '|' .. si .. '|' .. pi)
            end
            local function lease_end(lease_ms, cap_ms)
              local e = now_ms + lease_ms
              if cap_ms > 0 and cap_ms < e then e = cap_ms end
              return e
            end
            """;

    private static final String BEGIN_SCRIPT = LUA_PRELUDE + """
            local old = redis.call('HGET', KEYS[1], ARGV[1])
            if old then
              local si, pi = string.match(old, '^([^|]*)|([^|]*)|')
              if si ~= '' then emit(KEYS[3], 'out', now_s, si, pi) end
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. '|' .. ARGV[3] .. '|' .. ARGV[5])
            redis.call('ZADD', KEYS[2], num(lease_end(tonumber(ARGV[4]), tonumber(ARGV[5]))), ARGV[1])
            if ARGV[2] ~= '' then emit(KEYS[3], 'in', now_s, ARGV[2], ARGV[3]) end
            return 1
            """;

    private static final String RENEW_SCRIPT = LUA_PRELUDE + """
            local v = redis.call('HGET', KEYS[1], ARGV[1])
            if not v then return '-1' end
            local cap = tonumber(string.match(v, '|([^|]*)$'))
            redis.call('ZADD', KEYS[2], num(lease_end(tonumber(ARGV[2]), cap)), ARGV[1])
            return num(cap)
            """;

    private static final String END_SCRIPT = LUA_PRELUDE + """
            local v = redis.call('HGET', KEYS[1], ARGV[1])
            if not v then return 0 end
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            local si, pi = string.match(v, '^([^|]*)|([^|]*)|')
            if si ~= '' then emit(KEYS[3], 'out', now_s, si, pi) end
            return 1
            """;

    private static final String SWEEP_SCRIPT = LUA_PRELUDE + """
            local lease = tonumber(ARGV[1])
            local last = tonumber(redis.call('HGET', KEYS[4], 'last') or '0')
            redis.call('HSET', KEYS[4], 'last', num(now_ms))
            if last > 0 and now_ms - last > lease / 3 then
              redis.call('HSET', KEYS[4], 'hold_until', num(now_ms + lease))
            end
            if now_ms < tonumber(redis.call('HGET', KEYS[4], 'hold_until') or '0') then
              return 0
            end
            local backfill = tonumber(ARGV[3])
            local due = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', num(now_ms), 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[2]))
            for i = 1, #due, 2 do
              local conn = due[i]
              redis.call('ZREM', KEYS[2], conn)
              local v = redis.call('HGET', KEYS[1], conn)
              if v then
                redis.call('HDEL', KEYS[1], conn)
                local si, pi = string.match(v, '^([^|]*)|([^|]*)|')
                if si ~= '' then
                  local ot = math.floor(tonumber(due[i + 1]) / 1000)
                  if ot > now_s then ot = now_s end
                  if ot < now_s - backfill then ot = now_s - backfill end
                  emit(KEYS[3], 'out', ot, si, pi)
                end
              end
            end
            return #due / 2
            """;

    private static final String CLAIM_SCRIPT = LUA_PRELUDE + """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', num(now_ms), 'LIMIT', 0, tonumber(ARGV[1]))
            for _, member in ipairs(due) do
              redis.call('ZADD', KEYS[1], num(now_ms + tonumber(ARGV[2])), member)
            end
            return due
            """;

    private static final String RELEASE_SCRIPT = """
            for i = 1, #ARGV do
              redis.call('ZADD', KEYS[1], 'XX', 0, ARGV[i])
            end
            return 1
            """;

    public record Claimed(int shard, String member, NppaClient.Behavior behavior, boolean valid) {
    }

    private final RedissonClient redis;
    private final NppaRedisKeys keys;
    private final long leaseMs;
    private final AtomicInteger claimCursor = new AtomicInteger();

    public NppaLedger(RedissonClient redis, NppaRedisKeys keys, Duration lease) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.leaseMs = Objects.requireNonNull(lease, "lease").toMillis();
    }

    public void begin(String connId, String si, String pi, long playDeadlineMs) {
        int shard = keys.shardOf(connId);
        redis.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, BEGIN_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(keys.ledgerSessions(shard), keys.ledgerLeases(shard), keys.ledgerOutbox(shard)),
                connId, si == null ? "" : si, pi == null ? "" : pi,
                Long.toString(leaseMs), Long.toString(Math.max(0L, playDeadlineMs)));
    }

    public long renew(String connId) {
        int shard = keys.shardOf(connId);
        Object res = redis.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, RENEW_SCRIPT, RScript.ReturnType.VALUE,
                List.of(keys.ledgerSessions(shard), keys.ledgerLeases(shard)),
                connId, Long.toString(leaseMs));
        if (res == null) {
            return 0L;
        }
        try {
            long val = Long.parseLong(res.toString());
            return Math.max(0L, val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void end(String connId) {
        int shard = keys.shardOf(connId);
        redis.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, END_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(keys.ledgerSessions(shard), keys.ledgerLeases(shard), keys.ledgerOutbox(shard)),
                connId);
    }

    public int sweep() {
        int total = 0;
        for (int shard = 0; shard < NppaRedisKeys.LEDGER_SHARD_COUNT; shard++) {
            Long n = redis.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, SWEEP_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(keys.ledgerSessions(shard), keys.ledgerLeases(shard),
                            keys.ledgerOutbox(shard), keys.ledgerSweep(shard)),
                    Long.toString(leaseMs), Integer.toString(SWEEP_BATCH), Long.toString(SWEEP_BACKFILL_SECONDS));
            if (n != null) {
                total += n.intValue();
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    public List<Claimed> claim(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int start = Math.floorMod(claimCursor.getAndIncrement(), NppaRedisKeys.LEDGER_SHARD_COUNT);
        List<Claimed> out = new ArrayList<>(Math.min(limit, NppaClient.MAX_BATCH));
        for (int i = 0; i < NppaRedisKeys.LEDGER_SHARD_COUNT && out.size() < limit; i++) {
            int shard = (start + i) % NppaRedisKeys.LEDGER_SHARD_COUNT;
            int want = limit - out.size();
            List<Object> members = redis.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, CLAIM_SCRIPT, RScript.ReturnType.MULTI,
                    List.of(keys.ledgerOutbox(shard)),
                    Integer.toString(want), Long.toString(CLAIM_VISIBILITY.toMillis()));
            if (members == null) {
                continue;
            }
            for (Object raw : members) {
                String member = raw.toString();
                NppaClient.Behavior b = parseEvent(member);
                out.add(new Claimed(shard, member, b, b != null));
            }
        }
        return out;
    }

    public void ack(List<Claimed> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byShard = groupByShard(items);
        for (var entry : byShard.entrySet()) {
            redis.<String>getScoredSortedSet(keys.ledgerOutbox(entry.getKey()), StringCodec.INSTANCE)
                    .removeAll(entry.getValue());
        }
    }

    public void release(List<Claimed> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byShard = groupByShard(items);
        for (var entry : byShard.entrySet()) {
            Object[] args = entry.getValue().toArray();
            redis.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, RELEASE_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(keys.ledgerOutbox(entry.getKey())), args);
        }
    }

    private static Map<Integer, List<String>> groupByShard(List<Claimed> items) {
        Map<Integer, List<String>> map = new LinkedHashMap<>();
        for (Claimed item : items) {
            map.computeIfAbsent(item.shard(), k -> new ArrayList<>()).add(item.member());
        }
        return map;
    }

    static NppaClient.Behavior parseEvent(String member) {
        String[] parts = member.split("\\|", -1);
        if (parts.length != 4 || parts[2].isEmpty() || parts[3].isEmpty()) {
            return null;
        }
        long ot;
        try {
            ot = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        int bt;
        if ("in".equals(parts[0])) {
            bt = NppaClient.Behavior.LOGIN;
        } else if ("out".equals(parts[0])) {
            bt = NppaClient.Behavior.LOGOUT;
        } else {
            return null;
        }
        return new NppaClient.Behavior(0, parts[2], bt, ot, 0, parts[3]);
    }
}
