package com.game.gate.session;

import com.game.api.proto.SessionContext;
import com.game.net.limit.MessageRateLimiter;
import com.game.proto.ClientRequest;
import com.google.protobuf.Message;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条客户端连接的会话。
 *
 * <p>线程所有权：除 {@link #sessionId()} / {@link #clientIp()} 外，全部状态<b>只在该连接的 EventLoop 上读写</b>
 * （AGENTS.md §3）。别的线程（Dubbo 回调、节点链路）要动会话，一律经 {@link #execute} 投递回来。
 *
 * <p>连接的所有权在 Netty；关闭后本对象只在会话表里留到断线流程结束（{@link ClientDispatcher} 负责释放）。
 */
public final class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    private final int sessionId;
    private final Channel channel;
    private final String clientIp;

    // ---------------------------------------------------------------- 以下只在 channel.eventLoop() 上读写

    /** 已通过 gate 令牌校验。 */
    boolean verified;
    /** 已决定关闭（握手被拒、CloseSession 指令、超限）：不再处理任何上行。 */
    boolean closing;
    /** 连接已断开，断线流程已开始。 */
    boolean closed;
    /** 已认证账号（BindAccount 指令）；未登录为空串。 */
    String account = "";
    /**
     * 已绑定玩家（EnterScene 指令）；未进游戏、已离开游戏（UnbindPlayer 指令）或进场失败为 0——
     * 它随 {@code SessionContext.player_id} 发给 login，login 据此拒绝已在游戏里的会话再次进游戏 / 建角。
     */
    long playerId;
    /**
     * 所在 scene 节点；0 = 不在场景里。以下四个「场景绑定」字段与 {@link #transferEntering} 同生同灭（{@code unbindScene} 一起清）。
     * 绑定的建立有两个入口：login 的 EnterScene 指令（进游戏），以及源 scene 的 PlayerTransfer（跨节点换图改绑）。
     */
    int sceneNodeId;
    /** 进场帧所在的节点链路代次；与 {@link #sceneNodeId} 一起判定下行 / 链路事件是否属于本会话的当前场景。 */
    long sceneLinkGen;
    /**
     * 送进场景的那个玩家（进场帧里的 player_id）。离场帧用它而不是 {@link #playerId}：会话关闭途中迟到的 UnbindPlayer
     * 会把 {@link #playerId} 清零，而场景里的玩家仍要按这个 id 放掉。
     */
    long scenePlayerId;
    /** 进场帧里的 owner_epoch：只认同一次进场的结果 / 踢出通知，丢弃更早一次进场的迟到帧。 */
    long sceneOwnerEpoch;
    /**
     * 当前绑定是跨节点换图的交出进场、目标节点还没回结果（{@code PlayerEnter.transfer = true} 已发出）。
     * 这期间进场失败（目标节点拒绝、建链失败）不回大厅，推 23 {tip} 后断开：源节点已交出、实例已移除，会话上没有可回的场景。
     * 由进场结果、未送达回报、链路断开、断线四条路径之一终结（后三条经 {@code unbindScene}）。
     */
    boolean transferEntering;
    /**
     * scene 已确认进场，{@link #scenePlayerId} 已登记进玩家在线目录（{@link PresenceRecorder#online}）；
     * 场景绑定结束或会话关闭时撤销。服务端推送（{@code GatePush}）只发给这种状态下、玩家对得上的会话。
     */
    boolean presenceOnline;
    /** 向 login 发过调用：断线时要通知 login 清理。 */
    boolean loginTouched;
    /** 待处理的上行请求（按到达顺序）；login 调用在途时后续请求在此排队，保证同一会话严格串行。 */
    final ArrayDeque<PendingRequest> pending = new ArrayDeque<>();
    /** 有一个 login 调用在途。 */
    boolean inFlight;
    /**
     * login 以外的后端（friend ……）各自的上行队列与在途标记（键 = 消息域）：同一后端的请求串行（写后读），
     * 但不占 {@link #inFlight}、不阻塞 login / scene——一个慢的好友调用不能让移动等 scene 消息排满上限而断线。
     */
    final Map<String, ArrayDeque<BackendPending>> backendQueues = new HashMap<>();
    final Set<String> backendInFlight = new HashSet<>();
    /**
     * 有一个 LeaveGame（17）排队或在途、还没回来：这时断线按主动离开通知 scene（客户端约定「发完 17 即关连接、不等应答」，
     * 不能把这次干净登出当成断线、留 30 s 重连租约）。LeaveGame 的调用回来后减掉（关闭途中成功的不减，留给断线流程按主动离开发）。
     */
    int leaveGameRequests;
    /** 非法包计数（未知消息号、超长、超频、运行模式不放行的 GM 指令），达到阈值断开。 */
    int illegalPackets;
    /** 按消息号的发送频率限制（C++ MessageLimiter 同义；与 battle 直连面共用 xm-net 的实现）。 */
    final MessageRateLimiter rateLimiter = new MessageRateLimiter();
    private ScheduledFuture<?> handshakeTimeout;

    ClientSession(int sessionId, Channel channel, String clientIp) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.clientIp = clientIp;
    }

    /** uint32 会话号（按位存进 int；日志里用 {@link Integer#toUnsignedString}）。 */
    public int sessionId() {
        return sessionId;
    }

    public String clientIp() {
        return clientIp;
    }

    Channel channel() {
        return channel;
    }

    /**
     * 把任务投递到会话所属线程（别的线程要动会话一律经它）。EventLoop 已关闭（进程退出中）时丢弃并返回 false，
     * 需要善后的调用方（例如必须放弃归属的链路事件）据此自己收尾。
     */
    public boolean execute(Runnable task) {
        try {
            channel.eventLoop().execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("会话线程已关闭，丢弃任务 session={}", Integer.toUnsignedString(sessionId));
            return false;
        }
    }

    /** 连接已关时写入静默失败（断线流程由 channelInactive 驱动，不靠写失败）。 */
    void send(Message message) {
        channel.writeAndFlush(message);
    }

    /** 发完这条再关（给客户端一个收到原因的窗口）。 */
    void sendThenClose(Message message) {
        channel.writeAndFlush(message).addListener(ChannelFutureListener.CLOSE);
    }

    void close() {
        channel.close();
    }

    void scheduleHandshakeTimeout(Duration timeout, Runnable onTimeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            return;
        }
        handshakeTimeout = channel.eventLoop().schedule(onTimeout, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void cancelHandshakeTimeout() {
        if (handshakeTimeout != null) {
            handshakeTimeout.cancel(false);
            handshakeTimeout = null;
        }
    }

    boolean boundTo(int nodeId, long linkGen) {
        return sceneNodeId != 0 && sceneNodeId == nodeId && sceneLinkGen == linkGen;
    }

    /** 一条已通过入口校验、等待按序处理的上行请求。 */
    record PendingRequest(MessageRoute route, ClientRequest request) {
    }

    /**
     * 排在后端队列里的请求，带着<b>入队时</b>的会话身份快照：排队期间会话可能离开游戏、换角色进游戏（login 的调用不排在好友后面），
     * 按发出时的身份转发会把它当成别的角色的请求。
     */
    record BackendPending(MessageRoute route, ClientRequest request, SessionContext session) {
    }
}
