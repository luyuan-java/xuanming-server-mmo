package com.game.battle.engine;

import com.game.proto.TacticalPuzzleBattleContext;
import com.game.proto.TacticalPuzzleEnemySpec;
import com.game.proto.TacticalPuzzleSpec;

/**
 * 万象残局坊（UGC 回合解谜工坊 V1）共享白名单规则与确定性数值工具。
 * 与 C++ {@code tactical_puzzle_rules.h}、Go {@code puzzle/spec.go}、Unity {@code TacticalPuzzleModels} 四端逐项同源。
 */
public final class TacticalPuzzleRules {

    public static final int WORKSHOP_DUNGEON_ID = 314;

    public static final int MIN_ROUND_LIMIT = 1;
    public static final int MAX_ROUND_LIMIT = 6;

    public static final int MIN_CHAR_PRESET_ID = 101;
    public static final int MAX_CHAR_PRESET_ID = 103;
    public static final int MIN_PET_PRESET_ID = 201;
    public static final int MAX_PET_PRESET_ID = 203;
    public static final int MAX_FORMATION_ID = 5;

    public static final int MIN_MONSTER_ID = 311;
    public static final int MAX_MONSTER_ID = 316;
    public static final int MIN_ENEMIES = 1;
    public static final int MAX_ENEMIES = 3;

    public static final int PLAYER_MIN_HP_PCT = 15;
    public static final int PLAYER_MAX_HP_PCT = 100;
    public static final int PLAYER_MIN_MP_PCT = 20;
    public static final int PLAYER_MAX_MP_PCT = 100;
    public static final int PLAYER_MAX_SHIELD_PCT = 50;
    public static final int MAX_PLAYER_DEBUFF_KIND = 3;

    public static final int ENEMY_MIN_HP_PCT = 10;
    public static final int ENEMY_MAX_HP_PCT = 150;
    public static final int ENEMY_MAX_SHIELD_PCT = 60;
    public static final int MIN_SPEED_DELTA_PCT = -30;
    public static final int MAX_SPEED_DELTA_PCT = 30;
    public static final int MAX_ENEMY_BUFF_KIND = 4;
    public static final int MAX_LEYLINE_ELEMENT = 5;

    public static boolean isAllowedPresetSkill(int charPresetId, int petPresetId, int skillId) {
        if (skillId == 0) {
            return true;
        }
        boolean inChar = switch (charPresetId) {
            case 101 -> skillId == 10002 || skillId == 10039 || skillId == 10025 || skillId == 10035;
            case 102 -> skillId == 10006 || skillId == 10020 || skillId == 10034 || skillId == 10041;
            case 103 -> skillId == 10010 || skillId == 10033 || skillId == 10040 || skillId == 10026;
            default -> false;
        };
        if (inChar) {
            return true;
        }
        return switch (petPresetId) {
            case 201 -> skillId == 10002 || skillId == 10018 || skillId == 10016 || skillId == 13005;
            case 202 -> skillId == 10006 || skillId == 10019 || skillId == 10034 || skillId == 13006;
            case 203 -> skillId == 10014 || skillId == 10010 || skillId == 11006 || skillId == 11005;
            default -> false;
        };
    }

    public static final int SHIELD_BUFF_ID = 100400;
    public static final int DEBUFF_ATK_DOWN_BUFF_ID = 100031;
    public static final int DEBUFF_DEF_DOWN_BUFF_ID = 130051;
    public static final int DEBUFF_POISON_BUFF_ID = 100190;
    public static final int BUFF_DEF_UP_BUFF_ID = 100140;
    public static final int BUFF_SPD_UP_BUFF_ID = 100160;
    public static final int BUFF_DMG_UP_BUFF_ID = 100250;
    public static final int BUFF_REFLECT_BUFF_ID = 110070;
    public static final int POISON_MAX_HP_PCT = 8;

    private TacticalPuzzleRules() {
    }

    public static int playerDebuffTableId(int kind) {
        return switch (kind) {
            case 1 -> DEBUFF_ATK_DOWN_BUFF_ID;
            case 2 -> DEBUFF_DEF_DOWN_BUFF_ID;
            case 3 -> DEBUFF_POISON_BUFF_ID;
            default -> 0;
        };
    }

    public static int enemyBuffTableId(int kind) {
        return switch (kind) {
            case 1 -> BUFF_DEF_UP_BUFF_ID;
            case 2 -> BUFF_SPD_UP_BUFF_ID;
            case 3 -> BUFF_DMG_UP_BUFF_ID;
            case 4 -> BUFF_REFLECT_BUFF_ID;
            default -> 0;
        };
    }

