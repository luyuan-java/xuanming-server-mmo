package com.game.robot.scenario;

import com.game.common.time.GameDay;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.robot.client.BattleIds;
import com.game.robot.client.MatchAdminClient;
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
 * 帮会活动开战的 dev 入口（批次 6.4，match-spec §15.5「场景 match-activity」、§7.1–§7.2）。真正的调用方 xm-guild 随批次 4.6 接入；
 * 6.4 由 robot 经 xm-match 的 dev 管理口 {@code POST /admin/match/dev/activity-battle}（{@link MatchAdminClient}）调同一个实现。
 * 只在 dev / test 运行模式下跑（prod 的 403 由 xm-match 自己的单测覆盖，robot 不切运行模式）。A / B 两个新号在线，C 进场后立即登出：
 * <ol>
 *   <li>发起人不在名单首位（名单 [A, B]、上下文的发起人 = B）→ {@code INVALID_ARGUMENT}，offender = 0、不发 battle_id；</li>
 *   <li>名单 [A, C]（C 已登出）→ {@code MEMBER_OFFLINE}，offender = C；</li>
 *   <li>名单 [A, B]、上下文合法 → 受理，{@code battle_id ≠ 0}；A、B 在大厅收到这一局的 177 / 143（PVE：两人都在 0 队）；</li>
 *   <li>两人在战斗中（已直连、未开自动）时再发同样的请求 → {@code MEMBER_IN_BATTLE}，offender = A（名单里第一个持战斗锁的人）；</li>
 *   <li>两人直连挂机打到 150。</li>
 * </ol>
 * {@code guild_id} / {@code activity_id} 用<b>不存在的值</b>：dev 口建的是正常房间，打完照常发活动结果事件；4.6 的消费方把不认识的
 * (guild, activity) 当终态销账（match-spec §7.2 末条）。结果行 {@code MATCH_ACTIVITY_OK battle_id=…} / {@code MATCH_ACTIVITY_FAIL step=… reason=…}。
 */
public final class MatchActivityScenario {

    static final String REF = "match-spec §15.5、§7.1";
    static final String MARKER = "MATCH_ACTIVITY";
    /** Dungeon 1（PVE 组队，缺省人数表 {@code {1: 5}}）。 */
    static final int BATTLE_CONFIG = 1;
    /** 不存在的帮会与活动（契约只要求非 0）：真实的帮会号是雪花号，活动号是 GuildActivity 表的小整数，都撞不上。 */
    static final long GHOST_GUILD_ID = 9_000_000_000_000_006_400L;
    static final int GHOST_ACTIVITY_ID = 2_000_006_400;

    private final RobotClient client;
    private final PlayerFlow flow;
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

    private long battleId;
    private long playerA;
    private long playerB;

