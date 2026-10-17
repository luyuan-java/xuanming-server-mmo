package com.game.match.rating;

import com.game.audit.BattleResultTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.common.KafkaNames;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *       {@link BattleResultIngest} 自己不带生命周期：它以进程的评分消费启停口 {@code lifecycle.ResultConsumerControl} 的身份交给
 *       {@code MatchLifecycle}，在 Dubbo 导出、凑单启动之后才被启动。</li>
 * </ol>
 * <b>停机</b>：结果消费由 {@code MatchLifecycle} 在等完在途 gather 之后停（{@code SmartLifecycle} 先于一切 bean 销毁），然后 Spring 按依赖逆序
 * 销毁清理线程、读口线程池，最后才关数据源。
 */
@Configuration(proxyBeanMethods = false)
public class RatingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RatingConfiguration.class);

    /** 两张评分表已就绪的标记；依赖它的 bean 一定排在建表之后。 */
    public record SchemaReady() {
    }

    /**
     * 启动第 6 步：两张评分表经 game-pbmysql 建表 / 只扩不缩地同步；结构漂移即启动失败，由人工处理。
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

    public RatingStore ratingStore(DataSource dataSource, MatchProperties props, MatchMetrics metrics, SchemaReady matchRatingSchema) {
        return new RatingStore(RatingStore.connections(dataSource), props.rating()::drawRoundCapFor, metrics, System::currentTimeMillis);
    }
    @Bean(name="ratingStore")
    public RatingStore configuredRatingStore(DataSource dataSource,MatchProperties props,MatchMetrics metrics,SchemaReady ready,
            ObjectProvider<com.game.match.global.RatingRankOutbox> outbox){return ratingStore(dataSource,props,metrics,ready).withCommitHook(outbox.getIfAvailable());}

    /** 读评分（{@link RatingReader} 的唯一实现）：自带 {@code match-db} 线程池，失败回落 1500。 */
    @Bean(destroyMethod = "close")
    public JdbcRatingReader ratingReader(RatingStore ratingStore) {
        return new JdbcRatingReader(ratingStore);
    }

    /** 入账标记的保留期清理（每小时一轮，保留 {@link RatingCleanup#RETENTION}）。{@code game.match.rating.enabled = false} 时也照常清。 */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RatingCleanup ratingCleanup(RatingStore ratingStore) {
        return new RatingCleanup(ratingStore, System::currentTimeMillis);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="game.match.global.enabled",havingValue="true")
    public BattleCompletionStore battleCompletionStore(DataSource source, com.game.match.global.GlobalMatchStore strongSchema)
            throws SQLException {
        BattleCompletionStore.sync(source);
        return new BattleCompletionStore(source);
    }

    /**
     * 对局结果的消费：全球模式下关闭评分仍持续归档来源；显式旧兼容模式关闭评分才停止消费。
     * 只建对象、不启动：它实现 {@code lifecycle.ResultConsumerControl}，启停由 {@code MatchLifecycle} 调它的 {@code start()} / {@code stop()}
     * （启动第 9 步；停机时在等完在途 gather 之后）。上下文里不许有第二个 {@code ResultConsumerControl}。
     * topic、消费组缺省值与两个 client.id 都由注入的 {@link KafkaNames} 给出（带部署命名空间前缀，见 {@link #kafkaIds}）。
     */
    @Bean
    public BattleResultIngest battleResultIngest(MatchProperties props, RatingStore ratingStore, MatchMetrics metrics, MatchInstance instance,
                                                 KafkaNames kafkaNames,@org.springframework.beans.factory.annotation.Value("${game.match.global.enabled:false}")boolean global,
                                                 ObjectProvider<BattleCompletionStore> sources) {
        MatchProperties.Kafka kafka = props.kafka();
        MatchProperties.Rating rating = props.rating();
        RatingKafkaIds ids = kafkaIds(rating, kafkaNames, instance.id());
        BattleResultIngest.Settings settings = new BattleResultIngest.Settings(rating.enabled(), kafka.topicGeneration(),
                kafka.replicationFactor(), kafka.initTimeout(),global);
        BattleResultConsumer.Handler handler=global
                ? sourceHandler(sources.getObject(),rating.enabled(),ratingStore::apply) : ratingStore::apply;
        BattleResultIngest ingest = new BattleResultIngest(settings, new BattleResultTopics(kafkaNames),
                () -> new KafkaTopicAdmin(kafka.bootstrapServers(), ids.adminClientId()),
                BattleResultIngest.kafkaConsumers(kafka.bootstrapServers(), ids.group(), ids.clientId()),
                handler, metrics);
        ingest.requireDurableTopicBeforeAdmission();
        log.info("评分回流 enabled={} topic={} group={} 回合打满按平局阈值={} 覆盖={}", rating.enabled(), ingest.topic(), ids.group(),
                rating.drawRoundCap(), rating.drawRoundCapByConfigId());
        if (!ids.group().startsWith(kafkaNames.prefix())) {
            log.warn("对局结果的消费组 {} 不以部署命名空间前缀 {} 开头：与共用同一 Kafka 的别的部署同名时会互相触发重平衡，将来按前缀配 ACL 时会授权失败",
                    ids.group(), kafkaNames.prefix());
        }
        return ingest;
    }

    /** 来源先归档，评分随后可选执行；两笔均成功才允许消费循环提交。 */
    static BattleResultConsumer.SourceHandler sourceHandler(BattleCompletionStore source,boolean ratingEnabled,
                                                           BattleResultConsumer.Handler rating) {
        return received -> {
            source.archive(received);
            if(ratingEnabled)rating.handle(received.event());
        };
    }

    /**
     * 评分消费用到的 Kafka 名字。
     *
     * @param group         实际生效的消费组：配了 {@code game.match.rating.consumer-group} 用配置值，没配用 {@code <ns>-match-rating}
     * @param clientId      消费者的 client.id：{@code <ns>-match-rating-<实例 id>}
     * @param adminClientId 核对对局结果 topic 用的 admin 客户端的 client.id：{@code <ns>-match-admin}
     */
    record RatingKafkaIds(String group, String clientId, String adminClientId) {
    }

    /**
     * 由配置与注入的 {@link KafkaNames} 算出评分消费的 Kafka 名字（纯函数）。缺省命名空间、没配消费组时即 {@code game-match-rating} /
     * {@code game-match-rating-<实例 id>} / {@code game-match-admin}，与引入命名空间之前逐字节相同。
     */
    static RatingKafkaIds kafkaIds(MatchProperties.Rating rating, KafkaNames kafkaNames, String instanceId) {
        String group = rating.consumerGroup() != null ? rating.consumerGroup() : kafkaNames.matchRatingGroup();
        return new RatingKafkaIds(group, kafkaNames.matchRatingClientId(instanceId), kafkaNames.matchAdminClientId());
    }
}
