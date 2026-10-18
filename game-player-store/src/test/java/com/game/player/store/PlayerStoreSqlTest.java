package com.game.player.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.PlayerStore.ClaimResult;
import com.game.player.store.PlayerStore.HandOffMode;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import java.time.Duration;
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
 * 真实 SQL（缺省 H2 的 MySQL 兼容模式；{@code -Dgame.it.mysql=...} 时连真 MySQL）+ 生产建表脚本 + 生产 Mapper + Spring 事务代理。
 * 覆盖归属协议（夺权 / 释放 / 续约 / 租约过期）与建角上限在并发事务下的行为。
 *
 * <p>H2 的重键错误码与 MySQL 不同（PlayerStore 按 MySQL 1062 认索引名），所以「撞名」分支只在 {@link PlayerStoreTest} 里测。
 */
class PlayerStoreSqlTest {

    private static final String ACCOUNT = "robot_0001";
    private static final AtomicLong CLOCK = new AtomicLong();
    /**
     * 设了 {@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306} 就改跑真 MySQL（用户 {@code game.it.mysql.user}，缺省 root；
     * 口令取环境变量 GAME_MYSQL_PASSWORD），否则用 H2 的 MySQL 兼容模式。
     */
    private static final String MYSQL_URL = System.getProperty("game.it.mysql");
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
        org.junit.jupiter.api.Assumptions.assumeTrue(MYSQL_URL != null, "需要 -Dgame.it.mysql");
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

    // ================================================================ 交出（跨节点换图，归属协议第 6 步）

    /** 夺权后的租约到期时刻（CLOCK 起点 + 30 s）。 */
    private static final long CLAIMED_LEASE = 1_000_000L + PlayerStore.OWNER_LEASE.toMillis();
    private static final long MARGIN_MS = 15_000;
    private static final Duration TX_TIMEOUT = Duration.ofSeconds(5);

    /** 按「现在」取一次交出尝试：新租约 now + 30 s，剩余租约下限 now + 15 s。 */
    private HandOffResult handOff(long playerId, long epoch, int sceneConfigId, double x, PlayerState state) {
        long now = CLOCK.get();
        return store.handOffOwnership(save(playerId, epoch, sceneConfigId, x), state,
                now + PlayerStore.OWNER_LEASE.toMillis(), now + MARGIN_MS, TX_TIMEOUT);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private OwnerState owner(long playerId) {
        return jdbc().queryForObject("SELECT owner_epoch, owner_released, owner_lease_until FROM player WHERE player_id = ?",
                (rs, i) -> new OwnerState(rs.getLong(1), rs.getInt(2) != 0, rs.getLong(3)), playerId);
    }

    private long savedEpoch(long playerId) {
        return jdbc().queryForObject("SELECT saved_epoch FROM player_state WHERE player_id = ?", Long.class, playerId);
    }

    /** player 行全部列 + player_state（玩法数据与写入 epoch）：用于断言「什么也没改」。 */
    private List<Object> everything(long playerId) {
        List<java.util.Map<String, Object>> state = jdbc().queryForList(
                "SELECT saved_epoch, updated_at FROM player_state WHERE player_id = ?", playerId);
        return List.of(jdbc().queryForMap("SELECT * FROM player WHERE player_id = ?", playerId), state, store.loadState(playerId));
    }

    @Test
    void 交出成功_epoch加一未释放_租约是传入值_快照与玩法数据落库_saved_epoch是交出方() {
        long p = newPlayer(1301, "交出甲");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.saveStateHeld(save(p, 1, 2, 10), facing(0.5))).isTrue();
        CLOCK.addAndGet(5_000);

        assertThat(handOff(p, 1, 5, 77.5, facing(3))).isEqualTo(new HandOffResult.HandedOff(2));

        assertThat(owner(p)).isEqualTo(new OwnerState(2, false, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis()));
        PlayerRow row = store.findPlayer(p).orElseThrow();
        assertThat(row.getSceneConfigId()).isEqualTo(5);
        assertThat(row.getPosX()).isEqualTo(77.5);
        assertThat(row.getLevel()).isEqualTo(3);
        assertThat(row.getUpdatedAt()).isEqualTo(CLOCK.get());
        assertThat(store.loadState(p)).isEqualTo(facing(3));
        assertThat(savedEpoch(p)).as("玩法数据由交出方 E 写入").isEqualTo(1);
    }

    @Test
    void 交出没提交的四种情况各回对应结局且什么也不改() {
        long stale = newPlayer(1311, "交出乙");
        store.claimOwnership(stale);
        List<Object> before = everything(stale);
        assertThat(handOff(stale, 7, 5, 1, facing(9))).as("epoch 不是 E")
                .isEqualTo(new HandOffResult.Fenced(new OwnerState(1, false, CLAIMED_LEASE)));
        assertThat(everything(stale)).isEqualTo(before);

        long released = newPlayer(1312, "交出丙");
        store.claimOwnership(released);
        assertThat(store.saveStateAndRelease(save(released, 1, 2, 20), facing(0.75))).isTrue();
        before = everything(released);
        assertThat(handOff(released, 1, 5, 1, facing(9))).as("已释放")
                .isEqualTo(new HandOffResult.Fenced(new OwnerState(1, true, CLAIMED_LEASE)));
        assertThat(everything(released)).isEqualTo(before);

        long shortLease = newPlayer(1313, "交出丁");
        store.claimOwnership(shortLease);
        before = everything(shortLease);
        CLOCK.addAndGet(PlayerStore.OWNER_LEASE.toMillis() - MARGIN_MS + 1);
        assertThat(handOff(shortLease, 1, 5, 1, facing(9))).as("剩余租约比安全边际少 1 ms")
                .isEqualTo(new HandOffResult.LeaseTooShort(new OwnerState(1, false, CLAIMED_LEASE)));
        assertThat(everything(shortLease)).isEqualTo(before);
        CLOCK.decrementAndGet();
        assertThat(handOff(shortLease, 1, 5, 1, facing(9))).as("剩余租约恰好等于安全边际可以交出")
                .isEqualTo(new HandOffResult.HandedOff(2));

        assertThat(handOff(424242, 1, 5, 1, facing(9))).as("玩家不存在").isEqualTo(new HandOffResult.Fenced(null));
    }

