package com.game.robot.scenario;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.MoveAckS2C;
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

        /** 观察者应在 66 里看到的速度：STOP 为全零，其余为截断到 10 m/s 的上报速度。 */
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

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
