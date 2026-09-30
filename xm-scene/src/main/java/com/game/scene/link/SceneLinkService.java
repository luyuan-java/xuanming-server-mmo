package com.game.scene.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.SceneWorld;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 链路事件 → 场景逻辑的分发（{@link LinkInbound} 的实现）。只在场景逻辑线程上运行。
 *
 * <p>链路的生命周期规则：一条链路上的玩家只活到链路断开为止。同一 gate 节点号的新链路顶替旧链路时，
 * 旧链路上的玩家按断线处理（视野内他人收到 51、写回），gate 若要保留这些会话需在新链路上重新发 PlayerEnter。
 * 新链路的租约防护代次低于现有链路时拒绝它（回 {@code LinkHelloAck{accepted=false}} 并断开）：
 * 那是丢了节点号租约的旧 gate 进程，不能让它顶掉新持有者的链路、踢掉新持有者的玩家。
 */
public final class SceneLinkService implements LinkInbound {

    private static final Logger log = LoggerFactory.getLogger(SceneLinkService.class);

    private final LinkIdentity identity;
    private final GateLinks links;
    private final SceneWorld world;
    private final ClientRequestHandler requests;
    private final SceneMetrics metrics;

    /** @param metrics 握手回包（hello_ack）的出站计数；其余出站帧由 {@link GateLinks} 计 */
    public SceneLinkService(LinkIdentity identity, GateLinks links, SceneWorld world, ClientRequestHandler requests,
                            SceneMetrics metrics) {
        this.identity = identity;
        this.links = links;
        this.world = world;
        this.requests = requests;
        this.metrics = metrics;
    }

    /** 拒绝过期租约代次时给 gate 的原因。 */
    static final String STALE_LEASE_REASON = "gate 节点号租约代次过期";

    @Override
    public void linkOpened(long linkId, LinkHello hello, Channel channel) {
        GateLinks.Registration registration = links.register(linkId, hello.getGateNodeId(), hello.getGateInstanceId(),
                hello.getLeaseEpoch(), channel);
        if (!registration.accepted()) {
            GateLinks.Link current = registration.current();
            log.warn("拒绝租约代次过期的 gate 链路 gate_node={} 实例={} 代次={}，现有链路 link={} 实例={} 代次={}",
                    hello.getGateNodeId(), hello.getGateInstanceId(), Long.toUnsignedString(hello.getLeaseEpoch()),
                    current.linkId(), current.gateInstanceId(), Long.toUnsignedString(current.leaseEpoch()));
            channel.writeAndFlush(identity.ack(false, STALE_LEASE_REASON)).addListener(ChannelFutureListener.CLOSE);
            metrics.linkFrameOut(NodeLinkFrame.BodyCase.HELLO_ACK);
            return;
        }
        GateLinks.Link replaced = registration.replaced();
        if (replaced != null) {
            log.warn("gate 节点 {} 的新链路顶替旧链路 旧link={} 旧实例={} 新link={} 新实例={} 代次 {}→{}", hello.getGateNodeId(),
                    replaced.linkId(), replaced.gateInstanceId(), linkId, hello.getGateInstanceId(),
                    Long.toUnsignedString(replaced.leaseEpoch()), Long.toUnsignedString(hello.getLeaseEpoch()));
            world.onLinkClosed(replaced.linkId());
            replaced.channel().close();
        }
        channel.writeAndFlush(identity.ack(true, ""));
        metrics.linkFrameOut(NodeLinkFrame.BodyCase.HELLO_ACK);
    }

    @Override
    public void frameReceived(long linkId, NodeLinkFrame frame) {
        if (!links.isRegistered(linkId)) {
            log.debug("链路已注销（被顶替或已断开），丢弃帧 link={} 类型={}", linkId, frame.getBodyCase());
            return;
        }
        switch (frame.getBodyCase()) {
            case PLAYER_ENTER -> world.onPlayerEnter(linkId, frame.getPlayerEnter());
            case PLAYER_LEAVE -> world.onPlayerLeave(linkId, frame.getPlayerLeave());
            case CLIENT_FORWARD -> requests.onClientForward(linkId, frame.getClientForward());
            default -> log.warn("gate 链路上出现不该由 gate 发的帧，忽略 link={} 类型={}", linkId, frame.getBodyCase());
        }
    }

    @Override
    public void linkClosed(long linkId) {
        if (links.unregister(linkId) != null) {
            world.onLinkClosed(linkId);
        }
    }
}
