package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.guild.asset.AssetOpDecisions;
import com.game.guild.rules.GuildLimits;
import com.game.guild.store.GuildTxTest.FakeConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 后台写事务基座（不连库；照 mmorpg assetop/seq_test.go 的 WithTxRetry 用例与 economy_repo_test.go:119 TestIsRetryableBackground；
 * guild-economy-spec §1.5「后台写」、§7.3）：1213 / 9007 / <b>1205</b> 都整体重跑、其余错误不重试、体抛的运行期异常回滚后原样抛出、
 * COMMIT 失败的分类、截止用尽、退避与重投循环同一公式、结果不跨重跑累积、连接状态还原。
 */
class BackgroundTxTest {

    private final FakeConnection conn = new FakeConnection();
    private final List<String> events = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();

    private BackgroundTx tx() {
        Connection proxy = conn.proxy();
        return new BackgroundTx(maxWait -> proxy, 3, new BackgroundTx.Listener() {
            @Override
            public void deadlockObserved(BackgroundTx.Op op) {
                events.add("deadlock:" + op.label());
            }

            @Override
            public void lockWaitTimeout(BackgroundTx.Op op) {
                events.add("lock_wait:" + op.label());
            }
        }, () -> 0.5, sleeps::add);
    }

    private static SQLException code(int errorCode) {
        return new SQLException("mysql " + errorCode, "HY000", errorCode);
    }

    private static Deadline roomy() {
        return Deadline.after(30_000);
    }

    @Test
    void 可重试分类_1213_9007_1205_沿cause链() {
        assertThat(BackgroundTx.isRetryable(code(1213))).isTrue();
        assertThat(BackgroundTx.isRetryable(code(9007))).isTrue();
        assertThat(BackgroundTx.isRetryable(code(1205))).as("后台写重试 1205").isTrue();
        assertThat(BackgroundTx.isRetryable(code(1062))).as("撞唯一键判成可重试会让注定失败的终结反复重放").isFalse();
        assertThat(BackgroundTx.isRetryable(new RuntimeException("terminal CAS", code(1205)))).isTrue();
        assertThat(BackgroundTx.isRetryable(new RuntimeException("connection refused"))).isFalse();
        assertThat(BackgroundTx.isRetryable(null)).isFalse();
    }

    @Test
    void 死锁与锁等待都整体重跑_成功返回最后一次的结果() {
        AtomicInteger attempts = new AtomicInteger();
        String result = tx().run(BackgroundTx.Op.FINALIZE, roomy(), 3, t -> {
            int n = attempts.incrementAndGet();
            if (n == 1) {
                throw code(1213);
            }
            if (n == 2) {
                throw code(1205);
            }
            return "第 " + n + " 次";
        });
        assertThat(result).as("重试契约：结果只在成功返回前交给外层").isEqualTo("第 3 次");
        assertThat(events).containsExactly("deadlock:finalize", "lock_wait:finalize");
        assertThat(conn.rollbacks).isEqualTo(2);
        assertThat(conn.commits).isEqualTo(1);
        assertThat(conn.closes).isEqualTo(3);
        // 两次退避：10 ms × 1.0、20 ms × 1.0（抖动注入 0.5 = 中值）
        assertThat(sleeps).containsExactly(10L, 20L);
        assertThat(conn.autoCommit).as("还原自动提交").isTrue();
    }

