package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.robot.client.BattleIds;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.MatchSupport.Bot;
import com.game.robot.scenario.MatchSupport.Finished;
import com.game.robot.scenario.MatchSupport.Started;
import com.game.robot.scenario.MatchSupport.Tempo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 5V5 排队成局与蛇形分队（批次 6.4，match-spec §15.5「场景 match-5v5」，可选）。十个新号按次序逐个排 {@code {mode = 1, config = 0}}：
 * <ol>
 *   <li>每人的 157 都受理（上一个人的受理应答回来之后才发下一个：入队次序 = 账号次序）；</li>
 *   <li>第十个人入队后凑单弹组：十人在大厅收到<b>同一个</b> battle_id 的 177 / 143；</li>
 *   <li>十个新号评分都是 1500：gather 重读评分后按弹出序稳定排序、蛇形分队，按入队次序的队号是 {@code 0,1,1,0,0,1,1,0,0,1}（§2.9）；</li>
 *   <li>全员直连、开挂机，都收到 150；</li>
 *   <li>10 s 内评分落账：每人 games + 1；两队赛前同分，胜负且不满 30 回合时胜方每人 + 16、负方每人 − 16，平局或打满都是 0。</li>
 * </ol>
 * 前置同 battle-smoke（切片带 xm-match、xm-battle 与 6.3 的 scene、Kafka、dev 运行模式、运维令牌）。队列是全服共享的
 * {@code 5V5 / config 0}：切片里同时有别人在排这条队列时，弹出的十个人就不全是本场景的账号，第 2 步会失败并写明。
 * 结果行 {@code MATCH_5V5_OK battle_id=… outcome=… rounds=…} / {@code MATCH_5V5_FAIL step=… reason=…}。
 */
public final class Match5v5Scenario {

    static final String REF = "match-spec §15.5、§2.9";
    static final String MARKER = "MATCH_5V5";
    /** 5V5 凑满一局的人数。 */
    public static final int PLAYERS = 10;
    static final int PVP_CONFIG = 0;

    private final RobotClient client;
    private final PlayerFlow flow;
    private final MatchSupport.Ids ids;
    private final BattleIds battleIds;
    private final MatchAdminClient admin;
    private final String accountPrefix;
    private final String runTag;
    private final Duration requestTimeout;
    private final Tempo tempo;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps = new StepTrack(MARKER);
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();

    private long battleId;
    private eBattleOutcome outcome = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
    private int rounds;

