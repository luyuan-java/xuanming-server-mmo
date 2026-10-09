package com.game.scene.link;

import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.ToClient;
import com.game.api.proto.ZoneRedirect;
import com.game.proto.MessageContent;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.LinkDrop;
import com.game.scene.world.ClientSink;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 已握手的 gate 链路登记表，同时是场景逻辑的出站口（{@link ClientSink}）。只在场景逻辑线程上读写。
 *
 * <p>同一 gate 节点号只保留一条链路：新链路登记时顶替旧链路（旧链路由调用方清理玩家并关闭）——前提是新链路的
 * 租约防护代次（{@code LinkHello.lease_epoch}）<b>不低于</b>现有链路的；代次更低的是丢了节点号租约的旧 gate 进程，
 * 拒绝它，不让它顶掉新持有者的链路。
 * 链路注销后引用立即删除，迟到的下行按 linkId 找不到链路即丢弃，不会误发到新链路。
 *
 * <p>出站缓冲：链路 channel 越过写缓冲高水位（gate 长时间读不动）时直接关闭这条链路——其上的玩家按断线写回、
 * gate 关闭这些会话。宁可断链也不让出站缓冲无限增长，也不能丢帧（丢掉 21 / 51 会让客户端的场景状态永久错乱）。
 *
 * <p>跨节点换图的 {@code PlayerTransfer}（批次 5.2）与跨 zone 传送的 {@code PlayerTransfer{redirect}}（批次 5.4）另挂写结果监听：
 * 同步就知道写不出（链路已断 / 不可写）时返回 false；
 * 交给链路之后异步写失败时，把调用方的回调投递回场景逻辑线程（改绑指令：源节点据此释放新 epoch，scene-handoff-spec §5.5；
 * 重定向帧：源节点据此把位置改回源场景，zone-travel-spec §5.5）。
 */
public final class GateLinks implements ClientSink {

    private static final Logger log = LoggerFactory.getLogger(GateLinks.class);

    /** 一条已握手的链路。 */
    public record Link(long linkId, int gateNodeId, String gateInstanceId, long leaseEpoch, Channel channel) {
    }

    /**
     * 登记结果。
     *
     * @param accepted 是否登记成功
     * @param replaced 被顶替的同 gate 节点号旧链路（已从登记表删除，调用方负责清理并关闭）；没有则 null
     * @param current  被拒时，占着这个 gate 节点号的现有链路
     */
    public record Registration(boolean accepted, Link replaced, Link current) {
    }

    private final Map<Long, Link> byLinkId = new HashMap<>();
    private final Map<Integer, Link> byGateNode = new HashMap<>();
    private final SceneMetrics metrics;
    private final Executor logic;

    /**
     * @param metrics 出站帧与丢弃帧计数（{@code xm.scene.link.frames{direction=out}} / {@code xm.scene.link.dropped}）
     * @param logic   投递回场景逻辑线程（异步写失败的回调在链路 I/O 线程上触发，经它切回逻辑线程；已停止时抛拒绝异常）
     */
    public GateLinks(SceneMetrics metrics, Executor logic) {
        this.metrics = metrics;
        this.logic = logic;
    }

    /** 登记新链路。租约代次低于同 gate 节点号现有链路的一律拒绝。 */
    public Registration register(long linkId, int gateNodeId, String gateInstanceId, long leaseEpoch, Channel channel) {
        Link current = byGateNode.get(gateNodeId);
        if (current != null && Long.compareUnsigned(leaseEpoch, current.leaseEpoch()) < 0) {
            return new Registration(false, null, current);
        }
        Link link = new Link(linkId, gateNodeId, gateInstanceId, leaseEpoch, channel);
        Link replaced = byGateNode.put(gateNodeId, link);
        if (replaced != null) {
            byLinkId.remove(replaced.linkId());
        }
        byLinkId.put(linkId, link);
        return new Registration(true, replaced, null);
    }

    /** 注销链路；返回被注销的链路，已不在登记表（例如已被顶替）返回 null。 */
    public Link unregister(long linkId) {
        Link link = byLinkId.remove(linkId);
        if (link != null) {
            byGateNode.remove(link.gateNodeId(), link);
        }
        return link;
    }

    public boolean isRegistered(long linkId) {
        return byLinkId.containsKey(linkId);
    }

    /** 已登记的链路（回合制战斗快照的路由取它的 gate 节点号与实例 id，scene-battle-spec §7.11）；不在登记表为 null。逻辑线程上调用。 */
    public Link link(long linkId) {
        return byLinkId.get(linkId);
    }

    public int size() {
        return byLinkId.size();
    }

    /** 关闭并注销全部链路（停服）。返回被关闭的链路。 */
    public List<Link> closeAll() {
        List<Link> all = new ArrayList<>(byLinkId.values());
        byLinkId.clear();
        byGateNode.clear();
        for (Link link : all) {
            link.channel().close();
        }
        return all;
    }

    @Override
    public void send(long linkId, List<Integer> sessionIds, MessageContent content) {
        write(linkId, NodeLinkFrame.newBuilder()
                .setToClient(ToClient.newBuilder()
                        .addAllSessionIds(sessionIds)
                        .setMessageContent(content.toByteString()))
                .build());
    }

