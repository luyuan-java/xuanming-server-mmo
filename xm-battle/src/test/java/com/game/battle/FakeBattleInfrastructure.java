package com.game.battle;

import com.game.api.BattleNodeService;
import com.game.api.proto.BattleNodeInfo;
import com.game.battle.directory.BattleDirectoryPublisher;
import com.game.battle.edge.DirectEdge;
import com.game.battle.edge.EdgeDependencies;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.RoomDependencies;
import com.game.battle.testing.StubBattleRoomService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * {@link BattleInfrastructure} 的假实现：不连 Redis、不开端口，把每一步按发生顺序记进 {@link #events}，用来核对 {@link BattleNode} 的启停顺序
 * 与租约丢失处置（battle-node-spec §7.10、§7.11）。房间服务用 {@link StubBattleRoomService}，它的每次调用也记进同一条事件流
 * （带当时的准入闸阶段）。线程安全。
 */
final class FakeBattleInfrastructure implements BattleInfrastructure {

    final List<String> events = new CopyOnWriteArrayList<>();
    final StubBattleRoomService rooms = new StubBattleRoomService();
    final List<BattleNodeInfo> published = new CopyOnWriteArrayList<>();
    volatile int nodeId = 7;
    volatile int rpcPort = 21234;
    volatile boolean leaseValid = true;
    volatile boolean leaseLost;
    volatile Runnable onLost;
    volatile String instanceId;
    volatile BattleNodeInfo current;
    volatile RoomDependencies roomDeps;
    volatile EdgeDependencies edgeDeps;
    volatile BattleNodeService provider;
    volatile int connections;
    /** 非 null 时在 edge.start() 里执行（模拟绑定失败、启动途中租约丢失）。 */
    volatile Runnable onEdgeStart;
    /** 非 null 时在 rpc.close() 里执行（模拟反导出期间进来的调用）。 */
    volatile Runnable onRpcClose;
    /** 准入闸阶段的名字（记进 edge.start 与房间调用的事件；测试接到真准入闸上）。 */
    volatile Supplier<String> phase = () -> "?";

    FakeBattleInfrastructure() {
        rooms.beforeCall = method -> events.add("rooms." + method + "@" + phase.get());
    }

    @Override
    public Lease acquireLease(ScheduledExecutorService scheduler, String instanceId, Runnable onLost) {
        events.add("lease.acquire");
        this.instanceId = instanceId;
        this.onLost = onLost;
        return new Lease() {
            @Override
            public int nodeId() {
                return nodeId;
            }

            @Override
            public boolean isValid() {
                return leaseValid && !leaseLost;
            }

            @Override
            public boolean isLost() {
                return leaseLost;
            }

            @Override
            public void close() {
                events.add("lease.close");
            }
        };
    }

    /** 模拟租约丢失：先置位再回调（同 {@code NodeIdLease.markLost}）。 */
    void loseLease() {
        leaseLost = true;
        onLost.run();
    }

    @Override
    public BattleDirectoryPublisher.Directory directory(int nodeId) {
        events.add("directory:" + nodeId);
        return new BattleDirectoryPublisher.Directory() {
            @Override
            public void publish(BattleNodeInfo info, Duration ttl) {
                events.add("directory.publish(accepting=" + info.getAccepting() + ")");
                published.add(info);
                current = info;
            }

            @Override
            public Optional<BattleNodeInfo> find() {
                return Optional.ofNullable(current);
            }

            @Override
            public void remove() {
                events.add("directory.remove");
                current = null;
            }
        };
    }

    @Override
    public RpcExport exportRpc(BattleNodeService provider, String host, int port) {
        events.add("rpc.export:" + host + ":" + port);
        this.provider = provider;
        return new RpcExport() {
            @Override
            public int port() {
                return rpcPort;
            }

            @Override
            public void close() {
                Runnable hook = onRpcClose;
                if (hook != null) {
                    hook.run();
                }
                events.add("rpc.close");
            }
        };
    }

    @Override
    public BattleRoomService rooms(RoomDependencies deps) {
        events.add("rooms.create");
        this.roomDeps = deps;
        return rooms;
    }

    @Override
    public DirectEdge edge(EdgeDependencies deps) {
        events.add("edge.create");
        this.edgeDeps = deps;
        return new DirectEdge() {
            @Override
            public void start() {
                events.add("edge.start@" + phase.get());
                Runnable hook = onEdgeStart;
                if (hook != null) {
                    hook.run();
                }
            }

            @Override
            public void stopAccepting() {
                events.add("edge.stopAccepting");
            }

            @Override
            public void drainAndClose(Duration timeout) {
                events.add("edge.drainAndClose:" + timeout.toMillis() + "ms");
            }

            @Override
            public int connectionCount() {
                return connections;
            }
        };
    }

    /** 只保留以 {@code prefix} 之一开头的事件（便于断言子序列）。 */
    List<String> eventsMatching(String... prefixes) {
        return events.stream().filter(e -> {
            for (String prefix : prefixes) {
                if (e.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }
}
