package com.game.scene.asset;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetStream;
import com.game.api.proto.PlayerEnter;
import com.game.player.store.state.PlayerState;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.TestBagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资产通道传输测试的场景夹具：一个场景、一名在场玩家（{@link #PLAYER}，100 银两），真 {@link AssetOpService}（验签密钥
 * {@link AssetOpAuthTest#SECRET}）。构造在调用线程上完成；之后对场景的访问必须都经同一个逻辑线程（测试里的单线程执行器）。
 */
final class AssetChannelFixture {

    static final long PLAYER = 1001;
    static final long STREAM_EPOCH = 1_700_000_000_000L;
    static final int GOLD = 0;

    final ManualClock clock = new ManualClock();
    final FakePlayerRepository repo = new FakePlayerRepository();
    final SceneWorld world;
    final ScenePlayer player;
    final AssetOpService service;

    AssetChannelFixture() {
        RecordingAssetAudit audit = new RecordingAssetAudit();
        CurrencyService currency = new CurrencyService(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), clock);
        BagService bags = new BagService(TestBagTables.of(Map.of(), Map.of()), count -> null, audit,
                GainAnomalyDetector.off(), SceneMetrics.noop());
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(9000)::incrementAndGet, clock, SceneMetrics.noop(), AssetOpService::checkLedgerOnLoad,
                PlayerSnapshots.NONE);
        Scene scene = world.createScene(1);
        repo.put(new PlayerData(PLAYER, 1, 1, 1, "", 1, 1, new Vec3(5, 5, 0), PlayerState.getDefaultInstance()));
        world.onPlayerEnter(1, PlayerEnter.newBuilder().setSessionId(11).setPlayerId(PLAYER).setSceneId(scene.sceneId())
                .setOwnerEpoch(1).build());
        repo.completeAll();
        player = world.playerById(PLAYER);
        currency.add(player, GOLD, 100, Reason.GM_GRANT);
        service = new AssetOpService(world, currency, bags, new AssetOpAuth(c -> AssetOpAuthTest.SECRET), clock);
    }

    /** 帮会扣款请求（未签名）。 */
    static AssetOpRequest debit(long playerId, long seq, long amount) {
        return AssetOpRequest.newBuilder().setPlayerId(playerId).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(seq)
                .setStreamEpoch(STREAM_EPOCH).setTxType(24).setCorrelationId(500 + seq)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(GOLD)
                        .setAmount(amount)))
                .build();
    }

    /** 按夹具时钟现签（与调用方签名器同一份代码）。 */
    AssetOpRequest signed(AssetRpc rpc, AssetOpRequest request) {
        return AssetOpSignatures.sign(rpc, request, AssetOpSignatures.CALLER_GUILD, AssetOpAuthTest.SECRET,
                clock.epochMillis());
    }
}
