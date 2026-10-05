package com.game.battle.edge;

import static com.game.battle.edge.EdgeTestKit.BATTLE;
import static com.game.battle.edge.EdgeTestKit.INSTANCE;
import static com.game.battle.edge.EdgeTestKit.NODE_ID;
import static com.game.battle.edge.EdgeTestKit.NOW_MS;
import static com.game.battle.edge.EdgeTestKit.PLAYER;
import static com.game.battle.edge.EdgeTestKit.label;
import static com.game.battle.edge.EdgeTestKit.participant;
import static com.game.battle.edge.EdgeTestKit.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.BattleIdentity;
import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.protocol.BattleFrames;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.room.DirectLink;
import com.game.common.token.BattleTickets;
import com.game.net.client.ClientFrameException;
import com.game.net.client.ClientFrames;
import com.game.net.limit.MessageLimits;
import com.game.proto.BattleEndS2C;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 真 Netty 回环（battle-node-spec §13.3 的第二组）：生产同款 {@code ServerBootstrap}，child group 就是单线程逻辑组；客户端用阻塞 socket
 * 按线上字节收发。验证 {@code EmbeddedChannel} 验不了的：真 FIN（{@code shutdownOutput}）排在终局包与应答之后、停机排空、
 * child 连接注册在逻辑线程上、{@code DirectLink} 的线程断言。
 */
class BattleEdgeLoopbackTest {

    private static final int SUBMIT = 149;
    private static final int GET_STATE = 140;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(meters);
    private final BattleMessageIds ids = BattleMessageIds.loadFromClasspath();
    private final BattleTickets tickets = BattleTickets.ofUtf8(EdgeTestKit.SECRET);
    private final FakeRooms rooms = new FakeRooms(ids);
    private final NioEventLoopGroup logic = new NioEventLoopGroup(1, new DefaultThreadFactory("battle-logic-test"));
    private BattleEdgeServer edge;

    @AfterEach
    void tearDown() {
        if (edge != null) {
            edge.drainAndClose(Duration.ofMillis(200));
        }
        logic.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void 真连接_提交使全员就绪后依次收到139_150_应答然后FIN() throws Exception {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        rooms.onSubmit = (pid, req) -> {
            DirectLink link = rooms.link(pid);
            link.send(BattleFrames.push(ids.id(Notify.TURN_RESULT), TurnResultS2C.newBuilder().setBattleId(BATTLE).build()));
            link.send(BattleFrames.push(ids.id(Notify.BATTLE_END), BattleEndS2C.newBuilder().setBattleId(BATTLE).build()));
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return SubmitBattleActionResponse.getDefaultInstance();
        };
        startEdge(4096);

        try (TestClient client = connect()) {
            client.send(participant(tickets, BATTLE, PLAYER));
            assertThat(label(client.read())).isEqualTo("verify-ok:" + BATTLE);

            // child 连接注册在唯一的逻辑线程上，写水位 1 / 2 MiB，TCP_NODELAY（§7.3、§7.4）
            Channel child = onLogic(() -> ((DirectSession) rooms.link(PLAYER)).channelOrNull());
            assertThat(child.eventLoop()).isSameAs(logic.next());
            assertThat(child.config().getWriteBufferHighWaterMark()).isEqualTo(2 << 20);
            assertThat(child.config().getWriteBufferLowWaterMark()).isEqualTo(1 << 20);
            assertThat(child.config().getOption(ChannelOption.TCP_NODELAY)).isTrue();

            client.send(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build()));
            assertThat(label(client.read())).isEqualTo("push:139");
            assertThat(label(client.read())).isEqualTo("push:150");
            assertThat(label(client.read())).isEqualTo("reply:149");
            assertThat(client.readEof()).as("应答之后是 FIN（不是 RST）").isTrue();
        }
        await(() -> edge.connectionCount() == 0);
        await(() -> rooms.events.contains("detach:" + PLAYER + ":false"));
        assertThat(count("xm.battle.disconnects", "reason", "battle_closed")).isEqualTo(1);
    }

