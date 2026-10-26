package com.game.team.push;

import com.game.api.SceneTeamService;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 与 guild 的 SceneEndpointSweeper 同样按目录回收消失 / 换实例的引用，避免旧地址永久重连。
 * 每个有过调用的 zone 每分钟读取一次；读失败保留该 zone。目录 IO 只在专用清扫线程执行。
 * 登记与发调用、条件移除与 evict 各自原子化，旧清扫快照不能销毁刚被重新使用的引用。
 */
public final class SceneTeamEndpointSweeper implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SceneTeamEndpointSweeper.class);
    private static final Duration INTERVAL = Duration.ofMinutes(1);
    interface Clients {
        <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout,
                                      Function<SceneTeamService, CompletableFuture<R>> invocation);
        void evict(NodeRpcClients.Target target);
    }
    /** 每次调用新建身份，不能只按 endpoint 值比较，否则同实例的重新使用会被旧快照误删。 */
    private static final class Use {
        final SceneAssetEndpoint endpoint;
        Use(SceneAssetEndpoint endpoint) { this.endpoint = endpoint; }
    }
    private final IntFunction<List<SceneNodeInfo>> directory;
    private final Clients clients;
    private final Map<String, Use> tracked = new HashMap<>();
    private ScheduledExecutorService scheduler;
    private boolean closed;

    public SceneTeamEndpointSweeper(IntFunction<List<SceneNodeInfo>> directory, NodeRpcClients<SceneTeamService> clients) {
        this(directory, new Clients() {
            @Override public <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout,
                    Function<SceneTeamService, CompletableFuture<R>> invocation) {
                return clients.call(target, timeout, invocation);
            }
            @Override public void evict(NodeRpcClients.Target target) { clients.evict(target); }
        });
    }

    SceneTeamEndpointSweeper(IntFunction<List<SceneNodeInfo>> directory, Clients clients) {
        this.directory = Objects.requireNonNull(directory);
        this.clients = Objects.requireNonNull(clients);
    }

    /** 不等待 IO；由独立信号池调用。与清扫移除同一把锁，避免先登记、被清掉后才建出无主引用。 */
    public synchronized <R> CompletableFuture<R> call(SceneAssetEndpoint endpoint, Duration timeout,
            Function<SceneTeamService, CompletableFuture<R>> invocation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("组队场景清扫器已关闭"));
        tracked.put(endpoint.address(), new Use(endpoint));
        return clients.call(target(endpoint), timeout, invocation);
    }

    public synchronized void start() {
        if (closed || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("team-scene-sweep").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try { sweep(); }
            catch (RuntimeException e) { log.warn("[team] 场景客户端清扫失败，下一轮重试", e); }
        }, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 目录读失败的 zone 不误删；公开以便运维与确定性测试触发一轮。 */
    public int sweep() {
        List<Use> snapshot;
        synchronized (this) {
            if (closed) return 0;
            snapshot = List.copyOf(tracked.values());
        }
        Map<Integer, Set<String>> liveByZone = new HashMap<>();
        int removed = 0;
        for (Use use : snapshot) {
            SceneAssetEndpoint endpoint = use.endpoint;
            int zone = endpoint.zoneId();
            if (!liveByZone.containsKey(zone)) liveByZone.put(zone, readZone(zone));
            Set<String> live = liveByZone.get(zone);
            if (live == null || live.contains(endpoint.address() + "#" + endpoint.instanceId())) continue;
            synchronized (this) {
                if (!closed && tracked.remove(endpoint.address(), use)) {
                    clients.evict(target(endpoint));
                    removed++;
                }
            }
        }
        return removed;
    }

    private Set<String> readZone(int zone) {
        try {
            Set<String> live = new HashSet<>();
            for (SceneNodeInfo info : directory.apply(zone)) {
                if (info.getZoneId() != zone || info.getRpcHost().isBlank() || info.getRpcPort() <= 0 || info.getRpcPort() > 65535) continue;
                live.add(new NodeRpcClients.Target(info.getRpcHost(), info.getRpcPort(), info.getInstanceId()).address()
                        + "#" + info.getInstanceId());
            }
            return live;
        } catch (RuntimeException e) {
            log.warn("[team] 读 scene 目录失败，本轮保留该区客户端 zone={}: {}", Integer.toUnsignedString(zone), e.toString());
            return null;
        }
    }

    private static NodeRpcClients.Target target(SceneAssetEndpoint endpoint) {
        return new NodeRpcClients.Target(endpoint.host(), endpoint.port(), endpoint.instanceId());
    }

    /** 专用信号池先排空；本组件停止清扫后，Spring 才关闭 NodeRpcClients 和 Redis。 */
    @Override public void close() {
        ScheduledExecutorService stopping;
        synchronized (this) {
            if (closed) return;
            closed = true;
            stopping = scheduler;
            scheduler = null;
            tracked.clear();
        }
        if (stopping != null) {
            stopping.shutdownNow();
            try { stopping.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
