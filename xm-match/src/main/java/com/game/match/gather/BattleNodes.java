package com.game.match.gather;

import com.game.api.proto.BattleNodeInfo;
import com.game.common.deadline.Deadline;
import java.util.Optional;
import java.util.Set;

/**
 * battle 节点目录的读口（Redis {@code xm:nodes:battle:0}，由各 battle 节点每 5 s 写一次、TTL 15 s；match-spec §9.5、§9.6 第 2 步、§4.3）。
 * battle 是全服一个池、不分 zone。使用者：gather 选节点、凑单判断「池子空不空」、179 补签与 6.5 观众 RPC 的直拨判死。
 *
 * <p><b>契约</b>：每个方法都<b>阻塞</b>（一次 Redis 读，受 Redis 客户端自己的超时约束，缺省最坏约 4.2 s；两个 {@code lookup} 另有更短的上限）、<b>永不抛异常</b>——读失败各有各的
 * 返回形态（见方法注释），因为各调用方对「读不到」的处理都是保守的那一支。在凑单线程、工作线程、gather 与 163 的虚拟线程上调；线程安全。
 * 目录最多滞后 5 s：选中的节点可能刚关闸，所以建房被节点级拒绝后「换一个节点重试一次」仍然必须保留。
 */
public interface BattleNodes {

    /**
     * 排除集合里的键：(节点号, 实例) 一对。按一对排除而不是只按节点号——节点号会被新进程复用，新进程是另一个可以尝试的节点。
     */
    static String key(int nodeId, String instanceId) {
        return Integer.toUnsignedString(nodeId) + "#" + (instanceId == null ? "" : instanceId);
    }

    /** {@link #key(int, String)} 的便捷形式。 */
    static String key(BattleNodeInfo node) {
        return key(node.getNodeId(), node.getInstanceId());
    }

    /**
     * 从 {@code accepting = true}、直连地址可用（{@code rpc_port ≠ 0} 且 {@code rpc_host} 非空）、且不在排除集合里的条目中<b>等概率</b>选一个。
     *
     * @param excludeKeys 已经试过的节点（{@link #key}）；首选时传空集合
     * @return 选中的条目；没有可选的、或目录读失败（已记 WARN）为空——gather 首选时按 {@code no_battle_node}、换节点时按「没有可换的节点」处理
     */
    Optional<BattleNodeInfo> pickRandom(Set<String> excludeKeys);

    /**
     * 目录概况。
     *
     * @param accepting    可分配的节点数（{@code accepting = true} 且直连地址可用）
     * @param notAccepting 其余在目录里的节点数（关闸中 / 还没导出控制面）
     * @param readFailed   目录读失败（此时两个计数都是 0）
     */
    record Census(int accepting, int notAccepting, boolean readFailed) {

        /** 凑单是否应该暂停：读不到目录、或没有任何可分配的节点。 */
        public boolean nothingAllocatable() {
            return readFailed || accepting == 0;
        }
    }

    /** 读一次目录概况：凑单每轮在抢锁之前调一次（暂停判定），并据此刷新 {@code xm_match_battle_nodes} 指标。 */
    Census census();

    /** 目录里某个节点号此刻的情况（相对于调用方记下的实例）。 */
    enum Lookup {
        /** 目录里有这个节点号，实例就是调用方给的那个：原进程还在册。 */
        SAME_INSTANCE,
        /** 目录里有这个节点号，但实例不同：<b>号已被别的进程接手</b>（补签判死需要的正面证据）。 */
        OTHER_INSTANCE,
        /** 目录里没有这个节点号（进程退出、丢了租约但还活着、或条目刚过期）：不能证明房间没了。 */
        ABSENT,
        /** 目录读失败或条目损坏：不能证明任何事。 */
        ERROR
    }

    /**
     * 按节点号查一条目录条目并与 {@code instanceId} 比对。179 补签只在<b>直拨建连失败</b>之后才调它：
     * 只有 {@link Lookup#OTHER_INSTANCE} 才能据以判「这局确实没了」，其余三种一律按「暂不可用」。
     * 等待上限是实现里的固定值（生产 1 s）；要按调用方的预算收短用带截止的重载。
     */
    Lookup lookup(int nodeId, String instanceId);

    /**
     * 同上，但<b>至多等到 {@code d}</b>（并且仍不超过不带截止那个重载的固定上限）：6.5 的观众 RPC（163、开局清退）经带硬截止的直拨走到这里
     * （lead 裁决 3）。{@code d} 已过时不读目录，直接 {@link Lookup#ERROR}；等到 {@code d} 还没读出来同样是 {@link Lookup#ERROR}——
     * 「不能证明任何事」，调用方据此不判死。其余结果与不带截止的重载相同。永不抛异常。
     *
     * @param d 这次读的截止（非 null）
     */
    Lookup lookup(int nodeId, String instanceId, Deadline d);
}
