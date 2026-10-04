package com.game.common.player;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家展示资料（name / level / class_id / gender / appearance_id / home zone），读 {@code xm_java.player}。
 *
 * <p>好友、组队共用（与基线的有意差异见两者 spec 的 D4）：基线读 Redis {@code PlayerAllData} 缓存再向 data_service 补名字与 home zone；
 * Java 版没有这两套组件，player 表字段齐全（name 恒非空、zone_id 即 home zone），等级到存盘才更新（在线玩家可能滞后）。
 *
 * <p>两种读法：{@link #load}（展示用，失败语义照基线：每批 64 人，某批失败或预算用完 → 打 ERROR、停在这一批、返回已读到的部分，不回 tip）；
 * {@link #loadStrict}（读失败就是依赖故障，抛 {@link DependencyException}）。找不到的行不在结果里。
 * 每条语句的查询超时取请求预算的剩余（向上取整到秒）。阻塞 JDBC，只在工作线程上调用。
 */
public final class PlayerProfiles {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfiles.class);

    /** 补资料的每批人数（基线 friend_profiles.go 按 64 人一批读缓存与 data_service）。 */
    public static final int BATCH = 64;

    /** 一个玩家的展示资料。数值字段是 uint32 的位模式（列是 INT UNSIGNED）。 */
    public record Profile(long playerId, String name, int level, int classId, int gender, String appearanceId, int zoneId) {
    }

    /**
     * 按等待上限取连接（连接池满 / 连不上时最多等这么久）。连接池的固定 max-wait 不认请求预算：一次请求碰两次 MySQL 就可能拖过
     * gate 的 Dubbo 超时，所以用池子支持的「本次最多等 N 毫秒」（Druid {@code getConnection(long)}）。
     */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection get(long maxWaitMillis) throws SQLException;
    }

    private final ConnectionSource connections;
    private final int queryTimeoutCapSeconds;

    /** 取连接不受预算约束（只用池子自己的 max-wait）；能按次限等的池子用 {@link #PlayerProfiles(ConnectionSource, int)}。 */
    public PlayerProfiles(DataSource dataSource, int queryTimeoutCapSeconds) {
        this(maxWait -> dataSource.getConnection(), queryTimeoutCapSeconds);
    }

    public PlayerProfiles(ConnectionSource connections, int queryTimeoutCapSeconds) {
        this.connections = connections;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
    }

    /** 批量读资料；某批失败或预算用完时返回已读到的部分（记 ERROR），永不抛。 */
    public Map<Long, Profile> load(List<Long> playerIds, Deadline deadline) {
        Map<Long, Profile> out = new HashMap<>();
        for (int from = 0; from < playerIds.size(); from += BATCH) {
            List<Long> batch = playerIds.subList(from, Math.min(playerIds.size(), from + BATCH));
            try {
                readBatch(batch, deadline, out);
            } catch (SQLException | RuntimeException e) {
                log.error("玩家展示资料补全失败（第 {} 批起未填）: {}", from / BATCH + 1, e.toString());
                break;
            }
        }
        return out;
    }

    /** 批量读资料；任何一批失败或预算用完都抛 {@link DependencyException}。 */
    public Map<Long, Profile> loadStrict(List<Long> playerIds, Deadline deadline) {
        Map<Long, Profile> out = new HashMap<>();
        for (int from = 0; from < playerIds.size(); from += BATCH) {
            try {
                readBatch(playerIds.subList(from, Math.min(playerIds.size(), from + BATCH)), deadline, out);
            } catch (SQLException e) {
                throw new DependencyException("读玩家资料失败", e);
            }
        }
        return out;
    }

    /**
     * 一条 IN 语句读完（在线目录每轮一次：同基线每轮一次批查，不按 64 拆成多条）；调用方保证 id 数有界（在线目录单批 ≤ 1024）。
     * 读失败或预算用完抛 {@link DependencyException}。
     */
    public Map<Long, Profile> loadStrictOnce(List<Long> playerIds, Deadline deadline) {
        Map<Long, Profile> out = new HashMap<>();
        if (playerIds.isEmpty()) {
            return out;
        }
        try {
            readBatch(playerIds, deadline, out);
        } catch (SQLException e) {
            throw new DependencyException("读玩家资料失败", e);
        }
        return out;
    }

    /**
     * 一条 IN 语句。三处都受剩余预算约束：取连接最多等剩余时长；语句带 {@code MAX_EXECUTION_TIME} 提示（服务端按毫秒中止 SELECT）；
     * JDBC 查询超时（只能按秒）兜底网络停顿。
     */
    private void readBatch(List<Long> batch, Deadline deadline, Map<Long, Profile> out) throws SQLException {
        long remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            throw new DependencyException("读玩家资料超过请求预算");
        }
        try (Connection c = connections.get(remaining)) {
            remaining = deadline.remainingMillis();
            if (remaining <= 0) {
                throw new DependencyException("读玩家资料超过请求预算（等连接用完了预算）");
            }
            int timeout = (int) Math.max(1, (remaining + 999) / 1000);
            if (queryTimeoutCapSeconds > 0) {
                timeout = Math.min(timeout, queryTimeoutCapSeconds);
            }
            String sql = "SELECT /*+ MAX_EXECUTION_TIME(" + remaining + ") */ player_id, name, level, class_id, gender,"
                    + " appearance_id, zone_id FROM player WHERE player_id IN ("
                    + String.join(",", Collections.nCopies(batch.size(), "?")) + ")";
            query(c, sql, timeout, batch, out);
        }
    }

    private static void query(Connection c, String sql, int timeoutSeconds, List<Long> batch, Map<Long, Profile> out)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            for (int i = 0; i < batch.size(); i++) {
                long id = batch.get(i);
                ps.setObject(i + 1, id >= 0 ? (Object) id : new BigInteger(Long.toUnsignedString(id)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = unsigned64(rs.getObject(1));
                    out.put(id, new Profile(id, rs.getString(2), (int) rs.getLong(3), (int) rs.getLong(4),
                            (int) rs.getLong(5), rs.getString(6), (int) rs.getLong(7)));
                }
            }
        }
    }

    private static long unsigned64(Object value) throws SQLException {
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new SQLException("player_id 不是数字: " + value);
    }
}
