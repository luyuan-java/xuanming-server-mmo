package com.game.inbox.gate;

import com.game.api.proto.SceneNodeInfo;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 生产用的写前门禁：zone 集合取 {@code game:world:zones}（scene 启动时登记，只增），逐 zone 读 scene 节点目录，所有在线节点都发布了
 * {@code inbox_consumer_enabled = true} 才放行（判据见 {@link ConsumerGate}）。zone 里一个节点都没有不算拦（那个 zone 此刻没人在线，
 * 以后有节点起来时它自己的开关会进目录）；但全部 zone 加起来一个节点都没有就拦。线程安全；每次现读，不缓存。
 */
public final class SceneDirectoryConsumerGate implements ConsumerGate {

    private final Supplier<List<Integer>> zones;
    private final IntFunction<List<SceneNodeInfo>> scenes;

    /**
     * @param zones  出现过 scene 节点的 zone（{@code WorldChannelStore#zones}）
     * @param scenes 某个 zone 下所有未过期的 scene 节点目录条目（{@code NodeDirectory#list}）
     */
    public SceneDirectoryConsumerGate(Supplier<List<Integer>> zones, IntFunction<List<SceneNodeInfo>> scenes) {
        this.zones = Objects.requireNonNull(zones, "zones");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    @Override
    public Result check() {
        try {
            int online = 0;
            for (int zone : zones.get()) {
                for (SceneNodeInfo node : scenes.apply(zone)) {
                    online++;
                    if (!node.getInboxConsumerEnabled()) {
                        return new Result(Verdict.CLOSED, "scene 节点没开收件箱消费 zone=" + Integer.toUnsignedString(node.getZoneId())
                                + " node=" + Integer.toUnsignedString(node.getNodeId()) + " instance=" + node.getInstanceId()
                                + "（game.scene.inbox.consumer-enabled=false，或是不认识这个开关的旧版本节点）");
                    }
                }
            }
            if (online == 0) {
                return new Result(Verdict.CLOSED, "没有在线的 scene 节点，无从确认消费端已开");
            }
            return Result.OPEN;
        } catch (RuntimeException e) {
            return new Result(Verdict.ERROR, "读 scene 节点目录失败: " + e);
        }
    }
}
