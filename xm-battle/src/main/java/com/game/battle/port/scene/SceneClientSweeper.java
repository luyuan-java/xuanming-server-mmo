package com.game.battle.port.scene;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * battle → scene 直连客户端缓存的清扫（做法同 xm-guild 的 {@code SceneEndpointSweeper}；{@code NodeRpcClients} 的类注释把「节点从目录消失时清掉」
 * 交给调用方）。scene 节点下线或换地址（容器重启换 IP）之后，它的 Dubbo 引用留着只会按 1 s 间隔对一个没人监听的地址反复重连，battle 进程长期
 * 运行时引用与重连任务只增不减。正确性不依赖它（找不到节点 = 不发 / 本轮无目标；连不上 = 传输失败，等补发 / 重投）。
 *
 * <ul>
 *   <li><b>只登记发过调用的节点</b>（{@link #track}：传输每次按定位器 / 目录 Found 到的地址发调用时登记），所以登记集合就是缓存里可能有的客户端。</li>
 *   <li>{@link #sweep}：按登记节点所在的 zone 现读 scene 节点目录；目录里已经没有、换了实例、或不再提供直连地址（rpc_port = 0 / 越界）的，
 *       <b>逐个</b>销毁（{@code NodeRpcClients.evict}，只销毁同地址同实例的那一个）。<b>读目录失败的 zone 本轮跳过</b>——绝不拿不完整的目录去做
 *       「只保留这些」，那会误毁仍在用的引用，让在途调用按传输失败处理。同地址换实例时客户端缓存自己会重建，这里只管「消失」。</li>
 *   <li><b>线程</b>：{@link #sweep} 阻塞读 Redis（每个 zone 一次 HGETALL），只在本类自己的后台守护线程 {@value #THREAD_NAME} 上跑
 *       （{@link #start}），<b>不得</b>落在 {@code battle-outbox} 或逻辑线程上；{@link #track} 任意线程可调、不阻塞。{@link #close} 停掉线程，幂等。</li>
 * </ul>
 * 线程安全。
 */
final class SceneClientSweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneClientSweeper.class);

    /** 清扫间隔（代码常量，不开放配置；同 guild 资产通道的清扫节奏：低频即可，早一轮晚一轮只影响多重连几十次）。 */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(60);
    static final String THREAD_NAME = "battle-scene-sweep";

    private final IntFunction<List<SceneNodeInfo>> directory;
    private final Consumer<SceneAssetEndpoint> evictor;
    /** address → 最近一次发过调用的节点。 */
    private final Map<String, SceneAssetEndpoint> tracked = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    /** 持 {@link #lifecycle}。 */
    private ScheduledExecutorService scheduler;
    /** 持 {@link #lifecycle}。 */
    private boolean closed;

    /**
     * @param directory 按 zone 列出 scene 节点目录（生产 {@code NodeDirectory<SceneNodeInfo>::list}，阻塞）
     * @param evictor   销毁一个节点的客户端（生产 {@code NodeRpcClients::evict}；只销毁同地址同实例的那一个）
     */
    SceneClientSweeper(IntFunction<List<SceneNodeInfo>> directory, Consumer<SceneAssetEndpoint> evictor) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.evictor = Objects.requireNonNull(evictor, "evictor");
    }

    /** 对这个节点发过一次调用（传输在每次调用时调）。同地址换了实例就以新的为准。 */
    void track(SceneAssetEndpoint endpoint) {
        tracked.put(endpoint.address(), endpoint);
    }

    /** 当前登记的节点数（测试 / 排障用）。 */
    int trackedCount() {
        return tracked.size();
    }

    /** 开始按 {@code interval} 周期清扫（第一次在一个间隔之后），在自己的守护线程上。重复调用、关闭之后调用都无效。 */
    void start(Duration interval) {
        long millis = interval.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("清扫间隔必须为正: " + interval);
        }
        synchronized (lifecycle) {
            if (closed || scheduler != null) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::sweepQuietly, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    /** 清扫线程在跑吗（测试用）。 */
    boolean running() {
        synchronized (lifecycle) {
            return scheduler != null && !scheduler.isShutdown();
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Throwable t) {
            // 周期任务抛出异常就不再调度：吞在这里，下一轮照常
            log.error("清扫 battle → scene 直连客户端缓存出错（下一轮照常）", t);
        }
    }

    /** 清扫一轮（阻塞读目录）；读目录失败的 zone 本轮跳过（不误删）。返回销毁的条数。 */
    int sweep() {
        Map<Integer, Set<String>> liveByZone = new HashMap<>();
        int evicted = 0;
        for (SceneAssetEndpoint endpoint : List.copyOf(tracked.values())) {
            Set<String> live;
            if (liveByZone.containsKey(endpoint.zoneId())) {
                live = liveByZone.get(endpoint.zoneId());
            } else {
                live = readZone(endpoint.zoneId());
                liveByZone.put(endpoint.zoneId(), live);
            }
            if (live == null || live.contains(key(endpoint))) {
                continue;
            }
            // 只在登记没被更新过时才摘（清扫期间同地址又发了调用、换了实例的，留给下一轮）
            if (tracked.remove(endpoint.address(), endpoint)) {
                evictor.accept(endpoint);
                evicted++;
                log.info("scene 节点已不在目录（或换了实例 / 不再提供直连地址），销毁直连客户端 zone={} node={} address={} instance={}",
                        Integer.toUnsignedString(endpoint.zoneId()), Integer.toUnsignedString(endpoint.nodeId()), endpoint.address(),
                        endpoint.instanceId());
            }
        }
        return evicted;
    }

    /** 某 zone 里提供直连地址的节点（address#instance）；读失败为 null。 */
    private Set<String> readZone(int zoneId) {
        try {
            Set<String> live = new HashSet<>();
            for (SceneNodeInfo info : directory.apply(zoneId)) {
                if (info.getRpcPort() == 0 || info.getRpcHost().isBlank() || Integer.compareUnsigned(info.getRpcPort(), 65535) > 0) {
                    continue;
                }
                live.add(key(new SceneAssetEndpoint(info.getZoneId(), info.getNodeId(), info.getInstanceId(), info.getRpcHost(),
                        info.getRpcPort())));
            }
            return live;
        } catch (RuntimeException e) {
            log.warn("读 scene 节点目录失败，本轮不清扫这个 zone 的直连客户端 zone={}: {}", Integer.toUnsignedString(zoneId), e.toString());
            return null;
        }
    }

    private static String key(SceneAssetEndpoint endpoint) {
        return endpoint.address() + "#" + endpoint.instanceId();
    }

    /** 停掉清扫线程（正在读目录的那一轮被中断，不等它）。幂等；之后 {@link #start} 无效。 */
    @Override
    public void close() {
        ScheduledExecutorService s;
        synchronized (lifecycle) {
            closed = true;
            s = scheduler;
            scheduler = null;
        }
        if (s != null) {
            s.shutdownNow();
        }
    }
}
