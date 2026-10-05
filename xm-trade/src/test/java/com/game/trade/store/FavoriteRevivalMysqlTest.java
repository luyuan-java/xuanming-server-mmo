package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.game.common.deadline.Deadline;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 收藏写入的锁模式回归（2026-09-21 死锁审计 #9；基线 listing_repo_integration_test.go:250-813 的移植；trade-spec §1.9、§9.3）。缺省跳过：
 * {@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}；账号要能 SELECT {@code performance_schema.data_locks / data_lock_waits}（缺省 root），
 * 读不了时确定性用例<b>跳过（不代表通过）</b>、压力用例退化为不确认排队的纯并发。
 *
 * <p><b>成环的真实前提</b>（基线头注）：环不是「两个收藏并发」就能出现的，要<b>有第三方正持着那条删除标记记录的 X、至少两个收藏语句同时排在它后面</b>。
 * 本调用路径上的第三方只有两种（{@link Holder}）：未提交的取消收藏 DELETE；先到的收藏把删除标记记录复活、随后回滚。第三方一放锁：
 * INSERT IGNORE 的重复键检查取 S，两个排队者同时拿到、都判定「只是删除标记」、各自申请 X 复活它 → 互等 → 1213；
 * {@link ListingSql#INSERT_FAVORITE}（ODKU）的重复键检查直接取 X，只有一个排队者拿到，另一个排到它提交之后看见活行、走空更新。
 *
 * <p><b>编排</b>：靠 performance_schema <b>观察</b>两个排队者真的进了锁等待队列再放锁，不靠并发度去撞、不靠 sleep 估时间。红绿两组走同一个编排，
 * 唯一的变量是收藏语句本身。purge 挡板：一个 REPEATABLE READ 只读事务在删除提交<b>之前</b>做一次一致性读，它的 read view 让 purge 不能物理清掉
 * 这条删除标记记录（不挡的话锁会被继承成间隙锁，形状就变成 InsertFavorite 注释里那类由有界重试兜住的固有情形，结论不再确定）。
 *
 * <p>编排用的连接各自独立、锁等待放宽到 {@value #ORCHESTRATION_LOCK_WAIT_SECONDS} s（验的是锁模式，不是超时）；压力用例走完整的产品路径
 * {@link JdbcListingStore#insertFavorite}（连接池、{@code innodb_lock_wait_timeout=2}、单次 2000 ms 上限、有界重试）。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class FavoriteRevivalMysqlTest {

    private static final Logger log = LoggerFactory.getLogger(FavoriteRevivalMysqlTest.class);

    /** 2026-09-21 之前用的语句：生产代码里已经没有它，这里只给红对照用（listing_repo_integration_test.go:298）。 */
    static final String LEGACY_INSERT_IGNORE_FAVORITE = "INSERT IGNORE INTO trade_favorite (`player_id`, `listing_id`, `created_ms`)"
            + " VALUES (?, ?, ?)";

    private static final long LISTING = 9_200_001L;
    private static final long SEED_MS = 1;      // 夹具先插入再删除的那一版
    private static final long HOLDER_MS = 100;  // 「先到后回滚」的收藏写入、随后被回滚掉的值
    private static final long WAITER_MS = 201;  // 两个排队者依次取 201、202，终态 created_ms 能指认是谁写的
    private static final long WAIT_BUDGET_MS = 10_000;
    private static final int ORCHESTRATION_LOCK_WAIT_SECONDS = 30;

    /** 数本库里正在排队等某张表上行锁的事务数（基线 lockWaitersQuery，与 go/friend 同一口径）。 */
    private static final String LOCK_WAITERS_QUERY = "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)"
            + " FROM performance_schema.data_lock_waits w"
            + " JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"
            + " WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = ?";

    /** 排在两个收藏语句前面、持有那条记录 X 锁的第三方（基线 favoriteLockHolder）。 */
    enum Holder {
        /** 取消收藏的 DELETE 已删掉活行、尚未提交。放锁 = 提交，留下已提交的删除标记。 */
        PENDING_UNFAVORITE(9_100_001L),
        /** 先到的收藏已把已提交的删除标记记录复活、随后回滚。放锁 = 回滚，记录退回删除标记。 */
        ROLLED_BACK_FAVORITE(9_100_002L);

        final long player;

        Holder(long player) {
            this.player = player;
        }
    }

    /** 读不了 performance_schema 的锁视图（权限 / 对象缺失）：环境问题，不是产品缺陷。 */
    private static final class LockWaitsUnobservable extends Exception {
        LockWaitsUnobservable(SQLException cause) {
            super("读 performance_schema.data_lock_waits 失败", cause);
        }
    }

    /** 在独立 READ COMMITTED 事务里执行的一条收藏语句。 */
    private static final class FavoriteStmt {
        final String name;
        final long createdMs;
        final Connection conn;
        final CompletableFuture<SQLException> finished;

        FavoriteStmt(String name, long createdMs, Connection conn, CompletableFuture<SQLException> finished) {
            this.name = name;
            this.createdMs = createdMs;
            this.conn = conn;
            this.finished = finished;
        }

        SQLException await() {
            try {
                return finished.get(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError(name + " 在 " + WAIT_BUDGET_MS + " ms 内没有返回：持锁方已经放锁，它仍卡着——锁行为与推演不符");
            } catch (Exception e) {
                throw new AssertionError(name + " 异常结束", e);
            }
        }
    }

    private static TradeMysqlFixture db;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "trade-favorite-it");
        t.setDaemon(true);
        return t;
    });

    /** 本用例开的连接与要删的行：清理时先放持锁方、再等排队者、最后删行。 */
    private final List<Connection> holders = new ArrayList<>();
    private final List<FavoriteStmt> waiters = new ArrayList<>();
    private final List<Long> players = new ArrayList<>();

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TradeMysqlFixture.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        // 先回滚持锁方与 purge 挡板（连接空闲，ROLLBACK 当场放锁）→ 排队者随之执行完 → 回滚它们 → 删本用例的行
        for (Connection c : holders) {
            closeQuietly(c, true);
        }
        for (FavoriteStmt w : waiters) {
            try {
                w.finished.get(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
                closeQuietly(w.conn, true);
            } catch (Exception e) {
                log.error("清理：{} 在放锁之后仍未返回，强行断开", w.name);
                w.conn.abort(POOL);
            }
        }
        for (long player : players) {
            db.exec("DELETE FROM trade_favorite WHERE `player_id` = ?", player);
        }
    }

    private static void closeQuietly(Connection c, boolean rollback) {
        try (c) {
            if (rollback && !c.getAutoCommit()) {
                c.rollback();
            }
        } catch (SQLException e) {
            log.warn("清理连接失败: {}", e.toString());
        }
    }

    // ================================================================ 编排

    /** 开一条独立的 RC 事务连接（自动提交关闭）。 */
    private Connection begin(int isolation) throws SQLException {
        Connection c = db.connect(ORCHESTRATION_LOCK_WAIT_SECONDS);
        c.setTransactionIsolation(isolation);
        c.setAutoCommit(false);
        return c;
    }

    /**
     * purge 挡板：REPEATABLE READ 只读事务做一次一致性读，此刻建好的 read view 让 purge 不能物理清掉之后才提交的删除标记记录（基线 openPurgeBlocker）。
     * 用带 WHERE 的普通查询：它一定走 row_search_mvcc、一定分配 read view。
     */
    private void openPurgeBlocker(long player) throws SQLException {
        Connection c = begin(Connection.TRANSACTION_REPEATABLE_READ);
        holders.add(c);
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM trade_favorite WHERE `player_id` = ?")) {
            ps.setObject(1, player);
            ps.executeQuery().close();
        }
    }

    private static void mustAffectOneRow(Connection c, String what, String sql, long player, long listing, Long createdMs)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, player);
            ps.setObject(2, listing);
            if (createdMs != null) {
                ps.setObject(3, createdMs);
            }
            int n = ps.executeUpdate();
            assertThat(n).as("%s：期望恰好影响 1 行", what).isEqualTo(1);
        }
    }

    private FavoriteStmt startFavoriteStmt(String sql, String name, long player, long createdMs) throws SQLException {
        Connection c = begin(Connection.TRANSACTION_READ_COMMITTED);
        CompletableFuture<SQLException> finished = CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, player);
                ps.setObject(2, LISTING);
                ps.setObject(3, createdMs);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                return e;
            }
        }, POOL);
        FavoriteStmt s = new FavoriteStmt(name, createdMs, c, finished);
        waiters.add(s);
        return s;
    }

    /**
     * 把现场摆到「持锁方刚放锁」的那一刻，返回两个排队的收藏语句（基线 startDeleteMarkedFavoriteRace）：
     * 1. 开 purge 挡板；2. 按 holder 造出持 X 的第三方，(player, listing) 此时是删除标记记录；3. 起两个收藏语句，<b>确认</b>两者都排进了锁等待队列；
     * 4. 放锁（DELETE 提交 / 复活回滚）。
     */
    private List<FavoriteStmt> startDeleteMarkedFavoriteRace(String sql, Holder holder) throws Exception {
        long player = holder.player;
        players.add(player);
        openPurgeBlocker(player);
        db.exec("INSERT INTO trade_favorite (`player_id`, `listing_id`, `created_ms`) VALUES (?, ?, ?)", player, LISTING, SEED_MS);

        Connection holderTx = begin(Connection.TRANSACTION_READ_COMMITTED);
        holders.add(holderTx);
        switch (holder) {
            case PENDING_UNFAVORITE -> mustAffectOneRow(holderTx, "未提交的取消收藏应当删掉活行（此时持有该主键的 X）",
                    ListingSql.DELETE_FAVORITE, player, LISTING, null);
            case ROLLED_BACK_FAVORITE -> {
                assertThat(db.exec(ListingSql.DELETE_FAVORITE, player, LISTING)).as("删除活行、留下已提交的删除标记").isEqualTo(1);
                // 影响 1 行 = 真的走了「复活删除标记记录」的插入分支；记录若还是活行，INSERT IGNORE 影响 0 行且不持 X，编排就不成立
                mustAffectOneRow(holderTx, "先到的收藏应当复活删除标记记录", sql, player, LISTING, HOLDER_MS);
            }
        }

        List<FavoriteStmt> race = List.of(
                startFavoriteStmt(sql, "收藏#1", player, WAITER_MS),
                startFavoriteStmt(sql, "收藏#2", player, WAITER_MS + 1));
        awaitLockWaitersOrSkip(2, "持锁方未放锁，两个收藏语句都应当卡在这条记录的重复键检查上");

        if (holder == Holder.PENDING_UNFAVORITE) {
            holderTx.commit();
        } else {
            holderTx.rollback();
        }
        return race;
    }

    private static void awaitLockWaitersOrSkip(int want, String expectation) {
        try {
            awaitLockWaiters(want, expectation);
        } catch (LockWaitsUnobservable e) {
            Assumptions.abort("无法编排本场景（测试账号需要 performance_schema 的 SELECT 权限），跳过——不代表通过: " + e.getCause());
        }
    }

    /** 轮询直到 trade_favorite 上至少有 want 个事务在排队等行锁；靠观察，不靠 sleep 估时间（基线 awaitLockWaiters）。 */
    private static void awaitLockWaiters(int want, String expectation) throws LockWaitsUnobservable {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_BUDGET_MS);
        long seen = 0;
        while (true) {
            try {
                seen = db.count(LOCK_WAITERS_QUERY, TradeTables.FAVORITE);
            } catch (SQLException e) {
                if (isLockWaitsUnobservable(e)) {
                    throw new LockWaitsUnobservable(e);
                }
                throw new AssertionError("查询 performance_schema 锁等待失败，且不是权限 / 对象缺失类错误，按编排失败判红", e);
            }
            if (seen >= want) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("%d ms 内只看到 %d/%d 个事务排进 trade_favorite 的锁等待队列 —— %s", WAIT_BUDGET_MS, seen, want, expectation);
            }
            pause(10);
        }
    }

    /** 只按错误号判「账号或实例不支持观察锁等待」（基线 isLockWaitsUnobservable）：1044 / 1142 / 1143 / 1146 / 1227。 */
    private static boolean isLockWaitsUnobservable(SQLException e) {
        int code = JdbcListingStore.errorCode(e);
        return code == 1044 || code == 1142 || code == 1143 || code == 1146 || code == 1227;
    }

    private static boolean isDeadlock(SQLException e) {
        return e != null && JdbcListingStore.errorCode(e) == JdbcListingStore.ER_LOCK_DEADLOCK;
    }

    private static long onlyFavoriteCreatedMs(long player) throws SQLException {
        assertThat(db.count("SELECT COUNT(*) FROM trade_favorite WHERE `player_id` = ?", player)).as("终态该玩家必须恰好 1 条收藏").isEqualTo(1);
        return db.queryU64("SELECT `created_ms` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?", player, LISTING);
    }

    // ================================================================ 绿：生产语句

    /**
     * 同一编排换成生产常量 {@link ListingSql#INSERT_FAVORITE}：两个排队者都必须成功、终态恰好 1 行、created_ms 是先完成者写入的值
     * （后到者走空更新，不刷新收藏时间）。谁先拿到 X 由 InnoDB 决定，所以<b>观察</b>谁先返回：先完成者返回 → 确认另一个仍在锁等待队列里 →
     * 提交先完成者 → 另一个才返回（基线 TestInsertFavoriteSQLRevivesDeleteMarkedRowWithoutDeadlock）。
     */
    @ParameterizedTest
    @EnumSource(Holder.class)
    void 生产语句复活删除标记记录不死锁(Holder holder) throws Exception {
        List<FavoriteStmt> race = startDeleteMarkedFavoriteRace(ListingSql.INSERT_FAVORITE, holder);
        CompletableFuture.anyOf(race.get(0).finished, race.get(1).finished).get(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
        FavoriteStmt first = race.get(0).finished.isDone() ? race.get(0) : race.get(1);
        FavoriteStmt second = first == race.get(0) ? race.get(1) : race.get(0);
        assertNoDeadlock(first, first.await());

        // 先完成者持着 X 未提交，另一个此刻必须还在排队：ODKU 的重复键检查取 X，不可能与它并行
        assertThat(second.finished.isDone()).as("%s 在 %s 提交之前就返回了：重复键检查没有取 X——INSERT_FAVORITE 被改成别的写法了？",
                second.name, first.name).isFalse();
        awaitLockWaitersOrSkip(1, second.name + " 应当排在先完成者的 X 后面");

        first.conn.commit();
        assertNoDeadlock(second, second.await());
        second.conn.commit();
        assertThat(onlyFavoriteCreatedMs(holder.player)).as("created_ms 必须是先完成者 %s 写入的值：后到者只能走空更新", first.name)
                .isEqualTo(first.createdMs);
    }

    private static void assertNoDeadlock(FavoriteStmt s, SQLException error) {
        if (isDeadlock(error)) {
            fail("%s 返回 InnoDB 死锁（1213）：收藏写入在删除标记记录上 S→X 升级、与另一个排队者互等（INSERT_FAVORITE 被改回 INSERT IGNORE 了？）—— %s",
                    s.name, error);
        }
        if (error != null) {
            throw new AssertionError(s.name + " 出现非预期错误", error);
        }
    }

    // ================================================================ 红对照：旧语句

    /**
     * 红对照：旧的 INSERT IGNORE 在同一编排下必须复现 1213，且恰好一个排队者被牺牲、另一个成功。它不验产品代码，验的是「编排确实走到了成环形状」；
     * 它不红的时候，绿用例的「不死锁」就什么都没证明（基线 TestLegacyInsertIgnoreFavoriteDeadlocksOnDeleteMarkedRow）。
     */
    @ParameterizedTest
    @EnumSource(Holder.class)
    void 旧语句INSERT_IGNORE在同一编排下死锁(Holder holder) throws Exception {
        List<FavoriteStmt> race = startDeleteMarkedFavoriteRace(LEGACY_INSERT_IGNORE_FAVORITE, holder);
        int deadlocked = 0;
        int succeeded = 0;
        for (FavoriteStmt w : race) {
            SQLException error = w.await();
            if (isDeadlock(error)) {
                deadlocked++;
            } else if (error == null) {
                succeeded++;
            } else {
                fail("%s 出现非预期错误（既不是成功也不是 1213）: %s", w.name, error);
            }
        }
        assertThat(new int[] {deadlocked, succeeded})
                .as("红对照没有复现死锁：期望恰好 1 个 1213、1 个成功。说明放锁时两个 S 没有被同时授予（或记录已被 purge），编排没走到成环形状，"
                        + "绿用例在这个 MySQL 版本上失去证明力")
                .containsExactly(1, 1);
    }

    // ================================================================ 压力：完整产品路径

    /**
     * 每轮让 8 个并发收藏（{@link JdbcListingStore#insertFavorite}：连接池、自动提交、有界重试）排在一条未提交的取消收藏后面，放锁后断言零错误、
     * 终态恰好 1 行、且这一行是本轮复活出来的（基线 TestInsertFavoriteConcurrentRevivalStress）。产品路径会用重试吸收一次 1213，所以本用例不证明
     * 「一次 1213 都没有」（那是绿用例的职责），证明的是调用方零错误。读不了 performance_schema 时退化为不确认排队的纯并发轮次。
     */
    @Test
    void 产品路径并发复活零错误() throws Exception {
        long player = 9_100_101L;
        int workers = 8;
        int rounds = 200;
        players.add(player);
        JdbcListingStore store = db.store();
        openPurgeBlocker(player);
        store.insertFavorite(player, LISTING, SEED_MS, Deadline.after(30_000));
        Connection holder = begin(Connection.TRANSACTION_READ_COMMITTED);
        holders.add(holder);

        boolean observe = true;
        int retriesBefore = db.favoriteRetries.get();
        for (int round = 0; round < rounds; round++) {
            mustAffectOneRow(holder, "第 " + round + " 轮：取消收藏应当删掉上一轮留下的那 1 行", ListingSql.DELETE_FAVORITE, player, LISTING, null);
            long base = (round + 1) * 1000L;
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                long createdMs = base + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        store.insertFavorite(player, LISTING, createdMs, Deadline.after(30_000));
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }, POOL));
            }
            start.countDown();
            if (observe) {
                try {
                    awaitLockWaiters(workers, "未提交的取消收藏应当让全部 " + workers + " 个收藏卡在重复键检查上");
                } catch (LockWaitsUnobservable e) {
                    observe = false;
                    log.warn("读不了 performance_schema，之后各轮不再确认排队，退化为纯并发（不代表撞到了最坏形状）: {}", e.getCause().toString());
                } catch (AssertionError e) {
                    // 先放锁再收齐：不放锁的话它们要一直等到单次上限
                    holder.rollback();
                    CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
                    throw e;
                }
            }
            holder.commit();
            for (int i = 0; i < workers; i++) {
                Throwable error = results.get(i).get(WAIT_BUDGET_MS, TimeUnit.MILLISECONDS);
                if (error != null && JdbcListingStore.errorCode(error) == JdbcListingStore.ER_LOCK_DEADLOCK) {
                    fail("第 %d 轮收藏 #%d 返回 1213，连重试上限都用尽了：收藏写入又在删除标记记录上 S→X 互等 —— %s", round, i, error);
                }
                assertThat(error).as("第 %d 轮收藏 #%d", round, i).isNull();
            }
            long created = onlyFavoriteCreatedMs(player);
            assertThat(created).as("第 %d 轮：这一行必须是本轮复活出来的", round).isBetween(base, base + workers - 1);
        }
        log.info("压力用例 {} 轮完成，被有界重试吸收的可重试错误 {} 次（观察排队：{}）", rounds, db.favoriteRetries.get() - retriesBefore, observe);
    }

    /** 不连库也能跑的护栏自检：只有权限 / 对象缺失类错误号算「不可观测」（基线 TestLockWaitsQueryErrorClassification）。 */
    @Test
    void 不可观测的错误号分类() {
        for (int code : new int[] {1044, 1142, 1143, 1146, 1227}) {
            assertThat(isLockWaitsUnobservable(new SQLException("x", "42000", code))).as("错误号 %d", code).isTrue();
        }
        for (int code : new int[] {1213, 1205, 0, 2013}) {
            assertThat(isLockWaitsUnobservable(new SQLException("x", "HY000", code))).as("错误号 %d", code).isFalse();
        }
    }

    /** 轮询间隔（只为不空转打爆 MySQL，不是估时间）。 */
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
