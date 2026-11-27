package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.player.RouteAuthorityInitializer;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.game.common.team.TeamPolicyAuthority;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.global.pb.GlobalProbeProfile;
import com.game.match.global.pb.GlobalTicketRow;
import com.game.match.rating.BattleCompletionStore;
import com.game.proto.BattleCreationState;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.QueryBattleCreationResponse;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.CommitBattleOutcomeRequest;
import com.game.proto.match.NetworkProbeGrant;
import com.game.proto.match.NetworkProbeReceipt;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VoidSettlementAndEarlyAbortTest {

    private static final String SECRET = "test-global-probe-secret-0123456789";

    private record TestDb(JdbcDataSource raw, DataSource normal) {}

    private static String h2Ddl(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ").replace(" int unsigned ", " BIGINT ")
                .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    private static TestDb createDb() throws Exception {
        String url = "jdbc:h2:mem:void_abort_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE player(player_id BIGINT PRIMARY KEY,account VARCHAR(255) NOT NULL,owner_revision BIGINT NOT NULL,trade_listing_id BIGINT NOT NULL DEFAULT 0,zone_id INT NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE team_policy(id INT PRIMARY KEY,contract BLOB,allow_cross_zone TINYINT NOT NULL DEFAULT 0,contract_digest BLOB)");
            s.execute("CREATE TABLE match_rating(player_id BIGINT NOT NULL,rating_centi BIGINT NOT NULL,games BIGINT NOT NULL DEFAULT 0,updated_at_ms BIGINT NOT NULL DEFAULT 0,match_mode INT NOT NULL DEFAULT 3,PRIMARY KEY(player_id,match_mode))");
            String principal;
            try (var rs = s.executeQuery("SELECT CURRENT_USER()")) {
                rs.next();
                principal = rs.getString(1);
            }
            var matchSchema = GlobalMatchStore.registry();
            matchSchema.register(com.game.match.global.pb.GlobalQueueUnitRow.getDefaultInstance());
            matchSchema.register(com.game.match.global.pb.GlobalQueueUnitRegionRow.getDefaultInstance());
            for (var table : matchSchema.tables()) {
                s.execute(h2Ddl(table.createTableSql()));
            }
            var resource = new org.springframework.core.io.ClassPathResource("db/game-route-authority-schema.sql");
            String sql;
            try (var input = resource.getInputStream()) {
                sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            sql = sql.replaceAll("(?i)ENGINE\\s*=\\s*InnoDB[^;]*", "");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                    new org.springframework.core.io.support.EncodedResource(
                            new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            RouteAuthorityInitializer.initialize(c, UUID.randomUUID().toString(), List.of(1), principal);
            c.setAutoCommit(false);
            TeamPolicyAuthority.ensure(c, false, 3);
            c.commit();
        }
        byte[] instance = java.nio.ByteBuffer.allocate(16)
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .putLong(UUID.randomUUID().getLeastSignificantBits()).array();
        WriterAuthority boot = Mockito.mock(WriterAuthority.class, inv -> {
            String name = inv.getMethod().getName();
            if ("begin".equals(name)) {
                ((Connection) inv.getArgument(0)).setAutoCommit(false);
                return null;
            }
            if ("verifyPhysical".equals(name) || "seal".equals(name)) return null;
            if ("isSealed".equals(name)) return false;
            if ("instanceId".equals(name)) return instance.clone();
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        });
        Properties creds = new Properties();
        creds.setProperty("user", "sa");
        creds.setProperty("password", "");
        return new TestDb(ds, new AuthorityDataSource(url, creds, boot, false, List.of()));
    }

    private static GlobalFormationRow prepare(TestDb db, GlobalMatchStore store, long battleId, long p1, long p2) throws Exception {
        long now = store.now();
        String opId = UUID.randomUUID().toString();
        String t1Id = UUID.randomUUID().toString();
        String t2Id = UUID.randomUUID().toString();
        var pb = GlobalMatchStore.registry();
        try (Connection c = db.raw().getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO player(player_id,account,owner_revision,trade_listing_id,zone_id) VALUES(" + p1 + ",'acc-" + p1 + "',1,0,1)");
            s.execute("INSERT INTO player(player_id,account,owner_revision,trade_listing_id,zone_id) VALUES(" + p2 + ",'acc-" + p2 + "',1,0,1)");
            pb.insert(c, GlobalTicketRow.newBuilder()
                    .setPlayerId(p1).setTicket(t1Id).setState(2).setMode(3).setConfigId(1001).setTeamSize(2)
                    .setPreferredRegion(1).setSelectedRegion(1).setFormationId(battleId).setEnqueuedMs(now)
                    .setExpiresMs(now + 120_000).setOwnerAccount("acc-" + p1).setOwnerRevision(1).build());
            pb.insert(c, GlobalTicketRow.newBuilder()
                    .setPlayerId(p2).setTicket(t2Id).setState(2).setMode(3).setConfigId(1001).setTeamSize(2)
                    .setPreferredRegion(1).setSelectedRegion(1).setFormationId(battleId).setEnqueuedMs(now)
                    .setExpiresMs(now + 120_000).setOwnerAccount("acc-" + p2).setOwnerRevision(1).build());
            pb.insert(c, GlobalFormationRow.newBuilder()
                    .setBattleId(battleId).setOperationId(opId).setState(1)
                    .setRegion(1).setNodeId(1).setInstanceId("inst-A")
                    .setHost("127.0.0.1").setPort(21200).setMode(3).setConfigId(1001)
                    .setCreatedMs(now).setRetryMs(now).setDeadlineMs(now + 180_000)
                    .addMembers(com.game.match.global.pb.GlobalMember.newBuilder().setPlayerId(p1).setTicket(t1Id).setTeamIndex(0))
                    .addMembers(com.game.match.global.pb.GlobalMember.newBuilder().setPlayerId(p2).setTicket(t2Id).setTeamIndex(1))
                    .build());
        }
        var f = store.acquireWork(battleId);
        var create = CreateBattleRequest.newBuilder()
                .setBattleId(battleId).setPlacementOperationId(f.getOperationId()).setTargetInstanceId(f.getInstanceId())
                .setBattleConfigId(f.getConfigId()).setMatchMode(f.getMode())
                .setCreatedAtMs(f.getCreatedMs()).setRequiredPhysicalRegion(f.getRegion())
                .setDeadlineMs(f.getDeadlineMs()).setOutcomeProtocolVersion(1)
                .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(p1).setTeamIndex(0))
                .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(p2).setTeamIndex(1))
                .build();
        return store.saveIntent(f, create.toByteArray(),
                QueryBattleCreationResponse.newBuilder()
                        .setState(BattleCreationState.BATTLE_CREATION_ABSENT)
                        .setTargetInstanceId(f.getInstanceId())
                        .setSupportedOutcomeProtocolVersion(1).build());
    }

    @Test
    void matchRejectsVoidFromBattleEngineAndEnforcesWhitelist() {
        var voidSettlement = VoidSettlements.build(701L, 1L, 0, 10_000L, 3, 1001);
        assertThat(VoidSettlements.isValid(voidSettlement)).isTrue();
        assertThat(VoidSettlements.isValid(voidSettlement.toBuilder().setHealth(1).build())).isFalse();
        assertThat(VoidSettlements.isValid(voidSettlement.toBuilder().setGoldGain(1).build())).isFalse();

        BattleResultEvent forgedVoid = BattleResultEvent.newBuilder()
                .setBattleId(701L).setMatchMode(3).setBattleConfigId(1001)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .setSettlementFormatVersion(1)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(1L))
                .addSettlements(voidSettlement)
                .build();
        assertThatThrownBy(() -> BattleCompletionStore.validateTerminal(forgedVoid.toByteString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deathTrackerRequiresTwoObservationsAtLeast30sApartSharedPerInstanceAndClearedOnAlive() {
        BattleInstanceDeathTracker tracker = new BattleInstanceDeathTracker();
        tracker.markSuspect(501L, "inst-A");
        tracker.markSuspect(502L, "inst-A");

        // 首次观察不确认
        assertThat(tracker.recordObservation("inst-A", BattleInstanceDeathTracker.EvidenceKind.DIFFERENT_INSTANCE_ANSWERED, 100_000L))
                .isFalse();
        assertThat(tracker.isConfirmedDead("inst-A")).isFalse();

        // 间隔 29_999 ms 仍不确认
        assertThat(tracker.recordObservation("inst-A", BattleInstanceDeathTracker.EvidenceKind.CONNECTION_REFUSED_AND_DEREGISTERED, 129_999L))
                .isFalse();
        assertThat(tracker.isConfirmedDead("inst-A")).isFalse();

        // 原实例亲口应答立即撤销先前证据
        tracker.recordAlive("inst-A");
        assertThat(tracker.isConfirmedDead("inst-A")).isFalse();
        assertThat(tracker.suspectBattleIds()).isEmpty();

        // 重新取证：满 30_000 ms 即确认失活（同实例各局共享）
        tracker.markSuspect(501L, "inst-A");
        tracker.markSuspect(502L, "inst-A");
        assertThat(tracker.recordObservation("inst-A", BattleInstanceDeathTracker.EvidenceKind.NO_LIVE_ENDPOINT_FOR_IP, 200_000L))
                .isFalse();
        assertThat(tracker.recordObservation("inst-A", BattleInstanceDeathTracker.EvidenceKind.NO_LIVE_ENDPOINT_FOR_IP, 230_000L))
                .isTrue();
        assertThat(tracker.isConfirmedDead("inst-A")).isTrue();
    }

    @Test
    void earlyAbortWritesSevenFieldVoidSettlementsAtomicallyAndRacesCleanlyWithComplete() throws Exception {
        TestDb db = createDb();
        GlobalMatchStore store = new GlobalMatchStore(db.normal(),
                new GlobalMatchProperties(true, false, 30, 180, 120, SECRET),
                new com.game.match.MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null),
                true);
        BattleCompletionStore completions = new BattleCompletionStore(db.normal());

        // 1. 提前作废在截止前写入全员 7 字段 VOID 个人来源并释放原票
        GlobalFormationRow f1 = prepare(db, store, 9101L, 1L, 2L);
        assertThat(store.abortConfirmedDeadInstance(9101L)).isEqualTo(2);
        assertThat(store.terminal(9101L).getTerminalDecision()).isEqualTo(2);
        assertThat(store.ticket(1L).getState()).isEqualTo(4);
        assertThat(store.ticket(2L).getState()).isEqualTo(4);

        var voidRows = completions.pendingPlayers(0, 0);
        assertThat(voidRows).hasSize(2);
        for (var row : voidRows) {
            BattleSettlementData parsed = BattleSettlementData.parseFrom(row.getOriginal());
            assertThat(VoidSettlements.isValid(parsed)).isTrue();
            assertThat(parsed.getBattleId()).isEqualTo(9101L);
            assertThat(parsed.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_VOID);
            assertThat(parsed.getMatchMode()).isEqualTo(3);
            assertThat(parsed.getBattleConfigId()).isEqualTo(1001);
        }

        // 2. 提前作废与正常 COMPLETE 并发竞争：全员必唯一落同一结果，不可半提交
        GlobalFormationRow f2 = prepare(db, store, 9102L, 3L, 4L);
        BattleResultEvent normalEvent = BattleResultEvent.newBuilder()
                .setBattleId(9102L).setMatchMode(3).setBattleConfigId(1001)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setWinnerTeamIndex(0)
                .setTotalRounds(3).setFinishedAtMs(store.now()).setSettlementFormatVersion(1)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(3L))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(4L))
                .addSettlements(BattleSettlementData.newBuilder().setBattleId(9102L).setPlayerId(3L)
                        .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(0).setTotalRounds(3).build())
                .addSettlements(BattleSettlementData.newBuilder().setBattleId(9102L).setPlayerId(4L)
                        .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setPlayerTeamIndex(1).setTotalRounds(3).build())
                .build();
        CommitBattleOutcomeRequest commitReq = CommitBattleOutcomeRequest.newBuilder()
                .setBattleId(9102L).setPlacementOperationId(f2.getOperationId()).setTargetInstanceId(f2.getInstanceId())
                .setRawResultPayload(normalEvent.toByteString()).build();

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cFuture = pool.submit(() -> { barrier.await(); return store.commitOutcome(commitReq).getTerminalDecision(); });
            var aFuture = pool.submit(() -> { barrier.await(); return store.abortConfirmedDeadInstance(9102L); });
            int cRes = cFuture.get(10, TimeUnit.SECONDS);
            int aRes = aFuture.get(10, TimeUnit.SECONDS);
            assertThat(cRes).isEqualTo(aRes).isIn(1, 2);
            var f2Rows = completions.pendingPlayers(0, 0).stream().filter(r -> r.getBattleId() == 9102L).toList();
            assertThat(f2Rows).hasSize(2);
            eBattleOutcome expected = cRes == 1 ? eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN : eBattleOutcome.BATTLE_OUTCOME_VOID;
            for (var row : f2Rows) {
                BattleSettlementData parsed = BattleSettlementData.parseFrom(row.getOriginal());
                assertThat(parsed.getOutcome()).isEqualTo(expected);
                if (cRes == 2) {
                    assertThat(VoidSettlements.isValid(parsed)).isTrue();
                }
            }
        }
    }
}
