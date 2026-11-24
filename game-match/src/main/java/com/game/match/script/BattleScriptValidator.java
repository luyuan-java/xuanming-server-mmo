package com.game.match.script;

import com.game.proto.BattleScript;
import com.game.proto.BattleScriptCondition;
import com.game.proto.BattleScriptRole;
import com.game.proto.BattleScriptRule;
import com.game.proto.BattleScriptTarget;
import com.game.proto.BattleScriptTargetFilter;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleScriptCondition;
import com.game.proto.eBattleScriptSide;
import com.game.proto.eBattleScriptTargetFilter;
import com.game.proto.eBattleScriptTargetOrder;
import com.game.proto.eBattleScriptTargetSide;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 战斗剧本校验与规范化（逐条对齐 Go {@code go/match/internal/battlescript/validate.go}）。 */
public final class BattleScriptValidator {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ROLES = 8;
    public static final int MAX_RULES_PER_ROLE = 16;
    public static final int MAX_CONDITIONS_PER_RULE = 2;
    public static final int MAX_FILTERS_PER_RULE = 3;
    public static final int MAX_ROLE_MEMBERS = 10;
    public static final int MAX_ROUND = 1000;
    public static final int MAX_UNIT_COUNT = 20;
    public static final int MAX_CLASS_ID = 1000;
    public static final int PERMYRIAD_FULL = 10_000;
    public static final int MAX_NAME_BYTES = 64;
    public static final int MAX_DESCRIPTION_BYTES = 512;
    public static final int MAX_NOTE_BYTES = 256;

    private static final int SKILL_TYPE_BIT_PASSIVE = 0;
    private static final int SKILL_TYPE_BIT_CHANNEL = 2;
    private static final int SKILL_TYPE_BIT_TOGGLE = 3;
    private static final int SKILL_COST_RESOURCE_MANA = 1;
    private static final int TARGET_TEAM_ENEMY = 1;
    private static final int TARGET_TEAM_ALLY = 2;
    private static final int TARGET_TEAM_SELF = 3;

    private static final Set<Integer> STATUS_BUFF_TYPES = Set.of(
            10, 11, 13, 30, 31, 32, 34, 40, 41, 42, 50, 51, 52);

    private BattleScriptValidator() {
    }

    /** 校验查询配表的最小视图（生产用 {@link #tablesFrom(ConfigTables)}，单测可传内存实现）。 */
    public interface Tables {
        Optional<SkillTable> skill(int id);
        boolean hasBuff(int id);
        boolean hasMonster(int id);
        boolean hasDungeon(int id);
    }

    public static Tables tablesFrom(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        return new Tables() {
            @Override public Optional<SkillTable> skill(int id) { return config.skill().find(id); }
            @Override public boolean hasBuff(int id) { return config.buff().find(id).isPresent(); }
            @Override public boolean hasMonster(int id) { return config.monster().find(id).isPresent(); }
            @Override public boolean hasDungeon(int id) { return config.dungeon().find(id).isPresent(); }
        };
    }

    public static boolean isStatusBuffType(int buffType) {
        return STATUS_BUFF_TYPES.contains(buffType);
    }

    public static boolean isKnownMatchMode(int matchMode) {
        return matchMode == 0 || MatchMode.forNumber(matchMode) != null;
    }

    public static boolean isPveMatchMode(int matchMode) {
        return matchMode == MatchMode.MATCH_MODE_PVE_SOLO_VALUE
                || matchMode == MatchMode.MATCH_MODE_PVE_TEAM_VALUE;
    }

    public static boolean isRatedMode(int matchMode) {
        return matchMode == MatchMode.MATCH_MODE_1V1_VALUE
                || matchMode == MatchMode.MATCH_MODE_5V5_VALUE
                || matchMode == MatchMode.MATCH_MODE_PVP_TEAM_VALUE;
    }

