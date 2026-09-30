package com.game.gate.session;

import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.DisconnectReason;
import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.google.protobuf.Message;
import io.netty.channel.ChannelPipeline;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端连接的 pipeline：帧解码（上行只收握手与 ClientRequest）→ 帧编码 → 会话 handler。
 * 服务端与单测共用同一份装配，单测里的线上字节与生产一致。
 */
public final class ClientPipeline {

    private static final Logger log = LoggerFactory.getLogger(ClientPipeline.class);

    /** 上行白名单（protobuf 全名 → 默认实例）。其余类型一律按非法帧断开。 */
    static final Map<String, Message> ACCEPTED = Map.of(
            ClientTokenVerifyRequest.getDescriptor().getFullName(), ClientTokenVerifyRequest.getDefaultInstance(),
            ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance());

    private static final AtomicLong BAD_FRAMES = new AtomicLong();

    private ClientPipeline() {
    }

    public static void install(ChannelPipeline pipeline, SessionRegistry registry, ClientDispatcher dispatcher) {
        GateMetrics metrics = dispatcher.metrics();
        pipeline.addLast("clientFrameDecoder", new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> {
            // 非法帧由公网流量决定，采样记录；解码器随后关闭连接。
            metrics.invalidFrame(e.reason());
            metrics.disconnected(DisconnectReason.INVALID_FRAME);
            long n = BAD_FRAMES.getAndIncrement();
            if ((n & 0x3FF) == 0) {
                log.info("非法客户端帧（每 1024 次采样一条） reason={} peer={} total={}", e.reason(), ctx.channel().remoteAddress(), n + 1);
            } else {
                log.debug("非法客户端帧 reason={} peer={}", e.reason(), ctx.channel().remoteAddress());
            }
        }));
        pipeline.addLast("clientFrameEncoder", ClientFrameEncoder.INSTANCE);
        pipeline.addLast("clientSession", new ClientChannelHandler(registry, dispatcher));
    }
}
