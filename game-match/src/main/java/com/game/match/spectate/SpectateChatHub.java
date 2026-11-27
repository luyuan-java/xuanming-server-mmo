package com.game.match.spectate;

import com.game.discovery.RedisKeys;
import com.game.proto.match.BattleSpectateChatMessage;
import com.game.proto.match.BattleSpectateChatRole;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 局外人观战公开交流室存储与限流中心（与 Go 版 {@code spectatechat/hub.go} 同构）：
 * <ul>
 *   <li>生产环境注入 {@link RedissonClient} 与 {@link RedisKeys} 时，走单槽（hash tag {@code {<battle_id>}}）原子 Lua，支持多 match 副本水平扩展；</li>
 *   <li>单测或未配置 Redis 时自动使用线程安全的进程内环形缓冲兜底，语义与 Redis 路径完全一致。</li>
 * </ul>
 */
public final class SpectateChatHub {

    private static final Logger log = LoggerFactory.getLogger(SpectateChatHub.class);

    public static final int RING_LIMIT = 60;
    public static final int DEFAULT_PULL_LIMIT = 30;
    public static final int MAX_PULL_LIMIT = 60;
    public static final int ROOM_TTL_SECONDS = 3600;
    private static final long ROOM_IDLE_TTL_MS = ROOM_TTL_SECONDS * 1000L;
    private static final long EVICT_INTERVAL_MS = 60_000L;
    private static final int COOLDOWN_PRUNE_THRESHOLD = 1024;

    public record Config(
            int maxContentBytes,
            long participantCooldownMs,
            long spectatorCooldownMs,
            int roomRateLimitPerSec) {
        public static final Config DEFAULT = new Config(180, 2_000L, 4_000L, 8);

        public Config normalized() {
            return new Config(
                    maxContentBytes > 0 ? maxContentBytes : DEFAULT.maxContentBytes,
                    participantCooldownMs > 0 ? participantCooldownMs : DEFAULT.participantCooldownMs,
                    spectatorCooldownMs > 0 ? spectatorCooldownMs : DEFAULT.spectatorCooldownMs,
                    roomRateLimitPerSec > 0 ? roomRateLimitPerSec : DEFAULT.roomRateLimitPerSec);
        }
    }

    public enum SendStatus {
        OK,
        ENDED,
        COOLDOWN,
        ROOM_BUSY
    }

    public record SendResult(
            SendStatus status,
            BattleSpectateChatMessage message,
            long cooldownUntilMs,
            long serverTimeMs) {
    }

    public record PullResult(
            List<BattleSpectateChatMessage> messages,
            long latestSeq,
            long cooldownUntilMs,
            boolean battleEnded,
            boolean roomExists,
            long serverTimeMs) {
    }

    // KEYS: [1]=seq, [2]=msgs, [3]=cd, [4]=rate, [5]=closed
    // ARGV: [1]=nowMs, [2]=playerId, [3]=cooldownMs, [4]=rateLimit, [5]=rateBucket, [6]=ringLimit, [7]=ttlSec, [8]=payloadBytes
    // return: {code, seqOrZero, cooldownUntilMs}  code: 0=OK, 1=ENDED, 2=COOLDOWN, 3=ROOM_BUSY
    private static final String LUA_SEND = """
            local nowMs = tonumber(ARGV[1])
            local playerKey = ARGV[2]
            local cooldownMs = tonumber(ARGV[3])
            local rateLimit = tonumber(ARGV[4])
            local bucket = ARGV[5]
            local ringLimit = tonumber(ARGV[6])
            local ttlSec = tonumber(ARGV[7])
            local payload = ARGV[8]

            if redis.call('EXISTS', KEYS[5]) == 1 then
                return {1, 0, 0}
            end
            local prevUntil = tonumber(redis.call('HGET', KEYS[3], playerKey) or '0')
            if prevUntil > nowMs then
                return {2, 0, prevUntil}
            end
            local prevBucket = redis.call('HGET', KEYS[4], 'b')
            local bucketCount = tonumber(redis.call('HGET', KEYS[4], 'c') or '0')
            if prevBucket ~= bucket then
                bucketCount = 0
            end
            if bucketCount >= rateLimit then
                return {3, 0, 0}
            end
            bucketCount = bucketCount + 1
            redis.call('HSET', KEYS[4], 'b', bucket, 'c', tostring(bucketCount))
            redis.call('EXPIRE', KEYS[4], ttlSec)

            local seq = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ttlSec)
            local nextUntil = nowMs + cooldownMs
            redis.call('HSET', KEYS[3], playerKey, tostring(nextUntil))
            redis.call('EXPIRE', KEYS[3], ttlSec)
            redis.call('RPUSH', KEYS[2], tostring(seq) .. ':' .. payload)
            redis.call('LTRIM', KEYS[2], -ringLimit, -1)
            redis.call('EXPIRE', KEYS[2], ttlSec)
            return {0, seq, nextUntil}
            """;

