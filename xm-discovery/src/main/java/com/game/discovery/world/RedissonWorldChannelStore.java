package com.game.discovery.world;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorldChannelStore} 的 Redisson 实现（scene-channels-spec §4.2、§4.4、§4.6.1、§4.11）。
 *
 * <ul>
 *   <li>每个动作一段 Lua、单条 {@code eval}（遇 NOSCRIPT 自动重载），不用 RBatch / MULTI（批里的脚本遇 NOSCRIPT 不重载，
 *       architecture.md §4.3）；键全部经 KEYS 传入，一个 zone 的键共用 hash tag {@code {z:<zone>}}，Cluster 下同槽。</li>
 *   <li>编解码一律 ByteArrayCodec：频道记录是 pb 任意字节（含 0x00、非法 UTF-8），StringCodec 会把它们弄坏。</li>
 *   <li>时间源一律 Redis {@code TIME}（脚本里读，同 team / gateway；Redis 7 起脚本按效果复制，读 TIME 后再写没有问题）。</li>
 *   <li>Redisson 在响应超时后会重发同一段 EVAL（缺省 retry-attempts）：写入靠版本号 CAS 收敛（第一次其实已成功时重发回 −2，
 *       调用方本拍作废、下拍重读即看到已写入的计划）；竞选对「已是本令牌」续期回 1，续期 / 放锁比较令牌；预占对同一玩家幂等
 *       （成员是 player_id，只刷新到期时间）。</li>
 * </ul>
 */
public final class RedissonWorldChannelStore implements WorldChannelStore {

    private static final Logger log = LoggerFactory.getLogger(RedissonWorldChannelStore.class);

