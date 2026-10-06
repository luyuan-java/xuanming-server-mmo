package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 票据的只读口（match-spec §0.3、§9.4 的 S_STATUS）。{@link TicketStore} 的读半边；单独成接口是给只该读票的调用方用的：
 * 查排队状态 153、成员预检、凑单的成员校验，以及 6.5 观战的「匹配中无法观战」判定（spectate-spec §4.4）。
 *
 * <p><b>契约</b>（对全部方法成立）：
 * <ul>
 *   <li><b>阻塞</b>：在调用线程上等 Redis，至多等到 {@code d}；可以在工作线程与虚拟线程上调，不得在 Dubbo / Netty I/O 线程上调。线程安全。</li>
 *   <li><b>失败</b>：Redis 出错、超出 {@code d}、或票据 HASH 损坏（存在但没有票号——正常写者不会产生）一律抛
 *       {@link Deadline.DependencyException}，绝不把故障折成「没有票」。调用方按各入口的口径翻译（157 回 16004、148 / 153 回信封 1003、
 *       预检回 {@code TICKET_READ_FAILED}、凑单结束本轮）。</li>
 *   <li>只读，没有副作用，可以随意重试。读到的是某一时刻的快照：读完之后票据可能被别的实例改掉，所以<b>任何后续的写都必须带读到的票号做 CAS</b>。</li>
 * </ul>
 */
public interface TicketReader {

    /** 玩家此刻的票据；没有（从未排队、已取消、TTL 已过）为空。 */
    Optional<Ticket> read(long playerId, Deadline d);

    /**
     * 批量读（凑单一次校验一组候选用）：结果只含<b>有票</b>的玩家，键是入参里的玩家号；入参里重复的玩家号只读一次。
     * 任何一个读失败整体抛异常（不返回半份结果）。各人的票不保证是同一时刻的快照。
     */
    Map<Long, Ticket> readAll(Collection<Long> playerIds, Deadline d);

    /**
     * 票据与读它那一刻的 Redis 时间（一次原子读）。
     *
     * @param ticket     玩家此刻的票据；没有为空
     * @param redisNowMs Redis {@code TIME}（Unix 毫秒）：等待时长一律拿它减 {@link Ticket#enqueuedAtMs()} 并夹到 ≥ 0，不用本机时钟（M7）
     */
    record Status(Optional<Ticket> ticket, long redisNowMs) {
    }

    /** 查排队状态 153 用：票据 + 同一时刻的 Redis 时间。 */
    Status status(long playerId, Deadline d);
}
