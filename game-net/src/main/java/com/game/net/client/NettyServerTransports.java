package com.game.net.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadFactory;

/**
 * 面向公网客户端监听端口的 Netty 传输层选择：
 * Linux 且 classpath 具备 Netty Epoll 本地库时自动启用 Epoll，并可开启 {@code TCP_DEFER_ACCEPT}（对齐主仓 G10 / P2-1）；
 * 其余环境（Windows / macOS / 测试注入 NIO 组）无缝回落 NIO。
 */
public final class NettyServerTransports {

    /** 主仓 gate / battle 监听端口的 TCP_DEFER_ACCEPT 秒数（1 秒）。 */
    public static final int DEFAULT_TCP_DEFER_ACCEPT_SECONDS = 1;

    private static final String EPOLL_CLASS = "io.netty.channel.epoll.Epoll";
    private static final String EPOLL_GROUP_CLASS = "io.netty.channel.epoll.EpollEventLoopGroup";
    private static final String EPOLL_SERVER_CHANNEL_CLASS = "io.netty.channel.epoll.EpollServerSocketChannel";
    private static final String EPOLL_CHANNEL_OPTION_CLASS = "io.netty.channel.epoll.EpollChannelOption";

    private static final boolean EPOLL_AVAILABLE = detectEpoll();

    private NettyServerTransports() {
    }

    /** 当前运行环境是否具备可用的 Netty Epoll 传输。 */
    public static boolean epollAvailable() {
        return EPOLL_AVAILABLE;
    }

    /** 判断给定的 {@link EventLoopGroup} 是否为 Epoll 组（供 child group 由调用方传入时对齐传输类型）。 */
    public static boolean isEpollGroup(EventLoopGroup group) {
        return group != null && EPOLL_GROUP_CLASS.equals(group.getClass().getName());
    }

    /** 按是否启用 Epoll 创建对应的 {@link EventLoopGroup}。 */
    public static EventLoopGroup newEventLoopGroup(int threads, String threadPrefix, boolean useEpoll) {
        DefaultThreadFactory factory = new DefaultThreadFactory(threadPrefix);
        if (useEpoll) {
            try {
                Class<?> cls = Class.forName(EPOLL_GROUP_CLASS);
                Constructor<?> ctor = cls.getConstructor(int.class, ThreadFactory.class);
                return (EventLoopGroup) ctor.newInstance(threads, factory);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("初始化 EpollEventLoopGroup 失败", e);
            }
        }
        return new NioEventLoopGroup(threads, factory);
    }

    /** 配置监听端口 channel 类型；启用 Epoll 且 {@code tcpDeferAcceptSeconds > 0} 时设置 {@code TCP_DEFER_ACCEPT}。 */
    @SuppressWarnings("unchecked")
    public static ServerBootstrap configureServer(ServerBootstrap bootstrap, boolean useEpoll, int tcpDeferAcceptSeconds) {
        if (!useEpoll) {
            return bootstrap.channel(NioServerSocketChannel.class);
        }
        try {
            Class<? extends ServerChannel> chClass =
                    (Class<? extends ServerChannel>) Class.forName(EPOLL_SERVER_CHANNEL_CLASS);
            bootstrap.channel(chClass);
            if (tcpDeferAcceptSeconds > 0) {
                Class<?> optClass = Class.forName(EPOLL_CHANNEL_OPTION_CLASS);
                Field field = optClass.getField("TCP_DEFER_ACCEPT");
                ChannelOption<Integer> deferAccept = (ChannelOption<Integer>) field.get(null);
                bootstrap.option(deferAccept, tcpDeferAcceptSeconds);
            }
            return bootstrap;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("配置 EpollServerSocketChannel / TCP_DEFER_ACCEPT 失败", e);
        }
    }

    private static boolean detectEpoll() {
        try {
            Class<?> cls = Class.forName(EPOLL_CLASS);
            Method isAvailable = cls.getMethod("isAvailable");
            return Boolean.TRUE.equals(isAvailable.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
