package com.game.login.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.EnterScene;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ZoneRedirect;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.login.dispatch.HandlerReply;
import com.game.login.metrics.LoginMetrics;
import com.game.login.metrics.LoginMetrics.EnterRoute;
import com.game.login.ownership.OwnerTakeovers;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.proto.login.EnterGameRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * 批次 5.4 在 EnterGame 上加的东西（zone-travel-spec §5.8、§11.7）：持票者校验 2011 的位置与副作用、去向表经处理器落到
 * 分配请求上、登录期重定向（GO-5）与它的回落、签票回来后回到工作线程（§5.11）、回落链受进游戏时间预算约束（不超出 gate 调 login
 * 的等待）、合服围栏检查点 F2、第二条腿的夺权、夺权后复查位置的回归。
 * 去向表逐格的纯函数测试在 {@link EnterRoutesTest}；第二条腿对真实 SQL 的夺权在 {@link EnterGameSecondLegSqlTest}。
 */
class EnterGameZoneTravelTest {

    private static final String ACCOUNT = "robot_0001";
    private static final long PLAYER = 123456789L;
    private static final long OTHER = 987654321L;
    /** 入口 zone（这条连接所在 gate 的 zone）。 */
    private static final int G = 7;
    /** 别的 zone。 */
    private static final int H = 8;
    /** 角色的归属区（player.zone_id），故意不等于入口 zone：F2 判的是它。 */
    private static final int HOME = 1;

    /** 普通登录令牌进来的连接：Java 普通令牌 TP = 0、TZ = 签发 zone（X13）。 */
    private static final SessionContext NORMAL = SessionContext.newBuilder().setGateNodeId(2).setGateInstanceId("gate-z7")
            .setSessionId((2 << 17) | 9).setZoneId(G).setAccount(ACCOUNT).setTicketTargetZoneId(G).build();
    /** 持重定向票据来到本 zone 的连接（226 的第二条腿、GO-5 之后的那一跳）。 */
    private static final SessionContext PINNED = NORMAL.toBuilder().setTicketPlayerId(PLAYER).build();

    private static final AssignSceneResponse ASSIGNED = AssignSceneResponse.newBuilder()
            .setSceneNodeId(4).setSceneId(99_000_001L).setSceneConfigId(3).build();
    /** scene-manager 签好的重定向；票据字节故意不是合法的 GateTokenPayload：login 只原样转交、不解析。 */
    private static final ZoneRedirect REDIRECT = ZoneRedirect.newBuilder()
            .setTargetZoneId(H).setGateNodeId(3).setGateHost("10.0.8.1").setGatePort(11010)
            .setTokenPayload(ByteString.copyFrom(new byte[]{(byte) 0xff, 0x00, 0x08, 0x03, (byte) 0x80}))
            .setTokenSignature(ByteString.copyFromUtf8("0123456789abcdef".repeat(4)))
            .setTokenDeadline(1_800_000_300L)
            .build();
    private static final RedirectToZoneResponse REDIRECTED = RedirectToZoneResponse.newBuilder().setRedirect(REDIRECT).build();

    private static final Duration ASSIGN_TIMEOUT = Duration.ofMillis(200);
    private static final Duration CLAIM_WAIT = Duration.ofSeconds(3);
    /** 进游戏的时间预算（同 game.login.enter-game-budget 的缺省；gate 调 login 5 s 超时）。 */
    private static final Duration BUDGET = Duration.ofSeconds(4);

    private final PlayerStore store = mock(PlayerStore.class);
    private final SceneDirectoryService scenes = mock(SceneDirectoryService.class);
    private final OwnerTakeovers takeovers = mock(OwnerTakeovers.class);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoginMetrics metrics = new LoginMetrics(meters);
    /** 按发生次序记下：设备续期 {@code renew}、读位置 {@code find}、问围栏 {@code fence:<zone>}、分配 {@code assign:<zone>}、签票 {@code redirect:<zone>}。 */
    private final List<String> events = new ArrayList<>();
    private final FakeFence fence = new FakeFence();
    /** 位置记录的来源（按读的次序，第 n 次读用第 min(n, 最后) 个）。 */
    private List<Supplier<Optional<PlayerLocation>>> locationReads = List.of(Optional::empty);
    private final AtomicInteger reads = new AtomicInteger();

    private final EnterGameHandler handler = handler(Runnable::run, ASSIGN_TIMEOUT);

    /** 退避用同步执行器、单调时钟手动拨动（只在桩里拨）；工作线程池与分配兜底可换。 */
    private EnterGameHandler handler(Executor workers, Duration assignTimeout) {
        return new EnterGameHandler(store, scenes, takeovers, workers, 1, assignTimeout, CLAIM_WAIT, metrics,
                delay -> Runnable::run, nanos::get,
                session -> {
                    events.add("renew");
                    return null;
                },
                playerId -> {
                    events.add("find");
                    int n = reads.getAndIncrement();
                    return locationReads.get(Math.min(n, locationReads.size() - 1)).get();
                },
                fence, BUDGET);
    }

