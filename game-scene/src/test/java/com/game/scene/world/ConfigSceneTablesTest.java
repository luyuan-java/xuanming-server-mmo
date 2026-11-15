package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.ConfigTables;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** 用仓库里同步来的真实配置表（config-data/tables）核对契约文档给出的数值。 */
class ConfigSceneTablesTest {

    private static ConfigSceneTables tables;

    @BeforeAll
    static void load() {
        Path dir = CurrentMapNavigationTest.configRoot().resolve("tables");
        tables = ConfigSceneTables.from(ConfigTables.load(dir), CurrentMapNavigationTest.navigationRoot());
    }

    @Test
    void 初始技能保留表并集查询_实际发放按当前职业() {
        // Class.xlsx 四职业各有主动/被动初始技能；其余职业的重复普攻仍只保留一次，顺序来自正式表。
        assertThat(tables.initialSkills()).containsExactly(1101, 1106, 1201, 1206, 1301, 1306, 1401, 1406, 1);
        assertThat(tables.initialSkills(1)).containsExactly(1101, 1106);
        assertThat(tables.initialSkills(2)).containsExactly(1201, 1206);
        assertThat(tables.initialSkills(0)).isEmpty();
        assertThat(tables.initialSkills(987654)).isEmpty();
    }

    @Test
    void 首登主世界与当前地图出生点一致() {
        assertThat(tables.worldSceneConfigIds()).first().isEqualTo(1);
        assertThat(tables.spawnPoint(1)).isEqualTo(new Vec3(133, 199, 0));
        assertThat(tables.spawnPoint(2)).isEqualTo(new Vec3(173, 195, 0));
        Vec3 villageSpawn = CurrentMapNavigationTest.yuexiDataPresent()
                ? new Vec3(36.73125, 20.19375, 0) : new Vec3(147, 201, 0);
        assertThat(tables.spawnPoint(3)).isEqualTo(villageSpawn);
        for (int id = 1; id <= 16; id++) {
            if (id == 4) continue;
            assertThat(tables.navigation(id).isWalkable(tables.spawnPoint(id))).isTrue();
            assertThat(tables.navigation(id)).isInstanceOf(CurrentMapNavigation.class);
        }
        assertThat(tables.navigation(4)).isSameAs(SceneNavigation.NONE);
        assertThat(tables.navigation(17)).isSameAs(SceneNavigation.NONE);
    }

    @Test
    @EnabledIf("com.game.scene.world.CurrentMapNavigationTest#yuexiDataPresent")
    void 月汐湾只替换场景3且旧渔村出生点仍由旧版遮罩读取() {
        assertThat(tables.worldSceneConfigIds()).contains(3);
        CurrentMapNavigation grid = (CurrentMapNavigation) tables.navigation(3);
        assertThat(grid.spawn()).isEqualTo(tables.spawnPoint(3));
        Vec3 legacySpawn = new Vec3(147, 201, 0);
        assertThat(grid.isWalkable(legacySpawn)).isFalse();
        assertThat(grid.trace(legacySpawn, grid.spawn())).isEqualTo(new SceneNavigation.Move(grid.spawn(), true));
        assertThat(CurrentMapNavigation.load(CurrentMapNavigationTest.navigationRoot(), "village").spawn())
                .isEqualTo(legacySpawn);
    }

    @Test
    void 没有BaseScene行的配置_用基线兜底出生点() {
        assertThat(tables.spawnPoint(987654)).isEqualTo(ConfigSceneTables.DEFAULT_SPAWN);
    }

    @Test
    void 技能存在性按Skill表() {
        assertThat(tables.skillExists(13)).isTrue();
        assertThat(tables.skillExists(0)).isFalse();
        assertThat(tables.skillExists(987654)).isFalse();
    }
}
