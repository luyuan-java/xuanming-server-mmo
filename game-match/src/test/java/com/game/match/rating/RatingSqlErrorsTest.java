package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;

/** SQL 错误的三类划分（match-spec §5.2 第 2、7 步，§5.3）：重复、可整笔重跑、数据错误；其余都是可恢复故障。 */
class RatingSqlErrorsTest {

    @Test
    void 主键冲突_MySQL的1062与标准的23505都认() {
        assertThat(RatingSqlErrors.isDuplicateKey(new SQLIntegrityConstraintViolationException("Duplicate entry '9001' for key 'PRIMARY'", "23000", 1062)))
                .isTrue();
        assertThat(RatingSqlErrors.isDuplicateKey(new SQLException("Unique index or primary key violation", "23505", 23505))).isTrue();
        assertThat(RatingSqlErrors.isDuplicateKey(new SQLException("Column 'x' cannot be null", "23000", 1048))).as("别的完整性约束不是重复").isFalse();
        assertThat(RatingSqlErrors.isDuplicateKey(new SQLException("Deadlock", "40001", 1213))).isFalse();
        assertThat(RatingSqlErrors.isDuplicateKey(new IllegalStateException("不是 SQL 错误"))).isFalse();
    }

    @Test
    void 死锁与锁等待超时可以整笔重跑_其余不行() {
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Deadlock found when trying to get lock", "40001", 1213))).isTrue();
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Lock wait timeout exceeded", "HY000", 1205))).isTrue();
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Deadlock detected", "40001", 40001))).as("标准 SQLState 40001").isTrue();
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Communications link failure", "08S01", 0))).isFalse();
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Duplicate entry", "23000", 1062))).isFalse();
        assertThat(RatingSqlErrors.isRetryable(new SQLException("Table doesn't exist", "42S02", 1146))).isFalse();
    }

    @Test
    void 数据错误只认SQLState的22与23两类() {
        assertThat(RatingSqlErrors.isDataError(new SQLException("Out of range value", "22003", 1264))).isTrue();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Data too long", "22001", 1406))).isTrue();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Column cannot be null", "23000", 1048))).isTrue();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Communications link failure", "08S01", 0))).as("库不可达是可恢复故障").isFalse();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Deadlock", "40001", 1213))).isFalse();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Lock wait timeout exceeded", "HY000", 1205))).isFalse();
        assertThat(RatingSqlErrors.isDataError(new SQLException("Table doesn't exist", "42S02", 1146))).as("表还没建：可恢复").isFalse();
        assertThat(RatingSqlErrors.isDataError(new SQLException("没有 SQLState"))).isFalse();
        assertThat(RatingSqlErrors.isDataError(new RuntimeException("不是 SQL 错误"))).isFalse();
    }

    @Test
    void 沿cause链找_包了几层也认得出() {
        SQLException deadlock = new SQLException("Deadlock", "40001", 1213);
        RuntimeException wrapped = new IllegalStateException("外层", new RatingStore.StoreException("入账失败", deadlock));

        assertThat(RatingSqlErrors.isRetryable(wrapped)).isTrue();
        assertThat((Throwable) RatingSqlErrors.sqlCause(wrapped)).isSameAs(deadlock);
        assertThat((Throwable) RatingSqlErrors.sqlCause(new RuntimeException("没有 SQL 原因"))).isNull();
        assertThat((Throwable) RatingSqlErrors.sqlCause(null)).isNull();
    }

    @Test
    void 没有错误号也没有SQLState的SQL异常_继续往里找() {
        SQLException inner = new SQLException("Out of range", "22003", 1264);
        SQLException outer = new SQLException("包装", inner);

        assertThat((Throwable) RatingSqlErrors.sqlCause(outer)).isSameAs(inner);
        assertThat(RatingSqlErrors.isDataError(outer)).isTrue();
    }
}
