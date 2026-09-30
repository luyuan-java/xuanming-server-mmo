package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.table.WorldTable;
import com.game.table.WorldTableData;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldSceneConfigsTest {

    @Test
    void 默认取表序第一行的scene_id而不是行id() {
        WorldTableData table = table(row(7, 3), row(1, 5));

        WorldSceneConfigs configs = WorldSceneConfigs.fromWorldTable(table, null);

        assertThat(configs.defaultConfigId()).isEqualTo(3);
        assertThat(configs.worldConfigIds()).containsExactlyInAnyOrder(3, 5);
        assertThat(configs.isWorld(5)).isTrue();
        assertThat(configs.isWorld(7)).isFalse();
    }

    @Test
    void 显式指定的默认地图必须是世界地图() {
        WorldTableData table = table(row(1, 3), row(2, 5));

        assertThat(WorldSceneConfigs.fromWorldTable(table, 5).defaultConfigId()).isEqualTo(5);
        assertThatThrownBy(() -> WorldSceneConfigs.fromWorldTable(table, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9");
    }

    @Test
    void 空表启动失败() {
        assertThatThrownBy(() -> WorldSceneConfigs.fromWorldTable(WorldTableData.getDefaultInstance(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scene_id为0的行被跳过() {
        WorldSceneConfigs configs = WorldSceneConfigs.fromWorldTable(table(row(1, 0), row(2, 4)), null);

        assertThat(configs.defaultConfigId()).isEqualTo(4);
        assertThat(configs.worldConfigIds()).containsExactly(4);
    }

    /** 真配置表（仓库 config-data/tables）：首登落点必须是 scene_config_id 1（客户端契约 scene §1）。 */
    @Test
    void 仓库配置表的默认世界地图是1() {
        // surefire 的工作目录是模块目录，配置表在仓库根下。
        WorldSceneConfigs configs = SceneManagerConfiguration.loadWorldSceneConfigs(
                Path.of("..", "config-data", "tables"), null);

        assertThat(configs.defaultConfigId()).isEqualTo(1);
    }

    @Test
    void 配置表目录不存在时启动失败() {
        assertThatThrownBy(() -> SceneManagerConfiguration.loadWorldSceneConfigs(Path.of("no-such-table-dir"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("配置表目录不存在");
    }

    private static WorldTableData table(WorldTable... rows) {
        WorldTableData.Builder b = WorldTableData.newBuilder();
        for (WorldTable r : rows) {
            b.addData(r);
        }
        return b.build();
    }

    private static WorldTable row(int id, int sceneId) {
        return WorldTable.newBuilder().setId(id).setSceneId(sceneId).build();
    }
}
