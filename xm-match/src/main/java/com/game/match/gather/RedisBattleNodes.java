package com.game.match.gather;

import com.game.api.proto.BattleNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.match.metrics.MatchMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BattleNodes} 的生产实现：读 Redis 的 battle 节点目录 {@code xm:nodes:battle:0}（全服一个池，作用域 0；各 battle 节点每 5 s 写一次、
 * TTL 15 s）。每次调用现读一遍目录，不在本进程缓存——gather 的频率远低于目录的刷新频率，现读省掉一套「镜像何时过期」的状态。
 *
 * <p><b>可分配</b> = {@code accepting = true} 且直连地址可用（{@code rpc_host} 非空、{@code rpc_port} 在 1..65535）。{@link #pickRandom} 在
 * 「可分配且不在排除集合里」的条目中<b>等概率</b>选一个（基线 v1 同样是纯随机，不看负载）；排除按 (节点号, 实例) 一对——节点号会被新进程复用。
 *
 * <p>三个方法都不抛异常：目录读失败时 {@link #pickRandom} 为空、{@link #census} 带 {@code readFailed}、{@link #lookup} 是
 * {@link Lookup#ERROR}。只在 future / Redis 客户端的同步等待上阻塞（不持锁），可以在虚拟线程上调。线程安全。
 */
public final class RedisBattleNodes implements BattleNodes {

    private static final Logger log = LoggerFactory.getLogger(RedisBattleNodes.class);

    /** battle 池的目录作用域：全服一个池，不分 zone。 */
    static final int BATTLE_SCOPE = 0;
    /** 判死时读单条目录条目的等待上限：读不出来就按「不能证明任何事」，不让补签为它耗掉整个请求预算。 */
    static final long LOOKUP_WAIT_MS = 1_000;

    /** 目录的两种读（生产是 {@link NodeDirectory}；单测用内存实现）。 */
    public interface Directory {

        /** 全部未过期的条目；读失败抛 RuntimeException。 */
        List<BattleNodeInfo> list();

        /** 按节点号读一条；没有为空；读失败或条目损坏时 future 异常完成（或直接抛 RuntimeException）。 */
        CompletableFuture<Optional<BattleNodeInfo>> find(int nodeId);
    }

    private final Directory directory;
    private final MatchMetrics metrics;
    private final IntUnaryOperator random;

    /** 生产装配。 */
    public RedisBattleNodes(NodeDirectory<BattleNodeInfo> directory, MatchMetrics metrics) {
        this(new Directory() {
            @Override
            public List<BattleNodeInfo> list() {
                return directory.list(BATTLE_SCOPE);
            }

            @Override
            public CompletableFuture<Optional<BattleNodeInfo>> find(int nodeId) {
                return directory.findAsync(BATTLE_SCOPE, nodeId);
            }
        }, metrics, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /**
     * @param random 给上界（&gt; 0）、回 [0, 上界) 里的一个下标；生产是均匀随机，测试可以换成确定的
     */
    RedisBattleNodes(Directory directory, MatchMetrics metrics, IntUnaryOperator random) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public Optional<BattleNodeInfo> pickRandom(Set<String> excludeKeys) {
        Objects.requireNonNull(excludeKeys, "excludeKeys");
        List<BattleNodeInfo> entries;
        try {
            entries = directory.list();
        } catch (RuntimeException e) {
            log.warn("读 battle 节点目录失败，本次选不出节点: {}", e.toString());
            return Optional.empty();
        }
        List<BattleNodeInfo> candidates = new ArrayList<>(entries.size());
        for (BattleNodeInfo entry : entries) {
            if (allocatable(entry) && !excludeKeys.contains(BattleNodes.key(entry))) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = random.applyAsInt(candidates.size());
        if (index < 0 || index >= candidates.size()) {
            throw new IllegalStateException("随机下标越界: " + index + " / " + candidates.size());
        }
        return Optional.of(candidates.get(index));
    }

    @Override
    public Census census() {
        List<BattleNodeInfo> entries;
        try {
            entries = directory.list();
        } catch (RuntimeException e) {
            log.warn("读 battle 节点目录失败，按没有可分配的节点处理: {}", e.toString());
            metrics.battleNodes(0, 0);
            return new Census(0, 0, true);
        }
        int accepting = 0;
        for (BattleNodeInfo entry : entries) {
            if (allocatable(entry)) {
                accepting++;
            }
        }
        int notAccepting = entries.size() - accepting;
        metrics.battleNodes(accepting, notAccepting);
        return new Census(accepting, notAccepting, false);
    }

    @Override
    public Lookup lookup(int nodeId, String instanceId) {
        String wanted = instanceId == null ? "" : instanceId;
        Optional<BattleNodeInfo> found;
        try {
            CompletableFuture<Optional<BattleNodeInfo>> read = directory.find(nodeId);
            if (read == null) {
                return Lookup.ERROR;
            }
            found = read.get(LOOKUP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Lookup.ERROR;
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            log.warn("读 battle 节点目录条目失败 node={}: {}", Integer.toUnsignedString(nodeId), String.valueOf(e.getCause() == null ? e : e.getCause()));
            return Lookup.ERROR;
        }
        if (found == null) {
            return Lookup.ERROR;
        }
        if (found.isEmpty()) {
            return Lookup.ABSENT;
        }
        BattleNodeInfo entry = found.get();
        if (entry.getNodeId() != nodeId || entry.getInstanceId().isEmpty()) {
            // 条目与键不符、或没有实例号：正常写者不会产生，不能据以证明任何事
            log.warn("battle 节点目录条目损坏 key_node={} entry_node={} entry_instance='{}'", Integer.toUnsignedString(nodeId),
                    Integer.toUnsignedString(entry.getNodeId()), entry.getInstanceId());
            return Lookup.ERROR;
        }
        return entry.getInstanceId().equals(wanted) ? Lookup.SAME_INSTANCE : Lookup.OTHER_INSTANCE;
    }

    /** 可分配：准入闸开着，且控制面的直连地址可用（导出成功之后才写地址）。 */
    static boolean allocatable(BattleNodeInfo entry) {
        int port = entry.getRpcPort();
        return entry.getAccepting() && port >= 1 && port <= 65535 && !entry.getRpcHost().isBlank();
    }
}
