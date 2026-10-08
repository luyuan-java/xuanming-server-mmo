package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesRequest;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleIds;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.MatchSupport.Bot;
import com.game.robot.scenario.MatchSupport.Finished;
import com.game.robot.scenario.MatchSupport.JoinAttempts;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 匹配端到端（批次 6.4，match-spec §15.5「场景 battle-smoke」；基线 {@code robot/battle_smoke_scenario.go} 的 A 侧，加上排队语义、补签、
 * 1V1 评分、切磋）。三个新号 A / B / C 登录进场，全程经 gate → xm-match，评分与指标经 xm-match 管理端口（{@link MatchAdminClient}）：
 * <ol>
 *   <li>第 1 步 登录；A 的 153 → NOT_QUEUED，两个秒数都是 0；</li>
 *   <li>第 2 步 拒绝码：157 {mode = 2} → 16002，{mode = 5, config = 2} → 16003（{@code error_code == error_message.id}、
 *       {@code parameters[0]} 逐字节、不带票号）；</li>
 *   <li>第 3 步 排队与取消：1V1 入队拿到票 T → 再排 16001 带 T → 153 QUEUED → 148("stale") 1 s 内无回包且仍 QUEUED → 148(T) 无回包、
 *       153 → NOT_QUEUED；</li>
 *   <li>第 4 步 PVE_SOLO：受理 → 大厅先 177 后 143、同一 battle_id、票据形状、{@code expire_at_ms} ≈ 发起时刻 + 300 s → 153 是 MATCHED 或 READY；</li>
 *   <li>第 5 步 补签 179：A 的票与 177 逐字节相同；非成员 C → 1005 无票；不存在的局 → 1005「该战斗不存在或已结束」；</li>
 *   <li>第 6 步 凭补签的票直连 → 战斗中再排 → 16000 → 挂机打到 150（SIDE_A_WIN、回合数 ≥ 1）→ FIN；</li>
 *   <li>第 7 步 立即再排：结算落地之前的 16000 按过渡态重试，<b>不得</b>出现 16001（ready 残留必须已自愈）→ 第二局同样打完；
 *       旧局的 179 → 1005；</li>
 *   <li>第 8 步 1V1 与评分：A 受理之后 B 再排（A 是锚点）→ 同一 battle_id、A 在 0 队 B 在 1 队 → 都挂机打到 150 → 10 s 内评分落账：
 *       games 各 + 1；胜负且不满 30 回合 |Δ| = 16，平局或打满 Δ = 0；</li>
 *   <li>第 9 步 切磋：16007 → 156 邀请逐字段 → 16011 → 16013（不消费）→ 拒绝只推发起者 → 16012 → 再发起、接受 → 双方 154 true 与
 *       同一局的 177 / 143 → 开自动之前 C 挑 A → 16009 → 打完 → C 下线后 A 挑 C：越过过渡态 16010 直到 16008；</li>
 *   <li>第 10 步 163 → in-band 1006，164 → 空列表（6.4 的临时应答，观战归 6.5）；</li>
 *   <li>第 11 步 xm-match 指标：开局成功、补签成功、切磋、评分入账各有增长；</li>
 *   <li>第 12 步 结果行 {@code BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=…}，
 *       失败是 {@code BATTLE_SMOKE_FAIL step=… reason=…}（{@link #resultLine}）。</li>
 * </ol>
 * 前置：切片带 xm-match、xm-battle 与 6.3 的 scene，运行模式 dev，Kafka 就绪，有运维令牌。
 *
 * <p>节奏：同一会话相邻请求隔 {@link MatchSupport#REQUEST_SPACING}（gate 对 match 的号每秒 3 条）；过渡态每 1 s 重试、上限 20 s；
 * 等开战 30 s、等终局 120 s。各阶段独立：前一阶段中断时记失败并继续后面的阶段（它们各自从排队开始）。
 * 收尾时给 A / B 各发一条 148("")（取消当前票，尽力而为），免得中断的那一步把 6 小时的排队票留在队列里。
 */
public final class BattleSmokeScenario {

    static final String REF = "match-spec §15.5";
    static final String MARKER = "BATTLE_SMOKE";
    /** PVE 用 Dungeon 1（同基线 battle-smoke）。 */
    static final int PVE_CONFIG = 1;
    /** 1V1 与切磋不带副本。 */
    static final int PVP_CONFIG = 0;
    /** 没配组队人数的副本（缺省配置只有 {@code {1: 5}}）：PVE_TEAM 排它回 16003。 */
    static final int TEAM_CONFIG_NOT_OPEN = 2;
    /** 本场景成功的开局数：PVE 两局 + 1V1 + 切磋。 */
    static final int EXPECTED_GATHERS = 4;

    private final RobotClient client;
    private final PlayerFlow flow;
    private final MatchSupport.Ids ids;
    private final BattleIds battleIds;
    private final MatchAdminClient admin;
    private final int leaveGame;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final Duration requestTimeout;
    private final Tempo tempo;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps = new StepTrack(MARKER);
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<Bot> queuers = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    /** 结果行的字段（没跑到的保持 0）。 */
    private long battleId;
    private int aTurns;
    private long pvpBattleId;
    private long challengeBattleId;

    public BattleSmokeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                               String runTag, Duration requestTimeout) {
        this(client, flow, registry, admin, accountPrefix, runTag, requestTimeout, Tempo.STANDARD);
    }

    /** @param tempo 节奏：对真服务端用 {@link Tempo#STANDARD}（上面的构造器）；单测对着本机假服务端时调快 */
    BattleSmokeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                        String runTag, Duration requestTimeout, Tempo tempo) {
        this.tempo = tempo;
        this.client = client;
        this.flow = flow;
        this.ids = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.requestTimeout = requestTimeout;
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "bm" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", message(e), REF);
        } finally {
            cleanup();
        }
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）：{@code BATTLE_SMOKE_OK …} 或 {@code BATTLE_SMOKE_FAIL step=… reason=…}。 */
    public String resultLine() {
        return steps.line(report, "battle_id=" + uid(battleId) + " a_turns=" + aTurns + " a_direct_turns=" + aTurns + " pvp_battle_id="
                + uid(pvpBattleId) + " challenge_battle_id=" + uid(challengeBattleId));
    }

    private void runChecks() throws RobotException {
        // ---- 第 1 步：登录 ----
        steps.step("1-login", report);
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        // 先抓一次指标：xm-match 管理端口不通（没起 / 地址指错）在登录之前就说清楚，也是第 11 步的基线
        String metricsBefore = admin.scrapeMetrics();
        Bot a = enter("A", accountA);
        Bot b = enter("B", accountB);
        Bot c = enter("C", accountC);
        queuers.add(a);
        queuers.add(b);
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " C=" + uid(c.id()) + " match-admin=" + admin.baseUrl());
        GetQueueStatusResponse fresh = MatchSupport.status(a, ids);
        report.check(fresh.getState() == QueueState.QUEUE_STATE_NOT_QUEUED && fresh.getEstimatedWaitSeconds() == 0 && fresh.getQueuedSeconds() == 0,
                "第 1 步 新号发 153 → NOT_QUEUED（state = 5），两个秒数都是 0", BattleSmokeChecks.describe(fresh), "match-spec §2.4");

        phase("拒绝码与排队取消（第 2–3 步）", () -> queueSemantics(a));
        phase("PVE_SOLO、补签、再排（第 4–7 步）", () -> soloBattles(a, c));
        phase("1V1 与评分（第 8 步）", () -> rankedDuel(a, b));
        phase("切磋（第 9 步）", () -> challenge(a, b, c));
        phase("观战的临时应答（第 10 步）", () -> spectatePlaceholders(a));
        phase("指标（第 11 步）", () -> metrics(metricsBefore));
    }

    // ---------------------------------------------------------------- 第 2–3 步

    private void queueSemantics(Bot a) throws RobotException {
        steps.step("2-reject-codes", report);
        JoinQueueResponse notOpen = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_3V3, PVP_CONFIG);
        checkNull(BattleSmokeChecks.joinRejectProblem(notOpen, BattleSmokeChecks.TIP_MODE_NOT_OPEN, BattleSmokeChecks.TEXT_MODE_NOT_OPEN, ""),
                "第 2 步 157 {mode = 2（3V3）} → 16002「该匹配模式未开放」：error_code == error_message.id、parameters[0] 逐字节、不带票号",
                BattleSmokeChecks.describe(notOpen), "match-spec §2.2 第 2b 行");
        JoinQueueResponse noTeamSize = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_PVE_TEAM, TEAM_CONFIG_NOT_OPEN);
        checkNull(BattleSmokeChecks.joinRejectProblem(noTeamSize, BattleSmokeChecks.TIP_TEAM_SIZE_NOT_CONFIGURED,
                        BattleSmokeChecks.TEXT_TEAM_SIZE_NOT_CONFIGURED, ""),
                "第 2 步 157 {mode = 5, config = " + TEAM_CONFIG_NOT_OPEN + "} → 16003「该副本未开放组队」", BattleSmokeChecks.describe(noTeamSize),
                "match-spec §2.2 第 2a 行");

        steps.step("3-queue-cancel", report);
        JoinQueueResponse queued = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_1V1, PVP_CONFIG);
        String ticket = queued.getQueueTicket();
        must(BattleSmokeChecks.joinAcceptedProblem(queued) == null, "第 3 步 157 {mode = 3, config = 0} 受理，票号是 36 位小写 UUID",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(queued), BattleSmokeChecks.describe(queued)));
        JoinQueueResponse again = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_1V1, PVP_CONFIG);
        checkNull(BattleSmokeChecks.joinRejectProblem(again, BattleSmokeChecks.TIP_ALREADY_QUEUED, BattleSmokeChecks.TEXT_ALREADY_QUEUED, ticket),
                "第 3 步 再排一次 → 16001「已在匹配队列中」，queue_ticket 是原来那张", BattleSmokeChecks.describe(again), "match-spec §2.2 第 6 行");
        expectState(a, "第 3 步 153 → QUEUED", QueueState.QUEUE_STATE_QUEUED);

        cancelSilently(a, "stale", "第 3 步 148(\"stale\")（票号不符）");
        expectState(a, "第 3 步 错票取消不动原票：153 仍是 QUEUED", QueueState.QUEUE_STATE_QUEUED);
        cancelSilently(a, ticket, "第 3 步 148(T)");
        expectState(a, "第 3 步 取消之后 153 → NOT_QUEUED", QueueState.QUEUE_STATE_NOT_QUEUED);
    }

    /** 发 148 并确认静默窗口（{@link MatchSupport#SILENCE}）内既没有它的回包（应答是 Empty：成功不回包）、也没有 23 tip。 */
    private void cancelSilently(Bot bot, String ticket, String name) throws RobotException {
        int mark = bot.mark();
        long requestId = MatchSupport.cancel(bot, ids, ticket);
        Optional<Received> reply = bot.connection().await(mark,
                r -> (r.messageId() == ids.cancelQueue() && r.requestId() == requestId) || BattleSupport.lobbyTip(r, battleIds.sendTip()) >= 0,
                tempo.silence());
        report.check(reply.isEmpty(), name + " → " + tempo.silence().toMillis() + " ms 内无回包（也没有 23 tip）",
                reply.map(r -> r.messageId() == ids.cancelQueue() ? "收到了 148 的回包（信封 tip=" + r.envelopeTipId() + "）"
                        : "收到了 23 tip=" + BattleSupport.lobbyTip(r, battleIds.sendTip())).orElse(""), "match-spec §2.3、§8.1");
    }

    private void expectState(Bot bot, String name, QueueState... allowed) throws RobotException {
        GetQueueStatusResponse status = MatchSupport.status(bot, ids);
        report.check(List.of(allowed).contains(status.getState()) && status.getEstimatedWaitSeconds() == 0, name + "（estimated_wait_seconds 恒为 0）",
                BattleSmokeChecks.describe(status), "match-spec §2.4");
    }

    // ---------------------------------------------------------------- 第 4–7 步

    private void soloBattles(Bot a, Bot c) throws RobotException {
        // ---- 第 4 步：PVE_SOLO ----
        steps.step("4-pve-solo", report);
        int lobbyMark = a.mark();
        long sentAt = System.currentTimeMillis();
        JoinQueueResponse solo = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG);
        must(BattleSmokeChecks.joinAcceptedProblem(solo) == null, "第 4 步 157 {mode = 4, config = " + PVE_CONFIG + "} 受理：error_code = 0、票号非空",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(solo), BattleSmokeChecks.describe(solo)));
        Started started = MatchSupport.awaitBattle(a.name, a.connection(), lobbyMark, 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        battleId = started.battleId();
        report.note("PVE battle_id=" + uid(battleId));
        report.check(started.assignedFirst(), "第 4 步 大厅上先 177 后 143，battle_id 一致",
                "177 #" + started.assignedAt().index() + " 143 #" + started.startAt().index(), "battle-node-spec §5.8 O1");
        checkTicket("第 4 步 177", started.assigned(), a.id());
        checkNull(BattleSmokeChecks.deadlineProblem(started.assigned().getExpireAtMs(), sentAt, MatchSupport.wallClockMillis(started.assignedAt()),
                        BattleSmokeChecks.BATTLE_DURATION_MS),
                "第 4 步 177 的 expire_at_ms ∈ [发起 + 300 s − 5 s, 收到 177 + 300 s + 5 s]",
                "expire_at_ms − 发起 = " + (started.assigned().getExpireAtMs() - sentAt) + " ms", "match-spec §8.4");
        report.check(BattleSmokeChecks.teamOf(started.start().getState(), a.id()).orElse(-1) == 0, "第 4 步 143 的 actors 里 A 在 0 队",
                "actors=" + started.start().getState().getActorsCount(), "match-spec §2.9");
        expectState(a, "第 4 步 开局后 153 是 MATCHED 或 READY（置 ready 在建房回包之后）", QueueState.QUEUE_STATE_MATCHED, QueueState.QUEUE_STATE_READY);

        // ---- 第 5 步：补签 ----
        steps.step("5-reissue", report);
        RequestBattleTicketResponse reissued = reissue(a, battleId);
        boolean sameTicket = reissued.getErrorMessage().getId() == 0 && reissued.hasAssignment()
                && reissued.getAssignment().toByteString().equals(started.assigned().toByteString());
        report.check(sameTicket, "第 5 步 A 发 179 → 无错误，assignment 与 177 逐字节相同（同一份 payload，HMAC 是确定性的）",
                BattleSmokeChecks.describe(reissued.getErrorMessage()) + " assignment=" + reissued.hasAssignment(), "match-spec §4.1");
        RequestBattleTicketResponse outsider = reissue(c, battleId);
        report.check(outsider.getErrorMessage().getId() == BattleSmokeChecks.TIP_INVALID_PARAMETER && !outsider.hasAssignment(),
                "第 5 步 非成员 C 发同一个 battle_id 的 179 → 1005，无 assignment（battle 的裁决原样透传）",
                BattleSmokeChecks.describe(outsider.getErrorMessage()) + " assignment=" + outsider.hasAssignment(), "match-spec §4.3 第 4 行");
        long ghost = BattleFixtures.newBattleId(random);
        RequestBattleTicketResponse gone = reissue(c, ghost);
        String goneProblem = BattleSmokeChecks.tipProblem(gone.getErrorMessage(), BattleSmokeChecks.TIP_INVALID_PARAMETER,
                BattleSmokeChecks.TEXT_BATTLE_GONE);
        report.check(goneProblem == null && !gone.hasAssignment(), "第 5 步 C 发不存在的 battle_id 的 179 → {1005, [\"该战斗不存在或已结束\"]}",
                orDescribe(goneProblem, "assignment=" + gone.hasAssignment()), "match-spec §4.3 第 3 行");

        // ---- 第 6 步：凭补签的票直连，打完 ----
        steps.step("6-direct-fight", report);
        // 补签没拿到票时退回 177 的票，让后面的步骤照常跑（上面已经记了失败）
        BattleAssignedS2C ticket = sameTicket ? reissued.getAssignment() : started.assigned();
        Direct direct = connect(a.name, ticket);
        JoinQueueResponse inBattle = MatchSupport.join(a, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG);
        checkNull(BattleSmokeChecks.joinRejectProblem(inBattle, BattleSmokeChecks.TIP_IN_BATTLE, BattleSmokeChecks.TEXT_IN_BATTLE, ""),
                "第 6 步 战斗中（开自动之前）再排 PVE_SOLO → 16000「战斗尚未结束,无法排队」", BattleSmokeChecks.describe(inBattle),
                "match-spec §2.2 第 4 行");
        MatchSupport.enableAuto(direct, battleId, battleIds);
        Finished first = MatchSupport.awaitEnd(direct, battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        direct.close();
        aTurns = first.turns();
        BattleEndS2C end = first.end();
        report.check(end.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && end.getSettlement().getPlayerId() == a.id()
                        && end.getSettlement().getTotalRounds() >= 1 && first.turns() >= 1,
                "第 6 步 挂机打到 150：SIDE_A_WIN、settlement.player_id = A、total_rounds ≥ 1，直连上至少一条 139",
                "outcome=" + end.getOutcome() + " settlement.player=" + uid(end.getSettlement().getPlayerId()) + " rounds="
                        + end.getSettlement().getTotalRounds() + " 139 × " + first.turns(), "robot/features_battle_smoke.go:82-95");
        report.check(first.fin(), "第 6 步 150 之后服务端 FIN（不是 RST / 超时）", "关闭方式=" + first.closed() + " 帧=" + first.labels(),
                "battle-node-spec §3.6");

        // ---- 第 7 步：立即再排，打第二局 ----
        steps.step("7-requeue", report);
        JoinAttempts requeue = MatchSupport.joinRetrying(a, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG, Set.of(BattleSmokeChecks.TIP_IN_BATTLE),
                tempo);
        report.check(!requeue.saw(BattleSmokeChecks.TIP_ALREADY_QUEUED),
                "第 7 步 打完立即再排：不得出现 16001（上一局的 ready 票在战斗锁放掉之后必须被自愈清掉）", requeue.describe(), "match-spec §2.2「自愈」");
        must(requeue.accepted(), "第 7 步 再排在 " + tempo.settleTimeout().toSeconds() + " s 内受理（结算落地之前的 16000 按过渡态重试）",
                requeue.describe());
        Started second = MatchSupport.awaitBattle(a.name, a.connection(), requeue.mark(), 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        report.check(second.battleId() != battleId, "第 7 步 第二局是新的 battle_id", uid(battleId) + " → " + uid(second.battleId()), REF);
        // 开战即直连（回合结算只走直连），再做旧局的补签
        Direct secondDirect = connect(a.name, second.assigned());
        RequestBattleTicketResponse old = reissue(a, battleId);
        report.check(old.getErrorMessage().getId() == BattleSmokeChecks.TIP_INVALID_PARAMETER && !old.hasAssignment(),
                "第 7 步 第一局结束后补签它 → 1005（落点记录还在，battle 回「房间不存在」原样透传；只断言 id）",
                BattleSmokeChecks.describe(old.getErrorMessage()) + " assignment=" + old.hasAssignment(), "match-spec §4.3、坑 11");
        MatchSupport.enableAuto(secondDirect, second.battleId(), battleIds);
        Finished secondEnd = MatchSupport.awaitEnd(secondDirect, second.battleId(), battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        secondDirect.close();
        report.check(secondEnd.end().getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && secondEnd.fin(),
                "第 7 步 第二局同样挂机打到 150（SIDE_A_WIN）后 FIN——否则 A 带着战斗锁进第 8 步会一直 16000",
                "outcome=" + secondEnd.end().getOutcome() + " 关闭方式=" + secondEnd.closed(), REF);
    }

    private RequestBattleTicketResponse reissue(Bot bot, long battle) throws RobotException {
        return bot.call(ids.requestTicket(), RequestBattleTicketRequest.newBuilder().setBattleId(battle).build(),
                RequestBattleTicketResponse.parser());
    }

    // ---------------------------------------------------------------- 第 8 步

    private void rankedDuel(Bot a, Bot b) throws RobotException {
        steps.step("8-pvp-rating", report);
        Rating beforeA = admin.rating(a.id());
        Rating beforeB = admin.rating(b.id());
        report.note("赛前评分 A " + beforeA + "，B " + beforeB);
        JoinAttempts queueA = MatchSupport.joinRetrying(a, ids, MatchMode.MATCH_MODE_1V1, PVP_CONFIG, Set.of(BattleSmokeChecks.TIP_IN_BATTLE),
                tempo);
        must(queueA.accepted() && BattleSmokeChecks.joinAcceptedProblem(queueA.response()) == null,
                "第 8 步 A 排 1V1 受理（上一局的锁未放时的 16000 按过渡态重试）", queueA.describe());
        // A 的受理应答已经回来：A 的票先入队，是锚点
        int markB = b.mark();
        JoinQueueResponse queueB = MatchSupport.join(b, ids, MatchMode.MATCH_MODE_1V1, PVP_CONFIG);
        must(BattleSmokeChecks.joinAcceptedProblem(queueB) == null, "第 8 步 A 受理之后 B 排同一条队列，受理",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(queueB), BattleSmokeChecks.describe(queueB)));
        Started startA = MatchSupport.awaitBattle(a.name, a.connection(), queueA.mark(), 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        Started startB = MatchSupport.awaitBattle(b.name, b.connection(), markB, 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        must(startA.battleId() == startB.battleId(), "第 8 步 两人收到同一个 battle_id 的 177 / 143",
                "A " + uid(startA.battleId()) + "，B " + uid(startB.battleId()));
        pvpBattleId = startA.battleId();
        report.note("1V1 battle_id=" + uid(pvpBattleId));
        List<Long> order = List.of(a.id(), b.id());
        String sides = BattleSmokeChecks.sidesProblem(startA.start().getState(), order, List.of(0, 1));
        String sidesB = BattleSmokeChecks.sidesProblem(startB.start().getState(), order, List.of(0, 1));
        report.check(sides == null && sidesB == null, "第 8 步 143 的 actors 里 A（锚点）在 0 队、B 在 1 队（两人各自收到的 143 都如此）",
                sides != null ? "A 的 143：" + sides : sidesB != null ? "B 的 143：" + sidesB : "", "match-spec §2.9");

        Direct directA = connect(a.name, startA.assigned());
        Direct directB = connect(b.name, startB.assigned());
        MatchSupport.enableAuto(directA, pvpBattleId, battleIds);
        MatchSupport.enableAuto(directB, pvpBattleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, pvpBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        Finished endB = MatchSupport.awaitEnd(directB, pvpBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        directA.close();
        directB.close();
        eBattleOutcome outcome = endA.end().getOutcome();
        int rounds = endA.end().getSettlement().getTotalRounds();
        report.check(endB.end().getOutcome() == outcome && endA.fin() && endB.fin(), "第 8 步 都开自动 → 两人都收到 150（同一个终局）后 FIN",
                "A outcome=" + outcome + " rounds=" + rounds + " 关闭=" + endA.closed() + "；B outcome=" + endB.end().getOutcome() + " 关闭="
                        + endB.closed(), REF);

        // 评分经 battle → Kafka → xm-match 入账：收到 150 后最多等 10 s
        List<Rating> before = List.of(beforeA, beforeB);
        List<Rating> after = MatchSupport.awaitRatings(admin, before, tempo);
        String problem = BattleSmokeChecks.ratingProblem(before, after, List.of(0, 1), outcome, rounds);
        report.check(problem == null, "第 8 步 收到 150 后 " + MatchSupport.RATING_WAIT.toSeconds() + " s 内评分落账：games 各 + 1；"
                        + ratingRule(outcome, rounds),
                problem == null ? "A " + beforeA + " → " + after.get(0) + "；B " + beforeB + " → " + after.get(1)
                        : problem + "（没入账先看 xm_match_rating_updates_total 与 xm-battle 的结果事件兜底日志）", "match-spec §5.1、§15.5 第 8 步");
    }

    private static String ratingRule(eBattleOutcome outcome, int rounds) {
        if (!BattleSmokeChecks.rated(outcome)) {
            return "终局 " + outcome;
        }
        long delta = BattleSmokeChecks.evenDeltaCenti(outcome, rounds);
        return delta == 0 ? "平局或打满 " + BattleSmokeChecks.RATING_DRAW_ROUND_CAP + " 回合（本局 " + rounds + " 回合）Δ 都是 0"
                : "胜负局且不满 " + BattleSmokeChecks.RATING_DRAW_ROUND_CAP + " 回合（本局 " + rounds + " 回合）：Δ 互为相反数、|Δ| = 16";
    }

    // ---------------------------------------------------------------- 第 9 步

    private void challenge(Bot a, Bot b, Bot c) throws RobotException {
        steps.step("9-challenge", report);
        ChallengePlayerResponse self = challenge(a, a.id());
        checkNull(BattleSmokeChecks.tipProblem(self.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_SELF, BattleSmokeChecks.TEXT_CHALLENGE_SELF),
                "第 9 步 A 挑战自己 → 16007「不能挑战自己」", BattleSmokeChecks.describe(self.getErrorMessage()), "match-spec §6.1");

        // 上一局（1V1）的战斗锁在结算落地前还在：A 的锁 → 16010，B 的锁 → 16009，都是过渡态
        Invite first = inviteRetrying(a, b, "第 9 步 A 挑战 B");
        ChallengePlayerResponse pending = challenge(c, b.id());
        checkNull(BattleSmokeChecks.tipProblem(pending.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_PENDING, BattleSmokeChecks.TEXT_CHALLENGE_PENDING),
                "第 9 步 B 有待应答的邀请时 C 挑战 B → 16011「对方已有待处理的切磋邀请」", BattleSmokeChecks.describe(pending.getErrorMessage()),
                "match-spec §6.1");
        RespondChallengeResponse notTarget = respond(c, first.challengeId(), true);
        checkNull(BattleSmokeChecks.tipProblem(notTarget.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_NOT_TARGET,
                        BattleSmokeChecks.TEXT_CHALLENGE_NOT_TARGET),
                "第 9 步 C 应答发给 B 的邀请 → 16013「该邀请不是发给你的」", BattleSmokeChecks.describe(notTarget.getErrorMessage()), "match-spec §6.1");

        int markA = a.mark();
        int markB = b.mark();
        RespondChallengeResponse declined = respond(b, first.challengeId(), false);
        report.check(declined.getErrorMessage().getId() == 0, "第 9 步 B 拒绝：应答无错误（上一步的 16013 没有消费掉这条邀请）",
                BattleSmokeChecks.describe(declined.getErrorMessage()), "match-spec §6.1");
        Optional<ChallengeResultS2C> declinedPush = awaitResult(a, markA, first.challengeId());
        report.check(declinedPush.isPresent() && BattleSmokeChecks.resultProblem(declinedPush.get(), first.challengeId(), false, b.id()) == null,
                "第 9 步 拒绝后发起者 A 收到 154 {accepted = false, responder_id = B}",
                declinedPush.map(p -> orDescribe(BattleSmokeChecks.resultProblem(p, first.challengeId(), false, b.id()), ""))
                        .orElse(MatchSupport.PUSH_TIMEOUT.toSeconds() + " s 内没收到" + a.describeSince(markA)), "match-spec §6.1 第 7 行");
        Optional<ChallengeResultS2C> strayPush = MatchSupport.awaitPush(b.connection(), markB, ids.challengeResult(), ChallengeResultS2C.parser(),
                p -> p.getChallengeId() == first.challengeId(), tempo.silence());
        report.check(strayPush.isEmpty(), "第 9 步 拒绝只推发起者：应答者 B 在 " + tempo.silence().toMillis() + " ms 内没有收到 154",
                strayPush.isPresent() ? "B 也收到了" : "", "match-spec §8.3");
        RespondChallengeResponse consumed = respond(b, first.challengeId(), false);
        checkNull(BattleSmokeChecks.tipProblem(consumed.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_EXPIRED, BattleSmokeChecks.TEXT_CHALLENGE_EXPIRED),
                "第 9 步 B 再应答同一条邀请 → 16012「切磋邀请已过期」（记录是一次性的）", BattleSmokeChecks.describe(consumed.getErrorMessage()),
                "match-spec §6.1");

        // 再发起、接受：先推双方 154 true，再开局
        Invite second = inviteRetrying(a, b, "第 9 步 A 再次挑战 B");
        markA = a.mark();
        markB = b.mark();
        RespondChallengeResponse accepted = respond(b, second.challengeId(), true);
        must(accepted.getErrorMessage().getId() == 0, "第 9 步 B 接受：应答无错误", BattleSmokeChecks.describe(accepted.getErrorMessage()));
        Optional<ChallengeResultS2C> acceptedA = awaitResult(a, markA, second.challengeId());
        Optional<ChallengeResultS2C> acceptedB = awaitResult(b, markB, second.challengeId());
        report.check(acceptedA.isPresent() && acceptedB.isPresent()
                        && BattleSmokeChecks.resultProblem(acceptedA.get(), second.challengeId(), true, b.id()) == null
                        && BattleSmokeChecks.resultProblem(acceptedB.get(), second.challengeId(), true, b.id()) == null,
                "第 9 步 接受后 A、B 都收到 154 {accepted = true, responder_id = B}",
                "A " + acceptedA.map(p -> "accepted=" + p.getAccepted()).orElse("没收到" + a.describeSince(markA)) + "；B "
                        + acceptedB.map(p -> "accepted=" + p.getAccepted()).orElse("没收到" + b.describeSince(markB)), "match-spec §6.1 第 10 行");
        Started startA = MatchSupport.awaitBattle(a.name, a.connection(), markA, 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        Started startB = MatchSupport.awaitBattle(b.name, b.connection(), markB, 0, battleIds, MatchSupport.BATTLE_START_TIMEOUT);
        must(startA.battleId() == startB.battleId(), "第 9 步 两人收到同一个 battle_id 的 177 / 143（切磋，mode 6）",
                "A " + uid(startA.battleId()) + "，B " + uid(startB.battleId()));
        challengeBattleId = startA.battleId();
        report.note("切磋 battle_id=" + uid(challengeBattleId));
        String sides = BattleSmokeChecks.sidesProblem(startA.start().getState(), List.of(a.id(), b.id()), List.of(0, 1));
        report.check(sides == null, "第 9 步 切磋的 143：发起者 A 在 0 队、应战者 B 在 1 队", orDescribe(sides, ""), "match-spec §2.9");

        Direct directA = connect(a.name, startA.assigned());
        Direct directB = connect(b.name, startB.assigned());
        // 开自动之前：A 持战斗锁，C 挑 A 被拒
        ChallengePlayerResponse busy = challenge(c, a.id());
        checkNull(BattleSmokeChecks.tipProblem(busy.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_TARGET_BUSY,
                        BattleSmokeChecks.TEXT_CHALLENGE_TARGET_BUSY),
                "第 9 步 A 在战斗中（开自动之前）C 挑战 A → 16009「对方正在战斗中」", BattleSmokeChecks.describe(busy.getErrorMessage()),
                "match-spec §6.1 第 4 行");
        // C 的戏份到此为止：按契约收尾（LeaveGame 后断开），gate 断线即删在线目录
        c.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        c.connection().close();

        MatchSupport.enableAuto(directA, challengeBattleId, battleIds);
        MatchSupport.enableAuto(directB, challengeBattleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, challengeBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        Finished endB = MatchSupport.awaitEnd(directB, challengeBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        directA.close();
        directB.close();
        report.check(endA.end().getOutcome() == endB.end().getOutcome() && endA.fin() && endB.fin(), "第 9 步 都开自动 → 两人都收到 150 后 FIN",
                "A outcome=" + endA.end().getOutcome() + " 关闭=" + endA.closed() + "；B outcome=" + endB.end().getOutcome() + " 关闭="
                        + endB.closed(), REF);

        // C 已下线：A 的锁在结算落地前仍在，先命中第 3 行 16010（过渡态），放掉之后才走到第 6 行 16008
        ChallengePlayerResponse offline = challengeRetrying(a, c.id(), Set.of(BattleSmokeChecks.TIP_CHALLENGE_SELF_BUSY));
        checkNull(BattleSmokeChecks.tipProblem(offline.getErrorMessage(), BattleSmokeChecks.TIP_CHALLENGE_TARGET_OFFLINE,
                        BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE),
                "第 9 步 C 下线后 A 挑战 C → 16008「对方不在线」（此前的 16010 按过渡态重试）", BattleSmokeChecks.describe(offline.getErrorMessage())
                        + (offline.getChallengeId() != 0 ? " challenge_id=" + uid(offline.getChallengeId()) : ""), "match-spec §6.1 第 6 行");
    }

    /** 一次受理的发起：切磋号与目标收到的 156 已核对。 */
    private record Invite(long challengeId) {
    }

    /** A 发 152(B) 直到受理（两人上一局的锁未放时的 16010 / 16009 按过渡态重试），并核对 B 收到的 156。 */
    private Invite inviteRetrying(Bot challenger, Bot target, String name) throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        Set<Integer> transientCodes = Set.of(BattleSmokeChecks.TIP_CHALLENGE_SELF_BUSY, BattleSmokeChecks.TIP_CHALLENGE_TARGET_BUSY);
        List<Integer> retried = new ArrayList<>();
        while (true) {
            int targetMark = target.mark();
            long sentAt = System.currentTimeMillis();
            ChallengePlayerResponse response = challenge(challenger, target.id());
            int tip = response.getErrorMessage().getId();
            if (MatchSupport.shouldRetry(tip, transientCodes, System.nanoTime(), deadline)) {
                retried.add(tip);
                BattleSupport.sleep(tempo.retryInterval());
                continue;
            }
            long challengeId = response.getChallengeId();
            must(tip == 0 && challengeId != 0, name + " → 受理，challenge_id ≠ 0（上一局的锁未放时的 16010 / 16009 按过渡态重试）",
                    BattleSmokeChecks.describe(response.getErrorMessage()) + " challenge_id=" + uid(challengeId)
                            + (retried.isEmpty() ? "" : "（此前重试 " + retried + "）"));
            Optional<Received> push = target.connection().await(targetMark, r -> {
                if (r.messageId() != ids.challengeInvite() || r.requestId() != 0) {
                    return false;
                }
                ChallengeInviteS2C invite = r.parseOrNull(ChallengeInviteS2C.parser());
                return invite != null && invite.getChallengeId() == challengeId;
            }, MatchSupport.PUSH_TIMEOUT);
            String problem = push.isEmpty() ? MatchSupport.PUSH_TIMEOUT.toSeconds() + " s 内 " + target.name + " 没收到 156" + target.describeSince(targetMark)
                    : BattleSmokeChecks.inviteProblem(push.get().parse(ChallengeInviteS2C.parser()), challengeId, challenger.id(), challenger.account(),
                            PVP_CONFIG, sentAt, MatchSupport.wallClockMillis(push.get()));
            report.check(problem == null, name + "：" + target.name + " 收到 156（challenger_id = " + challenger.name + "、challenger_name = "
                    + challenger.name + " 的账号、expires_at_ms ≈ 发起 + 60 s）", orDescribe(problem, ""), "match-spec §6.1 第 11 行");
            return new Invite(challengeId);
        }
    }

    private ChallengePlayerResponse challenge(Bot challenger, long targetId) throws RobotException {
        return challenger.call(ids.challenge(), ChallengePlayerRequest.newBuilder().setPlayerId(challenger.id()).setTargetPlayerId(targetId)
                .setBattleConfigId(PVP_CONFIG).build(), ChallengePlayerResponse.parser());
    }

    /** 发 152；应答是过渡态的码时每 1 s 重试、上限 20 s；返回最后一次的应答。 */
    private ChallengePlayerResponse challengeRetrying(Bot challenger, long targetId, Set<Integer> transientCodes) throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        while (true) {
            ChallengePlayerResponse response = challenge(challenger, targetId);
            if (!MatchSupport.shouldRetry(response.getErrorMessage().getId(), transientCodes, System.nanoTime(), deadline)) {
                return response;
            }
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    private RespondChallengeResponse respond(Bot responder, long challengeId, boolean accept) throws RobotException {
        return responder.call(ids.respondChallenge(), RespondChallengeRequest.newBuilder().setPlayerId(responder.id()).setChallengeId(challengeId)
                .setAccept(accept).build(), RespondChallengeResponse.parser());
    }

    private Optional<ChallengeResultS2C> awaitResult(Bot bot, int mark, long challengeId) throws RobotException {
        return MatchSupport.awaitPush(bot.connection(), mark, ids.challengeResult(), ChallengeResultS2C.parser(),
                p -> p.getChallengeId() == challengeId, MatchSupport.PUSH_TIMEOUT);
    }

    // ---------------------------------------------------------------- 第 10–11 步

    private void spectatePlaceholders(Bot a) throws RobotException {
        steps.step("10-spectate-placeholder", report);
        WatchBattleResponse watch = a.call(ids.watchBattle(), WatchBattleRequest.newBuilder().setPlayerId(a.id()).build(),
                WatchBattleResponse.parser());
        report.check(watch.getErrorMessage().getId() == BattleSmokeChecks.TIP_FEATURE_UNAVAILABLE && watch.getBattleId() == 0,
                "第 10 步 163 WatchBattle → in-band error_message{1006}（6.4 的临时应答，观战归 6.5）",
                BattleSmokeChecks.describe(watch.getErrorMessage()) + " battle_id=" + uid(watch.getBattleId()), "match-spec §8.6");
        ListWatchableBattlesResponse list = a.call(ids.listWatchable(), ListWatchableBattlesRequest.newBuilder().setPlayerId(a.id()).build(),
                ListWatchableBattlesResponse.parser());
        report.check(list.getBattlesCount() == 0, "第 10 步 164 ListWatchableBattles → 空列表", list.getBattlesCount() + " 条", "match-spec §8.6");
    }

    private void metrics(String before) throws RobotException {
        steps.step("11-metrics", report);
        String after = admin.scrapeMetrics();
        metricGrew(before, after, EXPECTED_GATHERS, "xm_match_gathers_total", "outcome=\"success\"");
        metricGrew(before, after, 1, "xm_match_battle_ticket_reissues_total", "result=\"ok\"");
        metricGrew(before, after, 1, "xm_match_challenges_total");
        metricGrew(before, after, 1, "xm_match_rating_updates_total", "outcome=\"applied\"");
    }

    private void metricGrew(String before, String after, int atLeast, String metric, String... labels) {
        double b = AdminClient.sum(before, metric, labels);
        double a = AdminClient.sum(after, metric, labels);
        report.check(a - b >= atLeast, "第 11 步 指标 " + metric + (labels.length == 0 ? "" : "{" + String.join(",", labels) + "}") + " 本轮至少 + " + atLeast,
                b + " → " + a, "match-spec §11、§15.5 第 11 步");
    }

    // ---------------------------------------------------------------- 工具

    /** 177 的票据形状：通告地址、role = 1、签名是 64 位小写 hex、payload 能解析且指向本人本局。 */
    private void checkTicket(String step, BattleAssignedS2C assigned, long playerId) {
        BattleTicketPayload payload;
        try {
            payload = BattleTicketPayload.parseFrom(assigned.getTokenPayload());
        } catch (InvalidProtocolBufferException e) {
            report.fail(step + " token_payload 能解析为 BattleTicketPayload", e.getMessage(), "battle-node-spec §2.1");
            return;
        }
        report.check(assigned.getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT && !assigned.getHost().isEmpty()
                        && assigned.getPort() > 0 && assigned.getPort() <= 65535
                        && BattleFixtures.isLowerHex64(assigned.getTokenSignature()) && payload.getBattleId() == assigned.getBattleId()
                        && payload.getPlayerId() == playerId,
                step + "：role = 1、通告地址非空、token_signature 是 64 位小写 hex、payload 指向本人本局",
                "role=" + assigned.getRole().getNumber() + " endpoint=" + assigned.getHost() + ":" + assigned.getPort() + " payload.player="
                        + uid(payload.getPlayerId()), "battle-node-spec §2.3");
    }

    @FunctionalInterface
    private interface Phase {
        void run() throws RobotException;
    }

    private void phase(String name, Phase body) {
        try {
            body.run();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断：" + name, message(e), REF);
        }
    }

    /** 一条「问题为 null 才算过」的检查；细节里总带上实得的应答（通过时便于对照，失败时在问题之后）。 */
    private void checkNull(String problem, String name, String described, String ref) {
        report.check(problem == null, name, problem == null ? described : problem + "；实得 " + described, ref);
    }

    /** 后续步骤依赖的检查：不通过即中止本阶段（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，本阶段后面的步骤依赖它");
        }
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        return new Bot(name, player, requestTimeout, tempo);
    }

    private Direct connect(String name, BattleAssignedS2C ticket) throws RobotException {
        Direct direct = MatchSupport.connect(client, name, ticket, battleIds, requestTimeout);
        closeables.add(direct);
        return direct;
    }

    /** 收尾：还连着的排队者各发一条 148("")（取消当前票，尽力而为），再关掉全部连接。 */
    private void cleanup() {
        for (Bot bot : queuers) {
            if (bot.connection().isOpen()) {
                try {
                    MatchSupport.cancel(bot, ids, "");
                } catch (RobotException | RuntimeException ignored) {
                    // 尽力而为：断线之后凑单也会把离线成员的票删掉
                }
            }
        }
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结论
            }
        }
    }

    private static String orDescribe(String problem, String fallback) {
        return problem == null ? fallback : problem;
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
