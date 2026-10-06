package com.game.match.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.id.Snowflake;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.rating.RatingReader;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FixedRatingReader;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.RedissonTicketStore;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 票据存储与排队入口的装配：存储 / 自愈 / 三个处理器各一个 bean；构造时不碰 Redis；本包向别的包要的两样（评分读取、开局入口）缺了就起不来——
 * 不许静默地少挂几个处理器（那样 157 会变成信封 1003 而没人发现）。
 */
class QueueConfigurationTest {

    private final RedissonClient redis = mock(RedissonClient.class);

    /** 基础设施装配与别的包提供的 bean 的替身；{@code withRating} / {@code withGather} 控制那两样在不在。 */
    private ApplicationContextRunner runner(boolean withRating, boolean withGather) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(QueueConfiguration.class)
                .withBean(RedissonClient.class, () -> redis)
                .withBean(MatchProperties.class, () -> new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null))
                .withBean(PlayerStatusReader.class, FakePlayerStatus::new)
                .withBean(MatchIds.class, () -> new MatchIds(new Snowflake(7), () -> true, () -> false))
                .withBean(MatchMetrics.class, () -> new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false)));
        if (withRating) {
            runner = runner.withBean(RatingReader.class, FixedRatingReader::new);
        }
        if (withGather) {
            runner = runner.withBean(GatherLauncher.class, FakeGatherLauncher::new);
        }
        return runner;
    }

    @Test
    void 存储_自愈_服务与三个处理器各一个bean_构造时不碰Redis() {
        runner(true, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TicketStore.class)).isInstanceOf(RedissonTicketStore.class);
            assertThat(context.getBean(TicketReader.class)).as("只读口就是同一个存储").isSameAs(context.getBean(TicketStore.class));
            assertThat(context.getBean(TicketHealing.class)).isInstanceOf(DefaultTicketHealing.class);
            assertThat(context).hasSingleBean(QueueService.class);
            assertThat(context.getBeansOfType(MatchMethodHandler.class).values()).extracting(MatchMethodHandler::method)
                    .containsExactlyInAnyOrder("JoinQueue", "CancelQueue", "GetQueueStatus");
            assertThat(context.getBeansOfType(MatchMethodHandler.class).values()).noneMatch(MatchMethodHandler::inline);
            verifyNoInteractions(redis);
        });
    }

    @Test
    void 缺评分读取或开局入口_上下文起不来_不会静默少挂处理器() {
        runner(false, true).run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("RatingReader"));
        runner(true, false).run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining("GatherLauncher"));
    }
}
