package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.CreateBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.BattleSupport.LobbyBot;
import com.game.table.CommonErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * battle 节点端到端（批次 6.2，battle-node-spec §13.8「场景 battle」；基线 battle-smoke 去掉 match 的部分）。三个新号 A / B / C 登录进场，
 * 经 xm-battle 的 dev 接口（{@link BattleAdminClient}，代替 6.4 的 match）建房 / 销毁 / 补签 / 登记观众，按客户端契约核对：
 * <ol>
 *   <li>第 2 步 大厅上发战斗上行（149）→ gate 推 23 {1003}、不断连；</li>
 *   <li>第 3–10 步 PVE：建房受理 → 大厅先 177 后 143（O1 / R5）→ 凭票直连、握手应答是首帧（R1）、补拉 140 → 非法行动 1005 →
 *       合法行动当场结算、139 先于应答（R2）→ 同票重连顶替旧连接、旧连接不收帧（O7）→ 挂机翻转结算、2 s 节奏打到 150、FIN（O3 / O4 / R3），
 *       大厅上没有 150（6.3 才有）→ 终局后旧票被名单拒绝；</li>
 *   <li>第 11、13–15 步 PVP：两人都交才结算、只一人交时 6 s 超时结算、视角裁剪（对手冷却清空、self_items 只给本人）→ 补签与开局票逐字节相同、
 *       非成员 1005 → 幂等建房不重推 → 销毁后参战者只见 FIN（O6）；</li>
 *   <li>第 12 步 强制平局：期限 8 s，6 s 一条 139，到期 150 DRAW（无奖励）后 FIN，之后再没有 139（O5）；</li>
 *   <li>第 16 步 观战：大厅 177（role = 2）→ 握手应答之后 161（O2）→ 每回合 158、收尾 166 FINISHED 后 FIN → 165 应答后 FIN 且没有 166（O8）
 *       → dev 清退推 166 REMOVED 后 FIN；</li>
 *   <li>第 17 步 xm-battle 指标增长（建房 ok、握手 ok、直连推送）。</li>
 * </ol>
 * {@code --expect-dev deny}（xm-battle 以 prod 运行）：dev 接口必须回 403，场景只跑第 2 步。
 *
 * <p>直连上同号请求过节拍（{@link BattleSupport.Direct}），不会撞上直连面每秒 3 条的限频。battle_id 由 robot 随机生成（非 0 正数）。
 * 各阶段独立：前一阶段中断时记失败并继续后面的阶段（每阶段都新建房间）。
 */
public final class BattleScenario {

    static final String REF = "battle-node-spec §13.8";
    private static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final String NOT_IN_ROSTER = "battle not found or player not in this battle";
    /** 开局整场期限（远长于场景用时；房间收尾后由 dev 销毁或自然结束）。 */
    static final Duration LONG_DEADLINE = Duration.ofMinutes(5);
    /** 强制平局用的短期限（§13.8 第 12 步）。 */
    static final Duration SHORT_DEADLINE = Duration.ofSeconds(8);
    /** 第一回合与手动回合的窗口（§4.4.1）。 */
    static final long ROUND_MS = 6000;
    /** 挂机打完一局的上限（两只怪、2 s 节奏，留足余量）。 */
    static final Duration AUTO_FINISH_TIMEOUT = Duration.ofSeconds(60);
    /** 等「不该有的帧」的静默窗口。 */
    static final Duration SILENCE = Duration.ofMillis(300);
    /** 幂等建房之后确认没有重推 177 / 143 的窗口（§13.8 第 15 步）。 */
    static final Duration NO_REPUSH_WINDOW = Duration.ofSeconds(2);

