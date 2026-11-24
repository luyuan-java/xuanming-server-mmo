package com.game.login.compliance;

import com.game.common.compliance.AntiAddictionRules;
import com.game.player.store.PlayerStore;
import com.game.table.LoginErrorTip;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏方自有实名认证服务（对齐 Go {@code go/login/internal/logic/pkg/realname/realname.go}）：
 * 本地 GB 11643 校验 → 全服 NPPA 配额 → 账号每日次数限制 → 调 NPPA Check → 原子一次性写入 {@code account.real_name_pi}。
 * 姓名与身份证号仅在 {@link #submit} 内短暂驻留，绝不落库、绝不进日志。
 */
public final class RealNameService {

    private static final Logger log = LoggerFactory.getLogger(RealNameService.class);
    static final Duration PENDING_TTL = Duration.ofHours(48);
    static final Duration ATTEMPTS_TTL = Duration.ofHours(25);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 账号待查状态与每日尝试计数存储（生产为 Redis，单测可用内存替身）。 */
    public interface StateStore {
        long incrementDailyAttempts(String account, String yyyyMmDd, Duration ttl);
        void rememberPendingAi(String account, String ai, Duration ttl);
        String readPendingAi(String account);
        void forgetPendingAi(String account);
    }

    public static final class RedisStateStore implements StateStore {
        private static final String COUNT_SCRIPT = """
                local n = redis.call('INCR', KEYS[1])
                if n == 1 then
                  redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return n
                """;
        private final RedissonClient redis;
        private final NppaRedisKeys keys;

        public RedisStateStore(RedissonClient redis, NppaRedisKeys keys) {
            this.redis = Objects.requireNonNull(redis, "redis");
            this.keys = Objects.requireNonNull(keys, "keys");
        }

        @Override
        public long incrementDailyAttempts(String account, String yyyyMmDd, Duration ttl) {
            Long n = redis.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE, COUNT_SCRIPT, RScript.ReturnType.INTEGER,
                    List.of(keys.realNameAttempts(account, yyyyMmDd)),
                    Long.toString(ttl.toSeconds()));
            return n == null ? 0L : n;
        }

        @Override
        public void rememberPendingAi(String account, String ai, Duration ttl) {
            redis.getBucket(keys.realNamePending(account), StringCodec.INSTANCE).set(ai, ttl);
        }

        @Override
        public String readPendingAi(String account) {
            Object v = redis.getBucket(keys.realNamePending(account), StringCodec.INSTANCE).get();
            return v == null ? null : v.toString();
        }

        @Override
        public void forgetPendingAi(String account) {
            redis.getBucket(keys.realNamePending(account), StringCodec.INSTANCE).delete();
        }
    }

    public record SubmitOutcome(String pi, int tipId) {
        public boolean ok() {
            return tipId == 0;
        }
    }

    private final StateStore stateStore;
    private final NppaClient.Verifier verifier;
    private final PlayerStore playerStore;
    private final NppaQuota quota;
    private final int dailyLimit;
    private final Clock clock;

    public RealNameService(StateStore stateStore, NppaClient.Verifier verifier, PlayerStore playerStore,
                           NppaQuota quota, int dailyLimit, Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.playerStore = Objects.requireNonNull(playerStore, "playerStore");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.dailyLimit = Math.max(1, dailyLimit);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** 提交实名。成功返回库里的 {@code pi} 与 0；已绑定别的实名时返回库里的旧 {@code pi} 与 2046。 */
    public SubmitOutcome submit(String account, String rawName, String rawIdNumber) {
        String name = rawName == null ? "" : rawName.trim();
        String idNumber = AntiAddictionRules.normalizeIdNumber(rawIdNumber);
        var now = clock.instant();
        if (!AntiAddictionRules.validName(name) || !AntiAddictionRules.validIdNumber(idNumber, now)) {
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameInvalid_VALUE);
        }
        if (!allowQuota(NppaQuota.CHECK_QUOTA)) {
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE);
        }
        String ymd = now.atZone(AntiAddictionRules.BEIJING).toLocalDate().format(YMD);
        long attempts;
        try {
            attempts = stateStore.incrementDailyAttempts(account, ymd, ATTEMPTS_TTL);
        } catch (RuntimeException e) {
            log.error("[real-name] count submit attempt failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE);
        }
        if (attempts > dailyLimit) {
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameTooManyAttempts_VALUE);
        }
        String ai = NppaClient.newId();
        NppaClient.AuthResult result;
        try {
            result = verifier.check(ai, name, idNumber);
        } catch (NppaClient.NppaException e) {
            if (e.kind() == NppaClient.ErrorKind.INVALID_IDENTITY) {
                return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameInvalid_VALUE);
            }
            log.error("[real-name] nppa check failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getMessage());
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE);
        }
        return switch (result.status()) {
            case OK -> bind(account, result.pi());
            case PENDING -> {
                try {
                    stateStore.rememberPendingAi(account, ai, PENDING_TTL);
                } catch (RuntimeException e) {
                    log.error("[real-name] remember pending ai failed account={} err={}",
                            AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
                }
                yield new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNamePending_VALUE);
            }
            case FAILED -> new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameInvalid_VALUE);
        };
    }

    /** 登录时回查认证中的提交：通过即原子绑定并清除 pending；未决或查询故障返回空串，待下次登录再查。 */
    public String resolvePending(String account) {
        String ai;
        try {
            ai = stateStore.readPendingAi(account);
        } catch (RuntimeException e) {
            log.error("[real-name] read pending ai failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
            return "";
        }
        if (ai == null || ai.isEmpty()) {
            return "";
        }
        if (!allowQuota(NppaQuota.QUERY_QUOTA)) {
            return "";
        }
        NppaClient.AuthResult result;
        try {
            result = verifier.query(ai);
        } catch (NppaClient.NppaException e) {
            if (e.kind() == NppaClient.ErrorKind.NO_RECORD) {
                forgetPendingQuietly(account);
                return "";
            }
            log.error("[real-name] nppa query failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getMessage());
            return "";
        }
        return switch (result.status()) {
            case PENDING -> "";
            case OK -> {
                SubmitOutcome bound = bind(account, result.pi());
                if (bound.tipId() == LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE) {
                    yield "";
                }
                forgetPendingQuietly(account);
                yield bound.pi();
            }
            case FAILED -> {
                forgetPendingQuietly(account);
                yield "";
            }
        };
    }

    public void forgetPendingQuietly(String account) {
        try {
            stateStore.forgetPendingAi(account);
        } catch (RuntimeException e) {
            log.error("[real-name] clear pending ai failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
        }
    }

    private SubmitOutcome bind(String account, String pi) {
        String stored;
        try {
            stored = playerStore.setAccountRealName(account, pi);
        } catch (RuntimeException e) {
            log.error("[real-name] bind pi to account failed account={} err={}",
                    AccountDeletionService.maskAccount(account), e.getClass().getSimpleName());
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE);
        }
        if (stored.isEmpty()) {
            return new SubmitOutcome("", LoginErrorTip.login_error.kLoginRealNameServiceUnavailable_VALUE);
        }
        if (!stored.equals(pi)) {
            return new SubmitOutcome(stored, LoginErrorTip.login_error.kLoginRealNameAlreadyBound_VALUE);
        }
        return new SubmitOutcome(stored, 0);
    }

    private boolean allowQuota(NppaQuota.Rule rule) {
        try {
            return quota.allow(rule);
        } catch (RuntimeException e) {
            log.error("[real-name] quota unavailable api={} err={}", rule.name(), e.getClass().getSimpleName());
            return false;
        }
    }
}
