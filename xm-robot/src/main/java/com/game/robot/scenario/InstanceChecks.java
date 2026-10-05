package com.game.robot.scenario;

import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.SceneInfoComp;
import com.game.table.ConfigTables;
import com.game.table.SceneErrorTip;
import com.game.table.WorldTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 副本 / 镜像探针（批次 5.3）共用的纯函数：三种 63 请求、79 的 {@code scene_info} 逐字段核对（dungeon-mirror-spec §5.2）、
 * 「进不去」的两种合法形态、配置号挑选。不连服务端，单测覆盖。
 */
final class InstanceChecks {

    static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    static final Vec3 DEFAULT_SPAWN = new Vec3(180.0, 200.0, 0.0);

    private InstanceChecks() {
    }

    /** 建镜像：{@code {mirror_config_id = M, scene_id = 0}}，其余字段不带（镜像分支忽略请求的 scene_config_id，§6.7）。 */
    static EnterSceneC2SRequest mirrorRequest(int mirrorConfigId) {
        return EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setMirrorConfigId(mirrorConfigId))
                .build();
    }

    /** 按号加入实例：{@code {scene_id = 实例号, mirror_config_id = M}}（scene_id 非 0 时走普通显式换图，镜像号任意，§6.12）。 */
    static EnterSceneC2SRequest joinRequest(long sceneId, int mirrorConfigId) {
        return EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneId(sceneId).setMirrorConfigId(mirrorConfigId))
                .build();
    }

    /** 只带地图：{@code {scene_config_id = conf}}。身在实例里时必回该图的主世界频道（R3）。 */
    static EnterSceneC2SRequest mapRequest(int sceneConfigId) {
        return EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(sceneConfigId))
                .build();
    }

    /**
     * 镜像的 79（§5.2）：地图 = 源场景的配置号；scene_id 非 0 且不是源；mirror_config_id = 请求的 M；dungeon_config_id = 0；
     * creators 恰好一项 {@code {创建者: true}}（后加入的人看到的也只有创建者）。
     *
     * @return 不符项（空 = 全部符合）
     */
    static List<String> mirrorInfoProblems(SceneInfoComp info, int sourceConfigId, long sourceSceneId, int mirrorConfigId,
                                           long creator) {
        List<String> problems = new ArrayList<>();
        if (info.getSceneConfigId() != sourceConfigId) {
            problems.add("scene_config_id 应为源地图 " + Integer.toUnsignedString(sourceConfigId));
        }
        if (info.getSceneId() == 0 || info.getSceneId() == sourceSceneId) {
            problems.add("scene_id 应为新号（非 0、不是源 " + Long.toUnsignedString(sourceSceneId) + "）");
        }
        if (info.getMirrorConfigId() != mirrorConfigId) {
            problems.add("mirror_config_id 应为 " + Integer.toUnsignedString(mirrorConfigId));
        }
        if (info.getDungeonConfigId() != 0) {
            problems.add("dungeon_config_id 应为 0");
        }
        if (!info.getCreatorsMap().equals(Map.of(creator, true))) {
            problems.add("creators 应恰为 {" + Long.toUnsignedString(creator) + ": true}");
        }
        return problems;
    }

    /** 副本的 79（§5.2）：地图 = Dungeon.scene_id、号 = 管理口给的号、dungeon_config_id = Dungeon.id、mirror 0、creators 空。 */
    static List<String> dungeonInfoProblems(SceneInfoComp info, long sceneId, int sceneConfigId, int dungeonConfigId) {
        List<String> problems = new ArrayList<>();
        if (info.getSceneId() != sceneId) {
            problems.add("scene_id 应为副本号 " + Long.toUnsignedString(sceneId));
        }
        if (info.getSceneConfigId() != sceneConfigId) {
            problems.add("scene_config_id 应为 " + Integer.toUnsignedString(sceneConfigId));
        }
        if (info.getDungeonConfigId() != dungeonConfigId) {
            problems.add("dungeon_config_id 应为 " + Integer.toUnsignedString(dungeonConfigId));
        }
        if (info.getMirrorConfigId() != 0) {
            problems.add("mirror_config_id 应为 0");
        }
        if (info.getCreatorsCount() != 0) {
            problems.add("creators 应为空（系统创建）");
        }
        return problems;
    }

    /** 主世界频道的 79：地图对、不是那个实例、mirror / dungeon 为 0、creators 空（5.1 频道的 info 只有前两个字段）。 */
    static List<String> worldInfoProblems(SceneInfoComp info, int sceneConfigId, long notSceneId) {
        List<String> problems = new ArrayList<>();
        if (info.getSceneConfigId() != sceneConfigId) {
            problems.add("scene_config_id 应为 " + Integer.toUnsignedString(sceneConfigId));
        }
        if (info.getSceneId() == 0 || info.getSceneId() == notSceneId) {
            problems.add("scene_id 应为主世界频道（非 0、不是实例 " + Long.toUnsignedString(notSceneId) + "）");
        }
        if (info.getMirrorConfigId() != 0) {
            problems.add("mirror_config_id 应为 0");
        }
        if (info.getDungeonConfigId() != 0) {
            problems.add("dungeon_config_id 应为 0");
        }
        if (info.getCreatorsCount() != 0) {
            problems.add("creators 应为空");
        }
        return problems;
    }

    /**
     * 「进不去」的两种合法形态（§5.7、§12.6 第 8 步）：同节点同步回 3023（实例在回收宽限 / 排空中）；或号已不在本节点，
     * 应答 {0} 之后问 scene-manager、推 23 {3023}。两种情况都不能有 79。
     *
     * @param replyTip        63 应答的拒绝码
     * @param lateEnterFailed 应答之后是否收到了 23 {3023}
     * @param enters          这段时间收到的 79 条数
     */
    static boolean enterRefused(int replyTip, boolean lateEnterFailed, long enters) {
        return enters == 0 && (replyTip == ENTER_FAILED || (replyTip == 0 && lateEnterFailed));
    }

    /** 从 {@code start} 起第一个不在 {@code present} 里的配置号（非 0）：表外的号，用来测「表里没有这一行」。 */
    static int firstMissing(Set<Integer> present, int start) {
        int id = Math.max(1, start);
        while (present.contains(id)) {
            id++;
        }
        return id;
    }

    /** 默认主世界：World 表第一行（scene 契约 §1）。 */
    static int homeWorld(ConfigTables tables) {
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0) {
                return row.getSceneId();
            }
        }
        throw new IllegalStateException("World 表里没有 scene_id 非 0 的行");
    }

    /** 同 scene：BaseScene 的出生点（有限且不全为 0），否则基线常量 (180, 200, 0)。 */
    static Vec3 spawn(ConfigTables tables, int sceneConfigId) {
        return tables.baseScene().find(sceneConfigId)
                .map(row -> new Vec3(row.getSpawnX(), row.getSpawnY(), row.getSpawnZ()))
                .filter(v -> v.isFinite() && !v.isZero())
                .orElse(DEFAULT_SPAWN);
    }

    /** 79 的 scene_info 全字段（creators 按键的无符号值排序，便于对照；号一律按无符号输出）。 */
    static String describe(SceneInfoComp info) {
        StringBuilder out = new StringBuilder();
        out.append("{conf=").append(Integer.toUnsignedString(info.getSceneConfigId()))
                .append(" scene_id=").append(Long.toUnsignedString(info.getSceneId()))
                .append(" mirror=").append(Integer.toUnsignedString(info.getMirrorConfigId()))
                .append(" dungeon=").append(Integer.toUnsignedString(info.getDungeonConfigId()))
                .append(" creators={");
        boolean first = true;
        Map<Long, Boolean> sorted = new TreeMap<>(Long::compareUnsigned);
        sorted.putAll(info.getCreatorsMap());
        for (Map.Entry<Long, Boolean> e : sorted.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(Long.toUnsignedString(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        return out.append("}}").toString();
    }
}
