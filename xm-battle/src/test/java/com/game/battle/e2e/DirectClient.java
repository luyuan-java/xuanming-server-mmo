package com.game.battle.e2e;

import com.game.net.client.ClientFrameException;
import com.game.net.client.ClientFrames;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 端到端测试用的直连客户端：阻塞 socket + 一条读线程，按线上字节收发（帧格式同 robot，xm-net {@link ClientFrames}）。读线程把每个下行帧连同
 * 到达时刻放进收件箱，连接被对端关闭时放一个 {@link Frame#closed()} 标记（区分 FIN 与 RST），所以「一段时间内什么都没收到」可以安全地断言，
 * 不会把半帧读坏。
 */
final class DirectClient implements AutoCloseable {

    /** 下行只有两种类型。 */
    private static final Map<String, Message> DOWNSTREAM = Map.of(
            BattleTokenVerifyResponse.getDescriptor().getFullName(), BattleTokenVerifyResponse.getDefaultInstance(),
            MessageContent.getDescriptor().getFullName(), MessageContent.getDefaultInstance());

    /** 收件箱里的一项：一个下行帧，或连接关闭标记（{@code message == null}）。 */
    record Frame(Message message, long atNanos, String closedBy) {

        boolean closed() {
            return message == null;
        }

        /** verify-ok:battle / verify-fail:错误串 / push:号 / reply:号 / error:号:tip / closed:fin|reset。 */
        String label() {
            if (message == null) {
                return "closed:" + closedBy;
            }
            if (message instanceof BattleTokenVerifyResponse r) {
                return r.getSuccess() ? "verify-ok:" + r.getBattleId() : "verify-fail:" + r.getError();
            }
            MessageContent c = (MessageContent) message;
            if (c.hasErrorMessage()) {
                return "error:" + c.getMessageId() + ":" + c.getErrorMessage().getId();
            }
            return (c.getId() == 0 ? "push:" : "reply:") + c.getMessageId();
        }

        MessageContent content() {
            if (!(message instanceof MessageContent c)) {
                throw new AssertionError("期望 MessageContent，实际 " + label());
            }
            return c;
        }

        BattleTokenVerifyResponse verify() {
            if (!(message instanceof BattleTokenVerifyResponse r)) {
                throw new AssertionError("期望握手应答，实际 " + label());
            }
            return r;
        }
    }

    private final Socket socket = new Socket();
    private final OutputStream out;
    private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
    private final Thread reader;
    private long nextRequestId = 1;

    private DirectClient(InetSocketAddress address) throws IOException {
        socket.connect(address, 3000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        reader = Thread.ofPlatform().daemon().name("e2e-direct-reader").start(() -> readLoop(in));
    }

    static DirectClient connect(String host, int port) throws IOException {
        return new DirectClient(new InetSocketAddress(host, port));
    }

    /** 连接并用分配包里的票握手（不读应答）。 */
    static DirectClient connectWith(BattleAssignedS2C assignment) throws IOException {
        DirectClient client = connect(assignment.getHost(), assignment.getPort());
        client.send(verifyRequest(assignment));
        return client;
    }

    static BattleTokenVerifyRequest verifyRequest(BattleAssignedS2C assignment) {
        return BattleTokenVerifyRequest.newBuilder()
                .setPayload(assignment.getTokenPayload())
                .setSignature(assignment.getTokenSignature())
                .build();
    }

    private void readLoop(DataInputStream in) {
        String closedBy;
        try {
            while (true) {
                int len = in.readInt();
                byte[] body = in.readNBytes(len);
                if (body.length < len) {
                    throw new EOFException("半帧");
                }
                long at = System.nanoTime();
                try {
                    inbox.add(new Frame(ClientFrames.decodeBody(Unpooled.wrappedBuffer(body), DOWNSTREAM), at, null));
                } catch (ClientFrameException e) {
                    inbox.add(new Frame(null, at, "invalid-frame:" + e.getMessage()));
                    return;
                }
            }
        } catch (EOFException e) {
            closedBy = "fin";
        } catch (IOException e) {
            closedBy = socket.isClosed() ? "local" : "reset";
        }
        inbox.add(new Frame(null, System.nanoTime(), closedBy));
    }

    // ---------------------------------------------------------------- 上行

    void send(Message message) throws IOException {
        ByteBuf buf = ClientFrames.encode(UnpooledByteBufAllocator.DEFAULT, message);
        try {
            sendRaw(ByteBufUtil.getBytes(buf));
        } finally {
            buf.release();
        }
    }

    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** 发一条 {@code ClientRequest}，返回它的 id（自增，从 1 开始）。 */
    long request(int messageId, MessageLite body) throws IOException {
        long id = nextRequestId++;
        send(ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(body.toByteString()).build());
        return id;
    }

    // ---------------------------------------------------------------- 下行

    /** 下一项（帧或关闭标记）；{@code timeout} 内没有就断言失败。 */
    Frame next(Duration timeout) throws InterruptedException {
        Frame frame = inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new AssertionError(timeout + " 内没有收到任何下行帧");
        }
        return frame;
    }

    Frame next() throws InterruptedException {
        return next(Duration.ofSeconds(5));
    }

    /** 下一项必须是 {@code label}。 */
    Frame expect(String label) throws InterruptedException {
        return expect(label, Duration.ofSeconds(5));
    }

    Frame expect(String label, Duration timeout) throws InterruptedException {
        Frame frame = next(timeout);
        if (!frame.label().equals(label)) {
            throw new AssertionError("期望 " + label + "，实际 " + frame.label());
        }
        return frame;
    }

    /** 下一项必须是关闭标记（不再有任何帧）；返回 fin / reset。 */
    String expectClosed(Duration timeout) throws InterruptedException {
        Frame frame = next(timeout);
        if (!frame.closed()) {
            throw new AssertionError("期望连接被关闭，实际又收到 " + frame.label());
        }
        return frame.closedBy();
    }

    /** {@code window} 内什么都没收到（也没有被关闭）。 */
    void expectSilence(Duration window) throws InterruptedException {
        Frame frame = inbox.poll(window.toMillis(), TimeUnit.MILLISECONDS);
        if (frame != null) {
            throw new AssertionError(window + " 内不该收到任何东西，实际收到 " + frame.label());
        }
    }

    /** 收集到连接被关闭为止的全部项（含最后的关闭标记）。 */
    List<Frame> untilClosed(Duration total) throws InterruptedException {
        long deadline = System.nanoTime() + total.toNanos();
        List<Frame> frames = new ArrayList<>();
        while (true) {
            long left = deadline - System.nanoTime();
            Frame frame = left > 0 ? inbox.poll(left, TimeUnit.NANOSECONDS) : null;
            if (frame == null) {
                throw new AssertionError(total + " 内连接没有关闭，已收到 " + frames.stream().map(Frame::label).toList());
            }
            frames.add(frame);
            if (frame.closed()) {
                return frames;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
