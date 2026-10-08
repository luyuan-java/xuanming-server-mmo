package com.game.team.service;

import static com.game.team.match.FakeMatchTeamService.broken;
import static com.game.team.match.FakeMatchTeamService.done;
import static com.game.team.match.FakeMatchTeamService.gathered;
import static com.game.team.match.FakeMatchTeamService.rejected;
import static com.game.team.service.TeamMatchFixture.CONFIG;
import static com.game.team.service.TeamMatchFixture.LOCK_SECONDS;
import static com.game.team.service.TeamMatchFixture.ZONE;
import static com.game.team.service.TeamMatchFixture.byReason;
import static com.game.team.service.TeamMatchFixture.deadline;
import static com.game.team.service.TeamMatchFixture.u;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.Empty;
import com.game.proto.team.InviteToTeamRequest;
import com.game.proto.team.LeaveTeamRequest;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TransferLeaderRequest;
import com.game.team.match.FakeMatchTeamService;
import com.game.team.match.FakeMatchTeamService.Call;
import com.game.team.match.MatchTeamBattle;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.TeamRules;
import com.game.team.rules.TeamTips;
import com.game.team.service.TeamMatchFixture.Pushed;
import com.game.team.service.TeamMatchFixture.State;
import com.game.team.store.EndMatchResult;
import com.game.team.store.EndMatchStop;
import com.game.team.store.PinnedResult;
import com.game.team.store.Snapshot;
import com.game.team.store.TeamScript;
import com.game.team.store.TeamStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 整队开战（StartTeamMatch，211）的服务层用例：match-spec §7.6 的五步流程、§15.2「xm-team TeamServiceTest 扩充」一段，
 * 对照基线 {@code go/match/internal/team/team_battle_test.go}（#17、#18、#24、#28、#30 与 §E.3 的 10 条服务层用例；
 * 第 11 条 TestMatchLockRules 是纯函数，在 {@code TeamMatchLockRulesTest}）。
 *
 * <p>同一批用例跑在两种存储后端上（子类给后端）：{@code TeamServiceTest} 用内存替身（缺省档）、{@code TeamMatchRedisIntegrationTest}
 * 用真 Redis。xm-match 是 {@link FakeMatchTeamService}，经真的 {@link MatchTeamBattle} 适配——所以应答枚举的翻译、
 * 每跳预算与超时的下传也在被测范围里。要拨 Redis 时钟的用例（开战锁自然过期）在真 Redis 上跳过。
 *
 * <p>与基线的差别（都是跨进程带来的，match-spec M20）：预检整个在 xm-match（这里是替身的简化模型）；多了「调不通 / 应答不可信 →
 * 4030」「建票结果不明 → 先退票」「gather 结果不明 → 不退票」三个故障面；gather 是异步的，结果到达之前 211 已经回了 STARTING。
 */
abstract class TeamMatchScenarios {

    protected TeamMatchFixture fx;
    protected TeamService svc;

    /** 一个全新的存储后端（内存后端每次一个新实例；真 Redis 共用连接、id 随机不相撞）。 */
    abstract TeamMatchFixture.Backend backend();

    @BeforeEach
    void setUp() {
        fresh();
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    /** 换一套全新的夹具（一条用例里跑多个互不相干的情形时用：指标、替身、推送记录都从零开始）。 */
    private void fresh() {
        if (fx != null) {
            fx.cleanup();
        }
        fx = new TeamMatchFixture(backend());
        svc = fx.service;
    }

    private static void requireTip(TeamResponse resp, int code, long param) {
        assertThat(resp.getErrorMessage().getId()).as("resp=%s", resp).isEqualTo(code);
        if (param == 0) {
            assertThat(resp.getErrorMessage().getParametersList()).isEmpty();
        } else {
            assertThat(resp.getErrorMessage().getParametersList()).as("parameters 只放十进制 player_id").containsExactly(u(param));
        }
    }

    private static void requireOk(TeamResponse resp) {
        assertThat(resp.hasErrorMessage()).as("resp=%s", resp).isFalse();
    }

    private TeamResponse leave(long caller, long tid) {
        return svc.leaveTeam(caller, LeaveTeamRequest.newBuilder().setExpectedTeamId(tid).build(), deadline());
    }

    /** 所有终态计数之和：一次 211 恰好计一次。 */
    private double matchesTotal() {
        double sum = 0;
        for (String outcome : List.of("rejected", "internal", "unknown_code", "success", "gather_failed", "ticket_failed",
                "gather_unknown")) {
            sum += fx.matches(outcome);
        }
        return sum;
    }

    // ================================================================ #17：加锁之前的拒绝（team_battle_test.go:231-272）

    private record Rejection(String name, int caller, BiConsumer<TeamMatchFixture, long[]> setup, int code, int param,
                             int checks, String outcome) {
    }

    @Test
    void 加锁之前就拒绝_回包带本轮读到的同源视图_不写记录不建票不推送() {
        List<Rejection> cases = List.of(
                new Rejection("非队长", 2, (f, t) -> {
                }, TeamTips.NOT_LEADER, 0, 0, "rejected"),
                new Rejection("副本未开放组队", 1, (f, t) -> f.match.sizes.clear(), TeamTips.DUNGEON_NOT_OPEN, 0, 1, "rejected"),
                new Rejection("人数超过副本上限", 1, (f, t) -> f.match.open(CONFIG, 2), TeamTips.SIZE_EXCEEDED, 0, 1, "rejected"),
                new Rejection("队员离线", 1, (f, t) -> f.match.offline.add(t[3]), TeamTips.MEMBER_OFFLINE, 3, 1, "rejected"),
                new Rejection("队员在战斗中", 1, (f, t) -> f.match.inBattle.add(t[2]), TeamTips.MEMBER_IN_BATTLE, 2, 1, "rejected"),
                new Rejection("队员没有位置或已有在途票据", 1, (f, t) -> f.match.notReady.add(t[3]), TeamTips.MEMBER_NOT_READY, 3, 1,
                        "rejected"),
                new Rejection("xm-match 回内部故障", 1, (f, t) -> f.match.checkReply =
                        r -> done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0)), TeamTips.INTERNAL, 0, 1, "internal"),
                new Rejection("xm-match 调不通（传输失败）", 1, (f, t) -> f.match.checkReply = r -> broken("connection refused"),
                        TeamTips.INTERNAL, 0, 1, "internal"),
                new Rejection("应答全默认值（result 缺失）绝不读成通过", 1, (f, t) -> f.match.checkReply =
                        r -> done(TeamMatchCheckReply.getDefaultInstance()), TeamTips.INTERNAL, 0, 1, "internal"),
                new Rejection("通过但开战锁时长比 EndMatch 的截止还长", 1, (f, t) -> f.match.lockTtlSeconds = 111, TeamTips.INTERNAL, 0, 1,
                        "internal"));
        for (Rejection c : cases) {
            fresh();
            long[] t = fx.team(3);
            long tid = t[0];
            long caller = t[c.caller()];
            c.setup().accept(fx, t);
            State before = fx.state(tid, t[1], t[2], t[3]);
            fx.resetCounters();

            TeamResponse resp = fx.start(caller, tid);

            int hgets = fx.hgets.get();
            int reads = fx.evals.get(TeamScript.READ).get();
            requireTip(resp, c.code(), c.param() == 0 ? 0 : t[c.param()]);
            assertThat(resp.getTeam().getTeamId()).as("%s：拒绝回包带调用者当前视图", c.name()).isEqualTo(tid);
            assertThat(resp.getTeam().getMatchState()).as(c.name()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
            assertThat(resp.getTeam().getVersion()).as(c.name()).isEqualTo(before.version());
            assertThat(resp.getTeam().getMembershipEpoch()).as(c.name()).isEqualTo(before.indexes().get(caller).epoch());
            assertThat(hgets).as("%s：视图与判定同源（本轮 S_READ），没有再做自由读", c.name()).isZero();
            assertThat(reads).as(c.name()).isEqualTo(1);
            assertThat(fx.evals.get(TeamScript.COMMIT)).as("%s：加锁前拒绝不提交", c.name()).hasValue(0);
            assertThat(fx.state(tid, t[1], t[2], t[3])).as("%s：不写记录", c.name()).isEqualTo(before);
            assertThat(fx.match.checks).as(c.name()).hasSize(c.checks());
            assertThat(fx.match.tickets).as(c.name()).isEmpty();
            assertThat(fx.match.releases).as(c.name()).isEmpty();
            assertThat(fx.match.gathers).as(c.name()).isEmpty();
            assertThat(fx.takePushes()).as(c.name()).isEmpty();
            assertThat(fx.matches(c.outcome())).as("%s：指标 outcome=%s", c.name(), c.outcome()).isEqualTo(1);
            assertThat(matchesTotal()).as("%s：一次 211 恰好计一次", c.name()).isEqualTo(1);
        }
    }

