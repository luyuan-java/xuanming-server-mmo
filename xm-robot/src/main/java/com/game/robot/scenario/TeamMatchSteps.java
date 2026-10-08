package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.robot.client.BattleIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.MatchSupport.Caller;
import com.game.robot.scenario.MatchSupport.Finished;
import com.game.robot.scenario.MatchSupport.JoinAttempts;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.game.table.TeamErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * team 场景的开战段（批次 6.4，match-spec §15.5「场景 team 升级」；基线 {@code robot/team_smoke_scenario.go} 的 S7 / S8）。从
 * {@link TeamScenario} 拆出来：它只需要「队长 A、队员 B、队伍号」三样，不依赖前面建队 / 邀请那些步骤的内部状态，可以对着假服务端单独跑。三段：
 * <ol>
 *   <li><b>开战拒绝码</b>：非队长 B 发 211 → 4018；A 发 211(2)（没配组队人数的副本）→ 4027，带视图且 IDLE；B 持有 1V1 排队票时 A 发 211(1) →
 *       4026，{@code parameters[0] = B}，带视图且 IDLE；随后 B 发 148 取消，153 确认 NOT_QUEUED（免得 S7 的预检被这张票挡住）；</li>
 *   <li><b>S7 整队开战</b>：A 发 211(1) → 回包视图 STARTING；B 收 213 MATCH_STARTED（发起人 A 不收，他以回包为准）；两人在大厅收到同一个
 *       battle_id 的 177 → 143（PVE 组队：都在 0 队）→ 都直连、开自动 → 都收到 150；两人都收到 213 MATCH_ENDED（视图回到 IDLE；它在开局成功、
 *       开战锁释放后即推，通常早于战斗结束，所以从发起之前的 mark 起找）；</li>
 *   <li><b>S8 战斗中拒绝</b>：B 单人 PVE 开战、已直连但不开自动（持战斗锁）时 A 发 211 → 4025，{@code parameters[0] = B}，带视图且 IDLE；
 *       随后 B 开自动把这一局打完。</li>
 * </ol>
 * 过渡态：上一场结算落地之前队员仍持战斗锁（211 回 4025[那个人]、157 回 16000）、上一场的 ready 票还没清（4026）——按节奏重试到上限；
 * 被拒时服务端不加锁，重试没有副作用。三段各自独立：某一段中断时记一条失败，后面的段照常跑。
 *
 * <p>只在场景线程上使用。用完 {@link #close()}（关掉本类建的战斗直连）。
 */
final class TeamMatchSteps implements AutoCloseable {

    static final String SERVICE = "ClientPlayerTeam";
    static final String REF = "match-spec §15.5「team 升级」、§7.3；team-spec §5";
    /** 整队开战用的副本：Dungeon 1（xm-match 缺省的组队人数表只有 {@code {1: 5}}，同基线的 battle_config_id）。 */
    static final int BATTLE_CONFIG_ID = 1;
    /** 没配组队人数的副本：211 回 4027。 */
    static final int BATTLE_CONFIG_NOT_OPEN = 2;
    /** B 排 1V1 用的队列（不带副本）。 */
    static final int PVP_CONFIG = 0;

    static final int TIP_NOT_LEADER = TeamErrorTip.team_error.kTeamNotLeader_VALUE;
    static final int TIP_MEMBER_IN_BATTLE = TeamErrorTip.team_error.kTeamMemberInBattle_VALUE;
    static final int TIP_MEMBER_NOT_READY = TeamErrorTip.team_error.kTeamMemberNotReady_VALUE;
    static final int TIP_DUNGEON_NOT_OPEN = TeamErrorTip.team_error.kTeamDungeonNotOpen_VALUE;

    /** 「期望 4025[某人]」时一次 211 应答的判读。 */
    enum StartVerdict {
        /** 4025 且 parameters[0] 是期望的那个人。 */
        MATCHED,
        /** 4025 / 4026 但肇事者是别人：上一场结算尚未落地的过渡态，重试。 */
        RETRY,
        /** 被受理（0）或别的码：不是过渡态，立即失败。 */
        FAILED
    }

    private final RobotClient client;
    private final CheckReport report;
    private final StepTrack steps;
    private final BattleIds battleIds;
    private final MatchSupport.Ids matchIds;
    private final int startMatch;
    private final int notifySnapshot;
    private final Duration requestTimeout;
    private final Tempo tempo;
    private final List<Direct> directs = new ArrayList<>();
    private long teamBattleId;

    /**
     * @param report 检查记到调用方的报告里
     * @param steps  步骤记到调用方的结果行里（{@code match-rejects} / {@code s7-team-battle} / {@code s8-member-in-battle}）
     */
    TeamMatchSteps(RobotClient client, MessageIdRegistry registry, CheckReport report, StepTrack steps, Duration requestTimeout, Tempo tempo) {
        this.client = client;
        this.report = report;
        this.steps = steps;
        this.battleIds = BattleIds.resolve(registry);
        this.matchIds = MatchSupport.Ids.resolve(registry);
        this.startMatch = registry.requireId(SERVICE, "StartTeamMatch");
        this.notifySnapshot = registry.requireId(SERVICE, "NotifyTeamSnapshot");
        this.requestTimeout = requestTimeout;
        this.tempo = tempo;
    }

    /** S7 那一局的 battle_id；没打成为 0。 */
    long teamBattleId() {
        return teamBattleId;
    }

    /**
     * 跑三段。不抛出：每一段的中断都记成一条失败。
     *
     * @param a   队长
     * @param b   队员（队伍此刻恰好是 A、B 两人，都在线、都在场景里、都不在战斗中）
     * @param tid 队伍号
     */
    void run(Caller a, Caller b, long tid) {
        phase("开战拒绝码", () -> matchRejects(a, b, tid));
        phase("S7 整队开战", () -> teamBattle(a, b, tid));
        phase("S8 战斗中拒绝", () -> memberInBattle(a, b, tid));
    }

    @Override
    public void close() {
        directs.forEach(Direct::close);
    }

    // ---------------------------------------------------------------- 三段

    private void matchRejects(Caller a, Caller b, long tid) throws RobotException {
        steps.step("match-rejects", report);
        TeamResponse notLeader = start(b, BATTLE_CONFIG_ID, tid);
        report.check(tipOf(notLeader) == TIP_NOT_LEADER && notLeader.hasTeam() && notLeader.getTeam().getTeamId() == tid,
                "非队长 B 开战 → 4018（带当前视图）", TeamScenario.describe(notLeader), REF);
        TeamResponse notOpen = start(a, BATTLE_CONFIG_NOT_OPEN, tid);
        report.check(tipOf(notOpen) == TIP_DUNGEON_NOT_OPEN && idleViewOf(notOpen, tid),
                "A 发 211(" + BATTLE_CONFIG_NOT_OPEN + ")（没配组队人数的副本）→ 4027，带视图且 match_state = IDLE", TeamScenario.describe(notOpen), REF);

        JoinQueueResponse queued = MatchSupport.join(b, matchIds, MatchMode.MATCH_MODE_1V1, PVP_CONFIG);
        String ticketProblem = BattleSmokeChecks.joinAcceptedProblem(queued);
        must(ticketProblem == null, "B 排 1V1 受理（持有一张排队票）", ticketProblem == null ? BattleSmokeChecks.describe(queued) : ticketProblem);
        RobotException failure = null;
        try {
            TeamResponse notReady = start(a, BATTLE_CONFIG_ID, tid);
            report.check(tipOf(notReady) == TIP_MEMBER_NOT_READY && firstParameterIs(notReady.getErrorMessage(), b.id()) && idleViewOf(notReady, tid),
                    "B 持有 1V1 排队票时 A 发 211 → 4026，parameters[0] = B，带视图且仍是 IDLE", TeamScenario.describe(notReady), REF);
        } catch (RobotException e) {
            failure = e;
        }
        // 上一步无论结果如何都把票取消掉（148 成功不回包），再用 153 确认：免得 S7 的预检被这张票挡住
        MatchSupport.cancel(b, matchIds, queued.getQueueTicket());
        GetQueueStatusResponse status = MatchSupport.status(b, matchIds);
        report.check(status.getState() == QueueState.QUEUE_STATE_NOT_QUEUED, "B 发 148 取消排队后 153 → NOT_QUEUED",
                BattleSmokeChecks.describe(status), REF);
        if (failure != null) {
            throw failure;
        }
    }

    private void teamBattle(Caller a, Caller b, long tid) throws RobotException {
        steps.step("s7-team-battle", report);
        int aMark = a.mark();
        int bMark = b.mark();
        TeamResponse started = startRetrying(a, tid);
        must(tipOf(started) == 0 && started.getTeam().getTeamId() == tid
                        && started.getTeam().getMatchState() == TeamMatchState.TEAM_MATCH_STATE_STARTING,
                "S7 A 发 211(" + BATTLE_CONFIG_ID + ") → 受理，回包视图 match_state = STARTING（4025 / 4026 的过渡态按节奏重试）",
                TeamScenario.describe(started));
        Optional<TeamSnapshotS2C> matchStarted = awaitSnapshot(b, bMark, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED, tid);
        report.check(matchStarted.isPresent() && matchStarted.get().getTeam().getMatchState() == TeamMatchState.TEAM_MATCH_STATE_STARTING,
                "S7 B 收到 213 MATCH_STARTED（视图 STARTING）", matchStarted.map(s -> TeamScenario.describe(s.getTeam()))
                        .orElse(MatchSupport.PUSH_TIMEOUT.toSeconds() + " s 内没收到" + b.connection().describeSince(bMark)), REF);

        Started startA;
        Started startB;
        try {
            startA = MatchSupport.awaitBattle(a.name(), a.connection(), aMark, 0, battleIds, tempo.battleStartTimeout());
            startB = MatchSupport.awaitBattle(b.name(), b.connection(), bMark, 0, battleIds, tempo.battleStartTimeout());
        } catch (RobotException e) {
            throw new RobotException(e.getMessage() + matchFailedHint(a, aMark, tid), e);
        }
        must(startA.battleId() == startB.battleId(), "S7 两人收到同一个 battle_id 的 177 / 143",
                "A " + MatchSupport.uid(startA.battleId()) + "，B " + MatchSupport.uid(startB.battleId()));
        teamBattleId = startA.battleId();
        String sides = BattleSmokeChecks.sidesProblem(startA.start().getState(), List.of(a.id(), b.id()), List.of(0, 0));
        report.check(startA.assignedFirst() && startB.assignedFirst() && sides == null, "S7 大厅上先 177 后 143，两人都在 0 队（PVE 组队）",
                "A 177 #" + startA.assignedAt().index() + " 143 #" + startA.startAt().index() + "；B 177 #" + startB.assignedAt().index()
                        + " 143 #" + startB.startAt().index() + (sides == null ? "" : "；" + sides), REF);

        Direct directA = connect(a.name(), startA.assigned());
        Direct directB = connect(b.name(), startB.assigned());
        MatchSupport.enableAuto(directA, teamBattleId, battleIds);
        MatchSupport.enableAuto(directB, teamBattleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, teamBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        Finished endB = MatchSupport.awaitEnd(directB, teamBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        directA.close();
        directB.close();
        report.check(endA.end().getOutcome() == endB.end().getOutcome() && endA.turns() >= 1 && endB.turns() >= 1,
                "S7 都开自动 → 两人都收到 150（同一个终局），直连上各至少一条 139",
                "A outcome=" + endA.end().getOutcome() + " 139 × " + endA.turns() + "；B outcome=" + endB.end().getOutcome() + " 139 × "
                        + endB.turns(), REF);

        // MATCH_ENDED：开局成功、开战锁释放后即推，通常早于战斗结束——从发起之前的 mark 起找
        for (Caller bot : List.of(a, b)) {
            int mark = bot == a ? aMark : bMark;
            Optional<TeamSnapshotS2C> ended = awaitSnapshot(bot, mark, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED, tid);
            report.check(ended.isPresent() && ended.get().getTeam().getMatchState() == TeamMatchState.TEAM_MATCH_STATE_IDLE,
                    "S7 " + bot.name() + " 收到 213 MATCH_ENDED（视图回到 IDLE）", ended.map(s -> TeamScenario.describe(s.getTeam()))
                            .orElse(MatchSupport.PUSH_TIMEOUT.toSeconds() + " s 内没收到" + matchFailedHint(bot, mark, tid)
                                    + bot.connection().describeSince(mark)), REF);
        }
        boolean leaderPushed = a.connection().inbox().snapshot(aMark).stream()
                .anyMatch(r -> snapshotOf(r, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED, tid) != null);
        report.check(!leaderPushed, "S7 发起人 A 没有收到 MATCH_STARTED（他以 211 的回包为准）", leaderPushed ? "A 也收到了" : "", REF);
    }

    private void memberInBattle(Caller a, Caller b, long tid) throws RobotException {
        steps.step("s8-member-in-battle", report);
        JoinAttempts solo = MatchSupport.joinRetrying(b, matchIds, MatchMode.MATCH_MODE_PVE_SOLO, BATTLE_CONFIG_ID,
                Set.of(BattleSmokeChecks.TIP_IN_BATTLE), tempo);
        must(solo.accepted(), "S8 B 单人 PVE 排队受理（上一场结算落地前的 16000 按过渡态重试）", solo.describe());
        Started bSolo = MatchSupport.awaitBattle(b.name(), b.connection(), solo.mark(), 0, battleIds, tempo.battleStartTimeout());
        // 开战即直连：下面的拒绝断言在过渡态下会重试十几秒，远超一回合 6 s；晚连的话超时结算的回合全被丢弃，单人 PVE 甚至可能先打完。
        // 不开自动：B 一直持有战斗锁（scene 备战时就落了锁，先于建房）
        Direct bDirect = connect(b.name(), bSolo.assigned());
        TeamResponse rejected = startUntilMemberInBattle(a, tid, b.id());
        report.check(memberInBattleVerdict(tipOf(rejected), rejected.getErrorMessage().getParametersList(), b.id()) == StartVerdict.MATCHED
                        && idleViewOf(rejected, tid),
                "S8 B 在战斗中（已直连、未开自动）A 发 211 → 4025，parameters[0] = B，带视图且仍是 IDLE", TeamScenario.describe(rejected), REF);
        MatchSupport.enableAuto(bDirect, bSolo.battleId(), battleIds);
        Finished end = MatchSupport.awaitEnd(bDirect, bSolo.battleId(), battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        bDirect.close();
        report.check(end.turns() >= 1, "S8 B 开自动把这一局打到 150", "outcome=" + end.end().getOutcome() + " 139 × " + end.turns(), REF);
    }

    // ---------------------------------------------------------------- 211

    private TeamResponse start(Caller bot, int battleConfigId, long tid) throws RobotException {
        return bot.call(startMatch, startRequest(battleConfigId, tid), TeamResponse.parser());
    }

    /**
     * 队长发 211 直到受理。4025 / 4026 是有界的过渡态：按节奏重试到上限；其它拒绝码立即返回（Go teamSmokeStartTeamMatch）。
     * 返回最后一次的应答，由调用方判定。
     */
    private TeamResponse startRetrying(Caller leader, long tid) throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        while (true) {
            TeamResponse response = start(leader, BATTLE_CONFIG_ID, tid);
            if (!transientStartTip(tipOf(response)) || System.nanoTime() - deadline >= 0) {
                return response;
            }
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    /**
     * 队长发 211，直到被拒且码为 4025、{@code parameters[0]} 是 {@code inBattle}；先撞到别的队员（上一场结算尚未落地）时按节奏重试到上限
     * （Go teamSmokeExpectMemberInBattle）。返回最后一次的应答，由调用方判定。
     */
    private TeamResponse startUntilMemberInBattle(Caller leader, long tid, long inBattle) throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        while (true) {
            TeamResponse response = start(leader, BATTLE_CONFIG_ID, tid);
            StartVerdict verdict = memberInBattleVerdict(tipOf(response), response.getErrorMessage().getParametersList(), inBattle);
            if (verdict != StartVerdict.RETRY || System.nanoTime() - deadline >= 0) {
                return response;
            }
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    static StartVerdict memberInBattleVerdict(int tip, List<String> parameters, long inBattle) {
        if (tip == TIP_MEMBER_IN_BATTLE && !parameters.isEmpty() && parameters.get(0).equals(Long.toUnsignedString(inBattle))) {
            return StartVerdict.MATCHED;
        }
        return transientStartTip(tip) ? StartVerdict.RETRY : StartVerdict.FAILED;
    }

    /** 211 的过渡态拒绝码：队员仍持战斗锁（4025）、上一场的票未清（4026）。 */
    static boolean transientStartTip(int tip) {
        return tip == TIP_MEMBER_IN_BATTLE || tip == TIP_MEMBER_NOT_READY;
    }

    /** tip 的 {@code parameters[0]} 是这个玩家号的无符号十进制（4024 / 4025 / 4026 与 MATCH_FAILED 带肇事者）。 */
    static boolean firstParameterIs(TipInfoMessage tip, long playerId) {
        return tip.getParametersCount() > 0 && tip.getParameters(0).equals(Long.toUnsignedString(playerId));
    }

    static StartTeamMatchRequest startRequest(int battleConfigId, long tid) {
        return StartTeamMatchRequest.newBuilder().setBattleConfigId(battleConfigId).setExpectedTeamId(tid).build();
    }

    /** 应答带调用者当前视图、是这支队伍、且没有开战锁（IDLE）。 */
    private static boolean idleViewOf(TeamResponse response, long tid) {
        return response.hasTeam() && response.getTeam().getTeamId() == tid
                && response.getTeam().getMatchState() == TeamMatchState.TEAM_MATCH_STATE_IDLE;
    }

    private static int tipOf(TeamResponse response) {
        return response.getErrorMessage().getId();
    }

    // ---------------------------------------------------------------- 213

    private Optional<TeamSnapshotS2C> awaitSnapshot(Caller bot, int mark, TeamChangeReason reason, long tid) throws RobotException {
        return MatchSupport.awaitPush(bot.connection(), mark, notifySnapshot, TeamSnapshotS2C.parser(),
                s -> s.getReason() == reason && s.getTeam().getTeamId() == tid, MatchSupport.PUSH_TIMEOUT);
    }

    /** 这支队伍、指定原因的 213 推送；不是则为 null。 */
    private TeamSnapshotS2C snapshotOf(Received r, TeamChangeReason reason, long tid) {
        if (r.messageId() != notifySnapshot || r.requestId() != 0) {
            return null;
        }
        TeamSnapshotS2C snapshot = r.parseOrNull(TeamSnapshotS2C.parser());
        return snapshot != null && snapshot.getReason() == reason && snapshot.getTeam().getTeamId() == tid ? snapshot : null;
    }

    /** 排查提示：从 {@code mark} 起收到过 MATCH_FAILED（gather 失败不带 tip，建票失败带 4026[pid]）就把它写出来；没有为空串。 */
    private String matchFailedHint(Caller bot, int mark, long tid) {
        for (Received r : bot.connection().inbox().snapshot(mark)) {
            TeamSnapshotS2C failed = snapshotOf(r, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED, tid);
            if (failed != null) {
                return "；" + bot.name() + " 收到了 213 MATCH_FAILED" + (failed.hasTip() ? " tip=" + failed.getTip().getId() + " parameters="
                        + failed.getTip().getParametersList() : "（不带 tip：gather 失败，看 xm-match 的 xm_match_gathers_total{outcome}）");
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 工具

    private Direct connect(String name, BattleAssignedS2C ticket) throws RobotException {
        Direct direct = MatchSupport.connect(client, name, ticket, battleIds, requestTimeout);
        directs.add(direct);
        return direct;
    }

    @FunctionalInterface
    private interface Phase {
        void run() throws RobotException;
    }

    /** 独立的一段：中断时记一条失败并继续后面的段。 */
    private void phase(String name, Phase body) {
        try {
            body.run();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断：" + name, e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        }
    }

    /** 本段后续步骤依赖的检查：不通过即中止本段（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，本段后面的步骤依赖它");
        }
    }
}
