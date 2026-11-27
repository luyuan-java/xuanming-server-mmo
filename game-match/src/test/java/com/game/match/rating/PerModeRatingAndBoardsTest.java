package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.global.GlobalRankProperties;
import com.game.match.global.RatingRankOutbox;
import com.game.match.global.pb.GlobalRankOutboxRow;
import com.game.match.global.pb.GlobalRankSourceRow;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.rank.RankBoardType;
import com.game.proto.rank.SubmitRankScoresRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 覆盖 R5 评分按模式分开、榜按模式（RankBoardType 1/2/3）、score_version、按模式停写与回合打满判平。 */
class PerModeRatingAndBoardsTest {

    private static String toH2(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ")
                .replace(" int unsigned ", " BIGINT ")
                .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    @Test
    void 评分与榜源按模式隔离_带scoreVersion_不计分模式拒绝_各模式独立停写() throws Exception {
        assertThat(RatingRankOutbox.boardTypeForMode(MatchModes.ONE_V_ONE))
                .isEqualTo(RankBoardType.RANK_BOARD_TYPE_MATCH_1V1_VALUE);
        assertThat(RatingRankOutbox.boardTypeForMode(MatchModes.FIVE_V_FIVE))
                .isEqualTo(RankBoardType.RANK_BOARD_TYPE_MATCH_5V5_VALUE);
        assertThat(RatingRankOutbox.boardTypeForMode(MatchModes.PVP_TEAM))
                .isEqualTo(RankBoardType.RANK_BOARD_TYPE_MATCH_PVP_TEAM_VALUE);
        assertThatThrownBy(() -> RatingRankOutbox.boardTypeForMode(MatchModes.PVE_TEAM))
                .isInstanceOf(IllegalArgumentException.class);

        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            PbMysql rankSchema = new PbMysql();
            rankSchema.register(GlobalRankSourceRow.getDefaultInstance());
            rankSchema.register(GlobalRankOutboxRow.getDefaultInstance());
            try (Connection c = RatingTestDatabase.h2SchemaDataSource(db.dataSource).getConnection();
                 Statement st = c.createStatement()) {
                for (TableSchema table : rankSchema.tables()) {
                    st.execute(toH2(table.createTableSql()));
                }
            }

            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
            GlobalRankProperties rankProps = new GlobalRankProperties(
                    "127.0.0.1", 20917, "fixture-only-rank-producer-key-0000");

            try (RatingRankOutbox outbox = new RatingRankOutbox(db.dataSource, rankProps).withMetrics(metrics)) {
                RatingStore store = new RatingStore(
                        RatingStore.connections(db.dataSource), id -> 30, metrics, System::currentTimeMillis)
                        .withCommitHook(outbox);

                // 不计分模式读分直接拒绝
                assertThatThrownBy(() -> store.find(MatchModes.PVE_TEAM, List.of(1L)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> outbox.stopSource(MatchModes.PVP_CHALLENGE))
                        .isInstanceOf(IllegalArgumentException.class);

                // 同一名玩家 1001 分别打 1V1 胜、5V5 负、PVP_TEAM 胜 -> 三条轨道完全独立
                store.apply(battle(5001L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1001L, 1002L));
                store.apply(battle(5002L, MatchModes.FIVE_V_FIVE, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 1001L, 1002L));
                store.apply(battle(5003L, MatchModes.PVP_TEAM, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1001L, 1002L));
                store.apply(battle(5004L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1001L, 1002L));

                assertThat(store.findOrFresh(MatchModes.ONE_V_ONE, 1001L).games()).isEqualTo(2L);
                assertThat(store.findOrFresh(MatchModes.ONE_V_ONE, 1001L).ratingCenti()).isGreaterThan(151_600L);
                assertThat(store.findOrFresh(MatchModes.FIVE_V_FIVE, 1001L).games()).isEqualTo(1L);
                assertThat(store.findOrFresh(MatchModes.FIVE_V_FIVE, 1001L).ratingCenti()).isEqualTo(148_400L);
                assertThat(store.findOrFresh(MatchModes.PVP_TEAM, 1001L).games()).isEqualTo(1L);
                assertThat(store.findOrFresh(MatchModes.PVP_TEAM, 1001L).ratingCenti()).isEqualTo(151_600L);

                // 校验 1V1 第 2 局 outbox 里 score_version == 2（本模式第 2 局），且 5V5 在独立 epoch=2 游标从 1 开始
                try (Connection c = db.dataSource.getConnection()) {
                    var out1v1 = rankSchema.findOneByPk(c, GlobalRankOutboxRow.newBuilder()
                            .setSourceEpoch(1L).setSourceCursor(2L).build()).orElseThrow();
                    SubmitRankScoresRequest req1v1 = SubmitRankScoresRequest.parseFrom(out1v1.getEntries());
                    assertThat(req1v1.getEntriesList()).allSatisfy(e -> assertThat(e.getScoreVersion()).isEqualTo(2L));

                    var out5v5 = rankSchema.findOneByPk(c, GlobalRankOutboxRow.newBuilder()
                            .setSourceEpoch(2L).setSourceCursor(1L).build()).orElseThrow();
                    SubmitRankScoresRequest req5v5 = SubmitRankScoresRequest.parseFrom(out5v5.getEntries());
                    assertThat(req5v5.getEntriesList()).allSatisfy(e -> assertThat(e.getScoreVersion()).isEqualTo(1L));
                }

                // 仅停写 1V1 不影响 5V5/PVP_TEAM 游标；stopAllSources 返回各模式真实最终游标
                assertThat(outbox.stopSource(MatchModes.ONE_V_ONE)).isEqualTo(2L);
                store.apply(battle(5005L, MatchModes.FIVE_V_FIVE, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1001L, 1002L));
                Map<Integer, Long> all = outbox.stopAllSources();
                assertThat(all).containsEntry(MatchModes.ONE_V_ONE, 2L)
                        .containsEntry(MatchModes.FIVE_V_FIVE, 2L)
                        .containsEntry(MatchModes.PVP_TEAM, 1L);
            } finally {
                meters.close();
            }
        }
    }

    @Test
    void 回合打满判平_有个人原件时SIDE_A_WIN不改平_SIDE_B_WIN看0队存活玩家或宝宝_旧版按timeLimit推算() {
        // 1. SIDE_A_WIN 带完整 v1 个人原件即使 total_rounds 很高也必为真胜（不改平）
        BattleResultEvent aWin = battle(6001L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1L, 2L)
                .toBuilder().setSettlementFormatVersion(1).setTotalRounds(99)
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1L).setPlayerTeamIndex(0).setIsDead(false))
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(2L).setPlayerTeamIndex(1).setIsDead(true))
                .build();
        EloRules.Verdict.Scored vA = (EloRules.Verdict.Scored) EloRules.judge(aWin, id -> 30);
        assertThat(vA.scoreA()).isEqualTo(1.0);
        assertThat(vA.roundCapDraw()).isFalse();

        // 2. SIDE_B_WIN 但 0 队玩家仍存活未逃跑 -> 打满改判平局
        BattleResultEvent bWinAlive = battle(6002L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 1L, 2L)
                .toBuilder().setSettlementFormatVersion(1).setTotalRounds(10)
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1L).setPlayerTeamIndex(0).setIsDead(false).setFled(false))
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(2L).setPlayerTeamIndex(1).setIsDead(false))
                .build();
        EloRules.Verdict.Scored vCapPlayer = (EloRules.Verdict.Scored) EloRules.judge(bWinAlive, id -> 30);
        assertThat(vCapPlayer.scoreA()).isEqualTo(0.5);
        assertThat(vCapPlayer.roundCapDraw()).isTrue();

        // 3. SIDE_B_WIN 且 0 队玩家已阵亡但宝宝未阵亡 -> 仍有可行动单位 -> 打满改判平局
        BattleResultEvent bWinPetAlive = battle(6003L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 1L, 2L)
                .toBuilder().setSettlementFormatVersion(1).setTotalRounds(10)
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1L).setPlayerTeamIndex(0).setIsDead(true)
                        .addPets(BattlePetSettlementData.newBuilder().setPetId(77L).setIsDead(false)))
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(2L).setPlayerTeamIndex(1).setIsDead(false))
                .build();
        EloRules.Verdict.Scored vCapPet = (EloRules.Verdict.Scored) EloRules.judge(bWinPetAlive, id -> 30);
        assertThat(vCapPet.scoreA()).isEqualTo(0.5);
        assertThat(vCapPet.roundCapDraw()).isTrue();

        // 4. SIDE_B_WIN 且 0 队玩家阵亡/逃跑且宝宝全阵亡 -> 真输
        BattleResultEvent bWinRealLoss = battle(6004L, MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 1L, 2L)
                .toBuilder().setSettlementFormatVersion(1).setTotalRounds(50)
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(1L).setPlayerTeamIndex(0).setIsDead(true)
                        .addPets(BattlePetSettlementData.newBuilder().setPetId(77L).setIsDead(true)))
                .addSettlements(BattleSettlementData.newBuilder().setPlayerId(2L).setPlayerTeamIndex(1).setIsDead(false))
                .build();
        EloRules.Verdict.Scored vRealLoss = (EloRules.Verdict.Scored) EloRules.judge(bWinRealLoss, id -> 30);
        assertThat(vRealLoss.scoreA()).isEqualTo(0.0);
        assertThat(vRealLoss.roundCapDraw()).isFalse();

        // 5. 旧版无个人原件按 ceil(time_limit * 1000 / 6000)（0 按 300s -> 50 回合）
        assertThat(EloRules.defaultRoundCapForTimeLimitSeconds(0)).isEqualTo(50);
        assertThat(EloRules.defaultRoundCapForTimeLimitSeconds(180)).isEqualTo(30);
        assertThat(EloRules.defaultRoundCapForTimeLimitSeconds(181)).isEqualTo(31);
    }

    private static BattleResultEvent battle(long id, int mode, eBattleOutcome outcome, long pA, long pB) {
        return BattleResultEvent.newBuilder()
                .setBattleId(id).setMatchMode(mode).setOutcome(outcome)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(pA))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(pB))
                .build();
    }
}
