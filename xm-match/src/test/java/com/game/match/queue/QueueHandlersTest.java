package com.game.match.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
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
    private final QueueService service = new QueueService(new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null),
            players, store, new DefaultTicketHealing(store), new FixedRatingReader(), new FakeGatherLauncher(),
            new MatchIds(new Snowflake(7), () -> true, () -> false), metrics, () -> "ticket-1");
    private final List<MatchMethodHandler> handlers = List.of(new QueueHandlers.Join(service, metrics), new QueueHandlers.Cancel(service),
            new QueueHandlers.Status(service));
    private final MatchDispatcher dispatcher = new MatchDispatcher(REGISTRY, handlers, Runnable::run, metrics, 4500);
    private final MatchDispatcher overloaded = new MatchDispatcher(REGISTRY, handlers, REJECTING, metrics, 4500);

    private static int id(String method) {
        return REGISTRY.requireId(MatchMethods.SERVICE, method);
    }

    private static ClientCall call(String method, long sessionPlayer, ByteString body) {
        return ClientCall.newBuilder().setMessageId(id(method)).setRequestId(9).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(5).setAccount("acc")
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
        assertThat(meters.counter("xm.match.join.queue", "mode", "unknown", "outcome", "overloaded").count())
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
