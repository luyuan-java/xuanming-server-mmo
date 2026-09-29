package com.game.net.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.Adler32;

/**
 * 客户端帧格式，与 mmorpg C++ {@code cpp/libs/engine/core/network/codec/codec.{h,cpp}}（ProtobufCodec）逐字节一致：
 *
 * <pre>
 * [int32 len][int32 nameLen][typeName: nameLen 字节，末字节为终止符][protobuf body][int32 adler32]
 * </pre>
 *
 * <ul>
 *   <li>整数全部大端；{@code len = 4 + nameLen + bodyLen + 4}，合法范围 {@code [MIN_LEN, maxLen]}。</li>
 *   <li>adler32 初值 1，覆盖 nameLen 字段、typeName、body。</li>
 *   <li>解码只剥 typeName 的最后 1 字节（Go robot 写空格，C++ 写 {@code \0}），其余按 protobuf 全名查类型。</li>
 *   <li>编码写全名 + {@code \0}。</li>
 * </ul>
 *
 * 纯函数，无状态，线程安全。
 */
public final class ClientFrames {

    /** 长度头字节数。 */
    public static final int HEADER_LEN = 4;
    /** len 的下限：nameLen(4) + 最短 typeName(2) + adler32(4)。 */
    public static final int MIN_LEN = 10;
    /** C++ kDefaultMaxMessageLen = 64 * 1024。 */
    public static final int DEFAULT_MAX_LEN = 64 * 1024;

    private ClientFrames() {
    }

    /** 编码一整帧（含长度头）。 */
    public static ByteBuf encode(ByteBufAllocator alloc, Message message) {
        byte[] name = (message.getDescriptorForType().getFullName() + '\0').getBytes(StandardCharsets.US_ASCII);
        byte[] body = message.toByteArray();
        int len = 4 + name.length + body.length + 4;
        ByteBuf out = alloc.buffer(HEADER_LEN + len);
        out.writeInt(len);
        int checksumFrom = out.writerIndex();
        out.writeInt(name.length);
        out.writeBytes(name);
        out.writeBytes(body);
        out.writeInt(adler32(out, checksumFrom, out.writerIndex() - checksumFrom));
        return out;
    }

    /**
     * 解码一帧的 len 之后的部分（调用方已校验 len 范围并确保 {@code frame} 恰好是 len 字节）。
     *
     * @param accepted 允许的类型：protobuf 全名 → 默认实例；不在表里的类型视为非法帧
     */
    public static Message decodeBody(ByteBuf frame, Map<String, Message> accepted) throws ClientFrameException {
        int len = frame.readableBytes();
        int start = frame.readerIndex();
        int expected = frame.getInt(start + len - 4);
        int actual = adler32(frame, start, len - 4);
        if (expected != actual) {
            throw new ClientFrameException(ClientFrameException.Reason.CHECKSUM, "adler32 不符");
        }
        int nameLen = frame.getInt(start);
        if (nameLen < 2 || nameLen > len - 8) {
            throw new ClientFrameException(ClientFrameException.Reason.INVALID_NAME_LEN, "nameLen=" + nameLen + " len=" + len);
        }
        // 只剥最后 1 字节，不按 C 字符串截断，也不 trim —— 与 C++ 行为一致。
        String typeName = frame.toString(start + 4, nameLen - 1, StandardCharsets.US_ASCII);
        Message prototype = accepted.get(typeName);
        if (prototype == null) {
            throw new ClientFrameException(ClientFrameException.Reason.UNKNOWN_TYPE, "类型不在白名单: " + typeName);
        }
        int bodyStart = start + 4 + nameLen;
        int bodyLen = len - 4 - nameLen - 4;
        try {
            byte[] body = new byte[bodyLen];
            frame.getBytes(bodyStart, body);
            return prototype.getParserForType().parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new ClientFrameException(ClientFrameException.Reason.PARSE, "消息体解析失败: " + typeName);
        }
    }

    static int adler32(ByteBuf buf, int from, int length) {
        Adler32 adler = new Adler32();
        adler.update(buf.nioBuffer(from, length));
        return (int) adler.getValue();
    }
}
