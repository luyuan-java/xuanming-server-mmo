package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.discovery.RedisKeys;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 部署命名空间的装配（注入式，同 Spring Session 的 {@code spring.session.redis.namespace}、Spring Cache 的 key-prefix 的做法）：
 * {@link GameNamespaceProperties} 绑定并校验 {@code game.namespace}，本类据此注册三个 bean——{@link GameNamespace}、{@link RedisKeys}、
 * {@link KafkaNames}——经构造器注入给各模块的使用方；另登记 DataSource 对命名空间的依赖与库名核对 {@link NamespaceDatabaseVerifier}（应急开关
 * {@link GameMysqlProperties}）。登记在 game-discovery 的 AutoConfiguration.imports，13 个 Spring 进程都经 game-discovery 带上它。
 *
 * <p>让路规则（Spring Boot 自动配置的惯例：使用方自己定义了同类 bean 就让路）：
 * <ul>
 *   <li>{@link RedisKeys} / {@link KafkaNames} 加 {@code @ConditionalOnMissingBean}：上下文里已有同类型的 bean（测试换实现）时不再注册。main 代码里另行构造它们
 *       由 game-robot 的 {@code NamespaceLiteralGuardTest}（R3）挡住，生产上它们只有这一个出处。</li>
 *   <li>{@link GameNamespace} <b>不让路</b>：它就是配置项 {@code game.namespace} 绑定校验后的值，是命名空间的唯一来源（MySQL 库名核对、JDBC URL 的
 *       {@code ${game.namespace:game}} 都跟着它）。上下文里另定义一个 {@code GameNamespace} bean 会在注入时以 {@code NoUniqueBeanDefinitionException}
 *       拒启（报错列出两个 bean 名）；要换命名空间就设属性 {@code game.namespace}。</li>
 * </ul>
 * 三个 bean 都是非懒加载单例，所以连懒取 Redis 的进程（game-data）也在启动时完成校验。校验先于一切连接：
 * <ul>
 *   <li>Redisson 客户端依赖 {@link RedisKeys}，Redis 连接在校验之后；</li>
 *   <li>每个 DataSource 经 depends-on 依赖 {@link GameNamespace}：JDBC URL 里的 {@code ${game.namespace:game}} 只在校验通过之后才拿去建连接
 *       （否则 {@code mysql}、或带 {@code ?} / {@code &} 的值可能先拼进 URL，触发 {@code createDatabaseIfNotExist} 或建表）；</li>
 *   <li>Kafka 客户端都在注入了 {@link KafkaNames} 的模块 bean 里创建。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties({GameNamespaceProperties.class, GameMysqlProperties.class})
public class GameNamespaceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GameNamespaceAutoConfiguration.class);

    /** 本进程的部署命名空间（{@link GameNamespaceProperties} 已经校验过；这里再按值类型校验一次，作防御）。不让路，见类注释。 */
    @Bean
    public GameNamespace gameNamespace(GameNamespaceProperties properties) {
        GameNamespace ns = GameNamespace.of(properties.namespace(), GameNamespace.PROPERTY);
        log.info("部署命名空间 {}={}（Redis 前缀 {}:，Kafka 前缀 {}-，MySQL 库 {}）", GameNamespace.PROPERTY, ns, ns, ns, ns);
        return ns;
    }

    /** Redis 键与频道的唯一出处（前缀 {@code <ns>:}）。 */
    @Bean
    @ConditionalOnMissingBean
    public RedisKeys redisKeys(GameNamespace namespace) {
        return new RedisKeys(namespace);
    }

    /** Kafka topic / 消费组 / client.id 的唯一出处（前缀 {@code <ns>-}）。 */
    @Bean
    @ConditionalOnMissingBean
    public KafkaNames kafkaNames(GameNamespace namespace) {
        return new KafkaNames(namespace);
    }

    /** 每个 DataSource 都依赖 {@link GameNamespace}：命名空间校验通过之前不建连接池、不连库。静态方法：BeanFactoryPostProcessor 要先于本配置类实例化。 */
    @Bean
    static DataSourceDependsOnGameNamespace dataSourceDependsOnGameNamespace() {
        return new DataSourceDependsOnGameNamespace();
    }

    /** 连库的进程在任何使用者拿到 DataSource 之前核对库名。静态方法：BeanPostProcessor 要先于本配置类实例化。 */
    @Bean
    static NamespaceDatabaseVerifier namespaceDatabaseVerifier(ObjectProvider<GameNamespace> namespace, ObjectProvider<GameMysqlProperties> mysql) {
        return new NamespaceDatabaseVerifier(namespace, mysql);
    }

    /** 给每个 DataSource bean 加上对 {@link GameNamespace} bean 的 depends-on（Spring Boot 自带的基类）。 */
    static final class DataSourceDependsOnGameNamespace extends AbstractDependsOnBeanFactoryPostProcessor {

        DataSourceDependsOnGameNamespace() {
            super(DataSource.class, GameNamespace.class);
        }
    }
}
