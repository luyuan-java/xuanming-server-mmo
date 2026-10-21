package com.game.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.friend.store.FriendLimits;
import com.game.friend.store.FriendStore;
import com.game.friend.store.FriendStore.AcceptOutcome;
import com.game.friend.store.FriendStore.AcceptResult;
import com.game.friend.store.FriendStore.BlockResult;
import com.game.friend.store.FriendStore.RemoveResult;
import com.game.friend.store.JdbcFriendStore;
import com.game.friend.store.pb.FriendBlockRow;
import com.game.friend.store.pb.FriendCapacityRow;
import com.game.friend.store.pb.FriendEdgeRow;
import com.game.friend.store.pb.FriendRequestRow;
import com.game.pbmysql.PbMysql;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 本机真实 TiDB 的双副本契约：两个独立 Druid 池经过生产会话策略，权威表在同一事务域。
 * 只在随机专库内建表、删表；没有网络分区或真实跨区域部署，不代替双区故障验收。
 * 入口为 game.it.tidb；验收同时传 game.it.tidb.required=true，缺入口必须失败。
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FriendReplicasTiDBTest {
    private static final String DATABASE = "game_friend_replica_it_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"
            + "&connectTimeout=3000&socketTimeout=10000";
    private static final FriendLimits LIMITS = new FriendLimits(200, 50, 200, 200);
    private static final AtomicLong REQUEST_IDS = new AtomicLong(10000);
    private static String server;
    private static boolean databaseCreated;
    private static DruidDataSource firstPool;
    private static DruidDataSource secondPool;

    @BeforeAll
    static void createDatabase() throws Exception {
        String configured = System.getProperty("game.it.tidb", "").trim();
        if (Boolean.getBoolean("game.it.tidb.required")) {
            assertThat(configured).as("严格验收要求设置 game.it.tidb").isNotEmpty();
        }
        assumeTrue(!configured.isEmpty(), "未设置 game.it.tidb，未运行真实双副本 TiDB 契约");
        if (!configured.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("双副本测试只接受本机 TiDB JDBC 地址");
        }
        URI address = URI.create(configured.substring("jdbc:".length()));
        if ((!"127.0.0.1".equals(address.getHost()) && !"localhost".equals(address.getHost()))
                || address.getPort() <= 0 || address.getRawUserInfo() != null) {
            throw new IllegalArgumentException("双副本测试只接受本机显式端口，凭据不得放进 URL");
        }
        server = "jdbc:mysql://" + address.getHost() + ":" + address.getPort();
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute(FriendConnectionPolicy.SESSION_SQL);
            FriendConfiguration.verifyFriendDatabase(connection, true);
            statement.execute("CREATE DATABASE `" + DATABASE + "` DEFAULT CHARACTER SET utf8mb4");
            databaseCreated = true;
        }
        firstPool = pool();
        secondPool = pool();
        PbMysql schema = new PbMysql();
        schema.register(FriendEdgeRow.getDefaultInstance());
        schema.register(FriendRequestRow.getDefaultInstance());
        schema.register(FriendCapacityRow.getDefaultInstance());
        schema.register(FriendBlockRow.getDefaultInstance());
        try (Connection first = firstPool.getConnection(); Connection second = secondPool.getConnection()) {
            FriendConfiguration.verifyFriendDatabase(first, true);
            FriendConfiguration.verifyFriendDatabase(second, true);
            assertThat(connectionId(first)).isNotEqualTo(connectionId(second));
            schema.syncAll(first);
            try (var statement = first.createStatement()) {
                statement.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY, account VARBINARY(191) NOT NULL,"
                        + " owner_revision BIGINT UNSIGNED NOT NULL, trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0, zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            }
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/game-route-authority-schema.sql")).execute(firstPool);
            com.game.common.player.RouteAuthorityInitializer.initialize(first,UUID.randomUUID().toString(),java.util.List.of(1));
        }
    }

    private static DruidDataSource pool() {
        DruidDataSource pool = new DruidDataSource();
        pool.setUrl(server + "/" + DATABASE + PARAMS);
        pool.setUsername(user());
        pool.setPassword(password());
        pool.setMaxActive(16);
        pool.setMaxWait(10_000);
        new FriendConnectionPolicy(true).postProcessBeforeInitialization(pool, "replicaDataSource");
        return pool;
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (firstPool != null) {
            firstPool.close();
        }
        if (secondPool != null) {
            secondPool.close();
        }
        if (databaseCreated) {
            try (Connection connection = admin(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + DATABASE + "`");
            }
        }
    }

    @BeforeEach
    void emptyTables() throws SQLException {
        try (Connection connection = firstPool.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("friend", "friend_request", "friend_capacity", "friend_block", "player")) {
                statement.execute("TRUNCATE TABLE " + table);
            }
        }
    }

    @Test
    void 双副本共享原会话权限_过户与ABA旧请求拒绝_冻结目标关系不变() throws Exception {
        try (Connection connection = firstPool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO player(player_id,account,owner_revision) VALUES(3101,'original',7),(3102,'peer',3)");
        }
        FriendStore original = store(firstPool, LIMITS).forActor(3101, "original", 7);
        FriendStore peer = store(secondPool, LIMITS).forActor(3102, "peer", 3);
        long request = original.addRequest(3101, 3102, deadline()).requestId();
        assertThat(peer.accept(request, 3102, 0, deadline()).result()).isEqualTo(AcceptResult.OK);
        assertThat(original.friends(3101, 200, deadline())).hasSize(1);
        for (String changed : List.of("account='next',owner_revision=8", "account='original',owner_revision=9")) {
            try (Connection connection = secondPool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("UPDATE player SET " + changed + " WHERE player_id=3101");
            }
            assertThatThrownBy(() -> original.remove(3101, 3102, deadline())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.block(3101, 3102, deadline())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> original.friends(3101, 200, deadline())).isInstanceOf(SecurityException.class);
            assertThat(count("SELECT COUNT(*) FROM friend")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM friend_block")).isZero();
        }
        FriendStore current = store(secondPool, LIMITS).forActor(3101, "original", 9);
        assertThat(current.friends(3101, 200, deadline())).hasSize(1);
        try (Connection connection = firstPool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE player SET trade_listing_id=81 WHERE player_id=3101");
        }
        assertThatThrownBy(() -> current.remove(3101, 3102, deadline())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> peer.remove(3102, 3101, deadline())).isInstanceOf(SecurityException.class);
        assertThat(count("SELECT COUNT(*) FROM friend")).isEqualTo(2);
        assertInvariants();
    }

    @Test
    void 独立副本反向接受只提交一次_删除和重发拒绝旧代次() throws Exception {
        FriendStore first = store(firstPool, LIMITS);
        FriendStore second = store(secondPool, LIMITS);
        long forward = first.addRequest(1101, 1102, deadline()).requestId();
        long reverse = second.addRequest(1102, 1101, deadline()).requestId();
        List<AcceptOutcome> outcomes = concurrent(16, i -> i % 2 == 0
                ? first.accept(forward, 1102, 9999, deadline())
                : second.accept(reverse, 1101, 9999, deadline()));
        assertThat(outcomes).allMatch(outcome -> outcome.result() == AcceptResult.OK);
        assertThat(outcomes.stream().filter(AcceptOutcome::changed).count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM friend_request WHERE status=2")).isEqualTo(2);
        for (FriendStore store : List.of(first, second)) {
            assertThat(store.friends(1102, 200, deadline())).hasSize(1)
                    .allMatch(edge -> edge.friendPlayerId() == 1101);
            assertThat(store.pendingRequests(1102, 200, deadline())).isEmpty();
        }
        assertThat(second.remove(1101, 1102, deadline())).isEqualTo(RemoveResult.REMOVED);
        assertThat(first.accept(forward, 1102, 9999, deadline()))
                .isEqualTo(new AcceptOutcome(AcceptResult.OK, 1101, false));
        assertThat(first.friends(1102, 200, deadline())).isEmpty();
        long next = second.addRequest(1101, 1102, deadline()).requestId();
        assertThat(next).isNotEqualTo(forward);
        assertThat(first.accept(forward, 1102, 1101, deadline()).result()).isEqualTo(AcceptResult.NO_PENDING);
        assertThat(first.accept(next, 1102, 9999, deadline()).changed()).isTrue();
        assertInvariants();
    }

    @Test
    void 独立副本并发接受守住硬上限_拉黑之后不能恢复关系() throws Exception {
        FriendLimits limits = new FriendLimits(2, 50, 200, 200);
        FriendStore first = store(firstPool, limits);
        FriendStore second = store(secondPool, limits);
        long[] requests = new long[16];
        for (int i = 0; i < requests.length; i++) {
            requests[i] = first.addRequest(2100 + i, 2200, deadline()).requestId();
        }
        List<AcceptOutcome> outcomes = concurrent(requests.length, i -> (i % 2 == 0 ? first : second)
                .accept(requests[i], 2200, 2100 + i, deadline()));
        assertThat(outcomes).allMatch(outcome -> outcome.result() == AcceptResult.OK
                || outcome.result() == AcceptResult.ACCEPTOR_FULL);
        assertThat(outcomes.stream().filter(AcceptOutcome::changed).count()).isEqualTo(2);
        assertThat(count("SELECT friend_count FROM friend_capacity WHERE player_id=2200")).isEqualTo(2);

        long request = first.addRequest(2301, 2302, deadline()).requestId();
        concurrent(2, i -> {
            if (i == 0) {
                assertThat(first.accept(request, 2302, 2301, deadline()).result())
                        .isIn(AcceptResult.OK, AcceptResult.NO_PENDING, AcceptResult.BLOCKED);
            } else {
                assertThat(second.block(2302, 2301, deadline())).isEqualTo(BlockResult.OK);
            }
            return null;
        });
        assertThat(count("SELECT COUNT(*) FROM friend_block WHERE player_id=2302 AND blocked_player_id=2301")).isEqualTo(1);
        assertThat(first.friends(2302, 200, deadline())).isEmpty();
        assertThat(first.accept(request, 2302, 2301, deadline()).changed()).isFalse();
        assertThat(second.friends(2302, 200, deadline())).isEmpty();
        assertInvariants();
    }

    private static JdbcFriendStore store(DruidDataSource pool, FriendLimits limits) {
        return new JdbcFriendStore(pool, limits, System::currentTimeMillis, 10,
                JdbcFriendStore.Events.NONE, REQUEST_IDS::incrementAndGet);
    }

    private static <T> List<T> concurrent(int n, IntFunction<T> action) throws Exception {
        var executor = Executors.newFixedThreadPool(n);
        CyclicBarrier start = new CyclicBarrier(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return action.apply(index);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertInvariants() throws SQLException {
        assertThat(count("SELECT COUNT(*) FROM friend f LEFT JOIN friend r"
                + " ON r.player_id=f.friend_player_id AND r.friend_player_id=f.player_id WHERE r.player_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM friend_capacity c LEFT JOIN"
                + " (SELECT player_id,COUNT(*) actual FROM friend GROUP BY player_id) f ON f.player_id=c.player_id"
                + " WHERE c.friend_count<>COALESCE(f.actual,0)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM friend f JOIN friend_block b"
                + " ON b.player_id=f.player_id AND b.blocked_player_id=f.friend_player_id")).isZero();
    }

    private static long count(String query) throws SQLException {
        try (Connection connection = secondPool.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static Deadline deadline() {
        return Deadline.after(60_000);
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(server + "/" + PARAMS, user(), password());
    }

    private static String user() {
        return System.getProperty("game.it.mysql.user", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    }
}
