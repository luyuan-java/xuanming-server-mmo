package com.game.gateway.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.AccountLoginService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** 官方通行证 webhook 入口测试（/internal/passport/events：未配置 404、短密钥拒启、鉴权 401、超限 400、转发 200/400/503）。 */
class PassportEventControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void 密钥不足32字节拒启_未配置恒回404() throws Exception {
        AccountLoginService login = mock(AccountLoginService.class);
        assertThatThrownBy(() -> new PassportEventController(login, "too-short"))
                .isInstanceOf(IllegalStateException.class);

        PassportEventController disabled = new PassportEventController(login, "");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/passport/events");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(disabled.handle(SECRET, req).join().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 密钥错误回401_空包或超64KiB回400_上游映射200_400_503() throws Exception {
        AccountLoginService login = mock(AccountLoginService.class);
        PassportEventController controller = new PassportEventController(login, SECRET);

        MockHttpServletRequest badAuth = new MockHttpServletRequest("POST", "/internal/passport/events");
        badAuth.setContent("{\"user\":\"a\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(controller.handle("wrong-secret", badAuth).join().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.handle(null, badAuth).join().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        MockHttpServletRequest empty = new MockHttpServletRequest("POST", "/internal/passport/events");
        empty.setContent(new byte[0]);
        assertThat(controller.handle(SECRET, empty).join().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        MockHttpServletRequest huge = new MockHttpServletRequest("POST", "/internal/passport/events");
        huge.setContent(new byte[PassportEventController.MAX_BODY_BYTES + 1]);
        assertThat(controller.handle(SECRET, huge).join().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        byte[] payload = "{\"organization\":\"game\",\"user\":\"alice\"}".getBytes(StandardCharsets.UTF_8);
        when(login.handlePassportEvent(any())).thenReturn(CompletableFuture.completedFuture(1));
        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/internal/passport/events");
        ok.setContent(payload);
        assertThat(controller.handle(SECRET, ok).join().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(login).handlePassportEvent(payload);

        when(login.handlePassportEvent(any())).thenReturn(CompletableFuture.completedFuture(-1));
        MockHttpServletRequest badRecord = new MockHttpServletRequest("POST", "/internal/passport/events");
        badRecord.setContent(payload);
        assertThat(controller.handle(SECRET, badRecord).join().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(login.handlePassportEvent(any())).thenReturn(CompletableFuture.completedFuture(-2));
        MockHttpServletRequest unavail = new MockHttpServletRequest("POST", "/internal/passport/events");
        unavail.setContent(payload);
        assertThat(controller.handle(SECRET, unavail).join().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        when(login.handlePassportEvent(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("dubbo timeout")));
        MockHttpServletRequest err = new MockHttpServletRequest("POST", "/internal/passport/events");
        err.setContent(payload);
        assertThat(controller.handle(SECRET, err).join().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
