package com.game.pbmysql;

/**
 * xm-pbmysql 自身判定的失败（表定义非法、结构漂移、键值越界、行转换失败、未注册的消息等）的基类。
 *
 * <p>数据库本身的错误仍以 {@link java.sql.SQLException} 抛出，调用方照常按 JDBC 处理；本类及子类都是非受检异常，
 * 除 {@link SchemaLockException}（咨询锁竞争，瞬时、可重试）外都表示「请求本身不成立」，重试不会变好。
 */
public class PbMysqlException extends RuntimeException {

    public PbMysqlException(String message) {
        super(message);
    }

    public PbMysqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
