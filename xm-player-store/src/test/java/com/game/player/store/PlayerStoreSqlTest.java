package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真实 SQL（缺省 H2 的 MySQL 兼容模式；{@code -Dxm.it.mysql=...} 时连真 MySQL）+ 生产建表脚本 + 生产 Mapper + Spring 事务代理。
 * 覆盖归属协议（夺权 / 释放 / 续约 / 租约过期）与建角上限在并发事务下的行为。
 *
 * <p>H2 的重键错误码与 MySQL 不同（PlayerStore 按 MySQL 1062 认索引名），所以「撞名」分支只在 {@link PlayerStoreTest} 里测。
 */
class PlayerStoreSqlTest {

    private static final String ACCOUNT = "robot_0001";
    private static final AtomicLong CLOCK = new AtomicLong();
    /**
     * 设了 {@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306} 就改跑真 MySQL（用户 {@code xm.it.mysql.user}，缺省 root；
     * 口令取环境变量 XM_MYSQL_PASSWORD），否则用 H2 的 MySQL 兼容模式。
     */
    private static final String MYSQL_URL = System.getProperty("xm.it.mysql");
    private static final java.util.Queue<String> MYSQL_DATABASES = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private AnnotationConfigApplicationContext context;
    private PlayerStore store;
    private PlayerMapper mapper;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        CLOCK.set(1_000_000L);
        context = new AnnotationConfigApplicationContext(Config.class);
        store = context.getBean(PlayerStore.class);
        mapper = context.getBean(PlayerMapper.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        store.ensureAccount(ACCOUNT);
    }

    @AfterEach
    void tearDown() {
        DataSource ds = context.getBean(DataSource.class);
        context.close();
        for (String database = MYSQL_DATABASES.poll(); database != null; database = MYSQL_DATABASES.poll()) {
            new JdbcTemplate(ds).execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    private long newPlayer(long playerId, String name) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount(ACCOUNT);
        row.setZoneId(1);
        row.setClassId(1);
        row.setGender(1);
        assertThat(store.createPlayerWithinCap(row, 5, List.of(name)).status()).isEqualTo(PlayerStore.CreateStatus.CREATED);
        return playerId;
    }

    private static PlayerRow save(long playerId, long epoch, int sceneConfigId, double x) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setOwnerEpoch(epoch);
        row.setLevel(3);
        row.setSceneConfigId(sceneConfigId);
        row.setPosX(x);
        return row;
    }

    // ================================================================ 归属协议


    @Test
    void 口令记录_新账号没有口令_写入后读回规范账号与哈希_不存在的账号为空() {
        assertThat(store.findAccountPassword(ACCOUNT)).contains(new AccountPassword(ACCOUNT, null));
        new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
                .update("UPDATE account SET password_hash = ? WHERE account = ?", "$argon2id$v=19$x", ACCOUNT);
        assertThat(store.findAccountPassword(ACCOUNT)).contains(new AccountPassword(ACCOUNT, "$argon2id$v=19$x"));
        assertThat(store.findAccountPassword("robot_9999")).isEmpty();
        assertThat(store.findAccountPassword("ROBOT_0001")).as("utf8mb4_bin：大小写敏感").isEmpty();
    }
    @Test
    void 新角色可直接夺权_持有期间再夺回Held_最终写回释放后才能再夺且读到写回的状态() {
        long p = newPlayer(1001, "甲");

        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.claimOwnership(p)).as("上一个写者没释放、租约没过期").isEqualTo(new ClaimResult.Held(1));

