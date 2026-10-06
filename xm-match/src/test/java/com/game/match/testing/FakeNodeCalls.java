package com.game.match.testing;

import com.game.api.rpc.NodeRpcClients;
import com.game.match.port.NodeCalls;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link NodeCalls} 的测试替身：按目标地址把调用路由到登记好的假服务（{@link FakeSceneBattle} / {@link FakeBattleNode}）上，不经过 Dubbo。
 * 能模拟传输层的三种失败——连不上、超时、对端回错——并记下每次调用发往哪里、给了多长的超时。
 *
 * <pre>
 * FakeNodeCalls&lt;BattleNodeService&gt; calls = new FakeNodeCalls&lt;&gt;();
 * calls.register(target("127.0.0.1", 21200, "inst-a"), battleA);     // 这个地址上是 battleA
 * calls.unreachable(targetB);                                         // 连不上（缺省抛 ConnectException；可以给任意异常）
 * calls.timeout(targetC);                                             // 立即以 TimeoutException 完成（不真的等）
 * calls.failWith(targetD, () -> new IllegalStateException("鉴权失败")); // 对端回错
 * assertThat(calls.calls).extracting(c -> c.target().address()).containsExactly("127.0.0.1:21200");
 * assertThat(calls.calls.get(0).timeout()).isEqualTo(Duration.ofSeconds(5));
 * </pre>
 * 路由规则与真的直连一致：按 {@code host:port} 找服务（实例号由假服务自己核对——scene 回 NOT_HERE；battle 的调用落到「占了同一地址的新进程」上）。
 * 没登记的地址按「连不上」。假服务返回的 future 原样交回（它自己可以挂起、异常完成）；{@code timeout ≤ 0} 时不调用、直接超时（同真实现）。线程安全。
 *
 * @param <S> 服务接口
 */
public final class FakeNodeCalls<S> implements NodeCalls<S> {

    /** 一次调用的去向与超时。 */
    public record Call(NodeRpcClients.Target target, Duration timeout) {
    }

    /** 每次调用，按发起顺序。 */
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, S> services = new ConcurrentHashMap<>();
    private final Map<String, Supplier<? extends Throwable>> failures = new ConcurrentHashMap<>();

    /** 把这个地址指到一个假服务上（覆盖之前的登记与故障）。 */
    public FakeNodeCalls<S> register(NodeRpcClients.Target target, S service) {
        services.put(target.address(), service);
        failures.remove(target.address());
        return this;
    }

    /** 这个地址连不上（请求确定没有送达）。 */
    public FakeNodeCalls<S> unreachable(NodeRpcClients.Target target) {
        return failWith(target, () -> new ConnectException("注入的故障: 连接被拒绝 " + target.address()));
    }

    /** 发往这个地址的调用立即以超时失败（请求可能已送达：假服务<b>不会</b>被调用；要「生效了但超时」请在假服务上脚本化）。 */
    public FakeNodeCalls<S> timeout(NodeRpcClients.Target target) {
        return failWith(target, () -> new TimeoutException("注入的故障: 调用超时 " + target.address()));
    }

    /** 发往这个地址的调用以给定的异常失败（每次调用取一个新的异常）。 */
    public FakeNodeCalls<S> failWith(NodeRpcClients.Target target, Supplier<? extends Throwable> error) {
        failures.put(target.address(), error);
        return this;
    }

    /** 清掉这个地址的故障（登记的服务还在）。 */
    public FakeNodeCalls<S> heal(NodeRpcClients.Target target) {
        failures.remove(target.address());
        return this;
    }

    @Override
    public <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout, Function<S, CompletableFuture<R>> invocation) {
        calls.add(new Call(target, timeout));
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return CompletableFuture.failedFuture(new TimeoutException("这次调用的剩余预算已用完"));
        }
        Supplier<? extends Throwable> failure = failures.get(target.address());
        if (failure != null) {
            return CompletableFuture.failedFuture(failure.get());
        }
        S service = services.get(target.address());
        if (service == null) {
            return CompletableFuture.failedFuture(new ConnectException("没有登记的地址（按连不上处理）: " + target.address()));
        }
        try {
            CompletableFuture<R> future = invocation.apply(service);
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException("假服务返回了空的 future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
