package com.game.robot.client;

import com.game.contract.MessageIdRegistry;
import com.game.proto.GateTokenPayload;
import com.game.proto.RedirectToGateNotify;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Optional;

/**
 * 124 {@code RedirectToGateNotify} 的内容（批次 5.4，zone-travel-spec §1.2 / §1.7）：服务端让客户端断开、改连另一台 gate。
 * 记录本身只是五个字段的原样拷贝；判据都是纯函数，不碰网络。
 *
 * <p>票据（{@code tokenPayload} + {@code tokenSignature}）必须<b>原字节</b>交给目标 gate 握手——这里用 {@link ByteString} 保存，
 * 不解析后重新序列化（重新序列化会改掉未知字段与字段次序，签名就对不上了）。票据只认证那一条 TCP，不转移登录会话：
 * 握手之后还要在新连接上完整重跑 48 → 26（{@code PlayerFlow.reenter}）。
 *
 * @param host           124 的 {@code target_ip}（目标 gate 的通告地址）
 * @param port           124 的 {@code target_port}
 * @param tokenPayload   序列化的 {@link GateTokenPayload}，原样作为 {@code ClientTokenVerifyRequest.payload}
 * @param tokenSignature 签名（64 字节小写 hex ASCII），原样作为 {@code ClientTokenVerifyRequest.signature}
 * @param tokenDeadline  票据到期（Unix 秒）= 票据的 {@code expire_timestamp}
 */
public record RedirectTarget(String host, int port, ByteString tokenPayload, ByteString tokenSignature, long tokenDeadline) {

    /** 124 所在的服务与方法（{@code message_id.txt} 的键是两者相连）。 */
    public static final String SERVICE = "SceneClientPlayerCommon";
    public static final String METHOD = "RedirectToGate";

    /** 124 的消息号：按「服务裸名 + 方法名」从契约注册表解析，不写死数字（号由 mmorpg 的生成器发）。 */
    public static int messageId(MessageIdRegistry registry) {
        return registry.requireId(SERVICE, METHOD);
    }

    /**
     * 解一条 124。只解包体，不判内容（内容是否可用看 {@link #problem}）。
     *
     * @param notify124 收到的那条 124（调用方已按消息号认出它）
     * @throws RobotException 包体不是合法的 {@code RedirectToGateNotify}
     */
    public static RedirectTarget parse(Received notify124) throws RobotException {
        RedirectToGateNotify notify = notify124.parse(RedirectToGateNotify.parser());
        return new RedirectTarget(notify.getTargetIp(), notify.getTargetPort(), notify.getTokenPayload(), notify.getTokenSignature(),
                notify.getTokenDeadline());
    }

    /**
     * 本地可判的毛病（不碰网络）：地址为空、端口不在 1–65535、票据为空、票据已到期（{@code token_deadline ≤ now}，没带也算）。
     * 比基线 Go robot 严：它把 {@code deadline = 0} 与空票据当作「空密钥直通档」放过；Java 版的密钥是必填的，124 一定带票据与到期时刻。
     *
     * @param nowEpochSeconds 当前 Unix 秒（调用方给，便于测试）
     * @return 没毛病为空
     */
    public Optional<String> problem(long nowEpochSeconds) {
        if (host == null || host.isBlank()) {
            return Optional.of("target_ip 为空");
        }
        if (port < 1 || port > 65535) {
            return Optional.of("target_port=" + Integer.toUnsignedString(port) + " 不在 1–65535");
        }
        if (tokenPayload.isEmpty() || tokenSignature.isEmpty()) {
            return Optional.of("票据为空（token_payload " + tokenPayload.size() + " B，token_signature " + tokenSignature.size() + " B）");
        }
        if (tokenDeadline <= nowEpochSeconds) {
            return Optional.of("票据已到期：token_deadline=" + tokenDeadline + " ≤ now=" + nowEpochSeconds
                    + "（连过去目标 gate 也只会回 token expired）");
        }
        return Optional.empty();
    }

    /**
     * 解出票据，供断言 {@code zone_id}（目标 gate 的 zone）、{@code player_id}（持票者）、{@code target_zone_id}（目标 zone）、
     * {@code gate_node_id}、{@code expire_timestamp}。只读：握手用的仍是 {@link #tokenPayload()} 的原字节。
     *
     * @throws RobotException 票据为空或不是合法的 {@code GateTokenPayload}
     */
    public GateTokenPayload ticket() throws RobotException {
        if (tokenPayload.isEmpty()) {
            throw new RobotException("124 的 token_payload 为空，没有票据可解");
        }
        try {
            return GateTokenPayload.parseFrom(tokenPayload);
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("124 的 token_payload 不是合法的 GateTokenPayload：" + e.getMessage(), e);
        }
    }

    /** 目标 gate 的端点。 */
    public GateEndpoint endpoint() {
        return new GateEndpoint(host, port);
    }

    /** 装成 assign-gate 的结果形状（五个字段一一对应），可以直接交给 {@link RobotClient#connect}。 */
    public GateAssignment toAssignment() {
        return new GateAssignment(host, port, tokenPayload.toByteArray(), tokenSignature.toByteArray(), tokenDeadline);
    }

    /** 票据是签名凭据，不进日志：只打长度。 */
    @Override
    public String toString() {
        return "RedirectTarget[" + host + ":" + port + ", payload=" + tokenPayload.size() + "B, signature=" + tokenSignature.size()
                + "B, deadline=" + tokenDeadline + "]";
    }
}
