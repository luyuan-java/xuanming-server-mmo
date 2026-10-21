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
    @Bean
    @ConditionalOnMissingBean(PlayerTradeStore.FreezeEligibility.class)
    public PlayerTradeStore.FreezeEligibility playerTradeFreezeEligibility(
            @Value("${game.player-trade.require-guild-exit:true}") boolean requireGuildExit) {
        return (sql, request) -> {
            // 所有合同新建/绑定买家均先持 player 锁；未知资产腿不能在角色过户后交给新账号。
            if (RolePendingFence.economicBlocked(sql,request.playerId(),request.listingId()))
                throw new PlayerTradeStore.Failure(PlayerTradeStore.Code.NOT_READY, "角色仍有未终局交易资产合同");
            if (!requireGuildExit) return;
            // player 锁在前；guild 的建帮/审批加入也先锁同一 player，再取 G/S/M。
            // 不存在 S 行时不推断离帮：player 已排斥全部未来加入者，RC 读成员原件即可裁决。
            sql.queryForList("SELECT player_id FROM guild_player_state WHERE player_id=? FOR UPDATE", request.playerId());
            if (!sql.queryForList("SELECT guild_id FROM guild_member WHERE player_id=?", request.playerId()).isEmpty())
                throw new PlayerTradeStore.Failure(PlayerTradeStore.Code.CONFLICT, "角色出售前必须离帮");
        };
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
            ObjectProvider<PlayerTradeStore.FreezeVerifier> verifier,
            ObjectProvider<PlayerTradeStore.FreezeEligibility> eligibility) {
        return new PlayerTradeStore(source, transactionManager, System::currentTimeMillis, maxPlayers,
            verifier.getIfAvailable(), eligibility.getIfAvailable());
    }
}
