package com.game.discovery.battle;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 回合制战斗在 Redis 上的全部脚本与跨进程常量（scene-battle-spec §7.2；scene 与 battle 共用，唯一出处）。
 *
 * <p><b>键</b>（都经 {@link RedisKeys}）：锁 {@code xm:battle:{pid}:lock}（Hash：{@code b n s d p}）、待结算记录 {@code xm:battle:{pid}:settlement}
 * （Hash：字段名 = battle_id，值 = {@code BattleSettlementEvent} 字节）、活动结果 {@code xm:battle:activity-result:<id>}。
 * 一切比较都用 battle_id 的<b>无符号十进制串</b>；脚本走 {@link ByteArrayCodec}（值是原样字节）。
 *
 * <p><b>重放语义</b>（§7.2）：Redisson 配置 {@code retryAttempts = 1}，超时的 EVAL 会被原样重发，第一次可能已经执行。每段可变脚本的重放结论：
 * {@code CONFIRM / TOUCH / HOLD / DELETE_IF_MATCH / STORE_SETTLEMENT} 幂等；{@code CANCEL_OFFLINE} 重放回 0；{@code ACK} 重放回 0（丢了位 2 的「补一次跟随」）；
 * {@code ACK_IF_SUPERSEDED} 重放回 3；{@code PREPARE_LOCK} 同局且仍是 {@code P} 的命中按重放成功（回 {@code "0"}）。
 *
 * <p>全部方法异步、不阻塞、线程安全；返回的 future 在 Redisson 回调线程上完成（调用方自己投递回所属线程），出错以异常完成。
 */
public final class BattleRedis {

    // ------------------------------------------------------------------ 跨进程常量（代码常量，不开放配置，§7.2 表）

    /** 锁必须比冻结活得久的余量（基线 {@code kLockExtraTtlSec}）：锁 TTL = 有效期限剩余 + 60 s。node-spec §10.4 的确认补发窗口 180 ≥ 96 + 60 引用它。 */
    public static final long LOCK_EXTRA_TTL_SEC = 60;
    /** 结算应用后锁至少再保持（基线 {@code kSettlementLockHoldSec}）：≥ 重投窗口 10 s × 12 + 60 s。 */
    public static final long LOCK_HOLD_AFTER_APPLY_SEC = 180;
    /** 待结算记录 TTL（7 天，两端同值）。 */
    public static final long SETTLEMENT_TTL_SEC = 604_800;
    /** battle 发件箱重投间隔（基线 {@code room.h:172-176}）。 */
    public static final Duration SETTLEMENT_RETRY_INTERVAL = Duration.ofSeconds(10);
    /** 重投次数上限：12 次重投、第 13 轮判用尽（次数在判定之后才加，§3.4）。 */
    public static final int SETTLEMENT_RETRY_MAX = 12;
    /** 活动结果持久副本 TTL（7 天）。 */
    public static final long ACTIVITY_RESULT_TTL_SEC = 604_800;
    /** 活动结果重发间隔。 */
    public static final Duration ACTIVITY_RETRY_INTERVAL = Duration.ofSeconds(10);
    /** 活动结果重发次数上限（只在重发时计次，30 次重发后第 31 轮用尽）。 */
    public static final int ACTIVITY_RETRY_MAX = 30;
    /** scene reaper 缺省间隔（基线 {@code pb.h:101-102}）；配置只许调小。 */
    public static final Duration REAPER_INTERVAL = Duration.ofSeconds(30);
    /** FIGHTING 判废宽限（D21）：GRACE + REAPER_INTERVAL &lt; LOCK_EXTRA_TTL_SEC，第一次 rescue 时锁一定还在。 */
    public static final Duration FIGHTING_EXPIRY_GRACE = Duration.ofSeconds(10);
    /** 两个发件箱条目登记后的最长寿命（Java 独有）：一直探测 / 定位出错、从不计次的条目按用尽摘除。 */
    public static final Duration OUTBOX_MAX_AGE = Duration.ofMinutes(10);
    /** {@code STORE_SETTLEMENT} 返回的字段数超过它就 ERROR（正常为 1）。 */
    public static final int SETTLEMENT_FIELDS_WARN = 16;
    /** scene 结算账本容量（基线 {@code ledger.h:32}，异常兜底）。 */
    public static final int LEDGER_CAPACITY = 64;

