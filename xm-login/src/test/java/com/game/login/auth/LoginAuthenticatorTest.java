package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.login.auth.LoginAuthenticator.Authenticated;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.login.token.TokenPair;
import com.game.proto.login.LoginRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginAuthenticatorTest {

    private static final String SECRET = "unit-test-shared-secret";
    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final LoginAuthenticator dev =
            LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("robot_", "dev_")), tokens);

    private static LoginRequest request(String authType, String account, String password) {
        return LoginRequest.newBuilder().setAuthType(authType).setAccount(account).setPassword(password).build();
    }

    private static LoginRequest accessToken(String token, String account) {
        return LoginRequest.newBuilder().setAuthType("access_token").setAuthToken(token).setAccount(account).build();
    }

    @Test
    void 空auth_type与password都走口令认证_归一成password() {
        assertThat(dev.authenticate(request("", "robot_0001", SECRET)))
                .contains(new Authenticated("robot_0001", "password"));
        assertThat(dev.authenticate(request("password", "robot_0001", SECRET)))
                .contains(new Authenticated("robot_0001", "password"));
        assertThat(new Authenticated("robot_0001", "password").issuesTokens()).isTrue();
    }

    @Test
    void 口令认证失败返回空() {
        assertThat(dev.authenticate(request("", "robot_0001", "bad"))).isEmpty();
    }

    @Test
    void access_token认证_账号取令牌里的_忽略请求里的account_不再签令牌() {
        TokenPair pair = tokens.issue("robot_0001", "password", "");
        Authenticated authenticated = dev.authenticate(accessToken(pair.accessToken(), "robot_9999")).orElseThrow();
        assertThat(authenticated).isEqualTo(new Authenticated("robot_0001", "access_token"));
        assertThat(authenticated.issuesTokens()).isFalse();
    }

    @Test
    void access_token无效_空_拿refresh冒充_存储故障都失败() {
        TokenPair pair = tokens.issue("robot_0001", "password", "");
        assertThat(dev.authenticate(accessToken("nope", "robot_0001"))).isEmpty();
        assertThat(dev.authenticate(accessToken("", "robot_0001"))).isEmpty();
        assertThat(dev.authenticate(accessToken(pair.refreshToken(), "robot_0001"))).isEmpty();
        tokens.broken = true;
        assertThat(dev.authenticate(accessToken(pair.accessToken(), "robot_0001"))).isEmpty();
    }

    @Test
    void 其它认证类型本批不支持() {
        assertThat(dev.authenticate(request("satoken", "robot_0001", SECRET))).isEmpty();
        assertThat(dev.authenticate(request("wechat", "robot_0001", SECRET))).isEmpty();
        // auth_type 精确匹配，大小写不同即是未知类型。
        assertThat(dev.authenticate(request("PASSWORD", "robot_0001", SECRET))).isEmpty();
        assertThat(dev.authenticate(request("ACCESS_TOKEN", "robot_0001", SECRET))).isEmpty();
    }

    @Test
    void 口令认证关闭时口令一律失败_access_token照常可用() {
        LoginAuthenticator disabled = LoginAuthenticator.passwordDisabled(tokens);
        assertThat(disabled.passwordEnabled()).isFalse();
        assertThat(disabled.authenticate(request("", "robot_0001", SECRET))).isEmpty();
        TokenPair pair = tokens.issue("robot_0001", "wechat", "");
        assertThat(disabled.authenticate(accessToken(pair.accessToken(), ""))).isPresent();
    }
}
