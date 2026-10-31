package com.game.login.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LoginTokens} 的 Redis 实现（键见 {@link RedisKeys#loginAccessToken} 等），与 mmorpg {@code go/login/internal/logic/pkg/token}
 * 同一套设计（mmorpg {@code docs/design/dual-token-authentication.md}「令牌家族、重用检测与家族吊销」）。线程安全、阻塞。
 *
 * <p><b>家族</b>：一次完整认证（{@link #issue}）签出的那一对，加上由它逐次轮换出的全部后继。两种令牌的 32 字节都是
 * 16 字节家族号 + 16 字节随机数，随机半段第一个字节的最高位是类型位（0 = refresh，1 = access）；外观仍是 43 字符 base64url。
 * 任何一枚令牌——包括已经作废的旧 refresh——都能从自身解出家族号与类型；类型位让「把 access 当 refresh 发来」在碰 Redis 之前
 * 就被普通拒绝，不会被当成重用。一个家族的全部键（两种令牌、家族记录、轮换记录）以家族号作 hash tag，同在一个 Redis Cluster slot：
 * 签发、轮换、重放判定、吊销各是一段单 slot 的 Lua，只碰 KEYS 里声明过的键、不在 Lua 里解析 JSON。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>家族记录（{@link RedisKeys#loginRefreshFamily}）是唯一的真相源；记录不在 = 已吊销或已结束：该家族的 refresh 不能轮换，
 *       宽限重放不返回，access 不能登录。</li>
 *   <li>每个家族恰有一枚活 refresh，就是记录里的 head；只有 head 能轮换。</li>
 *   <li>令牌与家族记录都带正 TTL；被 PERSIST 成永久的、类型 / 归属 / 内容对不上的，一律按损坏拒绝，且脚本把全部检查放在第一次写之前，
 *       拒绝时一个字节都不写（Lua 运行期错误不回滚已执行的写）。</li>
 *   <li>只认规范编码：同一 32 字节只对应一个字符串，填充位非零的变体与任意文本在碰 Redis 之前就被拒绝。</li>
 * </ul>
 *
 * <p><b>轮换与重放</b>：轮换在一段 Lua 里按值比对旧 refresh、作废它、签新的一对、推进家族记录、写轮换记录
 * （{@link RedisKeys#loginRefreshRotated}，TTL = 重放宽限窗口）。窗口内同一旧 refresh 再来，新 refresh 还没用过且家族仍在，
 * 原样返回同一对。已轮换的旧成员再次出现，而它的后继已经被轮换走（窗口内）、或已经没有轮换记录（窗口外），家族却还活着，就是
 * <b>重用</b>：有两方在用同一条链，吊销整个家族（可配成只观测）。脚本发出之前看一次调用方给的截止，已过就不发
 * （见 {@link LoginTokens#refresh(String, BooleanSupplier)}）。脚本沿用客户端的 Redisson 重发：轮换的首发已执行时重发读不到
 * 同一份旧值、什么都不写，转到重放判定拿回同一对；签发的首发已执行时重发按已写入返回。窗口为 0 时轮换的重发会被当作重用而吊销，
 * 所以窗口 0 不要与吊销同时开（装配时报错日志提示）。
 *
 * <p><b>账号索引</b>（{@link RedisKeys#loginAccountRefresh}，成员是家族号）是二级索引，只服务每账号家族数封顶；它与家族不同 slot，
 * 不进家族的原子提交。签发先写家族、再写索引，索引写失败（或登记完发现家族已被并发吊销）就按值撤回家族并让签发失败；
 * 轮换后尽力而为地续 score，续完家族已被并发吊销就不交出这一对。令牌是否有效从不看索引。
 *
 * <p>存储出错抛 {@link TokenStoreException}（不带原异常：Redisson 的异常消息会带命令参数，即令牌本身）。
 */
public final class RedisLoginTokens implements LoginTokens {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginTokens.class);

    /** 令牌 32 字节 → base64url 无填充 43 字符；前 16 字节是家族号。 */
    static final int TOKEN_BYTES = 32;
    static final int FAMILY_BYTES = 16;
    /** 随机半段第一个字节的最高位：1 = access，0 = refresh。 */
    static final int ACCESS_KIND_BIT = 0x80;
    /** 每账号活家族上限（基线 maxFamiliesPerAccount）。每个家族恰有一枚活 refresh，所以也是活 refresh 上限。 */
    static final int MAX_FAMILIES_PER_ACCOUNT = 32;
    /**
     * 重放宽限窗口的上限（基线 MaxRefreshReuseGrace）：窗口越长，偷到旧 refresh 的人能搭车的时间越长；
     * 60s 足够覆盖「客户端超时 + 重连 + 重发」。也是轮换记录 TTL 的硬上限：剩余 TTL 超过它的记录按损坏处理。
     */
    public static final Duration MAX_REFRESH_REUSE_GRACE = Duration.ofSeconds(60);
    /** 吊销脚本幂等：检测到重用后有界重试（基线 revokeAttempts / revokeBackoff）。 */
    private static final int REVOKE_ATTEMPTS = 3;
    private static final long REVOKE_BACKOFF_MILLIS = 100;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** 令牌的类型位。 */
    enum Kind { REFRESH, ACCESS }

    /** 从令牌解出的家族号与类型。 */
    record Parsed(String familyId, Kind kind) {
    }

    /**
     * 签发（家族 slot 内原子）：三个新键必须都不存在（128 位随机撞上已有键只可能是随机源或代码出了问题，拒绝而不是覆盖）。
     * 例外是驱动重发：首发已写入、回包丢失后同一组参数再来，三个键的内容与本次完全一致（只有同一次签发会命中），按已写入返回 1。
     * KEYS = access 键、refresh 键、家族键；ARGV = 令牌数据 JSON、access TTL 毫秒、refresh TTL 毫秒、账号、refresh、现在秒。
     */
    private static final String ISSUE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1], KEYS[2], KEYS[3]) ~= 0 then
              if redis.call('TYPE', KEYS[1]).ok == 'string' and redis.call('GET', KEYS[1]) == ARGV[1]
                  and redis.call('TYPE', KEYS[2]).ok == 'string' and redis.call('GET', KEYS[2]) == ARGV[1]
                  and redis.call('TYPE', KEYS[3]).ok == 'hash' and redis.call('HGET', KEYS[3], 'head') == ARGV[5]
                  and redis.call('HGET', KEYS[3], 'account') == ARGV[4] then
                return 1
              end
              return redis.error_reply('token identity collision')
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3])
            redis.call('HSET', KEYS[3], 'account', ARGV[4], 'head', ARGV[5], 'prev', '', 'created_at', ARGV[6], 'rotated_at', ARGV[6])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            return 1
            """;

    /**
     * 撤回一次没交出去的签发（家族 slot 内原子）：只删内容仍是本次写入的键（compare-and-delete），撞键时不会误删别人的键。
     * KEYS = access 键、refresh 键、家族键；ARGV = 令牌数据 JSON、refresh。
     */
    private static final String WITHDRAW_SCRIPT = """
            if redis.call('TYPE', KEYS[1]).ok == 'string' and redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1])
            end
            if redis.call('TYPE', KEYS[2]).ok == 'string' and redis.call('GET', KEYS[2]) == ARGV[1] then
              redis.call('DEL', KEYS[2])
            end
            if redis.call('TYPE', KEYS[3]).ok == 'hash' and redis.call('HGET', KEYS[3], 'head') == ARGV[2] then
              redis.call('DEL', KEYS[3])
            end
            return 1
            """;

    /**
     * access 校验（只读，同 slot 一次快照）：KEYS = access 键、家族键。全部回字符串：
     * {"0"} access 不在；{"-1"} 损坏（无 TTL、家族类型不对或无 TTL）；{"2", 值} 家族不在；{"1", 值, 家族账号} 都在，由调用方核对值。
     */
    private static final String VALIDATE_ACCESS_SCRIPT = """
            local payload = redis.call('GET', KEYS[1])
            if not payload then return {'0'} end
            if redis.call('PTTL', KEYS[1]) <= 0 then return {'-1'} end
            local kind = redis.call('TYPE', KEYS[2]).ok
            if kind == 'none' then return {'2', payload} end
            if kind ~= 'hash' or redis.call('PTTL', KEYS[2]) <= 0 then return {'-1'} end
            return {'1', payload, redis.call('HGET', KEYS[2], 'account') or ''}
            """;

    /**
     * 轮换（家族 slot 内原子）。写之前依次核对：旧 refresh 仍是读到的那一份（否则 0）、带正 TTL；家族在（不在回 2）、是 hash、
     * 带正 TTL、属于同一账号；旧 refresh 就是 head（不是回 3）；新令牌不撞键。0、2、3 与报错都不写。
     * KEYS = 旧 refresh 键、新 access 键、新 refresh 键、家族键、轮换记录键；
     * ARGV = 旧 refresh 的原值、新令牌数据 JSON、access TTL 毫秒、refresh TTL 毫秒、新 refresh、旧 refresh、现在秒、
     * 宽限窗口毫秒（0 = 不写记录）、轮换记录 JSON、账号。
     */
    private static final String ROTATE_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('PTTL', KEYS[1]) <= 0 then return redis.error_reply('refresh token without expiry') end
            local kind = redis.call('TYPE', KEYS[4]).ok
            if kind == 'none' then return 2 end
            if kind ~= 'hash' or redis.call('PTTL', KEYS[4]) <= 0 or redis.call('HGET', KEYS[4], 'account') ~= ARGV[10] then
              return redis.error_reply('token family damaged')
            end
            if redis.call('HGET', KEYS[4], 'head') ~= ARGV[6] then return 3 end
            if redis.call('EXISTS', KEYS[2], KEYS[3]) ~= 0 then return redis.error_reply('token identity collision') end
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[4])
            redis.call('HSET', KEYS[4], 'head', ARGV[5], 'prev', ARGV[6], 'rotated_at', ARGV[7])
            redis.call('PEXPIRE', KEYS[4], ARGV[4])
            if tonumber(ARGV[8]) > 0 then
              redis.call('SET', KEYS[5], ARGV[9], 'PX', ARGV[8])
            end
            return 1
            """;

    /**
     * 宽限重放前的核对（只读，同 slot），与基线 {@code replayCheckScript} 同一组返回值。
     * KEYS = 轮换记录键、后继 refresh 键、家族键；ARGV = 账号、当前宽限窗口毫秒、窗口硬上限毫秒、后继 refresh。
     * 返回 1 都在，返回同一对；0 后继已过期或被外部删除（家族头仍指着它）；2 家族不在；4 记录在 GET 之后刚好过期，按「没有记录」处理
     * （转重用检测）；5 记录剩余 TTL 超过当前窗口（窗口刚调小后的过渡期），按窗口外普通无效；6 后继已被轮换走而家族还活着，按重用处理；
     * -1 损坏（记录被 PERSIST 或超过硬上限、家族类型 / TTL / 归属不对、后继被 PERSIST）。
     */
    private static final String REPLAY_CHECK_SCRIPT = """
            local record = redis.call('PTTL', KEYS[1])
            if record == -2 then return 4 end
            if record < 0 or record > tonumber(ARGV[3]) then return -1 end
            if record > tonumber(ARGV[2]) then return 5 end
            local kind = redis.call('TYPE', KEYS[3]).ok
            if kind == 'none' then return 2 end
            if kind ~= 'hash' or redis.call('PTTL', KEYS[3]) <= 0 or redis.call('HGET', KEYS[3], 'account') ~= ARGV[1] then
              return -1
            end
            local successor = redis.call('PTTL', KEYS[2])
            if successor == -1 then return -1 end
            if successor == -2 then
              if redis.call('HGET', KEYS[3], 'head') ~= ARGV[4] then return 6 end
              return 0
            end
            return 1
            """;

    /**
     * 吊销一个家族（家族 slot 内原子）：记录必须是 hash 且属于给定账号，否则报错不写（被损坏的账号索引不能拿来删别人的家族）；
     * head 仍是读到的那一枚就顺带删掉它的键；删记录。读到 head 之后它被并发轮换走时，新 head 的键留到 TTL，
     * 但家族记录已不在，它不能轮换，只多占一点内存。
     * KEYS = 家族键、读到的 head 的 refresh 键；ARGV = 读到的 head、账号。返回 1 已吊销；0 本来就不在。
     */
    private static final String REVOKE_SCRIPT = """
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind == 'none' then return 0 end
            if kind ~= 'hash' then return redis.error_reply('token family damaged') end
            if redis.call('HGET', KEYS[1], 'account') ~= ARGV[2] then
              return redis.error_reply('token family owned by another account')
            end
            if ARGV[1] ~= '' and redis.call('HGET', KEYS[1], 'head') == ARGV[1] then
              redis.call('DEL', KEYS[2])
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    /**
     * 把家族登记 / 续进账号索引（单键）：索引必须是 zset 或不存在（否则报错且不写，不给坏键续期），先按分数清掉已过期的死成员，
     * 再写这个家族的过期秒，续整键 TTL。KEYS = 账号索引；ARGV = 死成员分数上界、家族过期秒、家族号、TTL 毫秒。
     */
    private static final String INDEX_SCRIPT = """
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind ~= 'none' and kind ~= 'zset' then return redis.error_reply('token family index damaged') end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """;

    private static final long ROTATED = 1L;
    private static final long CONSUMED = 0L;
    private static final long FAMILY_GONE = 2L;
    private static final long NOT_HEAD = 3L;

    private static final String COLLISION = "token identity collision";

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final long reuseGraceMillis;
    private final boolean revokeOnReuse;
    private final SecureRandom random;

    /** 缺省重用即吊销（安全缺省）。 */
    public RedisLoginTokens(RedissonClient redis, RedisKeys keys, Clock clock, Duration accessTtl, Duration refreshTtl,
                            Duration refreshReuseGrace, SecureRandom random) {
        this(redis, keys, clock, accessTtl, refreshTtl, refreshReuseGrace, true, random);
    }

    /**
     * @param keys              令牌键的出处（带部署命名空间，装配方注入）
     * @param refreshReuseGrace 重放宽限窗口，[0, {@link #MAX_REFRESH_REUSE_GRACE}]，整毫秒；0 = 严格一次性
     * @param revokeOnReuse     重用时吊销家族；false = 只观测（照常检测、记日志，不吊销）
     */
    public RedisLoginTokens(RedissonClient redis, RedisKeys keys, Clock clock, Duration accessTtl, Duration refreshTtl,
                            Duration refreshReuseGrace, boolean revokeOnReuse, SecureRandom random) {
        Objects.requireNonNull(refreshReuseGrace, "refreshReuseGrace");
        if (refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(MAX_REFRESH_REUSE_GRACE) > 0) {
            throw new IllegalArgumentException("refresh 重放宽限窗口必须在 [0, " + MAX_REFRESH_REUSE_GRACE + "] 内: "
                    + refreshReuseGrace);
        }
        // 脚本按毫秒算：亚毫秒的余数会让这里以为窗口开着，而脚本按截断后的值写记录（不足 1ms 时不写），实际变成窗口 0 加吊销
        if (refreshReuseGrace.toNanos() % 1_000_000L != 0) {
            throw new IllegalArgumentException("refresh 重放宽限窗口必须是整毫秒: " + refreshReuseGrace);
        }
        if (accessTtl == null || refreshTtl == null || accessTtl.toMillis() <= 0 || refreshTtl.toMillis() <= 0) {
            throw new IllegalArgumentException("令牌TTL必须为正毫秒");
        }
        this.redis = redis;
        this.redisKeys = Objects.requireNonNull(keys, "keys");
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.reuseGraceMillis = refreshReuseGrace.toMillis();
        this.revokeOnReuse = revokeOnReuse;
        this.random = random;
    }

    /**
     * 两步，各自原子：① 家族 slot 里一段脚本写两枚令牌与家族记录；② 账号索引登记这个家族。② 失败、或登记完发现家族已被并发的封顶吊销，
     * 就按值撤回 ① 并失败，所以交出去的每个家族都在索引里且活着。① 的结果未知（超时、断连）时同样撤回；撤回本身失败时留下的家族
     * 没有交给任何人，到 TTL 自然消失。
     */
    @Override
    public TokenPair issue(String account, String authType, String deviceId) {
        if (account == null || account.isEmpty() || authType == null || authType.isEmpty()) {
            throw new IllegalArgumentException("签发令牌的账号与认证方式不能为空");
        }
        String setKey = redisKeys.loginAccountRefresh(account);
        // 索引类型不对时整个签发都写不进索引：先查，不留下要撤回的家族。
        RType indexType = store("签发", () -> redis.getKeys().getType(setKey));
        if (indexType != null && indexType != RType.ZSET) {
            throw new TokenStoreException("签发", new IllegalStateException("账号家族索引类型损坏"));
        }

        long nowSeconds = clock.instant().getEpochSecond();
        String familyId = randomValue(FAMILY_BYTES);
        String access = newToken(familyId, Kind.ACCESS);
        String refresh = newToken(familyId, Kind.REFRESH);
        long refreshExpire = nowSeconds + refreshTtl.toSeconds();
        TokenPair pair = new TokenPair(access, refresh, nowSeconds + accessTtl.toSeconds(), refreshExpire);
        String payload = encode(new TokenData(account, authType, deviceId, nowSeconds, familyId));
        List<Object> keys = List.of(redisKeys.loginAccessToken(familyId, access), redisKeys.loginRefreshToken(familyId, refresh),
                redisKeys.loginRefreshFamily(familyId));
        try {
            redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ISSUE_SCRIPT, RScript.ReturnType.INTEGER, keys,
                    payload, String.valueOf(accessTtl.toMillis()), String.valueOf(refreshTtl.toMillis()), account, refresh,
                    String.valueOf(nowSeconds));
        } catch (RuntimeException e) {
            if (!isScriptReply(e, COLLISION)) {
                // 结果未知：首发可能已经写入。按值撤回，撞键之外的情况都不会误删别人的键。
                withdrawIssue(account, familyId, keys, payload, refresh);
            }
            throw new TokenStoreException("签发", e);
        }

        boolean alive;
        try {
            alive = indexFamily(setKey, familyId, refreshExpire, nowSeconds);
        } catch (RuntimeException e) {
            withdrawIssue(account, familyId, keys, payload, refresh);
            throw new TokenStoreException("签发", e);
        }
        if (!alive) {
            withdrawIssue(account, familyId, keys, payload, refresh);
            throw new TokenStoreException("签发", new IllegalStateException("签出的令牌家族被并发吊销"));
        }
        // 封顶（尽力而为，失败不阻断签发）：超出即吊销最久没轮换的那几个家族，本次签发的家族不在候选里。
        trim(account, setKey, familyId);
        return pair;
    }

    /** 尽力撤回一次没交出去的签发；失败只记日志（留下的家族没有交给任何人，到 TTL 自然消失）。 */
    private void withdrawIssue(String account, String familyId, List<Object> keys, String payload, String refresh) {
        try {
            redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, WITHDRAW_SCRIPT, RScript.ReturnType.INTEGER,
                    keys, payload, refresh);
        } catch (RuntimeException e) {
            log.error("撤回没交出去的令牌家族失败 account={} family={}: {}", account, familyLogTag(familyId),
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<TokenData> validateAccess(String accessToken) {
        Optional<Parsed> parsed = parse(accessToken).filter(p -> p.kind() == Kind.ACCESS);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String familyId = parsed.get().familyId();
        List<Object> reply = store("校验", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                VALIDATE_ACCESS_SCRIPT, RScript.ReturnType.MULTI,
                List.of(redisKeys.loginAccessToken(familyId, accessToken), redisKeys.loginRefreshFamily(familyId))));
        String status = reply == null || reply.isEmpty() ? "-1" : String.valueOf(reply.get(0));
        switch (status) {
            case "0":
                return Optional.empty();
            case "1":
            case "2":
                break;
            default:
                log.warn("access 令牌或其家族记录损坏，按无效处理 family={}", familyLogTag(familyId));
                return Optional.empty();
        }
        Optional<TokenData> data = decode(String.valueOf(reply.get(1)), familyId);
        if (data.isEmpty()) {
            return data;
        }
        if (status.equals("2")) {
            log.info("access 所属家族已吊销或已结束，按无效处理 account={} family={}", data.get().account(), familyLogTag(familyId));
            return Optional.empty();
        }
        if (!data.get().account().equals(String.valueOf(reply.get(2)))) {
            log.warn("access 的家族记录属于别的账号，按无效处理 account={} family={}", data.get().account(), familyLogTag(familyId));
            return Optional.empty();
        }
        return data;
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline) {
        Objects.requireNonNull(pastDeadline, "pastDeadline");
        Optional<Parsed> parsed = parse(refreshToken).filter(p -> p.kind() == Kind.REFRESH);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String familyId = parsed.get().familyId();
        String value = store("轮换", () -> redis.<String>getBucket(redisKeys.loginRefreshToken(familyId, refreshToken),
                StringCodec.INSTANCE).get());
        if (value == null) {
            // 旧 refresh 不在：从未存在、过期，或已被轮换走（可能就是本请求丢了回包的那一次）
            return replay(familyId, refreshToken);
        }
        Optional<TokenData> data = decode(value, familyId);
        if (data.isEmpty()) {
            return Optional.empty();
        }

        TokenData old = data.get();
        long nowSeconds = clock.instant().getEpochSecond();
        String access = newToken(familyId, Kind.ACCESS);
        String refresh = newToken(familyId, Kind.REFRESH);
        TokenPair pair = new TokenPair(access, refresh, nowSeconds + accessTtl.toSeconds(),
                nowSeconds + refreshTtl.toSeconds());
        String record = reuseGraceMillis > 0
                ? encode(new RotationRecord(old.account(), familyId, access, refresh, pair.accessExpireSeconds(),
                        pair.refreshExpireSeconds(), nowSeconds))
                : "";
        if (pastDeadline.getAsBoolean()) {
            // 提交点（作废 + 签发的原子脚本）之前最后看一次截止；之前的读、造令牌都没有副作用。不带令牌、不带账号：消息可能进日志。
            throw new RotationAbandoned("请求截止已过，没有发出轮换脚本");
        }
        Long rotated = store("轮换", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, ROTATE_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(redisKeys.loginRefreshToken(familyId, refreshToken), redisKeys.loginAccessToken(familyId, access),
                        redisKeys.loginRefreshToken(familyId, refresh), redisKeys.loginRefreshFamily(familyId),
                        redisKeys.loginRefreshRotated(familyId, refreshToken)),
                value, encode(new TokenData(old.account(), old.authType(), old.deviceId(), nowSeconds, familyId)),
                String.valueOf(accessTtl.toMillis()), String.valueOf(refreshTtl.toMillis()), refresh, refreshToken,
                String.valueOf(nowSeconds), String.valueOf(reuseGraceMillis), record, old.account()));
        long result = rotated == null ? -1L : rotated;
        if (result == CONSUMED) {
            // 读到旧值之后、脚本执行之前，并发的另一路抢先轮换了它：按重放判定，拿那一路换出的同一对
            return replay(familyId, refreshToken);
        }
        if (result == FAMILY_GONE) {
            log.info("refresh 所属家族已吊销或已结束，按无效处理 account={} family={}", old.account(), familyLogTag(familyId));
            return Optional.empty();
        }
        if (result == NOT_HEAD) {
            log.warn("活着的 refresh 不是家族的 head（数据损坏），按无效处理 account={} family={}", old.account(), familyLogTag(familyId));
            return Optional.empty();
        }
        if (result != ROTATED) {
            throw new TokenStoreException("轮换", new IllegalStateException("轮换脚本返回值异常"));
        }
        // 提交之后续账号索引的 score（家族的新过期时刻），尽力而为：失败不影响这次轮换，下一次轮换会再续。
        // 续完发现家族已被并发吊销：这对令牌已经无效，不交出去。
        try {
            if (!indexFamily(redisKeys.loginAccountRefresh(old.account()), familyId, pair.refreshExpireSeconds(), nowSeconds)) {
                log.info("refresh 刚轮换完家族就被并发吊销，按无效处理 account={} family={}", old.account(), familyLogTag(familyId));
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            log.error("轮换后续账号家族索引失败（不影响这次轮换） account={} family={}: {}", old.account(), familyLogTag(familyId),
                    e.getClass().getSimpleName());
        }
        return Optional.of(pair);
    }

    /**
     * 对已不在的旧 refresh 的判定：有轮换记录（在宽限窗口内）时，新 refresh 仍在且家族仍在，原样返回那一对；新 refresh 已被轮换走、
     * 家族还活着，按重用处理；其余无效。没有轮换记录就按重用检测（{@link #detectReuse}）。
     */
    private Optional<TokenPair> replay(String familyId, String oldToken) {
        String rotatedKey = redisKeys.loginRefreshRotated(familyId, oldToken);
        String raw = store("重放判定", () -> redis.<String>getBucket(rotatedKey, StringCodec.INSTANCE).get());
        if (raw == null) {
            return detectReuse(familyId, oldToken);
        }
        Optional<RotationRecord> record = decodeRotation(raw, familyId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        RotationRecord rec = record.get();
        Long status = store("重放判定", () -> redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,
                REPLAY_CHECK_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(rotatedKey, redisKeys.loginRefreshToken(familyId, rec.refreshToken()), redisKeys.loginRefreshFamily(familyId)),
                rec.account(), String.valueOf(reuseGraceMillis), String.valueOf(MAX_REFRESH_REUSE_GRACE.toMillis()),
                rec.refreshToken()));
        long result = status == null ? -1L : status;
        if (result == 1L) {
            log.info("宽限窗口内重放旧 refresh，原样返回上次轮换的一对 account={} rotated_at={}", rec.account(), rec.rotatedAt());
            return Optional.of(new TokenPair(rec.accessToken(), rec.refreshToken(), rec.accessExpire(), rec.refreshExpire()));
        }
        if (result == 0L) {
            log.info("宽限窗口内重放旧 refresh，但新 refresh 已过期或被外部删除，按无效处理 account={} rotated_at={}",
                    rec.account(), rec.rotatedAt());
            return Optional.empty();
        }
        if (result == 2L) {
            log.info("宽限窗口内重放旧 refresh，但家族已吊销或已结束，按无效处理 account={} family={}", rec.account(),
                    familyLogTag(familyId));
            return Optional.empty();
        }
        if (result == 4L || result == 6L) {
            // 4：记录刚好过期，等同窗口外；6：后继已被轮换走而家族还活着。两者都交给重用检测按家族记录判定。
            return detectReuse(familyId, oldToken);
        }
        if (result == 5L) {
            log.info("重放旧 refresh，但轮换记录比当前宽限窗口长（窗口刚调小），按无效处理 account={}", rec.account());
            return Optional.empty();
        }
        log.warn("宽限窗口内重放旧 refresh，但轮换记录、新 refresh 或家族记录损坏，按无效处理 account={} family={}",
                rec.account(), familyLogTag(familyId));
        return Optional.empty();
    }

    /**
     * 已不在的旧 refresh：家族还活着、head 却不是它，即为重用（RFC 9700 §4.14.2：服务端分不清哪边是合法客户端），吊销整个家族；
     * 家族不在、或 head 就是它（刚过期或被外部删除），普通无效。吊销不看请求截止：漏掉这一次，合法客户端拿到无效后去重登，
     * 不会再拿那枚旧 token 回来，就再也等不到下一次检测。判定为重用却没能吊销时抛 {@link TokenStoreException}：家族还活着，
     * 是要人处理的安全事件。
     */
    private Optional<TokenPair> detectReuse(String familyId, String oldToken) {
        Map<String, String> fields = store("重用检测", () -> redis.<String, String>getMap(
                redisKeys.loginRefreshFamily(familyId), StringCodec.INSTANCE).readAllMap());
        String head = fields.get("head");
        if (fields.isEmpty() || oldToken.equals(head)) {
            return Optional.empty();
        }
        String account = fields.getOrDefault("account", "");
        long now = clock.instant().getEpochSecond();
        String replayed = oldToken.equals(fields.get("prev")) ? "predecessor" : "older";
        long delay = Math.max(now - parseLong(fields.get("rotated_at")), 0);
        long familyAge = Math.max(now - parseLong(fields.get("created_at")), 0);
        if (!revokeOnReuse) {
            log.warn("SECURITY 检测到 refresh 重用（只观测，未吊销） account={} family={} replayed={} delay={}s family_age={}s revoked=false",
                    account, familyLogTag(familyId), replayed, delay, familyAge);
            return Optional.empty();
        }
        try {
            revokeFamilyWithRetry(account, familyId, head == null ? "" : head);
        } catch (RuntimeException e) {
            log.error("SECURITY 检测到 refresh 重用，吊销失败 account={} family={} replayed={} delay={}s family_age={}s revoked=false: {}",
                    account, familyLogTag(familyId), replayed, delay, familyAge, e.getClass().getSimpleName());
            throw new TokenStoreException("吊销", e);
        }
        log.warn("SECURITY 检测到 refresh 重用，已吊销令牌家族 account={} family={} replayed={} delay={}s family_age={}s revoked=true",
                account, familyLogTag(familyId), replayed, delay, familyAge);
        return Optional.empty();
    }

    /** 吊销脚本幂等，有界重试；家族记录损坏或属于别的账号（脚本的明确拒绝）不重试。 */
    private void revokeFamilyWithRetry(String account, String familyId, String head) {
        for (int attempt = 1; ; attempt++) {
            try {
                revokeFamily(account, familyId, head);
                return;
            } catch (RuntimeException e) {
                if (attempt >= REVOKE_ATTEMPTS || isScriptReply(e, "token family")) {
                    throw e;
                }
            }
            try {
                Thread.sleep(REVOKE_BACKOFF_MILLIS << (attempt - 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("吊销令牌家族的重试被中断");
            }
        }
    }

    /**
     * 吊销一个家族（家族 slot 内原子），再尽力而为地从账号索引摘掉它（摘不掉无害：死成员按过期分数清，封顶先摘掉家族已不在的成员）。
     * 脚本出错原样抛出（调用方决定是否重试、怎么记），返回家族在吊销前是否还在。
     */
    private boolean revokeFamily(String account, String familyId, String head) {
        Long revoked = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, REVOKE_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(redisKeys.loginRefreshFamily(familyId), redisKeys.loginRefreshToken(familyId, head)), head, account);
        try {
            redis.<String>getScoredSortedSet(redisKeys.loginAccountRefresh(account), StringCodec.INSTANCE).remove(familyId);
        } catch (RuntimeException e) {
            log.error("从账号索引摘掉已吊销的家族失败 account={} family={}: {}", account, familyLogTag(familyId),
                    e.getClass().getSimpleName());
        }
        return revoked != null && revoked == 1L;
    }

    /** 先读出家族的 head，再吊销（head 用于顺带删掉它的键）。 */
    private boolean revokeFamilyById(String account, String familyId) {
        String head = redis.<String, String>getMap(redisKeys.loginRefreshFamily(familyId), StringCodec.INSTANCE).get("head");
        return revokeFamily(account, familyId, head == null ? "" : head);
    }

    /**
     * 把家族登记 / 续进账号索引，再核一次家族记录：登记期间家族已被并发吊销（吊销方先删记录、再摘索引），就把它摘掉并返回 false，
     * 索引里不留死成员。两个键分属不同 slot，各自线性一致：吊销方的摘除要么排在本次登记之后，要么本次的 EXISTS 已经看到记录被删。
     */
    private boolean indexFamily(String setKey, String familyId, long expireSeconds, long nowSeconds) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, INDEX_SCRIPT, RScript.ReturnType.INTEGER,
                List.of(setKey), String.valueOf(nowSeconds - 1), String.valueOf(expireSeconds), familyId,
                String.valueOf(refreshTtl.toMillis()));
        if (redis.getKeys().countExists(redisKeys.loginRefreshFamily(familyId)) > 0) {
            return true;
        }
        try {
            redis.<String>getScoredSortedSet(setKey, StringCodec.INSTANCE).remove(familyId);
        } catch (RuntimeException e) {
            log.error("从账号索引摘掉已吊销的家族失败 family={}: {}", familyLogTag(familyId), e.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * 封顶账号的活家族：先摘掉家族记录已不在的成员（不计入活家族），再按分数从小到大（最久没轮换）吊销超出的那几个，连同它们的 head；
     * {@code keep}（本次签发的家族）不在候选里。尽力而为，失败只记日志，下一次签发会再封一次。逐键 EXISTS 走非原子批量，
     * Cluster 下由 Redisson 按节点拆分。
     */
    private void trim(String account, String setKey, String keep) {
        try {
            RScoredSortedSet<String> set = redis.getScoredSortedSet(setKey, StringCodec.INSTANCE);
            List<String> members = new ArrayList<>(set.valueRange(0, -1));
            if (members.size() <= MAX_FAMILIES_PER_ACCOUNT) {
                return;
            }
            RBatch batch = redis.createBatch(BatchOptions.defaults());
            List<RFuture<Long>> exists = new ArrayList<>(members.size());
            for (String familyId : members) {
                exists.add(batch.getKeys().countExistsAsync(redisKeys.loginRefreshFamily(familyId)));
            }
            batch.execute();
            List<String> live = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                (exists.get(i).toCompletableFuture().join() > 0 ? live : stale).add(members.get(i));
            }
            if (!stale.isEmpty()) {
                set.removeAll(stale);
            }
            int excess = live.size() - MAX_FAMILIES_PER_ACCOUNT;
            for (String familyId : live) {
                if (excess <= 0) {
                    break;
                }
                if (familyId.equals(keep)) {
                    continue;
                }
                try {
                    revokeFamilyById(account, familyId);
                    excess--;
                } catch (RuntimeException e) {
                    log.error("封顶吊销令牌家族失败（不影响这次签发） account={} family={}: {}", account, familyLogTag(familyId),
                            e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            // 不记原异常：它的消息里可能有家族号
            log.error("封顶账号令牌家族失败（不影响这次签发） account={}: {}", account, e.getClass().getSimpleName());
        }
    }

    private static <T> T store(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new TokenStoreException(operation, e);
        }
    }

    /**
     * 异常是不是脚本的这句明确报错（{@code redis.error_reply}）。Redisson 把错误回复原样放在消息开头、后接命令参数；超时、断连等
     * 其他异常的消息也带命令参数，而参数里有脚本原文（含这些报错字样），所以只认开头，不能用包含。只用于分支判断，消息不进日志。
     */
    private static boolean isScriptReply(RuntimeException e, String reply) {
        String message = e.getMessage();
        return message != null && message.startsWith(reply);
    }

    static boolean wellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    /**
     * 从令牌解出家族号与类型；不是规范的 43 字符 base64url（客户端给的任意文本，或同一 32 字节的非规范编码）为空，调用方不碰 Redis。
     */
    static Optional<Parsed> parse(String token) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != TOKEN_BYTES || !ENCODER.encodeToString(raw).equals(token)) {
            return Optional.empty();
        }
        Kind kind = (raw[FAMILY_BYTES] & ACCESS_KIND_BIT) != 0 ? Kind.ACCESS : Kind.REFRESH;
        return Optional.of(new Parsed(ENCODER.encodeToString(Arrays.copyOf(raw, FAMILY_BYTES)), kind));
    }

    /** 从令牌解出家族号，不看类型。 */
    static Optional<String> familyOf(String token) {
        return parse(token).map(Parsed::familyId);
    }

    /** token 是规范编码、类型为 kind、嵌的正是 familyId。 */
    static boolean tokenOfFamily(String token, String familyId, Kind kind) {
        return parse(token).filter(p -> p.familyId().equals(familyId) && p.kind() == kind).isPresent();
    }

    /**
     * 只给日志串联同一家族的几行用：SHA-256(家族号) 的前 8 位 hex。家族号原值是令牌的一半，知道它就能拼出「家族号 + 任意」
     * 去触发吊销，所以不进日志。
     */
    static String familyLogTag(String familyId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(familyId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }

    /**
     * 生成属于 familyId、类型为 kind 的令牌：家族号 16 字节 ‖ 随机 16 字节（第一个字节的最高位是类型位）。
     * 对外人 256 bit 全部未知；对持有过同家族令牌的人未知部分是 127 bit，猜中当前的 head 不可行。
     */
    private String newToken(String familyId, Kind kind) {
        byte[] bytes = new byte[TOKEN_BYTES];
        System.arraycopy(Base64.getUrlDecoder().decode(familyId), 0, bytes, 0, FAMILY_BYTES);
        byte[] tail = new byte[TOKEN_BYTES - FAMILY_BYTES];
        random.nextBytes(tail);
        System.arraycopy(tail, 0, bytes, FAMILY_BYTES, tail.length);
        if (kind == Kind.ACCESS) {
            bytes[FAMILY_BYTES] |= (byte) ACCESS_KIND_BIT;
        } else {
            bytes[FAMILY_BYTES] &= (byte) ~ACCESS_KIND_BIT;
        }
        return ENCODER.encodeToString(bytes);
    }

    private String randomValue(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static String encode(Object data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("令牌数据序列化失败", e);
        }
    }

    /**
     * 令牌数据：值不存在为空；解不开、是 JSON null、缺账号 / 认证方式 / 签发秒、家族号与令牌自身嵌的不一致（bug 或数据损坏），
     * 一律按无效处理并告警，不让坏数据变成认证通过。
     */
    static Optional<TokenData> decode(String value, String familyId) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            TokenData data = JSON.readValue(value, TokenData.class);
            if (data == null || isEmpty(data.account()) || isEmpty(data.authType()) || data.createdAt() <= 0) {
                log.warn("令牌数据身份字段不全，按无效处理");
                return Optional.empty();
            }
            if (!familyId.equals(data.familyId())) {
                log.warn("令牌数据的家族号与令牌不一致，按无效处理 account={}", data.account());
                return Optional.empty();
            }
            return Optional.of(new TokenData(data.account(), data.authType(), data.deviceId() == null ? "" : data.deviceId(),
                    data.createdAt(), data.familyId()));
        } catch (JsonProcessingException e) {
            // 不记原消息：它可能带上值的片段
            log.warn("令牌数据解不开，按无效处理: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 轮换记录：记录不存在为空；解不开、缺账号、家族号不符、新令牌不属于这个家族或类型不对（不该发生）按无效处理并告警。 */
    static Optional<RotationRecord> decodeRotation(String value, String familyId) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            RotationRecord rec = JSON.readValue(value, RotationRecord.class);
            if (rec == null || isEmpty(rec.account()) || !familyId.equals(rec.familyId())
                    || !tokenOfFamily(rec.accessToken(), familyId, Kind.ACCESS)
                    || !tokenOfFamily(rec.refreshToken(), familyId, Kind.REFRESH)) {
                log.warn("refresh 轮换记录损坏，按无效处理");
                return Optional.empty();
            }
            return Optional.of(rec);
        } catch (JsonProcessingException e) {
            // 不记原消息：它可能带上记录片段，即新令牌
            log.warn("refresh 轮换记录解不开，按无效处理: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * 轮换记录（键 {@link RedisKeys#loginRefreshRotated}，值是蛇形键 JSON，同基线 {@code rotationRecord}）：那次轮换换出的一对。
     * 新令牌以明文存放——它们本来就以明文出现在令牌键名里，这条记录不新增暴露面，且 TTL 不超过 {@link #MAX_REFRESH_REUSE_GRACE}。
     */
    record RotationRecord(String account, String familyId, String accessToken, String refreshToken, long accessExpire,
                          long refreshExpire, long rotatedAt) {
    }
}
