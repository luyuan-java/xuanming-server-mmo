package com.game.gate.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.PlayerEnter;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.LinkDrop;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按需建立、复用到各 scene 节点的链路：每个节点至多一条接新绑定的当前链路；被取代的旧代次在有限宽限内排空。
 *
 * <p><b>节点号租约</b>：新建链路（以及建链时的握手）前检查 {@code newLinksAllowed}（生产为 gate 节点号租约的
 * {@code isValid}）。租约无效时不建新链路、{@link #send} 对没有活链路的节点返回 0（调用方按「链路层不可用」处理，
 * 进场回 3023）：丢了租约的 gate 进程用着一个可能已归别人的节点号，scene 按节点号登记链路，它一建链就会顶掉新持有者
 * 的链路、踢掉新持有者的玩家。已就绪的旧链路照常发帧，直到它们自然断开。
 *
 * <p>装配顺序：构造 → {@link #bindListener}（一次）→ 使用 → {@link #close}。
 */
public final class SceneLinkManager implements SceneLinks, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneLinkManager.class);

    private final Supplier<LinkHello> hellos;
    private final LinkConnector connector;
    private final LinkSettings settings;
    private final BooleanSupplier newLinksAllowed;
    private final GateMetrics metrics;
    private final ConcurrentHashMap<Integer, SceneLink> links = new ConcurrentHashMap<>();
    private record LinkKey(int nodeId, long generation) { }
    private record Retired(SceneLink link, long deadlineNanos) { }
    private final ConcurrentHashMap<LinkKey, Retired> retired = new ConcurrentHashMap<>();
    private final Object directoryLock = new Object();
    private final Map<Integer, Long> maximumEpochs = new HashMap<>();
    private final Map<Integer, String> legacyCandidates = new HashMap<>();
    private final LongSupplier nanoClock;
    private final AtomicLong generations = new AtomicLong();
    private volatile SceneLinkListener listener;
    private volatile boolean closed;

    /**
     * @param hellos          每次 TCP 连上时调用一次，生成本 gate 的链路握手帧（gate 节点号、实例 uuid、zone、租约代次，以及
     *                        建链时刻的鉴权时间戳与 MAC，见 {@link LinkHellos}）；在链路 I/O 线程上调用，不得阻塞。
     *                        scene 回的 ack 必须与它同 zone
     * @param newLinksAllowed 现在能不能新建链路（节点号租约有效）；任意线程调用，不得阻塞
     * @param metrics         链路指标（帧收发、丢帧、状态变化）
     */
    public SceneLinkManager(Supplier<LinkHello> hellos, LinkConnector connector, LinkSettings settings,
                            BooleanSupplier newLinksAllowed, GateMetrics metrics) {
        this(hellos, connector, settings, newLinksAllowed, metrics, System::nanoTime);
    }

    SceneLinkManager(Supplier<LinkHello> hellos, LinkConnector connector, LinkSettings settings,
                     BooleanSupplier newLinksAllowed, GateMetrics metrics, LongSupplier nanoClock) {
        this.hellos = hellos;
        this.connector = connector;
        this.settings = settings;
        this.newLinksAllowed = newLinksAllowed;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
    }

    @Override
    public long send(int sceneNodeId, long generation, NodeLinkFrame frame) {
        if (closed || generation == 0) return 0;
        SceneLink link = links.get(sceneNodeId);
        if (link == null || link.generation() != generation) {
            Retired old = retired.get(new LinkKey(sceneNodeId, generation));
            if (old != null && nanoClock.getAsLong() - old.deadlineNanos() >= 0) {
                old.link().fail("被新实例取代");
                return 0;
            }
            link = old == null ? null : old.link();
        }
        return link == null ? 0 : link.offer(frame, false);
    }

    @Override
    public boolean cancelQueuedEnter(int nodeId, long generation, PlayerEnter enter) {
        SceneLink link = links.get(nodeId);
        if (link == null || link.generation() != generation) return false;
        return link.cancelQueuedEnter(enter);
    }

    /** 连接与扫描共用最大代次围栏：迟到的旧目录写不能使新绑定回到旧进程。 */
    boolean acceptDirectoryIdentity(SceneNodeInfo info) {
        synchronized (directoryLock) {
            long maximum = maximumEpochs.getOrDefault(info.getNodeId(), 0L);
            if (Long.compareUnsigned(info.getLeaseEpoch(), maximum) < 0) return false;
            maximumEpochs.put(info.getNodeId(), info.getLeaseEpoch());
            return true;
        }
    }

    void observeLeaseEpoch(int node, long epoch) {
        synchronized (directoryLock) {
            maximumEpochs.merge(node, epoch, (a, b) -> Long.compareUnsigned(a, b) >= 0 ? a : b);
        }
    }

    /** resolve 线程上的一次成功目录读；不在 EventLoop 进行 Redis I/O。 */
    public void inspectDirectory(List<SceneNodeInfo> entries) {
        if (closed) return;
        Map<Integer, SceneNodeInfo> byNode = new HashMap<>();
        for (var entry : entries) byNode.merge(entry.getNodeId(), entry,
                (a, b) -> Long.compareUnsigned(a.getLeaseEpoch(), b.getLeaseEpoch()) >= 0 ? a : b);
        synchronized (directoryLock) {
            for (SceneLink link : List.copyOf(links.values())) {
                SceneLink.Peer peer = link.peer();
                if (peer == null) continue;
                var entry = byNode.get(link.nodeId());
                if (entry == null || entry.getInstanceId().isBlank() || !acceptDirectoryIdentity(entry)) {
                    legacyCandidates.remove(link.nodeId());
                    continue;
                }
                if (entry.getInstanceId().equals(peer.instanceId())) {
                    link.updateLeaseEpoch(entry.getLeaseEpoch());
                    legacyCandidates.remove(link.nodeId());
                    continue;
                }
                boolean newer;
                if (entry.getLeaseEpoch() == 0 || peer.leaseEpoch() == 0) {
                    newer = entry.getInstanceId().equals(legacyCandidates.put(link.nodeId(), entry.getInstanceId()));
                } else newer = Long.compareUnsigned(entry.getLeaseEpoch(), peer.leaseEpoch()) > 0;
                if (newer) {
                    LinkKey key = new LinkKey(link.nodeId(), link.generation());
                    Retired old = new Retired(link, nanoClock.getAsLong() + settings.supersededGrace().toNanos());
                    // 先发布旧代次寻址，再禁止新绑定。否则新绑定线程可能先摘当前表，造成旧绑定短暂无处可发。
                    retired.put(key, old);
                    if (link.markSuperseded()) {
                        links.remove(link.nodeId(), link);
                        legacyCandidates.remove(link.nodeId());
                        metrics.linkSuperseded();
                    } else retired.remove(key, old);
                }
            }
        }
        expireSuperseded();
    }

    /** 缺席/失败不判断死亡；只打断旧版本的连续两次换代证据，并照常收口已有宽限。 */
    public void directoryUnavailable() {
        synchronized (directoryLock) { legacyCandidates.clear(); }
        expireSuperseded();
    }

    public void expireSuperseded() {
        long now = nanoClock.getAsLong();
        for (Retired old : List.copyOf(retired.values())) if (now - old.deadlineNanos() >= 0) old.link().fail("被新实例取代");
    }

    /** 总允许新建链路、不记指标（测试用）。 */
    public SceneLinkManager(Supplier<LinkHello> hellos, LinkConnector connector, LinkSettings settings) {
        this(hellos, connector, settings, () -> true, GateMetrics.noop());
    }

    /** 绑定事件监听器；必须在第一次 {@link #send} 之前调用，且只能调一次。 */
    public void bindListener(SceneLinkListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener 不能为空");
        }
        if (this.listener != null) {
            throw new IllegalStateException("listener 已绑定");
        }
        this.listener = listener;
    }

    @Override
    public long send(int sceneNodeId, NodeLinkFrame frame) {
        if (listener == null) {
            throw new IllegalStateException("SceneLinkManager 尚未绑定 listener");
        }
        // 最多两次：第一次可能拿到刚判死、还没来得及摘除的旧链路。
        for (int attempt = 0; attempt < 2 && !closed; attempt++) {
            SceneLink link = links.get(sceneNodeId);
            if (link == null) {
                if (!newLinksAllowed.getAsBoolean()) {
                    log.warn("本 gate 节点号租约无效，不新建 scene 链路，丢弃一帧 node={} type={}", sceneNodeId, frame.getBodyCase());
                    metrics.linkDropped(LinkDrop.LEASE_INVALID, 1);
                    return 0;
                }
                link = links.computeIfAbsent(sceneNodeId, id -> new SceneLink(id, generations.incrementAndGet(), this));
            }
            long gen = link.offer(frame);
            if (gen != 0) {
                return gen;
            }
            links.remove(sceneNodeId, link);
        }
        log.warn("scene 链路层不可用，丢弃一帧 node={} type={}", sceneNodeId, frame.getBodyCase());
        metrics.linkDropped(LinkDrop.UNAVAILABLE, 1);
        return 0;
    }

    /** 当前链路数（建链中 + 就绪；指标用，弱一致）。 */
    public int linkCount() {
        return links.size() + retired.size();
    }

    /** 判死全部链路（进程退出时，在会话都关掉之后调用）。 */
    @Override
    public void close() {
        closed = true;
        var all = new HashSet<>(links.values());
        retired.values().forEach(old -> all.add(old.link()));
        for (SceneLink link : all) {
            link.fail("gate 关闭");
        }
    }

    void onLinkDead(SceneLink link) {
        links.remove(link.nodeId(), link);
        retired.remove(new LinkKey(link.nodeId(), link.generation()));
    }

    /** 为一次建链生成新的握手帧（时间戳取现在）。 */
    LinkHello newHello() {
        return hellos.get();
    }

    boolean newLinksAllowed() {
        return newLinksAllowed.getAsBoolean();
    }

    LinkConnector connector() {
        return connector;
    }

    LinkSettings settings() {
        return settings;
    }

    SceneLinkListener listener() {
        return listener;
    }

    GateMetrics metrics() {
        return metrics;
    }
}
