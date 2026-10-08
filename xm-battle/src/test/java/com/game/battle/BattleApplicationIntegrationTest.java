package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admin.BattleAdminAuthFilter;
import com.game.battle.admin.DevBattleController;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.port.kafka.KafkaBattleResultSink;
import com.game.battle.testing.FakeResultKafka;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.eBattleTicketRole;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 整个 xm-battle 进程的装配与启停（连真 Redis，缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 14；battle-node-spec §7.11、§7.12、
 * §13.5、§13.6）：真租约、真目录、真 Dubbo Triple 导出、真直连端口、真房间服务、真管理 Tomcat。核对开闸后进目录、dev 接口建房 → 经 Triple 补签
 * → 票据地址是通告地址与直连端口、Prometheus 导出 battle 指标、dev 销毁后补签回 1005。对局结果的 Kafka 客户端换成 {@link FakeResultKafka}
 * （这个类只开 Redis 的开关，不该依赖、更不该写本机的真 Kafka）：核对启动时按契约建出了结果 topic，dev 房间自始至终没有结果消息。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
@SpringBootTest(classes = {BattleApplication.class, FakeResultKafka.Beans.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "xm.run-mode=dev",
                "xm.table-dir=../config-data/tables",
                "xm.advertise-host=127.0.0.1",
                "xm.redis.database=14",
                "XM_ADMIN_TOKEN=" + BattleApplicationIntegrationTest.ADMIN_TOKEN,
                "XM_GATE_TOKEN_SECRET="})
