package com.game.scene.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.LoadResult;
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
    void 加载带上持久化组件() {
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

    @Test
    void 组件损坏按加载失败处理() {
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
