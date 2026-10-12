package com.game.match.ticket;

import com.game.common.deadline.Deadline;

/**
 * 「这名玩家手里有没有挡路的票」——带自愈的判定（match-spec §2.2「自愈」；基线 {@code join.go:234-269}、{@code tb.go:74-90}）。
 * 排队入口 157（第 5–6 步）与成员预检（整队开战、活动开战的第 4 项）共用同一条规则，所以抽成接口：
 * <ol>
 *   <li>没有票 → {@link Free}；</li>
 *   <li>ready 票 → 按票号条件删（要求删时仍是 ready）；删掉了（或已经不在）→ {@link Free}；</li>
 *   <li>queued 票，但它的队列里找不到这个人（孤儿）→ 按票号条件删（要求删时仍是 queued）；删掉了 → {@link Free}；</li>
 *   <li>其余（在队列里的 queued 票、matched 票、状态不认识的票、条件删没删成的票）→ {@link InFlight}，带读到的票号；
 *       唯一的例外是 ready 票没删成（读与删之间票已被换成新的）：带<b>重新读到的</b>那张票的票号，那一刻又没有票了则是 {@link Free}。</li>
 * </ol>
 *
 * <p><b>前提</b>：调用方已经确认这名玩家<b>没有战斗锁</b>——第 2 条的 ready 票只有在「战斗已结束」时才是残留。所以顺序固定为「先查战斗锁、再调本接口」，
 * 不可调换。自愈是有副作用的读：即使调用方随后因为别的原因拒绝（例如 157 接着发现没有位置回 16020），残留票也已经被清掉——这是基线行为，必须保持。
 *
 * <p><b>契约</b>：阻塞，至多等到 {@code d}；在工作线程上调。读票或条件删出错 / 超时抛 {@link Deadline.DependencyException}
 * （157 回 16004、预检回 {@code TICKET_READ_FAILED}）。幂等：同一名玩家重复调用结果收敛。线程安全。
 */
public interface TicketHealing {

    /** 判定结果（二选一，调用方穷举）。 */
    sealed interface Verdict {
    }

    /** 没有票，或残留票已被清掉：可以继续。 */
    record Free() implements Verdict {
    }

    /** 有一张在途的票（{@code ticketId} 是玩家此刻那张票的票号）：157 回 16001 并带上它；预检回 {@code TICKET_IN_FLIGHT}。 */
    record InFlight(String ticketId) implements Verdict {
    }

    /** 按上面的规则判定（必要时自愈）。前提：调用方已确认 {@code playerId} 没有战斗锁。 */
    Verdict healOrBlock(long playerId, Deadline d);
}
