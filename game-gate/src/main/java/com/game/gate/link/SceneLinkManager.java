package com.game.gate.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.LinkDrop;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按需建立、复用到各 scene 节点的链路：每个节点同一时刻至多一条活链路，断了再有帧要发时建新代次。
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
        this.hellos = hellos;
        this.connector = connector;
        this.settings = settings;
        this.newLinksAllowed = newLinksAllowed;
        this.metrics = metrics;
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
        return links.size();
    }

    /** 判死全部链路（进程退出时，在会话都关掉之后调用）。 */
    @Override
    public void close() {
        closed = true;
        for (SceneLink link : List.copyOf(links.values())) {
            link.fail("gate 关闭");
        }
    }

    void onLinkDead(SceneLink link) {
        links.remove(link.nodeId(), link);
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
