package com.game.guild;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.player.PlayerProfiles;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.guild.asset.GuildAssetRuntime;
import com.game.guild.cache.ApplyPushCooldown;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.dispatch.GuildDispatcher;
import com.game.guild.dispatch.GuildMethods;
import com.game.guild.dispatch.GuildWorkerPool;
import com.game.guild.id.GuildIds;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.push.GuildPushes;
import com.game.guild.rank.GuildRanks;
import com.game.guild.rules.GuildTip;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.GuildAccess;
import com.game.guild.service.GuildEconomyService;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.guild.service.GuildTableLookup;
import com.game.guild.service.GuildViews;
import com.game.guild.store.EconomyStore;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildTx;
import com.game.pbmysql.PbMysql;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.ApplyJoinGuildResponse;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildRankByGuildRequest;
import com.game.proto.guild.GetGuildRankByGuildResponse;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRankResponse;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.KickGuildMemberResponse;
import com.game.proto.guild.ListGuildApplicationsResponse;
import com.game.proto.guild.ListMyGuildApplicationsResponse;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.ReviewGuildApplicationResponse;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.SetGuildMemberRoleResponse;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.game.proto.guild.TransferGuildLeaderResponse;
import com.game.table.ConfigTables;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 真 MySQL + 真 Redis 的端到端：按 {@link GuildConfiguration} 的装配顺序（直接调用它的 bean 方法，不起 Spring / Dubbo）搭出整个帮会服务，
 * 经 {@link GuildDispatcher} 跑一遍建帮 → 申请 → 待审 → 通过 → 任命 → 转让 → 踢人 → 解散，以及 8 / 1006 / 14012 的准入（guild-spec §3、§11.3、§11.4）。
 * 玩家号取 ≥ 2^63 的随机区间（无符号全流程）。
 *
 * <p>缺省跳过：要同时给 {@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}（口令取 XM_MYSQL_PASSWORD）与 {@code -Dxm.it.redis=redis://127.0.0.1:6379}。
 * MySQL 用一次性库 {@code xm_guild_it_e2e_<随机>}（含一张最小的 player 表），用完 DROP；Redis 用 DB 10，只删自己写过的键（启动期的排行重建会
 * 用这个空库重写 DB 10 的全局榜键，同模块其它排行测试本来就在用例前后清它们）。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GuildEndToEndIntegrationTest {

    private static final String MYSQL = System.getProperty("xm.it.mysql");
    private static final String USER = System.getProperty("xm.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1";
    private static final int ZONE = 1;
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();

    private final long base = Long.MIN_VALUE + ThreadLocalRandom.current().nextLong(1L << 40) * 16;
    private final long a = base + 1;
    private final long b = base + 2;
    private final long c = base + 3;

    private String database;
    private DruidDataSource dataSource;
    private RedissonClient redis;
    private ScheduledExecutorService leaseScheduler;
    private ScheduledExecutorService background;
    private NodeIdLease lease;
    private GuildWorkerPool pool;
    private GuildDispatcher dispatcher;
    private final List<Long> guildIds = new ArrayList<>();
    /** 资产通道关闭的运行件（不起循环与清理）。 */
    private static final GuildAssetRuntime CHANNEL_OFF = new GuildAssetRuntime(null, null, java.time.Duration.ZERO);

    @BeforeEach
    void setUp() throws Exception {
        database = "xm_guild_it_e2e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection conn = DriverManager.getConnection(MYSQL + "/" + PARAMS, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        }
        dataSource = new DruidDataSource();
        dataSource.setUrl(MYSQL + "/" + database + PARAMS);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaxActive(16);
        dataSource.setMaxWait(10_000);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE player (player_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL,"
                    + " level INT UNSIGNED NOT NULL DEFAULT 1, class_id INT UNSIGNED NOT NULL DEFAULT 0,"
                    + " gender INT UNSIGNED NOT NULL DEFAULT 0, appearance_id VARCHAR(64) NOT NULL DEFAULT '',"
                    + " zone_id INT UNSIGNED NOT NULL) ENGINE=InnoDB");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player (player_id, name, zone_id) VALUES (?, ?, ?)")) {
                insertPlayer(ps, a, "甲", ZONE);
                insertPlayer(ps, b, "乙", ZONE);
                insertPlayer(ps, c, "丙", 0);
            }
        }
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(10);
        redis = Redisson.create(config);

        // ---- 照 GuildConfiguration 的顺序装配 ----
        GuildConfiguration wiring = new GuildConfiguration();
        GuildProperties props = new GuildProperties(null, null, null, null, null, 4, 64, null);
        GuildMetrics metrics = wiring.guildMetrics(new SimpleMeterRegistry());
        ConfigTables tables = wiring.guildConfigTables(tableDir());
        PbMysql synced = wiring.guildTables(dataSource, tables);
        GuildTx tx = wiring.guildTx(dataSource, props, metrics);
        GuildConfiguration.SchemaReady ready = wiring.guildSchemaReady(tx, synced);
        assertThat(ready.serverVersion()).isNotBlank();
        GuildStore store = wiring.guildStore(tx, ready);
        leaseScheduler = wiring.guildLeaseScheduler();
        lease = wiring.guildIdLease(redis, leaseScheduler, ready);
        GuildIds ids = wiring.guildIds(lease);
        background = wiring.guildBackgroundScheduler();
        GuildCache.CacheRedis cacheRedis = wiring.guildCacheRedis(redis);
        GuildCacheInvalidator invalidator = wiring.guildCacheInvalidator(cacheRedis, props, background, metrics);
        GuildCache cache = wiring.guildCache(cacheRedis, props, store, invalidator, metrics);
        GuildRanks ranks = wiring.guildRanks(redis, background, metrics, store, lease, CHANNEL_OFF);
        ApplyPushCooldown cooldown = wiring.guildApplyPushCooldown(redis);
        PlayerPresenceDirectory presence = wiring.playerPresenceDirectory(redis);
        PlayerPushes playerPushes = wiring.playerPushes(redis, presence);
        PlayerProfiles profiles = wiring.playerProfiles(dataSource, props);
        GuildPushes pushes = wiring.guildPushes(playerPushes, metrics, props, REGISTRY);
        GuildTableLookup lookup = wiring.guildTableLookup(tables);
        GuildAccess access = wiring.guildAccess(cache, profiles, invalidator);
        GuildViews views = wiring.guildViews(presence, profiles, metrics, props, lookup, store);
        GuildService guilds = wiring.guildService(store, cache, access, views, ranks, pushes, ids, lookup);
        GuildManageService manage = wiring.guildManageService(store, cache, access, views, pushes, cooldown, lookup);
        GuildRankService rankService = wiring.guildRankService(ranks, cache, access, views);
        pool = wiring.guildWorkerPool(props, dataSource);
        // 4.5：资产通道关闭（props 缺省），经济服务照常装配（升级与两个读页可用，捐献 / 兑换回 14026）
        EconomyStore economyStore = wiring.economyStore(tx, ready);
        GuildEconomyService economy = wiring.guildEconomyService(economyStore, cache, access, views,
                wiring.economyTables(tables), pushes, metrics, ids, CHANNEL_OFF, props, pool);
        dispatcher = wiring.guildDispatcher(REGISTRY, guilds, manage, rankService, economy, pool, metrics, props);
    }

    private static void insertPlayer(PreparedStatement ps, long id, String name, int zone) throws Exception {
        ps.setObject(1, new java.math.BigInteger(Long.toUnsignedString(id)));
        ps.setString(2, name);
        ps.setInt(3, zone);
        ps.executeUpdate();
    }

    private static String tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? "../config-data/tables" : "config-data/tables";
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pool != null) {
            pool.close();
        }
        if (background != null) {
            background.shutdownNow();
        }
        if (lease != null) {
            int node = lease.nodeId();
            lease.close();
            redis.getKeys().delete(RedisKeys.nodeIdEpoch(NodeTypes.GUILD, 0, node));
        }
        if (leaseScheduler != null) {
            leaseScheduler.shutdownNow();
        }
        if (redis != null) {
            List<String> keys = new ArrayList<>();
            for (long id : guildIds) {
                keys.add(RedisKeys.guildSnapshot(id));
                keys.add(RedisKeys.cacheGeneration(RedisKeys.guildSnapshot(id)));
                for (long p : List.of(a, b, c)) {
                    keys.add(RedisKeys.guildApplyPush(id, p));
                }
            }
            for (long p : List.of(a, b, c)) {
                keys.add(RedisKeys.guildOfPlayer(p));
                keys.add(RedisKeys.cacheGeneration(RedisKeys.guildOfPlayer(p)));
            }
            keys.add(RedisKeys.guildRankAll());
            keys.add(RedisKeys.guildRankZone(ZONE));
            keys.add(RedisKeys.guildRankZones());
            redis.getKeys().delete(keys.toArray(String[]::new));
            redis.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        try (Connection conn = DriverManager.getConnection(MYSQL + "/" + PARAMS, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private ClientReply send(long player, String method, Message request) {
        ClientCall call = ClientCall.newBuilder().setMessageId(REGISTRY.requireId(GuildMethods.SERVICE, method))
                .setBody(request == null ? ByteString.EMPTY : request.toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(3).setPlayerId(player)).build();
        return dispatcher.dispatch(call).join();
    }

    private <T extends Message> T call(long player, String method, Message request, com.google.protobuf.Parser<T> parser)
            throws Exception {
        ClientReply reply = send(player, method, request);
        assertThat(reply.getTipId()).as(method + " 不该回信封").isZero();
        return parser.parseFrom(reply.getBody());
    }

    @Test
    void 建帮到解散的完整流程() throws Exception {
        String name = "青云门" + Long.toHexString(base & 0xFFFFF);
        CreateGuildResponse created = call(a, GuildMethods.CREATE_GUILD,
                CreateGuildRequest.newBuilder().setName(" " + name + " ").setZoneId(99).build(), CreateGuildResponse.parser());
        assertThat(created.hasErrorMessage()).isFalse();
        GuildInfo info = created.getGuild();
        long g = info.getGuildId();
        guildIds.add(g);
        assertThat(info.getName()).isEqualTo(name);
        assertThat(info.getZoneId()).isEqualTo(ZONE);
        assertThat(info.getLeaderId()).isEqualTo(a);
        assertThat(info.getLeaderName()).isEqualTo("甲");
        assertThat(info.getMaxOfficers()).isEqualTo(2);
        assertThat(info.getMembersList()).singleElement().satisfies(m -> assertThat(m.getRole()).isEqualTo(3));

        // 二次建帮 14000；同名（全角 / 大写也算）14010
        assertThat(call(a, GuildMethods.CREATE_GUILD, CreateGuildRequest.newBuilder().setName("另一个").build(),
                CreateGuildResponse.parser()).getErrorMessage()).isEqualTo(GuildTip.ALREADY_IN_GUILD.proto());
        assertThat(call(b, GuildMethods.CREATE_GUILD, CreateGuildRequest.newBuilder().setName(name).build(),
                CreateGuildResponse.parser()).getErrorMessage()).isEqualTo(GuildTip.NAME_TAKEN.proto());
        // 归属区未知的人什么写都做不了
        assertThat(call(c, GuildMethods.CREATE_GUILD, CreateGuildRequest.newBuilder().setName("丙帮").build(),
                CreateGuildResponse.parser()).getErrorMessage()).isEqualTo(GuildTip.HOME_ZONE_UNKNOWN.proto());

        // 本区榜：新帮 0 分入榜，帮主名补上；页长 60 回显 50
        GetGuildRankResponse rank = call(b, GuildMethods.GET_GUILD_RANK,
                GetGuildRankRequest.newBuilder().setPage(1).setPageSize(60).build(), GetGuildRankResponse.parser());
        assertThat(rank.getPageSize()).isEqualTo(50);
        assertThat(rank.getEntriesList()).anySatisfy(e -> {
            assertThat(e.getGuildId()).isEqualTo(g);
            assertThat(e.getLeaderName()).isEqualTo("甲");
            assertThat(e.getMemberCount()).isEqualTo(1);
        });

        // 申请 → 本人申请列表 → 帮主看到待审数与名单
        assertThat(call(b, GuildMethods.APPLY_JOIN_GUILD, ApplyJoinGuildRequest.newBuilder().setGuildId(g).build(),
                ApplyJoinGuildResponse.parser()).hasErrorMessage()).isFalse();
        ListMyGuildApplicationsResponse mine = call(b, GuildMethods.LIST_MY_GUILD_APPLICATIONS, null,
                ListMyGuildApplicationsResponse.parser());
        assertThat(mine.getApplicationsList()).singleElement().satisfies(v -> {
            assertThat(v.getGuildId()).isEqualTo(g);
            assertThat(v.getLeaderName()).isEqualTo("甲");
        });
        assertThat(call(a, GuildMethods.GET_PLAYER_GUILD, null, GetPlayerGuildResponse.parser()).getGuild()
                .getPendingApplicationCount()).isEqualTo(1);
        ListGuildApplicationsResponse applicants = call(a, GuildMethods.LIST_GUILD_APPLICATIONS, null,
                ListGuildApplicationsResponse.parser());
        assertThat(applicants.getApplicantsList()).singleElement().satisfies(v -> {
            assertThat(v.getPlayerId()).isEqualTo(b);
            assertThat(v.getName()).isEqualTo("乙");
            assertThat(v.getOnline()).isFalse();
        });

        // 通过：乙入帮；他的 GetPlayerGuild 立刻看到（映射已失效）
        ReviewGuildApplicationResponse approved = call(a, GuildMethods.REVIEW_GUILD_APPLICATION,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(b).setApprove(true).build(),
                ReviewGuildApplicationResponse.parser());
        assertThat(approved.getGuild().getMembersList()).extracting(m -> m.getPlayerId()).containsExactlyInAnyOrder(a, b);
        assertThat(call(b, GuildMethods.GET_PLAYER_GUILD, null, GetPlayerGuildResponse.parser()).getGuild().getGuildId())
                .isEqualTo(g);
        // 审批已删的申请再审一次 14018
        assertThat(call(a, GuildMethods.REVIEW_GUILD_APPLICATION,
                ReviewGuildApplicationRequest.newBuilder().setApplicantPlayerId(b).setApprove(true).build(),
                ReviewGuildApplicationResponse.parser()).getErrorMessage()).isEqualTo(GuildTip.APPLICATION_NOT_FOUND.proto());

        // 任命长老 → 转让 → 新帮主踢掉原帮主
        SetGuildMemberRoleResponse role = call(a, GuildMethods.SET_GUILD_MEMBER_ROLE,
                SetGuildMemberRoleRequest.newBuilder().setTargetPlayerId(b).setRole(1).build(),
                SetGuildMemberRoleResponse.parser());
        assertThat(role.getGuild().getOfficerCount()).isEqualTo(1);
        TransferGuildLeaderResponse transferred = call(a, GuildMethods.TRANSFER_GUILD_LEADER,
                TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(b).build(), TransferGuildLeaderResponse.parser());
        assertThat(transferred.getGuild().getLeaderId()).isEqualTo(b);
        assertThat(transferred.getGuild().getLeaderName()).isEqualTo("乙");
        assertThat(call(a, GuildMethods.TRANSFER_GUILD_LEADER,
                TransferGuildLeaderRequest.newBuilder().setTargetPlayerId(b).build(), TransferGuildLeaderResponse.parser())
                .getErrorMessage()).isEqualTo(GuildTip.RANK_TOO_LOW.proto());
        KickGuildMemberResponse kicked = call(b, GuildMethods.KICK_GUILD_MEMBER,
                KickGuildMemberRequest.newBuilder().setTargetPlayerId(a).build(), KickGuildMemberResponse.parser());
        assertThat(kicked.getGuild().getMembersList()).extracting(m -> m.getPlayerId()).containsExactly(b);
        assertThat(call(a, GuildMethods.GET_PLAYER_GUILD, null, GetPlayerGuildResponse.parser()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());

        // 上行 8 → 信封 1003；4.5 的捐献在通道关闭时 → in-band 14026；4.6 的号 → in-band 1006
        assertThat(send(b, GuildMethods.UPDATE_GUILD_SCORE, null).getTipId()).isEqualTo(GuildTips.SERVICE_UNAVAILABLE);
        assertThat(call(b, GuildMethods.DONATE_TO_GUILD, null, DonateToGuildResponse.parser()).getErrorMessage())
                .isEqualTo(GuildTip.ASSET_CHANNEL_DISABLED.proto());
        assertThat(call(b, GuildMethods.GET_GUILD_ACTIVITIES, null, com.game.proto.guild.GetGuildActivitiesResponse.parser())
                .getErrorMessage()).isEqualTo(GuildTip.FEATURE_UNAVAILABLE.proto());

        // 解散：清榜，之后查不到名次、也不在任何帮
        assertThat(call(b, GuildMethods.DISBAND_GUILD, null, DisbandGuildResponse.parser()).hasErrorMessage()).isFalse();
        assertThat(call(b, GuildMethods.GET_PLAYER_GUILD, null, GetPlayerGuildResponse.parser()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        assertThat(call(b, GuildMethods.GET_GUILD_RANK_BY_GUILD, GetGuildRankByGuildRequest.newBuilder().setGuildId(g).build(),
                GetGuildRankByGuildResponse.parser()).getErrorMessage()).isEqualTo(GuildTip.NOT_RANKED.proto());
        assertThat(call(b, GuildMethods.DISBAND_GUILD, null, DisbandGuildResponse.parser()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
    }

    @Test
    void 启动期重建排行_把MySQL里已有的帮会放回榜上() throws Exception {
        CreateGuildResponse created = call(a, GuildMethods.CREATE_GUILD, CreateGuildRequest.newBuilder()
                .setName("重建" + Long.toHexString(base & 0xFFFFF)).build(), CreateGuildResponse.parser());
        long g = created.getGuild().getGuildId();
        guildIds.add(g);
        // 榜键被清掉（模拟 Redis 丢数据），再按装配跑一次重建
        redis.getKeys().delete(RedisKeys.guildRankAll(), RedisKeys.guildRankZone(ZONE), RedisKeys.guildRankZones());
        GuildConfiguration wiring = new GuildConfiguration();
        GuildProperties props = new GuildProperties(null, null, null, null, null, null, null, null);
        GuildMetrics metrics = wiring.guildMetrics(new SimpleMeterRegistry());
        GuildTx tx = wiring.guildTx(dataSource, props, metrics);
        GuildStore store = wiring.guildStore(tx, new GuildConfiguration.SchemaReady("test"));
        wiring.guildRanks(redis, background, metrics, store, lease, CHANNEL_OFF);
        assertThat(redis.getScoredSortedSet(RedisKeys.guildRankZone(ZONE), org.redisson.client.codec.StringCodec.INSTANCE)
                .contains(Long.toUnsignedString(g))).isTrue();
        GetGuildRankByGuildResponse byGuild = call(a, GuildMethods.GET_GUILD_RANK_BY_GUILD,
                GetGuildRankByGuildRequest.newBuilder().setGuildId(g).build(), GetGuildRankByGuildResponse.parser());
        assertThat(byGuild.getEntry().getRank()).isEqualTo(1);
        assertThat(byGuild.getEntry().getLeaderName()).isEqualTo("甲");
    }
}
