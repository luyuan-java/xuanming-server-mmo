package com.game.battle.engine;

import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.eBattleOutcome;
import java.util.ArrayList;
import java.util.List;

/**
 * 五行试炼志 V2 纯判定与常量（Scene 与 Battle 共用，零 ECS 依赖，对齐 C++ {@code trial_journal_rules.h}）。
 */
public final class TrialJournalRules {

    /** 仅自用横向玩具固定物品模板 ID（Item 3403/3404）。 */
    public static final int BOUND_REFORGE_SAND_ITEM_ID = 3403;
    public static final int BOUND_ARTIFACT_VOUCHER_ITEM_ID = 3404;

    /** 八件 V1 法宝效果范围（EquipArtifact.id 1..8）及佩戴/队友上限。 */
    public static final int MIN_ARTIFACT_EFFECT_ID = 1;
    public static final int MAX_ARTIFACT_EFFECT_ID = 8;
    public static final int MAX_ARTIFACT_SLOTS = 2;
    public static final int MAX_COOP_TEAMMATES = 4;

    /** 卷册编号（TrialJournalEntry.chapter_id / TrialJournalMilestone.scope_chapter_id）。 */
    public static final int SCOPE_ALL_PERMANENT = 0;
    public static final int CHAPTER_BREAKTHROUGH = 1;
    public static final int CHAPTER_THREE_REALMS = 2;
    public static final int CHAPTER_SEASONAL = 3;

    /** 准入模式（TrialJournalEntry.unlock_mode）。 */
    public static final int UNLOCK_MODE_OPEN = 0;
    public static final int UNLOCK_MODE_REQUIRE_ANY_NORMAL_BREAKTHROUGH_CLEAR = 1;

    /** 战法印定位分类（TrialJournalSeal.seal_slot_kind）。 */
    public static final int SLOT_KIND_CLEAR = 1;
    public static final int SLOT_KIND_GUARD = 2;
    public static final int SLOT_KIND_TACTIC = 3;
    public static final int SLOT_KIND_SPEED = 4;

    /** 战法印权威判定条件（TrialJournalSeal.condition_type）。 */
    public static final int COND_CLEAR = 1;
    public static final int COND_SPEED_CLEAR = 2;
    public static final int COND_FLAWLESS_SELF_AND_PET = 3;
    public static final int COND_NO_BOSS_GUARD_HEAL = 4;
    public static final int COND_BREAK_GUARD_STANCE = 5;
    public static final int COND_WIND_DEFEND = 6;
    public static final int COND_BELL_HARMONY = 7;
    public static final int COND_TIDE_FINISH = 8;
    public static final int COND_GUARD_RESCUED = 9;
    public static final int COND_POLARITY_WIN = 10;
    public static final int COND_ARTIFACT_SHIELD_BREAK = 11;
    public static final int COND_ANTI_CONTROL_OR_SEASON_SKILL = 12;

    private TrialJournalRules() {
    }

    public static boolean isPlayerVictory(eBattleOutcome outcome, int playerTeamIndex) {
        return (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && playerTeamIndex == 0)
                || (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && playerTeamIndex == 1);
    }

    public static boolean isCompletedNonVoidOutcome(eBattleOutcome outcome) {
        return outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                || outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                || outcome == eBattleOutcome.BATTLE_OUTCOME_DRAW;
    }

    public static boolean isValidTrialSettlement(BattleSettlementData settlement) {
        if (settlement == null || settlement.getBattleId() == 0L || settlement.getPlayerId() == 0L
                || settlement.getFled() || settlement.getTotalRounds() == 0
                || Integer.compareUnsigned(settlement.getPlayerTeamIndex(), 1) > 0
                || !isCompletedNonVoidOutcome(settlement.getOutcome())
                || !settlement.hasTrialFacts()) {
            return false;
        }
        BattleTrialJournalFacts facts = settlement.getTrialFacts();
        int humanCount = facts.getInitialHumanCount();
        return facts.getJournalEntryId() != 0 && facts.getTrialRuleVersion() != 0
                && humanCount >= 1 && humanCount <= 5;
    }

    public static boolean isSoloTrial(BattleTrialJournalFacts facts) {
        return facts != null && facts.getInitialHumanCount() == 1;
    }

    public static boolean isCoopTrial(BattleTrialJournalFacts facts) {
        return facts != null && facts.getInitialHumanCount() >= 2 && facts.getInitialHumanCount() <= 5;
    }

    public static List<Integer> normalizeArtifactEffectIds(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(Math.min(raw.size(), MAX_ARTIFACT_SLOTS + 2));
        for (int effectId : raw) {
            if (effectId >= MIN_ARTIFACT_EFFECT_ID && effectId <= MAX_ARTIFACT_EFFECT_ID) {
                out.add(effectId);
            }
        }
        out.sort(Integer::compareUnsigned);
        if (out.size() > MAX_ARTIFACT_SLOTS) {
            return List.copyOf(out.subList(0, MAX_ARTIFACT_SLOTS));
        }
        return List.copyOf(out);
    }

    /** 校验一条战法印是否在本次已验证的终局结算中达成。 */
    public static boolean evaluateSealCondition(int conditionType, int conditionParam, BattleSettlementData settlement) {
        if (!isValidTrialSettlement(settlement)) {
            return false;
        }
        BattleTrialJournalFacts facts = settlement.getTrialFacts();
        boolean won = isPlayerVictory(settlement.getOutcome(), settlement.getPlayerTeamIndex());
        return switch (conditionType) {
            case COND_CLEAR -> won;
            case COND_SPEED_CLEAR -> won && conditionParam > 0
                    && Integer.compareUnsigned(settlement.getTotalRounds(), conditionParam) <= 0;
            case COND_FLAWLESS_SELF_AND_PET -> won && !facts.getSelfOrPetEverDied();
            case COND_NO_BOSS_GUARD_HEAL -> won && facts.getBossGuardHealTotal() == 0L;
            case COND_BREAK_GUARD_STANCE -> won && (facts.getGuardStanceDispelled()
                    || (facts.getTeamDealtPhysicalDirectDamage() && facts.getTeamDealtMagicDirectDamage()));
            case COND_WIND_DEFEND -> won && !facts.getBossWindHitUnprotectedDeath()
                    && (conditionParam == 0
                    || Integer.compareUnsigned(settlement.getTotalRounds(), conditionParam) <= 0);
            case COND_BELL_HARMONY -> won && facts.getDealtYangBellPhysicalDamage()
                    && facts.getDealtYinDrumMagicDamage();
            case COND_TIDE_FINISH -> won && facts.getFinishedInEbbTide();
            case COND_GUARD_RESCUED -> won && facts.getGuardSurvived();
            case COND_POLARITY_WIN -> won;
            case COND_ARTIFACT_SHIELD_BREAK -> (won || settlement.getParticipated())
                    && facts.getSelfTriggeredShieldBreak();
            case COND_ANTI_CONTROL_OR_SEASON_SKILL -> (won || settlement.getParticipated())
                    && facts.getSelfTriggeredAntiControlOrSeasonSkill();
            default -> false;
        };
    }

    public static boolean isValidConditionType(int conditionType) {
        return conditionType >= COND_CLEAR && conditionType <= COND_ANTI_CONTROL_OR_SEASON_SKILL;
    }

    public static boolean isValidSealSlotKind(int slotKind) {
        return slotKind >= SLOT_KIND_CLEAR && slotKind <= SLOT_KIND_SPEED;
    }
}
