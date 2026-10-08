package com.game.team.match;

import static com.game.team.match.FakeMatchTeamService.broken;
import static com.game.team.match.FakeMatchTeamService.done;
import static com.game.team.match.FakeMatchTeamService.gathered;
import static com.game.team.match.FakeMatchTeamService.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchRpcAttachments;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.common.deadline.Deadline;
import com.game.proto.Empty;
import com.game.team.match.FakeMatchTeamService.Call;
import com.game.team.match.TeamBattlePort.Check;
import com.game.team.match.TeamBattlePort.Gather;
import com.game.team.match.TeamBattlePort.Tickets;
import com.game.team.rules.TeamTips;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.Test;

/**
 * {@link MatchTeamBattle}：xm-match 的应答怎么翻译成 team 自己的词汇，以及调用纪律（match-spec §7.5、§7.6，plan §7 问题 10）——
 * 每跳超时 = min(3 s, 剩余预算)、同一个值经 {@code xm-budget-ms} 下传（不是整请求的剩余预算）、附件调完即清、预算用完就不发；{@code runTeamGather} 用开战锁时长作
 * 调用级超时、不带预算；传输失败与应答枚举的 UNSPECIFIED / 不认识的值一律不读成「通过 / 建成」。不起 Dubbo：替身记下调用线程上的附件。
 */
class MatchTeamBattleTest {

    private static final long A = Long.MIN_VALUE + 101, B = Long.MIN_VALUE + 102, C = 103;
    private static final List<Long> ROSTER = List.of(A, B, C);

    private final FakeMatchTeamService match = new FakeMatchTeamService().open(1, 5);
    private final MatchTeamBattle battle = new MatchTeamBattle(match);

    private static Deadline budget() {
        return Deadline.after(3500);
    }

    private static Map<Long, String> ticketIds() {
        Map<Long, String> ids = new LinkedHashMap<>();
        ids.put(A, "ticket-a");
        ids.put(B, "ticket-b");
        ids.put(C, "ticket-c");
        return ids;
    }

    // ================================================================ checkTeamMatch：结论的翻译

    @Test
    void 预检通过_带回每人的zone与锁时长_请求里是副本号与名单原序() {
        match.zone = 7;
        match.lockTtlSeconds = 83;

        Check check = battle.checkTeamMatch(1, ROSTER, budget());

        assertThat(check.ok()).isTrue();
        assertThat(check.code()).isEqualTo(TeamTips.OK);
        assertThat(check.param()).isZero();
        assertThat(check.zones()).isEqualTo(Map.of(A, 7, B, 7, C, 7));
        assertThat(check.lockTtlSeconds()).isEqualTo(83);
        TeamMatchCheckRequest sent = match.checks.get(0).request();
        assertThat(sent.getBattleConfigId()).isEqualTo(1);
        assertThat(sent.getRosterList()).containsExactly(A, B, C);
    }

