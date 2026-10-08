package com.game.match.port;

import com.game.api.rpc.NodeRpcClients;
import com.game.api.rpc.NodeRpcClients.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * match 这一侧的直连客户端缓存：包一个 xm-api 的 {@link NodeRpcClients}（按地址缓存 Dubbo 引用），补上它明说交给调用方做的两件事。
 *
 * <ol>
 *   <li><b>清掉不再用的引用</b>（{@link #sweepIdle}）。节点下线或换了地址（容器重启换 IP）之后，它的引用留着只会按 1 s 间隔对一个没人监听的
 *       地址反复重连，进程长期运行时引用与重连任务只增不减。这里不读目录、按<b>空闲</b>清：每个地址记最近一次发调用的时刻，
 *       空闲超过阈值（远大于任何一跳的超时，所以没有在途调用）就销毁；误清的代价只是下次多建一次引用。补签按落点记录直拨出来的、
 *       已经不在目录里的地址也一样清得到。</li>
 *   <li><b>记下来的目标不顶掉更新的引用</b>（{@link Calls#callRemembered}）。底层缓存的键只有地址：同一地址上实例号不同就销毁旧引用再建，
 *       被销毁的引用上在途的调用一律按传输失败收场。目标刚从目录读出来时（备战、建房）这是对的——节点原地重启后要换到新进程；
 *       但补偿的取消、回滚的销毁带的是<b>先前记下的</b>实例号，节点原地重启之后它会把别的 gather 正在用的新引用顶掉，两边来回重建。
 *       这类调用改用「这个地址上已有引用就用它」：请求照样到达现在占着这个地址的进程（scene 自己核对实例号回 NOT_HERE；
 *       battle 的请求不带实例号，实例号对它只是缓存键）。</li>
 * </ol>
 *
 * <p>线程：{@link Calls} 的两个方法不阻塞（契约同 {@link NodeCalls}），可在任意线程（含虚拟线程）上调；内部一把 {@link ReentrantLock}，
 * 临界区里只有查表与发起调用（都不等 I/O），销毁引用（清扫、换实例）时短暂占着。线程安全。
 *
 * @param <S> 服务接口
 */
public final class NodeClientCache<S> implements IdleSweep, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeClientCache.class);

    /** 底层缓存用到的三个口（生产是 {@link NodeRpcClients}；单测用记录型替身）。 */
    interface Clients<S> {

        <R> CompletableFuture<R> call(Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation);

        void evict(Target target);

        void close();
    }

    /** 一个地址此刻挂在底层缓存里的目标（实例号以它为准）与最近一次发调用的时刻。 */
    private static final class Entry {
        Target target;
        long lastUsedNanos;
    }

    private final String name;
    private final Clients<S> clients;
    private final long idleNanos;
    private final LongSupplier nanoClock;
    private final ReentrantLock lock = new ReentrantLock();
    /** 地址 → 条目（持锁读写）。与底层缓存的条目一一对应：每次发调用都先在这里登记。 */
    private final Map<String, Entry> entries = new HashMap<>();
    private final Calls calls = new Calls();

    /**
     * 生产装配。
     *
     * @param name        缓存的名字（日志用）
     * @param clients     底层缓存；本对象接管它的关闭
     * @param idleTimeout 空闲多久算不再用：必须远大于经它发出的任何一跳的超时
     */
    public NodeClientCache(String name, NodeRpcClients<S> clients, Duration idleTimeout) {
        this(name, adapt(clients), idleTimeout, System::nanoTime);
    }

    NodeClientCache(String name, Clients<S> clients, Duration idleTimeout, LongSupplier nanoClock) {
        this.name = Objects.requireNonNull(name, "name");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.idleNanos = idleTimeout.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (idleNanos < 1) {
            throw new IllegalArgumentException("空闲阈值必须为正: " + idleTimeout);
        }
    }

    private static <S> Clients<S> adapt(NodeRpcClients<S> clients) {
        Objects.requireNonNull(clients, "clients");
        return new Clients<>() {
            @Override
            public <R> CompletableFuture<R> call(Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
                return clients.call(target, timeout, invocation);
            }

            @Override
            public void evict(Target target) {
                clients.evict(target);
            }

            @Override
            public void close() {
                clients.close();
            }
        };
    }

    /** 经本缓存发调用的出站口（{@link NodeCalls} 的实现）。 */
    public NodeCalls<S> calls() {
        return calls;
    }

    @Override
    public String name() {
        return name;
    }

    /** 当前登记着的地址数（指标 / 测试用）。 */
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清掉空闲超过阈值的地址（销毁它的引用）。不抛异常。
     *
     * <p>「登记 + 发起调用」与这里的「判空闲 + 销毁」在同一把锁里，所以不会销毁一个刚被取用的引用；而空闲超过阈值意味着早已没有在途调用。
     */
    @Override
    public int sweepIdle() {
        long now = nanoClock.getAsLong();
        List<Target> victims = new ArrayList<>();
        lock.lock();
        try {
            for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
                Entry entry = it.next();
                if (now - entry.lastUsedNanos >= idleNanos) {
                    it.remove();
                    victims.add(entry.target);
                }
            }
            for (Target victim : victims) {
                try {
                    clients.evict(victim);
                } catch (RuntimeException e) {
                    log.warn("{} 清掉空闲的直连客户端时出错（忽略） address={}", name, victim.address(), e);
                }
            }
        } finally {
            lock.unlock();
        }
        if (!victims.isEmpty()) {
            log.info("{} 清掉了 {} 个空闲的直连客户端: {}", name, victims.size(), victims.stream().map(Target::address).toList());
        }
        return victims.size();
    }

    /** 关闭底层缓存（销毁全部引用与它的 Dubbo 模型）。幂等。 */
    @Override
    public void close() {
        lock.lock();
        try {
            entries.clear();
        } finally {
            lock.unlock();
        }
        clients.close();
    }

    private final class Calls implements NodeCalls<S> {

        @Override
        public <R> CompletableFuture<R> call(Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
            return dispatch(target, false, timeout, invocation);
        }

        @Override
        public <R> CompletableFuture<R> callRemembered(Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
            return dispatch(target, true, timeout, invocation);
        }

        private <R> CompletableFuture<R> dispatch(Target target, boolean remembered, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
            Objects.requireNonNull(target, "target");
            lock.lock();
            try {
                Entry entry = entries.computeIfAbsent(target.address(), address -> new Entry());
                if (entry.target == null || !remembered) {
                    // 刚从目录读出来的目标：实例号以它为准（与已有的不同时底层会销毁旧引用再建）
                    entry.target = target;
                }
                entry.lastUsedNanos = nanoClock.getAsLong();
                // 发起调用也在锁里（它不等 I/O）：否则「取到已有的目标」与「别的线程把它换成新实例」可以交错，照样来回重建
                return clients.call(entry.target, timeout, invocation);
            } finally {
                lock.unlock();
            }
        }
    }
}
