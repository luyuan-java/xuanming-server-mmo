package com.game.player.store;

import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

/**
 * 引入本模块的进程自动得到 {@link PlayerStore}（数据源、MyBatis、事务管理器由 Spring Boot starter 装配）。
 * 表结构只由 game-login 在启动时初始化（{@code db/game-player-schema.sql}），其他进程只读写。
 */
@AutoConfiguration
@MapperScan(basePackageClasses = PlayerMapper.class)
public class PlayerStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlayerTradeStore.FreezeVerifier.class)
    public PlayerTradeStore.FreezeVerifier playerTradeFreezeVerifier() {
        return PlayerTradeStore.sqlFreezeVerifier();
    }

    /** 列名 player_id → 属性 playerId。 */
    @Bean
    public ConfigurationCustomizer gamePlayerStoreMybatisCustomizer() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    /** 事务管理器与注解式事务用的是同一个（按类型唯一），交出 / 探测的带时限事务也用它。 */
    @Bean
    @ConditionalOnMissingBean
    public PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager transactionManager) {
        return new PlayerStore(mapper, System::currentTimeMillis, transactionManager);
    }

    /** 可选验证器尚未装配时仍可持久发起/取消冻结，Confirm明确拒绝。 */
    @Bean
    @ConditionalOnMissingBean
    public PlayerTradeStore playerTradeStore(DataSource source, PlatformTransactionManager transactionManager,
            @Value("${game.login.max-players-per-account:5}") int maxPlayers,
            ObjectProvider<PlayerTradeStore.FreezeVerifier> verifier) {
        return new PlayerTradeStore(source, transactionManager, System::currentTimeMillis, maxPlayers, verifier.getIfAvailable());
    }
}
