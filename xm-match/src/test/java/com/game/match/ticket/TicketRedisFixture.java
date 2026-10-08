package com.game.match.ticket;

import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.redisson.config.Config;

/**
 * 票据存储真 Redis 集成测试的夹具（{@code -Dxm.it.redis=redis://127.0.0.1:6379}）。
 *
 * <ul>
 *   <li><b>DB 13</b>，不是进程缺省的 12：{@code xm:{match}:index} 是全局键，本机切片里跑着的 xm-match（DB 12）会把测试队列里的人弹走。</li>
 *   <li>多人共用这台 Redis：玩家号、队列的副本号、弹组 token 每个夹具取随机的一段，互不重叠；{@link #cleanup()} <b>只删自己分配过的键</b>
 *       （票、队列、评分镜像、凑单锁、弹组标记），注册集只 SREM 自己的队列键。不 FLUSHDB。</li>
 *   <li>真 Redis 拨不动 {@code TIME}：要「过期之后」就把键删掉，要「等了很久 / 退避到点」就改写票里的时刻（{@link #putTicket}）。</li>
 * </ul>
 * 读写口（{@link #putTicket} / {@link #rawTicket} / {@link #queueMembers}……）绕过存储的 Lua 直接操作键，用来摆状态与断言。
 */
public final class TicketRedisFixture {

    public static final int DB = 13;

    public final RedissonClient redis;
    public final RedissonTicketStore store;

    private final long pidBase;
    private final int configBase;
    private final String run;
    private final Set<Long> pids = new LinkedHashSet<>();
    private final Set<QueueRef> queues = new LinkedHashSet<>();
    private final Set<String> tokens = new LinkedHashSet<>();
    /** 回队首的 token：只登记清理它们的重放标记，不进 {@link #allKeys()}（见 {@link #cleanup()}）。 */
    private final Set<String> requeueTokens = new LinkedHashSet<>();
    private int freshTokens;

