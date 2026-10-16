package com.game.mall;

import com.game.common.RunMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 复用全仓 namespace 的库名核对；只为本模块装配独立连接池及启动门禁。 */
@Configuration(proxyBeanMethods = false)
public class MallDatabaseConfiguration {
    @Bean
    Clock mallClock() { return Clock.systemUTC(); }

    @Bean
    DataSource dataSource(DataSourceProperties source, MallProperties properties,
                          @Value("${game.run-mode:prod}") String runMode) {
        return new HikariDataSource(poolConfiguration(source, properties, runMode));
    }

    static HikariConfig poolConfiguration(DataSourceProperties source, MallProperties properties, String runMode) {
        if (RunMode.parse(runMode) == RunMode.PROD && !properties.requireTidb()) {
            throw new IllegalStateException("生产运行模式必须启用 game.mall.require-tidb");
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(source.getUrl());
        pool.setUsername(source.getUsername());
        pool.setPassword(source.getPassword());
        if (source.getUrl() != null && source.getUrl().startsWith("jdbc:mysql:")) {
            pool.addDataSourceProperty("connectTimeout", "3000");
            pool.addDataSourceProperty("socketTimeout", "6000");
        }
        pool.setMaximumPoolSize(16);
        pool.setConnectionTimeout(3000);
        pool.setValidationTimeout(2000);
        pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        // 每个新物理连接都初始化，包含断线重连；测试H2只有显式test/dev且关闭TiDB才能进入。
        if (properties.requireTidb()) {
            // 先清除 JDBC URL 注入的历史快照，再固定强读；不能依赖外部会话缺省值。
            pool.setConnectionInitSql("SET SESSION tidb_snapshot='',tidb_txn_mode='pessimistic',tidb_replica_read='leader',tidb_read_consistency='strict',"
                    + "tidb_read_staleness=0,tidb_low_resolution_tso=OFF,"
                    + "sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES'),innodb_lock_wait_timeout=5");
        } else if (source.getUrl() != null && !source.getUrl().startsWith("jdbc:h2:")) {
            pool.setConnectionInitSql("SET SESSION sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES'),innodb_lock_wait_timeout=5");
        }
        return pool;
    }

    @Bean(initMethod = "verify")
    @DependsOnDatabaseInitialization
    MallSchemaVerifier mallSchemaReady(DataSource source, MallProperties properties) {
        return new MallSchemaVerifier(source, properties);
    }

    @Bean
    MallOrderStore mallOrderStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                 MallProperties properties, MallSchemaVerifier mallSchemaReady) {
        return new MallOrderStore(jdbc, manager, clock, properties);
    }

    @Bean
    MallNonceStore mallNonceStore(JdbcTemplate jdbc, PlatformTransactionManager manager, MallProperties properties,
                                 MallSchemaVerifier mallSchemaReady) {
        return new MallNonceStore(jdbc, manager, properties);
    }

    @Bean
    MallAuthentication mallAuthentication(MallProperties properties, MallNonceStore nonces) {
        return new MallAuthentication(properties, nonces);
    }
}
