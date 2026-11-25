package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTelegraphEntry;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.CreateBattleRequest;
import com.game.proto.EquipmentArtifactSource;
import com.game.proto.TrialBattleContext;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleHitKind;
import com.game.proto.eBattleOutcome;
import com.game.table.BuffTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 首领试炼（101–108 与 112 双卫同阵 V2）、护卫救援（109）、物法易势 5V5（110）与有效对抗统计的局内权威机制
 * （对齐 C++ {@code turn_battle_engine.cpp} 特殊玩法段，保证全确定性且零侵入存量常规对局）。
 */
final class EncounterMechanics {

    static final int POLARITY_NONE = 0;
    static final int POLARITY_PHYSICAL = 1;
    static final int POLARITY_MAGIC = 2;

    private static final class ExposedState {
        int pct;
        int untilRound;
    }

    private final BattleContext ctx;
    private int bossTrialKind = BattleConstants.BOSS_TRIAL_KIND_NONE;
    private int battleDifficulty;
    private int initialAttackerHumanCount;
    private int trialGuardMonsterTableId;
    private int trialGuardStanceBuffId;
    private long protectedGuardActorId;
    private int initialPolarity;
    private int activePolarity;
    private int bossStanceType;
    private int bossStanceRemainRounds;
    private int lastChosenStanceType;
    private int bossPhase = 1;
    private int bossMomentumStacks;
    private int nextPhase2CleaveTelegraphRound;
    private int bossDisheartenedUntilRound;
    private long bossBarrierShield;
    private long bossGuardHealTotal;
    private long telegraphStateVersion;
    private final boolean[] teamDealtPhysicalDirectDamage = new boolean[2];
    private final boolean[] teamDealtMagicDirectDamage = new boolean[2];
    private final TreeSet<Long> everDiedActors = new TreeSet<>(Long::compareUnsigned);
    private final List<BattleTelegraphEntry> activeTelegraphs = new ArrayList<>();
    private List<BattleTelegraphEntry> lastResolvedTelegraphs = List.of();
    private final NavigableMap<Long, TreeSet<Integer>> manualActionRoundsByPlayer =
            new TreeMap<>(Long::compareUnsigned);
    private final TreeSet<Long> effectiveContributionPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> shieldBreakPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> antiControlOrSeasonSkillPlayers = new TreeSet<>(Long::compareUnsigned);
    private final NavigableMap<Long, ExposedState> exposedActors = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Integer> sunderUntilRound = new TreeMap<>(Long::compareUnsigned);
    private final TreeSet<Long> attackerTriggeredStanceCounterThisRound = new TreeSet<>(Long::compareUnsigned);
    private final NavigableMap<Integer, long[]> roundPlayerDirectDamage = new TreeMap<>(Integer::compareUnsigned);
    private final NavigableMap<Long, Integer> selfCleavesTargetedByPlayer = new TreeMap<>(Long::compareUnsigned);
    private final NavigableMap<Long, Integer> selfCleavesParriedByPlayer = new TreeMap<>(Long::compareUnsigned);
    private int cleaveParryCount;
    private int healInterruptBacklashCount;
    private int stanceBreakCount;
    private boolean guardStanceDispelled;

    EncounterMechanics(BattleContext ctx) {
        this.ctx = ctx;
    }

    void configure(int bossTrialKind, int battleDifficulty) {
        this.bossTrialKind = bossTrialKind;
        this.battleDifficulty = battleDifficulty;
    }

    void configureTrial(int guardMonsterTableId, int guardStanceBuffId) {
        this.trialGuardMonsterTableId = guardMonsterTableId;
        this.trialGuardStanceBuffId = guardStanceBuffId;
    }

    void setInitialAttackerHumanCount(int count) {
        this.initialAttackerHumanCount = count;
    }

    void recordDeath(long actorId) {
        if (actorId != 0L) {
            everDiedActors.add(actorId);
        }
    }

    void recordTrialDirectDamage(BattleUnit source, BattleUnit target, int damageType, long dealtOrAbsorbed) {
        if (dealtOrAbsorbed == 0L || source == null || target == null
                || source.teamIndex() == target.teamIndex()
                || source.teamIndex() < 0 || source.teamIndex() >= 2) {
            return;
        }
        if (damageType == CombatDamageRules.PHYSICAL_DAMAGE) {
            teamDealtPhysicalDirectDamage[source.teamIndex()] = true;
            if (source.teamIndex() == 0 && target.teamIndex() == 1) {
                long[] tally = roundPlayerDirectDamage.computeIfAbsent(ctx.roundIndex(), k -> new long[2]);
                tally[0] = EquipmentCombatRules.addSaturated(tally[0], dealtOrAbsorbed);
            }
        } else if (damageType == CombatDamageRules.MAGIC_DAMAGE) {
            teamDealtMagicDirectDamage[source.teamIndex()] = true;
            if (source.teamIndex() == 0 && target.teamIndex() == 1) {
                long[] tally = roundPlayerDirectDamage.computeIfAbsent(ctx.roundIndex(), k -> new long[2]);
                tally[1] = EquipmentCombatRules.addSaturated(tally[1], dealtOrAbsorbed);
            }
        }
    }

    void recordTrialMonsterHeal(BattleUnit source, BattleUnit target, long healed) {
        if (healed == 0L || source == null || target == null
                || !source.isMonster() || !target.isMonster()
                || !target.isBoss() || source.actorId() == target.actorId()) {
            return;
        }
        if (trialGuardMonsterTableId == 0 || source.monsterTableId() == trialGuardMonsterTableId) {
            bossGuardHealTotal = EquipmentCombatRules.addSaturated(bossGuardHealTotal, healed);
        }
    }

    int bossTrialKind() {
        return bossTrialKind;
    }

    int battleDifficulty() {
        return battleDifficulty;
    }

    boolean isBossTrial() {
        return BattleConstants.isBossTrialKind(bossTrialKind);
    }

    boolean isBossTrialOrRescue() {
        return BattleConstants.isBossTrialOrRescueKind(bossTrialKind);
    }

    long protectedGuardActorId() {
        return protectedGuardActorId;
    }

    void setProtectedGuardActorId(long actorId) {
        this.protectedGuardActorId = actorId;
    }

    int activePolarity() {
        return activePolarity;
    }

    int bossStanceType() {
        return bossStanceType;
    }

    int bossStanceRemainRounds() {
        return bossStanceRemainRounds;
    }

    int bossPhase() {
        return bossPhase;
    }

    int bossMomentumStacks() {
        return bossMomentumStacks;
    }

    long bossBarrierShield() {
        return bossBarrierShield;
    }

    boolean guardStanceDispelled() {
        return guardStanceDispelled;
    }

    boolean isBossDisheartened() {
        return bossDisheartenedUntilRound != 0
                && Integer.compareUnsigned(bossDisheartenedUntilRound, ctx.roundIndex()) >= 0;
    }

    boolean isActorSundered(long actorId) {
        Integer until = sunderUntilRound.get(actorId);
        return until != null && Integer.compareUnsigned(until, ctx.roundIndex()) >= 0;
    }

