package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoS2C;
import com.game.robot.client.AdminClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import com.game.robot.flow.PlayerFlow;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.SceneErrorTip;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 副本（批次 5.3，dungeon-mirror-spec §12.6 {@code dungeon}）。副本只有 xm-scene dev / test 实例管理口一个入口（Q3），需要 dev 运行模式与运维令牌
 * （{@code XM_ADMIN_TOKEN} 或 {@code run/xm-admin-token}）；副本恒建在收到请求的节点上（{@code --scene-admin-url}），scene 侧指标也抓这里。
 *
 * <p>两个新账号 {@code 前缀 + dg + 标签 + _a / _b}：
 * <ol>
 *   <li><b>管理口拒绝码</b>：Dungeon 表外的号与 0 → 3005；销毁号 0 → 3005、不存在的号 → 3000。</li>
 *   <li><b>建副本</b>：Dungeon 第一行 → {tip 0, 新号, scene_config_id = Dungeon.scene_id, 节点号非 0}。</li>
 *   <li><b>进副本</b>：A 发 63 {scene_id = 副本号} → {0} → 79 逐字段 {副本地图, 副本号, mirror 0, dungeon = Dungeon.id, creators 空}，
 *       自己的 21 在副本地图的 BaseScene 出生点；43 → 31 同 79。</li>
 *   <li><b>副本里</b>：63 {scene_id = 副本号} → 3008；只带副本地图 → 不留在副本也换不出去（同步 3023，或 {0} 后 23 {3023}），没有 79。</li>
 *   <li><b>第二人</b>：B 同样进副本，含 A 的 47，A 收到 B 的 21。</li>
 *   <li><b>管理口销毁</b> → 0：A、B 都收到默认主世界的 79（不是副本）与出生点上的 21，没有 23；重复销毁 → 0 或 3000；
 *       按号再进被拒、没有 79；销毁主世界频道 → 3005。</li>
 *   <li><b>指标</b>：scene 的 created / destroyed_admin 增长、active 不高于开始时；scene-manager 的 ok 增长。</li>
 * </ol>
 * {@code --expect-dev deny}（prod 进程）只核对两个管理口都回 403。依据：dungeon-mirror-spec §5.2、§5.6、§5.7、§6.13、§8.2、§12.6；
 * PARITY「副本与镜像场景」行。
 */
public final class DungeonScenario {

    private static final String REF = "dungeon-mirror-spec §12.6 dungeon";
    private static final String REF_ADMIN = "dungeon-mirror-spec §6.13（dev 实例管理口）；xm-api scene_admin.proto";
    private static final String REF_ACCEPTED = "dungeon-mirror-spec §5.4；scene 契约 §4.3（无错 = 已受理）";
    private static final String REF_INFO = "dungeon-mirror-spec §5.2（副本的 scene_info）";
    private static final String REF_SPAWN = "dungeon-mirror-spec §5.6（进出副本换了地图，落 BaseScene 出生点）";
    private static final String REF_QUERY = "dungeon-mirror-spec §5.2 末条（43 → 31 在实例里带实例字段）";
    private static final String REF_CURRENT = "scene 契约 §4.3（scene_id 就是当前场景回 3008）";
    private static final String REF_R3 = "dungeon-mirror-spec R3（实例里只带地图必离开实例；副本地图不是世界地图 → 3023）";
    private static final String REF_DESTROY = "dungeon-mirror-spec §5.7 末行、§6.11、D17（显式销毁：居民改派默认主世界出生点，无 tip）";
    private static final String REF_GONE = "dungeon-mirror-spec §5.7（已销毁的实例按号进入失败）";
    private static final String REF_METRICS = "dungeon-mirror-spec §8.2";

    static final int PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    static final int NOT_FOUND = SceneErrorTip.scene_error.kEnterSceneNotFound_VALUE;
    static final int IN_CURRENT_SCENE = SceneErrorTip.scene_error.kEnterSceneYouInCurrentScene_VALUE;
    /** 表外号从这里起找。 */
    static final int MISSING_DUNGEON_START = 999;
    private static final double LOCATION_EPS = 1e-3;

    private final SceneMoves moves;
    private final ConfigTables tables;
    private final SceneAdminClient admin;
    private final MetricsSource sceneMetrics;
    private final MetricsSource smMetrics;
    private final String sceneManagerMetricsUrl;
    private final String prefix;
    private final String runTag;
    private final boolean expectDevAllowed;
    private final CheckReport report = new CheckReport();

