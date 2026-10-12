package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.placement.ConnectProbe.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 判死前「原地址到底连不连得上」的探测（{@link TcpConnectProbe}）在真套接字上的三种结论：有人在听 → 连得上；没人在听 → 明确连不上；
 * 预算不够或地址不合法 → 没有结论（不发起连接）。只用本机回环，不依赖外部网络。
 */
class TcpConnectProbeTest {

    private final TcpConnectProbe probe = new TcpConnectProbe();

    @Test
    void 有进程在听_连得上_探测不留连接() throws IOException {
        try (ServerSocket listening = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            assertThat(probe.probe("127.0.0.1", listening.getLocalPort(), 2_000)).isEqualTo(Result.CONNECTED);
            assertThat(probe.probe("127.0.0.1", listening.getLocalPort(), 2_000)).as("连上即关，可以反复探测").isEqualTo(Result.CONNECTED);
        }
    }

    @Test
    void 没人监听的端口_明确连不上() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        // 被拒绝通常是立即的；有的环境要等内核重试几次（旧版 Windows 约 2 s），所以这里给足等待
        long started = System.nanoTime();
        Result result = probe.probe("127.0.0.1", closedPort, TcpConnectProbe.MAX_TIMEOUT_MS);

        assertThat(result).isEqualTo(Result.REFUSED);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("被拒绝不用等满上限").isLessThan(TcpConnectProbe.MAX_TIMEOUT_MS);
    }

    @Test
    void 预算不够或地址不合法_不探测_没有结论() throws IOException {
        try (ServerSocket listening = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            int port = listening.getLocalPort();

            assertThat(probe.probe("127.0.0.1", port, 0)).as("这次直拨的预算已经用完").isEqualTo(Result.INCONCLUSIVE);
            assertThat(probe.probe("127.0.0.1", port, -5)).isEqualTo(Result.INCONCLUSIVE);
            assertThat(probe.probe("", port, 1_000)).isEqualTo(Result.INCONCLUSIVE);
            assertThat(probe.probe(null, port, 1_000)).isEqualTo(Result.INCONCLUSIVE);
            assertThat(probe.probe("127.0.0.1", 0, 1_000)).isEqualTo(Result.INCONCLUSIVE);
            assertThat(probe.probe("127.0.0.1", 70_000, 1_000)).isEqualTo(Result.INCONCLUSIVE);
        }
    }
}
