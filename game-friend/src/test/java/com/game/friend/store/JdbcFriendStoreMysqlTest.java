package com.game.friend.store;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link JdbcFriendStore} 连真 MySQL 的测试（缺省跳过）：{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量
 * GAME_MYSQL_PASSWORD。建一次性库 {@code game_friend_it_<随机>}（四张表经 game-pbmysql 建），结束时删掉。
 *
 * <p>钉住 friend-spec.md §1.6：业务规则与检查优先级、上限的角色映射、缺行时权威计数、无符号玩家号，以及并发场景
 * （同一 target 的 16 个申请人、各不相同的 target、同一玩家并发拉黑 16 人、同一对玩家 Block × Accept / Add × Accept 交错、
 * 并发同意的硬上限）全程不出 1213、四条不变量成立；守卫之后的锁定语句都是完整主键点查。
 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class JdbcFriendStoreMysqlTest {

    private static final String BASE_URL = System.getProperty("game.it.mysql");
    private static final String USER = System.getProperty("game.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"
            + "&connectTimeout=3000&socketTimeout=10000";
    private static AuthoritySqlFixture fixture;
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS = new java.util.concurrent.atomic.AtomicLong(10000);
    private static final FriendLimits LIMITS = new FriendLimits(200, 50, 200, 200);

    private static DruidDataSource dataSource;
    private final AtomicInteger underflows = new AtomicInteger();
    private final Map<String, AtomicInteger> retries = new ConcurrentHashMap<>();

    @BeforeAll
    static void createDatabase() throws Exception {
        PbMysql db = new PbMysql();
        db.register(FriendEdgeRow.getDefaultInstance());
        db.register(FriendRequestRow.getDefaultInstance());
        db.register(FriendCapacityRow.getDefaultInstance());
        db.register(FriendBlockRow.getDefaultInstance());
        fixture = AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(
                BASE_URL.replaceAll("/$", "") + "/", USER, PASSWORD, "game_friend", true, List.of(1)), (c, principal) -> {
            db.initializeFresh(c, principal);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE player (player_id BIGINT UNSIGNED PRIMARY KEY, account VARBINARY(191) NOT NULL,"
                        + " owner_revision BIGINT UNSIGNED NOT NULL, trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0, zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            }
        });
        dataSource = new DruidDataSource();
        dataSource.setUrl(fixture.jdbcUrl(PARAMS));
        dataSource.setUsername(fixture.normalUser());
        dataSource.setPassword(fixture.normalPassword());
        dataSource.setMaxActive(64);
        dataSource.setMaxWait(10_000);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        AuthorityDataSourcePolicy.protectDruid(dataSource, fixture.writerBoot(), false);
        try (Connection c = dataSource.getConnection()) { db.syncAll(c); }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (dataSource != null) dataSource.close();
        if (fixture != null) fixture.close();
    }

    @BeforeEach
    void truncate() throws SQLException {
        for (String table : List.of("friend", "friend_request", "friend_capacity", "friend_block", "player")) {
            exec("TRUNCATE TABLE " + table);
        }
    }

    /** 每次调用一个宽裕的预算（测试关心的是 SQL 行为，不是超时）。 */
    private static Deadline d() {
        return Deadline.after(30_000);
    }

    private JdbcFriendStore store(FriendLimits limits) {
        return store(limits, REQUEST_IDS::incrementAndGet);
    }

    private JdbcFriendStore store(FriendLimits limits, java.util.function.LongSupplier ids) {
        return new JdbcFriendStore(dataSource, limits, System::currentTimeMillis, 10, new JdbcFriendStore.Events() {
            @Override
            public void guardRetry(String kind) {
                retries.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            }

            @Override
            public void countUnderflow() {
                underflows.incrementAndGet();
            }
        }, ids);
    }

    private static long requestId(JdbcFriendStore store, long from, long to) {
        try {
            List<Long> rows = query("SELECT request_id FROM friend_request WHERE from_player_id = "
                    + Long.toUnsignedString(from) + " AND to_player_id = " + Long.toUnsignedString(to));
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AcceptResult accept(JdbcFriendStore store, long from, long to, Deadline deadline) {
        return store.accept(requestId(store, from, to), to, from, deadline).result();
    }

    private static RejectResult reject(JdbcFriendStore store, long from, long to, Deadline deadline) {
        return store.reject(requestId(store, from, to), to, from, deadline).result();
    }

    // ================================================================ 业务规则

    @Test
    void 旧申请表普通启动拒绝自动升级_新Schema历史形状仍按需补号() throws SQLException {
        exec("ALTER TABLE friend_request DROP INDEX idx_friend_request_2, DROP COLUMN request_id");
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms)"
                + " VALUES (91,92,123,1,456)");
        PbMysql upgraded = new PbMysql();
        upgraded.register(FriendRequestRow.getDefaultInstance());
        try {
            try (Connection c = dataSource.getConnection()) {
                assertThatThrownBy(() -> upgraded.syncAll(c)).isInstanceOf(SQLException.class);
            }
            assertThat(query("SELECT request_time_ms FROM friend_request WHERE from_player_id=91 AND to_player_id=92")).containsExactly(123L);
            assertThat(updatedMsOf(91,92)).isEqualTo(456);
        } finally {
            // 当前仅支持上线前fresh Schema；显式重建本测试单表，普通启动绝不修复已有数据。
            exec("DROP TABLE friend_request");
            exec(upgraded.tables().iterator().next().createTableSql());
        }
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (91,92,123,1,456)");
        assertThat(query("SELECT request_id FROM friend_request WHERE from_player_id=91 AND to_player_id=92"))
                .containsExactly(0L);
        assertThat(query("SELECT non_unique FROM information_schema.statistics WHERE table_schema='" + fixture.database()
                + "' AND table_name='friend_request' AND index_name='idx_friend_request_2'"))
                .containsExactly(1L);
        assertThat(store(LIMITS).pendingRequests(92, 10, d())).singleElement().satisfies(row -> {
            assertThat(row.requestId()).isNotZero();
            assertThat(row.requestTimeMs()).isEqualTo(123);
        });
        assertThat(updatedMsOf(91, 92)).isEqualTo(456);
    }

    @Test
    void 申请_同意_双向收敛_计数与列表() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(1, 2, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(1, 2, d()).result()).isEqualTo(AddResult.OK);
        // 对方也向我申请：不自动成为好友，双方各挂一条
        assertThat(s.addRequest(2, 1, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.pendingRequests(2, 100, d())).extracting(FriendStore.PendingRequest::fromPlayerId).containsExactly(1L);
        assertThat(s.pendingRequests(1, 100, d())).extracting(FriendStore.PendingRequest::fromPlayerId).containsExactly(2L);

        assertThat(accept(s, 1, 2, d())).isEqualTo(AcceptResult.OK);
        // 反向 pending 一并置已同意：1 的收件箱里不留孤儿
        assertThat(s.pendingRequests(1, 100, d())).isEmpty();
        assertThat(s.pendingRequests(2, 100, d())).isEmpty();
        assertThat(s.friends(1, 100, d())).extracting(FriendEdge::friendPlayerId).containsExactly(2L);
        assertThat(s.friends(2, 100, d())).extracting(FriendEdge::friendPlayerId).containsExactly(1L);
        assertThat(s.friends(1, 100, d()).get(0).sinceMs()).isEqualTo(s.friends(2, 100, d()).get(0).sinceMs());
        assertThat(capacity(1)).isEqualTo(1);
        assertThat(capacity(2)).isEqualTo(1);
        // 第二个同意：事务外前置就是 NO_PENDING
        assertThat(accept(s, 2, 1, d())).isEqualTo(AcceptResult.OK);
        assertThat(s.addRequest(1, 2, d()).result()).isEqualTo(AddResult.ALREADY_FRIENDS);
        assertInvariants();
    }

    @Test
    void 没有申请的同意不补容量行_拒绝后可以重新申请且刷新申请时间() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        assertThat(accept(s, 7, 8, d())).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(countRows("friend_capacity")).isZero();

        assertThat(s.addRequest(7, 8, d()).result()).isEqualTo(AddResult.OK);
        long first = s.pendingRequests(8, 10, d()).get(0).requestTimeMs();
        assertThat(reject(s, 7, 8, d())).isEqualTo(RejectResult.OK);
        assertThat(reject(s, 7, 8, d())).isEqualTo(RejectResult.OK);
        assertThat(s.pendingRequests(8, 10, d())).isEmpty();
        Thread.sleep(5);
        assertThat(s.addRequest(7, 8, d()).result()).isEqualTo(AddResult.OK);
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
        assertThat(s.addRequest(10, 12, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(12, 10, d()).result()).isEqualTo(AddResult.OK);

        assertThat(s.block(10, 11, d())).isEqualTo(BlockResult.OK);
        assertThat(s.friends(10, 10, d())).isEmpty();
        assertThat(capacity(10)).isZero();
        assertThat(capacity(11)).isZero();
        // 拉黑两个方向都拒（同码、不泄露是谁拉黑了谁）
        assertThat(s.addRequest(10, 11, d()).result()).isEqualTo(AddResult.BLOCKED);
        assertThat(s.addRequest(11, 10, d()).result()).isEqualTo(AddResult.BLOCKED);

        assertThat(s.block(12, 10, d())).isEqualTo(BlockResult.OK);
        // 拉黑把两个方向的 pending 都置成终态：之后去同意拿到的是 NO_PENDING（不是 BLOCKED）
        assertThat(accept(s, 12, 10, d())).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(accept(s, 10, 12, d())).isEqualTo(AcceptResult.NO_PENDING);
        // 重复拉黑幂等；单向：A 拉黑了 B 不影响 B 拉黑 A
        assertThat(s.block(12, 10, d())).isEqualTo(BlockResult.OK);
        assertThat(s.block(10, 12, d())).isEqualTo(BlockResult.OK);
        assertThat(s.blocks(10, 10, d())).extracting(FriendStore.BlockEdge::blockedPlayerId).containsExactly(11L, 12L);

        s.unblock(10, 11, d());
        s.unblock(10, 11, d());
        assertThat(s.blocks(10, 10, d())).extracting(FriendStore.BlockEdge::blockedPlayerId).containsExactly(12L);
        assertThat(s.friends(10, 10, d())).isEmpty(); // 不恢复好友
        assertThat(s.addRequest(10, 11, d()).result()).isEqualTo(AddResult.OK);
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
        assertThat(s.addRequest(30, 31, d()).result()).isEqualTo(AddResult.OK);
        s.block(31, 30, d());
        assertThat(s.addRequest(30, 31, d()).result()).isEqualTo(AddResult.BLOCKED);
    }

    @Test
    void 出站_入站_好友数上限_满的角色映射() {
        JdbcFriendStore pending = store(new FriendLimits(200, 2, 200, 200));
        assertThat(pending.addRequest(40, 41, d()).result()).isEqualTo(AddResult.OK);
        assertThat(pending.addRequest(40, 42, d()).result()).isEqualTo(AddResult.OK);
        assertThat(pending.addRequest(40, 43, d()).result()).isEqualTo(AddResult.TOO_MANY_PENDING);

        JdbcFriendStore inbox = store(new FriendLimits(200, 50, 2, 200));
        assertThat(inbox.addRequest(51, 50, d()).result()).isEqualTo(AddResult.OK);
        assertThat(inbox.addRequest(52, 50, d()).result()).isEqualTo(AddResult.OK);
        assertThat(inbox.addRequest(53, 50, d()).result()).isEqualTo(AddResult.TARGET_INBOX_FULL);

        JdbcFriendStore full = store(new FriendLimits(1, 50, 200, 200));
        befriend(full, 60, 61);
        assertThat(full.addRequest(60, 62, d()).result()).isEqualTo(AddResult.SENDER_FULL);
        assertThat(full.addRequest(62, 60, d()).result()).isEqualTo(AddResult.RECEIVER_FULL);
        // 同意：原申请人满 → SENDER_FULL，我（接受者）满 → ACCEPTOR_FULL
        JdbcFriendStore loose = store(LIMITS);
        assertThat(loose.addRequest(60, 63, d()).result()).isEqualTo(AddResult.OK);
        assertThat(loose.addRequest(64, 61, d()).result()).isEqualTo(AddResult.OK);
        assertThat(accept(full, 60, 63, d())).isEqualTo(AcceptResult.SENDER_FULL);
        assertThat(accept(full, 64, 61, d())).isEqualTo(AcceptResult.ACCEPTOR_FULL);
    }

    @Test
    void 容量行被回收后_按好友表的权威边数重建_绝不猜零() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        befriend(s, 70, 71);
        exec("DELETE FROM friend_capacity WHERE player_id = 70");
        assertThat(s.addRequest(70, 72, d()).result()).isEqualTo(AddResult.OK);
        assertThat(capacity(70)).isEqualTo(1);
        assertInvariants();
    }

    @Test
    void 大于2的63次方的玩家号按无符号存取() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        long big = -5L;              // 18446744073709551611
        long bigger = Long.MIN_VALUE; // 9223372036854775808
        assertThat(s.addRequest(big, bigger, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.pendingRequests(bigger, 10, d())).singleElement()
                .satisfies(r -> assertThat(r.fromPlayerId()).isEqualTo(big));
        assertThat(accept(s, big, bigger, d())).isEqualTo(AcceptResult.OK);
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
        assertThatThrownBy(() -> s.addRequest(0, 1, d()).result()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.addRequest(5, 5, d())).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.remove(5, 5, d())).isEqualTo(RemoveResult.NOT_FRIENDS);
    }

    @Test
    void 申请代次_重放不改ID时间_终态重发换号_旧操作无副作用() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        FriendStore.AddOutcome first = s.addRequest(101, 102, d());
        long originalTime = s.pendingRequests(102, 10, d()).getFirst().requestTimeMs();
        assertThat(first.requestId()).isNotZero();
        assertThat(first.created()).isTrue();
        FriendStore.AddOutcome replay = s.addRequest(101, 102, d());
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.created()).isFalse();
        assertThat(s.pendingRequests(102, 10, d()).getFirst().requestTimeMs()).isEqualTo(originalTime);
        assertThat(s.reject(first.requestId(), 102, 0, d()).changed()).isTrue();
        assertThat(s.reject(first.requestId(), 102, 0, d()).changed()).isFalse();
        assertThat(s.accept(first.requestId(), 102, 0, d()).result()).isEqualTo(AcceptResult.NO_PENDING);
        FriendStore.AddOutcome next = s.addRequest(101, 102, d());
        assertThat(next.requestId()).isNotZero().isNotEqualTo(first.requestId());
        assertThat(s.accept(first.requestId(), 102, 101, d()).result()).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.reject(first.requestId(), 102, 101, d()).result()).isEqualTo(RejectResult.NO_PENDING);
        assertThat(s.pendingRequests(102, 10, d())).singleElement().satisfies(q -> assertThat(q.requestId()).isEqualTo(next.requestId()));
        assertThat(s.friends(101, 10, d())).isEmpty();
        assertThat(capacity(101)).isZero();
        assertInvariants();
    }

    @Test
    void 同意只认代次与会话接收方_忽略兼容from_同代重放不重复计数() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        long id = s.addRequest(111, 112, d()).requestId();
        assertThat(s.accept(0, 112, 111, d()).result()).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.accept(id, 113, 113, d()).result()).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.reject(id, 113, 999999, d()).result()).isEqualTo(RejectResult.NO_PENDING);
        assertThat(s.accept(id, 112, 112, d()).changed()).isTrue();
        var repeated = s.accept(id, 112, 999999, d());
        assertThat(repeated.result()).isEqualTo(AcceptResult.OK);
        assertThat(repeated.changed()).isFalse();
        assertThat(s.reject(id, 112, 0, d()).result()).isEqualTo(RejectResult.NO_PENDING);
        assertThat(capacity(111)).isEqualTo(1);
        assertThat(capacity(112)).isEqualTo(1);
        s.remove(111, 112, d());
        long next = s.addRequest(111, 112, d()).requestId();
        assertThat(next).isNotEqualTo(id);
        assertThat(s.accept(id, 112, 0, d()).result()).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(s.reject(next, 112, 112, d()).changed()).isTrue();
        assertThat(s.reject(next, 112, 999999, d()).result()).isEqualTo(RejectResult.OK);
        assertInvariants();
    }

    @Test
    void 旧零ID申请安全补号保留时间_非零ID不轮换_号源失败不影响既有重放() throws SQLException {
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (121,122,123,1,456)");
        JdbcFriendStore s = store(LIMITS);
        var row = s.pendingRequests(122, 10, d()).getFirst();
        assertThat(row.requestId()).isNotZero();
        assertThat(row.requestTimeMs()).isEqualTo(123);
        assertThat(updatedMsOf(121, 122)).isEqualTo(456);
        JdbcFriendStore unavailable = store(LIMITS, () -> { throw new IllegalStateException("号源失败"); });
        assertThat(unavailable.addRequest(121, 122, d()).requestId()).isEqualTo(row.requestId());
        assertThat(unavailable.pendingRequests(122, 10, d()).getFirst().requestId()).isEqualTo(row.requestId());
        assertThatThrownBy(() -> unavailable.addRequest(123, 124, d())).isInstanceOf(IllegalStateException.class);
        assertThat(unavailable.pendingRequests(124, 10, d())).isEmpty();
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (125,126,789,1,888)");
        var migrated = s.addRequest(125, 126, d());
        assertThat(migrated.created()).isFalse();
        assertThat(migrated.requestId()).isNotZero();
        assertThat(s.pendingRequests(126, 10, d()).getFirst().requestTimeMs()).isEqualTo(789);
        assertInvariants();
    }

    @Test
    void 旧记录补号期间其他申请被同意_返回整页新快照而非拼接旧pending() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        JdbcFriendStore concurrent = store(LIMITS);
        long acceptedDuringMigration = concurrent.addRequest(142, 140, d()).requestId();
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (141,140,1,1,1)");
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        s.afterEnsureForTest(() -> {
            if (changed.compareAndSet(false, true)) {
                assertThat(concurrent.accept(acceptedDuringMigration, 140, 142, d()).changed()).isTrue();
            }
        });
        assertThat(s.pendingRequests(140, 10, d())).singleElement().satisfies(row -> {
            assertThat(row.fromPlayerId()).isEqualTo(141);
            assertThat(row.requestId()).isNotZero();
        });
        assertThat(changed).isTrue();
        assertInvariants();
    }

    @Test
    void 重复申请ID冲突必须拒绝_不能任选一条处理() throws SQLException {
        exec("INSERT INTO friend_request (from_player_id,to_player_id,request_time_ms,status,updated_ms,request_id) VALUES (131,132,1,1,1,777),(133,132,1,1,1,777)");
        JdbcFriendStore s = store(LIMITS);
        assertThatThrownBy(() -> s.accept(777, 132, 0, d())).isInstanceOf(FriendStoreException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> s.reject(777, 132, 0, d())).isInstanceOf(FriendStoreException.class).hasMessageContaining("重复");
        assertThat(s.pendingRequests(132, 10, d())).hasSize(2);
        assertThat(s.friends(132, 10, d())).isEmpty();
    }

    @Test
    void 同意在第二次计数更新失败_申请双向边计数全部回滚_可重新处理() throws SQLException {
        JdbcFriendStore normal = store(LIMITS);
        long id = normal.addRequest(151, 152, d()).requestId();
        normal.addRequest(152, 151, d());
        AtomicInteger increments = new AtomicInteger();
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenAnswer(ignored -> {
            Connection actual = dataSource.getConnection();
            Connection connection = mock(Connection.class, delegatesTo(actual));
            doAnswer(call -> {
                String sql = call.getArgument(0);
                PreparedStatement actualStatement = actual.prepareStatement(sql);
                if (!JdbcFriendStore.INCREMENT_COUNT.equals(sql)) {
                    return actualStatement;
                }
                PreparedStatement statement = mock(PreparedStatement.class, delegatesTo(actualStatement));
                doAnswer(update -> {
                    if (increments.incrementAndGet() == 2) {
                        throw new SQLException("注入第二个计数更新失败");
                    }
                    return actualStatement.executeUpdate();
                }).when(statement).executeUpdate();
                return statement;
            }).when(connection).prepareStatement(anyString());
            return connection;
        });
        JdbcFriendStore broken = new JdbcFriendStore(failing, LIMITS, System::currentTimeMillis, 10,
                JdbcFriendStore.Events.NONE, REQUEST_IDS::incrementAndGet);
        assertThatThrownBy(() -> broken.accept(id, 152, 0, d())).isInstanceOf(FriendStoreException.class);
        assertThat(increments).hasValue(2);
        assertThat(normal.pendingRequests(151, 10, d())).hasSize(1);
        assertThat(normal.pendingRequests(152, 10, d())).hasSize(1);
        assertThat(normal.friends(151, 10, d())).isEmpty();
        assertThat(normal.friends(152, 10, d())).isEmpty();
        assertThat(capacity(151)).isZero();
        assertThat(capacity(152)).isZero();
        assertThat(normal.accept(id, 152, 0, d()).changed()).isTrue();
        assertInvariants();
    }

    // ================================================================ 并发（全程不得出现 1213）

    @Test
    void 并发_16个申请人发给同一个target_与各不相同的target() throws Exception {
        JdbcFriendStore s = store(LIMITS);
        List<Callable<Object>> same = new ArrayList<>();
        List<Callable<Object>> distinct = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            long from = 1000 + i;
            same.add(() -> s.addRequest(from, 999, d()).result());
            distinct.add(() -> s.addRequest(from, 2000 + from, d()).result());
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
                s.addRequest(3100 + i, 3000, d()).result();
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
            s.addRequest(a, b, d()).result();
            List<Callable<Object>> tasks = List.of(() -> s.block(b, a, d()), () -> accept(s, a, b, d()));
            runConcurrently(tasks);
            long c = 5000 + round * 2L;
            long d = c + 1;
            s.addRequest(c, d, d()).result();
            runConcurrently(List.of(() -> s.addRequest(d, c, d()).result(), () -> accept(s, c, d, d())));
        }
        assertInvariants();
    }

    @Test
    void 并发同意的好友数硬上限() throws Exception {
        JdbcFriendStore s = store(new FriendLimits(3, 50, 200, 200));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long from = 6100 + i;
            assertThat(s.addRequest(from, 6000, d()).result()).isEqualTo(AddResult.OK);
            tasks.add(() -> accept(s, from, 6000, d()));
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
        assertThat(s.addRequest(7001, 7002, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(7003, 7004, d()).result()).isEqualTo(AddResult.OK);
        for (int round = 0; round < 5; round++) {
            runConcurrently(List.of(() -> s.addRequest(7002, 7003, d()).result(), () -> s.addRequest(7004, 7001, d()).result(),
                    () -> s.addRequest(7003, 7002, d()).result(), () -> s.addRequest(7001, 7004, d()).result()));
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
                Future<AddResult> a = pool.submit(() -> s.addRequest(8001, 8000, d()).result());
                Future<AddResult> b = pool.submit(() -> s.addRequest(8002, 8000, d()).result());
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
        assertThat(s.addRequest(8200, 8202, d()).result()).isEqualTo(AddResult.OK); // ensure 遇到已有行：ODKU 什么都不改
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
        assertThat(once.addRequest(8300, 8301, d()).result()).isEqualTo(AddResult.OK);
        assertThat(retries.get("missing_row")).hasValue(1);

        JdbcFriendStore always = store(LIMITS);
        always.afterEnsureForTest(() -> deleteCapacity(8310));
        assertThatThrownBy(() -> always.addRequest(8310, 8311, d()).result()).isInstanceOf(FriendStoreException.class)
                .hasMessageContaining("重试用尽");
        assertThat(query("SELECT from_player_id FROM friend_request WHERE from_player_id = 8310")).isEmpty();
    }

    @Test
    void 同意_守卫之内发现拉黑回BLOCKED_申请行不动_反向pending的updated_ms也写() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        assertThat(s.addRequest(8400, 8401, d()).result()).isEqualTo(AddResult.OK);
        exec("INSERT INTO friend_block (player_id, blocked_player_id, since_ms) VALUES (8401, 8400, 1)"); // 绕过 Block 直写
        assertThat(accept(s, 8400, 8401, d())).isEqualTo(AcceptResult.BLOCKED);
        assertThat(query("SELECT status FROM friend_request WHERE from_player_id = 8400 AND to_player_id = 8401"))
                .containsExactly(1L);

        assertThat(s.addRequest(8410, 8411, d()).result()).isEqualTo(AddResult.OK);
        assertThat(s.addRequest(8411, 8410, d()).result()).isEqualTo(AddResult.OK);
        exec("UPDATE friend_request SET updated_ms = 0 WHERE from_player_id = 8411 AND to_player_id = 8410");
        assertThat(accept(s, 8410, 8411, d())).isEqualTo(AcceptResult.OK);
        assertThat(query("SELECT status FROM friend_request WHERE from_player_id = 8411 AND to_player_id = 8410"))
                .containsExactly(2L);
        assertThat(updatedMsOf(8411, 8410)).isPositive();
    }

    @Test
    void 预算用完不再写() throws SQLException {
        JdbcFriendStore s = store(LIMITS);
        Deadline expired = Deadline.after(0);
        assertThatThrownBy(() -> s.addRequest(8500, 8501, expired).result()).isInstanceOf(FriendStoreException.class);
        assertThatThrownBy(() -> s.reject(1, 8501, 8500, expired)).isInstanceOf(FriendStoreException.class);
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
                    assertThat(s.addRequest(a, b, d()).result()).isEqualTo(AddResult.OK);
                    age(a, b);
                    assertThat(accept(s, a, b, d())).isEqualTo(AcceptResult.OK);
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
        assertPointLookup(JdbcFriendStore.UPDATE_REQUEST_GENERATION_STATUS, 16, 3, 1L, 1L, 4L, 1L, 1);
        assertPointLookup(JdbcFriendStore.BACKFILL_REQUEST_ID, 16, 1L, 1L, 4L, 1);
        assertPointLookup(JdbcFriendStore.DELETE_EDGE, 16, 1L, 2L);
        assertPointLookup(JdbcFriendStore.DECREMENT_COUNT, 8, 1L, 1L);
        // 清理的逐行删也是按完整主键（提交点复核条件只是附加过滤）
        assertPointLookup(SweepStore.DELETE_TERMINAL, 16, 1L, 4L, 2, 3, Long.MAX_VALUE);
        assertPointLookup(SweepStore.DELETE_IDLE_CAPACITY, 8, 1L, Long.MAX_VALUE);
    }

    private static void assertPointLookup(String sql, int keyLen, Object... args) throws SQLException {
        // TiDB 的传统 EXPLAIN 是算子树，不提供 MySQL 的 key/key_len/type 列。
        try (Connection c = dataSource.getConnection(); Statement versionStatement = c.createStatement();
             ResultSet versionRows = versionStatement.executeQuery("SELECT VERSION()")) {
            assertThat(versionRows.next()).isTrue();
            String version = versionRows.getString(1);
            assertThat(version).isNotBlank();
            assertThat(versionRows.next()).isFalse();
            if (version.toLowerCase(java.util.Locale.ROOT).contains("tidb")) {
                assertTiDbPointLookup(c, sql, keyLen, args);
                return;
            }
        }
        // 服务端默认 EXPLAIN 格式可变；断言 key / key_len 前显式要求传统表格格式。
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("EXPLAIN FORMAT=TRADITIONAL " + sql)) {
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


    /** 原生产语句的 TiDB 计划：恰一完整 PRIMARY 点入口，拒绝扫描/前缀/多点与未知算子。 */
    private static void assertTiDbPointLookup(Connection c, String sql, int keyLen, Object... args) throws SQLException {
        String normalized = sql.replaceAll("\\s+", " ").strip();
        var tableMatch = java.util.regex.Pattern.compile("(?i)^(?:SELECT .+? FROM|UPDATE|DELETE FROM) ([a-z_]+)\\b.*$").matcher(normalized);
        assertThat(tableMatch.matches()).as("未登记的单表主键语句").isTrue();
        String table = tableMatch.group(1);
        assertThat(table).isIn("friend_capacity", "friend_block", "friend", "friend_request");
        List<String> primary = new ArrayList<>();
        int actualKeyLen = 0;
        Map<String, Integer> widths = Map.of("tinyint", 1, "smallint", 2, "mediumint", 3, "int", 4, "bigint", 8);
        try (PreparedStatement columns = c.prepareStatement("SELECT s.COLUMN_NAME,c.DATA_TYPE,c.IS_NULLABLE"
                + " FROM INFORMATION_SCHEMA.STATISTICS s JOIN INFORMATION_SCHEMA.COLUMNS c"
                + " ON c.TABLE_SCHEMA=s.TABLE_SCHEMA AND c.TABLE_NAME=s.TABLE_NAME AND c.COLUMN_NAME=s.COLUMN_NAME"
                + " WHERE s.TABLE_SCHEMA=DATABASE() AND s.TABLE_NAME=? AND s.INDEX_NAME='PRIMARY' ORDER BY s.SEQ_IN_INDEX")) {
            columns.setString(1, table);
            try (ResultSet rs = columns.executeQuery()) {
                while (rs.next()) {
                    primary.add(rs.getString(1));
                    Integer width = widths.get(rs.getString(2).toLowerCase(java.util.Locale.ROOT));
                    assertThat(width).as("PRIMARY 列必须是已知整数类型").isNotNull();
                    assertThat(rs.getString(3)).isEqualTo("NO");
                    actualKeyLen += width;
                }
            }
        }
        assertThat(primary).isNotEmpty();
        assertThat(actualKeyLen).as("原 keyLen 仍须覆盖全部 PRIMARY 列").isEqualTo(keyLen);
        String pkType;
        try (PreparedStatement metadata = c.prepareStatement("SELECT TIDB_PK_TYPE FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            metadata.setString(1, table);
            try (ResultSet rs = metadata.executeQuery()) {
                assertThat(rs.next()).isTrue();
                pkType = rs.getString(1);
                assertThat(pkType).isIn("CLUSTERED", "NONCLUSTERED");
                assertThat(rs.next()).isFalse();
            }
        }
        List<Map<String, String>> nodes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN FORMAT=TRADITIONAL " + sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, String> row = new java.util.HashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getString(i));
                    nodes.add(row);
                }
            }
        }
        assertThat(nodes).as("TiDB 必须返回原语句的实际完整计划").isNotEmpty();
        String primaryIndex = "index:PRIMARY(" + String.join(",", primary) + ")";
        String clusteredIndex = "clustered " + primaryIndex;
        String tableOnly = "table:" + table;
        int keyAccesses = 0;
        int rowIdLookups = 0;
        boolean primaryIndexRange = false;
        boolean indexLookup = false;
        for (Map<String, String> node : nodes) {
            String id = node.get("id");
            assertThat(id).isNotNull();
            // 树形缩进只用于显示；算子名称严格从 TiDB 的 id 后缀前读取。
            String operator = id.replaceFirst("^[^A-Za-z]*", "").replaceFirst("_[0-9]+(?:\\(Build\\)|\\(Probe\\))?$", "");
            String access = node.get("access object");
            String info = node.get("operator info");
            assertThat(access).as("%s 缺 access object", sql).isNotNull();
            assertThat(info).as("%s 缺 operator info", sql).isNotNull();
            access = access.replace(", ", ",");
            switch (operator) {
                case "Point_Get" -> {
                    keyAccesses++;
                    if (access.equals(tableOnly)) {
                        assertThat(pkType).isEqualTo("CLUSTERED");
                        assertThat(primary).hasSize(1);
                        assertThat(info).contains("handle:");
                    } else {
                        assertThat(access).isIn(tableOnly + "," + primaryIndex, tableOnly + "," + clusteredIndex);
                    }
                }
                case "TableRangeScan" -> {
                    keyAccesses++;
                    assertThat(access).isEqualTo(tableOnly);
                    assertThat(pkType).isEqualTo("CLUSTERED");
                    assertCompletePointRange(info, primary.size(), sql);
                }
                case "IndexRangeScan" -> {
                    keyAccesses++;
                    primaryIndexRange = true;
                    assertThat(access).isIn(tableOnly + "," + primaryIndex, tableOnly + "," + clusteredIndex);
                    assertCompletePointRange(info, primary.size(), sql);
                }
                case "TableRowIDScan" -> {
                    rowIdLookups++;
                    assertThat(access).isEqualTo(tableOnly);
                }
                case "IndexLookUp" -> {
                    indexLookup = true;
                    assertThat(access).isEmpty();
                }
                case "Projection", "Selection", "Update", "Delete", "TableReader", "SelectLock" ->
                    assertThat(access).isEmpty();
                default -> throw new AssertionError(sql + " 出现未证明为完整主键点操作的 TiDB 算子：" + operator);
            }
        }
        assertThat(keyAccesses).as("%s 必须恰好一个完整主键点入口", sql).isEqualTo(1);
        if (primaryIndexRange) {
            assertThat(indexLookup).isTrue();
            assertThat(rowIdLookups).isEqualTo(1);
        } else {
            assertThat(indexLookup).isFalse();
            assertThat(rowIdLookups).isZero();
        }
    }

    /** TiDB Range.String 的闭区间：上下界全列逐值相等；未知/无界/前缀/多区间一律红。 */
    private static void assertCompletePointRange(String info, int keyColumns, String name) {
        var matcher = java.util.regex.Pattern.compile("^range:\\[(-?\\d+(?: -?\\d+)*),(-?\\d+(?: -?\\d+)*)], (.+)$").matcher(info);
        assertThat(matcher.matches()).as("%s 不是单个整数闭区间：%s", name, info).isTrue();
        assertThat(matcher.group(1)).as("%s 不是等值点区间", name).isEqualTo(matcher.group(2));
        assertThat(matcher.group(1).split(" ")).as("%s 区间没有用满主键列", name).hasSize(keyColumns);
        assertThat(matcher.group(3)).doesNotContain("range:");
    }

    // ================================================================ 工具

    @Test
    void 原会话过户_ABA与冻结均拒绝所有关系读写_不留容量和关系副作用() throws SQLException {
        JdbcFriendStore system = store(LIMITS);
        exec("INSERT INTO player(player_id,account,owner_revision) VALUES (22,'original',7),(11,'peer',3)");
        FriendStore original = system.forActor(22, "original", 7);
        for (String change : List.of("account='new-owner',owner_revision=8", "account='original',owner_revision=9",
                "account='original',owner_revision=7,trade_listing_id=81")) {
            exec("UPDATE player SET " + change + " WHERE player_id=22");
            assertThatThrownBy(() -> original.addRequest(22, 11, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.accept(123, 22, 0, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.reject(123, 22, 0, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.remove(22, 11, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.block(22, 11, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.unblock(22, 11, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.friends(22, 100, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.pendingRequests(22, 100, d())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.blocks(22, 100, d())).isInstanceOf(SecurityException.class);
            for (String table : List.of("friend", "friend_request", "friend_capacity", "friend_block")) {
                assertThat(countRows(table)).as("失效会话不能留下 " + table).isZero();
            }
        }
    }

    @Test
    void 原会话正常申请同意与解除拉黑_过户后旧视图不能清除新主关系() throws SQLException {
        JdbcFriendStore system = store(LIMITS);
        exec("INSERT INTO player(player_id,account,owner_revision) VALUES (22,'original',7),(11,'peer',3)");
        FriendStore original = system.forActor(22, "original", 7);
        FriendStore peer = system.forActor(11, "peer", 3);
        long id = peer.addRequest(11, 22, d()).requestId();
        assertThat(original.accept(id, 22, 0, d()).result()).isEqualTo(AcceptResult.OK);
        assertThat(original.friends(22, 100, d())).extracting(FriendEdge::friendPlayerId).containsExactly(11L);
        assertThat(original.block(22, 11, d())).isEqualTo(BlockResult.OK);
        assertThat(original.blocks(22, 100, d())).hasSize(1);
        exec("UPDATE player SET account='new-owner',owner_revision=8 WHERE player_id=22");
        assertThatThrownBy(() -> original.unblock(22, 11, d())).isInstanceOf(SecurityException.class);
        FriendStore next = system.forActor(22, "new-owner", 8);
        assertThat(next.blocks(22, 100, d())).hasSize(1);
        next.unblock(22, 11, d());
        assertThat(next.blocks(22, 100, d())).isEmpty();
        assertInvariants();
    }

    @Test
    void 冻结目标不能被申请或拉黑_高半区玩家号保持无符号锁序() throws SQLException {
        JdbcFriendStore system = store(LIMITS);
        exec("INSERT INTO player(player_id,account,owner_revision,trade_listing_id)"
                + " VALUES (18446744073709551615,'actor',7,0),(11,'peer',3,81)");
        FriendStore actor = system.forActor(-1, "actor", 7);
        assertThatThrownBy(() -> actor.addRequest(-1, 11, d())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> actor.block(-1, 11, d())).isInstanceOf(SecurityException.class);
        assertThat(countRows("friend_capacity")).isZero();
        exec("UPDATE player SET trade_listing_id=0 WHERE player_id=11");
        assertThat(actor.addRequest(-1, 11, d()).result()).isEqualTo(AddResult.OK);
        assertThat(system.forActor(11, "peer", 3).pendingRequests(11, 10, d()))
                .extracting(FriendStore.PendingRequest::fromPlayerId).containsExactly(-1L);
    }

    @Test
    void 预检后过户_最终关系事务仍复核原会话_不把容量预检当授权() throws SQLException {
        exec("INSERT INTO player(player_id,account,owner_revision) VALUES (22,'original',7),(11,'peer',3)");
        JdbcFriendStore original = (JdbcFriendStore) store(LIMITS).forActor(22, "original", 7);
        original.afterEnsureForTest(() -> {
            try { exec("UPDATE player SET account='next',owner_revision=8 WHERE player_id=22"); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        });
        assertThatThrownBy(() -> original.addRequest(22, 11, d())).isInstanceOf(SecurityException.class);
        assertThat(countRows("friend_request")).isZero();
        assertThat(countRows("friend")).isZero();
        assertThat(countRows("friend_block")).isZero();
        assertThat(store(LIMITS).forActor(22, "next", 8).pendingRequests(22, 100, d())).isEmpty();
    }

    private static void befriend(JdbcFriendStore s, long a, long b) {
        assertThat(s.addRequest(a, b, d()).result()).isEqualTo(AddResult.OK);
        assertThat(accept(s, a, b, d())).isEqualTo(AcceptResult.OK);
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
        try (Connection c = fixture.schemaConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
