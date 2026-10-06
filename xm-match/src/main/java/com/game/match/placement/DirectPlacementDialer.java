package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.api.rpc.NodeRpcClients;
import com.game.match.gather.BattleNodes;
import com.game.match.port.NodeCalls;
import com.game.match.proto.BattlePlacement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PlacementDialer} 的生产实现（match-spec §4.3 第 4–6 行）：按落点记录里的地址与实例号直拨 battle，失败时用 {@link RpcFailures} 分类，
 * 只有「请求确定没有送达」（{@link RpcFailures.Kind#NOT_SENT}）才去读一次 battle 目录，且只有「同号节点已换实例」才判这一局没了。
 *
 * <p>判死需要<b>两条</b>正面证据同时成立：原地址连不上（请求没送达）+ 原进程的节点号已被别的进程接手。缺任何一条都只回「暂不可用」：
 * <ul>
 *   <li>超时永不判死——丢了租约的 battle 恰好是「可能很慢」的进程，房间可能还活着；</li>
 *   <li>目录里没有这个号（进程丢了租约但还活着、条目刚过期）、还是同一个实例（目录最多滞后 15 s）、目录读失败，都不能证明房间没了；</li>
 *   <li>记录里没有实例号（正常写者不会产生）时无从比对，同样不判死。</li>
 * </ul>
 *
 * <p>线程：阻塞等这一次调用（至多 {@code timeout} 加一点本地余量），只在 future 上等、不持锁，可以在工作线程与虚拟线程上调。无状态、线程安全。
 */
public final class DirectPlacementDialer implements PlacementDialer {

    private static final Logger log = LoggerFactory.getLogger(DirectPlacementDialer.class);

    /** 本地等待比这次调用的超时多给的余量：正常情况下出站口自己先以超时失败（它另有约 200 ms 的本地兜底），这里只防 future 永不完成。 */
    static final long LOCAL_WAIT_GRACE_MS = 250;

    private final NodeCalls<BattleNodeService> calls;
    private final BattleNodes nodes;

    /**
     * @param calls battle 节点控制面的直连出站口
     * @param nodes battle 目录（只在建连失败之后读）
     */
    public DirectPlacementDialer(NodeCalls<BattleNodeService> calls, BattleNodes nodes) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
    }

    @Override
    public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(call, "call");
        String battle = Long.toUnsignedString(placement.getBattleId());
        NodeRpcClients.Target target;
        try {
            target = new NodeRpcClients.Target(placement.getRpcHost(), placement.getRpcPort(), placement.getBattleInstanceId());
        } catch (IllegalArgumentException e) {
            log.error("落点记录里的 battle 地址不合法，无法直拨 battle_id={} node={} host='{}' port={}: {}", battle,
                    Integer.toUnsignedString(placement.getBattleNodeId()), placement.getRpcHost(),
                    Integer.toUnsignedString(placement.getRpcPort()), e.getMessage());
            return new Dial.Unavailable<>(Kind.OTHER, "落点记录里的地址不合法: " + e.getMessage());
        }
        long timeoutMs = timeout == null ? 0 : Math.max(0, timeout.toMillis());
        Throwable failure;
        try {
            CompletableFuture<R> future = calls.call(target, Duration.ofMillis(timeoutMs), call);
            if (future == null) {
                return new Dial.Unavailable<>(Kind.OTHER, "出站口没有返回 future");
            }
            R reply = future.get(timeoutMs + LOCAL_WAIT_GRACE_MS, TimeUnit.MILLISECONDS);
            if (reply == null) {
                return new Dial.Unavailable<>(Kind.OTHER, "battle 的应答为空");
            }
            return new Dial.Replied<>(reply);
        } catch (TimeoutException e) {
            return new Dial.Unavailable<>(Kind.TIMEOUT, "本地等待超时 " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Dial.Unavailable<>(Kind.OTHER, "等待应答时被中断");
        } catch (ExecutionException e) {
            failure = e.getCause() == null ? e : e.getCause();
        } catch (RuntimeException e) {
            failure = e;
        }
        return switch (RpcFailures.classify(failure)) {
            case TIMEOUT -> new Dial.Unavailable<>(Kind.TIMEOUT, String.valueOf(failure));
            case OTHER -> new Dial.Unavailable<>(Kind.OTHER, String.valueOf(failure));
            case NOT_SENT -> afterConnectFailure(placement, target, failure);
        };
    }

    /** 直拨建连失败：读一次目录，只有同号节点已换实例才判这一局没了。 */
    private <R> Dial<R> afterConnectFailure(BattlePlacement placement, NodeRpcClients.Target target, Throwable failure) {
        String battle = Long.toUnsignedString(placement.getBattleId());
        String node = Integer.toUnsignedString(placement.getBattleNodeId());
        if (placement.getBattleInstanceId().isEmpty()) {
            log.warn("直拨 battle 建连失败，但落点记录没有实例号，无从比对目录，不判死 battle_id={} node={} address={}", battle, node, target.address());
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "建连失败；落点记录没有实例号: " + failure);
        }
        BattleNodes.Lookup lookup = nodes.lookup(placement.getBattleNodeId(), placement.getBattleInstanceId());
        if (lookup == BattleNodes.Lookup.OTHER_INSTANCE) {
            log.info("直拨 battle 建连失败且同号节点已换实例，判这一局已不存在 battle_id={} node={} address={} recorded_instance={}: {}", battle, node,
                    target.address(), placement.getBattleInstanceId(), String.valueOf(failure));
            return new Dial.RoomGone<>();
        }
        return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "建连失败；目录=" + lookup + ": " + failure);
    }
}
