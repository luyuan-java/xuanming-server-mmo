package com.game.gate.link;

import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.SceneNodeInfo;
import com.game.net.link.NodeLinkCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 真实建链：在 {@code resolveExecutor} 上查节点目录（阻塞 Redis，不能在 Netty I/O 线程上做），
 * 再用 {@code group} 异步连接。任何失败都以 {@link SceneLink#fail} 结束。
 */
public final class NettyLinkConnector implements LinkConnector {

    /** gate → scene 出站缓冲的水位：低 8MB / 高 32MB。 */
    static final WriteBufferWaterMark LINK_WATER_MARK = new WriteBufferWaterMark(8 << 20, 32 << 20);

    private final SceneNodeResolver resolver;
    private final Executor resolveExecutor;
    private final EventLoopGroup group;
    private final Duration connectTimeout;

    public NettyLinkConnector(SceneNodeResolver resolver, Executor resolveExecutor, EventLoopGroup group, Duration connectTimeout) {
        this.resolver = resolver;
        this.resolveExecutor = resolveExecutor;
        this.group = group;
        this.connectTimeout = connectTimeout;
    }

    @Override
    public void connect(SceneLink link) {
        try {
            resolveExecutor.execute(() -> resolveAndConnect(link));
        } catch (RejectedExecutionException e) {
            link.fail("建链线程已关闭");
        }
    }

    private void resolveAndConnect(SceneLink link) {
        Optional<SceneNodeInfo> info;
        try {
            info = resolver.resolve(link.nodeId());
        } catch (RuntimeException e) {
            link.fail("查询 scene 节点目录失败: " + e);
            return;
        }
        if (info.isEmpty() || info.get().getLinkHost().isBlank() || info.get().getLinkPort() == 0) {
            link.fail("节点目录中没有该 scene 节点或地址为空");
            return;
        }
        String host = info.get().getLinkHost();
        int port = info.get().getLinkPort();
        try {
            new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                    .option(ChannelOption.TCP_NODELAY, true)
                    // 水位设得很宽，正常突发不触发；越过高水位 = scene 长时间读不动，SceneLink 判死这条链路（见 offer）。
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, LINK_WATER_MARK)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            NodeLinkCodec.install(ch.pipeline(), NodeLinkFrame.getDefaultInstance());
                            ch.pipeline().addLast("sceneLink", new SceneLinkHandler(link));
                        }
                    })
                    .connect(host, port)
                    .addListener((ChannelFutureListener) f -> {
                        if (!f.isSuccess()) {
                            link.fail("连接 " + host + ":" + port + " 失败: " + f.cause());
                        }
                    });
        } catch (RuntimeException e) {
            link.fail("发起连接失败: " + e);
        }
    }
}
