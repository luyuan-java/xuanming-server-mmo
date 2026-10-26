package com.game.robot.scenario;

import static com.game.robot.scenario.MoveAssertions.fmt;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.TeleportS2C;
import com.game.proto.Vector3;
import com.game.robot.client.GameConnection;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.client.SceneAdminClient;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.MoveAssertions.InputKind;
import com.game.robot.scenario.MoveAssertions.InputMatch;
import com.game.robot.scenario.MoveAssertions.JumpVerdict;
import com.game.robot.scenario.MoveAssertions.Obs66;
import com.game.robot.scenario.MoveAssertions.Owner;
import com.game.robot.scenario.MoveAssertions.SentInput;
import com.game.robot.scenario.MoveAssertions.ObsMove;
import com.game.robot.scenario.MoveAssertions.ActorInputMatch;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 移动：单scene节点的两个新账号依次验证行走133（保留66旁证）、重登朝向、管理口瞬移130、超速跳跃；可另开30秒挂机探针。
 * 依据 {@code docs/reference/mmorpg-client-contract-movement.md}（下称 movement）与 {@code ...-aoi.md}（下称 AOI）。
 *
 * <ol>
 *   <li><b>进场</b>：A 先进、B 后进；两者同一 scene_id、相距 ≤ 10 m；B 收到含 A 的 47（或 A 的 21），实体号与位置同 A 自己的 21。</li>
 *   <li><b>行走</b>：A 每 250ms 一条：MoveStart → MoveSync ×3（最后一条上报 30 m/s）→ MoveStop，沿 +x 共约 4.4 m。
 *       主断言133的实体号、逐位位置/朝向/速度、状态、时间及顺序；66旁证保留（Start / Sync带对应velocity，超速截到10m/s；Stop全零velocity、
 *       位置 = 停止点）；66 的位置都在路径上、rotation = 上报值；停下后静默；A 对 134/132/131 无任何回包、不收 137、
 *       不收自己的 66。</li>
 *   <li><b>重登</b>：A 断开（B 收到 A 的 51）→ 重新登录进场 → 自己的 21 位置 = 停止点（离场写回 + 同图保留坐标）。</li>
 *   <li><b>瞬移</b>：管理口挪到+50m，核对130与视野差集、旧包落位等待；返回后从130落点起步。deny只验403，失败记报告后继续跳跃。</li>
 *   <li><b>跳跃</b>：A 在 MoveStart 之后 250ms 发一条上报位置前跳 200 m 的 MoveSync（远超 10 m/s × 0.25 s）。
 *       收到 137 → 按纠偏判（input_seq 回显、水平偏差 &gt; 0.5 m、UTC 毫秒），客户端服从、在 server_location 停下；
 *       没收到 → 按 fail-open 判，在上报位置停下。随后重登，落盘位置必须等于服务端的裁决位置。</li>
 * </ol>
 * 每次运行默认用新账号（{@code 前缀 + mv + 运行标签 + _a/_b}）：跳跃会把 A 的存档位置移走，复用账号会让下次 A、B 不在同一出生点。
 */
public final class MovementScenario {

    /** MoveSync 的间隔（proto 注释：移动中约每 250ms 一条）。 */
    static final Duration STEP = Duration.ofMillis(250);
    /** 行走计划的上报速率两两不同，便于把133/66对回输入；最后一条超速，期望截断到10m/s。 */
    static final double[] WALK_SPEEDS = {4.0, 4.5, 5.0, 30.0};
    /** 最后一条（超速）Sync 之后，Stop 再前进的距离（米）。 */
    static final double STOP_ADVANCE = 1.0;
    static final Vec3 DIRECTION = new Vec3(1, 0, 0);
    /** 朝向：服务器不解释、原样存原样广播（movement §1、§4.3 第 4 步）；取非零值才能核对是否原样。 */
    static final Vec3 FACING = new Vec3(0, 0, 90);
    /** 入视野半径（米，AOI §2.2）：先保证A、B互见才能验证133与66。 */
    static final double VIEW_RADIUS = 10.0;
    /** 超速跳跃的距离（米）：远超 10 m/s × 0.25 s = 2.5 m，也超过 Java 版位移令牌桶的上限 24 m。 */
    static final double JUMP_DISTANCE = 200.0;

    private static final double LOCATION_EPS = 1e-4;
    private static final double VELOCITY_EPS = 1e-6;
    /** 66 位置偏离路径的容差（米）：y / z 不应变化，只给浮点与起点吸附留一点余量。 */
    private static final double PATH_LATERAL_TOLERANCE = 0.5;
    /** 沿路径越过停止点的余量（米）：最后一条 Sync 之后服务器以 ≤ 10 m/s 外推，直到 Stop 到达（约一个 STEP，再加一倍余量）。 */
    static final double PATH_OVERSHOOT =MoveAssertions.MAX_TRUSTED_SPEED * STEP.toMillis() * 2 / 1000.0;
    /** 停下后观察「不再有 66」的窗口（movement §6.1：MoveStop 后发一条，然后静默）。 */
    private static final Duration QUIET_WINDOW = Duration.ofSeconds(1);
    /** 服从裁决后的 MoveStop 发出后，确认没有再被纠偏的等待时间。 */
    private static final Duration STOP_SETTLE = Duration.ofMillis(500);
    private static final int JUMP_SEQ = 2;

    private final PlayerFlow flow;
    private final MessageIds ids;
    private final String accountA;
    private final String accountB;
    private final Duration observeTimeout;
    private final Duration leaveTimeout;
    private final ExpectJump expectJump;
    private final SceneAdminClient admin;
    private final boolean expectDevAllowed;
    private final boolean afkProbe;

