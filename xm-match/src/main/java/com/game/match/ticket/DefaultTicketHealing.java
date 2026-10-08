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
 *   <tr><td>ready</td><td>{@code heal(READY)}：票号一致且仍是 ready 才删。删掉了（或已不在）→ {@link Free}；没删成——ready 票不会同票号变回别的状态，
 *       只可能是读与删之间票被<b>换成了新的</b>（另一条会话排了队，或整队 / 活动开战恰在这一刻给他建了票）→ 再读一次：读到 →
 *       {@link InFlight}，带<b>现在这张票</b>的票号（157 把它放进 16001，客户端要拿它去取消；回已经不存在的旧票号的话取消会被当成票号不符
 *       静默忽略。基线在这里放行、随后建票失败时回赢家的票号，{@code join.go:235-245}、{@code :286-299}）；读不到 → {@link Free}</td></tr>
 *   <tr><td>queued</td><td>{@code heal(ORPHAN)}：票号一致、仍是 queued、它的队列里找不到这个人才删。删掉了（或已不在）→ {@link Free}（记 ERROR：
 *       Java 的入队 / 回队首都是原子的，孤儿只可能来自人为改数据或 bug——存储分不出「本次删掉」与「调用时已经不在」，所以读与删之间
 *       这张票恰好被正常删掉（取消、到期、凑单剔除）时这条 ERROR 是误报，日志里写明了）；在队列里、或探测与删票之间被弹走 → {@link InFlight}，
 *       带读到的票号（这是「人确实在排队」的常态路径，不多读一次；基线同样回读到的票号，{@code join.go:133-139}）</td></tr>
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
                // 没删成 = 票已被换成新的：带现在这张票的票号（只在这个竞态下多读一次）
                Optional<Ticket> current = tickets.read(playerId, d);
                if (current.isEmpty()) {
                    return new Free();
                }
                log.info("[match] 残留的 ready 票在读与删之间被换成了新票，按在途处理 player={} stale={} current={} state={}",
                        Long.toUnsignedString(playerId), ticket.ticketId(), current.get().ticketId(), current.get().state());
                return new InFlight(current.get().ticketId());
            }
            case QUEUED -> {
                if (tickets.heal(playerId, ticket, HealMode.ORPHAN, d)) {
                    log.error("[match] 清掉孤儿票据（queued 但不在它的队列里；也可能是读与删之间这张票恰好被取消 / 到期 / 被凑单剔除——"
                                    + "存储分不出这两种，偶发一条可以忽略，反复出现才是数据或代码有问题）player={} ticket={} queue={} enqueued_at_ms={}",
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
