package com.game.pbmysql;

/**
 * 主键 / 唯一键里 string / bytes 列的值放不进目标列：超过 max_length，或 string 不是合法 UTF-8。
 *
 * <p>在发出任何 SQL 之前抛出：严格模式下 MySQL 会报 1406 / 1366，非严格模式会把超长值静默截断，
 * 两个只在尾部不同的第三方 ID 截断后落成同一个键。消息只带表、列、实际长度与上限，不回显值
 * （键列里常是第三方账号 ID、令牌这类不该进日志的内容）。对应 Go 版 {@code ErrInvalidKeyValue}。
 */
public final class InvalidKeyValueException extends PbMysqlException {

    public InvalidKeyValueException(String message) {
        super(message);
    }
}
