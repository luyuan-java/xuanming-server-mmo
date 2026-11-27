package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.player.RouteAuthorityInitializer;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.game.common.team.TeamPolicyAuthority;
import com.game.common.team.TeamPolicyContract;
import com.game.proto.match.MatchMode;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.team.TeamProperties;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.TeamTips;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 人数档真人 PVP 两条组队限制（5v5 拒 4 人队、计分组排评分差上限与策略指纹）SQL 事务校验。 */
class TeamPvpLimitsTest {

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

    private static TestDb createDb(int pvpRatingGapMax) throws Exception {
        String url = "jdbc:h2:mem:team_pvp_limits_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String principal;
            try (var rs = s.executeQuery("SELECT CURRENT_USER()")) {
                rs.next();
                principal = rs.getString(1);
            }
            for (var table : TeamTables.registry().tables()) {
                s.execute(h2Ddl(table.createTableSql()));
            }
            s.execute("CREATE DOMAIN UNSIGNED AS BIGINT");
            s.execute("CREATE TABLE player(player_id BIGINT PRIMARY KEY,account VARCHAR(191) NOT NULL,owner_revision BIGINT NOT NULL,trade_listing_id BIGINT NOT NULL DEFAULT 0,zone_id INT NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE match_rating(player_id BIGINT PRIMARY KEY,rating_centi BIGINT NOT NULL,games BIGINT NOT NULL DEFAULT 0,updated_at_ms BIGINT NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE match_global_ticket(player_id BIGINT PRIMARY KEY,state INT)");
            s.execute("CREATE TABLE match_global_watching(player_id BIGINT PRIMARY KEY,operation_id VARCHAR(36),owner_account VARCHAR(191),owner_revision BIGINT,state INT,add_request BLOB)");
            s.execute("CREATE TABLE match_global_watch_head(player_id BIGINT PRIMARY KEY,request_id VARCHAR(36))");
            s.execute("CREATE TABLE match_global_watch_request(player_id BIGINT,request_id VARCHAR(36),owner_account VARCHAR(191),owner_revision BIGINT,cancelled INT,original_add BLOB,PRIMARY KEY(player_id,request_id))");
            s.execute("CREATE TABLE guild_trial_launch_role(player_id BIGINT,request_id BIGINT,state INT)");
            s.execute("CREATE TABLE guild_trial_launch(request_id BIGINT PRIMARY KEY,state INT)");
            s.execute("CREATE TABLE guild_activity_slot(player_id BIGINT PRIMARY KEY,lobby_id BIGINT,expires_ms BIGINT)");
            s.execute("CREATE TABLE guild_trial_lobby(lobby_id BIGINT PRIMARY KEY,state INT,view_proto BLOB,guild_id BIGINT,activity_id INT)");
            initRouteAuthority(c, principal);
        }
        TeamTables.ensurePolicy(ds, false, pvpRatingGapMax);
        return new TestDb(ds, wrapAuthority(url));
    }

    private static SessionContext actor(long pid) {
        return SessionContext.newBuilder().setPlayerId(pid).setAccount("acc-" + pid).setOwnerRevision(1).build();
    }

    private static void seedPlayers(JdbcDataSource ds, long... pids) throws Exception {
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("INSERT INTO player(player_id,account,owner_revision,trade_listing_id,zone_id) VALUES(?,?,1,0,1)")) {
            for (long pid : pids) {
                ps.setLong(1, pid);
                ps.setString(2, "acc-" + pid);
                ps.executeUpdate();
            }
        }
    }

    private static void setRating(JdbcDataSource ds, long pid, long ratingCenti) throws Exception {
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement("MERGE INTO match_rating(player_id,rating_centi,games,updated_at_ms) KEY(player_id) VALUES(?,?,1,1000)")) {
            ps.setLong(1, pid);
            ps.setLong(2, ratingCenti);
            ps.executeUpdate();
        }
    }

    private static long launchCount(JdbcDataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM team_launch")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static TeamStore createParty(DataSource ds, long teamId, RuleConfig cfg, long... members) throws Exception {
        TeamStore base = new TeamStore(ds);
        Deadline d = Deadline.after(5000);
        long leader = members[0];
        var createReq = CreateTeamRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).build();
        assertThat(base.forActor(actor(leader), "CreateTeam", createReq)
                .mutate(Bind.create(leader, teamId), Op.create(leader, teamId, 1), SessionLoader.NONE, cfg, d).outcome())
                .isEqualTo(Outcome.COMMITTED);
        for (int i = 1; i < members.length; i++) {
            long target = members[i];
            var invReq = com.game.proto.team.InviteToTeamRequest.newBuilder()
                    .setExpectedTeamId(teamId).setTargetPlayerId(target).setRequestId(UUID.randomUUID().toString()).build();
            assertThat(base.forActor(actor(leader), "InviteToTeam", invReq)
                    .mutate(Bind.caller(leader, teamId), Op.invite(leader, target, 1), SessionLoader.NONE, cfg, d).outcome())
                    .isEqualTo(Outcome.COMMITTED);
            var accReq = RespondInviteRequest.newBuilder()
                    .setTeamId(teamId).setAccept(true).setRequestId(UUID.randomUUID().toString()).build();
            assertThat(base.forActor(actor(target), "RespondInvite", accReq)
                    .mutate(Bind.target(target, teamId), Op.respondInvite(target, true), SessionLoader.NONE, cfg, d).outcome())
                    .isEqualTo(Outcome.COMMITTED);
        }
        return base;
    }

    @Test
    void 配置校验与策略指纹包含评分差上限_不同副本配置拒启() throws Exception {
        TeamProperties def = new TeamProperties(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(def.pvpRatingGapMax()).isEqualTo(TeamPolicyContract.DEFAULT_PVP_RATING_GAP_MAX);
        TeamProperties unlimited = new TeamProperties(null, null, null, null, null, null, null, null, null, null, 0);
        assertThat(unlimited.pvpRatingGapMax()).isZero();
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, null, null, null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);

        TestDb db = createDb(500);
        try (Connection c = db.raw().getConnection()) {
            TeamPolicyAuthority.Contract loaded = TeamPolicyAuthority.load(c, 3);
            assertThat(loaded.pvpRatingGapMax()).isEqualTo(500);
        }
        assertThatThrownBy(() -> TeamTables.ensurePolicy(db.raw(), false, 300))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void 事务内复核5v5拒4人队与评分差超限整笔回滚_边界与0不限放行() throws Exception {
        // 1. 缺省上限 500 分：4 人队打 5v5 在事务内被拒（4031）；评分差 501 分被拒（4032）；差 500 分放行
        RuleConfig cfg = new RuleConfig(false, 500);
        TestDb db = createDb(500);
        seedPlayers(db.raw(), 1L, 2L, 3L, 4L);
        TeamStore store = createParty(db.normal(), 100L, cfg, 1L, 2L, 3L, 4L);
        Deadline d = Deadline.after(5000);
        Map<Long, Integer> zones4 = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1);

        var pvp5Req = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(100L).setBattleConfigId(10)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM).setTargetTeamSize(5)
                .setRequestId(UUID.randomUUID().toString()).build();
        var bound5 = store.forActor(actor(1L), "StartTeamMatch", pvp5Req);
        var snap = bound5.read(1L, 100L, d);
        PinnedResult rejected4in5 = bound5.commitMatchLock(snap, 1L, UUID.randomUUID().toString(),
                List.of(1L, 2L, 3L, 4L), snap.nowMs() + 30_000, SessionLoader.NONE, d, zones4, 30, cfg);
        assertThat(rejected4in5.code()).isEqualTo(TeamTips.PVP_PARTY_SIZE_NOT_ALLOWED);
        assertThat(launchCount(db.raw())).isZero();

        // 4 人队打 4v4，但 1 号=200100(2001分)，2..4 号缺行按 150000(1500分) -> 差 501 分 > 500 -> 4032 整笔回滚
        setRating(db.raw(), 1L, 200_100L);
        var pvp4TooWide = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(100L).setBattleConfigId(10)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM).setTargetTeamSize(4)
                .setRequestId(UUID.randomUUID().toString()).build();
        var boundWide = store.forActor(actor(1L), "StartTeamMatch", pvp4TooWide);
        PinnedResult rejectedGap = boundWide.commitMatchLock(snap, 1L, UUID.randomUUID().toString(),
                List.of(1L, 2L, 3L, 4L), snap.nowMs() + 30_000, SessionLoader.NONE, d, zones4, 30, cfg);
        assertThat(rejectedGap.code()).isEqualTo(TeamTips.RATING_GAP_TOO_LARGE);
        assertThat(launchCount(db.raw())).isZero();

        // 调整为恰好 500 分差（200000 - 150000 = 50000 <= 500*100）-> 4v4 放行落盘
        setRating(db.raw(), 1L, 200_000L);
        var pvp4Exact = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(100L).setBattleConfigId(10)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM).setTargetTeamSize(4)
                .setRequestId(UUID.randomUUID().toString()).build();
        var boundExact = store.forActor(actor(1L), "StartTeamMatch", pvp4Exact);
        PinnedResult okExact = boundExact.commitMatchLock(snap, 1L, UUID.randomUUID().toString(),
                List.of(1L, 2L, 3L, 4L), snap.nowMs() + 30_000, SessionLoader.NONE, d, zones4, 30, cfg);
        assertThat(okExact.code()).isEqualTo(TeamTips.OK);
        assertThat(okExact.commit()).isNotNull();
        assertThat(launchCount(db.raw())).isEqualTo(1L);

        // 2. 上限设为 0（不限）：1000 分差也能开战
        RuleConfig unlimitedCfg = new RuleConfig(false, 0);
        TestDb dbUnlimited = createDb(0);
        seedPlayers(dbUnlimited.raw(), 11L, 12L);
        setRating(dbUnlimited.raw(), 11L, 250_000L);
        setRating(dbUnlimited.raw(), 12L, 150_000L);
        TeamStore storeUnlimited = createParty(dbUnlimited.normal(), 200L, unlimitedCfg, 11L, 12L);
        var reqUnlimited = StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(200L).setBattleConfigId(10)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM).setTargetTeamSize(5)
                .setRequestId(UUID.randomUUID().toString()).build();
        var boundUnlimited = storeUnlimited.forActor(actor(11L), "StartTeamMatch", reqUnlimited);
        var snapUnlimited = boundUnlimited.read(11L, 200L, d);
        PinnedResult okUnlimited = boundUnlimited.commitMatchLock(snapUnlimited, 11L, UUID.randomUUID().toString(),
                List.of(11L, 12L), snapUnlimited.nowMs() + 30_000, SessionLoader.NONE, d, Map.of(11L, 1, 12L, 1), 30, unlimitedCfg);
        assertThat(okUnlimited.code()).isEqualTo(TeamTips.OK);
        assertThat(launchCount(dbUnlimited.raw())).isEqualTo(1L);
    }
}
