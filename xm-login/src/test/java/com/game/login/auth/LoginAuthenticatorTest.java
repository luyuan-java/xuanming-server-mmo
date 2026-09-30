package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.login.LoginRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginAuthenticatorTest {

    private static final String SECRET = "unit-test-shared-secret";
    private final LoginAuthenticator dev =
            LoginAuthenticator.withDevPassword(new DevPasswordRule(SECRET, List.of("robot_", "dev_")));

    private static LoginRequest request(String authType, String account, String password) {
        return LoginRequest.newBuilder().setAuthType(authType).setAccount(account).setPassword(password).build();
    }

    @Test
    void 空auth_type与password都走口令认证() {
        assertThat(dev.authenticate(request("", "robot_0001", SECRET))).contains("robot_0001");
        assertThat(dev.authenticate(request("password", "robot_0001", SECRET))).contains("robot_0001");
    }

    @Test
    void 口令认证失败返回空() {
        assertThat(dev.authenticate(request("", "robot_0001", "bad"))).isEmpty();
    }

    @Test
    void 其它认证类型本批不支持() {
        assertThat(dev.authenticate(request("access_token", "robot_0001", SECRET))).isEmpty();
        assertThat(dev.authenticate(request("satoken", "robot_0001", SECRET))).isEmpty();
        assertThat(dev.authenticate(request("wechat", "robot_0001", SECRET))).isEmpty();
        // auth_type 精确匹配，大小写不同即是未知类型。
        assertThat(dev.authenticate(request("PASSWORD", "robot_0001", SECRET))).isEmpty();
    }

    @Test
    void 口令认证关闭时一律失败() {
        LoginAuthenticator disabled = LoginAuthenticator.passwordDisabled();
        assertThat(disabled.passwordEnabled()).isFalse();
        assertThat(disabled.authenticate(request("", "robot_0001", SECRET))).isEmpty();
    }
}
