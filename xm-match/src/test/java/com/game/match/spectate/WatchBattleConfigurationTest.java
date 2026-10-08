package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.port.PlayerStatusReader;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakePlacementDialer;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.TicketReader;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 163、观众 RPC 与在途执行器的<b>真装配</b>（批次 6.5 工作包 W2；占位已换掉，M22 的 163 一半关闭）。钉住：四个 bean 的类型与彼此的接线
 * （处理器的执行器就是停机时等在途 163 的那个口；在途数接到了指标；上限取配置）、处理器不再当场回、过载应答是 in-band 16004、
 * 上下文关闭之后执行器不再受理；并经装配出来的处理器走通一次 163——观众 RPC 真的经直拨器发到了落点记录指向的 battle。
 * 判定表、直拨的四种结局、执行器的许可分别见 {@code WatchBattleServiceTest} / {@code DefaultObserverDialerTest} / {@code SpectateExecutorTest}。
 */
class WatchBattleConfigurationTest {

    private static final long PLAYER = 1001;
    private static final long X = 7_000_000_163L;
    private static final SessionContext SESSION = SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst-1").setSessionId(7)
            .setZoneId(1).setPlayerId(PLAYER).setAccount("acc-1001").build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore();
    private final InMemorySpectateStore spectate = new InMemorySpectateStore(clock, tickets, placements);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeBattleNode battle = new FakeBattleNode();
    private final FakePlacementDialer placementDialer = new FakePlacementDialer(battle);

