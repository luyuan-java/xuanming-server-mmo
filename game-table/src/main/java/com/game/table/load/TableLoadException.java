package com.game.table.load;

/**
 * 配置表加载失败：目录 / manifest / 数据文件缺失、被改动、与 schema 不一致，键重复，外键解析不到。
 * 进程应当因此启动失败——宁可起不来，也不带着半套配置对外服务。
 */
public final class TableLoadException extends RuntimeException {

    public TableLoadException(String message) {
        super(message);
    }

    public TableLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 主键或唯一键重复（由生成的 {@code <Sheet>Rows} 构造时抛出）。 */
    public static TableLoadException duplicateKey(String sheet, String column, Object key) {
        return new TableLoadException("配置表 " + sheet + " 的键 " + column + " 重复: " + key);
    }
}
