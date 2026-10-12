package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.gather.GatherHooks;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 164、开局钩子与观战清扫的装配（{@link WatchableConfiguration}）：三个 bean 都是真实现、按接口类型给出，接在同一个 {@link SpectateStore} 与
 * {@link ObserverDialer} 上；清扫器只建不起（启停归 {@code MatchLifecycle}），带一个幂等的 {@code destroyMethod} 兜底。
 * 各自的真语义在 {@code WatchableListServiceTest} / {@code ListWatchableHandlerTest} / {@code SpectateGatherHooksTest} / {@code SpectateSweeperTest}；
 * 整个进程的装配（十个号都有处理器、各接口恰好一个 bean、启停次序）在 {@code MatchApplicationContextTest}。
 */
class WatchableConfigurationTest {

    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final SessionContext NOT_IN_GAME = SessionContext.newBuilder().setGateNodeId(1).setSessionId(8).build();

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore();
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements);
    private final FakeObserverDialer dialer = new FakeObserverDialer();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final WatchableConfiguration configuration = new WatchableConfiguration();

    private static MatchProperties props(Duration sweepInterval) {
        return new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null,
                new MatchProperties.Spectate(sweepInterval, null));
    }

    private static BattlePlacement placement(long battleId, long createdAtMs) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-7").setRpcHost("127.0.0.1")
                .setRpcPort(21207).setAttempt(1).setMode(3).addPlayerNames("甲").setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000)
                .build();
    }

    @Test
    void 处理器是真的164_不当场回_不带自己的执行器_接在给它的存储上() throws Exception {
        MatchMethodHandler handler = configuration.listWatchableBattlesHandler(configuration.watchableListService(store, metrics), metrics);
        placements.put(placement(880500, T0));
        store.putWatchable(880500, T0);

        assertThat(handler).isInstanceOf(ListWatchableHandler.class);
        assertThat(handler.method()).isEqualTo(MatchMethods.LIST_WATCHABLE_BATTLES);
        assertThat(handler.inline()).as("6.4 的临时处理器是当场回的空列表：已换掉").isFalse();
        assertThat(handler.executor()).as("跑在共用的 match-worker 上").isNull();
        assertThat(handler.onOverload()).as("164 没有 in-band 错误字段：过载只能回信封").isEqualTo(Reply.envelope(1003));

        Reply reply = handler.handle(NOT_IN_GAME, ListWatchableBattlesRequest.newBuilder().setLimit(6).build().toByteString(), Deadline.after(1_000));

        assertThat(reply).isInstanceOf(Reply.Body.class);
        assertThat(ListWatchableBattlesResponse.parseFrom(((Reply.Body) reply).bytes()).getBattlesList()).extracting(BattleWatchSummary::getBattleId)
                .as("不再是恒空的列表：读的是给它的那个存储").containsExactly(880500L);
        assertThat(store.calls).startsWith("list(6)");
    }

    @Test
    void 开局钩子是真的_开局前按落点清退观众_开局后登记进索引() {
        GatherHooks hooks = configuration.gatherHooks(store, dialer, metrics);
        placements.put(placement(880510, T0 - 5_000));
        store.putMark(1001, SpectateRules.encodeMark(880510, "0123456789abcdef"));
        BattlePlacement started = placement(880511, T0);
        placements.put(started);

        assertThat(hooks).isInstanceOf(SpectateGatherHooks.class).isNotSameAs(GatherHooks.NOOP);

        hooks.beforePrepare(List.of(1001L, 1002L));
        hooks.onStarted(started);

        assertThat(dialer.calls).singleElement().satisfies(call -> {
            assertThat(call.battleId()).isEqualTo(880510L);
            assertThat(call.observerId()).isEqualTo(1001L);
            assertThat(call.reason()).isEqualTo("enter_gather");
        });
        assertThat(store.markOf(1001)).isEmpty();
        assertThat(store.watchable()).containsExactly("880511");
    }

    @Test
    void 清扫器是真的_只建不起_间隔取配置_起了之后才清扫() throws Exception {
        store.putWatchable(880520, T0 - 360_001);
        SweeperControl idle = configuration.sweeperControl(store, metrics, props(null));
        SweeperControl sweeper = configuration.sweeperControl(store, metrics, props(Duration.ofMillis(5)));
        try {
            assertThat(sweeper).isInstanceOf(SpectateSweeper.class).isNotSameAs(SweeperControl.NOOP);
            assertThat(((SpectateSweeper) sweeper).isRunning()).as("不得自己启停：等 MatchLifecycle 在 Dubbo 导出之后起").isFalse();
            assertThat(((SpectateSweeper) idle).isRunning()).isFalse();
            assertThat(store.calls).as("构造不碰存储").isEmpty();

            sweeper.start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (store.isWatchable(880520) && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertThat(store.watchable()).as("5 ms 一轮：过期成员很快被摘掉").isEmpty();
        } finally {
            sweeper.stop();
            idle.stop();
        }
        assertThat(((SpectateSweeper) sweeper).isRunning()).isFalse();
    }

    @Test
    void 三个bean按接口类型声明_清扫器不带initMethod_带幂等的destroyMethod兜底_不用代理() throws Exception {
        Method handler = WatchableConfiguration.class.getMethod("listWatchableBattlesHandler", WatchableListService.class, MatchMetrics.class);
        Method hooks = WatchableConfiguration.class.getMethod("gatherHooks", SpectateStore.class, ObserverDialer.class, MatchMetrics.class);
        Method sweeper = WatchableConfiguration.class.getMethod("sweeperControl", SpectateStore.class, MatchMetrics.class, MatchProperties.class);

        assertThat(handler.getReturnType()).as("派发器按 MatchMethodHandler 收集").isEqualTo(MatchMethodHandler.class);
        assertThat(hooks.getReturnType()).as("开局管线按 GatherHooks 注入").isEqualTo(GatherHooks.class);
        assertThat(sweeper.getReturnType()).as("MatchLifecycle 按 SweeperControl 注入").isEqualTo(SweeperControl.class);
        Bean bean = sweeper.getAnnotation(Bean.class);
        assertThat(bean.initMethod()).as("后台件一律由 MatchLifecycle 在 Dubbo 导出之后统一起").isEmpty();
        assertThat(bean.destroyMethod()).as("上下文起到一半失败时的兜底；stop 幂等").isEqualTo("stop");
        assertThat(WatchableConfiguration.class.getAnnotation(Configuration.class).proxyBeanMethods()).isFalse();
    }

    @Test
    void 在容器里_各接口恰好一个bean_清扫器不随容器启动_容器关闭时兜底停掉它() {
        SpectateSweeper[] holder = new SpectateSweeper[1];
        new ApplicationContextRunner()
                .withUserConfiguration(WatchableConfiguration.class)
                .withBean(SpectateStore.class, () -> store)
                .withBean(ObserverDialer.class, () -> dialer)
                .withBean(MatchMetrics.class, () -> metrics)
                .withBean(MatchProperties.class, () -> props(Duration.ofSeconds(30)))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MatchMethodHandler.class).hasSingleBean(GatherHooks.class)
                            .hasSingleBean(SweeperControl.class).hasSingleBean(WatchableListService.class);
                    assertThat(context.getBean(MatchMethodHandler.class)).isInstanceOf(ListWatchableHandler.class);
                    assertThat(context.getBean(GatherHooks.class)).isInstanceOf(SpectateGatherHooks.class);
                    holder[0] = (SpectateSweeper) context.getBean(SweeperControl.class);
                    assertThat(holder[0].isRunning()).as("容器刷新完成：没有人调 start，它就不跑").isFalse();

                    holder[0].start(); // 模拟 MatchLifecycle 的启动第 8 步
                    assertThat(holder[0].isRunning()).isTrue();
                });
        assertThat(holder[0].isRunning()).as("容器关闭：destroyMethod 把它停了").isFalse();
        assertThat(store.calls).as("30 s 的间隔里一轮都没到：启停本身不碰存储").isEmpty();
    }

    @Test
    void 缺观战存储或观众直拨_容器起不来_它们是硬依赖() {
        new ApplicationContextRunner()
                .withUserConfiguration(WatchableConfiguration.class)
                .withBean(ObserverDialer.class, () -> dialer)
                .withBean(MatchMetrics.class, () -> metrics)
                .withBean(MatchProperties.class, () -> props(null))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(SpectateStore.class.getName());
                });
        new ApplicationContextRunner()
                .withUserConfiguration(WatchableConfiguration.class)
                .withBean(SpectateStore.class, () -> store)
                .withBean(MatchMetrics.class, () -> metrics)
                .withBean(MatchProperties.class, () -> props(null))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(ObserverDialer.class.getName());
                });
    }
}