    @Test
    void 预检的七种结论逐个映射成team段的tip_出问题的成员进param() {
        record Row(TeamMatchCheckResult result, long offender, int code, long param) {
        }
        List<Row> rows = List.of(
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN, 0, TeamTips.DUNGEON_NOT_OPEN, 0),
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED, 0, TeamTips.SIZE_EXCEEDED, 0),
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE, B, TeamTips.MEMBER_OFFLINE, B),
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE, A, TeamTips.MEMBER_IN_BATTLE, A),
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY, C, TeamTips.MEMBER_NOT_READY, C),
                // 内部故障不带成员：即使对端填了 offender 也不透给客户端
                new Row(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, B, TeamTips.INTERNAL, 0));
        assertThat(List.of(TeamTips.DUNGEON_NOT_OPEN, TeamTips.SIZE_EXCEEDED, TeamTips.MEMBER_OFFLINE, TeamTips.MEMBER_IN_BATTLE,
                TeamTips.MEMBER_NOT_READY, TeamTips.INTERNAL)).as("码值钉住：4027 / 4028 / 4024 / 4025 / 4026 / 4030")
                .containsExactly(4027, 4028, 4024, 4025, 4026, 4030);
        for (Row row : rows) {
            match.checkReply = r -> done(rejected(row.result(), row.offender()));

            Check check = battle.checkTeamMatch(1, ROSTER, budget());

            assertThat(check.ok()).as("%s", row.result()).isFalse();
            assertThat(check.code()).as("%s", row.result()).isEqualTo(row.code());
            assertThat(check.param()).as("%s", row.result()).isEqualTo(row.param());
            assertThat(check.zones()).isEmpty();
            assertThat(check.lockTtlSeconds()).isZero();
        }
    }

    @Test
    void 调不通_空应答_result缺失或不认识_一律4030_绝不读成通过() {
        Map<String, CompletableFuture<TeamMatchCheckReply>> replies = new LinkedHashMap<>();
        replies.put("future 异常完成", broken("no provider"));
        replies.put("空应答", done(null));
        replies.put("全默认值（UNSPECIFIED）", done(TeamMatchCheckReply.getDefaultInstance()));
        replies.put("没见过的枚举值", done(TeamMatchCheckReply.newBuilder().setResultValue(99).setLockTtlSeconds(83)
                .putZones(A, 1).putZones(B, 1).putZones(C, 1).build()));
        replies.forEach((name, reply) -> {
            match.checkReply = r -> reply;

            Check check = battle.checkTeamMatch(1, ROSTER, budget());

            assertThat(check.ok()).as(name).isFalse();
            assertThat(check.code()).as(name).isEqualTo(TeamTips.INTERNAL);
            assertThat(check.param()).as(name).isZero();
        });
        // 调用本身同步抛异常（引用还没建好）：同样折成 4030，不抛给服务层
        match.checkReply = r -> {
            throw new IllegalStateException("reference not ready");
        };
        assertThat(battle.checkTeamMatch(1, ROSTER, budget()).code()).isEqualTo(TeamTips.INTERNAL);
    }

    @Test
    void 通过的应答不可信时按4030_锁时长越界或zone没带齐() {
        match.lockTtlSeconds = 0;
        assertThat(battle.checkTeamMatch(1, ROSTER, budget()).code()).as("没有锁时长").isEqualTo(TeamTips.INTERNAL);
        match.lockTtlSeconds = MatchTeamBattle.MAX_LOCK_TTL_SECONDS + 1;
        assertThat(battle.checkTeamMatch(1, ROSTER, budget()).code()).as("锁比 EndMatch 的截止还长").isEqualTo(TeamTips.INTERNAL);
        match.lockTtlSeconds = MatchTeamBattle.MAX_LOCK_TTL_SECONDS;
        assertThat(battle.checkTeamMatch(1, ROSTER, budget()).ok()).as("恰好等于截止可以").isTrue();
        match.lockTtlSeconds = 1;
        assertThat(battle.checkTeamMatch(1, ROSTER, budget()).lockTtlSeconds()).isEqualTo(1);

        match.checkReply = r -> done(TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                .setLockTtlSeconds(83).putZones(A, 1).putZones(C, 1).build());
        Check missingZone = battle.checkTeamMatch(1, ROSTER, budget());
        assertThat(missingZone.ok()).as("B 的 zone 没带回来：不拿 0 顶替").isFalse();
        assertThat(missingZone.code()).isEqualTo(TeamTips.INTERNAL);
    }

    // ================================================================ 每跳超时与预算附件

    @Test
    void 前三个方法每跳超时取3秒与剩余预算的较小者_带给对端的预算就是这一跳的超时_调完即清() {
        // 预算充足（3500 ms）：超时封顶 3 s。带下去的预算也是 3 s 而不是整请求的剩余值（3.4 s 以上）——本端过了 3 s 就不收应答了，
        // 对端的截止再晚，迟到的建票就会在本端已经判「结果不明」并回滚之后照常写票
        battle.checkTeamMatch(1, ROSTER, Deadline.after(3500));
        Call<TeamMatchCheckRequest> wide = match.checks.get(0);
        assertThat(wide.timeoutMs()).isEqualTo(3000L).isEqualTo(MatchTeamBattle.HOP_TIMEOUT_MS);
        assertThat(wide.budget()).as("预算附件 = 每跳超时，不超过 3 s").isEqualTo(wide.timeoutMs());

        battle.createTeamTickets(1, 77, ROSTER, Map.of(A, 1, B, 1, C, 1), ticketIds(), Deadline.after(3500));
        Call<TeamTicketsRequest> wideTickets = match.tickets.get(0);
        assertThat(wideTickets.timeoutMs()).isEqualTo(MatchTeamBattle.HOP_TIMEOUT_MS);
        assertThat(wideTickets.budget()).as("建票是「迟到了就不该再写」的那一跳：对端的截止不得晚于本端放弃的时刻").isEqualTo(3000L);

        // 预算只剩 800 ms：超时 = 预算附件 = 剩余预算
        battle.createTeamTickets(1, 77, ROSTER, Map.of(A, 1, B, 1, C, 1), ticketIds(), Deadline.after(800));
        Call<TeamTicketsRequest> narrow = match.tickets.get(1);
        assertThat(narrow.budget()).isBetween(1L, 800L);
        assertThat(narrow.timeoutMs()).isEqualTo(narrow.budget());

        battle.releaseTeamTickets(ticketIds(), Deadline.after(1200));
        Call<TeamTicketsRelease> release = match.releases.get(0);
        assertThat(release.budget()).isBetween(801L, 1200L);
        assertThat(release.timeoutMs()).isEqualTo(release.budget());

        battle.releaseTeamTickets(ticketIds(), Deadline.after(60_000));
        Call<TeamTicketsRelease> wideRelease = match.releases.get(1);
        assertThat(wideRelease.timeoutMs()).isEqualTo(MatchTeamBattle.HOP_TIMEOUT_MS);
        assertThat(wideRelease.budget()).isEqualTo(3000L);

        assertThat(RpcContext.getClientAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS)).as("附件不漏到这条线程上之后的调用里").isNull();
        assertThat(RpcContext.getClientAttachment().getObjectAttachment(CommonConstants.TIMEOUT_KEY)).isNull();
    }

    @Test
    void 预算已用完就不发_预检4030_建票结果不明_退票false() {
        Deadline spent = Deadline.after(0);

        assertThat(battle.checkTeamMatch(1, ROSTER, spent).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(battle.createTeamTickets(1, 77, ROSTER, Map.of(), ticketIds(), spent)).isInstanceOf(Tickets.Unknown.class);
        assertThat(battle.releaseTeamTickets(ticketIds(), spent)).isFalse();

        assertThat(match.checks).as("没有预算的调用不发出去").isEmpty();
        assertThat(match.tickets).isEmpty();
        assertThat(match.releases).isEmpty();
    }

    @Test
    void 对端不应答_等到请求截止就放弃_不拖过预算() {
        match.checkReply = r -> new CompletableFuture<>();
        long started = System.nanoTime();

        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(150));

        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(check.code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(waited).as("等满预算、但不等到 3 s 的每跳上限").isBetween(140L, 2000L);
    }

    // ================================================================ createTeamTickets / releaseTeamTickets

    @Test
    void 建票_请求带队伍号名单zone与每人的票号_四种应答各归其位() {
        Map<Long, Integer> zones = Map.of(A, 3, B, 3, C, 4);

        assertThat(battle.createTeamTickets(9, 77, ROSTER, zones, ticketIds(), budget())).isEqualTo(new Tickets.Created());
        TeamTicketsRequest sent = match.tickets.get(0).request();
        assertThat(sent.getBattleConfigId()).isEqualTo(9);
        assertThat(sent.getTeamId()).isEqualTo(77);
        assertThat(sent.getRosterList()).containsExactly(A, B, C);
        assertThat(sent.getZonesMap()).isEqualTo(zones);
        assertThat(sent.getTicketIdsMap()).isEqualTo(ticketIds());

        match.failTickets(B);
        assertThat(battle.createTeamTickets(9, 77, ROSTER, zones, ticketIds(), budget())).isEqualTo(new Tickets.Failed(B));

        Map<String, CompletableFuture<TeamTicketsReply>> unknown = new LinkedHashMap<>();
        unknown.put("future 异常完成", broken("reset"));
        unknown.put("空应答", done(null));
        unknown.put("全默认值（UNSPECIFIED）", done(TeamTicketsReply.getDefaultInstance()));
        unknown.put("EXPIRED", done(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_EXPIRED).build()));
        unknown.put("没见过的枚举值", done(TeamTicketsReply.newBuilder().setStatusValue(42).build()));
        unknown.put("FAILED 却没有失败者", done(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_FAILED).build()));
        unknown.forEach((name, reply) -> {
            match.ticketsReply = r -> reply;
            assertThat(battle.createTeamTickets(9, 77, ROSTER, zones, ticketIds(), budget()))
                    .as("%s：结果不明（调用方要退票），不是建成也不是哪名成员失败", name).isInstanceOf(Tickets.Unknown.class);
        });
    }

    @Test
    void 退票_调通回true_对端报错回false不抛() {
        assertThat(battle.releaseTeamTickets(ticketIds(), budget())).isTrue();
        assertThat(match.releases.get(0).request().getTicketIdsMap()).isEqualTo(ticketIds());

        match.releaseReply = r -> broken("deleteGroup failed");
        assertThat(battle.releaseTeamTickets(ticketIds(), budget())).as("没删成只记日志，票据按 matched TTL 过期").isFalse();
        match.releaseReply = r -> done(Empty.getDefaultInstance());
        assertThat(battle.releaseTeamTickets(ticketIds(), budget())).isTrue();
    }

    // ================================================================ runTeamGather

    @Test
    void gather_调用级超时是开战锁时长_不带预算附件_应答原样带回() throws Exception {
        match.gatherReply = r -> done(gathered(true, 555));

        Gather ok = battle.runTeamGather(9, 77, ROSTER, ticketIds(), 101).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(ok).isEqualTo(new Gather(true, "success", 555));
        Call<TeamGatherRequest> call = match.gathers.get(0);
        assertThat(call.timeoutMs()).as("5 人 101 s：引用上的 3 s 兜底超时不适用于它").isEqualTo(101_000L);
        assertThat(call.budgetMs()).as("gather 不受请求预算约束").isNull();
        assertThat(call.request().getBattleConfigId()).isEqualTo(9);
        assertThat(call.request().getTeamId()).isEqualTo(77);
        assertThat(call.request().getRosterList()).containsExactly(A, B, C);
        assertThat(call.request().getTicketIdsMap()).isEqualTo(ticketIds());
        assertThat(RpcContext.getClientAttachment().getObjectAttachment(CommonConstants.TIMEOUT_KEY)).as("调完即清").isNull();

        match.gatherReply = r -> done(gathered(false, 0));
        Gather failed = battle.runTeamGather(9, 77, ROSTER, ticketIds(), 101).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.battleId()).isZero();
        assertThat(failed.outcome()).isEqualTo("prepare_failed");
    }

    @Test
    void gather_不阻塞调用线程_对端完成时stage才完成() throws Exception {
        CompletableFuture<com.game.api.proto.TeamGatherReply> pending = new CompletableFuture<>();
        match.gatherReply = r -> pending;

        CompletionStage<Gather> stage = battle.runTeamGather(9, 77, ROSTER, ticketIds(), 83);

        assertThat(stage.toCompletableFuture()).as("立即返回：gather 还在 xm-match 那边跑").isNotDone();
        pending.complete(gathered(true, 556));
        assertThat(stage.toCompletableFuture().get(5, TimeUnit.SECONDS).battleId()).isEqualTo(556);
    }

    @Test
    void gather_传输失败或空应答_stage异常完成_锁时长非法时根本不发() {
        match.gatherReply = r -> broken("connection closed");
        assertThat(battle.runTeamGather(9, 77, ROSTER, ticketIds(), 83).toCompletableFuture()).isCompletedExceptionally();

        match.gatherReply = r -> done(null);
        assertThat(battle.runTeamGather(9, 77, ROSTER, ticketIds(), 83).toCompletableFuture()).isCompletedExceptionally();

        match.gatherReply = r -> {
            throw new IllegalStateException("reference not ready");
        };
        assertThat(battle.runTeamGather(9, 77, ROSTER, ticketIds(), 83).toCompletableFuture()).isCompletedExceptionally();
        assertThat(match.gathers).hasSize(3);

        assertThat(battle.runTeamGather(9, 77, ROSTER, ticketIds(), 0).toCompletableFuture()).as("没有锁时长就没有调用级超时：不发")
                .isCompletedExceptionally();
        assertThat(match.gathers).hasSize(3);
    }
}
