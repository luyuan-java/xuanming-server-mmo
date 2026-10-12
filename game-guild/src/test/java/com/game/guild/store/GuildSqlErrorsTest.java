package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import org.junit.jupiter.api.Test;

/**
 * 错误分类（基线 guild_manage_repo_test.go:304-334 TestMySQLErrClassifiers、guild_repo_zone_test.go:21-40 TestIsDuplicateKeyOn）：
 * 1213 / 9007 可重试、1205 不可重试，分类必须穿透包装（沿 cause 链取错误号）；1062 要分辨撞的是哪个唯一键（按消息结尾）。
 */
class GuildSqlErrorsTest {

    private static SQLException mysql(int code, String message) {
        return new SQLException(message, "HY000", code);
    }

    @Test
    void 死锁与TiDB写冲突可重试_锁等待超时不可重试() {
        SQLException deadlock = new SQLTransactionRollbackException("Deadlock found", "40001", 1213);
        SQLException tidb = mysql(9007, "Write conflict");
        SQLException lockWait = mysql(1205, "Lock wait timeout exceeded");
        SQLException duplicate = new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062);

        assertThat(GuildSqlErrors.isRetryable(deadlock)).isTrue();
        assertThat(GuildSqlErrors.isLockWaitTimeout(deadlock)).isFalse();
        assertThat(GuildSqlErrors.isRetryable(tidb)).as("TiDB 9007 与死锁同等对待").isTrue();
        assertThat(GuildSqlErrors.isLockWaitTimeout(tidb)).isFalse();
        assertThat(GuildSqlErrors.isLockWaitTimeout(lockWait)).isTrue();
        assertThat(GuildSqlErrors.isRetryable(lockWait)).as("锁等待已被 innodb_lock_wait_timeout=1 封顶，重试只是白等").isFalse();
        assertThat(GuildSqlErrors.isDuplicateKey(duplicate)).isTrue();
        for (Throwable other : new Throwable[] {duplicate, new SQLException("boom"), new RuntimeException("boom"), null}) {
            assertThat(GuildSqlErrors.isRetryable(other)).isFalse();
            assertThat(GuildSqlErrors.isLockWaitTimeout(other)).isFalse();
        }
    }

    @Test
    void 分类穿透包装_沿cause链取第一个非0错误号() {
        SQLException deadlock = mysql(1213, "Deadlock found");
        assertThat(GuildSqlErrors.isRetryable(new SQLException("insert member", deadlock))).as("外层 SQLException 的错误号是 0").isTrue();
        assertThat(GuildSqlErrors.isRetryable(new RuntimeException("wrapped", new SQLException("x", deadlock)))).isTrue();
        assertThat(GuildSqlErrors.isLockWaitTimeout(new IllegalStateException(mysql(1205, "lock wait")))).isTrue();
        assertThat(GuildSqlErrors.errorCode(new RuntimeException("no sql"))).isZero();
        // 外层自己带了非 0 错误号：以外层为准（不再往里找）
        assertThat(GuildSqlErrors.errorCode(new SQLException("outer", "HY000", 1062, deadlock))).isEqualTo(1062);
    }

    @Test
    void 查询超时沿cause链识别() {
        assertThat(GuildSqlErrors.isQueryTimeout(new SQLTimeoutException("Statement cancelled due to timeout"))).isTrue();
        assertThat(GuildSqlErrors.isQueryTimeout(new SQLException("wrap", new SQLTimeoutException("t")))).isTrue();
        assertThat(GuildSqlErrors.isQueryTimeout(mysql(1205, "lock wait"))).isFalse();
    }

    @Test
    void 撞的是哪个唯一键_按消息结尾判断() {
        String key = JdbcGuildStore.GUILD_NAME_UNIQUE_KEY;
        assertThat(GuildSqlErrors.isDuplicateKeyOn(mysql(1062, "Duplicate entry '青云门' for key 'guild.uk_guild'"), key))
                .as("MySQL 8 带表名前缀").isTrue();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(mysql(1062, "Duplicate entry '青云门' for key 'uk_guild'"), key))
                .as("MySQL 5.7 不带前缀").isTrue();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(
                new SQLException("insert guild", mysql(1062, "Duplicate entry 'x' for key 'guild.uk_guild'")), key))
                .as("包装过一层").isTrue();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(mysql(1062, "Duplicate entry '7' for key 'guild.PRIMARY'"), key))
                .as("主键冲突不是撞名").isFalse();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(
                mysql(1062, "Duplicate entry 'a.uk_guild'' for key 'guild_member.uk_guild_member'"), key))
                .as("名字里含索引名不能误判").isFalse();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(mysql(1452, "for key 'uk_guild'"), key)).as("别的 MySQL 错误").isFalse();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(new RuntimeException("Duplicate entry for key 'uk_guild'"), key))
                .as("不是 SQL 错误").isFalse();
        assertThat(GuildSqlErrors.isDuplicateKeyOn(null, key)).isFalse();
    }
}
