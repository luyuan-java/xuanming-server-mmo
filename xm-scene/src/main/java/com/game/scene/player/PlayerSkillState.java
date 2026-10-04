package com.game.scene.player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家的技能运行态（只在场景逻辑线程上读写；<b>不持久化</b>，随场景内的玩家实例创建与丢弃，同基线组件不进存档）：
 * 进行中的一次施法（阶段 + 截止时刻）、各冷却组的开始时刻、行为状态（战斗 / 跟随 / 骑乘）、战斗状态（沉默等，来源 buff 号集合）。
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
    /**
     * 当前战斗状态（基线 eActorCombatState：1 沉默）→ 来源 buff 号。只有实时 buff（沉默）会写，而基线实时 buff 线上从不挂到玩家身上，
     * 两版都恒空（PARITY「实时 buff」行）；回合制战斗的沉默在引擎内另算（路线图 6.x）。
     */
    private final TreeMap<Integer, Set<Long>> combatStates = new TreeMap<>();

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
        cooldownStarts.put(cooldownId, nowNanos);
    }

    /** 行为状态（可改：加 / 删由行为互斥规则做）。 */
    public TreeSet<Integer> actionStates() {
        return actionStates;
    }

    /** 当前战斗状态编号（升序，只读）。 */
    public Set<Integer> combatStates() {
        return Collections.unmodifiableSet(combatStates.keySet());
    }
}
