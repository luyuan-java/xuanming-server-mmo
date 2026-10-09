package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.api.proto.ZoneRedirect;
import com.game.scenemanager.travel.TravelConfiguration;
import com.game.scenemanager.travel.TravelRouting;
import com.game.scenemanager.world.NodeAvailability;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SceneDirectoryProviderTest {

    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1));
    private static final AssignSceneRequest REQUEST =
            AssignSceneRequest.newBuilder().setZoneId(1).setPlayerId(42).build();
    private static final SelectSwitchTargetRequest SWITCH = SelectSwitchTargetRequest.newBuilder()
            .setZoneId(1).setPlayerId(42).setFromSceneNodeId(1).setFromSceneId(11).setWantSceneId(77).build();
    private static final SceneNodeInfo NODE = SceneNodeInfo.newBuilder()
            .setZoneId(1).setNodeId(3).setLinkHost("127.0.0.1").setLinkPort(21000)
            .addScenes(SceneEntry.newBuilder().setSceneId(77).setSceneConfigId(1))
            .addScenes(SceneEntry.newBuilder().setSceneId(78).setSceneConfigId(1).setDraining(true))
            .build();

    private static final CreateInstanceRequest MIRROR = CreateInstanceRequest.newBuilder()
            .setZoneId(1).setRequesterSceneNodeId(3).setPlayerId(42).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
            .setSourceSceneId(77).setSceneConfigId(1).setMirrorConfigId(1).build();
    private static final CreateInstanceRequest DUNGEON = CreateInstanceRequest.newBuilder()
            .setZoneId(1).setRequesterSceneNodeId(3).setKind(ChannelKind.CHANNEL_KIND_DUNGEON)
            .setSceneConfigId(17).setDungeonConfigId(1).build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private boolean leaseValid = true;
    /** 跨 zone 选路（批次 5.4）：缺省是先行件装配的占位，个别用例换成记录用的假实现。 */
    private TravelRouting travel = new TravelConfiguration().travelRouting();
    private InstanceIdIssuer issuer = new InstanceIdIssuer(SceneIdAllocator.forTesting(5, () -> leaseValid),
            NodeAvailability.ALL);

    private long instances(String kind, String result) {
        return meters.get(SceneDirectoryProvider.INSTANCE_METRIC).tag("kind", kind).tag("result", result).timer().count();
    }

    private long assigns(String result) {
        return meters.get(SceneDirectoryProvider.ASSIGN_METRIC).tag("result", result).timer().count();
    }

    private long switches(String result) {
        return meters.get(SceneDirectoryProvider.SWITCH_METRIC).tag("result", result).timer().count();
    }

    private SceneDirectoryProvider provider(SceneNodeSource source) {
        ChannelSelector selector = ChannelSelector.withoutReservations(source);
        return new SceneDirectoryProvider(new SceneAssigner(source, WORLD, selector),
                new SwitchTargetSelector(source, WORLD, selector), issuer, travel, meters);
    }

    @Test
    void 分配结果以已完成的future返回() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of(NODE));

        CompletableFuture<AssignSceneResponse> future = provider.assign(REQUEST);

        assertThat(future).isCompleted();
        assertThat(future.get().getSceneNodeId()).isEqualTo(3);
        assertThat(future.get().getSceneId()).isEqualTo(77);
        assertThat(assigns("ok")).isEqualTo(1);
    }

    @Test
    void 无场景是正常应答而不是异常() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of());

        assertThat(provider.assign(REQUEST).get().getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(assigns("no_scene")).isEqualTo(1);
        assertThat(assigns("error")).isZero();
    }

    @Test
    void 缺zone计bad_request() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of());

        assertThat(provider.assign(REQUEST.toBuilder().setZoneId(0).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
        assertThat(assigns("bad_request")).isEqualTo(1);
    }

    @Test
    void 目录不可读时future以异常完成且不外泄内部细节() {
        SceneDirectoryProvider provider = provider(zone -> {
            throw new IllegalStateException("redis://10.0.0.1:6379 unreachable");
        });

        CompletableFuture<AssignSceneResponse> future = provider.assign(REQUEST);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("场景目录暂不可用")
                .hasMessageNotContaining("10.0.0.1")
                .hasNoCause();
        assertThat(assigns("error")).isEqualTo(1);
    }

    // ---------- 换图选目标（批次 5.2，scene-handoff-spec §5.4、§7.2） ----------

    @Test
    void 换图选目标以已完成的future返回并计ok() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of(NODE));

        CompletableFuture<SelectSwitchTargetResponse> future = provider.selectSwitchTarget(SWITCH);

        assertThat(future).isCompleted();
        assertThat(future.get().getTipId()).isZero();
        assertThat(future.get().getSceneNodeId()).isEqualTo(3);
        assertThat(future.get().getSceneId()).isEqualTo(77);
        assertThat(future.get().getSceneConfigId()).isEqualTo(1);
        assertThat(switches("ok")).isEqualTo(1);
        assertThat(assigns("ok")).isZero();
    }

    @Test
    void 换图的业务拒绝是正常应答_按结局分别计数() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of(NODE));

        assertThat(provider.selectSwitchTarget(SWITCH.toBuilder().setWantSceneId(99).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(provider.selectSwitchTarget(SWITCH.toBuilder().setWantSceneId(78).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(provider.selectSwitchTarget(SWITCH.toBuilder().setWantSceneConfigId(2).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
        assertThat(provider.selectSwitchTarget(SWITCH.toBuilder().setZoneId(0).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_BAD_REQUEST);

        assertThat(switches("not_found")).isEqualTo(1);
        assertThat(switches("draining")).isEqualTo(1);
        assertThat(switches("bad_request")).isEqualTo(2);
        assertThat(switches("ok")).isZero();
        assertThat(switches("error")).isZero();
    }

    @Test
    void 换图时目录不可读_future以异常完成且不外泄内部细节() {
        SceneDirectoryProvider provider = provider(zone -> {
            throw new IllegalStateException("redis://10.0.0.1:6379 unreachable");
        });

        CompletableFuture<SelectSwitchTargetResponse> future = provider.selectSwitchTarget(SWITCH);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("场景目录暂不可用")
                .hasMessageNotContaining("10.0.0.1")
                .hasNoCause();
        assertThat(switches("error")).isEqualTo(1);
        assertThat(assigns("error")).isZero();
    }

    @Test
    void 换图指标的结果标签只有固定几种() {
        provider(zone -> List.of());

        assertThat(meters.get(SceneDirectoryProvider.SWITCH_METRIC).timers())
                .extracting(t -> t.getId().getTag("result"))
                .containsExactlyInAnyOrder("ok", "not_found", "draining", "bad_request", "error");
        assertThat(meters.get(SceneDirectoryProvider.SWITCH_METRIC).timers())
                .allSatisfy(t -> assertThat(t.getId().getTags()).hasSize(1));
    }

    // ---------- 镜像 / 副本实例取号（批次 5.3，dungeon-mirror-spec §6.6、§8.2、§12.3 SceneDirectoryProviderTest） ----------

    @Test
    void 实例取号以已完成的future返回_放在发起节点_按种类计ok_不读目录() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> {
            throw new AssertionError("实例取号不读节点目录");
        });

        CompletableFuture<CreateInstanceResponse> mirror = provider.createInstance(MIRROR);
        CompletableFuture<CreateInstanceResponse> dungeon = provider.createInstance(DUNGEON);

        assertThat(mirror).isCompleted();
        assertThat(mirror.get().getTipId()).isZero();
        assertThat(mirror.get().getSceneNodeId()).isEqualTo(3);
        assertThat(mirror.get().getSceneId()).isNotZero();
        assertThat(dungeon.get().getSceneNodeId()).isEqualTo(3);
        assertThat(dungeon.get().getSceneId()).isNotZero().isNotEqualTo(mirror.get().getSceneId());
        assertThat(instances("mirror", "ok")).isEqualTo(1);
        assertThat(instances("dungeon", "ok")).isEqualTo(1);
        assertThat(assigns("ok") + switches("ok")).as("不经分配 / 选目标").isZero();
    }

    @Test
    void 实例取号参数错是正常应答3005_种类不合法计在other() throws Exception {
        SceneDirectoryProvider provider = provider(zone -> List.of());

        assertThat(provider.createInstance(MIRROR.toBuilder().setSourceSceneId(0).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
        assertThat(provider.createInstance(MIRROR.toBuilder().setKind(ChannelKind.CHANNEL_KIND_WORLD).build()).get()
                .getTipId()).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
        assertThat(provider.createInstance(MIRROR.toBuilder().setKindValue(9).build()).get().getTipId())
                .isEqualTo(SceneAssigner.TIP_BAD_REQUEST);

        assertThat(instances("mirror", "bad_request")).isEqualTo(1);
        assertThat(instances("other", "bad_request")).isEqualTo(2);
        assertThat(instances("mirror", "ok")).isZero();
    }

    @Test
    void 发号租约无效时future以异常完成_计no_lease_Q12() {
        leaseValid = false;
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CompletableFuture<CreateInstanceResponse> future = provider.createInstance(MIRROR);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("发号租约")
                .hasNoCause();
        assertThat(instances("mirror", "no_lease")).isEqualTo(1);
        assertThat(instances("mirror", "error")).isZero();
    }

    @Test
    void 发起节点不接新实例时回3000_计node_unavailable_5_5钩子() throws Exception {
        issuer = new InstanceIdIssuer(SceneIdAllocator.forTesting(5, () -> true), (zone, node) -> false);
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CreateInstanceResponse response = provider.createInstance(DUNGEON).get();

        assertThat(response.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(response.getSceneId()).isZero();
        assertThat(instances("dungeon", "node_unavailable")).isEqualTo(1);
    }

    @Test
    void 取号意外异常时future以异常完成且不外泄内部细节_计error() {
        issuer = new InstanceIdIssuer(() -> {
            throw new IllegalStateException("redis://10.0.0.1:6379 unreachable");
        }, NodeAvailability.ALL);
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CompletableFuture<CreateInstanceResponse> future = provider.createInstance(MIRROR);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实例取号暂不可用")
                .hasMessageNotContaining("10.0.0.1")
                .hasNoCause();
        assertThat(instances("mirror", "error")).isEqualTo(1);
    }

    @Test
    void 实例取号指标只有种类与结果两个标签_取值固定() {
        provider(zone -> List.of());

        assertThat(meters.get(SceneDirectoryProvider.INSTANCE_METRIC).timers())
                .allSatisfy(t -> assertThat(t.getId().getTags()).extracting(Tag::getKey)
                        .containsExactlyInAnyOrder("kind", "result"))
                .extracting(t -> t.getId().getTag("kind") + "/" + t.getId().getTag("result"))
                .containsExactlyInAnyOrder(
                        "mirror/ok", "mirror/bad_request", "mirror/node_unavailable", "mirror/no_lease", "mirror/error",
                        "dungeon/ok", "dungeon/bad_request", "dungeon/node_unavailable", "dungeon/no_lease", "dungeon/error",
                        "other/ok", "other/bad_request", "other/node_unavailable", "other/no_lease", "other/error");
    }

    // ---------- 跨 zone 选路（批次 5.4 先行件）：两个新方法原样委托给 TravelRouting ----------

    private static final SelectTravelTargetRequest TRAVEL = SelectTravelTargetRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).setWantSceneConfigId(5).build();
    private static final RedirectToZoneRequest REDIRECT = RedirectToZoneRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).build();
    private static final ZoneRedirect TICKET = ZoneRedirect.newBuilder().setTargetZoneId(2).setGateNodeId(7)
            .setGateHost("10.0.0.7").setGatePort(11010).setTokenPayload(ByteString.copyFromUtf8("payload-bytes"))
            .setTokenSignature(ByteString.copyFromUtf8("signature")).setTokenDeadline(1_800_000_300L).build();

    /** 记录收到的请求、返回预先放好的 future 的假选路。 */
    private static final class RecordingRouting implements TravelRouting {
        final List<SelectTravelTargetRequest> travelRequests = new ArrayList<>();
        final List<RedirectToZoneRequest> redirectRequests = new ArrayList<>();
        CompletableFuture<SelectTravelTargetResponse> travelReply = new CompletableFuture<>();
        CompletableFuture<RedirectToZoneResponse> redirectReply = new CompletableFuture<>();

        @Override
        public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
            travelRequests.add(request);
            return travelReply;
        }

        @Override
        public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
            redirectRequests.add(request);
            return redirectReply;
        }
    }

    @Test
    void 跨zone传送选目标_请求原样交给选路_返回的就是它给的那个future_载荷不经改写() throws Exception {
        RecordingRouting routing = new RecordingRouting();
        travel = routing;
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CompletableFuture<SelectTravelTargetResponse> future = provider.selectTravelTarget(TRAVEL);

        assertThat(routing.travelRequests).containsExactly(TRAVEL);
        assertThat(routing.redirectRequests).isEmpty();
        assertThat(future).as("不包一层、不另起线程：就是选路返回的那个 future").isSameAs(routing.travelReply);
        assertThat(future).isNotDone();
        SelectTravelTargetResponse chosen = SelectTravelTargetResponse.newBuilder().setSceneConfigId(5).setRedirect(TICKET).build();
        routing.travelReply.complete(chosen);
        assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(chosen);
        assertThat(future.get(5, TimeUnit.SECONDS).getRedirect().getTokenPayload())
                .isEqualTo(ByteString.copyFromUtf8("payload-bytes"));
    }

    @Test
    void 登录期重定向_请求原样交给选路_业务拒绝走tip_调用失败以异常完成() throws Exception {
        RecordingRouting routing = new RecordingRouting();
        travel = routing;
        SceneDirectoryProvider provider = provider(zone -> List.of());

        routing.redirectReply = CompletableFuture.completedFuture(RedirectToZoneResponse.newBuilder().setTipId(3000).build());
        RedirectToZoneResponse refused = provider.redirectToZone(REDIRECT).get(5, TimeUnit.SECONDS);
        assertThat(refused.getTipId()).isEqualTo(3000);
        assertThat(refused.hasRedirect()).isFalse();

        routing.redirectReply = CompletableFuture.failedFuture(new IllegalStateException("gate 目录暂不可用"));
        CompletableFuture<RedirectToZoneResponse> failed = provider.redirectToZone(REDIRECT);
        assertThat(failed).isCompletedExceptionally();
        assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("gate 目录暂不可用");

        assertThat(routing.redirectRequests).containsExactly(REDIRECT, REDIRECT);
        assertThat(routing.travelRequests).isEmpty();
    }

    /**
     * 先行件阶段装配的是 {@link TravelConfiguration} 的占位：两个方法都以 {@link IllegalStateException}（「5.4 施工中」）完成，
     * 不是 tip、不是 null、不是挂起——调用方按「调用失败」处理（226 受理后推 23 {3027}，登录期重定向回落入口 zone）。
     */
    @Test
    void 先行件的占位选路_两个新方法都以异常完成_不挂起不回空应答() {
        travel = new TravelConfiguration().travelRouting();
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CompletableFuture<SelectTravelTargetResponse> selected = provider.selectTravelTarget(TRAVEL);
        CompletableFuture<RedirectToZoneResponse> redirected = provider.redirectToZone(REDIRECT);

        assertThat(selected).isCompletedExceptionally();
        assertThat(redirected).isCompletedExceptionally();
        assertThatThrownBy(() -> selected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("5.4 施工中");
        assertThatThrownBy(() -> redirected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("5.4 施工中");
        assertThat(travel).hasToString("TravelRouting.PLACEHOLDER");
    }

    /** 选路的实现违反契约（同步抛出 / 返回 null）时，提供方仍交回以异常完成的 future，内部细节不外泄。 */
    @Test
    void 选路同步抛出或返回null_仍以异常完成的future交回_不外泄内部细节() {
        travel = new TravelRouting() {
            @Override
            public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
                throw new IllegalStateException("redis://10.0.0.1:6379 unreachable");
            }

            @Override
            public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
                return null;
            }
        };
        SceneDirectoryProvider provider = provider(zone -> List.of());

        CompletableFuture<SelectTravelTargetResponse> selected = provider.selectTravelTarget(TRAVEL);
        CompletableFuture<RedirectToZoneResponse> redirected = provider.redirectToZone(REDIRECT);

        assertThat(selected).isCompletedExceptionally();
        assertThatThrownBy(() -> selected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("跨 zone 选路暂不可用")
                .hasMessageNotContaining("redis://").hasNoCause();
        assertThat(redirected).isCompletedExceptionally();
        assertThatThrownBy(() -> redirected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("跨 zone 选路暂不可用");
    }

    @Test
    void 提供方必须有选路_缺了当场抛_两个新方法不新增指标序列() {
        assertThatThrownBy(() -> new SceneDirectoryProvider(null, null, issuer, null, meters))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("travelRouting");

        provider(zone -> List.of());
        // 只看计时器本身（SimpleMeterRegistry 还会为每个带桶的计时器另建一个 .histogram 量表）
        assertThat(meters.getMeters()).filteredOn(meter -> meter instanceof io.micrometer.core.instrument.Timer)
                .extracting(meter -> meter.getId().getName())
                .as("提供方只注册分配 / 换图 / 实例取号三组计时器；跨 zone 选路的指标归选路的实现")
                .containsOnly(SceneDirectoryProvider.ASSIGN_METRIC, SceneDirectoryProvider.SWITCH_METRIC,
                        SceneDirectoryProvider.INSTANCE_METRIC);
    }
}
