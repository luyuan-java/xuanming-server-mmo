package com.game.robot.client;

/**
 * 一条客户端连接实际连的 gate 端点（批次 5.4）。跨 zone 场景用它核对「确实从不同的区进来」「跟随 124 之后换了 gate」，
 * 也是结果行里 {@code home_gate / visit_gate / back_gate} 的取值。
 *
 * @param host gate 地址（assign-gate 的 {@code gate_ip} 或 124 的 {@code target_ip}，原样）
 * @param port gate 端口
 */
public record GateEndpoint(String host, int port) {

    /** assign-gate 给的那个端点。 */
    public static GateEndpoint of(GateAssignment gate) {
        return new GateEndpoint(gate.gateIp(), gate.gatePort());
    }

    /** {@code host:port}（结果行与报告里直接用）。 */
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
