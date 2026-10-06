package com.game.match.activity;

import com.game.common.deadline.Deadline;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.ActivityResult;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.team.GroupTickets;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.proto.BattleActivityContext;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会活动开战（match-spec §7.1、§7.2；基线 {@code go/match/internal/logic/activitybattlelogic.go}）。Dubbo 提供方
 * （{@link MatchInternalServiceImpl}）与 dev 管理口走的是这同一个实现。步骤与基线逐条对应：
 * <ol>
 *   <li>参数校验（{@link ActivityRequestValidator}）→ {@code INVALID_ARGUMENT}；</li>
 *   <li>逐成员只读预检（{@link MemberPrecheck}，名单顺序，第一个不满足的人即为结论）；</li>
 *   <li>发号（battle_id 要同步回给调用方登记，所以在这里发、不在管线里发）；发不出 → {@code INTERNAL}，绝不拿 0 顶替；</li>
 *   <li>原子建全员的 matched 票（PVE_TEAM、不入队、不写 team_id、每人一个新的 UUID）：有人已有票 → {@code MEMBER_NOT_READY} + 该成员，
 *       什么都没写；建票调用出错（结局不明）→ 独立预算回滚后 {@code INTERNAL}；</li>
 *   <li>调用方已放弃（请求预算用完）→ 回滚票据、{@code INTERNAL}：调用方拿不到 battle_id 会把这次开战判为失败，这时再开局等于把玩家拉进一场
 *       它以为没开的战斗；</li>
 *   <li>异步 gather（预发的号 + 活动上下文原样透传），同步回 {@code battle_id}。</li>
 * </ol>
 *
 * <p><b>预检的翻译（活动入口的口径，与整队入口不同，别抄混）</b>：不在线 → {@code MEMBER_OFFLINE}；有战斗锁 → {@code MEMBER_IN_BATTLE}；
 * 没有可用位置 / 有在途票据 → {@code MEMBER_NOT_READY}；一切读失败——<b>含读战斗锁出错</b>——与预算用完 → {@code INTERNAL}、offender = 0。
 *
 * <p>发号租约无效（含已丢失）时第 3 步自然失败 → {@code INTERNAL}，此时还没有建票。gather 之后失败由管线全员删票、不回队列，这个 battle_id
 * 不会产生结果事件——调用方必须有过期兜底（§12.1 第 7 条）。
 *
 * <p>阻塞（在 {@code match-worker} 或管理口的 Tomcat 线程上调）；业务拒绝都在应答的 {@code reject} 里，永不抛依赖异常。线程安全，无可变状态。
 */
public final class ActivityBattleService {

    private static final Logger log = LoggerFactory.getLogger(ActivityBattleService.class);
    private static final String WHAT = "活动开战";

    private final MemberPrecheck precheck;
    private final GroupTickets groupTickets;
    private final GatherLauncher gather;
    private final MatchIds ids;
    private final MatchMetrics metrics;
    private final Supplier<String> ticketIds;