    @Test
    void 预检超出请求预算_回4030_预算用完了同源视图照样带得出来() {
        long[] t = fx.team(2);
        fx.match.checkReply = r -> new CompletableFuture<>(); // xm-match 一直不应答
        State before = fx.state(t[0], t[1], t[2]);
        fx.resetCounters();
        Deadline budget = Deadline.after(250);

        TeamResponse resp = fx.start(t[1], t[0], budget);

        assertThat(budget.expired()).as("等到了请求截止才放弃").isTrue();
        requireTip(resp, TeamTips.INTERNAL, 0);
        assertThat(resp.getTeam().getTeamId()).as("自由读此刻必然失败（预算已用完），视图只能来自本轮 S_READ").isEqualTo(t[0]);
        assertThat(resp.getTeam().getVersion()).isEqualTo(before.version());
        assertThat(fx.hgets).hasValue(0);
        Call<?> call = fx.match.checks.get(0);
        assertThat(call.budget()).as("这一跳的超时随调用带给 xm-match 作预算").isBetween(1L, 250L);
        assertThat(call.timeoutMs()).as("每跳超时 = min(3 s, 剩余预算)").isEqualTo(call.budget());
        assertThat(fx.state(t[0], t[1], t[2])).isEqualTo(before);
        assertThat(fx.matches("internal")).isEqualTo(1);
    }

    // ================================================================ #17：建票失败 → 释放锁，推 MATCH_FAILED（:276-300）

