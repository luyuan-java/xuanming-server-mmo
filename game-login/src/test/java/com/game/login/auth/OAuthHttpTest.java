package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 三方 HTTP：总时限覆盖读响应体、响应体上限、异常与启动校验都不带地址 / 查询串。 */
class OAuthHttpTest {

    private ServerSocket server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() throws IOException {
        release.countDown();
        if (server != null) {
            server.close();
        }
    }

    /** 一个只回响应头和半截响应体、然后不动的服务。 */
    private String stallingServer(String headersAndPartialBody) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                socket.getInputStream().read(new byte[4096]);
                OutputStream out = socket.getOutputStream();
                out.write(headersAndPartialBody.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                // 测试结束
            }
        });
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Test
    void 响应体读到一半卡住_总时限到就失败_不带查询串() throws IOException {
        String base = stallingServer("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{\"open");
        OAuthHttp http = new OAuthHttp(Duration.ofMillis(300));
        long start = System.nanoTime();
        assertThatThrownBy(() -> http.get(base, "/x", Map.of("secret", "TOPSECRET")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("TOPSECRET");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void 响应体超过上限按失败() throws IOException {
        String big = "x".repeat(OAuthHttp.MAX_BODY_BYTES + 1);
        String base = stallingServer("HTTP/1.1 200 OK\r\nContent-Length: " + big.length() + "\r\n\r\n" + big);
        assertThatThrownBy(() -> new OAuthHttp(Duration.ofSeconds(3)).get(base, "/x", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 地址坏了的请求异常不带地址与查询串() {
        assertThatThrownBy(() -> new OAuthHttp().get("http://bad host", "/x", Map.of("secret", "TOPSECRET")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("TOPSECRET")
                .hasMessageNotContaining("bad host");
    }

    @Test
    void 启动校验接口地址_不回显取值() {
        assertThat(OAuthHttp.requireEndpoint("http://127.0.0.1:8080/", "微信认证")).isEqualTo("http://127.0.0.1:8080/");
        assertThat(OAuthHttp.requireEndpoint("https://api.weixin.qq.com", "微信认证")).isNotNull();
        for (String bad : new String[]{"http://wechat_mock:8080", "ftp://host", "host:8080", "http://u:p@host",
                "http://host/?a=1", "http://bad host"}) {
            assertThatThrownBy(() -> OAuthHttp.requireEndpoint(bad, "微信认证")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(bad);
        }
        assertThatThrownBy(() -> new WeChatProvider("wx", "secret", "http://wechat_mock:8080"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QqProvider("100", "http://qq_mock"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
