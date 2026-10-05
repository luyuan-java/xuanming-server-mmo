package com.game.robot.client;

import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.MessageContent;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 客户端到 battle 节点的一条直连（battle-node-spec §3、§13.8；基线 robot {@code bdc.go} / {@code robot/pkg/client.go:240-265}）：
 * <ul>
 *   <li>帧格式与 gate 完全相同，复用 {@code xm-net} 的 {@link ClientFrameEncoder} / {@link ClientFrameDecoder}；上行只有
 *       {@code BattleTokenVerifyRequest}（首帧握手）与 {@code ClientRequest}，下行只接受 {@code BattleTokenVerifyResponse} 与 {@code MessageContent}；</li>
 *   <li>{@link #verify}：发出握手后读到的<b>第一帧</b>必须是 {@code BattleTokenVerifyResponse}（同基线 {@code VerifyBattleToken} 只读一个包）；
 *       拒绝不抛异常，交给调用方核对逐字的拒绝串；</li>
 *   <li>{@link #handshake}：参战票握手成功后立刻发一条 140 补拉状态（{@code bdc.go:178-189}）；观众票不发（服务端推 161）；</li>
 *   <li>全部下行（含关闭标记）按序进 {@link BattleInbox}：开着半关闭（{@code ALLOW_HALF_CLOSURE}），对端 FIN 记 {@value BattleFrame#FIN}、RST 记
 *       {@value BattleFrame#RESET}——「终局包之后是 FIN 而不是 RST」是线上顺序的一部分（battle-node-spec §3.6、R3）；</li>
 *   <li>{@link #sendRaw} 可以发任意字节（坏校验和等负面用例）。</li>
 * </ul>
 * 发送可在任意线程调用；等待方法阻塞调用线程，不得在 EventLoop 上调用。
 */
public final class BattleDirectConnection implements AutoCloseable {

    /** 下行帧 len 上限：只为防御损坏的长度头（battle 下发没有上限约定，同 {@link GameConnection}）。 */
    static final int MAX_DOWNSTREAM_LEN = 4 * 1024 * 1024;

    static final Map<String, Message> ACCEPTED_DOWNSTREAM = Map.of(
            BattleTokenVerifyResponse.getDescriptor().getFullName(), BattleTokenVerifyResponse.getDefaultInstance(),
            MessageContent.getDescriptor().getFullName(), MessageContent.getDefaultInstance());

    /** 建连 + 握手的总预算（基线 {@code bdc.go:51}；Unity 也是建连 10 s）。 */
    public static final Duration CONNECT_AND_HANDSHAKE_BUDGET = Duration.ofSeconds(10);

    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);

    private final Channel channel;
    private final BattleInbox inbox;
    private final String endpoint;
    private final AtomicLong nextRequestId = new AtomicLong();

    BattleDirectConnection(Channel channel, BattleInbox inbox, String endpoint) {
        this.channel = channel;
        this.inbox = inbox;
        this.endpoint = endpoint;
    }

    /** 建 TCP 连接（不握手）。 */
    public static BattleDirectConnection open(EventLoopGroup group, String host, int port, Duration connectTimeout)
            throws RobotException {
        BattleInbox inbox = new BattleInbox();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        configure(ch.pipeline(), inbox);
                    }
                });
        String endpoint = host + ":" + port;
        ChannelFuture connect = bootstrap.connect(host, port);
        if (!connect.awaitUninterruptibly(connectTimeout.toMillis() + 1000)) {
            connect.channel().close();
            throw new RobotException("连接 battle 直连面 " + endpoint + " 超时（" + connectTimeout.toMillis() + " ms）");
        }
        if (!connect.isSuccess()) {
            throw new RobotException("连接 battle 直连面 " + endpoint + " 失败（xm-battle 没起，或票据里的地址不可达）：" + connect.cause(),
                    connect.cause());
        }
        return new BattleDirectConnection(connect.channel(), inbox, endpoint);
    }

    /** 客户端管线：解码 → 编码 → 入站处理。单独拿出来便于用 EmbeddedChannel 单测。 */
    static void configure(ChannelPipeline pipeline, BattleInbox inbox) {
        pipeline.addLast("decoder", new ClientFrameDecoder(ACCEPTED_DOWNSTREAM, MAX_DOWNSTREAM_LEN,
                (ctx, e) -> inbox.markClosed("invalid-frame:" + e.getMessage(), System.nanoTime())));
        pipeline.addLast("encoder", ClientFrameEncoder.INSTANCE);
        pipeline.addLast("handler", new Handler(inbox));
    }

    /** 握手结局：应答与（参战者）补拉 140 的请求 id（没发为 0）。 */
    public record Handshake(BattleTokenVerifyResponse response, long stateRequestId) {

        public boolean success() {
            return response.getSuccess();
        }
    }

    /**
     * 首帧握手：发 {@code BattleTokenVerifyRequest{payload, signature}}，等这之后的第一帧，它必须是 {@code BattleTokenVerifyResponse}。
     * 拒绝（{@code success = false}）照常返回，由调用方核对拒绝串。
     *
     * @throws RobotException 超时、连接被关、或第一帧不是握手应答
     */
    public BattleTokenVerifyResponse verify(ByteString payload, ByteString signature, Duration timeout) throws RobotException {
        int mark = inbox.size();
        write(BattleTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(signature).build());
        BattleFrame first = await(mark, f -> true, timeout)
                .orElseThrow(() -> new RobotException(timeout.toMillis() + " ms 内没有收到 BattleTokenVerifyResponse（" + endpoint + "）"));
        if (!first.isVerify()) {
            throw new RobotException("握手后的第一帧不是 BattleTokenVerifyResponse，而是 " + first.label()
                    + "（battle-node-spec §3.4 R1：握手应答必须是直连上的第一帧）");
        }
        return first.verify();
    }

    /**
     * 用分配包里的票握手；参战票成功后立刻发一条 140 补拉（不等应答，应答进收件箱，id 见返回值）。
     *
     * @param getBattleStateId 140 的消息号（从契约解析）
     */
    public Handshake handshake(BattleAssignedS2C assignment, int getBattleStateId, Duration timeout) throws RobotException {
        BattleTokenVerifyResponse response = verify(assignment.getTokenPayload(), assignment.getTokenSignature(), timeout);
        long stateRequestId = 0;
        if (response.getSuccess() && assignment.getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT) {
            stateRequestId = request(getBattleStateId, GetBattleStateRequest.newBuilder().setBattleId(assignment.getBattleId()).build());
        }
        return new Handshake(response, stateRequestId);
    }

    /** 发一条 {@code ClientRequest{id, message_id, body}}，不等应答；返回 id（每条连接从 1 起自增）。 */
    public long request(int messageId, Message body) throws RobotException {
        long id = nextRequestId.incrementAndGet();
        write(ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(body.toByteString()).build());
        return id;
    }

    /** 发一条现成的 {@code ClientRequest}（id 由调用方定，例如凑体积的请求）。 */
    public void send(Message frame) throws RobotException {
        write(frame);
    }

    /** 发任意字节（不经编码器：负面用例里的坏校验和、半帧等）。 */
    public void sendRaw(byte[] bytes) throws RobotException {
        ensureOpen("原始字节");
        ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        awaitWrite(written, "原始字节");
    }

    /** 发请求并等它的应答（{@code message_id} 与 {@code id} 都对上；信封错误也算应答，由调用方判定）。 */
    public BattleFrame call(int messageId, Message body, Duration timeout) throws RobotException {
        int mark = inbox.size();
        long id = request(messageId, body);
        return await(mark, f -> f.content() != null && f.messageId() == messageId && f.requestId() == id, timeout)
                .orElseThrow(() -> new RobotException(timeout.toMillis() + " ms 内没有收到 message_id=" + messageId + " id=" + id
                        + " 的应答；此后收到 " + inbox.labelsSince(mark)));
    }

    /** 发请求、等应答并解析应答体（信封错误即失败）。 */
    public <T extends Message> T call(int messageId, Message body, Parser<T> parser, Duration timeout) throws RobotException {
        BattleFrame reply = call(messageId, body, timeout);
        if (reply.isEnvelopeError()) {
            throw new RobotException("message_id=" + messageId + " 的应答是信封错误 tip=" + reply.envelopeTipId());
        }
        return reply.parse(parser);
    }

    /** 从 {@code fromIndex} 起等第一项满足条件的记录（关闭标记也参与匹配）；超时或已关闭且没有匹配时为空。 */
    public Optional<BattleFrame> await(int fromIndex, Predicate<BattleFrame> match, Duration timeout) throws RobotException {
        try {
            return inbox.await(fromIndex, match, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待 battle 直连下行被中断", e);
        }
    }

    /** 等连接被关闭；返回关闭原因（{@value BattleFrame#FIN} / {@value BattleFrame#RESET} / …），超时为空。 */
    public Optional<String> awaitClosed(Duration timeout) throws RobotException {
        return await(0, BattleFrame::isClosed, timeout).map(BattleFrame::closedBy);
    }

    public BattleInbox inbox() {
        return inbox;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean isOpen() {
        return channel.isActive() && inbox.closedBy() == null;
    }

    /** 本端主动关闭（幂等）。 */
    @Override
    public void close() {
        inbox.markClosed(BattleFrame.LOCAL, System.nanoTime());
        channel.close().awaitUninterruptibly(WRITE_TIMEOUT.toMillis());
    }

    private void write(Message frame) throws RobotException {
        String what = frame.getDescriptorForType().getName();
        ensureOpen(what);
        awaitWrite(channel.writeAndFlush(frame), what);
    }

    private void ensureOpen(String what) throws RobotException {
        String closed = inbox.closedBy();
        if (closed != null || !channel.isActive()) {
            throw new RobotException("无法在 battle 直连上发送 " + what + "：" + (closed != null ? "连接已关闭（" + closed + "）" : "连接未打开"));
        }
    }

    private static void awaitWrite(ChannelFuture written, String what) throws RobotException {
        if (!written.awaitUninterruptibly(WRITE_TIMEOUT.toMillis())) {
            throw new RobotException("在 battle 直连上发送 " + what + " 超时");
        }
        if (!written.isSuccess()) {
            throw new RobotException("在 battle 直连上发送 " + what + " 失败：" + written.cause(), written.cause());
        }
    }

    /**
     * 入站处理：握手应答与信封按序记账；对端 FIN（半关闭开着时是 {@link ChannelInputShutdownEvent}）记 fin 并关掉本端；读异常（RST）记 reset。
     * 每条连接一个实例，只在该连接的 EventLoop 上被调用。
     */
    static final class Handler extends SimpleChannelInboundHandler<Message> {

        private final BattleInbox inbox;

        Handler(BattleInbox inbox) {
            this.inbox = inbox;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
            long now = System.nanoTime();
            if (msg instanceof BattleTokenVerifyResponse verify) {
                inbox.addVerify(verify, now);
            } else {
                inbox.addContent((MessageContent) msg, now);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ChannelInputShutdownEvent) {
                inbox.markClosed(BattleFrame.FIN, System.nanoTime());
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            inbox.markClosed(cause instanceof IOException ? BattleFrame.RESET : "error:" + cause, System.nanoTime());
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            inbox.markClosed(BattleFrame.CLOSED, System.nanoTime());
            super.channelInactive(ctx);
        }
    }
}
