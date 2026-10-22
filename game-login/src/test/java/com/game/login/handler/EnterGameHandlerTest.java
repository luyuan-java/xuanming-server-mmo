package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.zone.ZoneMergeFence;
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
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.Claimed(5));
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED));
    }

    private static EnterGameResponse response(HandlerReply reply) throws Exception {
        assertThat(reply.body()).isPresent();
        return EnterGameResponse.parseFrom(reply.body().get().toByteString());
    }

    @Test
    void 已冻结角色在入口拒绝_不分配场景不发起夺权() throws Exception {
        var row = LoginHandlerTest.row(PLAYER, ACCOUNT, "冻结角色");
        row.setTradeListingId(101);
        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(row));
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);
        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));
    }

    @Test
    void 分配回包迟到仍携带受理时账号修订_不能用后来读到的新修订接受旧链() throws Exception {
        var row = LoginHandlerTest.row(PLAYER, ACCOUNT, "原修订角色");
        row.setOwnerRevision(7);
        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(row));
        var allocation = new CompletableFuture<AssignSceneResponse>();
        when(scenes.assign(any())).thenReturn(allocation);
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(7L), any(Duration.class))).thenReturn(new ClaimResult.NotFound());
        var result = handler.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        row.setOwnerRevision(8);
        allocation.complete(ASSIGNED);
        assertError(result.orTimeout(5, TimeUnit.SECONDS).join(), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        verify(store).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(7L), any(Duration.class));
        verify(store, never()).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(8L), any(Duration.class));
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
                .setPlayerId(PLAYER).setSceneNodeId(4).setSceneId(99_000_001L).setOwnerEpoch(5).setOwnerRevision(1).build());

        ArgumentCaptor<AssignSceneRequest> request = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes).assign(request.capture());
        assertThat(request.getValue()).as("没有位置记录：不指定落点（默认主世界），不看存档里的地图")
                .isEqualTo(AssignSceneRequest.newBuilder().setZoneId(7).setPlayerId(PLAYER).build());

        assertThat(assigns("ok")).isEqualTo(1);
        assertThat(claims("claimed")).isEqualTo(1);
        assertThat(meters.get("game.login.owner.takeover.requests").counter().count()).isZero();
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
        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));
    }

    @Test
    void 归属仍被持有_请持有者让出后退避重试_释放后夺权成功() throws Exception {
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
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
        assertThat(meters.get("game.login.owner.claims").tag("outcome", "waited").timer().totalTime(TimeUnit.MILLISECONDS))
                .as("耗时取注入的单调时钟：两次各 150ms").isEqualTo(300);
        assertThat(meters.get("game.login.owner.takeover.requests").counter().count()).isEqualTo(2);
    }

    @Test
    void 归属在等待窗口内一直被持有_回2005_每次重试都请持有者让出() throws Exception {
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return new ClaimResult.Held(4);
        });

        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        verify(store, times(3)).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class));
        verify(takeovers, times(3)).request(PLAYER, 4);
        assertThat(backoffs).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(EnterGameHandler.MAX_CLAIM_BACKOFF));
        assertThat(claims("timeout")).isEqualTo(1);

        // 等待链结束后在途闸门已释放：下一次（持有者已释放）能进。
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.Claimed(5));
        assertThat(response(enter(SESSION, PLAYER)).hasErrorMessage()).isFalse();
    }

    @Test
    void 归属等待的截止不晚于进游戏的时间预算_分配慢时夺权少等() throws Exception {
        // 分配用了 2 s：claimWait（3 s）本可以等到第 5 s，可 gate 调 login 5 s 就放弃、迟到的应答被丢弃，
        // 那时夺到的归属没人接——所以夺权只等到时间预算（缺省 4 s）截止，回 2005 让客户端重试。
        assertThat(EnterGameHandler.DEFAULT_ENTER_BUDGET).isEqualTo(Duration.ofSeconds(4));
        long start = nanos.get();
        when(scenes.assign(any())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            return CompletableFuture.completedFuture(ASSIGNED);
        });
        List<Duration> attempts = new ArrayList<>();
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
            attempts.add(Duration.ofNanos(nanos.get() - start));
            nanos.addAndGet(Duration.ofMillis(500).toNanos());
            return new ClaimResult.Held(4);
        });

        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        assertThat(attempts).as("每次夺权都在预算之内发起，最后一次排在预算截止上").containsExactly(Duration.ofMillis(2000),
                Duration.ofMillis(2500), Duration.ofMillis(3000), Duration.ofMillis(3500));
        // 第 4 次夺权的结果在第 4 s（预算截止）才回来：不会再重试，也不再请持有者让出——gate 放行之后迟到的让出请求
        // 可能踢掉客户端重试进来的新会话。前 3 次结果回来时预算都还有剩余，照常请。
        verify(takeovers, times(3)).request(PLAYER, 4);
        assertThat(meters.get("game.login.owner.takeover.requests").counter().count()).isEqualTo(3);
        assertThat(claims("timeout")).isEqualTo(1);
    }

    /** 退避真的耗时：计时器按延迟（再加 {@code lag}，模拟计时器晚触发、回工作线程时排队）拨动单调时钟，再交回工作线程。 */
    private EnterGameHandler sleeping(List<Duration> delays, Duration lag) {
        return new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1, Duration.ofMillis(200), CLAIM_WAIT, metrics,
                delay -> {
                    delays.add(delay);
                    return task -> {
                        nanos.addAndGet(delay.plus(lag).toNanos());
                        task.run();
                    };
                }, nanos::get);
    }

    @Test
    void 下一次重试只能排在预算截止上_不再空等_当场回2005() throws Exception {
        // 分配用了 2 s，夺权等待截止取预算截止（第 4 s）。退避 100 / 200 / 400 / 800 ms 之后，第 3.5 s 那次仍被持有：
        // 下一次退避（800 ms）会被截到预算截止上，醒来时预算必然已用完、注定不夺权——不排这次重试，当场回 2005，
        // 不白等 500 ms（那段时间一直占着在途闸门和 gate 上这条会话的队列，回包离 gate 放行也更近）。
        List<Duration> delays = new ArrayList<>();
        EnterGameHandler sleeping = sleeping(delays, Duration.ZERO);
        long start = nanos.get();
        when(scenes.assign(any())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            return CompletableFuture.completedFuture(ASSIGNED);
        });
        List<Duration> attempts = new ArrayList<>();
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
            attempts.add(Duration.ofNanos(nanos.get() - start));
            return new ClaimResult.Held(4);
        });

        assertError(sleeping.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join(), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        assertThat(delays).as("没有截到预算截止上的那次退避").containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(400), Duration.ofMillis(800));
        assertThat(attempts).containsExactly(Duration.ofMillis(2000), Duration.ofMillis(2100), Duration.ofMillis(2300),
                Duration.ofMillis(2700), Duration.ofMillis(3500));
        assertThat(Duration.ofNanos(nanos.get() - start)).as("第 3.5 s 就回包，不等到预算截止").isEqualTo(Duration.ofMillis(3500));
        verify(takeovers, times(5)).request(PLAYER, 4);
        assertThat(claims("timeout")).isEqualTo(1);
    }

    @Test
    void 退避醒来晚于预算截止_不再夺权() throws Exception {
        // 每次退避醒来都比排定的晚 600 ms（计时器晚触发、回工作线程时排队）：第 3.5 s 那次之后排了 400 ms 的退避
        // （排定在预算截止之前），实际第 4.5 s 才醒——已过预算截止，gate 随时会放行同一会话的下一条请求，
        // 夺到的归属没人接，还可能晚于客户端的重试落库，所以不再夺权（醒来那一刻的检查）。
        List<Duration> delays = new ArrayList<>();
        EnterGameHandler sleeping = sleeping(delays, Duration.ofMillis(600));
        long start = nanos.get();
        when(scenes.assign(any())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            return CompletableFuture.completedFuture(ASSIGNED);
        });
        List<Duration> attempts = new ArrayList<>();
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
            attempts.add(Duration.ofNanos(nanos.get() - start));
            return new ClaimResult.Held(4);
        });

        assertError(sleeping.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join(), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        assertThat(delays).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
        assertThat(attempts).as("第 4.5 s 醒来的那次不再夺权").containsExactly(Duration.ofMillis(2000),
                Duration.ofMillis(2700), Duration.ofMillis(3500));
        verify(takeovers, times(3)).request(PLAYER, 4);
        assertThat(claims("timeout")).isEqualTo(1);
    }

    @Test
    void 工作队列排队已耗掉预算_不分配不夺权() throws Exception {
        // 过载时 login 工作队列积压：gate 的 5 s 从发出请求就开始走。时间预算按分发器受理的时刻算（含排队），
        // 处理器开始跑时预算已用完就不再调 scene-manager（按调用超时回 3023）、更不夺权。
        long budget = EnterGameHandler.DEFAULT_ENTER_BUDGET.toNanos();
        EnterGameRequest request = EnterGameRequest.newBuilder().setPlayerId(PLAYER).build();

        assertError(handler.handle(SESSION, request, budget).orTimeout(5, TimeUnit.SECONDS).join(),
                SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);
        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));

        // 对照：排队只耗掉预算减 1 ms，照常分配、夺权——排队时长是从预算里扣掉，不是「排过队就放弃」。
        HandlerReply reply = handler.handle(SESSION, request, budget - Duration.ofMillis(1).toNanos())
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(response(reply).hasErrorMessage()).isFalse();
        verify(store).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class));
    }

    @Test
    void 角色不存在或不属于本账号回2011() throws Exception {
        when(store.findPlayer(PLAYER)).thenReturn(Optional.empty());
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);

        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(LoginHandlerTest.row(PLAYER, "robot_other", "李四")));
        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));
    }

    @Test
    void 场景分配拒绝_tip原样返回且不夺权() throws Exception {
        int noScene = SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE;
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(
                AssignSceneResponse.newBuilder().setTipId(noScene).build()));

        assertError(enter(SESSION, PLAYER), noScene);
        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));
        assertThat(assigns("rejected")).isEqualTo(1);
        assertThat(meters.get("game.login.owner.claims").timers()).as("分配失败不走到夺权")
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

        verify(store, never()).claimOwnership(anyLong(), any(String.class), anyLong(), any(Duration.class));
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
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.Held(4));

        assertThatThrownBy(() -> busy.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(claims("error")).as("异常结束的夺权链记 error").isEqualTo(1);

        executions.set(0);
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.Claimed(5));
        HandlerReply reply = busy.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(response(reply).hasErrorMessage()).as("闸门已释放").isFalse();
    }

    @Test
    void 夺权时角色已不存在回2011() throws Exception {
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.NotFound());
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
        doThrow(new IllegalStateException("db down")).when(store).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class));
        assertThatThrownBy(() -> enter(SESSION, PLAYER)).isInstanceOf(CompletionException.class);
        assertThat(claims("error")).as("第一次夺权就抛出也记 error").isEqualTo(1);

        doReturn(new ClaimResult.Claimed(6)).when(store).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class));
        assertThat(enter(SESSION, PLAYER).directives().get(0).getEnterScene().getOwnerEpoch()).isEqualTo(6);
        assertThat(claims("claimed")).isEqualTo(1);
    }

    private double claims(String outcome) {
        return meters.get("game.login.owner.claims").tag("outcome", outcome).timer().count();
    }

    private double assigns(String result) {
        return meters.get("game.login.backend.calls").tag("backend", "scene-manager").tag("method", "assign")
                .tag("result", result).timer().count();
    }

    @Test
    void 设备数续期被拒_回拒绝码_不查角色不分配场景() throws Exception {
        EnterGameHandler limited = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
                Duration.ofMillis(200), CLAIM_WAIT, metrics, delay -> Runnable::run, nanos::get,
                session -> LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE, PlayerLocationLookup.NONE,
                ZoneMergeFence.OPEN);
        HandlerReply reply = limited.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build()).join();
        assertThat(response(reply).getErrorMessage().getId()).isEqualTo(LoginErrorTip.login_error.kLoginRedisSetFailed_VALUE);
        org.mockito.Mockito.verifyNoInteractions(scenes);
        verify(store, never()).findPlayer(org.mockito.ArgumentMatchers.anyLong());
    }
    private AssignSceneRequest assignWith(PlayerLocationLookup locations) {
        return assignWith(SESSION, locations);
    }

    private AssignSceneRequest assignWith(SessionContext entry, PlayerLocationLookup locations) {
        EnterGameHandler located = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
                Duration.ofMillis(200), CLAIM_WAIT, metrics, delay -> Runnable::run, nanos::get, session -> null,
                locations, ZoneMergeFence.OPEN);
        located.handle(entry, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build()).join();
        ArgumentCaptor<AssignSceneRequest> request = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes).assign(request.capture());
        return request.getValue();
    }

    private static PlayerLocation location(int zoneId) {
        return PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(zoneId).setSceneNodeId(4)
                .setSceneId(99_000_001L).setSceneConfigId(2).setOwnerEpoch(4).build();
    }

    @Test
    void 有本zone的位置记录_请scene_manager送回原场景实例() {
        AssignSceneRequest request = assignWith(id -> Optional.of(location(7)));
        assertThat(request).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(7).setPlayerId(PLAYER)
                .setPreferredSceneConfigId(2).setPreferredSceneNodeId(4).setPreferredSceneId(99_000_001L).build());
    }

    @Test
    void 位置记录在别的zone_连接持票钉在本zone_按首登不指定落点() {
        // 没钉住时是登录期重定向（GO-5），见 EnterGameZoneTravelTest；这里是持票来到本 zone 的连接：票据优先，不再弹走。
        SessionContext pinned = SESSION.toBuilder().setTicketPlayerId(PLAYER).setTicketTargetZoneId(7).build();
        AssignSceneRequest request = assignWith(pinned, id -> Optional.of(location(8)));
        assertThat(request).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(7).setPlayerId(PLAYER).build());
        verify(scenes, never()).redirectToZone(any());
    }

    @Test
    void 读位置记录出错_退化成按存档里的地图选() {
        AssignSceneRequest request = assignWith(id -> {
            throw new IllegalStateException("Redis 不可达");
        });
        assertThat(request).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(7).setPlayerId(PLAYER)
                .setPreferredSceneConfigId(3).build());
    }
    private HandlerReply enterWith(PlayerLocationLookup locations) {
        EnterGameHandler located = new EnterGameHandler(store, scenes, takeovers, Runnable::run, 1,
                Duration.ofMillis(200), CLAIM_WAIT, metrics, delay -> Runnable::run, nanos::get, session -> null,
                locations, ZoneMergeFence.OPEN);
        return located.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build()).join();
    }

    @Test
    void 夺权期间位置记录被登出墓碑取代_按首登重新分配_进场指令用新落点() {
        AssignSceneResponse home = AssignSceneResponse.newBuilder().setSceneNodeId(5).setSceneId(77L).setSceneConfigId(1)
                .build();
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED),
                CompletableFuture.completedFuture(home));
        AtomicInteger reads = new AtomicInteger();
        HandlerReply reply = enterWith(id -> reads.getAndIncrement() == 0 ? Optional.of(location(7)) : Optional.empty());

        ArgumentCaptor<AssignSceneRequest> requests = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes, times(2)).assign(requests.capture());
        assertThat(requests.getAllValues().get(1)).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(7)
                .setPlayerId(PLAYER).build());
        assertThat(reply.directives().get(0).getEnterScene().getSceneId()).isEqualTo(77L);
        assertThat(reply.directives().get(0).getEnterScene().getSceneNodeId()).isEqualTo(5);
    }

    @Test
    void 夺权后位置记录没变_不重新分配() {
        HandlerReply reply = enterWith(id -> Optional.of(location(7)));
        verify(scenes, times(1)).assign(any());
        assertThat(reply.directives().get(0).getEnterScene().getSceneId()).isEqualTo(99_000_001L);
    }

    @Test
    void 重新分配失败_沿用第一次的落点() {
        when(scenes.assign(any())).thenReturn(CompletableFuture.completedFuture(ASSIGNED),
                CompletableFuture.failedFuture(new IllegalStateException("scene-manager 不可达")));
        AtomicInteger reads = new AtomicInteger();
        HandlerReply reply = enterWith(id -> reads.getAndIncrement() == 0 ? Optional.of(location(7)) : Optional.empty());
        verify(scenes, times(2)).assign(any());
        assertThat(reply.directives().get(0).getEnterScene().getSceneId()).isEqualTo(99_000_001L);
    }

    // ---- 夺权事务的时限（mmorpg client-request-ordering.md §6.1）----

    @Test
    void 夺权事务的时限是受理时刻起算的剩余预算_重试时按当时剩下的() throws Exception {
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(300).toNanos());
            return new ClaimResult.Held(4);
        }).thenReturn(new ClaimResult.Claimed(5));

        HandlerReply reply = handler.handle(SESSION, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build(),
                Duration.ofSeconds(1).toNanos()).orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(response(reply).hasErrorMessage()).isFalse();
        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(store, times(2)).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), timeouts.capture());
        assertThat(timeouts.getAllValues()).as("预算 4 s 减排队 1 s；第二次再减第一次夺权的 300 ms（退避用同步执行器，不耗时钟）")
                .containsExactly(Duration.ofSeconds(3), Duration.ofMillis(2700));
    }

    @Test
    void 夺权事务没赶在预算截止前完成_已回滚_回2005_不请持有者让出_不再重试() throws Exception {
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.TimedOut());

        assertError(enter(SESSION, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        verify(store, times(1)).claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class));
        verify(takeovers, never()).request(anyLong(), anyLong());
        assertThat(backoffs).isEmpty();
        assertThat(claims("timeout")).isEqualTo(1);

        // 在途闸门已释放：下一次照常夺权。
        when(store.claimOwnership(eq(PLAYER), eq(ACCOUNT), eq(1L), any(Duration.class))).thenReturn(new ClaimResult.Claimed(6));
        assertThat(response(enter(SESSION, PLAYER)).hasErrorMessage()).isFalse();
    }
}
