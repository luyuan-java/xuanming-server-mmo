package com.game.robot.scenario;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveListS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.MoveAckS2C;
import com.game.proto.TeleportS2C;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 移动场景的断言辅助：纯函数，不碰网络，便于单测。常量都是契约里的数，注释写明出处。
 */
public final class MoveAssertions {

    /** 客户端速度信任上限（米/秒）：三维模长超过即等比缩放到它（movement 契约 §4.3「速度处理」，基线 kMaxTrustedClientSpeed）。 */
    public static final double MAX_TRUSTED_SPEED = 10.0;
    /** 纠偏阈值（米）：裁决位置与上报位置<b>水平</b>距离严格大于它才回 137（movement 契约 §4.3 第 5 步）。 */
    public static final double CORRECTION_EPSILON = 0.5;
    /** 137 的 server_time_ms 与本机墙钟允许的偏差：只防「填了单调时钟 / 秒 / 0」这类口径错误，不做时钟同步校验。 */
    static final long SERVER_TIME_TOLERANCE_MS = 10 * 60 * 1000L;

    private MoveAssertions() {
    }

    /** 移动输入的种类。 */
    public enum InputKind {
        START("MoveStart"), SYNC("MoveSync"), STOP("MoveStop");

        private final String method;

        InputKind(String method) {
            this.method = method;
        }

        public String method() {
            return method;
        }
    }

    /**
     * 已发出的一条移动输入。
     *
     * @param sentVelocity 上报的速度（STOP 不带速度，为 {@link Vec3#ZERO}）
     * @param sentNanos    发出时刻（{@link System#nanoTime()}）
     */
    public record SentInput(int seq, InputKind kind, Vec3 location, Vec3 sentVelocity, long sentNanos) {

        /** 观察者在133或66里应看到的速度：STOP全零，其余为截断到10m/s的上报速度。 */
        public Vec3 expectedVelocity() {
            return kind == InputKind.STOP ? Vec3.ZERO : clampSpeed(sentVelocity);
        }
    }

    /**
     * 观察者收到的一条 66（字段缺席用 null 表示——66 只带脏字段，存在性本身就是语义，AOI 契约 §5.1）。
     */
    public record Obs66(int index, long receivedNanos, long entityId, Vec3 location, Vec3 rotation, Vec3 velocity) {

        public static Obs66 of(int index, long receivedNanos, ActorBaseAttributesS2C m) {
            Vec3 location = m.hasTransform() && m.getTransform().hasLocation() ? Vec3.of(m.getTransform().getLocation()) : null;
            Vec3 rotation = m.hasTransform() && m.getTransform().hasRotation() ? Vec3.of(m.getTransform().getRotation()) : null;
            Vec3 velocity = m.hasVelocity() ? Vec3.of(m.getVelocity()) : null;
            return new Obs66(index, receivedNanos, m.getEntityId(), location, rotation, velocity);
        }

        /** 「停了」：velocity 字段存在且全零（线上 {@code 1a 00}，movement 契约 §3.7）。 */
        public boolean isStop() {
            return velocity != null && velocity.isZero();
        }
    }

    /** 一条输入与观察者收到的、由它引起的第一条 66；没对上时 {@code observed} 为 null。 */
    public record InputMatch(SentInput input, Obs66 observed) {

        public long latencyMillis() {
            return observed == null ? -1 : (observed.receivedNanos() - input.sentNanos()) / 1_000_000;
        }
    }

    /** 一条 66 属于谁（按 {@code entity_id}，guid 口径 = player_id，AOI 契约 §3.5 / §5.1）。 */
    public enum Owner {
        /** entity_id = 移动者。 */
        MOVER,
        /** entity_id = 收件人自己（契约：66 不发给自己）。 */
        RECEIVER,
        /** 别的实体。 */
        OTHER,
        /** 没带 entity_id：基线移动触发的 66 就是这样（movement 契约 §0 第 4 条），无法归属。 */
        UNATTRIBUTED
    }

