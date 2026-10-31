package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.BattleNodeService;
import com.game.common.deadline.Deadline;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.global.GlobalWatchAuthority.RequestCursor;
import com.game.match.global.GlobalWatchAuthority.RequestPurge;
import com.game.match.global.GlobalWatchAuthority.Settlement;
import com.game.match.global.pb.GlobalWatchingRow;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.PlacementDialer;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakePlacementDialer;
import com.game.proto.AddObserverRequest;
import com.game.proto.ObserverAdmissionState;
import com.game.proto.QueryObserverRequest;
import com.game.proto.QueryObserverResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.RemoveObserverResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 全球观战原件的恢复一步（{@link SqlSpectateStore#drive}）：原 Remove 的正证明优先，拿不到证明时把目录判定传给 {@link GlobalWatchAuthority#settled}（同一笔事务里的成局判定与关闭见
 * {@code GlobalMatchStoreIntegrationTest}）。权威与 battle 都是替身。
 */
class SqlSpectateStoreDriveTest {

    private static final long PLAYER = 1001;
    private static final long X = 7_000_000_077L;
    private static final String INSTANCE = "inst-a";
    private static final int NODE = 7;

    private final String operation = UUID.randomUUID().toString();
    private final GlobalWatchAuthority authority = mock(GlobalWatchAuthority.class);
    private final BattleNodeService battle = mock(BattleNodeService.class);
    private final FakePlacementDialer dialer = new FakePlacementDialer(battle);
    private final FakeBattleNodes directory = new FakeBattleNodes();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SqlSpectateStore store = new SqlSpectateStore(authority, dialer, directory, new MatchMetrics(meters, new MetricLabels(id -> false)));

    private GlobalWatchingRow row(int state) {
        AddObserverRequest add = AddObserverRequest.newBuilder().setBattleId(X).setObserverPlayerId(PLAYER).setSpectatorOperationId(operation)
                .setAdmitBeforeMs(1).setTargetInstanceId(INSTANCE).build();
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(X).setBattleNodeId(NODE).setBattleInstanceId(INSTANCE).setRpcHost("10.1.1.1").setRpcPort(21200).build();
        return GlobalWatchingRow.newBuilder().setPlayerId(PLAYER).setMark(Long.toUnsignedString(X) + ":" + operation).setOperationId(operation).setState(state)
                .setAddRequest(add.toByteString()).setPlacement(placement.toByteString()).build();
    }

    private GlobalWatchingRow leased(int state) {
        GlobalWatchingRow work = row(state);
        when(authority.lease(any(), any())).thenReturn(work);
        return work;
    }

    private void removeReplies(RemoveObserverResponse reply) {
        when(battle.removeObserver(any(RemoveObserverRequest.class))).thenReturn(CompletableFuture.completedFuture(reply));
    }

    private void queryReplies(QueryObserverResponse reply) {
        when(battle.queryObserver(any(QueryObserverRequest.class))).thenReturn(CompletableFuture.completedFuture(reply));
    }

    private double unproven(String reason) {
        return meters.get("game.match.watch.unproven.close").tag("reason", reason).counter().count();
    }

    @BeforeEach
    void settledReturnsPending() {
        when(authority.settled(any(), anyBoolean(), any(), anyBoolean(), any())).thenReturn(Settlement.PENDING);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void 拿到取消证明_不读目录() {
        GlobalWatchingRow work = leased(4);
        RemoveObserverResponse proof = RemoveObserverResponse.newBuilder().setCancelled(true).setSpectatorOperationId(operation).setTargetInstanceId(INSTANCE).build();
        removeReplies(proof);

        store.drive(work, Deadline.after(4000));

        verify(authority).settled(eq(work), eq(false), eq(proof), eq(false), any());
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void 新实例回空应答_目录里同节点号已是别的实例_传给结算_计不带证明关闭() {
        GlobalWatchingRow work = leased(4);
        removeReplies(RemoveObserverResponse.getDefaultInstance());
        directory.add(FakeBattleNodes.node(NODE, "inst-new", 21200));
        when(authority.settled(any(), anyBoolean(), any(), eq(true), any())).thenReturn(Settlement.INSTANCE_REPLACED);

        store.drive(work, Deadline.after(4000));

        verify(authority).settled(eq(work), eq(false), eq(RemoveObserverResponse.getDefaultInstance()), eq(true), any());
        assertThat(unproven("instance_replaced")).isEqualTo(1);
    }

    @Test
    void 死端点没有回包_目录里还是原实例或查不到或读失败_都不算换了() {
        GlobalWatchingRow work = leased(4);
        dialer.unavailable(PlacementDialer.Kind.TIMEOUT);

        store.drive(work, Deadline.after(4000));
        directory.add(FakeBattleNodes.node(NODE, INSTANCE, 21200));
        store.drive(work, Deadline.after(4000));
        directory.readFailed = true;
        store.drive(work, Deadline.after(4000));

        verify(authority, times(3)).settled(eq(work), eq(false), eq(null), eq(false), any());
        assertThat(unproven("instance_replaced")).isZero();
    }

    @Test
    void 没有证明但成局已结束_结算判定之后计数() {
        GlobalWatchingRow work = leased(4);
        dialer.unavailable(PlacementDialer.Kind.NOT_DELIVERED);
        when(authority.settled(any(), anyBoolean(), any(), anyBoolean(), any())).thenReturn(Settlement.FORMATION_ENDED);

        store.drive(work, Deadline.after(4000));

        assertThat(unproven("formation_ended")).isEqualTo(1);
    }

    @Test
    void 已登记的原件_原实例明确回ACTIVE_不读目录_不发Remove() {
        GlobalWatchingRow work = leased(3);
        queryReplies(QueryObserverResponse.newBuilder().setState(ObserverAdmissionState.OBSERVER_ADMISSION_ACTIVE).setSpectatorOperationId(operation).setTargetInstanceId(INSTANCE).build());

        store.drive(work, Deadline.after(4000));

        verify(authority).settled(eq(work), eq(true), eq(null), eq(false), any());
        assertThat(directory.lookups).isEmpty();
        verify(battle, never()).removeObserver(any(RemoveObserverRequest.class));
    }

    @Test
    void 已登记的原件_Query调不通_目录里已换实例_不再发Remove_直接传给结算() {
        GlobalWatchingRow work = leased(3);
        dialer.unavailable(PlacementDialer.Kind.NOT_DELIVERED);
        directory.add(FakeBattleNodes.node(NODE, "inst-new", 21200));

        store.drive(work, Deadline.after(4000));

        verify(authority).settled(eq(work), eq(false), eq(null), eq(true), any());
    }

    @Test
    void 已登记的原件_新实例对Query回空应答_目录里已换实例_同样传给结算() {
        GlobalWatchingRow work = leased(3);
        queryReplies(QueryObserverResponse.getDefaultInstance());
        directory.add(FakeBattleNodes.node(NODE, "inst-new", 21200));

        store.drive(work, Deadline.after(4000));

        verify(authority).settled(eq(work), eq(false), eq(null), eq(true), any());
    }

    @Test
    void 租约被别人拿着_什么都不做() {
        when(authority.lease(any(), any())).thenReturn(null);

        store.drive(row(4), Deadline.after(4000));

        verify(authority, never()).settled(any(), anyBoolean(), any(), anyBoolean(), any());
        assertThat(directory.lookups).isEmpty();
    }

    // ================================================================ 请求历史的保留期清理

    @Test
    void 清理一页_游标往前走_计数() {
        RequestCursor next = new RequestCursor(5, PLAYER, operation);
        when(authority.purgeRequests(eq(RequestCursor.START), anyInt(), any())).thenReturn(new RequestPurge(next, 3));
        when(authority.purgeRequests(eq(next), anyInt(), any())).thenReturn(new RequestPurge(RequestCursor.START, 0));

        store.purgeRequests();
        store.purgeRequests();

        verify(authority).purgeRequests(eq(RequestCursor.START), anyInt(), any());
        verify(authority).purgeRequests(eq(next), anyInt(), any());
        assertThat(meters.get("game.match.watch.requests.purged").counter().count()).isEqualTo(3);
    }

    @Test
    void 清理失败_不往外抛_游标不动_下一轮重试() {
        when(authority.purgeRequests(any(), anyInt(), any())).thenThrow(new Deadline.DependencyException("sql down"));

        store.purgeRequests();
        store.purgeRequests();

        verify(authority, times(2)).purgeRequests(eq(RequestCursor.START), anyInt(), any());
    }
}
