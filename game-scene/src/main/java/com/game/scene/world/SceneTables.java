package com.game.scene.world;

import java.util.List;
import java.util.OptionalInt;

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

    /** 当前地图显式装配导航；旧场景和不提供导航的测试表保留原有语义。 */
    default SceneNavigation navigation(int sceneConfigId) {
        return SceneNavigation.NONE;
    }

    /**
     * 旧通用表适配和配置检查使用的种子并集。生产加载调用 {@link #initialSkills(int)} 按职业取单行，
     * 再由职业技能目录按当前等级验证，不把全表并集授予玩家。
     */
    List<Integer> initialSkills();

    /** 当前职业的种子；正式表按 Class 的单行投影。 */
    default List<Integer> initialSkills(int classId) { return initialSkills(); }

    /** 旧通用技能适配表；生产实现必须提供完整职业技能目录。 */
    default com.game.scene.skill.ClassSkillCatalog classSkills() {
        return com.game.scene.skill.ClassSkillCatalog.generic(initialSkills());
    }

    boolean skillExists(int skillTableId);

    /**
     * Mirror 表里有没有这一行（批次 5.3 D7 / Q1：63 镜像分支的 {@code mirror_config_id} 必须在表里，否则 3005；只校验行存在，
     * 不看 {@code scene_id} / {@code main_scene_id}——镜像的地图仍取源场景的）。按 uint32 的位型查：≥ 2^31 的取值在 Java int 里为负，
     * 表里不可能有（R9）；0 恒为 false。
     */
    boolean mirrorExists(int mirrorConfigId);

    /**
     * 副本的地图：Dungeon 表这一行的 {@code scene_id}（BaseScene 17–19，批次 5.3 D16）。没有这一行、id 为 0 或这一行的 scene_id 为 0 时为空。
     */
    OptionalInt dungeonSceneConfigId(int dungeonConfigId);
}
