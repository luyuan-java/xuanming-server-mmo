package com.game.scene.world;

import com.game.api.proto.ChannelKind;
import java.util.Optional;

/**
 * 场景种类（批次 5.3，dungeon-mirror-spec §6.4、§7.2「常量」）：节点本地的读法，与内部契约 {@link ChannelKind} 一一互转。
 * <ul>
 *   <li>{@link #WORLD}：频道计划建的主世界频道（5.1）。按地图选频道（只带地图的 63、排空改派、进场重定向）、计划外排空、
 *       {@code xm.scene.channels} 只认它（R1–R4）。</li>
 *   <li>{@link #MIRROR} / {@link #DUNGEON}：承载节点自有的实例——不在频道计划里、随进程消亡，节点目录（{@code SceneEntry.kind}）是唯一登记（D1）；
 *       只能按 scene_id 进入（63 显式号、登录回原实例、组队跟随）。镜像的源是同节点上的一条主世界频道（{@link Scene#sourceSceneId()}）。</li>
 * </ul>
 */
public enum SceneKind {
    WORLD(ChannelKind.CHANNEL_KIND_WORLD),
    MIRROR(ChannelKind.CHANNEL_KIND_MIRROR),
    DUNGEON(ChannelKind.CHANNEL_KIND_DUNGEON);

    private final ChannelKind channelKind;

    SceneKind(ChannelKind channelKind) {
        this.channelKind = channelKind;
    }

    /** 写进节点目录 / 取号请求的契约取值。 */
    public ChannelKind channelKind() {
        return channelKind;
    }

    /** 是不是节点自有的实例（镜像 / 副本）。 */
    public boolean isInstance() {
        return this != WORLD;
    }

    /**
     * 契约取值 → 本地种类：UNSPECIFIED（旧版本写的记录）按 WORLD 读（同 {@code com.game.api.ChannelKinds#isWorldChannel}）；
     * 不认识的取值（更新版本才有的种类）为空，调用方按 fail-closed 拒绝。
     */
    public static Optional<SceneKind> of(ChannelKind kind) {
        return switch (kind) {
            case CHANNEL_KIND_UNSPECIFIED, CHANNEL_KIND_WORLD -> Optional.of(WORLD);
            case CHANNEL_KIND_MIRROR -> Optional.of(MIRROR);
            case CHANNEL_KIND_DUNGEON -> Optional.of(DUNGEON);
            default -> Optional.empty();
        };
    }
}
