package com.game.match.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.GameNamespace;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.RedisKeys;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FixedRatingReader;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketState;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.CancelQueueRequest;
import com.game.proto.match.GetQueueStatusRequest;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 排队三个号经派发器之后回给 gate 的 {@link ClientReply}（match-spec §8.1 的表里 157 / 148 / 153 三行、每一列）：正常、会话没绑定玩家、
 * 请求体解析失败、依赖故障、过载（M29）。处理器接在真的 {@link MatchDispatcher} 上（同步执行器），消息号从契约的号表取。
 */
class QueueHandlersTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final long A = 1001;
    private static final long VICTIM = 2002;
    private static final QueueRef Q_1V1 = new QueueRef(3, 0);
    private static final ByteString GARBAGE = ByteString.copyFrom(new byte[] {(byte) 0xFF});
    private static final Executor REJECTING = task -> {
        throw new RejectedExecutionException("满了");
    };

    private final InMemoryTicketStore store = new InMemoryTicketStore(new ManualRedisClock());
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final QueueService service = new QueueService(new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null),
            players, store, new DefaultTicketHealing(store), new FixedRatingReader(), new FakeGatherLauncher(),
            new MatchIds(new Snowflake(7), () -> true, () -> false), metrics, REDIS_KEYS, () -> "ticket-1");
    private final List<MatchMethodHandler> handlers = List.of(new QueueHandlers.Join(service, metrics), new QueueHandlers.Cancel(service),
            new QueueHandlers.Status(service));
    private final MatchDispatcher dispatcher = new MatchDispatcher(REGISTRY, handlers, Runnable::run, metrics, 4500);
    private final MatchDispatcher overloaded = new MatchDispatcher(REGISTRY, handlers, REJECTING, metrics, 4500);

    private static int id(String method) {
        return REGISTRY.requireId(MatchMethods.SERVICE, method);
    }

    private static ClientCall call(String method, long sessionPlayer, ByteString body) {
        return ClientCall.newBuilder().setMessageId(id(method)).setRequestId(9).setBody(body)
                // 会话按 FakePlayerStatus.online 的在线条目约定构造:157 的跨会话栅栏会比对会话号与 gate 实例。
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId(FakePlayerStatus.GATE_INSTANCE)
                        .setSessionId(FakePlayerStatus.sessionIdOf(sessionPlayer)).setAccount("acc")
                        .setPlayerId(sessionPlayer))
                .build();
    }

    private static ClientCall call(String method, long sessionPlayer, MessageLite request) {
        return call(method, sessionPlayer, request.toByteString());
    }

    private static ClientReply send(MatchDispatcher via, ClientCall call) throws Exception {
        return via.dispatch(call).get(5, TimeUnit.SECONDS);
    }

    private static void assertEnvelope1003(ClientReply reply) {
        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(reply.getBody().isEmpty()).isTrue();
        assertThat(reply.getTipParametersList()).as("信封不带 parameters").isEmpty();
        assertThat(reply.getDirectivesList()).isEmpty();
    }

    private static ByteString busy() {
        return JoinQueueResponse.newBuilder().setErrorCode(16004)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16004).addParameters("服务器繁忙,请稍后再试")).build().toByteString();
    }

    private static JoinQueueRequest join1v1() {
        return JoinQueueRequest.newBuilder().setModeValue(3).build();
    }

    // ================================================================ 登记

    @Test
    void 三个处理器对上契约的三个号_都进工作池() {
        assertThat(id(MatchMethods.JOIN_QUEUE)).isEqualTo(157);
        assertThat(id(MatchMethods.CANCEL_QUEUE)).isEqualTo(148);
        assertThat(id(MatchMethods.GET_QUEUE_STATUS)).isEqualTo(153);
        assertThat(dispatcher.handledMessageIds()).containsExactly(148, 153, 157);
        assertThat(handlers).extracting(MatchMethodHandler::method).containsExactly("JoinQueue", "CancelQueue", "GetQueueStatus");
        assertThat(handlers).as("都要等 Redis，不许在 Dubbo 线程上当场回").noneMatch(MatchMethodHandler::inline);
    }

    // ================================================================ 157

    @Test
    void 排队_正常_应答体是JoinQueueResponse_信封为0() throws Exception {
        players.online(A, 1, 7);

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(reply.getTipId()).isZero();
        assertThat(JoinQueueResponse.parseFrom(reply.getBody())).isEqualTo(JoinQueueResponse.newBuilder().setQueueTicket("ticket-1").build());
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
    }

    @Test
    void 排队_来自已被取代的会话_inband16020_不建票不入队() throws Exception {
        // 跨会话栅栏(mmorpg client-request-ordering.md §6.3 / O13):玩家已在新会话上重连,在线目录的会话号换了;
        // 旧会话上迟到的 157 不得建票,否则玩家会在新会话里「取消成功、实际仍在排队」。
        players.online(A, 1, 7);
        players.presence(A, PlayerPresence.newBuilder().setPlayerId(A).setZoneId(1).setGateNodeId(1)
                .setGateInstanceId(FakePlayerStatus.GATE_INSTANCE).setSessionId(FakePlayerStatus.sessionIdOf(A) + 1).setOwnerEpoch(2).build());

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(reply.getTipId()).as("业务拒绝在应答体里,不走信封").isZero();
        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
    }

    @Test
    void 排队_换了gate进程实例的会话同样被拒() throws Exception {
        players.online(A, 1, 7);
        players.presence(A, PlayerPresence.newBuilder().setPlayerId(A).setZoneId(1).setGateNodeId(1)
                .setGateInstanceId("gate-inst-restarted").setSessionId(FakePlayerStatus.sessionIdOf(A)).setOwnerEpoch(2).build());

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).isEmpty();
    }

    @Test
    void 排队_在线目录还没写到_照常受理() throws Exception {
        // 在线目录由 gate 在 scene 确认进场之后异步写入:刚进场就点匹配时可能还没有条目,缺席按放行(位置已要求 ONLINE)。
        players.location(A, 1, 7);

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isZero();
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
    }

    // ---------------------------------------------------------------- 跨会话栅栏按 owner_epoch 认残留条目
    // 在线目录条目与位置记录都带这次进场夺得的 owner_epoch(按玩家单调,同一次进场两边同值)。条目代次小于位置记录 = 上一次进场的残留,按缺席;
    // 不小于 = 当前这次进场的条目,照常比会话号(代次 0 = 未知,也照常比)。下面几例的旧会话 = 会话号 sessionIdOf(A),新会话 = sessionIdOf(A) + 1。

    private static final int OLD_SESSION = FakePlayerStatus.sessionIdOf(A);
    private static final int NEW_SESSION = OLD_SESSION + 1;

    private static PlayerPresence presenceOf(String gateInstance, int sessionId, long ownerEpoch) {
        return PlayerPresence.newBuilder().setPlayerId(A).setZoneId(1).setGateNodeId(1).setGateInstanceId(gateInstance).setSessionId(sessionId)
                .setOwnerEpoch(ownerEpoch).build();
    }

    private static ClientCall joinFrom(int sessionId) {
        return call(MatchMethods.JOIN_QUEUE, A, join1v1()).toBuilder()
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId(FakePlayerStatus.GATE_INSTANCE).setSessionId(sessionId)
                        .setAccount("acc").setPlayerId(A))
                .build();
    }

    @Test
    void 排队_在线目录残留旧会话的条目_代次小于位置记录_新会话照常受理() throws Exception {
        // PARITY「客户端请求按会话保序」的已知残留:旧 gate 崩溃没撤条目,或新 gate 写目录失败(续期脚本遇值不一致不覆盖,最长留到 TTL 60 s)。
        // 位置记录已由新一次进场(owner_epoch 2)写下,条目还是旧会话的(owner_epoch 1):按缺席放行,不误拒合法的新会话。
        players.location(A, 1, 7, 2);
        players.presence(A, presenceOf("gate-inst-crashed", OLD_SESSION, 1));

        ClientReply reply = send(dispatcher, joinFrom(NEW_SESSION));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody())).isEqualTo(JoinQueueResponse.newBuilder().setQueueTicket("ticket-1").build());
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
    }

    @Test
    void 排队_在线目录是新会话的条目_位置记录还没跟上_旧会话迟到的157_inband16020() throws Exception {
        // gate 写目录与 scene 写位置记录之间没有先后:新会话的条目(owner_epoch 3)可能先于位置记录(还是 2)落地。条目代次更大,它就是当前的。
        players.location(A, 1, 7, 2);
        players.presence(A, presenceOf(FakePlayerStatus.GATE_INSTANCE, NEW_SESSION, 3));

        ClientReply reply = send(dispatcher, joinFrom(OLD_SESSION));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
    }

    @Test
    void 排队_条目代次与位置记录相等_不算残留_旧会话被拒_新会话受理() throws Exception {
        // 边界:相等 = 条目与位置记录出自同一次进场(新会话进场完毕的常态),条目是当前的。按「大于」判当前会把旧会话迟到的 157 放进来。
        players.location(A, 1, 7, 2);
        players.presence(A, presenceOf(FakePlayerStatus.GATE_INSTANCE, NEW_SESSION, 2));

        ClientReply stale = send(dispatcher, joinFrom(OLD_SESSION));
        assertThat(JoinQueueResponse.parseFrom(stale.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).as("旧会话不得建票").isEmpty();

        ClientReply current = send(dispatcher, joinFrom(NEW_SESSION));
        assertThat(JoinQueueResponse.parseFrom(current.getBody())).isEqualTo(JoinQueueResponse.newBuilder().setQueueTicket("ticket-1").build());
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
    }

    @Test
    void 排队_位置记录缺失_哪怕在线目录是本会话的条目也回16020_不读在线目录() throws Exception {
        // 边界:没有位置记录就没有代次可比,也说明此刻没有节点持有这名玩家——第 8 步先回 16020,栅栏(读在线目录)走不到。
        players.presence(A, presenceOf(FakePlayerStatus.GATE_INSTANCE, OLD_SESSION, 1));

        ClientReply reply = send(dispatcher, joinFrom(OLD_SESSION));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(players.reads).contains("location:1001").doesNotContain("presence:1001");
    }

    @Test
    void 排队_条目代次为0_代次未知不算残留_照常比会话号_inband16020() throws Exception {
        // 代次 0 = 字段缺失 / 旧版本 / 损坏的写入(gate 只用铸出的代次写,正常不会是 0)。按「0 < 位置记录的 2」认成残留会把它放过去(fail-open);
        // 代次未知时退回放宽之前的口径:会话号不同就拒。
        players.location(A, 1, 7, 2);
        players.presence(A, presenceOf(FakePlayerStatus.GATE_INSTANCE, NEW_SESSION, 0));

        ClientReply reply = send(dispatcher, joinFrom(OLD_SESSION));

        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getErrorCode()).isEqualTo(16020);
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
    }

    @Test
    void 排队_会话没绑定玩家_inband16004缺少玩家身份_请求体里的player_id不算数() throws Exception {
        players.online(VICTIM, 1, 7);

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, 0, JoinQueueRequest.newBuilder().setPlayerId(VICTIM).setModeValue(3).build()));

        assertThat(reply.getTipId()).as("业务拒绝在应答体里，不走信封").isZero();
        assertThat(reply.getBody()).isEqualTo(JoinQueueResponse.newBuilder().setErrorCode(16004)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16004).addParameters("缺少玩家身份")).build().toByteString());
        assertThat(store.ticketOf(VICTIM)).as("没进游戏的会话不能替别人排队（修基线 B1）").isEmpty();
    }

    @Test
    void 排队_请求体解析失败_信封1003_先于身份检查() throws Exception {
        assertEnvelope1003(send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, GARBAGE)));
        assertEnvelope1003(send(dispatcher, call(MatchMethods.JOIN_QUEUE, 0, GARBAGE)));
    }

    @Test
    void 排队_依赖故障_inband16004服务器繁忙() throws Exception {
        players.online(A, 1, 7);
        players.failLock(A);

        ClientReply reply = send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody()).isEqualTo(busy());
    }

    @Test
    void 排队_工作池满_inband16004服务器繁忙_不碰任何依赖_指标记overloaded() throws Exception {
        players.online(A, 1, 7);

        ClientReply reply = send(overloaded, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody()).isEqualTo(busy());
        assertThat(players.reads).isEmpty();
        assertThat(store.calls).isEmpty();
        assertThat(meters.counter("game.match.join.queue", "mode", "unknown", "outcome", "overloaded").count())
                .as("过载时请求体没解析过：模式未知").isEqualTo(1);
    }

    // ================================================================ 148

    @Test
    void 取消_成功_应答体0字节_信封为0_gate据此不回包() throws Exception {
        players.online(A, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        ClientReply reply = send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.newBuilder().setQueueTicket("ticket-1").build()));

        assertThat(reply).as("Empty、tip 0：全默认的应答").isEqualTo(ClientReply.getDefaultInstance());
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
    }

    @Test
    void 取消_票号不符_太迟_没有票_会话没绑定玩家_都是静默成功() throws Exception {
        players.online(A, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertThat(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.newBuilder().setQueueTicket("stale").build())))
                .isEqualTo(ClientReply.getDefaultInstance());
        assertThat(store.ticketOf(A).orElseThrow().state()).as("票号不符不动").isEqualTo(TicketState.QUEUED);
        assertThat(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, 3003, CancelQueueRequest.getDefaultInstance()))).as("没有票")
                .isEqualTo(ClientReply.getDefaultInstance());
        assertThat(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, 0, CancelQueueRequest.newBuilder().setPlayerId(A).build())))
                .as("会话没绑定玩家").isEqualTo(ClientReply.getDefaultInstance());
        assertThat(store.ticketOf(A)).as("没进游戏的会话不能替别人取消").isPresent();
    }

    @Test
    void 取消_身份只认会话_请求体里填别人的player_id取消不了别人的票() throws Exception {
        players.online(VICTIM, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, VICTIM, join1v1()));

        ClientReply reply = send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.newBuilder().setPlayerId(VICTIM).build()));

        assertThat(reply).isEqualTo(ClientReply.getDefaultInstance());
        assertThat(store.ticketOf(VICTIM)).isPresent();
        assertThat(store.queueMembers(Q_1V1)).containsExactly("2002");
    }

    @Test
    void 取消_请求体解析失败_存储故障_过载_都是信封1003() throws Exception {
        players.online(A, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        assertEnvelope1003(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, GARBAGE)));
        store.faults.failNext("read");
        assertEnvelope1003(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.getDefaultInstance())));
        store.faults.failNext("cancel");
        assertEnvelope1003(send(dispatcher, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.getDefaultInstance())));
        assertEnvelope1003(send(overloaded, call(MatchMethods.CANCEL_QUEUE, A, CancelQueueRequest.getDefaultInstance())));

        assertThat(store.ticketOf(A)).as("哪一次都没取消成").isPresent();
    }

    // ================================================================ 153

    @Test
    void 查状态_正常_应答体是GetQueueStatusResponse() throws Exception {
        players.online(A, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, A, join1v1()));

        ClientReply reply = send(dispatcher, call(MatchMethods.GET_QUEUE_STATUS, A, GetQueueStatusRequest.getDefaultInstance()));

        assertThat(reply.getTipId()).isZero();
        assertThat(GetQueueStatusResponse.parseFrom(reply.getBody())).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(1).build());
    }

    @Test
    void 查状态_会话没绑定玩家回state5_身份只认会话_查不到别人的排队() throws Exception {
        players.online(VICTIM, 1, 7);
        send(dispatcher, call(MatchMethods.JOIN_QUEUE, VICTIM, join1v1()));
        ByteString notQueued = GetQueueStatusResponse.newBuilder().setStateValue(5).build().toByteString();

        ClientReply anonymous = send(dispatcher, call(MatchMethods.GET_QUEUE_STATUS, 0, GetQueueStatusRequest.newBuilder().setPlayerId(VICTIM).build()));
        ClientReply other = send(dispatcher, call(MatchMethods.GET_QUEUE_STATUS, A, GetQueueStatusRequest.newBuilder().setPlayerId(VICTIM).build()));

        assertThat(anonymous.getTipId()).isZero();
        assertThat(anonymous.getBody()).isEqualTo(notQueued);
        assertThat(other.getBody()).isEqualTo(notQueued);
    }

    @Test
    void 查状态_请求体解析失败_读票故障_过载_都是信封1003() throws Exception {
        assertEnvelope1003(send(dispatcher, call(MatchMethods.GET_QUEUE_STATUS, A, GARBAGE)));
        store.faults.failNext("status");
        assertEnvelope1003(send(dispatcher, call(MatchMethods.GET_QUEUE_STATUS, A, GetQueueStatusRequest.getDefaultInstance())));
        assertEnvelope1003(send(overloaded, call(MatchMethods.GET_QUEUE_STATUS, A, GetQueueStatusRequest.getDefaultInstance())));
    }
}
