package com.game.discovery;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConstantDelay;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * 提供 {@link RedissonClient}。不用 redisson-spring-boot-starter：它会带进 Lettuce（见选型表）。
 * 客户端懒创建（第一次注入 / 取用时才连 Redis）：常驻依赖 Redis 的服务在启动装配时就注入它，照样启动即连、连不上就拒绝启动；
 * 只在个别运维接口里用 Redis 的服务（xm-data）经 {@code ObjectProvider} 取用，Redis 不可用时不挡住启动与主业务。
 */
@AutoConfiguration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisAutoConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    @Lazy
    @ConditionalOnMissingBean
    public RedissonClient redissonClient(RedisProperties props) {
        Config config = new Config();
        configure(config.useSingleServer(), props);
        log.info("Redis 客户端 address={} db={} connect_timeout_ms={} timeout_ms={} retry_attempts={} retry_delay_ms={} "
                        + "单条命令最坏阻塞≈{}ms", props.address(), props.database(), props.connectTimeoutMs(),
                props.timeoutMs(), props.retryAttempts(), props.retryDelayMs(), props.worstCaseCommandMillis());
        return Redisson.create(config);
    }

    /** 把配置写进 Redisson 单机配置（包内可见供测试核对接线）。 */
    static SingleServerConfig configure(SingleServerConfig server, RedisProperties props) {
        return server
                .setAddress(props.address())
                .setDatabase(props.database())
                .setPassword(props.password())
                .setConnectTimeout(props.connectTimeoutMs())
                .setTimeout(props.timeoutMs())
                .setRetryAttempts(props.retryAttempts())
                .setRetryDelay(new ConstantDelay(Duration.ofMillis(props.retryDelayMs())));
    }
}
