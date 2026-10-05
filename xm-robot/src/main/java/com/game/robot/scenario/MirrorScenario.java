package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoS2C;
import com.game.robot.client.AdminClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import com.game.robot.flow.PlayerFlow;
import com.game.table.ConfigTables;
import com.game.table.MirrorTable;
import com.game.table.SceneErrorTip;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 镜像场景（批次 5.3，dungeon-mirror-spec §12.6 {@code mirror}）。单节点即可；双节点切片时 B 先按号换到 A 的频道（可能跨节点），
 * scene 侧指标只在镜像所在节点就是 {@code --scene-metrics-url} 时核对（否则记观察、回收改为固定等待）。
 *
 * <p>三个新账号 {@code 前缀 + mr + 标签 + _a / _b / _c}：
 * <ol>
 *   <li><b>落位</b>：A、B 登录，同处默认主世界的同一频道（不同就让 B 发 63 {scene_id = A 的频道}）。</li>
 *   <li><b>建镜像</b>：A 发 63 {mirror_config_id = M, scene_id = 0}：应答 {0} 先于 79；79 逐字段为 {源地图, 新号, M, 0, {A: true}}；
 *       自己的 21 与原位置水平偏差 ≤ 0.5 m；源频道的 B 收到 A 的 51；A 全程没收到 23。隔离：A 看不见 B、B 看不见镜像里的 A，
 *       A 在镜像里走动 B 收不到 66。</li>
 *   <li><b>43</b>：A 发 43 → 31 恰好一条、等于 79 的 scene_info。</li>
 *   <li><b>R1</b>：镜像存在时 C 新登录落在主世界频道（不落进镜像），C 与镜像里的 A 互不可见。</li>
 *   <li><b>镜像里再建镜像</b> → 3005，没有 79。</li>
 *   <li><b>按号加入</b>：B 发 63 {scene_id = 镜像, mirror_config_id = M} → {0}、79（creators 仍是 {A: true}）、含 A 的 47；A 收到 B 的 21；
 *       A 走动，B 收到 66。</li>
 *   <li><b>断线重连</b>：A 断开（B 收到 51）后立即重连 → 回到镜像（Q9）。</li>
 *   <li><b>离开</b>：A、B 先后发 63 {scene_config_id = 源地图} → 主世界频道（mirror 0、号不是镜像）、坐标保留；后走的 B 收到 A 的 51。</li>
 *   <li><b>连发</b>：A 连发两条 63 镜像 → {0}、3014，恰好一条 79（第二个镜像）；A 再回主世界。</li>
 *   <li><b>空置回收</b>：轮询 scene 指标直到两个镜像都 {@code destroyed_idle}（上限 {@code --instance-wait-ms}）→ B 按号进第一个镜像被拒
 *       （同节点同步 3023，或 {0} 后 23 {3023}），没有 79。</li>
 *   <li><b>指标</b>：scene 的 created / destroyed_idle / accepted / bad_source 增长、active 不高于开始时；scene-manager 的 ok 增长。</li>
 *   <li><b>表外 M</b>：strict → 3005、没有 79、bad_mirror_config 增长；lenient → {0} + 79，再回主世界。</li>
 * </ol>
 * 组队跟随进镜像（§12.6 第 11 步，可选）不在本场景里。依据：dungeon-mirror-spec §5（客户端可见行为）、§6.7、§6.10、§6.12、§8.2、§12.6；
 * PARITY「副本与镜像场景」行。
 */
public final class MirrorScenario {

    private static final String REF = "dungeon-mirror-spec §12.6 mirror";
    private static final String REF_PLACE = "scene 契约 §1（首登落 World 第一行）；dungeon-mirror-spec §12.6 mirror";
    private static final String REF_ACCEPTED = "dungeon-mirror-spec §5.4（应答 {0} 先于 79）；scene 契约 §4.3";
    private static final String REF_INFO = "dungeon-mirror-spec §5.2（79 的 scene_info 两版逐字段一致）";
    private static final String REF_POSITION = "dungeon-mirror-spec §5.6（同图换入镜像 / 回频道保留坐标）";
    private static final String REF_ISOLATION = "dungeon-mirror-spec §0.5、§6.3（实例与主世界频道互不可见）";
    private static final String REF_QUERY = "dungeon-mirror-spec §5.2 末条（43 → 31 在实例里带实例字段）";
    private static final String REF_R1 = "dungeon-mirror-spec R1（选频道只认主世界频道，登录不落进镜像）";
    private static final String REF_SYNC = "dungeon-mirror-spec §5.4、Q7、Q11（镜像分支的同步拒绝一律 3005，源必须是主世界频道）";
    private static final String REF_JOIN = "dungeon-mirror-spec §6.12、§5.2（按号加入，creators 仍只有创建者）";
    private static final String REF_RECONNECT = "dungeon-mirror-spec Q9、§6.14（重连回原实例）";
    private static final String REF_LEAVE = "dungeon-mirror-spec R3（实例里只带地图必回主世界频道）、§5.6";
    private static final String REF_IN_FLIGHT = "dungeon-mirror-spec D4、§5.9（RESOLVING 期间 63 回 3014）";
    private static final String REF_RECLAIM = "dungeon-mirror-spec §5.7、§5.8、§6.10（空置超时 + 回收宽限后销毁，之后按号进入失败）";
    private static final String REF_VALIDATION = "dungeon-mirror-spec D7、Q1（mirror_config_id 先查 Mirror 表）";
    private static final String REF_METRICS = "dungeon-mirror-spec §8.2";

