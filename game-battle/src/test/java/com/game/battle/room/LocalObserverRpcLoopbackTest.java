package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.BattleAdmission;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.rpc.BattleRpcServer;
import com.game.proto.AddObserverRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.RemoveObserverResponse;
import io.netty.channel.DefaultEventLoop;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 原操作经过真实 Triple、调用方鉴权、逻辑线程和房间账本。使用固定配表与记录型出站端口，
 * 不连 Redis/SQL；match 的请求构造/标记恢复由其流程和直拨回环测试另验。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalObserverRpcLoopbackTest {
    private final RoomHarness fixture = new RoomHarness();
    private final DefaultEventLoop loop = new DefaultEventLoop(
            (ThreadFactory) task -> new Thread(task, "observer-room-loopback"));
    private final ExecutorService replies = Executors.newFixedThreadPool(2,
            task -> new Thread(task, "observer-room-reply"));
    private BattleRoomServiceImpl rooms;
    private BattleRpcServer server;
    private IsolatedDubboModule clientModel;
    private BattleNodeService client;

    @BeforeAll
    void start() throws Exception {
        var scheduler = new EventLoopBattleScheduler(loop);
        // 配表、票据与出站端口复用真实房间组件夹具；实际执行线程和 RPC 使用生产实现。
        rooms = new BattleRoomServiceImpl(new RoomDependencies(fixture.data, RoomHarness.NODE_FINGERPRINT,
                FingerprintMode.WARN, scheduler, () -> RoomHarness.T0, fixture.identity, fixture.tickets,
                fixture.ids, fixture.lobby, fixture.sceneEvents, fixture.settlements, fixture.activityResults,
                fixture.results, fixture.metrics));
        var admission = new AdmissionGate();
        admission.open();
        var provider = new BattleNodeServiceImpl(admission, scheduler, rooms, replies, 8, fixture.metrics);
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = BattleRpcServer.export(provider, "127.0.0.1", port);
        clientModel = IsolatedDubboModule.create("observer-real-room-client");
        var reference = new ReferenceConfig<BattleNodeService>(clientModel.module());
        reference.setInterface(BattleNodeService.class);
        reference.setGroup(DubboGroups.BATTLE_NODE);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5_000);
        client = reference.get();
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (server != null) server.close();
            if (clientModel != null) clientModel.close();
            if (rooms != null) onLoop(() -> {
                rooms.abortAll("test_shutdown");
                return null;
            });
        } finally {
            replies.shutdownNow();
            var stopped = loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            assertThat(replies.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.awaitUninterruptibly(5, TimeUnit.SECONDS)).isTrue();
            fixture.registry.close();
        }
    }

    @Test
    void 原件准入重放及取消证明_旧取消不摘同角色的新操作() throws Exception {
        long battle = 96_101;
        create(battle);
        var first = original(battle);
        assertThat(client.addObserver(first).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        assertThat(client.addObserver(first).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        assertThat(operation(battle)).isEqualTo(first.getSpectatorOperationId());

        assertCancelled(client.removeObserver(remove(first)).get(10, TimeUnit.SECONDS), first);
        assertThat(operation(battle)).isNull();
        assertThat(client.addObserver(first).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();

        var second = original(battle);
        assertThat(client.addObserver(second).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        assertCancelled(client.removeObserver(remove(first)).get(10, TimeUnit.SECONDS), first);
        assertThat(operation(battle)).isEqualTo(second.getSpectatorOperationId());
        assertCancelled(client.removeObserver(remove(second)).get(10, TimeUnit.SECONDS), second);
        assertThat(operation(battle)).isNull();
    }

    @Test
    void 取消先于建房和准入到达_迟到原Add仍被拒绝() throws Exception {
        long battle = 96_102;
        var cancelled = original(battle);
        assertCancelled(client.removeObserver(remove(cancelled)).get(10, TimeUnit.SECONDS), cancelled);
        create(battle);
        assertThat(client.addObserver(cancelled).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();
        assertThat(operation(battle)).isNull();
        var next = original(battle);
        assertThat(client.addObserver(next).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        assertThat(operation(battle)).isEqualTo(next.getSpectatorOperationId());
    }

    @Test
    void 错实例和变异截止不提供取消证明_同操作变异路由不改原槽() throws Exception {
        long battle = 96_103;
        create(battle);
        var original = original(battle);
        assertThat(client.addObserver(original).get(10, TimeUnit.SECONDS).hasErrorMessage()).isFalse();
        var wrongInstance = remove(original).toBuilder().setTargetInstanceId("replacement-instance").build();
        var wrongDeadline = remove(original).toBuilder().setAdmitBeforeMs(original.getAdmitBeforeMs() + 1).build();
        assertThat(client.removeObserver(wrongInstance).get(10, TimeUnit.SECONDS).getCancelled()).isFalse();
        assertThat(client.removeObserver(wrongDeadline).get(10, TimeUnit.SECONDS).getCancelled()).isFalse();
        var changed = original.toBuilder().setRouting(original.getRouting().toBuilder().setSessionId(999)).build();
        assertThat(client.addObserver(changed).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();
        assertThat(operation(battle)).isEqualTo(original.getSpectatorOperationId());
        assertThat(onLoop(() -> rooms.room(battle).routingByObserver.get(RoomHarness.C)))
                .isEqualTo(original.getRouting());
    }

    @Test
    void 旧格式缺原件与非法准入截止经真实房间拒绝() throws Exception {
        long battle = 96_104;
        create(battle);
        var original = original(battle);
        var legacy = original.toBuilder().clearSpectatorOperationId().clearAdmitBeforeMs().clearTargetInstanceId().build();
        var expired = original.toBuilder().setAdmitBeforeMs(RoomHarness.T0).build();
        var tooFar = original(battle).toBuilder().setAdmitBeforeMs(RoomHarness.T0 + 30_001).build();
        assertThat(client.addObserver(legacy).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();
        assertThat(client.addObserver(expired).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();
        assertThat(client.addObserver(tooFar).get(10, TimeUnit.SECONDS).hasErrorMessage()).isTrue();
        assertThat(operation(battle)).isNull();
    }

    private void create(long battle) throws Exception {
        var result = client.createBattle(fixture.pvp(battle, RoomHarness.A, RoomHarness.B).build()).get(10, TimeUnit.SECONDS);
        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(result.getResponse()).hasErrorMessage()).isFalse();
    }

    private AddObserverRequest original(long battle) {
        return AddObserverRequest.newBuilder().setBattleId(battle).setObserverPlayerId(RoomHarness.C)
                .setRouting(RoomHarness.observerRouting(RoomHarness.C, 107)).setObserverName("观战玩家")
                .setSpectatorOperationId(UUID.randomUUID().toString()).setAdmitBeforeMs(RoomHarness.T0 + 25_000)
                .setTargetInstanceId(RoomHarness.INSTANCE).build();
    }

    private RemoveObserverRequest remove(AddObserverRequest original) {
        return RemoveObserverRequest.newBuilder().setBattleId(original.getBattleId())
                .setObserverPlayerId(original.getObserverPlayerId()).setReason("rewatch")
                .setSpectatorOperationId(original.getSpectatorOperationId()).setAdmitBeforeMs(original.getAdmitBeforeMs())
                .setTargetInstanceId(original.getTargetInstanceId()).build();
    }

    private static void assertCancelled(RemoveObserverResponse response, AddObserverRequest original) {
        assertThat(response.getCancelled()).isTrue();
        assertThat(response.getSpectatorOperationId()).isEqualTo(original.getSpectatorOperationId());
        assertThat(response.getTargetInstanceId()).isEqualTo(original.getTargetInstanceId());
    }

    private String operation(long battle) throws Exception {
        return onLoop(() -> rooms.room(battle).observerOperations.get(RoomHarness.C));
    }

    private <T> T onLoop(Callable<T> read) throws Exception {
        return loop.submit(read).get(5, TimeUnit.SECONDS);
    }
}