    public ActivityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds ids, MatchMetrics metrics) {
        this(precheck, tickets, gather, ids, metrics, () -> UUID.randomUUID().toString());
    }

    /** @param ticketIds 票号来源（生产是随机 UUID；测试可注入确定的值） */
    ActivityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds ids, MatchMetrics metrics,
                          Supplier<String> ticketIds) {
        this.precheck = Objects.requireNonNull(precheck, "precheck");
        this.groupTickets = new GroupTickets(Objects.requireNonNull(tickets, "tickets"));
        this.gather = Objects.requireNonNull(gather, "gather");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ticketIds = Objects.requireNonNull(ticketIds, "ticketIds");
    }

    /**
     * 开一场活动战斗。
     *
     * @param d 本次请求的截止（调用方的剩余预算；到点即视为调用方已放弃）
     * @return {@code reject = NONE} 时 {@code battle_id ≠ 0}、票据已建、gather 已异步启动
     */
    public StartActivityBattleResponse start(StartActivityBattleRequest request, Deadline d) {
        int kind = request.getActivityContext().getKindValue(); // 没有上下文时是 0（NONE）
        List<Long> members = request.getMemberPlayerIdsList();
        int configId = request.getBattleConfigId();
        BattleActivityContext context = request.getActivityContext();

        // 1. 参数校验
        String invalid = ActivityRequestValidator.invalidReason(request);
        if (invalid != null) {
            log.error("[match] 活动开战参数非法：{} config={} members={} ctx=[{}]", invalid, Integer.toUnsignedString(configId), describe(members),
                    describe(request));
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);
        }

        // 2. 逐成员只读预检
        MemberPrecheck.Result checked = precheck.check(members, d);
        if (!checked.passed()) {
            return switch (checked.reason()) {
                case OFFLINE -> rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, checked.offender());
                case IN_BATTLE -> rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE, checked.offender());
                case NO_LOCATION, TICKET_IN_FLIGHT ->
                        rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, checked.offender());
                // 读失败（含读战斗锁出错）与预算用完：INTERNAL，不归咎于成员
                case PRESENCE_READ_FAILED, LOCK_READ_FAILED, LOCATION_READ_FAILED, TICKET_READ_FAILED, DEADLINE_EXPIRED, OK ->
                        rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
            };
        }

        // 3. 发号
        OptionalLong issued = ids.nextBattleId();
        if (issued.isEmpty()) {
            log.error("[match] 活动开战 battle_id 发号失败（发号租约无效或时钟回拨） members={} ctx=[{}]", describe(members), describe(request));
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
        }
        long battleId = issued.getAsLong();

        // 4. 原子建全员的 matched 票
        Map<Long, String> tickets = new LinkedHashMap<>();
        List<GroupMember> group = new ArrayList<>(members.size());
        for (long playerId : members) {
            String ticketId = ticketIds.get();
            tickets.put(playerId, ticketId);
            group.add(new GroupMember(playerId, ticketId, checked.zones().getOrDefault(playerId, 0)));
        }
        GroupTickets.Outcome created = groupTickets.create(configId, 0, group, d, WHAT);
        if (created instanceof GroupTickets.Outcome.Conflict conflict) {
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, conflict.playerId());
        }
        if (!(created instanceof GroupTickets.Outcome.Created)) {
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
        }

        // 5. 调用方已放弃就不再开局
        if (d.expired()) {
            log.error("[match] 活动开战前调用方已放弃，回滚票据 battle={} members={}", Long.toUnsignedString(battleId), describe(members));
            groupTickets.rollback(group, WHAT);
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
        }

        // 6. 异步 gather（回调在 gather 的线程上：只计数与记日志）
        CompletableFuture<GatherResult> launched;
        try {
            launched = gather.launch(GatherPlan.activity(configId, members, tickets, battleId, context));
        } catch (RuntimeException e) {
            log.error("[match] 活动开战没能交给开局管线，回滚票据 battle={} members={}", Long.toUnsignedString(battleId), describe(members), e);
            groupTickets.rollback(group, WHAT);
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
        }
        launched.whenComplete((result, error) -> {
            boolean ok = error == null && result != null && result.ok();
            metrics.activityBattle(kind, ok ? ActivityResult.GATHER_OK : ActivityResult.GATHER_FAILED);
            if (!ok) {
                log.error("[match] 活动开战的 gather 失败（票据已由管线删除；这个 battle_id 不会有结果事件） battle={} outcome={}",
                        Long.toUnsignedString(battleId), result == null ? "error" : result.outcome().label(), error);
            }
        });

        // 7. 同步回 battle_id
        log.info("[match] 活动开战已受理 battle={} config={} members={} zones={} ctx=[{}]", Long.toUnsignedString(battleId),
                Integer.toUnsignedString(configId), describe(members), checked.zones().values(), describe(request));
        metrics.activityBattle(kind, ActivityResult.STARTED);
        return StartActivityBattleResponse.newBuilder().setBattleId(battleId).build();
    }

    /**
     * 请求没能执行（工作池满 / 排队超预算 / 处理中出了未分类的异常）时的应答：{@code INTERNAL}、offender = 0，计入同步出口的 internal。
     */
    public StartActivityBattleResponse unavailable(StartActivityBattleRequest request) {
        return rejected(request.getActivityContext().getKindValue(), ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
    }

    private StartActivityBattleResponse rejected(int kind, ActivityBattleReject reject, long offender) {
        metrics.activityBattle(kind, switch (reject) {
            case ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT -> ActivityResult.INVALID;
            case ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE -> ActivityResult.OFFLINE;
            case ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE -> ActivityResult.IN_BATTLE;
            case ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY -> ActivityResult.NOT_READY;
            default -> ActivityResult.INTERNAL;
        });
        return StartActivityBattleResponse.newBuilder().setReject(reject).setOffenderPlayerId(offender).build();
    }

    private static String describe(List<Long> members) {
        return members.stream().map(Long::toUnsignedString).toList().toString();
    }

    /** 活动上下文的单行日志形式。 */
    private static String describe(StartActivityBattleRequest request) {
        if (!request.hasActivityContext()) {
            return "无";
        }
        BattleActivityContext context = request.getActivityContext();
        return "kind=" + context.getKindValue() + " guild=" + Long.toUnsignedString(context.getGuildId())
                + " activity=" + Integer.toUnsignedString(context.getActivityId()) + " period=" + Integer.toUnsignedString(context.getPeriodKey())
                + " guild_period=" + Integer.toUnsignedString(context.getGuildPeriodKey())
                + " initiator=" + Long.toUnsignedString(context.getInitiatorPlayerId());
    }
}