        assertThat(store.saveStateAndRelease(save(p, 1, 2, 42.5), PlayerState.getDefaultInstance())).isTrue();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));
        PlayerRow loaded = store.findPlayer(p).orElseThrow();
        assertThat(loaded.getOwnerEpoch()).isEqualTo(2);
        assertThat(loaded.getSceneConfigId()).as("新写者加载到的是上一个写者的最终写回").isEqualTo(2);
        assertThat(loaded.getPosX()).isEqualTo(42.5);

        assertThat(store.saveStateAndRelease(save(p, 1, 9, 0), PlayerState.getDefaultInstance())).as("旧 epoch 的写回被围栏拒绝").isFalse();
        assertThat(store.findPlayer(p).orElseThrow().getSceneConfigId()).isEqualTo(2);
    }

    private static PlayerState facing(double x) {
        return PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(x).setY(1).setZ(2)).build();
    }

    @Test
    void 等级列存了超出int的无符号值_照样读出来不让整行加载失败() {
        // Connector/J 读 INT UNSIGNED 进 int 时超出 int 会抛 NumberOutOfRange（H2 不复现，只在真 MySQL 上跑）
        org.junit.jupiter.api.Assumptions.assumeTrue(MYSQL_URL != null, "需要 -Dxm.it.mysql");
        long p = newPlayer(1201, "戊");
        new JdbcTemplate(context.getBean(DataSource.class)).update("UPDATE player SET level = 4294967295 WHERE player_id = ?", p);

        assertThat(store.findPlayer(p).orElseThrow().getLevel()).isEqualTo(4294967295L);
    }

    @Test
    void 从未写过玩法数据时读到默认实例() {
        long p = newPlayer(1101, "丁");
        assertThat(store.loadState(p)).isEqualTo(PlayerState.getDefaultInstance());
    }

    @Test
    void 在线存盘不释放_最终写回后玩法数据与player行一起落库() {
        long p = newPlayer(1102, "戊");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));

        assertThat(store.saveStateHeld(save(p, 1, 2, 10), facing(0.5))).isTrue();
        assertThat(store.loadState(p)).isEqualTo(facing(0.5));
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(10);
        assertThat(store.claimOwnership(p)).as("在线存盘不释放归属").isEqualTo(new ClaimResult.Held(1));

        assertThat(store.saveStateAndRelease(save(p, 1, 2, 20), facing(0.75))).isTrue();
        assertThat(store.loadState(p)).isEqualTo(facing(0.75));
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(20);
    }

    @Test
    void 迟到的在线存盘盖不过已提交的最终写回() {
        long p = newPlayer(1103, "己");
        store.claimOwnership(p);
        assertThat(store.saveStateAndRelease(save(p, 1, 2, 20), facing(0.75))).isTrue();

        assertThat(store.saveStateHeld(save(p, 1, 2, 99), facing(9))).as("同 epoch，但已释放").isFalse();
        assertThat(store.loadState(p)).isEqualTo(facing(0.75));
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(20);
    }

    @Test
    void 旧epoch的在线存盘被围栏拒绝_玩法数据不变() {
        long p = newPlayer(1104, "庚");
        store.claimOwnership(p);
        store.saveStateAndRelease(save(p, 1, 2, 20), facing(0.75));
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));

        assertThat(store.saveStateHeld(save(p, 1, 2, 99), facing(9))).isFalse();
        assertThat(store.saveStateAndRelease(save(p, 1, 2, 99), facing(9))).isFalse();
        assertThat(store.loadState(p)).isEqualTo(facing(0.75));

        assertThat(store.saveStateHeld(save(p, 2, 2, 30), facing(1))).isTrue();
        assertThat(store.loadState(p)).isEqualTo(facing(1));
    }

    @Test
    void 夺权不存在的玩家回NotFound() {
        assertThat(store.claimOwnership(424242)).isEqualTo(new ClaimResult.NotFound());
    }

    @Test
    void 租约过期可强制夺权_旧epoch释放不了新归属() {
        long p = newPlayer(1001, "甲");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));

        CLOCK.addAndGet(PlayerStore.OWNER_LEASE.toMillis());
        assertThat(store.claimOwnership(p)).as("恰好到期还不算过期").isEqualTo(new ClaimResult.Held(1));
        CLOCK.incrementAndGet();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));

        assertThat(store.releaseOwnership(p, 1)).isFalse();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Held(2));
        assertThat(store.releaseOwnership(p, 2)).isTrue();
        assertThat(store.releaseOwnership(p, 2)).as("已释放的不重复释放").isFalse();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(3));
    }

    @Test
    void 续约推迟到期_续不上的归属报出来() {
        long a = newPlayer(1001, "甲");
        long b = newPlayer(1002, "乙");
        assertThat(store.claimOwnership(a)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.claimOwnership(b)).isEqualTo(new ClaimResult.Claimed(1));

        CLOCK.addAndGet(20_000);
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(a, 1), new OwnerLease(b, 1)))).isEmpty();
        CLOCK.addAndGet(20_000);
        assertThat(store.claimOwnership(a)).as("续约后到期推迟到续约时刻 + 租约").isEqualTo(new ClaimResult.Held(1));

        assertThat(store.releaseOwnership(b, 1)).isTrue();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(a, 1), new OwnerLease(b, 1))))
                .as("已释放的续不上").containsExactly(new OwnerLease(b, 1));
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(a, 7))))
                .as("epoch 不是当前值的续不上").containsExactly(new OwnerLease(a, 7));
        assertThat(store.claimOwnership(a)).as("续不上的那次没有动到真正持有者的租约").isEqualTo(new ClaimResult.Held(1));
    }

    // ================================================================ 建角上限

    @Test
    void 两个实例并发建第5个和第6个角色_账号行锁让后者看到前者提交的插入_恰好一个成功() throws Exception {
        for (int i = 0; i < 4; i++) {
            newPlayer(2000 + i, "角色" + i);
        }
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 「实例 A」：在自己的事务里拿到账号锁后停住，再插第 5 个并提交。
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(mapper.lockAccount(ACCOUNT)).isEqualTo(ACCOUNT);
            locked.countDown();
            await(release);
            PlayerRow row = new PlayerRow();
            row.setPlayerId(3000);
            row.setAccount(ACCOUNT);
            row.setName("第五个");
            row.setCreatedAt(CLOCK.get());
            row.setUpdatedAt(CLOCK.get());
            mapper.insertPlayer(row, PlayerStore.nameKey(row.getName()));
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // 「实例 B」：同账号建第 6 个，必须等 A 提交。
        PlayerRow sixth = new PlayerRow();
        sixth.setPlayerId(3001);
        sixth.setAccount(ACCOUNT);
        CompletableFuture<PlayerStore.CreateOutcome> second =
                CompletableFuture.supplyAsync(() -> store.createPlayerWithinCap(sixth, 5, List.of("第六个")));
        Thread.sleep(300);
        assertThat(second).as("B 在账号行锁上等待 A").isNotDone();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        PlayerStore.CreateOutcome outcome = second.get(10, TimeUnit.SECONDS);

        assertThat(outcome.status()).isEqualTo(PlayerStore.CreateStatus.PLAYER_FULL);
        assertThat(store.countPlayers(ACCOUNT)).isEqualTo(5);
    }

    @Test
    void 账号行不存在时建角失败() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(9);
        row.setAccount("robot_nobody");
        assertThat(store.createPlayerWithinCap(row, 5, List.of("无主")).status())
                .isEqualTo(PlayerStore.CreateStatus.ACCOUNT_MISSING);
        assertThat(store.countPlayers("robot_nobody")).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 与生产相同的装配要素：建表脚本、Mapper、列名下划线转驼峰、Spring 事务（@Transactional 经代理生效）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            String database = "xm_it_" + UUID.randomUUID().toString().replace("-", "");
            DataSource ds;
            if (MYSQL_URL != null) {
                // 真 MySQL：每个用例一个临时库（URL 里 createDatabaseIfNotExist），用例结束删库。
                ds = new DriverManagerDataSource(MYSQL_URL + "/" + database
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true",
                        System.getProperty("xm.it.mysql.user", "root"),
                        System.getenv().getOrDefault("XM_MYSQL_PASSWORD", ""));
                MYSQL_DATABASES.add(database);
            } else {
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
                ds = h2;
            }
            new ResourceDatabasePopulator(new ClassPathResource("db/xm-player-schema.sql")).execute(ds);
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
        PlayerStore playerStore(PlayerMapper playerMapper) {
            return new PlayerStore(playerMapper, CLOCK::get);
        }
    }
}
