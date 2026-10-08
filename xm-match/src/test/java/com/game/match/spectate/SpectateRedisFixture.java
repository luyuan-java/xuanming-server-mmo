package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.placement.PlacementStore;
import com.game.match.placement.RedissonPlacementStore;
import com.game.match.proto.BattlePlacement;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketRedisFixture;
import java.nio.charset.StandardCharsets;
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
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.redisson.codec.CompositeCodec;

/**
 * 观战存储真 Redis 集成测试的夹具（{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13，同 {@link TicketRedisFixture}）。
 *
 * <ul>
 *   <li><b>每个夹具一把自己的索引键</b>（{@link #indexKey} = 生产键 + {@code ":it:<随机>"}，同在 {@code {match}} 槽）：可观战索引是全局的一把键，
 *       共用这台 Redis 的别的用例会往里公开战斗、别的进程里的清扫器会摘掉过期成员——「索引此刻恰好这几个成员」的断言只有在自己的键上才成立。
 *       生产键只在个别用例里碰（随机的战斗号，只摘自己放进去的成员）。</li>
 *   <li>玩家号、战斗号每个夹具取随机的一段（都 ≥ 2^63：键名、成员、标记值必须按无符号十进制），互不重叠；{@link #cleanup()} <b>只删自己分配过的键</b>
 *       （票、标记、落点、自己的索引键）。不 FLUSHDB。</li>
 *   <li>真 Redis 拨不动 {@code TIME}：「过期的成员」写一个旧分数；恰在分界上的判定用 {@link #evalAt}——把脚本里取时间的那一处换成固定值，
 *       其余逐字不变地执行；「已经过去了一段时间」的 TTL 用 {@link #pexpire} 改短。</li>
 * </ul>
 * 读写口绕过观战存储的 Lua 直接操作键，用来摆状态与断言。落点记录经 6.4 的真写者 {@link RedissonPlacementStore} 写，票据经真的
 * {@link RedissonTicketStore} 建——观战脚本读的就是它们写出来的形状。
 */
public final class SpectateRedisFixture {

    public final RedissonClient redis;
    /** 本夹具的可观战索引键（不是生产键）。 */
    public final String indexKey;
    /** 用 {@link #indexKey} 的观战存储。 */
    public final RedissonSpectateStore store;
    /** 6.4 的落点存储（真写者）。 */
    public final RedissonPlacementStore placements;
    /** 票据夹具：玩家号从它分配（票据键由它清理），{@code tickets.store} 是真的票据存储。 */
    public final TicketRedisFixture tickets;

    private final long battleBase;
    private final Set<Long> pids = new LinkedHashSet<>();
    private final Set<Long> battles = new LinkedHashSet<>();
    private final Set<String> productionMembers = new LinkedHashSet<>();

    public SpectateRedisFixture(RedissonClient redis) {
        this.redis = redis;
        this.tickets = new TicketRedisFixture(redis, true);
        this.battleBase = Long.MIN_VALUE + (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 36) * 4096;
        this.indexKey = RedisKeys.matchWatchable() + ":it:" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        this.store = new RedissonSpectateStore(redis, indexKey);
        this.placements = new RedissonPlacementStore(redis);
    }

    // ---------------------------------------------------------------- 分配（并登记清理）

    public long pid(int n) {
        long playerId = tickets.pid(n);
        pids.add(playerId);
        return playerId;
    }

    public long battle(int n) {
        long battleId = battleBase + n;
        battles.add(battleId);
        return battleId;
    }

    public static String markKey(long playerId) {
        return RedisKeys.matchWatching(playerId);
    }

    public static String placementKey(long battleId) {
        return RedisKeys.matchBattlePlacement(battleId);
    }

