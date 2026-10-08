package com.game.match.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.match.MatchProperties;
import com.game.match.challenge.ChallengeHandlers;
import com.game.match.challenge.ChallengeService;
import com.game.match.challenge.ChallengeStore;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.queue.QueueHandlers;
import com.game.match.queue.QueueService;
import com.game.match.reissue.BattleTicketReissue;
import com.game.match.reissue.ReissueHandler;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlacementDialer;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FixedRatingReader;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.RecordingPushes;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.TicketState;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.QueueState;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 客户端入口（match-spec §9.9、§8.1）：从 Dubbo 提供方 {@link MatchClientMessageService} 进，经真的派发器与真的 {@code match-worker} 工作池，
 * 到 10 个号各自的处理器。
 *
 * <p>分两层：
 * <ul>
 *   <li><b>派发层的机制</b>用带探针的替身处理器钉（{@link #businessHandlers()}，按 §8.1 写）：按号选处理器、投到工作池、截止与会话原样交到、
 *       应答体 / 信封的翻译、未知号与解析失败回信封 1003、过载时不调 {@code handle} 而按该方法的过载应答回（M29）。当场回的四个号
 *       （156 / 154 / 163 / 164）用的是真处理器（{@link InlineHandlers}），§8.1 的那四行逐格钉住。</li>
 *   <li><b>§8.1 整张表对着真处理器再钉一遍</b>（最后一节「真处理器」）：进程里实际登记的十个处理器——排队三个、补签一个、切磋两个、当场回的四个，
 *       依赖用 {@code testing} 包的替身——经同一个派发器与工作池：恰好覆盖契约的十个方法；过载时 157 / 152 / 151 / 179 回 in-band 16004、
 *       148 / 153 回信封 1003 且一条依赖都没碰；依赖故障时 148 / 153 回信封 1003、157 / 179 回 in-band 16004；正常路径走得通。</li>
 * </ul>
 */
class MatchClientMessageServiceTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final String BUSY_TEXT = "服务器繁忙,请稍后再试";
    /** 字段 1 声明成长度前缀、长度却超出剩余字节：任何消息类型都解析不了。 */
    private static final ByteString TRUNCATED = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

    private static final Map<String, Integer> IDS = Map.of(
            MatchMethods.JOIN_QUEUE, 157, MatchMethods.CANCEL_QUEUE, 148, MatchMethods.GET_QUEUE_STATUS, 153,
            MatchMethods.CHALLENGE_PLAYER, 152, MatchMethods.RESPOND_CHALLENGE, 151, MatchMethods.REQUEST_BATTLE_TICKET, 179,
            MatchMethods.NOTIFY_CHALLENGE_INVITE, 156, MatchMethods.NOTIFY_CHALLENGE_RESULT, 154,
            MatchMethods.WATCH_BATTLE, 163, MatchMethods.LIST_WATCHABLE_BATTLES, 164);
    private static final List<String> INLINE = List.of(MatchMethods.NOTIFY_CHALLENGE_INVITE, MatchMethods.NOTIFY_CHALLENGE_RESULT,
            MatchMethods.WATCH_BATTLE, MatchMethods.LIST_WATCHABLE_BATTLES);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final Map<String, Business> business = businessHandlers();
    private MatchWorkerPool pool;

    @AfterEach
    void closePool() {
        business.values().forEach(Business::unblock);
        if (pool != null) {
            pool.close();
        }
    }

    // ================================================================ 六个业务号的替身（各包合入后换成真处理器）

    /**
     * 一个业务号的替身：按契约的请求类型解析请求体；正常应答与过载应答各一份；记下被调的次数、线程、会话、请求体与截止；可以卡住（占住工作线程）。
     */
    private static final class Business implements MatchMethodHandler {
        final String method;
        final Parser<?> requestParser;
        final Reply overloadReply;
        final AtomicInteger handled = new AtomicInteger();
        final AtomicInteger overloads = new AtomicInteger();
        final AtomicReference<String> thread = new AtomicReference<>();
        final AtomicReference<SessionContext> session = new AtomicReference<>();
        final AtomicReference<ByteString> body = new AtomicReference<>();
        final AtomicReference<Deadline> deadline = new AtomicReference<>();
        volatile Reply reply;
        volatile RuntimeException error;
        volatile CountDownLatch block;
        final CountDownLatch entered = new CountDownLatch(1);

        Business(String method, Reply reply, Reply overloadReply) {
            this.method = method;
            this.requestParser = REGISTRY.byId(IDS.get(method)).orElseThrow().requestPrototype().getParserForType();
            this.reply = reply;
            this.overloadReply = overloadReply;
        }

        void unblock() {
            CountDownLatch latch = block;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
            requestParser.parseFrom(body);
            handled.incrementAndGet();
            this.thread.set(Thread.currentThread().getName());
            this.session.set(session);
            this.body.set(body);
            this.deadline.set(deadline);
            entered.countDown();
            CountDownLatch latch = block;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (error != null) {
                throw error;
            }
            return reply;
        }

        @Override
        public Reply onOverload() {
            overloads.incrementAndGet();
            return overloadReply;
        }
    }

    /**
     * 六个业务号：正常应答各不相同（好认出是谁回的）；过载应答按 §8.1「工作池满 / 排队超预算」一列——有 in-band 错误字段的 157 / 152 / 151 / 179
     * 回 in-band 16004，只能用信封的 148 / 153 回信封 1003。
     */
    private static Map<String, Business> businessHandlers() {
        Map<String, Business> handlers = new LinkedHashMap<>();
        add(handlers, new Business(MatchMethods.JOIN_QUEUE, Reply.body(MatchTips.joinAccepted("ticket-157")),
                Reply.body(MatchTips.joinRejected(MatchTip.BUSY))));
        add(handlers, new Business(MatchMethods.CANCEL_QUEUE, Reply.empty(), Reply.envelope(MatchTips.SERVICE_UNAVAILABLE)));
        add(handlers, new Business(MatchMethods.GET_QUEUE_STATUS,
                Reply.body(GetQueueStatusResponse.newBuilder().setState(QueueState.QUEUE_STATE_QUEUED).setQueuedSeconds(3).build()),
                Reply.envelope(MatchTips.SERVICE_UNAVAILABLE)));
        add(handlers, new Business(MatchMethods.CHALLENGE_PLAYER, Reply.body(MatchTips.challengeSent(152_000)),
                Reply.body(MatchTips.challengeRejected(MatchTip.BUSY))));
        add(handlers, new Business(MatchMethods.RESPOND_CHALLENGE, Reply.body(MatchTips.respondRejected(MatchTip.CHALLENGE_EXPIRED)),
                Reply.body(MatchTips.respondRejected(MatchTip.BUSY))));
        add(handlers, new Business(MatchMethods.REQUEST_BATTLE_TICKET, Reply.body(MatchTips.reissueRejected(MatchTip.REISSUE_BATTLE_GONE)),
                Reply.body(MatchTips.reissueRejected(MatchTip.BUSY))));
        return handlers;
    }

    private static void add(Map<String, Business> handlers, Business handler) {
        handlers.put(handler.method, handler);
    }

    // ================================================================ 装配

    private MatchClientMessageService service(int threads, int queue, long budgetMillis) {
        pool = new MatchWorkerPool(threads, queue, Duration.ofSeconds(2));
        pool.bindTo(meters);
        InlineHandlers inline = new InlineHandlers();
        List<MatchMethodHandler> all = new ArrayList<>(business.values());
        all.add(inline.notifyChallengeInviteUplinkHandler());
        all.add(inline.notifyChallengeResultUplinkHandler());
        all.add(inline.watchBattlePlaceholderHandler());
        all.add(inline.listWatchableBattlesPlaceholderHandler());
        return new MatchClientMessageService(new MatchDispatcher(REGISTRY, all, pool, metrics, budgetMillis));
    }

    private MatchClientMessageService service() {
        return service(4, 64, 4500);
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(3).setGateInstanceId("gate-a").setSessionId(21).setZoneId(1).setAccount("acc-21")
                .setPlayerId(playerId).build();
    }

    private static ClientCall call(int messageId, ByteString body, long playerId) {
        return ClientCall.newBuilder().setMessageId(messageId).setRequestId(77).setBody(body).setSession(session(playerId)).build();
    }

    private static ClientCall call(String method) {
        return call(IDS.get(method), ByteString.EMPTY, 1001);
    }

    private static ClientReply await(CompletableFuture<ClientReply> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private double requests(String method, String result) {
        return meters.get("xm.match.requests").tag("method", method).tag("result", result).timer().count();
    }

    private static void assertInBandBusy(TipInfoMessage tip) {
        assertThat(tip.getId()).isEqualTo(16004);
        assertThat(tip.getParametersList()).containsExactly(BUSY_TEXT);
    }

    // ================================================================ 10 个号的派发

    @Test
    void 契约的十个号都有处理器_号与方法一一对应() {
        service();
        for (String method : MatchMethods.ALL) {
            assertThat(REGISTRY.requireId(MatchMethods.SERVICE, method)).as(method).isEqualTo(IDS.get(method));
        }
        assertThat(IDS.keySet()).containsExactlyInAnyOrderElementsOf(MatchMethods.ALL);
    }

    @Test
    void 六个业务号各自派到自己的处理器_在match_worker线程上_会话请求体截止原样交到() throws Exception {
        MatchClientMessageService service = service();
        ByteString body = JoinQueueRequest.newBuilder().setModeValue(3).setBattleConfigId(7).build().toByteString();

        for (Business target : business.values()) {
            // 同一份字节对六个请求类型都是合法消息：字段号对不上或线型不符的字段按未知字段处理，不报错
            ClientReply reply = await(service.handle(call(IDS.get(target.method), body, 1001)));

            assertThat(target.handled).as(target.method).hasValue(1);
            assertThat(target.thread.get()).as(target.method).startsWith("match-worker-");
            assertThat(target.session.get()).as("会话上下文原样交到").isEqualTo(session(1001));
            assertThat(target.body.get()).isEqualTo(body);
            assertThat(target.deadline.get().remainingMillis()).as("截止 = 受理时刻 + 4500 ms").isBetween(1L, 4500L);
            assertThat(reply.getTipId()).as(target.method).isZero();
            assertThat(reply.getBody()).as(target.method).isEqualTo(((Reply.Body) target.reply).bytes());
            assertThat(reply.getDirectivesList()).as("match 不产生会话指令").isEmpty();
            assertThat(reply.getTipParametersList()).isEmpty();
        }
        assertThat(business.values()).as("每个处理器恰好被自己的号调到一次").allSatisfy(h -> assertThat(h.handled).hasValue(1));
        assertThat(JoinQueueResponse.parseFrom(await(service.handle(call(MatchMethods.JOIN_QUEUE))).getBody()).getQueueTicket()).isEqualTo("ticket-157");
        assertThat(ChallengePlayerResponse.parseFrom(await(service.handle(call(MatchMethods.CHALLENGE_PLAYER))).getBody()).getChallengeId())
                .isEqualTo(152_000);
    }

    @Test
    void 取消排队148成功_应答Empty_tip为0_gate据此不回包() throws Exception {
        ClientReply reply = await(service().handle(call(MatchMethods.CANCEL_QUEUE)));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody().isEmpty()).isTrue();
        assertThat(REGISTRY.byId(148).orElseThrow().responsePrototype()).as("应答类型是 Empty：gate 的规则是 Empty 且 tip 0 不回包")
                .isInstanceOf(com.game.proto.Empty.class);
    }

    @Test
    void 当场回的四个号_在调用线程上完成_不进工作池_不看会话有没有玩家() throws Exception {
        MatchClientMessageService service = service();

        for (long playerId : new long[] {1001, 0}) {
            for (String method : INLINE) {
                CompletableFuture<ClientReply> future = service.handle(call(IDS.get(method), ByteString.EMPTY, playerId));
                assertThat(future).as("%s player=%d 当场完成", method, playerId).isDone();
                assertThat(future.get().getTipId()).as(method).isZero();
                assertThat(future.get().getDirectivesList()).isEmpty();
            }
            ClientReply invite = await(service.handle(call(156, ByteString.EMPTY, playerId)));
            ClientReply result = await(service.handle(call(154, ByteString.EMPTY, playerId)));
            ClientReply watch = await(service.handle(call(163, ByteString.EMPTY, playerId)));
            ClientReply list = await(service.handle(call(164, ByteString.EMPTY, playerId)));

            assertThat(invite.getBody().isEmpty()).as("156 上行：Empty").isTrue();
            assertThat(result.getBody().isEmpty()).as("154 上行：Empty").isTrue();
            WatchBattleResponse watched = WatchBattleResponse.parseFrom(watch.getBody());
            assertThat(watched.getErrorMessage().getId()).as("163：in-band 1006").isEqualTo(1006);
            assertThat(watched.getErrorMessage().getParametersList()).isEmpty();
            assertThat(watch.getBody()).isEqualTo(MatchTips.watchUnavailable().toByteString());
            assertThat(list.getBody().isEmpty()).as("164：空列表").isTrue();
            assertThat(ListWatchableBattlesResponse.parseFrom(list.getBody()).getBattlesCount()).isZero();
        }
        assertThat(business.values()).allSatisfy(h -> assertThat(h.handled).hasValue(0));
        assertThat(meters.get("executor.completed").tag("name", "match-worker").functionCounter().count()).as("工作池一个任务都没跑过").isZero();
        assertThat(meters.get("executor.queued").tag("name", "match-worker").gauge().value()).isZero();
        assertThat(requests("WatchBattle", "ok")).isEqualTo(4);
    }

    // ================================================================ 信封 1003：未知号、解析失败、处理器回信封、处理器异常

    @Test
    void 未知号_契约外的号与别的服务的号_都回信封1003() throws Exception {
        MatchClientMessageService service = service();

        for (int messageId : new int[] {0, 1, 150, 155, 211, 99_999, -5}) {
            ClientReply reply = await(service.handle(call(messageId, ByteString.EMPTY, 1001)));
            assertThat(reply.getTipId()).as("message_id=%d", messageId).isEqualTo(1003);
            assertThat(reply.getBody().isEmpty()).isTrue();
            assertThat(reply.getTipParametersList()).isEmpty();
        }
        assertThat(requests("unknown", "unsupported")).isEqualTo(7);
        assertThat(business.values()).allSatisfy(h -> assertThat(h.handled).hasValue(0));
    }

    @Test
    void 请求体解析失败_十个号都回信封1003() throws Exception {
        MatchClientMessageService service = service();

        for (String method : MatchMethods.ALL) {
            ClientReply reply = await(service.handle(call(IDS.get(method), TRUNCATED, 1001)));
            assertThat(reply.getTipId()).as(method).isEqualTo(1003);
            assertThat(reply.getBody().isEmpty()).as(method).isTrue();
            assertThat(requests(method, "bad_request")).as(method).isEqualTo(1);
        }
        assertThat(business.values()).as("解析失败的请求没有进入业务").allSatisfy(h -> assertThat(h.handled).hasValue(0));
    }

    @Test
    void 处理器回信封_取消与查状态的依赖故障_原样成tip_id_不带应答体() throws Exception {
        business.get(MatchMethods.CANCEL_QUEUE).reply = Reply.envelope(1003);
        business.get(MatchMethods.GET_QUEUE_STATUS).reply = Reply.envelope(1003);
        MatchClientMessageService service = service();

        for (String method : List.of(MatchMethods.CANCEL_QUEUE, MatchMethods.GET_QUEUE_STATUS)) {
            ClientReply reply = await(service.handle(call(method)));
            assertThat(reply.getTipId()).as(method).isEqualTo(1003);
            assertThat(reply.getBody().isEmpty()).isTrue();
            assertThat(reply.getTipParametersList()).as("信封不带 parameters").isEmpty();
            assertThat(requests(method, "failed")).isEqualTo(1);
        }
    }

    @Test
    void 处理器抛未分类的异常_信封1003_future正常完成() throws Exception {
        business.get(MatchMethods.REQUEST_BATTLE_TICKET).error = new IllegalStateException("实现 bug");

        CompletableFuture<ClientReply> future = service().handle(call(MatchMethods.REQUEST_BATTLE_TICKET));

        assertThat(await(future).getTipId()).isEqualTo(1003);
        assertThat(future).isCompleted();
        assertThat(requests("RequestBattleTicket", "error")).isEqualTo(1);
    }

    // ================================================================ 过载（M29）

    /** 把唯一的工作线程卡在一条 153 上，再塞一条占满队列；返回这两条的 future。 */
    private List<CompletableFuture<ClientReply>> saturate(MatchClientMessageService service) throws Exception {
        Business status = business.get(MatchMethods.GET_QUEUE_STATUS);
        status.block = new CountDownLatch(1);
        CompletableFuture<ClientReply> running = service.handle(call(MatchMethods.GET_QUEUE_STATUS));
        assertThat(status.entered.await(5, TimeUnit.SECONDS)).as("第一条已经占住工作线程").isTrue();
        CompletableFuture<ClientReply> queued = service.handle(call(MatchMethods.GET_QUEUE_STATUS));
        return List.of(running, queued);
    }

    @Test
    void 工作池满_不调处理_有in_band错误字段的四个号回in_band的16004_只能用信封的两个号回信封1003() throws Exception {
        MatchClientMessageService service = service(1, 1, 4500);
        List<CompletableFuture<ClientReply>> occupying = saturate(service);
        Map<String, Integer> handledBefore = new LinkedHashMap<>();
        business.values().forEach(h -> handledBefore.put(h.method, h.handled.get()));

        CompletableFuture<ClientReply> join = service.handle(call(MatchMethods.JOIN_QUEUE));
        CompletableFuture<ClientReply> challenge = service.handle(call(MatchMethods.CHALLENGE_PLAYER));
        CompletableFuture<ClientReply> respond = service.handle(call(MatchMethods.RESPOND_CHALLENGE));
        CompletableFuture<ClientReply> reissue = service.handle(call(MatchMethods.REQUEST_BATTLE_TICKET));
        CompletableFuture<ClientReply> cancel = service.handle(call(MatchMethods.CANCEL_QUEUE));
        CompletableFuture<ClientReply> status = service.handle(call(MatchMethods.GET_QUEUE_STATUS));

        assertThat(List.of(join, challenge, respond, reissue, cancel, status)).as("拒收当场应答，不等工作线程").allSatisfy(f -> assertThat(f).isDone());
        JoinQueueResponse joined = JoinQueueResponse.parseFrom(join.get().getBody());
        assertThat(join.get().getTipId()).as("in-band：信封不带 tip").isZero();
        assertThat(joined.getErrorCode()).isEqualTo(16004);
        assertInBandBusy(joined.getErrorMessage());
        assertThat(joined.getQueueTicket()).isEmpty();
        assertThat(challenge.get().getTipId()).isZero();
        assertInBandBusy(ChallengePlayerResponse.parseFrom(challenge.get().getBody()).getErrorMessage());
        assertThat(respond.get().getTipId()).isZero();
        assertInBandBusy(RespondChallengeResponse.parseFrom(respond.get().getBody()).getErrorMessage());
        assertThat(reissue.get().getTipId()).isZero();
        RequestBattleTicketResponse reissued = RequestBattleTicketResponse.parseFrom(reissue.get().getBody());
        assertInBandBusy(reissued.getErrorMessage());
        assertThat(reissued.hasAssignment()).isFalse();
        for (CompletableFuture<ClientReply> envelope : List.of(cancel, status)) {
            assertThat(envelope.get().getTipId()).isEqualTo(1003);
            assertThat(envelope.get().getBody().isEmpty()).isTrue();
            assertThat(envelope.get().getTipParametersList()).isEmpty();
        }
        business.values().forEach(h -> {
            assertThat(h.handled).as("%s 过载时不调 handle", h.method).hasValue(handledBefore.get(h.method));
            assertThat(h.overloads).as(h.method).hasValue(1);
        });
        for (String method : business.keySet()) {
            assertThat(requests(method, "overloaded")).as(method).isEqualTo(1);
        }

        business.get(MatchMethods.GET_QUEUE_STATUS).unblock();
        assertThat(await(occupying.get(0)).getTipId()).as("占着线程的那条照常做完").isZero();
        assertThat(await(occupying.get(1)).getTipId()).as("排着队的那条在预算内轮到，照常做完").isZero();
    }

    @Test
    void 工作池满时_当场回的四个号不受影响() throws Exception {
        MatchClientMessageService service = service(1, 1, 4500);
        saturate(service);

        for (String method : INLINE) {
            CompletableFuture<ClientReply> future = service.handle(call(method));
            assertThat(future).as(method).isDone();
            assertThat(future.get().getTipId()).as(method).isZero();
        }
        assertThat(WatchBattleResponse.parseFrom(await(service.handle(call(MatchMethods.WATCH_BATTLE))).getBody()).getErrorMessage().getId())
                .isEqualTo(1006);
    }

    @Test
    void 排队超预算_轮到执行时不再调处理_按过载应答回() throws Exception {
        MatchClientMessageService service = service(1, 8, 150);
        // 占住唯一的工作线程用的是不带预算的裸任务：用一条带 150 ms 预算的请求去占的话，工作线程是用到才建的，
        // 它得在 150 ms 之内被接手，慢机器上会先被判成排队超预算（本用例只留「至少等够」这一个方向的时间依赖）
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        pool.execute(() -> {
            occupied.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).as("唯一的工作线程已被占住").isTrue();

        CompletableFuture<ClientReply> join = service.handle(call(MatchMethods.JOIN_QUEUE));
        CompletableFuture<ClientReply> cancel = service.handle(call(MatchMethods.CANCEL_QUEUE));
        assertThat(join).as("进了队列，还没轮到").isNotDone();
        TimeUnit.MILLISECONDS.sleep(300); // 两条都在队列里等过了 150 ms 的预算
        release.countDown();

        JoinQueueResponse joined = JoinQueueResponse.parseFrom(await(join).getBody());
        assertThat(joined.getErrorCode()).isEqualTo(16004);
        assertInBandBusy(joined.getErrorMessage());
        assertThat(await(cancel).getTipId()).isEqualTo(1003);
        assertThat(business.get(MatchMethods.JOIN_QUEUE).handled).as("预算已用完：不再做事").hasValue(0);
        assertThat(business.get(MatchMethods.CANCEL_QUEUE).handled).hasValue(0);
        assertThat(requests("JoinQueue", "overloaded")).isEqualTo(1);
        assertThat(requests("CancelQueue", "overloaded")).isEqualTo(1);
    }

    // ================================================================ 真处理器（进程里实际登记的十个；依赖用 testing 包的替身）

    private final InMemoryTicketStore realTickets = new InMemoryTicketStore();
    private final FakePlayerStatus realPlayers = new FakePlayerStatus();
    private final FakeGatherLauncher realGather = new FakeGatherLauncher();
    private final InMemoryPlacementStore realPlacements = new InMemoryPlacementStore();
    private final ChallengeStore realChallenges = mock(ChallengeStore.class);

    /** 与各包的装配类（QueueConfiguration / ReissueConfiguration / ChallengeConfiguration / InlineHandlers）登记的是同一批处理器类。 */
    private List<MatchMethodHandler> realHandlers() {
        MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null);
        MatchIds ids = new MatchIds(new Snowflake(3), () -> true, () -> false);
        QueueService queue = new QueueService(props, realPlayers, realTickets, new DefaultTicketHealing(realTickets), new FixedRatingReader(),
                realGather, ids, metrics);
        ChallengeService challenge = new ChallengeService(realPlayers, realChallenges, ids, new RecordingPushes(), realGather, metrics,
                Runnable::run, 60_000, IDS.get(MatchMethods.NOTIFY_CHALLENGE_INVITE), IDS.get(MatchMethods.NOTIFY_CHALLENGE_RESULT));
        BattleTicketReissue reissue = new BattleTicketReissue(realPlacements, new FakePlacementDialer(new FakeBattleNode()), metrics);
        InlineHandlers inline = new InlineHandlers();
        return List.of(new QueueHandlers.Join(queue, metrics), new QueueHandlers.Cancel(queue), new QueueHandlers.Status(queue),
                new ReissueHandler(reissue), ChallengeHandlers.challengePlayer(challenge, metrics),
                ChallengeHandlers.respondChallenge(challenge, metrics), inline.notifyChallengeInviteUplinkHandler(),
                inline.notifyChallengeResultUplinkHandler(), inline.watchBattlePlaceholderHandler(),
                inline.listWatchableBattlesPlaceholderHandler());
    }

    private MatchDispatcher realDispatcher(int threads, int queue, long budgetMillis) {
        pool = new MatchWorkerPool(threads, queue, Duration.ofSeconds(2));
        return new MatchDispatcher(REGISTRY, realHandlers(), pool, metrics, budgetMillis);
    }

    private static ClientCall joinCall(int mode, int configId) {
        return call(157, JoinQueueRequest.newBuilder().setModeValue(mode).setBattleConfigId(configId).build().toByteString(), 1001);
    }

    /** 六个业务号各发一条（请求体对各自的请求类型都合法）。 */
    private static Map<String, ClientCall> businessCalls() {
        Map<String, ClientCall> calls = new LinkedHashMap<>();
        calls.put(MatchMethods.JOIN_QUEUE, joinCall(3, 0));
        calls.put(MatchMethods.CHALLENGE_PLAYER, call(152, ChallengePlayerRequest.newBuilder().setTargetPlayerId(1002).build().toByteString(), 1001));
        calls.put(MatchMethods.RESPOND_CHALLENGE,
                call(151, RespondChallengeRequest.newBuilder().setChallengeId(7001).setAccept(true).build().toByteString(), 1001));
        calls.put(MatchMethods.REQUEST_BATTLE_TICKET,
                call(179, RequestBattleTicketRequest.newBuilder().setBattleId(42).build().toByteString(), 1001));
        calls.put(MatchMethods.CANCEL_QUEUE, call(148, ByteString.EMPTY, 1001));
        calls.put(MatchMethods.GET_QUEUE_STATUS, call(153, ByteString.EMPTY, 1001));
        return calls;
    }

    private void assertNoDependencyTouched() {
        assertThat(realTickets.calls).as("票据存储").isEmpty();
        assertThat(realPlayers.reads).as("玩家状态读口").isEmpty();
        assertThat(realGather.plans).as("开局管线").isEmpty();
        verifyNoInteractions(realChallenges);
    }

    /** §8.1「过载」一列逐格：有 in-band 错误字段的四个号回 in-band 16004，只能用信封的两个号回信封 1003。 */
    private static void assertOverloadReplies(Map<String, ClientReply> replies) throws Exception {
        JoinQueueResponse joined = JoinQueueResponse.parseFrom(replies.get(MatchMethods.JOIN_QUEUE).getBody());
        assertThat(replies.get(MatchMethods.JOIN_QUEUE).getTipId()).as("in-band：信封不带 tip").isZero();
        assertThat(joined.getErrorCode()).isEqualTo(16004);
        assertInBandBusy(joined.getErrorMessage());
        assertThat(joined.getQueueTicket()).isEmpty();
        ClientReply challenge = replies.get(MatchMethods.CHALLENGE_PLAYER);
        assertThat(challenge.getTipId()).isZero();
        assertInBandBusy(ChallengePlayerResponse.parseFrom(challenge.getBody()).getErrorMessage());
        assertThat(ChallengePlayerResponse.parseFrom(challenge.getBody()).getChallengeId()).isZero();
        ClientReply respond = replies.get(MatchMethods.RESPOND_CHALLENGE);
        assertThat(respond.getTipId()).isZero();
        assertInBandBusy(RespondChallengeResponse.parseFrom(respond.getBody()).getErrorMessage());
        ClientReply reissue = replies.get(MatchMethods.REQUEST_BATTLE_TICKET);
        assertThat(reissue.getTipId()).isZero();
        RequestBattleTicketResponse reissued = RequestBattleTicketResponse.parseFrom(reissue.getBody());
        assertInBandBusy(reissued.getErrorMessage());
        assertThat(reissued.hasAssignment()).isFalse();
        for (String method : List.of(MatchMethods.CANCEL_QUEUE, MatchMethods.GET_QUEUE_STATUS)) {
            assertThat(replies.get(method).getTipId()).as(method).isEqualTo(1003);
            assertThat(replies.get(method).getBody().isEmpty()).as(method).isTrue();
            assertThat(replies.get(method).getTipParametersList()).as("信封不带 parameters").isEmpty();
        }
    }

    @Test
    void 真处理器_进程登记的十个处理器恰好覆盖契约的十个方法_四个当场回_六个进工作池() {
        List<MatchMethodHandler> handlers = realHandlers();
        MatchDispatcher dispatcher = realDispatcher(2, 8, 4500);

        assertThat(handlers.stream().map(MatchMethodHandler::method)).containsExactlyInAnyOrderElementsOf(MatchMethods.ALL);
        assertThat(dispatcher.handledMessageIds()).containsExactly(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);
        assertThat(dispatcher.unhandledMethods()).isEmpty();
        assertThat(handlers.stream().filter(MatchMethodHandler::inline).map(MatchMethodHandler::method))
                .as("不涉及 I/O、当场回的只有这四个").containsExactlyInAnyOrderElementsOf(INLINE);
    }

    @Test
    void 真处理器_工作池满_四个有in_band字段的号回in_band的16004_取消与查状态回信封1003_一条依赖都没碰() throws Exception {
        MatchClientMessageService service = new MatchClientMessageService(realDispatcher(1, 1, 4500));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        pool.execute(() -> {
            occupied.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).as("唯一的工作线程已被占住").isTrue();
        pool.execute(() -> { }); // 再占满容量为 1 的队列

        Map<String, ClientReply> replies = new LinkedHashMap<>();
        for (Map.Entry<String, ClientCall> call : businessCalls().entrySet()) {
            CompletableFuture<ClientReply> future = service.handle(call.getValue());
            assertThat(future).as("%s：拒收当场应答，不等工作线程", call.getKey()).isDone();
            replies.put(call.getKey(), future.get());
        }
        release.countDown();

        assertOverloadReplies(replies);
        assertNoDependencyTouched();
        for (String method : replies.keySet()) {
            assertThat(requests(method, "overloaded")).as(method).isEqualTo(1);
        }
        assertThat(meters.get("xm.match.join.queue").tag("mode", "unknown").tag("outcome", "overloaded").counter().count())
                .as("过载时请求体没解析过：模式记 unknown").isEqualTo(1);
    }

    @Test
    void 真处理器_排队超预算_轮到执行时不再做事_按各自的过载应答回() throws Exception {
        MatchClientMessageService service = new MatchClientMessageService(realDispatcher(1, 16, 150));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        pool.execute(() -> {
            occupied.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

        Map<String, CompletableFuture<ClientReply>> futures = new LinkedHashMap<>();
        businessCalls().forEach((method, call) -> futures.put(method, service.handle(call)));
        assertThat(futures.values()).as("都进了队列，还没轮到").allSatisfy(future -> assertThat(future).isNotDone());
        TimeUnit.MILLISECONDS.sleep(300); // 在队列里等过了 150 ms 的预算
        release.countDown();

        Map<String, ClientReply> replies = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<ClientReply>> future : futures.entrySet()) {
            replies.put(future.getKey(), await(future.getValue()));
        }
        assertOverloadReplies(replies);
        assertNoDependencyTouched();
    }

    @Test
    void 真处理器_依赖故障_取消与查状态回信封1003_排队与补签回in_band的16004() throws Exception {
        MatchClientMessageService service = new MatchClientMessageService(realDispatcher(2, 8, 4500));
        realTickets.faults.failAlways("read").failAlways("status");
        realPlayers.failLock(1001);
        realPlacements.readFailed = true;

        ClientReply cancel = await(service.handle(call(148, ByteString.EMPTY, 1001)));
        ClientReply status = await(service.handle(call(153, ByteString.EMPTY, 1001)));
        ClientReply join = await(service.handle(joinCall(3, 0)));
        ClientReply reissue = await(service.handle(call(179, RequestBattleTicketRequest.newBuilder().setBattleId(42).build().toByteString(), 1001)));

        for (ClientReply envelope : List.of(cancel, status)) {
            assertThat(envelope.getTipId()).isEqualTo(1003);
            assertThat(envelope.getBody().isEmpty()).isTrue();
            assertThat(envelope.getTipParametersList()).isEmpty();
        }
        assertThat(requests("CancelQueue", "failed")).isEqualTo(1);
        assertThat(requests("GetQueueStatus", "failed")).isEqualTo(1);
        assertThat(join.getTipId()).as("157 的失败在应答体里").isZero();
        JoinQueueResponse joined = JoinQueueResponse.parseFrom(join.getBody());
        assertThat(joined.getErrorCode()).isEqualTo(16004);
        assertInBandBusy(joined.getErrorMessage());
        assertThat(reissue.getTipId()).isZero();
        assertInBandBusy(RequestBattleTicketResponse.parseFrom(reissue.getBody()).getErrorMessage());
        assertThat(realTickets.ticketCount()).as("什么票都没建").isZero();
    }

    @Test
    void 真处理器_正常路径_排队受理_查到QUEUED_取消成功不带应答体_补签不存在的战斗回1005() throws Exception {
        MatchClientMessageService service = new MatchClientMessageService(realDispatcher(2, 8, 4500));
        realPlayers.online(1001, 1, 7);

        ClientReply join = await(service.handle(joinCall(3, 0)));
        JoinQueueResponse joined = JoinQueueResponse.parseFrom(join.getBody());
        assertThat(join.getTipId()).isZero();
        assertThat(joined.getErrorCode()).isZero();
        assertThat(joined.hasErrorMessage()).isFalse();
        assertThat(joined.getQueueTicket()).as("受理：回票号").isNotEmpty();
        assertThat(realTickets.ticketOf(1001).orElseThrow().ticketId()).isEqualTo(joined.getQueueTicket());
        assertThat(realTickets.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.QUEUED);

        GetQueueStatusResponse queued = GetQueueStatusResponse.parseFrom(await(service.handle(call(153, ByteString.EMPTY, 1001))).getBody());
        assertThat(queued.getState()).isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(queued.getEstimatedWaitSeconds()).as("恒为 0").isZero();

        ClientReply cancel = await(service.handle(call(148, ByteString.EMPTY, 1001)));
        assertThat(cancel.getTipId()).as("148 成功：tip 0 + 空应答体，gate 据此不回包").isZero();
        assertThat(cancel.getBody().isEmpty()).isTrue();
        assertThat(realTickets.ticketOf(1001)).isEmpty();
        GetQueueStatusResponse gone = GetQueueStatusResponse.parseFrom(await(service.handle(call(153, ByteString.EMPTY, 1001))).getBody());
        assertThat(gone.getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);

        ClientReply reissue = await(service.handle(call(179, RequestBattleTicketRequest.newBuilder().setBattleId(42).build().toByteString(), 1001)));
        RequestBattleTicketResponse reissued = RequestBattleTicketResponse.parseFrom(reissue.getBody());
        assertThat(reissue.getTipId()).isZero();
        assertThat(reissued.getErrorMessage().getId()).as("没有落点记录：这局不存在").isEqualTo(1005);
        assertThat(reissued.getErrorMessage().getParametersList()).containsExactly("该战斗不存在或已结束");
        assertThat(requests("JoinQueue", "ok")).isEqualTo(1);
    }

    // ================================================================ 会话事件

    @Test
    void 会话结束与进场未送达_直接确认_不碰任何处理器() throws Exception {
        MatchClientMessageService service = service();

        Ack closed = service.sessionClosed(SessionClosed.newBuilder().setSession(session(1001)).build()).get(5, TimeUnit.SECONDS);
        Ack abandoned = service.abandonEnter(AbandonedEnter.newBuilder().setSession(session(1001)).build()).get(5, TimeUnit.SECONDS);

        assertThat(closed).isEqualTo(Ack.getDefaultInstance());
        assertThat(abandoned).isEqualTo(Ack.getDefaultInstance());
        assertThat(business.values()).allSatisfy(h -> assertThat(h.handled).hasValue(0));
    }
}