    /** 只装这一个装配类；它依赖的别的包的 bean 用替身（不加 @Configuration 类：本包的测试类会被起整个进程的测试扫描到）。 */
    private ApplicationContextRunner runner(MatchProperties.Spectate spectateProps) {
        return new ApplicationContextRunner()
                .withUserConfiguration(WatchBattleConfiguration.class)
                .withBean(MatchProperties.class,
                        () -> new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, spectateProps))
                .withBean(MatchMetrics.class, () -> metrics)
                .withBean(SpectateStore.class, () -> spectate)
                .withBean(PlacementStore.class, () -> placements)
                .withBean(PlayerStatusReader.class, () -> players)
                .withBean(TicketReader.class, () -> tickets)
                .withBean(PlacementDialer.class, () -> placementDialer);
    }

    private static ByteString bytes(Reply reply) {
        assertThat(reply).isInstanceOf(Reply.Body.class);
        return ((Reply.Body) reply).bytes();
    }

    private double inflightGauge() {
        return meters.get("xm.match.spectate.inflight").gauge().value();
    }

    @Test
    void 四个bean都是真实现_处理器的执行器就是停机时等在途163的那个口_在途上限取配置() {
        runner(new MatchProperties.Spectate(null, 3)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MatchMethodHandler.class).hasSingleBean(ObserverDialer.class).hasSingleBean(InflightWatches.class)
                    .hasSingleBean(SpectateExecutor.class).hasSingleBean(WatchBattleService.class);

            MatchMethodHandler handler = context.getBean(MatchMethodHandler.class);
            assertThat(handler).isInstanceOf(WatchBattleHandler.class);
            assertThat(handler.method()).isEqualTo(MatchMethods.WATCH_BATTLE);
            assertThat(handler.inline()).as("不再是 6.4 那个当场回的临时应答").isFalse();
            assertThat(context.getBean(ObserverDialer.class)).isInstanceOf(DefaultObserverDialer.class);

            SpectateExecutor executor = context.getBean(SpectateExecutor.class);
            assertThat(handler.executor()).as("163 跑在它自己的执行器上").isSameAs(executor);
            assertThat(context.getBean(InflightWatches.class)).as("MatchLifecycle 停机时等的就是这个执行器").isSameAs(executor);
            assertThat(executor.maxInflight()).as("xm.match.spectate.max-inflight").isEqualTo(3);
            assertThat(executor.awaitIdle(Duration.ofSeconds(5))).as("一次 163 都没受理过：立即空闲").isTrue();
        });
    }

    @Test
    void 在途上限的缺省值是128() {
        runner(null).run(context -> assertThat(context.getBean(SpectateExecutor.class).maxInflight()).isEqualTo(128));
    }

    @Test
    void 在途数接到了指标_受理时加一_结束时回到0() {
        runner(null).run(context -> {
            SpectateExecutor executor = context.getBean(SpectateExecutor.class);
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            assertThat(inflightGauge()).isZero();

            executor.execute(() -> {
                running.countDown();
                try {
                    hold.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(running.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(inflightGauge()).as("xm_match_spectate_inflight").isEqualTo(1.0);
            hold.countDown();
            assertThat(executor.awaitIdle(Duration.ofSeconds(20))).isTrue();
            assertThat(inflightGauge()).isZero();
        });
    }

    @Test
    void 过载应答是in_band的16004服务器繁忙_逐字节_并计overloaded_M22的1006不再出现() {
        runner(null).run(context -> {
            MatchMethodHandler handler = context.getBean(MatchMethodHandler.class);

            ByteString reply = bytes(handler.onOverload());

            assertThat(reply).isEqualTo(WatchBattleResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(16004).addParameters("服务器繁忙,请稍后再试")).build().toByteString());
            assertThat(meters.get("xm.match.watch.battle").tag("outcome", "overloaded").counter().count()).isEqualTo(1.0);
        });
    }

    @Test
    void 经装配出来的处理器走通一次163_观众RPC经直拨器带着硬截止发到落点记录指向的battle_标记写下_指标都记了() {
        runner(null).run(context -> {
            MatchMethodHandler handler = context.getBean(MatchMethodHandler.class);
            BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(7).setBattleInstanceId("inst-a")
                    .setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).setMode(1).setCreatedAtMs(clock.peekMs()).build();
            placements.put(placement);
            spectate.putWatchable(X, placement.getCreatedAtMs());
            battle.room(CreateBattleRequest.newBuilder().setBattleId(X).build());
            players.online(PLAYER, 1, 7);
            ByteString request = WatchBattleRequest.newBuilder().setBattleId(X).build().toByteString();

            // 处理流程会阻塞：照生产那样交给处理器自己的执行器去跑
            AtomicReference<Object> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            handler.executor().execute(() -> {
                try {
                    // 预算给得宽，下面对「超时 3 s」的断言才不受机器快慢影响
                    result.set(handler.handle(SESSION, request, Deadline.after(10_000)));
                } catch (Throwable t) {
                    result.set(t);
                } finally {
                    done.countDown();
                }
            });
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(result.get()).isInstanceOf(Reply.Body.class);
            assertThat(bytes((Reply) result.get())).as("成功：只有 battle_id，不是 6.4 的 1006")
                    .isEqualTo(WatchBattleResponse.newBuilder().setBattleId(X).build().toByteString());
            assertThat(battle.addObservers).singleElement().satisfies(add -> {
                assertThat(add.getBattleId()).isEqualTo(X);
                assertThat(add.getObserverPlayerId()).isEqualTo(PLAYER);
                assertThat(add.getObserverName()).isEqualTo("acc-1001");
                assertThat(add.getRouting().getGateInstanceId()).isEqualTo("gate-inst-1");
            });
            assertThat(placementDialer.dials).singleElement().satisfies(dialed -> {
                assertThat(dialed.placement()).isEqualTo(placement);
                assertThat(dialed.hardStop()).as("观众 RPC 用带硬截止的重载（裁决 3）").isNotNull();
                assertThat(dialed.timeout()).isEqualTo(Duration.ofSeconds(3));
            });
            assertThat(spectate.markOf(PLAYER).flatMap(SpectateRules::decodeMark).map(SpectateRules.Mark::battleId)).contains(X);
            assertThat(meters.get("xm.match.watch.battle").tag("outcome", "ok").counter().count()).isEqualTo(1.0);
            assertThat(meters.get("xm.match.observer.rpc").tag("method", "add").tag("result", "replied").counter().count()).isEqualTo(1.0);
            assertThat(context.getBean(SpectateExecutor.class).awaitIdle(Duration.ofSeconds(20))).isTrue();
        });
    }

    @Test
    void 处理器照样解析请求体_解析失败把异常交给派发器() {
        runner(null).run(context -> {
            MatchMethodHandler handler = context.getBean(MatchMethodHandler.class);
            ByteString truncated = ByteString.copyFrom(new byte[] {0x10, (byte) 0xFF});

            assertThatThrownBy(() -> handler.handle(SESSION, truncated, Deadline.after(1_000))).isInstanceOf(InvalidProtocolBufferException.class);
            assertThat(spectate.calls).as("解析在进处理流程之前").isEmpty();
        });
    }

    @Test
    void 上下文关闭之后执行器不再受理_关闭不等也不打断在途的请求() {
        AtomicReference<SpectateExecutor> held = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        runner(null).run(context -> {
            SpectateExecutor executor = context.getBean(SpectateExecutor.class);
            held.set(executor);
            executor.execute(() -> {
                running.countDown();
                try {
                    hold.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            });
            assertThat(running.await(20, TimeUnit.SECONDS)).isTrue();
        });

        // 上下文已关闭（单例销毁时调了 close）；那条在途的任务还挂着
        assertThatThrownBy(() -> held.get().execute(() -> { })).isInstanceOf(RejectedExecutionException.class).hasMessageContaining("已关闭");
        assertThat(held.get().inflight()).as("销毁时不等在途的请求（有界等待在 MatchLifecycle 的停机序列里）").isEqualTo(1);
        hold.countDown();
        assertThat(held.get().awaitIdle(Duration.ofSeconds(20))).isTrue();
        assertThat(interrupted.get()).as("关闭不打断在途的请求").isFalse();
    }
}
