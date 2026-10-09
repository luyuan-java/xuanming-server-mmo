package com.game.discovery.gate;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.drain.GateDrainMarks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GateSource} 的 Redis 实现：读 xm-gate 发布到 {@link NodeDirectory} 的 {@link NodeTypes#GATE} 目录
 * （类型名与 xm-gate 共用同一常量），再一次 MGET 叠加运维打的排空标记（{@link GateDrainMarks}）——标记在的条目
 * {@code draining=true}（标记的实例须与条目一致），由 {@link GatePicker} 剔除。
 *
 * <p>排空标记读不到时当作「没人在排空」（同基线 DrainingGates：这条路径的失败方向必须是放行，不能因为标记读不到就挡掉全部登录）；
 * 目录本身读不到照旧抛出（调用方 fail-closed）。
 */
public final class RedisGateSource implements GateSource {

    private static final Logger log = LoggerFactory.getLogger(RedisGateSource.class);

    private final NodeDirectory<GateNodeInfo> directory;
    private final GateDrainMarks drainMarks;

    public RedisGateSource(RedissonClient redis) {
        this.directory = new NodeDirectory<>(redis, NodeTypes.GATE, GateNodeInfo.parser());
        this.drainMarks = new GateDrainMarks(redis);
    }

    @Override
    public List<GateNodeInfo> listGates(int zoneId) {
        List<GateNodeInfo> gates = directory.list(zoneId);
        if (gates.isEmpty()) {
            return gates;
        }
        Map<Integer, GateDrainMarks.Mark> draining;
        try {
            draining = drainMarks.draining(zoneId, gates.stream().map(GateNodeInfo::getNodeId).toList());
        } catch (RuntimeException e) {
            log.error("读取 gate 排空标记失败，按没人在排空处理 zone={}: {}", zoneId, e.toString());
            return gates;
        }
        if (draining.isEmpty()) {
            return gates;
        }
        List<GateNodeInfo> out = new ArrayList<>(gates.size());
        for (GateNodeInfo gate : gates) {
            GateDrainMarks.Mark mark = draining.get(gate.getNodeId());
            // 标记绑实例：节点号被新实例复用时，旧实例的标记不算数
            out.add(mark != null && mark.appliesTo(gate.getInstanceId()) ? gate.toBuilder().setDraining(true).build() : gate);
        }
        return out;
    }
}