    static final int PARAM_ERROR = SceneErrorTip.scene_error.kEnterSceneParamError_VALUE;
    static final int CHANGING_SCENE = SceneErrorTip.scene_error.kEnterSceneChangingScene_VALUE;
    /** 表外号从这里起找（§12.6 第 9 步的 999）。 */
    static final int MISSING_MIRROR_START = 999;
    /** 本场景建的镜像数（第一步一个、连发一个）：指标增量的期望下限。 */
    static final int MIRRORS_CREATED = 2;

    private final SceneMoves moves;
    private final ConfigTables tables;
    private final String prefix;
    private final String runTag;
    private final int mirrorConfigId;
    private final boolean strictValidation;
    private final Duration instanceWait;
    private final String sceneMetricsUrl;
    private final String sceneManagerMetricsUrl;
    private final MetricsSource sceneMetrics;
    private final MetricsSource smMetrics;
    private final CheckReport report = new CheckReport();

    /** 开始时的 scene 指标（null = 抓不到或没有 5.3 指标，scene 侧指标不核对）。 */
    private String sceneBefore;
    /** 开始时的 scene-manager 指标（null = 抓不到）。 */
    private String smBefore;
    /** 镜像建在 {@code --scene-metrics-url} 的节点上（第一个镜像建好后按 created 的增量判定）。 */
    private boolean hosted;

    /**
     * @param mirrorConfigId         建镜像用的 Mirror 表 id
     * @param strictValidation       表外 mirror_config_id 期望 3005（true）还是 {@code {0}} + 79
     * @param instanceWait           等两个镜像都被空置回收的上限
     * @param sceneMetricsUrl        xm-scene 管理端口（抓 scene 侧实例指标）
     * @param sceneManagerMetricsUrl xm-scene-manager 管理端口（抓取号指标）
     */
    public MirrorScenario(PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, Path tableDir, String accountPrefix,
                          String runTag, int mirrorConfigId, boolean strictValidation, Duration instanceWait, String sceneMetricsUrl,
                          String sceneManagerMetricsUrl, Duration requestTimeout, Duration observeTimeout) {
        this.moves = new SceneMoves(flow, ids, registry, requestTimeout, observeTimeout);
        this.tables = ConfigTables.load(tableDir);
        this.prefix = accountPrefix;
        this.runTag = runTag;
        this.mirrorConfigId = mirrorConfigId;
        this.strictValidation = strictValidation;
        this.instanceWait = instanceWait;
        this.sceneMetricsUrl = sceneMetricsUrl;
        this.sceneManagerMetricsUrl = sceneManagerMetricsUrl;
        this.sceneMetrics = () -> SceneAdminClient.scrape(sceneMetricsUrl, requestTimeout);
        this.smMetrics = () -> SceneAdminClient.scrape(sceneManagerMetricsUrl, requestTimeout);
    }

    public static String accountName(String prefix, String runTag, String role) {
        return prefix + "mr" + runTag + "_" + role;
    }

