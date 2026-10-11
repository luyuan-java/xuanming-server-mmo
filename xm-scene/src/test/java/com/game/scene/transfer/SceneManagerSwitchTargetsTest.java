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
import com.game.api.proto.ZoneRedirect;
import com.game.scene.world.RemoteSwitchTargets.Selection;
import com.game.scene.world.TravelTargets.TravelSelection;
import com.google.protobuf.ByteString;
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

        @Override
        public CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request) {
            travelRequests.add(request);
            return nextTravelReply;
        }

        // 登录期重定向（GO-5）只由 login 调，scene 不用
        @Override
        public CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request) {
            throw new UnsupportedOperationException();
        }
    };
    private final List<SelectTravelTargetRequest> travelRequests = new CopyOnWriteArrayList<>();
    private volatile CompletableFuture<SelectTravelTargetResponse> nextTravelReply = new CompletableFuture<>();
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

    // ------------------------------------------------------------------ 跨 zone 传送的选目标（批次 5.4，zone-travel-spec §5.4、§5.5）

    /** scene-manager 签好的重定向：目标 zone 2 的 4 号 gate。票据是任意字节（scene 不解析、只原样转发，验票在目标 gate 上）。 */
    private static final ZoneRedirect REDIRECT = ZoneRedirect.newBuilder().setTargetZoneId(2).setGateNodeId(4)
            .setGateHost("10.0.0.2").setGatePort(11010)
            .setTokenPayload(ByteString.copyFrom(new byte[] {0x08, 0x04, 0x10, 0x02, (byte) 0xff, 0x00}))
            .setTokenSignature(ByteString.copyFromUtf8("ab".repeat(32))).setTokenDeadline(1_700_000_300L).build();

    private static SelectTravelTargetResponse chosen(int sceneConfigId, ZoneRedirect redirect) {
        return SelectTravelTargetResponse.newBuilder().setSceneConfigId(sceneConfigId).setRedirect(redirect).build();
    }

    /** 选一次跨 zone 目标，等结果投递进逻辑队列后在测试线程上执行，返回结果；断言恰好一次。 */
    private TravelSelection travelAndAwait(int toZoneId, int wantSceneConfigId) throws InterruptedException {
        List<TravelSelection> results = new CopyOnWriteArrayList<>();
        client.selectTravel(1001, toZoneId, wantSceneConfigId, results::add);
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果投递到了逻辑执行器").isNotNull();
        assertThat(results).as("回调不在别的线程上直接跑").isEmpty();
        task.run();
        assertThat(logicQueue.poll(100, TimeUnit.MILLISECONDS)).as("恰好一次").isNull();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void 跨zone选目标_请求带上源zone_目标zone_玩家与想去的地图_选中映射成Chosen_票据原样() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        SelectTravelTargetResponse reply = chosen(5, REDIRECT);
        nextTravelReply = CompletableFuture.completedFuture(reply);

        TravelSelection selection = travelAndAwait(2, 5);

        assertThat(selection).isEqualTo(new TravelSelection.Chosen(5, REDIRECT));
        assertThat(((TravelSelection.Chosen) selection).redirect().getTokenPayload()).as("票据字节原样，不解析、不重组")
                .isSameAs(reply.getRedirect().getTokenPayload());
        assertThat(travelRequests).singleElement().isEqualTo(SelectTravelTargetRequest.newBuilder()
                .setFromZoneId(7).setToZoneId(2).setPlayerId(1001).setWantSceneConfigId(5).build());
        assertThat(requests).as("不经 63 的选目标").isEmpty();
    }

    @Test
    void 跨zone选目标_不指定地图_请求带0_应答里是解析后的默认主世界() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextTravelReply = CompletableFuture.completedFuture(chosen(1, REDIRECT));

        assertThat(travelAndAwait(2, 0)).isEqualTo(new TravelSelection.Chosen(1, REDIRECT));
        assertThat(travelRequests).singleElement().extracting(SelectTravelTargetRequest::getWantSceneConfigId).isEqualTo(0);
    }

    @Test
    void 跨zone选目标_tip非0映射成Refused() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextTravelReply = CompletableFuture.completedFuture(SelectTravelTargetResponse.newBuilder().setTipId(3000).build());
        assertThat(travelAndAwait(2, 5)).isEqualTo(new TravelSelection.Refused(3000));

        nextTravelReply = CompletableFuture.completedFuture(chosen(5, REDIRECT).toBuilder().setTipId(3005).build());
        assertThat(travelAndAwait(2, 5)).as("带了 redirect 也按拒绝").isEqualTo(new TravelSelection.Refused(3005));
    }

    @Test
    void 跨zone选目标_调用异常完成与本地兜底超时映射成Failed() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        nextTravelReply = CompletableFuture.failedFuture(new IllegalStateException("频道计划读不到"));
        assertThat(travelAndAwait(2, 5)).isInstanceOf(TravelSelection.Failed.class);

        nextTravelReply = CompletableFuture.completedFuture(null);
        assertThat(travelAndAwait(2, 5)).isEqualTo(new TravelSelection.Failed("空应答"));

        client.close();
        client = newClient(Duration.ofMillis(200));
        nextTravelReply = new CompletableFuture<>();
        assertThat(travelAndAwait(2, 5)).isEqualTo(new TravelSelection.Failed("选目标超时"));
    }

    /** tip = 0 而应答残缺（TravelSelection.Failed 的 javadoc 列的几种，加上目标 zone 对不上）：按调用失败，不当成选中。 */
    @Test
    void 跨zone选目标_应答残缺逐项映射成Failed() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        record Case(String what, SelectTravelTargetResponse reply) {
        }
        List<Case> cases = List.of(
                new Case("地图为 0", chosen(0, REDIRECT)),
                new Case("没带 redirect", SelectTravelTargetResponse.newBuilder().setSceneConfigId(5).build()),
                new Case("目标 zone 对不上", chosen(5, REDIRECT.toBuilder().setTargetZoneId(3).build())),
                new Case("目标 zone 为 0", chosen(5, REDIRECT.toBuilder().setTargetZoneId(0).build())),
                new Case("gate 地址空", chosen(5, REDIRECT.toBuilder().setGateHost("").build())),
                new Case("gate 地址全是空白", chosen(5, REDIRECT.toBuilder().setGateHost("  ").build())),
                new Case("端口 0", chosen(5, REDIRECT.toBuilder().setGatePort(0).build())),
                new Case("端口 65536", chosen(5, REDIRECT.toBuilder().setGatePort(65536).build())),
                new Case("端口超出 int（uint32 读成负数）", chosen(5, REDIRECT.toBuilder().setGatePort(0x8000_0050).build())),
                new Case("票据为空", chosen(5, REDIRECT.toBuilder().clearTokenPayload().build())),
                new Case("签名为空", chosen(5, REDIRECT.toBuilder().clearTokenSignature().build())),
                new Case("过期时刻 0", chosen(5, REDIRECT.toBuilder().setTokenDeadline(0).build())),
                new Case("过期时刻为负", chosen(5, REDIRECT.toBuilder().setTokenDeadline(-1).build())));
        for (Case c : cases) {
            nextTravelReply = CompletableFuture.completedFuture(c.reply());
            assertThat(travelAndAwait(2, 5)).as(c.what()).isInstanceOfSatisfying(TravelSelection.Failed.class,
                    failed -> assertThat(failed.reason()).startsWith("应答残缺"));
        }

        nextTravelReply = CompletableFuture.completedFuture(chosen(5, REDIRECT.toBuilder().setGatePort(65535).build()));
        assertThat(travelAndAwait(2, 5)).as("端口上界 65535 合法").isInstanceOf(TravelSelection.Chosen.class);
        nextTravelReply = CompletableFuture.completedFuture(chosen(5, REDIRECT.toBuilder().setGatePort(1).build()));
        assertThat(travelAndAwait(2, 5)).as("端口下界 1 合法").isInstanceOf(TravelSelection.Chosen.class);
    }

    @Test
    void 跨zone选目标_与63共用引用_建引用失败这次Failed下次重建_关闭后Failed() throws Exception {
        client = newClient(Duration.ofSeconds(4));
        connectFailure = new IllegalStateException("缺 XM_DUBBO_SECRET");
        assertThat(travelAndAwait(2, 5)).isInstanceOf(TravelSelection.Failed.class);

        connectFailure = null;
        nextTravelReply = CompletableFuture.completedFuture(chosen(5, REDIRECT));
        nextReply = CompletableFuture.completedFuture(SelectSwitchTargetResponse.newBuilder()
                .setSceneNodeId(9).setSceneId(1).setSceneConfigId(1).build());
        assertThat(travelAndAwait(2, 5)).isInstanceOf(TravelSelection.Chosen.class);
        assertThat(selectAndAwait()).isInstanceOf(Selection.Chosen.class);
        assertThat(connects.get()).as("226 与 63 共用同一个引用").isEqualTo(2);

        client.close();
        assertThat(travelAndAwait(2, 5)).isInstanceOf(TravelSelection.Failed.class);
    }

    @Test
    void 跨zone选目标_逻辑线程已停_结果丢弃不抛() throws Exception {
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
        nextTravelReply = CompletableFuture.completedFuture(chosen(5, REDIRECT));

        client.selectTravel(1001, 2, 0, selection -> {
            throw new AssertionError("不该执行");
        });

        assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(travelRequests).hasSize(1);
    }
}
