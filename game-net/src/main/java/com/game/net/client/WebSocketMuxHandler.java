package com.game.net.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 同端口原生 TCP / RFC 6455 WebSocket 自动分流处理器（对齐 {@code minigame-websocket-gate.md} W1 / W4）。
 *
 * <p>安装在 {@link ClientFrameDecoder} 之前：
 * <ul>
 *   <li>首字节非 {@code 'G'}（0x47）：原生 TCP 帧（64 KiB 上限下长度首字节恒为 0x00），立即自移除，对原生连接零运行时开销；</li>
 *   <li>首字节为 {@code 'G'}：按 RFC 6455 解析 HTTP 升级请求（头上限 8 KiB），通过后回 101 并替换为
 *       {@link WebSocketBinaryCodec}；版本非 13 回 426、其余非法回 400 并短延迟强关。</li>
 * </ul>
 */
public final class WebSocketMuxHandler extends ByteToMessageDecoder {

    public static final int MAX_HANDSHAKE_BYTES = 8192;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = ClientFrames.DEFAULT_MAX_LEN + ClientFrames.HEADER_LEN + 64;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final byte[] RESP_400 = (
            "HTTP/1.1 400 Bad Request\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESP_426 = (
            "HTTP/1.1 426 Upgrade Required\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Content-Length: 0\r\n"
            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

    private final int maxPayloadBytes;
    private final WebSocketObserver observer;
    private boolean decidedWebSocket;
    private boolean rejected;

    public WebSocketMuxHandler() {
        this(DEFAULT_MAX_PAYLOAD_BYTES, WebSocketObserver.NOOP);
    }

    public WebSocketMuxHandler(WebSocketObserver observer) {
        this(DEFAULT_MAX_PAYLOAD_BYTES, observer);
    }

    public WebSocketMuxHandler(int maxPayloadBytes, WebSocketObserver observer) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** RFC 6455 §4.2.2 {@code Sec-WebSocket-Accept = Base64(SHA-1(key + GUID))}。 */
    public static String computeAccept(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-1", e);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (rejected || !ctx.channel().isActive()) {
            in.skipBytes(in.readableBytes());
            return;
        }
        if (!in.isReadable()) {
            return;
        }
        if (!decidedWebSocket) {
            if (in.getUnsignedByte(in.readerIndex()) != 'G') {
                // 原生 TCP 连接：自移除，ByteToMessageDecoder.handlerRemoved 自动把剩余字节交给下游 ClientFrameDecoder
                ctx.pipeline().remove(this);
                return;
            }
            decidedWebSocket = true;
        }

        int headerEnd = findHeaderEnd(in);
        if (headerEnd < 0) {
            if (in.readableBytes() > MAX_HANDSHAKE_BYTES) {
                reject(ctx, in, RESP_400, "header_too_large");
            }
            return;
        }
        int headerLen = (headerEnd - in.readerIndex()) + 4;
        if (headerLen > MAX_HANDSHAKE_BYTES) {
            reject(ctx, in, RESP_400, "header_too_large");
            return;
        }
        byte[] raw = new byte[headerLen];
        in.readBytes(raw);
        String text = new String(raw, StandardCharsets.ISO_8859_1);

        HandshakeResult parsed = parseUpgrade(text);
        if (!parsed.ok()) {
            reject(ctx, in, parsed.versionMismatch() ? RESP_426 : RESP_400, parsed.error());
            return;
        }

        observer.onHandshakeAccepted();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + computeAccept(parsed.key()) + "\r\n\r\n";
        ctx.writeAndFlush(Unpooled.copiedBuffer(response, StandardCharsets.US_ASCII));

        // 替换为二进制帧桥：若本 TCP 段已紧跟首个 WebSocket 帧，handlerRemoved 会将剩余字节直接投给 wsBinaryCodec
        ctx.pipeline().addAfter(ctx.name(), "wsBinaryCodec", new WebSocketBinaryCodec(maxPayloadBytes, observer));
        ctx.pipeline().remove(this);
    }

    private void reject(ChannelHandlerContext ctx, ByteBuf in, byte[] httpBytes, String reason) {
        rejected = true;
        in.skipBytes(in.readableBytes());
        observer.onHandshakeRejected(reason);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(httpBytes)).addListener(ChannelFutureListener.CLOSE);
        WebSocketBinaryCodec.scheduleShortForceClose(ctx);
    }

    private static int findHeaderEnd(ByteBuf in) {
        int start = in.readerIndex();
        int limit = Math.min(in.writerIndex(), start + MAX_HANDSHAKE_BYTES);
        for (int i = start; i + 3 < limit; i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n'
                    && in.getByte(i + 2) == '\r' && in.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private record HandshakeResult(boolean ok, boolean versionMismatch, String key, String error) {
        static HandshakeResult accepted(String key) {
            return new HandshakeResult(true, false, key, null);
        }

        static HandshakeResult badRequest(String error) {
            return new HandshakeResult(false, false, null, error);
        }

        static HandshakeResult badVersion() {
            return new HandshakeResult(false, true, null, "unsupported_version");
        }
    }

    private static HandshakeResult parseUpgrade(String raw) {
        String trimmed = raw.substring(0, raw.length() - 4);
        String[] lines = trimmed.split("\r\n", -1);
        if (lines.length < 2) {
            return HandshakeResult.badRequest("empty_headers");
        }
        String[] reqLine = lines[0].split(" ");
        if (reqLine.length != 3 || !"GET".equals(reqLine[0]) || !reqLine[1].startsWith("/")
                || !"HTTP/1.1".equals(reqLine[2])) {
            return HandshakeResult.badRequest("bad_request_line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                return HandshakeResult.badRequest("malformed_header");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        if (!hasToken(headers.get("upgrade"), "websocket")) {
            return HandshakeResult.badRequest("missing_upgrade_websocket");
        }
        if (!hasToken(headers.get("connection"), "upgrade")) {
            return HandshakeResult.badRequest("missing_connection_upgrade");
        }
        String version = headers.get("sec-websocket-version");
        if (!"13".equals(version)) {
            return HandshakeResult.badVersion();
        }
        String key = headers.get("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            return HandshakeResult.badRequest("missing_key");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length != 16) {
                return HandshakeResult.badRequest("invalid_key_length");
            }
        } catch (IllegalArgumentException e) {
            return HandshakeResult.badRequest("invalid_key_base64");
        }
        return HandshakeResult.accepted(key);
    }

    private static boolean hasToken(String headerValue, String expectedLower) {
        if (headerValue == null) {
            return false;
        }
        for (String part : headerValue.split(",")) {
            if (expectedLower.equals(part.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
