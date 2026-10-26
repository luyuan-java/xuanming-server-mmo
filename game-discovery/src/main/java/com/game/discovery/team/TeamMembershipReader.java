package com.game.discovery.team;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 读一个玩家的组队成员关系（索引 + 投影）。生产构造器从 SQL 一次快照读永久成员关系与队伍原件，
 * 由有界存储执行器承载 JDBC；场景回调须自行投递回逻辑线程，并核对进场实例与成员 epoch。
 * scene 的 TeamId 只是 AOI 排序提示，不是权限或玩法裁决的权威（4.3b）。
 *
 * <p>旧 Redis 构造器保留给兼容装配与投影测试：一段只读 Lua、一次往返原子读出：
 * <ol>
 *   <li>{@code HMGET game:{team}:player:<pid> tid epoch}；</li>
 *   <li>tid 是非 0 的规范十进制时再 {@code GET game:{team}:info:<tid>}。</li>
 * </ol>
 * 投影键由 tid 在脚本里拼出（只拼规范十进制，不让损坏的 tid 拼出别的键），没有出现在 KEYS 里；两把键带同一个 hash tag
 * {@code {team}}（team-spec D2），在 Cluster 上仍落在同一槽；投影缺失按未知处理，不能清掉已缓存的成员提示。
 *
 * <p>旧 Redis 回复的形状判定：两个字段都缺失 = 键缺失（{@link TeamMembership#KEY_MISSING}）；
 * 两个都是合法十进制 = 存在；其余（半个 hash、非十进制、回复形状不对）以及投影解析失败都是数据损坏，返回的 future 以
 * {@link IllegalStateException} 异常完成（不降级成「无队」，调用方按读失败 fail-closed）。
 *
 * <p>两个构造器均不阻塞调用线程。兼容 Redis 路径用 {@code READ_ONLY evalAsync}（NOSCRIPT 自动重新加载），
 * 参数与回复均走 ByteArrayCodec；它不取代生产 SQL 权威。
 */
public final class TeamMembershipReader {

    /**
     * KEYS[1] = 玩家索引；ARGV[1] = 投影键前缀。回复 {@code {tid 或 "", epoch 或 "", 有无投影 0/1, 投影字节或 ""}}
     * （数组里不放 nil，缺失用 "" 占位，同基线 scripts.go:16 的约定）。
     */
    private static final String READ = """
            local idx = redis.call('hmget', KEYS[1], '%s', '%s')
            local tid = idx[1] or ''
            local epoch = idx[2] or ''
            if not string.match(tid, '^[1-9][0-9]*$') then
              return {tid, epoch, 0, ''}
            end
            local info = redis.call('get', ARGV[1] .. tid)
            if not info then
              return {tid, epoch, 0, ''}
            end
            return {tid, epoch, 1, info}
            """.formatted(TeamRedisFields.TID, TeamRedisFields.EPOCH);

    /** 无符号 64 位的十进制最多 20 位。 */
    private static final int MAX_DECIMAL_DIGITS = 20;

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    /** 投影键的前缀（{@code game:{team}:info:}）：脚本里接上 tid 拼出投影键。 */
    private final String infoKeyPrefix;
    private final byte[] infoKeyPrefixBytes;
    private final com.game.discovery.sql.AuthorityJdbc authority;
    private final java.util.concurrent.Executor executor;

    /** 生产成员关系只从SQL当前快照读取；有界存储线程承载JDBC，场景逻辑线程不阻塞。 */
    public TeamMembershipReader(javax.sql.DataSource source,java.util.concurrent.Executor executor) {
        this.redis=null;this.redisKeys=null;this.infoKeyPrefix=null;this.infoKeyPrefixBytes=null;
        this.authority=new com.game.discovery.sql.AuthorityJdbc(source);this.executor=Objects.requireNonNull(executor);
    }

    public TeamMembershipReader(RedissonClient redis, RedisKeys keys) {
        this.redis = redis;
        this.redisKeys = Objects.requireNonNull(keys, "keys");
        this.infoKeyPrefix = infoKeyPrefix(keys);
        this.infoKeyPrefixBytes = infoKeyPrefix.getBytes(StandardCharsets.UTF_8);
        this.authority=null;this.executor=null;
    }

    /**
     * 读 {@code playerId} 此刻的成员关系。存储故障 / 超时 / 数据损坏时异常完成（从不同步抛出）。
     */
    public CompletableFuture<TeamMembership> readAsync(long playerId) {
        if(authority!=null){
            try{return CompletableFuture.supplyAsync(()->{
                try{return authority.snapshot(com.game.common.deadline.Deadline.after(3000),sql->{
                    com.game.common.player.PlayerRouteAuthority.lock(sql.connection(),java.util.List.of(playerId),3);
                    var result=sql.one("SELECT m.team_id,m.epoch,t.tombstone,t.projection_proto FROM team_member_authority m LEFT JOIN team_authority t ON t.team_id=m.team_id WHERE m.player_id=?",row->{
                        long team=row.getLong(1),epoch=row.getLong(2);if(team==0)return new TeamMembership(0,epoch,false,null);
                        byte[] raw=row.getBytes(4);if(row.getObject(3)==null||row.getBoolean(3)||raw==null||raw.length==0)throw new java.sql.SQLException("SQL成员与队伍原件不一致");
                        try{var info=TeamInfo.parseFrom(raw);if(info.getTeamId()!=team||!info.getMembersList().contains(playerId))throw new java.sql.SQLException("SQL队伍投影与永久成员不一致");return new TeamMembership(team,epoch,false,info);}
                        catch(InvalidProtocolBufferException invalid){throw new java.sql.SQLException("SQL队伍投影损坏",invalid);}
                    },com.game.discovery.sql.AuthorityJdbc.u64(playerId));return result==null?TeamMembership.KEY_MISSING:result;
                });}catch(java.sql.SQLException failure){throw new java.util.concurrent.CompletionException(failure);}
            },executor);}catch(RuntimeException rejected){return CompletableFuture.failedFuture(rejected);}
        }
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_ONLY, READ,
                            RScript.ReturnType.MULTI, List.of(redisKeys.teamPlayer(playerId)), infoKeyPrefixBytes)
                    .toCompletableFuture()
                    .thenApply(reply -> parse(playerId, reply));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static TeamMembership parse(long playerId, List<Object> reply) {
        if (reply == null || reply.size() != 4 || !(reply.get(0) instanceof byte[] tidRaw)
                || !(reply.get(1) instanceof byte[] epochRaw) || !(reply.get(2) instanceof Long hasInfo)
                || !(reply.get(3) instanceof byte[] infoRaw)) {
            throw corrupt(playerId, "回复形状不对: " + reply);
        }
        if (tidRaw.length == 0 && epochRaw.length == 0) {
            return TeamMembership.KEY_MISSING;
        }
        if (!canonicalDecimal(tidRaw) || !canonicalDecimal(epochRaw)) {
            throw corrupt(playerId, "索引不是规范十进制 tid=" + ascii(tidRaw) + " epoch=" + ascii(epochRaw));
        }
        long teamId = Long.parseUnsignedLong(ascii(tidRaw));
        long epoch = Long.parseUnsignedLong(ascii(epochRaw));
        if (teamId == 0) {
            return new TeamMembership(0, epoch, false, null);
        }
        if (hasInfo == 0) {
            return new TeamMembership(teamId, epoch, false, null);
        }
        try {
            return new TeamMembership(teamId, epoch, false, TeamInfo.parseFrom(infoRaw));
        } catch (InvalidProtocolBufferException e) {
            throw corrupt(playerId, "投影无法解码 team=" + Long.toUnsignedString(teamId));
        }
    }

    /** 规范的无符号十进制：只认 ASCII 数字、不带符号、除 "0" 外不带前导零、不溢出 uint64（写者一律 %.0f / toUnsignedString）。 */
    private static boolean canonicalDecimal(byte[] raw) {
        if (raw.length == 0 || raw.length > MAX_DECIMAL_DIGITS || raw.length > 1 && raw[0] == '0') {
            return false;
        }
        for (byte b : raw) {
            if (b < '0' || b > '9') {
                return false;
            }
        }
        // 20 位时可能超过 18446744073709551615：按字符串比（同位数时字典序即数值序）
        return raw.length < MAX_DECIMAL_DIGITS || ascii(raw).compareTo(Long.toUnsignedString(-1L)) <= 0;
    }

    private static String ascii(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private static IllegalStateException corrupt(long playerId, String detail) {
        return new IllegalStateException("组队索引 / 投影损坏 player=" + Long.toUnsignedString(playerId) + " " + detail);
    }

    /**
     * 由 {@code RedisKeys#teamInfo} 推出前缀，并核对投影键与索引键同一个 hash tag（脚本里拼的键必须与 KEYS[1] 同槽）。纯函数，包内可见供测试；
     * 格式不对抛 {@link IllegalStateException}（构造时即失败）。
     */
    static String infoKeyPrefix(RedisKeys keys) {
        String probe = keys.teamInfo(0);
        String prefix = probe.substring(0, probe.length() - 1);
        String tag = hashTag(prefix);
        if (!keys.teamInfo(1234567890123L).equals(prefix + "1234567890123") || tag == null
                || !tag.equals(hashTag(keys.teamPlayer(1)))) {
            throw new IllegalStateException("组队投影键的格式不是 <前缀><tid>，或与玩家索引不在同一 hash tag: " + probe);
        }
        return prefix;
    }

    /** Redis Cluster 的 hash tag：第一个 '{' 与其后第一个 '}' 之间的非空串；没有为 null。 */
    private static String hashTag(String key) {
        int open = key.indexOf('{');
        int close = open < 0 ? -1 : key.indexOf('}', open + 1);
        return close > open + 1 ? key.substring(open + 1, close) : null;
    }
}