    @Test
    void 真连接_同一次写里打出最后一击的149与140_两条都有应答然后FIN() throws Exception {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        rooms.onSubmit = (pid, req) -> {
            rooms.link(pid).send(BattleFrames.push(ids.id(Notify.BATTLE_END), BattleEndS2C.newBuilder().setBattleId(BATTLE).build()));
            rooms.closeSlot(pid, Disconnect.BATTLE_CLOSED);
            return SubmitBattleActionResponse.getDefaultInstance();
        };
        startEdge(4096);

        try (TestClient client = connect()) {
            client.send(participant(tickets, BATTLE, PLAYER));
            assertThat(label(client.read())).isEqualTo("verify-ok:" + BATTLE);

            client.sendTogether(request(9, SUBMIT, SubmitBattleActionRequest.newBuilder().setBattleId(BATTLE).build()),
                    request(10, GET_STATE, GetBattleStateRequest.newBuilder().setBattleId(BATTLE).build()));
            assertThat(label(client.read())).isEqualTo("push:150");
            assertThat(label(client.read())).isEqualTo("reply:149");
            assertThat(label(client.read())).as("房间的关闭排在本轮读之后（基线 queueInLoop），同一批的 140 照常应答").isEqualTo("reply:140");
            assertThat(client.readEof()).isTrue();
        }
        await(() -> edge.connectionCount() == 0);
        assertThat(rooms.events).containsSubsequence("submit:" + PLAYER + ":" + BATTLE, "getState:" + PLAYER + ":" + BATTLE);
    }

    @Test
    void 真连接_握手被拒收到应答后FIN() throws Exception {
        startEdge(4096);
        try (TestClient client = connect()) {
            client.send(participant(tickets, BATTLE, PLAYER));
            assertThat(label(client.read())).isEqualTo("verify-fail:" + BattleFrames.REJECT_NOT_IN_ROSTER);
            assertThat(client.readEof()).isTrue();
        }
        await(() -> edge.connectionCount() == 0);
    }

    @Test
    void 真连接_并发上限为1时第二条连接直接被关不回包() throws Exception {
        startEdge(1);
        try (TestClient first = connect()) {
            await(() -> edge.connectionCount() == 1);
            try (TestClient second = connect()) {
                assertThat(second.readEof()).isTrue();
            }
            assertThat(edge.connectionCount()).isEqualTo(1);
            await(() -> count("xm.battle.disconnects", "reason", "at_capacity") == 1);
        }
    }

    @Test
    void 停机排空_优雅关闭中的连接先收完166再FIN_空闲连接被强关_连接数归零() throws Exception {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        startEdge(4096);
        try (TestClient idle = connect(); TestClient watcher = connect()) {
            watcher.send(EdgeTestKit.observer(tickets, BATTLE, PLAYER));
            assertThat(label(watcher.read())).isEqualTo("verify-ok:" + BATTLE);
            assertThat(label(watcher.read())).isEqualTo("push:" + ids.id(Notify.SPECTATE_STATE));
            await(() -> edge.connectionCount() == 2);

            // 停机第 2 步（逻辑线程上的同一个任务）：房间推 166 后摘槽、优雅关闭
            onLogic(() -> {
                DirectLink link = rooms.link(PLAYER);
                link.send(BattleFrames.push(ids.id(Notify.SPECTATE_END), SpectateEndS2C.newBuilder().setBattleId(BATTLE).build()));
                rooms.closeSlot(PLAYER, Disconnect.SHUTDOWN);
                return null;
            });
            edge.drainAndClose(Duration.ofSeconds(1));

            assertThat(edge.connectionCount()).isZero();
            assertThat(edge.localAddress()).isNull();
            assertThat(label(watcher.read())).isEqualTo("push:" + ids.id(Notify.SPECTATE_END));
            assertThat(watcher.readEof()).isTrue();
            assertThat(idle.readEof()).isTrue();
        }
        assertThat(count("xm.battle.disconnects", "reason", "shutdown")).isEqualTo(2);
        edge.drainAndClose(Duration.ofSeconds(1));
    }