    @Test
    void 重试用尽是依赖故障_最后一次失败后不再睡() {
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.RESCHEDULE, roomy(), 3, t -> {
            throw code(9007);
        })).isInstanceOf(DependencyException.class).hasMessageContaining("reschedule").hasRootCauseMessage("mysql 9007");
        assertThat(events).containsExactly("deadlock:reschedule", "deadlock:reschedule", "deadlock:reschedule");
        assertThat(sleeps).hasSize(2);

        events.clear();
        sleeps.clear();
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.CLEANUP, roomy(), 1, t -> {
            throw code(1205);
        })).as("清理每行只尝试 1 次").isInstanceOf(DependencyException.class);
        assertThat(events).containsExactly("lock_wait:cleanup");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 非可重试错误不重试_体的运行期异常回滚后原样抛出() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, roomy(), 3, t -> {
            attempts.incrementAndGet();
            throw code(1062);
        })).isInstanceOf(DependencyException.class);
        assertThat(attempts).hasValue(1);

        IllegalStateException boom = new IllegalStateException("写入自检失败");
        int rollbacksBefore = conn.rollbacks;
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, roomy(), 3, t -> {
            throw boom;
        })).isSameAs(boom);
        assertThat(conn.rollbacks).as("没走到提交就回滚，再还原自动提交").isEqualTo(rollbacksBefore + 1);
        assertThat(conn.autoCommit).isTrue();
        assertThat(conn.commits).isZero();
    }

    @Test
    void 提交失败_可重试的照常重跑_其余是依赖故障() {
        conn.commitErrors.add(code(1213));
        AtomicInteger attempts = new AtomicInteger();
        Integer second = tx().run(BackgroundTx.Op.MANUAL_RESOLVE, roomy(), 3, t -> attempts.incrementAndGet());
        assertThat(second).isEqualTo(2);
        assertThat(events).containsExactly("deadlock:manual_resolve");

        conn.commitErrors.add(new SQLException("Communications link failure", "08S01", 0));
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, roomy(), 3, t -> 1))
                .as("结果不明：终结的 CAS 若已生效，下一轮 CAS 落空，不会做第二遍对侧账").isInstanceOf(DependencyException.class);
    }

    @Test
    void 截止用尽_开始前_退避前_提交前() {
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, Deadline.after(0), 3, t -> 1))
                .isInstanceOf(DependencyException.class);
        assertThat(conn.closes).as("截止已过就不取连接").isZero();

        // 退避前剩余预算不够睡：不在一个已经没有预算的截止上睡满
        Deadline tight = Deadline.after(5);
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.RESCHEDULE, tight, 3, t -> {
            throw code(1213);
        })).isInstanceOf(DependencyException.class);
        assertThat(sleeps).isEmpty();

        // 体跑完时截止已过：不提交
        int commits = conn.commits;
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, Deadline.after(30), 1, t -> {
            GuildMysqlFixture.sleep(60);
            return 1;
        })).isInstanceOf(DependencyException.class);
        assertThat(conn.commits).isEqualTo(commits);
        assertThatThrownBy(() -> tx().run(BackgroundTx.Op.FINALIZE, roomy(), 0, t -> 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 每次尝试显式读已提交_还原原隔离级() {
        conn.isolation = Connection.TRANSACTION_REPEATABLE_READ;
        List<Integer> seen = new ArrayList<>();
        tx().run(BackgroundTx.Op.FINALIZE, roomy(), 1, t -> {
            seen.add(conn.isolation);
            return null;
        });
        assertThat(seen).containsExactly(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(conn.isolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }

    /** 后台事务退避与重投循环的退避是同一个公式（seq.go:420-425 txBackoff = NextAttemptMs(0, i, 10ms, 200ms, rnd)）。 */
    @Test
    void 退避与重投循环同一公式() {
        for (int i = 0; i < 40; i++) {
            for (double j : new double[] {0, 0.25, 0.5, 0.999999, -1, 3, Double.NaN}) {
                int attempt = i;
                assertThat(BackgroundTx.backoffMillis(attempt, () -> j)).as("i=%d j=%s", attempt, j)
                        .isEqualTo(AssetOpDecisions.nextAttemptMs(0, attempt, GuildLimits.BACKGROUND_TX_BASE_BACKOFF_MS,
                                GuildLimits.BACKGROUND_TX_MAX_BACKOFF_MS, () -> j));
            }
        }
        assertThat(BackgroundTx.backoffMillis(0, () -> 0)).isEqualTo(8);
        assertThat(BackgroundTx.backoffMillis(10, () -> 0.999999)).as("封顶 200 ms × 1.2").isLessThanOrEqualTo(240);
    }

    @Test
    void 自动提交语句_错误是依赖故障() {
        conn.autoCommit = false;
        Boolean autoCommitted = tx().autocommit(roomy(), db -> conn.autoCommit);
        assertThat(autoCommitted).as("自动提交语句必须在自动提交连接上跑").isTrue();
        assertThatThrownBy(() -> tx().autocommit(roomy(), db -> {
            throw code(1205);
        })).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> tx().autocommit(Deadline.after(0), db -> 1)).isInstanceOf(DependencyException.class);
    }
}
