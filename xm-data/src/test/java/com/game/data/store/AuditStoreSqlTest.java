package com.game.data.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.data.admin.AuditQueryController;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.PlayerSnapshotSink;
import com.game.data.txlog.TransactionLogRow;
import com.game.data.txlog.TransactionLogSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 生产建表脚本 + 生产 Mapper 的真实 SQL（缺省 H2 的 MySQL 兼容模式；{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}
 * 时连真 MySQL，口令取 XM_MYSQL_PASSWORD）。受影响行数的「重复计 0」语义只在真 MySQL（useAffectedRows=true）上断言。
 */
class AuditStoreSqlTest {

    private static final String MYSQL_URL = System.getProperty("xm.it.mysql");
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);

    private AnnotationConfigApplicationContext context;
    private TransactionLogMapper mapper;
    private TransactionLogSink sink;
    private PlayerSnapshotMapper snapshotMapper;
    private PlayerSnapshotSink snapshotSink;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        mapper = context.getBean(TransactionLogMapper.class);
        sink = new TransactionLogSink(mapper, new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),
                2, CLOCK);
        snapshotMapper = context.getBean(PlayerSnapshotMapper.class);
        snapshotSink = new PlayerSnapshotSink(snapshotMapper,
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)), 2, CLOCK);
    }

    @AfterEach
    void tearDown() {
        DataSource ds = context.getBean(DataSource.class);
        String database = context.getBean("databaseName", String.class);
        context.close();
        if (MYSQL_URL != null) {
            new JdbcTemplate(ds).execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    private static TransactionLogRow row(long txId, long timeMs, long from, long to, long delta) {
        return new TransactionLogRow(txId, timeMs, 9, 1, from, to, 1, delta, 100, 100 + delta, 0, 0, 0, 0, "", 1);
    }

    @Test
    void 分块多行插入_重放幂等_按玩家双向查询按时间升序() {
        List<TransactionLogRow> rows = List.of(row(3, 300, 0, 1001, 5), row(1, 100, 1001, 0, -7), row(2, 200, 0, 2002, 9));

        int inserted = sink.insert(rows);
        int replayed = sink.insert(rows);

        assertThat(inserted).isEqualTo(3);
        if (MYSQL_URL != null) {
            assertThat(replayed).as("useAffectedRows=true：重复行计 0").isZero();
        }
        AuditQueryController query = new AuditQueryController(mapper, snapshotMapper);
        List<Map<String, Object>> found = query.transactionLog("1001", 0, Long.MAX_VALUE, 100);
        assertThat(found).extracting(m -> m.get("txId")).containsExactly("1", "3");
        assertThat(found.get(0)).containsEntry("currencyDelta", -7L).containsEntry("fromPlayer", "1001")
                .containsEntry("ingestedAt", 1_800_000_000_000L);
        assertThat(query.transactionLog("1001", 150, Long.MAX_VALUE, 100)).extracting(m -> m.get("txId"))
                .containsExactly("3");
        assertThat(query.transactionLog("1001", 0, Long.MAX_VALUE, 1)).hasSize(1);
    }

    @Test
    void 保留期清理分批删掉更早的行() {
        sink.insert(List.of(row(1, 100, 0, 1, 1), row(2, 200, 0, 1, 1), row(3, 300, 0, 1, 1), row(4, 400, 0, 1, 1)));

        assertThat(mapper.deleteOlderThan(350, 2)).isEqualTo(2);
        assertThat(mapper.deleteOlderThan(350, 2)).isEqualTo(1);
        assertThat(mapper.deleteOlderThan(350, 2)).isZero();
        assertThat(mapper.findByToPlayer(1, 0, Long.MAX_VALUE, 10)).extracting(TransactionLogEntry::getTxId)
                .containsExactly(4L);
    }

    private static PlayerSnapshotRow snapshot(long snapshotId, long playerId, long timeMs, int stateBytes) {
        byte[] state = new byte[stateBytes];
        for (int i = 0; i < state.length; i++) {
            state[i] = (byte) i;
        }
        return new PlayerSnapshotRow(snapshotId, playerId, timeMs, 2, 1, 7, 12, 3, 1.5, 0, -2.25, state);
    }

    @Test
    void 快照分块插入_重放幂等_按玩家查元数据不取本体_字节数准确() {
        List<PlayerSnapshotRow> rows = List.of(snapshot(3, 1001, 300, 5), snapshot(1, 1001, 100, 0),
                snapshot(2, 2002, 200, 1), snapshot(4, 1001, 300, 700_000));

        int inserted = snapshotSink.insert(rows);
        int replayed = snapshotSink.insert(rows);

        assertThat(inserted).isEqualTo(4);
        if (MYSQL_URL != null) {
            assertThat(replayed).as("useAffectedRows=true：重复行计 0").isZero();
        }
        AuditQueryController query = new AuditQueryController(mapper, snapshotMapper);
        List<Map<String, Object>> found = query.playerSnapshots("1001", 0, Long.MAX_VALUE, 100);
        assertThat(found).extracting(m -> m.get("snapshotId")).containsExactly("1", "3", "4");
        assertThat(found).extracting(m -> m.get("stateBytes")).containsExactly(0L, 5L, 700_000L);
        assertThat(found.get(1)).containsEntry("cause", 2).containsEntry("ownerEpoch", "7").containsEntry("level", 12L)
                .containsEntry("sceneConfigId", 3L).containsEntry("posX", 1.5).containsEntry("posZ", -2.25)
                .containsEntry("ingestedAt", 1_800_000_000_000L);
        assertThat(query.playerSnapshots("1001", 150, 300, 100)).isEmpty();
        assertThat(query.playerSnapshots("1001", 0, Long.MAX_VALUE, 2)).hasSize(2);
        byte[] stored = new JdbcTemplate(context.getBean(DataSource.class))
                .queryForObject("SELECT player_state FROM player_snapshot WHERE snapshot_id = 3", byte[].class);
        assertThat(stored).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void 快照保留期清理分批删掉更早的行() {
        snapshotSink.insert(List.of(snapshot(1, 1, 100, 1), snapshot(2, 1, 200, 1), snapshot(3, 1, 400, 1)));

        assertThat(snapshotMapper.deleteOlderThan(350, 1)).isEqualTo(1);
        assertThat(snapshotMapper.deleteOlderThan(350, 5)).isEqualTo(1);
        assertThat(snapshotMapper.findByPlayer(1, 0, Long.MAX_VALUE, 10)).extracting(PlayerSnapshotEntry::getSnapshotId)
                .containsExactly(3L);
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        String databaseName() {
            return "xm_it_" + UUID.randomUUID().toString().replace("-", "");
        }

        @Bean
        DataSource dataSource(String databaseName) {
            DataSource ds;
            if (MYSQL_URL != null) {
                ds = new DriverManagerDataSource(MYSQL_URL + "/" + databaseName
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true",
                        System.getProperty("xm.it.mysql.user", "root"),
                        System.getenv().getOrDefault("XM_MYSQL_PASSWORD", ""));
            } else {
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
                ds = h2;
            }
            new ResourceDatabasePopulator(new ClassPathResource("db/xm-data-schema.sql")).execute(ds);
            return ds;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<TransactionLogMapper> transactionLogMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<TransactionLogMapper> bean = new MapperFactoryBean<>(TransactionLogMapper.class);
            bean.setSqlSessionFactory(sqlSessionFactory);
            return bean;
        }

        @Bean
        MapperFactoryBean<PlayerSnapshotMapper> playerSnapshotMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<PlayerSnapshotMapper> bean = new MapperFactoryBean<>(PlayerSnapshotMapper.class);
            bean.setSqlSessionFactory(sqlSessionFactory);
            return bean;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
