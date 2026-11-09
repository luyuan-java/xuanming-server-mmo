package com.game.robot.client;

import com.game.net.client.ClientFrameException;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一条连接的入站处理：握手应答交给 {@link #handshake()}，其余 {@code MessageContent} 全部记进 {@link Inbox}。
 * 每条连接一个实例（非 {@code @Sharable}），只在该连接的 EventLoop 上被调用；{@link #expectHandshake()} 由场景线程在
 * 写出握手帧<b>之前</b>调用（volatile 保证 I/O 线程看得见）。
 *
 * <p>robot 契约 §4 步骤 A：发出 {@code ClientTokenVerifyRequest} 后，连接上的第一帧必须是 {@code ClientTokenVerifyResponse}。
 */
final class ConnectionHandler extends SimpleChannelInboundHandler<Message> {

    private final Inbox inbox;
    private final CompletableFuture<ClientTokenVerifyResponse> handshake = new CompletableFuture<>();
    private volatile boolean awaitingHandshake;
    /** 心跳归本连接的 EventLoop；重定向新建 handler，验签前没有定时任务。 */
    private ScheduledFuture<?> heartbeat;
    private long heartbeatSequence;

    ConnectionHandler(Inbox inbox) {
        this.inbox = inbox;
    }

    CompletableFuture<ClientTokenVerifyResponse> handshake() {
        return handshake;
    }

    void expectHandshake() {
        awaitingHandshake = true;
    }

    /** 解码器发现非法下行帧（随后连接被关闭）：记下原因。 */
    void frameError(ClientFrameException e) {
        String reason = "收到非法下行帧（" + e.getMessage() + "），连接已断开";
        inbox.markClosed(reason);
        handshake.completeExceptionally(new RobotException(reason));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        long now = System.nanoTime();
        if (msg instanceof ClientTokenVerifyResponse response) {
            if (awaitingHandshake && !handshake.isDone()) {
                awaitingHandshake = false;
                if (response.getSuccess() && response.getHeartbeatIntervalMs() != 0) {
                    startHeartbeat(ctx, Integer.toUnsignedLong(response.getHeartbeatIntervalMs()));
                }
                handshake.complete(response);
            } else {
                inbox.markClosed("握手之外又收到 ClientTokenVerifyResponse（契约只允许作为握手应答出现）");
                ctx.close();
            }
            return;
        }
        if (msg instanceof ClientHeartbeatResponse) {
            if (awaitingHandshake && !handshake.isDone()) {
                handshake.completeExceptionally(new RobotException("握手前收到心跳应答"));
                ctx.close();
            }
            // 心跳不进业务收件箱，不能干扰登录阶段等待的业务应答。
            return;
        }
        MessageContent content = (MessageContent) msg;
        if (awaitingHandshake && !handshake.isDone()) {
            awaitingHandshake = false;
            handshake.completeExceptionally(new RobotException(
                    "握手后的第一帧不是 ClientTokenVerifyResponse，而是 MessageContent{message_id="
                            + content.getMessageId() + "}"));
        }
        inbox.add(content, now);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        stopHeartbeat();
        inbox.markClosed("连接已被关闭");
        handshake.completeExceptionally(new RobotException("握手完成前连接已被关闭"));
        super.channelInactive(ctx);
    }

    private void startHeartbeat(ChannelHandlerContext ctx, long intervalMillis) {
        sendHeartbeat(ctx);
        if (!ctx.channel().isActive()) return;
        // 固定延迟避免 EventLoop 卡顿恢复后补发一串过期节拍。
        heartbeat = ctx.executor().scheduleWithFixedDelay(() -> sendHeartbeat(ctx),
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(ClientHeartbeatRequest.newBuilder().setSeq(++heartbeatSequence).build())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stopHeartbeat();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        inbox.markClosed("连接异常：" + cause);
        handshake.completeExceptionally(new RobotException("连接异常：" + cause, cause));
        ctx.close();
    }
}
