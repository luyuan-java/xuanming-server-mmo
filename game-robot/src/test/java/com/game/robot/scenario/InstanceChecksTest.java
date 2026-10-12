package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.SceneInfoComp;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 副本 / 镜像探针（批次 5.3）里不连服务端的纯函数：三种 63 请求的形状、79 的 {@code scene_info} 逐字段核对（dungeon-mirror-spec §5.2）、
 * 「进不去」的两种合法形态、配置号挑选、出生点回落、描述按无符号。
 */
class InstanceChecksTest {

    /** 大于 Long.MAX_VALUE 的场景号（uint64），描述要按无符号。 */
    private static final long BIG = 0x8000_0000_0000_0001L;
    private static final long CREATOR = 1001;

    private static ConfigTables tables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        return ConfigTables.load(dir);
    }

    private static SceneInfoComp mirror(int conf, long id, int mirrorConfigId, long creator) {
        return SceneInfoComp.newBuilder().setSceneConfigId(conf).setSceneId(id).setMirrorConfigId(mirrorConfigId)
                .putCreators(creator, true).build();
    }

    @Test
    void 三种63请求_只带各自的字段() {
        assertThat(InstanceChecks.mirrorRequest(1)).isEqualTo(EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setMirrorConfigId(1)).build());
        assertThat(InstanceChecks.joinRequest(BIG, 2)).isEqualTo(EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneId(BIG).setMirrorConfigId(2)).build());
        assertThat(InstanceChecks.mapRequest(17)).isEqualTo(EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(17)).build());
        // 建镜像：scene_id = 0、scene_config_id 不带（字节里只有 mirror 这一个字段）
        assertThat(HexFormat.ofDelimiter(" ").withUpperCase().formatHex(InstanceChecks.mirrorRequest(1).toByteArray()))
                .isEqualTo("0A 02 18 01");
    }

    @Test
    void 镜像info_全部符合时没有不符项_逐字段各报一条() {
        assertThat(InstanceChecks.mirrorInfoProblems(mirror(1, BIG, 1, CREATOR), 1, 7, 1, CREATOR)).isEmpty();

        assertThat(InstanceChecks.mirrorInfoProblems(mirror(20, BIG, 1, CREATOR), 1, 7, 1, CREATOR))
                .as("地图取源场景的，不是 Mirror.scene_id").singleElement().asString().contains("源地图");
        assertThat(InstanceChecks.mirrorInfoProblems(mirror(1, 7, 1, CREATOR), 1, 7, 1, CREATOR))
                .as("号就是源").singleElement().asString().contains("新号");
        assertThat(InstanceChecks.mirrorInfoProblems(mirror(1, 0, 1, CREATOR), 1, 7, 1, CREATOR))
                .as("号为 0").singleElement().asString().contains("新号");
        assertThat(InstanceChecks.mirrorInfoProblems(mirror(1, BIG, 2, CREATOR), 1, 7, 1, CREATOR))
                .singleElement().asString().contains("mirror_config_id");
        assertThat(InstanceChecks.mirrorInfoProblems(mirror(1, BIG, 1, CREATOR).toBuilder().setDungeonConfigId(1).build(), 1, 7, 1,
                CREATOR)).singleElement().asString().contains("dungeon_config_id");
    }

    @Test
    void 镜像info_creators必须恰为创建者一项且为true() {
        SceneInfoComp falseValue = mirror(1, BIG, 1, CREATOR).toBuilder().putCreators(CREATOR, false).build();
        SceneInfoComp extra = mirror(1, BIG, 1, CREATOR).toBuilder().putCreators(1002, true).build();
        SceneInfoComp missing = mirror(1, BIG, 1, CREATOR).toBuilder().clearCreators().build();
        SceneInfoComp other = mirror(1, BIG, 1, 1002);

        for (SceneInfoComp info : new SceneInfoComp[] {falseValue, extra, missing, other}) {
            assertThat(InstanceChecks.mirrorInfoProblems(info, 1, 7, 1, CREATOR)).as(InstanceChecks.describe(info))
                    .singleElement().asString().contains("creators");
        }
    }

    @Test
    void 副本info与主世界info的核对() {
        SceneInfoComp dungeon = SceneInfoComp.newBuilder().setSceneConfigId(17).setSceneId(BIG).setDungeonConfigId(1).build();
        assertThat(InstanceChecks.dungeonInfoProblems(dungeon, BIG, 17, 1)).isEmpty();
        assertThat(InstanceChecks.dungeonInfoProblems(dungeon, BIG + 1, 18, 2)).hasSize(3);
        assertThat(InstanceChecks.dungeonInfoProblems(dungeon.toBuilder().setMirrorConfigId(1).putCreators(1, true).build(), BIG, 17,
                1)).hasSize(2);

        SceneInfoComp world = SceneInfoComp.newBuilder().setSceneConfigId(1).setSceneId(5).build();
        assertThat(InstanceChecks.worldInfoProblems(world, 1, BIG)).isEmpty();
        assertThat(InstanceChecks.worldInfoProblems(world, 1, 5)).as("还是那个实例").hasSize(1);
        assertThat(InstanceChecks.worldInfoProblems(mirror(1, BIG, 1, CREATOR), 1, 5))
                .as("落进了镜像：mirror 与 creators 都不对").hasSize(2);
        assertThat(InstanceChecks.worldInfoProblems(dungeon, 1, 5)).hasSize(2);
    }

    @Test
    void 进不去的两种合法形态_同步3023或应答0后23的3023_都不能有79() {
        assertThat(InstanceChecks.enterRefused(3023, false, 0)).isTrue();
        assertThat(InstanceChecks.enterRefused(0, true, 0)).isTrue();
        assertThat(InstanceChecks.enterRefused(0, false, 0)).as("{0} 之后什么都没有").isFalse();
        assertThat(InstanceChecks.enterRefused(0, true, 1)).as("有 79 就是进去了").isFalse();
        assertThat(InstanceChecks.enterRefused(3005, false, 0)).as("别的拒绝码").isFalse();
        assertThat(InstanceChecks.enterRefused(3023, false, 1)).isFalse();
    }

    @Test
    void 表外配置号_从起点找第一个空位_不取0() {
        assertThat(InstanceChecks.firstMissing(Set.of(1, 2), 999)).isEqualTo(999);
        assertThat(InstanceChecks.firstMissing(Set.of(999, 1000), 999)).isEqualTo(1001);
        assertThat(InstanceChecks.firstMissing(Set.of(1, 2), 0)).isEqualTo(3);
    }

    @Test
    void 配表_默认主世界取World第一行_副本地图出生点取BaseScene_找不到回落基线常量() {
        ConfigTables tables = tables();

        int home = InstanceChecks.homeWorld(tables);
        assertThat(home).isEqualTo(tables.world().all().get(0).getSceneId()).isNotZero();
        DungeonTable first = tables.dungeon().all().get(0);
        assertThat(InstanceChecks.spawn(tables, first.getSceneId())).as("BaseScene 17–19 的出生点")
                .isEqualTo(new Vec3(180, 200, 0));
        assertThat(InstanceChecks.spawn(tables, 999_999)).isEqualTo(InstanceChecks.DEFAULT_SPAWN);
    }

    @Test
    void 描述_creators按键排序_号按无符号() {
        SceneInfoComp info = mirror(1, BIG, 1, 3).toBuilder().putCreators(-1L, true).build();

        assertThat(InstanceChecks.describe(info)).isEqualTo("{conf=1 scene_id=9223372036854775809 mirror=1 dungeon=0 creators={3: true, "
                + "18446744073709551615: true}}");
    }
}
