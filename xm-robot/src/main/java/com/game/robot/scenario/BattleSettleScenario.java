package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AutoAllocateAttributePointsRequest;
import com.game.proto.AutoAllocateAttributePointsResponse;
import com.game.proto.BagInfo;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattleReconnectS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.GetAttributePanelRequest;
import com.game.proto.GetAttributePanelResponse;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.GetCurrencyListRequest;
import com.game.proto.GetCurrencyListResponse;
import com.game.proto.GetMissionListRequest;
import com.game.proto.GetMissionListResponse;
import com.game.proto.GetPetListRequest;
import com.game.proto.GetPetListResponse;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MissionActionRequest;
import com.game.proto.MoveStartC2S;
import com.game.proto.PetInfo;
import com.game.proto.PlayerMissionInfo;
import com.game.proto.PlayerSkillComp;
import com.game.proto.RecallPetRequest;
import com.game.proto.RecallPetResponse;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SceneInfoComp;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SortBagRequest;
import com.game.proto.SortBagResponse;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SummonPetRequest;
import com.game.proto.SummonPetResponse;
import com.game.proto.Vector3;
import com.game.proto.eBattleOutcome;
import com.game.proto.login.LeaveGameRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.GetMyTeamRequest;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamView;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.BattleAdminClient.DevGather;
import com.game.robot.client.BattleAdminClient.GatherOutcome;
import com.game.robot.client.BattleAdminClient.PrepareResult;
import com.game.robot.client.BattleDirectConnection;
import com.game.robot.client.BattleFrame;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.BattleSettleChecks.BattleIdSequence;
import com.game.robot.scenario.BattleSettleChecks.StepFailures;
import com.game.robot.scenario.BattleSupport.Direct;
import com.game.robot.scenario.BattleSupport.LobbyBot;
import com.game.table.AttributeErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.ConfigTables;
import com.game.table.PetErrorTip;
import com.game.table.SceneErrorTip;
import com.game.table.SkillErrorTip;
import com.game.table.TeamErrorTip;
import com.game.table.WorldTable;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * scene 侧战斗冻结与结算应用的端到端（批次 6.3，scene-battle-spec §13.8「battle-settle」；6.4 的 match 之前经 xm-battle 的 dev gather 真实备战）。
 * 两个新号：A（参战者）、B（同场景观察者）。按客户端契约核对：
 * <ol>
 *   <li>第 1 步 准备：A、B 进同一场景；A 用 GM 187 领宝宝并 183 出战、194 接任务 12；</li>
 *   <li>第 2 步 大厅上发 149 / 140 / 162 / 165 → 每条 23 {1003}、不断连；</li>
 *   <li>第 3 步 只备战：快照逐项核对（player_id、等级、速度、上限、路由、指纹、道具、宝宝、技能）；B 在备战那一刻收到 A 速度 0 的 66（D5）；</li>
 *   <li>第 4 步 在途闸（PREPARING）：63 → 3023、168 → 25011、185 / 187 → 26008、192 → 1005、84 → 7004、B 对 A 放 84 → 7002、
 *       173 与 194 照常、134 静默丢（B 1 s 内看不到 A 的位置变化）、再备战 → 1006；</li>
 *   <li>第 5 步 取消：闸立即解除、再取消幂等、再备战成功后取消；{@code --slow} 另跑备战到期（reaper 只摘冻结、锁留到备战 TTL）；</li>
 *   <li>第 6 步 完整一局：dev gather 建房 → 大厅 177 后 143 → 直连握手 → 断开大厅重登收到 144 且 63 仍 3023 → 补签重连直连 → 162 挂机打完
 *       → 直连 150，大厅随后 184（有宝宝条目时）先于 150，大厅 150 与直连逐字段相同；</li>
 *   <li>第 7 步 结算效果：54 金币增量 = gold_gain、背包按掉落 / 消耗变化、167 气血、181 宝宝气血、193 任务 12 可领 → 195 领取、重复被拒、
 *       闸解除（168、63）、锁已放（2 s 内再备战成功）；</li>
 *   <li>第 8 步 重登不重发：金币、背包、任务原样，10 s 内没有第二条 150；</li>
 *   <li>第 9 步 离线结算：开局挂机后立即断开大厅与直连，等房间打完（{@code --slow} 再等过重投窗口 130 s）→ 登录收到 150、金币只增一次；</li>
 *   <li>第 10 步 确认后销毁（B1）：FIGHTING 拒绝取消、销毁后没有 150、63 一直 3023 直到期限 + 10 s 宽限后 reaper 判废；</li>
 *   <li>第 11 步 与 5.2 互斥（只在双 scene 切片上跑，排在第 5 步之后）：PREPARING 时 63 指向另一节点上的同图频道 → 应答里就是 3023（不是 3014）、
 *       A 留在原地；取消后同一条 63 跨节点成功。目标取 B 登录时所在的频道（判据同 {@link CrossNodeScenario#onDifferentChannels}）；
 *       {@code --scene-metrics-url} 给了两个地址（= 声明双 scene 切片）而 A、B 恰好同频道时，另用探针账号 C 落位（次数与间隔同 cross-node）。
 *       找不到另一个节点上的频道：声明了双 scene 就判失败，否则跳过并写观察记录；</li>
 *   <li>第 12 步 队伍视图（xm-team 的 {@code TeamMemberView.in_battle} 由战斗锁算出）：A 建单人队，备战前 false → 只备战后 true → 取消后 false；
 *       第 6 步确认之后（FIGHTING）true、第 7 步销账放锁之后变回 false（这两次嵌在第 6–7 步里采样，失败记在第 12 步名下）；</li>
 *   <li>第 13 步 指标：scene 结算应用 / 销账放锁有增长、在途闸 enter_scene / attribute / pet / bag_sort / skill / move <b>逐个</b>有增长，
 *       battle 发件箱 acked 有增长、exhausted 不变。</li>
 * </ol>
 * {@code --expect-dev deny}（xm-battle 以 prod 运行）：gather 与取消接口必须回 403，场景只跑第 2 步。
 * 结尾在观察记录里写一行 {@code BATTLE_SETTLE_OK …} 或 {@code BATTLE_SETTLE_FAIL step=…}（带失败的步骤号，逗号分隔）。
 * 故障变体（kill -9 的两个崩溃窗口）见 {@link BattleCrashScenario}，由 {@code tools/local/battle-crash-window.sh} 编排。
 *
 * <p>前置：本机切片带 xm-battle、xm-team 与 xm-scene（{@code SceneBattleService}），dev 运行模式，运维令牌（{@code XM_ADMIN_TOKEN} 或
 * {@code run/xm-admin-token}）；reaper 间隔建议调到 2 s（{@code xm.scene.battle.reaper-interval}），本场景按缺省 30 s 的上界等待，只把实际时延记进观察记录。
 * 双 scene 切片（{@code XM_SCENE_NODES=2}）要把两个节点的管理端口都传给 {@code --scene-metrics-url}（逗号分隔）：A 落在哪个节点不确定，
 * 第 13 步按两个节点的指标之和判定。只给了一个地址而 A、B 登录时不同频道：第 11 步照跑（跑完 A 留在另一个节点），第 13 步的 scene 指标
 * 只看得到一个节点、不达标时记一条写明原因的失败（重跑时把两个地址都给上），不逐条报「0.0 → 0.0」。
 */
public final class BattleSettleScenario {

    static final String REF = "scene-battle-spec §13.8";
    private static final String REF_GATES = "scene-battle-spec §7.13、§6.1";
    private static final String REF_APPLY = "scene-battle-spec §7.10–§7.11";
    private static final String REF_CROSS = "scene-battle-spec §13.8 第 11 步、§7.13；scene-handoff-spec §5.5";
    private static final String REF_TEAM = "scene-battle-spec §13.8 第 12 步、§7.13 世界内部第 4 条；team-spec D10";
    private static final String TEAM_SERVICE = "ClientPlayerTeam";

    private static final int TIP_MEMBER_IN_TEAM = TeamErrorTip.team_error.kTeamMemberInTeam_VALUE;
    private static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int TIP_BUSY = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    private static final int TIP_ENTER_SCENE_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    private static final int TIP_ATTRIBUTE_IN_BATTLE = AttributeErrorTip.attribute_error.kAttributeInBattle_VALUE;
    private static final int TIP_PET_IN_BATTLE = PetErrorTip.pet_error.kPetInBattle_VALUE;
    private static final int TIP_CASTER_IN_BATTLE = SkillErrorTip.skill_error.kSkillCannotBeCastInCurrentState_VALUE;
    private static final int TIP_TARGET_IN_BATTLE = SkillErrorTip.skill_error.kSkillInvalidTarget_VALUE;

    /** PVE 单人（{@code MatchMode} 数值）、Dungeon 1（怪物 1、2；任务 12 要杀怪物 1）。 */
    static final int MATCH_MODE_PVE = 4;
    static final int DUNGEON_1 = 1;
    static final int MONSTER_1 = 1;
    static final long SEED = 20261005L;
    static final int MISSION_12 = 12;
    static final int MISSION_13 = 13;
    static final int FOX = 1;
    static final int POOL = 1;
    static final int GOLD = 0;
    static final int BAG_INVENTORY = 0;
    static final int BAG_TEMPORARY = 3;
    /** 技能 1：单体（B 对在途的 A 放它 → 7002）。 */
    static final int SKILL_SINGLE = 1;

    static final Duration LONG_DEADLINE = Duration.ofMinutes(5);
    static final Duration PREPARE_DEADLINE = Duration.ofSeconds(60);
    /** 确认到达 scene 的余量（收到 143 后再等它，冻结升级为 FIGHTING）。 */
    static final Duration CONFIRM_SETTLE = Duration.ofSeconds(1);
    /** 挂机打完 PVE1 的上限（2 s 一回合）。 */
    static final Duration AUTO_FINISH_TIMEOUT = Duration.ofSeconds(120);
    /** 结算落地后大厅收到 150 的上限。 */
    static final Duration LOBBY_END_TIMEOUT = Duration.ofSeconds(15);
    /** 重登 / 离线结算之后确认没有重复 150 的观察窗。 */
    static final Duration NO_REPEAT_WINDOW = Duration.ofSeconds(10);
    /** 任务 12 变可领的上限（§13.8 第 7 步每 250 ms 轮询、5 s 内）。 */
    static final Duration MISSION_WAIT = Duration.ofSeconds(5);
    /** 销账放锁之后再备战成功的上限（§13.8 第 7 步）。 */
    static final Duration LOCK_RELEASE_WAIT = Duration.ofSeconds(2);
    /**
     * FIGHTING 判废宽限（D21）。服务端的真值是 xm-discovery 的 {@code BattleRedis.FIGHTING_EXPIRY_GRACE}；robot 不依赖 xm-discovery，
     * 这里与下面几个随服务端常量走的取值（reaper 间隔上界、备战锁的额外 TTL、重投窗口）由 {@code UpstreamConstantsTest} 读源码钉住。
     */
    static final Duration FIGHTING_GRACE = Duration.ofSeconds(10);
    /** reaper 间隔的上界（缺省 30 s = {@code BattleRedis.REAPER_INTERVAL}，只许调小；切片调到 2 s 时实际远小于它）+ 2 s 余量。 */
    static final Duration REAPER_SLACK = Duration.ofSeconds(32);
    /** 备战锁在备战期限之后还留多久（{@code BattleRedis.LOCK_EXTRA_TTL_SEC}，§7.2）：第 5 步慢用例等它过期。 */
    static final Duration PREPARE_LOCK_EXTRA_TTL = Duration.ofSeconds(60);
    /** 第 10 步的短期限。 */
    static final Duration SHORT_DEADLINE = Duration.ofSeconds(20);
    /** 离线结算：房间打完之后再等（快跑：落库 + 首投登记；--slow：12 次重投、第 13 轮用尽，§3.4）。 */
    static final Duration OFFLINE_SETTLE_FAST = Duration.ofSeconds(3);
    static final Duration OFFLINE_SETTLE_SLOW = Duration.ofSeconds(130);
    /** 观察「这段时间没有某帧」的窗口（134 被静默丢）。 */
    static final Duration MOVE_SILENCE = Duration.ofSeconds(1);
    /** 同号请求的节拍（gate 缺省每秒 3 条；1.2 s 窗口留 200 ms 余量）。 */
    private static final Duration RATE_WINDOW = Duration.ofMillis(1200);
    /**
     * 第 12 步等队伍视图跟上锁的上限：备战 / 取消的写锁与删锁是异步脚本；结算后锁要等落盘才随销账放掉（§7.12），所以给到 5 s，
     * 每 500 ms 拉一次 GetMyTeam（gate 对它每秒 5 条）。
     */
    static final Duration TEAM_VIEW_WAIT = Duration.ofSeconds(5);
    private static final Duration TEAM_VIEW_POLL = Duration.ofMillis(500);

    private final RobotClient client;
    private final PlayerFlow flow;
    private final MessageIds ids;
    private final BattleIds battleIds;
    private final BattleAdminClient admin;
    private final ConfigTables tables;
    /** scene 管理端口（抓指标）；双 scene 切片给两个，按节点之和判定。 */
    private final List<String> sceneMetricsUrls;
    private final String accountA;
    private final String accountB;
    /** 第 11 步的探针账号：只在声明了双 scene 切片、而 A、B 登录时恰好同频道时才登录。 */
    private final String accountC;
    private final boolean expectDevAllowed;
    private final boolean slow;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    private final CheckReport report = new CheckReport();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final BattleIdSequence battleIdSequence = new BattleIdSequence();
    /** 只备战、可能还冻着的局（阶段中断时尽力取消）。 */
    private final Set<Long> preparedOnly = new LinkedHashSet<>();

    private final int reconnectHint;
    private final int notifyPetList;
    private final int getPanel;
    private final int allocate;
    private final int autoAllocate;
    private final int getPetList;
    private final int summonPet;
    private final int recallPet;
    private final int grantPet;
    private final int getBag;
    private final int sortBag;
    private final int getCurrencyList;
    private final int getMissionList;
    private final int acceptMission;
    private final int claimMission;
    private final int enterScene;
    private final int releaseSkill;
    private final int leaveGame;
    private final int createTeam;
    private final int getMyTeam;
    private final int disbandTeam;

    private Bot a;
    private Bot b;
    /** B 登录时落在的场景（第 1 步把 B 换到 A 的实例之前）：与 A 同图不同频道时就是第 11 步的跨节点目标。 */
    private SceneInfoComp bOrigin;
    /**
     * 第 11 步找到的另一个 scene 节点上的频道（null = 没有见到第二个节点）。{@code --scene-metrics-url} 不足两个地址时，第 13 步据此把
     * 「没抓的那个节点上的增量看不到」报成一条写明原因的失败（{@link BattleSettleChecks#partialScrapeProblem}），而不是八条「0.0 → 0.0」。
     */
    private SceneInfoComp secondNodeChannel;
    private long petId;
    /** 第 12 步 A 的单人队（0 = 没建成，后面的队伍视图采样跳过）。 */
    private long teamId;
    /** 汇总行用。 */
    private long fullBattleId;
    private long fullBattleGold = -1;
    private boolean missionOk;
    private boolean reloginOk;
    private boolean offlineOk;
    private final StepFailures stepFailures = new StepFailures();

    public BattleSettleScenario(RobotClient client, PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, BattleAdminClient admin,
                                Path tableDir, String sceneMetricsUrl, String accountPrefix, String runTag, boolean expectDevAllowed,
                                boolean slow, Duration requestTimeout, Duration observeTimeout) {
        this.client = client;
        this.flow = flow;
        this.ids = ids;
        this.battleIds = BattleIds.resolve(registry);
        this.admin = admin;
        this.tables = ConfigTables.load(tableDir);
        this.sceneMetricsUrls = BattleSettleChecks.metricsUrls(sceneMetricsUrl);
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.expectDevAllowed = expectDevAllowed;
        this.slow = slow;
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.reconnectHint = registry.requireId("BattleClientPlayer", "NotifyBattleReconnect");
        this.notifyPetList = registry.requireId("ScenePetClientPlayer", "NotifyPetListChanged");
        this.getPanel = registry.requireId("SceneAttributeClientPlayer", "GetAttributePanel");
        this.allocate = registry.requireId("SceneAttributeClientPlayer", "AllocateAttributePoints");
        this.autoAllocate = registry.requireId("SceneAttributeClientPlayer", "AutoAllocateAttributePoints");
        this.getPetList = registry.requireId("ScenePetClientPlayer", "GetPetList");
        this.summonPet = registry.requireId("ScenePetClientPlayer", "SummonPet");
        this.recallPet = registry.requireId("ScenePetClientPlayer", "RecallPet");
        this.grantPet = registry.requireId("ScenePetClientPlayer", "GmGrantPet");
        this.getBag = registry.requireId("SceneBagClientPlayer", "GetBag");
        this.sortBag = registry.requireId("SceneBagClientPlayer", "SortBag");
        this.getCurrencyList = registry.requireId("SceneCurrencyClientPlayer", "GetCurrencyList");
        this.getMissionList = registry.requireId("SceneMissionClientPlayer", "GetMissionList");
        this.acceptMission = registry.requireId("SceneMissionClientPlayer", "AcceptMission");
        this.claimMission = registry.requireId("SceneMissionClientPlayer", "ClaimMissionReward");
        this.enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
        this.releaseSkill = registry.requireId("SceneSkillClientPlayer", "ReleaseSkill");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
        this.createTeam = registry.requireId(TEAM_SERVICE, "CreateTeam");
        this.getMyTeam = registry.requireId(TEAM_SERVICE, "GetMyTeam");
        this.disbandTeam = registry.requireId(TEAM_SERVICE, "DisbandTeam");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "bs" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", message(e), REF);
            stepFailures.add("流程");
        } finally {
            cleanup();
            disbandTeamQuietly();
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结论
                }
            }
            report.note(summaryLine());
        }
        return report;
    }

    /** 汇总行（§13.8 第 14 步）。 */
    String summaryLine() {
        return BattleSettleChecks.summaryLine(report.passed(), stepFailures.steps(), fullBattleId, fullBattleGold, missionOk ? MISSION_12 : 0,
                reloginOk, offlineOk);
    }

    private void runChecks() throws RobotException {
        if (!admin.hasToken()) {
            throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-battle 相同），或先用 tools/local/start-slice.sh 生成 "
                    + "run/xm-admin-token（从仓库根目录运行 robot）");
        }
        // ---- 第 1 步（前半）：A 登录进场 ----
        a = enter("A", accountA);
        report.note("A=" + uid(a.id()) + " battle-admin=" + admin.baseUrl());

        // ---- 第 2 步：gate 拒绝战斗上行 ----
        step("2", this::lobbyRejectsBattleUplink);

        if (!expectDevAllowed) {
            step("prod-403", this::devInterfacesDenied);
            return;
        }

        // ---- 第 1 步（后半）：B、宝宝、任务 ----
        b = enter("B", accountB);
        bOrigin = b.sceneInfo();
        report.note("B=" + uid(b.id()));
        step("1", this::prepareFixtures);
        String sceneBefore = scrapeScene();
        String battleBefore = admin.scrapeMetrics();

        step("3-4", this::prepareOnlyAndGates);
        step("5", this::cancelPrepare);
        if (slow) {
            step("5-slow", this::prepareExpiry);
        }
        // 第 12、11 步排在这里：A 还在登录时的场景里、没有冻结也没有锁（第 3–5 步保持原来的次序，不受新步骤影响）。
        // 第 12 步建的单人队留到场景结束（第 6–7 步里还要采样两次）；第 11 步在双 scene 切片上会把 A 换到另一个节点，后面的步骤就在那个节点上跑
        step("12", this::teamViewPrepareCancel);
        step("11", this::crossNodeExclusion);
        step("6-7", this::fullBattle);
        step("8", this::reloginNoResend);
        step("9", this::offlineSettlement);
        step("10", this::confirmedThenDestroyed);
        step("13", () -> metrics(sceneBefore, battleBefore));
    }

    /** {@code --expect-dev deny}：gather 与取消接口在 prod 运行模式下必须回 403（§7.18）。 */
    private void devInterfacesDenied() throws RobotException {
        DevGather probe = DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, battleIdSequence.next(System.currentTimeMillis()),
                MATCH_MODE_PVE, DUNGEON_1, SEED, deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id());
        BattleAdminClient.HttpResult gather = admin.gatherRaw(probe);
        BattleAdminClient.HttpResult cancel = admin.cancelPrepare(a.id(), probe.battleId());
        report.check(gather.status() == 403 && cancel.status() == 403, "dev gather / 取消接口在 prod 运行模式下回 403（--expect-dev deny）",
                "gather=" + gather.status() + " cancel=" + cancel.status(), "scene-battle-spec §7.18");
    }

    // ================================================================ 第 13 步

    /**
     * 指标（§13.8 第 13 步）：scene 的结算应用、销账放锁有增长；在途闸<b>逐个</b>有增长（只看合计的话，某个服务闸漏了计数也照样通过）；
     * battle 发件箱 acked 有增长、exhausted 不变。scene 指标抓不到时只记观察记录。
     * 第 11 步见过第二个 scene 节点、{@code --scene-metrics-url} 却只有一个地址、增量又不达标时，记一条写明原因的失败代替逐条的「0.0 → 0.0」
     * （A 先后在两个节点上跑步骤，只抓一个节点判不了；{@link BattleSettleChecks#partialScrapeProblem}）。
     */
    private void metrics(String sceneBefore, String battleBefore) throws RobotException {
        String sceneAfter = scrapeScene();
        String battleAfter = admin.scrapeMetrics();
        if (sceneBefore != null && sceneAfter != null) {
            BattleSettleChecks.judgeSceneMetrics(report, sceneBefore, sceneAfter, sceneMetricsUrls.size(),
                    secondNodeChannel == null ? null : describe(secondNodeChannel));
            report.note("第 13 步 本场景打不到的闸不断言（asset 要 xm-guild 的资产指令、default 是缺省 REJECT 的方法），增量："
                    + BattleSettleChecks.gateRejectDeltas(sceneBefore, sceneAfter, BattleSettleChecks.UNEXERCISED_GATES));
        } else {
            report.note("第 13 步 scene 指标没有抓到（开头或结尾），scene 侧的三组指标不判");
        }
        metricGrew(battleBefore, battleAfter, "xm_battle_settlement_outbox_total", 1, "event=\"acked\"");
        double exhausted = BattleSettleChecks.delta(battleBefore, battleAfter, "xm_battle_settlement_outbox_total", "event=\"exhausted\"");
        report.check(exhausted == 0, "第 13 步 battle 发件箱没有用尽（exhausted 不变）", "增量 " + exhausted, "scene-battle-spec §9");
    }

    // ================================================================ 第 1 步

    private void prepareFixtures() throws RobotException {
        // A、B 要在同一个场景实例里（D5 的 66、B 对 A 放技能 7002）
        if (a.sceneInfo().getSceneId() != b.sceneInfo().getSceneId()) {
            report.note("A、B 落在不同的场景实例（" + describe(a.sceneInfo()) + " / " + describe(b.sceneInfo()) + "），B 换到 A 的实例");
            int tip = switchScene(b, a.sceneInfo().getSceneConfigId(), a.sceneInfo().getSceneId());
            report.check(tip == 0 && b.sceneInfo().getSceneId() == a.sceneInfo().getSceneId(), "第 1 步 B 换到 A 所在的场景实例",
                    "tip=" + tip + " " + describe(b.sceneInfo()), REF);
        }
        GmGrantPetResponse granted = a.call(grantPet, GmGrantPetRequest.newBuilder().setPetTableId(FOX).build(), GmGrantPetResponse.parser());
        petId = granted.getPetId();
        report.check(granted.getErrorMessage().getId() == 0 && petId != 0, "第 1 步 A 用 GM 187 领灵狐", "tip=" + granted.getErrorMessage().getId()
                + " pet_id=" + uid(petId), REF);
        SummonPetResponse summoned = a.call(summonPet, SummonPetRequest.newBuilder().setPetId(petId).build(), SummonPetResponse.parser());
        report.check(summoned.getErrorMessage().getId() == 0 && summoned.getPets().getActivePetId() == petId, "第 1 步 A 183 出战灵狐",
                "tip=" + summoned.getErrorMessage().getId(), REF);
        GetMissionListResponse accepted = a.call(acceptMission, MissionActionRequest.newBuilder().setScope(0).setMissionId(MISSION_12).build(),
                GetMissionListResponse.parser());
        report.check(accepted.getErrorMessage().getId() == 0, "第 1 步 A 194 接任务 12（杀怪物 1）", "tip=" + accepted.getErrorMessage().getId(),
                REF);
        report.note("A 起始：金币 " + gold(a) + "，气血 " + panel(a).getDerived().getHealth() + "/" + panel(a).getDerived().getMaxHealth());
    }

    // ================================================================ 第 2 步

    private void lobbyRejectsBattleUplink() throws RobotException {
        int[] uplinks = {battleIds.submitAction(), battleIds.getBattleState(), battleIds.setAutoBattle(), battleIds.stopWatch()};
        List<Message> bodies = List.of(SubmitBattleActionRequest.newBuilder().setBattleId(1).build(),
                GetBattleStateRequest.newBuilder().setBattleId(1).build(), SetAutoBattleRequest.newBuilder().setBattleId(1).setEnabled(true).build(),
                StopWatchBattleRequest.newBuilder().setBattleId(1).build());
        for (int i = 0; i < uplinks.length; i++) {
            int messageId = uplinks[i];
            int mark = a.mark();
            long requestId = a.connection().send(messageId, bodies.get(i));
            Optional<Received> tip = a.connection().await(mark, r -> BattleSupport.lobbyTip(r, battleIds.sendTip()) >= 0, requestTimeout);
            boolean noReply = a.connection().inbox().snapshot(mark).stream()
                    .noneMatch(r -> r.messageId() == messageId && r.requestId() == requestId);
            report.check(tip.isPresent() && BattleSupport.lobbyTip(tip.get(), battleIds.sendTip()) == TIP_SERVICE_UNAVAILABLE && noReply,
                    "第 2 步 大厅上发 " + messageId + " → gate 推 23 {1003}、不回应答",
                    tip.map(r -> "tip=" + BattleSupport.lobbyTip(r, battleIds.sendTip())).orElse("没有收到 23" + a.connection().describeSince(mark)),
                    "scene-battle-spec §7.19");
        }
        ListSkillsResponse skills = flow.listSkills(a.player);
        report.check(a.connection().isOpen() && skills.hasSkillList(), "第 2 步 拒绝后不断连，正常请求（77）照常应答",
                "open=" + a.connection().isOpen(), "scene-battle-spec §7.19");
    }

    // ================================================================ 第 3–4 步

    private void prepareOnlyAndGates() throws RobotException {
        Set<Integer> knownSkills = new HashSet<>();
        for (PlayerSkillComp skill : flow.listSkills(a.player).getSkillList().getSkillListList()) {
            knownSkills.add(skill.getSkillTableId());
        }
        // A 先动起来：备战时 scene 停步并置速度脏位，B 收到 A 速度 0 的 66（D5）
        a.request(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(a.at.toLocation()).setRotation(new Vec3(0, 0, 90).toRotation())
                .setVelocity(new Vec3(4, 0, 0).toVelocity()).setClientTimeMs(System.nanoTime() / 1_000_000).setInputSeq(1).build());
        BattleSupport.sleep(Duration.ofMillis(300));

        long x1 = battleIdSequence.next(System.currentTimeMillis());
        int markB = b.mark();
        GatherOutcome g1 = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x1, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        PrepareResult r1 = g1.of(a.id());
        if (r1 != null && r1.ok()) {
            preparedOnly.add(x1);
        }
        report.check(g1.ok() && r1 != null && r1.ok(), "第 3 步 dev gather PREPARE_ONLY：200、scene 已处理、无业务错误、带快照", g1.describe(),
                "scene-battle-spec §7.18");
        report.note("只备战 X1=" + uid(x1));
        if (r1 == null || !r1.ok()) {
            throw new RobotException("只备战失败，后面的在途闸无从验证：" + g1.describe());
        }
        List<String> problems = BattleSettleChecks.snapshotProblems(r1.response(), a.id(), knownSkills,
                id -> tables.skill().find(id).map(BattleSettleChecks::castable).orElse(false),
                id -> tables.item().find(id).map(row -> row.getBattleUsable() != 0).orElse(false), petId);
        report.check(problems.isEmpty(), "第 3 步 快照：player_id、等级 ≥ 1、速度 > 0、上限 > 0、路由完整、指纹 32 位 hex、道具可战斗、宝宝一只归 A、技能可施放",
                problems.isEmpty() ? "level=" + r1.response().getSnapshot().getLevel() + " pets=" + r1.response().getSnapshot().getPetsCount()
                        + " skills=" + r1.response().getSnapshot().getSkillTableIdsList() : String.join("；", problems), "scene-battle-spec §7.11");
        Optional<Received> stop = b.connection().await(markB, r -> {
            ActorBaseAttributesS2C sync = CrossNodeScenario.syncOf(ids, r, a.id());
            return sync != null && Vec3.of(sync.getVelocity()).isZero();
        }, observeTimeout);
        report.check(stop.isPresent(), "第 3 步 备战即停步：B 收到 A 速度为 0 的 66（D5）", stop.map(r -> "#" + r.index()).orElse("没有收到"),
                "scene-battle-spec §6.1、D5");

        // ---- 第 4 步：在途闸（PREPARING）----
        checkGates("第 4 步（PREPARING）");
        // 173 只给建议、不闸（1 级没有剩余点时回 25014 也算「照常」，只要不是战斗闸的 25011）
        expectNot(a.callTip(autoAllocate, AutoAllocateAttributePointsRequest.newBuilder().setPoolId(POOL).build(),
                AutoAllocateAttributePointsResponse.parser(), r -> r.getErrorMessage().getId()), TIP_ATTRIBUTE_IN_BATTLE,
                "第 4 步 173 自动加点建议照常（不回 25011）", REF_GATES);
        expectTip(a.callTip(acceptMission, MissionActionRequest.newBuilder().setScope(0).setMissionId(MISSION_13).build(),
                GetMissionListResponse.parser(), r -> r.getErrorMessage().getId()), 0, "第 4 步 194 接任务 13 照常", REF_GATES);
        if (b.id() != 0) {
            int tip = b.callTip(releaseSkill, ReleaseSkillRequest.newBuilder().setSkillTableId(SKILL_SINGLE).setTargetId(a.entity)
                    .setPosition(Vector3.newBuilder().setX(1).setZ(1)).build(), ReleaseSkillResponse.parser(), r -> r.getErrorMessage().getId());
            expectTip(tip, TIP_TARGET_IN_BATTLE, "第 4 步 B 对在途的 A 放 84 → 7002", REF_GATES);
        }
        // 134 静默丢：B 在 1 s 内看不到 A 的位置变化
        int markMove = b.mark();
        a.request(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(a.at.plus(new Vec3(5, 0, 0)).toLocation())
                .setRotation(new Vec3(0, 0, 90).toRotation()).setVelocity(new Vec3(4, 0, 0).toVelocity())
                .setClientTimeMs(System.nanoTime() / 1_000_000).setInputSeq(2).build());
        BattleSupport.sleep(MOVE_SILENCE);
        long moved = b.connection().inbox().snapshot(markMove).stream().filter(r -> CrossNodeScenario.syncOf(ids, r, a.id()) != null).count();
        report.check(moved == 0, "第 4 步 A 发 134 带新坐标 → 静默丢，B 1 s 内收不到 A 的 66", "66 " + moved + " 条", REF_GATES);
        // 再备战 → 1006
        long x2 = battleIdSequence.next(System.currentTimeMillis());
        GatherOutcome g2 = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x2, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        PrepareResult r2 = g2.of(a.id());
        report.check(g2.httpStatus() == 422 && r2 != null && r2.tip() == TIP_BUSY && g2.cancelledPlayerIds().isEmpty(),
                "第 4 步 已有冻结时再备战 → 1006，零副作用（不补发取消）", g2.describe(), "scene-battle-spec §7.5");
    }

    /** 冻结（PREPARING / FIGHTING）期间的一组闸：63、168、185、187、192、84。 */
    private void checkGates(String step) throws RobotException {
        int other = otherWorld(a.sceneInfo().getSceneConfigId());
        expectTip(enterSceneTip(a, other), TIP_ENTER_SCENE_FAILED, step + " 63 换图 → 3023（先于换图在途 3014）", REF_GATES);
        expectTip(a.callTip(allocate, allocateOne(), AllocateAttributePointsResponse.parser(),
                r -> r.getErrorMessage().getId()), TIP_ATTRIBUTE_IN_BATTLE, step + " 168 加点 → 25011", REF_GATES);
        expectTip(a.callTip(recallPet, RecallPetRequest.getDefaultInstance(), RecallPetResponse.parser(), r -> r.getErrorMessage().getId()),
                TIP_PET_IN_BATTLE, step + " 185 收回宝宝 → 26008", REF_GATES);
        expectTip(a.callTip(grantPet, GmGrantPetRequest.newBuilder().setPetTableId(FOX).build(), GmGrantPetResponse.parser(),
                r -> r.getErrorMessage().getId()), TIP_PET_IN_BATTLE, step + " 187 GM 发宝宝 → 26008", REF_GATES);
        expectTip(a.callTip(sortBag, SortBagRequest.newBuilder().setBagType(BAG_INVENTORY).build(), SortBagResponse.parser(),
                r -> r.getErrorMessage().getId()), TIP_INVALID_PARAMETER, step + " 192 整理背包 → 1005", REF_GATES);
        long target = b == null ? 1 : b.entity;
        expectTip(a.callTip(releaseSkill, ReleaseSkillRequest.newBuilder().setSkillTableId(SKILL_SINGLE).setTargetId(target)
                        .setPosition(Vector3.newBuilder().setX(1).setZ(1)).build(), ReleaseSkillResponse.parser(), r -> r.getErrorMessage().getId()),
                TIP_CASTER_IN_BATTLE, step + " 84 放技能 → 7004", REF_GATES);
    }

    // ================================================================ 第 5 步

    private void cancelPrepare() throws RobotException {
        long x1 = preparedOnly.stream().findFirst().orElseThrow(() -> new RobotException("没有只备战的局可取消"));
        BattleAdminClient.HttpResult first = admin.cancelPrepare(a.id(), x1);
        int allocateTip = allocateTip();
        report.check(first.status() == 204 && allocateTip != TIP_ATTRIBUTE_IN_BATTLE, "第 5 步 取消 X1 → 204，闸立即解除（168 不再回 25011）",
                "status=" + first.status() + " 168 tip=" + allocateTip, "scene-battle-spec §7.6");
        BattleAdminClient.HttpResult again = admin.cancelPrepare(a.id(), x1);
        report.check(again.status() == 204, "第 5 步 再取消一次幂等（204）", "status=" + again.status() + " " + again.text(), "scene-battle-spec §7.6");
        preparedOnly.remove(x1);
        // 删锁是异步脚本：给它一点时间先于下一次 PREPARE_LOCK 落地
        BattleSupport.sleep(Duration.ofMillis(300));
        long x3 = battleIdSequence.next(System.currentTimeMillis());
        GatherOutcome g3 = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x3, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        if (g3.ok()) {
            preparedOnly.add(x3);
        }
        BattleAdminClient.HttpResult cancel3 = admin.cancelPrepare(a.id(), x3);
        report.check(g3.ok() && cancel3.status() == 204, "第 5 步 取消之后锁已删：再备战 X3 成功，随后取消", g3.describe() + " cancel=" + cancel3.status(),
                "scene-battle-spec §7.6");
        if (cancel3.status() == 204) {
            preparedOnly.remove(x3);
        }
        BattleSupport.sleep(Duration.ofMillis(300));
    }

    /** {@code --slow}：备战到期只摘冻结、锁留到备战 TTL（B2、§1.7）。约 70 s。 */
    private void prepareExpiry() throws RobotException {
        long x3b = battleIdSequence.next(System.currentTimeMillis());
        long prepareDeadline = deadline(Duration.ofSeconds(3));
        GatherOutcome g = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x3b, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), prepareDeadline, a.id()));
        report.check(g.ok(), "第 5 步（--slow）只备战 X3b，备战期限 3 s，不取消", g.describe(), "scene-battle-spec §7.9");
        long freedAt = pollUntil(Duration.ofMillis(prepareDeadline - System.currentTimeMillis()).plus(REAPER_SLACK), Duration.ofSeconds(1),
                () -> allocateTip() != TIP_ATTRIBUTE_IN_BATTLE);
        report.check(freedAt >= 0 && freedAt >= prepareDeadline - 500, "第 5 步（--slow）备战期限过后 reaper 摘掉冻结（168 恢复）",
                freedAt < 0 ? "没有恢复" : "期限后 " + (freedAt - prepareDeadline) + " ms", "scene-battle-spec §7.9");
        long x3c = battleIdSequence.next(System.currentTimeMillis());
        GatherOutcome held = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x3c, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        PrepareResult heldResult = held.of(a.id());
        report.check(held.httpStatus() == 422 && heldResult != null && heldResult.tip() == TIP_BUSY,
                "第 5 步（--slow）reaper 只摘冻结、锁保留：立刻再备战 X3c → 1006", held.describe(), "scene-battle-spec §1.7、B2");
        BattleAdminClient.HttpResult ignored = admin.cancelPrepare(a.id(), x3b);
        report.check(ignored.status() == 204, "第 5 步（--slow）在线无冻结时取消 X3b 被忽略（204，不删锁，B2）", "status=" + ignored.status(),
                "scene-battle-spec §7.6");
        long lockTtlEnd = prepareDeadline + PREPARE_LOCK_EXTRA_TTL.toMillis();
        long retried = pollUntil(Duration.ofMillis(lockTtlEnd - System.currentTimeMillis()).plus(Duration.ofSeconds(15)), Duration.ofSeconds(5),
                () -> {
                    long xr = battleIdSequence.next(System.currentTimeMillis());
                    GatherOutcome retry = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, xr, MATCH_MODE_PVE, DUNGEON_1, SEED,
                            deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
                    if (retry.ok()) {
                        preparedOnly.add(xr);
                        admin.cancelPrepare(a.id(), xr);
                        preparedOnly.remove(xr);
                        return true;
                    }
                    return false;
                });
        report.check(retried >= 0 && retried >= lockTtlEnd - 2_000, "第 5 步（--slow）锁按备战 TTL（期限 + 60 s）过期后再备战成功并取消",
                retried < 0 ? "一直 1006" : "备战期限后 " + (retried - prepareDeadline) + " ms", "scene-battle-spec §7.2");
    }

    // ================================================================ 第 6–7 步

    private void fullBattle() throws RobotException {
        Baseline before = baseline(a);
        long x4 = battleIdSequence.next(System.currentTimeMillis());
        fullBattleId = x4;
        report.note("完整一局 X4=" + uid(x4));
        LobbyBot lobby = a.lobby();
        int lobbyMark = a.mark();
        GatherOutcome g = admin.gather(new DevGather(BattleAdminClient.GATHER_CREATE, x4, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), List.of(new DevGather.Member(a.id(), 0))));
        report.check(g.ok() && g.created() != null && g.created().ok(), "第 6 步 dev gather CREATE：全员备战成功、建房 ADMITTED 且无业务错误",
                g.describe(), "scene-battle-spec §7.18");
        if (!g.ok()) {
            throw new RobotException("建房失败：" + g.describe());
        }
        Received r177 = lobby.awaitAssigned(lobbyMark, x4);
        Received r143 = lobby.awaitStart(lobbyMark, x4);
        report.check(r177.index() < r143.index(), "第 6 步 大厅先 177 后 143", "177 #" + r177.index() + " 143 #" + r143.index(), REF);
        BattleAssignedS2C assigned = r177.parse(BattleAssignedS2C.parser());
        Direct first = open("A#1", assigned.getHost(), assigned.getPort());
        BattleDirectConnection.Handshake hs = first.handshake(assigned);
        first.awaitReply(0, battleIds.getBattleState(), hs.stateRequestId(), requestTimeout);
        report.check(hs.success(), "第 6 步 凭票直连握手成功、补拉 140", BattleScenario.describe(hs.response()), REF);
        BattleSupport.sleep(CONFIRM_SETTLE);
        teamViewSample(true, TEAM_VIEW_WAIT, "第 12 步 战斗中（确认之后，FIGHTING）in_battle = true");

        // ---- 重连提示 144：断开大厅、重新登录 ----
        a.connection().close();
        a = enter("A", accountA);
        Optional<Received> hint = a.connection().await(0, r -> r.messageId() == reconnectHint
                && Optional.ofNullable(r.parseOrNull(BattleReconnectS2C.parser())).map(m -> m.getBattleId() == x4).orElse(false), LOBBY_END_TIMEOUT);
        int enterIndex = indexOf(a.connection(), ids.notifyEnterScene());
        report.check(hint.isPresent() && hint.get().index() > enterIndex, "第 6 步 战斗中断开大厅重登：79 之后大厅收到 144 {X4}",
                hint.map(r -> "144 #" + r.index() + " 79 #" + enterIndex).orElse("没有收到 144" + a.connection().describeSince(0)),
                "scene-battle-spec §7.8、D6");
        expectTip(enterSceneTip(a, otherWorld(a.sceneInfo().getSceneConfigId())), TIP_ENTER_SCENE_FAILED, "第 6 步 重登后 63 仍回 3023（FIGHTING）",
                REF_GATES);

        // ---- 补签 → 重新直连 → 162 挂机 → 直连 150 ----
        IssueBattleTicketResponse reissued = admin.issueTicket(x4, a.id());
        report.check(!reissued.hasErrorMessage() && reissued.hasAssignment(), "第 6 步 经 dev/issue-ticket 补签", "tip="
                + reissued.getErrorMessage().getId(), "battle-node-spec §2.4");
        Direct second = open("A#2", reissued.getAssignment().getHost(), reissued.getAssignment().getPort());
        BattleDirectConnection.Handshake hs2 = second.handshake(reissued.getAssignment());
        report.check(hs2.success() && hs2.response().getBattleId() == x4, "第 6 步 凭补签的票重新直连握手成功（顶替旧直连）",
                BattleScenario.describe(hs2.response()), "battle-node-spec §3.4");
        second.awaitReply(0, battleIds.getBattleState(), hs2.stateRequestId(), requestTimeout);
        int lobbyEndMark = a.mark();
        int autoMark = second.mark();
        second.request(battleIds.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(x4).setEnabled(true).build());
        List<BattleFrame> tail = second.untilClosed(autoMark, AUTO_FINISH_TIMEOUT);
        Optional<BattleFrame> endFrame = tail.stream().filter(f -> f.isPush(battleIds.battleEnd())).findFirst();
        if (endFrame.isEmpty()) {
            throw new RobotException("挂机打完后直连上没有 150：" + BattleSupport.labels(tail));
        }
        BattleEndS2C direct = endFrame.get().parse(BattleEndS2C.parser());
        BattleSettlementData settlement = direct.getSettlement();
        report.check(direct.getBattleId() == x4 && direct.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                        && settlement.getPlayerId() == a.id() && settlement.getTotalRounds() >= 1,
                "第 6 步 直连 150：SIDE_A_WIN、settlement.player_id = A、total_rounds ≥ 1", "outcome=" + direct.getOutcome() + " rounds="
                        + settlement.getTotalRounds() + " gold=" + settlement.getGoldGain() + " pets=" + settlement.getPetsCount(),
                "robot/features_battle_smoke.go:82-95");

        // ---- 大厅 184 → 150 ----
        Optional<Received> lobbyEnd = a.connection().await(lobbyEndMark, r -> a.lobby().isLobbyPush(r, battleIds.battleEnd(), x4), LOBBY_END_TIMEOUT);
        // 不能写成 lobbyEnd.map(...).orElse(...)：一致时比对返回 null，map 会把它变成空 Optional、误报「没有收到」
        String mismatch;
        if (lobbyEnd.isEmpty()) {
            mismatch = "没有收到" + a.connection().describeSince(lobbyEndMark);
        } else {
            BattleEndS2C body = lobbyEnd.get().parseOrNull(BattleEndS2C.parser());
            mismatch = body == null ? "大厅 150 解析失败" : BattleSettleChecks.lobbyEndMismatch(body, direct);
        }
        report.check(mismatch == null, "第 6 步 结算落地后大厅收到 150：battle_id、outcome、settlement 与直连逐字段相同", mismatch == null ? "一致" : mismatch,
                "scene-battle-spec §6.1");
        if (settlement.getPetsCount() > 0 && lobbyEnd.isPresent()) {
            List<Received> petPushes = a.connection().inbox().snapshot(lobbyEndMark).stream()
                    .filter(r -> r.messageId() == notifyPetList && r.index() < lobbyEnd.get().index()).toList();
            report.check(petPushes.size() == 1, "第 6 步 有宝宝条目：150 之前恰好一条 184", petPushes.size() + " 条", "scene-battle-spec §7.11 f 步");
        }
        fullBattleGold = settlement.getGoldGain();

        // ---- 第 7 步：结算效果 ----
        checkEffects(before, settlement);
        missionOk = claimMission12(settlement);
        int allocateTip = allocateTip();
        expectNot(allocateTip, TIP_ATTRIBUTE_IN_BATTLE, "第 7 步 闸已解除：168 不再回 25011", REF_GATES);
        int other = otherWorld(a.sceneInfo().getSceneConfigId());
        expectTip(enterSceneTip(a, other), 0, "第 7 步 闸已解除：63 换图成功", REF_GATES);
        long lockFreed = pollUntil(LOCK_RELEASE_WAIT, Duration.ofMillis(500), () -> {
            long x5 = battleIdSequence.next(System.currentTimeMillis());
            GatherOutcome probe = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x5, MATCH_MODE_PVE, DUNGEON_1, SEED,
                    deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
            if (!probe.ok()) {
                return false;
            }
            preparedOnly.add(x5);
            if (admin.cancelPrepare(a.id(), x5).status() == 204) {
                preparedOnly.remove(x5);
            }
            return true;
        });
        report.check(lockFreed >= 0, "第 7 步 销账放锁：2 s 内再备战 X5 成功（随后取消）", lockFreed >= 0 ? "成功" : "一直被拒（锁没放）",
                "scene-battle-spec §7.12");
        BattleSupport.sleep(Duration.ofMillis(300));
        // X4 的锁已随销账放掉、X5 的锁已随取消删掉：队伍视图回到「不在战斗」
        teamViewSample(false, TEAM_VIEW_WAIT, "第 12 步 结算销账后 in_battle 变回 false");
    }

    /** 结算效果（§13.8 第 7 步）：金币、背包、气血、宝宝气血。 */
    private void checkEffects(Baseline before, BattleSettlementData settlement) throws RobotException {
        long goldAfter = gold(a);
        report.check(goldAfter - before.gold() == settlement.getGoldGain(), "第 7 步 54 金币增量 = gold_gain",
                before.gold() + " → " + goldAfter + "，gold_gain=" + settlement.getGoldGain(), REF_APPLY);
        Map<Integer, Long> bagAfter = bagTotals(a);
        Map<Integer, Long> expected = BattleSettleChecks.expectedBagDelta(settlement, before.bag());
        Map<Integer, Long> actual = BattleSettleChecks.actualBagDelta(before.bag(), bagAfter);
        report.check(actual.equals(expected), "第 7 步 背包（主包 + 临时格）按掉落 / 消耗变化，消耗按持有夹紧",
                "期望 " + expected + " 实际 " + actual + "（gained=" + BattleSettleChecks.oneLine(settlement.getItemsGainedList())
                        + " consumed=" + BattleSettleChecks.oneLine(settlement.getItemsConsumedList()) + "）", REF_APPLY);
        AttributePanelInfo panel = panel(a);
        long expectedHealth = BattleSettleChecks.expectedHealth(settlement.getHealth(), panel.getDerived().getMaxHealth());
        report.check(panel.getDerived().getHealth() == expectedHealth, "第 7 步 167 气血 = min(结算气血, 上限)，阵亡回满",
                "health=" + panel.getDerived().getHealth() + " 期望 " + expectedHealth + "（结算 " + settlement.getHealth() + "，上限 "
                        + panel.getDerived().getMaxHealth() + "）", REF_APPLY);
        GetPetListResponse pets = a.call(getPetList, GetPetListRequest.getDefaultInstance(), GetPetListResponse.parser());
        for (BattlePetSettlementData settled : settlement.getPetsList()) {
            Optional<PetInfo> pet = pets.getPets().getPetsList().stream().filter(p -> p.getPetId() == settled.getPetId()).findFirst();
            long expectedPet = pet.map(p -> BattleSettleChecks.expectedPetHealth(settled, p.getDerived().getMaxHealth())).orElse(-1L);
            report.check(pet.isPresent() && pet.get().getDerived().getHealth() == expectedPet, "第 7 步 181 宝宝气血 = 结算值（夹到上限，阵亡回满）",
                    pet.map(p -> "health=" + p.getDerived().getHealth() + " 期望 " + expectedPet + "（结算 " + settled.getHealth() + " dead="
                            + settled.getIsDead() + "）").orElse("列表里没有 pet_id=" + uid(settled.getPetId())), "scene-battle-spec §7.14");
        }
        if (settlement.getPetsCount() == 0) {
            report.note("这一局的结算没有宝宝条目（宝宝未参战？）");
        }
    }

    /** 任务 12：击杀事实落地后可领 → 领取成功 → 重复领取被拒。返回是否全部通过。 */
    private boolean claimMission12(BattleSettlementData settlement) throws RobotException {
        if (BattleSettleChecks.killed(settlement, MONSTER_1) == 0) {
            report.fail("第 7 步 任务 12 的击杀事实", "这一局没有击杀怪物 1（outcome=" + settlement.getOutcome() + "），任务 12 无从推进", REF_APPLY);
            return false;
        }
        long claimableAt = pollUntil(MISSION_WAIT, Duration.ofMillis(250), () -> {
            PlayerMissionInfo twelve = mission(a.call(getMissionList, GetMissionListRequest.getDefaultInstance(), GetMissionListResponse.parser()),
                    MISSION_12);
            return twelve != null && twelve.getCanClaim();
        });
        report.check(claimableAt >= 0, "第 7 步 193 轮询：5 s 内任务 12 可领（击杀事实 → MissionService）", claimableAt >= 0 ? "可领" : "一直不可领",
                "scene-battle-spec §7.11 j 步");
        if (claimableAt < 0) {
            return false;
        }
        GetMissionListResponse claimed = a.call(claimMission, MissionActionRequest.newBuilder().setScope(0).setMissionId(MISSION_12).build(),
                GetMissionListResponse.parser());
        GetMissionListResponse again = a.call(claimMission, MissionActionRequest.newBuilder().setScope(0).setMissionId(MISSION_12).build(),
                GetMissionListResponse.parser());
        boolean ok = claimed.getErrorMessage().getId() == 0 && again.getErrorMessage().getId() != 0;
        report.check(ok, "第 7 步 195 领取任务 12 成功、重复领取被拒", "tip=" + claimed.getErrorMessage().getId() + " / 重复 tip="
                + again.getErrorMessage().getId(), "PARITY「任务」行");
        return ok;
    }

    // ================================================================ 第 8 步

    private void reloginNoResend() throws RobotException {
        long goldBefore = gold(a);
        Map<Integer, Long> bagBefore = bagTotals(a);
        GetMissionListResponse missionsBefore = a.call(getMissionList, GetMissionListRequest.getDefaultInstance(), GetMissionListResponse.parser());
        leave(a);
        a = enter("A", accountA);
        long goldAfter = gold(a);
        Map<Integer, Long> bagAfter = bagTotals(a);
        GetMissionListResponse missionsAfter = a.call(getMissionList, GetMissionListRequest.getDefaultInstance(), GetMissionListResponse.parser());
        report.check(goldAfter == goldBefore && bagAfter.equals(bagBefore) && missionsAfter.equals(missionsBefore),
                "第 8 步 登出再登入：金币、背包、任务与第 7 步相同", "金币 " + goldBefore + " → " + goldAfter + "，背包 " + bagBefore + " → " + bagAfter
                        + "，任务相同=" + missionsAfter.equals(missionsBefore), "scene-battle-spec §7.12");
        BattleSupport.sleep(NO_REPEAT_WINDOW);
        long ends = a.connection().inbox().snapshot(0).stream().filter(r -> r.messageId() == battleIds.battleEnd()).count();
        report.check(ends == 0, "第 8 步 重登后 10 s 内大厅没有第二条 150（账本 / 销账已完成，不重发）", ends + " 条", "scene-battle-spec §7.12");
        reloginOk = goldAfter == goldBefore && ends == 0;
    }

    // ================================================================ 第 9 步

    private void offlineSettlement() throws RobotException {
        long goldBefore = gold(a);
        long x6 = battleIdSequence.next(System.currentTimeMillis());
        report.note("离线结算 X6=" + uid(x6));
        int lobbyMark = a.mark();
        GatherOutcome g = admin.gather(DevGather.solo(BattleAdminClient.GATHER_CREATE, x6, MATCH_MODE_PVE, DUNGEON_1, SEED, deadline(LONG_DEADLINE),
                deadline(PREPARE_DEADLINE), a.id()));
        report.check(g.ok(), "第 9 步 dev gather CREATE X6", g.describe(), "scene-battle-spec §7.18");
        if (!g.ok()) {
            throw new RobotException("建房失败：" + g.describe());
        }
        BattleAssignedS2C assigned = a.lobby().awaitAssigned(lobbyMark, x6).parse(BattleAssignedS2C.parser());
        a.lobby().awaitStart(lobbyMark, x6);
        Direct direct = open("A#offline", assigned.getHost(), assigned.getPort());
        BattleDirectConnection.Handshake hs = direct.handshake(assigned);
        direct.awaitReply(0, battleIds.getBattleState(), hs.stateRequestId(), requestTimeout);
        direct.request(battleIds.setAutoBattle(), SetAutoBattleRequest.newBuilder().setBattleId(x6).setEnabled(true).build());
        BattleSupport.sleep(Duration.ofMillis(300));
        direct.close();
        a.connection().close();
        long finishedAt = pollUntil(AUTO_FINISH_TIMEOUT, Duration.ofSeconds(1), () -> admin.issueTicket(x6, a.id()).hasErrorMessage());
        report.check(finishedAt >= 0, "第 9 步 玩家离线时房间照常挂机打完", finishedAt >= 0 ? "已结束" : "期限内没打完", REF);
        Duration settle = slow ? OFFLINE_SETTLE_SLOW : OFFLINE_SETTLE_FAST;
        report.note("第 9 步 房间结束后再等 " + settle.toSeconds() + " s 再登录" + (slow ? "（越过 12 次重投、第 13 轮用尽）" : ""));
        BattleSupport.sleep(settle);
        a = enter("A", accountA);
        Optional<Received> end = a.connection().await(0, r -> a.lobby().isLobbyPush(r, battleIds.battleEnd(), x6), LOBBY_END_TIMEOUT);
        report.check(end.isPresent(), "第 9 步 离线期间结算的局：登录后大厅收到 150 {X6}（进场恢复按待结算记录应用）",
                end.map(r -> "#" + r.index()).orElse("没有收到" + a.connection().describeSince(0)), "scene-battle-spec §7.8");
        if (end.isEmpty()) {
            return;
        }
        BattleSettlementData settlement = end.get().parse(BattleEndS2C.parser()).getSettlement();
        long goldAfter = gold(a);
        report.check(goldAfter - goldBefore == settlement.getGoldGain(), "第 9 步 金币只增一次（= gold_gain）",
                goldBefore + " → " + goldAfter + "，gold_gain=" + settlement.getGoldGain(), REF_APPLY);
        BattleSupport.sleep(NO_REPEAT_WINDOW);
        long ends = a.connection().inbox().snapshot(0).stream().filter(r -> a.lobby().isLobbyPush(r, battleIds.battleEnd(), x6)).count();
        long goldLater = gold(a);
        report.check(ends == 1 && goldLater == goldAfter, "第 9 步 再等 10 s：没有重复的 150、金币不再变", ends + " 条 150，金币 " + goldLater, REF_APPLY);
        offlineOk = ends == 1 && goldAfter - goldBefore == settlement.getGoldGain() && goldLater == goldAfter;
    }

    // ================================================================ 第 10 步

    private void confirmedThenDestroyed() throws RobotException {
        long x7 = battleIdSequence.next(System.currentTimeMillis());
        long battleDeadline = deadline(SHORT_DEADLINE);
        report.note("确认后销毁 X7=" + uid(x7));
        int lobbyMark = a.mark();
        GatherOutcome g = admin.gather(DevGather.solo(BattleAdminClient.GATHER_CREATE, x7, MATCH_MODE_PVE, DUNGEON_1, SEED, battleDeadline,
                deadline(PREPARE_DEADLINE), a.id()));
        report.check(g.ok(), "第 10 步 dev gather CREATE X7（期限 now + 20 s）", g.describe(), "scene-battle-spec §7.18");
        if (!g.ok()) {
            throw new RobotException("建房失败：" + g.describe());
        }
        a.lobby().awaitStart(lobbyMark, x7);
        BattleSupport.sleep(CONFIRM_SETTLE);
        BattleAdminClient.HttpResult cancel = admin.cancelPrepare(a.id(), x7);
        int stillFrozen = enterSceneTip(a, otherWorld(a.sceneInfo().getSceneConfigId()));
        report.check(cancel.status() == 204 && stillFrozen == TIP_ENTER_SCENE_FAILED, "第 10 步 FIGHTING 拒绝取消（B1）：取消回 204，63 仍回 3023",
                "cancel=" + cancel.status() + " 63 tip=" + stillFrozen, "scene-battle-spec §7.6");
        admin.destroy(x7, "robot_battle_settle");
        long recoveredAt = pollUntil(Duration.ofMillis(battleDeadline - System.currentTimeMillis()).plus(FIGHTING_GRACE).plus(REAPER_SLACK),
                Duration.ofSeconds(1), () -> enterSceneTip(a, otherWorld(a.sceneInfo().getSceneConfigId())) != TIP_ENTER_SCENE_FAILED);
        long voidAt = battleDeadline + FIGHTING_GRACE.toMillis();
        report.check(recoveredAt >= 0 && recoveredAt >= voidAt - 1_000, "第 10 步 销毁后 63 一直 3023，期限 + 10 s 宽限之后 reaper 判废、63 恢复",
                recoveredAt < 0 ? "一直没恢复" : "期限 + 10 s 之后 " + (recoveredAt - voidAt) + " ms 恢复", "scene-battle-spec §7.9、D21");
        long ends = a.connection().inbox().snapshot(lobbyMark).stream().filter(r -> a.lobby().isLobbyPush(r, battleIds.battleEnd(), x7)).count();
        report.check(ends == 0, "第 10 步 销毁的局 A 收不到 150", ends + " 条", "scene-battle-spec §7.9");
    }

    // ================================================================ 第 11 步

    /**
     * 与 5.2 互斥（§13.8 第 11 步，只在双 scene 切片上跑）：PREPARING 时 63 指向另一节点上的场景 → 应答里就是 3023（战斗闸先于换图在途的 3014，
     * 也不是「先受理 {0}、选目标后再推 23」），A 留在原地；取消后同一条 63 跨节点成功（79 是目标场景、自己的 21 是新实体号）。
     * 调用时 A 没有冻结也没有锁，还在登录时的场景里。
     */
    private void crossNodeExclusion() throws RobotException {
        boolean declared = sceneMetricsUrls.size() >= 2;
        SceneInfoComp here = a.sceneInfo();
        // B 登录时与 A 同图不同频道（第 1 步已把它换到 A 的实例）→ B 原来的频道就在另一个节点上
        SceneInfoComp remote = BattleSettleChecks.remoteChannel(here, Arrays.asList(bOrigin, b == null ? null : b.sceneInfo()));
        if (remote == null && declared) {
            remote = placeProbe(here);
        }
        secondNodeChannel = remote;
        if (remote != null && !declared) {
            // 这一步照跑（多一份覆盖），但跑完 A 留在另一个节点：第 13 步只有一个节点的指标可看，判不成。现在就写明，第 13 步据此给出一条写明原因的失败
            report.note("第 11 步 发现第二个 scene 节点（同图另一个频道 " + describe(remote) + "），但 --scene-metrics-url 只给了 "
                    + sceneMetricsUrls.size() + " 个地址：这一步之后 A 换到另一个节点，第 13 步的 scene 指标要两个节点都抓到才判得了。"
                    + "双 scene 切片把两个节点的管理端口都给上（逗号分隔，如 --scene-metrics-url http://127.0.0.1:18104,http://127.0.0.1:18114）");
        }
        if (remote == null) {
            if (declared) {
                report.fail("第 11 步 落位：找到另一个 scene 节点上的同图频道", "--scene-metrics-url 给了 " + sceneMetricsUrls.size()
                        + " 个地址（声明双 scene 切片），但 B 与探针账号登录 " + CrossNodeScenario.MAX_PLACEMENT_ATTEMPTS
                        + " 次都落在 A 的频道 " + describe(here) + "；需要 XM_SCENE_NODES=2、per-node 覆盖、每图每节点一个频道", REF_CROSS);
            } else {
                report.note("第 11 步（与 5.2 互斥）跳过：A、B 登录时在同一个频道 " + describe(here) + "，没有发现另一个 scene 节点"
                        + "（单 scene 切片）。双 scene 切片（XM_SCENE_NODES=2 tools/local/start-slice.sh）上要强制跑这一步，把两个节点的管理端口"
                        + "都传给 --scene-metrics-url（逗号分隔，如 http://127.0.0.1:18104,http://127.0.0.1:18114）");
            }
            return;
        }
        report.note("第 11 步 跨节点目标 " + describe(remote) + "（A 在 " + describe(here) + "）");

        long x = battleIdSequence.next(System.currentTimeMillis());
        GatherOutcome prepared = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        if (!prepared.ok()) {
            throw new RobotException("第 11 步 只备战失败，互斥无从验证：" + prepared.describe());
        }
        preparedOnly.add(x);
        int rejectMark = a.mark();
        int frozenTip = switchScene(a, remote.getSceneConfigId(), remote.getSceneId());
        BattleSupport.sleep(MOVE_SILENCE);
        long enters = CrossNodeScenario.countOf(a.connection().inbox().snapshot(rejectMark), ids.notifyEnterScene());
        report.check(frozenTip == TIP_ENTER_SCENE_FAILED && enters == 0 && CrossNodeScenario.sameScene(a.sceneInfo(), here),
                "第 11 步 PREPARING 时 63 指向另一节点的场景 → 应答 3023（不是 3014、不是先受理），A 留在原地",
                "tip=" + frozenTip + "，79 " + enters + " 条，A 在 " + describe(a.sceneInfo()), REF_CROSS);

        BattleAdminClient.HttpResult cancelled = admin.cancelPrepare(a.id(), x);
        report.check(cancelled.status() == 204, "第 11 步 取消备战（204）", "status=" + cancelled.status() + " " + cancelled.text(),
                "scene-battle-spec §7.6");
        if (cancelled.status() == 204) {
            preparedOnly.remove(x);
        }
        if (frozenTip == 0) {
            // 闸没挡住，A 已经换过去了：「取消后同一条 63 成功」无从区分，到此为止（上面已记失败）
            return;
        }
        // 删锁是异步脚本；跨节点换图本身不看锁，这里只是让后面的步骤从干净的状态开始
        BattleSupport.sleep(Duration.ofMillis(300));
        long oldEntity = a.entity;
        int crossMark = a.mark();
        int crossTip = switchScene(a, remote.getSceneConfigId(), remote.getSceneId());
        report.check(crossTip == 0 && CrossNodeScenario.sameScene(a.sceneInfo(), remote), "第 11 步 取消后同一条 63 跨节点成功：应答 {0}、79 是目标场景",
                "tip=" + crossTip + "，A 在 " + describe(a.sceneInfo()) + "，目标 " + describe(remote), REF_CROSS);
        if (crossTip != 0) {
            return;
        }
        Optional<Received> selfActor = a.connection().await(crossMark, r -> CrossNodeScenario.findActor(ids, r, a.id()) != null, observeTimeout);
        ActorCreateS2C self = selfActor.map(r -> CrossNodeScenario.findActor(ids, r, a.id())).orElse(null);
        report.check(self != null && self.getEntity() != 0 && self.getEntity() != oldEntity, "第 11 步 跨节点后收到自己的 21（新实体号）",
                self == null ? observeTimeout.toMillis() + " ms 内没有收到" + a.connection().describeSince(crossMark)
                        : "entity " + oldEntity + " → " + self.getEntity(), REF_CROSS);
        if (self != null) {
            a.entity = self.getEntity();
            a.at = Vec3.of(self.getTransform().getLocation());
        }
        report.note("第 11 步之后 A 留在另一个节点的场景 " + describe(a.sceneInfo()) + "，后面的步骤在那个节点上跑");
    }

    /**
     * 声明了双 scene 切片、而 A、B 登录时恰好同频道：用探针账号 C 落位（做法同 {@link CrossNodeScenario} 的账号 2：没落到另一个频道就发 LeaveGame
     * 断开、等节点目录刷新后重登，至多 {@link CrossNodeScenario#MAX_PLACEMENT_ATTEMPTS} 次）。返回 C 落到的另一个频道（C 已登出），没有为 null。
     */
    private SceneInfoComp placeProbe(SceneInfoComp here) throws RobotException {
        for (int attempt = 1; attempt <= CrossNodeScenario.MAX_PLACEMENT_ATTEMPTS; attempt++) {
            Bot probe = enter("C", accountC);
            SceneInfoComp landed = probe.sceneInfo();
            leave(probe);
            if (CrossNodeScenario.onDifferentChannels(here, landed)) {
                report.note("第 11 步 探针账号 C 第 " + attempt + " 次登录落在另一个频道 " + describe(landed));
                return landed;
            }
            if (attempt < CrossNodeScenario.MAX_PLACEMENT_ATTEMPTS) {
                BattleSupport.sleep(CrossNodeScenario.DIRECTORY_REFRESH_WAIT);
            }
        }
        return null;
    }

    // ================================================================ 第 12 步

    /**
     * 队伍视图的前半（§13.8 第 12 步）：A 建单人队（视图里只有自己这一名成员；{@code in_battle} 是 xm-team 在组视图时按战斗锁算的，对谁都一样）→
     * 备战前 false → 只备战后 true（锁从备战起就在）→ 取消后变回 false。队伍留到场景结束：第 6–7 步里还要在战斗中、销账后各采样一次。
     */
    private void teamViewPrepareCancel() throws RobotException {
        TeamResponse created = a.call(createTeam, CreateTeamRequest.getDefaultInstance(), TeamResponse.parser());
        int tip = created.getErrorMessage().getId();
        teamId = created.getTeam().getTeamId();
        // 4003 = 已在队伍里（带当前视图）：新号不会遇到，重跑同一个 run-tag 时会，照用
        report.check((tip == 0 || tip == TIP_MEMBER_IN_TEAM) && teamId != 0 && BattleSettleChecks.inBattle(created.getTeam(), a.id()) != null,
                "第 12 步 A 建单人队，视图里有自己这名成员", TeamScenario.describe(created), REF_TEAM);
        if (teamId == 0) {
            throw new RobotException("第 12 步 建队失败，队伍视图无从验证：" + TeamScenario.describe(created));
        }
        expectInBattle(false, Duration.ZERO, "第 12 步 备战前 TeamMemberView.in_battle = false");

        long x = battleIdSequence.next(System.currentTimeMillis());
        GatherOutcome prepared = admin.gather(DevGather.solo(BattleAdminClient.GATHER_PREPARE_ONLY, x, MATCH_MODE_PVE, DUNGEON_1, SEED,
                deadline(LONG_DEADLINE), deadline(PREPARE_DEADLINE), a.id()));
        if (!prepared.ok()) {
            throw new RobotException("第 12 步 只备战失败，队伍视图无从验证：" + prepared.describe());
        }
        preparedOnly.add(x);
        expectInBattle(true, TEAM_VIEW_WAIT, "第 12 步 成员备战后 TeamMemberView.in_battle = true（锁从备战起就在）");
        BattleAdminClient.HttpResult cancelled = admin.cancelPrepare(a.id(), x);
        report.check(cancelled.status() == 204, "第 12 步 取消备战（204）", "status=" + cancelled.status() + " " + cancelled.text(),
                "scene-battle-spec §7.6");
        if (cancelled.status() == 204) {
            preparedOnly.remove(x);
        }
        expectInBattle(false, TEAM_VIEW_WAIT, "第 12 步 取消备战后 in_battle 变回 false（锁已删）");
        // 删锁是异步脚本：给它一点时间先于下一次 PREPARE_LOCK 落地
        BattleSupport.sleep(Duration.ofMillis(300));
    }

    /**
     * 嵌在别的步骤里的队伍视图采样（第 6 步确认之后、第 7 步销账放锁之后）：失败记在第 12 步名下，不算到外层步骤头上；抛出的异常也只记一条失败，
     * 不打断外层步骤。第 12 步前半没建成队时跳过（那里已经记过失败）。
     */
    private void teamViewSample(boolean expected, Duration budget, String name) {
        if (teamId == 0) {
            return;
        }
        long before = report.failures();
        try {
            expectInBattle(expected, budget, name);
        } catch (RobotException | RuntimeException e) {
            report.fail(name, message(e), REF_TEAM);
        }
        stepFailures.attribute("12", report.failures() - before);
    }

    /** 每 {@link #TEAM_VIEW_POLL} 拉一次 A 的队伍视图，直到自己这名成员的 in_battle 等于期望或超过 {@code budget}；记一条检查。 */
    private void expectInBattle(boolean expected, Duration budget, String name) throws RobotException {
        long start = System.nanoTime();
        Boolean[] last = new Boolean[1];
        long reachedAt = pollUntil(budget, TEAM_VIEW_POLL, () -> {
            last[0] = BattleSettleChecks.inBattle(myTeamView(), a.id());
            return last[0] != null && last[0] == expected;
        });
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        report.check(reachedAt >= 0, name, reachedAt >= 0 ? tookMs + " ms 内读到 in_battle=" + expected
                : (last[0] == null ? "视图里没有 A 这名成员" : "in_battle 一直是 " + last[0]) + "（等了 " + tookMs + " ms）", REF_TEAM);
    }

    /**
     * A 的队伍视图（207 GetMyTeam，不带 notify_online）。队伍不在了（A 重登期间被回收之类）就重建一个单人队再读：in_battle 是读视图时按锁现算的，
     * 换一支队伍不影响结论。
     */
    private TeamView myTeamView() throws RobotException {
        TeamResponse response = a.call(getMyTeam, GetMyTeamRequest.getDefaultInstance(), TeamResponse.parser());
        if (response.getErrorMessage().getId() != 0 || !response.hasTeam()) {
            throw new RobotException("GetMyTeam 期望受理且带视图，实得 " + TeamScenario.describe(response));
        }
        if (response.getTeam().getTeamId() != 0) {
            return response.getTeam();
        }
        TeamResponse recreated = a.call(createTeam, CreateTeamRequest.getDefaultInstance(), TeamResponse.parser());
        if (recreated.getTeam().getTeamId() == 0) {
            throw new RobotException("A 已不在队伍里，重建单人队也失败：" + TeamScenario.describe(recreated));
        }
        report.note("第 12 步 A 原来的队伍 " + uid(teamId) + " 已不在，重建单人队 " + uid(recreated.getTeam().getTeamId()));
        teamId = recreated.getTeam().getTeamId();
        return recreated.getTeam();
    }

    /** 场景结束时解散第 12 步建的单人队（尽力而为：连接已断、队伍已不在都不影响结论）。 */
    private void disbandTeamQuietly() {
        if (teamId == 0 || a == null || !a.connection().isOpen()) {
            return;
        }
        try {
            a.call(disbandTeam, DisbandTeamRequest.newBuilder().setExpectedTeamId(teamId).build(), TeamResponse.parser());
        } catch (RobotException | RuntimeException ignored) {
            // 新号每次都换，留下一支单人队不影响下一轮
        }
        teamId = 0;
    }

    // ================================================================ 工具

    /** 一个已进场的机器人（大厅连接 + 节拍）。只在场景线程上用。 */
    private final class Bot {
        final String name;
        final EnteredPlayer player;
        /** 自己当前的实体号与位置（进场时的 21；第 11 步跨节点换图后更新）。 */
        long entity;
        Vec3 at;
        private SceneInfoComp scene;
        private final GuildScenario.Pacer pacer = new GuildScenario.Pacer(Duration.ofMillis(60), RATE_WINDOW, 3);

        Bot(String name, EnteredPlayer player, long entity, Vec3 at) {
            this.name = name;
            this.player = player;
            this.entity = entity;
            this.at = at;
            this.scene = player.sceneInfo();
        }

        long id() {
            return player.playerId();
        }

        GameConnection connection() {
            return player.connection();
        }

        SceneInfoComp sceneInfo() {
            return scene;
        }

        int mark() {
            return connection().inbox().size();
        }

        LobbyBot lobby() {
            return new LobbyBot(name, player, battleIds, requestTimeout);
        }

        long request(int messageId, Message body) throws RobotException {
            pace(messageId);
            return connection().send(messageId, body);
        }

        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            pace(messageId);
            return connection().call(messageId, body, parser, requestTimeout);
        }

        <T extends Message> int callTip(int messageId, Message body, Parser<T> parser, java.util.function.ToIntFunction<T> tip)
                throws RobotException {
            return tip.applyAsInt(call(messageId, body, parser));
        }

        private void pace(int messageId) throws RobotException {
            long wait = pacer.delayNanos(messageId, System.nanoTime());
            if (wait > 0) {
                BattleSupport.sleep(Duration.ofNanos(wait));
            }
            pacer.record(messageId, System.nanoTime());
        }
    }

    /** 结算前的快照。 */
    private record Baseline(long gold, Map<Integer, Long> bag) {
    }

    private Baseline baseline(Bot bot) throws RobotException {
        return new Baseline(gold(bot), bagTotals(bot));
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
     * 跑一步。这一步里新增的失败检查（{@code report.check(false, …)} / {@code expectTip} 不符）与抛出的异常都记到它名下，汇总行据此带出步骤号
     * （§13.8 第 14 步；原来只在抛异常时登记，普通检查失败会输出 {@code step=?}）。
     */
    private void step(String name, Step body) {
        long baseline = stepFailures.enter(report.failures());
        try {
            body.run();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断：第 " + name + " 步", message(e), REF);
            cleanup();
        } finally {
            stepFailures.leave(name, baseline, report.failures());
        }
    }

    /** 尽力取消还可能冻着的只备战局（阶段中断 / 结束时）。 */
    private void cleanup() {
        if (a == null) {
            return;
        }
        for (Long battleId : List.copyOf(preparedOnly)) {
            try {
                admin.cancelPrepare(a.id(), battleId);
            } catch (RobotException | RuntimeException ignored) {
                // 尽力而为：失败时冻结按备战期限由 reaper 摘
            }
            preparedOnly.remove(battleId);
        }
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        closeables.add(player.connection());
        ActorCreateS2C self = flow.awaitSelfActor(player, observeTimeout);
        return new Bot(name, player, self.getEntity(), Vec3.of(self.getTransform().getLocation()));
    }

    /** 契约里的干净登出：发 LeaveGame 后关连接。 */
    private void leave(Bot bot) throws RobotException {
        bot.request(leaveGame, LeaveGameRequest.getDefaultInstance());
        BattleSupport.sleep(Duration.ofMillis(200));
        bot.connection().close();
    }

    private Direct open(String name, String host, int port) throws RobotException {
        Direct direct = Direct.open(client, name, host, port, battleIds);
        closeables.add(direct);
        return direct;
    }

    /** 63 换到 {@code configId}（scene_id = 0：按配置号选频道）；应答 0 时等 79 并更新所在场景。返回应答码。 */
    private int enterSceneTip(Bot bot, int configId) throws RobotException {
        return switchScene(bot, configId, 0);
    }

    private int switchScene(Bot bot, int configId, long sceneId) throws RobotException {
        int mark = bot.mark();
        EnterSceneC2SResponse response = bot.call(enterScene, EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(sceneId)).build(), EnterSceneC2SResponse.parser());
        int tip = response.getErrorMessage().getId();
        if (tip == 0) {
            Optional<Received> arrived = bot.connection().await(mark, r -> r.messageId() == ids.notifyEnterScene(), requestTimeout);
            if (arrived.isEmpty()) {
                throw new RobotException(bot.name + "：63 应答 {0} 之后 " + requestTimeout.toMillis() + " ms 内没有 79" + bot.connection().describeSince(mark));
            }
            bot.scene = arrived.get().parse(EnterSceneS2C.parser()).getSceneInfo();
        }
        return tip;
    }

    /** World 表里与 {@code current} 不同的第一张世界地图。 */
    private int otherWorld(int current) throws RobotException {
        for (WorldTable row : tables.world().all()) {
            if (row.getSceneId() != 0 && row.getSceneId() != current) {
                return row.getSceneId();
            }
        }
        throw new RobotException("World 表里没有第二张世界地图，换不了图");
    }

    /**
     * 168 的请求：池 1 的第一个维度加 1 点。空的 allocated 在入口就回 1005（基线 player_attribute_handler.cpp 先判参数、再进 CheckWritable），
     * 打不到战斗闸，所以必须带一个维度。
     */
    private AllocateAttributePointsRequest allocateOne() {
        int dimension = tables.attributeDimension().all().stream().filter(row -> row.getPoolId() == POOL)
                .mapToInt(com.game.table.AttributeDimensionTable::getId).findFirst().orElse(1);
        return AllocateAttributePointsRequest.newBuilder().setPoolId(POOL).putAllocated(dimension, 1).build();
    }

    private int allocateTip() throws RobotException {
        return a.callTip(allocate, allocateOne(), AllocateAttributePointsResponse.parser(),
                r -> r.getErrorMessage().getId());
    }

    private long gold(Bot bot) throws RobotException {
        GetCurrencyListResponse response = bot.call(getCurrencyList, GetCurrencyListRequest.getDefaultInstance(), GetCurrencyListResponse.parser());
        if (response.getCurrency().getValuesCount() <= GOLD) {
            throw new RobotException("GetCurrencyList（54）没有金币槽");
        }
        return response.getCurrency().getValues(GOLD);
    }

    private Map<Integer, Long> bagTotals(Bot bot) throws RobotException {
        List<BagInfo> bags = new ArrayList<>();
        for (int bagType : new int[] {BAG_INVENTORY, BAG_TEMPORARY}) {
            GetBagResponse bag = bot.call(getBag, GetBagRequest.newBuilder().setBagType(bagType).build(), GetBagResponse.parser());
            if (bag.getErrorMessage().getId() != 0) {
                throw new RobotException("191 读背包 " + bagType + " 回 " + bag.getErrorMessage().getId());
            }
            bags.add(bag.getBag());
        }
        return BattleSettleChecks.bagTotals(bags);
    }

    private AttributePanelInfo panel(Bot bot) throws RobotException {
        GetAttributePanelResponse response = bot.call(getPanel, GetAttributePanelRequest.getDefaultInstance(), GetAttributePanelResponse.parser());
        return response.getPanel();
    }

    private static PlayerMissionInfo mission(GetMissionListResponse list, int missionId) {
        return list.getMissionsList().stream().filter(m -> m.getMissionId() == missionId).findFirst().orElse(null);
    }

    /**
     * 每 {@code interval} 检查一次，直到成立或超过 {@code budget}。成立时返回那一刻的 Unix 毫秒，超时返回 −1。
     */
    private static long pollUntil(Duration budget, Duration interval, Condition condition) throws RobotException {
        long deadline = System.nanoTime() + Math.max(0, budget.toNanos());
        while (true) {
            if (condition.test()) {
                return System.currentTimeMillis();
            }
            if (System.nanoTime() >= deadline) {
                return -1;
            }
            BattleSupport.sleep(interval);
        }
    }

    private static int indexOf(GameConnection connection, int messageId) {
        return connection.inbox().snapshot(0).stream().filter(r -> r.messageId() == messageId).mapToInt(Received::index).findFirst().orElse(-1);
    }

    private void expectTip(int actual, int expected, String name, String ref) {
        report.check(actual == expected, name, "tip=" + actual + (actual == expected ? "" : "，期望 " + expected), ref);
    }

    private void expectNot(int actual, int unexpected, String name, String ref) {
        report.check(actual != unexpected, name, "tip=" + actual, ref);
    }

    private void metricGrew(String before, String after, String metric, double atLeast, String... labels) {
        double b0 = com.game.robot.client.AdminClient.sum(before, metric, labels);
        double a0 = com.game.robot.client.AdminClient.sum(after, metric, labels);
        report.check(a0 - b0 >= atLeast, "第 13 步 指标 " + metric + (labels.length == 0 ? "" : "{" + String.join(",", labels) + "}") + " 增长 ≥ "
                + (long) atLeast, b0 + " → " + a0, "scene-battle-spec §9");
    }

    /**
     * 抓全部 scene 节点的指标，文本拼在一起（{@code AdminClient.sum} 对同名序列求和，拼接即按节点求和）。任何一个地址抓不到就整体不判：
     * 只拿到一部分节点的数会把「A 在另一个节点上」误判成没有增长。
     */
    private String scrapeScene() {
        StringBuilder all = new StringBuilder();
        for (String url : sceneMetricsUrls) {
            try {
                all.append(SceneAdminClient.scrape(url, requestTimeout)).append('\n');
            } catch (RobotException e) {
                report.note("抓 scene 指标失败（第 13 步的 scene 指标不判）：" + e.getMessage());
                return null;
            }
        }
        if (sceneMetricsUrls.isEmpty()) {
            report.note("--scene-metrics-url 为空，第 13 步的 scene 指标不判");
            return null;
        }
        return all.toString();
    }

    private static long deadline(Duration fromNow) {
        return System.currentTimeMillis() + fromNow.toMillis();
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    private static String describe(SceneInfoComp info) {
        return CrossNodeScenario.describe(info);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
