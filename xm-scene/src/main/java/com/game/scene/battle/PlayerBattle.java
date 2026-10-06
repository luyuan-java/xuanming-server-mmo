package com.game.scene.battle;

/**
 * 挂在场景玩家上的回合制战斗运行态（scene-battle-spec §7.4）：冻结、进场恢复状态、结算应用中的不重入标记。不持久化，随实例生灭。
 * 只在场景逻辑线程上读写；改冻结只经 {@code PlayerBattleService}。
 */
public final class PlayerBattle {

    /** 进场恢复（§7.8，D20）：恢复读在途 / 已就绪 / 读失败或有延后的待结算记录（由 reaper 重跑）。 */
    public enum Recovery {
        PENDING,
        READY,
        RETRY
    }

    private BattleFreeze freeze;
    /** 新实例在进场恢复读回来之前是 PENDING（fail-closed：没接战斗服务的装配里一直是它，结算一律延后）。 */
    private Recovery recovery = Recovery.PENDING;
    private boolean applying;

    /** 当前冻结；null = 没有在途战斗。 */
    public BattleFreeze freeze() {
        return freeze;
    }

    /** 有在途战斗（备战或战斗中）：基线 {@code IsInBattle}，全部在途闸的唯一判据。 */
    public boolean inBattle() {
        return freeze != null;
    }

    public Recovery recovery() {
        return recovery;
    }

    void setFreeze(BattleFreeze freeze) {
        this.freeze = freeze;
    }

    void setRecovery(Recovery recovery) {
        this.recovery = recovery;
    }

    boolean applying() {
        return applying;
    }

    void setApplying(boolean applying) {
        this.applying = applying;
    }
}
