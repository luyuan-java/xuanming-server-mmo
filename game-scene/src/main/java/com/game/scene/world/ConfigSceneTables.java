package com.game.scene.world;

import com.game.table.BaseSceneTable;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.SkillRows;
import com.game.table.WorldTable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
    private final Map<Integer, CurrentMapNavigation> navigation;

    private ConfigSceneTables(ConfigTables tables, List<Integer> worldSceneConfigIds, List<Integer> initialSkills,
                              Map<Integer, CurrentMapNavigation> navigation) {
        this.tables = tables;
        this.worldSceneConfigIds = worldSceneConfigIds;
        this.initialSkills = initialSkills;
        this.navigation = Map.copyOf(navigation);
    }

    /** World 表为空时直接失败（没有可创建的主世界）。 */
    public static ConfigSceneTables from(ConfigTables tables, Path navigationRoot) {
        Set<Integer> world = new LinkedHashSet<>();
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0) {
                world.add(row.getSceneId());
            }
        }
        if (world.isEmpty()) {
            throw new IllegalStateException("World 表为空，无法创建主世界场景（检查 game.table-dir）");
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
        Map<String, CurrentMapNavigation> loaded = new HashMap<>();
        Map<Integer, CurrentMapNavigation> navigation = new HashMap<>();
        for (BaseSceneTable row : tables.baseScene().all()) {
            String nav = row.getNavBinFile().replace('\\', '/');
            String key = switch (nav.substring(nav.lastIndexOf('/') + 1)) {
                case "main_scene.bin" -> "main";
                case "penglai_scene.bin" -> "island";
                case "donghai_scene.bin" -> "village";
                default -> null; // 独立副本、旧图与未声明本批导航的场景保留原规则。
            };
            if (key == null) continue;
            CurrentMapNavigation grid = loaded.computeIfAbsent(key, k -> CurrentMapNavigation.load(navigationRoot, k));
            Vec3 spawn = new Vec3(row.getSpawnX(), row.getSpawnY(), row.getSpawnZ());
            if (!grid.spawn().equals(spawn)) {
                throw new IllegalStateException("BaseScene 出生点与当前地图导航不一致: " + row.getId());
            }
            navigation.put(row.getId(), grid);
        }
        return new ConfigSceneTables(tables, List.copyOf(world), List.copyOf(skills), navigation);
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
    public SceneNavigation navigation(int sceneConfigId) {
        CurrentMapNavigation grid = navigation.get(sceneConfigId);
        return grid == null ? SceneNavigation.NONE : grid;
    }

    @Override
    public List<Integer> initialSkills() {
        return initialSkills;
    }

    @Override
    public boolean skillExists(int skillTableId) {
        return skillTableId != 0 && tables.skill().contains(skillTableId);
    }

    @Override
    public boolean mirrorExists(int mirrorConfigId) {
        // 主键是 uint32、按 int 位型存：直接按位型查即是无符号查找（≥ 2^31 的值为负，表里不可能有）
        return mirrorConfigId != 0 && tables.mirror().contains(mirrorConfigId);
    }

    @Override
    public OptionalInt dungeonSceneConfigId(int dungeonConfigId) {
        if (dungeonConfigId == 0) {
            return OptionalInt.empty();
        }
        Optional<DungeonTable> row = tables.dungeon().find(dungeonConfigId);
        if (row.isEmpty() || row.get().getSceneId() == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(row.get().getSceneId());
    }
}