    /**
     * @param observeTimeout 133/66、瞬移130和跳跃137的观察上限
     * @param leaveTimeout   A 断开后 B 收到 51 的上限（要经过 gate 发现断线 → scene 离场）
     * @param admin          A所在节点的dev管理口，调用时才校验运维令牌
     * @param expectDevAllowed true跑瞬移全段，false仅核对403
     * @param afkProbe       另跑30秒挂机停推探针
     */
    public MovementScenario(PlayerFlow flow, MessageIds ids, String accountPrefix, String runTag,
                            Duration observeTimeout, Duration leaveTimeout, ExpectJump expectJump,
                            SceneAdminClient admin, boolean expectDevAllowed, boolean afkProbe) {
        this.flow = flow;
        this.ids = ids;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.observeTimeout = observeTimeout;
        this.leaveTimeout = leaveTimeout;
        this.expectJump = expectJump;
        this.admin = admin;
        this.expectDevAllowed = expectDevAllowed;
        this.afkProbe = afkProbe;
    }

    public static String accountName(String prefix, String runTag, String role) {
        return prefix + "mv" + runTag + "_" + role;
    }

    /** 计划里的一条移动输入。 */
    record PlannedInput(InputKind kind, Vec3 location, Vec3 velocity) {
    }

    /**
     * 行走计划：从 {@code start} 沿 +x，Start / Sync 的上报位置按上一条的速率 × 0.25 s 推进（与速度自洽，不触发位移校验），
     * 最后一条超速 Sync 之后 Stop 只再前进 {@link #STOP_ADVANCE}。
     */
    static List<PlannedInput> planWalk(Vec3 start) {
        List<PlannedInput> plan = new ArrayList<>();
        Vec3 at = start;
        double stepSeconds = STEP.toMillis() / 1000.0;
        for (int i = 0; i < WALK_SPEEDS.length; i++) {
            plan.add(new PlannedInput(i == 0 ? InputKind.START : InputKind.SYNC, at, DIRECTION.scaled(WALK_SPEEDS[i])));
            boolean last = i == WALK_SPEEDS.length - 1;
            at = at.plus(DIRECTION.scaled(last ? STOP_ADVANCE : WALK_SPEEDS[i] * stepSeconds));
        }
        plan.add(new PlannedInput(InputKind.STOP, at, Vec3.ZERO));
        return plan;
    }

    public String accountA() {
        return accountA;
    }

    public String accountB() {
        return accountB;
    }

    public CheckReport run() {
        CheckReport report = new CheckReport();
        report.note("账号 A=" + accountA + "，B=" + accountB + "；跳跃期望=" + expectJump);
        List<GameConnection> opened = new ArrayList<>();
        try {
            EnteredPlayer a = enter(accountA, "A", report, opened);
            ActorCreateS2C selfA = flow.awaitSelfActor(a, observeTimeout);
            EnteredPlayer b = enter(accountB, "B", report, opened);
            ActorCreateS2C selfB = flow.awaitSelfActor(b, observeTimeout);

            checkSameSceneAndVisible(a, selfA, b, selfB, report);
            Vec3 stopAt = walk(a, b, selfA.getEntity(), Vec3.of(selfA.getTransform().getLocation()), report);

            Reentry again = reconnect(a, selfA, stopAt, b, "行走停止点", report, opened);
            try {
                again = teleport(again, b, selfB, report);
            } catch (RobotException e) {
                report.fail("瞬移段失败", e.getMessage(), "movement-broadcast-spec §11.6");
                report.note("瞬移段余下步骤跳过，继续跳跃段；起点使用重登的21位置");
            }
            if (afkProbe) {
                again = afk(again, b, report);
            }
            jump(again, report, opened);
        } catch (RobotException e) {
            report.fail("流程中断", e.getMessage(), "");
        } finally {
            for (GameConnection connection : opened) {
                connection.close();
            }
        }
        return report;
    }

    // ------------------------------------------------------------------ 进场

