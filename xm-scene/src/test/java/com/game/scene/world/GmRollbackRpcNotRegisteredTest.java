package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.contract.MessageMethod;
import com.game.proto.GmExecuteRollbackRequest;
import com.game.proto.GmQueryTransactionLogRequest;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 批次 7.2a 回归（data-ops-spec §6.2、§12.1 T-S1）：scene 侧 GM 指令 102–117（{@code SceneRollbackClientPlayer}）在 Java 版<b>没有桩</b>。
 * 服务标了玩家服务、没标客户端协议服务：伪造的 {@code ClientForward} 带这些号被丢弃、不回包；在 scene 注册它们启动即抛。
 * 基线 scene 上是 12 个 {@code TODO(P1-B)} 空桩（{@code player_rollback_handler.cpp}），只经节点路由可达；Java 没有那条入口，
 * 这组操作走 xm-data 的运维面（玩家必须离线 / 由运维持有归属后做）。
 */
class GmRollbackRpcNotRegisteredTest {

    private static final long LINK = 1;
    private static final SceneMessageIds IDS = Contracts.IDS;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SceneWorld world;
    private ClientRequestHandler handler;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        AtomicLong ids = new AtomicLong(5000);
        world = new SceneWorld(new FakeSceneTables(), IDS, sink, repo, ids::incrementAndGet, new ManualClock(),
                SceneMetrics.noop());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS);
        Scene scene = world.createScene(1);
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));
        repo.completeAll();
        sink.clear();
    }

    @Test
    void SceneRollbackClientPlayer是玩家服务但不是客户端协议服务() {
        List<MessageMethod> methods = Contracts.REGISTRY.all().stream()
                .filter(m -> m.serviceName().equals("SceneRollbackClientPlayer")).toList();
        assertThat(methods).as("12 个 GM 指令（102 / 103 / 104 / 106 / 107 / 109 / 110 / 112 / 113 / 115 / 116 / 117）").hasSize(12);
        assertThat(methods).allSatisfy(m -> {
            assertThat(m.playerService()).as(m.key()).isTrue();
            assertThat(m.clientService()).as(m.key()).isFalse();
        });
    }

    @Test
    void 伪造的ClientForward带96到117一律丢弃_不回包() {
        long requestId = 1;
        for (int messageId = 96; messageId <= 117; messageId++) {
            ByteString body = messageId == 112 ? GmExecuteRollbackRequest.getDefaultInstance().toByteString()
                    : messageId == 117 ? GmQueryTransactionLogRequest.getDefaultInstance().toByteString() : ByteString.EMPTY;
            handler.onClientForward(LINK, ClientForward.newBuilder().setSessionId(11).setPlayerId(1001)
                    .setMessageId(messageId).setBody(body).setRequestId(requestId++).build());
        }

        assertThat(sink.events()).as("没有处理器、没有应答、没有 1006 / 1005").isEmpty();
    }

    @Test
    void 在scene注册112或117启动即抛() {
        SceneFeature rollback = r -> r.on("SceneRollbackClientPlayer", "GmExecuteRollback", GmExecuteRollbackRequest.class,
                (call, req) -> { });
        SceneFeature txlog = r -> r.on("SceneRollbackClientPlayer", "GmQueryTransactionLog",
                GmQueryTransactionLogRequest.class, (call, req) -> { });

        for (SceneFeature feature : List.of(rollback, txlog)) {
            assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(feature)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是 scene 的客户端玩家服务");
        }
    }
}
