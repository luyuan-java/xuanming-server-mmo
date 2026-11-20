package com.game.login.dispatch;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「同一个键同一时刻只允许一个在途操作」的闸门（对应 mmorpg 的 {@code account_lock:*} / {@code player_locker:*}，
 * 只试一次不排队，拿不到由调用方回 2005 kLoginInProgress）。
 *
 * <p>无参构造只做本进程互斥（供单测）；{@link #distributed} 在本进程互斥之上叠加 Redis {@code SET NX PX} + Lua 令牌核对释放，
 * 供多实例 {@code game-login} 共享互斥。Redis 瞬时不可达时降级为本进程互斥，不阻断下游 SQL / Redis 权威护栏。线程安全。
 *
 * @param <K> 键类型（账号、player_id）
 */
public final class InFlightKeys<K> {

    private static final Logger log = LoggerFactory.getLogger(InFlightKeys.class);
    private static final String LOCAL_TOKEN = "<local>";

    /** KEYS[1] 锁键；ARGV[1] 持有者令牌、ARGV[2] TTL 毫秒。返回 1 占用成功，0 已被其他实例占用。 */
    static final String ACQUIRE_SCRIPT = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              return 1
            end
            return 0
            """;

    /** KEYS[1] 锁键；ARGV[1] 持有者令牌。仅当当前值等于本持有者令牌时删除，避免误删过期后其他实例重新持有的锁。 */
    static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final ConcurrentHashMap<K, String> heldTokens = new ConcurrentHashMap<>();
    private final RedissonClient redis;
    private final Function<K, String> keyMapper;
    private final String ttlMs;

    /** 纯进程内互斥（单测与单实例默认）。 */
    public InFlightKeys() {
        this.redis = null;
        this.keyMapper = null;
        this.ttlMs = null;
    }

    private InFlightKeys(RedissonClient redis, Function<K, String> keyMapper, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("在途闸门 TTL 必须为正: " + ttl);
        }
        this.redis = redis;
        this.keyMapper = Objects.requireNonNull(keyMapper, "keyMapper");
        this.ttlMs = String.valueOf(ttl.toMillis());
    }

    /**
     * 跨实例互斥：经 Redis {@code SET NX PX} 占用，{@link #release} 经 Lua 按令牌删除。
     * {@code redis} 为 null 时退化为纯进程内互斥。
     */
    public static <K> InFlightKeys<K> distributed(RedissonClient redis, Function<K, String> keyMapper, Duration ttl) {
        if (redis == null) {
            return new InFlightKeys<>();
        }
        return new InFlightKeys<>(redis, keyMapper, ttl);
    }

    /** 占用成功返回 true；调用方必须在操作结束（含异步链结束）后 {@link #release}。 */
    public boolean tryAcquire(K key) {
        if (redis == null) {
            return heldTokens.putIfAbsent(key, LOCAL_TOKEN) == null;
        }
        String token = UUID.randomUUID().toString();
        if (heldTokens.putIfAbsent(key, token) != null) {
            return false;
        }
        String redisKey = keyMapper.apply(key);
        try {
            RScript script = redis.getScript(StringCodec.INSTANCE);
            if (script == null) {
                heldTokens.replace(key, token, LOCAL_TOKEN);
                return true;
            }
            Long acquired = script.eval(RScript.Mode.READ_WRITE, ACQUIRE_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(redisKey), token, ttlMs);
            if (acquired == null) {
                heldTokens.replace(key, token, LOCAL_TOKEN);
                return true;
            }
            if (acquired == 1L) {
                return true;
            }
            heldTokens.remove(key, token);
            return false;
        } catch (RuntimeException e) {
            log.warn("跨实例在途闸门 Redis 加锁失败，本次降级为本进程互斥 key={}: {}", redisKey, e.toString());
            heldTokens.replace(key, token, LOCAL_TOKEN);
            return true;
        }
    }

    public void release(K key) {
        String token = heldTokens.remove(key);
        if (token == null || redis == null || LOCAL_TOKEN.equals(token)) {
            return;
        }
        String redisKey = keyMapper.apply(key);
        try {
            RScript script = redis.getScript(StringCodec.INSTANCE);
            if (script != null) {
                script.eval(RScript.Mode.READ_WRITE, RELEASE_SCRIPT, RScript.ReturnType.INTEGER,
                        List.of(redisKey), token);
            }
        } catch (RuntimeException e) {
            log.warn("跨实例在途闸门 Redis 释放失败（按 TTL {}ms 自愈） key={}: {}", ttlMs, redisKey, e.toString());
        }
    }

    public boolean isHeld(K key) {
        return heldTokens.containsKey(key);
    }
}
