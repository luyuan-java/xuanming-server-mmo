package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private long assigns(String result) {
        return meters.get(SceneDirectoryProvider.ASSIGN_METRIC).tag("result", result).timer().count();
    }

    private long switches(String result) {
        return meters.get(SceneDirectoryProvider.SWITCH_METRIC).tag("result", result).timer().count();
    }

    private SceneDirectoryProvider provider(SceneNodeSource source) {
        ChannelSelector selector = ChannelSelector.withoutReservations(source);
        return new SceneDirectoryProvider(new SceneAssigner(source, WORLD, selector),
                new SwitchTargetSelector(source, WORLD, selector), meters);
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
}
