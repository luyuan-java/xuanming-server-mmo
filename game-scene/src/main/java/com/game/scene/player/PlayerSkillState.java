package com.game.scene.player;

import com.game.common.combatai.StateMutexMatrix;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家的技能运行态（只在场景逻辑线程上读写；<b>不持久化</b>，随场景内的玩家实例创建与丢弃，同基线组件不进存档）：
 * 进行中的一次施法（阶段 + 截止时刻）、各冷却组的开始时刻、行为状态（战斗 / 跟随 / 骑乘）及 64 位位图缓存、
 * 战斗状态（沉默等，来源 buff 号集合）及 64 位位图缓存，以及主角权威状态位图同步基线。
 *
 * <p>时刻都是单调时钟纳秒（{@code SceneClock.nanoTime}），比较一律用差值，不怕回绕。阶段不靠定时器推进：
 * 截止时刻存在这里，下次放技能时由技能服务按截止时刻顺次结算（前一阶段的截止时刻就是下一阶段的起点，不会因结算晚了被拉长）。
 */
public final class PlayerSkillState {

    /** 施法阶段：前摇 → （引导技能）引导 → 后摇。 */
    public enum Phase {
        CASTING, CHANNELING, RECOVERY
    }

    /**
     * 一次进行中的施法。
     *
     * @param phaseEndsAtNanos 当前阶段的截止时刻（含之前已活跃，到点即结束）
     * @param channelNanos     引导时长（只有引导技能用；通用技能为 -1）
     * @param recoveryNanos    后摇时长
     */
    public record Cast(int skillTableId, Phase phase, long phaseEndsAtNanos, long channelNanos, long recoveryNanos) {

        public Cast next(Phase phase, long durationNanos) {
            return new Cast(skillTableId, phase, phaseEndsAtNanos + durationNanos, channelNanos, recoveryNanos);
        }
    }

    private Cast cast;
    /** 冷却组号 → 开始时刻。 */
    private final Map<Integer, Long> cooldownStarts = new HashMap<>();
    /** 当前行为状态（基线 eActorState：0 战斗、1 跟随、2 骑乘），按编号升序遍历。 */
    private final TreeSet<Integer> actionStates = new TreeSet<>();
    /** 与 {@link #actionStates} 同步维护的 64 位 O(1) 行为状态位图缓存。 */
    private long actionStateMask;
    /**
     * 当前战斗状态（基线 eActorCombatState：1 沉默）→ 来源 buff 号集合。
     */
    private final TreeMap<Integer, Set<Long>> combatStates = new TreeMap<>();
    /** 与 {@link #combatStates} 同步维护的 64 位 O(1) 战斗异常状态位图缓存。 */
    private long combatStateMask;
    /** 上次推送给主角本人的行为 / 战斗状态位图基线（入场重置为 0）与单调递增序号。 */
    private long lastSyncedActionStateMask;
    private long lastSyncedCombatStateMask;
    private long nextStateSyncSeq = 1L;

    public Cast cast() {
        return cast;
    }

    public void cast(Cast cast) {
        this.cast = cast;
    }

    public Long cooldownStart(int cooldownId) {
        return cooldownStarts.get(cooldownId);
    }

    public void startCooldown(int cooldownId, long nowNanos) {
        if (cooldownId == 0) {
            return;
        }
        cooldownStarts.put(cooldownId, nowNanos);
    }

    /** 当前行为状态编号（升序，只读；修改走 {@link #addActionState} / {@link #removeActionState} 保持位图同步）。 */
    public Set<Integer> actionStates() {
        return Collections.unmodifiableSet(actionStates);
    }

    /** O(1) 行为状态位图（位 i = eActorState(i)）。 */
    public long actionStateMask() {
        return actionStateMask;
    }

    public boolean hasActionState(int state) {
        if (Integer.compareUnsigned(state, StateMutexMatrix.MAX_MUTEX_STATES) < 0) {
            return (actionStateMask & (1L << state)) != 0L;
        }
        return actionStates.contains(state);
    }

    /** 添加行为状态并同步位图，返回该状态是否由未激活变为激活。 */
    public boolean addActionState(int state) {
        boolean added = actionStates.add(state);
        if (added && Integer.compareUnsigned(state, StateMutexMatrix.MAX_MUTEX_STATES) < 0) {
            actionStateMask |= (1L << state);
        }
        return added;
    }

    /** 移除行为状态并同步位图，返回该状态原先是否处于激活态。 */
    public boolean removeActionState(int state) {
        boolean removed = actionStates.remove(state);
        if (removed && Integer.compareUnsigned(state, StateMutexMatrix.MAX_MUTEX_STATES) < 0) {
            actionStateMask &= ~(1L << state);
        }
        return removed;
    }

    /** 当前战斗状态编号（升序，只读）。 */
    public Set<Integer> combatStates() {
        return Collections.unmodifiableSet(combatStates.keySet());
    }

    /** O(1) 战斗异常状态位图（位 i = eActorCombatState(i)）。 */
    public long combatStateMask() {
        return combatStateMask;
    }

    /**
     * 添加来源 buff 的战斗异常状态（引用计数语义）。
     *
     * @return 该状态是否从无到有（0 → 1）激活
     */
    public boolean addCombatState(int state, long sourceBuffId) {
        Set<Long> sources = combatStates.get(state);
        boolean becameActive = false;
        if (sources == null) {
            sources = new HashSet<>();
            combatStates.put(state, sources);
            becameActive = true;
        }
        sources.add(sourceBuffId);
        if (becameActive && Integer.compareUnsigned(state, StateMutexMatrix.MAX_MUTEX_STATES) < 0) {
            combatStateMask |= (1L << state);
        }
        return becameActive;
    }

    /**
     * 移除来源 buff 的战斗异常状态（最后一个来源移除时才清除状态位）。
     *
     * @return 该状态是否由激活变为彻底清除（1 → 0）
     */
    public boolean removeCombatState(int state, long sourceBuffId) {
        Set<Long> sources = combatStates.get(state);
        if (sources == null) {
            return false;
        }
        sources.remove(sourceBuffId);
        if (!sources.isEmpty()) {
            return false;
        }
        combatStates.remove(state);
        if (Integer.compareUnsigned(state, StateMutexMatrix.MAX_MUTEX_STATES) < 0) {
            combatStateMask &= ~(1L << state);
        }
        return true;
    }

    /**
     * 进场 / 换场景落地时调用：客户端收到 {@code EnterSceneS2C} 后本地状态位已清零，
     * 此处重置上次推送基线为 0（保留单调递增序号），使非零态立即可补推。
     */
    public void resetSyncedStateBaseline() {
        lastSyncedActionStateMask = 0L;
        lastSyncedCombatStateMask = 0L;
    }

    /**
     * 若当前行为或战斗状态位图较上次推送有变化，更新基线并返回递增的 {@code state_seq}（恒大于 0）；
     * 无变化则返回 0，不产生冗余网络包。
     */
    public long nextStateSyncSeqIfChanged() {
        if (actionStateMask == lastSyncedActionStateMask && combatStateMask == lastSyncedCombatStateMask) {
            return 0L;
        }
        lastSyncedActionStateMask = actionStateMask;
        lastSyncedCombatStateMask = combatStateMask;
        return nextStateSyncSeq++;
    }
}
