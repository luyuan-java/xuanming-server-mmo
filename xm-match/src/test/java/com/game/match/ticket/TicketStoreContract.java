package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.ticket.TicketStore.DropReason;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.match.ticket.TicketStore.HealMode;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.match.ticket.TicketStore.QueueSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@link TicketStore} 的契约测试：接口注释里的每一条承诺各钉一例（不变量 I1–I5、每个写的 CAS 条件、<b>每个写方法连调两次第二次什么都不改</b>）。
 * 内存替身（{@code InMemoryTicketStoreTest}）与 Redis 实现（{@code RedissonTicketStoreIntegrationTest}）各继承一份、跑同一套断言——
 * 别的包的组件测试都建在内存替身上，替身与真实现不许漂移。
 *
 * <p>用例不依赖「拨时钟」（真 Redis 的 {@code TIME} 拨不动）：时刻断言夹在调用前后各读一次存储时间之间；TTL 断言留 {@link #TTL_SLACK} 的余量；
 * 「过期之后」用钩子把对应的键直接弄没（{@link #expireTicket} 等），「退避到点」改写票里的 {@code not_before_ms}。
 * 玩家号、队列、弹组 token 都经钩子分配（{@link #p} / {@link #q} / {@link #tok}）：Redis 版每个用例用随机的一段，互不相扰、用完只删自己的键。
 */
public abstract class TicketStoreContract {

    protected static final long QUEUED_TTL = 21_600_000;
    protected static final long MATCHED_TTL = 48_000;
    protected static final long READY_TTL = 60_000;
    /** TTL 断言的余量：真 Redis 上从写入到读回会流逝一点时间。 */
    protected static final long TTL_SLACK = 15_000;

    private final Set<Long> pids = new LinkedHashSet<>();
    private final Set<QueueRef> queues = new LinkedHashSet<>();
    private int requeueSeq;

    // ================================================================ 钩子（两种实现各自提供）

    protected abstract TicketStore store();

    /** 本用例的第 n 号玩家（非 0；同一个 n 返回同一个号）。 */
    protected abstract long pid(int n);

    /** 本用例的第 n 条队列（同一个 n 返回同一条）。 */
    protected abstract QueueRef queue(int n);

    /** 本用例的一个弹组 token（同名同值）。 */
    protected abstract String token(String name);

    /** 存储的时间（Redis {@code TIME} / 手拨时钟），Unix 毫秒。 */
    protected abstract long nowMs();

    /** 绕过存储的写口直接放一张票（覆盖已有的），不碰队列。 */
    protected abstract void putTicket(long playerId, Ticket ticket, long ttlMs);

    /** 直接往队尾放一个成员并登记注册集；{@code ratingCenti} 为 null 时不写评分镜像。 */
    protected abstract void putQueueMember(QueueRef queue, String member, Long ratingCenti);

    protected abstract Optional<Ticket> ticketOf(long playerId);

    /** 票据剩余的 TTL（毫秒）；没有票为 -1。 */
    protected abstract long ttlMs(long playerId);

    protected abstract List<String> queueMembers(QueueRef queue);

    protected abstract Map<String, Long> rankOf(QueueRef queue);

    protected abstract boolean indexed(QueueRef queue);

    protected abstract Optional<String> lockHolder(QueueRef queue);

    /** 让一张票立即过期（队列项不动）。 */
    protected abstract void expireTicket(long playerId);

    protected abstract void expirePopMarker(String popToken);

    protected abstract void expireRequeueMarker(String requeueToken);

    protected abstract void expireLock(QueueRef queue);

    /**
     * 本用例碰过的全部数据的完整内容（不含 TTL）：重放用例拿它比「第二次执行什么都没改」。缺省按上面的读钩子拼；Redis 版另加逐键的原始字节。
     */
    protected Object fingerprint() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (long playerId : pids) {
            out.put("ticket:" + u(playerId), ticketOf(playerId));
        }
        for (QueueRef queue : queues) {
            out.put("queue:" + queue, queueMembers(queue));
            out.put("rank:" + queue, new TreeMap<>(rankOf(queue)));
            out.put("index:" + queue, indexed(queue));
            out.put("lock:" + queue, lockHolder(queue));
        }
        return out;
    }

    // ================================================================ 小工具

    protected final long p(int n) {
        long playerId = pid(n);
        pids.add(playerId);
        return playerId;
    }

    protected final QueueRef q(int n) {
        QueueRef queue = queue(n);
        queues.add(queue);
        return queue;
    }

    protected final String tok(String name) {
        return token(name);
    }

    /** 本用例的一个回队首 token（同名同值）。缺省与弹组 token 同一个分配口；Redis 版另行登记（只清它的重放标记）。 */
    protected String requeueToken(String name) {
        return token(name);
    }

    /** 一个新的回队首 token（每次调用都不同）：不测重放的用例每次回队首用一个新的；测重放的用例先存进变量再用两次。 */
    protected final String rq() {
        return requeueToken("rq-" + (++requeueSeq));
    }

    protected static Deadline d() {
        return Deadline.after(10_000);
    }

    protected static String u(long playerId) {
        return Long.toUnsignedString(playerId);
    }

    /** 这名玩家在用例里的缺省票号。 */
    protected static String tid(long playerId) {
        return "t-" + u(playerId);
    }

    protected static TicketRef ref(long playerId) {
        return new TicketRef(playerId, tid(playerId));
    }

    /** 入队并断言建成；返回写进票里的入队时刻。 */
    protected final long enqueue(long playerId, QueueRef queue, long ratingCenti) {
        JoinResult result = store().enqueue(playerId, tid(playerId), queue, 1, ratingCenti, QUEUED_TTL, d());
        assertThat(result).isInstanceOf(JoinResult.Created.class);
        return ((JoinResult.Created) result).enqueuedAtMs();
    }

    protected final void assertTtl(long playerId, long expectedMs) {
        assertThat(ttlMs(playerId)).as("票据 TTL player=%s", u(playerId)).isBetween(expectedMs - TTL_SLACK, expectedMs);
    }

    private Ticket ticket(long playerId) {
        return ticketOf(playerId).orElseThrow(() -> new AssertionError("没有票 player=" + u(playerId)));
    }

    private TicketState stateOf(long playerId) {
        return ticket(playerId).state();
    }

    // ================================================================ 入队、读、状态

    @Test
    public void 入队_建queued票_登记注册集_写评分镜像_入队尾_时间取存储的时间() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);

        long before = nowMs();
        JoinResult first = store().enqueue(a, tid(a), q, 2, 162_500, QUEUED_TTL, d());
        long after = nowMs();
        enqueue(b, q, 150_000);

        assertThat(first).isInstanceOf(JoinResult.Created.class);
        long enqueuedAt = ((JoinResult.Created) first).enqueuedAtMs();
        assertThat(enqueuedAt).as("入队时刻取存储的时间，不收调用方的").isBetween(before, after);
        assertThat(queueMembers(q)).as("等得最久的在队首").containsExactly(u(a), u(b));
        assertThat(rankOf(q)).as("I2：镜像与队列成员一致").containsOnlyKeys(u(a), u(b)).containsEntry(u(a), 162_500L).containsEntry(u(b), 150_000L);
        assertThat(indexed(q)).as("I1：非空队列一定在注册集里").isTrue();
        assertThat(store().read(a, d())).contains(new Ticket(tid(a), q.mode(), q.configId(), TicketState.QUEUED, enqueuedAt, 2, q.queueKey(),
                162_500, 0, 0, 0));
        assertTtl(a, QUEUED_TTL);
    }

    @Test
    public void 已有别的票_什么都不写_回现有票号() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long a = p(1);
        enqueue(a, q, 150_000);
        Object before = fingerprint();

        JoinResult second = store().enqueue(a, "t-new", other, 1, 150_000, QUEUED_TTL, d());
        JoinResult solo = store().createMatched(a, "t-solo", 4, 1, 1, 150_000, 42_000, d());

        assertThat(second).isEqualTo(new JoinResult.Exists(tid(a)));
        assertThat(solo).isEqualTo(new JoinResult.Exists(tid(a)));
        assertThat(queueMembers(other)).isEmpty();
        assertThat(indexed(other)).isFalse();
        assertThat(ticket(a).ticketId()).as("I3：每人至多一张票").isEqualTo(tid(a));
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    public void 不入队直接建matched票_队列键为空_不碰任何队列() {
        QueueRef q = q(1);
        long a = p(1);

        long before = nowMs();
        JoinResult result = store().createMatched(a, "t-solo", 4, 1, 2, 150_000, 42_000, d());
        long after = nowMs();

        assertThat(result).isInstanceOf(JoinResult.Created.class);
        long enqueuedAt = ((JoinResult.Created) result).enqueuedAtMs();
        assertThat(enqueuedAt).isBetween(before, after);
        assertThat(ticket(a)).isEqualTo(new Ticket("t-solo", 4, 1, TicketState.MATCHED, enqueuedAt, 2, "", 150_000, 0, 0, 0));
        assertThat(queueMembers(q)).isEmpty();
        assertThat(indexed(q)).isFalse();
        assertTtl(a, 42_000);
    }

    @Test
    public void 票据到期后读不到_队列里的残留项照旧留着_重排后队列里是两份() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);

        expireTicket(a);

        assertThat(store().read(a, d())).isEmpty();
        assertThat(store().status(a, d()).ticket()).isEmpty();
        assertThat(queueMembers(q)).as("残留项由凑单的校验剔除").containsExactly(u(a));
        assertThat(store().enqueue(a, "t-again", q, 1, 150_000, QUEUED_TTL, d())).as("过期之后可以重排").isInstanceOf(JoinResult.Created.class);
        assertThat(queueMembers(q)).as("入队不清旧项（同基线）：重复项由凑单按玩家号去重、弹组时一并摘掉").containsExactly(u(a), u(a));
    }

    @Test
    public void 查状态_票据与同一时刻的存储时间() {
        QueueRef q = q(1);
        long a = p(1);
        long absent = p(2);
        long enqueuedAt = enqueue(a, q, 150_000);

        long before = nowMs();
        TicketReader.Status status = store().status(a, d());
        TicketReader.Status none = store().status(absent, d());
        long after = nowMs();

        assertThat(status.ticket().orElseThrow().enqueuedAtMs()).isEqualTo(enqueuedAt);
        assertThat(status.redisNowMs()).isBetween(before, after).isGreaterThanOrEqualTo(enqueuedAt);
        assertThat(none.ticket()).isEmpty();
        assertThat(none.redisNowMs()).as("没有票也带回时间").isBetween(before, after);
    }

    @Test
    public void 批量读_只含有票的人_重复的玩家号只算一次() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        enqueue(a, q, 150_000);
        store().createMatched(c, tid(c), 4, 1, 1, 150_000, 42_000, d());

        Map<Long, Ticket> all = store().readAll(List.of(a, b, c, a), d());

        assertThat(all).containsOnlyKeys(a, c);
        assertThat(all.get(a)).isEqualTo(ticket(a));
        assertThat(all.get(c).state()).isEqualTo(TicketState.MATCHED);
        assertThat(store().readAll(List.of(), d())).isEmpty();
        assertThat(store().readAll(List.of(b), d())).isEmpty();
    }

    // ================================================================ 取消

    @Test
    public void 取消_票号一致且仍是queued_删票并摘出队列与镜像() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);

        assertThat(store().cancel(a, tid(a), q, d())).isTrue();

        assertThat(ticketOf(a)).isEmpty();
        assertThat(queueMembers(q)).containsExactly(u(b));
        assertThat(rankOf(q)).as("I2：镜像与队列成员一致").containsOnlyKeys(u(b));
        assertThat(indexed(q)).as("取消不动注册集：由凑单懒剔除").isTrue();
    }

    @Test
    public void 取消太迟_票号不符_队列不符_已被弹走_什么都不动() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        Object before = fingerprint();

        assertThat(store().cancel(a, "t-stale", q, d())).as("票号不符").isFalse();
        assertThat(store().cancel(a, tid(a), other, d())).as("队列不符").isFalse();
        assertThat(fingerprint()).isEqualTo(before);

        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        Object popped = fingerprint();
        assertThat(store().cancel(a, tid(a), q, d())).as("I5：matched 之后取消太迟").isFalse();
        assertThat(stateOf(a)).isEqualTo(TicketState.MATCHED);
        assertThat(fingerprint()).isEqualTo(popped);
    }

    @Test
    public void 取消_把同一玩家在队列里的重复项一并摘掉() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        putQueueMember(q, u(a), 150_000L);
        enqueue(b, q, 150_000);
        enqueue(a, q, 150_000);

        assertThat(store().cancel(a, tid(a), q, d())).isTrue();

        assertThat(queueMembers(q)).containsExactly(u(b));
    }

    // ================================================================ 快照、剔除

    @Test
    public void 快照_等待序_镜像分_非法成员与缺分都原样暴露() {
        QueueRef q = q(1);
        QueueRef empty = q(2);
        long a = p(1);
        long b = p(2);
        long c = p(5);
        enqueue(a, q, 162_500);
        putQueueMember(q, "abc", null);
        putQueueMember(q, "0", null);
        putQueueMember(q, u(c), null);
        enqueue(b, q, 140_000);
        putQueueMember(q, u(a), 162_500L);

        long before = nowMs();
        QueueSnapshot all = store().snapshot(q, 256, d());
        long after = nowMs();
        QueueSnapshot prefix = store().snapshot(q, 2, d());

        assertThat(all.redisNowMs()).isBetween(before, after);
        assertThat(all.entries()).extracting(e -> e.member() + "/" + u(e.playerId()) + "/" + e.ratingCenti())
                .as("队首在前；非法成员的玩家号是 0；镜像缺分为空；同一玩家的重复项原样保留")
                .containsExactly(u(a) + "/" + u(a) + "/OptionalLong[162500]", "abc/0/OptionalLong.empty", "0/0/OptionalLong.empty",
                        u(c) + "/" + u(c) + "/OptionalLong.empty", u(b) + "/" + u(b) + "/OptionalLong[140000]",
                        u(a) + "/" + u(a) + "/OptionalLong[162500]");
        assertThat(prefix.entries()).extracting(TicketStore.SnapshotEntry::member).containsExactly(u(a), "abc");
        QueueSnapshot none = store().snapshot(empty, 256, d());
        assertThat(none.entries()).isEmpty();
        assertThat(none.redisNowMs()).as("空队列也带回时间").isGreaterThanOrEqualTo(before);
    }

    @Test
    public void 剔除非法成员_只动队列_不碰票据() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        putQueueMember(q, "abc", 1L);
        putQueueMember(q, "abc", 1L);

        assertThat(store().dropMalformed(q, "abc", d())).isTrue();

        assertThat(queueMembers(q)).containsExactly(u(a));
        assertThat(rankOf(q)).containsOnlyKeys(u(a));
        assertThat(ticketOf(a)).isPresent();
        assertThat(store().dropMalformed(q, "abc", d())).as("已经没有了").isFalse();
    }

    @Test
    public void 剔除_票据缺失或状态不对或不属于这条队列的成员_任何原因都只摘队列项() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long noTicket = p(1);
        long matched = p(2);
        long elsewhere = p(3);
        putQueueMember(q, u(noTicket), 150_000L);
        enqueue(matched, q, 150_000);
        putTicket(matched, new Ticket(tid(matched), 3, q.configId(), TicketState.MATCHED, 1, 1, q.queueKey(), 150_000, 0, 0, 0), MATCHED_TTL);
        enqueue(elsewhere, other, 150_000);
        putQueueMember(q, u(elsewhere), 150_000L);

        assertThat(store().drop(q, noTicket, DropReason.INVALID, null, d())).isTrue();
        assertThat(store().drop(q, matched, DropReason.INVALID, tid(matched), d())).isTrue();
        assertThat(store().drop(q, elsewhere, DropReason.OFFLINE, tid(elsewhere), d())).as("他的票在别的队列里：这里只摘残留项").isTrue();

        assertThat(queueMembers(q)).isEmpty();
        assertThat(rankOf(q)).isEmpty();
        assertThat(stateOf(matched)).as("不是 queued 的票不动").isEqualTo(TicketState.MATCHED);
        assertThat(stateOf(elsewhere)).as("别的队列里的票不动").isEqualTo(TicketState.QUEUED);
        assertThat(queueMembers(other)).containsExactly(u(elsewhere));
    }

    @Test
    public void 剔除_有战斗锁或已离线_票号一致才删票() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);

        assertThat(store().drop(q, a, DropReason.IN_BATTLE, tid(a), d())).isTrue();
        assertThat(store().drop(q, b, DropReason.OFFLINE, tid(b), d())).isTrue();

        assertThat(ticketOf(a)).isEmpty();
        assertThat(ticketOf(b)).isEmpty();
        assertThat(queueMembers(q)).containsExactly(u(c));
        assertThat(rankOf(q)).containsOnlyKeys(u(c));
    }

    @Test
    public void 剔除_不误伤期间重新入队的同一玩家() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        Object before = fingerprint();

        // 凑单读到的是 t-old；之后玩家取消并重新排了一次（现在是 tid(a)）
        assertThat(store().drop(q, a, DropReason.IN_BATTLE, "t-old", d())).isFalse();
        assertThat(store().drop(q, a, DropReason.OFFLINE, null, d())).as("没读到票号").isFalse();
        assertThat(store().drop(q, a, DropReason.INVALID, tid(a), d())).as("他现在是一张有效的 queued 票").isFalse();

        assertThat(fingerprint()).isEqualTo(before);
    }

    // ================================================================ 弹组

    @Test
    public void 弹组_全员满足才一次性摘出并置matched() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        long enqueuedAt = enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);

        PopResult result = store().pop(q, tok("pop-1"), List.of(ref(c), ref(a)), MATCHED_TTL, d());

        assertThat(result).isInstanceOf(PopResult.Popped.class);
        assertThat(queueMembers(q)).containsExactly(u(b));
        assertThat(rankOf(q)).containsOnlyKeys(u(b));
        assertThat(stateOf(a)).isEqualTo(TicketState.MATCHED);
        assertThat(stateOf(c)).isEqualTo(TicketState.MATCHED);
        assertThat(stateOf(b)).isEqualTo(TicketState.QUEUED);
        assertTtl(a, MATCHED_TTL);
        assertTtl(c, MATCHED_TTL);
        assertTtl(b, QUEUED_TTL);
        assertThat(ticket(a).enqueuedAtMs()).as("入队时刻不变").isEqualTo(enqueuedAt);
        assertThat(ticket(a).queueKey()).as("队列键留在票上：回队首按它核对").isEqualTo(q.queueKey());
    }

    @Test
    public void 弹组_有人不满足_什么都不写_返回不满足的人() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        long stranger = p(4);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);
        store().cancel(b, tid(b), q, d());
        String popToken = tok("pop-1"); // 先分配：指纹里包含这个 token 的重放标记（此刻不存在）
        Object before = fingerprint();

        PopResult result = store().pop(q, popToken, List.of(ref(a), ref(b), new TicketRef(c, "t-stale"), ref(stranger)), MATCHED_TTL, d());

        assertThat(result).as("按入参顺序").isEqualTo(new PopResult.Invalid(List.of(b, c, stranger)));
        assertThat(fingerprint()).as("全有全无：a 没有离开原位，也没有留下重放标记").isEqualTo(before);
        assertThat(queueMembers(q)).containsExactly(u(a), u(c));
        assertTtl(a, QUEUED_TTL);
        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a), ref(c)), MATCHED_TTL, d())).as("失败的弹组不留重放标记").isInstanceOf(PopResult.Popped.class);
    }

    @Test
    public void 弹组_票在但人不在队列里_票属于别的队列_票不是queued_都算不满足() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long orphan = p(1);
        long elsewhere = p(2);
        long matched = p(3);
        putTicket(orphan, new Ticket(tid(orphan), 3, q.configId(), TicketState.QUEUED, 1, 1, q.queueKey(), 150_000, 0, 0, 0), QUEUED_TTL);
        enqueue(elsewhere, other, 150_000);
        putQueueMember(q, u(elsewhere), 150_000L);
        putTicket(matched, new Ticket(tid(matched), 3, q.configId(), TicketState.MATCHED, 1, 1, q.queueKey(), 150_000, 0, 0, 0), MATCHED_TTL);
        putQueueMember(q, u(matched), 150_000L);

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(orphan), ref(elsewhere), ref(matched)), MATCHED_TTL, d()))
                .isEqualTo(new PopResult.Invalid(List.of(orphan, elsewhere, matched)));
    }

    @Test
    public void 弹组_把同一玩家在队列里的重复项一并摘掉() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        putQueueMember(q, u(a), 150_000L);
        enqueue(b, q, 150_000);

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);

        assertThat(queueMembers(q)).as("多余的一份被丢弃，队列弹空").isEmpty();
        assertThat(rankOf(q)).isEmpty();
    }

    @Test
    public void 弹组的重放标记失效之后按现状重新核对_不会双弹() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());

        expirePopMarker(tok("pop-1"));
        Object popped = fingerprint();

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d())).as("他已经不是 queued").isEqualTo(new PopResult.Invalid(List.of(a)));
        assertThat(fingerprint()).as("失败的弹组什么都不写，标记也不会被重新写出来").isEqualTo(popped);
        assertThat(stateOf(a)).isEqualTo(TicketState.MATCHED);
    }

    // ================================================================ gather 的写

    @Test
    public void 置ready_要求票号一致且是matched_写battle_id与新TTL() {
        QueueRef q = q(1);
        long a = p(1);
        long none = p(2);
        long battleId = Long.MIN_VALUE + 77; // 雪花号可以 ≥ 2^63：按无符号十进制存取
        enqueue(a, q, 150_000);
        assertThat(store().markReady(ref(a), battleId, READY_TTL, d())).as("还是 queued").isFalse();
        assertThat(stateOf(a)).isEqualTo(TicketState.QUEUED);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());

        assertThat(store().markReady(new TicketRef(a, "t-stale"), battleId, READY_TTL, d())).as("I4：票号不符不写").isFalse();
        assertThat(stateOf(a)).isEqualTo(TicketState.MATCHED);
        assertThat(store().markReady(ref(a), battleId, READY_TTL, d())).isTrue();

        assertThat(stateOf(a)).isEqualTo(TicketState.READY);
        assertThat(ticket(a).battleId()).isEqualTo(battleId);
        assertTtl(a, READY_TTL);
        assertThat(store().markReady(ref(a), battleId + 1, READY_TTL, d())).as("别的 battle_id").isFalse();
        assertThat(ticket(a).battleId()).isEqualTo(battleId);
        assertThat(store().markReady(ref(none), battleId, READY_TTL, d())).as("没有票").isFalse();
        assertThat(ticketOf(none)).as("不会凭空造出一张票").isEmpty();
    }

    @Test
    public void 续期_只续票号一致且仍是matched的票_照设可以比原来短() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        long none = p(4);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());

        int extended = store().extendMatched(List.of(ref(a), new TicketRef(b, "t-stale"), ref(c), ref(none)), 16_000, d());

        assertThat(extended).isEqualTo(1);
        assertThat(ttlMs(a)).as("照设：可以比原来短").isBetween(16_000 - TTL_SLACK, 16_000L);
        assertThat(ttlMs(b)).as("票号不符的不续").isGreaterThan(16_000L);
        assertTtl(c, QUEUED_TTL);
        assertThat(stateOf(a)).as("续期不改状态").isEqualTo(TicketState.MATCHED);
        assertThat(ticketOf(none)).isEmpty();
        assertThat(store().extendMatched(List.of(), 16_000, d())).isZero();
    }

    @Test
    public void 删票_票号一致就删_不看状态_不摘队列项() {
        QueueRef q = q(1);
        long a = p(1);
        long solo = p(2);
        enqueue(a, q, 150_000);
        store().createMatched(solo, tid(solo), 4, 1, 1, 150_000, 42_000, d());

        assertThat(store().delete(new TicketRef(a, "t-stale"), d())).isFalse();
        assertThat(ticketOf(a)).isPresent();
        assertThat(store().delete(ref(a), d())).isTrue();
        assertThat(store().delete(ref(solo), d())).isTrue();

        assertThat(ticketOf(a)).isEmpty();
        assertThat(ticketOf(solo)).isEmpty();
        assertThat(queueMembers(q)).as("残留项留给凑单的校验").containsExactly(u(a));
    }

    @Test
    public void 删一组票_逐张票号一致才删() {
        long a = p(1);
        long b = p(2);
        long c = p(3);
        long none = p(4);
        store().createGroup(List.of(new GroupMember(a, "g-1", 1), new GroupMember(b, "g-2", 1), new GroupMember(c, "g-3", 1)), 5, 1, 900, 66_000, d());

        int deleted = store().deleteGroup(List.of(new TicketRef(a, "g-1"), new TicketRef(b, "other"), new TicketRef(c, "g-3"), new TicketRef(none, "g-4")),
                d());

        assertThat(deleted).isEqualTo(2);
        assertThat(ticketOf(a)).isEmpty();
        assertThat(ticketOf(b)).isPresent();
        assertThat(ticketOf(c)).isEmpty();
        assertThat(store().deleteGroup(List.of(), d())).isZero();
    }

    // ================================================================ 回队首

    @Test
    public void 回队首_幸存者按原相对顺序排在原有成员之前_恢复queued与长TTL_入队时刻不变() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        long late = p(4);
        long enqueuedAt = enqueue(a, q, 162_500);
        enqueue(b, q, 150_000);
        enqueue(c, q, 140_000);
        enqueue(late, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b), ref(c)), MATCHED_TTL, d());

        int requeued = store().requeueFront(q, rq(), List.of(ref(a), ref(c)), QUEUED_TTL, 0, d());

        assertThat(requeued).isEqualTo(2);
        assertThat(queueMembers(q)).containsExactly(u(a), u(c), u(late));
        assertThat(rankOf(q)).as("按票里的评分写回镜像").containsOnlyKeys(u(a), u(c), u(late)).containsEntry(u(a), 162_500L).containsEntry(u(c), 140_000L);
        Ticket back = ticket(a);
        assertThat(back.state()).isEqualTo(TicketState.QUEUED);
        assertThat(back.enqueuedAtMs()).as("等待时长从第一次入队算").isEqualTo(enqueuedAt);
        assertThat(back.notBeforeMs()).isZero();
        assertTtl(a, QUEUED_TTL);
        assertThat(ttlMs(a)).as("恢复成比 matched 长的寿命").isGreaterThan(MATCHED_TTL);
        assertThat(stateOf(b)).as("没在幸存者名单里的人不动").isEqualTo(TicketState.MATCHED);
        assertThat(indexed(q)).isTrue();
    }

    @Test
    public void 回队首_注册集被剔除之后重新登记() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        assertThat(store().pruneIfEmpty(q, d())).as("弹空之后被别的实例懒剔除").isTrue();
        assertThat(indexed(q)).isFalse();

        assertThat(store().requeueFront(q, rq(), List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);

        assertThat(indexed(q)).as("I1：回队首必须重新登记注册集").isTrue();
        assertThat(queueMembers(q)).containsExactly(u(a));
    }

    @Test
    public void 回队首_票已过期_已换_不属于这条队列的人跳过_其余照回() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long a = p(1);
        long gone = p(2);
        long replaced = p(3);
        long elsewhere = p(4);
        enqueue(a, q, 150_000);
        enqueue(gone, q, 150_000);
        enqueue(replaced, q, 150_000);
        enqueue(elsewhere, other, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(gone), ref(replaced)), MATCHED_TTL, d());
        store().pop(other, tok("pop-2"), List.of(ref(elsewhere)), MATCHED_TTL, d());
        store().delete(ref(gone), d());

        int requeued = store().requeueFront(q, rq(), List.of(ref(a), ref(gone), new TicketRef(replaced, "t-stale"), ref(elsewhere)), QUEUED_TTL, 0, d());

        assertThat(requeued).isEqualTo(1);
        assertThat(queueMembers(q)).containsExactly(u(a));
        assertThat(ticketOf(gone)).as("不会凭空造出一张票").isEmpty();
        assertThat(stateOf(replaced)).isEqualTo(TicketState.MATCHED);
        assertThat(stateOf(elsewhere)).as("票的队列键不是这条队列：不动").isEqualTo(TicketState.MATCHED);
        assertThat(queueMembers(other)).isEmpty();
        assertThat(store().requeueFront(q, rq(), List.of(), QUEUED_TTL, 0, d())).as("空名单什么都不做").isZero();
    }

    @Test
    public void 回队首带退避_到点之前弹不出来_但不会被当成无效成员剔除_到点之后照常() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());

        long before = nowMs();
        assertThat(store().requeueFront(q, rq(), List.of(ref(a)), QUEUED_TTL, 600_000, d())).isEqualTo(1);
        long after = nowMs();

        Ticket backing = ticket(a);
        assertThat(backing.notBeforeMs()).as("退避到点时刻 = 存储的时间 + 退避").isBetween(before + 600_000, after + 600_000);
        assertThat(backing.backingOff(nowMs())).isTrue();
        assertThat(store().pop(q, tok("pop-2"), List.of(ref(a)), MATCHED_TTL, d())).isEqualTo(new PopResult.Invalid(List.of(a)));
        assertThat(store().drop(q, a, DropReason.INVALID, tid(a), d())).as("只是退避没到：不剔除").isFalse();
        assertThat(queueMembers(q)).containsExactly(u(a));

        // 到点：把票里的 not_before_ms 改到过去（真 Redis 拨不动时钟）
        putTicket(a, new Ticket(backing.ticketId(), backing.mode(), backing.configId(), backing.state(), backing.enqueuedAtMs(), backing.zoneId(),
                backing.queueKey(), backing.ratingCenti(), backing.teamId(), backing.battleId(), nowMs() - 1), QUEUED_TTL);
        assertThat(store().pop(q, tok("pop-3"), List.of(ref(a)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);

        // 再回一次、不带退避：清掉 not_before_ms
        assertThat(store().requeueFront(q, rq(), List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);
        assertThat(ticket(a).notBeforeMs()).isZero();
        assertThat(store().pop(q, tok("pop-4"), List.of(ref(a)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
    }

    @Test
    public void 不入队的票回不了队首() {
        QueueRef q = q(1);
        long a = p(1);
        store().createMatched(a, tid(a), 4, 1, 1, 150_000, 42_000, d());
        Object before = fingerprint();

        assertThat(store().requeueFront(q, rq(), List.of(ref(a)), QUEUED_TTL, 0, d())).isZero();

        assertThat(fingerprint()).isEqualTo(before);
        assertThat(indexed(q)).as("没有人回去就不登记注册集").isFalse();
    }

    @Test
    public void 回队首之后票是queued就一定在队列里_再弹正常成组() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        store().requeueFront(q, rq(), List.of(ref(a), ref(b)), QUEUED_TTL, 0, d());

        for (long playerId : List.of(a, b)) {
            assertThat(stateOf(playerId)).isEqualTo(TicketState.QUEUED);
            assertThat(queueMembers(q)).as("不留孤儿").contains(u(playerId));
        }
        assertThat(store().pop(q, tok("pop-2"), List.of(ref(a), ref(b)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
        assertThat(queueMembers(q)).isEmpty();
    }

    // ================================================================ 整组建票

    @Test
    public void 整组建票_全员没有票才建_带队伍号_不入队() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long teamId = Long.MIN_VALUE + 900;

        long before = nowMs();
        OptionalLong conflict = store().createGroup(List.of(new GroupMember(a, "g-a", 1), new GroupMember(b, "g-b", 2)), 5, 1, teamId, 48_000, d());
        long after = nowMs();

        assertThat(conflict).isEmpty();
        Ticket ticket = ticket(b);
        assertThat(ticket.enqueuedAtMs()).isBetween(before, after);
        assertThat(ticket).isEqualTo(new Ticket("g-b", 5, 1, TicketState.MATCHED, ticket.enqueuedAtMs(), 2, "", 150_000, teamId, 0, 0));
        assertThat(ticket(a).zoneId()).isEqualTo(1);
        assertThat(queueMembers(q)).isEmpty();
        assertTtl(a, 48_000);
        assertTtl(b, 48_000);
    }

    @Test
    public void 整组建票_不带队伍号时队伍号为0() {
        long a = p(1);

        assertThat(store().createGroup(List.of(new GroupMember(a, "g-a", 0)), 5, 1, 0, 42_000, d())).isEmpty();

        assertThat(ticket(a).teamId()).isZero();
        assertThat(ticket(a).zoneId()).isZero();
        assertThat(store().createGroup(List.of(), 5, 1, 0, 42_000, d())).as("空名单").isEmpty();
    }

    @Test
    public void 整组建票_有一个人冲突就什么都不写_返回名单序第一个冲突者() {
        QueueRef q = q(1);
        long fresh = p(1);
        long first = p(3);
        long second = p(2);
        enqueue(second, q, 150_000);
        enqueue(first, q, 150_000);
        Object before = fingerprint();

        OptionalLong conflict = store().createGroup(
                List.of(new GroupMember(fresh, "g-1", 1), new GroupMember(first, "g-3", 1), new GroupMember(second, "g-2", 1)), 5, 1, 0, 54_000, d());

        assertThat(conflict).hasValue(first);
        assertThat(ticketOf(fresh)).as("原子：没有留下部分建成的票").isEmpty();
        assertThat(fingerprint()).isEqualTo(before);
    }

    // ================================================================ 自愈

    @Test
    public void 自愈ready票_票号一致且仍是ready才删() {
        long a = p(1);
        putTicket(a, new Ticket("t-r", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), READY_TTL);
        Ticket seen = ticket(a);

        assertThat(store().heal(a, seen, HealMode.READY, d())).isTrue();

        assertThat(ticketOf(a)).isEmpty();
    }

    @Test
    public void 自愈孤儿queued票_队列里找不到这个人才删_在队列里的不删() {
        QueueRef q = q(1);
        long orphanPlayer = p(1);
        long alivePlayer = p(2);
        Ticket orphan = new Ticket("t-o", 3, q.configId(), TicketState.QUEUED, 1, 1, q.queueKey(), 150_000, 0, 0, 0);
        putTicket(orphanPlayer, orphan, QUEUED_TTL);
        enqueue(alivePlayer, q, 150_000);
        Ticket alive = ticket(alivePlayer);

        assertThat(store().heal(orphanPlayer, orphan, HealMode.ORPHAN, d())).isTrue();
        assertThat(store().heal(alivePlayer, alive, HealMode.ORPHAN, d())).as("在队列里：在途").isFalse();

        assertThat(ticketOf(orphanPlayer)).isEmpty();
        assertThat(ticketOf(alivePlayer)).isPresent();
        assertThat(queueMembers(q)).containsExactly(u(alivePlayer));
    }

    @Test
    public void 自愈_队列键不是规范形的queued票一律算孤儿() {
        long a = p(1);
        long b = p(2);
        Ticket emptyKey = new Ticket("t-e", 3, 0, TicketState.QUEUED, 1, 1, "", 150_000, 0, 0, 0);
        Ticket junkKey = new Ticket("t-j", 3, 0, TicketState.QUEUED, 1, 1, "not-a-queue-key", 150_000, 0, 0, 0);
        putTicket(a, emptyKey, QUEUED_TTL);
        putTicket(b, junkKey, QUEUED_TTL);

        assertThat(store().heal(a, emptyKey, HealMode.ORPHAN, d())).isTrue();
        assertThat(store().heal(b, junkKey, HealMode.ORPHAN, d())).isTrue();

        assertThat(ticketOf(a)).isEmpty();
        assertThat(ticketOf(b)).isEmpty();
    }

    @Test
    public void 自愈_票号已换_状态已变_队列键已变_都不删() {
        QueueRef q = q(1);
        QueueRef other = q(2);
        long a = p(1);
        long moved = p(2);
        enqueue(a, q, 150_000);
        Ticket stale = new Ticket("t-old", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0);
        Ticket queuedSeen = ticket(a);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        // moved：读到时票在 q（孤儿），之后同一票号被改到了 other 队列（人为构造）
        Ticket movedSeen = new Ticket(tid(moved), 3, q.configId(), TicketState.QUEUED, 1, 1, q.queueKey(), 150_000, 0, 0, 0);
        putTicket(moved, new Ticket(tid(moved), 3, other.configId(), TicketState.QUEUED, 1, 1, other.queueKey(), 150_000, 0, 0, 0), QUEUED_TTL);
        Object before = fingerprint();

        assertThat(store().heal(a, stale, HealMode.READY, d())).as("票号不符").isFalse();
        assertThat(store().heal(a, queuedSeen, HealMode.ORPHAN, d())).as("读到时是 queued，现在已是 matched").isFalse();
        assertThat(store().heal(a, queuedSeen, HealMode.READY, d())).as("matched 不是 ready").isFalse();
        assertThat(store().heal(moved, movedSeen, HealMode.ORPHAN, d())).as("队列键和读到的不一样了").isFalse();

        assertThat(fingerprint()).isEqualTo(before);
    }

    // ================================================================ 注册集、深度、锁

    @Test
    public void 剔除空队列_非空不动_空了才出注册集_残留的镜像一并清掉() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);

        assertThat(store().queueLength(q, d())).isEqualTo(1);
        assertThat(store().pruneIfEmpty(q, d())).isFalse();
        assertThat(indexed(q)).isTrue();
        assertThat(store().queueIndex(d())).contains(q.queueKey());

        store().delete(ref(a), d());
        store().dropMalformed(q, u(a), d());
        assertThat(store().queueIndex(d())).as("队列空了也不自动出注册集：由凑单懒剔除").contains(q.queueKey());
        assertThat(store().queueLength(q, d())).isZero();
        assertThat(store().pruneIfEmpty(q, d())).isTrue();
        assertThat(store().queueIndex(d())).doesNotContain(q.queueKey());
        assertThat(rankOf(q)).isEmpty();
    }

    @Test
    public void 剔除空队列之后再入队_重新登记() {
        QueueRef q = q(1);
        long a = p(1);
        putQueueMember(q, "junk", null);
        store().dropMalformed(q, "junk", d());
        assertThat(store().pruneIfEmpty(q, d())).isTrue();
        assertThat(indexed(q)).isFalse();

        enqueue(a, q, 150_000);

        assertThat(indexed(q)).isTrue();
        assertThat(store().pruneIfEmpty(q, d())).as("非空").isFalse();
    }

    @Test
    public void 凑单锁_抢到的人持有到TTL_按持有者释放() {
        QueueRef q = q(1);
        QueueRef other = q(2);

        assertThat(store().tryLockQueue(q, "inst-a", 10_000, d())).isTrue();
        assertThat(store().tryLockQueue(q, "inst-b", 10_000, d())).isFalse();
        assertThat(store().tryLockQueue(q, "inst-a", 10_000, d())).as("SET NX：自己再抢也抢不到").isFalse();
        assertThat(store().tryLockQueue(other, "inst-b", 10_000, d())).as("按队列加锁").isTrue();

        store().unlockQueue(q, "inst-b", d());
        assertThat(lockHolder(q)).as("不是持有者，放不掉").contains("inst-a");
        store().unlockQueue(q, "inst-a", d());
        assertThat(lockHolder(q)).isEmpty();
        assertThat(lockHolder(other)).contains("inst-b");

        store().tryLockQueue(q, "inst-a", 10_000, d());
        expireLock(q);
        assertThat(store().tryLockQueue(q, "inst-b", 10_000, d())).as("过期后别人可以抢").isTrue();
        store().unlockQueue(q, "inst-a", d());
        assertThat(lockHolder(q)).as("旧持有者迟到的释放不影响新持有者").contains("inst-b");
    }

    // ================================================================ 重放：每个写方法连调两次（Redis 客户端超时后重发同一段脚本），第二次什么都不改
    //
    // 「不续期」怎么钉：重发时入参不变，同一个 TTL 值续没续期从读数上看不出来（续了也还是那个数；指纹又不含 TTL）。所以每条都再重放一次、
    // 这次**换一个 TTL**（存储认重放靠的是票号 / token，不看 TTL），票的 TTL 必须仍是首次写入的那个值——重放分支里只要有一句 PEXPIRE 就会变。

    /** 重放时故意传的「另一个 TTL」：比任何一种票的 TTL 减去余量都小，被拿去续期的话一眼看得出来。 */
    private static final long OTHER_TTL = 5_000;

    @Test
    public void 重放_入队_同一票号再执行一次_不会入两次队_也不报已在队列中() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        Object once = fingerprint();
        long ttl = ttlMs(a);

        JoinResult replay = store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d());

        assertThat(replay).as("不是假的「已在队列中」").isInstanceOf(JoinResult.Replayed.class);
        assertThat(queueMembers(q)).containsExactly(u(a));
        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).as("不续期").isLessThanOrEqualTo(ttl);

        assertThat(store().enqueue(a, tid(a), q, 1, 150_000, OTHER_TTL, d())).isInstanceOf(JoinResult.Replayed.class);
        assertTtl(a, QUEUED_TTL);
        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_入队之后已被弹走_重放仍按成功_不会把人塞回队列_也不把matched票续成排队的寿命() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        Object popped = fingerprint();

        assertThat(store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d())).isInstanceOf(JoinResult.Replayed.class);

        assertThat(fingerprint()).isEqualTo(popped);
        assertThat(queueMembers(q)).isEmpty();
        // 重发的入队带的是 6 h：重放分支若顺手续期，这张 matched 票就从 48 s 变成 6 h——gather 所在实例此时崩溃的话，它要挡这名玩家 6 小时
        assertTtl(a, MATCHED_TTL);
    }

    @Test
    public void 重放_建matched票() {
        long a = p(1);
        store().createMatched(a, tid(a), 4, 1, 2, 150_000, 42_000, d());
        Object once = fingerprint();
        long ttl = ttlMs(a);

        assertThat(store().createMatched(a, tid(a), 4, 1, 2, 150_000, 42_000, d())).isInstanceOf(JoinResult.Replayed.class);

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).isLessThanOrEqualTo(ttl);

        assertThat(store().createMatched(a, tid(a), 4, 1, 2, 150_000, OTHER_TTL, d())).isInstanceOf(JoinResult.Replayed.class);
        assertTtl(a, 42_000);
        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_整组建票_本次票号的票当作已建_不重写不续期() {
        long a = p(1);
        long b = p(2);
        List<GroupMember> members = List.of(new GroupMember(a, "g-a", 1), new GroupMember(b, "g-b", 1));
        store().createGroup(members, 5, 1, 900, 48_000, d());
        Object once = fingerprint();
        long ttl = ttlMs(a);

        assertThat(store().createGroup(members, 5, 1, 900, 48_000, d())).isEmpty();

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).as("没有续期").isLessThanOrEqualTo(ttl);

        assertThat(store().createGroup(members, 5, 1, 900, OTHER_TTL, d())).isEmpty();
        assertTtl(a, 48_000);
        assertTtl(b, 48_000);
        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_整组建票_部分成员的票已过期_只补建缺的() {
        long a = p(1);
        long b = p(2);
        List<GroupMember> members = List.of(new GroupMember(a, "g-a", 1), new GroupMember(b, "g-b", 1));
        store().createGroup(members, 5, 1, 900, 48_000, d());
        expireTicket(b);

        // 这一次换一个 TTL（30 s）：补建的那张用它，还在的那张不许被碰
        assertThat(store().createGroup(members, 5, 1, 900, 30_000, d())).isEmpty();

        assertThat(ticket(b).ticketId()).isEqualTo("g-b");
        assertThat(ticket(a).ticketId()).isEqualTo("g-a");
        assertTtl(a, 48_000);
        assertThat(ttlMs(b)).as("补建的票按这一次的 TTL").isBetween(30_000 - TTL_SLACK, 30_000L);
    }

    @Test
    public void 重放_自愈_第二次已经不在了_也算没有票() {
        QueueRef q = q(1);
        long ready = p(1);
        long orphanPlayer = p(2);
        putTicket(ready, new Ticket("t-r", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), READY_TTL);
        Ticket orphan = new Ticket("t-o", 3, q.configId(), TicketState.QUEUED, 1, 1, q.queueKey(), 150_000, 0, 0, 0);
        putTicket(orphanPlayer, orphan, QUEUED_TTL);
        Ticket seen = ticket(ready);
        store().heal(ready, seen, HealMode.READY, d());
        store().heal(orphanPlayer, orphan, HealMode.ORPHAN, d());
        Object once = fingerprint();

        assertThat(store().heal(ready, seen, HealMode.READY, d())).isTrue();
        assertThat(store().heal(orphanPlayer, orphan, HealMode.ORPHAN, d())).isTrue();

        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_自愈之后玩家重排了新票_重放不碰新票() {
        QueueRef q = q(1);
        long a = p(1);
        putTicket(a, new Ticket("t-r", 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), READY_TTL);
        Ticket seen = ticket(a);
        store().heal(a, seen, HealMode.READY, d());
        enqueue(a, q, 150_000);
        Object requeued = fingerprint();

        assertThat(store().heal(a, seen, HealMode.READY, d())).as("票号不符：在途").isFalse();

        assertThat(fingerprint()).isEqualTo(requeued);
    }

    @Test
    public void 重放_取消_第二次回没删_状态不变() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        assertThat(store().cancel(a, tid(a), q, d())).isTrue();
        Object once = fingerprint();

        assertThat(store().cancel(a, tid(a), q, d())).as("重放：已经删了").isFalse();

        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_剔除_第二次仍回已摘掉_状态不变() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        putQueueMember(q, "abc", null);
        assertThat(store().drop(q, a, DropReason.IN_BATTLE, tid(a), d())).isTrue();
        assertThat(store().dropMalformed(q, "abc", d())).isTrue();
        Object once = fingerprint();

        assertThat(store().drop(q, a, DropReason.IN_BATTLE, tid(a), d())).isTrue();
        assertThat(store().dropMalformed(q, "abc", d())).isFalse();

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(queueMembers(q)).containsExactly(u(b));
    }

    @Test
    public void 重放_剔除之后玩家重排了新票_重放不碰新票与新队列项() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().drop(q, a, DropReason.OFFLINE, tid(a), d());
        store().enqueue(a, "t-again", q, 1, 150_000, QUEUED_TTL, d());
        Object requeued = fingerprint();

        assertThat(store().drop(q, a, DropReason.OFFLINE, tid(a), d())).isFalse();

        assertThat(fingerprint()).isEqualTo(requeued);
        assertThat(queueMembers(q)).containsExactly(u(a));
    }

    @Test
    public void 重放_弹组_同一个token重发命中标记_不重写不续期() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);
        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
        Object once = fingerprint();
        long ttl = ttlMs(a);

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d())).isInstanceOf(PopResult.Replayed.class);

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).isLessThanOrEqualTo(ttl);
        assertThat(queueMembers(q)).containsExactly(u(c));

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), OTHER_TTL, d())).isInstanceOf(PopResult.Replayed.class);
        assertTtl(a, MATCHED_TTL);
        assertTtl(b, MATCHED_TTL);
        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_弹组之后有人已回队首_重放仍命中标记_不会把回了队的人再弹一次() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        store().requeueFront(q, rq(), List.of(ref(a)), QUEUED_TTL, 0, d());
        Object back = fingerprint();

        assertThat(store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d())).isInstanceOf(PopResult.Replayed.class);

        assertThat(fingerprint()).isEqualTo(back);
        assertThat(stateOf(a)).isEqualTo(TicketState.QUEUED);
        assertTtl(a, QUEUED_TTL);
    }

    @Test
    public void 重放_置ready_同一个battle_id再置一次仍成功_不续期() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        assertThat(store().markReady(ref(a), 77, READY_TTL, d())).isTrue();
        Object once = fingerprint();
        long ttl = ttlMs(a);

        assertThat(store().markReady(ref(a), 77, READY_TTL, d())).as("同一个 battle_id 的重放").isTrue();

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).isLessThanOrEqualTo(ttl);

        assertThat(store().markReady(ref(a), 77, OTHER_TTL, d())).isTrue();
        assertTtl(a, READY_TTL);
        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_续期_张数不变_TTL还是那个值() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        assertThat(store().extendMatched(List.of(ref(a)), 16_000, d())).isEqualTo(1);
        Object once = fingerprint();

        assertThat(store().extendMatched(List.of(ref(a)), 16_000, d())).isEqualTo(1);

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(ttlMs(a)).isBetween(16_000 - TTL_SLACK, 16_000L);
    }

    @Test
    public void 重放_删票与删一组票_第二次回没删_状态不变() {
        long a = p(1);
        long b = p(2);
        long c = p(3);
        store().createGroup(List.of(new GroupMember(a, "g-a", 1), new GroupMember(b, "g-b", 1), new GroupMember(c, "g-c", 1)), 5, 1, 0, 54_000, d());
        assertThat(store().delete(new TicketRef(a, "g-a"), d())).isTrue();
        assertThat(store().deleteGroup(List.of(new TicketRef(b, "g-b"), new TicketRef(c, "g-c")), d())).isEqualTo(2);
        Object once = fingerprint();

        assertThat(store().delete(new TicketRef(a, "g-a"), d())).as("重放").isFalse();
        assertThat(store().deleteGroup(List.of(new TicketRef(b, "g-b"), new TicketRef(c, "g-c")), d())).as("重放").isZero();

        assertThat(fingerprint()).isEqualTo(once);
    }

    @Test
    public void 重放_删票之后玩家重排了新票_重放不删新票() {
        QueueRef q = q(1);
        long a = p(1);
        store().createMatched(a, "t-old", 4, 1, 1, 150_000, 42_000, d());
        store().delete(new TicketRef(a, "t-old"), d());
        enqueue(a, q, 150_000);
        Object requeued = fingerprint();

        assertThat(store().delete(new TicketRef(a, "t-old"), d())).isFalse();
        assertThat(store().deleteGroup(List.of(new TicketRef(a, "t-old")), d())).isZero();

        assertThat(fingerprint()).isEqualTo(requeued);
    }

    @Test
    public void 重放_回队首_同一个token再调一次_不会被推两次_返回第一次的人数() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        String token = rq();
        assertThat(store().requeueFront(q, token, List.of(ref(a), ref(b)), QUEUED_TTL, 2000, d())).isEqualTo(2);
        Object once = fingerprint();

        int replay = store().requeueFront(q, token, List.of(ref(a), ref(b)), QUEUED_TTL, 2000, d());

        assertThat(replay).as("重放返回第一次的人数（指标与「回去的人数少于幸存者」的告警不被重放带偏）").isEqualTo(2);
        assertThat(queueMembers(q)).as("没有被推两次").containsExactly(u(a), u(b));
        assertThat(fingerprint()).as("退避到点时刻也没有被往后推").isEqualTo(once);
    }

    @Test
    public void 回队首_换一个token对已经回了队的人再调_按现状核对_已是queued的跳过_返回0() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        assertThat(store().requeueFront(q, rq(), List.of(ref(a), ref(b)), QUEUED_TTL, 2000, d())).isEqualTo(2);
        Object once = fingerprint();

        assertThat(store().requeueFront(q, rq(), List.of(ref(a), ref(b)), QUEUED_TTL, 2000, d())).as("另一次回队首：票已是 queued，CAS 不成立").isZero();

        assertThat(queueMembers(q)).containsExactly(u(a), u(b));
        assertThat(fingerprint()).isEqualTo(once);
    }

    /**
     * 回队首的 CAS 条件在第一次执行之后<b>还能重新成立</b>：回了队首的人被凑单用新的弹组 token 再弹一次，票又是 matched（票号、队列都没变）。
     * 这时迟到的重发（同一个回队首 token）不得把正在第二次 gather 里的人再推回队首。
     */
    @Test
    public void 重放_回队首之后有人又被弹成matched_同一个token的重放不把他再推回队首() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        long c = p(3);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        enqueue(c, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        String token = rq();
        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).as("gather#1 失败，b 是肇事者，a 回队首").isEqualTo(1);
        assertThat(store().pop(q, tok("pop-2"), List.of(ref(a), ref(c)), MATCHED_TTL, d())).as("凑单把 a 与 c 再弹成组").isInstanceOf(PopResult.Popped.class);
        Object secondGather = fingerprint();
        long ttl = ttlMs(a);

        int replay = store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d());

        assertThat(replay).as("返回第一次的人数").isEqualTo(1);
        assertThat(stateOf(a)).as("a 仍在第二次 gather 里").isEqualTo(TicketState.MATCHED);
        assertThat(queueMembers(q)).as("队列里没有他").isEmpty();
        assertThat(rankOf(q)).isEmpty();
        assertThat(fingerprint()).isEqualTo(secondGather);
        assertThat(ttlMs(a)).as("matched 的寿命没有被改成 queued 的 6 h").isLessThanOrEqualTo(ttl);
        assertThat(store().markReady(ref(a), 4242, READY_TTL, d())).as("第二次 gather 成功时照常置 ready").isTrue();
    }

    @Test
    public void 重放_回队首第一次一个人都没放回去_标记照写_之后条件成立了重放也不放() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        String token = rq();
        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).as("他还在队列里（queued）：不满足").isZero();
        assertThat(queueMembers(q)).containsExactly(u(a));
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        Object popped = fingerprint();

        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).as("同一个 token：第一次回 0 也记了标记").isZero();

        assertThat(fingerprint()).isEqualTo(popped);
        assertThat(stateOf(a)).isEqualTo(TicketState.MATCHED);
        assertThat(queueMembers(q)).isEmpty();
    }

    @Test
    public void 回队首的标记过期之后_同一个token按现状重新核对() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        String token = rq();
        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);
        expireRequeueMarker(token);
        Object once = fingerprint();

        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).as("标记没了：按现状核对，他已是 queued").isZero();
        assertThat(fingerprint()).isEqualTo(once);

        store().pop(q, tok("pop-2"), List.of(ref(a)), MATCHED_TTL, d());
        expireRequeueMarker(token);
        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).as("标记过期后是一次新的回队首").isEqualTo(1);
        assertThat(queueMembers(q)).containsExactly(u(a));
        assertThat(stateOf(a)).isEqualTo(TicketState.QUEUED);
    }

    @Test
    public void 回队首的空名单不写标记_同一个token随后照常回队首() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        String token = rq();

        assertThat(store().requeueFront(q, token, List.of(), QUEUED_TTL, 0, d())).isZero();

        assertThat(store().requeueFront(q, token, List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);
        assertThat(queueMembers(q)).containsExactly(u(a));
    }

    @Test
    public void 重放_剔除空队列与释放锁_幂等() {
        QueueRef q = q(1);
        putQueueMember(q, "junk", null);
        store().dropMalformed(q, "junk", d());
        store().tryLockQueue(q, "inst-a", 10_000, d());
        assertThat(store().pruneIfEmpty(q, d())).isTrue();
        store().unlockQueue(q, "inst-a", d());
        Object once = fingerprint();

        assertThat(store().pruneIfEmpty(q, d())).as("本来就不在").isTrue();
        store().unlockQueue(q, "inst-a", d());

        assertThat(fingerprint()).isEqualTo(once);
        assertThat(lockHolder(q)).isEmpty();
    }

    @Test
    public void 重放_释放锁之后被别人抢到_重放不会放掉别人的锁() {
        QueueRef q = q(1);
        store().tryLockQueue(q, "inst-a", 10_000, d());
        store().unlockQueue(q, "inst-a", d());
        assertThat(store().tryLockQueue(q, "inst-b", 10_000, d())).isTrue();

        store().unlockQueue(q, "inst-a", d());

        assertThat(lockHolder(q)).contains("inst-b");
    }

    // ================================================================ 迟到的 gather 写不脏玩家重排之后的新票（I4）

    @Test
    public void 票据被替换之后_旧gather的置ready_续期_回队首_删票都不动新票() {
        QueueRef q = q(1);
        long a = p(1);
        // 旧票被弹出（matched），TTL 到期自灭；玩家重排拿到新票（queued，在队列里）
        store().enqueue(a, "t-old", q, 1, 150_000, QUEUED_TTL, d());
        store().pop(q, tok("pop-1"), List.of(new TicketRef(a, "t-old")), MATCHED_TTL, d());
        expireTicket(a);
        store().enqueue(a, "t-new", q, 1, 150_000, QUEUED_TTL, d());
        String lateToken = tok("pop-2");
        Object fresh = fingerprint();
        TicketRef stale = new TicketRef(a, "t-old");

        assertThat(store().markReady(stale, 987_654_321L, READY_TTL, d())).isFalse();
        assertThat(store().extendMatched(List.of(stale), 16_000, d())).isZero();
        assertThat(store().requeueFront(q, rq(), List.of(stale), QUEUED_TTL, 0, d())).isZero();
        assertThat(store().delete(stale, d())).isFalse();
        assertThat(store().deleteGroup(List.of(stale), d())).isZero();
        assertThat(store().pop(q, lateToken, List.of(stale), MATCHED_TTL, d())).isEqualTo(new PopResult.Invalid(List.of(a)));

        assertThat(fingerprint()).isEqualTo(fresh);
        assertThat(ticket(a).ticketId()).isEqualTo("t-new");
        assertThat(ticket(a).battleId()).isZero();
        assertThat(queueMembers(q)).as("队列里不能塞成两份").containsExactly(u(a));
        assertTtl(a, QUEUED_TTL);
    }

    // ================================================================ 参数

    @Test
    public void 参数不合法是调用方的错_什么都不发() {
        QueueRef q = q(1);
        long a = p(1);
        List<TicketRef> none = new ArrayList<>();

        assertThatThrownBy(() -> store().enqueue(0, "t", q, 1, 0, QUEUED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().enqueue(a, "", q, 1, 0, QUEUED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().enqueue(a, "t", q, 1, 0, 0, d())).as("TTL 必须 ≥ 1 ms").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().createMatched(a, "t", 4, 1, 1, 0, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().createGroup(List.of(new GroupMember(a, "x", 1), new GroupMember(a, "y", 1)), 5, 1, 0, 48_000, d()))
                .as("整组建票的玩家号重复").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().cancel(a, "", q, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().snapshot(q, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().drop(q, 0, DropReason.INVALID, null, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().pop(q, "", List.of(ref(a)), MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().pop(q, "p", none, MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().pop(q, "p", List.of(ref(a), ref(a)), MATCHED_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().pop(q, "p", List.of(ref(a)), 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().markReady(ref(a), 0, READY_TTL, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().markReady(ref(a), 77, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().extendMatched(List.of(ref(a)), 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().requeueFront(q, rq(), none, QUEUED_TTL, -1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().requeueFront(q, "", List.of(ref(a)), QUEUED_TTL, 0, d())).as("回队首必须带 token").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().requeueFront(q, null, List.of(ref(a)), QUEUED_TTL, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().requeueFront(q, rq(), List.of(ref(a), ref(a)), QUEUED_TTL, 0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().tryLockQueue(q, "", 10_000, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().tryLockQueue(q, "inst", 0, d())).isInstanceOf(IllegalArgumentException.class);

        assertThat(ticketOf(a)).isEmpty();
        assertThat(queueMembers(q)).isEmpty();
        assertThat(lockHolder(q)).isEmpty();
    }
}
