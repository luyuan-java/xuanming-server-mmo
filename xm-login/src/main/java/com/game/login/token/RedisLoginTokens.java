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
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginTokens} 的 Redis 实现（键见 {@link RedisKeys#loginAccessToken} 等）。线程安全、阻塞。
 * <ul>
 *   <li>签发：两个令牌键 + 账号集合（先按分数清掉已过期的成员，再加入新成员、续集合的 TTL）在一个事务里写；
 *       之后把集合封顶到 {@link #MAX_REFRESH_PER_ACCOUNT}（尽力而为，失败不影响这次签发）。</li>
 *   <li>轮换：原子取走旧 refresh（Redisson getAndDelete，一段 GET + DEL 的 Lua 脚本；基线是 GET 再 DEL、靠 DEL 的返回数防双用），
 *       从账号集合里摘掉它（尽力而为，同基线忽略出错），再签新的一对。</li>
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
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RedissonClient redis;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random;

    public RedisLoginTokens(RedissonClient redis, Clock clock, Duration accessTtl, Duration refreshTtl,
                            SecureRandom random) {
        this.redis = redis;
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
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

        String setKey = RedisKeys.loginAccountRefresh(account);
        store("签发", () -> {
            RBatch batch = redis.createBatch(BatchOptions.defaults()
                    .executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
            batch.getBucket(RedisKeys.loginAccessToken(access), StringCodec.INSTANCE).setAsync(value, accessTtl);
            batch.getBucket(RedisKeys.loginRefreshToken(refresh), StringCodec.INSTANCE).setAsync(value, refreshTtl);
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
        return decode(store("校验", () -> redis.<String>getBucket(RedisKeys.loginAccessToken(accessToken),
                StringCodec.INSTANCE).get()));
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken) {
        if (!wellFormed(refreshToken)) {
            return Optional.empty();
        }
        String value = store("轮换", () -> redis.<String>getBucket(RedisKeys.loginRefreshToken(refreshToken),
                StringCodec.INSTANCE).getAndDelete());
        Optional<TokenData> data = decode(value);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        try {
            redis.<String>getScoredSortedSet(RedisKeys.loginAccountRefresh(data.get().account()), StringCodec.INSTANCE)
                    .remove(refreshToken);
        } catch (RuntimeException e) {
            // 同基线忽略：残留的成员按分数过期清掉 / 封顶时淘汰
            log.warn("从账号集合摘掉用过的 refresh 失败（不影响轮换） account={}: {}", data.get().account(),
                    e.getClass().getSimpleName());
        }
        return Optional.of(issue(data.get().account(), data.get().authType(), data.get().deviceId()));
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
                keys.add(RedisKeys.loginRefreshToken(token));
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

    static String encode(TokenData data) {
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
}
