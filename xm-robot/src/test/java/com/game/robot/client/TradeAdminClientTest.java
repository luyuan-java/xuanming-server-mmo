package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.TipInfoMessage;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 播种接口客户端对一个本机假 xm-trade 管理端口：请求形状（路径、令牌、操作人、protobuf 体）、200 解析、各状态码的提示、不发无令牌的请求。 */
class TradeAdminClientTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>(new byte[0]);
    private final List<Captured> captured = new ArrayList<>();

    private record Captured(String method, String path, String token, String operator, String contentType, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (captured) {
                captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Xm-Admin-Token"), exchange.getRequestHeaders().getFirst("X-Xm-Operator"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
            }
            byte[] out = responseBody.get();
            exchange.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 发protobuf二进制带令牌与操作人_200解析应答() throws Exception {
        SeedListingResponse reply = SeedListingResponse.newBuilder().setListingId(0x8000_0000_0000_0001L).setMarketZone(1).build();
        responseBody.set(reply.toByteArray());
        SeedListingRequest request = SeedListingRequest.newBuilder().setSellerPlayerId(-1L).setTitle("SMK-1-A").build();

        SeedListingResponse got = client("tok").seedListing(request);

        assertThat(got).isEqualTo(reply);
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo(TradeAdminClient.SEED_PATH).isEqualTo("/admin/trade/seed-listing");
            assertThat(c.token()).isEqualTo("tok");
            assertThat(c.operator()).isEqualTo(AdminClient.OPERATOR);
            assertThat(c.contentType()).isEqualTo("application/x-protobuf");
            assertThat(SeedListingRequest.parseFrom(c.body())).isEqualTo(request);
        });
    }

    @Test
    void 业务拒绝在200应答体里_由调用方判定() throws Exception {
        responseBody.set(SeedListingResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(20001)).build().toByteArray());
        assertThat(client("tok").seedListing(SeedListingRequest.getDefaultInstance()).getErrorMessage().getId()).isEqualTo(20001);
    }

    @Test
    void 非200按状态码给提示_应答体附在后面() {
        status.set(403);
        responseBody.set("{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client("tok").seedListing(SeedListingRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("返回 403").hasMessageContaining("dev / test")
                .hasMessageContaining("forbidden");
        status.set(401);
        assertThatThrownBy(() -> client("bad").seedListing(SeedListingRequest.getDefaultInstance()))
                .hasMessageContaining("返回 401").hasMessageContaining("令牌不对");
        assertThat(TradeAdminClient.statusHint(503)).contains("没配 XM_ADMIN_TOKEN");
        assertThat(TradeAdminClient.statusHint(400)).contains("X-Xm-Operator");
        assertThat(TradeAdminClient.statusHint(404)).contains("--trade-admin-url");
        assertThat(TradeAdminClient.statusHint(500)).isEmpty();
    }

    @Test
    void 应答体解不开即失败() {
        responseBody.set(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertThatThrownBy(() -> client("tok").seedListing(SeedListingRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("不是 SeedListingResponse");
    }

    @Test
    void 没有令牌不发请求() {
        assertThat(client(null).hasToken()).isFalse();
        assertThat(client("").hasToken()).isFalse();
        assertThatThrownBy(() -> client(null).seedListing(SeedListingRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("XM_ADMIN_TOKEN").hasMessageContaining("run/xm-admin-token");
        assertThat(captured).isEmpty();
    }

    @Test
    void 连不上给出xm_trade未起的提示() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        TradeAdminClient down = new TradeAdminClient("http://127.0.0.1:" + closedPort, "tok", Duration.ofSeconds(10));
        assertThatThrownBy(() -> down.seedListing(SeedListingRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("xm-trade");
    }

    private TradeAdminClient client(String token) {
        return new TradeAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), token, Duration.ofSeconds(5));
    }
}
