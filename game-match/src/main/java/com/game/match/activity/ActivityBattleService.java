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
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.proto.match.StartGuildWarBattleRequest;
import com.game.proto.match.StartGuildWarBattleResponse;
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
    private final java.util.function.LongSupplier clockMs;
    private com.game.match.global.GlobalMatchService global;
    private com.game.match.ranked.RankedTables rankedTables;
    public ActivityBattleService withGlobal(com.game.match.global.GlobalMatchService global){this.global=global;return this;}
    public ActivityBattleService withRankedTables(com.game.match.ranked.RankedTables rankedTables){this.rankedTables=rankedTables;return this;}

    public ActivityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds ids, MatchMetrics metrics) {
        this(precheck, tickets, gather, ids, metrics, () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    /** @param ticketIds 票号来源（生产是随机 UUID；测试可注入确定的值） */
    ActivityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds ids, MatchMetrics metrics,
                          Supplier<String> ticketIds) {
        this(precheck, tickets, gather, ids, metrics, ticketIds, System::currentTimeMillis);
    }

    ActivityBattleService(MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather, MatchIds ids, MatchMetrics metrics,
                          Supplier<String> ticketIds, java.util.function.LongSupplier clockMs) {
        this.precheck = Objects.requireNonNull(precheck, "precheck");
        this.groupTickets = new GroupTickets(Objects.requireNonNull(tickets, "tickets"));
        this.gather = Objects.requireNonNull(gather, "gather");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ticketIds = Objects.requireNonNull(ticketIds, "ticketIds");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    /**
     * 开一场活动战斗。
     *
     * @param d 本次请求的截止（调用方的剩余预算；到点即视为调用方已放弃）
     * @return {@code reject = NONE} 时 {@code battle_id ≠ 0}、票据已建、gather 已异步启动
     */
    public StartActivityBattleResponse start(StartActivityBattleRequest request, Deadline d) {
        if(global!=null)return startGlobal(request,d);
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
        boolean isFestivalBeast = context.getKind() == com.game.proto.eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST;
        if (isFestivalBeast) {
            if (rankedTables != null && rankedTables.bossTrialKind(configId)
                    != com.game.match.ranked.RankedTables.BOSS_TRIAL_KIND_FESTIVAL_BEAST) {
                return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);
            }
            var fb = context.getFestivalBeast();
            long nowMs = clockMs.getAsLong();
            if (Long.compareUnsigned(nowMs, fb.getEffectiveStartAtMs()) < 0
                    || Long.compareUnsigned(nowMs, fb.getEffectiveEndAtMs()) >= 0) {
                return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_OUTSIDE_WINDOW, 0);
            }
        }

        // 2. 逐成员只读预检
        MemberPrecheck.Result checked = precheck.check(members, d);
        if (!checked.passed()) {
            return rejectedPrecheck(kind,checked);
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
        long admittedAtMs = isFestivalBeast ? clockMs.getAsLong() : 0L;
        log.info("[match] 活动开战已受理 battle={} config={} members={} zones={} ctx=[{}]", Long.toUnsignedString(battleId),
                Integer.toUnsignedString(configId), describe(members), checked.zones().values(), describe(request));
        metrics.activityBattle(kind, ActivityResult.STARTED);
        return StartActivityBattleResponse.newBuilder().setBattleId(battleId).setAdmittedAtMs(admittedAtMs).build();
    }

    private StartActivityBattleResponse startGlobal(StartActivityBattleRequest request,Deadline deadline) {
        int kind=request.getActivityContext().getKindValue();
        if(ActivityRequestValidator.invalidReason(request)!=null)return rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT,0);
        if(request.getActivityContext().getKind()==com.game.proto.eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST){
            return startGlobalFestivalBeast(request,kind,deadline);
        }
        if(request.getRequestId()==0||request.getActivityContext().getLaunchRequestId()!=request.getRequestId())
            return rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT,0);
        try{
            var key=com.game.match.global.pb.GlobalDirectIntentRow.newBuilder().setKind(2).setOwnerId(request.getActivityContext().getGuildId()).setRequestLow(request.getRequestId()).build();
            var old=global.store().direct(key);
            if(old!=null){if(old.getCancelled())return rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL,0);
                if(!old.getPayload().equals(request.toByteString()))return rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT,0);
                var f=old.getBattleId()!=0?global.store().formation(old.getBattleId()):global.reserveDirect(key,request.toByteString(),com.game.proto.match.MatchMode.MATCH_MODE_PVE_TEAM_VALUE,request.getBattleConfigId(),request.getMemberPlayerIdsList(),Map.of(),0,request.getActivityContext());global.launchCommitted(f);return globalReply(f);}
            var checked=precheck.check(request.getMemberPlayerIdsList(),deadline);
            if(!checked.passed())return rejectedPrecheck(kind,checked);
            var f=global.reserveDirect(key,request.toByteString(),com.game.proto.match.MatchMode.MATCH_MODE_PVE_TEAM_VALUE,request.getBattleConfigId(),request.getMemberPlayerIdsList(),Map.of(),0,request.getActivityContext());
            global.launchCommitted(f);return globalReply(f);
        }catch(RuntimeException e){log.warn("活动原意图提交结果尚未确认",e);return rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL,0);}
    }

    private StartActivityBattleResponse startGlobalFestivalBeast(StartActivityBattleRequest request, int kind, Deadline deadline) {
        if (rankedTables != null && rankedTables.bossTrialKind(request.getBattleConfigId())
                != com.game.match.ranked.RankedTables.BOSS_TRIAL_KIND_FESTIVAL_BEAST) {
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);
        }
        try {
            long playerId = request.getMemberPlayerIds(0);
            var key = com.game.match.global.pb.GlobalDirectIntentRow.newBuilder()
                    .setKind(5)
                    .setOwnerId(playerId)
                    .setRequestHigh(request.getRequestHigh())
                    .setRequestLow(request.getRequestLow())
                    .build();
            var old = global.store().direct(key);
            if (old != null) {
                if (old.getCancelled()) return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
                if (!old.getPayload().equals(request.toByteString())) {
                    return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);
                }
                var f = old.getBattleId() != 0
                        ? global.store().formation(old.getBattleId())
                        : global.reserveDirect(key, request.toByteString(),
                        com.game.proto.match.MatchMode.MATCH_MODE_PVE_SOLO_VALUE,
                        request.getBattleConfigId(), request.getMemberPlayerIdsList(),
                        Map.of(), 0, request.getActivityContext());
                global.launchCommitted(f);
                return globalReply(f);
            }
            var checked = precheck.check(request.getMemberPlayerIdsList(), deadline);
            if (!checked.passed()) return rejectedPrecheck(kind, checked);
            var f = global.reserveDirect(key, request.toByteString(),
                    com.game.proto.match.MatchMode.MATCH_MODE_PVE_SOLO_VALUE,
                    request.getBattleConfigId(), request.getMemberPlayerIdsList(),
                    Map.of(), 0, request.getActivityContext());
            global.launchCommitted(f);
            return globalReply(f);
        } catch (com.game.match.global.GlobalMatchStore.OutsideActivityWindowException outside) {
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_OUTSIDE_WINDOW, 0);
        } catch (RuntimeException e) {
            log.warn("瑞兽迎春原意图提交结果尚未确认", e);
            return rejected(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0);
        }
    }

    private static StartActivityBattleResponse globalReply(com.game.match.global.pb.GlobalFormationRow formation) {
        if(formation==null||formation.getBattleId()==0||formation.getState()<1||formation.getState()>5)
            throw new IllegalStateException("活动成局原件或状态缺失");
        return StartActivityBattleResponse.newBuilder()
                .setBattleId(formation.getBattleId())
                .setFormationState(formation.getState())
                .setAdmittedAtMs(formation.getCreatedMs())
                .build();
    }

    public com.game.proto.match.CancelActivityBattleResponse cancel(com.game.proto.match.CancelActivityBattleRequest request,Deadline d) {
        if(global==null)throw new IllegalStateException("活动取消需要SQL权威；旧内存模式不能证明迟到请求已被阻止");
        if(d.expired()||Thread.currentThread().isInterrupted())throw new IllegalStateException("活动取消预算结束");
        var original=request.getOriginal();
        if(!request.hasOriginal()||original.getRequestId()==0||original.getActivityContext().getLaunchRequestId()!=original.getRequestId()
            ||ActivityRequestValidator.invalidReason(original)!=null)
            throw new IllegalArgumentException("活动取消缺少完整有效原请求");
        var key=com.game.match.global.pb.GlobalDirectIntentRow.newBuilder().setKind(2)
            .setOwnerId(original.getActivityContext().getGuildId()).setRequestLow(original.getRequestId()).build();
        var decision=global.store().cancelActivity(key,original.toByteString());
        var response=com.game.proto.match.CancelActivityBattleResponse.newBuilder().setBattleId(decision.getBattleId())
            .setRequestCancelled(decision.getCancelled()).setRequestDigest(com.game.discovery.serviceauth.ServiceCallSignatures.digest(request));
        if(decision.getBattleId()!=0) {
            var formation=global.store().formation(decision.getBattleId());
            if(formation==null||formation.getState()<1||formation.getState()>5)throw new IllegalStateException("原活动成局状态未知，保留待恢复");
            response.setFormationState(formation.getState());
        } else if(!decision.getCancelled())throw new IllegalStateException("活动取消未取得持久墓碑");
        return response.build();
    }

    private StartActivityBattleResponse rejectedPrecheck(int kind,MemberPrecheck.Result checked){
        return switch(checked.reason()){
            case OFFLINE -> rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE,checked.offender());
            case IN_BATTLE -> rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE,checked.offender());
            case NO_LOCATION,TICKET_IN_FLIGHT -> rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY,checked.offender());
            default -> rejected(kind,ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL,0);
        };
    }

    /**
     * 请求没能执行（工作池满 / 排队超预算 / 处理中出了未分类的异常）时的应答：{@code INTERNAL}、offender = 0，计入同步出口的 internal。
     */
    /** 帮会全天帮战 V2 零匹配池直打开局（支持真人 PVP 与守旗阵 AI 托管，目标刚入战时带回 offender_battle_id 供一键观战）。 */
    public StartGuildWarBattleResponse startGuildWar(StartGuildWarBattleRequest request, Deadline d) {
        int kind = eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR_VALUE;
        if (request == null || !request.hasActivityContext()
                || request.getActivityContext().getKind() != eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR
                || !request.getActivityContext().hasGuildWar()) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
        }
        MatchMode mode = MatchMode.forNumber(request.getMatchMode());
        if (mode != MatchMode.MATCH_MODE_GUILD_WAR_PVP && mode != MatchMode.MATCH_MODE_GUILD_WAR_SIEGE) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
        }
        List<Long> attackers = request.getAttackerPlayerIdsList();
        List<Long> defenders = request.getDefenderPlayerIdsList();
        List<BattlePlayerSnapshot> aiDefenders = request.getAiDefenderSnapshotsList();
        if (attackers.isEmpty() || attackers.size() > 5) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
        }
        if (mode == MatchMode.MATCH_MODE_GUILD_WAR_PVP && (defenders.isEmpty() || defenders.size() > 5)) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
        }
        if (mode == MatchMode.MATCH_MODE_GUILD_WAR_SIEGE && (aiDefenders.isEmpty() || aiDefenders.size() > 5)) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
        }

        List<Long> humans = new ArrayList<>(attackers.size() + defenders.size());
        Map<Long, Integer> memberTeams = new LinkedHashMap<>();
        for (long pid : attackers) {
            if (pid == 0L || memberTeams.put(pid, 0) != null) {
                return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
            }
            humans.add(pid);
        }
        for (long pid : defenders) {
            if (pid == 0L || memberTeams.put(pid, 1) != null) {
                return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
            }
            humans.add(pid);
        }

        if (global != null) {
            return startGlobalGuildWar(request, kind, mode, humans, memberTeams, aiDefenders, d);
        }

        MemberPrecheck.Result checked = precheck.check(humans, d);
        if (!checked.passed()) {
            return switch (checked.reason()) {
                case OFFLINE -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, checked.offender(), 0L);
                case IN_BATTLE -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE,
                        checked.offender(), checked.offenderBattleId());
                case NO_LOCATION, TICKET_IN_FLIGHT -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY,
                        checked.offender(), 0L);
                default -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
            };
        }

        OptionalLong issued = ids.nextBattleId();
        if (issued.isEmpty()) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
        }
        long battleId = issued.getAsLong();

        Map<Long, String> tickets = new LinkedHashMap<>();
        List<GroupMember> group = new ArrayList<>(humans.size());
        for (long pid : humans) {
            String ticketId = ticketIds.get();
            tickets.put(pid, ticketId);
            group.add(new GroupMember(pid, ticketId, checked.zones().getOrDefault(pid, 0)));
        }
        int configId = request.getBattleConfigId();
        GroupTickets.Outcome created = groupTickets.create(configId, 0, group, d, "帮战直打");
        if (created instanceof GroupTickets.Outcome.Conflict conflict) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY, conflict.playerId(), 0L);
        }
        if (!(created instanceof GroupTickets.Outcome.Created)) {
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
        }
        if (d.expired()) {
            groupTickets.rollback(group, "帮战直打");
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
        }

        GatherPlan plan = GatherPlan.guildWar(mode, configId, humans, memberTeams, tickets, battleId,
                request.getActivityContext(), aiDefenders);
        CompletableFuture<GatherResult> launched;
        try {
            launched = gather.launch(plan);
        } catch (RuntimeException e) {
            log.error("[match] 帮战直打交给开局管线失败，回滚票据 battle={}", Long.toUnsignedString(battleId), e);
            groupTickets.rollback(group, "帮战直打");
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
        }
        launched.whenComplete((result, error) -> {
            boolean ok = error == null && result != null && result.ok();
            metrics.activityBattle(kind, ok ? ActivityResult.GATHER_OK : ActivityResult.GATHER_FAILED);
            if (!ok) {
                log.error("[match] 帮战直打 gather 失败 battle={} outcome={}", Long.toUnsignedString(battleId),
                        result == null ? "error" : result.outcome().label(), error);
            }
        });
        metrics.activityBattle(kind, ActivityResult.STARTED);
        return StartGuildWarBattleResponse.newBuilder().setBattleId(battleId).build();
    }

    /** 生产全球 SQL 权威模式下的帮战直打入口（kind=6，保留攻守阵营与守旗阵 AI 守卫快照）。 */
    private StartGuildWarBattleResponse startGlobalGuildWar(StartGuildWarBattleRequest request, int kind, MatchMode mode,
                                                            List<Long> humans, Map<Long, Integer> memberTeams,
                                                            List<BattlePlayerSnapshot> aiDefenders, Deadline d) {
        long requestId = request.getRequestId();
        if (requestId == 0L) {
            OptionalLong issued = ids.nextBattleId();
            if (issued.isEmpty()) {
                return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
            }
            requestId = issued.getAsLong();
        }
        long ownerGuildId = request.getActivityContext().getGuildWar().getAttackerGuildId();
        if (ownerGuildId == 0L) {
            ownerGuildId = humans.getFirst();
        }
        com.google.protobuf.ByteString stablePayload = request.toBuilder().clearAuth().build().toByteString();
        try {
            var key = com.game.match.global.pb.GlobalDirectIntentRow.newBuilder()
                    .setKind(6)
                    .setOwnerId(ownerGuildId)
                    .setRequestLow(requestId)
                    .build();
            var old = global.store().direct(key);
            if (old != null) {
                if (old.getCancelled()) {
                    return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
                }
                if (!old.getPayload().equals(stablePayload)) {
                    return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0L, 0L);
                }
                var f = old.getBattleId() != 0L
                        ? global.store().formation(old.getBattleId())
                        : global.reserveDirectGuildWar(key, stablePayload, mode.getNumber(), request.getBattleConfigId(),
                                humans, memberTeams, request.getActivityContext(), aiDefenders);
                if (f == null || f.getBattleId() == 0L || f.getState() == 5) {
                    return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
                }
                global.launchCommitted(f);
                return StartGuildWarBattleResponse.newBuilder()
                        .setBattleId(f.getBattleId())
                        .setFormationState(f.getState())
                        .build();
            }
            MemberPrecheck.Result checked = precheck.check(humans, d);
            if (!checked.passed()) {
                return switch (checked.reason()) {
                    case OFFLINE -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, checked.offender(), 0L);
                    case IN_BATTLE -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE,
                            checked.offender(), checked.offenderBattleId());
                    case NO_LOCATION, TICKET_IN_FLIGHT -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY,
                            checked.offender(), 0L);
                    default -> rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
                };
            }
            var f = global.reserveDirectGuildWar(key, stablePayload, mode.getNumber(), request.getBattleConfigId(),
                    humans, memberTeams, request.getActivityContext(), aiDefenders);
            if (f == null || f.getBattleId() == 0L || f.getState() == 5) {
                return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
            }
            global.launchCommitted(f);
            metrics.activityBattle(kind, ActivityResult.STARTED);
            return StartGuildWarBattleResponse.newBuilder()
                    .setBattleId(f.getBattleId())
                    .setFormationState(f.getState())
                    .build();
        } catch (RuntimeException e) {
            log.warn("[match] 全球帮战直打原意图提交结果尚未确认", e);
            return rejectedWar(kind, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
        }
    }

    public StartGuildWarBattleResponse unavailableGuildWar(StartGuildWarBattleRequest request) {
        return rejectedWar(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_WAR_VALUE,
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL, 0L, 0L);
    }

    private StartGuildWarBattleResponse rejectedWar(int kind, ActivityBattleReject reject, long offender, long offenderBattleId) {
        metrics.activityBattle(kind, switch (reject) {
            case ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT -> ActivityResult.INVALID;
            case ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE -> ActivityResult.OFFLINE;
            case ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE -> ActivityResult.IN_BATTLE;
            case ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY -> ActivityResult.NOT_READY;
            default -> ActivityResult.INTERNAL;
        });
        return StartGuildWarBattleResponse.newBuilder()
                .setReject(reject)
                .setOffenderPlayerId(offender)
                .setOffenderBattleId(offenderBattleId)
                .build();
    }

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
