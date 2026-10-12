package com.game.guild.store;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * SQL 错误分类（基线 guild_manage_repo.go:276-307 的 isRetryableTxError / isLockWaitTimeout / mysqlErrNumber，
 * guild_repo.go:514-549 的 isDuplicateKey / isDuplicateKeyOn）。
 *
 * <p>错误号沿 cause 链按 {@link SQLException#getErrorCode()} 取，不做文本匹配（同 JdbcFriendStore.isDeadlock）：调用链上任何一层把
 * SQL 错误包进别的异常，都不能让死锁被当成内部错误抛给玩家。唯一的文本判断是「撞的是哪个唯一键」——1062 只说「有重复」，
 * 要区分索引只能看消息结尾（见 {@link #isDuplicateKeyOn}）。纯函数，任意线程可调。
 */
public final class GuildSqlErrors {

    /** InnoDB 死锁：事务已被整体回滚，可重跑。 */
    public static final int ER_LOCK_DEADLOCK = 1213;
    /** TiDB 写冲突（乐观事务提交期检测到冲突，语义与死锁等价；Java 首批不上 TiDB，照基线一并认）。 */
    public static final int TIDB_WRITE_CONFLICT = 9007;
    /** 锁等待超时（innodb_lock_wait_timeout=1 封顶）：不重试。 */
    public static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    /** 唯一键 / 主键冲突。 */
    public static final int ER_DUP_ENTRY = 1062;

    private GuildSqlErrors() {
    }

    /** 沿 cause 链找到的第一个非 0 MySQL 错误号；没有 SQLException 或都是 0 时返回 0。 */
    public static int errorCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() != 0) {
                return sql.getErrorCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return 0;
    }

    /** 1213 / 9007：事务已整体回滚，重跑是安全的（isRetryableTxError）。 */
    public static boolean isRetryable(Throwable error) {
        int code = errorCode(error);
        return code == ER_LOCK_DEADLOCK || code == TIDB_WRITE_CONFLICT;
    }

    /** 1205（isLockWaitTimeout）。 */
    public static boolean isLockWaitTimeout(Throwable error) {
        return errorCode(error) == ER_LOCK_WAIT_TIMEOUT;
    }

    /** 1062（isDuplicateKey）：建帮插成员行撞任何唯一键都算已入帮；审批通过插成员行撞了 = 申请人已入他帮。 */
    public static boolean isDuplicateKey(Throwable error) {
        return errorCode(error) == ER_DUP_ENTRY;
    }

    /**
     * 是否撞了指定的唯一索引（isDuplicateKeyOn）。MySQL 8 / TiDB 的消息形如 {@code Duplicate entry 'x' for key 'guild.uk_guild'}，
     * 5.7 不带表名前缀；按<b>结尾</b>匹配——帮名本身出现在消息中段，名字里含 "uk_guild" 不能让主键冲突被误判成重名。
     */
    public static boolean isDuplicateKeyOn(Throwable error, String key) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() != 0) {
                if (sql.getErrorCode() != ER_DUP_ENTRY) {
                    return false;
                }
                String message = sql.getMessage();
                return message != null && (message.endsWith("'" + key + "'") || message.endsWith("." + key + "'"));
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** JDBC 查询超时（{@code setQueryTimeout} 触发，Connector/J 的 MySQLTimeoutException 是它的子类），沿 cause 链找。 */
    public static boolean isQueryTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
