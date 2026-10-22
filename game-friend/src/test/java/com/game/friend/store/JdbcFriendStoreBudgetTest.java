package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.friend.store.FriendStore.RejectResult;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcFriendStore} 的提交点（不连 MySQL：JDBC 全是记录型桩，按 SQL 文本给结果、记下每条写语句与 COMMIT / ROLLBACK）。
 *
 * <p>只钉 mmorpg client-request-ordering.md §6.1 那一条：请求截止之后不得再发出 COMMIT。gate 对同一会话的后端请求逐条发出，friend 回包或
 * gate 的 5 s 调用超时之后就放行下一条；语句在锁等待里跑过截止之后才提交的写，会落在同一会话的下一条请求之后。
 *
 * <p>「截止在语句执行期间过去」的做法：game-common 的 {@link Deadline} 没有可注入时钟，桩在被选中的那条写语句里等到
 * {@link Deadline#expired()} 为真再返回（等的是这个截止本身，不是猜一个时长），所以结果确定；预算给得宽，前面的语句一定在截止之前发出
 * （断言里核对那条语句确实执行过，保证测的是提交点而不是逐条语句的预算检查）。
 */
class JdbcFriendStoreBudgetTest {

    private static final FriendLimits LIMITS = new FriendLimits(200, 50, 200, 200);
    /** 宽裕到被选中语句之前的几步一定在截止之前完成；被选中的语句会把它等完。 */
    private static final long BUDGET_MS = 500;
    private static final String LOCK_ROUTE_META = "SELECT generation,writable,fence_shards FROM route_meta WHERE id=1 LOCK IN SHARE MODE";

    private final Map<String, List<Object[]>> queryRows = new HashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> updates = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> updateCounts = new ConcurrentHashMap<>();
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger capacityCommits = new AtomicInteger();
    private final AtomicInteger capacityRollbacks = new AtomicInteger();
    private final AtomicInteger stalledWrites = new AtomicInteger();
    private final AtomicBoolean stallStartedBeforeDeadline = new AtomicBoolean();
    /** 第 {@link #stallOccurrence} 次执行 {@link #stallSql} 时，等到 {@link #stallUntil} 过期才返回。 */
    private volatile String stallSql;
    private volatile int stallOccurrence;
    private volatile Deadline stallUntil;

    private JdbcFriendStore store;
    private DatabaseMetaData metadata;

    @BeforeEach
    void setUp() throws SQLException {
        // 先各建一次桩：Mockito 头一次为某个类型生成代理较慢，不要让它花在请求预算里
        mock(Connection.class);
        mock(PreparedStatement.class);
        mock(ResultSet.class);
        metadata = mock(DatabaseMetaData.class);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        // 系统写入口仍实际读取并校验正式meta；不绕过生产围栏，也不把未知SQL默认为可写。
        queryRows.put(LOCK_ROUTE_META, List.<Object[]>of(new Object[] {"7425c34c-87c2-46c9-95a4-8af56755c014", "1", 64}));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> connection());
        store = new JdbcFriendStore(dataSource, LIMITS, () -> 1_000L, 3, JdbcFriendStore.Events.NONE, () -> 99L);
    }

    @Test
    void 原会话请求视图拒绝空原件_高半区修订与主体替换() {
        assertThatThrownBy(() -> store.forActor(0, "original", 7)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.forActor(22, "", 7)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.forActor(22, "original", 0)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.forActor(22, "original", Long.MIN_VALUE)).isInstanceOf(SecurityException.class);
        FriendStore original = store.forActor(22, "original", 7);
        assertThatThrownBy(() -> original.addRequest(23, 11, Deadline.after(30_000))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> original.friends(23, 100, Deadline.after(30_000))).isInstanceOf(SecurityException.class);
        assertThat(updates).isEmpty();
        assertThat(queries).isEmpty();
        assertThat(commits).hasValue(0);
    }

    @Test
    void 拒绝_截止前完成_提交一次() {
        queryRows.put(JdbcFriendStore.FIND_REQUEST, List.<Object[]>of(new Object[] {11L, 22L, JdbcFriendStore.STATUS_PENDING, 5L, 0L}));

        FriendStore.RejectOutcome outcome = store.reject(5, 22, 0, Deadline.after(30_000));

        assertThat(outcome.result()).isEqualTo(RejectResult.OK);
        assertThat(outcome.changed()).isTrue();
        assertThat(updates).containsExactly(JdbcFriendStore.UPDATE_REQUEST_GENERATION_STATUS);
        assertThat(queries).containsExactly(JdbcFriendStore.FIND_REQUEST, LOCK_ROUTE_META);
        assertThat(commits).as("桩确实记得到 COMMIT（下面「不提交」的断言不是空转）").hasValue(1);
        assertThat(rollbacks).hasValue(0);
    }

    @Test
    void 拒绝_写语句跑过请求截止_不提交_回滚_按存储故障抛() {
        queryRows.put(JdbcFriendStore.FIND_REQUEST, List.<Object[]>of(new Object[] {11L, 22L, JdbcFriendStore.STATUS_PENDING, 5L, 0L}));
        Deadline deadline = Deadline.after(BUDGET_MS);
        stall(JdbcFriendStore.UPDATE_REQUEST_GENERATION_STATUS, 1, deadline);

        assertThatThrownBy(() -> store.reject(5, 22, 0, deadline)).isInstanceOf(FriendStoreException.class);

        assertThat(updates).as("语句在截止之前发出（测的是提交点）").containsExactly(JdbcFriendStore.UPDATE_REQUEST_GENERATION_STATUS);
        assertThat(queries).containsExactly(JdbcFriendStore.FIND_REQUEST, LOCK_ROUTE_META);
        assertDeadlineExpiredInWrite();
        // 以前是自动提交：语句返回即已提交，客户端收到 1003 / gate 已放行下一条之后申请才变成已拒绝
        assertThat(commits).as("截止之后不发 COMMIT").hasValue(0);
        assertThat(rollbacks).hasValue(1);
    }

    @Test
    void 解除拉黑_写语句跑过请求截止_不提交_回滚() {
        Deadline deadline = Deadline.after(BUDGET_MS);
        stall(JdbcFriendStore.DELETE_BLOCK, 1, deadline);

        assertThatThrownBy(() -> store.unblock(22, 33, deadline)).isInstanceOf(FriendStoreException.class);

        assertThat(updates).containsExactly(JdbcFriendStore.DELETE_BLOCK);
        assertThat(queries).containsExactly(LOCK_ROUTE_META);
        assertDeadlineExpiredInWrite();
        assertThat(commits).as("截止之后不发 COMMIT").hasValue(0);
        assertThat(rollbacks).hasValue(1);
    }

    /** 守卫事务（Add / Accept / Remove / Block 共用）的代表：体内最后一条写跑过截止 → 不提交、回滚。 */
    @Test
    void 删好友_守卫事务体内最后一条写跑过请求截止_不提交_回滚() {
        queryRows.put(JdbcFriendStore.EDGE_EXISTS_EITHER, List.<Object[]>of(new Object[] {1}));
        queryRows.put(JdbcFriendStore.COUNT_FRIENDS, List.<Object[]>of(new Object[] {1L}));
        queryRows.put(JdbcFriendStore.LOCK_CAPACITY, List.<Object[]>of(new Object[] {1L}));
        Deadline deadline = Deadline.after(BUDGET_MS);
        stall(JdbcFriendStore.DECREMENT_COUNT, 2, deadline);

        assertThatThrownBy(() -> store.remove(22, 33, deadline)).isInstanceOf(FriendStoreException.class);

        assertThat(updates).as("体内的写都在截止之前发出").containsExactly(JdbcFriendStore.ENSURE_CAPACITY, JdbcFriendStore.ENSURE_CAPACITY,
                JdbcFriendStore.DELETE_EDGE, JdbcFriendStore.DECREMENT_COUNT, JdbcFriendStore.DELETE_EDGE, JdbcFriendStore.DECREMENT_COUNT);
        assertThat(queries).containsExactly(JdbcFriendStore.EDGE_EXISTS_EITHER,
                LOCK_ROUTE_META, JdbcFriendStore.COUNT_FRIENDS, LOCK_ROUTE_META, JdbcFriendStore.COUNT_FRIENDS,
                LOCK_ROUTE_META, JdbcFriendStore.LOCK_CAPACITY, JdbcFriendStore.LOCK_CAPACITY);
        assertDeadlineExpiredInWrite();
        // 补容量是先行独立提交；不能把它们混进之后越过截止的好友事务，也不能漏记这些真实提交。
        assertThat(capacityCommits).hasValue(2);
        assertThat(capacityRollbacks).hasValue(1);
        assertThat(commits).as("好友事务截止之后不发 COMMIT").hasValue(0);
        assertThat(rollbacks).hasValue(1);
    }

    // ================================================================ 记录型 JDBC 桩

    private void assertDeadlineExpiredInWrite() {
        assertThat(stalledWrites).as("指定业务写确实执行一次，不能由路由预检错误提前结束").hasValue(1);
        assertThat(stallStartedBeforeDeadline).as("业务写在截止前开始").isTrue();
        assertThat(stallUntil.expired()).as("正是指定业务写等到请求截止").isTrue();
    }

    private void stall(String sql, int occurrence, Deadline until) {
        stallSql = sql;
        stallOccurrence = occurrence;
        stallUntil = until;
    }

    private Connection connection() throws SQLException {
        Connection c = mock(Connection.class);
        List<String> connectionWrites = new CopyOnWriteArrayList<>();
        when(c.getMetaData()).thenReturn(metadata);
        AtomicBoolean autoCommit = new AtomicBoolean(true);
        AtomicInteger isolation = new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        when(c.getAutoCommit()).thenAnswer(ignored -> autoCommit.get());
        doAnswer(call -> {
            autoCommit.set(call.getArgument(0));
            return null;
        }).when(c).setAutoCommit(anyBoolean());
        when(c.getTransactionIsolation()).thenAnswer(ignored -> isolation.get());
        doAnswer(call -> {
            isolation.set(call.getArgument(0));
            return null;
        }).when(c).setTransactionIsolation(anyInt());
        doAnswer(ignored -> {
            if (capacityOnly(connectionWrites)) capacityCommits.incrementAndGet();
            else commits.incrementAndGet();
            return null;
        }).when(c).commit();
        doAnswer(ignored -> {
            if (capacityOnly(connectionWrites)) capacityRollbacks.incrementAndGet();
            else rollbacks.incrementAndGet();
            return null;
        }).when(c).rollback();
        when(c.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0), connectionWrites));
        return c;
    }

    private static boolean capacityOnly(List<String> connectionWrites) {
        return !connectionWrites.isEmpty() && connectionWrites.stream().allMatch(JdbcFriendStore.ENSURE_CAPACITY::equals);
    }

    private PreparedStatement statement(String sql, List<String> connectionWrites) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenAnswer(ignored -> {
            queries.add(sql);
            return resultSet(queryRows.getOrDefault(sql, List.of()));
        });
        when(ps.executeUpdate()).thenAnswer(ignored -> {
            connectionWrites.add(sql);
            return update(sql);
        });
        return ps;
    }

    private int update(String sql) {
        int occurrence = updateCounts.computeIfAbsent(sql, k -> new AtomicInteger()).incrementAndGet();
        updates.add(sql);
        Deadline until = stallUntil;
        if (sql.equals(stallSql) && occurrence == stallOccurrence) {
            stalledWrites.incrementAndGet();
            stallStartedBeforeDeadline.set(!until.expired());
            // 模拟语句在锁等待里跑过请求截止：返回时截止一定已过
            while (!until.expired()) {
                LockSupport.parkNanos(Math.max(1, until.remainingNanos()));
            }
        }
        return 1;
    }

    private static ResultSet resultSet(List<Object[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(ignored -> cursor.incrementAndGet() < rows.size());
        when(rs.getObject(anyInt())).thenAnswer(call -> rows.get(cursor.get())[call.<Integer>getArgument(0) - 1]);
        when(rs.getString(anyInt())).thenAnswer(call -> (String) rows.get(cursor.get())[call.<Integer>getArgument(0) - 1]);
        when(rs.getLong(anyInt())).thenAnswer(call -> ((Number) rows.get(cursor.get())[call.<Integer>getArgument(0) - 1]).longValue());
        when(rs.getInt(anyInt())).thenAnswer(call -> ((Number) rows.get(cursor.get())[call.<Integer>getArgument(0) - 1]).intValue());
        return rs;
    }
}
