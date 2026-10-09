package com.game.scene.transfer;

import com.game.api.SceneDirectoryService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.scene.world.InstanceIds;
import com.game.scene.world.RemoteSwitchTargets;
import com.game.scene.world.TravelTargets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.dubbo.config.ReferenceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * scene → scene-manager 的选目标客户端（{@link SceneDirectoryService#selectSwitchTarget}，scene-handoff-spec §5.4、Q7）：编程式 Dubbo 引用
 * （自己的 {@link IsolatedDubboModule}，同资产通道的做法），直连 {@code xm.scene.scene-manager-url}（缺省 {@code tri://127.0.0.1:20882}，与 login 同），
 * {@code retries = 0}（选目标会写软预占，重发打乱人数；失败直接推 1003，由玩家再发 63）、{@code check = false}（scene-manager 不在不影响 scene 启动）、
 * 单次超时 {@link #DUBBO_TIMEOUT}（= scene-manager 提供方超时）。调用方鉴权（{@code XM_DUBBO_SECRET}）由 xm-api 的 SPI 过滤器自动带上。
 *
 * <p><b>线程</b>：{@link #select} 在场景逻辑线程上调，<b>不阻塞</b>——建引用（对端不可达时 Dubbo 会同步建连数秒）与发起调用都在本类自己的
 * 一条守护线程（{@code scene-switch-rpc}）上做；结果（含本地兜底超时 {@code xm.scene.switch-resolve-timeout}）经 {@code logic} 投递回逻辑线程，
 * 恰好一次、不在调用栈内。引用建失败时下一次调用重建。
 *
 * <p>批次 5.3（dungeon-mirror-spec §6.5、§6.6）：同一个引用也做镜像 / 副本实例取号（{@link SceneDirectoryService#createInstance}，
 * {@link InstanceIds}）——不幂等，同样 {@code retries = 0}；结果投递与本地兜底超时同选目标。业务拒绝（tip ≠ 0）→ {@link InstanceIds.Result.Refused}；
 * 调用失败 / 超时 / 发号租约无效（提供方以异常完成）/ 应答残缺（节点号或场景号为 0）→ {@link InstanceIds.Result.Failed}。
 */
public final class SceneManagerSwitchTargets implements RemoteSwitchTargets, InstanceIds, TravelTargets, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneManagerSwitchTargets.class);

    /** scene-manager 提供方的单次超时（xm-scene-manager {@code dubbo.provider.timeout = 3000}），引用上取同值。本地兜底超时必须大于它。 */
    public static final Duration DUBBO_TIMEOUT = Duration.ofSeconds(3);
    /** Dubbo 应用名（进 URL，便于在 scene-manager 日志里认出调用方）。 */
    static final String APPLICATION = "xm-scene-switch";
    /** 先行件阶段 {@link #selectTravel} 的占位结果里的原因（真实现接上后删掉）。 */
    static final String TRAVEL_PLACEHOLDER = "5.4 施工中";

    /** 建一个 scene-manager 服务引用（阻塞，只在本类的连接线程上调）。 */
    interface Connector extends AutoCloseable {

        SceneDirectoryService connect();

        /** 销毁已建的引用与 Dubbo 模型。幂等。 */
        @Override
        void close();
    }

    private final int zoneId;
    private final int nodeId;
    private final Executor logic;
    private final Duration timeout;
    private final Connector connector;
    private final ThreadPoolExecutor rpcThread;
    private final Object lock = new Object();
    /** 当前（或正在建）的引用；持 {@link #lock} 读写。 */
    private CompletableFuture<SceneDirectoryService> client;
    private boolean closed;

    /**
     * @param zoneId  本节点的 zone（请求里的 zone_id）
     * @param nodeId  本节点号（请求里的 from_scene_node_id）
     * @param logic   投递回场景逻辑线程（已停止时抛拒绝异常，结果丢弃）
     * @param timeout 本地兜底超时（必须大于 {@link #DUBBO_TIMEOUT}，由配置校验）
     */
    SceneManagerSwitchTargets(int zoneId, int nodeId, Executor logic, Duration timeout, Connector connector) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("选目标超时必须为正: " + timeout);
        }
        this.zoneId = zoneId;
        this.nodeId = nodeId;
        this.logic = Objects.requireNonNull(logic, "logic");
        this.timeout = timeout;
        this.connector = Objects.requireNonNull(connector, "connector");
        // 一条线程：建引用最多一个在途；调用本身只是序列化 + 交给连接（不阻塞），排队很短。队列无界：入队的只有在途 63 的远端去向，
        // 每个玩家至多一个（RESOLVING 期间再发 63 回 3014），天然有界
        this.rpcThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "scene-switch-rpc");
            t.setDaemon(true);
            return t;
        });
    }

    /** 生产装配：直连 {@code url}（如 {@code tri://127.0.0.1:20882}）的 Dubbo 引用，第一次调用时才建。 */
    public static SceneManagerSwitchTargets dubbo(String url, int zoneId, int nodeId, Executor logic, Duration timeout) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("xm.scene.scene-manager-url 不能为空");
        }
        return new SceneManagerSwitchTargets(zoneId, nodeId, logic, timeout, new DubboConnector(url));
    }

    @Override
    public void select(long playerId, long fromSceneId, long wantSceneId, int wantSceneConfigId,
                       Consumer<Selection> onDone) {
        SelectSwitchTargetRequest request = SelectSwitchTargetRequest.newBuilder()
                .setZoneId(zoneId)
                .setPlayerId(playerId)
                .setFromSceneNodeId(nodeId)
                .setFromSceneId(fromSceneId)
                .setWantSceneId(wantSceneId)
                .setWantSceneConfigId(wantSceneConfigId)
                .build();
        call(service -> service.selectSwitchTarget(request))
                .whenComplete((response, failure) -> deliver(playerId, onDone, toSelection(response, failure)));
    }

    /** 实例取号（批次 5.3）：zone 与发起节点取本节点的；任意线程可调，结果经逻辑执行器投递、恰好一次。 */
    @Override
    public void create(InstanceIds.Request request, Consumer<InstanceIds.Result> onDone) {
        CreateInstanceRequest wire = CreateInstanceRequest.newBuilder()
                .setZoneId(zoneId)
                .setRequesterSceneNodeId(nodeId)
                .setPlayerId(request.playerId())
                .setKind(request.kind().channelKind())
                .setSourceSceneId(request.sourceSceneId())
                .setSceneConfigId(request.sceneConfigId())
                .setMirrorConfigId(request.mirrorConfigId())
                .setDungeonConfigId(request.dungeonConfigId())
                .build();
        call(service -> service.createInstance(wire))
                .whenComplete((response, failure) -> deliver(request.playerId(), onDone, toResult(response, failure)));
    }

    /**
     * 跨 zone 传送的选目标（批次 5.4，{@link SceneDirectoryService#selectTravelTarget}）。<b>先行件阶段的占位</b>：不发调用，
     * 经逻辑执行器回 {@link TravelSelection.Failed}（恰好一次、不在调用栈内，线程纪律与真实现相同）；调用方按「选目标失败」处理
     * （226 受理后推 23 {3027}、留在原地）。真实现复用 {@link #call} / {@link #deliver} 与本地兜底超时。
     */
    @Override
    public void selectTravel(long playerId, int toZoneId, int wantSceneConfigId, Consumer<TravelSelection> onDone) {
        deliver(playerId, onDone, new TravelSelection.Failed(TRAVEL_PLACEHOLDER));
    }

    /** 在连接线程上取引用并发起调用，套上本地兜底超时。 */
    private <R> CompletableFuture<R> call(Function<SceneDirectoryService, CompletableFuture<R>> invocation) {
        CompletableFuture<R> call;
        try {
            call = clientAsync().thenComposeAsync(service -> invoke(service, invocation), rpcThread);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static <R> CompletableFuture<R> invoke(SceneDirectoryService service,
                                                   Function<SceneDirectoryService, CompletableFuture<R>> invocation) {
        try {
            CompletableFuture<R> future = invocation.apply(service);
            return future == null ? CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future"))
                    : future;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static InstanceIds.Result toResult(CreateInstanceResponse response, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            return new InstanceIds.Result.Failed(cause instanceof TimeoutException ? "取号超时" : cause.toString());
        }
        if (response == null) {
            return new InstanceIds.Result.Failed("空应答");
        }
        if (response.getTipId() != 0) {
            return new InstanceIds.Result.Refused(response.getTipId());
        }
        if (response.getSceneNodeId() == 0 || response.getSceneId() == 0) {
            return new InstanceIds.Result.Failed("应答残缺 node=" + response.getSceneNodeId() + " scene_id="
                    + Long.toUnsignedString(response.getSceneId()));
        }
        return new InstanceIds.Result.Issued(response.getSceneNodeId(), response.getSceneId());
    }

    /** 取（或开始建）引用；上次建失败的重新建。已关闭时异常完成。 */
    private CompletableFuture<SceneDirectoryService> clientAsync() {
        CompletableFuture<SceneDirectoryService> building;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("选目标客户端已关闭"));
            }
            if (client != null && !client.isCompletedExceptionally()) {
                return client;
            }
            building = new CompletableFuture<>();
            client = building;
        }
        try {
            rpcThread.execute(() -> {
                try {
                    building.complete(connector.connect());
                } catch (RuntimeException e) {
                    log.warn("建 scene-manager 选目标客户端失败（下次调用重建）: {}", e.toString());
                    building.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            building.completeExceptionally(e);
        }
        return building;
    }

    static Selection toSelection(SelectSwitchTargetResponse response, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            return new Selection.Failed(cause instanceof TimeoutException ? "选目标超时" : cause.toString());
        }
        if (response == null) {
            return new Selection.Failed("空应答");
        }
        if (response.getTipId() != 0) {
            return new Selection.Refused(response.getTipId());
        }
        return new Selection.Chosen(response.getSceneNodeId(), response.getSceneId(), response.getSceneConfigId());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private <T> void deliver(long playerId, Consumer<T> onDone, T result) {
        try {
            logic.execute(() -> onDone.accept(result));
        } catch (RejectedExecutionException e) {
            log.debug("场景逻辑线程已停止，丢弃选目标 / 取号结果 player={}", Long.toUnsignedString(playerId));
        }
    }

    /** 停掉连接线程并销毁引用与 Dubbo 模型。幂等；之后的 {@link #select} 回 {@link Selection.Failed}。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        rpcThread.shutdown();
        try {
            if (!rpcThread.awaitTermination(5, TimeUnit.SECONDS)) {
                rpcThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            rpcThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        connector.close();
    }

    /** 直连 scene-manager 的 Dubbo 引用（Dubbo 模型在第一次建引用时才创建，只在连接线程上访问）。 */
    private static final class DubboConnector implements Connector {

        private final String url;
        private IsolatedDubboModule model;
        private ReferenceConfig<SceneDirectoryService> reference;

        DubboConnector(String url) {
            this.url = url;
        }

        @Override
        public synchronized SceneDirectoryService connect() {
            if (model == null) {
                model = IsolatedDubboModule.create(APPLICATION);
            }
            destroyReference();
            ReferenceConfig<SceneDirectoryService> ref = new ReferenceConfig<>(model.module());
            ref.setInterface(SceneDirectoryService.class);
            ref.setUrl(url);
            ref.setRetries(0);
            ref.setCheck(false);
            ref.setTimeout((int) DUBBO_TIMEOUT.toMillis());
            reference = ref;
            SceneDirectoryService service = ref.get();
            log.info("scene-manager 选目标客户端就绪 url={}", url);
            return service;
        }

        private void destroyReference() {
            if (reference != null) {
                try {
                    reference.destroy();
                } catch (RuntimeException e) {
                    log.warn("销毁旧的 scene-manager 引用时出错（忽略）", e);
                }
                reference = null;
            }
        }

        @Override
        public synchronized void close() {
            destroyReference();
            if (model != null) {
                model.close();
                model = null;
            }
        }
    }
}
