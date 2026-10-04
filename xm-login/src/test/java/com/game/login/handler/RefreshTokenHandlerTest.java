package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.login.token.TokenPair;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.table.LoginErrorTip;
import org.junit.jupiter.api.Test;

/** 127 RefreshToken：轮换、一次性、空 / 无效 / 存储出错一律 2000；不要求本连接已登录。 */
class RefreshTokenHandlerTest {

    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    private final RefreshTokenHandler handler = new RefreshTokenHandler(tokens);

    private static RefreshTokenRequest request(String token) {
        return RefreshTokenRequest.newBuilder().setRefreshToken(token).build();
    }

    private static int tip(RefreshTokenResponse response) {
        return response.getErrorMessage().getId();
    }

    @Test
    void 轮换成功回新的一对_旧refresh再用回2000() throws Exception {
        TokenPair first = tokens.issue("robot_0001", "password", "");

        RefreshTokenResponse rotated = RefreshTokenResponse.parseFrom(
                handler.handle(SessionContext.getDefaultInstance(), request(first.refreshToken())).join()
                        .body().orElseThrow().toByteString());

        assertThat(rotated.hasErrorMessage()).isFalse();
        assertThat(rotated.getAccessToken()).isNotEmpty().isNotEqualTo(first.accessToken());
        assertThat(rotated.getRefreshToken()).isNotEmpty().isNotEqualTo(first.refreshToken());
        assertThat(rotated.getAccessTokenExpire()).isPositive();
        assertThat(tip(handler.refresh(request(first.refreshToken()))))
                .isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
    }

    @Test
    void 空的_无效的_存储出错都回2000() {
        int notFound = LoginErrorTip.login_error.kLoginAccountNotFound_VALUE;
        assertThat(tip(handler.refresh(request("")))).isEqualTo(notFound);
        assertThat(tip(handler.refresh(request("nope")))).isEqualTo(notFound);
        TokenPair pair = tokens.issue("robot_0001", "password", "");
        tokens.broken = true;
        RefreshTokenResponse broken = handler.refresh(request(pair.refreshToken()));
        assertThat(tip(broken)).isEqualTo(notFound);
        assertThat(broken.getAccessToken()).isEmpty();
    }
}
