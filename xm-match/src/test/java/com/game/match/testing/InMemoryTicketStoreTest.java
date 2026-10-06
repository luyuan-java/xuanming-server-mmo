package com.game.match.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore.DropReason;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.match.ticket.TicketStore.HealMode;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.match.ticket.TicketStore.QueueSnapshot;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 内存票据存储按 {@code TicketStore} 的接口注释行事（不变量 I1–I5、每个写的 CAS 条件与重放行为）。别的包的组件测试都建在它上面，
 * 所以这里把接口注释里的每一条承诺各钉一例；Redis 实现要过同样的断言（票据工作包会把这些用例抽成两边共跑的契约测试）。
 */
class InMemoryTicketStoreTest {

    private static final QueueRef Q = new QueueRef(3, 0);
    private static final QueueRef OTHER = new QueueRef(3, 7);
    private static final long QUEUED_TTL = 21_600_000;
    private static final long MATCHED_TTL = 48_000;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore store = new InMemoryTicketStore(clock);

    private static Deadline d() {
        return Deadline.after(1000);
    }

    private void enqueue(long playerId, long ratingCenti) {
        assertThat(store.enqueue(playerId, "t-" + playerId, Q, 1, ratingCenti, QUEUED_TTL, d())).isInstanceOf(JoinResult.Created.class);
    }

    private static TicketRef ref(long playerId) {
        return new TicketRef(playerId, "t-" + playerId);
    }

    // ================================================================ 入队、读、状态

    @Test
    void 入队_建queued票_登记注册集_写评分镜像_入队尾_时间取Redis时间() {
        JoinResult first = store.enqueue(1001, "t-1001", Q, 2, 162_500, QUEUED_TTL, d());
        clock.advanceSeconds(3);
        enqueue(1002, 150_000);

        assertThat(first).isEqualTo(new JoinResult.Created(ManualRedisClock.DEFAULT_START_MS));
        assertThat(store.queueMembers(Q)).as("等得最久的在队首").containsExactly("1001", "1002");
        assertThat(store.rankOf(Q)).containsOnlyKeys("1001", "1002").containsEntry("1001", 162_500L);
        assertThat(store.indexed(Q)).as("I1：非空队列一定在注册集里").isTrue();
        assertThat(store.read(1001, d())).contains(new Ticket("t-1001", 3, 0, TicketState.QUEUED, ManualRedisClock.DEFAULT_START_MS, 2,
                "xm:{match}:queue:3:0", 162_500, 0, 0, 0));
        assertThat(store.ttlMs(1001)).isEqualTo(QUEUED_TTL - 3000);
    }

    @Test
    void 入队重放_同一票号再执行一次_不会入两次队_也不报已在队列中() {
        enqueue(1001, 150_000);

        JoinResult replay = store.enqueue(1001, "t-1001", Q, 1, 150_000, QUEUED_TTL, d());

        assertThat(replay).isInstanceOf(JoinResult.Replayed.class);
        assertThat(store.queueMembers(Q)).containsExactly("1001");
    }

    @Test
    void 已有别的票_什么都不写_回现有票号() {
        enqueue(1001, 150_000);

        JoinResult second = store.enqueue(1001, "t-new", OTHER, 1, 150_000, QUEUED_TTL, d());
        JoinResult solo = store.createMatched(1001, "t-solo", 4, 1, 1, 150_000, 42_000, d());

        assertThat(second).isEqualTo(new JoinResult.Exists("t-1001"));
        assertThat(solo).isEqualTo(new JoinResult.Exists("t-1001"));
        assertThat(store.queueMembers(OTHER)).isEmpty();
        assertThat(store.indexed(OTHER)).isFalse();
        assertThat(store.ticketOf(1001).orElseThrow().ticketId()).as("I3：每人至多一张票").isEqualTo("t-1001");
    }

