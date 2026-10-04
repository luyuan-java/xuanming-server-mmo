package com.game.friend.store;

import com.game.friend.support.Deadline;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FriendStore} 的 MySQL 实现（语句与锁序逐条对齐 mmorpg friend_repo.go / block_repo.go，编号见 friend-spec.md §1.5）。
 *
 * <p><b>守卫事务骨架</b>（Add / Accept / Remove / Block 共用，{@link #guarded}）：
 * <ol>
 *   <li>事务外、自动提交：按玩家号升序给双方补容量行（{@link #ensureCapacityRows}：权威边数 COUNT + INSERT ODKU，绝不猜 0；
 *       遇 1213 整对重跑至多 3 遍）——放进事务会与别的事务互持新插行、抢同一接收者的行，形成插入意向锁死锁；</li>
 *   <li>READ COMMITTED 事务里第一把锁：按升序 {@code SELECT friend_count ... FOR UPDATE} 锁双方容量行（守卫）。缺行（被回收）
 *       绝不当 0 继续：回滚、回到第 1 步重补，至多 3 遍，用尽 fail-closed；</li>
 *   <li>守卫之后只用完整主键的等值点查 / 点更新（不用 OR / IN / 前缀范围：真库上会扫二级索引、锁到别的玩家对上）；</li>
 *   <li>业务拒绝回滚、成功提交；体内任何错误原样上抛、不重试（一次 1213 就是一次 1003）。没走到提交 / 回滚的事务（含 {@link Error}）
 *       在还原自动提交<b>之前</b>回滚——把自动提交切回 true 会隐式提交一个半截的事务。</li>
 * </ol>
 * 「任何会让 pending 计数增加的写路径都必须先持有对应玩家的守卫行」——出站 / 入站计数因此可以用普通读（RC 下每条语句新快照）。
 *
 * <p><b>请求预算</b>（spec §7.3）：每条语句的查询超时 = min(配置上限, 剩余预算向上取整到秒)；预算用完不再取连接、不再发语句、
 * 不再重试，守卫事务在提交前再查一次（过了就回滚）——gate 已经判超时、客户端看到失败的请求，不会在之后落库（否则重试撞「已申请」）。
 *
 * <p>玩家号按无符号 64 位：绑定参数用 {@link #uid}，读回用 {@link #readUid}，排序用 {@link Long#compareUnsigned}。
 * 连接池须是会话级 READ COMMITTED（自动提交语句不经事务，隔离级别来自会话），{@code innodb_lock_wait_timeout} 设短。
 * 线程安全；全部方法阻塞。
 */
public final class JdbcFriendStore implements FriendStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFriendStore.class);

    static final int STATUS_PENDING = 1;
    static final int STATUS_ACCEPTED = 2;
    static final int STATUS_REJECTED = 3;
    static final int CAPACITY_GUARD_MAX_ATTEMPTS = 3;
    static final int ENSURE_DEADLOCK_MAX_ATTEMPTS = 3;
    static final int MYSQL_DEADLOCK = 1213;

    // S1 / S2：补容量行（事务外）
    static final String COUNT_FRIENDS = "SELECT COUNT(*) FROM friend WHERE player_id = ?";
    static final String ENSURE_CAPACITY = "INSERT INTO friend_capacity (player_id, friend_count, created_ms) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE player_id = player_id";
    // S3：守卫
    static final String LOCK_CAPACITY = "SELECT friend_count FROM friend_capacity WHERE player_id = ? FOR UPDATE";
    // S4 / S5 / S6：守卫之后的主键点锁
    static final String LOCK_BLOCK = "SELECT 1 FROM friend_block WHERE player_id = ? AND blocked_player_id = ? FOR UPDATE";
    static final String LOCK_EDGE = "SELECT 1 FROM friend WHERE player_id = ? AND friend_player_id = ? FOR UPDATE";
    static final String LOCK_REQUEST = "SELECT status FROM friend_request WHERE from_player_id = ? AND to_player_id = ? FOR UPDATE";
    // S7 / S8：出站 / 入站待处理计数（事务内普通读）
    static final String COUNT_OUTGOING = "SELECT COUNT(*) FROM friend_request WHERE from_player_id = ? AND status = ?";
    static final String COUNT_INCOMING = "SELECT COUNT(*) FROM friend_request WHERE to_player_id = ? AND status = ?";
    // S9：申请 upsert（VALUES() 为 TiDB 可移植）
    static final String UPSERT_REQUEST = "INSERT INTO friend_request (from_player_id, to_player_id, request_time_ms, status, updated_ms)"
            + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE status = VALUES(status),"
            + " request_time_ms = VALUES(request_time_ms), updated_ms = VALUES(updated_ms)";
    // S10：同意的事务外前置
    static final String PENDING_EXISTS = "SELECT 1 FROM friend_request WHERE from_player_id = ? AND to_player_id = ? AND status = ?";
    // S11 / S12 / S15 / S22：带 updated_ms 的状态 CAS
    static final String UPDATE_REQUEST_STATUS = "UPDATE friend_request SET status = ?, updated_ms = ?"
            + " WHERE from_player_id = ? AND to_player_id = ? AND status = ?";
    // S13 / S14：插边与加计数
    static final String INSERT_EDGE = "INSERT IGNORE INTO friend (player_id, friend_player_id, since_ms) VALUES (?, ?, ?)";
    static final String INCREMENT_COUNT = "UPDATE friend_capacity SET friend_count = friend_count + 1 WHERE player_id = ?";
    // S16：删除的事务外前置（普通读，没有锁，允许 OR）
    static final String EDGE_EXISTS_EITHER = "SELECT 1 FROM friend WHERE (player_id = ? AND friend_player_id = ?)"
            + " OR (player_id = ? AND friend_player_id = ?) LIMIT 1";
    // S17 / S18：删边与减计数（减计数刷新 created_ms：防「陈旧 ensure 把回收后的计数写大 1」）
    static final String DELETE_EDGE = "DELETE FROM friend WHERE player_id = ? AND friend_player_id = ?";
    static final String DECREMENT_COUNT = "UPDATE friend_capacity SET friend_count = friend_count - 1, created_ms = ?"
            + " WHERE player_id = ? AND friend_count > 0";
    // S19 / S20 / S21 / S23：黑名单
    static final String BLOCK_QUOTA = "SELECT COUNT(*), COUNT(CASE WHEN blocked_player_id = ? THEN 1 END)"
            + " FROM friend_block WHERE player_id = ?";
    static final String COUNT_BLOCKS = "SELECT COUNT(*) FROM friend_block WHERE player_id = ?";
    static final String INSERT_BLOCK = "INSERT INTO friend_block (player_id, blocked_player_id, since_ms) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE player_id = player_id";
    static final String DELETE_BLOCK = "DELETE FROM friend_block WHERE player_id = ? AND blocked_player_id = ?";
    // S24 / S25 / S26：列表读（ORDER BY 与索引同序：不引入 filesort，截断结果确定）
    static final String LIST_FRIENDS = "SELECT friend_player_id, since_ms FROM friend WHERE player_id = ?"
            + " ORDER BY friend_player_id LIMIT ?";
    static final String LIST_PENDING = "SELECT from_player_id, to_player_id, request_time_ms, status FROM friend_request"
            + " WHERE to_player_id = ? AND status = ? ORDER BY from_player_id LIMIT ?";
    static final String LIST_BLOCKS = "SELECT blocked_player_id, since_ms FROM friend_block WHERE player_id = ?"
            + " ORDER BY blocked_player_id LIMIT ?";

    /** 事件回调（指标）：守卫缺行重试、ensure 死锁重试、计数下溢被拦。 */
    public interface Events {
        void guardRetry(String kind);

        void countUnderflow();

        Events NONE = new Events() {
            @Override
            public void guardRetry(String kind) {
            }

            @Override
            public void countUnderflow() {
            }
        };
    }

    private final DataSource dataSource;
    private final FriendLimits limits;
    private final LongSupplier nowMs;
    private final int queryTimeoutCapSeconds;
    private final Events events;
    /** 测试缝：补完容量行、开守卫事务之前调用（模拟回收在这个窗口删掉容量行）。 */
    private volatile Runnable afterEnsure = () -> { };

    /**
     * @param queryTimeoutCapSeconds 每条语句查询超时的上限（秒）；实际取它与剩余预算（向上取整）的较小者
     */
    public JdbcFriendStore(DataSource dataSource, FriendLimits limits, LongSupplier nowMs, int queryTimeoutCapSeconds,
                           Events events) {
        this.dataSource = dataSource;
        this.limits = limits;
        this.nowMs = nowMs;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
        this.events = events;
    }

    void afterEnsureForTest(Runnable hook) {
        this.afterEnsure = hook;
    }

    /** 一次请求里的一条连接：语句都带着请求预算。 */
    private record Db(Connection c, Deadline deadline) {
    }

    // ================================================================ 写路径

    @Override
    public AddResult addRequest(long from, long to, Deadline deadline) {
        AddResult rejected = guarded(from, to, deadline, (db, counts, now) -> {
            if (blockedEitherWay(db, from, to)) {
                return AddResult.BLOCKED;
            }
            if (exists(db, LOCK_EDGE, from, to) || exists(db, LOCK_EDGE, to, from)) {
                return AddResult.ALREADY_FRIENDS;
            }
            Integer status = lockedRequestStatus(db, from, to);
            if (status != null && status == STATUS_PENDING) {
                // 不刷新时间：防反复点击把自己顶到对方列表最前面
                return AddResult.ALREADY_SENT;
            }
            if (limits.maxPendingRequests() > 0 && count(db, COUNT_OUTGOING, uid(from), STATUS_PENDING)
                    >= limits.maxPendingRequests()) {
                return AddResult.TOO_MANY_PENDING;
            }
            if (limits.maxIncomingRequests() > 0 && count(db, COUNT_INCOMING, uid(to), STATUS_PENDING)
                    >= limits.maxIncomingRequests()) {
                return AddResult.TARGET_INBOX_FULL;
            }
            if (limits.maxFriends() > 0) {
                if (counts.get(from) >= limits.maxFriends()) {
                    return AddResult.SENDER_FULL;
                }
                if (counts.get(to) >= limits.maxFriends()) {
                    return AddResult.RECEIVER_FULL;
                }
            }
            update(db, UPSERT_REQUEST, uid(from), uid(to), now, STATUS_PENDING, now);
            return null;
        });
        return rejected == null ? AddResult.OK : rejected;
    }

    @Override
    public AcceptResult accept(long from, long me, Deadline deadline) {
        checkPair(from, me);
        // 事务外前置：没有这条待处理就不补容量行（防无申请的调用制造容量空行）
        if (!autocommitExists(deadline, PENDING_EXISTS, uid(from), uid(me), STATUS_PENDING)) {
            return AcceptResult.NO_PENDING;
        }
        AcceptResult rejected = guarded(from, me, deadline, (db, counts, now) -> {
            // 没有这一步，「拉黑删边」与「同意插边」可以交错出「既是好友又被拉黑」
            if (blockedEitherWay(db, from, me)) {
                return AcceptResult.BLOCKED;
            }
            Integer status = lockedRequestStatus(db, from, me);
            if (status == null || status != STATUS_PENDING) {
                return AcceptResult.NO_PENDING;
            }
            if (limits.maxFriends() > 0) {
                if (counts.get(from) >= limits.maxFriends()) {
                    return AcceptResult.SENDER_FULL;
                }
                if (counts.get(me) >= limits.maxFriends()) {
                    return AcceptResult.ACCEPTOR_FULL;
                }
            }
            if (update(db, UPDATE_REQUEST_STATUS, STATUS_ACCEPTED, now, uid(from), uid(me), STATUS_PENDING) != 1) {
                return AcceptResult.NO_PENDING;
            }
            // 反向收敛：对方也向我申请过时一并置已同意（0 行是常态），否则 from 的收件箱里留孤儿 pending
            update(db, UPDATE_REQUEST_STATUS, STATUS_ACCEPTED, now, uid(me), uid(from), STATUS_PENDING);
            insertEdge(db, from, me, now);
            insertEdge(db, me, from, now);
            return null;
        });
        return rejected == null ? AcceptResult.OK : rejected;
    }

    @Override
    public RejectResult reject(long from, long me, Deadline deadline) {
        checkPair(from, me);
        int rows = autocommitUpdate(deadline, UPDATE_REQUEST_STATUS, STATUS_REJECTED, nowMs.getAsLong(), uid(from), uid(me),
                STATUS_PENDING);
        return rows == 0 ? RejectResult.NO_PENDING : RejectResult.OK;
    }

    @Override
    public RemoveResult remove(long me, long target, Deadline deadline) {
        if (me == target) {
            return RemoveResult.NOT_FRIENDS;
        }
        checkPair(me, target);
        // 事务外前置（还没有守卫，不许锁定读）：漏判只可能发生在边恰在此刻由 Accept 提交，等价于删除发生在成为好友之前
        if (!autocommitExists(deadline, EDGE_EXISTS_EITHER, uid(me), uid(target), uid(target), uid(me))) {
            return RemoveResult.NOT_FRIENDS;
        }
        guarded(me, target, deadline, (db, counts, now) -> {
            deleteFriendEdges(db, me, target, now);
            return null;
        });
        return RemoveResult.REMOVED;
    }

    @Override
    public BlockResult block(long me, long target, Deadline deadline) {
        checkPair(me, target);
        if (limits.maxBlocks() > 0) {
            // 事务外快速判满（不补容量行）；已拉黑过的目标不按名额拒：重复拉黑幂等成功，还要靠下面收敛残留
            long[] quota = autocommitBlockQuota(deadline, target, me);
            if (quota[1] == 0 && quota[0] >= limits.maxBlocks()) {
                return BlockResult.BLOCK_LIST_FULL;
            }
        }
        BlockResult rejected = guarded(me, target, deadline, (db, counts, now) -> {
            // 单向判定：「A 拉黑了 B」不能让「B 拉黑 A」被当成重复而不写
            boolean already = exists(db, LOCK_BLOCK, me, target);
            if (!already) {
                if (limits.maxBlocks() > 0 && count(db, COUNT_BLOCKS, uid(me)) >= limits.maxBlocks()) {
                    return BlockResult.BLOCK_LIST_FULL;
                }
                // ODKU 而非 INSERT IGNORE：这一行可能是 Unblock 刚删、未 purge 的记录，S→X 升级会与排队的 Unblock 互等成 1213
                update(db, INSERT_BLOCK, uid(me), uid(target), now);
            }
            // 已拉黑也照样执行：收敛历史残留
            deleteFriendEdges(db, me, target, now);
            // 两个方向各一条，不许合成 OR（会走 status 前缀扫全服待处理行）
            update(db, UPDATE_REQUEST_STATUS, STATUS_REJECTED, now, uid(me), uid(target), STATUS_PENDING);
            update(db, UPDATE_REQUEST_STATUS, STATUS_REJECTED, now, uid(target), uid(me), STATUS_PENDING);
            return null;
        });
        return rejected == null ? BlockResult.OK : rejected;
    }

    @Override
    public void unblock(long me, long target, Deadline deadline) {
        checkPair(me, target);
        autocommitUpdate(deadline, DELETE_BLOCK, uid(me), uid(target));
    }

    // ================================================================ 读

    @Override
    public List<FriendEdge> friends(long me, int limit, Deadline deadline) {
        return query(deadline, LIST_FRIENDS, rs -> new FriendEdge(readUid(rs, 1), rs.getLong(2)), uid(me), limit);
    }

    @Override
    public List<PendingRequest> pendingRequests(long me, int limit, Deadline deadline) {
        return query(deadline, LIST_PENDING,
                rs -> new PendingRequest(readUid(rs, 1), readUid(rs, 2), rs.getLong(3), rs.getInt(4)),
                uid(me), STATUS_PENDING, limit);
    }

    @Override
    public List<BlockEdge> blocks(long me, int limit, Deadline deadline) {
        return query(deadline, LIST_BLOCKS, rs -> new BlockEdge(readUid(rs, 1), rs.getLong(2)), uid(me), limit);
    }

    // ================================================================ 守卫事务骨架

    @FunctionalInterface
    private interface GuardedBody<R> {
        /** @return 业务拒绝（回滚）；null = 成功（提交） */
        R run(Db db, Map<Long, Long> counts, long nowMs) throws SQLException;
    }

    /** 守卫缺行（多半是回收竞态）：回滚、回到事务外重补再跑。 */
    private static final class CapacityRowMissing extends Exception {
        CapacityRowMissing(long playerId) {
            super("friend_capacity 缺行 player=" + Long.toUnsignedString(playerId), null, false, false);
        }
    }

    private <R> R guarded(long a, long b, Deadline deadline, GuardedBody<R> body) {
        checkPair(a, b);
        long[] ids = ascending(a, b);
        for (int attempt = 1; ; attempt++) {
            requireBudget(deadline, "补容量行");
            ensureCapacityRows(ids, deadline);
            afterEnsure.run();
            requireBudget(deadline, "开守卫事务");
            try (Connection c = dataSource.getConnection()) {
                boolean autoCommit = c.getAutoCommit();
                int isolation = c.getTransactionIsolation();
                boolean finished = false;
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                try {
                    Db db = new Db(c, deadline);
                    Map<Long, Long> counts = lockCapacityRows(db, ids);
                    R rejected = body.run(db, counts, nowMs.getAsLong());
                    if (rejected != null) {
                        c.rollback();
                    } else {
                        // 过了预算就不提交：gate 已经判超时，客户端看到的是失败
                        requireBudget(deadline, "提交");
                        c.commit();
                    }
                    finished = true;
                    return rejected;
                } catch (CapacityRowMissing e) {
                    c.rollback();
                    finished = true;
                    if (attempt >= CAPACITY_GUARD_MAX_ATTEMPTS) {
                        throw new FriendStoreException("容量守卫缺行，重试用尽（fail-closed）: " + e.getMessage());
                    }
                    events.guardRetry("missing_row");
                    log.warn("容量守卫缺行（多半是回收竞态），重补后重跑 第 {} 遍: {}", attempt, e.getMessage());
                } finally {
                    // 没走到提交 / 回滚（异常、Error）：先回滚再还原自动提交——切回 true 会隐式提交半截的事务
                    if (!finished) {
                        rollbackQuietly(c);
                    }
                    restore(c, autoCommit, isolation);
                }
            } catch (SQLException e) {
                throw new FriendStoreException("好友写事务失败", e);
            }
        }
    }

    /** 守卫：升序逐个 {@code SELECT ... FOR UPDATE}；遇到第一个缺行就返回（后面的不再锁），缺行绝不当 0。 */
    private Map<Long, Long> lockCapacityRows(Db db, long[] ids) throws SQLException, CapacityRowMissing {
        Map<Long, Long> counts = new HashMap<>();
        for (long id : ids) {
            try (PreparedStatement ps = prepare(db, LOCK_CAPACITY, uid(id)); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new CapacityRowMissing(id);
                }
                counts.put(id, rs.getLong(1));
            }
        }
        return counts;
    }

    /**
     * 事务外补容量行：每个玩家「COUNT 权威边数 + INSERT ODKU」两条各自自动提交；遇 1213 把这一对整个重跑（COUNT 必须一起重跑，
     * 否则写下去的是陈旧边数），至多 3 遍，每遍之前查预算。ODKU 直接取 X 锁（INSERT IGNORE 的重复键检查取 S 锁，两个排队的 S 升 X 会互等成环）。
     */
    private void ensureCapacityRows(long[] ids, Deadline deadline) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(true);
            try {
                Db db = new Db(c, deadline);
                for (long id : ids) {
                    for (int attempt = 1; ; attempt++) {
                        requireBudget(deadline, "补容量行");
                        try {
                            long edges = count(db, COUNT_FRIENDS, uid(id));
                            update(db, ENSURE_CAPACITY, uid(id), edges, nowMs.getAsLong());
                            break;
                        } catch (SQLException e) {
                            if (!isDeadlock(e) || attempt >= ENSURE_DEADLOCK_MAX_ATTEMPTS) {
                                throw e;
                            }
                            events.guardRetry("ensure_deadlock");
                            log.warn("补容量行遇死锁（1213），整对重跑 第 {} 遍 player={}", attempt, Long.toUnsignedString(id));
                        }
                    }
                }
            } finally {
                restore(c, autoCommit, isolation);
            }
        } catch (SQLException e) {
            throw new FriendStoreException("补容量行失败", e);
        }
    }

    private boolean blockedEitherWay(Db db, long a, long b) throws SQLException {
        return exists(db, LOCK_BLOCK, a, b) || exists(db, LOCK_BLOCK, b, a);
    }

    private Integer lockedRequestStatus(Db db, long from, long to) throws SQLException {
        try (PreparedStatement ps = prepare(db, LOCK_REQUEST, uid(from), uid(to)); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private void insertEdge(Db db, long a, long b, long now) throws SQLException {
        int inserted = update(db, INSERT_EDGE, uid(a), uid(b), now);
        if (inserted > 1) {
            throw new SQLException("INSERT IGNORE friend 影响了 " + inserted + " 行");
        }
        if (inserted == 1) {
            // SQL 里没有上限条件：完全依赖守卫与前面的判满
            update(db, INCREMENT_COUNT, uid(a));
        }
    }

    /** 双向删边，真删到才减计数；减计数 0 行（下溢）只记 ERROR 不回滚：边已经删了，回滚会把一次正确的删好友变成失败。 */
    private void deleteFriendEdges(Db db, long a, long b, long now) throws SQLException {
        for (long[] pair : new long[][] {{a, b}, {b, a}}) {
            if (update(db, DELETE_EDGE, uid(pair[0]), uid(pair[1])) == 1
                    && update(db, DECREMENT_COUNT, now, uid(pair[0])) == 0) {
                events.countUnderflow();
                log.error("friend_count 下溢被拦截（计数与边数不一致） player={}", Long.toUnsignedString(pair[0]));
            }
        }
    }

    // ================================================================ JDBC 小工具

    private boolean exists(Db db, String sql, long a, long b) throws SQLException {
        try (PreparedStatement ps = prepare(db, sql, uid(a), uid(b)); ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private long count(Db db, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(db, sql, args); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int update(Db db, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(db, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private boolean autocommitExists(Deadline deadline, String sql, Object... args) {
        requireBudget(deadline, "读");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = prepare(new Db(c, deadline), sql, args);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            throw new FriendStoreException("好友读失败", e);
        }
    }

    private int autocommitUpdate(Deadline deadline, String sql, Object... args) {
        requireBudget(deadline, "写");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = prepare(new Db(c, deadline), sql, args)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new FriendStoreException("好友写失败", e);
        }
    }

    /** S19：{总数, 是否已拉黑 target}。 */
    private long[] autocommitBlockQuota(Deadline deadline, long target, long me) {
        requireBudget(deadline, "读黑名单名额");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = prepare(new Db(c, deadline), BLOCK_QUOTA, uid(target), uid(me));
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new long[] {rs.getLong(1), rs.getLong(2)};
        } catch (SQLException e) {
            throw new FriendStoreException("黑名单名额读失败", e);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(Deadline deadline, String sql, RowMapper<T> mapper, Object... args) {
        requireBudget(deadline, "列表读");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = prepare(new Db(c, deadline), sql, args);
             ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new FriendStoreException("好友列表读失败", e);
        }
    }

    /** 每条语句：预算用完就不发；查询超时 = min(上限, 剩余预算向上取整到秒)。 */
    private PreparedStatement prepare(Db db, String sql, Object... args) throws SQLException {
        long remaining = db.deadline().remainingMillis();
        if (remaining <= 0) {
            throw new SQLException("超过请求预算，不再执行: " + sql.substring(0, Math.min(40, sql.length())));
        }
        int timeout = (int) Math.max(1, (remaining + 999) / 1000);
        if (queryTimeoutCapSeconds > 0) {
            timeout = Math.min(timeout, queryTimeoutCapSeconds);
        }
        PreparedStatement ps = db.c().prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    private static void requireBudget(Deadline deadline, String what) {
        if (deadline.expired()) {
            throw new FriendStoreException("超过请求预算（" + what + "之前），不再继续");
        }
    }

    private static void restore(Connection c, boolean autoCommit, int isolation) {
        try {
            if (c.getAutoCommit() != autoCommit) {
                c.setAutoCommit(autoCommit);
            }
            if (c.getTransactionIsolation() != isolation) {
                c.setTransactionIsolation(isolation);
            }
        } catch (SQLException e) {
            log.warn("还原连接状态失败: {}", e.toString());
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败: {}", e.toString());
        }
    }

    /** 只认错误号 1213，沿 cause 链找（不做文本匹配）。 */
    static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) {
                return true;
            }
        }
        return false;
    }

    /** 0 与「自己对自己」由上层挡住；到这里就是程序错误（上层定性 1003 并记 ERROR）。 */
    private static void checkPair(long a, long b) {
        if (a == 0 || b == 0 || a == b) {
            throw new IllegalArgumentException("非法玩家对 " + Long.toUnsignedString(a) + "," + Long.toUnsignedString(b));
        }
    }

    /** 两个玩家号按无符号升序、去重。 */
    static long[] ascending(long a, long b) {
        if (a == b) {
            return new long[] {a};
        }
        return Long.compareUnsigned(a, b) < 0 ? new long[] {a, b} : new long[] {b, a};
    }

    /** 无符号 64 位玩家号的绑定值：大于 Long.MAX_VALUE 的用 BigInteger（BIGINT UNSIGNED 不收负数）。 */
    static Object uid(long id) {
        return id >= 0 ? (Object) id : new BigInteger(Long.toUnsignedString(id));
    }

    /** 读 BIGINT UNSIGNED 列为无符号 64 位玩家号（驱动对超过 Long.MAX_VALUE 的值给 BigInteger）。 */
    static long readUid(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SQLException("列 " + column + " 不是数字: " + value);
    }
}
