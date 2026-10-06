package com.game.match.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.match.ticket.TicketStoreContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 内存票据存储：先过 {@link TicketStoreContract} 的全部用例（与 Redis 实现同一套断言——别的包的组件测试都建在这个替身上，它不许和真实现漂移），
 * 再加只有替身才有的东西：手拨时钟下精确的时刻与 TTL、故障注入的两个注入点、调用序列。
 */
class InMemoryTicketStoreTest extends TicketStoreContract {

    private static final QueueRef Q = new QueueRef(3, 0);

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore store = new InMemoryTicketStore(clock);

    // ================================================================ 契约测试的钩子

    @Override
    protected TicketStore store() {
        return store;
    }

    @Override
    protected long pid(int n) {
        return 1000 + n;
    }

    @Override
    protected QueueRef queue(int n) {
        return new QueueRef(3, n);
    }

    @Override
    protected String token(String name) {
        return name;
    }

    @Override
    protected long nowMs() {
        return clock.peekMs();
    }

    @Override
    protected void putTicket(long playerId, Ticket ticket, long ttlMs) {
        store.putTicket(playerId, ticket, ttlMs);
    }

    @Override
    protected void putQueueMember(QueueRef queue, String member, Long ratingCenti) {
        store.putQueueMember(queue, member, ratingCenti);
    }

    @Override
    protected Optional<Ticket> ticketOf(long playerId) {
        return store.ticketOf(playerId);
    }

    @Override
    protected long ttlMs(long playerId) {
        return store.ttlMs(playerId);
    }

    @Override
    protected List<String> queueMembers(QueueRef queue) {
        return store.queueMembers(queue);
    }

    @Override
    protected Map<String, Long> rankOf(QueueRef queue) {
        return store.rankOf(queue);
    }

    @Override
    protected boolean indexed(QueueRef queue) {
        return store.indexed(queue);
    }

    @Override
    protected Optional<String> lockHolder(QueueRef queue) {
        return store.lockHolder(queue);
    }

    @Override
    protected void expireTicket(long playerId) {
        store.ticketOf(playerId).ifPresent(ticket -> store.putTicket(playerId, ticket, 0));
    }

    @Override
    protected void expirePopMarker(String popToken) {
        store.expirePopMarker(popToken);
    }

    @Override
    protected void expireLock(QueueRef queue) {
        store.expireLock(queue);
    }

    // ================================================================ 手拨时钟：精确的时刻与 TTL

    @Test
    void 入队时刻与TTL按手拨时钟精确可算() {
        JoinResult first = store.enqueue(1001, "t-1001", Q, 2, 162_500, QUEUED_TTL, d());
        clock.advanceSeconds(3);
        store.enqueue(1002, "t-1002", Q, 1, 150_000, QUEUED_TTL, d());

        assertThat(first).isEqualTo(new JoinResult.Created(ManualRedisClock.DEFAULT_START_MS));
        assertThat(store.read(1001, d())).contains(new Ticket("t-1001", 3, 0, TicketState.QUEUED, ManualRedisClock.DEFAULT_START_MS, 2,
                "xm:{match}:queue:3:0", 162_500, 0, 0, 0));
        assertThat(store.ttlMs(1001)).isEqualTo(QUEUED_TTL - 3000);
        assertThat(store.ticketOf(1002).orElseThrow().enqueuedAtMs()).isEqualTo(ManualRedisClock.DEFAULT_START_MS + 3000);
    }

