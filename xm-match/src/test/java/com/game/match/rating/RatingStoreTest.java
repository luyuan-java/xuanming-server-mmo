package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.game.match.rating.RatingStore.Outcome;
import com.game.match.rating.RatingStore.Result;
import com.game.match.support.MatchModes;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link RatingStore} 在 H2（MySQL 兼容模式）上的全部行为用例（缺省执行；用例本身在 {@link RatingStoreCases}）。每个用例一个全新的内存库，
 * 两张表用 pbmysql 生成的同一份 DDL 建。H2 与 MySQL 在错误码上的差异（主键冲突 23505 对 1062）由 {@link RatingSqlErrors} 按标准 SQLState 兜住；
 * 真的死锁、锁等待超时与多连接压测只在真 MySQL 上才有，见 {@link RatingStoreSqlTest}。
 *
 * <p>这里另加一条缺省就跑的并发用例：同一玩家的两局被两条连接交错入账。它钉的是入账事务的<b>形状</b>——评分行的锁在读赛前评分之前就拿到、
 * 一直持有到提交，所以后一局一定等前一局提交、按更新后的分算。H2 同样有行锁与 READ COMMITTED；把这名玩家的行锁整个拿掉（不补行、读不加锁），
 * 后一局就不会等，这条用例会在「没有看到卡在行锁上的会话」处失败（做过这个变异验证）。补行的 {@code ON DUPLICATE KEY UPDATE} 与加锁读的
 * {@code FOR UPDATE} 各自都会锁行，单去掉其中一个这条用例看不出来——两条语句的文本由 {@code MatchRatingTablesTest} 逐字钉住。
 * InnoDB 上的同一条断言在 {@link RatingStoreSqlTest} 里。
 */
class RatingStoreTest extends RatingStoreCases {

    /** H2：正卡在别的会话的行锁上的会话数。 */
    private static final String BLOCKED_SESSIONS = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL";

    @Override
    protected RatingTestDatabase openDatabase() {
        return RatingTestDatabase.h2();
    }

    @Override
    protected void closeDatabase(RatingTestDatabase database) {
        database.close();
    }

    /** 等到有会话卡在行锁上（最多 8 s）。 */
    private void awaitBlockedSession() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            long blocked = db.query(BLOCKED_SESSIONS, rs -> {
                rs.next();
                return rs.getLong(1);
            });
            if (blocked > 0) {
                return;
            }
            Thread.sleep(5);
        }
        fail("8 s 内没有看到卡在行锁上的会话");
    }

    @Test
    void 同一玩家的两局被两条连接交错入账_后一局等前一局提交_按更新后的分算_两局都落账() throws Exception {
        long p = 14001;
        long oppX = 14002;
        long oppY = 14003;
        db.putRating(p, 150_000, 0);
        CountDownLatch xHoldsLock = new CountDownLatch(1);
        // 局 X 锁住 p 的评分行（加锁读）之后停住，等看到局 Y 卡在这把锁上再继续
        ds.afterOnce(LOCK_ROW, () -> {
            xHoldsLock.countDown();
            awaitBlockedSession();
        });

        Result resultX;
        Result resultY;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Result> x = pool.submit(() -> apply(event(9401, MatchModes.ONE_V_ONE, A_WIN, List.of(p), List.of(oppX))));
            assertThat(xHoldsLock.await(10, TimeUnit.SECONDS)).as("局 X 走到了加锁读").isTrue();
            Future<Result> y = pool.submit(() -> apply(event(9402, MatchModes.ONE_V_ONE, A_WIN, List.of(p), List.of(oppY))));

            resultX = x.get(30, TimeUnit.SECONDS);
            resultY = y.get(30, TimeUnit.SECONDS);
        }

        assertThat(resultX).isEqualTo(new Result(Outcome.APPLIED, null, 1_600, false));
        // 局 Y 读到的是局 X 提交后的 1516：E = 0.5230，Δ = 15.26（基线并发时两局都按旧分算，各 +16；这里行锁串行，M18）
        assertThat(resultY).isEqualTo(new Result(Outcome.APPLIED, null, 1_526, false));
        assertThat(db.ratingRow(p)).as("两局的增量都落账，局数 2").hasValueSatisfying(row -> {
            assertThat(row[0]).isEqualTo(153_126);
            assertThat(row[1]).isEqualTo(2);
        });
        assertThat(db.ratingCenti(oppX)).hasValue(148_400);
        assertThat(db.ratingCenti(oppY)).hasValue(148_474);
        assertThat(db.count("match_rating_applied")).isEqualTo(2);
        assertThat(ds.errors).as("只是排队：没有死锁、没有锁等待超时").isEmpty();
        assertThat(sleeps).as("没有整笔重跑").isEmpty();
    }
}
