package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientReply;
import com.game.api.proto.CloseSession;
import com.game.api.proto.EnterScene;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.RedirectToGate;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ToClient;
import com.game.api.proto.UnbindPlayer;
import com.game.api.proto.ZoneRedirect;
import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import com.game.net.limit.MessageLimits;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.RedirectToGateNotify;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * gate 的跨 zone 重定向（批次 5.4，zone-travel-spec §5.7、§11.6）：握手存票据字段并随调用交出、源 scene 的重定向帧与 login 的
 * 重定向指令推 124、已重定向会话的收口。风格同 {@link ClientDispatcherTest}（EmbeddedChannel + 假 login / 假链路），
 * 收口定时器用冻结的 EventLoop 时钟推进。
 */
class ClientRedirectTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    /** 传送的目标 zone。 */
    private static final int OTHER_ZONE = 2;
    /** 目标 zone 里被选中的 gate 节点号（与本 gate 不同 zone，号可以相同，见「他区同号 gate」用例）。 */
    private static final int TARGET_GATE = 1;
    private static final int TIP_MSG = 23;
    private static final int REDIRECT_MSG = 124;
    private static final int LOGIN_MSG = 48;
    private static final int ENTER_GAME_MSG = 26;
    private static final int LEAVE_MSG = 17;
    private static final int SCENE_MSG = 77;
    private static final int TRAVEL_MSG = 226;
    private static final int FRIEND_MSG = 234;
    private static final int UNKNOWN_MSG = 99_999;
    private static final int SCENE_NODE = 7;
    /** 源节点链路代次（{@link FakeLinks} 的缺省代次）。 */
    private static final long SOURCE_GEN = 11;
    private static final long PLAYER = 42L;
    /** 收口时限：故意不用缺省 60 s，钉住会话层用的是装配进来的值。 */
    private static final Duration LINGER = Duration.ofSeconds(20);
    private static final int TIP_ZONE_TRAVEL_FAILED = 3027;

    private static final MessageRoutes ROUTES = id -> switch (id) {
        case LOGIN_MSG, ENTER_GAME_MSG -> new MessageRoute(id, "login");
        case LEAVE_MSG -> new MessageRoute(id, "login", false, "ClientPlayerLogin.LeaveGame");
        case SCENE_MSG, TRAVEL_MSG -> new MessageRoute(id, "scene");
        case FRIEND_MSG -> new MessageRoute(id, "friend");
        default -> null;
    };

    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLogin friend = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);
    private final RecordingPresence presence = new RecordingPresence();
    private final ClientDispatcher dispatcher = dispatcher(new PushMessageIds(TIP_MSG, REDIRECT_MSG));
    private final SceneEventRouter router = new SceneEventRouter(registry, dispatcher);

    private ClientDispatcher dispatcher(PushMessageIds pushIds) {
        return new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES, pushIds, login, Map.of("friend", friend), links,
                registry, new GateLimits(4, 3, Duration.ZERO, MessageLimits.UNLIMITED, false, LINGER), metrics, presence);
    }

    // ================================================================ 握手：票据字段只存不判（§5.7 第 1 条）

    @Test
    void 票据握手_持票者与目标zone存进会话_之后每个login与后端调用_放弃归属与断线通知都带上() {
        Client c = verified(ticket(PLAYER));
        c.ch().writeInbound(request(1, LOGIN_MSG, "login"));
        login.complete(ClientReply.getDefaultInstance());
        c.ch().runPendingTasks();
        c.ch().writeInbound(request(2, FRIEND_MSG, "friend"));
        friend.complete(ClientReply.getDefaultInstance());
        c.ch().runPendingTasks();
        // EnterGame 在途时断线：迟到的进场指令没有写者，请 login 释放（也经会话上下文）
        c.ch().writeInbound(request(3, ENTER_GAME_MSG, "enter"));
        c.ch().close();
        login.complete(enterGameReply());
        c.ch().runPendingTasks();

        List<SessionContext> contexts = new ArrayList<>();
        login.calls.forEach(call -> contexts.add(call.getSession()));
        friend.calls.forEach(call -> contexts.add(call.getSession()));
        login.abandoned.forEach(abandoned -> contexts.add(abandoned.getSession()));
        login.closed.forEach(closed -> contexts.add(closed.getSession()));
        assertThat(contexts).as("login 两次、friend 一次、放弃归属一次、断线通知一次").hasSize(5).allSatisfy(ctx -> {
            assertThat(ctx.getSessionId()).isEqualTo(c.sid());
            assertThat(ctx.getTicketPlayerId()).isEqualTo(PLAYER);
            assertThat(ctx.getTicketTargetZoneId()).isEqualTo(ZONE);
        });
        assertThat(handshakes("ok")).isEqualTo(1);
    }

    @Test
    void 普通令牌_持票者为0_目标zone为签发zone() {
        Client c = verified(normalToken());
        c.ch().writeInbound(request(1, LOGIN_MSG, "x"));

        SessionContext ctx = login.calls.get(0).getSession();
        assertThat(ctx.getTicketPlayerId()).isZero();
        assertThat(ctx.getTicketTargetZoneId()).as("Java 普通令牌钉在签发 zone").isEqualTo(ZONE);
    }

    @Test
    void 已验证的会话再次verify回成功_不重新验票_不覆盖票据字段() {
        Client holder = verified(ticket(PLAYER));
        holder.ch().writeInbound(verify(normalToken()));
        assertThat(((ClientTokenVerifyResponse) holder.ch().readOutbound()).getSuccess()).isTrue();
        holder.ch().writeInbound(request(1, LOGIN_MSG, "x"));
        assertThat(login.calls.get(0).getSession().getTicketPlayerId()).isEqualTo(PLAYER);
        login.complete(ClientReply.getDefaultInstance());
        holder.ch().runPendingTasks();

        Client plain = verified(normalToken());
        plain.ch().writeInbound(verify(ticket(PLAYER + 1)));
        assertThat(((ClientTokenVerifyResponse) plain.ch().readOutbound()).getSuccess()).isTrue();
        plain.ch().writeInbound(request(2, LOGIN_MSG, "x"));
        assertThat(login.calls.get(1).getSession().getTicketPlayerId()).as("第二次出示的票据不生效").isZero();
        assertThat(handshakes("ok")).as("再次 verify 不计握手").isEqualTo(2);
    }

    @Test
    void 他区同号gate的票据_WRONG_ZONE_回token_not_for_this_zone后断开() {
        Client c = connect(dispatcher);
        GateTokenPayload otherZone = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(OTHER_ZONE)
                .setExpireTimestamp(NOW + 300).setPlayerId(PLAYER).setTargetZoneId(OTHER_ZONE).build();

        c.ch().writeInbound(verify(otherZone));

        ClientTokenVerifyResponse rejected = c.ch().readOutbound();
        assertThat(rejected.getSuccess()).isFalse();
        assertThat(rejected.getError()).isEqualTo("token not for this zone");
        assertThat(c.ch().isOpen()).isFalse();
        assertThat(handshakes("wrong_zone")).isEqualTo(1);
        assertThat(disconnects("handshake_rejected")).isEqualTo(1);
        assertThat(login.calls).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void 会话已从会话表释放时_路由层放弃归属用的上下文不带票据字段() {
        Client c = inScene(dispatcher, ticket(PLAYER));
        c.ch().close();
        c.ch().runPendingTasks();
        assertThat(registry.size()).isZero();

        // 5.2 的改绑指令（不带 redirect）赶上会话已释放：路由层按会话号补上下文请 login 放弃
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, PlayerTransfer.newBuilder()
                .setSessionId(c.sid()).setPlayerId(PLAYER).setFromEpoch(5).setToEpoch(6)
                .setTargetSceneNodeId(SCENE_NODE + 1).setTargetSceneId(901).build());

        assertThat(login.abandoned).singleElement().satisfies(abandoned -> {
            assertThat(abandoned.getSession().getSessionId()).isEqualTo(c.sid());
            assertThat(abandoned.getSession().getTicketPlayerId()).isZero();
            assertThat(abandoned.getSession().getTicketTargetZoneId()).isZero();
        });
    }

    // ================================================================ 226 传送：源 scene 的重定向帧（§5.7 第 2 条）

    @Test
    void 重定向帧_解绑不发PlayerLeave_撤在线_推124五项逐字段等于帧且信封不带id_会话转入已重定向() {
        Client c = inScene();
        int framesBefore = links.sent.size();
        PlayerTransfer frame = travel(c.sid(), 5, 6);

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        c.ch().runPendingTasks();

        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());
        assertThat((Object) c.ch().readOutbound()).isNull();
        assertThat(c.ch().isOpen()).as("推 124 不断开").isTrue();
        assertThat(sentSince(framesBefore)).as("不发 PlayerLeave（源已移除实例）、不向任何 scene 发进场").isEmpty();
        assertThat(presence.events).as("撤在线登记")
                .containsExactly(new RecordingPresence.Event(true, PLAYER, c.sid(), 5),
                        new RecordingPresence.Event(false, PLAYER, c.sid(), 0));
        assertThat(login.abandoned).as("E+1 已由源节点释放，没有可放弃的归属").isEmpty();
        ClientSession s = registry.get(c.sid());
        assertThat(s.redirected).isTrue();
        assertThat(s.sceneNodeId).isZero();
        assertThat(s.scenePlayerId).isZero();
        assertThat(s.sceneOwnerEpoch).isZero();
        assertThat(s.playerId).as("玩家绑定不动：断线通知照常带上").isEqualTo(PLAYER);
        assertThat(dispatcher.deliverPush(s, PLAYER, MessageContent.newBuilder().setMessageId(235).build()))
                .as("旅途窗口内服务端推送不再送达").isEqualTo(GateMetrics.PushResult.NOT_BOUND);
        assertThat(redirects("travel", "sent")).isEqualTo(1);
        assertThat(sceneTransfers()).as("重定向帧不计改绑指标").isZero();
    }

    @Test
    void 已重定向之后客户端请求一律静默丢弃_不回包不推1003_不计非法包_计redirected() {
        Client c = redirectedByTravel();
        int framesBefore = links.sent.size();

        c.ch().writeInbound(request(10, SCENE_MSG, "x"));
        c.ch().writeInbound(request(11, TRAVEL_MSG, "x"));
        c.ch().writeInbound(request(12, LOGIN_MSG, "x"));
        c.ch().writeInbound(request(13, FRIEND_MSG, "x"));
        for (int i = 0; i < 5; i++) {
            // 超过非法包阈值（3）也不断开：已重定向的会话不再做入口校验
            c.ch().writeInbound(request(20 + i, UNKNOWN_MSG, "x"));
        }
        c.ch().writeInbound(verify(normalToken()));

        assertThat((Object) c.ch().readOutbound()).as("一切上行无回包（含再次握手）").isNull();
        assertThat(c.ch().isOpen()).isTrue();
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(login.calls).as("只有进游戏那一次").hasSize(1);
        assertThat(friend.calls).isEmpty();
        assertThat(requests("scene", SCENE_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("scene", TRAVEL_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("friend", FRIEND_MSG, "redirected")).isEqualTo(1);
        assertThat(meters.get("xm.gate.client.requests").tag("route", "unknown").tag("method", "unknown")
                .tag("result", "redirected").counter().count()).isEqualTo(5);
        assertThat(requests("scene", SCENE_MSG, "not_in_scene")).as("不走「未进场景推 23 {1003}」").isZero();
        assertThat(registry.get(c.sid()).illegalPackets).isZero();
    }

    @Test
    void 收口时限到点由gate关闭_不推任何tip_原因redirect_linger_断线照常通知login_不发PlayerLeave() {
        Client c = redirectedByTravel();
        int framesBefore = links.sent.size();

        c.ch().advanceTimeBy(LINGER.toMillis() - 1, TimeUnit.MILLISECONDS);
        c.ch().runPendingTasks();
        assertThat(c.ch().isOpen()).as("时限之内不关").isTrue();

        c.ch().advanceTimeBy(1, TimeUnit.MILLISECONDS);
        c.ch().runPendingTasks();
        c.ch().runPendingTasks();

        assertThat(c.ch().isOpen()).isFalse();
        assertThat((Object) c.ch().readOutbound()).as("不推 tip").isNull();
        assertThat(disconnects("redirect_linger")).isEqualTo(1);
        assertThat(sentSince(framesBefore)).as("没有场景绑定，不发 PlayerLeave").isEmpty();
        assertThat(login.closed).singleElement().satisfies(closed -> {
            assertThat(closed.getVoluntary()).isFalse();
            assertThat(closed.getSession().getPlayerId()).isEqualTo(PLAYER);
        });
        assertThat(registry.size()).isZero();
    }

    @Test
    void 客户端先关_收口定时器作废() {
        Client c = redirectedByTravel();
        assertThat(c.ch().runScheduledPendingTasks()).as("推 124 后挂着收口定时器").isPositive();

        // 连接断开（channelInactive）：直接走断线流程。不用 EmbeddedChannel#close——它会顺手取消通道上的全部定时任务，测不出撤销
        dispatcher.onDisconnected(registry.get(c.sid()));
        assertThat(c.ch().runScheduledPendingTasks()).as("断线流程撤销了收口定时器").isEqualTo(-1);
        assertThat(login.closed).as("断线照常通知 login 注销设备窗口").hasSize(1);
        assertThat(registry.size()).isZero();

        c.ch().advanceTimeBy(LINGER.toMillis() * 2, TimeUnit.MILLISECONDS);
        c.ch().runPendingTasks();
        assertThat(disconnects("redirect_linger")).isZero();
        assertThat(login.closed).hasSize(1);
    }

    @Test
    void 定时器到点时会话已在关闭或已不在会话表_什么都不做() {
        Client closing = redirectedByTravel();
        registry.get(closing.sid()).closing = true;
        Client released = redirectedByTravel();
        registry.release(registry.get(released.sid()));

        closing.ch().advanceTimeBy(LINGER.toMillis(), TimeUnit.MILLISECONDS);
        closing.ch().runPendingTasks();
        released.ch().advanceTimeBy(LINGER.toMillis(), TimeUnit.MILLISECONDS);
        released.ch().runPendingTasks();

        assertThat(disconnects("redirect_linger")).isZero();
        assertThat(closing.ch().isOpen()).as("正在关闭的会话由它自己的关闭流程收尾").isTrue();
        assertThat(released.ch().isOpen()).as("到点按会话号查不到会话就什么都不动").isTrue();
        assertThat((Object) closing.ch().readOutbound()).isNull();
        assertThat((Object) released.ch().readOutbound()).isNull();
    }

    @Test
    void 迟到帧_重定向之前源节点的226应答照常先到_之后源节点的下行踢出进场结果重复帧与链路断开都丢弃() {
        Client c = inScene();
        MessageContent travelReply = MessageContent.newBuilder().setMessageId(TRAVEL_MSG).setId(9)
                .setSerializedMessage(ByteString.copyFrom(new byte[] {0x0A, 0x00})).build();
        MessageContent late = MessageContent.newBuilder().setMessageId(66).build();
        PlayerTransfer frame = travel(c.sid(), 5, 6);

        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(c.sid(), travelReply));
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(c.sid(), late));
        router.onPlayerKicked(SCENE_NODE, SOURCE_GEN, PlayerKicked.newBuilder()
                .setSessionId(c.sid()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());
        router.onPlayerEnterResult(SCENE_NODE, SOURCE_GEN, PlayerEnterResult.newBuilder()
                .setSessionId(c.sid()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(3023).build());
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        router.onLinkDown(SCENE_NODE, SOURCE_GEN);
        c.ch().runPendingTasks();

        assertThat((Object) c.ch().readOutbound()).as("226 应答 {0} 恒先于 124（同一链路）").isEqualTo(travelReply);
        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());
        assertThat((Object) c.ch().readOutbound()).as("之后源节点的帧都不再属于这个会话").isNull();
        assertThat(c.ch().isOpen()).as("源链路断开不关已重定向的会话").isTrue();
        assertThat(redirects("travel", "sent")).isEqualTo(1);
        assertThat(redirects("travel", "stale")).as("重复的重定向帧：绑定已清，按过期").isEqualTo(1);
        assertThat(disconnects("kicked")).isZero();
        assertThat(disconnects("scene_link_down")).isZero();
        assertThat(login.abandoned).isEmpty();
    }

    @Test
    void 过期的重定向帧_只计stale_不放弃任何epoch_不推124_绑定不动() {
        Client c = inScene();
        int framesBefore = links.sent.size();

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN + 1, travel(c.sid(), 5, 6));
        router.onPlayerTransfer(SCENE_NODE + 1, SOURCE_GEN, travel(c.sid(), 5, 6));
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, travel(c.sid(), 5, 6).toBuilder().setPlayerId(PLAYER + 1).build());
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, travel(c.sid(), 4, 6));
        c.ch().runPendingTasks();

        assertThat((Object) c.ch().readOutbound()).isNull();
        assertThat(login.abandoned).as("代次 / 节点 / 玩家 / from_epoch 任一对不上：只计过期，不放弃（新 epoch 已释放）").isEmpty();
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(redirects("travel", "stale")).isEqualTo(4);
        assertThat(redirects("travel", "sent")).isZero();
        assertThat(sceneTransfers()).isZero();
        assertThat(registry.get(c.sid()).redirected).isFalse();

        MessageContent notify = MessageContent.newBuilder().setMessageId(66).build();
        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(c.sid(), notify));
        c.ch().runPendingTasks();
        assertThat((Object) c.ch().readOutbound()).as("仍绑定在源节点").isEqualTo(notify);
        c.ch().writeInbound(request(9, SCENE_MSG, "x"));
        assertThat(links.last().frame().getClientForward().getPlayerId()).isEqualTo(PLAYER);
    }

    @Test
    void 会话不在场景里或正在关闭_重定向帧只计stale() {
        // 会话号被复用给一个还没进游戏的新会话：绑定对不上
        Client fresh = verified(normalToken());
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, travel(fresh.sid(), 5, 6));
        fresh.ch().runPendingTasks();
        assertThat((Object) fresh.ch().readOutbound()).isNull();
        assertThat(fresh.ch().isOpen()).isTrue();

        Client closing = inScene();
        registry.get(closing.sid()).closing = true;
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, travel(closing.sid(), 5, 6));
        closing.ch().runPendingTasks();
        assertThat((Object) closing.ch().readOutbound()).isNull();
        assertThat(registry.get(closing.sid()).redirected).isFalse();

        assertThat(redirects("travel", "stale")).isEqualTo(2);
        assertThat(login.abandoned).isEmpty();
    }

    @Test
    void 非法的重定向帧_解绑后推3027断开_不发PlayerLeave_不放弃epoch_计invalid() {
        Map<String, UnaryOperator<PlayerTransfer>> variants = new LinkedHashMap<>();
        variants.put("空地址", t -> withRedirect(t, r -> r.setGateHost("")));
        variants.put("空白地址", t -> withRedirect(t, r -> r.setGateHost("  ")));
        variants.put("端口 0", t -> withRedirect(t, r -> r.setGatePort(0)));
        variants.put("端口越界", t -> withRedirect(t, r -> r.setGatePort(65536)));
        variants.put("端口按 uint32 极大", t -> withRedirect(t, r -> r.setGatePort(-1)));
        variants.put("空票据", t -> withRedirect(t, r -> r.setTokenPayload(ByteString.EMPTY)));
        variants.put("空签名", t -> withRedirect(t, r -> r.setTokenSignature(ByteString.EMPTY)));
        variants.put("同时带目标节点", t -> t.toBuilder().setTargetSceneNodeId(SCENE_NODE + 1).build());
        variants.put("新 epoch 不大于旧 epoch", t -> t.toBuilder().setToEpoch(5).build());

        for (Map.Entry<String, UnaryOperator<PlayerTransfer>> variant : variants.entrySet()) {
            String name = variant.getKey();
            Client c = inScene();
            int framesBefore = links.sent.size();
            int presenceBefore = presence.events.size();

            router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, variant.getValue().apply(travel(c.sid(), 5, 6)));
            c.ch().runPendingTasks();

            assertThat(tipOf(c.ch().readOutbound())).as(name).isEqualTo(TIP_ZONE_TRAVEL_FAILED);
            assertThat((Object) c.ch().readOutbound()).as(name + "：不推 124").isNull();
            assertThat(c.ch().isOpen()).as(name).isFalse();
            assertThat(sentSince(framesBefore)).as(name + "：已解绑，断线不再发 PlayerLeave").isEmpty();
            assertThat(presence.events.subList(presenceBefore, presence.events.size())).as(name + "：撤在线登记")
                    .containsExactly(new RecordingPresence.Event(false, PLAYER, c.sid(), 0));
        }

        int n = variants.size();
        assertThat(redirects("travel", "invalid")).isEqualTo(n);
        assertThat(redirects("travel", "sent")).isZero();
        assertThat(disconnects("transfer_failed")).isEqualTo(n);
        assertThat(login.abandoned).isEmpty();
        assertThat(login.closed).hasSize(n);
        assertThat(sceneTransfers()).isZero();
    }

    @Test
    void 没装配124消息号的gate_重定向帧与重定向指令都按非法收口_绝不发消息号为0的包() {
        ClientDispatcher tipOnly = dispatcher(PushMessageIds.tipOnly(TIP_MSG));
        Client traveller = inScene(tipOnly, normalToken());
        new SceneEventRouter(registry, tipOnly).onPlayerTransfer(SCENE_NODE, SOURCE_GEN, travel(traveller.sid(), 5, 6));
        traveller.ch().runPendingTasks();
        assertThat(tipOf(traveller.ch().readOutbound())).isEqualTo(TIP_ZONE_TRAVEL_FAILED);
        assertThat((Object) traveller.ch().readOutbound()).isNull();
        assertThat(traveller.ch().isOpen()).isFalse();

        Client entering = verified(tipOnly, normalToken());
        entering.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(redirectReply(zoneRedirect()));
        entering.ch().runPendingTasks();
        assertThat(((MessageContent) entering.ch().readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(tipOf(entering.ch().readOutbound())).isEqualTo(TIP_ZONE_TRAVEL_FAILED);
        assertThat((Object) entering.ch().readOutbound()).isNull();
        assertThat(entering.ch().isOpen()).isFalse();

        assertThat(redirects("travel", "invalid")).isEqualTo(1);
        assertThat(redirects("login", "invalid")).isEqualTo(1);
        assertThat(redirects("travel", "sent") + redirects("login", "sent")).isZero();
    }

    // ================================================================ 登录期重定向：login 的 RedirectToGate 指令（§5.7 第 3 条）

    @Test
    void 登录期重定向指令_先回EnterGame应答再推124_会话转入已重定向_不进场景_计login_sent() {
        Client c = verified(normalToken());
        ZoneRedirect redirect = zoneRedirect();
        c.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(redirectReply(redirect));
        c.ch().runPendingTasks();

        MessageContent reply = c.ch().readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(reply.getId()).isEqualTo(1);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("enter-resp{player_id}");
        assertThat(reply.hasErrorMessage()).isFalse();
        assertRedirectNotify(c.ch().readOutbound(), redirect);
        assertThat((Object) c.ch().readOutbound()).isNull();
        assertThat(c.ch().isOpen()).isTrue();
        assertThat(links.sent).as("不进任何场景").isEmpty();
        assertThat(presence.events).isEmpty();
        assertThat(login.abandoned).isEmpty();
        ClientSession s = registry.get(c.sid());
        assertThat(s.redirected).isTrue();
        assertThat(s.playerId).as("不绑定玩家").isZero();
        assertThat(redirects("login", "sent")).isEqualTo(1);
        assertThat(redirects("travel", "sent")).isZero();

        c.ch().writeInbound(request(2, ENTER_GAME_MSG, "again"));
        assertThat(login.calls).as("之后的请求丢弃").hasSize(1);
        assertThat((Object) c.ch().readOutbound()).isNull();

        c.ch().advanceTimeBy(LINGER.toMillis(), TimeUnit.MILLISECONDS);
        c.ch().runPendingTasks();
        c.ch().runPendingTasks();
        assertThat(c.ch().isOpen()).isFalse();
        assertThat((Object) c.ch().readOutbound()).isNull();
        assertThat(disconnects("redirect_linger")).isEqualTo(1);
        assertThat(login.closed).hasSize(1);
    }

    @Test
    void 会话已断或正在断时迟到的重定向指令什么都不发_计stale() {
        Client closed = verified(normalToken());
        closed.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        closed.ch().close();
        login.complete(redirectReply(zoneRedirect()));
        closed.ch().runPendingTasks();
        assertThat((Object) closed.ch().readOutbound()).isNull();
        assertThat(login.closed).as("等在途调用回来后照常通知 login").hasSize(1);
        assertThat(registry.get(closed.sid())).isNull();

        Client closing = verified(normalToken());
        closing.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        registry.get(closing.sid()).closing = true;
        login.complete(redirectReply(zoneRedirect()));
        closing.ch().runPendingTasks();
        assertThat((Object) closing.ch().readOutbound()).isNull();
        assertThat(registry.get(closing.sid()).redirected).isFalse();

        assertThat(redirects("login", "stale")).isEqualTo(2);
        assertThat(redirects("login", "sent")).isZero();
        assertThat(links.sent).isEmpty();
        assertThat(login.abandoned).as("登录期重定向不夺权，没有要释放的归属").isEmpty();
    }

    @Test
    void 非法的重定向指令_应答照发后推3027断开_计login_invalid() {
        Map<String, RedirectToGate> variants = new LinkedHashMap<>();
        variants.put("空地址", RedirectToGate.newBuilder().setRedirect(zoneRedirect().toBuilder().setGateHost("")).build());
        variants.put("端口 0", RedirectToGate.newBuilder().setRedirect(zoneRedirect().toBuilder().setGatePort(0)).build());
        variants.put("端口越界", RedirectToGate.newBuilder().setRedirect(zoneRedirect().toBuilder().setGatePort(70_000)).build());
        variants.put("空票据", RedirectToGate.newBuilder()
                .setRedirect(zoneRedirect().toBuilder().setTokenPayload(ByteString.EMPTY)).build());
        variants.put("空签名", RedirectToGate.newBuilder()
                .setRedirect(zoneRedirect().toBuilder().setTokenSignature(ByteString.EMPTY)).build());
        variants.put("缺 redirect", RedirectToGate.getDefaultInstance());

        for (Map.Entry<String, RedirectToGate> variant : variants.entrySet()) {
            String name = variant.getKey();
            Client c = verified(normalToken());
            c.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
            login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("enter-resp"))
                    .addDirectives(SessionDirective.newBuilder().setRedirectToGate(variant.getValue())).build());
            c.ch().runPendingTasks();

            assertThat(((MessageContent) c.ch().readOutbound()).getMessageId()).as(name + "：应答先发").isEqualTo(ENTER_GAME_MSG);
            assertThat(tipOf(c.ch().readOutbound())).as(name).isEqualTo(TIP_ZONE_TRAVEL_FAILED);
            assertThat((Object) c.ch().readOutbound()).as(name + "：不推 124").isNull();
            assertThat(c.ch().isOpen()).as(name).isFalse();
        }

        assertThat(redirects("login", "invalid")).isEqualTo(variants.size());
        assertThat(redirects("login", "sent")).isZero();
        assertThat(disconnects("transfer_failed")).isEqualTo(variants.size());
        assertThat(links.sent).isEmpty();
    }

    // ================================================================ 已重定向之前转出、之后才回来的应答（§5.7 评审补）

    @Test
    void 已重定向之后回来的LeaveGame应答_指令被忽略_不发PlayerLeave不改会话_收口定时器到点关闭不推tip() {
        Client c = inScene();
        // 传送提交时客户端恰好发了 17 LeaveGame：调用在途
        c.ch().writeInbound(request(2, LEAVE_MSG, ""));
        int framesBefore = links.sent.size();
        PlayerTransfer frame = travel(c.sid(), 5, 6);
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        c.ch().runPendingTasks();
        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());

        login.complete(unbindReply());
        c.ch().runPendingTasks();

        assertThat((Object) c.ch().readOutbound()).as("LeaveGame 应答类型是 Empty，不回包").isNull();
        assertThat(sentSince(framesBefore)).as("UnbindPlayer 被忽略：不发 PlayerLeave").isEmpty();
        ClientSession s = registry.get(c.sid());
        assertThat(s.playerId).as("不改会话").isEqualTo(PLAYER);
        assertThat(s.redirected).isTrue();
        assertThat(c.ch().isOpen()).isTrue();

        c.ch().advanceTimeBy(LINGER.toMillis(), TimeUnit.MILLISECONDS);
        c.ch().runPendingTasks();
        c.ch().runPendingTasks();

        assertThat(c.ch().isOpen()).isFalse();
        assertThat((Object) c.ch().readOutbound()).as("收口不推任何 tip").isNull();
        assertThat(disconnects("redirect_linger")).isEqualTo(1);
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(login.closed).singleElement()
                .extracting(closed -> closed.getSession().getPlayerId()).isEqualTo(PLAYER);
    }

    @Test
    void 已重定向之后回来的login应答体照常下发_其中CloseSession与EnterScene一律忽略() {
        Client c = inScene();
        c.ch().writeInbound(request(3, LOGIN_MSG, "x"));
        PlayerTransfer frame = travel(c.sid(), 5, 6);
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        c.ch().runPendingTasks();
        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());
        int framesBefore = links.sent.size();

        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("login-resp"))
                .addDirectives(SessionDirective.newBuilder().setCloseSession(CloseSession.newBuilder().setTipId(2017)))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .addDirectives(SessionDirective.newBuilder().setRedirectToGate(RedirectToGate.newBuilder()
                        .setRedirect(zoneRedirect())))
                .build());
        c.ch().runPendingTasks();

        MessageContent reply = c.ch().readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(3);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("login-resp");
        assertThat((Object) c.ch().readOutbound()).as("CloseSession 的 tip 不推、不再推第二条 124").isNull();
        assertThat(c.ch().isOpen()).as("CloseSession 由收口定时器覆盖").isTrue();
        assertThat(sentSince(framesBefore)).as("EnterScene 被忽略：不发 PlayerEnter").isEmpty();
        assertThat(registry.get(c.sid()).sceneNodeId).isZero();
        assertThat(disconnects("server_directive")).isZero();
        assertThat(redirects("travel", "sent")).isEqualTo(1);
        assertThat(redirects("login", "stale")).as("已重定向的会话上又来的重定向指令按过期计").isEqualTo(1);
        assertThat(redirects("login", "sent")).isZero();
    }

    @Test
    void 推124时排队中的请求一律丢弃计redirected_在途调用的应答照常下发_之后不再转发() {
        Client c = inScene();
        c.ch().writeInbound(request(2, LOGIN_MSG, "in-flight"));
        c.ch().writeInbound(request(3, SCENE_MSG, "queued"));
        c.ch().writeInbound(request(4, LOGIN_MSG, "queued"));
        c.ch().writeInbound(request(5, FRIEND_MSG, "in-flight"));
        c.ch().writeInbound(request(6, FRIEND_MSG, "queued"));
        int framesBefore = links.sent.size();
        PlayerTransfer frame = travel(c.sid(), 5, 6);

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        c.ch().runPendingTasks();
        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());

        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("friend-resp")).build());
        c.ch().runPendingTasks();

        assertThat(((MessageContent) c.ch().readOutbound()).getId()).as("在途 login 调用的应答照常下发").isEqualTo(2);
        assertThat(((MessageContent) c.ch().readOutbound()).getId()).as("在途好友调用的应答照常下发").isEqualTo(5);
        assertThat((Object) c.ch().readOutbound()).isNull();
        assertThat(login.calls).as("进游戏一次 + 在途一次；排队的不再发").hasSize(2);
        assertThat(friend.calls).hasSize(1);
        assertThat(sentSince(framesBefore)).as("排队的 scene 请求不再转发").isEmpty();
        assertThat(requests("scene", SCENE_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("friend", FRIEND_MSG, "redirected")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "forwarded")).isEqualTo(1);
        assertThat(requests("friend", FRIEND_MSG, "forwarded")).isEqualTo(1);
        assertThat(requests("scene", SCENE_MSG, "dropped") + requests("login", LOGIN_MSG, "dropped")
                + requests("friend", FRIEND_MSG, "dropped")).as("每个请求只计一次去向").isZero();
    }

    // ================================================================ 工具

    /** 一条测试连接与它的会话号。 */
    private record Client(EmbeddedChannel ch, int sid) {
    }

    private Client connect(ClientDispatcher d) {
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, d));
        ch.freezeTime();
        int sid = registry.all().stream().filter(s -> s.channel() == ch).findFirst().orElseThrow().sessionId();
        return new Client(ch, sid);
    }

    private Client verified(GateTokenPayload token) {
        return verified(dispatcher, token);
    }

    private Client verified(ClientDispatcher d, GateTokenPayload token) {
        Client c = connect(d);
        c.ch().writeInbound(verify(token));
        ClientTokenVerifyResponse ok = c.ch().readOutbound();
        assertThat(ok.getSuccess()).isTrue();
        return c;
    }

    /** 普通令牌握手、进游戏（EnterScene：节点 7、epoch 5、代次 11）并经 scene 确认进场（已登记在线）的会话。 */
    private Client inScene() {
        return inScene(dispatcher, normalToken());
    }

    private Client inScene(ClientDispatcher d, GateTokenPayload token) {
        Client c = verified(d, token);
        c.ch().writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(enterGameReply());
        c.ch().runPendingTasks();
        MessageContent enterGameResponse = c.ch().readOutbound();
        assertThat(enterGameResponse.getMessageId()).isEqualTo(ENTER_GAME_MSG);
        new SceneEventRouter(registry, d).onPlayerEnterResult(SCENE_NODE, SOURCE_GEN, PlayerEnterResult.newBuilder()
                .setSessionId(c.sid()).setPlayerId(PLAYER).setOwnerEpoch(5).build());
        c.ch().runPendingTasks();
        assertThat(registry.get(c.sid()).presenceOnline).isTrue();
        return c;
    }

    /** 已在场景里、收到源节点重定向帧（5 → 6）并推过 124 的会话。 */
    private Client redirectedByTravel() {
        Client c = inScene();
        PlayerTransfer frame = travel(c.sid(), 5, 6);
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, frame);
        c.ch().runPendingTasks();
        assertRedirectNotify(c.ch().readOutbound(), frame.getRedirect());
        return c;
    }

    /** Java 普通登录令牌（gateway 签）：不带持票者，target_zone_id 钉在签发 zone。 */
    private static GateTokenPayload normalToken() {
        return GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE).setExpireTimestamp(NOW + 600)
                .setHmacSessionKey(ByteString.copyFrom(new byte[32])).setTargetZoneId(ZONE).build();
    }

    /** 来到本 zone 的重定向票据（scene-manager 签）：带持票者、不带会话密钥。 */
    private static GateTokenPayload ticket(long holder) {
        return GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE).setExpireTimestamp(NOW + 300)
                .setPlayerId(holder).setTargetZoneId(ZONE).build();
    }

    private ClientTokenVerifyRequest verify(GateTokenPayload token) {
        ByteString payload = token.toByteString();
        return ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build();
    }

    /** scene-manager 为去 zone 2 签的重定向（票据原字节 + hex 签名）。 */
    private ZoneRedirect zoneRedirect() {
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(TARGET_GATE).setZoneId(OTHER_ZONE)
                .setExpireTimestamp(NOW + 300).setPlayerId(PLAYER).setTargetZoneId(OTHER_ZONE).build().toByteString();
        return ZoneRedirect.newBuilder()
                .setTargetZoneId(OTHER_ZONE)
                .setGateNodeId(TARGET_GATE)
                .setGateHost("10.0.2.15")
                .setGatePort(11010)
                .setTokenPayload(payload)
                .setTokenSignature(tokens.sign(payload))
                .setTokenDeadline(NOW + 300)
                .build();
    }

    /** 源节点发来的跨 zone 传送重定向帧（目标节点 / 场景为 0）。 */
    private PlayerTransfer travel(int sessionId, long fromEpoch, long toEpoch) {
        return PlayerTransfer.newBuilder()
                .setSessionId(sessionId).setPlayerId(PLAYER)
                .setFromEpoch(fromEpoch).setToEpoch(toEpoch)
                .setRedirect(zoneRedirect())
                .build();
    }

    private static PlayerTransfer withRedirect(PlayerTransfer transfer, UnaryOperator<ZoneRedirect.Builder> change) {
        return transfer.toBuilder().setRedirect(change.apply(transfer.getRedirect().toBuilder())).build();
    }

    private static ClientReply redirectReply(ZoneRedirect redirect) {
        return ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp{player_id}"))
                .addDirectives(SessionDirective.newBuilder().setRedirectToGate(RedirectToGate.newBuilder().setRedirect(redirect)))
                .build();
    }

    /** 124：消息号 124、信封不带 id 与 error_message，五项逐字段等于重定向载荷（地址、端口、票据原字节、签名、过期时刻）。 */
    private static void assertRedirectNotify(Object outbound, ZoneRedirect expected) {
        assertThat(outbound).isInstanceOf(MessageContent.class);
        MessageContent content = (MessageContent) outbound;
        assertThat(content.getMessageId()).isEqualTo(REDIRECT_MSG);
        assertThat(content.getId()).as("推送不设 id").isZero();
        assertThat(content.hasErrorMessage()).isFalse();
        RedirectToGateNotify notify;
        try {
            notify = RedirectToGateNotify.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
        assertThat(notify.getTargetIp()).isEqualTo(expected.getGateHost());
        assertThat(notify.getTargetPort()).isEqualTo(expected.getGatePort());
        assertThat(notify.getTokenPayload()).as("票据原字节").isEqualTo(expected.getTokenPayload());
        assertThat(notify.getTokenSignature()).isEqualTo(expected.getTokenSignature());
        assertThat(notify.getTokenDeadline()).isEqualTo(expected.getTokenDeadline());
        assertThat(notify.getUnknownFields().asMap()).isEmpty();
    }

    private static EnterScene enterScene() {
        return EnterScene.newBuilder().setPlayerId(PLAYER).setSceneNodeId(SCENE_NODE).setSceneId(900).setOwnerEpoch(5).build();
    }

    private static ClientReply enterGameReply() {
        return ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build();
    }

    private static ClientReply unbindReply() {
        return ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setUnbindPlayer(UnbindPlayer.getDefaultInstance()))
                .build();
    }

    private static ToClient toClient(int sessionId, MessageContent content) {
        return ToClient.newBuilder().addSessionIds(sessionId).setMessageContent(content.toByteString()).build();
    }

    private static ClientRequest request(long id, int messageId, String body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.copyFromUtf8(body)).build();
    }

    private List<FakeLinks.Sent> sentSince(int index) {
        return links.sent.subList(index, links.sent.size());
    }

    private double redirects(String source, String result) {
        return meters.get("xm.gate.redirects").tag("source", source).tag("result", result).counter().count();
    }

    private double sceneTransfers() {
        return meters.find("xm.gate.scene.transfers").counters().stream().mapToDouble(Counter::count).sum();
    }

    private double handshakes(String result) {
        return meters.get("xm.gate.handshakes").tag("result", result).counter().count();
    }

    private double disconnects(String reason) {
        return meters.get("xm.gate.disconnects").tag("reason", reason).counter().count();
    }

    /** 测试路由的方法名缺省是消息号本身（见 {@link MessageRoute}）；没出现过的组合计 0。 */
    private double requests(String route, int messageId, String result) {
        Counter counter = meters.find("xm.gate.client.requests").tag("route", route)
                .tag("method", Integer.toString(messageId)).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private static int tipOf(Object outbound) {
        MessageContent content = (MessageContent) outbound;
        assertThat(content.getMessageId()).isEqualTo(TIP_MSG);
        assertThat(content.hasErrorMessage()).isFalse();
        try {
            return TipInfoMessage.parseFrom(content.getSerializedMessage()).getId();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
