package com.game.battle.e2e;

import static com.game.battle.e2e.E2eSupport.NOTIFY_SPECTATE_END;
import static com.game.battle.e2e.E2eSupport.NOTIFY_SPECTATE_STATE;
import static com.game.battle.e2e.E2eSupport.count;
import static com.game.battle.e2e.E2eSupport.createAdmitted;
import static com.game.battle.e2e.E2eSupport.nextBattleId;
import static com.game.battle.e2e.E2eSupport.pvp;
import static com.game.battle.e2e.E2eSupport.reissue;
import static com.game.battle.e2e.E2eSupport.routing;
import static com.game.battle.e2e.E2eSupport.tank;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.BattleNodeService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.BattleApplication;
import com.game.battle.BattleNode;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.testing.FakeResultKafka;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.SpectateEndS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eSpectateEndReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 停机的端到端（真 Spring 进程 + 真 Redis，缺省跳过：{@code -Dxm.it.redis}，DB 14；battle-node-spec §6.6、§7.11、§11 N16）：停机时
 * 「关闸 + 作废全部房间」在同一个逻辑任务里，观众先收完 166{ABORTED, ONGOING} 再 FIN（修基线 F1：观众的 166 不会被强关吞掉），参战者
 * 什么帧都收不到、只见 FIN，未握手的空闲连接被关；之后目录条目删除、不再接受直连、建房回 NOT_ALLOCATABLE。另核对握手期限（注入 2 s）。
 * 停机会拆掉本类的整个进程，所以单独一个类、单独一个 Spring 上下文。对局结果的 Kafka 客户端换成 {@link FakeResultKafka}（只开 Redis 的开关，
 * 不依赖也不写本机的真 Kafka）：停机作废的房间不发结果事件（评分只按真正打完的局更新）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