    public static Owner ownerOf(ActorBaseAttributesS2C m, long moverId, long receiverId) {
        long entity = m.getEntityId();
        if (entity == 0) {
            return Owner.UNATTRIBUTED;
        }
        if (entity == moverId) {
            return Owner.MOVER;
        }
        return entity == receiverId ? Owner.RECEIVER : Owner.OTHER;
    }

    /** 三维模长超过 {@link #MAX_TRUSTED_SPEED} 时等比缩放到它，方向不变（movement 契约 §4.3）。 */
    public static Vec3 clampSpeed(Vec3 velocity) {
        double speed = velocity.length();
        return speed <= MAX_TRUSTED_SPEED ? velocity : velocity.scaled(MAX_TRUSTED_SPEED / speed);
    }

    /**
     * 把每条输入对上观察者收到的、由它引起的第一条 66（按到达顺序贪心）。
     *
     * <p>契约（movement §6.1）：每条 MoveStart / MoveSync 都置 Velocity 脏位，其后第一条 66 带 velocity；
     * MoveStop 之后发一条带 transform + 全零 velocity 的 66。66 每 100ms 至多一条，所以两条输入若落进同一个同步周期，
     * 前一条的速度会被后一条覆盖——此时前一条也算由那条 66 满足（「合并」），时延量到那条 66。
     *
     * <p>前提：各条 START / SYNC 的期望速度两两不同且非零（场景的移动计划保证），否则无法区分哪条 66 由哪条输入引起。
     * 每条 66 必须带 transform.location（输入都置 Transform 脏位）。
     */
    public static List<InputMatch> matchInputs(List<SentInput> inputs, List<Obs66> observed,
                                               double locationEps, double velocityEps) {
        List<InputMatch> matches = new ArrayList<>(inputs.size());
        int cursor = 0;
        for (int i = 0; i < inputs.size(); i++) {
            SentInput input = inputs.get(i);
            Obs66 found = null;
            for (int j = cursor; j < observed.size(); j++) {
                Obs66 obs = observed.get(j);
                if (obs.receivedNanos() < input.sentNanos()) {
                    continue;
                }
                if (satisfies(inputs, i, obs, locationEps, velocityEps)) {
                    found = obs;
                    // 不跳过这条：合并时后面的输入也由它满足。
                    cursor = j;
                    break;
                }
            }
            matches.add(new InputMatch(input, found));
        }
        return matches;
    }

