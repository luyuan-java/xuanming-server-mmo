package com.game.guild.war;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.guild.asset.AssetOp;
import com.game.guild.asset.AssetOpDecisions;
import com.game.guild.asset.AssetOpProcessor;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.EconomyViews;
import com.game.guild.store.GuildData;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.GuildWarBattleContext;
import com.game.proto.TipInfoMessage;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.guild.*;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartGuildWarBattleRequest;
import com.game.proto.match.StartGuildWarBattleResponse;
import com.game.table.ConfigTables;
import com.game.table.GuildWarMilestoneTable;
import com.game.table.GuildWarRuleTable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会全天帮战 V2 服务（与 Go 权威版 {@code go/guild/internal/logic/war_logic.go} 10 条 RPC + 预警推送 + Kafka 结算完全对等）。
 */
public final class GuildWarService {

    private static final Logger log = LoggerFactory.getLogger(GuildWarService.class);

    public record SceneLocation(boolean online, int sceneConfigId, int channelNo, long sceneId) {
        public static final SceneLocation OFFLINE = new SceneLocation(false, 0, 0, 0L);
    }

    public interface GuildLookup {
        Optional<GuildData> findByPlayer(long playerId, Deadline d);
        Optional<GuildData> findByGuildId(long guildId, Deadline d);
    }

    @FunctionalInterface
    public interface MatchLauncher {
        StartGuildWarBattleResponse launch(StartGuildWarBattleRequest request, Deadline d);
    }

    @FunctionalInterface
    public interface BattleLockLookup {
        long battleIdOf(long playerId, Deadline d);
    }

    @FunctionalInterface
    public interface SceneLocator {
        SceneLocation locate(int zoneId, long playerId, Deadline d);
    }

    @FunctionalInterface
    public interface AlertSink {
        void send(GuildWarAlertS2C alert, Collection<Long> recipients);
    }

    @FunctionalInterface
    public interface NameResolver {
        Map<Long, String> resolve(Collection<Long> playerIds, Deadline d);
    }

    @FunctionalInterface
    public interface AssetOpStatusReader {
        EconomyViews.OrderView read(long opId, Deadline d);
    }

    private static final long DEFAULT_ASSET_LEASE_MS = 30_000L;

    private final GuildWarStore store;
    private final GuildLookup guilds;
    private final Supplier<ConfigTables> tables;
    private final MatchLauncher match;
    private final BattleLockLookup battleLocks;
    private final SceneLocator sceneLocator;
    private final AlertSink alerts;
    private final NameResolver names;
    private final LongSupplier ids;
    private final LongSupplier nowMs;
    private final AssetOpProcessor assetChannel;
    private final AssetOpStatusReader opStatusReader;
    private final long assetLeaseMs;
    private final boolean requireAssetChannel;
    private final RandomGenerator tokens;

    /** 纯内存 / 轻量单测构造器（不启用资产通道；生产装配请走带 {@link AssetOpProcessor} 的重载）。 */
    public GuildWarService(GuildWarStore store, GuildLookup guilds, Supplier<ConfigTables> tables,
                           MatchLauncher match, BattleLockLookup battleLocks, SceneLocator sceneLocator,
                           AlertSink alerts, NameResolver names, LongSupplier ids, LongSupplier nowMs) {
        this(store, guilds, tables, match, battleLocks, sceneLocator, alerts, names, ids, nowMs,
                null, null, Duration.ofMillis(DEFAULT_ASSET_LEASE_MS), false);
    }

    /** 生产装配构造器：全量贯通帮战资产通道（里程碑、灵脉分红、追杀令托管/赏金、战局奖励）。 */
    public GuildWarService(GuildWarStore store, GuildLookup guilds, Supplier<ConfigTables> tables,
                           MatchLauncher match, BattleLockLookup battleLocks, SceneLocator sceneLocator,
                           AlertSink alerts, NameResolver names, LongSupplier ids, LongSupplier nowMs,
                           AssetOpProcessor assetChannel, AssetOpStatusReader opStatusReader,
                           Duration assetLease, boolean requireAssetChannel) {
        this.store = Objects.requireNonNull(store, "store");
        this.guilds = Objects.requireNonNull(guilds, "guilds");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.match = match;
        this.battleLocks = battleLocks != null ? battleLocks : (pid, d) -> 0L;
        this.sceneLocator = sceneLocator != null ? sceneLocator : (z, pid, d) -> SceneLocation.OFFLINE;
        this.alerts = alerts != null ? alerts : (a, r) -> {};
        this.names = names != null ? names : (p, d) -> Map.of();
        this.ids = Objects.requireNonNull(ids, "ids");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.assetChannel = assetChannel;
        this.opStatusReader = opStatusReader;
        this.assetLeaseMs = assetLease != null && assetLease.toMillis() > 0 ? assetLease.toMillis() : DEFAULT_ASSET_LEASE_MS;
        this.requireAssetChannel = requireAssetChannel;
        this.tokens = new SecureRandom();
    }

    private boolean useAssetChannel() {
        return requireAssetChannel || assetChannel != null;
    }

    private long mintOpId() {
        try {
            return ids.getAsLong();
        } catch (RuntimeException e) {
            log.warn("[guild-war] 发号器暂不可用: {}", e.toString());
            return 0L;
        }
    }

    static AssetBundle buildRewardBundle(long silver, int item1Id, int item1Count, int item2Id, int item2Count) {
        AssetBundle.Builder b = AssetBundle.newBuilder();
        if (silver > 0) {
            b.addCurrencies(AssetCurrency.newBuilder()
                    .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                    .setAmount(silver));
        }
        if (item1Id != 0 && item1Count > 0) {
            b.addItems(AssetItem.newBuilder().setConfigId(item1Id).setCount(item1Count));
        }
        if (item2Id != 0 && item2Count > 0) {
            b.addItems(AssetItem.newBuilder().setConfigId(item2Id).setCount(item2Count));
        }
        return (b.getCurrenciesCount() == 0 && b.getItemsCount() == 0) ? null : b.build();
    }

