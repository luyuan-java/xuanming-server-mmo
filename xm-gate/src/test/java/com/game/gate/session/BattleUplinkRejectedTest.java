package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.SessionDirective;
import com.game.common.killswitch.KillSwitch;
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
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 战斗上行在大厅连接上当场被拒的钉住测试（批次 6.2 建，6.3 按 D12 补齐；inventory combat.md gate-battle-uplink-reject；
 * battle-node-spec §3.7、§13.7；scene-battle-spec §2.5、§7.19、§13.6）：大厅连接上发 {@code BattleClientPlayer} 的任何号
 * （140 / 149 / 162 / 165 与各 Notify 号，共 12 个）都当场回 23 {1003}，不计非法包、不断连、不转发——战斗上行只走 xm-battle
 * 直连（身份只来自票据）。「当场」指基线的位置（{@code client_message_processor.cpp:937-950}）：体积 / 限频 / GM 闸之后、
 * 按域分派之前，所以不受热关停影响、不排在在途的 login 调用之后、待处理队列满了也不因它断连。
 *
 * <p>用同步进来的真实契约与真实路由表，限频取生产缺省（表外的号每秒 3 条）：以后有人摘掉 {@link MessageRoute#directOnly()}、
 * 把这个服务接进 {@link MessageRoutes#SERVICE_BACKENDS} 或把这道闸挪到别处，这里先失败。手写路由下的闸序细节见
 * {@code ClientDispatcherTest} 的「战斗上行」一节。
 */
class BattleUplinkRejectedTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;
    private static final String UNSUPPORTED = MessageRoutes.BACKEND_UNSUPPORTED;
    /** 非法包阈值取最小：12 个号要是有一个计了非法包，连接就会被断开。 */
    private static final int STRICT = 1;
    private static final KillSwitch.Rule DENY = new KillSwitch.Rule(true, "止血", 0);

    private final MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
    private final MessageRoutes routes = MessageRoutes.of(ids);
    /** 契约里 BattleClientPlayer 的全部方法，按消息号升序。 */
    private final List<MessageMethod> battle = ids.all().stream()
            .filter(m -> m.serviceName().equals("BattleClientPlayer"))
            .sorted(Comparator.comparingInt(MessageMethod::messageId)).toList();
    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLogin friend = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);
    /** 限频用的单调时钟：用例自己推进，不依赖真实耗时。 */
    private final AtomicLong nanos = new AtomicLong(1);

    @Test
    void 大厅上发战斗服务的每个号_都推23的1003_不计非法包不断连不转发() {
        EmbeddedChannel ch = verified(16, STRICT, Map.of("friend", friend));
        assertThat(battle).extracting(MessageMethod::messageId)
                .containsExactly(139, 140, 143, 144, 149, 150, 158, 161, 162, 165, 166, 177);

        long requestId = 1;
        for (MessageMethod method : battle) {
            ch.writeInbound(request(requestId++, method.messageId(), "battle"));
            expectTip1003(ch, method.key());
        }

        ClientSession session = session();
        assertThat(session.illegalPackets).as("合法协议号，不计非法包").isZero();
        assertThat(ch.isOpen()).as("不断连").isTrue();
        assertThat(session.pending).as("不进待处理队列").isEmpty();
        assertThat(session.backendQueues).as("不进任何后端队列").isEmpty();
        assertThat(session.inFlight).isFalse();
        assertThat(login.calls).as("不转给 login").isEmpty();
        assertThat(friend.calls).as("不转给任何后端").isEmpty();
        assertThat(links.sent).as("不转给 scene").isEmpty();
        assertThat(battleRejected()).as("12 个号各计一次 result=battle_rejected").hasSize(12)
                .extracting(Counter::count).containsOnly(1.0);
        assertThat(battleRejected()).extracting(c -> c.getId().getTag("route")).containsOnly(UNSUPPORTED);
        assertThat(requestCounters("unsupported")).as("6.2 的口径（缺省分支的 unsupported）不再出现").isEmpty();
        assertThat(disconnects()).isZero();

        int login48 = ids.requireId("ClientPlayerLogin", "Login");
        ch.writeInbound(request(requestId, login48, "x"));
        assertThat(login.calls).as("同一连接上的正常请求照常转发").singleElement()
                .satisfies(c -> assertThat(c.getMessageId()).isEqualTo(login48));
    }

    @Test
    void 战斗上行拒绝的12条时间序列_装配时预建为0_拒绝时就地加一_不新增序列() {
        EmbeddedChannel ch = verified(16, STRICT, Map.of());

        Collection<Counter> prebuilt = meters.find("xm.gate.client.requests").counters();
        assertThat(prebuilt).as("还没有任何请求：只有预建的 12 条").hasSize(12).allSatisfy(c -> {
            assertThat(c.count()).isZero();
            assertThat(c.getId().getTag("result")).isEqualTo("battle_rejected");
            assertThat(c.getId().getTag("route")).isEqualTo(UNSUPPORTED);
        });
        assertThat(prebuilt).extracting(c -> c.getId().getTag("method")).containsExactlyInAnyOrderElementsOf(
                battle.stream().map(m -> "BattleClientPlayer." + m.methodName()).toList());

        MessageMethod submit = method("SubmitBattleAction");
        ch.writeInbound(request(1, submit.messageId(), "action"));
        expectTip1003(ch, submit.key());

        assertThat(rejected(submit)).isEqualTo(1);
        assertThat(meters.find("xm.gate.client.requests").counters()).as("计在预建的那条上，没有新序列").hasSize(12);
        assertThat(battleRejected()).extracting(Counter::count).containsOnlyOnce(1.0).filteredOn(v -> v == 0.0).hasSize(11);
    }

    @Test
    void login调用在途时_12个战斗号都当场回_不进待处理队列_不等login完成() {
        EmbeddedChannel ch = verified(16, STRICT, Map.of());
        int login48 = ids.requireId("ClientPlayerLogin", "Login");
        int listSkills = ids.requireId("SceneSkillClientPlayer", "ListSkills");
        ch.writeInbound(request(1, login48, "slow"));
        ch.writeInbound(request(2, listSkills, "queued"));
        ClientSession session = session();
        assertThat(session.inFlight).as("login 调用在途").isTrue();
        assertThat(session.pending).as("对照：普通请求排在在途调用之后").hasSize(1);
        assertThat((Object) ch.readOutbound()).isNull();

        long requestId = 10;
        for (MessageMethod method : battle) {
            ch.writeInbound(request(requestId++, method.messageId(), "battle"));
            expectTip1003(ch, method.key() + "（login 还没回来）");
            assertThat(session.pending).as(method.key() + " 不进队列").hasSize(1)
                    .allSatisfy(p -> assertThat(p.request().getMessageId()).isEqualTo(listSkills));
        }
        assertThat(session.inFlight).isTrue();
        assertThat(login.calls).hasSize(1);
        assertThat(battleRejected()).extracting(Counter::count).containsOnly(1.0).hasSize(12);

        // login 回来：先回它自己的应答，再轮到排队的 scene 请求（没进场景 → 23 {1003}）；战斗 tip 不会再发第二遍
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(login48);
        assertThat(reply.getId()).isEqualTo(1);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("login-resp");
        expectTip1003(ch, "排队的 scene 请求");
        assertThat(battleRejected()).extracting(Counter::count).containsOnly(1.0).hasSize(12);
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 待处理队列已满时_12个战斗号照常当场回_不断连_队列不变_下一条普通请求才溢出() {
        EmbeddedChannel ch = verified(2, STRICT, Map.of());
        // Login 在途，CreatePlayer / EnterGame 排队：队列到上限 2
        ch.writeInbound(request(1, ids.requireId("ClientPlayerLogin", "Login"), "a"));
        ch.writeInbound(request(2, ids.requireId("ClientPlayerLogin", "CreatePlayer"), "b"));
        ch.writeInbound(request(3, ids.requireId("ClientPlayerLogin", "EnterGame"), "c"));
        ClientSession session = session();
        assertThat(session.pending).hasSize(2);
        assertThat(ch.isOpen()).isTrue();

        long requestId = 10;
        for (MessageMethod method : battle) {
            ch.writeInbound(request(requestId++, method.messageId(), "battle"));
            expectTip1003(ch, method.key() + "（队列已满）");
            assertThat(ch.isOpen()).as(method.key() + " 不触发溢出断连").isTrue();
        }

        assertThat(session.pending).as("队列原样").hasSize(2);
        assertThat(session.illegalPackets).isZero();
        assertThat(disconnects()).isZero();
        assertThat(requestCounters("overflow")).isEmpty();
        assertThat(battleRejected()).extracting(Counter::count).containsOnly(1.0).hasSize(12);
        assertThat(login.calls).hasSize(1);

        // 对照：队列确实是满的——再来一条普通请求就溢出断开
        ch.writeInbound(request(4, ids.requireId("ClientPlayerLogin", "LeaveGame"), "d"));
        assertThat(ch.isOpen()).isFalse();
        assertThat(meters.get("xm.gate.disconnects").tag("reason", "pending_overflow").counter().count()).isEqualTo(1);
        assertThat(requestCounters("overflow")).extracting(Counter::count).containsExactly(1.0);
    }

    @Test
    void 热关停命中战斗方法时_仍推23的1003_不是信封1003_不计killed_不记短路() {
        KillSwitch killSwitch = new KillSwitch(-1, System::nanoTime);
        List<String> blocked = new ArrayList<>();
        killSwitch.onBlocked(blocked::add);
        KillSwitch.installGlobal(killSwitch);
        try {
            EmbeddedChannel ch = verified(16, STRICT, Map.of("friend", friend));
            MessageMethod submit = method("SubmitBattleAction");
            long requestId = 1;

            // 精确规则：运维只关了 SubmitBattleAction（§7.19 的原话「热关停了某个战斗方法」）
            killSwitch.setRules(Map.of("BattleClientPlayer/SubmitBattleAction", DENY));
            assertThat(killSwitch.blocked(routes.clientRoute(submit.messageId()).rpcPath())).as("规则确实命中").isPresent();
            ch.writeInbound(request(requestId++, submit.messageId(), "action"));
            expectTip1003(ch, "精确规则 " + submit.key());

            // 服务通配、全局规则：12 个号都命中，12 个号都仍是 23 {1003}
            for (String key : List.of("BattleClientPlayer/*", "*")) {
                nanos.addAndGet(Duration.ofSeconds(1).toNanos());
                killSwitch.setRules(Map.of(key, DENY));
                for (MessageMethod method : battle) {
                    assertThat(killSwitch.blocked(routes.clientRoute(method.messageId()).rpcPath()))
                            .as("规则 " + key + " 命中 " + method.key()).isPresent();
                    ch.writeInbound(request(requestId++, method.messageId(), "battle"));
                    expectTip1003(ch, "规则 " + key + " 下的 " + method.key());
                }
            }

            assertThat(blocked).as("没有一条记成热关停短路").isEmpty();
            assertThat(requestCounters("killed")).isEmpty();
            assertThat(rejected(submit)).isEqualTo(3);
            assertThat(battleRejected()).extracting(Counter::count).containsOnly(2.0, 3.0).hasSize(12);
            assertThat(session().illegalPackets).isZero();

            // 对照：全局规则还在，同一连接上的普通请求回的是带请求 id 的信封 1003，并记一次短路
            int login48 = ids.requireId("ClientPlayerLogin", "Login");
            ch.writeInbound(request(99, login48, "x"));
            MessageContent envelope = ch.readOutbound();
            assertThat(envelope.getMessageId()).isEqualTo(login48);
            assertThat(envelope.getId()).isEqualTo(99);
            assertThat(envelope.getErrorMessage().getId()).isEqualTo(1003);
            assertThat(envelope.getSerializedMessage()).isEmpty();
            assertThat(login.calls).isEmpty();
            assertThat(blocked).containsExactly("ClientPlayerLogin.Login");
            assertThat(requestCounters("killed")).extracting(Counter::count).containsExactly(1.0);
            assertThat(ch.isOpen()).isTrue();
        } finally {
            KillSwitch.installGlobal(null);
        }
    }

    @Test
    void 战斗域即使被人配了后端_12个号也到不了后端() {
        // §7.19：6.4 把 MatchService 接入 gate 之后 BattleClientPlayer 仍被拒。这里把夹具做到最坏：战斗路由实际所在的域
        // （unsupported）和想得到的域名都配上后端——直连闸排在后端分派之前，一条也转不出去。
        FakeLogin rogue = new FakeLogin();
        EmbeddedChannel ch = verified(16, STRICT, Map.of(UNSUPPORTED, rogue, "battle", rogue, "match", rogue));
        for (MessageMethod method : battle) {
            assertThat(routes.clientRoute(method.messageId()).domain()).as(method.key()).isEqualTo(UNSUPPORTED);
        }

        long requestId = 1;
        for (MessageMethod method : battle) {
            ch.writeInbound(request(requestId++, method.messageId(), "battle"));
            expectTip1003(ch, method.key());
        }

        assertThat(rogue.calls).as("大厅连接不能成为绕过直连票据的第二条战斗通路").isEmpty();
        assertThat(session().backendQueues).isEmpty();
        assertThat(requestCounters("forwarded")).isEmpty();
        assertThat(battleRejected()).extracting(Counter::count).containsOnly(1.0).hasSize(12);
    }

    @Test
    void 已进场景的会话发战斗号_同样当场拒_不转给scene() {
        EmbeddedChannel ch = verified(16, STRICT, Map.of());
        ch.writeInbound(request(1, ids.requireId("ClientPlayerLogin", "EnterGame"), "enter"));
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                        .setPlayerId(42).setSceneNodeId(7).setSceneId(900).setOwnerEpoch(5)))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getId()).isEqualTo(1);
        assertThat(links.sent).as("进场帧").hasSize(1);
        assertThat(session().sceneNodeId).isEqualTo(7);

        long requestId = 10;
        for (MessageMethod method : battle) {
            ch.writeInbound(request(requestId++, method.messageId(), "battle"));
            expectTip1003(ch, method.key());
        }
        assertThat(links.sent).as("没有一帧 ClientForward").hasSize(1);
        assertThat(session().sceneNodeId).as("场景绑定不受影响").isEqualTo(7);

        int listSkills = ids.requireId("SceneSkillClientPlayer", "ListSkills");
        ch.writeInbound(request(requestId, listSkills, "skills"));
        assertThat(links.sent).as("对照：scene 号照常转发").hasSize(2);
        assertThat(links.last().frame().getClientForward().getMessageId()).isEqualTo(listSkills);
    }

    @Test
    void 战斗号同样先过体积与限频_超频回1008_超长回1010_都计非法包() {
        // 基线闸序：白名单 → 体积与限频（ValidateClientMessage）→ GM 闸 → 战斗拒绝。被直连闸拒绝的请求照样占限频额度，
        // 否则「拒绝不占额度」会让这组号成为无成本刷 tip 的口子
        EmbeddedChannel ch = verified(16, 50, Map.of());
        MessageMethod submit = method("SubmitBattleAction");
        for (int i = 1; i <= 3; i++) {
            ch.writeInbound(request(i, submit.messageId(), "action"));
            expectTip1003(ch, "窗口内第 " + i + " 条");
        }

        ch.writeInbound(request(4, submit.messageId(), "action"));
        MessageContent limited = ch.readOutbound();
        assertThat(limited.getMessageId()).as("超频：以请求号为 message_id 的信封").isEqualTo(submit.messageId());
        assertThat(limited.getId()).isEqualTo(4);
        assertThat(limited.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_RATE_LIMIT_EXCEEDED).isEqualTo(1008);
        assertThat(session().illegalPackets).isEqualTo(1);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        ch.writeInbound(request(5, submit.messageId(), "action"));
        expectTip1003(ch, "窗口滑过后");

        ch.writeInbound(ClientRequest.newBuilder().setId(6).setMessageId(submit.messageId())
                .setBody(ByteString.copyFrom(new byte[ClientDispatcher.MAX_REQUEST_BYTES])).build());
        MessageContent oversized = ch.readOutbound();
        assertThat(oversized.getMessageId()).isEqualTo(submit.messageId());
        assertThat(oversized.getId()).isEqualTo(6);
        assertThat(oversized.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_MESSAGE_SIZE_EXCEEDED).isEqualTo(1010);
        assertThat(session().illegalPackets).isEqualTo(2);

        assertThat(rejected(submit)).isEqualTo(4);
        assertThat(requestCounters("rate_limited")).extracting(Counter::count).containsExactly(1.0);
        assertThat(requestCounters("oversized")).extracting(Counter::count).containsExactly(1.0);
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 未握手的连接发战斗号_直接断开不回包_不计battle_rejected() {
        // 基线：令牌校验排在最前（:858-863），不因为是战斗号就给未认证的连接回 tip
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher(16, STRICT, Map.of())));
        ch.writeInbound(request(1, method("SubmitBattleAction").messageId(), "action"));
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(battleRejected()).extracting(Counter::count).containsOnly(0.0).hasSize(12);
    }

    // ================================================================ 工具

    /** 真实契约 + 真实路由表 + 生产缺省限频（表外的号每秒 3 条，单调时钟取 {@link #nanos}）。 */
    private ClientDispatcher dispatcher(int maxPending, int illegalThreshold, Map<String, ClientMessageService> backends) {
        return new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), routes, TIP_MSG, login, backends, links, registry,
                new GateLimits(maxPending, illegalThreshold, Duration.ZERO, MessageLimits.of(Map.of())),
                metrics, new RecordingPresence(), nanos::get);
    }

    /** 一条已握手的连接。 */
    private EmbeddedChannel verified(int maxPending, int illegalThreshold, Map<String, ClientMessageService> backends) {
        EmbeddedChannel ch = new EmbeddedChannel(
                new ClientChannelHandler(registry, dispatcher(maxPending, illegalThreshold, backends)));
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE)
                .setExpireTimestamp(NOW + 600).build().toByteString();
        ch.writeInbound(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();
        return ch;
    }

    private ClientSession session() {
        return registry.all().iterator().next();
    }

    private MessageMethod method(String name) {
        return battle.stream().filter(m -> m.methodName().equals(name)).findFirst().orElseThrow();
    }

    private static ClientRequest request(long id, int messageId, String body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.copyFromUtf8(body)).build();
    }

    /** 下一帧出站恰好是 23 {@code TipInfoMessage{1003}}（推送形状：id 不填、不带信封错误、没有参数），且后面没有别的帧。 */
    private static void expectTip1003(EmbeddedChannel ch, String what) {
        MessageContent tip = ch.readOutbound();
        assertThat(tip).as(what).isNotNull();
        assertThat(tip.getMessageId()).as(what + "：23 推送，不是以请求号为 message_id 的信封").isEqualTo(TIP_MSG);
        assertThat(tip.getId()).as(what + "：推送形状，id 不填").isZero();
        assertThat(tip.hasErrorMessage()).as(what + "：不是信封错误").isFalse();
        try {
            assertThat(TipInfoMessage.parseFrom(tip.getSerializedMessage())).as(what)
                    .isEqualTo(TipInfoMessage.newBuilder().setId(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).build())
                    .extracting(TipInfoMessage::getId).isEqualTo(1003);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(what, e);
        }
        assertThat((Object) ch.readOutbound()).as(what + "：只有一帧").isNull();
    }

    /** {@code xm.gate.client.requests{result=battle_rejected}} 的全部时间序列。 */
    private Collection<Counter> battleRejected() {
        return requestCounters("battle_rejected");
    }

    private Collection<Counter> requestCounters(String result) {
        return meters.find("xm.gate.client.requests").tag("result", result).counters();
    }

    private double rejected(MessageMethod method) {
        return meters.get("xm.gate.client.requests").tag("route", UNSUPPORTED)
                .tag("method", "BattleClientPlayer." + method.methodName()).tag("result", "battle_rejected").counter().count();
    }

    /** gate 主动断开的总次数（各原因之和）。 */
    private double disconnects() {
        return meters.find("xm.gate.disconnects").counters().stream().mapToDouble(Counter::count).sum();
    }
}
