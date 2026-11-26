package com.game.guild.war;

import com.game.proto.guild.GuildWarBattleKind;
import com.game.proto.guild.GuildWarBountyTier;
import com.game.table.GuildWarRuleTable;
import java.util.List;
import java.util.Optional;

/**
 * 帮会全天帮战 V2 纯规则计算（与 Go 权威版 {@code go/guild/internal/war/rules.go} 逐项同口径，无 I/O、无墙钟、完全确定性）。
 */
public final class GuildWarRules {

    public static final int RULE_ROW_ID = 1;
    public static final int SAFE_ZONE_SCENE_ID = 1;

    public static final int DEFAULT_LEYLINE_MAX_HP = 1000;
    public static final int DEFAULT_LEYLINE_CAPTURE_INIT_HP = 600;
    public static final int DEFAULT_LEYLINE_SHIELD_SEC = 900;
    public static final int DEFAULT_LEYLINE_PVP_DAMAGE = 100;
    public static final int DEFAULT_LEYLINE_SIEGE_DAMAGE = 60;
    public static final int DEFAULT_LEYLINE_DEFENSE_HEAL = 60;
    public static final int DEFAULT_GARRISON_MAX_SLOTS = 5;
    public static final int DEFAULT_GARRISON_REFRESH_COOLDOWN_SEC = 60;
    public static final int DEFAULT_PEACE_COOLDOWN_SEC = 60;
    public static final int DEFAULT_POST_BATTLE_PROTECT_SEC = 10;
    public static final int DEFAULT_DIMINISH_WINDOW_SEC = 1800;
    public static final int DEFAULT_DIVIDEND_GUILD_WEEKLY_CAP = 24;
    public static final int DEFAULT_DIVIDEND_PLAYER_WEEKLY_CAP = 12;
    public static final long DEFAULT_DIVIDEND_MIN_MERIT = 30L;
    public static final long DEFAULT_DIVIDEND_SILVER = 2000L;
    public static final long DEFAULT_DIVIDEND_CONTRIBUTION = 40L;
    public static final long DEFAULT_BOUNTY_NORMAL_SILVER = 5000L;
    public static final long DEFAULT_BOUNTY_HEAVY_SILVER = 15000L;
    public static final long DEFAULT_BOUNTY_PUBLIC_FUNDS = 2000L;
    public static final long DEFAULT_BOUNTY_PUBLIC_SILVER_POOL = 9000L;
    public static final int DEFAULT_BOUNTY_TAX_BPS = 1000;
    public static final int DEFAULT_BOUNTY_TTL_SEC = 86400;
    public static final int DEFAULT_BOUNTY_IMMUNITY_SEC = 1800;
    public static final int DEFAULT_BOUNTY_COUNTER_KILL_TEAR = 2;
    public static final int DEFAULT_BOUNTY_DAILY_PAYOUT_CAP = 5;
    public static final int DEFAULT_BOUNTY_PUBLIC_DAILY_LIMIT = 2;
    public static final int DEFAULT_TRACK_COOLDOWN_SEC = 15;
    public static final int DEFAULT_GOLD_LEYLINE_BONUS_BPS = 15000;
    public static final int DEFAULT_RIVALRY_BONUS_BPS = 2000;
    public static final int DEFAULT_FEUD_BONUS_BPS = 2500;
    public static final int DEFAULT_FEUD_ACCEPT_WINDOW_SEC = 900;
    public static final int DEFAULT_FEUD_DURATION_SEC = 7200;
    public static final int DEFAULT_FEUD_COOLDOWN_SEC = 86400;
    public static final int FEUD_FREE_DIMINISH_FIGHTS = 3;
    public static final int STREAK_BROADCAST_MIN = 5;
    public static final int RIVALRY_CLASH_THRESHOLD = 5;

    public record LeylineMeta(int id, String name, int sceneConfigId) {
    }

    public static final List<LeylineMeta> ALL_LEYLINES = List.of(
            new LeylineMeta(1, "八仙岛·灵脉", 2),
            new LeylineMeta(2, "碧渊水宫·灵脉", 5),
            new LeylineMeta(3, "月汐湾·灵脉", 3)
    );

    public record GoldLeylineWindow(int leylineId, long endsAtMs) {
    }

    public record BountyTierSpec(long costSilver, long taxSilver, long poolSilver, long costGuildFunds,
                                 boolean requiresOfficer) {
    }

