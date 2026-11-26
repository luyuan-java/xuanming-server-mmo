package com.game.guild.jianghu;

import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.eBattleOutcome;
import com.game.proto.guild.GetGuildJianghuGrudgeWallResponse;
import com.game.proto.guild.GuildJianghuGrudgeRecordView;
import com.game.proto.guild.GuildJianghuRivalSummaryView;
import com.game.proto.match.MatchMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帮派生死状恩怨墙一期领域服务（Java 双版对账，镜像 Go {@code go/guild/internal/data/jianghu_grudge_repo.go}）。
 *
 * <p>仅记录两支不同帮会纯同帮小队正常完赛（{@code total_rounds > 0 && outcome != VOID}）的天墉生死状对局，
 * 按 {@code (guild_id, battle_id)} 严格幂等去重，每帮保留最近 20 条战绩，且仅本帮成员可读。
 */
public final class GuildJianghuGrudgeService {

    public static final int MAX_WALL_RECORDS = 20;

    private record RivalKey(long guildId, long rivalGuildId) {
    }

    private record BattleKey(long guildId, long battleId) {
    }

    private static final class RivalSummary {
        final long rivalGuildId;
        String rivalGuildName;
        int wins;
        int losses;
        int draws;
        long lastBattleMs;

        RivalSummary(long rivalGuildId, String rivalGuildName) {
            this.rivalGuildId = rivalGuildId;
            this.rivalGuildName = rivalGuildName;
        }
    }

    private final Object lock = new Object();
    private final Set<BattleKey> seenBattles = new HashSet<>();
    private final Map<RivalKey, RivalSummary> rivals = new HashMap<>();
    private final Map<Long, List<GuildJianghuGrudgeRecordView>> recordsByGuild = new HashMap<>();

    /** 幂等入账两帮纯同帮生死状战果；重复 battle_id、散人混编、同帮切磋或 VOID/0 回合局均忽略并返回 false。 */
    public boolean recordDuelResult(BattleResultEvent ev) {
        if (ev == null || ev.getBattleId() == 0L
                || ev.getMatchMode() != MatchMode.MATCH_MODE_JIANGHU_DUEL_VALUE
                || ev.getTotalRounds() == 0
                || ev.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_VOID
                || !ev.hasJianghuDuelContext()) {
            return false;
        }
        JianghuDuelBattleContext duel = ev.getJianghuDuelContext();
        long gidA = duel.getSideAGuildId();
        long gidB = duel.getSideBGuildId();
        if (gidA == 0L || gidB == 0L || gidA == gidB) {
            return false;
        }
        int outA;
        int outB;
        switch (ev.getOutcome()) {
            case BATTLE_OUTCOME_SIDE_A_WIN -> {
                outA = 1;
                outB = 2;
            }
            case BATTLE_OUTCOME_SIDE_B_WIN -> {
                outA = 2;
                outB = 1;
            }
            case BATTLE_OUTCOME_DRAW -> {
                outA = 3;
                outB = 3;
            }
            default -> {
                return false;
            }
        }
        long finishedMs = ev.getFinishedAtMs() != 0L ? ev.getFinishedAtMs() : ev.getBattleId();
        synchronized (lock) {
            if (seenBattles.contains(new BattleKey(gidA, ev.getBattleId()))) {
                return false;
            }
            applySideLocked(gidA, gidB, duel.getSideBGuildName(), outA,
                    duel.getChallengerCaptainName(), duel.getTargetCaptainName(), ev, duel, finishedMs);
            applySideLocked(gidB, gidA, duel.getSideAGuildName(), outB,
                    duel.getTargetCaptainName(), duel.getChallengerCaptainName(), ev, duel, finishedMs);
            return true;
        }
    }

    public GetGuildJianghuGrudgeWallResponse getWall(long guildId) {
        GetGuildJianghuGrudgeWallResponse.Builder resp = GetGuildJianghuGrudgeWallResponse.newBuilder()
                .setGuildId(guildId);
        if (guildId == 0L) {
            return resp.build();
        }
        synchronized (lock) {
            List<RivalSummary> guildRivals = new ArrayList<>();
            for (Map.Entry<RivalKey, RivalSummary> e : rivals.entrySet()) {
                if (e.getKey().guildId() == guildId) {
                    guildRivals.add(e.getValue());
                }
            }
            guildRivals.sort(Comparator.comparingLong((RivalSummary r) -> r.lastBattleMs).reversed());
            for (RivalSummary r : guildRivals) {
                resp.addRivals(GuildJianghuRivalSummaryView.newBuilder()
                        .setRivalGuildId(r.rivalGuildId)
                        .setRivalGuildName(r.rivalGuildName == null ? "" : r.rivalGuildName)
                        .setWins(r.wins)
                        .setLosses(r.losses)
                        .setDraws(r.draws)
                        .setLastBattleMs(r.lastBattleMs)
                        .build());
            }
            List<GuildJianghuGrudgeRecordView> recs = recordsByGuild.getOrDefault(guildId, List.of());
            resp.addAllRecentRecords(recs);
        }
        return resp.build();
    }

    private void applySideLocked(long guildId, long rivalGuildId, String rivalGuildName, int outcome,
                                 String ourCaptain, String rivalCaptain, BattleResultEvent ev,
                                 JianghuDuelBattleContext duel, long finishedMs) {
        seenBattles.add(new BattleKey(guildId, ev.getBattleId()));
        RivalSummary summary = rivals.computeIfAbsent(
                new RivalKey(guildId, rivalGuildId), k -> new RivalSummary(rivalGuildId, rivalGuildName));
        summary.rivalGuildName = rivalGuildName;
        summary.lastBattleMs = Math.max(summary.lastBattleMs, finishedMs);
        switch (outcome) {
            case 1 -> summary.wins++;
            case 2 -> summary.losses++;
            case 3 -> summary.draws++;
            default -> { }
        }
        List<GuildJianghuGrudgeRecordView> list = recordsByGuild.computeIfAbsent(guildId, k -> new ArrayList<>());
        list.add(0, GuildJianghuGrudgeRecordView.newBuilder()
                .setBattleId(ev.getBattleId())
                .setRivalGuildId(rivalGuildId)
                .setRivalGuildName(rivalGuildName == null ? "" : rivalGuildName)
                .setOutcomeForGuild(outcome)
                .setWritTier(duel.getWritTier())
                .setTeamSize(duel.getTeamSize())
                .setTotalRounds(ev.getTotalRounds())
                .setOurCaptainName(ourCaptain == null ? "" : ourCaptain)
                .setRivalCaptainName(rivalCaptain == null ? "" : rivalCaptain)
                .setFinishedMs(finishedMs)
                .build());
        if (list.size() > MAX_WALL_RECORDS) {
            list.subList(MAX_WALL_RECORDS, list.size()).clear();
        }
    }
}
