package com.game.login.character;

import com.game.table.AllTable;
import com.game.table.ClassTableData;
import com.game.table.ClassTableManager;
import com.game.table.RoleNameRuleTableManager;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以导表器生成的全局表管理器为数据源的 {@link CharacterRules}。
 *
 * <p>只能经 {@link #load(Path)} 得到：它同步加载全部配置表（{@code AllTable.loadTables(dir, true)}，读 .pb）
 * 并立刻自检建角要用的两张表，任何一项不合格都抛异常让进程起不来——宁可启动失败，也不等到有人建角才发现。
 * 表在进程对外服务（Dubbo 暴露）之前加载完，此后只读。
 */
public final class TableCharacterRules implements CharacterRules {

    private TableCharacterRules() {
    }

    /**
     * @param tableDir 导表器 .pb 产物目录
     * @throws IllegalStateException 目录不存在、加载失败或自检不通过
     */
    public static TableCharacterRules load(Path tableDir) {
        if (!Files.isDirectory(tableDir)) {
            throw new IllegalStateException("配置表目录不存在: " + tableDir.toAbsolutePath()
                    + "（进程须从仓库根目录启动，或用 xm.table-dir 指定）");
        }
        try {
            AllTable.loadTables(tableDir.toString(), true);
        } catch (Exception e) {
            throw new IllegalStateException("加载配置表失败: " + tableDir.toAbsolutePath(), e);
        }
        TableCharacterRules rules = new TableCharacterRules();
        rules.defaultClassId();
        rules.roleNameRules();
        return rules;
    }

    @Override
    public int defaultClassId() {
        ClassTableData all = ClassTableManager.getInstance().findAll();
        if (all.getDataCount() == 0) {
            throw new IllegalStateException("Class 表为空，无法确定默认职业");
        }
        return all.getData(0).getId();
    }

    @Override
    public boolean classExists(int classId) {
        return ClassTableManager.getInstance().exists(classId);
    }

    @Override
    public RoleNameRules roleNameRules() {
        try {
            return RoleNameRules.fromRow(RoleNameRuleTableManager.getInstance().findById(RoleNameRules.ROW_ID));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RoleNameRule 不合法: " + e.getMessage(), e);
        }
    }
}
