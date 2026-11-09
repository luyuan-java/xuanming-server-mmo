package com.game.match.rating;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.PlayerInboxService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.completion.pb.BattlePlayerCompletionRow;
import com.game.match.global.GlobalMatchStore;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.TerminalDeliveryKind;
import com.game.match.metrics.MatchMetrics.TerminalDeliveryOutcome;
import com.game.match.metrics.MetricLabels;
import com.game.proto.player_inbox.BattleSettlementInboxRequest;
import com.game.proto.player_inbox.BattleSettlementInboxResponse;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** 只观测现有投递边界：ACK/签名收据与 SQL 回写分别失败，均不能制造成功计数。 */
class BattleCompletionReplayMetricsTest {
    private static final String SECRET = "终局指标回归独立密钥0123456789abcdef0123456789";
    private static final String METER = "game.match.outcome.outbox.delivery";
    private static final long NOW = 1_000_000;

    @Test void Kafka确认与本地持久回写都成功后才计一次published() throws Exception {
        try (var f = new Fixture()) {
            var row = result(101);
            f.results(row);
            doAnswer(call -> {
                assertEquals(0, f.total());
                assertEquals(row.getOriginal(), call.getArgument(1));
                return CompletableFuture.completedFuture(null);
            }).when(f.publisher).publish(any(), any());
            doAnswer(call -> { assertEquals(0, f.total()); return null; }).when(f.store).resultPublished(row);

            f.replay.round();

            var order = inOrder(f.publisher, f.store);
            order.verify(f.publisher).publish(any(), eq(row.getOriginal()));
            order.verify(f.store).resultPublished(row);
            f.only("result", "published");
        }
    }

