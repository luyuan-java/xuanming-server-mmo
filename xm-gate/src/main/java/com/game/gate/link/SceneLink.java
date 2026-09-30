package com.game.gate.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.gate.metrics.GateMetrics.LinkDrop;
import com.game.gate.metrics.GateMetrics.LinkEvent;
import io.netty.channel.Channel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 到一个 scene 节点的一条链路（一个代次）。状态机：
 *
 * <pre>
 * NEW ──首帧──▶ CONNECTING ──LinkHelloAck(accepted)──▶ READY
 *                  │                                     │
 *                  └──寻址/连接/握手失败──▶ DEAD ◀──断开──┘
 * </pre>
 *
 * <ul>
 *   <li>CONNECTING 时帧排队；就绪后先发队列、再接新帧，顺序不变。握手帧 {@code LinkHello} 总是连接上的第一帧。</li>
 *   <li>DEAD 是终态：先从 {@link SceneLinkManager} 摘除（之后的发送会建新代次），再回报事件。</li>
 * </ul>
 *
 * 线程模型：发送来自任意会话线程，状态与队列由对象锁保护；锁内只做入队 / 非阻塞写，回调监听器一律在锁外。
 */
public final class SceneLink {

    private static final Logger log = LoggerFactory.getLogger(SceneLink.class);

    enum State {
        NEW,
        CONNECTING,
        READY,
        DEAD
    }

    private final int nodeId;
    private final long generation;
    private final SceneLinkManager manager;

    private State state = State.NEW;
    private final ArrayDeque<NodeLinkFrame> queue = new ArrayDeque<>();
    private Channel channel;
    /** 本连接发出的握手帧（每次建链现生成：鉴权时间戳取建链时刻）；ack 的 zone 与它比对。 */
    private LinkHello sentHello;

    SceneLink(int nodeId, long generation, SceneLinkManager manager) {
        this.nodeId = nodeId;
        this.generation = generation;
        this.manager = manager;
    }

    public int nodeId() {
        return nodeId;
    }

    public long generation() {
        return generation;
    }

    synchronized State state() {
        return state;
    }

    /**
     * 受理一帧；链路已死返回 0（调用方换新代次重试）。
     * 就绪链路的出站缓冲越过高水位（scene 长时间读不动）时判死这条链路（其上会话由 onLinkDown 关闭、scene 侧按断线写回），
     * 返回 0：宁可断链，也不让出站缓冲无限增长。
     */
    long offer(NodeLinkFrame frame) {
        boolean startConnect = false;
        boolean overWaterMark = false;
        PlayerEnter overflowed = null;
        synchronized (this) {
            if (state == State.DEAD) {
                return 0;
            }
            if (state == State.READY) {
                if (channel.isWritable()) {
                    channel.writeAndFlush(frame);
                    manager.metrics().linkFrameOut(frame.getBodyCase());
                    return generation;
                }
                overWaterMark = true;
            } else {
                if (state == State.NEW) {
                    state = State.CONNECTING;
                    startConnect = true;
                }
                if (queue.size() < manager.settings().maxQueuedFrames()) {
                    queue.add(frame);
                } else {
                    log.warn("scene 链路未就绪且排队已满，丢弃一帧 node={} gen={} type={}", nodeId, generation, frame.getBodyCase());
                    manager.metrics().linkDropped(LinkDrop.QUEUE_FULL, 1);
                    overflowed = frame.hasPlayerEnter() ? frame.getPlayerEnter() : null;
                }
            }
        }
        if (overWaterMark) {
            // 回调监听器一律在锁外：fail 自己加锁、摘除并回报断链。
            fail("出站缓冲超过高水位（scene 读不动）");
            return 0;
        }
        if (startConnect) {
            log.info("开始建立 scene 链路 node={} gen={}", nodeId, generation);
            manager.metrics().linkEvent(LinkEvent.CONNECTING);
            manager.connector().connect(this);
        }
        if (overflowed != null) {
            manager.listener().onEnterUndeliverable(nodeId, generation, overflowed);
        }
        return generation;
    }

