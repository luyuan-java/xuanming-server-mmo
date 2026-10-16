package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.login.auth.Argon2id;
import com.game.login.auth.PasswordAuthenticator;
import com.game.login.auth.LoginAuthenticator;
import com.game.login.auth.ExternalAuthProviders;
import com.game.login.auth.PasswordFailureQuota;
import com.game.login.auth.PasswordFailureQuota.Domain;
import com.game.login.auth.PasswordFailureQuota.Outcome;
import com.game.login.auth.RedisPasswordFailureQuota;
import com.game.login.token.LoginTokens;
import com.game.proto.login.LoginRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.game.player.store.AccountPassword;
import com.game.player.store.PlayerStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/** 生产认证工厂的真实跨客户端回归；仅清理本测试随机命名空间，禁止清共享库。 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class PasswordFailureQuotaIntegrationTest {
    private final RedisKeys keys = new RedisKeys(GameNamespace.of("pwit" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
    private RedissonClient first;
    private RedissonClient second;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void open() {
        first = client();
        second = client();
    }

    private RedissonClient client() {
        String address = System.getProperty("game.it.redis");
        if (!address.equals("redis://127.0.0.1:13379") && !address.equals("redis://localhost:13379")) {
            throw new IllegalArgumentException("口令配额测试只允许本机隔离Redis13379");
        }
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress(address).setDatabase(9)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        return Redisson.create(config);
    }

    @AfterEach
    void close() {
        try {
            if (first != null) {
                var owned = new ArrayList<String>();
                first.getKeys().getKeysByPattern(keys.prefix() + "login:password-failure-v1:*").forEach(owned::add);
                if (!owned.isEmpty()) first.getKeys().delete(owned.toArray(String[]::new));
            }
        } finally {
            try { if (second != null) second.shutdown(); }
            finally {
                try { if (first != null) first.shutdown(); }
                finally { meters.close(); }
            }
        }
    }

    @Test
    void 实际生产工厂跨副本按权威账号累计五次错密后拒绝正确密码() {
        PlayerStore store = mock(PlayerStore.class);
        String encoded = Argon2id.hash("correct", new SecureRandom());
        when(store.findAccountPassword(anyString())).thenReturn(Optional.of(new AccountPassword("Canonical", encoded)));
        LoginProperties mode = new LoginProperties("prod", null, null, null, null, null, null, null, null, null, null, null);
        var properties = new LoginAuthProperties.PasswordAuth(true, null, null);
        var auth = new LoginAuthProperties(properties, null, null, null, null);
        var configuration = new LoginConfiguration();
        try (var providers = new ExternalAuthProviders()) {
            LoginAuthenticator a = configuration.loginAuthenticator(mode, auth, mock(LoginTokens.class), store, providers,
                    configuration.passwordFailureQuota(first, keys, auth, meters), "");
            LoginAuthenticator b = configuration.loginAuthenticator(mode, auth, mock(LoginTokens.class), store, providers,
                    configuration.passwordFailureQuota(second, keys, auth, meters), "");
            for (int i = 0; i < 5; i++) assertThat(a.authenticate(LoginRequest.newBuilder().setAccount("alias").setPassword("wrong").build())).isEmpty();
            assertThat(b.authenticate(LoginRequest.newBuilder().setAccount("anotherAlias").setPassword("correct").build()))
                    .as("同一权威账号跨副本共享锁窗").isEmpty();
            assertThat(meters.get("game.login.password.quota").tag("operation", "begin").tag("result", "locked").counter().count()).isEqualTo(1);
        }
    }

    private RedisPasswordFailureQuota quota(RedissonClient client, int limit) {
        return new RedisPasswordFailureQuota(client, keys,
                new RedisPasswordFailureQuota.Policy(limit, Duration.ofMinutes(15), Duration.ofMinutes(5)), meters);
    }

    @Test
    void 同账号唯一在途_跨账号和未知域并行_abort与完成幂等不越过新Begin() {
        var a = quota(first, 3);
        var b = quota(second, 3);
        var pending = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        var other = b.begin(Domain.KNOWN, "Other").orElseThrow();
        var unknown = b.begin(Domain.UNKNOWN, "Canonical").orElseThrow();
        assertThat(unknown.key()).isNotEqualTo(pending.key());
        assertThat(pending.key()).doesNotContain("Canonical").matches(".*\\{known:[0-9a-f]{64}}$");
        assertThat(b.begin(Domain.KNOWN, "Canonical")).isEmpty();
        assertThat(a.finish(pending, Outcome.ABORT)).isTrue();
        assertThat(a.finish(pending, Outcome.ABORT)).isTrue();
        assertThat(a.finish(pending, Outcome.SUCCESS)).isFalse();
        var newer = b.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(a.finish(pending, Outcome.SUCCESS)).isFalse();
        assertThat(a.finish(pending, Outcome.ABORT)).isFalse();
        assertThat(b.finish(newer, Outcome.SUCCESS)).isTrue();
        assertThat(b.finish(newer, Outcome.SUCCESS)).isTrue();
        var latest = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(b.finish(newer, Outcome.SUCCESS)).isFalse();
        assertThat(a.finish(latest, Outcome.ABORT)).isTrue();
        assertThat(b.finish(other, Outcome.ABORT)).isTrue();
        assertThat(b.finish(unknown, Outcome.MISMATCH)).isTrue();
    }

    @Test
    void 失败窗固定_锁到期后重新累计_成功清除旧失败() {
        var a = quota(first, 3);
        var b = quota(second, 3);
        var one = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(a.finish(one, Outcome.MISMATCH)).isTrue();
        var state = first.<String, String>getMap(one.key(), StringCodec.INSTANCE);
        String originalWindow = state.get("windowUntil");
        var two = b.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(b.finish(two, Outcome.MISMATCH)).isTrue();
        assertThat(state.get("windowUntil")).isEqualTo(originalWindow);
        assertThat(a.finish(one, Outcome.MISMATCH)).isFalse();
        var success = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(a.finish(success, Outcome.SUCCESS)).isTrue();
        assertThat(state.get("failure")).isEqualTo("0");
        for (int i = 0; i < 3; i++) {
            var attempt = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
            assertThat(a.finish(attempt, Outcome.MISMATCH)).isTrue();
        }
        String lockedUntil = state.get("lockUntil");
        assertThat(state.get("failure")).isEqualTo("0");
        assertThat(b.begin(Domain.KNOWN, "Canonical")).isEmpty();
        assertThat(state.get("lockUntil")).isEqualTo(lockedUntil);
        expire(one.key(), "lockUntil");
        var afterLock = b.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(b.finish(afterLock, Outcome.MISMATCH)).isTrue();
        assertThat(state.get("failure")).isEqualTo("1");
        assertThat(state.get("lockUntil")).isEqualTo("0");
        expire(one.key(), "windowUntil");
        var afterWindow = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(a.finish(afterWindow, Outcome.MISMATCH)).isTrue();
        assertThat(state.get("failure")).isEqualTo("1");
        assertThat(state.remainTimeToLive()).isBetween(1L, 900000L);
    }

    @Test
    void 崩溃租期到期可恢复_旧完成和过期回执不能返回成功() throws ReflectiveOperationException {
        var a = quota(first, 3);
        var b = quota(second, 3);
        var crashed = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(b.begin(Domain.KNOWN, "Canonical")).isEmpty();
        expire(crashed.key(), "leaseUntil");
        assertThat(replayBegin(crashed, first.<String, String>getMap(crashed.key(), StringCodec.INSTANCE).get("cfg")))
                .containsExactly("obsolete");
        assertThat(a.finish(crashed, Outcome.MISMATCH)).isFalse();
        var recovered = b.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(a.finish(crashed, Outcome.SUCCESS)).isFalse();
        assertThat(b.finish(recovered, Outcome.SUCCESS)).isTrue();
        expire(recovered.key(), "receiptUntil");
        assertThat(b.finish(recovered, Outcome.SUCCESS)).isFalse();
        assertThat(first.<String, String>getMap(recovered.key(), StringCodec.INSTANCE).get("failure")).isEqualTo("0");
    }

    @Test
    void 混配和坏状态失败关闭不删除或重置() {
        var a = quota(first, 3);
        var pending = a.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(quota(second, 4).begin(Domain.KNOWN, "Canonical")).isEmpty();
        var state = first.<String, String>getMap(pending.key(), StringCodec.INSTANCE);
        assertThat(state.get("currentToken")).isEqualTo(pending.token());
        state.put("failure", "broken");
        assertThat(a.begin(Domain.KNOWN, "Canonical")).isEmpty();
        assertThat(a.finish(pending, Outcome.SUCCESS)).isFalse();
        assertThat(state.get("failure")).isEqualTo("broken");
        assertThat(meters.get("game.login.password.quota").tag("operation", "begin").tag("result", "error").counter().count()).isEqualTo(2);
    }

    @Test
    void 已完成token的Begin重放不能重新预留或再次计错密() throws ReflectiveOperationException {
        var quota = quota(first, 3);
        var completed = quota.begin(Domain.KNOWN, "Canonical").orElseThrow();
        assertThat(quota.finish(completed, Outcome.MISMATCH)).isTrue();
        var state = first.<String, String>getMap(completed.key(), StringCodec.INSTANCE);
        assertThat(replayBegin(completed, state.get("cfg"))).containsExactly("obsolete");
        assertThat(state.get("failure")).isEqualTo("1");
        assertThat(state.get("currentToken")).isEmpty();
        expire(completed.key(), "receiptUntil");
        assertThat(replayBegin(completed, state.get("cfg"))).containsExactly("obsolete");
        assertThat(quota.finish(completed, Outcome.MISMATCH)).isFalse();
        assertThat(state.get("failure")).isEqualTo("1");
    }

    /** 重放实际生产脚本的同一命令，模拟客户端发送结果未知后的重传，不开放公共旧 token API。 */
    private java.util.List<Object> replayBegin(PasswordFailureQuota.Attempt attempt, String fingerprint)
            throws ReflectiveOperationException {
        var field = RedisPasswordFailureQuota.class.getDeclaredField("BEGIN");
        field.setAccessible(true);
        return second.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, (String) field.get(null),
                RScript.ReturnType.MULTI, java.util.List.of(attempt.key()), fingerprint, "3", "900000", "300000", attempt.token());
    }

    /** 故障注入只改本测试的自有键，用 Redis TIME 造过期状态，不靠睡眠或本机墙钟。 */
    private void expire(String key, String field) {
        first.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "local t=redis.call('TIME'); return redis.call('HSET',KEYS[1],ARGV[1],tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)-1)",
                RScript.ReturnType.INTEGER, java.util.List.of(key), field);
    }
}
