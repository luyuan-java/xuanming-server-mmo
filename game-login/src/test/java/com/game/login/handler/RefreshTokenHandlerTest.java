package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.SessionContext;
import com.game.login.testing.InMemoryLoginTokens;
import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.login.token.TokenPair;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.table.CommonErrorTip;
import com.game.table.LoginErrorTip;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 127 RefreshToken：轮换、一次性、空 / 无效 / 存储出错一律 2000；不要求本连接已登录；客户端路径轮换前查请求截止。 */
@ExtendWith(OutputCaptureExtension.class)
class RefreshTokenHandlerTest {

    private static final Duration BUDGET = RefreshTokenHandler.DEFAULT_REFRESH_BUDGET;

    private final InMemoryLoginTokens tokens = new InMemoryLoginTokens();
    /** 请求预算用的单调时钟（纳秒），测试手动拨动、不读真实时钟：没拨动它的用例永远在预算之内。 */
    private final long[] nanos = {1_000_000_000L};
    private final RefreshTokenHandler handler = new RefreshTokenHandler(tokens, () -> nanos[0], BUDGET);

    private static RefreshTokenRequest request(String token) {
        return RefreshTokenRequest.newBuilder().setRefreshToken(token).build();
    }

    private static int tip(RefreshTokenResponse response) {
        return response.getErrorMessage().getId();
    }

    /** 走分发器的客户端路径：带排队时长调用。 */
    private static RefreshTokenResponse viaClient(RefreshTokenHandler handler, String token, long queuedNanos)
            throws Exception {
        return RefreshTokenResponse.parseFrom(handler.handle(SessionContext.getDefaultInstance(), request(token), queuedNanos)
                .join().body().orElseThrow().toByteString());
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

    // ---- 请求预算：轮换之前用完就什么都不写（mmorpg client-request-ordering.md §6.1）----

    @Test
    void 排队已耗掉请求预算_不轮换_回1003_旧refresh仍可用() throws Exception {
        // login 工作队列积压：gate 的 5 s 从发出请求就开始走，处理器开始跑时预算已经没了。这时再轮换，
        // 旧 refresh 会在 gate 放行同一会话的下一条请求之后才作废。截止当刻也算用完。
        TokenPair first = tokens.issue("robot_0001", "password", "");

        RefreshTokenResponse abandoned = viaClient(handler, first.refreshToken(), BUDGET.toNanos());

        assertThat(tip(abandoned)).as("可重试的 1003，不是逼客户端重登的 2000")
                .isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        assertThat(abandoned.getAccessToken()).isEmpty();
        assertThat(tokens.refresh).as("旧 refresh 没被作废").containsKey(first.refreshToken());
        assertThat(tokens.issued).as("没签新的一对").isEqualTo(1);

        // 客户端拿原 token 重试（排队只耗掉预算减 1 ms，仍在预算之内）：照常轮换。
        RefreshTokenResponse retried = viaClient(handler, first.refreshToken(), BUDGET.minusMillis(1).toNanos());
        assertThat(retried.hasErrorMessage()).isFalse();
        assertThat(retried.getRefreshToken()).isNotEmpty().isNotEqualTo(first.refreshToken());
        assertThat(tokens.refresh).doesNotContainKey(first.refreshToken());
    }

    @Test
    void 读旧refresh慢到把预算用完_轮换前那一刻看截止_不轮换() throws Exception {
        // 截止在提交点那一刻看，不是只在入口看一次：入口时预算充足，读旧 refresh 的那一步把预算正好用完。
        LoginTokens slowRead = new LoginTokens() {
            @Override
            public TokenPair issue(String account, String authType, String deviceId) {
                return tokens.issue(account, authType, deviceId);
            }

            @Override
            public Optional<TokenData> validateAccess(String accessToken) {
                return tokens.validateAccess(accessToken);
            }

            @Override
            public Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline) {
                nanos[0] += BUDGET.toNanos();
                return tokens.refresh(refreshToken, pastDeadline);
            }
        };
        TokenPair first = tokens.issue("robot_0001", "password", "");

        RefreshTokenResponse abandoned = viaClient(new RefreshTokenHandler(slowRead, () -> nanos[0], BUDGET),
                first.refreshToken(), 0);

        assertThat(tip(abandoned)).isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        assertThat(tokens.refresh).containsKey(first.refreshToken());
        assertThat(tokens.issued).isEqualTo(1);
    }

    @Test
    void 刷新失败日志不记录异常消息和嵌套凭据(CapturedOutput output) {
        String marker = "synthetic-refresh-credential-marker";
        LoginTokens broken = mock(LoginTokens.class);
        when(broken.refresh(anyString(), any())).thenThrow(new IllegalStateException("Redis key=" + marker,
                new IllegalArgumentException("Redis command=" + marker)));
        RefreshTokenResponse response = new RefreshTokenHandler(broken).refresh(request(marker));
        assertThat(tip(response)).isEqualTo(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE);
        assertThat(response.getRefreshToken()).isEmpty();
        assertThat(output.getAll()).contains("IllegalStateException").doesNotContain(marker);
    }
}
