package com.game.login.auth;

import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单键、固定大小的口令失败状态；严格性限于同主且状态未丢失，不是不可回退的失败账本。 */
public final class RedisPasswordFailureQuota implements PasswordFailureQuota {
    private static final Logger log = LoggerFactory.getLogger(RedisPasswordFailureQuota.class);
    public static final Duration LEASE = Duration.ofSeconds(30);
    static final long REDIS_BUDGET_MS = 250;

    public record Policy(int limit, Duration window, Duration lock) {
        public Policy {
            Objects.requireNonNull(window, "失败窗口");
            Objects.requireNonNull(lock, "锁窗");
            if (limit < 1 || limit > 100 || window.compareTo(Duration.ofSeconds(1)) < 0
                    || window.compareTo(Duration.ofHours(1)) > 0 || lock.compareTo(Duration.ofSeconds(1)) < 0
                    || lock.compareTo(Duration.ofHours(1)) > 0
                    || window.getNano() % 1_000_000 != 0 || lock.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("口令失败配额次数必须为1..100，失败窗口与锁窗必须为1s..1h的整毫秒");
            }
        }
        String fingerprint() { return "1:" + limit + ":" + window.toMillis() + ":" + lock.toMillis() + ":30000"; }
    }

    @FunctionalInterface
    interface ScriptCall {
        CompletionStage<List<Object>> eval(String script, String key, String... arguments);
    }

    // 先验证状态和全部参数，再执行写入。Lua 出错不回滚，不能写一半后才发现坏格式。
    private static final String STATE = """
            local kind = redis.call('TYPE', KEYS[1]).ok
            local cfg = ARGV[1]
            local limit = tonumber(ARGV[2])
            local window = tonumber(ARGV[3])
            local lock = tonumber(ARGV[4])
            local token = ARGV[5]
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local failure, windowUntil, lockUntil, current, leaseUntil, last, outcome, receiptUntil = 0, 0, 0, '', 0, '', '', 0
            local function integer(v)
              local n = tonumber(v)
              return n and n >= 0 and n <= 9007199254740991 and n == math.floor(n)
            end
            local function validToken(t)
              return #t == 36 and string.sub(t,9,9) == '-' and string.sub(t,14,14) == '-'
                and string.sub(t,19,19) == '-' and string.sub(t,24,24) == '-'
                and #string.gsub(t,'%-','') == 32 and string.match(string.gsub(t,'%-',''), '^[0-9a-f]+$')
            end
            if kind ~= 'none' then
              if kind ~= 'hash' or redis.call('HLEN', KEYS[1]) ~= 10 or redis.call('PTTL', KEYS[1]) < 0 then return {'bad'} end
              local s = redis.call('HMGET', KEYS[1], 'v','cfg','failure','windowUntil','lockUntil','currentToken','leaseUntil','lastToken','lastOutcome','receiptUntil')
              for i = 1, 10 do if not s[i] then return {'bad'} end end
              if s[1] ~= '1' then return {'bad'} end
              if s[2] ~= cfg then return {'config'} end
              if not integer(s[3]) or not integer(s[4]) or not integer(s[5]) or not integer(s[7]) or not integer(s[10]) then return {'bad'} end
              failure, windowUntil, lockUntil = tonumber(s[3]), tonumber(s[4]), tonumber(s[5])
              current, leaseUntil, last, outcome, receiptUntil = s[6], tonumber(s[7]), s[8], s[9], tonumber(s[10])
              if failure >= limit or (failure == 0 and windowUntil ~= 0) or (failure > 0 and windowUntil == 0)
                or (failure > 0 and lockUntil ~= 0)
                or (current == '' and leaseUntil ~= 0) or (current ~= '' and leaseUntil == 0)
                or (current ~= '' and not validToken(current)) or (last ~= '' and not validToken(last))
                or (current ~= '' and last ~= '')
                or windowUntil > now + window or lockUntil > now + lock or leaseUntil > now + 30000 or receiptUntil > now + 30000
                or (last == '' and (outcome ~= '' or receiptUntil ~= 0))
                or (last ~= '' and (receiptUntil == 0 or (outcome ~= 'success' and outcome ~= 'mismatch' and outcome ~= 'abort'))) then return {'bad'} end
            end
            local function save()
              redis.call('HSET', KEYS[1], 'v','1','cfg',cfg,'failure',failure,'windowUntil',windowUntil,'lockUntil',lockUntil,
                'currentToken',current,'leaseUntil',leaseUntil,'lastToken',last,'lastOutcome',outcome,'receiptUntil',receiptUntil)
              redis.call('PEXPIREAT', KEYS[1], math.max(windowUntil, lockUntil, leaseUntil, receiptUntil))
            end
            """;

