package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.TicketStore.HealMode;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TicketHealing} 的实现：读一次票，按状态决定要不要条件删（基线 {@code healOrphanTicket}，{@code join.go:234-269}）。
 *
 * <table>
 *   <caption>判定</caption>
 *   <tr><td>没有票</td><td>{@link Free}</td></tr>
 *   <tr><td>ready</td><td>{@code heal(READY)}：票号一致且仍是 ready 才删。删掉了（或已不在）→ {@link Free}；没删成（票已换）→ {@link InFlight}</td></tr>
 *   <tr><td>queued</td><td>{@code heal(ORPHAN)}：票号一致、仍是 queued、它的队列里找不到这个人才删。删掉了 → {@link Free}（记 ERROR：
 *       Java 的入队 / 回队首都是原子的，孤儿只可能来自人为改数据或 bug）；在队列里、或探测与删票之间被弹走 → {@link InFlight}</td></tr>
 *   <tr><td>matched / 状态不认识</td><td>{@link InFlight}，不碰它（gather 在途，靠 matched TTL 自愈）</td></tr>
 * </table>
 * 「在不在队列里」的探测与删票在同一段脚本里（基线是 LPOS 与 CAS 删两步），所以没有「探测之后被弹走又被误删」的窗口。
 *
 * <p><b>前提由调用方保证</b>：已确认这名玩家没有战斗锁（ready 票只有在战斗已结束时才是残留）。读票或条件删出错原样抛
 * {@link Deadline.DependencyException}。无状态，线程安全。
 */
public final class DefaultTicketHealing implements TicketHealing {

    private static final Logger log = LoggerFactory.getLogger(DefaultTicketHealing.class);

    private final TicketStore tickets;

    public DefaultTicketHealing(TicketStore tickets) {
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    @Override
    public Verdict healOrBlock(long playerId, Deadline d) {
        Optional<Ticket> existing = tickets.read(playerId, d);
        if (existing.isEmpty()) {
            return new Free();
        }
        Ticket ticket = existing.get();
        switch (ticket.state()) {
            case READY -> {
                if (tickets.heal(playerId, ticket, HealMode.READY, d)) {
                    log.info("[match] 清掉已结束战斗残留的 ready 票据 player={} ticket={} battle={}", Long.toUnsignedString(playerId),
                            ticket.ticketId(), Long.toUnsignedString(ticket.battleId()));
                    return new Free();
                }
                return new InFlight(ticket.ticketId());
            }
            case QUEUED -> {
                if (tickets.heal(playerId, ticket, HealMode.ORPHAN, d)) {
                    log.error("[match] 清掉孤儿票据（queued 但不在它的队列里）player={} ticket={} queue={} enqueued_at_ms={}",
                            Long.toUnsignedString(playerId), ticket.ticketId(), ticket.queueKey(), ticket.enqueuedAtMs());
                    return new Free();
                }
                return new InFlight(ticket.ticketId());
            }
            default -> {
                return new InFlight(ticket.ticketId());
            }
        }
    }
}
