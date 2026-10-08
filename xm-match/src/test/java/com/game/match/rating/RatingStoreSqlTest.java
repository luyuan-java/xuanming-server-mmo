package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.game.match.rating.RatingStore.Outcome;
import com.game.match.rating.RatingStore.Result;
import com.game.match.rating.RatingStore.StoreException;
import com.game.match.support.MatchModes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link RatingStore} 连真 MySQL 的测试（缺省跳过）：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量 XM_MYSQL_PASSWORD。
 * 建一次性库 {@code xm_match_it_<随机>}（两张表走生产的 pbmysql 同步），结束时删掉；不碰 {@code xm_java}。
 *
 * <p>先把 {@link RatingStoreCases} 的全部行为用例在真库上再跑一遍（{@code ON DUPLICATE KEY UPDATE}、主键冲突 1062、回滚都是真的），
 * 再加只有真库才有的：两个连接并发入账同一玩家的两局都落账（对应基线 {@code rating_review_fix_test.go:108}，match-spec §15.3）、
 * 同一局并发投递恰好一次、真的 1213 死锁整笔重跑、真的越界被库拒绝。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class RatingStoreSqlTest extends RatingStoreCases {

    /** 会话的锁等待上限放宽到 10 s（生产是 1 s）：下面的用例要看到的是「等到锁」与「死锁」，不是锁等待超时。 */
    private static final int LOCK_WAIT_SECONDS = 10;
    private static final String LOCK_WAITERS = "SELECT COUNT(*) FROM information_schema.innodb_trx t JOIN information_schema.processlist p"
            + " ON p.id = t.trx_mysql_thread_id WHERE t.trx_state = 'LOCK WAIT' AND p.db = DATABASE()";

    private static RatingTestDatabase shared;

    @BeforeAll
    static void createDatabase() {
        shared = RatingTestDatabase.mysql(LOCK_WAIT_SECONDS);
    }

    @AfterAll
    static void dropDatabase() {
        if (shared != null) {
            shared.close();
        }
    }

    @Override
    protected RatingTestDatabase openDatabase() {
        shared.update("TRUNCATE TABLE match_rating");
        shared.update("TRUNCATE TABLE match_rating_applied");
        return shared;
    }

    @Override
    protected void closeDatabase(RatingTestDatabase database) {
        // 一次性库整个类共用，@AfterAll 再删
    }

    /** 等到本库里有事务在等行锁（最多 8 s）。 */
    private void awaitLockWaiter() throws InterruptedException {
        awaitLockWaiter(null);
    }

    /**
     * 等到本库里有事务在等行锁（最多 8 s）。
     *
     * @param expectedWaiter 预期会卡在锁上的那次入账；它若没等锁就结束了（成功或失败），立即报出来，不白等
     */
    private void awaitLockWaiter(Future<Result> expectedWaiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        long t0 = System.nanoTime();
        while (System.nanoTime() < deadline) {
            long waiters = db.query(LOCK_WAITERS, rs -> {
                rs.next();
                return rs.getLong(1);
            });
            System.out.println("DIAG2 t=" + (System.nanoTime() - t0) / 1_000_000 + " waiters=" + waiters + " calls=" + ds.calls.size()
                    + " trx=" + dump("SELECT trx_state, trx_mysql_thread_id, trx_query FROM information_schema.innodb_trx")
                    + " procs=" + dump("SELECT id, command, time, state, LEFT(IFNULL(info, ''), 60) FROM information_schema.processlist WHERE db = DATABASE()"));
            if (waiters > 0) {
                return;
            }
            if (expectedWaiter != null && expectedWaiter.isDone()) {
                String ending;
                try {
                    ending = String.valueOf(expectedWaiter.get());
                } catch (Exception e) {
                    ending = e + " / " + e.getCause() + " / " + (e.getCause() == null ? null : e.getCause().getCause());
                }
                fail("预期会等行锁的入账没有等锁就结束了: " + ending);
            }
            Thread.sleep(20);
        }
        fail("8 s 内没有看到等行锁的事务；innodb_trx=" + dump("SELECT trx_state, trx_mysql_thread_id, trx_query FROM information_schema.innodb_trx")
                + " processlist=" + dump("SELECT id, db, command, state, info FROM information_schema.processlist"));
    }

    /** 失败时把现场带进断言消息里。 */
    private String dump(String sql) {
        return db.query(sql, rs -> {
            List<String> rows = new ArrayList<>();
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(rs.getString(i)).append('|');
                }
                rows.add(row.toString());
            }
            return rows.toString();
        });
    }

    // ================================================================ 建表

    @Test
    void 生产的建表路径_列类型与索引都在_再同步一次不改任何东西() throws Exception {
        List<String> columns = db.query("SELECT CONCAT(table_name, '.', column_name, ' ', column_type) FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position", rs -> {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        });
        List<String> indexes = db.query("SELECT CONCAT(table_name, '.', index_name, '(', column_name, ')') FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() ORDER BY table_name, index_name", rs -> {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        });

        assertThat(columns).containsExactly(
                "match_rating.player_id bigint unsigned", "match_rating.rating_centi bigint", "match_rating.games int unsigned",
                "match_rating.updated_at_ms bigint unsigned",
                "match_rating_applied.battle_id bigint unsigned", "match_rating_applied.match_mode int",
                "match_rating_applied.delta_a_centi bigint", "match_rating_applied.applied_at_ms bigint unsigned");
        assertThat(indexes).containsExactlyInAnyOrder("match_rating.PRIMARY(player_id)",
                "match_rating_applied.idx_match_rating_applied_0(applied_at_ms)", "match_rating_applied.PRIMARY(battle_id)");

        db.putRating(1, 151_600, 1);
        MatchRatingTables.sync(db.dataSource, Duration.ofMinutes(1));
        MatchRatingTables.PBMYSQL.sync(db.dataSource);
        assertThat(db.ratingRow(1)).as("重复同步只扩不缩、不动数据").hasValueSatisfying(row -> assertThat(row).containsExactly(151_600, 1, 0));
    }

    // ================================================================ 真库才有的错误码

    @Test
    void 重复投递撞的是真的1062() {
        apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        Result again = apply(event(9001, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L)));

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(ds.errors).hasSize(1);
        assertThat(ds.errors.get(0).getErrorCode()).isEqualTo(1062);
        assertThat(db.games(12001)).isEqualTo(1);
    }

    @Test
    void 局数列越界_被真库拒绝_是数据错误_整笔回滚() {
        // games 是 int unsigned：已到上限的行再 +1，严格模式下报 1264（SQLState 22003）
        db.putRating(12001, 150_000, 4_294_967_295L);

        assertThatThrownBy(() -> apply(event(9909, MatchModes.ONE_V_ONE, A_WIN, List.of(12001L), List.of(12002L))))
                .isInstanceOf(StoreException.class)
                .satisfies(e -> assertThat(RatingSqlErrors.isDataError(e)).isTrue())
                .satisfies(e -> assertThat(RatingSqlErrors.isRetryable(e)).isFalse());

        assertThat(ds.errors).hasSize(1);
        assertThat(ds.errors.get(0).getSQLState()).startsWith("22");
        assertThat(db.ratingRow(12001)).hasValueSatisfying(row -> assertThat(row).containsExactly(150_000, 4_294_967_295L, 0));
        assertThat(db.ratingRow(12002)).as("对手的行与标记一并回滚").isEmpty();
        assertThat(db.count("match_rating_applied")).isZero();
        assertThat(ds.executed(INSERT_APPLIED)).as("数据错误不重跑").isEqualTo(1);
    }

    // ================================================================ 并发

    @Test
    void 同一局被两个连接同时投递_恰好一个入账一个duplicate() throws Exception {
        for (int round = 0; round < 10; round++) {
            long battle = 9500 + round;
            long a = 20_000 + round * 2L;
            long b = a + 1;
            CyclicBarrier start = new CyclicBarrier(2);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                List<Future<Result>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return apply(event(battle, MatchModes.ONE_V_ONE, A_WIN, List.of(a), List.of(b)));
                    }));
                }
                List<Outcome> outcomes = new ArrayList<>();
                for (Future<Result> future : futures) {
                    outcomes.add(future.get(30, TimeUnit.SECONDS).outcome());
                }

                assertThat(outcomes).as("第 %d 轮", round).containsExactlyInAnyOrder(Outcome.APPLIED, Outcome.DUPLICATE);
            }
            assertThat(db.ratingRow(a)).hasValueSatisfying(row -> assertThat(row[0]).isEqualTo(151_600));
            assertThat(db.games(a)).as("只加了一次").isEqualTo(1);
            assertThat(db.games(b)).isEqualTo(1);
        }
        assertThat(db.count("match_rating_applied")).isEqualTo(10);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(10);
        assertThat(updates("MATCH_MODE_1V1", "duplicate")).isEqualTo(10);
    }

    @Test
    void 同一玩家的两局被两个连接并发入账_行锁串行化_两局都落账_后一局按更新后的分算() throws Exception {
        long p = 14001;
        long oppX = 14002;
        long oppY = 14003;
        CountDownLatch xHoldsLock = new CountDownLatch(1);
        // 局 X 锁住 p 的评分行之后停住，等看到局 Y 的事务卡在这把锁上再继续
        ds.afterOnce(LOCK_ROW, () -> {
            xHoldsLock.countDown();
            awaitLockWaiter();
        });

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Result> x = pool.submit(() -> apply(event(9401, MatchModes.ONE_V_ONE, A_WIN, List.of(p), List.of(oppX))));
            assertThat(xHoldsLock.await(10, TimeUnit.SECONDS)).as("局 X 走到了加锁读").isTrue();
            Future<Result> y = pool.submit(() -> apply(event(9402, MatchModes.ONE_V_ONE, A_WIN, List.of(p), List.of(oppY))));

            Result resultX = x.get(30, TimeUnit.SECONDS);
            Result resultY = y.get(30, TimeUnit.SECONDS);

            assertThat(resultX).isEqualTo(new Result(Outcome.APPLIED, null, 1_600, false));
            // 局 Y 读到的是局 X 提交后的 1516：E = 0.5230，Δ = 15.26（基线并发时两局都按旧分算，各 +16；Java 行锁串行，M18）
            assertThat(resultY).isEqualTo(new Result(Outcome.APPLIED, null, 1_526, false));
        }
        assertThat(db.ratingRow(p)).as("两局的增量都落账，局数 2").hasValueSatisfying(row -> {
            assertThat(row[0]).isEqualTo(153_126);
            assertThat(row[1]).isEqualTo(2);
        });
        assertThat(db.ratingCenti(oppX)).hasValue(148_400);
        assertThat(db.ratingCenti(oppY)).hasValue(148_474);
        assertThat(db.count("match_rating_applied")).isEqualTo(2);
        assertThat(ds.errors).as("没有死锁、没有锁等待超时：只是排队").isEmpty();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 许多局并发入账同一批玩家_全部落账_没有死锁() throws Exception {
        // 4 名玩家两两对战，12 局（每对 2 局）同时入账：加锁次序一致（玩家号升序），不会互相死锁
        long[] players = {31_001, 31_002, 31_003, 31_004};
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < players.length; i++) {
            for (int j = i + 1; j < players.length; j++) {
                pairs.add(new long[] {players[i], players[j]});
                pairs.add(new long[] {players[j], players[i]});
            }
        }
        CyclicBarrier start = new CyclicBarrier(pairs.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(pairs.size())) {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                long battle = 9600 + i;
                long[] pair = pairs.get(i);
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return apply(event(battle, MatchModes.ONE_V_ONE, A_WIN, List.of(pair[0]), List.of(pair[1])));
                }));
            }
            for (Future<Result> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.APPLIED);
            }
        }

        for (long player : players) {
            assertThat(db.games(player)).as("每人 6 局").isEqualTo(6);
        }
        assertThat(db.count("match_rating_applied")).isEqualTo(12);
        assertThat(ds.errors).extracting(SQLException::getErrorCode).as("升序加锁：不出 1213").doesNotContain(1213);
        // Elo 是零和的；每局两边各舍入至多 0.005 分，12 局的总和偏离不超过 12 centi
        assertThat(Math.abs(db.sumRatingCenti() - 4 * 150_000L)).isLessThanOrEqualTo(12);
        boolean moved = false;
        for (long player : players) {
            moved |= db.ratingCenti(player).orElseThrow() != 150_000;
        }
        assertThat(moved).as("每对互有胜负、先后有别：评分确实动过").isTrue();
    }

    @Test
    void 真的死锁1213_本事务被回滚_整笔重跑后恰好入账一次() throws Exception {
        db.putRating(101, 160_000, 0);
        db.putRating(102, 140_000, 0);
        seedFillerRows(1_000, 300);

        try (Connection rival = db.dataSource.getConnection(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            rival.setAutoCommit(false);
            try (Statement st = rival.createStatement()) {
                // 对手事务先改 300 行（分量重，死锁时 InnoDB 会挑分量轻的入账事务回滚），再锁住 102
                assertThat(st.executeUpdate("UPDATE match_rating SET games = games + 1 WHERE player_id >= 1000")).isEqualTo(300);
                lockRow(rival, 102);

                // 入账事务：插标记 → 锁 101 → 等 102（被对手拿着）
                Future<Result> applying = pool.submit(() -> apply(event(9931, MatchModes.ONE_V_ONE, A_WIN, List.of(101L), List.of(102L))));
                awaitLockWaiter(applying);

                // 对手再去要 101：成环。InnoDB 回滚入账事务（1213），对手拿到锁
                try {
                    lockRow(rival, 101);
                } catch (SQLException e) {
                    rival.rollback();
                    fail("死锁的牺牲者应该是入账事务，而不是分量重的对手事务: " + e);
                }
                rival.commit();

                assertThat(applying.get(30, TimeUnit.SECONDS)).isEqualTo(new Result(Outcome.APPLIED, null, 769, false));
            } finally {
                rival.rollback();
            }
        }

        assertThat(ds.errors).as("真驱动报了一次死锁").hasSize(1);
        assertThat(ds.errors.get(0).getErrorCode()).isEqualTo(1213);
        assertThat(ds.errors.get(0).getSQLState()).isEqualTo("40001");
        assertThat(ds.executed(INSERT_APPLIED)).as("整笔重跑：标记插了两次，第一次随事务回滚").isEqualTo(2);
        assertThat(sleeps).hasSize(1);
        assertThat(db.ratingRow(101)).hasValueSatisfying(row -> assertThat(row).containsExactly(160_769, 1, NOW));
        assertThat(db.ratingRow(102)).hasValueSatisfying(row -> assertThat(row).containsExactly(139_231, 1, NOW));
        assertThat(db.count("match_rating_applied")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "applied")).isEqualTo(1);
        assertThat(updates("MATCH_MODE_1V1", "error")).isZero();
    }

    private static void lockRow(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT rating_centi FROM match_rating WHERE player_id = ? FOR UPDATE")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    /** 一条连接上批量插 {@code count} 行占位的评分行（玩家号从 {@code firstPlayerId} 起连续）。 */
    private void seedFillerRows(long firstPlayerId, int count) throws SQLException {
        try (Connection c = db.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO match_rating (player_id, rating_centi, games, updated_at_ms) VALUES (?, 150000, 0, 0)")) {
            for (int i = 0; i < count; i++) {
                ps.setLong(1, firstPlayerId + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