    public record SideReward(long contribution, long silver, long guildFunds, long merit) {
    }

    public record StreakJackpot(long contribution, long silver) {
    }

    public record BannerDecision(int hpAfter, boolean brokeBanner, boolean captured, long newShieldUntilMs,
                                 long bonusContribution, long bonusSilver) {
    }

    private GuildWarRules() {
    }

    public static Optional<LeylineMeta> leylineById(int id) {
        for (LeylineMeta meta : ALL_LEYLINES) {
            if (meta.id() == id) {
                return Optional.of(meta);
            }
        }
        return Optional.empty();
    }

    public static Optional<LeylineMeta> leylineByScene(int sceneConfigId) {
        for (LeylineMeta meta : ALL_LEYLINES) {
            if (meta.sceneConfigId() == sceneConfigId) {
                return Optional.of(meta);
            }
        }
        return Optional.empty();
    }

    /** 场景号未知（<=0）或位于主城安全区（天墉城=1）时默认视为安全区，fail-closed 拦截野外帮战强开。 */
    public static boolean isSafeZoneScene(int sceneConfigId) {
        return sceneConfigId <= 0 || sceneConfigId == SAFE_ZONE_SCENE_ID;
    }

    /** 启动期配表硬校验：拦截非法战意冷却/分红上限/悬赏税率，避免线上零冷却或税率越界。 */
    public static void validate(com.game.table.ConfigTables tables) {
        if (tables == null || tables.guildWarRule() == null) {
            return;
        }
        for (GuildWarRuleTable row : tables.guildWarRule().all()) {
            if (row.getLeylineMaxBannerHp() <= 0 || row.getPeaceCooldownSec() <= 0
                    || row.getGarrisonMaxSlots() <= 0 || row.getDividendPlayerWeeklyCap() <= 0
                    || row.getBountyTtlSec() <= 0) {
                throw new IllegalStateException("GuildWarRuleTable 配置非法 id=" + row.getId());
            }
            if (row.getBountyNormalSilver() < 0 || row.getBountyHeavySilver() < 0
                    || row.getBountyTaxBps() < 0 || row.getBountyTaxBps() > 10_000) {
                throw new IllegalStateException("GuildWarRuleTable 追杀令银两或税率配置非法 id=" + row.getId());
            }
        }
    }

    public static GoldLeylineWindow goldLeylineForHour(long nowMs) {
        long hour = Long.divideUnsigned(nowMs, 3_600_000L);
        int id = (int) (Long.remainderUnsigned(hour, 3L) + 1L);
        long endsAtMs = (hour + 1L) * 3_600_000L;
        return new GoldLeylineWindow(id, endsAtMs);
    }

    public static boolean isRivalryActive(int weeklyClashes, boolean mutualBounty) {
        return weeklyClashes >= RIVALRY_CLASH_THRESHOLD || mutualBounty;
    }

    public static int diminishPercentForRepeat(int priorMatchesInWindow) {
        return diminishPercentForRepeat(priorMatchesInWindow, false);
    }

    public static int diminishPercentForRepeat(int priorMatchesInWindow, boolean feudActive) {
        if (feudActive) {
            if (priorMatchesInWindow < FEUD_FREE_DIMINISH_FIGHTS) {
                return 100;
            }
            return priorMatchesInWindow == FEUD_FREE_DIMINISH_FIGHTS ? 20 : 0;
        }
        return switch (priorMatchesInWindow) {
            case 0 -> 100;
            case 1 -> 50;
            case 2 -> 20;
            default -> 0;
        };
    }

    public static long resolveFeudWinner(long issuerGuildId, long targetGuildId,
                                         int issuerWins, int targetWins,
                                         long issuerMerit, long targetMerit) {
        if (issuerWins > targetWins) return issuerGuildId;
        if (targetWins > issuerWins) return targetGuildId;
        if (issuerMerit > targetMerit) return issuerGuildId;
        if (targetMerit > issuerMerit) return targetGuildId;
        return 0L;
    }

