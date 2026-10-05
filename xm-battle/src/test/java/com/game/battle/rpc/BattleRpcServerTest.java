package com.game.battle.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.testing.StubBattleRoomService;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 控制面的真 Dubbo Triple 回环（battle-node-spec §13.4）：提供方与调用方各在自己的 Dubbo 框架模型里（等价于两个进程），调用方鉴权过滤器照常生效
 * （测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）。核对导出参数（register = false、group battle-node、tri）、createBattle 的两种结论经 Triple
 * 原样到达调用方、业务 tip 在应答里、不带调用方 MAC 的调用被拒、端口被占导出失败、关闭后连不上。
 */
class BattleRpcServerTest {

    private final DefaultEventLoop loop = new DefaultEventLoop((ThreadFactory) r -> new Thread(r, "test-battle-logic"));
    private final ExecutorService replies = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-battle-rpc-reply"));
    private final AdmissionGate admission = new AdmissionGate();
    private final StubBattleRoomService rooms = new StubBattleRoomService();
    private IsolatedDubboModule clientModel;
    private BattleRpcServer server;
    private int port;

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        rooms.loop = new EventLoopBattleScheduler(loop);
    }

    @AfterEach
    void tearDown() {
        if (clientModel != null) {
            clientModel.close();
        }
        if (server != null) {
            server.close();
        }
        replies.shutdownNow();
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    private BattleNodeServiceImpl provider() {
        return new BattleNodeServiceImpl(admission, new EventLoopBattleScheduler(loop), rooms, replies, 8,
                new BattleMetrics(new SimpleMeterRegistry()));
    }

    /** 调用方：直连 tri://127.0.0.1:port，retries = 0；{@code withoutAuth} 时去掉调用方 MAC 过滤器。 */
    private BattleNodeService client(boolean withoutAuth) {
        clientModel = IsolatedDubboModule.create("xm-battle-test-client");
        ReferenceConfig<BattleNodeService> reference = new ReferenceConfig<>(clientModel.module());
        reference.setInterface(BattleNodeService.class);
        reference.setGroup(DubboGroups.BATTLE_NODE);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5000);
        if (withoutAuth) {
            reference.setFilter("-xmAuthConsumer");
        }
        return reference.get();
    }

    @Test
    void 导出参数_不进注册中心_按group直连() {
        server = BattleRpcServer.export(provider(), "127.0.0.1", port);
        assertThat(server.port()).isEqualTo(port);
        assertThat(server.exportedUrls()).anySatisfy(url -> assertThat(url)
                .startsWith("tri://")
                .contains(":" + port + "/com.game.api.BattleNodeService")
                .contains("register=false")
                .contains("group=battle-node"));
    }

    @Test
    void createBattle两种结论经Triple原样到达调用方() throws Exception {
        server = BattleRpcServer.export(provider(), "127.0.0.1", port);
        BattleNodeService battle = client(false);

        CreateBattleResult before = battle.createBattle(CreateBattleRequest.newBuilder().setBattleId(7).build())
                .get(10, TimeUnit.SECONDS);
        admission.open();
        CreateBattleResult admitted = battle.createBattle(CreateBattleRequest.newBuilder().setBattleId(8).build())
                .get(10, TimeUnit.SECONDS);
        rooms.createTip = 1006;
        CreateBattleResult rejected = battle.createBattle(CreateBattleRequest.newBuilder().setBattleId(9).build())
                .get(10, TimeUnit.SECONDS);

        assertThat(before.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(before.getReason()).isEqualTo("not_started");
        assertThat(admitted.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(admitted.getResponse()).getBattleId()).isEqualTo(8);
        assertThat(rejected.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(rejected.getResponse()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(rooms.threads).as("房间只在逻辑线程上调").containsOnly("test-battle-logic");
    }

    @Test
    void 其余方法经Triple_业务tip在应答里_Dubbo层成功() throws Exception {
        server = BattleRpcServer.export(provider(), "127.0.0.1", port);
        BattleNodeService battle = client(false);
        rooms.ticketTip = 1005;

        Empty destroyed = battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(3).build()).get(10, TimeUnit.SECONDS);
        IssueBattleTicketResponse ticket = battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(3)
                .setPlayerId(5).build()).get(10, TimeUnit.SECONDS);

        assertThat(destroyed).isEqualTo(Empty.getDefaultInstance());
        assertThat(ticket.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(ticket.hasAssignment()).isFalse();
        assertThat(rooms.calls).containsExactly("destroyBattle", "issueBattleTicket");
    }

    @Test
    void 不带调用方MAC的调用被拒_不进入业务代码() {
        server = BattleRpcServer.export(provider(), "127.0.0.1", port);
        admission.open();
        BattleNodeService anonymous = client(true);

        assertThatThrownBy(() -> anonymous.createBattle(CreateBattleRequest.newBuilder().setBattleId(7).build())
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(rooms.calls).isEmpty();
    }

    @Test
    void 端口被占_导出失败() throws IOException {
        try (ServerSocket busy = new ServerSocket(0)) {
            int taken = busy.getLocalPort();
            assertThatThrownBy(() -> BattleRpcServer.export(provider(), "127.0.0.1", taken))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(Integer.toString(taken));
        }
        assertThatThrownBy(() -> BattleRpcServer.export(provider(), "127.0.0.1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 关闭后调用方连不上_是传输失败() throws Exception {
        server = BattleRpcServer.export(provider(), "127.0.0.1", port);
        BattleNodeService battle = client(false);
        assertThat(battle.destroyBattle(DestroyBattleRequest.getDefaultInstance()).get(10, TimeUnit.SECONDS))
                .isEqualTo(Empty.getDefaultInstance());
        server.close();
        server.close();
        server = null;

        assertThatThrownBy(() -> battle.destroyBattle(DestroyBattleRequest.getDefaultInstance()).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }
}
