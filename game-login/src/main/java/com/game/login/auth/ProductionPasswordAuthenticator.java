package com.game.login.auth;

import com.game.common.text.GoSpaces;
import com.game.player.store.AccountPassword;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生产口令认证（同 mmorpg {@code ProductionPasswordProvider}）：只读权威表 {@code account.password_hash} 里的 Argon2id 哈希；
 * 登录不注册、不改密（存量账号的哈希由 {@code PasswordAdmin} 工具写一次）。
 * <ul>
 *   <li>账号形状：合法 UTF-16、非空、首尾无空白（Go TrimSpace 口径）、不超过 64 个码点（Java 版账号列 VARCHAR(64)；基线 191）；
 *       口令 1–1024 字节；不合法的输入、不存在的账号、没有哈希的账号都照样跑一次同成本的 dummy KDF，压低账号枚举的时序信号；</li>
 *   <li>KDF 每次占 64 MiB：并发槽默认 2、上限 8，等槽默认 500 ms、上限 5 s，等不到按认证失败（防止被打 OOM）；
 *       同时在口令认证里的线程至多 3 × 并发槽，再来的立即失败（不让口令请求占满共用的 login 工作线程池）；</li>
 *   <li>返回库里的账号（规范值），不回显客户端输入；所有失败对客户端都是 2000（调用方映射），原因只进日志（不带口令）。</li>
 * </ul>
 * 线程安全；阻塞，只在 login 工作线程上调用。
 */
