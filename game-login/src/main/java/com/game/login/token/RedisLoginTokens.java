package com.game.login.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.discovery.RedisKeys;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginTokens} 的 Redis 实现（键见 {@link RedisKeys#loginAccessToken} 等）。线程安全、阻塞。
 * <ul>
 *   <li>签发：两个令牌键 + 家族记录 + 账号集合在同一 Lua 中预检后写入；
 *       之后把集合封顶到 {@link #MAX_REFRESH_PER_ACCOUNT}（尽力而为，失败不影响这次签发）。</li>
 *   <li>轮换：先读旧 refresh，再核家族账号/head、原件与账号索引，用一段 Lua 脚本原子地「作废旧 refresh、从账号集合摘掉它、
 *       写新的一对与账号集合、写轮换记录」。轮换记录（{@link RedisKeys#loginRefreshRotated}）TTL = 重放宽限窗口：
 *       窗口内同一旧 refresh 再来（回包丢失后的重试、并发的两路），只要新 refresh 还没用过、也没被淘汰，就原样返回同一对，
 *       不再签发；窗口外或新 refresh 已不在一律无效。窗口为 0 时不写记录，严格一次性（基线 {@code token.go} Refresh）。
 *       脚本发出之前看一次调用方给的截止，已过就不发（见 {@link LoginTokens#refresh(String, BooleanSupplier)}）。
 *       脚本沿用客户端的 Redisson 重发（不改成 {@code retryAttempts(0)}）：首发已执行时重发读不到旧 refresh、什么都不写，转到重放判定拿回同一对，
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
    private static final Pattern FAMILY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}");
    /** 家族号与令牌独立随机，128位→22字符；任何新签发都不能写空家族。 */
    private static final int FAMILY_BYTES = 16;

    // Lua不回滚运行期错误：所有可预见TYPE/原件/TTL检查必须早于第一条写入。
    /** access与家族/head在一次只读Lua中取快照，正常轮换推进head不能造成多次读误拒。 */
    private static final String ACCESS_SCRIPT = """
            if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('GET', KEYS[1]) ~= ARGV[1]
                or redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
            if ARGV[4] == '1' then return 1 end
            if redis.call('TYPE', KEYS[2]).ok ~= 'hash' or redis.call('PTTL', KEYS[2]) <= 0
                or redis.call('HGET', KEYS[2], 'account') ~= ARGV[2] then return 0 end
            local head = redis.call('HGET', KEYS[2], 'head')
            if not head or string.len(head) ~= 43 or string.find(head, '[^A-Za-z0-9_-]') then return 0 end
            local key = ARGV[5] .. head
            if redis.call('TYPE', key).ok ~= 'string' or redis.call('PTTL', key) <= 0 then return 0 end
            local ok, data = pcall(cjson.decode, redis.call('GET', key))
            if not ok or type(data) ~= 'table' or data.account ~= ARGV[2] or data.family_id ~= ARGV[3] then return 0 end
            return 1
            """;

    private static final String ISSUE_SCRIPT = """
            local t = redis.call('TYPE', KEYS[3]).ok
            if t ~= 'none' and t ~= 'zset' then return -2 end
            if redis.call('EXISTS', KEYS[1], KEYS[2], KEYS[4]) ~= 0 then return -1 end
            if tonumber(ARGV[2]) <= 0 or tonumber(ARGV[3]) <= 0 then return -2 end
            redis.call('HSET', KEYS[4], 'account', ARGV[7], 'head', ARGV[6],
                       'prev', '', 'created_at', ARGV[4], 'rotated_at', ARGV[4])
            redis.call('PEXPIRE', KEYS[4], ARGV[3])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. ARGV[4])
            redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            return 1
            """;

    /** 原raw值、account、head和类型在首写前同一Lua核验；旧无家族仅在此CAS内收编。 */
    private static final String ROTATE_SCRIPT = """
            local oldtype = redis.call('TYPE', KEYS[1]).ok
            if oldtype ~= 'none' and oldtype ~= 'string' then return -2 end
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            if raw ~= ARGV[10] then return -1 end
            if redis.call('PTTL', KEYS[1]) <= 0 then return -1 end
            local settype = redis.call('TYPE', KEYS[5]).ok
            local replaytype = redis.call('TYPE', KEYS[2]).ok
            local familytype = redis.call('TYPE', KEYS[6]).ok
            if (settype ~= 'none' and settype ~= 'zset') or
               (replaytype ~= 'none' and replaytype ~= 'string') or
               (familytype ~= 'none' and familytype ~= 'hash') then return -2 end
            if settype ~= 'zset' or not redis.call('ZSCORE', KEYS[5], ARGV[7]) then return -1 end
            if replaytype ~= 'none' then return -1 end
            if redis.call('EXISTS', KEYS[3], KEYS[4]) ~= 0 then return -2 end
            if tonumber(ARGV[2]) <= 0 or tonumber(ARGV[3]) <= 0 then return -2 end
            if ARGV[12] == '1' then
              if familytype ~= 'none' then return -1 end
            elseif familytype ~= 'hash' or redis.call('HGET', KEYS[6], 'account') ~= ARGV[11]
                or redis.call('HGET', KEYS[6], 'head') ~= ARGV[7]
                or redis.call('PTTL', KEYS[6]) <= 0 then return -1 end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[5], ARGV[7])
            redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[4], ARGV[1], 'PX', ARGV[3])
            if ARGV[12] == '1' then redis.call('HSET', KEYS[6], 'created_at', ARGV[4]) end
            redis.call('HSET', KEYS[6], 'account', ARGV[11], 'head', ARGV[6],
                       'prev', ARGV[7], 'rotated_at', ARGV[4])
            redis.call('PEXPIRE', KEYS[6], ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', '(' .. ARGV[4])
            redis.call('ZADD', KEYS[5], ARGV[5], ARGV[6])
            redis.call('PEXPIRE', KEYS[5], ARGV[3])
            if tonumber(ARGV[9]) > 0 then
              redis.call('SET', KEYS[2], ARGV[8], 'PX', ARGV[9])
            end
            return 1
            """;

    /** 先核整个淘汰集合，再写；不可能删掉在Java读旧候选后已经轮换的新head。 */
    private static final String TRIM_SCRIPT = """
            local t = redis.call('TYPE', KEYS[1]).ok
            if t ~= 'none' and t ~= 'zset' then return -2 end
            local n = tonumber(ARGV[2])
            local values = {}
            for i = 1, n do
              local tokenkey = KEYS[2 * i]
              local familykey = KEYS[2 * i + 1]
              local j = 3 + (i - 1) * 3
              local tt = redis.call('TYPE', tokenkey).ok
              if tt ~= 'none' and tt ~= 'string' then return -2 end
              local value = redis.call('GET', tokenkey)
              if value then
                if value ~= ARGV[j] then return -1 end
                if ARGV[j + 2] == '1' then
                  local ft = redis.call('TYPE', familykey).ok
                  if ft ~= 'none' and ft ~= 'hash' then return -2 end
                  if ft == 'hash' and (redis.call('HGET', familykey, 'account') ~= ARGV[1]
                      or redis.call('HGET', familykey, 'head') ~= ARGV[j + 1]) then return -1 end
                end
              end
              values[i] = value or false
            end
            for i = 1, n do
              local j = 3 + (i - 1) * 3
              if values[i] then
                if ARGV[j + 2] == '1' then redis.call('DEL', KEYS[2 * i + 1]) end
                redis.call('DEL', KEYS[2 * i])
              end
              redis.call('ZREM', KEYS[1], ARGV[j + 1])
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
        if (accessTtl == null || refreshTtl == null || accessTtl.toMillis() <= 0 || refreshTtl.toMillis() <= 0) {
            throw new IllegalArgumentException("令牌TTL必须为正毫秒");
        }
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.reuseGraceMillis = refreshReuseGrace.toMillis();
        this.random = random;
    }

    @Override
    public TokenPair issue(String account, String authType, String deviceId) {
        long nowSeconds = clock.instant().getEpochSecond();
        String familyId = randomValue(FAMILY_BYTES);
        String value = encode(new TokenData(account, authType, deviceId, nowSeconds, familyId));
        String access = newToken(), refresh = newToken();
        long accessExpire = nowSeconds + accessTtl.toSeconds();
        long refreshExpire = nowSeconds + refreshTtl.toSeconds();
        String setKey = redisKeys.loginAccountRefresh(account);
        Long issued = store("签发", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ISSUE_SCRIPT,
                RScript.ReturnType.INTEGER, List.of(redisKeys.loginAccessToken(access), redisKeys.loginRefreshToken(refresh),
                        setKey, redisKeys.loginRefreshFamily(familyId)), value, String.valueOf(accessTtl.toMillis()),
                String.valueOf(refreshTtl.toMillis()), String.valueOf(nowSeconds), String.valueOf(refreshExpire), refresh, account));
        if (issued == null || issued != 1L) throw new TokenStoreException("签发", new IllegalStateException());
        trim(account, setKey);
        return new TokenPair(access, refresh, accessExpire, refreshExpire);
    }

    @Override
    public Optional<TokenData> validateAccess(String accessToken) {
        if (!wellFormed(accessToken)) return Optional.empty();
        String accessKey = redisKeys.loginAccessToken(accessToken);
        String raw = store("校验", () -> redis.<String>getBucket(accessKey, StringCodec.INSTANCE).get());
        Optional<TokenData> data = decode(raw);
        if (data.isEmpty()) return data;
        TokenData token = data.get();
        boolean legacy = token.familyId().isEmpty();
        Long valid = store("校验", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, ACCESS_SCRIPT,
                RScript.ReturnType.INTEGER, List.of(accessKey, legacy ? accessKey : redisKeys.loginRefreshFamily(token.familyId())),
                raw, token.account(), token.familyId(), legacy ? "1" : "0", redisKeys.loginRefreshToken("")));
        return valid != null && valid == 1L ? data : Optional.empty();
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
        boolean legacy = old.familyId().isEmpty();
        String familyId = legacy ? randomValue(FAMILY_BYTES) : old.familyId();
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
                        redisKeys.loginAccessToken(access), redisKeys.loginRefreshToken(refresh), setKey,
                        redisKeys.loginRefreshFamily(familyId)),
                encode(new TokenData(old.account(), old.authType(), old.deviceId(), nowSeconds, familyId)),
                String.valueOf(accessTtl.toMillis()), String.valueOf(refreshTtl.toMillis()), String.valueOf(nowSeconds),
                String.valueOf(pair.refreshExpireSeconds()), refresh, refreshToken, record,
                String.valueOf(reuseGraceMillis), value, old.account(), legacy ? "1" : "0"));
        if (rotated != null && rotated == -2L) throw new TokenStoreException("轮换", new IllegalStateException());
        if (rotated != null && rotated == -1L) return Optional.empty();
        if (rotated == null || rotated != 1L) {
            // 读到旧值之后、脚本执行之前，并发的另一路抢先轮换了它：按重放判定，拿那一路换出的同一对
            return replay(refreshToken);
        }
        trim(old.account(), setKey);
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
        long replayTtl = store("重放判定", () -> redis.getBucket(redisKeys.loginRefreshRotated(oldToken), StringCodec.INSTANCE)
                .remainTimeToLive());
        if (replayTtl <= 0 || replayTtl > reuseGraceMillis) return Optional.empty();
        RotationRecord rec = record.get();
        Optional<TokenData> successor = decode(store("重放判定", () -> redis.<String>getBucket(
                redisKeys.loginRefreshToken(rec.refreshToken()), StringCodec.INSTANCE).get()));
        boolean successorLive = successor.isPresent() && rec.account().equals(successor.get().account())
                && store("重放判定", () -> redis.getBucket(redisKeys.loginRefreshToken(rec.refreshToken()), StringCodec.INSTANCE)
                        .remainTimeToLive()) > 0;
        if (successorLive && !successor.get().familyId().isEmpty()) {
            Map<String, String> family = store("重放判定", () -> redis.<String, String>getMap(
                    redisKeys.loginRefreshFamily(successor.get().familyId()), StringCodec.INSTANCE)
                    .getAll(Set.of("account", "head", "prev")));
            successorLive = rec.account().equals(family.get("account")) && rec.refreshToken().equals(family.get("head"))
                    && oldToken.equals(family.get("prev"))
                    && store("重放判定", () -> redis.getMap(redisKeys.loginRefreshFamily(successor.get().familyId()), StringCodec.INSTANCE)
                            .remainTimeToLive()) > 0;
        }
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
    private void trim(String account, String setKey) {
        try {
            RScoredSortedSet<String> set = redis.getScoredSortedSet(setKey, StringCodec.INSTANCE);
            int size = set.size();
            if (size <= MAX_REFRESH_PER_ACCOUNT) return;
            Collection<String> oldest = set.valueRange(0, size - MAX_REFRESH_PER_ACCOUNT - 1);
            if (oldest.isEmpty()) return;
            List<Object> keys = new java.util.ArrayList<>(); keys.add(setKey);
            List<Object> arguments = new java.util.ArrayList<>(); arguments.add(account); arguments.add(String.valueOf(oldest.size()));
            for (String token : oldest) {
                if (!wellFormed(token)) throw new IllegalStateException("淘汰候选格式损坏");
                String raw = redis.<String>getBucket(redisKeys.loginRefreshToken(token), StringCodec.INSTANCE).get();
                Optional<TokenData> data = decode(raw);
                if (raw != null && (data.isEmpty() || !account.equals(data.get().account()))) {
                    throw new IllegalStateException("淘汰候选账号或原件损坏");
                }
                String familyId = data.map(TokenData::familyId).orElse("");
                keys.add(redisKeys.loginRefreshToken(token));
                keys.add(familyId.isEmpty() ? setKey : redisKeys.loginRefreshFamily(familyId));
                arguments.add(raw == null ? "" : raw); arguments.add(token); arguments.add(familyId.isEmpty() ? "0" : "1");
            }
            Long trimmed = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, TRIM_SCRIPT,
                    RScript.ReturnType.INTEGER, keys, arguments.toArray());
            if (trimmed == null || trimmed != 1L) throw new IllegalStateException("淘汰前原件或类型改变");
        } catch (RuntimeException e) {
            // 既有封顶是尽力而为；不打印Redis命令参数、令牌或家族号。
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

    private static boolean validFamily(String value) {
        if (value == null || !FAMILY_FORMAT.matcher(value).matches()) return false;
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        return decoded.length == FAMILY_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
    }

    private String newToken() {
        return randomValue(TOKEN_BYTES);
    }

    private String randomValue(int length) {
        byte[] bytes = new byte[length];
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
            var node = JSON.readTree(value);
            if (node == null || !node.isObject()) return Optional.empty();
            var family = node.get("family_id");
            // 只有真正旧JSON缺字段认旧；显式null/空/非法值不能借兼容分支绕过撤销。
            if (family != null && (!family.isTextual() || !validFamily(family.textValue()))) {
                return Optional.empty();
            }
            TokenData data = JSON.treeToValue(node, TokenData.class);
            if (data == null || data.account() == null || data.account().isEmpty()) {
                log.warn("令牌数据没有账号，按无效处理");
                return Optional.empty();
            }
            return Optional.of(new TokenData(data.account(), data.authType() == null ? "" : data.authType(),
                    data.deviceId() == null ? "" : data.deviceId(), data.createdAt(), family == null ? "" : family.textValue()));
        } catch (JsonProcessingException e) {
            log.warn("令牌数据解不开，按无效处理: {}", e.getClass().getSimpleName());
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
            if (rec == null || rec.account() == null || rec.account().isEmpty() || !wellFormed(rec.accessToken()) || !wellFormed(rec.refreshToken())) {
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
