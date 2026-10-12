package com.game.scene.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.scene.discovery.SceneDirectoryPublisher.Snapshot;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SceneDirectoryPublisherTest {

    /** 身份含资产通道的直连地址（rpc_host / rpc_port）：每轮发布原样带上，场景列表与已应用的计划版本另填。 */
    private static final SceneNodeInfo IDENTITY = SceneNodeInfo.newBuilder()
            .setZoneId(1).setNodeId(3).setInstanceId("inst").setLinkHost("127.0.0.1").setLinkPort(21000)
            .setRpcHost("127.0.0.1").setRpcPort(21100).build();
    private static final SceneEntry ENTRY = SceneEntry.newBuilder()
            .setSceneId(88).setSceneConfigId(1).setPlayerCount(2).build();
    private static final SceneEntry DRAINING = SceneEntry.newBuilder()
            .setSceneId(0x8000_0000_0000_0089L).setSceneConfigId(1).setPlayerCount(1).setDraining(true).build();

    @SuppressWarnings("unchecked")
    private final NodeDirectory<SceneNodeInfo> directory = mock(NodeDirectory.class);

    @Test
    void 发布身份加场景快照与已应用的计划版本_TTL15秒() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY, DRAINING), 0x8000_0000_0000_0005L));

        publisher.publishOnce();

        ArgumentCaptor<SceneNodeInfo> info = ArgumentCaptor.forClass(SceneNodeInfo.class);
        verify(directory).publish(eq(1), eq(3), info.capture(), eq(SceneDirectoryPublisher.TTL));
        assertThat(info.getValue()).isEqualTo(IDENTITY.toBuilder().addScenes(ENTRY).addScenes(DRAINING)
                .setAppliedPlanVersion(0x8000_0000_0000_0005L).build());
        assertThat(info.getValue().getSceneNodeType()).as("5.1 恒 0（D15）").isZero();
    }

    @Test
    void 正常停止_删除条目且之后不再发布() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));

        publisher.stop(true);
        publisher.publishOnce();

        verify(directory).remove(1, 3);
        verify(directory, never()).publish(anyInt(), anyInt(), any(), any());
    }

    @Test
    void 租约丢失停止_不删条目() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));

        publisher.stop(false);

        verify(directory, never()).remove(anyInt(), anyInt());
    }

    @Test
    void 取快照失败_本轮不发布() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> {
            throw new IllegalStateException("逻辑线程超时");
        });

        publisher.publishOnce();

        verify(directory, never()).publish(anyInt(), anyInt(), any(), any());
    }

    @Test
    void 立即补发_启动后在调度线程上发一次新快照_没启动或已停止时什么也不做() {
        AtomicLong applied = new AtomicLong(1);
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), applied.get()));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            publisher.requestPublishNow();
            verify(directory, never()).publish(anyInt(), anyInt(), any(), any());

            publisher.start(scheduler);
            verify(directory, timeout(2_000).times(1)).publish(anyInt(), anyInt(), any(), any());
            applied.set(2);
            publisher.requestPublishNow();

            ArgumentCaptor<SceneNodeInfo> info = ArgumentCaptor.forClass(SceneNodeInfo.class);
            verify(directory, timeout(2_000).times(2)).publish(eq(1), eq(3), info.capture(), any());
            assertThat(info.getAllValues()).extracting(SceneNodeInfo::getAppliedPlanVersion).containsExactly(1L, 2L);

            publisher.stop(false);
            publisher.requestPublishNow();
            verify(directory, times(2)).publish(anyInt(), anyInt(), any(), any());
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** 记下 execute 次数的调度池（周期任务走 schedule，不计）。 */
    private static final class CountingScheduler extends ScheduledThreadPoolExecutor {

        final AtomicInteger executes = new AtomicInteger();

        CountingScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            executes.incrementAndGet();
            super.execute(command);
        }
    }

    @Test
    void Redis写卡住时_请求立即补发不阻塞调用方_逻辑线程不等IO() throws Exception {
        CountDownLatch inRedis = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inRedis.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(directory).publish(anyInt(), anyInt(), any(), any());
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            publisher.start(scheduler);
            assertThat(inRedis.await(2, TimeUnit.SECONDS)).as("周期发布已进到 Redis 写里").isTrue();

            // 修前：requestPublishNow 拿 this 的监视器，发布在同一个监视器下等 Redis（慢 Redis 一次可达 4.2 s）
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                for (int i = 0; i < 100; i++) {
                    publisher.requestPublishNow();
                }
            });

            release.countDown();
            verify(directory, timeout(2_000).times(2)).publish(anyInt(), anyInt(), any(), any());
        } finally {
            release.countDown();
            publisher.stop(false);
            scheduler.shutdownNow();
        }
    }

    @Test
    void 连发两百次补发_合并成至多一个调度任务与两次发布_不多占调度线程() throws Exception {
        CountDownLatch inSnapshot = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> {
            if (snapshots.incrementAndGet() == 1) {
                inSnapshot.countDown();
                release.await(10, TimeUnit.SECONDS);   // 第一次取快照卡在「逻辑线程」上
            }
            return new Snapshot(List.of(ENTRY), 0);
        });
        CountingScheduler scheduler = new CountingScheduler();
        try {
            publisher.start(scheduler);
            assertThat(inSnapshot.await(2, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < 200; i++) {   // 一次排空推进销毁 200 个实例，每个都请求一次
                publisher.requestPublishNow();
            }
            assertThat(scheduler.executes.get()).as("周期任务正在发：只标脏，不另排任务").isZero();

            release.countDown();
            verify(directory, timeout(2_000).times(2)).publish(anyInt(), anyInt(), any(), any());
            verify(directory, after(300).times(2)).publish(anyInt(), anyInt(), any(), any());
            assertThat(snapshots.get()).as("200 次请求合并成补发一轮").isEqualTo(2);

            publisher.requestPublishNow();
            verify(directory, timeout(2_000).times(3)).publish(anyInt(), anyInt(), any(), any());
            assertThat(scheduler.executes.get()).as("空闲时的一次请求排一个任务").isEqualTo(1);
        } finally {
            release.countDown();
            publisher.stop(false);
            scheduler.shutdownNow();
        }
    }

    @Test
    void 停止等在途的Redis写结束再删条目_之后的请求与周期都不再发布() throws Exception {
        CountDownLatch inRedis = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inRedis.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(directory).publish(anyInt(), anyInt(), any(), any());
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            publisher.start(scheduler);
            assertThat(inRedis.await(2, TimeUnit.SECONDS)).isTrue();
            publisher.requestPublishNow();   // 标脏：在途的写结束后本会再发一轮

            Thread stopper = new Thread(() -> publisher.stop(true), "stopper");
            stopper.start();
            stopper.join(300);
            assertThat(stopper.isAlive()).as("在途的写没结束，停止不返回").isTrue();

            release.countDown();
            stopper.join(2_000);
            assertThat(stopper.isAlive()).isFalse();

            InOrder order = inOrder(directory);
            order.verify(directory).publish(anyInt(), anyInt(), any(), any());
            order.verify(directory).remove(1, 3);
            publisher.requestPublishNow();
            verify(directory, after(300).times(1)).publish(anyInt(), anyInt(), any(), any());
        } finally {
            release.countDown();
            scheduler.shutdownNow();
        }
    }
}
