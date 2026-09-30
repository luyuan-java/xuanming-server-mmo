package com.game.scene.link;

import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.ToClient;
import com.game.proto.MessageContent;
import com.game.scene.world.ClientSink;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private void write(long linkId, NodeLinkFrame frame) {
        Link link = byLinkId.get(linkId);
        if (link == null || !link.channel().isActive()) {
            // 链路已断：gate 侧的会话随链路一起失效，丢弃即可。
            return;
        }
        if (!link.channel().isWritable()) {
            // 出站缓冲越过高水位：gate 读不动。断链（其上玩家经 linkClosed 按断线写回），不丢单帧、不无限堆积。
            log.error("gate 链路出站缓冲超过高水位，断开链路 link={} gate_node={} 距恢复可写还差字节={}", linkId,
                    link.gateNodeId(), link.channel().bytesBeforeWritable());
            link.channel().close();
            return;
        }
        // Channel 的写是线程安全的：Netty 把它排到该连接的 I/O 线程上执行，同一链路上的帧保持提交顺序。
        link.channel().writeAndFlush(frame);
    }
}
