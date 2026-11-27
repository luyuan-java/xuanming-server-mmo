package com.game.scene.skill;

import com.game.common.combatai.StateMutexMatrix;
import com.game.common.combatai.StateMutexMatrix.StateMode;
import com.game.table.ActorActionCombatStateTable;
import com.game.table.ActorActionStateTable;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.CooldownTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能用到的配表视图（Skill / Cooldown / ActorActionState / ActorActionCombatState / SkillPermission）。不可变，加载后任意线程可读。
 *
 * <p>时长在加载时换成纳秒：Skill 的 cast_point / recovery_time 是秒（double，schema 注释写的毫秒是错的，基线按秒用），
 * channel_finish 是秒（uint32），Cooldown.duration 是毫秒（uint32）。表是同步来的契约，加载只告警、不拒绝。
 *
 * <p>{@code ActorActionState} 与 {@code ActorActionCombatState} 已规范化为 {@code (id, state_id, state_mode, state_tip)}
 * 并统一模式口径（{@code 0=Block / 1=Allow / 2=Interrupt}），加载期一次性编译为 64 位 O(1) {@link StateMutexMatrix}。
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

    private final Map<Integer, SkillDef> skills;
    private final Map<Integer, Long> cooldownNanos;
    private final StateMutexMatrix actionStateMatrix;
    private final StateMutexMatrix combatStateMatrix;
    private final Map<Integer, List<Integer>> skillPermissions;
    private final Map<Integer, BuffTable> buffs;

    SkillTables(Map<Integer, SkillDef> skills, Map<Integer, Long> cooldownNanos,
                StateMutexMatrix actionStateMatrix, StateMutexMatrix combatStateMatrix,
                Map<Integer, List<Integer>> skillPermissions) {
        this(skills, cooldownNanos, actionStateMatrix, combatStateMatrix, skillPermissions, Map.of());
    }

    SkillTables(Map<Integer, SkillDef> skills, Map<Integer, Long> cooldownNanos,
                StateMutexMatrix actionStateMatrix, StateMutexMatrix combatStateMatrix,
                Map<Integer, List<Integer>> skillPermissions, Map<Integer, BuffTable> buffs) {
        this.skills = Map.copyOf(skills);
        this.cooldownNanos = Map.copyOf(cooldownNanos);
        this.actionStateMatrix = actionStateMatrix;
        this.combatStateMatrix = combatStateMatrix;
        this.skillPermissions = Map.copyOf(skillPermissions);
        this.buffs = buffs == null ? Map.of() : Map.copyOf(buffs);
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

        StateMutexMatrix actionMatrix = new StateMutexMatrix();
        Set<Long> seenActionPairs = new HashSet<>();
        for (ActorActionStateTable row : tables.actorActionState().all()) {
            compileMatrixRow("ActorActionState", actionMatrix, seenActionPairs,
                    row.getId(), row.getStateId(), row.getStateMode(), row.getStateTip());
        }

        StateMutexMatrix combatMatrix = new StateMutexMatrix();
        Set<Long> seenCombatPairs = new HashSet<>();
        for (ActorActionCombatStateTable row : tables.actorActionCombatState().all()) {
            compileMatrixRow("ActorActionCombatState", combatMatrix, seenCombatPairs,
                    row.getId(), row.getStateId(), row.getStateMode(), row.getStateTip());
        }

        Map<Integer, List<Integer>> permissions = new HashMap<>();
        for (SkillPermissionTable row : tables.skillPermission().all()) {
            permissions.put(row.getId(), List.copyOf(row.getSkillTypeList()));
        }
        Map<Integer, BuffTable> buffs = new HashMap<>();
        for (BuffTable row : tables.buff().all()) {
            if (row.getId() != 0) {
                buffs.put(row.getId(), row);
            }
        }
        return new SkillTables(skills, cooldowns, actionMatrix, combatMatrix, permissions, buffs);
    }

    /**
     * 编译一条规范化互斥规则进矩阵：保留 Java 侧 {@link SkillRules#tableTip(int)} 口径——
     * {@code BLOCK} 格填 {@code kSuccess(1000)} 归一化为放行，填 {@code 0} 按配置错 fail-closed 映射为 {@code 1002}。
     */
    static void compileMatrixRow(String tableName, StateMutexMatrix matrix, Set<Long> seenPairs,
                                 int actionId, int stateId, int modeRaw, int rawTip) {
        matrix.ensureAction(actionId);
        if (Integer.compareUnsigned(stateId, StateMutexMatrix.MAX_MUTEX_STATES) >= 0) {
            log.warn("{} state_id 越界已忽略 action={} state_id={}",
                    tableName, Integer.toUnsignedString(actionId), Integer.toUnsignedString(stateId));
            return;
        }
        StateMode mode = StateMode.fromCode(modeRaw);
        if (mode == null) {
            log.warn("{} state_mode 非法已忽略 action={} state_id={} state_mode={}",
                    tableName, Integer.toUnsignedString(actionId), Integer.toUnsignedString(stateId),
                    Integer.toUnsignedString(modeRaw));
            return;
        }
        long pairKey = (Integer.toUnsignedLong(actionId) << 32) | Integer.toUnsignedLong(stateId);
        if (seenPairs != null && !seenPairs.add(pairKey)) {
            log.warn("{} 存在重复规则已覆盖 action={} state_id={}",
                    tableName, Integer.toUnsignedString(actionId), Integer.toUnsignedString(stateId));
        }
        if (mode == StateMode.BLOCK) {
            int normalizedTip = SkillRules.tableTip(rawTip);
            if (normalizedTip == SkillRules.OK) {
                matrix.setRule(actionId, stateId, StateMode.ALLOW, SkillRules.OK);
            } else {
                matrix.setRule(actionId, stateId, StateMode.BLOCK, normalizedTip);
            }
            return;
        }
        matrix.setRule(actionId, stateId, mode, SkillRules.OK);
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

    /** 冷却组的时长（毫秒）；与 {@link #cooldownNanos} 同源，供 UTC 墙钟毫秒冷却判定使用。 */
    public long cooldownMillis(int cooldownId) {
        return cooldownNanos(cooldownId) / 1_000_000L;
    }

    /** Buff 表行；查不到（含 0 号）为 null。 */
    public BuffTable buff(int buffTableId) {
        return buffTableId == 0 ? null : buffs.get(buffTableId);
    }

    /** 预编译的行为互斥矩阵（行号 = 行为：0 放技能、1 跟随、2 上坐骑、3 下坐骑）。 */
    public StateMutexMatrix actionStateMatrix() {
        return actionStateMatrix;
    }

    /** 预编译的战斗异常状态互斥矩阵（行号同上）。 */
    public StateMutexMatrix combatStateMatrix() {
        return combatStateMatrix;
    }

    /** SkillPermission 的一行（行号 = 战斗状态，格子按技能类型位序号）；没有为 null。 */
    public List<Integer> skillPermissionRow(int combatState) {
        return skillPermissions.get(combatState);
    }
}
