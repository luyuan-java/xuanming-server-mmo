package com.game.battle.edge;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.room.DirectLink;
import com.game.net.limit.MessageRateLimiter;
import com.game.proto.MessageContent;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.Message;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * 一条客户端直连的会话状态（基线 {@code BattleClientEdge::DirectSession}，{@code edge.h:76-91}；battle-node-spec §7.4），兼作房间一侧的
 * {@link DirectLink}：握手第 5 步经 {@code BattleRoomService.attachDirect} 交给房间，房间不接触 Netty {@code Channel}。
 *
 * <p>状态机 {@code PENDING → VERIFIED → CLOSING → CLOSED}（PENDING 也可直接进 CLOSING：握手被拒、握手超时、握手前发请求）：
 * <ul>
 *   <li>PENDING：只认握手包；</li>
 *   <li>VERIFIED：已绑定 battle_id / player_id / 角色，上行过 §3.5 的闸后分发给房间；</li>
 *   <li>CLOSING：服务端的关闭已生效（优雅或强关）；之后到达的任何帧一律忽略（基线 {@code codec.cpp:158-162}、{@code :180-185}），
 *       但当前正在处理的那条请求的应答照常写出（R3）；</li>
 *   <li>CLOSED：连接已断（{@code channelInactive}）；清掉 Channel 引用（AGENTS.md §3：长期持有的 Channel 引用在关闭后必须清掉），
 *       房间万一还留着这个对象，它的方法也都安全地什么都不做。</li>
 * </ul>
 *
 * <p><b>谁发起关闭决定何时进 CLOSING</b>：
 * <ul>
 *   <li>直连面自己关（握手被拒、握手前发请求、非法帧 / 非法包达阈值、写缓冲满、强关）：当场进 CLOSING。基线这些路径同步调
 *       {@code shutdown()} / {@code forceClose()}（{@code edge.cpp:267-274} 等），连接当场不再 connected，同一次读里后面的帧作废。</li>
 *   <li>房间发起的优雅关闭（{@link #closeGracefully}：终局、销毁 / 作废、165 退出观战、观众被清退）：当场只「脱离房间」——{@link #isLive()} /
 *       {@link #send} 立刻失效（房间也已摘槽），但会话仍收帧；当前这次读处理完（{@link EdgeInboundGuard}）或排在当前任务之后
 *       （{@link DirectClose}），先到者把它置为 CLOSING。基线 {@code ShutdownDirectConnAfterThisLoop}（{@code battle_room_manager.cpp:1602-1617}）
 *       只 {@code queueInLoop}，本轮读里连接仍是 connected，codec 照常分发同一批里后面的帧、写出它们的应答，然后才 FIN：
 *       例如观众同一次写里的「165 + 140」两条都有应答，打出最后一击的 149 后面跟着的 140 也有应答。</li>
 * </ul>
 *
 * <p><b>线程</b>：全部状态只在本连接的 EventLoop（= battle 逻辑线程，§7.3）上读写；{@link DirectLink} 的写方法断言调用线程。
 * {@code state} / {@code closeRequested} / {@code channel} 用 volatile 只为让停机排空（别的线程）能读到。
 *
 * <p><b>断开指标</b>：服务端主动断开的原因每条连接至多计一次（第一个原因生效；含房间经 {@link #closeGracefully} / {@link #closeNow} 传入的原因）。
 */
final class DirectSession implements DirectLink {

    enum State {
        PENDING,
        VERIFIED,
        CLOSING,
        CLOSED
    }

    private final BattleMetrics metrics;
    private final String peer;
    /** 每消息号限频，每条连接一份（基线 {@code DirectSession::messageLimiter}，与 gate 同一张表）。 */
    private final MessageRateLimiter rateLimiter = new MessageRateLimiter();

    private volatile Channel channel;
    private volatile State state = State.PENDING;
    /** 房间已发起优雅关闭、还没生效为 CLOSING（见类注释）：已脱离房间，但当前这次读里的帧照常分发。 */
    private volatile boolean closeRequested;
    private boolean verified;
    private long battleId;
    private long playerId;
    private eBattleTicketRole role = eBattleTicketRole.BATTLE_TICKET_ROLE_NONE;
    /** 大厅会话号（房间快照路由里的，只进日志）；-1 = 未知。 */
    private long gateSessionId = -1;
    private int illegalPackets;
    private ScheduledFuture<?> handshakeTimer;
    private Disconnect disconnectReason;

    DirectSession(Channel channel, BattleMetrics metrics) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.peer = describe(channel.remoteAddress());
    }

    // ---------------------------------------------------------------- DirectLink（房间一侧，逻辑线程）

    @Override
    public boolean isLive() {
        Channel ch = channel;
        return state == State.VERIFIED && !closeRequested && ch != null && ch.isActive();
    }

    @Override
    public void send(MessageContent frame) {
        Objects.requireNonNull(frame, "frame");
        Channel ch = channel;
        if (state != State.VERIFIED || closeRequested || ch == null || !ch.isActive()) {
            return;
        }
        requireOwnerThread(ch);
        ch.writeAndFlush(frame);
    }

    /**
     * 房间发起的优雅关闭：当场脱离房间（{@link #isLive()} / {@link #send} 失效、记断开原因），会话<b>暂不</b>进 CLOSING——当前这次读里
     * 后面的帧照常分发、应答照常写出；当前这次读处理完或排在当前任务之后才置 CLOSING，然后输出排空、FIN、1 s 强关兜底（见类注释，
     * 基线 {@code battle_room_manager.cpp:1602-1617}）。
     */
    @Override
    public void closeGracefully(Disconnect reason) {
        Channel ch = channel;
        if (ch == null || closeRequested || state == State.CLOSING || state == State.CLOSED) {
            return;
        }
        requireOwnerThread(ch);
        closeRequested = true;
        recordDisconnect(reason);
        DirectClose.afterCurrentTask(ch, GRACEFUL_FORCE_CLOSE_MS, this::applyRequestedClose);
    }

    @Override
    public void closeNow(Disconnect reason) {
        Channel ch = channel;
        if (ch == null || state == State.CLOSED) {
            return;
        }
        requireOwnerThread(ch);
        forceClose(reason);
    }

    @Override
    public String peer() {
        return peer;
    }

    // ---------------------------------------------------------------- 直连面内部（逻辑线程）

    State state() {
        return state;
    }

    /** 还接受上行帧（PENDING / VERIFIED；房间已发起、还没生效的优雅关闭也算，见类注释）。 */
    boolean acceptsFrames() {
        State s = state;
        return s == State.PENDING || s == State.VERIFIED;
    }

    /** 服务端已发起关闭（含房间发起、还没生效的优雅关闭）、连接还没断（停机排空等的就是这些）。任何线程可读。 */
    boolean isClosing() {
        State s = state;
        return s == State.CLOSING || (closeRequested && s != State.CLOSED);
    }

    /** 曾经握手成功（断开时据此决定是否向房间摘槽，同基线 {@code HandleClosed} 判 {@code session.verified}）。 */
    boolean wasVerified() {
        return verified;
    }

    long battleId() {
        return battleId;
    }

    long playerId() {
        return playerId;
    }

    eBattleTicketRole role() {
        return role;
    }

    long gateSessionId() {
        return gateSessionId;
    }

    int illegalPackets() {
        return illegalPackets;
    }

    MessageRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** 当前 Channel；连接断开之后为 null。 */
    Channel channelOrNull() {
        return channel;
    }

    void armHandshakeTimer(ScheduledFuture<?> timer) {
        this.handshakeTimer = timer;
    }

    /**
     * 握手成功（基线 {@code edge.cpp:343-349}）：置为已验证、绑定身份、非法包计数清零、取消握手定时器。
     * 在 {@code attachDirect} 成功<b>之后</b>调用：挂接期间 {@link #isLive()} 仍为 false，房间写不出任何帧（R1）。
     */
    void markVerified(long battleId, long playerId, eBattleTicketRole role, long gateSessionId) {
        this.battleId = battleId;
        this.playerId = playerId;
        this.role = role;
        this.gateSessionId = gateSessionId;
        this.illegalPackets = 0;
        this.verified = true;
        this.state = State.VERIFIED;
        cancelHandshakeTimer();
    }

    /** 非法包 +1，返回累计值。 */
    int registerIllegal() {
        return ++illegalPackets;
    }

    /**
     * 直连面自己的写出（握手应答、请求应答、信封错误）：不看 VERIFIED / CLOSING——当前请求的应答在房间已经优雅关闭这条连接之后也要写出（R2 / R3），
     * 握手应答在 PENDING 时写出。连接已断则丢弃。
     */
    void writeDirect(Message message) {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(message);
        }
    }

    /**
     * 进入关闭中：之后到达的帧一律忽略；取消握手定时器；记断开原因（第一个生效；{@code reason} 为 null 时不计）。
     */
    void beginClosing(Disconnect reason) {
        State s = state;
        if (s == State.PENDING || s == State.VERIFIED) {
            state = State.CLOSING;
        }
        cancelHandshakeTimer();
        recordDisconnect(reason);
    }

    /**
     * 房间发起的优雅关闭在此生效（置 CLOSING）；没有待生效的关闭则什么也不做。当前这次读处理完时（{@link EdgeInboundGuard}）与
     * {@link DirectClose} 排在当前任务之后的那一步各调一次，先到者生效，幂等。
     */
    void applyRequestedClose() {
        if (closeRequested) {
            beginClosing(null);
        }
    }

    /**
     * 直连面自己发起的优雅关闭（握手被拒，R4）：当场进 CLOSING（基线 {@code edge.cpp:267-274} 同步 {@code shutdown()}，同一批里后面的帧作废），
     * 输出排空后 FIN，{@code forceAfterMs} 后强关兜底（{@link DirectClose}）。
     */
    void gracefulClose(Disconnect reason, long forceAfterMs) {
        beginClosing(reason);
        Channel ch = channel;
        if (ch != null) {
            DirectClose.afterCurrentTask(ch, forceAfterMs, () -> {
            });
        }
    }

    /** 立即强关，不再发任何帧。 */
    void forceClose(Disconnect reason) {
        beginClosing(reason);
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    /** 连接已断（{@code channelInactive}）：置 CLOSED、取消定时器、清掉 Channel 引用。 */
    void onInactive() {
        state = State.CLOSED;
        cancelHandshakeTimer();
        channel = null;
    }

    /** 第一次记下的服务端主动断开原因（没有则 null；测试与日志用）。 */
    Disconnect disconnectReason() {
        return disconnectReason;
    }

    /** 记服务端主动断开的原因（每条连接只计第一个；null 不计）。 */
    private void recordDisconnect(Disconnect reason) {
        if (reason != null && disconnectReason == null) {
            disconnectReason = reason;
            metrics.disconnect(reason);
        }
    }

    private void cancelHandshakeTimer() {
        ScheduledFuture<?> timer = handshakeTimer;
        if (timer != null) {
            handshakeTimer = null;
            timer.cancel(false);
        }
    }

    private static void requireOwnerThread(Channel ch) {
        if (!ch.eventLoop().inEventLoop()) {
            throw new IllegalStateException("DirectLink 只许在 battle 逻辑线程上调用，当前线程 " + Thread.currentThread().getName());
        }
    }

    static String describe(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            String host = inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
            return host + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }
}
