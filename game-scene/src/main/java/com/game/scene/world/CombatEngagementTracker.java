package com.game.scene.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AOI 战斗交手关注、脱战 10 s 过期、死亡立即撤销与 NPC 仇恨名单（≤ 40 人）追踪器（L625）。
 *
 * <p>对齐 mmorpg {@code CombatEngagementSystem} + {@code CombatAllyRosterComp}：
 * <ul>
 *   <li>单次伤害（目标未死）或有效治疗（实际回血 &gt; 0 且施法者 != 目标）建立双方互相 {@link ViewIndex#KIND_COMBAT}
 *       显式关注（权重 {@link ViewIndex#WEIGHT_COMBAT}，除钉住外最高），不看距离；</li>
 *   <li>10 秒无新交手自动脱战撤销，触发空出名额回补暂隐显式关注与附近空间候选（如任务 NPC）；</li>
 *   <li>目标死亡立即撤销其所有交手关注与 NPC 仇恨名单登记；</li>
 *   <li>和同一 NPC 敌对交手的非 NPC 实体按先后进入最多 {@value #MAX_NPC_HATE_ROSTER} 人的仇恨名单，名单成员两两互相建立交手关注；
 *       给正在打该 NPC 的人回血时治疗者一并加入名单；只对 NPC 推，玩家混战不推。</li>
 * </ul>
 */
final class CombatEngagementTracker {

    /** 脱战衰减窗口（10 s）。 */
    static final long DECAY_MS = 10_000L;
    /** 单个 NPC 仇恨名单上限（按先后进入，最多 40 人）。 */
    static final int MAX_NPC_HATE_ROSTER = 40;
    private static final long COMBAT_INSTANCE_ID = 1L;
    private static final ViewIndex.ExplicitSource COMBAT_SOURCE =
            new ViewIndex.ExplicitSource(ViewIndex.KIND_COMBAT, COMBAT_INSTANCE_ID, ViewIndex.WEIGHT_COMBAT, false);

    private record Pair(ScenePlayer first, ScenePlayer second) {
        static Pair of(ScenePlayer a, ScenePlayer b) {
            return Long.compareUnsigned(a.playerId(), b.playerId()) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    private final Map<Pair, Long> engagements = new LinkedHashMap<>();
    /** npcId → (participant → lastActivityMs)，保持插入先后顺序、上限 {@value #MAX_NPC_HATE_ROSTER}。 */
    private final Map<Long, LinkedHashMap<ScenePlayer, Long>> npcHateRosters = new LinkedHashMap<>();

    /** 记录一次伤害交手；目标死亡立即撤销该目标全部交手与 NPC 仇恨名单。 */
    void onDamage(ScenePlayer attacker, ScenePlayer victim, boolean victimDied, long nowMs, ViewIndex view,
                  ViewChanges out) {
        if (victim == null) {
            return;
        }
        if (victimDied) {
            onEntityDied(victim, view, out);
            return;
        }
        engage(attacker, victim, nowMs, view, out);
    }

    /** 记录一次有效治疗（actualHeal &gt; 0 且施法者 != 目标）：双方互关，并将治疗者并入目标所在的全部 NPC 仇恨名单。 */
    void onHeal(ScenePlayer healer, ScenePlayer target, long actualHeal, long nowMs, ViewIndex view, ViewChanges out) {
        if (actualHeal <= 0L || healer == null || target == null || healer == target) {
            return;
        }
        engage(healer, target, nowMs, view, out);
        // 仅沿 NPC 仇恨名单传播：玩家混战不推
        List<Long> matchedNpcs = new ArrayList<>();
        for (Map.Entry<Long, LinkedHashMap<ScenePlayer, Long>> entry : npcHateRosters.entrySet()) {
            if (entry.getValue().containsKey(target)) {
                matchedNpcs.add(entry.getKey());
            }
        }
        for (long npcId : matchedNpcs) {
            addToNpcHateRoster(npcId, healer, nowMs, view, out);
        }
    }

    /** 记录一名非 NPC 参与者与指定 NPC 的敌对交手：按先后入名单（上限 40）并与名单成员两两互关。 */
    void onNpcHostileEngagement(long npcId, ScenePlayer participant, long nowMs, ViewIndex view, ViewChanges out) {
        if (npcId == 0L || participant == null) {
            return;
        }
        addToNpcHateRoster(npcId, participant, nowMs, view, out);
    }

    /** 实体死亡：立即撤销其所有战斗关注与 NPC 仇恨名单占位。 */
    void onEntityDied(ScenePlayer entity, ViewIndex view, ViewChanges out) {
        if (entity == null) {
            return;
        }
        for (Iterator<Map.Entry<Pair, Long>> it = engagements.entrySet().iterator(); it.hasNext(); ) {
            Pair pair = it.next().getKey();
            if (pair.first() == entity || pair.second() == entity) {
                it.remove();
                revokePair(pair, view, out);
            }
        }
        for (Iterator<LinkedHashMap<ScenePlayer, Long>> it = npcHateRosters.values().iterator(); it.hasNext(); ) {
            LinkedHashMap<ScenePlayer, Long> roster = it.next();
            roster.remove(entity);
            if (roster.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 定期推进脱战过期：距上次交手 ≥ 10 s 的关注对立即撤销，名额空出后由 {@link ViewIndex} 回补附近候选。 */
    void tickDecay(long nowMs, ViewIndex view, ViewChanges out) {
        for (Iterator<Map.Entry<Pair, Long>> it = engagements.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Pair, Long> entry = it.next();
            if (nowMs - entry.getValue() >= DECAY_MS) {
                Pair pair = entry.getKey();
                it.remove();
                revokePair(pair, view, out);
            }
        }
        for (Iterator<LinkedHashMap<ScenePlayer, Long>> it = npcHateRosters.values().iterator(); it.hasNext(); ) {
            LinkedHashMap<ScenePlayer, Long> roster = it.next();
            roster.entrySet().removeIf(e -> nowMs - e.getValue() >= DECAY_MS);
            if (roster.isEmpty()) {
                it.remove();
            }
        }
    }

    int activeEngagementCount() {
        return engagements.size();
    }

    int npcHateRosterSize(long npcId) {
        LinkedHashMap<ScenePlayer, Long> roster = npcHateRosters.get(npcId);
        return roster == null ? 0 : roster.size();
    }

    private void addToNpcHateRoster(long npcId, ScenePlayer participant, long nowMs, ViewIndex view, ViewChanges out) {
        LinkedHashMap<ScenePlayer, Long> roster = npcHateRosters.computeIfAbsent(npcId, ignored -> new LinkedHashMap<>());
        if (!roster.containsKey(participant)) {
            if (roster.size() >= MAX_NPC_HATE_ROSTER) {
                return;
            }
            roster.put(participant, nowMs);
        } else {
            roster.put(participant, nowMs);
        }
        for (ScenePlayer ally : roster.keySet()) {
            if (ally != participant) {
                engage(participant, ally, nowMs, view, out);
            }
        }
    }

    private void engage(ScenePlayer a, ScenePlayer b, long nowMs, ViewIndex view, ViewChanges out) {
        if (a == null || b == null || a == b) {
            return;
        }
        engagements.put(Pair.of(a, b), nowMs);
        view.addExplicitInterest(a, b, COMBAT_SOURCE, out);
        view.addExplicitInterest(b, a, COMBAT_SOURCE, out);
    }

    private static void revokePair(Pair pair, ViewIndex view, ViewChanges out) {
        view.removeExplicitInterest(pair.first(), pair.second(), ViewIndex.KIND_COMBAT, COMBAT_INSTANCE_ID, out);
        view.removeExplicitInterest(pair.second(), pair.first(), ViewIndex.KIND_COMBAT, COMBAT_INSTANCE_ID, out);
    }
}
