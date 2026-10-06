package com.game.match.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.MatchProperties;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.queue.QueueService.CancelOutcome;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FixedRatingReader;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.ForwardingTicketStore;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRedisFixture;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.JoinResult;
import com.game.match.ticket.TicketStore.PopResult;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
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
 * 基线排队侧的 miniredis 用例移植到真 Redis（match-spec §15.3；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13）：
 * {@code review_fix_test.go}、{@code ready_ticket_test.go}、{@code crosszone_test.go}、{@code ticket_cas_test.go} 里与票据 / 排队入口有关的那些
 * （凑单循环的用例归凑单包；legacy 队列的三条不移植）。被测的是 {@link QueueService} + Redis 票据存储 + 自愈规则的真实组合；
 * 战斗锁 / 位置、评分、gather 是替身。每个用例标了它对应的基线用例名。
 *
 * <p>与基线的写法差异：基线把入队、置 matched、回队首拆成多步（票据与队列跨 slot），用钩子把「另一个实例」插进两步之间；Java 每个迁移是一段脚本，
 * 没有可插的缝——对应的用例改成断言脚本执行前后的终态（没有孤儿），并发的那几条用真并发。基线用 miniredis 的 FastForward 拨时钟，
 * 这里改写键的剩余寿命或票里的时刻。队列用夹具随机的副本号（{@code xm:{match}:index} 是全局键，不能占用副本 0）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class QueuePortedIntegrationTest {

    private static final int MODE_1V1 = MatchMode.MATCH_MODE_1V1_VALUE;
    private static final int MODE_5V5 = MatchMode.MATCH_MODE_5V5_VALUE;
    private static final int MODE_PVE_SOLO = MatchMode.MATCH_MODE_PVE_SOLO_VALUE;
    private static final long SIX_HOURS = 21_600_000;
    private static final long READY_TTL = 60_000;

    private static RedissonClient redis;

    private TicketRedisFixture fx;
    private TicketStore store;
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FixedRatingReader ratings = new FixedRatingReader();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    private final MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
    private final MatchIds ids = new MatchIds(new Snowflake(7), () -> true, () -> false);
    private QueueService service;
    /** 本用例的 1V1 队列（副本号随机）。 */
    private QueueRef queue;
    private int config;

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
        fx = new TicketRedisFixture(redis, false);
        store = fx.store;
        service = serviceOn(store);
        config = fx.configId(0);
        queue = fx.track(new QueueRef(MODE_1V1, config));
    }

    @AfterEach
    void cleanup() {
        fx.cleanup();
    }

    private QueueService serviceOn(TicketStore tickets) {
        return new QueueService(props, players, tickets, new DefaultTicketHealing(tickets), ratings, gather, ids, metrics);
    }

    private static Deadline d() {
        return Deadline.after(10_000);
    }

    private static String u(long playerId) {
        return Long.toUnsignedString(playerId);
    }

    /** 模拟 scene 写了位置记录：玩家在 zone 的某个 scene 节点上在线。 */
    private long inScene(int n, int zoneId) {
        long playerId = fx.pid(n);
        players.online(playerId, zoneId, 7);
        return playerId;
    }

    private JoinQueueResponse joinQueue1v1(long playerId) {
        return service.join(playerId, JoinQueueRequest.newBuilder().setModeValue(MODE_1V1).setBattleConfigId(config).build(), d());
    }

    private Ticket ticket(long playerId) {
        return fx.ticketOf(playerId).orElseThrow(() -> new AssertionError("没有票 player=" + u(playerId)));
    }

    /** 模拟凑单把这个人弹出：出队 + 置 matched。 */
    private void pop(String name, long ttlMs, TicketRef... members) {
        assertThat(store.pop(queue, fx.token(name), List.of(members), ttlMs, d())).isInstanceOf(PopResult.Popped.class);
    }

    // ================================================================ review_fix_test.go

    /** TestCancelTicketIfQueuedIsCasOnState：取消的删票是（票号, state == queued）的 CAS。 */
    @Test
    void 取消的删票是票号加queued状态的CAS() {
        long a = inScene(1, 1);
        String ticketId = joinQueue1v1(a).getQueueTicket();

        // 票号不一致：不删
        assertThat(store.cancel(a, "stale", queue, d())).isFalse();
        assertThat(fx.ticketOf(a)).isPresent();

        // 已被弹出推进 matched：不删（取消太迟在存储层成立）
        pop("pop-1", 30_000, new TicketRef(a, ticketId));
        assertThat(store.cancel(a, ticketId, queue, d())).isFalse();
        assertThat(fx.rawTicket(a)).containsEntry("state", "matched");

        // 全流程：读到 matched 的取消幂等返回，票仍在
        assertThat(service.cancel(a, "", d())).isEqualTo(CancelOutcome.TOO_LATE);
        assertThat(fx.ticketOf(a)).isPresent();

        // 回到 queued 之后才删得掉
        assertThat(store.requeueFront(queue, List.of(new TicketRef(a, ticketId)), SIX_HOURS, 0, d())).isEqualTo(1);
        assertThat(store.cancel(a, ticketId, queue, d())).isTrue();
        assertThat(fx.ticketOf(a)).isEmpty();
        assertThat(fx.queueMembers(queue)).isEmpty();
    }

    /** TestJoinQueueHealsOrphanQueuedTicket：queued 但不在队列里的孤儿票被清掉，按本次请求正常入队。 */
    @Test
    void 排队自愈孤儿queued票_按新请求入队_旧队列不被塞回去() {
        long a = inScene(1, 1);
        fx.putTicket(a, new Ticket("orphan", MODE_1V1, config, TicketState.QUEUED, fx.nowMs() - 60_000, 1, queue.queueKey(), 150_000, 0, 0, 0),
                SIX_HOURS);
        assertThat(fx.exists(queue.queueKey())).as("前置：队列里没有他").isFalse();
        int otherConfig = fx.configId(7);
        QueueRef newQueue = fx.track(new QueueRef(MODE_5V5, otherConfig));

        // 用另一个模式重排：自愈后按新请求入队（不是把旧票补回旧队列）
        JoinQueueResponse response = service.join(a, JoinQueueRequest.newBuilder().setModeValue(MODE_5V5).setBattleConfigId(otherConfig).build(), d());

        assertThat(response.getErrorCode()).as("孤儿票据必须被清掉后正常入队").isZero();
        assertThat(response.getQueueTicket()).isNotEmpty().isNotEqualTo("orphan");
        Ticket ticket = ticket(a);
        assertThat(ticket.ticketId()).isEqualTo(response.getQueueTicket());
        assertThat(ticket.mode()).isEqualTo(MODE_5V5);
        assertThat(ticket.queueKey()).isEqualTo(newQueue.queueKey());
        assertThat(fx.queueMembers(newQueue)).containsExactly(u(a));
        assertThat(fx.exists(queue.queueKey())).as("旧队列不能被塞回去").isFalse();
    }

    /** TestJoinQueueStillRejectsLiveTickets：真在队列里的 queued 票、以及 matched 票，仍按「已在匹配队列中」拒（legacy 一段不移植）。 */
    @Test
    void 在队列里的queued票与matched票仍然回16001() {
        long a = inScene(2, 1);
        long b = inScene(3, 1);

        // 在队列里
        String first = joinQueue1v1(a).getQueueTicket();
        JoinQueueResponse response = joinQueue1v1(a);
        assertThat(response.getErrorCode()).isEqualTo(16001);
        assertThat(response.getQueueTicket()).isEqualTo(first);
        assertThat(fx.queueMembers(queue)).as("不能塞成两份").containsExactly(u(a));

        // matched（已弹出，gather 在途）
        String ticketId = joinQueue1v1(b).getQueueTicket();
        pop("pop-1", 30_000, new TicketRef(a, first), new TicketRef(b, ticketId));
        response = joinQueue1v1(b);
        assertThat(response.getErrorCode()).isEqualTo(16001);
        assertThat(response.getQueueTicket()).isEqualTo(ticketId);
        assertThat(response.getErrorMessage().getParameters(0)).isEqualTo("已在匹配队列中");
        assertThat(fx.rawTicket(b)).containsEntry("state", "matched");
    }

    /** TestCreateTicketIfAbsentIsConditional：票据「不存在才创建」，已存在不得覆盖。 */
    @Test
    void 建票是不存在才创建_已存在不得覆盖() {
        long a = fx.pid(1);
        QueueRef other = fx.track(new QueueRef(5, fx.configId(9)));

        assertThat(store.enqueue(a, "t1", queue, 1, 150_000, 100_000, d())).isInstanceOf(JoinResult.Created.class);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isGreaterThan(0).isLessThanOrEqualTo(100_000);

        assertThat(store.enqueue(a, "t2", other, 2, 150_000, 100_000, d())).as("已存在不得覆盖").isEqualTo(new JoinResult.Exists("t1"));

        assertThat(fx.rawTicket(a)).containsEntry("ticket", "t1").containsEntry("queue_key", queue.queueKey()).containsEntry("zone_id", "1");
        assertThat(fx.queueMembers(other)).isEmpty();
        assertThat(fx.indexed(other)).isFalse();
    }

    /**
     * TestJoinQueueConcurrentDuplicateRejected：并发排队的后来者（读票时还没有票，建票时先到者的票已经在了）回 16001 且带先到者的票号；
     * 队列里只有一份。基线的先到者停在「票已建、尚未入队」；Java 的建票与入队是一段脚本，先到者此刻已经在队列里。
     */
    @Test
    void 并发排队的后来者被拒_回先到者的票号_队列里只有一份() {
        long a = inScene(2, 1);
        TicketStore racing = new ForwardingTicketStore(store) {
            @Override
            public JoinResult enqueue(long playerId, String ticketId, QueueRef into, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
                delegate.enqueue(playerId, "first", into, zoneId, ratingCenti, ttlMs, d);
                return super.enqueue(playerId, ticketId, into, zoneId, ratingCenti, ttlMs, d);
            }
        };

        JoinQueueResponse response = serviceOn(racing).join(a, JoinQueueRequest.newBuilder().setModeValue(MODE_1V1).setBattleConfigId(config).build(),
                d());

        assertThat(response.getErrorCode()).isEqualTo(16001);
        assertThat(response.getQueueTicket()).isEqualTo("first");
        assertThat(fx.rawTicket(a)).containsEntry("ticket", "first");
        assertThat(fx.queueMembers(queue)).as("后来者不得入队").containsExactly(u(a));
    }

    /** 同上，真并发：同一玩家的 8 条排队同时到，恰好一条受理，其余都是 16001 且带同一个票号。 */
    @Test
    void 同一玩家真并发排队_恰好一条受理_其余16001带同一个票号() throws Exception {
        long a = inScene(2, 1);
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JoinQueueResponse>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return joinQueue1v1(a);
                }));
            }
            start.countDown();
            List<JoinQueueResponse> responses = new ArrayList<>();
            for (Future<JoinQueueResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            String winner = ticket(a).ticketId();
            assertThat(responses).filteredOn(r -> r.getErrorCode() == 0).as("恰好一条受理").hasSize(1)
                    .allSatisfy(r -> assertThat(r.getQueueTicket()).isEqualTo(winner));
            assertThat(responses).filteredOn(r -> r.getErrorCode() != 0).hasSize(racers - 1).allSatisfy(r -> {
                assertThat(r.getErrorCode()).isEqualTo(16001);
                assertThat(r.getQueueTicket()).isEqualTo(winner);
            });
            assertThat(fx.queueMembers(queue)).containsExactly(u(a));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * TestRequeueFrontCasBeforePushLeavesNoOrphan：回队首之后票是 queued 就一定在队列里，恢复长 TTL，再被弹出时正常成组。
     * （基线靠「先 CAS 后 LPUSH」的次序保证；Java 一段脚本完成，没有中间态可看。）
     */
    @Test
    void 回队首不留孤儿_票是queued就在队列里_再弹正常成组() {
        long a = inScene(1, 1);
        String ticketId = joinQueue1v1(a).getQueueTicket();
        TicketRef ref = new TicketRef(a, ticketId);
        pop("pop-1", MatchBudgets.matchedTicketTtlSeconds(2) * 1000L, ref);
        assertThat(fx.exists(queue.queueKey())).as("弹出后队列是空的").isFalse();

        assertThat(store.requeueFront(queue, List.of(ref), SIX_HOURS, 0, d())).isEqualTo(1);

        assertThat(ticket(a).state()).isEqualTo(TicketState.QUEUED);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).as("恢复长 TTL").isGreaterThan(MatchBudgets.MAX_MATCHED_TTL_SECONDS * 1000L);
        assertThat(fx.queueMembers(queue)).as("票据 queued 就必须在队列里").containsExactly(u(a));
        // 回队首后再被弹出：票据 queued，正常成组而不是被丢弃
        assertThat(store.pop(queue, fx.token("pop-2"), List.of(ref), 48_000, d())).isInstanceOf(PopResult.Popped.class);
        assertThat(ticket(a).ticketId()).isEqualTo(ticketId);
    }

    /** TestRequeueFrontDeletesTicketWhenPushFails：LPUSH 失败（队列键被占成别的类型）时票不能停在 queued，删票让玩家可以立即重排。 */
    @Test
    void 回队首时入队失败必须删票_不留queued孤儿_玩家可以立即重排() {
        long a = inScene(2, 1);
        String ticketId = joinQueue1v1(a).getQueueTicket();
        pop("pop-1", 48_000, new TicketRef(a, ticketId));
        fx.setString(queue.queueKey(), "not-a-list");

        store.requeueFront(queue, List.of(new TicketRef(a, ticketId)), SIX_HOURS, 0, d());

        assertThat(fx.exists(TicketRedisFixture.ticketKey(a))).as("入队失败必须删票，不留 queued 孤儿").isFalse();
        // 玩家可以立即重排（队列键恢复为 list 之后）
        fx.del(queue.queueKey());
        assertThat(joinQueue1v1(a).getErrorCode()).isZero();
    }

    /** TestExtendMatchedTicketsRefreshesOnlyOwnedTickets：补偿前续期只续票号一致且仍是 matched 的票；续期后的收尾照常把幸存者送回队首。 */
    @Test
    void 补偿前续期只续自己的票_续期后回队首照常() {
        long a = inScene(1, 1);
        long b = inScene(2, 1);
        long missing = fx.pid(3);
        String ticketA = joinQueue1v1(a).getQueueTicket();
        String ticketB = joinQueue1v1(b).getQueueTicket();
        pop("pop-1", 30_000, new TicketRef(a, ticketA), new TicketRef(b, ticketB));
        // 20 s 过去：票据只剩 10 s，补偿（10 人约 40 s）会在中途把票据过期掉
        fx.pexpire(TicketRedisFixture.ticketKey(a), 10_000);
        fx.pexpire(TicketRedisFixture.ticketKey(b), 10_000);
        long compensationMs = MatchBudgets.compensationTtlSeconds(10) * 1000L;

        int extended = store.extendMatched(List.of(new TicketRef(a, ticketA), new TicketRef(b, "stale"), new TicketRef(missing, "missing")),
                compensationMs, d());

        assertThat(extended).isEqualTo(1);
        assertThat(compensationMs).isEqualTo(40_000);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).as("续期后要盖住整个补偿窗口").isGreaterThan(30_000).isLessThanOrEqualTo(compensationMs);
        assertThat(fx.rawTicket(a)).as("续期不改状态").containsEntry("state", "matched");
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(b))).as("票号不一致的不得续期").isLessThanOrEqualTo(10_000);
        assertThat(fx.exists(TicketRedisFixture.ticketKey(missing))).isFalse();

        // 续期后的补偿收尾：35 s 过去，未续期的票此刻已过期；回队首仍能把幸存者送回去
        fx.del(TicketRedisFixture.ticketKey(b));
        store.requeueFront(queue, List.of(new TicketRef(a, ticketA), new TicketRef(b, ticketB)), SIX_HOURS, 0, d());
        assertThat(fx.queueMembers(queue)).containsExactly(u(a));
        assertThat(fx.rawTicket(a)).containsEntry("state", "queued");
        assertThat(fx.exists(TicketRedisFixture.ticketKey(b))).as("过期的票不会被回队首重新造出来").isFalse();
    }

    // ================================================================ ready_ticket_test.go

    /** TestJoinQueueClearsStaleReadyTicket：上一局结束后（战斗锁已删）仍在 TTL 内的 ready 票不得挡住再次排队。 */
    @Test
    void 已结束战斗残留的ready票不得挡住再次排队_签发新票() {
        long a = inScene(1, 1);
        fx.putTicket(a, new Ticket("stale-ready", MODE_1V1, config, TicketState.READY, 1, 0, "", 150_000, 0, 424_242, 0), READY_TTL);

        JoinQueueResponse response = joinQueue1v1(a);

        assertThat(response.getErrorCode()).as("已结束战斗的 ready 票据必须被清掉并放行").isZero();
        assertThat(response.getQueueTicket()).as("必须签发新票据").isNotEqualTo("stale-ready").isNotEmpty();
        Ticket ticket = ticket(a);
        assertThat(ticket.state()).isEqualTo(TicketState.QUEUED);
        assertThat(ticket.ticketId()).isEqualTo(response.getQueueTicket());
        assertThat(ticket.battleId()).as("新票不带旧局的 battle_id").isZero();
    }

    /** TestJoinQueueStillRejectsMatchedTicket：matched（gather 在途）仍然拒绝，不能被「再排一次」抢先删掉。 */
    @Test
    void matched的票仍然拒绝_靠matched的TTL自愈() {
        long a = inScene(2, 2);
        fx.putTicket(a, new Ticket("in-gather", MODE_1V1, config, TicketState.MATCHED, 1, 0, "", 150_000, 0, 0, 0), 48_000);

        JoinQueueResponse response = joinQueue1v1(a);

        assertThat(response.getErrorCode()).isEqualTo(16001);
        assertThat(response.getQueueTicket()).isEqualTo("in-gather");
        assertThat(fx.rawTicket(a)).containsEntry("ticket", "in-gather").containsEntry("state", "matched");
    }

    // ================================================================ crosszone_test.go

    /** TestEnqueueAtomicRegistersQueue：入队把队列键登记进注册集，队尾入队保持先来后到。 */
    @Test
    void 入队登记注册集_队尾入队保持先来后到() {
        long a = fx.pid(1);
        long b = fx.pid(2);

        store.enqueue(a, "t-a", queue, 1, 150_000, SIX_HOURS, d());
        store.enqueue(b, "t-b", queue, 1, 150_000, SIX_HOURS, d());

        assertThat(fx.indexed(queue)).as("队列键必须进注册集").isTrue();
        assertThat(store.queueIndex(d())).contains(queue.queueKey());
        assertThat(fx.queueMembers(queue)).as("RPUSH 保持 FIFO").containsExactly(u(a), u(b));
        assertThat(fx.rankOf(queue)).containsOnlyKeys(u(a), u(b));
    }

    /** TestJoinQueueRejectsWithoutLocation：没有位置记录的玩家被拒，不留票据、不登记队列。 */
    @Test
    void 没有位置的玩家被拒16020_不留票据_不登记队列() {
        long a = fx.pid(1);

        JoinQueueResponse response = joinQueue1v1(a);

        assertThat(response.getErrorCode()).isEqualTo(16020);
        assertThat(response.getErrorMessage().getParameters(0)).isEqualTo("请先进入场景");
        assertThat(response.getQueueTicket()).isEmpty();
        assertThat(fx.ticketOf(a)).as("被拒不能留票据").isEmpty();
        assertThat(fx.indexed(queue)).as("被拒不能登记队列").isFalse();
        assertThat(fx.exists(queue.queueKey())).isFalse();
    }

    /** TestJoinQueueRecordsZoneAndQueueKey：票据记下位置记录里的 zone 与入队的队列键；queued 是长 TTL。 */
    @Test
    void 排队把zone与队列键记进票据_queued是长TTL() {
        long a = inScene(2, 2);
        ratings.set(a, 162_550);
        long before = fx.nowMs();

        JoinQueueResponse response = joinQueue1v1(a);

        long after = fx.nowMs();
        assertThat(response.getErrorCode()).isZero();
        assertThat(response.getQueueTicket()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        Ticket ticket = ticket(a);
        assertThat(ticket.zoneId()).isEqualTo(2);
        assertThat(ticket.state()).isEqualTo(TicketState.QUEUED);
        assertThat(ticket.queueKey()).isEqualTo(queue.queueKey());
        assertThat(ticket.enqueuedAtMs()).as("入队时刻取 Redis TIME").isBetween(before, after);
        assertThat(fx.rawTicket(a)).as("zone_id 必须落盘").containsEntry("zone_id", "2").containsEntry("rating_centi", "162550");
        assertThat(fx.queueMembers(queue)).containsExactly(u(a));
        assertThat(fx.rankOf(queue)).containsEntry(u(a), 162_550L);
        assertThat(fx.indexed(queue)).isTrue();
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).as("长 TTL（queued 态）").isBetween(SIX_HOURS - 10_000, SIX_HOURS);
    }

    /** TestRequeueFrontRestoresQueuedAndLongTTL：回队首恢复 queued + 长 TTL，按票据自己的队列键回队，并重新登记注册集。 */
    @Test
    void 回队首恢复queued与长TTL_重新登记注册集() {
        long a = inScene(1, 1);
        String ticketId = joinQueue1v1(a).getQueueTicket();
        pop("pop-1", MatchBudgets.matchedTicketTtlSeconds(2) * 1000L, new TicketRef(a, ticketId));
        // 弹出后列表清空，注册集被别的实例剔除
        assertThat(store.pruneIfEmpty(queue, d())).isTrue();
        assertThat(fx.indexed(queue)).isFalse();
        QueueRef wrong = fx.track(new QueueRef(MODE_1V1, fx.configId(55)));

        assertThat(store.requeueFront(wrong, List.of(new TicketRef(a, ticketId)), SIX_HOURS, 0, d())).as("票不属于那条队列：不回").isZero();
        assertThat(store.requeueFront(queue, List.of(new TicketRef(a, ticketId)), SIX_HOURS, 0, d())).isEqualTo(1);

        assertThat(ticket(a).state()).isEqualTo(TicketState.QUEUED);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isGreaterThan(MatchBudgets.MAX_MATCHED_TTL_SECONDS * 1000L);
        assertThat(fx.queueMembers(queue)).as("回到票据自己的队列").containsExactly(u(a));
        assertThat(fx.queueMembers(wrong)).isEmpty();
        assertThat(fx.indexed(queue)).as("回队首必须重新登记注册集").isTrue();
        assertThat(fx.indexed(wrong)).isFalse();
    }

    /** TestCancelQueueUsesTicketQueueKey：取消按票据里记下的队列键出队，不按 (mode, config) 重算。 */
    @Test
    void 取消按票据里记下的队列键出队() {
        long a = fx.pid(1);
        // 票里的队列键故意与 (mode, config) 重算出来的不同
        QueueRef recorded = fx.track(new QueueRef(MODE_1V1, fx.configId(77)));
        fx.putTicket(a, new Ticket("tk", MODE_1V1, config, TicketState.QUEUED, fx.nowMs(), 1, recorded.queueKey(), 150_000, 0, 0, 0), SIX_HOURS);
        fx.putQueueMember(recorded, u(a), 150_000L);

        assertThat(service.cancel(a, "tk", d())).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(fx.exists(TicketRedisFixture.ticketKey(a))).isFalse();
        assertThat(fx.exists(recorded.queueKey())).as("玩家应从票据记录的队列里出队").isFalse();
        assertThat(fx.exists(recorded.rankKey())).as("评分镜像一并摘掉").isFalse();
    }

    // ================================================================ ticket_cas_test.go

    /**
     * TestTicketCasRejectsStaleGatherWrites：玩家被弹出（matched，旧票）、matched TTL 到期票据消失、玩家重排拿到新票之后，
     * 旧 gather 迟到的置 matched / 置 ready / 回队首 / 删票都不动新票。
     */
    @Test
    void 票据被替换后_旧gather迟到的写都不动新票() {
        long a = inScene(1, 1);
        String oldTicket = joinQueue1v1(a).getQueueTicket();
        TicketRef stale = new TicketRef(a, oldTicket);
        pop("pop-1", 48_000, stale);
        // matched TTL 到期，票据自灭；玩家重排拿到新票
        fx.del(TicketRedisFixture.ticketKey(a));
        String newTicket = joinQueue1v1(a).getQueueTicket();
        assertThat(newTicket).isNotEmpty().isNotEqualTo(oldTicket);
        assertThat(fx.queueMembers(queue)).containsExactly(u(a));
        String lateToken = fx.token("pop-late");
        Object untouched = fx.dump();

        // 旧 gather 迟到：再弹一次（置 matched）→ 拒
        assertThat(store.pop(queue, lateToken, List.of(stale), 30_000, d())).isEqualTo(new PopResult.Invalid(List.of(a)));
        // 旧 gather 成功收尾：ready → 拒
        assertThat(store.markReady(stale, 987_654_321L, READY_TTL, d())).isFalse();
        // 旧 gather 失败收尾：续期、回队首 → 不入队不改票
        assertThat(store.extendMatched(List.of(stale), 40_000, d())).isZero();
        assertThat(store.requeueFront(queue, List.of(stale), SIX_HOURS, 2000, d())).isZero();
        // 旧 gather 判定他是肇事者删票 → 拒
        assertThat(store.delete(stale, d())).isFalse();
        assertThat(store.deleteGroup(List.of(stale), d())).isZero();

        assertThat(fx.dump()).as("新票与队列逐字节没动").isEqualTo(untouched);
        Ticket ticket = ticket(a);
        assertThat(ticket.ticketId()).isEqualTo(newTicket);
        assertThat(ticket.state()).isEqualTo(TicketState.QUEUED);
        assertThat(fx.rawTicket(a)).doesNotContainKeys("battle_id", "not_before_ms");
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).as("TTL 不能被 ready 收紧").isGreaterThan(READY_TTL + 1000);
        assertThat(fx.queueMembers(queue)).as("队列里不能塞成两份").containsExactly(u(a));
    }

    /**
     * TestTicketCasAcceptsMatchingTicket：票号一致时 ready 同时写 state + battle_id 并收紧 TTL；回队首恢复 queued + 长 TTL；删票生效。
     * 基线从 ready 直接回队首；Java 的回队首要求票是 matched（ready 之后不会再失败回队），所以这里各走一张票。
     */
    @Test
    void 票号一致时_置ready写状态与battle_id并收紧TTL_回队首恢复_删票生效() {
        long a = inScene(2, 2);
        long b = inScene(3, 2);
        String ticketA = joinQueue1v1(a).getQueueTicket();
        String ticketB = joinQueue1v1(b).getQueueTicket();
        TicketRef refA = new TicketRef(a, ticketA);
        TicketRef refB = new TicketRef(b, ticketB);
        pop("pop-1", MatchBudgets.matchedTicketTtlSeconds(2) * 1000L, refA, refB);

        assertThat(store.markReady(refA, 424_242, READY_TTL, d())).isTrue();
        assertThat(ticket(a).state()).isEqualTo(TicketState.READY);
        assertThat(fx.rawTicket(a)).containsEntry("battle_id", "424242");
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isGreaterThan(0).isLessThanOrEqualTo(READY_TTL);
        assertThat(store.requeueFront(queue, List.of(refA), SIX_HOURS, 0, d())).as("ready 的票不回队首").isZero();
        assertThat(ticket(a).state()).isEqualTo(TicketState.READY);

        assertThat(store.requeueFront(queue, List.of(refB), SIX_HOURS, 0, d())).isEqualTo(1);
        assertThat(ticket(b).state()).isEqualTo(TicketState.QUEUED);
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(b))).isGreaterThan(READY_TTL);
        assertThat(fx.queueMembers(queue)).containsExactly(u(b));

        assertThat(store.delete(refA, d())).isTrue();
        assertThat(fx.exists(TicketRedisFixture.ticketKey(a))).isFalse();
    }

    // ================================================================ 真 Redis 上的入口全流程（Java 新增）

    @Test
    void 排队_查状态_取消_查状态_全流程_等待秒数按Redis的时间算() {
        long a = inScene(1, 1);
        assertThat(service.status(a, d())).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(5).build());

        String ticketId = joinQueue1v1(a).getQueueTicket();
        GetQueueStatusResponse fresh = service.status(a, d());
        assertThat(fresh.getState()).as("刚入队：读得到自己刚写的票").isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(fresh.getQueuedSeconds()).as("刚入队，没等几秒").isLessThanOrEqualTo(5);

        // 已经等了 65 秒：改写票里的入队时刻（真 Redis 拨不动时钟）
        fx.setField(a, "enqueued_at_ms", Long.toString(fx.nowMs() - 65_000));
        GetQueueStatusResponse waited = service.status(a, d());
        assertThat(waited.getState()).isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(waited.getQueuedSeconds()).isBetween(65, 67);
        assertThat(waited.getEstimatedWaitSeconds()).isZero();

        // 入队时刻比 Redis 的时间还晚（时钟偏斜）：夹到 0
        fx.setField(a, "enqueued_at_ms", Long.toString(fx.nowMs() + 3_600_000));
        assertThat(service.status(a, d()).getQueuedSeconds()).isZero();

        assertThat(service.cancel(a, "stale", d())).isEqualTo(CancelOutcome.TICKET_MISMATCH);
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(service.cancel(a, ticketId, d())).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);
        assertThat(fx.exists(queue.queueKey())).isFalse();
        assertThat(service.cancel(a, ticketId, d())).as("重复取消").isEqualTo(CancelOutcome.NO_TICKET);
    }

    @Test
    void 查状态的五态映射走真Redis_matched与ready() {
        long a = inScene(1, 1);
        String ticketId = joinQueue1v1(a).getQueueTicket();
        TicketRef ref = new TicketRef(a, ticketId);

        pop("pop-1", 48_000, ref);
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_MATCHED);
        store.markReady(ref, 424_242, READY_TTL, d());
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_READY);
        fx.setField(a, "state", "entering");
        assertThat(service.status(a, d()).getStateValue()).as("不认识的状态按 NOT_QUEUED").isEqualTo(5);
    }

    @Test
    void PVE_SOLO走真Redis_matched票42秒_不入队_gather已交出() {
        long a = inScene(1, 3);
        int soloConfig = fx.configId(1);

        JoinQueueResponse response = service.join(a, JoinQueueRequest.newBuilder().setModeValue(MODE_PVE_SOLO).setBattleConfigId(soloConfig).build(),
                d());

        assertThat(response.getErrorCode()).isZero();
        assertThat(fx.rawTicket(a)).containsEntry("ticket", response.getQueueTicket()).containsEntry("state", "matched").containsEntry("queue_key", "")
                .containsEntry("mode", "4").containsEntry("zone_id", "3").containsEntry("config", Integer.toUnsignedString(soloConfig));
        assertThat(fx.pttl(TicketRedisFixture.ticketKey(a))).isBetween(37_000L, 42_000L);
        assertThat(gather.plans).singleElement().satisfies(plan -> {
            assertThat(plan.members()).containsExactly(a);
            assertThat(plan.tickets()).containsEntry(a, response.getQueueTicket());
        });
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_MATCHED);
        // gather 失败的收尾是按票号删票：之后查状态是 NOT_QUEUED，可以立即再排
        assertThat(store.deleteGroup(gather.plans.get(0).ticketRefs(), d())).isEqualTo(1);
        assertThat(service.status(a, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);
    }
}
