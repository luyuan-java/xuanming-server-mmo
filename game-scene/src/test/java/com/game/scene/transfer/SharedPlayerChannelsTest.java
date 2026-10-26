package com.game.scene.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.discovery.world.WorldChannelDirectoryStore;
import com.game.proto.SceneChannelDirectory;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerStore;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerSave;
import com.game.scene.world.Vec3;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedPlayerChannelsTest {
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final LinkedBlockingQueue<Runnable> logic = new LinkedBlockingQueue<>();
    private byte[] payload;
    private RuntimeException failure;
    private int reads;
    private final List<Duration> writes = new ArrayList<>();
    private final WorldChannelDirectoryStore directory = new WorldChannelDirectoryStore() {
        @Override public byte[] read(int zone, int config) {
            assertThat(zone).isEqualTo(7); assertThat(config).isEqualTo(1); reads++;
            if (failure != null) throw failure;
            return payload;
        }
        @Override public boolean publish(int zone, String token, long version, int config, byte[] data, Duration ttl) {
            throw new AssertionError("scene 只能读目录");
        }
    };
    private final ChannelSwitchCooldown cooldown = new ChannelSwitchCooldown() {
        @Override public boolean active(long playerId) { throw new AssertionError("scene 不做 manager 预检"); }
        @Override public void start(long playerId, Duration ttl) {
            assertThat(playerId).isEqualTo(99); writes.add(ttl);
            if (failure != null) throw failure;
        }
    };

    private SharedPlayerChannels port(Duration timeout) {
        return new SharedPlayerChannels(7, directory, cooldown, worker::add, logic::add, timeout);
    }

    @Test
    void IO只在worker_回调只在logic_目录逐字段原样保留() {
        var expected = SceneChannelDirectory.newBuilder().setZoneId(7).setSceneConfigId(1)
                .setSwitchEnabled(true).setSwitchCooldownSeconds(30).setMaxPlayersPerChannel(2000).setUpdatedAtMs(77).build();
        payload = expected.toByteArray();
        List<SceneChannelDirectory> results = new ArrayList<>();
        port(Duration.ofSeconds(3)).directory(1, results::add);
        assertThat(reads).isZero(); assertThat(results).isEmpty();
        worker.remove().run();
        assertThat(reads).isEqualTo(1); assertThat(results).isEmpty();
        logic.remove().run();
        assertThat(results).containsExactly(expected);
    }

    @Test
    void 缺失损坏跨区跨图与依赖失败都返回无目录_恰好回调一次() {
        List<byte[]> inputs = new ArrayList<>();
        inputs.add(null); inputs.add(new byte[]{(byte) 0xff});
        inputs.add(SceneChannelDirectory.newBuilder().setZoneId(8).setSceneConfigId(1).build().toByteArray());
        inputs.add(SceneChannelDirectory.newBuilder().setZoneId(7).setSceneConfigId(2).build().toByteArray());
        inputs.add(new byte[0]);
        for (byte[] input : inputs) {
            payload = input;
            List<SceneChannelDirectory> results = new ArrayList<>();
            port(Duration.ofSeconds(3)).directory(1, results::add);
            worker.remove().run(); logic.remove().run();
            assertThat(results).containsExactly((SceneChannelDirectory) null);
            assertThat(logic).isEmpty();
        }
        failure = new IllegalStateException("unavailable");
        List<SceneChannelDirectory> results = new ArrayList<>();
        port(Duration.ofSeconds(3)).directory(1, results::add);
        worker.remove().run(); logic.remove().run();
        assertThat(results).containsExactly((SceneChannelDirectory) null);
    }

    @Test
    void 冷却秒数按uint32_零不写_失败仍完成成功路径() {
        List<String> done = new ArrayList<>();
        var port = port(Duration.ofSeconds(3));
        port.switched(99, 0, () -> done.add("zero"));
        assertThat(worker).isEmpty(); logic.remove().run();
        port.switched(99, -1, () -> done.add("large"));
        assertThat(writes).isEmpty(); worker.remove().run();
        assertThat(done).containsExactly("zero"); logic.remove().run();
        assertThat(writes).containsExactly(Duration.ofSeconds(4_294_967_295L));
        failure = new IllegalStateException("unavailable");
        port.switched(99, 30, () -> done.add("failed"));
        worker.remove().run(); logic.remove().run();
        assertThat(done).containsExactly("zero", "large", "failed");
    }

    @Test
    void 排队超时仍回调_迟到任务不执行冷却写也不重复完成() throws Exception {
        List<String> done = new ArrayList<>();
        port(Duration.ofMillis(20)).switched(99, 30, () -> done.add("done"));
        Runnable completion = logic.poll(5, TimeUnit.SECONDS);
        assertThat(completion).isNotNull(); completion.run();
        worker.remove().run();
        assertThat(done).containsExactly("done");
        assertThat(writes).isEmpty(); assertThat(logic).isEmpty();
    }

    @Test
    void worker拒绝时目录和冷却都回调_logic停止则丢弃() {
        List<String> done = new ArrayList<>();
        var rejected = new SharedPlayerChannels(7, directory, cooldown,
                task -> { throw new RejectedExecutionException(); }, logic::add);
        rejected.directory(1, value -> done.add(value == null ? "directory" : "bad"));
        rejected.switched(99, 30, () -> done.add("cooldown"));
        while (!logic.isEmpty()) logic.remove().run();
        assertThat(done).containsExactly("directory", "cooldown");
        var stopped = new SharedPlayerChannels(7, directory, cooldown, worker::add,
                task -> { throw new RejectedExecutionException(); });
        stopped.switched(99, 0, () -> done.add("must not run"));
        assertThat(done).hasSize(2);
    }

    /** 真仓储负责 settlement，只替换 SQL 与线程调度；释放确实先排进 storage，不能靠假仓储的空句柄掩盖竞态。 */
    private final class ContinuationFixture {
        final Queue<Runnable> storageTasks = new ArrayDeque<>();
        final PlayerStore store = mock(PlayerStore.class);
        final StoragePlayerRepository repository;
        PlayerRepository.TransferContinuation retained;
        boolean logicStopped;
        boolean callbackThrows;
        int completed;

        ContinuationFixture() {
            ExecutorService storage = mock(ExecutorService.class);
            doAnswer(inv -> { storageTasks.add(inv.getArgument(0)); return null; }).when(storage).execute(any());
            repository = new StoragePlayerRepository(store, storage, logic::add, SceneMetrics.noop());
            when(store.handOffOwnership(any(), any(), anyLong(), anyLong(), any()))
                    .thenReturn(new PlayerStore.HandOffResult.HandedOff(10));
            when(store.releaseOwnership(99, 10)).thenReturn(true);
        }

        void begin(Duration timeout, boolean probe) {
            SharedPlayerChannels port = new SharedPlayerChannels(7, directory, cooldown, worker::add, task -> {
                if (logicStopped) throw new RejectedExecutionException("逻辑线程已停");
                logic.add(task);
            }, timeout);
            Runnable continuation = () -> {
                retained = repository.retainTransferContinuation();
                port.switched(99, 30, () -> {
                    try {
                        repository.release(99, 10); completed++;
                        if (callbackThrows) throw new IllegalStateException("续接失败");
                    }
                    finally { retained.close(); }
                }, () -> {
                    try { repository.release(99, 10); }
                    finally { retained.close(); }
                });
            };
            if (probe) {
                when(store.probeOwnership(anyLong(), any()))
                        .thenReturn(Optional.of(new OwnerState(10, false, 123)));
                repository.probe(new PlayerRepository.HandOffOutcome.Failed(99, 9,
                        new PlayerRepository.HandOffAttempts(System.nanoTime(), List.of(123L))), outcome -> {
                    assertThat(outcome).isEqualTo(new PlayerRepository.ProbeOutcome.HandedOff(10));
                    continuation.run();
                });
            } else {
                repository.handOff(new PlayerSave(99, 9, 1, 1, Vec3.ORIGIN), outcome -> {
                    assertThat(outcome).isEqualTo(new PlayerRepository.HandOffOutcome.HandedOff(10));
                    continuation.run();
                });
            }
            storageTasks.remove().run();
            logic.remove().run();
        }

        void assertReleasedAfterSettlement() throws Exception {
            assertThat(repository.awaitTransfersSettled(0)).isZero();
            assertThat(storageTasks).as("结算完成前释放已入队").hasSize(1);
            storageTasks.remove().run();
            verify(store).releaseOwnership(99, 10);
            retained.close();
            assertThat(repository.awaitTransfersSettled(0)).as("重复关闭不减成负数").isZero();
            assertThat(repository.writeFailures()).isZero();
        }
    }

    @Test
    void 原交出或探测回调已返回_冷却续接完成前真仓储仍未结算() throws Exception {
        for (boolean probe : List.of(false, true)) {
            var f = new ContinuationFixture();
            f.begin(Duration.ofSeconds(3), probe);
            assertThat(f.repository.awaitTransfersSettled(0)).isEqualTo(1);
            worker.remove().run();
            assertThat(f.repository.awaitTransfersSettled(0)).as("已回报冷却结果但逻辑回调仍在队列").isEqualTo(1);
            logic.remove().run();
            assertThat(f.completed).isEqualTo(1);
            f.assertReleasedAfterSettlement();
        }
    }

    @Test
    void 冷却排队超时仍续接结算_迟到worker不写不重复释放() throws Exception {
        var f = new ContinuationFixture();
        f.begin(Duration.ofMillis(20), false);
        assertThat(f.repository.awaitTransfersSettled(0)).isEqualTo(1);
        Runnable completion = logic.poll(5, TimeUnit.SECONDS);
        assertThat(completion).isNotNull(); completion.run();
        f.assertReleasedAfterSettlement();
        worker.remove().run();
        assertThat(writes).isEmpty(); assertThat(logic).isEmpty(); assertThat(f.completed).isEqualTo(1);
    }

    @Test
    void 冷却完成投递被拒_只入队E加1释放后结算_不在完成线程跑SQL() throws Exception {
        var f = new ContinuationFixture();
        f.begin(Duration.ofSeconds(3), false);
        f.logicStopped = true;
        worker.remove().run();
        assertThat(f.completed).isZero(); assertThat(logic).isEmpty();
        org.mockito.Mockito.verify(f.store, org.mockito.Mockito.never()).releaseOwnership(anyLong(), anyLong());
        f.assertReleasedAfterSettlement();
    }

    @Test
    void 逻辑续接抛异常仍归还结算句柄() throws Exception {
        var f = new ContinuationFixture();
        f.callbackThrows = true;
        f.begin(Duration.ofSeconds(3), false);
        worker.remove().run();
        assertThatThrownBy(() -> logic.remove().run()).isInstanceOf(IllegalStateException.class).hasMessage("续接失败");
        f.assertReleasedAfterSettlement();
    }
}
