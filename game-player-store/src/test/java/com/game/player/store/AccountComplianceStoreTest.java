package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore.AccountDeletionOutcome;
import com.game.player.store.PlayerStore.AccountDeletionStatus;
import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.PlayerStore.CreateOutcome;
import com.game.player.store.PlayerStore.CreateResult;
import com.game.player.store.PlayerStore.CreateStatus;
import com.game.player.store.PlayerStore.FinishDeletionOutcome;
import com.game.player.store.PlayerStore.ResetRealNameOutcome;
import com.game.player.store.PlayerStore.RoleRemovalStatus;
import java.sql.SQLException;
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

/** 账号实名、渠道身份档案与账号注销全状态机（申请/拦阻/建角封锁/撤销/认领/删角/回执/重注册）SQL 回归。 */
class AccountComplianceStoreTest {

    private static final AtomicLong CLOCK = new AtomicLong();
    private static final String PI_ADULT = "1fff0000000000000000000000000000000001";
    private static final String PI_OTHER = "1fff0000000000000000000000000000000002";

    private AnnotationConfigApplicationContext context;
    private PlayerStore store;

    @BeforeEach
    void setUp() {
        CLOCK.set(1_750_000_000_000L);
        context = new AnnotationConfigApplicationContext(Config.class);
        store = context.getBean(PlayerStore.class);
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

    @Test
    void 实名绑定一次性CAS_客服重置_按角色号查实名与渠道身份() {
        AccountLifecycle created = store.readAccountLifecycle("acc_rn", true).orElseThrow();
        assertThat(created.realNamePi()).isEmpty();
        assertThat(created.previousDeletedMs()).isZero();

        // 首次绑定成功；重放同 pi 幂等；绑定不同 pi 保留原值
        assertThat(store.setAccountRealName("acc_rn", PI_ADULT)).isEqualTo(PI_ADULT);
        assertThat(store.setAccountRealName("acc_rn", PI_ADULT)).isEqualTo(PI_ADULT);
        assertThat(store.setAccountRealName("acc_rn", PI_OTHER)).isEqualTo(PI_ADULT);

        assertThat(store.createPlayer(newPlayer(201, "acc_rn", "实名测试号"))).isEqualTo(CreateResult.CREATED);
        assertThat(store.findRealNamePiByPlayerId(201)).isEqualTo(PI_ADULT);

        // 渠道身份写入与按角色号回查；后续空 openId 重连不得抹掉已有 openId
        store.upsertAccountChannelIdentity("acc_rn", "wechat", "openid_xyz");
        store.upsertAccountChannelIdentity("acc_rn", "wechat", "");
        AccountChannelIdentity id = store.findPlayerChannelIdentity(201).orElseThrow();
        assertThat(id.channel()).isEqualTo("wechat");
        assertThat(id.openId()).isEqualTo("openid_xyz");

        // 尚未回填 account_channel_identity 的角色也应回查出所属账号以支持前缀路由
        store.ensureAccount("wx_legacy_openid");
        assertThat(store.createPlayer(newPlayer(202, "wx_legacy_openid", "老渠道角色"))).isEqualTo(CreateResult.CREATED);
        AccountChannelIdentity legacy = store.findPlayerChannelIdentity(202).orElseThrow();
        assertThat(legacy.account()).isEqualTo("wx_legacy_openid");
        assertThat(legacy.channel()).isEmpty();
        assertThat(legacy.openId()).isEmpty();

        // 客服重置后清零，再次重置幂等返回 hadRealName=false
        ResetRealNameOutcome reset1 = store.resetAccountRealName("acc_rn");
        assertThat(reset1.found()).isTrue();
        assertThat(reset1.hadRealName()).isTrue();
        assertThat(store.findRealNamePiByPlayerId(201)).isEmpty();

        ResetRealNameOutcome reset2 = store.resetAccountRealName("acc_rn");
        assertThat(reset2.found()).isTrue();
        assertThat(reset2.hadRealName()).isFalse();
        assertThat(store.setAccountRealName("acc_rn", PI_OTHER)).isEqualTo(PI_OTHER);
    }

    @Test
    void 账号注销全流程_预检拦阻_冷静期禁建角与撤销_到期认领删角写回执与重注册可见() {
        store.ensureAccount("acc_del");
        store.upsertAccountChannelIdentity("acc_del", "password", "");
        assertThat(store.createPlayer(newPlayer(301, "acc_del", "注销一号"))).isEqualTo(CreateResult.CREATED);

        // 1. 预检抛 SQL 异常 -> fail-closed 回 RETRY
        RoleDeletionPrecheck failing = (c, p, self) -> {
            throw new SQLException("downstream table unavailable");
        };
        assertThat(store.requestAccountDeletion("acc_del", AccountLifecycle.SOURCE_USER, Duration.ofDays(7), failing)
                .status()).isEqualTo(AccountDeletionStatus.RETRY);

        // 2. 帮主拦阻：本人申请被拦；运维代办跳过自助拦阻项
        RoleDeletionPrecheck guildLeader = (c, p, self) -> self ? List.of(RoleDeletionPrecheck.BLOCKER_GUILD_LEADER) : List.of();
        AccountDeletionOutcome blocked = store.requestAccountDeletion("acc_del", AccountLifecycle.SOURCE_USER,
                Duration.ofDays(7), guildLeader);
        assertThat(blocked.status()).isEqualTo(AccountDeletionStatus.BLOCKED);
        assertThat(blocked.blockers()).containsExactly(new PlayerStore.DeletionBlocker(301, "guild_leader"));

        // 3. 合法申请进入冷静期，重复申请不刷新 executeAfterMs
        long t0 = CLOCK.get();
        AccountDeletionOutcome pending = store.requestAccountDeletion("acc_del", AccountLifecycle.SOURCE_USER,
                Duration.ofDays(7), RoleDeletionPrecheck.NONE);
        assertThat(pending.status()).isEqualTo(AccountDeletionStatus.OK);
        assertThat(pending.lifecycle().deletionState()).isEqualTo(AccountLifecycle.DELETION_PENDING);
        long executeAfter = pending.lifecycle().deletionExecuteAfterMs();
        assertThat(executeAfter).isEqualTo(t0 + Duration.ofDays(7).toMillis());

        CLOCK.addAndGet(60_000L);
        AccountDeletionOutcome duplicate = store.requestAccountDeletion("acc_del", AccountLifecycle.SOURCE_USER,
                Duration.ofDays(7), RoleDeletionPrecheck.NONE);
        assertThat(duplicate.status()).isEqualTo(AccountDeletionStatus.OK);
        assertThat(duplicate.lifecycle().deletionExecuteAfterMs()).isEqualTo(executeAfter);

        // 4. 冷静期内建角被账号行锁事务直接拒绝
        CreateOutcome deniedCreate = store.createPlayerWithinCap(newPlayer(302, "acc_del", "冷静期建角"), 5, List.of("冷静期建角"));
        assertThat(deniedCreate.status()).isEqualTo(CreateStatus.DELETION_PENDING);

        // 5. 冷静期内可撤销，撤销后恢复可建角
        AccountDeletionOutcome cancelled = store.cancelAccountDeletion("acc_del");
        assertThat(cancelled.status()).isEqualTo(AccountDeletionStatus.OK);
        assertThat(cancelled.lifecycle().deletionState()).isEqualTo(AccountLifecycle.DELETION_NONE);

        // 6. 运维代办跳过 guild_leader 直接进入冷静期
        AccountDeletionOutcome adminScheduled = store.requestAccountDeletion("acc_del", AccountLifecycle.SOURCE_ADMIN,
                Duration.ofHours(1), guildLeader);
        assertThat(adminScheduled.status()).isEqualTo(AccountDeletionStatus.OK);
        assertThat(adminScheduled.lifecycle().deletionSource()).isEqualTo(AccountLifecycle.SOURCE_ADMIN);

        // 7. 未到期认领不到；到期后认领进入 DELETING(2)，此时撤销回 TOO_LATE
        assertThat(store.claimDueAccountDeletions(8, Duration.ofMinutes(2))).isEmpty();
        CLOCK.addAndGet(Duration.ofHours(1).toMillis() + 1);
        assertThat(store.claimDueAccountDeletions(8, Duration.ofMinutes(2))).containsExactly("acc_del");
        // 租约内不重复认领
        assertThat(store.claimDueAccountDeletions(8, Duration.ofMinutes(2))).isEmpty();
        assertThat(store.cancelAccountDeletion("acc_del").status()).isEqualTo(AccountDeletionStatus.TOO_LATE);

        // 8. 角色尚在场景持有中 -> BUSY；释放后删除成功 -> REMOVED
        ClaimResult claimed = store.claimOwnership(301, Duration.ofSeconds(3));
        assertThat(claimed).isInstanceOf(ClaimResult.Claimed.class);
        long epoch = ((ClaimResult.Claimed) claimed).ownerEpoch();
        assertThat(store.removeRoleForDeletion("acc_del", 301, RoleDeletionPrecheck.NONE))
                .isEqualTo(RoleRemovalStatus.BUSY);

        // 角色未删完前 finishAccountDeletion 返回 done=false, remainingRoles=1
        FinishDeletionOutcome notReady = store.finishAccountDeletion("acc_del");
        assertThat(notReady.done()).isFalse();
        assertThat(notReady.remainingRoles()).isEqualTo(1);

        assertThat(store.releaseOwnership(301, epoch)).isTrue();
        assertThat(store.removeRoleForDeletion("acc_del", 301, RoleDeletionPrecheck.NONE))
                .isEqualTo(RoleRemovalStatus.REMOVED);

        // 9. 全部角色清完 -> finishAccountDeletion 写回执并删账号行与渠道身份
        long deletedAt = CLOCK.get();
        FinishDeletionOutcome done = store.finishAccountDeletion("acc_del");
        assertThat(done.done()).isTrue();
        assertThat(done.remainingRoles()).isZero();
        // 幂等重调
        assertThat(store.finishAccountDeletion("acc_del").done()).isTrue();

        // 10. 不建行读返回空（旧 access_token 不能复活已注销账号）；允许建行读创建新账号并带上次注销时刻
        assertThat(store.readAccountLifecycle("acc_del", false)).isEmpty();
        AccountLifecycle reborn = store.readAccountLifecycle("acc_del", true).orElseThrow();
        assertThat(reborn.deletionState()).isEqualTo(AccountLifecycle.DELETION_NONE);
        assertThat(reborn.previousDeletedMs()).isEqualTo(deletedAt);
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
            h2.setURL("jdbc:h2:mem:compliance_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<PlayerMapper> playerMapper(SqlSessionFactory factory) {
            MapperFactoryBean<PlayerMapper> bean = new MapperFactoryBean<>(PlayerMapper.class);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager tx) {
            return new PlayerStore(mapper, CLOCK::get, tx);
        }
    }
}