    @BeforeEach
    void setUp() {
        PlayerRow row = LoginHandlerTest.row(PLAYER, ACCOUNT, "张三");
        row.setZoneId(HOME);
        row.setSceneConfigId(3);
        when(store.findPlayer(PLAYER)).thenReturn(Optional.of(row));
        when(store.claimOwnership(eq(PLAYER), any(Duration.class))).thenReturn(new ClaimResult.Claimed(5));
        when(scenes.assign(any())).thenAnswer(inv -> {
            events.add("assign:" + inv.<AssignSceneRequest>getArgument(0).getZoneId());
            return CompletableFuture.completedFuture(ASSIGNED);
        });
        when(scenes.redirectToZone(any())).thenAnswer(inv -> {
            events.add("redirect:" + inv.<RedirectToZoneRequest>getArgument(0).getToZoneId());
            return CompletableFuture.completedFuture(REDIRECTED);
        });
    }

    /** 假围栏：记下被问到的 zone，可脚本化「合服中」与「读不到」。进游戏在 login 工作线程上同步判，异步版调到即失败。 */
    private final class FakeFence implements ZoneMergeFence {

        private boolean merging;
        private Exception unreadable;

        @Override
        public boolean inProgress(int zoneId) throws Exception {
            events.add("fence:" + Integer.toUnsignedString(zoneId));
            if (unreadable != null) {
                throw unreadable;
            }
            return merging;
        }

        @Override
        public CompletionStage<Boolean> inProgressAsync(int zoneId) {
            throw new AssertionError("进游戏应同步判围栏 zone=" + Integer.toUnsignedString(zoneId));
        }
    }

    @SafeVarargs
    private void locations(Supplier<Optional<PlayerLocation>>... sequence) {
        locationReads = List.of(sequence);
    }

    private static Supplier<Optional<PlayerLocation>> record(int zone, int node, long scene, int conf) {
        return () -> Optional.of(PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(zone).setSceneNodeId(node)
                .setSceneId(scene).setSceneConfigId(conf).setOwnerEpoch(4).build());
    }

    private static Supplier<Optional<PlayerLocation>> none() {
        return Optional::empty;
    }

