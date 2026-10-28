package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.completion.pb.BattlePlayerCompletionRow;
import com.game.match.rating.BattleCompletionStore;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 专用隔离fixture必须预置未来retry_ms的未APPLIED个人来源；不能只数当前due列表。 */
class MatchMaintenanceDrainTiDbTest {
    @Test void futureRetryAndUnappliedSourcesStillBlockTheRealSqlDrain() throws Exception {
        String root = System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));
        if (root == null || root.isBlank())
            throw new IllegalArgumentException("必须通过game.it.authority.jdbc（兼容game.it.mysql）显式指定本机TiDB根URL");
        var options = AuthoritySqlFixture.Options.fromEnvironment(root,
                System.getProperty("game.it.mysql.user", "root"),
                System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""),
                "jmatchdrain", false, List.of(1));
        try (var fixture = AuthoritySqlFixture.prepareWithPlayerSchema(options, (schema, principal) -> {
            BattleCompletionStore.registry().initializeFresh(schema, principal);
            // prepare回调成功返回前一直标记UNKNOWN；种子提交未明时保留私有owner，不能误清。
            seedFutureSource(schema);
        })) {
            var source = fixture.normalDataSource("?connectTimeout=3000&socketTimeout=5000");
            var store = new BattleCompletionStore(source);
            long expected = 2;
            assertTrue(expected > 0);
            assertEquals(expected, store.maintenanceOutstanding());
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM match_battle_player_completion WHERE applied=0 AND retry_ms>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")) {
                assertTrue(rows.next());
                assertTrue(rows.getLong(1) > 0, "fixture必须真实包含尚未到重试时刻的来源");
            }
        }
    }

    private static void seedFutureSource(Connection schema) throws Exception {
        var db = BattleCompletionStore.registry();
        schema.setAutoCommit(false);
        try {
            long now;
            try (var statement = schema.createStatement()) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")) {
                    assertTrue(rows.next());
                    now = rows.getLong(1);
                    assertTrue(now > 0);
                    assertFalse(rows.next());
                }
            }
            long future = Math.addExact(now, 86400000L);
            long battleId = 1;
            long playerId = 1;
            var settlement = BattleSettlementData.newBuilder().setBattleId(battleId).setPlayerId(playerId)
                    .setPlayerTeamIndex(0).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .setTotalRounds(1).build();
            var event = BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(3)
                    .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setWinnerTeamIndex(0)
                    .setTotalRounds(1).setFinishedAtMs(now).setSettlementFormatVersion(1)
                    .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(playerId))
                    .addSettlements(settlement).build();
            var original = event.toByteString();
            var members = BattleCompletionStore.validateTerminal(original);
            assertEquals(1, members.size());
            assertEquals(settlement, members.get(playerId));
            var personalOriginal = settlement.toByteString();
            // 只种正式PB形状的计数SQL接缝；source_kind=2不声称真实RPC或Kafka闭环。
            var parent = BattleCompletionRow.newBuilder().setBattleId(battleId).setFormatVersion(1)
                    .setOriginal(original).setOriginalDigest(BattleCompletionStore.digest(original))
                    .setPlayerCount(1).setTerminalDecision(1).setResultPublished(false)
                    .setRetryMs(future).setIssuedAtMs(now).setSourceKind(2).build();
            var player = BattlePlayerCompletionRow.newBuilder().setBattleId(battleId).setPlayerId(playerId)
                    .setOriginal(personalOriginal).setOriginalDigest(BattleCompletionStore.digest(personalOriginal))
                    .setOwnerAccount("jmatchdrain-fixture").setOwnerRevision(1).setIssuedAtMs(now)
                    .setInboxSeq(0).setApplied(false).setRetryMs(future).build();
            try (var statement = schema.createStatement()) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM match_battle_completion),(SELECT COUNT(*) FROM match_battle_player_completion)")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                    assertEquals(0, rows.getLong(2));
                }
            }
            db.insert(schema, parent);
            db.insert(schema, player);
            assertEquals(parent, db.findOneByPk(schema,
                    BattleCompletionRow.newBuilder().setBattleId(battleId).build()).orElseThrow());
            assertEquals(player, db.findOneByPk(schema,
                    BattlePlayerCompletionRow.newBuilder().setBattleId(battleId).setPlayerId(playerId).build()).orElseThrow());
            try (var statement = schema.createStatement()) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM match_battle_completion WHERE terminal_decision=1 AND result_published=0),(SELECT COUNT(*) FROM match_battle_player_completion WHERE applied=0),(SELECT COUNT(*) FROM match_battle_player_completion WHERE applied=0 AND retry_ms>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED))")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong(1));
                    assertEquals(1, rows.getLong(2));
                    assertEquals(1, rows.getLong(3));
                }
            }
            schema.commit();
            schema.setAutoCommit(true);
        } catch (Exception | Error failure) {
            try { schema.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
}