    static final String BEGIN = STATE + """
            if lockUntil > now then return {'locked'} end
            if current ~= '' then
              if current == token then
                if leaseUntil > now then return {'allowed'} else return {'obsolete'} end
              elseif leaseUntil > now then return {'busy'} end
            end
            if last == token then return {'obsolete'} end
            if windowUntil <= now then failure, windowUntil = 0, 0 end
            if lockUntil <= now then lockUntil = 0 end
            current, leaseUntil = token, now + 30000
            last, outcome, receiptUntil = '', '', 0
            save()
            return {'allowed'}
            """;

    static final String FINISH = STATE + """
            local requested = ARGV[6]
            if requested ~= 'success' and requested ~= 'mismatch' and requested ~= 'abort' then return {'bad'} end
            if kind == 'none' then return {'stale'} end
            if current == '' and last == token and receiptUntil > now then
              if outcome == requested then return {'confirmed'} end
              return {'opposite'}
            end
            if current ~= token or leaseUntil <= now then return {'stale'} end
            if windowUntil <= now then failure, windowUntil = 0, 0 end
            if requested == 'success' then
              failure, windowUntil, lockUntil = 0, 0, 0
            elseif requested == 'mismatch' then
              if failure == 0 then windowUntil = now + window end
              failure = failure + 1
              if failure >= limit then failure, windowUntil, lockUntil = 0, 0, now + lock end
            end
            last, outcome, receiptUntil = token, requested, leaseUntil
            current, leaseUntil = '', 0
            save()
            return {'confirmed'}
            """;

    private final RedisKeys keys;
    private final Policy policy;
    private final ScriptCall call;
    private final Counter beginAllowed;
    private final Counter beginRejected;
    private final Counter beginError;
    private final Counter beginLocked;
    private final Counter beginInFlight;
    private final Counter beginCancelled;
    private final Counter finishConfirmed;
    private final Counter finishRejected;
    private final Counter finishError;
    private final Counter finishObsolete;
    private final Counter finishCancelled;
    private final AtomicLong lastWarning = new AtomicLong();

    public RedisPasswordFailureQuota(RedissonClient redis, RedisKeys keys, Policy policy, MeterRegistry meters) {
        this(keys, policy, scriptCall(redis), meters);
    }

    private static ScriptCall scriptCall(RedissonClient redis) {
        // 只约束此配额的脚本视图，不改共享客户端。结果未知不能自动重发后重新预留旧 token。
        RScript script = Objects.requireNonNull(redis, "Redis客户端").getScript(OptionalOptions.defaults()
                .codec(StringCodec.INSTANCE).retryAttempts(0).timeout(Duration.ofMillis(REDIS_BUDGET_MS)));
        return (source, key, args) -> script.<List<Object>>evalAsync(
                RScript.Mode.READ_WRITE, source, RScript.ReturnType.MULTI, List.of(key), (Object[]) args);
    }

