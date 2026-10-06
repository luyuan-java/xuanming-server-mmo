package com.game.match.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 派发器（match-spec §9.9、§8.1 的应答规则里归派发层的那几列）：处理器的登记规则；没有处理器 → 信封 1003；inline 的当场执行、其余投工作池；
 * 截止从受理时刻起算；工作池拒收或排队超预算 → 该方法自己的过载应答（M29）；解析失败与未分类异常 → 信封 1003；future 永不异常完成。
 */
class MatchDispatcherTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));

    /** 可配置的处理器。 */
    private static final class Handler implements MatchMethodHandler {
        final String method;
        final boolean inline;
        final AtomicInteger handled = new AtomicInteger();
        final AtomicInteger overloads = new AtomicInteger();
        final AtomicReference<SessionContext> session = new AtomicReference<>();
        final AtomicReference<Deadline> deadline = new AtomicReference<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        volatile Reply reply = Reply.empty();
        volatile Reply overloadReply = Reply.envelope(MatchTips.SERVICE_UNAVAILABLE);
        volatile RuntimeException error;
        volatile RuntimeException overloadError;
        volatile boolean parse;

        Handler(String method, boolean inline) {
            this.method = method;
            this.inline = inline;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public boolean inline() {
            return inline;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            handled.incrementAndGet();
            this.session.set(session);
            this.deadline.set(deadline);
            this.thread.set(Thread.currentThread());
            if (parse) {
                WatchBattleRequest.parseFrom(body);
            }
            if (error != null) {
                throw error;
            }
            return reply;
        }

        @Override
        public Reply onOverload() {
            overloads.incrementAndGet();
            if (overloadError != null) {
                throw overloadError;
            }
            return overloadReply;
        }
    }

    /** 手动推进的工作池：任务先排着，测试决定何时执行（模拟排队）；可以设成拒收。 */
    private static final class ManualWorkers implements Executor {
        final Deque<Runnable> queued = new ArrayDeque<>();
        volatile boolean reject;

        @Override
        public void execute(Runnable task) {
            if (reject) {
                throw new RejectedExecutionException("队列已满");
            }
            queued.add(task);
        }

        void runAll() {
            Runnable task;
            while ((task = queued.poll()) != null) {
                task.run();
            }
        }
    }

    private final ManualWorkers workers = new ManualWorkers();

    private MatchDispatcher dispatcher(MatchMethodHandler... handlers) {
        return dispatcher(4500, handlers);
    }

    private MatchDispatcher dispatcher(long budgetMillis, MatchMethodHandler... handlers) {
        return new MatchDispatcher(REGISTRY, List.of(handlers), workers, metrics, budgetMillis);
    }

    private static ClientCall call(int messageId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setBody(body).setRequestId(9)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(5).setPlayerId(1001).setAccount("acc")).build();
    }

    private static ClientReply reply(CompletableFuture<ClientReply> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private double requests(String method, String result) {
        return meters.get("xm.match.requests").tag("method", method).tag("result", result).timer().count();
    }

    // ================================================================ 登记

    @Test
    void 一个处理器都没有_十个号与契约外的号都回信封1003_不进工作池() throws Exception {
        MatchDispatcher dispatcher = dispatcher();

        assertThat(dispatcher.handledMessageIds()).isEmpty();
        for (int messageId : List.of(157, 148, 153, 152, 151, 156, 154, 163, 164, 179, 1, 99999)) {
            ClientReply reply = reply(dispatcher.dispatch(call(messageId, ByteString.EMPTY)));
            assertThat(reply.getTipId()).as("message_id=%d", messageId).isEqualTo(1003);
            assertThat(reply.getTipParametersList()).isEmpty();
            assertThat(reply.getBody().isEmpty()).isTrue();
            assertThat(reply.getDirectivesList()).isEmpty();
        }
        assertThat(workers.queued).isEmpty();
        assertThat(requests("unknown", "unsupported")).isEqualTo(12);
    }

    @Test
    void 处理器按方法名登记到消息号() {
        MatchDispatcher dispatcher = dispatcher(new Handler(MatchMethods.WATCH_BATTLE, true), new Handler(MatchMethods.JOIN_QUEUE, false),
                new Handler(MatchMethods.REQUEST_BATTLE_TICKET, false));

        assertThat(dispatcher.handledMessageIds()).containsExactly(157, 163, 179);
    }

    @Test
    void 同一个方法两个处理器_构造即失败() {
        assertThatThrownBy(() -> dispatcher(new Handler(MatchMethods.JOIN_QUEUE, false), new Handler(MatchMethods.JOIN_QUEUE, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JoinQueue").hasMessageContaining("两个处理器");
    }

    @Test
    void 方法名不在契约的MatchService里_构造即失败() {
        assertThatThrownBy(() -> dispatcher(new Handler("StartActivityBattle", false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("StartActivityBattle");
        assertThatThrownBy(() -> dispatcher(new Handler("joinQueue", false))).as("大小写敏感").isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ inline

    @Test
    void inline处理器在调用线程上当场执行_不进工作池_应答体原样带回() throws Exception {
        Handler watch = new Handler(MatchMethods.WATCH_BATTLE, true);
        watch.reply = Reply.body(MatchTips.watchUnavailable());

        CompletableFuture<ClientReply> future = dispatcher(watch).dispatch(call(163, WatchBattleRequest.newBuilder().setBattleId(7).build().toByteString()));

        assertThat(future).as("当场完成").isDone();
        ClientReply reply = reply(future);
        assertThat(reply.getTipId()).isZero();
        assertThat(WatchBattleResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(watch.thread.get()).isSameAs(Thread.currentThread());
        assertThat(workers.queued).isEmpty();
        assertThat(watch.session.get().getPlayerId()).isEqualTo(1001);
        assertThat(watch.session.get().getAccount()).isEqualTo("acc");
        assertThat(watch.deadline.get().remainingMillis()).isBetween(3000L, 4500L);
        assertThat(requests("WatchBattle", "ok")).isEqualTo(1);
    }

    @Test
    void 空应答体_tip为0_是否回包交给gate按契约决定() throws Exception {
        Handler invite = new Handler(MatchMethods.NOTIFY_CHALLENGE_INVITE, true);

        ClientReply reply = reply(dispatcher(invite).dispatch(call(156, ByteString.EMPTY)));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody().isEmpty()).isTrue();
    }

    // ================================================================ 工作池

    @Test
    void 非inline的处理器投到工作池_执行之前future不完成_绝不在调用线程上跑() throws Exception {
        Handler join = new Handler(MatchMethods.JOIN_QUEUE, false);
        join.reply = Reply.body(MatchTips.joinAccepted("t-1"));

        CompletableFuture<ClientReply> future = dispatcher(join).dispatch(call(157, ByteString.EMPTY));

        assertThat(future).isNotDone();
        assertThat(join.handled).as("还在排队").hasValue(0);
        assertThat(workers.queued).hasSize(1);
        workers.runAll();
        ClientReply reply = reply(future);
        assertThat(JoinQueueResponse.parseFrom(reply.getBody()).getQueueTicket()).isEqualTo("t-1");
        assertThat(reply.getTipId()).isZero();
        assertThat(join.handled).hasValue(1);
        assertThat(requests("JoinQueue", "ok")).isEqualTo(1);
    }

    @Test
    void 截止从受理时刻起算_排队的时间算在预算里() throws Exception {
        Handler join = new Handler(MatchMethods.JOIN_QUEUE, false);
        CompletableFuture<ClientReply> future = dispatcher(4500, join).dispatch(call(157, ByteString.EMPTY));

        TimeUnit.MILLISECONDS.sleep(300);
        workers.runAll();

        reply(future);
        assertThat(join.deadline.get().remainingMillis()).as("排了 300 ms 的队：剩下的预算不到 4200 ms").isBetween(3000L, 4200L);
    }

    @Test
    void 工作池拒收_不调处理_按该方法的过载应答回_有in_band字段的回in_band() throws Exception {
        Handler join = new Handler(MatchMethods.JOIN_QUEUE, false);
        join.overloadReply = Reply.body(MatchTips.joinRejected(MatchTip.BUSY));
        workers.reject = true;

        CompletableFuture<ClientReply> future = dispatcher(join).dispatch(call(157, ByteString.EMPTY));

        assertThat(future).isDone();
        JoinQueueResponse response = JoinQueueResponse.parseFrom(reply(future).getBody());
        assertThat(response.getErrorCode()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly("服务器繁忙,请稍后再试");
        assertThat(reply(future).getTipId()).as("in-band：信封不带 tip").isZero();
        assertThat(join.handled).hasValue(0);
        assertThat(join.overloads).hasValue(1);
        assertThat(requests("JoinQueue", "overloaded")).isEqualTo(1);
    }

    @Test
    void 工作池拒收_只能用信封的方法回信封1003() throws Exception {
        Handler cancel = new Handler(MatchMethods.CANCEL_QUEUE, false);
        workers.reject = true;

        ClientReply reply = reply(dispatcher(cancel).dispatch(call(148, ByteString.EMPTY)));

        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(reply.getBody().isEmpty()).isTrue();
        assertThat(cancel.overloads).hasValue(1);
    }

    @Test
    void 排队超预算_轮到执行时不再调处理_按过载应答回() throws Exception {
        Handler status = new Handler(MatchMethods.GET_QUEUE_STATUS, false);
        CompletableFuture<ClientReply> future = dispatcher(40, status).dispatch(call(153, ByteString.EMPTY));

        TimeUnit.MILLISECONDS.sleep(80);
        workers.runAll();

        assertThat(reply(future).getTipId()).isEqualTo(1003);
        assertThat(status.handled).as("预算已用完：不再做事").hasValue(0);
        assertThat(status.overloads).hasValue(1);
        assertThat(requests("GetQueueStatus", "overloaded")).isEqualTo(1);
    }

    @Test
    void 过载应答本身出错_退到信封1003_future照样完成() throws Exception {
        Handler join = new Handler(MatchMethods.JOIN_QUEUE, false);
        join.overloadError = new IllegalStateException("实现 bug");
        workers.reject = true;

        assertThat(reply(dispatcher(join).dispatch(call(157, ByteString.EMPTY))).getTipId()).isEqualTo(1003);
    }

    // ================================================================ 处理器的各种结局

    @Test
    void 处理器回信封_原样变成tip_id() throws Exception {
        Handler cancel = new Handler(MatchMethods.CANCEL_QUEUE, false);
        cancel.reply = Reply.envelope(1003);
        CompletableFuture<ClientReply> future = dispatcher(cancel).dispatch(call(148, ByteString.EMPTY));
        workers.runAll();

        assertThat(reply(future).getTipId()).isEqualTo(1003);
        assertThat(reply(future).getBody().isEmpty()).isTrue();
        assertThat(requests("CancelQueue", "failed")).isEqualTo(1);
    }

    @Test
    void 请求体解析失败_信封1003() throws Exception {
        Handler watch = new Handler(MatchMethods.WATCH_BATTLE, true);
        watch.parse = true;

        // 字段 1 声明成长度前缀、长度却超出剩余字节：截断的消息
        ClientReply reply = reply(dispatcher(watch).dispatch(call(163, ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01}))));

        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(requests("WatchBattle", "bad_request")).isEqualTo(1);
    }

    @Test
    void 处理器抛未分类的异常_信封1003_工作池上的也一样_future不异常完成() throws Exception {
        Handler watch = new Handler(MatchMethods.WATCH_BATTLE, true);
        watch.error = new IllegalStateException("实现 bug");
        Handler join = new Handler(MatchMethods.JOIN_QUEUE, false);
        join.error = new IllegalStateException("实现 bug");
        MatchDispatcher dispatcher = dispatcher(watch, join);

        ClientReply inline = reply(dispatcher.dispatch(call(163, ByteString.EMPTY)));
        CompletableFuture<ClientReply> pooled = dispatcher.dispatch(call(157, ByteString.EMPTY));
        workers.runAll();

        assertThat(inline.getTipId()).isEqualTo(1003);
        assertThat(reply(pooled).getTipId()).isEqualTo(1003);
        assertThat(pooled).isCompleted();
        assertThat(requests("WatchBattle", "error")).isEqualTo(1);
        assertThat(requests("JoinQueue", "error")).isEqualTo(1);
    }

    @Test
    void 应答类型的约束_信封必须带非0的tip_应答体可以是空() {
        assertThatThrownBy(() -> Reply.envelope(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(((Reply.Body) Reply.empty()).bytes().isEmpty()).isTrue();
        assertThat(new Reply.Body(null).bytes()).isEqualTo(ByteString.EMPTY);
        assertThat(((Reply.Body) Reply.body(MatchTips.joinAccepted("t"))).bytes()).isEqualTo(MatchTips.joinAccepted("t").toByteString());
    }
}
