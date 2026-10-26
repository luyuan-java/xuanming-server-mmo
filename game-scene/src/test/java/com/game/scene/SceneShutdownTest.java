package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore;
import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.discovery.world.WorldChannelDirectoryStore;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.transfer.SharedPlayerChannels;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import io.netty.channel.DefaultEventLoop;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 停服写回的顺序：写回任务在逻辑线程上排队时，存储线程池必须等它把写回交出去之后才关——
 * 回归「逻辑线程积压超过固定 5s → 先关池 → 写回稍后执行时全部被池拒绝」。
 */
class SceneShutdownTest {

    private final DefaultEventLoop logic = new DefaultEventLoop();
    private final ThreadPoolExecutor storage = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100));
    private final List<Long> savedPlayers = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        logic.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        storage.shutdownNow();
    }

    /** 逻辑线程被一个长任务堵住，直到 latch 放开。 */
    private CountDownLatch blockLogic() {
        CountDownLatch unblock = new CountDownLatch(1);
        logic.execute(() -> {
            try {
                unblock.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return unblock;
    }

    private StoragePlayerRepository repository() {
        PlayerStore store = Mockito.mock(PlayerStore.class);
        Mockito.when(store.registerFinalSave(Mockito.any(), Mockito.any(),Mockito.any(),Mockito.any())).thenAnswer(inv -> {
            var row=inv.<com.game.player.store.PlayerRow>getArgument(0);
            savedPlayers.add(row.getPlayerId());
            return new PlayerStore.FinalSaveReceipt(PlayerStore.FinalSaveStatus.APPLIED,row.getPlayerId(),
                    row.getOwnerEpoch(),1,inv.getArgument(2),"a".repeat(64));
        });
        return new StoragePlayerRepository(store, storage, logic, SceneMetrics.noop());
    }

    @Test
    void SQL已交出后仍等冷却续接_先释放新epoch入队再关存储池() throws Exception {
        PlayerStore store = Mockito.mock(PlayerStore.class);
        Mockito.when(store.handOffOwnership(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(new PlayerStore.HandOffResult.HandedOff(10));
        Mockito.when(store.releaseOwnership(99, 10)).thenReturn(true);
        var repository = new StoragePlayerRepository(store, storage, logic, SceneMetrics.noop());
        var cooldownTasks = new LinkedBlockingQueue<Runnable>();
        var channels = new SharedPlayerChannels(7, Mockito.mock(WorldChannelDirectoryStore.class),
                Mockito.mock(ChannelSwitchCooldown.class), cooldownTasks::add, logic);
        CountDownLatch handedOff = new CountDownLatch(1);
        CountDownLatch shutdownReachedSettlement = new CountDownLatch(1);
        AtomicBoolean detached = new AtomicBoolean();
        repository.handOff(new PlayerSave(99, 9, 1, 1, Vec3.ORIGIN), outcome -> {
            assertThat(outcome).isEqualTo(new com.game.scene.world.PlayerRepository.HandOffOutcome.HandedOff(10));
            var continuation = repository.retainTransferContinuation();
            channels.switched(99, 30, () -> {
                try {
                    assertThat(detached).isTrue();
                    repository.release(99, 10);
                } finally { continuation.close(); }
            }, () -> {
                try { repository.release(99, 10); }
                finally { continuation.close(); }
            });
            handedOff.countDown();
        });
        assertThat(handedOff.await(5, TimeUnit.SECONDS)).isTrue();
        logic.submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertThat(repository.awaitTransfersSettled(0)).as("原 SQL 回调已返回，冷却仍持有结算责任").isEqualTo(1);
        var stopping = CompletableFuture.supplyAsync(() -> SceneShutdown.writeBackThenDrainStorage(logic, () -> {
            detached.set(true);
            return 0;
        }, remaining -> {
            shutdownReachedSettlement.countDown();
            return repository.awaitTransfersSettled(remaining);
        }, SceneShutdown.TransferSettlement.NONE, () -> 0, repository::writeFailures,
                storage, Duration.ofSeconds(10), () -> 0, System::nanoTime));
        assertThat(shutdownReachedSettlement.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(storage.isShutdown()).isFalse();
        assertThat(stopping).isNotDone();
        Runnable cooldown = cooldownTasks.poll(5, TimeUnit.SECONDS);
        assertThat(cooldown).isNotNull(); cooldown.run();
        assertThat(stopping.get(5, TimeUnit.SECONDS).clean()).isTrue();
        Mockito.verify(store).releaseOwnership(99, 10);
        assertThat(repository.writeFailures()).isZero();
        assertThat(storage.isTerminated()).isTrue();
    }

    @Test
    void 满队列拒绝释放但其余任务正常排空_仍须报告写失败且unclean() throws Exception {
        var limited = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        var store = Mockito.mock(PlayerStore.class);
        var repository = new StoragePlayerRepository(store, limited, logic, SceneMetrics.noop());
        try {
            limited.execute(() -> {
                started.countDown();
                try { unblock.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            limited.execute(() -> { });
            var result = SceneShutdown.writeBackThenDrainStorage(logic, () -> {
                var continuation = repository.retainTransferContinuation();
                try { repository.release(99, 10); }
                finally { continuation.close(); unblock.countDown(); }
                return 0;
            }, repository::awaitTransfersSettled, SceneShutdown.TransferSettlement.NONE, () -> 0,
                    repository::writeFailures, limited, Duration.ofSeconds(5), () -> 0, System::nanoTime);
            assertThat(result.writeBackRan()).isTrue();
            assertThat(result.unsettledTransfers()).isZero();
            assertThat(result.droppedTasks()).isZero();
            assertThat(result.storageTerminated()).isTrue();
            assertThat(result.storageForceStopped()).isFalse();
            assertThat(result.storageWriteFailures()).isEqualTo(1);
            assertThat(result.clean()).isFalse();
            Mockito.verify(store, Mockito.never()).releaseOwnership(99, 10);
        } finally { unblock.countDown(); limited.shutdownNow(); }
    }

    @Test
    void 已入队释放在SQL执行时失败_排空后计入写失败() {
        var store = Mockito.mock(PlayerStore.class);
        Mockito.when(store.releaseOwnership(99, 10)).thenThrow(new IllegalStateException("SQL failure"));
        var repository = new StoragePlayerRepository(store, storage, logic, SceneMetrics.noop());
        var result = SceneShutdown.writeBackThenDrainStorage(logic, () -> {
            repository.release(99, 10); return 0;
        }, repository::awaitTransfersSettled, SceneShutdown.TransferSettlement.NONE, () -> 0,
                repository::writeFailures, storage, Duration.ofSeconds(5), () -> 0, System::nanoTime);
        assertThat(result.droppedTasks()).isZero();
        assertThat(result.storageTerminated()).isTrue();
        assertThat(result.storageWriteFailures()).isEqualTo(1);
        assertThat(result.clean()).isFalse();
    }

    @Test
    void 冷却续接未结算且等待预算用尽_不能把停服报成clean() {
        var repository = repository();
        var continuation = repository.retainTransferContinuation();
        try {
            var result = SceneShutdown.writeBackThenDrainStorage(logic, () -> 0,
                    ignored -> repository.awaitTransfersSettled(0), storage, Duration.ofSeconds(2), () -> 0, System::nanoTime);
            assertThat(result.unsettledTransfers()).isEqualTo(1);
            assertThat(result.clean()).isFalse();
        } finally { continuation.close(); }
    }

    @Test
    void 逻辑线程积压_写回排在队尾_等写回交出去之后才关存储池_全部落库() throws Exception {
        StoragePlayerRepository repository = repository();
        CountDownLatch unblock = blockLogic();
        // 逻辑线程被占住一段时间（远小于预算）：写回任务排在它后面。旧实现在这种情况下固定等 5s 就放弃、先关池。
        new Thread(() -> {
            sleep(300);
            unblock.countDown();
        }).start();

        SceneShutdown.Result result = SceneShutdown.writeBackThenDrainStorage(logic, () -> {
            repository.saveFinal(new PlayerSave(1, 1, 1, 1, Vec3.ORIGIN), ignored -> { });
            repository.saveFinal(new PlayerSave(2, 1, 1, 1, Vec3.ORIGIN), ignored -> { });
            repository.saveFinal(new PlayerSave(3, 1, 1, 1, Vec3.ORIGIN), ignored -> { });
            return 3;
        }, SceneShutdown.TransferSettlement.NONE, repository::awaitFinalRegistered, storage, Duration.ofSeconds(10), () -> 3, System::nanoTime);

        assertThat(result).isEqualTo(new SceneShutdown.Result(true, 3, 0));
        assertThat(savedPlayers).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(repository.writeFailures()).as("没有一条写回被已关闭的池拒绝").isZero();
        assertThat(storage.isTerminated()).isTrue();
    }

    @Test
    void 积压超过整个预算_写回被取消_之后不会在已关闭的池上执行() throws Exception {
        StoragePlayerRepository repository = repository();
        CountDownLatch unblock = blockLogic();
        AtomicBoolean ran = new AtomicBoolean();

        SceneShutdown.Result result = SceneShutdown.writeBackThenDrainStorage(logic, () -> {
            ran.set(true);
            repository.saveFinal(new PlayerSave(1, 1, 1, 1, Vec3.ORIGIN), ignored -> { });
            return 1;
        }, SceneShutdown.TransferSettlement.NONE, storage, Duration.ofMillis(200), () -> 1, System::nanoTime);

        unblock.countDown();
        logic.submit(() -> { }).get(5, TimeUnit.SECONDS);

        assertThat(result.writeBackRan()).isFalse();
        assertThat(ran).as("已取消：逻辑线程恢复后也不执行").isFalse();
        assertThat(repository.writeFailures()).isZero();
        assertThat(storage.isShutdown()).isTrue();
    }

    @Test
    void 写回交出去之后预算内没落库完_丢弃剩余存储任务并计数() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        ThreadPoolExecutor slow = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(10));
        try {
            SceneShutdown.Result result = SceneShutdown.writeBackThenDrainStorage(logic, () -> {
                slow.execute(() -> {
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                slow.execute(() -> { });
                return 2;
            }, SceneShutdown.TransferSettlement.NONE, slow, Duration.ofMillis(200), () -> 2, System::nanoTime);

            assertThat(result.writeBackRan()).isTrue();
            assertThat(result.playersSubmitted()).isEqualTo(2);
            assertThat(result.droppedTasks()).isEqualTo(1);
            assertThat(result.storageForceStopped()).isTrue();
            assertThat(result.clean()).isFalse();
        } finally {
            slow.shutdownNow();
        }
    }

    @Test
    void 最终登记预算用尽明确unclean且等待发生在关池前() {
        AtomicBoolean finalWaitRan=new AtomicBoolean();
        var result=SceneShutdown.writeBackThenDrainStorage(logic,()->1,
                SceneShutdown.TransferSettlement.NONE,remaining->{
                    assertThat(storage.isShutdown()).isFalse();
                    finalWaitRan.set(true);return 1;
                },storage,Duration.ofSeconds(2),()->1,System::nanoTime);
        assertThat(finalWaitRan).isTrue();
        assertThat(result.unregisteredFinals()).isEqualTo(1);
        assertThat(result.clean()).isFalse();
    }

    @Test
    void SQL全部登记但最后存储任务公布结果投递失败_drain之后仍必须unclean() {
        var failures=new java.util.concurrent.atomic.AtomicInteger();
        var result=SceneShutdown.writeBackThenDrainStorage(logic,()->{
            storage.execute(()->failures.incrementAndGet());return 1;
        },SceneShutdown.TransferSettlement.NONE,remaining->0,failures::get,storage,
                Duration.ofSeconds(2),()->1,System::nanoTime);
        assertThat(result.unregisteredFinals()).isZero();
        assertThat(result.undeliveredFinalResults()).isEqualTo(1);
        assertThat(result.clean()).isFalse();
    }

    @Test
    void 在途存储不响应中断且排队为零_不得把shutdownNow空列表冒充排空() {
        var stubborn=new java.util.concurrent.AbstractExecutorService() {
            boolean shutdown;
            boolean forced;
            @Override public void shutdown(){shutdown=true;}
            @Override public List<Runnable> shutdownNow(){shutdown=true;forced=true;return List.of();}
            @Override public boolean isShutdown(){return shutdown;}
            @Override public boolean isTerminated(){return false;}
            @Override public boolean awaitTermination(long timeout,TimeUnit unit){return false;}
            @Override public void execute(Runnable task){throw new AssertionError("本例模拟已在途任务，不提交新任务");}
        };
        var result=SceneShutdown.writeBackThenDrainStorage(logic,()->0,SceneShutdown.TransferSettlement.NONE,
                stubborn,Duration.ofSeconds(2),()->0,System::nanoTime);
        assertThat(stubborn.forced).as("正常await未终止后确实走了shutdownNow").isTrue();
        assertThat(result.writeBackRan()).isTrue();
        assertThat(result.droppedTasks()).as("保留实际0排队任务，不能虚构丢弃数量").isZero();
        assertThat(result.storageTerminated()).isFalse();
        assertThat(result.storageForceStopped()).isTrue();
        assertThat(result.clean()).as("在途SQL未终止，即使所有其它计数为0仍不干净").isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
