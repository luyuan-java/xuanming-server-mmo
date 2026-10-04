package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.EnterScene;
import com.game.api.proto.SessionContext;
import com.game.login.dispatch.HandlerReply;
import com.game.login.metrics.LoginMetrics;
import com.game.login.ownership.OwnerTakeovers;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnterGameHandlerTest {

    private static final String ACCOUNT = "robot_0001";
    private static final long PLAYER = 123456789L;
    private static final SessionContext SESSION = SessionContext.newBuilder()
            .setGateNodeId(2).setSessionId((2 << 17) | 9).setZoneId(7).setAccount(ACCOUNT).build();
    private static final AssignSceneResponse ASSIGNED = AssignSceneResponse.newBuilder()
            .setSceneNodeId(4).setSceneId(99_000_001L).setSceneConfigId(3).build();

    private static final Duration CLAIM_WAIT = Duration.ofSeconds(3);

    private final PlayerStore store = mock(PlayerStore.class);
    private final SceneDirectoryService scenes = mock(SceneDirectoryService.class);
    private final OwnerTakeovers takeovers = mock(OwnerTakeovers.class);
    /** 单调时钟（纳秒），测试手动拨动；退避用同步执行器，不真等。 */
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final List<Duration> backoffs = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoginMetrics metrics = new LoginMetrics(meters);
    private final EnterGameHandler handler = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
            Duration.ofMillis(200), CLAIM_WAIT, metrics, delay -> {
                backoffs.add(delay);
                return Runnable::run;
            }, nanos::get);

    @BeforeEach
    void setUp() {
        PlayerRow row = LoginHandlerTest.row(PLAYER, ACCOUNT, "张三");
        row.setSceneConfigId(3);
        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(row));
        when(store.claimOwnership(PLAYER)).thenReturn(new ClaimResult.Claimed(5));
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED));
    }

    private static EnterGameResponse response(HandlerReply reply) throws Exception {
        assertThat(reply.body()).isPresent();
        return EnterGameResponse.parseFrom(reply.body().get().toByteString());
    }

    private HandlerReply enter(SessionContext session, long playerId) {
        return handler.handle(session, EnterGameRequest.newBuilder().setPlayerId(playerId).build())
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static void assertError(HandlerReply reply, int tipId) throws Exception {
        EnterGameResponse response = response(reply);
        assertThat(response.getErrorMessage().getId()).isEqualTo(tipId);
        assertThat(response.getPlayerId()).isZero();
        assertThat(reply.directives()).isEmpty();
    }

    @Test
    void 成功_回player_id并下发进场景指令() throws Exception {
        HandlerReply reply = enter(SESSION, PLAYER);

        EnterGameResponse response = response(reply);
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getPlayerId()).isEqualTo(PLAYER);
        assertThat(response.getPostMergeNoticeTs()).isZero();
        assertThat(response.getForceRenameRequired()).isFalse();
        assertThat(reply.directives()).hasSize(1);
        assertThat(reply.directives().get(0).getEnterScene()).isEqualTo(EnterScene.newBuilder()
                .setPlayerId(PLAYER).setSceneNodeId(4).setSceneId(99_000_001L).setOwnerEpoch(5).build());

        ArgumentCaptor<AssignSceneRequest> request = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes).assign(request.capture());
        assertThat(request.getValue()).isEqualTo(AssignSceneRequest.newBuilder()
                .setZoneId(7).setPlayerId(PLAYER).setPreferredSceneConfigId(3).build());

        assertThat(assigns("ok")).isEqualTo(1);
        assertThat(claims("claimed")).isEqualTo(1);
        assertThat(meters.get("xm.login.owner.takeover.requests").counter().count()).isZero();
    }

    @Test
    void 会话没带zone时用本login的zone() {
        enter(SESSION.toBuilder().setZoneId(0).build(), PLAYER);
        ArgumentCaptor<AssignSceneRequest> request = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes).assign(request.capture());
        assertThat(request.getValue().getZoneId()).isEqualTo(1);
    }

    @Test
    void 会话前置条件() throws Exception {
        assertError(enter(SESSION.toBuilder().setSessionId(0).build(), PLAYER),
                LoginErrorTip.login_error.kLoginSessionIdNotFound_VALUE);
        assertError(enter(SESSION.toBuilder().clearAccount().build(), PLAYER),
                LoginErrorTip.login_error.kLoginSessionNotFound_VALUE);
        assertError(enter(SESSION.toBuilder().setPlayerId(PLAYER + 1).build(), PLAYER),
                LoginErrorTip.login_error.kLoginSessionNotFound_VALUE);
        verify(scenes, never()).assign(any());
    }

    @Test
    void 已绑定同一角色的会话再次进游戏回2028_不分配也不夺权() throws Exception {
        // 回归：曾放行「同一角色再次 EnterGame」，夺权抢在旧实例最终写回之前，旧写回被围栏拒掉、在线进度全部丢失。
        // 基线进游戏成功即删除登录会话，之后的 EnterGame 一律 2028；进场异步失败后 gate 会解绑玩家，重试不受影响。
        assertError(enter(SESSION.toBuilder().setPlayerId(PLAYER).build(), PLAYER),
                LoginErrorTip.login_error.kLoginSessionNotFound_VALUE);
        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong());
    }

    @Test
    void 归属仍被持有_请持有者让出后退避重试_释放后夺权成功() throws Exception {
        when(store.claimOwnership(PLAYER)).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(150).toNanos());
            return new ClaimResult.Held(4);
        }).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(150).toNanos());
            return new ClaimResult.Held(4);
        }).thenReturn(new ClaimResult.Claimed(5));

        HandlerReply reply = enter(SESSION, PLAYER);

        assertThat(response(reply).hasErrorMessage()).isFalse();
        assertThat(reply.directives().get(0).getEnterScene().getOwnerEpoch()).isEqualTo(5);
        verify(takeovers, times(2)).request(PLAYER, 4);
        assertThat(backoffs).as("退避翻倍").containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));

        assertThat(claims("waited")).as("等持有者让出后夺到").isEqualTo(1);
        assertThat(claims("claimed")).isZero();
        assertThat(meters.get("xm.login.owner.claims").tag("outcome", "waited").timer().totalTime(TimeUnit.MILLISECONDS))
                .as("耗时取注入的单调时钟：两次各 150ms").isEqualTo(300);
        assertThat(meters.get("xm.login.owner.takeover.requests").counter().count()).isEqualTo(2);
    }

    @Test
    void 归属在等待窗口内一直被持有_回2005_每次重试都请持有者让出() throws Exception {
        when(store.claimOwnership(PLAYER)).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return new ClaimResult.Held(4);
        });

        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        verify(store, times(3)).claimOwnership(PLAYER);
        verify(takeovers, times(3)).request(PLAYER, 4);
        assertThat(backoffs).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(EnterGameHandler.MAX_CLAIM_BACKOFF));
        assertThat(claims("timeout")).isEqualTo(1);

        // 等待链结束后在途闸门已释放：下一次（持有者已释放）能进。
        when(store.claimOwnership(PLAYER)).thenReturn(new ClaimResult.Claimed(5));
        assertThat(response(enter(SESSION, PLAYER)).hasErrorMessage()).isFalse();
    }

    @Test
    void 角色不存在或不属于本账号回2011() throws Exception {
        when(store.findPlayer(PLAYER)).thenReturn(Optional.empty());
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);

        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(LoginHandlerTest.row(PLAYER, "robot_other", "李四")));
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong());
    }

    @Test
    void 场景分配拒绝_tip原样返回且不夺权() throws Exception {
        int noScene = SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE;
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(
                AssignSceneResponse.newBuilder().setTipId(noScene).build()));

        assertError(enter(SESSION, PLAYER), noScene);
        verify(store, never()).claimOwnership(anyLong());
        assertThat(assigns("rejected")).isEqualTo(1);
        assertThat(meters.get("xm.login.owner.claims").timers()).as("分配失败不走到夺权")
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

    @Test
    void 场景分配调用失败回3023() throws Exception {
        int failed = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
        when(scenes.assign(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        assertError(enter(SESSION, PLAYER), failed);

        doThrow(new IllegalStateException("no provider")).when(scenes).assign(any());
        assertError(enter(SESSION, PLAYER), failed);

        doReturn(null).when(scenes).assign(any());
        assertError(enter(SESSION, PLAYER), failed);

        verify(store, never()).claimOwnership(anyLong());
        assertThat(assigns("error")).isEqualTo(3);
    }

    @Test
    void 场景分配超时回3023并释放在途闸门() throws Exception {
        CompletableFuture<AssignSceneResponse> never = new CompletableFuture<>();
        when(scenes.assign(any())).thenReturn(never);

        assertError(enter(SESSION, PLAYER), SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);
        // 超时只作用在副本上，不去完成 Dubbo 给的原 future。
        assertThat(never).isNotDone();

        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED));
        assertThat(response(enter(SESSION, PLAYER)).hasErrorMessage()).isFalse();
    }

    @Test
    void 退避重试时工作队列满_链以异常结束_在途闸门释放() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Executor firstOnly = task -> {
            if (executions.getAndIncrement() > 0) {
                throw new RejectedExecutionException("满");
            }
            task.run();
        };
        EnterGameHandler busy = new EnterGameHandler(store, scenes, takeovers, firstOnly, 1, Duration.ofMillis(200),
                CLAIM_WAIT, metrics, delay -> Runnable::run, nanos::get);
        when(store.claimOwnership(PLAYER)).thenReturn(new ClaimResult.Held(4));

        assertThatThrownBy(() -> busy.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(claims("error")).as("异常结束的夺权链记 error").isEqualTo(1);

        executions.set(0);
        when(store.claimOwnership(PLAYER)).thenReturn(new ClaimResult.Claimed(5));
        HandlerReply reply = busy.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(response(reply).hasErrorMessage()).as("闸门已释放").isFalse();
    }

    @Test
    void 夺权时角色已不存在回2011() throws Exception {
        when(store.claimOwnership(PLAYER)).thenReturn(new ClaimResult.NotFound());
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        verify(takeovers, never()).request(anyLong(), anyLong());
        assertThat(claims("not_found")).isEqualTo(1);
    }

    @Test
    void 同一角色进场在途时后到者回2005_链结束后释放() throws Exception {
        CompletableFuture<AssignSceneResponse> pending = new CompletableFuture<>();
        when(scenes.assign(any())).thenReturn(pending);
        CompletableFuture<HandlerReply> first =
                handler.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());

        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        pending.complete(ASSIGNED);
        assertThat(response(first.join()).getPlayerId()).isEqualTo(PLAYER);

        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED));
        assertThat(response(enter(SESSION, PLAYER)).hasErrorMessage()).isFalse();
        verify(scenes, times(2)).assign(any());
    }

    @Test
    void 夺权故障_future异常完成且释放闸门() throws Exception {
        doThrow(new IllegalStateException("db down")).when(store).claimOwnership(PLAYER);
        assertThatThrownBy(() -> enter(SESSION, PLAYER)).isInstanceOf(CompletionException.class);
        assertThat(claims("error")).as("第一次夺权就抛出也记 error").isEqualTo(1);

        doReturn(new ClaimResult.Claimed(6)).when(store).claimOwnership(PLAYER);
        assertThat(enter(SESSION, PLAYER).directives().get(0).getEnterScene().getOwnerEpoch()).isEqualTo(6);
        assertThat(claims("claimed")).isEqualTo(1);
    }

    private double claims(String outcome) {
        return meters.get("xm.login.owner.claims").tag("outcome", outcome).timer().count();
    }

    private double assigns(String result) {
        return meters.get("xm.login.backend.calls").tag("backend", "scene-manager").tag("method", "assign")
                .tag("result", result).timer().count();
    }

    @Test
    void 设备数续期被拒_回拒绝码_不查角色不分配场景() throws Exception {
        EnterGameHandler limited = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
                Duration.ofMillis(200), CLAIM_WAIT, metrics, delay -> Runnable::run, nanos::get,
                session -> LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
        HandlerReply reply = limited.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build()).join();
        assertThat(response(reply).getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
        org.mockito.Mockito.verifyNoInteractions(scenes);
        verify(store, never()).findPlayer(org.mockito.ArgumentMatchers.anyLong());
    }
}
