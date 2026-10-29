package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.UnbindPlayer;
import com.game.common.token.GateTokens;
import com.game.gate.link.EnterFailure;
import com.game.gate.link.LinkSettings;
import com.game.gate.link.NettyLinkConnector;
import com.game.gate.link.SceneLink;
import com.game.gate.link.SceneLinkHandler;
import com.game.gate.link.SceneLinkManager;
import com.game.gate.metrics.GateMetrics;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 原 login 指令 → 真 SceneLinkManager 队列 → 会话 EventLoop 补发，时钟与网络可控。 */
class SceneEntryRetryTest {
    private final AtomicLong time = new AtomicLong();
    private final FakeLogin login = new FakeLogin();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(3));
    private final List<SceneLink> attempts = new ArrayList<>();
    private final List<EmbeddedChannel> transports = new ArrayList<>();
    private boolean missing;
    private final SceneLinkManager links = new SceneLinkManager(() -> LinkHello.newBuilder().setZoneId(1).build(), link -> {
        attempts.add(link);
        if (missing) new NettyLinkConnector(node -> Optional.empty(), Runnable::run, null, Duration.ofSeconds(3)).connect(link);
    }, new LinkSettings(Duration.ZERO, 100));
    private final ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(3, "gate", 1),
            GateTokens.ofUtf8("retry-test"), InstantSource.system(), id -> id == 17
                    ? new MessageRoute(id, "login", false, "ClientPlayerLogin.LeaveGame") : new MessageRoute(id, "login"), 23,
            login, links, registry, new GateLimits(4, 10, Duration.ZERO), GateMetrics.noop(), PresenceRecorder.NONE, time::get);
    private final SceneEventRouter router = new SceneEventRouter(registry, dispatcher);
    private ClientSession session;
    private final EmbeddedChannel client = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            dispatcher.onDisconnected(session);
        }
    });

    SceneEntryRetryTest() {
        client.freezeTime();
        session = registry.open(client, "127.0.0.1");
        session.verified = true; session.account = "original-account";
        links.bindListener(router);
    }

    @AfterEach void close() { client.close(); links.close(); transports.forEach(EmbeddedChannel::finishAndReleaseAll); }

    private void enter() {
        dispatcher.onRequest(session, ClientRequest.newBuilder().setId(1).setMessageId(26).build());
        login.complete(ClientReply.newBuilder().addDirectives(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                .setPlayerId(42).setSceneNodeId(7).setSceneId(900).setOwnerEpoch(11).setOwnerRevision(8))).build());
        client.runPendingTasks();
        assertThat(((MessageContent) client.readOutbound()).getMessageId()).isEqualTo(26);
    }

    private EmbeddedChannel tcp() {
        EmbeddedChannel transport = new EmbeddedChannel(new SceneLinkHandler(attempts.getLast()));
        transports.add(transport);
        assertThat(((NodeLinkFrame) transport.readOutbound()).hasHello()).isTrue();
        return transport;
    }

    private PlayerEnter acknowledge(EmbeddedChannel transport, int node) {
        transport.writeInbound(NodeLinkFrame.newBuilder().setHelloAck(LinkHelloAck.newBuilder().setAccepted(true)
                .setZoneId(1).setSceneNodeId(node).setSceneInstanceId("scene-new")).build());
        client.runPendingTasks();
        return ((NodeLinkFrame) transport.readOutbound()).getPlayerEnter();
    }

    private void advance(long millis) {
        time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        client.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        client.runScheduledPendingTasks(); client.runPendingTasks();
    }

    private int tip() throws Exception {
        MessageContent frame = client.readOutbound();
        assertThat(frame.getMessageId()).isEqualTo(23);
        return TipInfoMessage.parseFrom(frame.getSerializedMessage()).getId();
    }

    @Test void 目录短暂缺席后恢复_只重发原node及完整准入原件_成功写出即取消预算() {
        missing = true; enter();
        PlayerEnter original = session.pendingSceneEntry.enter;
        assertThat(login.abandoned).isEmpty();
        assertThat(attempts).hasSize(1);
        missing = false; advance(250);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.getLast().nodeId()).isEqualTo(7);
        EmbeddedChannel transport = tcp();
        assertThat((Object) transport.readOutbound()).as("握手未确认不发进场").isNull();
        assertThat(acknowledge(transport, 7)).isEqualTo(original);
        assertThat(original.getAccount()).isEqualTo("original-account");
        assertThat(original.getOwnerRevision()).isEqualTo(8);
        assertThat(original.getLoginGeneration()).isEqualTo(11);
        assertThat(original.getOwnerEpoch()).isEqualTo(11);
        assertThat(original.getSceneId()).isEqualTo(900);
        assertThat(session.pendingSceneEntry).isNull();
        advance(30000);
        assertThat(client.isOpen()).isTrue();
        assertThat(login.abandoned).isEmpty();
        assertThat(attempts).hasSize(2);
    }

    @Test void 握手前断开确认未发送_退避后再连接_旧尝试重复失败不释放重试中的同E() {
        enter(); PlayerEnter original = session.pendingSceneEntry.enter;
        long firstGen = session.sceneLinkGen;
        tcp().close(); client.runPendingTasks();
        assertThat(session.pendingSceneEntry.knownUnsent).isTrue();
        advance(250);
        long secondGen = session.sceneLinkGen;
        assertThat(secondGen).isGreaterThan(firstGen);
        router.onEnterUndeliverable(7, firstGen, original, EnterFailure.CONNECT_UNAVAILABLE);
        client.runPendingTasks();
        assertThat(login.abandoned).isEmpty();
        assertThat(acknowledge(tcp(), 7)).isEqualTo(original);
        router.onEnterUndeliverable(7, firstGen, original, EnterFailure.CONNECT_UNAVAILABLE);
        client.runPendingTasks(); advance(30000);
        assertThat(client.isOpen()).isTrue();
        assertThat(login.abandoned).isEmpty();
        assertThat(attempts).hasSize(2);
    }

    @Test void 目录持续缺席按首次路由三秒截止_只放弃一次并推3023关闭() throws Exception {
        missing = true; enter();
        advance(250); advance(500); advance(1000); advance(1249);
        assertThat(client.isOpen()).isTrue();
        advance(1);
        assertThat(client.isOpen()).isFalse();
        assertThat(tip()).isEqualTo(3023);
        assertThat(login.abandoned).hasSize(1);
        assertThat(login.abandoned.getFirst().getOwnerEpoch()).isEqualTo(11);
        assertThat(session.pendingSceneEntry).isNull();
        int count = attempts.size(); advance(30000);
        assertThat(attempts).hasSize(count);
    }

    @Test void 解析或握手一直未完成_二十秒取消队列原件_迟到握手不能再发() throws Exception {
        enter();
        advance(19999); assertThat(client.isOpen()).isTrue();
        advance(1);
        assertThat(client.isOpen()).isFalse();
        assertThat(tip()).isEqualTo(3023);
        assertThat(login.abandoned).hasSize(1);
        EmbeddedChannel late = tcp();
        late.writeInbound(NodeLinkFrame.newBuilder().setHelloAck(LinkHelloAck.newBuilder().setAccepted(true)
                .setZoneId(1).setSceneNodeId(7).setSceneInstanceId("scene")).build());
        assertThat((Object) late.readOutbound()).isNull();
    }

    @Test void 客户端先关闭_取消未写原件并释放_随后握手不载入旧角色() {
        enter(); client.close();
        assertThat(login.abandoned).hasSize(1);
        assertThat(session.pendingSceneEntry).isNull();
        EmbeddedChannel late = tcp();
        late.writeInbound(NodeLinkFrame.newBuilder().setHelloAck(LinkHelloAck.newBuilder().setAccepted(true)
                .setZoneId(1).setSceneNodeId(7).setSceneInstanceId("scene")).build());
        assertThat((Object) late.readOutbound()).isNull();
        advance(30000); assertThat(attempts).hasSize(1);
    }

    @Test void 退避时LeaveGame撤销原进场_旧定时器与重复失败都不能重新进入() {
        missing = true; enter();
        PlayerEnter original = session.pendingSceneEntry.enter;
        long generation = session.sceneLinkGen;
        router.onEnterUndeliverable(7, generation, original, EnterFailure.NODE_MISSING);
        client.runPendingTasks();
        dispatcher.onRequest(session, ClientRequest.newBuilder().setId(2).setMessageId(17).build());
        login.complete(ClientReply.newBuilder().addDirectives(SessionDirective.newBuilder().setUnbindPlayer(
                UnbindPlayer.getDefaultInstance())).build());
        client.runPendingTasks();
        while (client.readOutbound() != null) { }
        assertThat(login.abandoned).hasSize(1);
        assertThat(session.pendingSceneEntry).isNull();
        assertThat(session.playerId).isZero();
        missing = false; advance(30000);
        assertThat(client.isOpen()).isTrue();
        assertThat(attempts).hasSize(1);
    }

    @Test void 会话身份被替换时旧进场取消_不以新角色身份补发或关闭新会话() {
        missing = true; enter();
        session.playerId = 43; session.scenePlayerId = 43; session.sceneOwnerEpoch = 12;
        missing = false; advance(250);
        assertThat(attempts).hasSize(1);
        assertThat(session.pendingSceneEntry).isNull();
        assertThat(login.abandoned).hasSize(1);
        assertThat(login.abandoned.getFirst().getPlayerId()).isEqualTo(42);
        assertThat(login.abandoned.getFirst().getOwnerEpoch()).isEqualTo(11);
        assertThat(session.playerId).isEqualTo(43);
        assertThat(client.isOpen()).isTrue();
    }

    @Test void 已调用write后连接断开_即使进场结果未知也不重发或abandon() throws Exception {
        enter(); EmbeddedChannel transport = tcp(); acknowledge(transport, 7);
        assertThat(session.pendingSceneEntry).isNull();
        transport.close(); client.runPendingTasks();
        assertThat(client.isOpen()).isFalse();
        assertThat(tip()).isEqualTo(1003);
        assertThat(login.abandoned).isEmpty();
        advance(30000); assertThat(attempts).hasSize(1);
    }

    @Test void 截止先于未发送回调执行_保留原件等证明_不能把失败误认已发而永久悬挂() throws Exception {
        enter(); PendingSceneEntry pending = session.pendingSceneEntry;
        tcp().close(); // fail 已摘出链路队列，回调尚未在客户端 EventLoop 执行。
        time.set(PendingSceneEntry.BUDGET_NANOS);
        ReflectionTestUtils.invokeMethod(dispatcher, "expireSceneEntry", session, pending);
        assertThat(session.pendingSceneEntry).isSameAs(pending);
        assertThat(login.abandoned).isEmpty();
        client.runPendingTasks();
        assertThat(tip()).isEqualTo(3023);
        assertThat(client.isOpen()).isFalse();
        assertThat(login.abandoned).hasSize(1);
    }

    @Test void 截止先于已write通知执行_取消失败后绝不释放_随后通知仅撤销重试资格() {
        enter(); PendingSceneEntry pending = session.pendingSceneEntry;
        EmbeddedChannel transport = tcp();
        transport.writeInbound(NodeLinkFrame.newBuilder().setHelloAck(LinkHelloAck.newBuilder().setAccepted(true)
                .setZoneId(1).setSceneNodeId(7).setSceneInstanceId("scene")).build());
        assertThat(((NodeLinkFrame) transport.readOutbound()).hasPlayerEnter()).isTrue();
        time.set(PendingSceneEntry.BUDGET_NANOS);
        ReflectionTestUtils.invokeMethod(dispatcher, "expireSceneEntry", session, pending);
        assertThat(login.abandoned).isEmpty();
        client.runPendingTasks();
        assertThat(session.pendingSceneEntry).isNull();
        assertThat(client.isOpen()).isTrue();
        assertThat(attempts).hasSize(1);
    }

    @Test void 交出进场补发保留E与登录代_预算用尽后释放目标E并断开() throws Exception {
        enter(); acknowledge(tcp(), 7);
        router.onPlayerEnterResult(7, session.sceneLinkGen, PlayerEnterResult.newBuilder().setSessionId(session.sessionId())
                .setPlayerId(42).setOwnerEpoch(11).build()); client.runPendingTasks();
        long sourceGeneration = session.sceneLinkGen;
        missing = true;
        router.onPlayerTransfer(7, sourceGeneration, PlayerTransfer.newBuilder().setSessionId(session.sessionId()).setPlayerId(42)
                .setFromEpoch(11).setToEpoch(12).setTargetSceneNodeId(8).setTargetSceneId(901).build());
        client.runPendingTasks();
        assertThat(session.pendingSceneEntry.enter.getTransfer()).isTrue();
        assertThat(session.pendingSceneEntry.enter.getOwnerEpoch()).isEqualTo(12);
        assertThat(session.pendingSceneEntry.enter.getLoginGeneration()).isEqualTo(11);
        assertThat(session.pendingSceneEntry.enter.getAccount()).isEqualTo("original-account");
        advance(3000);
        assertThat(tip()).isEqualTo(3023);
        assertThat(client.isOpen()).isFalse();
        assertThat(login.abandoned).hasSize(1);
        assertThat(login.abandoned.getFirst().getOwnerEpoch()).isEqualTo(12);
    }

    @Test void 退避策略封顶两秒_十六次纵深上限_缺席预算不能被恢复失败拉长() {
        PendingSceneEntry pending = new PendingSceneEntry(7, PlayerEnter.getDefaultInstance(), 0);
        pending.attempts = 1;
        assertThat(pending.retryDelay(EnterFailure.CONNECT_UNAVAILABLE, 0)).isEqualTo(Duration.ofMillis(250).toNanos());
        pending.attempts = 8;
        assertThat(pending.retryDelay(EnterFailure.CONNECT_UNAVAILABLE, 0)).isEqualTo(Duration.ofSeconds(2).toNanos());
        pending.retryDelay(EnterFailure.NODE_MISSING, 0);
        assertThat(pending.deadline).isEqualTo(Duration.ofSeconds(3).toNanos());
        assertThat(pending.retryDelay(EnterFailure.CONNECT_UNAVAILABLE, Duration.ofSeconds(3).toNanos())).isEqualTo(-1);
        pending.attempts = 16;
        assertThat(pending.retryDelay(EnterFailure.CONNECT_UNAVAILABLE, 0)).isEqualTo(-1);
        assertThat(pending.retryDelay(EnterFailure.PERMANENT, 0)).isEqualTo(-1);
    }
}
