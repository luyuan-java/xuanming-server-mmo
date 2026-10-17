package com.game.guild.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetItem;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.discovery.RedisKeys;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.guild.activity.pb.ActivityPoisonRow;
import com.game.guild.activity.pb.ActivityProgressRow;
import com.game.guild.activity.pb.ActivityRewardRow;
import com.game.guild.activity.pb.ActivitySlotRow;
import com.game.guild.activity.pb.TrialBattleRow;
import com.game.guild.activity.pb.TrialLobbyRow;
import com.game.guild.rules.GuildTips;
import com.game.guild.store.AssetOpColumns;
import com.game.guild.store.GuildActivityStore;
import com.game.guild.store.GuildTables;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.guild.GuildTrialLobbyView;
import com.game.proto.guild.RespondGuildTrialInviteRequest;
import com.game.proto.guild.StartGuildTrialRequest;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.table.ConfigTables;
import com.game.table.GuildActivityTable;
import com.game.table.GuildRuleTable;
import com.game.table.RewardTable;
import com.game.table.Rewardreward;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/** 真 TiDB、每例独立库；资产队列只造测试自己的记录，不接触已有游戏数据或修改共享消费位点。 */
class GuildActivityStoreTiDbTest {
    private static final long NOW = Instant.parse("2026-10-16T01:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;
    private static final long GUILD = 11, OTHER_GUILD = 22, PLAYER = 7, PARTNER = 8;
    private static final int LANTERN = 101, REUNION = 102, TRIAL = 103;
    private String base, database;
    private DruidDataSource pool;
    private AuthorityJdbc jdbc;
    private GuildActivityStore store;
    private ConfigTables tables;
    private AtomicLong ids;

    private static Deadline budget() {
        return Deadline.after(30_000);
    }

    @BeforeEach
    void setup() throws Exception {
        base = System.getenv("MAIL_RANK_TEST_JDBC");
        if (base == null || base.isBlank()) {
            if ("1".equals(System.getenv("MAIL_RANK_REQUIRE_TESTS")))
                fail("MAIL_RANK_TEST_JDBC必须配置");
            Assumptions.assumeTrue(false, "未配置隔离TiDB");
        }
        database = "guild_activity_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        pool = new DruidDataSource();
        pool.setUrl(base + database + "?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true");
        pool.setUsername("root");
        pool.setPassword("");
        pool.setMaxActive(16);
        pool.setMaxWait(5000);
        pool.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL));
        pool.init();
        var registry = GuildTables.registry();
        for (var prototype : List.of(ActivityProgressRow.getDefaultInstance(), ActivitySlotRow.getDefaultInstance(),
                     TrialLobbyRow.getDefaultInstance(), TrialBattleRow.getDefaultInstance(), ActivityRewardRow.getDefaultInstance(),
                     ActivityPoisonRow.getDefaultInstance()))
            registry.register(prototype);
        try (var first = pool.getConnection(); var second = pool.getConnection()) {
            StrongTiDbConnectionPolicy.verify(first, true);
            StrongTiDbConnectionPolicy.verify(second, true);
            first.setNetworkTimeout(Runnable::run, 60_000);
            registry.syncAll(first);
        }
        jdbc = new AuthorityJdbc(pool);
        ids = new AtomicLong(10_000);
        tables = mock(ConfigTables.class, RETURNS_DEEP_STUBS);
        var rule = GuildRuleTable.newBuilder()
                           .setId(1)
                           .setActivityJoinMinHours(2)
                           .setReunionMinOnlineMembers(3)
                           .setTrialInviteTtlSeconds(30)
                           .setTrialInviteCooldownSeconds(10)
                           .build();
        when(tables.guildRule().find(1)).thenReturn(Optional.of(rule));
        List<GuildActivityTable> rows = List.of(activity(LANTERN, 1, 1), activity(REUNION, 2, 1), activity(TRIAL, 3, 3));
        when(tables.guildActivity().all()).thenReturn(rows);
        for (var row : rows) when(tables.guildActivity().find(row.getId())).thenReturn(Optional.of(row));
        var reward = RewardTable.newBuilder().setId(1).addReward(Rewardreward.newBuilder().setRewardItem(9).setRewardCount(2)).build();
        when(tables.reward().find(1)).thenReturn(Optional.of(reward));
        store = new GuildActivityStore(jdbc, tables, ids::incrementAndGet);
        seedGuild(GUILD, PLAYER);
        seedGuild(OTHER_GUILD, 17);
        seedMember(GUILD, PLAYER);
        seedMember(GUILD, PARTNER);
    }

    private static GuildActivityTable activity(int id, int type, int dailyLimit) {
        var row = GuildActivityTable.newBuilder()
                          .setId(id)
                          .setType(type)
                          .setName("夹具活动" + id)
                          .setEnabled(true)
                          .setMinGuildLevel(1)
                          .setPersonalContribution(type == 3 ? 15 : 10)
                          .setGuildFunds(type == 2 ? 0 : 50)
                          .setGuildThreshold(3)
                          .setRewardId(1)
                          .setDailyLimit(dailyLimit);
        if (type == 3)
            row.setDungeonId(7).setTeamSizeMin(2).setTeamSizeMax(5);
        return row.build();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (pool != null)
            pool.close();
        if (database != null) {
            assertTrue(database.matches("guild_activity_it_[0-9a-f]{16}"));
            try (var c = DriverManager.getConnection(base, "root", ""); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + database);
            }
        }
    }

    private void exec(String statement, Object... args) throws SQLException {
        jdbc.tx(budget(), s -> {
            s.update(statement, args);
            return null;
        });
    }

    private long count(String statement, Object... args) throws SQLException {
        return jdbc.read(budget(), s -> s.count(statement, args));
    }

    private byte[] bytes(String statement, Object... args) throws SQLException {
        return jdbc.read(budget(), s -> s.one(statement, r -> r.getBytes(1), args));
    }

    private void seedGuild(long guild, long leader) throws SQLException {
        exec("INSERT INTO guild(guild_id,name,name_norm,leader_id,level,announcement,create_time_ms,max_members,zone_id,score,funds)"
                        + " VALUES(?,?,?,?,1,'',?,30,1,0,0)",
                guild, "fixture" + guild, "fixture" + guild, leader, NOW - 30 * DAY);
    }

    private void seedMember(long guild, long player) throws SQLException {
        exec("INSERT INTO guild_member(guild_id,player_id,role,join_time_ms,last_active_ms,contribution_total,contribution_balance)"
                        + " VALUES(?,?,0,?,?,0,0)",
                guild, player, NOW - 30 * DAY, NOW - 30 * DAY);
    }

    private GuildTrialLobbyView start(long at) throws SQLException {
        return store.start(PLAYER,
                StartGuildTrialRequest.newBuilder().setActivityId(TRIAL).addMemberPlayerIds(PLAYER).addMemberPlayerIds(PARTNER).build(), at,
                budget());
    }

    private GuildActivityStore.Launch readyLaunch(long at) throws SQLException {
        var lobby = start(at);
        store.respond(PARTNER, RespondGuildTrialInviteRequest.newBuilder().setLobbyId(lobby.getLobbyId()).setAccept(true).build(), at + 1,
                budget());
        var launch = store.leaseLaunch(lobby.getLobbyId(), at + 2, budget());
        assertNotNull(launch);
        return launch;
    }

    private BattleResultEvent result(GuildActivityStore.Launch launch, long battle, long at) {
        return BattleResultEvent.newBuilder()
                .setBattleId(battle)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setBattleConfigId(7)
                .setOutcomeValue(1)
                .setWinnerTeamIndex(0)
                .setFinishedAtMs(at)
                .setActivityContext(launch.request().getActivityContext())
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(PLAYER).addPlayerIds(PARTNER))
                .build();
    }

    private <T> List<T> parallel(int workers, Callable<T> work) throws Exception {
        var executor = Executors.newFixedThreadPool(workers);
        var gate = new CyclicBarrier(workers);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++)
                futures.add(executor.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return work.call();
                }));
            List<T> answers = new ArrayList<>();
            for (var future : futures) answers.add(future.get(35, TimeUnit.SECONDS));
            return answers;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "夹具工作线程必须退出");
        }
    }

    @Test
    void 并发个人日限只记一次帮贡和可靠奖励且非成员无副作用() throws Exception {
        var answers = parallel(8, () -> {
            try {
                store.participate(PLAYER, GUILD, 1, LANTERN, 0, NOW, budget());
                return 0;
            } catch (GuildActivityStore.Rejected rejected) {
                return rejected.tip;
            }
        });
        assertEquals(1, answers.stream().filter(value -> value == 0).count());
        assertEquals(7, answers.stream().filter(value -> value == GuildTips.ACTIVITY_ALREADY_CLAIMED).count());
        assertEquals(10, count("SELECT contribution_balance FROM guild_member WHERE guild_id=? AND player_id=?", GUILD, PLAYER));
        assertEquals(1,
                count("SELECT used_count FROM guild_daily_counter WHERE player_id=? AND counter_kind=3 AND ref_id=? AND period_key=?",
                        PLAYER, LANTERN, GameDay.dayKey(NOW)));
        assertEquals(1, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        var rejected = assertThrows(GuildActivityStore.Rejected.class, () -> store.participate(99, GUILD, 1, LANTERN, 0, NOW, budget()));
        assertEquals(GuildTips.NOT_IN_GUILD, rejected.tip);
        assertEquals(1, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
    }

    @Test
    void 团圆预检帮会变化不得借用旧帮人数锁存新帮() throws Exception {
        exec("UPDATE guild_member SET guild_id=? WHERE guild_id=? AND player_id=?", OTHER_GUILD, GUILD, PLAYER);
        var rejected =
                assertThrows(GuildActivityStore.Rejected.class, () -> store.participate(PLAYER, GUILD, 2, REUNION, 3, NOW, budget()));
        assertEquals(GuildTips.NOT_IN_GUILD, rejected.tip);
        assertEquals(0, count("SELECT COUNT(*) FROM guild_daily_counter"));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_progress"));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        assertEquals(0, count("SELECT contribution_balance FROM guild_member WHERE player_id=?", PLAYER));
    }

    @Test
    void 重复建房和重复同意返回固定房间且只保存一个开战请求() throws Exception {
        var lobby = start(NOW);
        assertEquals(lobby.getLobbyId(), start(NOW + 1).getLobbyId());
        var accept = RespondGuildTrialInviteRequest.newBuilder().setLobbyId(lobby.getLobbyId()).setAccept(true).build();
        var first = store.respond(PARTNER, accept, NOW + 2, budget());
        var duplicate = store.respond(PARTNER, accept, NOW + 3, budget());
        assertEquals(first, duplicate);
        assertEquals(2, first.getStateValue());
        assertEquals(2, first.getAcceptedPlayerIdsCount());
        assertEquals(1, count("SELECT COUNT(*) FROM guild_trial_lobby"));
        var launch = store.leaseLaunch(lobby.getLobbyId(), NOW + 4, budget());
        assertEquals(lobby.getLobbyId(), launch.request().getRequestId());
        assertEquals(List.of(PLAYER, PARTNER), launch.request().getMemberPlayerIdsList());
    }

    @Test
    void 开战响应未知和内部拒绝保留原请求并可重领恢复同局() throws Exception {
        var first = readyLaunch(NOW);
        byte[] request = first.request().toByteArray();
        store.completeLaunch(first, null, NOW + 10, budget());
        assertArrayEquals(request, bytes("SELECT request_proto FROM guild_trial_lobby WHERE lobby_id=?", first.lobby()));
        var retry = store.leaseLaunch(first.lobby(), NOW + 1011, budget());
        assertNotNull(retry);
        assertEquals(first.request(), retry.request());
        store.completeLaunch(retry, StartActivityBattleResponse.newBuilder().setRejectValue(5).build(), NOW + 1020, budget());
        assertEquals(2, count("SELECT state FROM guild_trial_lobby WHERE lobby_id=?", first.lobby()));
        assertArrayEquals(request, bytes("SELECT request_proto FROM guild_trial_lobby WHERE lobby_id=?", first.lobby()));
        var recovered = store.leaseLaunch(first.lobby(), NOW + 2021, budget());
        assertNotNull(recovered);
        assertEquals(first.request(), recovered.request());
        store.completeLaunch(recovered, StartActivityBattleResponse.newBuilder().setBattleId(9001).build(), NOW + 2030, budget());
        assertEquals(9001, count("SELECT battle_id FROM guild_trial_lobby WHERE lobby_id=?", first.lobby()));
        assertEquals(1, count("SELECT COUNT(*) FROM guild_trial_battle"));
    }

    @Test
    void 成功开战后并发重复战果只发一次帮贡资金和物品意图() throws Exception {
        var launch = readyLaunch(NOW);
        store.completeLaunch(launch, StartActivityBattleResponse.newBuilder().setBattleId(9002).build(), NOW + 3, budget());
        var result = result(launch, 9002, NOW + 1000);
        parallel(4, () -> {
            store.settle(result, NOW + 2000, budget());
            return true;
        });
        assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        assertEquals(30, count("SELECT SUM(contribution_balance) FROM guild_member WHERE guild_id=?", GUILD));
        assertEquals(50, count("SELECT funds FROM guild WHERE guild_id=?", GUILD));
        assertEquals(2, count("SELECT SUM(used_count) FROM guild_daily_counter WHERE counter_kind=3 AND ref_id=?", TRIAL));
        assertEquals(2, count("SELECT state FROM guild_trial_battle WHERE battle_id=9002"));
        assertEquals(2, store.enqueueRewards(NOW + 2001, budget()));
        assertEquals(0, store.enqueueRewards(NOW + 2002, budget()));
        assertEquals(2, count("SELECT COUNT(*) FROM guild_asset_op WHERE kind=3 AND stream=2"));
        assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed WHERE status=1 AND reward_id=op_id"));
    }

    @Test
    void 未确认开战回执不借相同上下文发奖且伪副本名单被拒绝() throws Exception {
        var launch = readyLaunch(NOW);
        var original = result(launch, 9003, NOW + 1000);
        assertThrows(SQLException.class, () -> store.settle(original, NOW + 2000, budget()));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        store.completeLaunch(launch, StartActivityBattleResponse.newBuilder().setBattleId(9003).build(), NOW + 3, budget());
        for (var forged : List.of(original.toBuilder().setBattleConfigId(8).build(), original.toBuilder().setWinnerTeamIndex(1).build(),
                     original.toBuilder().setTeams(0, original.getTeams(0).toBuilder().addPlayerIds(PLAYER)).build(),
                     original.toBuilder().setActivityContext(original.getActivityContext().toBuilder().setGuildId(OTHER_GUILD)).build())) {
            assertThrows(SQLException.class, () -> store.settle(forged, NOW + 2000, budget()));
            assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
            assertEquals(0, count("SELECT SUM(contribution_balance) FROM guild_member"));
        }
        store.settle(original, NOW + 2000, budget());
        assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
    }

    @Test
    void 已超过计数保留期的战果拒绝自动重建日限发奖() throws Exception {
        long old = NOW - 10 * DAY;
        var launch = readyLaunch(old);
        store.completeLaunch(launch, StartActivityBattleResponse.newBuilder().setBattleId(9004).build(), old + 3, budget());
        var result = result(launch, 9004, old + 1000);
        assertThrows(GuildActivityStore.InvalidResult.class, () -> store.settle(result, NOW, budget()));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_daily_counter"));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        assertEquals(0, count("SELECT SUM(contribution_balance) FROM guild_member"));
        assertEquals(0, count("SELECT funds FROM guild WHERE guild_id=?", GUILD));
    }

    @Test
    void 第二份奖励发号失败回滚整局计数资金帮贡和第一份意图() throws Exception {
        var launch = readyLaunch(NOW);
        store.completeLaunch(launch, StartActivityBattleResponse.newBuilder().setBattleId(9010).build(), NOW + 3, budget());
        var calls = new AtomicLong();
        var failing = new GuildActivityStore(jdbc, tables, () -> {
            if (calls.incrementAndGet() == 2)
                throw new IllegalStateException("夹具发号中断");
            return ids.incrementAndGet();
        });
        assertThrows(GuildActivityStore.Rejected.class, () -> failing.settle(result(launch, 9010, NOW + 1000), NOW + 2000, budget()));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        assertEquals(0, count("SELECT COUNT(*) FROM guild_daily_counter"));
        assertEquals(0, count("SELECT SUM(contribution_balance) FROM guild_member"));
        assertEquals(0, count("SELECT funds FROM guild WHERE guild_id=?", GUILD));
        assertEquals(1, count("SELECT state FROM guild_trial_battle WHERE battle_id=9010"));
        store.settle(result(launch, 9010, NOW + 1000), NOW + 2001, budget());
        assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
    }

    @Test
    void 真实Redis战报在SQL开战未确认时保留提交后才按字节删除且重复不再发奖() throws Exception {
        String address = System.getenv("MAIL_RANK_TEST_REDIS");
        if (address == null || address.isBlank()) {
            if ("1".equals(System.getenv("MAIL_RANK_REQUIRE_TESTS")))
                fail("MAIL_RANK_TEST_REDIS必须配置");
            Assumptions.assumeTrue(false, "未配置隔离Redis");
        }
        var namespace = GameNamespace.of("guildtrial" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        var keys = new RedisKeys(namespace);
        var config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress(address).setDatabase(0).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        var redis = Redisson.create(config);
        var meters = new SimpleMeterRegistry();
        var bucket = redis.<byte[]>getBucket(keys.battleActivityResult(9011), ByteArrayCodec.INSTANCE);
        try {
            var launch = readyLaunch(NOW);
            // runtime 使用真实当前时钟；活动日来自该时钟，避免合法战报被保留窗口隔离。
            long current = System.currentTimeMillis();
            var context = launch.request()
                                  .getActivityContext()
                                  .toBuilder()
                                  .setPeriodKey(GameDay.dayKey(current))
                                  .setGuildPeriodKey(GameDay.dayKey(current))
                                  .build();
            var original = launch.request().toBuilder().setActivityContext(context).build();
            exec("UPDATE guild_trial_lobby SET request_proto=? WHERE lobby_id=?", original.toByteArray(), launch.lobby());
            launch = new GuildActivityStore.Launch(launch.lobby(), launch.guild(), launch.token(), original);
            var event = result(launch, 9011, current);
            bucket.set(event.toByteArray());
            var runtime = new GuildActivityRuntime(
                    store, redis, keys, new KafkaNames(namespace), "127.0.0.1:1", 1, false, true, meters, (guild, actor, players) -> {});
            assertThrows(SQLException.class, () -> runtime.settle(event));
            assertArrayEquals(event.toByteArray(), bucket.get());
            assertEquals(0, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
            store.completeLaunch(launch, StartActivityBattleResponse.newBuilder().setBattleId(9011).build(), current, budget());
            runtime.settle(event);
            assertNull(bucket.get());
            assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
            runtime.settle(event);
            assertEquals(2, count("SELECT COUNT(*) FROM guild_activity_reward_owed"));
        } finally {
            bucket.delete();
            redis.shutdown();
            meters.close();
        }
    }

    @Test
    void 换帮补拉不暴露旧帮邀请且奖励拒绝原因可恢复() throws Exception {
        start(NOW);
        exec("UPDATE guild_member SET guild_id=? WHERE guild_id=? AND player_id=?", OTHER_GUILD, GUILD, PLAYER);
        var trial = store.views(PLAYER, NOW + 1, budget()).stream().filter(v -> v.getActivityId() == TRIAL).findFirst().orElseThrow();
        assertFalse(trial.hasTrialLobby());
        var op = GuildAssetOpRow.newBuilder()
                         .setOpId(9910)
                         .setPlayerId(PLAYER)
                         .setGuildId(GUILD)
                         .setStream(2)
                         .setSeq(1)
                         .setStreamEpoch(NOW)
                         .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD)
                         .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                         .setRefId(TRIAL)
                         .setLastReason(27001)
                         .build();
        jdbc.tx(budget(), s -> {
            s.update(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(op));
            return null;
        });
        var pending = store.views(PLAYER, NOW + 2, budget()).stream().filter(v -> v.getActivityId() == TRIAL).findFirst().orElseThrow();
        assertEquals(1, pending.getMyPendingRewardCount());
        assertEquals(27001, pending.getMyPendingReasonTipId());
        exec("UPDATE guild_asset_op SET status=?,reason_tip_id=27000,next_attempt_ms=? WHERE op_id=9910",
                GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED_VALUE, NOW);
        var rejected = store.views(PLAYER, NOW + 3, budget()).stream().filter(v -> v.getActivityId() == TRIAL).findFirst().orElseThrow();
        assertEquals(0, rejected.getMyPendingRewardCount());
        assertEquals(27000, rejected.getMyLastRewardRejectTipId());
    }

    @Test
    void 满窗口奖励保留且不会饿死后续玩家释放窗口可用原标识恢复() throws Exception {
        store.participate(PLAYER, GUILD, 1, LANTERN, 0, NOW, budget());
        long epoch = count("SELECT epoch FROM guild_player_op_seq WHERE player_id=? AND stream=2", PLAYER);
        var payload = AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(9).setCount(1)).build();
        jdbc.tx(budget(), s -> {
            for (int seq = 1; seq <= 16; seq++) {
                var op = GuildAssetOpRow.newBuilder()
                                 .setOpId(200_000 + seq)
                                 .setPlayerId(PLAYER)
                                 .setGuildId(GUILD)
                                 .setStream(2)
                                 .setSeq(seq)
                                 .setStreamEpoch(epoch)
                                 .setKind(GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP)
                                 .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                                 .setPayload(payload.toByteString())
                                 .setCreatedMs(NOW)
                                 .setUpdatedMs(NOW)
                                 .setNextAttemptMs(NOW)
                                 .build();
                s.update(AssetOpColumns.INSERT, AssetOpColumns.insertArgs(op));
            }
            s.update("UPDATE guild_player_op_seq SET next_seq=17 WHERE player_id=? AND stream=2", PLAYER);
            for (int n = 2; n <= 100; n++)
                s.update("INSERT INTO guild_activity_reward_owed"
                                + "(reward_id,player_id,guild_id,activity_id,period_key,payload,status,op_id,created_ms,next_attempt_ms)"
                                + " VALUES(?,?,?,?,?,?,0,0,?,0)",
                        10_000L + n, PLAYER, GUILD, LANTERN, GameDay.dayKey(NOW), payload.toByteArray(), NOW);
            return null;
        });
        ids.set(2_000_000);
        store.participate(PARTNER, GUILD, 1, LANTERN, 0, NOW, budget());
        assertEquals(0, store.enqueueRewards(NOW, budget()));
        assertEquals(1, store.enqueueRewards(NOW, budget()), "前100条退避后，后续其他玩家仍应获入队机会");
        assertEquals(100, count("SELECT COUNT(*) FROM guild_activity_reward_owed WHERE player_id=? AND status=0", PLAYER));
        assertEquals(1, count("SELECT COUNT(*) FROM guild_asset_op WHERE player_id=?", PARTNER));
        exec("UPDATE guild_asset_op SET status=2,durable=1 WHERE player_id=? AND op_id BETWEEN 200001 AND 200016", PLAYER);
        assertEquals(16, store.enqueueRewards(NOW + 1001, budget()));
        assertEquals(1, count("SELECT COUNT(*) FROM guild_asset_op WHERE op_id=10001 AND player_id=?", PLAYER));
        assertEquals(1, count("SELECT COUNT(*) FROM guild_activity_reward_owed WHERE reward_id=10001 AND status=1 AND op_id=10001"));
        assertEquals(0, store.enqueueRewards(NOW + 1001, budget()));
        assertEquals(16, count("SELECT COUNT(*) FROM guild_asset_op WHERE player_id=? AND kind=3", PLAYER));
    }
}
