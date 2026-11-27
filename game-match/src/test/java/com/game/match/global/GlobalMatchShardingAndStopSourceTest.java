package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.game.common.workshard.WorkShard;
import com.game.match.MatchProperties;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.global.pb.GlobalRankOutboxRow;
import com.game.match.global.pb.GlobalRankSourceRow;
import com.game.match.global.pb.GlobalTicketRow;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.RatingStore;
import com.game.match.rating.RatingTestDatabase;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-10 全服匹配第四批：后台按 64 片分工（含空表全做/非成员不做）与评级来源停写不卡消费。 */
class GlobalMatchShardingAndStopSourceTest {

    private static String toH2(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ")
                .replace(" int unsigned ", " BIGINT ")
                .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    private static void initGlobalSchema(DataSource ds) throws Exception {
        PbMysql matchSchema = GlobalMatchStore.registry();
        matchSchema.register(com.game.match.global.pb.GlobalQueueUnitRow.getDefaultInstance());
        matchSchema.register(com.game.match.global.pb.GlobalQueueUnitRegionRow.getDefaultInstance());
        try (Connection c = RatingTestDatabase.h2SchemaDataSource(ds).getConnection();
             Statement st = c.createStatement()) {
            for (TableSchema table : matchSchema.tables()) {
                st.execute(toH2(table.createTableSql()));
            }
            st.execute("CREATE TABLE IF NOT EXISTS team_policy(id INT PRIMARY KEY, contract VARBINARY(4096), allow_cross_zone TINYINT NOT NULL DEFAULT 0, contract_digest VARBINARY(64))");
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            com.game.common.team.TeamPolicyAuthority.ensure(c, false, 3);
            c.commit();
        }
    }

    @Test
    void 成局与已建房票按64片分工无重无漏_空成员表全做_非成员不碰库() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            initGlobalSchema(db.dataSource);
            GlobalMatchProperties gProps = new GlobalMatchProperties(true, false, 15, 250, 300,
                    "0123456789abcdef0123456789abcdef");
            GlobalMatchStore store = new GlobalMatchStore(db.dataSource, gProps,
                    new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null), true);

            // 写入 16 条待推进成局（battle_id = 1..16）与 16 条已建房票（player_id = 101..116）
            PbMysql pb = GlobalMatchStore.registry();
            try (Connection c = RatingTestDatabase.h2SchemaDataSource(db.dataSource).getConnection()) {
                for (long id = 1; id <= 16; id++) {
                    pb.insert(c, GlobalFormationRow.newBuilder()
                            .setBattleId(id).setOperationId("op-" + id).setState(1)
                            .setRetryMs(1L).setCreatedMs(1L).build());
                }
                for (long pid = 101; pid <= 116; pid++) {
                    pb.insert(c, GlobalTicketRow.newBuilder()
                            .setPlayerId(pid).setTicket("t-" + pid).setState(3)
                            .setEnqueuedMs(1000L + pid).build());
                }
            }

            AtomicLong now = new AtomicLong(1_700_000_000_000L);
            WorkShard.Membership nodeA = new WorkShard.Membership(30_000L, now::get);
            nodeA.setSelf("match-a");
            nodeA.updateMembers(List.of("match-a", "match-b"));

            WorkShard.Membership nodeB = new WorkShard.Membership(30_000L, now::get);
            nodeB.setSelf("match-b");
            nodeB.updateMembers(List.of("match-a", "match-b"));

            // 1. 两副本按 battle_id 分片：互不重叠且并集覆齐全部 16 条
            List<GlobalFormationRow> formA = store.pending(nodeA.sqlFilter("match_formation", "battle_id"));
            List<GlobalFormationRow> formB = store.pending(nodeB.sqlFilter("match_formation", "battle_id"));
            Set<Long> idsA = new HashSet<>();
            formA.forEach(r -> idsA.add(r.getBattleId()));
            Set<Long> idsB = new HashSet<>();
            formB.forEach(r -> idsB.add(r.getBattleId()));
            assertThat(idsA).doesNotContainAnyElementsOf(idsB);
            Set<Long> unionForms = new HashSet<>(idsA);
            unionForms.addAll(idsB);
            assertThat(unionForms).hasSize(16);

