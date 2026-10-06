package com.game.match.matcher;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 凑单测试用的票据存储包装：全部方法原样转给被包的存储（内存实现或真 Redis 实现），只在弹组这一步留两个口子——
 * <ul>
 *   <li>{@link #beforePop}：凑单校验完、弹组执行之前插一段动作（「校验与弹组之间有人取消」这类交错，对应基线的 {@code matcherAfterPopHook}）；</li>
 *   <li>{@link #popOverride}：不执行真的弹组、直接给结局（摆出「每次都被拒」这种存储本身很难稳定复现的情形）；</li>
 *   <li>{@link #afterPop}：弹组返回之后插一段动作（例如把开局管线的许可拨成 0）。</li>
 * </ul>
 * 三个口子缺省都是空的。
 */
final class HookedTicketStore implements TicketStore {

    private final TicketStore delegate;
    /** 弹组执行之前调一次（入参是要弹的人）；用完自己置回 null 就只生效一次。 */
    Consumer<List<TicketRef>> beforePop;
    /** 非 null 时弹组不落到被包的存储上，直接返回它给的结局。 */
    Function<List<TicketRef>, PopResult> popOverride;
    /** 弹组返回之后调一次（入参是结局）。 */
    Consumer<PopResult> afterPop;

    HookedTicketStore(TicketStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public PopResult pop(QueueRef queue, String popToken, List<TicketRef> members, long matchedTtlMs, Deadline d) {
        if (beforePop != null) {
            beforePop.accept(members);
        }
        PopResult result = popOverride != null ? popOverride.apply(members) : delegate.pop(queue, popToken, members, matchedTtlMs, d);
        if (afterPop != null) {
            afterPop.accept(result);
        }
        return result;
    }

    @Override
    public Optional<Ticket> read(long playerId, Deadline d) {
        return delegate.read(playerId, d);
    }

    @Override
    public Map<Long, Ticket> readAll(Collection<Long> playerIds, Deadline d) {
        return delegate.readAll(playerIds, d);
    }

    @Override
    public Status status(long playerId, Deadline d) {
        return delegate.status(playerId, d);
    }

    @Override
    public boolean heal(long playerId, Ticket seen, HealMode mode, Deadline d) {
        return delegate.heal(playerId, seen, mode, d);
    }

    @Override
    public JoinResult enqueue(long playerId, String ticketId, QueueRef queue, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        return delegate.enqueue(playerId, ticketId, queue, zoneId, ratingCenti, ttlMs, d);
    }

    @Override
    public JoinResult createMatched(long playerId, String ticketId, int mode, int configId, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
        return delegate.createMatched(playerId, ticketId, mode, configId, zoneId, ratingCenti, ttlMs, d);
    }

    @Override
    public OptionalLong createGroup(List<GroupMember> members, int mode, int configId, long teamId, long ttlMs, Deadline d) {
        return delegate.createGroup(members, mode, configId, teamId, ttlMs, d);
    }

    @Override
    public boolean cancel(long playerId, String ticketId, QueueRef queue, Deadline d) {
        return delegate.cancel(playerId, ticketId, queue, d);
    }

    @Override
    public QueueSnapshot snapshot(QueueRef queue, int limit, Deadline d) {
        return delegate.snapshot(queue, limit, d);
    }

    @Override
    public boolean drop(QueueRef queue, long playerId, DropReason reason, String seenTicketId, Deadline d) {
        return delegate.drop(queue, playerId, reason, seenTicketId, d);
    }

    @Override
    public boolean dropMalformed(QueueRef queue, String member, Deadline d) {
        return delegate.dropMalformed(queue, member, d);
    }

    @Override
    public boolean markReady(TicketRef ticket, long battleId, long readyTtlMs, Deadline d) {
        return delegate.markReady(ticket, battleId, readyTtlMs, d);
    }

    @Override
    public int extendMatched(List<TicketRef> tickets, long ttlMs, Deadline d) {
        return delegate.extendMatched(tickets, ttlMs, d);
    }

    @Override
    public boolean delete(TicketRef ticket, Deadline d) {
        return delegate.delete(ticket, d);
    }

    @Override
    public int deleteGroup(List<TicketRef> tickets, Deadline d) {
        return delegate.deleteGroup(tickets, d);
    }

    @Override
    public int requeueFront(QueueRef queue, List<TicketRef> survivorsInOrder, long queuedTtlMs, long notBeforeDelayMs, Deadline d) {
        return delegate.requeueFront(queue, survivorsInOrder, queuedTtlMs, notBeforeDelayMs, d);
    }

    @Override
    public Set<String> queueIndex(Deadline d) {
        return delegate.queueIndex(d);
    }

    @Override
    public long queueLength(QueueRef queue, Deadline d) {
        return delegate.queueLength(queue, d);
    }

    @Override
    public boolean pruneIfEmpty(QueueRef queue, Deadline d) {
        return delegate.pruneIfEmpty(queue, d);
    }

    @Override
    public boolean tryLockQueue(QueueRef queue, String instanceId, long ttlMs, Deadline d) {
        return delegate.tryLockQueue(queue, instanceId, ttlMs, d);
    }

    @Override
    public void unlockQueue(QueueRef queue, String instanceId, Deadline d) {
        delegate.unlockQueue(queue, instanceId, d);
    }
}