    public static String formatFeudSummary(int status, long issuerGuildId, String issuerGuildName,
                                           long targetGuildId, String targetGuildName,
                                           int issuerWins, int targetWins, long winnerGuildId) {
        if (status != 3) {
            return "";
        }
        if (winnerGuildId != 0 && winnerGuildId == issuerGuildId) {
            return "约战大捷：" + issuerGuildName + " " + issuerWins + ":" + targetWins + " 胜 " + targetGuildName;
        }
        if (winnerGuildId != 0 && winnerGuildId == targetGuildId) {
            return "约战大捷：" + targetGuildName + " " + targetWins + ":" + issuerWins + " 胜 " + issuerGuildName;
        }
        return "势均力敌：" + issuerGuildName + " " + issuerWins + ":" + targetWins + " 平 " + targetGuildName;
    }

    public static boolean shouldBroadcastStreakAlert(int newStreak, int endedLoserStreak) {
        return newStreak == 5 || newStreak == 8 || endedLoserStreak >= STREAK_BROADCAST_MIN;
    }

    public static StreakJackpot streakBountyJackpot(int defeatedStreak, int distinctOpponents) {
        if (defeatedStreak < 3 || distinctOpponents < 2) {
            return new StreakJackpot(0L, 0L);
        }
        if (defeatedStreak >= 8) {
            return new StreakJackpot(400L, 12_000L);
        }
        if (defeatedStreak >= 5) {
            return new StreakJackpot(200L, 6_000L);
        }
        return new StreakJackpot(100L, 3_000L);
    }

    public static Optional<BountyTierSpec> resolveBountyTier(GuildWarRuleTable rule, GuildWarBountyTier tier) {
        long normalSilver = rule != null && rule.getBountyNormalSilver() > 0 ? rule.getBountyNormalSilver() : DEFAULT_BOUNTY_NORMAL_SILVER;
        long heavySilver = rule != null && rule.getBountyHeavySilver() > 0 ? rule.getBountyHeavySilver() : DEFAULT_BOUNTY_HEAVY_SILVER;
        int taxBps = rule != null && rule.getBountyTaxBps() > 0 ? rule.getBountyTaxBps() : DEFAULT_BOUNTY_TAX_BPS;
        long pubFunds = rule != null && rule.getBountyPublicFunds() > 0 ? rule.getBountyPublicFunds() : DEFAULT_BOUNTY_PUBLIC_FUNDS;
        return switch (tier) {
            case GUILD_WAR_BOUNTY_TIER_NORMAL_SILVER -> {
                long tax = (normalSilver * taxBps) / 10_000L;
                yield Optional.of(new BountyTierSpec(normalSilver, tax, normalSilver - tax, 0L, false));
            }
            case GUILD_WAR_BOUNTY_TIER_HEAVY_SILVER -> {
                long tax = (heavySilver * taxBps) / 10_000L;
                yield Optional.of(new BountyTierSpec(heavySilver, tax, heavySilver - tax, 0L, false));
            }
            case GUILD_WAR_BOUNTY_TIER_GUILD_PUBLIC ->
                    Optional.of(new BountyTierSpec(0L, 0L, DEFAULT_BOUNTY_PUBLIC_SILVER_POOL, pubFunds, true));
            default -> Optional.empty();
        };
    }

    public static SideReward computeSideReward(GuildWarBattleKind kind, boolean won, boolean draw, int diminishPct,
                                               boolean goldLeyline, boolean rivalGuild, GuildWarRuleTable rule) {
        return computeSideReward(kind, won, draw, diminishPct, goldLeyline, rivalGuild, false, rule);
    }