@SpringBootTest(classes = {BattleApplication.class, FakeResultKafka.Beans.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "xm.run-mode=dev",
                "xm.table-dir=../config-data/tables",
                "xm.advertise-host=127.0.0.1",
                "xm.redis.database=14",
                "xm.battle.handshake-timeout=2s",
                "XM_GATE_TOKEN_SECRET="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BattleNodeShutdownEndToEndTest {

    static final int CLIENT_PORT = E2eSupport.freePort();
    static final int RPC_PORT = E2eSupport.freePort();
    static final int ZONE = 9702;
    static final int GATE_NODE = 978;
    static final long PLAYER_BASE = 7_500_000_000L + (System.currentTimeMillis() % 100_000_000L) * 100;

    private static IsolatedDubboModule clientModel;

    @Autowired
    BattleNode node;

    @Autowired
    MeterRegistry meters;

    @Autowired
    RedissonClient redis;

    @Autowired
    FakeResultKafka resultKafka;

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

    @Test
    void 握手期限到点被关_停机时观众先收166再FIN_参战者只见FIN_空闲连接被关_之后目录删除不再接受连接建房不可分配() throws Exception {
        clientModel = IsolatedDubboModule.create("xm-battle-e2e-shutdown-match");
        BattleNodeService battle = E2eSupport.tripleClient(clientModel, RPC_PORT);
        GatePushInbox gate = new GatePushInbox(redis, ZONE, GATE_NODE, "e2e-gate-" + UUID.randomUUID());

        // G3：连上不握手，握手期限（注入 2 s）到点被关，不回包
        try (DirectClient idle = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
            long started = System.nanoTime();
            idle.expectClosed(Duration.ofSeconds(5));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1_500L, 4_000L);
        }

        long a = PLAYER_BASE + 1;
        long b = PLAYER_BASE + 2;
        long watcher = PLAYER_BASE + 3;
        long battleId = nextBattleId();
        createAdmitted(battle, pvp(battleId, System.currentTimeMillis() + 300_000, tank(a, 0, routing(gate, 601)),
                tank(b, 1, routing(gate, 602))));
        assertThat(battle.addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(watcher)
                .setRouting(routing(gate, 603)).build()).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        BattleAssignedS2C ticketA = reissue(battle, battleId, a);
        BattleAssignedS2C ticketW = reissue(battle, battleId, watcher);
        NodeDirectory<BattleNodeInfo> directory = new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser());
        assertThat(directory.list(0)).anySatisfy(info -> {
            assertThat(info.getInstanceId()).isEqualTo(node.instanceId());
            assertThat(info.getAccepting()).isTrue();
        });
        BattleNodeServiceImpl inProcess = node.controlPlane().orElseThrow();
        double abortedBefore = count(meters, "xm.battle.room.ends", "reason", "aborted");
        double shutdownBefore = count(meters, "xm.battle.disconnects", "reason", "shutdown");

        try (DirectClient ca = DirectClient.connectWith(ticketA); DirectClient cw = DirectClient.connectWith(ticketW)) {
            ca.expect("verify-ok:" + battleId);
            cw.expect("verify-ok:" + battleId);
            cw.expect("push:" + NOTIFY_SPECTATE_STATE);
            try (DirectClient unverified = DirectClient.connect("127.0.0.1", CLIENT_PORT)) {
                ca.expectSilence(Duration.ofMillis(200));

                node.stop();

                // 观众：166{ABORTED, ONGOING} 完整到达之后才 FIN（N16）
                SpectateEndS2C end = SpectateEndS2C.parseFrom(cw.expect("push:" + NOTIFY_SPECTATE_END).content().getSerializedMessage());
                assertThat(end.getBattleId()).isEqualTo(battleId);
                assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
                assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
                assertThat(cw.expectClosed(Duration.ofSeconds(2))).isEqualTo("fin");
                // 参战者（O6）：没有 150、没有任何帧，只见 FIN
                assertThat(ca.expectClosed(Duration.ofSeconds(2))).isEqualTo("fin");
                // 未握手的空闲连接：排空之后被关
                unverified.expectClosed(Duration.ofSeconds(2));
            }
        }

        assertThat(node.isRunning()).isFalse();
        assertThat(node.admission().phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(count(meters, "xm.battle.room.ends", "reason", "aborted") - abortedBefore).isEqualTo(1);
        assertThat(count(meters, "xm.battle.disconnects", "reason", "shutdown") - shutdownBefore).isGreaterThanOrEqualTo(2);
        // 停机作废的房间不发对局结果：三个结局的计数都没动，生产者上一条消息也没有（节点停了，结果发送要等 Spring 销毁 bean 才关）
        assertThat(count(meters, "xm.battle.result.events")).isZero();
        assertThat(count(meters, "xm.battle.results")).isZero();
        assertThat(resultKafka.sent()).isEmpty();
        assertThat(resultKafka.producer(0).closed()).isFalse();
        // 停机第 1 步删了目录条目；直连端口不再接受连接
        assertThat(directory.list(0)).noneSatisfy(info -> assertThat(info.getInstanceId()).isEqualTo(node.instanceId()));
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", CLIENT_PORT), 1000);
            }
        }).isInstanceOf(IOException.class);
        // 停机之后进来的建房：准入闸已关 → NOT_ALLOCATABLE(closed)，零副作用；Triple 已反导出 → 传输失败
        CreateBattleResult late = inProcess.createBattle(pvp(nextBattleId(), System.currentTimeMillis() + 300_000,
                tank(a, 0, routing(gate, 601)), tank(b, 1, routing(gate, 602)))).get(5, TimeUnit.SECONDS);
        assertThat(late.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(late.getReason()).isEqualTo(BattleNodeServiceImpl.REASON_CLOSED);
        assertThat(late.getResponse().isEmpty()).isTrue();
        assertThat(node.controlPlane()).isEmpty();
        assertThatThrownBy(() -> battle.issueBattleTicket(com.game.proto.IssueBattleTicketRequest.newBuilder()
                .setBattleId(battleId).setPlayerId(a).build()).get(10, TimeUnit.SECONDS)).isNotNull();
        gate.close();
    }
}
