package com.game.gate.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.SceneNodeInfo;
import com.game.gate.metrics.GateMetrics.LinkDrop;
import com.game.gate.metrics.GateMetrics.LinkEvent;
import io.netty.channel.Channel;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
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
    /** 曾经握手成功（到过 READY）：判死之后仍为 true。 */
    private boolean handshaken;
    private final ArrayDeque<NodeLinkFrame> queue = new ArrayDeque<>();
    private Channel channel;
    /** 本连接发出的握手帧（每次建链现生成：鉴权时间戳取建链时刻）；ack 的 zone 与它比对。 */
    private LinkHello sentHello;
    private SceneNodeInfo resolved;
    private String peerInstance;
    private long peerLeaseEpoch;
    private boolean superseded;
    private ScheduledFuture<?> heartbeat;
    private long pingSequence;

    record Peer(String instanceId, long leaseEpoch) { }
    synchronized Peer peer() { return state == State.READY ? new Peer(peerInstance, peerLeaseEpoch) : null; }
    synchronized void updateLeaseEpoch(long epoch) {
        if (Long.compareUnsigned(epoch, peerLeaseEpoch) > 0) peerLeaseEpoch = epoch;
    }
    synchronized boolean markSuperseded() {
        if (state != State.READY || superseded) return false;
        superseded = true;
        return true;
    }

    synchronized boolean cancelQueuedEnter(PlayerEnter enter) {
        NodeLinkFrame frame = NodeLinkFrame.newBuilder().setPlayerEnter(enter).build();
        if (!queue.remove(frame)) return false;
        // 进场被取消后，其后尚未发出的业务帧也不能泄漏到一个从未承接该玩家的 scene。
        queue.removeIf(queued -> queued.hasClientForward()
                && queued.getClientForward().getSessionId() == enter.getSessionId()
                && queued.getClientForward().getPlayerId() == enter.getPlayerId()
                || queued.hasPlayerLeave() && queued.getPlayerLeave().getSessionId() == enter.getSessionId()
                && queued.getPlayerLeave().getPlayerId() == enter.getPlayerId());
        return true;
    }

    /** 目录 I/O 已结束，在真正建 TCP 之前固定这次连接的目标身份。 */
    boolean resolved(SceneNodeInfo info) {
        if (info.getNodeId() != nodeId || info.getInstanceId().isBlank()) {
            fail("目录目标身份无效");
            return false;
        }
        if (!manager.acceptDirectoryIdentity(info)) {
            fail("目录目标身份过期", EnterFailure.CONNECT_UNAVAILABLE);
            return false;
        }
        synchronized (this) {
            if (state != State.CONNECTING) return false;
            resolved = info;
            return true;
        }
    }

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

    private synchronized boolean handshaken() {
        return handshaken;
    }

    /**
     * 受理一帧；链路已死返回 0（调用方换新代次重试）。
     * 就绪链路的出站缓冲越过高水位（scene 长时间读不动）时判死这条链路（其上会话由 onLinkDown 关闭、scene 侧按断线写回），
     * 返回 0：宁可断链，也不让出站缓冲无限增长。
     */
    long offer(NodeLinkFrame frame) {
        return offer(frame, true);
    }

    long offer(NodeLinkFrame frame, boolean newBinding) {
        boolean startConnect = false;
        boolean overWaterMark = false;
        boolean written = false;
        PlayerEnter overflowed = null;
        try { synchronized (this) {
            if (state == State.DEAD || newBinding && superseded) {
                return 0;
            }
            if (state == State.READY) {
                if (channel.isWritable()) {
                    written = true; // 调用 write 之前即判为未知/已发；异常也绝不转为 undeliverable。
                    channel.writeAndFlush(frame);
                    manager.metrics().linkFrameOut(frame.getBodyCase());
                } else overWaterMark = true;
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
        } } catch (RuntimeException failure) {
            fail("链路写调用失败（结果未知）");
        } finally {
            if (written && frame.hasPlayerEnter()) manager.listener().onEnterDispatched(nodeId, generation, frame.getPlayerEnter());
        }
        if (written) return generation;
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
                    fail("等待 LinkHelloAck 超时", EnterFailure.CONNECT_UNAVAILABLE);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 链路上收到一帧（链路 I/O 线程）。
     *
     * <p>scene 发来的业务下行（ToClient / PlayerEnterResult / PlayerKicked / PlayerTransfer）用<b>同一条</b>规则：只要这条链路握手成功过
     * （对端身份核对过）就按到达顺序交给监听器，判死（DEAD）之后、连接真正关上之前读到的也照样交——判死可能来自别的线程
     * （会话线程发现出站高水位），I/O 线程上这一批已读到的帧仍在逐个处理。不能只放行改绑指令而丢掉它前面的下行：
     * 会话按改绑去了目标节点、之后的断链事件因已不绑定这条链路而被忽略，源节点在 PlayerTransfer 之前发的应答 / 推送就永远丢了
     * （违背「PlayerTransfer 之前的 ToClient 照常下发」）。过期帧由会话线程按绑定（节点 + 代次）、玩家与 epoch 过滤；
     * 仍绑定在这个死代次上的会话随后被断链事件关闭。从未握手成功的连接上来的帧不可信，忽略（这种连接上也不可能有已进场的会话）。
     */
    void onFrame(NodeLinkFrame frame) {
        manager.metrics().linkFrameIn(frame.getBodyCase());
        switch (frame.getBodyCase()) {
            case HELLO_ACK -> onHelloAck(frame.getHelloAck());
            case PONG -> { /* IdleStateHandler 已按任何入站帧重置计时，无需业务回调。 */ }
            case TO_CLIENT -> {
                if (handshaken()) {
                    manager.listener().onToClient(nodeId, generation, frame.getToClient());
                }
            }
            case PLAYER_ENTER_RESULT -> {
                if (handshaken()) {
                    manager.listener().onPlayerEnterResult(nodeId, generation, frame.getPlayerEnterResult());
                }
            }
            case PLAYER_KICKED -> {
                if (handshaken()) {
                    manager.listener().onPlayerKicked(nodeId, generation, frame.getPlayerKicked());
                }
            }
            case PLAYER_TRANSFER -> {
                // 改绑指令尤其不能丢：源节点已交出归属，丢了它新 epoch 就没人放弃、只能等租约过期。由会话线程按绑定裁决改绑或放弃。
                if (handshaken()) {
                    manager.listener().onPlayerTransfer(nodeId, generation, frame.getPlayerTransfer());
                } else {
                    log.warn("未握手成功的 scene 链路上收到改绑指令，忽略 node={} gen={} session={} player_id={}", nodeId,
                            generation, Integer.toUnsignedString(frame.getPlayerTransfer().getSessionId()),
                            frame.getPlayerTransfer().getPlayerId());
                }
            }
            default -> log.warn("scene 链路收到不该由 scene 发出的帧，忽略 node={} type={}", nodeId, frame.getBodyCase());
        }
    }

    private void onHelloAck(LinkHelloAck ack) {
        LinkHello hello;
        SceneNodeInfo directory;
        synchronized (this) {
            hello = sentHello;
            directory = resolved;
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
        } else if (directory != null && (!ack.getSceneInstanceId().equals(directory.getInstanceId())
                || directory.getLeaseEpoch() != 0 && ack.getSceneLeaseEpoch() != 0
                && Long.compareUnsigned(ack.getSceneLeaseEpoch(), directory.getLeaseEpoch()) < 0)) {
            reject = "对端实例与目录不符";
        } else if (ack.getPingIntervalMs() != 0 && (ack.getPingIntervalMs() != hello.getPingIntervalMs()
                || ack.getPingIntervalMs() < 1000 || ack.getPingIntervalMs() > 30_000)) {
            reject = "心跳协商值不符";
        }
        if (reject != null) {
            fail(reject);
            return;
        }
        int flushed;
        List<PlayerEnter> dispatched = new ArrayList<>();
        try { synchronized (this) {
            if (state != State.CONNECTING) {
                return;
            }
            state = State.READY;
            handshaken = true;
            peerInstance = ack.getSceneInstanceId();
            peerLeaseEpoch = ack.getSceneLeaseEpoch();
            if (ack.getPingIntervalMs() != 0) startHeartbeat(channel, ack.getPingIntervalMs());
            flushed = queue.size();
            NodeLinkFrame queued;
            while ((queued = queue.poll()) != null) {
                // 与 cancelQueuedEnter 共用锁；先从待发队列摘除，再调用 write，异常不可恢复其重试资格。
                if (queued.hasPlayerEnter()) dispatched.add(queued.getPlayerEnter());
                channel.write(queued);
                manager.metrics().linkFrameOut(queued.getBodyCase());
            }
            channel.flush();
        } } finally {
            for (PlayerEnter enter : dispatched) manager.listener().onEnterDispatched(nodeId, generation, enter);
        }
        manager.metrics().linkEvent(LinkEvent.READY);
        manager.observeLeaseEpoch(nodeId, ack.getSceneLeaseEpoch());
        log.info("scene 链路就绪 node={} gen={} instance={} 补发={}", nodeId, generation, ack.getSceneInstanceId(), flushed);
    }

    private void startHeartbeat(Channel ch, int interval) {
        ch.pipeline().addBefore(ch.pipeline().context(SceneLinkHandler.class).name(), "sceneLinkIdle",
                new IdleStateHandler(manager.settings().idleTimeout().toMillis(), 0, 0, TimeUnit.MILLISECONDS));
        heartbeat = ch.eventLoop().scheduleAtFixedRate(() -> {
            synchronized (this) {
                if (state != State.READY || channel != ch) return;
            }
            offer(NodeLinkFrame.newBuilder().setPing(com.game.api.proto.LinkPing.newBuilder().setSeq(++pingSequence)).build(), false);
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    void readIdle() {
        synchronized (this) { if (state != State.READY || heartbeat == null) return; }
        manager.metrics().linkIdleTimeout();
        fail("心跳超时");
    }

    /** 链路判死（幂等）：摘除 → 关连接 → 回报排队中的进场帧 → 若曾就绪则回报断链。 */
    void fail(String reason) {
        fail(reason, EnterFailure.PERMANENT);
    }

    void fail(String reason, EnterFailure failure) {
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
            if (heartbeat != null) { heartbeat.cancel(false); heartbeat = null; }
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
            listener.onEnterUndeliverable(nodeId, generation, enter, failure);
        }
        if (wasReady) {
            listener.onLinkDown(nodeId, generation, superseded);
        }
    }
}
