package com.game.match.rating;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.RatingOutcome;
import com.game.match.metrics.MetricLabels;
import com.game.pbmysql.PbMysql;
import com.game.proto.contracts.kafka.BattleResultEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评分的 MySQL 存储（match-spec §5.2；替代基线 {@code rating.go:56-89}、{@code :374-506} 的 Redis 两层幂等 Lua，有意差异 M18）。
 * 两张表（{@link MatchRatingTables}）：{@code match_rating} 每人一行，{@code match_rating_applied} 每局计分对局一行。
 *
 * <p><b>入账 {@link #apply}：一局一笔事务</b>（READ COMMITTED，连接串 {@code innodb_lock_wait_timeout=1}）：
 * <ol>
 *   <li>不计分的（{@link EloRules#judge}：模式、结局、队伍形态）直接返回 {@link Outcome#IGNORED}，<b>不碰库</b>——活动局的结果会被 battle
 *       每 10 s 原字节重发、最多 30 次，每条都必须走这里；</li>
 *   <li>{@code INSERT match_rating_applied}：撞主键 = 这一局已入账 → {@link Outcome#DUPLICATE}，回滚，什么都不写；</li>
 *   <li>按<b>无符号 player_id 升序</b>逐人 {@code INSERT … ON DUPLICATE KEY UPDATE player_id = player_id} 补出缺的行（150000）。
 *       不用 {@code INSERT IGNORE}（MySQL 8.4 上会与并发删除死锁，且会把真错误降级成 warning）；</li>
 *   <li>同样升序逐人 {@code SELECT … FOR UPDATE} 读赛前评分（完整主键点查）；</li>
 *   <li>{@link EloRules#settle} 算每人的赛后评分；同一玩家在事件里出现多次只入账一次、取首次出现的队别；</li>
 *   <li>逐人 {@code UPDATE rating_centi, games = games + 1, updated_at_ms}，把 Δ_A 回填到第 2 步自己插入的那行标记，提交；</li>
 *   <li>死锁（1213）或锁等待超时（1205）→ 整笔重跑，至多 {@value #MAX_ATTEMPTS} 次；仍失败、或别的 SQL 错误 → 抛 {@link StoreException}，
 *       由消费者按「数据错误跳过 / 可恢复故障暂停重试」处置。</li>
 * </ol>
 * 效果：一局要么全员落账、要么一个都不落账；同一玩家的两局被并发入账时由行锁串行化，第二局按更新后的分算。
 *
 * <p><b>锁序</b>：先入账标记、再按升序锁评分行。第 6 步回填的是本事务自己插入、已经持有排他锁的那一行标记，不取新锁，不破坏这个次序。
 *
 * <p><b>读</b>（{@link #find}）：主键 / IN 查询，语句超时 {@value #READ_TIMEOUT_SECONDS} s，取连接至多等 {@value #READ_CONNECTION_WAIT_MS} ms；
 * 失败抛 {@link StoreException}（回落缺省分是 {@link JdbcRatingReader} 的事，dev 管理口则如实报错）。
 *
 * <p>线程：全部方法阻塞（JDBC）。{@link #apply} 只在评分消费线程上调；读在 {@code match-db} 线程池或管理端口的 Tomcat 线程上调。
 * <b>不得在虚拟线程上直接调</b>（JDK 21 下驱动内部的 {@code synchronized} 会钉住载体线程）。线程安全（无共享可变状态）。
 */
public final class RatingStore {

    private static final Logger log = LoggerFactory.getLogger(RatingStore.class);

    /** 死锁 / 锁等待超时的整笔重跑上限（含第一次）。 */
    static final int MAX_ATTEMPTS = 3;
    /** 入账事务里每条语句的查询超时（秒）；小于连接串的 socketTimeout 4 s。 */
    static final int APPLY_STATEMENT_TIMEOUT_SECONDS = 3;
    /** 入账取连接的等待上限。 */
    static final long APPLY_CONNECTION_WAIT_MS = 3_000;
    /** 读评分的语句超时（秒）。 */
    static final int READ_TIMEOUT_SECONDS = 1;
    /** 读评分取连接的等待上限。 */
    static final long READ_CONNECTION_WAIT_MS = 1_000;
    /** 一条 IN 查询最多带多少个玩家号（一局最多 10 人，远用不到）。 */
    static final int READ_CHUNK = 200;

    static final String SQL_INSERT_APPLIED =
            "INSERT INTO match_rating_applied (battle_id, match_mode, delta_a_centi, applied_at_ms) VALUES (?, ?, 0, ?)";
    static final String SQL_ENSURE_ROW =
            "INSERT INTO match_rating (player_id, rating_centi, games, updated_at_ms) VALUES (?, ?, 0, ?)"
                    + " ON DUPLICATE KEY UPDATE player_id = player_id";
    static final String SQL_LOCK_ROW = "SELECT rating_centi FROM match_rating WHERE player_id = ? FOR UPDATE";
    static final String SQL_UPDATE_ROW =
            "UPDATE match_rating SET rating_centi = ?, games = games + 1, updated_at_ms = ? WHERE player_id = ?";
    static final String SQL_SET_DELTA = "UPDATE match_rating_applied SET delta_a_centi = ? WHERE battle_id = ?";
    static final String SQL_FIND_PREFIX = "SELECT player_id, rating_centi, games FROM match_rating WHERE player_id IN (";
    static final String SQL_DELETE_APPLIED = "DELETE FROM match_rating_applied WHERE applied_at_ms < ? LIMIT ?";

    /** 一条对局结果的入账结局（终态；失败是抛异常）。 */
    public enum Outcome {
        /** 全员已更新评分。 */
        APPLIED,
        /** 这一局已经入过账（重复投递）。 */
        DUPLICATE,
        /** 不计分（PVE / 切磋 / 活动 / 结局或队伍形态不符）。 */
        IGNORED
    }

    /**
     * {@link #apply} 的结果。
     *
     * @param outcome      结局
     * @param ignored      不计分的原因；只有 {@link Outcome#IGNORED} 时非 null
     * @param deltaACenti  A 队每人的增量（centi）；只有 {@link Outcome#APPLIED} 时有意义
     * @param roundCapDraw 这一局是不是因「回合打满」被改按平局结算；只有 {@link Outcome#APPLIED} 时有意义
     */
    public record Result(Outcome outcome, EloRules.Ignore ignored, long deltaACenti, boolean roundCapDraw) {

        static Result ignored(EloRules.Ignore reason) {
            return new Result(Outcome.IGNORED, reason, 0, false);
        }

        static Result duplicate() {
            return new Result(Outcome.DUPLICATE, null, 0, false);
        }
    }

    /**
     * 一名玩家的评分行。
     *
     * @param ratingCenti 评分 × 100
     * @param games       已入账的计分局数
     */
    public record Rating(long playerId, long ratingCenti, long games) {

        /** 没有行的新号：1500.00、0 局。 */
        public static Rating fresh(long playerId) {
            return new Rating(playerId, RatingReader.DEFAULT_CENTI, 0);
        }
    }

    /** 按等待上限取连接（Druid 的 {@code getConnection(long)}；别的数据源退化为不限等）。 */
    @FunctionalInterface
    public interface Connections {
        Connection get(long maxWaitMillis) throws SQLException;
    }

    /** 评分库读写失败（cause 是 SQL 错误；分类见 {@link RatingSqlErrors}）。 */
    public static final class StoreException extends RuntimeException {

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 测试缝：重跑之间的等待。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Connections connections;
    private final IntUnaryOperator drawRoundCapFor;
    private final MatchMetrics metrics;
    private final LongSupplier clockMs;
    private final Sleeper sleeper;

    /**
     * @param connections     取连接
     * @param drawRoundCapFor 副本号 →「回合打满按平局」的阈值（0 = 不做这条判定；生产为 {@code props.rating()::drawRoundCapFor}）
     * @param clockMs         服务进程时钟（Unix 毫秒）：只写进 {@code updated_at_ms} / {@code applied_at_ms}
     */
    public RatingStore(Connections connections, IntUnaryOperator drawRoundCapFor, MatchMetrics metrics, LongSupplier clockMs) {
        this(connections, drawRoundCapFor, metrics, clockMs, Thread::sleep);
    }

    RatingStore(Connections connections, IntUnaryOperator drawRoundCapFor, MatchMetrics metrics, LongSupplier clockMs, Sleeper sleeper) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.drawRoundCapFor = Objects.requireNonNull(drawRoundCapFor, "drawRoundCapFor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** 数据源 → {@link Connections}：Druid 支持「本次最多等 N 毫秒」，其余数据源直接取。 */
    public static Connections connections(DataSource dataSource) {
        return dataSource instanceof DruidDataSource druid ? druid::getConnection : maxWait -> dataSource.getConnection();
    }

    // ================================================================ 入账

    /**
     * 消费一条对局结果并入账。返回即终态（指标 {@code xm_match_rating_updates_total{mode, outcome}} 已记）；
     * 失败抛 {@link StoreException}（已记 {@code outcome="error"}，库里没有留下这一局的任何东西），调用方重试是安全的。
     */
    public Result apply(BattleResultEvent event) {
        int mode = event.getMatchMode();
        EloRules.Verdict verdict = EloRules.judge(event, drawRoundCapFor);
        if (verdict instanceof EloRules.Verdict.Ignored ignored) {
            metrics.ratingUpdate(mode, RatingOutcome.IGNORED);
            if (ignored.reason() != EloRules.Ignore.MODE_NOT_RATED) {
                // 计分模式里出现不认识的结局 / 队伍形态值得看一眼；PVE、切磋、活动局是常态（活动局还会重发几十次），不打日志
                log.warn("[rating] 对局结果不计分 battle={} mode={} outcome={} teams={} 原因={}", battle(event), MetricLabels.mode(mode),
                        event.getOutcomeValue(), event.getTeamsCount(), ignored.reason());
            }
            return Result.ignored(ignored.reason());
        }
        EloRules.Verdict.Scored scored = (EloRules.Verdict.Scored) verdict;
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Result result = applyOnce(event, scored);
                if (result.outcome() == Outcome.DUPLICATE) {
                    metrics.ratingUpdate(mode, RatingOutcome.DUPLICATE);
                    log.info("[rating] 对局结果已入账，跳过重复投递 battle={}", battle(event));
                } else {
                    metrics.ratingUpdate(mode, RatingOutcome.APPLIED);
                    if (result.roundCapDraw()) {
                        metrics.ratingRoundCapDraw(mode);
                    }
                }
                return result;
            } catch (SQLException e) {
                last = e;
                if (!RatingSqlErrors.isRetryable(e)) {
                    break;
                }
                log.warn("[rating] 入账事务被回滚（死锁 / 锁等待超时），第 {}/{} 次 battle={}: {}", attempt, MAX_ATTEMPTS, battle(event), e.toString());
                if (attempt < MAX_ATTEMPTS && !backoff(attempt)) {
                    break;
                }
            } catch (RuntimeException e) {
                // 不是 SQL 错误（规则计算、驱动内部）：事务已回滚，原样上抛
                metrics.ratingUpdate(mode, RatingOutcome.ERROR);
                throw e;
            }
        }
        metrics.ratingUpdate(mode, RatingOutcome.ERROR);
        throw new StoreException("对局结果入账失败 battle=" + battle(event), last);
    }

    /** 一次尝试：一笔事务。SQL 错误原样抛出（事务已回滚），由 {@link #apply} 分类。 */
    private Result applyOnce(BattleResultEvent event, EloRules.Verdict.Scored scored) throws SQLException {
        long now = clockMs.getAsLong();
        long battleId = event.getBattleId();
        Connection c = connections.get(APPLY_CONNECTION_WAIT_MS);
        try {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            boolean finished = false;
            try {
                if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
                    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }
                c.setAutoCommit(false);
                if (!insertApplied(c, battleId, event.getMatchMode(), now)) {
                    c.rollback();
                    finished = true;
                    return Result.duplicate();
                }
                Map<Long, Boolean> sides = scored.sides();
                List<Long> ascending = new ArrayList<>(sides.keySet());
                ascending.sort(Long::compareUnsigned);
                for (Long pid : ascending) {
                    ensureRow(c, pid, now);
                }
                Map<Long, Long> pre = new LinkedHashMap<>();
                for (Long pid : ascending) {
                    pre.put(pid, lockRow(c, pid));
                }
                EloRules.Settlement settlement = EloRules.settle(scored, pre);
                for (Long pid : ascending) {
                    updateRow(c, pid, settlement.nextCenti().get(pid), now);
                }
                long deltaACenti = settlement.deltaACenti();
                setDelta(c, battleId, deltaACenti);
                c.commit();
                finished = true;
                log.info("[rating] 对局入账 battle={} mode={} outcome={} rounds={} 回合打满按平局={} avgA={} avgB={} deltaA={} teamA={} teamB={}",
                        battle(event), MetricLabels.mode(event.getMatchMode()), event.getOutcomeValue(),
                        Integer.toUnsignedString(event.getTotalRounds()), scored.roundCapDraw(), settlement.averageA(), settlement.averageB(),
                        settlement.deltaA(), unsigned(scored.teamA()), unsigned(scored.teamB()));
                return new Result(Outcome.APPLIED, null, deltaACenti, scored.roundCapDraw());
            } finally {
                if (!finished) {
                    rollbackQuietly(c);
                }
                restore(c, autoCommit, isolation);
            }
        } finally {
            closeQuietly(c);
        }
    }

    /** @return false = 撞主键（这一局已入账） */
    private static boolean insertApplied(Connection c, long battleId, int mode, long now) throws SQLException {
        try (PreparedStatement ps = prepare(c, SQL_INSERT_APPLIED, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
            ps.setObject(1, PbMysql.uint64(battleId));
            ps.setInt(2, mode);
            ps.setLong(3, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (RatingSqlErrors.isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    private static void ensureRow(Connection c, long playerId, long now) throws SQLException {
        try (PreparedStatement ps = prepare(c, SQL_ENSURE_ROW, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.setLong(2, RatingReader.DEFAULT_CENTI);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private static long lockRow(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = prepare(c, SQL_LOCK_ROW, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // 刚补过行、又在同一笔事务里：读不到只可能是有人在事务外删了它
                    throw new SQLException("评分行在补行之后不见了 player=" + Long.toUnsignedString(playerId));
                }
                return rs.getLong(1);
            }
        }
    }

    private static void updateRow(Connection c, long playerId, long ratingCenti, long now) throws SQLException {
        try (PreparedStatement ps = prepare(c, SQL_UPDATE_ROW, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
            ps.setLong(1, ratingCenti);
            ps.setLong(2, now);
            ps.setObject(3, PbMysql.uint64(playerId));
            ps.executeUpdate();
        }
    }

    private static void setDelta(Connection c, long battleId, long deltaACenti) throws SQLException {
        try (PreparedStatement ps = prepare(c, SQL_SET_DELTA, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
            ps.setLong(1, deltaACenti);
            ps.setObject(2, PbMysql.uint64(battleId));
            ps.executeUpdate();
        }
    }

    /** 重跑之前等一小会儿（带抖动，避免两个撞锁的事务同步重来）。@return false = 被中断，不再重跑 */
    private boolean backoff(int attempt) {
        try {
            sleeper.sleep(20L * attempt + ThreadLocalRandom.current().nextLong(30));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ================================================================ 读

    /**
     * 读一组玩家的评分行：结果<b>只含有行的人</b>（没有行 = 新号，调用方按 {@link Rating#fresh} 处理）；入参里重复的玩家号只查一次。
     *
     * @throws StoreException 读失败（库不可达、取连接或语句超时）
     */
    public Map<Long, Rating> find(Collection<Long> playerIds) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(playerIds));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, Rating> out = new LinkedHashMap<>();
        try (Connection c = connections.get(READ_CONNECTION_WAIT_MS)) {
            for (int from = 0; from < distinct.size(); from += READ_CHUNK) {
                List<Long> chunk = distinct.subList(from, Math.min(distinct.size(), from + READ_CHUNK));
                String sql = SQL_FIND_PREFIX + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement ps = prepare(c, sql, READ_TIMEOUT_SECONDS)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setObject(i + 1, PbMysql.uint64(chunk.get(i)));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            // BIGINT UNSIGNED：≥ 2^63 的号驱动给 BigInteger / BigDecimal，取位模式
                            long playerId = rs.getBigDecimal(1).toBigInteger().longValue();
                            out.put(playerId, new Rating(playerId, rs.getLong(2), rs.getLong(3)));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读评分失败", e);
        }
        return out;
    }

    /** 读一名玩家的评分行；没有行为 {@link Rating#fresh}。失败抛 {@link StoreException}。 */
    public Rating findOrFresh(long playerId) {
        Rating row = find(List.of(playerId)).get(playerId);
        return row == null ? Rating.fresh(playerId) : row;
    }

    // ================================================================ 清理

    /**
     * 删一批早于 {@code beforeMs} 的入账标记（自动提交的一条语句；按 {@code applied_at_ms} 索引）。
     *
     * @return 删掉的行数（等于 {@code limit} 说明可能还有）
     * @throws StoreException 删除失败
     */
    public int deleteAppliedBefore(long beforeMs, int limit) {
        try (Connection c = connections.get(APPLY_CONNECTION_WAIT_MS)) {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            try (PreparedStatement ps = prepare(c, SQL_DELETE_APPLIED, APPLY_STATEMENT_TIMEOUT_SECONDS)) {
                ps.setLong(1, beforeMs);
                ps.setInt(2, limit);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StoreException("清理入账标记失败", e);
        }
    }

    // ================================================================ 内部

    private static PreparedStatement prepare(Connection c, String sql, int timeoutSeconds) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeoutSeconds);
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
        return ps;
    }

    private static String battle(BattleResultEvent event) {
        return Long.toUnsignedString(event.getBattleId());
    }

    private static List<String> unsigned(List<Long> ids) {
        return ids.stream().map(Long::toUnsignedString).toList();
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
            log.warn("[rating] 还原连接状态失败: {}", e.toString());
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("[rating] 回滚失败（连接断开时服务端会自动回滚）: {}", e.toString());
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            log.warn("[rating] 归还连接失败: {}", e.toString());
        }
    }
}
