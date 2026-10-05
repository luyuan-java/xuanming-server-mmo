package com.game.battle.admission;

/** 建房准入闸的阶段（基线 {@code battle_admission_gate.h AdmissionPhase}）：只朝一个方向走，{@link #CLOSED} 是终态。 */
public enum AdmissionPhase {
    /** 启动还没完成：拒绝建房（Java 先开闸后进目录，这个阶段 match 本来就找不到本节点，§7.11）。 */
    NOT_STARTED("not_started", 0),
    /** 正常接房间。 */
    OPEN("open", 1),
    /** 停机已开始（或节点号租约丢失），拒绝建房（终态）。 */
    CLOSED("closed", 2);

    private final String wireName;
    private final int gaugeValue;

    AdmissionPhase(String wireName, int gaugeValue) {
        this.wireName = wireName;
        this.gaugeValue = gaugeValue;
    }

    /** not_started / open / closed（基线 {@code ToString}；日志与 CreateBattleResult.reason 用）。 */
    public String wireName() {
        return wireName;
    }

    /** {@code xm_battle_admission_phase} 的取值：0 / 1 / 2。 */
    public int gaugeValue() {
        return gaugeValue;
    }
}