    @Test
    void DirectLink只许在逻辑线程上调用() throws Exception {
        rooms.add(BATTLE, PLAYER, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        startEdge(4096);
        try (TestClient client = connect()) {
            client.send(participant(tickets, BATTLE, PLAYER));
            assertThat(label(client.read())).isEqualTo("verify-ok:" + BATTLE);
            DirectLink link = rooms.link(PLAYER);

            assertThatThrownBy(() -> link.send(BattleFrames.push(139, TurnResultS2C.getDefaultInstance())))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> link.closeGracefully(Disconnect.BATTLE_CLOSED)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> link.closeNow(Disconnect.REPLACED)).isInstanceOf(IllegalStateException.class);
            assertThat(onLogic(link::isLive)).isTrue();
        }
    }

    @Test
    void 不得在逻辑线程上排空_重复启动与端口被占用都拒绝() throws Exception {
        startEdge(4096);
        assertThatThrownBy(() -> edge.start()).isInstanceOf(IllegalStateException.class);
        Throwable onLogic = onLogic(() -> {
            try {
                edge.drainAndClose(Duration.ofMillis(10));
                return null;
            } catch (IllegalStateException e) {
                return e;
            }
        });
        assertThat(onLogic).isInstanceOf(IllegalStateException.class);

        int busyPort = edge.localAddress().getPort();
        BattleEdgeServer second = new BattleEdgeServer(deps(EdgeTestKit.loopbackProps(busyPort, 4096)));
        assertThatThrownBy(second::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("绑定失败");
    }

    @Test
    void 逻辑线程组不是单线程时拒绝启动() {
        NioEventLoopGroup two = new NioEventLoopGroup(2, new DefaultThreadFactory("battle-logic-two"));
        try {
            BattleEdgeServer server = new BattleEdgeServer(new EdgeDependencies(EdgeTestKit.loopbackProps(freePort(), 4096), two, rooms,
                    tickets, identity(), () -> NOW_MS, MessageLimits.of(Map.of()), ids, metrics));
            assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("恰好一个");
        } finally {
            two.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // ---------------------------------------------------------------- 工具

    private void startEdge(int maxConnections) {
        edge = new BattleEdgeServer(deps(EdgeTestKit.loopbackProps(freePort(), maxConnections)));
        edge.start();
    }

    private EdgeDependencies deps(BattleProperties properties) {
        return new EdgeDependencies(properties, logic, rooms, tickets, identity(), () -> NOW_MS, MessageLimits.of(Map.of()), ids, metrics);
    }

    private static BattleIdentity identity() {
        return new BattleIdentity(NODE_ID, INSTANCE, "127.0.0.1", 12000);
    }

    private TestClient connect() throws IOException {
        return new TestClient(edge.localAddress());
    }

    private <T> T onLogic(Callable<T> task) throws Exception {
        EventLoop loop = logic.next();
        return loop.submit(task).get(3, TimeUnit.SECONDS);
    }

    private double count(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter().count();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待超时");
            }
            Thread.sleep(10);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 阻塞 socket 客户端：按线上字节发帧、读帧、判断 FIN。 */
    private static final class TestClient implements AutoCloseable {

        private final Socket socket = new Socket();
        private final DataInputStream in;
        private final OutputStream out;

        TestClient(InetSocketAddress address) throws IOException {
            socket.connect(address, 3000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void send(Message message) throws IOException {
            ByteBuf buf = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
            try {
                out.write(ByteBufUtil.getBytes(buf));
                out.flush();
            } finally {
                buf.release();
            }
        }

        /** 几帧拼成一次 {@code write}（回环上落在同一个 TCP 段里，服务端一次读到）。 */
        void sendTogether(Message... messages) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (Message message : messages) {
                ByteBuf buf = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
                try {
                    all.writeBytes(ByteBufUtil.getBytes(buf));
                } finally {
                    buf.release();
                }
            }
            out.write(all.toByteArray());
            out.flush();
        }

        Message read() throws IOException {
            int len = in.readInt();
            byte[] body = in.readNBytes(len);
            try {
                return ClientFrames.decodeBody(Unpooled.wrappedBuffer(body), EdgeTestKit.DOWNSTREAM);
            } catch (ClientFrameException e) {
                throw new AssertionError("下行帧非法: " + e.getMessage(), e);
            }
        }

        /** 下一次读到的是 EOF（对端 FIN）。 */
        boolean readEof() throws IOException {
            return in.read() == -1;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
