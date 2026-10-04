package com.game.pbmysql;

/**
 * 结构同步的 DDL 咨询锁没取到（另一个副本正在同步、持锁超过 30 秒）或锁状态未知（已丢弃那条连接的会话）。
 *
 * <p>与 {@link PbMysqlException} 的其他子类不同，这是<b>瞬时</b>状态：另一个副本的长 ALTER 结束后重试即可成功，
 * 调用方可以按启动失败重启、或退避后重试；锁状态未知时那条连接已被关闭，重试须换一条新连接。
 */
public final class SchemaLockException extends PbMysqlException {

    public SchemaLockException(String message) {
        super(message);
    }
}
