package com.game.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.PersistedAssetLedger;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.player.store.state.AssetOpLedgerState;
import com.game.player.store.state.AssetOpReceiptState;
import com.game.player.store.state.AssetOpStreamLedgerState;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** 邮件资产重投循环的单条故障隔离与退避推进。 */
class MailDeliveryWorkerTest {

    private static MailStore.Operation operation(long opId, long playerId, long seq) {
        byte[] bundle = AssetBundle.newBuilder()
                .addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(100))
                .build().toByteArray();
        return new MailStore.Operation(opId, playerId, 1000L + opId, 1L, seq, 10L, bundle, 77L + opId);
    }

    private static PersistedAssetLedger committedLedger(MailStore.Operation op, boolean applied) throws Exception {
        var stream = AssetOpStreamLedgerState.newBuilder()
                .setStream(5)
                .setStreamEpoch(op.epoch())
                .setMaxSeq(op.seq());
        for (int i = 0; i < 16; i++) {
            long seen = i == (op.seq() - 1) / 64 ? 1L << ((op.seq() - 1) % 64) : 0;
            stream.addSeenBits(seen).addAppliedBits(applied ? seen : 0);
        }
        stream.addReceipts(AssetOpReceiptState.newBuilder()
                .setSeq(op.seq())
                .setCorrelationId(op.id())
                .setRequestDigest(AssetOpSignatures.requestDigest(op.request())));
        return PersistedAssetLedger.restore(AssetOpLedgerState.newBuilder().addStreams(stream).build());
    }

    @Test
    void 投递异常与账本读取异常仍推进settle退避_不阻塞同批后续操作() throws Exception {
        MailStore store = mock(MailStore.class);
        ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
        PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);

        var op1 = operation(101, 11, 1);
        var op2 = operation(102, 12, 1);
        var op3 = operation(103, 13, 1);
        var committed3 = committedLedger(op3, true);

        when(store.due(any(), anyLong())).thenReturn(List.of(101L, 102L, 103L));
        when(store.lease(any(), eq(101L), anyLong(), anyLong())).thenReturn(op1);
        when(store.lease(any(), eq(102L), anyLong(), anyLong())).thenReturn(op2);
        when(store.lease(any(), eq(103L), anyLong(), anyLong())).thenReturn(op3);

        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(op1.request()), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("scene timeout")));
        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(op2.request()), any()))
                .thenReturn(CompletableFuture.completedFuture(ReliableAssetDelivery.Delivery.answered(
                        AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build())));
        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(op3.request()), any()))
                .thenReturn(CompletableFuture.completedFuture(ReliableAssetDelivery.Delivery.answered(
                        AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build())));

        when(ledger.read(11L)).thenReturn(new PersistedAssetLedgerReader.Read.Absent());
        when(ledger.read(12L)).thenThrow(new RuntimeException("ledger sql timeout"));
        when(ledger.read(13L)).thenReturn(new PersistedAssetLedgerReader.Read.Loaded(committed3));

        new MailDeliveryWorker(store, delivery, ledger).pollDue();

        verify(store).settle(any(), eq(op1), isNull());
        verify(store).settle(any(), eq(op2), isNull());
        verify(store).settle(any(), eq(op3), eq(committed3));
    }

    @Test
    void 投递超时但SQL账本已落盘时直接带证据结案() throws Exception {
        MailStore store = mock(MailStore.class);
        ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
        PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);

        var op = operation(201, 21, 1);
        var committed = committedLedger(op, true);

        when(store.lease(any(), eq(201L), anyLong(), anyLong())).thenReturn(op);
        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(op.request()), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("rpc reply lost")));
        when(ledger.read(21L)).thenReturn(new PersistedAssetLedgerReader.Read.Loaded(committed));

        new MailDeliveryWorker(store, delivery, ledger).process(201L);

        verify(store).settle(any(), eq(op), eq(committed));
    }

    @Test
    void 单条lease异常或结局翻转异常不阻断同批后续操作() throws Exception {
        MailStore store = mock(MailStore.class);
        ReliableAssetDelivery delivery = mock(ReliableAssetDelivery.class);
        PersistedAssetLedgerReader ledger = mock(PersistedAssetLedgerReader.class);

        var opFlipped = operation(302, 32, 1);
        var opOk = operation(303, 33, 1);
        var committedFlipped = committedLedger(opFlipped, false);
        var committedOk = committedLedger(opOk, true);

        when(store.due(any(), anyLong())).thenReturn(List.of(301L, 302L, 303L));
        when(store.lease(any(), eq(301L), anyLong(), anyLong())).thenThrow(new SQLException("lock timeout"));
        when(store.lease(any(), eq(302L), anyLong(), anyLong())).thenReturn(opFlipped);
        when(store.lease(any(), eq(303L), anyLong(), anyLong())).thenReturn(opOk);

        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(opFlipped.request()), any()))
                .thenReturn(CompletableFuture.completedFuture(ReliableAssetDelivery.Delivery.answered(
                        AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build())));
        when(delivery.deliver(eq(AssetRpc.CREDIT), eq(opOk.request()), any()))
                .thenReturn(CompletableFuture.completedFuture(ReliableAssetDelivery.Delivery.answered(
                        AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).build())));

        when(ledger.read(32L)).thenReturn(new PersistedAssetLedgerReader.Read.Loaded(committedFlipped));
        when(ledger.read(33L)).thenReturn(new PersistedAssetLedgerReader.Read.Loaded(committedOk));

        var worker = new MailDeliveryWorker(store, delivery, ledger);
        assertThatThrownBy(() -> worker.process(302L)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).settle(any(), eq(opFlipped), any());

        worker.pollDue();
        verify(store).settle(any(), eq(opOk), eq(committedOk));
    }
}
