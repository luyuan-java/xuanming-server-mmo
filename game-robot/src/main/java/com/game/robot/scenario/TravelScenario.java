package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.ListSkillsRequest;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.TravelToZoneRequest;
import com.game.proto.login.EnterGameResponse;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.TeamResponse;
import com.game.robot.RobotOptions;
import com.game.robot.TravelOptions;
import com.game.robot.client.GameConnection;
import com.game.robot.client.GateEndpoint;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RedirectTarget;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnterOutcome;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.RedirectFollower;
import com.game.robot.flow.RedirectFollower.CloseOld;
import com.game.robot.flow.RedirectFollower.Followed;
import com.game.robot.flow.RoleZone;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.TravelLedger.Window;
import com.game.table.ConfigTables;
import com.game.table.CrossServerErrorTip;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨 zone 传送场景（批次 5.4，zone-travel-spec §11.11；基线 {@code robot/travel_smoke_scenario.go}）。三个子命令共用这一个类：
 * <ul>
 *   <li>{@code travel}（{@link Mode#ROUND_TRIP}）：从 {@code --zone}（出发区 H）传送到 {@code --visit-zone}（访客区 V）再回来，T0–T11；</li>
 *   <li>{@code travel-abandon}（{@link Mode#ABANDON}）：拿到 124 不跟随，A 经 H 的入口、B 经 V 的入口重登（R6 / R7）；</li>
 *   <li>{@code travel-long}（{@link Mode#LONG}）：在 V 断线、等过重连租约，再经 H 的入口重登（R4）。</li>
 * </ul>
 * 往返（{@code travel}）的步骤与断言（A 是旅客，B 是同场景的旁观者兼 T8 的冒用者）：
 * <ol>
 *   <li>T0 {@code preflight}：区服列表里 H、V 都是 OPEN，否则失败并写明「需要 GAME_ZONES=2」；</li>
 *   <li>T1 A、B 经 H 登录（B 尽量与 A 同场景，重登有次数上限，同 {@link CrossNodeScenario}）：A 的角色列表 {@code zone_id == H}；
 *       登录窗口内 0 条 124；A GM +11 后读回得出发值 G0（非 0、刚变过）；</li>
 *   <li>T2 226 {@code {0}}、{@code {H}}、{@code {V, 4000000000}}：依次 3024、3024、3007；无 124；金币不变；</li>
 *   <li>T3 A 建一人队 → 226 {@code {V}} → 3026（同步应答）→ 解散；无 124；</li>
 *   <li>T4 226 {@code {U}}（没部署的区）：应答 {@code {0}}，之后 23 {3027}；无 124；77 照常应答、实例没动；</li>
 *   <li>T5 226 {@code {V, --travel-scene-config}}：应答 {@code {0}}（字节 {@code 0a 00}）先于 124；B 收到 A 的 51（同场景时）；恰好一条 124，
 *       票据 {@code player_id == A}、{@code target_zone_id == zone_id == V}、到期 ∈ (now, now + 305]；跟随（握手成功后<b>晚</b>关旧连接）→
 *       严格重登（列表必须有 A、绝不建角）→ 角色列表里 A 的 {@code zone_id} 仍是 H、79 晚于 124、新连接的 gate 就是 124 给的；金币 G0，
 *       GM +7 → G0 + 7；T5a 晚关的那段时间里旧连接上发 77 没有回包（REDIRECTED）；</li>
 *   <li>T6 停留 {@code --dwell-ms}：没有 23 / 34 / 124；金币 G0 + 7；</li>
 *   <li>T7 GO-5：记下 V 的场景与坐标 → 直接关 TCP → 经 H 的入口重连：26 无错、随后恰好一条 124（目标 V、持票者 A）→ 跟随 → 回到原实例、
 *       坐标误差 &lt; 1 m、金币 G0 + 7；T7b 再关 TCP → 经 V 的入口直连：无 124、原实例；随后在 V 做一次移动探针（同基线，不判定）；</li>
 *   <li>T8 用 T7 的票据开新连接到 V 的 gate 验票 → 以 B 登录 → 26 进 B 的角色：2011（持票者不符）；T8b 同一张票据交给 H 的 gate：
 *       {@code success = false}、{@code token not for this zone}、连接被关；</li>
 *   <li>T9 连发两条 226 {@code {H}}：按信封 {@code id} 配对，第一条 {@code {0}}、第二条 13000（13000 可能先到）→ 一条 124 → 跟随（<b>先关旧连接再握手</b>）
 *       → 回到 H、金币 G0 + 7（不回档）；</li>
 *   <li>T10 17 登出 → 等 {@link Tempo#leaveSettle()}（离场是异步的：登出墓碑落下之前 V 的 login 读到的仍是 H 的在线记录，按规格走 GO-5）→
 *       经 V 的入口登录：无 124（79 之后再等一会儿），落在 V；最后 17；</li>
 *   <li>T11 留底：124 共 3 条（T5、T7、T9），每个窗口恰好一条、窗口里的 79 都在 124 之后；结果行
 *       {@code TRAVEL_SMOKE_OK player_id=… home_zone=… visit_zone=… home_gate=… visit_gate=… back_gate=… gold_home=… gold_back=… move_ack=…
 *       ticket_binding=… reject_tip=… reject_map_tip=… hops=… reconnect_gate=…}。</li>
 * </ol>
 * 跟随 124 三跳用了三种关旧连接的次序：T5 握手成功后晚 3 s 关、T7 握手成功即关、T9 握手之前就关（真实客户端的次序）——服务端两种都要支持。
 * tip 一律取生成的表枚举，消息号按名字从契约注册表解析。同一条连接上相邻请求至少隔 {@link Tempo#requestSpacing()}（缺省 1.1 s，
 * 避开 gate 按消息号每秒 3 条的限频）。每轮新号（{@code <前缀>tv<标签>_a / _b}），首登即在 H 建角，归属区一定是 H。
 *
 * <p>前置：{@code GAME_ZONES=2} 的切片（两个区各有 gate 与 scene，都在区服列表里）、dev 运行模式（GM 加金币）。
 * 不为「停在交接窗口」在服务端加后门：交出窗口内的时序由 game-scene 的单测覆盖（§11.11 末尾）。
 */
public final class TravelScenario {

    static final String REF = "zone-travel-spec §11.11";
    /** 226 所在的服务与方法；124 的见 {@link RedirectTarget#messageId}。两个号都按名字解析，不写死数字。 */
    static final String TRAVEL_SERVICE = "SceneSceneClientPlayer";
    static final String TRAVEL_METHOD = "TravelToZone";

    /** 一跳（受理 → 124、跟随之后等 79）的上限（基线 robot 每跳 60 s）。入口给 travel 的登录流程也用它当等 79 的上限。 */
    public static final Duration HOP_TIMEOUT = Duration.ofSeconds(60);

    static final int TIP_ZONE_INVALID = SceneErrorTip.scene_error.kZoneTravelTargetZoneNotFound_VALUE;
    static final int TIP_MAP_INVALID = SceneErrorTip.scene_error.kEnterSceneSceneNotFound_VALUE;
    static final int TIP_IN_TEAM = SceneErrorTip.scene_error.kZoneTravelInTeam_VALUE;
    static final int TIP_TRAVEL_FAILED = SceneErrorTip.scene_error.kZoneTravelTargetBusy_VALUE;
    static final int TIP_IN_TRANSFER = CrossServerErrorTip.cross_server_error.kSceneTransferInProgress_VALUE;
    static final int TIP_TICKET_HOLDER = LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE;
    /** 目标 gate 拒绝他区票据的文案（§1.7 第 6 步，五条文案之一，逐字保持）。 */
    static final String WRONG_ZONE_TEXT = "token not for this zone";
    /** World 表里一定没有的地图（uint32，按位放进 int）。 */
    static final int MISSING_MAP = (int) 4_000_000_000L;
    static final int GOLD = 0;
    static final long GOLD_AT_HOME = 11;
    static final long GOLD_AT_VISIT = 7;
    /** T7：回到原实例之后坐标的容差（米）。 */
    static final double RECONNECT_POSITION_TOLERANCE = 1.0;
    /** B 与 A 落到同一场景的尝试次数上限（含第一次）。 */
    static final int MAX_PLACEMENT_ATTEMPTS = 3;
    /** 移动探针的目标点：远在地图外（同基线 {@code probeMove}），服务端截断位移后回 137。 */
    static final Vec3 PROBE_POINT = new Vec3(100000, 100000, 0);

    /** 子模式，各有自己的结果行标记（大写字母与下划线，外层脚本按子串匹配）。 */
    public enum Mode {
        /** 子命令 {@code travel}。 */
        ROUND_TRIP("TRAVEL_SMOKE"),
        /** 子命令 {@code travel-abandon}。 */
        ABANDON("TRAVEL_ABANDON"),
        /** 子命令 {@code travel-long}。 */
        LONG("TRAVEL_LONG");

        private final String marker;

        Mode(String marker) {
            this.marker = marker;
        }

        /** 结果行的前缀：{@code <标记>_OK …} / {@code <标记>_FAIL step=… reason=…}。 */
        public String marker() {
            return marker;
        }

        /** 子命令对应的子模式。 */
        public static Mode of(RobotOptions.Scenario scenario) {
            return switch (scenario) {
                case TRAVEL -> ROUND_TRIP;
                case TRAVEL_ABANDON -> ABANDON;
                case TRAVEL_LONG -> LONG;
                default -> throw new IllegalArgumentException("不是 travel 的子命令：" + scenario);
            };
        }
    }

    /**
     * 节奏与各种上限。对真服务端用 {@link #standard}；单测对着本机假服务端调快。
     *
     * @param requestSpacing 同一条连接上相邻请求的最小间隔（1.1 s：gate 按消息号每秒 3 条限频）
     * @param syncReply      226 等应答的上限（10 s；服务端的延迟应答 ≤ 2 s）
     * @param hop            受理之后等 124 的上限（60 s）
     * @param failTip        T4 受理之后等 23 {3027} 的上限（10 s）
     * @param lateClose      T5 握手成功之后晚关旧连接的延迟（3 s）
     * @param quiet          「之后再没有 124」的静默窗口（3 s）
     * @param leaseWait      travel-long 断线之后等多久（31 s：越过 30 s 的重连租约）
     * @param observe        等旁人 51、自己 21、137 的上限
     * @param placementWait  B 与 A 不同场景时，B 登出后等多久再登（节点目录 5 s 刷新）
     * @param leaveSettle    T10 发 17 登出之后、经访客区入口登录之前等多久（3 s）。离场是异步的（gate → login → 出发区 scene 的逻辑线程
     *                       处理离场 → 写登出墓碑），墓碑落下之前访客区的 login 读到的仍是「出发区、节点号 ≠ 0」的在线记录，按规格走 GO-5
     *                       （回 26 再推 124，不夺权、不复查位置，§5.8）——那是正确行为，不是 R5 的反例。同区的 LeaveGame 紧跟 EnterGame
     *                       由 login 夺权后的复查兜住（{@code rerouteIfStale}），跨区的 GO-5 分支没有这道复查，只能等
     */
    record Tempo(Duration requestSpacing, Duration syncReply, Duration hop, Duration failTip, Duration lateClose, Duration quiet,
                 Duration leaseWait, Duration observe, Duration placementWait, Duration leaveSettle) {

        static Tempo standard(Duration observe) {
            Duration atLeast = Duration.ofSeconds(3);
            return new Tempo(Duration.ofMillis(1100), Duration.ofSeconds(10), HOP_TIMEOUT, Duration.ofSeconds(10), Duration.ofSeconds(3),
                    Duration.ofSeconds(3), Duration.ofSeconds(31), observe.compareTo(atLeast) > 0 ? observe : atLeast, Duration.ofSeconds(6),
                    Duration.ofSeconds(3));
        }
    }

    private final Mode mode;
    private final RobotClient homeClient;
    private final PlayerFlow homeFlow;
    private final RobotClient visitClient;
    private final PlayerFlow visitFlow;
    private final GatewayHttp gateway;
    private final RobotOptions options;
    private final TravelOptions travel;
    private final Tempo tempo;
    private final MessageIds ids;
    private final int travelToZone;
    private final int redirectToGate;
    private final int getCurrencyList;
    private final int gmAddCurrency;
    private final int createTeam;
    private final int disbandTeam;
    private final int leaveGame;
    private final int kickPlayer;
    private final int homeZone;
    private final int visitZone;
    private final String accountA;
    private final String accountB;
    private final CheckReport report = new CheckReport();
    private final StepTrack steps;
    private final TravelLedger ledger = new TravelLedger();
    private final List<GameConnection> opened = new ArrayList<>();
    /** 收尾时还要发 17 的连接（各账号最后所在的那条）。 */
    private final List<GameConnection> loggedIn = new ArrayList<>();
    /** 每条连接上次发请求的时刻（{@link System#nanoTime()}）。 */
    private final Map<GameConnection, Long> lastSend = new IdentityHashMap<>();

    /** 结果行的字段（没跑到的保持缺省）。 */
    private long playerA;
    private long playerB;
    private GateEndpoint homeGate;
    private GateEndpoint visitGate;
    private GateEndpoint backGate;
    private GateEndpoint reconnectGate;
    private long goldHome;
    private long goldBack;
    private boolean moveAck;
    private boolean ticketBinding = true;
    private int rejectTip;
    private int rejectMapTip;
    private int hops;
    private int pendingSceneConfig;
    private long waitedMillis;

    /**
     * @param homeClient  {@code --zone}（出发区，也是新号的归属区）的客户端
     * @param homeFlow    经出发区入口登录的流程
     * @param visitClient {@code --visit-zone}（访客区）的客户端
     * @param visitFlow   经访客区入口登录的流程
     * @param gateway     game-gateway 的 HTTP 接口（区服列表）
     * @param options     全部通用选项：子命令（定子模式）、两个区号、账号前缀与标签、各种超时、配置表目录
     * @param travel      travel 的附加选项（停留、去程地图）
     */
    public TravelScenario(RobotClient homeClient, PlayerFlow homeFlow, RobotClient visitClient, PlayerFlow visitFlow, GatewayHttp gateway,
                          MessageIdRegistry registry, RobotOptions options, TravelOptions travel) {
        this(homeClient, homeFlow, visitClient, visitFlow, gateway, registry, options, travel, Tempo.standard(options.observeTimeout()));
    }

    /** 单测对着本机假服务端时调快节奏与各种上限；对真服务端用上面的构造器。 */
    TravelScenario(RobotClient homeClient, PlayerFlow homeFlow, RobotClient visitClient, PlayerFlow visitFlow, GatewayHttp gateway,
                   MessageIdRegistry registry, RobotOptions options, TravelOptions travel, Tempo tempo) {
        if (options.zoneId() == options.visitZoneId()) {
            throw new IllegalArgumentException("travel 的两个区不能相同：" + options.zoneId());
        }
        this.mode = Mode.of(options.scenario());
        this.homeClient = homeClient;
        this.homeFlow = homeFlow;
        this.visitClient = visitClient;
        this.visitFlow = visitFlow;
        this.gateway = gateway;
        this.options = options;
        this.travel = travel;
        this.tempo = tempo;
        this.ids = MessageIds.resolve(registry);
        this.travelToZone = registry.requireId(TRAVEL_SERVICE, TRAVEL_METHOD);
        this.redirectToGate = RedirectTarget.messageId(registry);
        this.getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        this.gmAddCurrency = registry.requireId("SceneCurrencyClientPlayer", "GmAddCurrency");
        this.createTeam = registry.requireId("ClientPlayerTeam", "CreateTeam");
        this.disbandTeam = registry.requireId("ClientPlayerTeam", "DisbandTeam");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.kickPlayer = registry.requireId("SceneClientPlayerCommon", "KickPlayer");
        this.homeZone = options.zoneId();
        this.visitZone = options.visitZoneId();
        this.accountA = accountName(options.accountPrefix(), options.runTag(), "a");
        this.accountB = accountName(options.accountPrefix(), options.runTag(), "b");
        this.steps = new StepTrack(mode.marker());
    }

    /**
     * 账号：{@code 前缀 + tv + 标签 + _ + 后缀}（每轮新号、首登即在出发区建角，所以归属区一定是出发区）。三个子模式共用 {@code tv}，
     * 靠后缀区分；后缀取<b>一个字符</b>——{@code RobotOptions} 按它核对账号不超长。
     */
    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "tv" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public Mode mode() {
        return mode;
    }

    public CheckReport run() {
        try {
            switch (mode) {
                case ROUND_TRIP -> roundTrip();
                case ABANDON -> abandon();
                case LONG -> longStay();
            }
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", message(e), REF);
        } finally {
            closeAll();
        }
        return report;
    }

    /** 结果行（跑完 {@link #run} 之后取）：{@code TRAVEL_SMOKE_OK …} 或 {@code TRAVEL_SMOKE_FAIL step=… reason=…}（子模式换成各自的标记）。 */
    public String resultLine() {
        String fields = switch (mode) {
            case ROUND_TRIP -> TravelChecks.okFields(playerA, homeZone, visitZone, gate(homeGate), gate(visitGate), gate(backGate), goldHome,
                    goldBack, moveAck, ticketBinding, rejectTip, rejectMapTip, hops, gate(reconnectGate));
            case ABANDON -> "player_a=" + uid(playerA) + " player_b=" + uid(playerB) + " home_zone=" + homeZone + " visit_zone=" + visitZone
                    + " pending_scene_config=" + pendingSceneConfig + " gold_home=" + uid(goldHome);
            case LONG -> "player_id=" + uid(playerA) + " home_zone=" + homeZone + " visit_zone=" + visitZone + " visit_gate=" + gate(visitGate)
                    + " back_gate=" + gate(backGate) + " waited_ms=" + waitedMillis;
        };
        return steps.line(report, fields);
    }

    // ================================================================ travel：T0–T11

    private void roundTrip() throws RobotException {
        long started = System.nanoTime();
        preflight();

        // ---- T1：A、B 经出发区登录 ----
        steps.step("t1-login", report);
        EnteredPlayer a = login(homeFlow, accountA, "A-home");
        playerA = a.playerId();
        homeGate = a.gate();
        checkHomeZone("T1 A", a);
        ActorCreateS2C selfA = homeFlow.awaitSelfActor(a, tempo.observe());
        EnteredPlayer b = placeObserver(a);
        playerB = b.playerId();
        boolean sameScene = b.sceneInfo().getSceneId() == a.sceneInfo().getSceneId();
        report.note("A=" + uid(playerA) + " B=" + uid(playerB) + " 出发区 " + homeZone + "（gate " + homeGate + "） 访客区 " + visitZone
                + "；A " + CrossNodeScenario.describe(a.sceneInfo()) + "，B " + CrossNodeScenario.describe(b.sceneInfo()));
        GameConnection connA = a.connection();
        goldHome = startingGold("T1", connA);
        report.check(countOf(connA, 0, redirectToGate) == 0 && countOf(b.connection(), 0, redirectToGate) == 0,
                "T1 登录窗口内 0 条 124（124 = 上一轮的残留把人送走了）", "", REF + " T1");

        // ---- T2：同步拒绝 ----
        steps.step("t2-sync-reject", report);
        int mark = connA.inbox().size();
        rejectTip = TravelChecks.replyTip(requestTravel(connA, 0, 0));
        report.check(rejectTip == TIP_ZONE_INVALID, "T2 226 {target_zone_id = 0} → " + TIP_ZONE_INVALID + "（同步拒绝）",
                "error_message.id=" + rejectTip, REF + " T2；§1.5 第 2 行");
        int ownZoneTip = TravelChecks.replyTip(requestTravel(connA, homeZone, 0));
        report.check(ownZoneTip == TIP_ZONE_INVALID, "T2 226 {target_zone_id = 本区 " + homeZone + "} → " + TIP_ZONE_INVALID,
                "error_message.id=" + ownZoneTip, REF + " T2；§1.5 第 2 行");
        rejectMapTip = TravelChecks.replyTip(requestTravel(connA, visitZone, MISSING_MAP));
        report.check(rejectMapTip == TIP_MAP_INVALID, "T2 226 {" + visitZone + ", scene_config_id = " + Integer.toUnsignedString(MISSING_MAP)
                + "（不在 World 表）} → " + TIP_MAP_INVALID, "error_message.id=" + rejectMapTip, REF + " T2；§1.5 第 3 行");
        report.check(countOf(connA, mark, redirectToGate) == 0, "T2 同步拒绝之后没有 124", connA.describeSince(mark), REF + " T2");
        long gold = gold(connA);
        report.check(gold == goldHome, "T2 金币不变（== G0）", "金币 " + gold + "，G0=" + goldHome, REF + " T2");

        // ---- T3：在队即拒 ----
        steps.step("t3-in-team", report);
        mark = connA.inbox().size();
        TeamResponse created = call(connA, createTeam, CreateTeamRequest.getDefaultInstance(), TeamResponse.parser());
        long teamId = created.getTeam().getTeamId();
        must(tipOf(created.hasErrorMessage(), created.getErrorMessage()) == 0 && teamId != 0, "T3 A 建一人队",
                "tip=" + tipOf(created.hasErrorMessage(), created.getErrorMessage()) + " team_id=" + uid(teamId) + "（需要 game-team）");
        Received inTeam = requestTravel(connA, visitZone, 0);
        int inTeamTip = TravelChecks.replyTip(inTeam);
        report.check(inTeamTip == TIP_IN_TEAM, "T3 在队时 226 {" + visitZone + "} → " + TIP_IN_TEAM + "（同步应答）",
                "error_message.id=" + inTeamTip + "，发出到应答 " + millisSince(lastSend.get(connA), inTeam) + " ms", REF + " T3；X4");
        TeamResponse disbanded = call(connA, disbandTeam, DisbandTeamRequest.newBuilder().setExpectedTeamId(teamId).build(),
                TeamResponse.parser());
        must(tipOf(disbanded.hasErrorMessage(), disbanded.getErrorMessage()) == 0, "T3 解散一人队",
                "tip=" + tipOf(disbanded.hasErrorMessage(), disbanded.getErrorMessage()));
        report.check(countOf(connA, mark, redirectToGate) == 0, "T3 没有 124", connA.describeSince(mark), REF + " T3");

        // ---- T4：去没有部署的区 ----
        steps.step("t4-unknown-zone", report);
        unknownZone(a, b, selfA.getEntity(), sameScene);

        // ---- T5：去程 ----
        steps.step("t5-travel", report);
        long t5 = System.nanoTime();
        int markB = b.connection().inbox().size();
        Hop out = travelUntilRedirect("T5", connA, playerA, visitZone, travel.sceneConfigId());
        if (sameScene) {
            Optional<Received> destroyed = b.connection().await(markB, r -> CrossNodeScenario.destroys(ids, r, selfA.getEntity()),
                    tempo.observe());
            report.check(destroyed.isPresent(), "T5 同场景的 B 收到 A 的 51（源实例移除）",
                    destroyed.isPresent() ? "entity=" + selfA.getEntity() : tempo.observe().toMillis() + " ms 内没收到"
                            + b.connection().describeSince(markB), REF + " T5；§4.1");
        } else {
            report.note("T5 B 与 A 不在同一场景，跳过「B 收到 A 的 51」");
        }
        Followed followed = follow(new RedirectFollower(visitClient), connA, out.target(), CloseOld.AFTER_VERIFY, tempo.lateClose(), "A-visit");
        loggedIn.remove(connA);
        // T5a：晚关的那段时间里，旧连接上的请求不该有回包（gate 已把它置为 REDIRECTED）
        int oldMark = connA.inbox().size();
        long probeId = 0;
        String probeError = null;
        try {
            probeId = connA.send(ids.listSkills(), ListSkillsRequest.getDefaultInstance());
        } catch (RobotException e) {
            probeError = e.getMessage();
        }
        EnteredPlayer visitA = visitFlow.reenter(followed.connection(), accountA, playerA, new Timings());
        loggedIn.add(visitA.connection());
        visitGate = visitA.gate();
        checkLanding("T5", visitA, out, homeGate);
        if (travel.sceneConfigId() != 0) {
            report.check(visitA.sceneInfo().getSceneConfigId() == travel.sceneConfigId(), "T5 落在 226 请求的地图",
                    CrossNodeScenario.describe(visitA.sceneInfo()) + "，请求 scene_config_id=" + travel.sceneConfigId(), REF + " T5；MZ2");
        }
        ActorCreateS2C selfVisit = visitFlow.awaitSelfActor(visitA, tempo.observe());
        Vec3 visitAt = Vec3.of(selfVisit.getTransform().getLocation());
        boolean oldClosed = followed.awaitOldClosed(tempo.lateClose().plus(Duration.ofSeconds(5)));
        long oldRedirects = countOf(connA, out.mark(), redirectToGate);
        report.check(oldRedirects == 1, "T5 旧连接上恰好一条 124（一次传送一跳）",
                "受理之后旧连接上 124 共 " + oldRedirects + " 条", REF + " T5");
        GameConnection connVisit = visitA.connection();
        gold = gold(connVisit);
        report.check(gold == goldHome, "T5 到了访客区金币 == G0（交出的是冻结快照，不丢不多）", "金币 " + gold + "，G0=" + goldHome, REF + " T5");
        long plus = gmAddGold(connVisit, GOLD_AT_VISIT);
        report.check(plus == goldHome + GOLD_AT_VISIT, "T5 访客区可写：GM +" + GOLD_AT_VISIT + " → G0 + " + GOLD_AT_VISIT,
                "balance_after=" + plus + "，期望 " + (goldHome + GOLD_AT_VISIT), REF + " T5");

        steps.step("t5a-old-connection", report);
        List<Received> late = connA.inbox().snapshot(oldMark);
        long answerId = probeId;
        boolean answered = late.stream().anyMatch(r -> r.messageId() == ids.listSkills() && r.requestId() == answerId && answerId != 0);
        List<Integer> lateTips = tips(late);
        report.check(probeError == null && !answered && lateTips.isEmpty() && oldClosed,
                "T5a 握手成功后晚 " + tempo.lateClose().toMillis() + " ms 关旧连接，期间旧连接上发 77：没有回包、没有 23（会话已 REDIRECTED，X8）",
                probeError != null ? "旧连接上发 77 失败：" + probeError
                        : (answered ? "收到了 77 的应答" : "") + (lateTips.isEmpty() ? "" : " 收到 23 " + lateTips)
                        + (oldClosed ? "" : " 旧连接到点没关"), REF + " T5a");

        // ---- T6：停留 ----
        steps.step("t6-dwell", report);
        mark = connVisit.inbox().size();
        sleep(travel.dwell());
        List<Received> during = connVisit.inbox().snapshot(mark);
        List<Integer> dwellTips = tips(during);
        long dwellKicks = during.stream().filter(r -> r.messageId() == kickPlayer).count();
        long dwellRedirects = during.stream().filter(r -> r.messageId() == redirectToGate).count();
        report.check(connVisit.isOpen() && dwellTips.isEmpty() && dwellKicks == 0 && dwellRedirects == 0,
                "T6 在访客区停留 " + travel.dwell().toMillis() + " ms：没有 23 / 34 / 124，连接仍在",
                "23 " + dwellTips + "，34 " + dwellKicks + " 条，124 " + dwellRedirects + " 条" + connVisit.describeSince(mark), REF + " T6");
        gold = gold(connVisit);
        report.check(gold == goldHome + GOLD_AT_VISIT, "T6 停留之后金币 == G0 + " + GOLD_AT_VISIT, "金币 " + gold, REF + " T6");

        // ---- T7：GO-5，经出发区的入口重连 ----
        steps.step("t7-reconnect-home", report);
        SceneInfoComp recorded = visitA.sceneInfo();
        long t7 = System.nanoTime();
        connVisit.close();
        loggedIn.remove(connVisit);
        GameConnection connHome2 = connect(homeClient, "A-home-reconnect");
        EnterOutcome outcome = homeFlow.reenterOrRedirect(connHome2, accountA, playerA, new Timings());
        must(outcome instanceof EnterOutcome.Redirected, "T7 经出发区入口重连：26 无错，随后来的是 124（GO-5：角色在访客区，login 只送连接）",
                outcome instanceof EnterOutcome.Entered e ? "直接进了场：" + CrossNodeScenario.describe(e.player().sceneInfo())
                        + "（login 没有按位置记录把人送回访客区）" : "");
        EnterOutcome.Redirected go5 = (EnterOutcome.Redirected) outcome;
        Optional<Received> enterReply = lastReply(connHome2, ids.enterGame());
        report.check(enterReply.isPresent() && enterReply.get().index() < go5.frame().index(), "T7 26 的应答先于 124（先回包、再执行会话指令，X3）",
                enterReply.map(r -> "26 应答是第 " + r.index() + " 条下行，124 是第 " + go5.frame().index() + " 条").orElse("没找到 26 的应答"),
                REF + " T7");
        long now = epochSeconds();
        List<String> go5Problems = TravelChecks.ticketProblems(go5.target().ticket(), go5.target(), playerA, visitZone, now);
        ticketBinding &= go5Problems.isEmpty();
        report.check(go5Problems.isEmpty(), "T7 124 的票据：目标是访客区 " + visitZone + "、持票者是 A",
                TravelChecks.describe(go5.target().ticket()) + (go5Problems.isEmpty() ? "" : "；不符：" + String.join("；", go5Problems)), REF + " T7");
        RedirectTarget ticketT7 = go5.target();
        Followed back = follow(new RedirectFollower(visitClient), connHome2, ticketT7, CloseOld.AFTER_VERIFY, Duration.ZERO, "A-visit-go5");
        EnteredPlayer returned = visitFlow.reenter(back.connection(), accountA, playerA, new Timings());
        loggedIn.add(returned.connection());
        reconnectGate = returned.gate();
        report.check(returned.gate().equals(ticketT7.endpoint()), "T7 跟随后连的是 124 给的 gate", returned.gate() + "，124 给的是 "
                + ticketT7.endpoint(), REF + " T7");
        report.check(CrossNodeScenario.sameScene(returned.sceneInfo(), recorded), "T7 跟随后 79 是断线前的原实例（X18）",
                CrossNodeScenario.describe(returned.sceneInfo()) + "，断线前 " + CrossNodeScenario.describe(recorded), REF + " T7；R1 / R2");
        ActorCreateS2C selfReturned = visitFlow.awaitSelfActor(returned, tempo.observe());
        Vec3 returnedAt = Vec3.of(selfReturned.getTransform().getLocation());
        report.check(returnedAt.distance(visitAt) < RECONNECT_POSITION_TOLERANCE, "T7 回到断线前的坐标（误差 < " + RECONNECT_POSITION_TOLERANCE + " m）",
                "自己的 21 位置 " + returnedAt + "，断线前 " + visitAt, REF + " T7");
        gold = gold(returned.connection());
        report.check(gold == goldHome + GOLD_AT_VISIT, "T7 金币 == G0 + " + GOLD_AT_VISIT, "金币 " + gold, REF + " T7");

        // ---- T7b：经访客区的入口直连 ----
        steps.step("t7b-reconnect-visit", report);
        returned.connection().close();
        loggedIn.remove(returned.connection());
        GameConnection direct = connect(visitClient, "A-visit-direct");
        EnterOutcome directOutcome = visitFlow.reenterOrRedirect(direct, accountA, playerA, new Timings());
        must(directOutcome instanceof EnterOutcome.Entered, "T7b 经访客区入口直连重登：没有 124、直接进场（R3）",
                directOutcome instanceof EnterOutcome.Redirected r ? "收到了 124（目标 " + r.target().endpoint() + "）" : "");
        EnteredPlayer directA = ((EnterOutcome.Entered) directOutcome).player();
        loggedIn.add(direct);
        report.check(CrossNodeScenario.sameScene(directA.sceneInfo(), recorded), "T7b 79 仍是原实例",
                CrossNodeScenario.describe(directA.sceneInfo()) + "，期望 " + CrossNodeScenario.describe(recorded), REF + " T7b");
        moveAck = probeMove(direct);

        // ---- T8：冒用 T7 的票据 ----
        steps.step("t8-ticket-holder", report);
        ticketHolder(ticketT7);
        steps.step("t8b-wrong-zone", report);
        wrongZone(ticketT7);

        // ---- T9：连发两条 226 回程 ----
        steps.step("t9-double-travel", report);
        long t9 = System.nanoTime();
        EnteredPlayer home = doubleTravelHome(direct);

        // ---- T10：登出后经访客区入口登录 ----
        steps.step("t10-leave-enter-visit", report);
        leave(home.connection());
        long t10 = System.nanoTime();
        // 先等出发区处理完离场、写下登出墓碑（见 Tempo#leaveSettle）：早了访客区的 login 按规格会走 GO-5，测到的就不是 R5
        sleep(tempo.leaveSettle());
        GameConnection fresh = connect(visitClient, "A-visit-fresh");
        EnterOutcome freshOutcome = visitFlow.reenterOrRedirect(fresh, accountA, playerA, new Timings());
        must(freshOutcome instanceof EnterOutcome.Entered, "T10 登出（等 " + tempo.leaveSettle().toMillis()
                        + " ms 离场落定）后经访客区入口登录：没有 124、直接进场（窗口外按入口落地，R5）",
                freshOutcome instanceof EnterOutcome.Redirected r ? "收到了 124（目标 " + r.target().endpoint() + "）：访客区的 login 读到的位置记录"
                        + "仍是出发区在线，走了 GO-5——登出之后已等 " + tempo.leaveSettle().toMillis() + " ms，出发区的 scene 没写登出墓碑"
                        + "（17 被当成了断线、或离场没处理），或者离场处理得比这还慢（竞态，不一定是缺陷：看出发区 scene 的离场日志）" : "");
        loggedIn.add(fresh);
        sleep(tempo.quiet());
        report.check(countOf(fresh, 0, redirectToGate) == 0, "T10 79 之后再等 " + tempo.quiet().toMillis() + " ms 仍没有 124",
                fresh.describeSince(0), REF + " T10");
        leave(fresh);

        // ---- T11：留底 ----
        steps.step("t11-summary", report);
        summarize("T11 留底", List.of(
                Window.none("T1–T4 出发区", started, t5),
                Window.hop("T5 去程（含 T6 停留）", t5, t7),
                Window.hop("T7 经出发区重连（含 T7b、T8）", t7, t9),
                Window.hop("T9 回程", t9, t10),
                Window.none("T10 登出后经访客区登录", t10, Long.MAX_VALUE)), 3);
    }

    /** T1：B 经出发区登录，尽量与 A 同场景（不同就登出、等节点目录刷新再登，有次数上限）。 */
    private EnteredPlayer placeObserver(EnteredPlayer a) throws RobotException {
        EnteredPlayer b = login(homeFlow, accountB, "B-home");
        for (int attempt = 1; b.sceneInfo().getSceneId() != a.sceneInfo().getSceneId() && attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            report.note("T1 第 " + attempt + " 次：B " + CrossNodeScenario.describe(b.sceneInfo()) + " 与 A " + CrossNodeScenario.describe(a.sceneInfo())
                    + " 不同场景；B 发 LeaveGame 断开，等 " + tempo.placementWait().toMillis() + " ms 后重登");
            leave(b.connection());
            sleep(tempo.placementWait());
            b = login(homeFlow, accountB, "B-home-" + (attempt + 1));
        }
        return b;
    }

    /** T4：226 去一个没部署的区——受理之后 23 {3027}，留在原地。 */
    private void unknownZone(EnteredPlayer a, EnteredPlayer b, long entityA, boolean sameScene) throws RobotException {
        GameConnection connA = a.connection();
        int unknown = TravelChecks.unknownZone(options.runTag(), homeZone, visitZone);
        int mark = connA.inbox().size();
        int markB = b.connection().inbox().size();
        Received reply = requestTravel(connA, unknown, 0);
        int tip = TravelChecks.replyTip(reply);
        report.check(tip == 0 && TravelChecks.acceptedEncoding(reply), "T4 226 {target_zone_id = " + unknown
                        + "（没有部署的区）} → 应答 {0}（受理；scene 判不了目标区存在与否）",
                "error_message.id=" + tip + "，应答体 " + TravelChecks.hex(reply.content().getSerializedMessage()), REF + " T4；runbook D1");
        Optional<Received> failed = connA.await(mark, r -> r.messageId() == ids.sendTip() || r.messageId() == redirectToGate, tempo.failTip());
        boolean refused = failed.isPresent() && failed.get().messageId() == ids.sendTip()
                && CrossNodeScenario.tipId(failed.get()) == TIP_TRAVEL_FAILED;
        report.check(refused && failed.get().index() > reply.index(), "T4 受理之后收到 23 {" + TIP_TRAVEL_FAILED + "}，且在应答之后",
                failed.map(r -> r.messageId() == redirectToGate ? "收到的是 124（被送去了别处）"
                        : "23 {" + CrossNodeScenario.tipId(r) + "}；应答是第 " + reply.index() + " 条下行，23 是第 " + r.index() + " 条")
                        .orElse(tempo.failTip().toMillis() + " ms 内没收到" + connA.describeSince(mark)), REF + " T4；§4.2");
        ListSkillsResponse skills = call(connA, ids.listSkills(), ListSkillsRequest.getDefaultInstance(), ListSkillsResponse.parser());
        int skillsTip = tipOf(skills.hasErrorMessage(), skills.getErrorMessage());
        List<Received> since = connA.inbox().snapshot(mark);
        long enters = since.stream().filter(r -> r.messageId() == ids.notifyEnterScene()).count();
        boolean reCreated = since.stream().anyMatch(r -> CrossNodeScenario.findActor(ids, r, playerA) != null);
        boolean destroyed = sameScene && b.connection().inbox().snapshot(markB).stream()
                .anyMatch(r -> CrossNodeScenario.destroys(ids, r, entityA));
        report.check(skillsTip == 0 && enters == 0 && !reCreated && !destroyed && connA.isOpen(),
                "T4 留在原地照常可玩：77 照常应答，没有 79、没有自己的 21（实例没动、坐标不变）" + (sameScene ? "，B 没收到 A 的 51" : ""),
                "77 tip=" + skillsTip + "，79 " + enters + " 条" + (reCreated ? "，收到了自己的 21" : "") + (destroyed ? "，B 收到了 A 的 51" : "")
                        + (connA.isOpen() ? "" : "，连接已断开"), REF + " T4");
        report.check(countOf(connA, mark, redirectToGate) == 0, "T4 没有 124", "", REF + " T4");
    }

    /** T8：持 A 的票据在访客区的 gate 上握手、以 B 登录、进 B 的角色 → 2011。 */
    private void ticketHolder(RedirectTarget ticket) throws RobotException {
        Optional<String> problem = ticket.problem(epochSeconds());
        if (problem.isPresent()) {
            report.fail("T8 T7 的票据仍在有效期内", problem.get(), REF + " T8");
            return;
        }
        GameConnection holder = openRaw(visitClient, ticket.endpoint(), "B-with-A-ticket");
        try {
            ClientTokenVerifyResponse verified = holder.tryVerifyToken(ticket.tokenPayload(), ticket.tokenSignature(), visitClient.handshakeTimeout());
            report.check(verified.getSuccess(), "T8 T7 的票据在 300 s 内可以再次验票（票据不是一次性的，只认证连接，R-T5）",
                    verified.getSuccess() ? "" : "success=false error=" + verified.getError(), REF + " T8");
            if (!verified.getSuccess()) {
                return;
            }
            LoginResponse login = homeFlow.tryLogin(holder, accountB);
            List<RoleZone> roles = RoleZone.of(login.getPlayersList());
            boolean listed = roles.stream().anyMatch(role -> role.playerId() == playerB);
            report.check(!login.hasErrorMessage() && listed, "T8 在这条连接上以 B 的账号登录（48）：受理、角色列表里有 B",
                    "tip=" + tipOf(login.hasErrorMessage(), login.getErrorMessage()) + " 角色列表 " + roles, REF + " T8");
            if (!listed) {
                return;
            }
            EnterGameResponse entered = homeFlow.tryEnterGame(holder, playerB);
            int tip = tipOf(entered.hasErrorMessage(), entered.getErrorMessage());
            report.check(tip == TIP_TICKET_HOLDER, "T8 26 EnterGame(B 的角色) → " + TIP_TICKET_HOLDER + "（票据的持票者是 A，MZ6）",
                    "error_message.id=" + tip + (tip == 0 ? "（B 被放进了游戏：login 没有核对持票者）" : ""), REF + " T8；§5.8 第 2 步");
        } finally {
            holder.close();
        }
    }

    /** T8b：同一张票据交给出发区的 gate（节点号同为 1）→ 握手被拒、文案 {@value #WRONG_ZONE_TEXT}、连接被关。 */
    private void wrongZone(RedirectTarget ticket) throws RobotException {
        GameConnection wrong = openRaw(homeClient, homeGate, "A-ticket-at-home-gate");
        try {
            ClientTokenVerifyResponse response;
            try {
                response = wrong.tryVerifyToken(ticket.tokenPayload(), ticket.tokenSignature(), homeClient.handshakeTimeout());
            } catch (RobotException e) {
                report.fail("T8b 访客区的票据交给出发区的 gate：握手应答 success = false、" + WRONG_ZONE_TEXT, "没等到握手应答：" + e.getMessage(),
                        REF + " T8b；MZ7");
                return;
            }
            boolean closed = !response.getSuccess() && wrong.awaitClosed(homeClient.handshakeTimeout());
            String hint = response.getError().equals("token not for this gate")
                    ? "（两个区的 gate 节点号不同：这条用例要求同号，切片里两个区的第一台 gate 都是 1 号）" : "";
            report.check(!response.getSuccess() && response.getError().equals(WRONG_ZONE_TEXT) && closed,
                    "T8b 访客区的票据交给出发区的 gate（同为 1 号）：握手 success = false、" + WRONG_ZONE_TEXT + "、连接被服务端关闭",
                    "success=" + response.getSuccess() + " error=" + response.getError() + hint + (closed ? "" : "，连接没被关"), REF + " T8b；MZ7");
        } finally {
            wrong.close();
        }
    }

    /** T9：连发两条 226 回出发区，按信封 id 配对应答；跟随时先关旧连接再握手。返回回到出发区的进场结果。 */
    private EnteredPlayer doubleTravelHome(GameConnection connection) throws RobotException {
        pace(connection);
        int mark = connection.inbox().size();
        TravelToZoneRequest request = TravelToZoneRequest.newBuilder().setTargetZoneId(homeZone).build();
        long first = connection.send(travelToZone, request);
        long second = connection.send(travelToZone, request);
        lastSend.put(connection, System.nanoTime());
        Received firstReply = awaitReply(connection, mark, first, "T9 第一条 226");
        Received secondReply = awaitReply(connection, mark, second, "T9 第二条 226");
        int firstTip = TravelChecks.replyTip(firstReply);
        int secondTip = TravelChecks.replyTip(secondReply);
        report.check(firstTip == 0, "T9 连发两条 226 {" + homeZone + ", 0}：按信封 id 配对，第一条（id=" + first + "）{0}",
                "error_message.id=" + firstTip, REF + " T9；X21");
        report.check(secondTip == TIP_IN_TRANSFER, "T9 第二条（id=" + second + "）→ " + TIP_IN_TRANSFER + "（传送在途）",
                "error_message.id=" + secondTip, REF + " T9；X21");
        report.note("T9 两条应答的到达次序：" + (secondReply.index() < firstReply.index() ? "第二条的 " + secondTip + " 先到（X21：第一条是延迟应答）"
                : "按发送次序"));
        if (firstTip != 0) {
            throw new RobotException("T9 第一条 226 没有受理（" + firstTip + "），回程无从验证");
        }
        Received redirect = awaitRedirect("T9", connection, mark);
        report.check(firstReply.index() < redirect.index(), "T9 第一条的应答 {0} 先于 124", "应答是第 " + firstReply.index() + " 条下行，124 是第 "
                + redirect.index() + " 条", REF + " T9；X3");
        RedirectTarget target = checkedTarget("T9", redirect, playerA, homeZone);
        Followed followed = follow(new RedirectFollower(homeClient), connection, target, CloseOld.BEFORE_VERIFY, Duration.ZERO, "A-home-back");
        loggedIn.remove(connection);
        EnteredPlayer home = homeFlow.reenter(followed.connection(), accountA, playerA, new Timings());
        loggedIn.add(home.connection());
        backGate = home.gate();
        checkLanding("T9", home, new Hop(firstReply, redirect, target, mark), visitGate);
        noteOtherHomeGate("T9 回程", home.gate());
        long count = countOf(connection, mark, redirectToGate);
        report.check(count == 1, "T9 旧连接上恰好一条 124", "共 " + count + " 条", REF + " T9");
        goldBack = gold(home.connection());
        report.check(goldBack == goldHome + GOLD_AT_VISIT, "T9 回到出发区金币 == G0 + " + GOLD_AT_VISIT + "（访客区的写入带回来了，不回档）",
                "金币 " + goldBack, REF + " T9；CZ-2");
        return home;
    }

    /** T7b 之后：同基线 {@code probeMove} 在访客区发 134 + 131 到地图外，等 137。尽力而为、不判定（基线 {@code require_move_ack} 缺省 false）。 */
    private boolean probeMove(GameConnection connection) throws RobotException {
        int mark = connection.inbox().size();
        long now = System.currentTimeMillis();
        connection.send(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(PROBE_POINT.toLocation()).setClientTimeMs(now)
                .setInputSeq(1).build());
        connection.send(ids.moveStop(), MoveStopC2S.newBuilder().setEndLocation(PROBE_POINT.toLocation()).setClientTimeMs(now)
                .setInputSeq(2).build());
        Optional<Received> ack = connection.await(mark, r -> r.messageId() == ids.notifyMoveAck(), tempo.observe());
        report.note("移动探针（访客区，同基线 probeMove，不判定）：" + ack.map(r -> {
            MoveAckS2C body = r.parseOrNull(MoveAckS2C.parser());
            return body == null ? "收到 137（解不开）" : "收到 137 纠偏 input_seq=" + body.getInputSeq() + " → " + Vec3.of(body.getServerLocation());
        }).orElse(tempo.observe().toMillis() + " ms 内没收到 137"));
        return ack.isPresent();
    }

    // ================================================================ travel-abandon：R6 / R7

    private void abandon() throws RobotException {
        long started = System.nanoTime();
        preflight();
        pendingSceneConfig = pendingMap();

        // ---- A：拿到 124 不跟随 → 经出发区入口重登 ----
        steps.step("a1-login", report);
        EnteredPlayer a = login(homeFlow, accountA, "A-home");
        playerA = a.playerId();
        homeGate = a.gate();
        checkHomeZone("A", a);
        goldHome = startingGold("A", a.connection());
        steps.step("a2-travel-unfollowed", report);
        long ta = System.nanoTime();
        travelUntilRedirect("A", a.connection(), playerA, visitZone, pendingSceneConfig);
        a.connection().close();
        loggedIn.remove(a.connection());
        steps.step("a3-home-relogin", report);
        long ta2 = System.nanoTime();
        EnteredPlayer backA = strictRelogin("A 拿到 124 不跟随，经出发区入口严格重登：直接进场、没有 124，落在出发区（别处的待落点不牵引，R6）",
                homeClient, homeFlow, accountA, playerA, "A-home-relogin");
        noteOtherHomeGate("A 重登", backA.gate());
        long gold = gold(backA.connection());
        report.check(gold == goldHome, "A 金币 == 出发值（库里是交出时的冻结快照）", "金币 " + gold + "，出发值 " + goldHome, REF + " R6");
        leave(backA.connection());

        // ---- B：拿到 124 不跟随 → 经访客区入口重登 → 落待落点地图 ----
        steps.step("b1-login", report);
        EnteredPlayer b = login(homeFlow, accountB, "B-home");
        playerB = b.playerId();
        checkHomeZone("B", b);
        steps.step("b2-travel-unfollowed", report);
        long tb = System.nanoTime();
        travelUntilRedirect("B", b.connection(), playerB, visitZone, pendingSceneConfig);
        b.connection().close();
        loggedIn.remove(b.connection());
        steps.step("b3-visit-relogin", report);
        long tb2 = System.nanoTime();
        EnteredPlayer backB = strictRelogin("B 拿到 124 不跟随，经访客区入口严格重登：直接进场、没有 124", visitClient, visitFlow, accountB, playerB,
                "B-visit-relogin");
        report.check(backB.sceneInfo().getSceneConfigId() == pendingSceneConfig, "B 落在 226 请求的那张地图（待落点记的目标图，R7）",
                CrossNodeScenario.describe(backB.sceneInfo()) + "，请求 scene_config_id=" + pendingSceneConfig, REF + " R7；§5.8");
        leave(backB.connection());

        steps.step("summary", report);
        summarize("留底", List.of(
                Window.none("A 登录", started, ta),
                Window.unfollowed("A 去程（不跟随）", ta, ta2),
                Window.none("A 重登与 B 登录", ta2, tb),
                Window.unfollowed("B 去程（不跟随）", tb, tb2),
                Window.none("B 经访客区重登", tb2, Long.MAX_VALUE)), 2);
    }

    /**
     * travel-abandon 用的目标图：{@code --travel-scene-config} 非 0 就用它；否则取 World 表的第二张世界地图（要分得清「落在请求的地图」与
     * 「首登落默认主世界」，默认主世界不行）。
     */
    private int pendingMap() throws RobotException {
        if (travel.sceneConfigId() != 0) {
            return travel.sceneConfigId();
        }
        List<Integer> worlds;
        try {
            worlds = TeamScenario.worldConfigs(ConfigTables.load(Path.of(options.tableDir())));
        } catch (RuntimeException e) {
            throw new RobotException("travel-abandon 要一张非默认的地图：--travel-scene-config 没给，读 --table-dir " + options.tableDir()
                    + " 的 World 表也失败了：" + e);
        }
        if (worlds.size() < 2) {
            throw new RobotException("travel-abandon 要一张非默认的地图：World 表只有 " + worlds + "，给 --travel-scene-config");
        }
        report.note("目标图取 World 表的第二张世界地图 " + worlds.get(1) + "（默认主世界是 " + worlds.get(0) + "）");
        return worlds.get(1);
    }

    // ================================================================ travel-long：R4

    private void longStay() throws RobotException {
        long started = System.nanoTime();
        preflight();

        steps.step("l1-login", report);
        EnteredPlayer a = login(homeFlow, accountA, "A-home");
        playerA = a.playerId();
        homeGate = a.gate();
        checkHomeZone("A", a);
        goldHome = startingGold("A", a.connection());

        steps.step("l2-travel", report);
        long t = System.nanoTime();
        Hop out = travelUntilRedirect("去程", a.connection(), playerA, visitZone, travel.sceneConfigId());
        Followed followed = follow(new RedirectFollower(visitClient), a.connection(), out.target(), CloseOld.AFTER_VERIFY, Duration.ZERO,
                "A-visit");
        loggedIn.remove(a.connection());
        EnteredPlayer visitA = visitFlow.reenter(followed.connection(), accountA, playerA, new Timings());
        visitGate = visitA.gate();
        checkLanding("去程", visitA, out, homeGate);

        steps.step("l3-disconnect-wait", report);
        long closedAt = System.nanoTime();
        visitA.connection().close();
        sleep(tempo.leaseWait());
        waitedMillis = (System.nanoTime() - closedAt) / 1_000_000;

        steps.step("l4-home-relogin", report);
        long t2 = System.nanoTime();
        EnteredPlayer back = strictRelogin("在访客区断线 " + waitedMillis + " ms（越过重连租约）后经出发区入口严格重登：直接进场、没有 124，"
                + "落在出发区（R4）", homeClient, homeFlow, accountA, playerA, "A-home-relogin");
        backGate = back.gate();
        noteOtherHomeGate("重登", back.gate());
        long gold = gold(back.connection());
        report.check(gold == goldHome, "金币 == 出发值（数据随传送走、回来不丢）", "金币 " + gold + "，出发值 " + goldHome, REF + " R4");
        leave(back.connection());

        steps.step("summary", report);
        summarize("留底", List.of(
                Window.none("登录", started, t),
                Window.hop("去程", t, t2),
                Window.none("断线久等后经出发区重登", t2, Long.MAX_VALUE)), 1);
    }

    // ================================================================ 共用步骤

    /** T0：区服列表里两个区都是 OPEN。 */
    private void preflight() throws RobotException {
        steps.step("preflight", report);
        JsonNode serverList = gateway.get("/api/server-list");
        String problem = BattleCrossZoneChecks.zonesProblem(serverList, homeZone, visitZone);
        must(problem == null, "T0 区服列表里区 " + homeZone + "、区 " + visitZone + " 都是 OPEN（缺区即失败、不跳过：需要 GAME_ZONES=2 的切片）",
                problem == null ? serverList.path("zones").toString() : problem);
    }

    /** 角色列表里本人的 {@code zone_id} == 出发区（归属区登记；建角取会话 zone，X16）。 */
    private void checkHomeZone(String who, EnteredPlayer player) {
        report.check(player.homeZoneId() == homeZone, who + " 的角色列表 zone_id == 出发区 " + homeZone + "（归属区登记，X16）",
                "角色列表 " + player.roles() + (player.created() ? "（本次新建）" : ""), REF + " T1；§3.2");
    }

    /** GM 加 {@value #GOLD_AT_HOME} 金币后读回，得出发值（非 0、刚变过）。 */
    private long startingGold(String who, GameConnection connection) throws RobotException {
        long before = gold(connection);
        long after = gmAddGold(connection, GOLD_AT_HOME);
        long read = gold(connection);
        report.check(read == before + GOLD_AT_HOME && after == read && read != 0,
                who + " GM 加金币 " + GOLD_AT_HOME + "（37）后读回（54）得出发值 G0：非 0、刚变过",
                "加之前 " + before + "，balance_after=" + after + "，读回 " + read, REF + " T1");
        return read;
    }

    /** 一跳的发起：226 的应答、到达的 124 与解出的目标、发 226 之前连接上的位置。 */
    private record Hop(Received reply, Received redirect, RedirectTarget target, int mark) {
    }

    /**
     * 发 226 并等到 124：应答 {@code {0}}（字节 {@code 0a 00}）先于 124；124 的内容本地可用、票据各项对得上。
     * 受理后来的是 23（传送失败）或等不到 124 都中止。
     */
    private Hop travelUntilRedirect(String label, GameConnection connection, long playerId, int toZone, int sceneConfigId)
            throws RobotException {
        int mark = connection.inbox().size();
        Received reply = requestTravel(connection, toZone, sceneConfigId);
        int tip = TravelChecks.replyTip(reply);
        must(tip == 0, label + " 226 {" + toZone + ", " + sceneConfigId + "} → 应答 {0}（已受理）", "error_message.id=" + tip);
        report.check(TravelChecks.acceptedEncoding(reply), label + " 受理应答体恰好是 0a 00（error_message 恒存在、受理时为空）",
                "应答体 " + TravelChecks.hex(reply.content().getSerializedMessage()), REF + "；§4.5");
        Received redirect = awaitRedirect(label, connection, mark);
        report.check(reply.index() < redirect.index(), label + " 226 应答 {0} 先于 124（同一条链路，X3）",
                "应答是第 " + reply.index() + " 条下行，124 是第 " + redirect.index() + " 条", REF + "；X3");
        RedirectTarget target = checkedTarget(label, redirect, playerId, toZone);
        return new Hop(reply, redirect, target, mark);
    }

    /** 从 {@code mark} 起等 124；先等到的是 23 或者等不到都中止。 */
    private Received awaitRedirect(String label, GameConnection connection, int mark) throws RobotException {
        Optional<Received> first = connection.await(mark, r -> r.messageId() == redirectToGate || r.messageId() == ids.sendTip(), tempo.hop());
        boolean arrived = first.isPresent() && first.get().messageId() == redirectToGate;
        must(arrived, label + " 受理之后 " + tempo.hop().toSeconds() + " s 内收到 124",
                arrived ? "本连接第 " + first.get().index() + " 条下行"
                        : first.map(r -> "先到的是 23 {" + CrossNodeScenario.tipId(r) + "}（传送没成）" + connection.describeSince(mark))
                                .orElse("没有收到" + connection.describeSince(mark)));
        return first.get();
    }

    /** 解 124、本地校验、核对票据。 */
    private RedirectTarget checkedTarget(String label, Received redirect, long playerId, int toZone) throws RobotException {
        RedirectTarget target = RedirectTarget.parse(redirect);
        long now = epochSeconds();
        Optional<String> problem = target.problem(now);
        must(problem.isEmpty(), label + " 124 的地址与票据本地可用（地址非空、端口合法、票据非空、未到期）", problem.orElse(target.toString()));
        GateTokenPayload ticket = target.ticket();
        List<String> problems = TravelChecks.ticketProblems(ticket, target, playerId, toZone, now);
        ticketBinding &= problems.isEmpty();
        report.check(problems.isEmpty(), label + " 票据：player_id == 本人、target_zone_id == zone_id == " + toZone
                        + "、到期 ∈ (now, now + 305] 且等于 token_deadline、不带会话密钥",
                TravelChecks.describe(ticket) + (problems.isEmpty() ? "" : "；不符：" + String.join("；", problems)), REF + "；§1.4、§4.5");
        return target;
    }

    /** 跟随之后的落地：连的是 124 给的 gate（与 {@code previousGate} 不同）、角色列表里的归属区不变、79 晚于 124。 */
    private void checkLanding(String label, EnteredPlayer landed, Hop hop, GateEndpoint previousGate) {
        report.check(landed.gate().equals(hop.target().endpoint()) && !landed.gate().equals(previousGate),
                label + " 新连接连的是 124 给的 gate（换了 gate）", landed.gate() + "，124 给的是 " + hop.target().endpoint() + "，之前在 " + previousGate,
                REF);
        report.check(landed.homeZoneId() == homeZone, label + " 跟随后角色列表里的 zone_id 仍是出发区 " + homeZone + "（传送不改归属区，CZ-2）",
                "角色列表 " + landed.roles(), REF + "；§3.4");
        Optional<Received> enter = landed.connection().inbox().snapshot(0).stream()
                .filter(r -> r.messageId() == ids.notifyEnterScene()).findFirst();
        report.check(enter.isPresent() && enter.get().receivedNanos() > hop.redirect().receivedNanos(), label + " 79 在 124 之后到达",
                enter.map(r -> "79 晚于 124 " + (r.receivedNanos() - hop.redirect().receivedNanos()) / 1_000_000 + " ms").orElse("新连接上没有 79"),
                REF);
    }

    /**
     * 回到出发区时连的 gate 与出发时的不同：只记观察。出发区有不止一台 gate 时 assign-gate 按人数挑，换一台是正常的——
     * 「落在出发区」的证据是经出发区的入口（或 124 给的出发区地址）直接进场、没有 124，不是 gate 端点相等。
     */
    private void noteOtherHomeGate(String label, GateEndpoint landed) {
        if (!landed.equals(homeGate)) {
            report.note(label + "连的 gate " + landed + " 与出发时的 " + homeGate + " 不同（出发区有不止一台 gate）");
        }
    }

    /**
     * 自己建连（assign-gate → 握手）后严格重登，要求直接进场（没有 124），再等一个静默窗口确认之后也没有 124。
     * 经某个区的入口直接进场、没有 124，就是落在那个区（gate 只把会话送进本区的 scene；别处有位置记录时 login 回的是 124）。
     */
    private EnteredPlayer strictRelogin(String name, RobotClient client, PlayerFlow flow, String account, long playerId, String label)
            throws RobotException {
        GameConnection connection = connect(client, label);
        EnterOutcome outcome = flow.reenterOrRedirect(connection, account, playerId, new Timings());
        must(outcome instanceof EnterOutcome.Entered, name,
                outcome instanceof EnterOutcome.Redirected r ? "收到了 124（目标 " + r.target().endpoint() + "）" : "");
        loggedIn.add(connection);
        sleep(tempo.quiet());
        long redirects = countOf(connection, 0, redirectToGate);
        report.check(redirects == 0, name + "——进场之后再等 " + tempo.quiet().toMillis() + " ms 仍没有 124", "124 " + redirects + " 条", REF);
        return ((EnterOutcome.Entered) outcome).player();
    }

    /** 留底的总账：每个窗口的 124 与 79、全程 124 的条数。 */
    private void summarize(String name, List<Window> windows, int expectedRedirects) {
        List<TravelLedger.Entry> timeline = ledger.timeline();
        List<String> problems = TravelLedger.problems(timeline, windows, redirectToGate, ids.notifyEnterScene());
        long total = TravelLedger.count(timeline, redirectToGate);
        report.check(problems.isEmpty() && total == expectedRedirects,
                name + "：124 共 " + expectedRedirects + " 条，每个窗口恰好一条（或没有）、窗口里的 79 都在 124 之后",
                "连接 " + ledger.size() + " 条，下行 " + timeline.size() + " 条，124 共 " + total + " 条"
                        + (problems.isEmpty() ? "" : "；" + String.join("；", problems)), REF + "；对照基线 travel_smoke_scenario.go:435-479、:737-799");
    }

    // ================================================================ 连接与收发

    private EnteredPlayer login(PlayerFlow flow, String account, String label) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        register(label, player.connection());
        loggedIn.add(player.connection());
        return player;
    }

    /** assign-gate → TCP → 握手（不登录）。 */
    private GameConnection connect(RobotClient client, String label) throws RobotException {
        GameConnection connection = client.connect(client.assignGate());
        register(label, connection);
        return connection;
    }

    /** 只建 TCP、不握手。 */
    private GameConnection openRaw(RobotClient client, GateEndpoint gate, String label) throws RobotException {
        GameConnection connection = client.openRaw(gate.host(), gate.port());
        register(label, connection);
        return connection;
    }

    private Followed follow(RedirectFollower follower, GameConnection old, RedirectTarget target, CloseOld closeOld, Duration delay,
                            String label) throws RobotException {
        Followed followed = follower.follow(old, target, closeOld, delay);
        register(label, followed.connection());
        hops++;
        return followed;
    }

    private void register(String label, GameConnection connection) {
        ledger.add(label, connection);
        opened.add(connection);
    }

    /** 契约里的干净登出：发完 17 立即关连接。 */
    private void leave(GameConnection connection) throws RobotException {
        connection.send(leaveGame, LeaveGameRequest.getDefaultInstance());
        connection.close();
        loggedIn.remove(connection);
    }

    private void closeAll() {
        for (GameConnection connection : loggedIn) {
            try {
                if (connection.isOpen()) {
                    connection.send(leaveGame, LeaveGameRequest.getDefaultInstance());
                }
            } catch (RobotException | RuntimeException ignored) {
                // 尽力而为：关连接本身就是离线信号
            }
        }
        opened.forEach(GameConnection::close);
    }

    /** 发 226 并按信封 id 等应答（{@link Tempo#syncReply()}）。 */
    private Received requestTravel(GameConnection connection, int toZone, int sceneConfigId) throws RobotException {
        pace(connection);
        int mark = connection.inbox().size();
        long id = connection.send(travelToZone, TravelToZoneRequest.newBuilder().setTargetZoneId(toZone).setSceneConfigId(sceneConfigId).build());
        lastSend.put(connection, System.nanoTime());
        return awaitReply(connection, mark, id, "226 {" + Integer.toUnsignedString(toZone) + ", " + Integer.toUnsignedString(sceneConfigId) + "}");
    }

    private Received awaitReply(GameConnection connection, int mark, long id, String what) throws RobotException {
        Optional<Received> reply = connection.await(mark, r -> r.messageId() == travelToZone && r.requestId() == id, tempo.syncReply());
        if (reply.isEmpty()) {
            throw new RobotException(what + "（id=" + id + "）" + tempo.syncReply().toMillis() + " ms 内没有应答" + connection.describeSince(mark));
        }
        return reply.get();
    }

    private <T extends Message> T call(GameConnection connection, int messageId, Message body, Parser<T> parser) throws RobotException {
        pace(connection);
        try {
            return connection.call(messageId, body, parser, options.requestTimeout());
        } finally {
            lastSend.put(connection, System.nanoTime());
        }
    }

    /** 同一条连接上相邻请求至少隔 {@link Tempo#requestSpacing()}。 */
    private void pace(GameConnection connection) throws RobotException {
        Long last = lastSend.get(connection);
        if (last != null) {
            long wait = tempo.requestSpacing().toNanos() - (System.nanoTime() - last);
            if (wait > 0) {
                sleep(Duration.ofNanos(wait));
            }
        }
    }

    private long gold(GameConnection connection) throws RobotException {
        GetCurrencyListResponse response = call(connection, getCurrencyList, GetCurrencyListRequest.getDefaultInstance(),
                GetCurrencyListResponse.parser());
        int tip = tipOf(response.hasErrorMessage(), response.getErrorMessage());
        if (tip != 0) {
            throw new RobotException("查余额（54）回 " + tip);
        }
        List<Long> values = response.getCurrency().getValuesList();
        return values.size() > GOLD ? values.get(GOLD) : 0;
    }

    private long gmAddGold(GameConnection connection, long amount) throws RobotException {
        GmAddCurrencyResponse response = call(connection, gmAddCurrency,
                GmAddCurrencyRequest.newBuilder().setCurrencyType(GOLD).setAmount(amount).build(), GmAddCurrencyResponse.parser());
        int tip = tipOf(response.hasErrorMessage(), response.getErrorMessage());
        if (tip != 0) {
            throw new RobotException("GM 加金币（37）回 " + tip + "（travel 要 dev 运行模式）");
        }
        return response.getBalanceAfter();
    }

    /** 连接上最后一条消息号为 {@code messageId} 的应答（{@code id ≠ 0}）。 */
    private static Optional<Received> lastReply(GameConnection connection, int messageId) {
        Received last = null;
        for (Received r : connection.inbox().snapshot(0)) {
            if (r.messageId() == messageId && r.requestId() != 0) {
                last = r;
            }
        }
        return Optional.ofNullable(last);
    }

    private static long countOf(GameConnection connection, int mark, int messageId) {
        return connection.inbox().snapshot(mark).stream().filter(r -> r.messageId() == messageId).count();
    }

    private List<Integer> tips(List<Received> received) {
        List<Integer> out = new ArrayList<>();
        for (Received r : received) {
            if (r.messageId() == ids.sendTip()) {
                out.add(CrossNodeScenario.tipId(r));
            }
        }
        return out;
    }

    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后面的步骤依赖它");
        }
    }

    private static int tipOf(boolean present, TipInfoMessage tip) {
        return present ? tip.getId() : 0;
    }

    private static long millisSince(long startNanos, Received r) {
        return Math.max(0, (r.receivedNanos() - startNanos) / 1_000_000);
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    private static String gate(GateEndpoint endpoint) {
        return endpoint == null ? "-" : endpoint.toString();
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static void sleep(Duration duration) throws RobotException {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
