package com.game.scene.testing;

import com.game.scene.world.SceneTables;
import com.game.scene.world.Vec3;
import java.util.List;
import java.util.OptionalInt;

/**
 * 测试用配表：两张主世界地图。配置 1 的出生点与基线数据一致 (180, 200, 0)，配置 2 为 (170, 220, 0)；
 * 初始技能与基线 Class 表并集一致 [1, 2, 13]；Skill 表含 1–13。
 * 批次 5.3：Mirror 表两行（id 1、2，同基线数据）；Dungeon 表三行（1 → 地图 17、2 → 18、3 → 19，同基线数据），
 * 副本地图 17 的出生点为 {@link #SPAWN_DUNGEON}（基线 BaseScene 17–19 都是 (180, 200, 0)，这里取不同的值好区分落点）。
 */
public final class FakeSceneTables implements SceneTables {

    public static final Vec3 SPAWN_1 = new Vec3(180, 200, 0);
    public static final Vec3 SPAWN_2 = new Vec3(170, 220, 0);
    public static final Vec3 SPAWN_DUNGEON = new Vec3(150, 150, 0);
    /** Dungeon 1 的地图。 */
    public static final int DUNGEON_MAP = 17;

    @Override
    public List<Integer> worldSceneConfigIds() {
        return List.of(1, 2);
    }

    @Override
    public Vec3 spawnPoint(int sceneConfigId) {
        if (sceneConfigId >= 17 && sceneConfigId <= 19) {
            return SPAWN_DUNGEON;
        }
        return sceneConfigId == 2 ? SPAWN_2 : SPAWN_1;
    }

    @Override
    public List<Integer> initialSkills() {
        return List.of(1, 2, 13);
    }

    @Override
    public boolean skillExists(int skillTableId) {
        return skillTableId >= 1 && skillTableId <= 13;
    }

    @Override
    public boolean mirrorExists(int mirrorConfigId) {
        return mirrorConfigId == 1 || mirrorConfigId == 2;
    }

    @Override
    public OptionalInt dungeonSceneConfigId(int dungeonConfigId) {
        return switch (dungeonConfigId) {
            case 1 -> OptionalInt.of(17);
            case 2 -> OptionalInt.of(18);
            case 3 -> OptionalInt.of(19);
            default -> OptionalInt.empty();
        };
    }
}
