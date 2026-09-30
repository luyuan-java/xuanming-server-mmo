package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ToClient;
import com.game.api.proto.UnbindPlayer;
import com.game.common.token.GateTokens;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
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
    private static final int GUILD_MSG = 500;
    private static final int SCENE_NODE = 7;
    private static final long PLAYER = 42L;

    private static final MessageRoutes ROUTES = id -> switch (id) {
        case LOGIN_MSG, CREATE_MSG, ENTER_GAME_MSG -> new MessageRoute(id, "login");
        case LEAVE_MSG -> new MessageRoute(id, "login", false);
        case SCENE_MSG -> new MessageRoute(id, "scene");
        case GUILD_MSG -> new MessageRoute(id, "guild");
        default -> null;
    };

    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final ClientDispatcher dispatcher = new ClientDispatcher(
            new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
            ROUTES, TIP_MSG, login, links, registry, new GateLimits(4, 3, Duration.ZERO));
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
    void 断线时LeaveGame在途_迟到的UnbindPlayer只清身份_只发一次被动PlayerLeave() {
        EmbeddedChannel ch = enteredScene();
        ch.writeInbound(request(2, LEAVE_MSG, ""));
        ch.close();

        login.complete(unbindReply());
        ch.runPendingTasks();

        List<PlayerLeave> leaves = links.sent.stream().filter(s -> s.frame().hasPlayerLeave())
                .map(s -> s.frame().getPlayerLeave()).toList();
        assertThat(leaves).extracting(PlayerLeave::getVoluntary).containsExactly(false);
        assertThat(login.closed).hasSize(1);
        assertThat(login.closed.get(0).getSession().getPlayerId()).isZero();
        assertThat(registry.size()).isZero();
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
        ch.writeInbound(request(5, GUILD_MSG, "x"));
        assertThat(tipOf(ch.readOutbound())).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(login.calls).isEmpty();
        assertThat(ch.isOpen()).isTrue();
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
    void 更早一次进场的迟到建链失败回报_忽略() {
        EmbeddedChannel ch = reenteredWithNewEpoch();

        router.onEnterUndeliverable(SCENE_NODE, links.generation, links.sent.get(0).frame().getPlayerEnter());
        ch.runPendingTasks();

        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(login.abandoned).isEmpty();
        ch.writeInbound(request(9, SCENE_MSG, "skills"));
        assertThat(links.last().frame().hasClientForward()).isTrue();
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
            assertThat(leave.getVoluntary()).isFalse();
        });
        assertThat(login.closed).hasSize(1);
        assertThat(registry.size()).isZero();
    }

    // ================================================================ 按消息号限频（C++ MessageLimiter）

    @Test
    void 超频回1008信封错误_不转发_计非法包_窗口滑过后恢复() {
        AtomicLong nanos = new AtomicLong();
        ClientDispatcher limited = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES, TIP_MSG, login, links, registry,
                new GateLimits(16, 3, Duration.ZERO,
                        MessageLimits.of(Map.of(SCENE_MSG, new MessageLimit(2, Duration.ofSeconds(1))))),
                nanos::get);
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
