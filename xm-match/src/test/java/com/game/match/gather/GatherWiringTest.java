package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.SceneBattleService;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.location.SceneAssetLocator;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.DirectPlacementDialer;
import com.game.match.placement.PlacementConfiguration;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.placement.RedissonPlacementStore;
import com.game.match.port.NodeCalls;
import com.game.match.port.RedisClock;
import com.game.match.rating.RatingReader;
import com.game.match.reissue.BattleTicketReissue;
import com.game.match.reissue.ReissueConfiguration;
import com.game.match.reissue.ReissueHandler;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * gather / placement / reissue 三个包的 Spring 装配：给齐它们向别的包要的接口（票据存储、评分读取、发号、时钟、两个出站口、定位、目录、Redis）之后，
 * 三份 {@code XxxConfiguration} 能装出完整的一套，并且各个件真的接在了一起（从入口跑一次 gather、调一次 179 处理器）。
 * 少任何一个依赖，上下文都起不来——这些 bean 不做「缺了就悄悄不建」的条件装配。
 */
class GatherWiringTest {

    /** 别的包 / 基础设施提供的 bean 的替身（Redis 是只应答发号租约的替身：读目录、读写落点都会失败）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        static final GatherFixture FIXTURE = new GatherFixture();
        static final LeaseOnlyRedis REDIS = new LeaseOnlyRedis();

        @Bean
        MatchProperties matchProperties() {
            return new MatchProperties(null, null, null, null, null, null, null, null, FingerprintMode.ENFORCE, 3, null, null, null);
        }

        @Bean
        MatchMetrics matchMetrics() {
            return FIXTURE.metrics;
        }

        @Bean
        MatchIds matchIds() {
            return new MatchIds(new Snowflake(9), () -> true, () -> false);
        }

        @Bean
        RedisClock redisClock() {
            return FIXTURE.clock;
        }

        @Bean
        GatherHooks gatherHooks() {
            return FIXTURE.hooks;
        }

        @Bean
        RatingReader ratingReader() {
            return FIXTURE.ratings;
        }

        @Bean
        TicketStore ticketStore() {
            return FIXTURE.tickets;
        }

        @Bean
        NodeCalls<SceneBattleService> sceneBattleCalls() {
            return FIXTURE.sceneCalls;
        }

        @Bean
        NodeCalls<BattleNodeService> battleNodeCalls() {
            return FIXTURE.battleCalls;
        }

        @Bean
        SceneAssetLocator sceneLocator() {
            return new SceneAssetLocator(FIXTURE.players::holderAsync, FIXTURE.sceneNodes, null);
        }

        @Bean
        RedissonClient redissonClient() {
            return REDIS.client;
        }

        @Bean
        NodeDirectory<BattleNodeInfo> battleNodeDirectory(RedissonClient redis) {
            return new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, GatherConfiguration.class, PlacementConfiguration.class, ReissueConfiguration.class);

    @Test
    void 三份配置装出完整的一套_实现类与在途上限取自配置() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GatherLauncher.class)).isInstanceOf(VirtualThreadGatherLauncher.class);
            assertThat(context.getBean(GatherLauncher.class).availablePermits()).as("xm.match.gather-max-inflight").isEqualTo(3);
            assertThat(context.getBean(BattleNodes.class)).isInstanceOf(RedisBattleNodes.class);
            assertThat(context.getBean(PlacementStore.class)).isInstanceOf(RedissonPlacementStore.class);
            assertThat(context.getBean(PlacementDialer.class)).isInstanceOf(DirectPlacementDialer.class);
            assertThat(context).hasSingleBean(GatherPipeline.class).hasSingleBean(ScenePreparer.class).hasSingleBean(Compensation.class)
                    .hasSingleBean(BattleTicketReissue.class);
            assertThat(context.getBeansOfType(MatchMethodHandler.class).values()).singleElement().satisfies(handler -> {
                assertThat(handler).isInstanceOf(ReissueHandler.class);
                assertThat(handler.method()).isEqualTo(MatchMethods.REQUEST_BATTLE_TICKET);
            });
        });
    }

    @Test
    void 从入口跑一次gather_目录读不到节点_按入口策略删票_各件确实接在一起() {
        runner.run(context -> {
            GatherFixture f = Collaborators.FIXTURE;
            GatherPlan plan = f.solo(6001);

            GatherResult result = context.getBean(GatherLauncher.class).launch(plan).get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).as("替身 Redis 读不出 battle 目录").isEqualTo(GatherOutcome.NO_BATTLE_NODE);
            assertThat(result.battleId()).as("发号器接上了").isNotZero();
            assertThat(f.tickets.ticketOf(6001)).as("票据存储接上了：PVE_SOLO 失败删票").isEmpty();
            assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "no_battle_node")).isEqualTo(1.0);
        });
    }

    @Test
    void 补签处理器经装配可用_落点读失败回inband的16004_不抛() {
        runner.run(context -> {
            MatchMethodHandler handler = context.getBean(ReissueHandler.class);
            SessionContext session = SessionContext.newBuilder().setPlayerId(6001).build();

            MatchMethodHandler.Reply reply = handler.handle(session, RequestBattleTicketRequest.newBuilder().setBattleId(42).build().toByteString(),
                    Deadline.after(2_000));

            RequestBattleTicketResponse response = RequestBattleTicketResponse.parseFrom(((MatchMethodHandler.Reply.Body) reply).bytes());
            assertThat(response.getErrorMessage().getId()).isEqualTo(16004);
            assertThat(response.getErrorMessage().getParameters(0)).isEqualTo("服务器繁忙,请稍后再试");
        });
    }

    // ================================================================ 装配把配置值传对了地方（客户端可见的时限，§8.4）

    /**
     * 同 {@link Collaborators}，三处不同：每个用例一份自己的夹具（{@code withBean} 注入）；battle 目录与落点换成夹具里的替身，gather 走得到
     * 回队首与成功这些出口；<b>配置全取非缺省且互不相同的值</b>——{@code GatherConfiguration} 把它们拆成相邻的同类型参数往下传
     * （回队首 TTL 与退避是两个相邻的 long），传错位编译照过，只有经装配跑一次才看得出来。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LiveCollaborators {

        static final Duration TICKET_TTL = Duration.ofHours(1);
        static final Duration READY_TICKET_TTL = Duration.ofSeconds(30);
        static final Duration REQUEUE_BACKOFF = Duration.ofSeconds(7);

        @Bean
        MatchProperties matchProperties() {
            return new MatchProperties(null, null, null, TICKET_TTL, READY_TICKET_TTL, null, null, null, FingerprintMode.ENFORCE, 3,
                    REQUEUE_BACKOFF, null, null);
        }

        @Bean
        MatchMetrics matchMetrics(GatherFixture f) {
            return f.metrics;
        }

        @Bean
        MatchIds matchIds() {
            return new MatchIds(new Snowflake(9), () -> true, () -> false);
        }

        @Bean
        RedisClock redisClock(GatherFixture f) {
            return f.clock;
        }

        @Bean
        GatherHooks gatherHooks(GatherFixture f) {
            return f.hooks;
        }

        @Bean
        RatingReader ratingReader(GatherFixture f) {
            return f.ratings;
        }

        @Bean
        TicketStore ticketStore(GatherFixture f) {
            return f.tickets;
        }

        @Bean
        PlacementStore placementStore(GatherFixture f) {
            return f.placements;
        }

        @Bean
        NodeCalls<SceneBattleService> sceneBattleCalls(GatherFixture f) {
            return f.sceneCalls;
        }

        @Bean
        NodeCalls<BattleNodeService> battleNodeCalls(GatherFixture f) {
            return f.battleCalls;
        }

        @Bean
        SceneAssetLocator sceneLocator(GatherFixture f) {
            return new SceneAssetLocator(f.players::holderAsync, f.sceneNodes, null);
        }

        /** 只为满足 {@code GatherConfiguration.battleNodes} 的入参；管线实际用的是下面那个 {@code @Primary}。 */
        @Bean
        NodeDirectory<BattleNodeInfo> battleNodeDirectory() {
            return new NodeDirectory<>(Collaborators.REDIS.client, NodeTypes.BATTLE, BattleNodeInfo.parser());
        }

