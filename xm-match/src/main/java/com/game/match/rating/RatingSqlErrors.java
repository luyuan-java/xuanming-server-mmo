package com.game.match.rating;

import java.sql.SQLException;

/**
 * 评分入账遇到的 SQL 错误分类（match-spec §5.2 第 2、7 步，§5.3）。错误号 / SQLState 沿 cause 链取，不做文本匹配
 * （写法同 xm-guild 的 {@code GuildSqlErrors}；不能直接依赖那个模块）。纯函数。
 *
 * <table>
 *   <caption>三类</caption>
 *   <tr><td>{@link #isDuplicateKey}</td><td>入账标记撞主键 = 这一局已经入过账（重复投递），不是故障</td></tr>
 *   <tr><td>{@link #isRetryable}</td><td>死锁 / 锁等待超时：事务已整体回滚，整笔重跑是安全的</td></tr>
 *   <tr><td>{@link #isDataError}</td><td>这一条数据本身写不进去（越界、约束）：重试多少次都一样，写毒丸日志后跳过</td></tr>
 * </table>
 * 三类都不是的（库不可达、超时、表还没建……）一律当可恢复故障：消费者暂停、退避、原记录重试，不提交位点、不跳过。
 */
public final class RatingSqlErrors {

    /** InnoDB 死锁：事务已被整体回滚。 */
    static final int ER_LOCK_DEADLOCK = 1213;
    /** 锁等待超时（连接串里 {@code innodb_lock_wait_timeout=1} 封顶）。 */
    static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    /** 主键 / 唯一键冲突。 */
    static final int ER_DUP_ENTRY = 1062;
    /** 标准 SQLState：唯一约束冲突（MySQL 对 1062 给的是 23000；H2 等给这个）。 */
    static final String STATE_UNIQUE_VIOLATION = "23505";
    /** 标准 SQLState：串行化失败 / 死锁（MySQL 的 1213 也是它）。 */
    static final String STATE_SERIALIZATION_FAILURE = "40001";

    private RatingSqlErrors() {
    }

    /** cause 链上第一个带错误号或 SQLState 的 {@link SQLException}；没有为 null。 */
    static SQLException sqlCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && (sql.getErrorCode() != 0 || sql.getSQLState() != null)) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** 主键冲突：MySQL 1062，或标准 SQLState 23505。 */
    public static boolean isDuplicateKey(Throwable error) {
        SQLException sql = sqlCause(error);
        return sql != null && (sql.getErrorCode() == ER_DUP_ENTRY || STATE_UNIQUE_VIOLATION.equals(sql.getSQLState()));
    }

    /** 死锁（1213 / SQLState 40001）或锁等待超时（1205）：整笔重跑。 */
    public static boolean isRetryable(Throwable error) {
        SQLException sql = sqlCause(error);
        return sql != null && (sql.getErrorCode() == ER_LOCK_DEADLOCK || sql.getErrorCode() == ER_LOCK_WAIT_TIMEOUT
                || STATE_SERIALIZATION_FAILURE.equals(sql.getSQLState()));
    }

    /**
     * 数据错误：SQLState 22（数据异常：越界、截断）或 23（完整性约束）。调用方先判 {@link #isDuplicateKey}——入账标记的主键冲突是「重复」，
     * 不走这里；评分行的补行用 {@code ON DUPLICATE KEY UPDATE}，不会报主键冲突。
     */
    public static boolean isDataError(Throwable error) {
        SQLException sql = sqlCause(error);
        if (sql == null || sql.getSQLState() == null) {
            return false;
        }
        String state = sql.getSQLState();
        return state.startsWith("22") || state.startsWith("23");
    }
}
