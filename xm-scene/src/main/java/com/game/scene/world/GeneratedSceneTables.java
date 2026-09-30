package com.game.scene.world;

import com.game.table.BaseSceneTable;
import com.game.table.BaseSceneTableManager;
import com.game.table.ClassTable;
import com.game.table.ClassTableManager;
import com.game.table.SkillTableManager;
import com.game.table.WorldTable;
import com.game.table.WorldTableManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link SceneTables} 的导表产物实现（{@code com.game.table} 的表管理器单例）。
 *
 * <p>派生数据（主世界配置列表、初始技能）在构造时一次算好；本进程不做配置热更，热更时需要重建本对象。
 */
public final class GeneratedSceneTables implements SceneTables {

    /** 基线常量 {@code kTianyongSpawn}（mmorpg {@code spatial/constants/nav.h}）：BaseScene 没有有效出生点时的兜底。 */
    public static final Vec3 DEFAULT_SPAWN = new Vec3(180.0, 200.0, 0.0);

    private final List<Integer> worldSceneConfigIds;
    private final List<Integer> initialSkills;

    private GeneratedSceneTables(List<Integer> worldSceneConfigIds, List<Integer> initialSkills) {
        this.worldSceneConfigIds = worldSceneConfigIds;
        this.initialSkills = initialSkills;
    }

    /** 调用前必须已执行 {@code AllTable.loadTables}。World 表为空时直接失败（没有可创建的主世界）。 */
    public static GeneratedSceneTables fromLoadedTables() {
        Set<Integer> world = new LinkedHashSet<>();
        for (WorldTable row : WorldTableManager.getInstance().findAll().getDataList()) {
            if (row.getSceneId() != 0) {
                world.add(row.getSceneId());
            }
        }
        if (world.isEmpty()) {
            throw new IllegalStateException("World 表为空，无法创建主世界场景（检查 xm.table-dir）");
        }

        SkillTableManager skillTable = SkillTableManager.getInstance();
        Set<Integer> skills = new LinkedHashSet<>();
        for (ClassTable row : ClassTableManager.getInstance().findAll().getDataList()) {
            for (int skill : row.getSkillList()) {
                if (skill != 0 && skillTable.exists(skill)) {
                    skills.add(skill);
                }
            }
        }
        return new GeneratedSceneTables(List.copyOf(world), List.copyOf(skills));
    }

    @Override
    public List<Integer> worldSceneConfigIds() {
        return worldSceneConfigIds;
    }

    @Override
    public Vec3 spawnPoint(int sceneConfigId) {
        BaseSceneTable row = BaseSceneTableManager.getInstance().findById(sceneConfigId);
        if (row == null) {
            return DEFAULT_SPAWN;
        }
        Vec3 spawn = new Vec3(row.getSpawnX(), row.getSpawnY(), row.getSpawnZ());
        return spawn.isFinite() && !spawn.isOrigin() ? spawn : DEFAULT_SPAWN;
    }

    @Override
    public List<Integer> initialSkills() {
        return initialSkills;
    }

    @Override
    public boolean skillExists(int skillTableId) {
        return skillTableId != 0 && SkillTableManager.getInstance().exists(skillTableId);
    }
}
