package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientForward;
import com.game.common.RunMode;
import com.game.contract.MessageIdRegistry;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 旧 SceneRollbackClientPlayer 服务已从共享协议删除，12 个消息号保留墓碑，不得重新注册或复用。
 * 伪造 ClientForward 携带这些号或仍存的内部运维号，均在解析请求前丢弃且不回包。
 * Java 回档操作沿 game-data 的运维面执行；此测试只检查 scene 不会开放已删除的入口。
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
    void 已删除回档服务没有注册且十二个消息号保留原名墓碑() throws Exception {
        assertThat(Contracts.REGISTRY.all()).noneMatch(m -> m.serviceName().equals("SceneRollbackClientPlayer"));
        List<String> tombstones = List.of(
                "102=reserved:SceneRollbackClientPlayerGmAttachDebt",
                "103=reserved:SceneRollbackClientPlayerGmCreateSnapshot",
                "104=reserved:SceneRollbackClientPlayerGmTraceItem",
                "106=reserved:SceneRollbackClientPlayerGmWaiveDebt",
                "107=reserved:SceneRollbackClientPlayerGmQueryDebt",
                "109=reserved:SceneRollbackClientPlayerGmFreezeDebt",
                "110=reserved:SceneRollbackClientPlayerGmClawbackItem",
                "112=reserved:SceneRollbackClientPlayerGmExecuteRollback",
                "113=reserved:SceneRollbackClientPlayerGmAdjustDebt",
                "115=reserved:SceneRollbackClientPlayerGmListSnapshots",
                "116=reserved:SceneRollbackClientPlayerGmPreviewRollback",
                "117=reserved:SceneRollbackClientPlayerGmQueryTransactionLog");
        try (var stream = getClass().getClassLoader().getResourceAsStream(MessageIdRegistry.MESSAGE_ID_RESOURCE)) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()).containsAll(tombstones);
        }
        for (String tombstone : tombstones) {
            int messageId = Integer.parseInt(tombstone.substring(0, tombstone.indexOf('=')));
            assertThat(Contracts.REGISTRY.byId(messageId)).as(tombstone).isEmpty();
        }
    }

    @Test
    void 伪造的ClientForward带96到117一律丢弃_不回包() {
        long requestId = 1;
        for (int messageId = 96; messageId <= 117; messageId++) {
            for (ByteString body : List.of(ByteString.EMPTY, ByteString.copyFrom(new byte[] {(byte) 0xFF}))) {
                handler.onClientForward(LINK, ClientForward.newBuilder().setSessionId(11).setPlayerId(1001)
                        .setMessageId(messageId).setBody(body).setRequestId(requestId++).build());
            }
        }

        assertThat(sink.events()).as("没有处理器、没有应答、没有 1006 / 1005").isEmpty();
    }

    @Test
    void 在scene注册已删除的回档或流水方法启动即抛() {
        for (String method : List.of("GmExecuteRollback", "GmQueryTransactionLog")) {
            // Message 仅为注册接口的类型占位；服务查号即失败，不需要恢复已删除的 proto 类型。
            SceneFeature feature = r -> r.on("SceneRollbackClientPlayer", method, Message.class, (call, req) -> { });
            assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(feature)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("message_id.txt 中没有 SceneRollbackClientPlayer." + method);
        }
    }

    @Test
    void 在scene注册仍存的非客户端运维方法启动即抛() {
        SceneFeature feature = r -> r.on("DataService", "QueryTransactionLog", Message.class, (call, req) -> { });
        assertThatThrownBy(() -> new ClientRequestHandler(world, Contracts.REGISTRY, IDS, RunMode.DEV, List.of(feature)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 scene 的客户端玩家服务");
    }
}
