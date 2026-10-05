package com.game.discovery.world;

import com.game.api.proto.WorldChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * scene 节点拉到的一份频道计划（scene-channels-spec §4.10.1）：版本号与整张 {@code xm:world:{z:<zone>}:ch}，同一段只读 Lua 取回，彼此一致。
 *
 * @param version  计划版本号（{@code xm:world:{z:<zone>}:ver}，不存在为 0）
 * @param channels 全部频道记录，按 scene_id 无符号升序
 */
public record WorldPlan(long version, List<WorldChannel> channels) {

    public WorldPlan {
        channels = List.copyOf(channels);
    }

    /** 属于某个节点号的记录（节点身份 = 节点号，§4.10.1），保持 scene_id 无符号升序。 */
    public List<WorldChannel> channelsOn(int nodeId) {
        List<WorldChannel> out = new ArrayList<>();
        for (WorldChannel channel : channels) {
            if (channel.getNodeId() == nodeId) {
                out.add(channel);
            }
        }
        return out;
    }
}
