package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.engine.BattleData;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.LoggingActivityResultSink;
import com.game.battle.port.LoggingBattleResultSink;
import com.game.battle.port.LoggingSceneBattleEvents;
import com.game.battle.port.LoggingSettlementSink;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.room.BattleClock;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.room.FingerprintMode;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import com.game.net.limit.MessageLimits;
import com.game.proto.CreateBattleRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.concurrent.EventExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * battle 节点的启停顺序与租约丢失处置（battle-node-spec §6.5、§6.6、§7.10、§7.11、§11 N16 / N17，Q4、Q16），用假基础设施（不连 Redis、不开端口），
 * 逻辑线程是真的单线程 EventLoop。
 */
@ExtendWith(OutputCaptureExtension.class)
class BattleNodeTest {

    static final BattleMessageIds IDS = BattleMessageIds.loadFromClasspath();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final AdmissionGate admission = new AdmissionGate();
    private final FakeBattleInfrastructure infra = new FakeBattleInfrastructure();
    private final BattleProperties props = new BattleProperties(12001, null, 12345, 21201, null, null, null,
            FingerprintMode.ENFORCE, null, Duration.ofMillis(300), null);
    private BattleNode node;

    static BattleTables tables() {
        return new BattleTables(mock(BattleData.class), "fp-test", MessageLimits.UNLIMITED, IDS);
    }

    private BattleNode node() {
        infra.phase = () -> admission.phase().wireName();
        BattleMetrics m = metrics;
        node = new BattleNode(props, RunMode.DEV, "10.1.2.3", tables(), BattleTickets.ofUtf8("x".repeat(40)),
                new OutboundPorts((pid, contents) -> { }, new LoggingSceneBattleEvents(m), new LoggingSettlementSink(m),
                        new LoggingActivityResultSink(m), new LoggingBattleResultSink(m)),
                admission, metrics, BattleClock.SYSTEM, infra);
        return node;
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
    }

    private static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    @Test
    void 启动顺序_租约_房间_导出_直连面_开闸_最后进目录() {
        node().start();

        assertThat(infra.eventsMatching("lease.", "rooms.create", "rpc.", "edge.start", "directory.publish")).containsExactly(
                "lease.acquire",
                "rooms.create",
                "rpc.export:10.1.2.3:21201",
                "edge.start@not_started",
                "directory.publish(accepting=true)");
        assertThat(admission.phase()).isEqualTo(AdmissionPhase.OPEN);
        assertThat(node.isRunning()).isTrue();
        assertThat(node.controlPlane()).isPresent();

        BattleIdentity identity = node.identity().orElseThrow();
        assertThat(identity.nodeId()).isEqualTo(7);
        assertThat(identity.instanceId()).isEqualTo(infra.instanceId).isEqualTo(node.instanceId());
        assertThat(UUID.fromString(identity.instanceId())).isNotNull();
        assertThat(identity.advertiseHost()).isEqualTo("10.1.2.3");
        assertThat(identity.advertisePort()).as("advertise-port 非 0 时用它").isEqualTo(12345);

        var info = infra.published.get(0);
        assertThat(info.getNodeId()).isEqualTo(7);
        assertThat(info.getInstanceId()).isEqualTo(node.instanceId());
        assertThat(info.getRpcHost()).isEqualTo("10.1.2.3");
        assertThat(info.getRpcPort()).isEqualTo(21234);
        assertThat(info.getClientHost()).isEqualTo("10.1.2.3");
        assertThat(info.getClientPort()).isEqualTo(12345);
        assertThat(info.getTableFingerprint()).isEqualTo("fp-test");
        assertThat(infra.events).contains("directory:7");
    }

    @Test
    void 单逻辑线程_既是直连面唯一的EventLoop_也是房间的执行线程() {
        node().start();

        EventLoopBattleScheduler scheduler = (EventLoopBattleScheduler) infra.roomDeps.scheduler();
        List<EventExecutor> loops = new ArrayList<>();
        infra.edgeDeps.logicGroup().forEach(loops::add);
        assertThat(loops).as("逻辑线程组只有一个 EventLoop").hasSize(1);
        assertThat(loops.get(0)).isSameAs(scheduler.eventLoop());
        assertThat(infra.edgeDeps.rooms()).isSameAs(infra.rooms);
        assertThat(infra.edgeDeps.identity()).isEqualTo(infra.roomDeps.identity());
        assertThat(infra.edgeDeps.tickets()).isSameAs(infra.roomDeps.tickets());
        assertThat(infra.roomDeps.fingerprintMode()).isEqualTo(FingerprintMode.ENFORCE);
        assertThat(infra.roomDeps.tableFingerprint()).isEqualTo("fp-test");
        assertThat(infra.edgeDeps.properties()).isSameAs(props);

        CompletableFuture<String> thread = new CompletableFuture<>();
        scheduler.execute(() -> thread.complete(Thread.currentThread().getName()));
        assertThat(thread.join()).startsWith("battle-logic");
    }

