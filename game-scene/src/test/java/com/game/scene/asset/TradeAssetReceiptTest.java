package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.*;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.state.*;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.TestBagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemCatalog.ItemSpec;
import com.game.scene.testing.*;
import com.game.scene.world.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 面向实际scene入口验证完整实例与持久回执；存储回调替身不冒称真实SQL集成。 */
class TradeAssetReceiptTest {
    private static final long PLAYER = 1001;
    private static final String SECRET = "trade-receipt-test-only-secret-0123456789";
    private final ManualClock clock = new ManualClock();
    private final FakePlayerRepository repository = new FakePlayerRepository();
    private final RecordingAssetAudit audit = new RecordingAssetAudit();
    private final CurrencyService currency = new CurrencyService(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), clock);
    private final BagService bags = new BagService(TestBagTables.of(Map.of(1, new ItemSpec(1, 1, 0)), Map.of()),
            count -> { throw new AssertionError("既有实例交付不得铸造新ID"); }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
    private ScenePlayer player;
    private SceneWorld world;
    private AssetOpService assets;

    private void start(PlayerState state) {
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repository,
                new AtomicLong(9000)::incrementAndGet, clock, SceneMetrics.noop(), AssetOpService::checkLedgerOnLoad, PlayerSnapshots.NONE);
        Scene scene = world.createScene(1);
        repository.put(new PlayerData(PLAYER, 1, 1, 1, "", 1, 1, new Vec3(5, 5, 0), state));
        world.onPlayerEnter(1, PlayerEnter.newBuilder().setSessionId(11).setPlayerId(PLAYER).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repository.completeAll();
        player = world.playerById(PLAYER);
        bags.initializeOnLoad(player);
        assets = new AssetOpService(world, currency, bags, new AssetOpAuth(caller -> SECRET), clock);
    }

    private AssetOpRequest request(AssetStream stream, long seq, AssetBundle bundle) {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(stream).setStreamEpoch(1).setSeq(seq)
                .setCorrelationId(100 + seq).setTxType(stream == AssetStream.ASSET_STREAM_TRADE_DEBIT ? 3 : 4).setBundle(bundle).build();
    }

    private AssetOpResponse call(AssetRpc rpc, AssetOpRequest unsigned) {
        return assets.handle(rpc, AssetOpSignatures.sign(rpc, unsigned, AssetOpSignatures.CALLER_TRADE, SECRET, clock.epochMillis()));
    }

    private void saved() { repository.takeProgress().complete(PlayerRepository.ProgressResult.SAVED); }

    private static UnknownFieldSet extension(String value) {
        return UnknownFieldSet.newBuilder().addField(1001, UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFromUtf8(value)).build()).build();
    }

    @Test
    void 完整实例扣出及结果同存档_丢包重查_原ID到账_ACK后仍不重复应用() throws Exception {
        var item = BagItemState.newBuilder().setItemUuid(700).setConfigId(1).setStackSize(1).setPos(7).setAcquireSeq(9)
                .setUnknownFields(extension("装备词条-强化-宝石-未来字段")).build();
        var pet = PetEntry.newBuilder().setPetId(800).setPetTableId(1).setName("原宝宝").setLevel(10)
                .putAllocated(1, 7).putAptitude(2, 12000).setHealth(123).setMana(45).setCreatedAt(99)
                .addSkillTableIds(100).setUnknownFields(extension("宝宝未来字段")).build();
        start(PlayerState.newBuilder().setBag(BagState.newBuilder().addItems(item)).setPets(PetState.newBuilder().addPets(pet)).build());
        var debit = request(AssetStream.ASSET_STREAM_TRADE_DEBIT, 1, AssetBundle.newBuilder().addItemUuids(700).setPetId(800).build());
        var first = call(AssetRpc.DEBIT, debit);
        assertThat(first.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(first.getDurable()).isFalse();
        assertThat(player.bags().bag(BagType.INVENTORY).item(700)).isNull();
        assertThat(player.pets().find(800)).isNull();
        assertThat(BagItemState.parseFrom(first.getSnapshot().getItems(0).getInstanceProto()).getUnknownFields()).isEqualTo(item.getUnknownFields());
        assertThat(PetEntry.parseFrom(first.getSnapshot().getPet().getInstanceProto())).isEqualTo(pet);
        saved();
        var replay = call(AssetRpc.DEBIT, debit);
        assertThat(replay.getDurable()).isTrue();
        assertThat(replay.getSnapshot()).isEqualTo(first.getSnapshot());
        var restored = AssetOpLedger.restore(player.persistedState().getAssetLedger());
        assertThat(restored.receipt(3, 1, 1).getSnapshotProto()).isEqualTo(first.getSnapshot().toByteString());

        // 正式交易合同的信用腿清除了扣出选择字段，只携带由 scene 签出的完整原件。
        var credit = request(AssetStream.ASSET_STREAM_TRADE_CREDIT, 1, AssetBundle.newBuilder()
                .addAllItemInstances(replay.getSnapshot().getItemsList()).setPetInstance(replay.getSnapshot().getPet()).build());
        assertThat(call(AssetRpc.CREDIT, credit).getDurable()).isFalse();
        assertThat(player.bags().bag(BagType.INVENTORY).item(700)).isNotNull();
        assertThat(player.pets().toState().getPets(0)).isEqualTo(pet);
        var received = player.bags().toState().getItems(0);
        assertThat(received.toBuilder().clearPos().clearBagType().clearAcquireSeq().build())
                .isEqualTo(item.toBuilder().clearPos().clearBagType().clearAcquireSeq().build());
        saved();
        assertThat(call(AssetRpc.CREDIT, credit).getDurable()).isTrue();
        assertThat(player.bags().bag(BagType.INVENTORY).itemCount()).isEqualTo(1);
        assertThat(call(AssetRpc.ACK_RECEIPT, debit).getDurable()).isFalse();
        saved();
        assertThat(call(AssetRpc.ACK_RECEIPT, debit).getDurable()).isTrue();
        var retained = player.assetLedger().receipt(3, 1, 1);
        assertThat(retained.getSnapshotProto()).isEmpty();
        assertThat(retained.getCorrelationId()).isEqualTo(debit.getCorrelationId());
        assertThat(retained.getRequestDigest()).isEqualTo(AssetOpSignatures.requestDigest(debit));
        assertThat(player.assetLedger().classify(3, 1, 1)).isEqualTo(AssetSeqState.APPLIED);
        assertThat(call(AssetRpc.DEBIT, debit).getDurable()).isTrue();
        assertThat(call(AssetRpc.DEBIT, debit.toBuilder().setCorrelationId(999).build()).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(call(AssetRpc.ACK_RECEIPT, debit.toBuilder().setBundle(AssetBundle.getDefaultInstance()).build()).getOutcome())
                .isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
    }

    @Test
    void 最终SQL意图持久接管不等于资产到账_没有player_state确认仍不回durable() {
        var item=BagItemState.newBuilder().setItemUuid(700).setConfigId(1).setStackSize(1).build();
        start(PlayerState.newBuilder().setBag(BagState.newBuilder().addItems(item)).build());
        var debit=request(AssetStream.ASSET_STREAM_TRADE_DEBIT,1,AssetBundle.newBuilder().addItemUuids(700).build());
        assertThat(call(AssetRpc.DEBIT,debit).getDurable()).isFalse();
        var pendingProgress=repository.takeProgress();
        repository.setAutoFinalRegistration(false);
        world.onTakeoverRequested(PLAYER,1);
        var pendingFinal=repository.takeFinalRegistration();
        pendingFinal.complete(new PlayerRepository.FinalSaveRegistration.Durable(pendingFinal.receipt(),false));
        assertThat(player.persistedState().getAssetLedger().getStreamsCount()).isZero();
        assertThat(call(AssetRpc.DEBIT,debit).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
        assertThat(call(AssetRpc.DEBIT,debit).getDurable()).isFalse();
        // 原周期存盘的迟到失败也不能由TX1接管凭据替代为已到账。
        pendingProgress.complete(PlayerRepository.ProgressResult.FAILED);
        assertThat(call(AssetRpc.DEBIT,debit).getDurable()).isFalse();
    }

    @Test
    void 同操作改载荷拒绝_超大实例在副作用前拒绝() {
        var item = BagItemState.newBuilder().setItemUuid(700).setConfigId(1).setStackSize(1).build();
        start(PlayerState.newBuilder().setBag(BagState.newBuilder().addItems(item)).build());
        var debit = request(AssetStream.ASSET_STREAM_TRADE_DEBIT, 1, AssetBundle.newBuilder().addItemUuids(700).build());
        assertThat(call(AssetRpc.DEBIT, debit).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(call(AssetRpc.DEBIT, debit.toBuilder().setCorrelationId(999).build()).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        var oversized = AssetItemInstance.newBuilder().setItemUuid(701).setConfigId(1).setStackSize(1)
                .setInstanceProto(item.toBuilder().setItemUuid(701).setUnknownFields(extension("x".repeat(16384))).build().toByteString());
        var credit = request(AssetStream.ASSET_STREAM_TRADE_CREDIT, 1, AssetBundle.newBuilder().addItemUuids(701).addItemInstances(oversized).build());
        assertThat(call(AssetRpc.CREDIT, credit).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(player.bags().bag(BagType.INVENTORY).item(701)).isNull();
    }
}
