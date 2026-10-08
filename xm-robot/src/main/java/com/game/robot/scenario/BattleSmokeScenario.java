package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.RequestBattleTicketRequest;
import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.QueueState;
import com.game.proto.match.RespondChallengeRequest;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.WatchBattleResponse;
import com.game.robot.client.AdminClient;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSmokeChecks.InBattleWatch;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.MatchSupport.AutoRequest;
import com.game.robot.scenario.MatchSupport.Bot;
import com.game.robot.scenario.MatchSupport.Finished;
import com.game.robot.scenario.MatchSupport.JoinAttempts;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.MatchSupport.Tempo;
import com.game.robot.scenario.SpectateSteps.Ended;
import com.game.robot.scenario.SpectateSteps.Stopped;
import com.game.robot.scenario.SpectateSteps.Timing;
import com.game.robot.scenario.SpectateSteps.Watching;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * 匹配与观战端到端（批次 6.4 match-spec §15.5「场景 battle-smoke」+ 批次 6.5 spectate-spec §10.7 的观战段；基线
 * {@code robot/battle_smoke_scenario.go}，加上排队语义、补签、1V1 评分、切磋）。全程经 gate → xm-match，评分与指标经 xm-match 管理端口
 * （{@link MatchAdminClient}）。
 *
 * <p><b>观战段</b>（S0–S11 在匹配各段之前跑，用自己的四个新号 SA 参战 / SB 指定观战 / SC 列表与随机观战 / SD 第二局参战，
 * 账号后缀 {@code _w / _x / _y / _z}；判据集中在 {@link SpectateSteps}）：
 * <ul>
 *   <li>S0 SC 发 164 {limit = 0}：条数 ≤ 20、{@code created_at_ms} 不增、都没过期；随后<b>预清理</b>：列表非空就发 163(0)——每次失败都会懒剔除
 *       已结束的残留场——直到列表为空（上限 30 轮；挑中别人的活战斗就 165 退出并停止清理）。残留场会让 S8 的随机观战一再扑空；</li>
 *   <li>S1 SA 排 PVE_SOLO → 战斗 X → 直连、<b>不开自动</b>（屏障：X 靠 6 s 的回合超时活着）；SA 发 163(X) → 16014（持票）；</li>
 *   <li>S2 SC 发 164：X 在列表里，mode / battle_config_id / player_names / created_at_ms 逐字段；{limit = 1} 至多一条；</li>
 *   <li>S3 SB 发 163(X)：成功只带 battle_id → 大厅 177（role = 2，期限同 SA 的票）→ 直连：握手应答之后紧跟 161 {observer_count = 1}；</li>
 *   <li>S4 SB 再发 163(X)（重看）：重推的 177 经<b>直连</b>到达且与第一条逐字节相同，随后再一条 161；大厅没有新的 177，直连上没有 166；</li>
 *   <li>S5 SB 发 179(X)：assignment 与 177 逐字节相同；</li>
 *   <li>S6 SB 排一条凑不成局的 1V1：受理、直连上 1 s 内没有 166；163(0) → 16014；148 之后 153 → NOT_QUEUED；</li>
 *   <li>S7 SC 发 163(不存在的 id) → 16018「该战斗不存在或已结束」；</li>
 *   <li>S8 SC 发 163(0)：成功（16017 按过渡态重试 10 s）→ 177 → 直连 161（挑中 X 时 observer_count = 2）→ 165：应答 → FIN、没有 166；</li>
 *   <li>S9 放行屏障，SA 开自动打到 150；SB 的直连上 ≥ 1 条 158 → 166 {FINISHED, 同一个 outcome} → FIN；SB 的大厅上没有任何战斗帧；</li>
 *   <li>S10 SB 再发 163(X) → 16018；SC 的 164 里没有 X；</li>
 *   <li>S11 开局清退：SD 开战斗 Z，SB 观战 Z 后去排 PVE_SOLO → Z 的直连上 166 {ONGOING, REMOVED} → FIN，大厅收到新战斗 W 的 177 / 143；
 *       之后两人都开自动打完；</li>
 *   <li>S12（插在第 9 步的切磋局里，A、B 开自动之前）A 发 163(0) → 16015；上一局 1V1 的 ready 票还在时先回 16014，按过渡态重试到
 *       「第 8 步收到 177 + 62 s」；</li>
 *   <li>S13（场景末尾）xm-match 的观战指标增量。</li>
 * </ul>
 * S1 收到 177 到 S8 结束合计预算 60 s，超出记 {@code step=s<n>-budget}；S9 之前 X 就结束记 {@code step=s<n>-x-ended-early}。
 *
 * <p><b>匹配段</b>（三个新号 A / B / C，步骤号沿 6.4；原第 10 步——6.4 的 163 / 164 临时应答——随 M22 关闭删除）：
 * <ol>
 *   <li>第 1 步 登录；A 的 153 → NOT_QUEUED，两个秒数都是 0；</li>
 *   <li>第 2 步 拒绝码：157 {mode = 2} → 16002，{mode = 5, config = 2} → 16003（{@code error_code == error_message.id}、
 *       {@code parameters[0]} 逐字节、不带票号）；</li>
 *   <li>第 3 步 排队与取消：1V1 入队拿到票 T → 再排 16001 带 T → 153 QUEUED → 148("stale") 1 s 内无回包且仍 QUEUED → 148(T) 无回包、
 *       153 → NOT_QUEUED；</li>
 *   <li>第 4 步 PVE_SOLO：受理 → 大厅先 177 后 143、同一 battle_id、票据形状、{@code expire_at_ms} ≈ 发起时刻 + 300 s → 153 是 MATCHED 或 READY；</li>
 *   <li>第 5 步 补签 179：A 的票与 177 逐字节相同；非成员 C → 1005 无票；不存在的局 → 1005「该战斗不存在或已结束」；</li>
 *   <li>第 6 步 凭补签的票直连 → 战斗中再排 → 16000 → 开挂机（162 的应答无错误）打到 150（SIDE_A_WIN，settlement 指向本局本人且
 *       终局一致、回合数 ≥ 1；判据同基线 {@code robot/features_battle_smoke.go:82-95}）→ FIN；</li>
 *   <li>第 7 步 立即再排：结算落地之前的 16000 按过渡态重试，<b>不得</b>出现 16001（ready 残留必须已自愈）→ 第二局同样打完；
 *       旧局的 179 → 1005；</li>
 *   <li>第 8 步 1V1 与评分：A 受理之后 B 再排（A 是锚点）→ 同一 battle_id、A 在 0 队 B 在 1 队 → 都挂机打到 150 → 10 s 内评分落账：
 *       games 各 + 1；胜负且不满 30 回合 |Δ| = 16，平局或打满 Δ = 0；</li>
 *   <li>第 9 步 切磋：16007 → 156 邀请逐字段 → 16011 → 16013（不消费）→ 拒绝只推发起者 → 16012 → 再发起、接受 → 双方 154 true 与
 *       同一局的 177 / 143 → 开自动之前 C 挑 A → 16009、A 发 163(0)（S12）→ 打完 → C 下线后 A 挑 C：越过过渡态 16010 直到 16008；</li>
 *   <li>第 11 步 xm-match 指标（本轮的增量）：成功开局按模式 PVE_SOLO ≥ 2、1V1 ≥ 1、切磋 ≥ 1；补签成功 ≥ 1；切磋按出口
 *       {@code invite/ok} ≥ 2、{@code respond/declined} ≥ 1、{@code respond/accepted} ≥ 1；评分入账 ≥ 1；</li>
 *   <li>第 12 步 结果行 {@code BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=…
 *       spectate_battle_id=… b_spectate_turns=… b_direct_spectate_turns=… removed_ok=1 s12_ready_residue=0|1}，
 *       失败是 {@code BATTLE_SMOKE_FAIL step=… reason=…}（{@link #resultLine}）。</li>
 * </ol>
 * 前置：切片带 xm-match、xm-battle 与 6.3 的 scene，运行模式 dev，Kafka 就绪，有运维令牌。
 *
 * <p>节奏：同一会话相邻请求隔 {@link MatchSupport#REQUEST_SPACING}（gate 对 match 的号每秒 3 条）；过渡态每 1 s 重试、上限 20 s；
 * 等开战 30 s、等终局 120 s；等观众票与 161 各 15 s、等 166 120 s。各阶段独立：前一阶段中断时记失败并继续后面的阶段（它们各自从排队开始）。
 * 收尾时给 A / B 各发一条 148("")（取消当前票，尽力而为），免得中断的那一步把 6 小时的排队票留在队列里；观战段的四个号在该段结束时就下线。
 *
 * <p>结果行里的步骤号是小写（{@code s3-watch}、{@code s5-budget}……）：{@link StepTrack} 只认小写字母、数字与连字符（同 6.4 的
 * {@code 4-pve-solo}、team 的 {@code s7-team-battle}）。
 */
public final class BattleSmokeScenario {

    static final String REF = "match-spec §15.5";
    static final String SPECTATE_REF = SpectateSteps.REF;
    static final String MARKER = "BATTLE_SMOKE";
    /** PVE 用 Dungeon 1（同基线 battle-smoke）。 */
    static final int PVE_CONFIG = 1;
    /** 1V1 与切磋不带副本。 */
    static final int PVP_CONFIG = 0;
    /** 没配组队人数的副本（缺省配置只有 {@code {1: 5}}）：PVE_TEAM 排它回 16003。 */
    static final int TEAM_CONFIG_NOT_OPEN = 2;
    /** 本场景成功的 PVE_SOLO 开局数（第 4 步与第 7 步）；另有 1V1 与切磋各一局。 */
    static final int EXPECTED_SOLO_GATHERS = 2;
    /** 本场景被受理的切磋邀请数（第 9 步：一条被拒绝、一条被接受）。 */
    static final int EXPECTED_INVITES = 2;
    /** 观战段成功的 163：S3、S4、S8、S11（S13 的下界）。 */
    static final int EXPECTED_WATCH_OK = 4;
    /** 观战段回 16018「该战斗不存在或已结束」的 163：S7、S10。 */
    static final int EXPECTED_WATCH_NOT_FOUND = 2;
    /** 观战段回 16014 的 163：S1、S6（S12 撞上 ready 残留时还会更多）。 */
    static final int EXPECTED_WATCH_QUEUED = 2;

    private final RobotClient client;
    private final PlayerFlow flow;
    private final MatchSupport.Ids ids;
    private final BattleIds battleIds;
    private final MatchAdminClient admin;
    private final int leaveGame;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final String accountSA;
    private final String accountSB;
    private final String accountSC;
    private final String accountSD;
    private final String runTag;
    private final Duration requestTimeout;
    private final Tempo tempo;
    private final Timing timing;
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
    private long spectateBattleId;
    private int spectateTurns;
    private boolean removedOk;
    private boolean readyResidue;

    /** 第 8 步 A 收到 1V1 那一局 177 的时刻（{@link System#nanoTime()}）；没跑到为 0。S12 据此算那一局的 ready 票据最晚何时过期。 */
    private long pvpAssignedNanos;
    /** 观战段 S6 里 SB 还没取消的排队票；没有为 null。观战段中途中断时据此补一条 148。 */
    private String spectatorQueueTicket;

    public BattleSmokeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                               String runTag, Duration requestTimeout) {
        this(client, flow, registry, admin, accountPrefix, runTag, requestTimeout, Tempo.STANDARD, Timing.STANDARD);
    }

    /**
     * @param tempo  节奏：对真服务端用 {@link Tempo#STANDARD}（上面的构造器）；单测对着本机假服务端时调快
     * @param timing 观战段的时限：对真服务端用 {@link Timing#STANDARD}
     */
    BattleSmokeScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                        String runTag, Duration requestTimeout, Tempo tempo, Timing timing) {
        this.tempo = tempo;
        this.timing = timing;
        this.client = client;
        this.flow = flow;
        this.ids = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        // 观战段的四个号：后缀与 a / b / c 等长（账号长度上限按一位后缀算）
        this.accountSA = accountName(accountPrefix, runTag, "w");
        this.accountSB = accountName(accountPrefix, runTag, "x");
        this.accountSC = accountName(accountPrefix, runTag, "y");
        this.accountSD = accountName(accountPrefix, runTag, "z");
        this.runTag = runTag;
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
                + uid(pvpBattleId) + " challenge_battle_id=" + uid(challengeBattleId) + " "
                + BattleSmokeChecks.spectateFields(spectateBattleId, spectateTurns, removedOk, readyResidue));
    }

    private void runChecks() throws RobotException {
        // ---- 第 1 步（前半）：运维令牌与指标基数 ----
        steps.step("1-login", report);
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        // 先抓一次指标：xm-match 管理端口不通（没起 / 地址指错）在登录之前就说清楚，也是第 11 步与 S13 的基线
        String metricsBefore = admin.scrapeMetrics();

        // ---- 观战段 S0–S11：四个自己的号，在匹配各段之前跑 ----
        phase("观战（S0–S11）", this::spectate);

        // ---- 第 1 步（后半）：A / B / C 登录 ----
        steps.step("1-login", report);
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
        phase("切磋（第 9 步，含 S12）", () -> challenge(a, b, c));
        phase("指标（第 11 步、S13）", () -> metrics(metricsBefore));
    }

    // ---------------------------------------------------------------- 观战段 S0–S11

    /**
     * 屏障期的战斗 X：S1 开出来，SA 直连但不开自动，到 S9 才放行。
     *
     * @param direct       SA 的直连（X 提前结束时 150 会出现在它上面）
     * @param startedNanos SA 收到 X 的 177 的时刻（屏障期用时预算的起点）
     */
    private record Barrier(long battleId, Direct direct, long startedNanos) {
    }

    private Barrier barrier;
    private boolean budgetBlown;

    private void spectate() throws RobotException {
        steps.step("s0-login", report);
        Bot sa = enter("SA", accountSA);
        Bot sb = enter("SB", accountSB);
        Bot sc = enter("SC", accountSC);
        Bot sd = enter("SD", accountSD);
        report.note("观战段 SA=" + uid(sa.id()) + " SB=" + uid(sb.id()) + " SC=" + uid(sc.id()) + " SD=" + uid(sd.id()));
        try {
            spectateSteps(sa, sb, sc, sd);
        } finally {
            // 四个号的戏份到此为止：SB 的排队票没来得及取消就补一条 148，然后按契约收尾（LeaveGame 后断开）
            if (spectatorQueueTicket != null) {
                cancelQuietly(sb);
            }
            for (Bot bot : List.of(sa, sb, sc, sd)) {
                leave(bot);
            }
        }
    }

    private void spectateSteps(Bot sa, Bot sb, Bot sc, Bot sd) throws RobotException {
        // ---- S0：列表的形状；预清理残留场次 ----
        steps.step("s0-list", report);
        ListWatchableBattlesResponse initial = SpectateSteps.list(sc, ids, 0);
        checkNull(SpectateSteps.listProblem(initial, 0, System.currentTimeMillis()),
                "S0 SC 发 164 {limit = 0} → 条数 ≤ 20、created_at_ms 不增、都在 [now − 365 s, now + 5 s]（过期的场次不在列表里）",
                SpectateSteps.describe(initial), SPECTATE_REF + " S0");
        steps.step("s0-preclean", report);
        preclean(sc);

        // ---- S1：开出战斗 X（屏障），参战者自己观战被拒 ----
        steps.step("s1-queued-watch", report);
        int saMark = sa.mark();
        long sentAt = System.currentTimeMillis();
        JoinQueueResponse solo = MatchSupport.join(sa, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG);
        must(BattleSmokeChecks.joinAcceptedProblem(solo) == null, "S1 SA 发 157 {mode = 4, config = " + PVE_CONFIG + "} 受理",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(solo), BattleSmokeChecks.describe(solo)), SPECTATE_REF + " S1");
        Started x = MatchSupport.awaitBattle(sa.name, sa.connection(), saMark, 0, battleIds, tempo.battleStartTimeout());
        spectateBattleId = x.battleId();
        report.note("观战 battle_id=" + uid(x.battleId()));
        // 屏障：立刻直连（回合帧只走直连），但不开自动——X 靠 6 s 的回合超时活着，直到 S9 放行
        Direct saDirect = connect(sa.name, x.assigned());
        barrier = new Barrier(x.battleId(), saDirect, x.assignedAt().receivedNanos());
        Direct sbDirect;
        try {
            sbDirect = barrierSteps(sa, sb, sc, x, sentAt);
        } catch (RobotException e) {
            throw new RobotException(e.getMessage() + barrierNote(), e);
        }
        finishWatched(sb, sc, sbDirect);
        eviction(sb, sd);
    }

    /**
     * S0 与 S1 之间的预清理（不参与判定，只记观察）。已结束的战斗在可观战索引里最长留 360 s（BW7），前一遍 robot 留下的残留场会让 S8 的随机观战
     * 一再扑空：每次 163(0) 至多挑两场，挑中已结束的就把它剔掉、回 16017。所以这里在开 X 之前先把残留清掉——列表非空就发一次 163(0)，
     * 直到列表为空。停止的判据是「列表空了」而不是「回了 16017」：只要连着扑空两场就回 16017，那时索引里多半还有别的残留。
     * 163(0) 成功说明切片上有活着的战斗：经直连发 165 退出，并停止清理（再发也只会落到它上面）；列表不再变短、或回了别的码也停。
     */
    private void preclean(Bot sc) {
        int rounds = 0;
        int previous = Integer.MAX_VALUE;
        try {
            for (; rounds < timing.precleanRounds(); rounds++) {
                ListWatchableBattlesResponse listed = SpectateSteps.list(sc, ids, SpectateSteps.LIST_MAX);
                int count = listed.getBattlesCount();
                if (count == 0) {
                    report.note("预清理：可观战列表已空（清了 " + rounds + " 轮）");
                    return;
                }
                if (count < SpectateSteps.LIST_MAX && count >= previous) {
                    // 上一轮的 163(0) 没有让列表变短：剩下的不是「已结束、能剔除」的残留（例如还在建房窗口里的场次），再发也没有用
                    report.note("预清理：第 " + rounds + " 轮的 163(0) 没有让列表变短（还有 " + count + " 条），停止清理；S8 的随机观战可能要多试几次");
                    return;
                }
                previous = count;
                int mark = sc.mark();
                WatchBattleResponse probe = SpectateSteps.watch(sc, ids, 0);
                if (SpectateSteps.accepted(probe)) {
                    report.note("预清理第 " + (rounds + 1) + " 轮：163(0) 挑中了一场活着的战斗 battle_id=" + uid(probe.getBattleId())
                            + "（切片上有别人在打，或上一遍中断的 robot 留下了还没到期的战斗），退出观战并停止清理；S8 的随机观战可能落在它上面");
                    leaveStranger(sc, mark, probe.getBattleId());
                    return;
                }
                if (!SpectateSteps.isRejection(probe, SpectateSteps.TIP_NO_BATTLE, SpectateSteps.TEXT_NO_BATTLE)) {
                    report.note("预清理第 " + (rounds + 1) + " 轮：163(0) 回了 " + SpectateSteps.describe(probe) + "，停止清理（列表里还有 "
                            + listed.getBattlesCount() + " 条）");
                    return;
                }
            }
            report.note("预清理：" + rounds + " 轮之后列表仍不为空，残留场次太多；S8 的随机观战可能要多试几次");
        } catch (RobotException | RuntimeException e) {
            report.note("预清理在第 " + (rounds + 1) + " 轮中断（不影响判定，后面的步骤照常跑）：" + message(e));
        }
    }

    /** 预清理时误入别人的战斗：等观众票 → 直连 → 165 退出。尽力而为，失败只记观察（观战标记留到 360 s 过期，下一条 163 会按换场清掉它）。 */
    private void leaveStranger(Bot sc, int mark, long strangerBattle) {
        try {
            Received r177 = SpectateSteps.awaitObserverTicket(sc.name, sc.connection(), mark, strangerBattle, battleIds, timing.ticketTimeout());
            Watching watching = observe(sc.name + "@预清理", r177.parse(BattleAssignedS2C.parser()));
            SpectateSteps.stopWatching(watching.direct(), strangerBattle, battleIds, timing.stopTimeout());
        } catch (RobotException | RuntimeException e) {
            report.note("预清理：退出 battle_id=" + uid(strangerBattle) + " 的观战没有走完：" + message(e));
        }
    }

    /** 屏障期的各步（S1 的后半到 S8）：战斗 X 必须一直活着，合计用时有预算。返回 SB 在 X 上的观战直连（S9 在它上面等收尾）。 */
    private Direct barrierSteps(Bot sa, Bot sb, Bot sc, Started x, long sentAt) throws RobotException {
        long xId = x.battleId();
        WatchBattleResponse own = SpectateSteps.watch(sa, ids, xId);
        checkNull(SpectateSteps.rejectedProblem(own, SpectateSteps.TIP_QUEUED, SpectateSteps.TEXT_QUEUED),
                "S1 SA 持着这一局的票（PVE_SOLO 直接建 matched 票，开局后 ready）发 163(X) → {16014, 匹配中无法观战}（票据检查先于战斗锁检查）",
                SpectateSteps.describe(own), SPECTATE_REF + " S1；§3.1 第 3 行");
        barrierBudget(1);

        // ---- S2：X 进了可观战列表 ----
        barrierStep(2, "s2-list");
        ListWatchableBattlesResponse listed = listUntilPresent(sc, xId);
        Optional<BattleWatchSummary> summary = SpectateSteps.find(listed, xId);
        String saName = BattleSmokeChecks.actorName(x.start().getState(), sa.id()).orElse("");
        String summaryProblem = summary.isEmpty() ? timing.publishRetry().toSeconds() + " s 内列表里一直没有 X；实得 " + SpectateSteps.describe(listed)
                : saName.isEmpty() ? "SA 的 143 里没有自己的角色名，无从核对 player_names"
                : SpectateSteps.summaryProblem(summary.get(), MatchMode.MATCH_MODE_PVE_SOLO_VALUE, PVE_CONFIG, List.of(saName), sentAt,
                        MatchSupport.wallClockMillis(x.assignedAt()));
        report.check(summaryProblem == null, "S2 SC 发 164 {limit = 50}（开局公告先于登记进索引，不在列表里时每 1 s 重试、上限 "
                        + timing.publishRetry().toSeconds() + " s）：X 在列表里，mode = 4、battle_config_id = " + PVE_CONFIG
                        + "、player_names = [SA 的角色名]、created_at_ms ∈ [发 157 − 5 s, 收到 177 + 5 s]",
                summaryProblem == null ? "player_names=" + summary.get().getPlayerNamesList() + " created_at_ms − 发 157 = "
                        + (summary.get().getCreatedAtMs() - sentAt) + " ms" : summaryProblem, SPECTATE_REF + " S2");
        ListWatchableBattlesResponse one = SpectateSteps.list(sc, ids, 1);
        checkNull(SpectateSteps.listProblem(one, 1, System.currentTimeMillis()), "S2 SC 发 164 {limit = 1} → 条数 ≤ 1", SpectateSteps.describe(one),
                SPECTATE_REF + " S2");
        barrierBudget(2);

        // ---- S3：SB 指定观战 X ----
        barrierStep(3, "s3-watch");
        int sbMark = sb.mark();
        WatchBattleResponse watched = SpectateSteps.watch(sb, ids, xId);
        must(SpectateSteps.acceptedProblem(watched, xId) == null, "S3 SB 发 163(X) → 应答 battle_id = X、没有 error_message",
                orDescribe(SpectateSteps.acceptedProblem(watched, xId), SpectateSteps.describe(watched)), SPECTATE_REF + " S3");
        Received r177 = SpectateSteps.awaitObserverTicket(sb.name, sb.connection(), sbMark, xId, battleIds, timing.ticketTimeout());
        BattleAssignedS2C sbTicket = r177.parse(BattleAssignedS2C.parser());
        checkNull(SpectateSteps.observerTicketProblem(sbTicket, xId, sb.id(), x.assigned().getExpireAtMs()),
                "S3 SB 的大厅收到 177 {role = 2, battle_id = X}：expire_at_ms 等于 SA 的 177、token_signature 是 64 位小写 hex、payload 是 SB 的观众票",
                "endpoint=" + sbTicket.getHost() + ":" + sbTicket.getPort() + " expire_at_ms=" + sbTicket.getExpireAtMs(), SPECTATE_REF + " S3");
        Watching sbWatch = observe(sb.name + "@X", sbTicket);
        Direct sbDirect = sbWatch.direct();
        checkNull(SpectateSteps.firstFrameProblem(sbDirect.since(0), battleIds, xId, 1),
                "S3 SB 的直连上第一帧是握手应答，然后才是 161 {observer_count = 1}，所有 actor 的冷却为空、没有 self_items",
                sbDirect.labelsSince(0), SPECTATE_REF + " S3；battle-node-spec §5.8 O2");
        barrierBudget(3);

        // ---- S4：重看同一场 ----
        barrierStep(4, "s4-rewatch");
        int lobbyMark = sb.mark();
        int directMark = sbDirect.mark();
        WatchBattleResponse again = SpectateSteps.watch(sb, ids, xId);
        checkNull(SpectateSteps.acceptedProblem(again, xId), "S4 SB 再发 163(X)（重看同一场）→ 成功", SpectateSteps.describe(again),
                SPECTATE_REF + " S4；§3.4");
        // 有活直连时大厅公告经直连直写：重推的 177 出现在直连上，不在大厅上
        Optional<BattleFrame> repush = sbDirect.await(directMark, f -> f.isPush(battleIds.battleAssigned()), timing.ticketTimeout());
        boolean identical = repush.isPresent()
                && repush.get().content().getSerializedMessage().equals(r177.content().getSerializedMessage());
        report.check(identical, "S4 重推的 177 到达 SB 的直连（它是活的，大厅公告直写），与第一条逐字节相同（同一份 payload，HMAC 是确定性的）",
                repush.isEmpty() ? timing.ticketTimeout().toSeconds() + " s 内直连上没有 177；此后收到 " + sbDirect.labelsSince(directMark)
                        : identical ? "" : "两条 177 的字节不同", SPECTATE_REF + " S4（评审 E-3）");
        Optional<BattleFrame> again161 = repush.isEmpty() ? Optional.empty()
                : sbDirect.await(repush.get().index() + 1, f -> f.isPush(battleIds.spectateState()), timing.firstFrameTimeout());
        report.check(again161.isPresent(), "S4 随后 SB 的直连上再收到一条 161（重看时有活直连即补推观战快照）",
                again161.isPresent() ? "" : "此后收到 " + sbDirect.labelsSince(directMark), SPECTATE_REF + " S4");
        Optional<Received> lobbyAgain = sb.connection().await(lobbyMark, r -> SpectateSteps.observerTicket(r, battleIds, 0) != null,
                tempo.silence());
        report.check(lobbyAgain.isEmpty(), "S4 SB 的大厅连接上 " + tempo.silence().toMillis() + " ms 内没有新的 177", lobbyAgain.isPresent()
                ? "大厅上又来了一条 177（重推的公告没有走直连）" : "", SPECTATE_REF + " S4（评审 E-3）");
        report.check(!removedOrClosed(sbDirect, directMark), "S4 重看同一场不清退：SB 的直连上没有 166、连接没有被关",
                sbDirect.labelsSince(directMark), SPECTATE_REF + " S4；§3.4");
        barrierBudget(4);

        // ---- S5：观众也能补签 ----
        barrierStep(5, "s5-reissue");
        RequestBattleTicketResponse reissued = reissue(sb, xId);
        report.check(reissued.getErrorMessage().getId() == 0 && reissued.hasAssignment()
                        && reissued.getAssignment().toByteString().equals(sbTicket.toByteString()),
                "S5 SB 发 179(X) → 无错误，assignment 与 177 逐字节相同（role = 2）",
                BattleSmokeChecks.describe(reissued.getErrorMessage()) + " assignment=" + reissued.hasAssignment()
                        + (reissued.hasAssignment() ? " role=" + reissued.getAssignment().getRole().getNumber() : ""), SPECTATE_REF + " S5");
        barrierBudget(5);

        // ---- S6：观战中可以排队，只排队不清退 ----
        barrierStep(6, "s6-queue-watching");
        int soloConfig = BattleSmokeChecks.soloQueueConfig(runTag);
        int queueMark = sbDirect.mark();
        JoinQueueResponse queued = MatchSupport.join(sb, ids, MatchMode.MATCH_MODE_1V1, soloConfig);
        must(BattleSmokeChecks.joinAcceptedProblem(queued) == null, "S6 观战中的 SB 发 157 {mode = 3, config = " + soloConfig
                        + "（本轮独有，凑不成局）} → 受理（观战中可以排队）",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(queued), BattleSmokeChecks.describe(queued)), SPECTATE_REF + " S6；BW3");
        spectatorQueueTicket = queued.getQueueTicket();
        Optional<BattleFrame> kicked = sbDirect.await(queueMark, f -> f.isPush(battleIds.spectateEnd()) || f.isClosed(), tempo.silence());
        report.check(kicked.isEmpty(), "S6 只排队不清退（进 gather 才清退）：SB 的直连上 " + tempo.silence().toMillis() + " ms 内没有 166",
                kicked.map(f -> "收到了 " + f.label()).orElse(""), SPECTATE_REF + " S6；BW3");
        WatchBattleResponse whileQueued = SpectateSteps.watch(sb, ids, 0);
        checkNull(SpectateSteps.rejectedProblem(whileQueued, SpectateSteps.TIP_QUEUED, SpectateSteps.TEXT_QUEUED),
                "S6 持排队票的 SB 发 163(0) → {16014, 匹配中无法观战}", SpectateSteps.describe(whileQueued), SPECTATE_REF + " S6");
        MatchSupport.cancel(sb, ids, queued.getQueueTicket());
        GetQueueStatusResponse afterCancel = MatchSupport.status(sb, ids);
        if (afterCancel.getState() == QueueState.QUEUE_STATE_QUEUED) {
            // 148 与随后的 153 是两次独立的调用，服务端在工作池上处理，先后没有硬保证：还在排就再问一次
            BattleSupport.sleep(tempo.retryInterval());
            afterCancel = MatchSupport.status(sb, ids);
        }
        if (afterCancel.getState() == QueueState.QUEUE_STATE_NOT_QUEUED) {
            spectatorQueueTicket = null;
        }
        report.check(afterCancel.getState() == QueueState.QUEUE_STATE_NOT_QUEUED, "S6 SB 发 148(该票) 之后 153 → NOT_QUEUED",
                BattleSmokeChecks.describe(afterCancel), SPECTATE_REF + " S6");
        barrierBudget(6);

        // ---- S7：指定一场不存在的战斗 ----
        barrierStep(7, "s7-ghost");
        long ghost = BattleFixtures.newBattleId(random);
        WatchBattleResponse missing = SpectateSteps.watch(sc, ids, ghost);
        checkNull(SpectateSteps.rejectedProblem(missing, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND),
                "S7 SC 发 163(随机且不存在的 id) → {16018, 该战斗不存在或已结束}", SpectateSteps.describe(missing), SPECTATE_REF + " S7；§3.1 第 12 行");
        barrierBudget(7);

        // ---- S8：随机观战，165 主动退出 ----
        barrierStep(8, "s8-random");
        RandomWatch picked = watchRandomRetrying(sc);
        must(SpectateSteps.acceptedProblem(picked.response(), 0) == null, "S8 SC 发 163(0)（随机；16017 每 1 s 重试、上限 "
                        + timing.randomRetry().toSeconds() + " s）→ 成功，battle_id ≠ 0",
                orDescribe(SpectateSteps.acceptedProblem(picked.response(), 0), SpectateSteps.describe(picked.response()))
                        + (picked.emptyRounds() == 0 ? "" : "（此前 " + picked.emptyRounds() + " 次 16017）"), SPECTATE_REF + " S8");
        long y = picked.response().getBattleId();
        boolean sameBattle = y == xId;
        if (!sameBattle) {
            report.note("S8 随机观战挑中的不是 X 而是 battle_id=" + uid(y) + "（切片上还有别的活战斗）：observer_count 只要求 ≥ 1");
        }
        Received y177 = SpectateSteps.awaitObserverTicket(sc.name, sc.connection(), picked.mark(), y, battleIds, timing.ticketTimeout());
        BattleAssignedS2C scTicket = y177.parse(BattleAssignedS2C.parser());
        checkNull(SpectateSteps.observerTicketProblem(scTicket, y, sc.id(), sameBattle ? x.assigned().getExpireAtMs() : 0),
                "S8 SC 的大厅收到 Y 的 177（role = 2）", "battle_id=" + uid(scTicket.getBattleId()), SPECTATE_REF + " S8");
        Watching scWatch = observe(sc.name + "@Y", scTicket);
        checkNull(SpectateSteps.firstFrameProblem(scWatch.direct().since(0), battleIds, y, sameBattle ? 2 : -1),
                "S8 SC 直连后握手应答紧跟 161" + (sameBattle ? " {observer_count = 2}（SB 与 SC）" : ""), scWatch.direct().labelsSince(0),
                SPECTATE_REF + " S8");
        Stopped stopped = SpectateSteps.stopWatching(scWatch.direct(), y, battleIds, timing.stopTimeout());
        checkNull(SpectateSteps.stopProblem(stopped, battleIds), "S8 SC 在直连上发 165 → 成功应答，然后 FIN，没有 166",
                BattleSupport.labels(stopped.tail()).toString(), SPECTATE_REF + " S8；battle-node-spec §5.8 O8");
        barrierBudget(8);
        if (!budgetBlown) {
            report.pass("S1 收到 177 到 S8 结束的合计用时在 " + timing.liveBudget().toSeconds() + " s 的预算内", barrierElapsedMs() + " ms",
                    SPECTATE_REF + "「战斗 X 的寿命」");
        }
        return sbDirect;
    }

    /** S9（放行屏障，X 打完）与 S10（结束之后再看、列表里已剔除）。 */
    private void finishWatched(Bot sb, Bot sc, Direct sbDirect) throws RobotException {
        long xId = barrier.battleId();
        barrierStep(9, "s9-finish");
        Direct saDirect = barrier.direct();
        AutoRequest auto = MatchSupport.enableAuto(saDirect, xId, battleIds);
        Finished xEnd = MatchSupport.awaitEnd(saDirect, xId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, auto);
        saDirect.close();
        report.check(xEnd.fin() && xEnd.autoAccepted(), "S9 放行屏障：SA 开自动（162 被受理）打到 150 后 FIN",
                "outcome=" + xEnd.end().getOutcome() + " 139 × " + xEnd.turns() + " 关闭方式=" + xEnd.closed() + xEnd.autoNote(), SPECTATE_REF + " S9");
        Ended sbEnd = SpectateSteps.awaitEnd(sbDirect, xId, battleIds, timing.endTimeout());
        sbDirect.close();
        spectateTurns = sbEnd.turns();
        checkNull(SpectateSteps.endProblem(sbEnd, battleIds, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED, xEnd.end().getOutcome(), 1),
                "S9 SB 的直连上 ≥ 1 条 158（观众版：冷却为空）→ 166 {FINISHED(1), outcome = SA 那条 150 的 outcome} → FIN",
                "158 × " + sbEnd.turns() + " 166 {" + sbEnd.end().getReason() + ", " + sbEnd.end().getOutcome() + "} 关闭方式=" + sbEnd.closed(),
                SPECTATE_REF + " S9");
        long stray = SpectateSteps.lobbyBattleFrames(sb.connection().inbox().snapshot(0), battleIds);
        report.check(stray == 0, "S9 SB 的大厅连接上 139 / 158 / 161 / 166 的条数都是 0（战斗帧只走直连，不回落大厅）", stray + " 条",
                SPECTATE_REF + " S9；battle-node-spec §5.7");

        steps.step("s10-ended", report);
        WatchBattleResponse ended = SpectateSteps.watch(sb, ids, xId);
        checkNull(SpectateSteps.rejectedProblem(ended, SpectateSteps.TIP_NOT_WATCHABLE, SpectateSteps.TEXT_NOT_FOUND),
                "S10 X 结束之后 SB 再发 163(X) → {16018, 该战斗不存在或已结束}（battle 回「房间不存在」）", SpectateSteps.describe(ended),
                SPECTATE_REF + " S10；§3.1 第 16 行");
        ListWatchableBattlesResponse afterEnd = SpectateSteps.list(sc, ids, SpectateSteps.LIST_MAX);
        report.check(SpectateSteps.find(afterEnd, xId).isEmpty(), "S10 SC 发 164：列表里没有 X（上一条 163 已经把它从可观战索引里剔除）",
                SpectateSteps.describe(afterEnd), SPECTATE_REF + " S10");
    }

    /** S11 开局清退：SB 观战 SD 的战斗 Z 时自己去排队，进 gather 之前被清退；之后两人都把各自的局打完（否则带着战斗锁）。 */
    private void eviction(Bot sb, Bot sd) throws RobotException {
        steps.step("s11-evict", report);
        int sdMark = sd.mark();
        JoinQueueResponse sdJoin = MatchSupport.join(sd, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG);
        must(BattleSmokeChecks.joinAcceptedProblem(sdJoin) == null, "S11 SD 发 157 PVE_SOLO 受理（战斗 Z）",
                orDescribe(BattleSmokeChecks.joinAcceptedProblem(sdJoin), BattleSmokeChecks.describe(sdJoin)), SPECTATE_REF + " S11");
        Started z = MatchSupport.awaitBattle(sd.name, sd.connection(), sdMark, 0, battleIds, tempo.battleStartTimeout());
        long zId = z.battleId();
        Direct sdDirect = connect(sd.name, z.assigned());
        Direct sbFight = null;
        long wId = 0;
        try {
            int sbMark = sb.mark();
            WatchBattleResponse watchZ = SpectateSteps.watch(sb, ids, zId);
            must(SpectateSteps.acceptedProblem(watchZ, zId) == null, "S11 SB 发 163(Z) → 成功",
                    orDescribe(SpectateSteps.acceptedProblem(watchZ, zId), SpectateSteps.describe(watchZ)), SPECTATE_REF + " S11");
            Received z177 = SpectateSteps.awaitObserverTicket(sb.name, sb.connection(), sbMark, zId, battleIds, timing.ticketTimeout());
            Watching sbWatch = observe(sb.name + "@Z", z177.parse(BattleAssignedS2C.parser()));
            checkNull(SpectateSteps.firstFrameProblem(sbWatch.direct().since(0), battleIds, zId, 1), "S11 SB 直连 Z 后握手应答紧跟 161 {observer_count = 1}",
                    sbWatch.direct().labelsSince(0), SPECTATE_REF + " S11");

            int lobbyMark = sb.mark();
            JoinQueueResponse sbJoin = MatchSupport.join(sb, ids, MatchMode.MATCH_MODE_PVE_SOLO, PVE_CONFIG);
            must(BattleSmokeChecks.joinAcceptedProblem(sbJoin) == null, "S11 观战中的 SB 发 157 PVE_SOLO 受理",
                    orDescribe(BattleSmokeChecks.joinAcceptedProblem(sbJoin), BattleSmokeChecks.describe(sbJoin)), SPECTATE_REF + " S11");
            // 两条连接之间不断言先后：直连上的 166 与大厅上新战斗的 177 / 143 分别来自 battle 的两间房
            String evictProblem;
            try {
                Ended kicked = SpectateSteps.awaitEnd(sbWatch.direct(), zId, battleIds, timing.evictTimeout());
                evictProblem = SpectateSteps.endProblem(kicked, battleIds, eSpectateEndReason.SPECTATE_END_REMOVED,
                        eBattleOutcome.BATTLE_OUTCOME_ONGOING, 0);
            } catch (RobotException e) {
                evictProblem = message(e);
            }
            sbWatch.direct().close();
            removedOk = evictProblem == null;
            report.check(removedOk, "S11 开局清退：SB 在 Z 的直连上 " + timing.evictTimeout().toSeconds()
                            + " s 内收到 166 {battle_id = Z, outcome = 0 ONGOING, reason = 3 REMOVED}，然后 FIN",
                    orDescribe(evictProblem, ""), SPECTATE_REF + " S11；§4.6");
            Started w = MatchSupport.awaitBattle(sb.name, sb.connection(), lobbyMark, 0, battleIds, tempo.battleStartTimeout());
            wId = w.battleId();
            report.check(wId != zId && wId != barrier.battleId(), "S11 SB 的大厅收到新战斗 W 的 177（role = 1）/ 143（清退不阻断开局）",
                    "W=" + uid(wId) + " Z=" + uid(zId), SPECTATE_REF + " S11");
            sbFight = connect(sb.name, w.assigned());
        } finally {
            // 不管上面走到哪一步，开出来的局都打完：SD 的 Z，以及 SB 的 W（开出来了的话）
            AutoRequest autoZ = MatchSupport.enableAuto(sdDirect, zId, battleIds);
            AutoRequest autoW = sbFight == null ? null : MatchSupport.enableAuto(sbFight, wId, battleIds);
            Finished zEnd = MatchSupport.awaitEnd(sdDirect, zId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoZ);
            sdDirect.close();
            boolean finished = zEnd.fin() && zEnd.autoAccepted();
            String detail = "Z 关闭=" + zEnd.closed() + zEnd.autoNote();
            if (sbFight != null) {
                Finished wEnd = MatchSupport.awaitEnd(sbFight, wId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoW);
                sbFight.close();
                finished &= wEnd.fin() && wEnd.autoAccepted();
                detail += "；W 关闭=" + wEnd.closed() + wEnd.autoNote();
            }
            report.check(finished && sbFight != null, "S11 之后 SD、SB 都开自动（162 被受理）打完各自的局（否则带着战斗锁）",
                    sbFight == null ? detail + "；SB 的 W 没有开出来" : detail, SPECTATE_REF + " S11");
        }
    }

    /** 凭观众票直连到 161，并登记到收尾时关闭的名单里。 */
    private Watching observe(String name, BattleAssignedS2C ticket) throws RobotException {
        Watching watching = SpectateSteps.connectObserver(client, name, ticket, battleIds, timing.firstFrameTimeout());
        closeables.add(watching.direct());
        return watching;
    }

    /** 这条观战直连从 {@code from} 起有没有 166 或被关闭（此刻的快照，不等）。 */
    private boolean removedOrClosed(Direct direct, int from) {
        return direct.since(from).stream().anyMatch(f -> f.isPush(battleIds.spectateEnd()) || f.isClosed());
    }

    /** 发 164 {limit = 50} 直到列表里有这一场（每 1 s 一次）；到上限仍没有就返回最后一次的应答，由调用方判定。 */
    private ListWatchableBattlesResponse listUntilPresent(Bot bot, long battleId) throws RobotException {
        long deadline = System.nanoTime() + timing.publishRetry().toNanos();
        while (true) {
            ListWatchableBattlesResponse listed = SpectateSteps.list(bot, ids, SpectateSteps.LIST_MAX);
            if (SpectateSteps.find(listed, battleId).isPresent() || System.nanoTime() - deadline >= 0) {
                return listed;
            }
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    /**
     * 一次随机观战的尝试序列。
     *
     * @param mark        最后一次发 163 之前的大厅收件箱位置（成功时从它起找观众票）
     * @param response    最后一次的应答
     * @param emptyRounds 此前回 16017 的次数
     */
    private record RandomWatch(int mark, WatchBattleResponse response, int emptyRounds) {
    }

    /** 发 163(0)；回 16017「当前没有可观战的战斗」时每 1 s 重试（每次失败都会懒剔除已结束的残留场），直到别的应答或到上限。 */
    private RandomWatch watchRandomRetrying(Bot bot) throws RobotException {
        long deadline = System.nanoTime() + timing.randomRetry().toNanos();
        int empty = 0;
        while (true) {
            int mark = bot.mark();
            WatchBattleResponse response = SpectateSteps.watch(bot, ids, 0);
            if (!SpectateSteps.isRejection(response, SpectateSteps.TIP_NO_BATTLE, SpectateSteps.TEXT_NO_BATTLE)
                    || System.nanoTime() - deadline >= 0) {
                return new RandomWatch(mark, response, empty);
            }
            empty++;
            BattleSupport.sleep(tempo.retryInterval());
        }
    }

    /** 进屏障期的一步（S2–S9）：先确认战斗 X 还活着（已结束就记 {@code s<n>-x-ended-early} 并中止观战段），再记步骤号。 */
    private void barrierStep(int n, String step) throws RobotException {
        List<BattleFrame> frames = barrier.direct().since(0);
        boolean ended = MatchSupport.endOf(frames, battleIds, barrier.battleId()) != null;
        if (ended || frames.stream().anyMatch(BattleFrame::isClosed)) {
            steps.step("s" + n + "-x-ended-early", report);
            report.fail("S" + n + " 之前战斗 X 就结束了（屏障期间 SA 的直连上出现了" + (ended ? " 150" : "关闭") + "）",
                    "X 打了 " + MatchSupport.turnCount(frames, battleIds) + " 回合，距 S1 收到 177 " + barrierElapsedMs()
                            + " ms。X 不开自动时每 6 s 按回合超时结算一次：回合数远少于十几回合说明表数值变了，否则是脚本太慢；帧="
                            + BattleSupport.labels(frames), SPECTATE_REF + "「战斗 X 的寿命」");
            throw new RobotException("战斗 X 在 S9 放行之前就结束了，观战段后面的步骤依赖它");
        }
        steps.step(step, report);
    }

    /** 屏障期一步结束时核对合计用时；第一次超出时记一条 {@code s<n>-budget} 的失败（之后照常往下跑：X 多半还活着）。 */
    private void barrierBudget(int n) {
        String problem = BattleSmokeChecks.budgetProblem(barrierElapsedMs(), timing.liveBudget().toMillis());
        if (problem != null && !budgetBlown) {
            budgetBlown = true;
            steps.step("s" + n + "-budget", report);
            report.fail("S1 收到 177 到 S" + n + " 结束的合计用时超出预算", problem, SPECTATE_REF + "「战斗 X 的寿命」");
        }
    }

    private long barrierElapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - barrier.startedNanos());
    }

    /** 屏障期某一步中断时拼在原因后面的一段：X 此刻是不是已经结束了（多数「观战突然失败」的真实原因）。 */
    private String barrierNote() {
        List<BattleFrame> frames = barrier.direct().since(0);
        if (MatchSupport.endOf(frames, battleIds, barrier.battleId()) == null && frames.stream().noneMatch(BattleFrame::isClosed)) {
            return "";
        }
        return "（此刻战斗 X 已经结束：打了 " + MatchSupport.turnCount(frames, battleIds) + " 回合，距 S1 收到 177 " + barrierElapsedMs() + " ms）";
    }

    private void cancelQuietly(Bot bot) {
        try {
            if (bot.connection().isOpen()) {
                MatchSupport.cancel(bot, ids, "");
            }
        } catch (RobotException | RuntimeException ignored) {
            // 尽力而为：断线之后凑单也会把离线成员的票删掉
        }
    }

    /** 按契约收尾：LeaveGame 后断开（gate 断线即删在线目录）。 */
    private void leave(Bot bot) {
        try {
            if (bot.connection().isOpen()) {
                bot.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
            }
        } catch (RobotException | RuntimeException ignored) {
            // 发不出去就直接断开
        }
        bot.connection().close();
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
        Started started = MatchSupport.awaitBattle(a.name, a.connection(), lobbyMark, 0, battleIds, tempo.battleStartTimeout());
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
        AutoRequest auto = MatchSupport.enableAuto(direct, battleId, battleIds);
        Finished first = MatchSupport.awaitEnd(direct, battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, auto);
        direct.close();
        aTurns = first.turns();
        BattleEndS2C end = first.end();
        report.check(first.autoAccepted(), "第 6 步 162 开挂机被受理：应答不是信封错误、不带 error_message（否则这一局是靠回合超时的默认行动打完的）",
                first.autoAccepted() ? "帧=" + first.labels() : first.autoProblem() + "；帧=" + first.labels(),
                "robot/features_battle_smoke.go:126-129");
        String endProblem = BattleSmokeChecks.pveVictoryProblem(end, battleId, a.id(), first.turns());
        report.check(endProblem == null,
                "第 6 步 挂机打到 150：SIDE_A_WIN（外层与 settlement 一致）、settlement 指向本局与 A、total_rounds ≥ 1，直连上至少一条 139",
                orDescribe(endProblem, "outcome=" + end.getOutcome() + " settlement.battle=" + uid(end.getSettlement().getBattleId())
                        + " settlement.player=" + uid(end.getSettlement().getPlayerId()) + " rounds=" + end.getSettlement().getTotalRounds()
                        + " 139 × " + first.turns()), "robot/features_battle_smoke.go:82-95");
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
        Started second = MatchSupport.awaitBattle(a.name, a.connection(), requeue.mark(), 0, battleIds, tempo.battleStartTimeout());
        report.check(second.battleId() != battleId, "第 7 步 第二局是新的 battle_id", uid(battleId) + " → " + uid(second.battleId()), REF);
        // 开战即直连（回合结算只走直连），再做旧局的补签
        Direct secondDirect = connect(a.name, second.assigned());
        RequestBattleTicketResponse old = reissue(a, battleId);
        report.check(old.getErrorMessage().getId() == BattleSmokeChecks.TIP_INVALID_PARAMETER && !old.hasAssignment(),
                "第 7 步 第一局结束后补签它 → 1005（落点记录还在，battle 回「房间不存在」原样透传；只断言 id）",
                BattleSmokeChecks.describe(old.getErrorMessage()) + " assignment=" + old.hasAssignment(), "match-spec §4.3、坑 11");
        AutoRequest secondAuto = MatchSupport.enableAuto(secondDirect, second.battleId(), battleIds);
        Finished secondEnd = MatchSupport.awaitEnd(secondDirect, second.battleId(), battleIds, MatchSupport.BATTLE_END_TIMEOUT, secondAuto);
        secondDirect.close();
        report.check(secondEnd.end().getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && secondEnd.fin() && secondEnd.autoAccepted(),
                "第 7 步 第二局同样挂机（162 被受理）打到 150（SIDE_A_WIN）后 FIN——否则 A 带着战斗锁进第 8 步会一直 16000",
                "outcome=" + secondEnd.end().getOutcome() + " 关闭方式=" + secondEnd.closed() + secondEnd.autoNote(), REF);
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
        Started startA = MatchSupport.awaitBattle(a.name, a.connection(), queueA.mark(), 0, battleIds, tempo.battleStartTimeout());
        // S12 用：这一局的 ready 票据从此刻起最多再活 60 s（置 ready 在建房回包之后，177 在建房期间就推出了）
        pvpAssignedNanos = startA.assignedAt().receivedNanos();
        Started startB = MatchSupport.awaitBattle(b.name, b.connection(), markB, 0, battleIds, tempo.battleStartTimeout());
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
        AutoRequest autoA = MatchSupport.enableAuto(directA, pvpBattleId, battleIds);
        AutoRequest autoB = MatchSupport.enableAuto(directB, pvpBattleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, pvpBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoA);
        Finished endB = MatchSupport.awaitEnd(directB, pvpBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoB);
        directA.close();
        directB.close();
        eBattleOutcome outcome = endA.end().getOutcome();
        int rounds = endA.end().getSettlement().getTotalRounds();
        report.check(endB.end().getOutcome() == outcome && endA.fin() && endB.fin() && endA.autoAccepted() && endB.autoAccepted(),
                "第 8 步 都开自动（162 被受理）→ 两人都收到 150（同一个终局）后 FIN",
                "A outcome=" + outcome + " rounds=" + rounds + " 关闭=" + endA.closed() + endA.autoNote() + "；B outcome=" + endB.end().getOutcome()
                        + " 关闭=" + endB.closed() + endB.autoNote(), REF);

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
        Started startA;
        Started startB;
        try {
            startA = MatchSupport.awaitBattle(a.name, a.connection(), markA, 0, battleIds, tempo.battleStartTimeout());
            startB = MatchSupport.awaitBattle(b.name, b.connection(), markB, 0, battleIds, tempo.battleStartTimeout());
        } catch (RobotException e) {
            // 开局失败时 match 会给双方各再推一次 154 false：把它写进失败原因，省得去翻服务端日志
            int failMark = markA;
            boolean gatherFailed = MatchSupport.awaitPush(a.connection(), failMark, ids.challengeResult(), ChallengeResultS2C.parser(),
                    p -> p.getChallengeId() == second.challengeId() && !p.getAccepted(), Duration.ZERO).isPresent();
            throw new RobotException(e.getMessage() + (gatherFailed ? "；A 在 154 true 之后又收到了 154 false：这一局的 gather 失败了"
                    + "（看 xm-match 的 xm_match_gathers_total{outcome}）" : ""), e);
        }
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

        // S12（观战段的一步，借这一局）：切磋不建票，A 只有战斗锁
        inBattleWatch(a, directA, directB);

        AutoRequest autoA = MatchSupport.enableAuto(directA, challengeBattleId, battleIds);
        AutoRequest autoB = MatchSupport.enableAuto(directB, challengeBattleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, challengeBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoA);
        Finished endB = MatchSupport.awaitEnd(directB, challengeBattleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT, autoB);
        directA.close();
        directB.close();
        report.check(endA.end().getOutcome() == endB.end().getOutcome() && endA.fin() && endB.fin() && endA.autoAccepted() && endB.autoAccepted(),
                "第 9 步 都开自动（162 被受理）→ 两人都收到 150 后 FIN",
                "A outcome=" + endA.end().getOutcome() + " 关闭=" + endA.closed() + endA.autoNote() + "；B outcome=" + endB.end().getOutcome()
                        + " 关闭=" + endB.closed() + endB.autoNote(), REF);

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

    // ---------------------------------------------------------------- S12（在第 9 步的切磋局里）

    /**
     * S12：A 在切磋局里（开自动之前）发 163(0)，期望 16015。A 在第 8 步刚打完 1V1，那一局的 ready 票据（60 s）可能还在，而 163 先查票据后查锁、
     * 对票据不自愈（BW1），这时回 16014：每 1 s 重试，直到「第 8 步收到 177 的时刻 + 62 s」，之后必须是 16015。切磋局不开自动时按 6 s 的
     * 回合超时推进，新号 1V1 最短也要打约 48 s，等得过来；等待期间它要是打完了就记 {@code s12-battle-ended}。
     *
     * <p>不抛异常：这一步出什么事，后面「都开自动打完」都要照常跑，否则 A、B 带着战斗锁。
     */
    private void inBattleWatch(Bot a, Direct directA, Direct directB) {
        steps.step("s12-in-battle", report);
        // 不知道上一局什么时候开的（第 8 步没走到开局）：按「刚刚」算，最多等满一个 ready 窗口
        long residueDeadline = (pvpAssignedNanos != 0 ? pvpAssignedNanos : System.nanoTime()) + timing.readyResidue().toNanos();
        int residueHits = 0;
        try {
            while (true) {
                WatchBattleResponse response = SpectateSteps.watch(a, ids, 0);
                InBattleWatch verdict = BattleSmokeChecks.inBattleWatch(response, System.nanoTime() - residueDeadline < 0);
                if (verdict == InBattleWatch.READY_RESIDUE) {
                    residueHits++;
                    readyResidue = true;
                    if (challengeOver(directA) || challengeOver(directB)) {
                        steps.step("s12-battle-ended", report);
                        report.fail("S12 等上一局的 ready 票据过期时切磋局先打完了", "已经 " + residueHits
                                        + " 次 16014；切磋局不开自动时每 6 s 一回合，新号 1V1 至少打 8 回合——打得这么快说明表数值变了，或前面的步骤太慢",
                                SPECTATE_REF + " S12");
                        return;
                    }
                    BattleSupport.sleep(tempo.retryInterval());
                    continue;
                }
                report.check(verdict == InBattleWatch.IN_BATTLE, "S12 切磋局里（开自动之前）A 发 163(0) → {16015, 战斗尚未结束,无法观战}"
                                + "（上一局 1V1 的 ready 票据还在时先回 16014，每 1 s 重试到「第 8 步收到 177 + "
                                + timing.readyResidue().toSeconds() + " s」，之后必须是 16015）",
                        SpectateSteps.describe(response) + (residueHits == 0 ? "" : "（此前 " + residueHits + " 次 16014：ready 残留，BW1）"),
                        SPECTATE_REF + " S12；§3.1 第 3、5 行");
                return;
            }
        } catch (RobotException | RuntimeException e) {
            report.fail("S12 流程中断", message(e), SPECTATE_REF + " S12");
        } finally {
            steps.step("9-challenge", report);
        }
    }

    /** 切磋局在这条直连上是不是已经打完了（收到了它的 150，或连接已被关闭）。 */
    private boolean challengeOver(Direct direct) {
        List<BattleFrame> frames = direct.since(0);
        return MatchSupport.endOf(frames, battleIds, challengeBattleId) != null || frames.stream().anyMatch(BattleFrame::isClosed);
    }

    // ---------------------------------------------------------------- 第 11 步、S13

    private void metrics(String before) throws RobotException {
        steps.step("11-metrics", report);
        String after = admin.scrapeMetrics();
        String step = "第 11 步";
        String ref = "match-spec §11、§15.5 第 11 步";
        // 开局按 mode 拆开（标签值是 MatchMode 的枚举名）：合在一起只看「成功 ≥ 4」的话，同一切片上别的机器人的成功开局可以顶数
        metricGrew(step, ref, before, after, EXPECTED_SOLO_GATHERS, "xm_match_gathers_total", mode(MatchMode.MATCH_MODE_PVE_SOLO), "outcome=\"success\"");
        metricGrew(step, ref, before, after, 1, "xm_match_gathers_total", mode(MatchMode.MATCH_MODE_1V1), "outcome=\"success\"");
        metricGrew(step, ref, before, after, 1, "xm_match_gathers_total", mode(MatchMode.MATCH_MODE_PVP_CHALLENGE), "outcome=\"success\"");
        metricGrew(step, ref, before, after, 1, "xm_match_battle_ticket_reissues_total", "result=\"ok\"");
        // 切磋按 (stage, result) 看：服务端每个出口都计数，不带标签求和的话第 9 步头一个动作「挑战自己 → 16007」就让它 + 1，
        // 之后发起、应答、接受全失败也照样「有增长」
        metricGrew(step, ref, before, after, EXPECTED_INVITES, "xm_match_challenges_total", "stage=\"invite\"", "result=\"ok\"");
        metricGrew(step, ref, before, after, 1, "xm_match_challenges_total", "stage=\"respond\"", "result=\"declined\"");
        metricGrew(step, ref, before, after, 1, "xm_match_challenges_total", "stage=\"respond\"", "result=\"accepted\"");
        metricGrew(step, ref, before, after, 1, "xm_match_rating_updates_total", "outcome=\"applied\"");

        // ---- S13：观战指标（与场景开头的基数相减；指标是全局的，只取下界） ----
        steps.step("s13-metrics", report);
        step = "S13";
        ref = SPECTATE_REF + " S13；spectate-spec §6";
        metricGrew(step, ref, before, after, EXPECTED_WATCH_OK, "xm_match_watch_battle_total", "outcome=\"ok\"");
        metricGrew(step, ref, before, after, EXPECTED_WATCH_NOT_FOUND, "xm_match_watch_battle_total", "outcome=\"not_found\"");
        metricGrew(step, ref, before, after, EXPECTED_WATCH_QUEUED, "xm_match_watch_battle_total", "outcome=\"queued\"");
        metricGrew(step, ref, before, after, 1, "xm_match_watch_battle_total", "outcome=\"in_battle\"");
        metricGrew(step, ref, before, after, 1, "xm_match_spectate_evictions_total", "reason=\"enter_gather\"", "result=\"removed\"");
    }

    private static String mode(MatchMode mode) {
        return "mode=\"" + mode.name() + "\"";
    }

    private void metricGrew(String step, String ref, String before, String after, int atLeast, String metric, String... labels) {
        double b = AdminClient.sum(before, metric, labels);
        double a = AdminClient.sum(after, metric, labels);
        report.check(a - b >= atLeast, step + " 指标 " + metric + (labels.length == 0 ? "" : "{" + String.join(",", labels) + "}") + " 本轮至少 + " + atLeast,
                b + " → " + a, ref);
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
        must(passed, name, detail, REF);
    }

    private void must(boolean passed, String name, String detail, String ref) throws RobotException {
        report.check(passed, name, detail, ref);
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
