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
    private static final String CURRENT_NAV_PREFIX = "data/scene_nav_bin/current_maps/";

    private final ConfigTables tables;
    private final com.game.scene.skill.ClassSkillCatalog classSkills;
    private final List<Integer> worldSceneConfigIds;
    private final List<Integer> initialSkills;
    private final Map<Integer, CurrentMapNavigation> navigation;

    private ConfigSceneTables(ConfigTables tables, List<Integer> worldSceneConfigIds, List<Integer> initialSkills,
                              Map<Integer, CurrentMapNavigation> navigation) {
        this.tables = tables;
        this.classSkills = com.game.scene.skill.ClassSkillCatalog.from(tables);
        this.worldSceneConfigIds = worldSceneConfigIds;
        this.initialSkills = initialSkills;
        this.navigation = Map.copyOf(navigation);
    }

    /** World 表为空时直接失败（没有可创建的主世界）。 */
    public static ConfigSceneTables from(ConfigTables tables, Path navigationRoot) {
        return from(tables, navigationRoot, null);
    }

    /** festivalRoot 只能由显式 dev/test 装配提供；普通启动遇到候选节庆表直接拒启。 */
    public static ConfigSceneTables from(ConfigTables tables, Path navigationRoot, Path festivalRoot) {
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
            Vec3 spawn = new Vec3(row.getSpawnX(), row.getSpawnY(), row.getSpawnZ());
            String festivalId = FestivalMapNavigation.paddedId(row.getId());
            if (festivalId != null || nav.contains("/festival_maps/")) {
                if (festivalRoot == null || festivalId == null
                        || !nav.equals("data/scene_nav_bin/festival_maps/" + festivalId + "/scene.bin")) {
                    throw new IllegalStateException("节庆地图需要显式开发配置及匹配的导航映射: " + row.getId());
                }
                CurrentMapNavigation grid = FestivalMapNavigation.load(festivalRoot, festivalId);
                // XLSX 十进制往返可改变最后一位 double；容许纳米级表示差，不容许实际落点漂移。
                if (!spawn.isFinite() || spawn.z() != grid.spawn().z()
                        || grid.spawn().horizontalDistance(spawn) > 1e-9 || !grid.isWalkable(spawn)) {
                    throw new IllegalStateException("BaseScene 出生点与节庆导航不一致: " + row.getId());
                }
                navigation.put(row.getId(), grid);
                continue;
            }
            if (nav.startsWith(CURRENT_NAV_PREFIX)) {
                if (!nav.endsWith("/scene.bin")) {
                    throw new IllegalStateException("新版主世界导航路径缺少 /scene.bin: " + row.getId());
                }
                String relativeFolder = nav.substring(CURRENT_NAV_PREFIX.length(), nav.length() - "/scene.bin".length());
                CurrentMapNavigation grid;
                if (row.getId() == 3 && CurrentMapNavigation.YUEXI_FOLDER.equals(relativeFolder)) {
                    grid = loaded.computeIfAbsent(relativeFolder, k -> CurrentMapNavigation.loadYuexi(navigationRoot));
                } else if (row.getId() == 3) {
                    throw new IllegalStateException("新版主世界导航与 BaseScene 不匹配: " + row.getId());
                } else {
                    // 新主城出图前，场景 1 与占位世界槽位 6..16 临时共享碧渊水宫(5)双节导航。
                    int expectedSceneId = (row.getId() == 1 || (row.getId() >= 6 && row.getId() <= 16))
                            && CurrentMapNavigation.BIYUAN_FOLDER.equals(relativeFolder)
                            ? 5 : row.getId();
                    grid = loaded.computeIfAbsent(relativeFolder,
                            k -> CurrentMapNavigation.loadVersionedCurrentMap(navigationRoot, k, expectedSceneId));
                }
                if (!spawn.isFinite() || spawn.z() != grid.spawn().z()
                        || grid.spawn().horizontalDistance(spawn) > 1e-9 || !grid.isWalkable(spawn)) {
                    throw new IllegalStateException("BaseScene 出生点与新版主世界导航不一致: " + row.getId());
                }
                navigation.put(row.getId(), grid);
            }
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
    public List<Integer> initialSkills(int classId) {
        return tables.classTable().find(classId).map(ClassTable::getSkillList).orElse(List.of());
    }

    @Override
    public com.game.scene.skill.ClassSkillCatalog classSkills() { return classSkills; }

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
