package com.game.scene.link;

import com.game.api.proto.NodeLinkFrame;
import com.game.net.link.NodeLinkCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gate → scene 节点链路的 TCP 服务端。编解码用 {@link NodeLinkCodec}（4 字节长度头 + {@code NodeLinkFrame}）。
 *
 * <p>生命周期：{@link #start} → {@link #stopAccepting}（关监听，已有链路不动）→ {@link #closeLinks}（断开全部已接入的链路）
 * → {@link #shutdown}（关全部 I/O 线程）。I/O 线程只做编解码与握手，场景逻辑在别的线程上（见 {@link NodeLinkHandler}）。
 *
 * <p>链路 channel 的写缓冲水位设得很宽（{@link #LINK_WATER_MARK}）：正常突发不会触发；越过高水位说明 gate 长时间读不动，
 * 由 {@link GateLinks} 断链处理（不丢单帧、不无限堆积）。
 */
public final class NodeLinkServer {

    private static final Logger log = LoggerFactory.getLogger(NodeLinkServer.class);

    /** scene → gate 出站缓冲的水位：低 8MB / 高 32MB。 */
    static final WriteBufferWaterMark LINK_WATER_MARK = new WriteBufferWaterMark(8 << 20, 32 << 20);

    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final ChannelGroup links = new DefaultChannelGroup("scene-links", GlobalEventExecutor.INSTANCE);
    private volatile Channel serverChannel;

    public NodeLinkServer(int ioThreads) {
        this.boss = new NioEventLoopGroup(1, new DefaultThreadFactory("scene-link-accept"));
        this.workers = new NioEventLoopGroup(ioThreads, new DefaultThreadFactory("scene-link-io"));
    }

    /**
     * 绑定并开始接受 gate 连接。
     *
     * @param handlerFactory 每条连接一个新的 {@link NodeLinkHandler}
     * @return 实际监听的端口（配置 0 时为系统分配的端口）
     */
    public int start(String bindHost, int port, Supplier<ChannelHandler> handlerFactory) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, LINK_WATER_MARK)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        links.add(ch);
                        NodeLinkCodec.install(ch.pipeline(), NodeLinkFrame.getDefaultInstance());
                        ch.pipeline().addLast("sceneLink", handlerFactory.get());
                    }
                });
        Channel channel = bootstrap.bind(bindHost, port).sync().channel();
        serverChannel = channel;
        int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
        log.info("节点链路开始监听 {}:{}", bindHost, boundPort);
        return boundPort;
    }

    /** 关闭监听端口，不再接受新的 gate 连接；已建立的链路不受影响。幂等。 */
    public void stopAccepting() {
        Channel channel = serverChannel;
        if (channel != null && channel.isOpen()) {
            channel.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
            log.info("节点链路已停止监听");
        }
    }

    /**
     * 断开全部已接入的 gate 链路（停服第一步）：不再有新帧进来，逻辑线程的积压只减不增；
     * 各链路的断开事件照常投递到逻辑线程，其上玩家按断线写回。幂等。
     */
    public void closeLinks() {
        if (!links.isEmpty()) {
            log.info("停服：断开全部 gate 链路 条数={}", links.size());
            links.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
        }
    }

    /** 关闭监听与全部 I/O 线程（已有链路随之断开）。幂等。 */
    public void shutdown() {
        stopAccepting();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }
}
