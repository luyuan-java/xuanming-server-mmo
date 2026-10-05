package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.TipInfoMessage;
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
 * xm-battle dev 接口客户端对一个本机假管理端口：五个路径与请求形状（令牌、操作人、protobuf 体）、建房结果按字段号解（用 xm-api 的生成类钉住
 * 字段号与枚举值）、204 / 200 的期望、各状态码的提示、不发无令牌的请求、抓指标。
 */
class BattleAdminClientTest {

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

    private BattleAdminClient client(String token) {
        return new BattleAdminClient("http://127.0.0.1:" + server.getAddress().getPort(), token, Duration.ofSeconds(5));
    }

    @Test
    void 建房_发protobuf二进制带令牌与操作人_按字段号解出受理结论与契约应答() throws Exception {
        CreateBattleResponse inner = CreateBattleResponse.newBuilder().setBattleId(-2L).build();
        responseBody.set(CreateBattleResult.newBuilder().setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                .setResponse(inner.toByteString()).build().toByteArray());
        CreateBattleRequest request = CreateBattleRequest.newBuilder().setBattleId(-2L).setMatchMode(4).build();

        BattleAdminClient.CreateOutcome outcome = client("tok").create(request);

        assertThat(outcome.admission()).isEqualTo(BattleAdminClient.ADMISSION_ADMITTED);
        assertThat(outcome.admitted()).isTrue();
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.reason()).isEmpty();
        assertThat(outcome.response()).isEqualTo(inner);
        assertThat(captured).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo(BattleAdminClient.CREATE).isEqualTo("/admin/battle/dev/create");
            assertThat(c.token()).isEqualTo("tok");
            assertThat(c.operator()).isEqualTo(AdminClient.OPERATOR);
            assertThat(c.contentType()).isEqualTo("application/x-protobuf");
            assertThat(CreateBattleRequest.parseFrom(c.body())).isEqualTo(request);
        });
    }

    @Test
    void 建房结果_字段号与枚举值对照xm_api生成类钉住() throws Exception {
        assertThat(BattleAdminClient.ADMISSION_ADMITTED).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED_VALUE);
        assertThat(BattleAdminClient.ADMISSION_NOT_ALLOCATABLE).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE_VALUE);
        assertThat(CreateBattleResult.ADMISSION_FIELD_NUMBER).isEqualTo(1);
        assertThat(CreateBattleResult.REASON_FIELD_NUMBER).isEqualTo(2);
        assertThat(CreateBattleResult.RESPONSE_FIELD_NUMBER).isEqualTo(3);

        BattleAdminClient.CreateOutcome refused = BattleAdminClient.decodeCreateResult(CreateBattleResult.newBuilder()
                .setAdmission(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE).setReason("not_started").build().toByteArray());
        assertThat(refused.admitted()).isFalse();
        assertThat(refused.ok()).isFalse();
        assertThat(refused.reason()).isEqualTo("not_started");
        assertThat(refused.describe()).isEqualTo("admission=2 reason=not_started");

        BattleAdminClient.CreateOutcome business = BattleAdminClient.decodeCreateResult(CreateBattleResult.newBuilder()
                .setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED).setResponse(CreateBattleResponse.newBuilder()
                        .setErrorMessage(TipInfoMessage.newBuilder().setId(1005)).build().toByteString()).build().toByteArray());
        assertThat(business.admitted()).isTrue();
        assertThat(business.ok()).as("受理但有业务错误").isFalse();
        assertThat(business.describe()).isEqualTo("admission=1 tip=1005");

        BattleAdminClient.CreateOutcome empty = BattleAdminClient.decodeCreateResult(new byte[0]);
        assertThat(empty.admission()).as("缺字段按 proto3 缺省 = UNSPECIFIED，不会被读成受理").isZero();
        assertThat(empty.admitted()).isFalse();

        assertThatThrownBy(() -> BattleAdminClient.decodeCreateResult(new byte[] {(byte) 0xFF, (byte) 0xFF}))
                .isInstanceOf(RobotException.class).hasMessageContaining("不是 CreateBattleResult");
    }

    @Test
    void 销毁与清退期望204_补签与登记观众解析200应答() throws Exception {
        status.set(204);
        client("tok").destroy(7, "robot");
        client("tok").removeObserver(7, 9, "robot");
        status.set(200);
        BattleAssignedS2C assignment = BattleAssignedS2C.newBuilder().setBattleId(7).setHost("h").setPort(12000).build();
        responseBody.set(IssueBattleTicketResponse.newBuilder().setAssignment(assignment).build().toByteArray());
        assertThat(client("tok").issueTicket(7, 8).getAssignment()).isEqualTo(assignment);
        responseBody.set(AddObserverResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1004)).build().toByteArray());
        assertThat(client("tok").addObserver(7, 9, "C").getErrorMessage().getId()).isEqualTo(1004);

        assertThat(captured).extracting(Captured::path).containsExactly(BattleAdminClient.DESTROY, BattleAdminClient.REMOVE_OBSERVER,
                BattleAdminClient.ISSUE_TICKET, BattleAdminClient.ADD_OBSERVER);
        assertThat(DestroyBattleRequest.parseFrom(captured.get(0).body())).isEqualTo(DestroyBattleRequest.newBuilder().setBattleId(7)
                .setReason("robot").build());
        assertThat(RemoveObserverRequest.parseFrom(captured.get(1).body()).getObserverPlayerId()).isEqualTo(9);
        assertThat(IssueBattleTicketRequest.parseFrom(captured.get(2).body()).getPlayerId()).isEqualTo(8);
        AddObserverRequest add = AddObserverRequest.parseFrom(captured.get(3).body());
        assertThat(add.getObserverName()).isEqualTo("C");
        assertThat(add.hasRouting()).as("路由留空，由接口按在线目录补全").isFalse();
    }

    @Test
    void 状态码不符_按状态给提示_应答体附在后面() {
        status.set(403);
        responseBody.set("battle dev 管理接口只在运行模式 dev / test 下开放".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client("tok").create(CreateBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("返回 403").hasMessageContaining("XM_RUN_MODE=dev")
                .hasMessageContaining("只在运行模式");
        status.set(200);
        responseBody.set(new byte[0]);
        assertThatThrownBy(() -> client("tok").destroy(1, "x")).hasMessageContaining("返回 200");
        assertThat(BattleAdminClient.statusHint(422)).contains("先登录进场");
        assertThat(BattleAdminClient.statusHint(401)).contains("令牌不对");
        assertThat(BattleAdminClient.statusHint(503)).contains("XM_ADMIN_TOKEN");
        assertThat(BattleAdminClient.statusHint(504)).contains("5 s");
        assertThat(BattleAdminClient.statusHint(404)).contains("--battle-admin-url");
        assertThat(BattleAdminClient.statusHint(500)).isEmpty();
    }

    @Test
    void 不判状态码的原始调用_prod核对403用() throws Exception {
        status.set(403);
        responseBody.set("forbidden".getBytes(StandardCharsets.UTF_8));
        BattleAdminClient.HttpResult result = client("tok").post(BattleAdminClient.CREATE, CreateBattleRequest.getDefaultInstance());
        assertThat(result.status()).isEqualTo(403);
        assertThat(result.text()).isEqualTo("forbidden");
    }

    @Test
    void 没有令牌不发请求() {
        assertThat(client(null).hasToken()).isFalse();
        assertThat(client("").hasToken()).isFalse();
        assertThatThrownBy(() -> client(null).create(CreateBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("XM_ADMIN_TOKEN").hasMessageContaining("run/xm-admin-token");
        assertThat(captured).isEmpty();
    }

    @Test
    void 抓指标_非200即失败() throws Exception {
        responseBody.set("xm_battle_rooms{application=\"xm-battle\"} 0.0\n".getBytes(StandardCharsets.UTF_8));
        assertThat(client(null).scrapeMetrics()).contains("xm_battle_rooms");
        assertThat(captured).singleElement().extracting(Captured::path).isEqualTo("/actuator/prometheus");
        status.set(500);
        assertThatThrownBy(() -> client(null).scrapeMetrics()).isInstanceOf(RobotException.class).hasMessageContaining("500");
    }

    @Test
    void 连不上给出xm_battle未起的提示() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        BattleAdminClient down = new BattleAdminClient("http://127.0.0.1:" + closedPort, "tok", Duration.ofSeconds(10));
        assertThatThrownBy(() -> down.create(CreateBattleRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class).hasMessageContaining("xm-battle");
    }
}
