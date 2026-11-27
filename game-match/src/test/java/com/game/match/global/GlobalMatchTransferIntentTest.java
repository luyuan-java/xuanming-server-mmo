package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.player.RouteAuthorityInitializer;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.game.common.team.TeamPolicyAuthority;
import com.game.match.global.pb.GlobalDirectIntentRow;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamMatchIntent;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 过户后排队查询回 request_cancelled=true、撤队不覆写旧原件、整队意图回 state=6 及无战斗取消回 state=5 的 H2 实跑回归。 */
class GlobalMatchTransferIntentTest {

    private record TestDb(JdbcDataSource raw, DataSource normal) {}

    private static String h2Ddl(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ").replace(" int unsigned ", " BIGINT ")
                .replaceAll(" CHARACTER SET [^ ]+ COLLATE [^ ]+", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    private static void initRouteAuthority(Connection c, String principal) throws Exception {
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
    }

    private static DataSource wrapAuthority(String url) throws Exception {
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
        return new AuthorityDataSource(url, creds, boot, false, List.of());
    }

    private static TestDb createDb() throws Exception {
        String url = "jdbc:h2:mem:global_transfer_" + UUID.randomUUID().toString().replace("-", "")
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
            initRouteAuthority(c, principal);
            c.setAutoCommit(false);
            TeamPolicyAuthority.ensure(c, false, 3);
            c.commit();
        }
        return new TestDb(ds, wrapAuthority(url));
    }

    private static SessionContext actor(long playerId, String account, long rev) {
        return SessionContext.newBuilder().setPlayerId(playerId).setAccount(account).setOwnerRevision(rev).build();
    }

    @Test
    void 过户后现主人查旧请求回已取消且撤单不动旧原件_整队原件回状态6_旧会话仍拒绝() throws Exception {
        TestDb db = createDb();
        JdbcDataSource ds = db.raw();
        GlobalMatchStore store = new GlobalMatchStore(db.normal(),
                new GlobalMatchProperties(true, false, 30, 180, 120, "test-global-probe-secret-0123456789"),
                new com.game.match.MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null),
                true);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO player(player_id,account,owner_revision,trade_listing_id,zone_id) VALUES(101,'old-acc',1,0,1)");
        }
        String joinReq = UUID.randomUUID().toString();
        UUID joinUuid = UUID.fromString(joinReq);
        SessionContext oldOwner = actor(101L, "old-acc", 1L);
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO match_global_join_intent(player_id,request_id,ticket_record,cancelled,request_high,request_low,owner_account,owner_revision,request_payload,observer_exits,admission_prepared,admission_ticket,session_binding,admission_completed,response_payload) "
                             + "VALUES(101,?,?,1,?,?,'old-acc',1,X'',X'',0,X'',X'',0,X'')")) {
            ps.setString(1, joinReq);
            ps.setBytes(2, new byte[0]);
            ps.setObject(3, com.game.pbmysql.PbMysql.uint64(joinUuid.getMostSignificantBits()));
            ps.setObject(4, com.game.pbmysql.PbMysql.uint64(joinUuid.getLeastSignificantBits()));
            ps.executeUpdate();
        }
        assertThat(store.requestCancelled(oldOwner, joinReq)).isTrue();

        // 写入一条前主人的整队直接意图
        String teamReqOld = UUID.randomUUID().toString();
        StartTeamMatchRequest startOld = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(500L).setBattleConfigId(1).setRequestId(teamReqOld).build();
        UUID oldUuid = UUID.fromString(teamReqOld);
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO match_global_direct_intent(kind,owner_id,request_high,request_low,payload,battle_id,cancelled,owner_account,owner_revision,admission_prepared,admission_completed) "
                             + "VALUES(1,?,?,?,?,0,0,'old-acc',1,1,0)")) {
            ps.setLong(1, 101L);
            ps.setObject(2, com.game.pbmysql.PbMysql.uint64(oldUuid.getMostSignificantBits()));
            ps.setObject(3, com.game.pbmysql.PbMysql.uint64(oldUuid.getLeastSignificantBits()));
            ps.setBytes(4, startOld.toByteArray());
            ps.executeUpdate();
        }

        // 角色过户给 new-acc (owner_revision = 3)
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE player SET account='new-acc',owner_revision=3 WHERE player_id=101");
        }
        SessionContext newOwner = actor(101L, "new-acc", 3L);

        // 1. 旧会话自身失效：仍抛异常，不给确定结论
        assertThatThrownBy(() -> store.requestCancelled(oldOwner, joinReq)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.cancel(oldOwner, "", joinReq)).isInstanceOf(RuntimeException.class);
        GlobalDirectIntentRow staleTeamKey = GlobalMatchStore.directUuidKey(1, 101L, teamReqOld).toBuilder()
                .setOwnerAccount("old-acc").setOwnerRevision(1L).build();
        assertThatThrownBy(() -> store.teamIntent(staleTeamKey, startOld.toByteString())).isInstanceOf(RuntimeException.class);

        // 2. 现主人查过户前 Join 请求 -> request_cancelled = true；撤单回成功且原件不动
        assertThat(store.requestCancelled(newOwner, joinReq)).isTrue();
        assertThat(store.cancel(newOwner, "", joinReq)).isTrue();
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("SELECT owner_account,owner_revision FROM match_global_join_intent WHERE player_id=101 AND request_id=?")) {
            ps.setString(1, joinReq);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("old-acc");
                assertThat(rs.getLong(2)).isEqualTo(1L);
            }
        }

        // 3. 现主人查过户前整队意图 -> state = 6（battle_id = 0）
        GlobalDirectIntentRow currentTeamKey = GlobalMatchStore.directUuidKey(1, 101L, teamReqOld).toBuilder()
                .setOwnerAccount("new-acc").setOwnerRevision(3L).build();
        TeamMatchIntent transferredIntent = store.teamIntent(currentTeamKey, startOld.toByteString());
        assertThat(transferredIntent).isNotNull();
        assertThat(transferredIntent.getRequestId()).isEqualTo(teamReqOld);
        assertThat(transferredIntent.getBattleId()).isZero();
        assertThat(transferredIntent.getState()).isEqualTo(6);

        // 4. 现主人自己未成局即已取消（含 CancelQueue 墓碑）的意图 -> state = 5（battle_id = 0）
        String cancelledTeamReq = UUID.randomUUID().toString();
        UUID cancelledUuid = UUID.fromString(cancelledTeamReq);
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO match_global_join_intent(player_id,request_id,ticket_record,cancelled,request_high,request_low,owner_account,owner_revision,request_payload,observer_exits,admission_prepared,admission_ticket,session_binding,admission_completed,response_payload) "
                             + "VALUES(101,?,?,1,?,?,'new-acc',3,X'',X'',0,X'',X'',0,X'')")) {
            ps.setString(1, cancelledTeamReq);
            ps.setBytes(2, new byte[0]);
            ps.setObject(3, com.game.pbmysql.PbMysql.uint64(cancelledUuid.getMostSignificantBits()));
            ps.setObject(4, com.game.pbmysql.PbMysql.uint64(cancelledUuid.getLeastSignificantBits()));
            ps.executeUpdate();
        }
        GlobalDirectIntentRow cancelledKey = GlobalMatchStore.directUuidKey(1, 101L, cancelledTeamReq).toBuilder()
                .setOwnerAccount("new-acc").setOwnerRevision(3L).build();
        TeamMatchIntent cancelledIntent = store.teamIntent(cancelledKey, com.google.protobuf.ByteString.EMPTY);
        assertThat(cancelledIntent).isNotNull();
        assertThat(cancelledIntent.getBattleId()).isZero();
        assertThat(cancelledIntent.getState()).isEqualTo(5);

        // 5. 现主人已准备入场但尚未生成 battle_id 的整队直接意图 -> state = 1（battle_id = 0）
        String pendingTeamReq = UUID.randomUUID().toString();
        UUID pendingUuid = UUID.fromString(pendingTeamReq);
        StartTeamMatchRequest startPending = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(500L).setBattleConfigId(1).setRequestId(pendingTeamReq).build();
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO match_global_direct_intent(kind,owner_id,request_high,request_low,payload,battle_id,cancelled,owner_account,owner_revision,admission_prepared,admission_completed) "
                             + "VALUES(1,?,?,?,?,0,0,'new-acc',3,1,0)")) {
            ps.setLong(1, 101L);
            ps.setObject(2, com.game.pbmysql.PbMysql.uint64(pendingUuid.getMostSignificantBits()));
            ps.setObject(3, com.game.pbmysql.PbMysql.uint64(pendingUuid.getLeastSignificantBits()));
            ps.setBytes(4, startPending.toByteArray());
            ps.executeUpdate();
        }
        GlobalDirectIntentRow pendingKey = GlobalMatchStore.directUuidKey(1, 101L, pendingTeamReq).toBuilder()
                .setOwnerAccount("new-acc").setOwnerRevision(3L).build();
        TeamMatchIntent pendingIntent = store.teamIntent(pendingKey, startPending.toByteString());
        assertThat(pendingIntent).isNotNull();
        assertThat(pendingIntent.getBattleId()).isZero();
        assertThat(pendingIntent.getState()).isEqualTo(1);
    }
}
