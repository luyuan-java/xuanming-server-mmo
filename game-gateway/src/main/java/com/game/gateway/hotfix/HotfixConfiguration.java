package com.game.gateway.hotfix;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 热更查询独立装配；绑定错误在启动时暴露，不需要 Redis、SQL 或 RPC。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HotfixProperties.class)
public class HotfixConfiguration {
    @Bean
    public HotfixCheckService hotfixCheckService(HotfixProperties properties) {
        return new HotfixCheckService(properties);
    }
}
