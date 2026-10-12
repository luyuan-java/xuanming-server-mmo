package com.game.pbmysql;

/**
 * 表定义在产出任何 DDL 之前就被拒绝：字段类型没有列映射、真实 oneof、表名 / 字段名超长、表选项非法（主键 / 索引 /
 * 唯一键引用不存在的字段、空分量、浮点主键、键列可空、max_length 越界、索引键长超 3072 字节、自增列不是键首列……）。
 *
 * <p>对应 Go 版的 {@code ErrUnsupportedFieldKind} / {@code ErrInvalidTableOption} / {@code ErrDuplicateTableMapping}，
 * 消息文本与 Go 版一致（去掉了英文哨兵前缀）。
 */
public final class InvalidTableDefinitionException extends PbMysqlException {

    /** 拒绝原因的分类，对应 Go 版的三个哨兵错误。 */
    public enum Reason {
        /** 字段类型 / 表名 / 字段名不能映射成 MySQL 表（Go: ErrUnsupportedFieldKind）。 */
        UNSUPPORTED_FIELD_KIND,
        /** 表选项组合必然产出无效或有歧义的 DDL（Go: ErrInvalidTableOption）。 */
        INVALID_TABLE_OPTION,
        /** 两个不同的 protobuf message 映射到同一张物理表（Go: ErrDuplicateTableMapping）。 */
        DUPLICATE_TABLE_MAPPING
    }

    private final Reason reason;

    public InvalidTableDefinitionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
