package com.game.battle.edge;

import com.game.battle.room.DirectLink;
import com.game.proto.MessageContent;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * 直连面的共享帧（mmorpg battle-spectate-tiered-fanout.md §10 第 3 条「观众帧编码一次」）：同一帧只编码一次的客户端帧字节
 * （{@code ClientFrames.encode}，与 pipeline 里 {@code ClientFrameEncoder} 逐条编出的字节完全相同）。
 *
 * <p>引用计数（Netty 标准做法）：每条连接写一份 {@link ByteBuf#retainedDuplicate()}——共享内容、各自的读写下标，交给
 * {@code writeAndFlush} 之后归 Netty，写完或写失败（连接已关、出站处理器拒收）都由 Netty 释放；扇出结束由调用方 {@link #close()}
 * 释放原件。所有引用放掉之后内存才回到分配器，任何一条连接写到一半都不会读到已回收的内存。
 *
 * <p>只在 battle 逻辑线程上用（直连 I/O 与房间同一条线程），不跨扇出保留；不是线程安全的。
 */
final class EncodedFrame implements DirectLink.SharedFrame {

    private final MessageContent content;
    /** 原件；{@link #close()} 之后为 null。 */
    private ByteBuf bytes;

    EncodedFrame(MessageContent content, ByteBuf bytes) {
        this.content = Objects.requireNonNull(content, "content");
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public MessageContent content() {
        return content;
    }

    /**
     * 给一条连接的一份引用：调用方必须立刻交给 {@code writeAndFlush}（此后由 Netty 释放），中间不能再有会抛异常的代码。
     *
     * @throws IllegalStateException 原件已经释放（扇出结束之后还在写：程序缺陷）
     */
    ByteBuf retainedBytes() {
        ByteBuf original = bytes;
        if (original == null) {
            throw new IllegalStateException("共享帧已释放，不能再写");
        }
        return original.retainedDuplicate();
    }

    @Override
    public void close() {
        ByteBuf original = bytes;
        bytes = null;
        if (original != null) {
            original.release();
        }
    }
}