    @Test
    void 不入队直接建matched票_队列键为空_不碰任何队列() {
        JoinResult result = store.createMatched(1001, "t-solo", 4, 1, 2, 150_000, 42_000, d());

        Ticket ticket = store.ticketOf(1001).orElseThrow();
        assertThat(result).isInstanceOf(JoinResult.Created.class);
        assertThat(ticket.state()).isEqualTo(TicketState.MATCHED);
        assertThat(ticket.queueKey()).isEmpty();
        assertThat(ticket.mode()).isEqualTo(4);
        assertThat(store.queueIndex(d())).isEmpty();
        assertThat(store.ttlMs(1001)).isEqualTo(42_000);
        assertThat(store.createMatched(1001, "t-solo", 4, 1, 2, 150_000, 42_000, d())).isInstanceOf(JoinResult.Replayed.class);
    }

    @Test
    void 票据到期后读不到_队列里的残留项照旧留着() {
        enqueue(1001, 150_000);

        clock.advanceMs(QUEUED_TTL);

        assertThat(store.read(1001, d())).isEmpty();
        assertThat(store.queueMembers(Q)).as("残留项由凑单的校验剔除").containsExactly("1001");
        assertThat(store.enqueue(1001, "t-again", Q, 1, 150_000, QUEUED_TTL, d())).as("过期之后可以重排").isInstanceOf(JoinResult.Created.class);
    }

    @Test
    void 查状态_票据与同一时刻的Redis时间() {
        enqueue(1001, 150_000);
        clock.advanceSeconds(17);

        TicketReader.Status status = store.status(1001, d());
        TicketReader.Status none = store.status(2002, d());

        assertThat(status.redisNowMs() - status.ticket().orElseThrow().enqueuedAtMs()).isEqualTo(17_000);
        assertThat(none.ticket()).isEmpty();
        assertThat(none.redisNowMs()).isEqualTo(clock.peekMs());
    }

    @Test
    void 批量读_只含有票的人() {
        enqueue(1001, 150_000);
        enqueue(1003, 150_000);

        assertThat(store.readAll(List.of(1001L, 1002L, 1003L, 1001L), d())).containsOnlyKeys(1001L, 1003L);
    }

    // ================================================================ 取消

