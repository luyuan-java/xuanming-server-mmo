package com.game.player.store;

import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 引入本模块的进程自动得到 {@link PlayerStore}（数据源、MyBatis、事务管理器由 Spring Boot starter 装配）。
 * 表结构只由 xm-login 在启动时初始化（{@code db/xm-player-schema.sql}），其他进程只读写。
 */
@AutoConfiguration
@MapperScan(basePackageClasses = PlayerMapper.class)
public class PlayerStoreAutoConfiguration {

    /** 列名 player_id → 属性 playerId。 */
    @Bean
    public ConfigurationCustomizer xmPlayerStoreMybatisCustomizer() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    /** 事务管理器与注解式事务用的是同一个（按类型唯一），交出 / 探测的带时限事务也用它。 */
    @Bean
    @ConditionalOnMissingBean
    public PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager transactionManager) {
        return new PlayerStore(mapper, System::currentTimeMillis, transactionManager);
    }
}
