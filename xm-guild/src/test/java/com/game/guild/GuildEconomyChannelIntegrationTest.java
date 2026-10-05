package com.game.guild;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.DubboGroups;
import com.game.api.SceneAssetOpService;
import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.ListAppliedAssetOpsSinceRequest;
import com.game.api.proto.ListAppliedAssetOpsSinceResponse;
import com.game.api.proto.ListAppliedResult;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerProfiles;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.discovery.proto.PlayerLocation;
import com.game.guild.asset.AssetOpLoop;
import com.game.guild.asset.GuildAssetRuntime;
import com.game.guild.asset.GuildAssetStore;
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
import com.game.guild.service.EconomyAssetEffects;
import com.game.guild.service.EconomyTables;
import com.game.guild.service.GuildAccess;
import com.game.guild.service.GuildEconomyService;
import com.game.guild.service.GuildInternalQueries;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.guild.service.GuildTableLookup;
import com.game.guild.service.GuildViews;
import com.game.guild.store.BackgroundTx;
import com.game.guild.store.EconomyStore;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildTx;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.pbmysql.PbMysql;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.guild.BuyGuildShopGoodsRequest;
import com.game.proto.guild.BuyGuildShopGoodsResponse;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.DonateToGuildRequest;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildDonateOptionsResponse;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.UpgradeGuildRequest;
import com.game.proto.guild.UpgradeGuildResponse;
import com.game.table.ConfigTables;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 资产通道的跨进程集成（guild-economy-spec §11.5 能在单模块里做的部分）：真 MySQL + 真 Redis + 一个进程内的假 scene 资产提供方（真 Dubbo Triple，
 * 自己的 Dubbo 框架模型 = 另一个进程；调用方 MAC 与请求体 HMAC 两层鉴权照常生效）。按 {@link GuildConfiguration} 的装配（资产通道开启）经
 * {@link GuildDispatcher} 跑：捐献同步投递（未 durable → 重查到 durable → 终结、资金帮贡入账）、余额不足 durable 拒绝（14025、次数退回）、兑换发放、
 * 升级资金不足带 GuildInfo、离线（位置登出）→ PENDING → 重投循环离线读已落盘账本（player_state）终结、内部查询看得到已应用的行。
 *
 * <p>缺省跳过：要同时给 {@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}（口令取 XM_MYSQL_PASSWORD）与 {@code -Dxm.it.redis=redis://127.0.0.1:6379}；
 * 签名密钥与 Dubbo 调用方密钥由 surefire 注入的测试值。MySQL 用一次性库 {@code xm_guild_it_econ_<随机>}（含最小的 player 与 player_state 表），用完 DROP；
 * Redis 用 DB 10，zone 取随机值，只删自己写过的键。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GuildEconomyChannelIntegrationTest {

    private static final String MYSQL = System.getProperty("xm.it.mysql");
    private static final String USER = System.getProperty("xm.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1";
    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int SCENE_NODE = 7;
    private static final long OWNER_EPOCH = 5;

    private final int zone = 5_000 + ThreadLocalRandom.current().nextInt(1_000);
    private final long a = Long.MIN_VALUE + ThreadLocalRandom.current().nextLong(1L << 40) * 16 + 1;
    private final String secret = System.getenv(AssetOpSignatures.SECRET_ENV_GUILD);
    private final FakeScene scene = new FakeScene();

    private String database;
    private DruidDataSource dataSource;
    private RedissonClient redis;
    private ScheduledExecutorService leaseScheduler;
    private ScheduledExecutorService background;
    private ScheduledExecutorService timer;
    private NodeIdLease lease;
    private GuildWorkerPool pool;
    private GuildWorkerPool settlePool;
    private GuildWorkerPool workerPool;
    private SceneAssetOpClients clients;
    private IsolatedDubboModule sceneServer;
    private GuildAssetRuntime runtime;
    private GuildDispatcher dispatcher;
    private GuildAssetStore assetStore;
    private GuildInternalQueries internal;
    private SimpleMeterRegistry meters;
    private NodeDirectory<SceneNodeInfo> scenes;
    private PlayerLocationDirectory locations;
    private final List<Long> guildIds = new ArrayList<>();

    /** 假 scene：验签（caller = guild、rpc 进规范串）、按 (流, 纪元, seq) 记账；同一 seq 第一次答复不 durable，之后只读答复且 durable。 */
    final class FakeScene implements SceneAssetOpService {
        final Map<String, AssetOpResponse> ledger = new ConcurrentHashMap<>();
        final Map<Integer, Long> balances = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger badSignatures = new AtomicInteger();

        @Override
        public CompletableFuture<AssetOpResponse> debit(AssetOpRequest request) {
            return CompletableFuture.completedFuture(handle(AssetRpc.DEBIT, request));
        }

        @Override
        public CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request) {
            return CompletableFuture.completedFuture(handle(AssetRpc.ABORT_DEBIT, request));
        }

        @Override
        public CompletableFuture<AssetOpResponse> credit(AssetOpRequest request) {
            // 异步完成（别的线程），Dubbo 在 future 完成时才回写
            return CompletableFuture.supplyAsync(() -> handle(AssetRpc.CREDIT, request),
                    CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS));
        }

        private synchronized AssetOpResponse handle(AssetRpc rpc, AssetOpRequest request) {
            calls.incrementAndGet();
            if (!AssetOpSignatures.CALLER_GUILD.equals(request.getAuth().getCaller())
                    || !AssetOpSignatures.signatureMatches(rpc.wireName(), request, secret)) {
                badSignatures.incrementAndGet();
                return AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_UNKNOWN).setReason(27008).build();
            }
            String key = request.getStreamValue() + ":" + request.getStreamEpoch() + ":" + request.getSeq();
            AssetOpResponse seen = ledger.get(key);
            if (seen != null) {
                return seen.toBuilder().setDurable(true).build();
            }
            AssetOpResponse answer;
            if (rpc == AssetRpc.ABORT_DEBIT) {
                answer = AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_REJECTED).build();
            } else if (rpc == AssetRpc.DEBIT) {
                var currency = request.getBundle().getCurrencies(0);
                long balance = balances.getOrDefault(currency.getCurrencyType(), 0L);
                if (balance < currency.getAmount()) {
                    answer = AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_REJECTED).setReason(27000).build();
                } else {
                    balances.put(currency.getCurrencyType(), balance - currency.getAmount());
                    answer = AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build();
                }
            } else {
                answer = AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build();
            }
            ledger.put(key, answer);
            return answer.toBuilder().setDurable(false).build();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(AssetOpSignatures.usableSecret(AssetOpSignatures.normalizeSecret(secret)),
                "需要 surefire 注入的 XM_ASSET_OP_SECRET_GUILD");
        database = "xm_guild_it_econ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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
            st.execute("CREATE TABLE player_state (player_id BIGINT UNSIGNED NOT NULL, data MEDIUMBLOB NOT NULL,"
                    + " saved_epoch BIGINT UNSIGNED NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (player_id)) ENGINE=InnoDB");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player (player_id, name, zone_id) VALUES (?, ?, ?)")) {
                ps.setObject(1, new BigInteger(Long.toUnsignedString(a)));
                ps.setString(2, "甲");
                ps.setInt(3, zone);
                ps.executeUpdate();
            }
        }
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(10);
        redis = Redisson.create(config);
        meters = new SimpleMeterRegistry();

        // ---- 假 scene：真 Triple 提供方，地址写进 Redis 节点目录，玩家位置记录指向它 ----
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        sceneServer = exportScene(scene, port);
        scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        scenes.publish(zone, SCENE_NODE, SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(SCENE_NODE)
                .setInstanceId("fake-scene").setRpcHost("127.0.0.1").setRpcPort(port).build(), Duration.ofMinutes(2));
        locations = new PlayerLocationDirectory(redis);
        locations.putAsync(PlayerLocation.newBuilder().setPlayerId(a).setZoneId(zone).setSceneNodeId(SCENE_NODE)
                .setOwnerEpoch(OWNER_EPOCH).build(), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // ---- 照 GuildConfiguration 的顺序装配（资产通道开启；Tick 间隔放长，由用例手动 tick）----
        GuildConfiguration wiring = new GuildConfiguration();
        GuildProperties.AssetOp assetOp = new GuildProperties.AssetOp(true, Duration.ofSeconds(60), null, null, null, null, null,
                null, null, false, null, null, null);
        GuildProperties props = new GuildProperties(null, null, null, null, null, 4, 64, assetOp);
        GuildMetrics metrics = wiring.guildMetrics(meters);
        ConfigTables tables = wiring.guildConfigTables(tableDir());
        PbMysql synced = wiring.guildTables(dataSource, tables);
        GuildTx tx = wiring.guildTx(dataSource, props, metrics);
        GuildConfiguration.SchemaReady ready = wiring.guildSchemaReady(tx, synced);
        GuildStore store = wiring.guildStore(tx, ready);
        EconomyStore economyStore = wiring.economyStore(tx, ready);
        leaseScheduler = wiring.guildLeaseScheduler();
        lease = wiring.guildIdLease(redis, leaseScheduler, ready);
        GuildIds ids = wiring.guildIds(lease);
        background = wiring.guildBackgroundScheduler();
        GuildCache.CacheRedis cacheRedis = wiring.guildCacheRedis(redis);
        GuildCacheInvalidator invalidator = wiring.guildCacheInvalidator(cacheRedis, props, background, metrics);
        GuildCache cache = wiring.guildCache(cacheRedis, props, store, invalidator, metrics);
        PlayerPresenceDirectory presence = wiring.playerPresenceDirectory(redis);
        PlayerPushes playerPushes = wiring.playerPushes(redis, presence);
        PlayerProfiles profiles = wiring.playerProfiles(dataSource, props);
        GuildPushes pushes = wiring.guildPushes(playerPushes, metrics, props, REGISTRY);
        BackgroundTx backgroundTx = wiring.guildBackgroundTx(dataSource, props);
        EconomyAssetEffects effects = wiring.economyAssetEffects(invalidator, pushes, metrics);
        assetStore = wiring.guildAssetStore(backgroundTx, effects, ready);
        EconomyTables.Lookup economyTables = wiring.economyTables(tables);
        settlePool = wiring.guildAssetSettlePool(props, dataSource, redis);
        workerPool = wiring.guildAssetWorkerPool(props, dataSource, redis);
        timer = wiring.guildAssetTimer();
        clients = wiring.sceneAssetOpClients();
        runtime = wiring.guildAssetRuntime(props, tables, assetStore, dataSource, redis, metrics, settlePool, workerPool, timer,
                clients, lease);
        assertThat(runtime.loop()).isPresent();
        GuildRanks ranks = wiring.guildRanks(redis, background, metrics, store, lease, runtime);
        ApplyPushCooldown cooldown = wiring.guildApplyPushCooldown(redis);
        GuildTableLookup lookup = wiring.guildTableLookup(tables);
        GuildAccess access = wiring.guildAccess(cache, profiles, invalidator);
        GuildViews views = wiring.guildViews(presence, profiles, metrics, props, lookup, store);
        GuildService guilds = wiring.guildService(store, cache, access, views, ranks, pushes, ids, lookup);
        GuildManageService manage = wiring.guildManageService(store, cache, access, views, pushes, cooldown, lookup);
        GuildRankService rankService = wiring.guildRankService(ranks, cache, access, views);
        pool = wiring.guildWorkerPool(props, dataSource);
        GuildEconomyService economy = wiring.guildEconomyService(economyStore, cache, access, views, economyTables, pushes,
                metrics, ids, runtime, props, pool);
        internal = wiring.guildInternalQueries(assetStore, props, pool, metrics);
        dispatcher = wiring.guildDispatcher(REGISTRY, guilds, manage, rankService, economy, pool, metrics, props);
        runtime.start();

        // 预热到假 scene 的客户端：第一次建 Dubbo 引用要同步建连，可能超过单次 800 ms（生产上那一笔会 PENDING、由循环接手）
        clients.call(new SceneAssetEndpoint(zone, SCENE_NODE, "fake-scene", "127.0.0.1", port), AssetRpc.DEBIT,
                AssetOpRequest.getDefaultInstance(), Duration.ofSeconds(20)).get(30, TimeUnit.SECONDS);
        scene.calls.set(0);
        scene.badSignatures.set(0);
    }

    private static IsolatedDubboModule exportScene(SceneAssetOpService ref, int port) {
        IsolatedDubboModule dubbo = IsolatedDubboModule.create("xm-guild-it-fake-scene");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<SceneAssetOpService> service = new ServiceConfig<>(dubbo.module());
        service.setInterface(SceneAssetOpService.class);
        service.setRef(ref);
        service.setGroup(DubboGroups.SCENE_ASSET);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();
        return dubbo;
    }

    private static String tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? "../config-data/tables" : "config-data/tables";
    }

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.stop();
        }
        for (AutoCloseable c : new AutoCloseable[] {pool, settlePool, workerPool, clients, sceneServer}) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 尽力清理
                }
            }
        }
        for (ScheduledExecutorService s : new ScheduledExecutorService[] {background, timer}) {
            if (s != null) {
                s.shutdownNow();
            }
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
            }
            keys.add(RedisKeys.guildOfPlayer(a));
            keys.add(RedisKeys.cacheGeneration(RedisKeys.guildOfPlayer(a)));
            keys.add(RedisKeys.guildRankAll());
            keys.add(RedisKeys.guildRankZone(zone));
            keys.add(RedisKeys.guildRankZones());
            keys.add(RedisKeys.playerLocation(a));
            keys.add(RedisKeys.nodeDirectory(NodeTypes.SCENE, zone));
            redis.getKeys().delete(keys.toArray(String[]::new));
            redis.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        if (database != null) {
            try (Connection conn = DriverManager.getConnection(MYSQL + "/" + PARAMS, USER, PASSWORD);
                 Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    private <T extends Message> T call(String method, Message request, com.google.protobuf.Parser<T> parser) throws Exception {
        ClientCall call = ClientCall.newBuilder().setMessageId(REGISTRY.requireId(GuildMethods.SERVICE, method))
                .setBody(request == null ? ByteString.EMPTY : request.toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(3).setPlayerId(a)).build();
        ClientReply reply = dispatcher.dispatch(call).get(10, TimeUnit.SECONDS);
        assertThat(reply.getTipId()).as(method + " 不该回信封").isZero();
        return parser.parseFrom(reply.getBody());
    }

    private long guildFunds(long g) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT funds FROM guild WHERE guild_id = ?")) {
            ps.setObject(1, new BigInteger(Long.toUnsignedString(g)));
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void 捐献兑换升级经真Dubbo投递到假scene_离线后由循环读已落盘账本终结() throws Exception {
        long startMs = System.currentTimeMillis();
        scene.balances.put(0, 300_000L);
        CreateGuildResponse created = call(GuildMethods.CREATE_GUILD, CreateGuildRequest.newBuilder()
                .setName("资产" + Long.toHexString(a & 0xFFFFF)).build(), CreateGuildResponse.parser());
        long g = created.getGuild().getGuildId();
        guildIds.add(g);

        // 1. 银两大捐：未 durable → 重查到 durable → 同步终结；资金 12000、帮贡 120
        DonateToGuildResponse donated = call(GuildMethods.DONATE_TO_GUILD, DonateToGuildRequest.newBuilder().setDonateId(2).build(),
                DonateToGuildResponse.parser());
        assertThat(donated.hasErrorMessage()).isFalse();
        assertThat(donated.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED);
        assertThat(donated.getGuild().getFunds()).isEqualTo(12_000);
        assertThat(guildFunds(g)).isEqualTo(12_000);
        assertThat(scene.balances.get(0)).isEqualTo(200_000L);
        assertThat(scene.badSignatures.get()).isZero();
        assertThat(scene.calls.get()).as("首投 + 一次重查").isGreaterThanOrEqualTo(2);
        assertThat(meters.get("xm.guild.assetop.requery").tag("rpc", "debit").tag("result", "durable").counter().count())
                .isGreaterThanOrEqualTo(1);

        // 2. 灵石捐献：余额 0 → durable 拒绝 27000 → 14025、视图 REJECTED、今日次数退回
        DonateToGuildResponse rejected = call(GuildMethods.DONATE_TO_GUILD, DonateToGuildRequest.newBuilder().setDonateId(3)
                .build(), DonateToGuildResponse.parser());
        assertThat(rejected.getErrorMessage()).isEqualTo(GuildTip.CURRENCY_INSUFFICIENT.proto());
        assertThat(rejected.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED);
        GetGuildDonateOptionsResponse options = call(GuildMethods.GET_GUILD_DONATE_OPTIONS, null,
                GetGuildDonateOptionsResponse.parser());
        assertThat(options.getOptions(1).getUsedToday()).isEqualTo(1);
        assertThat(options.getOptions(2).getUsedToday()).as("拒绝退回次数").isZero();
        assertThat(options.getContributionBalance()).isEqualTo(120);
        assertThat(options.getRecentResultsList()).extracting(v -> v.getStatus())
                .contains(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED);

        // 3. 兑换 101：帮贡先扣、发放 APPLIED
        BuyGuildShopGoodsResponse bought = call(GuildMethods.BUY_GUILD_SHOP_GOODS, BuyGuildShopGoodsRequest.newBuilder()
                .setGoodsId(101).build(), BuyGuildShopGoodsResponse.parser());
        assertThat(bought.hasErrorMessage()).isFalse();
        assertThat(bought.getOrder().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED);
        assertThat(bought.getContributionBalance()).isEqualTo(90);

        // 4. 升级：资金 12000 < 20000 → 14022，带最新 GuildInfo
        UpgradeGuildResponse upgrade = call(GuildMethods.UPGRADE_GUILD, UpgradeGuildRequest.newBuilder().setExpectedLevel(1)
                .build(), UpgradeGuildResponse.parser());
        assertThat(upgrade.getErrorMessage()).isEqualTo(GuildTip.FUNDS_INSUFFICIENT.proto());
        assertThat(upgrade.getGuild().getFunds()).isEqualTo(12_000);

        // 5. 玩家登出：同步投递本地 NOT_HERE → PENDING；scene 早已记账并落盘（player_state 里有这一 seq 的 APPLIED）→ 循环第 3 次起离线读账本终结
        locations.removeAsync(a, OWNER_EPOCH, 2).toCompletableFuture().get(5, TimeUnit.SECONDS);
        DonateToGuildResponse offline = call(GuildMethods.DONATE_TO_GUILD, DonateToGuildRequest.newBuilder().setDonateId(1).build(),
                DonateToGuildResponse.parser());
        assertThat(offline.hasErrorMessage()).isFalse();
        assertThat(offline.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        long opId = offline.getDonation().getOpId();
        GuildAssetOpRow row = assetStore.getOp(opId, Deadline.after(2_000)).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING);
        assertThat(row.getLastOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE_VALUE);
        writePersistedLedger(row);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE guild_asset_op SET attempts = 3, next_attempt_ms = 0, lease_until_ms = 0 WHERE op_id = ?")) {
            ps.setObject(1, new BigInteger(Long.toUnsignedString(opId)));
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        AssetOpLoop loop = runtime.loop().orElseThrow();
        assertThat(loop.tick()).isEqualTo(1);
        row = assetStore.getOp(opId, Deadline.after(2_000)).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED);
        assertThat(row.getDurable()).isEqualTo(1);
        assertThat(guildFunds(g)).isEqualTo(13_000);
        assertThat(meters.get("xm.guild.assetop.ledger.read").tag("result", "finalized").counter().count()).isEqualTo(1);
        // 后台终结推 FUNDS_CHANGED 只推本人（玩家离线：计 offline；推送是异步的，等它落指标）
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (meters.get("xm.guild.pushes").tag("kind", "funds_changed").tag("outcome", "offline").counter().count() < 1
                && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(meters.get("xm.guild.pushes").tag("kind", "funds_changed").tag("outcome", "offline").counter().count())
                .isEqualTo(1);

        // 6. 内部查询（回档前检查）：看得到三条已应用的行，看不到被拒的
        ListAppliedAssetOpsSinceResponse applied = internal.listAppliedAssetOpsSince(ListAppliedAssetOpsSinceRequest.newBuilder()
                .addPlayerIds(a).setSinceMs(startMs - 1).build()).get(10, TimeUnit.SECONDS);
        assertThat(applied.getResult()).isEqualTo(ListAppliedResult.LIST_APPLIED_RESULT_OK);
        assertThat(applied.getOpsList()).hasSize(3);
        assertThat(applied.getOpsList()).extracting(o -> o.getStatus()).containsOnly(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_VALUE);

        // 解散（离帮提前截止钩子在事务里跑一遍）
        assertThat(call(GuildMethods.DISBAND_GUILD, null, DisbandGuildResponse.parser()).hasErrorMessage()).isFalse();
        assertThat(call(GuildMethods.GET_PLAYER_GUILD, null, GetPlayerGuildResponse.parser()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
    }

    /** 往 player_state 写一份已落盘账本：这一行的 (流, 纪元, seq) 已应用。 */
    private void writePersistedLedger(GuildAssetOpRow row) throws Exception {
        long seq = row.getSeq();
        assertThat(seq).isBetween(1L, 64L);
        AssetOpStreamLedgerState.Builder stream = AssetOpStreamLedgerState.newBuilder().setStream(row.getStream())
                .setWatermark(0).setStreamEpoch(row.getStreamEpoch()).setMaxSeq(seq);
        for (int i = 0; i < 16; i++) {
            long bit = i == 0 ? 1L << (seq - 1) : 0;
            stream.addSeenBits(bit).addAppliedBits(bit);
        }
        PlayerState state = PlayerState.newBuilder().setAssetLedger(AssetOpLedgerState.newBuilder().addStreams(stream)).build();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, new BigInteger(Long.toUnsignedString(a)));
            ps.setBytes(2, state.toByteArray());
            ps.setLong(3, OWNER_EPOCH);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
