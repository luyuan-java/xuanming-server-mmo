package com.game.scene.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.scene.world.RemoteSwitchTargets.Selection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * scene → scene-manager 选目标客户端（批次 5.2，scene-handoff-spec §5.4、Q7）：请求字段、结果映射、结果只经逻辑执行器投递、
 * 本地兜底超时、建引用失败重建、关闭后失败。不连真 Dubbo（连接器是假的）。
 */
class SceneManagerSwitchTargetsTest {

    /** 假的「逻辑线程」：只收任务，测试线程自己取出来跑。 */
    private final LinkedBlockingQueue<Runnable> logicQueue = new LinkedBlockingQueue<>();
    private final Executor logic = logicQueue::add;
    private final List<SelectSwitchTargetRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger connects = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private volatile CompletableFuture<SelectSwitchTargetResponse> nextReply = new CompletableFuture<>();
    private volatile RuntimeException connectFailure;
    private SceneManagerSwitchTargets client;

    private final SceneDirectoryService service = new SceneDirectoryService() {
        @Override
        public CompletableFuture<AssignSceneResponse> assign(AssignSceneRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<SelectSwitchTargetResponse> selectSwitchTarget(SelectSwitchTargetRequest request) {
            requests.add(request);
            return nextReply;
        }
    };

    private SceneManagerSwitchTargets newClient(Duration timeout) {
        return new SceneManagerSwitchTargets(7, 3, logic, timeout, new SceneManagerSwitchTargets.Connector() {
            @Override
            public SceneDirectoryService connect() {
                connects.incrementAndGet();
                RuntimeException failure = connectFailure;
                if (failure != null) {
                    throw failure;
                }
                return service;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    /** 选一次，等结果投递进逻辑队列后在测试线程上执行，返回结果。 */
    private Selection selectAndAwait() throws InterruptedException {
        CompletableFuture<Selection> result = new CompletableFuture<>();
        client.select(1001, 50, 60, 2, result::complete);
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果投递到了逻辑执行器").isNotNull();
        assertThat(result).as("回调不在别的线程上直接跑").isNotDone();
        task.run();
        return result.getNow(null);
    }

    @Test
    void 请求带上zone_本节点号_当前场景与想去的场景_选中映射成Chosen() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextReply = CompletableFuture.completedFuture(SelectSwitchTargetResponse.newBuilder()
                .setSceneNodeId(9).setSceneId(900_001).setSceneConfigId(2).build());

        Selection selection = selectAndAwait();

        assertThat(selection).isEqualTo(new Selection.Chosen(9, 900_001, 2));
        assertThat(requests).singleElement().isEqualTo(SelectSwitchTargetRequest.newBuilder()
                .setZoneId(7).setPlayerId(1001).setFromSceneNodeId(3).setFromSceneId(50).setWantSceneId(60)
                .setWantSceneConfigId(2).build());
    }

    @Test
    void tip非0映射成Refused() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextReply = CompletableFuture.completedFuture(SelectSwitchTargetResponse.newBuilder().setTipId(3000).build());

        assertThat(selectAndAwait()).isEqualTo(new Selection.Refused(3000));
    }

    @Test
    void 调用异常完成映射成Failed() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextReply = CompletableFuture.failedFuture(new IllegalStateException("场景目录暂不可用"));

        assertThat(selectAndAwait()).isInstanceOf(Selection.Failed.class);
    }

    @Test
    void 一直不回_本地兜底超时到了回Failed() throws Exception {
        client = newClient(Duration.ofMillis(200));

        Selection selection = selectAndAwait();

        assertThat(selection).isEqualTo(new Selection.Failed("选目标超时"));
    }

    @Test
    void 建引用失败_这次Failed_下次重建() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        connectFailure = new IllegalStateException("缺 XM_DUBBO_SECRET");
        assertThat(selectAndAwait()).isInstanceOf(Selection.Failed.class);

        connectFailure = null;
        nextReply = CompletableFuture.completedFuture(SelectSwitchTargetResponse.newBuilder()
                .setSceneNodeId(9).setSceneId(1).setSceneConfigId(1).build());
        assertThat(selectAndAwait()).isInstanceOf(Selection.Chosen.class);
        assertThat(connects.get()).isEqualTo(2);

        selectAndAwait();
        assertThat(connects.get()).as("建好之后复用").isEqualTo(2);
    }

    @Test
    void 关闭后_调用回Failed_连接器被关() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        client.close();

        assertThat(selectAndAwait()).isInstanceOf(Selection.Failed.class);
        assertThat(closes.get()).isEqualTo(1);
        client.close();
        assertThat(closes.get()).as("幂等").isEqualTo(1);
    }

    @Test
    void 逻辑线程已停_结果丢弃不抛() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        client = new SceneManagerSwitchTargets(7, 3, task -> {
            rejected.countDown();
            throw new RejectedExecutionException("逻辑线程已停");
        }, Duration.ofSeconds(4), new SceneManagerSwitchTargets.Connector() {
            @Override
            public SceneDirectoryService connect() {
                return service;
            }

            @Override
            public void close() {
            }
        });
        nextReply = CompletableFuture.completedFuture(SelectSwitchTargetResponse.getDefaultInstance());

        client.select(1001, 50, 60, 2, selection -> {
            throw new AssertionError("不该执行");
        });

        assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
