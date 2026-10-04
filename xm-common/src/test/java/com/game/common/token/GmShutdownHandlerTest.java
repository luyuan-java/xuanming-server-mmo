package com.game.common.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * GM 签名停机的受理：只收本机、签名绑「区:节点号:实例」（换区 / 重启后的新进程不认旧签名）、原因走请求头且限长、没在运行 503、
 * 身份接口。
 */
class GmShutdownHandlerTest {

    private static final byte[] SECRET = "gm-secret".getBytes(StandardCharsets.UTF_8);
    private static final String METHOD = "Gate.GmGracefulShutdown";
    private static final long NOW = 1_800_000_000L;

    private final AtomicReference<GmShutdownHandler.Identity> identity =
            new AtomicReference<>(new GmShutdownHandler.Identity(1, 3, "inst-a"));
    private final GmShutdownHandler handler = new GmShutdownHandler(METHOD, new GmRequestAuth(SECRET, 300, () -> NOW),
            identity::get, () -> 42, false);

    private static GmShutdownHandler.Request signed(String remote, String target, String nonce, String reason) {
        String ts = Long.toString(NOW);
        String signature = GmRequestAuth.sign(SECRET, GmRequestAuth.canonical(METHOD, target, "ops", ts, nonce, reason));
        return new GmShutdownHandler.Request(remote, "ops", ts, nonce, signature,
                URLEncoder.encode(reason, StandardCharsets.UTF_8));
    }

    @Test
    void 签名绑区节点号实例_受理回影响数() {
        GmShutdownHandler.Reply reply = handler.shutdown(signed("127.0.0.1", "1:3:inst-a", "n1", "滚动 发布"));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.accepted()).isTrue();
        assertThat(reply.body()).containsEntry("affected_count", 42);
    }

    @Test
    void 别的区同号节点_重启后的新实例_都不认旧签名() {
        assertThat(handler.shutdown(signed("127.0.0.1", "2:3:inst-a", "n2", "")).status()).as("别的区").isEqualTo(403);
        identity.set(new GmShutdownHandler.Identity(1, 3, "inst-b"));
        assertThat(handler.shutdown(signed("127.0.0.1", "1:3:inst-a", "n3", "")).status()).as("重启后的新进程")
                .isEqualTo(403);
        assertThat(handler.shutdown(signed("127.0.0.1", "1:3:inst-b", "n4", "")).accepted()).isTrue();
    }

    @Test
    void 只收本机来的请求_显式打开才收远程() {
        assertThat(handler.shutdown(signed("10.0.0.5", "1:3:inst-a", "n5", "")).status()).isEqualTo(403);
        assertThat(handler.identity("10.0.0.5").status()).isEqualTo(403);
        assertThat(handler.shutdown(signed("0:0:0:0:0:0:0:1", "1:3:inst-a", "n6", "")).accepted()).isTrue();
        GmShutdownHandler remote = new GmShutdownHandler(METHOD, new GmRequestAuth(SECRET, 300, () -> NOW),
                identity::get, () -> 0, true);
        assertThat(remote.shutdown(signed("10.0.0.5", "1:3:inst-a", "n7", "")).accepted()).isTrue();
    }

    @Test
    void 原因限长_编码不合法拒() {
        assertThat(handler.shutdown(signed("127.0.0.1", "1:3:inst-a", "n8", "x".repeat(GmRequestAuth.MAX_REASON_LENGTH)))
                .accepted()).isTrue();
        assertThat(handler.shutdown(signed("127.0.0.1", "1:3:inst-a", "n9", "x".repeat(GmRequestAuth.MAX_REASON_LENGTH + 1)))
                .status()).isEqualTo(403);
        GmShutdownHandler.Request bad = signed("127.0.0.1", "1:3:inst-a", "n10", "");
        assertThat(handler.shutdown(new GmShutdownHandler.Request(bad.remoteAddr(), bad.operator(), bad.timestamp(),
                bad.nonce(), bad.signature(), "%zz")).status()).isEqualTo(403);
    }

    @Test
    void 没在运行503_身份接口回区节点号实例() {
        assertThat(handler.identity("127.0.0.1").body()).containsEntry("zone_id", 1).containsEntry("node_id", 3)
                .containsEntry("instance_id", "inst-a").containsEntry("method", METHOD);
        identity.set(new GmShutdownHandler.Identity(1, 0, "inst-a"));
        assertThat(handler.identity("127.0.0.1").status()).isEqualTo(503);
        assertThat(handler.shutdown(signed("127.0.0.1", "1:0:inst-a", "n11", "")).status()).isEqualTo(503);
    }

    @Test
    void 日志字段截断_控制字符替换() {
        assertThat(GmShutdownHandler.clip("a\nb")).isEqualTo("a?b");
        assertThat(GmShutdownHandler.clip("x".repeat(200))).hasSize(129);
        assertThat(GmShutdownHandler.clip(null)).isEmpty();
    }
}