    /** 锁 Hash 的字段名。 */
    public static final String FIELD_BATTLE = "b";
    public static final String FIELD_NODE = "n";
    public static final String FIELD_STATE = "s";
    public static final String FIELD_DEADLINE = "d";
    public static final String FIELD_PREPARE_DEADLINE = "p";
    /** 锁阶段取值。 */
    public static final String STATE_PREPARING = "P";
    public static final String STATE_FIGHTING = "F";

    // ------------------------------------------------------------------ 脚本（语义逐条对齐基线，§7.2 表）

    /**
     * 备战写锁（D3）：KEYS[1] = lock；ARGV = X, n, d, p, ttl。键里没有 b → 写入、回 "0"；b == X 且 s == P → 重放，重写 n d p 并续 TTL、回 "0"；
     * 其余（别的局，或同局已 F）→ 回现在的 b（拒绝）。
     */
    static final String PREPARE_LOCK = """
            local cur = redis.call('HGET', KEYS[1], 'b')
            if not cur then
              redis.call('HSET', KEYS[1], 'b', ARGV[1], 'n', ARGV[2], 's', 'P', 'd', ARGV[3], 'p', ARGV[4])
              redis.call('EXPIRE', KEYS[1], ARGV[5])
              return '0'
            end
            if cur == ARGV[1] and redis.call('HGET', KEYS[1], 's') == 'P' then
              redis.call('HSET', KEYS[1], 'n', ARGV[2], 'd', ARGV[3], 'p', ARGV[4])
              redis.call('EXPIRE', KEYS[1], ARGV[5])
              return '0'
            end
            return cur
            """;

    /**
     * 确认（离线确认 / 在线升级 / 迟到确认重建共用）：KEYS[1] = lock；ARGV = X, d, ttl。b ≠ X → nil；否则 s = F、d ≠ "0" 时写 d、
     * ttl ≠ "0" 时续期，回 HGETALL（扁平数组）。
     */
    static final String CONFIRM = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return false
            end
            redis.call('HSET', KEYS[1], 's', 'F')
            if ARGV[2] ~= '0' then
              redis.call('HSET', KEYS[1], 'd', ARGV[2])
            end
            if ARGV[3] ~= '0' then
              redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return redis.call('HGETALL', KEYS[1])
            """;

    /** 离线取消：KEYS[1] = lock；ARGV = X。b ≠ X → 0；s == F → 2（拒绝）；否则 DEL → 1。 */
    static final String CANCEL_OFFLINE = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            if redis.call('HGET', KEYS[1], 's') == 'F' then
              return 2
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    /** 条件删锁：KEYS[1] = lock；ARGV = X。b == X → DEL → 1，否则 0。 */
    static final String DELETE_IF_MATCH = """
            if redis.call('HGET', KEYS[1], 'b') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """;

    /** 登录重建复核：KEYS[1] = lock；ARGV = X, ttl, s, d, p。b == X → HSET s d p + EXPIRE → 1，否则 0。 */
    static final String TOUCH = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1], 's', ARGV[3], 'd', ARGV[4], 'p', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """;

