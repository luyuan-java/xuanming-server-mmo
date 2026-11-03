package com.game.common.sql.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 本机黑洞握手集成回归：只占独立回环端口，不连接真实数据库或读取凭据。 */
@Tag("integration")
class AuthoritySqlFixtureConnectionTest {
    @Test
    void 对端接受TCP但不发送握手时连接仍有上限() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var executor = Executors.newFixedThreadPool(2);
            Socket peer = null;
            try {
                var accepted = executor.submit(listener::accept);
                var connection = executor.submit(() -> {
                    try (var ignored = AuthoritySqlFixture.openFixtureConnection(
                            "jdbc:mysql://127.0.0.1:" + listener.getLocalPort() + "/",
                            "isolated_fixture", "non-secret-test-value")) {
                        return null;
                    }
                });
                peer = accepted.get(3, TimeUnit.SECONDS);
                // 固定连接预算之外留调度余量；旧实现会在这里超时失败，finally关闭对端解除阻塞。
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> connection.get(9, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, failure.getCause());
            } finally {
                if (peer != null) peer.close();
                listener.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "所有测试连接线程必须退出");
            }
        }
    }
}
