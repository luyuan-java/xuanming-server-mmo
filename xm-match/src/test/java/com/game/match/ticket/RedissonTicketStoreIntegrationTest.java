package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.ticket.TicketStore.DropReason;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.match.ticket.TicketStore.HealMode;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.match.ticket.TicketStore.PopResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 票据存储的 Redis 实现连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}；DB 13，随机的玩家号 / 副本号，只删自己的键）。
 *
 * <ul>
 *   <li>先过 {@link TicketStoreContract} 的全部用例——15 段 Lua 的真值表、I1–I5、<b>每段可变脚本连跑两次第二次什么都不改</b>。这里的
 *       {@link #fingerprint()} 另加了全部相关键的 {@code DUMP}：「什么都不改」是逐字节比出来的。玩家号全部 ≥ 2^63（键名、成员串、Lua 里的比较按无符号）。</li>
 *   <li>再加只有真 Redis 才看得见的东西：HASH 的字段布局与键的类型、TTL 的毫秒数、损坏的 HASH 与被占成别的类型的键、截止已过不发、
 *       脚本中途出错不留孤儿票、真并发下的「每人一张票」与「不双弹」。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedissonTicketStoreIntegrationTest extends TicketStoreContract {

    private static RedissonClient redis;
    private TicketRedisFixture fx;

    @BeforeAll
    static void connect() {
        redis = TicketRedisFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        fx = new TicketRedisFixture(redis, true);
    }

    @AfterEach
    void cleanup() {
        fx.cleanup();
    }

    // ================================================================ 契约测试的钩子

    @Override
    protected TicketStore store() {
        return fx.store;
    }

    @Override
    protected long pid(int n) {
        return fx.pid(n);
    }

    @Override
    protected QueueRef queue(int n) {
        return fx.queue(3, n);
    }

    @Override
    protected String token(String name) {
        return fx.token(name);
    }

    @Override
    protected long nowMs() {
        return fx.nowMs();
    }

    @Override
    protected void putTicket(long playerId, Ticket ticket, long ttlMs) {
        fx.putTicket(playerId, ticket, ttlMs);
    }

    @Override
    protected void putQueueMember(QueueRef queue, String member, Long ratingCenti) {
        fx.putQueueMember(queue, member, ratingCenti);
    }

    @Override
    protected Optional<Ticket> ticketOf(long playerId) {
        return fx.ticketOf(playerId);
    }

    @Override
    protected long ttlMs(long playerId) {
        return fx.ticketTtlMs(playerId);
    }

    @Override
    protected List<String> queueMembers(QueueRef queue) {
        return fx.queueMembers(queue);
    }

    @Override
    protected Map<String, Long> rankOf(QueueRef queue) {
        return fx.rankOf(queue);
    }

    @Override
    protected boolean indexed(QueueRef queue) {
        return fx.indexed(queue);
    }

    @Override
    protected Optional<String> lockHolder(QueueRef queue) {
        return fx.lockHolder(queue);
    }

    @Override
    protected void expireTicket(long playerId) {
        fx.del(TicketRedisFixture.ticketKey(playerId));
    }

    @Override
    protected void expirePopMarker(String popToken) {
        fx.del(RedisKeys.matchPopMarker(popToken));
    }

    @Override
    protected void expireLock(QueueRef queue) {
        fx.del(queue.lockKey());
    }

    /** 结构化内容之外再加逐键的原始字节：重放用例的「第二次什么都不改」按 DUMP 比。 */
    @Override
    protected Object fingerprint() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("structured", super.fingerprint());
        out.put("dump", fx.dump());
        return out;
    }

    // ================================================================ 键与字段的形状

    @Test
    void 入队之后各把键的名字与类型_全部在match一个槽() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 162_500);
        store().tryLockQueue(q, "inst-a", 10_000, d());

        String ticketKey = "xm:{match}:ticket:" + u(a);
        String suffix = "3:" + Integer.toUnsignedString(q.configId());
        assertThat(fx.type(ticketKey)).isEqualTo("hash");
        assertThat(fx.type("xm:{match}:queue:" + suffix)).isEqualTo("list");
        assertThat(fx.type("xm:{match}:rank:" + suffix)).isEqualTo("zset");
        assertThat(fx.type("xm:{match}:lock:" + suffix)).isEqualTo("string");
        assertThat(fx.type("xm:{match}:index")).isEqualTo("set");
        assertThat(fx.indexed(q)).as("注册集的成员是队列键全文").isTrue();
        assertThat(u(a)).as("玩家号 ≥ 2^63，键里是无符号十进制").hasSize(19).doesNotStartWith("-");
    }

    @Test
    void 票据HASH的字段布局_入队票只有八个字段() {
        QueueRef q = q(1);
        long a = p(1);
        long enqueuedAt = ((JoinResult.Created) store().enqueue(a, tid(a), q, 2, 162_500, QUEUED_TTL, d())).enqueuedAtMs();

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ticket", tid(a));
        expected.put("mode", "3");
        expected.put("config", Integer.toUnsignedString(q.configId()));
        expected.put("state", "queued");
        expected.put("enqueued_at_ms", Long.toString(enqueuedAt));
        expected.put("zone_id", "2");
        expected.put("queue_key", q.queueKey());
        expected.put("rating_centi", "162500");
        assertThat(fx.rawTicket(a)).isEqualTo(expected);
        assertThat(fx.rawTicket(a).get("enqueued_at_ms")).as("十进制整数，不是科学计数法").matches("\\d{13}");
    }

    @Test
    void 票据HASH的字段布局_matched票的队列键是空串_队伍号只在非0时写() {
        long solo = p(1);
        long member = p(2);
        long teamId = Long.MIN_VALUE + 900;
        store().createMatched(solo, tid(solo), 4, -1, 7, 150_000, 42_000, d());
        store().createGroup(List.of(new GroupMember(member, "g-1", 3)), 5, 1, teamId, 48_000, d());

        assertThat(fx.rawTicket(solo)).containsOnlyKeys("ticket", "mode", "config", "state", "enqueued_at_ms", "zone_id", "queue_key", "rating_centi")
                .containsEntry("state", "matched").containsEntry("queue_key", "").containsEntry("mode", "4")
                .containsEntry("config", "4294967295").containsEntry("zone_id", "7");
        assertThat(fx.rawTicket(member)).containsOnlyKeys("ticket", "mode", "config", "state", "enqueued_at_ms", "zone_id", "queue_key",
                        "rating_centi", "team_id")
                .containsEntry("team_id", "9223372036854776708").containsEntry("rating_centi", "150000").containsEntry("state", "matched");
    }

    @Test
    void 票据HASH的字段布局_ready写battle_id_回队首删掉它_退避时刻只在带退避时有() {
        QueueRef q = q(1);
        long a = p(1);
        long battleId = Long.MIN_VALUE + 77;
        enqueue(a, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        assertThat(fx.rawTicket(a)).containsEntry("state", "matched").doesNotContainKeys("battle_id", "not_before_ms");

        store().markReady(ref(a), battleId, READY_TTL, d());
        assertThat(fx.rawTicket(a)).containsEntry("state", "ready").containsEntry("battle_id", "9223372036854775885");

        // 把 ready 票摆回 matched（保留 battle_id 字段）再回队首：battle_id 必须被删掉
        fx.setField(a, "state", "matched");
        long before = nowMs();
        assertThat(store().requeueFront(q, List.of(ref(a)), QUEUED_TTL, 600_000, d())).isEqualTo(1);
        long after = nowMs();
        Map<String, String> backing = fx.rawTicket(a);
        assertThat(backing).containsEntry("state", "queued").doesNotContainKey("battle_id");
        assertThat(Long.parseLong(backing.get("not_before_ms"))).isBetween(before + 600_000, after + 600_000);

        store().pop(q, tok("pop-2"), List.of(ref(a)), MATCHED_TTL, d());
        assertThat(ticketOf(a).orElseThrow().state()).as("退避没到，弹不出来").isEqualTo(TicketState.QUEUED);
        fx.setField(a, "not_before_ms", Long.toString(nowMs() - 1));
        assertThat(store().pop(q, tok("pop-3"), List.of(ref(a)), MATCHED_TTL, d())).isInstanceOf(PopResult.Popped.class);
        assertThat(store().requeueFront(q, List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);
        assertThat(fx.rawTicket(a)).as("不带退避回队首：删掉 not_before_ms").doesNotContainKey("not_before_ms");
    }

    @Test
    void 回队首_票里没有评分时按缺省1500写镜像() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 162_500);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        fx.removeField(a, "rating_centi");

        assertThat(store().requeueFront(q, List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);

        assertThat(rankOf(q)).containsEntry(u(a), 150_000L);
    }

    // ================================================================ TTL

    @Test
    void 各种票与标记的TTL按毫秒设() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isBetween(QUEUED_TTL - 5_000, QUEUED_TTL);
        assertThat(fx.pttl(q.queueKey())).as("队列没有 TTL").isEqualTo(-1);
        assertThat(fx.pttl(q.rankKey())).as("评分镜像没有 TTL").isEqualTo(-1);

        store().pop(q, tok("pop-1"), List.of(ref(a)), 66_000, d());
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isBetween(61_000L, 66_000L);
        assertThat(fx.pttl(RedisKeys.matchPopMarker(tok("pop-1")))).as("弹组重放标记 60 s").isBetween(55_000L, RedissonTicketStore.POP_MARKER_TTL_MS);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(b))).as("没被弹的人 TTL 不动").isGreaterThan(66_000L);

        store().extendMatched(List.of(ref(a)), 25_000, d());
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isBetween(20_000L, 25_000L);
        store().markReady(ref(a), 77, 60_000, d());
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isBetween(55_000L, 60_000L);

        store().tryLockQueue(q, "inst-a", 10_000, d());
        assertThat(fx.pttl(q.lockKey())).isBetween(5_000L, 10_000L);
    }

    @Test
    void 票的TTL到了真的会自己消失() throws Exception {
        long a = p(1);
        store().createMatched(a, tid(a), 4, 1, 1, 150_000, 150, d());
        assertThat(store().read(a, d())).isPresent();

        TimeUnit.MILLISECONDS.sleep(400);

        assertThat(store().read(a, d())).isEmpty();
        assertThat(store().createMatched(a, "t-again", 4, 1, 1, 150_000, 42_000, d())).as("过期之后可以再建").isInstanceOf(JoinResult.Created.class);
    }

    // ================================================================ 损坏的数据与被占成别的类型的键

    @Test
    void 票据HASH存在却没有票号_读一律按故障抛出_写一律不碰它() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        fx.putTicket(a, new Ticket("will-be-removed", 3, q.configId(), TicketState.QUEUED, 1, 1, q.queueKey(), 150_000, 0, 0, 0), QUEUED_TTL);
        fx.removeField(a, "ticket");
        String popToken = tok("pop-1"); // 先分配：下面的 dump 里包含它的重放标记（此刻不存在）
        Object before = fx.dump();

        assertThatThrownBy(() -> store().read(a, d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("没有票号");
        assertThatThrownBy(() -> store().status(a, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().readAll(List.of(b, a), d())).as("不返回半份结果").isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().createMatched(a, tid(a), 4, 1, 1, 150_000, 42_000, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(store().createGroup(List.of(new GroupMember(b, "g-b", 1), new GroupMember(a, "g-a", 1)), 5, 1, 0, 48_000, d()))
                .as("按冲突处理：一张都不建").hasValue(a);
        assertThat(store().cancel(a, tid(a), q, d())).isFalse();
        assertThat(store().delete(ref(a), d())).isFalse();
        assertThat(store().markReady(ref(a), 77, READY_TTL, d())).isFalse();
        assertThat(store().extendMatched(List.of(ref(a)), 16_000, d())).isZero();
        assertThat(store().requeueFront(q, List.of(ref(a)), QUEUED_TTL, 0, d())).isZero();
        assertThat(store().pop(q, popToken, List.of(ref(a)), MATCHED_TTL, d())).isEqualTo(new PopResult.Invalid(List.of(a)));
        assertThat(store().heal(a, new Ticket(tid(a), 3, 0, TicketState.READY, 1, 1, "", 150_000, 0, 0, 0), HealMode.READY, d())).isFalse();

        assertThat(fx.dump()).as("没有任何写入").isEqualTo(before);
        assertThat(ticketOf(b)).isEmpty();
        assertThat(queueMembers(q)).isEmpty();
    }

    @Test
    void 票键被占成别的类型_读写都按依赖故障抛出_队列不被动() {
        QueueRef q = q(1);
        long a = p(1);
        fx.setString(TicketRedisFixture.ticketKey(a), "not-a-hash");

        assertThatThrownBy(() -> store().read(a, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().delete(ref(a), d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(queueMembers(q)).isEmpty();
        assertThat(indexed(q)).isFalse();
    }

    @Test
    void 入队中途出错_不留孤儿票_最多留下一个没有票的队列项() {
        QueueRef q = q(1);
        long a = p(1);
        fx.setString(q.rankKey(), "not-a-zset"); // ZADD 会报 WRONGTYPE：此时注册集与队列已写

        assertThatThrownBy(() -> store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(ticketOf(a)).as("先写队列后写票：出错时没有「票是 queued、人不在队列」的状态").isEmpty();
        assertThat(queueMembers(q)).as("残留项没有票，凑单的校验会剔掉").containsExactly(u(a));
        fx.del(q.rankKey());
        assertThat(store().drop(q, a, DropReason.INVALID, null, d())).isTrue();
        assertThat(queueMembers(q)).isEmpty();
        assertThat(store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, d())).as("玩家可以照常重排").isInstanceOf(JoinResult.Created.class);
    }

    @Test
    void 回队首时队列键被占成别的类型_删票让玩家可以立即重排_不留queued孤儿() {
        QueueRef q = q(1);
        long a = p(1);
        long b = p(2);
        enqueue(a, q, 150_000);
        enqueue(b, q, 150_000);
        store().pop(q, tok("pop-1"), List.of(ref(a), ref(b)), MATCHED_TTL, d());
        fx.setString(q.queueKey(), "not-a-list");

        int requeued = store().requeueFront(q, List.of(ref(a), ref(b)), QUEUED_TTL, 0, d());

        assertThat(requeued).isZero();
        assertThat(ticketOf(a)).as("入队失败必须删票").isEmpty();
        assertThat(ticketOf(b)).isEmpty();
        fx.del(q.queueKey());
        assertThat(store().enqueue(a, "t-again", q, 1, 150_000, QUEUED_TTL, d())).as("队列键恢复后可以立即重排").isInstanceOf(JoinResult.Created.class);
        assertThat(queueMembers(q)).containsExactly(u(a));
    }

    @Test
    void 回队首时镜像键被占成别的类型_只丢镜像分_票与队列照常一致() {
        QueueRef q = q(1);
        long a = p(1);
        enqueue(a, q, 162_500);
        store().pop(q, tok("pop-1"), List.of(ref(a)), MATCHED_TTL, d());
        fx.setString(q.rankKey(), "not-a-zset");

        assertThat(store().requeueFront(q, List.of(ref(a)), QUEUED_TTL, 0, d())).isEqualTo(1);

        assertThat(ticketOf(a).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
        assertThat(queueMembers(q)).containsExactly(u(a));
        fx.del(q.rankKey());
        assertThat(store().snapshot(q, 10, d()).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.ratingCenti()).as("镜像缺分：凑单按票里的评分用").isEmpty());
    }

    // ================================================================ 截止与连接

    @Test
    void 截止已过_不发出去_什么都没写() {
        QueueRef q = q(1);
        long a = p(1);
        Deadline expired = Deadline.after(0);

        assertThatThrownBy(() -> store().enqueue(a, tid(a), q, 1, 150_000, QUEUED_TTL, expired)).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store().read(a, expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().queueIndex(expired)).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> store().tryLockQueue(q, "inst-a", 10_000, expired)).isInstanceOf(Deadline.DependencyException.class);

        assertThat(ticketOf(a)).isEmpty();
        assertThat(queueMembers(q)).isEmpty();
        assertThat(lockHolder(q)).isEmpty();
    }

    @Test
    void 客户端已不可用_在请求预算内以依赖异常失败_不折成没有票() {
        RedissonClient closed = TicketRedisFixture.connect();
        RedissonTicketStore dead = new RedissonTicketStore(closed);
        long a = p(1);
        assertThat(dead.read(a, d())).isEmpty();
        closed.shutdown();

        long started = System.nanoTime();
        assertThatThrownBy(() -> dead.read(a, Deadline.after(1500))).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> dead.queueLength(q(1), Deadline.after(1500))).isInstanceOf(Deadline.DependencyException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(8000);
    }

    // ================================================================ 规模与并发

    @Test
    void 批量读几十个人_逐人对上各自的票() {
        QueueRef q = q(1);
        List<Long> everyone = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            long playerId = p(i);
            everyone.add(playerId);
            if (i % 3 != 0) {
                enqueue(playerId, q, 150_000 + i);
                queued.add(playerId);
            }
        }

        Map<Long, Ticket> all = store().readAll(everyone, d());

        assertThat(all.keySet()).containsExactlyElementsOf(queued);
        for (long playerId : queued) {
            assertThat(all.get(playerId).ticketId()).isEqualTo(tid(playerId));
        }
        assertThat(all.get(p(1)).ratingCenti()).isEqualTo(150_001);
        assertThat(store().snapshot(q, 256, d()).entries()).extracting(TicketStore.SnapshotEntry::playerId).containsExactlyElementsOf(queued);
        assertThat(store().snapshot(q, 7, d()).entries()).hasSize(7);
        assertThat(store().queueLength(q, d())).isEqualTo(40);
    }

    @Test
    void 同一玩家并发建票_只有一张票_其余都拿到赢家的票号_队列里只有一份() throws Exception {
        QueueRef q = q(1);
        long a = p(1);
        int racers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JoinResult>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                String ticketId = "race-" + i;
                Callable<JoinResult> join = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().enqueue(a, ticketId, q, 1, 150_000, QUEUED_TTL, d());
                };
                futures.add(pool.submit(join));
            }
            start.countDown();
            List<JoinResult> results = new ArrayList<>();
            for (Future<JoinResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            String winner = ticketOf(a).orElseThrow().ticketId();
            assertThat(results).filteredOn(JoinResult.Created.class::isInstance).as("I3：每人至多一张票").hasSize(1);
            assertThat(results).filteredOn(JoinResult.Exists.class::isInstance).hasSize(racers - 1)
                    .allSatisfy(result -> assertThat(((JoinResult.Exists) result).ticketId()).isEqualTo(winner));
            assertThat(queueMembers(q)).as("后来者不得入队").containsExactly(u(a));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 两个实例并发弹有交集的两组_至多一组弹出_没有人被弹两次() throws Exception {
        QueueRef q = q(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long a = p(round * 3 + 1);
                long b = p(round * 3 + 2);
                long c = p(round * 3 + 3);
                enqueue(a, q, 150_000);
                enqueue(b, q, 150_000);
                enqueue(c, q, 150_000);
                String tokenLeft = tok("left-" + round);
                String tokenRight = tok("right-" + round);
                CountDownLatch start = new CountDownLatch(1);
                Future<PopResult> left = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().pop(q, tokenLeft, List.of(ref(a), ref(b)), MATCHED_TTL, d());
                });
                Future<PopResult> right = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().pop(q, tokenRight, List.of(ref(b), ref(c)), MATCHED_TTL, d());
                });
                start.countDown();
                PopResult leftResult = left.get(30, TimeUnit.SECONDS);
                PopResult rightResult = right.get(30, TimeUnit.SECONDS);

                boolean leftPopped = leftResult instanceof PopResult.Popped;
                boolean rightPopped = rightResult instanceof PopResult.Popped;
                assertThat(leftPopped ^ rightPopped).as("第 %d 轮：两组抢同一个人，恰好一组弹出 left=%s right=%s", round, leftResult, rightResult).isTrue();
                if (leftPopped) {
                    assertThat(rightResult).isEqualTo(new PopResult.Invalid(List.of(b)));
                    assertThat(queueMembers(q)).containsExactly(u(c));
                    assertThat(ticketOf(c).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
                } else {
                    assertThat(leftResult).isEqualTo(new PopResult.Invalid(List.of(b)));
                    assertThat(queueMembers(q)).containsExactly(u(a));
                    assertThat(ticketOf(a).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
                }
                assertThat(ticketOf(b).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
                // 清场：下一轮从空队列开始
                store().cancel(a, tid(a), q, d());
                store().cancel(c, tid(c), q, d());
                assertThat(queueMembers(q)).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 取消与弹组并发_取消成功的人一定不在弹出的组里() throws Exception {
        QueueRef q = q(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long a = p(round * 2 + 1);
                long b = p(round * 2 + 2);
                enqueue(a, q, 150_000);
                enqueue(b, q, 150_000);
                String token = tok("pop-" + round);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> cancel = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().cancel(a, tid(a), q, d());
                });
                Future<PopResult> pop = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().pop(q, token, List.of(ref(a), ref(b)), MATCHED_TTL, d());
                });
                start.countDown();
                boolean cancelled = cancel.get(30, TimeUnit.SECONDS);
                PopResult popped = pop.get(30, TimeUnit.SECONDS);

                if (cancelled) {
                    assertThat(popped).as("第 %d 轮：取消先到，弹组全有全无地失败", round).isEqualTo(new PopResult.Invalid(List.of(a)));
                    assertThat(ticketOf(a)).isEmpty();
                    assertThat(ticketOf(b).orElseThrow().state()).as("b 没有离开原位").isEqualTo(TicketState.QUEUED);
                    assertThat(queueMembers(q)).containsExactly(u(b));
                } else {
                    assertThat(popped).as("第 %d 轮：弹组先到，取消太迟", round).isInstanceOf(PopResult.Popped.class);
                    assertThat(ticketOf(a).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
                    assertThat(queueMembers(q)).isEmpty();
                }
                store().delete(ref(a), d());
                store().delete(ref(b), d());
                store().dropMalformed(q, u(b), d());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
