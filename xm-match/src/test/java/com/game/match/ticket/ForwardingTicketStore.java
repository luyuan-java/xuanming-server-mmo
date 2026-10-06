package com.game.match.ticket;

import com.game.common.deadline.Deadline;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 把每个调用原样转给另一个 {@link TicketStore} 的测试基类：用例继承它、只覆盖想动手脚的那一个方法（在两步之间插入「另一个实例」的并发动作、
 * 让某次读回过时的票），其余行为仍是被包住的存储的。
 */
public class ForwardingTicketStore implements TicketStore {

    protected final TicketStore delegate;

    public ForwardingTicketStore(TicketStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
    public PopResult pop(QueueRef queue, String popToken, List<TicketRef> members, long matchedTtlMs, Deadline d) {
        return delegate.pop(queue, popToken, members, matchedTtlMs, d);
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
