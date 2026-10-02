package com.game.login.character;

import com.game.table.ClassRows;
import com.game.table.ConfigTables;
import java.nio.file.Path;

/**
 * 以配置表（{@link ConfigTables}）为数据源的 {@link CharacterRules}。
 *
 * <p>只能经 {@link #load(Path)} 得到：它加载并整体校验全部配置表，立刻算出建角要用的默认职业与起名规则，
 * 任何一项不合格都抛异常让进程起不来——宁可启动失败，也不等到有人建角才发现。
 * 表在进程对外服务（Dubbo 暴露）之前加载完，此后只读。
 */
public final class TableCharacterRules implements CharacterRules {

    private final ClassRows classes;
    private final int defaultClassId;
    private final RoleNameRules roleNameRules;

    private TableCharacterRules(ClassRows classes, int defaultClassId, RoleNameRules roleNameRules) {
        this.classes = classes;
        this.defaultClassId = defaultClassId;
        this.roleNameRules = roleNameRules;
    }

    /**
     * @param tableDir 导表器产物目录（manifest.json + 各表 .pb）
     * @throws com.game.table.load.TableLoadException 目录不存在、数据不完整或被改动
     * @throws IllegalStateException                  Class 表为空或 RoleNameRule 不合法
     */
    public static TableCharacterRules load(Path tableDir) {
        return from(ConfigTables.load(tableDir));
    }

    static TableCharacterRules from(ConfigTables tables) {
        ClassRows classes = tables.classTable();
        if (classes.size() == 0) {
            throw new IllegalStateException("Class 表为空，无法确定默认职业");
        }
        RoleNameRules nameRules;
        try {
            nameRules = RoleNameRules.fromRow(tables.roleNameRule().find(RoleNameRules.ROW_ID).orElse(null));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RoleNameRule 不合法: " + e.getMessage(), e);
        }
        // 默认职业 = Class 表按表序第一行（与 mmorpg createplayerlogic 一致）。
        return new TableCharacterRules(classes, classes.all().get(0).getId(), nameRules);
    }

    @Override
    public int defaultClassId() {
        return defaultClassId;
    }

    @Override
    public boolean classExists(int classId) {
        return classes.contains(classId);
    }

    @Override
    public RoleNameRules roleNameRules() {
        return roleNameRules;
    }
}