    RedisPasswordFailureQuota(RedisKeys keys, Policy policy, ScriptCall call, MeterRegistry meters) {
        this.keys = Objects.requireNonNull(keys);
        this.policy = Objects.requireNonNull(policy);
        this.call = Objects.requireNonNull(call);
        beginAllowed = meters.counter("game.login.password.quota", "operation", "begin", "result", "allowed");
        beginRejected = meters.counter("game.login.password.quota", "operation", "begin", "result", "rejected");
        beginError = meters.counter("game.login.password.quota", "operation", "begin", "result", "error");
        beginLocked = meters.counter("game.login.password.quota", "operation", "begin", "result", "locked");
        beginInFlight = meters.counter("game.login.password.quota", "operation", "begin", "result", "inflight");
        beginCancelled = meters.counter("game.login.password.quota", "operation", "begin", "result", "cancelled");
        finishConfirmed = meters.counter("game.login.password.quota", "operation", "finish", "result", "confirmed");
        finishRejected = meters.counter("game.login.password.quota", "operation", "finish", "result", "rejected");
        finishError = meters.counter("game.login.password.quota", "operation", "finish", "result", "error");
        finishObsolete = meters.counter("game.login.password.quota", "operation", "finish", "result", "obsolete");
        finishCancelled = meters.counter("game.login.password.quota", "operation", "finish", "result", "cancelled");
    }

    @Override
    public Optional<Attempt> begin(Domain domain, String identity) {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(identity);
        if (Thread.currentThread().isInterrupted()) { beginCancelled.increment(); return Optional.empty(); }
        String key = keys.loginPasswordFailure(domain == Domain.KNOWN ? "known" : "unknown", digest(identity));
        Attempt attempt = new Attempt(key, UUID.randomUUID().toString(), System.nanoTime() + LEASE.toNanos());
        String result = execute(BEGIN, attempt, null);
        if ("allowed".equals(result) && !Thread.currentThread().isInterrupted() && !attempt.expired()) {
            beginAllowed.increment();
            return Optional.of(attempt);
        }
        (Thread.currentThread().isInterrupted() ? beginCancelled
                : result == null || "bad".equals(result) || "config".equals(result) ? beginError : "locked".equals(result) ? beginLocked
                : "busy".equals(result) ? beginInFlight : beginRejected).increment();
        return Optional.empty();
    }

    @Override
    public boolean finish(Attempt attempt, Outcome outcome) {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(outcome);
        if (attempt.expired()) { finishObsolete.increment(); return false; }
        if (Thread.currentThread().isInterrupted()) { finishCancelled.increment(); return false; }
        String result = execute(FINISH, attempt, outcome);
        boolean confirmed = "confirmed".equals(result) && !Thread.currentThread().isInterrupted() && !attempt.expired();
        (Thread.currentThread().isInterrupted() ? finishCancelled
                : result == null || "bad".equals(result) || "config".equals(result) ? finishError
                : confirmed ? finishConfirmed : "stale".equals(result) ? finishObsolete : finishRejected).increment();
        return confirmed;
    }

    private String execute(String script, Attempt attempt, Outcome outcome) {
        CompletionStage<List<Object>> pending = null;
        try {
            String[] args = outcome == null
                    ? new String[]{policy.fingerprint(), "" + policy.limit(), "" + policy.window().toMillis(), "" + policy.lock().toMillis(), attempt.token()}
                    : new String[]{policy.fingerprint(), "" + policy.limit(), "" + policy.window().toMillis(), "" + policy.lock().toMillis(), attempt.token(), outcome.name().toLowerCase(java.util.Locale.ROOT)};
            pending = call.eval(script, attempt.key(), args);
            List<Object> response = pending.toCompletableFuture().get(REDIS_BUDGET_MS, TimeUnit.MILLISECONDS);
            if (response == null || response.size() != 1 || !(response.getFirst() instanceof String result)) {
                warn("invalid_result"); return null;
            }
            if ("bad".equals(result) || "config".equals(result)) {
                warn(result);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            warn("redis_error");
            return null;
        } finally {
            if (pending != null && !pending.toCompletableFuture().isDone()) pending.toCompletableFuture().cancel(false);
        }
    }

    private void warn(String reason) {
        long now = System.nanoTime();
        long last = lastWarning.get();
        if ((last == 0 || now - last >= TimeUnit.MINUTES.toNanos(1)) && lastWarning.compareAndSet(last, now)) {
            log.warn("口令失败配额不可确认，拒绝本次认证 reason={}", reason);
        }
    }

    private static String digest(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK缺少SHA-256", e);
        }
    }
}
