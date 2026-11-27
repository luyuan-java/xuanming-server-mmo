package com.game.battle.engine;

import com.game.proto.TournamentLeylineElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 五行玩法三大扩展 V1 纯规则表（对齐 C++ {@code wuxing_expansion_rules.h}）：
 * <ul>
 *   <li>模块 A：5 套全员免费道家阵法（1 天覆/2 地载/3 风扬/4 云垂/5 虎翼，循环互克 +5% 直伤）与人宠五行相生共鸣（每第 3/6/9 回合 +10%）；</li>
 *   <li>模块 B：五行幻境 10 层轻肉鸽周常爬塔（UTC+8 周一 05:00 换周、15 枚局内符印 101..503、层数副本与怪物成长映射、确定性三选一抽印）；</li>
 *   <li>模块 C：战报配装收藏夹上限（12 条）与自定义切磋回合上限白名单（0/8/12/20）。</li>
 * </ul>
 */
public final class WuxingExpansionRules {

    public static final int ELEMENT_NONE = 0;
    public static final int ELEMENT_METAL = 1;
    public static final int ELEMENT_WOOD = 2;
    public static final int ELEMENT_WATER = 3;
    public static final int ELEMENT_FIRE = 4;
    public static final int ELEMENT_EARTH = 5;

    public static final int MAX_FORMATION_ID = 5;
    public static final int TOWER_MAX_FLOORS = 10;
    public static final int TOWER_OFFER_COUNT = 3;
    public static final int MAX_SAVED_BUILD_BOOKMARKS = 12;
    public static final int SYNERGY_INTERVAL_ROUNDS = 3;
    public static final int SYNERGY_BONUS_PCT = 10;
    public static final int FORMATION_COUNTER_BONUS_PCT = 5;
    public static final long FIRE_CRIT_BURN_CAP_PER_HIT = 1500L;

