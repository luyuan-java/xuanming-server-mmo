package com.game.scene.progression;

import com.game.battle.engine.BattleConstants;
import com.game.player.store.state.BattleEconomyLedgerState;
import com.game.proto.BattleCurrencyGain;
import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.Wallet;
import java.util.List;

/**
 * 角色平滑升级曲线（1→85）与全玩法经验/银两周软上限纯规则（零 IO / 零外部状态，与 C++ {@code battle_progression_rules.h} 逐口径对齐）。
 * 所有 64 位经验与银两均按无符号 64 位语义运算（{@code Long.compareUnsigned} / {@code divideUnsigned} / {@code remainderUnsigned}）。
 */
public final class BattleProgressionRules {

    public static final int WUXING_TOWER_LOOKUP_MODE = 99;
    public static final int OUTCOME_TIER_WIN = 1;
    public static final int OUTCOME_TIER_QUALIFIED_NON_WIN = 2;
    public static final int PVP_MIN_ROUNDS_FOR_LOSS = 3;
    public static final int PVP_MIN_MANUAL_ROUNDS_FOR_LOSS = 2;
    public static final long PERMYRIAD_BASE = 10_000L;

    private BattleProgressionRules() {
    }

    public record LevelExpRow(int level, long requiredTotalExp) {
    }

    public record ModeRewardRow(int matchMode, int difficulty, int outcomeTier, long expGain, long silverGain) {
    }

    public record WeeklySilverRule(long softCap, int diminishedRatePermyriad, long hardCap) {
    }

    public record LevelExpDisplay(long totalExp, long currentLevelExp, long nextLevelExp,
                                  long nextLevelTotalExp, boolean isMaxLevel) {
    }

    public record LevelProgressResult(int storedLevelBefore, int storedLevelAfter,
                                      int levelBefore, int levelAfter,
                                      long totalExpAfter, long nextLevelTotalExp,
                                      boolean leveledUp) {
    }

    public record WeeklySilverEval(long creditedSilver, long usedAfter, boolean diminished) {
    }

    public record LookupKey(int lookupMode, int difficulty, int outcomeTier) {
    }

    public record BattleProgressionDelta(long expGain, long silverGain,
                                         int levelBefore, int levelAfter,
                                         long totalExpAfter, long nextLevelTotalExp,
                                         long weeklySilverUsedAfter, long weeklySilverSoftCap,
                                         boolean silverDiminished, boolean leveledUp,
                                         boolean ledgerChanged, boolean usedTableSilver) {
        public static final BattleProgressionDelta NONE =
                new BattleProgressionDelta(0L, 0L, 0, 0, 0L, 0L, 0L, 0L, false, false, false, false);

        public BattleProgressionDelta withLevelProgress(LevelProgressResult lp) {
            return new BattleProgressionDelta(
                    expGain, silverGain,
                    lp.levelBefore(), lp.levelAfter(),
                    lp.totalExpAfter(), lp.nextLevelTotalExp(),
                    weeklySilverUsedAfter, weeklySilverSoftCap,
                    silverDiminished, lp.leveledUp(), ledgerChanged, usedTableSilver);
        }

        public BattleProgressionDelta withSilverBlocked(long weeklySilverUsedBefore) {
            return new BattleProgressionDelta(
                    expGain, 0L,
                    levelBefore, levelAfter,
                    totalExpAfter, nextLevelTotalExp,
                    weeklySilverUsedBefore, weeklySilverSoftCap,
                    false, leveledUp, ledgerChanged, false);
        }
    }

    public record PreparedEconomy(BattleEconomyLedgerState candidateLedger, BattleProgressionDelta delta) {
    }

    /** 校验 1..85 经验表：连续无空洞、1 级恒为 0、后续严格单调递增。 */
    public static boolean validateLevelExpRows(List<LevelExpRow> rows) {
        if (rows == null || rows.size() != PlayerLevels.MAX_LEVEL) {
            return false;
        }
        long prevExp = 0L;
        for (int expected = 1; expected <= PlayerLevels.MAX_LEVEL; expected++) {
            LevelExpRow row = rows.get(expected - 1);
            if (row.level() != expected) {
                return false;
            }
            if (expected == 1) {
                if (row.requiredTotalExp() != 0L) {
                    return false;
                }
            } else if (Long.compareUnsigned(row.requiredTotalExp(), prevExp) <= 0) {
                return false;
            }
            prevExp = row.requiredTotalExp();
        }
        return true;
    }

