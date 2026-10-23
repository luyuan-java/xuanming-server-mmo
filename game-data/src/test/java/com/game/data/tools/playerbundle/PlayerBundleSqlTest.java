package com.game.data.tools.playerbundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.common.NamespaceDatabaseCheck;
import com.game.data.testing.DataSqlFixture;
import com.game.discovery.RedisKeys;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.common.GameNamespace;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 导出与导入的端到端（库用 H2，{@code -Dgame.it.mysql} 时连真 MySQL；Redis 用内存替身）：
 * <ul>
 *   <li>导出只读、口令哈希不出库、判读与来源身份写进包；不覆盖已有文件、不许写进 git 工作区；</li>
 *   <li>导入到另一个库后，生产 {@link PlayerStore} 的夺权与加载（login → scene 加载链路的存储层）拿到与线上逐字节相同的玩法数据；</li>
 *   <li>写前闸门：非本机地址、目标就是导出来源、本地同号在线、名字冲突、包被改过都拒绝且不写；</li>
 *   <li>中途失败 fail-closed（占位期间夺权拿不到归属），带 {@code --replace} 重跑收敛。</li>
 * </ul>
 */
class PlayerBundleSqlTest {

    private static final long PLAYER = 900_001L;
    private static final long NOW = 2_000_000_000_000L;
    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("game"));
    private static final String SECRET_HASH = "$argon2id$v=19$m=65536,t=3,p=4$c2VjcmV0c2FsdA$TOPSECRETHASHVALUE";

    private DataSqlFixture source;
    private DataSqlFixture target;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PlayerState state;

    @BeforeEach
    void setUp() throws Exception {
        source = DataSqlFixture.create();
        target = DataSqlFixture.create();
        // DataSqlFixture统一导入player-store唯一最小消费投影，不在此手写第二套DDL。
        state = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.5).setY(-1).setZ(2))
                .setCurrency(CurrencyState.newBuilder().addBalances(12345).addBalances(7)).build();
        try (Connection c = source.dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO account (account, created_at, password_hash) VALUES ('prod_alice', 1, '" + SECRET_HASH + "')");
            st.executeUpdate("""
                    INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, appearance_id, level, scene_config_id,
                                        pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, updated_at)
                    VALUES (900001, 'prod_alice', 3, 'Alice', 'alice', 2, 1, 'look-1', 35, 1001, 1.5, 2.25, -3.125, 7, 1, 0, 1000, 5000)""");
            st.executeUpdate("""
                    INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, to_player, currency_type, currency_delta,
                                                 balance_before, balance_after, zone_id, ingested_at)
                    VALUES (11, 4000, 1, 0, 0, 900001, 0, 100, 12245, 12345, 3, 4001),
                           (12, 6000, 1, 0, 900001, 0, 0, -5, 12345, 12340, 3, 6001)""");
        }
        try (Connection c = source.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (?, ?, 7, 5000)");
             PreparedStatement snap = c.prepareStatement("""
                     INSERT INTO player_snapshot (snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, level, scene_config_id,
                                                  pos_x, pos_y, pos_z, player_state, ingested_at)
                     VALUES (21, 900001, 3000, 2, 3, 6, 34, 1001, 0, 0, 0, ?, 3001)""")) {
            ps.setLong(1, PLAYER);
            ps.setBytes(2, state.toByteArray());
            ps.executeUpdate();
            snap.setBytes(1, new byte[] {1, 2, 3});
            snap.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        source.close();
        target.close();
    }

    // ---------------------------------------------------------------- 替身

    /** 内存 Redis 替身：键 → 原始读数；run_id 可配；删除可设为失败（模拟第二段中断）。 */
    static final class FakeRedis implements PlayerRedis {
        final Map<String, RawEntry> data = new LinkedHashMap<>();
        final String runId;
        final AtomicBoolean failDelete = new AtomicBoolean();
        final List<String> deleted = new ArrayList<>();

        FakeRedis(String runId) {
            this.runId = runId;
        }

        @Override
        public List<RawEntry> read(List<String> keys) {
            return keys.stream().map(k -> data.getOrDefault(k, new RawEntry(k, "none", -2, null, null, null, null))).toList();
        }

        @Override
        public Map<String, String> infoServer() {
            return Map.of("run_id", runId);
        }

        @Override
        public long delete(List<String> keys) {
            if (failDelete.get()) {
                throw new IllegalStateException("模拟 Redis 故障");
            }
            deleted.addAll(keys);
            return keys.stream().filter(k -> data.remove(k) != null).count();
        }

        @Override
        public void close() {
        }
    }

    private static PlayerBundleExport.Connections connections(DataSqlFixture db, FakeRedis redis, String identity, AtomicInteger opened) {
        return new PlayerBundleExport.Connections() {
            @Override
            public Connection mysql(String url) throws SQLException {
                opened.incrementAndGet();
                return db.dataSource.getConnection();
            }

            @Override
            public PlayerRedis redis(String address, int database, String password) {
                opened.incrementAndGet();
                return redis;
            }

            @Override
            public String mysqlIdentity(Connection connection) {
                return identity;
            }
        };
    }

    private static String databaseOf(DataSqlFixture db) throws SQLException {
        try (Connection c = db.dataSource.getConnection()) {
            return NamespaceDatabaseCheck.currentDatabase(c);
        }
    }

    private int export(Path file, FakeRedis redis, String... extra) throws SQLException {
        List<String> argv = new ArrayList<>(List.of("--player", Long.toString(PLAYER), "--operator", "ops_wang", "--reason", "TICKET-1",
                "--out", file.toString(), "--server-revision", "abc1234", "--expect-database", databaseOf(source)));
        argv.addAll(List.of(extra));
        return PlayerBundleExport.run(argv, Map.of(PlayerBundleExport.JDBC_URL_ENV, "jdbc:mysql://10.9.8.7:4000/game?password=pw"),
                Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                connections(source, redis, "server_uuid:source", new AtomicInteger()), () -> NOW);
    }

    private int importBundle(Path file, FakeRedis redis, String identity, AtomicInteger opened, String url, String... extra)
            throws SQLException {
        List<String> argv = new ArrayList<>(List.of("--file", file.toString(), "--local-account", "dev_tester",
                "--expect-database", databaseOf(target)));
        argv.addAll(List.of(extra));
        Map<String, String> env = new HashMap<>();
        env.put(PlayerBundleImport.JDBC_URL_ENV, url);
        return PlayerBundleImport.run(argv, env, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), connections(target, redis, identity, opened), () -> NOW);
    }

    private int importLocal(Path file, FakeRedis redis, String... extra) throws SQLException {
        return importBundle(file, redis, "server_uuid:local", new AtomicInteger(), "jdbc:mysql://127.0.0.1:3306/game", extra);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private FakeRedis sourceRedis() {
        FakeRedis redis = new FakeRedis("prod-run");
        redis.data.put(KEYS.battleLock(PLAYER), new PlayerRedis.RawEntry(KEYS.battleLock(PLAYER), "hash", 50_000, null,
                Map.of("b", "77".getBytes(StandardCharsets.UTF_8), "s", "F".getBytes(StandardCharsets.UTF_8)), null, null));
        return redis;
    }

    private Path exported(Path dir) throws SQLException {
        Path file = dir.resolve("player.json");
        assertThat(export(file, sourceRedis())).as(err()).isEqualTo(0);
        return file;
    }

    // ---------------------------------------------------------------- 导出

    @Test
    void 导出_只读_口令哈希不出库_带判读来源身份操作人与原因(@TempDir Path dir) throws Exception {
        Path file = exported(dir);

        String text = Files.readString(file);
        assertThat(text).doesNotContain("TOPSECRETHASHVALUE").doesNotContain("argon2id").doesNotContain("password=pw");
        JsonNode root = PlayerBundle.json().readTree(text);
        assertThat(root.get("format").asText()).isEqualTo(PlayerBundle.FORMAT);
        assertThat(root.get("playerId").asText()).isEqualTo("900001");
        assertThat(root.get("operator").asText()).isEqualTo("ops_wang");
        assertThat(root.get("reason").asText()).isEqualTo("TICKET-1");
        assertThat(root.at("/sources/mysqlIdentity").asText()).isEqualTo("server_uuid:source");
        assertThat(root.at("/sources/redisIdentity").asText()).isEqualTo("run_id:prod-run");
        assertThat(root.at("/sources/mysqlHost").asText()).isEqualTo("10.9.8.7:4000");
        assertThat(root.at("/mysql/account/hasPasswordHash").asBoolean()).isTrue();
        assertThat(root.at("/mysql/player/ownerEpoch").asText()).isEqualTo("7");
        assertThat(root.at("/mysql/playerState/sha256").asText()).isEqualTo(BundleMysqlReader.sha256(state.toByteArray()));
        assertThat(root.at("/mysql/playerState/decoded/state/currency/balances/0").asText()).isEqualTo("12345");
        assertThat(root.at("/mysql/snapshots/0/causeName").asText()).isEqualTo("SNAPSHOT_LOGOUT");
        assertThat(root.at("/mysql/transactionLog").size()).isEqualTo(2);
        assertThat(root.at("/mysql/transactionLog/0/txId").asText()).as("按时刻倒序").isEqualTo("12");
        assertThat(root.at("/mysql/transactionLogCounts/total").asLong()).isEqualTo(2);
        assertThat(root.at("/mysql/transactionLogCounts/afterStateSave").asLong()).as("6000 晚于 player_state.updated_at=5000").isEqualTo(1);
        assertThat(root.at("/analysis/ownership").asText()).isEqualTo(BundleAnalyzer.RELEASED);
        assertThat(root.at("/analysis/persistence").asText()).contains("最终写回已落库");
        assertThat(root.at("/analysis/warnings").toString()).contains("战斗锁");
        assertThat(root.get("redis").size()).isEqualTo(PlayerRedisKeys.specs(KEYS, PLAYER).size());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RELEASED");

        // 只读：源库没有任何变化
        try (Connection c = source.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner_epoch, owner_released, updated_at FROM player WHERE player_id = 900001")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(7);
            assertThat(rs.getInt(2)).isEqualTo(1);
            assertThat(rs.getLong(3)).isEqualTo(5000);
        }
    }

    @Test
    void 导出_不覆盖已有文件_不写进git工作区_玩家不存在不写文件(@TempDir Path dir) throws Exception {
        Path existing = dir.resolve("old.json");
        Files.writeString(existing, "keep");
        assertThat(export(existing, sourceRedis())).isEqualTo(2);
        assertThat(Files.readString(existing)).isEqualTo("keep");

        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path inRepo = Files.createDirectories(repo.resolve("sub")).resolve("p.json");
        assertThat(export(inRepo, sourceRedis())).isEqualTo(2);
        assertThat(err()).contains("git 工作区");
        assertThat(inRepo).doesNotExist();

        Path missing = dir.resolve("missing.json");
        List<String> argv = List.of("--player", "123", "--operator", "o", "--reason", "r", "--out", missing.toString(),
                "--expect-database", databaseOf(source), "--skip-redis");
        assertThat(PlayerBundleExport.run(argv, Map.of(PlayerBundleExport.JDBC_URL_ENV, "jdbc:h2:mem:x"), Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                connections(source, null, "server_uuid:source", new AtomicInteger()), () -> NOW)).isEqualTo(1);
        assertThat(missing).doesNotExist();
    }

    @Test
    void 导出_缺操作人或原因是参数错误() {
        assertThat(PlayerBundleExport.run(List.of("--player", "1", "--out", "x.json"), Map.of(), Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                PlayerBundleExport.REAL, () -> NOW)).isEqualTo(2);
        assertThat(err()).contains("--operator");
    }

    // ---------------------------------------------------------------- 导入

    @Test
    void 本地replace同锁拒交易锁及坏修订_成功覆盖递增账号修订使旧登录链失效(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).as(err()).isEqualTo(0);
        for (String change : List.of("trade_listing_id=101", "owner_revision=0", "owner_revision=9223372036854775807")) {
            target.jdbc().update("UPDATE player SET trade_listing_id=0,owner_revision=1 WHERE player_id=?", PLAYER);
            target.jdbc().update("UPDATE player SET " + change + " WHERE player_id=?", PLAYER);
            var before = localFinalSaveSnapshot();
            var redis = new FakeRedis("local-run"); err.reset();
            assertThat(importLocal(file, redis, "--execute", "--replace")).as(err()).isEqualTo(2);
            assertThat(err()).contains("交易锁").contains("修订");
            assertThat(redis.deleted).isEmpty();
            assertThat(localFinalSaveSnapshot()).isEqualTo(before);
        }
        target.jdbc().update("UPDATE player SET trade_listing_id=0,owner_revision=1 WHERE player_id=?", PLAYER);
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--replace")).as(err()).isEqualTo(0);
        var store = target.playerStore(() -> NOW);
        assertThat(store.findPlayer(PLAYER).orElseThrow().getOwnerRevision()).isEqualTo(2);
        assertThat(store.claimOwnership(PLAYER, "dev_tester", 1, Duration.ofSeconds(5))).isInstanceOf(PlayerStore.ClaimResult.NotFound.class);
        assertThat(store.claimOwnership(PLAYER, "dev_tester", 2, Duration.ofSeconds(5))).isInstanceOf(PlayerStore.ClaimResult.Claimed.class);
    }
    @Test
    void 未终局交易合同阻止导入_不改角色存档也不清Redis(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).as(err()).isEqualTo(0);
        target.jdbc().update("INSERT INTO trade_commerce VALUES(101,?,0,4)", PLAYER);
        var before = localFinalSaveSnapshot(); var redis = new FakeRedis("local-run"); err.reset();
        assertThat(importLocal(file, redis, "--execute", "--replace")).as(err()).isEqualTo(2);
        assertThat(err()).contains("未终局交易"); assertThat(redis.deleted).isEmpty();
        assertThat(localFinalSaveSnapshot()).isEqualTo(before);
        target.jdbc().update("UPDATE trade_commerce SET status=11"); err.reset();
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--replace")).as(err()).isEqualTo(0);
    }

    @Test
    void 历练ACTIVE待发奖和manual资产各自阻止replace及发布_终态后才放行(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        assertThat(importLocal(file,new FakeRedis("local-run"),"--execute")).as(err()).isEqualTo(0);
        for (String pending : List.of(
                "INSERT INTO team_member_authority VALUES(" + PLAYER + ",101)",
                "INSERT INTO team_launch_role VALUES(" + PLAYER + ",101,1)",
                "INSERT INTO team_invite_authority VALUES(" + PLAYER + ",101,9223372036854775807,1)",
                "INSERT INTO team_application_authority VALUES(" + PLAYER + ",101,9223372036854775807,1)",
                "INSERT INTO guild_trial_launch_role VALUES(" + PLAYER + ",101,1)",
                "INSERT INTO guild_activity_reward_owed VALUES(102," + PLAYER + ",0)",
                "INSERT INTO guild_asset_op VALUES(103," + PLAYER + ",5)",
                "INSERT INTO mail_asset_op VALUES(104," + PLAYER + ",4)",
                "INSERT INTO match_global_ticket VALUES(" + PLAYER + ",2)",
                "INSERT INTO match_global_watching VALUES(" + PLAYER + ",4)",
                "INSERT INTO match_global_challenge VALUES(1," + PLAYER + ",1,9223372036854775807)")) {
            target.jdbc().update(pending);
            var before = localFinalSaveSnapshot(); var redis = new FakeRedis("local-run"); err.reset();
            assertThat(importLocal(file,redis,"--execute","--replace")).as(err()).isEqualTo(2);
            assertThat(err()).contains("历练ACTIVE"); assertThat(redis.deleted).isEmpty();
            assertThat(localFinalSaveSnapshot()).isEqualTo(before);
            target.jdbc().update("UPDATE player SET owner_epoch=9,owner_released=0,owner_lease_until=? WHERE player_id=?",
                    LocalPlayerImporter.IMPORT_HOLD_LEASE,PLAYER);
            before = localFinalSaveSnapshot();
            try (var connection = target.dataSource.getConnection()) {
                assertThat(new LocalPlayerImporter(connection).publish(PLAYER,9,NOW)).isFalse();
            }
            assertThat(localFinalSaveSnapshot()).isEqualTo(before);
            target.jdbc().update("UPDATE player SET owner_released=1,owner_lease_until=0 WHERE player_id=?",PLAYER);
            target.jdbc().update("DELETE FROM guild_trial_launch_role");
            target.jdbc().update("DELETE FROM guild_activity_reward_owed");
            target.jdbc().update("DELETE FROM guild_asset_op");
            target.jdbc().update("DELETE FROM mail_asset_op");
            target.jdbc().update("DELETE FROM match_global_ticket");
            target.jdbc().update("DELETE FROM match_global_challenge");
            target.jdbc().update("DELETE FROM match_global_watching");
            target.jdbc().update("DELETE FROM team_member_authority");
            target.jdbc().update("DELETE FROM team_launch_role");
            target.jdbc().update("DELETE FROM team_invite_authority");
            target.jdbc().update("DELETE FROM team_application_authority");
        }
        assertThat(importLocal(file,new FakeRedis("local-run"),"--execute","--replace")).as(err()).isEqualTo(0);
    }

    @Test
    void 本地发布占位同锁拒交易锁和坏修订_不冒充可在线恢复(@TempDir Path dir) throws Exception {
        assertThat(importLocal(exported(dir), new FakeRedis("local-run"), "--execute")).as(err()).isEqualTo(0);
        target.jdbc().update("UPDATE player SET owner_epoch=9,owner_released=0,owner_lease_until=? WHERE player_id=?",
                LocalPlayerImporter.IMPORT_HOLD_LEASE, PLAYER);
        for (String change : List.of("trade_listing_id=101", "owner_revision=0")) {
            target.jdbc().update("UPDATE player SET trade_listing_id=0,owner_revision=1 WHERE player_id=?", PLAYER);
            target.jdbc().update("UPDATE player SET " + change + " WHERE player_id=?", PLAYER);
            var before = localFinalSaveSnapshot();
            try (var connection = target.dataSource.getConnection()) {
                assertThat(new LocalPlayerImporter(connection).publish(PLAYER, 9, NOW)).isFalse();
            }
            assertThat(localFinalSaveSnapshot()).isEqualTo(before);
        }
    }

    @Test
    void 导入后_生产PlayerStore夺权并加载到逐字节相同的玩法数据(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        FakeRedis local = new FakeRedis("local-run");
        String locationKey = KEYS.playerLocation(PLAYER);
        local.data.put(locationKey, new PlayerRedis.RawEntry(locationKey, "hash", 30_000, null, Map.of(), null, null));

        int code = importLocal(file, local, "--execute");

        assertThat(code).as(err()).isEqualTo(0);
        assertThat(local.deleted).containsExactlyElementsOf(PlayerRedisKeys.importCleanup(KEYS, PLAYER));
        assertThat(local.data).doesNotContainKey(locationKey);
        PlayerStore store = target.playerStore(() -> NOW);
        PlayerRow row = store.findPlayer(PLAYER).orElseThrow();
        assertThat(row.getAccount()).isEqualTo("dev_tester");
        assertThat(row.getName()).isEqualTo("Alice");
        assertThat(row.getLevel()).isEqualTo(35);
        assertThat(row.getPosZ()).isEqualTo(-3.125);
        assertThat(row.getOwnerEpoch()).as("max(线上 7, 本地无) + 1").isEqualTo(8);
        assertThat(store.listPlayers("dev_tester")).extracting(PlayerRow::getPlayerId).containsExactly(PLAYER);
        // login 夺权 → scene 加载：与线上逐字节相同
        assertThat(store.claimOwnership(PLAYER, Duration.ofSeconds(5))).isEqualTo(new PlayerStore.ClaimResult.Claimed(9));
        assertThat(store.loadState(PLAYER).toByteArray()).isEqualTo(state.toByteArray());
    }

    @Test
    void 缺省只预览_不连任何外部系统(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        AtomicInteger opened = new AtomicInteger();

        int code = importBundle(file, new FakeRedis("local-run"), "server_uuid:local", opened, "jdbc:mysql://127.0.0.1:3306/game");

        assertThat(code).isEqualTo(0);
        assertThat(opened.get()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[预览]").contains("127.0.0.1:3306");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).isEmpty();
    }

    @Test
    void 写目标不是本机_拒绝且不连接(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        AtomicInteger opened = new AtomicInteger();

        int code = importBundle(file, new FakeRedis("local-run"), "server_uuid:local", opened, "jdbc:mysql://10.9.8.7:4000/game",
                "--execute");

        assertThat(code).isEqualTo(2);
        assertThat(opened.get()).isZero();
        assertThat(err()).contains("10.9.8.7").contains("不是本机回环地址");
    }

    @Test
    void 目标就是导出来源_库或Redis任一相同都拒绝_不写(@TempDir Path dir) throws Exception {
        Path file = exported(dir);

        assertThat(importBundle(file, new FakeRedis("local-run"), "server_uuid:source", new AtomicInteger(),
                "jdbc:mysql://127.0.0.1:4000/game", "--execute")).isEqualTo(2);
        assertThat(err()).contains("MySQL 与导出来源是同一个实例");

        assertThat(importLocal(file, new FakeRedis("prod-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("Redis 与导出来源是同一个实例");

        assertThat(importBundle(file, new FakeRedis("local-run"), null, new AtomicInteger(), "jdbc:mysql://127.0.0.1:4000/game",
                "--execute")).isEqualTo(2);
        assertThat(err()).contains("取不到实例身份");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).isEmpty();
    }

    @Test
    void 不读Redis的包没有Redis来源身份_拒绝导入_缺字段的包拒绝(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("no-redis.json");
        assertThat(export(file, null, "--skip-redis")).as(err()).isEqualTo(0);

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("没有记录 Redis 的来源实例身份");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).isEmpty();

        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, Files.readString(exported(dir)).replace("\"name\" : \"Alice\",", ""));
        assertThat(importLocal(broken, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("缺必需字段");
    }

    @Test
    void 包被改过_拒绝(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        String text = Files.readString(file);
        String sha = BundleMysqlReader.sha256(state.toByteArray());
        Files.writeString(file, text.replace(sha, "0".repeat(64)));

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("sha256 不符");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).isEmpty();
    }

    @Test
    void 本地同号在线_拒绝_已有存档要replace(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(0);
        PlayerStore store = target.playerStore(() -> NOW);
        assertThat(store.claimOwnership(PLAYER, Duration.ofSeconds(5))).isInstanceOf(PlayerStore.ClaimResult.Claimed.class);

        // 本地 scene 正持有（租约未过期）：即使 --replace 也拒绝
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--replace")).isEqualTo(2);
        assertThat(err()).contains("本地同号玩家在线");

        // 下线（释放）后：不带 --replace 拒绝，带了才覆盖
        try (Connection c = target.dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE player SET owner_released = 1 WHERE player_id = 900001");
        }
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("--replace");
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--replace")).as(err()).isEqualTo(0);
        assertThat(store.findPlayer(PLAYER).orElseThrow().getOwnerEpoch()).as("max(线上 7, 本地 9) + 1").isEqualTo(10);
    }

    @Test
    void 名字与本地别的玩家冲突_拒绝_换名后通过(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        try (Connection c = target.dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, level, created_at, updated_at)
                    VALUES (5, 'dev_other', 3, 'ALICE', 'alice', 1, 1, 1, 1, 1)""");
        }

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("冲突").contains("--name");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).as("拒绝时整笔回滚").isEmpty();

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--name", "Alice_dbg")).as(err()).isEqualTo(0);
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER).orElseThrow().getName()).isEqualTo("Alice_dbg");
    }

    @Test
    void 第二段中断_fail_closed_夺权拿不到归属_带replace重跑收敛(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        FakeRedis broken = new FakeRedis("local-run");
        broken.failDelete.set(true);

        assertThat(importLocal(file, broken, "--execute")).isEqualTo(1);
        assertThat(err()).contains("第 2 步").contains("--replace");
        PlayerStore store = target.playerStore(() -> NOW);
        assertThat(store.claimOwnership(PLAYER, Duration.ofSeconds(5))).as("占位租约永不过期：登录拿不到归属")
                .isInstanceOf(PlayerStore.ClaimResult.Held.class);
        try (Connection c = target.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner_released, owner_lease_until FROM player WHERE player_id = 900001")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
            assertThat(rs.getLong(2)).isEqualTo(LocalPlayerImporter.IMPORT_HOLD_LEASE);
        }

        // 不带 --replace 重跑：认出占位，提示加 --replace
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).isEqualTo(2);
        assertThat(err()).contains("导入中断留下的占位");

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute", "--replace")).as(err()).isEqualTo(0);
        assertThat(store.claimOwnership(PLAYER, Duration.ofSeconds(5))).isInstanceOf(PlayerStore.ClaimResult.Claimed.class);
        assertThat(store.loadState(PLAYER).toByteArray()).isEqualTo(state.toByteArray());
    }

    @Test
    void 直接构造null存档导入计划_首个数据库动作前拒绝() {
        ImportPlan plan = new ImportPlan(PLAYER, "dev_absent", 1, "缺失", "缺失", 1, 1, "", 1, 1,
                0, 0, 0, 1, null, 0, List.of());
        assertThatThrownBy(() -> new LocalPlayerImporter(null).stage(plan, false, NOW))
                .isInstanceOf(LocalPlayerImporter.Refused.class).hasMessageContaining("缺少 player_state");
    }

    @Test
    void 明确存在的空protobuf初始存档_仍可正式导入(@TempDir Path dir) throws Exception {
        source.jdbc().update("UPDATE player_state SET data=? WHERE player_id=?", new byte[0], PLAYER);
        Path file = exported(dir);
        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).as(err()).isEqualTo(0);
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM player_state WHERE player_id=?", Long.class, PLAYER))
                .isEqualTo(1L);
        assertThat(target.playerStore(() -> NOW).loadState(PLAYER).toByteArray()).isEmpty();
    }

    @Test
    void 来源缺失玩法数据_拒绝导入且本地原件不变(@TempDir Path dir) throws Exception {
        try (Connection c = source.dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM player_state WHERE player_id = 900001");
        }
        Path file = exported(dir);
        try (Connection c = target.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (900001, ?, 1, 1)")) {
            ps.setBytes(1, state.toByteArray());
            ps.executeUpdate();
        }

        assertThat(importLocal(file, new FakeRedis("local-run"), "--execute")).as(err()).isEqualTo(2);
        assertThat(err()).contains("来源缺少 player_state");
        assertThat(target.playerStore(() -> NOW).findPlayer(PLAYER)).isEmpty();
        assertThat(target.playerStore(() -> NOW).loadState(PLAYER).toByteArray()).isEqualTo(state.toByteArray());
    }

    @Test
    void 过期旧纪元已有最终待办_replace拒绝且三表不变(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        preparePendingLocal();
        Map<String, List<Map<String, Object>>> before = localFinalSaveSnapshot();
        FakeRedis redis = new FakeRedis("local-run");

        assertThat(importLocal(file, redis, "--execute", "--replace")).as(err()).isEqualTo(2);
        assertThat(err()).contains("最终存盘");
        assertThat(redis.deleted).as("拒绝发生在Redis清理前").isEmpty();
        assertThat(localFinalSaveSnapshot()).isEqualTo(before);
    }

    @Test
    void 发布占位遇异代最终待办_拒绝且三表不变() throws Exception {
        preparePendingLocal();
        // 模拟先前旧导入器或人工操作留下的新纪元占位，旧待办仍必须阻断发布。
        target.jdbc().update("UPDATE player SET owner_epoch = 6, owner_released = 0, owner_lease_until = ? WHERE player_id = ?",
                LocalPlayerImporter.IMPORT_HOLD_LEASE, PLAYER);
        Map<String, List<Map<String, Object>>> before = localFinalSaveSnapshot();
        try (Connection connection = target.dataSource.getConnection()) {
            assertThat(new LocalPlayerImporter(connection).publish(PLAYER, 6, NOW)).isFalse();
        }
        assertThat(localFinalSaveSnapshot()).isEqualTo(before);
    }

    @Test
    void 最终意图损坏未知及非精确终态_replace全部拒绝且不读大快照(@TempDir Path dir) throws Exception {
        Path file = exported(dir);
        preparePendingLocal();
        // 正式schema不允许NULL；故障夹具显式模拟外部损坏，验证SQL三值逻辑仍fail-closed。
        target.jdbc().execute(DataSqlFixture.mysql()
                ? "ALTER TABLE player_final_save MODIFY COLUMN status VARCHAR(16) NULL"
                : "ALTER TABLE player_final_save ALTER COLUMN status DROP NOT NULL");
        for (String status : java.util.Arrays.asList("PENDING", "BROKEN", "open", "OPEN ", "applied", "APPLIED ", null)) {
            target.jdbc().update("UPDATE player_final_save SET status = ?, data = ? WHERE player_id = ?",
                    status, new byte[] {(byte) 0xff}, PLAYER);
            Map<String, List<Map<String, Object>>> before = localFinalSaveSnapshot();
            err.reset();
            FakeRedis redis = new FakeRedis("local-run");
            assertThat(importLocal(file, redis, "--execute", "--replace")).as("status=%s: %s", status, err()).isEqualTo(2);
            assertThat(err()).contains("最终存盘");
            assertThat(redis.deleted).isEmpty();
            assertThat(localFinalSaveSnapshot()).as("status=%s", status).isEqualTo(before);
        }
    }

    private void preparePendingLocal() {
        target.insertPlayer(PLAYER, 3, 11, 1001, 5, false, NOW - 30_000, 1000, 5000);
        target.putState(PLAYER, state.toBuilder().setCurrency(CurrencyState.newBuilder().addBalances(321)).build().toByteArray(), 5, 5000);
        PlayerStore store = target.playerStore(() -> NOW - 60_000);
        String instance = "00000000-0000-0000-0000-000000000033";
        assertThat(store.loadForScene(PLAYER, 5, instance, Duration.ofSeconds(5))).isPresent();
        PlayerRow finalRow = store.findPlayer(PLAYER).orElseThrow();
        finalRow.setLevel(73);
        finalRow.setPosX(42);
        byte[] finalBytes = state.toBuilder().setCurrency(CurrencyState.newBuilder().addBalances(999)).build().toByteArray();
        assertThat(store.registerFinalSave(finalRow, finalBytes, instance, Duration.ofSeconds(5)).status())
                .isEqualTo(PlayerStore.FinalSaveStatus.ACCEPTED);
    }

    private Map<String, List<Map<String, Object>>> localFinalSaveSnapshot() throws SQLException {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("player", "player_state", "player_final_save")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : target.jdbc().queryForList("SELECT * FROM " + table + " WHERE player_id = ?", PLAYER)) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof java.sql.Blob blob) {
                        value = blob.getBytes(1, Math.toIntExact(blob.length()));
                    }
                    if (value instanceof byte[] bytes) {
                        value = java.util.HexFormat.of().formatHex(bytes);
                    }
                    normalized.put(entry.getKey(), value);
                }
                rows.add(normalized);
            }
            snapshot.put(table, rows);
        }
        return snapshot;
    }
}
