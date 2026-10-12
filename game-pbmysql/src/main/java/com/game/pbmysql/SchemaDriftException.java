package com.game.pbmysql;

import java.util.List;

/**
 * 线上表结构与 proto 声明不一致，且对齐它需要 expand-only 之外的 DDL（MODIFY / CHANGE / DROP）或人工迁移数据。
 *
 * <p>抛出时本表<b>没有执行任何 DDL</b>（规划在执行之前完成）。{@link #differences()} 逐条列出差异，
 * {@link #suggestedStatements()} 给出对齐所需的语句——只供人工审核后执行，本库从不自动执行它们：
 * 滚动发布时新旧两版进程各自把自己的 proto 当成目标结构，MODIFY / CHANGE 会被来回执行；收窄与改名还可能丢数据。
 *
 * <p>对应 Go 版 ExpandOnly 模式下的 {@code ErrSchemaDrift} / {@code ErrUnsafeSchemaConversion} /
 * {@code ErrFieldNumberReused} / {@code ErrExpandOnlyViolation} / {@code ErrLegacyKeyColumn}，这里汇总成一个异常。
 */
public final class SchemaDriftException extends PbMysqlException {

    private final String tableName;
    private final List<String> differences;
    private final List<String> suggestedStatements;

    public SchemaDriftException(String tableName, List<String> differences, List<String> suggestedStatements) {
        super(format(tableName, differences, suggestedStatements));
        this.tableName = tableName;
        this.differences = List.copyOf(differences);
        this.suggestedStatements = List.copyOf(suggestedStatements);
    }

    /** 出现漂移的表名。 */
    public String tableName() {
        return tableName;
    }

    /** 逐条差异说明。 */
    public List<String> differences() {
        return differences;
    }

    /** 对齐所需、但本库不会自动执行的语句（不带结尾分号）；可能为空（需要人工迁移数据时给不出可直接执行的语句）。 */
    public List<String> suggestedStatements() {
        return suggestedStatements;
    }

    private static String format(String tableName, List<String> differences, List<String> statements) {
        StringBuilder b = new StringBuilder();
        b.append("表 ").append(tableName).append(" 的线上结构与 proto 声明不一致，expand-only 同步只会 ADD，")
                .append("拒绝自动执行 MODIFY / CHANGE / DROP（本表未执行任何 DDL）：");
        for (String d : differences) {
            b.append("\n  - ").append(d);
        }
        if (!statements.isEmpty()) {
            b.append("\n对齐所需的语句（人工审核、确认数据后再执行；滚动发布期间请走 expand→migrate→contract）：");
            for (String s : statements) {
                b.append("\n    ").append(s).append(';');
            }
        }
        return b.toString();
    }
}
