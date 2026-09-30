package com.game.gateway.gate;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import java.util.List;
import org.redisson.api.RedissonClient;

/**
 * {@link GateSource} 的 Redis 实现：读 xm-gate 发布到 {@link NodeDirectory} 的 {@link NodeTypes#GATE} 目录
 * （类型名与 xm-gate 共用同一常量）。
 */
public final class RedisGateSource implements GateSource {

    private final NodeDirectory<GateNodeInfo> directory;

    public RedisGateSource(RedissonClient redis) {
        this.directory = new NodeDirectory<>(redis, NodeTypes.GATE, GateNodeInfo.parser());
    }

    @Override
    public List<GateNodeInfo> listGates(int zoneId) {
        return directory.list(zoneId);
    }
}
