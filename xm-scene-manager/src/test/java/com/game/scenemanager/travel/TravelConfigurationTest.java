package com.game.scenemanager.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.scenemanager.ChannelSelector;
import com.game.scenemanager.InstanceIdIssuer;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneDirectoryProvider;
import com.game.scenemanager.SceneIdAllocator;
import com.game.scenemanager.SceneManagerApplication;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.SwitchTargetSelector;
import com.game.scenemanager.WorldSceneConfigs;
import com.game.scenemanager.world.NodeAvailability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 跨 zone 选路的装配（批次 5.4 先行件；真实现接上后由对应的工作包接着写密钥、选 gate、签票据的用例）。不起 Dubbo、不连 Redis。
 *
 * <p>这里钉的是「先行件之后 scene-manager 进程起得来」的那一环：{@code SceneDirectoryProvider} 是 {@code @DubboService}、构造器注入，
 * 多了一个 {@link TravelRouting} 参数就必须有这个 bean——{@link TravelConfiguration} 提供占位，容器按构造器把提供方建得出来，
 * 两个新 RPC 经它走到占位、以异常完成。
 */
class TravelConfigurationTest {

    private static final SelectTravelTargetRequest TRAVEL = SelectTravelTargetRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).build();
    private static final RedirectToZoneRequest REDIRECT = RedirectToZoneRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).build();
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1));
    private static final SceneNodeSource NO_NODES = zone -> List.of();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TravelConfiguration.class);

    @Test
    void 装配提供恰好一个选路bean_先行件阶段是占位_不要求gate令牌密钥() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TravelRouting.class);
            assertThat(ctx.getBean(TravelRouting.class)).hasToString("TravelRouting.PLACEHOLDER");
        });
    }

    @Test
    void 占位选路_两个方法都以5_4施工中的异常完成_每次调用都是新的future() {
        TravelRouting routing = new TravelConfiguration().travelRouting();

        CompletableFuture<SelectTravelTargetResponse> selected = routing.selectTravelTarget(TRAVEL);
        CompletableFuture<RedirectToZoneResponse> redirected = routing.redirectToZone(REDIRECT);

        assertThat(selected).isCompletedExceptionally();
        assertThat(redirected).isCompletedExceptionally();
        assertThatThrownBy(() -> selected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage(TravelConfiguration.PLACEHOLDER_REASON);
        assertThatThrownBy(() -> redirected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(IllegalStateException.class).hasMessage("5.4 施工中");
        assertThat(routing.selectTravelTarget(TRAVEL)).as("不共用同一个 future：调用方往上挂回调不会互相影响").isNotSameAs(selected);
    }

    /** 容器按构造器注入把 Dubbo 提供方建出来（它多收的那个参数由本装配提供），两个新 RPC 走到占位。 */
    @Test
    void 容器能按构造器注入建出场景目录提供方_两个新RPC经它以异常完成() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(SceneAssigner.class,
                        () -> new SceneAssigner(NO_NODES, WORLD, ChannelSelector.withoutReservations(NO_NODES)))
                .withBean(SwitchTargetSelector.class,
                        () -> new SwitchTargetSelector(NO_NODES, WORLD, ChannelSelector.withoutReservations(NO_NODES)))
                .withBean(InstanceIdIssuer.class,
                        () -> new InstanceIdIssuer(SceneIdAllocator.forTesting(5, () -> true), NodeAvailability.ALL))
                .withBean(SceneDirectoryProvider.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    SceneDirectoryService service = ctx.getBean(SceneDirectoryService.class);
                    assertThat(service).isInstanceOf(SceneDirectoryProvider.class);

                    CompletableFuture<SelectTravelTargetResponse> selected = service.selectTravelTarget(TRAVEL);
                    CompletableFuture<RedirectToZoneResponse> redirected = service.redirectToZone(REDIRECT);

                    assertThat(selected).isCompletedExceptionally();
                    assertThat(redirected).isCompletedExceptionally();
                    assertThatThrownBy(() -> selected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                            .cause().isInstanceOf(IllegalStateException.class).hasMessage("5.4 施工中");
                    assertThatThrownBy(() -> redirected.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                            .cause().isInstanceOf(IllegalStateException.class).hasMessage("5.4 施工中");
                });
    }

    /** 没有选路 bean 时提供方建不出来（上下文失败）：缺了本装配进程就起不来，而不是带着一个空指针跑。 */
    @Test
    void 缺了选路bean_场景目录提供方建不出来_上下文失败() {
        new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(SceneAssigner.class,
                        () -> new SceneAssigner(NO_NODES, WORLD, ChannelSelector.withoutReservations(NO_NODES)))
                .withBean(SwitchTargetSelector.class,
                        () -> new SwitchTargetSelector(NO_NODES, WORLD, ChannelSelector.withoutReservations(NO_NODES)))
                .withBean(InstanceIdIssuer.class,
                        () -> new InstanceIdIssuer(SceneIdAllocator.forTesting(5, () -> true), NodeAvailability.ALL))
                .withBean(SceneDirectoryProvider.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("TravelRouting"));
    }

    /** 进程入口的组件扫描从 {@code com.game.scenemanager} 起，本装配所在的子包在扫描范围内（不必在别处显式 import）。 */
    @Test
    void 装配类在进程入口的组件扫描范围内() {
        assertThat(SceneManagerApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
        assertThat(TravelConfiguration.class.getPackageName())
                .startsWith(SceneManagerApplication.class.getPackageName() + ".");
        assertThat(TravelConfiguration.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class))
                .isTrue();
    }
}
