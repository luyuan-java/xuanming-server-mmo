package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
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
 * {@link PlacementDialer} 的生产实现（match-spec §4.3 第 4–6 行）：按落点记录里的<b>地址</b>直拨 battle，失败时用 {@link RpcFailures} 分类，
 * 只有「请求确定没有送达」（{@link RpcFailures.Kind#NOT_SENT}）才往下判这一局是不是没了。
 *
 * <p>判死需要<b>三条</b>证据同时成立，缺任何一条都只回「暂不可用」：
 * <ol>
 *   <li>直拨的请求确定没有送达；</li>
 *   <li>目录里原进程的节点号已被<b>别的实例</b>接手（{@link BattleNodes.Lookup#OTHER_INSTANCE}，按记录里的实例号比）；</li>
 *   <li>对原地址再探测一次，<b>明确连不上</b>（{@link ConnectProbe.Result#REFUSED}）。第 1 条的「没送达」比「连不上」宽：连接刚断、
 *       1 s 级的重连还没连上时也是没送达，而原进程此刻可能活着（网络抖动，或进程长停顿被心跳判断线——恰好是「丢了租约、可能很慢」的那一类）。
 *       探测连得上或没有结论（超时、预算不够）都不判死：客户端重试时连接多半已恢复，由占着那个地址的进程自己回答。</li>
 * </ol>
 * 其余不判死的情形：
 * <ul>
 *   <li>超时永不判死——丢了租约的 battle 恰好是「可能很慢」的进程，房间可能还活着；</li>
 *   <li>目录里没有这个号（进程丢了租约但还活着、条目刚过期）、还是同一个实例（目录最多滞后 15 s）、目录读失败，都不能证明房间没了；</li>
 *   <li>记录里没有实例号（正常写者不会产生）时无从比对，同样不判死。</li>
 * </ul>
 *
 * <p><b>直连目标的实例段固定为空串</b>：battle 的请求不带实例号，实例号对直拨只是客户端缓存的键。记录里的实例是建房那一刻的，
 * 节点原地重启之后拿它当键会不停地销毁重建引用（见 {@link PlacementClients}）；固定成空串后同一地址永远是同一个引用，
 * 调用落到现在占着这个地址的进程，由它回「房间不存在」。判死时与目录比对的仍是记录里的实例号。
 *
 * <p>线程：阻塞等这一次调用（至多 {@code timeout} 加一点本地余量；建连失败之后另有一次目录读与一次不超出 {@code timeout} 余下部分的探测），
 * 只在 future 与套接字上等、不持锁，可以在工作线程与虚拟线程上调。无状态、线程安全。
 *
 * <p><b>带硬截止的重载</b>（6.5 的观众 RPC；lead 裁决 3）走同一套判定，只是每一步都夹在硬截止之内：进来时已过点不发调用；
 * 交给出站口的超时与本地等待都不超过它的剩余（不再另加本地余量）；到点之后不读目录、不探测。到点的结局一律是「暂不可用」——
 * 时间不够永远不会被当成「这一局没了」。不带硬截止的重载（179）行为不变：本地余量 {@value #LOCAL_WAIT_GRACE_MS} ms、目录读固定等 1 s。
 */
public final class DirectPlacementDialer implements PlacementDialer {

    private static final Logger log = LoggerFactory.getLogger(DirectPlacementDialer.class);

    /** 本地等待比这次调用的超时多给的余量：正常情况下出站口自己先以超时失败（它另有约 200 ms 的本地兜底），这里只防 future 永不完成。 */
    static final long LOCAL_WAIT_GRACE_MS = 250;
    /** 判死前探测原地址的等待上限（再按这次直拨余下的预算收短）。被拒绝通常是立即的，这个上限只是丢包时白等的时间。 */
    static final long PROBE_TIMEOUT_MS = 300;

    private final NodeCalls<BattleNodeService> calls;
    private final BattleNodes nodes;
    private final ConnectProbe probe;
    private final long probeTimeoutMs;

    /**
     * 生产装配。
     *
     * @param calls battle 节点控制面的直连出站口（直拨专用的那一份，见 {@link PlacementClients}）
     * @param nodes battle 目录（只在建连失败之后读）
     * @param probe 原地址连不连得上的探测（只在「没送达 + 同号换实例」之后做）
     */
    public DirectPlacementDialer(NodeCalls<BattleNodeService> calls, BattleNodes nodes, ConnectProbe probe) {
        this(calls, nodes, probe, PROBE_TIMEOUT_MS);
    }

    /** 测试用：可以把探测的上限放长（有的环境对没人监听的端口建连要等内核重试几次才报拒绝）。 */
    DirectPlacementDialer(NodeCalls<BattleNodeService> calls, BattleNodes nodes, ConnectProbe probe, long probeTimeoutMs) {
        this.calls = Objects.requireNonNull(calls, "calls");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.probeTimeoutMs = probeTimeoutMs;
    }

    @Override
    public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call) {
        return dial0(placement, timeout, null, call);
    }

    @Override
    public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Deadline hardStop, Function<BattleNodeService, CompletableFuture<R>> call) {
        return dial0(placement, timeout, Objects.requireNonNull(hardStop, "hardStop"), call);
    }

    /**
     * 两个重载共用的实现。
     *
     * @param hardStop 整次直拨的硬截止；null = 没有（179 的旧行为：本地另等 {@value #LOCAL_WAIT_GRACE_MS} ms 余量、目录读固定等 1 s）
     */
    private <R> Dial<R> dial0(BattlePlacement placement, Duration timeout, Deadline hardStop,
                              Function<BattleNodeService, CompletableFuture<R>> call) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(call, "call");
        String battle = Long.toUnsignedString(placement.getBattleId());
        NodeRpcClients.Target target;
        try {
            // 实例段固定为空串：只按地址缓存客户端（见类注释）
            target = new NodeRpcClients.Target(placement.getRpcHost(), placement.getRpcPort(), "");
        } catch (IllegalArgumentException e) {
            log.error("落点记录里的 battle 地址不合法，无法直拨 battle_id={} node={} host='{}' port={}: {}", battle,
                    Integer.toUnsignedString(placement.getBattleNodeId()), placement.getRpcHost(),
                    Integer.toUnsignedString(placement.getRpcPort()), e.getMessage());
            return new Dial.Unavailable<>(Kind.OTHER, "落点记录里的地址不合法: " + e.getMessage());
        }
        long timeoutMs = timeout == null ? 0 : Math.max(0, timeout.toMillis());
        long localWaitNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs + LOCAL_WAIT_GRACE_MS);
        if (hardStop != null) {
            long remainingNanos = hardStop.remainingNanos();
            if (remainingNanos <= 0) {
                // 没有时间了：不发调用。请求确定没有发出，所以是「没送达」而不是「超时」
                return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "硬截止已到，没有发出调用");
            }
            if (timeoutMs > 0) {
                // 交给出站口的超时不超过硬截止的剩余（不足 1 ms 按 1 ms：0 在出站口的含义是「不发包」）
                timeoutMs = Math.min(timeoutMs, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            }
            // 本地等待不再另加余量越过硬截止
            localWaitNanos = Math.min(localWaitNanos, remainingNanos);
        }
        long startedNanos = System.nanoTime();
        Throwable failure;
        try {
            CompletableFuture<R> future = calls.call(target, Duration.ofMillis(timeoutMs), call);
            if (future == null) {
                return new Dial.Unavailable<>(Kind.OTHER, "出站口没有返回 future");
            }
            R reply = future.get(Math.max(1, localWaitNanos), TimeUnit.NANOSECONDS);
            if (reply == null) {
                return new Dial.Unavailable<>(Kind.OTHER, "battle 的应答为空");
            }
            return new Dial.Replied<>(reply);
        } catch (TimeoutException e) {
            return new Dial.Unavailable<>(Kind.TIMEOUT, "本地等待超时 " + timeoutMs + " ms" + (hardStop == null ? "" : "（受硬截止约束）"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Dial.Unavailable<>(Kind.OTHER, "等待应答时被中断");
        } catch (ExecutionException e) {
            failure = e.getCause() == null ? e : e.getCause();
        } catch (RuntimeException e) {
            failure = e;
        }
        return switch (RpcFailures.classify(failure, target.address())) {
            case TIMEOUT -> new Dial.Unavailable<>(Kind.TIMEOUT, String.valueOf(failure));
            case OTHER -> new Dial.Unavailable<>(Kind.OTHER, String.valueOf(failure));
            case NOT_SENT -> afterConnectFailure(placement, target, failure,
                    timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos), hardStop);
        };
    }

    /**
     * 直拨的请求没有送达：读一次目录，同号节点已换实例时再探测一次原地址，明确连不上才判这一局没了。
     *
     * @param remainingMs 这次直拨的预算还剩多少（探测不得超出它）
     * @param hardStop    整次直拨的硬截止（null = 没有）：到点就不再读目录、不再探测，按「没送达、不判死」收场
     */
    private <R> Dial<R> afterConnectFailure(BattlePlacement placement, NodeRpcClients.Target target, Throwable failure, long remainingMs,
                                            Deadline hardStop) {
        String battle = Long.toUnsignedString(placement.getBattleId());
        String node = Integer.toUnsignedString(placement.getBattleNodeId());
        if (placement.getBattleInstanceId().isEmpty()) {
            log.warn("直拨 battle 建连失败，但落点记录没有实例号，无从比对目录，不判死 battle_id={} node={} address={}", battle, node, target.address());
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "建连失败；落点记录没有实例号: " + failure);
        }
        BattleNodes.Lookup lookup;
        if (hardStop == null) {
            lookup = nodes.lookup(placement.getBattleNodeId(), placement.getBattleInstanceId());
        } else if (hardStop.expired()) {
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "建连失败；硬截止已到，没有读目录: " + failure);
        } else {
            lookup = nodes.lookup(placement.getBattleNodeId(), placement.getBattleInstanceId(), hardStop);
        }
        if (lookup != BattleNodes.Lookup.OTHER_INSTANCE) {
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "建连失败；目录=" + lookup + ": " + failure);
        }
        long probeBudgetMs = Math.min(probeTimeoutMs, remainingMs);
        if (hardStop != null) {
            probeBudgetMs = Math.min(probeBudgetMs, hardStop.remainingMillis());
            if (probeBudgetMs <= 0) {
                // 第三条证据来不及取：时间不够永远不当成「这一局没了」
                log.info("直拨 battle 的请求没有送达、同号节点已换实例，但硬截止已到、来不及探测原地址，不判死 battle_id={} node={} address={} "
                        + "recorded_instance={}", battle, node, target.address(), placement.getBattleInstanceId());
                return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "请求没有送达；同号节点已换实例，但硬截止已到、没有探测: " + failure);
            }
        }
        ConnectProbe.Result probed;
        try {
            probed = probe.probe(target.host(), target.port(), probeBudgetMs);
        } catch (RuntimeException e) {
            log.warn("探测 battle 原地址时出错（按没有结论处理） battle_id={} address={}: {}", battle, target.address(), e.toString());
            probed = ConnectProbe.Result.INCONCLUSIVE;
        }
        if (probed != ConnectProbe.Result.REFUSED) {
            log.info("直拨 battle 的请求没有送达、同号节点已换实例，但原地址{}，不判死（客户端重试） battle_id={} node={} address={} "
                    + "recorded_instance={}", probed == ConnectProbe.Result.CONNECTED ? "连得上" : "的探测没有结论", battle, node, target.address(),
                    placement.getBattleInstanceId());
            return new Dial.Unavailable<>(Kind.NOT_DELIVERED, "请求没有送达；同号节点已换实例，但原地址的探测=" + probed + ": " + failure);
        }
        log.info("直拨 battle 建连失败、同号节点已换实例、原地址明确连不上，判这一局已不存在 battle_id={} node={} address={} recorded_instance={}: {}",
                battle, node, target.address(), placement.getBattleInstanceId(), String.valueOf(failure));
        return new Dial.RoomGone<>();
    }
}