            // 2. 已建房票按 player_id 分片：每副本只读属于自己的片
            List<GlobalTicketRow> readyA = store.readyCandidates(0L, 0L, nodeA.sqlFilter("match_ready_ticket", "player_id"));
            List<GlobalTicketRow> readyB = store.readyCandidates(0L, 0L, nodeB.sqlFilter("match_ready_ticket", "player_id"));
            assertThat(readyA).allSatisfy(t -> assertThat(nodeA.owns("match_ready_ticket:" + WorkShard.shardOf(t.getPlayerId()))).isTrue());
            assertThat(readyB).allSatisfy(t -> assertThat(nodeB.owns("match_ready_ticket:" + WorkShard.shardOf(t.getPlayerId()))).isTrue());

            // 3. 成员表为空（保活退化）-> 全量读出 16 条
            WorkShard.Membership empty = new WorkShard.Membership(30_000L, now::get);
            empty.setSelf("match-a");
            assertThat(store.pending(empty.sqlFilter("match_formation", "battle_id"))).hasSize(16);

            // 4. 成员表非空但本实例不在其中（如 canary）-> 返回空且零查库
            WorkShard.Membership canary = new WorkShard.Membership(30_000L, now::get);
            canary.setSelf("match-canary");
            canary.updateMembers(List.of("match-a", "match-b"));
            assertThat(store.pending(canary.sqlFilter("match_formation", "battle_id"))).isEmpty();
            assertThat(store.readyCandidates(0L, 0L, canary.sqlFilter("match_ready_ticket", "player_id"))).isEmpty();
            assertThat(store.pendingJoinUpper(canary.sqlFilter("match_join_intent", "player_id"))).isNull();
            assertThat(store.pendingDirectUpper(canary.sqlFilter("match_direct_intent", "owner_id"))).isNull();
        }
    }

    @Test
    void 评级来源停写后继续入账不卡消费_不写outbox不推游标_计unpublished且重放仍回DUPLICATE() throws Exception {
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
                RatingStore ratings = new RatingStore(
                        RatingStore.connections(db.dataSource), id -> 30, metrics, System::currentTimeMillis)
                        .withCommitHook(outbox);

                BattleResultEvent b1 = BattleResultEvent.newBuilder()
                        .setBattleId(1001L).setMatchMode(3)
                        .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                        .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(11L))
                        .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(12L))
                        .build();
                assertThat(ratings.apply(b1).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
                long afterFirst = ratings.findOrFresh(11L).ratingCenti();
                assertThat(afterFirst).isGreaterThan(150_000L);

                // 停写评级来源：返回最终游标 1
                assertThat(outbox.stopSource()).isEqualTo(1L);

                // 停写后的新局：不报错、评分照记、入账标记照写、不写 outbox、游标保持 1、unpublished 计数 +1
                BattleResultEvent b2 = b1.toBuilder().setBattleId(1002L).build();
                assertThat(ratings.apply(b2).outcome()).isEqualTo(RatingStore.Outcome.APPLIED);
                assertThat(ratings.findOrFresh(11L).ratingCenti()).isGreaterThan(afterFirst);
                assertThat(meters.get("game.match.rating.unpublished").tag("mode", "MATCH_MODE_1V1").counter().count())
                        .isEqualTo(1.0);

                try (Connection c = db.dataSource.getConnection();
                     Statement st = c.createStatement()) {
                    try (var r = st.executeQuery("SELECT COUNT(*) FROM match_global_rank_outbox")) {
                        assertThat(r.next()).isTrue();
                        assertThat(r.getInt(1)).isEqualTo(1);
                    }
                    try (var r = st.executeQuery("SELECT produced_cursor FROM match_global_rank_source WHERE source_epoch=1")) {
                        assertThat(r.next()).isTrue();
                        assertThat(r.getLong(1)).isEqualTo(1L);
                    }
                }

                // 停写后入账的同一局重放仍回 DUPLICATE，不二次加分、不二次计 unpublished
                long afterSecond = ratings.findOrFresh(11L).ratingCenti();
                assertThat(ratings.apply(b2).outcome()).isEqualTo(RatingStore.Outcome.DUPLICATE);
                assertThat(ratings.findOrFresh(11L).ratingCenti()).isEqualTo(afterSecond);
                assertThat(meters.get("game.match.rating.unpublished").tag("mode", "MATCH_MODE_1V1").counter().count())
                        .isEqualTo(1.0);
            } finally {
                meters.close();
            }
        }
    }
}
