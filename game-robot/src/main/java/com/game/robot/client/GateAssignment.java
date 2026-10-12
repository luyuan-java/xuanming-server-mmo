package com.game.robot.client;

/**
 * {@code POST /api/assign-gate} 准入（{@code code=0}）时的结果（robot 契约 §2.1）。
 *
 * @param gateIp         gate 地址，非空
 * @param gatePort       gate 端口，1–65535
 * @param tokenPayload   原样作为 {@code ClientTokenVerifyRequest.payload}；为空时不握手（与 Go robot 一致）
 * @param tokenSignature 原样作为 {@code ClientTokenVerifyRequest.signature}
 * @param tokenDeadline  令牌到期（Unix 秒），只做记录
 */
public record GateAssignment(String gateIp, int gatePort, byte[] tokenPayload, byte[] tokenSignature,
                             long tokenDeadline) {

    public GateAssignment {
        tokenPayload = tokenPayload.clone();
        tokenSignature = tokenSignature.clone();
    }

    @Override
    public byte[] tokenPayload() {
        return tokenPayload.clone();
    }

    @Override
    public byte[] tokenSignature() {
        return tokenSignature.clone();
    }

    /** 令牌是签名凭据，不进日志：只打长度。 */
    @Override
    public String toString() {
        return "GateAssignment[" + gateIp + ":" + gatePort + ", payload=" + tokenPayload.length + "B, signature="
                + tokenSignature.length + "B, deadline=" + tokenDeadline + "]";
    }
}
