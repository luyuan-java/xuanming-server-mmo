package com.game.match.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.MatchProperties;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.queue.QueueService.CancelOutcome;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FixedRatingReader;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.DefaultTicketHealing;
import com.game.match.ticket.ForwardingTicketStore;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 排队的三个入口 157 / 148 / 153（match-spec §2.1–§2.4、§15.2 的 {@code QueueServiceTest} 一条）：§2.2 判定表的每一行（应答逐字节、
 * {@code queue_ticket}、指标 outcome）、判定顺序、身份只认会话、PVE_SOLO 的前置闸、建票结局不明的回滚；取消的每一种静默成功；
 * 查状态的五态映射与等待秒数。依赖全部是替身：内存票据存储、战斗锁 / 位置、评分、gather。
 *
 * <p>期望的应答在这里用字面量另拼一遍（码与中文串都不经被测代码的常量），再比序列化后的字节。
 */
class QueueServiceTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final int MODE_5V5 = 1;
    private static final int MODE_3V3 = 2;
    private static final int MODE_1V1 = 3;
    private static final int MODE_PVE_SOLO = 4;
    private static final int MODE_PVE_TEAM = 5;
    private static final int MODE_CHALLENGE = 6;
    private static final QueueRef Q_1V1 = new QueueRef(MODE_1V1, 0);
    private static final long SIX_HOURS = 21_600_000;
    private static final String BUSY = "服务器繁忙,请稍后再试";

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore store = new InMemoryTicketStore(clock);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FixedRatingReader ratings = new FixedRatingReader();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);
    private final MatchIds ids = new MatchIds(new Snowflake(7), leaseValid::get, leaseLost::get);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> id >= 1 && id <= 3));
    private final MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    private final AtomicInteger ticketSeq = new AtomicInteger();
    private final QueueService service = serviceOn(store);

    private QueueService serviceOn(TicketStore tickets) {
        return serviceOn(tickets, gather);
    }

    private QueueService serviceOn(TicketStore tickets, GatherLauncher launcher) {
        return new QueueService(props, players, tickets, new DefaultTicketHealing(tickets), ratings, launcher, ids, metrics,
                () -> "ticket-" + ticketSeq.incrementAndGet());
    }

    private static Deadline d() {
        return Deadline.after(2000);
    }

    private static JoinQueueRequest request(int mode, int configId) {
        return JoinQueueRequest.newBuilder().setModeValue(mode).setBattleConfigId(configId).build();
    }

    private JoinQueueResponse join(long playerId, int mode, int configId) {
        return service.join(playerId, request(mode, configId), d());
    }

    /** 拒绝应答的期望形状：error_code 与 error_message.id 同值、parameters[0] 是写死的中文串、queue_ticket 为空。 */
    private static JoinQueueResponse rejected(int code, String text) {
        return JoinQueueResponse.newBuilder().setErrorCode(code).setErrorMessage(TipInfoMessage.newBuilder().setId(code).addParameters(text)).build();
    }

    private static JoinQueueResponse alreadyQueued(String ticket) {
        return JoinQueueResponse.newBuilder().setErrorCode(16001).setQueueTicket(ticket)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16001).addParameters("已在匹配队列中")).build();
    }

    private static void assertBytes(JoinQueueResponse actual, JoinQueueResponse expected) {
        assertThat(actual.toByteString()).as("应答逐字节：%s", actual).isEqualTo(expected.toByteString());
    }

    private double joined(String mode, String outcome) {
        return meters.counter("xm.match.join.queue", "mode", mode, "outcome", outcome).count();
    }

    private void inScene(long playerId) {
        players.online(playerId, 1, 7);
    }

    // ================================================================ 157：§2.2 判定表逐行

    @Test
    void 第1行_身份为0_16004缺少玩家身份_什么都不读() {
        JoinQueueResponse response = join(0, MODE_1V1, 0);

        assertBytes(response, rejected(16004, "缺少玩家身份"));
        assertThat(response.getErrorCode()).isEqualTo(response.getErrorMessage().getId());
        assertThat(response.getQueueTicket()).isEmpty();
        assertThat(players.reads).isEmpty();
        assertThat(store.calls).isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(1);
    }

    @Test
    void 第2a行_PVE组队且该副本没配人数_16003() {
        inScene(A);

        JoinQueueResponse response = join(A, MODE_PVE_TEAM, 2);

        assertBytes(response, rejected(16003, "该副本未开放组队"));
        assertThat(players.reads).as("模式判定先于一切读").isEmpty();
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(joined("MATCH_MODE_PVE_TEAM", "no_team_size")).isEqualTo(1);
    }

    @Test
    void 第2b行_3V3_切磋_未指定_契约里没有的值_16002() {
        inScene(A);

        for (int mode : new int[] {MODE_3V3, MODE_CHALLENGE, 0, 99, -1}) {
            assertBytes(join(A, mode, 0), rejected(16002, "该匹配模式未开放"));
        }

        assertThat(players.reads).isEmpty();
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(joined("MATCH_MODE_3V3", "mode_not_open")).isEqualTo(1);
        assertThat(joined("MATCH_MODE_PVP_CHALLENGE", "mode_not_open")).isEqualTo(1);
        assertThat(joined("MATCH_MODE_UNSPECIFIED", "mode_not_open")).isEqualTo(1);
        assertThat(joined("unknown", "mode_not_open")).as("契约里没有的值不进标签（修基线 B2）").isEqualTo(2);
    }

    @Test
    void 第3行_读战斗锁出错_16004服务器繁忙_不放行也不往下读() {
        inScene(A);
        players.failLock(A);

        JoinQueueResponse response = join(A, MODE_1V1, 0);

        assertBytes(response, rejected(16004, BUSY));
        assertThat(response.getErrorMessage().getParameters(0).getBytes(StandardCharsets.UTF_8)).as("半角逗号 0x2C").contains((byte) 0x2C);
        assertThat(players.reads).containsExactly("lock:1001");
        assertThat(store.calls).as("fail-closed：没有读票").isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(1);
    }

    @Test
    void 第4行_有战斗锁_16000() {
        inScene(A);
        players.inBattle(A, true);

        assertBytes(join(A, MODE_1V1, 0), rejected(16000, "战斗尚未结束,无法排队"));

        assertThat(store.calls).isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "in_battle")).isEqualTo(1);
    }

    @Test
    void 第5行_读票出错_自愈出错_16004_不去读位置() {
        inScene(A);
        store.faults.failNext("read");
        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));
        assertThat(players.reads).as("读票失败就停，不读位置").containsExactly("lock:1001");

        store.putTicket(A, new Ticket("stale-ready", MODE_1V1, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);
        store.faults.failNext("heal");
        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).as("自愈没做成：票还在").isPresent();
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(2);
    }

    @Test
    void 第6行_已有在途的票_16001_带现有票号_队列里不会变成两份() {
        inScene(A);
        JoinQueueResponse first = join(A, MODE_1V1, 0);

        JoinQueueResponse again = join(A, MODE_1V1, 0);
        JoinQueueResponse otherMode = join(A, MODE_5V5, 7);

        assertThat(first.getQueueTicket()).isEqualTo("ticket-1");
        assertBytes(again, alreadyQueued("ticket-1"));
        assertBytes(otherMode, alreadyQueued("ticket-1"));
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
        assertThat(store.queueMembers(new QueueRef(MODE_5V5, 7))).isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "already_queued")).isEqualTo(1);
        assertThat(joined("MATCH_MODE_5V5", "already_queued")).isEqualTo(1);
    }

    @Test
    void 第6行_matched的票仍然拒绝_不能被再排一次抢先删掉() {
        inScene(A);
        store.createMatched(A, "in-gather", MODE_1V1, 0, 1, 150_000, 48_000, d());

        assertBytes(join(A, MODE_1V1, 0), alreadyQueued("in-gather"));

        assertThat(store.ticketOf(A).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
    }

    @Test
    void 第7行_读位置出错_16004() {
        inScene(A);
        players.failLocation(A);

        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));

        assertThat(players.reads).containsExactly("lock:1001", "location:1001");
        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(1);
    }

    @Test
    void 第8行_没有在线的位置_16020_被拒不留票据也不登记队列() {
        for (LocationStatus status : new LocationStatus[] {LocationStatus.MISSING, LocationStatus.RECONNECT_LEASE, LocationStatus.LOGGED_OUT}) {
            players.location(A, status);

            assertBytes(join(A, MODE_1V1, 0), rejected(16020, "请先进入场景"));
        }

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueIndex(d())).isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "not_in_scene")).isEqualTo(3);
    }

    @Test
    void 第9行_建票在执行前失败_16004_什么都没留下() {
        inScene(A);
        store.faults.failNext("enqueue");

        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
        assertThat(store.calls).as("结局不明：按本次票号回滚一次").containsSubsequence("enqueue(1001)", "cancel(1001)");
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(1);
    }

    @Test
    void 第9行_建票已生效但应答丢了_按本次票号回滚_玩家没有被悄悄留在队列里() {
        inScene(A);
        store.faults.failNext("enqueue:after");

        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).as("回滚：票已删").isEmpty();
        assertThat(store.queueMembers(Q_1V1)).as("回滚：队列项已摘").isEmpty();
        assertThat(store.rankOf(Q_1V1)).isEmpty();
        assertBytes(join(A, MODE_1V1, 0), JoinQueueResponse.newBuilder().setQueueTicket("ticket-2").build());
    }

    @Test
    void 第9行_回滚也失败_照回16004_留下的票下次排队时回16001_状态自洽() {
        inScene(A);
        store.faults.failNext("enqueue:after");
        store.faults.failNext("cancel");

        assertBytes(join(A, MODE_1V1, 0), rejected(16004, BUSY));

        assertThat(store.ticketOf(A).orElseThrow().ticketId()).isEqualTo("ticket-1");
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
        assertBytes(join(A, MODE_1V1, 0), alreadyQueued("ticket-1"));
    }

    @Test
    void 第10行_并发重复建票_后到的一方16001_带赢家的票号_队列里只有一份() {
        inScene(A);
        // 后到的一方读票时还没有票（自愈放行），建票时赢家的票已经在了
        TicketStore racing = new ForwardingTicketStore(store) {
            @Override
            public JoinResult enqueue(long playerId, String ticketId, QueueRef queue, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
                delegate.enqueue(playerId, "winner", queue, zoneId, ratingCenti, ttlMs, d);
                return super.enqueue(playerId, ticketId, queue, zoneId, ratingCenti, ttlMs, d);
            }
        };

        JoinQueueResponse response = serviceOn(racing).join(A, request(MODE_1V1, 0), d());

        assertBytes(response, alreadyQueued("winner"));
        assertThat(store.ticketOf(A).orElseThrow().ticketId()).isEqualTo("winner");
        assertThat(store.queueMembers(Q_1V1)).as("后来者不得入队").containsExactly("1001");
        assertThat(joined("MATCH_MODE_1V1", "already_queued")).isEqualTo(1);
    }

    @Test
    void 受理_error_code为0_不带error_message_票是queued_记下zone与评分_长TTL() {
        players.online(A, 2, 7);
        ratings.set(A, 162_550);

        JoinQueueResponse response = join(A, MODE_1V1, 0);

        assertBytes(response, JoinQueueResponse.newBuilder().setQueueTicket("ticket-1").build());
        assertThat(response.hasErrorMessage()).as("成功应答里绝不能出现 error_message").isFalse();
        assertThat(response.getErrorCode()).isZero();
        assertThat(store.ticketOf(A)).contains(new Ticket("ticket-1", MODE_1V1, 0, TicketState.QUEUED, clock.peekMs(), 2, "xm:{match}:queue:3:0",
                162_550, 0, 0, 0));
        assertThat(store.ttlMs(A)).isEqualTo(SIX_HOURS);
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
        assertThat(store.rankOf(Q_1V1)).containsEntry("1001", 162_550L);
        assertThat(store.indexed(Q_1V1)).isTrue();
        assertThat(ratings.loads).containsExactly(List.of(A));
        assertThat(gather.plans).as("走队列的模式不直接开局").isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "ok")).isEqualTo(1);
    }

    @Test
    void 受理_票号是UUIDv4小写带连字符_每次不同() {
        inScene(A);
        inScene(B);
        QueueService real = new QueueService(props, players, store, new DefaultTicketHealing(store), ratings, gather, ids, metrics);

        String first = real.join(A, request(MODE_1V1, 0), d()).getQueueTicket();
        String second = real.join(B, request(MODE_1V1, 0), d()).getQueueTicket();

        assertThat(first).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        assertThat(second).matches("^[0-9a-f-]{36}$").isNotEqualTo(first);
    }

    // ================================================================ 157：判定顺序（顺序本身就是语义）

    @Test
    void 顺序_在战且模式未开放_回16002() {
        inScene(A);
        players.inBattle(A, true);

        assertBytes(join(A, MODE_3V3, 0), rejected(16002, "该匹配模式未开放"));
    }

    @Test
    void 顺序_PVE组队未配置且在战_回16003() {
        inScene(A);
        players.inBattle(A, true);

        assertBytes(join(A, MODE_PVE_TEAM, 3), rejected(16003, "该副本未开放组队"));
    }

    @Test
    void 顺序_在战且有残留的ready票_回16000_票不动_自愈的前提是没有战斗锁() {
        inScene(A);
        players.inBattle(A, true);
        store.putTicket(A, new Ticket("ready-in-battle", MODE_1V1, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        assertBytes(join(A, MODE_1V1, 0), rejected(16000, "战斗尚未结束,无法排队"));

        assertThat(store.ticketOf(A).orElseThrow().ticketId()).isEqualTo("ready-in-battle");
    }

    @Test
    void 顺序_在途票且无位置_回16001() {
        inScene(A);
        join(A, MODE_1V1, 0);
        players.location(A, LocationStatus.MISSING);

        assertBytes(join(A, MODE_1V1, 0), alreadyQueued("ticket-1"));

        assertThat(players.reads).as("第二次请求只读了战斗锁，没走到位置").containsExactly("lock:1001", "location:1001", "lock:1001");
    }

    @Test
    void 顺序_ready残留加无位置_先自愈再回16020_残留票已经被清掉() {
        players.location(A, LocationStatus.MISSING);
        store.putTicket(A, new Ticket("stale-ready", MODE_1V1, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        assertBytes(join(A, MODE_1V1, 0), rejected(16020, "请先进入场景"));

        assertThat(store.ticketOf(A)).as("自愈先于读位置").isEmpty();
        assertThat(service.status(A, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);
    }

    @Test
    void 顺序_各步的读按战斗锁_票_位置_建票的次序() {
        inScene(A);

        join(A, MODE_1V1, 0);

        assertThat(players.reads).containsExactly("lock:1001", "location:1001");
        assertThat(store.calls).containsExactly("read(1001)", "enqueue(1001)");
    }

    // ================================================================ 157：自愈

    @Test
    void 已结束战斗残留的ready票被清掉并放行_签发新票() {
        inScene(A);
        store.putTicket(A, new Ticket("stale-ready", MODE_1V1, 0, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        JoinQueueResponse response = join(A, MODE_1V1, 0);

        assertThat(response.getErrorCode()).isZero();
        assertThat(response.getQueueTicket()).isEqualTo("ticket-1");
        assertThat(store.ticketOf(A).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
    }

    @Test
    void 孤儿queued票被清掉_按本次请求入队_不是把旧票补回旧队列() {
        inScene(A);
        store.putTicket(A, new Ticket("orphan", MODE_1V1, 0, TicketState.QUEUED, clock.peekMs() - 60_000, 1, Q_1V1.queueKey(), 150_000, 0, 0, 0),
                SIX_HOURS);

        JoinQueueResponse response = join(A, MODE_5V5, 7);

        assertThat(response.getErrorCode()).isZero();
        Ticket ticket = store.ticketOf(A).orElseThrow();
        assertThat(ticket.ticketId()).isEqualTo("ticket-1");
        assertThat(ticket.mode()).isEqualTo(MODE_5V5);
        assertThat(ticket.queueKey()).isEqualTo("xm:{match}:queue:1:7");
        assertThat(store.queueMembers(new QueueRef(MODE_5V5, 7))).containsExactly("1001");
        assertThat(store.queueMembers(Q_1V1)).as("旧队列不能被塞回去").isEmpty();
    }

    // ================================================================ 157：身份只认会话、请求里的无关字段

    @Test
    void 请求体里的player_id_zone_map_预组队成员全部忽略() {
        players.online(A, 2, 7);
        inScene(B);
        JoinQueueRequest forged = JoinQueueRequest.newBuilder().setPlayerId(B).setModeValue(MODE_1V1).setZoneId(9).setMapConfigId(55)
                .addPartyMemberIds(B).addPartyMemberIds(1003).build();

        JoinQueueResponse response = service.join(A, forged, d());

        assertThat(response.getErrorCode()).isZero();
        assertThat(store.ticketOf(A).orElseThrow().zoneId()).as("zone 取位置记录，不取请求").isEqualTo(2);
        assertThat(store.ticketOf(B)).as("不能替别人排队").isEmpty();
        assertThat(store.queueMembers(Q_1V1)).as("预组队成员不入队").containsExactly("1001");
    }

    @Test
    void battle_config_id不校验_每个不同的值一条队列_指标标签净化() {
        inScene(A);
        inScene(B);

        assertThat(join(A, MODE_1V1, 0xFFFF_FFFF).getErrorCode()).isZero();
        assertThat(join(B, MODE_1V1, 424242).getErrorCode()).isZero();

        assertThat(store.ticketOf(A).orElseThrow().queueKey()).isEqualTo("xm:{match}:queue:3:4294967295");
        assertThat(store.ticketOf(B).orElseThrow().queueKey()).isEqualTo("xm:{match}:queue:3:424242");
        assertThat(store.queueIndex(d())).containsExactlyInAnyOrder("xm:{match}:queue:3:4294967295", "xm:{match}:queue:3:424242");
        assertThat(joined("MATCH_MODE_1V1", "ok")).isEqualTo(2);
    }

    @Test
    void 五V五与配置了人数的PVE组队照常入队_评分读不到按1500() {
        inScene(A);
        inScene(B);

        assertThat(join(A, MODE_5V5, 0).getErrorCode()).isZero();
        assertThat(join(B, MODE_PVE_TEAM, 1).getErrorCode()).isZero();

        assertThat(store.queueMembers(new QueueRef(MODE_5V5, 0))).containsExactly("1001");
        assertThat(store.queueMembers(new QueueRef(MODE_PVE_TEAM, 1))).containsExactly("1002");
        assertThat(store.ticketOf(A).orElseThrow().ratingCenti()).isEqualTo(150_000);
        assertThat(store.rankOf(new QueueRef(MODE_PVE_TEAM, 1))).containsEntry("1002", 150_000L);
    }

    // ================================================================ 157：PVE_SOLO

    @Test
    void PVE_SOLO_不入队_直接建matched票42秒_把gather交出去不等结果() {
        players.online(A, 3, 7);
        gather.hold();

        JoinQueueResponse response = join(A, MODE_PVE_SOLO, 1);

        assertBytes(response, JoinQueueResponse.newBuilder().setQueueTicket("ticket-1").build());
        assertThat(store.ticketOf(A)).contains(new Ticket("ticket-1", MODE_PVE_SOLO, 1, TicketState.MATCHED, clock.peekMs(), 3, "", 150_000, 0, 0, 0));
        assertThat(store.ttlMs(A)).isEqualTo(42_000).isEqualTo(MatchBudgets.matchedTicketTtlSeconds(1) * 1000L);
        assertThat(store.queueIndex(d())).as("不入队").isEmpty();
        assertThat(ratings.loads).as("PVE_SOLO 不读评分").isEmpty();
        assertThat(gather.plans).singleElement().satisfies(plan -> {
            assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_SOLO);
            assertThat(plan.battleConfigId()).isEqualTo(1);
            assertThat(plan.members()).containsExactly(A);
            assertThat(plan.tickets()).isEqualTo(Map.of(A, "ticket-1"));
            assertThat(plan.onFail()).isEqualTo(FailPolicy.DELETE_ALL);
            assertThat(plan.presetBattleId()).isZero();
        });
        assertThat(gather.futures.get(0)).as("应答先回，gather 还在途").isNotDone();
        assertThat(joined("MATCH_MODE_PVE_SOLO", "ok")).isEqualTo(1);
    }

    @Test
    void PVE_SOLO_副本号不校验_gather失败也不影响已经回出去的受理() {
        inScene(A);
        gather.nextResult(GatherResult.failed(GatherOutcome.NO_BATTLE_NODE, 0));

        JoinQueueResponse response = join(A, MODE_PVE_SOLO, 999);

        assertThat(response.getErrorCode()).isZero();
        assertThat(gather.plans).singleElement().satisfies(plan -> assertThat(plan.battleConfigId()).isEqualTo(999));
    }

    @Test
    void PVE_SOLO_发号租约此刻无效_16004_不建票不开局_走队列的模式照常入队() {
        inScene(A);
        inScene(B);
        leaseValid.set(false);

        assertBytes(join(A, MODE_PVE_SOLO, 1), rejected(16004, BUSY));
        JoinQueueResponse queued = join(B, MODE_1V1, 0);

        assertThat(store.ticketOf(A)).as("不建票").isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(joined("MATCH_MODE_PVE_SOLO", "internal")).isEqualTo(1);
        assertThat(queued.getErrorCode()).as("续期滞后会自愈：1V1 照常入队，恢复后照常成局").isZero();
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1002");
    }

    @Test
    void PVE_SOLO_gather在途许可已满_16004_不建票_指标记overloaded() {
        inScene(A);
        gather.permits(0);

        assertBytes(join(A, MODE_PVE_SOLO, 1), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(joined("MATCH_MODE_PVE_SOLO", "overloaded")).isEqualTo(1);
    }

    @Test
    void PVE_SOLO的前置闸排在第8步之后_没有位置仍回16020_残留票仍被自愈() {
        players.location(A, LocationStatus.MISSING);
        leaseValid.set(false);
        gather.permits(0);
        store.putTicket(A, new Ticket("stale-ready", MODE_PVE_SOLO, 1, TicketState.READY, 1, 1, "", 150_000, 0, 77, 0), 60_000);

        assertBytes(join(A, MODE_PVE_SOLO, 1), rejected(16020, "请先进入场景"));

        assertThat(store.ticketOf(A)).isEmpty();
    }

    @Test
    void PVE_SOLO_建票结局不明_按本次票号回滚_不开局() {
        inScene(A);
        store.faults.failNext("createMatched:after");

        assertBytes(join(A, MODE_PVE_SOLO, 1), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).as("回滚：不留一张没人收尾的 matched 票").isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(store.calls).containsExactly("read(1001)", "createMatched(1001)", "delete(1001)");
    }

    @Test
    void PVE_SOLO_并发重复_后到的一方16001_不开第二次局() {
        inScene(A);
        TicketStore racing = new ForwardingTicketStore(store) {
            @Override
            public JoinResult createMatched(long playerId, String ticketId, int mode, int configId, int zoneId, long ratingCenti, long ttlMs, Deadline d) {
                delegate.createMatched(playerId, "winner", mode, configId, zoneId, ratingCenti, ttlMs, d);
                return super.createMatched(playerId, ticketId, mode, configId, zoneId, ratingCenti, ttlMs, d);
            }
        };

        assertBytes(serviceOn(racing).join(A, request(MODE_PVE_SOLO, 1), d()), alreadyQueued("winner"));

        assertThat(gather.plans).isEmpty();
    }

    @Test
    void PVE_SOLO_交出gather时意外抛异常_删票并回16004_不留matched票() {
        inScene(A);
        GatherLauncher broken = new GatherLauncher() {
            @Override
            public CompletableFuture<GatherResult> launch(GatherPlan plan) {
                throw new IllegalStateException("违反约定的 launch");
            }

            @Override
            public int availablePermits() {
                return 1;
            }

            @Override
            public boolean awaitIdle(Duration timeout) {
                return true;
            }
        };

        assertBytes(serviceOn(store, broken).join(A, request(MODE_PVE_SOLO, 1), d()), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).isEmpty();
    }

    // ================================================================ 157：发号租约已丢失（lead 裁决 2）

    @Test
    void 租约已丢失_任何模式都回16004_不建票不入队_判定仍排在第8步之后() {
        inScene(A);
        leaseLost.set(true);
        leaseValid.set(false);

        for (int mode : new int[] {MODE_1V1, MODE_5V5, MODE_PVE_SOLO}) {
            assertBytes(join(A, mode, 0), rejected(16004, BUSY));
        }
        assertBytes(join(A, MODE_PVE_TEAM, 1), rejected(16004, BUSY));

        assertThat(store.ticketOf(A)).as("票入队后永不成局：不收").isEmpty();
        assertThat(store.queueIndex(d())).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(ratings.loads).as("拒在读评分之前").isEmpty();
        assertThat(joined("MATCH_MODE_1V1", "internal")).isEqualTo(1);
        // 前 8 步的先后不变
        assertBytes(join(A, MODE_3V3, 0), rejected(16002, "该匹配模式未开放"));
        players.inBattle(A, true);
        assertBytes(join(A, MODE_1V1, 0), rejected(16000, "战斗尚未结束,无法排队"));
        players.inBattle(A, false).location(A, LocationStatus.MISSING);
        assertBytes(join(A, MODE_1V1, 0), rejected(16020, "请先进入场景"));
    }

    @Test
    void 租约已丢失_取消与查状态不受影响() {
        inScene(A);
        join(A, MODE_1V1, 0);
        leaseLost.set(true);
        leaseValid.set(false);

        assertThat(service.status(A, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(service.cancel(A, "", d())).isEqualTo(CancelOutcome.CANCELLED);
        assertThat(service.status(A, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);
    }

    // ================================================================ 148 CancelQueue

    @Test
    void 取消_身份为0或没有票_静默成功() {
        assertThat(service.cancel(0, "", d())).isEqualTo(CancelOutcome.NO_IDENTITY);
        assertThat(store.calls).as("身份为 0 不读票").isEmpty();

        assertThat(service.cancel(A, "whatever", d())).isEqualTo(CancelOutcome.NO_TICKET);
    }

    @Test
    void 取消_带当前票号_删票并从队列与镜像里摘掉() {
        inScene(A);
        inScene(B);
        String ticket = join(A, MODE_1V1, 0).getQueueTicket();
        join(B, MODE_1V1, 0);

        assertThat(service.cancel(A, ticket, d())).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1002");
        assertThat(store.rankOf(Q_1V1)).containsOnlyKeys("1002");
        assertThat(service.status(A, d()).getState()).isEqualTo(QueueState.QUEUE_STATE_NOT_QUEUED);
        assertThat(join(A, MODE_1V1, 0).getErrorCode()).as("取消后可以立即重排").isZero();
    }

    @Test
    void 取消_空串表示取消我当前那张票() {
        inScene(A);
        join(A, MODE_1V1, 0);

        assertThat(service.cancel(A, "", d())).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(Q_1V1)).isEmpty();
    }

    @Test
    void 取消_票号不符_静默成功_不动当前的排队() {
        inScene(A);
        join(A, MODE_1V1, 0);

        assertThat(service.cancel(A, "stale", d())).isEqualTo(CancelOutcome.TICKET_MISMATCH);

        assertThat(store.ticketOf(A).orElseThrow().state()).isEqualTo(TicketState.QUEUED);
        assertThat(store.queueMembers(Q_1V1)).containsExactly("1001");
        assertThat(store.calls).as("只读了一次票，没有任何写").endsWith("read(1001)");
    }

    @Test
    void 取消_matched或ready之后太迟_静默成功_票不动() {
        inScene(A);
        String ticket = join(A, MODE_1V1, 0).getQueueTicket();
        store.pop(Q_1V1, "pop-1", List.of(new TicketRef(A, ticket)), 48_000, d());

        assertThat(service.cancel(A, ticket, d())).isEqualTo(CancelOutcome.TOO_LATE);
        assertThat(service.cancel(A, "", d())).isEqualTo(CancelOutcome.TOO_LATE);
        assertThat(store.ticketOf(A).orElseThrow().state()).isEqualTo(TicketState.MATCHED);

        store.markReady(new TicketRef(A, ticket), 77, 60_000, d());
        assertThat(service.cancel(A, ticket, d())).isEqualTo(CancelOutcome.TOO_LATE);
        assertThat(store.ticketOf(A).orElseThrow().state()).isEqualTo(TicketState.READY);
    }

    @Test
    void 取消_读到queued之后被弹走_存储层保证取消太迟_票不被删() {
        inScene(A);
        String ticket = join(A, MODE_1V1, 0).getQueueTicket();
        TicketStore racing = new ForwardingTicketStore(store) {
            @Override
            public boolean cancel(long playerId, String ticketId, QueueRef queue, Deadline d) {
                delegate.pop(queue, "pop-race", List.of(new TicketRef(playerId, ticketId)), 48_000, d);
                return super.cancel(playerId, ticketId, queue, d);
            }
        };

        assertThat(serviceOn(racing).cancel(A, ticket, d())).isEqualTo(CancelOutcome.RACED);

        assertThat(store.ticketOf(A).orElseThrow().state()).as("玩家不会在「取消成功」之后被冻进战斗却没有票").isEqualTo(TicketState.MATCHED);
    }

    @Test
    void 取消_按票里记下的队列键出队_不按模式与副本号重算() {
        QueueRef recorded = new QueueRef(MODE_1V1, 777);
        store.putTicket(A, new Ticket("tk", MODE_1V1, 0, TicketState.QUEUED, clock.peekMs(), 1, recorded.queueKey(), 150_000, 0, 0, 0), SIX_HOURS);
        store.putQueueMember(recorded, "1001", 150_000L);

        assertThat(service.cancel(A, "tk", d())).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(store.ticketOf(A)).isEmpty();
        assertThat(store.queueMembers(recorded)).as("从票据记录的队列里出队").isEmpty();
    }

    @Test
    void 取消_队列键不是规范形的queued票_按孤儿条件删() {
        store.putTicket(A, new Ticket("tk", MODE_1V1, 0, TicketState.QUEUED, clock.peekMs(), 1, "", 150_000, 0, 0, 0), SIX_HOURS);

        assertThat(service.cancel(A, "", d())).isEqualTo(CancelOutcome.CANCELLED);

        assertThat(store.ticketOf(A)).isEmpty();
    }

    @Test
    void 取消_读票或删票出错_抛依赖异常_由处理器回信封1003() {
        inScene(A);
        join(A, MODE_1V1, 0);

        store.faults.failNext("read");
        assertThatThrownBy(() -> service.cancel(A, "", d())).isInstanceOf(Deadline.DependencyException.class);
        store.faults.failNext("cancel");
        assertThatThrownBy(() -> service.cancel(A, "", d())).isInstanceOf(Deadline.DependencyException.class);

        assertThat(store.ticketOf(A)).as("没删成").isPresent();
    }

    // ================================================================ 153 GetQueueStatus

    @Test
    void 查状态_身份为0或没有票_NOT_QUEUED_两个秒数都是0() {
        GetQueueStatusResponse expected = GetQueueStatusResponse.newBuilder().setStateValue(5).build();

        assertThat(service.status(0, d()).toByteString()).isEqualTo(expected.toByteString());
        assertThat(store.calls).as("身份为 0 不读票").isEmpty();
        assertThat(service.status(A, d()).toByteString()).isEqualTo(expected.toByteString());
    }

    @Test
    void 查状态_五态映射_queued_matched_ready_等待秒数三种状态都算() {
        inScene(A);
        String ticket = join(A, MODE_1V1, 0).getQueueTicket();
        clock.advanceMs(17_999);
        assertThat(service.status(A, d())).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(1).setQueuedSeconds(17).build());

        store.pop(Q_1V1, "pop-1", List.of(new TicketRef(A, ticket)), 48_000, d());
        clock.advanceMs(2_001);
        assertThat(service.status(A, d())).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(2).setQueuedSeconds(20).build());

        store.markReady(new TicketRef(A, ticket), 77, 60_000, d());
        clock.advanceSeconds(5);
        GetQueueStatusResponse ready = service.status(A, d());
        assertThat(ready).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(3).setQueuedSeconds(25).build());
        assertThat(ready.getEstimatedWaitSeconds()).as("恒为 0").isZero();
    }

    @Test
    void 查状态_回队首不重置等待秒数() {
        inScene(A);
        String ticket = join(A, MODE_1V1, 0).getQueueTicket();
        clock.advanceSeconds(30);
        store.pop(Q_1V1, "pop-1", List.of(new TicketRef(A, ticket)), 48_000, d());
        clock.advanceSeconds(10);
        store.requeueFront(Q_1V1, "rq-1", List.of(new TicketRef(A, ticket)), SIX_HOURS, 2000, d());

        GetQueueStatusResponse status = service.status(A, d());

        assertThat(status.getState()).isEqualTo(QueueState.QUEUE_STATE_QUEUED);
        assertThat(status.getQueuedSeconds()).isEqualTo(40);
    }

    @Test
    void 查状态_不认识的状态按NOT_QUEUED_永不回ENTERING与UNSPECIFIED() {
        store.putTicket(A, new Ticket("weird", MODE_1V1, 0, TicketState.UNKNOWN, clock.peekMs() - 9_000, 1, "", 150_000, 0, 0, 0), 60_000);

        GetQueueStatusResponse status = service.status(A, d());

        assertThat(status.getStateValue()).isEqualTo(5);
        assertThat(status.getQueuedSeconds()).as("等待秒数照算（同基线 status.go:54-72）").isEqualTo(9);
    }

    @Test
    void 查状态_入队时刻在存储时间之后_夹到0_不下溢() {
        store.putTicket(A, new Ticket("future", MODE_1V1, 0, TicketState.QUEUED, clock.peekMs() + 5_000, 1, Q_1V1.queueKey(), 150_000, 0, 0, 0),
                SIX_HOURS);
        store.putTicket(B, new Ticket("zero", MODE_1V1, 0, TicketState.QUEUED, 0, 1, Q_1V1.queueKey(), 150_000, 0, 0, 0), SIX_HOURS);

        assertThat(service.status(A, d())).isEqualTo(GetQueueStatusResponse.newBuilder().setStateValue(1).build());
        assertThat(service.status(B, d()).getQueuedSeconds()).as("入队时刻为 0（旧数据）不算等待").isZero();
    }

    @Test
    void 查状态_用的是存储的时间_不是本机时钟() {
        inScene(A);
        join(A, MODE_1V1, 0);
        // 手拨时钟的起点与本机时钟差了好几年：若用了 System.currentTimeMillis，秒数会是负的被夹成 0，或大得离谱
        clock.advanceSeconds(3);

        assertThat(service.status(A, d()).getQueuedSeconds()).isEqualTo(3);
    }

    @Test
    void 查状态_读票出错_抛依赖异常() {
        store.faults.failNext("status");

        assertThatThrownBy(() -> service.status(A, d())).isInstanceOf(Deadline.DependencyException.class);
    }
}
