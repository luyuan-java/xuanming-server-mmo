package com.game.scene.player;

import com.game.common.combatai.StateMutexMatrix;
import com.game.player.store.state.SkillCooldownEntryState;
import com.game.player.store.state.SkillCooldownListState;
import com.google.protobuf.UnknownFieldSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家的技能运行态（只在场景逻辑线程上读写）：
 * 进行中的一次施法（阶段 + 截止时刻，不持久化）、各冷却组的开始时刻（UTC 墙钟毫秒，<b>随玩家存档全量保留</b>，跨重登/换节点/跨 zone 接着算）、
 * 行为状态（战斗 / 跟随 / 骑乘）及 64 位位图缓存、战斗状态（沉默等，来源 buff 号集合）及 64 位位图缓存，以及主角权威状态位图同步基线。
 *
 * <p>施法阶段时刻用单调时钟纳秒（{@code SceneClock.nanoTime}），冷却开始时刻用 UTC 墙钟毫秒（{@code SceneClock.epochMillis}）。
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
    /** 冷却组号 → 开始时刻（UTC 墙钟毫秒，无符号升序写出，避免存盘字节顺序抖动）。 */
    private final TreeMap<Integer, Long> cooldownStarts = new TreeMap<>(Integer::compareUnsigned);
    /** 冷却列表级未知字段（混版本保护：即便 entries 为空也原样保留）。 */
    private UnknownFieldSet cooldownUnknownFields = UnknownFieldSet.getDefaultInstance();
    /** 单条冷却记录级未知字段。 */
    private final Map<Integer, UnknownFieldSet> cooldownEntryUnknownFields = new HashMap<>();
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

    /** 冷却组的开始时刻（UTC 墙钟毫秒）；未记录过为 null。 */
    public Long cooldownStart(int cooldownId) {
        return cooldownStarts.get(cooldownId);
    }

    /** 记录冷却组开始时刻（UTC 墙钟毫秒）；0 号冷却组不登记。 */
    public void startCooldown(int cooldownId, long nowEpochMillis) {
        if (cooldownId == 0) {
            return;
        }
        cooldownStarts.put(cooldownId, nowEpochMillis);
    }

    /** 从存档恢复全部技能冷却与未知字段（加载规整前原样暂存，空列表但带未知字段也保留）。 */
    public void restoreCooldowns(SkillCooldownListState state) {
        cooldownStarts.clear();
        cooldownEntryUnknownFields.clear();
        if (state == null) {
            cooldownUnknownFields = UnknownFieldSet.getDefaultInstance();
            return;
        }
        cooldownUnknownFields = state.getUnknownFields();
        for (SkillCooldownEntryState entry : state.getEntriesList()) {
            int cooldownId = entry.getCooldownId();
            if (cooldownId == 0) {
                continue;
            }
            cooldownStarts.put(cooldownId, entry.getStartMs());
            if (!entry.getUnknownFields().asMap().isEmpty()) {
                cooldownEntryUnknownFields.put(cooldownId, entry.getUnknownFields());
            }
        }
    }

    /** 加载规整：写盘节点时钟超前导致开始时刻晚于本机当前时刻的，压回当前时刻（剩余至多一个整冷却）。 */
    public void clampLoadedCooldowns(long nowEpochMillis) {
        for (Map.Entry<Integer, Long> entry : cooldownStarts.entrySet()) {
            long startMs = entry.getValue();
            if (Long.compareUnsigned(startMs, nowEpochMillis) > 0) {
                entry.setValue(nowEpochMillis);
            }
        }
    }

    /** 是否无任何持久冷却记录且无列表级未知字段（新号或老档省略写该段）。 */
    public boolean isCooldownStatePristine() {
        return cooldownStarts.isEmpty() && cooldownUnknownFields.asMap().isEmpty();
    }

    /** 导出持久冷却状态：不按时钟剔除到期项，保证存档字节只由状态变化驱动。 */
    public SkillCooldownListState toCooldownState() {
        SkillCooldownListState.Builder builder = SkillCooldownListState.newBuilder()
                .setUnknownFields(cooldownUnknownFields);
        for (Map.Entry<Integer, Long> entry : cooldownStarts.entrySet()) {
            SkillCooldownEntryState.Builder row = SkillCooldownEntryState.newBuilder()
                    .setCooldownId(entry.getKey())
                    .setStartMs(entry.getValue());
            UnknownFieldSet entryUnknown = cooldownEntryUnknownFields.get(entry.getKey());
            if (entryUnknown != null && !entryUnknown.asMap().isEmpty()) {
                row.setUnknownFields(entryUnknown);
            }
            builder.addEntries(row);
        }
        return builder.build();
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
