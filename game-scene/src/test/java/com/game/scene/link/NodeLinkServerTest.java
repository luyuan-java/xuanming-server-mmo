package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;
import io.netty.channel.*;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 真实loopback链路验证关闭结局；不用进程消失、睡眠或固定超时冒充已排空。 */
class NodeLinkServerTest {
    @Test void 实际已接入链路与IO屏障完成后才返回成功()throws Exception {
        NodeLinkServer server=new NodeLinkServer(1);CountDownLatch accepted=new CountDownLatch(1);AtomicReference<Channel> child=new AtomicReference<>();
        int port=server.start("127.0.0.1",0,()->new ChannelInboundHandlerAdapter(){@Override public void channelActive(ChannelHandlerContext context)throws Exception{child.set(context.channel());accepted.countDown();super.channelActive(context);}});
        try(Socket client=new Socket("127.0.0.1",port)){
            assertThat(accepted.await(5,TimeUnit.SECONDS)).isTrue();assertThat(server.closeAndJoin(Duration.ofSeconds(6))).isTrue();assertThat(child.get().isOpen()).isFalse();assertThat(server.connectionCount()).isZero();
        }finally{server.closeAndJoin(Duration.ofSeconds(6));server.shutdown();}
    }
    @Test void 关闭操作没有完成时保持失败而非超时成功()throws Exception {
        NodeLinkServer server=new NodeLinkServer(1);CountDownLatch accepted=new CountDownLatch(1);AtomicReference<Channel> child=new AtomicReference<>();
        ChannelDuplexHandler blocker=new ChannelDuplexHandler(){@Override public void channelActive(ChannelHandlerContext context)throws Exception{child.set(context.channel());accepted.countDown();super.channelActive(context);}@Override public void close(ChannelHandlerContext context,ChannelPromise promise){/* 显式挂起关闭，模拟尚未完成的实际IO；测试finally移除后正常清理。 */}};
        int port=server.start("127.0.0.1",0,()->blocker);
        try(Socket client=new Socket("127.0.0.1",port)){
            assertThat(accepted.await(5,TimeUnit.SECONDS)).isTrue();assertThat(server.closeAndJoin(Duration.ofSeconds(1))).isFalse();assertThat(child.get().isOpen()).isTrue();
        }finally{if(child.get()!=null)child.get().pipeline().remove(blocker);server.closeAndJoin(Duration.ofSeconds(6));server.shutdown();}
    }
}