    private String sceneBefore;
    private String smBefore;

    /**
     * @param admin                  xm-scene dev 实例管理口（副本建在它所在的节点上）
     * @param sceneManagerMetricsUrl xm-scene-manager 管理端口（抓取号指标）
     * @param expectDevAllowed       管理口开放（dev / test）还是回 403（prod）
     */
    public DungeonScenario(PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, Path tableDir, SceneAdminClient admin,
                           String sceneManagerMetricsUrl, String accountPrefix, String runTag, boolean expectDevAllowed,
                           Duration requestTimeout, Duration observeTimeout) {
        this.moves = new SceneMoves(flow, ids, registry, requestTimeout, observeTimeout);
        this.tables = ConfigTables.load(tableDir);
        this.admin = admin;
        this.sceneMetrics = admin::scrapeMetrics;
        this.smMetrics = () -> SceneAdminClient.scrape(sceneManagerMetricsUrl, requestTimeout);
        this.sceneManagerMetricsUrl = sceneManagerMetricsUrl;
        this.prefix = accountPrefix;
        this.runTag = runTag;
        this.expectDevAllowed = expectDevAllowed;
    }

    public static String accountName(String prefix, String runTag, String role) {
        return prefix + "dg" + runTag + "_" + role;
    }

    public String accountA() {
        return accountName(prefix, runTag, "a");
    }

    public CheckReport run() {
        try {
            if (!admin.hasToken()) {
                throw new RobotException("没有运维令牌：设环境变量 XM_ADMIN_TOKEN（与 xm-scene 相同），或先用 tools/local/start-slice.sh 生成 "
                        + "run/xm-admin-token（从仓库根目录运行 robot）");
            }
            if (expectDevAllowed) {
                runChecks();
            } else {
                prodChecks();
            }
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            moves.closeAll();
        }
        return report;
    }

    // ------------------------------------------------------------------ prod

    private void prodChecks() throws RobotException {
        DungeonTable row = firstDungeon();
        SceneAdminClient.HttpResult create = admin.createRaw(row.getId());
        report.check(create.status() == 403, "prod：建副本管理口回 403", "HTTP " + create.status() + " " + create.text()
                + SceneAdminClient.statusHint(create.status()), REF_ADMIN);
        SceneAdminClient.HttpResult destroy = admin.destroyRaw(1);
        report.check(destroy.status() == 403, "prod：销毁实例管理口回 403", "HTTP " + destroy.status() + " " + destroy.text()
                + SceneAdminClient.statusHint(destroy.status()), REF_ADMIN);
    }

    // ------------------------------------------------------------------ dev / test

