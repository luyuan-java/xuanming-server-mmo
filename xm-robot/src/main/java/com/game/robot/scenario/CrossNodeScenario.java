package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.ListSkillsResponse;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LeaveGameRequest;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.LoginErrorTip;
import com.game.table.SceneErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 跨节点换场景与归属交接（批次 5.2，scene-handoff-spec §10.8）。
 *
 * <p><b>前提</b>：本机切片起两个 scene 节点（{@code XM_SCENE_NODES=2 tools/local/start-slice.sh}），scene-manager 保持 per-node 覆盖、
 * 每张世界图每个节点一个频道（切片缺省）。这样「同一张地图、不同 scene_id」的两个场景必在不同节点上——探针看不到节点号，靠这条推断。
 *
 * <p>三个新账号 {@code 前缀 + xn + 标签 + _1 / _2 / _3}，落位后分工为 A（换图的人）、B（留在 S_B）、C（留在 S_A 的观察者）：
 * <ol>
 *   <li><b>落位</b>：账号 1、2 登录，用 79 的 scene_id 判断是否同场景；同场景就让账号 2 发 LeaveGame 断开、等 6 s（节点目录 5 s 刷新）后重登，
 *       至多 {@value #MAX_PLACEMENT_ATTEMPTS} 次，直到两人在同图的两个频道上。再登账号 3：它与谁同场景谁当 A、它当 C，另一个当 B。</li>
 *   <li><b>跨节点换图</b>：A 发 63 {scene_id = S_B}：应答 {0}（先于 79）→ 79（S_B）、自己的 21（新实体号、同图保留坐标）、含 B 的 47；
 *       B 收到 A 的 21；C 收到 A 旧实体的 51；A 全程没收到 23。</li>
 *   <li><b>在目标节点上玩</b>：A 走两步，B 收到 A 的 66；A 发 77 有应答（源节点已移除实例，能应答的只有目标节点）。</li>
 *   <li><b>断开重连</b>：A 断开（B 收到 A 的 51）后立即重连，回到 S_B 原实例、断开前走到的点。</li>
 *   <li><b>换回</b>：A 发 63 回 S_A，同第 2 步（C 收到 A 的 21、B 收到 A 的 51）。</li>
 *   <li><b>目标不存在</b>：A 发 63 {scene_id = 不存在}：应答 {0} 后收到 23 {3023}；A 留在原地，没有 79，77 照常应答。</li>
 *   <li><b>连发</b>：先空出 gate 对 63 的限频窗口（缺省每秒 3 条），A 连发两条 63 {S_B}：第一条 {0}、第二条 3014（换场景在途），
 *       最终只换一次（恰好一条 79）。</li>
 *   <li><b>顶号</b>：A 在 S_B 上时从另一条连接进同一角色：旧连接收到 23 {2017}，新连接回到 S_B 原实例。</li>
 * </ol>
 * 依据：scene-handoff-spec §3.2（各结局的客户端所见）、§5.5（63 的 Remote 分支）、§5.9（在途 3014）、§5.10（失败一览与断开后重连）、
 * §5.11（交接中顶号）、D10（同图保留坐标）；scene 契约 §4.3（63 无错 = 已受理，不代表已到达）；PARITY「跨节点换图与归属交接」行。
 */
public final class CrossNodeScenario {

    private static final String REF = "scene-handoff-spec §10.8";
    private static final String REF_ACCEPTED = "scene 契约 §4.3（无错 = 已受理，不代表已到达）；scene-handoff-spec §0.6";
    private static final String REF_SUCCESS = "scene-handoff-spec §3.2 成功行、§5.6；PARITY「跨节点换图与归属交接」行";
    private static final String REF_POSITION = "scene-handoff-spec §3.4、D10（交出进场同图保留坐标）";
    private static final String REF_TARGET = "scene-handoff-spec §5.7（改绑后上行转发到目标节点）、§5.8";
    private static final String REF_RECONNECT = "scene-handoff-spec §10.7 M3、§5.10；PARITY「短线重连与落点」行";
    private static final String REF_MISSING = "scene-handoff-spec §5.5（选目标拒绝推 23 {3023}）、§0.6";
    private static final String REF_IN_FLIGHT = "scene-handoff-spec §5.9、§5.11（两次 63 连发）；scene 契约 §4.3";
    private static final String REF_TAKEOVER = "scene-handoff-spec §10.8 第 8 步、§5.11；PARITY「短线重连与落点」行";

    static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    static final int CHANGING_SCENE = SceneErrorTip.scene_error.kEnterSceneChangingScene_VALUE;
    static final int KICKED_BY_ANOTHER = LoginErrorTip.login_error.kLoginBeKickByAnOtherAccount_VALUE;

    /** 落位：账号 2 至多登录这么多次（含第一次），仍和账号 1 同场景就判前提不成立。 */
    static final int MAX_PLACEMENT_ATTEMPTS = 4;
    /** 账号 2 登出后等多久再登：节点目录每 5 s 刷新一次（{@code SceneDirectoryPublisher.PERIOD}），多给 1 s。 */
    static final Duration DIRECTORY_REFRESH_WAIT = Duration.ofSeconds(6);
    /** 连发 63 之后确认「只换了一次」的静默窗口。 */
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(1);
    /**
     * gate 按会话、按消息号限频：MessageLimiter 表里没有 63，取缺省每秒 3 条（{@code MessageLimit.DEFAULT}），超了回信封 1008、不转发。
     * 连发两条 63 之前先空出一个窗口（再多 200 ms 余量），免得与前两步的 63 凑满 4 条、第二条被 gate 挡下而到不了 scene。
     */
    static final Duration GATE_RATE_WINDOW = Duration.ofMillis(1200);
    private static final double LOCATION_EPS = 1e-3;
    /** 在目标节点上走的位移与用时（2 m / 0.5 s = 4 m/s，远低于位移校验的 12 m/s），同 reconnect 场景。 */
    private static final Vec3 WALK = new Vec3(2, 0, 0);
    private static final Duration WALK_TIME = Duration.ofMillis(500);
    private static final Vec3 FACING = new Vec3(0, 0, 90);

    private final PlayerFlow flow;
    private final MessageIds ids;
    private final String prefix;
    private final String runTag;
    private final Duration requestTimeout;
    private final Duration observeTimeout;
    /** 跨节点换图从 63 到 79 的上限：选目标（≤ 4 s）+ 交出与探测（≤ 约 15 s）+ 目标加载，取请求超时（缺省 15 s）。 */
    private final Duration transferTimeout;
    private final int enterScene;
    private final int leaveGame;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();

    /** 一个已进场的账号：分工（A / B / C）、所在场景、自己当前的实体号与位置。不可变，换图 / 重登后换一个新的。 */
    private record Bot(String role, EnteredPlayer player, SceneInfoComp scene, long entity, Vec3 at) {

        GameConnection connection() {
            return player.connection();
        }

        long playerId() {
            return player.playerId();
        }

        String account() {
            return player.account();
        }

        Bot as(String newRole) {
            return new Bot(newRole, player, scene, entity, at);
        }

        Bot movedTo(SceneInfoComp newScene, long newEntity, Vec3 newAt) {
            return new Bot(role, player, newScene, newEntity, newAt);
        }
    }

    /**
     * 落位后的分工：下标 1–3 对应账号 1–3；{@code c == 0} 表示没有留在 S_A 的观察者。
     */
    record Roles(int a, int b, int c) {
    }

    public CrossNodeScenario(PlayerFlow flow, MessageIds ids, MessageIdRegistry registry, String accountPrefix,
                             String runTag, Duration requestTimeout, Duration observeTimeout) {
        this.flow = flow;
        this.ids = ids;
        this.prefix = accountPrefix;
        this.runTag = runTag;
        this.requestTimeout = requestTimeout;
        this.observeTimeout = observeTimeout;
        this.transferTimeout = requestTimeout;
        this.enterScene = registry.requireId("SceneSceneClientPlayer", "EnterScene");
        this.leaveGame = registry.requireId("ClientPlayerLogin", "LeaveGame");
    }

    public static String accountName(String prefix, String runTag, String role) {
        return prefix + "xn" + runTag + "_" + role;
    }

    public String firstAccount() {
        return accountName(prefix, runTag, "1");
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        Bot[] placed = place();
        Bot a = placed[0];
        Bot b = placed[1];
        Bot c = placed[2];
        SceneInfoComp sceneA = a.scene();
        SceneInfoComp sceneB = b.scene();

        // 2. 跨节点换图：A → S_B
        a = crossTo("跨节点换图 S_A → S_B", a, sceneB, b, c);
        // 3. 在目标节点上玩
        a = playOnTarget(a, b);
        // 4. 断开后立即重连，回到 S_B 原位
        a = reconnect(a, b);
        // 5. 换回 S_A
        a = crossTo("换回 S_B → S_A", a, sceneA, c, b);
        // 6. 目标不存在
        targetMissing(a, List.of(sceneA.getSceneId(), sceneB.getSceneId()));
        // 7. 连发两条 63
        a = doubleSwitch(a, sceneB);
        // 8. 顶号
        takeover(a);
    }

    // ------------------------------------------------------------------ 1. 落位

    /** 返回 {A, B, C}；C 可能为 null（账号 3 没落在 S_A）。 */
    private Bot[] place() throws RobotException {
        Bot first = login(accountName(prefix, runTag, "1"), "账号1");
        Bot second = login(accountName(prefix, runTag, "2"), "账号2");
        for (int attempt = 1; !onDifferentChannels(first.scene(), second.scene()); attempt++) {
            if (attempt >= MAX_PLACEMENT_ATTEMPTS) {
                throw new RobotException("账号 2 登录 " + attempt + " 次都没落到账号 1 所在地图的另一个频道（账号 1 "
                        + describe(first.scene()) + "，账号 2 " + describe(second.scene()) + "）。本场景需要两个 scene 节点"
                        + "（XM_SCENE_NODES=2 tools/local/start-slice.sh）、per-node 覆盖、每图每节点一个频道");
            }
            report.note("第 " + attempt + " 次落位：账号 1 " + describe(first.scene()) + "，账号 2 " + describe(second.scene())
                    + "，不是同图的两个频道；账号 2 发 LeaveGame 断开，等 " + DIRECTORY_REFRESH_WAIT.toSeconds() + " s 后重登");
            leave(second);
            sleep(DIRECTORY_REFRESH_WAIT);
            second = login(accountName(prefix, runTag, "2"), "账号2");
        }
        Bot third = login(accountName(prefix, runTag, "3"), "账号3");
        Roles roles = assignRoles(first.scene().getSceneId(), second.scene().getSceneId(), third.scene().getSceneId());
        Bot[] byIndex = {null, first, second, third};
        Bot a = byIndex[roles.a()].as("A");
        Bot b = byIndex[roles.b()].as("B");
        Bot c = roles.c() == 0 ? null : byIndex[roles.c()].as("C");
        report.pass("落位：A、B 在同一张地图的两个频道上（per-node 覆盖下即两个节点）",
                "A=" + a.account() + " S_A " + describe(a.scene()) + "；B=" + b.account() + " S_B " + describe(b.scene()), REF);
        if (c == null) {
            report.note("账号 3 落在第三个场景 " + describe(third.scene()) + "（不是每图每节点一个频道？），没有留在 S_A 的观察者，"
                    + "跳过「旁人收到 A 旧实体的 51 / A 换回后 C 收到 21」");
        } else {
            Optional<ActorCreateS2C> seen = awaitActor(c.connection(), 0, a.playerId(), observeTimeout);
            report.check(seen.isPresent() && seen.get().getEntity() == a.entity(), "落位：观察者 C 与 A 同在 S_A、看得见 A",
                    "C=" + c.account() + "；" + seen.map(x -> "47 / 21 含 A，entity=" + x.getEntity() + "（A 自己的 21 为 "
                            + a.entity() + "）").orElse(observeTimeout.toMillis() + " ms 内没收到含 A 的 47 / 21"), REF);
        }
        return new Bot[] {a, b, c};
    }

    // ------------------------------------------------------------------ 2 / 5. 跨节点换图

    /**
     * A 发 63 到 {@code target}（别的节点上的场景）并核对成功路径。
     *
     * @param peer     目标场景里的旁人（应看到 A 的 21，A 的 47 应含它）；可为 null
     * @param observer 源场景里的旁人（应看到 A 旧实体的 51）；可为 null
     */
    private Bot crossTo(String label, Bot a, SceneInfoComp target, Bot peer, Bot observer) throws RobotException {
        GameConnection connection = a.connection();
        int markA = connection.inbox().size();
        int markPeer = peer == null ? 0 : peer.connection().inbox().size();
        int markObserver = observer == null ? 0 : observer.connection().inbox().size();

        long requestId = connection.send(enterScene, switchRequest(target.getSceneConfigId(), target.getSceneId()));
        Received reply = awaitReply(connection, markA, requestId)
                .orElseThrow(() -> new RobotException(label + "：" + requestTimeout.toMillis() + " ms 内没收到 63 的应答"
                        + connection.describeSince(markA)));
        int tip = replyTip(reply);
        report.check(tip == 0, label + "：63 应答 {0}（已受理）", "目标 " + describe(target) + "，error_message.id=" + tip,
                REF_ACCEPTED);
        if (tip != 0) {
            throw new RobotException(label + "：63 被拒 " + tip + "（服务端还没有跨节点换图？），后续步骤无从验证");
        }

        Optional<Received> notify = connection.await(markA, r -> r.messageId() == ids.notifyEnterScene(), transferTimeout);
        if (notify.isEmpty()) {
            throw new RobotException(label + "：63 受理后 " + transferTimeout.toMillis() + " ms 内没收到 79"
                    + connection.describeSince(markA));
        }
        SceneInfoComp info = notify.get().parse(EnterSceneS2C.parser()).getSceneInfo();
        report.check(sameScene(info, target), label + "：79 是目标场景",
                "79 " + describe(info) + "，期望 " + describe(target), REF_SUCCESS);
        report.check(reply.index() < notify.get().index(), label + "：63 应答先于 79",
                "应答是本连接第 " + reply.index() + " 条下行，79 是第 " + notify.get().index() + " 条", REF_ACCEPTED);

        ActorCreateS2C self = awaitActor(connection, markA, a.playerId(), observeTimeout)
                .orElseThrow(() -> new RobotException(label + "：79 之后 " + observeTimeout.toMillis()
                        + " ms 内没收到自己的 21" + connection.describeSince(markA)));
        long entity = self.getEntity();
        Vec3 at = self.hasTransform() && self.getTransform().hasLocation() ? Vec3.of(self.getTransform().getLocation()) : null;
        report.check(entity != 0 && entity != a.entity(), label + "：自己的 21 是新实体号",
                "entity " + a.entity() + " → " + entity, REF_SUCCESS);
        report.check(at != null && at.approx(a.at(), LOCATION_EPS), label + "：同图保留坐标",
                "自己的 21 位置 " + at + "，换图前 " + a.at(), REF_POSITION);

        if (peer != null) {
            Optional<ActorCreateS2C> seenPeer = awaitActor(connection, markA, peer.playerId(), observeTimeout);
            report.check(seenPeer.isPresent(), label + "：A 收到含 " + peer.role() + " 的 47 / 21",
                    seenPeer.map(x -> peer.role() + " entity=" + x.getEntity())
                            .orElse(observeTimeout.toMillis() + " ms 内没收到" + connection.describeSince(markA)), REF_SUCCESS);
            Optional<ActorCreateS2C> peerSees = awaitActor(peer.connection(), markPeer, a.playerId(), observeTimeout);
            report.check(peerSees.isPresent() && peerSees.get().getEntity() == entity,
                    label + "：" + peer.role() + " 收到 A 的 21（新实体号）",
                    peerSees.map(x -> "entity=" + x.getEntity() + "，A 自己的 21 为 " + entity)
                            .orElse(observeTimeout.toMillis() + " ms 内没收到含 A 的 21 / 47"
                                    + peer.connection().describeSince(markPeer)), REF_SUCCESS);
        }
        if (observer != null) {
            long oldEntity = a.entity();
            Optional<Received> destroyed = observer.connection().await(markObserver,
                    r -> destroys(ids, r, oldEntity), observeTimeout);
            report.check(destroyed.isPresent(), label + "：" + observer.role() + " 收到 A 旧实体的 51",
                    destroyed.isPresent() ? "entity=" + oldEntity
                            : observeTimeout.toMillis() + " ms 内没收到 entity=" + oldEntity + " 的 51"
                                    + observer.connection().describeSince(markObserver), REF_SUCCESS);
        }
        List<Integer> tips = tipsSince(connection, markA);
        report.check(tips.isEmpty(), label + "：A 没收到 23", tips.isEmpty() ? "" : "收到 23 " + tips, REF_SUCCESS);
        return a.movedTo(info, entity, at == null ? a.at() : at);
    }

    // ------------------------------------------------------------------ 3. 在目标节点上玩

    /**
     * A 在目标场景走 {@link #WALK} 停下（134 → 等 {@link #WALK_TIME} → 131），再发 77：源节点在交出时已移除实例，
     * 改绑没生效时上行仍到源节点、会被静默丢弃（等不到应答），所以 77 有应答即目标节点在服务 A。B 应收到 A 的 66。
     */
    private Bot playOnTarget(Bot a, Bot b) throws RobotException {
        GameConnection connection = a.connection();
        int markA = connection.inbox().size();
        int markB = b.connection().inbox().size();
        Vec3 from = a.at();
        Vec3 to = from.plus(WALK);
        Vec3 velocity = new Vec3(WALK.x() * 1000.0 / WALK_TIME.toMillis(), 0, 0);
        long now = System.nanoTime() / 1_000_000;
        connection.send(ids.moveStart(), MoveStartC2S.newBuilder().setStartLocation(from.toLocation())
                .setRotation(FACING.toRotation()).setVelocity(velocity.toVelocity()).setClientTimeMs(now).setInputSeq(1).build());
        sleep(WALK_TIME);
        connection.send(ids.moveStop(), MoveStopC2S.newBuilder().setEndLocation(to.toLocation())
                .setRotation(FACING.toRotation()).setClientTimeMs(now + WALK_TIME.toMillis()).setInputSeq(2).build());

        ListSkillsResponse skills;
        try {
            skills = flow.listSkills(a.player());
        } catch (RobotException e) {
            report.fail("在目标节点上：A 发 77 有应答", e.getMessage(), REF_TARGET);
            throw e;
        }
        int skillCount = skills.hasSkillList() ? skills.getSkillList().getSkillListCount() : 0;
        int skillTip = skills.hasErrorMessage() ? skills.getErrorMessage().getId() : 0;
        report.check(skillTip == 0 && skillCount > 0, "在目标节点上：A 发 77 有应答（源节点已移除实例，只有目标节点能答）",
                "error_message.id=" + skillTip + "，技能 " + skillCount + " 个", REF_TARGET);

        Optional<Received> ack = connection.await(markA, r -> r.messageId() == ids.notifyMoveAck(), Duration.ZERO);
        Vec3 stopped = ack.isPresent() ? Vec3.of(ack.get().parse(MoveAckS2C.parser()).getServerLocation()) : to;
        Optional<Received> sync = b.connection().await(markB, r -> syncOf(ids, r, a.playerId()) != null, observeTimeout);
        report.check(sync.isPresent(), "在目标节点上：A 移动，B 收到 A 的 66",
                sync.map(r -> {
                    ActorBaseAttributesS2C s = syncOf(ids, r, a.playerId());
                    return "entity_id=" + s.getEntityId() + (s.hasTransform() && s.getTransform().hasLocation()
                            ? " 位置 " + Vec3.of(s.getTransform().getLocation()) : "");
                }).orElse(observeTimeout.toMillis() + " ms 内没收到" + b.connection().describeSince(markB)), REF_TARGET);
        report.note("在 S_B 上走到 " + stopped + (ack.isPresent() ? "（被 137 纠偏）" : ""));
        return a.movedTo(a.scene(), a.entity(), stopped);
    }

    // ------------------------------------------------------------------ 4. 断开重连

    /** A 断开（B 收到 A 的 51：目标节点持有并移除了 A）→ 立即重连 → 回到 S_B 原实例、原位置。 */
    private Bot reconnect(Bot a, Bot b) throws RobotException {
        int markB = b.connection().inbox().size();
        long oldEntity = a.entity();
        a.connection().close();
        Optional<Received> destroyed = b.connection().await(markB, r -> destroys(ids, r, oldEntity), requestTimeout);
        report.check(destroyed.isPresent(), "断开重连：A 断开后 B 收到 A 的 51（目标节点持有并移除了 A）",
                destroyed.isPresent() ? "entity=" + oldEntity
                        : requestTimeout.toMillis() + " ms 内没收到 entity=" + oldEntity + " 的 51", REF_RECONNECT);

        Bot back = login(a.account(), "A");
        report.check(sameScene(back.scene(), a.scene()), "断开重连：回到 S_B 原实例",
                "79 " + describe(back.scene()) + "，期望 " + describe(a.scene()), REF_RECONNECT);
        report.check(back.at().approx(a.at(), LOCATION_EPS), "断开重连：回到断开前走到的点",
                "自己的 21 位置 " + back.at() + "，期望 " + a.at(), REF_RECONNECT);
        return back;
    }

    // ------------------------------------------------------------------ 6. 目标不存在

    private void targetMissing(Bot a, Collection<Long> knownScenes) throws RobotException {
        GameConnection connection = a.connection();
        long missing = missingSceneId(knownScenes);
        int mark = connection.inbox().size();
        long requestId = connection.send(enterScene, switchRequest(0, missing));
        Received reply = awaitReply(connection, mark, requestId)
                .orElseThrow(() -> new RobotException("目标不存在：" + requestTimeout.toMillis() + " ms 内没收到 63 的应答"
                        + connection.describeSince(mark)));
        int tip = replyTip(reply);
        report.check(tip == 0, "目标不存在：63 应答 {0}（scene_id 不在本节点，先受理再问 scene-manager）",
                "scene_id=" + Long.toUnsignedString(missing) + " error_message.id=" + tip, REF_MISSING);

        Optional<Received> failed = connection.await(mark,
                r -> r.messageId() == ids.sendTip() && tipId(r) == ENTER_FAILED, transferTimeout);
        report.check(failed.isPresent() && failed.get().index() > reply.index(), "目标不存在：应答之后收到 23 {3023}",
                failed.map(r -> "应答是第 " + reply.index() + " 条下行，23 是第 " + r.index() + " 条")
                        .orElse(transferTimeout.toMillis() + " ms 内没收到" + connection.describeSince(mark)), REF_MISSING);

        // 留在原地：连接仍在、77 照常应答（还是源节点在服务）、这段时间没有 79
        ListSkillsResponse skills = flow.listSkills(a.player());
        long enters = countOf(connection.inbox().snapshot(mark), ids.notifyEnterScene());
        boolean answered = !skills.hasErrorMessage() || skills.getErrorMessage().getId() == 0;
        report.check(enters == 0 && answered && connection.isOpen(), "目标不存在：A 留在原地、可以继续玩",
                "79 " + enters + " 条，77 " + (answered ? "照常应答" : "回 " + skills.getErrorMessage().getId())
                        + "，连接" + (connection.isOpen() ? "仍在" : "已断开"), REF_MISSING);
    }

    // ------------------------------------------------------------------ 7. 连发

    private Bot doubleSwitch(Bot a, SceneInfoComp target) throws RobotException {
        GameConnection connection = a.connection();
        sleep(GATE_RATE_WINDOW);
        int mark = connection.inbox().size();
        EnterSceneC2SRequest request = switchRequest(target.getSceneConfigId(), target.getSceneId());
        long first = connection.send(enterScene, request);
        long second = connection.send(enterScene, request);

        Optional<Received> firstReply = awaitReply(connection, mark, first);
        Optional<Received> secondReply = awaitReply(connection, mark, second);
        report.check(firstReply.isPresent() && replyTip(firstReply.get()) == 0, "连发 63：第一条应答 {0}",
                firstReply.map(r -> "error_message.id=" + replyTip(r)).orElse("没有应答"), REF_IN_FLIGHT);
        report.check(secondReply.isPresent() && replyTip(secondReply.get()) == CHANGING_SCENE,
                "连发 63：第二条应答 " + CHANGING_SCENE + "（换场景在途）",
                secondReply.map(r -> "error_message.id=" + replyTip(r))
                        .orElse(requestTimeout.toMillis() + " ms 内没有应答（第二条到源节点时实例已交出会被静默丢弃：在途窗口没挡住它）"),
                REF_IN_FLIGHT);

        Optional<Received> notify = connection.await(mark, r -> r.messageId() == ids.notifyEnterScene(), transferTimeout);
        if (notify.isEmpty()) {
            throw new RobotException("连发 63：" + transferTimeout.toMillis() + " ms 内没收到 79" + connection.describeSince(mark));
        }
        SceneInfoComp info = notify.get().parse(EnterSceneS2C.parser()).getSceneInfo();
        report.check(sameScene(info, target), "连发 63：第一条照常换到 S_B",
                "79 " + describe(info) + "，期望 " + describe(target), REF_IN_FLIGHT);
        ActorCreateS2C self = awaitActor(connection, mark, a.playerId(), observeTimeout)
                .orElseThrow(() -> new RobotException("连发 63：79 之后 " + observeTimeout.toMillis()
                        + " ms 内没收到自己的 21" + connection.describeSince(mark)));
        sleep(QUIET_WINDOW);
        long enters = countOf(connection.inbox().snapshot(mark), ids.notifyEnterScene());
        report.check(enters == 1, "连发 63：只换了一次（恰好一条 79）", "79 " + enters + " 条", REF_IN_FLIGHT);
        Vec3 at = self.hasTransform() && self.getTransform().hasLocation() ? Vec3.of(self.getTransform().getLocation()) : a.at();
        return a.movedTo(info, self.getEntity(), at);
    }

    // ------------------------------------------------------------------ 8. 顶号

    /**
     * A 在 S_B（跨节点进来的）上时从另一条连接进同一角色：login 夺权 → 目标节点写回释放并踢旧会话 → gate 按改绑后的归属代次认这条踢人，
     * 旧连接收到 23 {2017}（gate 若没把会话的代次换成交出后的那一代，这条踢人会被当成迟到帧丢掉）。
     */
    private void takeover(Bot a) throws RobotException {
        GameConnection old = a.connection();
        int mark = old.inbox().size();
        Bot fresh = login(a.account(), "A（新连接）");
        Optional<Received> kicked = old.await(mark,
                r -> r.messageId() == ids.sendTip() && tipId(r) == KICKED_BY_ANOTHER, requestTimeout);
        report.check(kicked.isPresent(), "顶号：旧连接收到 23 {" + KICKED_BY_ANOTHER + "}",
                kicked.isPresent() ? "收到" : requestTimeout.toMillis() + " ms 内没收到" + old.describeSince(mark), REF_TAKEOVER);
        report.check(sameScene(fresh.scene(), a.scene()), "顶号：新连接回到 S_B 原实例",
                "79 " + describe(fresh.scene()) + "，期望 " + describe(a.scene()), REF_TAKEOVER);
    }

    // ------------------------------------------------------------------ 连接与收发

    private Bot login(String account, String role) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        ActorCreateS2C self = flow.awaitSelfActor(player, observeTimeout);
        return new Bot(role, player, player.sceneInfo(), self.getEntity(), Vec3.of(self.getTransform().getLocation()));
    }

    /** 契约里的干净登出：发完 17 立即关连接（gate 按主动离开通知 scene，位置记录不留重连租约）。 */
    private void leave(Bot bot) throws RobotException {
        bot.connection().send(leaveGame, LeaveGameRequest.getDefaultInstance());
        bot.connection().close();
    }

    private Optional<Received> awaitReply(GameConnection connection, int mark, long requestId) throws RobotException {
        return connection.await(mark, r -> r.messageId() == enterScene && r.requestId() == requestId, requestTimeout);
    }

    private Optional<ActorCreateS2C> awaitActor(GameConnection connection, int mark, long guid, Duration timeout)
            throws RobotException {
        return connection.await(mark, r -> findActor(ids, r, guid) != null, timeout).map(r -> findActor(ids, r, guid));
    }

    private List<Integer> tipsSince(GameConnection connection, int mark) {
        List<Integer> tips = new ArrayList<>();
        for (Received r : connection.inbox().snapshot(mark)) {
            if (r.messageId() == ids.sendTip()) {
                tips.add(tipId(r));
            }
        }
        return tips;
    }

    // ------------------------------------------------------------------ 纯函数（单测覆盖）

    /** 63 请求：只带配置号与场景号（{@code scene_config_id = 0} 表示不限定地图）。 */
    static EnterSceneC2SRequest switchRequest(int sceneConfigId, long sceneId) {
        return EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(sceneConfigId).setSceneId(sceneId))
                .build();
    }

    /** 同一张地图（配置号非 0 且相同）、两个不同的非 0 场景号：per-node 覆盖、每图每节点一个频道时即两个节点。 */
    static boolean onDifferentChannels(SceneInfoComp x, SceneInfoComp y) {
        return x.getSceneConfigId() != 0 && x.getSceneConfigId() == y.getSceneConfigId()
                && x.getSceneId() != 0 && y.getSceneId() != 0 && x.getSceneId() != y.getSceneId();
    }

    /** 账号 3 与谁同场景，谁当 A、账号 3 当观察者 C，另一个当 B；都不同则账号 1 当 A、没有观察者。 */
    static Roles assignRoles(long scene1, long scene2, long scene3) {
        if (scene3 == scene1) {
            return new Roles(1, 2, 3);
        }
        if (scene3 == scene2) {
            return new Roles(2, 1, 3);
        }
        return new Roles(1, 2, 0);
    }

    /**
     * 一个必定不存在的场景号：场景号来自频道计划的全服雪花（D18），是很大的数；从 1 起取第一个不在 {@code known} 里的（非 0）。
     */
    static long missingSceneId(Collection<Long> known) {
        long id = 1;
        while (known.contains(id)) {
            id++;
        }
        return id;
    }

    /** 63 应答的拒绝码：信封上的传输层错误优先（gate / 关停开关），否则应答体的 {@code error_message.id}；没有即 0，解析失败 -1。 */
    static int replyTip(Received reply) {
        if (reply.envelopeTipId() != 0) {
            return reply.envelopeTipId();
        }
        EnterSceneC2SResponse response = reply.parseOrNull(EnterSceneC2SResponse.parser());
        if (response == null) {
            return -1;
        }
        return response.hasErrorMessage() ? response.getErrorMessage().getId() : 0;
    }

    /** 23 推送的 tip 号；解析失败 -1。 */
    static int tipId(Received r) {
        TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
        return tip == null ? -1 : tip.getId();
    }

    /** 21 或 47 里 guid 为 {@code guid} 的那一项；没有返回 null。 */
    static ActorCreateS2C findActor(MessageIds ids, Received r, long guid) {
        if (r.messageId() == ids.notifyActorCreate()) {
            ActorCreateS2C actor = r.parseOrNull(ActorCreateS2C.parser());
            return actor != null && actor.getGuid() == guid ? actor : null;
        }
        if (r.messageId() == ids.notifyActorListCreate()) {
            ActorListCreateS2C list = r.parseOrNull(ActorListCreateS2C.parser());
            if (list != null) {
                for (ActorCreateS2C actor : list.getActorListList()) {
                    if (actor.getGuid() == guid) {
                        return actor;
                    }
                }
            }
        }
        return null;
    }

    /** 这条下行是否销毁实体 {@code entity}（51，或批量的 52 里含它）。 */
    static boolean destroys(MessageIds ids, Received r, long entity) {
        if (entity == 0) {
            return false;
        }
        if (r.messageId() == ids.notifyActorDestroy()) {
            ActorDestroyS2C destroy = r.parseOrNull(ActorDestroyS2C.parser());
            return destroy != null && destroy.getEntity() == entity;
        }
        if (r.messageId() == ids.notifyActorListDestroy()) {
            ActorListDestroyS2C list = r.parseOrNull(ActorListDestroyS2C.parser());
            return list != null && list.getEntityList().contains(entity);
        }
        return false;
    }

    /** 属于玩家 {@code playerId} 的 66（Java 版 66 的 entity_id 即玩家号）；不是返回 null。 */
    static ActorBaseAttributesS2C syncOf(MessageIds ids, Received r, long playerId) {
        if (r.messageId() != ids.syncBaseAttribute()) {
            return null;
        }
        ActorBaseAttributesS2C sync = r.parseOrNull(ActorBaseAttributesS2C.parser());
        return sync != null && sync.getEntityId() == playerId ? sync : null;
    }

    static long countOf(List<Received> received, int messageId) {
        return received.stream().filter(r -> r.messageId() == messageId).count();
    }

    static boolean sameScene(SceneInfoComp actual, SceneInfoComp expected) {
        return actual.getSceneId() == expected.getSceneId() && actual.getSceneConfigId() == expected.getSceneConfigId();
    }

    static String describe(SceneInfoComp info) {
        return "scene_config_id=" + Integer.toUnsignedString(info.getSceneConfigId())
                + " scene_id=" + Long.toUnsignedString(info.getSceneId());
    }

    private static void sleep(Duration d) throws RobotException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("被中断");
        }
    }
}
