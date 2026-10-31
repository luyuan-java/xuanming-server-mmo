package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
import com.game.proto.eSpectateVisibility;
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

    /** 已建房（state 4）的成局 X 读出来的样子：落点带着建房原件里冻结的可见性。 */
    private static SpectateStore.Snapshot formation(eSpectateVisibility visibility) {
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(7).setBattleInstanceId("inst-a")
                .setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).setSpectateVisibility(visibility.getNumber()).build();
        return new SpectateStore.Snapshot(true, new SpectateStore.Record.Found(placement), 0L);
    }

    /** 首次受理的原 UUID：还没有回执、是最新请求、已固定选到 X；在线、无票、无锁、落点在、抢标记成功、原 Add 已持久化。 */
    private void admissible() {
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.empty()));
        when(store.read(eq(X), any())).thenReturn(formation(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC));
        when(authority.reserve(any(), anyLong(), anyString(), any())).thenReturn(SpectateStore.Acquire.OK);
        when(authority.prepare(any(), anyLong(), anyString(), any(), any(), any())).thenReturn(AddObserverRequest.newBuilder().setBattleId(X)
                .setObserverPlayerId(PLAYER).setSpectatorOperationId(requestId).setTargetInstanceId("inst-a").build());
        // 回执原样落库并回出（没有并发的首答）
        when(authority.respond(any(), anyString(), any(), any())).thenAnswer(call -> call.getArgument(2));
        when(authority.observedFull(any(), anyLong(), anyString(), any(), any(), any())).thenAnswer(call -> call.getArgument(4));
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

    // ================================================================ 可见性闸（mmorpg battle-spectate-tiered-fanout.md §10 第 6 条）

    @Test
    void 可见性闸_按id观战私密局_清退旧观战之前就回不存在或已结束_回执持久化_不保留不发Add_计not_found() {
        admissible();
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.of("7000000099:" + UUID.randomUUID())));
        when(store.read(eq(X), any())).thenReturn(formation(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE));
        SessionContext actor = session(PLAYER);

        GlobalWatchService.Verdict verdict = service.watch(actor, request(), budget());

        assertThat(verdict.response()).as("与成局不在逐字节相同，不泄露私密局存在与否").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(verdict.outcome()).as("回执只存 tip：重放无从区分私密与不在，统一记 not_found").isEqualTo(WatchOutcome.NOT_FOUND);
        verify(store, never()).releaseForRequest(any(), anyString(), anyString(), any());
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
        verify(authority, never()).prepare(any(), anyLong(), anyString(), any(), any(), any());
        verify(authority).respond(eq(actor), eq(requestId), eq(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND)), any());
        assertThat(players.reads).as("互斥检查之后就判定：不读在线目录").noneMatch(read -> read.startsWith("presence:"));
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void 可见性闸_按id观战不在的成局_同样在清退旧观战之前拒绝_两者对客户端与旧观战都一样() {
        admissible();
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.of("7000000099:" + UUID.randomUUID())));
        when(store.read(eq(X), any())).thenReturn(new SpectateStore.Snapshot(false, new SpectateStore.Record.Absent(), 0L));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.NOT_FOUND);
        verify(store, never()).releaseForRequest(any(), anyString(), anyString(), any());
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void 可见性闸_随机挑中的成局读回落点后复核_私密局同样回不存在_不保留不发Add() {
        admissible();
        GlobalWatchRequestRow unselected = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(unselected, true));
        when(store.pickRandom(anyDouble(), any())).thenReturn(new SpectateStore.Pick.Member(Long.toUnsignedString(X), 0L, 0L));
        when(authority.select(any(), anyString(), anyLong(), any())).thenReturn(X);
        when(store.read(eq(X), any())).thenReturn(formation(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE));
        WatchBattleRequest random = WatchBattleRequest.newBuilder().setRequestId(requestId).build();

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), random, budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        verify(authority).select(any(), eq(requestId), eq(X), any());
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
        assertThat(observers.calls).isEmpty();
    }

    @Test
    void 可见性闸_公开局照常登记_指定场在清退前后各读一次() {
        admissible();
        when(authority.active(any(), anyLong(), anyString(), any())).thenReturn(true);

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.OK);
        verify(store, org.mockito.Mockito.times(2)).read(eq(X), any());
    }

    // ================================================================ 大厅档（mmorpg battle-spectate-tiered-fanout.md §15.6）

    private static final String RELAY_SECRET = "global-watch-lobby-secret-0123456789abcdef";

    private final com.game.match.testing.InMemorySpectateTierStore tiers = new com.game.match.testing.InMemorySpectateTierStore();
    private final SpectateRelayDialer relayDialer = mock(SpectateRelayDialer.class);
    private final com.game.common.token.SpectateTickets tickets = com.game.common.token.SpectateTickets.ofUtf8(RELAY_SECRET);
    private final com.game.match.testing.FakeBattleNodes directory = new com.game.match.testing.FakeBattleNodes();

    private void relayOn() {
        service.withRelay(new SpectateRelay(tiers, relayDialer, tickets, java.time.Duration.ofSeconds(60), new BattleInstanceCheck(directory)));
    }

    private void assertLobbyTicket(WatchBattleResponse response, String instance) {
        assertThat(response.getBattleId()).isEqualTo(X);
        assertThat(response.getDelivery()).isEqualTo(com.game.proto.match.eSpectateDelivery.SPECTATE_DELIVERY_LOBBY);
        var verdict = tickets.verify(response.getSpectateTicket(), PLAYER, X, 0);
        assertThat(verdict.ok()).as("凭证验得过").isTrue();
        assertThat(verdict.payload().getBattleInstanceId()).isEqualTo(instance);
    }

    /** 回执里不存凭证：凭证有 TTL，重放要重新签。 */
    private WatchBattleResponse storedLobby() {
        return MatchTips.watchLobby(X, com.google.protobuf.ByteString.EMPTY);
    }

    @Test
    void 大厅档_直连席满了转LOBBY_结论与原Add转待取消同一笔事务落库_回执只存结案不存凭证_计lobby() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-reply", 9_000L));
        SessionContext actor = session(PLAYER);

        GlobalWatchService.Verdict verdict = service.watch(actor, request(), budget());

        assertLobbyTicket(verdict.response(), "inst-reply");
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.LOBBY);
        verify(authority).observedFull(eq(actor), eq(PLAYER), eq(mark), eq(new Outcome.Replied(MatchTips.SPECTATE_FULL)), eq(storedLobby()), any());
        verify(authority, never()).observed(any(), anyLong(), anyString(), any(), any());
        verify(authority, never()).respond(any(), anyString(), any(), any());
        assertThat(tiers.peek(X)).isEqualTo(com.game.proto.eSpectateTier.SPECTATE_TIER_RELAY);
    }

    @Test
    void 大厅档_打开中继调不通_回繁忙不持久化_LOBBY回执已落库_同UUID重放再准入_不再发Add() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Unavailable("TIMEOUT"));

        GlobalWatchService.Verdict first = service.watch(session(PLAYER), request(), budget());

        assertThat(first.response()).as("Add 用掉大半预算之后 Attach 超时不是结论").isEqualTo(MatchTips.watchRejected(MatchTip.BUSY));
        assertThat(first.outcome()).isEqualTo(WatchOutcome.INTERNAL);
        verify(authority).observedFull(any(), anyLong(), anyString(), any(), eq(storedLobby()), any());
        verify(authority, never()).respond(any(), anyString(), any(), any());
        assertThat(tiers.peek(X)).as("没调通不记档位").isEqualTo(com.game.proto.eSpectateTier.SPECTATE_TIER_DIRECT);

        GlobalWatchRequestRow concluded = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setResponsePayload(storedLobby().toByteString()).setCancelled(true).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(concluded, true));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-reply", 9_000L));

        GlobalWatchService.Verdict replay = service.watch(session(PLAYER), request(), budget());

        assertLobbyTicket(replay.response(), "inst-reply");
        assertThat(replay.outcome()).as("恢复循环已把原件关闭（cancelled）也一样：回执优先").isEqualTo(WatchOutcome.LOBBY);
        assertThat(observers.adds()).hasSize(1);
    }

    @Test
    void 大厅档_打开中继被battle明确拒绝_回本次结论_不改LOBBY回执() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Rejected(MatchTips.BATTLE_ROOM_NOT_FOUND));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.NOT_FOUND);
        verify(authority).observedFull(any(), anyLong(), anyString(), any(), eq(storedLobby()), any());
        verify(authority, never()).respond(any(), anyString(), any(), any());
    }

    @Test
    void 大厅档_并发先落的首答不是LOBBY_以首答为准_不打开中继() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        WatchBattleResponse first = MatchTips.watchRejected(MatchTip.WATCH_ALREADY);
        when(authority.observedFull(any(), anyLong(), anyString(), any(), any(), any())).thenReturn(first);

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(first);
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.ALREADY_WATCHING);
        verify(relayDialer, never()).attach(any(), any(), any());
    }

    @Test
    void 大厅档_满员结案提交失败_回繁忙_同UUID重发原Add拿到首次满员码_仍转LOBBY() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        when(authority.observedFull(any(), anyLong(), anyString(), any(), any(), any())).thenThrow(new Deadline.DependencyException("提交未知"));
        SessionContext actor = session(PLAYER);

        assertThat(service.watch(actor, request(), budget()).response()).isEqualTo(MatchTips.watchRejected(MatchTip.BUSY));

        // 行还停在原 Add 已持久：重试走同一份原 Add，battle 的墓碑回首次满员码（ObserverOperationRoomTest 钉住），不是别的码
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.of(mark)));
        org.mockito.Mockito.doAnswer(call -> call.getArgument(4)).when(authority).observedFull(any(), anyLong(), anyString(), any(), any(), any());
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-reply", 9_000L));

        GlobalWatchService.Verdict retry = service.watch(actor, request(), budget());

        assertLobbyTicket(retry.response(), "inst-reply");
        assertThat(retry.outcome()).isEqualTo(WatchOutcome.LOBBY);
    }

    @Test
    void 大厅档_battle的其它拒绝不写LOBBY回执() {
        admissible();
        relayOn();
        observers.nextAdd(new Outcome.Replied(MatchTips.INVALID_PARAMETER));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_WATCHABLE));
        verify(authority, never()).observedFull(any(), anyLong(), anyString(), any(), any(), any());
        verify(authority).observed(any(), anyLong(), anyString(), any(), any());
        verify(relayDialer, never()).attach(any(), any(), any());
    }

    @Test
    void 大厅档_已是中继档而同节点号已是别的实例_按房间不存在结案_不签凭证() {
        admissible();
        relayOn();
        tiers.raise(X, com.game.proto.eSpectateTier.SPECTATE_TIER_RELAY, budget());
        directory.add(com.game.match.testing.FakeBattleNodes.node(7, "inst-new", 21200));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        assertThat(verdict.response().getSpectateTicket().isEmpty()).isTrue();
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.NOT_FOUND);
        verify(authority).respond(any(), eq(requestId), eq(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND)), any());
    }

    @Test
    void 大厅档_已是中继档而目录查不到或读失败_不判_照常签凭证() {
        admissible();
        relayOn();
        tiers.raise(X, com.game.proto.eSpectateTier.SPECTATE_TIER_RELAY, budget());
        directory.readFailed = true;

        assertLobbyTicket(service.watch(session(PLAYER), request(), budget()).response(), "inst-a");
    }

    @Test
    void 大厅档_已是中继档_不写占用行不发Add_不调battle_以LOBBY结案() {
        admissible();
        relayOn();
        tiers.raise(X, com.game.proto.eSpectateTier.SPECTATE_TIER_RELAY, budget());

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertLobbyTicket(verdict.response(), "inst-a");
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
        verify(authority, never()).prepare(any(), anyLong(), anyString(), any(), any(), any());
        assertThat(observers.adds()).isEmpty();
        verify(relayDialer, never()).attach(any(), any(), any());
        verify(authority).respond(any(), eq(requestId), eq(storedLobby()), any());
    }

    @Test
    void 大厅档_同一UUID已写过占用行_照直连路径走完原Add_不留没人收尾的直连席() {
        admissible();
        relayOn();
        tiers.raise(X, com.game.proto.eSpectateTier.SPECTATE_TIER_RELAY, budget());
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.of(mark)));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(observers.adds()).hasSize(1);
        assertThat(verdict.response().getDelivery()).isNotEqualTo(com.game.proto.match.eSpectateDelivery.SPECTATE_DELIVERY_LOBBY);
    }

    @Test
    void 大厅档_重放LOBBY结案的意图_重新准入并签新凭证_不改持久回执_不写占用行() {
        relayOn();
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setResponsePayload(storedLobby().toByteString()).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.empty()));
        when(store.read(eq(X), any())).thenReturn(formation(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC));
        when(relayDialer.attach(any(), any(), any())).thenReturn(new SpectateRelayDialer.Outcome.Enabled("inst-reply", 9_000L));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertLobbyTicket(verdict.response(), "inst-reply");
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.LOBBY);
        verify(authority, never()).respond(any(), anyString(), any(), any());
        verify(authority, never()).reserve(any(), anyLong(), anyString(), any());
    }

    @Test
    void 大厅档_重放时已被顶替或已排队或已开战_回当前结论_不签新凭证_不改持久回执() {
        relayOn();
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setResponsePayload(storedLobby().toByteString()).build();

        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, false));
        assertThat(service.watch(session(PLAYER), request(), budget()).response()).as("被新请求顶替").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_ALREADY));

        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(true, Optional.empty()));
        assertThat(service.watch(session(PLAYER), request(), budget()).response()).as("已排队").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_QUEUED));

        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.empty()));
        players.inBattle(PLAYER, true);
        assertThat(service.watch(session(PLAYER), request(), budget()).response()).as("已开战").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_IN_BATTLE));

        verify(relayDialer, never()).attach(any(), any(), any());
        verify(authority, never()).respond(any(), anyString(), any(), any());
    }

    @Test
    void 大厅档_重放时这场已不在或开关已关_回本次结论_不改持久回执() {
        GlobalWatchRequestRow history = GlobalWatchRequestRow.newBuilder().setPlayerId(PLAYER).setRequestId(requestId).setBattleId(X)
                .setResponsePayload(storedLobby().toByteString()).build();
        when(authority.begin(any(), any(), any())).thenReturn(new GlobalWatchAuthority.Request(history, true));

        assertThat(service.watch(session(PLAYER), request(), budget()).response()).as("开关已关").isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_FULL));

        relayOn();
        when(authority.entry(any(), anyLong(), any())).thenReturn(new SpectateStore.Entry(false, Optional.empty()));
        when(store.read(eq(X), any())).thenReturn(new SpectateStore.Snapshot(false, new SpectateStore.Record.Absent(), 0L));
        assertThat(service.watch(session(PLAYER), request(), budget()).response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_NOT_FOUND));
        verify(authority, never()).respond(any(), anyString(), any(), any());
    }

    @Test
    void 大厅档_读档位失败_回繁忙_不持久化_原UUID可重试() {
        admissible();
        relayOn();
        tiers.failing = true;

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.BUSY));
        verify(authority, never()).respond(any(), anyString(), any(), any());
    }

    @Test
    void 大厅档_关闭开关时满员仍回16021_没有转LOBBY() {
        admissible();
        observers.nextAdd(new Outcome.Replied(MatchTips.SPECTATE_FULL));

        GlobalWatchService.Verdict verdict = service.watch(session(PLAYER), request(), budget());

        assertThat(verdict.response()).isEqualTo(MatchTips.watchRejected(MatchTip.WATCH_FULL));
        assertThat(verdict.outcome()).isEqualTo(WatchOutcome.FULL);
    }
}
