package com.game.scene.asset;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.asset.*;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.PlayerEnter;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.sql.*;
import com.game.mail.*;
import com.game.player.store.*;
import com.game.player.store.asset.*;
import com.game.player.store.state.*;
import com.game.proto.mail.*;
import com.game.proto.trade.*;
import com.game.scene.audit.*;
import com.game.scene.bag.*;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.world.*;
import com.game.table.ConfigTables;
import com.game.trade.commerce.*;
import com.game.trade.store.TradeTables;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实际场景效果→围栏 SQL→业务终态；只注入路由、配置号源、回复故障和调度，不注入资产账本或成功状态。 */
@Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AssetArrivalTiDbTest {
    private static final long SELLER = 11, BUYER = 22;
    private static final String SECRET = "asset-arrival-test-only-secret-0123456789";
    private String base, database;
    private DruidDataSource pool;
    private AnnotationConfigApplicationContext context;
    private PlayerStore players;
    private final ExecutorService logic = Executors.newSingleThreadExecutor();
    private final GateExecutor storage = new GateExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService transport = Executors.newSingleThreadExecutor();
    private final AtomicLong ids = new AtomicLong(10000);
    private final AtomicLong businessClock = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean proofAvailable = new AtomicBoolean(true);
    private final AtomicBoolean dropCommittedReply = new AtomicBoolean();
    private final List<AssetOpRequest> requests = new CopyOnWriteArrayList<>();
    private PersistedAssetLedgerReader proof;
    private SceneWorld world;
    private Scene scene;
    private AssetOpEndpoint endpoint;

    private static Deadline budget() { return Deadline.after(15000); }

    @BeforeEach
    void start() throws Exception {
        base = System.getenv("GAME_ASSET_ARRIVAL_TEST_JDBC");
        assertThat(base).as("显式整链 profile 必须配置本机 TiDB，缺失失败而非跳过")
                .matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):4000/?");
        base = base.replaceAll("/$", "");
        database = "asset_arrival_it_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (var c = DriverManager.getConnection(base + "/", "root", ""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        System.out.println("测试随机库创建=" + database);
        pool = new DruidDataSource();
        pool.setUrl(base + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000");
        pool.setUsername("root");
        pool.setPassword("");
        pool.setMaxActive(12);
        pool.setMaxWait(2000);
        new StrongTiDbConnectionPolicy("asset-arrival", true).postProcessBeforeInitialization(pool, "pool");
        pool.init();
        try (var c = pool.getConnection()) { StrongTiDbConnectionPolicy.verify(c, true); }
        new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql")).execute(pool);
        try (var c = pool.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE guild_member (player_id BIGINT UNSIGNED PRIMARY KEY,guild_id BIGINT UNSIGNED NOT NULL)");
        }
        MailTables.sync(pool);
        TradeTables.sync(pool, Duration.ofSeconds(15));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> pool);
        context.register(SqlConfiguration.class);
        context.refresh();
        players = context.getBean(PlayerStore.class);
        players.ensureAccount("asset_arrival");
        for (long id : new long[] {SELLER, BUYER}) {
            PlayerRow row = new PlayerRow();
            row.setPlayerId(id);
            row.setAccount("asset_arrival");
            row.setZoneId(1);
            row.setClassId(1);
            row.setGender(1);
            assertThat(players.createPlayerWithinCap(row, 5, List.of("到账" + id)).status())
                    .isEqualTo(PlayerStore.CreateStatus.CREATED);
            assertThat(players.claimOwnership(id)).isEqualTo(new PlayerStore.ClaimResult.Claimed(1));
        }
        // 唯一初始数据夹具：开局余额/卖家既有实例，不含任何资产位图或收据。
        var extension = UnknownFieldSet.newBuilder().addField(1001, UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFromUtf8("原词条-强化-宝石")).build()).build();
        var initial = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(100).addBalances(1000))
                .setBag(BagState.newBuilder().addItems(BagItemState.newBuilder().setItemUuid(700).setConfigId(1)
                        .setStackSize(1).setUnknownFields(extension))).build();
        assertThat(players.saveStateHeld(players.findPlayer(SELLER).orElseThrow(), initial)).isTrue();
        assertThat(players.saveStateHeld(players.findPlayer(BUYER).orElseThrow(),
                initial.toBuilder().clearBag().build())).isTrue();
        proof = new PersistedAssetLedgerReader(pool, Duration.ofSeconds(2));
        // Surefire 从模块目录运行；同时兼容从仓库根目录单独运行，与既有场景集成夹具一致。
        Path defaultTables = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        var tables = ConfigTables.load(Path.of(System.getProperty("game.asset-arrival.table-dir", defaultTables.toString())));
        var audit = AssetAudit.log();
        var bags = new BagService(BagTables.from(tables), count -> {
            long[] allocated = new long[count];
            for (int i = 0; i < count; i++) allocated[i] = ids.incrementAndGet();
            return allocated;
        }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
        var repository = new StoragePlayerRepository(players, storage, logic, SceneMetrics.noop());
        world = onLogic(() -> new SceneWorld(ConfigSceneTables.from(tables),
                SceneMessageIds.resolve(MessageIdRegistry.loadFromClasspath()), mock(ClientSink.class), repository,
                ids::incrementAndGet, SceneClock.SYSTEM, SceneMetrics.noop(), player -> {
                    bags.initializeOnLoad(player);
                    AssetOpService.checkLedgerOnLoad(player);
                }, PlayerSnapshots.NONE));
        int map = ConfigSceneTables.from(tables).worldSceneConfigIds().getFirst();
        scene = onLogic(() -> world.createScene(map));
        var service = new AssetOpService(world,
                new CurrencyService(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), SceneClock.SYSTEM), bags,
                new AssetOpAuth(caller -> SECRET), SceneClock.SYSTEM);
        endpoint = new AssetOpEndpoint(logic, service);
        enter(SELLER, 1, 11);
        enter(BUYER, 1, 22);
    }

    private <T> T onLogic(Callable<T> action) throws Exception { return logic.submit(action).get(8, TimeUnit.SECONDS); }

    private void enter(long player, long epoch, int session) throws Exception {
        onLogic(() -> {
            world.onPlayerEnter(1, PlayerEnter.newBuilder().setPlayerId(player).setOwnerEpoch(epoch)
                    .setSessionId(session).setSceneId(scene.sceneId()).build());
            return null;
        });
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).until(() ->
                onLogic(() -> world.playerById(player) != null && world.playerById(player).ownerEpoch() == epoch
                        && world.playerById(player).session().sessionId() == session));
    }

    private PersistedAssetLedgerReader businessProof() {
        DataSource available = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                if (!proofAvailable.get()) throw new SQLTransientConnectionException("测试故障：证明读取不可用");
                return pool.getConnection();
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        return new PersistedAssetLedgerReader(available, Duration.ofSeconds(2));
    }

    private ReliableAssetDelivery delivery(String caller) {
        var address = new SceneAssetEndpoint(1, 1, "asset-arrival-test", "127.0.0.1", 21100);
        return new ReliableAssetDelivery(player -> CompletableFuture.completedFuture(new SceneAssetLocator.Found(
                com.game.discovery.proto.PlayerLocation.newBuilder().setPlayerId(player).build(), address)),
                (target, rpc, request, timeout) -> {
                    requests.add(request.toBuilder().clearAuth().build());
                    return endpoint.submit(rpc, request).thenCompose(answer -> {
                        if ((answer.getOutcome() == AssetOutcome.ASSET_OUTCOME_APPLIED
                                || answer.getOutcome() == AssetOutcome.ASSET_OUTCOME_REJECTED)
                                && dropCommittedReply.compareAndSet(true, false)) {
                            return CompletableFuture.supplyAsync(() -> {
                                await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(20)).until(() -> {
                                    var read = proof.read(request.getPlayerId());
                                    return read instanceof PersistedAssetLedgerReader.Read.Loaded loaded
                                            && loaded.ledger().outcomeOf(request.getStreamValue(), request.getStreamEpoch(), request.getSeq()).conclusive();
                                });
                                throw new CompletionException(new SQLRecoverableException("测试故障：SQL 提交后响应丢失"));
                            }, transport);
                        }
                        return CompletableFuture.completedFuture(answer);
                    });
                }, (rpc, request, now) -> AssetOpSignatures.sign(rpc, request, caller, SECRET, now), timer,
                System::currentTimeMillis, new ReliableAssetDelivery.Observer() { }, 1200, List.of(50L, 100L, 200L));
    }

    private long handOff(long player) throws Exception {
        var row = players.findPlayer(player).orElseThrow();
        long oldEpoch = row.getOwnerEpoch();
        var oldState = players.loadState(player);
        long now = System.currentTimeMillis();
        assertThat(players.handOffOwnership(row, oldState, now + PlayerStore.OWNER_LEASE.toMillis(), now + 1000,
                Duration.ofSeconds(5))).isEqualTo(new PlayerStore.HandOffResult.HandedOff(oldEpoch + 1));
        assertThat(players.saveStateHeld(row, PlayerState.getDefaultInstance()))
                .as("旧 owner 的迟到写不能抹去已入库资产与账本").isFalse();
        assertThat(players.loadState(player)).isEqualTo(oldState);
        enter(player, oldEpoch + 1, (int) (player + oldEpoch + 100));
        return oldEpoch + 1;
    }

    @Test
    void 邮件附件实际到账_SQL提交后丢回复_换主与工作器重建沿用原操作() throws Exception {
        var jdbc = new AuthorityJdbc(pool);
        var mail = new MailStore(jdbc, new AuthorityIds(jdbc, "mail_id_sequence"), id -> id == 1, businessClock::get);
        long id = mail.send("admin", 1, SendMailRequest.newBuilder().setSenderKind(1).setRequestId(991)
                .setRecipientPlayerId(BUYER).setTitle("真实附件到账").setBody("货币和道具同档落库")
                .setAttachments(MailAttachments.newBuilder().addCurrencies(MailCurrency.newBuilder().setCurrencyType(0).setAmount(500))
                        .addItems(MailItem.newBuilder().setConfigId(1).setCount(2))).build(), budget()).getMailId();
        long operation = mail.claim(BUYER, id, budget()).getAssetOpId();
        proofAvailable.set(false);
        dropCommittedReply.set(true);
        try (var first = new MailDeliveryWorker(mail, delivery("mail"), businessProof())) {
            MailAssetArrivalFixture.process(first, operation);
        }
        assertThat(mail.read(BUYER, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue())
                .as("已实际到账但独立证明不可读时仍待确认").isEqualTo(2);
        assertThat(players.loadState(BUYER).getCurrency().getBalances(0)).isEqualTo(600);
        assertThat(players.loadState(BUYER).getBag().getItemsList().stream().mapToInt(BagItemState::getStackSize).sum()).isEqualTo(2);
        var original = requests.getFirst();
        assertThat(handOff(BUYER)).isEqualTo(2);
        proofAvailable.set(true);
        var restarted = new MailStore(jdbc, new AuthorityIds(jdbc, "mail_id_sequence"), item -> item == 1, businessClock::get);
        try (var next = new MailDeliveryWorker(restarted, delivery("mail"), businessProof())) {
            MailAssetArrivalFixture.process(next, operation);
            MailAssetArrivalFixture.process(next, operation);
        }
        assertThat(restarted.read(BUYER, ReadMailRequest.newBuilder().setMailId(id).build(), budget()).getMail().getClaimStateValue()).isEqualTo(3);
        assertThat(restarted.claim(BUYER, id, budget()).getAssetOpId()).isEqualTo(operation);
        assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request).isEqualTo(original));
        assertThat(players.loadState(BUYER).getCurrency().getBalances(0)).isEqualTo(600);
        assertThat(players.loadState(BUYER).getBag().getItemsList().stream().mapToInt(BagItemState::getStackSize).sum()).isEqualTo(2);
        var mailProof = ((PersistedAssetLedgerReader.Read.Loaded) proof.read(BUYER)).ledger();
        assertThat(mailProof.identityMatches(original.getStreamValue(), original.getStreamEpoch(), original.getSeq(),
                original.getCorrelationId(), AssetOpSignatures.requestDigest(original))).isTrue();
        var altered = original.toBuilder().setBundle(original.getBundle().toBuilder().setCurrencies(0,
                original.getBundle().getCurrencies(0).toBuilder().setAmount(501))).build();
        assertThat(endpoint.submit(AssetRpc.CREDIT, signed(altered)).get(5, TimeUnit.SECONDS).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(players.loadState(BUYER).getCurrency().getBalances(0)).isEqualTo(600);
        try (var c = pool.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM mail_asset_op")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void 寄售原实例与双侧货币实际到账_未知结果及换主恢复后只结算一次() throws Exception {
        var store = new CommerceStore(pool);
        var policy = new CommerceRules.Policy(Set.of(1), Set.of(), Set.of(1), true, List.of());
        var engine = new CommerceEngine(store, ids::incrementAndGet, policy, businessClock::get);
        var row = engine.consignment(SELLER, CreateConsignmentRequest.newBuilder().setClientRequestId("asset-arrival-listing-001")
                .setAssets(TradeAssetSelection.newBuilder().addItemUuids(700)).setCurrencyType(1).setPrice(50)
                .setDurationMs(60000).build(), budget());
        engine.advance(row, budget());
        // 资产待办的 next_attempt_ms 来自 SQL 时钟；夹具从该事实推进调度时钟，不写待办状态。
        try (var connection = pool.getConnection()) { businessClock.set(CommerceStore.sqlNow(connection) + 1); }
        assertThat(store.ops("status=1")).hasSize(1);
        proofAvailable.set(false); dropCommittedReply.set(true);
        try (var first = new CommerceRuntime(engine, delivery("trade"), businessProof())) { first.tick(budget()); }
        assertThat(requests).as("必须实际投递到 scene 才能检查扣出结果").isNotEmpty();
        assertThat(players.loadState(SELLER).getBag().getItemsCount()).isZero();
        assertThat(store.get(row.getCommerceId()).getStatus()).isNotEqualTo(CommerceRules.SETTLED);
        var original = requests.getFirst();
        assertThat(handOff(SELLER)).isEqualTo(2);
        proofAvailable.set(true);
        engine = new CommerceEngine(new CommerceStore(pool), ids::incrementAndGet, policy, businessClock::get);
        try (var restarted = new CommerceRuntime(engine, delivery("trade"), businessProof())) {
            for (int i = 0; i < 8 && !TradeCommerceWorkflow.parseFrom(store.get(row.getCommerceId()).getWorkflowProto()).getStage().equals("open"); i++) {
                businessClock.addAndGet(2000); restarted.tick(budget());
            }
            assertThat(TradeCommerceWorkflow.parseFrom(store.get(row.getCommerceId()).getWorkflowProto()).getStage()).isEqualTo("open");
            engine.mutate(BUYER, row.getCommerceId(), "buy", budget(), "asset-arrival-buy-001");
            for (int i = 0; i < 12 && store.get(row.getCommerceId()).getStatus() != CommerceRules.SETTLED; i++) {
                businessClock.addAndGet(2000); restarted.tick(budget());
            }
            assertThat(store.get(row.getCommerceId()).getStatus()).isEqualTo(CommerceRules.SETTLED);
            restarted.tick(budget());
        }
        assertThat(requests.stream().filter(request -> request.getPlayerId() == SELLER && request.getStreamValue() == 3).toList())
                .hasSizeGreaterThanOrEqualTo(2).allSatisfy(request -> assertThat(request).isEqualTo(original));
        var seller = players.loadState(SELLER); var buyer = players.loadState(BUYER);
        assertThat(seller.getCurrency().getBalances(1)).isEqualTo(1050);
        assertThat(buyer.getCurrency().getBalances(1)).isEqualTo(950);
        assertThat(seller.getBag().getItemsCount()).isZero();
        assertThat(buyer.getBag().getItemsList()).hasSize(1);
        var item = buyer.getBag().getItems(0);
        assertThat(item.getItemUuid()).isEqualTo(700);
        assertThat(item.getUnknownFields().getField(1001).getLengthDelimitedList())
                .containsExactly(ByteString.copyFromUtf8("原词条-强化-宝石"));
        assertThat(store.ops("status=1")).isEmpty();
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            var receipt = ((PersistedAssetLedgerReader.Read.Loaded) proof.read(SELLER)).ledger()
                    .receipt(original.getStreamValue(), original.getStreamEpoch(), original.getSeq());
            assertThat(receipt).isNotNull();
            assertThat(receipt.getSnapshotProto()).isEmpty();
            assertThat(receipt.getRequestDigest()).isEqualTo(AssetOpSignatures.requestDigest(original));
            assertThat(receipt.getCorrelationId()).isEqualTo(original.getCorrelationId());
        });
        var altered = original.toBuilder().setCorrelationId(original.getCorrelationId() + 1).build();
        assertThat(endpoint.submit(AssetRpc.DEBIT, AssetOpSignatures.sign(AssetRpc.DEBIT, altered, "trade", SECRET,
                System.currentTimeMillis())).get(5, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
    }

    @Test
    void 同epoch重建与真实旧SQL在途_不允许新写越过旧写并保留新增资产() throws Exception {
        storage.holdNext();
        var first = credit(1, 10);
        assertThat(endpoint.submit(AssetRpc.CREDIT, signed(first)).get(5, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        storage.awaitHeld();
        enter(BUYER, 1, 222);
        assertThat(endpoint.submit(AssetRpc.CREDIT, signed(credit(2, 20))).get(5, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(onLogic(() -> world.requestSave(world.playerById(BUYER)))).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        storage.releaseHeld();
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).until(() ->
                onLogic(() -> world.requestSave(world.playerById(BUYER))) != SceneWorld.SaveRequest.IN_FLIGHT);
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            var read = proof.read(BUYER);
            assertThat(read).isInstanceOf(PersistedAssetLedgerReader.Read.Loaded.class);
            assertThat(((PersistedAssetLedgerReader.Read.Loaded) read).ledger().classify(5, 19, 2)).isEqualTo(AssetSeqState.APPLIED);
        });
        assertThat(players.loadState(BUYER).getCurrency().getBalances(0)).isEqualTo(130);
    }

    @Test
    void 被改成副本读的物理连接不得出具SQL资产证明() throws Exception {
        try (var poisoned = new DruidDataSource()) {
            poisoned.setUrl(pool.getUrl()); poisoned.setUsername("root"); poisoned.setPassword("");
            poisoned.setMaxActive(1); poisoned.setMaxWait(1000);
            poisoned.setConnectionInitSqls(List.of(StrongTiDbConnectionPolicy.SESSION_SQL)); poisoned.init();
            try (var c = poisoned.getConnection(); var s = c.createStatement()) { s.execute("SET SESSION tidb_replica_read='follower'"); }
            assertThat(new PersistedAssetLedgerReader(poisoned).read(BUYER)).isInstanceOf(PersistedAssetLedgerReader.Read.Failed.class);
        }
    }

    private AssetOpRequest credit(long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(BUYER).setStream(AssetStream.ASSET_STREAM_SYSTEM_CREDIT)
                .setStreamEpoch(19).setSeq(seq).setCorrelationId(1000 + seq).setTxType(2)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(amount))).build();
    }
    private AssetOpRequest signed(AssetOpRequest request) {
        return AssetOpSignatures.sign(AssetRpc.CREDIT, request, "mail", SECRET, System.currentTimeMillis());
    }

    @AfterEach
    void close() throws Exception {
        storage.releaseHeld();
        for (var executor : List.of(transport, storage, timer, logic)) {
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("本用例执行器必须排空").isTrue();
        }
        if (context != null) context.close();
        if (pool != null) pool.close();
        if (database != null) {
            assertThat(database).matches("asset_arrival_it_[0-9a-f]{32}");
            try (var c = DriverManager.getConnection(base + "/", "root", ""); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + database);
            }
            System.out.println("测试随机库清理=" + database);
        }
    }

    /** 精确暂停下一笔实际存储任务，后续加载仍由真实存储线程执行；没有数据库结果替身。 */
    private static final class GateExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newFixedThreadPool(3);
        private final AtomicBoolean next = new AtomicBoolean();
        private final AtomicReference<Runnable> held = new AtomicReference<>();
        private final CountDownLatch captured = new CountDownLatch(1);
        void holdNext() { next.set(true); }
        void awaitHeld() throws InterruptedException { assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue(); }
        void releaseHeld() { var task = held.getAndSet(null); if (task != null) delegate.execute(task); }
        @Override public void execute(Runnable command) {
            if (next.compareAndSet(true, false)) { held.set(command); captured.countDown(); }
            else delegate.execute(command);
        }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout, unit); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class SqlConfiguration {
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration); return factory.getObject();
        }
        @Bean MapperFactoryBean<PlayerMapper> playerMapper(SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(PlayerMapper.class); mapper.setSqlSessionFactory(factory); return mapper;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager transactions) {
            return new PlayerStore(mapper, System::currentTimeMillis, transactions);
        }
    }
}