    void applyBreakerSunder(BattleUnit attacker, BattleUnit target) {
        if (attacker == null || target == null || !target.isActive()
                || !isBossTrial() || !attacker.isMonster()
                || attacker.teamIndex() != 1
                || attacker.monsterTableId() != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER) {
            return;
        }
        sunderUntilRound.put(target.actorId(), ctx.roundIndex() + 2);
        emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_BREAKER_SUNDER,
                attacker.actorId(), target.actorId(), 20L);
    }

    boolean clearSunder(long actorId) {
        return sunderUntilRound.remove(actorId) != null;
    }

    boolean hasShieldBreak(long playerId) {
        return shieldBreakPlayers.contains(playerId);
    }

    boolean hasAntiControlOrSeasonSkill(long playerId) {
        return antiControlOrSeasonSkillPlayers.contains(playerId);
    }

    void markAntiControlOrSeasonSkill(long playerId) {
        if (playerId != 0L) {
            antiControlOrSeasonSkillPlayers.add(playerId);
        }
    }

    void markShieldBreak(BattleUnit source) {
        long owner = ownerPlayerIdOf(source);
        if (owner != 0L) {
            shieldBreakPlayers.add(owner);
        }
    }

    void recordManualActionRound(long ownerPlayerId, int roundIndex) {
        if (ownerPlayerId == 0L || roundIndex == 0) {
            return;
        }
        manualActionRoundsByPlayer.computeIfAbsent(ownerPlayerId, k -> new TreeSet<>(Integer::compareUnsigned))
                .add(roundIndex);
    }

    int manualActionRounds(long playerId) {
        TreeSet<Integer> rounds = manualActionRoundsByPlayer.get(playerId);
        return rounds == null ? 0 : rounds.size();
    }

    boolean hadManualActionInRound(long playerId, int roundIndex) {
        TreeSet<Integer> rounds = manualActionRoundsByPlayer.get(playerId);
        return rounds != null && rounds.contains(roundIndex);
    }

    void recordEffectiveContribution(BattleUnit actor) {
        long owner = ownerPlayerIdOf(actor);
        if (owner != 0L) {
            effectiveContributionPlayers.add(owner);
        }
    }

    boolean hasEffectiveContribution(long playerId) {
        return effectiveContributionPlayers.contains(playerId);
    }

    static long ownerPlayerIdOf(BattleUnit actor) {
        if (actor == null) {
            return 0L;
        }
        if (actor.isPlayer()) {
            return actor.actorId();
        }
        return actor.isPet() ? actor.ownerPlayerId() : 0L;
    }

    boolean hasActiveDamageModifiers() {
        return activePolarity != 0 || isBossTrial();
    }

    void emitBossMechanicEvent(int mechanicKind, long sourceId, long targetId, long value) {
        ctx.events().append(eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC, sourceId, targetId)
                .setMechanicKind(mechanicKind)
                .setValue(value);
    }

    void applyBossExposed(long actorId, int pct, int untilRound) {
        if (actorId == 0L || pct == 0) {
            return;
        }
        ExposedState state = exposedActors.computeIfAbsent(actorId, k -> new ExposedState());
        // V2 防线：同目标同一时刻仅保留 1 份破绽，高值覆盖低值、绝不叠乘，同值仅续时至更晚回合末。
        if (Integer.compareUnsigned(pct, state.pct) > 0) {
            state.pct = pct;
        }
        if (Integer.compareUnsigned(untilRound, state.untilRound) > 0) {
            state.untilRound = untilRound;
        }
    }

    private boolean actorHasBossCleaveParryProtection(BattleUnit target, int damageType) {
        if (ctx.actorHasBuffOfType(target, BattleConstants.BUFF_TYPE_INVINCIBILITY)) {
            return true;
        }
        for (BattleBuffEntry entry : target.state().getBuffsList()) {
            BuffTable row = ctx.configured.row(entry);
            if (row == null || row.getBattleOp() == 0) {
                continue;
            }
            if (!ConfiguredBattle.matches(row, damageType)) {
                continue;
            }
            int op = row.getBattleOp();
            if (op == BattleEffect.SHIELD || op == BattleEffect.MANA_SHIELD
                    || op == BattleEffect.SHARE || op == BattleEffect.REFLECT) {
                return true;
            }
        }
        return false;
    }

    private void triggerRejuvBacklash(long sourceId, BattleUnit boss) {
        if (boss == null || !boss.isActive()) {
            return;
        }
        long rawBacklash = Math.max(1L, Long.divideUnsigned(boss.maxHealth() * BattleConstants.BOSS_HEAL_BACKLASH_PCT, 100L));
        long dealt = Unsigned.minUnsigned(boss.health(), rawBacklash);
        if (dealt != 0L) {
            boss.setHealth(boss.health() - dealt);
        }
        healInterruptBacklashCount++;
        applyBossExposed(boss.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, ctx.roundIndex() + 1);
        emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_HEAL_BACKLASH, sourceId, boss.actorId(), dealt);
        ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, sourceId, boss.actorId())
                .setValue(dealt)
                .setTargetHealthAfter(boss.health())
                .setTargetManaAfter(boss.mana());
        if (boss.health() == 0L) {
            ctx.handleDeath(boss, sourceId);
        }
    }

    void handleBossTrialMonsterDeath(BattleUnit deadMonster) {
        if (deadMonster == null || deadMonster.isBoss()) {
            return;
        }
        BattleUnit boss = findAliveBoss();

        // 1. 护势护卫阵亡：联防阵自然瓦解。
        int deadMid = deadMonster.monsterTableId();
        if (BattleConstants.isBossStanceTrialKind(bossTrialKind) && bossStanceType != POLARITY_NONE
                && (deadMid == BattleConstants.BOSS_TRIAL_MONSTER_GUARD_STANCE
                || (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                && deadMid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER
                && deadMid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER))) {
            bossStanceType = POLARITY_NONE;
            bossStanceRemainRounds = 0;
        }

        // 2. 回春护卫在已发出治疗预告后、兑现前被击杀：立即触发断疗反噬（6% 首领血 + 破绽）并移除预告防重复触发。
        boolean cancelledPendingHeal = false;
        List<BattleTelegraphEntry> updatedResolved = new ArrayList<>(lastResolvedTelegraphs);
        for (Iterator<BattleTelegraphEntry> it = activeTelegraphs.iterator(); it.hasNext(); ) {
            BattleTelegraphEntry entry = it.next();
            if (entry.getSourceActorId() == deadMonster.actorId()
                    && entry.getIntentKind() == BattleConstants.TELEGRAPH_KIND_GUARD_HEAL
                    && Integer.compareUnsigned(entry.getExecuteRound(), ctx.roundIndex()) >= 0) {
                BattleTelegraphEntry resolved = entry.toBuilder()
                        .setStatus(BattleConstants.TELEGRAPH_STATUS_CANCELLED_BY_CONTROL)
                        .setStateVersion(++telegraphStateVersion)
                        .setLabel("回春护卫阵亡，治疗中断触发灵力反噬")
                        .build();
                ctx.events().addResolvedTelegraph(resolved);
                updatedResolved.add(resolved);
                it.remove();
                cancelledPendingHeal = true;
            }
        }
        if (cancelledPendingHeal) {
            lastResolvedTelegraphs = List.copyOf(updatedResolved);
            if (boss != null && boss.isActive()) {
                triggerRejuvBacklash(deadMonster.actorId(), boss);
            }
        }

        // 3. 策应卫阵亡：破军卫清 1 层蓄势；鼓噪卫清全部蓄势并对首领造成 4% 震荡反噬。
        if (deadMid == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER && boss != null && boss.isActive()) {
            if (bossMomentumStacks > 0) {
                bossMomentumStacks--;
                emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_SUPPORT_SHOCK,
                        deadMonster.actorId(), boss.actorId(), 1L);
            }
        } else if (deadMid == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER && boss != null && boss.isActive()) {
            bossMomentumStacks = 0;
            long rawShock = Math.max(1L,
                    Long.divideUnsigned(boss.maxHealth() * BattleConstants.BOSS_DRUMMER_DEATH_SHOCK_PCT, 100L));
            long dealt = Unsigned.minUnsigned(boss.health(), rawShock);
            if (dealt != 0L) {
                boss.setHealth(boss.health() - dealt);
            }
            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_SUPPORT_SHOCK,
                    deadMonster.actorId(), boss.actorId(), dealt);
            ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, deadMonster.actorId(), boss.actorId())
                    .setValue(dealt)
                    .setTargetHealthAfter(boss.health())
                    .setTargetManaAfter(boss.mana());
            if (boss.health() == 0L) {
                ctx.handleDeath(boss, deadMonster.actorId());
            }
        }
    }

    void processBossTrialRoundEnd(ActionChecks checks) {
        BattleUnit boss = null;
        boolean anySpecialGuardAlive = false;
        for (BattleUnit actor : ctx.units()) {
            if (!actor.isMonster() || actor.teamIndex() != 1 || !actor.isActive()) {
                continue;
            }
            if (actor.isBoss()) {
                boss = actor;
            } else {
                int mid = actor.monsterTableId();
                if (mid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER
                        && mid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER) {
                    anySpecialGuardAlive = true;
                }
            }
        }
        if (boss == null || !boss.isActive()) {
            return;
        }

        // 1. 存活且未受控的鼓噪卫回合末为首领鼓舞 +1 蓄势（至多 3 层）。
        for (BattleUnit actor : ctx.units()) {
            if (actor.isMonster() && actor.teamIndex() == 1
                    && actor.monsterTableId() == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER
                    && actor.isActive() && !isActorControlLocked(actor, checks)) {
                if (bossMomentumStacks < BattleConstants.BOSS_MOMENTUM_MAX_STACKS) {
                    bossMomentumStacks++;
                    emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_MOMENTUM_GAIN,
                            actor.actorId(), boss.actorId(), Integer.toUnsignedLong(bossMomentumStacks));
                }
            }
        }

        // 2. 护势联防持续回合扣减（3 回合含施放当轮）。
        if (bossStanceRemainRounds > 0) {
            bossStanceRemainRounds--;
            if (bossStanceRemainRounds == 0) {
                bossStanceType = POLARITY_NONE;
            }
        }

        // 3. 二阶段「决战姿态（破釜）」：全部特殊护卫阵亡或首领气血 <= 50%，整场仅触发一次且绝不回合中途连劈（V7）。
        boolean halfHpOrBelow = Long.compareUnsigned(boss.health(), Long.divideUnsigned(boss.maxHealth(), 2L)) <= 0
                && Long.compareUnsigned(boss.health() * 2L, boss.maxHealth()) <= 0;
        if (bossPhase < 2 && (!anySpecialGuardAlive || halfHpOrBelow)) {
            bossPhase = 2;
            int nextRound = ctx.roundIndex() + 1;
            boolean hasNextRoundCleave = false;
            for (BattleTelegraphEntry entry : activeTelegraphs) {
                if (entry.getSourceActorId() == boss.actorId() && entry.getExecuteRound() == nextRound) {
                    hasNextRoundCleave = true;
                    break;
                }
            }
            nextPhase2CleaveTelegraphRound = hasNextRoundCleave ? (ctx.roundIndex() + 2) : nextRound;
            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_PHASE_TWO_ENRAGE,
                    boss.actorId(), boss.actorId(), 2L);
        }
    }

    void advanceRoundCleanup() {
        attackerTriggeredStanceCounterThisRound.clear();
        int currentRound = ctx.roundIndex();
        exposedActors.entrySet().removeIf(e -> Integer.compareUnsigned(e.getValue().untilRound, currentRound) < 0);
        sunderUntilRound.entrySet().removeIf(e -> Integer.compareUnsigned(e.getValue(), currentRound) < 0);
    }

    private int chooseDynamicStanceType(int currentRound) {
        if (Integer.compareUnsigned(currentRound, 1) <= 0) {
            return POLARITY_PHYSICAL;
        }
        if (Integer.compareUnsigned(battleDifficulty, 2) < 0) {
            return (((currentRound - 1) / 3) % 2 == 0) ? POLARITY_PHYSICAL : POLARITY_MAGIC;
        }
        // 困难·护势进阶 / 双卫同阵「顺势应变」：统计前 2 个完整回合玩家方物理/法术直接伤害总量，针对高侧开阵；平票确定性反切。
        long physTotal = 0L;
        long magicTotal = 0L;
        int startRound = Integer.compareUnsigned(currentRound, 2) > 0 ? currentRound - 2 : 1;
        for (int r = startRound; Integer.compareUnsigned(r, currentRound) < 0; r++) {
            long[] tally = roundPlayerDirectDamage.get(r);
            if (tally != null) {
                physTotal = EquipmentCombatRules.addSaturated(physTotal, tally[0]);
                magicTotal = EquipmentCombatRules.addSaturated(magicTotal, tally[1]);
            }
        }
        int cmp = Long.compareUnsigned(physTotal, magicTotal);
        if (cmp > 0) {
            return POLARITY_PHYSICAL;
        }
        if (cmp < 0) {
            return POLARITY_MAGIC;
        }
        return lastChosenStanceType == POLARITY_PHYSICAL ? POLARITY_MAGIC : POLARITY_PHYSICAL;
    }

    /** 存量非配置伤害路径的落血与特殊玩法修正合一；常规对局直接走原 {@link BattleUnit#applyDamage} 保逐字节兼容。 */
    long applyLegacyDamage(BattleUnit source, BattleUnit target, double rawDamage, int damageType, boolean isDirectPrimary) {
        return applyLegacyDamage(source, target, rawDamage, damageType, isDirectPrimary, false, true, null);
    }

    long applyLegacyDamage(BattleUnit source, BattleUnit target, double rawDamage, int damageType,
                           boolean isDirectPrimary, boolean isMultiTargetSkill, boolean isPrimaryTarget,
                           boolean[] outSplashReduced) {
        if (outSplashReduced != null && outSplashReduced.length > 0) {
            outSplashReduced[0] = false;
        }
        if (!hasActiveDamageModifiers()) {
            return target.applyDamage(rawDamage);
        }
        if (rawDamage <= 0.0 || !Double.isFinite(rawDamage)) {
            return 0L;
        }
        double effective = rawDamage;
        if (ctx.actorHasBuffOfType(target, BattleConstants.BUFF_TYPE_INVINCIBILITY)) {
            effective = 0.0;
        }
        if (target.isDefending()) {
            effective *= 0.5;
        }
        long remaining = CombatDamageRules.damageToHealth(effective, -1L);
        remaining = applyDamageModifiers(source, target, remaining, damageType, isDirectPrimary,
                isMultiTargetSkill, isPrimaryTarget, outSplashReduced);
        long dealt = Unsigned.minUnsigned(target.health(), remaining);
        target.setHealth(target.health() - dealt);
        return dealt;
    }

    static int polarityOfDamageType(int damageType) {
        return damageType == CombatDamageRules.PHYSICAL_DAMAGE ? POLARITY_PHYSICAL
                : damageType == CombatDamageRules.MAGIC_DAMAGE ? POLARITY_MAGIC : POLARITY_NONE;
    }

    long applyDamageModifiers(BattleUnit source, BattleUnit target, long damage, int damageType, boolean isDirectPrimary) {
        return applyDamageModifiers(source, target, damage, damageType, isDirectPrimary, false, true, null);
    }

    /** 物法易势 ×1.15、首领试炼 V2 聚火/溅射 60%、护势联防阵 35%/40%+蓄势反制、统一破绽+二阶段易伤、灵障护盾吸收。 */
    long applyDamageModifiers(BattleUnit source, BattleUnit target, long damage, int damageType, boolean isDirectPrimary,
                              boolean isMultiTargetSkill, boolean isPrimaryTarget, boolean[] outSplashReduced) {
        if (outSplashReduced != null && outSplashReduced.length > 0) {
            outSplashReduced[0] = false;
        }
        if (damage == 0L) {
            return 0L;
        }
        long result = damage;
        int dmgPolarity = polarityOfDamageType(damageType);
        // 1. 物法易势 5V5：与当前易势同类型的直接主伤害 ×115/100（整数向下取整一次；1=物势，2=法势）。
        if (isDirectPrimary && activePolarity != POLARITY_NONE && activePolarity == dmgPolarity) {
            result = Long.divideUnsigned(result * BattleConstants.POLARITY_DAMAGE_NUM, BattleConstants.POLARITY_DAMAGE_DEN);
        }
        // 2. 首领试炼 V2 阵型「主目标聚火 100% / 多目标直伤副目标溅射 60%」（仅限试炼敌方，零侵入常规 PVE/PVP，V6）。
        if (isDirectPrimary && isBossTrial() && source != null && source.teamIndex() == 0 && target.teamIndex() == 1
                && isMultiTargetSkill && !isPrimaryTarget) {
            result = Long.divideUnsigned(result * BattleConstants.BOSS_TRIAL_SPLASH_KEEP_PCT, 100L);
            if (outSplashReduced != null && outSplashReduced.length > 0) {
                outSplashReduced[0] = true;
            }
        }
        // 3. 破阵·护势联防阵（单卫与双卫同阵）：庇护全体存活敌方，同时对同系直伤触发轻反制叠「蓄势」（毒火不触发）。
        if (isDirectPrimary && BattleConstants.isBossStanceTrialKind(bossTrialKind) && target.teamIndex() == 1
                && bossStanceType != POLARITY_NONE && bossStanceRemainRounds > 0 && bossStanceType == dmgPolarity) {
            long keepPct = bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_DUAL_GUARD
                    ? 65L
                    : (Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 60L : 65L);
            result = Long.divideUnsigned(result * keepPct, 100L);
            if (source != null && source.teamIndex() == 0
                    && !attackerTriggeredStanceCounterThisRound.contains(source.actorId())) {
                attackerTriggeredStanceCounterThisRound.add(source.actorId());
                if (bossMomentumStacks < BattleConstants.BOSS_MOMENTUM_MAX_STACKS) {
                    bossMomentumStacks++;
                    BattleUnit aliveBoss = findAliveBoss();
                    long bossId = aliveBoss != null ? aliveBoss.actorId() : target.actorId();
                    emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_MOMENTUM_GAIN,
                            target.actorId(), bossId, Integer.toUnsignedLong(bossMomentumStacks));
                }
            }
        }
        // 4. 首领试炼 V2 统一「破绽 (+35%/+20%)」+ 二阶段「决战姿态 (+15%)」加算易伤（覆盖直接伤害与持续毒火，V2）。
        if (isBossTrial() && source != null && source.teamIndex() != target.teamIndex() && target.teamIndex() == 1) {
            int vulnPct = 0;
            ExposedState exposed = exposedActors.get(target.actorId());
            if (exposed != null && Integer.compareUnsigned(exposed.untilRound, ctx.roundIndex()) >= 0) {
                vulnPct += exposed.pct;
            }
            if (target.isBoss() && bossPhase >= 2) {
                vulnPct += BattleConstants.BOSS_PHASE_TWO_VULN_PCT;
            }
            if (vulnPct > 0) {
                result = Long.divideUnsigned(result * (100L + Integer.toUnsignedLong(vulnPct)), 100L);
            }
        }
        // 5. 灵障首领：灵障护盾在生命扣血前优先吸收伤害，击破时记破盾者与有效贡献。
        if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER
                && target.isBoss() && bossBarrierShield != 0L && result != 0L) {
            long absorbed = Unsigned.minUnsigned(result, bossBarrierShield);
            bossBarrierShield -= absorbed;
            result -= absorbed;
            if (absorbed != 0L) {
                recordTrialDirectDamage(source, target, damageType, absorbed);
                recordEffectiveContribution(source);
                ctx.events().append(eBattleEventType.BATTLE_EVENT_SHIELD, target.actorId(), target.actorId())
                        .setAbsorbed(absorbed)
                        .setShieldAfter(bossBarrierShield);
                if (bossBarrierShield == 0L) {
                    markShieldBreak(source);
                }
            }
        }
        return result;
    }

    void recordGuardStanceDispelled(BattleUnit source, BattleUnit target, int buffTableId) {
        if (source == null || target == null || source.teamIndex() != 0 || target.teamIndex() != 1 || !target.isMonster()) {
            return;
        }
        int targetBuffId = trialGuardStanceBuffId;
        if (targetBuffId == 0 || targetBuffId == buffTableId) {
            guardStanceDispelled = true;
        }
    }

    void tryDispelBossStance(BattleUnit source, BattleUnit target) {
        if (source == null || target == null
                || !isBossTrial()
                || source.teamIndex() == target.teamIndex()
                || target.teamIndex() != 1) {
            return;
        }
        boolean dispelledAny = false;
        if (BattleConstants.isBossStanceTrialKind(bossTrialKind) && bossStanceType != POLARITY_NONE) {
            bossStanceType = POLARITY_NONE;
            bossStanceRemainRounds = 0;
            guardStanceDispelled = true;
            stanceBreakCount++;
            dispelledAny = true;
            // 崩势破盾：首领与存活护势护卫同时陷入「破绽（+35%，至下回合末）」。
            for (BattleUnit a : ctx.units()) {
                if (!a.isMonster() || a.teamIndex() != 1 || !a.isActive()) {
                    continue;
                }
                int mid = a.monsterTableId();
                if (a.isBoss() || mid == BattleConstants.BOSS_TRIAL_MONSTER_GUARD_STANCE
                        || (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                        && mid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER
                        && mid != BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER)) {
                    applyBossExposed(a.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, ctx.roundIndex() + 1);
                }
            }
            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_STANCE_BREAK,
                    source.actorId(), target.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT);
        }
        if (bossMomentumStacks > 0) {
            bossMomentumStacks = 0;
            dispelledAny = true;
        }
        if (dispelledAny) {
            recordEffectiveContribution(source);
        }
    }

    void initSpecialState(int matchMode, int maxRounds) {
        if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_NONE && matchMode != BattleConstants.MATCH_MODE_POLARITY_5V5) {
            return;
        }
        if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_POLARITY_5V5 || matchMode == BattleConstants.MATCH_MODE_POLARITY_5V5) {
            initialPolarity = ctx.rng().randIndex(2) == 0 ? POLARITY_PHYSICAL : POLARITY_MAGIC;
            updatePolarityForRound(matchMode);
            return;
        }
        refreshTelegraphs(maxRounds);
    }

    void updatePolarityForRound(int matchMode) {
        if (bossTrialKind != BattleConstants.BOSS_TRIAL_KIND_POLARITY_5V5
                && matchMode != BattleConstants.MATCH_MODE_POLARITY_5V5) {
            activePolarity = POLARITY_NONE;
            return;
        }
        if (initialPolarity == POLARITY_NONE) {
            initialPolarity = POLARITY_PHYSICAL;
        }
        int r = ctx.roundIndex();
        int block = (r > 0 ? r - 1 : 0) / 2;
        activePolarity = (block % 2 == 0)
                ? initialPolarity
                : (initialPolarity == POLARITY_PHYSICAL ? POLARITY_MAGIC : POLARITY_PHYSICAL);
    }

    int nextPolaritySwitchRound(int maxRounds) {
        if (activePolarity == 0) {
            return 0;
        }
        int r = ctx.roundIndex();
        int nextSwitch = ((r > 0 ? r - 1 : 0) / 2 + 1) * 2 + 1;
        return Integer.compareUnsigned(nextSwitch, maxRounds) <= 0 ? nextSwitch : 0;
    }

    void clearActiveTelegraphs() {
        activeTelegraphs.clear();
    }

    boolean isSlowPhaseActor(long actorId) {
        int r = ctx.roundIndex();
        for (BattleTelegraphEntry entry : activeTelegraphs) {
            if (entry.getExecuteRound() == r && entry.getSourceActorId() == actorId) {
                return true;
            }
        }
        return false;
    }

    void refreshTelegraphs(int maxRounds) {
        int r = ctx.roundIndex();
        List<BattleTelegraphEntry> kept = new ArrayList<>();
        for (BattleTelegraphEntry entry : activeTelegraphs) {
            if (Integer.compareUnsigned(entry.getExecuteRound(), r) >= 0) {
                int nextStatus = entry.getExecuteRound() == r
                        ? BattleConstants.TELEGRAPH_STATUS_EXECUTING
                        : BattleConstants.TELEGRAPH_STATUS_UPCOMING;
                kept.add(entry.getStatus() == nextStatus ? entry
                        : entry.toBuilder().setStatus(nextStatus).setStateVersion(++telegraphStateVersion).build());
            }
        }
        activeTelegraphs.clear();
        activeTelegraphs.addAll(kept);

        if (!isBossTrialOrRescue()) {
            return;
        }

        BattleUnit boss = null;
        BattleUnit rejuvGuard = null;
        BattleUnit stanceGuard = null;
        BattleUnit otherGuard = null;
        for (BattleUnit actor : ctx.units()) {
            if (!actor.isMonster() || actor.teamIndex() != 1 || !actor.isActive()) {
                continue;
            }
            if (actor.isBoss()) {
                boss = actor;
                continue;
            }
            int mid = actor.monsterTableId();
            if (mid == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER
                    || mid == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER) {
                continue;
            }
            if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_DUAL_GUARD) {
                if (mid == BattleConstants.BOSS_TRIAL_MONSTER_GUARD_REJUV && rejuvGuard == null) {
                    rejuvGuard = actor;
                } else if (mid == BattleConstants.BOSS_TRIAL_MONSTER_GUARD_STANCE && stanceGuard == null) {
                    stanceGuard = actor;
                } else if (rejuvGuard == null) {
                    rejuvGuard = actor;
                } else if (stanceGuard == null) {
                    stanceGuard = actor;
                }
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV) {
                if (rejuvGuard == null) rejuvGuard = actor;
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE) {
                if (stanceGuard == null) stanceGuard = actor;
            } else {
                if (otherGuard == null) otherGuard = actor;
            }
        }

        // 1. 首领蓄力慢速重击：一阶段 R1/4/7/10/13 预告；二阶段决战姿态按 nextPhase2CleaveTelegraphRound 每 2 回合循环。
        boolean bossTelegraphDue = bossPhase >= 2
                ? (nextPhase2CleaveTelegraphRound != 0 && r == nextPhase2CleaveTelegraphRound)
                : (Integer.remainderUnsigned(r, 3) == 1);
        if (boss != null && bossTelegraphDue
                && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                && !hasEntryFor(boss.actorId(), r + 1)) {
            BattleTelegraphEntry.Builder entry = BattleTelegraphEntry.newBuilder()
                    .setSourceActorId(boss.actorId())
                    .setTelegraphRound(r)
                    .setExecuteRound(r + 1)
                    .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING)
                    .setStateVersion(++telegraphStateVersion);
            if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER) {
                long tid = pickEnemyTarget(boss);
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE)
                        .setTargetActorId(tid)
                        .setLabel("灵障重击：下回合慢速法术重击，防御或护罩化解可触发首领破绽");
                if (tid != 0L) {
                    entry.addTargetActorIds(tid);
                }
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_VENOM) {
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_BOSS_VENOM_AOE)
                        .setTargetActorId(0L)
                        .setLabel("玄毒冲击：下回合慢速群体法术伤害，防御可减半、控场打断触发破绽");
            } else {
                boolean dualTarget = isBossTrial() && initialAttackerHumanCount >= 3;
                List<Long> targets = pickCleaveTargets(boss, dualTarget);
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE);
                if (!targets.isEmpty()) {
                    entry.setTargetActorId(targets.getFirst());
                    for (long tid : targets) {
                        entry.addTargetActorIds(tid);
                    }
                }
                entry.setLabel(targets.size() >= 2
                        ? "裂地双劈：下回合慢速重劈双目标，全员防御/护盾化解触发完整破绽"
                        : "蓄力重劈：下回合慢速物理重劈，防御/护盾/反震化解触发首领破绽");
            }
            activeTelegraphs.add(entry.build());
            if (bossPhase >= 2) {
                nextPhase2CleaveTelegraphRound = r + 2;
            }
        }

        // 2. 敌方护卫周期机制预告（支持单卫与双卫同阵独立并行预告）。
        if (boss != null) {
            if (rejuvGuard != null && BattleConstants.isBossRejuvTrialKind(bossTrialKind)) {
                // 普通回春与双卫同阵均为 R2/5/8 预告 -> R3/6/9 兑现（错峰防死锁）；困难单卫回春(B1)为 R1/4/7 -> R2/5/8 与重劈重叠。
                int triggerMod = (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV
                        && Integer.compareUnsigned(battleDifficulty, 2) >= 0) ? 1 : 2;
                if (Integer.remainderUnsigned(r, 3) == triggerMod
                        && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                        && !hasEntryFor(rejuvGuard.actorId(), r + 1)) {
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(rejuvGuard.actorId())
                            .setTargetActorId(boss.actorId())
                            .addTargetActorIds(boss.actorId())
                            .setTelegraphRound(r)
                            .setExecuteRound(r + 1)
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_HEAL)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING)
                            .setStateVersion(++telegraphStateVersion)
                            .setLabel(Integer.compareUnsigned(battleDifficulty, 2) >= 0
                                    ? "回春祈愈（进阶）：下回合为首领回复10%血并净1减益；打断或击杀触发反噬破绽"
                                    : "回春祈愈：下回合为首领回复8%最大生命；控断或击杀触发反噬破绽")
                            .build());
                }
            }
            if (stanceGuard != null && BattleConstants.isBossStanceTrialKind(bossTrialKind)) {
                if (Integer.remainderUnsigned(r, 3) == 1 && !hasEntryFor(stanceGuard.actorId(), r)) {
                    int chosenType = chooseDynamicStanceType(r);
                    boolean isPhysical = chosenType == POLARITY_PHYSICAL;
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(stanceGuard.actorId())
                            .setTargetActorId(boss.actorId())
                            .addTargetActorIds(boss.actorId())
                            .setTelegraphRound(r)
                            .setExecuteRound(r)
                            .setIntentKind(isPhysical
                                    ? BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_PHYS
                                    : BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_MAGIC)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_EXECUTING)
                            .setStateVersion(++telegraphStateVersion)
                            .setLabel(isPhysical
                                    ? "物防联防阵：本回合展开全体物理减伤阵（可驱散崩势，物理直伤叠蓄势）"
                                    : "法防联防阵：本回合展开全体法术减伤阵（可驱散崩势，法术直伤叠蓄势）")
                            .build());
                }
            }
            if (otherGuard != null && bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER) {
                if (Integer.remainderUnsigned(r, 3) == 1 && !hasEntryFor(otherGuard.actorId(), r)) {
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(otherGuard.actorId())
                            .setTargetActorId(boss.actorId())
                            .addTargetActorIds(boss.actorId())
                            .setTelegraphRound(r)
                            .setExecuteRound(r)
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_SHIELD)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_EXECUTING)
                            .setStateVersion(++telegraphStateVersion)
                            .setLabel("灵障护佑：本回合为首领附加灵障护盾")
                            .build());
                }
            } else if (otherGuard != null && bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_VENOM) {
                if (Integer.remainderUnsigned(r, 3) == 1
                        && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                        && !hasEntryFor(otherGuard.actorId(), r + 1)) {
                    long tid = pickEnemyTarget(otherGuard);
                    BattleTelegraphEntry.Builder bindEntry = BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(otherGuard.actorId())
                            .setTargetActorId(tid)
                            .setTelegraphRound(r)
                            .setExecuteRound(r + 1)
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_BIND)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING)
                            .setStateVersion(++telegraphStateVersion)
                            .setLabel("缚灵锁脉：下回合封控目标并消耗法力");
                    if (tid != 0L) {
                        bindEntry.addTargetActorIds(tid);
                    }
                    activeTelegraphs.add(bindEntry.build());
                }
            }
        }
    }

    /** 在全部常规行动之后、回合末 buff tick 与摘除防御之前执行慢速首领/护卫机制；受控直接取消不回退普攻。 */
    void executeSlowAndGuardianActions(ActionChecks checks) {
        if (activeTelegraphs.isEmpty()) {
            return;
        }
        int r = ctx.roundIndex();
        List<BattleTelegraphEntry> due = List.copyOf(activeTelegraphs);
        List<BattleTelegraphEntry> roundResolved = new ArrayList<>();

        for (BattleTelegraphEntry entry : due) {
            if (entry.getExecuteRound() != r) {
                continue;
            }
            BattleUnit source = ctx.findActor(entry.getSourceActorId());
            if (source == null || !source.isActive()) {
                continue;
            }
            if (isActorControlLocked(source, checks)) {
                if (isBossTrial()) {
                    switch (entry.getIntentKind()) {
                        case BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE,
                             BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE,
                             BattleConstants.TELEGRAPH_KIND_BOSS_VENOM_AOE -> {
                            applyBossExposed(source.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, r + 1);
                            cleaveParryCount++;
                            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_CLEAVE_PARRY_EXPOSE,
                                    source.actorId(), source.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT);
                            recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_CANCELLED_BY_CONTROL,
                                    "重击受控取消，首领陷入破绽");
                        }
                        case BattleConstants.TELEGRAPH_KIND_GUARD_HEAL -> {
                            BattleUnit boss = findAliveBoss();
                            if (boss != null) {
                                triggerRejuvBacklash(source.actorId(), boss);
                            }
                            recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_CANCELLED_BY_CONTROL,
                                    "回春被控打断，触发灵力反噬");
                        }
                        case BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_PHYS,
                             BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_MAGIC -> {
                            stanceBreakCount++;
                            applyBossExposed(source.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, r + 1);
                            BattleUnit boss = findAliveBoss();
                            if (boss != null) {
                                applyBossExposed(boss.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, r + 1);
                                emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_STANCE_BREAK,
                                        source.actorId(), boss.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT);
                            }
                            recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_CANCELLED_BY_CONTROL,
                                    "切阵被控打断，触发崩势破绽");
                        }
                        default -> {
                        }
                    }
                }
                continue;
            }
            switch (entry.getIntentKind()) {
                case BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE,
                     BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE -> {
                    List<Long> targetIds = new ArrayList<>();
                    if (entry.getTargetActorIdsCount() > 0) {
                        targetIds.addAll(entry.getTargetActorIdsList());
                    } else if (entry.getTargetActorId() != 0L) {
                        targetIds.add(entry.getTargetActorId());
                    }
                    if (targetIds.isEmpty()) {
                        break;
                    }
                    boolean magic = entry.getIntentKind() == BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE;
                    int dmgType = magic ? CombatDamageRules.MAGIC_DAMAGE : CombatDamageRules.PHYSICAL_DAMAGE;
                    double scale = magic
                            ? (Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 2.0 : 1.7)
                            : (Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 2.1 : 1.8);
                    if (isBossTrial()) {
                        if (bossMomentumStacks > 0) {
                            scale *= (1.0 + (bossMomentumStacks * BattleConstants.BOSS_MOMENTUM_PER_STACK_PCT) / 100.0);
                        }
                        if (bossPhase >= 2) {
                            scale *= 1.10;
                        }
                    }
                    int validTargets = 0;
                    int invalidTargets = 0;
                    int parriedTargets = 0;
                    for (long targetId : targetIds) {
                        BattleUnit target = ctx.findActor(targetId);
                        long ownerId = ownerPlayerIdOf(target);
                        if (ownerId != 0L && isBossTrial()) {
                            selfCleavesTargetedByPlayer.merge(ownerId, 1, Integer::sum);
                        }
                        if (target == null || !target.isActive()) {
                            // V1 防线：目标倒地或主动退出导致的失效取消，绝不计为化劲成功、绝不触发破绽。
                            invalidTargets++;
                            continue;
                        }
                        validTargets++;
                        boolean wasDefending = target.isDefending();
                        boolean hadProtection = actorHasBossCleaveParryProtection(target, dmgType);
                        int eventsBefore = ctx.events().eventCount();
                        ctx.events().beginGroup();
                        ctx.events().append(eBattleEventType.BATTLE_EVENT_ATTACK, source.actorId(), target.actorId());
                        dealBossStrike(source, target, dmgType, scale);
                        boolean triggeredShieldOrReflect = false;
                        for (int i = eventsBefore; i < ctx.events().eventCount(); i++) {
                            BattleEventItem ev = ctx.events().eventAt(i);
                            if (ev.getEventType() == eBattleEventType.BATTLE_EVENT_SHIELD
                                    && ev.getTargetId() == target.actorId()
                                    && ev.getAbsorbed() != 0L) {
                                triggeredShieldOrReflect = true;
                                break;
                            }
                            if (ev.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                                    && ev.getHitKind() == eBattleHitKind.BATTLE_HIT_REFLECT
                                    && ev.getSourceId() == target.actorId()) {
                                triggeredShieldOrReflect = true;
                                break;
                            }
                        }
                        boolean parried = wasDefending || hadProtection || triggeredShieldOrReflect;
                        if (parried) {
                            parriedTargets++;
                            if (ownerId != 0L && isBossTrial()) {
                                selfCleavesParriedByPlayer.merge(ownerId, 1, Integer::sum);
                            }
                        }
                    }
                    if (isBossTrial()) {
                        bossMomentumStacks = 0;
                        if (validTargets == 0) {
                            recordResolved(roundResolved, entry,
                                    BattleConstants.TELEGRAPH_STATUS_CANCELLED_TARGET_INVALID, "目标已失效，重劈取消");
                        } else {
                            if (parriedTargets == targetIds.size() && invalidTargets == 0) {
                                applyBossExposed(source.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT, r + 1);
                                cleaveParryCount++;
                                emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_CLEAVE_PARRY_EXPOSE,
                                        source.actorId(), source.actorId(), BattleConstants.BOSS_EXPOSED_FULL_PCT);
                            } else if (targetIds.size() >= 2 && parriedTargets >= 1) {
                                // V3 防线：多人裂地双劈 1/2 化解给予轻度破绽 +20%，保留认真防守队友的正反馈。
                                applyBossExposed(source.actorId(), BattleConstants.BOSS_EXPOSED_PARTIAL_PCT, r + 1);
                                cleaveParryCount++;
                                emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_CLEAVE_PARRY_EXPOSE,
                                        source.actorId(), source.actorId(), BattleConstants.BOSS_EXPOSED_PARTIAL_PCT);
                            }
                            recordResolved(roundResolved, entry,
                                    BattleConstants.TELEGRAPH_STATUS_EXECUTED, "慢速重劈已发动");
                        }
                    }
                }
                case BattleConstants.TELEGRAPH_KIND_BOSS_VENOM_AOE -> {
                    long[] targets = ctx.collectAliveEnemyIds(source);
                    if (targets.length == 0) {
                        break;
                    }
                    ctx.events().beginGroup();
                    double scale = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 1.05 : 0.85;
                    for (long targetId : targets) {
                        BattleUnit target = ctx.findActor(targetId);
                        if (target != null && target.isActive()) {
                            dealBossStrike(source, target, CombatDamageRules.MAGIC_DAMAGE, scale);
                        }
                    }
                    recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED, "玄毒冲击已发动");
                }
                case BattleConstants.TELEGRAPH_KIND_GUARD_HEAL -> {
                    BattleUnit boss = ctx.findActor(entry.getTargetActorId());
                    if (boss == null || !boss.isActive()) {
                        break;
                    }
                    double incomingHealMod = ctx.configured.modifier(boss, BattleEffect.IN_HEAL);
                    boolean antiHealSuppressed = incomingHealMod < 0.0;
                    double received = Math.max(0.0, 1.0 + incomingHealMod);
                    ctx.events().beginGroup();
                    long healed = 0L;
                    if (received > 0.0) {
                        long pct = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 10L : 8L;
                        long rawHeal = Math.max(1L, Long.divideUnsigned(boss.maxHealth() * pct, 100L));
                        healed = ctx.applyHeal(boss, Unsigned.toDouble(rawHeal) * received);
                        recordTrialMonsterHeal(source, boss, healed);
                        ctx.events().append(eBattleEventType.BATTLE_EVENT_HEAL, source.actorId(), boss.actorId())
                                .setValue(healed)
                                .setTargetHealthAfter(boss.health())
                                .setTargetManaAfter(boss.mana());
                    }
                    if (antiHealSuppressed) {
                        // A35 断脉咒压制回春：阻止困难净化，并使首领陷入 1 回合「气馁」（物攻 -15%）。
                        bossDisheartenedUntilRound = r + 1;
                        emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_ANTI_HEAL_DISHEARTEN,
                                source.actorId(), boss.actorId(), 15L);
                    } else if (Integer.compareUnsigned(battleDifficulty, 2) >= 0 && healed != 0L) {
                        // 困难回春进阶（方案 B1 / 双卫同阵）：有效治疗顺手净化首领 1 项可清除有害状态。
                        long removeConfiguredId = 0L;
                        int removeLegacyIdx = -1;
                        for (int idx = 0; idx < boss.state().getBuffsCount(); idx++) {
                            BattleBuffEntry b = boss.state().getBuffs(idx);
                            if (!ctx.configured.legacy.isDebuffEntry(b) || ctx.configured.listener(b)) {
                                continue;
                            }
                            BuffTable bRow = ctx.configured.row(b);
                            if (bRow != null && bRow.getBattleOp() != 0) {
                                removeConfiguredId = b.getBuffId();
                                break;
                            }
                            if (removeLegacyIdx < 0) {
                                removeLegacyIdx = idx;
                            }
                        }
                        if (removeConfiguredId != 0L) {
                            ctx.configured.remove(boss, removeConfiguredId);
                            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_REJUV_CLEANSE,
                                    source.actorId(), boss.actorId(), 1L);
                        } else if (removeLegacyIdx >= 0) {
                            ctx.configured.legacy.removeBuffAt(boss, removeLegacyIdx);
                            emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_REJUV_CLEANSE,
                                    source.actorId(), boss.actorId(), 1L);
                        }
                    }
                    recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED, "回春祈愈已发动");
                }
                case BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_PHYS,
                     BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_MAGIC -> {
                    BattleUnit boss = ctx.findActor(entry.getTargetActorId());
                    if (boss == null || !boss.isActive()) {
                        break;
                    }
                    ctx.events().beginGroup();
                    bossStanceType = entry.getIntentKind() == BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_PHYS
                            ? POLARITY_PHYSICAL
                            : POLARITY_MAGIC;
                    bossStanceRemainRounds = 3;
                    lastChosenStanceType = bossStanceType;
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_ADD, source.actorId(), boss.actorId())
                            .setValue(Integer.toUnsignedLong(bossStanceType));
                    // 困难·护势进阶（方案 B2）：切阵成功瞬间额外给首领叠 1 层「蓄势」。
                    if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                            && Integer.compareUnsigned(battleDifficulty, 2) >= 0
                            && bossMomentumStacks < BattleConstants.BOSS_MOMENTUM_MAX_STACKS) {
                        bossMomentumStacks++;
                        emitBossMechanicEvent(BattleConstants.BOSS_MECHANIC_MOMENTUM_GAIN,
                                source.actorId(), boss.actorId(), Integer.toUnsignedLong(bossMomentumStacks));
                    }
                    recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED,
                            bossStanceType == POLARITY_PHYSICAL ? "物防联防阵已展开" : "法防联防阵已展开");
                }
                case BattleConstants.TELEGRAPH_KIND_GUARD_SHIELD -> {
                    BattleUnit boss = ctx.findActor(entry.getTargetActorId());
                    if (boss == null || !boss.isActive()) {
                        break;
                    }
                    ctx.events().beginGroup();
                    long pct = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 18L : 12L;
                    long addShield = Math.max(1L, Long.divideUnsigned(boss.maxHealth() * pct, 100L));
                    bossBarrierShield = EquipmentCombatRules.addSaturated(bossBarrierShield, addShield);
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_SHIELD, source.actorId(), boss.actorId())
                            .setValue(addShield)
                            .setShieldAfter(bossBarrierShield);
                    recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED, "灵障护盾已附加");
                }
                case BattleConstants.TELEGRAPH_KIND_GUARD_BIND -> {
                    BattleUnit target = ctx.findActor(entry.getTargetActorId());
                    if (target == null || !target.isActive()) {
                        break;
                    }
                    ctx.events().beginGroup();
                    long resist = Unsigned.minUnsigned(100L,
                            EquipmentCombatRules.addSaturated(
                                    target.combat().getResistForget(),
                                    target.combat().getResistAllAilment()));
                    if (ctx.rng().rollPercent(resist)) {
                        ctx.events().append(eBattleEventType.BATTLE_EVENT_RESIST, source.actorId(), target.actorId());
                        markAntiControlOrSeasonSkill(ownerPlayerIdOf(target));
                        recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED, "缚灵锁脉已被抵抗");
                        break;
                    }
                    long rawDrain = Math.max(1L, Long.divideUnsigned(target.maxMana() * 15L, 100L));
                    long drain = Unsigned.minUnsigned(target.mana(), rawDrain);
                    if (drain != 0L) {
                        target.setMana(target.mana() - drain);
                        ctx.events().append(eBattleEventType.BATTLE_EVENT_MANA, source.actorId(), target.actorId())
                                .setValue(drain)
                                .setManaDelta(EventLog.signedManaDelta(drain, false))
                                .setTargetHealthAfter(target.health())
                                .setTargetManaAfter(target.mana());
                    }
                    recordResolved(roundResolved, entry, BattleConstants.TELEGRAPH_STATUS_EXECUTED, "缚灵锁脉已发动");
                }
                default -> {
                }
            }
        }
        if (!roundResolved.isEmpty()) {
            lastResolvedTelegraphs = List.copyOf(roundResolved);
        }
    }

    private void recordResolved(List<BattleTelegraphEntry> roundResolved, BattleTelegraphEntry base,
                                int status, String label) {
        BattleTelegraphEntry.Builder b = base.toBuilder()
                .setStatus(status)
                .setStateVersion(++telegraphStateVersion);
        if (label != null && !label.isEmpty()) {
            b.setLabel(label);
        }
        BattleTelegraphEntry resolved = b.build();
        ctx.events().addResolvedTelegraph(resolved);
        roundResolved.add(resolved);
    }

    void populateStateSnapshot(BattleStateS2C.Builder state, eBattleOutcome outcome, int maxRounds) {
        if (activePolarity != 0) {
            state.setActivePolarity(activePolarity)
                    .setNextPolaritySwitchRound(nextPolaritySwitchRound(maxRounds));
        }
        if (protectedGuardActorId != 0L) {
            state.setProtectedGuardActorId(protectedGuardActorId);
        }
        if (bossTrialKind != BattleConstants.BOSS_TRIAL_KIND_NONE) {
            state.setBossTrialKind(bossTrialKind)
                    .setBattleDifficulty(battleDifficulty);
        }
        if (isBossTrial()) {
            state.setBossPhase(bossPhase)
                    .setBossMomentumStacks(bossMomentumStacks);
            if (bossStanceType != POLARITY_NONE && bossStanceRemainRounds > 0) {
                state.setBossStanceType(bossStanceType)
                        .setBossStanceRemainRounds(bossStanceRemainRounds);
            }
            for (BattleUnit a : ctx.units()) {
                if (a.isMonster() && a.teamIndex() == 1 && a.isBoss()) {
                    ExposedState exp = exposedActors.get(a.actorId());
                    if (exp != null && Integer.compareUnsigned(exp.untilRound, ctx.roundIndex()) >= 0) {
                        state.setBossExposedPct(exp.pct)
                                .setBossExposedUntilRound(exp.untilRound);
                    }
                    break;
                }
            }
        }
        state.addAllLastResolvedTelegraphs(lastResolvedTelegraphs);
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            state.addAllTelegraphs(activeTelegraphs);
        }
    }

    /** 五行试炼志 V2：冻结权威战法事实与轻量阵容快照（不查配置表、不掷随机，对齐 C++ BuildSettlement）。 */
    void populateTrialFacts(CreateBattleRequest request, BattleUnit actor, long playerId,
                            BattleSettlementData.Builder settlement) {
        if (!request.hasTrialContext() || request.getTrialContext().getJournalEntryId() == 0) {
            return;
        }
        TrialBattleContext trialCtx = request.getTrialContext();
        BattleTrialJournalFacts.Builder facts = settlement.getTrialFactsBuilder()
                .setJournalEntryId(trialCtx.getJournalEntryId())
                .setTrialRuleVersion(trialCtx.getTrialRuleVersion())
                .setSeasonId(trialCtx.getSeasonId());

        record Teammate(long playerId, String name) {
        }
        int humanCount = 0;
        List<Teammate> teammates = new ArrayList<>();
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            if (snapshot.getTeamIndex() != actor.teamIndex()) {
                continue;
            }
            humanCount++;
            if (snapshot.getPlayerId() == playerId) {
                if (snapshot.getPetsCount() > 0) {
                    var pet = snapshot.getPets(0);
                    facts.setUsedPetTableId(pet.getCombatPetTableId() != 0
                            ? pet.getCombatPetTableId() : pet.getPetTableId());
                }
            } else {
                teammates.add(new Teammate(snapshot.getPlayerId(), snapshot.getPlayerName()));
            }
        }
        facts.setInitialHumanCount(humanCount);
        teammates.sort(Comparator.comparingLong(Teammate::playerId));
        int maxNames = Math.min(teammates.size(), TrialJournalRules.MAX_COOP_TEAMMATES);
        for (int i = 0; i < maxNames; i++) {
            if (!teammates.get(i).name().isEmpty()) {
                facts.addTeammateNames(teammates.get(i).name());
            }
        }

        boolean selfOrPetDied = everDiedActors.contains(playerId);
        boolean guardSurvived = false;
        if (protectedGuardActorId != 0L) {
            BattleUnit protectedGuard = ctx.findActor(protectedGuardActorId);
            guardSurvived = protectedGuard != null && !protectedGuard.isDead();
        }
        for (BattleUnit other : ctx.units()) {
            if (other.isPet() && other.ownerPlayerId() == playerId && everDiedActors.contains(other.actorId())) {
                selfOrPetDied = true;
            }
            if (protectedGuardActorId == 0L
                    && other.isMonster() && other.teamIndex() != actor.teamIndex()
                    && !other.isBoss() && !other.isDead()
                    && (trialCtx.getGuardMonsterTableId() == 0
                    || other.monsterTableId() == trialCtx.getGuardMonsterTableId())) {
                guardSurvived = true;
            }
        }
        int myTeam = actor.teamIndex();
        int selfTargeted = selfCleavesTargetedByPlayer.getOrDefault(playerId, 0);
        int selfParried = selfCleavesParriedByPlayer.getOrDefault(playerId, 0);
        facts.setSelfOrPetEverDied(selfOrPetDied)
                .setBossGuardHealTotal(bossGuardHealTotal)
                .setGuardStanceDispelled(guardStanceDispelled)
                .setTeamDealtPhysicalDirectDamage(myTeam >= 0 && myTeam < 2 && teamDealtPhysicalDirectDamage[myTeam])
                .setTeamDealtMagicDirectDamage(myTeam >= 0 && myTeam < 2 && teamDealtMagicDirectDamage[myTeam])
                .setGuardSurvived(guardSurvived)
                .setSelfTriggeredShieldBreak(shieldBreakPlayers.contains(playerId))
                .setSelfTriggeredAntiControlOrSeasonSkill(antiControlOrSeasonSkillPlayers.contains(playerId))
                .setCleaveParryCount(cleaveParryCount)
                .setHealInterruptBacklashCount(healInterruptBacklashCount)
                .setStanceBreakCount(stanceBreakCount)
                .setSelfParriedCleaveAtLeastOnce(selfParried > 0)
                .setAllSelfCleavesParried(selfTargeted > 0 && selfParried == selfTargeted);

        List<Integer> rawEffects = new ArrayList<>();
        for (EquipmentArtifactSource artifact : actor.state().getEquipmentEffects().getArtifactsList()) {
            rawEffects.add(artifact.getEffectId());
        }
        facts.addAllUsedArtifactEffectIds(TrialJournalRules.normalizeArtifactEffectIds(rawEffects));
        if (actor.state().getEquipmentEffects().hasSeasonSeal()) {
            facts.setUsedSeasonSealId(actor.state().getEquipmentEffects().getSeasonSeal().getSealId());
        }
    }

    private BattleUnit findAliveBoss() {
        for (BattleUnit actor : ctx.units()) {
            if (actor.isMonster() && actor.teamIndex() == 1 && actor.isBoss() && actor.isActive()) {
                return actor;
            }
        }
        return null;
    }

    private boolean isActorControlLocked(BattleUnit actor, ActionChecks checks) {
        return checks.checkState(actor) != ActionChecks.SUCCESS
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_SILENCE)
                || ctx.configured.hasState(actor, BattleEffect.FREEZE)
                || ctx.configured.hasState(actor, BattleEffect.SLEEP)
                || ctx.configured.hasState(actor, BattleEffect.CONFUSION)
                || ctx.configured.hasState(actor, BattleEffect.SEAL_ACTIVE)
                || ctx.configured.hasState(actor, BattleEffect.SEAL_MAGIC)
                || ctx.configured.hasState(actor, BattleEffect.SEAL_PHYSICAL);
    }

    private void dealBossStrike(BattleUnit source, BattleUnit target, int damageType, double scale) {
        long phys = ctx.effectivePhysicalAttack(source);
        long attack = damageType == CombatDamageRules.MAGIC_DAMAGE
                ? (Long.compareUnsigned(source.magicAttack(), phys) >= 0 ? source.magicAttack() : phys)
                : phys;
        BattleContext.FinalDamage raw = ctx.calculateFinalDamage(source, target,
                BattleConstants.BASIC_ATTACK_BASE_DAMAGE * scale, attack, scale, damageType);
        if (ctx.configured.activeFor(source, target)) {
            BattleEffect.Hit hit = new BattleEffect.Hit(0, 1, BattleEffect.DIRECT, target.actorId());
            hit.critical = raw.critical();
            ctx.configured.impact.damage(source, target, raw.value(), damageType, hit, eBattleHitKind.BATTLE_HIT_NORMAL);
        } else {
            long dealt = applyLegacyDamage(source, target, raw.value(), damageType, true);
            ctx.events().append(eBattleEventType.BATTLE_EVENT_DAMAGE, source.actorId(), target.actorId())
                    .setValue(dealt)
                    .setIsCritical(raw.critical())
                    .setTargetHealthAfter(target.health())
                    .setTargetManaAfter(target.mana());
            if (target.health() == 0L) {
                ctx.handleDeath(target, source.actorId());
            }
        }
    }

    private boolean hasEntryFor(long sourceId, int execRound) {
        for (BattleTelegraphEntry e : activeTelegraphs) {
            if (e.getSourceActorId() == sourceId && e.getExecuteRound() == execRound) {
                return true;
            }
        }
        return false;
    }

    private long pickEnemyTarget(BattleUnit caster) {
        long[] targets = ctx.collectAliveEnemyIds(caster);
        if (targets.length == 0) {
            return 0L;
        }
        if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_GUARDIAN_RESCUE && protectedGuardActorId != 0L) {
            BattleUnit allyGuard = ctx.findActor(protectedGuardActorId);
            if (allyGuard != null && allyGuard.isActive()) {
                return protectedGuardActorId;
            }
        }
        return targets[(int) ctx.rng().randIndex(targets.length)];
    }

    private List<Long> pickCleaveTargets(BattleUnit caster, boolean dualTarget) {
        long[] targets = ctx.collectAliveEnemyIds(caster);
        if (targets.length == 0) {
            return List.of();
        }
        long first = pickEnemyTarget(caster);
        List<Long> chosen = new ArrayList<>(2);
        if (first != 0L) {
            chosen.add(first);
        }
        if (!dualTarget || targets.length < 2 || chosen.isEmpty()) {
            return chosen;
        }
        BattleUnit firstUnit = ctx.findActor(first);
        long firstOwner = firstUnit == null ? 0L
                : (firstUnit.isPet() ? firstUnit.ownerPlayerId() : firstUnit.actorId());
        List<Long> diffOwnerPool = new ArrayList<>();
        List<Long> fallbackPool = new ArrayList<>();
        for (long id : targets) {
            if (id == first) {
                continue;
            }
            BattleUnit u = ctx.findActor(id);
            long owner = u == null ? 0L : (u.isPet() ? u.ownerPlayerId() : u.actorId());
            if (firstOwner != 0L && owner != firstOwner) {
                diffOwnerPool.add(id);
            } else {
                fallbackPool.add(id);
            }
        }
        List<Long> pool = !diffOwnerPool.isEmpty() ? diffOwnerPool : fallbackPool;
        if (!pool.isEmpty()) {
            chosen.add(pool.get((int) ctx.rng().randIndex(pool.size())));
        }
        return chosen;
    }
}
