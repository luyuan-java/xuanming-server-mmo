package com.game.api.auth;

import com.game.api.ClientMessageService;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.ClientCall;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.dubbo.rpc.Invocation;

/**
 * 构造 gate → 在线服务 {@link ClientMessageService} 的规范化 Ed25519 签名载荷（带长度前缀，防拼接注入）。
 * 覆盖方法名、时间戳、完整 {@link SessionContext} 身份字段以及调用级关键元数据，确保验证方无法篡改或伪造会话身份。
 */
final class GateSessionPayload {

    private static final String VERSION = "gate-ed25519-v1";

    private GateSessionPayload() {
    }

    static boolean isClientMessageService(String serviceInterface) {
        return ClientMessageService.class.getName().equals(serviceInterface);
    }

    static byte[] build(Invocation invocation, long timestampSeconds) {
        String method = invocation.getMethodName() == null ? "" : invocation.getMethodName();
        Object[] args = invocation.getArguments();
        Object first = (args != null && args.length > 0) ? args[0] : null;
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(160);
            DataOutputStream out = new DataOutputStream(raw);
            writeString(out, VERSION);
            writeString(out, method);
            out.writeLong(timestampSeconds);
            if (first instanceof ClientCall call) {
                out.writeByte(1);
                writeSession(out, call.hasSession() ? call.getSession() : SessionContext.getDefaultInstance());
                out.writeInt(call.getMessageId());
                out.writeLong(call.getRequestId());
            } else if (first instanceof SessionClosed closed) {
                out.writeByte(2);
                writeSession(out, closed.hasSession() ? closed.getSession() : SessionContext.getDefaultInstance());
                out.writeBoolean(closed.getVoluntary());
            } else if (first instanceof AbandonedEnter abandoned) {
                out.writeByte(3);
                writeSession(out, abandoned.hasSession() ? abandoned.getSession() : SessionContext.getDefaultInstance());
                out.writeLong(abandoned.getPlayerId());
                out.writeLong(abandoned.getOwnerEpoch());
            } else {
                out.writeByte(0);
            }
            out.flush();
            return raw.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("序列化 gate 会话签名载荷失败", impossible);
        }
    }

    private static void writeSession(DataOutputStream out, SessionContext s) throws IOException {
        out.writeInt(s.getGateNodeId());
        writeString(out, s.getGateInstanceId());
        out.writeInt(s.getSessionId());
        out.writeInt(s.getZoneId());
        writeString(out, s.getAccount());
        out.writeLong(s.getPlayerId());
        writeString(out, s.getClientIp());
        out.writeLong(s.getTicketPlayerId());
        out.writeInt(s.getTicketTargetZoneId());
        out.writeLong(s.getOwnerRevision());
        byte[] digest = s.getTicketAccountDigest().toByteArray();
        out.writeInt(digest.length);
        out.write(digest);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