    // KEYS: [1]=seq, [2]=msgs, [3]=cd, [4]=closed
    // ARGV: [1]=nowMs, [2]=playerId
    // return: {latestSeq, cooldownUntilMs, closed(0/1), msgs...}
    private static final String LUA_PULL = """
            local nowMs = tonumber(ARGV[1])
            local playerKey = ARGV[2]
            local latestSeq = tonumber(redis.call('GET', KEYS[1]) or '0')
            local closed = redis.call('EXISTS', KEYS[4])
            local cd = tonumber(redis.call('HGET', KEYS[3], playerKey) or '0')
            if cd <= nowMs then
                cd = 0
            end
            local msgs = redis.call('LRANGE', KEYS[2], 0, -1)
            local out = {tostring(latestSeq), tostring(cd), tostring(closed)}
            for i = 1, #msgs do
                out[#out + 1] = msgs[i]
            end
            return out
            """;

    // KEYS: [1]=closed, [2]=seq, [3]=msgs, [4]=cd, [5]=rate
    // ARGV: [1]=ttlSec
    private static final String LUA_MARK_ENDED = """
            local ttlSec = tonumber(ARGV[1])
            redis.call('SET', KEYS[1], '1', 'EX', ttlSec)
            if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ttlSec) end
            if redis.call('EXISTS', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], ttlSec) end
            if redis.call('EXISTS', KEYS[4]) == 1 then redis.call('EXPIRE', KEYS[4], ttlSec) end
            if redis.call('EXISTS', KEYS[5]) == 1 then redis.call('EXPIRE', KEYS[5], ttlSec) end
            return 1
            """;

    private static final class MemoryRoom {
        long nextSeq;
        boolean closed;
        long lastActiveMs;
        long rateBucketSec;
        int rateCount;
        final ArrayDeque<BattleSpectateChatMessage> recent = new ArrayDeque<>(RING_LIMIT + 1);
        final Map<Long, Long> cooldowns = new HashMap<>();
    }

    private final Config config;
    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    private final Map<Long, MemoryRoom> memRooms = new HashMap<>();
    private long lastEvictMs;

    public SpectateChatHub(Config config) {
        this(config, null, null);
    }

    public SpectateChatHub(Config config, RedissonClient redis, RedisKeys redisKeys) {
        this.config = (config == null ? Config.DEFAULT : config).normalized();
        if (redis != null && redisKeys == null) {
            throw new IllegalArgumentException("注入 RedissonClient 时必须同时注入 RedisKeys");
        }
        this.redis = redis;
        this.redisKeys = redisKeys;
    }

    public Config config() {
        return config;
    }

    public SendResult send(long battleId, long senderPlayerId, String senderName,
                           BattleSpectateChatRole role, String content, long nowMs) {
        long cooldownMs = role == BattleSpectateChatRole.BATTLE_SPECTATE_CHAT_ROLE_PARTICIPANT
                ? config.participantCooldownMs()
                : config.spectatorCooldownMs();
        if (redis != null) {
            return sendRedis(battleId, senderPlayerId, senderName, role, content, cooldownMs, nowMs);
        }
        return sendMemory(battleId, senderPlayerId, senderName, role, content, cooldownMs, nowMs);
    }

    public PullResult pull(long battleId, long viewerPlayerId, long afterSeq, int limit, long nowMs) {
        int clampedLimit = clampLimit(limit);
        if (redis != null) {
            return pullRedis(battleId, viewerPlayerId, afterSeq, clampedLimit, nowMs);
        }
        return pullMemory(battleId, viewerPlayerId, afterSeq, clampedLimit, nowMs);
    }

    public void markEnded(long battleId, long nowMs) {
        if (battleId == 0L) {
            return;
        }
        if (redis != null) {
            try {
                List<Object> keys = List.of(
                        redisKeys.matchSpectateChatClosed(battleId),
                        redisKeys.matchSpectateChatSeq(battleId),
                        redisKeys.matchSpectateChatMessages(battleId),
                        redisKeys.matchSpectateChatCooldown(battleId),
                        redisKeys.matchSpectateChatRate(battleId));
                redis.getScript(ByteArrayCodec.INSTANCE).eval(
                        RScript.Mode.READ_WRITE,
                        LUA_MARK_ENDED,
                        RScript.ReturnType.INTEGER,
                        keys,
                        ascii(Integer.toString(ROOM_TTL_SECONDS)));
            } catch (RuntimeException e) {
                log.warn("[spectate-chat] 标记战斗结束失败 battle_id={}: {}",
                        Long.toUnsignedString(battleId), e.getMessage());
            }
            return;
        }
        synchronized (this) {
            MemoryRoom room = memRooms.computeIfAbsent(battleId, k -> {
                MemoryRoom r = new MemoryRoom();
                r.lastActiveMs = nowMs;
                return r;
            });
            room.closed = true;
            room.lastActiveMs = nowMs;
        }
    }

