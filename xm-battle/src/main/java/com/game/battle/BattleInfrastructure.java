package com.game.battle;

import com.game.api.BattleNodeService;
import com.game.battle.directory.BattleDirectoryPublisher;
import com.game.battle.directory.RedisBattleDirectory;
import com.game.battle.edge.BattleEdgeServer;
import com.game.battle.edge.DirectEdge;
import com.game.battle.edge.EdgeDependencies;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.BattleRoomServiceImpl;
import com.game.battle.room.RoomDependencies;
import com.game.battle.rpc.BattleRpcServer;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import org.redisson.api.RedissonClient;

/**
 * {@link BattleNode} 在启动时才能建的部件（要 Redis、要开端口、要节点身份）的工厂：生产实现 {@link #production(RedissonClient)}，
 * 测试换成假实现，就能在没有 Redis、不开端口的情况下核对启停顺序与租约丢失处置（battle-node-spec §13.5）。
 * 节点自己的线程（逻辑线程、调度线程、回复执行器）与准入闸不在这里：它们的创建与释放顺序就是 {@link BattleNode} 要守的契约。
 */
public interface BattleInfrastructure {

    /** 节点号租约丢失回调与有效性（生产包装 {@link NodeIdLease}）。 */
    interface Lease extends AutoCloseable {

        int nodeId();

        /** 现在能否用这个号接新房间（未丢失且续期及时）；任意线程可读。 */
        boolean isValid();

        /** 是否已丢失（终态）；任意线程可读。 */
        boolean isLost();

        /** 交还租约（只删仍属于本实例的键）。幂等，不抛异常。 */
        @Override
        void close();
    }

    /** 已导出的控制面（生产 {@link BattleRpcServer}）。 */
    interface RpcExport extends AutoCloseable {

        int port();

        /** 反导出（等在途调用至多 Dubbo 停服等待的 1/3）。幂等，不抛异常。 */
        @Override
        void close();
    }

    /**
     * 占节点号租约（{@link NodeTypes#BATTLE}，作用域 0）。号段占满或 Redis 不可达时抛异常（拒绝启动）。
     *
     * @param scheduler 续期用的调度线程（{@code battle-sched}）
     * @param onLost    租约丢失回调（在调度线程上，只调一次）
     */
    Lease acquireLease(ScheduledExecutorService scheduler, String instanceId, Runnable onLost);

    /** 本节点号的目录存取（{@code xm:nodes:battle:0}）。 */
    BattleDirectoryPublisher.Directory directory(int nodeId);

    /** 导出控制面（阻塞到端口开好；端口被占 / 缺 {@code XM_DUBBO_SECRET} 抛 {@link IllegalStateException}）。 */
    RpcExport exportRpc(BattleNodeService provider, String host, int port);

    /** 房间服务（生产 {@link BattleRoomServiceImpl}）。 */
    BattleRoomService rooms(RoomDependencies deps);

    /** 客户端直连面（生产 {@link BattleEdgeServer}；构造不绑端口，{@link DirectEdge#start()} 才绑）。 */
    DirectEdge edge(EdgeDependencies deps);

    /** 生产实现：Redis 租约与目录、Dubbo Triple 导出、真房间服务与真直连面。 */
    static BattleInfrastructure production(RedissonClient redis) {
        return new Production(redis);
    }

    /** 生产实现。 */
    final class Production implements BattleInfrastructure {

        /** 节点号区间（票据 {@code battle_node_id} 是 uint32；区间同其它节点的雪花 worker 上限，够用且占号扫描有界）。 */
        static final int MIN_NODE_ID = 1;
        static final int MAX_NODE_ID = 1023;
        /** 租约 TTL（同 scene / gate：每 5 s 续期，有效性界 10 s）。 */
        static final Duration LEASE_TTL = Duration.ofSeconds(15);

        private final RedissonClient redis;

        Production(RedissonClient redis) {
            this.redis = Objects.requireNonNull(redis, "redis");
        }

        @Override
        public Lease acquireLease(ScheduledExecutorService scheduler, String instanceId, Runnable onLost) {
            NodeIdLease lease = NodeIdLease.acquire(redis, scheduler, NodeTypes.BATTLE, RedisBattleDirectory.SCOPE,
                    MIN_NODE_ID, MAX_NODE_ID, instanceId, LEASE_TTL, onLost);
            return new Lease() {
                @Override
                public int nodeId() {
                    return lease.nodeId();
                }

                @Override
                public boolean isValid() {
                    return lease.isValid();
                }

                @Override
                public boolean isLost() {
                    return lease.isLost();
                }

                @Override
                public void close() {
                    lease.close();
                }
            };
        }

        @Override
        public BattleDirectoryPublisher.Directory directory(int nodeId) {
            return new RedisBattleDirectory(redis, nodeId);
        }

        @Override
        public RpcExport exportRpc(BattleNodeService provider, String host, int port) {
            BattleRpcServer server = BattleRpcServer.export(provider, host, port);
            return new RpcExport() {
                @Override
                public int port() {
                    return server.port();
                }

                @Override
                public void close() {
                    server.close();
                }
            };
        }

        @Override
        public BattleRoomService rooms(RoomDependencies deps) {
            return new BattleRoomServiceImpl(deps);
        }

        @Override
        public DirectEdge edge(EdgeDependencies deps) {
            return new BattleEdgeServer(deps);
        }
    }
}
