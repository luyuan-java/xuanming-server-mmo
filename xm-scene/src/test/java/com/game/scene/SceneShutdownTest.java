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
        Mockito.when(store.saveStateAndRelease(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            savedPlayers.add(inv.<com.game.player.store.PlayerRow>getArgument(0).getPlayerId());
            return true;
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
            repository.save(new PlayerSave(1, 1, 1, 1, Vec3.ORIGIN));
            repository.save(new PlayerSave(2, 1, 1, 1, Vec3.ORIGIN));
            repository.save(new PlayerSave(3, 1, 1, 1, Vec3.ORIGIN));
            return 3;
        }, storage, Duration.ofSeconds(10), () -> 3, System::nanoTime);

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
            repository.save(new PlayerSave(1, 1, 1, 1, Vec3.ORIGIN));
            return 1;
        }, storage, Duration.ofMillis(200), () -> 1, System::nanoTime);

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
            }, slow, Duration.ofMillis(200), () -> 2, System::nanoTime);

            assertThat(result).isEqualTo(new SceneShutdown.Result(true, 2, 1));
        } finally {
            slow.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