    private final RobotClient client;
    private final PlayerFlow flow;
    private final BattleIds ids;
    private final BattleAdminClient admin;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final boolean expectDevAllowed;
    private final Duration requestTimeout;
    private final CheckReport report = new CheckReport();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    public BattleScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, BattleAdminClient admin, String accountPrefix,
                          String runTag, boolean expectDevAllowed, Duration requestTimeout) {
        this.client = client;
        this.flow = flow;
        this.ids = BattleIds.resolve(registry);
        this.admin = admin;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.expectDevAllowed = expectDevAllowed;
        this.requestTimeout = requestTimeout;
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "bt" + runTag + "_" + suffix;
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
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结论
                }
            }
        }
        return report;
    }

    private void runChecks() throws RobotException {
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-battle 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        // ---- 第 1 步：登录进场 ----
        LobbyBot a = enter("A", accountA);
        report.note("A=" + BattleSupport.uid(a.id()) + " battle-admin=" + admin.baseUrl());

        // ---- 第 2 步：gate 永久拒绝战斗上行 ----
        lobbyRejectsBattleUplink(a);

        if (!expectDevAllowed) {
            BattleAdminClient.HttpResult denied = admin.post(BattleAdminClient.CREATE,
                    BattleFixtures.pve(BattleFixtures.newBattleId(random), deadline(LONG_DEADLINE), BattleFixtures.hero(a.id(), "A", 3)));
            report.check(denied.status() == 403, "dev 接口在 prod 运行模式下回 403（--expect-dev deny）",
                    "status=" + denied.status() + " " + denied.text(), "battle-node-spec §7.12");
            return;
        }

        LobbyBot b = enter("B", accountB);
        LobbyBot c = enter("C", accountC);
        report.note("B=" + BattleSupport.uid(b.id()) + " C=" + BattleSupport.uid(c.id()));
        String metricsBefore = admin.scrapeMetrics();

        phase("PVE（第 3–10 步）", () -> pve(a));
        phase("PVP（第 11、13–15 步）", () -> pvp(a, b, c));
        phase("强制平局（第 12 步）", () -> forcedDraw(a, b));
        phase("观战（第 16 步）", () -> spectate(a, c));

        // ---- 第 17 步：指标 ----
        String metricsAfter = admin.scrapeMetrics();
        metricGrew(metricsBefore, metricsAfter, "xm_battle_room_creates_total", "result=\"ok\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_handshakes_total", "result=\"ok\"");
        metricGrew(metricsBefore, metricsAfter, "xm_battle_pushes_total", "route=\"direct\"");
    }

    // ---------------------------------------------------------------- 第 2 步

    private void lobbyRejectsBattleUplink(LobbyBot a) throws RobotException {
        int mark = a.mark();
        long requestId = a.connection().send(ids.submitAction(), SubmitBattleActionRequest.newBuilder().setBattleId(1).build());
        Optional<Received> tip = a.connection().await(mark, r -> BattleSupport.lobbyTip(r, ids.sendTip()) >= 0, requestTimeout);
        report.check(tip.isPresent() && BattleSupport.lobbyTip(tip.get(), ids.sendTip()) == TIP_SERVICE_UNAVAILABLE,
                "第 2 步 大厅上发 149 → gate 推 23 {1003}", tip.map(r -> "tip=" + BattleSupport.lobbyTip(r, ids.sendTip()))
                        .orElse("没有收到 23" + a.connection().describeSince(mark)), "battle-node-spec §3.7");
        flow.listSkills(a.player);
        boolean noReply = a.connection().inbox().snapshot(mark).stream()
                .noneMatch(r -> r.messageId() == ids.submitAction() && r.requestId() == requestId);
        report.check(a.connection().isOpen() && noReply, "第 2 步 拒绝后不断连、不回 149 的应答，正常请求（ListSkills）照常应答",
                "open=" + a.connection().isOpen() + " 149 应答=" + !noReply, "battle-node-spec §3.7");
    }

    // ---------------------------------------------------------------- PVE

    private void pve(LobbyBot a) throws RobotException {
        long battleId = BattleFixtures.newBattleId(random);
        long deadline = deadline(LONG_DEADLINE);
        int lobbyMark = a.mark();
        long beforeCreate = System.currentTimeMillis();
        BattleAdminClient.CreateOutcome created = admin.create(BattleFixtures.pve(battleId, deadline, BattleFixtures.hero(a.id(), "A", 3)));
        long afterCreate = System.currentTimeMillis();
        report.check(created.ok() && created.response().getBattleId() == battleId, "第 3 步 dev 建 PVE 房：ADMITTED 且无业务错误、battle_id 回填",
                created.describe() + " battle_id=" + BattleSupport.uid(created.response().getBattleId()), "battle-node-spec §4.3");
        report.note("PVE battle_id=" + BattleSupport.uid(battleId));

        // ---- 第 4 步：大厅公告 177 → 143 ----
        Received r177 = a.awaitAssigned(lobbyMark, battleId);
        Received r143 = a.awaitStart(lobbyMark, battleId);
        report.check(r177.index() < r143.index(), "第 4 步 大厅上先 177 后 143（O1 / R5）",
                "177 #" + r177.index() + " 143 #" + r143.index(), "battle-node-spec §5.8");
        BattleAssignedS2C assigned = r177.parse(BattleAssignedS2C.parser());
        checkAssigned("第 4 步 177", assigned, battleId, a.id(), deadline, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        BattleStartS2C start = r143.parse(BattleStartS2C.parser());
        BattleStateS2C startState = start.getState();
        long startDeadline = startState.getActionDeadlineMs();
        report.check(start.getBattleId() == battleId && startState.getRoundIndex() == 1
                        && startState.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING
                        && startDeadline >= beforeCreate + ROUND_MS - 500 && startDeadline <= afterCreate + ROUND_MS + 500,
                "第 4 步 143：round_index = 1、ONGOING、action_deadline_ms ≈ 建房时刻 + 6 s",
                "round=" + startState.getRoundIndex() + " outcome=" + startState.getOutcome() + " deadline-建房=" + (startDeadline - beforeCreate)
                        + " ms", "battle-node-spec §4.4");
        report.check(monsters(startState).size() == 2 && startState.getPendingActorIdsList().equals(List.of(a.id()))
                        && startState.getSelfItemsList().equals(List.of(heal(3))),
                "第 4 步 143：两只怪、待行动只有 A、self_items 是 A 自己的 3 瓶药",
                "monsters=" + monsters(startState).size() + " pending=" + startState.getPendingActorIdsList() + " self_items="
                        + startState.getSelfItemsList().size(), "battle-node-spec §5.6");

        // ---- 第 5 步：直连 + 补拉 140 ----
        Direct first = open("A#1", assigned);
        BattleDirectConnection.Handshake hs = first.handshake(assigned);
        report.check(hs.success() && hs.response().getBattleId() == battleId && hs.response().getError().isEmpty(),
                "第 5 步 握手应答是直连上的第一帧，success 且 battle_id 一致（R1）", describe(hs.response()), "battle-node-spec §3.4");
        BattleFrame stateReply = first.awaitReply(0, ids.getBattleState(), hs.stateRequestId(), requestTimeout);
        List<String> afterVerify = BattleSupport.labels(first.since(1)).subList(0, stateReply.index());
        report.check(afterVerify.equals(List.of("reply:" + ids.getBattleState())), "第 5 步 参战者握手后服务端什么也不推，第二帧就是 140 的应答",
                first.labelsSince(0), "battle-node-spec §3.4");
        BattleStateS2C state = stateReply.parse(BattleStateS2C.parser());
        List<Long> monsters = monsters(state);
        report.check(stateReply.isReply() && state.getBattleId() == battleId && state.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING
                        && actorIds(state).contains(a.id()) && monsters.size() == 2,
                "第 5 步 补拉 140：应答 id / message_id 回显，actors 含 A 与两只怪，ONGOING",
                stateReply.label() + " battle_id=" + BattleSupport.uid(state.getBattleId()) + " actors=" + actorIds(state).size(),
                "battle-node-spec §5.3");
        if (monsters.isEmpty()) {
            throw new RobotException("140 里没有怪物，无法继续 PVE");
        }

        // ---- 第 6 步：非法行动 ----
        SubmitBattleActionResponse rejected = first.callParsed(ids.submitAction(), submit(battleId, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_NONE).build()), SubmitBattleActionResponse.parser(), requestTimeout);
        report.check(rejected.getErrorMessage().getId() == TIP_INVALID_PARAMETER, "第 6 步 Submit action_type = NONE → 应答体带 1005",
                "tip=" + rejected.getErrorMessage().getId(), "battle-engine-spec §2.5");

        // ---- 第 7 步：合法行动，当场结算 ----
        int beforeSubmit = first.mark();
        long submitAt = System.currentTimeMillis();
        BattleFrame submitReply = first.call(ids.submitAction(), submit(battleId, attack(monsters.get(0))), requestTimeout);
        Optional<BattleFrame> turn = first.await(beforeSubmit, f -> f.isPush(ids.turnResult()), Duration.ZERO);
        report.check(submitReply.isReply() && submitReply.content().getSerializedMessage().isEmpty() && turn.isPresent()
                        && turn.get().index() < submitReply.index(),
                "第 7 步 Submit ATTACK：单人房全员就绪当场结算，139 先于这条应答（R2），应答体 0 字节",
                first.labelsSince(beforeSubmit), "battle-node-spec §5.8 O3");
        if (turn.isPresent()) {
            TurnResultS2C t = turn.get().parse(TurnResultS2C.parser());
            long next = t.getState().getActionDeadlineMs();
            report.check(t.getBattleId() == battleId && t.getRoundIndex() == 1 && t.getState().getRoundIndex() == 2
                            && t.getActionOrderCount() > 0 && next >= submitAt + ROUND_MS - 500 && next <= System.currentTimeMillis() + ROUND_MS + 500,
                    "第 7 步 139：第 1 回合、action_order 非空、state 带新截止（≈ 结算时刻 + 6 s）",
                    "round=" + t.getRoundIndex() + "→" + t.getState().getRoundIndex() + " action_order=" + t.getActionOrderCount()
                            + " deadline-提交=" + (next - submitAt) + " ms", "battle-node-spec §4.4");
        }

        // ---- 第 8 步：同票重连顶替 ----
        int firstMark = first.mark();
        Direct second = open("A#2", assigned);
        BattleDirectConnection.Handshake hs2 = second.handshake(assigned);
        List<BattleFrame> firstTail = first.untilClosed(firstMark, Duration.ofSeconds(3));
        report.check(hs2.success() && hs2.response().getBattleId() == battleId && BattleSupport.onlyClosed(firstTail),
                "第 8 步 同一张票再连：新连接握手成功，旧连接被服务端关闭且没收到任何帧（O7）",
                describe(hs2.response()) + " 旧连接=" + BattleSupport.labels(firstTail), "battle-node-spec §3.4");
        second.awaitReply(0, ids.getBattleState(), hs2.stateRequestId(), requestTimeout);

        // ---- 第 9 步：挂机打完 ----
        int autoMark = second.mark();
        second.request(ids.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
        List<BattleFrame> tail = second.untilClosed(autoMark, AUTO_FINISH_TIMEOUT);
        String problem = BattleOrder.autoTailProblem(tail, ids.turnResult(), ids.battleEnd(), ids.setAutoBattle());
        report.check(problem == null, "第 9 步 挂机：翻转当场结算（139 先于 162 应答），之后约 2 s 一回合直到 150，1.5 s 内 FIN",
                problem == null ? BattleSupport.labels(tail).toString() : problem, "battle-node-spec §5.8 O3 / O4");
        Optional<BattleFrame> endFrame = tail.stream().filter(f -> f.isPush(ids.battleEnd())).findFirst();
        if (endFrame.isPresent()) {
            BattleEndS2C end = endFrame.get().parse(BattleEndS2C.parser());
            report.check(end.getBattleId() == battleId && end.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                            && end.getSettlement().getPlayerId() == a.id() && end.getSettlement().getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                            && end.getSettlement().getTotalRounds() >= 1,
                    "第 9 步 150：SIDE_A_WIN，settlement.player_id = A，total_rounds ≥ 1",
                    "outcome=" + end.getOutcome() + " settlement.player=" + BattleSupport.uid(end.getSettlement().getPlayerId()) + " rounds="
                            + end.getSettlement().getTotalRounds(), "robot/features_battle_smoke.go:82-95");
        }
        Optional<Received> lobby150 = a.findWithin(lobbyMark, ids.battleEnd(), battleId, Duration.ofMillis(500));
        report.check(lobby150.isEmpty(), "第 9 步 6.2 的大厅上不收 150（scene 结算经大厅推 150 是 6.3）", lobby150.map(r -> "大厅 #" + r.index())
                .orElse("没有"), "battle-node-spec §5.1");

        // ---- 第 10 步：终局后旧票被拒 ----
        expectRosterRejected("第 10 步 终局后旧票重新握手", "A#3", assigned);
        IssueBattleTicketResponse reissue = admin.issueTicket(battleId, a.id());
        report.check(reissue.getErrorMessage().getId() == TIP_INVALID_PARAMETER && !reissue.hasAssignment(),
                "第 10 步 终局后补签 → 1005、无 assignment（客户端据此判 BattleGone）", "tip=" + reissue.getErrorMessage().getId(),
                "battle-node-spec §2.6");
    }

    // ---------------------------------------------------------------- PVP

    private void pvp(LobbyBot a, LobbyBot b, LobbyBot c) throws RobotException {
        long battleId = BattleFixtures.newBattleId(random);
        long deadline = deadline(LONG_DEADLINE);
        int markA = a.mark();
        int markB = b.mark();
        CreateBattleRequest request = BattleFixtures.pvp(battleId, deadline, BattleFixtures.tank(a.id(), "A", 0, 2),
                BattleFixtures.tank(b.id(), "B", 1, 3));
        BattleAdminClient.CreateOutcome created = admin.create(request);
        report.check(created.ok(), "第 11 步 dev 建 PVP 1V1 房（A 在 team 0、B 在 team 1）", created.describe(), "battle-node-spec §4.3");
        report.note("PVP battle_id=" + BattleSupport.uid(battleId));
        BattleAssignedS2C ticketA = a.awaitAssigned(markA, battleId).parse(BattleAssignedS2C.parser());
        BattleAssignedS2C ticketB = b.awaitAssigned(markB, battleId).parse(BattleAssignedS2C.parser());
        a.awaitStart(markA, battleId);
        b.awaitStart(markB, battleId);
        checkAssigned("第 11 步 B 的 177", ticketB, battleId, b.id(), deadline, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        // 第 14 / 15 步（补签、幂等建房）放到第 11 步的回合断言之后：第 1 回合的 6 s 计时从建房开始，
        // 把它们（含 2 s 的不重推观察窗）塞在前面会让「A 先交、B 再交都落在第 1 回合」的断言在慢机器上偶发失败（评审意见）

        // ---- 第 11 步：两人都交才结算 ----
        Direct da = open("A", ticketA);
        Direct db = open("B", ticketB);
        BattleDirectConnection.Handshake hsA = da.handshake(ticketA);
        BattleDirectConnection.Handshake hsB = db.handshake(ticketB);
        da.awaitReply(0, ids.getBattleState(), hsA.stateRequestId(), requestTimeout);
        db.awaitReply(0, ids.getBattleState(), hsB.stateRequestId(), requestTimeout);
        report.check(hsA.success() && hsB.success(), "第 11 步 A、B 凭各自的票直连握手成功", describe(hsA.response()) + " / " + describe(hsB.response()),
                "battle-node-spec §3.4");

        int aBefore = da.mark();
        int bBefore = db.mark();
        BattleFrame aReply = da.call(ids.submitAction(), submit(battleId, attack(b.id())), requestTimeout);
        Optional<BattleFrame> early = da.await(aBefore, f -> f.isPush(ids.turnResult()), SILENCE);
        report.check(aReply.isReply() && aReply.content().getSerializedMessage().isEmpty() && early.isEmpty()
                        && db.since(bBefore).isEmpty(),
                "第 11 步 A 先交：应答体为空，B 没交之前不结算（两边都没有 139）", da.labelsSince(aBefore) + " / B " + db.labelsSince(bBefore),
                "battle-node-spec §4.4.2");
        int bSubmit = db.mark();
        BattleFrame bReply = db.call(ids.submitAction(), submit(battleId, attack(a.id())), requestTimeout);
        Optional<BattleFrame> bTurn = db.await(bSubmit, f -> f.isPush(ids.turnResult()), Duration.ZERO);
        BattleFrame aTurn = da.awaitPush(aBefore, ids.turnResult(), requestTimeout);
        report.check(bReply.isReply() && bTurn.isPresent() && bTurn.get().index() < bReply.index(),
                "第 11 步 B 再交 → 全员就绪当场结算：两边各一条 139，B 的 139 先于 B 的应答（R2）",
                "B " + db.labelsSince(bSubmit) + " / A " + da.labelsSince(aBefore), "battle-node-spec §5.8 O3");
        TurnResultS2C turn1 = aTurn.parse(TurnResultS2C.parser());
        report.check(turn1.getRoundIndex() == 1 && turn1.getState().getRoundIndex() == 2, "第 11 步 139 是第 1 回合，之后进第 2 回合",
                "round=" + turn1.getRoundIndex() + "→" + turn1.getState().getRoundIndex(), "battle-node-spec §4.4.3");

        // 只有 A 出手 → 约 6 s 后超时结算
        int aRound2 = da.mark();
        int bRound2 = db.mark();
        BattleFrame aReply2 = da.call(ids.submitAction(), submit(battleId, attack(b.id())), requestTimeout);
        BattleFrame aTurn2 = da.awaitPush(aRound2, ids.turnResult(), Duration.ofMillis(ROUND_MS + 3000));
        BattleFrame bTurn2 = db.awaitPush(bRound2, ids.turnResult(), Duration.ofSeconds(2));
        long gap = BattleSupport.millisBetween(aTurn, aTurn2);
        report.check(aReply2.isReply() && aTurn2.index() > aReply2.index() && gap >= ROUND_MS - 500 && bTurn2 != null,
                "第 11 步 下一回合只有 A 出手：不当场结算，约 6 s 后超时结算（两边都收到 139，间隔 ≥ 5.5 s）",
                "间隔 " + gap + " ms，A " + da.labelsSince(aRound2), "battle-node-spec §4.4.2");

        // 视角裁剪：A 的 140
        BattleStateS2C stateA = da.callParsed(ids.getBattleState(), GetBattleStateRequest.newBuilder().setBattleId(battleId).build(),
                BattleStateS2C.parser(), requestTimeout);
        BattleStateS2C stateB = db.callParsed(ids.getBattleState(), GetBattleStateRequest.newBuilder().setBattleId(battleId).build(),
                BattleStateS2C.parser(), requestTimeout);
        Optional<BattleActorState> bInA = stateA.getActorsList().stream().filter(x -> x.getActorId() == b.id()).findFirst();
        report.check(bInA.isPresent() && bInA.get().getSkillCooldownRoundsMap().isEmpty() && stateA.getSelfItemsList().equals(List.of(heal(2)))
                        && stateB.getSelfItemsList().equals(List.of(heal(3))),
                "第 11 步 视角裁剪：A 的 140 里 B 的 skill_cooldown_rounds 为空，self_items 只有 A 的（B 的 140 只有 B 的）",
                "A.self_items=" + stateA.getSelfItemsList().size() + " B.self_items=" + stateB.getSelfItemsList().size(), "battle-node-spec §5.6");

        // ---- 第 14 步：补签（房间仍活着）----
        IssueBattleTicketResponse reA = admin.issueTicket(battleId, a.id());
        report.check(!reA.hasErrorMessage() && reA.getAssignment().getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT
                        && reA.getAssignment().toByteString().equals(ticketA.toByteString()),
                "第 14 步 活着的房间给 A 补签：role = 1，与开局 177 逐字节相同（HMAC 确定）",
                "tip=" + reA.getErrorMessage().getId() + " 相同=" + reA.getAssignment().toByteString().equals(ticketA.toByteString()),
                "battle-node-spec §2.4");
        IssueBattleTicketResponse reC = admin.issueTicket(battleId, c.id());
        report.check(reC.getErrorMessage().getId() == TIP_INVALID_PARAMETER && !reC.hasAssignment(), "第 14 步 给非成员 C 补签 → 1005、无 assignment",
                "tip=" + reC.getErrorMessage().getId() + " assignment=" + reC.hasAssignment(), "battle-node-spec §2.6");

        // ---- 第 15 步：幂等建房不重推 ----
        int againA = a.mark();
        int againB = b.mark();
        BattleAdminClient.CreateOutcome again = admin.create(request);
        report.check(again.ok() && again.response().getBattleId() == battleId, "第 15 步 同一个 battle_id 再建：ADMITTED 且无错误",
                again.describe(), "battle-node-spec §4.3.3");
        Optional<Received> repushA = a.findWithin(againA, ids.battleAssigned(), battleId, NO_REPUSH_WINDOW);
        boolean repush = repushA.isPresent()
                || a.connection().inbox().snapshot(againA).stream().anyMatch(r -> a.isLobbyPush(r, ids.battleStart(), battleId))
                || b.connection().inbox().snapshot(againB).stream().anyMatch(r -> b.isLobbyPush(r, ids.battleAssigned(), battleId)
                        || b.isLobbyPush(r, ids.battleStart(), battleId));
        report.check(!repush, "第 15 步 幂等命中零副作用：2 s 内 A / B 都没有再收到 177 / 143", repush ? "重推了" : "没有", "battle-node-spec §4.3.3");

        // ---- 第 13 步：销毁 ----
        int aDestroy = da.mark();
        int bDestroy = db.mark();
        admin.destroy(battleId, "robot_battle");
        List<BattleFrame> aTail = da.untilClosed(aDestroy, Duration.ofSeconds(3));
        List<BattleFrame> bTail = db.untilClosed(bDestroy, Duration.ofSeconds(3));
        String finOnly = BattleOrder.exactly(aTail, List.of(BattleOrder.FIN));
        String finOnlyB = BattleOrder.exactly(bTail, List.of(BattleOrder.FIN));
        report.check(finOnly == null && finOnlyB == null, "第 13 步 dev destroy：参战者只看到 FIN，没有 150、没有终局 139（O6）",
                "A " + BattleSupport.labels(aTail) + " / B " + BattleSupport.labels(bTail), "battle-node-spec §5.8 O6");
        expectRosterRejected("第 13 步 销毁后原票重新握手", "A#late", ticketA);
    }

    // ---------------------------------------------------------------- 强制平局

    private void forcedDraw(LobbyBot a, LobbyBot b) throws RobotException {
        long battleId = BattleFixtures.newBattleId(random);
        int markA = a.mark();
        int markB = b.mark();
        long t0 = System.currentTimeMillis();
        long deadline = t0 + SHORT_DEADLINE.toMillis();
        BattleAdminClient.CreateOutcome created = admin.create(BattleFixtures.pvp(battleId, deadline, BattleFixtures.tank(a.id(), "A", 0, 0),
                BattleFixtures.tank(b.id(), "B", 1, 0)));
        report.check(created.ok(), "第 12 步 dev 建 1V1 房，deadline_ms = now + 8 s", created.describe(), "battle-node-spec §4.5");
        report.note("强制平局 battle_id=" + BattleSupport.uid(battleId));
        BattleAssignedS2C ticketA = a.awaitAssigned(markA, battleId).parse(BattleAssignedS2C.parser());
        BattleAssignedS2C ticketB = b.awaitAssigned(markB, battleId).parse(BattleAssignedS2C.parser());
        report.check(ticketA.getExpireAtMs() == deadline && ticketB.getExpireAtMs() == deadline, "第 12 步 票据期限 = 房间期限",
                "expire-deadline=" + (ticketA.getExpireAtMs() - deadline), "battle-node-spec §2.4");
        Direct da = open("A", ticketA);
        Direct db = open("B", ticketB);
        BattleDirectConnection.Handshake hsA = da.handshake(ticketA);
        BattleDirectConnection.Handshake hsB = db.handshake(ticketB);
        da.awaitReply(0, ids.getBattleState(), hsA.stateRequestId(), requestTimeout);
        db.awaitReply(0, ids.getBattleState(), hsB.stateRequestId(), requestTimeout);

        int aMark = da.mark();
        int bMark = db.mark();
        BattleFrame turnA = da.awaitPush(aMark, ids.turnResult(), Duration.ofSeconds(9));
        long turnAt = System.currentTimeMillis();
        BattleFrame turnB = db.awaitPush(bMark, ids.turnResult(), Duration.ofSeconds(2));
        report.check(turnAt - t0 >= ROUND_MS - 500 && turnAt - t0 <= SHORT_DEADLINE.toMillis() - 100,
                "第 12 步 没人出手：约 6 s 时窗口到期结算一条 139", "建房后 " + (turnAt - t0) + " ms", "battle-node-spec §5.8 O4");
        List<BattleFrame> tailA = da.untilClosed(turnA.index() + 1, Duration.ofSeconds(5));
        List<BattleFrame> tailB = db.untilClosed(turnB.index() + 1, Duration.ofSeconds(3));
        long closedAt = System.currentTimeMillis();
        String expectEnd = "push:" + ids.battleEnd();
        String pa = BattleOrder.exactly(tailA, List.of(expectEnd, BattleOrder.FIN));
        String pb = BattleOrder.exactly(tailB, List.of(expectEnd, BattleOrder.FIN));
        report.check(pa == null && pb == null && closedAt >= deadline - 50,
                "第 12 步 期限到：两边 150 → FIN，期限之后再没有 139（O5）", "A " + BattleSupport.labels(tailA) + " / B " + BattleSupport.labels(tailB)
                        + " 收尾-期限=" + (closedAt - deadline) + " ms", "battle-node-spec §5.8 O5");
        for (List<BattleFrame> tail : List.of(tailA, tailB)) {
            Optional<BattleFrame> endFrame = tail.stream().filter(f -> f.isPush(ids.battleEnd())).findFirst();
            if (endFrame.isEmpty()) {
                continue;
            }
            BattleEndS2C end = endFrame.get().parse(BattleEndS2C.parser());
            report.check(end.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_DRAW && end.getSettlement().getOutcome() == eBattleOutcome.BATTLE_OUTCOME_DRAW
                            && end.getSettlement().getExpGain() == 0 && end.getSettlement().getGoldGain() == 0,
                    "第 12 步 150 DRAW，经验、金币为 0（player " + BattleSupport.uid(end.getSettlement().getPlayerId()) + "）",
                    "outcome=" + end.getOutcome() + " exp=" + end.getSettlement().getExpGain() + " gold=" + end.getSettlement().getGoldGain(),
                    "battle-node-spec §4.5");
        }
    }

    // ---------------------------------------------------------------- 观战

    private void spectate(LobbyBot a, LobbyBot c) throws RobotException {
        long battleId = BattleFixtures.newBattleId(random);
        int markA = a.mark();
        BattleAdminClient.CreateOutcome created = admin.create(BattleFixtures.pve(battleId, deadline(LONG_DEADLINE),
                BattleFixtures.hero(a.id(), "A", 0)));
        report.check(created.ok(), "第 16 步 dev 建 PVE 房（A 不开挂机）", created.describe(), "battle-node-spec §5.5");
        report.note("观战 battle_id=" + BattleSupport.uid(battleId));
        BattleAssignedS2C ticketA = a.awaitAssigned(markA, battleId).parse(BattleAssignedS2C.parser());
        Direct da = open("A", ticketA);
        BattleDirectConnection.Handshake hsA = da.handshake(ticketA);
        da.awaitReply(0, ids.getBattleState(), hsA.stateRequestId(), requestTimeout);

        BattleAssignedS2C ticketC = addObserver("第 16 步", battleId, c);
        Direct dc = open("C", ticketC);
        BattleDirectConnection.Handshake hsC = dc.handshake(ticketC);
        BattleFrame first161 = dc.awaitPush(0, ids.spectateState(), requestTimeout);
        report.check(hsC.success() && hsC.stateRequestId() == 0 && first161.index() == 1,
                "第 16 步 观众直连：握手应答之后紧跟 161（O2），观众不自动补拉 140", dc.labelsSince(0), "battle-node-spec §5.8 O2");
        SpectateStateS2C spectate = first161.parse(SpectateStateS2C.parser());
        report.check(spectate.getObserverCount() == 1 && spectate.getState().getBattleId() == battleId
                        && spectate.getState().getSelfItemsCount() == 0 && allCooldownsEmpty(spectate.getState()),
                "第 16 步 161：observer_count = 1，所有 actor 的冷却为空、没有 self_items", "observer_count=" + spectate.getObserverCount(),
                "battle-node-spec §5.6");
        SetAutoBattleResponse denied = dc.callParsed(ids.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true)
                .build(), SetAutoBattleResponse.parser(), requestTimeout);
        report.check(denied.getErrorMessage().getId() == TIP_INVALID_PARAMETER, "第 16 步 观众不能切挂机 → 1005", "tip=" + denied.getErrorMessage().getId(),
                "battle-node-spec §5.4");

        int aMark = da.mark();
        int cMark = dc.mark();
        da.request(ids.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(true).build());
        List<BattleFrame> aTail = da.untilClosed(aMark, AUTO_FINISH_TIMEOUT);
        List<BattleFrame> cTail = dc.untilClosed(cMark, Duration.ofSeconds(5));
        long turns = BattleOrder.count(aTail, "push:" + ids.turnResult());
        String problem = BattleOrder.spectatorTailProblem(cTail, turns, ids.spectateTurnResult(), ids.spectateEnd());
        report.check(problem == null && turns >= 1, "第 16 步 A 开挂机：C 每回合一条 158（与 A 的 139 同样多），然后 166，然后 FIN",
                problem == null ? BattleSupport.labels(cTail).toString() : problem, "battle-node-spec §5.5");
        boolean redacted = true;
        for (BattleFrame f : cTail) {
            if (f.isPush(ids.spectateTurnResult())) {
                TurnResultS2C t = f.parse(TurnResultS2C.parser());
                redacted &= t.getState().getSelfItemsCount() == 0 && allCooldownsEmpty(t.getState());
            }
        }
        report.check(redacted, "第 16 步 158 是观众版：所有 actor 的冷却为空、没有 self_items", redacted ? "是" : "有未裁剪的", "battle-node-spec §5.6");
        Optional<BattleFrame> end166 = cTail.stream().filter(f -> f.isPush(ids.spectateEnd())).findFirst();
        if (end166.isPresent()) {
            SpectateEndS2C end = end166.get().parse(SpectateEndS2C.parser());
            report.check(end.getReason() == eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED && end.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                            && end.getBattleId() == battleId, "第 16 步 166 {FINISHED, SIDE_A_WIN}",
                    "reason=" + end.getReason() + " outcome=" + end.getOutcome(), "battle-node-spec §4.10");
        }

        // 另起一局：165 → 应答 → FIN，没有 166（O8）
        long second = BattleFixtures.newBattleId(random);
        BattleAdminClient.CreateOutcome created2 = admin.create(BattleFixtures.pve(second, deadline(LONG_DEADLINE), BattleFixtures.hero(a.id(), "A", 0)));
        report.check(created2.ok(), "第 16 步 另建一间 PVE 房给 165 / 清退用", created2.describe(), "battle-node-spec §5.5");
        BattleAssignedS2C stopTicket = addObserver("第 16 步（165）", second, c);
        Direct stopper = open("C#stop", stopTicket);
        stopper.handshake(stopTicket);
        stopper.awaitPush(0, ids.spectateState(), requestTimeout);
        int stopMark = stopper.mark();
        long stopId = stopper.request(ids.stopWatch(), StopWatchBattleRequest.newBuilder().setBattleId(second).build());
        List<BattleFrame> stopTail = stopper.untilClosed(stopMark, Duration.ofSeconds(3));
        String stopProblem = BattleOrder.exactly(stopTail, List.of("reply:" + ids.stopWatch(), BattleOrder.FIN));
        boolean stopOk = stopProblem == null && stopTail.get(0).requestId() == stopId
                && stopTail.get(0).parseOrNull(StopWatchBattleResponse.parser()) != null
                && !stopTail.get(0).parseOrNull(StopWatchBattleResponse.parser()).hasErrorMessage();
        report.check(stopOk, "第 16 步 C 发 165 → 成功应答 → FIN，没有 166（O8）", stopProblem == null ? BattleSupport.labels(stopTail).toString() : stopProblem,
                "battle-node-spec §5.8 O8");

        // 再登记 → 直连 → dev 清退：166 {REMOVED, ONGOING} → FIN
        BattleAssignedS2C removeTicket = addObserver("第 16 步（清退）", second, c);
        Direct removed = open("C#removed", removeTicket);
        removed.handshake(removeTicket);
        removed.awaitPush(0, ids.spectateState(), requestTimeout);
        int removeMark = removed.mark();
        admin.removeObserver(second, c.id(), "robot_battle");
        List<BattleFrame> removeTail = removed.untilClosed(removeMark, Duration.ofSeconds(3));
        String removeProblem = BattleOrder.exactly(removeTail, List.of("push:" + ids.spectateEnd(), BattleOrder.FIN));
        SpectateEndS2C kicked = removeProblem == null ? removeTail.get(0).parse(SpectateEndS2C.parser()) : null;
        report.check(kicked != null && kicked.getReason() == eSpectateEndReason.SPECTATE_END_REMOVED
                        && kicked.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING,
                "第 16 步 dev 清退观众：166 {REMOVED, ONGOING} → FIN", removeProblem == null ? "reason=" + kicked.getReason() : removeProblem,
                "battle-node-spec §5.5");
        admin.destroy(second, "robot_battle");
    }

    /** dev 登记观众并等大厅上的 177（role = 2）。 */
    private BattleAssignedS2C addObserver(String step, long battleId, LobbyBot observer) throws RobotException {
        int mark = observer.mark();
        AddObserverResponse added = admin.addObserver(battleId, observer.id(), observer.name);
        report.check(!added.hasErrorMessage(), step + " dev 登记观众 " + observer.name, "tip=" + added.getErrorMessage().getId(), "battle-node-spec §5.5");
        BattleAssignedS2C ticket = observer.awaitAssigned(mark, battleId).parse(BattleAssignedS2C.parser());
        report.check(ticket.getRole() == eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER && BattleFixtures.isLowerHex64(ticket.getTokenSignature()),
                step + " 观众在大厅收到 177（role = 2）", "role=" + ticket.getRole(), "battle-node-spec §5.5");
        return ticket;
    }

    // ---------------------------------------------------------------- 工具

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

    private LobbyBot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        return new LobbyBot(name, player, ids, requestTimeout);
    }

    private Direct open(String name, BattleAssignedS2C assigned) throws RobotException {
        Direct direct = Direct.open(client, name, assigned, ids);
        closeables.add(direct);
        return direct;
    }

    /** 新连接上用旧票握手：名单拒绝串逐字一致，然后 FIN（R4）。 */
    private void expectRosterRejected(String step, String name, BattleAssignedS2C ticket) throws RobotException {
        Direct late = open(name, ticket);
        BattleTokenVerifyResponse response = late.verify(ticket.getTokenPayload(), ticket.getTokenSignature());
        List<BattleFrame> tail = late.untilClosed(1, Duration.ofSeconds(2));
        report.check(!response.getSuccess() && response.getError().equals(NOT_IN_ROSTER) && response.getBattleId() == 0
                        && BattleOrder.exactly(tail, List.of(BattleOrder.FIN)) == null,
                step + " → 「" + NOT_IN_ROSTER + "」，battle_id 不在线上，然后 FIN", describe(response) + " 之后 " + BattleSupport.labels(tail),
                "battle-node-spec §3.4");
    }

    private void checkAssigned(String step, BattleAssignedS2C assigned, long battleId, long playerId, long deadline, eBattleTicketRole role)
            throws RobotException {
        BattleTicketPayload payload;
        try {
            payload = BattleTicketPayload.parseFrom(assigned.getTokenPayload());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            report.fail(step + " token_payload 能解析为 BattleTicketPayload", e.getMessage(), "battle-node-spec §2.1");
            return;
        }
        report.check(assigned.getBattleId() == battleId && assigned.getRole() == role && !assigned.getHost().isEmpty()
                        && assigned.getPort() > 0 && assigned.getPort() <= 65535 && assigned.getExpireAtMs() == deadline
                        && BattleFixtures.isLowerHex64(assigned.getTokenSignature()),
                step + "：battle_id 一致、role = " + role.getNumber() + "、通告地址非空、expire_at_ms = 期限、token_signature 是 64 位小写 hex",
                "endpoint=" + assigned.getHost() + ":" + assigned.getPort() + " expire-deadline=" + (assigned.getExpireAtMs() - deadline),
                "battle-node-spec §2.3");
        report.check(payload.getBattleId() == battleId && payload.getPlayerId() == playerId && payload.getRole() == role
                        && payload.getExpireAtMs() == deadline && payload.getBattleNodeId() != 0 && !payload.getBattleInstanceId().isEmpty(),
                step + " 票据 payload：battle_id / player_id / role / expire_at_ms 对上，签发节点号与实例 id 非空",
                "node=" + payload.getBattleNodeId() + " instance=" + payload.getBattleInstanceId(), "battle-node-spec §2.1");
    }

    private void metricGrew(String before, String after, String metric, String label) {
        double b = AdminClient.sum(before, metric, label);
        double a = AdminClient.sum(after, metric, label);
        report.check(a > b, "第 17 步 指标 " + metric + "{" + label + "} 有增长", b + " → " + a, "battle-node-spec §9");
    }

    static List<Long> monsters(BattleStateS2C state) {
        return state.getActorsList().stream().filter(x -> x.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                .map(BattleActorState::getActorId).toList();
    }

    static List<Long> actorIds(BattleStateS2C state) {
        return state.getActorsList().stream().map(BattleActorState::getActorId).toList();
    }

    static boolean allCooldownsEmpty(BattleStateS2C state) {
        return state.getActorsList().stream().allMatch(x -> x.getSkillCooldownRoundsMap().isEmpty());
    }

    static BattleItemEntry heal(int count) {
        return BattleItemEntry.newBuilder().setItemTableId(BattleFixtures.ITEM_HEAL).setCount(count).build();
    }

    static SubmitBattleActionRequest submit(long battleId, BattleAction action) {
        return SubmitBattleActionRequest.newBuilder().setBattleId(battleId).setAction(action).build();
    }

    static BattleAction attack(long target) {
        return BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(target).build();
    }

    private static long deadline(Duration fromNow) {
        return System.currentTimeMillis() + fromNow.toMillis();
    }

    static String describe(BattleTokenVerifyResponse response) {
        return response.getSuccess() ? "success battle_id=" + BattleSupport.uid(response.getBattleId())
                : "fail「" + response.getError() + "」battle_id=" + BattleSupport.uid(response.getBattleId());
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
