package com.game.match.testing;

import com.game.api.proto.BattleNodeInfo;
import com.game.match.gather.BattleNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link BattleNodes} 的测试替身：一份内存里的 battle 节点目录。选节点是<b>确定性</b>的（符合条件的条目里取登记最早的那个），
 * 这样「首选 A、被拒后换 B」的用例不靠运气；等概率这条性质由真实现自己的测试钉。
 *
 * <pre>
 * FakeBattleNodes nodes = new FakeBattleNodes();
 * nodes.add(FakeBattleNodes.node(1, "inst-a", 21200));      // accepting = true，rpc_host = 127.0.0.1
 * nodes.add(FakeBattleNodes.node(2, "inst-b", 21201));
 * nodes.set(FakeBattleNodes.node(1, "inst-a", 21200).toBuilder().setAccepting(false).build());   // 同号覆盖：关闸
 * nodes.readFailed = true;                                    // 目录读失败：pickRandom 为空、census.readFailed、lookup = ERROR
 * assertThat(nodes.picks).containsExactly(Set.of(), Set.of("1#inst-a"));   // 每次 pickRandom 的排除集合
 * </pre>
 * 线程安全。
 */
public final class FakeBattleNodes implements BattleNodes {

    /** 每次 {@code pickRandom} 收到的排除集合，按调用顺序。 */
    public final List<Set<String>> picks = new CopyOnWriteArrayList<>();
    /** 每次 {@code lookup} 的入参（{@link BattleNodes#key} 形式），按调用顺序。 */
    public final List<String> lookups = new CopyOnWriteArrayList<>();
    /** 目录读失败。 */
    public volatile boolean readFailed;
    private final List<BattleNodeInfo> entries = new ArrayList<>();

    /** 一条可分配的目录条目：{@code accepting = true}，直连地址 {@code 127.0.0.1:rpcPort}。 */
    public static BattleNodeInfo node(int nodeId, String instanceId, int rpcPort) {
        return BattleNodeInfo.newBuilder().setNodeId(nodeId).setInstanceId(instanceId).setRpcHost("127.0.0.1").setRpcPort(rpcPort)
                .setAccepting(true).build();
    }

    /** 登记一条（节点号已存在则报错：换实例请用 {@link #set}）。 */
    public synchronized FakeBattleNodes add(BattleNodeInfo info) {
        for (BattleNodeInfo existing : entries) {
            if (existing.getNodeId() == info.getNodeId()) {
                throw new IllegalArgumentException("节点号已登记: " + info.getNodeId());
            }
        }
        entries.add(info);
        return this;
    }

    /** 覆盖同号条目（位置不变；没有就追加）：模拟关闸、换实例。 */
    public synchronized FakeBattleNodes set(BattleNodeInfo info) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getNodeId() == info.getNodeId()) {
                entries.set(i, info);
                return this;
            }
        }
        entries.add(info);
        return this;
    }

    /** 摘掉一个节点号（进程退出 / 条目过期）。 */
    public synchronized FakeBattleNodes remove(int nodeId) {
        entries.removeIf(e -> e.getNodeId() == nodeId);
        return this;
    }

    @Override
    public synchronized Optional<BattleNodeInfo> pickRandom(Set<String> excludeKeys) {
        picks.add(Set.copyOf(excludeKeys));
        if (readFailed) {
            return Optional.empty();
        }
        for (BattleNodeInfo entry : entries) {
            if (allocatable(entry) && !excludeKeys.contains(BattleNodes.key(entry))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Census census() {
        if (readFailed) {
            return new Census(0, 0, true);
        }
        int accepting = 0;
        for (BattleNodeInfo entry : entries) {
            if (allocatable(entry)) {
                accepting++;
            }
        }
        return new Census(accepting, entries.size() - accepting, false);
    }

    @Override
    public synchronized Lookup lookup(int nodeId, String instanceId) {
        lookups.add(BattleNodes.key(nodeId, instanceId));
        if (readFailed) {
            return Lookup.ERROR;
        }
        for (BattleNodeInfo entry : entries) {
            if (entry.getNodeId() == nodeId) {
                return entry.getInstanceId().equals(instanceId) ? Lookup.SAME_INSTANCE : Lookup.OTHER_INSTANCE;
            }
        }
        return Lookup.ABSENT;
    }

    private static boolean allocatable(BattleNodeInfo entry) {
        return entry.getAccepting() && entry.getRpcPort() != 0 && !entry.getRpcHost().isBlank();
    }
}
