package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.TicketHealing;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link TicketHealing} 的测试替身：按玩家预置判定结果，不碰任何票据存储。给「只关心判定结果、不关心自愈细节」的测试用（成员预检的映射与顺序、
 * 整队 / 活动入口）。要连自愈的副作用一起测，就用真的实现配 {@link InMemoryTicketStore}。
 *
 * <pre>
 * FakeTicketHealing healing = new FakeTicketHealing();     // 缺省人人 Free
 * healing.inFlight(1002, "t-1002");                          // 这个人有在途票
 * healing.faults.failNext("healOrBlock");                    // 下一次读票失败
 * assertThat(healing.calls).containsExactly(1001L, 1002L);   // 查了谁、什么顺序
 * </pre>
 */
public final class FakeTicketHealing implements TicketHealing {

    /** 故障注入：操作名 {@code "healOrBlock"}。 */
    public final Faults faults = new Faults();
    /** 被查的玩家，按调用顺序。 */
    public final List<Long> calls = new CopyOnWriteArrayList<>();
    private final Map<Long, Verdict> verdicts = new ConcurrentHashMap<>();
    private final Map<Long, RuntimeException> failing = new ConcurrentHashMap<>();

    /** 这名玩家有一张在途的票。 */
    public FakeTicketHealing inFlight(long playerId, String ticketId) {
        verdicts.put(playerId, new InFlight(ticketId));
        return this;
    }

    /** 这名玩家没有挡路的票（缺省）。 */
    public FakeTicketHealing free(long playerId) {
        verdicts.remove(playerId);
        failing.remove(playerId);
        return this;
    }

    /** 查这名玩家时每次都失败（读票 / 自愈出错）。 */
    public FakeTicketHealing failFor(long playerId) {
        failing.put(playerId, new Deadline.DependencyException("注入的故障: 读票 player=" + Long.toUnsignedString(playerId)));
        return this;
    }

    @Override
    public Verdict healOrBlock(long playerId, Deadline d) {
        calls.add(playerId);
        faults.check("healOrBlock");
        RuntimeException error = failing.get(playerId);
        if (error != null) {
            throw error;
        }
        return verdicts.getOrDefault(playerId, new Free());
    }
}
