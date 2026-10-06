package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.BindAccount;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.CloseSession;
import com.game.api.proto.EnterScene;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ToClient;
import com.game.api.proto.UnbindPlayer;
import com.game.common.killswitch.KillSwitch;
import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.gate.metrics.GateMetrics;
import com.game.net.limit.MessageLimit;
import com.game.net.limit.MessageLimits;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClientDispatcherTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;
    private static final int LOGIN_MSG = 48;
    private static final int CREATE_MSG = 14;
    private static final int ENTER_GAME_MSG = 26;
    private static final int LEAVE_MSG = 17;
    private static final int SCENE_MSG = 77;
    private static final int GM_MSG = 37;
    /** 未接入 Java 版的后端域（夹具里不配后端），不是只走直连的号。 */
    private static final int UNSUPPORTED_MSG = 500;
    /** 战斗上行 149（只走直连：directOnly；域同生产路由，是 unsupported）。方法名 / 热关停键取缺省（消息号本身）。 */
    private static final int BATTLE_MSG = 149;
    /** 战斗 Notify 号 150（应答类型 Empty 的推送占位，客户端发上来同样当场拒）。 */
    private static final int BATTLE_NOTIFY_MSG = 150;
    /** 假想有人把战斗服务接到了某个后端（域 friend，夹具里配了后端）：直连闸排在后端分派之前，仍被拒。 */
    private static final int BATTLE_VIA_BACKEND_MSG = 162;
    /** 契约里不存在的组合（GM 名字的战斗号），只用来钉闸序：GM 闸在直连闸之前。 */
    private static final int BATTLE_GM_MSG = 165;
    /** 帮会推送占位 220（应答类型 Empty：tip 为 0 不回包，tip ≠ 0 回信封）。 */
    private static final int GUILD_NOTIFY_MSG = 220;
    private static final int FRIEND_MSG = 234;
    /** 聚宝斋浏览 196（trade 域；rpcPath 用生产形状，热关停按 trade.ClientPlayerJubaozhai/* 匹配）。 */
    private static final int TRADE_MSG = 196;
    private static final int SCENE_NODE = 7;
    private static final long PLAYER = 42L;

    private static final MessageRoutes ROUTES = id -> switch (id) {
        case LOGIN_MSG, CREATE_MSG, ENTER_GAME_MSG -> new MessageRoute(id, "login");
        case LEAVE_MSG -> new MessageRoute(id, "login", false, "ClientPlayerLogin.LeaveGame");
        case SCENE_MSG -> new MessageRoute(id, "scene");
        case GM_MSG -> new MessageRoute(id, "scene", true, Integer.toString(id), true);
        case UNSUPPORTED_MSG -> new MessageRoute(id, MessageRoutes.BACKEND_UNSUPPORTED);
        case BATTLE_MSG -> directOnly(id, MessageRoutes.BACKEND_UNSUPPORTED, true, false);
        case BATTLE_NOTIFY_MSG -> directOnly(id, MessageRoutes.BACKEND_UNSUPPORTED, false, false);
        case BATTLE_VIA_BACKEND_MSG -> directOnly(id, "friend", true, false);
        case BATTLE_GM_MSG -> directOnly(id, MessageRoutes.BACKEND_UNSUPPORTED, true, true);
        case GUILD_NOTIFY_MSG -> new MessageRoute(id, "guild", false, "GuildService.NotifyGuildChanged");
        case FRIEND_MSG -> new MessageRoute(id, "friend");
        case TRADE_MSG -> new MessageRoute(id, "trade", true, "ClientPlayerJubaozhai.BrowseListings", false,
                "/trade.ClientPlayerJubaozhai/BrowseListings");
        default -> null;
    };

    /** 只走直连的路由；方法名与热关停键都取消息号本身（与别的手写路由同形，{@link #requests} 按它查指标）。 */
    private static MessageRoute directOnly(int id, String domain, boolean hasResponse, boolean gm) {
        return new MessageRoute(id, domain, hasResponse, Integer.toString(id), gm, "/" + id, true);
    }

    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    /** friend 后端（与 login 同一条 Dubbo 契约，复用同一个替身）。 */
    private final FakeLogin friend = new FakeLogin();
    /** guild 后端（同上）。 */
    private final FakeLogin guild = new FakeLogin();
    /** trade 后端（同上）。 */
    private final FakeLogin trade = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);
    private final RecordingPresence presence = new RecordingPresence();
    private final ClientDispatcher dispatcher = new ClientDispatcher(
            new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
            ROUTES, TIP_MSG, login, Map.of("friend", friend, "guild", guild, "trade", trade), links, registry,
            new GateLimits(4, 3, Duration.ZERO), metrics, presence);
    private final SceneEventRouter router = new SceneEventRouter(registry, dispatcher);

    // ================================================================ 握手

    @Test
    void 握手通过回成功_重复握手再回成功() {
        EmbeddedChannel ch = connect();
        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        assertThat((Object) ch.readOutbound()).isEqualTo(ClientTokenVerifyResponse.newBuilder().setSuccess(true).build());

        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        ClientTokenVerifyResponse again = ch.readOutbound();
        assertThat(again.getSuccess()).isTrue();
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 握手失败先回原因再断开并释放会话号() {
        EmbeddedChannel ch = connect();
        ch.writeInbound(verifyRequest(GATE_NODE + 1, NOW + 600));
        ClientTokenVerifyResponse rejected = ch.readOutbound();
        assertThat(rejected.getSuccess()).isFalse();
        assertThat(rejected.getError()).isEqualTo("token not for this gate");
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void 握手被拒后同一批到达的业务包不再处理() {
        EmbeddedChannel ch = connect();
        ClientSession session = registry.all().iterator().next();
        session.closing = true;
        dispatcher.onRequest(session, request(1, LOGIN_MSG, "x"));
        dispatcher.onTokenVerify(session, verifyRequest(GATE_NODE, NOW + 600));
        assertThat(login.calls).isEmpty();
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void 过期令牌与签名错误都被拒() {
        EmbeddedChannel expired = connect();
        expired.writeInbound(verifyRequest(GATE_NODE, NOW));
        assertThat(((ClientTokenVerifyResponse) expired.readOutbound()).getError()).isEqualTo("token expired");

        EmbeddedChannel forged = connect();
        ByteString payload = payload(GATE_NODE, NOW + 600);
        forged.writeInbound(ClientTokenVerifyRequest.newBuilder()
                .setPayload(payload)
                .setSignature(GateTokens.ofUtf8("other-secret").sign(payload))
                .build());
        assertThat(((ClientTokenVerifyResponse) forged.readOutbound()).getError()).isEqualTo("invalid token signature");
        assertThat(forged.isOpen()).isFalse();
    }

    @Test
    void 未握手的业务包直接断开不回包() {
        EmbeddedChannel ch = connect();
        ch.writeInbound(request(1, LOGIN_MSG, "x"));
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(login.calls).isEmpty();
        assertThat(login.closed).as("未与 login 交互过的连接断开不通知 login").isEmpty();
    }

    // ================================================================ login 域

    @Test
    void login域请求带会话上下文转发_应答回显请求id且成功时不带error_message() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(7, LOGIN_MSG, "login-body"));

        assertThat(login.calls).hasSize(1);
        ClientCall call = login.calls.get(0);
        assertThat(call.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(call.getBody().toStringUtf8()).isEqualTo("login-body");
        assertThat(call.getRequestId()).isEqualTo(7);
        assertThat(call.getSession().getGateNodeId()).isEqualTo(GATE_NODE);
        assertThat(call.getSession().getGateInstanceId()).isEqualTo("gate-uuid");
        assertThat(call.getSession().getZoneId()).isEqualTo(ZONE);
        assertThat(call.getSession().getSessionId()).isEqualTo(sessionId());
        assertThat(call.getSession().getAccount()).isEmpty();
        assertThat(call.getSession().getPlayerId()).isZero();

        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        ch.runPendingTasks();

        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(7);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("login-resp");
        assertThat(reply.hasErrorMessage()).isFalse();
    }

    @Test
    void login应答带tip时放进error_message() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(8, LOGIN_MSG, "x"));
        login.complete(ClientReply.newBuilder().setTipId(2005).addTipParameters("p1").build());
        ch.runPendingTasks();

        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(8);
        assertThat(reply.getSerializedMessage()).isEmpty();
        assertThat(reply.getErrorMessage()).isEqualTo(TipInfoMessage.newBuilder().setId(2005).addParameters("p1").build());
    }

    @Test
    void 有应答的方法应答体为0字节也要回包() {
        // 回归：新账号的 LoginResponse 全默认值，序列化为 0 字节；曾被当成「无应答」吞掉，robot 等 48 超时。
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(9, LOGIN_MSG, "x"));
        login.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(9);
        assertThat(reply.getSerializedMessage()).isEmpty();
        assertThat(reply.hasErrorMessage()).isFalse();
    }

    @Test
    void 应答类型为Empty的方法且tip为0时不回包() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(9, LEAVE_MSG, "x"));
        login.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void 同一会话的调用严格串行_上一个完成才发下一个() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "first"));
        ch.writeInbound(request(2, ENTER_GAME_MSG, "second"));
        assertThat(login.calls).hasSize(1);

        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("r1")).build());
        ch.runPendingTasks();
        assertThat(login.calls).hasSize(2);
        assertThat(login.calls.get(1).getRequestId()).isEqualTo(2);
        assertThat(((MessageContent) ch.readOutbound()).getId()).isEqualTo(1);
    }

    @Test
    void 在途调用期间到达的scene消息排在它之后_按进场后的绑定转发() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        ch.writeInbound(request(2, SCENE_MSG, "list-skills"));
        assertThat(links.sent).isEmpty();

        login.complete(enterGameReply());
        ch.runPendingTasks();

        assertThat(links.sent).hasSize(2);
        assertThat(links.sent.get(0).frame().hasPlayerEnter()).isTrue();
        assertThat(links.sent.get(1).frame().getClientForward().getRequestId()).isEqualTo(2);
    }

    @Test
    void login调用失败推23服务不可用_并继续处理后续请求() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "x"));
        ch.writeInbound(request(2, LOGIN_MSG, "y"));
        login.fail(new RuntimeException("超时"));
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(login.calls).hasSize(2);
        assertThat(ch.isOpen()).isTrue();
    }

    // ================================================================ 会话指令

    @Test
    void BindAccount之后的调用带上账号() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("ok"))
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .build());
        ch.runPendingTasks();

        ch.writeInbound(request(2, ENTER_GAME_MSG, "y"));
        assertThat(login.calls.get(1).getSession().getAccount()).isEqualTo("robot_0001");
    }

    @Test
    void EnterScene发PlayerEnter并绑定场景_之后scene域消息转成ClientForward() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        login.complete(enterGameReply());
        ch.runPendingTasks();

        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(links.sent).hasSize(1);
        assertThat(links.last().sceneNodeId()).isEqualTo(SCENE_NODE);
        assertThat(links.last().frame().getPlayerEnter()).isEqualTo(PlayerEnter.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setSceneId(900).setOwnerEpoch(5).build());

        ch.writeInbound(request(3, SCENE_MSG, "skills"));
        assertThat(links.last().sceneNodeId()).isEqualTo(SCENE_NODE);
        assertThat(links.last().frame().getClientForward().getSessionId()).isEqualTo(sessionId());
        assertThat(links.last().frame().getClientForward().getPlayerId()).isEqualTo(PLAYER);
        assertThat(links.last().frame().getClientForward().getMessageId()).isEqualTo(SCENE_MSG);
        assertThat(links.last().frame().getClientForward().getBody().toStringUtf8()).isEqualTo("skills");
        assertThat(links.last().frame().getClientForward().getRequestId()).isEqualTo(3);

        ch.writeInbound(request(4, ENTER_GAME_MSG, "again"));
        assertThat(login.calls.get(1).getSession().getPlayerId()).isEqualTo(PLAYER);
    }

    @Test
    void 防御_login若对已在场景的会话再下发进场_先让旧场景放掉旧玩家() {
        // 真实 login 不会产生这个序列：会话已绑定玩家时 EnterGame 回 2028（见下一条用例）。这里只守住 gate 的防御：
        // 一个会话绝不同时挂两个场景实例。
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        login.complete(enterGameReply());
        ch.runPendingTasks();
        ch.writeInbound(request(2, ENTER_GAME_MSG, "x"));
        login.complete(enterGameReply());
        ch.runPendingTasks();

        List<NodeLinkFrame.BodyCase> kinds = links.sent.stream().map(s -> s.frame().getBodyCase()).toList();
        assertThat(kinds).containsExactly(NodeLinkFrame.BodyCase.PLAYER_ENTER, NodeLinkFrame.BodyCase.PLAYER_LEAVE,
                NodeLinkFrame.BodyCase.PLAYER_ENTER);
    }

    @Test
    void 在场景里再次EnterGame_调用带着已绑定的玩家_login拒绝时不发PlayerLeave也不发PlayerEnter() {
        EmbeddedChannel ch = enteredScene();
        ch.writeInbound(request(2, ENTER_GAME_MSG, "again"));
        assertThat(login.calls.get(1).getSession().getPlayerId()).as("login 据此回 2028").isEqualTo(PLAYER);

        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("enter-resp{2028}")).build());
        ch.runPendingTasks();

        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(links.sent).as("只有最初那次进场").hasSize(1);
        ch.writeInbound(request(3, SCENE_MSG, "skills"));
        assertThat(links.last().frame().getClientForward().getPlayerId()).as("仍在原场景").isEqualTo(PLAYER);
    }

    @Test
    void CloseSession先发tip再断开_后续指令不再执行() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setCloseSession(CloseSession.newBuilder().setTipId(2017)))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();

        // 先回这次请求自己的应答（有应答的方法必回包），再推 tip、断开
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(tipOf(ch.readOutbound())).isEqualTo(2017);
        assertThat(ch.isOpen()).isFalse();
        assertThat(links.sent).isEmpty();
    }

    @Test
    void UnbindPlayer发主动PlayerLeave并解绑玩家与场景_账号保留() {
        EmbeddedChannel ch = verified();
        int sid = sessionId();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);

        ch.writeInbound(request(2, LEAVE_MSG, ""));
        login.complete(unbindReply());
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).as("LeaveGame 应答类型是 Empty，不回包").isNull();
        assertThat(links.last().sceneNodeId()).isEqualTo(SCENE_NODE);
        assertThat(links.last().frame().getPlayerLeave())
                .isEqualTo(PlayerLeave.newBuilder().setSessionId(sid).setPlayerId(PLAYER).setVoluntary(true).build());

        // 不在场景里了：scene 域消息推 1003，不再转发。
        int framesBefore = links.sent.size();
        ch.writeInbound(request(3, SCENE_MSG, "skills"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(links.sent).hasSize(framesBefore);

        // 旧链路代次上迟到的下行不再发给这个会话。
        router.onToClient(SCENE_NODE, links.generation, ToClient.newBuilder().addSessionIds(sid)
                .setMessageContent(MessageContent.newBuilder().setMessageId(79).build().toByteString()).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();

        // 账号还在、玩家已清：可以回选角后再进游戏。
        ch.writeInbound(request(4, ENTER_GAME_MSG, "again"));
        assertThat(login.calls.get(2).getSession().getAccount()).isEqualTo("robot_0001");
        assertThat(login.calls.get(2).getSession().getPlayerId()).isZero();
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 进场失败后LeaveGame_会话已不绑定玩家_不发PlayerLeave_断线也不再发() {
        // 进场失败时 gate 已把玩家绑定清零（回到未进游戏），所以随后的 LeaveGame 带的 player_id 是 0；
        // 即便后端仍回 UnbindPlayer，也没有场景要离开。
        EmbeddedChannel ch = enteredScene();
        router.onEnterUndeliverable(SCENE_NODE, links.generation, links.sent.get(0).frame().getPlayerEnter());
        ch.runPendingTasks();
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_ENTER_SCENE_FAILED);

        ch.writeInbound(request(2, LEAVE_MSG, ""));
        assertThat(login.calls.get(1).getSession().getPlayerId()).isZero();
        login.complete(unbindReply());
        ch.runPendingTasks();
        ch.close();

        assertThat(links.sent.stream().noneMatch(s -> s.frame().hasPlayerLeave())).isTrue();
        assertThat(login.closed).hasSize(1);
        assertThat(login.closed.get(0).getSession().getPlayerId()).isZero();
    }

    @Test
    void 断线时LeaveGame在途_按主动离开发一次PlayerLeave_迟到的UnbindPlayer只清身份() {
        EmbeddedChannel ch = enteredScene();
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        ch.close();

        login.complete(unbindReply());
        ch.runPendingTasks();

        List<PlayerLeave> leaves = links.sent.stream().filter(s -> s.frame().hasPlayerLeave())
                .map(s -> s.frame().getPlayerLeave()).toList();
        assertThat(leaves).as("客户端发完 17 即关连接：按干净登出处理").extracting(PlayerLeave::getVoluntary)
                .containsExactly(true);
        assertThat(login.closed).hasSize(1);
        assertThat(login.closed.get(0).getSession().getPlayerId()).isZero();
        assertThat(registry.size()).isZero();
    }

    @Test
    void LeaveGame调用失败之后断线_按被动离开() {
        EmbeddedChannel ch = enteredScene();
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        login.fail(new IllegalStateException("超时"));
        ch.runPendingTasks();
        ch.close();

        List<PlayerLeave> leaves = links.sent.stream().filter(s -> s.frame().hasPlayerLeave())
                .map(s -> s.frame().getPlayerLeave()).toList();
        assertThat(leaves).as("LeaveGame 没成功，玩家还在游戏里：断线留重连租约").extracting(PlayerLeave::getVoluntary)
                .containsExactly(false);
    }

    // ================================================================ 其他路由与入口校验

    @Test
    void 未进场景的scene域消息推23服务不可用() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(5, SCENE_MSG, "x"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(links.sent).isEmpty();
    }

    @Test
    void 未接入Java版的域推23服务不可用() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(5, UNSUPPORTED_MSG, "x"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(login.calls).isEmpty();
        assertThat(ch.isOpen()).isTrue();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, UNSUPPORTED_MSG, "unsupported")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, UNSUPPORTED_MSG, "battle_rejected"))
                .as("普通的未接入域不走直连闸").isZero();
    }

    @Test
    void 未接入Java版的域_login在途时排队_完成后才回tip() {
        // 对照组：普通的未接入域仍走待处理队列（同一会话严格按到达顺序）；只有战斗号（directOnly）当场回，见下一节
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "slow"));
        ch.writeInbound(request(2, UNSUPPORTED_MSG, "x"));
        assertThat((Object) ch.readOutbound()).as("login 在途，排在它后面").isNull();
        assertThat(session().pending).hasSize(1);

        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("r1")).build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getId()).isEqualTo(1);
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
    }

    // ================================================================ 战斗上行当场拒绝（scene-battle-spec §2.5、§7.19、§13.6，D12）

    @Test
    void 战斗上行_当场推23的1003_不计非法包不断连不转发_计battle_rejected() {
        EmbeddedChannel ch = enteredScene();
        int framesBefore = links.sent.size();
        // 非法包阈值是 3：发 5 条，只要有一条计了非法包连接就会断
        for (int i = 0; i < 4; i++) {
            ch.writeInbound(request(10 + i, BATTLE_MSG, "action"));
            MessageContent tip = ch.readOutbound();
            assertThat(tip.getId()).as("推送形状：不带请求 id").isZero();
            assertThat(tipOf(tip)).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).isEqualTo(1003);
            assertThat((Object) ch.readOutbound()).as("只有一帧").isNull();
        }
        // Notify 号（应答类型 Empty）发上来同样回 tip：不因为「无应答」被吞掉
        ch.writeInbound(request(20, BATTLE_NOTIFY_MSG, "notify"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(1003);

        assertThat(session().illegalPackets).as("合法协议号，不计非法包").isZero();
        assertThat(ch.isOpen()).as("不断连").isTrue();
        assertThat(session().pending).as("不进待处理队列").isEmpty();
        assertThat(session().backendQueues).as("不进任何后端队列").isEmpty();
        assertThat(links.sent).as("已在场景里也不转给 scene").hasSize(framesBefore);
        assertThat(login.calls).as("只有进场那一次").hasSize(1);
        assertThat(friend.calls).isEmpty();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(4);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_NOTIFY_MSG, "battle_rejected")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "unsupported"))
                .as("不再走缺省分支（6.2 的口径是 unsupported）").isZero();
        assertThat(disconnects("illegal_packets")).isZero();

        ch.writeInbound(request(30, SCENE_MSG, "skills"));
        assertThat(links.sent).as("同一连接上的正常请求照常转发").hasSize(framesBefore + 1);
    }

    @Test
    void 战斗上行_login在途时当场回_不进待处理队列_不等login完成() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "slow"));
        ch.writeInbound(request(2, SCENE_MSG, "queued"));
        assertThat(session().inFlight).isTrue();
        assertThat(session().pending).as("对照：普通请求排在在途的 login 调用之后").hasSize(1);
        assertThat((Object) ch.readOutbound()).isNull();

        ch.writeInbound(request(3, BATTLE_MSG, "action"));

        assertThat(tipOf(ch.readOutbound())).as("login 还没回来，战斗 tip 已经发出").isEqualTo(1003);
        assertThat(session().pending).as("队列里仍只有那条 scene 请求").hasSize(1)
                .allSatisfy(p -> assertThat(p.request().getMessageId()).isEqualTo(SCENE_MSG));
        assertThat(session().inFlight).isTrue();
        assertThat(login.calls).hasSize(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(1);

        // login 完成：先回它自己的应答，再处理排队的 scene 请求（没进场景 → 23 {1003}）；战斗 tip 不会再发第二次
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("r1")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(1);
        assertThat(tipOf(ch.readOutbound())).isEqualTo(1003);
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(requests("scene", SCENE_MSG, "not_in_scene")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(1);
    }

    @Test
    void 战斗上行_待处理队列已满时照常当场回_不断连_队列不变() {
        EmbeddedChannel ch = verified();
        for (int i = 1; i <= 5; i++) {
            ch.writeInbound(request(i, LOGIN_MSG, "x"));
        }
        // 第 1 个在途，第 2~5 个排队：队列到上限 4
        assertThat(session().pending).hasSize(4);

        for (int i = 0; i < 10; i++) {
            ch.writeInbound(request(100 + i, BATTLE_MSG, "action"));
            assertThat(tipOf(ch.readOutbound())).isEqualTo(1003);
        }

        assertThat(ch.isOpen()).as("战斗上行不占队列，队列满了也不因它断连").isTrue();
        assertThat(session().pending).hasSize(4)
                .allSatisfy(p -> assertThat(p.request().getMessageId()).isEqualTo(LOGIN_MSG));
        assertThat(session().illegalPackets).isZero();
        assertThat(disconnects("pending_overflow")).isZero();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "overflow")).isZero();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(10);

        // 对照：队列确实是满的——再来一条普通请求就溢出断开
        ch.writeInbound(request(6, LOGIN_MSG, "x"));
        assertThat(ch.isOpen()).isFalse();
        assertThat(disconnects("pending_overflow")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "overflow")).isEqualTo(1);
    }

    @Test
    void 战斗上行_热关停命中时仍推23的1003_不是信封1003_不计killed_不记短路() {
        KillSwitch killSwitch = new KillSwitch(-1, System::nanoTime);
        List<String> blocked = new ArrayList<>();
        killSwitch.onBlocked(blocked::add);
        KillSwitch.installGlobal(killSwitch);
        try {
            EmbeddedChannel ch = verified();
            // 全局规则、精确规则（测试路由的热关停键是消息号本身）各来一遍
            for (Map<String, KillSwitch.Rule> rules : List.of(
                    Map.of("*", new KillSwitch.Rule(true, "止血", 0)),
                    Map.of(Integer.toString(BATTLE_MSG), new KillSwitch.Rule(true, "止血", 0)))) {
                killSwitch.setRules(rules);
                assertThat(killSwitch.blocked("/" + BATTLE_MSG)).as("规则确实命中这个战斗方法").isPresent();

                ch.writeInbound(request(7, BATTLE_MSG, "action"));

                MessageContent tip = ch.readOutbound();
                assertThat(tip.getMessageId()).as("23 推送，不是以请求号为 message_id 的信封").isEqualTo(TIP_MSG);
                assertThat(tip.getId()).isZero();
                assertThat(tip.hasErrorMessage()).as("不是信封错误").isFalse();
                assertThat(tipOf(tip)).isEqualTo(1003);
                assertThat((Object) ch.readOutbound()).isNull();
            }
            assertThat(blocked).as("没有记成热关停短路").isEmpty();
            assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "killed")).isZero();
            assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(2);

            // 对照：同一份全局规则下普通请求回的是带请求 id 的信封 1003，并记一次短路
            killSwitch.setRules(Map.of("*", new KillSwitch.Rule(true, "止血", 0)));
            ch.writeInbound(request(8, FRIEND_MSG, "f"));
            MessageContent envelope = ch.readOutbound();
            assertThat(envelope.getMessageId()).isEqualTo(FRIEND_MSG);
            assertThat(envelope.getId()).isEqualTo(8);
            assertThat(envelope.getErrorMessage().getId()).isEqualTo(1003);
            assertThat(blocked).containsExactly(Integer.toString(FRIEND_MSG));
            assertThat(ch.isOpen()).isTrue();
        } finally {
            KillSwitch.installGlobal(null);
        }
    }

    @Test
    void 战斗上行_所在域即使配了后端也当场拒_不转给后端() {
        // §7.19：以后别的服务接入 gate（6.4 的 MatchService）不影响这道闸；就算有人把战斗服务错接到某个后端，
        // 直连闸排在后端分派之前，请求到不了后端（大厅连接不能成为绕过直连票据的第二条战斗通路）
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, BATTLE_VIA_BACKEND_MSG, "action"));

        assertThat(tipOf(ch.readOutbound())).isEqualTo(1003);
        assertThat(friend.calls).as("不转给 friend 后端").isEmpty();
        assertThat(session().backendQueues).isEmpty();
        assertThat(requests("friend", BATTLE_VIA_BACKEND_MSG, "battle_rejected")).isEqualTo(1);
        assertThat(requests("friend", BATTLE_VIA_BACKEND_MSG, "forwarded")).isZero();

        ch.writeInbound(request(2, FRIEND_MSG, "f"));
        assertThat(friend.calls).as("对照：同一个域的普通号照常转发").hasSize(1);
    }

    @Test
    void 战斗上行_闸序_体积与GM闸在前() {
        // 基线闸序（client_message_processor.cpp）：白名单 → 体积与限频 → GM 闸 → 战斗拒绝。限频见 BattleUplinkRejectedTest
        EmbeddedChannel ch = verified();
        ch.writeInbound(ClientRequest.newBuilder().setId(6).setMessageId(BATTLE_MSG)
                .setBody(ByteString.copyFrom(new byte[ClientDispatcher.MAX_REQUEST_BYTES])).build());
        MessageContent oversized = ch.readOutbound();
        assertThat(oversized.getMessageId()).isEqualTo(BATTLE_MSG);
        assertThat(oversized.getId()).isEqualTo(6);
        assertThat(oversized.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_MESSAGE_SIZE_EXCEEDED).isEqualTo(1010);
        assertThat(session().illegalPackets).as("超长照样计非法包").isEqualTo(1);

        // 生产模式下 GM 名字的号先被 GM 闸拦：1006 + 非法包，轮不到直连闸
        ch.writeInbound(request(7, BATTLE_GM_MSG, "gm"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(1006);
        assertThat(session().illegalPackets).isEqualTo(2);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "oversized")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_GM_MSG, "gm_rejected")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isZero();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_GM_MSG, "battle_rejected")).isZero();

        // dev 模式放行 GM 闸之后，落到直连闸：1003，不计非法包
        ClientDispatcher dev = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES, TIP_MSG, login, links, registry,
                new GateLimits(4, 3, Duration.ZERO, MessageLimits.UNLIMITED, true), metrics, presence);
        EmbeddedChannel devCh = new EmbeddedChannel(new ClientChannelHandler(registry, dev));
        devCh.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        devCh.readOutbound();
        devCh.writeInbound(request(1, BATTLE_GM_MSG, "gm"));
        assertThat(tipOf(devCh.readOutbound())).isEqualTo(1003);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_GM_MSG, "battle_rejected")).isEqualTo(1);
        assertThat(devCh.isOpen()).isTrue();
    }

    @Test
    void 战斗上行_未握手直接断开不回包_会话关闭中不回包计dropped() {
        // 基线：令牌校验排在最前（:858-863），没握手的连接发战斗号同样断开
        EmbeddedChannel raw = connect();
        raw.writeInbound(request(1, BATTLE_MSG, "action"));
        assertThat((Object) raw.readOutbound()).isNull();
        assertThat(raw.isOpen()).isFalse();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isZero();

        EmbeddedChannel ch = verified();
        ClientSession closing = registry.all().stream().filter(s -> s.verified).findFirst().orElseThrow();
        closing.closing = true;
        dispatcher.onRequest(closing, request(2, BATTLE_MSG, "action"));
        assertThat((Object) ch.readOutbound()).as("已决定关闭的会话不再回任何东西").isNull();
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "dropped")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isZero();
    }

    @Test
    void 好友域消息转给friend后端_带会话_应答原样回客户端_不碰login() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(9, FRIEND_MSG, "add"));
        assertThat(login.calls).isEmpty();
        assertThat(friend.calls).hasSize(1);
        ClientCall call = friend.calls.get(0);
        assertThat(call.getMessageId()).isEqualTo(FRIEND_MSG);
        assertThat(call.getRequestId()).isEqualTo(9);
        assertThat(call.getBody().toStringUtf8()).isEqualTo("add");
        assertThat(call.getSession().getSessionId()).isNotZero();

        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("friend-resp")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(FRIEND_MSG);
        assertThat(reply.getId()).isEqualTo(9);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("friend-resp");
        assertThat(login.closed).as("只和 friend 打过交道的连接断开不通知 login").isEmpty();
    }

    @Test
    void 好友后端调用失败回带请求id的信封1003_同一会话串行() {
        // 同基线路由服：上游错误 / 超时翻成带请求 id 的信封 1003（不是推 23 tip 让客户端等到自己超时）
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, FRIEND_MSG, "a"));
        ch.writeInbound(request(2, FRIEND_MSG, "b"));
        assertThat(friend.calls).as("上一个没完成不发下一个").hasSize(1);
        friend.fail(new IllegalStateException("no provider"));
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(FRIEND_MSG);
        assertThat(reply.getId()).isEqualTo(1);
        assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(reply.getSerializedMessage()).as("没有业务回包").isEmpty();
        assertThat(friend.calls).hasSize(2);
    }

    @Test
    void 好友调用在途时断线_不等它_迟到的应答丢掉() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, FRIEND_MSG, "a"));
        ch.writeInbound(request(2, FRIEND_MSG, "b"));
        ch.close();
        assertThat(registry.size()).as("断线流程只等 login，不等好友调用").isZero();
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("late")).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(friend.calls).as("排队的那个随断线丢掉").hasSize(1);
        assertThat(requests("friend", FRIEND_MSG, "dropped")).isEqualTo(1);
    }

    @Test
    void 好友调用在途不阻塞login与scene() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, FRIEND_MSG, "slow"));
        ch.writeInbound(request(2, LOGIN_MSG, "x"));
        assertThat(login.calls).as("好友调用没回来，login 照常发").hasSize(1);
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(LOGIN_MSG);
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("friend-resp")).build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(FRIEND_MSG);
    }

    @Test
    void 排队的好友请求带入队时的身份_期间离开游戏不算到别的角色头上() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();
        ch.readOutbound();

        ch.writeInbound(request(2, FRIEND_MSG, "slow"));
        ch.writeInbound(request(3, FRIEND_MSG, "queued"));
        ch.writeInbound(request(4, LEAVE_MSG, ""));
        login.complete(unbindReply()); // 好友调用还在途，login 先把玩家解绑
        ch.runPendingTasks();

        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("r1")).build());
        ch.runPendingTasks();
        assertThat(friend.calls).hasSize(2);
        assertThat(friend.calls.get(1).getSession().getPlayerId()).as("按入队时的身份转发").isEqualTo(PLAYER);
    }

    @Test
    void 帮会推送占位220上行_后端回tip时带请求id回信封_tip为0不回包() {
        // guild-spec §7.3 / §11.5：220 的应答类型是 Empty，后端对客户端上行回 tip_id = 1003；
        // gate 照样回信封（带请求 id、没有业务体），客户端按请求 id 对上，不会卡到超时
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(7, GUILD_NOTIFY_MSG, "uplink"));
        assertThat(guild.calls).hasSize(1);
        assertThat(friend.calls).as("guild 域只发给 guild 后端").isEmpty();
        assertThat(guild.calls.get(0).getMessageId()).isEqualTo(GUILD_NOTIFY_MSG);
        guild.complete(ClientReply.newBuilder().setTipId(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(GUILD_NOTIFY_MSG);
        assertThat(reply.getId()).isEqualTo(7);
        assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(reply.getSerializedMessage()).as("没有业务回包").isEmpty();

        ch.writeInbound(request(8, GUILD_NOTIFY_MSG, "uplink"));
        guild.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).as("Empty 应答且 tip 为 0 不回包").isNull();
    }

    @Test
    void 帮会调用在途不阻塞好友与login() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, GUILD_NOTIFY_MSG, "slow"));
        ch.writeInbound(request(2, FRIEND_MSG, "f"));
        ch.writeInbound(request(3, LOGIN_MSG, "l"));
        assertThat(friend.calls).as("guild 调用没回来，friend 照常发").hasSize(1);
        assertThat(login.calls).as("guild 调用没回来，login 照常发").hasSize(1);
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("friend-resp")).build());
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        ch.runPendingTasks();
        assertThat(List.of(((MessageContent) ch.readOutbound()).getMessageId(), ((MessageContent) ch.readOutbound()).getMessageId()))
                .containsExactlyInAnyOrder(FRIEND_MSG, LOGIN_MSG);
        guild.complete(ClientReply.newBuilder().setTipId(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(GUILD_NOTIFY_MSG);
    }

    // ================================================================ 聚宝斋（trade-spec §5.2、§9.4）

    @Test
    void 聚宝斋消息转给trade后端_同一会话串行_应答原样回_后端失败回带请求id的信封1003() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(3, TRADE_MSG, "browse-1"));
        ch.writeInbound(request(4, TRADE_MSG, "browse-2"));
        assertThat(trade.calls).as("上一个没完成不发下一个").hasSize(1);
        assertThat(login.calls).isEmpty();
        assertThat(friend.calls).isEmpty();
        assertThat(guild.calls).isEmpty();
        ClientCall call = trade.calls.get(0);
        assertThat(call.getMessageId()).isEqualTo(TRADE_MSG);
        assertThat(call.getRequestId()).isEqualTo(3);
        assertThat(call.getBody().toStringUtf8()).isEqualTo("browse-1");
        assertThat(call.getSession().getSessionId()).isNotZero();

        trade.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("trade-resp")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(TRADE_MSG);
        assertThat(reply.getId()).isEqualTo(3);
        assertThat(reply.hasErrorMessage()).as("业务拒绝写在应答体里，信封不带错误").isFalse();
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("trade-resp");

        // T8：xm-trade 不在 / 超时 → 带请求 id 的信封 1003（客户端当场按信封错误处理，不等 15 s 超时、不隔离到重连）
        assertThat(trade.calls).hasSize(2);
        trade.fail(new IllegalStateException("No provider available for trade"));
        ch.runPendingTasks();
        MessageContent failed = ch.readOutbound();
        assertThat(failed.getMessageId()).isEqualTo(TRADE_MSG);
        assertThat(failed.getId()).isEqualTo(4);
        assertThat(failed.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(failed.getSerializedMessage()).as("没有业务回包").isEmpty();
        assertThat((Object) ch.readOutbound()).as("不另推 23").isNull();
        assertThat(ch.isOpen()).isTrue();
        ch.close();
        assertThat(login.closed).as("只和 trade 打过交道的连接断开不通知 login").isEmpty();
    }

    @Test
    void 聚宝斋调用在途不阻塞login_scene_好友与帮会() {
        EmbeddedChannel ch = enteredScene();
        int framesBefore = links.sent.size();
        ch.writeInbound(request(2, TRADE_MSG, "slow"));
        ch.writeInbound(request(3, SCENE_MSG, "s"));
        ch.writeInbound(request(4, FRIEND_MSG, "f"));
        ch.writeInbound(request(5, GUILD_NOTIFY_MSG, "g"));
        ch.writeInbound(request(6, LOGIN_MSG, "l"));
        assertThat(links.sent).as("trade 调用没回来，scene 照常转发").hasSize(framesBefore + 1);
        assertThat(friend.calls).as("trade 调用没回来，friend 照常发").hasSize(1);
        assertThat(guild.calls).as("trade 调用没回来，guild 照常发").hasSize(1);
        assertThat(login.calls).as("trade 调用没回来，login 照常发（进场那次 + 这次）").hasSize(2);
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("friend-resp")).build());
        guild.complete(ClientReply.newBuilder().setTipId(ClientDispatcher.TIP_SERVICE_UNAVAILABLE).build());
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("login-resp")).build());
        ch.runPendingTasks();
        assertThat(List.of(((MessageContent) ch.readOutbound()).getMessageId(), ((MessageContent) ch.readOutbound()).getMessageId(),
                ((MessageContent) ch.readOutbound()).getMessageId()))
                .containsExactlyInAnyOrder(FRIEND_MSG, GUILD_NOTIFY_MSG, LOGIN_MSG);
        assertThat(trade.calls).hasSize(1);
        trade.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("trade-resp")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(TRADE_MSG);
        assertThat(reply.getId()).isEqualTo(2);
    }

    @Test
    void 聚宝斋热关停_服务通配规则回带请求id的信封1003_不转发_别的域不受影响() {
        KillSwitch killSwitch = new KillSwitch(-1, System::nanoTime);
        KillSwitch.installGlobal(killSwitch);
        try {
            EmbeddedChannel ch = verified();
            // 规则键与基线 trade.yaml:39-44 同名（T9）：服务通配 trade.ClientPlayerJubaozhai/*
            killSwitch.setRules(Map.of("trade.ClientPlayerJubaozhai/*", new KillSwitch.Rule(true, "止血", 0)));
            ch.writeInbound(request(1, TRADE_MSG, "a"));
            MessageContent reply = ch.readOutbound();
            assertThat(reply.getMessageId()).isEqualTo(TRADE_MSG);
            assertThat(reply.getId()).isEqualTo(1);
            assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            assertThat(trade.calls).isEmpty();

            ch.writeInbound(request(2, FRIEND_MSG, "f"));
            assertThat(friend.calls).as("只关聚宝斋").hasSize(1);

            killSwitch.setRules(Map.of());
            ch.writeInbound(request(3, TRADE_MSG, "b"));
            assertThat(trade.calls).hasSize(1);
            assertThat(ch.isOpen()).as("被关停不算非法包").isTrue();
        } finally {
            KillSwitch.installGlobal(null);
        }
    }

    @Test
    void 真实路由_客户端发199TradeAdminSeedListing_计非法包不回包不转发_同一连接照常可用() {
        // trade-spec §0.8 / §4.9 第 2 步：199 是内部服务的号，gate 按「不认识的号」丢弃（同基线 C++ gate 白名单），客户端只能等到超时
        MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
        int seed = ids.requireId("TradeAdmin", "SeedListing");
        int browse = ids.requireId("ClientPlayerJubaozhai", "BrowseListings");
        ClientDispatcher real = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), MessageRoutes.of(ids), TIP_MSG, login,
                Map.of(DubboGroups.TRADE, trade), links, registry, new GateLimits(4, 3, Duration.ZERO), metrics, presence);
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, real));
        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();

        ch.writeInbound(request(1, seed, "seed"));
        assertThat((Object) ch.readOutbound()).as("不回包（不回信封、不推 23）").isNull();
        assertThat(trade.calls).as("不转发给 xm-trade").isEmpty();
        assertThat(session().illegalPackets).as("计一次非法包").isEqualTo(1);
        assertThat(ch.isOpen()).isTrue();

        ch.writeInbound(request(2, browse, "browse"));
        assertThat(trade.calls).singleElement().satisfies(c -> {
            assertThat(c.getMessageId()).isEqualTo(browse);
            assertThat(c.getRequestId()).isEqualTo(2);
        });
        trade.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("page")).build());
        ch.runPendingTasks();
        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(browse);
        assertThat(reply.getId()).isEqualTo(2);
        assertThat(reply.getSerializedMessage().toStringUtf8()).isEqualTo("page");
    }

    @Test
    void 热关停_命中规则回信封1003不转发_精确规则可豁免_规则清空后恢复() {
        KillSwitch killSwitch = new KillSwitch(-1, System::nanoTime);
        KillSwitch.installGlobal(killSwitch);
        try {
            EmbeddedChannel ch = verified();
            // 测试路由的方法名是消息号本身（全路径 /234），规则键用它；生产路由是 /friendpb.ClientPlayerFriend/AddFriend
            killSwitch.setRules(Map.of("*", new KillSwitch.Rule(true, "止血", 0)));
            ch.writeInbound(request(1, FRIEND_MSG, "a"));
            MessageContent reply = ch.readOutbound();
            assertThat(reply.getMessageId()).isEqualTo(FRIEND_MSG);
            assertThat(reply.getId()).isEqualTo(1);
            assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
            assertThat(friend.calls).isEmpty();
            assertThat(requests("friend", FRIEND_MSG, "killed")).isEqualTo(1);

            // 精确规则 deny=false 把自己从服务通配里豁免出来
            killSwitch.setRules(Map.of("*", new KillSwitch.Rule(true, "", 0),
                    Integer.toString(FRIEND_MSG), new KillSwitch.Rule(false, "", 0)));
            ch.writeInbound(request(2, FRIEND_MSG, "b"));
            assertThat(friend.calls).hasSize(1);
            friend.complete(ClientReply.getDefaultInstance());
            ch.runPendingTasks();
            ch.readOutbound();

            killSwitch.setRules(Map.of());
            ch.writeInbound(request(3, FRIEND_MSG, "c"));
            assertThat(friend.calls).hasSize(2);
            assertThat(ch.isOpen()).as("被关停不算非法包").isTrue();
        } finally {
            KillSwitch.installGlobal(null);
        }
    }

    @Test
    void 未知消息号不回包_累计到阈值断开() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, 99_999, "x"));
        ch.writeInbound(request(2, 99_999, "x"));
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isTrue();

        ch.writeInbound(request(3, 99_999, "x"));
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 超过1KB的请求回1010信封错误且不转发() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(ClientRequest.newBuilder().setId(6).setMessageId(LOGIN_MSG)
                .setBody(ByteString.copyFrom(new byte[ClientDispatcher.MAX_REQUEST_BYTES])).build());

        MessageContent reply = ch.readOutbound();
        assertThat(reply.getMessageId()).isEqualTo(LOGIN_MSG);
        assertThat(reply.getId()).isEqualTo(6);
        assertThat(reply.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_MESSAGE_SIZE_EXCEEDED);
        assertThat(login.calls).isEmpty();
    }

    @Test
    void 待处理请求超限断开() {
        EmbeddedChannel ch = verified();
        for (int i = 1; i <= 6; i++) {
            ch.writeInbound(request(i, LOGIN_MSG, "x"));
        }
        // 第 1 个在途，第 2~5 个排队（上限 4），第 6 个超限。
        assertThat(ch.isOpen()).isFalse();
    }

    // ================================================================ 断线

    @Test
    void 断线时发PlayerLeave_通知login并释放会话号() {
        EmbeddedChannel ch = verified();
        int sid = sessionId();
        ch.writeInbound(request(1, LOGIN_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();

        ch.close();

        assertThat(links.last().sceneNodeId()).isEqualTo(SCENE_NODE);
        assertThat(links.last().frame().getPlayerLeave())
                .isEqualTo(PlayerLeave.newBuilder().setSessionId(sid).setPlayerId(PLAYER).setVoluntary(false).build());
        assertThat(login.closed).hasSize(1);
        SessionClosed closed = login.closed.get(0);
        assertThat(closed.getVoluntary()).isFalse();
        assertThat(closed.getSession().getSessionId()).isEqualTo(sid);
        assertThat(closed.getSession().getAccount()).isEqualTo("robot_0001");
        assertThat(closed.getSession().getPlayerId()).isEqualTo(PLAYER);
        assertThat(registry.size()).isZero();
    }

    @Test
    void 断线时login调用在途_等调用完成后才通知login且带上迟到的身份() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        ch.close();
        assertThat(login.closed).isEmpty();
        assertThat(registry.size()).isEqualTo(1);

        login.complete(enterGameReply());
        ch.runPendingTasks();

        assertThat(links.sent).as("会话已断，不再送进场景").isEmpty();
        assertThat(login.abandoned).as("夺得的归属没有写者：请 login 释放，不必等租约过期")
                .extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));
        assertThat(login.closed).hasSize(1);
        assertThat(login.closed.get(0).getSession().getPlayerId()).isEqualTo(PLAYER);
        assertThat(registry.size()).isZero();
    }

    // ================================================================ scene 链路事件

    @Test
    void ToClient只下发给绑定在该链路代次上的会话() {
        EmbeddedChannel ch = enteredScene();
        MessageContent notify = MessageContent.newBuilder().setMessageId(79).setSerializedMessage(ByteString.copyFromUtf8("s2c")).build();

        router.onToClient(SCENE_NODE, links.generation, ToClient.newBuilder()
                .addSessionIds(sessionId()).addSessionIds(123_456)
                .setMessageContent(notify.toByteString()).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isEqualTo(notify);

        router.onToClient(SCENE_NODE, links.generation + 1, ToClient.newBuilder()
                .addSessionIds(sessionId()).setMessageContent(notify.toByteString()).build());
        router.onToClient(SCENE_NODE + 1, links.generation, ToClient.newBuilder()
                .addSessionIds(sessionId()).setMessageContent(notify.toByteString()).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void ToClient内容不是合法MessageContent时整条丢弃() {
        EmbeddedChannel ch = enteredScene();
        router.onToClient(SCENE_NODE, links.generation, ToClient.newBuilder()
                .addSessionIds(sessionId())
                .setMessageContent(ByteString.copyFrom(new byte[] {0x0A, (byte) 0xFF}))
                .build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 进场被scene拒绝时解绑场景并推tip() {
        EmbeddedChannel ch = enteredScene();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setTipId(3007).setOwnerEpoch(5).build());
        ch.runPendingTasks();
        assertThat(tipOf(ch.readOutbound())).isEqualTo(3007);

        int framesBefore = links.sent.size();
        ch.writeInbound(request(9, SCENE_MSG, "x"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(links.sent).hasSize(framesBefore);
        assertThat(login.abandoned).as("scene 见过这次进场，由 scene 释放归属").isEmpty();
    }

    @Test
    void 进场成功的结果不改变绑定() {
        EmbeddedChannel ch = enteredScene();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.writeInbound(request(9, SCENE_MSG, "x"));
        assertThat(links.last().frame().hasClientForward()).isTrue();
    }

    // ================================================================ 在线目录与服务端推送

    /** 已进场且 scene 已确认（在线目录里有它）的会话。 */
    private EmbeddedChannel confirmedInGame() {
        EmbeddedChannel ch = enteredScene();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).build());
        ch.runPendingTasks();
        return ch;
    }

    private ClientSession session() {
        return registry.get(sessionId());
    }

    private static MessageContent pushMessage() {
        return MessageContent.newBuilder().setMessageId(235).setSerializedMessage(ByteString.copyFromUtf8("evt")).build();
    }

    @Test
    void scene确认进场才登记在线_离开游戏时撤销() {
        EmbeddedChannel ch = enteredScene();
        assertThat(presence.events).as("进场帧发出但 scene 还没确认").isEmpty();

        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).build());
        ch.runPendingTasks();
        assertThat(presence.events).containsExactly(new RecordingPresence.Event(true, PLAYER, sessionId(), 5));

        ch.writeInbound(request(2, LEAVE_MSG, "leave"));
        login.complete(unbindReply());
        ch.runPendingTasks();
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
    }

    @Test
    void 进场被拒不登记在线() {
        EmbeddedChannel ch = enteredScene();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(3023).build());
        ch.runPendingTasks();
        assertThat(presence.events).isEmpty();
    }

    @Test
    void 断线_被踢_链路断开都撤销在线() {
        EmbeddedChannel a = confirmedInGame();
        a.close();
        a.runPendingTasks();
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
    }

    @Test
    void 推送只发给在游戏里的目标玩家_玩家栅栏() {
        EmbeddedChannel ch = confirmedInGame();
        assertThat(dispatcher.deliverPush(session(), PLAYER + 1, pushMessage()))
                .as("会话上在游戏里的不是这个玩家").isEqualTo(GateMetrics.PushResult.NOT_BOUND);
        assertThat((Object) ch.readOutbound()).isNull();

        assertThat(dispatcher.deliverPush(session(), PLAYER, pushMessage())).isEqualTo(GateMetrics.PushResult.DELIVERED);
        MessageContent pushed = ch.readOutbound();
        assertThat(pushed).isEqualTo(pushMessage());
    }

    @Test
    void 未确认进场的会话不收推送() {
        enteredScene();
        assertThat(dispatcher.deliverPush(session(), PLAYER, pushMessage())).isEqualTo(GateMetrics.PushResult.NOT_BOUND);
    }

    @Test
    void 服务端踢下线_推tip后关闭_随后照常离场() {
        EmbeddedChannel ch = confirmedInGame();
        int framesBefore = links.sent.size();
        ClientSession kicked = session();

        assertThat(dispatcher.kickByServer(kicked, PLAYER, 2017)).isEqualTo(GateMetrics.PushResult.DELIVERED);
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(2017);
        assertThat(ch.isOpen()).isFalse();
        assertThat(disconnects("server_kick")).isEqualTo(1);
        assertThat(links.sent.subList(framesBefore, links.sent.size()))
                .as("断线流程让 scene 放掉玩家").anySatisfy(f -> assertThat(f.frame().hasPlayerLeave()).isTrue());
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
        assertThat(dispatcher.kickByServer(kicked, PLAYER, 2017)).as("已关闭的会话不再处理").isEqualTo(GateMetrics.PushResult.NOT_BOUND);
    }

    @Test
    void 建链失败时进场失败推3023并解绑() {
        EmbeddedChannel ch = enteredScene();
        PlayerEnter enter = links.sent.get(0).frame().getPlayerEnter();
        router.onEnterUndeliverable(SCENE_NODE, links.generation, enter);
        ch.runPendingTasks();
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_ENTER_SCENE_FAILED);
        assertThat(ClientDispatcher.TIP_ENTER_SCENE_FAILED).isEqualTo(3023);

        assertThat(login.abandoned).as("进场帧从未写上链路：请 login 释放归属")
                .extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));

        ch.close();
        assertThat(links.sent.stream().noneMatch(s -> s.frame().hasPlayerLeave())).as("已解绑，断线不再发 PlayerLeave").isTrue();
    }

    @Test
    void scene链路断开时关闭其上的会话_不再发PlayerLeave() {
        EmbeddedChannel ch = enteredScene();
        EmbeddedChannel other = verified2();

        router.onLinkDown(SCENE_NODE, links.generation);
        ch.runPendingTasks();
        other.runPendingTasks();

        assertThat(ch.isOpen()).isFalse();
        assertThat(other.isOpen()).as("没进这条链路的会话不受影响").isTrue();
        assertThat(links.sent.stream().noneMatch(s -> s.frame().hasPlayerLeave())).isTrue();
        assertThat(login.closed).hasSize(1);
    }

    @Test
    void 旧代次链路断开不影响已在新代次上进场的会话() {
        EmbeddedChannel ch = enteredScene();
        router.onLinkDown(SCENE_NODE, links.generation - 1);
        ch.runPendingTasks();
        assertThat(ch.isOpen()).isTrue();
    }

    // ================================================================ 进场失败回到「已登录、未进游戏」（可重试 / 换角色 / 建角）

    /** 进场失败之后：会话仍开着，CreatePlayer 与换一个角色 EnterGame 发给 login 的 player_id 都是 0（login 据此放行）。 */
    private void assertBackToCharacterSelect(EmbeddedChannel ch, long nextRequestId) {
        assertThat(ch.isOpen()).isTrue();
        int before = login.calls.size();
        ch.writeInbound(request(nextRequestId, CREATE_MSG, "create"));
        login.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("create-resp")).build());
        ch.runPendingTasks();
        ch.writeInbound(request(nextRequestId + 1, ENTER_GAME_MSG, "enter-other-player"));
        assertThat(login.calls).hasSize(before + 2);
        assertThat(login.calls.get(before).getSession().getPlayerId()).isZero();
        assertThat(login.calls.get(before + 1).getSession().getPlayerId()).isZero();
        assertThat(login.calls.get(before + 1).getSession().getAccount()).isEqualTo("robot_0001");
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void scene回3023后_会话回到未进游戏_可建角可换角色进游戏() {
        EmbeddedChannel ch = loggedInAndEntered();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setTipId(3023).setOwnerEpoch(5).build());
        ch.runPendingTasks();
        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);

        assertBackToCharacterSelect(ch, 10);
    }

    @Test
    void 建链失败后_会话回到未进游戏_可建角可换角色进游戏() {
        EmbeddedChannel ch = loggedInAndEntered();
        router.onEnterUndeliverable(SCENE_NODE, links.generation, links.sent.get(0).frame().getPlayerEnter());
        ch.runPendingTasks();
        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);

        assertBackToCharacterSelect(ch, 10);
    }

    @Test
    void 链路层不可用send返回0_推3023_会话回到未进游戏_请login释放归属() {
        EmbeddedChannel ch = verified();
        links.generation = 0;
        ch.writeInbound(request(1, ENTER_GAME_MSG, "x"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));

        links.generation = 11;
        assertBackToCharacterSelect(ch, 10);
    }

    @Test
    void 进场成功之后EnterGame仍带着已绑定的玩家() {
        EmbeddedChannel ch = loggedInAndEntered();
        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).build());
        ch.runPendingTasks();

        ch.writeInbound(request(10, ENTER_GAME_MSG, "again"));
        assertThat(login.calls.getLast().getSession().getPlayerId()).isEqualTo(PLAYER);
    }

    // ================================================================ 同一会话多次进场：只认当前那次（owner_epoch）

    /** 进场（epoch 5）→ LeaveGame → 再次进场（epoch 6），两次都在同一节点、同一链路代次上。 */
    private EmbeddedChannel reenteredWithNewEpoch() {
        EmbeddedChannel ch = loggedInAndEntered();
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        login.complete(unbindReply());
        ch.runPendingTasks();
        ch.writeInbound(request(3, ENTER_GAME_MSG, "again"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene().toBuilder().setOwnerEpoch(6)))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) ch.readOutbound()).getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(links.last().frame().getPlayerEnter().getOwnerEpoch()).isEqualTo(6);
        return ch;
    }

    @Test
    void 更早一次进场的迟到失败结果_不解绑新的进场() {
        EmbeddedChannel ch = reenteredWithNewEpoch();

        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setTipId(3023).setOwnerEpoch(5).build());
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).as("不推 tip").isNull();
        MessageContent notify = MessageContent.newBuilder().setMessageId(79).build();
        router.onToClient(SCENE_NODE, links.generation, ToClient.newBuilder().addSessionIds(sessionId())
                .setMessageContent(notify.toByteString()).build());
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).as("绑定还在：下行照常送达").isEqualTo(notify);
        ch.writeInbound(request(9, SCENE_MSG, "skills"));
        assertThat(links.last().frame().hasClientForward()).as("scene 请求照常转发").isTrue();
    }

    @Test
    void 当前这次进场的失败结果_照常解绑并推tip() {
        EmbeddedChannel ch = reenteredWithNewEpoch();

        router.onPlayerEnterResult(SCENE_NODE, links.generation, PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setTipId(3023).setOwnerEpoch(6).build());
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        ch.writeInbound(request(9, SCENE_MSG, "skills"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
    }

    @Test
    void 更早一次进场的迟到建链失败回报_只请login放弃那一次的epoch_不解绑新的进场() {
        EmbeddedChannel ch = reenteredWithNewEpoch();

        router.onEnterUndeliverable(SCENE_NODE, links.generation, links.sent.get(0).frame().getPlayerEnter());
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(login.abandoned).as("那一帧从未写上链路：一律放弃它的 epoch（带围栏，已被取代时 login 侧什么也不做）")
                .extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));
        ch.writeInbound(request(9, SCENE_MSG, "skills"));
        assertThat(links.last().frame().hasClientForward()).isTrue();
        assertThat(links.last().frame().getClientForward().getPlayerId()).isEqualTo(PLAYER);
    }

    // ================================================================ 数据归属被接管：scene 踢出

    @Test
    void scene踢出会话上的玩家_推2017后断开_不发PlayerLeave_通知login() {
        EmbeddedChannel ch = loggedInAndEntered();
        int framesBefore = links.sent.size();

        router.onPlayerKicked(SCENE_NODE, links.generation, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(2017);
        assertThat(ch.isOpen()).isFalse();
        assertThat(links.sent).as("scene 已移除并写回，不再发 PlayerLeave").hasSize(framesBefore);
        assertThat(login.closed).hasSize(1);
        assertThat(registry.size()).isZero();
    }

    @Test
    void 踢出帧的玩家或epoch或链路代次对不上_忽略() {
        EmbeddedChannel ch = loggedInAndEntered();

        router.onPlayerKicked(SCENE_NODE, links.generation, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(4).setTipId(2017).build());
        router.onPlayerKicked(SCENE_NODE, links.generation, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER + 1).setOwnerEpoch(5).setTipId(2017).build());
        router.onPlayerKicked(SCENE_NODE, links.generation + 1, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isTrue();
    }

    // ================================================================ 关闭途中迟到的 UnbindPlayer

    @Test
    void 关闭途中迟到的UnbindPlayer清了玩家绑定_断线仍按场景里的玩家发PlayerLeave() {
        // 回归：LeaveGame 在途时连接因超限进入 closing，login 应答先于 channelInactive 执行，只清了 playerId、
        // 没清场景绑定；断线发出 PlayerLeave{player_id=0}，scene 按 player_id 不符忽略，玩家成了幽灵。
        EmbeddedChannel ch = loggedInAndEntered();
        ClientSession session = registry.get(sessionId());
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        login.complete(unbindReply());
        // 直接调 dispatcher，不经 writeInbound（它会先跑排队任务）：让 login 应答排在 closeNow 之后执行。
        for (int i = 0; i < 3; i++) {
            dispatcher.onRequest(session, request(10 + i, 99_999, "x"));
        }
        ch.runPendingTasks();

        List<PlayerLeave> leaves = links.sent.stream().filter(s -> s.frame().hasPlayerLeave())
                .map(s -> s.frame().getPlayerLeave()).toList();
        assertThat(leaves).singleElement().satisfies(leave -> {
            assertThat(leave.getPlayerId()).isEqualTo(PLAYER);
            assertThat(leave.getVoluntary()).as("LeaveGame 在途").isTrue();
        });
        assertThat(login.closed).hasSize(1);
        assertThat(registry.size()).isZero();
    }

    // ================================================================ 跨节点换图改绑（批次 5.2，scene-handoff-spec §5.7 / §10.4）

    /** 源节点链路代次（{@link FakeLinks} 的缺省代次）。 */
    private static final long SOURCE_GEN = 11;
    private static final int TARGET_NODE = 8;
    private static final long TARGET_SCENE = 901;
    /** 到目标节点的链路代次。 */
    private static final long TARGET_GEN = 21;

    /** 源节点发来的改绑指令（会话 = 最早那个会话，玩家 = PLAYER）。 */
    private PlayerTransfer transfer(long fromEpoch, long toEpoch) {
        return PlayerTransfer.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER)
                .setFromEpoch(fromEpoch).setToEpoch(toEpoch)
                .setTargetSceneNodeId(TARGET_NODE).setTargetSceneId(TARGET_SCENE)
                .build();
    }

    private PlayerEnterResult enterResult(long ownerEpoch, int tipId) {
        return PlayerEnterResult.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(ownerEpoch).setTipId(tipId).build();
    }

    private ToClient toClient(MessageContent content) {
        return ToClient.newBuilder().addSessionIds(sessionId()).setMessageContent(content.toByteString()).build();
    }

    /** 已在源节点确认进场（epoch 5，代次 11），收到改绑指令（5 → 6）后改绑到目标节点（代次 21）、还没收到目标节点结果的会话。 */
    private EmbeddedChannel transferred() {
        EmbeddedChannel ch = confirmedInGame();
        links.generation = TARGET_GEN;
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        ch.runPendingTasks();
        assertThat(links.last().sceneNodeId()).isEqualTo(TARGET_NODE);
        assertThat(links.last().frame().getPlayerEnter().getTransfer()).isTrue();
        return ch;
    }

    private List<FakeLinks.Sent> sentSince(int index) {
        return links.sent.subList(index, links.sent.size());
    }

    private double sceneTransfers(String result) {
        return meters.get("xm.gate.scene.transfers").tag("result", result).counter().count();
    }

    @Test
    void 改绑_不给源节点发PlayerLeave_向目标节点发交出进场_在线登记保持_目标确认后以新epoch重登在线() {
        EmbeddedChannel ch = confirmedInGame();
        int framesBefore = links.sent.size();
        links.generation = TARGET_GEN;

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        ch.runPendingTasks();

        assertThat(sentSince(framesBefore)).as("只向目标节点发一帧交出进场，不给源节点发 PlayerLeave")
                .containsExactly(new FakeLinks.Sent(TARGET_NODE, NodeLinkFrame.newBuilder()
                        .setPlayerEnter(PlayerEnter.newBuilder()
                                .setSessionId(sessionId()).setPlayerId(PLAYER).setSceneId(TARGET_SCENE)
                                .setOwnerEpoch(6).setTransfer(true))
                        .build()));
        assertThat((Object) ch.readOutbound()).as("改绑本身对客户端不可见").isNull();
        assertThat(presence.events).as("gap 期间不撤在线登记")
                .containsExactly(new RecordingPresence.Event(true, PLAYER, sessionId(), 5));
        assertThat(dispatcher.deliverPush(session(), PLAYER, pushMessage())).as("gap 期间服务端推送照常送达")
                .isEqualTo(GateMetrics.PushResult.DELIVERED);
        assertThat((Object) ch.readOutbound()).isEqualTo(pushMessage());

        ch.writeInbound(request(9, SCENE_MSG, "x"));
        assertThat(links.last().sceneNodeId()).as("上行转发到目标节点").isEqualTo(TARGET_NODE);
        assertThat(links.last().frame().getClientForward().getPlayerId()).isEqualTo(PLAYER);

        MessageContent enterNotify = MessageContent.newBuilder().setMessageId(79).build();
        router.onToClient(TARGET_NODE, TARGET_GEN, toClient(enterNotify));
        router.onPlayerEnterResult(TARGET_NODE, TARGET_GEN, enterResult(6, 0));
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).as("目标节点的 79 照常下发").isEqualTo(enterNotify);
        assertThat(presence.events).as("目标确认后以新 epoch 重登（在线标记一直为 true 也要重登）")
                .containsExactly(new RecordingPresence.Event(true, PLAYER, sessionId(), 5),
                        new RecordingPresence.Event(true, PLAYER, sessionId(), 6));
        assertThat(ch.isOpen()).isTrue();
        assertThat(login.abandoned).isEmpty();
        assertThat(sceneTransfers("rebound")).isEqualTo(1);
        assertThat(sceneTransfers("entered")).isEqualTo(1);
        assertThat(meters.get("xm.gate.link.frames").tag("direction", "in").tag("type", "player_transfer").counter())
                .as("链路帧计数器按帧类型自动出现").isNotNull();
    }

    @Test
    void 改绑之前源节点的下行照常下发_之后源节点的迟到帧全部丢弃() {
        EmbeddedChannel ch = confirmedInGame();
        MessageContent before = MessageContent.newBuilder().setMessageId(63).setId(3).build();
        MessageContent after = MessageContent.newBuilder().setMessageId(66).build();
        links.generation = TARGET_GEN;

        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(before));
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(after));
        router.onPlayerKicked(SCENE_NODE, SOURCE_GEN, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());
        router.onPlayerEnterResult(SCENE_NODE, SOURCE_GEN, enterResult(5, 3023));
        router.onLinkDown(SCENE_NODE, SOURCE_GEN);
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).as("同链路先到的下行（冻结中请求的应答）照常下发").isEqualTo(before);
        assertThat((Object) ch.readOutbound()).as("改绑后源节点的下行 / 踢出 / 进场结果都不再属于这个会话").isNull();
        assertThat(ch.isOpen()).as("源节点链路断开不影响已改绑的会话").isTrue();
        ch.writeInbound(request(9, SCENE_MSG, "x"));
        assertThat(links.last().sceneNodeId()).isEqualTo(TARGET_NODE);
    }

    @Test
    void 过期的改绑指令_请login放弃新epoch_不改绑() {
        EmbeddedChannel ch = confirmedInGame();
        int framesBefore = links.sent.size();

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN + 1, transfer(5, 6));
        router.onPlayerTransfer(SCENE_NODE + 1, SOURCE_GEN, transfer(5, 6));
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6).toBuilder().setPlayerId(PLAYER + 1).build());
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(4, 7));
        ch.runPendingTasks();

        assertThat(login.abandoned).as("代次 / 节点 / 玩家 / from_epoch 任一对不上都按过期放弃 to_epoch")
                .extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L), tuple(PLAYER, 6L), tuple(PLAYER + 1, 6L), tuple(PLAYER, 7L));
        assertThat(sentSince(framesBefore)).as("不改绑、不发进场").isEmpty();
        assertThat(sceneTransfers("stale")).isEqualTo(4);
        assertThat(sceneTransfers("rebound")).isZero();

        MessageContent notify = MessageContent.newBuilder().setMessageId(66).build();
        router.onToClient(SCENE_NODE, SOURCE_GEN, toClient(notify));
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).as("仍绑定在源节点").isEqualTo(notify);
        assertThat(presence.events).containsExactly(new RecordingPresence.Event(true, PLAYER, sessionId(), 5));
    }

    @Test
    void 会话不在场景里或正在关闭_改绑指令按过期放弃() {
        // 会话号被复用给一个还没进游戏的新会话：绑定对不上
        EmbeddedChannel fresh = verified();
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        fresh.runPendingTasks();
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(links.sent).isEmpty();
        assertThat(fresh.isOpen()).isTrue();

        // 正在关闭（推了 tip、等写完再关）的会话：不改绑，断线流程照常让源节点处理
        fresh.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(enterGameReply());
        fresh.runPendingTasks();
        int framesBefore = links.sent.size();
        session().closing = true;
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        fresh.runPendingTasks();
        assertThat(login.abandoned).hasSize(2);
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(sceneTransfers("stale")).isEqualTo(2);
    }

    @Test
    void 会话已断开并从会话表释放_路由层照样请login放弃新epoch() {
        EmbeddedChannel ch = confirmedInGame();
        ch.close();
        ch.runPendingTasks();
        assertThat(registry.size()).isZero();
        int framesBefore = links.sent.size();

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));

        assertThat(login.abandoned).hasSize(1);
        AbandonedEnter abandoned = login.abandoned.get(0);
        assertThat(abandoned.getPlayerId()).isEqualTo(PLAYER);
        assertThat(abandoned.getOwnerEpoch()).isEqualTo(6);
        assertThat(abandoned.getSession().getGateNodeId()).isEqualTo(GATE_NODE);
        assertThat(abandoned.getSession().getGateInstanceId()).isEqualTo("gate-uuid");
        assertThat(abandoned.getSession().getZoneId()).isEqualTo(ZONE);
        assertThat(abandoned.getSession().getSessionId()).isEqualTo(sessionId());
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(sceneTransfers("orphan")).isEqualTo(1);
    }

    @Test
    void 重复的改绑指令_不放弃目标节点正在用的新epoch() {
        EmbeddedChannel ch = transferred();
        int framesBefore = links.sent.size();

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        ch.runPendingTasks();

        assertThat(login.abandoned).isEmpty();
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(sceneTransfers("rebound")).isEqualTo(1);
        assertThat(sceneTransfers("stale")).isEqualTo(1);
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 改绑后到目标的链路层不可用_放弃新epoch_推3023后断开_原因transfer_failed() {
        EmbeddedChannel ch = confirmedInGame();
        int framesBefore = links.sent.size();
        links.generation = 0;

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6));
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        assertThat(ch.isOpen()).isFalse();
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(sentSince(framesBefore)).as("只有那帧没受理的交出进场，没有 PlayerLeave")
                .allSatisfy(s -> assertThat(s.frame().hasPlayerLeave()).isFalse());
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
        assertThat(login.closed).hasSize(1);
        assertThat(login.closed.get(0).getSession().getPlayerId()).isEqualTo(PLAYER);
        assertThat(registry.size()).isZero();
        assertThat(disconnects("transfer_failed")).isEqualTo(1);
        assertThat(sceneTransfers("rebound")).isEqualTo(1);
        assertThat(sceneTransfers("link_unavailable")).isEqualTo(1);
    }

    @Test
    void 目标节点拒绝交出进场_推tip后断开_不回大厅_不发PlayerLeave() {
        EmbeddedChannel ch = transferred();
        int framesBefore = links.sent.size();

        router.onPlayerEnterResult(TARGET_NODE, TARGET_GEN, enterResult(6, 3023));
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        assertThat(ch.isOpen()).as("对照「进场被 scene 拒绝时解绑场景并推 tip」：交出进场失败不回大厅").isFalse();
        assertThat(sentSince(framesBefore)).as("目标节点已拒绝并释放，不再发 PlayerLeave").isEmpty();
        assertThat(login.abandoned).as("目标节点见过这次进场，由它释放").isEmpty();
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
        assertThat(login.closed).hasSize(1);
        assertThat(disconnects("transfer_failed")).isEqualTo(1);
        assertThat(sceneTransfers("enter_failed")).isEqualTo(1);
    }

    @Test
    void 交出进场帧没能送到目标节点_放弃新epoch_推3023后断开() {
        EmbeddedChannel ch = transferred();
        PlayerEnter enter = links.last().frame().getPlayerEnter();
        int framesBefore = links.sent.size();

        router.onEnterUndeliverable(TARGET_NODE, TARGET_GEN, enter);
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        assertThat(ch.isOpen()).isFalse();
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(sentSince(framesBefore)).isEmpty();
        assertThat(disconnects("transfer_failed")).isEqualTo(1);
        assertThat(sceneTransfers("undeliverable")).isEqualTo(1);
    }

    @Test
    void 改绑后断线_PlayerLeave发往目标节点() {
        EmbeddedChannel ch = transferred();
        ch.close();
        ch.runPendingTasks();

        assertThat(links.last().sceneNodeId()).isEqualTo(TARGET_NODE);
        assertThat(links.last().frame().getPlayerLeave()).isEqualTo(PlayerLeave.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setVoluntary(false).build());
        assertThat(login.closed).hasSize(1);
        assertThat(disconnects("transfer_failed")).isZero();
    }

    @Test
    void 改绑后LeaveGame_主动PlayerLeave发往目标节点_目标迟到的成功结果不再登记在线() {
        EmbeddedChannel ch = transferred();
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        login.complete(unbindReply());
        ch.runPendingTasks();

        assertThat(links.last().sceneNodeId()).isEqualTo(TARGET_NODE);
        assertThat(links.last().frame().getPlayerLeave().getVoluntary()).isTrue();
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));

        router.onPlayerEnterResult(TARGET_NODE, TARGET_GEN, enterResult(6, 0));
        ch.runPendingTasks();
        assertThat(presence.events).last().isEqualTo(new RecordingPresence.Event(false, PLAYER, sessionId(), 0));
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 改绑后目标链路断开_关闭会话() {
        EmbeddedChannel ch = transferred();
        int framesBefore = links.sent.size();

        router.onLinkDown(TARGET_NODE, TARGET_GEN);
        ch.runPendingTasks();

        assertThat(ch.isOpen()).isFalse();
        assertThat(sentSince(framesBefore)).as("链路已断，不发 PlayerLeave").isEmpty();
        assertThat(disconnects("scene_link_down")).isEqualTo(1);
    }

    @Test
    void 非法的改绑指令_推3023后断开_绑定不动_断线照常让源节点放掉() {
        EmbeddedChannel ch = confirmedInGame();
        int framesBefore = links.sent.size();

        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 6).toBuilder().setTargetSceneNodeId(0).build());
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).isEqualTo(3023);
        assertThat(ch.isOpen()).isFalse();
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(sentSince(framesBefore)).as("没改绑：断线流程向源节点发 PlayerLeave")
                .containsExactly(new FakeLinks.Sent(SCENE_NODE, NodeLinkFrame.newBuilder()
                        .setPlayerLeave(PlayerLeave.newBuilder().setSessionId(sessionId()).setPlayerId(PLAYER))
                        .build()));
        assertThat(sceneTransfers("invalid")).isEqualTo(1);
        assertThat(disconnects("transfer_failed")).isEqualTo(1);
    }

    @Test
    void 新epoch不大于旧epoch的改绑指令_按非法处理_不放弃任何epoch() {
        EmbeddedChannel ch = confirmedInGame();
        router.onPlayerTransfer(SCENE_NODE, SOURCE_GEN, transfer(5, 5));
        ch.runPendingTasks();

        assertThat(tipOf(ch.readOutbound())).as("不当成重复帧忽略：会话不能挂在可能已移除实例的源节点上").isEqualTo(3023);
        assertThat(ch.isOpen()).isFalse();
        assertThat(login.abandoned).as("不能拿一个不比旧 epoch 新的值去放弃（可能就是源节点自己持有的）").isEmpty();
        assertThat(sceneTransfers("invalid")).isEqualTo(1);
    }

    // ================================================================ 没送到 scene 的进场帧一律放弃（G10）

    @Test
    void 会话已断开并从会话表释放后_建链失败回报照样请login释放归属() {
        EmbeddedChannel ch = enteredScene();
        PlayerEnter enter = links.sent.get(0).frame().getPlayerEnter();
        ch.close();
        ch.runPendingTasks();
        assertThat(registry.size()).isZero();

        router.onEnterUndeliverable(SCENE_NODE, SOURCE_GEN, enter);

        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));
        assertThat(login.abandoned.get(0).getSession().getSessionId()).isEqualTo(sessionId());
        assertThat(sceneTransfers("undeliverable")).as("普通进场不计改绑指标").isZero();
    }

    @Test
    void 会话已关闭但还在会话表里_建链失败回报照样请login释放归属() {
        EmbeddedChannel ch = enteredScene();
        PlayerEnter enter = links.sent.get(0).frame().getPlayerEnter();
        ch.writeInbound(request(2, LOGIN_MSG, "x"));
        ch.close();
        ch.runPendingTasks();
        assertThat(registry.size()).as("login 调用在途：断线流程等它回来才释放会话").isEqualTo(1);

        router.onEnterUndeliverable(SCENE_NODE, SOURCE_GEN, enter);
        ch.runPendingTasks();
        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L));

        login.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        assertThat(registry.size()).isZero();
    }

    @Test
    void 改绑后断线_交出进场帧随建链失败没送到目标_路由层照样放弃新epoch() {
        EmbeddedChannel ch = transferred();
        PlayerEnter enter = links.last().frame().getPlayerEnter();
        ch.close();
        ch.runPendingTasks();
        assertThat(registry.size()).isZero();

        router.onEnterUndeliverable(TARGET_NODE, TARGET_GEN, enter);

        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(sceneTransfers("undeliverable")).isEqualTo(1);
    }

    // ================================================================ 按消息号限频（C++ MessageLimiter）

    @Test
    void 生产模式拒绝GM指令_推23的1006_计非法包_不转发() {
        EmbeddedChannel ch = enteredScene();
        int framesBefore = links.sent.size();

        ch.writeInbound(request(5, GM_MSG, "gm"));

        assertThat(tipOf(ch.readOutbound())).isEqualTo(1006);
        assertThat(links.sent).as("不转发给 scene").hasSize(framesBefore);
        assertThat(requests("scene", GM_MSG, "gm_rejected")).isEqualTo(1);
        assertThat(session().illegalPackets).isEqualTo(1);
    }

    @Test
    void 开发模式放行GM指令给scene() {
        ClientDispatcher dev = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES, TIP_MSG, login, links, registry,
                new GateLimits(4, 3, Duration.ZERO, MessageLimits.UNLIMITED, true), metrics, presence);
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dev));
        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        ch.readOutbound();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(enterGameReply());
        ch.runPendingTasks();
        ch.readOutbound();
        int framesBefore = links.sent.size();

        ch.writeInbound(request(5, GM_MSG, "gm"));

        assertThat(links.sent).hasSize(framesBefore + 1);
        assertThat(links.last().frame().getClientForward().getMessageId()).isEqualTo(GM_MSG);
    }

    @Test
    void 超频回1008信封错误_不转发_计非法包_窗口滑过后恢复() {
        AtomicLong nanos = new AtomicLong();
        ClientDispatcher limited = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES, TIP_MSG, login, links, registry,
                new GateLimits(16, 3, Duration.ZERO,
                        MessageLimits.of(Map.of(SCENE_MSG, new MessageLimit(2, Duration.ofSeconds(1))))),
                metrics, PresenceRecorder.NONE, nanos::get);
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, limited));
        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        ch.readOutbound();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(enterGameReply());
        ch.runPendingTasks();
        ch.readOutbound();
        int framesBefore = links.sent.size();

        ch.writeInbound(request(2, SCENE_MSG, "a"));
        ch.writeInbound(request(3, SCENE_MSG, "b"));
        ch.writeInbound(request(4, SCENE_MSG, "c"));

        assertThat(links.sent).as("窗口内只放行 2 条").hasSize(framesBefore + 2);
        MessageContent rejected = ch.readOutbound();
        assertThat(rejected.getMessageId()).isEqualTo(SCENE_MSG);
        assertThat(rejected.getId()).isEqualTo(4);
        assertThat(rejected.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_RATE_LIMIT_EXCEEDED).isEqualTo(1008);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        ch.writeInbound(request(5, SCENE_MSG, "d"));
        assertThat(links.sent).hasSize(framesBefore + 3);

        // 持续超频：被拒的计非法包，到阈值（3）断开。
        ch.writeInbound(request(6, SCENE_MSG, "e"));
        ch.writeInbound(request(7, SCENE_MSG, "f"));
        assertThat(ch.isOpen()).isTrue();
        ch.writeInbound(request(8, SCENE_MSG, "g"));
        assertThat(ch.isOpen()).isFalse();

        assertThat(requests("scene", SCENE_MSG, "rate_limited")).isEqualTo(3);
        assertThat(requests("scene", SCENE_MSG, "forwarded")).isEqualTo(4);
        assertThat(disconnects("illegal_packets")).isEqualTo(1);
    }

    // ================================================================ 指标

    @Test
    void 指标_握手结果与主动断开原因() {
        verified();
        EmbeddedChannel rejected = connect();
        rejected.writeInbound(verifyRequest(GATE_NODE + 1, NOW + 600));
        EmbeddedChannel noHandshake = connect();
        noHandshake.writeInbound(request(1, LOGIN_MSG, "x"));

        assertThat(handshakes("ok")).isEqualTo(1);
        assertThat(handshakes("wrong_gate")).isEqualTo(1);
        assertThat(handshakes("missing")).isEqualTo(1);
        assertThat(disconnects("handshake_rejected")).isEqualTo(1);
        assertThat(disconnects("no_handshake")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "forwarded")).as("没握手的请求不算客户端请求").isZero();
    }

    @Test
    void 指标_请求按路由与方法计去向_login调用计耗时() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, 99_999, "x"));
        ch.writeInbound(request(2, UNSUPPORTED_MSG, "x"));
        ch.writeInbound(request(6, BATTLE_MSG, "x"));
        ch.writeInbound(request(3, SCENE_MSG, "x"));
        ch.writeInbound(request(4, LOGIN_MSG, "x"));
        login.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();
        ch.writeInbound(request(5, LOGIN_MSG, "x"));
        login.fail(new IllegalStateException("超时"));
        ch.runPendingTasks();

        assertThat(meters.get("xm.gate.client.requests").tag("route", "unknown").tag("method", "unknown")
                .tag("result", "unknown_message").counter().count()).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, UNSUPPORTED_MSG, "unsupported")).isEqualTo(1);
        // 战斗上行单独一个取值（§9：xm_gate_client_requests_total{result="battle_rejected"}），不混进 unsupported
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "battle_rejected")).isEqualTo(1);
        assertThat(requests(MessageRoutes.BACKEND_UNSUPPORTED, BATTLE_MSG, "unsupported")).isZero();
        assertThat(meters.find("xm.gate.client.requests").tag("result", "battle_rejected").counters())
                .as("每个请求恰好计一次去向").extracting(Counter::count).containsExactly(1.0);
        assertThat(requests("scene", SCENE_MSG, "not_in_scene")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "forwarded")).isEqualTo(2);
        assertThat(loginCalls("handle", "ok")).isEqualTo(1);
        assertThat(loginCalls("handle", "error")).isEqualTo(1);
    }

    @Test
    void 指标_断线时排队中的请求计dropped_会话结束通知计login调用() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, LOGIN_MSG, "a"));
        ch.writeInbound(request(2, LOGIN_MSG, "b"));
        ch.writeInbound(request(3, LOGIN_MSG, "c"));

        ch.close();
        login.complete(ClientReply.getDefaultInstance());
        ch.runPendingTasks();

        assertThat(requests("login", LOGIN_MSG, "forwarded")).isEqualTo(1);
        assertThat(requests("login", LOGIN_MSG, "dropped")).isEqualTo(2);
        assertThat(loginCalls("sessionClosed", "ok")).isEqualTo(1);
    }

    @Test
    void 指标_被踢计一次主动断开() {
        EmbeddedChannel ch = loggedInAndEntered();
        router.onPlayerKicked(SCENE_NODE, links.generation, PlayerKicked.newBuilder()
                .setSessionId(sessionId()).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());
        ch.runPendingTasks();

        assertThat(ch.isOpen()).isFalse();
        assertThat(disconnects("kicked")).isEqualTo(1);
        assertThat(disconnects("scene_link_down")).isZero();
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

    private double loginCalls(String method, String result) {
        return meters.get("xm.gate.backend.calls").tag("backend", "login").tag("method", method).tag("result", result)
                .timer().count();
    }

    // ================================================================ 工具

    private EmbeddedChannel connect() {
        return new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher));
    }

    private EmbeddedChannel verified() {
        EmbeddedChannel ch = connect();
        ch.writeInbound(verifyRequest(GATE_NODE, NOW + 600));
        ClientTokenVerifyResponse ok = ch.readOutbound();
        assertThat(ok.getSuccess()).isTrue();
        return ch;
    }

    /** 第二条已握手连接（不进场景）。 */
    private EmbeddedChannel verified2() {
        return verified();
    }

    private EmbeddedChannel enteredScene() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(enterGameReply());
        ch.runPendingTasks();
        MessageContent enterGameResponse = ch.readOutbound();
        assertThat(enterGameResponse.getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(links.sent).hasSize(1);
        return ch;
    }

    /** 已登录（账号 robot_0001）并进了场景（epoch 5）的会话。 */
    private EmbeddedChannel loggedInAndEntered() {
        EmbeddedChannel ch = verified();
        ch.writeInbound(request(1, ENTER_GAME_MSG, "enter"));
        login.complete(ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setBindAccount(BindAccount.newBuilder().setAccount("robot_0001")))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build());
        ch.runPendingTasks();
        MessageContent enterGameResponse = ch.readOutbound();
        assertThat(enterGameResponse.getMessageId()).isEqualTo(ENTER_GAME_MSG);
        assertThat(links.sent).hasSize(1);
        return ch;
    }

    /** 最早建立的那个会话的会话号（测试里按需只开一两条连接）。 */
    private int sessionId() {
        return (GATE_NODE << SessionIdAllocator.SEQ_BITS) | 1;
    }

    private static ClientRequest request(long id, int messageId, String body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.copyFromUtf8(body)).build();
    }

    private static EnterScene enterScene() {
        return EnterScene.newBuilder().setPlayerId(PLAYER).setSceneNodeId(SCENE_NODE).setSceneId(900).setOwnerEpoch(5).build();
    }

    private static ClientReply unbindReply() {
        return ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setUnbindPlayer(UnbindPlayer.getDefaultInstance()))
                .build();
    }

    private static ClientReply enterGameReply() {
        return ClientReply.newBuilder()
                .setBody(ByteString.copyFromUtf8("enter-resp"))
                .addDirectives(SessionDirective.newBuilder().setEnterScene(enterScene()))
                .build();
    }

    private static ByteString payload(int gateNodeId, long expire) {
        return GateTokenPayload.newBuilder().setGateNodeId(gateNodeId).setZoneId(ZONE).setExpireTimestamp(expire).build().toByteString();
    }

    private ClientTokenVerifyRequest verifyRequest(int gateNodeId, long expire) {
        ByteString payload = payload(gateNodeId, expire);
        return ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build();
    }

    private static int tipOf(Object outbound) {
        MessageContent content = (MessageContent) outbound;
        assertThat(content.getMessageId()).isEqualTo(TIP_MSG);
        assertThat(content.hasErrorMessage()).isFalse();
        try {
            return TipInfoMessage.parseFrom(content.getSerializedMessage()).getId();
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
