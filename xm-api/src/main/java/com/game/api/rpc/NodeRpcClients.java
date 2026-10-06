package com.game.api.rpc;

import com.game.api.asset.IsolatedDubboModule;
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
import java.util.function.Function;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按节点直连的 Dubbo 客户端缓存（{@code SceneAssetOpClients} 的泛化；scene-battle-spec Q20、match-spec §… 的形状）：按目标节点的
 * (host, port) 缓存编程式 {@link ReferenceConfig}{@code <S>}——直连 {@code tri://host:port}、指定 group、<b>{@code retries = 0}</b>
 * （Dubbo 缺省 failover 会重发；重投由调用方负责）、{@code check = false}。同一地址上换了实例（节点号被新进程接手）就销毁旧引用再建；
 * 节点从目录消失时调用方用 {@link #retainOnly} / {@link #evict} 清掉。
 *
 * <p><b>不阻塞调用线程</b>：建引用时 Dubbo 会同步建连（对端不可达时可达数秒），所以建引用放在本类自己的两条守护线程上做，
 * {@link #call} 只挂回调。Dubbo 模型在第一次建引用时才创建（见 {@link IsolatedDubboModule} 关于缺省框架模型的说明），{@link #close} 时整体销毁。
 * 重连 / 心跳参数同资产通道（{@link #RECONNECT_INTERVAL}）：对端重启后 1 s 级重连，而不是 Dubbo 缺省的 60 s。
 * 调用方鉴权（{@code XM_DUBBO_SECRET} 的调用方 MAC）由 xm-api 的 SPI 过滤器自动加上，缺密钥时引用建不起来（那次调用异常完成）。
 *
 * <p>线程安全；{@link #call} 可在任意线程上调（不阻塞）。
 *
 * @param <S> 服务接口
 */
public final class NodeRpcClients<S> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeRpcClients.class);

    /** 重连间隔与空闲心跳（理由见 {@code SceneAssetOpClients#RECONNECT_INTERVAL}）。 */
    public static final Duration RECONNECT_INTERVAL = Duration.ofSeconds(1);
    /** 本地兜底超时比这次调用的超时多给的余量：正常情况下 Dubbo 自己先超时；建引用卡住时由它兜底。 */
    private static final long LOCAL_TIMEOUT_GRACE_MS = 200;
    private static final int CONNECT_THREADS = 2;

    /**
     * 一个目标节点（来自 Redis 节点目录）。
     *
     * @param host       Dubbo Triple 通告地址
     * @param port       Dubbo Triple 端口（1..65535）
     * @param instanceId 实例 id（进程启动时随机生成；换了实例就重建引用）
     */
    public record Target(String host, int port, String instanceId) {

        public Target {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("直连地址 host 为空");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("直连端口超出范围: " + port);
            }
            instanceId = instanceId == null ? "" : instanceId;
        }

        /** {@code host:port}（IPv6 字面量加方括号），用作缓存键与 Dubbo 直连 URL 的地址段。 */
        public String address() {
            return (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    private final String applicationName;
    private final Class<S> type;
    private final String group;
    private final Duration referenceTimeout;
    private final String name;
    private final ThreadPoolExecutor connector;
    private final Object lock = new Object();
    /** address → 条目（持锁读写）。 */
    private final Map<String, Entry<S>> entries = new HashMap<>();
    /** 只在连接线程上创建（{@link #model()}），关闭时读。 */
    private volatile IsolatedDubboModule dubbo;
    /** 持 {@link #lock}。 */
    private boolean closed;
    /** 持 this：模型已销毁，不再创建。 */
    private boolean shutDown;

    private record Entry<S>(Target target, CompletableFuture<Client<S>> client) {
    }

    private record Client<S>(ReferenceConfig<S> reference, S service) {
    }

    /**
     * @param applicationName  调用方的 Dubbo 应用名（如 {@code xm-battle-scene}）
     * @param type             服务接口
     * @param group            Dubbo group（{@code DubboGroups.*}）
     * @param referenceTimeout 引用上的缺省单次超时（{@link #call} 传入的超时优先）
     * @param threadName       建引用的守护线程名前缀（如 {@code scene-battle-connect}）
     */
    public NodeRpcClients(String applicationName, Class<S> type, String group, Duration referenceTimeout, String threadName) {
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.type = Objects.requireNonNull(type, "type");
        this.group = Objects.requireNonNull(group, "group");
        if (referenceTimeout.toMillis() < 1 || referenceTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("单次超时非法: " + referenceTimeout);
        }
        this.referenceTimeout = referenceTimeout;
        this.name = Objects.requireNonNull(threadName, "threadName");
        AtomicInteger threads = new AtomicInteger();
        // 队列无界：任务数 ≤ 同时在建的地址数（每个地址至多一个），天然有界
        this.connector = new ThreadPoolExecutor(CONNECT_THREADS, CONNECT_THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, threadName + "-" + threads.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.connector.allowCoreThreadTimeOut(true);
    }

    /**
     * 发一次调用。future 异常完成 = 传输失败（连不上、超时、对端过载 / 未就绪 / 鉴权失败、本缓存已关闭），结局未知。不重试；不阻塞调用线程。
     *
     * @param timeout    这一次的上限；≤ 0 直接异常完成（不发包）
     * @param invocation 在引用上发起调用（返回 Dubbo 的异步 future）
     */
    public <R> CompletableFuture<R> call(Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        long timeoutMs = timeout == null ? 0 : Math.min(timeout.toMillis(), Integer.MAX_VALUE);
        if (timeoutMs <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("这次调用的剩余预算已用完"));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return clientAsync(target)
                .thenCompose(service -> {
                    // 建引用可能已经花掉一部分预算：Dubbo 的单次超时取剩下的
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        return CompletableFuture.<R>failedFuture(new TimeoutException("建立客户端用完了这次调用的预算"));
                    }
                    return invoke(service, invocation, remaining);
                })
                .orTimeout(timeoutMs + LOCAL_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    private static <S, R> CompletableFuture<R> invoke(S service, Function<S, CompletableFuture<R>> invocation, long timeoutMs) {
        CompletableFuture<R> future;
        // 单次超时走 Dubbo 的调用级附件（只作用于下面这一次调用，调完即清），比引用上的缺省值优先
        RpcContext.getClientAttachment().setObjectAttachment(CommonConstants.TIMEOUT_KEY, timeoutMs);
        try {
            future = invocation.apply(service);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            RpcContext.getClientAttachment().removeAttachment(CommonConstants.TIMEOUT_KEY);
        }
        return future == null ? CompletableFuture.failedFuture(new IllegalStateException("Dubbo 返回了空的 future")) : future;
    }

    /** 取（或开始建）这个地址的客户端；同一地址上实例变了就重建；上次建失败的重新建。本缓存已关闭时异常完成。 */
    public CompletableFuture<S> clientAsync(Target target) {
        Objects.requireNonNull(target, "target");
        String address = target.address();
        Entry<S> stale = null;
        Entry<S> entry;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException(name + " 客户端缓存已关闭"));
            }
            Entry<S> existing = entries.get(address);
            if (existing != null && existing.target().instanceId().equals(target.instanceId())
                    && !existing.client().isCompletedExceptionally()) {
                return existing.client().thenApply(Client::service);
            }
            if (existing != null && !existing.target().instanceId().equals(target.instanceId())) {
                stale = existing;
                log.info("{} 目标地址换了实例，重建客户端 address={} old_instance={} new_instance={}", name, address,
                        existing.target().instanceId(), target.instanceId());
            }
            entry = new Entry<>(target, new CompletableFuture<>());
            entries.put(address, entry);
        }
        destroyWhenBuilt(stale);
        Entry<S> created = entry;
        try {
            connector.execute(() -> build(created));
        } catch (RuntimeException e) {
            // 只在关闭之后发生
            created.client().completeExceptionally(e);
        }
        return created.client().thenApply(Client::service);
    }

    /** 连接线程上：建引用（可能阻塞到建连超时）。 */
    private void build(Entry<S> entry) {
        ReferenceConfig<S> reference = null;
        try {
            reference = new ReferenceConfig<>(model().module());
            reference.setInterface(type);
            reference.setGroup(group);
            reference.setUrl("tri://" + entry.target().address());
            reference.setRetries(0);
            reference.setCheck(false);
            reference.setTimeout((int) referenceTimeout.toMillis());
            reference.setParameters(reconnectParameters());
            entry.client().complete(new Client<>(reference, reference.get()));
        } catch (RuntimeException e) {
            // 失败的条目留在表里，下一次调用看到它已异常完成就重建
            log.warn("{} 建客户端失败 address={}: {}", name, entry.target().address(), e.toString());
            if (reference != null) {
                destroyQuietly(reference);
            }
            entry.client().completeExceptionally(e);
        }
    }

    /** 进直连 URL 的重连 / 心跳参数（同 {@code SceneAssetOpClients}）。可变 Map：Dubbo 刷新配置时可能往里写。 */
    static Map<String, String> reconnectParameters() {
        String millis = Long.toString(RECONNECT_INTERVAL.toMillis());
        Map<String, String> parameters = new HashMap<>();
        parameters.put(Constants.HEARTBEAT_KEY, millis);
        parameters.put(Constants.LEAST_RECONNECT_DURATION_KEY, millis);
        return parameters;
    }

    private IsolatedDubboModule model() {
        IsolatedDubboModule model = dubbo;
        if (model == null) {
            synchronized (this) {
                if (shutDown) {
                    throw new IllegalStateException(name + " 客户端缓存已关闭");
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
    public void evict(Target target) {
        Entry<S> removed = null;
        synchronized (lock) {
            Entry<S> existing = entries.get(target.address());
            if (existing != null && existing.target().instanceId().equals(target.instanceId())) {
                entries.remove(target.address());
                removed = existing;
            }
        }
        destroyWhenBuilt(removed);
    }

    /** 只保留这些节点（按地址 + 实例），其余销毁（节点从目录消失 / 换了实例）。 */
    public void retainOnly(Collection<Target> live) {
        Set<String> keep = new HashSet<>();
        for (Target target : live) {
            keep.add(target.address() + "#" + target.instanceId());
        }
        List<Entry<S>> removed = new ArrayList<>();
        synchronized (lock) {
            entries.values().removeIf(entry -> {
                if (keep.contains(entry.target().address() + "#" + entry.target().instanceId())) {
                    return false;
                }
                removed.add(entry);
                return true;
            });
        }
        removed.forEach(NodeRpcClients::destroyWhenBuilt);
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

    private static <S> void destroyWhenBuilt(Entry<S> entry) {
        if (entry != null) {
            entry.client().whenComplete((client, error) -> {
                if (client != null) {
                    destroyQuietly(client.reference());
                }
            });
        }
    }

    private static void destroyQuietly(ReferenceConfig<?> reference) {
        try {
            reference.destroy();
        } catch (RuntimeException e) {
            log.warn("销毁直连客户端时出错（忽略）", e);
        }
    }
}