    public static long scalePct(long baseValue, int pct) {
        long scaled = (baseValue * Integer.toUnsignedLong(pct)) / 100L;
        return Math.max(1L, scaled);
    }

    public static long applySpeedDelta(long baseSpeed, int deltaPct) {
        int clamped = Math.max(MIN_SPEED_DELTA_PCT, Math.min(MAX_SPEED_DELTA_PCT, deltaPct));
        long scaled = (baseSpeed * (100L + clamped)) / 100L;
        return Math.max(1L, scaled);
    }

    public static boolean isValidSpec(TacticalPuzzleSpec spec) {
        if (spec == null) {
            return false;
        }
        int roundLimit = spec.getRoundLimit();
        if (roundLimit < MIN_ROUND_LIMIT || roundLimit > MAX_ROUND_LIMIT) {
            return false;
        }
        int charPreset = spec.getCharacterPresetId();
        if (charPreset < MIN_CHAR_PRESET_ID || charPreset > MAX_CHAR_PRESET_ID) {
            return false;
        }
        int petPreset = spec.getPetPresetId();
        if (petPreset != 0 && (petPreset < MIN_PET_PRESET_ID || petPreset > MAX_PET_PRESET_ID)) {
            return false;
        }
        int formationId = spec.getFormationId();
        if (formationId < 0 || formationId > MAX_FORMATION_ID) {
            return false;
        }
        int playerHp = spec.getPlayerInitialHpPct();
        if (playerHp < PLAYER_MIN_HP_PCT || playerHp > PLAYER_MAX_HP_PCT) {
            return false;
        }
        int playerMp = spec.getPlayerInitialMpPct();
        if (playerMp < PLAYER_MIN_MP_PCT || playerMp > PLAYER_MAX_MP_PCT) {
            return false;
        }
        int playerShield = spec.getPlayerInitialShieldPct();
        if (playerShield < 0 || playerShield > PLAYER_MAX_SHIELD_PCT) {
            return false;
        }
        int playerDebuff = spec.getPlayerInitialDebuffKind();
        if (playerDebuff < 0 || playerDebuff > MAX_PLAYER_DEBUFF_KIND) {
            return false;
        }
        int leylineVal = spec.getLeylineElementValue();
        if (leylineVal < 0 || leylineVal > MAX_LEYLINE_ELEMENT) {
            return false;
        }
        if (!isAllowedPresetSkill(charPreset, petPreset, spec.getBannedSkillId())) {
            return false;
        }
        int enemyCount = spec.getEnemiesCount();
        if (enemyCount < MIN_ENEMIES || enemyCount > MAX_ENEMIES) {
            return false;
        }
        for (TacticalPuzzleEnemySpec enemy : spec.getEnemiesList()) {
            int monId = enemy.getMonsterTableId();
            if (monId < MIN_MONSTER_ID || monId > MAX_MONSTER_ID) {
                return false;
            }
            int hpPct = enemy.getInitialHpPct();
            if (hpPct < ENEMY_MIN_HP_PCT || hpPct > ENEMY_MAX_HP_PCT) {
                return false;
            }
            int shieldPct = enemy.getInitialShieldPct();
            if (shieldPct < 0 || shieldPct > ENEMY_MAX_SHIELD_PCT) {
                return false;
            }
            int speedDelta = enemy.getSpeedDeltaPct();
            if (speedDelta < MIN_SPEED_DELTA_PCT || speedDelta > MAX_SPEED_DELTA_PCT) {
                return false;
            }
            int buffKind = enemy.getInitialBuffKind();
            if (buffKind < 0 || buffKind > MAX_ENEMY_BUFF_KIND) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidContext(TacticalPuzzleBattleContext ctx) {
        return ctx != null
                && ctx.getPuzzleId() != 0L
                && ctx.getSpecRevision() != 0
                && ctx.hasSpec()
                && isValidSpec(ctx.getSpec());
    }

    /** 按 (puzzleId, specRevision) 确定性派生非零战斗 RNG 种子（与 Go puzzle.DeterministicSeed 逐位同源）。 */
    public static long deterministicSeed(long puzzleId, int specRevision) {
        long x = puzzleId ^ (Integer.toUnsignedLong(specRevision) * 0x9E3779B97F4A7C15L) ^ 0xA0761D6478BD642FL;
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x = x ^ (x >>> 31);
        return x == 0L ? 1L : x;
    }
}