    public static SideReward computeSideReward(GuildWarBattleKind kind, boolean won, boolean draw, int diminishPct,
                                               boolean goldLeyline, boolean rivalGuild, boolean feudActive,
                                               GuildWarRuleTable rule) {
        if (diminishPct <= 0) {
            return new SideReward(0L, 0L, 0L, 0L);
        }
        boolean siege = kind == GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_GARRISON_SIEGE;
        long baseContrib;
        long baseSilver;
        long baseFunds;
        long baseMerit;
        if (siege) {
            if (!won) {
                return new SideReward(0L, 0L, 0L, 0L);
            }
            baseContrib = 50L;
            baseSilver = 1500L;
            baseFunds = 400L;
            baseMerit = 20L;
        } else if (won) {
            baseContrib = 80L;
            baseSilver = 3000L;
            baseFunds = 600L;
            baseMerit = 30L;
        } else if (draw) {
            baseContrib = 45L;
            baseSilver = 1500L;
            baseFunds = 300L;
            baseMerit = 18L;
        } else {
            baseContrib = 35L;
            baseSilver = 1000L;
            baseFunds = 200L;
            baseMerit = 15L;
        }

        int goldBps = rule != null && rule.getGoldLeylineMultiplierBps() > 0 ? rule.getGoldLeylineMultiplierBps() : DEFAULT_GOLD_LEYLINE_BONUS_BPS;
        int rivalBps = rule != null && rule.getRivalryBonusBps() > 0 ? rule.getRivalryBonusBps() : DEFAULT_RIVALRY_BONUS_BPS;
        if (feudActive && DEFAULT_FEUD_BONUS_BPS > rivalBps) {
            rivalBps = DEFAULT_FEUD_BONUS_BPS;
        }
        if (goldLeyline) {
            baseContrib = (baseContrib * goldBps) / 10_000L;
            baseSilver = (baseSilver * goldBps) / 10_000L;
            baseFunds = (baseFunds * goldBps) / 10_000L;
            baseMerit = (baseMerit * goldBps) / 10_000L;
        }
        if (rivalGuild || feudActive) {
            baseContrib = (baseContrib * (10_000L + rivalBps)) / 10_000L;
            baseSilver = (baseSilver * (10_000L + rivalBps)) / 10_000L;
            baseFunds = (baseFunds * (10_000L + rivalBps)) / 10_000L;
            baseMerit = (baseMerit * (10_000L + rivalBps)) / 10_000L;
        }
        return new SideReward(
                (baseContrib * diminishPct) / 100L,
                (baseSilver * diminishPct) / 100L,
                (baseFunds * diminishPct) / 100L,
                (baseMerit * diminishPct) / 100L
        );
    }

    public static BannerDecision applyBannerImpact(GuildWarRuleTable rule, long nowMs, long attackerGuildId,
                                                   int currentHp, long shieldUntilMs, int diminishPct,
                                                   boolean attackerAlreadyOwnsOtherLeyline,
                                                   boolean attackerWon, boolean isPvp, boolean defenderWon) {
        int maxHp = rule != null && rule.getLeylineMaxBannerHp() > 0 ? rule.getLeylineMaxBannerHp() : DEFAULT_LEYLINE_MAX_HP;
        int initHp = rule != null && rule.getLeylineCaptureInitHp() > 0 ? rule.getLeylineCaptureInitHp() : DEFAULT_LEYLINE_CAPTURE_INIT_HP;
        int shieldSec = rule != null && rule.getLeylineShieldSec() > 0 ? rule.getLeylineShieldSec() : DEFAULT_LEYLINE_SHIELD_SEC;
        int pvpDmg = rule != null && rule.getPvpWinBannerDamage() > 0 ? rule.getPvpWinBannerDamage() : DEFAULT_LEYLINE_PVP_DAMAGE;
        int siegeDmg = rule != null && rule.getSiegeWinBannerDamage() > 0 ? rule.getSiegeWinBannerDamage() : DEFAULT_LEYLINE_SIEGE_DAMAGE;
        int defHeal = rule != null && rule.getPvpDefendBannerHeal() > 0 ? rule.getPvpDefendBannerHeal() : DEFAULT_LEYLINE_DEFENSE_HEAL;

        if (Long.compareUnsigned(nowMs, shieldUntilMs) < 0) {
            return new BannerDecision(currentHp, false, false, shieldUntilMs, 0L, 0L);
        }
        if (defenderWon) {
            int nextHp = Math.min(maxHp, currentHp + defHeal);
            return new BannerDecision(nextHp, false, false, 0L, 0L, 0L);
        }
        if (!attackerWon) {
            return new BannerDecision(currentHp, false, false, 0L, 0L, 0L);
        }
        int baseDmg = isPvp ? pvpDmg : siegeDmg;
        int dmg = diminishPct < 100 ? (baseDmg * diminishPct) / 100 : baseDmg;
        if (dmg <= 0) {
            return new BannerDecision(currentHp, false, false, 0L, 0L, 0L);
        }
        if (currentHp > dmg) {
            return new BannerDecision(currentHp - dmg, false, false, 0L, 0L, 0L);
        }
        long nextShield = nowMs + shieldSec * 1000L;
        if (attackerAlreadyOwnsOtherLeyline || attackerGuildId == 0L) {
            return new BannerDecision(initHp, true, false, nextShield, 150L, 5000L);
        }
        return new BannerDecision(initHp, true, true, nextShield, 200L, 8000L);
    }
}
