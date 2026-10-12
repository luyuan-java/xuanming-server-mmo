package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.login.auth.LoginAuthenticator.Authenticated;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.login.token.TokenPair;
import com.game.proto.login.LoginRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void 外部认证按类型注册_账号来自外部_请求里的account忽略_出错与未注册都失败() {
        LoginAuthenticator auth = new LoginAuthenticator(null, tokens, Map.of(
                "wechat", token -> token.equals("good") ? Optional.of("wx_u1") : Optional.empty(),
                "qq", token -> {
                    throw new IllegalStateException("三方认证 HTTP 请求失败: ConnectException");
                }));
        LoginRequest wechat = LoginRequest.newBuilder().setAuthType("wechat").setAuthToken("good")
                .setAccount("robot_ignored").build();
        assertThat(auth.authenticate(wechat)).contains(new Authenticated("wx_u1", "wechat"));
        assertThat(new Authenticated("wx_u1", "wechat").issuesTokens()).isTrue();
        assertThat(auth.authenticate(wechat.toBuilder().setAuthToken("bad").build())).isEmpty();
        assertThat(auth.authenticate(LoginRequest.newBuilder().setAuthType("qq").setAuthToken("t").build())).isEmpty();
        assertThat(auth.authenticate(LoginRequest.newBuilder().setAuthType("satoken").setAuthToken("t").build()))
                .as("没注册").isEmpty();
        assertThat(auth.externalTypes()).containsExactlyInAnyOrder("wechat", "qq");
    }

    @Test
    void 认证出的账号放不进账号列按失败() {
        LoginAuthenticator auth = new LoginAuthenticator(null, tokens, Map.of("satoken", Optional::of));
        assertThat(auth.authenticate(LoginRequest.newBuilder().setAuthType("satoken").setAuthToken("a".repeat(64))
                .build())).isPresent();
        assertThat(auth.authenticate(LoginRequest.newBuilder().setAuthType("satoken").setAuthToken("a".repeat(65))
                .build())).isEmpty();
        assertThat(auth.authenticate(LoginRequest.newBuilder().setAuthType("satoken").setAuthToken(" lead")
                .build())).isEmpty();
    }

    @Test
    void 外部认证不能占用password与access_token() {
        assertThatThrownBy(() -> new LoginAuthenticator(null, tokens, Map.of("password", token -> Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAuthenticator(null, tokens, Map.of("access_token", token -> Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