        @Bean
        @Primary
        BattleNodes fixtureBattleNodes(GatherFixture f) {
            return f.battleNodes;
        }
    }

    private final ApplicationContextRunner live = new ApplicationContextRunner().withBean(GatherFixture.class, GatherFixture::new)
            .withUserConfiguration(LiveCollaborators.class, GatherConfiguration.class);

    private static GatherResult launch(AssertableApplicationContext context, GatherPlan plan) throws Exception {
        return context.getBean(GatherLauncher.class).launch(plan).get(10, TimeUnit.SECONDS);
    }

    @Test
    void 回队首的票恢复成ticket_ttl_退避取requeue_backoff_两个值没有传错位() {
        live.run(context -> {
            assertThat(context).hasNotFailed();
            GatherFixture f = context.getBean(GatherFixture.class);
            GatherPlan plan = f.popped(GatherFixture.ONE_V_ONE, 0, 7001, 7002);
            f.battleNodes.readFailed = true; // 没有可分配的节点：无肇事者的失败，全员回队首并带退避

            GatherResult result = launch(context, plan);

            assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_BATTLE_NODE);
            for (long playerId : new long[] {7001, 7002}) {
                assertThat(f.ticket(playerId).state()).isEqualTo(TicketState.QUEUED);
                assertThat(f.tickets.ttlMs(playerId)).as("xm.match.ticket-ttl = 1 h（不是 ready 窗口的 30 s，也不是退避的 7 s）").isEqualTo(3_600_000);
                assertThat(f.ticket(playerId).notBeforeMs()).as("xm.match.requeue-backoff = 7 s（不是 1 h）").isEqualTo(f.clock.peekMs() + 7_000);
            }
            assertThat(f.tickets.queueMembers(new QueueRef(GatherFixture.ONE_V_ONE, 0))).containsExactly("7001", "7002");
        });
    }

    @Test
    void 开局成功的票置ready_TTL取ready_ticket_ttl() {
        live.run(context -> {
            GatherFixture f = context.getBean(GatherFixture.class);
            GatherPlan plan = f.popped(GatherFixture.ONE_V_ONE, 0, 7001, 7002);

            GatherResult result = launch(context, plan);

            assertThat(result.ok()).isTrue();
            assertThat(f.battleA.creates).hasSize(1);
            for (long playerId : new long[] {7001, 7002}) {
                assertThat(f.ticket(playerId).state()).isEqualTo(TicketState.READY);
                assertThat(f.ticket(playerId).battleId()).isEqualTo(result.battleId());
                assertThat(f.tickets.ttlMs(playerId)).as("xm.match.ready-ticket-ttl = 30 s（不是 1 h）").isEqualTo(30_000);
            }
        });
    }

    @Test
    void 指纹闸的模式取自配置_enforce下两人指纹不同就不开局_幸存者按ticket_ttl回队首() {
        live.run(context -> {
            GatherFixture f = context.getBean(GatherFixture.class);
            GatherPlan plan = f.popped(GatherFixture.ONE_V_ONE, 0, 7001, 7002);
            f.scene.fingerprint(7001, "fp-A").fingerprint(7002, "fp-B");

            GatherResult result = launch(context, plan);

            assertThat(result.outcome()).as("xm.match.table-fingerprint-mode = enforce（缺省的 warn 会照常开局）").isEqualTo(GatherOutcome.FINGERPRINT_MISMATCH);
            assertThat(f.battleA.creates).isEmpty();
            assertThat(f.scene.frozen()).isEmpty();
            assertThat(f.tickets.ticketOf(7002)).as("少数派是肇事者：删票").isEmpty();
            assertThat(f.ticket(7001).state()).isEqualTo(TicketState.QUEUED);
            assertThat(f.tickets.ttlMs(7001)).isEqualTo(3_600_000);
            assertThat(f.ticket(7001).notBeforeMs()).as("有肇事者：幸存者不带退避").isZero();
        });
    }

    @Test
    void 少了别的包该提供的票据存储_上下文起不来_不会悄悄少一个入口() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoTicketStore.class, GatherConfiguration.class, PlacementConfiguration.class, ReissueConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("TicketStore");
                });
    }

    /** 同 {@link Collaborators}，只是不提供票据存储。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoTicketStore {

        private final Collaborators all = new Collaborators();

        @Bean
        MatchProperties matchProperties() {
            return all.matchProperties();
        }

        @Bean
        MatchMetrics matchMetrics() {
            return all.matchMetrics();
        }

        @Bean
        MatchIds matchIds() {
            return all.matchIds();
        }

        @Bean
        RedisClock redisClock() {
            return all.redisClock();
        }

        @Bean
        GatherHooks gatherHooks() {
            return all.gatherHooks();
        }

        @Bean
        RatingReader ratingReader() {
            return all.ratingReader();
        }

        @Bean
        NodeCalls<SceneBattleService> sceneBattleCalls() {
            return all.sceneBattleCalls();
        }

        @Bean
        NodeCalls<BattleNodeService> battleNodeCalls() {
            return all.battleNodeCalls();
        }

        @Bean
        SceneAssetLocator sceneLocator() {
            return all.sceneLocator();
        }

        @Bean
        RedissonClient redissonClient() {
            return all.redissonClient();
        }

        @Bean
        NodeDirectory<BattleNodeInfo> battleNodeDirectory(RedissonClient redis) {
            return all.battleNodeDirectory(redis);
        }
    }
}
