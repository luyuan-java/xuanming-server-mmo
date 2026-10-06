package com.game.scene.battle;

/**
 * 一名玩家身上的回合制战斗冻结（基线 {@code InBattleComp{battle_id, battle_node_id, deadline_ms, state, prepare_deadline_ms}}；
 * scene-battle-spec §7.4）。可变，<b>只在场景逻辑线程上改</b>；不持久化，随实例生灭（跨断线靠 Redis 锁重建）。
 *
 * <p>异步回调按「实例 + 冻结对象」双重核对：回调回来时 {@code player.battle().freeze() != 这个对象} 就丢弃，所以同一局在新实例上沿用时
 * 复制成一个新对象（{@link #carriedCopy()}），不搬同一个。
 */
public final class BattleFreeze {

    /** 阶段（锁的 {@code s}：P / F）。 */
    public enum Phase {
        PREPARING,
        FIGHTING
    }

    private final long battleId;
    private final int battleNodeId;
    private Phase phase;
    private long deadlineMs;
    private long prepareDeadlineMs;
    /** 在本实例上 PrepareBattle 建的（顶替基线 BattlePrepareSessionComp，D7）：确认到达时不必推 144。 */
    private boolean preparedHere;
    /** 备战写锁在途。 */
    private boolean lockPending;
    /** 写锁在途时收到取消：写锁完成后再删锁。 */
    private boolean cancelRequested;
    /** FIGHTING 的条件续期已确认成功（D9）：之后的重复确认零 Redis。 */
    private boolean lockExtended;
    /** reaper 的 rescue 读在途（防重复处理）。 */
    private boolean rescuing;

    public BattleFreeze(long battleId, int battleNodeId, Phase phase, long deadlineMs, long prepareDeadlineMs,
                        boolean preparedHere) {
        this.battleId = battleId;
        this.battleNodeId = battleNodeId;
        this.phase = phase;
        this.deadlineMs = deadlineMs;
        this.prepareDeadlineMs = prepareDeadlineMs;
        this.preparedHere = preparedHere;
    }

    public long battleId() {
        return battleId;
    }

    public int battleNodeId() {
        return battleNodeId;
    }

    public Phase phase() {
        return phase;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    public long prepareDeadlineMs() {
        return prepareDeadlineMs;
    }

    /** 有效期限：PREPARING 看备战期限（0 时退回战斗期限），FIGHTING 看战斗期限。 */
    public long effectiveDeadlineMs() {
        if (phase == Phase.PREPARING) {
            return prepareDeadlineMs != 0 ? prepareDeadlineMs : deadlineMs;
        }
        return deadlineMs;
    }

    public boolean preparedHere() {
        return preparedHere;
    }

    boolean lockPending() {
        return lockPending;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    boolean lockExtended() {
        return lockExtended;
    }

    boolean rescuing() {
        return rescuing;
    }

    void upgrade(long deadlineMs) {
        this.phase = Phase.FIGHTING;
        if (deadlineMs != 0) {
            this.deadlineMs = deadlineMs;
        }
    }

    void setPreparedHere(boolean preparedHere) {
        this.preparedHere = preparedHere;
    }

    void setLockPending(boolean lockPending) {
        this.lockPending = lockPending;
    }

    void requestCancel() {
        this.cancelRequested = true;
    }

    void setLockExtended(boolean lockExtended) {
        this.lockExtended = lockExtended;
    }

    void setRescuing(boolean rescuing) {
        this.rescuing = rescuing;
    }

    /**
     * 同 epoch 沿用旧实例时复制给新实例（§7.8 第 0 步）：{@code preparedHere = false}，运行态标记复位（{@code rescuing} / {@code cancelRequested}
     * / {@code lockPending}），{@code lockExtended} 照抄。
     */
    BattleFreeze carriedCopy() {
        BattleFreeze copy = new BattleFreeze(battleId, battleNodeId, phase, deadlineMs, prepareDeadlineMs, false);
        copy.lockExtended = lockExtended;
        return copy;
    }

    @Override
    public String toString() {
        return "BattleFreeze{battle_id=" + Long.toUnsignedString(battleId) + ", node=" + battleNodeId + ", " + phase
                + ", deadline=" + deadlineMs + ", prepare_deadline=" + prepareDeadlineMs + ", prepared_here=" + preparedHere
                + ", lock_pending=" + lockPending + ", lock_extended=" + lockExtended + "}";
    }
}
