package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.common.token.SpectateTickets;
import com.game.contract.MessageIdRegistry;
import com.game.gate.metrics.GateMetrics;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.game.proto.SubscribeSpectateRequest;
import com.game.proto.SpectateTicketPayload;
import com.game.proto.UnsubscribeSpectateRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * gate 本地处理 {@code GateClientSpectate}（mmorpg battle-spectate-tiered-fanout.md §15.2、§15.5）：用同步进来的真实契约与路由表，凭证用真实的 HMAC。
 * 只钉会话层这一段（验凭证、回执、把合法的订阅交给订阅表）；订阅表本身见 {@code SpectateHubTest}。
 */
class ClientSpectateRequestTest {

    private static final int GATE_NODE = 3;
    private static final long PLAYER = 1001;
    private static final long BATTLE = 7_000_000_077L;
    private static final long NOW_MS = 1_800_000_000_000L;
    private static final String SECRET = "spectate-request-test-secret-0123456789abcdef";

    private static final MessageIdRegistry IDS = MessageIdRegistry.loadFromClasspath();
    private static final int SUBSCRIBE = IDS.requireId("GateClientSpectate", "SubscribeSpectate");
    private static final int UNSUBSCRIBE = IDS.requireId("GateClientSpectate", "UnsubscribeSpectate");

    /** 记录型订阅表。 */
    private final class Recording implements SpectateSubscriptions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void subscribe(ClientSession session, SpectateTicketPayload ticket) {
            calls.add("subscribe:" + ticket.getBattleId() + ":" + ticket.getBattleInstanceId());
        }

        @Override
        public void unsubscribe(ClientSession session, long battleId) {
            calls.add("unsubscribe:" + battleId);
        }