    @Test
    void 指标Gauge绑定到房间数_直连数_准入闸() {
        node().start();
        infra.rooms.rooms.set(3);
        infra.connections = 9;

        assertThat(registry.get("xm.battle.rooms").gauge().value()).isEqualTo(3);
        assertThat(registry.get("xm.battle.direct.connections").gauge().value()).isEqualTo(9);
        assertThat(registry.get("xm.battle.admission.phase").gauge().value()).isEqualTo(1);
        assertThat(registry.get("xm.battle.logic.pending.tasks").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 停机顺序_删目录_同一任务里关闸并作废房间_排空直连_反导出_交还租约() {
        node().start();
        infra.events.clear();

        node.stop();

        assertThat(infra.events).containsExactly(
                "directory.remove",
                "rooms.abortAll:node_shutdown@closed",
                "edge.stopAccepting",
                "edge.drainAndClose:300ms",
                "rpc.close",
                "lease.close");
        assertThat(infra.rooms.threads).allMatch(t -> t.startsWith("battle-logic"));
        assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(node.isRunning()).isFalse();
        assertThat(node.controlPlane()).isEmpty();
        node.stop();
        assertThat(infra.events).as("重复 stop 是空操作").hasSize(6);
    }

    @Test
    void 反导出期间进来的建房_准入闸已关_NOT_ALLOCATABLE() throws Exception {
        node().start();
        List<CreateBattleResult> duringUnexport = new ArrayList<>();
        infra.onRpcClose = () -> duringUnexport.add(infra.provider.createBattle(CreateBattleRequest.newBuilder()
                .setBattleId(9).build()).join());

        node.stop();

        assertThat(duringUnexport).singleElement().satisfies(r -> {
            assertThat(r.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
            assertThat(r.getReason()).isEqualTo("closed");
        });
        assertThat(infra.rooms.calls).doesNotContain("createBattle");
    }

    @Test
    void 开闸之后的建房走到房间服务() throws Exception {
        node().start();

        CreateBattleResult result = node.controlPlane().orElseThrow()
                .createBattle(CreateBattleRequest.newBuilder().setBattleId(5).build()).get(5, TimeUnit.SECONDS);

        assertThat(result.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(infra.rooms.calls).containsExactly("createBattle");
        assertThat(infra.rooms.threads).singleElement().satisfies(t -> assertThat(t).startsWith("battle-logic"));
    }

    @Test
    void 租约丢失_关闸_删本实例条目_计数_不作废房间_进程保持存活(CapturedOutput output) throws Exception {
        node().start();
        infra.rooms.rooms.set(2);
        infra.events.clear();

        infra.loseLease();

        eventually(() -> assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED));
        assertThat(infra.events).contains("directory.remove");
        assertThat(infra.rooms.calls).as("在打的房间不作废").noneMatch(c -> c.startsWith("abortAll"));
        assertThat(registry.get("xm.battle.lease.lost").counter().count()).isEqualTo(1);
        assertThat(node.isRunning()).isTrue();
        assertThat(output.getOut()).doesNotContain("房间已全部结束：可安全重启");

        CreateBattleResult after = node.controlPlane().orElseThrow()
                .createBattle(CreateBattleRequest.newBuilder().setBattleId(5).build()).get(5, TimeUnit.SECONDS);
        assertThat(after.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(after.getReason()).isEqualTo("closed");

        infra.events.clear();
        node.stop();
        assertThat(infra.events).as("停机：不再删目录（丢失时已处理过），照常作废剩余房间并交还")
                .containsExactly("rooms.abortAll:node_shutdown@closed", "edge.stopAccepting", "edge.drainAndClose:300ms",
                        "rpc.close", "lease.close");
    }

    @Test
    void 租约丢失时条目已归同号新实例_不删() throws Exception {
        node().start();
        infra.current = infra.current.toBuilder().setInstanceId("someone-else").build();
        infra.events.clear();

        infra.loseLease();

        eventually(() -> assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED));
        assertThat(infra.events).doesNotContain("directory.remove");
        assertThat(infra.current.getInstanceId()).isEqualTo("someone-else");
    }

    @Test
    void 租约丢失时已没有房间_立即打可安全重启(CapturedOutput output) throws Exception {
        node().start();

        infra.loseLease();

        eventually(() -> assertThat(output.getOut()).contains("房间已全部结束：可安全重启"));
    }

    @Test
    void 启动途中租约丢失_准入闸保持关闭_不进目录() {
        infra.onEdgeStart = infra::loseLease;

        node().start();

        assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(infra.published).isEmpty();
        assertThat(node.isRunning()).as("进程保持存活但不可分配").isTrue();
    }

    @Test
    void 启动失败_逆序释放_准入闸关闭_拒绝启动() {
        infra.onEdgeStart = () -> {
            throw new IllegalStateException("直连端口被占用");
        };

        assertThatThrownBy(() -> node().start()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("battle 节点启动失败").hasRootCauseMessage("直连端口被占用");

        assertThat(infra.eventsMatching("rooms.abortAll", "rpc.close", "lease.close")).containsExactly(
                "rooms.abortAll:node_shutdown@closed", "rpc.close", "lease.close");
        assertThat(infra.published).isEmpty();
        assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED);
        assertThat(node.isRunning()).isFalse();
        assertThat(node.controlPlane()).isEmpty();
    }

    @Test
    void 配了客户端通告地址_票据与目录client_host用它_Dubbo导出与rpc_host仍用控制面通告地址() {
        BattleProperties split = new BattleProperties(12001, null, 32001, 21201, null, null, null, null, null, null,
                " battle.example.com ");
        BattleNode n = new BattleNode(split, RunMode.PROD, "xm-battle", tables(), BattleTickets.ofUtf8("x".repeat(40)),
                new OutboundPorts((pid, contents) -> { }, new LoggingSceneBattleEvents(metrics), new LoggingSettlementSink(metrics),
                        new LoggingActivityResultSink(metrics), new LoggingBattleResultSink(metrics)),
                admission, metrics, BattleClock.SYSTEM, infra);
        node = n;
        n.start();

        BattleIdentity identity = n.identity().orElseThrow();
        assertThat(identity.advertiseHost()).as("票据里的直连主机（177 / 补签都从身份取）").isEqualTo("battle.example.com");
        assertThat(identity.advertisePort()).isEqualTo(32001);
        assertThat(infra.roomDeps.identity()).as("房间签票用同一个身份").isEqualTo(identity);
        assertThat(infra.edgeDeps.identity()).isEqualTo(identity);
        assertThat(infra.events).as("Dubbo 导出用控制面通告地址").contains("rpc.export:xm-battle:21201");
        var info = infra.published.get(0);
        assertThat(info.getRpcHost()).isEqualTo("xm-battle");
        assertThat(info.getClientHost()).isEqualTo("battle.example.com");
        assertThat(info.getClientPort()).isEqualTo(32001);
    }

    @Test
    void 没配客户端通告地址_票据与目录都取控制面通告地址() {
        node().start();
        assertThat(node.identity().orElseThrow().advertiseHost()).isEqualTo("10.1.2.3");
        assertThat(infra.published.get(0).getClientHost()).isEqualTo("10.1.2.3");
        assertThat(infra.published.get(0).getRpcHost()).isEqualTo("10.1.2.3");
    }

    @Test
    void 通告端口为0时用直连端口_通告地址不能为空() {
        BattleProperties defaults = BattleProperties.defaults();
        BattleNode n = new BattleNode(defaults, RunMode.PROD, "h", tables(), BattleTickets.ofUtf8("x".repeat(40)),
                new OutboundPorts((pid, contents) -> { }, new LoggingSceneBattleEvents(metrics), new LoggingSettlementSink(metrics),
                        new LoggingActivityResultSink(metrics), new LoggingBattleResultSink(metrics)),
                admission, metrics, BattleClock.SYSTEM, infra);
        node = n;
        n.start();
        assertThat(n.identity().orElseThrow().advertisePort()).isEqualTo(12000);

        assertThatThrownBy(() -> new BattleNode(defaults, RunMode.PROD, " ", tables(), BattleTickets.ofUtf8("x".repeat(40)),
                new OutboundPorts((pid, contents) -> { }, new LoggingSceneBattleEvents(metrics), new LoggingSettlementSink(metrics),
                        new LoggingActivityResultSink(metrics), new LoggingBattleResultSink(metrics)),
                new AdmissionGate(), metrics, BattleClock.SYSTEM, infra)).isInstanceOf(IllegalArgumentException.class);
    }
}
