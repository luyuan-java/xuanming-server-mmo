package com.game.robot.client;

import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 到 gate 的一条客户端 TCP 连接（robot 契约 §3）：
 * <ul>
 *   <li>帧格式复用 {@code xm-net} 的 {@link ClientFrameEncoder} / {@link ClientFrameDecoder}（typeName = 全名 + 1 字节终止符）；</li>
 *   <li>上行只有 {@code ClientTokenVerifyRequest}（首帧握手）与 {@code ClientRequest}；
 *       下行只接受 {@code ClientTokenVerifyResponse} 与 {@code MessageContent}，其它类型即非法帧、断开；</li>
 *   <li>{@code ClientRequest.id} 每条连接从 1 起自增；应答按 {@code (message_id, id)} 对上，推送（{@code id=0}）留在 {@link Inbox}。</li>
 * </ul>
 * 发送可在任意线程调用（Netty 保证写入串行）；等待方法阻塞调用线程，不得在 EventLoop 上调用。
 */
public final class GameConnection implements AutoCloseable {

    /**
     * 下行帧 len 上限。gate 下发没有长度上限（Go robot 也不查），64KB 只是客户端上行的限制；
     * 这里取 4MB 只为防御损坏的长度头，不把「服务端发了大帧」误判成非法。
     */
    static final int MAX_DOWNSTREAM_LEN = 4 * 1024 * 1024;