    @Test
    void 交出之后旧epoch的写全被拒_夺权回Held直到新租约过期才夺到下一代() {
        long p = newPlayer(1321, "交出戊");
        store.claimOwnership(p);
        assertThat(handOff(p, 1, 5, 77.5, facing(3))).isEqualTo(new HandOffResult.HandedOff(2));
        List<Object> handedOff = everything(p);

        assertThat(store.saveStateHeld(save(p, 1, 9, 99), facing(9))).isFalse();
        assertThat(store.saveStateAndRelease(save(p, 1, 9, 99), facing(9))).isFalse();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(p, 1)))).containsExactly(new OwnerLease(p, 1));
        assertThat(store.releaseOwnership(p, 1)).isFalse();
        assertThat(handOff(p, 1, 9, 99, facing(9))).isInstanceOf(HandOffResult.Fenced.class);
        assertThat(everything(p)).as("旧 epoch 的迟到写碰不到交出后的行").isEqualTo(handedOff);

        assertThat(store.claimOwnership(p)).as("E+1 未释放、租约未过期").isEqualTo(new ClaimResult.Held(2));
        CLOCK.addAndGet(PlayerStore.OWNER_LEASE.toMillis() + 1);
        assertThat(store.claimOwnership(p)).as("E+1 无人续约，租约过期后夺到下一代").isEqualTo(new ClaimResult.Claimed(3));
        PlayerRow loaded = store.findPlayer(p).orElseThrow();
        assertThat(loaded.getSceneConfigId()).as("夺到的是冻结快照").isEqualTo(5);
        assertThat(store.loadState(p)).isEqualTo(facing(3));
    }

    @Test
    void 交出之后新epoch可以释放_释放后立即可夺权_也能被新epoch写回() {
        long p = newPlayer(1322, "交出己");
        store.claimOwnership(p);
        assertThat(handOff(p, 1, 5, 77.5, facing(3))).isEqualTo(new HandOffResult.HandedOff(2));

        assertThat(store.saveStateHeld(save(p, 2, 6, 1), facing(4))).as("目标节点以 E+1 在线存盘").isTrue();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(p, 2)))).isEmpty();
        assertThat(store.releaseOwnership(p, 2)).isTrue();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(3));
    }

    // ---------------------------------------------------------------- 交出并释放（跨 zone 传送，批次 5.4，zone-travel-spec §5.2、§11.2）
    // 本节钉存储原语在库里的行为：提交即 (E+1, 已释放, L_i)、立即夺到 E+2、旧 epoch 的写全被拒、没提交的不改任何列、
    // 重试 / 探测读到的那一行（改判与探测的分类在 game-scene 的 StoragePlayerRepository，按这里读到的行查表）、并发下结论确定。

    /** 按「现在」取一次给定模式的交出尝试：新租约 now + 30 s，剩余租约下限 now + 15 s。 */
    private HandOffResult handOff(long playerId, long epoch, int sceneConfigId, double x, PlayerState state, HandOffMode mode) {
        long now = CLOCK.get();
        return store.handOffOwnership(save(playerId, epoch, sceneConfigId, x), state,
                now + PlayerStore.OWNER_LEASE.toMillis(), now + MARGIN_MS, TX_TIMEOUT, mode);
    }

    @Test
    void 交出并释放_提交后是E加1且已释放_租约是传入值_不等租约立即夺到E加2且读到冻结快照() {
        long p = newPlayer(1381, "释放甲");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.saveStateHeld(save(p, 1, 2, 10), facing(0.5))).isTrue();
        CLOCK.addAndGet(5_000);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();

        HandOffResult result = store.handOffOwnership(save(p, 1, 5, 77.5), facing(3), lease, CLOCK.get() + MARGIN_MS,
                TX_TIMEOUT, HandOffMode.RELEASE);

        assertThat(result).isEqualTo(new HandOffResult.HandedOff(2));
        assertThat(owner(p)).as("(E+1, 已释放, 这次尝试的租约值)").isEqualTo(new OwnerState(2, true, lease));
        PlayerRow row = store.findPlayer(p).orElseThrow();
        assertThat(row.getSceneConfigId()).isEqualTo(5);
        assertThat(row.getPosX()).isEqualTo(77.5);
        assertThat(row.getLevel()).isEqualTo(3);
        assertThat(row.getUpdatedAt()).isEqualTo(CLOCK.get());
        assertThat(row.getZoneId()).as("交出不动归属区").isEqualTo(1);
        assertThat(store.loadState(p)).isEqualTo(facing(3));
        assertThat(savedEpoch(p)).as("玩法数据由交出方 E 写入").isEqualTo(1);

        // 时钟没有再走：E+1 的租约还有整整 30 s，这次夺权成功只能是因为已释放
        assertThat(store.claimOwnership(p)).as("第二条腿：目标 zone 的登录立即夺到 E+2").isEqualTo(new ClaimResult.Claimed(3));
        PlayerRow loaded = store.findPlayer(p).orElseThrow();
        assertThat(loaded.getOwnerEpoch()).isEqualTo(3);
        assertThat(loaded.getSceneConfigId()).as("夺到的是冻结快照").isEqualTo(5);
        assertThat(loaded.getPosX()).isEqualTo(77.5);
        assertThat(store.loadState(p)).isEqualTo(facing(3));
        assertThat(owner(p).released()).as("E+2 由新的持有者持有").isFalse();
    }

    @Test
    void 六参数形式传HOLD等于五参数形式_E加1未释放_同一时刻夺权回Held_模式为null在动库之前就拒() {
        long p = newPlayer(1382, "释放乙");
        store.claimOwnership(p);
        List<Object> before = everything(p);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();

        assertThatThrownBy(() -> store.handOffOwnership(save(p, 1, 5, 77.5), facing(3), lease, CLOCK.get() + MARGIN_MS,
                TX_TIMEOUT, null)).isInstanceOf(NullPointerException.class);
        assertThat(everything(p)).isEqualTo(before);

        assertThat(store.handOffOwnership(save(p, 1, 5, 77.5), facing(3), lease, CLOCK.get() + MARGIN_MS, TX_TIMEOUT,
                HandOffMode.HOLD)).isEqualTo(new HandOffResult.HandedOff(2));
        assertThat(owner(p)).isEqualTo(new OwnerState(2, false, lease));
        assertThat(store.claimOwnership(p)).as("与交出并释放的唯一区别：E+1 有人持有，要等它释放或租约过期")
                .isEqualTo(new ClaimResult.Held(2));
    }

    @Test
    void 交出并释放之后旧epoch的写全被拒_对E加1的释放与续约都是空操作_什么也不改_随后立即夺到E加2() {
        long p = newPlayer(1383, "释放丙");
        store.claimOwnership(p);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        assertThat(handOff(p, 1, 5, 77.5, facing(3), HandOffMode.RELEASE)).isEqualTo(new HandOffResult.HandedOff(2));
        List<Object> handedOff = everything(p);

        assertThat(store.saveStateAndRelease(save(p, 1, 9, 99), facing(9))).as("停服 / 离场的最终写回(E)").isFalse();
        assertThat(store.saveStateHeld(save(p, 1, 9, 99), facing(9))).as("迟到的在线存盘(E)").isFalse();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(p, 1)))).as("续约(E)").containsExactly(new OwnerLease(p, 1));
        assertThat(store.releaseOwnership(p, 1)).as("释放(E)").isFalse();
        assertThat(handOff(p, 1, 9, 99, facing(9), HandOffMode.RELEASE)).as("再交出(E)").isInstanceOf(HandOffResult.Fenced.class);
        assertThat(handOff(p, 1, 9, 99, facing(9), HandOffMode.HOLD)).isInstanceOf(HandOffResult.Fenced.class);
        assertThat(store.releaseOwnership(p, 2)).as("E+1 提交时已释放：对它的释放是空操作").isFalse();
        assertThat(store.renewOwnerLeases(List.of(new OwnerLease(p, 2)))).as("E+1 没有持有者，续不上")
                .containsExactly(new OwnerLease(p, 2));
        assertThat(everything(p)).as("迟到的写碰不到交出并释放之后的行").isEqualTo(handedOff);
        assertThat(owner(p)).isEqualTo(new OwnerState(2, true, lease));

        assertThat(store.claimOwnership(p)).as("不等租约：已释放即可夺权").isEqualTo(new ClaimResult.Claimed(3));
        assertThat(store.findPlayer(p).orElseThrow().getSceneConfigId()).as("夺到的是冻结快照").isEqualTo(5);
        assertThat(store.loadState(p)).isEqualTo(facing(3));
    }

    /** 归属区（zone-travel-spec §3.2、§3.4）：建角写调用方给的 zone（login 传会话 zone）；去程交出并释放、目标 zone 夺权都不动它。 */
    @Test
    void 建角写入传入的zone_交出并释放与目标zone的夺权都不动归属区() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1385);
        row.setAccount(ACCOUNT);
        row.setZoneId(2);
        row.setClassId(1);
        row.setGender(1);
        assertThat(store.createPlayerWithinCap(row, 5, List.of("归属乙")).status()).isEqualTo(PlayerStore.CreateStatus.CREATED);
        assertThat(store.findPlayer(1385).orElseThrow().getZoneId()).isEqualTo(2);

        store.claimOwnership(1385);
        assertThat(handOff(1385, 1, 5, 77.5, facing(3), HandOffMode.RELEASE)).isEqualTo(new HandOffResult.HandedOff(2));
        assertThat(store.claimOwnership(1385)).isEqualTo(new ClaimResult.Claimed(3));
        assertThat(store.findPlayer(1385).orElseThrow().getZoneId()).as("传送不改归属区").isEqualTo(2);
    }

    /** 没提交的三种情况（epoch 不是 E、已释放、剩余租约不足）两种模式各试一遍：结果相同、库里什么也不改。 */
    @Test
    void 交出没提交的三种情况_两种模式都回对应结局且什么也不改() {
        long stale = newPlayer(1391, "未提交甲");
        store.claimOwnership(stale);
        long released = newPlayer(1392, "未提交乙");
        store.claimOwnership(released);
        assertThat(store.saveStateAndRelease(save(released, 1, 2, 20), facing(0.75))).isTrue();
        long shortLease = newPlayer(1393, "未提交丙");
        store.claimOwnership(shortLease);
        List<Object> staleBefore = everything(stale);
        List<Object> releasedBefore = everything(released);
        List<Object> shortBefore = everything(shortLease);

        for (HandOffMode mode : HandOffMode.values()) {
            CLOCK.set(1_000_000L);
            assertThat(handOff(stale, 7, 5, 1, facing(9), mode)).as("%s：epoch 不是 E", mode)
                    .isEqualTo(new HandOffResult.Fenced(new OwnerState(1, false, CLAIMED_LEASE)));
            assertThat(handOff(released, 1, 5, 1, facing(9), mode)).as("%s：已释放", mode)
                    .isEqualTo(new HandOffResult.Fenced(new OwnerState(1, true, CLAIMED_LEASE)));
            CLOCK.addAndGet(PlayerStore.OWNER_LEASE.toMillis() - MARGIN_MS + 1);
            assertThat(handOff(shortLease, 1, 5, 1, facing(9), mode)).as("%s：剩余租约比安全边际少 1 ms", mode)
                    .isEqualTo(new HandOffResult.LeaseTooShort(new OwnerState(1, false, CLAIMED_LEASE)));
            assertThat(handOff(424242, 1, 5, 1, facing(9), mode)).as("%s：玩家不存在", mode)
                    .isEqualTo(new HandOffResult.Fenced(null));

            assertThat(everything(stale)).isEqualTo(staleBefore);
            assertThat(everything(released)).isEqualTo(releasedBefore);
            assertThat(everything(shortLease)).isEqualTo(shortBefore);
        }
    }

    /**
     * 重试改判要读到的行（分类在 game-scene 的 StoragePlayerRepository）：已提交的交出并释放再执行一次，回 Fenced 且带着
     * (E+1, <b>已释放</b>, 上一次写下的租约值)——不是 HOLD 的「未释放」；随后被别的登录接管，再重试读到 (E+2, 未释放, 新持有者的租约)。
     */
    @Test
    void 交出并释放已提交后再执行一次_读到E加1已释放与上一次的租约值_被接管后读到E加2() {
        long p = newPlayer(1384, "释放丁");
        store.claimOwnership(p);
        long firstLease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        assertThat(handOff(p, 1, 5, 77.5, facing(3), HandOffMode.RELEASE)).isEqualTo(new HandOffResult.HandedOff(2));

        CLOCK.addAndGet(400);
        assertThat(handOff(p, 1, 5, 77.5, facing(3), HandOffMode.RELEASE)).as("应答丢失后的重试")
                .isEqualTo(new HandOffResult.Fenced(new OwnerState(2, true, firstLease)));
        assertThat(owner(p).leaseUntil()).as("重试没有改动租约").isEqualTo(firstLease);

        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(3));
        long claimedLease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        CLOCK.addAndGet(400);
        assertThat(handOff(p, 1, 5, 77.5, facing(3), HandOffMode.RELEASE)).as("被第二条腿接管之后的重试")
                .isEqualTo(new HandOffResult.Fenced(new OwnerState(3, false, claimedLease)));
        assertThat(store.probeOwnership(p, TX_TIMEOUT)).as("探测读到同一行").contains(new OwnerState(3, false, claimedLease));
    }

    @Test
    void 交出并释放与最终写回同时发起_恰好一个成功_库里是同一份快照() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 5; i++) {
                long p = newPlayer(1440 + i, "释放并发" + i);
                store.claimOwnership(p);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<HandOffResult> handOff = pool.submit(() -> {
                    start.await();
                    return handOff(p, 1, 7, 33.5, facing(4), HandOffMode.RELEASE);
                });
                java.util.concurrent.Future<Boolean> finalSave = pool.submit(() -> {
                    start.await();
                    return store.saveStateAndRelease(save(p, 1, 7, 33.5), facing(4));
                });
                HandOffResult handOffResult = handOff.get(15, TimeUnit.SECONDS);
                boolean saved = finalSave.get(15, TimeUnit.SECONDS);

                boolean handedOff = handOffResult instanceof HandOffResult.HandedOff;
                assertThat(handedOff ^ saved).as("第 %d 轮恰好一个成功：交出=%s 写回=%s", i, handOffResult, saved).isTrue();
                assertThat(owner(p)).as("两种结局都没有持有者").isEqualTo(handedOff
                        ? new OwnerState(2, true, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis())
                        : new OwnerState(1, true, CLAIMED_LEASE));
                if (!handedOff) {
                    assertThat(handOffResult).isEqualTo(new HandOffResult.Fenced(new OwnerState(1, true, CLAIMED_LEASE)));
                }
                PlayerRow row = store.findPlayer(p).orElseThrow();
                assertThat(row.getSceneConfigId()).isEqualTo(7);
                assertThat(row.getPosX()).isEqualTo(33.5);
                assertThat(store.loadState(p)).isEqualTo(facing(4));
                assertThat(savedEpoch(p)).isEqualTo(1);
                assertThat(store.claimOwnership(p)).as("无论谁赢，下一次夺权都立即成功")
                        .isEqualTo(new ClaimResult.Claimed(handedOff ? 3 : 2));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 交出并释放与别的登录夺权同时发起（第二条腿 / 回家重登 / 另一台设备）：E 的租约没到期，夺权只能在交出提交之后成功。
     * 结论确定：交出恒成功；夺权要么拿到 E+2（库里由它持有），要么回 Held（库里是 (E+1, 已释放)，没有持有者，下一次夺权立即成功）。
     */
    @Test
    void 交出并释放与夺权同时发起_结论确定_库里至多一个持有者() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 5; i++) {
                long p = newPlayer(1450 + i, "夺权并发" + i);
                store.claimOwnership(p);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<HandOffResult> handOff = pool.submit(() -> {
                    start.await();
                    return handOff(p, 1, 7, 33.5, facing(4), HandOffMode.RELEASE);
                });
                java.util.concurrent.Future<ClaimResult> claim = pool.submit(() -> {
                    start.await();
                    return store.claimOwnership(p);
                });
                HandOffResult handOffResult = handOff.get(15, TimeUnit.SECONDS);
                ClaimResult claimResult = claim.get(15, TimeUnit.SECONDS);

                assertThat(handOffResult).as("第 %d 轮：E 的租约没到期，夺权不可能先于交出成功", i)
                        .isEqualTo(new HandOffResult.HandedOff(2));
                if (claimResult instanceof ClaimResult.Claimed claimed) {
                    assertThat(claimed.ownerEpoch()).isEqualTo(3);
                    assertThat(owner(p)).isEqualTo(new OwnerState(3, false, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis()));
                } else {
                    assertThat(claimResult).as("第 %d 轮", i).isInstanceOf(ClaimResult.Held.class);
                    assertThat(owner(p)).isEqualTo(new OwnerState(2, true, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis()));
                    assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(3));
                }
                assertThat(store.findPlayer(p).orElseThrow().getPosX()).as("夺到的是冻结快照").isEqualTo(33.5);
                assertThat(store.saveStateAndRelease(save(p, 1, 9, 99), facing(9))).as("E 的迟到写回被拒").isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 已提交的交出再执行一次_读到Fenced且带着上一次写下的租约值_供调用方认领() {
        long p = newPlayer(1331, "交出庚");
        store.claimOwnership(p);
        long firstLease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        assertThat(handOff(p, 1, 5, 77.5, facing(3))).isEqualTo(new HandOffResult.HandedOff(2));

        CLOCK.addAndGet(400);
        assertThat(handOff(p, 1, 5, 77.5, facing(3))).as("应答丢失后的重试")
                .isEqualTo(new HandOffResult.Fenced(new OwnerState(2, false, firstLease)));
        assertThat(owner(p).leaseUntil()).as("重试没有改动租约（这次尝试的租约值不会出现在库里）").isEqualTo(firstLease);
    }

    @Test
    void 在线存盘先提交则交出覆盖它_交出先提交则迟到的在线存盘被拒() {
        long p = newPlayer(1341, "交出辛");
        store.claimOwnership(p);
        assertThat(store.saveStateHeld(save(p, 1, 2, 10), facing(1))).isTrue();
        assertThat(handOff(p, 1, 5, 20, facing(2))).isEqualTo(new HandOffResult.HandedOff(2));
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(20);
        assertThat(store.loadState(p)).isEqualTo(facing(2));

        assertThat(store.saveStateHeld(save(p, 1, 2, 99), facing(9))).isFalse();
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(20);
        assertThat(store.loadState(p)).isEqualTo(facing(2));
    }

    /** 另一个连接：在自己的事务里执行 {@code body}（拿到行锁）后停住，{@code release} 后提交或回滚。 */
    private CompletableFuture<Void> holdRowLock(Runnable body, CountDownLatch locked, CountDownLatch release, boolean commit) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            body.run();
            locked.countDown();
            await(release);
            if (!commit) {
                status.setRollbackOnly();
            }
        }));
    }

    private static PlayerRow stamped(PlayerRow row) {
        row.setUpdatedAt(CLOCK.get());
        return row;
    }

    @Test
    void 交出持锁时最终写回等待_交出提交后最终写回被拒() throws Exception {
        long p = newPlayer(1351, "交出壬");
        store.claimOwnership(p);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        CompletableFuture<Void> holder = holdRowLock(() -> {
            assertThat(mapper.updateStateAndHandOff(stamped(save(p, 1, 5, 20)), lease, CLOCK.get() + MARGIN_MS, 0)).isEqualTo(1);
            mapper.upsertState(p, facing(2).toByteArray(), 1, CLOCK.get());
        }, locked, release, true);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> finalSave =
                CompletableFuture.supplyAsync(() -> store.saveStateAndRelease(save(p, 1, 9, 99), facing(9)));
        Thread.sleep(300);
        assertThat(finalSave).as("最终写回在行锁上等交出").isNotDone();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(finalSave.get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(owner(p)).isEqualTo(new OwnerState(2, false, lease));
        assertThat(store.loadState(p)).isEqualTo(facing(2));
    }

    @Test
    void 最终写回持锁时交出等待_写回提交后交出回Fenced且什么也不改() throws Exception {
        long p = newPlayer(1352, "交出癸");
        store.claimOwnership(p);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdRowLock(() -> {
            assertThat(mapper.updateStateAndRelease(stamped(save(p, 1, 2, 20)))).isEqualTo(1);
            mapper.upsertState(p, facing(0.75).toByteArray(), 1, CLOCK.get());
        }, locked, release, true);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<HandOffResult> handOff = CompletableFuture.supplyAsync(() -> handOff(p, 1, 5, 77.5, facing(3)));
        Thread.sleep(300);
        assertThat(handOff).as("交出在行锁上等最终写回").isNotDone();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(handOff.get(10, TimeUnit.SECONDS)).isEqualTo(new HandOffResult.Fenced(new OwnerState(1, true, CLAIMED_LEASE)));
        assertThat(store.findPlayer(p).orElseThrow().getPosX()).isEqualTo(20);
        assertThat(store.loadState(p)).isEqualTo(facing(0.75));
    }

    @Test
    void 交出与最终写回同时发起_恰好一个成功_库里是同一份快照() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 5; i++) {
                long p = newPlayer(1360 + i, "并发" + i);
                store.claimOwnership(p);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<HandOffResult> handOff = pool.submit(() -> {
                    start.await();
                    return handOff(p, 1, 7, 33.5, facing(4));
                });
                java.util.concurrent.Future<Boolean> finalSave = pool.submit(() -> {
                    start.await();
                    return store.saveStateAndRelease(save(p, 1, 7, 33.5), facing(4));
                });
                HandOffResult handOffResult = handOff.get(15, TimeUnit.SECONDS);
                boolean saved = finalSave.get(15, TimeUnit.SECONDS);

                boolean handedOff = handOffResult instanceof HandOffResult.HandedOff;
                assertThat(handedOff ^ saved).as("第 %d 轮恰好一个成功：交出=%s 写回=%s", i, handOffResult, saved).isTrue();
                assertThat(owner(p)).isEqualTo(handedOff ? new OwnerState(2, false, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis())
                        : new OwnerState(1, true, CLAIMED_LEASE));
                if (!handedOff) {
                    assertThat(handOffResult).isEqualTo(new HandOffResult.Fenced(new OwnerState(1, true, CLAIMED_LEASE)));
                }
                PlayerRow row = store.findPlayer(p).orElseThrow();
                assertThat(row.getSceneConfigId()).isEqualTo(7);
                assertThat(row.getPosX()).isEqualTo(33.5);
                assertThat(store.loadState(p)).isEqualTo(facing(4));
                assertThat(savedEpoch(p)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 探测（结局不明之后的加锁读）

    @Test
    void 探测_没人持锁时读到当前归属_玩家不存在为空() {
        long p = newPlayer(1371, "探测甲");
        store.claimOwnership(p);
        assertThat(store.probeOwnership(p, TX_TIMEOUT)).contains(new OwnerState(1, false, CLAIMED_LEASE));
        assertThat(store.probeOwnership(424242, TX_TIMEOUT)).isEmpty();
    }

    @Test
    void 探测_另一连接持有未提交的交出时阻塞_提交后读到新epoch与它写下的租约() throws Exception {
        assertProbeWaitsForInFlightHandOff(1372, true);
    }

    @Test
    void 探测_另一连接持有未提交的交出时阻塞_回滚后读到原epoch() throws Exception {
        assertProbeWaitsForInFlightHandOff(1373, false);
    }

    @Test
    void 探测_另一连接持有未提交的交出并释放时阻塞_提交后读到E加1已释放与它写下的租约() throws Exception {
        assertProbeWaitsForInFlightHandOff(1376, true, HandOffMode.RELEASE);
    }

    @Test
    void 探测_另一连接持有未提交的交出并释放时阻塞_回滚后读到原epoch未释放() throws Exception {
        assertProbeWaitsForInFlightHandOff(1377, false, HandOffMode.RELEASE);
    }

    /** 交出并释放提交后、探测之前又被别的登录夺权：探测读到 E+2（game-scene 据此判 Superseded，按被接管踢 2017）。 */
    @Test
    void 探测_交出并释放提交后又被另一连接夺权_读到E加2未释放() throws Exception {
        long p = newPlayer(1378, "探测丙");
        store.claimOwnership(p);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        CompletableFuture<Void> holder = holdRowLock(() -> assertThat(mapper.updateStateAndHandOff(
                stamped(save(p, 1, 5, 20)), lease, CLOCK.get() + MARGIN_MS, 1)).isEqualTo(1), locked, release, true);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        CLOCK.addAndGet(1_000);
        assertThat(store.claimOwnership(p)).as("另一连接（第二条腿）立即夺到 E+2").isEqualTo(new ClaimResult.Claimed(3));
        assertThat(store.probeOwnership(p, TX_TIMEOUT))
                .contains(new OwnerState(3, false, CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis()));
    }

    private void assertProbeWaitsForInFlightHandOff(long playerId, boolean commit) throws Exception {
        assertProbeWaitsForInFlightHandOff(playerId, commit, HandOffMode.HOLD);
    }

    /** 另一个连接按 {@code mode} 执行交出的那条 UPDATE、拿住行锁不提交：探测必须等它结束，再按提交 / 回滚读到确定的行。 */
    private void assertProbeWaitsForInFlightHandOff(long playerId, boolean commit, HandOffMode mode) throws Exception {
        long p = newPlayer(playerId, "探测" + playerId);
        store.claimOwnership(p);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long lease = CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis();
        boolean releasedOnCommit = mode == HandOffMode.RELEASE;
        CompletableFuture<Void> holder = holdRowLock(() -> assertThat(mapper.updateStateAndHandOff(
                stamped(save(p, 1, 5, 20)), lease, CLOCK.get() + MARGIN_MS, releasedOnCommit ? 1 : 0)).isEqualTo(1),
                locked, release, commit);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<java.util.Optional<OwnerState>> probe =
                CompletableFuture.supplyAsync(() -> store.probeOwnership(p, TX_TIMEOUT));
        Thread.sleep(300);
        assertThat(probe).as("加锁读等在途事务结束").isNotDone();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(probe.get(10, TimeUnit.SECONDS)).contains(commit
                ? new OwnerState(2, releasedOnCommit, lease) : new OwnerState(1, false, CLAIMED_LEASE));
    }

    @Test
    void 探测_行锁被占超过时限_探测失败而不是给出结论() throws Exception {
        long p = newPlayer(1374, "探测乙");
        store.claimOwnership(p);
        assertTimesOutOnHeldLock(p, () -> store.probeOwnership(p, Duration.ofSeconds(1)));
    }

    @Test
    void 交出_行锁被占超过时限_抛超时且什么也没改() throws Exception {
        long p = newPlayer(1375, "交出子");
        store.claimOwnership(p);
        List<Object> before = everything(p);
        assertTimesOutOnHeldLock(p, () -> store.handOffOwnership(save(p, 1, 5, 20), facing(2),
                CLOCK.get() + PlayerStore.OWNER_LEASE.toMillis(), CLOCK.get() + MARGIN_MS, Duration.ofSeconds(1)));
        assertThat(everything(p)).isEqualTo(before);
    }

    /**
     * 另一个连接加锁读住这一行、一直不放：{@code call} 必须在事务时限（1 s）附近以超时失败——不能等到锁释放后给出结论。
     * 真 MySQL 上由 JDBC 查询超时（KILL QUERY）打断行锁等待；H2 的行锁等待只认 LOCK_TIMEOUT，不受查询超时约束，
     * 所以只在真 MySQL 上跑（{@code -Dgame.it.mysql}）。
     */
    private void assertTimesOutOnHeldLock(long playerId, Runnable call) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MYSQL_URL != null, "H2 的行锁等待不受查询超时约束，需要 -Dgame.it.mysql");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdRowLock(() -> mapper.selectOwnerForUpdate(playerId), locked, release, false);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(call::run).isInstanceOfAny(
                    org.springframework.dao.TransientDataAccessException.class,
                    org.springframework.transaction.TransactionTimedOutException.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).as("由事务时限打断，而不是 innodb_lock_wait_timeout").isBetween(900L, 4_000L);
        } finally {
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        }
    }

    // ================================================================ 回合制战斗结算账本（scene-battle-spec §13.3；player_state.battle_ledger）

    /** 一局的应用时刻（账本条目的 applied_at_ms）：按 battle_id 定，方便核对。 */
    private static long appliedAt(long battleId) {
        return 1_700_000_000_000L + (battleId & 0xFFFF);
    }

    /** 只带战斗结算账本的玩法数据：按给定顺序各一条（scene 写出时已按 battle_id 无符号升序，这里原样存、原样取）。 */
    private static PlayerState ledger(long... battleIds) {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (long battleId : battleIds) {
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(battleId).setAppliedAtMs(appliedAt(battleId)));
        }
        return PlayerState.newBuilder().setBattleLedger(ledger).build();
    }

    private static List<Long> ledgerIds(PlayerState state) {
        return state.getBattleLedger().getAppliedList().stream().map(BattleLedgerEntry::getBattleId).toList();
    }

    /**
     * 账本是 {@code player_state} 的一部分：在线存盘、最终写回都整份带上，读回来逐字节等价——条目顺序不变、battle_id 超过 2^63 不走样、
     * 应用时刻在、各层的未知字段（新版本 scene 写的、旧版本读到）原样保留。每次写是<b>整份覆盖</b>：scene 销账后摘掉的条目随下一次存盘从库里消失。
     */
    @Test
    void 战斗账本随player_state往返_在线存盘与最终写回都带_条目顺序与未知字段原样_整份覆盖() {
        long p = newPlayer(1401, "账本甲");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        long big = 0x8000_0000_0000_0001L;
        com.google.protobuf.UnknownFieldSet extra = com.google.protobuf.UnknownFieldSet.newBuilder()
                .addField(99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(5).build()).build();
        PlayerState state = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.5).setY(1).setZ(2))
                .setBattleLedger(BattleLedgerState.newBuilder()
                        .addApplied(BattleLedgerEntry.newBuilder().setBattleId(7).setAppliedAtMs(appliedAt(7)))
                        .addApplied(BattleLedgerEntry.newBuilder().setBattleId(big).setAppliedAtMs(appliedAt(big)).setUnknownFields(extra))
                        .setUnknownFields(extra))
                .build();

        assertThat(store.saveStateHeld(save(p, 1, 2, 10), state)).isTrue();

        PlayerState loaded = store.loadState(p);
        assertThat(loaded).as("整份玩法数据逐字段相等（含未知字段）").isEqualTo(state);
        assertThat(loaded.toByteString()).isEqualTo(state.toByteString());
        assertThat(ledgerIds(loaded)).as("顺序原样、超过 2^63 的 battle_id 不走样").containsExactly(7L, big);
        assertThat(loaded.getBattleLedger().getApplied(1).getAppliedAtMs()).isEqualTo(appliedAt(big));
        assertThat(loaded.getBattleLedger().getApplied(1).getUnknownFields()).isEqualTo(extra);
        assertThat(loaded.getBattleLedger().getUnknownFields()).isEqualTo(extra);
        assertThat(loaded.getFacing().getX()).as("账本与别的玩法数据在同一份里").isEqualTo(0.5);
        assertThat(savedEpoch(p)).isEqualTo(1);

        // 销账后摘掉 7：下一次在线存盘整份覆盖，库里不再有它
        assertThat(store.saveStateHeld(save(p, 1, 2, 11), ledger(big))).isTrue();
        assertThat(ledgerIds(store.loadState(p))).containsExactly(big);

        // 最终写回并释放；下一个写者夺权后加载到的就是这份账本
        assertThat(store.saveStateAndRelease(save(p, 1, 2, 20), ledger(big, 9))).isTrue();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));
        assertThat(store.loadState(p)).isEqualTo(ledger(big, 9));

        // 账本清空也落得下去（空账本 ≠ 保留旧账本）
        assertThat(store.saveStateHeld(save(p, 2, 2, 21), PlayerState.getDefaultInstance())).isTrue();
        assertThat(store.loadState(p).hasBattleLedger()).isFalse();
    }

    /**
     * 被围栏拒绝的写不落账本——scene 的销账以「条目已落库」为前提（{@code onProgressSaved(SAVED)} 之后才 ACK），
     * 被拒的写要是留下了账本条目，就会出现「库里有条目、奖励却没落库」。旧 epoch 的在线存盘 / 最终写回、已释放之后的在线存盘都不行。
     */
    @Test
    void 被围栏拒绝的写不落账本_旧epoch的在线存盘与最终写回_已释放后的在线存盘() {
        long p = newPlayer(1402, "账本乙");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.saveStateAndRelease(save(p, 1, 2, 20), ledger(7))).isTrue();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));
        List<Object> before = everything(p);

        // 旧写者（epoch 1）迟到的写：带着「又应用了一局 8」的账本
        assertThat(store.saveStateHeld(save(p, 1, 2, 99), ledger(7, 8))).isFalse();
        assertThat(store.saveStateAndRelease(save(p, 1, 2, 99), ledger(7, 8))).isFalse();

        assertThat(store.loadState(p)).as("8 没有落库").isEqualTo(ledger(7));
        assertThat(savedEpoch(p)).isEqualTo(1);
        assertThat(everything(p)).as("被拒的写什么也没改").isEqualTo(before);

        // 当前写者（epoch 2）最终写回并释放之后，它自己迟到的在线存盘同样被拒
        assertThat(store.saveStateAndRelease(save(p, 2, 2, 30), ledger(7))).isTrue();
        before = everything(p);
        assertThat(store.saveStateHeld(save(p, 2, 2, 99), ledger(7, 9))).as("同 epoch，但已释放").isFalse();
        assertThat(store.loadState(p)).isEqualTo(ledger(7));
        assertThat(everything(p)).isEqualTo(before);

        // 对照：持有者的写照常落账本
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(3));
        assertThat(store.saveStateHeld(save(p, 3, 2, 31), ledger(7, 9))).isTrue();
        assertThat(store.loadState(p)).isEqualTo(ledger(7, 9));
        assertThat(savedEpoch(p)).isEqualTo(3);
    }

    /**
     * 交出事务（跨节点换图，handoff-spec §5.2）把冻结快照里的账本一并带过去：提交后库里就是它（由交出方 E 写入），目标节点以 E+1 加载到，
     * 进场即判 durable、即销账。没提交的交出（剩余租约不足、被围栏拒绝）不落账本。
     */
    @Test
    void 交出事务把账本带过去_新epoch加载到冻结快照里的账本_没提交的交出不落账本() {
        long p = newPlayer(1403, "账本丙");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(store.saveStateHeld(save(p, 1, 2, 10), ledger(7))).isTrue();

        // 没提交：剩余租约比安全边际少 1 ms
        CLOCK.addAndGet(PlayerStore.OWNER_LEASE.toMillis() - MARGIN_MS + 1);
        List<Object> before = everything(p);
        assertThat(handOff(p, 1, 5, 77.5, ledger(7, 8))).isInstanceOf(HandOffResult.LeaseTooShort.class);
        assertThat(store.loadState(p)).as("没提交的交出不落账本").isEqualTo(ledger(7));
        assertThat(everything(p)).isEqualTo(before);

        CLOCK.decrementAndGet();
        assertThat(handOff(p, 1, 5, 77.5, ledger(7, 8))).isEqualTo(new HandOffResult.HandedOff(2));

        assertThat(store.loadState(p)).as("冻结快照里的账本随交出落库").isEqualTo(ledger(7, 8));
        assertThat(savedEpoch(p)).as("由交出方 E 写入").isEqualTo(1);
        assertThat(owner(p).ownerEpoch()).isEqualTo(2);
        assertThat(owner(p).released()).isFalse();

        // 交出方（旧 epoch）之后的任何写都碰不到这份账本
        before = everything(p);
        assertThat(handOff(p, 1, 5, 1, ledger(7, 8, 9))).isInstanceOf(HandOffResult.Fenced.class);
        assertThat(store.saveStateHeld(save(p, 1, 9, 99), ledger(7, 8, 9))).isFalse();
        assertThat(store.saveStateAndRelease(save(p, 1, 9, 99), PlayerState.getDefaultInstance())).isFalse();
        assertThat(store.loadState(p)).isEqualTo(ledger(7, 8));
        assertThat(everything(p)).isEqualTo(before);

        // 目标节点（E+1）销账后摘掉 7，在线存盘
        assertThat(store.saveStateHeld(save(p, 2, 6, 1), ledger(8))).isTrue();
        assertThat(store.loadState(p)).isEqualTo(ledger(8));
        assertThat(savedEpoch(p)).isEqualTo(2);
    }

    /** 角色名（战斗快照里的 {@code name} 取自它）随 player 行读出；在线存盘、交出、最终写回都不动它。 */
    @Test
    void 角色名随player行读出_写回与交出都不改名() {
        long p = newPlayer(1404, "账本丁");
        assertThat(store.findPlayer(p).orElseThrow().getName()).isEqualTo("账本丁");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));

        assertThat(store.saveStateHeld(save(p, 1, 2, 10), ledger(7))).isTrue();
        assertThat(store.findPlayer(p).orElseThrow().getName()).isEqualTo("账本丁");
        assertThat(handOff(p, 1, 5, 77.5, ledger(7))).isEqualTo(new HandOffResult.HandedOff(2));
        assertThat(store.findPlayer(p).orElseThrow().getName()).isEqualTo("账本丁");
        assertThat(store.saveStateAndRelease(save(p, 2, 5, 80), ledger())).isTrue();

        PlayerRow row = store.findPlayer(p).orElseThrow();
        assertThat(row.getName()).isEqualTo("账本丁");
        assertThat(row.getPosX()).as("（对照）这几次写确实落库了").isEqualTo(80);
    }

    // ================================================================ 建角上限

    @Test
    void 交易原实例与收据同事务落库_新读取者恢复_旧owner拒写不覆盖_ACK只清快照() {
        long p=newPlayer(6011,"交易持久化");
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
        var unknown=com.google.protobuf.UnknownFieldSet.newBuilder().addField(900,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build();
        var pet=com.game.player.store.state.PetEntry.newBuilder().setPetId(800).setPetTableId(1).setName("原宝宝").setLevel(30).putAllocated(1,17).setUnknownFields(unknown).build();
        var item=com.game.player.store.state.BagItemState.newBuilder().setItemUuid(700).setConfigId(1).setStackSize(1).setUnknownFields(unknown).build();
        var receipt=com.game.player.store.state.AssetOpReceiptState.newBuilder().setSeq(1).setCorrelationId(999).setRequestDigest(com.google.protobuf.ByteString.copyFrom(new byte[32])).setSnapshotProto(item.toByteString()).build();
        var stream=com.game.player.store.state.AssetOpStreamLedgerState.newBuilder().setStream(3).setStreamEpoch(1).setMaxSeq(1).addReceipts(receipt);
        for(int word=0;word<16;word++){stream.addSeenBits(word==0?1:0).addAppliedBits(word==0?1:0);}
        var state=PlayerState.newBuilder().setBag(com.game.player.store.state.BagState.newBuilder().addItems(item)).setPets(com.game.player.store.state.PetState.newBuilder().addPets(pet)).setAssetLedger(com.game.player.store.state.AssetOpLedgerState.newBuilder().addStreams(stream)).build();
        assertThat(store.saveStateAndRelease(save(p,1,1,20),state)).isTrue();
        // 新的仓储对象不读scene内存，直接从生产Mapper/SQL恢复完整未知字段及同一收据。
        PlayerStore restarted=new PlayerStore(mapper,CLOCK::get,context.getBean(PlatformTransactionManager.class));
        assertThat(restarted.loadState(p)).isEqualTo(state);
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(2));
        assertThat(store.saveStateHeld(save(p,1,9,0),PlayerState.getDefaultInstance())).isFalse();
        assertThat(restarted.loadState(p)).isEqualTo(state);
        var acked=state.toBuilder().setAssetLedger(state.getAssetLedger().toBuilder().setStreams(0,
                stream.setReceipts(0,receipt.toBuilder().clearSnapshotProto()))).build();
        assertThat(store.saveStateHeld(save(p,2,1,21),acked)).isTrue();
        assertThat(restarted.loadState(p)).isEqualTo(acked);
        assertThat(restarted.loadState(p).getPets().getPets(0)).isEqualTo(pet);
        assertThat(restarted.loadState(p).getAssetLedger().getStreams(0).getReceipts(0).getRequestDigest()).hasSize(32);
    }

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

    // ================================================================ 带时限的建角 / 夺权（mmorpg client-request-ordering.md §6.1）

    /** 调用方剩余的预算；测试里的单调时钟只在钩子里拨动，预算本身不会自己耗完。 */
    private static final Duration BUDGET = Duration.ofMillis(1500);

    private static PlayerRow freshRow(long playerId) {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(playerId);
        row.setAccount(ACCOUNT);
        row.setZoneId(1);
        row.setClassId(1);
        row.setGender(1);
        return row;
    }

    /** 在真 Mapper 外包一层：每个方法执行完（语句已在事务里发出）之后，把方法名交给 {@code afterEach}。 */
    private PlayerMapper observed(java.util.function.Consumer<String> afterEach) {
        return (PlayerMapper) java.lang.reflect.Proxy.newProxyInstance(PlayerMapper.class.getClassLoader(),
                new Class<?>[] {PlayerMapper.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(mapper, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    afterEach.accept(method.getName());
                    return result;
                });
    }

    /** 与 Config 里同一个事务管理器、同一个数据源，只把单调时钟换成可拨动的 {@code nanos}。 */
    private PlayerStore timedStore(PlayerMapper playerMapper, AtomicLong nanos) {
        return new PlayerStore(playerMapper, CLOCK::get, context.getBean(PlatformTransactionManager.class), nanos::get);
    }

    @Test
    void 带时限建角_插入已发出后调用方截止到点_回滚不提交_回TIMED_OUT_名字没被占() {
        AtomicLong nanos = new AtomicLong();
        PlayerStore timed = timedStore(observed(method -> {
            if (method.equals("insertPlayer")) {
                nanos.addAndGet(BUDGET.toNanos());
            }
        }), nanos);

        PlayerStore.CreateOutcome outcome = timed.createPlayerWithinCap(freshRow(4001), 5, List.of("超时甲"), BUDGET);

        assertThat(outcome).isEqualTo(new PlayerStore.CreateOutcome(PlayerStore.CreateStatus.TIMED_OUT, List.of()));
        assertThat(store.findPlayer(4001)).as("插入随事务回滚").isEmpty();
        assertThat(store.countPlayers(ACCOUNT)).isZero();
        assertThat(store.createPlayerWithinCap(freshRow(4002), 5, List.of("超时甲")).status())
                .as("名字与账号行锁都没留下").isEqualTo(PlayerStore.CreateStatus.CREATED);
    }

    @Test
    void 带时限建角与夺权_拿到连接时截止已到_一条语句都不发() {
        AtomicLong nanos = new AtomicLong();
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        DataSourceTransactionManager slowBegin = new DataSourceTransactionManager(context.getBean(DataSource.class)) {
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                nanos.addAndGet(BUDGET.toNanos());   // 取连接耗完了预算（连接池最多等 3 s）
            }
        };
        PlayerStore timed = new PlayerStore(observed(calls::add), CLOCK::get, slowBegin, nanos::get);

        assertThat(timed.createPlayerWithinCap(freshRow(4003), 5, List.of("超时乙"), BUDGET).status())
                .isEqualTo(PlayerStore.CreateStatus.TIMED_OUT);
        assertThat(timed.claimOwnership(4003, BUDGET)).isEqualTo(new ClaimResult.TimedOut());
        assertThat(calls).as("不锁账号行、不碰 player 行").isEmpty();
        assertThat(store.countPlayers(ACCOUNT)).isZero();
    }

    @Test
    void 带时限建角_预算内照常提交() {
        PlayerStore timed = timedStore(observed(method -> { }), new AtomicLong());

        assertThat(timed.createPlayerWithinCap(freshRow(4004), 5, List.of("准时甲"), BUDGET).status())
                .isEqualTo(PlayerStore.CreateStatus.CREATED);
        assertThat(store.findPlayer(4004)).map(PlayerRow::getName).contains("准时甲");
    }

    @Test
    void 带时限夺权_夺权语句已发出后调用方截止到点_回滚_epoch不变_下一次夺权仍得第一代() {
        long p = newPlayer(4011, "超时丙");
        AtomicLong nanos = new AtomicLong();
        PlayerStore timed = timedStore(observed(method -> {
            if (method.equals("claimOwnerEpoch")) {
                nanos.addAndGet(BUDGET.toNanos());
            }
        }), nanos);

        assertThat(timed.claimOwnership(p, BUDGET)).isEqualTo(new ClaimResult.TimedOut());

        assertThat(store.findPlayer(p).orElseThrow().getOwnerEpoch()).as("epoch 自增随事务回滚").isZero();
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
    }

    @Test
    void 带时限夺权_预算内照常夺权_仍被持有照常回Held_不存在回NotFound() {
        long p = newPlayer(4012, "准时乙");
        PlayerStore timed = timedStore(observed(method -> { }), new AtomicLong());

        assertThat(timed.claimOwnership(p, BUDGET)).isEqualTo(new ClaimResult.Claimed(1));
        assertThat(timed.claimOwnership(p, BUDGET)).isEqualTo(new ClaimResult.Held(1));
        assertThat(timed.claimOwnership(424243, BUDGET)).isEqualTo(new ClaimResult.NotFound());
    }

    @Test
    void 带时限建角_账号行锁被占超过时限_回TIMED_OUT不抛异常_什么也没写() throws Exception {
        assertTimedOutOnHeldLock(() -> mapper.lockAccount(ACCOUNT),
                () -> store.createPlayerWithinCap(freshRow(4021), 5, List.of("锁等甲"), Duration.ofSeconds(1)),
                new PlayerStore.CreateOutcome(PlayerStore.CreateStatus.TIMED_OUT, List.of()));
        assertThat(store.countPlayers(ACCOUNT)).isZero();
    }

    @Test
    void 带时限夺权_行锁被占超过时限_回TimedOut不抛异常_没有夺权() throws Exception {
        long p = newPlayer(4022, "锁等乙");
        assertTimedOutOnHeldLock(() -> mapper.selectOwnerForUpdate(p), () -> store.claimOwnership(p, Duration.ofSeconds(1)),
                new ClaimResult.TimedOut());
        assertThat(store.claimOwnership(p)).isEqualTo(new ClaimResult.Claimed(1));
    }

    /**
     * 同 {@link #assertTimesOutOnHeldLock}，但带时限的建角 / 夺权把语句超时折成结果：{@code call} 在事务时限（1 s）附近返回
     * {@code timedOut}，不抛异常。只在真 MySQL 上跑，理由同上。
     */
    private <T> void assertTimedOutOnHeldLock(Runnable lock, java.util.function.Supplier<T> call, T timedOut)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MYSQL_URL != null, "H2 的行锁等待不受查询超时约束，需要 -Dgame.it.mysql");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdRowLock(lock, locked, release, false);
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        try {
            assertThat(call.get()).isEqualTo(timedOut);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsedMs).as("由事务时限打断，而不是 innodb_lock_wait_timeout").isBetween(900L, 4_000L);
        } finally {
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
        }
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
            String database = "game_it_" + UUID.randomUUID().toString().replace("-", "");
            DataSource ds;
            if (MYSQL_URL != null) {
                // 真 MySQL：每个用例一个临时库（URL 里 createDatabaseIfNotExist），用例结束删库。
                ds = new DriverManagerDataSource(MYSQL_URL + "/" + database
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES'",
                        System.getProperty("game.it.mysql.user", "root"),
                        System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""));
                MYSQL_DATABASES.add(database);
            } else {
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
                ds = h2;
            }
            new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql")).execute(ds);
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
        PlayerStore playerStore(PlayerMapper playerMapper, PlatformTransactionManager transactionManager) {
            return new PlayerStore(playerMapper, CLOCK::get, transactionManager);
        }
    }
}
