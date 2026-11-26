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
        assertThat(GuildWarRules.isSafeZoneScene(0)).as("未知场景号必须 fail-closed 视为安全区").isTrue();
        assertThat(GuildWarRules.isSafeZoneScene(1)).isTrue();
        assertThat(GuildWarRules.isSafeZoneScene(5)).isFalse();

        // V2.5 约战前 3 场 100% 免递减 + +25% 约战收益替代 +20% 宿敌
        assertThat(GuildWarRules.diminishPercentForRepeat(2, true)).isEqualTo(100);
        assertThat(GuildWarRules.diminishPercentForRepeat(3, true)).isEqualTo(20);
        GuildWarRules.SideReward feudWin = GuildWarRules.computeSideReward(
                GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_PANEL_PVP, true, false, 100, false, true, true, null);
        assertThat(feudWin.contribution()).isEqualTo(100L);
        assertThat(GuildWarRules.shouldBroadcastStreakAlert(5, 0)).isTrue();
        assertThat(GuildWarRules.shouldBroadcastStreakAlert(4, 3)).isFalse();
    }

    @Test
    void feudIssueRespondLiveScoreBonusAndCooldown() throws SQLException {
        Deadline d = Deadline.after(3000);
        IssueGuildWarFeudResponse issued = service.issueGuildWarFeud(
                101L, IssueGuildWarFeudRequest.newBuilder().setTargetGuildId(1002L).build(), d);
        assertThat(issued.getErrorMessage().getId()).isZero();
        long feudId = issued.getFeud().getFeudId();
        assertThat(issued.getFeud().getStatus()).isEqualTo(GuildWarFeudStatus.GUILD_WAR_FEUD_STATUS_PENDING);
        assertThat(alerts).anyMatch(a -> a.getKind() == GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_CHALLENGE
                && a.getFeudId() == feudId);

        RespondGuildWarFeudResponse accepted = service.respondGuildWarFeud(
                201L, RespondGuildWarFeudRequest.newBuilder().setFeudId(feudId).setAccept(true).build(), d);
        assertThat(accepted.getErrorMessage().getId()).isZero();
        assertThat(accepted.getFeud().getStatus()).isEqualTo(GuildWarFeudStatus.GUILD_WAR_FEUD_STATUS_ACTIVE);
        assertThat(alerts).anyMatch(a -> a.getKind() == GuildWarAlertKind.GUILD_WAR_ALERT_KIND_FEUD_STARTED
                && a.getFeudId() == feudId);

        BattleResultEvent feudBattle = BattleResultEvent.newBuilder()
                .setBattleId(770_001L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setTotalRounds(5)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(101L))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(201L))
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR)
                        .setGuildWar(GuildWarBattleContext.newBuilder()
                                .setWarBattleKind(GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_PANEL_PVP_VALUE)
                                .setAttackerGuildId(1001L)
                                .setDefenderGuildId(1002L)
                                .setZoneId(1)
                                .build()))
                .build();
        assertThat(service.settleBattleResult(feudBattle)).isTrue();
        GetGuildWarBattleResultResponse res = service.getGuildWarBattleResult(
                101L, GetGuildWarBattleResultRequest.newBuilder().setBattleId(770_001L).build(), d);
        assertThat(res.getFeudBonus()).isTrue();
        assertThat(res.getContributionGain()).isEqualTo(100L);

        GetGuildWarOverviewResponse ov = service.getGuildWarOverview(101L, GetGuildWarOverviewRequest.getDefaultInstance(), d);
        assertThat(ov.getActiveFeud().getIssuerWins()).isEqualTo(1);
        assertThat(ov.getActiveFeud().getTargetWins()).isZero();

        now.addAndGet(7_300_000L);
        GetGuildWarOverviewResponse afterEnd = service.getGuildWarOverview(101L, GetGuildWarOverviewRequest.getDefaultInstance(), d);
        assertThat(afterEnd.hasActiveFeud()).isFalse();
        assertThat(afterEnd.getLastFeudSummary()).contains("约战大捷").contains("1:0");

        IssueGuildWarFeudResponse cdBlocked = service.issueGuildWarFeud(
                101L, IssueGuildWarFeudRequest.newBuilder().setTargetGuildId(1002L).build(), d);
        assertThat(cdBlocked.getErrorMessage().getId()).isEqualTo(GuildTips.WAR_FEUD_COOLDOWN);
    }

    @Test
    void securityGuardsRejectForgedBountyPrivateBountySelfSiegeAndOutsiderGarrison() throws SQLException {
        Deadline d = Deadline.after(3000);
        // 1. 帮主尝试把外帮玩家 201 塞进本帮守旗阵 -> 拒绝
        RegisterGuildWarGarrisonResponse outsider = service.registerGuildWarGarrison(
                101L, RegisterGuildWarGarrisonRequest.newBuilder().setAction(1).setTargetPlayerId(201L).build(), d);
        assertThat(outsider.getErrorMessage().getId()).isEqualTo(GuildTips.NOT_IN_GUILD);

        // 2. 帮会 A 对 201 发私密追杀令，客户端在 LIVE_PLAYER 里伪造无关 bountyId=99999 不得带入开战上下文
        PostGuildWarBountyResponse priv = service.postGuildWarBounty(
                101L, PostGuildWarBountyRequest.newBuilder()
                        .setTargetPlayerId(201L)
                        .setTier(GuildWarBountyTier.GUILD_WAR_BOUNTY_TIER_NORMAL_SILVER)
                        .setPublicBoard(false).build(), d);
        assertThat(priv.getErrorMessage().getId()).isZero();
        long privBountyId = priv.getBounty().getBountyId();

        // 201 所在帮会 B 试图用私密追杀令 BOUNTY 发起攻击 -> 不可见直接拒绝
        com.game.proto.guild.StartGuildWarBattleResponse hiddenBounty = service.startGuildWarBattle(
                201L, com.game.proto.guild.StartGuildWarBattleRequest.newBuilder()
                        .setTargetKind(GuildWarTargetKind.GUILD_WAR_TARGET_KIND_BOUNTY)
                        .setBountyId(privBountyId).build(), d);
        assertThat(hiddenBounty.getErrorMessage().getId()).isEqualTo(GuildTips.WAR_BOUNTY_NOT_FOUND);

        // 3. 帮会 A 试图踢馆自己占领的灵脉 -> WAR_SAME_GUILD
        store.setLeylineOwner(1, 2, 1001L);
        com.game.proto.guild.StartGuildWarBattleResponse selfSiege = service.startGuildWarBattle(
                101L, com.game.proto.guild.StartGuildWarBattleRequest.newBuilder()
                        .setTargetKind(GuildWarTargetKind.GUILD_WAR_TARGET_KIND_LEYLINE_GARRISON)
                        .setLeylineId(2).build(), d);
        assertThat(selfSiege.getErrorMessage().getId()).isEqualTo(GuildTips.WAR_SAME_GUILD);

        // 4. 终局结算若 bounty_id 目标不在守方阵容（伪造绑定），不得错误结单或没收赏金
        BattleResultEvent mismatched = BattleResultEvent.newBuilder()
                .setBattleId(700_999L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setTotalRounds(3)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(101L))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(999L))
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR)
                        .setGuildWar(GuildWarBattleContext.newBuilder()
                                .setWarBattleKind(GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_BOUNTY_HUNT_VALUE)
                                .setBountyId(privBountyId)
                                .setAttackerGuildId(1001L)
                                .setDefenderGuildId(1002L)
                                .setZoneId(1).build()))
                .build();
        assertThat(service.settleBattleResult(mismatched)).isTrue();
        assertThat(store.getBounty(privBountyId).orElseThrow().status()).as("目标不符时悬赏必须保持 ACTIVE").isEqualTo(2);
    }

    @Test
    void jdbcWarStoreUsesSingleConnectionWithoutPoolDeadlockAndPreservesDefenderMeritOnBountySettle() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checkedOut = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> executedSql = new ArrayList<>();
        // 内存状态表模拟单连接 READ_COMMITTED 可见性：仅单连接读写可见同事务内更新；若再次调用 getConnection() 立刻失败模拟连接池耗尽
        java.util.Map<Long, Object[]> playerRows = new HashMap<>();
        javax.sql.DataSource ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        org.mockito.Mockito.when(ds.getConnection()).thenAnswer(inv -> {
            if (checkedOut.incrementAndGet() > 1) {
                throw new IllegalStateException("嵌套借用第二条 JDBC 连接，单连接池必然死锁");
            }
            java.sql.Connection conn = org.mockito.Mockito.mock(java.sql.Connection.class);
            org.mockito.Mockito.doAnswer(cInv -> {
                checkedOut.decrementAndGet();
                return null;
            }).when(conn).close();
            org.mockito.Mockito.when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(pInv -> {
                String sql = pInv.getArgument(0, String.class);
                executedSql.add(sql);
                java.sql.PreparedStatement ps = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
                Object[] bound = new Object[32];
                org.mockito.Mockito.doAnswer(a -> { bound[(Integer) a.getArgument(0)] = a.getArgument(1); return null; })
                        .when(ps).setLong(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
                org.mockito.Mockito.doAnswer(a -> { bound[(Integer) a.getArgument(0)] = a.getArgument(1); return null; })
                        .when(ps).setInt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
                org.mockito.Mockito.doAnswer(a -> { bound[(Integer) a.getArgument(0)] = a.getArgument(1); return null; })
                        .when(ps).setString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
                org.mockito.Mockito.doAnswer(a -> { bound[(Integer) a.getArgument(0)] = a.getArgument(1); return null; })
                        .when(ps).setBytes(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.when(ps.executeQuery()).thenAnswer(qInv -> {
                    java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    if (sql.contains("FROM guild_war_player_state WHERE player_id = ?")) {
                        long pid = (Long) bound[1];
                        Object[] row = playerRows.get(pid);
                        org.mockito.Mockito.when(rs.next()).thenReturn(row != null, false);
                        if (row != null) {
                            org.mockito.Mockito.when(rs.getLong(org.mockito.ArgumentMatchers.anyInt()))
                                    .thenAnswer(g -> ((Number) row[(Integer) g.getArgument(0)]).longValue());
                            org.mockito.Mockito.when(rs.getInt(org.mockito.ArgumentMatchers.anyInt()))
                                    .thenAnswer(g -> ((Number) row[(Integer) g.getArgument(0)]).intValue());
                        }
                    } else {
                        org.mockito.Mockito.when(rs.next()).thenReturn(false);
                    }
                    return rs;
                });
                org.mockito.Mockito.when(ps.executeUpdate()).thenAnswer(uInv -> {
                    if (sql.startsWith("INSERT INTO guild_war_player_state")) {
                        long pid = (Long) bound[1];
                        if (sql.contains("VALUES (?,?,0,0,1")) {
                            playerRows.putIfAbsent(pid, new Object[]{null, pid, bound[2], 0, 0L, 1, 0L, 0, 0, 0L,
                                    bound[3], 0L, 0, 0, 0L, 0, 0, 0L, 0L, 0L});
                        } else {
                            Object[] r = new Object[20];
                            System.arraycopy(bound, 1, r, 1, 19);
                            playerRows.put(pid, r);
                        }
                        return 1;
                    }
                    if (sql.startsWith("UPDATE guild_war_player_state")) {
                        long pid = (Long) bound[19];
                        if (!playerRows.containsKey(pid)) return 0;
                        Object[] r = new Object[20];
                        r[1] = pid;
                        for (int i = 1; i <= 18; i++) r[i + 1] = bound[i];
                        playerRows.put(pid, r);
                        return 1;
                    }
                    return 1;
                });
                return ps;
            });
            return conn;
        });

        GuildWarStore jdbc = new GuildWarStore(ds);
        long t = now.get();
        int weekKey = 202648;
        GuildWarStore.BattleRow rec = new GuildWarStore.BattleRow(
                900_001L, 1, GuildWarBattleKind.GUILD_WAR_BATTLE_KIND_BOUNTY_HUNT_VALUE, 0, 8001L,
                1001L, 1002L, 1, 5, 0, 0, 0, 2, 0L, 4500L, 100,
                60L, 1000L, 50L, 30L, 20L, 500L, 10L, 15L, "{}".getBytes(), t);
        assertThat(jdbc.settleBattle(rec, weekKey, 20261126, List.of(101L), List.of(201L),
                false, 0L, true, 3, 0, "", 101L, 600L, 10L, t)).isTrue();
        assertThat(checkedOut.get()).isZero();
        assertThat(executedSql).anyMatch(s -> s.contains("guild_war_player_state WHERE player_id = ? FOR UPDATE"));
        assertThat(executedSql).anyMatch(s -> s.contains("guild_war_bounty WHERE bounty_id=? FOR UPDATE"));
        Object[] defRow = playerRows.get(201L);
        assertThat(defRow).isNotNull();
        assertThat(((Number) defRow[11]).longValue()).as("悬赏豁免期更新不得覆盖本场刚累计的守方战功").isEqualTo(15L);
        assertThat(((Number) defRow[14]).longValue()).as("悬赏豁免期必须落盘").isEqualTo(t + 600_000L);
        assertThat(((Number) defRow[4]).longValue()).as("参战后必须刷新免战冷却").isGreaterThan(t);
    }

    /** 轻量级内存仓储替身，仅单测使用（无外部 MySQL / H2 依赖）。 */
    private static final class InMemoryWarStore extends GuildWarStore {
        private final Map<String, LeylineRow> leylines = new HashMap<>();
        private final Map<Long, List<GarrisonRow>> garrisons = new HashMap<>();
        private final Map<String, WeeklyProgressRow> weekly = new HashMap<>();
        private final Map<Long, PlayerStateRow> players = new HashMap<>();
        private final Map<Long, BountyRow> bounties = new HashMap<>();
        private final Map<Long, BattleRow> battles = new HashMap<>();
        private final Map<Long, FeudRow> feuds = new HashMap<>();

        void setLeylineOwner(int zoneId, int leylineId, long ownerGuildId) {
            ensureZoneLeylines(zoneId, 1L);
            LeylineRow cur = leylines.get(zoneId + ":" + leylineId);
            leylines.put(zoneId + ":" + leylineId, new LeylineRow(cur.zoneId(), cur.leylineId(), ownerGuildId,
                    cur.bannerHp(), cur.shieldUntilMs(), cur.capturedAtMs(), cur.lastDividendAtMs(),
                    cur.dividendWeekKey(), cur.weeklyDividendCount(), cur.lastSiegeAlertMs(), cur.updatedMs()));
        }

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

        @Override
        public FeudLookup getActiveOrPendingFeud(long guildId, long nowMs) {
            for (FeudRow f : feuds.values()) {
                if ((f.issuerGuildId() == guildId || f.targetGuildId() == guildId) && (f.status() == 1 || f.status() == 2)) {
                    if (f.status() == 1 && f.acceptDeadlineMs() > 0 && Long.compareUnsigned(nowMs, f.acceptDeadlineMs()) >= 0) {
                        feuds.put(f.feudId(), new FeudRow(f.feudId(), f.zoneId(), f.issuerGuildId(), f.issuerGuildName(),
                                f.targetGuildId(), f.targetGuildName(), 5, f.issuerWins(), f.targetWins(), f.issuerMerit(),
                                f.targetMerit(), f.createdAtMs(), f.acceptDeadlineMs(), f.startedAtMs(), f.endsAtMs(), 0L, nowMs));
                        return new FeudLookup(Optional.empty(), Optional.empty());
                    }
                    if (f.status() == 2 && f.endsAtMs() > 0 && Long.compareUnsigned(nowMs, f.endsAtMs()) >= 0) {
                        long win = GuildWarRules.resolveFeudWinner(f.issuerGuildId(), f.targetGuildId(),
                                f.issuerWins(), f.targetWins(), f.issuerMerit(), f.targetMerit());
                        FeudRow fin = new FeudRow(f.feudId(), f.zoneId(), f.issuerGuildId(), f.issuerGuildName(),
                                f.targetGuildId(), f.targetGuildName(), 3, f.issuerWins(), f.targetWins(), f.issuerMerit(),
                                f.targetMerit(), f.createdAtMs(), f.acceptDeadlineMs(), f.startedAtMs(), f.endsAtMs(), win, nowMs);
                        feuds.put(f.feudId(), fin);
                        return new FeudLookup(Optional.empty(), Optional.of(fin));
                    }
                    return new FeudLookup(Optional.of(f), Optional.empty());
                }
            }
            return new FeudLookup(Optional.empty(), Optional.empty());
        }

        @Override
        public Optional<FeudRow> getLastFinishedFeud(long guildId) {
            return feuds.values().stream()
                    .filter(f -> (f.issuerGuildId() == guildId || f.targetGuildId() == guildId) && f.status() == 3)
                    .max(Comparator.comparingLong(FeudRow::endsAtMs));
        }

        @Override
        public long getLastFeudIssuedAt(long issuerGuildId) {
            return feuds.values().stream()
                    .filter(f -> f.issuerGuildId() == issuerGuildId && (f.status() == 1 || f.status() == 2 || f.status() == 3))
                    .mapToLong(FeudRow::createdAtMs).max().orElse(0L);
        }

        @Override
        public FeudRow issueFeud(FeudRow rec, int cooldownSec, long nowMs) {
            getActiveOrPendingFeud(rec.issuerGuildId(), nowMs);
            getActiveOrPendingFeud(rec.targetGuildId(), nowMs);
            boolean busy = feuds.values().stream().anyMatch(f -> (f.status() == 1 || f.status() == 2)
                    && (f.issuerGuildId() == rec.issuerGuildId() || f.targetGuildId() == rec.issuerGuildId()
                    || f.issuerGuildId() == rec.targetGuildId() || f.targetGuildId() == rec.targetGuildId()));
            if (busy) throw new WarRuleException("feud_active");
            long last = getLastFeudIssuedAt(rec.issuerGuildId());
            if (last > 0 && Long.compareUnsigned(nowMs, last + cooldownSec * 1000L) < 0) {
                throw new WarRuleException("feud_cooldown");
            }
            feuds.put(rec.feudId(), rec);
            return rec;
        }

        @Override
        public FeudRow respondFeud(long feudId, long responderGuildId, boolean accept, int durationSec, long nowMs) {
            FeudRow f = feuds.get(feudId);
            if (f == null || f.status() != 1) throw new WarRuleException("feud_not_found");
            if (accept && responderGuildId != f.targetGuildId()) throw new WarRuleException("feud_not_found");
            FeudRow updated = accept
                    ? new FeudRow(f.feudId(), f.zoneId(), f.issuerGuildId(), f.issuerGuildName(), f.targetGuildId(),
                    f.targetGuildName(), 2, 0, 0, 0L, 0L, f.createdAtMs(), f.acceptDeadlineMs(), nowMs,
                    nowMs + durationSec * 1000L, 0L, nowMs)
                    : new FeudRow(f.feudId(), f.zoneId(), f.issuerGuildId(), f.issuerGuildName(), f.targetGuildId(),
                    f.targetGuildName(), 4, 0, 0, 0L, 0L, f.createdAtMs(), f.acceptDeadlineMs(), 0L, 0L, 0L, nowMs);
            feuds.put(feudId, updated);
            return updated;
        }

        @Override
        public Optional<FeudRow> getActiveFeudBetween(long g1, long g2, long nowMs) {
            return feuds.values().stream()
                    .filter(f -> f.status() == 2 && Long.compareUnsigned(nowMs, f.endsAtMs()) < 0
                            && ((f.issuerGuildId() == g1 && f.targetGuildId() == g2)
                            || (f.issuerGuildId() == g2 && f.targetGuildId() == g1)))
                    .findFirst();
        }

        @Override
        public FeudBattleResult recordFeudBattle(long feudId, long atkGuildId, long defGuildId, boolean atkWon,
                                                 boolean defWon, long atkMerit, long defMerit, long nowMs) {
            FeudRow f = feuds.get(feudId);
            if (f == null || f.status() != 2) return new FeudBattleResult(Optional.empty(), false);
            int iw = f.issuerWins(), tw = f.targetWins();
            long im = f.issuerMerit(), tm = f.targetMerit();
            if (atkGuildId == f.issuerGuildId()) {
                if (atkWon) iw++; else if (defWon) tw++;
                im += atkMerit; tm += defMerit;
            } else {
                if (atkWon) tw++; else if (defWon) iw++;
                tm += atkMerit; im += defMerit;
            }
            boolean fin = Long.compareUnsigned(nowMs, f.endsAtMs()) >= 0;
            long win = fin ? GuildWarRules.resolveFeudWinner(f.issuerGuildId(), f.targetGuildId(), iw, tw, im, tm) : 0L;
            FeudRow updated = new FeudRow(f.feudId(), f.zoneId(), f.issuerGuildId(), f.issuerGuildName(),
                    f.targetGuildId(), f.targetGuildName(), fin ? 3 : 2, iw, tw, im, tm, f.createdAtMs(),
                    f.acceptDeadlineMs(), f.startedAtMs(), f.endsAtMs(), win, nowMs);
            feuds.put(feudId, updated);
            return new FeudBattleResult(Optional.of(updated), fin);
        }
    }
}
