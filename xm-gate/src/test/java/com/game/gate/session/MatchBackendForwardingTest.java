package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionDirective;
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
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 大厅连接上的匹配服务（批次 6.4；match-spec §1.4、§8.1、§9.2）：{@code MatchService} 的 10 个号经 gate 中继给 xm-match（Dubbo group
 * {@code match}），gate 这一侧的客户端可见规则——
 * <ul>
 *   <li>请求原样转发：消息号、请求号、请求体，加上 gate 填的会话身份（后端只认它，§2.1 / M3）；没进游戏的会话照常转发，由后端回它自己的码；</li>
 *   <li>应答体原样回，带请求号；148 CancelQueue 与两个推送占位 154 / 156 的应答类型是 {@code Empty}：tip 为 0 时<b>不回包</b>（M4），
 *       后端回了信封 tip（请求体解析失败 → 1003）时照样回信封；</li>
 *   <li>xm-match 不在、调用失败或超时 → 带请求号的信封 1003，10 个号都一样（含 148），不断连、不计非法包；</li>
 *   <li>同一会话的 match 请求串行（上一个有了结论才发下一个），不占 login / scene 的通道；</li>
 *   <li>10 个号都不在限频表里：每号每秒 3 条，第 4 条回信封 1008 并计非法包、不转发（§1.4）。</li>
 * </ul>
 *
 * <p>用同步进来的真实契约与真实路由表，限频取生产缺省：以后有人把 MatchService 从后端表里摘掉、改了应答类型的判定或挪动了后端分派，这里先失败。
 * 路由表本身（域、是否回包、热关停键）见 {@code MessageRoutesTest}；手写路由下的后端通用行为见 {@code ClientDispatcherTest}。
 */
class MatchBackendForwardingTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;
    private static final long PLAYER = 42;

    private final MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
    private final MessageRoutes routes = MessageRoutes.of(ids);
    /** 契约里 MatchService 的全部方法，按消息号升序。 */
    private final List<MessageMethod> matchMethods = ids.all().stream()
            .filter(m -> m.serviceName().equals("MatchService"))
            .sorted(Comparator.comparingInt(MessageMethod::messageId)).toList();
    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLogin match = new FakeLogin();
    private final FakeLogin friend = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);
    /** 限频用的单调时钟：用例自己推进，不依赖真实耗时。 */
    private final AtomicLong nanos = new AtomicLong(1);

    @Test
    void 十个号都转给match后端_带请求号与会话身份_应答体原样回_148与两个推送占位不回包() {
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, match, DubboGroups.FRIEND, friend));
        enterGame(ch);
        assertThat(matchMethods).extracting(MessageMethod::messageId)
                .containsExactly(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);

        long requestId = 100;
        for (MessageMethod method : matchMethods) {
            int id = method.messageId();
            requestId++;
            ch.writeInbound(request(requestId, id, "req-" + id));

            assertThat(match.calls).as(method.key() + " 转给 match 后端").hasSize((int) (requestId - 100));
            ClientCall call = match.calls.get(match.calls.size() - 1);
            assertThat(call.getMessageId()).as(method.key()).isEqualTo(id);
            assertThat(call.getRequestId()).as(method.key() + " 请求号").isEqualTo(requestId);
            assertThat(call.getBody().toStringUtf8()).as(method.key() + " 请求体原样").isEqualTo("req-" + id);
            assertThat(call.getSession().getPlayerId()).as(method.key() + " 身份取自会话（后端只认它）").isEqualTo(PLAYER);
            assertThat(call.getSession().getGateNodeId()).isEqualTo(GATE_NODE);
            assertThat(call.getSession().getZoneId()).isEqualTo(ZONE);
            assertThat(call.getSession().getSessionId()).isEqualTo(session().sessionId());
            assertThat((Object) ch.readOutbound()).as(method.key() + "：后端有结论之前不回任何东西").isNull();

            match.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("resp-" + id)).build());
            ch.runPendingTasks();

            boolean replied = routes.clientRoute(id).hasResponse();
            assertThat(replied).as(method.key() + " 是否回包").isEqualTo(id != 148 && id != 154 && id != 156);
            MessageContent out = ch.readOutbound();
            if (replied) {
                assertThat(out).as(method.key() + " 回包").isNotNull();
                assertThat(out.getMessageId()).as(method.key()).isEqualTo(id);
                assertThat(out.getId()).as(method.key() + " 带请求号").isEqualTo(requestId);
                assertThat(out.getSerializedMessage().toStringUtf8()).as(method.key() + " 应答体原样").isEqualTo("resp-" + id);
                assertThat(out.hasErrorMessage()).as(method.key() + " 成功应答不带信封错误（客户端见到它就判失败）").isFalse();
                assertThat((Object) ch.readOutbound()).as(method.key() + "：只有一帧").isNull();
            } else {
                assertThat(out).as(method.key() + " 应答类型是 Empty、tip 为 0：不回包").isNull();
            }
        }

        assertThat(match.calls).hasSize(10);
        assertThat(friend.calls).as("不串到别的后端").isEmpty();
        assertThat(login.calls).as("只有进游戏那一次").hasSize(1);
        assertThat(links.sent).as("不转给 scene（只有进场帧）").hasSize(1);
        assertThat(session().illegalPackets).isZero();
        assertThat(ch.isOpen()).isTrue();
        assertThat(forwarded()).as("10 个号各计一次 route=match、result=forwarded").hasSize(10)
                .allSatisfy(c -> {
                    assertThat(c.count()).isEqualTo(1.0);
                    assertThat(c.getId().getTag("method")).startsWith("MatchService.");
                });
        assertThat(requestCounters("unsupported")).as("没有一个号落到缺省分支").isEmpty();
    }

    @Test
    void 没进游戏的会话照常转发_身份为0由后端回自己的码_全默认值的应答是0字节也要回包() {
        // §2.1：gate 转发非玩家服务的消息不要求会话已绑定玩家；身份为 0 时 157 由后端回 in-band 16004、153 回 NOT_QUEUED……
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, match));
        int joinQueue = ids.requireId("MatchService", "JoinQueue");

        ch.writeInbound(request(7, joinQueue, "join"));

        assertThat(match.calls).singleElement().satisfies(call -> {
            assertThat(call.getSession().getPlayerId()).as("会话还没绑定玩家").isZero();
            assertThat(call.getSession().getAccount()).isEmpty();
            assertThat(call.getMessageId()).isEqualTo(157);
        });
        // 后端回一个全默认值的应答体（序列化后 0 字节）：有应答类型的方法照样回包，否则客户端一直等到超时
        match.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        MessageContent out = ch.readOutbound();
        assertThat(out).isNotNull();
        assertThat(out.getMessageId()).isEqualTo(157);
        assertThat(out.getId()).isEqualTo(7);
        assertThat(out.getSerializedMessage()).isEmpty();
        assertThat(out.hasErrorMessage()).isFalse();
        assertThat(login.calls).as("不碰 login").isEmpty();
    }

    @Test
    void match后端调用失败_回带请求号的信封1003_148也回_不断连不计非法包_下一条照常() {
        // §8.1：gate 调 match 失败或超时 → MessageContent{message_id, id = 请求号, error_message{1003}}（同基线路由服）。
        // 两种失败形态：future 异常完成（超时、上游不可用）与代理当场抛异常（Dubbo 把后端记为不可用之后的「No provider available」，
        // BackendReconnectTest 里测到的），客户端看到的必须一样
        ThrowingBackend down = new ThrowingBackend();
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, down));
        enterGame(ch);

        long requestId = 200;
        for (MessageMethod method : matchMethods) {
            requestId++;
            ch.writeInbound(request(requestId, method.messageId(), "x"));
            ch.runPendingTasks();
            expectEnvelope1003(ch, method, requestId);
        }
        assertThat(down.calls).as("每个号都试过一次，没有重试").isEqualTo(10);

        // future 异常完成的形态：换一条连接、用可控的后端
        EmbeddedChannel ch2 = verified(Map.of(DubboGroups.MATCH, match));
        int cancel = ids.requireId("MatchService", "CancelQueue");
        int status = ids.requireId("MatchService", "GetQueueStatus");
        ch2.writeInbound(request(1, cancel, "cancel"));
        ch2.writeInbound(request(2, status, "status"));
        assertThat(match.calls).as("同一会话的 match 请求串行：148 没有结论之前 153 不发").hasSize(1);

        match.fail(new IllegalStateException("upstream 127.0.0.1:20888 is unavailable"));
        ch2.runPendingTasks();
        MessageContent envelope = ch2.readOutbound();
        assertThat(envelope.getMessageId()).as("148 的应答类型是 Empty，失败时照样回信封").isEqualTo(148);
        assertThat(envelope.getId()).isEqualTo(1);
        assertThat(envelope.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).isEqualTo(1003);
        assertThat(envelope.getSerializedMessage()).isEmpty();

        assertThat(match.calls).as("148 失败之后轮到 153").hasSize(2);
        assertThat(match.calls.get(1).getMessageId()).isEqualTo(153);
        match.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("state")).build());
        ch2.runPendingTasks();
        MessageContent ok = ch2.readOutbound();
        assertThat(ok.getMessageId()).isEqualTo(153);
        assertThat(ok.getId()).isEqualTo(2);
        assertThat(ok.getSerializedMessage().toStringUtf8()).isEqualTo("state");
        assertThat(ok.hasErrorMessage()).isFalse();

        for (EmbeddedChannel channel : List.of(ch, ch2)) {
            assertThat(channel.isOpen()).as("后端不可用不断连").isTrue();
        }
        assertThat(registry.all()).allSatisfy(s -> assertThat(s.illegalPackets).as("不计非法包").isZero());
        assertThat(disconnects()).isZero();
    }

    @Test
    void 后端回信封tip时_148与推送占位也照样回信封_带请求号与tip参数() {
        // §8.1「请求体解析失败 → 信封 1003」一列：后端经 ClientReply.tip_id 回的传输层失败，不受「Empty 不回包」影响
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, match));
        long requestId = 300;
        for (int id : new int[] {148, 154, 156, 157}) {
            requestId++;
            ch.writeInbound(request(requestId, id, "garbage"));
            match.complete(ClientReply.newBuilder().setTipId(1003).addTipParameters("bad-" + id).build());
            ch.runPendingTasks();

            MessageContent envelope = ch.readOutbound();
            assertThat(envelope).as("消息号 %d", id).isNotNull();
            assertThat(envelope.getMessageId()).isEqualTo(id);
            assertThat(envelope.getId()).isEqualTo(requestId);
            assertThat(envelope.getErrorMessage().getId()).isEqualTo(1003);
            assertThat(envelope.getErrorMessage().getParametersList()).containsExactly("bad-" + id);
            assertThat(envelope.getSerializedMessage()).isEmpty();
            assertThat((Object) ch.readOutbound()).isNull();
        }
    }

    @Test
    void match调用在途不阻塞login_scene与别的后端() {
        // match 的 gather 入口最坏要等 4.5 s 的请求预算：这段时间同一会话的登录链路、场景消息、好友消息照常走
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, match, DubboGroups.FRIEND, friend));
        enterGame(ch);
        ch.writeInbound(request(1, ids.requireId("MatchService", "JoinQueue"), "join"));
        assertThat(match.calls).hasSize(1);

        ch.writeInbound(request(2, ids.requireId("ClientPlayerFriend", "AddFriend"), "add"));
        ch.writeInbound(request(3, ids.requireId("SceneSkillClientPlayer", "ListSkills"), "skills"));
        ch.writeInbound(request(4, ids.requireId("ClientPlayerLogin", "LeaveGame"), "leave"));

        assertThat(friend.calls).as("好友后端不等 match").hasSize(1);
        assertThat(links.sent).as("scene 不等 match（进场帧 + 这一条）").hasSize(2);
        assertThat(login.calls).as("login 不等 match（进游戏 + 这一条）").hasSize(2);
        assertThat(match.calls).as("match 自己仍只有一条在途").hasSize(1);
    }

    @Test
    void 同号每秒3条_第4条回信封1008并计非法包不转发_窗口滑过后恢复() {
        // §1.4：MessageLimiter 表里没有任何 match 消息号，按缺省每会话每号每秒 3 条（MessageRoutesTest 用真表钉住「不在表里」）。
        // robot 连发同号请求间隔 ≥ 350 ms、179 的退避重试都按这个节奏（§15.5）
        EmbeddedChannel ch = verified(Map.of(DubboGroups.MATCH, match));
        int status = ids.requireId("MatchService", "GetQueueStatus");
        for (int i = 1; i <= 3; i++) {
            ch.writeInbound(request(i, status, "s"));
            match.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("ok")).build());
            ch.runPendingTasks();
            assertThat(((MessageContent) ch.readOutbound()).getId()).as("窗口内第 %d 条正常应答", i).isEqualTo(i);
        }

        ch.writeInbound(request(4, status, "s"));
        MessageContent limited = ch.readOutbound();
        assertThat(limited.getMessageId()).isEqualTo(153);
        assertThat(limited.getId()).isEqualTo(4);
        assertThat(limited.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_RATE_LIMIT_EXCEEDED).isEqualTo(1008);
        assertThat(match.calls).as("超频的那条没有转发").hasSize(3);
        assertThat(session().illegalPackets).isEqualTo(1);

        // 限频按消息号分开：同一时刻别的 match 号不受影响
        ch.writeInbound(request(5, ids.requireId("MatchService", "JoinQueue"), "join"));
        assertThat(match.calls).hasSize(4);
        match.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getId()).isEqualTo(5);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        ch.writeInbound(request(6, status, "s"));
        assertThat(match.calls).as("窗口滑过后照常转发").hasSize(5);
        assertThat(ch.isOpen()).isTrue();
    }

    // ================================================================ 工具

    /** 调用当场抛异常的后端（Dubbo 把后端记为不可用之后的形态）。 */
    private static final class ThrowingBackend implements ClientMessageService {

        int calls;

        @Override
        public CompletableFuture<ClientReply> handle(ClientCall call) {
            calls++;
            throw new IllegalStateException("No provider available for the service match/com.game.api.ClientMessageService");
        }

        @Override
        public CompletableFuture<Ack> sessionClosed(SessionClosed event) {
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Ack> abandonEnter(AbandonedEnter event) {
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }
    }

    /** 真实契约 + 真实路由表 + 生产缺省限频（表外的号每秒 3 条，单调时钟取 {@link #nanos}）。 */
    private ClientDispatcher dispatcher(Map<String, ClientMessageService> backends) {
        return new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), routes, TIP_MSG, login, backends, links, registry,
                new GateLimits(16, 50, Duration.ZERO, MessageLimits.of(Map.of())),
                metrics, new RecordingPresence(), nanos::get);
    }

    /** 一条已握手的连接。 */
    private EmbeddedChannel verified(Map<String, ClientMessageService> backends) {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher(backends)));
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE)
                .setExpireTimestamp(NOW + 600).build().toByteString();
        ch.writeInbound(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();
        return ch;
    }

    /** 走一遍进游戏：login 回 EnterScene 指令，会话绑定玩家 {@link #PLAYER}。 */
    private void enterGame(EmbeddedChannel ch) {
        ch.writeInbound(request(1, ids.requireId("ClientPlayerLogin", "EnterGame"), "enter"));
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                        .setPlayerId(PLAYER).setSceneNodeId(7).setSceneId(900).setOwnerEpoch(5)))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getId()).isEqualTo(1);
        assertThat(links.sent).as("进场帧").hasSize(1);
    }

    private ClientSession session() {
        return registry.all().iterator().next();
    }

    private static ClientRequest request(long id, int messageId, String body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.copyFromUtf8(body)).build();
    }

    /** 下一帧出站恰好是带请求号的信封 1003（以请求的消息号为 message_id、没有应答体），且后面没有别的帧。 */
    private static void expectEnvelope1003(EmbeddedChannel ch, MessageMethod method, long requestId) {
        MessageContent envelope = ch.readOutbound();
        assertThat(envelope).as(method.key() + " 回信封").isNotNull();
        assertThat(envelope.getMessageId()).as(method.key() + "：信封的 message_id 是请求的消息号，不是 23 推送").isEqualTo(method.messageId());
        assertThat(envelope.getId()).as(method.key() + " 带请求号").isEqualTo(requestId);
        assertThat(envelope.getErrorMessage().getId()).as(method.key()).isEqualTo(1003);
        assertThat(envelope.getErrorMessage().getParametersList()).isEmpty();
        assertThat(envelope.getSerializedMessage()).isEmpty();
        assertThat((Object) ch.readOutbound()).as(method.key() + "：只有一帧").isNull();
    }

    private Collection<Counter> forwarded() {
        return meters.find("xm.gate.client.requests").tag("route", DubboGroups.MATCH).tag("result", "forwarded").counters();
    }

    private Collection<Counter> requestCounters(String result) {
        return meters.find("xm.gate.client.requests").tag("result", result).counters();
    }

    /** gate 主动断开的总次数（各原因之和）。 */
    private double disconnects() {
        return meters.find("xm.gate.disconnects").counters().stream().mapToDouble(Counter::count).sum();
    }
}
