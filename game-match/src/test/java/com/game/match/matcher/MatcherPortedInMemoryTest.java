package com.game.match.matcher;

import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link MatcherPortedScenarios}（从基线移植的凑单端到端用例）跑在内存票据存储上：缺省执行，不需要任何外部依赖。
 * 同一套用例在真 Redis 实现上的那一遍见 {@link MatcherRedisIntegrationTest}。
 */
class MatcherPortedInMemoryTest extends MatcherPortedScenarios {

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore memory = new InMemoryTicketStore(clock);

    @Override
    protected TicketStore store() {
        return memory;
    }

    @Override
    protected void setEnqueuedAgo(long playerId, Duration ago) {
        Ticket t = memory.ticketOf(playerId).orElseThrow(() -> new AssertionError("没有票: " + playerId));
        long ttlMs = memory.ttlMs(playerId);
        memory.putTicket(playerId, new Ticket(t.ticketId(), t.mode(), t.configId(), t.state(), clock.peekMs() - ago.toMillis(), t.zoneId(),
                t.queueKey(), t.ratingCenti(), t.teamId(), t.battleId(), t.notBeforeMs()), ttlMs);
    }

    @Override
    protected List<String> queueMembers(QueueRef queue) {
        return memory.queueMembers(queue);
    }

    @Override
    protected Map<String, Long> rankScores(QueueRef queue) {
        return memory.rankOf(queue);
    }

    @Override
    protected boolean indexed(QueueRef queue) {
        return memory.indexed(queue);
    }

    @Override
    protected Optional<String> lockHolder(QueueRef queue) {
        return memory.lockHolder(queue);
    }

    @Override
    protected long ticketTtlMs(long playerId) {
        return memory.ttlMs(playerId);
    }

    @Override
    protected void registerQueue(QueueRef queue) {
        memory.putIndexMember(queue.queueKey());
    }

    @Override
    protected void pushRaw(QueueRef queue, String member, long ratingCenti) {
        memory.putQueueMember(queue, member, ratingCenti);
    }

    @Override
    protected boolean addRankOnly(QueueRef queue, String member, long ratingCenti) {
        // 内存实现没有「只写镜像」的口子：队列与镜像总是一起改，摆不出孤儿
        return false;
    }
}
