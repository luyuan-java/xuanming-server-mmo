package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetAuth;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.api.proto.PlayerEnter;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import com.game.player.store.state.CurrencyDebtState;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.scene.asset.AssetOpService.Rpc;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.bag.TestBagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemCatalog.ItemSpec;
import com.game.scene.player.ItemGuids;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingAssetAudit.Currency;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 资产通道 scene 侧的统一流程，用例对应 mmorpg asset_op_system_test.cpp（Java 版没有的冻结 / 交接 / 战斗 / 退出在途除外）。 */
class AssetOpServiceTest {

    private static final long LINK = 1;
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long EPOCH = 1_700_000_000_000L;
    private static final int GOLD = 0;
    private static final int DIAMOND = 1;
    private static final int STACKABLE = 10;
    private static final int SINGLE = 1;

    private final ManualClock clock = new ManualClock();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    /** 物品入包流水记下之后调用（测试在「预检之后、货币入账之前」插入状态变化）。 */
    private Runnable onItemGained = () -> {
    };
    private final AssetAudit hooked = new AssetAudit() {
        @Override
        public void currencyChanged(long playerId, int currencyType, long delta, long before, long after,
                                    Reason reason, long correlationId, String extra) {
            audit.currencyChanged(playerId, currencyType, delta, before, after, reason, correlationId, extra);
        }

        @Override
        public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                               long correlationId, String extra) {
            audit.itemGained(playerId, itemUuid, configId, quantity, reason, correlationId, extra);
            onItemGained.run();
        }

