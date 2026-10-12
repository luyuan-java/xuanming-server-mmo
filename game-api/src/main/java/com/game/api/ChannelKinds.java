package com.game.api;

import com.game.api.proto.ChannelKind;

/**
 * {@link ChannelKind} 的读法（批次 5.3，dungeon-mirror-spec §6.5）：节点目录 {@code SceneEntry.kind} 与频道计划 {@code WorldChannel.kind}
 * 共用这一处判定，scene-manager 与 scene 不各写一份。
 */
public final class ChannelKinds {

    private ChannelKinds() {
    }

    /**
     * 是不是主世界频道：WORLD，或 UNSPECIFIED（proto3 缺省值，不认识 kind 字段的旧版本节点 / 记录写的条目，5.3 之前只有主世界）。
     * MIRROR / DUNGEON 与不认识的取值（{@code UNRECOGNIZED}，更新版本才有的种类）都不是——按地图选频道、计划外排空、缩容计数都不碰它们（fail-closed）。
     */
    public static boolean isWorldChannel(ChannelKind kind) {
        return kind == ChannelKind.CHANNEL_KIND_WORLD || kind == ChannelKind.CHANNEL_KIND_UNSPECIFIED;
    }
}