    /** 本夹具的全部键：票、标记、落点、自己的索引键（不含生产索引键）。 */
    public List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (long playerId : pids) {
            keys.add(TicketRedisFixture.ticketKey(playerId));
            keys.add(markKey(playerId));
        }
        for (long battleId : battles) {
            keys.add(placementKey(battleId));
        }
        keys.add(indexKey);
        return keys;
    }

    public void cleanup() {
        List<String> keys = new ArrayList<>();
        keys.add(indexKey);
        for (long playerId : pids) {
            keys.add(markKey(playerId));
        }
        for (long battleId : battles) {
            keys.add(placementKey(battleId));
        }
        for (int i = 0; i < keys.size(); i += 256) {
            redis.getKeys().delete(keys.subList(i, Math.min(keys.size(), i + 256)).toArray(String[]::new));
        }
        if (!productionMembers.isEmpty()) {
            redis.getScoredSortedSet(RedisKeys.matchWatchable(), StringCodec.INSTANCE).removeAll(productionMembers);
        }
        tickets.cleanup();
    }

    /** 登记一个放进<b>生产</b>索引键的成员，{@link #cleanup()} 时摘掉（只摘登记过的，不动别人的）。 */
    public String trackProductionMember(long battleId) {
        String member = SpectateRules.member(battleId);
        productionMembers.add(member);
        return member;
    }

    // ---------------------------------------------------------------- 时钟

    /** Redis {@code TIME}（毫秒）。 */
    public long nowMs() {
        return tickets.nowMs();
    }

    /**
     * 用<b>钉住的时钟</b>执行一段脚本：把脚本里唯一的 {@code redis.call('TIME')} 换成 {@code {秒, 微秒}} 的字面量，其余逐字不变。
     * 脚本里没有、或不止一处取时间都是用法错误。
     *
     * @param type 脚本的返回类型（{@link RScript.ReturnType#MULTI} → {@code List<Object>}，整数是 {@code Long}、串是 {@code byte[]}；
     *             {@link RScript.ReturnType#INTEGER} → {@code Long}）
     */
    public <R> R evalAt(String lua, long seconds, long micros, RScript.ReturnType type, List<Object> keys, String... args) {
        String needle = "redis.call('TIME')";
        int first = lua.indexOf(needle);
        if (first < 0 || lua.indexOf(needle, first + 1) >= 0) {
            throw new AssertionError("脚本里应当恰好有一处取 TIME");
        }
        String pinned = lua.replace(needle, "{" + seconds + ", " + micros + "}");
        Object[] encoded = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            encoded[i] = args[i].getBytes(StandardCharsets.ISO_8859_1);
        }
        return redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, pinned, type, keys, encoded);
    }

    // ---------------------------------------------------------------- 票据

    /** 让票据键存在：一张最小的 HASH（只有票号一个字段），TTL 60 s。「有没有票」只看键在不在。 */
    public void putTicketKey(long playerId) {
        String key = TicketRedisFixture.ticketKey(playerId);
        RMap<String, String> hash = redis.getMap(key, StringCodec.INSTANCE);
        hash.put("ticket", "it-" + Long.toUnsignedString(playerId));
        hash.expire(Duration.ofSeconds(60));
    }

    // ---------------------------------------------------------------- 观战标记

    /** 直接放一个标记（覆盖已有的），TTL 360 s。值按 UTF-8 写入。 */
    public void putMark(long playerId, String value) {
        redis.getBucket(markKey(playerId), StringCodec.INSTANCE).set(value, Duration.ofSeconds(360));
    }

    /** 直接放一个标记，值是任意字节（可以不是合法的 UTF-8）。 */
    public void putMarkBytes(long playerId, byte[] value) {
        redis.getBucket(markKey(playerId), ByteArrayCodec.INSTANCE).set(value, Duration.ofSeconds(360));
    }

    public Optional<String> markOf(long playerId) {
        return Optional.ofNullable(redis.<String>getBucket(markKey(playerId), StringCodec.INSTANCE).get());
    }

    /** 标记剩余的 TTL（毫秒）；没有标记为 -1。标记没有 TTL 是 bug（写者一律带 PX）。 */
    public long markTtlMs(long playerId) {
        long ttl = pttl(markKey(playerId));
        if (ttl == -2) {
            return -1;
        }
        if (ttl == -1) {
            throw new AssertionError("观战标记没有 TTL player=" + Long.toUnsignedString(playerId));
        }
        return ttl;
    }

    // ---------------------------------------------------------------- 落点记录

    /** 经真写者写一条落点（HASH 两个字段 + TTL 360 s）。 */
    public void putPlacement(BattlePlacement placement) {
        if (!placements.write(placement)) {
            throw new AssertionError("落点写入失败 battle_id=" + Long.toUnsignedString(placement.getBattleId()));
        }
    }

    /** 落点 HASH 的原始读写口（字段名是文本、值是字节）。 */
    public RMap<String, byte[]> rawPlacement(long battleId) {
        return redis.getMap(placementKey(battleId), new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }

    /** 直接摆一条落点 HASH（覆盖已有的）：{@code a} / {@code pb} 为 null 就不写那个字段；两个都为 null 时写一个不相干的字段让键存在。 */
    public void putRawPlacement(long battleId, String a, byte[] pb) {
        redis.getKeys().delete(placementKey(battleId));
        RMap<String, byte[]> hash = rawPlacement(battleId);
        if (a != null) {
            hash.put("a", a.getBytes(StandardCharsets.UTF_8));
        }
        if (pb != null) {
            hash.put("pb", pb);
        }
        if (a == null && pb == null) {
            hash.put("other", new byte[] {1});
        }
        hash.expire(Duration.ofSeconds(360));
    }

    public boolean placementExists(long battleId) {
        return exists(placementKey(battleId));
    }

    /** 经 6.4 的读口读（好记录才有值）。 */
    public Optional<BattlePlacement> placementOf(long battleId) {
        return placements.read(battleId, Deadline.after(5_000)) instanceof PlacementStore.Read.Found found
                ? Optional.of(found.placement()) : Optional.empty();
    }

    // ---------------------------------------------------------------- 可观战索引（自己的那把键）

    /** 直接往自己的索引里放一个成员（成员按字节一一对应写入，同存储的口径）。 */
    public void putMember(String member, double score) {
        putMemberBytes(member.getBytes(StandardCharsets.ISO_8859_1), score);
    }

    public void putMemberBytes(byte[] member, double score) {
        rawIndex().add(score, member);
    }

    /** 自己的索引：成员 → 分数（四舍五入到毫秒），按分数升序。 */
    public Map<String, Long> index() {
        return indexOf(indexKey);
    }

    public Map<String, Long> indexOf(String key) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ScoredEntry<byte[]> entry : redis.<byte[]>getScoredSortedSet(key, ByteArrayCodec.INSTANCE).entryRange(0, -1)) {
            out.put(new String(entry.getValue(), StandardCharsets.ISO_8859_1), Math.round(entry.getScore()));
        }
        return out;
    }

    private RScoredSortedSet<byte[]> rawIndex() {
        return redis.getScoredSortedSet(indexKey, ByteArrayCodec.INSTANCE);
    }

    // ---------------------------------------------------------------- 任意键

    /** {@code PTTL}：-2 键不存在，-1 没有 TTL。 */
    public long pttl(String key) {
        return redis.getKeys().remainTimeToLive(key);
    }

    /** 直接改一把键的剩余寿命（模拟「已经过去了一段时间」）。 */
    public void pexpire(String key, long ttlMs) {
        tickets.pexpire(key, ttlMs);
    }

    public boolean exists(String key) {
        return tickets.exists(key);
    }

    /** 把一把键占成字符串（模拟键被人为写成了别的类型）。带 10 分钟的 TTL：用例中途崩了也不会在共用的库里留下永久的键。 */
    public void setString(String key, String value) {
        tickets.setString(key, value);
        tickets.pexpire(key, 600_000);
    }

    /** {@code TYPE}：none / string / list / set / zset / hash。 */
    public String type(String key) {
        return tickets.type(key);
    }

    /**
     * {@link #allKeys()} 里<b>此刻存在</b>的键的 {@code DUMP}（十六进制）：比较「什么都没改」用，逐字节。不含 TTL。
     * 不存在的键不出现在结果里——两次快照之间多分配了几个号（还没有键）不算变化，多出一把键 / 少了一把键 / 内容变了才算。
     */
    public Map<String, String> dump() {
        List<String> keys = allKeys();
        List<Object> reply = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, """
                local out = {}
                for i = 1, #KEYS do
                  out[#out + 1] = redis.call('DUMP', KEYS[i]) or ''
                end
                return out
                """, RScript.ReturnType.MULTI, new ArrayList<Object>(keys));
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            byte[] dumped = (byte[]) reply.get(i);
            if (dumped.length > 0) {
                out.put(keys.get(i), HexFormat.of().formatHex(dumped));
            }
        }
        return out;
    }
}
