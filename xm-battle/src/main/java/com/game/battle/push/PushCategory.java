package com.game.battle.push;

/**
 * 推送类别（基线 {@code battle_push_policy.h PushCategory}）：决定「没有活直连时」怎么办。调用点按出口函数固定类别，不按消息号查表
 * （哪条消息属于哪一类见 {@code BattleMessageIds.Notify#category()}）。
 */
public enum PushCategory {
    /** 直连建立之前必须送达的大厅公告：177 NotifyBattleAssigned / 143 NotifyBattleStart。指标标签 {@code lobby}。 */
    LOBBY_ANNOUNCEMENT("lobby"),
    /** 战斗帧：139 / 150 / 158 / 161 / 166，以及直连建立后的一切战斗推送。指标标签 {@code battle_frame}。 */
    BATTLE_FRAME("battle_frame");

    private final String label;

    PushCategory(String label) {
        this.label = label;
    }

    /** {@code xm_battle_pushes_total{category}} 的取值。 */
    public String label() {
        return label;
    }
}
