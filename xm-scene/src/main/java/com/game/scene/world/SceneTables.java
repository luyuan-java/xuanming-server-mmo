package com.game.scene.world;

import java.util.List;

/**
 * 场景逻辑用到的配置表数据。只读；实现必须在表加载完成后构造，之后可在任意线程读。
 *
 * <p>单独成接口是为了让场景逻辑的单测不依赖导表产物的全局单例。
 */
public interface SceneTables {

    /**
     * 主世界场景配置 id：World 表各行的 {@code scene_id}，按表顺序去重。第一项是默认主世界（排空改派同图没有频道时的回落，
     * scene-channels-spec §4.10.3）。频道计划只按这些图建场景，别的图的 ACTIVE 记录一律拒绝（§4.10.2）。
     */
    List<Integer> worldSceneConfigIds();

    /**
     * 场景配置的出生点：BaseScene 表的 {@code spawn_x/y/z}（要求有限且不全为 0），否则取基线常量
     * {@code kTianyongSpawn} = (180, 200, 0)。
     */
    Vec3 spawnPoint(int sceneConfigId);

    /**
     * 新玩家的初始技能（{@code skill_table_id} 列表）：Class 表<b>所有行</b>的 {@code skill} 数组按顺序拼接，
     * 跳过 0、Skill 表里不存在的 id 与重复 id。与职业无关（基线 PlayerSkillSystem::RegisterPlayer 的行为）。
     */
    List<Integer> initialSkills();

    boolean skillExists(int skillTableId);
}
