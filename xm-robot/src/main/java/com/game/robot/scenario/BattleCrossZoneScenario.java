package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eSpectateEndReason;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.WatchBattleResponse;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GateAssignment;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.MatchSupport.AutoRequest;
import com.game.robot.scenario.MatchSupport.Bot;
import com.game.robot.scenario.MatchSupport.Finished;
import com.game.robot.scenario.MatchSupport.JoinAttempts;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.game.robot.scenario.SpectateSteps.Ended;
import com.game.robot.scenario.SpectateSteps.Timing;
import com.game.robot.scenario.SpectateSteps.Watching;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 跨区 1V1（批次 6.5，spectate-spec §10.8「场景 battle-cross-zone」；基线 {@code robot/battle_smoke_cross_zone_scenario.go}，补上它的几处弱点，
 * 并加跨区观众与第二局）。三个新号：A 经 {@code --zone} 的 gate 登录，B、C 经 {@code --visit-zone} 的 gate 登录；xm-match 与 xm-battle 不分区，
 * 两个区的 gate 把匹配请求转给同一组 xm-match。
 * <ol>
 *   <li>Z0 {@code GET /api/server-list}：两个区都是 OPEN，否则失败（{@code step=preflight}，不跳过——本场景验的就是这件事）；</li>
 *   <li>Z1 A、B、C 登录进场；两个区 assign-gate 给的 gate 端点不同；</li>
 *   <li>Z2 抓 xm-match 指标与两人的评分作基数；</li>
 *   <li>Z3 A 发 157 {1V1, config = 1, zone_id = 本区}，<b>A 的回包到了</b> B 才发（A 是锚点）；两个回包都受理、票号是 UUID；</li>
 *   <li>Z4 两侧大厅上 177 都在 143 之前、同一个非 0 的 battle_id；两张票的 {@code host:port}、签发节点与实例相同，player_id 各是自己；
 *       143 里 A 在 0 队、B 在 1 队；</li>
 *   <li>Z5 A、B 各自直连（握手 battle_id 一致、补拉 140 成功），都不开自动；区 B 的 C 发 163(battle_id)：成功 → 177（role = 2）经区 B 的 gate
 *       到达 → 直连 → 161 {observer_count = 1}；</li>
 *   <li>Z6 A、B 开自动打到 150：两侧 outcome 相同且是胜 / 负 / 平，直连回合数 ≥ 1，大厅上没有 139；C：≥ 1 条 158 → 166 {FINISHED, 同一个
 *       outcome} → FIN；</li>
 *   <li>Z7 两侧各等<b>大厅</b>上的 150（scene 应用结算后才推，证明结算回到了各自所在区的 scene）；</li>
 *   <li>Z8 评分（经 {@link MatchAdminClient}）：games 各 + 1；胜负且不满 30 回合 Δ 互为相反数、|Δ| = 16，平局或打满 Δ = 0；
 *       没有运维令牌 → 失败（{@code step=z8-admin-token}），不静默跳过；</li>
 *   <li>Z9 第二局：按 Z3 再排（16000 按过渡态重试、<b>不得</b> 16001）→ 重复 Z4、Z6、Z7（不带观众）与评分：battle_id 不同、games 再各 + 1——
 *       覆盖基线「连续对局」暴露过的三处：0 血带入、ready 残留、把上一局迟到的 150 当成本局的；</li>
 *   <li>Z10 xm-match 指标增量：{@code gather_zone_mix{mix="cross"}} ≥ 2（对 mode 求和）、{@code watch_battle{outcome="ok"}} ≥ 1；</li>
 *   <li>Z11 三人 LeaveGame；结果行 {@code CROSS_ZONE_MATCH_OK battle_id=… zone_a=… zone_b=… a_turns=… b_turns=… a_direct_turns=…
 *       b_direct_turns=… observer_zone=… c_spectate_turns=… second_battle_id=…}，失败是 {@code CROSS_ZONE_MATCH_FAIL step=… reason=…}。</li>
 * </ol>
 * 前置：{@code XM_ZONES=2} 的切片（两个区各有 gate 与 scene，都在 gateway 的区服列表里）、xm-match、xm-battle、Kafka、dev 运行模式、运维令牌。
 * 1V1 的 1 号配置队列是全服共享的：切片上同时有别的客户端在排它时会被凑走。
 *
 * <p>节奏与 battle-smoke 相同（{@link MatchSupport#REQUEST_SPACING}、开战 30 s、终局 120 s、观众票与 161 各 15 s）。前一步不成立时后面的
 * 步骤没有意义，所以除了纯核对之外都是「不通过即中止」；收尾（含失败路径）给 A、B 各发一条 148("")，免得把排队票留在全服共享的队列里
 * 凑走下一遍的 A。结果行里的步骤号是小写（{@link StepTrack} 只认小写）。
 */
public final class BattleCrossZoneScenario {

    static final String REF = "spectate-spec §10.8";
    static final String MARKER = "CROSS_ZONE_MATCH";
    /** 直连 150 之后等大厅 150 的上限（scene 应用结算 + 经 gate 推送；spectate-spec §10.8 Z7）。 */
    static final Duration LOBBY_END_TIMEOUT = Duration.ofSeconds(25);

    private final RobotClient homeClient;
    private final PlayerFlow homeFlow;
    private final RobotClient visitClient;
    private final PlayerFlow visitFlow;
    private final GatewayHttp gateway;
    private final MatchSupport.Ids ids;
    private final BattleIds battleIds;
    private final MatchAdminClient admin;
    private final int leaveGame;
    private final int zoneA;
    private final int zoneB;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final Duration requestTimeout;
    private final Tempo tempo;
    private final Timing timing;
    private final Duration lobbyEndTimeout;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps = new StepTrack(MARKER);
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();
    private final List<Bot> queuers = new ArrayList<>();

    /** 结果行的字段（没跑到的保持 0）。 */
    private long battleId;
    private int aTurns;
    private int bTurns;
    private int spectateTurns;
    private long secondBattleId;

    /**
     * @param homeClient  {@code --zone} 那个区的客户端（A）
     * @param visitClient {@code --visit-zone} 那个区的客户端（B、C）
     * @param gateway     xm-gateway 的 HTTP 接口（区服列表）
     */
    public BattleCrossZoneScenario(RobotClient homeClient, PlayerFlow homeFlow, RobotClient visitClient, PlayerFlow visitFlow, GatewayHttp gateway,
                                   MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix, String runTag, int zone, int visitZone,
                                   Duration requestTimeout) {
        this(homeClient, homeFlow, visitClient, visitFlow, gateway, registry, admin, accountPrefix, runTag, zone, visitZone, requestTimeout,
                Tempo.STANDARD, Timing.STANDARD, LOBBY_END_TIMEOUT);
    }

    /** 单测对着本机假服务端时调快节奏与各种上限；对真服务端用上面的构造器。 */
    BattleCrossZoneScenario(RobotClient homeClient, PlayerFlow homeFlow, RobotClient visitClient, PlayerFlow visitFlow, GatewayHttp gateway,
                            MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix, String runTag, int zone, int visitZone,
                            Duration requestTimeout, Tempo tempo, Timing timing, Duration lobbyEndTimeout) {
        if (zone == visitZone) {
            throw new IllegalArgumentException("跨区场景的两个区不能相同：" + zone);
        }
        this.homeClient = homeClient;
        this.homeFlow = homeFlow;
        this.visitClient = visitClient;
        this.visitFlow = visitFlow;
        this.gateway = gateway;
        this.ids = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.zoneA = zone;
        this.zoneB = visitZone;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.requestTimeout = requestTimeout;
        this.tempo = tempo;
        this.timing = timing;
        this.lobbyEndTimeout = lobbyEndTimeout;
    }

    /** 账号：{@code 前缀 + xz + 标签 + _a / _b / _c}（每轮新号：评分都从 1500 起，没有上一轮的残留）。 */
    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "xz" + runTag + "_" + suffix;
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
            leaveAll();
        }
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）：{@code CROSS_ZONE_MATCH_OK …} 或 {@code CROSS_ZONE_MATCH_FAIL step=… reason=…}。 */
    public String resultLine() {
        return steps.line(report, BattleCrossZoneChecks.okFields(battleId, zoneA, zoneB, aTurns, bTurns, zoneB, spectateTurns, secondBattleId));
    }

    private void runChecks() throws RobotException {
        // ---- Z0：两个区都开着 ----
        steps.step("preflight", report);
        JsonNode serverList = gateway.get("/api/server-list");
        String zones = BattleCrossZoneChecks.zonesProblem(serverList, zoneA, zoneB);
        must(zones == null, "Z0 区服列表里区 " + zoneA + "、区 " + zoneB + " 都是 OPEN（本场景验的就是跨区，缺区即失败、不跳过）",
                orDescribe(zones, serverList.path("zones").toString()));
        if (!admin.hasToken()) {
            // Z8 读评分要运维令牌，赛前的基数也要读：现在就失败，不白打一局、更不静默跳过
            steps.step("z8-admin-token", report);
            report.fail("Z8 读评分需要运维令牌", "设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）", REF + " 入口");
            throw new RobotException("没有运维令牌，评分那一步（Z8）无法核对");
        }

        // ---- Z1：三个号登录进场 ----
        steps.step("z1-login", report);
        // 落区的证据：assign-gate 按区给 gate，两个区的 gate 端点不同（切片里是 11000 / 11010）。端点取自这里另发的一次 assign-gate
        // （登录流程不暴露它实际连的地址）；同一个区只有自己的 gate，所以这与「实际连接的端点不同」等价
        GateAssignment gateA = homeClient.assignGate();
        GateAssignment gateB = visitClient.assignGate();
        report.check(!BattleCrossZoneChecks.endpoint(gateA).equals(BattleCrossZoneChecks.endpoint(gateB)),
                "Z1 区 " + zoneA + " 与区 " + zoneB + " 的 gate 端点不同（两人确实从不同的区进来；相同就不是跨区）",
                "区 " + zoneA + " → " + BattleCrossZoneChecks.endpoint(gateA) + "，区 " + zoneB + " → " + BattleCrossZoneChecks.endpoint(gateB),
                REF + " Z1");
        Bot a = enter(homeFlow, "A", accountA);
        Bot b = enter(visitFlow, "B", accountB);
        Bot c = enter(visitFlow, "C", accountC);
        queuers.add(a);
        queuers.add(b);
        report.note("A=" + uid(a.id()) + "（区 " + zoneA + "） B=" + uid(b.id()) + " C=" + uid(c.id()) + "（区 " + zoneB + "） match-admin="
                + admin.baseUrl());
        report.note("Z1 角色列表的 zone_id（建角归属区）没有核对：建角取会话 zone 归批次 5.4（X16），合入之前 B、C 的归属区会被记成 login 的区；"
                + "1V1 不读归属区，不影响本场景");

        // ---- Z2：指标与评分的基数 ----
        steps.step("z2-baseline", report);
        String metricsBefore = admin.scrapeMetrics();
        List<Rating> ratings = List.of(admin.rating(a.id()), admin.rating(b.id()));
        report.note("赛前评分 A " + ratings.get(0) + "，B " + ratings.get(1));

        // ---- Z3–Z8：第一局，带区 B 的观众 C ----
        Round first = fight(1, a, b, c, ratings);
        battleId = first.battleId();
        aTurns = first.aTurns();
        bTurns = first.bTurns();

        // ---- Z9：第二局（不带观众） ----
        Round second = fight(2, a, b, null, first.ratings());
        secondBattleId = second.battleId();
        steps.step("z9-second-battle", report);
        report.check(second.battleId() != first.battleId(), "Z9 第二局的 battle_id 与第一局不同（没有把上一局迟到的公告当成本局的）",
                uid(first.battleId()) + " → " + uid(second.battleId()), REF + " Z9");

        // ---- Z10：指标（全局计数，只取下界） ----
        steps.step("z10-metrics", report);
        String metricsAfter = admin.scrapeMetrics();
        metricGrew(metricsBefore, metricsAfter, 2, "xm_match_gather_zone_mix_total", "mix=\"cross\"");
        metricGrew(metricsBefore, metricsAfter, 1, "xm_match_watch_battle_total", "outcome=\"ok\"");

        // ---- Z11：下线 ----
        steps.step("z11-leave", report);
        boolean allOpen = bots.stream().allMatch(bot -> bot.connection().isOpen());
        leaveAll();
        report.check(allOpen, "Z11 三人的大厅连接全程没有被服务端断开；发 LeaveGame 后下线", allOpen ? "" : "有连接在收尾之前就断了", REF + " Z11");
    }

    /**
     * 一局打完的结果。
     *
     * @param ratings 这一局之后两人的评分（A、B）
     */
    private record Round(long battleId, int aTurns, int bTurns, List<Rating> ratings) {
    }

    /**
     * 打一局：排队 → 开局公告 → 直连（第一局另有观众）→ 开自动打完 → 大厅 150 → 评分。
     *
     * @param round    1 = 第一局（Z3–Z8），2 = 第二局（Z9）
     * @param observer 观众 C；null = 不带观众
     * @param before   赛前评分（A、B）
     */
    private Round fight(int round, Bot a, Bot b, Bot observer, List<Rating> before) throws RobotException {
        boolean first = round == 1;
        String z = first ? "" : "Z9 第二局 ";

        // ---- 排队：A 的回包到了 B 再排 ----
        steps.step(first ? "z3-queue" : "z9-second-queue", report);
        // 第二局紧跟第一局：结算落地之前的 16000 按过渡态重试；第一局不该遇到任何拒绝
        Set<Integer> transientCodes = first ? Set.of() : Set.of(BattleSmokeChecks.TIP_IN_BATTLE);
        JoinAttempts queueA = joinRetrying(a, zoneA, transientCodes);
        must(BattleSmokeChecks.joinAcceptedProblem(queueA.response()) == null, (first ? "Z3 " : z) + "A 发 157 {mode = 3, config = "
                        + BattleCrossZoneChecks.BATTLE_CONFIG + ", zone_id = " + zoneA + "} → error_code = 0、票号是 UUID"
                        + (first ? "" : "（结算落地之前的 16000 每 1 s 重试、上限 " + tempo.settleTimeout().toSeconds() + " s）"),
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(queueA.response()), queueA.describe())
                        + BattleCrossZoneChecks.joinHint(queueA.response()));
        // A 的受理应答已经回来：A 的票先入队，是锚点
        JoinAttempts queueB = joinRetrying(b, zoneB, transientCodes);
        must(BattleSmokeChecks.joinAcceptedProblem(queueB.response()) == null, (first ? "Z3 " : z) + "A 的回包到了之后 B 发同样的 157（zone_id = "
                        + zoneB + "）→ error_code = 0、票号是 UUID",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(queueB.response()), queueB.describe())
                        + BattleCrossZoneChecks.joinHint(queueB.response()));
        if (!first) {
            report.check(!queueA.saw(BattleSmokeChecks.TIP_ALREADY_QUEUED) && !queueB.saw(BattleSmokeChecks.TIP_ALREADY_QUEUED),
                    z + "打完立即再排：不得出现 16001（上一局的 ready 票据在战斗锁放掉之后必须被自愈清掉）",
                    "A " + queueA.describe() + "；B " + queueB.describe(), REF + " Z9");
        }

        // ---- 开局公告 ----
        steps.step(first ? "z4-announce" : "z9-second-announce", report);
        Started startA = MatchSupport.awaitBattle(a.name, a.connection(), queueA.mark(), 0, battleIds, tempo.battleStartTimeout());
        Started startB = MatchSupport.awaitBattle(b.name, b.connection(), queueB.mark(), 0, battleIds, tempo.battleStartTimeout());
        String step = first ? "Z4 " : z;
        report.check(startA.assignedFirst() && startB.assignedFirst(), step + "两侧大厅的收件箱里 177 都在 143 之前",
                "A：177 #" + startA.assignedAt().index() + " 143 #" + startA.startAt().index() + "；B：177 #" + startB.assignedAt().index()
                        + " 143 #" + startB.startAt().index(), REF + " Z4；battle-node-spec §5.8 O1");
        String tickets = BattleCrossZoneChecks.ticketsProblem(startA.assigned(), a.id(), startB.assigned(), b.id());
        must(tickets == null, step + "两侧 battle_id 相同且非 0；两张票的 host:port、battle_node_id、battle_instance_id 相同，player_id 各是自己，"
                        + "role = PARTICIPANT",
                orDescribe(tickets, "battle_id=" + uid(startA.battleId()) + " endpoint=" + startA.assigned().getHost() + ":"
                        + startA.assigned().getPort()));
        long battle = startA.battleId();
        report.note((first ? "第一局" : "第二局") + " battle_id=" + uid(battle));
        List<Long> order = List.of(a.id(), b.id());
        String sidesA = BattleSmokeChecks.sidesProblem(startA.start().getState(), order, List.of(0, 1));
        String sidesB = BattleSmokeChecks.sidesProblem(startB.start().getState(), order, List.of(0, 1));
        report.check(sidesA == null && sidesB == null, step + "143 的阵营里 A（先受理，是锚点）在 0 队、B 在 1 队（两人各自收到的 143 都如此）",
                sidesA != null ? "A 的 143：" + sidesA : sidesB != null ? "B 的 143：" + sidesB : "", REF + " Z4");

        // ---- 直连（都不开自动）；观众入场 ----
        steps.step(first ? "z5-direct-watch" : "z9-second-direct", report);
        Direct directA = connect(homeClient, a.name, startA.assigned());
        Direct directB = connect(visitClient, b.name, startB.assigned());
        report.pass((first ? "Z5 " : z) + "A、B 各自凭票直连同一个 battle 节点：握手成功且 battle_id 与票一致、补拉 140 成功",
                startA.assigned().getHost() + ":" + startA.assigned().getPort(), REF + " Z5");
        Watching watching = null;
        if (observer != null) {
            int mark = observer.mark();
            WatchBattleResponse watched = SpectateSteps.watch(observer, ids, battle);
            must(SpectateSteps.acceptedProblem(watched, battle) == null, "Z5 区 " + zoneB + " 的 C 发 163(battle_id) → 应答 battle_id 正确、没有 error_message",
                    orDescribe(SpectateSteps.acceptedProblem(watched, battle), SpectateSteps.describe(watched)));
            Received r177 = SpectateSteps.awaitObserverTicket(observer.name, observer.connection(), mark, battle, battleIds, timing.ticketTimeout());
            BattleAssignedS2C ticket = r177.parse(BattleAssignedS2C.parser());
            checkNull(SpectateSteps.observerTicketProblem(ticket, battle, observer.id(), startA.assigned().getExpireAtMs()),
                    "Z5 C 的 177（role = 2）经区 " + zoneB + " 的 gate 到达（观众的路由取在线目录：区 " + zoneB + " 的同号 gate，不是区 " + zoneA + " 的）",
                    "endpoint=" + ticket.getHost() + ":" + ticket.getPort(), REF + " Z5；§2.8");
            watching = SpectateSteps.connectObserver(visitClient, observer.name, ticket, battleIds, timing.firstFrameTimeout());
            closeables.add(watching.direct());
            checkNull(SpectateSteps.firstFrameProblem(watching.direct().since(0), battleIds, battle, 1),
                    "Z5 C 直连后握手应答紧跟 161 {observer_count = 1}", watching.direct().labelsSince(0), REF + " Z5");
        }

        // ---- 都开自动，打到 150 ----
        steps.step(first ? "z6-fight" : "z9-second-fight", report);
        AutoRequest autoA = MatchSupport.enableAuto(directA, battle, battleIds);
        AutoRequest autoB = MatchSupport.enableAuto(directB, battle, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, battle, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoA);
        Finished endB = MatchSupport.awaitEnd(directB, battle, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoB);
        long endedNanos = endNanos(directA, battle);
        directA.close();
        directB.close();
        step = first ? "Z6 " : z;
        eBattleOutcome outcome = endA.end().getOutcome();
        int rounds = endA.end().getSettlement().getTotalRounds();
        checkNull(BattleCrossZoneChecks.outcomesProblem(endA.end(), endB.end(), battle), step + "两侧直连上的 150：outcome 相同且是胜 / 负 / 平之一",
                "outcome=" + outcome + " rounds=" + rounds, REF + " Z6");
        long lobbyTurns = BattleCrossZoneChecks.lobbyTurns(a.connection().inbox().snapshot(0), battleIds)
                + BattleCrossZoneChecks.lobbyTurns(b.connection().inbox().snapshot(0), battleIds);
        report.check(endA.turns() >= 1 && endB.turns() >= 1 && lobbyTurns == 0, step + "两侧直连上的回合数 ≥ 1，大厅连接上的 139 条数 = 0",
                "A 139 × " + endA.turns() + "，B 139 × " + endB.turns() + "，大厅 139 × " + lobbyTurns, REF + " Z6");
        report.check(endA.fin() && endB.fin() && endA.autoAccepted() && endB.autoAccepted(), step + "都开自动（162 被受理）→ 150 之后服务端 FIN",
                "A 关闭=" + endA.closed() + endA.autoNote() + "；B 关闭=" + endB.closed() + endB.autoNote(), REF + " Z6");
        if (watching != null) {
            Ended spectated = SpectateSteps.awaitEnd(watching.direct(), battle, battleIds, timing.endTimeout());
            watching.direct().close();
            spectateTurns = spectated.turns();
            checkNull(SpectateSteps.endProblem(spectated, battleIds, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, outcome, 1),
                    "Z6 C 的直连上 ≥ 1 条 158，最后 166 {FINISHED, outcome 同上} → FIN",
                    "158 × " + spectated.turns() + " 166 {" + spectated.end().getReason() + ", " + spectated.end().getOutcome() + "}", REF + " Z6");
        }

        // ---- 大厅上的 150：结算回到了各自所在区的 scene ----
        steps.step(first ? "z7-lobby-end" : "z9-second-lobby-end", report);
        String lobbyA = awaitLobbyEnd(a, queueA.mark(), battle, endedNanos);
        String lobbyB = awaitLobbyEnd(b, queueB.mark(), battle, endedNanos);
        report.check(lobbyA == null && lobbyB == null, (first ? "Z7 " : z) + "两侧的大厅连接各收到这一局的 150（scene 应用结算之后推；直连 150 之后 "
                        + lobbyEndTimeout.toSeconds() + " s 内）：结算回到了各自所在区的 scene",
                lobbyA != null ? "A（区 " + zoneA + "）：" + lobbyA : lobbyB != null ? "B（区 " + zoneB + "）：" + lobbyB : "", REF + " Z7；§2.7 Z6 / Z7");

        // ---- 评分 ----
        steps.step(first ? "z8-rating" : "z9-second-rating", report);
        List<Rating> after = MatchSupport.awaitRatings(admin, before, tempo);
        String rating = BattleSmokeChecks.ratingProblem(before, after, List.of(0, 1), outcome, rounds);
        report.check(rating == null, (first ? "Z8 " : z) + "收到 150 后 " + MatchSupport.RATING_WAIT.toSeconds() + " s 内评分落账：games 各 + 1；"
                        + "胜负且不满 " + BattleSmokeChecks.RATING_DRAW_ROUND_CAP + " 回合 Δ 互为相反数、|Δ| = 16，平局或打满 Δ 都是 0（本局 " + outcome
                        + "，" + rounds + " 回合）",
                rating == null ? "A " + before.get(0) + " → " + after.get(0) + "；B " + before.get(1) + " → " + after.get(1) : rating,
                REF + " Z8；match-spec §5.1");
        return new Round(battle, endA.turns(), endB.turns(), after);
    }

    /** 发 157（带本侧的 zone_id）；应答是过渡态的码时每 1 s 重试，直到受理、遇到别的码或到上限。 */
    private JoinAttempts joinRetrying(Bot bot, int zone, Set<Integer> transientCodes) throws RobotException {
        long deadline = System.nanoTime() + tempo.settleTimeout().toNanos();
        List<Integer> retried = new ArrayList<>();
        while (true) {
            int mark = bot.mark();
            JoinQueueResponse response = bot.call(ids.joinQueue(), BattleCrossZoneChecks.joinRequest(bot.id(), zone), JoinQueueResponse.parser());
            if (!MatchSupport.shouldRetry(response.getErrorCode(), transientCodes, System.nanoTime(), deadline)) {
                return new JoinAttempts(mark, response, retried);
            }
            retried.add(response.getErrorCode());
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    /** 这条直连上本局 150 到达的时刻（{@link System#nanoTime()}）；找不到按现在算。 */
    private long endNanos(Direct direct, long battle) {
        for (BattleFrame frame : direct.since(0)) {
            if (frame.isPush(battleIds.battleEnd()) && MatchSupport.endOf(List.of(frame), battleIds, battle) != null) {
                return frame.atNanos();
            }
        }
        return System.nanoTime();
    }

    /**
     * 从 {@code from} 起等大厅上这一局的 150，期限 = 直连 150 的时刻 + {@link #lobbyEndTimeout}（已经到了的立刻返回）。
     *
     * @return null = 收到了；否则是问题描述
     */
    private String awaitLobbyEnd(Bot bot, int from, long battle, long endedNanos) throws RobotException {
        long remaining = Math.max(0, endedNanos + lobbyEndTimeout.toNanos() - System.nanoTime());
        Optional<Received> end = bot.connection().await(from, r -> BattleCrossZoneChecks.lobbyEnd(r, battleIds, battle) != null,
                Duration.ofNanos(remaining));
        return end.isPresent() ? null : "直连 150 之后 " + lobbyEndTimeout.toSeconds() + " s 内大厅上没有 battle_id=" + uid(battle)
                + " 的 150（结算没有回到这名玩家所在区的 scene：看 xm-battle 的结算投递日志与那个区 scene 的日志）" + bot.describeSince(from);
    }

    private void metricGrew(String before, String after, int atLeast, String metric, String label) {
        double b = AdminClient.sum(before, metric, label);
        double a = AdminClient.sum(after, metric, label);
        report.check(a - b >= atLeast, "Z10 指标 " + metric + "{" + label + "} 本轮至少 + " + atLeast, b + " → " + a, REF + " Z10");
    }

    // ---------------------------------------------------------------- 工具

    /** 一条「问题为 null 才算过」的检查；细节里总带上实得的值。 */
    private void checkNull(String problem, String name, String described, String ref) {
        report.check(problem == null, name, problem == null ? described : problem + "；实得 " + described, ref);
    }

    /** 后续步骤依赖的检查：不通过即中止（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后面的步骤依赖它");
        }
    }

    private Bot enter(PlayerFlow flow, String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        Bot bot = new Bot(name, player, requestTimeout, tempo);
        bots.add(bot);
        return bot;
    }

    private Direct connect(RobotClient client, String name, BattleAssignedS2C ticket) throws RobotException {
        Direct direct = MatchSupport.connect(client, name, ticket, battleIds, requestTimeout);
        closeables.add(direct);
        return direct;
    }

    /**
     * 收尾（成功与失败都走；幂等）：还连着的 A、B 各发一条 148("")（取消当前票，尽力而为——1 号配置的 1V1 队列全服共享，留下的票会把下一遍的
     * A 凑走），然后三人各发 LeaveGame、断开，最后关掉直连。
     */
    private void leaveAll() {
        for (Bot bot : queuers) {
            if (bot.connection().isOpen()) {
                try {
                    MatchSupport.cancel(bot, ids, "");
                } catch (RobotException | RuntimeException ignored) {
                    // 尽力而为：断线之后凑单也会把离线成员的票删掉
                }
            }
        }
        for (Bot bot : bots) {
            try {
                if (bot.connection().isOpen()) {
                    bot.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
                }
            } catch (RobotException | RuntimeException ignored) {
                // 发不出去就直接断开
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
