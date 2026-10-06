package com.game.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.common.RunMode;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.dispatch.MatchWorkerPool;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.GatherHooks;
import com.game.match.gather.GatherOutcome;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.port.NodeCalls;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
import com.game.match.port.RedisClock;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.proto.match.JoinQueueRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 先行件的进程骨架起得来（批次 6.4 的并行开发起点）：整个 {@link MatchApplication} 的上下文——真的 Dubbo Triple 导出（随机空闲端口，
 * 带调用方鉴权过滤器）、真的管理 Tomcat（随机端口）——只把外部连接换掉：Redis 用只应答发号租约的替身，不装数据源（骨架阶段没有任何 bean 用库）。
 * 钉住的状态是「Dubbo 已导出、没有任何处理器」：从另一个 Dubbo 框架模型（等价于 gate 进程）经真 Triple 调进来，10 个号都得到信封 1003；
 * 基础设施 bean 与各出站口齐全；指标按规格 §11 的名字经 Prometheus 端点导出。
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
                // 骨架阶段没有 bean 用 MySQL：不装数据源，免得上下文去连 3306
                "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureObservability(tracing = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchSkeletonContextTest {

    static final int RPC_PORT = freePort();
    static final LeaseOnlyRedis REDIS = new LeaseOnlyRedis();

    private static IsolatedDubboModule clientModel;
    private static ClientMessageService client;

    /** 外部依赖的替身：Redis（xm-discovery 的自动配置见到已有 {@code RedissonClient} 就让位）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalDoubles {

        @Bean
        RedissonClient redissonClient() {
            return REDIS.client;
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
            ReferenceConfig<ClientMessageService> reference = new ReferenceConfig<>(clientModel.module());
            reference.setInterface(ClientMessageService.class);
            reference.setGroup(DubboGroups.MATCH);
            reference.setUrl("tri://127.0.0.1:" + RPC_PORT);
            reference.setRetries(0);
            reference.setCheck(false);
            reference.setTimeout(5_000);
            client = reference.get();
        }
        return client;
    }

    private static ClientCall call(int messageId) {
        return ClientCall.newBuilder().setMessageId(messageId).setRequestId(42)
                .setBody(JoinQueueRequest.newBuilder().setModeValue(3).build().toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst").setSessionId(9).setZoneId(1)
                        .setAccount("acc").setPlayerId(1001))
                .build();
    }

    @Test
    void 没有任何处理器_派发表是空的() {
        assertThat(handlers.orderedStream()).isEmpty();
        assertThat(dispatcher.handledMessageIds()).isEmpty();
    }

    @Test
    void Dubbo已导出_经真Triple调进来_十个号都得到信封1003() throws Exception {
        MessageIdRegistry registry = context.getBean(MessageIdRegistry.class);

        for (String method : MatchMethods.ALL) {
            int messageId = registry.requireId(MatchMethods.SERVICE, method);
            ClientReply reply = client().handle(call(messageId)).get(15, TimeUnit.SECONDS);

            assertThat(reply.getTipId()).as("%s（%d）", method, messageId).isEqualTo(1003);
            assertThat(reply.getBody().isEmpty()).as(method).isTrue();
            assertThat(reply.getTipParametersList()).isEmpty();
            assertThat(reply.getDirectivesList()).as("match 不产生会话指令").isEmpty();
        }
    }

    @Test
    void 会话结束通知经Triple直接确认() throws Exception {
        Ack ack = client().sessionClosed(SessionClosed.newBuilder().setSession(call(157).getSession()).build()).get(15, TimeUnit.SECONDS);

        assertThat(ack).isEqualTo(Ack.getDefaultInstance());
    }

    @Test
    void 基础设施与出站口齐全_发号租约有效_钩子是空实现() {
        assertThat(REDIS.leaseAcquisitions()).as("启动时申领了一次发号租约").isEqualTo(1);
        assertThat(matchIds.leaseValid()).isTrue();
        assertThat(matchIds.leaseLost()).isFalse();
        long first = matchIds.nextBattleId().orElseThrow();
        long second = matchIds.nextChallengeId().orElseThrow();
        assertThat(Long.compareUnsigned(second, first)).as("同源、递增").isPositive();

        assertThat(context.getBean(MatchWorkers.class)).as("match-worker 工作池").isInstanceOf(MatchWorkerPool.class);
        assertThat(context.getBean(GatherHooks.class)).isSameAs(GatherHooks.NOOP);
        assertThat(context.getBean(RunMode.class)).isEqualTo(RunMode.TEST);
        assertThat(context.getBean(MatchInstance.class).id()).hasSize(36);
        assertThat(context.getBean(PlayerStatusReader.class)).isNotNull();
        assertThat(context.getBean(RedisClock.class)).isNotNull();
        assertThat(context.getBean(PlayerPusher.class)).isNotNull();
        assertThat(context.getBeanNamesForType(ResolvableType.forClassWithGenerics(NodeCalls.class, SceneBattleService.class)))
                .containsExactly("sceneBattleCalls");
        assertThat(context.getBeanNamesForType(ResolvableType.forClassWithGenerics(NodeCalls.class, BattleNodeService.class)))
                .containsExactly("battleNodeCalls");
        assertThat(properties.requestBudget()).isEqualTo(Duration.ofMillis(4500));
        assertThat(properties.pveTeamSizeFor(1)).isEqualTo(5);
    }

    @Test
    void 指标按规格的名字从Prometheus端点导出() throws Exception {
        // 懒建的几条先各记一笔
        metrics.queueDepth(3, 0, 2);
        metrics.starvedAnchorWait(3, 0, 46);
        metrics.matchWait(3, 12);
        metrics.groupRatingSpread(3, 5_000);
        metrics.gatherCompleted(3, GatherOutcome.SUCCESS, Duration.ofMillis(40));
        metrics.requeued(MatchMetrics.RequeueReason.GATHER_NO_OFFENDER, 2);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());

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
        assertThat(scrape).as("gather 计数的标签").containsPattern("xm_match_gathers_total\\{[^}]*mode=\"MATCH_MODE_1V1\"[^}]*outcome=\"success\"[^}]*} 1\\.0");
        assertThat(scrape).containsPattern("xm_match_queue_depth\\{[^}]*config=\"0\"[^}]*mode=\"MATCH_MODE_1V1\"[^}]*} 2\\.0");
    }

    @Test
    void 健康检查端点可用() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
