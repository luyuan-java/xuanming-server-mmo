package com.game.battle.engine;

import com.game.proto.BattleAction;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleScript;
import com.game.proto.BattleScriptCondition;
import com.game.proto.BattleScriptRole;
import com.game.proto.BattleScriptRule;
import com.game.proto.BattleScriptTarget;
import com.game.proto.BattleScriptTargetFilter;
import com.game.proto.BattleTeamScript;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleScriptCondition;
import com.game.proto.eBattleScriptSide;
import com.game.proto.eBattleScriptTargetFilter;
import com.game.proto.eBattleScriptTargetOrder;
import com.game.proto.eBattleScriptTargetSide;
import com.game.table.SkillTable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 战斗剧本的开局复核、角色位认领与回合求值器（逐条对齐 C++ {@code battle_script.cpp} 与 {@code turn_battle_engine.cpp}）。
 *
 * <p>纯逻辑：不打日志、不碰时钟、不消耗随机数；开局校验按本节点配表复核，不合法整份忽略并记入 {@link #rejections()}，
 * 由上层 battle 节点输出 ERROR 日志。
 */
final class BattleScriptEvaluator {

    static final int SCHEMA_VERSION = 1;
    static final int MAX_ROLES = 8;
    static final int MAX_RULES_PER_ROLE = 16;
    static final int MAX_CONDITIONS_PER_RULE = 2;
    static final int MAX_FILTERS_PER_RULE = 3;
    static final int MAX_ROLE_MEMBERS = 10;
    static final int MAX_ROUND = 1000;
    static final int MAX_UNIT_COUNT = 20;
    static final int MAX_CLASS_ID = 1000;
    static final int PERMYRIAD_FULL = 10_000;
    static final int MAX_NAME_BYTES = 64;
    static final int MAX_DESCRIPTION_BYTES = 512;
    static final int MAX_NOTE_BYTES = 256;

    private static final int TARGET_TEAM_ENEMY = 1;
    private static final int TARGET_TEAM_ALLY = 2;
    private static final int TARGET_TEAM_SELF = 3;

    // 剧本能当「状态」判断的持续类 buff 类型（与 C++ kScriptStatusBuffTypes 一致）
    private static final Set<Integer> STATUS_BUFF_TYPES = Set.of(
            10, // INCREASE_ATTACK
            11, // INCREASE_DEFENSE
            13, // MOVEMENT_SPEED_BOOST
            BattleConstants.BUFF_TYPE_STUN,
            BattleConstants.BUFF_TYPE_SILENCE,
            BattleConstants.BUFF_TYPE_INVINCIBILITY,
            BattleConstants.BUFF_TYPE_IMMUNITY,
            BattleConstants.BUFF_TYPE_HEALTH_REGENERATION,
            BattleConstants.BUFF_TYPE_MANA_REGENERATION,
            BattleConstants.BUFF_TYPE_HEALTH_REGENERATION_BASED_ON_LOST_HEALTH,
            BattleConstants.BUFF_TYPE_POISON,
            BattleConstants.BUFF_TYPE_BURN,
            BattleConstants.BUFF_TYPE_FREEZE);

    private record Claim(int roleId, long targetId) {
    }

    private final BattleContext ctx;
    private final ActionChecks checks;
    private final BattleScript[] teamScripts = new BattleScript[2];
    private final NavigableMap<Long, Integer> scriptRoleOf = new TreeMap<>(Long::compareUnsigned);
    private final List<String> rejections = new ArrayList<>();

    BattleScriptEvaluator(BattleContext ctx, ActionChecks checks) {
        this.ctx = ctx;
        this.checks = checks;
    }

    List<String> rejections() {
        return List.copyOf(rejections);
    }

    /** 无符号 64 位饱和乘法（对齐 C++ {@code SaturatingMul}）。 */
    static long saturatingMul(long lhs, long rhs) {
        if (lhs == 0L || rhs == 0L) {
            return 0L;
        }
        if (Long.compareUnsigned(rhs, Long.divideUnsigned(-1L, lhs)) > 0) {
            return -1L;
        }
        return lhs * rhs;
    }

    /** {@code value / max < permyriad / 10000}，纯整数交叉相乘（饱和）；{@code max == 0} 视为满返回 false。 */
    static boolean ratioBelowPermyriad(long value, long max, int permyriad) {
        if (max == 0L) {
            return false;
        }
        return Long.compareUnsigned(
                saturatingMul(value, PERMYRIAD_FULL),
                saturatingMul(max, Integer.toUnsignedLong(permyriad))) < 0;
    }

    /** {@code lhsValue / lhsMax < rhsValue / rhsMax}，纯整数交叉相乘（饱和）；{@code max == 0} 视为满（1/1）。 */
    static boolean ratioLess(long lhsValue, long lhsMax, long rhsValue, long rhsMax) {
        if (lhsMax == 0L) {
            lhsValue = 1L;
            lhsMax = 1L;
        }
        if (rhsMax == 0L) {
            rhsValue = 1L;
            rhsMax = 1L;
        }
        return Long.compareUnsigned(saturatingMul(lhsValue, rhsMax), saturatingMul(rhsValue, lhsMax)) < 0;
    }

    static boolean isScriptStatusBuffType(int buffType) {
        return STATUS_BUFF_TYPES.contains(buffType);
    }

    /** 开局绑定并按本节点配表复核 {@code CreateBattleRequest.team_scripts}，派生怪物技能集。 */
    void bind(CreateBattleRequest request) {
        boolean[] seenTeam = new boolean[2];
        for (BattleTeamScript teamScript : request.getTeamScriptsList()) {
            int teamIndex = teamScript.getTeamIndex();
            if (Integer.compareUnsigned(teamIndex, 1) > 0 || seenTeam[teamIndex]) {
                rejections.add("team_index " + Integer.toUnsignedString(teamIndex)
                        + ": out of range or more than one script for the team");
                continue;
            }
            seenTeam[teamIndex] = true;

            boolean monsterSide = false;
            for (BattleUnit unit : ctx.units()) {
                if (unit.teamIndex() == teamIndex && unit.isMonster()) {
                    monsterSide = true;
                    break;
                }
            }
            StringBuilder error = new StringBuilder();
            BattleScript normalized = normalize(teamScript.getScript(), ctx.data(), error);
            if (normalized == null || !checkSide(normalized,
                    monsterSide ? eBattleScriptSide.BATTLE_SCRIPT_SIDE_MONSTER : eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER,
                    ctx.data(), error)) {
                rejections.add("team_index " + teamIndex + ": " + error);
                continue;
            }

            Map<Integer, Integer> roleMembers = new HashMap<>();
            for (BattleUnit unit : ctx.units()) {
                if (unit.teamIndex() != teamIndex) {
                    continue;
                }
                for (BattleScriptRole role : normalized.getRolesList()) {
                    if (!roleAccepts(role, unit)) {
                        continue;
                    }
                    int members = roleMembers.getOrDefault(role.getRoleId(), 0);
                    if (role.getMaxMembers() != 0 && Integer.compareUnsigned(members, role.getMaxMembers()) >= 0) {
                        continue;
                    }
                    roleMembers.put(role.getRoleId(), members + 1);
                    scriptRoleOf.put(unit.actorId(), role.getRoleId());
                    if (unit.isMonster()) {
                        deriveMonsterSkills(unit, role);
                    }
                    break;
                }
            }
            teamScripts[teamIndex] = normalized;
        }
    }

    private void deriveMonsterSkills(BattleUnit monster, BattleScriptRole role) {
        TreeSet<Integer> skillIds = new TreeSet<>(Integer::compareUnsigned);
        for (BattleScriptRule rule : role.getRulesList()) {
            if (rule.getAction() == eBattleActionType.BATTLE_ACTION_SKILL && rule.getSkillId() != 0) {
                skillIds.add(rule.getSkillId());
            }
        }
        for (int skillId : skillIds) {
            if (!monster.state().getSkillTableIdsList().contains(skillId)) {
                monster.state().addSkillTableIds(skillId);
            }
            Optional<SkillTable> row = ctx.data().skill(skillId);
            if (row.isPresent() && row.get().getBattleModel() == 1) {
                monster.state().putSkillRanks(skillId, 1);
            }
        }
    }

    /** 回合收集结束后、其余默认行动之前执行剧本决策（零 RNG 消耗）。 */
    void fillActions(NavigableMap<Long, BattleAction> pendingActions) {
        for (int teamIndex = 0; teamIndex < 2; teamIndex++) {
            BattleScript script = teamScripts[teamIndex];
            if (script == null) {
                continue;
            }
            // 先登记手动提交行动的本角色位目标认领（target_id == 0 不登记）
            List<Claim> claims = new ArrayList<>();
            for (BattleUnit unit : ctx.units()) {
                if (unit.teamIndex() != teamIndex) {
                    continue;
                }
                Integer roleId = scriptRoleOf.get(unit.actorId());
                BattleAction submitted = pendingActions.get(unit.actorId());
                if (roleId != null && submitted != null && submitted.getTargetId() != 0L) {
                    claims.add(new Claim(roleId, submitted.getTargetId()));
                }
            }
            // 决策顺序 = 角色位 order，同一角色位内按单位插入序（阵位）
            for (BattleScriptRole role : script.getRolesList()) {
                for (BattleUnit unit : ctx.units()) {
                    if (unit.teamIndex() != teamIndex || !unit.isActive()
                            || pendingActions.containsKey(unit.actorId())) {
                        continue;
                    }
                    Integer claimedRole = scriptRoleOf.get(unit.actorId());
                    if (claimedRole == null || claimedRole != role.getRoleId()) {
                        continue;
                    }
                    BattleAction chosen = selectAction(unit, role, claims);
                    if (chosen == null) {
                        continue;
                    }
                    if (chosen.getTargetId() != 0L) {
                        claims.add(new Claim(role.getRoleId(), chosen.getTargetId()));
                    }
                    pendingActions.put(unit.actorId(), chosen);
                }
            }
        }
    }

    private BattleAction selectAction(BattleUnit actor, BattleScriptRole role, List<Claim> claims) {
        for (BattleScriptRule rule : role.getRulesList()) {
            boolean allConditions = true;
            for (BattleScriptCondition cond : rule.getConditionsList()) {
                if (!conditionHolds(actor, cond)) {
                    allConditions = false;
                    break;
                }
            }
            if (!allConditions) {
                continue;
            }

            List<Long> targetIds;
            switch (rule.getTarget().getSide()) {
                case BATTLE_SCRIPT_TARGET_SELF -> targetIds = List.of(
                        rule.getAction() == eBattleActionType.BATTLE_ACTION_DEFEND ? 0L : actor.actorId());
                case BATTLE_SCRIPT_TARGET_ENGINE_PICK -> targetIds = List.of(0L);
                case BATTLE_SCRIPT_TARGET_ENEMY, BATTLE_SCRIPT_TARGET_ALLY ->
                        targetIds = collectTargets(actor, rule.getTarget(), role.getRoleId(), claims);
                default -> targetIds = List.of();
            }

            for (long targetId : targetIds) {
                BattleAction candidate = BattleAction.newBuilder()
                        .setActionType(rule.getAction())
                        .setSkillTableId(rule.getSkillId())
                        .setTargetId(targetId)
                        .build();
                if (checks.checkActionPrerequisites(actor, candidate) == ActionChecks.SUCCESS) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean conditionHolds(BattleUnit actor, BattleScriptCondition cond) {
        boolean holds;
        int roundIndex = ctx.roundIndex();
        switch (cond.getKind()) {
            case BATTLE_SCRIPT_COND_SELF_HP_BELOW ->
                    holds = ratioBelowPermyriad(actor.health(), actor.maxHealth(), cond.getP1());
            case BATTLE_SCRIPT_COND_ROUND_AT_LEAST ->
                    holds = Integer.compareUnsigned(roundIndex, cond.getP1()) >= 0;
            case BATTLE_SCRIPT_COND_ROUND_EVERY -> holds = cond.getP1() != 0
                    && Integer.compareUnsigned(roundIndex, cond.getP2()) >= 0
                    && Integer.remainderUnsigned(roundIndex - cond.getP2(), cond.getP1()) == 0;
            case BATTLE_SCRIPT_COND_ENEMY_COUNT_AT_LEAST ->
                    holds = Integer.compareUnsigned(ctx.collectAliveEnemyIds(actor).length, cond.getP1()) >= 0;
            default -> {
                return false;
            }
        }
        return holds != cond.getNegate();
    }

    private boolean filterHolds(BattleUnit candidate, BattleScriptTargetFilter filter,
                                int ownRoleId, List<Claim> claims) {
        boolean holds;
        int p1 = filter.getP1();
        switch (filter.getKind()) {
            case BATTLE_SCRIPT_FILTER_HP_BELOW ->
                    holds = ratioBelowPermyriad(candidate.health(), candidate.maxHealth(), p1);
            case BATTLE_SCRIPT_FILTER_HAS_BUFF_TYPE -> holds = ctx.actorHasBuffOfType(candidate, p1);
            case BATTLE_SCRIPT_FILTER_HAS_BUFF -> {
                holds = false;
                for (BattleBuffEntry buff : candidate.state().getBuffsList()) {
                    if (buff.getBuffTableId() == p1) {
                        holds = true;
                        break;
                    }
                }
            }
            case BATTLE_SCRIPT_FILTER_IS_MONSTER ->
                    holds = candidate.isMonster() && candidate.monsterTableId() == p1;
            case BATTLE_SCRIPT_FILTER_IS_CLASS ->
                    holds = candidate.isPlayer() && candidate.state().getClassId() == p1;
            case BATTLE_SCRIPT_FILTER_IS_ACTOR_TYPE -> holds = candidate.actorType().getNumber() == p1;
            case BATTLE_SCRIPT_FILTER_CLAIMED_BY_ROLE -> {
                int roleId = p1 == 0 ? ownRoleId : p1;
                holds = false;
                for (Claim c : claims) {
                    if (c.roleId() == roleId && c.targetId() == candidate.actorId()) {
                        holds = true;
                        break;
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return holds != filter.getNegate();
    }

    private List<Long> collectTargets(BattleUnit actor, BattleScriptTarget target,
                                      int ownRoleId, List<Claim> claims) {
        boolean wantAlly = target.getSide() == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY;
        List<BattleUnit> candidates = new ArrayList<>();
        for (BattleUnit other : ctx.units()) {
            if (!other.isActive() || (other.teamIndex() == actor.teamIndex()) != wantAlly) {
                continue;
            }
            boolean passes = true;
            for (BattleScriptTargetFilter filter : target.getFiltersList()) {
                if (!filterHolds(other, filter, ownRoleId, claims)) {
                    passes = false;
                    break;
                }
            }
            if (passes) {
                candidates.add(other);
            }
        }
        eBattleScriptTargetOrder order = target.getOrder();
        candidates.sort((lhs, rhs) -> {
            int cmp = switch (order) {
                case BATTLE_SCRIPT_ORDER_HP_LOWEST -> Long.compareUnsigned(lhs.health(), rhs.health());
                case BATTLE_SCRIPT_ORDER_HP_HIGHEST -> Long.compareUnsigned(rhs.health(), lhs.health());
                case BATTLE_SCRIPT_ORDER_HP_RATIO_LOWEST -> {
                    if (ratioLess(lhs.health(), lhs.maxHealth(), rhs.health(), rhs.maxHealth())) {
                        yield -1;
                    }
                    if (ratioLess(rhs.health(), rhs.maxHealth(), lhs.health(), lhs.maxHealth())) {
                        yield 1;
                    }
                    yield 0;
                }
                case BATTLE_SCRIPT_ORDER_SPEED_HIGHEST ->
                        Long.compareUnsigned(ctx.effectiveSpeed(rhs), ctx.effectiveSpeed(lhs));
                default -> 0;
            };
            if (cmp != 0) {
                return cmp;
            }
            int slotCmp = Integer.compareUnsigned(lhs.state().getFormationSlot(), rhs.state().getFormationSlot());
            if (slotCmp != 0) {
                return slotCmp;
            }
            return Long.compareUnsigned(lhs.actorId(), rhs.actorId());
        });
        List<Long> ids = new ArrayList<>(candidates.size());
        for (BattleUnit c : candidates) {
            ids.add(c.actorId());
        }
        return ids;
    }

    private static boolean roleAccepts(BattleScriptRole role, BattleUnit actor) {
        if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                && role.getActorType() != actor.actorType()) {
            return false;
        }
        if (role.getClassId() != 0
                && (!actor.isPlayer() || actor.state().getClassId() != role.getClassId())) {
            return false;
        }
        if (role.getMonsterId() != 0
                && (!actor.isMonster() || actor.monsterTableId() != role.getMonsterId())) {
            return false;
        }
        return true;
    }

    // ---- 校验与规范化（逐条对齐 C++ NormalizeBattleScript & CheckBattleScriptSide） ----

    static BattleScript normalize(BattleScript script, BattleData data, StringBuilder error) {
        String scriptWhere = "script " + Integer.toUnsignedString(script.getScriptId());
        if (script.getSchemaVersion() != SCHEMA_VERSION) {
            return reject(error, scriptWhere + ": unsupported schema_version "
                    + Integer.toUnsignedString(script.getSchemaVersion()));
        }
        if (utf8Len(script.getName()) > MAX_NAME_BYTES || utf8Len(script.getDescription()) > MAX_DESCRIPTION_BYTES) {
            return reject(error, scriptWhere + ": name or description too long");
        }
        if (script.getRolesCount() == 0 || script.getRolesCount() > MAX_ROLES) {
            return reject(error, scriptWhere + ": needs 1.." + MAX_ROLES + " roles");
        }

        Set<Integer> roleIds = new HashSet<>();
        Set<Integer> roleOrders = new HashSet<>();
        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = scriptWhere + " role " + Integer.toUnsignedString(role.getRoleId());
            if (role.getRoleId() == 0 || !roleIds.add(role.getRoleId())) {
                return reject(error, roleWhere + ": role_id must be non-zero and unique");
            }
            if (!roleOrders.add(role.getOrder())) {
                return reject(error, roleWhere + ": duplicate role order " + Integer.toUnsignedString(role.getOrder()));
            }
            if (utf8Len(role.getName()) > MAX_NAME_BYTES) {
                return reject(error, roleWhere + ": name too long");
            }
            if (eBattleActorType.forNumber(role.getActorTypeValue()) == null) {
                return reject(error, roleWhere + ": unknown actor_type");
            }
            if (Integer.compareUnsigned(role.getClassId(), MAX_CLASS_ID) > 0) {
                return reject(error, roleWhere + ": class_id out of range");
            }
            if (role.getClassId() != 0
                    && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                    && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER) {
                return reject(error, roleWhere + ": class_id only applies to players");
            }
            if (role.getMonsterId() != 0) {
                if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                        && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER) {
                    return reject(error, roleWhere + ": monster_id only applies to monsters");
                }
                if (role.getClassId() != 0) {
                    return reject(error, roleWhere + ": a role cannot require both a class and a monster");
                }
                if (data.monster(role.getMonsterId()).isEmpty()) {
                    return reject(error, roleWhere + ": unknown monster " + Integer.toUnsignedString(role.getMonsterId()));
                }
            }
            if (Integer.compareUnsigned(role.getMaxMembers(), MAX_ROLE_MEMBERS) > 0) {
                return reject(error, roleWhere + ": max_members above " + MAX_ROLE_MEMBERS);
            }
            if (role.getRulesCount() == 0 || role.getRulesCount() > MAX_RULES_PER_ROLE) {
                return reject(error, roleWhere + ": needs 1.." + MAX_RULES_PER_ROLE + " rules");
            }
        }

        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = scriptWhere + " role " + Integer.toUnsignedString(role.getRoleId());
            Set<Integer> priorities = new HashSet<>();
            for (BattleScriptRule rule : role.getRulesList()) {
                String ruleWhere = roleWhere + " rule " + Integer.toUnsignedString(rule.getPriority());
                if (!priorities.add(rule.getPriority())) {
                    return reject(error, ruleWhere + ": duplicate rule priority");
                }
                if (utf8Len(rule.getNote()) > MAX_NOTE_BYTES) {
                    return reject(error, ruleWhere + ": note too long");
                }
                if (rule.getConditionsCount() > MAX_CONDITIONS_PER_RULE) {
                    return reject(error, ruleWhere + ": at most " + MAX_CONDITIONS_PER_RULE + " conditions");
                }
                for (BattleScriptCondition cond : rule.getConditionsList()) {
                    if (!checkCondition(cond, ruleWhere, error)) {
                        return null;
                    }
                }
                if (!checkTarget(rule.getTarget(), roleIds, data, ruleWhere, error)
                        || !checkAction(role, rule, data, ruleWhere, error)) {
                    return null;
                }
            }
        }

        List<BattleScriptRole> sortedRoles = new ArrayList<>(script.getRolesCount());
        for (BattleScriptRole role : script.getRolesList()) {
            List<BattleScriptRule> sortedRules = new ArrayList<>(role.getRulesList());
            sortedRules.sort((a, b) -> Integer.compareUnsigned(a.getPriority(), b.getPriority()));
            sortedRoles.add(role.toBuilder().clearRules().addAllRules(sortedRules).build());
        }
        sortedRoles.sort((a, b) -> Integer.compareUnsigned(a.getOrder(), b.getOrder()));
        return script.toBuilder().clearRoles().addAllRoles(sortedRoles).build();
    }

    static boolean checkSide(BattleScript script, eBattleScriptSide side, BattleData data, StringBuilder error) {
        String scriptWhere = "script " + Integer.toUnsignedString(script.getScriptId());
        if (side != eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER
                && side != eBattleScriptSide.BATTLE_SCRIPT_SIDE_MONSTER) {
            return fail(error, scriptWhere + ": side must be PLAYER or MONSTER");
        }
        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = scriptWhere + " role " + Integer.toUnsignedString(role.getRoleId());
            if (side == eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER) {
                if (role.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER || role.getMonsterId() != 0) {
                    return fail(error, roleWhere + ": player side roles cannot accept monsters");
                }
                continue;
            }
            if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                    && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER) {
                return fail(error, roleWhere + ": monster side roles can only accept monsters");
            }
            if (role.getClassId() != 0) {
                return fail(error, roleWhere + ": monster side roles cannot require a class");
            }
            for (BattleScriptRule rule : role.getRulesList()) {
                if (rule.getAction() != eBattleActionType.BATTLE_ACTION_SKILL) {
                    continue;
                }
                String ruleWhere = roleWhere + " rule " + Integer.toUnsignedString(rule.getPriority());
                Optional<SkillTable> rowOpt = data.skill(rule.getSkillId());
                if (rowOpt.isEmpty() || rowOpt.get().getClassId() != 0) {
                    return fail(error, ruleWhere + ": monsters can only cast legacy skills");
                }
                SkillTable row = rowOpt.get();
                if (row.getBattleModel() == 1) {
                    if (row.getManaPercent() > 0) {
                        return fail(error, ruleWhere + ": monsters have no mana but skill "
                                + Integer.toUnsignedString(rule.getSkillId()) + " costs mana");
                    }
                } else if (BattleRules.skillManaCost(row) != 0L) {
                    return fail(error, ruleWhere + ": monsters have no mana but skill "
                            + Integer.toUnsignedString(rule.getSkillId()) + " costs mana");
                }
            }
        }
        return true;
    }

    private static boolean checkCondition(BattleScriptCondition cond, String where, StringBuilder error) {
        return switch (cond.getKind()) {
            case BATTLE_SCRIPT_COND_SELF_HP_BELOW -> inRange(cond.getP1(), 1, PERMYRIAD_FULL) && cond.getP2() == 0
                    ? true : fail(error, where + ": SELF_HP_BELOW needs p1 in 1..10000 and p2 = 0");
            case BATTLE_SCRIPT_COND_ROUND_AT_LEAST -> inRange(cond.getP1(), 1, MAX_ROUND) && cond.getP2() == 0
                    ? true : fail(error, where + ": ROUND_AT_LEAST needs p1 in 1.." + MAX_ROUND + " and p2 = 0");
            case BATTLE_SCRIPT_COND_ROUND_EVERY -> inRange(cond.getP1(), 1, MAX_ROUND)
                    && inRange(cond.getP2(), 1, MAX_ROUND)
                    ? true : fail(error, where + ": ROUND_EVERY needs period p1 and first round p2 in 1.." + MAX_ROUND);
            case BATTLE_SCRIPT_COND_ENEMY_COUNT_AT_LEAST -> inRange(cond.getP1(), 1, MAX_UNIT_COUNT) && cond.getP2() == 0
                    ? true : fail(error, where + ": ENEMY_COUNT_AT_LEAST needs p1 in 1.." + MAX_UNIT_COUNT + " and p2 = 0");
            default -> fail(error, where + ": unknown condition kind " + Integer.toUnsignedString(cond.getKindValue()));
        };
    }

    private static boolean checkFilter(BattleScriptTargetFilter filter, Set<Integer> roleIds,
                                       BattleData data, String where, StringBuilder error) {
        int p1 = filter.getP1();
        return switch (filter.getKind()) {
            case BATTLE_SCRIPT_FILTER_HP_BELOW -> inRange(p1, 1, PERMYRIAD_FULL)
                    ? true : fail(error, where + ": HP_BELOW needs p1 in 1..10000");
            case BATTLE_SCRIPT_FILTER_HAS_BUFF_TYPE -> isScriptStatusBuffType(p1)
                    ? true : fail(error, where + ": HAS_BUFF_TYPE " + Integer.toUnsignedString(p1) + " is not a status type");
            case BATTLE_SCRIPT_FILTER_HAS_BUFF -> p1 != 0 && data.buff(p1).isPresent()
                    ? true : fail(error, where + ": unknown buff " + Integer.toUnsignedString(p1));
            case BATTLE_SCRIPT_FILTER_IS_MONSTER -> p1 != 0 && data.monster(p1).isPresent()
                    ? true : fail(error, where + ": unknown monster " + Integer.toUnsignedString(p1));
            case BATTLE_SCRIPT_FILTER_IS_CLASS -> inRange(p1, 1, MAX_CLASS_ID)
                    ? true : fail(error, where + ": IS_CLASS needs a class id");
            case BATTLE_SCRIPT_FILTER_IS_ACTOR_TYPE ->
                    p1 == eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER_VALUE
                            || p1 == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER_VALUE
                            || p1 == eBattleActorType.BATTLE_ACTOR_TYPE_PET_VALUE
                            ? true : fail(error, where + ": IS_ACTOR_TYPE needs 1 (player), 2 (monster) or 3 (pet)");
            case BATTLE_SCRIPT_FILTER_CLAIMED_BY_ROLE -> p1 == 0 || roleIds.contains(p1)
                    ? true : fail(error, where + ": CLAIMED_BY_ROLE refers to unknown role " + Integer.toUnsignedString(p1));
            default -> fail(error, where + ": unknown filter kind " + Integer.toUnsignedString(filter.getKindValue()));
        };
    }

    private static boolean checkTarget(BattleScriptTarget target, Set<Integer> roleIds,
                                       BattleData data, String where, StringBuilder error) {
        eBattleScriptTargetSide side = target.getSide();
        if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_SELF
                && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY
                && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
            return fail(error, where + ": target side must be SELF, ENEMY, ALLY or ENGINE_PICK");
        }
        if (eBattleScriptTargetOrder.forNumber(target.getOrderValue()) == null) {
            return fail(error, where + ": unknown target order " + Integer.toUnsignedString(target.getOrderValue()));
        }
        if (target.getFiltersCount() > MAX_FILTERS_PER_RULE) {
            return fail(error, where + ": at most " + MAX_FILTERS_PER_RULE + " target filters");
        }
        if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY
                && (target.getFiltersCount() != 0
                || target.getOrder() != eBattleScriptTargetOrder.BATTLE_SCRIPT_ORDER_FORMATION)) {
            return fail(error, where + ": SELF / ENGINE_PICK targets take no filters and no order");
        }
        for (BattleScriptTargetFilter filter : target.getFiltersList()) {
            if (!checkFilter(filter, roleIds, data, where, error)) {
                return false;
            }
        }
        return true;
    }

    private static boolean checkAction(BattleScriptRole role, BattleScriptRule rule,
                                       BattleData data, String where, StringBuilder error) {
        eBattleScriptTargetSide side = rule.getTarget().getSide();
        return switch (rule.getAction()) {
            case BATTLE_ACTION_ATTACK -> {
                if (rule.getSkillId() != 0) {
                    yield fail(error, where + ": ATTACK carries no skill_id");
                }
                if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                        && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
                    yield fail(error, where + ": ATTACK targets ENEMY or ENGINE_PICK");
                }
                yield true;
            }
            case BATTLE_ACTION_DEFEND -> {
                if (rule.getSkillId() != 0) {
                    yield fail(error, where + ": DEFEND carries no skill_id");
                }
                if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_SELF) {
                    yield fail(error, where + ": DEFEND targets SELF");
                }
                yield true;
            }
            case BATTLE_ACTION_SKILL -> {
                if (rule.getSkillId() == 0) {
                    yield fail(error, where + ": SKILL needs skill_id");
                }
                Optional<SkillTable> rowOpt = data.skill(rule.getSkillId());
                if (rowOpt.isEmpty()) {
                    yield fail(error, where + ": unknown skill " + Integer.toUnsignedString(rule.getSkillId()));
                }
                SkillTable row = rowOpt.get();
                if (!BattleRules.isTurnBattleCastableSkill(row)) {
                    yield fail(error, where + ": skill " + Integer.toUnsignedString(rule.getSkillId())
                            + " cannot be cast in turn battles (passive, toggle or channel)");
                }
                if (row.getBattleModel() == 1) {
                    if (row.getClassId() != 0) {
                        if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                                && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER) {
                            yield fail(error, where + ": class skill " + Integer.toUnsignedString(rule.getSkillId())
                                    + " needs a role that accepts players");
                        }
                        if (role.getClassId() != 0 && role.getClassId() != row.getClassId()) {
                            yield fail(error, where + ": class skill " + Integer.toUnsignedString(rule.getSkillId())
                                    + " belongs to class " + Integer.toUnsignedString(row.getClassId())
                                    + " but the role only accepts class " + Integer.toUnsignedString(role.getClassId()));
                        }
                    }
                    if (side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
                        yield true;
                    }
                    yield targetTeamMatches(row.getTargetTeam(), side)
                            ? true : fail(error, where + ": configured skill " + Integer.toUnsignedString(rule.getSkillId())
                                    + " target side does not match its target_team " + row.getTargetTeam());
                }
                if (row.getClassId() == 0) {
                    yield side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                            ? true : fail(error, where + ": legacy skill " + Integer.toUnsignedString(rule.getSkillId())
                                    + " can only target ENEMY");
                }
                if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                        && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER) {
                    yield fail(error, where + ": class skill " + Integer.toUnsignedString(rule.getSkillId())
                            + " needs a role that accepts players");
                }
                if (role.getClassId() != 0 && role.getClassId() != row.getClassId()) {
                    yield fail(error, where + ": class skill " + Integer.toUnsignedString(rule.getSkillId())
                            + " belongs to class " + Integer.toUnsignedString(row.getClassId())
                            + " but the role only accepts class " + Integer.toUnsignedString(role.getClassId()));
                }
                if (side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
                    yield true;
                }
                yield targetTeamMatches(row.getTargetTeam(), side)
                        ? true : fail(error, where + ": class skill " + Integer.toUnsignedString(rule.getSkillId())
                                + " target side does not match its target_team " + row.getTargetTeam());
            }
            default -> fail(error, where + ": action must be ATTACK, SKILL or DEFEND");
        };
    }

    private static boolean targetTeamMatches(int targetTeam, eBattleScriptTargetSide side) {
        return (targetTeam == TARGET_TEAM_ENEMY && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY)
                || (targetTeam == TARGET_TEAM_ALLY && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY)
                || (targetTeam == TARGET_TEAM_SELF && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_SELF);
    }

    private static boolean inRange(int value, int low, int high) {
        return Integer.compareUnsigned(value, low) >= 0 && Integer.compareUnsigned(value, high) <= 0;
    }

    private static int utf8Len(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static BattleScript reject(StringBuilder error, String msg) {
        error.setLength(0);
        error.append(msg);
        return null;
    }

    private static boolean fail(StringBuilder error, String msg) {
        error.setLength(0);
        error.append(msg);
        return false;
    }
}
