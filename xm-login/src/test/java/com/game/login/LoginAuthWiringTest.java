package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.game.login.auth.DevPasswordRule;
import com.game.login.auth.PasswordAuthenticator;
import com.game.login.auth.ProductionPasswordAuthenticator;
import com.game.player.store.PlayerStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 口令认证二选一（同基线 InitAuthProviders）：开发与生产互斥、都没开 = 关闭、dev 缺口令拒绝启动；认证配置缺省值。 */
class LoginAuthWiringTest {

    private final PlayerStore store = mock(PlayerStore.class);

    private static LoginProperties mode(String mode) {
        return new LoginProperties(mode, null, null, null, null, null, null, null, null, null, null);
    }

    private static LoginAuthProperties.PasswordAuth production(boolean enabled) {
        return new LoginAuthProperties.PasswordAuth(enabled, null, null);
    }

    @Test
    void 开发与生产口令互斥() {
        assertThatThrownBy(() -> LoginConfiguration.passwordAuthenticator(mode("dev"), production(true), store, "s"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能同时打开");
    }

    @Test
    void 各模式选出的口令认证() {
        PasswordAuthenticator prod = LoginConfiguration.passwordAuthenticator(mode("prod"), production(true), store, "");
        assertThat(prod).isInstanceOf(ProductionPasswordAuthenticator.class);
        assertThat(LoginConfiguration.passwordAuthenticator(mode("prod"), production(false), store, "s")).isNull();
        assertThat(LoginConfiguration.passwordAuthenticator(mode("dev"), production(false), store, "s"))
                .isInstanceOf(DevPasswordRule.class);
        assertThatThrownBy(() -> LoginConfiguration.passwordAuthenticator(mode("dev"), production(false), store, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("XM_LOGIN_DEV_PASSWORD");
    }

    @Test
    void 认证配置缺省值() {
        LoginAuthProperties props = new LoginAuthProperties(null, null, null, null, null);
        assertThat(props.password().enabled()).isFalse();
        assertThat(props.password().kdfConcurrency()).isEqualTo(2);
        assertThat(props.password().kdfWait()).isEqualTo(Duration.ofMillis(500));
        LoginAuthProperties.SaToken satoken = new LoginAuthProperties.SaToken("redis://127.0.0.1:6379", null, null, null);
        assertThat(satoken.tokenName()).isEqualTo("satoken");
        assertThat(satoken.loginType()).isEqualTo("login");
        assertThat(satoken.database()).isZero();
        assertThatThrownBy(() -> new LoginAuthProperties.SaToken(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
