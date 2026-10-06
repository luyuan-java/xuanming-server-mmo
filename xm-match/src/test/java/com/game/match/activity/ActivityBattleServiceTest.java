package com.game.match.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.rating.RatingReader;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 帮会活动开战（match-spec §7.1、§7.2、§15.2；对照基线 {@code activitybattlelogic_test.go} 里入口这一层的 12 条——其余 3 条测的是开局管线，
 * 归管线自己的测试）：参数校验（含未知的 kind）、预检的翻译（<b>读战斗锁失败是 INTERNAL</b>，与整队入口不同）、自愈、原子建票的冲突与
 * 结局不明的回滚、调用方已放弃、发号失败、成功后异步 gather 与两段指标。
 * 预检是真的实现接在替身的玩家状态与内存票据存储上（连自愈的副作用一起走），开局管线与发号租约是替身。
 */
class ActivityBattleServiceTest {

    private static final long A = 9701;
    private static final long B = 9702;
    private static final long C = 9703;
    private static final int CONFIG = 1;
    private static final long GUILD = 660001;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).online(B, 2, 7).online(C, 3, 8);
    private final MemberPrecheck precheck = new DefaultMemberPrecheck(players, new StoreBackedHealing(tickets));
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final MatchIds ids = new MatchIds(new Snowflake(5), leaseValid::get, () -> false);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final ActivityBattleService service = new ActivityBattleService(precheck, tickets, gather, ids, metrics);

    private static Deadline d() {
        return Deadline.after(3_000);
    }

    /** 合法的请求：发起人 = 名单第一个人，上下文四个号都非 0。 */
    private static StartActivityBattleRequest.Builder request(Long... members) {
        BattleActivityContext.Builder context = BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(GUILD).setActivityId(3).setPeriodKey(20261006).setGuildPeriodKey(20261006);
        if (members.length > 0) {
            context.setInitiatorPlayerId(members[0]);
        }
        return StartActivityBattleRequest.newBuilder().setBattleConfigId(CONFIG).addAllMemberPlayerIds(List.of(members)).setActivityContext(context);
    }

    private static StartActivityBattleRequest withContext(StartActivityBattleRequest.Builder request, Consumer<BattleActivityContext.Builder> change) {
        BattleActivityContext.Builder context = request.getActivityContext().toBuilder();
        change.accept(context);
        return request.setActivityContext(context).build();
    }

    private double count(String kind, String result) {
        return meters.get("xm.match.activity.battles").tags("kind", kind, "result", result).counter().count();
    }

    private static void assertRejected(StartActivityBattleResponse response, ActivityBattleReject reject, long offender) {
        assertThat(response.getReject()).isEqualTo(reject);
        assertThat(response.getOffenderPlayerId()).isEqualTo(offender);
        assertThat(response.getBattleId()).as("拒绝时不带 battle_id").isZero();
    }

    private static Ticket ticket(String ticketId, int mode, TicketState state, String queueKey) {
        return new Ticket(ticketId, mode, 0, state, 1, 1, queueKey, RatingReader.DEFAULT_CENTI, 0, state == TicketState.READY ? 55 : 0, 0);
    }

    // ================================================================ 参数校验

    @Test
    void 参数校验_十三种非法请求都是INVALID_ARGUMENT_不读任何依赖_kind标签收敛() {
        Map<String, StartActivityBattleRequest> invalid = new LinkedHashMap<>();
        invalid.put("0 人", request().build());
        invalid.put("6 人超上限", request(1L, 2L, 3L, 4L, 5L, 6L).build());
        invalid.put("名单重复", request(A, B, A).build());
        invalid.put("名单含 0", request(A, 0L).build());
        invalid.put("battle_config_id 为 0", request(A, B).setBattleConfigId(0).build());
        invalid.put("guild_id 为 0", withContext(request(A, B), c -> c.setGuildId(0)));
        invalid.put("activity_id 为 0", withContext(request(A, B), c -> c.setActivityId(0)));
        invalid.put("period_key 为 0", withContext(request(A, B), c -> c.setPeriodKey(0)));
        invalid.put("guild_period_key 为 0", withContext(request(A, B), c -> c.setGuildPeriodKey(0)));
        invalid.put("发起人不在首位", withContext(request(A, B), c -> c.setInitiatorPlayerId(B)));
        Map<String, StartActivityBattleRequest> noneKind = new LinkedHashMap<>();
        noneKind.put("缺少上下文", request(A, B).clearActivityContext().build());
        noneKind.put("kind = NONE", withContext(request(A, B), c -> c.setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_NONE)));
        StartActivityBattleRequest unknownKind = withContext(request(A, B), c -> c.setKindValue(99));
        assertThat(unknownKind.getActivityContext().getKind()).as("proto3 把未知的枚举数值读成 UNRECOGNIZED").isEqualTo(eBattleActivityKind.UNRECOGNIZED);

        invalid.forEach((name, request) -> assertRejected(service.start(request, d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0));
        noneKind.forEach((name, request) -> assertRejected(service.start(request, d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0));
        assertRejected(service.start(unknownKind, d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);

        assertThat(count("guild_trial", "invalid")).isEqualTo(10);
        assertThat(count("none", "invalid")).isEqualTo(2);
        assertThat(count("unknown", "invalid")).as("未知的 kind 不会变成新的标签值").isEqualTo(1);
        assertThat(players.reads).as("参数非法不读任何依赖").isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(gather.plans).as("参数非法不得起 gather").isEmpty();
    }

    @Test
    void 校验器_合法请求返回null_一到五人都可以() {
        assertThat(ActivityRequestValidator.invalidReason(request(A).build())).isNull();
        assertThat(ActivityRequestValidator.invalidReason(request(1L, 2L, 3L, 4L, 5L).build())).isNull();
        assertThat(ActivityRequestValidator.invalidReason(request(A, B).setBattleConfigId(0xFFFF_FFFF).build())).as("配置号不校验是否存在").isNull();
        assertThat(ActivityRequestValidator.invalidReason(request(A, B, A).build())).isEqualTo("名单重复");
        assertThat(ActivityRequestValidator.invalidReason(withContext(request(A, B), c -> c.setKindValue(99)))).isEqualTo("活动类型为 NONE 或未知");
    }

    // ================================================================ 预检的翻译（活动入口的口径）

    @Test
    void 第二个人不在线_MEMBER_OFFLINE带上他_没有票_不起gather() {
        players.disconnected(B);

        assertRejected(service.start(request(A, B, C).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, B);

        assertThat(tickets.ticketCount()).isZero();
        assertThat(gather.plans).isEmpty();
        assertThat(players.reads).as("第三个人不再查").doesNotContain("presence:9703");
        assertThat(count("guild_trial", "offline")).isEqualTo(1);
    }

    @Test
    void 有战斗锁的成员_MEMBER_IN_BATTLE带上他() {
        players.inBattle(B, true);

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE, B);

        assertThat(tickets.ticketCount()).isZero();
        assertThat(count("guild_trial", "in_battle")).isEqualTo(1);
    }

    @Test
    void 读战斗锁出错_是INTERNAL且不带offender_与整队入口的口径不同() {
        players.failLock(B);

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(tickets.ticketCount()).isZero();
        assertThat(gather.plans).isEmpty();
        assertThat(count("guild_trial", "internal")).isEqualTo(1);
    }

    @Test
    void 读在线目录或位置出错_INTERNAL_不带offender() {
        players.failPresence(A);
        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        players.heal(A).failLocation(B);
        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(count("guild_trial", "internal")).isEqualTo(2);
    }

    @Test
    void 没有可用位置的成员_MEMBER_NOT_READY_节点号为0也算() {
        players.location(B, LocationStatus.MISSING);
        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, B);

        players.location(B, 2, 0);
        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, B);

        assertThat(tickets.ticketCount()).isZero();
        assertThat(count("guild_trial", "not_ready")).isEqualTo(2);
    }

    @Test
    void 有在途票据的成员_MEMBER_NOT_READY_他的票原样_别人没有被建票() {
        tickets.putTicket(B, ticket("inflight-b", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, TicketState.MATCHED, ""), 42_000);

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, B);

        assertThat(tickets.ticketOf(B).orElseThrow().ticketId()).isEqualTo("inflight-b");
        assertThat(tickets.ticketOf(A)).isEmpty();
        assertThat(gather.plans).isEmpty();
    }

    @Test
    void 读票据出错_INTERNAL() {
        tickets.faults.failNext("read");

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(tickets.ticketCount()).isZero();
    }

    @Test
    void 交错顺序_第一个人有在途票_第二个人不在线_结论是第一个人() {
        tickets.putTicket(A, ticket("inflight-a", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, TicketState.MATCHED, ""), 42_000);
        players.disconnected(B);

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, A);
    }

    @Test
    void 残留的孤儿queued票与ready票先被自愈_然后照常开局_换成本次的活动票() {
        String queueKey = new QueueRef(MatchMode.MATCH_MODE_1V1_VALUE, 0).queueKey();
        tickets.putTicket(A, ticket("orphan-a", MatchMode.MATCH_MODE_1V1_VALUE, TicketState.QUEUED, queueKey), 21_600_000);
        tickets.putTicket(B, ticket("ready-b", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, TicketState.READY, ""), 60_000);

        StartActivityBattleResponse response = service.start(request(A, B).build(), d());

        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(response.getBattleId()).isNotZero();
        assertThat(gather.plans).hasSize(1);
        for (long playerId : List.of(A, B)) {
            Ticket now = tickets.ticketOf(playerId).orElseThrow();
            assertThat(now.ticketId()).as("残留票已被本次的活动票替换").isEqualTo(gather.plans.get(0).tickets().get(playerId));
            assertThat(now.state()).isEqualTo(TicketState.MATCHED);
        }
    }

    // ================================================================ 发号、建票、截止

    @Test
    void 发号失败_INTERNAL_绝不拿0顶替_不建票不起gather_预检已经做过() {
        leaseValid.set(false);

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(players.reads).as("发号在预检之后：成员的问题照常先报").contains("presence:9701", "location:9702");
        assertThat(tickets.ticketCount()).isZero();
        assertThat(tickets.calls).noneMatch(call -> call.startsWith("createGroup"));
        assertThat(gather.plans).isEmpty();
        assertThat(count("guild_trial", "internal")).isEqualTo(1);

        players.disconnected(B);
        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, B);
    }

    @Test
    void 预检之后第三个人被别人抢先写了票_MEMBER_NOT_READY带上他_原子建票不留残票() {
        // 预检通过之后、建票之前，C 自己去排了一次单人 PVE
        MemberPrecheck raced = (roster, deadline) -> {
            MemberPrecheck.Result result = precheck.check(roster, deadline);
            tickets.putTicket(C, ticket("raced-c", MatchMode.MATCH_MODE_PVE_SOLO_VALUE, TicketState.MATCHED, ""), 42_000);
            return result;
        };
        ActivityBattleService racing = new ActivityBattleService(raced, tickets, gather, ids, metrics);

        assertRejected(racing.start(request(A, B, C).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, C);

        assertThat(tickets.ticketOf(A)).as("原子：前两个人没有被建票，也就不需要回滚").isEmpty();
        assertThat(tickets.ticketOf(B)).isEmpty();
        assertThat(tickets.ticketOf(C).orElseThrow().ticketId()).as("别人的票不动").isEqualTo("raced-c");
        assertThat(tickets.calls).noneMatch(call -> call.startsWith("delete"));
        assertThat(gather.plans).isEmpty();
        assertThat(count("guild_trial", "not_ready")).isEqualTo(1);
    }

    @Test
    void 建票结局不明_其实已写入_按本次票号回滚后INTERNAL_不起gather() {
        tickets.faults.failNext("createGroup:after");

        assertRejected(service.start(request(A, B).build(), d()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(tickets.ticketCount()).as("已写入的票被回滚").isZero();
        assertThat(tickets.calls.stream().filter(call -> call.startsWith("delete("))).containsExactly("delete(9701)", "delete(9702)");
        assertThat(gather.plans).isEmpty();
        assertThat(count("guild_trial", "internal")).isEqualTo(1);
    }

    @Test
    void 建票之后调用方的预算已用完_回滚票据_INTERNAL_不得开局() {
        // 建票那一步把预算耗光（票已建成）：调用方拿不到 battle_id，会把这次开战判为失败
        TicketStore slow = (TicketStore) Proxy.newProxyInstance(TicketStore.class.getClassLoader(), new Class<?>[] {TicketStore.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(tickets, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("createGroup")) {
                        Deadline deadline = (Deadline) args[args.length - 1];
                        while (!deadline.expired()) {
                            TimeUnit.MILLISECONDS.sleep(5);
                        }
                    }
                    return result;
                });
        ActivityBattleService late = new ActivityBattleService(precheck, slow, gather, ids, metrics);

        assertRejected(late.start(request(A, B).build(), Deadline.after(400)), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(tickets.calls).as("票建成过，然后被回滚").contains("createGroup([9701, 9702])", "delete(9701)", "delete(9702)");
        assertThat(tickets.ticketCount()).isZero();
        assertThat(gather.plans).as("调用方已放弃就不得开局").isEmpty();
        assertThat(count("guild_trial", "internal")).isEqualTo(1);
    }

    @Test
    void 请求预算一开始就用完_INTERNAL_什么都不读() {
        assertRejected(service.start(request(A, B).build(), Deadline.after(0)), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(players.reads).isEmpty();
        assertThat(tickets.calls).isEmpty();
    }

    // ================================================================ 成功

    @Test
    void 成功_同步回battle_id_全员有matched票_gather拿到同一个号与原序名单与上下文() {
        StartActivityBattleRequest request = request(C, A, B).build();

        StartActivityBattleResponse response = service.start(request, d());

        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(response.getOffenderPlayerId()).isZero();
        long battleId = response.getBattleId();
        assertThat(battleId).isNotZero();
        assertThat(Snowflake.workerOf(battleId)).as("battle_id 出自 match 的发号器").isEqualTo(5);

        assertThat(gather.plans).hasSize(1);
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.presetBattleId()).as("gather 用的就是回给调用方的那个号").isEqualTo(battleId);
        assertThat(plan.members()).as("站位顺序 = 名单顺序，发起人在首位").containsExactly(C, A, B);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.battleConfigId()).isEqualTo(CONFIG);
        assertThat(plan.onFail()).as("失败全员删票、不回队列").isEqualTo(FailPolicy.DELETE_ALL);
        assertThat(plan.activityContext()).as("上下文原样透传").isEqualTo(request.getActivityContext());
        assertThat(plan.tickets().keySet()).containsExactly(C, A, B);
        assertThat(new HashSet<>(plan.tickets().values())).as("每人一个不同的票号").hasSize(3);

        int[] zones = {3, 1, 2};
        int index = 0;
        for (long playerId : List.of(C, A, B)) {
            Ticket ticket = tickets.ticketOf(playerId).orElseThrow();
            assertThat(ticket.ticketId()).as("gather 拿到的票号就是落盘的那张").isEqualTo(plan.tickets().get(playerId));
            assertThat(ticket.ticketId()).as("UUIDv4 小写带连字符").matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
            assertThat(ticket.state()).isEqualTo(TicketState.MATCHED);
            assertThat(ticket.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM_VALUE);
            assertThat(ticket.configId()).isEqualTo(CONFIG);
            assertThat(ticket.zoneId()).as("zone 取预检读到的位置").isEqualTo(zones[index++]);
            assertThat(ticket.queueKey()).as("活动开战不入队").isEmpty();
            assertThat(ticket.teamId()).as("活动票不设 team_id").isZero();
            assertThat(tickets.ttlMs(playerId)).as("TTL = 3 人的 matched TTL").isEqualTo(54_000);
        }
        assertThat(tickets.queueIndex(d())).as("不登记任何队列").isEmpty();
        assertThat(count("guild_trial", "started")).isEqualTo(1);
        assertThat(count("guild_trial", "gather_ok")).isEqualTo(1);
    }

    @Test
    void 成功_应答不等gather_gather之后失败只记指标_battle_id照样已经回给调用方() {
        gather.hold();

        StartActivityBattleResponse response = service.start(request(A, B).build(), d());

        assertThat(response.getBattleId()).as("同步出口仍是 started").isNotZero();
        assertThat(count("guild_trial", "started")).isEqualTo(1);
        assertThat(gather.futures.get(0)).isNotDone();

        gather.complete(0, GatherResult.failed(GatherOutcome.PREPARE_FAILED, response.getBattleId()));

        assertThat(count("guild_trial", "gather_failed")).isEqualTo(1);
        io.micrometer.core.instrument.Counter ok = meters.find("xm.match.activity.battles").tags("kind", "guild_trial", "result", "gather_ok").counter();
        assertThat(ok == null ? 0 : ok.count()).as("没有记成 gather_ok").isZero();
    }

    @Test
    void 没能执行时的应答_INTERNAL_计入同步出口的internal() {
        assertRejected(service.unavailable(request(A, B).build()), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);

        assertThat(count("guild_trial", "internal")).isEqualTo(1);
        assertThat(players.reads).isEmpty();
    }
}