    public static boolean validateWeeklySilverRule(WeeklySilverRule rule) {
        return rule != null
                && Integer.compareUnsigned(rule.diminishedRatePermyriad(), (int) PERMYRIAD_BASE) <= 0
                && Long.compareUnsigned(rule.hardCap(), rule.softCap()) >= 0;
    }

    /** 计算面板 / 战果展示用的本级经验进度（绝不因 GM 调级后 totalExp &lt; req(level) 下溢）。 */
    public static LevelExpDisplay computeLevelExpDisplay(int effectiveLevel, long totalExp, List<LevelExpRow> sortedRows) {
        int lvl = PlayerLevels.effective(PlayerLevels.clampStored(effectiveLevel));
        if (lvl >= PlayerLevels.MAX_LEVEL) {
            return new LevelExpDisplay(totalExp, 0L, 0L, 0L, true);
        }
        if (!validateLevelExpRows(sortedRows)) {
            return new LevelExpDisplay(totalExp, 0L, 0L, 0L, false);
        }
        long curReq = sortedRows.get(lvl - 1).requiredTotalExp();
        long nextReq = sortedRows.get(lvl).requiredTotalExp();
        long span = Long.compareUnsigned(nextReq, curReq) > 0 ? (nextReq - curReq) : 0L;
        long curExp = 0L;
        if (Long.compareUnsigned(totalExp, curReq) >= 0 && span != 0L) {
            long diff = totalExp - curReq;
            curExp = Long.compareUnsigned(diff, span) < 0 ? diff : span;
        }
        return new LevelExpDisplay(totalExp, curExp, span, nextReq, false);
    }

    /**
     * 按累计总经验推进存储等级：
     * - total_exp 无符号溢出返回 null（整笔结算延后）；
     * - storedLevel &gt;= 85（含混版本超上限）只加经验、绝不降级；
     * - 始终满足 newStoredLevel &gt;= max(1, storedLevel)（GM 调高级号绝不回退）；
     * - sortedRows 为空（旧单测未加载表）时仅累计经验不改级。
     */
    public static LevelProgressResult resolveLevelForExp(int storedLevel, long currentTotalExp, long expGain,
                                                         List<LevelExpRow> sortedRows) {
        long room = -1L - currentTotalExp; // uint64 max - currentTotalExp
        if (Long.compareUnsigned(expGain, room) > 0) {
            return null;
        }
        int levelBefore = PlayerLevels.effective(PlayerLevels.clampStored(storedLevel));
        long totalExpAfter = currentTotalExp + expGain;
        int storedLevelAfter;
        int levelAfter;
        boolean leveledUp;

        boolean storedAtOrAboveMax = storedLevel < 0 || storedLevel >= PlayerLevels.MAX_LEVEL;
        if (sortedRows == null || sortedRows.isEmpty() || !validateLevelExpRows(sortedRows) || storedAtOrAboveMax) {
            storedLevelAfter = storedLevel;
            levelAfter = PlayerLevels.effective(PlayerLevels.clampStored(storedLevel));
            leveledUp = false;
        } else {
            int targetLevel = 1;
            int lo = 0;
            int hi = sortedRows.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (Long.compareUnsigned(totalExpAfter, sortedRows.get(mid).requiredTotalExp()) < 0) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            if (lo > 0) {
                targetLevel = sortedRows.get(lo - 1).level();
            }
            targetLevel = Math.min(targetLevel, PlayerLevels.MAX_LEVEL);
            int baseStored = storedLevel == 0 ? 1 : storedLevel;
            storedLevelAfter = Math.max(baseStored, targetLevel);
            levelAfter = PlayerLevels.effective(PlayerLevels.clampStored(storedLevelAfter));
            leveledUp = storedLevelAfter > baseStored;
        }
        LevelExpDisplay display = computeLevelExpDisplay(levelAfter, totalExpAfter, sortedRows);
        return new LevelProgressResult(storedLevel, storedLevelAfter, levelBefore, levelAfter,
                totalExpAfter, display.nextLevelTotalExp(), leveledUp);
    }

    public static boolean isPvpProgressionMode(int matchMode) {
        return matchMode == MatchMode.MATCH_MODE_5V5_VALUE
                || matchMode == MatchMode.MATCH_MODE_1V1_VALUE
                || matchMode == MatchMode.MATCH_MODE_PVP_TEAM_VALUE
                || matchMode == MatchMode.MATCH_MODE_POLARITY_5V5_VALUE
                || matchMode == MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE
                || matchMode == MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE;
    }

