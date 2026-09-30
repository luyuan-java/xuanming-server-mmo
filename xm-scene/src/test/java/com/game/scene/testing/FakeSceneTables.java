package com.game.scene.testing;

import com.game.scene.world.SceneTables;
import com.game.scene.world.Vec3;
import java.util.List;

/**
 * 测试用配表：两张主世界地图。配置 1 的出生点与基线数据一致 (180, 200, 0)，配置 2 为 (170, 220, 0)；
 * 初始技能与基线 Class 表并集一致 [1, 2, 13]；Skill 表含 1–13。
 */
public final class FakeSceneTables implements SceneTables {

    public static final Vec3 SPAWN_1 = new Vec3(180, 200, 0);
    public static final Vec3 SPAWN_2 = new Vec3(170, 220, 0);

    @Override
    public List<Integer> worldSceneConfigIds() {
        return List.of(1, 2);
    }

    @Override
    public Vec3 spawnPoint(int sceneConfigId) {
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
}
