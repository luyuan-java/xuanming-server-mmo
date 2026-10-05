package com.game.api.asset;

import com.game.api.DubboGroups;
import com.game.api.SceneAssetOpService;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道调用方的 Dubbo 客户端缓存（基线 {@code scenenode/conn.go} 的 {@code ConnCache}；guild-economy-spec §4.7「客户端缓存」）：
 * 按 scene 节点的 (host, port) 缓存编程式 {@link ReferenceConfig}{@code <SceneAssetOpService>}——直连 {@code tri://host:port}、
 * <b>{@code retries = 0}</b>（Dubbo 缺省 failover 会重发，打乱预算与指标；重投由调用方的循环负责）、{@code check = false}、
 * 缺省单次超时 800 ms（{@code caller.go:23-32} 的 CallTimeout）。同一地址上换了实例（节点号被新进程接手）就销毁旧引用再建
 * （对应 {@code ConnCache.Remove}，{@code conn.go:69-79}）；节点从目录消失时调用方用 {@link #retainOnly} / {@link #evict} 清掉。
 *
 * <p><b>不阻塞调用线程</b>：建引用时 Dubbo 会同步建连（对端不可达时可达数秒，Windows 上连接被拒也要约 2–3 s），所以建引用放在本类自己的
 * 两条守护线程（{@code scene-asset-connect}）上做，{@link #call} 只挂回调；建好之后连接断了 Dubbo 会在后台重连，期间的调用立即异常完成
 * （实测不阻塞）。Dubbo 模型在第一次建引用时才创建（见 {@link IsolatedDubboModule} 关于缺省框架模型的说明），{@link #close} 时整体销毁。
 * 调用方鉴权（{@code XM_DUBBO_SECRET} 的调用方 MAC）由 xm-api 的 SPI 过滤器自动加上，缺密钥时引用建不起来（那次调用异常完成）。
 *
 * <p>线程安全；{@link #call} 可在任意线程上调（含 Netty I/O / 逻辑线程：不阻塞）。
 */
public final class SceneAssetOpClients implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SceneAssetOpClients.class);

    /** 单次调用的缺省上限（同基线 CallTimeout 800 ms）；调用方可按剩余预算传更小的值。 */
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofMillis(800);
    /** 本地兜底超时比这次调用的超时多给的余量：正常情况下 Dubbo 自己先超时；建引用卡住时由它兜底。 */
    private static final long LOCAL_TIMEOUT_GRACE_MS = 200;
    private static final int CONNECT_THREADS = 2;

    private final String applicationName;
    private final Duration referenceTimeout;
    private final ThreadPoolExecutor connector;
    private final Object lock = new Object();
    /** address → 条目（持锁读写）。 */
    private final Map<String, Entry> entries = new HashMap<>();
    /** 只在连接线程上创建（{@link #model()}），关闭时读。 */
    private volatile IsolatedDubboModule dubbo;
    /** 持 {@link #lock}。 */
    private boolean closed;
    /** 持 this：模型已销毁，不再创建。 */
    private boolean shutDown;

    private record Entry(SceneAssetEndpoint endpoint, CompletableFuture<Client> client) {
    }

    private record Client(ReferenceConfig<SceneAssetOpService> reference, SceneAssetOpService service) {
    }

    /** @param applicationName 调用方的 Dubbo 应用名（如 {@code xm-guild-asset}） */
    public SceneAssetOpClients(String applicationName) {
        this(applicationName, DEFAULT_CALL_TIMEOUT);
    }

    /**
     * @param referenceTimeout 引用上的缺省单次超时（{@link #call} 传入的超时优先）
     */
    public SceneAssetOpClients(String applicationName, Duration referenceTimeout) {
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        if (referenceTimeout.toMillis() < 1 || referenceTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("资产通道单次超时非法: " + referenceTimeout);
        }
        this.referenceTimeout = referenceTimeout;
        AtomicInteger threads = new AtomicInteger();
        // 队列无界：任务数 ≤ 同时在建的地址数（每个地址至多一个），天然有界
        this.connector = new ThreadPoolExecutor(CONNECT_THREADS, CONNECT_THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "scene-asset-connect-" + threads.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.connector.allowCoreThreadTimeOut(true);
    }

    /**
     * 发一次资产调用。future 异常完成 = 传输失败（连不上、超时、对端过载 / 未就绪 / 鉴权失败、本缓存已关闭），结局未知，调用方按 Retry 处理。
     * 不重试；不阻塞调用线程。
     *
     * @param timeout 这一次的上限（调用方按 {@code min(800 ms, 剩余预算)} 传）；≤ 0 直接异常完成（不发包）
     */
    public CompletableFuture<AssetOpResponse> call(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request,
                                                   Duration timeout) {
        Objects.requireNonNull(rpc, "rpc");
        Objects.requireNonNull(request, "request");
        long timeoutMs = timeout == null ? 0 : Math.min(timeout.toMillis(), Integer.MAX_VALUE);
        if (timeoutMs <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("资产调用的剩余预算已用完"));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return clientAsync(endpoint)
                .thenCompose(service -> {
                    // 建引用可能已经花掉一部分预算：Dubbo 的单次超时取剩下的
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        return CompletableFuture.<AssetOpResponse>failedFuture(
                                new TimeoutException("建立资产通道客户端用完了这次调用的预算"));
                    }
                    return invoke(service, rpc, request, remaining);
                })
                .orTimeout(timeoutMs + LOCAL_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    private static CompletableFuture<AssetOpResponse> invoke(SceneAssetOpService service, AssetRpc rpc,
                                                             AssetOpRequest request, long timeoutMs) {
        CompletableFuture<AssetOpResponse> future;
        // 单次超时走 Dubbo 的调用级附件（只作用于下面这一次调用，调完即清），比引用上的缺省值优先
        RpcContext.getClientAttachment().setObjectAttachment(CommonConstants.TIMEOUT_KEY, timeoutMs);
        try {
            future = switch (rpc) {
                case DEBIT -> service.debit(request);
                case ABORT_DEBIT -> service.abortDebit(request);
                case CREDIT -> service.credit(request);
            };
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            RpcContext.getClientAttachment().removeAttachment(CommonConstants.TIMEOUT_KEY);
        }
        return future == null ? CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future")) : future;
    }

    /**
     * 取（或开始建）这个地址的客户端；同一地址上实例变了就重建；上次建失败的重新建。本缓存已关闭时异常完成。
     */
    CompletableFuture<SceneAssetOpService> clientAsync(SceneAssetEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String address = endpoint.address();
        Entry stale = null;
        Entry entry;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("资产通道客户端缓存已关闭"));
            }
            Entry existing = entries.get(address);
            if (existing != null && existing.endpoint().instanceId().equals(endpoint.instanceId())
                    && !existing.client().isCompletedExceptionally()) {
                return existing.client().thenApply(Client::service);
            }
            if (existing != null && !existing.endpoint().instanceId().equals(endpoint.instanceId())) {
                stale = existing;
                log.info("资产通道目标地址换了实例，重建客户端 address={} zone={} node={} old_instance={} new_instance={}", address,
                        endpoint.zoneId(), endpoint.nodeId(), existing.endpoint().instanceId(), endpoint.instanceId());
            }
            entry = new Entry(endpoint, new CompletableFuture<>());
            entries.put(address, entry);
        }
        destroyWhenBuilt(stale);
        Entry created = entry;
        try {
            connector.execute(() -> build(created));
        } catch (RuntimeException e) {
            // 只在关闭之后发生
            created.client().completeExceptionally(e);
        }
        return created.client().thenApply(Client::service);
    }

    /** 连接线程上：建引用（可能阻塞到建连超时）。 */
    private void build(Entry entry) {
        ReferenceConfig<SceneAssetOpService> reference = null;
        try {
            SceneAssetEndpoint endpoint = entry.endpoint();
            reference = new ReferenceConfig<>(model().module());
            reference.setInterface(SceneAssetOpService.class);
            reference.setGroup(DubboGroups.SCENE_ASSET);
            reference.setUrl("tri://" + endpoint.address());
            reference.setRetries(0);
            reference.setCheck(false);
            reference.setTimeout((int) referenceTimeout.toMillis());
            // 建的过程中被换掉 / 清掉的条目：destroyWhenBuilt 已挂好，建完即销毁（等着它的调用随之按传输失败重投）；
            // 关闭时整个模型一起销毁
            entry.client().complete(new Client(reference, reference.get()));
        } catch (RuntimeException e) {
            // 失败的条目留在表里，下一次调用看到它已异常完成就重建
            log.warn("建资产通道客户端失败 address={}: {}", entry.endpoint().address(), e.toString());
            if (reference != null) {
                destroyQuietly(reference);
            }
            entry.client().completeExceptionally(e);
        }
    }

    private IsolatedDubboModule model() {
        IsolatedDubboModule model = dubbo;
        if (model == null) {
            synchronized (this) {
                if (shutDown) {
                    throw new IllegalStateException("资产通道客户端缓存已关闭");
                }
                model = dubbo;
                if (model == null) {
                    model = IsolatedDubboModule.create(applicationName);
                    dubbo = model;
                }
            }
        }
        return model;
    }

    /** 这个地址的客户端（若实例相同）不再需要：销毁。 */
    public void evict(SceneAssetEndpoint endpoint) {
        Entry removed = null;
        synchronized (lock) {
            Entry existing = entries.get(endpoint.address());
            if (existing != null && existing.endpoint().instanceId().equals(endpoint.instanceId())) {
                entries.remove(endpoint.address());
                removed = existing;
            }
        }
        destroyWhenBuilt(removed);
    }

    /** 只保留这些节点（按地址 + 实例），其余销毁（节点从目录消失 / 换了实例）。 */
    public void retainOnly(Collection<SceneAssetEndpoint> live) {
        Set<String> keep = new HashSet<>();
        for (SceneAssetEndpoint endpoint : live) {
            keep.add(endpoint.address() + "#" + endpoint.instanceId());
        }
        List<Entry> removed = new ArrayList<>();
        synchronized (lock) {
            entries.values().removeIf(entry -> {
                if (keep.contains(entry.endpoint().address() + "#" + entry.endpoint().instanceId())) {
                    return false;
                }
                removed.add(entry);
                return true;
            });
        }
        removed.forEach(SceneAssetOpClients::destroyWhenBuilt);
    }

    /** 当前缓存的客户端数（含正在建的；指标 / 测试用）。 */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** 销毁全部客户端与 Dubbo 模型（等在建的引用至多 5 s）。幂等；之后的 {@link #call} 一律异常完成。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            entries.clear();
        }
        connector.shutdown();
        try {
            if (!connector.awaitTermination(5, TimeUnit.SECONDS)) {
                connector.shutdownNow();
            }
        } catch (InterruptedException e) {
            connector.shutdownNow();
            Thread.currentThread().interrupt();
        }
        IsolatedDubboModule model;
        synchronized (this) {
            shutDown = true;
            model = dubbo;
            dubbo = null;
        }
        if (model != null) {
            // 整个框架模型一起销毁：其上的引用与连接随之释放
            model.close();
        }
    }

    private static void destroyWhenBuilt(Entry entry) {
        if (entry != null) {
            entry.client().whenComplete((client, error) -> {
                if (client != null) {
                    destroyQuietly(client.reference());
                }
            });
        }
    }

    private static void destroyQuietly(ReferenceConfig<SceneAssetOpService> reference) {
        try {
            reference.destroy();
        } catch (RuntimeException e) {
            log.warn("销毁资产通道客户端时出错（忽略）", e);
        }
    }
}
