package com.game.scene.world;

import com.game.proto.SceneInfoComp;

/**
 * 要建的一个镜像 / 副本实例（批次 5.3，dungeon-mirror-spec §6.8）：scene_id 已由 scene-manager 全服租约发出（Q8），节点在逻辑线程上经
 * {@link SceneWorld#createInstance} 校验后登记。{@link #toInfo()} 就是 79 / 31 里的 {@code scene_info}（§5.2，两版逐字段一致）：
 * <ul>
 *   <li>镜像：{@code scene_config_id} = 源频道的配置号（不是 Mirror.scene_id）、{@code mirror_config_id} = 63 里带的值、
 *       {@code dungeon_config_id} = 0、{@code creators} = {@code {创建者: true}}（只有一项；值必须是 true，基线 scene_node_service.cpp:39-50）；</li>
 *   <li>副本：{@code scene_config_id} = Dungeon.scene_id（D16）、{@code dungeon_config_id} = Dungeon.id、{@code mirror_config_id} = 0、
 *       {@code creators} 为空（系统创建，同基线 sm_reply.cpp:98-102）。</li>
 * </ul>
 *
 * @param sourceSceneId 镜像的源（同节点上的主世界频道），副本为 0
 * @param creatorId     镜像的创建者，副本为 0
 */
public record InstanceSpec(SceneKind kind, long sceneId, int sceneConfigId, long sourceSceneId, int mirrorConfigId,
                           int dungeonConfigId, long creatorId) {

    public InstanceSpec {
        if (kind == null || !kind.isInstance()) {
            throw new IllegalArgumentException("实例只能是镜像或副本: " + kind);
        }
        boolean mirror = kind == SceneKind.MIRROR;
        if (mirror != (sourceSceneId != 0) || mirror != (mirrorConfigId != 0) || mirror != (creatorId != 0)
                || mirror == (dungeonConfigId != 0)) {
            throw new IllegalArgumentException("镜像必须带源 / mirror_config_id / 创建者且不带 dungeon_config_id，副本反之: " + kindDescription(
                    kind, sourceSceneId, mirrorConfigId, dungeonConfigId, creatorId));
        }
    }

    /** 镜像：地图取源频道的配置号。 */
    public static InstanceSpec mirror(long sceneId, int sourceConfigId, long sourceSceneId, int mirrorConfigId,
                                      long creatorId) {
        return new InstanceSpec(SceneKind.MIRROR, sceneId, sourceConfigId, sourceSceneId, mirrorConfigId, 0, creatorId);
    }

    /** 副本：地图 = Dungeon.scene_id。 */
    public static InstanceSpec dungeon(long sceneId, int sceneConfigId, int dungeonConfigId) {
        return new InstanceSpec(SceneKind.DUNGEON, sceneId, sceneConfigId, 0, 0, dungeonConfigId, 0);
    }

    /** 79 / 31 的 {@code scene_info}。 */
    public SceneInfoComp toInfo() {
        SceneInfoComp.Builder info = SceneInfoComp.newBuilder()
                .setSceneConfigId(sceneConfigId)
                .setSceneId(sceneId)
                .setMirrorConfigId(mirrorConfigId)
                .setDungeonConfigId(dungeonConfigId);
        if (creatorId != 0) {
            info.putCreators(creatorId, true);
        }
        return info.build();
    }

    private static String kindDescription(SceneKind kind, long source, int mirror, int dungeon, long creator) {
        return "kind=" + kind + " source=" + Long.toUnsignedString(source) + " mirror=" + Integer.toUnsignedString(mirror)
                + " dungeon=" + Integer.toUnsignedString(dungeon) + " creator=" + Long.toUnsignedString(creator);
    }
}