    private void runChecks() throws RobotException {
        DungeonTable row = firstDungeon();
        int dungeonConfigId = row.getId();
        int home = InstanceChecks.homeWorld(tables);
        baseline();

        // 1. 管理口拒绝码
        Set<Integer> present = new HashSet<>();
        for (DungeonTable r : tables.dungeon().all()) {
            present.add(r.getId());
        }
        int missing = InstanceChecks.firstMissing(present, MISSING_DUNGEON_START);
        SceneAdminClient.Created bad = admin.createDungeon(missing);
        report.check(bad.tipId() == PARAM_ERROR && bad.sceneId() == 0, "管理口：Dungeon 表外的号 " + missing + " → " + PARAM_ERROR,
                bad.describe(), REF_ADMIN);
        SceneAdminClient.Created zero = admin.createDungeon(0);
        report.check(zero.tipId() == PARAM_ERROR && zero.sceneId() == 0, "管理口：dungeon_config_id = 0 → " + PARAM_ERROR,
                zero.describe(), REF_ADMIN);
        int destroyZero = admin.destroy(0);
        report.check(destroyZero == PARAM_ERROR, "管理口：销毁 scene_id = 0 → " + PARAM_ERROR, "tip_id=" + destroyZero, REF_ADMIN);

        // 2. 建副本
        SceneAdminClient.Created created = admin.createDungeon(dungeonConfigId);
        report.check(created.tipId() == 0 && created.sceneId() != 0 && created.sceneConfigId() == row.getSceneId()
                        && created.sceneNodeId() != 0,
                "管理口建副本 Dungeon " + dungeonConfigId + "：{tip 0, 新号, scene_config_id = " + row.getSceneId() + ", 节点号}",
                created.describe(), REF_ADMIN);
        if (created.tipId() != 0 || created.sceneId() == 0) {
            throw new RobotException("建副本失败（" + created.describe() + "；1003 = 取号失败，3023 = scene-manager 拒绝或本地拒建），后续步骤无从验证");
        }
        long dungeonId = created.sceneId();
        int destroyMissing = admin.destroy(CrossNodeScenario.missingSceneId(List.of(dungeonId)));
        report.check(destroyMissing == NOT_FOUND, "管理口：销毁本节点没有的号 → " + NOT_FOUND, "tip_id=" + destroyMissing, REF_ADMIN);

        // 3. A 进副本
        ProbeBot a = moves.login(accountA(), "A");
        ProbeBot b = moves.login(accountName(prefix, runTag, "b"), "B");
        a = enterDungeon(a, created, dungeonConfigId, null, "A 进副本");
        SceneInfoS2C pushed = moves.querySceneInfo(a, "副本里 43");
        report.check(pushed.getSceneInfoCount() == 1 && pushed.getSceneInfo(0).equals(a.scene()),
                "副本里发 43：31 恰好一条、等于进副本时 79 的 scene_info",
                pushed.getSceneInfoList().stream().map(InstanceChecks::describe).toList() + "，期望 [" + InstanceChecks.describe(a.scene())
                        + "]", REF_QUERY);

        // 4. 副本里：3008、只带副本地图
        SceneMoves.Sent current = moves.send(a, InstanceChecks.joinRequest(dungeonId, 0), "再进当前副本");
        long enters = moves.quietEnters(a.connection(), current.mark());
        report.check(current.tip() == IN_CURRENT_SCENE && enters == 0, "副本里 63 {scene_id = 副本号}：应答 {" + IN_CURRENT_SCENE + "}、没有 79",
                "error_message.id=" + current.tip() + "，79 " + enters + " 条", REF_CURRENT);
        SceneMoves.Sent mapOnly = moves.send(a, InstanceChecks.mapRequest(created.sceneConfigId()), "只带副本地图");
        SceneMoves.Refusal refusal = moves.awaitRefusal(a, mapOnly);
        report.check(refusal.ok(), "副本里 63 {scene_config_id = " + created.sceneConfigId() + "}：不留在副本也换不出去"
                + "（同步 3023，或 {0} 后 23 {3023}），没有 79", refusal.describe(), REF_R3);

        // 5. B 进副本
        b = enterDungeon(b, created, dungeonConfigId, a, "B 进副本");

        // 6. 管理口销毁
        int markA = a.connection().inbox().size();
        int markB = b.connection().inbox().size();
        int destroyed = admin.destroy(dungeonId);
        report.check(destroyed == 0, "管理口销毁有人的副本 → 0（已受理）", "tip_id=" + destroyed, REF_DESTROY);
        if (destroyed != 0) {
            throw new RobotException("管理口销毁副本被拒 " + destroyed + "，后续步骤无从验证");
        }
        a = relocated(a, markA, home, dungeonId);
        b = relocated(b, markB, home, dungeonId);
        SceneMoves.sleep(SceneMoves.QUIET_WINDOW);
        List<Integer> tipsA = moves.tipsSince(a.connection(), markA);
        List<Integer> tipsB = moves.tipsSince(b.connection(), markB);
        report.check(tipsA.isEmpty() && tipsB.isEmpty(), "管理口销毁：A、B 都没收到 23",
                "A " + tipsA + "，B " + tipsB, REF_DESTROY);
        int again = admin.destroy(dungeonId);
        report.check(again == 0 || again == NOT_FOUND, "管理口重复销毁 → 0（还在排空）或 " + NOT_FOUND + "（已销毁）", "tip_id=" + again,
                REF_ADMIN);
        SceneMoves.Sent reenter = moves.send(a, InstanceChecks.joinRequest(dungeonId, 0), "销毁后按号进入");
        SceneMoves.Refusal gone = moves.awaitRefusal(a, reenter);
        report.check(gone.ok(), "销毁后按号进副本被拒（同步 3023，或 {0} 后 23 {3023}），没有 79", gone.describe(), REF_GONE);
        int world = admin.destroy(a.scene().getSceneId());
        report.check(world == PARAM_ERROR, "管理口：销毁主世界频道 → " + PARAM_ERROR + "（只能由频道计划销毁）",
                "scene_id=" + Long.toUnsignedString(a.scene().getSceneId()) + " tip_id=" + world, REF_ADMIN);

        // 7. 指标
        checkMetrics();
    }