    static final Map<String, Message> ACCEPTED_DOWNSTREAM = Map.of(
            MessageContent.getDescriptor().getFullName(), MessageContent.getDefaultInstance(),
            ClientTokenVerifyResponse.getDescriptor().getFullName(), ClientTokenVerifyResponse.getDefaultInstance());

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);

    private final Channel channel;
    private final Inbox inbox;
    private final ConnectionHandler handler;
    private final int tipMessageId;
    private final AtomicLong nextRequestId = new AtomicLong();

    GameConnection(Channel channel, Inbox inbox, ConnectionHandler handler, int tipMessageId) {
        this.channel = channel;
        this.inbox = inbox;
        this.handler = handler;
        this.tipMessageId = tipMessageId;
    }

    /** 建立 TCP 连接（不握手）。 */
    static GameConnection open(EventLoopGroup group, String host, int port, Duration connectTimeout, int tipMessageId)
            throws RobotException {
        Inbox inbox = new Inbox();
        ConnectionHandler handler = new ConnectionHandler(inbox);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configure(ch.pipeline(), handler);
                    }
                });
        ChannelFuture connect = bootstrap.connect(host, port);
        if (!connect.awaitUninterruptibly(connectTimeout.toMillis() + 1000)) {
            connect.channel().close();
            throw new RobotException("连接 gate " + host + ":" + port + " 超时（" + connectTimeout.toMillis() + " ms）");
        }
        if (!connect.isSuccess()) {
            throw new RobotException("连接 gate " + host + ":" + port + " 失败：" + connect.cause(), connect.cause());
        }
        return new GameConnection(connect.channel(), inbox, handler, tipMessageId);
    }

    /** 客户端管线：解码 → 编码 → 入站处理。单独拿出来便于用 EmbeddedChannel 单测。 */
    static void configure(ChannelPipeline pipeline, ConnectionHandler handler) {
        pipeline.addLast("decoder", new ClientFrameDecoder(ACCEPTED_DOWNSTREAM, MAX_DOWNSTREAM_LEN,
                (ctx, e) -> handler.frameError(e)));
        pipeline.addLast("encoder", ClientFrameEncoder.INSTANCE);
        pipeline.addLast("handler", handler);
    }

    /**
     * 首帧握手（robot 契约 §4 步骤 A）：发 {@code ClientTokenVerifyRequest{payload, signature}}，
     * 连接上的第一帧必须是 {@code ClientTokenVerifyResponse{success=true}}。
     */
    public void verifyToken(byte[] payload, byte[] signature, Duration timeout) throws RobotException {
        handler.expectHandshake();
        write(ClientTokenVerifyRequest.newBuilder()
                .setPayload(ByteString.copyFrom(payload))
                .setSignature(ByteString.copyFrom(signature))
                .build());
        ClientTokenVerifyResponse response;
        try {
            response = handler.handshake().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RobotException("握手超时：" + timeout.toMillis() + " ms 内没有收到 ClientTokenVerifyResponse");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RobotException re ? re : new RobotException("握手失败：" + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("握手被中断", e);
        }
        if (!response.getSuccess()) {
            throw new RobotException("gate 拒绝令牌：success=false error=" + response.getError());
        }
    }

    /**
     * 发一条 {@code ClientRequest{id, message_id, body}}，不等应答。
     *
     * @return 本次请求的 id（从 1 起）
     */
    public long send(int messageId, Message body) throws RobotException {
        long id = nextRequestId.incrementAndGet();
        write(ClientRequest.newBuilder()
                .setId(id)
                .setMessageId(messageId)
                .setBody(body.toByteString())
                .build());
        return id;
    }

    /**
     * 发请求并等应答：{@code MessageContent.message_id} 与请求相同且 {@code id} 回显本次请求。
     * 信封带非 0 的 {@code error_message}（传输层失败：限频、服务不可用……）视为失败。
     */
    public <T extends Message> T call(int messageId, Message body, Parser<T> parser, Duration timeout)
            throws RobotException {
        int mark = inbox.size();
        long id = send(messageId, body);
        Optional<Received> reply = await(mark, r -> r.messageId() == messageId && r.requestId() == id, timeout);
        if (reply.isEmpty()) {
            throw new RobotException(timeout.toMillis() + " ms 内没有收到 message_id=" + messageId + " id=" + id
                    + " 的应答" + describeSince(mark));
        }
        Received received = reply.get();
        if (received.envelopeTipId() != 0) {
            throw new RobotException("message_id=" + messageId + " 的应答信封带传输层错误 tip="
                    + received.envelopeTipId() + "（业务错误应放在应答体的 error_message 里）");
        }
        return received.parse(parser);
    }

    /** 从 {@code fromIndex} 起等第一条满足条件的下行；超时或连接关闭时为空。 */
    public Optional<Received> await(int fromIndex, Predicate<Received> match, Duration timeout) throws RobotException {
        try {
            return inbox.await(fromIndex, match, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待下行被中断", e);
        }
    }

    public Inbox inbox() {
        return inbox;
    }

    public boolean isOpen() {
        return channel.isActive() && inbox.closedReason() == null;
    }

    /**
     * 超时说明：连接是否已关闭、这段时间收到的 23 tip。gate 调用后端失败时只推 23 {@code {1003}} 不回应答，
     * 不带上它排查时只看得到「超时」。
     */
    public String describeSince(int fromIndex) {
        StringBuilder out = new StringBuilder();
        String closed = inbox.closedReason();
        if (closed != null) {
            out.append("；").append(closed);
        }
        List<Integer> tips = new ArrayList<>();
        for (Received r : inbox.snapshot(fromIndex)) {
            if (r.messageId() == tipMessageId) {
                TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
                tips.add(tip == null ? -1 : tip.getId());
            }
        }
        if (!tips.isEmpty()) {
            out.append("；期间收到 23 tip ").append(tips);
        }
        return out.toString();
    }

    /** 主动断开（TCP 关闭就是权威的离线信号，robot 契约 §7.1）。幂等。 */
    @Override
    public void close() {
        inbox.markClosed("本端已主动断开");
        channel.close().awaitUninterruptibly(WRITE_TIMEOUT.toMillis());
    }

    private void write(Message frame) throws RobotException {
        String closed = inbox.closedReason();
        if (closed != null || !channel.isActive()) {
            throw new RobotException("无法发送 " + frame.getDescriptorForType().getName() + "："
                    + (closed != null ? closed : "连接未打开"));
        }
        ChannelFuture written = channel.writeAndFlush(frame);
        if (!written.awaitUninterruptibly(WRITE_TIMEOUT.toMillis())) {
            throw new RobotException("发送 " + frame.getDescriptorForType().getName() + " 超时");
        }
        if (!written.isSuccess()) {
            throw new RobotException("发送 " + frame.getDescriptorForType().getName() + " 失败：" + written.cause(),
                    written.cause());
        }
    }
}
