package com.game.robot.client;

import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 带握手的假 gate（批次 5.4；照 {@code GameConnectionTest.FakeGate}，做成可复用的测试替身）：本机回环上监听一个随机端口，
 * 只按客户端契约收发帧——首帧 {@code ClientTokenVerifyRequest} 回 {@code ClientTokenVerifyResponse}，之后的 {@code ClientRequest}
 * 交给用例给的 {@link Script}。不含任何游戏逻辑；它是探针这一侧的测试替身，<b>不是服务端行为的证明</b>。
 *
 * <p>线程：每个假 gate 一条自己的 I/O 线程，{@link Script} 的三个回调都在那条线程上串行执行（同一个 gate 的回调之间不用加锁）；
 * {@link Session} 的发送方法可以在任意线程调用。多个假 gate 之间的先后关系看共用的 {@link Events}。
 */
public final class FakeGate implements AutoCloseable {

    /** 握手通过的应答。 */
    public static final ClientTokenVerifyResponse VERIFY_OK = ClientTokenVerifyResponse.newBuilder().setSuccess(true).build();

    /** 握手被拒的应答（真 gate 回完它就关连接，假 gate 同）。 */
    public static ClientTokenVerifyResponse verifyRejected(String error) {
        return ClientTokenVerifyResponse.newBuilder().setSuccess(false).setError(error).build();
    }

    /** 用例给的行为。 */
    public interface Script {

        /**
         * 收到握手帧。返回应答即刻回出；返回 null = 现在不回（压着），之后由用例调 {@link Session#completeVerify}。缺省放行。
         */
        default ClientTokenVerifyResponse onVerify(Session session, ClientTokenVerifyRequest request) {
            return VERIFY_OK;
        }

        /** 收到一条请求。不调 {@link Session#reply} 就是不回包。 */
        void onRequest(Session session, ClientRequest request) throws Exception;

        /** 连接断开（哪一端关的都算）。 */
        default void onClosed(Session session) {
        }
    }

    /**
     * 几个假 gate 共用的事件账，按发生先后记。事件名：{@code <gate 名> accept#<连接序号>}、{@code … verify#n}（收到握手帧，回应答之前）、
     * {@code … request#n <消息号>}、{@code … closed#n}。用例按事件先后断言，不按时间差。
     */
    public static final class Events {

        private final List<String> log = new ArrayList<>();

        synchronized void add(String event) {
            log.add(event);
            notifyAll();
        }

        public synchronized List<String> snapshot() {
            return List.copyOf(log);
        }

        /** 事件在账里的位置；没有为 -1。 */
        public synchronized int indexOf(String event) {
            return log.indexOf(event);
        }

        /** 等到某个事件出现；到点还没有返回 false。 */
        public synchronized boolean await(String event, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!log.contains(event)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }
    }

    /** 一条客户端连接。 */
    public final class Session {

        private final int index;
        private final Channel channel;
        private volatile ClientTokenVerifyRequest verify;
        /** 用例自己挂的状态（例如这条连接登录的账号）；只在本 gate 的 I/O 线程上读写。 */
        public Object attachment;

        private Session(int index, Channel channel) {
            this.index = index;
            this.channel = channel;
        }

        /** 这是本 gate 接到的第几条连接（从 0 起）。 */
        public int index() {
            return index;
        }

        /** 客户端发来的握手帧原样（票据字节是否原样转发看它）；还没握手为 null。 */
        public ClientTokenVerifyRequest verify() {
            return verify;
        }

        /** 回一条应答：消息号与请求相同、{@code id} 回显。 */
        public void reply(ClientRequest request, Message body) {
            channel.writeAndFlush(MessageContent.newBuilder().setMessageId(request.getMessageId()).setId(request.getId())
                    .setSerializedMessage(body.toByteString()).build());
        }

        /** 推一条下行（{@code id = 0}）。 */
        public void push(int messageId, Message body) {
            channel.writeAndFlush(MessageContent.newBuilder().setMessageId(messageId).setSerializedMessage(body.toByteString()).build());
        }

        /** 补回被压着的握手应答；被拒的回完即关连接。 */
        public void completeVerify(ClientTokenVerifyResponse response) {
            if (response.getSuccess()) {
                channel.writeAndFlush(response);
            } else {
                channel.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }

        /** 服务端主动关这条连接。 */
        public void close() {
            channel.close();
        }

        public boolean isOpen() {
            return channel.isActive();
        }
    }

    private final String name;
    private final Events events;
    private final Script script;
    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final Channel server;
    private final List<Session> sessions = new ArrayList<>();
    private final Map<Integer, Integer> received = new HashMap<>();

    /**
     * @param name   事件账里的名字（例：{@code home}、{@code visit}）
     * @param events 事件账；几个假 gate 给同一个就能比较先后
     */
    public FakeGate(String name, Events events, Script script) {
        this.name = name;
        this.events = events;
        this.script = script;
        this.server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Session session = accept(ch);
                        ch.pipeline().addLast(new ClientFrameDecoder(Map.of(
                                ClientTokenVerifyRequest.getDescriptor().getFullName(), ClientTokenVerifyRequest.getDefaultInstance(),
                                ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance()),
                                ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> ctx.close()));
                        ch.pipeline().addLast(ClientFrameEncoder.INSTANCE);
                        ch.pipeline().addLast(new Handler(session));
                    }
                })
                .bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
    }

    public String name() {
        return name;
    }

    public int port() {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    public GateEndpoint endpoint() {
        return new GateEndpoint("127.0.0.1", port());
    }

    /** 至今接到的连接（按接入先后；断开的也留着）。 */
    public synchronized List<Session> sessions() {
        return List.copyOf(sessions);
    }

    /** 某个消息号至今收到的请求数。 */
    public synchronized int received(int messageId) {
        return received.getOrDefault(messageId, 0);
    }

    @Override
    public void close() {
        server.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    private synchronized Session accept(Channel channel) {
        Session session = new Session(sessions.size(), channel);
        sessions.add(session);
        events.add(name + " accept#" + session.index);
        return session;
    }

    private synchronized void count(int messageId) {
        received.merge(messageId, 1, Integer::sum);
    }

    private final class Handler extends SimpleChannelInboundHandler<Message> {

        private final Session session;

        Handler(Session session) {
            this.session = session;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
            if (msg instanceof ClientTokenVerifyRequest verify) {
                session.verify = verify;
                events.add(name + " verify#" + session.index);
                ClientTokenVerifyResponse response = script.onVerify(session, verify);
                if (response != null) {
                    session.completeVerify(response);
                }
                return;
            }
            ClientRequest request = (ClientRequest) msg;
            count(request.getMessageId());
            events.add(name + " request#" + session.index + " " + request.getMessageId());
            script.onRequest(session, request);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            events.add(name + " closed#" + session.index);
            script.onClosed(session);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            events.add(name + " error#" + session.index + " " + cause);
            ctx.close();
        }
    }
}
