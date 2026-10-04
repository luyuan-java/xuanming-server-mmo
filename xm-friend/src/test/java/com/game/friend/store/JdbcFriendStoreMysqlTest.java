package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.friend.store.FriendStore.AcceptResult;
import com.game.friend.store.FriendStore.AddResult;
import com.game.friend.store.FriendStore.BlockResult;
import com.game.friend.store.FriendStore.FriendEdge;
import com.game.friend.store.FriendStore.RejectResult;
import com.game.friend.store.FriendStore.RemoveResult;
import com.game.common.deadline.Deadline;
import com.game.friend.store.pb.FriendBlockRow;
import com.game.friend.store.pb.FriendCapacityRow;
import com.game.friend.store.pb.FriendEdgeRow;
import com.game.friend.store.pb.FriendRequestRow;
import com.game.pbmysql.PbMysql;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link JdbcFriendStore} 连真 MySQL 的测试（缺省跳过）：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量
 * XM_MYSQL_PASSWORD。建一次性库 {@code xm_friend_it_<随机>}（四张表经 xm-pbmysql 建），结束时删掉。
 *
 * <p>钉住 friend-spec.md §1.6：业务规则与检查优先级、上限的角色映射、缺行时权威计数、无符号玩家号，以及并发场景
 * （同一 target 的 16 个申请人、各不相同的 target、同一玩家并发拉黑 16 人、同一对玩家 Block × Accept / Add × Accept 交错、
 * 并发同意的硬上限）全程不出 1213、四条不变量成立；守卫之后的锁定语句都是完整主键点查。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class JdbcFriendStoreMysqlTest {

    private static final String BASE_URL = System.getProperty("xm.it.mysql");
    private static final String USER = System.getProperty("xm.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=5";
    private static final String DATABASE = "xm_friend_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final FriendLimits LIMITS = new FriendLimits(200, 50, 200, 200);

    private static DruidDataSource dataSource;
    private final AtomicInteger underflows = new AtomicInteger();
    private final Map<String, AtomicInteger> retries = new ConcurrentHashMap<>();

    @BeforeAll
    static void createDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + DATABASE + "` DEFAULT CHARACTER SET utf8mb4");
        }
        dataSource = new DruidDataSource();
        dataSource.setUrl(BASE_URL + "/" + DATABASE + PARAMS);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaxActive(64);
        dataSource.setMaxWait(10_000);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        PbMysql db = new PbMysql();
        db.register(FriendEdgeRow.getDefaultInstance());
        db.register(FriendRequestRow.getDefaultInstance());
        db.register(FriendCapacityRow.getDefaultInstance());
        db.register(FriendBlockRow.getDefaultInstance());
        try (Connection c = dataSource.getConnection()) {
            db.syncAll(c);
        }
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (dataSource != null) {
            dataSource.close();
        }
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
        }
    }

    @BeforeEach
    void truncate() throws SQLException {
        for (String table : List.of("friend", "friend_request", "friend_capacity", "friend_block")) {
            exec("TRUNCATE TABLE " + table);
        }
    }

    /** 每次调用一个宽裕的预算（测试关心的是 SQL 行为，不是超时）。 */
    private static Deadline d() {
        return Deadline.after(30_000);
    }

    private JdbcFriendStore store(FriendLimits limits) {
        return new JdbcFriendStore(dataSource, limits, System::currentTimeMillis, 10, new JdbcFriendStore.Events() {
            @Override
            public void guardRetry(String kind) {
                retries.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            }

            @Override
            public void countUnderflow() {
                underflows.incrementAndGet();
            }
        });
    }

    // ================================================================ 业务规则

    @Test
    void 申请_同意_双向收敛_计数与列表() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(1, 2, d())).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(1, 2, d())).isEqualTo(AddResult.ALREADY_SENT);
        // 对方也向我申请：不自动成为好友，双方各挂一条
        assertThat(s.addRequest(2, 1, d())).isEqualTo(AddResult.OK);
        assertThat(s.pendingRequests(2, 100, d())).extracting(FriendStore.PendingRequest::fromPlayerId).containsExactly(1L);
        assertThat(s.pendingRequests(1, 100, d())).extracting(FriendStore.PendingRequest::fromPlayerId).containsExactly(2L);

        assertThat(s.accept(1, 2, d())).isEqualTo(AcceptResult.OK);
        // 反向 pending 一并置已同意：1 的收件箱里不留孤儿
        assertThat(s.pendingRequests(1, 100, d())).isEmpty();
        assertThat(s.pendingRequests(2, 100, d())).isEmpty();
        assertThat(s.friends(1, 100, d())).extracting(FriendEdge::friendPlayerId).containsExactly(2L);
        assertThat(s.friends(2, 100, d())).extracting(FriendEdge::friendPlayerId).containsExactly(1L);
        assertThat(s.friends(1, 100, d()).get(0).sinceMs()).isEqualTo(s.friends(2, 100, d()).get(0).sinceMs());
        assertThat(capacity(1)).isEqualTo(1);
        assertThat(capacity(2)).isEqualTo(1);
        // 第二个同意：事务外前置就是 NO_PENDING
        assertThat(s.accept(2, 1, d())).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.addRequest(1, 2, d())).isEqualTo(AddResult.ALREADY_FRIENDS);
        assertInvariants();
    }

    @Test
    void 没有申请的同意不补容量行_拒绝后可以重新申请且刷新申请时间() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.accept(7, 8, d())).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(countRows("friend_capacity")).isZero();

        assertThat(s.addRequest(7, 8, d())).isEqualTo(AddResult.OK);
        long first = s.pendingRequests(8, 10, d()).get(0).requestTimeMs();
        assertThat(s.reject(7, 8, d())).isEqualTo(RejectResult.OK);
        assertThat(s.reject(7, 8, d())).isEqualTo(RejectResult.NO_PENDING);
        assertThat(s.pendingRequests(8, 10, d())).isEmpty();
        Thread.sleep(5);
        assertThat(s.addRequest(7, 8, d())).isEqualTo(AddResult.OK);
        assertThat(s.pendingRequests(8, 10, d()).get(0).requestTimeMs()).isGreaterThan(first);
        assertThat(updatedMsOf(7, 8)).isPositive(); // 凡改 status 的语句都写 updated_ms
        assertInvariants();
    }

    @Test
    void 删除好友_幂等_不是好友不补容量行() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.remove(3, 4, d())).isEqualTo(RemoveResult.NOT_FRIENDS);
        assertThat(countRows("friend_capacity")).isZero();
        befriend(s, 3, 4);
        assertThat(s.remove(4, 3, d())).isEqualTo(RemoveResult.REMOVED);
        assertThat(s.remove(4, 3, d())).isEqualTo(RemoveResult.NOT_FRIENDS);
        assertThat(s.friends(3, 10, d())).isEmpty();
        assertThat(capacity(3)).isZero();
        assertThat(capacity(4)).isZero();
        assertThat(underflows).hasValue(0);
        assertInvariants();
    }

    @Test
    void 拉黑_删边取消申请_双向都拒_同意拿到无申请_解除不恢复好友() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        befriend(s, 10, 11);
        assertThat(s.addRequest(10, 12, d())).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(12, 10, d())).isEqualTo(AddResult.OK);

        assertThat(s.block(10, 11, d())).isEqualTo(BlockResult.OK);
        assertThat(s.friends(10, 10, d())).isEmpty();
        assertThat(capacity(10)).isZero();
        assertThat(capacity(11)).isZero();
        // 拉黑两个方向都拒（同码、不泄露是谁拉黑了谁）
        assertThat(s.addRequest(10, 11, d())).isEqualTo(AddResult.BLOCKED);
        assertThat(s.addRequest(11, 10, d())).isEqualTo(AddResult.BLOCKED);

        assertThat(s.block(12, 10, d())).isEqualTo(BlockResult.OK);
        // 拉黑把两个方向的 pending 都置成终态：之后去同意拿到的是 NO_PENDING（不是 BLOCKED）
        assertThat(s.accept(12, 10, d())).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.accept(10, 12, d())).isEqualTo(AcceptResult.NO_PENDING);
        // 重复拉黑幂等；单向：A 拉黑了 B 不影响 B 拉黑 A
        assertThat(s.block(12, 10, d())).isEqualTo(BlockResult.OK);
        assertThat(s.block(10, 12, d())).isEqualTo(BlockResult.OK);
        assertThat(s.blocks(10, 10, d())).extracting(FriendStore.BlockEdge::blockedPlayerId).containsExactly(11L, 12L);

        s.unblock(10, 11, d());
        s.unblock(10, 11, d());
        assertThat(s.blocks(10, 10, d())).extracting(FriendStore.BlockEdge::blockedPlayerId).containsExactly(12L);
        assertThat(s.friends(10, 10, d())).isEmpty(); // 不恢复好友
        assertThat(s.addRequest(10, 11, d())).isEqualTo(AddResult.OK);
        assertInvariants();
    }

    @Test
    void 黑名单满_已拉黑过的目标照样成功() {
        JdbcFriendStore s = store(new FriendLimits(200, 50, 200, 2));
        assertThat(s.block(20, 21, d())).isEqualTo(BlockResult.OK);
        assertThat(s.block(20, 22, d())).isEqualTo(BlockResult.OK);
        assertThat(s.block(20, 23, d())).isEqualTo(BlockResult.BLOCK_LIST_FULL);
        assertThat(s.block(20, 21, d())).isEqualTo(BlockResult.OK);
    }

    @Test
    void 检查优先级_拉黑优先于已申请() {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(30, 31, d())).isEqualTo(AddResult.OK);
        s.block(31, 30, d());
        assertThat(s.addRequest(30, 31, d())).isEqualTo(AddResult.BLOCKED);
    }

    @Test
    void 出站_入站_好友数上限_满的角色映射() {
        JdbcFriendStore pending = store(new FriendLimits(200, 2, 200, 200));
        assertThat(pending.addRequest(40, 41, d())).isEqualTo(AddResult.OK);
        assertThat(pending.addRequest(40, 42, d())).isEqualTo(AddResult.OK);
        assertThat(pending.addRequest(40, 43, d())).isEqualTo(AddResult.TOO_MANY_PENDING);

        JdbcFriendStore inbox = store(new FriendLimits(200, 50, 2, 200));
        assertThat(inbox.addRequest(51, 50, d())).isEqualTo(AddResult.OK);
        assertThat(inbox.addRequest(52, 50, d())).isEqualTo(AddResult.OK);
        assertThat(inbox.addRequest(53, 50, d())).isEqualTo(AddResult.TARGET_INBOX_FULL);

        JdbcFriendStore full = store(new FriendLimits(1, 50, 200, 200));
        befriend(full, 60, 61);
        assertThat(full.addRequest(60, 62, d())).isEqualTo(AddResult.SENDER_FULL);
        assertThat(full.addRequest(62, 60, d())).isEqualTo(AddResult.RECEIVER_FULL);
        // 同意：原申请人满 → SENDER_FULL，我（接受者）满 → ACCEPTOR_FULL
        JdbcFriendStore loose = store(LIMITS);
        assertThat(loose.addRequest(60, 63, d())).isEqualTo(AddResult.OK);
        assertThat(loose.addRequest(64, 61, d())).isEqualTo(AddResult.OK);
        assertThat(full.accept(60, 63, d())).isEqualTo(AcceptResult.SENDER_FULL);
        assertThat(full.accept(64, 61, d())).isEqualTo(AcceptResult.ACCEPTOR_FULL);
    }

    @Test
    void 容量行被回收后_按好友表的权威边数重建_绝不猜零() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        befriend(s, 70, 71);
        exec("DELETE FROM friend_capacity WHERE player_id = 70");
        assertThat(s.addRequest(70, 72, d())).isEqualTo(AddResult.OK);
        assertThat(capacity(70)).isEqualTo(1);
        assertInvariants();
    }

    @Test
    void 大于2的63次方的玩家号按无符号存取() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        long big = -5L;              // 18446744073709551611
        long bigger = Long.MIN_VALUE; // 9223372036854775808
        assertThat(s.addRequest(big, bigger, d())).isEqualTo(AddResult.OK);
        assertThat(s.pendingRequests(bigger, 10, d())).singleElement()
                .satisfies(r -> assertThat(r.fromPlayerId()).isEqualTo(big));
        assertThat(s.accept(big, bigger, d())).isEqualTo(AcceptResult.OK);
        assertThat(s.friends(big, 10, d())).extracting(FriendEdge::friendPlayerId).containsExactly(bigger);
        assertThat(s.friends(bigger, 10, d())).extracting(FriendEdge::friendPlayerId).containsExactly(big);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(player_id) FROM friend")) {
            rs.next();
            assertThat(rs.getObject(1)).isEqualTo(new BigInteger(Long.toUnsignedString(big)));
        }
        assertInvariants();
    }

    @Test
    void 非法玩家对抛参数异常() {
        JdbcFriendStore s = store(LIMITS);
        assertThatThrownBy(() -> s.addRequest(0, 1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.accept(5, 5, d())).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.remove(5, 5, d())).isEqualTo(RemoveResult.NOT_FRIENDS);
    }

    // ================================================================ 并发（全程不得出现 1213）

    @Test
    void 并发_16个申请人发给同一个target_与各不相同的target() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        List<Callable<Object>> same = new ArrayList<>();
        List<Callable<Object>> distinct = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            long from = 1000 + i;
            same.add(() -> s.addRequest(from, 999, d()));
            distinct.add(() -> s.addRequest(from, 2000 + from, d()));
        }
        assertThat(runConcurrently(same)).containsOnly(AddResult.OK);
        assertThat(runConcurrently(distinct)).containsOnly(AddResult.OK);
        assertThat(s.pendingRequests(999, 100, d())).hasSize(16);
        assertInvariants();
    }

    @Test
    void 并发_同一玩家拉黑16个目标() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        for (int i = 0; i < 16; i++) {
            if (i % 2 == 0) {
                befriend(s, 3000, 3100 + i);
            } else {
                s.addRequest(3100 + i, 3000, d());
            }
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            long target = 3100 + i;
            tasks.add(() -> s.block(3000, target, d()));
        }
        assertThat(runConcurrently(tasks)).containsOnly(BlockResult.OK);
        assertThat(s.friends(3000, 100, d())).isEmpty();
        assertThat(capacity(3000)).isZero();
        assertInvariants();
    }

    @Test
    void 并发_同一对玩家的Block与Accept交错_Add与Accept() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        for (int round = 0; round < 8; round++) {
            long a = 4000 + round * 2L;
            long b = a + 1;
            s.addRequest(a, b, d());
            List<Callable<Object>> tasks = List.of(() -> s.block(b, a, d()), () -> s.accept(a, b, d()));
            runConcurrently(tasks);
            long c = 5000 + round * 2L;
            long d = c + 1;
            s.addRequest(c, d, d());
            runConcurrently(List.of(() -> s.addRequest(d, c, d()), () -> s.accept(c, d, d())));
        }
        assertInvariants();
    }

    @Test
    void 并发同意的好友数硬上限() throws Exception {
        JdbcFriendStore s = store(new FriendLimits(3, 50, 200, 200));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long from = 6100 + i;
            assertThat(s.addRequest(from, 6000, d())).isEqualTo(AddResult.OK);
            tasks.add(() -> s.accept(from, 6000, d()));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results.stream().filter(AcceptResult.OK::equals).count()).isEqualTo(3);
        assertThat(results.stream().filter(r -> r != AcceptResult.OK)).containsOnly(AcceptResult.ACCEPTOR_FULL);
        assertThat(capacity(6000)).isEqualTo(3);
        assertInvariants();
    }

    @Test
    void 并发_预置交叉pending_两个不相干的pair并发Add() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(7001, 7002, d())).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(7003, 7004, d())).isEqualTo(AddResult.OK);
        for (int round = 0; round < 5; round++) {
            runConcurrently(List.of(() -> s.addRequest(7002, 7003, d()), () -> s.addRequest(7004, 7001, d()),
                    () -> s.addRequest(7003, 7002, d()), () -> s.addRequest(7001, 7004, d())));
        }
        assertInvariants();
    }

    @Test
    void 并发_回收的DELETE压着X锁时两个ensure排队_不出1213() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        exec("INSERT INTO friend_capacity (player_id, friend_count, created_ms) VALUES (8000, 0, 1)");
        try (Connection reclaim = dataSource.getConnection()) {
            reclaim.setAutoCommit(false);
            try (Statement st = reclaim.createStatement()) {
                st.executeUpdate("DELETE FROM friend_capacity WHERE player_id = 8000 AND friend_count = 0");
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<AddResult> a = pool.submit(() -> s.addRequest(8001, 8000, d()));
                Future<AddResult> b = pool.submit(() -> s.addRequest(8002, 8000, d()));
                Thread.sleep(300); // 让两个 ensure 都排到回收的行锁后面
                reclaim.commit();
                assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(AddResult.OK);
                assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(AddResult.OK);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(capacity(8000)).isZero();
        assertInvariants();
    }

    @Test
    void 并发_Unblock压着X锁时Block与另一个Unblock排队_不出1213() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.block(8100, 8101, d())).isEqualTo(BlockResult.OK);
        try (Connection unblocking = dataSource.getConnection()) {
            unblocking.setAutoCommit(false);
            try (Statement st = unblocking.createStatement()) {
                st.executeUpdate("DELETE FROM friend_block WHERE player_id = 8100 AND blocked_player_id = 8101");
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<BlockResult> block = pool.submit(() -> s.block(8100, 8101, d()));
                Future<?> unblock = pool.submit(() -> s.unblock(8100, 8101, d()));
                Thread.sleep(300);
                unblocking.commit();
                assertThat(block.get(30, TimeUnit.SECONDS)).isEqualTo(BlockResult.OK);
                unblock.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
        assertInvariants();
    }

    @Test
    void created_ms_只在建行时写_减计数时刷新() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        befriend(s, 8200, 8201);
        long created = createdMs(8200);
        Thread.sleep(5);
        assertThat(s.addRequest(8200, 8202, d())).isEqualTo(AddResult.OK); // ensure 遇到已有行：ODKU 什么都不改
        assertThat(createdMs(8200)).isEqualTo(created);
        Thread.sleep(5);
        assertThat(s.remove(8200, 8201, d())).isEqualTo(RemoveResult.REMOVED);
        assertThat(createdMs(8200)).isGreaterThan(created);
    }

    @Test
    void 守卫缺行_重补后成功_重试用尽fail_closed且体一次都没执行() throws SQLException {
        JdbcFriendStore once = store(LIMITS);
        AtomicInteger deletes = new AtomicInteger();
        once.afterEnsureForTest(() -> {
            if (deletes.getAndIncrement() == 0) {
                deleteCapacity(8300);
            }
        });
        assertThat(once.addRequest(8300, 8301, d())).isEqualTo(AddResult.OK);
        assertThat(retries.get("missing_row")).hasValue(1);

        JdbcFriendStore always = store(LIMITS);
        always.afterEnsureForTest(() -> deleteCapacity(8310));
        assertThatThrownBy(() -> always.addRequest(8310, 8311, d())).isInstanceOf(FriendStoreException.class)
                .hasMessageContaining("重试用尽");
        assertThat(query("SELECT from_player_id FROM friend_request WHERE from_player_id = 8310")).isEmpty();
    }

    @Test
    void 同意_守卫之内发现拉黑回BLOCKED_申请行不动_反向pending的updated_ms也写() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(8400, 8401, d())).isEqualTo(AddResult.OK);
        exec("INSERT INTO friend_block (player_id, blocked_player_id, since_ms) VALUES (8401, 8400, 1)"); // 绕过 Block 直写
        assertThat(s.accept(8400, 8401, d())).isEqualTo(AcceptResult.BLOCKED);
        assertThat(query("SELECT status FROM friend_request WHERE from_player_id = 8400 AND to_player_id = 8401"))
                .containsExactly(1L);

        assertThat(s.addRequest(8410, 8411, d())).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(8411, 8410, d())).isEqualTo(AddResult.OK);
        exec("UPDATE friend_request SET updated_ms = 0 WHERE from_player_id = 8411 AND to_player_id = 8410");
        assertThat(s.accept(8410, 8411, d())).isEqualTo(AcceptResult.OK);
        assertThat(query("SELECT status FROM friend_request WHERE from_player_id = 8411 AND to_player_id = 8410"))
                .containsExactly(2L);
        assertThat(updatedMsOf(8411, 8410)).isPositive();
    }

    @Test
    void 预算用完不再写() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        Deadline expired = Deadline.after(0);
        assertThatThrownBy(() -> s.addRequest(8500, 8501, expired)).isInstanceOf(FriendStoreException.class);
        assertThatThrownBy(() -> s.reject(8500, 8501, expired)).isInstanceOf(FriendStoreException.class);
        assertThat(countRows("friend_capacity")).isZero();
        assertThat(countRows("friend_request")).isZero();
    }

    @Test
    void 并发_回收与四条写路径同时跑_不出错不出1213_不变量成立() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        SweepStore sweeper = new SweepStore(dataSource, 10);
        java.util.concurrent.atomic.AtomicBoolean writersDone = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong swept = new java.util.concurrent.atomic.AtomicLong();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            long a = 9000 + w * 2L;
            long b = a + 1;
            tasks.add(() -> {
                for (int i = 0; i < 12; i++) {
                    age(a, b); // 让这一对的容量行可被回收，逼出「守卫缺行 → 重补」
                    assertThat(s.addRequest(a, b, d())).isEqualTo(AddResult.OK);
                    age(a, b);
                    assertThat(s.accept(a, b, d())).isEqualTo(AcceptResult.OK);
                    age(a, b);
                    assertThat(s.remove(a, b, d())).isEqualTo(RemoveResult.REMOVED);
                    age(a, b);
                    assertThat(s.block(a, b, d())).isEqualTo(BlockResult.OK);
                    s.unblock(a, b, d());
                }
                return null;
            });
        }
        for (int r = 0; r < 4; r++) {
            tasks.add(() -> {
                while (!writersDone.get()) {
                    SweepStore.Result result = sweeper.sweepIdleCapacityRows("delete", 1, 1000, System.currentTimeMillis(),
                            () -> true);
                    assertThat(result.error()).isNull();
                    swept.addAndGet(result.deleted());
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < 4; i++) {
                futures.get(i).get(120, TimeUnit.SECONDS);
            }
            writersDone.set(true);
            for (int i = 4; i < futures.size(); i++) {
                futures.get(i).get(60, TimeUnit.SECONDS);
            }
        } finally {
            writersDone.set(true);
            pool.shutdownNow();
        }
        assertThat(swept.get()).isPositive();
        assertInvariants();
    }

    /** 把这一对玩家的容量行改成「很早以前建的」（只有零好友的行会被回收）。 */
    private static void age(long a, long b) throws SQLException {
        exec("UPDATE friend_capacity SET created_ms = 2 WHERE player_id IN (" + a + "," + b + ")");
    }

    private static void deleteCapacity(long playerId) {
        try {
            exec("DELETE FROM friend_capacity WHERE player_id = " + playerId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long createdMs(long playerId) throws SQLException {
        return query("SELECT created_ms FROM friend_capacity WHERE player_id = " + playerId).get(0);
    }

    // ================================================================ 锁定语句是主键点查

    @Test
    void 守卫之后的锁定语句都是完整主键等值点查() throws SQLException {
        exec("INSERT INTO friend_capacity (player_id, friend_count, created_ms) VALUES (1, 1, 1), (2, 1, 1)");
        exec("INSERT INTO friend (player_id, friend_player_id, since_ms) VALUES (1, 2, 1), (2, 1, 1)");
        exec("INSERT INTO friend_block (player_id, blocked_player_id, since_ms) VALUES (1, 3, 1)");
        exec("INSERT INTO friend_request (from_player_id, to_player_id, request_time_ms, status, updated_ms) VALUES (1, 4, 1, 1, 1)");
        assertPointLookup(JdbcFriendStore.LOCK_CAPACITY, 8, 1L);
        assertPointLookup(JdbcFriendStore.LOCK_BLOCK, 16, 1L, 3L);
        assertPointLookup(JdbcFriendStore.LOCK_EDGE, 16, 1L, 2L);
        assertPointLookup(JdbcFriendStore.LOCK_REQUEST, 16, 1L, 4L);
        assertPointLookup(JdbcFriendStore.UPDATE_REQUEST_STATUS, 16, 3, 1L, 1L, 4L, 1);
        assertPointLookup(JdbcFriendStore.DELETE_EDGE, 16, 1L, 2L);
        assertPointLookup(JdbcFriendStore.DECREMENT_COUNT, 8, 1L, 1L);
        // 清理的逐行删也是按完整主键（提交点复核条件只是附加过滤）
        assertPointLookup(SweepStore.DELETE_TERMINAL, 16, 1L, 4L, 2, 3, Long.MAX_VALUE);
        assertPointLookup(SweepStore.DELETE_IDLE_CAPACITY, 8, 1L, Long.MAX_VALUE);
    }

    private static void assertPointLookup(String sql, int keyLen, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                assertThat(rs.getString("key")).as(sql).isEqualTo("PRIMARY");
                assertThat(rs.getString("key_len")).as(sql).isEqualTo(Integer.toString(keyLen));
                String type = rs.getString("type");
                if (sql.startsWith("SELECT")) {
                    assertThat(type).as(sql).isEqualTo("const");
                } else {
                    assertThat(type).as(sql).isIn("range", "const");
                }
            }
        }
    }

    // ================================================================ 工具

    private static void befriend(JdbcFriendStore s, long a, long b) {
        assertThat(s.addRequest(a, b, d())).isEqualTo(AddResult.OK);
        assertThat(s.accept(a, b, d())).isEqualTo(AcceptResult.OK);
    }

    /** 同时起跑（屏障），收集结果；任何异常（含 1213 → FriendStoreException）都让测试失败。 */
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** friend-spec.md §1.6 的四条不变量，外加好友边双向对称。 */
    private static void assertInvariants() throws SQLException {
        assertThat(query("SELECT c.player_id FROM friend_capacity c LEFT JOIN (SELECT player_id, COUNT(*) n FROM friend"
                + " GROUP BY player_id) f ON f.player_id = c.player_id WHERE c.friend_count <> COALESCE(f.n, 0)"))
                .as("容量计数 = 边数").isEmpty();
        assertThat(query("SELECT DISTINCT player_id FROM friend WHERE player_id NOT IN (SELECT player_id FROM friend_capacity)"))
                .as("有边的玩家都有容量行").isEmpty();
        assertThat(query("SELECT a.player_id FROM friend a LEFT JOIN friend b ON b.player_id = a.friend_player_id"
                + " AND b.friend_player_id = a.player_id WHERE b.player_id IS NULL")).as("好友边双向").isEmpty();
        assertThat(query("SELECT f.player_id FROM friend f JOIN friend_block b ON (b.player_id = f.player_id"
                + " AND b.blocked_player_id = f.friend_player_id) OR (b.player_id = f.friend_player_id"
                + " AND b.blocked_player_id = f.player_id)")).as("不存在既是好友又被拉黑").isEmpty();
        assertThat(query("SELECT r.from_player_id FROM friend_request r JOIN friend_block b ON (b.player_id = r.from_player_id"
                + " AND b.blocked_player_id = r.to_player_id) OR (b.player_id = r.to_player_id"
                + " AND b.blocked_player_id = r.from_player_id) WHERE r.status = 1")).as("拉黑的一对之间不留 pending").isEmpty();
        assertThat(query("SELECT r.from_player_id FROM friend_request r JOIN friend f ON f.player_id = r.from_player_id"
                + " AND f.friend_player_id = r.to_player_id WHERE r.status = 1")).as("好友之间不留 pending").isEmpty();
    }

    private static List<Long> query(String sql) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static long capacity(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT friend_count FROM friend_capacity WHERE player_id = ?")) {
            ps.setObject(1, JdbcFriendStore.uid(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static long updatedMsOf(long from, long to) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT updated_ms FROM friend_request WHERE from_player_id = ? AND to_player_id = ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private static long countRows(String table) throws SQLException {
        return query("SELECT COUNT(*) FROM " + table).get(0);
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