    private HandlerReply enter(SessionContext session, long playerId) {
        return handler.handle(session, EnterGameRequest.newBuilder().setPlayerId(playerId).build())
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static EnterGameResponse response(HandlerReply reply) throws Exception {
        assertThat(reply.body()).isPresent();
        return EnterGameResponse.parseFrom(reply.body().get().toByteString());
    }

    private static void assertError(HandlerReply reply, int tipId) throws Exception {
        EnterGameResponse response = response(reply);
        assertThat(response.getErrorMessage().getId()).isEqualTo(tipId);
        assertThat(response.getPlayerId()).isZero();
        assertThat(reply.directives()).isEmpty();
    }

    private static void assertEntered(HandlerReply reply, long epoch) throws Exception {
        EnterGameResponse response = response(reply);
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getPlayerId()).isEqualTo(PLAYER);
        assertThat(reply.directives()).containsExactly(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                .setPlayerId(PLAYER).setSceneNodeId(4).setSceneId(99_000_001L).setOwnerEpoch(epoch)).build());
    }

    private List<AssignSceneRequest> assignRequests(int times) {
        ArgumentCaptor<AssignSceneRequest> captor = ArgumentCaptor.forClass(AssignSceneRequest.class);
        verify(scenes, times(times)).assign(captor.capture());
        return captor.getAllValues();
    }

    private double route(EnterRoute route) {
        return meters.get("game.login.enter.routes").tag("route", route.name().toLowerCase(Locale.ROOT)).counter().count();
    }

    /** 这一次进游戏恰好计了这一个去向，其余标签都是 0。 */
    private void assertOnlyRoute(EnterRoute expected) {
        for (EnterRoute route : EnterRoute.values()) {
            assertThat(route(route)).as("route=%s", route).isEqualTo(route == expected ? 1 : 0);
        }
    }

    private void assertNoRoute() {
        for (EnterRoute route : EnterRoute.values()) {
            assertThat(route(route)).as("route=%s", route).isZero();
        }
    }

    private double ticketRejected() {
        return meters.get("game.login.ticket.holder.rejected").counter().count();
    }

    private double redirectCalls(String result) {
        return meters.get("game.login.backend.calls").tag("backend", "scene-manager").tag("method", "redirectToZone")
                .tag("result", result).timer().count();
    }

    private double claims(String outcome) {
        return meters.get("game.login.owner.claims").tag("outcome", outcome).timer().count();
    }

    private double assigns(String result) {
        return meters.get("game.login.backend.calls").tag("backend", "scene-manager").tag("method", "assign")
                .tag("result", result).timer().count();
    }

    private static long ms(long millis) {
        return Duration.ofMillis(millis).toNanos();
    }

    /** 签票挂起：返回的 future 由测试来完成（相当于 Dubbo 的回调线程）。 */
    private CompletableFuture<RedirectToZoneResponse> redirectPending() {
        CompletableFuture<RedirectToZoneResponse> pending = new CompletableFuture<>();
        doAnswer(inv -> {
            events.add("redirect:" + inv.<RedirectToZoneRequest>getArgument(0).getToZoneId());
            return pending;
        }).when(scenes).redirectToZone(any());
        return pending;
    }

    /**
     * 手动泵的 login 工作线程池：投递的任务先排队，测试逐个执行——断言哪些事发生在「回到工作线程」之后，而不是在完成
     * scene-manager 应答的回调线程上内联执行。{@code full} 时拒绝投递（工作队列满）。
     */
    private static final class ManualWorkers implements Executor {

        private final Deque<Runnable> queued = new ArrayDeque<>();
        private boolean full;

        @Override
        public void execute(Runnable task) {
            if (full) {
                throw new RejectedExecutionException("login 工作队列已满");
            }
            queued.add(task);
        }

        /** 执行排在最前的一个任务（它投递的新任务排到队尾）。 */
        void runNext() {
            Runnable task = queued.poll();
            assertThat(task).as("工作线程池里应有待执行的任务").isNotNull();
            task.run();
        }

        int pending() {
            return queued.size();
        }
    }

    // ================================================================ 持票者校验（2011）

    @Test
    void 持票者不符回2011_排在2028之前_不续设备不读位置不问围栏不分配不夺权() throws Exception {
        SessionContext wrongHolder = NORMAL.toBuilder().setTicketPlayerId(OTHER).build();
        locations(record(H, 4, 99_000_001L, 2));

        // 正常登录了的会话；没登录的会话（本该 2028）；已绑定玩家的会话（本该 2028）：持票者不符一律先回 2011。
        assertError(enter(wrongHolder, PLAYER), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        assertError(enter(wrongHolder.toBuilder().clearAccount().build(), PLAYER),
                LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        assertError(enter(wrongHolder.toBuilder().setPlayerId(PLAYER).build(), PLAYER),
                LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        // 请求里没带角色号（0）而连接持票：同样不符。
        assertError(enter(PINNED, 0), LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);

        assertThat(events).as("不续设备、不读位置、不问围栏、不分配、不签票").isEmpty();
        verify(store, never()).findPlayer(anyLong());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        verify(scenes, never()).assign(any());
        verify(scenes, never()).redirectToZone(any());
        verifyNoInteractions(takeovers);
        assertThat(ticketRejected()).isEqualTo(4);
        assertNoRoute();
    }

    @Test
    void 持票者不符不占在途闸门_同一角色进场在途时回2011而不是2005_在途的那一次照常完成() throws Exception {
        CompletableFuture<AssignSceneResponse> pending = new CompletableFuture<>();
        doReturn(pending).when(scenes).assign(any());
        CompletableFuture<HandlerReply> first =
                handler.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        assertThat(first).isNotDone();

        // 同一角色正占着在途闸门：持票者不符的请求不去碰闸门，所以回的是 2011 而不是 2005。
        assertError(enter(NORMAL.toBuilder().setTicketPlayerId(OTHER).build(), PLAYER),
                LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE);
        // 对照：持票者相符（或普通令牌）的并发请求撞闸门回 2005。
        assertError(enter(PINNED, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        pending.complete(ASSIGNED);
        assertEntered(first.get(5, TimeUnit.SECONDS), 5);
        assertThat(events).as("只有在途的那一次与撞 2005 的那一次续过设备").containsExactly("renew", "find", "fence:1", "renew");
        assertThat(ticketRejected()).isEqualTo(1);
    }

    @Test
    void 持票者相符_照常进游戏_不计持票者不符() throws Exception {
        assertEntered(enter(PINNED, PLAYER), 5);
        assertThat(ticketRejected()).isZero();
        assertOnlyRoute(EnterRoute.FIRST);
        assertThat(events).containsExactly("renew", "find", "fence:1", "assign:7");
    }

    @Test
    void 票据的目标zone不是本zone但持票者相符_过2011_按未钉处理() throws Exception {
        // gate 只认本 zone 的票据，这种会话不该出现；出现了也只当普通登录：角色在 H 的节点上 → GO-5。
        locations(record(H, 4, 99_000_001L, 2));
        HandlerReply reply = enter(NORMAL.toBuilder().setTicketPlayerId(PLAYER).setTicketTargetZoneId(H).build(), PLAYER);

        assertThat(reply.directives()).extracting(SessionDirective::hasRedirectToGate).containsExactly(true);
        assertThat(ticketRejected()).isZero();
        assertOnlyRoute(EnterRoute.REDIRECT);
    }

    // ================================================================ 去向经处理器落到分配请求上

    @Test
    void 第二条腿_持票钉住且本zone有待落点_按记录的地图落_一次夺权成功_不复查位置() throws Exception {
        // 源节点「交出并释放」之后库里 owner_released = 1：目标 zone 的夺权第一次就成功（E+2），不发让出请求。
        locations(record(G, 0, 0, 5));
        when(store.claimOwnership(eq(PLAYER), any(Duration.class))).thenReturn(new ClaimResult.Claimed(6));

        assertEntered(enter(PINNED, PLAYER), 6);

        assertThat(assignRequests(1)).containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER)
                .setPreferredSceneConfigId(5).build());
        verify(store, times(1)).claimOwnership(eq(PLAYER), any(Duration.class));
        verifyNoInteractions(takeovers);
        assertThat(claims("claimed")).isEqualTo(1);
        assertThat(meters.get("game.login.owner.takeover.requests").counter().count()).isZero();
        assertOnlyRoute(EnterRoute.AWAITING);
        assertThat(events).as("待落点不带实例，夺权后不复查").containsExactly("renew", "find", "fence:1", "assign:7");
        verify(scenes, never()).redirectToZone(any());
    }

    @Test
    void 普通令牌从目标zone进来_本zone的待落点照样按记录的地图落() throws Exception {
        // R7：124 之后客户端没跟随，自己从 B 的入口用普通令牌登录：同基线 zone 对上即采用 pending 地图。
        locations(record(G, 0, 0, 5));

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(assignRequests(1).get(0).getPreferredSceneConfigId()).isEqualTo(5);
        assertOnlyRoute(EnterRoute.AWAITING);
    }

    @Test
    void 别处的待落点不牵引_按本zone首登_不签票() throws Exception {
        // R6：124 已发、没落地，从源 zone 的入口重登：这次传送作废，落入口 zone 默认主世界。
        locations(record(H, 0, 0, 5));

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(assignRequests(1)).containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER).build());
        verify(scenes, never()).redirectToZone(any());
        assertOnlyRoute(EnterRoute.FIRST);
    }

    @Test
    void 持票钉住而记录在别的zone的节点上_不再弹走_按本zone首登() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));

        assertEntered(enter(PINNED, PLAYER), 5);

        assertThat(assignRequests(1)).containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER).build());
        verify(scenes, never()).redirectToZone(any());
        assertOnlyRoute(EnterRoute.PINNED);
    }

    @Test
    void 本zone的在线或租约记录_回原实例() throws Exception {
        locations(record(G, 4, 99_000_001L, 2));

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(assignRequests(1)).containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER)
                .setPreferredSceneConfigId(2).setPreferredSceneNodeId(4).setPreferredSceneId(99_000_001L).build());
        assertOnlyRoute(EnterRoute.RESUME);
    }

    @Test
    void 读位置失败_按存档地图选_计location_error() throws Exception {
        locations(() -> {
            throw new IllegalStateException("Redis 不可达");
        });

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(assignRequests(1)).containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER)
                .setPreferredSceneConfigId(3).build());
        assertOnlyRoute(EnterRoute.LOCATION_ERROR);
    }

    // ================================================================ 登录期重定向（GO-5）

    @Test
    void 角色在别的zone的节点上且没钉住_回成功应答加重定向指令_不分配不夺权不让出_不复查位置() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));

        HandlerReply reply = enter(NORMAL, PLAYER);

        EnterGameResponse response = response(reply);
        assertThat(response.hasErrorMessage()).as("同基线：重定向是 EnterGame 的成功出口").isFalse();
        assertThat(response).isEqualTo(EnterGameResponse.newBuilder().setPlayerId(PLAYER).build());
        assertThat(reply.directives()).hasSize(1);
        SessionDirective directive = reply.directives().get(0);
        assertThat(directive.hasEnterScene()).isFalse();
        assertThat(directive.getRedirectToGate().getRedirect()).isEqualTo(REDIRECT);
        assertThat(directive.getRedirectToGate().getRedirect().getTokenPayload().toByteArray())
                .as("票据原字节原样转交").containsExactly(0xff, 0x00, 0x08, 0x03, 0x80);

        ArgumentCaptor<RedirectToZoneRequest> request = ArgumentCaptor.forClass(RedirectToZoneRequest.class);
        verify(scenes).redirectToZone(request.capture());
        assertThat(request.getValue()).isEqualTo(RedirectToZoneRequest.newBuilder()
                .setFromZoneId(G).setToZoneId(H).setPlayerId(PLAYER).build());

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        verifyNoInteractions(takeovers);
        assertThat(events).as("只读一次位置；重定向路径不问合服围栏（F3）").containsExactly("renew", "find", "redirect:8");

        assertOnlyRoute(EnterRoute.REDIRECT);
        assertThat(redirectCalls("ok")).isEqualTo(1);
        assertThat(meters.get("game.login.owner.claims").timers()).as("不走到夺权")
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

    @Test
    void 签票在途时同一角色的进场回2005_签完释放闸门() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        CompletableFuture<RedirectToZoneResponse> pending = new CompletableFuture<>();
        doReturn(pending).when(scenes).redirectToZone(any());
        CompletableFuture<HandlerReply> first =
                handler.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());

        assertError(enter(NORMAL, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        pending.complete(REDIRECTED);
        assertThat(first.get(5, TimeUnit.SECONDS).directives().get(0).hasRedirectToGate()).isTrue();

        doReturn(CompletableFuture.completedFuture(REDIRECTED)).when(scenes).redirectToZone(any());
        assertThat(enter(NORMAL, PLAYER).directives().get(0).hasRedirectToGate()).as("闸门已释放").isTrue();
    }

    private static ZoneRedirect.Builder redirect() {
        return REDIRECT.toBuilder();
    }

    private static Supplier<CompletableFuture<RedirectToZoneResponse>> answer(RedirectToZoneResponse response) {
        return () -> CompletableFuture.completedFuture(response);
    }

    private static RedirectToZoneResponse with(ZoneRedirect.Builder redirect) {
        return RedirectToZoneResponse.newBuilder().setRedirect(redirect).build();
    }

    /** 签票没成的各种样子 → 期望的调用结果标签（rejected = scene-manager 回了 tip，其余都按调用失败）。 */
    static Stream<Arguments> redirectFailures() {
        return Stream.of(
                Arguments.of("调用失败", (Supplier<CompletableFuture<RedirectToZoneResponse>>)
                        () -> CompletableFuture.failedFuture(new IllegalStateException("scene-manager 不可达")), "error"),
                Arguments.of("同步抛出", (Supplier<CompletableFuture<RedirectToZoneResponse>>) () -> {
                    throw new IllegalStateException("no provider");
                }, "error"),
                Arguments.of("返回null的future", (Supplier<CompletableFuture<RedirectToZoneResponse>>) () -> null, "error"),
                Arguments.of("应答为null", answer(null), "error"),
                Arguments.of("拒绝_目标zone没有可用的scene节点", answer(RedirectToZoneResponse.newBuilder()
                        .setTipId(SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE).build()), "rejected"),
                Arguments.of("拒绝_带着redirect也不用", answer(REDIRECTED.toBuilder().setTipId(3005).build()), "rejected"),
                Arguments.of("tip为0却没有redirect", answer(RedirectToZoneResponse.getDefaultInstance()), "error"),
                Arguments.of("redirect的目标zone不是请求的", answer(with(redirect().setTargetZoneId(9))), "error"),
                Arguments.of("gate地址为空", answer(with(redirect().setGateHost(""))), "error"),
                Arguments.of("端口为0", answer(with(redirect().setGatePort(0))), "error"),
                Arguments.of("端口越界", answer(with(redirect().setGatePort(65536))), "error"),
                Arguments.of("端口是uint32的大值", answer(with(redirect().setGatePort(-1))), "error"),
                Arguments.of("票据为空", answer(with(redirect().setTokenPayload(ByteString.EMPTY))), "error"),
                Arguments.of("签名为空", answer(with(redirect().setTokenSignature(ByteString.EMPTY))), "error"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectFailures")
    void 签票没成_退化为入口zone首登_过围栏_分配_夺权_计redirect_fallback(
            String name, Supplier<CompletableFuture<RedirectToZoneResponse>> outcome, String callResult) throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        doAnswer(inv -> {
            events.add("redirect:" + inv.<RedirectToZoneRequest>getArgument(0).getToZoneId());
            return outcome.get();
        }).when(scenes).redirectToZone(any());

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(assignRequests(1)).as("回落是本 zone 首登：不带别的 zone 记录里的任何东西")
                .containsExactly(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER).build());
        assertThat(events).containsExactly("renew", "find", "redirect:8", "fence:1", "assign:7");
        verify(store, times(1)).claimOwnership(eq(PLAYER), any(Duration.class));
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
        assertThat(redirectCalls(callResult)).isEqualTo(1);
        assertThat(redirectCalls("ok")).isZero();
    }

    @Test
    void 签票超时_按本地兜底到点回落_不去完成Dubbo给的原future() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        CompletableFuture<RedirectToZoneResponse> never = new CompletableFuture<>();
        doReturn(never).when(scenes).redirectToZone(any());

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(never).isNotDone();
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
        assertThat(redirectCalls("error")).isEqualTo(1);
    }

    @Test
    void 签票没成后回落的落地路径同样过合服围栏_命中回3023() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("down"))).when(scenes).redirectToZone(any());
        fence.merging = true;

        assertError(enter(NORMAL, PLAYER), SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
    }

    @Test
    void 重定向路径不问合服围栏_围栏封锁着也照样送连接() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        fence.merging = true;

        assertThat(enter(NORMAL, PLAYER).directives().get(0).getRedirectToGate().getRedirect()).isEqualTo(REDIRECT);
        assertThat(events).doesNotContain("fence:1");
    }

    // ================================================================ 签票回来后回到工作线程（zone-travel-spec §5.11）

    @Test
    void 签票没成_回落的围栏分配与夺权都回到工作线程上执行_不在签票的回调线程上() throws Exception {
        ManualWorkers workers = new ManualWorkers();
        EnterGameHandler onWorkers = handler(workers, ASSIGN_TIMEOUT);
        locations(record(H, 4, 99_000_001L, 2));
        CompletableFuture<RedirectToZoneResponse> pending = redirectPending();

        CompletableFuture<HandlerReply> result =
                onWorkers.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        // 签票失败由 Dubbo 的回调线程完成（这里是测试线程）：它只往工作线程池投递，自己不问围栏（7.3 起读 Redis）、不夺权（MySQL）。
        pending.completeExceptionally(new IllegalStateException("scene-manager 不可达"));

        assertThat(result).isNotDone();
        assertThat(events).containsExactly("renew", "find", "redirect:8");
        assertThat(workers.pending()).isEqualTo(1);
        assertNoRoute();

        // 工作线程：定结局（计回落）→ 围栏 → 发起分配；分配的应答再投递回工作线程。
        workers.runNext();
        assertThat(events).containsExactly("renew", "find", "redirect:8", "fence:1", "assign:7");
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        assertThat(result).isNotDone();

        // 工作线程：夺权 → 应答。
        workers.runNext();
        verify(store, times(1)).claimOwnership(eq(PLAYER), any(Duration.class));
        assertEntered(result.get(5, TimeUnit.SECONDS), 5);
        assertThat(workers.pending()).isZero();
    }

    @Test
    void 签票成功_同样回到工作线程才收口() throws Exception {
        ManualWorkers workers = new ManualWorkers();
        EnterGameHandler onWorkers = handler(workers, ASSIGN_TIMEOUT);
        locations(record(H, 4, 99_000_001L, 2));
        CompletableFuture<RedirectToZoneResponse> pending = redirectPending();

        CompletableFuture<HandlerReply> result =
                onWorkers.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        pending.complete(REDIRECTED);

        assertThat(result).isNotDone();
        assertNoRoute();
        assertThat(redirectCalls("ok")).isZero();

        workers.runNext();
        assertThat(result.get(5, TimeUnit.SECONDS).directives().get(0).getRedirectToGate().getRedirect())
                .isEqualTo(REDIRECT);
        assertOnlyRoute(EnterRoute.REDIRECT);
        assertThat(redirectCalls("ok")).isEqualTo(1);
    }

    @Test
    void 签票回来时工作队列已满_链以异常结束_不计去向不分配不夺权_在途闸门释放() throws Exception {
        ManualWorkers workers = new ManualWorkers();
        EnterGameHandler onWorkers = handler(workers, ASSIGN_TIMEOUT);
        locations(record(H, 4, 99_000_001L, 2));
        CompletableFuture<RedirectToZoneResponse> pending = redirectPending();

        CompletableFuture<HandlerReply> result =
                onWorkers.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        workers.full = true;
        // 签票失败（本该回落），但回工作线程的那一跳被拒：链当场以异常结束（派发层回 1003），不在回调线程上兜着做回落。
        pending.completeExceptionally(new IllegalStateException("scene-manager 不可达"));

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertNoRoute();
        assertThat(redirectCalls("ok") + redirectCalls("rejected") + redirectCalls("error")).as("没走到定结局").isZero();
        assertThat(events).containsExactly("renew", "find", "redirect:8");
        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));

        // 在途闸门已释放：同一角色下一次进游戏不回 2005。
        workers.full = false;
        doReturn(CompletableFuture.completedFuture(REDIRECTED)).when(scenes).redirectToZone(any());
        CompletableFuture<HandlerReply> again =
                onWorkers.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());
        workers.runNext();
        assertThat(again.get(5, TimeUnit.SECONDS).directives().get(0).hasRedirectToGate()).isTrue();
        assertOnlyRoute(EnterRoute.REDIRECT);
    }

    // ================================================================ 时间预算：回落链不超出 gate 调 login 的等待

    @Test
    void 签票耗掉大半预算后回落_夺权只等到预算截止_不按claimWait等满() throws Exception {
        // scene-manager 变慢：签票 3 s 才失败，回落的分配 0.5 s 成功，夺权撞上角色在 H 的在线实例（Held）。
        // claimWait（3 s）本可以等到第 6.5 s，可 gate 调 login 5 s 就放弃、迟到的应答被丢弃，那时夺到的归属没人接，
        // 玩家要等租约 30 s 过期——所以夺权只等到预算截止（4 s），回 2005 让客户端重试。
        locations(record(H, 4, 99_000_001L, 2));
        long start = nanos.get();
        doAnswer(inv -> {
            nanos.addAndGet(ms(3000));
            return CompletableFuture.failedFuture(new IllegalStateException("签票超时"));
        }).when(scenes).redirectToZone(any());
        doAnswer(inv -> {
            nanos.addAndGet(ms(500));
            return CompletableFuture.completedFuture(ASSIGNED);
        }).when(scenes).assign(any());
        List<Long> attempts = new ArrayList<>();
        when(store.claimOwnership(eq(PLAYER), any(Duration.class))).thenAnswer(inv -> {
            attempts.add(nanos.get() - start);
            nanos.addAndGet(ms(300));
            return new ClaimResult.Held(4);
        });

        assertError(enter(NORMAL, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        assertThat(attempts).as("每次夺权都在预算之内发起").containsExactly(ms(3500), ms(3800));
        // 第 2 次夺权的结果在第 4.1 s 才回来、已过预算截止：不再请持有者让出（gate 放行之后迟到的让出请求可能踢掉客户端重试进来的新会话）。
        verify(takeovers, times(1)).request(PLAYER, 4);
        assertThat(claims("timeout")).isEqualTo(1);
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
    }

    @Test
    void 分配回来时预算已用完_不夺权不请持有者让出_回2005() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        doAnswer(inv -> {
            nanos.addAndGet(ms(3000));
            return CompletableFuture.failedFuture(new IllegalStateException("签票超时"));
        }).when(scenes).redirectToZone(any());
        doAnswer(inv -> {
            nanos.addAndGet(ms(1000));
            return CompletableFuture.completedFuture(ASSIGNED);
        }).when(scenes).assign(any());

        assertError(enter(NORMAL, PLAYER), LoginErrorTip.login_error.kLoginInProgress_VALUE);

        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        verifyNoInteractions(takeovers);
        assertThat(assigns("ok")).isEqualTo(1);
        assertThat(claims("timeout")).as("没发起夺权也记一次等不到").isEqualTo(1);
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
    }

    @Test
    void 签票把预算用完_回落不再调scene_manager分配_回3023() throws Exception {
        locations(record(H, 4, 99_000_001L, 2));
        doAnswer(inv -> {
            events.add("redirect:" + inv.<RedirectToZoneRequest>getArgument(0).getToZoneId());
            nanos.addAndGet(BUDGET.toNanos());
            return CompletableFuture.failedFuture(new IllegalStateException("签票超时"));
        }).when(scenes).redirectToZone(any());

        assertError(enter(NORMAL, PLAYER), SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);

        assertThat(events).as("回落照样过围栏，但不再发起分配").containsExactly("renew", "find", "redirect:8", "fence:1");
        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        assertThat(assigns("error")).as("按调用超时计").isEqualTo(1);
        assertOnlyRoute(EnterRoute.REDIRECT_FALLBACK);
    }

    @Test
    void 回落的分配按剩余预算收紧本地兜底_不按scene_assign_timeout等满() throws Exception {
        // 分配兜底配成 1 h：要是没按剩余预算（50 ms）收紧，下面等 5 s 就会超时。
        EnterGameHandler patient = handler(Runnable::run, Duration.ofHours(1));
        locations(record(H, 4, 99_000_001L, 2));
        doAnswer(inv -> {
            nanos.addAndGet(BUDGET.minusMillis(50).toNanos());
            return CompletableFuture.failedFuture(new IllegalStateException("签票超时"));
        }).when(scenes).redirectToZone(any());
        CompletableFuture<AssignSceneResponse> never = new CompletableFuture<>();
        doReturn(never).when(scenes).assign(any());

        HandlerReply reply = patient.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                .orTimeout(5, TimeUnit.SECONDS).join();

        assertError(reply, SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);
        assertThat(never).isNotDone();
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        assertThat(assigns("error")).isEqualTo(1);
    }

    @Test
    void 夺到归属时预算已用完_不再复查位置_沿用原落点() throws Exception {
        // 回原实例这一种去向本该在夺权后复查位置（第二次读到的别 zone 记录会触发本 zone 重新分配）；
        // 预算已用完时归属已经在手，应答得赶在 gate 放弃之前回去：不复查、不重新分配。
        locations(record(G, 4, 99_000_001L, 2), record(H, 9, 55L, 6));
        doAnswer(inv -> {
            nanos.addAndGet(ms(3900));
            return CompletableFuture.completedFuture(ASSIGNED);
        }).when(scenes).assign(any());
        when(store.claimOwnership(eq(PLAYER), any(Duration.class))).thenAnswer(inv -> {
            nanos.addAndGet(ms(200));
            return new ClaimResult.Claimed(5);
        });

        assertEntered(enter(NORMAL, PLAYER), 5);

        assertThat(reads.get()).as("只在定去向时读了一次").isEqualTo(1);
        assertThat(assignRequests(1).get(0).getPreferredSceneId()).isEqualTo(99_000_001L);
        assertOnlyRoute(EnterRoute.RESUME);
    }

    // ================================================================ 合服围栏检查点 F2

    @Test
    void 合服围栏在分配之前被问一次_判的是角色的归属区而不是入口zone() throws Exception {
        assertEntered(enter(NORMAL, PLAYER), 5);
        assertThat(events).containsExactly("renew", "find", "fence:1", "assign:7");
    }

    @Test
    void 合服围栏命中回3023_不分配不夺权_撤掉后同一处理器照常进() throws Exception {
        fence.merging = true;

        assertError(enter(NORMAL, PLAYER), SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
        verifyNoInteractions(takeovers);
        assertOnlyRoute(EnterRoute.FIRST);

        fence.merging = false;
        assertEntered(enter(NORMAL, PLAYER), 5);
    }

    @Test
    void 合服围栏读不到按封锁处理回3023() throws Exception {
        fence.unreadable = new IOException("redis 超时");

        assertError(enter(NORMAL, PLAYER), SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);

        verify(scenes, never()).assign(any());
        verify(store, never()).claimOwnership(anyLong(), any(Duration.class));
    }

    @Test
    void 合服围栏被中断按封锁处理回3023_保留线程的中断标记() throws Exception {
        fence.unreadable = new InterruptedException("工作线程池正在关停");
        try {
            HandlerReply reply = handler.handle(NORMAL, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build())
                    .getNow(null);
            assertThat(reply).as("同步拒绝，链当场完成").isNotNull();
            assertError(reply, SceneErrorTip.scene_error.kEnterSceneFailed_VALUE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(scenes, never()).assign(any());
    }

    // ================================================================ 夺权后复查位置（rerouteIfStale）的回归

    @Test
    void 夺权后位置记录到了别的zone的节点上_不再重定向_只在本zone按首登重新分配() throws Exception {
        // 回原实例这一种去向在夺权后复查位置；复查只认入口 zone 的记录：别的 zone 的记录当作没有——已持有归属，不签票、
        // 不把人送走，按「记录没了」在本 zone 首登重新分配（与登出墓碑同一条路，zone-travel-spec §5.8 第 5 步「不改」）。
        locations(record(G, 4, 99_000_001L, 2), record(H, 9, 55L, 6));
        AssignSceneResponse home = AssignSceneResponse.newBuilder().setSceneNodeId(5).setSceneId(77L).setSceneConfigId(1).build();
        doReturn(CompletableFuture.completedFuture(ASSIGNED), CompletableFuture.completedFuture(home))
                .when(scenes).assign(any());

        HandlerReply reply = enter(NORMAL, PLAYER);

        List<AssignSceneRequest> requests = assignRequests(2);
        assertThat(requests).allSatisfy(request -> assertThat(request.getZoneId()).isEqualTo(G));
        assertThat(requests.get(1)).isEqualTo(AssignSceneRequest.newBuilder().setZoneId(G).setPlayerId(PLAYER).build());
        assertThat(reply.directives().get(0).getEnterScene().getSceneId()).isEqualTo(77L);
        verify(scenes, never()).redirectToZone(any());
        verify(store, times(1)).claimOwnership(eq(PLAYER), any(Duration.class));
        assertOnlyRoute(EnterRoute.RESUME);
    }

    @Test
    void 夺权后位置记录成了别处的待落点_同样不重定向() throws Exception {
        locations(record(G, 4, 99_000_001L, 2), record(H, 0, 0, 6));

        HandlerReply reply = enter(NORMAL, PLAYER);

        assertThat(assignRequests(2)).extracting(AssignSceneRequest::getZoneId).containsOnly(G);
        assertThat(reply.directives().get(0).hasEnterScene()).isTrue();
        verify(scenes, never()).redirectToZone(any());
    }

    @Test
    void 待落点与票据钉住两种去向不带实例_夺权后不复查位置() throws Exception {
        locations(record(G, 0, 0, 5), record(H, 9, 55L, 6));
        enter(PINNED, PLAYER);
        assertThat(reads.get()).isEqualTo(1);

        reads.set(0);
        locations(record(H, 9, 55L, 6), record(G, 4, 99_000_001L, 2));
        enter(PINNED, PLAYER);
        assertThat(reads.get()).isEqualTo(1);
        verify(scenes, times(2)).assign(any());
    }

    @Test
    void 夺权故障时_去向已计_异常照旧向上传() {
        doThrow(new IllegalStateException("db down")).when(store).claimOwnership(eq(PLAYER), any(Duration.class));
        locations(record(G, 0, 0, 5));

        CompletableFuture<HandlerReply> result =
                handler.handle(PINNED, EnterGameRequest.newBuilder().setPlayerId(PLAYER).build());

        assertThat(result).isCompletedExceptionally();
        assertOnlyRoute(EnterRoute.AWAITING);
    }
}