    @Override
    public void enterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        write(linkId, NodeLinkFrame.newBuilder()
                .setPlayerEnterResult(PlayerEnterResult.newBuilder()
                        .setSessionId(sessionId)
                        .setPlayerId(playerId)
                        .setTipId(tipId)
                        .setOwnerEpoch(ownerEpoch))
                .build());
    }

    @Override
    public void playerKicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        write(linkId, NodeLinkFrame.newBuilder()
                .setPlayerKicked(PlayerKicked.newBuilder()
                        .setSessionId(sessionId)
                        .setPlayerId(playerId)
                        .setOwnerEpoch(ownerEpoch)
                        .setTipId(tipId))
                .build());
    }

    @Override
    public boolean playerTransfer(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                  int targetNodeId, long targetSceneId, Runnable onWriteFailed) {
        return writeTransfer(linkId, PlayerTransfer.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setFromEpoch(fromEpoch)
                .setToEpoch(toEpoch)
                .setTargetSceneNodeId(targetNodeId)
                .setTargetSceneId(targetSceneId)
                .build(), onWriteFailed);
    }

    /**
     * 跨 zone 传送的重定向帧（批次 5.4）：{@code PlayerTransfer{redirect}}，目标节点与场景都不填（0）。{@code redirect} 整个子消息原样放进帧
     * ——其中的 {@code token_payload} 是 {@code bytes} 字段，这里不解析、不重组，写出去的就是选目标时签名的那一份字节。
     * 写帧与两条失败口同 {@link #playerTransfer}。
     */
    @Override
    public boolean playerRedirect(long linkId, int sessionId, long playerId, long fromEpoch, long toEpoch,
                                  ZoneRedirect redirect, Runnable onWriteFailed) {
        return writeTransfer(linkId, redirectFrame(sessionId, playerId, fromEpoch, toEpoch, redirect), onWriteFailed);
    }

    /** 重定向帧的帧体（独立出来供字节金样测试对着同一个函数断言：scene 写出的字节就是 gate 解析的字节）。 */
    static PlayerTransfer redirectFrame(int sessionId, long playerId, long fromEpoch, long toEpoch, ZoneRedirect redirect) {
        return PlayerTransfer.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setFromEpoch(fromEpoch)
                .setToEpoch(toEpoch)
                .setRedirect(Objects.requireNonNull(redirect, "redirect"))
                .build();
    }

    /**
     * 写一帧 {@code PlayerTransfer}（改绑指令或重定向帧）并挂写结果监听。同步就知道写不出（链路已断 / 不可写）返回 false、
     * {@code onWriteFailed} 不会被调用；交给链路之后异步写失败，把 {@code onWriteFailed} 投递回场景逻辑线程。
     */
    private boolean writeTransfer(long linkId, PlayerTransfer transfer, Runnable onWriteFailed) {
        ChannelFuture written = write(linkId, NodeLinkFrame.newBuilder().setPlayerTransfer(transfer).build());
        if (written == null) {
            return false;
        }
        int sessionId = transfer.getSessionId();
        long playerId = transfer.getPlayerId();
        long fromEpoch = transfer.getFromEpoch();
        long toEpoch = transfer.getToEpoch();
        boolean redirect = transfer.hasRedirect();
        written.addListener(future -> {
            if (future.isSuccess()) {
                return;
            }
            // 链路 I/O 线程上：帧没写出去（链路在冲刷前关闭）。gate 没收到这一帧，源节点要自己善后
            // （改绑指令：释放新 epoch；重定向帧：位置改回源场景）——切回逻辑线程做。
            metrics.linkFrameDropped(LinkDrop.WRITE_FAILED);
            log.warn("PlayerTransfer{} 交给链路后写失败 link={} session={} player={} epoch {}→{}: {}",
                    redirect ? "{redirect}" : "", linkId, sessionId, Long.toUnsignedString(playerId), fromEpoch, toEpoch,
                    String.valueOf(future.cause()));
            try {
                logic.execute(onWriteFailed);
            } catch (RejectedExecutionException e) {
                // 逻辑线程已停（停服）：改绑指令的新 epoch 等租约过期（scene-handoff-spec §8.2 R-J1）；重定向帧的新 epoch 本来就已释放
                log.warn("逻辑线程已停止，PlayerTransfer 写失败的善后丢弃 player={} epoch={}", Long.toUnsignedString(playerId),
                        toEpoch);
            }
        });
        return true;
    }

    /** 写一帧；链路已断 / 不可写时返回 null（帧确定没写出），否则返回这次写的 future。 */
    private ChannelFuture write(long linkId, NodeLinkFrame frame) {
        Link link = byLinkId.get(linkId);
        if (link == null || !link.channel().isActive()) {
            // 链路已断：gate 侧的会话随链路一起失效，丢弃即可。
            metrics.linkFrameDropped(LinkDrop.LINK_GONE);
            return null;
        }
        if (!link.channel().isWritable()) {
            // 出站缓冲越过高水位：gate 读不动。断链（其上玩家经 linkClosed 按断线写回），不丢单帧、不无限堆积。
            metrics.linkFrameDropped(LinkDrop.WRITE_BUFFER_FULL);
            log.error("gate 链路出站缓冲超过高水位，断开链路 link={} gate_node={} 距恢复可写还差字节={}", linkId,
                    link.gateNodeId(), link.channel().bytesBeforeWritable());
            link.channel().close();
            return null;
        }
        // Channel 的写是线程安全的：Netty 把它排到该连接的 I/O 线程上执行，同一链路上的帧保持提交顺序。
        ChannelFuture future = link.channel().writeAndFlush(frame);
        metrics.linkFrameOut(frame.getBodyCase());
        return future;
    }
}
