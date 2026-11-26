package com.game.guild.war;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.GuildData;
import com.game.proto.BattleActivityContext;
import com.game.proto.GuildWarBattleContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.guild.*;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartGuildWarBattleRequest;
import com.game.proto.match.StartGuildWarBattleResponse;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GuildWarServiceTest {

    private InMemoryWarStore store;
    private GuildWarService service;
    private final AtomicLong now = new AtomicLong(1_795_430_400_000L);
    private final AtomicLong idGen = new AtomicLong(50_000L);
    private final List<GuildWarAlertS2C> alerts = new ArrayList<>();
    private StartGuildWarBattleRequest lastMatchReq;
    private StartGuildWarBattleResponse nextMatchResp;
    private Map<Long, GuildWarService.SceneLocation> locations = Map.of();

    private static final GuildData GUILD_A = new GuildData(1001L, "沧澜盟", 101L, 6, "", 1L, 50, 1, 0L, 10_000L,
            List.of(new GuildData.Member(101L, GuildRoles.LEADER, 1L, 1L, 500L, 400L)));
    private static final GuildData GUILD_B = new GuildData(1002L, "青云阁", 201L, 6, "", 1L, 50, 1, 0L, 10_000L,
            List.of(new GuildData.Member(201L, GuildRoles.LEADER, 1L, 1L, 500L, 400L)));

    @BeforeEach
    void setUp() {
        store = new InMemoryWarStore();
        alerts.clear();
        lastMatchReq = null;
        nextMatchResp = StartGuildWarBattleResponse.newBuilder().setBattleId(880_001L).build();
        locations = Map.of(
                101L, new GuildWarService.SceneLocation(true, 5, 1, 5001L),
                201L, new GuildWarService.SceneLocation(true, 5, 1, 5001L)
        );
        GuildWarService.GuildLookup lookup = new GuildWarService.GuildLookup() {
            @Override
            public Optional<GuildData> findByPlayer(long playerId, Deadline d) {
                if (playerId == 101L || playerId == 102L) return Optional.of(GUILD_A);
                if (playerId == 201L) return Optional.of(GUILD_B);
                return Optional.empty();
            }

            @Override
            public Optional<GuildData> findByGuildId(long guildId, Deadline d) {
                if (guildId == 1001L) return Optional.of(GUILD_A);
                if (guildId == 1002L) return Optional.of(GUILD_B);
                return Optional.empty();
            }
        };
        service = new GuildWarService(
                store,
                lookup,
                () -> null,
                (req, d) -> {
                    lastMatchReq = req;
                    return nextMatchResp;
                },
                (pid, d) -> 0L,
                (z, pid, d) -> locations.getOrDefault(pid, GuildWarService.SceneLocation.OFFLINE),
                (alert, recips) -> alerts.add(alert),
                (pids, d) -> Map.of(101L, "云中客", 201L, "剑尘"),
                idGen::incrementAndGet,
                now::get
        );
    }

    @Test
    void overviewStanceGarrisonAndFieldRoadBattleAndConflictSpectate() {
        Deadline d = Deadline.after(3000);
        GetGuildWarOverviewResponse ov = service.getGuildWarOverview(101L, GetGuildWarOverviewRequest.getDefaultInstance(), d);
        assertThat(ov.getErrorMessage().getId()).isZero();
        assertThat(ov.getLeylinesList()).hasSize(3);
        assertThat(ov.getGoldLeylineId()).isBetween(1, 3);

        // 登记守旗阵与 60s 冷却防刷
        RegisterGuildWarGarrisonResponse gar = service.registerGuildWarGarrison(
                101L, RegisterGuildWarGarrisonRequest.newBuilder().setAction(1).build(), d);
        assertThat(gar.getErrorMessage().getId()).isZero();
        assertThat(gar.getGarrisonSlotsList()).hasSize(1);
        RegisterGuildWarGarrisonResponse garCd = service.registerGuildWarGarrison(
                101L, RegisterGuildWarGarrisonRequest.newBuilder().setAction(1).build(), d);
        assertThat(garCd.getErrorMessage().getId()).isEqualTo(GuildTips.WAR_GARRISON_COOLDOWN);

        // 野外异帮巡检 + 路上直接开战
        InspectFieldWarTargetsResponse inspected = service.inspectFieldWarTargets(
                101L, InspectFieldWarTargetsRequest.newBuilder().addPlayerIds(201L).build(), d);
        assertThat(inspected.getTargetsList()).hasSize(1);
        assertThat(inspected.getTargets(0).getCanAttackNow()).isTrue();
        assertThat(inspected.getTargets(0).getGuildName()).isEqualTo("青云阁");

        com.game.proto.guild.StartGuildWarBattleResponse started = service.startGuildWarBattle(
                101L,
                com.game.proto.guild.StartGuildWarBattleRequest.newBuilder()
                        .setTargetKind(GuildWarTargetKind.GUILD_WAR_TARGET_KIND_FIELD_PLAYER)
                        .setTargetPlayerId(201L)
                        .build(),
                d);
        assertThat(started.getErrorMessage().getId()).isZero();
        assertThat(started.getBattleId()).isEqualTo(880_001L);
        assertThat(lastMatchReq).isNotNull();
        assertThat(lastMatchReq.getActivityContext().getGuildWar().getLeylineId()).isEqualTo(2);

        // 目标抢点已在战 -> 返回 offender_battle_id 供一键观战
        nextMatchResp = StartGuildWarBattleResponse.newBuilder()
                .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE)
                .setOffenderPlayerId(201L)
                .setOffenderBattleId(990_777L)
                .build();
        com.game.proto.guild.StartGuildWarBattleResponse conflict = service.startGuildWarBattle(
                101L,
                com.game.proto.guild.StartGuildWarBattleRequest.newBuilder()
                        .setTargetKind(GuildWarTargetKind.GUILD_WAR_TARGET_KIND_LIVE_PLAYER)
                        .setTargetPlayerId(201L)
                        .build(),
                d);
        assertThat(conflict.getErrorMessage().getId()).isEqualTo(GuildTips.WAR_TARGET_IN_BATTLE);
        assertThat(conflict.getOffenderBattleId()).isEqualTo(990_777L);
    }

    @Test
    void bountyPostTrackAlertAndCounterKillTwoTearsBountyAndSettleIsIdempotent() throws SQLException {
        Deadline d = Deadline.after(3000);
        PostGuildWarBountyResponse posted = service.postGuildWarBounty(
                101L,
                PostGuildWarBountyRequest.newBuilder()
                        .setTargetPlayerId(201L)
                        .setTier(GuildWarBountyTier.GUILD_WAR_BOUNTY_TIER_NORMAL_SILVER)
                        .setPublicBoard(true)
                        .build(),
                d);
        assertThat(posted.getErrorMessage().getId()).isZero();
        long bountyId = posted.getBounty().getBountyId();
        assertThat(posted.getBounty().getRewardSilver()).isEqualTo(4500L);

        // 追踪行踪暴露野外地图与分线 scene_id，并向被追杀目标推预警横幅
        TrackGuildWarBountyResponse tracked = service.trackGuildWarBounty(
                101L, TrackGuildWarBountyRequest.newBuilder().setBountyId(bountyId).build(), d);
        assertThat(tracked.getErrorMessage().getId()).isZero();
        assertThat(tracked.getSceneConfigId()).isEqualTo(5);
        assertThat(tracked.getSceneId()).isEqualTo(5001L);
        assertThat(alerts).anyMatch(a -> a.getKind() == GuildWarAlertKind.GUILD_WAR_ALERT_KIND_PURSUER_ENTERED_MAP);

        // 目标连反杀 2 名不同追兵 -> 撕毁追杀令并没收 50% 托管赏金
        BattleResultEvent firstCounterKill = BattleResultEvent.newBuilder()
                .setBattleId(700_001L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)
                .setTotalRounds(6)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(101L))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(201L))
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR)
                        .setGuildWar(GuildWarBattleContext.newBuilder()
                                .setWarBattleKind(GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_BOUNTY_HUNT_VALUE)
                                .setBountyId(bountyId)
                                .setAttackerGuildId(1001L)
                                .setDefenderGuildId(1002L)
                                .setZoneId(1)
                                .build()))
                .build();
        assertThat(service.settleBattleResult(firstCounterKill)).isTrue();
        // 幂等重放第二次返回 false
        assertThat(service.settleBattleResult(firstCounterKill)).isFalse();
        assertThat(store.getBounty(bountyId).orElseThrow().status()).isEqualTo(2);
        assertThat(store.getBounty(bountyId).orElseThrow().counterKills()).isEqualTo(1);

        BattleResultEvent secondCounterKill = firstCounterKill.toBuilder()
                .setBattleId(700_002L)
                .clearTeams()
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(102L))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(201L))
                .build();
        assertThat(service.settleBattleResult(secondCounterKill)).isTrue();
        GuildWarStore.BountyRow torn = store.getBounty(bountyId).orElseThrow();
        assertThat(torn.status()).isEqualTo(4); // TORN_UP
        assertThat(torn.counterKills()).isEqualTo(2);

        GetGuildWarBattleResultResponse res = service.getGuildWarBattleResult(
                201L, GetGuildWarBattleResultRequest.newBuilder().setBattleId(700_002L).build(), d);
        assertThat(res.getSettled()).isTrue();
        assertThat(res.getBountyOutcomeTag()).isEqualTo(4);
        assertThat(res.getBountyJackpotSilver()).isEqualTo(2250L);
    }

    @Test
    void pureRulesVerifyGoldLeylineRivalryAndMaxOneLeylineMonopolyPrevention() {
        GuildWarRules.SideReward r = GuildWarRules.computeSideReward(
                GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_LEYLINE_PVP, true, false, 100, true, true, null);
        assertThat(r.contribution()).isEqualTo(144L);
        assertThat(r.merit()).isEqualTo(54L);

        GuildWarRules.BannerDecision alreadyOwnsOne = GuildWarRules.applyBannerImpact(
                null, 10_000L, 1001L, 80, 0L, 100, true, true, true, false);
        assertThat(alreadyOwnsOne.brokeBanner()).isTrue();
        assertThat(alreadyOwnsOne.captured()).isFalse();
        assertThat(alreadyOwnsOne.bonusSilver()).isEqualTo(5000L);
    }

    /** 轻量级内存仓储替身，仅单测使用（无外部 MySQL / H2 依赖）。 */
    private static final class InMemoryWarStore extends GuildWarStore {
        private final Map<String, LeylineRow> leylines = new HashMap<>();
        private final Map<Long, List<GarrisonRow>> garrisons = new HashMap<>();
        private final Map<String, WeeklyProgressRow> weekly = new HashMap<>();
        private final Map<Long, PlayerStateRow> players = new HashMap<>();
        private final Map<Long, BountyRow> bounties = new HashMap<>();
        private final Map<Long, BattleRow> battles = new HashMap<>();

        @Override
        public void ensureZoneLeylines(int zoneId, long nowMs) {
            for (var spec : GuildWarRules.ALL_LEYLINES) {
                leylines.putIfAbsent(zoneId + ":" + spec.id(),
                        new LeylineRow(zoneId, spec.id(), 0L, 600, 0L, 0L, 0L, 0, 0, 0L, nowMs));
            }
        }

        @Override
        public List<LeylineRow> listLeylines(int zoneId) {
            return leylines.values().stream()
                    .filter(r -> r.zoneId() == zoneId)
                    .sorted(Comparator.comparingInt(LeylineRow::leylineId))
                    .toList();
        }

        @Override
        public PlayerStateRow getOrDefaultPlayerState(long playerId, int zoneId, int weekKey) {
            PlayerStateRow st = players.get(playerId);
            if (st != null) {
                return st;
            }
            return new PlayerStateRow(playerId, zoneId, 0, 0L, 0, 0L, 0, 0, 0L, weekKey, 0L, 0, 0, 0L, 0, 0, 0L, 0L, 0L);
        }

        @Override
        public void updateStance(PlayerStateRow updated) {
            players.put(updated.playerId(), updated);
        }

        @Override
        public WeeklyProgressRow getOrDefaultWeeklyProgress(long guildId, int weekKey) {
            return weekly.getOrDefault(guildId + ":" + weekKey,
                    new WeeklyProgressRow(guildId, weekKey, 0L, 0, 0L, 0L, 0L, 0L, "", 0L, 0L));
        }

        @Override
        public List<GarrisonRow> listGarrison(long guildId) {
            return new ArrayList<>(garrisons.getOrDefault(guildId, List.of()));
        }

        @Override
        public List<GarrisonRow> upsertGarrison(long guildId, long targetPlayerId, int zoneId, int weekKey,
                                                boolean withdraw, GarrisonRow rec, long nowMs, int maxSlots, int cooldownSec) {
            PlayerStateRow st = getOrDefaultPlayerState(targetPlayerId, zoneId, weekKey);
            if (!withdraw && Long.compareUnsigned(nowMs, st.nextGarrisonRefreshMs()) < 0) {
                throw new WarRuleException("garrison_cooldown");
            }
            List<GarrisonRow> list = new ArrayList<>(garrisons.getOrDefault(guildId, List.of()));
            list.removeIf(g -> g.playerId() == targetPlayerId);
            if (!withdraw) {
                if (list.size() >= maxSlots) {
                    throw new WarRuleException("garrison_full");
                }
                int slot = list.size() + 1;
                list.add(new GarrisonRow(guildId, slot, rec.playerId(), rec.playerName(), rec.level(),
                        rec.appearanceId(), rec.petName(), rec.petTableId(), rec.snapshotPayload(), nowMs));
                players.put(targetPlayerId, new PlayerStateRow(targetPlayerId, zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), st.milestoneClaimedMask(),
                        st.dividendClaimedCount(), st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(),
                        st.bountyPayoutCountToday(), nowMs + cooldownSec * 1000L, st.nextTrackAtMs(), nowMs));
            }
            garrisons.put(guildId, list);
            return list;
        }

        @Override
        public List<PlayerStateRow> listOpenChallengePlayers(int zoneId, int limit) {
            return players.values().stream()
                    .filter(p -> p.zoneId() == zoneId && p.peaceMode() == 0 && p.openChallenge() != 0)
                    .limit(limit).toList();
        }

        @Override
        public List<PlayerStateRow> listStreakLeaders(int zoneId, int limit) {
            return players.values().stream()
                    .filter(p -> p.zoneId() == zoneId && p.streakCount() >= 3)
                    .limit(limit).toList();
        }

        @Override
        public List<BountyRow> listActiveBounties(int zoneId, long guildId, long nowMs) {
            return bounties.values().stream()
                    .filter(b -> b.zoneId() == zoneId && b.status() == 2 && b.expiresAtMs() > nowMs)
                    .toList();
        }

        @Override
        public Optional<BountyRow> activeBountyByTarget(long targetPlayerId, long nowMs) {
            return bounties.values().stream()
                    .filter(b -> b.targetPlayerId() == targetPlayerId && b.status() == 2 && b.expiresAtMs() > nowMs)
                    .findFirst();
        }

        @Override
        public Optional<BountyRow> getBounty(long bountyId) {
            return Optional.ofNullable(bounties.get(bountyId));
        }

        @Override
        public BountyRow postBounty(long bountyId, int zoneId, long issuerPlayerId, long issuerGuildId,
                                    long targetPlayerId, long targetGuildId, GuildWarBountyTier tier,
                                    GuildWarRules.BountyTierSpec spec, boolean publicBoard, int ttlSec, long nowMs,
                                    int status, long escrowOpId) {
            if (activeBountyByTarget(targetPlayerId, nowMs).isPresent()) {
                throw new WarRuleException("bounty_active");
            }
            BountyRow row = new BountyRow(bountyId, zoneId, issuerPlayerId, issuerGuildId, targetPlayerId, targetGuildId,
                    tier.getNumber(), spec.poolSilver(), spec.taxSilver(), publicBoard ? 1 : 0, status, 0, "",
                    escrowOpId, nowMs, nowMs + ttlSec * 1000L, 0L, 0L);
            bounties.put(bountyId, row);
            return row;
        }

        @Override
        public long markTrackCooldown(long playerId, int zoneId, int weekKey, long nowMs, int cooldownSec) {
            PlayerStateRow st = getOrDefaultPlayerState(playerId, zoneId, weekKey);
            if (Long.compareUnsigned(nowMs, st.nextTrackAtMs()) < 0) {
                throw new WarRuleException("track_cooldown");
            }
            long next = nowMs + cooldownSec * 1000L;
            players.put(playerId, new PlayerStateRow(playerId, zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                    st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                    st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), st.milestoneClaimedMask(),
                    st.dividendClaimedCount(), st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(),
                    st.bountyPayoutCountToday(), st.nextGarrisonRefreshMs(), next, nowMs));
            return next;
        }

        @Override
        public List<Long> listRivals(long guildId, int weekKey) {
            return List.of();
        }

        @Override
        public List<BattleRow> listRecentBattles(long guildId, int limit) {
            return battles.values().stream()
                    .filter(b -> b.attackerGuildId() == guildId || b.defenderGuildId() == guildId)
                    .limit(limit).toList();
        }

        @Override
        public Optional<BattleRow> getBattle(long battleId) {
            return Optional.ofNullable(battles.get(battleId));
        }

        @Override
        public boolean settleBattle(BattleRow battle, int weekKey, int dayKey, List<Long> attackerIds,
                                    List<Long> defenderIds, boolean capturedLeyline, long nextShieldUntilMs,
                                    boolean bountyResolved, int nextBountyStatus, int nextCounterKills,
                                    String nextCounterKillersJson, long winnerPlayerId, long bountyImmunitySec,
                                    long protectSec, long nowMs) {
            if (battles.containsKey(battle.battleId())) {
                return false;
            }
            battles.put(battle.battleId(), battle);
            if (battle.bountyId() != 0 && bounties.containsKey(battle.bountyId())) {
                BountyRow b = bounties.get(battle.bountyId());
                if (b.status() == 2) {
                    bounties.put(battle.bountyId(), new BountyRow(b.bountyId(), b.zoneId(), b.issuerPlayerId(),
                            b.issuerGuildId(), b.targetPlayerId(), b.targetGuildId(), b.tier(), b.escrowSilver(),
                            b.taxSilver(), b.publicBoard(), nextBountyStatus, nextCounterKills,
                            nextCounterKillersJson == null ? "" : nextCounterKillersJson, b.escrowOpId(),
                            b.createdAtMs(), b.expiresAtMs(), bountyResolved ? nowMs : 0L, winnerPlayerId));
                }
            }
            return true;
        }
    }
}