    private EnteredPlayer enter(String account, String role, CheckReport report, List<GameConnection> opened)
            throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        opened.add(player.connection());
        report.pass(role + " 登录进场", "player_id=" + player.playerId() + (player.created() ? "（新建角）" : "")
                        + " scene_id=" + player.sceneInfo().getSceneId(),
                "robot 契约 §4 步骤 A–E");
        return player;
    }

    private void checkSameSceneAndVisible(EnteredPlayer a, ActorCreateS2C selfA, EnteredPlayer b, ActorCreateS2C selfB,
                                          CheckReport report) throws RobotException {
        long sceneA = a.sceneInfo().getSceneId();
        long sceneB = b.sceneInfo().getSceneId();
        if (sceneA != sceneB) {
            throw new RobotException("A、B 没有分到同一场景实例（scene_id " + sceneA + " / " + sceneB
                    + "），无法验证视野内广播。场景按人数最少的频道分配，稍后重试或只起一个 scene 节点");
        }
        Vec3 posA = Vec3.of(selfA.getTransform().getLocation());
        Vec3 posB = Vec3.of(selfB.getTransform().getLocation());
        if (posA.distance(posB) > VIEW_RADIUS) {
            throw new RobotException("A " + posA + " 与 B " + posB + " 相距 " + fmt(posA.distance(posB))
                    + " m，超出视野半径 10 m（新号应落在同一出生点，scene 契约 §1）");
        }

        Optional<Received> seen = b.connection().await(0, r -> actorFor(r, a.playerId()) != null, observeTimeout);
        if (seen.isEmpty()) {
            report.fail("B 看得见 A", observeTimeout.toMillis() + " ms 内 B 没收到含 A 的 47 / 21", "AOI §2.4、§3.2；scene 契约 §3.3");
            throw new RobotException("B 看不见 A，后续133广播与66旁证无从验证");
        }
        ActorCreateS2C seenA = actorFor(seen.get(), a.playerId());
        boolean sameEntity = seenA.getEntity() == selfA.getEntity();
        Vec3 seenAt = seenA.hasTransform() ? Vec3.of(seenA.getTransform().getLocation()) : null;
        boolean samePlace = seenAt != null && seenAt.approx(posA, LOCATION_EPS);
        report.check(sameEntity && samePlace, "B 看得见 A",
                "B 经 " + seen.get().messageId() + " 收到 A：entity=" + seenA.getEntity() + "（A 自己的 21 为 "
                        + selfA.getEntity() + "），位置 " + seenAt + "（A 自己的 21 为 " + posA + "）",
                "AOI §3.1：同一实体发给谁内容都一样；AOI §2.4、scene 契约 §3.3");

        boolean aSeesB = a.connection().inbox().snapshot(0).stream().anyMatch(r -> actorFor(r, b.playerId()) != null);
        report.note("A 是否收到 B 进场的 21 / 47：" + (aSeesB ? "是" : "否")
                + "（基线不给场内老观察者补发，Java 版补发 21；AOI §7 第 1 条）");
    }

    /** 21 或 47 里 guid 为 {@code playerId} 的那一项；没有返回 null。 */
    private ActorCreateS2C actorFor(Received r, long playerId) {
        if (r.messageId() == ids.notifyActorCreate()) {
            ActorCreateS2C actor = r.parseOrNull(ActorCreateS2C.parser());
            return actor != null && actor.getGuid() == playerId ? actor : null;
        }
        if (r.messageId() == ids.notifyActorListCreate()) {
            ActorListCreateS2C list = r.parseOrNull(ActorListCreateS2C.parser());
            if (list != null) {
                for (ActorCreateS2C actor : list.getActorListList()) {
                    if (actor.getGuid() == playerId) {
                        return actor;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 行走

    /** 走完行走计划并逐条断言，返回停止点。 */
    private Vec3 walk(EnteredPlayer a, EnteredPlayer b, long entity, Vec3 start, CheckReport report) throws RobotException {
        List<PlannedInput> plan = planWalk(start);
        Vec3 stopAt = plan.get(plan.size() - 1).location();
        int markA = a.connection().inbox().size();
        int markB = b.connection().inbox().size();

        List<SentInput> sent = sendPlan(a.connection(), plan);

        // 等 B 看到停下的那条 66，再看一个静默窗口。
        b.connection().await(markB, r -> {
            Obs66 obs = moverObservation(r, a.playerId(), b.playerId());
            return obs != null && obs.isStop() && obs.location() != null && obs.location().approx(stopAt, LOCATION_EPS);
        }, observeTimeout);
        awaitStopped(b.connection(), markB, entity, stopAt);
        sleep(QUIET_WINDOW);

        List<Obs66> observed = new ArrayList<>();
        int unattributed = 0;
        for (Received r : b.connection().inbox().snapshot(markB)) {
            Obs66 obs = moverObservation(r, a.playerId(), b.playerId());
            if (obs != null) {
                observed.add(obs);
                if (obs.entityId() == 0) {
                    unattributed++;
                }
            }
        }
        report.note("B 收到 A 的 66 共 " + observed.size() + " 条，其中不带 entity_id 的 " + unattributed
                + " 条（基线移动的 66 不带 entity_id、按「只有 A 在动」归给 A；Java 版每条都带，AOI §5.1、§9）");

        List<InputMatch> matches = MoveAssertions.matchInputs(sent, observed, LOCATION_EPS, VELOCITY_EPS);
        checkInputsObserved(matches, report);
        checkPath(start, stopAt, observed, report);
        checkFacing(matches, report);
        checkQuietAfterStop(matches.get(matches.size() - 1).observed(), observed, report);
        checkMoverSide(a, b, markA, markB, "行走", report);
        checkActorInputs(a, b, entity, markA, markB, sent, "行走", report);
        return stopAt;
    }

    private List<SentInput> sendPlan(GameConnection connection, List<PlannedInput> plan) throws RobotException {
        List<SentInput> sent = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < plan.size(); i++) {
            sleepUntil(t0 + i * STEP.toNanos());
            PlannedInput input = plan.get(i);
            int seq = i + 1;
            long sentAt = System.nanoTime();
            send(connection, input.kind(), input.location(), input.velocity(), seq);
            sent.add(new SentInput(seq, input.kind(), input.location(), input.velocity(), sentAt));
        }
        return sent;
    }

    /** 每条输入之后，B 在观察时限内收到由它引起的 66（movement §6.1、§4.3「速度处理」；AOI §5.1）。 */
    private void checkInputsObserved(List<InputMatch> matches, CheckReport report) {
        for (InputMatch match : matches) {
            SentInput input = match.input();
            String name = "输入#" + input.seq() + " " + input.kind().method() + " → B 收到 A 的 66";
            String expected = input.kind() == InputKind.STOP
                    ? "transform 位置 = 停止点 " + input.location() + "、velocity 存在且全零"
                    : "transform + velocity " + input.expectedVelocity()
                            + (input.expectedVelocity().approx(input.sentVelocity(), VELOCITY_EPS) ? ""
                                    : "（上报 " + fmt(input.sentVelocity().length()) + " m/s，截断到 "
                                            + fmt(MoveAssertions.MAX_TRUSTED_SPEED) + "）");
            String ref = input.kind() == InputKind.STOP ? "movement §6.1（停止后一条全零 velocity）、§3.7"
                    : "movement §6.1、§4.3「速度处理」；AOI §5.1";
            if (match.observed() == null) {
                report.fail(name, "没有收到期望的 66：" + expected, ref);
                continue;
            }
            long latency = match.latencyMillis();
            boolean inTime = latency <= observeTimeout.toMillis();
            report.check(inTime, name, latency + " ms（上限 " + observeTimeout.toMillis() + " ms）；期望 " + expected
                    + "；实际位置 " + match.observed().location() + " velocity " + match.observed().velocity(), ref);
        }
    }

    private void checkPath(Vec3 start, Vec3 stopAt, List<Obs66> observed, CheckReport report) {
        List<String> offPath = new ArrayList<>();
        for (Obs66 obs : observed) {
            if (obs.location() != null) {
                MoveAssertions.offPath(obs.location(), start, stopAt, PATH_LATERAL_TOLERANCE, PATH_OVERSHOOT)
                        .ifPresent(why -> offPath.add("#" + obs.index() + " " + obs.location() + " " + why));
            }
        }
        report.check(offPath.isEmpty(), "B 看到的 A 的位置都在行走路径上",
                offPath.isEmpty() ? "y / z 不变，沿 +x 在 [起点, 停止点 + " + fmt(PATH_OVERSHOOT) + " m 外推余量] 内" : String.join("；", offPath),
                "movement §5（服务器外推）、§6.1");
    }

    /**
     * 由输入引起的 66（已对上输入的那几条）都带 rotation 且等于上报值：每条输入都用请求里的 rotation 整体覆盖，
     * 所以 transform 脏时 rotation 一定在（movement §4.3 第 4 步、§3.7）。只看对上输入的 66：其它 66（外推、新观察者补发）
     * 的 transform 同样带 rotation，但它们何时发出与输入无关，不作为依据。
     */
    private void checkFacing(List<InputMatch> matches, CheckReport report) {
        List<String> wrongFacing = new ArrayList<>();
        for (InputMatch match : matches) {
            Obs66 obs = match.observed();
            if (obs != null && (obs.rotation() == null || !obs.rotation().approx(FACING, VELOCITY_EPS))) {
                wrongFacing.add("输入#" + match.input().seq() + " 对应的 66 rotation=" + obs.rotation());
            }
        }
        report.check(wrongFacing.isEmpty(), "66 的 transform.rotation = A 上报的朝向 " + FACING,
                wrongFacing.isEmpty() ? "" : String.join("；", wrongFacing), "movement §4.3 第 4 步、§3.7");
    }

    /** 停下之后静默：停止的那条 66 之后，观察窗口内不再有 A 的 66（movement §6.1「然后静默，直到下一次输入」）。 */
    private void checkQuietAfterStop(Obs66 stop, List<Obs66> observed, CheckReport report) {
        if (stop == null) {
            report.fail("停下后静默", "没看到停止的 66，无从判断", "movement §6.1");
            return;
        }
        long after = observed.stream().filter(o -> o.index() > stop.index()).count();
        report.check(after == 0, "停下后静默", after == 0 ? QUIET_WINDOW.toMillis() + " ms 内没有再收到 A 的 66"
                : "停止后又收到 " + after + " 条 A 的 66", "movement §6.1");
    }

    /**
     * 移动者一侧：134/132/131 永不回包（应答类型 Empty），合法移动不被纠偏（不收 137），66 不发给自己；
     * 观察者也不收自己的 66。
     */
    private void checkMoverSide(EnteredPlayer a, EnteredPlayer b, int markA, int markB, String phase,
                                CheckReport report) {
        List<Received> atA = a.connection().inbox().snapshot(markA);
        long replies = atA.stream().filter(r -> ids.isMoveInput(r.messageId())).count();
        report.check(replies == 0, phase + "：A 对 134/132/131 没有任何回包",
                replies == 0 ? "" : "收到 " + replies + " 条 message_id 为移动上行的下行（含信封错误）",
                "movement §0 第 1 条、§2 表；AOI §4.1");

        List<Integer> acks = atA.stream().filter(r -> r.messageId() == ids.notifyMoveAck())
                .map(r -> {
                    MoveAckS2C ack = r.parseOrNull(MoveAckS2C.parser());
                    return ack == null ? -1 : ack.getInputSeq();
                }).toList();
        report.check(acks.isEmpty(), phase + "：合法移动不被纠偏（A 不收 137）",
                acks.isEmpty() ? "" : "收到 137，input_seq=" + acks, "movement §4.3 第 5 步、§0 第 2 条");

        long moverEcho = countOwnSync(a, markA, true);
        long observerEcho = countOwnSync(b, markB, false);
        report.check(moverEcho == 0 && observerEcho == 0, phase + "：66 不发给被同步者自己",
                "A 收到自己的 66 " + moverEcho + " 条，B 收到自己的 66 " + observerEcho + " 条", "movement §6.1；AOI §5.1");
    }

    /**
     * 收件人收到的「自己的」66 条数：{@code entity_id} = 自己；收件人是本场景唯一的移动者时，另算不带 entity_id 但带 velocity 的
     * （只有移动输入会置 velocity 脏位，基线移动的 66 又不带 entity_id——场景里只有它在动，那条只能是它自己的回声）。
     */
    private long countOwnSync(EnteredPlayer receiver, int from, boolean receiverIsOnlyMover) {
        long count = 0;
        for (Received r : receiver.connection().inbox().snapshot(from)) {
            if (r.messageId() != ids.syncBaseAttribute()) {
                continue;
            }
            ActorBaseAttributesS2C sync = r.parseOrNull(ActorBaseAttributesS2C.parser());
            if (sync == null) {
                continue;
            }
            Owner owner = MoveAssertions.ownerOf(sync, 0, receiver.playerId());
            if (owner == Owner.RECEIVER
                    || (receiverIsOnlyMover && owner == Owner.UNATTRIBUTED && sync.hasVelocity())) {
                count++;
            }
        }
        return count;
    }

    /** 观察者收到的一条 66 若属于移动者（entity_id = 移动者，或不带 entity_id）则转成观察记录，否则 null。 */
    private Obs66 moverObservation(Received r, long moverId, long receiverId) {
        if (r.messageId() != ids.syncBaseAttribute()) {
            return null;
        }
        ActorBaseAttributesS2C sync = r.parseOrNull(ActorBaseAttributesS2C.parser());
        if (sync == null) {
            return null;
        }
        Owner owner = MoveAssertions.ownerOf(sync, moverId, receiverId);
        if (owner != Owner.MOVER && owner != Owner.UNATTRIBUTED) {
            return null;
        }
        return Obs66.of(r.index(), r.receivedNanos(), sync);
    }

    // ------------------------------------------------------------------ 重登

    /** 重登后的 A。 */
    private record Reentry(EnteredPlayer player, ActorCreateS2C self) {
    }

    /**
     * A 断开 → （可选）观察者收到 A 的 51 → A 重新登录进场 → 自己的 21 位置 = {@code expected}
     * （离场先清速度再写回 location，再进场同图保留坐标：movement §7；scene 契约 §1）。
     *
     * @param observer 仍看得见 A 的观察者；为 null 时不检查 51
     */
    private Reentry reconnect(EnteredPlayer old, ActorCreateS2C oldSelf, Vec3 expected, EnteredPlayer observer,
                              String what, CheckReport report, List<GameConnection> opened) throws RobotException {
        int markObserver = observer == null ? 0 : observer.connection().inbox().size();
        old.connection().close();
        if (observer != null) {
            Optional<Received> destroyed = observer.connection().await(markObserver, r -> {
                if (r.messageId() != ids.notifyActorDestroy()) {
                    return false;
                }
                ActorDestroyS2C destroy = r.parseOrNull(ActorDestroyS2C.parser());
                return destroy != null && destroy.getEntity() == oldSelf.getEntity();
            }, leaveTimeout);
            report.check(destroyed.isPresent(), "A 断开后 B 收到 A 的 51",
                    destroyed.isPresent() ? "entity=" + oldSelf.getEntity()
                            : leaveTimeout.toMillis() + " ms 内没收到 entity=" + oldSelf.getEntity() + " 的 51",
                    "AOI §2.5；scene 契约 §5");
        }

        EnteredPlayer again = flow.enter(old.account(), new Timings());
        opened.add(again.connection());
        if (again.playerId() != old.playerId()) {
            throw new RobotException(old.account() + " 重登后角色变了：" + old.playerId() + " → " + again.playerId());
        }
        ActorCreateS2C self = flow.awaitSelfActor(again, observeTimeout);
        Vec3 at = Vec3.of(self.getTransform().getLocation());
        report.check(at.approx(expected, LOCATION_EPS), "A 重登后位置 = " + what,
                "自己的 21 位置 " + at + "，期望 " + expected + "（差 " + fmt(at.distance(expected)) + " m）",
                "movement §7（离场写回 location、再进场同图保留坐标）；scene 契约 §1");
        if (observer != null) {
            Optional<Received> peerCreate = observer.connection().await(markObserver,
                    r -> r.messageId() == ids.notifyActorCreate() && actorFor(r, again.playerId()) != null, observeTimeout);
            ActorCreateS2C seen = peerCreate.map(r -> actorFor(r, again.playerId())).orElse(null);
            report.check(self.hasTransform() && self.getTransform().hasRotation()
                            && MoveAssertions.sameBits(Vec3.of(self.getTransform().getRotation()), FACING)
                            && seen != null && seen.getEntity() == self.getEntity() && seen.getEntity() != oldSelf.getEntity()
                            && seen.getTransform().hasRotation()
                            && MoveAssertions.sameBits(Vec3.of(seen.getTransform().getRotation()), FACING)
                            && peerCreate.get().requestId() == 0,
                    "重登：本人和B收到的新实体21带FACING", "entity=" + self.getEntity() + "，B收到=" + (seen != null),
                    "movement-broadcast-spec §11.6 D2");
            sleep(QUIET_WINDOW);
            long moving = MoveAssertions.moves(observer.connection().inbox().snapshot(markObserver), ids).stream()
                    .filter(o -> o.move().getEntity() == self.getEntity()).count();
            report.check(moving == 0, "重登静止目标不补133/135", "1s内条目=" + moving, "movement-broadcast-spec §11.6 D2");
        }
        return new Reentry(again, self);
    }

    // ------------------------------------------------------------------ 133与瞬移（判定逻辑集中在纯函数MoveAssertions）

    private void awaitStopped(GameConnection observer, int mark, long entity, Vec3 at) throws RobotException {
        observer.await(mark, r -> {
            if (r.messageId() != ids.notifyActorMove()) return false;
            ActorMoveS2C move = r.parseOrNull(ActorMoveS2C.parser());
            return move != null && move.getEntity() == entity && move.hasVelocity()
                    && Vec3.of(move.getVelocity()).isZero() && move.hasTransform() && move.getTransform().hasLocation()
                    && MoveAssertions.sameBits(Vec3.of(move.getTransform().getLocation()), at);
        }, observeTimeout);
    }

    private void checkActorInputs(EnteredPlayer a, EnteredPlayer b, long entity, int markA, int markB,
                                  List<SentInput> inputs, String phase, CheckReport report) throws RobotException {
        List<ObsMove> observed = MoveAssertions.moves(b.connection().inbox().snapshot(markB), ids).stream()
                .filter(o -> o.move().getEntity() == entity).toList();
        List<ActorInputMatch> matches = MoveAssertions.matchActorInputs(inputs, observed);
        for (ActorInputMatch match : matches) {
            List<String> problems = match.observed() == null || match.effective() == null
                    ? List.of("没收到对应133")
                    : MoveAssertions.actorMoveProblems(match.observed(), entity, match.effective(), FACING, System.currentTimeMillis());
            boolean inTime = match.observed() != null && match.latencyMillis() >= 0
                    && match.latencyMillis() <= observeTimeout.toMillis();
            report.check(problems.isEmpty() && inTime, phase + "：输入#" + match.input().seq() + " → B的133逐字段正确",
                    "时延=" + match.latencyMillis() + "ms，" + String.join("；", problems), "movement-broadcast-spec §11.6");
        }
        report.check(observed.size() <= inputs.size() && observed.stream().allMatch(o -> o.messageId() == ids.notifyActorMove()),
                phase + "：133不多于输入条数，逐帧外推不发", "输入=" + inputs.size() + "，移动条目=" + observed.size(),
                "movement-broadcast-spec §5.3/11.6");
        long own = MoveAssertions.moves(a.connection().inbox().snapshot(markA), ids).stream()
                .filter(o -> o.move().getEntity() == entity).count();
        report.check(own == 0, phase + "：A不收自己的133/135", "条目=" + own, "movement-broadcast-spec C1");
        ActorInputMatch stop = matches.getLast();
        long after = stop.observed() == null ? -1
                : observed.stream().filter(o -> o.index() > stop.observed().index()).count();
        report.check(after == 0, phase + "：停步后1s没有额外133", "额外条目=" + after, "movement-broadcast-spec §11.6");
    }

    /** 本地记住130的落点，后续上报不得再从进场21的旧位置起步。 */
    private static Reentry at(Reentry player, Vec3 location) {
        return new Reentry(player.player(), player.self().toBuilder().setTransform(player.self().getTransform().toBuilder()
                .setLocation(Vector3.newBuilder().setX(location.x()).setY(location.y()).setZ(location.z()))).build());
    }

    private Received awaitTeleport(Reentry a, int mark) throws RobotException {
        return a.player().connection().await(mark, r -> r.messageId() == ids.notifyTeleport(), observeTimeout)
                .orElseThrow(() -> new RobotException("管理口成功，但A在观察时限内未收到130"));
    }

    private void requireTeleportTip(int tip) throws RobotException {
        if (tip == 1004) throw new RobotException("管理口回1004：--scene-admin-url没指向A所在的节点；movement请在单scene节点切片上跑");
        if (tip != 0) throw new RobotException("瞬移管理口回tip_id=" + tip + "（1005参数/落点非法，1006冻结或战斗在途）");
    }

    private Reentry teleport(Reentry again, EnteredPlayer b, ActorCreateS2C selfB, CheckReport report) throws RobotException {
        GameConnection a = again.player().connection();
        long entity = again.self().getEntity();
        Vec3 original = Vec3.of(again.self().getTransform().getLocation());
        Vec3 target = original.plus(DIRECTION.scaled(50));
        int markA = a.inbox().size();
        int markB = b.connection().inbox().size();
        SceneAdminClient.HttpResult result = admin.teleportRaw(again.player().playerId(), target.x(), target.y(), target.z());
        if (!expectDevAllowed) {
            report.check(result.status() == 403, "瞬移：prod管理口回403", "HTTP " + result.status() + " " + result.text(),
                    "movement-broadcast-spec §11.6");
            report.note("--expect-dev deny：已核对403，跳过瞬移段余下步骤，行走/重登/跳跃照跑");
            return again;
        }
        if (result.status() != 200) throw new RobotException("瞬移管理口返回HTTP " + result.status()
                + SceneAdminClient.statusHint(result.status()) + "：" + result.text());
        requireTeleportTip(SceneAdminClient.teleportTip(result));
        Received outgoing = awaitTeleport(again, markA);
        TeleportS2C teleport = outgoing.parse(TeleportS2C.parser());
        Reentry moved = at(again, Vec3.of(teleport.getTransform().getLocation()));
        // 收到130后立即模拟旧位置在途包，不能等视野下行后才发，以免错过3秒落位等待。
        int staleMark = a.inbox().size();
        send(a, InputKind.SYNC, original, DIRECTION.scaled(4), 31);
        sleep(STOP_SETTLE);
        report.check(a.inbox().snapshot(staleMark).stream().noneMatch(r -> r.messageId() == ids.notifyMoveAck()),
                "瞬移：收到130后立即发旧位置132不收137", "旧位置距落点50m，应整条丢弃", "movement-broadcast-spec §5.8/11.6");
        Vec3 settled = Vec3.of(moved.self().getTransform().getLocation()).plus(DIRECTION);
        send(a, InputKind.STOP, settled, Vec3.ZERO, 32);
        sleep(STOP_SETTLE);
        // 上报已在3秒内发完，随后用完整观察窗口捕获迟到纠偏，避免只看500ms造成假通过。
        a.await(staleMark, r -> r.messageId() == ids.notifyMoveAck(), observeTimeout);
        report.check(a.inbox().snapshot(staleMark).stream().noneMatch(r -> r.messageId() == ids.notifyMoveAck()),
                "瞬移：目标附近131落位后照常裁决且无137", "位置=" + settled, "movement-broadcast-spec §5.8/11.6");
        a.await(markA, r -> containsDestroyed(r, selfB.getEntity()), observeTimeout);
        b.connection().await(markB, r -> containsDestroyed(r, entity), observeTimeout);
        List<String> outProblems = MoveAssertions.teleportSequenceProblems(a.inbox().snapshot(markA),
                b.connection().inbox().snapshot(markB), ids, entity, selfB.getEntity(), target, FACING, false);
        report.check(outProblems.isEmpty(), "瞬移去程：A的130→64、B的64逐字段与顺序正确",
                String.join("；", outProblems), "movement-broadcast-spec §5.7/11.6");

        markA = a.inbox().size();
        markB = b.connection().inbox().size();
        requireTeleportTip(admin.teleport(again.player().playerId(), original.x(), original.y(), original.z()));
        Received incoming = awaitTeleport(again, markA);
        moved = at(again, Vec3.of(incoming.parse(TeleportS2C.parser()).getTransform().getLocation()));
        a.await(markA, r -> r.messageId() == ids.notifyActorListCreate() && actorFor(r, b.playerId()) != null, observeTimeout);
        b.connection().await(markB, r -> r.messageId() == ids.notifyActorCreate()
                && actorFor(r, again.player().playerId()) != null, observeTimeout);
        sleep(QUIET_WINDOW);
        List<String> backProblems = MoveAssertions.teleportSequenceProblems(a.inbox().snapshot(markA),
                b.connection().inbox().snapshot(markB), ids, entity, selfB.getEntity(), original, FACING, true);
        report.check(backProblems.isEmpty(), "瞬移回程：A的130→47、B的21且静止无133/135",
                String.join("；", backProblems), "movement-broadcast-spec §5.7/11.6");
        Vec3 from = Vec3.of(moved.self().getTransform().getLocation());
        Vec3 stop = from.plus(DIRECTION);
        markA = a.inbox().size();
        markB = b.connection().inbox().size();
        List<SentInput> sent = sendPlan(a, List.of(new PlannedInput(InputKind.START, from, DIRECTION.scaled(4)),
                new PlannedInput(InputKind.STOP, stop, Vec3.ZERO)));
        awaitStopped(b.connection(), markB, entity, stop);
        sleep(QUIET_WINDOW);
        checkActorInputs(again.player(), b, entity, markA, markB, sent, "瞬移后再走一步", report);
        checkMoverSide(again.player(), b, markA, markB, "瞬移后再走一步", report);
        report.note("瞬移段已运行：落点+50m、旧包丢弃、附近落位、返回与后续行走");
        return at(moved, stop);
    }

    private boolean containsDestroyed(Received frame, long entity) {
        if (frame.messageId() != ids.notifyActorListDestroy()) return false;
        ActorListDestroyS2C list = frame.parseOrNull(ActorListDestroyS2C.parser());
        return list != null && list.getEntityList().contains(entity);
    }

    /** A只有起步一条输入，之后保持连接静默，观察600帧后服务器主动停步。 */
    private Reentry afk(Reentry again, EnteredPlayer b, CheckReport report) throws RobotException {
        int mark = b.connection().inbox().size();
        int markA = again.player().connection().inbox().size();
        Vec3 start = Vec3.of(again.self().getTransform().getLocation());
        long sentAt = System.nanoTime();
        send(again.player().connection(), InputKind.START, start, DIRECTION.scaled(0.3), 41);
        Duration wait = Duration.ofSeconds(35).plus(observeTimeout);
        b.connection().await(mark, r -> {
            if (r.messageId() != ids.notifyActorMove()) return false;
            ActorMoveS2C move = r.parseOrNull(ActorMoveS2C.parser());
            return move != null && move.getEntity() == again.self().getEntity()
                    && move.hasVelocity() && Vec3.of(move.getVelocity()).isZero();
        }, wait);
        sleep(QUIET_WINDOW);
        List<ObsMove> observed = MoveAssertions.moves(b.connection().inbox().snapshot(mark), ids).stream()
                .filter(o -> o.move().getEntity() == again.self().getEntity()).toList();
        List<String> problems = MoveAssertions.afkProblems(observed, again.self().getEntity(), start, FACING, sentAt,
                wait.toMillis(), System.currentTimeMillis());
        report.check(problems.isEmpty() && observed.stream().allMatch(o -> o.messageId() == ids.notifyActorMove()),
                "挂机探针：只有起步133和约30s后的零速133", String.join("；", problems),
                "movement-broadcast-spec §11.6 --afk-probe");
        long own = MoveAssertions.moves(again.player().connection().inbox().snapshot(markA), ids).stream()
                .filter(o -> o.move().getEntity() == again.self().getEntity()).count();
        report.check(own == 0 && b.connection().inbox().snapshot(mark).stream()
                        .noneMatch(r -> containsDestroyed(r, again.self().getEntity())),
                "挂机探针：A没有自己的133且始终留在B视野", "自己的条目=" + own, "movement-broadcast-spec §11.6");
        report.note("当前Unity超过1s无移动样本就停止画面外推，服务端30s停推以收包断言为准");
        return observed.isEmpty() || observed.getLast().asMotion().location() == null ? again
                : at(again, observed.getLast().asMotion().location());
    }

    // ------------------------------------------------------------------ 跳跃

    private void jump(Reentry reentry, CheckReport report, List<GameConnection> opened) throws RobotException {
        EnteredPlayer a = reentry.player();
        GameConnection connection = a.connection();
        Vec3 from = Vec3.of(reentry.self().getTransform().getLocation());
        Vec3 velocity = DIRECTION.scaled(WALK_SPEEDS[0]);
        Vec3 target = from.plus(DIRECTION.scaled(JUMP_DISTANCE));
        int mark = connection.inbox().size();

        long t0 = System.nanoTime();
        send(connection, InputKind.START, from, velocity, 1);
        sleepUntil(t0 + STEP.toNanos());
        long jumpSentAt = System.nanoTime();
        send(connection, InputKind.SYNC, target, velocity, JUMP_SEQ);

        Optional<Received> ackFrame = connection.await(mark, r -> r.messageId() == ids.notifyMoveAck(), observeTimeout);
        MoveAckS2C ack = ackFrame.isPresent() ? ackFrame.get().parse(MoveAckS2C.parser()) : null;
        JumpVerdict verdict = MoveAssertions.judgeJump(expectJump, ack, ackFrame.map(Received::requestId).orElse(0L),
                JUMP_SEQ, target, System.currentTimeMillis());
        String observedText;
        if (verdict.corrected()) {
            long latency = (ackFrame.get().receivedNanos() - jumpSentAt) / 1_000_000;
            observedText = "收到 137 纠偏（" + latency + " ms）：server_location=" + verdict.finalPosition()
                    + "，距起跳点 " + fmt(verdict.finalPosition().horizontalDistance(from)) + " m，距上报点 "
                    + fmt(verdict.finalPosition().horizontalDistance(target)) + " m，server_velocity="
                    + (ack.hasServerVelocity() ? Vec3.of(ack.getServerVelocity()).toString() : "缺席");
        } else {
            observedText = observeTimeout.toMillis() + " ms 内没收到 137：服务端 fail-open 原样接受了前跳 "
                    + fmt(JUMP_DISTANCE) + " m 的上报位置 " + target;
        }
        report.note("跳跃：MoveSync 上报位置前跳 " + fmt(JUMP_DISTANCE) + " m（250ms 内，合法上限约 "
                + fmt(MoveAssertions.MAX_TRUSTED_SPEED * STEP.toMillis() / 1000.0) + " m）。" + observedText);
        report.check(verdict.violations().isEmpty(), "超速跳跃的服务端裁决符合契约（期望 " + expectJump + "）",
                verdict.violations().isEmpty() ? observedText : String.join("；", verdict.violations()),
                "movement §4.3 第 2 步（无导航 fail-open）/ 第 5 步（偏差 > 0.5 m 回 137）、§3.5、§4.4、§9 第 6 条");

        // 客户端服从裁决：在服务端认定的位置停下，不应再被纠偏。
        int stopMark = connection.inbox().size();
        send(connection, InputKind.STOP, verdict.finalPosition(), Vec3.ZERO, JUMP_SEQ + 1);
        sleep(STOP_SETTLE);
        long reCorrected = connection.inbox().snapshot(stopMark).stream()
                .filter(r -> r.messageId() == ids.notifyMoveAck())
                .count();
        report.check(reCorrected == 0, "在裁决位置 MoveStop 后不再被纠偏",
                reCorrected == 0 ? "" : "又收到 " + reCorrected + " 条 137", "movement §4.3 第 5 步");
        long replies = connection.inbox().snapshot(mark).stream().filter(r -> ids.isMoveInput(r.messageId())).count();
        report.check(replies == 0, "跳跃：A 对 134/132/131 没有任何回包",
                replies == 0 ? "" : "收到 " + replies + " 条", "movement §0 第 1 条、§4.5（拒绝也不回）");

        reconnect(a, reentry.self(), verdict.finalPosition(), null,
                verdict.corrected() ? "137 的 server_location" : "fail-open 接受的跳跃位置", report, opened);
    }

    // ------------------------------------------------------------------ 发送与时间

    private void send(GameConnection connection, InputKind kind, Vec3 location, Vec3 velocity, int seq)
            throws RobotException {
        long clientTimeMs = System.nanoTime() / 1_000_000;
        Message body;
        int messageId;
        switch (kind) {
            case START -> {
                messageId = ids.moveStart();
                body = MoveStartC2S.newBuilder()
                        .setStartLocation(location.toLocation())
                        .setRotation(FACING.toRotation())
                        .setVelocity(velocity.toVelocity())
                        .setClientTimeMs(clientTimeMs)
                        .setInputSeq(seq)
                        .build();
            }
            case SYNC -> {
                messageId = ids.moveSync();
                body = MoveSyncC2S.newBuilder()
                        .setLocation(location.toLocation())
                        .setRotation(FACING.toRotation())
                        .setVelocity(velocity.toVelocity())
                        .setClientTimeMs(clientTimeMs)
                        .setInputSeq(seq)
                        .build();
            }
            default -> {
                messageId = ids.moveStop();
                body = MoveStopC2S.newBuilder()
                        .setEndLocation(location.toLocation())
                        .setRotation(FACING.toRotation())
                        .setClientTimeMs(clientTimeMs)
                        .setInputSeq(seq)
                        .build();
            }
        }
        connection.send(messageId, body);
    }

    private static void sleepUntil(long deadlineNanos) throws RobotException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            sleep(Duration.ofNanos(remaining));
        }
    }

    private static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }
}