    /** 校验与绑定无关的结构/上限/引用，并返回角色按 order、规则按 priority 排好序的新副本。 */
    public static BattleScript normalize(BattleScript script, Tables tables) {
        Objects.requireNonNull(tables, "tables");
        if (script == null) {
            throw new IllegalArgumentException("script is missing");
        }
        String where = "script " + Integer.toUnsignedString(script.getScriptId());
        if (script.getSchemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException(where + ": unsupported schema_version " + script.getSchemaVersion());
        }
        if (utf8Bytes(script.getName()) > MAX_NAME_BYTES || utf8Bytes(script.getDescription()) > MAX_DESCRIPTION_BYTES) {
            throw new IllegalArgumentException(where + ": name or description too long");
        }
        int roleCount = script.getRolesCount();
        if (roleCount == 0 || roleCount > MAX_ROLES) {
            throw new IllegalArgumentException(where + ": needs 1.." + MAX_ROLES + " roles");
        }

        Set<Integer> roleIds = new HashSet<>(roleCount * 2);
        Set<Integer> roleOrders = new HashSet<>(roleCount * 2);
        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = where + " role " + Integer.toUnsignedString(role.getRoleId());
            if (role.getRoleId() == 0 || !roleIds.add(role.getRoleId())) {
                throw new IllegalArgumentException(roleWhere + ": role_id must be non-zero and unique");
            }
            if (!roleOrders.add(role.getOrder())) {
                throw new IllegalArgumentException(roleWhere + ": duplicate role order " + Integer.toUnsignedString(role.getOrder()));
            }
            checkRoleHeader(role, tables, roleWhere);
        }

        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = where + " role " + Integer.toUnsignedString(role.getRoleId());
            Set<Integer> priorities = new HashSet<>(role.getRulesCount() * 2);
            for (BattleScriptRule rule : role.getRulesList()) {
                String ruleWhere = roleWhere + " rule " + Integer.toUnsignedString(rule.getPriority());
                if (!priorities.add(rule.getPriority())) {
                    throw new IllegalArgumentException(ruleWhere + ": duplicate rule priority");
                }
                if (utf8Bytes(rule.getNote()) > MAX_NOTE_BYTES) {
                    throw new IllegalArgumentException(ruleWhere + ": note too long");
                }
                if (rule.getConditionsCount() > MAX_CONDITIONS_PER_RULE) {
                    throw new IllegalArgumentException(ruleWhere + ": at most " + MAX_CONDITIONS_PER_RULE + " conditions");
                }
                for (BattleScriptCondition c : rule.getConditionsList()) {
                    checkCondition(c, ruleWhere);
                }
                checkTarget(rule.getTarget(), roleIds, tables, ruleWhere);
                checkAction(role, rule, tables, ruleWhere);
            }
        }

        List<BattleScriptRole> sortedRoles = new ArrayList<>(roleCount);
        for (BattleScriptRole role : script.getRolesList()) {
            List<BattleScriptRule> sortedRules = new ArrayList<>(role.getRulesList());
            sortedRules.sort((a, b) -> Integer.compareUnsigned(a.getPriority(), b.getPriority()));
            sortedRoles.add(role.toBuilder().clearRules().addAllRules(sortedRules).build());
        }
        sortedRoles.sort((a, b) -> Integer.compareUnsigned(a.getOrder(), b.getOrder()));
        return script.toBuilder().clearRoles().addAllRoles(sortedRoles).build();
    }

    /** 校验剧本是否可用于指定一方（PLAYER / MONSTER）。 */
    public static void checkSide(BattleScript script, eBattleScriptSide side, Tables tables) {
        String where = "script " + Integer.toUnsignedString(script.getScriptId());
        if (side != eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER
                && side != eBattleScriptSide.BATTLE_SCRIPT_SIDE_MONSTER) {
            throw new IllegalArgumentException(where + ": side must be PLAYER or MONSTER");
        }
        for (BattleScriptRole role : script.getRolesList()) {
            String roleWhere = where + " role " + Integer.toUnsignedString(role.getRoleId());
            if (side == eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER) {
                if (role.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER || role.getMonsterId() != 0) {
                    throw new IllegalArgumentException(roleWhere + ": player side roles cannot accept monsters");
                }
                continue;
            }
            if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                    && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER) {
                throw new IllegalArgumentException(roleWhere + ": monster side roles can only accept monsters");
            }
            if (role.getClassId() != 0) {
                throw new IllegalArgumentException(roleWhere + ": monster side roles cannot require a class");
            }
            for (BattleScriptRule rule : role.getRulesList()) {
                if (rule.getAction() != eBattleActionType.BATTLE_ACTION_SKILL) {
                    continue;
                }
                String ruleWhere = roleWhere + " rule " + Integer.toUnsignedString(rule.getPriority());
                SkillTable row = tables.skill(rule.getSkillId()).orElse(null);
                if (row == null || row.getClassId() != 0) {
                    throw new IllegalArgumentException(ruleWhere + ": monsters can only cast legacy skills");
                }
                if (legacyManaCost(row) != 0L) {
                    throw new IllegalArgumentException(ruleWhere + ": monsters have no mana but skill "
                            + rule.getSkillId() + " costs mana");
                }
            }
        }
    }

    private static void checkRoleHeader(BattleScriptRole role, Tables tables, String where) {
        if (!role.getName().isEmpty() && utf8Bytes(role.getName()) > MAX_NAME_BYTES) {
            throw new IllegalArgumentException(where + ": name too long");
        }
        if (eBattleActorType.forNumber(role.getActorTypeValue()) == null) {
            throw new IllegalArgumentException(where + ": unknown actor_type");
        }
        if (Integer.compareUnsigned(role.getClassId(), MAX_CLASS_ID) > 0) {
            throw new IllegalArgumentException(where + ": class_id out of range");
        }
        if (role.getClassId() != 0
                && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER) {
            throw new IllegalArgumentException(where + ": class_id only applies to players");
        }
        if (role.getMonsterId() != 0) {
            if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                    && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER) {
                throw new IllegalArgumentException(where + ": monster_id only applies to monsters");
            }
            if (role.getClassId() != 0) {
                throw new IllegalArgumentException(where + ": a role cannot require both a class and a monster");
            }
            if (!tables.hasMonster(role.getMonsterId())) {
                throw new IllegalArgumentException(where + ": unknown monster " + role.getMonsterId());
            }
        }
        if (Integer.compareUnsigned(role.getMaxMembers(), MAX_ROLE_MEMBERS) > 0) {
            throw new IllegalArgumentException(where + ": max_members above " + MAX_ROLE_MEMBERS);
        }
        int rules = role.getRulesCount();
        if (rules == 0 || rules > MAX_RULES_PER_ROLE) {
            throw new IllegalArgumentException(where + ": needs 1.." + MAX_RULES_PER_ROLE + " rules");
        }
    }

    private static void checkCondition(BattleScriptCondition c, String where) {
        eBattleScriptCondition kind = eBattleScriptCondition.forNumber(c.getKindValue());
        if (kind == null) {
            throw new IllegalArgumentException(where + ": unknown condition kind " + c.getKindValue());
        }
        switch (kind) {
            case BATTLE_SCRIPT_COND_SELF_HP_BELOW -> {
                if (!inRange(c.getP1(), 1, PERMYRIAD_FULL) || c.getP2() != 0) {
                    throw new IllegalArgumentException(where + ": SELF_HP_BELOW needs p1 in 1..10000 and p2 = 0");
                }
            }
            case BATTLE_SCRIPT_COND_ROUND_AT_LEAST -> {
                if (!inRange(c.getP1(), 1, MAX_ROUND) || c.getP2() != 0) {
                    throw new IllegalArgumentException(where + ": ROUND_AT_LEAST needs p1 in 1.." + MAX_ROUND + " and p2 = 0");
                }
            }
            case BATTLE_SCRIPT_COND_ROUND_EVERY -> {
                if (!inRange(c.getP1(), 1, MAX_ROUND) || !inRange(c.getP2(), 1, MAX_ROUND)) {
                    throw new IllegalArgumentException(where + ": ROUND_EVERY needs period p1 and first round p2 in 1.." + MAX_ROUND);
                }
            }
            case BATTLE_SCRIPT_COND_ENEMY_COUNT_AT_LEAST -> {
                if (!inRange(c.getP1(), 1, MAX_UNIT_COUNT) || c.getP2() != 0) {
                    throw new IllegalArgumentException(where + ": ENEMY_COUNT_AT_LEAST needs p1 in 1.." + MAX_UNIT_COUNT + " and p2 = 0");
                }
            }
            default -> throw new IllegalArgumentException(where + ": unknown condition kind " + c.getKindValue());
        }
    }

    private static void checkTarget(BattleScriptTarget t, Set<Integer> roleIds, Tables tables, String where) {
        eBattleScriptTargetSide side = eBattleScriptTargetSide.forNumber(t.getSideValue());
        if (side == null || side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_UNSPECIFIED) {
            throw new IllegalArgumentException(where + ": target side must be SELF, ENEMY, ALLY or ENGINE_PICK");
        }
        if (eBattleScriptTargetOrder.forNumber(t.getOrderValue()) == null) {
            throw new IllegalArgumentException(where + ": unknown target order " + t.getOrderValue());
        }
        if (t.getFiltersCount() > MAX_FILTERS_PER_RULE) {
            throw new IllegalArgumentException(where + ": at most " + MAX_FILTERS_PER_RULE + " target filters");
        }
        boolean pickable = side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                || side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY;
        if (!pickable && (t.getFiltersCount() != 0 || t.getOrder() != eBattleScriptTargetOrder.BATTLE_SCRIPT_ORDER_FORMATION)) {
            throw new IllegalArgumentException(where + ": SELF / ENGINE_PICK targets take no filters and no order");
        }
        for (BattleScriptTargetFilter f : t.getFiltersList()) {
            checkFilter(f, roleIds, tables, where);
        }
    }

    private static void checkFilter(BattleScriptTargetFilter f, Set<Integer> roleIds, Tables tables, String where) {
        int p1 = f.getP1();
        eBattleScriptTargetFilter kind = eBattleScriptTargetFilter.forNumber(f.getKindValue());
        if (kind == null) {
            throw new IllegalArgumentException(where + ": unknown filter kind " + f.getKindValue());
        }
        switch (kind) {
            case BATTLE_SCRIPT_FILTER_HP_BELOW -> {
                if (!inRange(p1, 1, PERMYRIAD_FULL)) {
                    throw new IllegalArgumentException(where + ": HP_BELOW needs p1 in 1..10000");
                }
            }
            case BATTLE_SCRIPT_FILTER_HAS_BUFF_TYPE -> {
                if (!isStatusBuffType(p1)) {
                    throw new IllegalArgumentException(where + ": HAS_BUFF_TYPE " + p1 + " is not a status type");
                }
            }
            case BATTLE_SCRIPT_FILTER_HAS_BUFF -> {
                if (p1 == 0 || !tables.hasBuff(p1)) {
                    throw new IllegalArgumentException(where + ": unknown buff " + p1);
                }
            }
            case BATTLE_SCRIPT_FILTER_IS_MONSTER -> {
                if (p1 == 0 || !tables.hasMonster(p1)) {
                    throw new IllegalArgumentException(where + ": unknown monster " + p1);
                }
            }
            case BATTLE_SCRIPT_FILTER_IS_CLASS -> {
                if (!inRange(p1, 1, MAX_CLASS_ID)) {
                    throw new IllegalArgumentException(where + ": IS_CLASS needs a class id");
                }
            }
            case BATTLE_SCRIPT_FILTER_IS_ACTOR_TYPE -> {
                if (p1 != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER_VALUE
                        && p1 != eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER_VALUE
                        && p1 != eBattleActorType.BATTLE_ACTOR_TYPE_PET_VALUE) {
                    throw new IllegalArgumentException(where + ": IS_ACTOR_TYPE needs 1 (player), 2 (monster) or 3 (pet)");
                }
            }
            case BATTLE_SCRIPT_FILTER_CLAIMED_BY_ROLE -> {
                if (p1 != 0 && !roleIds.contains(p1)) {
                    throw new IllegalArgumentException(where + ": CLAIMED_BY_ROLE refers to unknown role " + p1);
                }
            }
            default -> throw new IllegalArgumentException(where + ": unknown filter kind " + f.getKindValue());
        }
    }

    private static void checkAction(BattleScriptRole role, BattleScriptRule rule, Tables tables, String where) {
        eBattleScriptTargetSide side = rule.getTarget().getSide();
        switch (rule.getAction()) {
            case BATTLE_ACTION_ATTACK -> {
                if (rule.getSkillId() != 0) {
                    throw new IllegalArgumentException(where + ": ATTACK carries no skill_id");
                }
                if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY
                        && side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
                    throw new IllegalArgumentException(where + ": ATTACK targets ENEMY or ENGINE_PICK");
                }
            }
            case BATTLE_ACTION_DEFEND -> {
                if (rule.getSkillId() != 0) {
                    throw new IllegalArgumentException(where + ": DEFEND carries no skill_id");
                }
                if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_SELF) {
                    throw new IllegalArgumentException(where + ": DEFEND targets SELF");
                }
            }
            case BATTLE_ACTION_SKILL -> checkSkillAction(role, rule, tables, where);
            default -> throw new IllegalArgumentException(where + ": action must be ATTACK, SKILL or DEFEND");
        }
    }

    private static void checkSkillAction(BattleScriptRole role, BattleScriptRule rule, Tables tables, String where) {
        eBattleScriptTargetSide side = rule.getTarget().getSide();
        if (rule.getSkillId() == 0) {
            throw new IllegalArgumentException(where + ": SKILL needs skill_id");
        }
        SkillTable row = tables.skill(rule.getSkillId())
                .orElseThrow(() -> new IllegalArgumentException(where + ": unknown skill " + rule.getSkillId()));
        if (!castable(row)) {
            throw new IllegalArgumentException(where + ": skill " + rule.getSkillId()
                    + " cannot be cast in turn battles (passive, toggle or channel)");
        }
        if (row.getClassId() == 0) {
            if (side != eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY) {
                throw new IllegalArgumentException(where + ": legacy skill " + rule.getSkillId() + " can only target ENEMY");
            }
            return;
        }
        if (role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_NONE
                && role.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER) {
            throw new IllegalArgumentException(where + ": class skill " + rule.getSkillId()
                    + " needs a role that accepts players");
        }
        if (role.getClassId() != 0 && role.getClassId() != row.getClassId()) {
            throw new IllegalArgumentException(where + ": class skill " + rule.getSkillId()
                    + " belongs to class " + row.getClassId() + " but the role only accepts class " + role.getClassId());
        }
        if (side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENGINE_PICK) {
            return;
        }
        int team = row.getTargetTeam();
        boolean matches = (team == TARGET_TEAM_ENEMY && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY)
                || (team == TARGET_TEAM_ALLY && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ALLY)
                || (team == TARGET_TEAM_SELF && side == eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_SELF);
        if (!matches) {
            throw new IllegalArgumentException(where + ": class skill " + rule.getSkillId()
                    + " target side does not match its target_team " + team);
        }
    }

    private static boolean castable(SkillTable row) {
        if (!row.getPassiveStat().isEmpty()) {
            return false;
        }
        for (int bit : row.getSkillTypeList()) {
            if (bit == SKILL_TYPE_BIT_PASSIVE || bit == SKILL_TYPE_BIT_TOGGLE || bit == SKILL_TYPE_BIT_CHANNEL) {
                return false;
            }
        }
        return true;
    }

    private static long legacyManaCost(SkillTable row) {
        long cost = 0L;
        for (Skillcost_resource entry : row.getCostResourceList()) {
            if (entry.getCostResourceId() == SKILL_COST_RESOURCE_MANA) {
                cost += Integer.toUnsignedLong(entry.getCostResourceCost());
            }
        }
        return cost;
    }

    private static boolean inRange(int value, int low, int high) {
        return Integer.compareUnsigned(value, low) >= 0 && Integer.compareUnsigned(value, high) <= 0;
    }

    private static int utf8Bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
