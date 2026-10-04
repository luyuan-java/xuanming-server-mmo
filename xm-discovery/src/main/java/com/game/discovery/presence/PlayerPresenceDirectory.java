package com.game.discovery.presence;

import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.RedisKeys;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家在线目录（Redis {@code xm:presence:{player_id}}，值为 {@link PlayerPresence}，带 TTL）。
 *
 * <p>写者只有 gate（会话的所有者）：scene 确认进场后 {@link #putAsync}，离场 / 断线 {@link #removeAsync}（值仍是自己写的才删），
 * 在线期间每 TTL/3 {@link #refreshAsync}（值仍是自己的才续期；键没了就补回）。读者是任何需要「玩家在不在线、在哪个 gate」的服务。
 *
 * <p>与 mmorpg {@code player_locator} 的差异（有意）：条目带 TTL、写者活着就续，gate 进程死掉后最多一个 TTL 自然消失，
 * 不会像基线无 TTL 的会话键那样在断线租约链丢一步后永远停在 ONLINE。条目只表示「此刻在游戏里」，不承载断线租约 / 顶号判定
 * （那些在 Java 版由归属协议负责）。
 *
 * <p>写方法全部异步（Redisson async，不阻塞调用线程，可在 Netty EventLoop 上调用）；读方法阻塞，调用方不得在 I/O 线程上用。
 */
public final class PlayerPresenceDirectory {

    /** 条目存活时长；写者每 1/3 续期一次。 */
    public static final Duration TTL = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(PlayerPresenceDirectory.class);

    /** 值仍是自己写的那一份才删（下线时不误删同一玩家在别处的新会话）。 */
    private static final String REMOVE_IF_SAME = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """;

    /**
     * 续期：值仍是自己的就延长 TTL（返回 1）；键已不在（TTL 期间 Redis 抖动丢了）就补回（返回 2）；
     * 已被别的会话覆盖（玩家在别处登录）不动（返回 0）。
     */
    private static final String REFRESH_OR_RESTORE = """
            local v = redis.call('get', KEYS[1])
            if v == ARGV[1] then
              return redis.call('pexpire', KEYS[1], ARGV[2])
            elseif not v then
              redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 2
            end
            return 0
            """;

    private final RedissonClient redis;

    public PlayerPresenceDirectory(RedissonClient redis) {
        this.redis = redis;
    }

    // ------------------------------------------------------------------ 写（gate）

    /** 写入 / 覆盖（同一玩家在别处的旧条目被新会话取代）。 */
    public CompletionStage<Void> putAsync(PlayerPresence presence) {
        return redis.getBucket(RedisKeys.presence(presence.getPlayerId()), ByteArrayCodec.INSTANCE)
                .setAsync(presence.toByteArray(), TTL);
    }

    /** @return 是否真的删了（值已被别的会话覆盖、或已过期时为 false） */
    public CompletionStage<Boolean> removeAsync(PlayerPresence presence) {
        return script().<Long>evalAsync(RScript.Mode.READ_WRITE, REMOVE_IF_SAME, RScript.ReturnType.INTEGER,
                List.of(RedisKeys.presence(presence.getPlayerId())), presence.toByteArray())
                .thenApply(deleted -> deleted != null && deleted > 0);
    }

    /**
     * 一批续期：每条一个异步脚本调用（Redisson 自动流水线化）。返回补回的条数（键丢过）；任一条失败则整体异常完成。
     *
     * <p>不用 RBatch：Redisson 的批里脚本一律发 EVALSHA、遇到 NOSCRIPT 不会重新加载（运维执行过 SCRIPT FLUSH 后整批永远失败，
     * 在线目录会全部过期）；单条 {@code evalAsync} 遇到 NOSCRIPT 会自动 SCRIPT LOAD 后重试。
     */
    public CompletionStage<Integer> refreshAsync(Collection<PlayerPresence> presences) {
        if (presences.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        byte[] ttl = Long.toString(TTL.toMillis()).getBytes(StandardCharsets.US_ASCII);
        List<java.util.concurrent.CompletableFuture<Long>> calls = new ArrayList<>(presences.size());
        for (PlayerPresence presence : presences) {
            calls.add(script().<Long>evalAsync(RScript.Mode.READ_WRITE, REFRESH_OR_RESTORE, RScript.ReturnType.INTEGER,
                    List.of(RedisKeys.presence(presence.getPlayerId())), presence.toByteArray(), ttl).toCompletableFuture());
        }
        return java.util.concurrent.CompletableFuture.allOf(calls.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(done -> (int) calls.stream().map(java.util.concurrent.CompletableFuture::join)
                        .filter(r -> r != null && r == 2).count());
    }

    private RScript script() {
        return redis.getScript(ByteArrayCodec.INSTANCE);
    }

    // ------------------------------------------------------------------ 读（任何服务，阻塞）

    /** 玩家此刻是否在游戏里、在哪个 gate；不在线为空。条目损坏按不在线处理（记告警）。 */
    public Optional<PlayerPresence> find(long playerId) {
        byte[] raw = redis.<byte[]>getBucket(RedisKeys.presence(playerId), ByteArrayCodec.INSTANCE).get();
        return decode(playerId, raw);
    }

    /** 批量查（一次往返）；只含在线的玩家。 */
    public Map<Long, PlayerPresence> findAll(Collection<Long> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(playerIds);
        String[] keys = ids.stream().map(RedisKeys::presence).toArray(String[]::new);
        Map<String, byte[]> raw = redis.getBuckets(ByteArrayCodec.INSTANCE).get(keys);
        Map<Long, PlayerPresence> online = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            decode(ids.get(i), raw.get(keys[i])).ifPresent(p -> online.put(p.getPlayerId(), p));
        }
        return online;
    }

    /** {@link #find} 的异步版（不阻塞调用线程）。 */
    public CompletionStage<Optional<PlayerPresence>> findAsync(long playerId) {
        return redis.<byte[]>getBucket(RedisKeys.presence(playerId), ByteArrayCodec.INSTANCE).getAsync()
                .thenApply(raw -> decode(playerId, raw));
    }

    /**
     * 严格单查（不阻塞调用线程）：不在线为空；条目损坏或与键不符时<b>异常完成</b>（{@link IllegalStateException}），
     * 不降级成「不在线」——调用方据此区分「对方离线」与「在线目录坏了」。
     */
    public CompletionStage<Optional<PlayerPresence>> findStrictAsync(long playerId) {
        return redis.<byte[]>getBucket(RedisKeys.presence(playerId), ByteArrayCodec.INSTANCE).getAsync().thenApply(raw -> {
            if (raw == null) {
                return Optional.<PlayerPresence>empty();
            }
            PlayerPresence presence;
            try {
                presence = PlayerPresence.parseFrom(raw);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("在线目录条目损坏 player=" + Long.toUnsignedString(playerId), e);
            }
            if (presence.getPlayerId() != playerId) {
                throw new IllegalStateException("在线目录条目与键不符 key_player=" + Long.toUnsignedString(playerId)
                        + " value_player=" + Long.toUnsignedString(presence.getPlayerId()));
            }
            return Optional.of(presence);
        });
    }

    /** {@link #findAll} 的异步版（一次往返，不阻塞调用线程）。 */
    public CompletionStage<Map<Long, PlayerPresence>> findAllAsync(Collection<Long> playerIds) {
        if (playerIds.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(Map.of());
        }
        List<Long> ids = new ArrayList<>(playerIds);
        String[] keys = ids.stream().map(RedisKeys::presence).toArray(String[]::new);
        return redis.getBuckets(ByteArrayCodec.INSTANCE).<byte[]>getAsync(keys).thenApply(raw -> {
            Map<Long, PlayerPresence> online = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                decode(ids.get(i), raw.get(keys[i])).ifPresent(p -> online.put(p.getPlayerId(), p));
            }
            return online;
        });
    }

    /**
     * 严格批量读的结果：{@code online} 只含在线且条目完好的玩家；{@code offline} 是没有条目的个数；
     * {@code errors} 是读失败（该批 MGET 出错）、条目损坏或与键不符的个数——调用方据此判「在线状态未知」，不能当成离线。
     */
    public record StrictLookup(Map<Long, PlayerPresence> online, int offline, int errors) {
    }

    /**
     * 严格批量读（同 mmorpg friend BatchOnlineStatus）：去重、剔除 0，按 {@code batchSize} 分批 MGET（不阻塞调用线程）；
     * 某批出错只把这一批记为错误、其余批照常读。与 {@link #findAllAsync} 不同：损坏 / 身份不符的条目计入 {@code errors}，
     * 不降级成离线——「好友在线状态未知」不能当离线（客户端据此禁邀）。
     */
    public CompletionStage<StrictLookup> findAllStrictAsync(Collection<Long> playerIds, int batchSize) {
        List<Long> ids = playerIds.stream().filter(id -> id != 0).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(new StrictLookup(Map.of(), 0, 0));
        }
        int size = batchSize > 0 ? batchSize : 256;
        List<java.util.concurrent.CompletableFuture<StrictLookup>> batches = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += size) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + size));
            String[] keys = batch.stream().map(RedisKeys::presence).toArray(String[]::new);
            batches.add(redis.getBuckets(ByteArrayCodec.INSTANCE).<byte[]>getAsync(keys).toCompletableFuture()
                    .handle((raw, error) -> error != null ? new StrictLookup(Map.of(), 0, batch.size())
                            : strict(batch, keys, raw)));
        }
        return java.util.concurrent.CompletableFuture.allOf(batches.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(done -> {
                    Map<Long, PlayerPresence> online = new HashMap<>();
                    int offline = 0;
                    int errors = 0;
                    for (java.util.concurrent.CompletableFuture<StrictLookup> batch : batches) {
                        StrictLookup part = batch.join();
                        online.putAll(part.online());
                        offline += part.offline();
                        errors += part.errors();
                    }
                    return new StrictLookup(online, offline, errors);
                });
    }

    private static StrictLookup strict(List<Long> ids, String[] keys, Map<String, byte[]> raw) {
        Map<Long, PlayerPresence> online = new HashMap<>();
        int offline = 0;
        int errors = 0;
        for (int i = 0; i < ids.size(); i++) {
            byte[] value = raw.get(keys[i]);
            if (value == null) {
                offline++;
                continue;
            }
            try {
                PlayerPresence presence = PlayerPresence.parseFrom(value);
                if (presence.getPlayerId() != ids.get(i)) {
                    errors++;
                } else {
                    online.put(ids.get(i), presence);
                }
            } catch (InvalidProtocolBufferException e) {
                errors++;
            }
        }
        return new StrictLookup(online, offline, errors);
    }

    private static Optional<PlayerPresence> decode(long playerId, byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            PlayerPresence presence = PlayerPresence.parseFrom(raw);
            if (presence.getPlayerId() != playerId) {
                log.warn("在线目录条目与键不符，按不在线处理 key_player={} value_player={}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(presence.getPlayerId()));
                return Optional.empty();
            }
            return Optional.of(presence);
        } catch (InvalidProtocolBufferException e) {
            log.warn("在线目录条目损坏，按不在线处理 player={}", Long.toUnsignedString(playerId));
            return Optional.empty();
        }
    }
}