    /** Redis TIME → 毫秒（脚本片段；t[1] 秒、t[2] 微秒）。 */
    private static final String NOW_MS = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            """;

    /** 拉取（只读）。KEYS: ch, ver。回 {@code {ver, ch 字段, ch 值, …}}。 */
    static final String PLAN = """
            local ch = redis.call('HGETALL', KEYS[1])
            local out = {redis.call('GET', KEYS[2]) or '0'}
            for i = 1, #ch do out[#out + 1] = ch[i] end
            return out
            """;

    /**
     * 快照（只读，§4.6.1）。KEYS: ch, desired, cooldown, ver。
     * 回 {@code {ver, now_ms, #ch, #desired, #cooldown, ch…, desired…, cooldown…}}（平铺，免得依赖嵌套数组的解码）。
     */
    static final String SNAPSHOT = NOW_MS + """
            local ch = redis.call('HGETALL', KEYS[1])
            local desired = redis.call('HGETALL', KEYS[2])
            local cooldown = redis.call('HGETALL', KEYS[3])
            local out = {redis.call('GET', KEYS[4]) or '0', now, #ch, #desired, #cooldown}
            for i = 1, #ch do out[#out + 1] = ch[i] end
            for i = 1, #desired do out[#out + 1] = desired[i] end
            for i = 1, #cooldown do out[#out + 1] = cooldown[i] end
            return out
            """;

    /**
     * 围栏写入（§4.4）。KEYS: leader, ch, desired, cooldown, ver；ARGV[1] = 令牌，ARGV[2] = 期望 ver，ARGV[3] = 写入后的 ver
     * （都是十进制，后者由 {@link WorldPlanBatch#writtenVersion()} 定，严格大于前者），之后若干 (op, field, value)。
     * op：S = HSET ch，D = HDEL ch，Q = HSET desired，N = HSETNX desired，X = HDEL desired，C = HSET cooldown，Y = HDEL cooldown。
     * 回 1 已写入；−1 不是领导者；−2 版本冲突。两项校验与 op 合法性检查都在第一次写之前完成：要么整批执行，要么一条不写
     * （Lua 出错不会回滚已执行的写，所以先整批校验）。版本号只按十进制串比较与原样 SET（与 Java 的 Long.toString 逐字相同），
     * 不经 Lua 的 double，任意大小都精确。
     */
    static final String WRITE = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return -1 end
            if (redis.call('GET', KEYS[5]) or '0') ~= ARGV[2] then return -2 end
            local ops = {S = {2, 'HSET'}, D = {2, 'HDEL'}, Q = {3, 'HSET'}, N = {3, 'HSETNX'}, X = {3, 'HDEL'},
                         C = {4, 'HSET'}, Y = {4, 'HDEL'}}
            local n = #ARGV
            if (n - 3) % 3 ~= 0 then return redis.error_reply('ERR xm world plan: op triples malformed') end
            for i = 4, n, 3 do
              if ops[ARGV[i]] == nil then return redis.error_reply('ERR xm world plan: unknown op ' .. ARGV[i]) end
            end
            for i = 4, n, 3 do
              local op = ops[ARGV[i]]
              if op[2] == 'HDEL' then
                redis.call('HDEL', KEYS[op[1]], ARGV[i + 1])
              else
                redis.call(op[2], KEYS[op[1]], ARGV[i + 1], ARGV[i + 2])
              end
            end
            redis.call('SET', KEYS[5], ARGV[3])
            return 1
            """;

    /** 竞选。KEYS[1] = 锁；ARGV[1] = 令牌，ARGV[2] = TTL 毫秒（写法同帮会排行锁 RedissonGuildRankRedis.TRY_LOCK）。 */
    static final String LEADER_TRY = """
            local holder = redis.call('GET', KEYS[1])
            if holder == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if holder then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """;

    /** 续期。KEYS[1] = 锁；ARGV[1] = 令牌，ARGV[2] = TTL 毫秒。 */
    static final String LEADER_RENEW = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """;

    /** 放锁。KEYS[1] = 锁；ARGV[1] = 令牌。 */
    static final String LEADER_RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /**
     * 选频道并软预占（§4.11）。KEYS[i] = 候选 i 的预占键；ARGV[1] = TTL 毫秒，ARGV[2] = player_id，ARGV[2 + i] = 候选 i 的目录人数。
     * 负载 = 目录人数 + 未到期预占数 − 本玩家自己那条（同一玩家重复分配不改变选择）；严格小于才换（并列取靠前的）。
     * 选定后撤掉本玩家在其它候选上的预占，再给选中者记一条并 PEXPIRE。回 {@code {best（从 1 起）, load}}。
     */
    static final String RESERVE = NOW_MS + """
            local ttl = tonumber(ARGV[1])
            local best, load
            for i = 1, #KEYS do
              redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
              local l = tonumber(ARGV[2 + i]) + redis.call('ZCARD', KEYS[i])
              if redis.call('ZSCORE', KEYS[i], ARGV[2]) then l = l - 1 end
              if best == nil or l < load then best, load = i, l end
            end
            for i = 1, #KEYS do
              if i ~= best then redis.call('ZREM', KEYS[i], ARGV[2]) end
            end
            redis.call('ZADD', KEYS[best], now + ttl, ARGV[2])
            redis.call('PEXPIRE', KEYS[best], ARGV[1])
            return {best, load}
            """;

    /** 给指定场景记一条预占。KEYS[1] = 预占键；ARGV[1] = TTL 毫秒，ARGV[2] = player_id。 */
    static final String RESERVE_ONE = NOW_MS + """
            local ttl = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            redis.call('ZADD', KEYS[1], now + ttl, ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """;

    /**
     * 各键未到期的预占条数（只读）。KEYS[i] = 预占键。回与 KEYS 同序的整数数组。
     * 下界用 TIME 的两段拼成的十进制串（不经 {@code %d}：Lua 5.1 的 {@code %d} 走 C long，在 32 位 long 的平台上会截断毫秒数）。
     */
    static final String COUNT = """
            local t = redis.call('TIME')
            local out = {}
            local from = '(' .. t[1] .. string.format('%03d', math.floor(tonumber(t[2]) / 1000))
            for i = 1, #KEYS do out[i] = redis.call('ZCOUNT', KEYS[i], from, '+inf') end
            return out
            """;

    private final RedissonClient redis;

    public RedissonWorldChannelStore(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public long planVersion(int zoneId) {
        String key = RedisKeys.worldPlanVersion(zoneId);
        byte[] raw = redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).get();
        return WorldPlanCodec.parseVersion(key, raw);
    }

    @Override
    public WorldPlan readPlan(int zoneId) {
        Object raw = script().eval(RScript.Mode.READ_ONLY, PLAN, RScript.ReturnType.MULTI,
                keys(RedisKeys.worldChannels(zoneId), RedisKeys.worldPlanVersion(zoneId)));
        return WorldPlanCodec.parsePlan(zoneId, raw);
    }

    @Override
    public WorldPlanSnapshot snapshot(int zoneId) {
        Object raw = script().eval(RScript.Mode.READ_ONLY, SNAPSHOT, RScript.ReturnType.MULTI,
                keys(RedisKeys.worldChannels(zoneId), RedisKeys.worldDesired(zoneId), RedisKeys.worldCooldown(zoneId),
                        RedisKeys.worldPlanVersion(zoneId)));
        return WorldPlanCodec.parseSnapshot(zoneId, raw);
    }

    @Override
    public WorldPlanWriteResult write(int zoneId, String leaderToken, WorldPlanBatch batch) {
        requireToken(leaderToken);
        Object raw = script().eval(RScript.Mode.READ_WRITE, WRITE, RScript.ReturnType.INTEGER,
                keys(RedisKeys.worldLeader(zoneId), RedisKeys.worldChannels(zoneId), RedisKeys.worldDesired(zoneId),
                        RedisKeys.worldCooldown(zoneId), RedisKeys.worldPlanVersion(zoneId)),
                WorldPlanCodec.writeArgs(leaderToken, batch).toArray());
        WorldPlanWriteResult result = WorldPlanCodec.writeResult(raw, batch);
        if (log.isDebugEnabled()) {
            log.debug("频道计划写入 zone={} {} → {}", Integer.toUnsignedString(zoneId), batch, result);
        }
        return result;
    }

    @Override
    public boolean tryAcquireLeader(int zoneId, String token, Duration ttl) {
        requireToken(token);
        return flag(script().eval(RScript.Mode.READ_WRITE, LEADER_TRY, RScript.ReturnType.INTEGER,
                keys(RedisKeys.worldLeader(zoneId)), WorldPlanCodec.ascii(token), millis(ttl)));
    }

    @Override
    public boolean renewLeader(int zoneId, String token, Duration ttl) {
        requireToken(token);
        return flag(script().eval(RScript.Mode.READ_WRITE, LEADER_RENEW, RScript.ReturnType.INTEGER,
                keys(RedisKeys.worldLeader(zoneId)), WorldPlanCodec.ascii(token), millis(ttl)));
    }

    @Override
    public boolean releaseLeader(int zoneId, String token) {
        requireToken(token);
        return flag(script().eval(RScript.Mode.READ_WRITE, LEADER_RELEASE, RScript.ReturnType.INTEGER,
                keys(RedisKeys.worldLeader(zoneId)), WorldPlanCodec.ascii(token)));
    }

    @Override
    public ReservationPick reserve(int zoneId, List<ReservationCandidate> candidates, long playerId, Duration ttl) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("候选不能为空");
        }
        List<Object> keys = new ArrayList<>(candidates.size());
        List<byte[]> args = new ArrayList<>(candidates.size() + 2);
        args.add(millis(ttl));
        args.add(WorldPlanCodec.ascii(Long.toUnsignedString(playerId)));
        for (ReservationCandidate candidate : candidates) {
            keys.add(RedisKeys.worldReservations(zoneId, candidate.sceneId()));
            args.add(WorldPlanCodec.ascii(Integer.toUnsignedString(candidate.directoryPlayers())));
        }
        Object raw = script().eval(RScript.Mode.READ_WRITE, RESERVE, RScript.ReturnType.MULTI, keys, args.toArray());
        return WorldPlanCodec.parsePick(raw, candidates.size());
    }

    @Override
    public void reserveScene(int zoneId, long sceneId, long playerId, Duration ttl) {
        script().eval(RScript.Mode.READ_WRITE, RESERVE_ONE, RScript.ReturnType.INTEGER,
                keys(RedisKeys.worldReservations(zoneId, sceneId)), millis(ttl),
                WorldPlanCodec.ascii(Long.toUnsignedString(playerId)));
    }

    @Override
    public boolean releaseReservation(int zoneId, long sceneId, long playerId) {
        return redis.getScoredSortedSet(RedisKeys.worldReservations(zoneId, sceneId), StringCodec.INSTANCE)
                .remove(Long.toUnsignedString(playerId));
    }

    @Override
    public List<Long> countReservations(int zoneId, List<Long> sceneIds) {
        if (sceneIds.isEmpty()) {
            return List.of();
        }
        List<Object> keys = new ArrayList<>(sceneIds.size());
        for (long sceneId : sceneIds) {
            keys.add(RedisKeys.worldReservations(zoneId, sceneId));
        }
        Object raw = script().eval(RScript.Mode.READ_ONLY, COUNT, RScript.ReturnType.MULTI, keys);
        return WorldPlanCodec.parseCounts(raw, sceneIds.size());
    }

    @Override
    public void registerZone(int zoneId) {
        redis.getSet(RedisKeys.worldZones(), StringCodec.INSTANCE).add(Integer.toUnsignedString(zoneId));
    }

    @Override
    public List<Integer> zones() {
        List<Integer> out = new ArrayList<>();
        for (Object member : redis.getSet(RedisKeys.worldZones(), StringCodec.INSTANCE).readAll()) {
            String s = String.valueOf(member);
            try {
                out.add(Integer.parseUnsignedInt(s));
            } catch (NumberFormatException e) {
                log.warn("zone 集合里有非法成员，跳过 key={} member={}", RedisKeys.worldZones(), s);
            }
        }
        out.sort(Integer::compareUnsigned);
        return out;
    }

    private RScript script() {
        return redis.getScript(ByteArrayCodec.INSTANCE);
    }

    private static List<Object> keys(String... keys) {
        return List.of((Object[]) keys);
    }

    private static byte[] millis(Duration ttl) {
        long ms = ttl.toMillis();
        if (ms <= 0) {
            throw new IllegalArgumentException("TTL 必须 ≥ 1 ms: " + ttl);
        }
        return Long.toString(ms).getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("领导者令牌不能为空");
        }
    }

    private static boolean flag(Object raw) {
        return raw instanceof Long n && n == 1L;
    }
}
