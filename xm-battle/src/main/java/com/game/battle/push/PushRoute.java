package com.game.battle.push;

/** 推送路由结论（基线 {@code battle_push_policy.h PushRoute}）。 */
public enum PushRoute {
    /** 经该玩家的活直连直发。指标标签 {@code direct}。 */
    DIRECT("direct"),
    /** 经 gate 回落到大厅会话（Java：{@code LobbyAnnouncer} → {@code PlayerPushes.pushAllToPlayer}）。指标标签 {@code via_gate}。 */
    VIA_GATE("via_gate"),
    /** 不发（调用方负责计数与采样日志）。指标标签 {@code dropped}。 */
    DROP("dropped");

    private final String label;

    PushRoute(String label) {
        this.label = label;
    }

    /** {@code xm_battle_pushes_total{route}} 的取值。 */
    public String label() {
        return label;
    }
}
