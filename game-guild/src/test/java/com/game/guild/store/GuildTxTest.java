package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildReject;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 事务基座的分类与重试（不连库，基线 guild_manage_repo_test.go:302-449 的 Java 版 + Java 增项）：
 * 1213 / 9007 整体重跑至多 3 次、1205 不重试、子预算到期回 WRITE_CONFLICT 不重试、请求预算用完是依赖故障、COMMIT 结果不明回
 * WRITE_CONFLICT、CommitThenReject 先提交再拒绝、业务拒绝回滚、结果不跨重跑累积、连接状态还原。
 * 连接是 {@link Proxy} 做的假连接：只认事务控制方法，体里不发语句（直接抛构造好的 SQLException）。
 */
class GuildTxTest {

    /** 假连接：记录提交 / 回滚 / 自动提交状态；commit 依次弹出预设的错误。 */
    static final class FakeConnection {
        int commits;
        int rollbacks;
        int closes;
        boolean autoCommit = true;
        int isolation = Connection.TRANSACTION_READ_COMMITTED;
        final Deque<SQLException> commitErrors = new ArrayDeque<>();

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            yield null;
                        }
                        case "getTransactionIsolation" -> isolation;
                        case "setTransactionIsolation" -> {
                            isolation = (Integer) args[0];
                            yield null;
                        }
                        case "commit" -> {
                            commits++;
                            SQLException error = commitErrors.poll();
                            if (error != null) {
                                throw error;
                            }
                            yield null;
                        }
                        case "rollback" -> {
                            rollbacks++;
                            yield null;
                        }
                        case "close" -> {
                            closes++;
                            yield null;
                        }
                        case "isClosed" -> false;
                        case "toString" -> "FakeConnection";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> throw new SQLException("假连接不支持 " + method.getName());
                    });
        }
    }

    /** 记录器：三类指标各计数。 */
    static final class Recorder implements GuildTxListener {
        final List<String> events = new ArrayList<>();

        @Override
        public synchronized void deadlockObserved(GuildTxOp op) {
            events.add("deadlock:" + op.label());
        }

        @Override
        public synchronized void lockWaitTimeout(GuildTxOp op) {
            events.add("lock_wait:" + op.label());
        }

        @Override
        public synchronized void budgetExceeded(GuildTxOp op) {
            events.add("budget:" + op.label());
        }
    }

    private final FakeConnection conn = new FakeConnection();
    private final Recorder recorder = new Recorder();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<Long> connectionWaits = new ArrayList<>();

    private GuildTx tx() {
        return tx(GuildTxOp::budgetMillis, () -> 25L);
    }

    private GuildTx tx(java.util.function.ToLongFunction<GuildTxOp> budgets, java.util.function.LongSupplier backoff) {
        Connection proxy = conn.proxy();
        return new GuildTx(maxWait -> {
            connectionWaits.add(maxWait);
            return proxy;
        }, 3, recorder, budgets, backoff, sleeps::add);
    }

    private static SQLException code(int errorCode) {
        return new SQLException("mysql " + errorCode, "HY000", errorCode);
    }

    private static Deadline roomy() {
        return Deadline.after(30_000);
    }

    @Test
    void 前两次死锁第三次成功_每次各给一份子预算() {
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<String> out = tx().run(GuildTxOp.SET_ROLE, roomy(), t -> {
            assertThat(t.deadline().remainingMillis()).isBetween(1_000L, GuildLimits.TX_BUDGET_MS);
            if (calls.incrementAndGet() <= 2) {
                throw code(1213);
            }
            return TxOutcome.ok("done");
        });
        assertThat(out).isEqualTo(TxOutcome.ok("done"));
        assertThat(calls).hasValue(3);
        assertThat(recorder.events).containsExactly("deadlock:set_role", "deadlock:set_role");
        assertThat(sleeps).containsExactly(25L, 25L);
        assertThat(conn.rollbacks).isEqualTo(2);
        assertThat(conn.commits).isEqualTo(1);
        assertThat(conn.autoCommit).as("还原自动提交").isTrue();
        assertThat(conn.closes).as("每次尝试一条连接，都归还").isEqualTo(3);
    }

    @Test
    void 连续死锁耗尽重试_回写冲突_每判一次计一次() {
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<String> out = tx().run(GuildTxOp.REVIEW, roomy(), t -> {
            calls.incrementAndGet();
            throw code(1213);
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(calls).hasValue(GuildLimits.MAX_TX_ATTEMPTS);
        assertThat(recorder.events).containsExactly("deadlock:review", "deadlock:review", "deadlock:review");
        assertThat(sleeps).hasSize(2);
        assertThat(conn.commits).isZero();
    }

    @Test
    void TiDB写冲突与包装过的死锁同样重跑() {
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<Integer> out = tx().run(GuildTxOp.APPLY, roomy(), t -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw code(9007);
            }
            if (n == 2) {
                throw new SQLException("insert member", code(1213));
            }
            return TxOutcome.ok(n);
        });
        assertThat(out).isEqualTo(TxOutcome.ok(3));
        assertThat(recorder.events).containsExactly("deadlock:apply", "deadlock:apply");
    }

    @Test
    void 锁等待超时不重试_回写冲突() {
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<String> out = tx().run(GuildTxOp.KICK, roomy(), t -> {
            calls.incrementAndGet();
            throw new SQLException("lock guild", code(1205));
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(calls).hasValue(1);
        assertThat(recorder.events).containsExactly("lock_wait:kick");
        assertThat(conn.rollbacks).isEqualTo(1);
    }

    @Test
    void 其它SQL错误是依赖故障_不重试() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> tx().run(GuildTxOp.CREATE, roomy(), t -> {
            calls.incrementAndGet();
            throw code(1062);
        })).isInstanceOf(DependencyException.class);
        assertThat(calls).hasValue(1);
        assertThat(recorder.events).isEmpty();
        assertThat(conn.rollbacks).isEqualTo(1);
        assertThat(conn.autoCommit).isTrue();
    }

    @Test
    void 子预算用完而请求还活着_语句不发_回写冲突且不重试() {
        AtomicInteger calls = new AtomicInteger();
        GuildTx tx = tx(op -> 20L, () -> 25L);
        TxOutcome<String> out = tx.run(GuildTxOp.KICK, roomy(), t -> {
            calls.incrementAndGet();
            sleep(60);
            t.update(JdbcGuildStore.DELETE_MEMBER, 1L, 2L); // 子预算已过：语句前检查命中，假连接的 prepareStatement 不会被调用
            return TxOutcome.ok("unreachable");
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(calls).hasValue(1);
        assertThat(recorder.events).containsExactly("budget:kick");
        assertThat(conn.commits).isZero();
        assertThat(conn.rollbacks).isEqualTo(1);
    }

    @Test
    void 子预算在提交前用完_不提交() {
        GuildTx tx = tx(op -> 20L, () -> 25L);
        TxOutcome<String> out = tx.run(GuildTxOp.LEAVE, roomy(), t -> {
            sleep(60);
            return TxOutcome.ok("late");
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(conn.commits).as("COMMIT 也是一条语句").isZero();
        assertThat(recorder.events).containsExactly("budget:leave");
    }

    @Test
    void 查询超时按子预算到期定性() {
        TxOutcome<String> out = tx().run(GuildTxOp.TRANSFER, roomy(), t -> {
            throw new SQLTimeoutException("Statement cancelled due to timeout or client request");
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(recorder.events).containsExactly("budget:transfer");
    }

    @Test
    void 请求预算也用完了_是依赖故障而不是写冲突() {
        GuildTx tx = tx();
        Deadline request = Deadline.after(30);
        assertThatThrownBy(() -> tx.run(GuildTxOp.APPLY, request, t -> {
            assertThat(t.deadline().remainingMillis()).as("子预算 = min(请求剩余, 1500)").isLessThanOrEqualTo(30);
            sleep(60);
            t.update(JdbcGuildStore.DELETE_MEMBER, 1L, 2L);
            return TxOutcome.ok("unreachable");
        })).isInstanceOf(DependencyException.class);
        assertThat(recorder.events).as("不计子预算指标").isEmpty();
    }

    @Test
    void 请求预算开始前就用完_不取连接() {
        Deadline expired = Deadline.after(0);
        sleep(2);
        assertThatThrownBy(() -> tx().run(GuildTxOp.CANCEL, expired, t -> TxOutcome.ok("x")))
                .isInstanceOf(DependencyException.class);
        assertThat(connectionWaits).isEmpty();
    }

    @Test
    void 解散的子预算是2500() {
        tx().run(GuildTxOp.DISBAND, roomy(), t -> {
            assertThat(t.deadline().remainingMillis()).isBetween(2_000L, GuildLimits.TX_BUDGET_DISBAND_MS);
            return TxOutcome.ok(null);
        });
        assertThat(connectionWaits).hasSize(1);
        assertThat(connectionWaits.getFirst()).as("取连接最多等子预算剩余").isLessThanOrEqualTo(GuildLimits.TX_BUDGET_DISBAND_MS);
    }

    @Test
    void 提交结果不明_回写冲突且不重试() {
        conn.commitErrors.add(new SQLException("Communications link failure", "08S01"));
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<String> out = tx().run(GuildTxOp.REVIEW, roomy(), t -> {
            calls.incrementAndGet();
            return TxOutcome.ok("maybe");
        });
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(calls).hasValue(1);
        assertThat(recorder.events).isEmpty();
        assertThat(conn.commits).isEqualTo(1);
    }

    @Test
    void 提交时报死锁照常重跑_报锁等待照常归一() {
        conn.commitErrors.add(code(9007));
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<Integer> out = tx().run(GuildTxOp.ACTIVITY, roomy(), t -> TxOutcome.ok(calls.incrementAndGet()));
        assertThat(out).isEqualTo(TxOutcome.ok(2));
        assertThat(recorder.events).containsExactly("deadlock:activity");

        conn.commitErrors.add(code(1205));
        TxOutcome<Integer> lockWait = tx().run(GuildTxOp.DONATE, roomy(), t -> TxOutcome.ok(1));
        assertThat(lockWait.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
        assertThat(recorder.events).endsWith("lock_wait:donate");
    }

    @Test
    void 先提交再拒绝_与_拒绝即回滚() {
        TxOutcome<String> committed = tx().run(GuildTxOp.CANCEL, roomy(),
                t -> TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND));
        assertThat(committed).isInstanceOf(TxOutcome.CommitThenReject.class);
        assertThat(committed.rejection()).isEqualTo(GuildReject.APPLICATION_NOT_FOUND);
        assertThat(conn.commits).isEqualTo(1);

        TxOutcome<String> rejected = tx().run(GuildTxOp.KICK, roomy(), t -> TxOutcome.reject(GuildReject.RANK_TOO_LOW));
        assertThat(rejected).isInstanceOf(TxOutcome.Reject.class);
        assertThat(conn.commits).as("拒绝不提交").isEqualTo(1);
        assertThat(conn.rollbacks).isEqualTo(1);
        assertThat(conn.autoCommit).isTrue();
    }

    @Test
    void 提交结果不明也覆盖先提交再拒绝() {
        conn.commitErrors.add(new SQLException("broken pipe", "08S01"));
        TxOutcome<String> out = tx().run(GuildTxOp.REVIEW, roomy(),
                t -> TxOutcome.commitThenReject(GuildReject.APPLICATION_NOT_FOUND));
        assertThat(out.rejection()).isEqualTo(GuildReject.WRITE_CONFLICT);
    }

    @Test
    void 体抛运行时异常_回滚后原样抛出_不重试() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> tx().run(GuildTxOp.TRANSFER, roomy(), t -> {
            calls.incrementAndGet();
            throw new GuildStoreException(GuildStoreException.Kind.INVARIANT_BROKEN, "帮主人数不是 1");
        })).isInstanceOf(GuildStoreException.class);
        assertThat(calls).hasValue(1);
        assertThat(conn.rollbacks).isEqualTo(1);
        assertThat(conn.commits).isZero();
        assertThat(conn.autoCommit).as("先回滚再还原自动提交").isTrue();
    }

    @Test
    void 退避期间请求预算会用完_是依赖故障() {
        GuildTx tx = tx(GuildTxOp::budgetMillis, () -> 10_000L);
        assertThatThrownBy(() -> tx.run(GuildTxOp.REVIEW, Deadline.after(2_000), t -> {
            throw code(1213);
        })).isInstanceOf(DependencyException.class);
        assertThat(sleeps).isEmpty();
        assertThat(recorder.events).containsExactly("deadlock:review");
    }

    @Test
    void 重跑的结果不累积() {
        AtomicInteger calls = new AtomicInteger();
        TxOutcome<List<Long>> out = tx().run(GuildTxOp.DISBAND, roomy(), t -> {
            List<Long> ids = new ArrayList<>(); // 每次尝试从零开始（§1.6 结果变量约定）
            ids.add(1L);
            ids.add(2L);
            ids.add(3L);
            if (calls.incrementAndGet() == 1) {
                throw code(1213);
            }
            return TxOutcome.ok(ids);
        });
        assertThat(out.orThrow()).containsExactly(1L, 2L, 3L);
        assertThat(calls).hasValue(2);
    }

    @Test
    void 事务外读_SQL错误与预算用完都是依赖故障() {
        GuildTx tx = tx();
        assertThatThrownBy(() -> tx.read(roomy(), db -> {
            throw code(2013);
        })).isInstanceOf(DependencyException.class);
        Deadline expired = Deadline.after(0);
        sleep(2);
        assertThatThrownBy(() -> tx.read(expired, db -> 1)).isInstanceOf(DependencyException.class);
        Integer seven = tx.read(roomy(), db -> 7);
        assertThat(seven).isEqualTo(7);
    }

    @Test
    void 随机退避在10到50毫秒之间() {
        for (int i = 0; i < 200; i++) {
            assertThat(GuildTx.randomBackoffMillis()).isBetween(GuildLimits.TX_RETRY_BACKOFF_MIN_MS,
                    GuildLimits.TX_RETRY_BACKOFF_MAX_MS - 1);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
