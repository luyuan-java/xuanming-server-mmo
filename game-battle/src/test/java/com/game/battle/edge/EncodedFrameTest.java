package com.game.battle.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.DirectLink;
import com.game.net.client.ClientFrameEncoder;
import com.game.proto.MessageContent;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 观众帧编码一次（mmorpg battle-spectate-tiered-fanout.md §10 第 3 条）：{@link DirectSession#share} 只编码一次，各条直连写同一份字节的
 * {@code retainedDuplicate}，线上字节与逐条经 pipeline 编码器编出的完全相同；引用计数在写成功、写失败、跳过不活直连时都不泄漏。
 *
 * <p>用非池化、关掉泄漏探测包装的分配器：派生缓冲与原件共用一个引用计数，读数是确定的（池化分配器的派生缓冲各自计数、放到 0 时再放原件一次，
 * 语义相同但读数不直观）。
 */
class EncodedFrameTest {

    private static final MessageContent FRAME = MessageContent.newBuilder().setMessageId(158)
            .setSerializedMessage(ByteString.copyFromUtf8("观众版回合帧")).build();

    private final BattleMetrics metrics = new BattleMetrics(new SimpleMeterRegistry());

    private static EmbeddedChannel channel(ChannelHandler... handlers) {
        EmbeddedChannel ch = new EmbeddedChannel(handlers);
        ch.config().setAllocator(new UnpooledByteBufAllocator(false, true));
        return ch;
    }

    /** 握手成功的观众直连（EmbeddedChannel 的 EventLoop 就是调用线程，满足「只在逻辑线程上用」）。 */
    private DirectSession verified(EmbeddedChannel ch, long playerId) {
        DirectSession session = new DirectSession(ch, metrics);
        session.markVerified(EdgeTestKit.BATTLE, playerId, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, 7);
        return session;
    }

    /** 改造前的写法：每条直连各自把 MessageContent 交给 pipeline 编码器编一遍。 */
    private static byte[] perConnectionBytes() {
        EmbeddedChannel legacy = channel(ClientFrameEncoder.INSTANCE);
        legacy.writeOutbound(FRAME);
        ByteBuf encoded = legacy.readOutbound();
        try {
            return ByteBufUtil.getBytes(encoded);
        } finally {
            encoded.release();
            legacy.finishAndReleaseAll();
        }
    }

    @Test
    void 同一帧只编码一次_每条活直连写同一份字节_线上字节与逐条编码相同_全部放掉之后无泄漏() {
        EmbeddedChannel a = channel(ClientFrameEncoder.INSTANCE);
        EmbeddedChannel b = channel(ClientFrameEncoder.INSTANCE);
        DirectSession first = verified(a, 601);
        DirectSession second = verified(b, 602);

        DirectLink.SharedFrame shared = first.share(FRAME);
        assertThat(shared).isInstanceOf(EncodedFrame.class);
        assertThat(shared.content()).isSameAs(FRAME);
        first.send(shared);
        second.send(shared);
        ByteBuf toA = a.readOutbound();
        ByteBuf toB = b.readOutbound();

        assertThat(toA.unwrap()).as("两条连接写的是同一份编码出的字节").isNotNull().isSameAs(toB.unwrap());
        assertThat(toA.refCnt()).as("原件 + 两份在途引用").isEqualTo(3);
        shared.close();
        shared.close();
        assertThat(toA.refCnt()).as("扇出结束只放掉原件一次（close 幂等），两份在途引用还在").isEqualTo(2);
        byte[] expected = perConnectionBytes();
        assertThat(ByteBufUtil.getBytes(toA)).isEqualTo(expected);
        assertThat(ByteBufUtil.getBytes(toB)).isEqualTo(expected);
        toA.release();
        toB.release();
        assertThat(toA.unwrap().refCnt()).as("写完的引用都放掉：没有泄漏").isZero();
        assertThat(a.finishAndReleaseAll()).isFalse();
        assertThat(b.finishAndReleaseAll()).isFalse();
    }

    @Test
    void 写失败也释放_出站拒收的那份引用由写出流程放掉_原件在扇出结束释放() {
        EmbeddedChannel broken = channel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                // 传输层写失败的标准收尾：处理方放掉消息、置 promise 失败
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("broken pipe"));
            }
        }, ClientFrameEncoder.INSTANCE);
        EmbeddedChannel healthy = channel(ClientFrameEncoder.INSTANCE);
        DirectSession failing = verified(broken, 601);
        DirectSession good = verified(healthy, 602);

        DirectLink.SharedFrame shared = good.share(FRAME);
        failing.send(shared);
        good.send(shared);
        ByteBuf delivered = healthy.readOutbound();

        assertThat((Object) broken.readOutbound()).isNull();
        assertThat(delivered.refCnt()).as("原件 + 写成功的那一份；写失败的那一份已经放掉").isEqualTo(2);
        shared.close();
        delivered.release();
        assertThat(delivered.unwrap().refCnt()).isZero();
        healthy.finishAndReleaseAll();
        broken.finishAndReleaseAll();
    }

    @Test
    void 不是活直连就不写也不多占引用_还没握手的_正在关闭的_连接已断的() {
        EmbeddedChannel live = channel(ClientFrameEncoder.INSTANCE);
        EmbeddedChannel pendingChannel = channel(ClientFrameEncoder.INSTANCE);
        EmbeddedChannel closingChannel = channel(ClientFrameEncoder.INSTANCE);
        EmbeddedChannel deadChannel = channel(ClientFrameEncoder.INSTANCE);
        DirectSession sender = verified(live, 601);
        DirectSession pending = new DirectSession(pendingChannel, metrics);
        DirectSession closing = verified(closingChannel, 603);
        closing.closeGracefully(com.game.battle.metrics.BattleMetrics.Disconnect.BATTLE_CLOSED);
        DirectSession dead = verified(deadChannel, 604);
        deadChannel.close();

        DirectLink.SharedFrame shared = sender.share(FRAME);
        sender.send(shared);
        pending.send(shared);
        closing.send(shared);
        dead.send(shared);
        ByteBuf delivered = live.readOutbound();

        assertThat((Object) pendingChannel.readOutbound()).isNull();
        assertThat((Object) closingChannel.readOutbound()).isNull();
        assertThat((Object) deadChannel.readOutbound()).isNull();
        assertThat(delivered.refCnt()).as("只有活直连拿了一份引用").isEqualTo(2);
        shared.close();
        delivered.release();
        assertThat(delivered.unwrap().refCnt()).isZero();
        live.finishAndReleaseAll();
        pendingChannel.finishAndReleaseAll();
        closingChannel.finishAndReleaseAll();
    }

    @Test
    void 连接已断时share退回不编码的共享帧_测试替身的共享帧按原帧经编码器写出() {
        EmbeddedChannel gone = channel(ClientFrameEncoder.INSTANCE);
        DirectSession detached = verified(gone, 601);
        detached.onInactive();
        DirectLink.SharedFrame unencoded = detached.share(FRAME);
        assertThat(unencoded).isNotInstanceOf(EncodedFrame.class);
        assertThat(unencoded.content()).isSameAs(FRAME);
        unencoded.close();

        EmbeddedChannel ch = channel(ClientFrameEncoder.INSTANCE);
        DirectSession session = verified(ch, 602);
        session.send(DirectLink.SharedFrame.unencoded(FRAME));
        ByteBuf written = ch.readOutbound();
        try {
            assertThat(ByteBufUtil.getBytes(written)).as("不是本直连面产出的共享帧：按原帧经编码器写出，字节不变").isEqualTo(perConnectionBytes());
        } finally {
            written.release();
            ch.finishAndReleaseAll();
            gone.finishAndReleaseAll();
        }
    }

    @Test
    void 扇出结束之后还拿原件写是程序缺陷_不会读到已释放的内存() {
        EmbeddedChannel ch = channel(ClientFrameEncoder.INSTANCE);
        DirectSession session = verified(ch, 601);
        DirectLink.SharedFrame shared = session.share(FRAME);
        shared.close();

        assertThatIllegalStateException().isThrownBy(() -> session.send(shared));
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }
}