    /** 发 63 {scene_id = 副本号} 并核对进副本的成功路径；{@code peer} 是已在副本里的人（可为 null）。 */
    private ProbeBot enterDungeon(ProbeBot bot, SceneAdminClient.Created created, int dungeonConfigId, ProbeBot peer, String label)
            throws RobotException {
        int markPeer = peer == null ? 0 : peer.connection().inbox().size();
        SceneMoves.Sent sent = moves.send(bot, InstanceChecks.joinRequest(created.sceneId(), 0), label);
        report.check(sent.tip() == 0, label + "：63 {scene_id = 副本号} 应答 {0}", "error_message.id=" + sent.tip(), REF_ACCEPTED);
        if (sent.tip() != 0) {
            throw new RobotException(label + "：63 被拒 " + sent.tip() + "，后续步骤无从验证");
        }
        SceneMoves.Arrival arrival = moves.arrive(bot, sent.mark(), label);
        List<String> problems = InstanceChecks.dungeonInfoProblems(arrival.info(), created.sceneId(), created.sceneConfigId(),
                dungeonConfigId);
        report.check(problems.isEmpty(), label + "：79 的 scene_info 逐字段为 {副本地图, 副本号, mirror 0, dungeon " + dungeonConfigId
                + ", creators 空}", InstanceChecks.describe(arrival.info()) + MirrorScenario.problemText(problems), REF_INFO);
        Vec3 spawn = InstanceChecks.spawn(tables, created.sceneConfigId());
        report.check(arrival.at().approx(spawn, LOCATION_EPS), label + "：落副本地图的 BaseScene 出生点",
                "自己的 21 位置 " + arrival.at() + "，期望 " + spawn, REF_SPAWN);
        if (peer != null) {
            Optional<ActorCreateS2C> seesPeer = moves.awaitActor(bot.connection(), sent.mark(), peer.playerId(), moves.observeTimeout());
            report.check(seesPeer.isPresent() && seesPeer.get().getEntity() == peer.entity(), label + "：收到含 " + peer.role() + " 的 47",
                    seesPeer.map(x -> "entity=" + x.getEntity()).orElse(moves.observeTimeout().toMillis() + " ms 内没收到"), REF);
            Optional<ActorCreateS2C> peerSees = moves.awaitActor(peer.connection(), markPeer, bot.playerId(), moves.observeTimeout());
            report.check(peerSees.isPresent() && peerSees.get().getEntity() == arrival.entity(),
                    label + "：副本里的 " + peer.role() + " 收到 " + bot.role() + " 的 21",
                    peerSees.map(x -> "entity=" + x.getEntity()).orElse(moves.observeTimeout().toMillis() + " ms 内没收到"), REF);
        }
        List<Integer> tips = moves.tipsSince(bot.connection(), sent.mark());
        report.check(tips.isEmpty(), label + "：没收到 23", tips.isEmpty() ? "" : "收到 23 " + tips, REF_ACCEPTED);
        return bot.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark());
    }

    /** 管理口销毁后：收到默认主世界的 79（不是副本）与出生点上的自己的 21。 */
    private ProbeBot relocated(ProbeBot bot, int mark, int home, long dungeonId) throws RobotException {
        String who = bot.role();
        Received notify = bot.connection().await(mark, r -> r.messageId() == moves.ids().notifyEnterScene(), moves.requestTimeout())
                .orElseThrow(() -> new RobotException("管理口销毁后 " + moves.requestTimeout().toMillis() + " ms 内 " + who + " 没收到 79"
                        + bot.connection().describeSince(mark)));
        SceneInfoComp info = notify.parse(EnterSceneS2C.parser()).getSceneInfo();
        List<String> problems = InstanceChecks.worldInfoProblems(info, home, dungeonId);
        report.check(problems.isEmpty(), "管理口销毁：" + who + " 被改派到默认主世界（79）",
                InstanceChecks.describe(info) + MirrorScenario.problemText(problems), REF_DESTROY);
        ActorCreateS2C self = moves.awaitActor(bot.connection(), mark, bot.playerId(), moves.observeTimeout())
                .orElseThrow(() -> new RobotException("管理口销毁后 " + who + " 没收到自己的 21" + bot.connection().describeSince(mark)));
        Vec3 at = self.hasTransform() && self.getTransform().hasLocation() ? Vec3.of(self.getTransform().getLocation()) : bot.at();
        Vec3 spawn = InstanceChecks.spawn(tables, home);
        report.check(at.approx(spawn, LOCATION_EPS), "管理口销毁：" + who + " 落默认主世界出生点",
                "自己的 21 位置 " + at + "，期望 " + spawn, REF_DESTROY);
        return bot.movedTo(info, self.getEntity(), at, mark);
    }

    // ------------------------------------------------------------------ 指标

    private void baseline() {
        try {
            String text = sceneMetrics.scrape();
            if (InstanceMetrics.present(text, InstanceMetrics.LIFECYCLE)) {
                sceneBefore = text;
            } else {
                report.fail("指标：scene 管理端口有批次 5.3 的实例指标", admin.baseUrl() + " 上没有 " + InstanceMetrics.LIFECYCLE, REF_METRICS);
            }
        } catch (RobotException e) {
            report.fail("指标：抓 scene 管理端口 " + admin.baseUrl(), e.getMessage(), REF_METRICS);
        }
        try {
            smBefore = smMetrics.scrape();
        } catch (RobotException e) {
            report.fail("指标：抓 scene-manager 管理端口 " + sceneManagerMetricsUrl, e.getMessage(), REF_METRICS);
        }
    }

    private void checkMetrics() {
        if (sceneBefore != null) {
            try {
                // 销毁「排空后销毁」：居民改派与销毁在同一个逻辑任务里，留一个请求超时的余量
                InstanceMetrics.Polled polled = InstanceMetrics.awaitDelta(sceneMetrics, sceneBefore, 1, moves.requestTimeout(),
                        InstanceMetrics.POLL_INTERVAL, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_DUNGEON,
                        InstanceMetrics.event("destroyed_admin"));
                report.check(polled.reached(), "指标：scene instance_lifecycle{kind=\"dungeon\",event=\"destroyed_admin\"} 增长",
                        "增量 " + (long) polled.delta(), REF_METRICS);
                String after = polled.text();
                double created = InstanceMetrics.delta(sceneBefore, after, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_DUNGEON,
                        InstanceMetrics.event("created"));
                report.check(created >= 1, "指标：scene instance_lifecycle{kind=\"dungeon\",event=\"created\"} 增长", "增量 " + (long) created,
                        REF_METRICS);
                double activeBefore = AdminClient.sum(sceneBefore, InstanceMetrics.INSTANCES, InstanceMetrics.KIND_DUNGEON,
                        InstanceMetrics.ACTIVE);
                double activeAfter = AdminClient.sum(after, InstanceMetrics.INSTANCES, InstanceMetrics.KIND_DUNGEON, InstanceMetrics.ACTIVE);
                report.check(activeAfter <= activeBefore, "指标：scene instances{kind=\"dungeon\",state=\"active\"} 回到不高于开始时",
                        "开始 " + (long) activeBefore + "，销毁后 " + (long) activeAfter, REF_METRICS);
            } catch (RobotException e) {
                report.fail("指标：抓 scene 管理端口 " + admin.baseUrl(), e.getMessage(), REF_METRICS);
            }
        }
        if (smBefore != null) {
            try {
                String smAfter = smMetrics.scrape();
                double grown = InstanceMetrics.delta(smBefore, smAfter, InstanceMetrics.SM_INSTANCE, InstanceMetrics.KIND_DUNGEON,
                        InstanceMetrics.result("ok"));
                report.check(grown >= 1, "指标：scene-manager instance_seconds_count{kind=\"dungeon\",result=\"ok\"} 增长",
                        "增量 " + (long) grown, REF_METRICS);
            } catch (RobotException e) {
                report.fail("指标：抓 scene-manager 管理端口 " + sceneManagerMetricsUrl, e.getMessage(), REF_METRICS);
            }
        }
    }

    /** Dungeon 表第一行 scene_id 非 0 的（§12.6 的 Dungeon 1 → BaseScene 17）。 */
    private DungeonTable firstDungeon() throws RobotException {
        for (DungeonTable row : tables.dungeon().all()) {
            if (row.getId() != 0 && row.getSceneId() != 0) {
                return row;
            }
        }
        throw new RobotException("Dungeon 表里没有 scene_id 非 0 的行（--table-dir 指错了？）");
    }
}