    private SendResult sendRedis(long battleId, long senderPlayerId, String senderName,
                                 BattleSpectateChatRole role, String content, long cooldownMs, long nowMs) {
        BattleSpectateChatMessage template = BattleSpectateChatMessage.newBuilder()
                .setSenderPlayerId(senderPlayerId)
                .setSenderName(senderName)
                .setRole(role)
                .setContent(content)
                .setCreatedMs(nowMs)
                .build();
        List<Object> keys = List.of(
                redisKeys.matchSpectateChatSeq(battleId),
                redisKeys.matchSpectateChatMessages(battleId),
                redisKeys.matchSpectateChatCooldown(battleId),
                redisKeys.matchSpectateChatRate(battleId),
                redisKeys.matchSpectateChatClosed(battleId));
        @SuppressWarnings("unchecked")
        List<Object> raw = redis.getScript(ByteArrayCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA_SEND,
                RScript.ReturnType.MULTI,
                keys,
                ascii(Long.toString(nowMs)),
                ascii(Long.toUnsignedString(senderPlayerId)),
                ascii(Long.toString(cooldownMs)),
                ascii(Integer.toString(config.roomRateLimitPerSec())),
                ascii(Long.toString(nowMs / 1000L)),
                ascii(Integer.toString(RING_LIMIT)),
                ascii(Integer.toString(ROOM_TTL_SECONDS)),
                template.toByteArray());
        long code = asLong(raw, 0);
        long seq = asLong(raw, 1);
        long cdUntil = asLong(raw, 2);
        return switch ((int) code) {
            case 1 -> new SendResult(SendStatus.ENDED, null, 0L, nowMs);
            case 2 -> new SendResult(SendStatus.COOLDOWN, null, cdUntil, nowMs);
            case 3 -> new SendResult(SendStatus.ROOM_BUSY, null, 0L, nowMs);
            default -> new SendResult(SendStatus.OK, template.toBuilder().setSeq(seq).build(), cdUntil, nowMs);
        };
    }

    private PullResult pullRedis(long battleId, long viewerPlayerId, long afterSeq, int limit, long nowMs) {
        List<Object> keys = List.of(
                redisKeys.matchSpectateChatSeq(battleId),
                redisKeys.matchSpectateChatMessages(battleId),
                redisKeys.matchSpectateChatCooldown(battleId),
                redisKeys.matchSpectateChatClosed(battleId));
        @SuppressWarnings("unchecked")
        List<Object> raw = redis.getScript(ByteArrayCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA_PULL,
                RScript.ReturnType.MULTI,
                keys,
                ascii(Long.toString(nowMs)),
                ascii(Long.toUnsignedString(viewerPlayerId)));
        long latestSeq = asLong(raw, 0);
        long cdUntil = asLong(raw, 1);
        boolean closed = asLong(raw, 2) != 0L;
        List<BattleSpectateChatMessage> decoded = new ArrayList<>();
        if (raw != null && raw.size() > 3) {
            for (int i = 3; i < raw.size(); i++) {
                BattleSpectateChatMessage msg = decodeFramed(raw.get(i));
                if (msg != null && Long.compareUnsigned(msg.getSeq(), afterSeq) > 0) {
                    decoded.add(msg);
                }
            }
        }
        if (decoded.size() > limit) {
            decoded = List.copyOf(decoded.subList(decoded.size() - limit, decoded.size()));
        } else {
            decoded = List.copyOf(decoded);
        }
        boolean exists = latestSeq != 0L || closed || !decoded.isEmpty();
        return new PullResult(decoded, latestSeq, cdUntil, closed, exists, nowMs);
    }

