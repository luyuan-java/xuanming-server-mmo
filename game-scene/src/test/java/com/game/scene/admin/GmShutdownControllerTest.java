package com.game.scene.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GmRequestAuth;
import com.game.common.token.GmShutdownHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** scene 的 GM 停机 HTTP 适配：方法名与 gate 不同（gate 的签名拿到 scene 上不通过）；受理回在线人数并在应答之后退出。 */
class GmShutdownControllerTest {

    private static final byte[] SECRET = "gm-secret".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000L;

    private final AtomicInteger exits = new AtomicInteger();
    private final GmShutdownController controller = new GmShutdownController(new GmShutdownHandler(
            GmShutdownController.METHOD, new GmRequestAuth(SECRET, 300, () -> NOW),
            () -> new GmShutdownHandler.Identity(1, 7, "scene-a"), () -> 5, false), exits::incrementAndGet);

    private int call(String method, String nonce) {
        String ts = Long.toString(NOW);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", GmShutdownController.PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(GmShutdownHandler.OPERATOR_HEADER, "ops");
        request.addHeader(GmShutdownHandler.TIMESTAMP_HEADER, ts);
        request.addHeader(GmShutdownHandler.NONCE_HEADER, nonce);
        request.addHeader(GmShutdownHandler.SIGNATURE_HEADER,
                GmRequestAuth.sign(SECRET, GmRequestAuth.canonical(method, "1:7:scene-a", "ops", ts, nonce, "")));
        return controller.shutdown(request).getStatusCode().value();
    }

    @Test
    void 签给gate的签名在scene上不通过_签给scene的通过并退出() {
        assertThat(call("Gate.GmGracefulShutdown", "n1")).isEqualTo(403);
        assertThat(exits.get()).isZero();
        assertThat(call(GmShutdownController.METHOD, "n2")).isEqualTo(200);
        assertThat(exits.get()).isEqualTo(1);
    }
}
