package com.game.pbmysql;

/**
 * 消息 ↔ 列值转换失败：浮点 NaN / ±Inf（MySQL 无法表示）、真实 oneof、列值超出字段取值范围、
 * BLOB 列不是合法的 protobuf wire 字节等。对应 Go 版 pbconv 的 {@code ErrNonFiniteFloat} / {@code ErrInvalidFieldKind}
 * 与解析错误。
 */
public final class RowConversionException extends PbMysqlException {

    public RowConversionException(String message) {
        super(message);
    }

    public RowConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
