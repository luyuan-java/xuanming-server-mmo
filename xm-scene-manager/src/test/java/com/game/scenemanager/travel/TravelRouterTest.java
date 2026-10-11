package com.game.scenemanager.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.api.proto.WorldChannel;
import com.game.api.proto.ZoneRedirect;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokens;
import com.game.discovery.gate.GateSource;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.proto.GateTokenPayload;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import com.game.scenemanager.world.FakeWorldChannelStore;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 跨 zone 的两种选路（批次 5.4，zone-travel-spec §11.4，{@code SceneAssignerTest} 风格）：频道计划、scene 节点目录、gate 目录、
 * 合服围栏都是内存替身，票据用真的签发器（固定时钟、固定密钥）签、用 {@link GateTokens#verify} 验。不连 Redis。
 */
class TravelRouterTest {

    private static final int FROM = 1;
    private static final int TO = 2;
    private static final long PLAYER = 42;
    /** 世界地图：1（默认）、2；3 是副本类配置，不在 World 表里。 */
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1, 2));
    private static final String SECRET = "travel-router-test-secret";
    private static final long NOW = 1_800_000_000L;

    private final FakeWorldChannelStore plans = new FakeWorldChannelStore();
    private final Map<Integer, List<SceneNodeInfo>> sceneDirectory = new HashMap<>();
    private final Map<Integer, List<GateNodeInfo>> gateDirectory = new HashMap<>();
    private RuntimeException sceneFailure;
    private RuntimeException gateFailure;
    private int sceneReads;
    private int gateReads;
    private final List<Integer> fenceChecks = new ArrayList<>();
    /** null = 放行；否则按它的结果走（true = 封锁；抛出 = 读不到）。 */
    private FenceAnswer fenceAnswer;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private interface FenceAnswer {
        boolean inProgress(int zoneId) throws Exception;
    }

    private final SceneNodeSource scenes = zone -> {
        sceneReads++;
        if (sceneFailure != null) {
            throw sceneFailure;
        }
        return sceneDirectory.getOrDefault(zone, List.of());
    };

    private final GateSource gates = zone -> {
        gateReads++;
        if (gateFailure != null) {
            throw gateFailure;
        }
        return gateDirectory.getOrDefault(zone, List.of());
    };

    private final ZoneMergeFence fence = new ZoneMergeFence() {
        @Override
        public boolean inProgress(int zoneId) throws Exception {
            fenceChecks.add(zoneId);
            return fenceAnswer != null && fenceAnswer.inProgress(zoneId);
        }

        @Override
        public CompletionStage<Boolean> inProgressAsync(int zoneId) {
            throw new AssertionError("scene-manager 在 Dubbo 业务线程上用同步版");
        }
    };

    private final GateTokenIssuer issuer = new GateTokenIssuer(GateTokens.ofUtf8(SECRET),
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), new SecureRandom());
    private final TravelRouter router = new TravelRouter(plans, scenes, gates, WORLD, fence, issuer, meters);

    // ---------------------------------------------------------------- 夹具

    private void channel(int zone, long sceneId, int conf, ChannelState state) {
        channel(zone, sceneId, conf, state, ChannelKind.CHANNEL_KIND_WORLD);
    }

    private void channel(int zone, long sceneId, int conf, ChannelState state, ChannelKind kind) {
        plans.zone(zone).channels.put(sceneId, WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(conf)
                .setNodeId(1).setState(state).setKind(kind).build());
    }

    private static GateNodeInfo gate(int nodeId, int playerCount) {
        return GateNodeInfo.newBuilder().setZoneId(TO).setNodeId(nodeId).setInstanceId("g-" + nodeId)
                .setClientHost("10.0.2." + nodeId).setClientPort(11000 + nodeId).setPlayerCount(playerCount).build();
    }

    private void gates(GateNodeInfo... entries) {
        gateDirectory.put(TO, List.of(entries));
    }

    private static SceneNodeInfo sceneNode(int zone, int nodeId) {
        return SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(nodeId).setInstanceId("s-" + nodeId)
                .setLinkHost("127.0.0.1").setLinkPort(21000 + nodeId)
                .addScenes(SceneEntry.newBuilder().setSceneId(1000L + nodeId).setSceneConfigId(1))
                .build();
    }

    /** 目标 zone 的默认主世界开着、有一台 gate、有一个 scene 节点：两种选路都能成功的最小环境。 */
    private void readyTargetZone() {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        gates(gate(7, 3));
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));
    }

    private static SelectTravelTargetRequest travelRequest(int from, int to, long player, int want) {
        return SelectTravelTargetRequest.newBuilder().setFromZoneId(from).setToZoneId(to).setPlayerId(player)
                .setWantSceneConfigId(want).build();
    }

    private static RedirectToZoneRequest redirectRequest(int from, int to, long player) {
        return RedirectToZoneRequest.newBuilder().setFromZoneId(from).setToZoneId(to).setPlayerId(player).build();
    }

    private SelectTravelTargetResponse travel(int want) throws Exception {
        return travel(travelRequest(FROM, TO, PLAYER, want));
    }

    private SelectTravelTargetResponse travel(SelectTravelTargetRequest request) throws Exception {
        CompletableFuture<SelectTravelTargetResponse> future = router.selectTravelTarget(request);
        assertThat(future).as("在调用线程上同步算完，返回已完成的 future").isDone();
        return future.get(5, TimeUnit.SECONDS);
    }

    private RedirectToZoneResponse redirect() throws Exception {
        return redirect(redirectRequest(FROM, TO, PLAYER));
    }

    private RedirectToZoneResponse redirect(RedirectToZoneRequest request) throws Exception {
        CompletableFuture<RedirectToZoneResponse> future = router.redirectToZone(request);
        assertThat(future).isDone();
        return future.get(5, TimeUnit.SECONDS);
    }

    private long travels(String result) {
        return meters.get(TravelRouter.TRAVEL_METRIC).tag("result", result).timer().count();
    }

    private long redirects(String result) {
        return meters.get(TravelRouter.REDIRECT_METRIC).tag("result", result).timer().count();
    }

    private long totalCount(String metric) {
        return meters.find(metric).timers().stream().mapToLong(Timer::count).sum();
    }

    /** 票据可在选中的 gate（目标 zone、节点号 = gate）上验过，字段与 zone-travel-spec §1.4 的重定向票据一致。 */
    private static void assertTicket(ZoneRedirect redirect, GateNodeInfo gate) throws Exception {
        assertThat(redirect.getTargetZoneId()).isEqualTo(TO);
        assertThat(redirect.getGateNodeId()).isEqualTo(gate.getNodeId());
        assertThat(redirect.getGateHost()).as("地址取 gate 的客户端通告地址").isEqualTo(gate.getClientHost());
        assertThat(redirect.getGatePort()).isEqualTo(gate.getClientPort());
        assertThat(redirect.getTokenDeadline()).isEqualTo(NOW + 300);

        GateTokenPayload payload = GateTokenPayload.parseFrom(redirect.getTokenPayload());
        assertThat(payload.getGateNodeId()).isEqualTo(gate.getNodeId());
        assertThat(payload.getZoneId()).isEqualTo(TO);
        assertThat(payload.getExpireTimestamp()).isEqualTo(NOW + 300);
        assertThat(payload.getPlayerId()).isEqualTo(PLAYER);
        assertThat(payload.getTargetZoneId()).isEqualTo(TO);
        assertThat(payload.getHmacSessionKey().isEmpty()).as("重定向票据不带会话密钥").isTrue();

        assertThat(new String(redirect.getTokenSignature().toByteArray(), java.nio.charset.StandardCharsets.US_ASCII))
                .matches("[0-9a-f]{64}");
        GateTokens tokens = GateTokens.ofUtf8(SECRET);
        assertThat(tokens.verify(redirect.getTokenPayload(), redirect.getTokenSignature(), gate.getNodeId(), TO, NOW).ok())
                .as("目标 zone 的这台 gate 验得过").isTrue();
        assertThat(tokens.verify(redirect.getTokenPayload(), redirect.getTokenSignature(), gate.getNodeId(), FROM, NOW).failure())
                .as("别的 zone 里同号的 gate 不收").isEqualTo(GateTokens.Failure.WRONG_ZONE);
        assertThat(tokens.verify(redirect.getTokenPayload(), redirect.getTokenSignature(), gate.getNodeId() + 1, TO, NOW)
                .failure()).isEqualTo(GateTokens.Failure.WRONG_GATE);
        assertThat(tokens.verify(redirect.getTokenPayload(), redirect.getTokenSignature(), gate.getNodeId(), TO, NOW + 300)
                .failure()).as("300 s 后过期").isEqualTo(GateTokens.Failure.EXPIRED);
    }

    // ================================================================ selectTravelTarget：参数与地图预检

    @Test
    void 传送_zone为0_同zone_玩家为0_来源zone为0_都回参数错_不读任何存储() throws Exception {
        readyTargetZone();
        List<SelectTravelTargetRequest> bad = List.of(
                travelRequest(FROM, 0, PLAYER, 0),
                travelRequest(FROM, FROM, PLAYER, 0),
                travelRequest(FROM, TO, 0, 0),
                travelRequest(0, TO, PLAYER, 0));
        for (SelectTravelTargetRequest request : bad) {
            SelectTravelTargetResponse response = travel(request);
            assertThat(response.getTipId()).as("%s", request).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
            assertThat(response.hasRedirect()).isFalse();
        }
        assertThat(plans.snapshots).isZero();
        assertThat(gateReads).isZero();
        assertThat(fenceChecks).isEmpty();
        assertThat(travels("bad_request")).isEqualTo(4);
    }

    @Test
    void 传送_想去的地图不是世界地图_回参数错_不读计划() throws Exception {
        readyTargetZone();
        channel(TO, 503, 3, ChannelState.CHANNEL_ACTIVE);

        assertThat(travel(3).getTipId()).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
        assertThat(plans.snapshots).isZero();
        assertThat(gateReads).isZero();
        assertThat(travels("bad_request")).isEqualTo(1);
    }

    @Test
    void 传送_目标zone的计划里没有这张图_回无场景_不查围栏不读gate目录() throws Exception {
        readyTargetZone();
        channel(FROM, 601, 2, ChannelState.CHANNEL_ACTIVE); // 别的 zone 开着这张图不算

        SelectTravelTargetResponse response = travel(2);

        assertThat(response.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(response.hasRedirect()).isFalse();
        assertThat(plans.snapshots).isEqualTo(1);
        assertThat(fenceChecks).isEmpty();
        assertThat(gateReads).isZero();
        assertThat(travels("no_map")).isEqualTo(1);
    }

    @Test
    void 传送_不指定地图时预检默认主世界_计划里没有默认主世界也拒() throws Exception {
        channel(TO, 502, 2, ChannelState.CHANNEL_ACTIVE);
        gates(gate(7, 0));

        assertThat(travel(0).getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(travels("no_map")).isEqualTo(1);
    }

    @Test
    void 传送_只有排空中的记录也放行_过渡窗口由第二条腿回落() throws Exception {
        channel(TO, 502, 2, ChannelState.CHANNEL_DRAINING);
        gates(gate(7, 0));

        SelectTravelTargetResponse response = travel(2);

        assertThat(response.getTipId()).isZero();
        assertThat(response.getSceneConfigId()).isEqualTo(2);
        assertThat(travels("ok")).isEqualTo(1);
    }

    @Test
    void 传送_状态未知或不是主世界的记录不算开着() throws Exception {
        channel(TO, 502, 2, ChannelState.CHANNEL_STATE_UNSPECIFIED);
        channel(TO, 503, 2, ChannelState.CHANNEL_ACTIVE, ChannelKind.CHANNEL_KIND_MIRROR);
        gates(gate(7, 0));

        assertThat(travel(2).getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);

        channel(TO, 504, 2, ChannelState.CHANNEL_ACTIVE, ChannelKind.CHANNEL_KIND_UNSPECIFIED); // 旧版本记录按 WORLD 读
        assertThat(travel(2).getTipId()).isZero();
    }

    @Test
    void 传送_计划读失败_future以异常完成_不外泄内部细节_不读gate目录() {
        readyTargetZone();
        plans.snapshotFailure = new IllegalStateException("redis://10.0.0.1:6379 unreachable");

        CompletableFuture<SelectTravelTargetResponse> future = router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0));

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("跨 zone 选路暂不可用")
                .hasMessageNotContaining("redis://").hasNoCause();
        assertThat(gateReads).isZero();
        assertThat(travels("error")).isEqualTo(1);
        assertThat(travels("no_map")).as("读不到不伪装成没有这张图").isZero();
    }

    // ================================================================ selectTravelTarget：围栏与选 gate

    @Test
    void 传送_合服围栏检查点在地图预检之后_选gate之前_7_3之前判zone0放行() throws Exception {
        readyTargetZone();

        assertThat(travel(0).getTipId()).isZero();
        assertThat(fenceChecks).as("请求里还没有归属区（7.3 加 home_zone_id），按围栏契约判 0").containsExactly(0);
    }

    @Test
    void 传送_围栏命中或读不到_都按封锁拒绝_不读gate目录() throws Exception {
        readyTargetZone();
        fenceAnswer = zone -> true;

        SelectTravelTargetResponse fenced = travel(0);
        assertThat(fenced.getTipId()).isEqualTo(TravelRouter.TIP_ZONE_FENCED);
        assertThat(fenced.hasRedirect()).isFalse();

        fenceAnswer = zone -> {
            throw new IllegalStateException("围栏读不到");
        };
        assertThat(travel(0).getTipId()).as("读不到 fail-closed").isEqualTo(TravelRouter.TIP_ZONE_FENCED);
        assertThat(gateReads).isZero();
        assertThat(travels("fenced")).isEqualTo(2);
        assertThat(travels("error")).isZero();
    }

    @Test
    void 传送_围栏读时线程被中断_按封锁拒绝并保留中断标记() throws Exception {
        readyTargetZone();
        fenceAnswer = zone -> {
            throw new InterruptedException("关停");
        };
        try {
            assertThat(router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0)).get().getTipId())
                    .isEqualTo(TravelRouter.TIP_ZONE_FENCED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 传送_目标zone没有gate_或都没有客户端地址_回没有gate() throws Exception {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);

        assertThat(travel(0).getTipId()).isEqualTo(TravelRouter.TIP_NO_GATE);

        gates(gate(7, 0).toBuilder().setClientHost("").build(), gate(8, 0).toBuilder().setClientPort(0).build(),
                gate(9, 0).toBuilder().setZoneId(FROM).build());
        SelectTravelTargetResponse response = travel(0);
        assertThat(response.getTipId()).isEqualTo(TravelRouter.TIP_NO_GATE);
        assertThat(response.hasRedirect()).isFalse();
        assertThat(travels("no_gate")).isEqualTo(2);
    }

    @Test
    void 传送_排空与不排空混合只挑不排空的_全部排空时忽略标记() throws Exception {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        GateNodeInfo drainingLight = gate(3, 0).toBuilder().setDraining(true).build();
        GateNodeInfo busy = gate(9, 80);
        gates(drainingLight, busy);

        assertThat(travel(0).getRedirect().getGateNodeId()).isEqualTo(9);

        GateNodeInfo drainingBusy = busy.toBuilder().setDraining(true).build();
        gates(drainingLight, drainingBusy);
        SelectTravelTargetResponse allDraining = travel(0);
        assertThat(allDraining.getTipId()).isZero();
        assertThat(allDraining.getRedirect().getGateNodeId()).isEqualTo(3);
    }

    @Test
    void 传送_同一客户端地址两条只留启动时刻大者_人数更少的陈旧条目也不选() throws Exception {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        GateNodeInfo stale = gate(4, 0).toBuilder().setStartedAtMs(1_000L).build();
        GateNodeInfo restarted = stale.toBuilder().setNodeId(6).setInstanceId("g-6").setPlayerCount(25)
                .setStartedAtMs(2_000L).build();
        gates(stale, restarted);

        SelectTravelTargetResponse response = travel(0);

        assertThat(response.getRedirect().getGateNodeId()).isEqualTo(6);
        assertThat(GateTokenPayload.parseFrom(response.getRedirect().getTokenPayload()).getGateNodeId()).isEqualTo(6);
        assertTicket(response.getRedirect(), restarted);
    }

    @Test
    void 传送_人数相同按节点号小者() throws Exception {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        gates(gate(9, 5), gate(4, 5), gate(7, 6));

        assertThat(travel(0).getRedirect().getGateNodeId()).isEqualTo(4);
    }

    @Test
    void 传送_选中_地址取客户端地址_票据字段正确_地图是解析后的值() throws Exception {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        channel(TO, 502, 2, ChannelState.CHANNEL_ACTIVE);
        GateNodeInfo chosen = gate(7, 3);
        gates(gate(8, 10), chosen);

        SelectTravelTargetResponse byDefault = travel(0);
        assertThat(byDefault.getTipId()).isZero();
        assertThat(byDefault.getSceneConfigId()).as("请求给 0：目标 zone 的默认主世界").isEqualTo(1);
        assertTicket(byDefault.getRedirect(), chosen);

        SelectTravelTargetResponse wanted = travel(2);
        assertThat(wanted.getTipId()).isZero();
        assertThat(wanted.getSceneConfigId()).isEqualTo(2);
        assertTicket(wanted.getRedirect(), chosen);

        assertThat(sceneReads).as("226 选目标不看 scene 节点目录（地图预检读的是频道计划）").isZero();
        assertThat(travels("ok")).isEqualTo(2);
    }

    @Test
    void 传送_gate目录读失败_future以异常完成() {
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        gateFailure = new IllegalStateException("redis down");

        CompletableFuture<SelectTravelTargetResponse> future = router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0));

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("跨 zone 选路暂不可用");
        assertThat(travels("error")).isEqualTo(1);
        assertThat(travels("no_gate")).as("读不到不伪装成没有 gate").isZero();
    }

    // ================================================================ redirectToZone

    @Test
    void 重定向_参数不合法回参数错_不读任何目录() throws Exception {
        readyTargetZone();
        for (RedirectToZoneRequest request : List.of(redirectRequest(FROM, 0, PLAYER), redirectRequest(FROM, FROM, PLAYER),
                redirectRequest(FROM, TO, 0), redirectRequest(0, TO, PLAYER))) {
            RedirectToZoneResponse response = redirect(request);
            assertThat(response.getTipId()).as("%s", request).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
            assertThat(response.hasRedirect()).isFalse();
        }
        assertThat(sceneReads).isZero();
        assertThat(gateReads).isZero();
        assertThat(redirects("bad_request")).isEqualTo(4);
    }

    @Test
    void 重定向_不查地图不查围栏_计划空着或读不到都照样选中() throws Exception {
        gates(gate(7, 3));
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));
        plans.snapshotFailure = new IllegalStateException("计划读不到也与重定向无关");
        fenceAnswer = zone -> true;

        RedirectToZoneResponse response = redirect();

        assertThat(response.getTipId()).isZero();
        assertTicket(response.getRedirect(), gate(7, 3));
        assertThat(plans.snapshots).isZero();
        assertThat(fenceChecks).as("只送连接，同基线 F3 不查").isEmpty();
        assertThat(redirects("ok")).isEqualTo(1);
    }

    @Test
    void 重定向_目标zone没有可用的scene节点_回无场景_不读gate目录() throws Exception {
        gates(gate(7, 3));

        assertThat(redirect().getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);

        // 只有不完整 / 串 zone 的条目：同 SceneAssigner.isUsable 的判法，不算可用
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1).toBuilder().setLinkHost("").build(),
                sceneNode(TO, 2).toBuilder().setLinkPort(0).build(), sceneNode(FROM, 3)));
        RedirectToZoneResponse response = redirect();
        assertThat(response.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(response.hasRedirect()).isFalse();
        assertThat(gateReads).isZero();
        assertThat(redirects("no_scene")).isEqualTo(2);
    }

    @Test
    void 重定向_目标zone没有gate_回没有gate() throws Exception {
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));

        assertThat(redirect().getTipId()).isEqualTo(TravelRouter.TIP_NO_GATE);
        assertThat(redirects("no_gate")).isEqualTo(1);
    }

    @Test
    void 重定向_同地址去重与排空规则同传送() throws Exception {
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));
        GateNodeInfo stale = gate(4, 0).toBuilder().setStartedAtMs(1_000L).build();
        GateNodeInfo restarted = stale.toBuilder().setNodeId(6).setInstanceId("g-6").setPlayerCount(25)
                .setStartedAtMs(2_000L).build();
        GateNodeInfo draining = gate(2, 0).toBuilder().setDraining(true).build();
        gates(stale, restarted, draining);

        assertThat(redirect().getRedirect().getGateNodeId()).isEqualTo(6);
    }

    @Test
    void 重定向_scene节点目录或gate目录读失败_future以异常完成() {
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));
        sceneFailure = new IllegalStateException("redis://10.0.0.1:6379 unreachable");

        CompletableFuture<RedirectToZoneResponse> sceneDown = router.redirectToZone(redirectRequest(FROM, TO, PLAYER));
        assertThat(sceneDown).isCompletedExceptionally();
        assertThatThrownBy(() -> sceneDown.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageNotContaining("redis://");
        assertThat(gateReads).isZero();

        sceneFailure = null;
        gateFailure = new IllegalStateException("redis down");
        assertThat(router.redirectToZone(redirectRequest(FROM, TO, PLAYER))).isCompletedExceptionally();
        assertThat(redirects("error")).isEqualTo(2);
        assertThat(redirects("no_scene") + redirects("no_gate")).isZero();
    }

    // ================================================================ 指标

    @Test
    void 指标_全部序列预注册_只有result标签_传送6种重定向5种() {
        assertThat(meters.find(TravelRouter.TRAVEL_METRIC).timers())
                .allSatisfy(t -> assertThat(t.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactly("result"))
                .extracting(t -> t.getId().getTag("result"))
                .containsExactlyInAnyOrder("ok", "no_gate", "no_map", "fenced", "bad_request", "error");
        assertThat(meters.find(TravelRouter.REDIRECT_METRIC).timers())
                .allSatisfy(t -> assertThat(t.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactly("result"))
                .extracting(t -> t.getId().getTag("result"))
                .containsExactlyInAnyOrder("ok", "no_gate", "no_scene", "bad_request", "error");
        assertThat(meters.getMeters()).filteredOn(m -> m instanceof Timer).extracting(m -> m.getId().getName())
                .containsOnly(TravelRouter.TRAVEL_METRIC, TravelRouter.REDIRECT_METRIC);
        assertThat(totalCount(TravelRouter.TRAVEL_METRIC) + totalCount(TravelRouter.REDIRECT_METRIC)).isZero();
    }

    /** Prometheus 名（zone-travel-spec §7.2）：{@code xm_scene_manager_travel_seconds} / {@code xm_scene_manager_redirect_seconds}，启动即有 0 值序列。 */
    @Test
    void 指标_Prometheus名与规格一致_启动即导出() throws Exception {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            TravelRouter exported = new TravelRouter(plans, scenes, gates, WORLD, fence, issuer, prometheus);
            String before = prometheus.scrape();
            for (String result : List.of("ok", "no_gate", "no_map", "fenced", "bad_request", "error")) {
                assertThat(before).contains("xm_scene_manager_travel_seconds_count{result=\"" + result + "\"} 0");
            }
            for (String result : List.of("ok", "no_gate", "no_scene", "bad_request", "error")) {
                assertThat(before).contains("xm_scene_manager_redirect_seconds_count{result=\"" + result + "\"} 0");
            }

            readyTargetZone();
            exported.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0)).get(5, TimeUnit.SECONDS);
            exported.redirectToZone(redirectRequest(FROM, TO, PLAYER)).get(5, TimeUnit.SECONDS);
            String after = prometheus.scrape();
            assertThat(after).contains("xm_scene_manager_travel_seconds_count{result=\"ok\"} 1")
                    .contains("xm_scene_manager_redirect_seconds_count{result=\"ok\"} 1")
                    .doesNotContain("player").doesNotContain("zone=");
        } finally {
            prometheus.close();
        }
    }

    @Test
    void 指标_每次调用按结果恰好计一次() throws Exception {
        // 传送：bad_request、no_map、fenced、no_gate、ok、error 各一次
        travel(travelRequest(FROM, FROM, PLAYER, 0));
        travel(0);
        channel(TO, 501, 1, ChannelState.CHANNEL_ACTIVE);
        fenceAnswer = zone -> true;
        travel(0);
        fenceAnswer = null;
        travel(0);
        gates(gate(7, 0));
        travel(0);
        plans.snapshotFailure = new IllegalStateException("down");
        router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0));

        for (String result : List.of("ok", "no_gate", "no_map", "fenced", "bad_request", "error")) {
            assertThat(travels(result)).as(result).isEqualTo(1);
        }
        assertThat(totalCount(TravelRouter.TRAVEL_METRIC)).isEqualTo(6);
        assertThat(totalCount(TravelRouter.REDIRECT_METRIC)).as("两组互不串").isZero();

        // 重定向：bad_request、no_scene、no_gate、ok、error 各一次
        gateDirectory.clear();
        redirect(redirectRequest(FROM, TO, 0));
        redirect();
        sceneDirectory.put(TO, List.of(sceneNode(TO, 1)));
        redirect();
        gates(gate(7, 0));
        redirect();
        sceneFailure = new IllegalStateException("down");
        router.redirectToZone(redirectRequest(FROM, TO, PLAYER));

        for (String result : List.of("ok", "no_gate", "no_scene", "bad_request", "error")) {
            assertThat(redirects(result)).as(result).isEqualTo(1);
        }
        assertThat(totalCount(TravelRouter.REDIRECT_METRIC)).isEqualTo(5);
        assertThat(totalCount(TravelRouter.TRAVEL_METRIC)).isEqualTo(6);
    }

    @Test
    void 每次调用签一张新票据_返回新的future() throws Exception {
        readyTargetZone();

        CompletableFuture<SelectTravelTargetResponse> first = router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0));
        CompletableFuture<SelectTravelTargetResponse> second = router.selectTravelTarget(travelRequest(FROM, TO, PLAYER, 0));

        assertThat(second).isNotSameAs(first);
        assertThat(first.get().getRedirect()).as("固定时钟下同一台 gate 同一持票者，票据逐字节相同（不依赖随机数）")
                .isEqualTo(second.get().getRedirect());
    }
}