    /** 结算后续锁（只延不缩）：KEYS[1] = lock；ARGV = X, hold。b ≠ X → 0；TTL ∈ [0, hold) 就 EXPIRE hold；回 1。 */
    static final String HOLD = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            local t = redis.call('TTL', KEYS[1])
            if t >= 0 and t < tonumber(ARGV[2]) then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """;

    /**
     * 销账与放锁同一段（基线 {@code kAckSettlementScript}）：KEYS = lock, settlement；ARGV = X。HDEL settlement X 删到 → 位 1；
     * lock 的 b == X → DEL lock、位 2。回位掩码。
     */
    static final String ACK = """
            local r = 0
            if redis.call('HDEL', KEYS[2], ARGV[1]) == 1 then
              r = 1
            end
            if redis.call('HGET', KEYS[1], 'b') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              r = r + 2
            end
            return r
            """;

    /**
     * 进场恢复的一致快照：KEYS = lock, settlement。回扁平数组 {TTL(lock), 锁字段数 × 2, 锁的 k v …, 记录的 k v …}。
     */
    static final String ENTER_READ = """
            local out = {}
            out[1] = redis.call('TTL', KEYS[1])
            local lock = redis.call('HGETALL', KEYS[1])
            out[2] = #lock
            for i = 1, #lock do
              out[#out + 1] = lock[i]
            end
            local s = redis.call('HGETALL', KEYS[2])
            for i = 1, #s do
              out[#out + 1] = s[i]
            end
            return out
            """;

    /** rescue（D21）读本局记录：KEYS[1] = settlement；ARGV = X → 记录字节或 nil。 */
    static final String READ_IF_OURS = """
            return redis.call('HGET', KEYS[1], ARGV[1])
            """;

    /** 读锁的 b（结算到达且没有冻结时按锁应用，§7.10 第 7 步）：KEYS[1] = lock → b 或 nil。 */
    static final String READ_LOCK_BATTLE = """
            return redis.call('HGET', KEYS[1], 'b')
            """;

    /** 删单个字段（坏字段，§7.8 第 2 步）：KEYS[1] = settlement；ARGV = X → 删到的个数。 */
    static final String DELETE_SETTLEMENT_FIELD = """
            return redis.call('HDEL', KEYS[1], ARGV[1])
            """;

    /** battle 落库：KEYS[1] = settlement；ARGV = X, bytes, ttl。HSET + EXPIRE → 回 HLEN。 */
    static final String STORE_SETTLEMENT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return redis.call('HLEN', KEYS[1])
            """;

    /** battle 探测：KEYS[1] = settlement；ARGV = X → HEXISTS（0 / 1）。 */
    static final String PROBE_SETTLEMENT = """
            return redis.call('HEXISTS', KEYS[1], ARGV[1])
            """;

    /**
     * 离线玩家的「已取代」判定（D17）：KEYS = lock, settlement；ARGV = X。记录已不在 → 3（已销账）；锁不在 → 0；b == X → 1（仍是本局）；
     * 否则 HDEL X → 2（已被取代）。
     */
    static final String ACK_IF_SUPERSEDED = """
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 0 then
              return 3
            end
            local b = redis.call('HGET', KEYS[1], 'b')
            if not b then
              return 0
            end
            if b == ARGV[1] then
              return 1
            end
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 2
            """;

    /** 活动结果落库：KEYS[1] = activity-result；ARGV = bytes, ttl。 */
    static final String STORE_ACTIVITY_RESULT = """
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """;

    /** 活动结果探测：KEYS[1] = activity-result → EXISTS。 */
    static final String PROBE_ACTIVITY_RESULT = """
            return redis.call('EXISTS', KEYS[1])
            """;

    // ------------------------------------------------------------------ 结果形状

    /**
     * 进场恢复读到的一致快照。
     *
     * @param lock        锁的字段（没有锁为空表）
     * @param lockTtlSec  锁的 TTL（秒；-2 = 不存在，-1 = 永不过期）
     * @param settlements 待结算记录：字段名（原样串）→ 值字节，保持读出顺序
     */
    public record EnterRead(Map<String, String> lock, long lockTtlSec, Map<String, byte[]> settlements) {

        public EnterRead {
            lock = Map.copyOf(lock);
            settlements = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settlements));
        }

        /** 锁的 b（无符号十进制解析）；没有锁 / 解析失败为 0。 */
        public long lockBattleId() {
            return parseUnsigned(lock.get(FIELD_BATTLE));
        }
    }

    private final RedissonClient redis;

    public BattleRedis(RedissonClient redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    // ------------------------------------------------------------------ 纯函数

    /**
     * 锁 TTL（基线 {@code LockTtlSecFor}，{@code pb.cpp:229-234}）：{@code max(0, 有效期限 − now) / 1000}（整数截断）+ 60。
     */
    public static long lockTtlSec(long effectiveDeadlineMs, long nowMs) {
        long remaining = effectiveDeadlineMs - nowMs;
        return (remaining > 0 ? remaining / 1000 : 0) + LOCK_EXTRA_TTL_SEC;
    }

    /** 无符号十进制解析；null / 空 / 非法为 0。 */
    public static long parseUnsigned(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ scene 侧

    /** {@link #PREPARE_LOCK}：回 "0" = 写入（或重放）成功；否则回现在的 b（被占）。 */
    public CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId, long deadlineMs,
                                                 long prepareDeadlineMs, long ttlSec) {
        return this.<byte[]>eval(RScript.Mode.READ_WRITE, PREPARE_LOCK, RScript.ReturnType.VALUE,
                        List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), text(Integer.toUnsignedString(battleNodeId)),
                        unsigned(deadlineMs), unsigned(prepareDeadlineMs), number(ttlSec))
                .thenApply(raw -> raw == null ? "" : new String(raw, StandardCharsets.UTF_8));
    }