        @Override
        public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                  long correlationId, String extra) {
            audit.itemDestroyed(playerId, itemUuid, configId, quantity, reason, correlationId, extra);
        }
    };
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final AtomicLong guidSeq = new AtomicLong(5000);
    private final AtomicBoolean guidsExhausted = new AtomicBoolean();
    private final ItemGuids guids = count -> {
        if (guidsExhausted.get()) {
            return null;
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = guidSeq.incrementAndGet();
        }
        return out;
    };
    private final BagTables tables = TestBagTables.of(Map.of(
            SINGLE, new ItemSpec(SINGLE, 1, 0),
            STACKABLE, new ItemSpec(STACKABLE, 999, 0)), Map.of());
    private final CurrencyService currency = new CurrencyService(hooked, GainAnomalyDetector.off(), SceneMetrics.noop(),
            clock);
    private final BagService bags = new BagService(tables, guids, hooked, GainAnomalyDetector.off(), SceneMetrics.noop());
    private SceneWorld world;
    private AssetOpService service;
    private ScenePlayer player;

    private void start(long ownerEpoch, PlayerState state) {
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(9000)::incrementAndGet, clock, SceneMetrics.noop(), AssetOpService::checkLedgerOnLoad,
                PlayerSnapshots.NONE);
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, ownerEpoch, 1, 1, "", 1, 1, new Vec3(5, 5, 0), state));
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setSceneId(scene.sceneId()).setOwnerEpoch(ownerEpoch).build());
        repo.completeAll();
        player = world.playerById(PLAYER);
        assertThat(player).isNotNull();
        service = new AssetOpService(world, currency, bags, new AssetOpAuth(c -> AssetOpAuthTest.SECRET), clock);
    }

    private void start() {
        start(1, PlayerState.getDefaultInstance());
    }

    private static AssetOpRequest.Builder request(AssetStream stream, int tx, long seq) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(stream).setSeq(seq).setStreamEpoch(EPOCH)
                .setTxType(tx).setCorrelationId(500 + seq)
                .setAuth(AssetAuth.newBuilder().setCaller(stream.getNumber() <= 2 ? "guild" : "trade"));
    }

    private static AssetOpRequest.Builder debit(long seq, long amount) {
        return request(AssetStream.ASSET_STREAM_GUILD_DEBIT, 24, seq).setBundle(AssetBundle.newBuilder()
                .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(GOLD).setAmount(amount)));
    }

    private static AssetOpRequest.Builder credit(long seq, AssetBundle.Builder bundle) {
        return request(AssetStream.ASSET_STREAM_GUILD_CREDIT, 25, seq).setBundle(bundle);
    }

    private static AssetBundle.Builder gold(long amount) {
        return AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(GOLD).setAmount(amount));
    }

    private AssetOpResponse call(Rpc rpc, AssetOpRequest.Builder request) {
        request.getAuthBuilder().setTimestampMs(clock.epochMillis());
        return service.handle(rpc, AssetOpAuthTest.signed(rpc.wireName(), request, AssetOpAuthTest.SECRET));
    }

    private static AssetOpResponse response(AssetOutcome outcome, int reason, boolean durable) {
        return AssetOpResponse.newBuilder().setOutcome(outcome).setReason(reason).setDurable(durable).build();
    }

    private void saveCompletes() {
        repo.takeProgress().complete(ProgressResult.SAVED);
    }

    private long gold() {
        return player.wallet().balance(GOLD);
    }

    // ------------------------------------------------------------------ 扣款

    @Test
    void 扣款应用_落盘后重查报durable_不重扣() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        audit.currencies.clear();

        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, false));
        assertThat(gold()).isEqualTo(70);
        assertThat(audit.currencies).containsExactly(new Currency(PLAYER, GOLD, -30, 100, 70, Reason.GUILD_DONATE, 501));
        assertThat(repo.pendingProgress()).as("记账后立刻请求存盘").isEqualTo(1);

        AssetOpResponse inFlight = call(Rpc.DEBIT, debit(1, 30));
        assertThat(inFlight).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, false));
        saveCompletes();
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true));
        assertThat(gold()).isEqualTo(70);
        assertThat(audit.currencies).hasSize(1);

        // 已 durable 不再补存：过了限频间隔、内存又有了别的改动（补存会真的提交），重查仍不提交
        clock.advanceMillis(AssetOpService.RESAVE_MIN_INTERVAL_MS);
        currency.add(player, GOLD, 1, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true));
        assertThat(repo.pendingProgress()).as("已 durable 不再存盘").isZero();
        assertThat(gold()).isEqualTo(71);
    }

    @Test
    void 记账时已有存盘在途_那次存盘不算数_限频过后补存才durable() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getDurable()).isFalse();
        assertThat(repo.pendingProgress()).isEqualTo(1);
        // seq 2 记账时 seq 1 的存盘还在途：requestSave 回 IN_FLIGHT，在途那份快照里没有 seq 2
        assertThat(call(Rpc.DEBIT, debit(2, 10)).getDurable()).isFalse();
        assertThat(repo.pendingProgress()).isEqualTo(1);
        saveCompletes();

        assertThat(call(Rpc.DEBIT, debit(1, 30)).getDurable()).isTrue();
        assertThat(call(Rpc.DEBIT, debit(2, 10)).getDurable()).as("落盘的快照里没有 seq 2").isFalse();
        assertThat(repo.pendingProgress()).as("距上次请求不到 500ms").isZero();
        clock.advanceMillis(AssetOpService.RESAVE_MIN_INTERVAL_MS);
        assertThat(call(Rpc.DEBIT, debit(2, 10)).getDurable()).isFalse();
        assertThat(repo.pendingProgress()).as("补存").isEqualTo(1);
        saveCompletes();
        assertThat(call(Rpc.DEBIT, debit(2, 10)).getDurable()).isTrue();
        assertThat(gold()).isEqualTo(60);
    }

    @Test
    void 已有改动后又失败_记部分发放_首答与重查都带partial() {
        start();
        // 物品写入的那一刻封掉钻石（预检之后），后面的钻石入账失败：物品已发，只能记部分发放转人工
        onItemGained = () -> player.wallet().block(DIAMOND);
        AssetBundle.Builder bundle = AssetBundle.newBuilder()
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(2))
                .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(DIAMOND).setAmount(5));
        AssetOpResponse first = call(Rpc.CREDIT, credit(1, bundle.clone()));
        assertThat(first).isEqualTo(AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED)
                .setReason(AssetOpService.PARTIAL_APPLIED).setPartial(true).build());
        assertThat(player.bags().bag(BagType.INVENTORY).total(STACKABLE)).isEqualTo(2);
        assertThat(player.wallet().balance(DIAMOND)).isZero();
        assertThat(player.assetLedger().isPartial(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, 1)).isTrue();

        saveCompletes();
        assertThat(call(Rpc.CREDIT, credit(1, bundle))).isEqualTo(AssetOpResponse.newBuilder()
                .setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setReason(AssetOpService.PARTIAL_APPLIED).setPartial(true)
                .setDurable(true).build());
        assertThat(player.bags().bag(BagType.INVENTORY).total(STACKABLE)).as("重查不重发").isEqualTo(2);
    }

    @Test
    void 余额不足_记REJECTED_结局固定() {
        start();
        currency.add(player, GOLD, 10, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpService.CURRENCY_INSUFFICIENT, false));
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        saveCompletes();
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpService.CURRENCY_INSUFFICIENT, true));
        assertThat(gold()).isEqualTo(110);
    }

    @Test
    void 玩家不在本节点_NOT_HERE_不记账() {
        start();
        assertThat(call(Rpc.DEBIT, debit(1, 30).setPlayerId(PLAYER + 1))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_NOT_HERE, AssetOpService.PLAYER_NOT_HERE, false));
        assertThat(player.assetLedger().isPristine()).isTrue();
    }

    @Test
    void 中止占位_此后同seq永远拒绝_已应用的seq中止仍答应用() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.ABORT_DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).extracting(AssetOpResponse::getOutcome, AssetOpResponse::getReason)
                .containsExactly(AssetOutcome.ASSET_OUTCOME_REJECTED, 0);
        assertThat(gold()).isEqualTo(100);

        assertThat(call(Rpc.DEBIT, debit(2, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(call(Rpc.ABORT_DEBIT, debit(2, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(gold()).isEqualTo(70);
    }

    @Test
    void 中止收全部流_不校验流水原因() {
        start();
        AssetOpRequest.Builder abort = request(AssetStream.ASSET_STREAM_GUILD_CREDIT, 0, 1);
        assertThat(call(Rpc.ABORT_DEBIT, abort).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
    }

    @Test
    void 账本损坏_一律RETRY_27005_原样带回不改写() {
        AssetOpLedgerState corrupted = AssetOpLedgerState.newBuilder().addStreams(AssetOpStreamLedgerState.newBuilder()
                .setStream(1).setStreamEpoch(EPOCH)).build();
        start(1, PlayerState.newBuilder().setAssetLedger(corrupted).build());
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpService.BLOCKED, false));
        assertThat(gold()).isEqualTo(100);
        assertThat(WorldTestAccess.persistentState(player).getAssetLedger()).isEqualTo(corrupted);
    }

    // ------------------------------------------------------------------ 信封、验签、分类

    @Test
    void 信封非法_UNKNOWN_27004_不记账() {
        start();
        int bad = AssetOpService.INVALID_BUNDLE;
        assertThat(call(Rpc.DEBIT, debit(1, 30).setPlayerId(0))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.DEBIT, debit(0, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.DEBIT, debit(1, 30).setStreamEpoch(0))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.DEBIT, debit(1, 30).setStreamValue(9))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.DEBIT, debit(1, 30).setStream(AssetStream.ASSET_STREAM_UNSPECIFIED)))
                .isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.CREDIT, debit(1, 30))).as("扣款流上调发放").isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(call(Rpc.DEBIT, debit(1, 30).setTxType(25))).as("流水原因不在白名单").isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, bad, false));
        assertThat(player.assetLedger().isPristine()).isTrue();
    }

    @Test
    void 验签失败_UNKNOWN_27008_不记账_签对后照常应用() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        AssetOpRequest.Builder forged = debit(1, 30);
        forged.getAuthBuilder().setTimestampMs(clock.epochMillis());
        AssetOpRequest wrongKey = AssetOpAuthTest.signed("debit", forged, "another-secret-of-enough-length-xx");
        assertThat(service.handle(Rpc.DEBIT, wrongKey)).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, AssetOpService.AUTH_FAILED, false));
        assertThat(player.assetLedger().isPristine()).isTrue();
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
    }

    @Test
    void 滑出窗口_跳号过远_旧纪元_都是UNKNOWN_0_不记账() {
        start();
        currency.add(player, GOLD, 10_000, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1024, 1)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(call(Rpc.DEBIT, debit(2048, 1)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        AssetOpLedgerState before = player.assetLedger().toState();
        assertThat(call(Rpc.DEBIT, debit(5, 1))).as("seq 5 已滑出窗口").isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, false));
        assertThat(call(Rpc.DEBIT, debit(2048 + 1025, 1))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, false));
        assertThat(call(Rpc.DEBIT, debit(2049, 1).setStreamEpoch(EPOCH - 1)))
                .isEqualTo(response(AssetOutcome.ASSET_OUTCOME_UNKNOWN, 0, false));
        assertThat(player.assetLedger().toState()).isEqualTo(before);
        assertThat(gold()).isEqualTo(9_998);
    }

    @Test
    void 新纪元是新流水簿_同seq重新应用() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(call(Rpc.DEBIT, debit(1, 30).setStreamEpoch(EPOCH + 1)).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(gold()).isEqualTo(40);
    }

    @Test
    void 暂时失败不重置纪元_旧纪元的结局还在() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        // 新纪元的发放碰上背包满：RETRY，不得因此把旧纪元的账本清掉
        AssetOpRequest.Builder full = credit(1, AssetBundle.newBuilder()
                .addItems(AssetItem.newBuilder().setConfigId(SINGLE).setCount(101))).setStreamEpoch(EPOCH + 1);
        assertThat(call(Rpc.CREDIT, full).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(gold()).isEqualTo(70);
    }

    // ------------------------------------------------------------------ 包内容

    @Test
    void 扣款包非法_记REJECTED_27004() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        int bad = AssetOpService.INVALID_BUNDLE;
        List<AssetBundle.Builder> bundles = List.of(
                AssetBundle.newBuilder(),
                gold(1).addCurrencies(AssetCurrency.newBuilder().setCurrencyType(1).setAmount(1)),
                gold(1).addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1)),
                gold(0),
                gold(-1L),
                AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(3).setAmount(1)),
                gold(1).addItemUuids(7),
                gold(1).setPetId(7));
        long seq = 1;
        for (AssetBundle.Builder bundle : bundles) {
            AssetOpResponse r = call(Rpc.DEBIT, debit(seq, 1).setBundle(bundle));
            assertThat(r.getOutcome()).as(bundle.toString()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
            assertThat(r.getReason()).isEqualTo(bad);
            assertThat(player.assetLedger().classify(1, EPOCH, seq)).isEqualTo(AssetOpLedger.SeqState.REJECTED);
            seq++;
        }
        assertThat(gold()).isEqualTo(100);
    }

    @Test
    void 发放包非法_记REJECTED_27004() {
        start();
        List<AssetBundle.Builder> bundles = List.of(
                AssetBundle.newBuilder(),
                gold(1).addCurrencies(AssetCurrency.newBuilder().setCurrencyType(GOLD).setAmount(2)),
                AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1))
                        .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(2)),
                AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(0)),
                AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(777).setCount(1)),
                gold(1).addItemUuids(1),
                AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(-1).setAmount(1)));
        long seq = 1;
        for (AssetBundle.Builder bundle : bundles) {
            AssetOpResponse r = call(Rpc.CREDIT, credit(seq++, bundle));
            assertThat(r.getOutcome()).as(bundle.toString()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
            assertThat(r.getReason()).isEqualTo(AssetOpService.INVALID_BUNDLE);
        }
        AssetBundle.Builder tooMany = AssetBundle.newBuilder();
        for (int i = 0; i < 17; i++) {
            tooMany.addItems(AssetItem.newBuilder().setConfigId(1000 + i).setCount(1));
        }
        assertThat(call(Rpc.CREDIT, credit(seq, tooMany)).getReason()).isEqualTo(AssetOpService.INVALID_BUNDLE);
        assertThat(gold()).isZero();
        assertThat(audit.items).isEmpty();
    }

    // ------------------------------------------------------------------ 发放

    @Test
    void 发放物品与货币_一笔关联号_入人物背包() {
        start();
        AssetOpResponse r = call(Rpc.CREDIT, credit(1, gold(50)
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(5))));
        assertThat(r).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, false));
        assertThat(gold()).isEqualTo(50);
        assertThat(player.bags().bag(BagType.INVENTORY).total(STACKABLE)).isEqualTo(5);
        assertThat(audit.currencies).containsExactly(new Currency(PLAYER, GOLD, 50, 0, 50, Reason.GUILD_SHOP, 501));
        assertThat(audit.items).singleElement().satisfies(item -> {
            assertThat(item.reason()).isEqualTo(Reason.GUILD_SHOP);
            assertThat(item.correlationId()).isEqualTo(501);
        });
    }

    @Test
    void 发放先抵补缴欠款_两条流水共用关联号且首尾相接() {
        PlayerState withDebt = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder()
                .addBalances(10).addBalances(0).addBalances(0)
                .addDebts(CurrencyDebtState.newBuilder().setCurrencyType(GOLD).setOwed(30))).build();
        start(1, withDebt);
        assertThat(call(Rpc.CREDIT, credit(1, gold(50))).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(gold()).isEqualTo(30);
        assertThat(player.wallet().debt(GOLD)).isNull();
        assertThat(audit.currencies).containsExactly(
                new Currency(PLAYER, GOLD, 50, 10, 60, Reason.GUILD_SHOP, 501),
                new Currency(PLAYER, GOLD, -30, 60, 30, Reason.DEFERRED_CLAWBACK, 501, "{\"debt_remaining\":0}"));
    }

    @Test
    void 货币被封禁_全服或本人_记REJECTED_27005_物品也不发() {
        start();
        currency.applyGlobalBlocks(new GlobalGainBlocks(Set.of(GOLD), Set.of()));
        AssetBundle.Builder bundle = gold(5).addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1));
        assertThat(call(Rpc.CREDIT, credit(1, bundle.clone()))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpService.BLOCKED, false));
        currency.applyGlobalBlocks(GlobalGainBlocks.NONE);
        player.wallet().block(GOLD);
        assertThat(call(Rpc.CREDIT, credit(2, bundle)).getReason()).isEqualTo(AssetOpService.BLOCKED);
        assertThat(audit.items).isEmpty();
        assertThat(player.bags().bag(BagType.INVENTORY).total(STACKABLE)).isZero();
    }

    @Test
    void 物品被全服禁发_记REJECTED_27005() {
        start();
        bags.applyGlobalBlocks(new GlobalGainBlocks(Set.of(), Set.of(STACKABLE)));
        AssetOpResponse r = call(Rpc.CREDIT, credit(1, gold(5)
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1))));
        assertThat(r.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(r.getReason()).isEqualTo(AssetOpService.BLOCKED);
        assertThat(gold()).isZero();
    }

    @Test
    void 发放会让余额溢出_改动之前按坏包拒绝() {
        start();
        currency.add(player, GOLD, Long.MAX_VALUE - 1, Reason.GM_GRANT);
        AssetOpResponse r = call(Rpc.CREDIT, credit(1, gold(2)
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1))));
        assertThat(r.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(r.getReason()).isEqualTo(AssetOpService.INVALID_BUNDLE);
        assertThat(player.bags().bag(BagType.INVENTORY).total(STACKABLE)).isZero();
        assertThat(gold()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void 背包满_RETRY_27001_不记账_货币也不发() {
        start();
        AssetOpResponse r = call(Rpc.CREDIT, credit(1, gold(5)
                .addItems(AssetItem.newBuilder().setConfigId(SINGLE).setCount(101))));
        assertThat(r).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpService.BAG_FULL, false));
        assertThat(gold()).isZero();
        assertThat(player.assetLedger().isPristine()).isTrue();
        assertThat(repo.pendingProgress()).isZero();
    }

    @Test
    void 物品guid发不出号_RETRY_0_不记账() {
        start();
        guidsExhausted.set(true);
        AssetOpResponse r = call(Rpc.CREDIT, credit(1, AssetBundle.newBuilder()
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1))));
        assertThat(r).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_RETRY, 0, false));
        assertThat(player.assetLedger().isPristine()).isTrue();
        guidsExhausted.set(false);
        assertThat(call(Rpc.CREDIT, credit(1, AssetBundle.newBuilder()
                .addItems(AssetItem.newBuilder().setConfigId(STACKABLE).setCount(1)))).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
    }

    // ------------------------------------------------------------------ durable 与补存限频

    @Test
    void 已见未durable的重查_每500ms至多补存一次() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        call(Rpc.DEBIT, debit(1, 30));
        repo.takeProgress().complete(ProgressResult.FAILED);
        assertThat(player.persistedState()).as("结局不明，快照作废").isNull();

        clock.advanceMillis(100);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getDurable()).isFalse();
        assertThat(repo.pendingProgress()).as("距上次请求不到 500ms").isZero();
        clock.advanceMillis(400);
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getDurable()).isFalse();
        assertThat(repo.pendingProgress()).isEqualTo(1);
        saveCompletes();
        assertThat(call(Rpc.DEBIT, debit(1, 30)).getDurable()).isTrue();
        assertThat(gold()).isEqualTo(70);
    }

    @Test
    void 中止占位落盘后报durable() {
        start();
        assertThat(call(Rpc.ABORT_DEBIT, debit(1, 30)).getDurable()).isFalse();
        saveCompletes();
        assertThat(call(Rpc.ABORT_DEBIT, debit(1, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_REJECTED, 0, true));
    }

    @Test
    void 账本随存档往返_重新进场后已见结局照答且已durable() {
        start();
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        call(Rpc.DEBIT, debit(1, 30));
        call(Rpc.DEBIT, debit(2, 500));
        saveCompletes();
        PlayerState saved = WorldTestAccess.persistentState(player);
        assertThat(saved.hasAssetLedger()).isTrue();

        repo.put(new PlayerData(PLAYER, 2, 1, 1, "", 1, 1, new Vec3(5, 5, 0), saved));
        world.onPlayerLeave(LINK, com.game.api.proto.PlayerLeave.newBuilder().setSessionId(SESSION)
                .setPlayerId(PLAYER).build());
        start(2, saved);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(response(AssetOutcome.ASSET_OUTCOME_APPLIED, 0, true));
        assertThat(call(Rpc.DEBIT, debit(2, 500))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_REJECTED, AssetOpService.CURRENCY_INSUFFICIENT, true));
        assertThat(gold()).isEqualTo(70);
    }

    @Test
    void 没有归属围栏_RETRY_27003_不记账() {
        start(0, PlayerState.getDefaultInstance());
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        assertThat(call(Rpc.DEBIT, debit(1, 30))).isEqualTo(
                response(AssetOutcome.ASSET_OUTCOME_RETRY, AssetOpService.FROZEN, false));
        assertThat(call(Rpc.ABORT_DEBIT, debit(1, 30)).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_RETRY);
        assertThat(player.assetLedger().isPristine()).isTrue();
        assertThat(gold()).isEqualTo(100);
    }

    @Test
    void 流规则表覆盖除UNSPECIFIED外的全部流() {
        for (AssetStream stream : AssetStream.values()) {
            if (stream == AssetStream.ASSET_STREAM_UNSPECIFIED || stream == AssetStream.UNRECOGNIZED) {
                continue;
            }
            assertThat(AssetOpService.ruledStreams()).as(stream.name()).contains(stream);
            assertThat(AssetOpLedger.isValidStream(stream.getNumber())).isTrue();
        }
        assertThat(AssetOpService.allowedTx(AssetStream.ASSET_STREAM_TRADE_CREDIT))
                .containsOnlyKeys(4, 1);
    }
}