    public String accountA() {
        return accountName(prefix, runTag, "a");
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            moves.closeAll();
        }
        return report;
    }

    private void runChecks() throws RobotException {
        int home = InstanceChecks.homeWorld(tables);
        if (!tables.mirror().contains(mirrorConfigId)) {
            report.note("Mirror 表里没有 id=" + mirrorConfigId + "（--mirror-config-id）：先查表的实现会对建镜像回 3005");
        }
        baseline();

        // 0. 落位
        ProbeBot a = moves.login(accountA(), "A");
        ProbeBot b = moves.login(accountName(prefix, runTag, "b"), "B");
        b = colocate(a, b, home);
        SceneInfoComp source = a.scene();
        boolean bSeesA = moves.awaitActor(b.connection(), b.since(), a.playerId(), moves.observeTimeout()).isPresent();
        if (!bSeesA) {
            report.note("落位后 B 没看见 A（视野外？），跳过「B 收到 A 的 51」");
        }

        // 1. 建镜像 + 隔离
        a = createMirror(a, b, bSeesA);
        SceneInfoComp mirror = a.scene();
        detectHost();
        a = walkUnseen(a, b);
        // 2. 43 → 31
        query(a, mirror);
        // R1：镜像存在时新登录不落进镜像
        loginDuringMirror(home, a, mirror);
        // 3. 镜像里再建镜像
        nested(a);
        // 4. B 按号加入
        ProbeBot[] joined = join(a, b, source, mirror);
        a = joined[0];
        b = joined[1];
        // 5. A 断线重连回镜像
        a = reconnect(a, b, source, mirror);
        // 6. 先后离开
        ProbeBot[] left = leaveBoth(a, b, source, mirror);
        a = left[0];
        b = left[1];
        // 7. 连发两条 63 镜像
        a = doubleCreate(a, mirror);
        // 8. 空置回收后按号进入被拒
        String sceneAfter = reclaim(b, mirror);
        // 10. 指标
        checkMetrics(sceneAfter);
        // 9. 表外 mirror_config_id
        validation(a);
        report.note("组队跟随进镜像（§12.6 第 11 步，可选）不在本场景里");
    }

    // ------------------------------------------------------------------ 0. 落位

    private ProbeBot colocate(ProbeBot a, ProbeBot b, int home) throws RobotException {
        List<String> problems = InstanceChecks.worldInfoProblems(a.scene(), home, 0);
        report.check(problems.isEmpty(), "落位：A 首登落在默认主世界的频道上（镜像源必须是主世界频道）",
                InstanceChecks.describe(a.scene()) + problemText(problems), REF_PLACE);
        if (CrossNodeScenario.sameScene(b.scene(), a.scene())) {
            report.pass("落位：A、B 同在默认主世界的同一频道", CrossNodeScenario.describe(a.scene()), REF);
            return b;
        }
        SceneMoves.Sent sent = moves.send(b, CrossNodeScenario.switchRequest(a.scene().getSceneConfigId(), a.scene().getSceneId()),
                "落位");
        if (sent.tip() != 0) {
            throw new RobotException("落位：B（" + CrossNodeScenario.describe(b.scene()) + "）换到 A 的频道 "
                    + CrossNodeScenario.describe(a.scene()) + " 被拒 " + sent.tip());
        }
        SceneMoves.Arrival arrival = moves.arrive(b, sent.mark(), "落位");
        boolean same = arrival.info().getSceneId() == a.scene().getSceneId();
        report.check(same, "落位：登录时不在同一频道，B 按号换到 A 的频道",
                "79 " + CrossNodeScenario.describe(arrival.info()) + "，期望 " + CrossNodeScenario.describe(a.scene()), REF);
        if (!same) {
            throw new RobotException("落位：B 没换到 A 的频道，后续步骤无从验证");
        }
        return b.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark());
    }

    // ------------------------------------------------------------------ 1. 建镜像

    private ProbeBot createMirror(ProbeBot a, ProbeBot b, boolean bSeesA) throws RobotException {
        SceneInfoComp source = a.scene();
        int markB = b.connection().inbox().size();
        SceneMoves.Sent sent = moves.send(a, InstanceChecks.mirrorRequest(mirrorConfigId), "建镜像");
        report.check(sent.tip() == 0, "建镜像：A 发 63 {mirror_config_id = " + mirrorConfigId + ", scene_id = 0} 应答 {0}",
                "error_message.id=" + sent.tip(), REF_ACCEPTED);
        if (sent.tip() != 0) {
            throw new RobotException("建镜像被拒 " + sent.tip() + "（xm-scene 还没有 5.3 镜像分支？Mirror 表没有 " + mirrorConfigId
                    + "？），后续步骤无从验证");
        }
        SceneMoves.Arrival arrival = moves.arrive(a, sent.mark(), "建镜像");
        report.check(sent.reply().index() < arrival.enterNotify().index(), "建镜像：63 应答先于 79",
                "应答是本连接第 " + sent.reply().index() + " 条下行，79 是第 " + arrival.enterNotify().index() + " 条",
                REF_ACCEPTED);
        List<String> problems = InstanceChecks.mirrorInfoProblems(arrival.info(), source.getSceneConfigId(), source.getSceneId(),
                mirrorConfigId, a.playerId());
        report.check(problems.isEmpty(), "建镜像：79 的 scene_info 逐字段为 {源地图, 新号, M, dungeon 0, creators {A: true}}",
                InstanceChecks.describe(arrival.info()) + problemText(problems), REF_INFO);
        checkKeptPosition("建镜像", arrival.at(), a.at());
        if (bSeesA) {
            Optional<Received> gone = moves.awaitDestroy(b.connection(), markB, a.entity(), moves.observeTimeout());
            report.check(gone.isPresent(), "建镜像：源频道的 B 收到 A 的 51",
                    gone.isPresent() ? "entity=" + a.entity()
                            : moves.observeTimeout().toMillis() + " ms 内没收到" + b.connection().describeSince(markB), REF_ISOLATION);
        }
        SceneMoves.sleep(SceneMoves.QUIET_WINDOW);
        boolean aSeesB = moves.sawActor(a.connection(), sent.mark(), b.playerId());
        boolean bSeesNewA = moves.sawActor(b.connection(), markB, a.playerId());
        report.check(!aSeesB && !bSeesNewA, "镜像隔离：A 进镜像后看不见源频道的 B，B 也看不见镜像里的 A",
                "A 收到含 B 的 21 / 47：" + aSeesB + "；B 收到含 A 的 21 / 47：" + bSeesNewA, REF_ISOLATION);
        List<Integer> tips = moves.tipsSince(a.connection(), sent.mark());
        report.check(tips.isEmpty(), "建镜像：A 全程没收到 23", tips.isEmpty() ? "" : "收到 23 " + tips, REF_ACCEPTED);
        return a.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark());
    }

    /** A 在镜像里走两步：源频道的 B 收不到 A 的 66。 */
    private ProbeBot walkUnseen(ProbeBot a, ProbeBot b) throws RobotException {
        int markB = b.connection().inbox().size();
        Vec3 stopped = moves.walk(a);
        Optional<ActorBaseAttributesS2C> leaked = moves.awaitSync(b.connection(), markB, a.playerId(), moves.observeTimeout());
        report.check(leaked.isEmpty(), "镜像隔离：A 在镜像里走动，源频道的 B 收不到 A 的 66",
                leaked.isEmpty() ? moves.observeTimeout().toMillis() + " ms 内没有" : "收到 entity_id=" + leaked.get().getEntityId(),
                REF_ISOLATION);
        return a.at(stopped);
    }

    // ------------------------------------------------------------------ 2. 43 → 31

    private void query(ProbeBot a, SceneInfoComp mirror) throws RobotException {
        SceneInfoS2C pushed = moves.querySceneInfo(a, "镜像里 43");
        boolean ok = pushed.getSceneInfoCount() == 1 && pushed.getSceneInfo(0).equals(mirror);
        report.check(ok, "镜像里发 43：31 恰好一条、等于建镜像时 79 的 scene_info",
                pushed.getSceneInfoList().stream().map(InstanceChecks::describe).toList() + "，期望 [" + InstanceChecks.describe(mirror)
                        + "]", REF_QUERY);
    }

    // ------------------------------------------------------------------ R1

    private void loginDuringMirror(int home, ProbeBot a, SceneInfoComp mirror) throws RobotException {
        int markA = a.connection().inbox().size();
        ProbeBot c = moves.login(accountName(prefix, runTag, "c"), "C");
        List<String> problems = InstanceChecks.worldInfoProblems(c.scene(), home, mirror.getSceneId());
        report.check(problems.isEmpty(), "镜像存在时新登录的 C 落在默认主世界频道、不落进镜像",
                InstanceChecks.describe(c.scene()) + problemText(problems), REF_R1);
        SceneMoves.sleep(SceneMoves.QUIET_WINDOW);
        boolean cSeesA = moves.sawActor(c.connection(), 0, a.playerId());
        boolean aSeesC = moves.sawActor(a.connection(), markA, c.playerId());
        report.check(!cSeesA && !aSeesC, "镜像隔离：新登录的 C 看不见镜像里的 A，A 也看不见 C",
                "C 收到含 A 的 21 / 47：" + cSeesA + "；A 收到含 C 的 21 / 47：" + aSeesC, REF_ISOLATION);
        moves.leave(c);
    }

    // ------------------------------------------------------------------ 3. 镜像里再建镜像

    private void nested(ProbeBot a) throws RobotException {
        SceneMoves.Sent sent = moves.send(a, InstanceChecks.mirrorRequest(mirrorConfigId), "镜像里再建镜像");
        long enters = moves.quietEnters(a.connection(), sent.mark());
        report.check(sent.tip() == PARAM_ERROR && enters == 0,
                "镜像里再发 63 {mirror_config_id = " + mirrorConfigId + ", scene_id = 0}：应答 {" + PARAM_ERROR + "}、没有 79",
                "error_message.id=" + sent.tip() + "，79 " + enters + " 条", REF_SYNC);
    }

    // ------------------------------------------------------------------ 4. 按号加入

    private ProbeBot[] join(ProbeBot a, ProbeBot b, SceneInfoComp source, SceneInfoComp mirror) throws RobotException {
        int markA = a.connection().inbox().size();
        SceneMoves.Sent sent = moves.send(b, InstanceChecks.joinRequest(mirror.getSceneId(), mirrorConfigId), "B 按号加入镜像");
        report.check(sent.tip() == 0, "B 按号加入：63 {scene_id = 镜像, mirror_config_id = " + mirrorConfigId + "} 应答 {0}",
                "error_message.id=" + sent.tip(), REF_JOIN);
        if (sent.tip() != 0) {
            throw new RobotException("B 按号加入镜像被拒 " + sent.tip() + "，后续步骤无从验证");
        }
        SceneMoves.Arrival arrival = moves.arrive(b, sent.mark(), "B 按号加入镜像");
        List<String> problems = InstanceChecks.mirrorInfoProblems(arrival.info(), source.getSceneConfigId(), source.getSceneId(),
                mirrorConfigId, a.playerId());
        boolean same = arrival.info().getSceneId() == mirror.getSceneId();
        report.check(same && problems.isEmpty(), "B 按号加入：79 是这个镜像、creators 仍只有 {A: true}",
                InstanceChecks.describe(arrival.info()) + (same ? "" : "；不是镜像 " + Long.toUnsignedString(mirror.getSceneId()))
                        + problemText(problems), REF_JOIN);
        checkKeptPosition("B 按号加入", arrival.at(), b.at());
        Optional<ActorCreateS2C> bSeesA = moves.awaitActor(b.connection(), sent.mark(), a.playerId(), moves.observeTimeout());
        report.check(bSeesA.isPresent() && bSeesA.get().getEntity() == a.entity(), "B 按号加入：B 收到含 A 的 47",
                bSeesA.map(x -> "entity=" + x.getEntity() + "（A 为 " + a.entity() + "）")
                        .orElse(moves.observeTimeout().toMillis() + " ms 内没收到" + b.connection().describeSince(sent.mark())), REF_JOIN);
        Optional<ActorCreateS2C> aSeesB = moves.awaitActor(a.connection(), markA, b.playerId(), moves.observeTimeout());
        report.check(aSeesB.isPresent() && aSeesB.get().getEntity() == arrival.entity(), "B 按号加入：镜像里的 A 收到 B 的 21",
                aSeesB.map(x -> "entity=" + x.getEntity() + "（B 自己的 21 为 " + arrival.entity() + "）")
                        .orElse(moves.observeTimeout().toMillis() + " ms 内没收到" + a.connection().describeSince(markA)), REF_JOIN);
        ProbeBot inMirror = b.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark());

        int markB = inMirror.connection().inbox().size();
        Vec3 stopped = moves.walk(a);
        Optional<ActorBaseAttributesS2C> sync = moves.awaitSync(inMirror.connection(), markB, a.playerId(), moves.observeTimeout());
        report.check(sync.isPresent(), "镜像里：A 走动，B 收到 A 的 66",
                sync.isPresent() ? "entity_id=" + sync.get().getEntityId()
                        : moves.observeTimeout().toMillis() + " ms 内没收到" + inMirror.connection().describeSince(markB), REF_JOIN);
        return new ProbeBot[] {a.at(stopped), inMirror};
    }

    // ------------------------------------------------------------------ 5. 断线重连

    private ProbeBot reconnect(ProbeBot a, ProbeBot b, SceneInfoComp source, SceneInfoComp mirror) throws RobotException {
        int markB = b.connection().inbox().size();
        long oldEntity = a.entity();
        a.connection().close();
        Optional<Received> gone = moves.awaitDestroy(b.connection(), markB, oldEntity, moves.requestTimeout());
        report.check(gone.isPresent(), "断线重连：A 断开后镜像里的 B 收到 A 的 51",
                gone.isPresent() ? "entity=" + oldEntity : moves.requestTimeout().toMillis() + " ms 内没收到 entity=" + oldEntity + " 的 51",
                REF_RECONNECT);
        int markB2 = b.connection().inbox().size();
        ProbeBot back = moves.login(a.account(), "A");
        boolean same = back.scene().getSceneId() == mirror.getSceneId();
        List<String> problems = InstanceChecks.mirrorInfoProblems(back.scene(), source.getSceneConfigId(), source.getSceneId(),
                mirrorConfigId, a.playerId());
        report.check(same && problems.isEmpty(), "断线重连：立即重连回到原镜像（79 的 scene_id 是镜像号、info 逐字段不变）",
                InstanceChecks.describe(back.scene()) + "，镜像 " + Long.toUnsignedString(mirror.getSceneId()) + problemText(problems),
                REF_RECONNECT);
        double off = back.at().horizontalDistance(a.at());
        report.check(off <= SceneMoves.POSITION_TOLERANCE, "断线重连：回到断开前的位置",
                "自己的 21 位置 " + back.at() + "，断开前 " + a.at() + meters(off), REF_RECONNECT);
        Optional<ActorCreateS2C> seen = moves.awaitActor(b.connection(), markB2, a.playerId(), moves.observeTimeout());
        report.check(seen.isPresent(), "断线重连：镜像里的 B 收到 A 的 21",
                seen.map(x -> "entity=" + x.getEntity()).orElse(moves.observeTimeout().toMillis() + " ms 内没收到"), REF_RECONNECT);
        if (!same) {
            throw new RobotException("A 重连没回到镜像（" + CrossNodeScenario.describe(back.scene()) + "），后续步骤无从验证");
        }
        return back;
    }

    // ------------------------------------------------------------------ 6. 离开

    private ProbeBot[] leaveBoth(ProbeBot a, ProbeBot b, SceneInfoComp source, SceneInfoComp mirror) throws RobotException {
        int markB = b.connection().inbox().size();
        ProbeBot outA = leaveTo(a, source, mirror, "A 离开镜像");
        Optional<Received> gone = moves.awaitDestroy(b.connection(), markB, a.entity(), moves.observeTimeout());
        report.check(gone.isPresent(), "离开：还在镜像里的 B 收到先走的 A 的 51",
                gone.isPresent() ? "entity=" + a.entity()
                        : moves.observeTimeout().toMillis() + " ms 内没收到" + b.connection().describeSince(markB), REF_LEAVE);
        ProbeBot outB = leaveTo(b, source, mirror, "B 离开镜像");
        return new ProbeBot[] {outA, outB};
    }

    /** 在实例里发 63 {scene_config_id = 源地图}：应答 {0} → 源地图的主世界频道、同图保留坐标。 */
    private ProbeBot leaveTo(ProbeBot bot, SceneInfoComp source, SceneInfoComp instance, String label) throws RobotException {
        SceneMoves.Sent sent = moves.send(bot, InstanceChecks.mapRequest(source.getSceneConfigId()), label);
        report.check(sent.tip() == 0, label + "：63 {scene_config_id = " + source.getSceneConfigId() + "} 应答 {0}",
                "error_message.id=" + sent.tip(), REF_LEAVE);
        if (sent.tip() != 0) {
            throw new RobotException(label + "：63 被拒 " + sent.tip() + "，后续步骤无从验证");
        }
        SceneMoves.Arrival arrival = moves.arrive(bot, sent.mark(), label);
        List<String> problems = InstanceChecks.worldInfoProblems(arrival.info(), source.getSceneConfigId(), instance.getSceneId());
        report.check(problems.isEmpty(), label + "：回到源地图的主世界频道（mirror 0、号不是镜像）",
                InstanceChecks.describe(arrival.info()) + problemText(problems), REF_LEAVE);
        checkKeptPosition(label, arrival.at(), bot.at());
        return bot.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark());
    }

    // ------------------------------------------------------------------ 7. 连发

    private ProbeBot doubleCreate(ProbeBot a, SceneInfoComp firstMirror) throws RobotException {
        SceneInfoComp source = a.scene();
        SceneMoves.Burst burst = moves.sendBurst(a, InstanceChecks.mirrorRequest(mirrorConfigId), 2);
        Optional<Received> first = moves.awaitReply(a.connection(), burst.mark(), burst.requestIds().get(0));
        Optional<Received> second = moves.awaitReply(a.connection(), burst.mark(), burst.requestIds().get(1));
        int firstTip = first.map(CrossNodeScenario::replyTip).orElse(-2);
        int secondTip = second.map(CrossNodeScenario::replyTip).orElse(-2);
        report.check(firstTip == 0, "连发 63 镜像：第一条应答 {0}",
                first.isPresent() ? "error_message.id=" + firstTip : "没有应答", REF_IN_FLIGHT);
        report.check(secondTip == CHANGING_SCENE, "连发 63 镜像：第二条应答 " + CHANGING_SCENE + "（建镜像在途）",
                second.isPresent() ? "error_message.id=" + secondTip : "没有应答", REF_IN_FLIGHT);
        if (firstTip != 0) {
            throw new RobotException("连发 63 镜像：第一条被拒 " + firstTip + "，后续步骤无从验证");
        }
        SceneMoves.Arrival arrival = moves.arrive(a, burst.mark(), "连发 63 镜像");
        long enters = moves.quietEnters(a.connection(), burst.mark());
        List<String> problems = InstanceChecks.mirrorInfoProblems(arrival.info(), source.getSceneConfigId(), source.getSceneId(),
                mirrorConfigId, a.playerId());
        boolean fresh = arrival.info().getSceneId() != firstMirror.getSceneId();
        report.check(enters == 1 && problems.isEmpty() && fresh, "连发 63 镜像：只建了一个新镜像（恰好一条 79、逐字段符合、不是第一个镜像）",
                "79 " + enters + " 条，" + InstanceChecks.describe(arrival.info()) + problemText(problems), REF_IN_FLIGHT);
        ProbeBot inMirror = a.movedTo(arrival.info(), arrival.entity(), arrival.at(), burst.mark());
        return leaveTo(inMirror, source, arrival.info(), "A 离开第二个镜像");
    }

    // ------------------------------------------------------------------ 8. 空置回收

    /** 等两个镜像都被空置回收，再让 B 按号进第一个镜像；返回等到时的 scene 指标文本（没核对指标为 null）。 */
    private String reclaim(ProbeBot b, SceneInfoComp mirror) throws RobotException {
        String sceneAfter = null;
        if (hosted) {
            InstanceMetrics.Polled polled = InstanceMetrics.awaitDelta(sceneMetrics, sceneBefore, MIRRORS_CREATED, instanceWait,
                    InstanceMetrics.POLL_INTERVAL, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR,
                    InstanceMetrics.event("destroyed_idle"));
            report.check(polled.reached(), "空置回收：两个镜像在 " + instanceWait.toMillis() + " ms 内都被回收（destroyed_idle +"
                            + MIRRORS_CREATED + "）",
                    "destroyed_idle 增量 " + (long) polled.delta() + (polled.reached() ? ""
                            : "（xm.scene.instance.mirror-idle-timeout + reclaim-grace 比 --instance-wait-ms 长？）"), REF_RECLAIM);
            sceneAfter = polled.text();
        } else {
            report.note("没在 --scene-metrics-url 的节点上看到镜像，回收改为固定等待 " + instanceWait.toMillis() + " ms 后再探");
            SceneMoves.sleep(instanceWait);
        }
        SceneMoves.Sent sent = moves.send(b, InstanceChecks.joinRequest(mirror.getSceneId(), mirrorConfigId), "回收后按号进入");
        SceneMoves.Refusal refusal = moves.awaitRefusal(b, sent);
        report.check(refusal.ok(), "空置回收后：B 按号进第一个镜像被拒（同步 3023，或 {0} 后 23 {3023}），没有 79",
                refusal.describe(), REF_RECLAIM);
        return sceneAfter;
    }

    // ------------------------------------------------------------------ 9. 表外 mirror_config_id

    private void validation(ProbeBot a) throws RobotException {
        Set<Integer> present = new HashSet<>();
        for (MirrorTable row : tables.mirror().all()) {
            present.add(row.getId());
        }
        int missing = InstanceChecks.firstMissing(present, MISSING_MIRROR_START);
        String before = hosted ? scrapeScene() : null;
        SceneMoves.Sent sent = moves.send(a, InstanceChecks.mirrorRequest(missing), "表外 mirror_config_id");
        if (strictValidation) {
            long enters = moves.quietEnters(a.connection(), sent.mark());
            report.check(sent.tip() == PARAM_ERROR && enters == 0,
                    "表外 mirror_config_id=" + missing + "（strict）：应答 {" + PARAM_ERROR + "}、没有 79",
                    "error_message.id=" + sent.tip() + "，79 " + enters + " 条", REF_VALIDATION);
            if (before != null) {
                String after = scrapeScene();
                if (after != null) {
                    double grown = InstanceMetrics.delta(before, after, InstanceMetrics.MIRROR_REQUESTS,
                            InstanceMetrics.result("bad_mirror_config"));
                    report.check(grown >= 1, "指标：scene mirror_requests{result=\"bad_mirror_config\"} 增长",
                            "增量 " + (long) grown, REF_METRICS);
                }
            }
            return;
        }
        report.check(sent.tip() == 0, "表外 mirror_config_id=" + missing + "（lenient）：应答 {0}", "error_message.id=" + sent.tip(),
                REF_VALIDATION);
        if (sent.tip() != 0) {
            return;
        }
        SceneInfoComp source = a.scene();
        SceneMoves.Arrival arrival = moves.arrive(a, sent.mark(), "表外 mirror_config_id");
        report.check(arrival.info().getMirrorConfigId() == missing, "表外 mirror_config_id（lenient）：79 进了镜像、带回请求的号",
                InstanceChecks.describe(arrival.info()), REF_VALIDATION);
        leaveTo(a.movedTo(arrival.info(), arrival.entity(), arrival.at(), sent.mark()), source, arrival.info(), "A 离开表外号的镜像");
    }

    // ------------------------------------------------------------------ 10. 指标

    private void baseline() {
        try {
            String text = sceneMetrics.scrape();
            if (InstanceMetrics.present(text, InstanceMetrics.LIFECYCLE)) {
                sceneBefore = text;
            } else {
                report.fail("指标：scene 管理端口有批次 5.3 的实例指标", sceneMetricsUrl + " 上没有 " + InstanceMetrics.LIFECYCLE
                        + "（xm-scene 早于批次 5.3，或 --scene-metrics-url 指错了端口）", REF_METRICS);
            }
        } catch (RobotException e) {
            report.fail("指标：抓 scene 管理端口 " + sceneMetricsUrl, e.getMessage(), REF_METRICS);
        }
        try {
            smBefore = smMetrics.scrape();
        } catch (RobotException e) {
            report.fail("指标：抓 scene-manager 管理端口 " + sceneManagerMetricsUrl, e.getMessage(), REF_METRICS);
        }
    }

    /** 第一个镜像建好后：created 在 {@code --scene-metrics-url} 的节点上增长了，镜像就在这个节点上。 */
    private void detectHost() {
        if (sceneBefore == null) {
            return;
        }
        String now = scrapeScene();
        hosted = now != null && InstanceMetrics.delta(sceneBefore, now, InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR,
                InstanceMetrics.event("created")) >= 1;
        if (!hosted) {
            report.note(sceneMetricsUrl + " 的节点上镜像 created 没增长：镜像建在别的 scene 节点上（双节点切片？），"
                    + "跳过 scene 侧指标核对，回收改为固定等待");
        }
    }

    private void checkMetrics(String sceneAfter) {
        if (hosted && sceneAfter != null) {
            checkGrown(sceneAfter, "scene instance_lifecycle{kind=\"mirror\",event=\"created\"}", MIRRORS_CREATED,
                    InstanceMetrics.LIFECYCLE, InstanceMetrics.KIND_MIRROR, InstanceMetrics.event("created"));
            checkGrown(sceneAfter, "scene mirror_resolves{result=\"created\"}", MIRRORS_CREATED,
                    InstanceMetrics.MIRROR_RESOLVES, InstanceMetrics.result("created"));
            checkGrown(sceneAfter, "scene mirror_requests{result=\"accepted\"}", MIRRORS_CREATED,
                    InstanceMetrics.MIRROR_REQUESTS, InstanceMetrics.result("accepted"));
            checkGrown(sceneAfter, "scene mirror_requests{result=\"bad_source\"}（镜像里再建镜像）", 1,
                    InstanceMetrics.MIRROR_REQUESTS, InstanceMetrics.result("bad_source"));
            double activeBefore = AdminClient.sum(sceneBefore, InstanceMetrics.INSTANCES, InstanceMetrics.KIND_MIRROR,
                    InstanceMetrics.ACTIVE);
            double activeAfter = AdminClient.sum(sceneAfter, InstanceMetrics.INSTANCES, InstanceMetrics.KIND_MIRROR,
                    InstanceMetrics.ACTIVE);
            report.check(activeAfter <= activeBefore, "指标：scene instances{kind=\"mirror\",state=\"active\"} 回到不高于开始时",
                    "开始 " + (long) activeBefore + "，回收后 " + (long) activeAfter, REF_METRICS);
        }
        if (smBefore != null) {
            try {
                String smAfter = smMetrics.scrape();
                double grown = InstanceMetrics.delta(smBefore, smAfter, InstanceMetrics.SM_INSTANCE, InstanceMetrics.KIND_MIRROR,
                        InstanceMetrics.result("ok"));
                report.check(grown >= MIRRORS_CREATED, "指标：scene-manager instance_seconds_count{kind=\"mirror\",result=\"ok\"} 增长 ≥ "
                        + MIRRORS_CREATED, "增量 " + (long) grown, REF_METRICS);
            } catch (RobotException e) {
                report.fail("指标：抓 scene-manager 管理端口 " + sceneManagerMetricsUrl, e.getMessage(), REF_METRICS);
            }
        }
    }

    private void checkGrown(String after, String name, int want, String metric, String... labels) {
        double grown = InstanceMetrics.delta(sceneBefore, after, metric, labels);
        report.check(grown >= want, "指标：" + name + " 增长 ≥ " + want, "增量 " + (long) grown, REF_METRICS);
    }

    /** 抓 scene 指标；失败记一条失败并返回 null。 */
    private String scrapeScene() {
        try {
            return sceneMetrics.scrape();
        } catch (RobotException e) {
            report.fail("指标：抓 scene 管理端口 " + sceneMetricsUrl, e.getMessage(), REF_METRICS);
            return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private void checkKeptPosition(String label, Vec3 at, Vec3 before) {
        double off = at.horizontalDistance(before);
        report.check(off <= SceneMoves.POSITION_TOLERANCE, label + "：同图保留坐标（水平偏差 ≤ " + SceneMoves.POSITION_TOLERANCE + " m）",
                "自己的 21 位置 " + at + "，之前 " + before + meters(off), REF_POSITION);
    }

    static String problemText(List<String> problems) {
        return problems.isEmpty() ? "" : "；不符：" + String.join("、", problems);
    }

    private static String meters(double off) {
        return String.format(Locale.ROOT, "，偏差 %.3f m", off);
    }
}
