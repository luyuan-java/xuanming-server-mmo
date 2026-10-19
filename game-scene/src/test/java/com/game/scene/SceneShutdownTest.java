package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import io.netty.channel.DefaultEventLoop;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