    private EconomyViews.OrderView syncDeliverWarOp(AssetOp op, Deadline d) {
        if (op == null) {
            return new EconomyViews.OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED, 0);
        }
        if (assetChannel == null) {
            return new EconomyViews.OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        }
        long budget = Math.min(GuildLimits.ASSET_SYNC_BUDGET_MS,
                d.remainingMillis() - GuildLimits.ASSET_SYNC_TAIL_RESERVE_MS);
        if (budget >= GuildLimits.ASSET_SYNC_MIN_BUDGET_MS) {
            try {
                assetChannel.processOne(op, DeliveryOrigin.SYNC, budget)
                        .get(budget + 500L, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("[guild-war] 帮战资产同步投递延后至重投循环 op_id={}: {}",
                        Long.toUnsignedString(op.opId()), e.toString());
            }
        }
        if (opStatusReader == null) {
            return new EconomyViews.OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        }
        try {
            EconomyViews.OrderView view = opStatusReader.read(op.opId(), d);
            return view != null ? view
                    : new EconomyViews.OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        } catch (RuntimeException e) {
            log.warn("[guild-war] 回读帮战资产指令状态失败 op_id={}: {}",
                    Long.toUnsignedString(op.opId()), e.toString());
            return new EconomyViews.OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 0);
        }
    }

    private GuildWarRuleTable rule() {
        ConfigTables ct = tables.get();
        return ct == null ? null : ct.guildWarRule().find(GuildWarRules.RULE_ROW_ID).orElse(null);
    }

    private static TipInfoMessage tip(int tipId) {
        return TipInfoMessage.newBuilder().setId(tipId).build();
    }

    private long now() {
        return nowMs.getAsLong();
    }

    // ── 1. GetGuildWarOverview ────────────────────────────────────────────────

    public GetGuildWarOverviewResponse getGuildWarOverview(long playerId, GetGuildWarOverviewRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return GetGuildWarOverviewResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        int zoneId = guild.zoneId();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        int dayKey = GameDay.dayKey(now);
        GuildWarRuleTable rule = rule();
        long minMerit = rule != null && rule.getDividendMinPersonalMerit() > 0
                ? rule.getDividendMinPersonalMerit() : GuildWarRules.DEFAULT_DIVIDEND_MIN_MERIT;
        int divPlayerCap = rule != null && rule.getDividendPlayerWeeklyCap() > 0
                ? rule.getDividendPlayerWeeklyCap() : GuildWarRules.DEFAULT_DIVIDEND_PLAYER_WEEKLY_CAP;
        int divGuildCap = rule != null && rule.getDividendGuildWeeklyCap() > 0
                ? rule.getDividendGuildWeeklyCap() : GuildWarRules.DEFAULT_DIVIDEND_GUILD_WEEKLY_CAP;
        int pubDailyLimit = rule != null && rule.getBountyPublicDailyLimit() > 0
                ? rule.getBountyPublicDailyLimit() : GuildWarRules.DEFAULT_BOUNTY_PUBLIC_DAILY_LIMIT;
        int payoutLimit = rule != null && rule.getBountyDailyPayoutLimit() > 0
                ? rule.getBountyDailyPayoutLimit() : GuildWarRules.DEFAULT_BOUNTY_DAILY_PAYOUT_CAP;
        int maxHp = rule != null && rule.getLeylineMaxBannerHp() > 0
                ? rule.getLeylineMaxBannerHp() : GuildWarRules.DEFAULT_LEYLINE_MAX_HP;

        try {
            store.ensureZoneLeylines(zoneId, now);
            List<GuildWarStore.LeylineRow> leylines = store.listLeylines(zoneId);
            GuildWarStore.PlayerStateRow mySt = store.getOrDefaultPlayerState(playerId, zoneId, weekKey);
            GuildWarStore.WeeklyProgressRow prog = store.getOrDefaultWeeklyProgress(guild.guildId(), weekKey);
            List<GuildWarStore.GarrisonRow> garrison = store.listGarrison(guild.guildId());
            List<GuildWarStore.PlayerStateRow> openPlayers = store.listOpenChallengePlayers(zoneId, 20);
            List<GuildWarStore.PlayerStateRow> streakLeaders = store.listStreakLeaders(zoneId, 10);
            List<GuildWarStore.BountyRow> bounties = store.listActiveBounties(zoneId, guild.guildId(), now);
            List<GuildWarStore.BattleRow> recentBattles = store.listRecentBattles(guild.guildId(), 30);
            Set<Long> rivals = new HashSet<>(store.listRivals(guild.guildId(), weekKey));

            GuildWarRules.GoldLeylineWindow gold = GuildWarRules.goldLeylineForHour(now);
            Map<Long, String> guildNameCache = new HashMap<>();
            guildNameCache.put(guild.guildId(), guild.name());

            Set<Long> nameIds = new HashSet<>();
            for (var op : openPlayers) nameIds.add(op.playerId());
            for (var sl : streakLeaders) nameIds.add(sl.playerId());
            for (var b : bounties) nameIds.add(b.targetPlayerId());
            if (prog.mvp1PlayerId() != 0) nameIds.add(prog.mvp1PlayerId());
            if (prog.mvp2PlayerId() != 0) nameIds.add(prog.mvp2PlayerId());
            Map<Long, String> resolvedNames = nameIds.isEmpty() ? Map.of() : names.resolve(nameIds, d);

            GetGuildWarOverviewResponse.Builder resp = GetGuildWarOverviewResponse.newBuilder()
                    .setServerTimeMs(now)
                    .setWeekKey(weekKey)
                    .setGoldLeylineId(gold.leylineId())
                    .setGoldLeylineEndsAtMs(gold.endsAtMs())
                    .setActiveBattleId(battleLocks.battleIdOf(playerId, d))
                    .setPublicBountyDailyLimit(pubDailyLimit);

            GuildData.Member selfMember = guild.member(playerId);
            if (selfMember != null) {
                resp.setContributionBalance(selfMember.contributionBalance());
            }

            int payoutsToday = mySt.bountyPayoutDayKey() == dayKey ? mySt.bountyPayoutCountToday() : 0;
            resp.setMyStance(GuildWarStanceView.newBuilder()
                    .setPeaceMode(mySt.peaceMode() != 0)
                    .setPeaceReadyAtMs(mySt.peaceReadyAtMs())
                    .setOpenChallenge(mySt.openChallenge() != 0)
                    .setProtectUntilMs(mySt.protectUntilMs())
                    .setMyStreak(mySt.streakCount())
                    .setMyWeeklyMerit(mySt.weeklyMerit())
                    .setMyBountyPayoutsToday(payoutsToday)
                    .setMyBountyPayoutLimit(payoutLimit)
                    .setBountyProtectUntilMs(mySt.bountyImmunityUntilMs())
                    .setNextGarrisonRefreshMs(mySt.nextGarrisonRefreshMs())
                    .setNextTrackAtMs(mySt.nextTrackAtMs())
                    .build());

            int occupiedLeyline = 0;
            for (GuildWarStore.LeylineRow lr : leylines) {
                if (lr.ownerGuildId() == guild.guildId()) {
                    occupiedLeyline = lr.leylineId();
                }
                GuildWarRules.LeylineMeta meta = GuildWarRules.leylineById(lr.leylineId()).orElse(null);
                String ownerName = lookupGuildName(lr.ownerGuildId(), guildNameCache, d);
                int gCount = lr.ownerGuildId() == 0 ? 0 : store.listGarrison(lr.ownerGuildId()).size();
                resp.addLeylines(GuildWarLeylineView.newBuilder()
                        .setLeylineId(lr.leylineId())
                        .setName(meta != null ? meta.name() : ("灵脉#" + lr.leylineId()))
                        .setSceneConfigId(meta != null ? meta.sceneConfigId() : 0)
                        .setOwnerGuildId(lr.ownerGuildId())
                        .setOwnerGuildName(ownerName)
                        .setBannerHp(lr.bannerHp())
                        .setBannerMaxHp(maxHp)
                        .setShieldUntilMs(lr.shieldUntilMs())
                        .setIsGoldLeyline(lr.leylineId() == gold.leylineId())
                        .setGarrisonCount(gCount)
                        .build());
            }

            GuildWarBannerHonorView.Builder honor = GuildWarBannerHonorView.newBuilder()
                    .setOccupiedLeylineId(occupiedLeyline)
                    .setFirstWinAtMs(prog.firstWinAtMs());
            if (prog.mvp1PlayerId() != 0) honor.addWeeklyMvps(nameOf(prog.mvp1PlayerId(), resolvedNames));
            if (prog.mvp2PlayerId() != 0) honor.addWeeklyMvps(nameOf(prog.mvp2PlayerId(), resolvedNames));
            for (long rg : rivals) {
                honor.addRivalGuildIds(rg);
                honor.addRivalGuildNames(lookupGuildName(rg, guildNameCache, d));
            }
            resp.setBannerHonor(honor.build());

            ConfigTables ct = tables.get();
            if (ct != null) {
                for (GuildWarMilestoneTable row : ct.guildWarMilestone().all()) {
                    int bit = 1 << Math.max(0, row.getId() - 1);
                    boolean claimed = (mySt.milestoneClaimedMask() & bit) != 0;
                    boolean claimable = !claimed && prog.guildMerit() >= row.getRequiredGuildMerit()
                            && mySt.weeklyMerit() >= minMerit;
                    GuildWarMilestoneView.Builder mv = GuildWarMilestoneView.newBuilder()
                            .setMilestoneId(row.getId())
                            .setRequiredMerit(row.getRequiredGuildMerit())
                            .setGuildMerit(prog.guildMerit())
                            .setClaimed(claimed)
                            .setClaimable(claimable)
                            .setRewardPreview(row.getRewardPreview())
                            .setRewardSilver(row.getRewardSilver())
                            .setRewardContribution(row.getRewardContribution());
                    if (row.getRewardItem1Id() != 0) {
                        mv.addRewardItems(GuildRewardItem.newBuilder()
                                .setItemId(row.getRewardItem1Id()).setCount(row.getRewardItem1Count()));
                    }
                    if (row.getRewardItem2Id() != 0) {
                        mv.addRewardItems(GuildRewardItem.newBuilder()
                                .setItemId(row.getRewardItem2Id()).setCount(row.getRewardItem2Count()));
                    }
                    resp.addMilestones(mv.build());
                }
            }

            int claimableDiv = 0;
            if (mySt.weeklyMerit() >= minMerit && prog.dividendPoolCount() > mySt.dividendClaimedCount()
                    && mySt.dividendClaimedCount() < divPlayerCap) {
                claimableDiv = Math.min(prog.dividendPoolCount(), divPlayerCap) - mySt.dividendClaimedCount();
            }
            resp.setDividend(GuildWarDividendView.newBuilder()
                    .setClaimableCount(claimableDiv)
                    .setClaimedThisWeek(mySt.dividendClaimedCount())
                    .setWeeklyCap(divPlayerCap)
                    .setGuildProducedThisWeek(prog.dividendPoolCount())
                    .setGuildWeeklyCap(divGuildCap)
                    .setMinPersonalMerit(minMerit)
                    .build());

            for (GuildWarStore.GarrisonRow g : garrison) {
                resp.addGarrisonSlots(toGarrisonView(g));
            }
            Map<Long, GuildWarStore.BountyRow> bountyByTarget = new HashMap<>();
            for (GuildWarStore.BountyRow b : bounties) {
                bountyByTarget.put(b.targetPlayerId(), b);
                SceneLocation loc = sceneLocator.locate(zoneId, b.targetPlayerId(), d);
                boolean inSafe = loc.online() && GuildWarRules.isSafeZoneScene(loc.sceneConfigId());
                resp.addBounties(GuildWarBountyView.newBuilder()
                        .setBountyId(b.bountyId())
                        .setTargetPlayerId(b.targetPlayerId())
                        .setTargetName(nameOf(b.targetPlayerId(), resolvedNames))
                        .setTargetGuildId(b.targetGuildId())
                        .setTargetGuildName(lookupGuildName(b.targetGuildId(), guildNameCache, d))
                        .setIssuerGuildId(b.issuerGuildId())
                        .setIssuerGuildName(lookupGuildName(b.issuerGuildId(), guildNameCache, d))
                        .setTier(GuildWarBountyTier.forNumber(b.tier()))
                        .setRewardSilver(b.escrowSilver())
                        .setPublicBoard(b.publicBoard() != 0)
                        .setStatus(GuildWarBountyStatus.forNumber(b.status()))
                        .setCounterKills(b.counterKills())
                        .setCounterKillsToTear(GuildWarRules.DEFAULT_BOUNTY_COUNTER_KILL_TEAR)
                        .setExpiresAtMs(b.expiresAtMs())
                        .setLastSceneConfigId(loc.sceneConfigId())
                        .setLastChannelNo(loc.channelNo())
                        .setTargetInSafeZone(inSafe)
                        .setTargetOnline(loc.online())
                        .setNextTrackAtMs(mySt.nextTrackAtMs())
                        .setCanDirectAttack(loc.online() && !inSafe && b.targetGuildId() != guild.guildId())
                        .build());
            }
            for (GuildWarStore.PlayerStateRow op : openPlayers) {
                if (op.playerId() == playerId || op.peaceMode() != 0) continue;
                Optional<GuildData> tg = guilds.findByPlayer(op.playerId(), d);
                if (tg.isEmpty() || tg.get().guildId() == guild.guildId()) continue;
                long bid = battleLocks.battleIdOf(op.playerId(), d);
                GuildWarStore.BountyRow activeB = bountyByTarget.get(op.playerId());
                resp.addLiveTargets(GuildWarLiveTargetView.newBuilder()
                        .setPlayerId(op.playerId())
                        .setPlayerName(nameOf(op.playerId(), resolvedNames))
                        .setGuildId(tg.get().guildId())
                        .setGuildName(tg.get().name())
                        .setPartySize(1)
                        .setStreak(op.streakCount())
                        .setInBattle(bid != 0)
                        .setBattleId(bid)
                        .setIsRivalGuild(rivals.contains(tg.get().guildId()))
                        .setActiveBountyId(activeB != null ? activeB.bountyId() : 0L)
                        .build());
            }
            for (GuildWarStore.PlayerStateRow sl : streakLeaders) {
                Optional<GuildData> tg = guilds.findByPlayer(sl.playerId(), d);
                if (tg.isEmpty() || tg.get().guildId() == guild.guildId()) continue;
                GuildWarRules.StreakJackpot jp = GuildWarRules.streakBountyJackpot(sl.streakCount(), sl.streakDistinctCount());
                if (jp.contribution() == 0 && jp.silver() == 0) continue;
                long bid = battleLocks.battleIdOf(sl.playerId(), d);
                resp.addStreakBounties(GuildWarStreakBountyView.newBuilder()
                        .setPlayerId(sl.playerId())
                        .setPlayerName(nameOf(sl.playerId(), resolvedNames))
                        .setGuildId(tg.get().guildId())
                        .setGuildName(tg.get().name())
                        .setStreak(sl.streakCount())
                        .setBonusContribution(jp.contribution())
                        .setBonusSilver(jp.silver())
                        .setInBattle(bid != 0)
                        .setBattleId(bid)
                        .build());
            }
            for (GuildWarStore.BattleRow br : recentBattles) {
                resp.addRecentLogs(GuildWarRecentLogView.newBuilder()
                        .setBattleId(br.battleId())
                        .setWarBattleKind(GuildWarBattleKind.forNumber(br.warBattleKind()))
                        .setLeylineId(br.leylineId())
                        .setAttackerGuildName(lookupGuildName(br.attackerGuildId(), guildNameCache, d))
                        .setDefenderGuildName(lookupGuildName(br.defenderGuildId(), guildNameCache, d))
                        .setOutcome(br.outcome())
                        .setRounds(br.totalRounds())
                        .setBannerHpDelta(br.bannerHpAfter() - br.bannerHpBefore())
                        .setBountyOutcomeTag(br.bountyOutcomeTag())
                        .setFinishedAtMs(br.finishedAtMs())
                        .build());
            }
            GuildWarStore.FeudLookup feudLookup = store.getActiveOrPendingFeud(guild.guildId(), now);
            feudLookup.justFinished().ifPresent(fin -> broadcastFeudAlert(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_ENDED, fin, d));
            feudLookup.activeOrPending().ifPresent(f -> resp.setActiveFeud(toFeudView(f)));
            store.getLastFinishedFeud(guild.guildId()).ifPresent(fin ->
                    resp.setLastFeudSummary(GuildWarRules.formatFeudSummary(fin.status(), fin.issuerGuildId(),
                            fin.issuerGuildName(), fin.targetGuildId(), fin.targetGuildName(),
                            fin.issuerWins(), fin.targetWins(), fin.winnerGuildId())));
            long lastFeudAt = store.getLastFeudIssuedAt(guild.guildId());
            if (lastFeudAt > 0) {
                long nextAllowed = lastFeudAt + GuildWarRules.DEFAULT_FEUD_COOLDOWN_SEC * 1000L;
                if (Long.compareUnsigned(nextAllowed, now) > 0) {
                    resp.setNextFeudIssueAtMs(nextAllowed);
                }
            }
            return resp.build();
        } catch (SQLException e) {
            throw new IllegalStateException("读取帮战总览失败", e);
        }
    }

    // ── 2. SetGuildWarStance ──────────────────────────────────────────────────

    public SetGuildWarStanceResponse setGuildWarStance(long playerId, SetGuildWarStanceRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return SetGuildWarStanceResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        GuildWarRuleTable rule = rule();
        int peaceCdSec = rule != null && rule.getPeaceCooldownSec() > 0
                ? rule.getPeaceCooldownSec() : GuildWarRules.DEFAULT_PEACE_COOLDOWN_SEC;
        try {
            GuildWarStore.PlayerStateRow st = store.getOrDefaultPlayerState(playerId, guild.zoneId(), weekKey);
            int nextPeace = st.peaceMode();
            long nextPeaceReady = st.peaceReadyAtMs();
            int nextOpen = st.openChallenge();
            if (req.hasSetPeaceMode()) {
                if (req.getSetPeaceMode()) {
                    if (st.peaceMode() == 0 && Long.compareUnsigned(now, st.peaceReadyAtMs()) < 0) {
                        return SetGuildWarStanceResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_PEACE_COOLDOWN)).build();
                    }
                    nextPeace = 1;
                    nextOpen = 0;
                } else {
                    nextPeace = 0;
                    nextPeaceReady = now + peaceCdSec * 1000L;
                }
            }
            if (req.hasSetOpenChallenge()) {
                nextOpen = req.getSetOpenChallenge() && nextPeace == 0 ? 1 : 0;
            }
            GuildWarStore.PlayerStateRow updated = new GuildWarStore.PlayerStateRow(
                    playerId, guild.zoneId(), nextPeace, nextPeaceReady, nextOpen, st.protectUntilMs(),
                    st.streakCount(), st.streakDistinctCount(), st.lastStreakOpponentId(), weekKey,
                    st.weeklyMerit(), st.milestoneClaimedMask(), st.dividendClaimedCount(),
                    st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(), st.bountyPayoutCountToday(),
                    st.nextGarrisonRefreshMs(), st.nextTrackAtMs(), now);
            store.updateStance(updated);
            return SetGuildWarStanceResponse.newBuilder()
                    .setServerTimeMs(now)
                    .setMyStance(GuildWarStanceView.newBuilder()
                            .setPeaceMode(nextPeace != 0)
                            .setPeaceReadyAtMs(nextPeaceReady)
                            .setOpenChallenge(nextOpen != 0)
                            .setProtectUntilMs(updated.protectUntilMs())
                            .setMyStreak(updated.streakCount())
                            .setMyWeeklyMerit(updated.weeklyMerit())
                            .build())
                    .build();
        } catch (SQLException e) {
            throw new IllegalStateException("更新帮战战意状态失败", e);
        }
    }

    // ── 3. RegisterGuildWarGarrison ───────────────────────────────────────────

    public RegisterGuildWarGarrisonResponse registerGuildWarGarrison(long playerId, RegisterGuildWarGarrisonRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return RegisterGuildWarGarrisonResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        long targetPid = req.getTargetPlayerId() == 0 ? playerId : req.getTargetPlayerId();
        boolean withdraw = req.getAction() == 2;
        if (targetPid != playerId) {
            GuildData.Member self = guild.member(playerId);
            if (self == null || !GuildRoles.rank(self.role()).atLeast(GuildRoles.Rank.OFFICER)) {
                return RegisterGuildWarGarrisonResponse.newBuilder().setErrorMessage(tip(GuildTips.RANK_TOO_LOW)).build();
            }
        }
        if (!withdraw && guild.member(targetPid) == null) {
            return RegisterGuildWarGarrisonResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        long now = now();
        int weekKey = GameDay.weekKey(now);
        GuildWarRuleTable rule = rule();
        int maxSlots = rule != null && rule.getGarrisonMaxSlots() > 0
                ? rule.getGarrisonMaxSlots() : GuildWarRules.DEFAULT_GARRISON_MAX_SLOTS;
        int cdSec = rule != null && rule.getGarrisonRefreshCooldownSec() > 0
                ? rule.getGarrisonRefreshCooldownSec() : GuildWarRules.DEFAULT_GARRISON_REFRESH_COOLDOWN_SEC;

        Map<Long, String> resolved = names.resolve(List.of(targetPid), d);
        String pName = resolved.getOrDefault(targetPid, "守旗弟子" + Long.toUnsignedString(targetPid));
        BattlePlayerSnapshot snap = buildGarrisonSnapshot(targetPid, pName, 60, 1);
        GuildWarStore.GarrisonRow rec = new GuildWarStore.GarrisonRow(
                guild.guildId(), 0, targetPid, pName, 60, "", "", 0, snap.toByteArray(), now);
        try {
            List<GuildWarStore.GarrisonRow> slots = store.upsertGarrison(
                    guild.guildId(), targetPid, guild.zoneId(), weekKey, withdraw, rec, now, maxSlots, cdSec);
            RegisterGuildWarGarrisonResponse.Builder resp = RegisterGuildWarGarrisonResponse.newBuilder()
                    .setNextRefreshAtMs(withdraw ? 0L : now + cdSec * 1000L);
            for (var s : slots) resp.addGarrisonSlots(toGarrisonView(s));
            return resp.build();
        } catch (GuildWarStore.WarRuleException e) {
            int tipId = switch (e.code) {
                case "garrison_full" -> GuildTips.WAR_GARRISON_FULL;
                case "garrison_cooldown" -> GuildTips.WAR_GARRISON_COOLDOWN;
                default -> GuildTips.WAR_SERVICE_BUSY;
            };
            return RegisterGuildWarGarrisonResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("更新守旗阵失败", e);
        }
    }

    // ── 4. StartGuildWarBattle ────────────────────────────────────────────────

    public com.game.proto.guild.StartGuildWarBattleResponse startGuildWarBattle(
            long playerId, com.game.proto.guild.StartGuildWarBattleRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        if (match == null) {
            return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_SERVICE_BUSY)).build();
        }
        GuildData guild = guildOpt.get();
        int zoneId = guild.zoneId();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        GuildWarRuleTable rule = rule();
        int battleConfigId = rule != null && rule.getBattleConfigId() > 0 ? rule.getBattleConfigId() : 1001;

        try {
            GuildWarStore.PlayerStateRow mySt = store.getOrDefaultPlayerState(playerId, zoneId, weekKey);
            if (mySt.peaceMode() != 0) {
                return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                        .setErrorMessage(tip(GuildTips.WAR_SELF_PEACE_MODE)).build();
            }
            long selfBattle = battleLocks.battleIdOf(playerId, d);
            if (selfBattle != 0) {
                return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                        .setErrorMessage(tip(GuildTips.WAR_TARGET_IN_BATTLE))
                        .setOffenderPlayerId(playerId)
                        .setOffenderBattleId(selfBattle).build();
            }

            store.ensureZoneLeylines(zoneId, now);
            GuildWarTargetKind targetKind = req.getTargetKind();
            int leylineId = req.getLeylineId();
            long targetPid = req.getTargetPlayerId();
            // 仅 BOUNTY 分支采信客户端 bounty_id；其他模式必须按真实目标查库匹配，防伪造 bounty_id 劫持他人赏金
            long bountyId = 0L;
            long defenderGuildId = 0L;
            MatchMode matchMode = MatchMode.MATCH_MODE_GUILD_WAR_PVP;
            GuildWarBattleKind warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_PANEL_PVP;
            List<Long> defenderPids = new ArrayList<>();
            List<BattlePlayerSnapshot> aiDefenders = new ArrayList<>();

            if (targetKind == GuildWarTargetKind.GUILD_WAR_TARGET_KIND_BOUNTY) {
                bountyId = req.getBountyId();
                Optional<GuildWarStore.BountyRow> bOpt = store.getBounty(bountyId);
                if (bOpt.isEmpty() || bOpt.get().status() != 2 || bOpt.get().expiresAtMs() <= now
                        || (bOpt.get().publicBoard() == 0 && bOpt.get().issuerGuildId() != guild.guildId())) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_BOUNTY_NOT_FOUND)).build();
                }
                GuildWarStore.BountyRow b = bOpt.get();
                if (b.targetPlayerId() == playerId) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
                }
                if (b.targetGuildId() == guild.guildId()) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_SAME_GUILD)).build();
                }
                GuildWarStore.PlayerStateRow tSt = store.getOrDefaultPlayerState(b.targetPlayerId(), zoneId, weekKey);
                if (tSt.peaceMode() != 0) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_PEACE_MODE)).build();
                }
                if (Long.compareUnsigned(now, tSt.protectUntilMs()) < 0) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_PROTECTED)).build();
                }
                long targetBattle = battleLocks.battleIdOf(b.targetPlayerId(), d);
                if (targetBattle != 0L) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_IN_BATTLE))
                            .setOffenderPlayerId(b.targetPlayerId())
                            .setOffenderBattleId(targetBattle).build();
                }
                SceneLocation loc = sceneLocator.locate(zoneId, b.targetPlayerId(), d);
                if (!loc.online()) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_OFFLINE)).build();
                }
                if (GuildWarRules.isSafeZoneScene(loc.sceneConfigId())) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_SAFE_ZONE)).build();
                }
                targetPid = b.targetPlayerId();
                defenderGuildId = b.targetGuildId();
                warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_BOUNTY_HUNT;
                defenderPids.add(targetPid);
            } else if (targetKind == GuildWarTargetKind.GUILD_WAR_TARGET_KIND_LIVE_PLAYER
                    || targetKind == GuildWarTargetKind.GUILD_WAR_TARGET_KIND_FIELD_PLAYER) {
                if (targetPid == 0 || targetPid == playerId) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
                }
                Optional<GuildData> tgOpt = guilds.findByPlayer(targetPid, d);
                if (tgOpt.isEmpty()) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
                }
                if (tgOpt.get().guildId() == guild.guildId()) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_SAME_GUILD)).build();
                }
                defenderGuildId = tgOpt.get().guildId();
                GuildWarStore.PlayerStateRow tSt = store.getOrDefaultPlayerState(targetPid, zoneId, weekKey);
                if (tSt.peaceMode() != 0) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_PEACE_MODE)).build();
                }
                if (Long.compareUnsigned(now, tSt.protectUntilMs()) < 0) {
                    return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                            .setErrorMessage(tip(GuildTips.WAR_TARGET_PROTECTED)).build();
                }
                if (targetKind == GuildWarTargetKind.GUILD_WAR_TARGET_KIND_FIELD_PLAYER) {
                    SceneLocation sLoc = sceneLocator.locate(zoneId, playerId, d);
                    SceneLocation tLoc = sceneLocator.locate(zoneId, targetPid, d);
                    if (!tLoc.online()) {
                        return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_TARGET_OFFLINE)).build();
                    }
                    if (!sLoc.online() || GuildWarRules.isSafeZoneScene(sLoc.sceneConfigId())
                            || GuildWarRules.isSafeZoneScene(tLoc.sceneConfigId())) {
                        return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_SAFE_ZONE)).build();
                    }
                    if (sLoc.sceneId() != 0 && tLoc.sceneId() != 0 && sLoc.sceneId() != tLoc.sceneId()) {
                        return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_TARGET_OFFLINE)).build();
                    }
                    Optional<GuildWarRules.LeylineMeta> leyMeta = GuildWarRules.leylineByScene(tLoc.sceneConfigId());
                    if (leyMeta.isPresent()) {
                        leylineId = leyMeta.get().id();
                        warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_LEYLINE_PVP;
                    } else {
                        warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_FIELD_ROAD;
                    }
                }
                Optional<GuildWarStore.BountyRow> activeB = store.activeBountyByTarget(targetPid, now);
                if (activeB.isPresent() && activeB.get().status() == 2
                        && (activeB.get().publicBoard() != 0 || activeB.get().issuerGuildId() == guild.guildId())) {
                    bountyId = activeB.get().bountyId();
                    warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_BOUNTY_HUNT;
                }
                defenderPids.add(targetPid);
            } else {
                // QUICK_AUTO 或 LEYLINE_GARRISON：优先找活人，否则 0 秒踢馆守旗阵/灵脉守卫
                if (targetKind == GuildWarTargetKind.GUILD_WAR_TARGET_KIND_QUICK_AUTO) {
                    for (var op : store.listOpenChallengePlayers(zoneId, 20)) {
                        if (op.playerId() == playerId || op.peaceMode() != 0 || now < op.protectUntilMs()) continue;
                        if (battleLocks.battleIdOf(op.playerId(), d) != 0) continue;
                        Optional<GuildData> tg = guilds.findByPlayer(op.playerId(), d);
                        if (tg.isPresent() && tg.get().guildId() != guild.guildId()) {
                            defenderGuildId = tg.get().guildId();
                            defenderPids.add(op.playerId());
                            warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_PANEL_PVP;
                            break;
                        }
                    }
                }
                if (defenderPids.isEmpty()) {
                    if (leylineId == 0) {
                        leylineId = GuildWarRules.goldLeylineForHour(now).leylineId();
                    }
                    if (GuildWarRules.leylineById(leylineId).isEmpty()) {
                        return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_LEYLINE_NOT_FOUND)).build();
                    }
                    matchMode = MatchMode.MATCH_MODE_GUILD_WAR_SIEGE;
                    warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_GARRISON_SIEGE;
                    for (var lr : store.listLeylines(zoneId)) {
                        if (lr.leylineId() == leylineId) {
                            defenderGuildId = lr.ownerGuildId();
                            break;
                        }
                    }
                    if (defenderGuildId == guild.guildId()) {
                        return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                                .setErrorMessage(tip(GuildTips.WAR_SAME_GUILD)).build();
                    }
                    if (defenderGuildId != 0 && defenderGuildId != guild.guildId()) {
                        for (var gSlot : store.listGarrison(defenderGuildId)) {
                            if (gSlot.snapshotPayload() != null && gSlot.snapshotPayload().length > 0) {
                                try {
                                    aiDefenders.add(BattlePlayerSnapshot.parseFrom(gSlot.snapshotPayload())
                                            .toBuilder().setTeamIndex(1).setAiControlled(true).build());
                                    break;
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                    if (aiDefenders.isEmpty()) {
                        aiDefenders.add(buildGarrisonSnapshot(900_000L + leylineId, "灵脉守卫", 55, 1));
                    }
                }
            }

            boolean isGold = leylineId != 0 && leylineId == GuildWarRules.goldLeylineForHour(now).leylineId();
            boolean isRival = defenderGuildId != 0 && store.listRivals(guild.guildId(), weekKey).contains(defenderGuildId);
            long reqId = req.getClientRequestId() != 0 ? req.getClientRequestId() : ids.getAsLong();

            StartGuildWarBattleRequest matchReq = StartGuildWarBattleRequest.newBuilder()
                    .setMatchMode(matchMode.getNumber())
                    .setBattleConfigId(battleConfigId)
                    .addAttackerPlayerIds(playerId)
                    .addAllDefenderPlayerIds(defenderPids)
                    .addAllAiDefenderSnapshots(aiDefenders)
                    .setRequestId(reqId)
                    .setActivityContext(BattleActivityContext.newBuilder()
                            .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR)
                            .setGuildId(guild.guildId())
                            .setInitiatorPlayerId(playerId)
                            .setLaunchRequestId(reqId)
                            .setGuildWar(GuildWarBattleContext.newBuilder()
                                    .setWarBattleKind(warKind.getNumber())
                                    .setLeylineId(leylineId)
                                    .setBountyId(bountyId)
                                    .setAttackerGuildId(guild.guildId())
                                    .setDefenderGuildId(defenderGuildId)
                                    .setZoneId(zoneId)
                                    .setGoldLeylineBonus(isGold)
                                    .setRivalryBonus(isRival)
                                    .build())
                            .build())
                    .build();

            StartGuildWarBattleResponse matchResp = match.launch(matchReq, d);
            if (matchResp == null) {
                return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                        .setErrorMessage(tip(GuildTips.WAR_SERVICE_BUSY)).build();
            }
            if (matchResp.getReject() != ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE) {
                int rejectTip = switch (matchResp.getReject()) {
                    case ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE -> GuildTips.WAR_TARGET_IN_BATTLE;
                    case ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE -> GuildTips.WAR_TARGET_OFFLINE;
                    default -> GuildTips.WAR_SERVICE_BUSY;
                };
                return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                        .setErrorMessage(tip(rejectTip))
                        .setOffenderPlayerId(matchResp.getOffenderPlayerId())
                        .setOffenderBattleId(matchResp.getOffenderBattleId())
                        .build();
            }

            if (leylineId != 0 && defenderGuildId != 0 && defenderGuildId != guild.guildId()) {
                Optional<GuildData> defGuild = guilds.findByGuildId(defenderGuildId, d);
                if (defGuild.isPresent()) {
                    List<Long> recips = defGuild.get().members().stream().map(GuildData.Member::playerId).toList();
                    GuildWarRules.LeylineMeta meta = GuildWarRules.leylineById(leylineId).orElse(null);
                    alerts.send(GuildWarAlertS2C.newBuilder()
                            .setKind(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_LEYLINE_UNDER_SIEGE)
                            .setLeylineId(leylineId)
                            .setSceneConfigId(meta != null ? meta.sceneConfigId() : 0)
                            .setAttackerGuildName(guild.name())
                            .setDefenderGuildName(defGuild.get().name())
                            .setBattleId(matchResp.getBattleId())
                            .build(), recips);
                }
            }
            return com.game.proto.guild.StartGuildWarBattleResponse.newBuilder()
                    .setBattleId(matchResp.getBattleId())
                    .setWarBattleKind(warKind)
                    .build();
        } catch (SQLException e) {
            throw new IllegalStateException("发起帮战失败", e);
        }
    }

    // ── 5. ClaimGuildWarMilestone ─────────────────────────────────────────────

    public ClaimGuildWarMilestoneResponse claimGuildWarMilestone(long playerId, ClaimGuildWarMilestoneRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return ClaimGuildWarMilestoneResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        ConfigTables ct = tables.get();
        Optional<GuildWarMilestoneTable> rowOpt = ct == null ? Optional.empty()
                : ct.guildWarMilestone().find(req.getMilestoneId());
        if (rowOpt.isEmpty()) {
            return ClaimGuildWarMilestoneResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_MILESTONE_LOCKED)).build();
        }
        GuildData guild = guildOpt.get();
        GuildWarMilestoneTable row = rowOpt.get();
        GuildWarRuleTable rule = rule();
        long minMerit = rule != null && rule.getDividendMinPersonalMerit() > 0
                ? rule.getDividendMinPersonalMerit() : GuildWarRules.DEFAULT_DIVIDEND_MIN_MERIT;
        long now = now();
        int weekKey = GameDay.weekKey(now);
        try {
            GuildAssetOrderStatus orderStatus;
            if (useAssetChannel()) {
                AssetBundle bundle = buildRewardBundle(row.getRewardSilver(),
                        row.getRewardItem1Id(), row.getRewardItem1Count(),
                        row.getRewardItem2Id(), row.getRewardItem2Count());
                long opId = 0L;
                if (bundle != null) {
                    if (assetChannel == null) {
                        return ClaimGuildWarMilestoneResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_SERVICE_BUSY)).build();
                    }
                    opId = mintOpId();
                    if (opId == 0L) {
                        return ClaimGuildWarMilestoneResponse.newBuilder().setErrorMessage(tip(GuildTips.ID_GEN_UNAVAILABLE)).build();
                    }
                }
                GuildWarStore.MilestoneClaimResult claimed = store.claimMilestoneWithAsset(
                        playerId, guild.guildId(), guild.zoneId(), weekKey,
                        row.getId(), row.getRequiredGuildMerit(), minMerit,
                        row.getRewardContribution(), opId, bundle,
                        now + assetLeaseMs, AssetOpDecisions.leaseToken(tokens), now);
                orderStatus = claimed.assetOp() == null
                        ? GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED
                        : syncDeliverWarOp(claimed.assetOp(), d).status();
            } else {
                store.claimMilestone(playerId, guild.guildId(), guild.zoneId(), weekKey,
                        row.getId(), row.getRequiredGuildMerit(), minMerit, now);
                orderStatus = GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED;
            }
            return ClaimGuildWarMilestoneResponse.newBuilder()
                    .setMilestone(GuildWarMilestoneView.newBuilder()
                            .setMilestoneId(row.getId())
                            .setRequiredMerit(row.getRequiredGuildMerit())
                            .setClaimed(true)
                            .setClaimable(false)
                            .setRewardPreview(row.getRewardPreview())
                            .setRewardSilver(row.getRewardSilver())
                            .setRewardContribution(row.getRewardContribution())
                            .build())
                    .setAssetOrderStatus(orderStatus)
                    .build();
        } catch (GuildWarStore.WarRuleException e) {
            int tipId = switch (e.code) {
                case "milestone_claimed" -> GuildTips.WAR_MILESTONE_CLAIMED;
                case "asset_pending" -> GuildTips.ASSET_PENDING;
                case "not_member" -> GuildTips.NOT_IN_GUILD;
                default -> GuildTips.WAR_MILESTONE_LOCKED;
            };
            return ClaimGuildWarMilestoneResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("领取战功宝箱失败", e);
        }
    }

    // ── 6. ClaimGuildWarDividend ──────────────────────────────────────────────

    public ClaimGuildWarDividendResponse claimGuildWarDividend(long playerId, ClaimGuildWarDividendRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return ClaimGuildWarDividendResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        GuildWarRuleTable rule = rule();
        long minMerit = rule != null && rule.getDividendMinPersonalMerit() > 0
                ? rule.getDividendMinPersonalMerit() : GuildWarRules.DEFAULT_DIVIDEND_MIN_MERIT;
        int playerCap = rule != null && rule.getDividendPlayerWeeklyCap() > 0
                ? rule.getDividendPlayerWeeklyCap() : GuildWarRules.DEFAULT_DIVIDEND_PLAYER_WEEKLY_CAP;
        int guildCap = rule != null && rule.getDividendGuildWeeklyCap() > 0
                ? rule.getDividendGuildWeeklyCap() : GuildWarRules.DEFAULT_DIVIDEND_GUILD_WEEKLY_CAP;
        long now = now();
        int weekKey = GameDay.weekKey(now);
        try {
            int claimed;
            GuildAssetOrderStatus orderStatus;
            if (useAssetChannel()) {
                if (assetChannel == null) {
                    return ClaimGuildWarDividendResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_SERVICE_BUSY)).build();
                }
                long opId = mintOpId();
                if (opId == 0L) {
                    return ClaimGuildWarDividendResponse.newBuilder().setErrorMessage(tip(GuildTips.ID_GEN_UNAVAILABLE)).build();
                }
                GuildWarStore.DividendClaimResult res = store.claimDividendWithAsset(
                        playerId, guild.guildId(), guild.zoneId(), weekKey, minMerit, playerCap,
                        GuildWarRules.DEFAULT_DIVIDEND_SILVER, GuildWarRules.DEFAULT_DIVIDEND_CONTRIBUTION,
                        opId, now + assetLeaseMs, AssetOpDecisions.leaseToken(tokens), now);
                claimed = res.claimedCount();
                orderStatus = res.assetOp() == null
                        ? GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED
                        : syncDeliverWarOp(res.assetOp(), d).status();
            } else {
                claimed = store.claimDividend(playerId, guild.guildId(), guild.zoneId(), weekKey, minMerit, playerCap, now);
                orderStatus = GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED;
            }
            GuildWarStore.PlayerStateRow st = store.getOrDefaultPlayerState(playerId, guild.zoneId(), weekKey);
            GuildWarStore.WeeklyProgressRow prog = store.getOrDefaultWeeklyProgress(guild.guildId(), weekKey);
            return ClaimGuildWarDividendResponse.newBuilder()
                    .setClaimedCount(claimed)
                    .setDividend(GuildWarDividendView.newBuilder()
                            .setClaimableCount(0)
                            .setClaimedThisWeek(st.dividendClaimedCount())
                            .setWeeklyCap(playerCap)
                            .setGuildProducedThisWeek(prog.dividendPoolCount())
                            .setGuildWeeklyCap(guildCap)
                            .setMinPersonalMerit(minMerit)
                            .build())
                    .setAssetOrderStatus(orderStatus)
                    .build();
        } catch (GuildWarStore.WarRuleException e) {
            int tipId = switch (e.code) {
                case "asset_pending" -> GuildTips.ASSET_PENDING;
                case "write_conflict" -> GuildTips.BUSY_RETRY;
                case "not_member" -> GuildTips.NOT_IN_GUILD;
                default -> GuildTips.WAR_DIVIDEND_EMPTY;
            };
            return ClaimGuildWarDividendResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("领取灵脉分红失败", e);
        }
    }

    // ── 7. PostGuildWarBounty ─────────────────────────────────────────────────

    public PostGuildWarBountyResponse postGuildWarBounty(long playerId, PostGuildWarBountyRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        long targetPid = req.getTargetPlayerId();
        if (targetPid == 0 || targetPid == playerId) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
        }
        Optional<GuildData> tgOpt = guilds.findByPlayer(targetPid, d);
        if (tgOpt.isEmpty()) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
        }
        if (tgOpt.get().guildId() == guild.guildId()) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_SAME_GUILD)).build();
        }
        GuildWarRuleTable rule = rule();
        Optional<GuildWarRules.BountyTierSpec> specOpt = GuildWarRules.resolveBountyTier(rule, req.getTier());
        if (specOpt.isEmpty()) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_BOUNTY_INVALID_TARGET)).build();
        }
        GuildWarRules.BountyTierSpec spec = specOpt.get();
        if (spec.requiresOfficer()) {
            GuildData.Member self = guild.member(playerId);
            if (self == null || !GuildRoles.rank(self.role()).atLeast(GuildRoles.Rank.OFFICER)) {
                return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.RANK_TOO_LOW)).build();
            }
        }
        long now = now();
        int dayKey = GameDay.dayKey(now);
        int ttlSec = rule != null && rule.getBountyTtlSec() > 0 ? rule.getBountyTtlSec() : GuildWarRules.DEFAULT_BOUNTY_TTL_SEC;
        int pubDailyLimit = rule != null && rule.getBountyPublicDailyLimit() > 0
                ? rule.getBountyPublicDailyLimit() : GuildWarRules.DEFAULT_BOUNTY_PUBLIC_DAILY_LIMIT;
        long bountyId = mintOpId();
        if (bountyId == 0L) {
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.ID_GEN_UNAVAILABLE)).build();
        }
        try {
            GuildWarStore.BountyRow row;
            if (useAssetChannel()) {
                long opId = 0L;
                AssetBundle debitBundle = null;
                if (spec.costSilver() > 0) {
                    if (assetChannel == null) {
                        return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_SERVICE_BUSY)).build();
                    }
                    opId = mintOpId();
                    if (opId == 0L) {
                        return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.ID_GEN_UNAVAILABLE)).build();
                    }
                    debitBundle = buildRewardBundle(spec.costSilver(), 0, 0, 0, 0);
                }
                GuildWarStore.PostBountyResult posted = store.postBountyWithAsset(
                        bountyId, guild.zoneId(), dayKey, playerId, guild.guildId(),
                        targetPid, tgOpt.get().guildId(), req.getTier(), spec, req.getPublicBoard(),
                        pubDailyLimit, ttlSec, now, opId, debitBundle,
                        now + assetLeaseMs, AssetOpDecisions.leaseToken(tokens));
                row = posted.bounty();
                if (posted.escrowOp() != null) {
                    EconomyViews.OrderView view = syncDeliverWarOp(posted.escrowOp(), d);
                    row = store.getBounty(bountyId).orElse(row);
                    if (view.status() == GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED
                            || view.status() == GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_ABORTED
                            || row.status() == GuildWarBountyStatus.GUILD_WAR_BOUNTY_STATUS_EXPIRED_VALUE) {
                        int rejectTip = view.reason() == AssetOpDecisions.REASON_CURRENCY_INSUFFICIENT
                                ? GuildTips.CURRENCY_INSUFFICIENT : GuildTips.ASSET_REJECTED;
                        return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(rejectTip)).build();
                    }
                }
            } else {
                row = store.postBounty(bountyId, guild.zoneId(), playerId, guild.guildId(),
                        targetPid, tgOpt.get().guildId(), req.getTier(), spec, req.getPublicBoard(), ttlSec, now, 2, 0L);
            }
            GuildWarBountyStatus statusEnum = GuildWarBountyStatus.forNumber(row.status());
            if (statusEnum == null) {
                statusEnum = GuildWarBountyStatus.GUILD_WAR_BOUNTY_STATUS_ACTIVE;
            }
            return PostGuildWarBountyResponse.newBuilder()
                    .setBounty(GuildWarBountyView.newBuilder()
                            .setBountyId(row.bountyId())
                            .setTargetPlayerId(row.targetPlayerId())
                            .setTargetGuildId(row.targetGuildId())
                            .setTargetGuildName(tgOpt.get().name())
                            .setIssuerGuildId(row.issuerGuildId())
                            .setIssuerGuildName(guild.name())
                            .setTier(req.getTier())
                            .setRewardSilver(row.escrowSilver())
                            .setPublicBoard(row.publicBoard() != 0)
                            .setStatus(statusEnum)
                            .setCounterKillsToTear(GuildWarRules.DEFAULT_BOUNTY_COUNTER_KILL_TEAR)
                            .setExpiresAtMs(row.expiresAtMs())
                            .build())
                    .build();
        } catch (GuildWarStore.WarRuleException e) {
            int tipId = switch (e.code) {
                case "funds_insufficient" -> GuildTips.FUNDS_INSUFFICIENT;
                case "rank_too_low" -> GuildTips.RANK_TOO_LOW;
                case "not_member" -> GuildTips.NOT_IN_GUILD;
                case "asset_pending" -> GuildTips.ASSET_PENDING;
                case "bounty_limit" -> GuildTips.WAR_BOUNTY_LIMIT;
                default -> GuildTips.WAR_BOUNTY_ALREADY_ACTIVE;
            };
            return PostGuildWarBountyResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("发布追杀令失败", e);
        }
    }

    // ── 8. TrackGuildWarBounty ────────────────────────────────────────────────

    public TrackGuildWarBountyResponse trackGuildWarBounty(long playerId, TrackGuildWarBountyRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return TrackGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        GuildWarRuleTable rule = rule();
        int cdSec = rule != null && rule.getTrackCooldownSec() > 0
                ? rule.getTrackCooldownSec() : GuildWarRules.DEFAULT_TRACK_COOLDOWN_SEC;
        try {
            Optional<GuildWarStore.BountyRow> bOpt = store.getBounty(req.getBountyId());
            if (bOpt.isEmpty() || bOpt.get().status() != 2 || bOpt.get().expiresAtMs() <= now
                    || (bOpt.get().publicBoard() == 0 && bOpt.get().issuerGuildId() != guild.guildId())) {
                return TrackGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_BOUNTY_NOT_FOUND)).build();
            }
            GuildWarStore.BountyRow b = bOpt.get();
            long nextTrack = store.markTrackCooldown(playerId, guild.zoneId(), weekKey, now, cdSec);
            SceneLocation loc = sceneLocator.locate(guild.zoneId(), b.targetPlayerId(), d);
            boolean inSafe = loc.online() && GuildWarRules.isSafeZoneScene(loc.sceneConfigId());
            long sceneId = inSafe ? 0L : loc.sceneId();
            if (loc.online() && !inSafe) {
                alerts.send(GuildWarAlertS2C.newBuilder()
                        .setKind(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_PURSUER_ENTERED_MAP)
                        .setSceneConfigId(loc.sceneConfigId())
                        .setChannelNo(loc.channelNo())
                        .setAttackerGuildName(guild.name())
                        .setBountyId(b.bountyId())
                        .setRewardSilver(b.escrowSilver())
                        .build(), List.of(b.targetPlayerId()));
            }
            return TrackGuildWarBountyResponse.newBuilder()
                    .setTargetOnline(loc.online())
                    .setInSafeZone(inSafe)
                    .setSceneConfigId(loc.sceneConfigId())
                    .setChannelNo(loc.channelNo())
                    .setNextTrackAtMs(nextTrack)
                    .setSceneId(sceneId)
                    .build();
        } catch (GuildWarStore.WarRuleException e) {
            return TrackGuildWarBountyResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_TRACK_COOLDOWN)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("追踪追杀令失败", e);
        }
    }

    // ── 9. GetGuildWarBattleResult ────────────────────────────────────────────

    public GetGuildWarBattleResultResponse getGuildWarBattleResult(long playerId, GetGuildWarBattleResultRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return GetGuildWarBattleResultResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        try {
            Optional<GuildWarStore.BattleRow> rowOpt = store.getBattle(req.getBattleId());
            if (rowOpt.isEmpty()) {
                return GetGuildWarBattleResultResponse.newBuilder()
                        .setSettled(false).setBattleId(req.getBattleId()).build();
            }
            GuildWarStore.BattleRow r = rowOpt.get();
            boolean isAtk = r.attackerGuildId() == guild.guildId();
            return GetGuildWarBattleResultResponse.newBuilder()
                    .setSettled(true)
                    .setBattleId(r.battleId())
                    .setWarBattleKind(GuildWarBattleKind.forNumber(r.warBattleKind()))
                    .setOutcome(r.outcome())
                    .setRounds(r.totalRounds())
                    .setContributionGain(isAtk ? r.attackerContribution() : r.defenderContribution())
                    .setSilverGain(isAtk ? r.attackerSilver() : r.defenderSilver())
                    .setGuildFundsGain(isAtk ? r.attackerFunds() : r.defenderFunds())
                    .setMeritGain(isAtk ? r.attackerMerit() : r.defenderMerit())
                    .setBountyJackpotContribution(r.jackpotContribution())
                    .setBountyJackpotSilver(r.jackpotSilver())
                    .setBountyOutcomeTag(r.bountyOutcomeTag())
                    .setDiminishPercent(r.diminishPercent())
                    .setLeylineId(r.leylineId())
                    .setBannerHpBefore(r.bannerHpBefore())
                    .setBannerHpAfter(r.bannerHpAfter())
                    .setBannerShielded(r.bannerShielded() != 0)
                    .setFeudBonus(r.summaryPayload() != null
                            && new String(r.summaryPayload(), StandardCharsets.UTF_8).contains("\"fb\":true"))
                    .build();
        } catch (SQLException e) {
            throw new IllegalStateException("查询帮战结算失败", e);
        }
    }

    // ── 10. InspectFieldWarTargets ────────────────────────────────────────────

    public InspectFieldWarTargetsResponse inspectFieldWarTargets(long playerId, InspectFieldWarTargetsRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return InspectFieldWarTargetsResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        try {
            GuildWarStore.PlayerStateRow mySt = store.getOrDefaultPlayerState(playerId, guild.zoneId(), weekKey);
            Set<Long> rivals = new HashSet<>(store.listRivals(guild.guildId(), weekKey));
            List<Long> raw = req.getPlayerIdsList();
            int limit = Math.min(30, raw.size());
            List<Long> filtered = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                long pid = raw.get(i);
                if (pid != 0 && pid != playerId) filtered.add(pid);
            }
            Map<Long, String> resolved = filtered.isEmpty() ? Map.of() : names.resolve(filtered, d);
            InspectFieldWarTargetsResponse.Builder resp = InspectFieldWarTargetsResponse.newBuilder();
            for (long pid : filtered) {
                Optional<GuildData> tg = guilds.findByPlayer(pid, d);
                GuildWarStore.PlayerStateRow st = store.getOrDefaultPlayerState(pid, guild.zoneId(), weekKey);
                long bid = battleLocks.battleIdOf(pid, d);
                Optional<GuildWarStore.BountyRow> activeB = store.activeBountyByTarget(pid, now);
                long tgId = tg.map(GuildData::guildId).orElse(0L);
                boolean canAttack = tgId != 0 && tgId != guild.guildId()
                        && mySt.peaceMode() == 0 && st.peaceMode() == 0
                        && Long.compareUnsigned(now, st.protectUntilMs()) >= 0
                        && bid == 0;
                FieldWarTargetBrief.Builder brief = FieldWarTargetBrief.newBuilder()
                        .setPlayerId(pid)
                        .setPlayerName(nameOf(pid, resolved))
                        .setGuildId(tgId)
                        .setGuildName(tg.map(GuildData::name).orElse(""))
                        .setPeaceMode(st.peaceMode() != 0)
                        .setProtectUntilMs(st.protectUntilMs())
                        .setStreak(st.streakCount())
                        .setInBattle(bid != 0)
                        .setBattleId(bid)
                        .setIsRivalGuild(tgId != 0 && rivals.contains(tgId))
                        .setCanAttackNow(canAttack);
                if (activeB.isPresent() && activeB.get().status() == 2
                        && (activeB.get().publicBoard() != 0 || activeB.get().issuerGuildId() == guild.guildId())) {
                    brief.setActiveBountyId(activeB.get().bountyId())
                            .setActiveBountyRewardSilver(activeB.get().escrowSilver())
                            .setActiveBountyTier(GuildWarBountyTier.forNumber(activeB.get().tier()));
                }
                resp.addTargets(brief.build());
            }
            return resp.build();
        } catch (SQLException e) {
            throw new IllegalStateException("巡检野外敌帮目标失败", e);
        }
    }

    // ── 11. IssueGuildWarFeud & RespondGuildWarFeud（2 小时宿敌约战战书） ──────

    public IssueGuildWarFeudResponse issueGuildWarFeud(long playerId, IssueGuildWarFeudRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return IssueGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        GuildData.Member self = guild.member(playerId);
        if (self == null || !GuildRoles.rank(self.role()).atLeast(GuildRoles.Rank.OFFICER)) {
            return IssueGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.RANK_TOO_LOW)).build();
        }
        long targetGuildId = req.getTargetGuildId();
        if (targetGuildId == 0 || targetGuildId == guild.guildId()) {
            return IssueGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_FEUD_INVALID_TARGET)).build();
        }
        Optional<GuildData> targetOpt = guilds.findByGuildId(targetGuildId, d);
        if (targetOpt.isEmpty()) {
            return IssueGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_FEUD_INVALID_TARGET)).build();
        }
        long now = now();
        long feudId = ids.getAsLong();
        GuildWarStore.FeudRow input = new GuildWarStore.FeudRow(
                feudId, guild.zoneId(), guild.guildId(), guild.name(),
                targetGuildId, targetOpt.get().name(), 1, 0, 0, 0L, 0L, now,
                now + GuildWarRules.DEFAULT_FEUD_ACCEPT_WINDOW_SEC * 1000L, 0L, 0L, 0L, now);
        try {
            GuildWarStore.FeudRow created = store.issueFeud(input, GuildWarRules.DEFAULT_FEUD_COOLDOWN_SEC, now);
            broadcastFeudAlert(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_CHALLENGE, created, d);
            return IssueGuildWarFeudResponse.newBuilder()
                    .setFeud(toFeudView(created))
                    .setNextIssueAtMs(now + GuildWarRules.DEFAULT_FEUD_COOLDOWN_SEC * 1000L)
                    .build();
        } catch (GuildWarStore.WarRuleException e) {
            int tipId = switch (e.code) {
                case "feud_active" -> GuildTips.WAR_FEUD_ALREADY_ACTIVE;
                case "feud_cooldown" -> GuildTips.WAR_FEUD_COOLDOWN;
                default -> GuildTips.WAR_SERVICE_BUSY;
            };
            return IssueGuildWarFeudResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("下发帮会宿敌约战战书失败", e);
        }
    }

    public RespondGuildWarFeudResponse respondGuildWarFeud(long playerId, RespondGuildWarFeudRequest req, Deadline d) {
        Optional<GuildData> guildOpt = guilds.findByPlayer(playerId, d);
        if (guildOpt.isEmpty()) {
            return RespondGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.NOT_IN_GUILD)).build();
        }
        GuildData guild = guildOpt.get();
        GuildData.Member self = guild.member(playerId);
        if (self == null || !GuildRoles.rank(self.role()).atLeast(GuildRoles.Rank.OFFICER)) {
            return RespondGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.RANK_TOO_LOW)).build();
        }
        long now = now();
        try {
            GuildWarStore.FeudRow updated = store.respondFeud(
                    req.getFeudId(), guild.guildId(), req.getAccept(), GuildWarRules.DEFAULT_FEUD_DURATION_SEC, now);
            if (req.getAccept() && updated.status() == 2) {
                broadcastFeudAlert(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_STARTED, updated, d);
            }
            return RespondGuildWarFeudResponse.newBuilder().setFeud(toFeudView(updated)).build();
        } catch (GuildWarStore.WarRuleException e) {
            return RespondGuildWarFeudResponse.newBuilder().setErrorMessage(tip(GuildTips.WAR_FEUD_NOT_FOUND)).build();
        } catch (SQLException e) {
            throw new IllegalStateException("应答帮会宿敌约战战书失败", e);
        }
    }

    private void broadcastFeudAlert(GuildWarAlertKind kind, GuildWarStore.FeudRow f, Deadline d) {
        if (f == null) return;
        List<Long> recipients = new ArrayList<>();
        guilds.findByGuildId(f.targetGuildId(), d).ifPresent(g -> g.members().forEach(m -> recipients.add(m.playerId())));
        if (kind != GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_CHALLENGE) {
            guilds.findByGuildId(f.issuerGuildId(), d).ifPresent(g -> g.members().forEach(m -> recipients.add(m.playerId())));
        }
        if (recipients.isEmpty()) return;
        alerts.send(GuildWarAlertS2C.newBuilder()
                .setKind(kind)
                .setAttackerGuildName(f.issuerGuildName())
                .setDefenderGuildName(f.targetGuildName())
                .setFeudId(f.feudId())
                .setIssuerWins(f.issuerWins())
                .setTargetWins(f.targetWins())
                .build(), recipients);
    }

    private static GuildWarFeudView toFeudView(GuildWarStore.FeudRow f) {
        return GuildWarFeudView.newBuilder()
                .setFeudId(f.feudId())
                .setIssuerGuildId(f.issuerGuildId())
                .setIssuerGuildName(f.issuerGuildName())
                .setTargetGuildId(f.targetGuildId())
                .setTargetGuildName(f.targetGuildName())
                .setStatus(GuildWarFeudStatus.forNumber(f.status()))
                .setIssuerWins(f.issuerWins())
                .setTargetWins(f.targetWins())
                .setIssuerMerit(f.issuerMerit())
                .setTargetMerit(f.targetMerit())
                .setCreatedAtMs(f.createdAtMs())
                .setAcceptDeadlineMs(f.acceptDeadlineMs())
                .setStartedAtMs(f.startedAtMs())
                .setEndsAtMs(f.endsAtMs())
                .setWinnerGuildId(f.winnerGuildId())
                .build();
    }

    // ── Kafka 帮战终局幂等结算 ────────────────────────────────────────────────

    public boolean settleBattleResult(BattleResultEvent event) throws SQLException {
        if (event == null || !event.hasActivityContext()
                || event.getActivityContext().getKind() != eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR
                || !event.getActivityContext().hasGuildWar()) {
            return false;
        }
        GuildWarBattleContext gw = event.getActivityContext().getGuildWar();
        long now = now();
        int weekKey = GameDay.weekKey(now);
        int dayKey = GameDay.dayKey(now);
        GuildWarRuleTable rule = rule();
        int protectSec = rule != null && rule.getPostBattleProtectSec() > 0
                ? rule.getPostBattleProtectSec() : GuildWarRules.DEFAULT_POST_BATTLE_PROTECT_SEC;
        int bountyImmunitySec = rule != null && rule.getBountyProtectSec() > 0
                ? rule.getBountyProtectSec() : GuildWarRules.DEFAULT_BOUNTY_IMMUNITY_SEC;

        List<Long> atkIds = new ArrayList<>();
        List<Long> defIds = new ArrayList<>();
        for (var team : event.getTeamsList()) {
            if (team.getTeamIndex() == 0) atkIds.addAll(team.getPlayerIdsList());
            else if (team.getTeamIndex() == 1) defIds.addAll(team.getPlayerIdsList());
        }
        boolean atkWon = event.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        boolean defWon = event.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        boolean draw = !atkWon && !defWon;
        GuildWarBattleKind warKind = GuildWarBattleKind.forNumber(gw.getWarBattleKind());
        if (warKind == null) warKind = GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_PANEL_PVP;

        Optional<GuildWarStore.FeudRow> activeFeud = store.getActiveFeudBetween(
                gw.getAttackerGuildId(), gw.getDefenderGuildId(), now);
        boolean feudActive = activeFeud.isPresent();

        GuildWarRules.SideReward atkReward = GuildWarRules.computeSideReward(
                warKind, atkWon, draw, 100, gw.getGoldLeylineBonus(), gw.getRivalryBonus(), feudActive, rule);
        GuildWarRules.SideReward defReward = defIds.isEmpty()
                ? new GuildWarRules.SideReward(0, 0, 0, 0)
                : GuildWarRules.computeSideReward(warKind, defWon, draw, 100, gw.getGoldLeylineBonus(), gw.getRivalryBonus(), feudActive, rule);

        int endedLoserStreak = 0;
        long preLoserId = (!draw && !atkIds.isEmpty() && !defIds.isEmpty()) ? (defWon ? atkIds.get(0) : defIds.get(0)) : 0L;
        if (preLoserId != 0L) {
            endedLoserStreak = store.getOrDefaultPlayerState(preLoserId, gw.getZoneId(), weekKey).streakCount();
        }

        int hpBefore = 0, hpAfter = 0, shielded = 0;
        boolean captured = false;
        long nextShieldUntil = 0L;
        long jackpotContrib = 0L, bannerBonusSilver = 0L, bountyPayoutSilver = 0L;
        int outcomeTag = 0;
        if (gw.getLeylineId() != 0) {
            store.ensureZoneLeylines(gw.getZoneId(), now);
            List<GuildWarStore.LeylineRow> leylines = store.listLeylines(gw.getZoneId());
            boolean ownsOther = false;
            GuildWarStore.LeylineRow targetLey = null;
            for (var lr : leylines) {
                if (lr.leylineId() == gw.getLeylineId()) targetLey = lr;
                else if (lr.ownerGuildId() == gw.getAttackerGuildId()) ownsOther = true;
            }
            if (targetLey != null) {
                hpBefore = targetLey.bannerHp();
                if (Long.compareUnsigned(now, targetLey.shieldUntilMs()) < 0) {
                    shielded = 1;
                    hpAfter = hpBefore;
                    nextShieldUntil = targetLey.shieldUntilMs();
                } else {
                    boolean isPvp = warKind != GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_GARRISON_SIEGE;
                    GuildWarRules.BannerDecision dec = GuildWarRules.applyBannerImpact(
                            rule, now, gw.getAttackerGuildId(), hpBefore, targetLey.shieldUntilMs(),
                            100, ownsOther, atkWon, isPvp, defWon);
                    hpAfter = dec.hpAfter();
                    captured = dec.captured();
                    nextShieldUntil = dec.newShieldUntilMs();
                    if (dec.brokeBanner()) {
                        outcomeTag = 5;
                        jackpotContrib += dec.bonusContribution();
                        bannerBonusSilver += dec.bonusSilver();
                    }
                }
            }
        }

        boolean bountyResolved = false;
        int nextBountyStatus = 2;
        int nextCounterKills = 0;
        String nextCounterKillers = "";
        long winnerPid = 0L;
        if (gw.getBountyId() != 0) {
            Optional<GuildWarStore.BountyRow> bOpt = store.getBounty(gw.getBountyId());
            if (bOpt.isPresent() && bOpt.get().status() == 2
                    && bOpt.get().targetGuildId() != gw.getAttackerGuildId()
                    && defIds.contains(bOpt.get().targetPlayerId())
                    && !atkIds.contains(bOpt.get().targetPlayerId())) {
                GuildWarStore.BountyRow b = bOpt.get();
                nextCounterKills = b.counterKills();
                nextCounterKillers = b.counterKillersJson();
                if (atkWon && !atkIds.isEmpty()) {
                    bountyResolved = true;
                    nextBountyStatus = 3; // COMPLETED
                    winnerPid = atkIds.get(0);
                    outcomeTag = 2;
                    bountyPayoutSilver += b.escrowSilver();
                } else if (defWon && !atkIds.isEmpty()) {
                    String killerToken = Long.toUnsignedString(atkIds.get(0));
                    Set<String> seen = new HashSet<>();
                    if (!nextCounterKillers.isEmpty()) {
                        for (String part : nextCounterKillers.split(",")) {
                            if (!part.isBlank()) seen.add(part.trim());
                        }
                    }
                    if (seen.add(killerToken)) {
                        nextCounterKills = seen.size();
                        nextCounterKillers = String.join(",", seen);
                    }
                    int tearNeed = rule != null && rule.getBountyCounterKillTear() > 0
                            ? rule.getBountyCounterKillTear() : GuildWarRules.DEFAULT_BOUNTY_COUNTER_KILL_TEAR;
                    if (nextCounterKills >= tearNeed) {
                        bountyResolved = true;
                        nextBountyStatus = 4; // TORN_UP
                        winnerPid = b.targetPlayerId();
                        outcomeTag = 4;
                        bountyPayoutSilver += b.escrowSilver() / 2L;
                    } else {
                        outcomeTag = 3;
                    }
                }
            }
        }
        long jackpotSilver = bannerBonusSilver + bountyPayoutSilver;

        GuildWarStore.BattleRow rec = new GuildWarStore.BattleRow(
                event.getBattleId(), gw.getZoneId(), warKind.getNumber(), gw.getLeylineId(), gw.getBountyId(),
                gw.getAttackerGuildId(), gw.getDefenderGuildId(), event.getOutcomeValue(), event.getTotalRounds(),
                hpBefore, hpAfter, shielded, outcomeTag, jackpotContrib, jackpotSilver, 100,
                atkReward.contribution(), atkReward.silver(), atkReward.guildFunds(), atkReward.merit(),
                defReward.contribution(), defReward.silver(), defReward.guildFunds(), defReward.merit(),
                (feudActive ? "{\"fb\":true}" : "{}").getBytes(StandardCharsets.UTF_8), now);
        boolean settled;
        List<AssetOp> createdOps = List.of();
        if (useAssetChannel()) {
            List<GuildWarStore.PlayerBattleCredit> credits = new ArrayList<>(atkIds.size() + defIds.size());
            Set<Long> seenPlayers = new HashSet<>();
            for (int i = 0; i < atkIds.size(); i++) {
                long pid = atkIds.get(i);
                if (!seenPlayers.add(pid)) continue;
                long contrib = atkReward.contribution() + (atkWon && i == 0 ? jackpotContrib : 0L);
                long silver = atkReward.silver() + (atkWon && i == 0 ? bannerBonusSilver : 0L);
                long opId = (silver > 0 && assetChannel != null) ? mintOpId() : 0L;
                AssetBundle bundle = opId != 0L ? buildRewardBundle(silver, 0, 0, 0, 0) : null;
                credits.add(new GuildWarStore.PlayerBattleCredit(pid, gw.getAttackerGuildId(), contrib, silver,
                        opId, bundle, opId != 0L ? AssetOpDecisions.leaseToken(tokens) : 0L));
            }
            for (int i = 0; i < defIds.size(); i++) {
                long pid = defIds.get(i);
                if (!seenPlayers.add(pid)) continue;
                long contrib = defReward.contribution() + (defWon && i == 0 ? jackpotContrib : 0L);
                long silver = defReward.silver() + (defWon && i == 0 ? bannerBonusSilver : 0L);
                long opId = (silver > 0 && assetChannel != null) ? mintOpId() : 0L;
                AssetBundle bundle = opId != 0L ? buildRewardBundle(silver, 0, 0, 0, 0) : null;
                credits.add(new GuildWarStore.PlayerBattleCredit(pid, gw.getDefenderGuildId(), contrib, silver,
                        opId, bundle, opId != 0L ? AssetOpDecisions.leaseToken(tokens) : 0L));
            }
            GuildWarStore.SettleBattleResult res = store.settleBattleWithAssets(rec, weekKey, dayKey, atkIds, defIds,
                    captured, nextShieldUntil, bountyResolved, nextBountyStatus, nextCounterKills, nextCounterKillers,
                    winnerPid, bountyImmunitySec, protectSec, atkReward.guildFunds(), defReward.guildFunds(),
                    credits, now + assetLeaseMs, now);
            settled = res.settled();
            if (settled) {
                List<AssetOp> allOps = new ArrayList<>(res.createdOps());
                if (bountyResolved && res.bountyUpdated() && bountyPayoutSilver > 0 && winnerPid != 0 && assetChannel != null) {
                    long bopId = mintOpId();
                    AssetBundle payoutBundle = bopId != 0L ? buildRewardBundle(bountyPayoutSilver, 0, 0, 0, 0) : null;
                    if (payoutBundle != null) {
                        long winnerGuildId = defIds.contains(winnerPid) ? gw.getDefenderGuildId() : gw.getAttackerGuildId();
                        int dailyPayCap = rule != null && rule.getBountyDailyPayoutLimit() > 0
                                ? rule.getBountyDailyPayoutLimit() : GuildWarRules.DEFAULT_BOUNTY_DAILY_PAYOUT_CAP;
                        store.enqueueBountyPayout(winnerPid, winnerGuildId, gw.getZoneId(), weekKey, dayKey, dailyPayCap,
                                bopId, payoutBundle, now + assetLeaseMs, AssetOpDecisions.leaseToken(tokens), now)
                                .ifPresent(allOps::add);
                    }
                }
                createdOps = allOps;
            }
        } else {
            settled = store.settleBattle(rec, weekKey, dayKey, atkIds, defIds, captured, nextShieldUntil,
                    bountyResolved, nextBountyStatus, nextCounterKills, nextCounterKillers, winnerPid,
                    bountyImmunitySec, protectSec, now);
        }
        if (settled) {
            Deadline bg = Deadline.after(1500);
            for (AssetOp op : createdOps) {
                syncDeliverWarOp(op, bg);
            }
            if (feudActive) {
                GuildWarStore.FeudBattleResult fRes = store.recordFeudBattle(activeFeud.get().feudId(),
                        gw.getAttackerGuildId(), gw.getDefenderGuildId(), atkWon, defWon,
                        atkReward.merit() * atkIds.size(), defReward.merit() * defIds.size(), now);
                if (fRes.justFinished() && fRes.feud().isPresent()) {
                    broadcastFeudAlert(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_ENDED, fRes.feud().get(), bg);
                }
            }
            if (!draw && !atkIds.isEmpty() && !defIds.isEmpty()) {
                long winPid = defWon ? defIds.get(0) : atkIds.get(0);
                long losePid = defWon ? atkIds.get(0) : defIds.get(0);
                int newStreak = store.getOrDefaultPlayerState(winPid, gw.getZoneId(), weekKey).streakCount();
                if (GuildWarRules.shouldBroadcastStreakAlert(newStreak, endedLoserStreak)) {
                    Map<Long, String> pNames = names.resolve(List.of(winPid, losePid), bg);
                    List<Long> recips = new ArrayList<>();
                    guilds.findByGuildId(gw.getAttackerGuildId(), bg).ifPresent(g -> g.members().forEach(m -> recips.add(m.playerId())));
                    guilds.findByGuildId(gw.getDefenderGuildId(), bg).ifPresent(g -> g.members().forEach(m -> recips.add(m.playerId())));
                    if (!recips.isEmpty()) {
                        alerts.send(GuildWarAlertS2C.newBuilder()
                                .setKind(GuildWarAlertKind.GUILD_WAR_ALERT_KIND_STREAK_BOUNTY)
                                .setActorName(nameOf(winPid, pNames))
                                .setTargetName(nameOf(losePid, pNames))
                                .setBattleId(event.getBattleId())
                                .setRewardSilver(jackpotSilver)
                                .setStreakCount(endedLoserStreak >= GuildWarRules.STREAK_BROADCAST_MIN ? endedLoserStreak : newStreak)
                                .build(), recips);
                    }
                }
            }
            log.info("[guild-war] settled battle_id={} kind={} outcome={} leyline={} hp={}->{}",
                    Long.toUnsignedString(event.getBattleId()), warKind, event.getOutcomeValue(),
                    gw.getLeylineId(), hpBefore, hpAfter);
        }
        return settled;
    }

    private String lookupGuildName(long guildId, Map<Long, String> cache, Deadline d) {
        if (guildId == 0) return "无主（灵脉守卫）";
        return cache.computeIfAbsent(guildId, id -> guilds.findByGuildId(id, d)
                .map(GuildData::name).orElse("帮会#" + Long.toUnsignedString(id)));
    }

    private static String nameOf(long playerId, Map<Long, String> resolved) {
        String n = resolved.get(playerId);
        return (n == null || n.isBlank()) ? ("道友" + Long.toUnsignedString(playerId)) : n;
    }

    private static GuildWarGarrisonSlotView toGarrisonView(GuildWarStore.GarrisonRow g) {
        return GuildWarGarrisonSlotView.newBuilder()
                .setSlotIndex(g.slotIndex())
                .setPlayerId(g.playerId())
                .setPlayerName(g.playerName())
                .setLevel(g.level())
                .setAppearanceId(g.appearanceId())
                .setPetName(g.petName())
                .setPetTableId(g.petTableId())
                .setUpdatedMs(g.updatedMs())
                .build();
    }

    static BattlePlayerSnapshot buildGarrisonSnapshot(long playerId, String name, int level, int classId) {
        int lv = level > 0 ? level : 50;
        int cls = classId > 0 ? classId : 1;
        long hp = 3200L + lv * 85L;
        long mp = 1200L + lv * 20L;
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setTeamIndex(1)
                .setPlayerName(name)
                .setLevel(lv)
                .setClassId(cls)
                .setMaxHealth(hp)
                .setMaxMana(mp)
                .setPhysicalAttack(360L + lv * 14L)
                .setMagicAttack(320L + lv * 12L)
                .setDefense(220L + lv * 8L)
                .setAiControlled(true)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(hp).setMana(mp).setSpeed(85L + lv * 2L)
                        .setStrength(120L + lv * 5L).setArmor(100L + lv * 4L).setResistance(90L + lv * 3L))
                .build();
    }
}