    public static boolean isGuildWarMode(int matchMode) {
        return BattleConstants.isGuildWarMatchMode(matchMode);
    }

    /** 提取结算里显式下发的银两（合并 gold_gain 与 currency_gains 中的 GOLD 条目）；溢出返回 -1。 */
    public static long extractExplicitSilver(BattleSettlementData settlement) {
        long explicit = settlement.getGoldGain();
        if (explicit < 0L) {
            return -1L;
        }
        for (BattleCurrencyGain gain : settlement.getCurrencyGainsList()) {
            if (gain.getCurrencyType() == Wallet.GOLD) {
                long amt = gain.getAmount();
                if (amt < 0L || Long.MAX_VALUE - explicit < amt) {
                    return -1L;
                }
                explicit += amt;
            }
        }
        return explicit;
    }

    /** 判定单局是否具备基础经验/银两资格，返回查表键；无资格返回 null。 */
    public static LookupKey resolveBattleLookupKey(BattleSettlementData settlement) {
        eBattleOutcome outcome = settlement.getOutcome();
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_VOID
                || outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING
                || settlement.getTotalRounds() == 0
                || settlement.getFled()) {
            return null;
        }
        if ((settlement.hasFestivalBeast() && settlement.getFestivalBeast().getActivityInstanceId() != 0)
                || BattleConstants.isNpcIsolatedEncounterKind(settlement.getBossTrialKind())) {
            return null;
        }
        int mode = settlement.getMatchMode();
        if (mode == MatchMode.MATCH_MODE_UNSPECIFIED_VALUE || mode == MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE) {
            return null;
        }
        boolean won = (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                || (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1);

        boolean isWuxingTower = settlement.hasTowerSettlement() && settlement.getTowerSettlement().getFloor() != 0;
        if (isWuxingTower) {
            return won ? new LookupKey(WUXING_TOWER_LOOKUP_MODE, 0, OUTCOME_TIER_WIN) : null;
        }

        if (isPvpProgressionMode(mode)) {
            if (won) {
                return new LookupKey(mode, 0, OUTCOME_TIER_WIN);
            }
            if (Integer.compareUnsigned(settlement.getTotalRounds(), PVP_MIN_ROUNDS_FOR_LOSS) >= 0
                    && Integer.compareUnsigned(settlement.getManualActionRounds(), PVP_MIN_MANUAL_ROUNDS_FOR_LOSS) >= 0
                    && settlement.getEffectiveCombatContribution()) {
                return new LookupKey(mode, 0, OUTCOME_TIER_QUALIFIED_NON_WIN);
            }
            return null;
        }

        if (!won) {
            return null;
        }
        return new LookupKey(mode, settlement.getBattleDifficulty(), OUTCOME_TIER_WIN);
    }

