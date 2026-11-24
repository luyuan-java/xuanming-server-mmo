package com.game.battle.edge;

import static com.game.battle.edge.EdgeTestKit.BATTLE;
import static com.game.battle.edge.EdgeTestKit.INSTANCE;
import static com.game.battle.edge.EdgeTestKit.NODE_ID;
import static com.game.battle.edge.EdgeTestKit.NOW_MS;
import static com.game.battle.edge.EdgeTestKit.PLAYER;
import static com.game.battle.edge.EdgeTestKit.buf;
import static com.game.battle.edge.EdgeTestKit.drain;
import static com.game.battle.edge.EdgeTestKit.labels;
import static com.game.battle.edge.EdgeTestKit.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.BattleIdentity;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.protocol.BattleMessageIds;
import com.game.common.net.SourcePrefixRateLimiter;
import com.game.common.token.BattleTickets;
import com.game.net.limit.MessageLimits;
import com.game.proto.eBattleTicketRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 直连面 O(1) 未认证连接计数与承压期仅公网来源前缀限速校验。 */
class BattleEdgeProtectionTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(meters);
    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    private final BattleTickets tickets = BattleTickets.ofUtf8(EdgeTestKit.SECRET);
    private final FakeRooms rooms = new FakeRooms(ids);
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final DefaultEventLoopGroup unusedLogic = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        unusedLogic.shutdownGracefully();
    }

    @Test
    void 未认证计数O1维护_验票成功与未验票断连均精确归零() {
        BattleEdgeServer edge = newEdge();
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        EmbeddedChannel c1 = openFrom(edge, "198.51.100.10");
        EmbeddedChannel c2 = openFrom(edge, "198.51.100.11");
        assertThat(edge.connectionCount()).isEqualTo(2);
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(2);

        // c1 验票成功：未认证计数减 1，总连接数仍为 2
        c1.writeInbound(buf(participant(tickets, BATTLE, PLAYER)));
        assertThat(labels(drain(c1))).containsExactly("verify-ok:" + BATTLE);
        assertThat(edge.connectionCount()).isEqualTo(2);
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);

        // c1 断开：已验证连接不得重复扣减未认证计数
        c1.close();
        c1.runPendingTasks();
        assertThat(edge.connectionCount()).isEqualTo(1);
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);

        // c2 未验证断开：未认证计数归零
        c2.close();
        c2.runPendingTasks();
        assertThat(edge.connectionCount()).isZero();
        assertThat(edge.unauthenticatedConnectionCount()).isZero();
    }

    @Test
    void 仅公网来源限速在承压时激活_私网与回环始终放行() {
        BattleEdgeServer edge = newEdge().withSourcePrefixRateLimiter(
                new SourcePrefixRateLimiter(1.0, 1, 64, nanos::get), 1);

        // 第 1 条公网连接到达时未承压（unauth=0 < budget=1），放行后 unauth=1 进入承压
        EmbeddedChannel c1 = openFrom(edge, "198.51.100.1");
        assertThat(c1.isOpen()).isTrue();
        assertThat(edge.unauthenticatedConnectionCount()).isEqualTo(1);

        // 承压期：同一公网 /24 消耗完 burst=1 后被拒
        EmbeddedChannel c2 = openFrom(edge, "198.51.100.2");
        assertThat(c2.isOpen()).isTrue();
        EmbeddedChannel c3 = openFrom(edge, "198.51.100.3");
        assertThat(c3.isOpen()).isFalse();

        // 承压期：内网代理/回环地址跳过来源前缀限速，照常放行
        EmbeddedChannel priv1 = openFrom(edge, "10.0.0.5");
        EmbeddedChannel priv2 = openFrom(edge, "10.0.0.6");
        EmbeddedChannel loop = openFrom(edge, "127.0.0.1");
        assertThat(priv1.isOpen()).isTrue();
        assertThat(priv2.isOpen()).isTrue();
        assertThat(loop.isOpen()).isTrue();

        c1.close();
        c2.close();
        priv1.close();
        priv2.close();
        loop.close();
    }

    private BattleEdgeServer newEdge() {
        return new BattleEdgeServer(new EdgeDependencies(
                EdgeTestKit.props(4096, Duration.ofSeconds(10), 50),
                unusedLogic, rooms, tickets,
                new BattleIdentity(NODE_ID, INSTANCE, "127.0.0.1", 12000),
                () -> NOW_MS, MessageLimits.of(Map.of()), ids, metrics),
                nanos::get);
    }

    private static final ThreadLocal<SocketAddress> PENDING_REMOTE = new ThreadLocal<>();

    private static EmbeddedChannel openFrom(BattleEdgeServer edge, String host) {
        InetSocketAddress remote;
        try {
            remote = new InetSocketAddress(InetAddress.getByName(host), 40000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        PENDING_REMOTE.set(remote);
        try {
            EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel c) {
                    edge.initChannel(c);
                }
            }) {
                private final SocketAddress fixedRemote = PENDING_REMOTE.get();

                @Override
                protected SocketAddress remoteAddress0() {
                    return fixedRemote != null ? fixedRemote : PENDING_REMOTE.get();
                }
            };
            ch.freezeTime();
            return ch;
        } finally {
            PENDING_REMOTE.remove();
        }
    }
}
