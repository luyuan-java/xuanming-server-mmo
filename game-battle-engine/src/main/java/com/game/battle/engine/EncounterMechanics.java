package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 首领试炼（101–108）、护卫救援（109）、物法易势 5V5（110）与有效对抗统计的局内权威机制
 * （对齐 C++ {@code turn_battle_engine.cpp} 特殊玩法段，保证全确定性且零侵入存量常规对局）。
 */
final class EncounterMechanics {

    private final BattleContext ctx;
    private int bossTrialKind = BattleConstants.BOSS_TRIAL_KIND_NONE;
    private int battleDifficulty;
    private int trialGuardMonsterTableId;
    private int trialGuardStanceBuffId;
    private long protectedGuardActorId;
    private int initialPolarity;
    private int activePolarity;
    private int bossStanceType;
    private long bossBarrierShield;
    private long bossGuardHealTotal;
    private final boolean[] teamDealtPhysicalDirectDamage = new boolean[2];
    private final boolean[] teamDealtMagicDirectDamage = new boolean[2];
    private final TreeSet<Long> everDiedActors = new TreeSet<>(Long::compareUnsigned);
    private final List<BattleTelegraphEntry> activeTelegraphs = new ArrayList<>();
    private final NavigableMap<Long, TreeSet<Integer>> manualActionRoundsByPlayer =
            new TreeMap<>(Long::compareUnsigned);
    private final TreeSet<Long> effectiveContributionPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> shieldBreakPlayers = new TreeSet<>(Long::compareUnsigned);
    private final TreeSet<Long> antiControlOrSeasonSkillPlayers = new TreeSet<>(Long::compareUnsigned);
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
        } else if (damageType == CombatDamageRules.MAGIC_DAMAGE) {
            teamDealtMagicDirectDamage[source.teamIndex()] = true;
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

    boolean isBossTrialOrRescue() {
        return bossTrialKind >= BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV
                && bossTrialKind <= BattleConstants.BOSS_TRIAL_KIND_GUARDIAN_RESCUE;
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

    long bossBarrierShield() {
        return bossBarrierShield;
    }

    boolean guardStanceDispelled() {
        return guardStanceDispelled;
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
        return activePolarity != 0
                || bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                || bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER;
    }

    /** 存量非配置伤害路径的落血与特殊玩法修正合一；常规对局直接走原 {@link BattleUnit#applyDamage} 保逐字节兼容。 */
    long applyLegacyDamage(BattleUnit source, BattleUnit target, double rawDamage, int damageType, boolean isDirectPrimary) {
        if (!hasActiveDamageModifiers()) {
            return target.applyDamage(rawDamage);
        }
        if (rawDamage <= 0.0 || !Double.isFinite(rawDamage)) {
            return 0L;
        }
        double effective = target.isDefending() ? rawDamage * 0.5 : rawDamage;
        long remaining = CombatDamageRules.damageToHealth(effective, -1L);
        remaining = applyDamageModifiers(source, target, remaining, damageType, isDirectPrimary);
        long dealt = Unsigned.minUnsigned(target.health(), remaining);
        target.setHealth(target.health() - dealt);
        return dealt;
    }

    static final int POLARITY_NONE = 0;
    static final int POLARITY_PHYSICAL = 1;
    static final int POLARITY_MAGIC = 2;

    static int polarityOfDamageType(int damageType) {
        return damageType == CombatDamageRules.PHYSICAL_DAMAGE ? POLARITY_PHYSICAL
                : damageType == CombatDamageRules.MAGIC_DAMAGE ? POLARITY_MAGIC : POLARITY_NONE;
    }

    /** 物法易势 ×1.15、破阵护势 30%/40% 减伤、灵障护盾吸收（常规减伤/防御后、生命扣血前）。 */
    long applyDamageModifiers(BattleUnit source, BattleUnit target, long damage, int damageType, boolean isDirectPrimary) {
        if (damage == 0L) {
            return 0L;
        }
        long result = damage;
        int dmgPolarity = polarityOfDamageType(damageType);
        // 1. 物法易势 5V5：与当前易势同类型的直接主伤害 ×115/100（整数向下取整一次；1=物势，2=法势）。
        if (isDirectPrimary && activePolarity != POLARITY_NONE && activePolarity == dmgPolarity) {
            result = Long.divideUnsigned(result * BattleConstants.POLARITY_DAMAGE_NUM, BattleConstants.POLARITY_DAMAGE_DEN);
        }
        // 2. 破阵·护势：首领处于同系护势减伤时，直接伤害降低 30%（普通）/ 40%（困难）。
        if (isDirectPrimary && bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                && target.isBoss() && bossStanceType != POLARITY_NONE && bossStanceType == dmgPolarity) {
            long keepPct = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 60L : 70L;
            result = Long.divideUnsigned(result * keepPct, 100L);
        }
        // 3. 灵障首领：灵障护盾在生命扣血前优先吸收伤害，击破时记破盾者与有效贡献。
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
                || bossTrialKind != BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE
                || bossStanceType == 0 || !target.isBoss()
                || source.teamIndex() == target.teamIndex()) {
            return;
        }
        bossStanceType = 0;
        guardStanceDispelled = true;
        recordEffectiveContribution(source);
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
                kept.add(entry.getStatus() == nextStatus ? entry : entry.toBuilder().setStatus(nextStatus).build());
            }
        }
        activeTelegraphs.clear();
        activeTelegraphs.addAll(kept);

        if (!isBossTrialOrRescue()) {
            return;
        }

        BattleUnit boss = null;
        BattleUnit guard = null;
        for (BattleUnit actor : ctx.units()) {
            if (!actor.isMonster() || actor.teamIndex() != 1 || !actor.isActive()) {
                continue;
            }
            if (actor.isBoss()) {
                boss = actor;
            } else if (guard == null) {
                guard = actor;
            }
        }

        // 1. 首领蓄力慢速重击：在 R1/4/7/10/13 预告，次轮（R2/5/8/11/14）慢速结算。
        if (boss != null && Integer.remainderUnsigned(r, 3) == 1
                && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                && !hasEntryFor(boss.actorId(), r + 1)) {
            BattleTelegraphEntry.Builder entry = BattleTelegraphEntry.newBuilder()
                    .setSourceActorId(boss.actorId())
                    .setExecuteRound(r + 1)
                    .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING);
            if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER) {
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE)
                        .setTargetActorId(pickEnemyTarget(boss))
                        .setLabel("灵障重击：下回合慢速法术重击，防御可减半、控场可打断");
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_VENOM) {
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_BOSS_VENOM_AOE)
                        .setTargetActorId(0L)
                        .setLabel("玄毒冲击：下回合慢速群体法术伤害，防御可减半、控场可打断");
            } else {
                entry.setIntentKind(BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE)
                        .setTargetActorId(pickEnemyTarget(boss))
                        .setLabel("蓄力重击：下回合慢速物理重击，防御可减半、控场可打断");
            }
            activeTelegraphs.add(entry.build());
        }

        // 2. 敌方护卫周期机制预告。
        if (guard != null && boss != null) {
            if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV) {
                int triggerMod = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 1 : 2;
                if (Integer.remainderUnsigned(r, 3) == triggerMod
                        && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                        && !hasEntryFor(guard.actorId(), r + 1)) {
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(guard.actorId())
                            .setTargetActorId(boss.actorId())
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_HEAL)
                            .setExecuteRound(r + 1)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING)
                            .setLabel(Integer.compareUnsigned(battleDifficulty, 2) >= 0
                                    ? "回春祈愈：下回合为首领回复10%最大生命"
                                    : "回春祈愈：下回合为首领回复8%最大生命")
                            .build());
                }
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BREAK_STANCE) {
                if (Integer.remainderUnsigned(r, 3) == 1 && !hasEntryFor(guard.actorId(), r)) {
                    boolean isPhysical = (((r - 1) / 3) % 2 == 0);
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(guard.actorId())
                            .setTargetActorId(boss.actorId())
                            .setIntentKind(isPhysical
                                    ? BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_PHYS
                                    : BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_MAGIC)
                            .setExecuteRound(r)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_EXECUTING)
                            .setLabel(isPhysical
                                    ? "物理护势：本回合为首领施加物理减伤护势（可驱散）"
                                    : "法术护势：本回合为首领施加法术减伤护势（可驱散）")
                            .build());
                }
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_BARRIER) {
                if (Integer.remainderUnsigned(r, 3) == 1 && !hasEntryFor(guard.actorId(), r)) {
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(guard.actorId())
                            .setTargetActorId(boss.actorId())
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_SHIELD)
                            .setExecuteRound(r)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_EXECUTING)
                            .setLabel("灵障护佑：本回合为首领附加灵障护盾")
                            .build());
                }
            } else if (bossTrialKind == BattleConstants.BOSS_TRIAL_KIND_VENOM) {
                if (Integer.remainderUnsigned(r, 3) == 1
                        && Integer.compareUnsigned(r + 1, maxRounds) <= 0
                        && !hasEntryFor(guard.actorId(), r + 1)) {
                    activeTelegraphs.add(BattleTelegraphEntry.newBuilder()
                            .setSourceActorId(guard.actorId())
                            .setTargetActorId(pickEnemyTarget(guard))
                            .setIntentKind(BattleConstants.TELEGRAPH_KIND_GUARD_BIND)
                            .setExecuteRound(r + 1)
                            .setStatus(BattleConstants.TELEGRAPH_STATUS_UPCOMING)
                            .setLabel("缚灵锁脉：下回合封控目标并消耗法力")
                            .build());
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
        for (BattleTelegraphEntry entry : due) {
            if (entry.getExecuteRound() != r) {
                continue;
            }
            BattleUnit source = ctx.findActor(entry.getSourceActorId());
            if (source == null || !source.isActive() || isActorControlLocked(source, checks)) {
                continue;
            }
            switch (entry.getIntentKind()) {
                case BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE,
                     BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE -> {
                    BattleUnit target = ctx.findActor(entry.getTargetActorId());
                    if (target == null || !target.isActive()) {
                        break;
                    }
                    ctx.events().beginGroup();
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_ATTACK, source.actorId(), target.actorId());
                    boolean magic = entry.getIntentKind() == BattleConstants.TELEGRAPH_KIND_BOSS_MAGIC_STRIKE;
                    double scale = magic
                            ? (Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 2.0 : 1.7)
                            : (Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 2.1 : 1.8);
                    int dmgType = magic ? CombatDamageRules.MAGIC_DAMAGE : CombatDamageRules.PHYSICAL_DAMAGE;
                    dealBossStrike(source, target, dmgType, scale);
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
                }
                case BattleConstants.TELEGRAPH_KIND_GUARD_HEAL -> {
                    BattleUnit boss = ctx.findActor(entry.getTargetActorId());
                    if (boss == null || !boss.isActive()) {
                        break;
                    }
                    double received = Math.max(0.0, 1.0 + ctx.configured.modifier(boss, BattleEffect.IN_HEAL));
                    if (received <= 0.0) {
                        break;
                    }
                    ctx.events().beginGroup();
                    long pct = Integer.compareUnsigned(battleDifficulty, 2) >= 0 ? 10L : 8L;
                    long rawHeal = Math.max(1L, Long.divideUnsigned(boss.maxHealth() * pct, 100L));
                    long healed = boss.applyHeal(Unsigned.toDouble(rawHeal) * received);
                    recordTrialMonsterHeal(source, boss, healed);
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_HEAL, source.actorId(), boss.actorId())
                            .setValue(healed)
                            .setTargetHealthAfter(boss.health())
                            .setTargetManaAfter(boss.mana());
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
                    ctx.events().append(eBattleEventType.BATTLE_EVENT_BUFF_ADD, source.actorId(), boss.actorId())
                            .setValue(Integer.toUnsignedLong(bossStanceType));
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
                }
                default -> {
                }
            }
        }
    }

    void populateStateSnapshot(BattleStateS2C.Builder state, eBattleOutcome outcome, int maxRounds) {
        if (activePolarity != 0) {
            state.setActivePolarity(activePolarity)
                    .setNextPolaritySwitchRound(nextPolaritySwitchRound(maxRounds));
        }
        if (protectedGuardActorId != 0L) {
            state.setProtectedGuardActorId(protectedGuardActorId);
        }
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
        facts.setSelfOrPetEverDied(selfOrPetDied)
                .setBossGuardHealTotal(bossGuardHealTotal)
                .setGuardStanceDispelled(guardStanceDispelled)
                .setTeamDealtPhysicalDirectDamage(myTeam >= 0 && myTeam < 2 && teamDealtPhysicalDirectDamage[myTeam])
                .setTeamDealtMagicDirectDamage(myTeam >= 0 && myTeam < 2 && teamDealtMagicDirectDamage[myTeam])
                .setGuardSurvived(guardSurvived)
                .setSelfTriggeredShieldBreak(shieldBreakPlayers.contains(playerId))
                .setSelfTriggeredAntiControlOrSeasonSkill(antiControlOrSeasonSkillPlayers.contains(playerId));

        List<Integer> rawEffects = new ArrayList<>();
        for (EquipmentArtifactSource artifact : actor.state().getEquipmentEffects().getArtifactsList()) {
            rawEffects.add(artifact.getEffectId());
        }
        facts.addAllUsedArtifactEffectIds(TrialJournalRules.normalizeArtifactEffectIds(rawEffects));
        if (actor.state().getEquipmentEffects().hasSeasonSeal()) {
            facts.setUsedSeasonSealId(actor.state().getEquipmentEffects().getSeasonSeal().getSealId());
        }
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
}
