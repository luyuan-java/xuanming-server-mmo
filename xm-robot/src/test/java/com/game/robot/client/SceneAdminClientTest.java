package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.CreateDungeonInstanceRequest;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.DestroyInstanceRequest;
import com.game.api.proto.DestroyInstanceResponse;
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

/**
 * xm-scene dev 实例管理口客户端（批次 5.3，dungeon-mirror-spec §6.13）对一个本机假管理端口：两个路径与请求形状（令牌、操作人、protobuf 体）、
 * 按字段号手工编解码（用 xm-api scene_admin.proto 的生成类钉住字段号与 uint64 无符号）、非 200 的提示、raw 调用不判状态、不发无令牌的请求、抓指标。
 */
class SceneAdminClientTest {

    private static final long BIG = 0x8000_0000_0000_0001L;

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
    void 建副本_路径令牌操作人与protobuf体_应答按字段号解() throws Exception {
        responseBody.set(CreateDungeonInstanceResponse.newBuilder().setSceneId(BIG).setSceneConfigId(17).setSceneNodeId(3).build()
                .toByteArray());

        SceneAdminClient.Created created = client("tok").createDungeon(1);

        assertThat(created).isEqualTo(new SceneAdminClient.Created(0, BIG, 17, 3));
        assertThat(created.describe()).contains("scene_id=9223372036854775809", "scene_config_id=17", "scene_node_id=3");
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo(SceneAdminClient.CREATE).isEqualTo("/admin/scene/instance/create");
            assertThat(c.token()).isEqualTo("tok");
            assertThat(c.operator()).isEqualTo(AdminClient.OPERATOR);
            assertThat(c.contentType()).isEqualTo("application/x-protobuf");
            assertThat(CreateDungeonInstanceRequest.parseFrom(c.body()))
                    .isEqualTo(CreateDungeonInstanceRequest.newBuilder().setDungeonConfigId(1).build());
        });
    }

    @Test
    void 销毁_号按uint64写出_tip按字段号解() throws Exception {
        responseBody.set(DestroyInstanceResponse.newBuilder().setTipId(3000).build().toByteArray());

        assertThat(client("tok").destroy(BIG)).isEqualTo(3000);

        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo(SceneAdminClient.DESTROY).isEqualTo("/admin/scene/instance/destroy");
            assertThat(DestroyInstanceRequest.parseFrom(c.body())).isEqualTo(DestroyInstanceRequest.newBuilder().setSceneId(BIG).build());
        });
    }

    @Test
    void 编码与生成类逐字节相同_0不写出_缺字段按0解() throws Exception {
        for (int dungeon : new int[] {0, 1, 999, -1}) {
            assertThat(SceneAdminClient.encodeCreate(dungeon))
                    .isEqualTo(CreateDungeonInstanceRequest.newBuilder().setDungeonConfigId(dungeon).build().toByteArray());
        }
        for (long sceneId : new long[] {0, 1, BIG, -1L}) {
            assertThat(SceneAdminClient.encodeDestroy(sceneId))
                    .isEqualTo(DestroyInstanceRequest.newBuilder().setSceneId(sceneId).build().toByteArray());
        }
        CreateDungeonInstanceResponse full = CreateDungeonInstanceResponse.newBuilder().setTipId(3023).setSceneId(-1L)
                .setSceneConfigId(18).setSceneNodeId(-1).build();
        assertThat(SceneAdminClient.decodeCreated(full.toByteArray())).isEqualTo(new SceneAdminClient.Created(3023, -1L, 18, -1));
        assertThat(SceneAdminClient.decodeCreated(new byte[0])).isEqualTo(new SceneAdminClient.Created(0, 0, 0, 0));
        assertThat(SceneAdminClient.decodeDestroyed(new byte[0])).isZero();
        assertThat(SceneAdminClient.decodeDestroyed(DestroyInstanceResponse.newBuilder().setTipId(3005).build().toByteArray()))
                .isEqualTo(3005);
    }

    @Test
    void 应答体解不开即失败() {
        responseBody.set(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        assertThatThrownBy(() -> client("tok").createDungeon(1)).isInstanceOf(RobotException.class)
                .hasMessageContaining("CreateDungeonInstanceResponse");
        assertThatThrownBy(() -> client("tok").destroy(1)).isInstanceOf(RobotException.class)
                .hasMessageContaining("DestroyInstanceResponse");
    }

    @Test
    void 非200按状态码给提示_raw调用不判状态() throws Exception {
        status.set(403);
        responseBody.set("实例管理口只在运行模式 dev / test 下开放".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client("tok").createDungeon(1)).isInstanceOf(RobotException.class)
                .hasMessageContaining("返回 403").hasMessageContaining("dev / test").hasMessageContaining("实例管理口只在");
        SceneAdminClient.HttpResult raw = client("tok").createRaw(1);
        assertThat(raw.status()).isEqualTo(403);
        assertThat(raw.text()).contains("dev / test");
        assertThat(client("tok").destroyRaw(BIG).status()).isEqualTo(403);

        assertThat(SceneAdminClient.statusHint(401)).contains("令牌不对");
        assertThat(SceneAdminClient.statusHint(503)).contains("XM_ADMIN_TOKEN");
        assertThat(SceneAdminClient.statusHint(400)).contains("X-Xm-Operator");
        assertThat(SceneAdminClient.statusHint(404)).contains("--scene-admin-url");
        assertThat(SceneAdminClient.statusHint(500)).isEmpty();
    }

    @Test
    void 没有令牌不发请求() {
        assertThat(client(null).hasToken()).isFalse();
        assertThat(client("").hasToken()).isFalse();
        assertThatThrownBy(() -> client(null).createDungeon(1)).isInstanceOf(RobotException.class)
                .hasMessageContaining("XM_ADMIN_TOKEN").hasMessageContaining("run/xm-admin-token");
        assertThatThrownBy(() -> client("").destroyRaw(1)).isInstanceOf(RobotException.class);
        assertThat(captured).isEmpty();
    }

    @Test
    void 抓指标_非200抛出() throws Exception {
        responseBody.set("xm_scene_instances{kind=\"mirror\",state=\"active\"} 0.0\n".getBytes(StandardCharsets.UTF_8));
        assertThat(client("tok").scrapeMetrics()).contains("xm_scene_instances");
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("GET");
            assertThat(c.path()).isEqualTo("/actuator/prometheus");
        });

        status.set(500);
        assertThatThrownBy(() -> SceneAdminClient.scrape("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5)))
                .isInstanceOf(RobotException.class).hasMessageContaining("500");
    }

    @Test
    void 连不上给出xm_scene未起的提示() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        SceneAdminClient down = new SceneAdminClient("http://127.0.0.1:" + closedPort, "tok", Duration.ofSeconds(10));

        assertThatThrownBy(() -> down.createDungeon(1)).isInstanceOf(RobotException.class).hasMessageContaining("xm-scene");
    }

    private SceneAdminClient client(String token) {
        return new SceneAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), token, Duration.ofSeconds(5));
    }
}