@AutoConfigureObservability(tracing = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BattleApplicationIntegrationTest {

    static final String ADMIN_TOKEN = "battle-it-admin-token";
    static final int CLIENT_PORT = freePort();
    static final int RPC_PORT = freePort();
    static final long PLAYER_A = 8_800_001L;
    static final long PLAYER_B = 8_800_002L;

    private static IsolatedDubboModule clientModel;

    @LocalServerPort
    int managementPort;

    @Autowired
    BattleNode node;

    @Autowired
    AdmissionGate admission;

    @Autowired
    BattleTables tables;

    @Autowired
    RedissonClient redis;

    @Autowired
    FakeResultKafka resultKafka;

    @Autowired
    KafkaBattleResultSink resultSink;

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("xm.redis.address", () -> System.getProperty("xm.it.redis"));
        registry.add("xm.battle.client-port", () -> CLIENT_PORT);
        registry.add("xm.battle.rpc-port", () -> RPC_PORT);
    }

    @AfterAll
    static void closeClient() {
        if (clientModel != null) {
            clientModel.close();
        }
    }

    private static BattleNodeService client() {
        if (clientModel == null) {
            clientModel = IsolatedDubboModule.create("xm-battle-it-client");
        }
        ReferenceConfig<BattleNodeService> reference = new ReferenceConfig<>(clientModel.module());
        reference.setInterface(BattleNodeService.class);
        reference.setGroup(DubboGroups.BATTLE_NODE);
        reference.setUrl("tri://127.0.0.1:" + RPC_PORT);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5000);
        return reference.get();
    }

    private HttpResponse<byte[]> admin(String path, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", DevBattleController.CONTENT_TYPE)
                .header(BattleAdminAuthFilter.TOKEN_HEADER, ADMIN_TOKEN)
                .header(BattleAdminAuthFilter.OPERATOR_HEADER, "battle-it")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static BattlePlayerSnapshot.Builder player(long playerId, int team) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName("it-" + playerId)
                .setLevel(10)
                .setTeamIndex(team)
                .setMaxHealth(1_000_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1_000_000).setStrength(10).setSpeed(100))
                .setRouting(BattleRouting.newBuilder().setSessionId(1).setGateNodeId(1).setGateInstanceId("gate-it")
                        .setSceneNodeId(1).setSceneInstanceId("scene-it").setZoneId(1));
    }

    @Test
    void 整进程_开闸进目录_dev建房_经Triple补签_指标导出_销毁后补签1005() throws Exception {
        // 启动：开闸、进目录（accepting = true）、直连端口在听
        assertThat(node.isRunning()).isTrue();
        assertThat(admission.phase()).isEqualTo(AdmissionPhase.OPEN);
        BattleIdentity identity = node.identity().orElseThrow();
        assertThat(identity.advertisePort()).isEqualTo(CLIENT_PORT);
        BattleNodeInfo entry = new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser()).list(0).stream()
                .filter(info -> info.getInstanceId().equals(node.instanceId())).findFirst().orElseThrow();
        assertThat(entry.getNodeId()).isEqualTo(identity.nodeId());
        assertThat(entry.getAccepting()).isTrue();
        assertThat(entry.getRpcPort()).isEqualTo(RPC_PORT);
        assertThat(entry.getClientPort()).isEqualTo(CLIENT_PORT);
        assertThat(entry.getTableFingerprint()).isEqualTo(tables.fingerprint());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", CLIENT_PORT), 2000);
            assertThat(socket.isConnected()).isTrue();
        }
        // 对局结果的 Kafka 生产方（6.4）：进程装配出的是 Kafka 实现，启动时按契约核对 / 建出了结果 topic（缺省代次 1、3 分区）
        assertThat(resultSink.topic()).isEqualTo("xm-battle-result-g1");
        assertThat(resultSink.verified()).isTrue();
        assertThat(resultKafka.partitionsOf("xm-battle-result-g1")).isEqualTo(3);
        assertThat(resultKafka.producersMade()).isEqualTo(1);

        // dev 建房（PVP 1V1，路由显式给全）
        long battleId = System.currentTimeMillis();
        CreateBattleRequest create = CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(3)
                .setSeed(7)
                .setDeadlineMs(System.currentTimeMillis() + 300_000)
                .addPlayers(player(PLAYER_A, 0))
                .addPlayers(player(PLAYER_B, 1))
                .build();
        HttpResponse<byte[]> created = admin(DevBattleController.CREATE, create.toByteArray());
        assertThat(created.statusCode()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        CreateBattleResult result = CreateBattleResult.parseFrom(created.body());
        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        CreateBattleResponse response = CreateBattleResponse.parseFrom(result.getResponse());
        assertThat(response.getBattleId()).isEqualTo(battleId);
        assertThat(response.hasErrorMessage()).as("建房成功：%s", response.getErrorMessage()).isFalse();

        // 经 Triple 补签：票据地址是通告地址 + 直连端口，签名是 64 位小写 hex
        BattleNodeService battle = client();
        IssueBattleTicketResponse ticket = battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder()
                .setBattleId(battleId).setPlayerId(PLAYER_A).build()).get(10, TimeUnit.SECONDS);
        assertThat(ticket.hasErrorMessage()).isFalse();
        assertThat(ticket.getAssignment().getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(ticket.getAssignment().getHost()).isEqualTo("127.0.0.1");
        assertThat(ticket.getAssignment().getPort()).isEqualTo(CLIENT_PORT);
        assertThat(ticket.getAssignment().getTokenSignature().toString(StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");

        // Prometheus 导出
        HttpResponse<String> scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("xm_battle_room_creates_total").contains("xm_battle_admission_phase")
                .contains("xm_battle_rpc_seconds").contains("xm_battle_rooms")
                .contains("xm_battle_result_events_total").contains("result=\"not_verified\"");

        // dev 销毁 → 补签 1005（这局确实没了）
        assertThat(admin(DevBattleController.DESTROY, DestroyBattleRequest.newBuilder().setBattleId(battleId)
                .setReason("it").build().toByteArray()).statusCode()).isEqualTo(204);
        IssueBattleTicketResponse gone = battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder()
                .setBattleId(battleId).setPlayerId(PLAYER_A).build()).get(10, TimeUnit.SECONDS);
        assertThat(gone.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(gone.hasAssignment()).isFalse();
        assertThat(resultKafka.sent()).as("dev 房间、又是销毁作废：没有任何对局结果消息").isEmpty();
    }
}
