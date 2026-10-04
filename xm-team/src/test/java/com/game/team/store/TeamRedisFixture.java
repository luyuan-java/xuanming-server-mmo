package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.team.TeamRedisFields;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 组队存储真 Redis 集成测试的夹具（team-spec §10.2）。
 *
 * <ul>
 *   <li>DB 12；id 取随机大号（玩家号与队伍号都 ≥ 2^63，键名、Lua 比较、解析都必须按无符号），每个夹具一段互不重叠的区间。</li>
 *   <li>只删自己写的键：{@link #cleanup()} 删掉经 {@link #pid} / {@link #tid} 分配过的 id 的四类键；不 FLUSHDB。</li>
 *   <li>真 Redis 拨不动 TIME：基线 miniredis 把时钟钉死、用 SetTime / FastForward 推进；这里改成断言落在「调用前后各读一次 Redis TIME」
 *       的区间里，过期则改写记录 / ZSET 的截止或删键来模拟（各测试里逐条注明）。</li>
 * </ul>
 */
final class TeamRedisFixture {

    static final int DB = 12;
    static final int ZONE = 1;

    final RedissonClient redis;
    final TeamRedis teamRedis;
    final TeamStore store;
    /** mutate 用的会话读取（缺省全员 UNKNOWN，同基线测试 sessions 为 nil）。 */
    volatile SessionLoader sessions = SessionLoader.NONE;
    final RuleConfig cfg = RuleConfig.DEFAULT;

    private final long pidBase;
    private final long tidBase;
    private final Set<Long> pids = ConcurrentHashMap.newKeySet();
    private final Set<Long> tids = ConcurrentHashMap.newKeySet();

    static RedissonClient connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        return Redisson.create(config);
    }

    TeamRedisFixture(RedissonClient redis) {
        this(redis, TeamStore.Hooks.NONE);
    }

    TeamRedisFixture(RedissonClient redis, TeamStore.Hooks hooks) {
        this.redis = redis;
        this.teamRedis = new RedissonTeamRedis(redis);
        this.store = new TeamStore(teamRedis, hooks);
        long r = ThreadLocalRandom.current().nextLong(1L << 38) * 16_384;
        this.pidBase = Long.MIN_VALUE + (1L << 52) + r;
        this.tidBase = Long.MIN_VALUE + (1L << 53) + r;
    }

    /** 第 n 号玩家（≥ 2^63），并登记清理。 */
    long pid(int n) {
        long id = pidBase + n;
        pids.add(id);
        return id;
    }

    /** 第 n 号队伍（≥ 2^63），并登记清理。 */
    long tid(int n) {
        long id = tidBase + n;
        tids.add(id);
        return id;
    }

    static String u(long id) {
        return Long.toUnsignedString(id);
    }

    Deadline deadline() {
        return Deadline.after(10_000);
    }

    /** 本夹具分配过的全部 id 的四类键。 */
    List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (long t : tids) {
            keys.add(RedisKeys.teamRecord(t));
            keys.add(RedisKeys.teamInfo(t));
        }
        for (long p : pids) {
            keys.add(RedisKeys.teamPlayer(p));
            keys.add(RedisKeys.teamInvite(p));
        }
        return keys;
    }

    void cleanup() {
        List<String> keys = allKeys();
        for (int i = 0; i < keys.size(); i += 256) {
            redis.getKeys().delete(keys.subList(i, Math.min(keys.size(), i + 256)).toArray(String[]::new));
        }
    }

    // ---------------------------------------------------------------- Redis 时钟

    /** Redis TIME（毫秒）。 */
    long nowMs() {
        Long ms = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                "local t = redis.call('TIME') return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
                RScript.ReturnType.INTEGER);
        return ms;
    }

    /** 忙等到 Redis TIME 严格大于 {@code ms}（真 Redis 拨不动时钟，用真实流逝代替基线的 SetTime）。 */
    void waitRedisAfter(long ms) {
        while (nowMs() <= ms) {
            LockSupport.parkNanos(500_000);
        }
    }

    // ---------------------------------------------------------------- 键读写（测试造数 / 断言用，绕过 Lua，同基线 mr.HSet）

    String hget(String key, String field) {
        return redis.<String, String>getMap(key, StringCodec.INSTANCE).get(field);
    }

    void hset(String key, String field, String value) {
        redis.<String, String>getMap(key, StringCodec.INSTANCE).put(field, value);
    }

    byte[] hgetBytes(String key, String field) {
        return redis.<byte[], byte[]>getMap(key, ByteArrayCodec.INSTANCE).get(field.getBytes(StandardCharsets.US_ASCII));
    }

    byte[] getBytes(String key) {
        return redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).get();
    }

    boolean exists(String key) {
        return redis.getKeys().countExists(key) == 1;
    }

    void del(String... keys) {
        redis.getKeys().delete(keys);
    }

    /** 剩余 TTL（秒，向下取整）；-2 不存在 / -1 无 TTL。 */
    long ttlSeconds(String key) {
        long ms = redis.getKeys().remainTimeToLive(key);
        return ms < 0 ? ms : ms / 1000;
    }

    void pexpire(String key, long ms) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, "return redis.call('PEXPIRE', KEYS[1], ARGV[1])",
                RScript.ReturnType.INTEGER, List.<Object>of(key), Long.toString(ms));
    }

    Double zscore(String key, String member) {
        return redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).getScore(member);
    }

    void zadd(String key, double score, String member) {
        redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).add(score, member);
    }

    List<String> zmembers(String key) {
        return new ArrayList<>(redis.<String>getScoredSortedSet(key, StringCodec.INSTANCE).readAll());
    }

    /** {@code ZCOUNT key (now +inf}：按此刻的 Redis 时钟算的有效邀请数。 */
    long zcountAfter(String key, long nowMs) {
        Long n = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                "return redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[1], '+inf')", RScript.ReturnType.INTEGER,
                List.<Object>of(key), Long.toString(nowMs));
        return n;
    }

    TeamRecord loadRecord(long tid) {
        byte[] raw = hgetBytes(RedisKeys.teamRecord(tid), TeamRedisFields.PB);
        assertThat(raw).as("记录 %s 不存在", u(tid)).isNotNull();
        try {
            return TeamRecord.parseFrom(raw);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    /** 直接改写记录的 pb（ver 不变；测试造数用，模拟「时钟走到截止」这类真 Redis 做不到的状态）。 */
    void writeRecord(long tid, TeamRecord rec) {
        redis.<byte[], byte[]>getMap(RedisKeys.teamRecord(tid), ByteArrayCodec.INSTANCE)
                .put(TeamRedisFields.PB.getBytes(StandardCharsets.US_ASCII), rec.toByteArray());
    }

    String ver(long tid) {
        return hget(RedisKeys.teamRecord(tid), TeamRedisFields.VER);
    }

    // ---------------------------------------------------------------- 状态快照（替代基线 mr.Dump）

    /** 一组键的 DUMP 与 PTTL；用 {@link #assertUnchanged} 比较「没有任何写入」。 */
    record KeyState(Map<String, String> dumps, Map<String, Long> pttls) {
    }

    KeyState state() {
        List<String> keys = allKeys();
        List<Object> reply = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, """
                local out = {}
                for i = 1, #KEYS do
                  out[#out + 1] = redis.call('DUMP', KEYS[i]) or ''
                  out[#out + 1] = redis.call('PTTL', KEYS[i])
                end
                return out
                """, RScript.ReturnType.MULTI, new ArrayList<Object>(keys));
        Map<String, String> dumps = new LinkedHashMap<>();
        Map<String, Long> pttls = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            dumps.put(keys.get(i), HexFormat.of().formatHex((byte[]) reply.get(2 * i)));
            pttls.put(keys.get(i), (Long) reply.get(2 * i + 1));
        }
        return new KeyState(dumps, pttls);
    }

    /** 内容逐字节相同，且没有任何键被续期（PTTL 只会变小；-1 / -2 保持不变）。 */
    void assertUnchanged(KeyState before) {
        KeyState after = state();
        assertThat(after.dumps()).isEqualTo(before.dumps());
        for (Map.Entry<String, Long> e : before.pttls().entrySet()) {
            long b = e.getValue();
            long a = after.pttls().get(e.getKey());
            if (b < 0) {
                assertThat(a).as("TTL of %s", e.getKey()).isEqualTo(b);
            } else {
                assertThat(a).as("TTL of %s 不应被续期", e.getKey()).isLessThanOrEqualTo(b);
            }
        }
    }

    // ---------------------------------------------------------------- 名册操作（照基线 store_test.go:51-75）

    MutateResult mutate(Bind bind, Op op) {
        return store.mutate(bind, op, sessions, cfg, deadline());
    }

    CommitResult mustCommit(Bind bind, Op op) {
        MutateResult res = mutate(bind, op);
        assertThat(res.outcome()).as("code=%d param=%s", res.code(), u(res.param())).isEqualTo(Outcome.COMMITTED);
        return res.commit();
    }

    CommitResult mustCreate(long leader, long tid) {
        return mustCommit(Bind.create(leader, tid), Op.create(leader, tid, ZONE));
    }

    /** 走真实路径入队：申请 → 队长同意。 */
    CommitResult mustJoin(long leader, long tid, long pid) {
        mustCommit(Bind.target(pid, tid), Op.apply(pid, ZONE));
        return mustCommit(Bind.caller(leader, tid), Op.handleApplication(leader, pid, true));
    }
}
