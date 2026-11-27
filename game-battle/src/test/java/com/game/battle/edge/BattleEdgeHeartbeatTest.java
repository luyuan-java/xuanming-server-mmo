package com.game.battle.edge;

import static com.game.battle.edge.EdgeTestKit.BATTLE;
import static com.game.battle.edge.EdgeTestKit.INSTANCE;
import static com.game.battle.edge.EdgeTestKit.NODE_ID;
import static com.game.battle.edge.EdgeTestKit.NOW_MS;
import static com.game.battle.edge.EdgeTestKit.PLAYER;
import static com.game.battle.edge.EdgeTestKit.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.battle.BattleIdentity;
import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.metrics.BattleMetrics.HeartbeatResult;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.room.BattleRoomService;
import com.game.net.limit.MessageLimits;
import com.game.common.token.BattleTickets;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** battle 直连应用层心跳（10s/30s 通告、验签前断开、10s 限 3 条计非法包、30s 读空闲断开与卡顿延后、排空终局不打断）。 */
class BattleEdgeHeartbeatTest {

    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    private final BattleTickets tickets = BattleTickets.ofUtf8(SECRET);
    private final DefaultEventLoopGroup unusedLogic = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        unusedLogic.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }

    private record Harness(BattleEdgeServer server, BattleRoomService rooms, SimpleMeterRegistry meters,
                           AtomicLong nanos, EmbeddedChannel ch, BattleTickets tickets) {
        double heartbeat(HeartbeatResult result) {
            return meters.counter("game.battle.client.heartbeat", "result", result.name().toLowerCase()).count();
        }

        double disconnect(Disconnect reason) {
            return meters.counter("game.battle.disconnects", "reason", reason.name().toLowerCase()).count();
        }

        void verifyParticipant() {
            when(rooms.attachDirect(any(), any())).thenReturn(OptionalInt.of(12));
            when(rooms.currentDirect(any(), any())).thenReturn(true);
            ch.writeInbound(EdgeTestKit.buf(EdgeTestKit.participant(tickets, BATTLE, PLAYER)));
            List<Message> out = EdgeTestKit.drain(ch);
            assertThat(out).hasSize(1);
            BattleTokenVerifyResponse resp = (BattleTokenVerifyResponse) out.getFirst();
            assertThat(resp.getSuccess()).isTrue();
            assertThat(resp.getHeartbeatIntervalMs()).isEqualTo(BattleFrames.HEARTBEAT_INTERVAL_MS);
            assertThat(resp.getHeartbeatTimeoutMs()).isEqualTo(BattleFrames.HEARTBEAT_TIMEOUT_MS);
        }
    }

    private Harness create(int illegalThreshold) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        BattleRoomService rooms = mock(BattleRoomService.class);
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        BattleProperties props = EdgeTestKit.props(100, Duration.ofSeconds(10), illegalThreshold);
        EdgeDependencies deps = new EdgeDependencies(
                props, unusedLogic, rooms, tickets,
                new BattleIdentity(NODE_ID, INSTANCE, "127.0.0.1", 12000),
                () -> NOW_MS, MessageLimits.of(Map.of()), ids, new BattleMetrics(meters));
        BattleEdgeServer server = new BattleEdgeServer(deps, nanos::get);
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                server.initChannel(c);
            }
        });
        ch.freezeTime();
        return new Harness(server, rooms, meters, nanos, ch, tickets);
    }

    @Test
    void 握手前发心跳立即断开且不回包() {
        Harness h = create(3);
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(1).build()));

        assertThat(EdgeTestKit.drain(h.ch())).isEmpty();
        assertThat(h.ch().isOpen()).isFalse();
        assertThat(h.heartbeat(HeartbeatResult.BEFORE_VERIFY)).isEqualTo(1);
        assertThat(h.disconnect(Disconnect.REQUEST_BEFORE_VERIFY)).isEqualTo(1);
    }

    @Test
    void 验签后10秒内最多回3条心跳_超限不回且计入非法包阈值断开_新窗口重置() {
        Harness h = create(2);
        h.verifyParticipant();
        assertThat(h.heartbeat(HeartbeatResult.TRACKED)).isEqualTo(1);

        // 10s 窗口内前 3 条原样回显 seq
        for (long seq = 1; seq <= 3; seq++) {
            h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(seq).build()));
            List<Message> replies = EdgeTestKit.drain(h.ch());
            assertThat(replies).containsExactly(ClientHeartbeatResponse.newBuilder().setSeq(seq).build());
        }
        assertThat(h.heartbeat(HeartbeatResult.ANSWERED)).isEqualTo(3);

        // 第 4 条超限不回包、计非法包 1（未达阈值 2 仍存活）
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(4).build()));
        assertThat(EdgeTestKit.drain(h.ch())).isEmpty();
        assertThat(h.ch().isOpen()).isTrue();
        assertThat(h.heartbeat(HeartbeatResult.EXCESS)).isEqualTo(1);

        // 推进 10s 进入新窗口，恢复应答
        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(10));
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(5).build()));
        assertThat(EdgeTestKit.drain(h.ch()))
                .containsExactly(ClientHeartbeatResponse.newBuilder().setSeq(5).build());

        // 再发 3 条（2 条正常 + 1 条超限 -> 累计非法包达阈值 2 强关）
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(6).build()));
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(7).build()));
        assertThat(EdgeTestKit.drain(h.ch())).hasSize(2);
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(8).build()));
        assertThat(EdgeTestKit.drain(h.ch())).isEmpty();
        assertThat(h.ch().isOpen()).isFalse();
        assertThat(h.disconnect(Disconnect.ILLEGAL_PACKETS)).isEqualTo(1);
    }

    @Test
    void 连续30秒无入站帧读空闲断开并摘槽_心跳续活_事件循环迟到2秒以上推迟重判() {
        Harness h = create(5);
        h.verifyParticipant();

        // 20s 时发一次心跳续活 -> 30s 首个检查点发现剩余 20s 不断开
        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(20));
        h.ch().writeInbound(EdgeTestKit.buf(ClientHeartbeatRequest.newBuilder().setSeq(1).build()));
        assertThat(EdgeTestKit.drain(h.ch())).hasSize(1);
        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(10));
        h.ch().advanceTimeBy(30, TimeUnit.SECONDS);
        h.ch().runScheduledPendingTasks();
        assertThat(h.ch().isOpen()).isTrue();

        // 模拟 EventLoop 迟到 3s（> 2s 阈值）-> 记 DEFERRED 并推迟 1s，不立刻断开
        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(23));
        h.ch().advanceTimeBy(20, TimeUnit.SECONDS);
        h.ch().runScheduledPendingTasks();
        assertThat(h.ch().isOpen()).isTrue();
        assertThat(h.heartbeat(HeartbeatResult.DEFERRED)).isEqualTo(1);

        // 推迟 1s 后仍无新帧 -> 记 IDLE_CLOSED、断开并向房间摘除直连槽
        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(1));
        h.ch().advanceTimeBy(1, TimeUnit.SECONDS);
        h.ch().runScheduledPendingTasks();
        assertThat(h.ch().isOpen()).isFalse();
        assertThat(h.heartbeat(HeartbeatResult.IDLE_CLOSED)).isEqualTo(1);
        assertThat(h.disconnect(Disconnect.CLIENT_IDLE)).isEqualTo(1);
        verify(h.rooms()).detachDirect(eq(BATTLE), eq(PLAYER), any());
    }

    @Test
    void 房间已在优雅排空终局包时不被空闲定时器打断() {
        Harness h = create(5);
        h.verifyParticipant();
        BattleEdgeHandler handler = h.ch().pipeline().get(BattleEdgeHandler.class);
        handler.session().closeGracefully(Disconnect.BATTLE_CLOSED);

        h.nanos().addAndGet(TimeUnit.SECONDS.toNanos(31));
        h.ch().advanceTimeBy(30, TimeUnit.SECONDS);
        h.ch().runScheduledPendingTasks();

        assertThat(h.heartbeat(HeartbeatResult.IDLE_CLOSED)).isZero();
        assertThat(h.disconnect(Disconnect.CLIENT_IDLE)).isZero();
        ByteBuf remaining;
        while ((remaining = h.ch().readOutbound()) != null) {
            remaining.release();
        }
    }
}
