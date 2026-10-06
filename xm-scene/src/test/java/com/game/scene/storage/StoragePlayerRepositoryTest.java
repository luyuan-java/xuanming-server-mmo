package com.game.scene.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.player.store.OwnerState;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.HandOffResult;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.HandOffAttempts;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.LoadResult;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

class StoragePlayerRepositoryTest {

    private final PlayerStore store = mock(PlayerStore.class);
    /** 记录投递到「逻辑线程」的任务，由测试手动执行，验证回调不在 load 调用栈内。 */
    private final List<Runnable> logicTasks = new ArrayList<>();
    private final Executor logic = logicTasks::add;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SceneMetrics metrics = new SceneMetrics(meters);

    @Test
    void 等级列超出int的无符号值饱和到int上限_进场再压回等级上限() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1001);
        row.setLevel(4_294_967_295L);

        assertThat(StoragePlayerRepository.toData(row, null).level()).isEqualTo(Integer.MAX_VALUE);
        row.setLevel(30);
        assertThat(StoragePlayerRepository.toData(row, null).level()).isEqualTo(30);
    }

    @Test
    void 加载命中_映射成PlayerData并投递回逻辑线程() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1001);
        row.setOwnerEpoch(6);
        row.setClassId(3);
        row.setGender(1);
        row.setAppearanceId("look");
        row.setLevel(4);
        row.setSceneConfigId(1);
        row.setPosX(1.5);
        row.setPosY(2.5);
        row.setPosZ(3.5);
        when(store.findPlayer(1001)).thenReturn(Optional.of(row));
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1001, results::add);

        assertThat(results).isEmpty();
        logicTasks.forEach(Runnable::run);
        assertThat(results).containsExactly(new LoadResult.Found(
                new PlayerData(1001, 6, 3, 1, "look", 4, 1, new Vec3(1.5, 2.5, 3.5))));
    }

    @Test
    void 加载带上持久化数据() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1001);
        row.setOwnerEpoch(6);
        when(store.findPlayer(1001)).thenReturn(Optional.of(row));
        PlayerState state = PlayerState.newBuilder().setFacing(Facing.newBuilder().setZ(90)).build();
        when(store.loadState(1001)).thenReturn(state);
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1001, results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).singleElement().isInstanceOfSatisfying(LoadResult.Found.class,
                found -> assertThat(found.data().state()).isEqualTo(state));
    }

    /**
     * 回合制战斗用到的两样东西随加载读出（scene-battle-spec §13.3）：角色名（战斗快照的 {@code name}，取自 player 行；缺了按空串）
     * 与结算账本（{@code player_state.battle_ledger}，库里这份就是落库快照，进场即判 durable）。
     */
    @Test
    void 加载带上角色名与战斗结算账本_没有名字按空串() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1001);
        row.setOwnerEpoch(6);
        row.setName("回合制玩家");
        when(store.findPlayer(1001)).thenReturn(Optional.of(row));
        PlayerState state = PlayerState.newBuilder().setBattleLedger(com.game.player.store.state.BattleLedgerState.newBuilder()
                .addApplied(com.game.player.store.state.BattleLedgerEntry.newBuilder().setBattleId(7).setAppliedAtMs(1_700_000_000_007L))
                .addApplied(com.game.player.store.state.BattleLedgerEntry.newBuilder().setBattleId(0x8000_0000_0000_0001L)
                        .setAppliedAtMs(1_700_000_000_008L))).build();
        when(store.loadState(1001)).thenReturn(state);
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1001, results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).singleElement().isInstanceOfSatisfying(LoadResult.Found.class, found -> {
            assertThat(found.data().name()).isEqualTo("回合制玩家");
            assertThat(found.data().state().getBattleLedger()).isEqualTo(state.getBattleLedger());
        });
        row.setName(null);
        assertThat(StoragePlayerRepository.toData(row, null).name()).as("库里没有名字（旧数据）不让加载失败").isEmpty();
    }

    @Test
    void 玩法数据损坏按加载失败处理() {
        PlayerRow row = new PlayerRow();
        row.setPlayerId(1001);
        when(store.findPlayer(1001)).thenReturn(Optional.of(row));
        when(store.loadState(1001)).thenThrow(new IllegalStateException("player_state 解析失败"));
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1001, results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).singleElement().isInstanceOf(LoadResult.Failed.class);
    }

    @Test
    void 在线存盘_不释放_结局投递回逻辑线程() {
        PlayerState state = PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(1)).build();
        when(store.saveStateHeld(any(), any())).thenReturn(true, false);
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<ProgressResult> results = new ArrayList<>();

        repository.saveProgress(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9), state), results::add);
        repository.saveProgress(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9), state), results::add);

        assertThat(results).as("回调不在调用栈内").isEmpty();
        logicTasks.forEach(Runnable::run);
        assertThat(results).containsExactly(ProgressResult.SAVED, ProgressResult.FENCED);
        verify(store, times(2)).saveStateHeld(any(), org.mockito.ArgumentMatchers.eq(state));
        verify(store, org.mockito.Mockito.never()).saveStateAndRelease(any(), any());
        assertThat(writes("progress", "saved").count()).isEqualTo(1);
        assertThat(writes("progress", "fenced").count()).isEqualTo(1);
    }

    @Test
    void 在线存盘被线程池拒绝_回调FAILED() {
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new RejectingExecutorService(), logic, metrics);
        List<ProgressResult> results = new ArrayList<>();

        repository.saveProgress(new PlayerSave(1001, 9, 4, 2, Vec3.ORIGIN), results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).containsExactly(ProgressResult.FAILED);
        assertThat(writes("progress", "rejected").count()).isEqualTo(1);
    }

    @Test
    void 加载未命中与异常() {
        when(store.findPlayer(1)).thenReturn(Optional.empty());
        when(store.findPlayer(2)).thenThrow(new IllegalStateException("db down"));
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1, results::add);
        repository.load(2, results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isInstanceOf(LoadResult.NotFound.class);
        assertThat(results.get(1)).isInstanceOf(LoadResult.Failed.class);
    }

    @Test
    void 存储线程池拒绝_按加载失败异步回调() {
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new RejectingExecutorService(), logic, metrics);
        List<LoadResult> results = new ArrayList<>();

        repository.load(1, results::add);

        assertThat(results).isEmpty();
        logicTasks.forEach(Runnable::run);
        assertThat(results).singleElement().isInstanceOf(LoadResult.Failed.class);
    }

    @Test
    void 写回带上owner_epoch围栏字段并释放归属() {
        when(store.saveStateAndRelease(any(), any())).thenReturn(false);
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        ArgumentCaptor<PlayerRow> row = ArgumentCaptor.forClass(PlayerRow.class);
        verify(store).saveStateAndRelease(row.capture(), any());
        assertThat(row.getValue().getPlayerId()).isEqualTo(1001L);
        assertThat(row.getValue().getOwnerEpoch()).isEqualTo(9L);
        assertThat(row.getValue().getLevel()).isEqualTo(4);
        assertThat(row.getValue().getSceneConfigId()).isEqualTo(2);
        assertThat(row.getValue().getPosX()).isEqualTo(7);
        assertThat(row.getValue().getPosY()).isEqualTo(8);
        assertThat(row.getValue().getPosZ()).isEqualTo(9);
        assertThat(repository.writeFailures()).as("围栏拒绝不是故障").isZero();
        assertThat(writes("save", "fenced").count()).isEqualTo(1);
        assertThat(writes("save", "failed").count()).isZero();
    }

    @Test
    void 回归_写回坐标非有限_改写为原点_其余照常写回并释放() {
        when(store.saveStateAndRelease(any(), any())).thenReturn(true);
        StoragePlayerRepository repository =
                new StoragePlayerRepository(store, new DirectExecutorService(), logic, metrics);

        // JDBC 驱动默认拒绝 NaN / ±Inf：不改写的话整条写回失败，等级 / 场景丢失、归属放不掉。
        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, Double.NaN, Double.NEGATIVE_INFINITY)));

        ArgumentCaptor<PlayerRow> row = ArgumentCaptor.forClass(PlayerRow.class);
        verify(store).saveStateAndRelease(row.capture(), any());
        assertThat(row.getValue().getOwnerEpoch()).isEqualTo(9L);
        assertThat(row.getValue().getLevel()).isEqualTo(4);
        assertThat(row.getValue().getSceneConfigId()).isEqualTo(2);
        assertThat(row.getValue().getPosX()).isZero();
        assertThat(row.getValue().getPosY()).isZero();
        assertThat(row.getValue().getPosZ()).isZero();
        assertThat(writes("save", "released").count()).isEqualTo(1);
        assertThat(repository.writeFailures()).isZero();
    }

    // ------------------------------------------------------------------ 写失败：重试与记录

    private final List<Long> sleeps = new ArrayList<>();
    private final long[] nanos = {0};

    private StoragePlayerRepository retrying(ExecutorService executor) {
        return new StoragePlayerRepository(store, executor, logic,
                new StoragePlayerRepository.RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5)),
                millis -> {
                    sleeps.add(millis);
                    nanos[0] += TimeUnit.MILLISECONDS.toNanos(millis);
                }, backoff -> 0, () -> nanos[0], metrics);
    }

    private Timer writes(String op, String result) {
        return meters.get("xm.scene.storage.writes").tag("op", op).tag("result", result).timer();
    }

    @Test
    void 开事务时取不到连接_按瞬时故障重试() {
        // 写回是 @Transactional：连接池超时在开事务时以 CannotCreateTransactionException 抛出（不是 DataAccessException），
        // 根因是连接池自己的异常（如 Druid 的 GetConnectionTimeoutException，不带 SQL 瞬时类型）。
        when(store.saveStateAndRelease(any(), any()))
                .thenThrow(new CannotCreateTransactionException("开事务失败", new IllegalStateException("连接池等待超时")))
                .thenReturn(true);
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        verify(store, times(2)).saveStateAndRelease(any(), any());
        assertThat(repository.writeFailures()).isZero();
        assertThat(writes("save", "released").count()).isEqualTo(1);
    }

    @Test
    void 在线存盘失败只告警_不计入写丢失() {
        when(store.saveStateHeld(any(), any())).thenThrow(new BadSqlGrammarException("save", "UPDATE", new SQLException("列不存在")));
        StoragePlayerRepository repository = retrying(new DirectExecutorService());
        List<ProgressResult> results = new ArrayList<>();

        repository.saveProgress(new PlayerSave(1001, 9, 4, 2, Vec3.ORIGIN), results::add);
        logicTasks.forEach(Runnable::run);

        assertThat(results).containsExactly(ProgressResult.FAILED);
        assertThat(repository.writeFailures()).as("在线存盘下个周期重写，不是丢失").isZero();
        assertThat(writes("progress", "failed").count()).isEqualTo(1);
    }

    @Test
    void 存储线程池排队达到线程数两倍_不再接在线存盘() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        try {
            StoragePlayerRepository repository = new StoragePlayerRepository(store, pool, logic, metrics);
            pool.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(repository.acceptsProgress()).isTrue();
            pool.execute(() -> { });
            assertThat(repository.acceptsProgress()).as("排队 1 < 2").isTrue();
            pool.execute(() -> { });
            assertThat(repository.acceptsProgress()).as("排队 2 = 线程数 × 2").isFalse();
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 取不到连接等瞬时故障_退避重试后成功() {
        when(store.saveStateAndRelease(any(), any()))
                .thenThrow(new CannotGetJdbcConnectionException("池满"))
                .thenThrow(new QueryTimeoutException("超时"))
                .thenReturn(true);
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        verify(store, times(3)).saveStateAndRelease(any(), any());
        assertThat(sleeps).containsExactly(200L, 400L);
        assertThat(repository.writeFailures()).isZero();
        assertThat(writes("save", "released").count()).as("重试后成功只计一次").isEqualTo(1);
        assertThat(writes("save", "released").totalTime(TimeUnit.MILLISECONDS)).as("耗时含重试等待").isEqualTo(600);
    }

    @Test
    void 瞬时故障重试用尽_记一次失败() {
        when(store.saveStateAndRelease(any(), any())).thenThrow(new CannotGetJdbcConnectionException("库挂了"));
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        verify(store, times(3)).saveStateAndRelease(any(), any());
        assertThat(repository.writeFailures()).isEqualTo(1);
        assertThat(writes("save", "failed").count()).isEqualTo(1);
    }

    @Test
    void 非瞬时故障不重试() {
        when(store.saveStateAndRelease(any(), any())).thenThrow(new BadSqlGrammarException("save", "UPDATE", new SQLException("列不存在")));
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        verify(store, times(1)).saveStateAndRelease(any(), any());
        assertThat(sleeps).isEmpty();
        assertThat(repository.writeFailures()).isEqualTo(1);
    }

    @Test
    void 截止时间不够再等一次就放弃() {
        when(store.saveStateAndRelease(any(), any())).thenAnswer(inv -> {
            nanos[0] += Duration.ofSeconds(5).toNanos();
            throw new CannotGetJdbcConnectionException("每次都卡满超时");
        });
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));

        verify(store, times(1)).saveStateAndRelease(any(), any());
        assertThat(repository.writeFailures()).isEqualTo(1);
    }

    @Test
    void 只释放归属_带epoch围栏_同样重试瞬时故障() {
        when(store.releaseOwnership(1001, 9)).thenThrow(new CannotGetJdbcConnectionException("闪断")).thenReturn(true);
        StoragePlayerRepository repository = retrying(new DirectExecutorService());

        repository.release(1001, 9);

        verify(store, times(2)).releaseOwnership(1001, 9);
        assertThat(repository.writeFailures()).isZero();
        assertThat(writes("release", "released").count()).isEqualTo(1);
    }

    @Test
    void 线程池拒绝写任务_计一次失败_停服丢弃的任务能逐条描述() {
        StoragePlayerRepository rejecting = retrying(new RejectingExecutorService());
        rejecting.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));
        assertThat(rejecting.writeFailures()).isEqualTo(1);
        assertThat(writes("save", "rejected").count()).isEqualTo(1);

        List<Runnable> queued = new ArrayList<>();
        StoragePlayerRepository queuing = retrying(new DirectExecutorService() {
            @Override
            public void execute(Runnable command) {
                queued.add(command);
            }
        });
        queuing.save(new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9)));
        queuing.release(1002, 3);

        assertThat(queued).hasSize(2);
        assertThat(StoragePlayerRepository.describeDropped(queued.get(0))).hasValueSatisfying(text -> assertThat(text)
                .contains("1001").contains("epoch=9").contains("scene_config=2").contains("pos=(7.0,8.0,9.0)"));
        assertThat(StoragePlayerRepository.describeDropped(queued.get(1))).hasValueSatisfying(text -> assertThat(text)
                .contains("释放").contains("1002").contains("epoch=3"));
        assertThat(StoragePlayerRepository.describeDropped(() -> { })).isEmpty();
    }

    // ------------------------------------------------------------------ 交出与探测（scene-handoff-spec §5.2）

    private final long[] wall = {1_000_000L};
    private static final PlayerSave FROZEN = new PlayerSave(1001, 9, 4, 2, new Vec3(7, 8, 9),
            PlayerState.newBuilder().setFacing(Facing.newBuilder().setX(3)).build());

    /** 墙钟与单调时钟都由测试驱动；退避等待同时推进两者。 */
    private StoragePlayerRepository handing(ExecutorService executor, Executor logicExecutor) {
        return new StoragePlayerRepository(store, executor, logicExecutor,
                new StoragePlayerRepository.RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5)),
                HandOffSettings.DEFAULT,
                millis -> {
                    sleeps.add(millis);
                    nanos[0] += TimeUnit.MILLISECONDS.toNanos(millis);
                    wall[0] += millis;
                }, backoff -> 0, () -> nanos[0], () -> wall[0], metrics);
    }

    private StoragePlayerRepository handing() {
        return handing(new DirectExecutorService(), logic);
    }

    private HandOffResult attemptHandOff() {
        return store.handOffOwnership(any(), any(), anyLong(), anyLong(), any());
    }

    private <T> List<T> runLogic(List<T> results) {
        new ArrayList<>(logicTasks).forEach(Runnable::run);
        logicTasks.clear();
        return results;
    }

    private static HandOffOutcome.Failed failed(long firstAttemptNanos, Long... leases) {
        return new HandOffOutcome.Failed(1001, 9, new HandOffAttempts(firstAttemptNanos, List.of(leases)));
    }

    @Test
    void 交出成功_带冻结快照与围栏_新租约是现在加租约_剩余租约下限是现在加安全边际_结局投递回逻辑线程() {
        when(attemptHandOff()).thenReturn(new HandOffResult.HandedOff(10));
        List<HandOffOutcome> results = new ArrayList<>();

        handing().handOff(FROZEN, results::add);

        assertThat(results).as("回调不在调用栈内").isEmpty();
        assertThat(runLogic(results)).containsExactly(new HandOffOutcome.HandedOff(10));
        ArgumentCaptor<PlayerRow> row = ArgumentCaptor.forClass(PlayerRow.class);
        verify(store).handOffOwnership(row.capture(), org.mockito.ArgumentMatchers.eq(FROZEN.state()),
                org.mockito.ArgumentMatchers.eq(1_030_000L), org.mockito.ArgumentMatchers.eq(1_015_000L),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(3)));
        assertThat(row.getValue().getPlayerId()).isEqualTo(1001L);
        assertThat(row.getValue().getOwnerEpoch()).isEqualTo(9L);
        assertThat(row.getValue().getSceneConfigId()).isEqualTo(2);
        assertThat(row.getValue().getPosX()).isEqualTo(7);
        assertThat(writes("handoff", "handed_off").count()).isEqualTo(1);
    }

    @Test
    void 交出没提交_租约不足与围栏拒绝原样回报_不重试() {
        when(attemptHandOff())
                .thenReturn(new HandOffResult.LeaseTooShort(new OwnerState(9, false, 1_010_000)))
                .thenReturn(new HandOffResult.Fenced(new OwnerState(12, false, 1_030_000)))
                .thenReturn(new HandOffResult.Fenced(null));
        StoragePlayerRepository repository = handing();
        List<HandOffOutcome> results = new ArrayList<>();

        repository.handOff(FROZEN, results::add);
        repository.handOff(FROZEN, results::add);
        repository.handOff(FROZEN, results::add);

        assertThat(runLogic(results)).containsExactly(new HandOffOutcome.LeaseTooShort(), new HandOffOutcome.Fenced(),
                new HandOffOutcome.Fenced());
        assertThat(sleeps).isEmpty();
        assertThat(writes("handoff", "lease_too_short").count()).isEqualTo(1);
        assertThat(writes("handoff", "fenced").count()).isEqualTo(2);
    }

    @Test
    void 重试改判_第一次已提交但应答丢失_第二次读到E加1未释放且租约是第一次写下的_回已交出() {
        when(attemptHandOff())
                .thenThrow(new QueryTimeoutException("提交应答丢失"))
                .thenReturn(new HandOffResult.Fenced(new OwnerState(10, false, 1_030_000)));
        List<HandOffOutcome> results = new ArrayList<>();

        handing().handOff(FROZEN, results::add);

        assertThat(runLogic(results)).containsExactly(new HandOffOutcome.HandedOff(10));
        verify(store).handOffOwnership(any(), any(), org.mockito.ArgumentMatchers.eq(1_030_000L), anyLong(), any());
        verify(store).handOffOwnership(any(), any(), org.mockito.ArgumentMatchers.eq(1_030_200L),
                org.mockito.ArgumentMatchers.eq(1_015_200L), any());
        assertThat(writes("handoff", "handed_off").count()).isEqualTo(1);
        assertThat(writes("handoff", "fenced").count()).isZero();
    }

    @Test
    void 重试不改判_租约不是自己写的_或已释放_或epoch不是E加1_或就是本次尝试的值() {
        StoragePlayerRepository repository = handing();
        List<HandOffOutcome> results = new ArrayList<>();
        for (OwnerState other : List.of(new OwnerState(10, false, 999), new OwnerState(10, true, 1_030_000),
                new OwnerState(11, false, 1_030_000))) {
            org.mockito.Mockito.reset(store);
            wall[0] = 1_000_000;
            when(attemptHandOff()).thenThrow(new QueryTimeoutException("应答丢失"))
                    .thenReturn(new HandOffResult.Fenced(other));
            repository.handOff(FROZEN, results::add);
        }
        org.mockito.Mockito.reset(store);
        wall[0] = 1_000_000;
        when(attemptHandOff()).thenReturn(new HandOffResult.Fenced(new OwnerState(10, false, 1_030_000)));
        repository.handOff(FROZEN, results::add);

        assertThat(runLogic(results)).as("第一次尝试影响 0 行时库里的租约值不可能是它写的").containsOnly(new HandOffOutcome.Fenced())
                .hasSize(4);
    }

    @Test
    void 交出重试用尽_结局不明_带上首次尝试时刻与每次的租约值_不计入写丢失() {
        nanos[0] = 7_000;
        when(attemptHandOff()).thenThrow(new CannotGetJdbcConnectionException("库挂了"));
        List<HandOffOutcome> results = new ArrayList<>();

        StoragePlayerRepository repository = handing();
        repository.handOff(FROZEN, results::add);

        assertThat(runLogic(results)).containsExactly(failed(7_000, 1_030_000L, 1_030_200L, 1_030_600L));
        assertThat(sleeps).containsExactly(200L, 400L);
        assertThat(repository.writeFailures()).isZero();
        assertThat(writes("handoff", "failed").count()).isEqualTo(1);
    }

    @Test
    void 交出被线程池拒绝_结局不明但尝试为空_探测不读库直接判没提交() {
        StoragePlayerRepository repository = handing(new RejectingExecutorService(), logic);
        List<HandOffOutcome> handOffs = new ArrayList<>();

        repository.handOff(FROZEN, handOffs::add);
        assertThat(handOffs).as("仍异步回调").isEmpty();
        HandOffOutcome outcome = runLogic(handOffs).get(0);
        assertThat(outcome).isEqualTo(new HandOffOutcome.Failed(1001, 9, new HandOffAttempts(0, List.of())));

        List<ProbeOutcome> probes = new ArrayList<>();
        repository.probe((HandOffOutcome.Failed) outcome, probes::add);
        assertThat(probes).isEmpty();
        assertThat(runLogic(probes)).containsExactly(new ProbeOutcome.NotCommitted());
        verify(store, org.mockito.Mockito.never()).probeOwnership(anyLong(), any());
        assertThat(writes("handoff", "rejected").count()).isEqualTo(1);
        assertThat(writes("probe", "not_committed").count()).isEqualTo(1);
    }

    @Test
    void 探测_读到原epoch未释放判没提交_读到E加1未释放且租约是自己写的判已交出() {
        when(store.probeOwnership(1001, Duration.ofSeconds(3)))
                .thenReturn(Optional.of(new OwnerState(9, false, 1_029_000)))
                .thenReturn(Optional.of(new OwnerState(10, false, 1_030_200)));
        StoragePlayerRepository repository = handing();
        List<ProbeOutcome> results = new ArrayList<>();

        repository.probe(failed(0, 1_030_000L, 1_030_200L), results::add);
        repository.probe(failed(0, 1_030_000L, 1_030_200L), results::add);

        assertThat(results).as("回调不在调用栈内").isEmpty();
        assertThat(runLogic(results)).containsExactly(new ProbeOutcome.NotCommitted(), new ProbeOutcome.HandedOff(10));
        assertThat(writes("probe", "not_committed").count()).isEqualTo(1);
        assertThat(writes("probe", "handed_off").count()).isEqualTo(1);
    }

    @Test
    void 探测_别人的租约值_已释放_epoch不对_玩家不存在_一律判失去() {
        when(store.probeOwnership(anyLong(), any()))
                .thenReturn(Optional.of(new OwnerState(10, false, 1_031_234)))
                .thenReturn(Optional.of(new OwnerState(10, true, 1_030_000)))
                .thenReturn(Optional.of(new OwnerState(9, true, 1_030_000)))
                .thenReturn(Optional.of(new OwnerState(11, false, 1_030_000)))
                .thenReturn(Optional.empty());
        StoragePlayerRepository repository = handing();
        List<ProbeOutcome> results = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            repository.probe(failed(0, 1_030_000L), results::add);
        }

        assertThat(runLogic(results)).hasSize(5).containsOnly(new ProbeOutcome.Lost());
        assertThat(writes("probe", "lost").count()).isEqualTo(5);
    }

    @Test
    void 探测_读失败在截止前退避重试_读到结论为止() {
        when(store.probeOwnership(anyLong(), any()))
                .thenThrow(new QueryTimeoutException("锁等待超时"))
                .thenThrow(new BadSqlGrammarException("probe", "SELECT", new SQLException("任何读失败都重试")))
                .thenReturn(Optional.of(new OwnerState(9, false, 1_029_000)));
        List<ProbeOutcome> results = new ArrayList<>();

        handing().probe(failed(0, 1_030_000L), results::add);

        assertThat(runLogic(results)).containsExactly(new ProbeOutcome.NotCommitted());
        assertThat(sleeps).containsExactly(200L, 400L);
    }

    @Test
    void 探测_一直读不到_到首次尝试起M减2秒判失去_每次读受语句时限() {
        when(store.probeOwnership(anyLong(), any())).thenAnswer(inv -> {
            nanos[0] += Duration.ofSeconds(3).toNanos();
            throw new QueryTimeoutException("锁一直被占");
        });
        List<ProbeOutcome> results = new ArrayList<>();

        handing().probe(failed(0, 1_030_000L), results::add);

        assertThat(runLogic(results)).containsExactly(new ProbeOutcome.Lost());
        // 读 0→3s、等 0.2、读 3.2→6.2、等 0.4、读 6.6→9.6、等 0.8、读 10.4→13.4 > 截止 13s
        verify(store, times(4)).probeOwnership(anyLong(), any());
        assertThat(sleeps).containsExactly(200L, 400L, 800L);
        assertThat(writes("probe", "lost").count()).isEqualTo(1);
    }

    @Test
    void 探测_截止已过也至少读一次_读到就给结论() {
        nanos[0] = Duration.ofSeconds(60).toNanos();
        when(store.probeOwnership(anyLong(), any()))
                .thenReturn(Optional.of(new OwnerState(10, false, 1_030_000)))
                .thenThrow(new QueryTimeoutException("超时"));
        StoragePlayerRepository repository = handing();
        List<ProbeOutcome> results = new ArrayList<>();

        repository.probe(failed(0, 1_030_000L), results::add);
        repository.probe(failed(0, 1_030_000L), results::add);

        assertThat(runLogic(results)).containsExactly(new ProbeOutcome.HandedOff(10), new ProbeOutcome.Lost());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 探测被线程池拒绝_判失去() {
        List<ProbeOutcome> results = new ArrayList<>();

        handing(new RejectingExecutorService(), logic).probe(failed(0, 1_030_000L), results::add);

        assertThat(runLogic(results)).containsExactly(new ProbeOutcome.Lost());
        assertThat(writes("probe", "rejected").count()).isEqualTo(1);
    }

    @Test
    void 结局投递被拒_已交出的由存储线程带围栏释放新epoch_其余结局只丢弃() {
        Executor stopped = task -> {
            throw new RejectedExecutionException("逻辑线程已停");
        };
        when(attemptHandOff()).thenReturn(new HandOffResult.HandedOff(10))
                .thenReturn(new HandOffResult.Fenced(new OwnerState(12, false, 1)));
        when(store.probeOwnership(anyLong(), any())).thenReturn(Optional.of(new OwnerState(10, false, 1_030_000)))
                .thenReturn(Optional.of(new OwnerState(9, false, 1_029_000)));
        when(store.releaseOwnership(1001, 10)).thenReturn(true);
        StoragePlayerRepository repository = handing(new DirectExecutorService(), stopped);

        repository.handOff(FROZEN, outcome -> { });
        repository.handOff(FROZEN, outcome -> { });
        repository.probe(failed(0, 1_030_000L), outcome -> { });
        repository.probe(failed(0, 1_030_000L), outcome -> { });

        verify(store, times(2)).releaseOwnership(1001, 10);
        verify(store, times(2)).releaseOwnership(anyLong(), anyLong());
        assertThat(writes("release", "released").count()).isEqualTo(2);
    }

    @Test
    void 停服丢弃的交出与探测任务能逐条描述() {
        List<Runnable> queued = new ArrayList<>();
        StoragePlayerRepository queuing = handing(new DirectExecutorService() {
            @Override
            public void execute(Runnable command) {
                queued.add(command);
            }
        }, logic);

        queuing.handOff(FROZEN, outcome -> { });
        queuing.probe(failed(0, 1_030_000L), outcome -> { });

        assertThat(StoragePlayerRepository.describeDropped(queued.get(0))).hasValueSatisfying(text -> assertThat(text)
                .contains("交出").contains("1001").contains("epoch=9").contains("scene_config=2"));
        assertThat(StoragePlayerRepository.describeDropped(queued.get(1))).hasValueSatisfying(text -> assertThat(text)
                .contains("探测").contains("1001").contains("1030000"));
    }

    @Test
    void 事务时限到期按瞬时故障重试() {
        assertThat(StoragePlayerRepository.isTransient(new TransactionTimedOutException("事务时限已到"))).isTrue();
        assertThat(StoragePlayerRepository.isTransient(new QueryTimeoutException("语句超时"))).isTrue();
        assertThat(StoragePlayerRepository.isTransient(new IllegalStateException("交出后 epoch 不是 E+1"))).isFalse();
    }

    /** 同步执行的线程池替身。 */
    private static class DirectExecutorService extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** 队列已满的线程池替身。 */
    private static final class RejectingExecutorService extends DirectExecutorService {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("满");
        }
    }
}
