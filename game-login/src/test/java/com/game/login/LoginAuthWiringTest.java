package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.PasswordAuthenticator;
import com.game.login.auth.ProductionPasswordAuthenticator;
import com.game.login.auth.PasswordFailureQuota;
import com.game.player.store.PlayerStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 口令认证二选一（同基线 InitAuthProviders）：开发与生产互斥、都没开 = 关闭、dev 缺口令拒绝启动；认证配置缺省值。 */
class LoginAuthWiringTest {

    private final PlayerStore store = mock(PlayerStore.class);
    private final PasswordFailureQuota quota = mock(PasswordFailureQuota.class);

    private static LoginProperties mode(String mode) {
        return new LoginProperties(mode, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static LoginAuthProperties.PasswordAuth production(boolean enabled) {
        return new LoginAuthProperties.PasswordAuth(enabled, null, null);
    }

    @Test
    void 开发与生产口令互斥() {
        assertThatThrownBy(() -> LoginConfiguration.passwordAuthenticator(mode("dev"), production(true), store, "s", quota))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能同时打开");
    }

    @Test
    void 各模式选出的口令认证() {
        PasswordAuthenticator prod = LoginConfiguration.passwordAuthenticator(mode("prod"), production(true), store, "", quota);
        assertThat(prod).isInstanceOf(ProductionPasswordAuthenticator.class);
        assertThat(LoginConfiguration.passwordAuthenticator(mode("prod"), production(false), store, "s", quota)).isNull();
        assertThat(LoginConfiguration.passwordAuthenticator(mode("dev"), production(false), store, "s", quota))
                .isInstanceOf(DevPasswordRule.class);
        assertThatThrownBy(() -> LoginConfiguration.passwordAuthenticator(mode("dev"), production(false), store, "", quota))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GAME_LOGIN_DEV_PASSWORD");
    }

    @Test
    void 认证配置缺省值() {
        LoginAuthProperties props = new LoginAuthProperties(null, null, null, null, null);
        assertThat(props.password().enabled()).isFalse();
        assertThat(props.password().kdfConcurrency()).isEqualTo(2);
        assertThat(props.password().kdfWait()).isEqualTo(Duration.ofMillis(500));
        assertThat(props.password().failureLimit()).isEqualTo(5);
        assertThat(props.password().failureWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.password().failureLock()).isEqualTo(Duration.ofMinutes(5));
        LoginAuthProperties.SaToken satoken = new LoginAuthProperties.SaToken("redis://127.0.0.1:6379", null, null, null);
        assertThat(satoken.tokenName()).isEqualTo("satoken");
        assertThat(satoken.loginType()).isEqualTo("login");
        assertThat(satoken.database()).isZero();
        assertThatThrownBy(() -> new LoginAuthProperties.SaToken(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 真实配置绑定选择完整构造器并校验失败配额范围() {
        new ApplicationContextRunner().withUserConfiguration(BoundAuth.class)
                .withPropertyValues("game.login.auth.password.enabled=true", "game.login.auth.password.failure-limit=3",
                        "game.login.auth.password.failure-window=2s", "game.login.auth.password.failure-lock=3s")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    var password = context.getBean(LoginAuthProperties.class).password();
                    assertThat(password.enabled()).isTrue();
                    assertThat(password.failureLimit()).isEqualTo(3);
                    assertThat(password.failureWindow()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(password.failureLock()).isEqualTo(Duration.ofSeconds(3));
                });
        for (String bad : new String[]{"failure-limit=0", "failure-limit=-1", "failure-limit=101",
                "failure-window=999ms", "failure-lock=3600001ms", "failure-window=PT1.000000001S", "failure-lock=PT1.000000001S"}) {
            new ApplicationContextRunner().withUserConfiguration(BoundAuth.class)
                    .withPropertyValues("game.login.auth.password." + bad)
                    .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginAuthProperties.class)
    static class BoundAuth { }
}