    public static ModeRewardRow findModeReward(List<ModeRewardRow> rows, int lookupMode, int difficulty, int outcomeTier) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        ModeRewardRow fallback = null;
        for (ModeRewardRow row : rows) {
            if (row.matchMode() != lookupMode || row.outcomeTier() != outcomeTier) {
                continue;
            }
            if (row.difficulty() == difficulty) {
                return row;
            }
            if (row.difficulty() == 0 && fallback == null) {
                fallback = row;
            }
        }
        return fallback;
    }

    /** 对原始银两施加每周软上限 + 衰减比例 + 硬封顶（精确跨边界拆分）。 */
    public static WeeklySilverEval evaluateWeeklySilverCap(long rawSilver, long usedThisWeek, WeeklySilverRule rule) {
        if (rawSilver == 0L) {
            boolean alreadyOver = rule.softCap() != 0L && Long.compareUnsigned(usedThisWeek, rule.softCap()) >= 0;
            return new WeeklySilverEval(0L, usedThisWeek, alreadyOver);
        }
        if (rule.hardCap() != 0L && Long.compareUnsigned(usedThisWeek, rule.hardCap()) >= 0) {
            return new WeeklySilverEval(0L, usedThisWeek, true);
        }
        long fullRoom = Long.compareUnsigned(rule.softCap(), usedThisWeek) > 0 ? (rule.softCap() - usedThisWeek) : 0L;
        long fullPart = Long.compareUnsigned(rawSilver, fullRoom) < 0 ? rawSilver : fullRoom;
        long overflowPart = rawSilver - fullPart;
        long diminishedPart = 0L;
        long rate = Integer.toUnsignedLong(rule.diminishedRatePermyriad());
        if (overflowPart != 0L && rate != 0L) {
            long q = Long.divideUnsigned(overflowPart, PERMYRIAD_BASE);
            long r = Long.remainderUnsigned(overflowPart, PERMYRIAD_BASE);
            diminishedPart = q * rate + Long.divideUnsigned(r * rate, PERMYRIAD_BASE);
        }
        long total = fullPart + diminishedPart;
        if (rule.hardCap() != 0L) {
            long hardRoom = Long.compareUnsigned(rule.hardCap(), usedThisWeek) > 0 ? (rule.hardCap() - usedThisWeek) : 0L;
            if (Long.compareUnsigned(total, hardRoom) > 0) {
                total = hardRoom;
            }
        }
        long usedAfter = usedThisWeek + total;
        boolean diminished = overflowPart != 0L
                || (rule.hardCap() != 0L && Long.compareUnsigned(usedAfter, rule.hardCap()) >= 0);
        return new WeeklySilverEval(total, usedAfter, diminished);
    }

    /** 统一在当前周账本上计算本局最终经验、实得银两与更新后的周账本候选。 */
    public static PreparedEconomy prepareEconomyLedger(BattleEconomyLedgerState current, long currentWeekStartMs,
                                                       BattleSettlementData settlement,
                                                       long explicitExp, long explicitSilver,
                                                       List<ModeRewardRow> rewardRows,
                                                       WeeklySilverRule rule) {
        BattleEconomyLedgerState base = current == null ? BattleEconomyLedgerState.getDefaultInstance() : current;
        BattleEconomyLedgerState.Builder cand = base.toBuilder();

        boolean weekReset = false;
        if (Long.compareUnsigned(currentWeekStartMs, cand.getWeekStartsAtMs()) > 0) {
            cand.setWeekStartsAtMs(currentWeekStartMs)
                    .setBattleSilverThisWeek(0L)
                    .setBattleExpThisWeek(0L);
            weekReset = true;
        }

        long tableExp = 0L;
        long tableSilver = 0L;
        if (rewardRows != null && !rewardRows.isEmpty()) {
            LookupKey key = resolveBattleLookupKey(settlement);
            if (key != null) {
                ModeRewardRow row = findModeReward(rewardRows, key.lookupMode(), key.difficulty(), key.outcomeTier());
                if (row != null) {
                    tableExp = row.expGain();
                    tableSilver = isGuildWarMode(settlement.getMatchMode()) ? 0L : row.silverGain();
                }
            }
        }

        long finalExp = Long.compareUnsigned(explicitExp, tableExp) >= 0 ? explicitExp : tableExp;
        long rawSilver = isGuildWarMode(settlement.getMatchMode())
                ? explicitSilver
                : (Long.compareUnsigned(explicitSilver, tableSilver) >= 0 ? explicitSilver : tableSilver);

        long creditedSilver = rawSilver;
        boolean diminished = false;
        long usedAfter = cand.getBattleSilverThisWeek();
        long softCap = 0L;
        boolean usedTableSilver = false;

        if (validateWeeklySilverRule(rule)) {
            softCap = rule.softCap();
            if (tableSilver != 0L) {
                usedTableSilver = true;
                WeeklySilverEval eval = evaluateWeeklySilverCap(rawSilver, cand.getBattleSilverThisWeek(), rule);
                creditedSilver = eval.creditedSilver();
                usedAfter = eval.usedAfter();
                diminished = eval.diminished();
                if (creditedSilver != 0L) {
                    cand.setBattleSilverThisWeek(usedAfter);
                }
            }
        }

        if (finalExp != 0L) {
            long curWeekExp = cand.getBattleExpThisWeek();
            long room = -1L - curWeekExp;
            cand.setBattleExpThisWeek(Long.compareUnsigned(finalExp, room) > 0 ? -1L : (curWeekExp + finalExp));
        }

        boolean ledgerChanged = weekReset || finalExp != 0L || (usedTableSilver && creditedSilver != 0L);
        BattleProgressionDelta delta = new BattleProgressionDelta(
                finalExp, creditedSilver, 0, 0, 0L, 0L,
                usedAfter, softCap, diminished, false, ledgerChanged, usedTableSilver);
        return new PreparedEconomy(cand.build(), delta);
    }
}
