package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.global.pb.GlobalWatchRequestRow;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakePlayerStatus;
import com.game.proto.AddObserverRequest;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 全球模式 163 的出口（{@link GlobalWatchService}）：每个出口的 tip 与指标 outcome，以及「业务拒绝持久化、重放与首答一致」。
 * SQL 权威与存储用替身（真 TiDB 的提交协议由 {@code GlobalMatchStoreIntegrationTest} 覆盖），观众 RPC 用可脚本化的 {@link FakeObserverDialer}。
 */
class GlobalWatchServiceTest {

    private static final long PLAYER = 1001;
    private static final long X = 7_000_000_077L;

    private final SqlSpectateStore store = mock(SqlSpectateStore.class);
    private final GlobalWatchAuthority authority = mock(GlobalWatchAuthority.class);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeObserverDialer observers = new FakeObserverDialer();
    private final String requestId = UUID.randomUUID().toString();
    private final String mark = Long.toUnsignedString(X) + ":" + requestId;
    private GlobalWatchService service;

    @BeforeEach
    void wire() {
        when(store.authority()).thenReturn(authority);
        service = new GlobalWatchService(store, players, observers, (player, deadline) -> "观众甲");
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId(FakePlayerStatus.GATE_INSTANCE)
                .setSessionId(FakePlayerStatus.sessionIdOf(playerId)).setZoneId(1).setPlayerId(playerId).setAccount("acc-" + playerId)
                .setOwnerRevision(1).build();
    }

    private WatchBattleRequest request() {
        return WatchBattleRequest.newBuilder().setBattleId(X).setRequestId(requestId).build();
    }