    @Test
    void 取消_票号一致且仍是queued_删票并摘出队列与镜像() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);

        assertThat(store.cancel(1001, "t-1001", Q, d())).isTrue();

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.queueMembers(Q)).containsExactly("1002");
        assertThat(store.rankOf(Q)).as("I2：镜像与队列成员一致").containsOnlyKeys("1002");
        assertThat(store.cancel(1001, "t-1001", Q, d())).as("重放：已经删了").isFalse();
    }

    @Test
    void 取消太迟_票号不符或已被弹走_什么都不动() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        assertThat(store.cancel(1001, "t-stale", Q, d())).as("票号不符").isFalse();
        assertThat(store.cancel(1001, "t-1001", OTHER, d())).as("队列不符").isFalse();
        store.pop(Q, "pop-1", List.of(ref(1001), ref(1002)), MATCHED_TTL, d());

        assertThat(store.cancel(1001, "t-1001", Q, d())).as("I5：matched 之后取消太迟").isFalse();
        assertThat(store.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
    }

    // ================================================================ 快照、剔除

    @Test
    void 快照_等待序_镜像分_非法成员与缺分都原样暴露() {
        enqueue(1001, 162_500);
        store.putQueueMember(Q, "abc", null);
        store.putQueueMember(Q, "0", null);
        store.putQueueMember(Q, "1005", null);
        enqueue(1002, 140_000);
        clock.advanceSeconds(5);

        QueueSnapshot all = store.snapshot(Q, 256, d());
        QueueSnapshot prefix = store.snapshot(Q, 2, d());

        assertThat(all.redisNowMs()).isEqualTo(clock.peekMs());
        assertThat(all.entries()).extracting(e -> e.member() + "/" + Long.toUnsignedString(e.playerId()) + "/" + e.ratingCenti())
                .containsExactly("1001/1001/OptionalLong[162500]", "abc/0/OptionalLong.empty", "0/0/OptionalLong.empty",
                        "1005/1005/OptionalLong.empty", "1002/1002/OptionalLong[140000]");
        assertThat(prefix.entries()).hasSize(2);
        assertThat(store.snapshot(OTHER, 256, d()).entries()).isEmpty();
    }

    @Test
    void 剔除非法成员_只动队列_不碰票据() {
        enqueue(1001, 150_000);
        store.putQueueMember(Q, "abc", null).putQueueMember(Q, "abc", null);

        assertThat(store.dropMalformed(Q, "abc", d())).isTrue();
        assertThat(store.dropMalformed(Q, "abc", d())).as("已经没有了").isFalse();
        assertThat(store.queueMembers(Q)).containsExactly("1001");
        assertThat(store.ticketOf(1001)).isPresent();
    }

    @Test
    void 剔除_票据缺失或状态不对的成员_任何原因都只摘队列项() {
        store.putQueueMember(Q, "1001", 150_000L);
        enqueue(1002, 150_000);
        store.putTicket(1002, new Ticket("t-1002", 3, 0, TicketState.MATCHED, 1, 1, Q.queueKey(), 150_000, 0, 0, 0), MATCHED_TTL);

        assertThat(store.drop(Q, 1001, DropReason.INVALID, null, d())).isTrue();
        assertThat(store.drop(Q, 1002, DropReason.INVALID, "t-1002", d())).isTrue();

        assertThat(store.queueMembers(Q)).isEmpty();
        assertThat(store.rankOf(Q)).isEmpty();
        assertThat(store.ticketOf(1002)).as("不是 queued 的票不动").isPresent();
    }

    @Test
    void 剔除_有战斗锁或已离线_票号一致才删票() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);

        assertThat(store.drop(Q, 1001, DropReason.IN_BATTLE, "t-1001", d())).isTrue();
        assertThat(store.drop(Q, 1002, DropReason.OFFLINE, "t-1002", d())).isTrue();

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.ticketOf(1002)).isEmpty();
        assertThat(store.queueMembers(Q)).isEmpty();
    }

    @Test
    void 剔除_不误伤期间重新入队的同一玩家() {
        enqueue(1001, 150_000);
        // 凑单读到的是 t-old；之后玩家取消并重新排了一次（现在是 t-1001）
        assertThat(store.drop(Q, 1001, DropReason.IN_BATTLE, "t-old", d())).isFalse();
        assertThat(store.drop(Q, 1001, DropReason.INVALID, null, d())).as("他现在是一张有效的 queued 票").isFalse();

        assertThat(store.ticketOf(1001)).isPresent();
        assertThat(store.queueMembers(Q)).containsExactly("1001");
    }

    // ================================================================ 弹组

    @Test
    void 弹组_全员满足才一次性摘出并置matched_写下重放标记() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        enqueue(1003, 150_000);
        clock.advanceSeconds(2);

        PopResult result = store.pop(Q, "pop-1", List.of(ref(1003), ref(1001)), MATCHED_TTL, d());

        assertThat(result).isInstanceOf(PopResult.Popped.class);
        assertThat(store.queueMembers(Q)).containsExactly("1002");
        assertThat(store.rankOf(Q)).containsOnlyKeys("1002");
        assertThat(store.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
        assertThat(store.ticketOf(1003).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
        assertThat(store.ttlMs(1001)).isEqualTo(MATCHED_TTL);
        assertThat(store.ticketOf(1001).orElseThrow().enqueuedAtMs()).as("入队时刻不变").isEqualTo(ManualRedisClock.DEFAULT_START_MS);
        assertThat(store.pop(Q, "pop-1", List.of(ref(1003), ref(1001)), MATCHED_TTL, d())).as("同一个 token 重发").isInstanceOf(PopResult.Replayed.class);
    }

    @Test
    void 弹组_有人不满足_什么都不写_返回不满足的人() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        enqueue(1003, 150_000);
        store.cancel(1002, "t-1002", Q, d());

        PopResult result = store.pop(Q, "pop-1", List.of(ref(1001), ref(1002), new TicketRef(1003, "t-stale"), ref(1004)), MATCHED_TTL, d());

        assertThat(result).isEqualTo(new PopResult.Invalid(List.of(1002L, 1003L, 1004L)));
        assertThat(store.queueMembers(Q)).as("全有全无：1001 没有离开原位").containsExactly("1001", "1003");
        assertThat(store.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
        assertThat(store.pop(Q, "pop-1", List.of(ref(1001), ref(1003)), MATCHED_TTL, d())).as("失败的弹组不留重放标记").isInstanceOf(PopResult.Popped.class);
    }

    @Test
    void 弹组_票在但人不在队列里_或票属于别的队列_都算不满足() {
        store.putTicket(1001, new Ticket("t-1001", 3, 0, TicketState.QUEUED, 1, 1, Q.queueKey(), 150_000, 0, 0, 0), QUEUED_TTL);
        store.enqueue(1002, "t-1002", OTHER, 1, 150_000, QUEUED_TTL, d());

        assertThat(store.pop(Q, "pop-1", List.of(ref(1001), ref(1002)), MATCHED_TTL, d())).isEqualTo(new PopResult.Invalid(List.of(1001L, 1002L)));
    }

    @Test
    void 弹组的重放标记60秒后失效() {
        enqueue(1001, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d());

        clock.advanceMs(InMemoryTicketStore.POP_MARKER_TTL_MS);

        assertThat(store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d())).as("标记过期后按现状重新核对：他已经不是 queued").isInstanceOf(PopResult.Invalid.class);
    }

    // ================================================================ gather 的写

    @Test
    void 置ready_要求票号一致且是matched_写battle_id与新TTL_可重放() {
        enqueue(1001, 150_000);
        assertThat(store.markReady(ref(1001), 77, 60_000, d())).as("还是 queued").isFalse();
        store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d());

        assertThat(store.markReady(new TicketRef(1001, "t-stale"), 77, 60_000, d())).as("I4：票号不符不写").isFalse();
        assertThat(store.markReady(ref(1001), 77, 60_000, d())).isTrue();

        Ticket ticket = store.ticketOf(1001).orElseThrow();
        assertThat(ticket.state()).isEqualTo(TicketState.READY);
        assertThat(ticket.battleId()).isEqualTo(77);
        assertThat(store.ttlMs(1001)).isEqualTo(60_000);
        assertThat(store.markReady(ref(1001), 77, 60_000, d())).as("同一个 battle_id 的重放").isTrue();
        assertThat(store.markReady(ref(1001), 78, 60_000, d())).as("别的 battle_id").isFalse();
        assertThat(store.markReady(ref(2002), 77, 60_000, d())).as("没有票").isFalse();
    }

    @Test
    void 续期_只续票号一致且仍是matched的票() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        enqueue(1003, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001), ref(1002)), MATCHED_TTL, d());

        int extended = store.extendMatched(List.of(ref(1001), new TicketRef(1002, "t-stale"), ref(1003), ref(1004)), 16_000, d());

        assertThat(extended).isEqualTo(1);
        assertThat(store.ttlMs(1001)).as("照设：可以比原来短").isEqualTo(16_000);
        assertThat(store.ttlMs(1002)).isEqualTo(MATCHED_TTL);
        assertThat(store.ttlMs(1003)).as("queued 的票不续").isEqualTo(QUEUED_TTL);
    }

    @Test
    void 删票_票号一致就删_不摘队列项_可重放() {
        enqueue(1001, 150_000);

        assertThat(store.delete(new TicketRef(1001, "t-stale"), d())).isFalse();
        assertThat(store.delete(ref(1001), d())).isTrue();
        assertThat(store.delete(ref(1001), d())).as("重放").isFalse();

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.queueMembers(Q)).as("残留项留给凑单的校验").containsExactly("1001");
    }

    @Test
    void 删一组票_逐张票号一致才删() {
        store.createGroup(List.of(new GroupMember(1, "g-1", 1), new GroupMember(2, "g-2", 1), new GroupMember(3, "g-3", 1)), 5, 1, 900, 66_000, d());

        int deleted = store.deleteGroup(List.of(new TicketRef(1, "g-1"), new TicketRef(2, "other"), new TicketRef(3, "g-3"), new TicketRef(4, "g-4")), d());

        assertThat(deleted).isEqualTo(2);
        assertThat(store.ticketOf(2)).isPresent();
        assertThat(store.ticketCount()).isEqualTo(1);
    }

    @Test
    void 回队首_幸存者按原相对顺序排在原有成员之前_恢复queued与长TTL_入队时刻不变() {
        enqueue(1001, 162_500);
        enqueue(1002, 150_000);
        enqueue(1003, 140_000);
        clock.advanceSeconds(10);
        enqueue(1004, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001), ref(1002), ref(1003)), MATCHED_TTL, d());

        int requeued = store.requeueFront(Q, List.of(ref(1001), ref(1003)), QUEUED_TTL, 0, d());

        assertThat(requeued).isEqualTo(2);
        assertThat(store.queueMembers(Q)).containsExactly("1001", "1003", "1004");
        assertThat(store.rankOf(Q)).as("按票里的评分写回镜像").containsEntry("1001", 162_500L).containsEntry("1003", 140_000L);
        Ticket back = store.ticketOf(1001).orElseThrow();
        assertThat(back.state()).isEqualTo(TicketState.QUEUED);
        assertThat(back.enqueuedAtMs()).as("等待时长从第一次入队算").isEqualTo(ManualRedisClock.DEFAULT_START_MS);
        assertThat(back.notBeforeMs()).isZero();
        assertThat(store.ttlMs(1001)).isEqualTo(QUEUED_TTL);
        assertThat(store.ticketOf(1002).orElseThrow().state()).as("没在幸存者名单里的人不动").isEqualTo(TicketState.MATCHED);
        assertThat(store.indexed(Q)).isTrue();
    }

    @Test
    void 回队首重放_已是queued的跳过_不留孤儿_返回0() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001), ref(1002)), MATCHED_TTL, d());
        store.requeueFront(Q, List.of(ref(1001), ref(1002)), QUEUED_TTL, 0, d());

        int replay = store.requeueFront(Q, List.of(ref(1001), ref(1002)), QUEUED_TTL, 0, d());

        assertThat(replay).isZero();
        assertThat(store.queueMembers(Q)).as("没有被推两次").containsExactly("1001", "1002");
    }

    @Test
    void 回队首_票已过期或已换的人跳过_其余照回() {
        enqueue(1001, 150_000);
        enqueue(1002, 150_000);
        enqueue(1003, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001), ref(1002), ref(1003)), MATCHED_TTL, d());
        store.delete(ref(1002), d());

        int requeued = store.requeueFront(Q, List.of(ref(1001), ref(1002), new TicketRef(1003, "t-stale")), QUEUED_TTL, 0, d());

        assertThat(requeued).isEqualTo(1);
        assertThat(store.queueMembers(Q)).containsExactly("1001");
        assertThat(store.ticketOf(1003).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
    }

    @Test
    void 回队首带退避_到点之前弹不出来_但不会被当成无效成员剔除() {
        enqueue(1001, 150_000);
        store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d());
        store.requeueFront(Q, List.of(ref(1001)), QUEUED_TTL, 2000, d());

        assertThat(store.ticketOf(1001).orElseThrow().notBeforeMs()).isEqualTo(clock.peekMs() + 2000);
        assertThat(store.ticketOf(1001).orElseThrow().backingOff(clock.peekMs())).isTrue();
        assertThat(store.pop(Q, "pop-2", List.of(ref(1001)), MATCHED_TTL, d())).isEqualTo(new PopResult.Invalid(List.of(1001L)));
        assertThat(store.drop(Q, 1001, DropReason.INVALID, "t-1001", d())).as("只是退避没到：不剔除").isFalse();

        clock.advanceMs(2000);
        assertThat(store.pop(Q, "pop-3", List.of(ref(1001)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
    }

    @Test
    void 不入队的票回不了队首() {
        store.createMatched(1001, "t-1001", 4, 1, 1, 150_000, 42_000, d());

        assertThat(store.requeueFront(Q, List.of(ref(1001)), QUEUED_TTL, 0, d())).isZero();
        assertThat(store.queueMembers(Q)).isEmpty();
    }

    // ================================================================ 整组建票

    @Test
    void 整组建票_全员没有票才建_带队伍号_不入队() {
        OptionalLong conflict = store.createGroup(List.of(new GroupMember(11, "g-11", 1), new GroupMember(12, "g-12", 2)), 5, 1, 900, 48_000, d());

        assertThat(conflict).isEmpty();
        Ticket ticket = store.ticketOf(12).orElseThrow();
        assertThat(ticket).isEqualTo(new Ticket("g-12", 5, 1, TicketState.MATCHED, clock.peekMs(), 2, "", 150_000, 900, 0, 0));
        assertThat(store.queueIndex(d())).isEmpty();
        assertThat(store.ttlMs(11)).isEqualTo(48_000);
    }

    @Test
    void 整组建票_有一个人冲突就什么都不写_返回名单序第一个冲突者() {
        enqueue(12, 150_000);
        enqueue(13, 150_000);

        OptionalLong conflict = store.createGroup(List.of(new GroupMember(11, "g-11", 1), new GroupMember(13, "g-13", 1), new GroupMember(12, "g-12", 1)),
                5, 1, 0, 54_000, d());

        assertThat(conflict).hasValue(13);
        assertThat(store.ticketOf(11)).as("原子：没有留下部分建成的票").isEmpty();
        assertThat(store.ticketOf(12).orElseThrow().ticketId()).isEqualTo("t-12");
    }

    @Test
    void 整组建票重放_本次票号的票当作已建_不重写不续期() {
        List<GroupMember> members = List.of(new GroupMember(11, "g-11", 1), new GroupMember(12, "g-12", 1));
        store.createGroup(members, 5, 1, 900, 48_000, d());
        clock.advanceSeconds(5);

        assertThat(store.createGroup(members, 5, 1, 900, 48_000, d())).isEmpty();
        assertThat(store.ttlMs(11)).as("没有续期").isEqualTo(43_000);
        assertThatThrownBy(() -> store.createGroup(List.of(new GroupMember(11, "a", 1), new GroupMember(11, "b", 1)), 5, 1, 0, 48_000, d()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================ 自愈

    @Test
    void 自愈ready票_票号一致且仍是ready才删_返回此刻有没有票() {
        store.putTicket(1001, new Ticket("t-r", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        Ticket seen = store.ticketOf(1001).orElseThrow();

        assertThat(store.heal(1001, seen, HealMode.READY, d())).isTrue();
        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.heal(1001, seen, HealMode.READY, d())).as("重放：已经不在了，也算没有票").isTrue();
    }

    @Test
    void 自愈孤儿queued票_队列里找不到这个人才删_在队列里的不删() {
        Ticket orphan = new Ticket("t-o", 3, 0, TicketState.QUEUED, 1, 1, Q.queueKey(), 150_000, 0, 0, 0);
        store.putTicket(1001, orphan, QUEUED_TTL);
        enqueue(1002, 150_000);
        Ticket alive = store.ticketOf(1002).orElseThrow();

        assertThat(store.heal(1001, orphan, HealMode.ORPHAN, d())).isTrue();
        assertThat(store.heal(1002, alive, HealMode.ORPHAN, d())).as("在队列里：在途").isFalse();

        assertThat(store.ticketOf(1001)).isEmpty();
        assertThat(store.ticketOf(1002)).isPresent();
        assertThat(store.queueMembers(Q)).containsExactly("1002");
    }

    @Test
    void 自愈_票号已换或状态已变_不删() {
        enqueue(1001, 150_000);
        Ticket stale = new Ticket("t-old", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0);
        Ticket queuedSeen = store.ticketOf(1001).orElseThrow();
        store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d());

        assertThat(store.heal(1001, stale, HealMode.READY, d())).as("票号不符").isFalse();
        assertThat(store.heal(1001, queuedSeen, HealMode.ORPHAN, d())).as("读到时是 queued，现在已是 matched").isFalse();
        assertThat(store.heal(1001, queuedSeen, HealMode.READY, d())).isFalse();
        assertThat(store.ticketOf(1001)).isPresent();
    }

    // ================================================================ 注册集、深度、锁

    @Test
    void 剔除空队列_非空不动_空了才出注册集() {
        enqueue(1001, 150_000);

        assertThat(store.queueLength(Q, d())).isEqualTo(1);
        assertThat(store.pruneIfEmpty(Q, d())).isFalse();
        assertThat(store.indexed(Q)).isTrue();

        store.cancel(1001, "t-1001", Q, d());
        assertThat(store.queueIndex(d())).as("取消不动注册集：由凑单懒剔除").containsExactly(Q.queueKey());
        assertThat(store.pruneIfEmpty(Q, d())).isTrue();
        assertThat(store.queueIndex(d())).isEmpty();
        assertThat(store.queueLength(Q, d())).isZero();
        assertThat(store.pruneIfEmpty(Q, d())).as("本来就不在").isTrue();
    }

    @Test
    void 凑单锁_抢到的人持有到TTL_按持有者释放() {
        assertThat(store.tryLockQueue(Q, "inst-a", 10_000, d())).isTrue();
        assertThat(store.tryLockQueue(Q, "inst-b", 10_000, d())).isFalse();
        assertThat(store.tryLockQueue(Q, "inst-a", 10_000, d())).as("SET NX：自己再抢也抢不到").isFalse();
        assertThat(store.tryLockQueue(OTHER, "inst-b", 10_000, d())).as("按队列加锁").isTrue();

        store.unlockQueue(Q, "inst-b", d());
        assertThat(store.lockHolder(Q)).as("不是持有者，放不掉").contains("inst-a");
        store.unlockQueue(Q, "inst-a", d());
        assertThat(store.lockHolder(Q)).isEmpty();

        store.tryLockQueue(Q, "inst-a", 10_000, d());
        clock.advanceMs(10_000);
        assertThat(store.tryLockQueue(Q, "inst-b", 10_000, d())).as("过期后别人可以抢").isTrue();
        store.unlockQueue(Q, "inst-a", d());
        assertThat(store.lockHolder(Q)).as("旧持有者迟到的释放不影响新持有者").contains("inst-b");
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
        enqueue(1001, 150_000);
        store.faults.failNext("pop:after");

        assertThatThrownBy(() -> store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(1001).orElseThrow().state()).as("其实已经弹出").isEqualTo(TicketState.MATCHED);
        assertThat(store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d())).isInstanceOf(PopResult.Replayed.class);
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
        enqueue(1001, 150_000);
        store.snapshot(Q, 256, d());
        store.faults.failNext("delete");
        assertThatThrownBy(() -> store.delete(ref(1001), d())).isInstanceOf(Deadline.DependencyException.class);
        store.pop(Q, "pop-1", List.of(ref(1001)), MATCHED_TTL, d());

        assertThat(store.calls).containsExactly("enqueue(1001)", "snapshot(3:0)", "delete(1001)", "pop(3:0,[1001])");
    }

    @Test
    void 参数不合法是调用方的错() {
        assertThatThrownBy(() -> store.enqueue(0, "t", Q, 1, 0, QUEUED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.enqueue(1, "", Q, 1, 0, QUEUED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.enqueue(1, "t", Q, 1, 0, 0, d())).as("TTL 必须 ≥ 1 ms").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pop(Q, "", List.of(ref(1)), MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pop(Q, "p", List.of(), MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pop(Q, "p", List.of(ref(1), ref(1)), MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.markReady(ref(1), 0, 60_000, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.requeueFront(Q, List.of(), QUEUED_TTL, -1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.snapshot(Q, 0, d())).isInstanceOf(IllegalArgumentException.class);
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