    @Test void Kafka失败空确认及超时都只记failed_不回写published() throws Exception {
        for (int failure = 0; failure < 4; failure++) {
            try (var f = new Fixture()) {
                var row = result(102);
                f.results(row);
                switch (failure) {
                    case 0 -> when(f.publisher.publish(any(), any())).thenThrow(new IllegalStateException("发送结果未知"));
                    case 1 -> when(f.publisher.publish(any(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("ACK未知")));
                    case 2 -> when(f.publisher.publish(any(), any())).thenReturn(null);
                    case 3 -> when(f.publisher.publish(any(), any())).thenReturn(new CompletableFuture<>() {
                        @Override public Void get(long timeout, TimeUnit unit) throws TimeoutException {
                            assertEquals(3250, timeout); assertEquals(TimeUnit.MILLISECONDS, unit);
                            throw new TimeoutException("回执未到，不等待真实时钟");
                        }
                    });
                    default -> throw new AssertionError();
                }
                f.replay.round();
                f.only("result", "failed");
                verify(f.store, never()).resultPublished(any());
            }
        }
    }

    @Test void Kafka已确认但SQL提交未知只记store_error_保留后续行处理() throws Exception {
        try (var f = new Fixture()) {
            var first = result(103); var second = result(104);
            f.results(first, second);
            doThrow(new IllegalStateException("提交UNKNOWN")).when(f.store).resultPublished(first);
            f.replay.round();
            assertEquals(1, f.count("result", "store_error"));
            assertEquals(1, f.count("result", "published"));
            assertEquals(2, f.total());
            verify(f.store).resultPublished(second);
        }
    }

    @Test void 全局摘要协议或正文损坏计corrupt_没有发布尝试() throws Exception {
        var good = result(105);
        var malformed = ByteString.copyFrom(new byte[]{(byte) 0xff});
        for (var row : List.of(good.toBuilder().clearOriginalDigest().build(),
                good.toBuilder().setOutcomeProtocolVersion(0).build(),
                good.toBuilder().setOriginal(malformed).setOriginalDigest(BattleCompletionStore.digest(malformed)).build())) {
            try (var f = new Fixture()) {
                f.results(row);
                f.replay.round();
                f.only("result", "corrupt");
                verifyNoInteractions(f.publisher);
                verify(f.store, never()).resultPublished(any());
            }
        }
    }

    @Test void 首投与waiting的pending及applied均在收据持久回写后计数() throws Exception {
        for (boolean first : List.of(true, false)) {
            for (var state : List.of(PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING, PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED)) {
                try (var f = new Fixture()) {
                    var row = person(first ? 0 : 71);
                    f.people(first, row);
                    f.receipt(first, state, 71);
                    boolean applied = state == PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED;
                    doAnswer(call -> { assertEquals(0, f.total()); return null; }).when(f.store).inboxReceipt(row, 71, applied);
                    f.replay.round();
                    f.only(first ? "first" : "waiting", applied ? "applied" : "pending");
                    var order = inOrder(f.store, f.inbox);
                    order.verify(f.store).claimInboxAttempt(row);
                    if (first) {
                        order.verify(f.inbox).enqueueBattleSettlement(argThat(request -> originalIdentity(row, request)));
                        verify(f.inbox, never()).lookupBattleSettlement(any());
                    } else {
                        order.verify(f.inbox).lookupBattleSettlement(argThat(request -> originalIdentity(row, request)));
                        verify(f.inbox, never()).enqueueBattleSettlement(any());
                    }
                    order.verify(f.store).inboxReceipt(row, 71, applied);
                }
            }
        }
    }

    @Test void 认领失败与认领SQL未知分开计数_都不发RPC() throws Exception {
        for (boolean first : List.of(true, false)) {
            for (boolean failedStore : List.of(false, true)) {
                try (var f = new Fixture()) {
                    var row = person(first ? 0 : 71);
                    f.people(first, row);
                    if (failedStore) when(f.store.claimInboxAttempt(row)).thenThrow(new IllegalStateException("原owner或SQL未知"));
                    else when(f.store.claimInboxAttempt(row)).thenReturn(false);
                    f.replay.round();
                    f.only(first ? "first" : "waiting", failedStore ? "store_error" : "claim_lost");
                    verifyNoInteractions(f.inbox);
                    verify(f.store, never()).inboxReceipt(any(), anyLong(), anyBoolean());
                }
            }
        }
    }

    @Test void 个人原件损坏仍沿用先认领后校验_只记corrupt() throws Exception {
        var good = person(0);
        for (var row : List.of(good.toBuilder().clearOriginalDigest().build(), good.toBuilder().clearOwnerAccount().build(),
                good.toBuilder().clearOwnerRevision().build(), good.toBuilder().clearIssuedAtMs().build())) {
            try (var f = new Fixture()) {
                f.people(true, row);
                f.replay.round();
                f.only("first", "corrupt");
                verify(f.store).claimInboxAttempt(row);
                verifyNoInteractions(f.inbox);
                verify(f.store, never()).inboxReceipt(any(), anyLong(), anyBoolean());
            }
        }
    }

    @Test void Inbox未知状态和空回复不能记pending或applied() throws Exception {
        for (boolean first : List.of(true, false)) {
            for (boolean empty : List.of(false, true)) {
                try (var f = new Fixture()) {
                    var row = person(first ? 0 : 71);
                    f.people(first, row);
                    if (empty) {
                        if (first) when(f.inbox.enqueueBattleSettlement(any())).thenReturn(CompletableFuture.completedFuture(null));
                        else when(f.inbox.lookupBattleSettlement(any())).thenReturn(CompletableFuture.completedFuture(null));
                    } else f.receipt(first, PlayerInboxItemState.PLAYER_INBOX_ITEM_UNSPECIFIED, 71);
                    f.replay.round();
                    f.only(first ? "first" : "waiting", "failed");
                    verify(f.store, never()).inboxReceipt(any(), anyLong(), anyBoolean());
                }
            }
        }
    }

    @Test void InboxRPC异常空句柄或永久seq被替换只记failed() throws Exception {
        for (int failure = 0; failure < 3; failure++) {
            try (var f = new Fixture()) {
                f.people(false, person(71));
                if (failure == 0) when(f.inbox.lookupBattleSettlement(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("远端结果未知")));
                else if (failure == 1) when(f.inbox.lookupBattleSettlement(any())).thenReturn(null);
                else f.receipt(false, PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED, 72);
                f.replay.round();
                f.only("waiting", "failed");
                verify(f.store, never()).inboxReceipt(any(), anyLong(), anyBoolean());
            }
        }
    }

    @Test void Inbox已签APPLIED但回写未知仍只记store_error() throws Exception {
        for (boolean first : List.of(true, false)) {
            try (var f = new Fixture()) {
                var row = person(first ? 0 : 71);
                f.people(first, row);
                f.receipt(first, PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED, 71);
                doThrow(new IllegalStateException("本地SQL提交未知")).when(f.store).inboxReceipt(row, 71, true);
                f.replay.round();
                f.only(first ? "first" : "waiting", "store_error");
            }
        }
    }

    @Test void 扫描未取得行及空页不制造逐行结局() throws Exception {
        try (var f = new Fixture()) {
            f.replay.round();
            assertEquals(0, f.total());
            when(f.store.pendingResults(anyLong())).thenThrow(new IllegalStateException("扫描未知，没有行可归类"));
            assertThrows(IllegalStateException.class, f.replay::round);
            assertEquals(0, f.total());
            verifyNoInteractions(f.publisher, f.inbox);
        }
    }

    @Test void 中断保留原停止语义_只计当前失败行不造剩余deferred() throws Exception {
        try (var f = new Fixture()) {
            var first = result(106); var untouched = result(107);
            f.results(first, untouched);
            when(f.publisher.publish(any(), any())).thenReturn(new CompletableFuture<>() {
                @Override public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                    throw new InterruptedException("中断当前投递");
                }
            });
            try {
                f.replay.round();
                assertTrue(Thread.currentThread().isInterrupted());
                f.only("result", "failed");
                verify(f.publisher, times(1)).publish(any(), any());
                verify(f.store, never()).resultPublished(any());
                verify(f.store, never()).pendingInbox(anyBoolean(), anyLong(), anyLong(), anyLong());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void 指标故障不能改变持久成功或阻断后续行() throws Exception {
        var broken = mock(MatchMetrics.class);
        doThrow(new IllegalStateException("指标后端故障")).when(broken).terminalDelivery(any(), any());
        try (var f = new Fixture(broken)) {
            var first = result(108); var second = result(109);
            f.results(first, second);
            assertDoesNotThrow(f.replay::round);
            verify(f.store).resultPublished(first);
            verify(f.store).resultPublished(second);
            verify(broken, times(2)).terminalDelivery(TerminalDeliveryKind.RESULT, TerminalDeliveryOutcome.PUBLISHED);
        }
    }

    private static BattleCompletionRow result(long battle) {
        var original = BattleCompletionStoreCases.complete(battle).toBuilder().setFinishedAtMs(123).build().toByteString();
        return BattleCompletionRow.newBuilder().setBattleId(battle).setTerminalDecision(1).setOutcomeProtocolVersion(1)
                .setSourceKind(2).setOriginal(original).setOriginalDigest(BattleCompletionStore.digest(original)).build();
    }

    private static BattlePlayerCompletionRow person(long sequence) {
        var original = BattleCompletionStoreCases.complete(110).getSettlements(0).toByteString();
        return BattlePlayerCompletionRow.newBuilder().setBattleId(110).setPlayerId(1).setOwnerAccount("original-owner")
                .setOwnerRevision(7).setIssuedAtMs(123).setInboxSeq(sequence).setOriginal(original)
                .setOriginalDigest(BattleCompletionStore.digest(original)).build();
    }

    private static boolean originalIdentity(BattlePlayerCompletionRow row, BattleSettlementInboxRequest request) {
        return request.getPlayerId() == row.getPlayerId() && request.getBattleId() == row.getBattleId()
                && request.getExpectedOwnerAccount().equals(row.getOwnerAccount()) && request.getExpectedOwnerRevision() == row.getOwnerRevision()
                && request.getIssuedAtMs() == row.getIssuedAtMs() && request.getSettlementPayload().equals(row.getOriginal());
    }

    private static BattleSettlementInboxResponse receipt(String method, BattleSettlementInboxRequest request,
                                                          PlayerInboxItemState state, long sequence) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(12).putInt(PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE)
                .putLong(request.getIssuedAtMs()).array());
        var response = BattleSettlementInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setBattleId(request.getBattleId())
                .setSeq(sequence).setState(state).setRequestDigest(ServiceCallSignatures.digest(request))
                .setContentSha256(ByteString.copyFrom(digest.digest(request.getSettlementPayload().toByteArray()))).build();
        return ServiceCallSignatures.sign(method + "/reply", response, "inbox", SECRET, NOW);
    }

    private static final class Fixture implements AutoCloseable {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final BattleCompletionStore store = mock(BattleCompletionStore.class);
        final GlobalMatchStore formations = mock(GlobalMatchStore.class);
        final PlayerInboxService inbox = mock(PlayerInboxService.class);
        final BattleCompletionReplay.Results publisher = mock(BattleCompletionReplay.Results.class);
        final BattleCompletionReplay replay;

        Fixture() { this(null); }
        Fixture(MatchMetrics override) {
            var metrics = override == null ? new MatchMetrics(meters, new MetricLabels(id -> false)) : override;
            when(formations.terminalDeadlineCandidates(anyLong(), anyLong())).thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(0, List.of()));
            when(store.pendingResults(anyLong())).thenReturn(List.of());
            when(store.pendingInbox(anyBoolean(), anyLong(), anyLong(), anyLong())).thenReturn(new BattleCompletionStore.PendingInboxBatch(0, List.of()));
            when(publisher.publish(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
            replay = new BattleCompletionReplay(store, formations, inbox, publisher, SECRET, () -> NOW, metrics);
        }

        void results(BattleCompletionRow... rows) { when(store.pendingResults(anyLong())).thenReturn(List.of(rows)); }
        void people(boolean first, BattlePlayerCompletionRow row) {
            when(store.pendingInbox(eq(first), anyLong(), anyLong(), anyLong())).thenReturn(new BattleCompletionStore.PendingInboxBatch(row.getBattleId(), List.of(row)));
            when(store.claimInboxAttempt(row)).thenReturn(true);
        }
        void receipt(boolean first, PlayerInboxItemState state, long sequence) {
            if (first) when(inbox.enqueueBattleSettlement(any())).thenAnswer(call -> CompletableFuture.completedFuture(
                    BattleCompletionReplayMetricsTest.receipt(PlayerInboxService.BATTLE_ENQUEUE_METHOD, call.getArgument(0), state, sequence)));
            else when(inbox.lookupBattleSettlement(any())).thenAnswer(call -> CompletableFuture.completedFuture(
                    BattleCompletionReplayMetricsTest.receipt(PlayerInboxService.BATTLE_LOOKUP_METHOD, call.getArgument(0), state, sequence)));
        }
        double count(String kind, String outcome) { return meters.get(METER).tags("kind", kind, "outcome", outcome).counter().count(); }
        double total() { return meters.find(METER).counters().stream().mapToDouble(Counter::count).sum(); }
        void only(String kind, String outcome) { assertEquals(1, count(kind, outcome)); assertEquals(1, total()); }
        @Override public void close() { replay.close(); meters.close(); }
    }
}
