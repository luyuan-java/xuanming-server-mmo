package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.proto.AddObserverRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 按落点记录直拨 battle 的两个观众 RPC（{@code BattleNodeService.addObserver / removeObserver}；spectate-spec §4.8）。
 * 使用者：163（登记观众、换场时清退旧场、复查命中后自我清退）与开局钩子（开局前清退参战者）。生产实现 {@code DefaultObserverDialer}
 * 包着 6.4 的 {@code placement.PlacementDialer}（<b>带硬截止的重载</b>，用它自己的直连客户端缓存），不要自己去容器里取 battle 的直连出站口；
 * 测试替身 {@code testing.FakeObserverDialer}。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>{@link #add} / {@link #remove} <b>阻塞</b>，返回时刻不晚于 {@code hardStop}（加毫秒级的调度抖动）：发调用、本地等待、
 *       请求没送达之后的目录读与探测都夹在它之内（lead 裁决 3）。在 163 的虚拟线程、gather 的虚拟线程上调；不得在 Dubbo / Netty I/O 线程上调。</li>
 *   <li><b>永不抛异常</b>，<b>不重试</b>：结局收敛成 {@link Outcome} 的四种之一。线程安全。</li>
 *   <li>实现负责记 {@code xm_match_observer_rpc_total{method, result}}（同步的与异步发出的都记）；调用方不重复记。
 *       清退的起因与结局（{@code xm_match_spectate_evictions_total}）由<b>调用方</b>记。</li>
 *   <li>发出去的请求不会因为调用方不等了而取消：{@link Outcome.Unknown} 之后 battle 仍可能执行它（同基线，B-s5）。</li>
 * </ul>
 */
public interface ObserverDialer {

    /**
     * 一次观众 RPC 的结局（四选一，调用方穷举）。与 {@code PlacementDialer.Dial} 的对应：{@code Replied} → {@link Replied}；
     * {@code RoomGone} → {@link Dead}；{@code Unavailable(NOT_DELIVERED)} → {@link NotDelivered}；{@code Unavailable(TIMEOUT / OTHER)} → {@link Unknown}。
     *
     * <table>
     *   <caption>调用方怎么处置（spectate-spec §4.8）</caption>
     *   <tr><th>结局</th><th>163 的 AddObserver</th><th>清退（rewatch / enter_gather / concurrent_queue）</th></tr>
     *   <tr><td>{@link Replied}</td><td>按 tip：0 = 成功；{@code MatchTips.BATTLE_ROOM_NOT_FOUND}（1004）= 房间不存在；其余 = 当前无法观战</td><td>忽略 tip，删标记</td></tr>
     *   <tr><td>{@link Dead}</td><td>视同 1004（走建房窗口判定）</td><td>只删标记</td></tr>
     *   <tr><td>{@link NotDelivered}</td><td>删标记 → 16018「该战斗当前无法观战」</td><td>只记日志（标记照删）</td></tr>
     *   <tr><td>{@link Unknown}</td><td><b>保留标记</b>（W4：battle 可能已登记，留着它开局清退才摘得到）→ 16018「该战斗当前无法观战」</td><td>只记日志（标记照删）</td></tr>
     * </table>
     */
    sealed interface Outcome {

        /**
         * 调通了：battle 给出了应答。
         *
         * @param tipId {@code addObserver} 是应答里 {@code error_message.id}（0 = 登记成功，含幂等重登记）；{@code removeObserver} 的应答是 Empty，恒为 0
         */
        record Replied(int tipId) implements Outcome {
        }

        /** 这一场所在的进程确实没了：请求确定没送达、目录里同号节点已换实例、原地址明确连不上（三条都在硬截止之前拿齐）。 */
        record Dead() implements Outcome {
        }

        /**
         * 请求<b>确定没有送达</b>，但判不了死：目录里没有这个号 / 还是同一个实例 / 目录读失败 / 原地址连得上或探测没有结论，
         * 或者硬截止已到（来不及发、来不及读目录、来不及探测）。battle 一定没有执行这次请求。{@code detail} 只进日志。
         */
        record NotDelivered(String detail) implements Outcome {
        }

        /**
         * <b>结局不明</b>：超时（含等到硬截止还没有应答）、连上之后断开、对端回了传输层错误（鉴权失败、在途超限）。
         * battle 可能已经执行、也可能没有。{@code detail} 只进日志。
         */
        record Unknown(String detail) implements Outcome {
        }
    }

    /**
     * 登记观众（163）。请求原样发给 {@code placement} 记录的地址。
     *
     * @param placement 这一场的落点记录（地址与实例取自它；{@code request.battle_id} 应与它的 battle_id 相同）
     * @param request   {@code battle_id}、{@code observer_player_id}、{@code routing}（{@code BattleRoutings.gatePart(在线目录条目)}）、
     *                  {@code observer_name}（{@code SessionContext.account}）
     * @param timeout   这一次调用的上限（{@code min(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, 硬截止的剩余)}）
     * @param hardStop  整次直拨的硬截止（163：请求截止 − {@code MatchBudgets.WATCH_ADD_RESERVE_MS}）；已过则不发调用、回 {@link Outcome.NotDelivered}
     */
    Outcome add(BattlePlacement placement, AddObserverRequest request, Duration timeout, Deadline hardStop);

    /**
     * 清退观众，<b>同步</b>等结果。两处用它：163 换场（旧场的 Remove 必须先于新场的 Add 完成或超时，规格 §7.1 第 4 条）；
     * 开局前清退（每人一个 3 s 的截止，读落点与这一次调用都算在里面）。
     *
     * @param placement  观众所在那一场的落点记录
     * @param observerId 被清退的玩家
     * @param reason     写进 {@code RemoveObserverRequest.reason}（battle 只拿它记日志）：{@link SpectateRules#REASON_REWATCH} /
     *                   {@link SpectateRules#REASON_ENTER_GATHER} / {@link SpectateRules#REASON_CONCURRENT_QUEUE}
     * @param timeout    这一次调用的上限（{@code min(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, 硬截止的剩余)}）
     * @param hardStop   整次直拨的硬截止（163 换场：请求截止 − {@code MatchBudgets.WATCH_REWATCH_RESERVE_MS}；开局清退：这名成员的 3 s 截止）
     */
    Outcome remove(BattlePlacement placement, long observerId, String reason, Duration timeout, Deadline hardStop);

    /**
     * 清退观众，<b>发出即返回</b>：只给 163 复查命中后的自我清退用（那时剩余预算可能只有约 0.2 s，同步等会把 16014 变成 16004）。
     * 调用在实现自己的虚拟线程上跑，<b>不占 163 的在途许可</b>，超时固定为 {@code MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS}。
     * 不抛异常、不阻塞调用线程。
     *
     * @return 这一次清退的结局：<b>一定会完成、永不异常完成</b>（至多约 3 s）。在实现的线程上完成，挂在它上面的回调不得阻塞
     *         （只用来记指标 / 日志）；调用方可以直接丢掉它
     */
    CompletableFuture<Outcome> removeAsync(BattlePlacement placement, long observerId, String reason);
}
