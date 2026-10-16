package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.login.auth.PasswordFailureQuota.Domain;
import com.game.login.auth.PasswordFailureQuota.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.OptionalParams;
import org.redisson.client.codec.StringCodec;

class RedisPasswordFailureQuotaTest {
    private final RedisKeys keys = new RedisKeys(GameNamespace.of("pwunit"));
    private final RedisPasswordFailureQuota.Policy policy = new RedisPasswordFailureQuota.Policy(5, Duration.ofMinutes(15), Duration.ofMinutes(5));

    @Test
    void Begin响应未知失败关闭且取消未完成等待() {
        var pending = new CompletableFuture<List<Object>>();
        var meters = new SimpleMeterRegistry();
        try {
            var quota = new RedisPasswordFailureQuota(keys, policy, (s, k, a) -> pending, meters);
            assertThat(quota.begin(Domain.KNOWN, "canonical")).isEmpty();
            assertThat(pending.isCancelled()).isTrue();
            assertThat(meters.get("game.login.password.quota").tag("operation", "begin").tag("result", "error").counter().count()).isEqualTo(1);
        } finally { meters.close(); }
    }

    @Test
    void Finish响应未知不重试不确认() {
        AtomicInteger calls = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        try {
            var quota = new RedisPasswordFailureQuota(keys, policy, (s, k, a) -> calls.incrementAndGet() == 1
                    ? CompletableFuture.completedFuture(List.of("allowed")) : CompletableFuture.failedFuture(new TimeoutException()), meters);
            var attempt = quota.begin(Domain.KNOWN, "canonical").orElseThrow();
            assertThat(quota.finish(attempt, Outcome.SUCCESS)).isFalse();
            assertThat(calls).hasValue(2);
        } finally { meters.close(); }
    }

    @Test
    void 观察到中断或本地租约过期不发命令() {
        AtomicInteger calls = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        try {
            var quota = new RedisPasswordFailureQuota(keys, policy, (s, k, a) -> {
                calls.incrementAndGet(); return CompletableFuture.completedFuture(List.of("confirmed"));
            }, meters);
            Thread.currentThread().interrupt();
            try { assertThat(quota.begin(Domain.KNOWN, "canonical")).isEmpty(); }
            finally { Thread.interrupted(); }
            assertThat(quota.finish(new PasswordFailureQuota.Attempt("key", "token", System.nanoTime() - 1), Outcome.SUCCESS)).isFalse();
            assertThat(calls).hasValue(0);
            assertThatThrownBy(() -> quota.begin(null, "canonical")).isInstanceOf(NullPointerException.class);
        } finally { meters.close(); }
    }

    @Test
    void 参数上下界与无关闭值() {
        for (int limit : new int[]{0, -1, 101}) {
            assertThatThrownBy(() -> new RedisPasswordFailureQuota.Policy(limit, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration duration : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(1).plusNanos(1), Duration.ofSeconds(1).plusNanos(1))) {
            assertThatThrownBy(() -> new RedisPasswordFailureQuota.Policy(1, duration, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RedisPasswordFailureQuota.Policy(1, Duration.ofSeconds(1), duration)).isInstanceOf(IllegalArgumentException.class);
        }
        new RedisPasswordFailureQuota.Policy(100, Duration.ofHours(1), Duration.ofHours(1));
    }

    @Test
    void 生产脚本视图关闭自动重试且网络等待为250毫秒() {
        var redis = mock(RedissonClient.class);
        when(redis.getScript(any(OptionalOptions.class))).thenReturn(mock(RScript.class));
        var meters = new SimpleMeterRegistry();
        try {
            new RedisPasswordFailureQuota(redis, keys, policy, meters);
            var captured = ArgumentCaptor.forClass(OptionalOptions.class);
            verify(redis).getScript(captured.capture());
            var options = (OptionalParams) captured.getValue();
            assertThat(options.getRetryAttempts()).isZero();
            assertThat(options.getTimeout()).isEqualTo(250);
            assertThat(options.getCodec()).isSameAs(StringCodec.INSTANCE);
        } finally { meters.close(); }
    }
}
