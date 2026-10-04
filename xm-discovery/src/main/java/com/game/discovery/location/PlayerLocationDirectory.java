package com.game.discovery.location;

import com.game.discovery.RedisKeys;
import com.game.discovery.proto.PlayerLocation;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家位置（Redis {@code xm:location:{player_id}}，Hash：{@code e} = owner_epoch、{@code q} = 写序号、{@code s} = 状态、
 * {@code v} = {@link PlayerLocation}，带 TTL）。对应 mmorpg scene_manager 的 PlayerLocation 与 player_locator 的 30 s 断线租约：断线重连、顶号把玩家送回
 * 原场景实例（基线在 zone 内只用 location 定 zone、落默认主世界，Java 按它注释里的设计意图回原实例，见 PARITY）；
 * 干净登出（LeaveGame）与租约到期后没有记录，进游戏按首登落默认主世界。
 *
 * <p>写者只有持有该玩家数据归属的 scene 节点。状态：{@code o} 在线（进场 / 换场景，TTL {@link #ONLINE_TTL}）、
 * {@code l} 重连租约（断线，TTL {@link #RECONNECT_LEASE}）、{@code x} 已登出（LeaveGame 留的不带位置的墓碑，TTL {@link #ONLINE_TTL}，
 * 读者当没有）。被接管 / 失去归属 / 停服不动记录：新持有者进场时覆盖，否则按 TTL 消失。
 *
 * <p><b>写序号</b>：Redisson 的命令可能乱序执行——走连接池里的不同连接，或脚本第一次执行遇到 NOSCRIPT 后重新加载再发
 * （实测进场、换场景、断线三条连发时会被重排）。所以每次写都带上写者在本次进场（epoch）内单调递增的序号与<b>完整</b>的此刻状态，
 * 按 (epoch, 序号) 只收比已存的更新的写：乱序晚到的旧写一律丢弃，最后生效的总是最新的那一次。在线续期不递增序号，
 * 只在记录仍是这次进场这一序号的在线记录时延长 TTL；键丢了（Redis 抖动）就补回。
 *
 * <p>写方法全部异步（Redisson async，不阻塞调用线程，可在场景逻辑线程上调用）；{@link #find} 阻塞，调用方不得在 I/O / 逻辑线程上用；
 * {@link #statusesAsync}（组队的会话四态用）异步。
 * 单条脚本调用而不用 RBatch：理由同 {@code PlayerPresenceDirectory}（批里的 EVALSHA 遇到 NOSCRIPT 不会重新加载）。
 */
public final class PlayerLocationDirectory {

    /** 在线时记录的存活时长；持有者每 {@link #REFRESH_INTERVAL} 续期一次。也是登出墓碑的存活时长（远长于命令最坏的乱序时间）。 */
    public static final Duration ONLINE_TTL = Duration.ofSeconds(60);
    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(20);
    /** 断线重连租约（同 mmorpg player_locator 的 30 s 断线租约）。 */
    public static final Duration RECONNECT_LEASE = Duration.ofSeconds(30);

    /** 一条在线续期：此刻的位置与写者当前的序号（不递增）。 */
    public record Refresh(PlayerLocation location, long seq) {
    }

    private static final Logger log = LoggerFactory.getLogger(PlayerLocationDirectory.class);

    /** 十进制无前导零的非负整数比较（epoch / 序号是 uint64，Lua 的数是 double，不能 tonumber 比）。 */
    private static final String GT = """
            local function gt(a, b)
              if #a ~= #b then return #a > #b end
              return a > b
            end
            """;

    /** 按 (epoch, 序号) 只收更新的写（1），否则不动（0）。ARGV: epoch, seq, state, value（墓碑为空）, ttl_ms。 */
    private static final String WRITE_IF_NEWER = GT + """
            local e = redis.call('hget', KEYS[1], 'e')
            if e then
              if gt(e, ARGV[1]) then
                return 0
              end
              if e == ARGV[1] then
                local q = redis.call('hget', KEYS[1], 'q')
                if q and not gt(ARGV[2], q) then
                  return 0
                end
              end
            end
            redis.call('del', KEYS[1])
            if ARGV[4] == '' then
              redis.call('hset', KEYS[1], 'e', ARGV[1], 'q', ARGV[2], 's', ARGV[3])
            else
              redis.call('hset', KEYS[1], 'e', ARGV[1], 'q', ARGV[2], 's', ARGV[3], 'v', ARGV[4])
            end
            redis.call('pexpire', KEYS[1], ARGV[5])
            return 1
            """;

    /**
     * 在线续期（ARGV: epoch, seq, value, ttl_ms）：还是这次进场这一序号的在线记录就延长 TTL（1）；键不在就补回成在线（2）；
     * 其余（别人的、更新的写、租约、墓碑）不动（0）。
     */
    private static final String REFRESH_OR_RESTORE = """
            local e = redis.call('hget', KEYS[1], 'e')
            if not e then
              redis.call('del', KEYS[1])
              redis.call('hset', KEYS[1], 'e', ARGV[1], 'q', ARGV[2], 's', 'o', 'v', ARGV[3])
              redis.call('pexpire', KEYS[1], ARGV[4])
              return 2
            end
            if e == ARGV[1] and redis.call('hget', KEYS[1], 'q') == ARGV[2] and redis.call('hget', KEYS[1], 's') == 'o' then
              redis.call('pexpire', KEYS[1], ARGV[4])
              return 1
            end
            return 0
            """;

    /** 只返回在线或租约中的位置（墓碑与缺值返回 nil）。 */
    private static final String READ_VALUE = """
            local s = redis.call('hget', KEYS[1], 's')
            if s == 'o' or s == 'l' then
              return redis.call('hget', KEYS[1], 'v')
            end
            return false
            """;

    /** 状态字段名（值 {@code o} / {@code l} / {@code x}，与上面各段脚本里的字面量一致）。 */
    private static final String STATE_FIELD = "s";

    private static final byte[] ONLINE = ascii("o");
    private static final byte[] LEASED = ascii("l");
    private static final byte[] LOGGED_OUT = ascii("x");
    private static final byte[] NO_VALUE = new byte[0];

    private final RedissonClient redis;

    public PlayerLocationDirectory(RedissonClient redis) {
        this.redis = redis;
    }

    // ------------------------------------------------------------------ 写（scene，异步）

    /** 进场 / 换场景：写成在线。@return 是否生效（false = 已有更新的写） */
    public CompletionStage<Boolean> putAsync(PlayerLocation location, long seq) {
        return write(location.getPlayerId(), location.getOwnerEpoch(), seq, ONLINE, location.toByteArray(), ONLINE_TTL);
    }

    /** 断线：写成重连租约（带此刻的位置）。@return 是否生效 */
    public CompletionStage<Boolean> leaseAsync(PlayerLocation location, long seq) {
        return write(location.getPlayerId(), location.getOwnerEpoch(), seq, LEASED, location.toByteArray(),
                RECONNECT_LEASE);
    }

    /** 主动离开：写成登出墓碑（读者当没有）。@return 是否生效 */
    public CompletionStage<Boolean> removeAsync(long playerId, long ownerEpoch, long seq) {
        return write(playerId, ownerEpoch, seq, LOGGED_OUT, NO_VALUE, ONLINE_TTL);
    }

    /** 一批在线续期；返回补回的条数（键丢过）。任一条失败则整体异常完成。 */
    public CompletionStage<Integer> refreshAsync(Collection<Refresh> refreshes) {
        if (refreshes.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        byte[] ttl = millis(ONLINE_TTL);
        List<CompletableFuture<Long>> calls = new ArrayList<>(refreshes.size());
        for (Refresh refresh : refreshes) {
            PlayerLocation location = refresh.location();
            calls.add(eval(REFRESH_OR_RESTORE, location.getPlayerId(), number(location.getOwnerEpoch()),
                    number(refresh.seq()), location.toByteArray(), ttl).toCompletableFuture());
        }
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                .thenApply(done -> (int) calls.stream().map(CompletableFuture::join).filter(r -> r != null && r == 2).count());
    }

    private CompletionStage<Boolean> write(long playerId, long ownerEpoch, long seq, byte[] state, byte[] value,
                                           Duration ttl) {
        return eval(WRITE_IF_NEWER, playerId, number(ownerEpoch), number(seq), state, value, millis(ttl))
                .thenApply(r -> r != null && r == 1);
    }

    private CompletionStage<Long> eval(String script, long playerId, byte[]... args) {
        return redis.getScript(ByteArrayCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.INTEGER, List.of(RedisKeys.playerLocation(playerId)), (Object[]) args);
    }

    private static byte[] number(long unsigned) {
        return ascii(Long.toUnsignedString(unsigned));
    }

    private static byte[] millis(Duration duration) {
        return ascii(Long.toString(duration.toMillis()));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ 读状态（任何服务，异步）

    /** 位置记录的状态（只读 {@code s} 字段，{@link #statusesAsync}）。 */
    public enum LocationStatus {
        /** {@code s=o}：持有归属的 scene 认为玩家在线（进场 / 换场景写的记录）。 */
        ONLINE,
        /** {@code s=l}：断线后的重连租约（{@link #RECONNECT_LEASE} 内）。 */
        RECONNECT_LEASE,
        /** {@code s=x}：主动离开（LeaveGame）留下的登出墓碑。 */
        LOGGED_OUT,
        /** 没有记录（从未写过、租约 / 墓碑已过期）。 */
        MISSING,
        /** 读失败，或记录损坏（状态值不认识、键类型不对）：状态未知。 */
        ERROR
    }

    /**
     * 批量读位置记录的状态（每人一条 {@code HGET <key> s}，异步发出、不阻塞调用线程，可在逻辑线程 / EventLoop 上调用）。
     * 每个玩家的键各在各的槽（没有共同的 hash tag），所以逐键读，不用一段 Lua 读多把键；也不与在线目录合成一段脚本
     * （team-spec §6.6：两类键将来上 Cluster 会跨槽）。某人的读失败只把<b>这个人</b>记为 {@link LocationStatus#ERROR}，
     * 返回的 future 正常完成、从不异常完成（组队按人 fail-closed）。
     *
     * @return 键覆盖全部入参（去重，保持入参顺序），不可修改
     */
    public CompletableFuture<Map<Long, LocationStatus>> statusesAsync(Collection<Long> playerIds) {
        List<Long> ids = playerIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<CompletableFuture<LocationStatus>> reads = new ArrayList<>(ids.size());
        for (long playerId : ids) {
            CompletableFuture<String> state;
            try {
                state = redis.<String, String>getMap(RedisKeys.playerLocation(playerId), StringCodec.INSTANCE)
                        .getAsync(STATE_FIELD).toCompletableFuture();
            } catch (RuntimeException e) {
                state = CompletableFuture.failedFuture(e);
            }
            reads.add(state.handle((s, error) -> {
                if (error != null) {
                    log.warn("读位置记录状态失败，按状态未知处理 player={}: {}", Long.toUnsignedString(playerId),
                            error.toString());
                    return LocationStatus.ERROR;
                }
                LocationStatus status = statusOf(s);
                if (status == LocationStatus.ERROR) {
                    log.warn("位置记录状态值不认识，按状态未知处理 player={} s={}", Long.toUnsignedString(playerId), s);
                }
                return status;
            }));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(done -> {
            Map<Long, LocationStatus> statuses = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                statuses.put(ids.get(i), reads.get(i).join());
            }
            return Collections.unmodifiableMap(statuses);
        });
    }

    private static LocationStatus statusOf(String state) {
        if (state == null) {
            return LocationStatus.MISSING;
        }
        return switch (state) {
            case "o" -> LocationStatus.ONLINE;
            case "l" -> LocationStatus.RECONNECT_LEASE;
            case "x" -> LocationStatus.LOGGED_OUT;
            default -> LocationStatus.ERROR;
        };
    }

    // ------------------------------------------------------------------ 读（login，阻塞）

    /** 玩家的位置记录（在线或重连租约内）；没有、已登出、损坏或与键不符为空（损坏记告警）。Redis 出错抛出（调用方决定怎么退化）。 */
    public Optional<PlayerLocation> find(long playerId) {
        Object raw = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, READ_VALUE,
                RScript.ReturnType.VALUE, List.of(RedisKeys.playerLocation(playerId)));
        if (!(raw instanceof byte[] value)) {
            return Optional.empty();
        }
        try {
            PlayerLocation location = PlayerLocation.parseFrom(value);
            if (location.getPlayerId() != playerId) {
                log.warn("位置记录与键不符，按没有处理 key_player={} value_player={}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(location.getPlayerId()));
                return Optional.empty();
            }
            return Optional.of(location);
        } catch (InvalidProtocolBufferException e) {
            log.warn("位置记录损坏，按没有处理 player={}", Long.toUnsignedString(playerId));
            return Optional.empty();
        }
    }
}
