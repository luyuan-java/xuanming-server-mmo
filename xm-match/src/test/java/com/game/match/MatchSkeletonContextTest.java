package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.MatchInternalService;
import com.game.api.MatchTeamService;
import com.game.api.SceneBattleService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.common.RunMode;
import com.game.contract.MessageIdRegistry;
import com.game.match.admin.DevActivityBattleController;
import com.game.match.admin.MatchAdminAuthFilter;
import com.game.match.challenge.ChallengeStore;
import com.game.match.challenge.RedissonChallengeStore;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.dispatch.MatchWorkerPool;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherHooks;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.RedisBattleNodes;
import com.game.match.gather.VirtualThreadGatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.InflightWatches;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.matcher.MatcherRunner;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.DirectPlacementDialer;
import com.game.match.placement.PlacementClients;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.placement.RedissonPlacementStore;
import com.game.match.port.IdleSweep;
import com.game.match.port.NodeCalls;
import com.game.match.port.NodeClientCache;
import com.game.match.port.NodeClientSweeper;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
import com.game.match.port.RedisClock;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.rating.BattleResultIngest;
import com.game.match.rating.JdbcRatingReader;
import com.game.match.rating.MatchRatingTables;
import com.game.match.rating.RatingReader;
import com.game.match.rating.RatingStore;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.support.MatchModes;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketStore;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 整个 xm-match 进程起得来（先行件阶段它钉的是「进程骨架」，类名沿用；批次 6.4 集成之后钉的是<b>组件扫描出来的真实装配</b>）：
 * 整个 {@link MatchApplication} 的上下文——真的 Dubbo Triple 导出（随机空闲端口，带调用方鉴权过滤器）、真的管理 Tomcat（随机端口）——
 * 只把外部连接换掉：Redis 用只应答发号租约的替身（其余读写一律失败），MySQL 换成 H2 内存库（评分两张表），不消费 Kafka。
 *
 * <p>从另一个 Dubbo 框架模型（等价于 gate / xm-team / xm-guild 进程）经真 Triple 调进来，钉住：
 * <ul>
 *   <li>契约 {@code MatchService} 的十个号都有处理器，而且经 Triple 真的走到各包的处理器：当场回的两个号按规格应答；排队三个号、补签、切磋在这个
 *       「Redis 读不出来」的上下文里按 §8.1「依赖故障」一列回（157 / 179 in-band 16004，148 / 153 信封 1003），没绑定玩家的 152 回「缺少玩家身份」；</li>
 *   <li>整队 / 活动两个内部接口与客户端入口同组导出；</li>
 *   <li>装起来的是各包的生产实现；启动完成后凑单循环在跑；评分读口读的是库，dev 读评分口与活动开战口挂在管理端口上、过了鉴权过滤器才进得去；</li>
 *   <li>指标按规格 §11 的名字经 Prometheus 端点导出；发号租约的健康组件已登记。</li>
 * </ul>
 * 启动门禁的各种拒启、启停次序见 {@code MatchApplicationContextTest}；Dubbo 导出与启停挂点的先后、长挂调用见 {@code MatchRpcLoopbackTest}。
 *
 * <p>{@code XM_DUBBO_SECRET} 由 surefire 注入（pom.xml，仅测试用的假值）。
 */
@SpringBootTest(classes = {MatchApplication.class, MatchSkeletonContextTest.ExternalDoubles.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "xm.run-mode=test",
                "xm.table-dir=../config-data/tables",
                "xm.killswitch.enabled=false",
                // 盖住开发机上可能已设置的运维令牌：dev 管理口要带这个值才进得去
                "XM_ADMIN_TOKEN=" + MatchSkeletonContextTest.ADMIN_TOKEN,
                // 不装 Druid 数据源，免得上下文去连 3306：评分包要的 DataSource 由下面的 ExternalDoubles 给一个 H2 内存库
                "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                // 不消费对局结果：本机若正好有 Kafka，测试进程不该以生产的消费组去读真的结果 topic
                "xm.match.rating.enabled=false"})