    private static boolean satisfies(List<SentInput> inputs, int i, Obs66 obs, double locationEps, double velocityEps) {
        if (obs.location() == null || obs.velocity() == null) {
            return false;
        }
        SentInput input = inputs.get(i);
        if (input.kind() == InputKind.STOP) {
            return obs.isStop() && obs.location().approx(input.location(), locationEps);
        }
        if (obs.velocity().approx(input.expectedVelocity(), velocityEps)) {
            return true;
        }
        for (int k = i + 1; k < inputs.size(); k++) {
            SentInput later = inputs.get(k);
            if (later.sentNanos() > obs.receivedNanos()) {
                break;
            }
            boolean sameVelocity = obs.velocity().approx(later.expectedVelocity(), velocityEps);
            if (sameVelocity && (later.kind() != InputKind.STOP || obs.location().approx(later.location(), locationEps))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 位置是否在「起点 → 终点」这条直线路径上：沿路径方向的投影在 {@code [-lateralTolerance, 长度 + overshoot]} 内，
     * 且离直线的垂直距离不超过 {@code lateralTolerance}。{@code overshoot} 给服务器外推（每 50ms 按速度前推，
     * 下一条输入才拉回）留余量（movement 契约 §5）。
     *
     * @return 偏离时的说明；在路径上为空
     */
    public static Optional<String> offPath(Vec3 point, Vec3 start, Vec3 end, double lateralTolerance, double overshoot) {
        Vec3 direction = end.minus(start);
        double length = direction.length();
        if (length == 0) {
            double d = point.distance(start);
            return d <= lateralTolerance + overshoot ? Optional.empty()
                    : Optional.of("距起点 " + fmt(d) + " m，超出 " + fmt(lateralTolerance + overshoot) + " m");
        }
        Vec3 unit = direction.scaled(1 / length);
        double along = point.minus(start).dot(unit);
        double lateral = point.minus(start.plus(unit.scaled(along))).length();
        if (along < -lateralTolerance || along > length + overshoot) {
            return Optional.of("沿路径位置 " + fmt(along) + " m 超出 [" + fmt(-lateralTolerance) + ", "
                    + fmt(length + overshoot) + "]");
        }
        if (lateral > lateralTolerance) {
            return Optional.of("偏离路径 " + fmt(lateral) + " m（容差 " + fmt(lateralTolerance) + " m）");
        }
        return Optional.empty();
    }

    /**
     * 跳跃裁决。
     *
     * @param corrected     是否收到了 137（纠偏）
     * @param finalPosition 服务端裁决后的位置：纠偏时是 137 的 server_location，fail-open 时是上报位置。
     *                      客户端据此发 MoveStop，随后重登核对落盘位置
     * @param violations    与契约或期望不符之处；为空即符合
     */
    public record JumpVerdict(boolean corrected, Vec3 finalPosition, List<String> violations) {
    }

    /**
     * 判定服务端对一次超速跳跃（MoveSync 上报位置远超 10 m/s × dt）的处理是否符合契约。
     *
     * @param ack           收到的 137（input_seq 与本条相同或第一条 137）；没收到为 null
     * @param ackEnvelopeId 137 信封的 {@code MessageContent.id}（推送应为 0）
     * @param sentSeq       跳跃那条 MoveSync 的 input_seq
     * @param reported      跳跃上报的位置
     * @param wallNowMillis 本机 UTC 毫秒（校验 server_time_ms 的口径）
     */
    public static JumpVerdict judgeJump(ExpectJump expect, MoveAckS2C ack, long ackEnvelopeId, int sentSeq,
                                        Vec3 reported, long wallNowMillis) {
        List<String> violations = new ArrayList<>();
        if (ack == null) {
            if (expect == ExpectJump.CORRECT) {
                violations.add("期望纠偏，但没收到 137 MoveAck（服务端 fail-open 原样接受了跳跃）");
            }
            return new JumpVerdict(false, reported, violations);
        }
        if (expect == ExpectJump.ACCEPT) {
            violations.add("期望 fail-open（无导航网格原样接受、永不回 137），却收到 137");
        }
        if (ackEnvelopeId != 0) {
            violations.add("137 是推送，信封 id 应为 0，实际 " + ackEnvelopeId);
        }
        if (ack.getInputSeq() != sentSeq) {
            violations.add("137 的 input_seq 应回显 " + sentSeq + "，实际 " + ack.getInputSeq());
        }
        if (!ack.hasServerLocation()) {
            violations.add("137 缺 server_location");
            return new JumpVerdict(true, reported, violations);
        }
        Vec3 server = Vec3.of(ack.getServerLocation());
        if (!server.isFinite()) {
            violations.add("137 的 server_location 含非有限值 " + server);
            return new JumpVerdict(true, reported, violations);
        }
        double deviation = server.horizontalDistance(reported);
        if (deviation <= CORRECTION_EPSILON) {
            violations.add("137 只应在裁决位置与上报位置水平偏差 > " + CORRECTION_EPSILON + " m 时发，实际偏差 " + fmt(deviation) + " m");
        }
        long skew = Math.abs(ack.getServerTimeMs() - wallNowMillis);
        if (skew > SERVER_TIME_TOLERANCE_MS) {
            violations.add("137 的 server_time_ms=" + ack.getServerTimeMs() + " 与本机 UTC 毫秒相差 " + skew
                    + " ms，不像 UTC Unix 毫秒");
        }
        return new JumpVerdict(true, server, violations);
    }

    /** 133与135条目的统一视图；135的多个条目保留同一信封到达序号。 */
    public record ObsMove(int index, long receivedNanos, long envelopeId, int messageId, ActorMoveS2C move) {
        public Obs66 asMotion() {
            return new Obs66(index, receivedNanos, move.getEntity(),
                    move.hasTransform() && move.getTransform().hasLocation() ? Vec3.of(move.getTransform().getLocation()) : null,
                    move.hasTransform() && move.getTransform().hasRotation() ? Vec3.of(move.getTransform().getRotation()) : null,
                    move.hasVelocity() ? Vec3.of(move.getVelocity()) : null);
        }
    }

    /** 实体号口径，不能拿player_id匹配133/135/130。 */
    public static Owner actorOwner(long entity, long moverEntity, long receiverEntity) {
        if (entity == 0) return Owner.UNATTRIBUTED;
        if (entity == moverEntity) return Owner.MOVER;
        return entity == receiverEntity ? Owner.RECEIVER : Owner.OTHER;
    }

    /** 只解析移动下行；坏包显式报错，不静默丢弃造成断言假通过。 */
    public static List<ObsMove> moves(List<Received> frames, MessageIds ids) throws RobotException {
        List<ObsMove> result = new ArrayList<>();
        for (Received frame : frames) {
            if (frame.messageId() == ids.notifyActorMove()) {
                result.add(new ObsMove(frame.index(), frame.receivedNanos(), frame.requestId(), frame.messageId(),
                        frame.parse(ActorMoveS2C.parser())));
            } else if (frame.messageId() == ids.notifyActorMoveList()) {
                for (ActorMoveS2C move : frame.parse(ActorMoveListS2C.parser()).getMovesList()) {
                    result.add(new ObsMove(frame.index(), frame.receivedNanos(), frame.requestId(), frame.messageId(), move));
                }
            }
        }
        return List.copyOf(result);
    }

    /** effective为合并后实际保留下来的输入；逐位位置断言只对它进行。 */
    public record ActorInputMatch(SentInput input, SentInput effective, ObsMove observed) {
        public long latencyMillis() {
            return observed == null ? -1 : (observed.receivedNanos() - input.sentNanos()) / 1_000_000;
        }
    }

    /** 复用66的到达顺序与合并容忍规则，额外保留最终输入以验证133精确位置。 */
    public static List<ActorInputMatch> matchActorInputs(List<SentInput> inputs, List<ObsMove> observed) {
        List<InputMatch> matched = matchInputs(inputs, observed.stream().map(ObsMove::asMotion).toList(), 1e-4, 1e-6);
        List<ActorInputMatch> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Obs66 motion = matched.get(i).observed();
            ObsMove found = motion == null ? null : observed.stream().filter(o -> o.asMotion().equals(motion)).findFirst().orElseThrow();
            SentInput effective = null;
            if (found != null) {
                for (int k = i; k < inputs.size(); k++) {
                    SentInput candidate = inputs.get(k);
                    if (candidate.sentNanos() > found.receivedNanos()) break;
                    if (motion.velocity().approx(candidate.expectedVelocity(), 1e-6)
                            && (candidate.kind() != InputKind.STOP || motion.location().approx(candidate.location(), 1e-4))) {
                        effective = candidate;
                        break;
                    }
                }
            }
            result.add(new ActorInputMatch(inputs.get(i), effective, found));
        }
        return List.copyOf(result);
    }

    /** 原始double位比较，包含-0，避免容差掩盖裁决位置被错误外推。 */
    public static boolean sameBits(Vec3 a, Vec3 b) {
        return a != null && b != null && Double.doubleToRawLongBits(a.x()) == Double.doubleToRawLongBits(b.x())
                && Double.doubleToRawLongBits(a.y()) == Double.doubleToRawLongBits(b.y())
                && Double.doubleToRawLongBits(a.z()) == Double.doubleToRawLongBits(b.z());
    }

    /** 验证133完整字段、实体口径、发送墙钟、状态位和推送信封。 */
    public static List<String> actorMoveProblems(ObsMove observed, long entity, SentInput effective, Vec3 facing, long wallNow) {
        List<String> problems = new ArrayList<>();
        ActorMoveS2C move = observed.move();
        Obs66 motion = observed.asMotion();
        if (observed.envelopeId() != 0) problems.add("133/135信封id非0");
        if (move.getEntity() != entity) problems.add("entity不是场景实体号" + entity);
        if (!sameBits(motion.location(), effective.location())) problems.add("location缺席或不等于裁决输入的原始位");
        if (!sameBits(motion.rotation(), facing)) problems.add("rotation缺席或不等于FACING");
        if (!sameBits(motion.velocity(), effective.expectedVelocity())) problems.add("velocity缺席或不等于截断后的速度");
        int state = effective.expectedVelocity().isZero() ? 0 : 1;
        if (move.getMoveState() != state) problems.add("move_state应为" + state);
        if (move.getServerTimeMs() < wallNow - SERVER_TIME_TOLERANCE_MS
                || move.getServerTimeMs() > wallNow + SERVER_TIME_TOLERANCE_MS) problems.add("server_time_ms不是当前UTC毫秒");
        return List.copyOf(problems);
    }

    public static List<String> teleportProblems(Received frame, long entity, Vec3 target, Vec3 facing, int reason)
            throws RobotException {
        TeleportS2C teleport = frame.parse(TeleportS2C.parser());
        List<String> problems = new ArrayList<>();
        if (frame.requestId() != 0) problems.add("130信封id非0");
        if (teleport.getEntity() != entity) problems.add("130 entity不是场景实体号" + entity);
        if (!teleport.hasTransform() || !teleport.getTransform().hasLocation()
                || !sameBits(Vec3.of(teleport.getTransform().getLocation()), target)) problems.add("130 location缺席或原始位不等于目标");
        if (!teleport.getTransform().hasRotation() || !sameBits(Vec3.of(teleport.getTransform().getRotation()), facing)) {
            problems.add("130 rotation缺席或不等于FACING");
        }
        if (teleport.getReason() != reason) problems.add("130 reason应为" + reason);
        if (teleport.getInputSeq() != 0) problems.add("130 input_seq应为0");
        return List.copyOf(problems);
    }

    /** 两人单节点瞬移序列：本人130→64/47，旁人去程64、回程21；66不参与此序列。 */
    public static List<String> teleportSequenceProblems(List<Received> atMover, List<Received> atPeer, MessageIds ids,
                                                        long mover, long peer, Vec3 target, Vec3 facing, boolean returning)
            throws RobotException {
        List<String> problems = new ArrayList<>();
        List<Received> teleports = atMover.stream().filter(r -> r.messageId() == ids.notifyTeleport()).toList();
        if (teleports.size() != 1) problems.add("本人130应恰好一条，实际" + teleports.size());
        int teleportIndex = teleports.isEmpty() ? Integer.MAX_VALUE : teleports.getFirst().index();
        if (!teleports.isEmpty()) problems.addAll(teleportProblems(teleports.getFirst(), mover, target, facing, 3));
        int ownChangeCount = 0;
        for (Received frame : atMover) {
            if (frame.messageId() == ids.notifyEnterScene()) problems.add("同场景瞬移不应收到79清场");
            if (frame.messageId() == ids.notifyActorDestroy()
                    && frame.parse(ActorDestroyS2C.parser()).getEntity() == mover) problems.add("本人51包含自己");
            if (frame.messageId() == ids.notifyActorListDestroy()) {
                List<Long> entities = frame.parse(ActorListDestroyS2C.parser()).getEntityList();
                if (entities.contains(mover)) problems.add("本人64包含自己");
                if (frame.requestId() != 0) problems.add("本人64信封id非0");
                if (entities.contains(peer)) {
                    ownChangeCount++;
                    if (returning || frame.index() <= teleportIndex) problems.add("本人64顺序或方向错误");
                }
            } else if (frame.messageId() == ids.notifyActorListCreate()) {
                for (ActorCreateS2C actor : frame.parse(ActorListCreateS2C.parser()).getActorListList()) {
                    if (actor.getEntity() == mover) problems.add("本人47包含自己");
                    if (actor.getEntity() == peer) {
                        ownChangeCount++;
                        if (!returning || frame.index() <= teleportIndex) problems.add("本人47顺序或方向错误");
                    }
                }
                if (frame.requestId() != 0) problems.add("本人47信封id非0");
            } else if (frame.messageId() == ids.notifyActorCreate()
                    && frame.parse(ActorCreateS2C.parser()).getEntity() == mover) problems.add("瞬移重发本人21");
        }
        if (ownChangeCount != 1) problems.add("本人对旁人的64/47应恰好一次，实际" + ownChangeCount);
        int peerChanges = 0;
        for (Received frame : atPeer) {
            if (frame.messageId() == ids.notifyActorListDestroy()
                    && frame.parse(ActorListDestroyS2C.parser()).getEntityList().contains(mover)) {
                peerChanges++;
                if (returning || frame.requestId() != 0) problems.add("旁人64方向或信封错误");
            } else if (frame.messageId() == ids.notifyActorCreate()) {
                ActorCreateS2C actor = frame.parse(ActorCreateS2C.parser());
                if (actor.getEntity() != mover) continue;
                peerChanges++;
                if (!returning || frame.requestId() != 0 || !actor.hasTransform() || !actor.getTransform().hasLocation()
                        || !sameBits(Vec3.of(actor.getTransform().getLocation()), target)
                        || !actor.getTransform().hasRotation() || !sameBits(Vec3.of(actor.getTransform().getRotation()), facing)) {
                    problems.add("旁人21方向、信封、位置或朝向错误");
                }
            } else if (frame.messageId() == ids.notifyTeleport()) problems.add("旁人不应收到130");
        }
        if (peerChanges != 1) problems.add("旁人64/21应恰好一次，实际" + peerChanges);
        if (moves(atMover, ids).stream().anyMatch(o -> o.move().getEntity() == mover)) problems.add("本人收到自己的133/135");
        if (moves(atPeer, ids).stream().anyMatch(o -> o.move().getEntity() == mover)) problems.add("静止瞬移目标不应补133/135");
        return List.copyOf(problems);
    }

    /** AFK必须仅有起步与服务器停步两条，不能被客户端1秒外推上限或中途离场替代。 */
    public static List<String> afkProblems(List<ObsMove> observed, long entity, Vec3 start, Vec3 facing,
                                          long sentAt, long maxWaitMillis, long wallNow) {
        List<String> problems = new ArrayList<>();
        if (observed.size() != 2) {
            problems.add("AFK的133应恰好两条，实际" + observed.size());
            return problems;
        }
        ObsMove begun = observed.getFirst();
        ObsMove stop = observed.getLast();
        problems.addAll(actorMoveProblems(begun, entity, new SentInput(41, InputKind.START, start,
                new Vec3(0.3, 0, 0), sentAt), facing, wallNow));
        Vec3 stopped = stop.asMotion().location();
        problems.addAll(actorMoveProblems(stop, entity, new SentInput(41, InputKind.STOP, stopped,
                Vec3.ZERO, sentAt), facing, wallNow));
        long delay = (stop.receivedNanos() - sentAt) / 1_000_000;
        if (delay < 29_000 || delay > maxWaitMillis) problems.add("AFK停步时刻不在约30秒窗口，实际" + delay + "ms");
        if (stopped == null || !stopped.approx(start.plus(new Vec3(9, 0, 0)), 0.1)) problems.add("AFK停止位置不在约9米处");
        if (stop.index() <= begun.index()) problems.add("AFK停步早于起步");
        return List.copyOf(problems);
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