    /**
     * TCP 已连上：发握手，并设握手超时。由 {@link SceneLinkHandler#channelActive} 调用。
     * 本 gate 的节点号租约此刻已无效（丢失或续期滞后）时不握手、直接判建链失败：
     * 不能用一个可能已归别人的节点号去顶掉 scene 上新持有者的链路。
     */
    void onConnected(Channel ch) {
        if (!manager.newLinksAllowed()) {
            ch.close();
            fail("本 gate 节点号租约无效，放弃建链");
            return;
        }
        LinkHello hello = manager.newHello();
        synchronized (this) {
            if (state != State.CONNECTING) {
                ch.close();
                return;
            }
            channel = ch;
            sentHello = hello;
        }
        ch.writeAndFlush(NodeLinkFrame.newBuilder().setHello(hello).build());
        manager.metrics().linkFrameOut(NodeLinkFrame.BodyCase.HELLO);
        long timeoutMs = manager.settings().helloTimeout().toMillis();
        if (timeoutMs > 0) {
            ch.eventLoop().schedule(() -> {
                if (state() == State.CONNECTING) {
                    fail("等待 LinkHelloAck 超时");
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 链路上收到一帧（链路 I/O 线程）。 */
    void onFrame(NodeLinkFrame frame) {
        manager.metrics().linkFrameIn(frame.getBodyCase());
        switch (frame.getBodyCase()) {
            case HELLO_ACK -> onHelloAck(frame.getHelloAck());
            case TO_CLIENT -> {
                if (state() == State.READY) {
                    manager.listener().onToClient(nodeId, generation, frame.getToClient());
                }
            }
            case PLAYER_ENTER_RESULT -> {
                if (state() == State.READY) {
                    manager.listener().onPlayerEnterResult(nodeId, generation, frame.getPlayerEnterResult());
                }
            }
            case PLAYER_KICKED -> {
                if (state() == State.READY) {
                    manager.listener().onPlayerKicked(nodeId, generation, frame.getPlayerKicked());
                }
            }
            default -> log.warn("scene 链路收到不该由 scene 发出的帧，忽略 node={} type={}", nodeId, frame.getBodyCase());
        }
    }

    private void onHelloAck(LinkHelloAck ack) {
        LinkHello hello;
        synchronized (this) {
            hello = sentHello;
        }
        String reject = null;
        if (hello == null) {
            reject = "握手帧尚未发出就收到 ack";
        } else if (!ack.getAccepted()) {
            reject = "scene 拒绝握手: " + ack.getReason();
        } else if (ack.getSceneNodeId() != nodeId) {
            reject = "对端节点号不符: " + ack.getSceneNodeId();
        } else if (ack.getZoneId() != hello.getZoneId()) {
            reject = "对端 zone 不符: " + ack.getZoneId();
        }
        if (reject != null) {
            fail(reject);
            return;
        }
        int flushed;
        synchronized (this) {
            if (state != State.CONNECTING) {
                return;
            }
            state = State.READY;
            flushed = queue.size();
            for (NodeLinkFrame queued : queue) {
                channel.write(queued);
                manager.metrics().linkFrameOut(queued.getBodyCase());
            }
            queue.clear();
            channel.flush();
        }
        manager.metrics().linkEvent(LinkEvent.READY);
        log.info("scene 链路就绪 node={} gen={} instance={} 补发={}", nodeId, generation, ack.getSceneInstanceId(), flushed);
    }

    /** 链路判死（幂等）：摘除 → 关连接 → 回报排队中的进场帧 → 若曾就绪则回报断链。 */
    void fail(String reason) {
        List<PlayerEnter> undeliverable = new ArrayList<>();
        boolean wasReady;
        int discarded;
        Channel ch;
        synchronized (this) {
            if (state == State.DEAD) {
                return;
            }
            wasReady = state == State.READY;
            state = State.DEAD;
            for (NodeLinkFrame queued : queue) {
                if (queued.hasPlayerEnter()) {
                    undeliverable.add(queued.getPlayerEnter());
                }
            }
            discarded = queue.size();
            queue.clear();
            ch = channel;
            channel = null;
        }
        manager.metrics().linkEvent(wasReady ? LinkEvent.DOWN : LinkEvent.CONNECT_FAILED);
        manager.metrics().linkDropped(LinkDrop.LINK_FAILED, discarded);
        manager.onLinkDead(this);
        if (ch != null) {
            ch.close();
        }
        if (wasReady) {
            log.warn("scene 链路断开 node={} gen={} 原因={}", nodeId, generation, reason);
        } else {
            log.warn("scene 链路建立失败 node={} gen={} 原因={} 进场失败={}", nodeId, generation, reason, undeliverable.size());
        }
        SceneLinkListener listener = manager.listener();
        for (PlayerEnter enter : undeliverable) {
            listener.onEnterUndeliverable(nodeId, generation, enter);
        }
        if (wasReady) {
            listener.onLinkDown(nodeId, generation);
        }
    }
}
