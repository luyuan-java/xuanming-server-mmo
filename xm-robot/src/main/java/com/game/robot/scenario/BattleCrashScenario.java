package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmBlockCurrencyRequest;
import com.game.proto.GmBlockCurrencyResponse;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.GmUnblockCurrencyRequest;
import com.game.proto.GmUnblockCurrencyResponse;
import com.game.proto.RecallPetRequest;
import com.game.proto.RecallPetResponse;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SummonPetRequest;
import com.game.proto.SummonPetResponse;
import com.game.proto.login.LeaveGameRequest;
import com.game.robot.CrashWindowOptions;
import com.game.robot.CrashWindowOptions.Phase;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.BattleAdminClient.DevGather;
import com.game.robot.client.BattleAdminClient.GatherOutcome;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleCrashChecks.Landing;
import com.game.robot.scenario.BattleCrashChecks.Replay;
import com.game.robot.scenario.BattleCrashChecks.Stage;
import com.game.robot.scenario.BattleCrashChecks.State;
import com.game.robot.scenario.BattleSettleChecks.BattleIdSequence;
import com.game.robot.scenario.BattleSettleChecks.StepFailures;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.BattleSupport.LobbyBot;
import com.game.table.PetErrorTip;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * battle-settle 的故障变体（scene-battle-spec §13.8「故障变体」；形态参照基线 {@code robot/currency_crash_window_scenario.go}：robot 只做客户端的两段，
 * kill -9 与重启由编排脚本 {@code tools/local/battle-crash-window.sh} 做）。只在本机切片上跑，一个新号 A。
 *
 * <p><b>scene-after-150</b>（大厅收到 150 之后立即 kill scene、重启、重登 → 金币恰好增一次、150 至多一份）
 * <ul>
 *   <li>{@code arm}：登录、领宝宝出战（同 battle-settle 第 1 步，打赢才有 gold_gain）、记金币 → dev gather 建房、直连挂机打完 → 大厅收到 150（scene 已应用）那一刻原子地写出状态文件（断点）→
 *       保持连接不动最多 {@link #ARM_HOLD}（本端主动断开会触发 scene 的断线写回，抢在 kill 之前把结算落盘）后退出；</li>
 *   <li>{@code verify}（scene 已重启）：重登（归属租约 30 s、频道与 gate 链路要恢复，登录带重试）→ 数这条连接上本局的 150：0 条 = kill 落在落盘之后，
 *       1 条 = 落在落盘之前、进场恢复按待结算记录重放，≥ 2 条 = 重复；金币 = 开打前 + gold_gain；闸已解除、锁已放；再重登一次仍不重发。</li>
 * </ul>
 * 金币是这个变体唯一分得清「整笔丢失 / 恰好一次 / 重复」的量（0 条 150 时已落盘与整笔丢失只差金币）：这一局必须打赢且 gold_gain &gt; 0，
 * 否则 arm 停在 fight 步、不写断点（编排脚本不杀进程），verify 读到 gold_gain = 0 的状态文件也停在 state 步。
 *
 * <p><b>battle-after-store</b>（结算落库之后、大厅 150 之前 kill battle → 期限 + 10 s 时 scene 的 rescue 到账，D21）。
 * 「落库之后、投递之前」在 battle 进程里只有几毫秒，从进程外杀不中；这里用一个不改服务端的办法把窗口撑开：A 先用 GM 94 封禁自己的金币获取
 * （战斗在途也放行），于是首投到达 scene 时「金币先行」那一步入账被拒、整笔 DEFERRED（零副作用，§7.11 d 步），待结算记录留在 Redis、冻结保持
 * FIGHTING——正是「已 SET、scene 还没应用」的状态，而且一直保持到有人解封。
 * <ul>
 *   <li>{@code arm}（跨过 kill，全程用同一条大厅连接：A 一旦重登，记录会由进场恢复应用，就看不到 rescue 了）：登录、领宝宝出战、记金币、封金币 →
 *       dev gather 建房（期限 now + {@link #STORE_DEADLINE}）、直连挂机打完 → 核对窗口已撑开（battle 指标 {@code stored} +1、
 *       {@code delivery{deferred}} +1、大厅没有 150）→ 写状态文件（断点，脚本此时 kill -9 xm-battle）→ 等 battle 管理端口连不上 → 解封金币 →
 *       等到期限 + 10 s 宽限 + reaper 间隔：大厅收到 150 的时刻不早于期限 + 10 s、与直连那份逐字段相同、金币恰好增一次、冻结已摘、
 *       scene 指标 {@code rescues{applied}} 有增长、再等 10 s 没有第二条 → 把结局写回状态文件；</li>
 *   <li>{@code verify}（battle 已重启）：状态文件里 arm 记下的结局必须是 rescued（early / missing 时 arm 已失败，停在 state 步，不让汇总行写成 OK）→
 *       重登 → 没有第二条 150、金币仍是开打前 + gold_gain、闸已解除、锁已放（再备战成功）。</li>
 * </ul>
 * 结尾在观察记录里写一行 {@code BATTLE_CRASH_OK variant=… phase=… battle_id=… outcome=…} 或 {@code BATTLE_CRASH_FAIL … step=…}。
 *
 * <p>前置同 battle-settle：切片带 xm-battle 与 xm-scene、dev 运行模式（要用 GM 94 / 95 与 dev gather）、运维令牌；battle-after-store 另要
 * scene 的 reaper 间隔调小（切片缺省 2 s），否则 rescue 最晚在期限 + 10 s + 30 s 才来。
 */
public final class BattleCrashScenario {

    static final String REF = "scene-battle-spec §13.8「故障变体」";
    private static final String REF_RESCUE = "scene-battle-spec §7.9、D21";
    private static final String REF_RECOVERY = "scene-battle-spec §7.8、§7.12";
    private static final String REF_HOLD = "scene-battle-spec §7.11 d 步、§7.15";

    private static final int GOLD = 0;
    private static final int TIP_PET_IN_BATTLE = PetErrorTip.pet_error.kPetInBattle_VALUE;

    /** battle-after-store 这一局的战斗期限：要长过挂机打完 PVE1 的时间，又决定了 rescue 要等多久。 */
    static final Duration STORE_DEADLINE = Duration.ofSeconds(120);
    /** 战斗至少要在期限前这么久打完：后面还要核对窗口、等脚本杀进程、解封，这些都必须在期限 + 10 s 之前做完。 */
    static final Duration STORE_MARGIN = Duration.ofSeconds(15);
    /** 直连 150 之后等这么久确认大厅没有 150（首投已被延后；正常投递在几十毫秒内就到）。 */
    static final Duration HOLD_SETTLE = Duration.ofSeconds(2);
    /** 写出断点之后等编排脚本杀掉 xm-battle 的上限（期间 battle 每 10 s 重投一次，金币仍封着，每次都被延后，不影响结论）。 */
    static final Duration KILL_WAIT = Duration.ofSeconds(30);
    private static final Duration KILL_POLL = Duration.ofMillis(300);
    /** 杀进程与解封必须在「期限 + 10 s」之前这么久做完，否则 reaper 的 rescue 会撞上还封着的金币。 */
    static final Duration RESCUE_HEADROOM = Duration.ofSeconds(5);
    /** scene-after-150 写出断点之后保持连接不动的上限。 */
    static final Duration ARM_HOLD = Duration.ofSeconds(8);
    /** 进程重启之后重登的总预算与重试间隔（归属租约 30 s 过期后才夺得回来；scene 重启后频道 / gate 链路也要几秒）。 */
    static final Duration RELOGIN_BUDGET = Duration.ofSeconds(120);
    private static final Duration RELOGIN_RETRY = Duration.ofSeconds(3);
    /** 重登后等进场恢复推 150 的上限。 */
    static final Duration RECOVERY_WAIT = Duration.ofSeconds(15);
    /** 重登后等锁放掉（再备战成功）的上限：进场恢复销账要等一次落盘。 */
    static final Duration LOCK_WAIT = Duration.ofSeconds(10);
    /** robot 与 scene 取时刻的误差余量（同一台机器）。 */
    static final Duration ARRIVAL_TOLERANCE = Duration.ofSeconds(1);

    private final RobotClient client;
    private final PlayerFlow flow;
    private final BattleIds battleIds;
    private final BattleAdminClient admin;
    private final List<String> sceneMetricsUrls;
    private final String armAccount;
    private final Variant variant;
    private final Phase phase;
    private final Path stateFile;
    private final Duration requestTimeout;
    private final CheckReport report = new CheckReport();
    private final StepFailures stepFailures = new StepFailures();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final BattleIdSequence battleIdSequence = new BattleIdSequence();
    /** 只备战、可能还冻着的局（结束时尽力取消）。 */
    private final List<Long> preparedOnly = new ArrayList<>();

    private final int getCurrencyList;
    private final int gmBlock;
    private final int gmUnblock;
    private final int recallPet;
    private final int grantPet;
    private final int summonPet;
    private final int leaveGame;

    private Bot a;
    private State state;
    private long battleId;
    private long deadlineMs;
    private long goldBefore;
    private int lobbyEndMark;
    private BattleEndS2C directEnd;
    private String battleBefore;
    private String sceneBefore;
    private boolean goldBlocked;
    private String outcome = "";

    /**
     * @param sceneMetricsUrl xm-scene 管理端口（battle-after-store 抓 {@code rescues{applied}}；抓不到只记观察）。可逗号分隔多个
     * @param options         哪个变体、哪个阶段、状态文件
     */
    public BattleCrashScenario(RobotClient client, PlayerFlow flow, MessageIdRegistry registry, BattleAdminClient admin, String sceneMetricsUrl,
                               String accountPrefix, String runTag, CrashWindowOptions options, Duration requestTimeout) {
        if (!options.enabled()) {
            throw new IllegalArgumentException("不是故障变体（--crash-window none）");
        }
        this.client = client;
        this.flow = flow;
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.sceneMetricsUrls = BattleSettleChecks.metricsUrls(sceneMetricsUrl);
        this.armAccount = accountName(accountPrefix, runTag);
        this.variant = options.variant();
        this.phase = options.phase();
        this.stateFile = options.stateFile();
        this.requestTimeout = requestTimeout;
        this.getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        this.gmBlock = registry.requireId("SceneCurrencyClientPlayer", "GmBlockCurrency");
        this.gmUnblock = registry.requireId("SceneCurrencyClientPlayer", "GmUnblockCurrency");
        this.recallPet = registry.requireId("ScenePetClientPlayer", "RecallPet");
        this.grantPet = registry.requireId("ScenePetClientPlayer", "GmGrantPet");
        this.summonPet = registry.requireId("ScenePetClientPlayer", "SummonPet");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
    }

    /**
     * 帮助文本里的判定版本标记（{@code [crash-window-rev=N]}，N = {@link BattleCrashChecks#STATE_VERSION}）：
     * {@code tools/local/battle-crash-window.sh} 在动手之前按它拒绝判定过期的旧 jar。
     */
    public static String revisionMarker() {
        return BattleCrashChecks.revisionMarker();
    }

    /** arm 阶段的新号：{@code 前缀 + bc + 标签 + _a}（verify 阶段的账号取自状态文件）。 */
    public static String accountName(String prefix, String runTag) {
        return prefix + "bc" + runTag + "_a";
    }

    public String armAccount() {
        return armAccount;
    }

    public CheckReport run() {
        try {
            if (!admin.hasToken()) {
                throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-battle 相同），或先用 tools/local/start-slice.sh 生成 "
                        + "run/xm-admin-token（从仓库根目录运行 robot）");
            }
            if (variant == Variant.SCENE_AFTER_150) {
                if (phase == Phase.ARM) {
                    armSceneAfterEnd();
                } else {
                    verifySceneAfterEnd();
                }
            } else if (phase == Phase.ARM) {
                armBattleAfterStore();
            } else {
                verifyBattleAfterStore();
            }
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", message(e), REF);
            stepFailures.add("流程");
        } finally {
            unblockGoldQuietly();
            cancelPreparedQuietly();
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结论
                }
            }
            report.note(BattleCrashChecks.summaryLine(report.passed(), variant, phase, battleId, outcome, stepFailures.steps()));
        }
        return report;
    }

    // ================================================================ scene-after-150

    private void armSceneAfterEnd() {
        if (!step("login", this::loginFresh)) {
            return;
        }
        if (!step("fight", () -> fightToDirectEnd(deadline(BattleSettleScenario.LONG_DEADLINE), false))) {
            return;
        }
        step("breakpoint", this::breakOnLobbyEnd);
    }

    /** 断点：大厅收到 150（scene 已应用结算）那一刻写出状态文件；然后保持连接不动，等脚本杀掉 scene。 */
    private void breakOnLobbyEnd() throws RobotException {
        long x = battleId;
        Optional<Received> lobbyEnd = a.connection().await(lobbyEndMark, r -> isEnd(r, x), BattleSettleScenario.LOBBY_END_TIMEOUT);
        if (lobbyEnd.isEmpty()) {
            report.fail("断点：大厅收到 150", BattleSettleScenario.LOBBY_END_TIMEOUT.toSeconds() + " s 内没有收到，不写状态文件（编排脚本不会杀进程）"
                    + a.connection().describeSince(lobbyEndMark), REF);
            return;
        }
        // 先写断点、后做别的：从这里到 kill 的时间越短，越可能落在「已应用、还没落盘」那一段。
        // armedAt 再拦一次「没打赢 / gold_gain = 0」（fight 步已经拦过）：那样的一局见证不了「恰好一次」，不能有断点文件
        long now = System.currentTimeMillis();
        state = BattleCrashChecks.armedAt(Variant.SCENE_AFTER_150, a.player.account(), a.id(), x, goldBefore, directEnd.getOutcome(),
                directEnd.getSettlement().getGoldGain(), deadlineMs, now, now);
        BattleCrashChecks.write(stateFile, state);
        report.pass("断点：大厅收到 150（scene 已应用结算），状态文件已写出", stateFile.toAbsolutePath() + "，gold_gain="
                + directEnd.getSettlement().getGoldGain(), REF);
        BattleEndS2C body = lobbyEnd.get().parseOrNull(BattleEndS2C.parser());
        String mismatch = body == null ? "大厅 150 解析失败" : BattleSettleChecks.lobbyEndMismatch(body, directEnd);
        report.check(mismatch == null, "大厅 150 与直连 150 逐字段相同", mismatch == null ? "一致" : mismatch, "scene-battle-spec §6.1");
        // 不主动断开：断线即写回，会抢在 kill 之前把这笔结算落盘
        long droppedAt = pollUntil(ARM_HOLD, Duration.ofMillis(100), () -> !a.connection().isOpen());
        report.note(droppedAt >= 0 ? "写出断点 " + (droppedAt - now) + " ms 后大厅连接被服务端断开"
                : "写出断点后 " + ARM_HOLD.toSeconds() + " s 内大厅连接没有断（gate 在 scene 死后不踢会话，或编排脚本没有杀 scene）");
    }

    private void verifySceneAfterEnd() {
        if (!step("state", () -> loadState(Variant.SCENE_AFTER_150, Stage.ARMED))) {
            return;
        }
        if (!step("relogin", this::reloginAfterRestart)) {
            return;
        }
        step("exactly-once", this::sceneRestartExactlyOnce);
        step("released", this::frozenAndLockReleased);
        step("relogin-again", this::secondReloginNoResend);
    }

    /** 重登后：本局的 150 至多一份、金币恰好增一次。 */
    private void sceneRestartExactlyOnce() throws RobotException {
        long x = state.battleId();
        // 进场恢复在进场后读锁与待结算记录：有记录就应用并推 150。先等它，再留一个观察窗看有没有第二条
        a.connection().await(0, r -> isEnd(r, x), RECOVERY_WAIT);
        BattleSupport.sleep(BattleSettleScenario.NO_REPEAT_WINDOW);
        List<Received> ends = a.connection().inbox().snapshot(0).stream().filter(r -> isEnd(r, x)).toList();
        Replay replay = BattleCrashChecks.replayOf(ends.size());
        outcome = replay.wire();
        report.check(replay != Replay.DUPLICATED, "scene 重启后重登：本局的 150 至多一份", ends.size() + " 条（" + describe(replay) + "）", REF);
        long goldNow = gold(a);
        String problem = BattleCrashChecks.goldProblem(state.goldBefore(), state.goldGain(), goldNow);
        // gold_gain > 0 已由 state 步保证：0 条 150 时「已落盘」与「整笔丢失」就靠这一条分开
        report.check(problem == null, "金币恰好增一次（开打前 + gold_gain）", problem == null ? state.goldBefore() + " → " + goldNow + "，gold_gain="
                + state.goldGain() : problem, REF);
        if (!ends.isEmpty()) {
            BattleEndS2C body = ends.get(0).parseOrNull(BattleEndS2C.parser());
            report.check(body != null && body.getSettlement().getPlayerId() == a.id() && body.getSettlement().getGoldGain() == state.goldGain(),
                    "重放的 150 是同一份结算（player_id、gold_gain 与断点前直连那份相同）", body == null ? "解析失败"
                            : "gold_gain=" + body.getSettlement().getGoldGain() + " outcome=" + body.getOutcome(), REF_RECOVERY);
        }
    }

    /** 再登出登入一次：进场恢复重放过的那一笔已经落盘并销账，不会再来一次。 */
    private void secondReloginNoResend() throws RobotException {
        long x = state.battleId();
        long goldThen = gold(a);
        leave(a);
        a = enterWithRetry(state.account(), RELOGIN_BUDGET);
        BattleSupport.sleep(BattleSettleScenario.NO_REPEAT_WINDOW);
        long ends = a.connection().inbox().snapshot(0).stream().filter(r -> isEnd(r, x)).count();
        long goldNow = gold(a);
        report.check(ends == 0 && goldNow == goldThen, "再登出登入一次：没有 150、金币不变（账本已落盘并销账）", ends + " 条 150，金币 " + goldThen
                + " → " + goldNow, REF_RECOVERY);
    }

    // ================================================================ battle-after-store

    private void armBattleAfterStore() {
        if (!step("login", this::loginFresh)) {
            return;
        }
        if (!step("block-gold", this::blockGold)) {
            return;
        }
        if (!step("fight", () -> fightToDirectEnd(deadline(STORE_DEADLINE), true))) {
            return;
        }
        if (!step("hold", this::confirmHoldAndBreak)) {
            return;
        }
        if (!step("kill", this::awaitBattleKilled)) {
            return;
        }
        if (!step("unblock-gold", this::unblockGold)) {
            return;
        }
        step("rescue", this::observeRescue);
    }

    /** 封禁 A 自己的金币获取（GM 94；货币的 GM 指令在战斗在途时也放行）。先抓一份指标基线。 */
    private void blockGold() throws RobotException {
        battleBefore = admin.scrapeMetrics();
        sceneBefore = scrapeScene();
        int tip = a.call(gmBlock, GmBlockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(), GmBlockCurrencyResponse.parser())
                .getErrorMessage().getId();
        goldBlocked = tip == 0;
        report.check(tip == 0, "封禁 A 的金币获取（GM 94）：首投时 scene 的金币先行入账会被拒，整笔结算延后", "tip=" + tip, REF_HOLD);
    }

    /**
     * 核对窗口确实撑开了，然后写断点。三样证据：battle 的 {@code stored} 有增长（已 SET）、{@code delivery{deferred}} 有增长（首投到了 scene、被延后）、
     * 大厅在 {@link #HOLD_SETTLE} 内没有 150（scene 没应用）。任何一样不成立都不写状态文件，脚本就不会杀进程。
     */
    private void confirmHoldAndBreak() throws RobotException {
        long x = battleId;
        long gain = directEnd.getSettlement().getGoldGain();
        Optional<Received> early = a.connection().await(lobbyEndMark, r -> isEnd(r, x), HOLD_SETTLE);
        String battleNow = admin.scrapeMetrics();
        double stored = BattleSettleChecks.delta(battleBefore, battleNow, "xm_battle_settlement_outbox_total", "event=\"stored\"");
        double deferred = BattleSettleChecks.delta(battleBefore, battleNow, "xm_battle_settlement_delivery_total", "result=\"deferred\"");
        String problem = BattleCrashChecks.holdProblem(stored, deferred, early.isPresent(), gain);
        report.check(problem == null, "窗口已撑开：结算已落库、首投被 scene 延后、大厅还没有 150", problem == null ? "stored +" + (long) stored
                + "，delivery{deferred} +" + (long) deferred + "，gold_gain=" + gain : problem, REF_HOLD);
        if (problem != null) {
            return;
        }
        long now = System.currentTimeMillis();
        state = BattleCrashChecks.armedAt(Variant.BATTLE_AFTER_STORE, a.player.account(), a.id(), x, goldBefore, directEnd.getOutcome(), gain,
                deadlineMs, now, 0);
        BattleCrashChecks.write(stateFile, state);
        report.pass("断点：状态文件已写出，等编排脚本 kill -9 xm-battle", stateFile.toAbsolutePath() + "，离战斗期限还有 "
                + (deadlineMs - now) / 1000 + " s", REF);
    }

    /** 等 xm-battle 的管理端口连不上（连续两次，免得把一次抖动当成进程已死）。必须赶在期限 + 10 s 之前。 */
    private void awaitBattleKilled() throws RobotException {
        long latest = deadlineMs + BattleSettleScenario.FIGHTING_GRACE.toMillis() - RESCUE_HEADROOM.toMillis();
        long budgetMs = Math.max(0, Math.min(KILL_WAIT.toMillis(), latest - System.currentTimeMillis()));
        int[] misses = {0};
        long deadAt = pollUntil(Duration.ofMillis(budgetMs), KILL_POLL, () -> {
            try {
                admin.scrapeMetrics();
                misses[0] = 0;
                return false;
            } catch (RobotException e) {
                return ++misses[0] >= 2;
            }
        });
        report.check(deadAt >= 0, "xm-battle 已被 kill -9（管理端口连续两次连不上）", deadAt >= 0 ? "断点后 " + (deadAt - state.breakpointAtMs()) + " ms"
                : budgetMs + " ms 内管理端口一直有应答：编排脚本没有杀 xm-battle（单独跑 arm 阶段不会有人杀它）", REF);
    }

    /** battle 已死、没有人再重投：解封金币，之后能让这笔结算落地的只剩 scene 的 reaper。 */
    private void unblockGold() throws RobotException {
        long x = battleId;
        int tip = a.call(gmUnblock, GmUnblockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(), GmUnblockCurrencyResponse.parser())
                .getErrorMessage().getId();
        goldBlocked = tip != 0;
        long ends = a.connection().inbox().snapshot(lobbyEndMark).stream().filter(r -> isEnd(r, x)).count();
        long left = deadlineMs + BattleSettleScenario.FIGHTING_GRACE.toMillis() - System.currentTimeMillis();
        report.check(tip == 0 && ends == 0 && left > 0, "battle 死后解封金币（GM 95）：此刻大厅仍没有 150，离期限 + 10 s 还有余量",
                "tip=" + tip + "，150 " + ends + " 条，离期限 + 10 s 还有 " + left + " ms", REF_HOLD);
    }

    /** 等 scene 的 reaper 在期限 + 10 s 宽限之后读到本局记录并应用（D21），核对到账恰好一次。结局写回状态文件。 */
    private void observeRescue() throws RobotException {
        long x = battleId;
        long graceMs = BattleSettleScenario.FIGHTING_GRACE.toMillis();
        long graceEnd = deadlineMs + graceMs;
        long waitMs = Math.max(0, graceEnd + BattleSettleScenario.REAPER_SLACK.toMillis() - System.currentTimeMillis());
        report.note("等 scene 的 reaper：离战斗期限还有 " + Math.max(0, deadlineMs - System.currentTimeMillis()) / 1000 + " s，之后 10 s 宽限，"
                + "再加一个 reaper 间隔（切片 2 s；这里按缺省 30 s 的上界等，共 " + waitMs / 1000 + " s）");
        Optional<Received> end = a.connection().await(lobbyEndMark, r -> isEnd(r, x), Duration.ofMillis(waitMs));
        long endAt = end.map(r -> BattleCrashChecks.wallClockMillis(r.receivedNanos(), System.nanoTime(), System.currentTimeMillis())).orElse(0L);
        Landing landing = BattleCrashChecks.landingOf(endAt, deadlineMs, graceMs, ARRIVAL_TOLERANCE.toMillis());
        outcome = landing.wire();
        report.check(landing == Landing.RESCUED, "battle 死后没有人重投：期限 + 10 s 宽限之后 scene 的 reaper 读到本局记录并应用，大厅收到 150",
                switch (landing) {
                    case RESCUED -> "期限 + 10 s 之后 " + (endAt - graceEnd) + " ms 到达";
                    case EARLY -> "150 在期限 + 10 s 之前 " + (graceEnd - endAt) + " ms 就到了：不是 rescue（有别的路径投递了这笔结算）";
                    case MISSING -> "等到期限 + 10 s + " + BattleSettleScenario.REAPER_SLACK.toSeconds() + " s 都没有 150：已落库的结算没有到账"
                            + a.connection().describeSince(lobbyEndMark);
                }, REF_RESCUE);
        if (end.isPresent()) {
            BattleEndS2C body = end.get().parseOrNull(BattleEndS2C.parser());
            String mismatch = body == null ? "大厅 150 解析失败" : BattleSettleChecks.lobbyEndMismatch(body, directEnd);
            report.check(mismatch == null, "rescue 推的 150 与直连 150 逐字段相同（读的是 battle 落库的那一份）", mismatch == null ? "一致" : mismatch,
                    "scene-battle-spec §6.1");
        }
        long goldNow = gold(a);
        String problem = BattleCrashChecks.goldProblem(goldBefore, directEnd.getSettlement().getGoldGain(), goldNow);
        report.check(problem == null, "金币恰好增一次（开打前 + gold_gain）", problem == null ? goldBefore + " → " + goldNow + "，gold_gain="
                + directEnd.getSettlement().getGoldGain() : problem, REF_RESCUE);
        int gateTip = petGateTip(a);
        report.check(gateTip != TIP_PET_IN_BATTLE, "冻结已摘（185 不再回 26008）", "tip=" + gateTip, REF_RESCUE);
        String sceneNow = sceneBefore == null ? null : scrapeScene();
        if (sceneNow != null) {
            double applied = BattleSettleChecks.delta(sceneBefore, sceneNow, "xm_scene_battle_rescues_total", "result=\"applied\"");
            // 只抓了一个节点而没有增长：双 scene 切片上 A 可能落在另一个节点（这里只有一个号，见不到第二个节点，只能在细节里提示）
            report.check(applied >= 1, "scene 指标 xm_scene_battle_rescues_total{result=\"applied\"} 有增长", "增量 " + applied
                    + (applied < 1 && sceneMetricsUrls.size() < 2 ? "（只抓了 1 个 scene 节点：双 scene 切片上 A 可能在另一个节点，要把两个节点的"
                    + "管理端口都传给 --scene-metrics-url；经 tools/local/battle-crash-window.sh 跑时它会自动加）" : ""), "scene-battle-spec §9");
        } else {
            report.note("scene 指标没有抓到，rescues{applied} 不判");
        }
        BattleSupport.sleep(BattleSettleScenario.NO_REPEAT_WINDOW);
        long ends = a.connection().inbox().snapshot(lobbyEndMark).stream().filter(r -> isEnd(r, x)).count();
        long goldLater = gold(a);
        report.check(ends == (end.isPresent() ? 1 : 0) && goldLater == goldNow, "再等 10 s：没有第二条 150、金币不再变", ends + " 条 150，金币 "
                + goldNow + " → " + goldLater, REF_RESCUE);
        state = state.observed(endAt, landing.wire());
        BattleCrashChecks.write(stateFile, state);
    }

    private void verifyBattleAfterStore() {
        if (!step("state", () -> loadState(Variant.BATTLE_AFTER_STORE, Stage.OBSERVED))) {
            return;
        }
        outcome = state.outcome();
        if (!step("relogin", this::reloginAfterRestart)) {
            return;
        }
        step("no-resend", this::storeNoResend);
        step("released", this::frozenAndLockReleased);
    }

    /** battle 重启、A 重登之后：rescue 应用过的那一笔已经销账，不会重发；金币仍是恰好一次。 */
    private void storeNoResend() throws RobotException {
        long x = state.battleId();
        BattleSupport.sleep(BattleSettleScenario.NO_REPEAT_WINDOW);
        long ends = a.connection().inbox().snapshot(0).stream().filter(r -> isEnd(r, x)).count();
        report.check(ends == 0, "重登后 10 s 内没有本局的 150（rescue 应用的那一笔已销账，不重发）", ends + " 条", REF_RECOVERY);
        long goldNow = gold(a);
        String problem = BattleCrashChecks.goldProblem(state.goldBefore(), state.goldGain(), goldNow);
        report.check(problem == null, "金币仍是恰好增一次（开打前 + gold_gain）", problem == null ? state.goldBefore() + " → " + goldNow
                + "，gold_gain=" + state.goldGain() : problem, REF);
    }

    // ================================================================ 两个变体共用的步骤

    /**
     * 登录一个新号、领宝宝并出战、记下金币；先删掉上一轮的状态文件（脚本以「文件出现」为断点信号）。
     * 宝宝与 battle-settle 第 1 步相同（GM 187 领灵狐、183 出战）：只有打赢才有 gold_gain（引擎只在 SIDE_A_WIN 且本人存活时结金币），
     * 而金币是这两个变体见证「恰好一次」的那个量，battle-after-store 还要靠它撑开窗口。
     */
    private void loginFresh() throws RobotException {
        BattleCrashChecks.delete(stateFile);
        a = enter(armAccount);
        GmGrantPetResponse granted = a.call(grantPet, GmGrantPetRequest.newBuilder().setPetTableId(BattleSettleScenario.FOX).build(),
                GmGrantPetResponse.parser());
        SummonPetResponse summoned = a.call(summonPet, SummonPetRequest.newBuilder().setPetId(granted.getPetId()).build(),
                SummonPetResponse.parser());
        goldBefore = gold(a);
        report.check(granted.getErrorMessage().getId() == 0 && granted.getPetId() != 0 && summoned.getErrorMessage().getId() == 0
                        && summoned.getPets().getActivePetId() == granted.getPetId(), "A 登录进场，领灵狐并出战，记下金币",
                "A=" + uid(a.id()) + " 金币 " + goldBefore + " 187 tip=" + granted.getErrorMessage().getId() + " 183 tip="
                        + summoned.getErrorMessage().getId() + " battle-admin=" + admin.baseUrl(), REF);
    }

    /**
     * dev gather 建房 → 大厅 177 / 143 → 直连握手 → 162 挂机 → 直连收到 150。记下 {@link #directEnd} 与等大厅 150 的起点 {@link #lobbyEndMark}。
     * 这一局必须打赢且 gold_gain &gt; 0（{@link BattleCrashChecks#witnessProblem}），否则这一步算失败、调用方不往下走到断点。
     *
     * @param beforeMargin true = 必须在期限前 {@link #STORE_MARGIN} 打完（battle-after-store）；false = 按挂机上限等
     */
    private void fightToDirectEnd(long battleDeadlineMs, boolean beforeMargin) throws RobotException {
        battleId = battleIdSequence.next(System.currentTimeMillis());
        deadlineMs = battleDeadlineMs;
        long x = battleId;
        LobbyBot lobby = a.lobby();
        int lobbyMark = a.mark();
        GatherOutcome g = admin.gather(DevGather.solo(BattleAdminClient.GATHER_CREATE, x, BattleSettleScenario.MATCH_MODE_PVE,
                BattleSettleScenario.DUNGEON_1, BattleSettleScenario.SEED, battleDeadlineMs, deadline(BattleSettleScenario.PREPARE_DEADLINE), a.id()));
        report.check(g.ok() && g.created() != null && g.created().ok(), "dev gather CREATE：备战成功、建房 ADMITTED", "battle_id=" + uid(x) + " "
                + g.describe(), "scene-battle-spec §7.18");
        if (!g.ok()) {
            throw new RobotException("建房失败：" + g.describe());
        }
        BattleAssignedS2C assigned = lobby.awaitAssigned(lobbyMark, x).parse(BattleAssignedS2C.parser());
        lobby.awaitStart(lobbyMark, x);
        Direct direct = Direct.open(client, "A#direct", assigned.getHost(), assigned.getPort(), battleIds);
        closeables.add(direct);
        BattleDirectConnection.Handshake hs = direct.handshake(assigned);
        if (!hs.success()) {
            throw new RobotException("直连握手失败：" + BattleScenario.describe(hs.response()));
        }
        direct.awaitReply(0, battleIds.getBattleState(), hs.stateRequestId(), requestTimeout);
        lobbyEndMark = a.mark();
        int autoMark = direct.mark();
        direct.request(battleIds.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(x).setEnabled(true).build());
        Duration finish = BattleSettleScenario.AUTO_FINISH_TIMEOUT;
        if (beforeMargin) {
            finish = Duration.ofMillis(Math.max(1_000, battleDeadlineMs - STORE_MARGIN.toMillis() - System.currentTimeMillis()));
        }
        BattleFrame endFrame = direct.awaitPush(autoMark, battleIds.battleEnd(), finish);
        directEnd = endFrame.parse(BattleEndS2C.parser());
        report.check(directEnd.getBattleId() == x && directEnd.getSettlement().getPlayerId() == a.id(), "挂机打完：直连收到 150",
                "outcome=" + directEnd.getOutcome() + " rounds=" + directEnd.getSettlement().getTotalRounds() + " gold_gain="
                        + directEnd.getSettlement().getGoldGain() + (beforeMargin ? "，离战斗期限还有 " + (battleDeadlineMs - System.currentTimeMillis()) / 1000
                        + " s" : ""), REF);
        // 打输 / 平局 / 本人阵亡时 gold_gain = 0：金币见证不了「恰好一次」（scene-after-150 分不清整笔丢失与已落盘，battle-after-store 撑不开窗口）。
        // 这里失败 → fight 步不干净 → 两个变体的 arm 都在写断点之前返回，编排脚本不会杀进程
        String witness = BattleCrashChecks.witnessProblem(directEnd.getOutcome(), directEnd.getSettlement().getGoldGain());
        report.check(witness == null, "这一局打赢且 gold_gain > 0：金币见证得了「恰好一次」（否则不写断点）", witness == null ? "outcome="
                + directEnd.getOutcome() + " gold_gain=" + directEnd.getSettlement().getGoldGain() : witness, REF);
    }

    /**
     * 读状态文件并核对它能拿来下结论：{@code version} 是本版的判定版本（旧版 robot 写的在 {@link BattleCrashChecks#read} 里就被拒，抛出、同样停在
     * state 步）；属于这个变体、停在期望的阶段；这一局的 gold_gain &gt; 0（本版 arm 不会写出 0，防手改的文件：
     * gold_gain = 0 时 0 条 150、金币不变既可能是已落盘也可能是整笔丢失）；battle-after-store（期望阶段 observed）另要 arm 记下的结局是 rescued
     * （early / missing 时 arm 已失败，verify 自己的三步在 early 时照样会过）。任何一条不成立，调用方都停在 state 步、不去登录。
     */
    private void loadState(Variant expectedVariant, Stage expectedStage) throws RobotException {
        state = BattleCrashChecks.read(stateFile);
        battleId = state.battleId();
        boolean mine = state.variant() == expectedVariant && state.stage() == expectedStage;
        report.check(mine, "状态文件属于本变体、arm 阶段已走到 "
                + expectedStage.wire(), "variant=" + state.variant().wire() + " stage=" + state.stage().wire() + " battle_id=" + uid(state.battleId())
                + " account=" + state.account() + (state.stage() == Stage.ARMED && expectedStage == Stage.OBSERVED
                ? "（arm 阶段写了断点但没有看完 rescue 的结局就退出了）" : ""), REF);
        if (!mine) {
            // 别的变体 / 没走完的 arm 留下的文件：下面两条对它没有意义
            return;
        }
        String witness = BattleCrashChecks.goldWitnessProblem(state.goldGain());
        report.check(witness == null, "状态文件里这一局的 gold_gain > 0（金币见证得了「恰好一次」）", witness == null ? "gold_gain=" + state.goldGain()
                : witness, REF);
        if (expectedStage == Stage.OBSERVED) {
            String observed = BattleCrashChecks.observedProblem(state.outcome());
            report.check(observed == null, "arm 阶段观察到的结局是 rescued（期限 + 10 s 之后由 scene 的 reaper 应用）", observed == null
                    ? "outcome=" + state.outcome() : observed, REF_RESCUE);
        }
    }

    /** 进程重启之后重登（带重试），核对还是同一个角色。 */
    private void reloginAfterRestart() throws RobotException {
        a = enterWithRetry(state.account(), RELOGIN_BUDGET);
        report.check(a.id() == state.playerId(), "进程重启后 A 重新登录进场", "player_id=" + uid(a.id()) + "（状态文件 " + uid(state.playerId()) + "）",
                REF);
    }

    /** 结算落地之后：冻结已摘（185 不回 26008）、锁已放（再备战成功，随后取消）。 */
    private void frozenAndLockReleased() throws RobotException {
        int gateTip = petGateTip(a);
        report.check(gateTip != TIP_PET_IN_BATTLE, "闸已解除（185 不回 26008）", "tip=" + gateTip, "scene-battle-spec §7.13");
        String[] last = {""};
        long freedAt = pollUntil(LOCK_WAIT, Duration.ofSeconds(1), () -> {
            long probeId = battleIdSequence.next(System.currentTimeMillis());
            GatherOutcome probe = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, probeId, BattleSettleScenario.MATCH_MODE_PVE,
                    BattleSettleScenario.DUNGEON_1, BattleSettleScenario.SEED, deadline(BattleSettleScenario.LONG_DEADLINE),
                    deadline(BattleSettleScenario.PREPARE_DEADLINE), a.id()));
            last[0] = probe.describe();
            if (!probe.ok()) {
                return false;
            }
            preparedOnly.add(probeId);
            if (admin.cancelPrepare(a.id(), probeId).status() == 204) {
                preparedOnly.remove(Long.valueOf(probeId));
            }
            return true;
        });
        report.check(freedAt >= 0, "锁已放：" + LOCK_WAIT.toSeconds() + " s 内再备战成功（随后取消）", freedAt >= 0 ? "成功" : "一直被拒：" + last[0],
                "scene-battle-spec §7.12");
    }

    // ================================================================ 工具

    /** 一个已进场的机器人（大厅连接 + 节拍：gate 对同一个消息号缺省每秒 3 条）。只在场景线程上用。 */
    private final class Bot {
        final EnteredPlayer player;
        private final GuildScenario.Pacer pacer = new GuildScenario.Pacer(Duration.ofMillis(60), Duration.ofMillis(1200), 3);

        Bot(EnteredPlayer player) {
            this.player = player;
        }

        long id() {
            return player.playerId();
        }

        GameConnection connection() {
            return player.connection();
        }

        int mark() {
            return connection().inbox().size();
        }

        LobbyBot lobby() {
            return new LobbyBot("A", player, battleIds, requestTimeout);
        }

        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            long wait = pacer.delayNanos(messageId, System.nanoTime());
            if (wait > 0) {
                BattleSupport.sleep(Duration.ofNanos(wait));
            }
            pacer.record(messageId, System.nanoTime());
            return connection().call(messageId, body, parser, requestTimeout);
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws RobotException;
    }

    @FunctionalInterface
    private interface Condition {
        boolean test() throws RobotException;
    }

    /**
     * 跑一步；期间新增的失败检查与抛出的异常都记到它名下。返回这一步是否干净（没有失败、没有异常）：后面的步骤依赖它时据此决定要不要继续。
     */
    private boolean step(String name, Step body) {
        long failuresBefore = report.failures();
        long baseline = stepFailures.enter(failuresBefore);
        try {
            body.run();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断：" + name, message(e), REF);
        } finally {
            stepFailures.leave(name, baseline, report.failures());
        }
        return report.failures() == failuresBefore;
    }

    private Bot enter(String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        return new Bot(player);
    }

    /**
     * 进程刚重启时登录可能暂时不成（上一个持有者的归属租约 30 s 才过期、scene 的频道与 gate 链路还在恢复）：每 {@link #RELOGIN_RETRY} 重试一次，
     * 直到成功或超过 {@code budget}。
     */
    private Bot enterWithRetry(String account, Duration budget) throws RobotException {
        long giveUpAt = System.nanoTime() + budget.toNanos();
        for (int attempt = 1; ; attempt++) {
            try {
                Bot bot = enter(account);
                if (attempt > 1) {
                    report.note(account + " 第 " + attempt + " 次登录成功");
                }
                return bot;
            } catch (RobotException e) {
                if (System.nanoTime() + RELOGIN_RETRY.toNanos() >= giveUpAt) {
                    throw new RobotException(budget.toSeconds() + " s 内登录了 " + attempt + " 次都没有进场（最后一次：" + e.getMessage()
                            + "）；看被重启进程的日志（run/logs/）与 gate 日志", e);
                }
                BattleSupport.sleep(RELOGIN_RETRY);
            }
        }
    }

    /** 契约里的干净登出：发 LeaveGame 后关连接。 */
    private void leave(Bot bot) throws RobotException {
        bot.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        BattleSupport.sleep(Duration.ofMillis(200));
        bot.connection().close();
    }

    private long gold(Bot bot) throws RobotException {
        GetCurrencyListResponse response = bot.call(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), GetCurrencyListResponse.parser());
        if (response.getCurrency().getValuesCount() <= GOLD) {
            throw new RobotException("GetCurrencyList（54）没有金币槽");
        }
        return response.getCurrency().getValues(GOLD);
    }

    /**
     * 冻结探针：185 收回宝宝。宝宝的写操作先过战斗闸（在途回 26008、什么都不改）；不在战斗时照常收回（第二次起回「没有出战」）——
     * 只在这一局已经有结论之后才调用，收回宝宝不影响后面的核对。
     */
    private int petGateTip(Bot bot) throws RobotException {
        return bot.call(recallPet, RecallPetRequest.getDefaultInstance(), RecallPetResponse.parser()).getErrorMessage().getId();
    }

    private boolean isEnd(Received r, long x) {
        return r.messageId() == battleIds.battleEnd() && r.requestId() == 0
                && Optional.ofNullable(r.parseOrNull(BattleEndS2C.parser())).map(m -> m.getBattleId() == x).orElse(false);
    }

    /** 抓全部 scene 节点的指标（文本拼接即按节点求和）；任何一个抓不到就返回 null，只记观察。 */
    private String scrapeScene() {
        StringBuilder all = new StringBuilder();
        for (String url : sceneMetricsUrls) {
            try {
                all.append(SceneAdminClient.scrape(url, requestTimeout)).append('\n');
            } catch (RobotException e) {
                report.note("抓 scene 指标失败（scene 侧的指标不判）：" + e.getMessage());
                return null;
            }
        }
        return sceneMetricsUrls.isEmpty() ? null : all.toString();
    }

    /** 结束时金币还封着（中途失败）就尽力解封：新号每次都换，解不掉也不影响下一轮。 */
    private void unblockGoldQuietly() {
        if (!goldBlocked || a == null || !a.connection().isOpen()) {
            return;
        }
        try {
            a.call(gmUnblock, GmUnblockCurrencyRequest.newBuilder().setCurrencyType(GOLD).build(), GmUnblockCurrencyResponse.parser());
            goldBlocked = false;
        } catch (RobotException | RuntimeException ignored) {
            // 尽力而为
        }
    }

    private void cancelPreparedQuietly() {
        if (a == null) {
            return;
        }
        for (Long id : List.copyOf(preparedOnly)) {
            try {
                admin.cancelPrepare(a.id(), id);
            } catch (RobotException | RuntimeException ignored) {
                // 尽力而为：失败时冻结按备战期限由 reaper 摘
            }
            preparedOnly.remove(id);
        }
    }

    /** 每 {@code interval} 检查一次，直到成立或超过 {@code budget}。成立时返回那一刻的 Unix 毫秒，超时返回 −1。 */
    private static long pollUntil(Duration budget, Duration interval, Condition condition) throws RobotException {
        long giveUpAt = System.nanoTime() + Math.max(0, budget.toNanos());
        while (true) {
            if (condition.test()) {
                return System.currentTimeMillis();
            }
            if (System.nanoTime() >= giveUpAt) {
                return -1;
            }
            BattleSupport.sleep(interval);
        }
    }

    private static String describe(Replay replay) {
        return switch (replay) {
            case DURABLE -> "kill 落在落盘之后：账本已持久，重登不重发";
            case RECOVERED -> "kill 落在落盘之前：进场恢复按待结算记录重放了一次";
            case DUPLICATED -> "重复应用";
        };
    }

    private static long deadline(Duration fromNow) {
        return System.currentTimeMillis() + fromNow.toMillis();
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