@AutoConfigureObservability(tracing = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchSkeletonContextTest {

    static final String ADMIN_TOKEN = "tok-for-process-context-test";
    static final int RPC_PORT = freePort();
    static final LeaseOnlyRedis REDIS = new LeaseOnlyRedis();
    private static final String BUSY_TEXT = "服务器繁忙,请稍后再试";

    private static IsolatedDubboModule clientModel;
    private static ClientMessageService client;

    /**
     * 外部依赖的替身：Redis（xm-discovery 的自动配置见到已有 {@code RedissonClient} 就让位）；MySQL 换成 H2 内存库，评分两张表用同一份 DDL 直接建
     * （H2 跑不了 pbmysql 的结构同步，评分的装配见到 {@code SchemaSync} bean 就用它）。<b>没有任何别的包的占位 bean</b>：其余全是组件扫描出来的真实现。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalDoubles {

        @Bean
        RedissonClient redissonClient() {
            return REDIS.client;
        }

        @Bean
        DataSource dataSource() {
            return RatingTestDatabase.h2DataSource("xm-match-process-context");
        }

        @Bean
        MatchRatingTables.SchemaSync ratingSchemaSync() {
            return RatingTestDatabase.H2_SCHEMA;
        }
    }

    @LocalServerPort
    int managementPort;

    @Autowired
    ApplicationContext context;

    @Autowired
    MatchDispatcher dispatcher;

    @Autowired
    MatchIds matchIds;

    @Autowired
    MatchMetrics metrics;

    @Autowired
    MatchProperties properties;

    @Autowired
    ObjectProvider<MatchMethodHandler> handlers;

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("dubbo.protocol.port", () -> RPC_PORT);
    }

    @AfterAll
    static void closeClient() {
        if (clientModel != null) {
            clientModel.close();
        }
    }

    /** 等价于 gate 的调用方：自己的 Dubbo 框架模型，直连 {@code tri://127.0.0.1:<端口>}，group {@code match}，不重试。 */
    private static synchronized ClientMessageService client() {
        if (client == null) {
            clientModel = IsolatedDubboModule.create("xm-match-skeleton-test-gate");
            client = reference(ClientMessageService.class);
        }
        return client;
    }

    /** 等价于 xm-team / xm-guild 的调用方：与 {@link #client()} 同一个框架模型，group {@code match}，不重试。 */
    private static synchronized <S> S internalClient(Class<S> service) {
        client();
        return reference(service);
    }

    private static <S> S reference(Class<S> service) {
        ReferenceConfig<S> reference = new ReferenceConfig<>(clientModel.module());
        reference.setInterface(service);
        reference.setGroup(DubboGroups.MATCH);
        reference.setUrl("tri://127.0.0.1:" + RPC_PORT);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5_000);
        return reference.get();
    }

    private static ClientCall call(int messageId) {
        return ClientCall.newBuilder().setMessageId(messageId).setRequestId(42)
                .setBody(JoinQueueRequest.newBuilder().setModeValue(3).build().toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1)
                        .setAccount("acc").setPlayerId(1001))
                .build();
    }

    private int messageId(String method) {
        return context.getBean(MessageIdRegistry.class).requireId(MatchMethods.SERVICE, method);
    }

    private HttpResponse<String> get(String path, boolean withToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)).timeout(Duration.ofSeconds(10));
        if (withToken) {
            request.header(MatchAdminAuthFilter.TOKEN_HEADER, ADMIN_TOKEN).header(MatchAdminAuthFilter.OPERATOR_HEADER, "tester");
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 当场回的两个号（入口派发这一包自己提供）；其余八个号的处理器在排队、补签、切磋、观战各包。163 / 164 自批次 6.5 起归观战包、不再当场回：
     * 判定表由观战包自己的测试钉，这里钉它们经真 Triple 走到处理器这一跳（这个上下文的 Redis 替身只应答发号租约，所以走的是「依赖故障」一列，
     * 见「观战两个号经真Triple走到处理器…」）。
     */
    private static final Set<String> INLINE_METHODS = Set.of(MatchMethods.NOTIFY_CHALLENGE_INVITE, MatchMethods.NOTIFY_CHALLENGE_RESULT);

    // ================================================================ 十个号

    @Test
    void 契约的十个号都有处理器_派发表与处理器bean一一对应() {
        assertThat(handlers.orderedStream().map(MatchMethodHandler::method)).as("每个方法恰好一个处理器 bean")
                .containsExactlyInAnyOrderElementsOf(MatchMethods.ALL);
        assertThat(dispatcher.handledMessageIds()).containsExactly(148, 151, 152, 153, 154, 156, 157, 163, 164, 179);
        assertThat(dispatcher.unhandledMethods()).isEmpty();
    }

    @Test
    void Dubbo已导出_经真Triple调进来_当场回的两个号按规格应答() throws Exception {
        for (String method : INLINE_METHODS) {
            int messageId = messageId(method);
            ClientReply reply = client().handle(call(messageId)).get(15, TimeUnit.SECONDS);

            assertThat(reply.getTipParametersList()).isEmpty();
            assertThat(reply.getDirectivesList()).as("match 不产生会话指令").isEmpty();
            assertThat(reply.getTipId()).as("%s（%d）", method, messageId).isZero();
            assertThat(reply.getBody().isEmpty()).as("%s：Empty", method).isTrue();
        }
    }

    /**
     * 排队三个号经真 Triple 走到处理器。这个上下文里的 Redis 替身只应答发号租约，其余读写一律失败——正好是 §8.1「依赖故障」一列：
     * 157 回 in-band 16004，没有 in-band 错误字段的 148 / 153 回信封 1003。
     */
    @Test
    void 排队三个号经真Triple走到处理器_依赖故障时157回inband16004_148与153回信封1003() throws Exception {
        ClientReply join = client().handle(call(157)).get(15, TimeUnit.SECONDS);
        ClientReply cancel = client().handle(call(148)).get(15, TimeUnit.SECONDS);
        ClientReply status = client().handle(call(153)).get(15, TimeUnit.SECONDS);

        assertThat(join.getTipId()).as("157 的失败在应答体里").isZero();
        JoinQueueResponse response = JoinQueueResponse.parseFrom(join.getBody());
        assertThat(response.getErrorCode()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);
        assertThat(response.getQueueTicket()).isEmpty();
        for (ClientReply reply : List.of(cancel, status)) {
            assertThat(reply.getTipId()).isEqualTo(1003);
            assertThat(reply.getBody().isEmpty()).isTrue();
            assertThat(reply.getTipParametersList()).isEmpty();
        }
        assertThat(join.getDirectivesList()).isEmpty();
    }

    @Test
    void 补签179经真Triple走到处理器_落点读不出来回inband的16004_不是信封() throws Exception {
        ClientCall request = call(messageId(MatchMethods.REQUEST_BATTLE_TICKET)).toBuilder()
                .setBody(RequestBattleTicketRequest.newBuilder().setBattleId(42).build().toByteString()).build();

        ClientReply reply = client().handle(request).get(15, TimeUnit.SECONDS);

        // 读落点记录失败 → §4.3 第 2 行
        assertThat(reply.getTipId()).as("in-band 错误不走信封").isZero();
        RequestBattleTicketResponse response = RequestBattleTicketResponse.parseFrom(reply.getBody());
        assertThat(response.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);
        assertThat(response.hasAssignment()).isFalse();
        assertThat(reply.getDirectivesList()).isEmpty();
    }

    /**
     * 观战两个号（批次 6.5）经真 Triple 走到观战包的处理器：Dubbo 提供方 → {@code MatchClientMessageService} → 派发器 → 163 在它自己的执行器
     * （每个请求一条虚拟线程）上完成应答 future、164 在 {@code match-worker} 上——应答都经真的 Dubbo 提供方回得出去。
     * Redis 替身读不出任何东西，正好是「依赖故障」一列（spectate-spec §4.11 给 match-spec §8.1 补的两行）：163 回 in-band 16004，
     * 没有 in-band 错误字段的 164 回信封 1003；163 的身份只认会话，没绑定玩家当场回「缺少玩家身份」（不读任何依赖）。
     */
    @Test
    void 观战两个号经真Triple走到处理器_依赖故障时163回inband16004_164回信封1003_163没绑定玩家回缺少玩家身份() throws Exception {
        int watch = messageId(MatchMethods.WATCH_BATTLE);
        int list = messageId(MatchMethods.LIST_WATCHABLE_BATTLES);
        assertThat(List.of(watch, list)).containsExactly(163, 164);
        ClientCall watchCall = call(watch).toBuilder().setBody(WatchBattleRequest.newBuilder().setBattleId(42).build().toByteString()).build();
        ClientCall unbound = watchCall.toBuilder().setSession(watchCall.getSession().toBuilder().setPlayerId(0)).build();
        ClientCall listCall = call(list).toBuilder().setBody(ListWatchableBattlesRequest.getDefaultInstance().toByteString()).build();

        ClientReply watched = client().handle(watchCall).get(15, TimeUnit.SECONDS);
        ClientReply anonymous = client().handle(unbound).get(15, TimeUnit.SECONDS);
        ClientReply listed = client().handle(listCall).get(15, TimeUnit.SECONDS);

        assertThat(watched.getTipId()).as("163 的失败在应答体里，不走信封").isZero();
        WatchBattleResponse busy = WatchBattleResponse.parseFrom(watched.getBody());
        assertThat(busy.getBattleId()).isZero();
        assertThat(busy.getErrorMessage().getId()).as("读票据与观战标记失败 → §3.1 第 2 行").isEqualTo(16004);
        assertThat(busy.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);

        assertThat(anonymous.getTipId()).isZero();
        WatchBattleResponse noIdentity = WatchBattleResponse.parseFrom(anonymous.getBody());
        assertThat(noIdentity.getBattleId()).isZero();
        assertThat(noIdentity.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(noIdentity.getErrorMessage().getParametersList()).as("§3.1 第 1 行").containsExactly("缺少玩家身份");

        assertThat(listed.getTipId()).as("164 没有 in-band 错误字段：读索引失败回信封").isEqualTo(1003);
        assertThat(listed.getBody().isEmpty()).isTrue();
        for (ClientReply reply : List.of(watched, anonymous, listed)) {
            assertThat(reply.getTipParametersList()).isEmpty();
            assertThat(reply.getDirectivesList()).as("match 不产生会话指令").isEmpty();
        }
        // 两条 163 都做完了：在途许可已归还（163 不占 match-worker，许可由它自己的执行器管）
        assertThat(context.getBean(InflightWatches.class).awaitIdle(Duration.ofSeconds(15))).isTrue();
        // 出口指标（本类只有这一条用例发 163 / 164，计数是确定的）
        String scrape = get("/actuator/prometheus", false).body();
        assertThat(scrape).as("两条 163 各记一个 internal 出口")
                .containsPattern("xm_match_watch_battle_total\\{[^}]*outcome=\"internal\"[^}]*} 2\\.0");
        assertThat(scrape).as("164 读索引失败").containsPattern("xm_match_list_watchable_total\\{[^}]*result=\"error\"[^}]*} 1\\.0");
    }

    @Test
    void 切磋经真Triple走通派发_工作池_处理器_会话没绑定玩家回in_band的16004() throws Exception {
        int challengePlayer = messageId(MatchMethods.CHALLENGE_PLAYER);
        ClientCall call = call(challengePlayer).toBuilder()
                .setBody(ChallengePlayerRequest.newBuilder().setTargetPlayerId(1002).build().toByteString())
                .setSession(call(challengePlayer).getSession().toBuilder().setPlayerId(0))
                .build();

        ClientReply reply = client().handle(call).get(15, TimeUnit.SECONDS);

        assertThat(reply.getTipId()).as("in-band：不是信封").isZero();
        ChallengePlayerResponse body = ChallengePlayerResponse.parseFrom(reply.getBody());
        assertThat(body.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(body.getErrorMessage().getParametersList()).containsExactly("缺少玩家身份");
    }

    @Test
    void 会话结束通知经Triple直接确认() throws Exception {
        Ack ack = client().sessionClosed(SessionClosed.newBuilder().setSession(call(157).getSession()).build()).get(15, TimeUnit.SECONDS);

        assertThat(ack).isEqualTo(Ack.getDefaultInstance());
    }

    // ================================================================ 整队 / 活动两个内部接口

    @Test
    void 点名开局的两个内部接口与客户端入口同组导出_经真Triple带调用方鉴权调得通() throws Exception {
        MatchTeamService team = internalClient(MatchTeamService.class);
        MatchInternalService internal = internalClient(MatchInternalService.class);

        // 两条都走不读任何依赖的分支：副本 9 没配组队人数；空请求过不了参数校验
        TeamMatchCheckReply checked = team.checkTeamMatch(TeamMatchCheckRequest.newBuilder().setBattleConfigId(9).addRoster(1001).build())
                .get(15, TimeUnit.SECONDS);
        StartActivityBattleResponse started = internal.startActivityBattle(StartActivityBattleRequest.getDefaultInstance()).get(15, TimeUnit.SECONDS);

        assertThat(checked.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN);
        assertThat(started.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);
        assertThat(started.getBattleId()).isZero();
    }

    // ================================================================ 装配与启停

    @Test
    void 应用启动完成后_凑单循环在跑_评分消费的启动步骤已执行() {
        MatchLifecycle lifecycle = context.getBean(MatchLifecycle.class);
        assertThat(lifecycle.backgroundStarted()).as("Spring Boot 的应用已启动事件到过了").isTrue();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(context.getBean(MatcherControl.class)).isInstanceOf(MatcherRunner.class);
        assertThat(context.getBean(MatcherRunner.class).isRunning()).as("启动第 8 步：凑单的调度线程起着").isTrue();
        assertThat(context.getBean(ResultConsumerControl.class)).isInstanceOf(BattleResultIngest.class);
        assertThat(context.getBean(BattleResultIngest.class).isRunning()).as("xm.match.rating.enabled=false：启动第 9 步是空操作").isFalse();
        assertThat(context.getBean(BattleResultIngest.class).topic()).as("代次缺省 1").isEqualTo("xm-battle-result-g1");
    }

    @Test
    void 基础设施与出站口齐全_发号租约有效_各包装上的都是生产实现() {
        assertThat(REDIS.leaseAcquisitions()).as("启动时申领了一次发号租约").isEqualTo(1);
        assertThat(matchIds.leaseValid()).isTrue();
        assertThat(matchIds.leaseLost()).isFalse();
        long first = matchIds.nextBattleId().orElseThrow();
        long second = matchIds.nextChallengeId().orElseThrow();
        assertThat(Long.compareUnsigned(second, first)).as("同源、递增").isPositive();

        assertThat(context.getBean(MatchWorkers.class)).as("match-worker 工作池").isInstanceOf(MatchWorkerPool.class);
        assertThat(context.getBean(GatherHooks.class)).as("开局钩子由观战包提供（恰好一个）").isNotNull();
        assertThat(context.getBean(RunMode.class)).isEqualTo(RunMode.TEST);
        assertThat(context.getBean(MatchInstance.class).id()).hasSize(36);
        assertThat(context.getBean(PlayerStatusReader.class)).isNotNull();
        assertThat(context.getBean(RedisClock.class)).isNotNull();
        assertThat(context.getBean(PlayerPusher.class)).isNotNull();
        assertThat(context.getBeanNamesForType(ResolvableType.forClassWithGenerics(NodeCalls.class, SceneBattleService.class)))
                .containsExactly("sceneBattleCalls");
        assertThat(context.getBeanNamesForType(ResolvableType.forClassWithGenerics(NodeCalls.class, BattleNodeService.class)))
                .as("补签直拨另有一份客户端缓存，但它不以 NodeCalls 的身份出现在容器里，不会被按类型注入给 gather").containsExactly("battleNodeCalls");
        // 三份直连客户端缓存（gather → scene、gather → battle、补签直拨 → battle）都在清扫名单里，清扫线程已起
        assertThat(context.getBeansOfType(IdleSweep.class).values()).extracting(IdleSweep::name)
                .containsExactlyInAnyOrder("scene-battle", "battle-node", "battle-placement");
        assertThat(context.getBeansOfType(NodeClientCache.class)).as("gather 用的两份").hasSize(2);
        assertThat(context.getBeansOfType(PlacementClients.class)).hasSize(1);
        assertThat(context.getBean(NodeClientSweeper.class).isRunning()).isTrue();
        assertThat(properties.requestBudget()).isEqualTo(Duration.ofMillis(4500));
        assertThat(properties.pveTeamSizeFor(1)).isEqualTo(5);

        // 没有任何占位：每个跨包接口恰好一个 bean，且是生产实现
        assertThat(context.getBeansOfType(TicketStore.class).values()).singleElement().isInstanceOf(RedissonTicketStore.class);
        assertThat(context.getBeansOfType(TicketHealing.class).values()).singleElement().isInstanceOf(DefaultTicketHealing.class);
        assertThat(context.getBeansOfType(RatingReader.class).values()).singleElement().isInstanceOf(JdbcRatingReader.class);
        assertThat(context.getBeansOfType(GatherLauncher.class).values()).singleElement().isInstanceOf(VirtualThreadGatherLauncher.class);
        assertThat(context.getBean(GatherLauncher.class).availablePermits()).as("在途上限取配置的缺省值").isEqualTo(256);
        assertThat(context.getBeansOfType(BattleNodes.class).values()).singleElement().isInstanceOf(RedisBattleNodes.class);
        assertThat(context.getBeansOfType(PlacementStore.class).values()).singleElement().isInstanceOf(RedissonPlacementStore.class);
        assertThat(context.getBeansOfType(PlacementDialer.class).values()).singleElement().isInstanceOf(DirectPlacementDialer.class);
        assertThat(context.getBeansOfType(MemberPrecheck.class).values()).singleElement().isInstanceOf(DefaultMemberPrecheck.class);
        assertThat(context.getBeansOfType(ChallengeStore.class).values()).singleElement().isInstanceOf(RedissonChallengeStore.class);
    }

    // ================================================================ 评分与 dev 管理口

    /**
     * 评分包在整个应用的上下文里装上了：别的包注入的 {@link RatingReader} 就是读库的那个实现；建表先于它；入账之后读口与 dev 读评分口
     * （管理端口上的真 HTTP，过了鉴权过滤器）都看得到；{@code match-db} 线程池的标准指标已导出。
     */
    @Test
    void 评分包已装上_读口读的是库_dev读评分口过了鉴权才进得去_matchdb线程池指标可见() throws Exception {
        RatingReader reader = context.getBean(RatingReader.class);
        long winner = 880_001;
        long loser = 880_002;
        assertThat(reader.loadCentiOrDefault(winner)).as("表已建好、是空的：新号 1500").isEqualTo(150_000);
        RatingStore.Result applied = context.getBean(RatingStore.class).apply(BattleResultEvent.newBuilder().setBattleId(770_001)
                .setMatchMode(MatchModes.ONE_V_ONE).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(winner))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(loser)).setTotalRounds(3).build());

        assertThat(applied.outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
        assertThat(reader.loadAllCentiOrDefault(List.of(winner, loser))).containsExactly(Map.entry(winner, 151_600L), Map.entry(loser, 148_400L));

        String path = "/admin/match/dev/rating/" + winner;
        assertThat(get(path, false).statusCode()).as("不带运维令牌：过滤器拦在控制器之前").isEqualTo(401);
        HttpResponse<String> rating = get(path, true);
        assertThat(rating.statusCode()).isEqualTo(200);
        assertThat(rating.body()).isEqualTo("{\"player_id\":\"880001\",\"rating\":\"1516.00\",\"games\":1}");

        String scrape = get("/actuator/prometheus", false).body();
        assertThat(scrape).as("读评分的线程池").containsPattern("executor_pool_core_threads\\{[^}]*name=\"match-db\"[^}]*} 8\\.0");
        assertThat(scrape).containsPattern("xm_match_rating_updates_total\\{[^}]*mode=\"MATCH_MODE_1V1\"[^}]*outcome=\"applied\"[^}]*} 1\\.0");
        assertThat(scrape).containsPattern("xm_match_rating_consumer_paused(\\{[^}]*})? 0\\.0");
        // 过滤器在 finally 里计数，排在应答写回之后：紧跟着抓指标时刚才那一次可能还没计上，所以带上限地重抓
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get("/actuator/prometheus", false).body()).as("过滤器按路径归类了这两次调用")
                        .containsPattern("xm_match_admin_requests_total\\{[^}]*op=\"rating\"[^}]*status=\"200\"[^}]*} 1\\.0")
                        .containsPattern("xm_match_admin_requests_total\\{[^}]*op=\"rating\"[^}]*status=\"401\"[^}]*} 1\\.0"));
    }

    @Test
    void dev活动开战口挂在管理端口上_过了鉴权才进得去_走与Dubbo同一个实现() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + managementPort + DevActivityBattleController.PATH);
        HttpClient http = HttpClient.newHttpClient();
        byte[] empty = StartActivityBattleRequest.getDefaultInstance().toByteArray();

        HttpResponse<byte[]> refused = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(empty)).build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> accepted = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header(MatchAdminAuthFilter.TOKEN_HEADER, ADMIN_TOKEN).header(MatchAdminAuthFilter.OPERATOR_HEADER, "tester")
                .POST(HttpRequest.BodyPublishers.ofByteArray(empty)).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(accepted.statusCode()).isEqualTo(200);
        StartActivityBattleResponse response = StartActivityBattleResponse.parseFrom(accepted.body());
        assertThat(response.getReject()).as("空请求过不了参数校验：业务拒绝一律 200 + reject").isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT);
        assertThat(response.getBattleId()).isZero();
    }

    // ================================================================ 指标与健康检查

    @Test
    void 指标按规格的名字从Prometheus端点导出() throws Exception {
        // 懒建的几条先各记一笔
        metrics.queueDepth(3, 0, 2);
        metrics.starvedAnchorWait(3, 0, 46);
        metrics.matchWait(3, 12);
        metrics.groupRatingSpread(3, 5_000);
        metrics.gatherCompleted(3, GatherOutcome.SUCCESS, Duration.ofMillis(40));
        metrics.requeued(MatchMetrics.RequeueReason.GATHER_NO_OFFENDER, 2);

        HttpResponse<String> response = get("/actuator/prometheus", false);

        assertThat(response.statusCode()).isEqualTo(200);
        String scrape = response.body();
        assertThat(scrape).contains("xm_match_requests_seconds_count{")
                .contains("xm_match_join_queue_total{")
                .contains("xm_match_queue_depth{")
                .contains("xm_match_starved_anchor_wait_seconds{")
                .contains("xm_match_wait_seconds_count{")
                .contains("xm_match_group_rating_spread_count{")
                .contains("xm_match_matcher_rounds_total{")
                .contains("xm_match_requeued_total{")
                .contains("xm_match_queue_dropped_total{")
                .contains("xm_match_queue_anomalies_total{")
                .contains("xm_match_rating_consumer_paused")
                .contains("xm_match_gathers_total{")
                .contains("xm_match_gather_seconds_count{")
                .contains("xm_match_gathers_inflight")
                .contains("xm_match_gather_zone_mix_total{")
                .contains("xm_match_table_fingerprint_mismatches_total{")
                .contains("xm_match_battle_ticket_reissues_total{")
                .contains("xm_match_challenges_total{")
                .contains("xm_match_activity_battles_total{")
                .contains("xm_match_team_calls_total{")
                .contains("xm_match_rating_updates_total{")
                .contains("xm_match_rating_round_cap_draws_total{")
                .contains("xm_match_battle_nodes{")
                .contains("xm_match_pushes_total{")
                .contains("xm_match_lease_lost")
                .contains("xm_match_admin_requests_total{");
        assertThat(scrape).as("公共标签").contains("application=\"xm-match\"");
        assertThat(scrape).as("工作池的标准指标").containsPattern("executor_pool_core_threads\\{[^}]*name=\"match-worker\"[^}]*} 16\\.0");
        assertThat(scrape).as("切磋推送回调的执行器").containsPattern("executor_pool_core_threads\\{[^}]*name=\"match-push\"[^}]*} 2\\.0");
        assertThat(scrape).as("gather 计数的标签").containsPattern("xm_match_gathers_total\\{[^}]*mode=\"MATCH_MODE_1V1\"[^}]*outcome=\"success\"[^}]*} 1\\.0");
        assertThat(scrape).as("凑单循环在这个上下文里每轮都因读不到 battle 目录而暂停；暂停的轮次不动 gauge，所以手记的 2 还在")
                .containsPattern("xm_match_queue_depth\\{[^}]*config=\"0\"[^}]*mode=\"MATCH_MODE_1V1\"[^}]*} 2\\.0");
    }

    @Test
    void 健康检查端点可用() throws Exception {
        HttpResponse<String> response = get("/actuator/health", false);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(context.getBean(HealthEndpoint.class).healthForPath("matchLease").getStatus()).as("发号租约的健康组件已登记").isEqualTo(Status.UP);
    }
}