    @Test
    void 建票失败_回4026带失败者_释放开战锁_全员收到带同一tip的MATCH_FAILED() {
        // 4 = 队员已有别的票据；1 = xm-match 自己的 Redis 出错、记在名单第一个人（队长）身上（match-spec Q27）
        for (int failedIndex : new int[] {4, 1}) {
            fresh();
            long[] t = fx.team(4);
            long tid = t[0];
            long failed = t[failedIndex];
            fx.match.failTickets(failed);
            long ver0 = fx.versionOf(tid);

            TeamResponse resp = fx.start(t[1], tid);

            requireTip(resp, TeamTips.MEMBER_NOT_READY, failed);
            assertThat(resp.getTeam().getTeamId()).isEqualTo(tid);
            assertThat(fx.match.gathers).as("建票失败不进 gather").isEmpty();
            assertThat(fx.match.tickets).hasSize(1);
            assertThat(fx.match.releases).as("FAILED = xm-match 保证没有留下本次的票，不需要退票").isEmpty();
            assertThat(fx.record(tid).getMatchLockToken()).as("锁已释放").isEmpty();
            assertThat(fx.versionOf(tid)).as("加锁一次 + 清锁一次").isEqualTo(ver0 + 2);
            List<Pushed> pushes = fx.takePushes();
            assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED).keySet())
                    .as("发起人看回包，其余队员收 MATCH_STARTED").containsExactlyInAnyOrder(t[2], t[3], t[4]);
            Map<Long, TeamSnapshotS2C> failedPushes = byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
            assertThat(failedPushes.keySet()).as("结果推全员（含发起人）").containsExactlyInAnyOrder(t[1], t[2], t[3], t[4]);
            for (TeamSnapshotS2C s : failedPushes.values()) {
                assertThat(s.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
                assertThat(s.getTeam().getVersion()).isEqualTo(ver0 + 2);
                assertThat(s.getActorId()).isZero();
                assertThat(s.getTip().getId()).as("MATCH_FAILED 的原因在 tip 字段").isEqualTo(TeamTips.MEMBER_NOT_READY);
                assertThat(s.getTip().getParametersList()).as("parameters[0] = 出问题的成员").containsExactly(u(failed));
            }
            assertThat(fx.matches("ticket_failed")).isEqualTo(1);
            assertThat(matchesTotal()).isEqualTo(1);
        }
    }

    // ================================================================ 建票结果不明（跨进程才有的故障面，match-spec §7.6 第 3 步）

    @Test
    void 建票结果不明_回4030_后台先按本次票号退票再清锁_全员收到不带tip的MATCH_FAILED() {
        Map<String, Function<TeamTicketsRequest, CompletableFuture<TeamTicketsReply>>> cases = new LinkedHashMap<>();
        cases.put("回包丢失（传输失败）", r -> broken("connection reset"));
        cases.put("xm-match 没有执行（预算已过期 / 过载 / 租约无效）", r -> done(TeamTicketsReply.newBuilder()
                .setStatus(TeamTicketsStatus.TEAM_TICKETS_EXPIRED).build()));
        cases.put("应答全默认值（status 缺失）绝不读成建成", r -> done(TeamTicketsReply.getDefaultInstance()));
        cases.put("FAILED 却没有失败者", r -> done(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_FAILED)
                .build()));
        for (Map.Entry<String, Function<TeamTicketsRequest, CompletableFuture<TeamTicketsReply>>> c : cases.entrySet()) {
            fresh();
            long[] t = fx.team(3);
            long tid = t[0];
            fx.match.ticketsReply = c.getValue();
            AtomicReference<String> lockAtRelease = new AtomicReference<>();
            fx.match.releaseReply = r -> {
                lockAtRelease.set(fx.record(tid).getMatchLockToken());
                return done(Empty.getDefaultInstance());
            };
            long ver0 = fx.versionOf(tid);

            TeamResponse resp = fx.start(t[1], tid);

            requireTip(resp, TeamTips.INTERNAL, 0);
            assertThat(fx.match.tickets).as(c.getKey()).hasSize(1);
            assertThat(fx.match.releases).as("%s：迟到的建票可能已执行，必须退票", c.getKey()).hasSize(1);
            Call<com.game.api.proto.TeamTicketsRelease> release = fx.match.releases.get(0);
            assertThat(release.request().getTicketIdsMap()).as("按本次建票的票号退")
                    .isEqualTo(fx.match.tickets.get(0).request().getTicketIdsMap()).hasSize(3);
            assertThat(release.budget()).as("退票用独立的 3 s 预算，不继承请求预算").isBetween(1L, TeamService.RELEASE_TICKETS_BUDGET_MS);
            assertThat(release.timeoutMs()).as("预算附件 = 每跳超时").isEqualTo(release.budget());
            assertThat(lockAtRelease.get()).as("%s：先退票、后清锁", c.getKey()).isNotEmpty();
            assertThat(fx.match.gathers).as("gather 没有发出").isEmpty();
            assertThat(fx.record(tid).getMatchLockToken()).as("锁已释放").isEmpty();
            assertThat(fx.versionOf(tid)).isEqualTo(ver0 + 2);
            Map<Long, TeamSnapshotS2C> failed = byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
            assertThat(failed.keySet()).as(c.getKey()).containsExactlyInAnyOrder(t[1], t[2], t[3]);
            for (TeamSnapshotS2C s : failed.values()) {
                assertThat(s.hasTip()).as("结果不明不是哪名成员的问题：不带 tip").isFalse();
                assertThat(s.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
            }
            assertThat(fx.matches("internal")).as(c.getKey()).isEqualTo(1);
            assertThat(matchesTotal()).isEqualTo(1);
        }
    }

    @Test
    void 建票超出请求预算_按结果不明处理_退票没调通也照常清锁() {
        long[] t = fx.team(2);
        long tid = t[0];
        fx.match.ticketsReply = r -> new CompletableFuture<>(); // 建票一直不应答
        fx.match.releaseReply = r -> broken("xm-match 不在");

        TeamResponse resp = fx.start(t[1], tid, Deadline.after(300));

        requireTip(resp, TeamTips.INTERNAL, 0);
        assertThat(fx.match.tickets.get(0).timeoutMs()).as("建票这一跳的超时也收口到剩余预算")
                .isEqualTo(fx.match.tickets.get(0).budget()).isBetween(1L, 300L);
        assertThat(fx.match.releases).hasSize(1);
        assertThat(fx.match.releases.get(0).budget()).as("退票不继承已经用完的请求预算").isGreaterThan(300L);
        assertThat(fx.record(tid).getMatchLockToken()).as("退票失败只记日志，锁照常清").isEmpty();
        assertThat(byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED).keySet())
                .containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(fx.matches("internal")).isEqualTo(1);
    }

    // ================================================================ #18：名单顺序、锁字段、gather 结束后清锁并推结果（:304-367）

    @Test
    void 开战受理_名单队长在前再按入队序_票号每人一个UUID_gather结束后清锁并推结果() {
        for (boolean gatherOk : new boolean[] {true, false}) {
            fresh();
            long[] t = fx.team(3);
            long tid = t[0];
            // 队长转给 join_seq = 2 的 t[2]：名单应为 [t2, t1, t3]
            requireOk(svc.transferLeader(t[1], TransferLeaderRequest.newBuilder().setTargetPlayerId(t[2]).setExpectedTeamId(tid)
                    .build(), deadline()));
            fx.takePushes();
            long ver0 = fx.versionOf(tid);
            List<Long> roster = List.of(t[2], t[1], t[3]);
            AtomicReference<TeamRecord> duringGather = new AtomicReference<>();
            fx.match.gatherReply = r -> {
                duringGather.set(fx.record(tid));
                return done(gathered(gatherOk, 7001));
            };
            long before = fx.nowMs();

            TeamResponse resp = fx.start(t[2], tid);

            long after = fx.nowMs();
            requireOk(resp);
            assertThat(resp.getTeam().getMatchState()).as("回包 = 加锁提交的视图").isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
            assertThat(resp.getTeam().getVersion()).isEqualTo(ver0 + 1);

            assertThat(fx.match.checks).hasSize(1);
            Call<com.game.api.proto.TeamMatchCheckRequest> check = fx.match.checks.get(0);
            assertThat(check.request().getRosterList()).as("队长在前再按 join_seq").isEqualTo(roster);
            assertThat(check.request().getBattleConfigId()).isEqualTo(CONFIG);
            assertThat(check.budget()).as("带给 xm-match 的预算 = 这一跳的超时，不是整请求的剩余预算（3500 ms）")
                    .isBetween(1L, MatchTeamBattle.HOP_TIMEOUT_MS);
            assertThat(check.timeoutMs()).as("每跳超时 = min(3 s, 剩余预算)，与预算附件是同一个值").isEqualTo(check.budget());

            assertThat(fx.match.tickets).hasSize(1);
            Call<TeamTicketsRequest> tickets = fx.match.tickets.get(0);
            assertThat(tickets.request().getRosterList()).isEqualTo(roster);
            assertThat(tickets.request().getTeamId()).as("票据带 team_id").isEqualTo(tid);
            assertThat(tickets.request().getBattleConfigId()).isEqualTo(CONFIG);
            assertThat(tickets.request().getZonesMap()).as("预检给的 zone 原样带回")
                    .isEqualTo(Map.of(t[1], ZONE, t[2], ZONE, t[3], ZONE));
            Map<Long, String> ticketIds = tickets.request().getTicketIdsMap();
            assertThat(ticketIds.keySet()).containsExactlyInAnyOrderElementsOf(roster);
            assertThat(ticketIds.values()).as("每人一个 UUID，互不相同").doesNotHaveDuplicates()
                    .allMatch(id -> id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
            assertThat(tickets.budget()).isBetween(1L, check.budget());
            assertThat(tickets.timeoutMs()).as("建票：xm-match 的截止不晚于本端这一跳放弃的时刻").isEqualTo(tickets.budget());

            assertThat(fx.match.gathers).hasSize(1);
            Call<com.game.api.proto.TeamGatherRequest> gather = fx.match.gathers.get(0);
            assertThat(gather.request().getRosterList()).isEqualTo(roster);
            assertThat(gather.request().getTicketIdsMap()).as("gather 用建票的那一批票号").isEqualTo(ticketIds);
            assertThat(gather.request().getTeamId()).isEqualTo(tid);
            assertThat(gather.request().getBattleConfigId()).isEqualTo(CONFIG);
            assertThat(gather.timeoutMs()).as("长挂调用的超时 = 开战锁时长").isEqualTo(LOCK_SECONDS * 1000L);
            assertThat(gather.budgetMs()).as("gather 不受请求预算约束：不带预算附件").isNull();
            assertThat(fx.match.releases).isEmpty();

            TeamRecord locked = duringGather.get();
            assertThat(locked.getMatchLockToken()).isNotEmpty().isNotIn(ticketIds.values());
            assertThat(locked.getMatchLockRosterList()).as("锁内名单 == roster").isEqualTo(roster);
            assertThat(TeamRules.memberIds(locked)).containsExactlyInAnyOrderElementsOf(roster);
            assertThat(locked.getMatchLockExpireAtMs() - LOCK_SECONDS * 1000L).as("锁截止 = 本轮 S_READ 的 Redis TIME + 时长")
                    .isBetween(before, after);

            TeamRecord end = fx.record(tid);
            assertThat(end.getMatchLockToken()).as("gather 结束后锁被清除").isEmpty();
            assertThat(end.getMatchLockRosterList()).isEmpty();
            assertThat(end.getMatchLockExpireAtMs()).isZero();
            assertThat(fx.versionOf(tid)).isEqualTo(ver0 + 2);

            List<Pushed> pushes = fx.takePushes();
            Map<Long, TeamSnapshotS2C> started = byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED);
            assertThat(started.keySet()).as("发起人看回包，其余队员收 MATCH_STARTED").containsExactlyInAnyOrder(t[1], t[3]);
            for (TeamSnapshotS2C s : started.values()) {
                assertThat(s.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
                assertThat(s.getTeam().getVersion()).isEqualTo(ver0 + 1);
                assertThat(s.getActorId()).isEqualTo(t[2]);
            }
            Map<Long, TeamSnapshotS2C> ended = byReason(pushes, gatherOk ? TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED
                    : TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
            assertThat(ended.keySet()).as("结果推全员（含发起人）").containsExactlyInAnyOrderElementsOf(roster);
            for (TeamSnapshotS2C s : ended.values()) {
                assertThat(s.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
                assertThat(s.getTeam().getVersion()).isEqualTo(ver0 + 2);
                assertThat(s.hasTip()).as("gather 结果不带 tip").isFalse();
                assertThat(s.getActorId()).isZero();
            }
            assertThat(byReason(pushes, gatherOk ? TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED
                    : TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED)).isEmpty();
            assertThat(fx.matches(gatherOk ? "success" : "gather_failed")).isEqualTo(1);
            assertThat(matchesTotal()).isEqualTo(1);
        }
    }

    // ================================================================ gather 是长挂的异步调用；传输失败 = 结果不明（§12.1 第 19 条）

    @Test
    void gather还没结束时211已经回了STARTING_结果到达后才清锁() {
        long[] t = fx.team(2);
        long tid = t[0];
        CompletableFuture<TeamGatherReply> pending = new CompletableFuture<>();
        fx.match.gatherReply = r -> pending;
        long ver0 = fx.versionOf(tid);

        TeamResponse resp = fx.start(t[1], tid);

        requireOk(resp);
        assertThat(resp.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(fx.record(tid).getMatchLockToken()).as("gather 在途：锁还在").isNotEmpty();
        assertThat(byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED).keySet()).containsExactly(t[2]);
        assertThat(matchesTotal()).as("终态在 gather 收尾时才计").isZero();
        requireTip(fx.start(t[1], tid), TeamTips.IN_MATCH, 0);
        assertThat(fx.matches("rejected")).isEqualTo(1);

        pending.complete(gathered(true, 7002));

        assertThat(fx.record(tid).getMatchLockToken()).isEmpty();
        assertThat(fx.versionOf(tid)).isEqualTo(ver0 + 2);
        assertThat(byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED).keySet())
                .containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(fx.matches("success")).isEqualTo(1);
    }

    @Test
    void gather传输失败_结果不明_推MATCH_FAILED计gather_unknown_绝不退票() {
        long[] t = fx.team(3);
        long tid = t[0];
        CompletableFuture<TeamGatherReply> pending = new CompletableFuture<>();
        fx.match.gatherReply = r -> pending;
        long ver0 = fx.versionOf(tid);
        requireOk(fx.start(t[1], tid));
        fx.takePushes();

        pending.completeExceptionally(new TimeoutException("xm-match 中途退出 / 网络分区 / 调用超时"));

        assertThat(fx.match.releases).as("gather 可能仍在跑：票据由它收尾或按 matched TTL 自愈，绝不退票").isEmpty();
        assertThat(fx.record(tid).getMatchLockToken()).as("锁照常清，客户端不停在 STARTING").isEmpty();
        assertThat(fx.versionOf(tid)).isEqualTo(ver0 + 2);
        Map<Long, TeamSnapshotS2C> failed = byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
        assertThat(failed.keySet()).containsExactlyInAnyOrder(t[1], t[2], t[3]);
        assertThat(failed.values()).allMatch(s -> !s.hasTip());
        assertThat(fx.matches("gather_unknown")).isEqualTo(1);
        assertThat(matchesTotal()).isEqualTo(1);
    }

    /**
     * 评审 T1-02（match-spec §7.5「xm-team 进程退出：match 照样把 gather 跑完，锁自然过期」）：本进程优雅停机时自己的 Dubbo 引用被销毁，
     * 挂着的 gather 若因此异常完成，不能照「结果不明」清锁并推 MATCH_FAILED——xm-match 那边的 gather 还在跑，成员随后会被拉进战斗。
     */
    @Test
    void 本进程停机之后才异常完成的gather_只计gather_unknown_不清锁不推送不退票() {
        long[] t = fx.team(3);
        long tid = t[0];
        CompletableFuture<TeamGatherReply> pending = new CompletableFuture<>();
        fx.match.gatherReply = r -> pending;
        long ver0 = fx.versionOf(tid);
        requireOk(fx.start(t[1], tid));
        fx.takePushes();
        String token = fx.record(tid).getMatchLockToken();
        fx.resetCounters();

        fx.stopping = true; // 上下文关闭事件已到；Dubbo 随后销毁引用
        pending.completeExceptionally(new IllegalStateException("CANCELLED"));

        // 先看调用计数（下面的观察读也走同一个计数的存储）
        assertThat(fx.evals.values().stream().mapToInt(AtomicInteger::get).sum()).as("没有碰存储：连 EndMatch 的读都不做").isZero();
        assertThat(fx.hgets).hasValue(0);
        assertThat(fx.record(tid).getMatchLockToken()).as("锁原样留着，靠自然过期（同进程崩溃）").isEqualTo(token).isNotEmpty();
        assertThat(fx.versionOf(tid)).as("没有任何提交").isEqualTo(ver0 + 1);
        assertThat(fx.takePushes()).as("不推 MATCH_FAILED：对端的 gather 并没有失败").isEmpty();
        assertThat(fx.match.releases).as("gather 已经发出：绝不退票").isEmpty();
        assertThat(fx.matches("gather_unknown")).as("这次 211 仍然恰好计一次").isEqualTo(1);
        assertThat(matchesTotal()).isEqualTo(1);
    }

    @Test
    void 停机之后正常到达的gather结果照常收尾_成功与失败都是() {
        for (boolean gatherOk : new boolean[] {true, false}) {
            fresh();
            long[] t = fx.team(2);
            long tid = t[0];
            CompletableFuture<TeamGatherReply> pending = new CompletableFuture<>();
            fx.match.gatherReply = r -> pending;
            long ver0 = fx.versionOf(tid);
            requireOk(fx.start(t[1], tid));
            fx.takePushes();

            fx.stopping = true;
            pending.complete(gathered(gatherOk, 7006));

            assertThat(fx.record(tid).getMatchLockToken()).as("结果是可信的：照常清锁 ok=%s", gatherOk).isEmpty();
            assertThat(fx.versionOf(tid)).isEqualTo(ver0 + 2);
            assertThat(byReason(fx.takePushes(), gatherOk ? TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED
                    : TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED).keySet()).containsExactlyInAnyOrder(t[1], t[2]);
            assertThat(fx.matches(gatherOk ? "success" : "gather_failed")).isEqualTo(1);
            assertThat(matchesTotal()).isEqualTo(1);
        }
    }

    @Test
    void 收尾执行器已满_211照常回包并计数_开战锁留给自然过期() {
        long[] t = fx.team(2);
        long tid = t[0];
        fx.rejectBackground = true;

        TeamResponse resp = fx.start(t[1], tid);

        requireOk(resp);
        assertThat(resp.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(fx.matches("success")).as("计数在投递之前：收尾被拒也恰好计一次").isEqualTo(1);
        assertThat(fx.record(tid).getMatchLockToken()).as("没有清锁：靠锁自然过期").isNotEmpty();
        assertThat(byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED)).isEmpty();

        // 建票失败的收尾同样投不进去：同步结果不受影响
        fresh();
        long[] t2 = fx.team(2);
        fx.rejectBackground = true;
        fx.match.failTickets(t2[2]);
        requireTip(fx.start(t2[1], t2[0]), TeamTips.MEMBER_NOT_READY, t2[2]);
        assertThat(fx.record(t2[0]).getMatchLockToken()).isNotEmpty();
        assertThat(fx.matches("ticket_failed")).isEqualTo(1);
    }

    // ================================================================ #18 / #30：锁自然过期后 EndMatch 不写；之后能再次开战（:371-395）

    @Test
    void 开战锁自然过期后_EndMatch不写只推当前视图_之后能再次开战() {
        assumeTrue(fx.backend.advance(0), "要拨 Redis 时钟：只在内存后端上跑");
        long[] t = fx.team(2);
        long tid = t[0];
        // gather 途中 Redis 时钟越过锁截止：EndMatch 发现锁已自然过期 → 停止、不写，仍推一次当前视图
        fx.match.gatherReply = r -> {
            fx.backend.advance(LOCK_SECONDS * 1000L);
            return done(gathered(true, 7003));
        };

        requireOk(fx.start(t[1], tid));

        assertThat(fx.record(tid).getMatchLockToken()).as("锁已过期的记录不写（token 残留但按时钟无效）").isNotEmpty();
        long verAfter = fx.versionOf(tid);
        Map<Long, TeamSnapshotS2C> ended = byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED);
        assertThat(ended.keySet()).containsExactlyInAnyOrder(t[1], t[2]);
        for (TeamSnapshotS2C s : ended.values()) {
            assertThat(s.getTeam().getMatchState()).as("按 Redis 时钟锁已无效").isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
            assertThat(s.getTeam().getVersion()).as("不经提交的视图版本不变").isEqualTo(verAfter);
            assertThat(s.getActorId()).isZero();
        }
        assertThat(fx.sleeps).as("停止分支不退避").isEmpty();

        fx.match.gatherReply = r -> done(gathered(true, 7004));
        TeamResponse again = fx.start(t[1], tid);

        requireOk(again);
        assertThat(again.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(fx.match.gathers).as("锁过期后能再次开战").hasSize(2);
        assertThat(fx.record(tid).getMatchLockToken()).isEmpty();
    }

    // ================================================================ #18：并发两次开战，只有一次拿到锁（:399-429）

    @Test
    void 并发两次开战_只有一次拿到锁_另一次回4023带STARTING视图() {
        long[] t = fx.team(2);
        long tid = t[0];
        CompletableFuture<TeamGatherReply> pending = new CompletableFuture<>();
        fx.match.gatherReply = r -> pending;
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<TeamResponse> inner = new AtomicReference<>();
        // 第二个请求在第一个预检之后、加锁之前完成加锁
        fx.match.afterCheckPassed = r -> {
            if (fired.compareAndSet(false, true)) {
                inner.set(fx.start(t[1], tid));
            }
        };

        TeamResponse outer = fx.start(t[1], tid);

        requireOk(inner.get());
        assertThat(inner.get().getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        requireTip(outer, TeamTips.IN_MATCH, 0);
        assertThat(outer.getTeam().getMatchState()).as("重复开战视为进行中").isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(outer.getTeam().getVersion()).isEqualTo(inner.get().getTeam().getVersion());
        assertThat(fx.match.tickets).as("只有一次拿到锁并建票").hasSize(1);
        assertThat(fx.match.gathers).hasSize(1);
        assertThat(fx.commitRetries()).as("旧快照上的加锁被 ver CAS 挡住，记一次重试").isEqualTo(1);
        assertThat(fx.matches("rejected")).isEqualTo(1);
        assertThat(fx.record(tid).getMatchLockToken()).as("没被第一个请求的「按 token 确认」误清").isNotEmpty();

        pending.complete(gathered(true, 7005));
        assertThat(fx.record(tid).getMatchLockToken()).isEmpty();
        assertThat(fx.matches("success")).isEqualTo(1);
    }

    // ================================================================ #24：迟到的 StartTeamMatch 不给玩家的新队伍开战（:433-459）

    @Test
    void 迟到的开战请求不给玩家的新队伍开战_回4013与新队视图() {
        long[] t = fx.team(2);
        long oldTeam = t[0];
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<Long> newTeam = new AtomicReference<>();
        fx.afterRead.set(bind -> {
            if (bind.playerId() != t[1] || !fired.compareAndSet(false, true)) {
                return;
            }
            // 旧请求停在 S_READ 之后：玩家离开 A 并新建 B
            requireOk(leave(t[1], oldTeam));
            newTeam.set(fx.create(t[1]));
        });

        TeamResponse resp = fx.start(t[1], oldTeam);

        requireTip(resp, TeamTips.NO_TEAM, 0);
        assertThat(newTeam.get()).isNotNull();
        assertThat(resp.getTeam().getTeamId()).as("回调用者当前（B）的视图").isEqualTo(newTeam.get());
        assertThat(fx.versionOf(newTeam.get())).as("B 只有建队那一次提交").isEqualTo(1);
        assertThat(fx.record(newTeam.get()).getMatchLockToken()).as("不给 B 加锁").isEmpty();
        assertThat(fx.record(oldTeam).getMatchLockToken()).as("A 的旧快照加锁被 ver CAS 挡住").isEmpty();
        assertThat(fx.match.tickets).isEmpty();
        assertThat(fx.match.gathers).isEmpty();
    }

    // ================================================================ #28：预检与加锁之间名单变化 → 整轮重来（:463-520）

    @Test
    void 预检与加锁之间队员离队_整轮重来重新预检_不给已离队者建票() {
        long[] t = fx.team(3);
        long tid = t[0];
        AtomicBoolean fired = new AtomicBoolean();
        fx.match.afterCheckPassed = r -> {
            if (fired.compareAndSet(false, true)) {
                requireOk(leave(t[3], tid));
            }
        };
        AtomicReference<TeamRecord> duringGather = new AtomicReference<>();
        fx.match.gatherReply = r -> {
            duringGather.set(fx.record(tid));
            return done(gathered(true, 7006));
        };

        requireOk(fx.start(t[1], tid));

        assertThat(fx.match.checkedRosters()).as("加锁 {0} 后整轮重来、重新预检")
                .containsExactly(List.of(t[1], t[2], t[3]), List.of(t[1], t[2]));
        assertThat(fx.match.tickets).hasSize(1);
        assertThat(fx.match.tickets.get(0).request().getRosterList()).as("不给已离队者建票").containsExactly(t[1], t[2]);
        assertThat(duringGather.get().getMatchLockRosterList()).containsExactly(t[1], t[2]);
        assertThat(TeamRules.memberIds(duringGather.get())).as("锁内 match_lock_roster == members").containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(fx.commitRetries()).isEqualTo(1);
    }

    @Test
    void 预检与加锁之间被邀请人自己接受邀请_整轮重来_新加入者也经过预检() {
        long[] t = fx.team(2);
        long tid = t[0];
        long invitee = fx.player();
        requireOk(svc.inviteToTeam(t[1], InviteToTeamRequest.newBuilder().setTargetPlayerId(invitee).setExpectedTeamId(tid).build(),
                deadline()));
        fx.takePushes();
        AtomicBoolean fired = new AtomicBoolean();
        fx.match.afterCheckPassed = r -> {
            if (fired.compareAndSet(false, true)) {
                // 队外被邀请人自己接受，不经队长：同样让 ver + 1
                requireOk(svc.respondInvite(invitee, RespondInviteRequest.newBuilder().setTeamId(tid).setAccept(true).build(),
                        deadline()));
            }
        };
        AtomicReference<TeamRecord> duringGather = new AtomicReference<>();
        fx.match.gatherReply = r -> {
            duringGather.set(fx.record(tid));
            return done(gathered(true, 7007));
        };

        requireOk(fx.start(t[1], tid));

        assertThat(fx.match.checkedRosters()).containsExactly(List.of(t[1], t[2]), List.of(t[1], t[2], invitee));
        assertThat(fx.match.tickets).hasSize(1);
        assertThat(fx.match.tickets.get(0).request().getRosterList()).containsExactly(t[1], t[2], invitee);
        assertThat(duringGather.get().getMatchLockRosterList()).containsExactly(t[1], t[2], invitee);
        assertThat(TeamRules.memberIds(duringGather.get())).containsExactlyInAnyOrder(t[1], t[2], invitee);
    }

    // ================================================================ §E.3：开战锁提交结果未知 → 按 token 补偿（:522-598；§12.1 第 9 条）

    /**
     * 在开战锁的 S_COMMIT 发出前插一次（只触发一次）：{@code landed} 时先自己执行同一段 EVAL（第一次投递已落锁）；随后 {@code lost} 时抛
     * 依赖故障（回复丢失，调用方拿到错误），否则正常返回（Redisson 重发，真正的 EVAL 回 {0}）。
     */
    private void injectLockEval(boolean landed, boolean lost) {
        AtomicBoolean fired = new AtomicBoolean();
        fx.beforeCommit.set((decision, keys, args) -> {
            if (decision.reason() != TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED || !fired.compareAndSet(false, true)) {
                return;
            }
            if (landed) {
                try {
                    fx.backend.redis().eval(TeamScript.COMMIT, keys, args).toCompletableFuture().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            if (lost) {
                throw new DependencyException("read tcp: i/o timeout");
            }
        });
    }

    @Test
    void 开战锁提交报错但已落锁_后台按token清锁并推MATCH_FAILED_队长可立即重试() {
        long[] t = fx.team(2);
        long tid = t[0];
        long ver0 = fx.versionOf(tid);
        injectLockEval(true, true);

        requireTip(fx.start(t[1], tid), TeamTips.INTERNAL, 0);

        assertThat(fx.record(tid).getMatchLockToken()).as("锁不白挂到自然过期").isEmpty();
        assertThat(fx.versionOf(tid)).as("落锁一次 + 补偿清锁一次").isEqualTo(ver0 + 2);
        assertThat(fx.match.tickets).isEmpty();
        List<Pushed> pushes = fx.takePushes();
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED)).isEmpty();
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED).keySet()).containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(fx.matches("internal")).isEqualTo(1);

        requireOk(fx.start(t[1], tid));
        assertThat(fx.match.gathers).as("锁已清，重试能开战").hasSize(1);
    }

    @Test
    void 开战锁提交报错且没有写入_补偿只读_不写不推() {
        long[] t = fx.team(2);
        long tid = t[0];
        injectLockEval(false, true);
        State before = fx.state(tid, t[1], t[2]);

        requireTip(fx.start(t[1], tid), TeamTips.INTERNAL, 0);

        assertThat(fx.state(tid, t[1], t[2])).isEqualTo(before);
        assertThat(fx.takePushes()).as("锁没写入不推 MATCH_FAILED：不凭空多出一场从没开始过的开战").isEmpty();
        assertThat(fx.sleeps).as("token 不符即停止，不退避").isEmpty();
        assertThat(fx.match.tickets).isEmpty();
    }

    @Test
    void 开战锁的EVAL被重发_重来之前按token清掉自己的锁_本次请求照常开战() {
        long[] t = fx.team(2);
        long tid = t[0];
        long ver0 = fx.versionOf(tid);
        injectLockEval(true, false);

        TeamResponse resp = fx.start(t[1], tid);

        requireOk(resp);
        assertThat(resp.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(fx.match.checkedRosters()).as("整轮重来、重新预检，没被自己的锁挡成 4023")
                .containsExactly(List.of(t[1], t[2]), List.of(t[1], t[2]));
        assertThat(fx.match.tickets).hasSize(1);
        assertThat(fx.match.gathers).hasSize(1);
        assertThat(fx.record(tid).getMatchLockToken()).isEmpty();
        assertThat(fx.versionOf(tid)).as("重发落的锁 + 确认清锁 + 本轮加锁 + EndMatch").isEqualTo(ver0 + 4);
        List<Pushed> pushes = fx.takePushes();
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED).keySet()).containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED).keySet()).containsExactlyInAnyOrder(t[1], t[2]);
        assertThat(fx.commitRetries()).isEqualTo(1);
        assertThat(fx.matches("success")).isEqualTo(1);
        assertThat(matchesTotal()).isEqualTo(1);
    }

    // ================================================================ #30：EndMatch 连续冲突最终清锁；停止分支不写（:602-671）

    @Test
    void EndMatch连续冲突后最终清锁_每次冲突退避一次_锁期间的申请都保留() {
        long[] t = fx.team(2);
        long tid = t[0];
        List<Long> outsiders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            outsiders.add(fx.player());
        }
        AtomicInteger injected = new AtomicInteger();
        fx.afterMatchRead.set(teamId -> {
            int i = injected.get();
            if (i >= outsiders.size()) {
                return;
            }
            injected.incrementAndGet();
            // 锁期间队外玩家申请照常允许，每次让 ver + 1，制造 EndMatch 的版本冲突
            requireOk(fx.apply(outsiders.get(i), t[1]));
        });

        requireOk(fx.start(t[1], tid));

        assertThat(injected).hasValue(outsiders.size());
        TeamRecord rec = fx.record(tid);
        assertThat(rec.getMatchLockToken()).as("连续 %d 次冲突后最终清锁（没有 3 次上限）", outsiders.size()).isEmpty();
        assertThat(rec.getApplicationsList()).as("锁期间的申请都保留").hasSize(outsiders.size());
        assertThat(fx.sleeps).as("每次冲突退避一次").hasSize(outsiders.size());
        for (int i = 0; i < fx.sleeps.size(); i++) {
            long base = Math.min(TeamStore.END_MATCH_BACKOFF_INITIAL_MS << i, TeamStore.END_MATCH_BACKOFF_MAX_MS);
            assertThat((double) fx.sleeps.get(i)).as("第 %d 次退避：50 ms 起翻倍、上限 1 s、±20%%", i)
                    .isBetween(Math.floor(base * 0.8), Math.ceil(base * 1.2));
        }
        assertThat(byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED).keySet())
                .containsExactlyInAnyOrder(t[1], t[2]);
    }

    @Test
    void EndMatch的停止分支都不写_token不符时仍给队员推一次当前视图() {
        long[] t = fx.team(2);
        long tid = t[0];
        Snapshot snap = fx.store.read(t[1], tid, deadline());
        List<Long> roster = TeamRules.matchRoster(snap.record());
        PinnedResult lock = fx.store.commitMatchLock(snap, t[1], "tok-A", roster, snap.nowMs() + LOCK_SECONDS * 1000L, fx.sessions,
                deadline());
        assertThat(lock.commit()).isNotNull();

        // token 不符（锁已被清或重新加锁）：停止、不写，仍给队员推一次当前视图（锁仍有效 → STARTING）
        State before = fx.state(tid, t[1], t[2]);
        svc.finishMatch(tid, "tok-B", true, roster, null);
        assertThat(fx.state(tid, t[1], t[2])).isEqualTo(before);
        Map<Long, TeamSnapshotS2C> current = byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED);
        assertThat(current.keySet()).containsExactlyInAnyOrderElementsOf(roster);
        for (TeamSnapshotS2C s : current.values()) {
            assertThat(s.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
            assertThat(s.getTeam().getVersion()).isEqualTo(lock.commit().version());
        }
        assertThat(fx.store.endMatch(tid, "tok-B", true, fx.sessions).stop()).isEqualTo(EndMatchStop.TOKEN_MISMATCH);

        // 记录不存在：停止、不写
        EndMatchResult missing = fx.store.endMatch(fx.unusedTid(), "tok-A", true, fx.sessions);
        assertThat(missing.stop()).isEqualTo(EndMatchStop.RECORD_MISSING);
        assertThat(missing.commit()).isNull();
        assertThat(fx.state(tid, t[1], t[2])).isEqualTo(before);

        // 锁已按 Redis 时钟过期：停止、不写（要拨时钟：只在内存后端上验）
        if (fx.backend.advance(LOCK_SECONDS * 1000L)) {
            EndMatchResult expired = fx.store.endMatch(tid, "tok-A", false, fx.sessions);
            assertThat(expired.stop()).isEqualTo(EndMatchStop.LOCK_EXPIRED);
            assertThat(expired.commit()).isNull();
            assertThat(fx.state(tid, t[1], t[2])).isEqualTo(before);
        }
        assertThat(fx.sleeps).as("停止分支不退避").isEmpty();
    }

    @Test
    void EndMatch到截止仍清不掉锁_放弃并仍给队员推一次当前视图() {
        long[] t = fx.team(2);
        long tid = t[0];
        Snapshot snap = fx.store.read(t[1], tid, deadline());
        List<Long> roster = TeamRules.matchRoster(snap.record());
        PinnedResult lock = fx.store.commitMatchLock(snap, t[1], "tok-A", roster, snap.nowMs() + LOCK_SECONDS * 1000L, fx.sessions,
                deadline());
        State before = fx.state(tid, t[1], t[2]);
        // 清锁的每一轮读都失败（Redis 持续故障）；单调时钟每问一次走 30 s：起点 0，第 4 次检查（120 s）越过 110 s 的截止
        fx.evalOverride.set((script, keys) -> script == TeamScript.READ ? broken("redis down") : null);
        AtomicInteger asked = new AtomicInteger();
        fx.nanoTime = () -> TimeUnit.SECONDS.toNanos(30L * asked.getAndIncrement());

        svc.finishMatch(tid, "tok-A", false, roster, null);

        fx.evalOverride.set((script, keys) -> null);
        assertThat(fx.sleeps).as("0 / 30 / 60 / 90 s 各跑一轮失败并退避，120 s 时放弃").hasSize(3);
        assertThat(fx.state(tid, t[1], t[2])).as("没清掉：锁留给自然过期").isEqualTo(before);
        Map<Long, TeamSnapshotS2C> current = byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
        assertThat(current.keySet()).containsExactlyInAnyOrder(t[1], t[2]);
        for (TeamSnapshotS2C s : current.values()) {
            assertThat(s.getTeam().getMatchState()).as("锁此刻仍有效").isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
            assertThat(s.getTeam().getVersion()).isEqualTo(lock.commit().version());
        }
    }
}
