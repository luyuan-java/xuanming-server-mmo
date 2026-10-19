package com.game.scene.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.FinalSaveReceipt;
import com.game.player.store.PlayerStore.FinalSaveStatus;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.scene.world.PlayerRepository.FinalSaveRegistration;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 仅手动推进两个执行器与时钟：不睡眠、不连库，不把这些结果冒充 SQL 或进程重启证据。 */
class FinalSaveRecoveryTest {
    private static final String SOURCE = "scene-a";
    private static final String ORIGINAL_SOURCE = "scene-origin";
    private static final String DIGEST = "a".repeat(64);
    private final PlayerStore store = mock(PlayerStore.class);
    private final ManualExecutor storage = new ManualExecutor();
    private final ManualExecutor logic = new ManualExecutor();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final FinalSaveRecovery recovery = new FinalSaveRecovery(store, storage, logic, SOURCE, clock::get);

    private static PlayerSave snapshot(long playerId, long epoch) {
        return new PlayerSave(playerId, epoch, 3, 2, new Vec3(42.5, 0, 0),
                PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(0.9)).build());
    }

    private static FinalSaveReceipt receipt(FinalSaveStatus status, long player, long epoch, String source) {
        return new FinalSaveReceipt(status, player, epoch, 1, source, DIGEST);
    }

    private void accepts(long player, long epoch) {
        when(store.dueFinalSaves(anyLong(),eq(16))).thenReturn(List.of(new PlayerStore.FinalSaveKey(player,epoch)));
        when(store.registerFinalSave(any(), any(), eq(SOURCE), any()))
                .thenReturn(receipt(FinalSaveStatus.ACCEPTED, player, epoch, SOURCE));
        when(store.completeFinalSave(eq(player), eq(epoch), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, player, epoch, SOURCE));
    }

    @Test
    void 登记与应用分成两个任务_持久接管回调只在逻辑执行器执行且不冒已到账() {
        accepts(7, 6);
        List<FinalSaveRegistration> results = new ArrayList<>();
        List<FinalSaveReceipt> applied = new ArrayList<>();
        recovery.onApplied(applied::add);
        recovery.submit(snapshot(7, 6), results::add);
        assertThat(results).isEmpty();
        assertThat(recovery.unregisteredCount()).isEqualTo(1);
        assertThat(storage.size()).isEqualTo(1);

        storage.runOne();
        assertThat(results).isEmpty();
        assertThat(recovery.unregisteredCount()).isZero();
        assertThat(storage.size()).isZero();
        verify(store, never()).completeFinalSave(anyLong(), anyLong(), any());
        logic.runOne();
        assertThat(results).singleElement().isInstanceOfSatisfying(FinalSaveRegistration.Durable.class, durable -> {
            assertThat(durable.applied()).as("TX1 回执不能冒充已写 player_state").isFalse();
            assertThat(durable.receipt().playerId()).isEqualTo(7);
            assertThat(durable.receipt().ownerEpoch()).isEqualTo(6);
            assertThat(durable.receipt().sourceInstance()).isEqualTo(SOURCE);
            assertThat(durable.receipt().payloadDigest()).isEqualTo(DIGEST);
        });
        recovery.sweep();
        storage.runOne(); // 真正due扫描只取键并安排一个应用任务。
        verify(store,never()).completeFinalSave(anyLong(),anyLong(),any());
        storage.runOne();
        assertThat(applied).as("应用通知也必须回到逻辑执行器").isEmpty();
        logic.drain();
        assertThat(results).as("TX2 不把一个注册回调再执行第二次").hasSize(1);
        assertThat(applied).containsExactly(receipt(FinalSaveStatus.APPLIED, 7, 6, SOURCE));
        var order = inOrder(store);
        order.verify(store).registerFinalSave(any(), any(), eq(SOURCE), any());
        order.verify(store).completeFinalSave(eq(7L), eq(6L), any());
    }

    @Test
    void 已APPLIED登记回执不再重复安排应用() {
        when(store.registerFinalSave(any(), any(), eq(SOURCE), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 7, 6, SOURCE));
        List<FinalSaveRegistration> results = new ArrayList<>();
        recovery.submit(snapshot(7, 6), results::add);
        storage.runOne();
        logic.runOne();
        assertThat(results).singleElement().isInstanceOfSatisfying(FinalSaveRegistration.Durable.class,
                durable -> assertThat(durable.applied()).isTrue());
        assertThat(storage.size()).isZero();
        verify(store, never()).completeFinalSave(anyLong(), anyLong(), any());
    }

    @Test
    void 登记应答异常保留冻结快照_时间推进后仍以相同bytes来源与原epoch重投() {
        when(store.registerFinalSave(any(), any(), eq(SOURCE), any()))
                .thenThrow(new IllegalStateException("模拟提交后应答不可见"))
                .thenReturn(receipt(FinalSaveStatus.ACCEPTED, 7, 6, SOURCE));
        when(store.completeFinalSave(eq(7L), eq(6L), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 7, 6, SOURCE));
        List<FinalSaveRegistration> results = new ArrayList<>();
        PlayerSave frozen = snapshot(7, 6);
        recovery.submit(frozen, results::add);
        storage.runOne();
        assertThat(results).isEmpty();
        assertThat(recovery.unregisteredCount()).isEqualTo(1);
        assertThat(logic.size()).isZero();
        clock.addAndGet(60_000);
        recovery.sweep();
        storage.drain();
        logic.drain();
        assertThat(results).singleElement().isInstanceOf(FinalSaveRegistration.Durable.class);
        assertThat(recovery.unregisteredCount()).isZero();
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<com.game.player.store.PlayerRow> rows = ArgumentCaptor.forClass(com.game.player.store.PlayerRow.class);
        verify(store, times(2)).registerFinalSave(rows.capture(), bytes.capture(), eq(SOURCE), any());
        assertThat(bytes.getAllValues().get(0)).containsExactly(frozen.state().toByteArray());
        assertThat(bytes.getAllValues().get(1)).containsExactly(bytes.getAllValues().get(0));
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getPlayerId()).isEqualTo(7);
            assertThat(row.getOwnerEpoch()).isEqualTo(6);
        });
    }

    @Test
    void 存储池拒绝登记不得回成功_下轮可重新入原池且只回调一次() {
        ManualExecutor initiallyFull = new ManualExecutor();
        initiallyFull.reject = true;
        FinalSaveRecovery subject = new FinalSaveRecovery(store, initiallyFull, logic, SOURCE, clock::get);
        accepts(7, 6);
        List<FinalSaveRegistration> results = new ArrayList<>();
        subject.submit(snapshot(7, 6), results::add);
        assertThat(subject.unregisteredCount()).isEqualTo(1);
        assertThat(results).isEmpty();
        verify(store, never()).registerFinalSave(any(), any(), any(), any());
        initiallyFull.reject = false;
        clock.addAndGet(60_000);
        subject.sweep();
        initiallyFull.drain();
        logic.drain();
        assertThat(results).singleElement().isInstanceOf(FinalSaveRegistration.Durable.class);
        assertThat(subject.unregisteredCount()).isZero();
        verify(store, times(1)).registerFinalSave(any(), any(), eq(SOURCE), any());
    }

    @Test
    void 同键不同冻结快照冲突不能替换在途终态_原等待者仍收到原凭据() {
        accepts(7, 6);
        List<FinalSaveRegistration> first = new ArrayList<>();
        List<FinalSaveRegistration> conflict = new ArrayList<>();
        recovery.submit(snapshot(7, 6), first::add);
        recovery.submit(new PlayerSave(7, 6, 99, 2, Vec3.ORIGIN), conflict::add);
        logic.drain();
        assertThat(conflict).containsExactly(new FinalSaveRegistration.Conflict());
        assertThat(first).isEmpty();
        storage.drain();
        logic.drain();
        assertThat(first).singleElement().isInstanceOf(FinalSaveRegistration.Durable.class);
        verify(store, times(1)).registerFinalSave(any(), any(), eq(SOURCE), any());
    }

    @Test
    void 同一冻结终态的并发等待者共享一次登记且各自只回调一次() {
        accepts(7, 6);
        List<FinalSaveRegistration> first = new ArrayList<>();
        List<FinalSaveRegistration> second = new ArrayList<>();
        PlayerSave frozen = snapshot(7, 6);
        recovery.submit(frozen, first::add);
        recovery.submit(frozen, second::add);
        assertThat(storage.size()).isEqualTo(1);
        storage.drain();
        logic.drain();
        assertThat(first).hasSize(1);
        assertThat(second).containsExactlyElementsOf(first);
        verify(store, times(1)).registerFinalSave(any(), any(), eq(SOURCE), any());
    }

    @Test
    void 停机前没有接管凭据明确unclean_取消未跑任务但保留未登记计数() {
        List<FinalSaveRegistration> results = new ArrayList<>();
        recovery.submit(snapshot(7, 6), results::add);
        recovery.close();
        recovery.close();
        storage.drain();
        logic.drain();
        assertThat(results).containsExactly(new FinalSaveRegistration.Unconfirmed());
        assertThat(recovery.unregisteredCount()).isEqualTo(1);
        verify(store, never()).registerFinalSave(any(), any(), any(), any());
        recovery.sweep();
        assertThat(storage.size()).isZero();
    }

    @Test
    void 新协调器只按持久待办原键完成_不能用新实例来源重新登记或改绑原epoch() {
        when(store.dueFinalSaves(clock.get(), 16)).thenReturn(List.of(new PlayerStore.FinalSaveKey(7, 6)));
        when(store.completeFinalSave(eq(7L), eq(6L), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 7, 6, ORIGINAL_SOURCE));
        recovery.sweep();
        recovery.sweep();
        assertThat(storage.size()).as("扫描在途时不会重复塞满原存储池").isEqualTo(1);
        storage.drain();
        verify(store, times(1)).completeFinalSave(eq(7L), eq(6L), any());
        verify(store, never()).registerFinalSave(any(), any(), any(), any());
        assertThat(logic.size()).isZero();
    }

    @Test
    void 非瞬时应用失败保留SQL意图并延后_不删不改绑且之后仍能扫描完成() {
        when(store.dueFinalSaves(anyLong(), eq(16))).thenReturn(List.of(new PlayerStore.FinalSaveKey(7, 6)));
        when(store.completeFinalSave(eq(7L), eq(6L), any()))
                .thenThrow(new IllegalStateException("模拟不可立即修复的SQL状态"))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 7, 6, ORIGINAL_SOURCE));
        recovery.sweep();
        storage.drain();
        ArgumentCaptor<Long> delayedUntil = ArgumentCaptor.forClass(Long.class);
        verify(store).postponeFinalSave(eq(7L), eq(6L), delayedUntil.capture(), eq("sql_unconfirmed"), any());
        assertThat(delayedUntil.getValue()).isBetween(clock.get() + 5_000, clock.get() + 5_999);
        assertThat(recovery.unregisteredCount()).as("持久待办并非未登记RAM计数").isZero();
        clock.addAndGet(60_000);
        recovery.sweep();
        storage.drain();
        verify(store, times(2)).completeFinalSave(eq(7L), eq(6L), any());
        verify(store, never()).registerFinalSave(any(), any(), any(), any());
    }

    @Test
    void 已登记后停止只取消本地应用任务_不会撤销接管凭据或报未登记() {
        accepts(7, 6);
        List<FinalSaveRegistration> results = new ArrayList<>();
        recovery.submit(snapshot(7, 6), results::add);
        storage.runOne();
        assertThat(recovery.unregisteredCount()).isZero();
        recovery.close();
        storage.drain();
        logic.drain();
        assertThat(results).singleElement().isInstanceOfSatisfying(FinalSaveRegistration.Durable.class,
                durable -> assertThat(durable.applied()).isFalse());
        verify(store, never()).completeFinalSave(anyLong(), anyLong(), any());
        verify(store, times(1)).registerFinalSave(any(), any(), eq(SOURCE), any());
    }

    @Test
    void 失去归属或内容冲突是明确终结_不能重绑新epoch也不再提交应用() {
        when(store.registerFinalSave(any(), any(), eq(SOURCE), any()))
                .thenReturn(receipt(FinalSaveStatus.FENCED, 7, 6, SOURCE))
                .thenReturn(receipt(FinalSaveStatus.CONFLICT, 8, 6, SOURCE));
        List<FinalSaveRegistration> results = new ArrayList<>();
        recovery.submit(snapshot(7, 6), results::add);
        recovery.submit(snapshot(8, 6), results::add);
        storage.drain();
        logic.drain();
        assertThat(results).containsExactly(new FinalSaveRegistration.Fenced(), new FinalSaveRegistration.Conflict());
        assertThat(recovery.unregisteredCount()).as("冲突终态保留隔离RAM，不能算干净退出").isEqualTo(1);
        verify(store, never()).completeFinalSave(anyLong(), anyLong(), any());
        clock.addAndGet(60_000);
        recovery.sweep();
        storage.drain();
        verify(store, times(2)).registerFinalSave(any(), any(), eq(SOURCE), any());
    }

    @Test
    void 启动仅绑定原调度器_关闭取消后续扫描且不能重复启动() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(500L),
                eq(TimeUnit.MILLISECONDS));
        recovery.start(scheduler);
        assertThatThrownBy(() -> recovery.start(scheduler)).isInstanceOf(IllegalStateException.class);
        recovery.close();
        recovery.close();
        verify(scheduled, times(1)).cancel(false);
        assertThatThrownBy(() -> recovery.start(scheduler)).isInstanceOf(IllegalStateException.class);
        recovery.sweep();
        assertThat(storage.size()).isZero();
    }

    @Test
    void 单轮扫描多个大payload键最多排一个应用_剩余键下轮继续不挤占多份blob内存() {
        var first = new PlayerStore.FinalSaveKey(7, 6);
        var second = new PlayerStore.FinalSaveKey(8, 6);
        when(store.dueFinalSaves(anyLong(), eq(16)))
                .thenReturn(List.of(first, second)).thenReturn(List.of(second));
        when(store.completeFinalSave(eq(7L), eq(6L), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 7, 6, ORIGINAL_SOURCE));
        when(store.completeFinalSave(eq(8L), eq(6L), any()))
                .thenReturn(receipt(FinalSaveStatus.APPLIED, 8, 6, ORIGINAL_SOURCE));
        recovery.sweep();
        storage.runOne();
        assertThat(storage.size()).as("key列表不加载blob，同时最多排一个大payload应用").isEqualTo(1);
        storage.runOne();
        verify(store).completeFinalSave(eq(7L), eq(6L), any());
        verify(store, never()).completeFinalSave(eq(8L), eq(6L), any());
        recovery.sweep();
        storage.drain();
        verify(store).completeFinalSave(eq(8L), eq(6L), any());
    }

    @Test
    void 逻辑线程拒绝Durable或Fenced结果_独立记unclean且拒绝先于RAM义务移除可见() throws Exception {
        for(var status:List.of(FinalSaveStatus.ACCEPTED,FinalSaveStatus.FENCED)) {
            var reference=new java.util.concurrent.atomic.AtomicReference<FinalSaveRecovery>();
            Executor rejecting=callback->{
                assertThat(reference.get().unregisteredCount()).as("不能先移除再公布投递失败").isEqualTo(1);
                assertThat(reference.get().undeliveredResultCount()).isPositive();
                throw new RejectedExecutionException("确定性拒绝逻辑投递");
            };
            var subject=new FinalSaveRecovery(store,storage,rejecting,SOURCE,clock::get);reference.set(subject);
            when(store.registerFinalSave(any(),any(),eq(SOURCE),any())).thenReturn(receipt(status,7,6,SOURCE));
            subject.submit(snapshot(7,6),ignored->{throw new AssertionError("拒绝后不能执行回调");});
            storage.runOne();
            assertThat(subject.unregisteredCount()).as("SQL接管/围栏结局不因投递失败撤销").isZero();
            assertThat(subject.awaitRegistered(0)).isZero();
            assertThat(subject.undeliveredResultCount()).as("SQL义务与结果投递分别计数").isEqualTo(1);
            assertThat(subject.awaitingAppliedCount()).isEqualTo(status==FinalSaveStatus.ACCEPTED?1:0);
            subject.close();
            assertThat(subject.undeliveredResultCount()).isEqualTo(1);
        }
        verify(store,never()).completeFinalSave(anyLong(),anyLong(),any());
    }

    @Test
    void 结果已接入逻辑队列但未执行也不能计为送达_执行后才回零() throws Exception {
        when(store.registerFinalSave(any(),any(),eq(SOURCE),any())).thenReturn(receipt(FinalSaveStatus.ACCEPTED,7,6,SOURCE));
        List<FinalSaveRegistration> results=new ArrayList<>();
        recovery.submit(snapshot(7,6),results::add);storage.runOne();
        assertThat(recovery.unregisteredCount()).isZero();
        assertThat(recovery.awaitRegistered(0)).isZero();
        assertThat(recovery.undeliveredResultCount()).isEqualTo(1);
        assertThat(results).isEmpty();
        logic.drain();
        assertThat(recovery.undeliveredResultCount()).isZero();
        assertThat(results).singleElement().isInstanceOf(FinalSaveRegistration.Durable.class);
    }

    @Test
    void 结果回调异常保留独立失败计数_不能撤销SQL凭据或伪称已送达() {
        when(store.registerFinalSave(any(),any(),eq(SOURCE),any())).thenReturn(receipt(FinalSaveStatus.ACCEPTED,7,6,SOURCE));
        recovery.submit(snapshot(7,6),ignored->{throw new IllegalStateException("模拟逻辑结局处理失败");});
        storage.runOne();logic.runOne();
        assertThat(recovery.unregisteredCount()).isZero();
        assertThat(recovery.awaitingAppliedCount()).isEqualTo(1);
        assertThat(recovery.undeliveredResultCount()).isEqualTo(1);
        verify(store,never()).completeFinalSave(anyLong(),anyLong(),any());
    }

    @Test
    void TX1应答丢失后TX2失败已经持久退避_本地短退避认回ACCEPTED不能提前再应用() {
        var key=new PlayerStore.FinalSaveKey(7,6);
        AtomicLong dueAt=new AtomicLong(clock.get());
        when(store.registerFinalSave(any(),any(),eq(SOURCE),any()))
                .thenThrow(new IllegalStateException("TX1已提交但应答丢失"))
                .thenReturn(receipt(FinalSaveStatus.ACCEPTED,7,6,SOURCE));
        when(store.dueFinalSaves(anyLong(),eq(16))).thenAnswer(call->
                call.<Long>getArgument(0)>=dueAt.get()?List.of(key):List.of());
        when(store.completeFinalSave(eq(7L),eq(6L),any()))
                .thenThrow(new IllegalStateException("TX2锁等待失败"))
                .thenReturn(receipt(FinalSaveStatus.APPLIED,7,6,SOURCE));
        doAnswer(call->{dueAt.set(call.<Long>getArgument(2));return null;})
                .when(store).postponeFinalSave(eq(7L),eq(6L),anyLong(),eq("sql_unconfirmed"),any());
        when(store.probeFinalSave(eq(7L),eq(6L),any())).thenReturn(receipt(FinalSaveStatus.ACCEPTED,7,6,SOURCE));
        List<FinalSaveRegistration> results=new ArrayList<>();
        recovery.submit(snapshot(7,6),results::add);storage.runOne();
        assertThat(recovery.unregisteredCount()).isEqualTo(1);
        recovery.sweep();storage.drain();
        verify(store,times(1)).completeFinalSave(eq(7L),eq(6L),any());
        assertThat(dueAt.get()).isBetween(clock.get()+5_000,clock.get()+5_999);
        clock.addAndGet(250); // 已超过200..239ms登记重试，但仍早于SQL的5..6s退避。
        recovery.sweep();storage.drain();logic.drain();
        verify(store,times(2)).registerFinalSave(any(),any(),eq(SOURCE),any());
        verify(store,times(1)).completeFinalSave(eq(7L),eq(6L),any());
        assertThat(results).singleElement().isInstanceOfSatisfying(FinalSaveRegistration.Durable.class,
                result->assertThat(result.applied()).isFalse());
        clock.set(dueAt.get()-1);recovery.sweep();storage.drain();
        verify(store,times(1)).completeFinalSave(eq(7L),eq(6L),any());
        clock.incrementAndGet();recovery.sweep();storage.drain();logic.drain();
        verify(store,times(2)).completeFinalSave(eq(7L),eq(6L),any());
        assertThat(results).hasSize(1);
        assertThat(recovery.awaitingAppliedCount()).isZero();
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean reject;
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("测试池满");
            tasks.add(command);
        }
        int size() { return tasks.size(); }
        void runOne() { tasks.remove().run(); }
        void drain() {
            int remaining = 100;
            while (!tasks.isEmpty()) {
                assertThat(remaining--).as("单轮工作必须有界，不允许即时无限重试").isPositive();
                runOne();
            }
        }
    }
}
