package com.game.discovery.team;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.TeamInfo;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 读一个玩家的组队成员关系（索引 + 投影）。读者是 xm-scene 的场景跟随（team-spec §6.10，D7），将来跨 zone 传送的「在队即拒」也用它。
 *
 * <p>一段只读 Lua、一次往返原子读出（基线 C++ scene 是两跳：先 {@code HMGET team:player:<pid> tid epoch}，再 {@code GET team:<tid>}，
 * cpp/libs/services/scene/player/system/player_team.cpp:149-195、:230-348）：
 * <ol>
 *   <li>{@code HMGET xm:{team}:player:<pid> tid epoch}；</li>
 *   <li>tid 是非 0 的规范十进制时再 {@code GET xm:{team}:info:<tid>}。</li>
 * </ol>
 * 投影键由 tid 在脚本里拼出（只拼规范十进制，不让损坏的 tid 拼出别的键），没有出现在 KEYS 里；两把键带同一个 hash tag
 * {@code {team}}（team-spec D2），将来上 Cluster 仍落在同一槽。Java 不缓存 TeamId，所以不需要基线的 {@code EXISTS rec}：
 * 「投影缺失」与「无队」对跟随来说一样，都是不跟随。
 *
 * <p>形状判定同基线 {@code ParseTeamIndexReply}（player_team.h:79-108）：两个字段都缺失 = 键缺失（{@link TeamMembership#KEY_MISSING}）；
 * 两个都是合法十进制 = 存在；其余（半个 hash、非十进制、回复形状不对）以及投影解析失败都是数据损坏，返回的 future 以
 * {@link IllegalStateException} 异常完成（不降级成「无队」，调用方按读失败 fail-closed）。
 *
 * <p>全部异步（Redisson async，不阻塞调用线程，可在场景逻辑线程上调用；结果在 Redisson 的线程上完成，调用方自己投递回所属线程）。
 * 只读脚本以 {@code READ_ONLY} 执行，单条 {@code evalAsync}（遇到 NOSCRIPT 自动重新加载）；参数与回复都走 ByteArrayCodec。
 */
public final class TeamMembershipReader {

    /** 投影键的前缀（{@code xm:{team}:info:}）：脚本里接上 tid 拼出投影键。 */
    static final String INFO_KEY_PREFIX = infoKeyPrefix();

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

    private static final byte[] INFO_KEY_PREFIX_BYTES = INFO_KEY_PREFIX.getBytes(StandardCharsets.UTF_8);
    /** 无符号 64 位的十进制最多 20 位。 */
    private static final int MAX_DECIMAL_DIGITS = 20;

    private final RedissonClient redis;

    public TeamMembershipReader(RedissonClient redis) {
        this.redis = redis;
    }

    /**
     * 读 {@code playerId} 此刻的成员关系。Redis 故障 / 超时 / 数据损坏时异常完成（从不同步抛出）。
     */
    public CompletableFuture<TeamMembership> readAsync(long playerId) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_ONLY, READ,
                            RScript.ReturnType.MULTI, List.of(RedisKeys.teamPlayer(playerId)), INFO_KEY_PREFIX_BYTES)
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

    /** 由 {@code RedisKeys.teamInfo} 推出前缀，并核对投影键与索引键同一个 hash tag（脚本里拼的键必须与 KEYS[1] 同槽）。 */
    private static String infoKeyPrefix() {
        String probe = RedisKeys.teamInfo(0);
        String prefix = probe.substring(0, probe.length() - 1);
        String tag = hashTag(prefix);
        if (!RedisKeys.teamInfo(1234567890123L).equals(prefix + "1234567890123") || tag == null
                || !tag.equals(hashTag(RedisKeys.teamPlayer(1)))) {
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