    /** {@link #CONFIRM}：b == X 时回锁的全部字段（已改成 F），否则回 null。{@code deadlineMs} / {@code ttlSec} 为 0 = 不改。 */
    public CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec) {
        return this.<List<Object>>eval(RScript.Mode.READ_WRITE, CONFIRM, RScript.ReturnType.MULTI,
                        List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), unsigned(deadlineMs), number(ttlSec))
                .thenApply(list -> list == null ? null : pairs(list, 0, list.size()));
    }

    /** {@link #CANCEL_OFFLINE}：1 删了 / 2 FIGHTING 拒绝 / 0 不是本局。 */
    public CompletableFuture<Long> cancelOffline(long playerId, long battleId) {
        return integer(CANCEL_OFFLINE, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId));
    }

    /** {@link #DELETE_IF_MATCH}：1 删了 / 0 不是本局。 */
    public CompletableFuture<Long> deleteIfMatch(long playerId, long battleId) {
        return integer(DELETE_IF_MATCH, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId));
    }

    /** {@link #TOUCH}：1 命中 / 0 不是本局。 */
    public CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String state, long deadlineMs,
                                         long prepareDeadlineMs) {
        return integer(TOUCH, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), number(ttlSec), text(state),
                unsigned(deadlineMs), unsigned(prepareDeadlineMs));
    }

    /** {@link #HOLD}：1 命中（只延不缩）/ 0 不是本局。 */
    public CompletableFuture<Long> hold(long playerId, long battleId, long holdSec) {
        return integer(HOLD, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), number(holdSec));
    }

    /** {@link #ACK}：位 1 = 删了记录，位 2 = 放了锁。 */
    public CompletableFuture<Long> ack(long playerId, long battleId) {
        return integer(ACK, List.of(RedisKeys.battleLock(playerId), RedisKeys.battleSettlements(playerId)), unsigned(battleId));
    }

    /** {@link #ENTER_READ}。 */
    public CompletableFuture<EnterRead> enterRead(long playerId) {
        return this.<List<Object>>eval(RScript.Mode.READ_ONLY, ENTER_READ, RScript.ReturnType.MULTI,
                        List.of(RedisKeys.battleLock(playerId), RedisKeys.battleSettlements(playerId)))
                .thenApply(BattleRedis::toEnterRead);
    }

    /** {@link #READ_IF_OURS}：本局记录的字节，没有为 null。 */
    public CompletableFuture<byte[]> readSettlement(long playerId, long battleId) {
        return this.<byte[]>eval(RScript.Mode.READ_ONLY, READ_IF_OURS, RScript.ReturnType.VALUE,
                List.of(RedisKeys.battleSettlements(playerId)), unsigned(battleId));
    }

    /** {@link #READ_LOCK_BATTLE}：锁的 b；没有锁为 0。 */
    public CompletableFuture<Long> readLockBattleId(long playerId) {
        return this.<byte[]>eval(RScript.Mode.READ_ONLY, READ_LOCK_BATTLE, RScript.ReturnType.VALUE,
                        List.of(RedisKeys.battleLock(playerId)))
                .thenApply(raw -> raw == null ? 0L : parseUnsigned(new String(raw, StandardCharsets.UTF_8)));
    }

    /** {@link #DELETE_SETTLEMENT_FIELD}：按原样字段名删一个字段（坏字段）。 */
    public CompletableFuture<Long> deleteSettlementField(long playerId, String field) {
        return integer(DELETE_SETTLEMENT_FIELD, List.of(RedisKeys.battleSettlements(playerId)), text(field));
    }

    // ------------------------------------------------------------------ battle 侧

    /** {@link #STORE_SETTLEMENT}：回落库后的字段数。 */
    public CompletableFuture<Long> storeSettlement(long playerId, long battleId, byte[] payload, long ttlSec) {
        return integer(STORE_SETTLEMENT, List.of(RedisKeys.battleSettlements(playerId)), unsigned(battleId), payload,
                number(ttlSec));
    }

    /** {@link #PROBE_SETTLEMENT}：记录还在吗。 */
    public CompletableFuture<Boolean> settlementExists(long playerId, long battleId) {
        return integer(PROBE_SETTLEMENT, List.of(RedisKeys.battleSettlements(playerId)), unsigned(battleId))
                .thenApply(n -> n != null && n != 0);
    }

    /** {@link #ACK_IF_SUPERSEDED}：3 已销账 / 0 锁不在 / 1 仍是本局 / 2 已被取代（删了记录）。 */
    public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
        return integer(ACK_IF_SUPERSEDED, List.of(RedisKeys.battleLock(playerId), RedisKeys.battleSettlements(playerId)),
                unsigned(battleId));
    }

    /** 活动结果落库（{@code SET EX}）。 */
    public CompletableFuture<Long> storeActivityResult(long battleId, byte[] payload, long ttlSec) {
        return integer(STORE_ACTIVITY_RESULT, List.of(RedisKeys.battleActivityResult(battleId)), payload, number(ttlSec));
    }

    /** 活动结果还在吗（EXISTS）。 */
    public CompletableFuture<Boolean> activityResultExists(long battleId) {
        return integer(PROBE_ACTIVITY_RESULT, List.of(RedisKeys.battleActivityResult(battleId)))
                .thenApply(n -> n != null && n != 0);
    }

    // ------------------------------------------------------------------ 内部

    private CompletableFuture<Long> integer(String script, List<Object> keys, byte[]... args) {
        return this.<Long>eval(RScript.Mode.READ_WRITE, script, RScript.ReturnType.INTEGER, keys, args);
    }

    private <R> CompletableFuture<R> eval(RScript.Mode mode, String script, RScript.ReturnType type, List<Object> keys,
                                          byte[]... args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<R>evalAsync(mode, script, type, keys, (Object[]) args)
                    .toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static EnterRead toEnterRead(List<Object> flat) {
        if (flat == null || flat.size() < 2) {
            throw new IllegalStateException("ENTER_READ 回复形状不对: " + flat);
        }
        long ttl = asLong(flat.get(0));
        int lockLen = (int) asLong(flat.get(1));
        if (lockLen < 0 || 2 + lockLen > flat.size()) {
            throw new IllegalStateException("ENTER_READ 锁段长度不对: " + lockLen);
        }
        Map<String, String> lock = pairs(flat, 2, 2 + lockLen);
        Map<String, byte[]> settlements = new LinkedHashMap<>();
        for (int i = 2 + lockLen; i + 1 < flat.size(); i += 2) {
            settlements.put(asText(flat.get(i)), asBytes(flat.get(i + 1)));
        }
        return new EnterRead(lock, ttl, settlements);
    }

    private static Map<String, String> pairs(List<Object> flat, int from, int to) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = from; i + 1 < to; i += 2) {
            out.put(asText(flat.get(i)), asText(flat.get(i + 1)));
        }
        return out;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof byte[] b) {
            return Long.parseLong(new String(b, StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("期望整数，得到 " + value);
    }

    private static String asText(Object value) {
        if (value instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static byte[] asBytes(Object value) {
        if (value instanceof byte[] b) {
            return b;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] unsigned(long value) {
        return text(Long.toUnsignedString(value));
    }

    static byte[] number(long value) {
        return text(Long.toString(value));
    }

    static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 测试用：把扁平数组按 ENTER_READ 的形状拼出来。 */
    static List<Object> flatEnterRead(long ttl, Map<String, String> lock, Map<String, byte[]> settlements) {
        List<Object> out = new ArrayList<>();
        out.add(ttl);
        out.add((long) lock.size() * 2);
        lock.forEach((k, v) -> {
            out.add(text(k));
            out.add(text(v));
        });
        settlements.forEach((k, v) -> {
            out.add(text(k));
            out.add(v);
        });
        return out;
    }
}
