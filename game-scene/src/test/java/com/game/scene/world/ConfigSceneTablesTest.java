package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 用仓库里同步来的真实配置表（config-data/tables）核对契约文档给出的数值。 */
class ConfigSceneTablesTest {

    private static ConfigSceneTables tables;

    @BeforeAll
    static void load() {
        // surefire 的工作目录是模块目录；从仓库根目录跑时也能找到。
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        tables = ConfigSceneTables.from(ConfigTables.load(dir), dir.getParent().resolve("navmesh/current_maps"));
    }

    @Test
    void 新号技能是Class表全表去重并集_与职业无关() {
        assertThat(tables.initialSkills()).containsExactly(1, 2, 13);
    }

    @Test
    void 首登主世界与当前地图出生点一致() {
        assertThat(tables.worldSceneConfigIds()).first().isEqualTo(1);
        assertThat(tables.spawnPoint(1)).isEqualTo(new Vec3(133, 199, 0));
        assertThat(tables.spawnPoint(2)).isEqualTo(new Vec3(173, 195, 0));
        assertThat(tables.spawnPoint(3)).isEqualTo(new Vec3(147, 201, 0));
        for (int id = 1; id <= 16; id++) {
            if (id == 4) continue;
            assertThat(tables.navigation(id).isWalkable(tables.spawnPoint(id))).isTrue();
            assertThat(tables.navigation(id)).isInstanceOf(CurrentMapNavigation.class);
        }
        assertThat(tables.navigation(4)).isSameAs(SceneNavigation.NONE);
        assertThat(tables.navigation(17)).isSameAs(SceneNavigation.NONE);
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