        @Override
        public void drop(ClientSession session) {
            calls.add("drop");
        }
    }

    private final SpectateTickets tickets = SpectateTickets.ofUtf8(SECRET);
    private final Recording hub = new Recording();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeLogin login = new FakeLogin();
    private final ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", 1), GateTokens.ofUtf8("gate-secret"),
            InstantSource.fixed(Instant.ofEpochMilli(NOW_MS)), MessageRoutes.of(IDS), 23, login, new FakeLinks(), registry,
            new GateLimits(8, 50, Duration.ZERO), new GateMetrics(meters), PresenceRecorder.NONE).withSpectate(tickets, hub);
    private final EmbeddedChannel channel = new EmbeddedChannel();

    @AfterEach
    void close() {
        channel.finishAndReleaseAll();
    }

    /** 已握手、已进游戏的会话（玩家绑定是登录流程的结果，这里只要它的值）。 */
    private ClientSession session(long playerId) {
        ClientSession s = registry.open(channel, "127.0.0.1");
        s.verified = true;
        s.playerId = playerId;
        return s;
    }

    private com.google.protobuf.ByteString ticket(long player, long battle, long expireAt) {
        return tickets.sign(SpectateTicketPayload.newBuilder().setBattleId(battle).setPlayerId(player).setBattleInstanceId("battle-instance-1")
                .setDeadlineMs(NOW_MS + 300_000).setExpireAtMs(expireAt).build());
    }

    private static ClientRequest subscribe(long id, long battle, com.google.protobuf.ByteString ticket) {
        return ClientRequest.newBuilder().setId(id).setMessageId(SUBSCRIBE)
                .setBody(SubscribeSpectateRequest.newBuilder().setBattleId(battle).setSpectateTicket(ticket).build().toByteString()).build();
    }

    private MessageContent reply() {
        return channel.readOutbound();
    }

    @Test
    void 合法凭证_回带请求id的成功应答_订阅交给订阅表_不转发给任何后端() {
        ClientSession s = session(PLAYER);

        dispatcher.onRequest(s, subscribe(11, BATTLE, ticket(PLAYER, BATTLE, NOW_MS + 60_000)));

        MessageContent reply = reply();
        assertThat(reply.getId()).isEqualTo(11);
        assertThat(reply.getMessageId()).isEqualTo(SUBSCRIBE);
        assertThat(reply.hasErrorMessage()).isFalse();
        assertThat(hub.calls).containsExactly("subscribe:" + BATTLE + ":battle-instance-1");
        assertThat(meters.get("game.gate.client.requests").tags("result", "local", "method", "GateClientSpectate.SubscribeSpectate").counter().count()).isEqualTo(1);
    }

    private void assertRejected(ClientRequest request, long playerId, String reason) {
        ClientSession s = session(playerId);

        dispatcher.onRequest(s, request);

        MessageContent reply = reply();
        assertThat(reply.getId()).as("带请求 id 的信封").isEqualTo(request.getId());
        assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SPECTATE_TICKET_INVALID);
        assertThat(hub.calls).as("被拒的不进订阅表").isEmpty();
        assertThat(meters.get("game.gate.spectate.ticket.rejects").tag("reason", reason).counter().count()).isEqualTo(1);
        assertThat(channel.isOpen()).as("不计非法包、不断连").isTrue();
    }

    @Test
    void 别人的凭证拒绝() {
        assertRejected(subscribe(12, BATTLE, ticket(PLAYER + 1, BATTLE, NOW_MS + 60_000)), PLAYER, "wrong_player");
    }

    @Test
    void 没进游戏的会话拒绝() {
        assertRejected(subscribe(13, BATTLE, ticket(PLAYER, BATTLE, NOW_MS + 60_000)), 0, "wrong_player");
    }

    @Test
    void 别的场次的凭证拒绝() {
        assertRejected(subscribe(14, BATTLE + 1, ticket(PLAYER, BATTLE, NOW_MS + 60_000)), PLAYER, "wrong_battle");
    }

    @Test
    void 过期的凭证拒绝_按gate墙钟() {
        assertRejected(subscribe(15, BATTLE, ticket(PLAYER, BATTLE, NOW_MS)), PLAYER, "expired");
    }

    @Test
    void 乱码凭证拒绝() {
        assertRejected(subscribe(16, BATTLE, com.google.protobuf.ByteString.copyFromUtf8("garbage")), PLAYER, "bad_format");
    }

    @Test
    void gate没配密钥_一切订阅都拒绝() {
        dispatcher.withSpectate(SpectateTickets.disabled(), hub);

        assertRejected(subscribe(17, BATTLE, ticket(PLAYER, BATTLE, NOW_MS + 60_000)), PLAYER, "no_secret");
    }

    @Test
    void 请求体解析不了_回解析错误的信封_不算凭证被拒_不进订阅表() {
        ClientSession s = session(PLAYER);
        com.google.protobuf.ByteString garbage = com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01});

        dispatcher.onRequest(s, ClientRequest.newBuilder().setId(18).setMessageId(SUBSCRIBE).setBody(garbage).build());
        dispatcher.onRequest(s, ClientRequest.newBuilder().setId(19).setMessageId(UNSUBSCRIBE).setBody(garbage).build());

        assertThat(reply().getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_REQUEST_PARSE_ERROR);
        assertThat(reply().getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_REQUEST_PARSE_ERROR);
        assertThat(hub.calls).isEmpty();
        assertThat(meters.get("game.gate.spectate.ticket.rejects").tag("reason", "bad_format").counter().count()).isZero();
    }

    @Test
    void 退订_幂等成功_回带请求id的应答() {
        ClientSession s = session(PLAYER);

        dispatcher.onRequest(s, ClientRequest.newBuilder().setId(21).setMessageId(UNSUBSCRIBE)
                .setBody(UnsubscribeSpectateRequest.newBuilder().setBattleId(BATTLE).build().toByteString()).build());

        MessageContent reply = reply();
        assertThat(reply.getId()).isEqualTo(21);
        assertThat(reply.getMessageId()).isEqualTo(UNSUBSCRIBE);
        assertThat(reply.hasErrorMessage()).isFalse();
        assertThat(hub.calls).containsExactly("unsubscribe:" + BATTLE);
    }

    @Test
    void 断线时摘除订阅() {
        ClientSession s = session(PLAYER);

        dispatcher.onDisconnected(s);

        assertThat(hub.calls).contains("drop");
    }

    @Test
    void 场景绑定结束时摘除订阅_凭证绑的是玩家() {
        ClientSession s = session(PLAYER);
        s.sceneNodeId = 5;
        s.scenePlayerId = PLAYER;

        dispatcher.onSceneLinkDown(s, 5, 0);

        assertThat(hub.calls).contains("drop");
    }
}
