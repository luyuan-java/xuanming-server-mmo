package com.game.login.handler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * 登录服进场会话提交 CAS 冲突有界重做器（对齐 gate-drain-session-migration 11-12 收尾批第 7 条）：
 * <p>gate 排空迁移时旧 gate 的断线上报可能落在新连接 EnterGame「读会话」与「写会话」之间。
 * 区分「读后被改（CAS 版本冲突）」与其他拒绝：仅前者重读、重判、重铸登录代次并提交，有界至多 {@value #MAX_ATTEMPTS} 次；
 * 短重连分支冲突与其他错误绝不重做。
 */
public final class SessionCommitRetrier {

    public static final int MAX_ATTEMPTS = 3;

    public enum CommitStatus {
        COMMITTED,
        STALE_VERSION_CONFLICT,
        SHORT_RECONNECT_CONFLICT,
        REJECTED_OTHER
    }

    public record AttemptOutcome<T>(CommitStatus status, T value) {
    }

    @FunctionalInterface
    public interface CommitAttempt<T> {
        AttemptOutcome<T> run(int attemptIndex);
    }

    private final Counter retryCounter;

    public SessionCommitRetrier(MeterRegistry meters) {
        Objects.requireNonNull(meters, "meters");
        this.retryCounter = Counter.builder("game.login.enter.session_commit_retries")
                .description("EnterGame 会话写入遇 CAS 版本落后的重做次数")
                .register(meters);
    }

    public <T> AttemptOutcome<T> execute(CommitAttempt<T> attempt) {
        AttemptOutcome<T> last = null;
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            last = attempt.run(i);
            if (last.status() != CommitStatus.STALE_VERSION_CONFLICT) {
                return last;
            }
            if (i < MAX_ATTEMPTS) {
                retryCounter.increment();
            }
        }
        return last;
    }

    public double retryCount() {
        return retryCounter.count();
    }
}
