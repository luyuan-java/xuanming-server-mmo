package com.game.match.activity;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketHealing;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.HealMode;
import java.util.Optional;

/**
 * 测试用的 {@link TicketHealing}：照接口注释写的四条规则直接读写一个 {@link TicketStore}（配内存票据存储用）。活动开战的组件测试要连
 * 「残留票被自愈、随后被本次的活动票替换」一起测，只有判定结果的替身（{@code FakeTicketHealing}）测不到这条副作用。
 * 生产的实现在票据包，这里不引用它（两个包并行开发）。
 */
final class StoreBackedHealing implements TicketHealing {

    private final TicketStore store;

    StoreBackedHealing(TicketStore store) {
        this.store = store;
    }

    @Override
    public Verdict healOrBlock(long playerId, Deadline d) {
        Optional<Ticket> seen = store.read(playerId, d);
        if (seen.isEmpty()) {
            return new Free();
        }
        Ticket ticket = seen.get();
        HealMode mode = ticket.state() == TicketState.READY ? HealMode.READY : ticket.state() == TicketState.QUEUED ? HealMode.ORPHAN : null;
        if (mode != null && store.heal(playerId, ticket, mode, d)) {
            return new Free();
        }
        return new InFlight(ticket.ticketId());
    }
}
