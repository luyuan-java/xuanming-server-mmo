package com.game.battle.directory;

import com.game.api.proto.BattleNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RedissonClient;

/**
 * {@link BattleDirectoryPublisher.Directory} 的生产实现：{@code NodeDirectory<BattleNodeInfo>}（节点类型 {@link NodeTypes#BATTLE}，
 * <b>作用域 0</b>：基线 battle 是全局池、不分 zone），键 {@code xm:nodes:battle:0}，字段为本节点号。阻塞调用，只在调度线程上用。
 */
public final class RedisBattleDirectory implements BattleDirectoryPublisher.Directory {

    /** battle 目录的作用域（全局池）。 */
    public static final int SCOPE = 0;
    /** 单条读的上限（租约丢失时「读 → 比 → 删」用）。 */
    static final Duration FIND_TIMEOUT = Duration.ofSeconds(3);

    private final NodeDirectory<BattleNodeInfo> directory;
    private final int nodeId;

    public RedisBattleDirectory(RedissonClient redis, int nodeId) {
        this(new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser()), nodeId);
    }

    public RedisBattleDirectory(NodeDirectory<BattleNodeInfo> directory, int nodeId) {
        this.directory = directory;
        this.nodeId = nodeId;
    }

    @Override
    public void publish(BattleNodeInfo info, Duration ttl) {
        directory.publish(SCOPE, nodeId, info, ttl);
    }

    @Override
    public Optional<BattleNodeInfo> find() {
        try {
            return directory.findAsync(SCOPE, nodeId).get(FIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读 battle 节点目录被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("读 battle 节点目录失败 node_id=" + nodeId, e);
        }
    }

    @Override
    public void remove() {
        directory.remove(SCOPE, nodeId);
    }
}
