package com.game.match.placement;

import com.game.api.BattleNodeService;
import com.game.match.proto.BattlePlacement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 按落点记录直拨 battle 节点，并把结局分类（match-spec §4.3 第 4–6 行；spectate-spec §4.8）。179 补签与 6.5 的观众 RPC 共用这一条规则，
 * 所以从补签里抽成接口：
 * <ol>
 *   <li>按<b>记录里的</b>地址与实例号（{@code rpc_host} / {@code rpc_port} / {@code battle_instance_id}）取直连客户端发起调用——不按目录找：
 *       battle 丢了租约仍活着时不在目录里，但直拨能到；节点重启后占了同一个地址时，调用落到新进程，由新进程回「房间不存在」。</li>
 *   <li>调通了 → {@link Dial.Replied}，应答原样交给调用方（battle 的裁决由调用方透传 / 解读）。</li>
 *   <li><b>建连失败</b>（对端拒绝连接 / 地址不可达，请求确定没有送达）→ 再读一次 battle 目录：同号节点存在且实例不同 → {@link Dial.RoomGone}
 *       （正面证据：原进程的号已被别的进程接手，且原地址连不上）；否则 → {@link Dial.Unavailable}({@link Kind#NOT_DELIVERED})。</li>
 *   <li>其余传输失败 → {@link Dial.Unavailable}：超时是 {@link Kind#TIMEOUT}；连上之后断开、对端回错、分不清的一律 {@link Kind#OTHER}。
 *       <b>超时永远不判死</b>——丢了租约的 battle 恰好是「可能很慢」的进程，而「这局没了」会让客户端永久放弃本局。分不清就归到不判死的那一边。</li>
 * </ol>
 *
 * <p><b>契约</b>：阻塞，至多约 {@code timeout}（外加建连失败后一次目录读）；在工作线程 / 虚拟线程上调。<b>永不抛异常</b>（{@code call} 抛出的异常按
 * {@link Kind#OTHER} 处理）。不重试。线程安全。
 */
public interface PlacementDialer {

    /** 没调通的类别。 */
    enum Kind {
        /** 建连失败（请求确定没有送达），但没有「号已被别的进程接手」的证据：目录里没有这个号、还是同一个实例、或目录读失败。 */
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

        /** 这一局确实没了：直拨建连失败，且目录里同号节点已换实例。179 据此回 1005；观众 RPC 视同「房间不存在」。 */
        record RoomGone<R>() implements Dial<R> {
        }

        /** 没调通，也不能证明房间没了：179 回 1003「战斗服务暂不可用」，客户端退避后再试。{@code detail} 只进日志。 */
        record Unavailable<R>(Kind kind, String detail) implements Dial<R> {
        }
    }

    /**
     * 直拨落点记录指向的 battle 节点并发起一次调用。
     *
     * @param placement 落点记录（地址与实例取自它）
     * @param timeout   这一次调用的上限（179 用 {@code MatchBudgets.ISSUE_TICKET_TIMEOUT_MS}；调用方可以按剩余预算收短）
     * @param call      在直连客户端上发起调用（例：{@code node -> node.issueBattleTicket(request)}）
     */
    <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call);
}
