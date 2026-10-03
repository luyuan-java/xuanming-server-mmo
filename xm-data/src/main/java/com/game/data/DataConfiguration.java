package com.game.data;

import com.game.audit.AuditProperties;
import com.game.audit.KafkaTopicAdmin;
import com.game.data.admin.AdminAuthFilter;
import com.game.data.metrics.DataMetrics;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** xm-data 的装配。所有依赖显式经构造参数传入。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataProperties.class, AuditProperties.class})
@MapperScan("com.game.data.store")
public class DataConfiguration {

    /** 列名下划线转驼峰（与 xm-player-store 同口径）。 */
    @Bean
    public ConfigurationCustomizer mapUnderscoreToCamelCase() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    @Bean
    public DataMetrics dataMetrics(MeterRegistry registry) {
        return new DataMetrics(registry);
    }

    @Bean
    public DataNode dataNode(AuditProperties audit, DataProperties props, TransactionLogMapper transactionLog,
                             PlayerSnapshotMapper playerSnapshot, PlatformTransactionManager transactionManager,
                             DataMetrics metrics) {
        return new DataNode(audit, props, transactionLog, playerSnapshot, new TransactionTemplate(transactionManager),
                metrics, () -> new KafkaTopicAdmin(audit.bootstrapServers(), "xm-data-admin"), Clock.systemUTC());
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(DataProperties props, DataMetrics metrics) {
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(new AdminAuthFilter(props.adminToken(), metrics));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
