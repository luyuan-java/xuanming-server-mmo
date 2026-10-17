package com.game.login.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginTokens} 的 Redis 实现（键见 {@link RedisKeys#loginAccessToken} 等）。线程安全、阻塞。
 * <ul>
 *   <li>签发：两个令牌键 + 账号集合（先按分数清掉已过期的成员，再加入新成员、续集合的 TTL）在一个事务里写；
 *       之后把集合封顶到 {@link #MAX_REFRESH_PER_ACCOUNT}（尽力而为，失败不影响这次签发）。</li>
 *   <li>轮换：先读旧 refresh，再用一段 Lua 脚本原子地「作废旧 refresh（DEL 返回 0 即已被别人轮换走）、从账号集合摘掉它、
 *       写新的一对与账号集合、写轮换记录」。轮换记录（{@link RedisKeys#loginRefreshRotated}）TTL = 重放宽限窗口：
 *       窗口内同一旧 refresh 再来（回包丢失后的重试、并发的两路），只要新 refresh 还没用过、也没被淘汰，就原样返回同一对，
 *       不再签发；窗口外或新 refresh 已不在一律无效。窗口为 0 时不写记录，严格一次性（基线 {@code token.go} Refresh）。
 *       脚本发出之前看一次调用方给的截止，已过就不发（见 {@link LoginTokens#refresh(String, BooleanSupplier)}）。
 *       脚本沿用客户端的 Redisson 重发（不改成 {@code retryAttempts(0)}）：首发已执行时重发的 DEL 返回 0、什么都不写，转到重放判定拿回同一对，
 *       「已轮换但回包超时」靠它在本请求内恢复；代价是首发没到 Redis 时，重发可能在截止之后才轮换（已知残留）。</li>
 *   <li>格式不对的令牌（不是 43 字符 base64url）不碰 Redis，直接无效：客户端给的任意文本不会拼进键、也不会经异常进日志。</li>
 *   <li>存储出错抛 {@link TokenStoreException}（不带原异常：Redisson 的异常消息会带命令参数，即令牌本身）。</li>
 * </ul>
 */
public final class RedisLoginTokens implements LoginTokens {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginTokens.class);

    /** 32 字节随机数 → base64url 无填充 43 字符。 */
    static final int TOKEN_BYTES = 32;
    /** 每账号活跃 refresh 上限（基线 maxRefreshTokensPerAccount）。 */
    static final int MAX_REFRESH_PER_ACCOUNT = 32;
    /**
     * 重放宽限窗口的上限（基线 MaxRefreshReuseGrace）：窗口越长，偷到旧 refresh 的人能搭车的时间越长；
     * 60s 足够覆盖「客户端超时 + 重连 + 重发」。
     */
    public static final Duration MAX_REFRESH_REUSE_GRACE = Duration.ofSeconds(60);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    /**
     * 轮换：KEYS = 旧 refresh 键、轮换记录键、新 access 键、新 refresh 键、账号集合；
     * ARGV = 令牌数据 JSON、access TTL 毫秒、refresh TTL 毫秒、现在秒、新 refresh 过期秒、新 refresh、旧 refresh、
     * 轮换记录 JSON、宽限窗口毫秒（0 = 不写记录）。旧 refresh 已不在回 0、什么都不写。
     * 签发部分（第 3–7 条命令）与 {@link #issue} 的事务写同一组键、同一套 TTL 与集合维护，改一处要同改另一处。
     */
    private static final String ROTATE_SCRIPT = """
            if redis.call('DEL', KEYS[1]) == 0 then
              return 0
            end
            redis.call('ZREM', KEYS[5], ARGV[7])
            redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', '(' .. ARGV[4])
            redis.call('ZADD', KEYS[5], ARGV[5], ARGV[6])
            redis.call('PEXPIRE', KEYS[5], ARGV[3])
            if tonumber(ARGV[9]) > 0 then
              redis.call('SET', KEYS[2], ARGV[8], 'PX', ARGV[9])
            end
            return 1
            """;

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final long reuseGraceMillis;
    private final SecureRandom random;

    /**
     * @param keys              令牌键的出处（带部署命名空间，装配方注入）
     * @param refreshReuseGrace 重放宽限窗口，[0, {@link #MAX_REFRESH_REUSE_GRACE}]；0 = 严格一次性
     */
    public RedisLoginTokens(RedissonClient redis, RedisKeys keys, Clock clock, Duration accessTtl, Duration refreshTtl,
                            Duration refreshReuseGrace, SecureRandom random) {
        Objects.requireNonNull(refreshReuseGrace, "refreshReuseGrace");
        if (refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(MAX_REFRESH_REUSE_GRACE) > 0) {
            throw new IllegalArgumentException("refresh 重放宽限窗口必须在 [0, " + MAX_REFRESH_REUSE_GRACE + "] 内: "
                    + refreshReuseGrace);
        }
        this.redis = redis;
        this.redisKeys = Objects.requireNonNull(keys, "keys");
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.reuseGraceMillis = refreshReuseGrace.toMillis();
        this.random = random;
    }

    @Override
    public TokenPair issue(String account, String authType, String deviceId) {
        long nowSeconds = clock.instant().getEpochSecond();
        String value = encode(new TokenData(account, authType, deviceId, nowSeconds));
        String access = newToken();
        String refresh = newToken();
        long accessExpire = nowSeconds + accessTtl.toSeconds();
        long refreshExpire = nowSeconds + refreshTtl.toSeconds();

        String setKey = redisKeys.loginAccountRefresh(account);
        store("签发", () -> {
            RBatch batch = redis.createBatch(BatchOptions.defaults()
                    .executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
            batch.getBucket(redisKeys.loginAccessToken(access), StringCodec.INSTANCE).setAsync(value, accessTtl);
            batch.getBucket(redisKeys.loginRefreshToken(refresh), StringCodec.INSTANCE).setAsync(value, refreshTtl);
            // 分数 = 该 refresh 的过期秒：分数已过去的成员，其令牌键早已按 TTL 过期，清成员即可（不让死成员无上界累积）
            batch.<String>getScoredSortedSet(setKey, StringCodec.INSTANCE)
                    .removeRangeByScoreAsync(Double.NEGATIVE_INFINITY, true, nowSeconds, false);
            batch.<String>getScoredSortedSet(setKey, StringCodec.INSTANCE).addAsync(refreshExpire, refresh);
            batch.<String>getScoredSortedSet(setKey, StringCodec.INSTANCE).expireAsync(refreshTtl);
            return batch.execute();
        });
        trim(setKey);
        return new TokenPair(access, refresh, accessExpire, refreshExpire);
    }

    @Override
    public Optional<TokenData> validateAccess(String accessToken) {
        if (!wellFormed(accessToken)) {
            return Optional.empty();
        }
        return decode(store("校验", () -> redis.<String>getBucket(redisKeys.loginAccessToken(accessToken),
                StringCodec.INSTANCE).get()));
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline) {
        Objects.requireNonNull(pastDeadline, "pastDeadline");
        if (!wellFormed(refreshToken)) {
            return Optional.empty();
        }
        String value = store("轮换", () -> redis.<String>getBucket(redisKeys.loginRefreshToken(refreshToken),
                StringCodec.INSTANCE).get());
        if (value == null) {
            // 旧 refresh 不在：从未存在、过期，或刚被轮换走（可能就是本请求丢了回包的那一次）
            return replay(refreshToken);
        }
        Optional<TokenData> data = decode(value);
        if (data.isEmpty()) {
            return Optional.empty();
        }

        TokenData old = data.get();
        long nowSeconds = clock.instant().getEpochSecond();
        String access = newToken();
        String refresh = newToken();
        TokenPair pair = new TokenPair(access, refresh, nowSeconds + accessTtl.toSeconds(),
                nowSeconds + refreshTtl.toSeconds());
        String record = reuseGraceMillis > 0
                ? encode(new RotationRecord(old.account(), access, refresh, pair.accessExpireSeconds(),
                        pair.refreshExpireSeconds(), nowSeconds))
                : "";
        String setKey = redisKeys.loginAccountRefresh(old.account());
        if (pastDeadline.getAsBoolean()) {
            // 提交点（作废 + 签发的原子脚本）之前最后看一次截止；之前的读、造令牌都没有副作用。不带令牌、不带账号：消息可能进日志。
            throw new RotationAbandoned("请求截止已过，没有发出轮换脚本");
        }
        Long rotated = store("轮换", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ROTATE_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(redisKeys.loginRefreshToken(refreshToken), redisKeys.loginRefreshRotated(refreshToken),
                        redisKeys.loginAccessToken(access), redisKeys.loginRefreshToken(refresh), setKey),
                encode(new TokenData(old.account(), old.authType(), old.deviceId(), nowSeconds)),
                String.valueOf(accessTtl.toMillis()), String.valueOf(refreshTtl.toMillis()), String.valueOf(nowSeconds),
                String.valueOf(pair.refreshExpireSeconds()), refresh, refreshToken, record,
                String.valueOf(reuseGraceMillis)));
        if (rotated == null || rotated != 1L) {
            // 读到旧值之后、脚本执行之前，并发的另一路抢先轮换了它：按重放判定，拿那一路换出的同一对
            return replay(refreshToken);
        }
        trim(setKey);
        return Optional.of(pair);
    }

    /**
     * 对已不在的旧 refresh 的判定：有轮换记录（在宽限窗口内）且它换出的新 refresh 仍在，原样返回那一对；否则无效。
     * 新 refresh 一旦用掉或被封顶淘汰就被删除，而随机令牌不会被重新写回，所以「先读记录、再查新 refresh」两次读之间不需要原子：
     * 第二次读到它存在，第一次读的时刻它也一定存在。
     */
    private Optional<TokenPair> replay(String oldToken) {
        String raw = store("重放判定", () -> redis.<String>getBucket(redisKeys.loginRefreshRotated(oldToken),
                StringCodec.INSTANCE).get());
        Optional<RotationRecord> record = decodeRotation(raw);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        RotationRecord rec = record.get();
        boolean successorLive = store("重放判定", () -> redis.getBucket(redisKeys.loginRefreshToken(rec.refreshToken()),
                StringCodec.INSTANCE).isExists());
        if (!successorLive) {
            // 合法客户端用上新 refresh 之后不会再拿旧的来重试：这里不为 0 值得排查（客户端重复发送，或旧 refresh 泄漏）
            log.info("宽限窗口内重放旧 refresh，但新 refresh 已用过或已被淘汰，按无效处理 account={} rotated_at={}",
                    rec.account(), rec.rotatedAt());
            return Optional.empty();
        }
        log.info("宽限窗口内重放旧 refresh，原样返回上次轮换的一对 account={} rotated_at={}", rec.account(), rec.rotatedAt());
        return Optional.of(new TokenPair(rec.accessToken(), rec.refreshToken(), rec.accessExpire(), rec.refreshExpire()));
    }

    /** 封顶账号的活跃 refresh：超出就淘汰分数最小（最早过期）的若干个，连同它们的令牌键。尽力而为。 */
    private void trim(String setKey) {
        try {
            RScoredSortedSet<String> set = redis.getScoredSortedSet(setKey, StringCodec.INSTANCE);
            int size = set.size();
            if (size <= MAX_REFRESH_PER_ACCOUNT) {
                return;
            }
            Collection<String> oldest = set.valueRange(0, size - MAX_REFRESH_PER_ACCOUNT - 1);
            if (oldest.isEmpty()) {
                return;
            }
            List<String> keys = new ArrayList<>(oldest.size());
            for (String token : oldest) {
                keys.add(redisKeys.loginRefreshToken(token));
            }
            RBatch batch = redis.createBatch(BatchOptions.defaults()
                    .executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
            batch.getKeys().deleteAsync(keys.toArray(String[]::new));
            batch.<String>getScoredSortedSet(setKey, StringCodec.INSTANCE).removeAllAsync(oldest);
            batch.execute();
        } catch (RuntimeException e) {
            // 不记原异常：它的消息里有被淘汰的令牌
            log.error("封顶账号 refresh 集合失败（不影响这次签发） key={}: {}", setKey, e.getClass().getSimpleName());
        }
    }

    private static <T> T store(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new TokenStoreException(operation, e);
        }
    }

    static boolean wellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String encode(Object data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("令牌数据序列化失败", e);
        }
    }

    /** 值不存在为空；值解不开 / 是 JSON null / 没有账号（不该发生）也按无效处理并告警，不让坏数据变成认证通过。 */
    static Optional<TokenData> decode(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            TokenData data = JSON.readValue(value, TokenData.class);
            if (data == null || data.account() == null || data.account().isEmpty()) {
                log.warn("令牌数据没有账号，按无效处理");
                return Optional.empty();
            }
            return Optional.of(new TokenData(data.account(), data.authType() == null ? "" : data.authType(),
                    data.deviceId() == null ? "" : data.deviceId(), data.createdAt()));
        } catch (JsonProcessingException e) {
            log.warn("令牌数据解不开，按无效处理: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    /** 记录不存在为空；解不开或缺新令牌（不该发生）按无效处理并告警：宁可让客户端重新登录，也不发出半截的一对。 */
    static Optional<RotationRecord> decodeRotation(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            RotationRecord rec = JSON.readValue(value, RotationRecord.class);
            if (rec == null || !wellFormed(rec.accessToken()) || !wellFormed(rec.refreshToken())) {
                log.warn("refresh 轮换记录缺新令牌，按无效处理");
                return Optional.empty();
            }
            return Optional.of(rec);
        } catch (JsonProcessingException e) {
            // 不记原消息：它可能带上记录片段，即新令牌
            log.warn("refresh 轮换记录解不开，按无效处理: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 轮换记录（键 {@link RedisKeys#loginRefreshRotated}，值是蛇形键 JSON，同基线 {@code rotationRecord}）：那次轮换换出的一对。
     * 新令牌以明文存放——它们本来就以明文出现在令牌键名里，这条记录不新增暴露面，且 TTL 不超过 {@link #MAX_REFRESH_REUSE_GRACE}。
     */
    record RotationRecord(String account, String accessToken, String refreshToken, long accessExpire, long refreshExpire,
                          long rotatedAt) {
    }
}