    @Test
    void 拨过TTL之后票据读不到_差一毫秒还在() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());

        clock.advanceMs(QUEUED_TTL - 1);
        assertThat(store.read(1001, d())).isPresent();
        clock.advanceMs(1);

        assertThat(store.read(1001, d())).isEmpty();
        assertThat(store.ticketCount()).isZero();
        assertThat(store.queueMembers(Q)).as("残留项照旧留着").containsExactly("1001");
    }

    @Test
    void 查状态的时间就是手拨时钟_等待时长可以直接算() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        clock.advanceSeconds(17);

        TicketReader.Status status = store.status(1001, d());

        assertThat(status.redisNowMs() - status.ticket().orElseThrow().enqueuedAtMs()).isEqualTo(17_000);
        assertThat(store.snapshot(Q, 10, d()).redisNowMs()).isEqualTo(clock.peekMs());
    }

    @Test
    void 弹组的重放标记60秒后失效_matched的TTL从弹出那一刻算() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        clock.advanceSeconds(2);
        store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), 120_000, d());
        assertThat(store.ttlMs(1001)).isEqualTo(120_000);

        clock.advanceMs(InMemoryTicketStore.POP_MARKER_TTL_MS - 1);
        assertThat(store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), 120_000, d())).isInstanceOf(PopResult.Replayed.class);
        clock.advanceMs(1);

        assertThat(store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), 120_000, d())).as("标记过期后按现状重新核对：他已经不是 queued")
                .isEqualTo(new PopResult.Invalid(List.of(1001L)));
    }

    @Test
    void 退避到点由手拨时钟决定() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d());
        store.requeueFront(Q, List.of(new TicketRef(1001, "t-1001")), QUEUED_TTL, 2000, d());

        assertThat(store.ticketOf(1001).orElseThrow().notBeforeMs()).isEqualTo(clock.peekMs() + 2000);
        clock.advanceMs(1999);
        assertThat(store.pop(Q, "pop-2", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d())).isInstanceOf(PopResult.Invalid.class);
        clock.advanceMs(1);
        assertThat(store.pop(Q, "pop-3", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
    }

    @Test
    void 凑单锁到TTL自己过期() {
        store.tryLockQueue(Q, "inst-a", 10_000, d());

        clock.advanceMs(9_999);
        assertThat(store.lockHolder(Q)).contains("inst-a");
        clock.advanceMs(1);

        assertThat(store.lockHolder(Q)).isEmpty();
        assertThat(store.tryLockQueue(Q, "inst-b", 10_000, d())).isTrue();
    }

    @Test
    void 整组建票的重放不续期_精确到毫秒() {
        List<GroupMember> members = List.of(new GroupMember(11, "g-11", 1), new GroupMember(12, "g-12", 1));
        store.createGroup(members, 5, 1, 900, 48_000, d());
        clock.advanceSeconds(5);

        assertThat(store.createGroup(members, 5, 1, 900, 48_000, d())).isEmpty();

        assertThat(store.ttlMs(11)).as("没有续期").isEqualTo(43_000);
    }

    // ================================================================ 故障注入与调用序列

    @Test
    void 执行前失败_什么都没写() {
        store.faults.failNext("enqueue");

        assertThatThrownBy(() -> store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.queueMembers(Q)).isEmpty();
        assertThat(store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d())).as("只失败一次").isInstanceOf(JoinResult.Created.class);
    }

    @Test
    void 执行后失败_效果已生效_调用方看到的是结局不明_重发得到重放() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        store.faults.failNext("pop:after");

        assertThatThrownBy(() -> store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d()))
                .isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(1001).orElseThrow().state()).as("其实已经弹出").isEqualTo(TicketState.MATCHED);
        assertThat(store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d())).isInstanceOf(PopResult.Replayed.class);
    }

    @Test
    void 持续失败_直到清除() {
        store.faults.failAlways("read");

        assertThatThrownBy(() -> store.read(1001, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store.read(1001, d())).isInstanceOf(Deadline.DependencyException.class);
        store.faults.clear("read");
        assertThat(store.read(1001, d())).isEmpty();
    }

    @Test
    void 调用序列按发生顺序记下_失败的调用也记() {
        store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());
        store.snapshot(Q, 256, d());
        store.faults.failNext("delete");
        assertThatThrownBy(() -> store.delete(new TicketRef(1001, "t-1001"), d())).isInstanceOf(Deadline.DependencyException.class);
        store.pop(Q, "pop-1", List.of(new TicketRef(1001, "t-1001")), MATCHED_TTL, d());

        assertThat(store.calls).containsExactly("enqueue(1001)", "snapshot(3:0)", "delete(1001)", "pop(3:0,[1001])");
    }

    @Test
    void 玩家号是64位无符号_队列成员与调用序列里都是无符号十进制() {
        long big = Long.MIN_VALUE + 4301;
        store.enqueue(big, "t-big", Q, 1, 150_000, QUEUED_TTL, d());

        assertThat(store.queueMembers(Q)).containsExactly("9223372036854780109");
        assertThat(store.snapshot(Q, 10, d()).entries().get(0).playerId()).isEqualTo(big);
        assertThat(store.calls).contains("enqueue(9223372036854780109)");
        assertThat(InMemoryTicketStore.parsePlayerId("18446744073709551615")).isEqualTo(-1L);
        assertThat(InMemoryTicketStore.parsePlayerId("18446744073709551616")).as("超出 uint64").isZero();
        assertThat(InMemoryTicketStore.parsePlayerId("007")).as("前导零不是规范形").isZero();
        assertThat(InMemoryTicketStore.parsePlayerId("-5")).isZero();
    }
}
