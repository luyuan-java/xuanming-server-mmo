package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.MatchInternalService;
import com.game.api.MatchTeamService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.match.MatchRpcAttachments;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.proto.Empty;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.redisson.api.RedissonClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 真 Triple 回环（match-spec §15.3「Dubbo」、§9.8）：起整个 {@link MatchApplication}（真的 Dubbo 导出、带调用方鉴权过滤器），从另一个 Dubbo 框架模型
 * （等价于 xm-team / xm-guild / gate 进程）调进来。钉住进程这一层的事：
 * <ul>
 *   <li>三个接口（{@code ClientMessageService}、{@code MatchTeamService}、{@code MatchInternalService}）都在 group {@code match} 上导出，
 *       <b>不带调用方 MAC 的调用被拒</b>、进不到业务代码；</li>
 *   <li>长挂的 {@code runTeamGather}：按<b>调用级超时</b>等到结果，不受引用上的缺省超时与提供方的 {@code dubbo.provider.timeout} 约束
 *       （这里故意都配成比挂起时间短）；心跳周期（2 s）远小于挂起时间（6 s）时也不会被空闲探活掐断；</li>
 *   <li><b>启动次序</b>：凑单启动的那一刻 Dubbo 端口已经在听（第 7 步先于第 8 步）；</li>
 *   <li><b>停机次序</b>：停凑单时端口还在听（先停凑单）、等在途 gather 时端口已关（后撤导出），之后才停评分消费；停机时仍挂着的
 *       {@code runTeamGather} 被切断——调用方的 future 很快异常完成，而不是等满它的调用级超时（xm-team 据此按 {@code gather_unknown} 收尾）。</li>
 * </ul>
 *
 * <p><b>集成阶段要改的地方</b>：整队 / 活动两个接口的真实现（{@code MatchTeamServiceImpl} / {@code MatchInternalServiceImpl}）还没合入，这里先用
 * {@link Doubles} 里的两个桩把它们导出——真实现合入后同一个接口会有两个提供方，届时删掉这两个桩 bean，把「挂多久」改由开局管线的替身控制
 * （{@code FakeGatherLauncher.hold()} + 定时 {@code complete}），并补上规格同一条里依赖真实现的两项：{@code xm-budget-ms} 已过期时
 * {@code createTeamTickets} 不写；{@code runTeamGather} 的 roster 顺序。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MatchRpcLoopbackTest {

    static final int RPC_PORT = freePort();
    static final LeaseOnlyRedis REDIS = new LeaseOnlyRedis();
    /** 启停事件（带当时 Dubbo 端口是否在听），按发生的先后。 */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final StubTeamService TEAM = new StubTeamService();
    static final StubInternalService INTERNAL = new StubInternalService();

    private static final List<IsolatedDubboModule> CLIENT_MODELS = new ArrayList<>();

    /** 整个进程的上下文。自己起、自己关（不用 {@code @SpringBootTest}）：最后一个用例要亲手把它关掉来观察停机次序。 */
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void startProcess() {
        // 命令行参数的优先级高于 application.yaml 与环境变量（SpringApplicationBuilder.properties 只是缺省值，盖不住 yaml）
        context = new SpringApplicationBuilder(MatchApplication.class, Doubles.class).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--dubbo.protocol.port=" + RPC_PORT,
                "--xm.run-mode=test",
                "--xm.table-dir=../config-data/tables",
                "--xm.killswitch.enabled=false",
                "--XM_ADMIN_TOKEN=",
                // 提供方的缺省超时故意比下面的挂起时间短：长挂调用不受它约束
                "--dubbo.provider.timeout=1000",
                // Triple 心跳 2 s：挂起 6 s 的调用要跨过三个心跳周期
                "--dubbo.protocol.heartbeat=2000",
                // 还没有 bean 用 MySQL：不装数据源（评分包合入后与 MatchSkeletonContextTest 一起调整）
                "--spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dubbo 端口此刻接不接受连接。 */
    static boolean dubboPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", RPC_PORT), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @AfterAll
    static void stopProcessAndClients() {
        TEAM.releaseHanging();
        if (context != null) {
            context.close(); // 停机用例已经关过时是空操作
        }
        CLIENT_MODELS.forEach(IsolatedDubboModule::close);
    }

    // ================================================================ 替身

    /** 整队接口的桩：{@code runTeamGather} 按 {@code team_id} 毫秒数延迟完成；{@code team_id = 0} 永不完成（停机用例）。 */
    static final class StubTeamService implements MatchTeamService {
        final AtomicInteger calls = new AtomicInteger();
        final List<CompletableFuture<TeamGatherReply>> hanging = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                    .setLockTtlSeconds(101).putAllZones(Map.of(request.getRoster(0), 1)).build());
        }

        @Override
        public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED).build());
        }

        @Override
        public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
            calls.incrementAndGet();
            TeamGatherReply reply = TeamGatherReply.newBuilder().setOk(true).setOutcome(GatherOutcome.SUCCESS.label()).setBattleId(request.getTeamId())
                    .build();
            if (request.getTeamId() == 0) {
                CompletableFuture<TeamGatherReply> never = new CompletableFuture<>();
                hanging.add(never);
                return never;
            }
            return CompletableFuture.supplyAsync(() -> reply, CompletableFuture.delayedExecutor(request.getTeamId(), TimeUnit.MILLISECONDS));
        }

        void releaseHanging() {
            hanging.forEach(future -> future.complete(TeamGatherReply.getDefaultInstance()));
        }
    }

    /** 活动开战接口的桩：把 {@code battle_config_id} 当 battle_id 回显。 */
    static final class StubInternalService implements MatchInternalService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<StartActivityBattleResponse> startActivityBattle(StartActivityBattleRequest request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(StartActivityBattleResponse.newBuilder().setBattleId(request.getBattleConfigId()).build());
        }
    }

    /**
     * 外部依赖与别的包的替身。启停口与开局管线标 {@code @Primary}：别的包的真 bean 合入后这里的替身仍然生效，不必改这个类。
     * 两个 Dubbo 桩在真实现合入后要删（见类注释）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {

        @Bean
        RedissonClient redissonClient() {
            return REDIS.client;
        }

        @Bean
        @Primary
        MatcherControl recordingMatcherControl() {
            return new MatcherControl() {
                @Override
                public void start() {
                    EVENTS.add("matcher.start dubbo_port_open=" + dubboPortOpen());
                }

                @Override
                public void stop() {
                    EVENTS.add("matcher.stop dubbo_port_open=" + dubboPortOpen());
                }
            };
        }

        @Bean
        @Primary
        ResultConsumerControl recordingResultConsumerControl() {
            return new ResultConsumerControl() {
                @Override
                public void start() {
                    EVENTS.add("consumer.start dubbo_port_open=" + dubboPortOpen());
                }

                @Override
                public void stop() {
                    EVENTS.add("consumer.stop lease_released=" + (REDIS.leaseReleases() > 0));
                }
            };
        }

        @Bean
        @Primary
        GatherLauncher recordingGatherLauncher() {
            return new GatherLauncher() {
                @Override
                public CompletableFuture<GatherResult> launch(GatherPlan plan) {
                    return CompletableFuture.completedFuture(GatherResult.failed(GatherOutcome.INTERNAL, 0));
                }

                @Override
                public int availablePermits() {
                    return 256;
                }

                @Override
                public boolean awaitIdle(Duration timeout) {
                    EVENTS.add("gathers.awaitIdle dubbo_port_open=" + dubboPortOpen());
                    return true;
                }
            };
        }

        @Bean
        @DubboService(group = DubboGroups.MATCH)
        MatchTeamService stubMatchTeamService() {
            return TEAM;
        }

        @Bean
        @DubboService(group = DubboGroups.MATCH)
        MatchInternalService stubMatchInternalService() {
            return INTERNAL;
        }
    }

    // ================================================================ 调用方（各自独立的 Dubbo 模型 = 别的进程）

    /**
     * @param withoutAuth  去掉调用方 MAC 过滤器（冒充不知道密钥的调用方）
     * @param timeoutMs    引用上的缺省超时
     * @param heartbeatMs  心跳周期；0 = 不设
     */
    private static <S> S remote(Class<S> type, boolean withoutAuth, int timeoutMs, int heartbeatMs) {
        IsolatedDubboModule model = IsolatedDubboModule.create("xm-match-loopback-test-" + type.getSimpleName());
        synchronized (CLIENT_MODELS) {
            CLIENT_MODELS.add(model);
        }
        ReferenceConfig<S> reference = new ReferenceConfig<>(model.module());
        reference.setInterface(type);
        reference.setGroup(DubboGroups.MATCH);
        reference.setUrl("tri://127.0.0.1:" + RPC_PORT);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(timeoutMs);
        if (withoutAuth) {
            reference.setFilter("-xmAuthConsumer");
        }
        if (heartbeatMs > 0) {
            reference.setParameters(new java.util.HashMap<>(Map.of("heartbeat", Integer.toString(heartbeatMs))));
        }
        return reference.get();
    }

    private static ClientCall watchCall() {
        return ClientCall.newBuilder().setMessageId(163).setRequestId(1).setBody(ByteString.EMPTY)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(2).setPlayerId(1001).setAccount("acc")).build();
    }

    // ================================================================ 启动次序

    @Test
    @Order(1)
    void 启动次序_凑单与评分消费在Dubbo导出之后才起_凑单在前() {
        assertThat(EVENTS).containsExactly("matcher.start dubbo_port_open=true", "consumer.start dubbo_port_open=true");
        assertThat(REDIS.leaseAcquisitions()).isEqualTo(1);
    }

    // ================================================================ 鉴权

    @Test
    @Order(2)
    void 不带调用方MAC的调用_三个接口都被拒_进不到业务代码() {
        MatchTeamService team = remote(MatchTeamService.class, true, 5_000, 0);
        MatchInternalService internal = remote(MatchInternalService.class, true, 5_000, 0);
        ClientMessageService client = remote(ClientMessageService.class, true, 5_000, 0);
        int teamCallsBefore = TEAM.calls.get();
        int internalCallsBefore = INTERNAL.calls.get();

        assertThatThrownBy(() -> team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().addRoster(1001).build()).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> team.runTeamGather(TeamGatherRequest.newBuilder().setTeamId(1).build()).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> internal.startActivityBattle(StartActivityBattleRequest.newBuilder().setBattleConfigId(1).build())
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> client.handle(watchCall()).get(10, TimeUnit.SECONDS))
                .as("客户端入口同样要 MAC：不是回 163 的应答").isInstanceOf(ExecutionException.class);

        assertThat(TEAM.calls).hasValue(teamCallsBefore);
        assertThat(INTERNAL.calls).hasValue(internalCallsBefore);
    }

    @Test
    @Order(3)
    void 带MAC_三个接口都在group_match上可达() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 5_000, 0);
        MatchInternalService internal = remote(MatchInternalService.class, false, 5_000, 0);
        ClientMessageService client = remote(ClientMessageService.class, false, 5_000, 0);

        TeamMatchCheckReply check = team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(1).addRoster(1001).addRoster(1002).build())
                .get(10, TimeUnit.SECONDS);
        TeamTicketsReply tickets = team.createTeamTickets(TeamTicketsRequest.newBuilder().addRoster(1001).build()).get(10, TimeUnit.SECONDS);
        Empty released = team.releaseTeamTickets(TeamTicketsRelease.getDefaultInstance()).get(10, TimeUnit.SECONDS);
        StartActivityBattleResponse started = internal.startActivityBattle(StartActivityBattleRequest.newBuilder().setBattleConfigId(77).build())
                .get(10, TimeUnit.SECONDS);
        ClientReply watch = client.handle(watchCall()).get(10, TimeUnit.SECONDS);

        assertThat(check.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(check.getLockTtlSeconds()).isEqualTo(101);
        assertThat(check.getZonesMap()).containsExactly(Map.entry(1001L, 1));
        assertThat(tickets.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(released).isEqualTo(Empty.getDefaultInstance());
        assertThat(started.getBattleId()).isEqualTo(77);
        assertThat(WatchBattleResponse.parseFrom(watch.getBody()).getErrorMessage().getId()).isEqualTo(1006);
    }

    // ================================================================ 长挂调用

    @Test
    @Order(4)
    void runTeamGather长挂3秒_按调用级超时等到结果_不受引用缺省超时与提供方超时约束() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0); // 引用缺省 1 s，提供方 dubbo.provider.timeout 也是 1 s
        long started = System.nanoTime();

        TeamGatherReply reply = MatchRpcAttachments.callWithTimeout(20_000,
                () -> team.runTeamGather(TeamGatherRequest.newBuilder().setTeamId(3_000).build())).get(30, TimeUnit.SECONDS);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(reply.getOk()).isTrue();
        assertThat(reply.getBattleId()).isEqualTo(3_000);
        assertThat(reply.getOutcome()).isEqualTo("success");
        assertThat(elapsedMs).as("确实挂了约 3 s 才回").isBetween(2_800L, 15_000L);
    }

    @Test
    @Order(5)
    void 不设调用级超时_同样的长挂调用按引用的缺省超时失败_所以调用方必须显式给() {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0);
        long started = System.nanoTime();

        assertThatThrownBy(() -> team.runTeamGather(TeamGatherRequest.newBuilder().setTeamId(3_000).build()).get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("约 1 s 就超时，没有等到 3 s 的应答").isLessThan(2_800L);
    }

    @Test
    @Order(6)
    void 心跳周期2秒_runTeamGather挂起6秒仍正常完成_挂起的流不会被空闲探活掐断() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 2_000);
        long started = System.nanoTime();

        TeamGatherReply reply = MatchRpcAttachments.callWithTimeout(30_000,
                () -> team.runTeamGather(TeamGatherRequest.newBuilder().setTeamId(6_000).build())).get(40, TimeUnit.SECONDS);

        assertThat(reply.getOk()).isTrue();
        assertThat(reply.getBattleId()).isEqualTo(6_000);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("跨过了至少两个心跳周期").isBetween(5_800L, 25_000L);
        // 同一条连接上紧接着的调用照常
        assertThat(team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().addRoster(7).build()).get(10, TimeUnit.SECONDS).getResult())
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
    }

    // ================================================================ 停机（必须最后跑：它把上下文关掉）

    @Test
    @Order(Integer.MAX_VALUE)
    void 停机次序_先停凑单再撤Dubbo导出_然后等gather_停评分消费_最后还租约_挂着的runTeamGather被切断() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0);
        CompletableFuture<TeamGatherReply> hanging = MatchRpcAttachments.callWithTimeout(120_000,
                () -> team.runTeamGather(TeamGatherRequest.newBuilder().setTeamId(0).build()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (TEAM.hanging.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(TEAM.hanging).as("长挂调用已经到了提供方").hasSize(1);
        assertThat(hanging).isNotDone();
        EVENTS.clear();
        long closeStarted = System.nanoTime();

        context.close();

        long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);
        assertThat(EVENTS).as("停凑单时端口还在听；等 gather 时已撤导出；评分消费停下时租约还没还")
                .containsExactly("matcher.stop dubbo_port_open=true", "gathers.awaitIdle dubbo_port_open=false", "consumer.stop lease_released=false");
        assertThat(REDIS.leaseReleases()).as("最后交还租约（释放脚本恰好一次）").isEqualTo(1);
        assertThat(context.isActive()).isFalse();
        assertThat(dubboPortOpen()).isFalse();
        assertThatThrownBy(() -> hanging.get(15, TimeUnit.SECONDS))
                .as("挂着的调用被停机切断：很快异常完成，而不是等满 120 s 的调用级超时").isInstanceOf(ExecutionException.class);
        assertThat(closeMs).as("整个停机有界（Dubbo 的停服等待缺省 10 s）").isLessThan(40_000L);
    }
}
