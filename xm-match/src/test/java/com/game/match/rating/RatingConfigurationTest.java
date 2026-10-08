package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.id.Snowflake;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 评分包的装配（{@code ApplicationContextRunner}：不起 Dubbo、不开端口、不连 MySQL）：{@link RatingConfiguration} 起得来，对外的
 * {@link RatingReader} 接到了真的存储上；建表是启动的一步（失败拒启）；结果消费的开关与「Kafka 不可达照常启动」。
 */
class RatingConfigurationTest {

    /** 没有人监听的端口：Kafka 不可达。 */
    private static final String DEAD_KAFKA = "127.0.0.1:1";
    private static final int A_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN_VALUE;
    private static final int B_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN_VALUE;

    private static MatchProperties props(boolean ratingEnabled) {
        return new MatchProperties(null, null, null, null, null, null,
                new MatchProperties.Rating(ratingEnabled, null, null, Map.of(7, 300), null), null, null, null, null,
                new MatchProperties.Kafka(DEAD_KAFKA, 9644, null, Duration.ofSeconds(1)));
    }

    private ApplicationContextRunner runner(boolean ratingEnabled) {
        String database = "rating-config-" + UUID.randomUUID();
        return new ApplicationContextRunner()
                .withUserConfiguration(RatingConfiguration.class)
                .withBean(DataSource.class, () -> RatingTestDatabase.h2DataSource(database))
                .withBean(MatchProperties.class, () -> props(ratingEnabled))
                .withBean(MatchMetrics.class, () -> new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false)))
                .withBean(MatchIds.class, () -> new MatchIds(new Snowflake(1), () -> true, () -> false))
                .withBean(MatchInstance.class, () -> new MatchInstance(UUID.randomUUID().toString()));
    }

    @Test
    void 装配起得来_建表先于一切_读口接到真的存储上() {
        AtomicInteger synced = new AtomicInteger();
        MatchRatingTables.SchemaSync h2 = dataSource -> {
            synced.incrementAndGet();
            RatingTestDatabase.H2_SCHEMA.sync(dataSource);
        };

        runner(false).withBean(MatchRatingTables.SchemaSync.class, () -> h2).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(synced.get()).as("启动时建表一次").isEqualTo(1);
            assertThat(context).hasSingleBean(RatingConfiguration.SchemaReady.class).hasSingleBean(RatingStore.class)
                    .hasSingleBean(RatingCleanup.class).hasSingleBean(BattleResultIngest.class);
            assertThat(context.getBeansOfType(RatingReader.class)).as("别的包注入的就是这一个").hasSize(1);
            RatingReader reader = context.getBean(RatingReader.class);
            assertThat(reader).isInstanceOf(JdbcRatingReader.class);

            RatingStore store = context.getBean(RatingStore.class);
            assertThat(reader.loadCentiOrDefault(1001)).as("表是空的：新号 1500").isEqualTo(150_000);
            store.apply(EloRulesTest.event(MatchModes.ONE_V_ONE, A_WIN, List.of(1001L), List.of(1002L)).setBattleId(9001).build());
            assertThat(reader.loadAllCentiOrDefault(List.of(1001L, 1002L, 1003L)))
                    .containsExactly(Map.entry(1001L, 151_600L), Map.entry(1002L, 148_400L), Map.entry(1003L, 150_000L));
        });
    }

    @Test
    void 回合打满的阈值取自配置() {
        runner(false).withBean(MatchRatingTables.SchemaSync.class, () -> RatingTestDatabase.H2_SCHEMA).run(context -> {
            RatingStore store = context.getBean(RatingStore.class);

            RatingStore.Result capped = store.apply(
                    EloRulesTest.event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L)).setBattleId(1).setTotalRounds(30).build());
            RatingStore.Result overridden = store.apply(EloRulesTest.event(MatchModes.ONE_V_ONE, B_WIN, List.of(3L), List.of(4L)).setBattleId(2)
                    .setTotalRounds(30).setBattleConfigId(7).build());

            assertThat(capped.roundCapDraw()).as("缺省阈值 30").isTrue();
            assertThat(overridden.roundCapDraw()).as("副本 7 覆盖成 300").isFalse();
        });
    }

    @Test
    void 没有另给建表方式_走pbmysql_同步不了就拒绝启动() {
        // H2 没有 GET_LOCK：pbmysql 的结构同步必然失败——证明缺省走的是 pbmysql，且建表失败不会被吞掉
        runner(false).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(SQLException.class).hasStackTraceContaining("matchRatingSchema");
        });
    }

    @Test
    void 评分开关关闭_结果消费不启动() {
        runner(false).withBean(MatchRatingTables.SchemaSync.class, () -> RatingTestDatabase.H2_SCHEMA).run(context -> {
            BattleResultIngest ingest = context.getBean(BattleResultIngest.class);

            assertThat(ingest.isRunning()).isFalse();
            assertThat(ingest.consuming()).isFalse();
            assertThat(ingest.topic()).isEqualTo("xm-battle-result-g9644");
        });
    }

    @Test
    void Kafka不可达_上下文照常启动_结果消费在后台等重试() {
        long started = System.nanoTime();

        runner(true).withBean(MatchRatingTables.SchemaSync.class, () -> RatingTestDatabase.H2_SCHEMA).run(context -> {
            long startupMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(context).hasNotFailed();
            BattleResultIngest ingest = context.getBean(BattleResultIngest.class);

            assertThat(ingest.isRunning()).as("已启动，在后台每 30 s 重试核对 topic").isTrue();
            assertThat(ingest.consuming()).as("没核对通过之前不消费").isFalse();
            assertThat(startupMs).as("第一次核对至多等 init-timeout（这里 1 s），不拖住启动").isLessThan(20_000);
            assertThat(context.getBean(RatingReader.class).loadCentiOrDefault(1)).as("评分读口不受 Kafka 影响").isEqualTo(150_000);
        });
    }
}
