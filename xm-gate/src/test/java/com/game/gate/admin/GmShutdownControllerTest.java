package com.game.gate.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GmRequestAuth;
import com.game.common.token.GmShutdownHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** gate 的 GM 停机 HTTP 适配：请求头 → 受理逻辑；受理了才在应答之后退出；身份接口。受理与鉴权细节见 GmShutdownHandlerTest。 */
class GmShutdownControllerTest {

    private static final byte[] SECRET = "gm-secret".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000L;

    private final AtomicInteger exits = new AtomicInteger();
    private final GmShutdownController controller = new GmShutdownController(new GmShutdownHandler(
            GmShutdownController.METHOD, new GmRequestAuth(SECRET, 300, () -> NOW),
            () -> new GmShutdownHandler.Identity(1, 3, "inst-a"), () -> 7, false), exits::incrementAndGet);

    private MockHttpServletRequest request(String method, String target, String nonce, String reason) {
        String ts = Long.toString(NOW);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", GmShutdownController.PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(GmShutdownHandler.OPERATOR_HEADER, "ops");
        request.addHeader(GmShutdownHandler.TIMESTAMP_HEADER, ts);
        request.addHeader(GmShutdownHandler.NONCE_HEADER, nonce);
        request.addHeader(GmShutdownHandler.SIGNATURE_HEADER,
                GmRequestAuth.sign(SECRET, GmRequestAuth.canonical(method, target, "ops", ts, nonce, reason)));
        request.addHeader(GmShutdownHandler.REASON_HEADER, URLEncoder.encode(reason, StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void 签名对_回会话数_应答之后退出() {
        var resp = controller.shutdown(request(GmShutdownController.METHOD, "1:3:inst-a", "n1", "维护"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("affected_count", 7);
        assertThat(exits.get()).isEqualTo(1);
    }

    @Test
    void 签给scene的签名在gate上不通过_不退出() {
        assertThat(controller.shutdown(request("Scene.GmGracefulShutdown", "1:3:inst-a", "n2", "")).getStatusCode().value())
                .isEqualTo(403);
        assertThat(exits.get()).isZero();
    }

    @Test
    void 身份接口() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", GmShutdownController.IDENTITY_PATH);
        request.setRemoteAddr("127.0.0.1");
        assertThat(controller.identity(request).getBody()).containsEntry("instance_id", "inst-a")
                .containsEntry("method", GmShutdownController.METHOD);
    }
}
