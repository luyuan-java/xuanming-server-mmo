package com.game.scene.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.SceneDirectoryService;
import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.scene.world.InstanceIds;
import com.game.scene.world.SceneKind;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
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

        @Override
        public CompletableFuture<CreateInstanceResponse> createInstance(CreateInstanceRequest request) {
            instanceRequests.add(request);
            return nextInstanceReply;
        }

        // 批次 5.4 先行件 a：接口多了两个方法，这里只为能编译；跨 zone 选目标的用例由先行件 b / S2 接上。
        @Override
        public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
            throw new UnsupportedOperationException();
        }
    };
    private final List<CreateInstanceRequest> instanceRequests = new CopyOnWriteArrayList<>();
    private volatile CompletableFuture<CreateInstanceResponse> nextInstanceReply = new CompletableFuture<>();

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

    // ------------------------------------------------------------------ 实例取号（批次 5.3，dungeon-mirror-spec §6.5、§6.7）

    /** 取一次号，等结果投递进逻辑队列后在测试线程上执行，返回结果。 */
    private InstanceIds.Result createAndAwait(InstanceIds.Request request) throws InterruptedException {
        CompletableFuture<InstanceIds.Result> result = new CompletableFuture<>();
        client.create(request, result::complete);
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果投递到了逻辑执行器").isNotNull();
        assertThat(result).as("回调不在别的线程上直接跑").isNotDone();
        task.run();
        return result.getNow(null);
    }

    private static final InstanceIds.Request MIRROR = new InstanceIds.Request(1001, SceneKind.MIRROR, 50, 1, 2, 0);

    @Test
    void 取号请求带上zone_本节点号_种类与源_发了号映射成Issued() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextInstanceReply = CompletableFuture.completedFuture(CreateInstanceResponse.newBuilder()
                .setSceneNodeId(3).setSceneId(0x8000_0000_0000_0001L).build());

        InstanceIds.Result result = createAndAwait(MIRROR);

        assertThat(result).isEqualTo(new InstanceIds.Result.Issued(3, 0x8000_0000_0000_0001L));
        assertThat(instanceRequests).singleElement().isEqualTo(CreateInstanceRequest.newBuilder()
                .setZoneId(7).setRequesterSceneNodeId(3).setPlayerId(1001).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
                .setSourceSceneId(50).setSceneConfigId(1).setMirrorConfigId(2).build());
        assertThat(requests).as("不经选目标").isEmpty();
    }

    @Test
    void 副本取号_种类DUNGEON_玩家与源为0() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextInstanceReply = CompletableFuture.completedFuture(CreateInstanceResponse.newBuilder()
                .setSceneNodeId(3).setSceneId(77).build());

        createAndAwait(new InstanceIds.Request(0, SceneKind.DUNGEON, 0, 17, 0, 1));

        assertThat(instanceRequests).singleElement().isEqualTo(CreateInstanceRequest.newBuilder()
                .setZoneId(7).setRequesterSceneNodeId(3).setKind(ChannelKind.CHANNEL_KIND_DUNGEON)
                .setSceneConfigId(17).setDungeonConfigId(1).build());
    }

    @Test
    void 取号tip非0映射成Refused_异常与超时与应答残缺映射成Failed() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextInstanceReply = CompletableFuture.completedFuture(CreateInstanceResponse.newBuilder().setTipId(3005).build());
        assertThat(createAndAwait(MIRROR)).isEqualTo(new InstanceIds.Result.Refused(3005));

        nextInstanceReply = CompletableFuture.failedFuture(new IllegalStateException("发号租约无效"));
        assertThat(createAndAwait(MIRROR)).isInstanceOf(InstanceIds.Result.Failed.class);

        nextInstanceReply = CompletableFuture.completedFuture(CreateInstanceResponse.newBuilder().setSceneNodeId(3).build());
        assertThat(createAndAwait(MIRROR)).as("号为 0").isInstanceOf(InstanceIds.Result.Failed.class);

        nextInstanceReply = CompletableFuture.completedFuture(CreateInstanceResponse.newBuilder().setSceneId(9).build());
        assertThat(createAndAwait(MIRROR)).as("节点号为 0").isInstanceOf(InstanceIds.Result.Failed.class);

        client.close();
        client = newClient(Duration.ofMillis(200));
        nextInstanceReply = new CompletableFuture<>();
        assertThat(createAndAwait(MIRROR)).isEqualTo(new InstanceIds.Result.Failed("取号超时"));
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
