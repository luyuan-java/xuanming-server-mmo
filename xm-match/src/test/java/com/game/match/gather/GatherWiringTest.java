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
import com.game.match.ticket.TicketStore;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

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
            return new MatchProperties(null, null, null, null, null, null, null, null, FingerprintMode.ENFORCE, 3, null, null);
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
