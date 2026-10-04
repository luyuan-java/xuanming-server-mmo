package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.friend.store.RecommendStore.Candidate;
import com.game.friend.support.Deadline;
import com.game.friend.sweep.FriendSweep;
import com.game.friend.store.pb.FriendBlockRow;
import com.game.friend.store.pb.FriendCapacityRow;
import com.game.friend.store.pb.FriendEdgeRow;
import com.game.friend.store.pb.FriendRequestRow;
import com.game.pbmysql.PbMysql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 推荐两条 SQL 与清理在真 MySQL 上的行为（缺省跳过：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}）：排除五类关系与调用方 exclude、
 * 共同好友数排序、随机锚点窗口不回绕、空表；清理的终态 / 零好友行、report_only 只数、updated_ms = 0 保险、提交点复核、截止点护栏。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class RecommendAndSweepMysqlTest {

    private static final String BASE_URL = System.getProperty("xm.it.mysql");
    private static final String USER = System.getProperty("xm.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES'";
    private static final String DATABASE = "xm_friend_rs_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final long DAY = 86_400_000L;

    private static DruidDataSource dataSource;

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
        dataSource.setMaxActive(8);
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

    private static Deadline d() {
        return Deadline.after(30_000);
    }

    private static RecommendStore recommend(long pivotOffset) {
        return new RecommendStore(dataSource, 10, (lo, span) -> pivotOffset);
    }

    private static void edge(long a, long b) throws SQLException {
        exec("INSERT INTO friend (player_id, friend_player_id, since_ms) VALUES (" + a + "," + b + ",1),(" + b + "," + a + ",1)");
    }

    // ================================================================ 推荐

    @Test
    void 共同好友_按共同数降序_排除五类关系与exclude() throws SQLException {
        long me = 1;
        edge(me, 10);
        edge(me, 11);
        for (long c : new long[] {20, 21, 22, 23, 24, 25, 26, 27}) {
            edge(10, c);
        }
        edge(11, 20);
        edge(me, 27);                                     // 27 已是我的好友
        exec("INSERT INTO friend_block VALUES (1, 21, 1)"); // 我拉黑 21
        exec("INSERT INTO friend_block VALUES (22, 1, 1)"); // 22 拉黑我
        exec("INSERT INTO friend_request VALUES (1, 23, 1, 1, 1)"); // 我发给 23 的 pending
        exec("INSERT INTO friend_request VALUES (24, 1, 1, 1, 1)"); // 24 发给我的 pending
        exec("INSERT INTO friend_request VALUES (1, 26, 1, 3, 1)"); // 已拒绝的申请不排除
        List<Candidate> got = recommend(0).mutual(me, List.of(25L), 20, d());
        assertThat(got.get(0)).isEqualTo(new Candidate(20, 2));
        assertThat(got).extracting(Candidate::playerId).containsExactlyInAnyOrder(20L, 26L);
        assertThat(recommend(0).mutual(me, List.of(), 1, d())).containsExactly(new Candidate(20, 2));
    }

    @Test
    void 随机兜底_空表为空_窗口按升序_不回绕_排除自己与exclude() throws SQLException {
        assertThat(recommend(0).random(1, List.of(), 10, d())).isEmpty();
        edge(100, 200);
        edge(300, 400);
        edge(1, 500);
        // pivot = lo + 0 = 1：窗口 1,100,200,300,400,500 → 去掉自己 1、已是好友的 500、exclude 300
        assertThat(recommend(0).random(1, List.of(300L), 10, d())).extracting(Candidate::playerId)
                .containsExactly(100L, 200L, 400L);
        assertThat(recommend(0).random(1, List.of(), 10, d())).allMatch(c -> c.mutualFriends() == 0);
        // pivot 靠尾部：只看 pivot 之后，不回绕
        assertThat(recommend(399).anchor(1, List.of(), 400, 10, d())).extracting(Candidate::playerId).containsExactly(400L);
        assertThat(recommend(0).anchor(1, List.of(), 401, 10, d())).isEmpty();
    }

    // ================================================================ 清理

    @Test
    void 清理终态申请_report_only只数_delete删过期终态_pending与未过期不动() throws SQLException {
        long now = 100 * DAY;
        exec("INSERT INTO friend_request VALUES (1, 2, 1, 2, " + (now - 8 * DAY) + ")"); // 已同意、过期
        exec("INSERT INTO friend_request VALUES (3, 4, 1, 3, " + (now - 9 * DAY) + ")"); // 已拒绝、过期
        exec("INSERT INTO friend_request VALUES (5, 6, 1, 1, " + (now - 9 * DAY) + ")"); // pending 永不清
        exec("INSERT INTO friend_request VALUES (7, 8, 1, 3, " + (now - 1 * DAY) + ")"); // 未过期
        SweepStore s = new SweepStore(dataSource, 10);
        assertThat(s.sweepTerminalRequests("report_only", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(2, 0));
        assertThat(s.sweepTerminalRequests("bogus", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(2, 0));
        assertThat(s.sweepTerminalRequests("delete", 7, 1, now, () -> true)).isEqualTo(SweepStore.Result.ok(1, 1));
        assertThat(s.sweepTerminalRequests("delete", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(1, 1));
        assertThat(requestKeys()).containsExactly("5-6", "7-8");
    }

    @Test
    void 清理终态申请_updated_ms为0时只数不删() throws SQLException {
        long now = 100 * DAY;
        exec("INSERT INTO friend_request VALUES (1, 2, 1, 2, 0)");
        SweepStore s = new SweepStore(dataSource, 10);
        assertThat(s.sweepTerminalRequests("delete", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(1, 0));
        assertThat(requestKeys()).containsExactly("1-2");
    }

    @Test
    void 回收零好友容量行_提交点复核_截止点护栏() throws SQLException {
        long now = 100 * DAY;
        exec("INSERT INTO friend_capacity VALUES (1, 0, " + (now - 8 * DAY) + ")"); // 可回收
        exec("INSERT INTO friend_capacity VALUES (2, 1, " + (now - 8 * DAY) + ")"); // 有好友
        exec("INSERT INTO friend_capacity VALUES (3, 0, " + (now - 1 * DAY) + ")"); // 未过期
        exec("INSERT INTO friend_capacity VALUES (4, 0, 0)");                        // created_ms=0 也可回收
        SweepStore s = new SweepStore(dataSource, 10);
        assertThat(s.sweepIdleCapacityRows("report_only", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(2, 0));
        // 提交点复核：直调删「此刻已有好友」的行不删
        assertThat(s.deleteIdleCapacityRow(2, now - 7 * DAY)).isZero();
        assertThat(s.sweepIdleCapacityRows("delete", 7, 1000, now, () -> true)).isEqualTo(SweepStore.Result.ok(2, 2));
        assertThat(capacityIds()).containsExactly(2L, 3L);
        // 截止点非正：本轮什么都不做；参数越界 fail-fast
        assertThat(s.sweepIdleCapacityRows("delete", 7, 1000, 6 * DAY, () -> true)).isEqualTo(SweepStore.Result.ok(0, 0));
        assertThatThrownBy(() -> s.sweepIdleCapacityRows("delete", 0, 1000, now, () -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.sweepIdleCapacityRows("delete", 7, 0, now, () -> true))
                .isInstanceOf(IllegalArgumentException.class);
        // 预算用完：停在行与行之间，带回已删行数
        exec("INSERT INTO friend_capacity VALUES (5, 0, 1), (6, 0, 1)");
        SweepStore.Result stopped = s.sweepIdleCapacityRows("delete", 7, 1000, now, () -> false);
        assertThat(stopped.deleted()).isZero();
        assertThat(stopped.error()).isNotNull();
    }

    @Test
    void 回收的提交点复核_刚重建的行与恰在截止点的行都不删() throws SQLException {
        long now = 100 * DAY;
        long cutoff = now - 7 * DAY;
        exec("INSERT INTO friend_capacity VALUES (11, 0, " + now + ")");          // 刚被 ensure 重建
        exec("INSERT INTO friend_capacity VALUES (12, 0, " + cutoff + ")");       // 恰在截止点（< 不含等号）
        exec("INSERT INTO friend_capacity VALUES (13, 0, " + (cutoff - 1) + ")"); // 对照：过期
        SweepStore s = new SweepStore(dataSource, 10);
        assertThat(s.deleteIdleCapacityRow(11, cutoff)).isZero();
        assertThat(s.deleteIdleCapacityRow(12, cutoff)).isZero();
        assertThat(s.deleteIdleCapacityRow(13, cutoff)).isEqualTo(1);
        assertThat(capacityIds()).containsExactly(11L, 12L);
    }

    @Test
    void 终态申请_有updated_ms为0的行时整轮只数不删_连带别的过期行() throws SQLException {
        long now = 100 * DAY;
        exec("INSERT INTO friend_request VALUES (1, 2, 1, 2, 0)");
        exec("INSERT INTO friend_request VALUES (3, 4, 1, 3, " + (now - 8 * DAY) + ")");
        exec("INSERT INTO friend_request VALUES (5, 6, 1, 2, " + (now - 9 * DAY) + ")");
        SweepStore.Result r = new SweepStore(dataSource, 10).sweepTerminalRequests("delete", 7, 1000, now, () -> true);
        assertThat(r).isEqualTo(SweepStore.Result.ok(3, 0));
        assertThat(requestKeys()).containsExactly("1-2", "3-4", "5-6");
    }

    @Test
    void 预算用完停在行与行之间_带回已删行数() throws SQLException {
        long now = 100 * DAY;
        exec("INSERT INTO friend_request VALUES (1, 2, 1, 2, " + (now - 8 * DAY) + ")");
        exec("INSERT INTO friend_request VALUES (3, 4, 1, 3, " + (now - 8 * DAY) + ")");
        SweepStore s = new SweepStore(dataSource, 10);
        SweepStore.Result none = s.sweepTerminalRequests("delete", 7, 1000, now, () -> false);
        assertThat(none.seen()).isEqualTo(2);
        assertThat(none.deleted()).isZero();
        assertThat(none.error()).isNotNull();
        java.util.concurrent.atomic.AtomicInteger allowance = new java.util.concurrent.atomic.AtomicInteger(1);
        SweepStore.Result one = s.sweepTerminalRequests("delete", 7, 1000, now, () -> allowance.getAndDecrement() > 0);
        assertThat(one.deleted()).isEqualTo(1);
        assertThat(one.error()).isNotNull();
        assertThat(requestKeys()).hasSize(1);

        exec("INSERT INTO friend_capacity VALUES (21, 0, 1), (22, 0, 1)");
        java.util.concurrent.atomic.AtomicInteger capAllowance = new java.util.concurrent.atomic.AtomicInteger(1);
        SweepStore.Result cap = s.sweepIdleCapacityRows("delete", 7, 1000, now, () -> capAllowance.getAndDecrement() > 0);
        assertThat(cap.seen()).isEqualTo(2);
        assertThat(cap.deleted()).isEqualTo(1);
        assertThat(cap.error()).isNotNull();
    }

    @Test
    void 清理一轮_两段都跑_合法模式每轮刷Gauge含0() throws SQLException {
        long now = System.currentTimeMillis();
        exec("INSERT INTO friend_request VALUES (1, 2, 1, 3, " + (now - 9 * DAY) + ")");
        exec("INSERT INTO friend_capacity VALUES (1, 0, " + (now - 9 * DAY) + ")");
        java.util.Map<String, Long> pending = new java.util.HashMap<>();
        java.util.Map<String, Long> idle = new java.util.HashMap<>();
        FriendSweep.Gauges gauges = new FriendSweep.Gauges() {
            @Override
            public void pendingRows(String mode, long value) {
                pending.put(mode, value);
            }

            @Override
            public void idleCapacityRows(String mode, long value) {
                idle.put(mode, value);
            }
        };
        SweepStore store = new SweepStore(dataSource, 10);
        new FriendSweep(store, "report_only", java.time.Duration.ofMinutes(5), 7, 1000, System::currentTimeMillis, gauges)
                .runRound();
        assertThat(pending).containsEntry("report_only", 1L);
        assertThat(idle).containsEntry("report_only", 1L);
        assertThat(requestKeys()).containsExactly("1-2");

        new FriendSweep(store, "delete", java.time.Duration.ofMinutes(5), 7, 1000, System::currentTimeMillis, gauges).runRound();
        assertThat(requestKeys()).isEmpty();
        assertThat(capacityIds()).isEmpty();
        new FriendSweep(store, "delete", java.time.Duration.ofMinutes(5), 7, 1000, System::currentTimeMillis, gauges).runRound();
        assertThat(pending).containsEntry("delete", 0L); // 每轮都刷，含 0
        assertThat(idle).containsEntry("delete", 0L);
    }

    private static List<String> requestKeys() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT from_player_id, to_player_id FROM friend_request ORDER BY 1, 2")) {
            while (rs.next()) {
                out.add(rs.getLong(1) + "-" + rs.getLong(2));
            }
        }
        return out;
    }

    private static List<Long> capacityIds() throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT player_id FROM friend_capacity ORDER BY 1"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
