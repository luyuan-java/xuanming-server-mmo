package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.gate.metrics.GateMetrics;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 批次 7.2a 回归（data-ops-spec §6.2、§12.1 T-G1）：GM 回档 / 快照 / 欠款 / 回收指令 96–117 客户端一律不可达——
 * {@code SceneRollbackClientPlayer} / {@code DataService} / {@code LoginAdmin} 都没标客户端协议服务，gate 按「不认识的号」
 * 丢弃、计非法包、不回包、不转发，同一连接照常可用（同基线 C++ gate 白名单，{@code client_message_processor.cpp:874-886}）。
 * 这些操作在 Java 版走 game-data 的运维面（令牌 + 操作人）。以后 mmorpg 若给这些服务加了客户端协议标记，同步契约时这里会先失败。
 */
class GmRollbackMessagesUnroutableTest {

    private static final List<String> SERVICES = List.of("SceneRollbackClientPlayer", "DataService", "LoginAdmin");
    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;

    private final MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
    private final MessageRoutes routes = MessageRoutes.of(ids);

    @Test
    void 运维服务方法不可路由_已删除的回档服务不再注册() {
        assertThat(ids.all()).noneMatch(m -> m.serviceName().equals("SceneRollbackClientPlayer"));
        for (String service : List.of("DataService", "LoginAdmin")) {
            List<MessageMethod> methods = ids.all().stream().filter(m -> m.serviceName().equals(service)).toList();
            assertThat(methods).as("契约里 " + service + " 的方法").isNotEmpty();
            for (MessageMethod m : methods) {
                assertThat(m.clientService()).as(m.key()).isFalse();
                assertThat(routes.clientRoute(m.messageId())).as(m.key()).isNull();
            }
        }
    }

    @Test
    void 消息号96到117保留当前运维方法和墓碑且全部不可路由() throws Exception {
        Map<Integer, String> active = Map.of(97, "DataServiceListPlayerSnapshots",
                108, "DataServiceBatchRecallItems", 111, "LoginAdminRemovePlayersFromAccounts",
                114, "DataServiceQueryTransactionLog");
        // 已删除的方法只留下墓碑；同时检查注册表和原始契约，避免放过编号被复用。
        Map<Integer, String> contract = new java.util.HashMap<>();
        try (var stream = getClass().getClassLoader().getResourceAsStream(MessageIdRegistry.MESSAGE_ID_RESOURCE)) {
            assertThat(stream).isNotNull();
            for (String line : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) contract.put(Integer.parseInt(parts[0]), parts[1]);
            }
        }
        for (int id = 96; id <= 117; id++) {
            if (active.containsKey(id)) {
                MessageMethod m = ids.byId(id).orElseThrow();
                assertThat(m.key()).isEqualTo(active.get(id));
                assertThat(m.clientService()).isFalse();
            } else {
                assertThat(ids.byId(id)).as("墓碑号 " + id + " 不得重新注册").isEmpty();
                assertThat(contract.get(id)).as("号 " + id + " 必须保留墓碑").startsWith("reserved:");
                String tombstone = contract.get(id);
                assertThat(SERVICES).anyMatch(service -> tombstone.startsWith("reserved:" + service));
            }
            assertThat(routes.clientRoute(id)).as("号 " + id).isNull();
        }
        assertThat(contract.get(112)).isEqualTo("reserved:SceneRollbackClientPlayerGmExecuteRollback");
        assertThat(contract.get(115)).isEqualTo("reserved:SceneRollbackClientPlayerGmListSnapshots");
        assertThat(contract.get(117)).isEqualTo("reserved:SceneRollbackClientPlayerGmQueryTransactionLog");
    }

    @Test
    void 客户端直接发112_115_117_计非法包不回包不转发_阈值内连接照常可用() {
        GateTokens tokens = GateTokens.ofUtf8("test-secret");
        FakeLogin login = new FakeLogin();
        FakeLinks links = new FakeLinks();
        SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), routes, TIP_MSG, login, Map.of(), links, registry,
                new GateLimits(4, 50, Duration.ZERO), new GateMetrics(meters), new RecordingPresence());
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher));
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE)
                .setExpireTimestamp(NOW + 600).build().toByteString();
        ch.writeInbound(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();

        long requestId = 1;
        for (int messageId : new int[] {112, 115, 117}) {
            ch.writeInbound(ClientRequest.newBuilder().setId(requestId++).setMessageId(messageId)
                    .setBody(ByteString.copyFromUtf8("gm")).build());
            assertThat((Object) ch.readOutbound()).as("号 " + messageId + " 不回包（不回信封、不推 23）").isNull();
        }

        assertThat(login.calls).as("不转发给任何后端").isEmpty();
        assertThat(links.sent).as("不转给 scene").isEmpty();
        ClientSession session = registry.get((GATE_NODE << SessionIdAllocator.SEQ_BITS) | 1);
        assertThat(session.illegalPackets).as("每条计一次非法包").isEqualTo(3);
        assertThat(meters.get("game.gate.client.requests").tag("result", "unknown_message").counter().count()).isEqualTo(3);
        assertThat(ch.isOpen()).as("阈值以内连接照常可用").isTrue();

        int login48 = ids.requireId("ClientPlayerLogin", "Login");
        ch.writeInbound(ClientRequest.newBuilder().setId(requestId).setMessageId(login48)
                .setBody(ByteString.copyFromUtf8("x")).build());
        assertThat(login.calls).as("同一连接上的正常请求照常转发").singleElement()
                .satisfies(c -> assertThat(c.getMessageId()).isEqualTo(login48));
    }
}