    public MatchActivityScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                                 String runTag, Duration requestTimeout) {
        this(client, flow, registry, admin, accountPrefix, runTag, requestTimeout, Tempo.STANDARD);
    }

    /** @param tempo 节奏：对真服务端用 {@link Tempo#STANDARD}（上面的构造器）；单测对着本机假服务端时调快 */
    MatchActivityScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, MatchAdminClient admin, String accountPrefix,
                          String runTag, Duration requestTimeout, Tempo tempo) {
        this.tempo = tempo;
        this.client = client;
        this.flow = flow;
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.requestTimeout = requestTimeout;
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "ma" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
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

    /** 结果行（跑完 {@link #run} 之后取）。 */
    public String resultLine() {
        return steps.line(report, "battle_id=" + uid(battleId) + " player_a=" + uid(playerA) + " player_b=" + uid(playerB));
    }

    private void runChecks() throws RobotException {
        // ---- 第 1 步：登录 ----
        steps.step("1-login", report);
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-match 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        Bot a = enter("A", accountA);
        Bot b = enter("B", accountB);
        // C 只充当「已登出的账号」：进场拿到角色号后按契约收尾（LeaveGame 后断开），gate 断线即删在线目录
        Bot c = enter("C", accountC);
        c.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        c.connection().close();
        playerA = a.id();
        playerB = b.id();
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " C（已登出）=" + uid(c.id()) + " match-admin=" + admin.baseUrl());
        int dayKey = GameDay.dayKey(System.currentTimeMillis());

        // ---- 第 2 步：发起人不在首位 ----
        steps.step("2-invalid-argument", report);
        StartActivityBattleResponse wrongInitiator = admin.startActivityBattle(request(BATTLE_CONFIG, List.of(a.id(), b.id()), b.id(), dayKey));
        expectReject("第 2 步 名单 [A, B] 而上下文的发起人是 B（不在首位）→ INVALID_ARGUMENT，offender = 0、不发 battle_id", wrongInitiator,
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0);

        // ---- 第 3 步：名单里有已登出的账号 ----
        steps.step("3-member-offline", report);
        StartActivityBattleResponse offline = admin.startActivityBattle(request(BATTLE_CONFIG, List.of(a.id(), c.id()), a.id(), dayKey));
        expectReject("第 3 步 名单 [A, C]（C 已登出）→ MEMBER_OFFLINE，offender = C", offline,
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, c.id());

        // ---- 第 4 步：受理，两人收到同一局的开局公告 ----
        steps.step("4-start", report);
        StartActivityBattleRequest valid = request(BATTLE_CONFIG, List.of(a.id(), b.id()), a.id(), dayKey);
        int markA = a.mark();
        int markB = b.mark();
        StartActivityBattleResponse started = admin.startActivityBattle(valid);
        report.check(started.getReject() == ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE && started.getBattleId() != 0
                        && started.getOffenderPlayerId() == 0,
                "第 4 步 名单 [A, B]、上下文合法 → 受理：reject = NONE、battle_id ≠ 0、offender = 0", describe(started), REF);
        if (started.getReject() != ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE || started.getBattleId() == 0) {
            throw new RobotException("活动开战没有受理，后面的步骤依赖它：" + describe(started));
        }
        battleId = started.getBattleId();
        Started startA = MatchSupport.awaitBattle(a.name, a.connection(), markA, battleId, battleIds, tempo.battleStartTimeout());
        Started startB = MatchSupport.awaitBattle(b.name, b.connection(), markB, battleId, battleIds, tempo.battleStartTimeout());
        String sides = BattleSmokeChecks.sidesProblem(startA.start().getState(), List.of(a.id(), b.id()), List.of(0, 0));
        report.check(startA.assignedFirst() && startB.assignedFirst() && sides == null,
                "第 4 步 A、B 在大厅收到这一局（应答里的 battle_id）的 177 → 143，两人都在 0 队（PVE）",
                "A 177 #" + startA.assignedAt().index() + " 143 #" + startA.startAt().index() + "；B 177 #" + startB.assignedAt().index() + " 143 #"
                        + startB.startAt().index() + (sides == null ? "" : "；" + sides), "match-spec §3.1、§8.3");
        // 开战即直连（回合结算只走直连）；不开自动：两人持战斗锁
        Direct directA = connect(a.name, startA);
        Direct directB = connect(b.name, startB);

        // ---- 第 5 步：战斗中再发 ----
        steps.step("5-member-in-battle", report);
        StartActivityBattleResponse inBattle = admin.startActivityBattle(valid);
        expectReject("第 5 步 两人在战斗中再发同样的请求 → MEMBER_IN_BATTLE，offender = A（名单里第一个持战斗锁的人）", inBattle,
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE, a.id());

        // ---- 第 6 步：打完 ----
        steps.step("6-fight", report);
        MatchSupport.enableAuto(directA, battleId, battleIds);
        MatchSupport.enableAuto(directB, battleId, battleIds);
        Finished endA = MatchSupport.awaitEnd(directA, battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        Finished endB = MatchSupport.awaitEnd(directB, battleId, battleIds, MatchSupport.BATTLE_END_TIMEOUT);
        report.check(endA.end().getOutcome() == endB.end().getOutcome() && endA.fin() && endB.fin(),
                "第 6 步 两人直连挂机打到 150（同一个终局）后 FIN",
                "A outcome=" + endA.end().getOutcome() + " rounds=" + endA.end().getSettlement().getTotalRounds() + " 关闭=" + endA.closed()
                        + "；B outcome=" + endB.end().getOutcome() + " 关闭=" + endB.closed(), REF);
    }

    /**
     * 活动开战请求：名单顺序即站位顺序；上下文的帮会 / 活动是不存在的值，两个期键取当前游戏日。
     *
     * @param initiator 上下文里的发起人（合法请求里必须是名单第一个人）
     * @param dayKey    游戏日键 YYYYMMDD（非 0）
     */
    static StartActivityBattleRequest request(int battleConfigId, List<Long> members, long initiator, int dayKey) {
        return StartActivityBattleRequest.newBuilder()
                .setBattleConfigId(battleConfigId)
                .addAllMemberPlayerIds(members)
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                        .setGuildId(GHOST_GUILD_ID)
                        .setActivityId(GHOST_ACTIVITY_ID)
                        .setPeriodKey(dayKey)
                        .setInitiatorPlayerId(initiator)
                        .setGuildPeriodKey(dayKey))
                .build();
    }

    /**
     * 业务拒绝的应答：拒绝原因与肇事者对上，且没有发 battle_id。
     *
     * @return null = 没问题
     */
    static String rejectProblem(StartActivityBattleResponse response, ActivityBattleReject reject, long offender) {
        if (response.getReject() == reject && response.getOffenderPlayerId() == offender && response.getBattleId() == 0) {
            return null;
        }
        return "实得 " + describe(response) + "，期望 reject=" + reject + " offender=" + uid(offender) + " battle_id=0";
    }

    static String describe(StartActivityBattleResponse response) {
        return "reject=" + response.getReject() + " offender=" + uid(response.getOffenderPlayerId()) + " battle_id=" + uid(response.getBattleId());
    }

    private void expectReject(String name, StartActivityBattleResponse response, ActivityBattleReject reject, long offender) {
        String problem = rejectProblem(response, reject, offender);
        report.check(problem == null, name, problem == null ? describe(response) : problem, REF);
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        return new Bot(name, player, requestTimeout, tempo);
    }

    private Direct connect(String name, Started started) throws RobotException {
        Direct direct = MatchSupport.connect(client, name, started.assigned(), battleIds, requestTimeout);
        closeables.add(direct);
        return direct;
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }
}