    public Match5v5Scenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                            String runTag, Duration requestTimeout) {
        this(client, flow, registry, admin, accountPrefix, runTag, requestTimeout, Tempo.STANDARD);
    }

    /** @param tempo 节奏：对真服务端用 {@link Tempo#STANDARD}（上面的构造器）；单测对着本机假服务端时调快 */
    Match5v5Scenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                     String runTag, Duration requestTimeout, Tempo tempo) {
        this.tempo = tempo;
        this.client = client;
        this.flow = flow;
        this.ids = MatchSupport.Ids.resolve(registry);
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.accountPrefix = accountPrefix;
        this.runTag = runTag;
        this.requestTimeout = requestTimeout;
    }

    /** 第 {@code index} 个账号（0 – 9；一位数，十个账号等长）。 */
    public static String accountName(String prefix, String runTag, int index) {
        if (index < 0 || index >= PLAYERS) {
            throw new IllegalArgumentException("账号下标应在 0–" + (PLAYERS - 1) + " 之间：" + index);
        }
        return prefix + "m5" + runTag + "_" + index;
    }

    public String firstAccount() {
        return accountName(accountPrefix, runTag, 0);
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            cleanup();
        }
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）。 */
    public String resultLine() {
        return steps.line(report, "battle_id=" + uid(battleId) + " outcome=" + outcome.getNumber() + " rounds=" + rounds);
    }

    private void runChecks() throws RobotException {
        // ---- 第 1 步：登录、赛前评分 ----
        steps.step("1-login", report);
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        List<Long> players = new ArrayList<>();
        List<Rating> before = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            EnteredPlayer player = flow.enter(accountName(accountPrefix, runTag, i), new Timings());
            closeables.add(player.connection());
            Bot bot = new Bot("P" + i, player, requestTimeout, tempo);
            bots.add(bot);
            players.add(bot.id());
            before.add(admin.rating(bot.id()));
        }
        report.note("十个账号按入队次序：" + players.stream().map(Match5v5Scenario::uid).toList() + " match-admin=" + admin.baseUrl());

        // ---- 第 2 步：按次序入队，凑满成局 ----
        steps.step("2-queue", report);
        List<Integer> marks = new ArrayList<>();
        for (Bot bot : bots) {
            marks.add(bot.mark());
            JoinQueueResponse joined = MatchSupport.join(bot, ids, MatchMode.MATCH_MODE_5V5, PVP_CONFIG);
            String problem = BattleSmokeChecks.joinAcceptedProblem(joined);
            if (problem != null) {
                report.fail("第 2 步 " + bot.name + " 发 157 {mode = 1, config = 0} 受理", problem, "match-spec §2.2");
                throw new RobotException(bot.name + " 入队没有受理，凑不满一局：" + problem);
            }
        }
        report.pass("第 2 步 十人按次序发 157 {mode = 1, config = 0} 都受理", "", "match-spec §2.2");
        List<Started> starts = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            starts.add(MatchSupport.awaitBattle(bots.get(i).name, bots.get(i).connection(), marks.get(i), 0, battleIds,
                    tempo.battleStartTimeout()));
        }
        battleId = starts.get(0).battleId();
        List<String> others = new ArrayList<>();
        for (int i = 1; i < PLAYERS; i++) {
            if (starts.get(i).battleId() != battleId) {
                others.add(bots.get(i).name + "=" + uid(starts.get(i).battleId()));
            }
        }
        report.check(others.isEmpty(), "第 2 步 十人收到同一个 battle_id 的 177 / 143",
                "P0=" + uid(battleId) + (others.isEmpty() ? "" : "，不同的：" + others + "（这条队列里还有别的排队者？）"), REF);
        if (!others.isEmpty()) {
            throw new RobotException("十人没有进同一局，后面的分队与评分断言无从谈起");
        }
        report.note("5V5 battle_id=" + uid(battleId));

        // ---- 第 3 步：蛇形分队 ----
        steps.step("3-teams", report);
        String sides = BattleSmokeChecks.sidesProblem(starts.get(0).start().getState(), players, BattleSmokeChecks.SNAKE_5V5);
        report.check(sides == null, "第 3 步 评分相同：143 的 actors 按入队次序的队号是蛇形 0,1,1,0,0,1,1,0,0,1", sides == null ? "" : sides,
                "match-spec §2.9");

        // ---- 第 4 步：全员直连挂机打完 ----
        steps.step("4-fight", report);
        List<Direct> directs = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            Direct direct = MatchSupport.connect(client, bots.get(i).name, starts.get(i).assigned(), battleIds, requestTimeout);
            closeables.add(direct);
            directs.add(direct);
        }
        for (Direct direct : directs) {
            MatchSupport.enableAuto(direct, battleId, battleIds);
        }
        List<String> endProblems = new ArrayList<>();
        Finished firstEnd = null;
        for (Direct direct : directs) {
            Finished end = MatchSupport.awaitEnd(direct, battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
            direct.close();
            if (firstEnd == null) {
                firstEnd = end;
            }
            if (end.end().getOutcome() != firstEnd.end().getOutcome() || !end.fin()) {
                endProblems.add(direct.name + " outcome=" + end.end().getOutcome() + " 关闭=" + end.closed());
            }
        }
        outcome = firstEnd.end().getOutcome();
        rounds = firstEnd.end().getSettlement().getTotalRounds();
        report.check(endProblems.isEmpty(), "第 4 步 全员开自动 → 十人都收到 150（同一个终局）后 FIN",
                "outcome=" + outcome + " rounds=" + rounds + (endProblems.isEmpty() ? "" : "；不一致的：" + endProblems), REF);

        // ---- 第 5 步：评分 ----
        steps.step("5-rating", report);
        List<Rating> after = MatchSupport.awaitRatings(admin, before, tempo);
        String problem = BattleSmokeChecks.ratingProblem(before, after, BattleSmokeChecks.SNAKE_5V5, outcome, rounds);
        report.check(problem == null, "第 5 步 收到 150 后 " + MatchSupport.RATING_WAIT.toSeconds()
                        + " s 内评分落账：每人 games + 1，同队同增量、两队互为相反数", problem == null ? "0 队 Δ = "
                        + BattleSmokeChecks.centi(after.get(0).ratingCenti() - before.get(0).ratingCenti()) : problem, "match-spec §5.1");
    }

    /** 收尾：还连着的人各发一条 148("")（取消当前票，尽力而为：没凑满时别把票留在队列里），再关掉全部连接。 */
    private void cleanup() {
        for (Bot bot : bots) {
            if (bot.connection().isOpen()) {
                try {
                    MatchSupport.cancel(bot, ids, "");
                } catch (RobotException | RuntimeException ignored) {
                    // 尽力而为
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

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }
}
