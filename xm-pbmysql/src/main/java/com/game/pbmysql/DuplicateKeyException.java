package com.game.pbmysql;

import java.sql.SQLException;

/**
 * {@link PbMysql#save} 撞上了备用唯一键：冲突行的主键与入参不同，按整行保存语义不能去改写那一行。
 * cause 是 MySQL 1062 的原始异常。对应 Go 版 {@code ErrDuplicateKey}。
 */
public final class DuplicateKeyException extends PbMysqlException {

    public DuplicateKeyException(String message, SQLException cause) {
        super(message, cause);
    }
}