    public static RedissonClient connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB).setConnectionMinimumIdleSize(2).setConnectionPoolSize(16);
        return Redisson.create(config);
    }

    /**
     * @param highIds true = 玩家号取 ≥ 2^63 的一段（键名、Lua 里的比较、成员串都必须按无符号）；false = 取 2^40 量级的普通号
     */
    public TicketRedisFixture(RedissonClient redis, boolean highIds) {
        this.redis = redis;
        this.store = new RedissonTicketStore(redis);
        long r = ThreadLocalRandom.current().nextLong(1L << 36) * 4096;
        this.pidBase = (highIds ? Long.MIN_VALUE : 0) + (1L << 50) + r;
        this.configBase = ThreadLocalRandom.current().nextInt() & 0xFFFF_FF00;
        this.run = Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    // ---------------------------------------------------------------- 分配（并登记清理）

    public long pid(int n) {
        long playerId = pidBase + n;
        pids.add(playerId);
        return playerId;
    }

    /** 本夹具的第 n 条队列：副本号是随机的一段（可能 ≥ 2^31，键里按无符号十进制）。 */
    public QueueRef queue(int mode, int n) {
        QueueRef queue = new QueueRef(mode, configBase + n);
        queues.add(queue);
        return queue;
    }

    /** 登记一条不是经 {@link #queue} 分配的队列（用例自己指定了副本号），让 {@link #cleanup()} 一并清掉。 */
    public QueueRef track(QueueRef queue) {
        queues.add(queue);
        return queue;
    }

    /** 本夹具的第 n 个副本号（给走排队入口的用例：请求里带它，队列就落在夹具自己的段里）。 */
    public int configId(int n) {
        return configBase + n;
    }

    public String token(String name) {
        String token = name + "-" + run;
        tokens.add(token);
        return token;
    }

    /** 本夹具的一个回队首 token（同名同值）。 */
    public String requeueToken(String name) {
        String token = name + "-" + run;
        requeueTokens.add(token);
        return token;
    }

    /** 一个新的回队首 token（每次调用都不同）：不测重放的用例每次回队首用一个新的。 */
    public String freshToken() {
        return requeueToken("fresh-" + (++freshTokens));
    }

    public static String ticketKey(long playerId) {
        return RedisKeys.matchTicket(playerId);
    }

    public List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (long playerId : pids) {
            keys.add(ticketKey(playerId));
        }
        for (QueueRef queue : queues) {
            keys.add(queue.queueKey());
            keys.add(queue.rankKey());
            keys.add(queue.lockKey());
        }
        for (String token : tokens) {
            keys.add(RedisKeys.matchPopMarker(token));
        }
        return keys;
    }

    public void cleanup() {
        List<String> keys = allKeys();
        // 回队首的重放标记只在这里清，不进 allKeys()：它不属于「票据 / 队列的状态」，逐字节比「什么都没改」的快照不看它（各用例单独断言）
        for (String token : requeueTokens) {
            keys.add(RedisKeys.matchRequeueMarker(token));
        }
        for (int i = 0; i < keys.size(); i += 256) {
            redis.getKeys().delete(keys.subList(i, Math.min(keys.size(), i + 256)).toArray(String[]::new));
        }
        if (!queues.isEmpty()) {
            List<String> queueKeys = queues.stream().map(QueueRef::queueKey).toList();
            redis.<String>getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).removeAll(queueKeys);
        }
    }

    // ---------------------------------------------------------------- 时钟

    /** Redis {@code TIME}（毫秒）。 */
    public long nowMs() {
        List<Object> time = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('TIME')", RScript.ReturnType.MULTI,
                List.of());
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }

    // ---------------------------------------------------------------- 票据

    /** 直接写一张票（覆盖已有的；只写正常写者会写的字段：队伍号 / 战斗号 / 退避时刻为 0 时不写）。{@code ttlMs ≤ 0} = 这张票已过期（删键）。 */
    public void putTicket(long playerId, Ticket ticket, long ttlMs) {
        String key = ticketKey(playerId);
        redis.getKeys().delete(key);
        if (ttlMs <= 0) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TicketCodec.F_TICKET, ticket.ticketId());
        fields.put(TicketCodec.F_MODE, Integer.toString(ticket.mode()));
        fields.put(TicketCodec.F_CONFIG, Integer.toUnsignedString(ticket.configId()));
        fields.put(TicketCodec.F_STATE, ticket.state() == TicketState.UNKNOWN ? "entering" : ticket.state().wire());
        fields.put(TicketCodec.F_ENQUEUED_AT_MS, Long.toUnsignedString(ticket.enqueuedAtMs()));
        fields.put(TicketCodec.F_ZONE_ID, Integer.toUnsignedString(ticket.zoneId()));
        fields.put(TicketCodec.F_QUEUE_KEY, ticket.queueKey());
        fields.put(TicketCodec.F_RATING_CENTI, Long.toString(ticket.ratingCenti()));
        if (ticket.teamId() != 0) {
            fields.put(TicketCodec.F_TEAM_ID, Long.toUnsignedString(ticket.teamId()));
        }
        if (ticket.battleId() != 0) {
            fields.put(TicketCodec.F_BATTLE_ID, Long.toUnsignedString(ticket.battleId()));
        }
        if (ticket.notBeforeMs() != 0) {
            fields.put(TicketCodec.F_NOT_BEFORE_MS, Long.toUnsignedString(ticket.notBeforeMs()));
        }
        redis.<String, String>getMap(key, StringCodec.INSTANCE).putAll(fields);
        redis.<String, String>getMap(key, StringCodec.INSTANCE).expire(Duration.ofMillis(ttlMs));
    }

    /** 票据 HASH 的原始字段（键不存在为空表）。 */
    public Map<String, String> rawTicket(long playerId) {
        return new LinkedHashMap<>(redis.<String, String>getMap(ticketKey(playerId), StringCodec.INSTANCE).readAllMap());
    }

    public Optional<Ticket> ticketOf(long playerId) {
        List<String> flat = new ArrayList<>();
        rawTicket(playerId).forEach((field, value) -> {
            flat.add(field);
            flat.add(value);
        });
        return TicketCodec.decode(flat, playerId);
    }

    /** 改一个字段（票必须存在）。 */
    public void setField(long playerId, String field, String value) {
        redis.<String, String>getMap(ticketKey(playerId), StringCodec.INSTANCE).put(field, value);
    }

    public void removeField(long playerId, String field) {
        redis.<String, String>getMap(ticketKey(playerId), StringCodec.INSTANCE).remove(field);
    }

    /** {@code PTTL}：-2 键不存在，-1 没有 TTL。 */
    public long pttl(String key) {
        return redis.getKeys().remainTimeToLive(key);
    }

    /** 直接改一把键的剩余寿命（模拟「已经过去了一段时间」：真 Redis 拨不动时钟）。 */
    public void pexpire(String key, long ttlMs) {
        redis.getKeys().expire(key, ttlMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 票据剩余的 TTL（毫秒）；没有票为 -1。票没有 TTL 是 bug（每个写者都设）。 */
    public long ticketTtlMs(long playerId) {
        long ttl = pttl(ticketKey(playerId));
        if (ttl == -2) {
            return -1;
        }
        if (ttl == -1) {
            throw new AssertionError("票据没有 TTL player=" + Long.toUnsignedString(playerId));
        }
        return ttl;
    }

    // ---------------------------------------------------------------- 队列、镜像、注册集、锁

    /** 直接往队尾放一个成员并登记注册集；{@code ratingCenti} 为 null 时不写评分镜像。 */
    public void putQueueMember(QueueRef queue, String member, Long ratingCenti) {
        redis.<String>getList(queue.queueKey(), StringCodec.INSTANCE).add(member);
        if (ratingCenti != null) {
            redis.<String>getScoredSortedSet(queue.rankKey(), StringCodec.INSTANCE).add(ratingCenti, member);
        }
        redis.<String>getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).add(queue.queueKey());
    }

    public List<String> queueMembers(QueueRef queue) {
        return new ArrayList<>(redis.<String>getList(queue.queueKey(), StringCodec.INSTANCE).readAll());
    }

    public Map<String, Long> rankOf(QueueRef queue) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ScoredEntry<String> entry : redis.<String>getScoredSortedSet(queue.rankKey(), StringCodec.INSTANCE).entryRange(0, -1)) {
            out.put(entry.getValue(), Math.round(entry.getScore()));
        }
        return out;
    }

    public boolean indexed(QueueRef queue) {
        return redis.<String>getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).contains(queue.queueKey());
    }

    public void unindex(QueueRef queue) {
        redis.<String>getSet(RedisKeys.matchQueueIndex(), StringCodec.INSTANCE).remove(queue.queueKey());
    }

    public Optional<String> lockHolder(QueueRef queue) {
        return Optional.ofNullable(redis.<String>getBucket(queue.lockKey(), StringCodec.INSTANCE).get());
    }

    /** 一把 STRING 键的值；不存在为 null。 */
    public String string(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).get();
    }

    public boolean exists(String key) {
        return redis.getKeys().countExists(key) == 1;
    }

    public void del(String key) {
        redis.getKeys().delete(key);
    }

    /** 把一把键占成字符串（模拟键被人为写成了别的类型）。 */
    public void setString(String key, String value) {
        redis.getKeys().delete(key);
        redis.<String>getBucket(key, StringCodec.INSTANCE).set(value);
    }

    /** {@code TYPE}：none / string / list / set / zset / hash。 */
    public String type(String key) {
        return redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('TYPE', KEYS[1]).ok", RScript.ReturnType.VALUE,
                List.<Object>of(key));
    }

    // ---------------------------------------------------------------- 原始字节快照

    /** 本夹具全部键的 {@code DUMP}（十六进制；键不存在为空串）：比较「没有任何写入」用，逐字节。不含 TTL。 */
    public Map<String, String> dump() {
        List<String> keys = allKeys();
        Map<String, String> out = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        List<Object> reply = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, """
                local out = {}
                for i = 1, #KEYS do
                  out[#out + 1] = redis.call('DUMP', KEYS[i]) or ''
                end
                return out
                """, RScript.ReturnType.MULTI, new ArrayList<Object>(keys));
        for (int i = 0; i < keys.size(); i++) {
            out.put(keys.get(i), HexFormat.of().formatHex((byte[]) reply.get(i)));
        }
        return out;
    }
}
