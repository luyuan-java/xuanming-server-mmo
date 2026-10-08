package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.MatchInternalService;
import com.game.api.MatchTeamService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.match.MatchBudgets;
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
import com.game.match.activity.MatchInternalServiceImpl;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.port.PlayerStatusReader;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.team.MatchTeamServiceImpl;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.proto.Empty;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
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
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.dubbo.config.ReferenceConfig;
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
 * （等价于 xm-team / xm-guild / gate 进程）调进来。整队 / 活动两个接口的提供方是<b>真实现</b>（{@link MatchTeamServiceImpl} /
 * {@link MatchInternalServiceImpl}，组件扫描出来的那两个 bean）；只把它们背后的 I/O 换成内存替身（票据存储、玩家状态读口、开局管线）。钉住进程这一层的事：
 * <ul>
 *   <li>三个接口（{@code ClientMessageService}、{@code MatchTeamService}、{@code MatchInternalService}）都在 group {@code match} 上导出，
 *       <b>不带调用方 MAC 的调用被拒</b>、进不到业务代码；</li>
 *   <li>带 MAC 的整队四个方法经真 Triple 走通真实现：预检通过回 OK + 开战锁时长 + 各人 zone；按调用方给的票号建票；退票；</li>
 *   <li><b>{@code xm-budget-ms} 过线</b>：调用方带来的预算已过期时 {@code createTeamTickets} 回 EXPIRED、<b>什么都没写</b>；</li>
 *   <li>长挂的 {@code runTeamGather}：名单原序交给开局管线；按<b>调用级超时</b>等到结果，不受引用上的缺省超时与提供方的
 *       {@code dubbo.provider.timeout} 约束（这里故意都配成比挂起时间短）；心跳周期（2 s）远小于挂起时间（6 s）时也不会被空闲探活掐断；</li>
 *   <li><b>启动次序</b>：凑单启动的那一刻 Dubbo 端口已经在听（第 7 步先于第 8 步）；</li>
 *   <li><b>停机次序</b>：停凑单时端口还在听（先停凑单）、等在途 gather 时端口已关（后撤导出），之后才停评分消费；停机时仍挂着的
 *       {@code runTeamGather} 被切断——调用方的 future 很快异常完成，而不是等满它的调用级超时（xm-team 据此按 {@code gather_unknown} 收尾）。</li>
 * </ul>
 * 启停口（凑单、评分消费）用记事件的替身盖过真的（{@code @Primary}）：这里要看的是它们被调的时刻相对 Dubbo 端口的先后。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MatchRpcLoopbackTest {

    static final int RPC_PORT = freePort();
    static final LeaseOnlyRedis REDIS = new LeaseOnlyRedis();
    /** 启停事件（带当时 Dubbo 端口是否在听），按发生的先后。 */
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final InMemoryTicketStore TICKETS = new InMemoryTicketStore();
    static final FakePlayerStatus PLAYERS = new FakePlayerStatus();
    static final ScriptedGathers GATHERS = new ScriptedGathers();

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
                // 不消费对局结果（评分消费的启停口在这里本来就是记事件的替身）
                "--xm.match.rating.enabled=false",
                // 不装 Druid 数据源，免得上下文去连 3306：评分包要的 DataSource 由 Doubles 给一个 H2 内存库
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
        GATHERS.releaseHanging();
        if (context != null) {
            context.close(); // 停机用例已经关过时是空操作
        }
        CLIENT_MODELS.forEach(IsolatedDubboModule::close);
    }

    // ================================================================ 替身

    /**
     * 开局管线的替身：记下交来的 plan；每次 launch 的结果由当前的脚本给（缺省立即以 internal 失败）。与真实现一样 launch 不抛、future 不异常完成。
     * 停机时 {@code awaitIdle} 记一条事件（带当时 Dubbo 端口是否在听）。
     */
    static final class ScriptedGathers implements GatherLauncher {
        final List<GatherPlan> plans = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<GatherResult>> hanging = new CopyOnWriteArrayList<>();
        private volatile Function<GatherPlan, CompletableFuture<GatherResult>> script = ScriptedGathers::failNow;

        private static CompletableFuture<GatherResult> failNow(GatherPlan plan) {
            return CompletableFuture.completedFuture(GatherResult.failed(GatherOutcome.INTERNAL, 0));
        }

        /** 之后的 launch：挂 {@code delayMs} 毫秒后以 {@code battleId} 成功。 */
        void succeedAfter(long delayMs, long battleId) {
            script = plan -> CompletableFuture.supplyAsync(() -> GatherResult.success(battleId),
                    CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
        }

        /** 之后的 launch：永不完成（停机用例）。 */
        void hangForever() {
            script = plan -> {
                CompletableFuture<GatherResult> never = new CompletableFuture<>();
                hanging.add(never);
                return never;
            };
        }

        void releaseHanging() {
            hanging.forEach(future -> future.complete(GatherResult.failed(GatherOutcome.INTERNAL, 0)));
        }

        @Override
        public CompletableFuture<GatherResult> launch(GatherPlan plan) {
            plans.add(plan);
            return script.apply(plan);
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
    }

    /**
     * 外部连接与 I/O 的替身，都标 {@code @Primary} 盖过组件扫描出来的真 bean：Redis（只应答发号租约）、MySQL（H2）、票据存储（内存）、玩家状态读口、
     * 开局管线、凑单与评分消费的启停口（记事件）。两个 Dubbo 提供方<b>不</b>在这里——用的是真实现。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {

        @Bean
        RedissonClient redissonClient() {
            return REDIS.client;
        }

        @Bean
        DataSource dataSource() {
            return RatingTestDatabase.h2DataSource("xm-match-rpc-loopback");
        }

        @Bean
        MatchRatingTables.SchemaSync ratingSchemaSync() {
            return RatingTestDatabase.H2_SCHEMA;
        }

        @Bean
        @Primary
        TicketStore inMemoryTicketStore() {
            return TICKETS;
        }

        @Bean
        @Primary
        PlayerStatusReader fakePlayerStatus() {
            return PLAYERS;
        }

        @Bean
        @Primary
        GatherLauncher scriptedGatherLauncher() {
            return GATHERS;
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

    private static TeamTicketsRequest ticketsRequest(long teamId, Map<Long, String> ticketIds, List<Long> roster) {
        TeamTicketsRequest.Builder request = TeamTicketsRequest.newBuilder().setBattleConfigId(1).setTeamId(teamId).addAllRoster(roster)
                .putAllTicketIds(ticketIds);
        roster.forEach(playerId -> request.putZones(playerId, 1));
        return request.build();
    }

    private static TeamGatherRequest gatherRequest(long teamId, List<Long> roster) {
        TeamGatherRequest.Builder request = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(teamId).addAllRoster(roster);
        roster.forEach(playerId -> request.putTicketIds(playerId, "ticket-" + playerId));
        return request.build();
    }

    // ================================================================ 启动次序

    @Test
    @Order(1)
    void 启动次序_凑单与评分消费在Dubbo导出之后才起_凑单在前_两个提供方是真实现() {
        assertThat(EVENTS).containsExactly("matcher.start dubbo_port_open=true", "consumer.start dubbo_port_open=true");
        assertThat(REDIS.leaseAcquisitions()).isEqualTo(1);
        assertThat(context.getBeansOfType(MatchTeamService.class).values()).singleElement().isInstanceOf(MatchTeamServiceImpl.class);
        assertThat(context.getBeansOfType(MatchInternalService.class).values()).singleElement().isInstanceOf(MatchInternalServiceImpl.class);
    }

    // ================================================================ 鉴权

    @Test
    @Order(2)
    void 不带调用方MAC的调用_三个接口都被拒_进不到业务代码() {
        MatchTeamService team = remote(MatchTeamService.class, true, 5_000, 0);
        MatchInternalService internal = remote(MatchInternalService.class, true, 5_000, 0);
        ClientMessageService client = remote(ClientMessageService.class, true, 5_000, 0);
        PLAYERS.online(2001, 1, 7);
        int readsBefore = PLAYERS.reads.size();
        int storeCallsBefore = TICKETS.calls.size();
        int plansBefore = GATHERS.plans.size();

        assertThatThrownBy(() -> team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(1).addRoster(2001).build())
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> team.createTeamTickets(ticketsRequest(21, Map.of(2001L, "ticket-2001"), List.of(2001L))).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> team.runTeamGather(gatherRequest(21, List.of(2001L))).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> internal.startActivityBattle(StartActivityBattleRequest.newBuilder().setBattleConfigId(1).build())
                .get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> client.handle(watchCall()).get(10, TimeUnit.SECONDS))
                .as("客户端入口同样要 MAC：不是回 163 的应答").isInstanceOf(ExecutionException.class);

        assertThat(PLAYERS.reads).as("预检一项都没读").hasSize(readsBefore);
        assertThat(TICKETS.calls).as("票据存储没被碰过").hasSize(storeCallsBefore);
        assertThat(TICKETS.ticketOf(2001)).isEmpty();
        assertThat(GATHERS.plans).as("没有开局").hasSize(plansBefore);
    }

    @Test
    @Order(3)
    void 带MAC_三个接口都在group_match上可达_整队的预检_建票_退票走通真实现() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 5_000, 0);
        MatchInternalService internal = remote(MatchInternalService.class, false, 5_000, 0);
        ClientMessageService client = remote(ClientMessageService.class, false, 5_000, 0);
        PLAYERS.online(1001, 1, 7).online(1002, 2, 8);
        List<Long> roster = List.of(1002L, 1001L);
        Map<Long, String> ticketIds = Map.of(1001L, "uuid-of-1001", 1002L, "uuid-of-1002");

        TeamMatchCheckReply check = team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(1).addAllRoster(roster).build())
                .get(10, TimeUnit.SECONDS);
        TeamMatchCheckReply notOpen = team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(9).addAllRoster(roster).build())
                .get(10, TimeUnit.SECONDS);
        TeamMatchCheckReply offline = team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(1).addRoster(1001).addRoster(4040)
                .build()).get(10, TimeUnit.SECONDS);
        TeamTicketsRequest create = TeamTicketsRequest.newBuilder().setBattleConfigId(1).setTeamId(31).addAllRoster(roster)
                .putAllZones(check.getZonesMap()).putAllTicketIds(ticketIds).build();
        TeamTicketsReply tickets = team.createTeamTickets(create).get(10, TimeUnit.SECONDS);

        assertThat(check.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(check.getLockTtlSeconds()).as("两人的开战锁时长").isEqualTo(MatchBudgets.teamMatchLockSeconds(2));
        assertThat(check.getZonesMap()).containsOnly(Map.entry(1001L, 1), Map.entry(1002L, 2));
        assertThat(notOpen.getResult()).as("副本 9 没配组队人数").isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN);
        assertThat(offline.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE);
        assertThat(offline.getOffender()).isEqualTo(4040);
        assertThat(tickets.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        for (long playerId : roster) {
            Ticket ticket = TICKETS.ticketOf(playerId).orElseThrow();
            assertThat(ticket.ticketId()).as("票号是调用方给的").isEqualTo(ticketIds.get(playerId));
            assertThat(ticket.state()).isEqualTo(TicketState.MATCHED);
            assertThat(ticket.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM_VALUE);
            assertThat(ticket.teamId()).isEqualTo(31);
            assertThat(ticket.zoneId()).isEqualTo(check.getZonesMap().get(playerId));
        }

        Empty released = team.releaseTeamTickets(TeamTicketsRelease.newBuilder().putAllTicketIds(ticketIds).build()).get(10, TimeUnit.SECONDS);
        StartActivityBattleResponse started = internal.startActivityBattle(StartActivityBattleRequest.getDefaultInstance()).get(10, TimeUnit.SECONDS);
        ClientReply watch = client.handle(watchCall()).get(10, TimeUnit.SECONDS);

        assertThat(released).isEqualTo(Empty.getDefaultInstance());
        assertThat(TICKETS.ticketOf(1001)).as("按票号退掉了").isEmpty();
        assertThat(TICKETS.ticketOf(1002)).isEmpty();
        assertThat(started.getReject()).as("空请求过不了活动开战的参数校验").isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);
        assertThat(started.getBattleId()).isZero();
        assertThat(WatchBattleResponse.parseFrom(watch.getBody()).getErrorMessage().getId()).isEqualTo(1006);
    }

    // ================================================================ xm-budget-ms 过线

    @Test
    @Order(4)
    void 调用方带来的预算已过期_createTeamTickets回EXPIRED_什么都没写_预算够时同一请求建得出来() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 5_000, 0);
        List<Long> roster = List.of(1101L, 1102L);
        TeamTicketsRequest request = ticketsRequest(41, Map.of(1101L, "uuid-of-1101", 1102L, "uuid-of-1102"), roster);
        int storeCallsBefore = TICKETS.calls.size();

        TeamTicketsReply expired = MatchRpcAttachments.callWithBudget(0, () -> team.createTeamTickets(request)).get(10, TimeUnit.SECONDS);

        assertThat(expired.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(expired.getFailedPlayerId()).isZero();
        assertThat(TICKETS.calls).as("附件 xm-budget-ms = 0 经真 Triple 过线：提供方一条存储调用都没发").hasSize(storeCallsBefore);
        assertThat(TICKETS.ticketOf(1101)).isEmpty();
        assertThat(TICKETS.ticketOf(1102)).isEmpty();

        TeamTicketsReply created = MatchRpcAttachments.callWithBudget(3_000, () -> team.createTeamTickets(request)).get(10, TimeUnit.SECONDS);

        assertThat(created.getStatus()).as("对照：预算够时同一份请求建成").isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(TICKETS.ticketOf(1101).orElseThrow().ticketId()).isEqualTo("uuid-of-1101");
        assertThat(TICKETS.ticketOf(1102).orElseThrow().ticketId()).isEqualTo("uuid-of-1102");
    }

    // ================================================================ 长挂调用

    @Test
    @Order(5)
    void runTeamGather长挂3秒_名单原序交给开局管线_按调用级超时等到结果_不受引用缺省超时与提供方超时约束() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0); // 引用缺省 1 s，提供方 dubbo.provider.timeout 也是 1 s
        List<Long> roster = List.of(1203L, 1201L, 1202L); // 故意不按号排：站位顺序 = 名单顺序
        GATHERS.succeedAfter(3_000, 880_005);
        int planIndex = GATHERS.plans.size();
        long started = System.nanoTime();

        TeamGatherReply reply = MatchRpcAttachments.callWithTimeout(20_000, () -> team.runTeamGather(gatherRequest(51, roster)))
                .get(30, TimeUnit.SECONDS);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(reply.getOk()).isTrue();
        assertThat(reply.getBattleId()).isEqualTo(880_005);
        assertThat(reply.getOutcome()).isEqualTo("success");
        assertThat(elapsedMs).as("确实挂了约 3 s 才回").isBetween(2_800L, 15_000L);
        GatherPlan plan = GATHERS.plans.get(planIndex);
        assertThat(plan.members()).as("roster 顺序原样").containsExactly(1203L, 1201L, 1202L);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.battleConfigId()).isEqualTo(1);
        assertThat(plan.onFail()).as("整队开局失败全员删票").isEqualTo(FailPolicy.DELETE_ALL);
        assertThat(plan.tickets()).containsOnly(Map.entry(1201L, "ticket-1201"), Map.entry(1202L, "ticket-1202"), Map.entry(1203L, "ticket-1203"));
    }

    @Test
    @Order(6)
    void 不设调用级超时_同样的长挂调用按引用的缺省超时失败_所以调用方必须显式给() {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0);
        GATHERS.succeedAfter(3_000, 880_006);
        long started = System.nanoTime();

        assertThatThrownBy(() -> team.runTeamGather(gatherRequest(61, List.of(1301L))).get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("约 1 s 就超时，没有等到 3 s 的应答").isLessThan(2_800L);
    }

    @Test
    @Order(7)
    void 心跳周期2秒_runTeamGather挂起6秒仍正常完成_挂起的流不会被空闲探活掐断() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 2_000);
        GATHERS.succeedAfter(6_000, 880_007);
        long started = System.nanoTime();

        TeamGatherReply reply = MatchRpcAttachments.callWithTimeout(30_000, () -> team.runTeamGather(gatherRequest(71, List.of(1401L, 1402L))))
                .get(40, TimeUnit.SECONDS);

        assertThat(reply.getOk()).isTrue();
        assertThat(reply.getBattleId()).isEqualTo(880_007);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("跨过了至少两个心跳周期").isBetween(5_800L, 25_000L);
        // 同一条连接上紧接着的调用照常
        assertThat(team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(9).addRoster(7).build()).get(10, TimeUnit.SECONDS)
                .getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN);
    }

    @Test
    @Order(8)
    void gather失败时runTeamGather的应答不异常完成_ok为假并带结局的标签() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 5_000, 0);
        GATHERS.script = plan -> CompletableFuture.completedFuture(GatherResult.failed(GatherOutcome.PREPARE_FAILED, 0));

        TeamGatherReply reply = MatchRpcAttachments.callWithTimeout(10_000, () -> team.runTeamGather(gatherRequest(81, List.of(1501L))))
                .get(15, TimeUnit.SECONDS);

        assertThat(reply.getOk()).isFalse();
        assertThat(reply.getOutcome()).isEqualTo(GatherOutcome.PREPARE_FAILED.label());
        assertThat(reply.getBattleId()).isZero();
    }

    // ================================================================ 停机（必须最后跑：它把上下文关掉）

    @Test
    @Order(Integer.MAX_VALUE)
    void 停机次序_先停凑单再撤Dubbo导出_然后等gather_停评分消费_最后还租约_挂着的runTeamGather被切断() throws Exception {
        MatchTeamService team = remote(MatchTeamService.class, false, 1_000, 0);
        GATHERS.hangForever();
        CompletableFuture<TeamGatherReply> hanging = MatchRpcAttachments.callWithTimeout(120_000,
                () -> team.runTeamGather(gatherRequest(91, List.of(1601L))));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (GATHERS.hanging.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(GATHERS.hanging).as("长挂调用已经到了提供方、交给了开局管线").hasSize(1);
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
