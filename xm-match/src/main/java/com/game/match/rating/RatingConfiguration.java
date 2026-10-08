package com.game.match.rating;

import com.game.audit.KafkaTopicAdmin;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评分与结果回流的装配（match-spec §5、§9.8）。对别的包只提供一个接口：{@link RatingReader}（排队入队读分、5V5 分队重读）。
 *
 * <p><b>启动</b>（顺序由 bean 依赖链钉住；任一步失败拒启）：
 * <ol>
 *   <li>建表（启动第 6 步，{@link #matchRatingSchema}）：排在发号租约之后（以 {@link MatchIds} 为参数）、Dubbo 导出之前（上下文刷新完成后才导出）；</li>
 *   <li>存储、读口（{@code match-db} 线程池）、保留期清理线程；</li>
 *   <li>结果消费（启动第 9 步，{@link BattleResultIngest}）：topic 分区数与契约不符拒启；Kafka 不可达照常启动、后台重试。
 *       {@link BattleResultIngest} 自己不带生命周期，由 {@link #battleResultIngestLifecycle} 这个挂点启停。</li>
 * </ol>
 * <b>停机</b>：结果消费先停（{@code SmartLifecycle} 先于一切 bean 销毁），然后 Spring 按依赖逆序销毁清理线程、读口线程池，最后才关数据源。
 */
@Configuration(proxyBeanMethods = false)
public class RatingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RatingConfiguration.class);

    /** 两张评分表已就绪的标记；依赖它的 bean 一定排在建表之后。 */
    public record SchemaReady() {
    }

    /**
     * 启动第 6 步：两张评分表经 xm-pbmysql 建表 / 只扩不缩地同步；结构漂移即启动失败，由人工处理。
     * 上下文里若有 {@link MatchRatingTables.SchemaSync} bean 就用它（只有不连 MySQL 的测试会提供），否则走 pbmysql。
     * {@code matchIds} 参数只为保证「占到发号租约之后才碰库」。
     */
    @Bean
    public SchemaReady matchRatingSchema(DataSource dataSource, ObjectProvider<MatchRatingTables.SchemaSync> schemaSync, MatchIds matchIds)
            throws SQLException {
        schemaSync.getIfAvailable(() -> MatchRatingTables.PBMYSQL).sync(dataSource);
        log.info("评分表已同步: {}", MatchRatingTables.NAMES);
        return new SchemaReady();
    }

    @Bean
    public RatingStore ratingStore(DataSource dataSource, MatchProperties props, MatchMetrics metrics, SchemaReady matchRatingSchema) {
        return new RatingStore(RatingStore.connections(dataSource), props.rating()::drawRoundCapFor, metrics, System::currentTimeMillis);
    }

    /** 读评分（{@link RatingReader} 的唯一实现）：自带 {@code match-db} 线程池，失败回落 1500。 */
    @Bean(destroyMethod = "close")
    public JdbcRatingReader ratingReader(RatingStore ratingStore) {
        return new JdbcRatingReader(ratingStore);
    }

    /** 入账标记的保留期清理（每小时一轮，7 天）。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RatingCleanup ratingCleanup(RatingStore ratingStore) {
        return new RatingCleanup(ratingStore, System::currentTimeMillis);
    }

    /**
     * 对局结果的消费（{@code xm.match.rating.enabled = false} 时照样提供这个 bean，{@code start()} 是空操作）。
     * 只建对象、不启动：启停经 {@link #battleResultIngestLifecycle}（或进程自己的生命周期编排）调它的 {@code start()} / {@code stop()}。
     */
    @Bean
    public BattleResultIngest battleResultIngest(MatchProperties props, RatingStore ratingStore, MatchMetrics metrics, MatchInstance instance) {
        MatchProperties.Kafka kafka = props.kafka();
        MatchProperties.Rating rating = props.rating();
        BattleResultIngest.Settings settings = new BattleResultIngest.Settings(rating.enabled(), kafka.topicGeneration(),
                kafka.replicationFactor(), kafka.initTimeout());
        BattleResultIngest ingest = new BattleResultIngest(settings,
                () -> new KafkaTopicAdmin(kafka.bootstrapServers(), "xm-match-admin"),
                BattleResultIngest.kafkaConsumers(kafka.bootstrapServers(), rating.consumerGroup(), "xm-match-rating-" + instance.id()),
                ratingStore::apply, metrics);
        log.info("评分回流 enabled={} topic={} group={} 回合打满按平局阈值={} 覆盖={}", rating.enabled(), ingest.topic(), rating.consumerGroup(),
                rating.drawRoundCap(), rating.drawRoundCapByConfigId());
        return ingest;
    }

    /**
     * 结果消费的启停挂点：缺省相位的 {@code SmartLifecycle}——上下文刷新的最后一步启动（单例都已就绪），关闭时先于一切 bean 销毁停止
     * （所以一定早于清理线程、读口线程池与数据源）。启动时 topic 与契约不符抛出的异常会让上下文刷新失败 = 进程拒绝启动。
     *
     * <p><b>这是评分包自带的临时挂点</b>：规格 §9.8 的次序（Dubbo 导出、凑单启动之后才起消费；在途 gather 等完之后才停）归进程级的生命周期编排
     * 掌握。编排接手之后删掉这个 bean、改由它直接调 {@link BattleResultIngest#start()} / {@link BattleResultIngest#stop()}（两者幂等，
     * 过渡期两处都调也无害）；消费与凑单、gather、Dubbo 没有数据上的先后依赖（只碰 Kafka 与 MySQL），所以这里早启、早停不影响正确性。
     */
    @Bean
    public IngestLifecycle battleResultIngestLifecycle(BattleResultIngest battleResultIngest) {
        return new IngestLifecycle(battleResultIngest);
    }

    /** 把 {@link BattleResultIngest} 的启停挂到 Spring 的生命周期上（见 {@link #battleResultIngestLifecycle}）。 */
    public static final class IngestLifecycle implements SmartLifecycle {

        private final BattleResultIngest ingest;

        IngestLifecycle(BattleResultIngest ingest) {
            this.ingest = Objects.requireNonNull(ingest, "ingest");
        }

        @Override
        public void start() {
            ingest.start();
        }

        @Override
        public void stop() {
            ingest.stop();
        }

        @Override
        public boolean isRunning() {
            return ingest.isRunning();
        }
    }
}
