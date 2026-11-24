package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.PlayerStore.CreateResult;
import com.game.player.store.PlayerStore.RenameOutcome;
import com.game.player.store.PlayerStore.RenameStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 角色原子改名（owner_epoch 围栏、冷却、旧名保护与本人改回、建角拦截旧名保护期）SQL 回归。 */
class PlayerRenameStoreTest {

    private static final AtomicLong CLOCK = new AtomicLong();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long COOLDOWN_MS = 7L * 86_400_000L;
    private static final long HOLD_MS = 7L * 86_400_000L;

    private AnnotationConfigApplicationContext context;
    private PlayerStore store;

    @BeforeEach
    void setUp() {
        CLOCK.set(1_700_000_000_000L);
        context = new AnnotationConfigApplicationContext(Config.class);
        store = context.getBean(PlayerStore.class);
        store.ensureAccount("acc_a");
        store.ensureAccount("acc_b");
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private PlayerRow newPlayer(long id, String account, String name) {
        PlayerRow r = new PlayerRow();
        r.setPlayerId(id);
        r.setAccount(account);
        r.setZoneId(1);
        r.setName(name);
        r.setClassId(1);
        r.setGender(1);
        return r;
    }

    private long claim(long playerId) {
        ClaimResult claimed = store.claimOwnership(playerId, TIMEOUT);
        assertThat(claimed).isInstanceOf(ClaimResult.Claimed.class);
        return ((ClaimResult.Claimed) claimed).ownerEpoch();
    }

    @Test
    void 围栏原子改名_冷却校验_旧名保护拦他人并允许本人改回_过期释放() {
        assertThat(store.createPlayer(newPlayer(101, "acc_a", "剑侠一号"))).isEqualTo(CreateResult.CREATED);
        assertThat(store.createPlayer(newPlayer(102, "acc_b", "刀客二号"))).isEqualTo(CreateResult.CREATED);
        long e1 = claim(101);
        long e2 = claim(102);

        // 1. 错 epoch / 同名 / 重名拒绝
        assertThat(store.renamePlayer(101, e1 + 99, "新名", COOLDOWN_MS, HOLD_MS, TIMEOUT).status())
                .isEqualTo(RenameStatus.FENCED);
        assertThat(store.renamePlayer(101, e1, "剑侠一号", COOLDOWN_MS, HOLD_MS, TIMEOUT).status())
                .isEqualTo(RenameStatus.SAME_NAME);
        assertThat(store.renamePlayer(101, e1, "刀客二号", COOLDOWN_MS, HOLD_MS, TIMEOUT).status())
                .isEqualTo(RenameStatus.NAME_TAKEN);

        // 2. 合法改名成功：更新 name 与 renamed_ms，写入旧名保护
        long t1 = CLOCK.get();
        RenameOutcome ok = store.renamePlayer(101, e1, "青莲剑仙", COOLDOWN_MS, HOLD_MS, TIMEOUT);
        assertThat(ok.status()).isEqualTo(RenameStatus.OK);
        assertThat(ok.name()).isEqualTo("青莲剑仙");
        assertThat(ok.renamedMs()).isEqualTo(t1);
        assertThat(ok.nextAllowedMs()).isEqualTo(t1 + COOLDOWN_MS);
        PlayerRow reloaded = store.findPlayer(101).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("青莲剑仙");
        assertThat(reloaded.getRenamedMs()).isEqualTo(t1);

        // 3. 冷却期内再次改名被拒并带 nextAllowedMs
        CLOCK.addAndGet(3_600_000L);
        RenameOutcome cooling = store.renamePlayer(101, e1, "第三名", COOLDOWN_MS, HOLD_MS, TIMEOUT);
        assertThat(cooling.status()).isEqualTo(RenameStatus.COOLDOWN);
        assertThat(cooling.nextAllowedMs()).isEqualTo(t1 + COOLDOWN_MS);

        // 4. 旧名保护期内：他人改名与建角都拿不到 "剑侠一号"
        assertThat(store.renamePlayer(102, e2, "剑侠一号", COOLDOWN_MS, HOLD_MS, TIMEOUT).status())
                .isEqualTo(RenameStatus.NAME_TAKEN);
        assertThat(store.createPlayer(newPlayer(103, "acc_b", "剑侠一号"))).isEqualTo(CreateResult.NAME_TAKEN);

        // 5. 本人无冷却（cooldown=0）可在保护期内改回自己的旧名 "剑侠一号"
        RenameOutcome reclaim = store.renamePlayer(101, e1, "剑侠一号", 0, HOLD_MS, TIMEOUT);
        assertThat(reclaim.status()).isEqualTo(RenameStatus.OK);
        assertThat(store.findPlayer(101).orElseThrow().getName()).isEqualTo("剑侠一号");

        // 6. 保护期过期后：他人可以改名或建角取得 "青莲剑仙"
        CLOCK.addAndGet(HOLD_MS + 1);
        assertThat(store.renamePlayer(102, e2, "青莲剑仙", COOLDOWN_MS, HOLD_MS, TIMEOUT).status())
                .isEqualTo(RenameStatus.OK);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config implements org.springframework.beans.factory.DisposableBean {
        private JdbcDataSource memory;
        private PlayerTradeStoreTest.H2Authority h2Authority;

        @Override
        public void destroy() {
            if (h2Authority != null) h2Authority.seal();
            if (memory != null) new JdbcTemplate(memory).execute("SHUTDOWN");
        }

        @Bean
        DataSource dataSource() throws Exception {
            var h2 = new JdbcDataSource();
            h2.setUser("sa");
            h2.setPassword("");
            memory = h2;
            h2.setURL("jdbc:h2:mem:rename_it_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/game-route-authority-schema.sql"),
                    new ClassPathResource("db/game-player-schema.sql"),
                    new ClassPathResource("db/role-pending-fence-fixture.sql")).execute(h2);
            try (var c = h2.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT CURRENT_USER()")) {
                if (!rs.next()) throw new java.sql.SQLException("缺主体");
                com.game.common.player.RouteAuthorityInitializer.initialize(c, UUID.randomUUID().toString(), List.of(1), rs.getString(1));
            }
            h2Authority = new PlayerTradeStoreTest.H2Authority(h2);
            return h2Authority.normalSource();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<PlayerMapper> playerMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<PlayerMapper> bean = new MapperFactoryBean<>(PlayerMapper.class);
            bean.setSqlSessionFactory(sqlSessionFactory);
            return bean;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PlayerStore playerStore(PlayerMapper playerMapper, PlatformTransactionManager transactionManager) {
            return new PlayerStore(playerMapper, CLOCK::get, transactionManager);
        }
    }
}
