package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 链路事件路由层（链路 I/O 线程）：找得到会话就投递到会话线程；找不到会话（已从会话表释放）或会话线程已停时，
 * 带着归属的事件（改绑指令、未送达的进场帧）由路由层直接请 login 放弃（scene-handoff-spec §5.7、§10.4，修 G10）。
 * 会话线程上的裁决见 {@link ClientDispatcherTest}。
 */
class SceneEventRouterTest {

    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int SCENE_NODE = 7;
    private static final long LINK_GEN = 11;
    private static final long PLAYER = 42L;
    /** 会话表里没有的会话号。 */
    private static final int GONE_SESSION = 123_456;

    private final FakeLogin login = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ClientDispatcher dispatcher = new ClientDispatcher(
            new GateIdentity(GATE_NODE, "gate-uuid", ZONE), GateTokens.ofUtf8("test-secret"),
            InstantSource.fixed(Instant.ofEpochSecond(1_800_000_000L)), id -> null, 23, login, links, registry,
            new GateLimits(4, 3, Duration.ZERO), new GateMetrics(meters), PresenceRecorder.NONE);
    private final SceneEventRouter router = new SceneEventRouter(registry, dispatcher);

    @Test
    void 找不到会话的改绑指令_在链路线程上直接请login放弃新epoch_上下文只带gate身份与会话号() {
        router.onPlayerTransfer(SCENE_NODE, LINK_GEN, transfer(GONE_SESSION));

        assertThat(login.abandoned).hasSize(1);
        AbandonedEnter abandoned = login.abandoned.get(0);
        assertThat(abandoned.getPlayerId()).isEqualTo(PLAYER);
        assertThat(abandoned.getOwnerEpoch()).as("放弃的是交出铸出的新 epoch").isEqualTo(6);
        assertThat(abandoned.getSession().getGateNodeId()).isEqualTo(GATE_NODE);
        assertThat(abandoned.getSession().getGateInstanceId()).isEqualTo("gate-uuid");
        assertThat(abandoned.getSession().getZoneId()).isEqualTo(ZONE);
        assertThat(abandoned.getSession().getSessionId()).isEqualTo(GONE_SESSION);
        assertThat(abandoned.getSession().getPlayerId()).isZero();
        assertThat(links.sent).as("没有会话可改绑，不发进场").isEmpty();
        assertThat(transfers("orphan")).isEqualTo(1);
    }

    @Test
    void 找不到会话的未送达进场帧_在链路线程上直接请login放弃() {
        router.onEnterUndeliverable(SCENE_NODE, LINK_GEN, enter(GONE_SESSION, 5, false));
        router.onEnterUndeliverable(SCENE_NODE, LINK_GEN, enter(GONE_SESSION, 6, true));

        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 5L), tuple(PLAYER, 6L));
        assertThat(login.abandoned.get(0).getSession().getSessionId()).isEqualTo(GONE_SESSION);
        assertThat(transfers("undeliverable")).as("只有交出进场帧计改绑指标").isEqualTo(1);
    }

    @Test
    void 会话线程已停投递被拒_同样直接放弃() {
        EventLoop stopped = mock(EventLoop.class);
        doThrow(new RejectedExecutionException("会话线程已关闭")).when(stopped).execute(any(Runnable.class));
        Channel channel = mock(Channel.class);
        when(channel.eventLoop()).thenReturn(stopped);
        ClientSession session = registry.open(channel, "127.0.0.1");

        router.onPlayerTransfer(SCENE_NODE, LINK_GEN, transfer(session.sessionId()));
        router.onEnterUndeliverable(SCENE_NODE, LINK_GEN, enter(session.sessionId(), 5, false));

        assertThat(login.abandoned).extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L), tuple(PLAYER, 5L));
        assertThat(transfers("orphan")).isEqualTo(1);
    }

    @Test
    void 找得到会话时投递到会话线程裁决_不在链路线程上动会话() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClientSession session = registry.open(channel, "127.0.0.1");

        router.onPlayerTransfer(SCENE_NODE, LINK_GEN, transfer(session.sessionId()));
        assertThat(login.abandoned).as("链路线程上只投递").isEmpty();

        channel.runPendingTasks();
        assertThat(login.abandoned).as("会话没进场景：会话线程上按过期放弃")
                .extracting(AbandonedEnter::getPlayerId, AbandonedEnter::getOwnerEpoch)
                .containsExactly(tuple(PLAYER, 6L));
        assertThat(transfers("stale")).isEqualTo(1);
        assertThat(transfers("orphan")).isZero();
    }

    @Test
    void 不带归属的链路事件找不到会话照旧丢弃() {
        router.onPlayerEnterResult(SCENE_NODE, LINK_GEN, PlayerEnterResult.newBuilder()
                .setSessionId(GONE_SESSION).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(3023).build());
        router.onPlayerKicked(SCENE_NODE, LINK_GEN, PlayerKicked.newBuilder()
                .setSessionId(GONE_SESSION).setPlayerId(PLAYER).setOwnerEpoch(5).setTipId(2017).build());

        assertThat(login.abandoned).isEmpty();
        assertThat(links.sent).isEmpty();
    }

    private double transfers(String result) {
        return meters.get("xm.gate.scene.transfers").tag("result", result).counter().count();
    }

    private static PlayerTransfer transfer(int sessionId) {
        return PlayerTransfer.newBuilder()
                .setSessionId(sessionId).setPlayerId(PLAYER).setFromEpoch(5).setToEpoch(6)
                .setTargetSceneNodeId(SCENE_NODE + 1).setTargetSceneId(901)
                .build();
    }

    private static PlayerEnter enter(int sessionId, long ownerEpoch, boolean transfer) {
        return PlayerEnter.newBuilder()
                .setSessionId(sessionId).setPlayerId(PLAYER).setSceneId(901).setOwnerEpoch(ownerEpoch).setTransfer(transfer)
                .build();
    }
}
