package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 按落点记录直拨 battle 节点，并把结局分类（match-spec §4.3 第 4–6 行；spectate-spec §4.8）。179 补签与 6.5 的观众 RPC 共用这一条规则，
 * 所以从补签里抽成接口：
 * <ol>
 *   <li>按<b>记录里的</b>地址（{@code rpc_host} / {@code rpc_port}）取直连客户端发起调用——不按目录找：
 *       battle 丢了租约仍活着时不在目录里，但直拨能到；节点重启后占了同一个地址时，调用落到新进程，由新进程回「房间不存在」。
 *       记录里的实例号（{@code battle_instance_id}）只用于下面与目录比对，不参与取客户端（battle 的请求不带实例号）。</li>
 *   <li>调通了 → {@link Dial.Replied}，应答原样交给调用方（battle 的裁决由调用方透传 / 解读）。</li>
 *   <li><b>请求确定没有送达</b> → 再读一次 battle 目录：同号节点存在且实例不同，<b>并且</b>对原地址再探测一次、明确连不上（对端拒绝连接 /
 *       地址不可达）→ {@link Dial.RoomGone}（正面证据：原进程的号已被别的进程接手，且原地址连不上）；
 *       否则 → {@link Dial.Unavailable}({@link Kind#NOT_DELIVERED})。「没送达」本身不等于「连不上」——连接刚断、还没重连上时也是没送达，
 *       所以要多探测这一次。</li>
 *   <li>其余传输失败 → {@link Dial.Unavailable}：超时是 {@link Kind#TIMEOUT}；连上之后断开、对端回错、分不清的一律 {@link Kind#OTHER}。
 *       <b>超时永远不判死</b>——丢了租约的 battle 恰好是「可能很慢」的进程，而「这局没了」会让客户端永久放弃本局。分不清就归到不判死的那一边。</li>
 * </ol>
 *
 * <p><b>契约</b>：阻塞，至多约 {@code timeout}（外加请求没送达之后的一次目录读；探测算在 {@code timeout} 之内）；在工作线程 / 虚拟线程上调。<b>永不抛异常</b>（{@code call} 抛出的异常按
 * {@link Kind#OTHER} 处理）。不重试。线程安全。
 *
 * <p><b>两个重载的分工</b>：不带硬截止的 {@link #dial(BattlePlacement, Duration, Function)} 给 179 补签用——它的最坏耗时是
 * 「{@code timeout} + 约 250 ms 的本地余量 + 一次至多 1 s 的目录读」，对 179 可以接受。带硬截止的
 * {@link #dial(BattlePlacement, Duration, Deadline, Function)} 给 6.5 的观众 RPC 用（163 的每一跳、开局清退的每人 3 s）：
 * 那两处的时限出现在不等式里（163 必须先于 gate 的 5 s 给出 in-band 应答；清退每人不得超过 matched TTL 公式里的 3 s），
 * 不能再被本地余量与目录读撑过去。
 */
public interface PlacementDialer {

    /** 没调通的类别。 */
    enum Kind {
        /**
         * 请求确定没有送达，但判不了「这局没了」：目录里没有这个号、还是同一个实例、目录读失败，或者号虽已被别的实例接手、
         * 原地址却连得上 / 探测没有结论。
         */
        NOT_DELIVERED,
        /** 调用超时：请求可能已经送达并生效。 */
        TIMEOUT,
        /** 其它：连上之后断开、对端回了传输层错误（鉴权失败、过载）、记录里的地址不合法、以及分不清类别的失败。 */
        OTHER
    }

    /** 一次直拨的结局（三选一，调用方穷举）。 */
    sealed interface Dial<R> {

        /** 调通了：{@code reply} 是 battle 的应答（非 null）。 */
        record Replied<R>(R reply) implements Dial<R> {
        }

        /** 这一局确实没了：直拨的请求没有送达、目录里同号节点已换实例、原地址明确连不上。179 据此回 1005；观众 RPC 视同「房间不存在」。 */
        record RoomGone<R>() implements Dial<R> {
        }

        /** 没调通，也不能证明房间没了：179 回 1003「战斗服务暂不可用」，客户端退避后再试。{@code detail} 只进日志。 */
        record Unavailable<R>(Kind kind, String detail) implements Dial<R> {
        }
    }

    /**
     * 直拨落点记录指向的 battle 节点并发起一次调用（<b>不带硬截止</b>：179 补签用，行为自 6.4 起不变）。
     *
     * @param placement 落点记录（地址与实例取自它）
     * @param timeout   这一次调用的上限（179 用 {@code MatchBudgets.ISSUE_TICKET_TIMEOUT_MS}；调用方可以按剩余预算收短）
     * @param call      在直连客户端上发起调用（例：{@code node -> node.issueBattleTicket(request)}）
     */
    <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call);

    /**
     * 同上，但<b>整次直拨不越过 {@code hardStop}</b>（lead 裁决 3；spectate-spec §4.8）——发调用、本地等待、请求没送达之后的目录读、
     * 判死前的探测，每一步都夹在它之内。判定规则与不带截止的重载完全相同，只多这几条：
     * <ul>
     *   <li>进来时 {@code hardStop} 已过：<b>不发调用</b>，直接 {@link Dial.Unavailable}({@link Kind#NOT_DELIVERED})（请求确定没有发出）。</li>
     *   <li>交给出站口的超时是 {@code min(timeout, hardStop 的剩余)}；本地至多等到 {@code hardStop}（不再另加本地余量越过它）。
     *       等到 {@code hardStop} 还没有结果 → {@link Dial.Unavailable}({@link Kind#TIMEOUT})：请求可能已经送达并生效。</li>
     *   <li>请求确定没有送达、但 {@code hardStop} 已到（来不及读目录），或目录读 / 探测被 {@code hardStop} 截断而没有结论
     *       → {@link Dial.Unavailable}({@link Kind#NOT_DELIVERED})。</li>
     * </ul>
     * 即<b>到点一律按「没调通、不判死」返回</b>：{@link Dial.RoomGone} 只在三条证据都在 {@code hardStop} 之前拿齐时才给出，
     * 时间不够永远不会被当成「这局没了」。返回时刻不晚于 {@code hardStop} 加上调度抖动（毫秒级）。
     *
     * @param placement 落点记录（地址与实例取自它）
     * @param timeout   这一次调用的上限（{@code MatchBudgets.ADD_OBSERVER_TIMEOUT_MS} / {@code REMOVE_OBSERVER_TIMEOUT_MS}）；≤ 0 时不发包、按超时收场（同不带截止的重载）
     * @param hardStop  整次直拨的硬截止（非 null）：163 传「请求截止 − 预留」，开局清退传每人的 3 s 截止
     * @param call      在直连客户端上发起调用（例：{@code node -> node.addObserver(request)}）
     */
    <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Deadline hardStop, Function<BattleNodeService, CompletableFuture<R>> call);
}