    private static Deadline budget() {
        return Deadline.after(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
    }

    /** 首次受理的原 UUID：还没有回执、是最新请求、已固定选到 X；在线、无票、无锁、落点在、抢标记成功、原 Add 已持久化。 */
    private void admissible() {
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.empty()));
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(7).setBattleInstanceId("inst-a")
                .setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).build();
        when(store.read(eq(X), any())).thenReturn(new SpectateStore.Snapshot(true, new SpectateStore.Record.Found(placement), 0L));
        when(authority.reserve(any(), anyLong(), anyString(), any())).thenReturn(SpectateStore.Acquire.OK);
        when(authority.prepare(any(), anyLong(), anyString(), any(), any(), any())).thenReturn(AddObserverRequest.newBuilder().setBattleId(X)
                .setObserverPlayerId(PLAYER).setSpectatorOperationId(requestId).setTargetInstanceId("inst-a").build());
        // 回执原样落库并回出（没有并发的首答）
        when(authority.respond(any(), anyString(), any(), any())).thenAnswer(call -> call.getArgument(2));
        players.online(PLAYER, 1, 7);
    }

    @Test
    void 会话没有绑定玩家_入口回缺少玩家身份_不落SQL_计internal() {
        GlobalWatchService.Verdict verdict = service.watch(session(0), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.NO_IDENTITY));
        assertThat(verdict.response().getErrorMessage().getParameters(0)).as("不是「服务器繁忙」").isEqualTo("缺少玩家身份");
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.INTERNAL);
        verifyNoInteractions(authority);
        verify(store).authority();
        verifyNoMoreInteractions(store);
        assertThat(players.reads).isEmpty();
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void battle回满员码_观战人数已满_回执持久化_计full_不换场() {
        admissible();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        SessionContext actor = session(PLAYER);

        GlobalWatchService.Verdict verdict = service.watch(actor, request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_FULL));
        assertThat(verdict.response().getBattleId()).isZero();
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.FULL);
        assertThat(observers.adds()).as("只发一次 Add，不换场").hasSize(1);
        // 原件置为待取消（标记回滚由恢复循环凭原 Remove 的取消正证明完成）；明确拒绝不再查 active
        verify(authority).observed(eq(actor), eq(PLAYER), eq(mark), eq(new Outcome.Replied(MatchTips.SPECTATE_FULL)), any());
        verify(authority, never()).active(any(), anyLong(), anyString(), any());
        verify(authority).respond(eq(actor), eq(requestId), eq(MatchTips.watchRejected(MatchTip.WATCH_FULL)), any());
    }

    @Test
    void battle回1004_该战斗不存在或已结束_计not_found() {
        admissible();
        observers.nextAdd(new Outcome.Replied(MatchTips.BATTLE_ROOM_NOT_FOUND));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.NOT_FOUND);
    }

    @Test
    void battle其它拒绝与没送达_该战斗当前无法观战_计rejected() {
        admissible();
        observers.nextAdd(new Outcome.Replied(MatchTips.INVALID_PARAMETER));
        observers.nextAdd(new Outcome.NotDelivered("连不上"));

        GlobalWatchService.Verdict refused = service.watch(session(PLAYER), request(), budget());
        GlobalWatchService.Verdict undelivered = service.watch(session(PLAYER), request(), budget());

        assertThat(refused.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
        assertThat(refused.outcome()).isEqualTo(WatchOutcome.REJECTED);
        assertThat(undelivered.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
        assertThat(undelivered.outcome()).isEqualTo(WatchOutcome.REJECTED);
    }

    @Test
    void 成功登记_计ok() {
        admissible();
        when(authority.active(any(), anyLong(), anyString(), any())).thenReturn(true);

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response().hasErrorMessage()).isFalse();
        assertThat(verdict.response().getBattleId()).isEqualTo(X);
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.OK);
    }

    @Test
    void 会话被替代_回会话不在线并持久化_计offline_不抢标记() {
        admissible();
        SessionContext stale = session(PLAYER).toBuilder().setSessionId(FakePlayerStatus.sessionIdOf(PLAYER) + 1).build();

        GlobalWatchService.Verdict verdict = service.watch(stale, request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_OFFLINE));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.OFFLINE);
        verify(authority).respond(eq(stale), eq(requestId), eq(MatchTips.watchRejected(MatchTip.WATCH_OFFLINE)), any());
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void 重放已存回执_原样读回_outcome与首答一致_不再往下判() {
        WatchBattleResponse stored = MatchTips.watchRejected(MatchTip.WATCH_FULL);
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setResponsePayload(stored.toByteString()).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(stored);
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.FULL);
        verify(authority, never()).entry(any(), anyLong(), any());
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void respond读回并发先落的首答_outcome按实际回出的应答计() {
        admissible();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        WatchBattleResponse first = MatchTips.watchRejected(MatchTip.WATCH_QUEUED);
        when(authority.respond(any(), anyString(), any(), any())).thenReturn(first);

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(first);
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.QUEUED);
    }

    @Test
    void 被同一玩家更新的请求顶替_回已在观战并持久化_不是服务器繁忙_不往下判() {
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, false));
        when(authority.respond(any(), anyString(), any(), any())).thenAnswer(call -> call.getArgument(2));
        SessionContext actor = session(PLAYER);

        GlobalWatchService.Verdict verdict = service.watch(actor, request(), budget());

        assertThat(verdict.response()).as("与 mmorpg 同口径：16016，不是 16004").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_ALREADY));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.ALREADY_WATCHING);
        verify(authority).respond(eq(actor), eq(requestId), eq(MatchTips.watchRejected(MatchTip.WATCH_ALREADY)), any());
        verify(authority, never()).entry(any(), anyLong(), any());
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void 原请求已被关闭_回该战斗当前无法观战并持久化_不是服务器繁忙() {
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setCancelled(true).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));
        when(authority.respond(any(), anyString(), any(), any())).thenAnswer(call -> call.getArgument(2));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
        assertThat(verdict.response().getErrorMessage().getId()).isEqualTo(MatchTips.BATTLE_NOT_WATCHABLE);
        verify(authority, never()).entry(any(), anyLong(), any());
        assertThat(observers.calls).isEmpty();
    }
}