    public record FormationDef(int id, int element, int countersId,
                               int damageBonusPct, int damageTakenReducePct, int speedBonusPct,
                               int healShieldBonusPct, int controlResistPct, int roundManaRegenPct,
                               int critBonusPct, int petDamageBonusPct, int openingShieldPct) {
        public static final FormationDef NONE = new FormationDef(0, ELEMENT_NONE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record TowerSigilDef(int id, int element, int rarity,
                                int damagePct, int critPct, int critDamagePct, int shieldBreakBonusPct,
                                int healShieldPct, int petDamagePct, int roundHpRegenPct, int roundManaRegenPct,
                                int controlResistPct, int onHitManaRegenPct, int damageTakenReducePct,
                                int first3RoundsDamagePct, int singleTargetDamagePct, int critBurnMaxHpPct,
                                int openingShieldPct, int reflectPct) {
        public static final TowerSigilDef NONE = new TowerSigilDef(0, ELEMENT_NONE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static final class TowerSigilTotals {
        public int damagePct;
        public int critPct;
        public int critDamagePct;
        public int shieldBreakBonusPct;
        public int healShieldPct;
        public int petDamagePct;
        public int roundHpRegenPct;
        public int roundManaRegenPct;
        public int controlResistPct;
        public int onHitManaRegenPct;
        public int damageTakenReducePct;
        public int first3RoundsDamagePct;
        public int singleTargetDamagePct;
        public int critBurnMaxHpPct;
        public int openingShieldPct;
        public int reflectPct;

        public void add(TowerSigilDef d) {
            damagePct += d.damagePct();
            critPct += d.critPct();
            critDamagePct += d.critDamagePct();
            shieldBreakBonusPct += d.shieldBreakBonusPct();
            healShieldPct += d.healShieldPct();
            petDamagePct += d.petDamagePct();
            roundHpRegenPct += d.roundHpRegenPct();
            roundManaRegenPct += d.roundManaRegenPct();
            controlResistPct += d.controlResistPct();
            onHitManaRegenPct += d.onHitManaRegenPct();
            damageTakenReducePct += d.damageTakenReducePct();
            first3RoundsDamagePct += d.first3RoundsDamagePct();
            singleTargetDamagePct += d.singleTargetDamagePct();
            critBurnMaxHpPct += d.critBurnMaxHpPct();
            openingShieldPct += d.openingShieldPct();
            reflectPct += d.reflectPct();
        }
    }

    private static final List<FormationDef> FORMATIONS = List.of(
            // 1 天覆阵（金）：直伤 +5%、速度 +2%，克地载阵(2)
            new FormationDef(1, ELEMENT_METAL, 2, 5, 0, 2, 0, 0, 0, 0, 0, 0),
            // 2 地载阵（土）：受击减伤 +6%、开局 4% 护盾，克风扬阵(3)
            new FormationDef(2, ELEMENT_EARTH, 3, 0, 6, 0, 0, 0, 0, 0, 0, 4),
            // 3 风扬阵（木）：速度 +6%、治疗与护盾 +4%，克云垂阵(4)
            new FormationDef(3, ELEMENT_WOOD, 4, 0, 0, 6, 4, 0, 0, 0, 0, 0),
            // 4 云垂阵（水）：控制抗性 +8%、回合初回复 3% 法力，克虎翼阵(5)
            new FormationDef(4, ELEMENT_WATER, 5, 0, 0, 0, 0, 8, 3, 0, 0, 0),
            // 5 虎翼阵（火）：暴击率 +4%、出战灵宠伤害 +8%，克天覆阵(1)
            new FormationDef(5, ELEMENT_FIRE, 1, 0, 0, 0, 0, 0, 0, 4, 8, 0)
    );

    private static final List<TowerSigilDef> TOWER_SIGILS = List.of(
            // 金系（101..103）
            new TowerSigilDef(101, ELEMENT_METAL, 1, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(102, ELEMENT_METAL, 2, 0, 12, 0, 25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(103, ELEMENT_METAL, 3, 14, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            // 木系（201..203）
            new TowerSigilDef(201, ELEMENT_WOOD, 1, 0, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(202, ELEMENT_WOOD, 2, 0, 0, 0, 0, 0, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(203, ELEMENT_WOOD, 3, 0, 0, 0, 0, 0, 22, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            // 水系（301..303）
            new TowerSigilDef(301, ELEMENT_WATER, 1, 0, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(302, ELEMENT_WATER, 2, 0, 0, 0, 0, 0, 0, 0, 0, 15, 4, 0, 0, 0, 0, 0, 0),
            new TowerSigilDef(303, ELEMENT_WATER, 3, 0, 0, 0, 0, 0, 0, 0, 10, 20, 0, 8, 0, 0, 0, 0, 0),
            // 火系（401..403）
            new TowerSigilDef(401, ELEMENT_FIRE, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 14, 0, 0, 0, 0),
            new TowerSigilDef(402, ELEMENT_FIRE, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0),
            new TowerSigilDef(403, ELEMENT_FIRE, 3, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0),
            // 土系（501..503）
            new TowerSigilDef(501, ELEMENT_EARTH, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 0),
            new TowerSigilDef(502, ELEMENT_EARTH, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12),
            new TowerSigilDef(503, ELEMENT_EARTH, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 20, 15)
    );

    private WuxingExpansionRules() {
    }

    public static int elementOfClass(int classId) {
        return classId == 0 ? ELEMENT_NONE : (int) ((Integer.toUnsignedLong(classId) - 1L) % 5L) + 1;
    }

    public static int elementOfPet(int petTableId) {
        return petTableId == 0 ? ELEMENT_NONE : (int) ((Integer.toUnsignedLong(petTableId) - 1L) % 5L) + 1;
    }

    /** 五行相生链：金(1)生水(3) → 水(3)生木(2) → 木(2)生火(4) → 火(4)生土(5) → 土(5)生金(1)。 */
    public static boolean elementGenerates(int producer, int receiver) {
        return (producer == ELEMENT_METAL && receiver == ELEMENT_WATER)
                || (producer == ELEMENT_WATER && receiver == ELEMENT_WOOD)
                || (producer == ELEMENT_WOOD && receiver == ELEMENT_FIRE)
                || (producer == ELEMENT_FIRE && receiver == ELEMENT_EARTH)
                || (producer == ELEMENT_EARTH && receiver == ELEMENT_METAL);
    }

    public static boolean areElementsMutuallyGenerating(int first, int second) {
        return elementGenerates(first, second) || elementGenerates(second, first);
    }

    public static boolean isSynergyRound(int roundIndex) {
        return roundIndex > 0 && (roundIndex % SYNERGY_INTERVAL_ROUNDS) == 0;
    }

    public static boolean isValidFormationId(int formationId) {
        return Integer.compareUnsigned(formationId, MAX_FORMATION_ID) <= 0;
    }

    public static FormationDef formationOf(int formationId) {
        if (formationId >= 1 && formationId <= MAX_FORMATION_ID) {
            return FORMATIONS.get(formationId - 1);
        }
        return FormationDef.NONE;
    }

    public static int formationCounterBonusPct(int attackerFormationId, int defenderFormationId) {
        if (attackerFormationId < 1 || attackerFormationId > MAX_FORMATION_ID
                || defenderFormationId < 1 || defenderFormationId > MAX_FORMATION_ID) {
            return 0;
        }
        return FORMATIONS.get(attackerFormationId - 1).countersId() == defenderFormationId
                ? FORMATION_COUNTER_BONUS_PCT : 0;
    }

    public static TowerSigilDef findTowerSigil(int sigilId) {
        for (TowerSigilDef def : TOWER_SIGILS) {
            if (def.id() == sigilId) {
                return def;
            }
        }
        return TowerSigilDef.NONE;
    }

    public static boolean isValidTowerSigilId(int sigilId) {
        return findTowerSigil(sigilId).id() != 0;
    }

    public static int towerFloorDungeonId(int floor) {
        return switch (floor) {
            case 1, 6 -> 101;
            case 2, 5, 9 -> 103;
            case 3, 7 -> 105;
            case 4, 8, 10 -> 107;
            default -> 101;
        };
    }

    public static int towerFloorLeylineElement(int weekKey, int floor) {
        int clamped = (floor >= 1 && floor <= TOWER_MAX_FLOORS) ? floor : 1;
        return (int) ((Integer.toUnsignedLong(weekKey) + clamped - 1L) % 5L) + 1;
    }

    public static TournamentLeylineElement toLeylineEnum(int element) {
        TournamentLeylineElement e = TournamentLeylineElement.forNumber(element);
        return e == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : e;
    }

    public static int towerMonsterHealthPct(int floor) {
        int f = (floor >= 1 && floor <= TOWER_MAX_FLOORS) ? floor : 1;
        int pct = 85 + (f - 1) * 8;
        if (f == 5) pct += 10;
        if (f == 10) pct += 20;
        return pct;
    }

    public static int towerMonsterAttackPct(int floor) {
        int f = (floor >= 1 && floor <= TOWER_MAX_FLOORS) ? floor : 1;
        int pct = 85 + (f - 1) * 5;
        if (f == 5) pct += 6;
        if (f == 10) pct += 12;
        return pct;
    }

    /** UTC+8 周一 05:00 换周键（与 C++ ComputeWuxingTowerWeekKey 逐位对齐）。 */
    public static int computeWuxingTowerWeekKey(long nowMs) {
        long msPerWeek = 7L * 24L * 3600L * 1000L;
        long monday05CstOffsetMs = (3L * 24L - 3L) * 3600L * 1000L;
        return (int) (Long.divideUnsigned(nowMs + monday05CstOffsetMs, msPerWeek) + 1L);
    }

    public static long mixSeed(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** 确定性抽取 3 枚不重复、未持有的局内五行符印（与 C++ RollTowerSigilOffer 逐项对齐）。 */
    public static List<Integer> rollTowerSigilOffer(int weekKey, long runId, int clearedFloor,
                                                    Collection<Integer> ownedSigils) {
        Set<Integer> owned = ownedSigils == null ? Set.of() : new HashSet<>(ownedSigils);
        List<Integer> pool = new ArrayList<>(TOWER_SIGILS.size());
        for (TowerSigilDef def : TOWER_SIGILS) {
            if (owned.contains(def.id())) {
                continue;
            }
            if (clearedFloor <= 3 && def.rarity() >= 3) {
                continue;
            }
            pool.add(def.id());
        }
        if (pool.size() < TOWER_OFFER_COUNT) {
            pool.clear();
            for (TowerSigilDef def : TOWER_SIGILS) {
                if (!owned.contains(def.id())) {
                    pool.add(def.id());
                }
            }
        }
        List<Integer> offers = new ArrayList<>(TOWER_OFFER_COUNT);
        long state = mixSeed((Integer.toUnsignedLong(weekKey) << 32)
                ^ mixSeed(runId)
                ^ (Integer.toUnsignedLong(clearedFloor) * 0x517CC1B727220A95L));
        while (!pool.isEmpty() && offers.size() < TOWER_OFFER_COUNT) {
            state = mixSeed(state);
            int idx = (int) Long.remainderUnsigned(state, pool.size());
            offers.add(pool.remove(idx));
        }
        return offers;
    }

    public static boolean isValidCustomDuelRoundLimit(int roundLimitOverride) {
        return roundLimitOverride == 0 || roundLimitOverride == 8
                || roundLimitOverride == 12 || roundLimitOverride == 20;
    }
}
