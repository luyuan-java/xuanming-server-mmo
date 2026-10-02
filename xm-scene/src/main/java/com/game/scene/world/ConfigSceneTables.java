package com.game.scene.world;

import com.game.table.BaseSceneTable;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import com.game.table.SkillRows;
import com.game.table.WorldTable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 以配置表快照（{@link ConfigTables}）为数据源的 {@link SceneTables}。
 *
 * <p>派生数据（主世界配置列表、初始技能）在构造时一次算好；配置热更 = 用新快照重建本对象。
 */
public final class ConfigSceneTables implements SceneTables {

    /** 基线常量 {@code kTianyongSpawn}（mmorpg {@code spatial/constants/nav.h}）：BaseScene 没有有效出生点时的兜底。 */
    public static final Vec3 DEFAULT_SPAWN = new Vec3(180.0, 200.0, 0.0);

    private final ConfigTables tables;
    private final List<Integer> worldSceneConfigIds;
    private final List<Integer> initialSkills;

    private ConfigSceneTables(ConfigTables tables, List<Integer> worldSceneConfigIds, List<Integer> initialSkills) {
        this.tables = tables;
        this.worldSceneConfigIds = worldSceneConfigIds;
        this.initialSkills = initialSkills;
    }

    /** World 表为空时直接失败（没有可创建的主世界）。 */
    public static ConfigSceneTables from(ConfigTables tables) {
        Set<Integer> world = new LinkedHashSet<>();
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0) {
                world.add(row.getSceneId());
            }
        }
        if (world.isEmpty()) {
            throw new IllegalStateException("World 表为空，无法创建主世界场景（检查 xm.table-dir）");
        }

        SkillRows skillTable = tables.skill();
        Set<Integer> skills = new LinkedHashSet<>();
        for (ClassTable row : tables.classTable().all()) {
            for (int skill : row.getSkillList()) {
                if (skill != 0 && skillTable.contains(skill)) {
                    skills.add(skill);
                }
            }
        }
        return new ConfigSceneTables(tables, List.copyOf(world), List.copyOf(skills));
    }

    @Override
    public List<Integer> worldSceneConfigIds() {
        return worldSceneConfigIds;
    }

    @Override
    public Vec3 spawnPoint(int sceneConfigId) {
        Optional<BaseSceneTable> row = tables.baseScene().find(sceneConfigId);
        if (row.isEmpty()) {
            return DEFAULT_SPAWN;
        }
        Vec3 spawn = new Vec3(row.get().getSpawnX(), row.get().getSpawnY(), row.get().getSpawnZ());
        return spawn.isFinite() && !spawn.isOrigin() ? spawn : DEFAULT_SPAWN;
    }

    @Override
    public List<Integer> initialSkills() {
        return initialSkills;
    }

    @Override
    public boolean skillExists(int skillTableId) {
        return skillTableId != 0 && tables.skill().contains(skillTableId);
    }
}
