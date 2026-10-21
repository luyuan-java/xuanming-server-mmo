package com.game.scene.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.player.store.PlayerTradeStore;
import com.game.player.store.PlayerTradeStore.DrainTask;
import com.game.player.store.PlayerTradeStore.FreezeRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 可控两线程队列，无sleep；只断言真实模块调用边界，不冒充scene/SQL实际完成。 */
class TradeFreezeRecoveryTest {
    private static DrainTask task(long listing) {
        return new DrainTask(new FreezeRequest(listing, listing, "seller", 1), 3, "scene-source");
    }

    @Test void 首页二十个未完成也继续尾五_每项先逻辑排空再SQLACK_读页失败不前移() {
        var store = mock(PlayerTradeStore.class);
        var storage = new Queue(); var logic = new Queue();
        List<DrainTask> first = new ArrayList<>(); List<DrainTask> tail = new ArrayList<>();
        for (long id = 1; id <= 25; id++) (id <= 20 ? first : tail).add(task(id));
        when(store.pendingSceneDrains("scene-source", 0, 20)).thenReturn(first);
        when(store.pendingSceneDrains("scene-source", 20, 20)).thenThrow(new IllegalStateException("一次SQL读取故障")).thenReturn(tail);
        when(store.pendingSceneDrains("scene-source", 25, 20)).thenReturn(List.of());
        Map<Long, Runnable> acknowledgements = new HashMap<>();
        try (var recovery = new TradeFreezeRecovery(store, "scene-source", storage, logic,
                (entry, done) -> acknowledgements.put(entry.request().listingId(), done))) {
            recovery.requestScan(); recovery.requestScan();
            assertEquals(1, storage.tasks.size(), "扫描尚未完成时不得并行读第二页");
            storage.runAll();
            assertEquals(20, logic.tasks.size());
            verify(store, never()).recordSceneDrain(any(), any());
            recovery.requestScan(); storage.runAll();
            verify(store).pendingSceneDrains("scene-source", 20, 20);
            recovery.requestScan(); storage.runAll();
            verify(store, times(2)).pendingSceneDrains("scene-source", 20, 20);
            assertEquals(25, logic.tasks.size());
            logic.runAll();
            verify(store, never()).recordSceneDrain(any(), any());
            for (long id = 21; id <= 25; id++) acknowledgements.get(id).run();
            assertEquals(5, storage.tasks.size());
            storage.runAll();
            for (long id = 21; id <= 25; id++) verify(store).recordSceneDrain(task(id), Duration.ofSeconds(5));
            verify(store, times(5)).recordSceneDrain(any(), any());
            recovery.requestScan(); storage.runAll();
            recovery.requestScan(); storage.runAll();
            assertTrue(logic.tasks.isEmpty(), "绕回后的前二十已在途，不重复累积逻辑排空等待者");
            verify(store, times(2)).pendingSceneDrains("scene-source", 0, 20);
        }
    }

    @Test void 单项逻辑或ACK故障不吞掉后项_下一轮以原listing和E重试() {
        var store = mock(PlayerTradeStore.class);
        var storage = new Queue(); var logic = new Queue();
        when(store.pendingSceneDrains("scene-source", 0, 20)).thenReturn(List.of(task(1), task(2)));
        when(store.pendingSceneDrains("scene-source", 2, 20)).thenReturn(List.of());
        when(store.recordSceneDrain(eq(task(2)), any())).thenThrow(new IllegalStateException("SQL ACK故障"));
        var attempted = new ArrayList<Long>();
        try (var recovery = new TradeFreezeRecovery(store, "scene-source", storage, logic, (entry, done) -> {
            attempted.add(entry.request().listingId());
            if (entry.request().listingId() == 1) throw new IllegalStateException("本项逻辑故障");
            done.run();
        })) {
            recovery.requestScan(); storage.runAll(); logic.runAll(); storage.runAll();
            assertEquals(List.of(1L, 2L), attempted);
            verify(store).recordSceneDrain(task(2), Duration.ofSeconds(5));
            recovery.requestScan(); storage.runAll();
            recovery.requestScan(); storage.runAll(); logic.runAll(); storage.runAll();
            assertEquals(List.of(1L, 2L, 1L, 2L), attempted);
            verify(store, times(2)).recordSceneDrain(task(2), Duration.ofSeconds(5));
        }
    }

    @Test void 线程池拒绝保留原游标可再试_关闭后不再执行排队SQL或ACK() {
        var store = mock(PlayerTradeStore.class);
        var storage = new Queue(); var logic = new Queue();
        when(store.pendingSceneDrains("scene-source", 0, 20)).thenReturn(List.of(task(1)));
        var recovery = new TradeFreezeRecovery(store, "scene-source", storage, logic, (entry, done) -> done.run());
        storage.reject = true;
        recovery.requestScan();
        verifyNoInteractions(store);
        storage.reject = false;
        recovery.requestScan(); storage.runAll(); logic.runAll();
        assertEquals(1, storage.tasks.size());
        recovery.close(); storage.runAll(); recovery.requestScan();
        verify(store, never()).recordSceneDrain(any(), any());
        assertTrue(storage.tasks.isEmpty());
        assertTrue(logic.tasks.isEmpty());
        var second = new TradeFreezeRecovery(store, "scene-source", storage, logic, (entry, done) -> done.run());
        second.requestScan(); second.close(); storage.runAll();
        verify(store, times(1)).pendingSceneDrains("scene-source", 0, 20);
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("本例受控拒绝");
            tasks.add(command);
        }
        void runAll() {
            int count = 0;
            while (!tasks.isEmpty()) {
                if (++count > 1000) fail("本例队列未有界结束");
                tasks.removeFirst().run();
            }
        }
    }
}