    private synchronized SendResult sendMemory(long battleId, long senderPlayerId, String senderName,
                                               BattleSpectateChatRole role, String content,
                                               long cooldownMs, long nowMs) {
        evictIdleLocked(nowMs);
        MemoryRoom room = memRooms.computeIfAbsent(battleId, k -> {
            MemoryRoom r = new MemoryRoom();
            r.lastActiveMs = nowMs;
            return r;
        });
        room.lastActiveMs = nowMs;
        if (room.closed) {
            return new SendResult(SendStatus.ENDED, null, 0L, nowMs);
        }
        long prevUntil = room.cooldowns.getOrDefault(senderPlayerId, 0L);
        if (prevUntil > nowMs) {
            return new SendResult(SendStatus.COOLDOWN, null, prevUntil, nowMs);
        }
        long bucketSec = nowMs / 1000L;
        if (room.rateBucketSec != bucketSec) {
            room.rateBucketSec = bucketSec;
            room.rateCount = 0;
        }
        if (room.rateCount >= config.roomRateLimitPerSec()) {
            return new SendResult(SendStatus.ROOM_BUSY, null, 0L, nowMs);
        }
        room.rateCount++;
        room.nextSeq++;
        BattleSpectateChatMessage msg = BattleSpectateChatMessage.newBuilder()
                .setSeq(room.nextSeq)
                .setSenderPlayerId(senderPlayerId)
                .setSenderName(senderName)
                .setRole(role)
                .setContent(content)
                .setCreatedMs(nowMs)
                .build();
        room.recent.addLast(msg);
        while (room.recent.size() > RING_LIMIT) {
            room.recent.removeFirst();
        }
        long nextUntil = nowMs + cooldownMs;
        room.cooldowns.put(senderPlayerId, nextUntil);
        if (room.cooldowns.size() > COOLDOWN_PRUNE_THRESHOLD) {
            room.cooldowns.entrySet().removeIf(e -> e.getValue() <= nowMs);
        }
        return new SendResult(SendStatus.OK, msg, nextUntil, nowMs);
    }

    private synchronized PullResult pullMemory(long battleId, long viewerPlayerId,
                                               long afterSeq, int limit, long nowMs) {
        MemoryRoom room = memRooms.get(battleId);
        if (room == null) {
            return new PullResult(List.of(), 0L, 0L, false, false, nowMs);
        }
        long cd = room.cooldowns.getOrDefault(viewerPlayerId, 0L);
        long effectiveCd = cd > nowMs ? cd : 0L;
        List<BattleSpectateChatMessage> out = new ArrayList<>();
        for (BattleSpectateChatMessage m : room.recent) {
            if (Long.compareUnsigned(m.getSeq(), afterSeq) > 0) {
                out.add(m);
            }
        }
        if (out.size() > limit) {
            out = List.copyOf(out.subList(out.size() - limit, out.size()));
        } else {
            out = List.copyOf(out);
        }
        return new PullResult(out, room.nextSeq, effectiveCd, room.closed, true, nowMs);
    }

    private void evictIdleLocked(long nowMs) {
        if (memRooms.isEmpty()) {
            return;
        }
        if (memRooms.size() < 64 && lastEvictMs != 0L && nowMs > lastEvictMs && nowMs - lastEvictMs < EVICT_INTERVAL_MS) {
            return;
        }
        lastEvictMs = nowMs;
        Iterator<Map.Entry<Long, MemoryRoom>> it = memRooms.entrySet().iterator();
        while (it.hasNext()) {
            MemoryRoom r = it.next().getValue();
            if (nowMs > r.lastActiveMs && nowMs - r.lastActiveMs >= ROOM_IDLE_TTL_MS) {
                it.remove();
            }
        }
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PULL_LIMIT;
        }
        return Math.min(limit, MAX_PULL_LIMIT);
    }

    private static byte[] ascii(String s) {
        return Objects.requireNonNull(s).getBytes(StandardCharsets.US_ASCII);
    }

    private static long asLong(List<Object> list, int idx) {
        if (list == null || idx >= list.size() || list.get(idx) == null) {
            return 0L;
        }
        Object v = list.get(idx);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof byte[] b) {
            try {
                return Long.parseLong(new String(b, StandardCharsets.US_ASCII));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static BattleSpectateChatMessage decodeFramed(Object raw) {
        if (!(raw instanceof byte[] bytes)) {
            return null;
        }
        int sep = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == ':') {
                sep = i;
                break;
            }
        }
        if (sep <= 0 || sep >= bytes.length) {
            return null;
        }
        long seq;
        try {
            seq = Long.parseUnsignedLong(new String(bytes, 0, sep, StandardCharsets.US_ASCII));
        } catch (NumberFormatException ignored) {
            return null;
        }
        try {
            BattleSpectateChatMessage parsed = BattleSpectateChatMessage.parser()
                    .parseFrom(bytes, sep + 1, bytes.length - sep - 1);
            return parsed.toBuilder().setSeq(seq).build();
        } catch (InvalidProtocolBufferException ignored) {
            return null;
        }
    }
}
