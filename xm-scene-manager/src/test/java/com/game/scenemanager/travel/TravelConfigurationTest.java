package com.game.scenemanager.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokens;
import com.game.discovery.gate.GateSource;
import com.game.discovery.gate.RedisGateSource;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.scenemanager.ChannelSelector;
import com.game.scenemanager.InstanceIdIssuer;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneDirectoryProvider;
import com.game.scenemanager.SceneIdAllocator;
import com.game.scenemanager.SceneManagerApplication;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.SwitchTargetSelector;
import com.game.scenemanager.WorldSceneConfigs;
import com.game.scenemanager.world.FakeWorldChannelStore;
import com.game.scenemanager.world.NodeAvailability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 跨 zone 选路的装配（批次 5.4，zone-travel-spec §5.4、§6.2、§11.4）：gate 令牌密钥必填、取法与 gateway / gate 逐字节相同（不去空白）；
 * 装配齐全时容器按构造器注入把 Dubbo 提供方建出来，两个新 RPC 经它走到 {@link TravelRouter}。不起 Dubbo、不连 Redis（替身）。
 */
class TravelConfigurationTest {

    private static final SelectTravelTargetRequest TRAVEL = SelectTravelTargetRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).build();
    private static final RedirectToZoneRequest REDIRECT = RedirectToZoneRequest.newBuilder()
            .setFromZoneId(1).setToZoneId(2).setPlayerId(42).build();
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1));
    private static final SceneNodeSource NO_NODES = zone -> List.of();

    /** 选路装配的全部上游依赖（Redis 客户端是替身：构造 gate 目录读取面不碰 Redis，本测试也不调它）。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TravelConfiguration.class)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(WorldChannelStore.class, FakeWorldChannelStore::new)
            .withBean(SceneNodeSource.class, () -> NO_NODES)
            .withBean(WorldSceneConfigs.class, () -> WORLD)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    /**
     * 把密钥按原值放进最前面的属性源（盖住开发机上可能已经导出的同名环境变量）。不用 {@code withPropertyValues}：它解析
     * {@code key=value} 时会去掉值两端的空白，测不出「不去空白」。
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> secret(String value) {
        return ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test-gate-secret", Map.of(TravelConfiguration.TOKEN_SECRET_ENV, value)));
    }

    @Test
    void 缺gate令牌密钥_或全是空白_拒绝启动_报错点名环境变量() {
        for (String missing : List.of("", "   ", "\t\n")) {
            runner.withInitializer(secret(missing)).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(TravelConfiguration.TOKEN_SECRET_ENV);
            });
        }
    }

    @Test
    void 环境变量名与gateway_gate是同一个键() {
        assertThat(TravelConfiguration.TOKEN_SECRET_ENV).isEqualTo("XM_GATE_TOKEN_SECRET");
    }

    /**
     * 部署模板（{@code deploy/compose/env.example}：整栈 stack.yaml 按进程最小授权注入秘密的依据，每个进程只拿清单里点了它名的）
     * 把 scene-manager 拒启必填的两把秘密都分给了 scene-manager。批次 5.4 让 gate 令牌密钥对 scene-manager 必填而模板没跟上：照模板注入，
     * scene-manager 起不来——assign 也在它上面，连单 zone 的普通进游戏都会失败。只做文本核对；找不到 {@code deploy/compose}（单独构建本模块）时跳过。
     */
    @Test
    void 部署模板的按进程秘密清单_两把必填秘密都分给了scene_manager() throws IOException {
        Path file = Files.isRegularFile(Path.of("../deploy/compose/env.example"))
                ? Path.of("../deploy/compose/env.example") : Path.of("deploy/compose/env.example");
        Assumptions.assumeTrue(Files.isRegularFile(file), "找不到 deploy/compose/env.example（单独构建 xm-scene-manager）");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        assertThat(requiredBy(lines, TravelConfiguration.TOKEN_SECRET_ENV))
                .as("gate 令牌密钥：签重定向票据的 scene-manager、签登录令牌的 gateway、验票的 gate 三方同值，缺一方拒启")
                .contains("scene-manager", "gateway", "gate");
        assertThat(requiredBy(lines, "XM_DUBBO_SECRET")).as("Dubbo 调用签名密钥").contains("scene-manager");
    }

    /**
     * 模板里 {@code 变量=} 那一行上方紧挨着的注释块（不含分节线）中「…（必填）：进程、进程；…」那一行的必填进程清单
     * （到第一个「；」为止，之后是可选的进程）。
     */
    private static List<String> requiredBy(List<String> lines, String variable) {
        int at = lines.indexOf(variable + "=");
        assertThat(at).as("env.example 里有 %s= 这一行", variable).isPositive();
        String marker = "（必填）：";
        String head = null;
        for (int i = at - 1; i >= 0 && lines.get(i).startsWith("# ") && !lines.get(i).startsWith("# ---"); i--) {
            if (lines.get(i).contains(marker)) {
                head = lines.get(i);
            }
        }
        assertThat(head).as("%s 上方的注释写了「（必填）：进程清单」", variable).isNotNull();
        String list = head.substring(head.indexOf(marker) + marker.length());
        int optional = list.indexOf('；');
        if (optional >= 0) {
            list = list.substring(0, optional);
        }
        return List.of(list.strip().split("、"));
    }

    @Test
    void 有密钥时装配齐全_选路是真实现_围栏恒放行_gate目录读Redis() {
        runner.withInitializer(secret("s")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TravelRouting.class).hasSingleBean(GateTokenIssuer.class)
                    .hasSingleBean(GateSource.class).hasSingleBean(ZoneMergeFence.class);
            assertThat(ctx.getBean(TravelRouting.class)).isInstanceOf(TravelRouter.class);
            assertThat(ctx.getBean(GateSource.class)).isInstanceOf(RedisGateSource.class);
            assertThat(ctx.getBean(ZoneMergeFence.class)).as("批次 7.3 之前没有合服").isSameAs(ZoneMergeFence.OPEN);
        });
    }

    /** 密钥按原值逐字节使用、不去首尾空白（与 xm-gateway / xm-gate 相同）：带空白的密钥签的票据，只有同样带空白的验得过。 */
    @Test
    void 密钥按原值使用_不去首尾空白() {
        String raw = "  s3cret \t";
        runner.withInitializer(secret(raw)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            GateTokenIssuer.IssuedGateToken ticket = ctx.getBean(GateTokenIssuer.class).issueRedirect(7, 2, 42, 2);
            long now = Instant.now().getEpochSecond();
            assertThat(GateTokens.ofUtf8(raw).verify(ticket.payload(), ticket.signature(), 7, 2, now).ok()).isTrue();
            assertThat(GateTokens.ofUtf8(raw.strip()).verify(ticket.payload(), ticket.signature(), 7, 2, now).failure())
                    .isEqualTo(GateTokens.Failure.BAD_SIGNATURE);
        });
    }

    /** 容器按构造器注入把 Dubbo 提供方建出来（它多收的那个参数由本装配提供），两个新 RPC 经它走到真实现。 */
    @Test
    void 容器能按构造器注入建出场景目录提供方_两个新RPC经它走到选路() {
        new ApplicationContextRunner()
                .withUserConfiguration(TravelConfiguration.class)
                .withInitializer(secret("s"))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(WorldChannelStore.class, FakeWorldChannelStore::new)
                .withBean(SceneNodeSource.class, () -> NO_NODES)
                .withBean(WorldSceneConfigs.class, () -> WORLD)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
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

                    // 这里只看「两个新 RPC 走到了真实现」（规则本身见 TravelRouterTest），挑两条不碰替身 Redis 的出口：
                    // 重定向：目标 zone 没有 scene 节点 → 3000（不读 gate 目录）
                    RedirectToZoneResponse refused = service.redirectToZone(REDIRECT).get(5, TimeUnit.SECONDS);
                    assertThat(refused.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
                    // 传送：参数错（同 zone）→ 3005，同样只走到真实现的第一步
                    SelectTravelTargetResponse bad = service.selectTravelTarget(TRAVEL.toBuilder().setToZoneId(1).build())
                            .get(5, TimeUnit.SECONDS);
                    assertThat(bad.getTipId()).isEqualTo(SceneAssigner.TIP_BAD_REQUEST);
                    MeterRegistry meters = ctx.getBean(MeterRegistry.class);
                    assertThat(meters.get(TravelRouter.REDIRECT_METRIC).tag("result", "no_scene").timer().count()).isEqualTo(1);
                    assertThat(meters.get(TravelRouter.TRAVEL_METRIC).tag("result", "bad_request").timer().count()).isEqualTo(1);
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
