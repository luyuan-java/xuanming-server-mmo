package com.game.gateway.store;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 引入本模块的进程自动得到 {@link GatewayStore}（数据源、MyBatis、事务模板由 Spring Boot starter 装配）。
 * 表结构只由 xm-gateway 在启动时初始化（{@code db/xm-gateway-schema.sql}），xm-data 的运维接口只读写。
 */
@AutoConfiguration
@MapperScan(basePackageClasses = GatewayStoreMapper.class)
public class GatewayStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GatewayStore gatewayStore(GatewayStoreMapper mapper, TransactionOperations transactionOperations) {
        return new GatewayStore(mapper, transactionOperations, System::currentTimeMillis);
    }
}
