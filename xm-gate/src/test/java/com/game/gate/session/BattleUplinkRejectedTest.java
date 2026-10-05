package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.gate.metrics.GateMetrics;
import com.game.net.limit.MessageLimits;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
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
 * 批次 6.2 钉住测试（inventory combat.md gate-battle-uplink-reject；battle-node-spec §3.7、§13.7）：大厅连接上发
 * {@code BattleClientPlayer} 的任何号（140 / 149 / 162 / 165 与各 Notify 号）都回 23 {1003}，不计非法包、不断连、不转发——
 * 战斗上行只走 xm-battle 直连（身份只来自票据）。用同步进来的真实契约与真实路由表，以后有人把这个服务接进
 * {@link MessageRoutes#SERVICE_BACKENDS}，这里先失败。
 */
class BattleUplinkRejectedTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;
    /** 非法包阈值取很小：12 个号要是有一个计了非法包，连接就会被断开。 */
    private static final int ILLEGAL_THRESHOLD = 1;

    private final MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLogin friend = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
            InstantSource.fixed(Instant.ofEpochSecond(NOW)), MessageRoutes.of(ids), TIP_MSG, login,
            Map.of("friend", friend), links, registry,
            // 限频取生产缺省（表外的号每秒 3 条）：每个号只发一次，不会触发
            new GateLimits(16, ILLEGAL_THRESHOLD, Duration.ZERO, MessageLimits.of(Map.of())),
            new GateMetrics(meters), new RecordingPresence());

    @Test
    void 大厅上发战斗服务的每个号_都推23的1003_不计非法包不断连不转发() throws Exception {
        EmbeddedChannel ch = verified();
        List<MessageMethod> battle = ids.all().stream().filter(m -> m.serviceName().equals("BattleClientPlayer")).toList();
        assertThat(battle).hasSize(12);

        long requestId = 1;
        for (MessageMethod method : battle) {
            ch.writeInbound(ClientRequest.newBuilder().setId(requestId++).setMessageId(method.messageId())
                    .setBody(ByteString.copyFromUtf8("battle")).build());
            MessageContent tip = ch.readOutbound();
            assertThat(tip).as(method.key()).isNotNull();
            assertThat(tip.getMessageId()).as(method.key()).isEqualTo(TIP_MSG);
            assertThat(tip.getId()).as("推送形状：id 不填").isZero();
            assertThat(TipInfoMessage.parseFrom(tip.getSerializedMessage()).getId()).as(method.key())
                    .isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).isEqualTo(1003);
            assertThat((Object) ch.readOutbound()).as(method.key() + " 只有一帧").isNull();
        }

        ClientSession session = registry.all().iterator().next();
        assertThat(session.illegalPackets).as("合法协议号，不计非法包").isZero();
        assertThat(ch.isOpen()).as("不断连").isTrue();
        assertThat(login.calls).as("不转给 login").isEmpty();
        assertThat(friend.calls).as("不转给任何后端").isEmpty();
        assertThat(links.sent).as("不转给 scene").isEmpty();
        assertThat(meters.get("xm.gate.client.requests").tag("route", MessageRoutes.BACKEND_UNSUPPORTED)
                .tag("result", "unsupported").counters())
                .extracting(c -> c.count()).containsOnly(1.0).hasSize(12);

        int login48 = ids.requireId("ClientPlayerLogin", "Login");
        ch.writeInbound(ClientRequest.newBuilder().setId(requestId).setMessageId(login48)
                .setBody(ByteString.copyFromUtf8("x")).build());
        assertThat(login.calls).as("同一连接上的正常请求照常转发").singleElement()
                .satisfies(c -> assertThat(c.getMessageId()).isEqualTo(login48));
    }

    private EmbeddedChannel verified() {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher));
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE)
                .setExpireTimestamp(NOW + 600).build().toByteString();
        ch.writeInbound(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();
        return ch;
    }
}