public final class ProductionPasswordAuthenticator implements PasswordAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ProductionPasswordAuthenticator.class);

    public static final int DEFAULT_KDF_CONCURRENCY = 2;
    public static final int MAX_KDF_CONCURRENCY = 8;
    public static final Duration DEFAULT_KDF_WAIT = Duration.ofMillis(500);
    public static final Duration MAX_KDF_WAIT = Duration.ofSeconds(5);
    static final int ACCOUNT_MAX_CODE_POINTS = DevPasswordRule.MAX_ACCOUNT_CHARS;

    /** 门的容量 = 并发槽 × 这个数（算 KDF 的 + 等槽的）；缺省 2 槽时至多 6 个工作线程在口令认证里。 */
    static final int ADMISSION_PER_SLOT = 3;

    private final Function<String, Optional<AccountPassword>> store;
    private final Semaphore admission;
    private final Semaphore kdfSlots;
    private final AtomicLong capacityMisses = new AtomicLong();
    private final AtomicLong lastCapacityWarn = new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(1));
    private final Duration kdfWait;
    private final String dummyHash;
    private final KdfVerifier verifier;
    private final PasswordFailureQuota quota;
    private enum Verification { MATCH, MISMATCH, ABORT }

    /** KDF 校验（测试可换成快的实现）。 */
    @FunctionalInterface
    interface KdfVerifier {
        boolean verify(String password, String encoded);
    }

    /**
     * @param store       账号 → 口令记录（{@code PlayerStore::findAccountPassword}）；库故障抛异常
     * @param concurrency KDF 并发槽（1–8）
     * @param kdfWait     等槽上限（(0, 5 s]）
     */
    public ProductionPasswordAuthenticator(Function<String, Optional<AccountPassword>> store, int concurrency,
                                           Duration kdfWait, PasswordFailureQuota quota) {
        this(store, concurrency, kdfWait, Objects.requireNonNull(quota, "生产口令失败配额"), Argon2id::verify,
                Argon2id.hash("invalid-account-dummy-password", new SecureRandom()));
    }

    ProductionPasswordAuthenticator(Function<String, Optional<AccountPassword>> store, int concurrency, Duration kdfWait,
                                    PasswordFailureQuota quota, KdfVerifier verifier, String dummyHash) {
        if (concurrency < 1 || concurrency > MAX_KDF_CONCURRENCY) {
            throw new IllegalArgumentException("KDF 并发槽须在 1–" + MAX_KDF_CONCURRENCY + " 之间: " + concurrency);
        }
        if (kdfWait.isNegative() || kdfWait.isZero() || kdfWait.compareTo(MAX_KDF_WAIT) > 0) {
            throw new IllegalArgumentException("KDF 等槽上限须在 (0, " + MAX_KDF_WAIT + "] 之间: " + kdfWait);
        }
        this.store = store;
        this.admission = new Semaphore(concurrency * ADMISSION_PER_SLOT);
        this.kdfSlots = new Semaphore(concurrency, true);
        this.kdfWait = kdfWait;
        this.verifier = verifier;
        this.dummyHash = dummyHash;
        this.quota = Objects.requireNonNull(quota, "生产口令失败配额");
    }

    @Override
    public Optional<String> authenticate(String account, String password) {
        if (!validAccount(account) || password == null || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > Argon2id.PASSWORD_MAX_BYTES) {
            verifyWithLimit("invalid-input-dummy-password", dummyHash, null);
            return Optional.empty();
        }
        Optional<AccountPassword> record;
        try {
            record = store.apply(account);
        } catch (RuntimeException e) {
            log.warn("口令库查询失败，按认证失败处理: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        boolean known = record.isPresent();
        String canonical = known ? record.get().account() : account;
        if (!validAccount(canonical) || Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }
        Optional<PasswordFailureQuota.Attempt> reserved;
        try {
            reserved = quota.begin(known ? PasswordFailureQuota.Domain.KNOWN : PasswordFailureQuota.Domain.UNKNOWN, canonical);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (reserved.isEmpty()) return Optional.empty();
        PasswordFailureQuota.Attempt attempt = reserved.get();
        boolean finishAttempted = false;
        try {
            String encoded = record.map(AccountPassword::passwordHash).orElse(null);
            boolean hasHash = encoded != null && !encoded.isEmpty();
            Verification verified = verifyWithLimit(password, hasHash ? encoded : dummyHash, attempt);
            if (verified == Verification.ABORT || (known && !hasHash)
                    || Thread.currentThread().isInterrupted() || attempt.expired()) return Optional.empty();
            PasswordFailureQuota.Outcome outcome = known && verified == Verification.MATCH
                    ? PasswordFailureQuota.Outcome.SUCCESS : PasswordFailureQuota.Outcome.MISMATCH;
            // 一旦发出完成，未知结果也不能再换成 abort；可能已经计错密或完成成功清理。
            finishAttempted = true;
            boolean confirmed = quota.finish(attempt, outcome);
            return confirmed && outcome == PasswordFailureQuota.Outcome.SUCCESS
                    && !Thread.currentThread().isInterrupted() && !attempt.expired()
                    ? Optional.of(canonical) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("口令认证依赖不可用，拒绝本次认证 reason={}", e.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            if (!finishAttempted) {
                try { quota.finish(attempt, PasswordFailureQuota.Outcome.ABORT); }
                catch (RuntimeException ignored) { /* 释放失败只占有限租期，不伪装错密。 */ }
            }
        }
    }

    /**
     * 占一个 KDF 槽跑一次校验。仅有效 PHC 的不匹配是 MISMATCH；坏哈希补 dummy 后仍是 ABORT。
     *
     * <p>门：同时在口令认证里（算 KDF + 等槽）的线程至多 {@link #ADMISSION_PER_SLOT} × 并发槽个，再来的不等、立即按容量不足失败。
     * login 工作线程池是全部登录请求共用的（建角 / 进游戏 / 令牌登录 / HTTP 登录），不设门的话一波不带凭据的口令请求
     * 就能让所有工作线程都停在等槽上（基线每个请求一个 goroutine，等槽只拖慢口令登录自己）。
     */
    private Verification verifyWithLimit(String password, String encoded, PasswordFailureQuota.Attempt attempt) {
        if (Thread.currentThread().isInterrupted() || (attempt != null && attempt.expired())) return Verification.ABORT;
        if (!admission.tryAcquire()) {
            capacityWarn("口令认证排队已满");
            return Verification.ABORT;
        }
        try {
            boolean acquired;
            try {
                acquired = kdfSlots.tryAcquire(kdfWait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Verification.ABORT;
            }
            if (!acquired) {
                capacityWarn("口令 KDF 并发槽 " + kdfWait.toMillis() + " ms 内等不到");
                return Verification.ABORT;
            }
            try {
                if (Thread.currentThread().isInterrupted() || (attempt != null && attempt.expired())) return Verification.ABORT;
                return verifier.verify(password, encoded) ? Verification.MATCH : Verification.MISMATCH;
            } catch (IllegalArgumentException e) {
                log.warn("库里的口令哈希不可用，按认证失败处理");
                verifier.verify(password, dummyHash);
                return Verification.ABORT;
            } finally {
                kdfSlots.release();
            }
        } finally {
            admission.release();
        }
    }

    /** 容量不足的告警每秒至多一条（被刷时不把日志打爆），带上这一秒里累计的次数。 */
    private void capacityWarn(String what) {
        capacityMisses.incrementAndGet();
        long now = System.nanoTime();
        long last = lastCapacityWarn.get();
        if (now - last >= TimeUnit.SECONDS.toNanos(1) && lastCapacityWarn.compareAndSet(last, now)) {
            log.warn("{}，按认证失败处理（自上次告警以来 {} 次）", what, capacityMisses.getAndSet(0));
        }
    }

    /** 同基线 ValidatePasswordAccount（码点上限按 Java 版账号列）：运行时与管理工具共用。 */
    public static boolean validAccount(String account) {
        if (account == null || account.isEmpty() || !account.equals(GoSpaces.trim(account))) {
            return false;
        }
        for (int i = 0; i < account.length(); i++) {
            char c = account.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= account.length() || !Character.isLowSurrogate(account.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return account.codePointCount(0, account.length()) <= ACCOUNT_MAX_CODE_POINTS;
    }
}
