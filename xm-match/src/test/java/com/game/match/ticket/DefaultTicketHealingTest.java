package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.TicketHealing.Free;
import com.game.match.ticket.TicketHealing.InFlight;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 「这名玩家手里有没有挡路的票」的自愈规则（match-spec §2.2「自愈」；基线 {@code join.go:234-269}）：没有票放行；残留的 ready 票与孤儿 queued 票
 * 按票号条件删后放行；其余都算在途、带读到的票号、不碰它。排队 157 与成员预检共用这一条规则。
 */
class DefaultTicketHealingTest {

    private static final QueueRef Q = new QueueRef(3, 0);
    private static final long QUEUED_TTL = 21_600_000;

    private final InMemoryTicketStore store = new InMemoryTicketStore(new ManualRedisClock());
    private final DefaultTicketHealing healing = new DefaultTicketHealing(store);

    private static Deadline d() {
        return Deadline.after(1000);
    }

    @Test
    void 没有票_放行_只读了一次() {
        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new Free());

        assertThat(store.calls).containsExactly("read(1001)");
    }

    @Test
    void 残留的ready票_删掉后放行() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new Free());

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.calls).containsExactly("read(1001)", "heal(1001,READY)");
    }

    @Test
    void 孤儿queued票_队列里找不到这个人_删掉后放行() {
        store.putTicket(1001, new Ticket("orphan", 3, 0, TicketState.QUEUED, 1, 1, Q.queueKey(), 150_000, 0, 0, 0), QUEUED_TTL);

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new Free());

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.calls).containsExactly("read(1001)", "heal(1001,ORPHAN)");
    }

    @Test
    void 在队列里的queued票_在途_带现有票号_不动() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new InFlight("t-1001"));

        assertThat(store.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
        assertThat(store.queueMembers(Q)).containsExactly("1001");
    }

    @Test
    void matched票_在途_不去自愈() {
        store.createMatched(1001, "in-gather", 4, 1, 1, 150_000, 42_000, d());
        store.calls.clear();

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new InFlight("in-gather"));

        assertThat(store.calls).as("gather 在途，靠 matched TTL 自愈：只读不删").containsExactly("read(1001)");
        assertThat(store.ticketOf(1001)).isPresent();
    }

    @Test
    void 状态不认识的票_在途_不碰() {
        store.putTicket(1001, new Ticket("weird", 3, 0, TicketState.UNKNOWN, 1, 1, "", 150_000, 0, 0, 0), 60_000);

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new InFlight("weird"));

        assertThat(store.ticketOf(1001)).isPresent();
        assertThat(store.calls).containsExactly("read(1001)");
    }

    @Test
    void 读到ready之后票被换成了新的_条件删没删成_按在途_带的是现在这张票的票号() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        TicketStore racing = new RacingStore(store, () -> {
            // 读与条件删之间：旧票过期，玩家从另一处重排了一张新票
            store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 0);
            store.enqueue(1001, "t-new", Q, 1, 150_000, QUEUED_TTL, d());
        });
        store.calls.clear();

        assertThat(new DefaultTicketHealing(racing).healOrBlock(1001, d())).as("16001 里要带客户端能拿去取消的票号：旧票已经不存在了")
                .isEqualTo(new InFlight("t-new"));

        assertThat(store.ticketOf(1001).orElseThrow().ticketId()).as("新票没有被误删").isEqualTo("t-new");
        assertThat(store.calls).as("只在这个竞态下多读一次").containsExactly("read(1001)", "enqueue(1001)", "heal(1001,READY)", "read(1001)");
    }

    @Test
    void 读到ready之后票被换成新的_重读时新票又没了_放行() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        AtomicInteger reads = new AtomicInteger();
        TicketStore racing = new RacingStore(store, () -> store.putTicket(1001,
                new Ticket("t-new", 4, 1, TicketState.MATCHED, 1, 1, "", 150_000, 0, 0, 0), 42_000)) {
            @Override
            public Optional<Ticket> read(long playerId, Deadline d) {
                if (reads.incrementAndGet() == 2) {
                    // 重读之前新票也没了（PVE_SOLO 的 gather 失败删票）
                    store.putTicket(1001, new Ticket("t-new", 4, 1, TicketState.MATCHED, 1, 1, "", 150_000, 0, 0, 0), 0);
                }
                return super.read(playerId, d);
            }
        };

        assertThat(new DefaultTicketHealing(racing).healOrBlock(1001, d())).isEqualTo(new Free());

        assertThat(reads.get()).isEqualTo(2);
        assertThat(store.ticketOf(1001)).isEmpty();
    }

    @Test
    void 在队列里的queued票_常态路径不多读一次() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        store.calls.clear();

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new InFlight("t-1001"));

        assertThat(store.calls).containsExactly("read(1001)", "heal(1001,ORPHAN)");
    }

    @Test
    void ready票没删成之后的重读失败_原样抛依赖异常() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        TicketStore racing = new RacingStore(store, () -> {
            store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 0);
            store.enqueue(1001, "t-new", Q, 1, 150_000, QUEUED_TTL, d());
            store.faults.failNext("read");
        });

        assertThatThrownBy(() -> new DefaultTicketHealing(racing).healOrBlock(1001, d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(1001).orElseThrow().ticketId()).isEqualTo("t-new");
    }

    @Test
    void 读到孤儿之后人被弹走了_条件删没删成_按在途() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        Ticket queued = store.ticketOf(1001).orElseThrow();
        store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), 48_000, d());
        // 调用方读到的是弹出之前的 queued 票（此刻队列里已经没有他）
        TicketStore stale = new StaleReadStore(store, queued);

        assertThat(new DefaultTicketHealing(stale).healOrBlock(1001, d())).isEqualTo(new InFlight("t-1001"));

        assertThat(store.ticketOf(1001).orElseThrow().state()).as("matched 的票不能被当成孤儿删掉").isEqualTo(TicketState.MATCHED);
    }

    @Test
    void 读票失败_原样抛依赖异常() {
        store.faults.failNext("read");

        assertThatThrownBy(() -> healing.healOrBlock(1001, d())).isInstanceOf(Deadline.DependencyException.class);
    }

    @Test
    void 条件删失败_原样抛依赖异常_票还在() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        store.faults.failNext("heal");

        assertThatThrownBy(() -> healing.healOrBlock(1001, d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(1001)).isPresent();
        assertThat(healing.healOrBlock(1001, d())).as("下一次照常自愈").isEqualTo(new Free());
    }

    @Test
    void 幂等_重复调用结果收敛() {
        store.putTicket(1001, new Ticket("stale-ready", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new Free());
        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new Free());
    }

    /** 在第一次 {@code heal} 之前插入一段并发动作的存储。 */
    private static class RacingStore extends ForwardingTicketStore {
        private Runnable beforeHeal;

        RacingStore(TicketStore delegate, Runnable beforeHeal) {
            super(delegate);
            this.beforeHeal = beforeHeal;
        }

        @Override
        public boolean heal(long playerId, Ticket seen, HealMode mode, Deadline d) {
            Runnable action = beforeHeal;
            beforeHeal = null;
            if (action != null) {
                action.run();
            }
            return super.heal(playerId, seen, mode, d);
        }
    }

    /** {@code read} 回一张过时的票（模拟读与写之间状态变了）。 */
    private static final class StaleReadStore extends ForwardingTicketStore {
        private final Ticket stale;

        StaleReadStore(TicketStore delegate, Ticket stale) {
            super(delegate);
            this.stale = stale;
        }

        @Override
        public Optional<Ticket> read(long playerId, Deadline d) {
            return Optional.of(stale);
        }
    }
}
