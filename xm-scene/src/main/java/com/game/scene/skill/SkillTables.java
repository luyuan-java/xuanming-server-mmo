package com.game.scene.skill;

import com.game.table.ActorActionCombatStateTable;
import com.game.table.ActorActionCombatStatestate;
import com.game.table.ActorActionStateTable;
import com.game.table.ActorActionStatestate;
import com.game.table.ConfigTables;
import com.game.table.CooldownTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能用到的配表视图（Skill / Cooldown / ActorActionState / ActorActionCombatState / SkillPermission）。不可变，加载后任意线程可读。
 * 时长在加载时换成纳秒：Skill 的 cast_point / recovery_time 是秒（double，schema 注释写的毫秒是错的，基线按秒用），
 * channel_finish 是秒（uint32），Cooldown.duration 是毫秒（uint32）。表是同步来的契约，加载只告警、不拒绝。
 */
public final class SkillTables {

    private static final Logger log = LoggerFactory.getLogger(SkillTables.class);

    static final int TYPE_GENERAL = 1;
    static final int TYPE_CHANNEL = 2;
    /** 时长上限（约 292 年的四分之一）：防表里离谱的值让截止时刻溢出。 */
    private static final long MAX_NANOS = Long.MAX_VALUE / 4;

    /**
     * 一行技能。
     *
     * @param skillTypes     技能类型的位序号（原样，按序；1 通用、2 引导，其余没有施法阶段）
     * @param targetingModes 目标方式的位序号（原样，按序；0 无目标、1 指定目标、2 范围，其余跳过）
     * @param immediate      能否打断进行中的施法
     */
    public record SkillDef(int id, List<Integer> skillTypes, List<Integer> targetingModes, boolean immediate,
                           long castPointNanos, long recoveryNanos, long channelFinishNanos, int cooldownId) {

        /** 通用技能（两个位都有时通用优先，同基线 SetupCastingTimer）。 */
        public boolean general() {
            return skillTypes.contains(TYPE_GENERAL);
        }

        public boolean channel() {
            return !general() && skillTypes.contains(TYPE_CHANNEL);
        }
    }

    /** 行为互斥 / 战斗状态表的一格。 */
    public record StateCell(int mode, int tip) {
    }

    private final Map<Integer, SkillDef> skills;
    private final Map<Integer, Long> cooldownNanos;
    private final Map<Integer, List<StateCell>> actionStates;
    private final Map<Integer, List<StateCell>> combatStates;
    private final Map<Integer, List<Integer>> skillPermissions;

    SkillTables(Map<Integer, SkillDef> skills, Map<Integer, Long> cooldownNanos, Map<Integer, List<StateCell>> actionStates,
                Map<Integer, List<StateCell>> combatStates, Map<Integer, List<Integer>> skillPermissions) {
        this.skills = Map.copyOf(skills);
        this.cooldownNanos = Map.copyOf(cooldownNanos);
        this.actionStates = Map.copyOf(actionStates);
        this.combatStates = Map.copyOf(combatStates);
        this.skillPermissions = Map.copyOf(skillPermissions);
    }

    public static SkillTables from(ConfigTables tables) {
        Map<Integer, Long> cooldowns = new HashMap<>();
        for (CooldownTable row : tables.cooldown().all()) {
            cooldowns.put(row.getId(), Integer.toUnsignedLong(row.getDuration()) * 1_000_000L);
        }
        Map<Integer, SkillDef> skills = new HashMap<>();
        for (SkillTable row : tables.skill().all()) {
            SkillDef def = new SkillDef(row.getId(), List.copyOf(row.getSkillTypeList()),
                    List.copyOf(row.getTargetingModeList()), row.getImmediate() != 0,
                    seconds(row.getId(), "cast_point", row.getCastPoint()),
                    seconds(row.getId(), "recovery_time", row.getRecoveryTime()),
                    Integer.toUnsignedLong(row.getChannelFinish()) * 1_000_000_000L, row.getCooldownId());
            if (def.cooldownId() != 0 && !cooldowns.containsKey(def.cooldownId())) {
                log.warn("技能的冷却组在 Cooldown 表里没有，这个技能不冷却 skill={} cooldown_id={}",
                        Integer.toUnsignedString(def.id()), Integer.toUnsignedString(def.cooldownId()));
            }
            skills.put(def.id(), def);
        }
        Map<Integer, List<StateCell>> action = new HashMap<>();
        for (ActorActionStateTable row : tables.actorActionState().all()) {
            List<StateCell> cells = new ArrayList<>();
            for (ActorActionStatestate cell : row.getStateList()) {
                cells.add(new StateCell(cell.getStateMode(), cell.getStateTip()));
            }
            action.put(row.getId(), List.copyOf(cells));
        }
        Map<Integer, List<StateCell>> combat = new HashMap<>();
        for (ActorActionCombatStateTable row : tables.actorActionCombatState().all()) {
            List<StateCell> cells = new ArrayList<>();
            for (ActorActionCombatStatestate cell : row.getStateList()) {
                cells.add(new StateCell(cell.getStateMode(), cell.getStateTip()));
            }
            combat.put(row.getId(), List.copyOf(cells));
        }
        Map<Integer, List<Integer>> permissions = new HashMap<>();
        for (SkillPermissionTable row : tables.skillPermission().all()) {
            permissions.put(row.getId(), List.copyOf(row.getSkillTypeList()));
        }
        return new SkillTables(skills, cooldowns, action, combat, permissions);
    }

    private static long seconds(int skillId, String column, double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            log.warn("技能时长不是非负有限数，按 0 处理 skill={} {}={}", Integer.toUnsignedString(skillId), column, seconds);
            return 0;
        }
        return (long) Math.min(Math.rint(seconds * 1e9), (double) MAX_NANOS);
    }

    /** 技能行；表里没有为 null（id 0 一律没有）。 */
    public SkillDef skill(int skillTableId) {
        return skillTableId == 0 ? null : skills.get(skillTableId);
    }

    /** 冷却组的时长（纳秒）；表里没有（含 0 号）为 0，即不冷却（同基线 Remaining 查不到行回 0）。 */
    public long cooldownNanos(int cooldownId) {
        return cooldownNanos.getOrDefault(cooldownId, 0L);
    }

    /** ActorActionState 的一行（行号 = 行为：0 放技能、1 跟随、2 上坐骑、3 下坐骑）；没有为 null。 */
    public List<StateCell> actionStateRow(int action) {
        return actionStates.get(action);
    }

    /** ActorActionCombatState 的一行（行号同上）；没有为 null。 */
    public List<StateCell> combatStateRow(int action) {
        return combatStates.get(action);
    }

    /** SkillPermission 的一行（行号 = 战斗状态，格子按技能类型位序号）；没有为 null。 */
    public List<Integer> skillPermissionRow(int combatState) {
        return skillPermissions.get(combatState);
    }
}
