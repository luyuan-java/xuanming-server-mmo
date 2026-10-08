package com.game.match.spectate;

import com.game.api.BattleNodeService;
import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ObserverMethod;
import com.game.match.metrics.MatchMetrics.ObserverResult;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementDialer.Dial;
import com.game.match.proto.BattlePlacement;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.Empty;
import com.game.proto.RemoveObserverRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ObserverDialer} 的生产实现（spectate-spec §4.8；lead 裁决 3）：包着 6.4 的 {@link PlacementDialer}——用它<b>带硬截止的重载</b>
 * 按落点记录里的地址直拨 battle，把直拨的三种结局翻成观众 RPC 的四种，并记 {@code xm_match_observer_rpc_total{method, result}}。
 *
 * <table>
 *   <caption>结局的对应（判据在 {@code DirectPlacementDialer}，这里只翻译）</caption>
 *   <tr><th>直拨</th><th>观众 RPC</th><th>含义</th></tr>
 *   <tr><td>{@code Replied(reply)}</td><td>{@link Outcome.Replied}(tip)</td><td>battle 应答了；{@code addObserver} 的 tip 取应答的 {@code error_message.id}
 *       （0 = 登记成功），{@code removeObserver} 的应答是 Empty、恒为 0</td></tr>
 *   <tr><td>{@code RoomGone}</td><td>{@link Outcome.Dead}</td><td>请求确定没送达 + 目录里同号节点已换实例 + 原地址明确连不上，三条都在硬截止之前拿齐</td></tr>
 *   <tr><td>{@code Unavailable(NOT_DELIVERED)}</td><td>{@link Outcome.NotDelivered}</td><td>请求确定没送达，但判不了死（含硬截止已到）</td></tr>
 *   <tr><td>{@code Unavailable(TIMEOUT / OTHER)}</td><td>{@link Outcome.Unknown}</td><td>超时、连上之后断开、对端回了传输层错误（鉴权失败、在途超限）：
 *       battle 可能已经执行</td></tr>
 * </table>
 * 「超时永不判死」「到点一律不判死」都是直拨器的性质；这里另补一条：调用方给的超时 ≤ 0（硬截止的剩余不足 1 ms）时<b>不发调用</b>、回
 * {@link Outcome.NotDelivered}——直拨器对 0 超时是「不发包、按超时收场」，照翻会变成「结局不明」，而请求其实确定没有发出
 * （163 会因此白白保留一个标记）。
 *
 * <p>不用 gather 的那份直连出站口：观众 RPC 经 {@code PlacementDialer} 发，自动走它自己的客户端缓存（{@code PlacementClients}；
 * 直连目标的实例段恒为空串，battle 原地重启之后不会来回销毁重建引用）。
 *
 * <p><b>线程</b>：{@link #add} / {@link #remove} 在调用线程上阻塞（163 的虚拟线程、gather 的虚拟线程），只在 future 上等，不持锁。
 * {@link #removeAsync} 另起一条虚拟线程（名字 {@code match-spectate-evict-<序号>}），<b>不占 163 的在途许可</b>：数量上界是「每次 163 至多一次」，
 * 每条至多活 {@link MatchBudgets#REMOVE_OBSERVER_TIMEOUT_MS}。永不抛异常、不重试。无状态、线程安全。
 */
public final class DefaultObserverDialer implements ObserverDialer {

    private static final Logger log = LoggerFactory.getLogger(DefaultObserverDialer.class);

    /** 异步清退的虚拟线程名前缀。 */
    static final String ASYNC_THREAD_PREFIX = "match-spectate-evict-";

    private final PlacementDialer dialer;
    private final MatchMetrics metrics;
    private final Executor asyncRemovals;

    /** 生产装配：异步清退每次一条虚拟线程。 */
    public DefaultObserverDialer(PlacementDialer dialer, MatchMetrics metrics) {
        this(dialer, metrics, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(ASYNC_THREAD_PREFIX, 0).factory()));
    }

    /**
     * @param asyncRemovals {@link #removeAsync} 的执行器（测试可传同步执行器或会拒收的执行器）；它上面的任务会阻塞至多约 3 s
     */
    DefaultObserverDialer(PlacementDialer dialer, MatchMetrics metrics, Executor asyncRemovals) {
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.asyncRemovals = Objects.requireNonNull(asyncRemovals, "asyncRemovals");
    }

    @Override
    public Outcome add(BattlePlacement placement, AddObserverRequest request, Duration timeout, Deadline hardStop) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(hardStop, "hardStop");
        Outcome outcome = dial(placement, timeout, hardStop, node -> node.addObserver(request),
                (AddObserverResponse reply) -> reply.getErrorMessage().getId());
        return recorded(ObserverMethod.ADD, placement, request.getObserverPlayerId(), outcome);
    }

    @Override
    public Outcome remove(BattlePlacement placement, long observerId, String reason, Duration timeout, Deadline hardStop) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(hardStop, "hardStop");
        RemoveObserverRequest request = RemoveObserverRequest.newBuilder().setBattleId(placement.getBattleId()).setObserverPlayerId(observerId)
                .setReason(reason == null ? "" : reason).build();
        Outcome outcome = dial(placement, timeout, hardStop, node -> node.removeObserver(request), (Empty reply) -> 0);
        return recorded(ObserverMethod.REMOVE, placement, observerId, outcome);
    }

    @Override
    public CompletableFuture<Outcome> removeAsync(BattlePlacement placement, long observerId, String reason) {
        Objects.requireNonNull(placement, "placement");
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        try {
            asyncRemovals.execute(() -> {
                try {
                    result.complete(remove(placement, observerId, reason, Duration.ofMillis(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS),
                            Deadline.after(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS)));
                } catch (RuntimeException e) { // remove 约定不抛；这里只防返回的 future 永不完成
                    log.error("[spectate] 异步清退观众时出错 battle_id={} player={}", Long.toUnsignedString(placement.getBattleId()),
                            Long.toUnsignedString(observerId), e);
                    result.complete(recorded(ObserverMethod.REMOVE, placement, observerId, new Outcome.Unknown("异步清退出错: " + e)));
                } catch (Error e) {
                    result.complete(new Outcome.Unknown("异步清退出错: " + e));
                    throw e;
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            // 起不了线程 / 执行器拒收：调用一定没有发出
            log.error("[spectate] 起不了异步清退的线程，这一次清退没有发出 battle_id={} player={} reason={}",
                    Long.toUnsignedString(placement.getBattleId()), Long.toUnsignedString(observerId), reason, e);
            result.complete(recorded(ObserverMethod.REMOVE, placement, observerId, new Outcome.NotDelivered("起不了异步清退的线程: " + e)));
        }
        return result;
    }

    /** 发一次直拨并把结局翻成四种之一。不抛异常。 */
    private <R> Outcome dial(BattlePlacement placement, Duration timeout, Deadline hardStop,
                             Function<BattleNodeService, CompletableFuture<R>> call, ToIntFunction<R> tipOf) {
        if (hardStop.expired()) {
            return new Outcome.NotDelivered("硬截止已到，没有发出调用");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            // 直拨器对 0 超时是「不发包、按超时收场」：请求确定没有发出，不能翻成「结局不明」
            return new Outcome.NotDelivered("这一跳的超时为 0，没有发出调用");
        }
        Dial<R> dial;
        try {
            dial = dialer.dial(placement, timeout, hardStop, call);
        } catch (RuntimeException e) {
            // 直拨器约定不抛；到这里说明约定被破坏，不知道请求发没发出去：按结局不明
            log.error("[spectate] 直拨器抛出了异常（按结局不明处理） battle_id={}", Long.toUnsignedString(placement.getBattleId()), e);
            return new Outcome.Unknown("直拨器抛出了异常: " + e);
        }
        return switch (dial) {
            case Dial.Replied<R> replied -> new Outcome.Replied(tipOf.applyAsInt(replied.reply()));
            case Dial.RoomGone<R> gone -> new Outcome.Dead();
            case Dial.Unavailable<R> unavailable -> unavailable.kind() == PlacementDialer.Kind.NOT_DELIVERED
                    ? new Outcome.NotDelivered(unavailable.detail())
                    : new Outcome.Unknown(unavailable.kind() + ": " + unavailable.detail());
            case null -> new Outcome.Unknown("直拨器返回了空结果");
        };
    }

    /** 记 {@code xm_match_observer_rpc_total} 并留一条调试日志；原样返回结局。 */
    private Outcome recorded(ObserverMethod method, BattlePlacement placement, long observerId, Outcome outcome) {
        ObserverResult result = switch (outcome) {
            case Outcome.Replied replied -> ObserverResult.REPLIED;
            case Outcome.Dead dead -> ObserverResult.DEAD;
            case Outcome.NotDelivered notDelivered -> ObserverResult.NOT_DELIVERED;
            case Outcome.Unknown unknown -> ObserverResult.UNKNOWN;
        };
        try {
            metrics.observerRpc(method, result);
        } catch (RuntimeException e) {
            log.warn("[spectate] 记观众 RPC 指标出错（忽略）", e);
        }
        if (log.isDebugEnabled()) {
            log.debug("[spectate] 观众 RPC {} battle_id={} node={}({}:{}#{}) player={} → {}", method, Long.toUnsignedString(placement.getBattleId()),
                    Integer.toUnsignedString(placement.getBattleNodeId()), placement.getRpcHost(), Integer.toUnsignedString(placement.getRpcPort()),
                    placement.getBattleInstanceId(), Long.toUnsignedString(observerId), outcome);
        }
        return outcome;
    }
}
