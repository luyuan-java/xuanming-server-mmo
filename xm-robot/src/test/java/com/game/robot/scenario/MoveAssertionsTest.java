package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.MoveAckS2C;
import com.game.proto.Transform;
import com.game.proto.Vector3;
import com.game.robot.scenario.MoveAssertions.InputKind;
import com.game.robot.scenario.MoveAssertions.InputMatch;
import com.game.robot.scenario.MoveAssertions.JumpVerdict;
import com.game.robot.scenario.MoveAssertions.Obs66;
import com.game.robot.scenario.MoveAssertions.Owner;
import com.game.robot.scenario.MoveAssertions.SentInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveAssertionsTest {

    private static final long MS = 1_000_000L;
    private static final Vec3 P0 = new Vec3(180, 200, 0);
    private static final long NOW_MS = 1_760_000_000_000L;

    private static Vec3 x(double dx) {
        return P0.plus(new Vec3(dx, 0, 0));
    }

    private static SentInput input(int seq, InputKind kind, double dx, double speed, long sentMs) {
        return new SentInput(seq, kind, x(dx), new Vec3(speed, 0, 0), sentMs * MS);
    }

    private static Obs66 obs(int index, long recvMs, double dx, Double speed) {
        return new Obs66(index, recvMs * MS, 7, x(dx), new Vec3(0, 0, 90), speed == null ? null : new Vec3(speed, 0, 0));
    }

    // ------------------------------------------------------------------ 速度截断 / 归属

    @Test
    void 超过_10_米每秒等比缩放_方向不变() {
        assertThat(MoveAssertions.clampSpeed(new Vec3(30, 0, 0))).isEqualTo(new Vec3(10, 0, 0));
        Vec3 diagonal = MoveAssertions.clampSpeed(new Vec3(30, 40, 0));
        assertThat(diagonal.approx(new Vec3(6, 8, 0), 1e-12)).isTrue();
        assertThat(MoveAssertions.clampSpeed(new Vec3(4, 0, 3))).isEqualTo(new Vec3(4, 0, 3));
    }

    @Test
    void 按_entity_id_归属() {
        ActorBaseAttributesS2C none = ActorBaseAttributesS2C.getDefaultInstance();
        assertThat(MoveAssertions.ownerOf(none, 1, 2)).isEqualTo(Owner.UNATTRIBUTED);
        assertThat(MoveAssertions.ownerOf(none.toBuilder().setEntityId(1).build(), 1, 2)).isEqualTo(Owner.MOVER);
        assertThat(MoveAssertions.ownerOf(none.toBuilder().setEntityId(2).build(), 1, 2)).isEqualTo(Owner.RECEIVER);
        assertThat(MoveAssertions.ownerOf(none.toBuilder().setEntityId(3).build(), 1, 2)).isEqualTo(Owner.OTHER);
    }

    @Test
    void 观察记录保留字段存在性_停止是_velocity_存在且全零() {
        ActorBaseAttributesS2C stop = ActorBaseAttributesS2C.newBuilder()
                .setTransform(Transform.newBuilder().setLocation(Vector3.newBuilder().setX(184).setY(200)))
                .setVelocity(Vec3.ZERO.toVelocity())
                .build();
        Obs66 o = Obs66.of(3, 5, stop);
        assertThat(o.isStop()).isTrue();
        assertThat(o.location()).isEqualTo(new Vec3(184, 200, 0));
        assertThat(o.rotation()).isNull();

        Obs66 transformOnly = Obs66.of(4, 6, stop.toBuilder().clearVelocity().build());
        assertThat(transformOnly.velocity()).isNull();
        assertThat(transformOnly.isStop()).isFalse();
    }

    // ------------------------------------------------------------------ 输入与 66 对应

    @Test
    void 每条输入对上由它引起的第一条_66_时延按到达算() {
        List<SentInput> inputs = List.of(
                input(1, InputKind.START, 0, 4, 0),
                input(2, InputKind.SYNC, 1, 4.5, 250),
                input(3, InputKind.STOP, 2.125, 0, 500));
        List<Obs66> observed = List.of(
                obs(0, 40, 0.1, 4.0),     // Start 引起
                obs(1, 140, 0.5, null),   // 只有 transform（外推）
                obs(2, 290, 1.1, 4.5),    // Sync 引起
                obs(3, 540, 2.125, 0.0)); // Stop 引起
        List<InputMatch> matches = MoveAssertions.matchInputs(inputs, observed, 1e-4, 1e-6);
        assertThat(matches).extracting(m -> m.observed().index()).containsExactly(0, 2, 3);
        assertThat(matches).extracting(InputMatch::latencyMillis).containsExactly(40L, 40L, 40L);
    }

    @Test
    void 发出之前到的_66_不算() {
        List<SentInput> inputs = List.of(input(2, InputKind.SYNC, 1, 4.5, 250));
        List<Obs66> observed = List.of(obs(0, 200, 1, 4.5));
        assertThat(MoveAssertions.matchInputs(inputs, observed, 1e-4, 1e-6).get(0).observed()).isNull();
    }

    @Test
    void 超速输入期望看到截断后的速度() {
        List<SentInput> inputs = List.of(input(4, InputKind.SYNC, 3.375, 30, 0));
        assertThat(MoveAssertions.matchInputs(inputs, List.of(obs(0, 50, 3.4, 30.0)), 1e-4, 1e-6).get(0).observed())
                .as("30 m/s 原样广播是违约").isNull();
        assertThat(MoveAssertions.matchInputs(inputs, List.of(obs(0, 50, 3.4, 10.0)), 1e-4, 1e-6).get(0).observed())
                .isNotNull();
    }

    @Test
    void 两条输入落进同一同步周期时_前一条也由那条_66_满足() {
        List<SentInput> inputs = List.of(
                input(1, InputKind.START, 0, 4, 0),
                input(2, InputKind.SYNC, 1, 4.5, 30),
                input(3, InputKind.STOP, 2, 0, 400));
        // 4.0 的那条被 4.5 覆盖，只来了一条 velocity=4.5 的 66。
        List<Obs66> observed = List.of(obs(0, 60, 0.2, 4.5), obs(1, 430, 2, 0.0));
        List<InputMatch> matches = MoveAssertions.matchInputs(inputs, observed, 1e-4, 1e-6);
        assertThat(matches).extracting(m -> m.observed().index()).containsExactly(0, 0, 1);
    }

    @Test
    void 最后一条_Sync_与_Stop_合并时由停止的_66_满足() {
        List<SentInput> inputs = List.of(
                input(1, InputKind.SYNC, 0, 5, 0),
                input(2, InputKind.STOP, 1, 0, 20));
        List<Obs66> observed = List.of(obs(0, 60, 1, 0.0));
        assertThat(MoveAssertions.matchInputs(inputs, observed, 1e-4, 1e-6))
                .extracting(m -> m.observed().index()).containsExactly(0, 0);
    }

    @Test
    void 停止的_66_必须在停止点且速度全零() {
        List<SentInput> inputs = List.of(input(5, InputKind.STOP, 4.375, 0, 0));
        assertThat(MoveAssertions.matchInputs(inputs, List.of(obs(0, 10, 4.0, 0.0)), 1e-4, 1e-6).get(0).observed())
                .as("位置不对").isNull();
        assertThat(MoveAssertions.matchInputs(inputs, List.of(obs(0, 10, 4.375, null)), 1e-4, 1e-6).get(0).observed())
                .as("velocity 缺席（没表达「停了」）").isNull();
        assertThat(MoveAssertions.matchInputs(inputs, List.of(obs(0, 10, 4.375, 0.0)), 1e-4, 1e-6).get(0).observed())
                .isNotNull();
    }

    @Test
    void 没有对应的_66_时为空() {
        List<SentInput> inputs = List.of(input(1, InputKind.START, 0, 4, 0), input(2, InputKind.SYNC, 1, 4.5, 250));
        List<InputMatch> matches = MoveAssertions.matchInputs(inputs, List.of(obs(0, 40, 0, 4.0)), 1e-4, 1e-6);
        assertThat(matches.get(0).observed()).isNotNull();
        assertThat(matches.get(1).observed()).isNull();
        assertThat(matches.get(1).latencyMillis()).isEqualTo(-1);
    }

    // ------------------------------------------------------------------ 路径

    @Test
    void 路径判定_沿向余量与侧向容差() {
        Vec3 end = x(4.375);
        assertThat(MoveAssertions.offPath(x(2), P0, end, 0.5, 5)).isEmpty();
        assertThat(MoveAssertions.offPath(x(9), P0, end, 0.5, 5)).as("外推余量内").isEmpty();
        assertThat(MoveAssertions.offPath(x(-0.4), P0, end, 0.5, 5)).isEmpty();
        assertThat(MoveAssertions.offPath(x(-1), P0, end, 0.5, 5)).get().asString().contains("沿路径");
        assertThat(MoveAssertions.offPath(x(9.5), P0, end, 0.5, 5)).isPresent();
        assertThat(MoveAssertions.offPath(new Vec3(182, 201, 0), P0, end, 0.5, 5)).get().asString().contains("偏离路径");
        assertThat(MoveAssertions.offPath(new Vec3(182, 200, 0.6), P0, end, 0.5, 5)).isPresent();
        assertThat(MoveAssertions.offPath(P0, P0, P0, 0.5, 5)).isEmpty();
    }

    // ------------------------------------------------------------------ 跳跃裁决

    private static final Vec3 REPORTED = x(200);

    private static MoveAckS2C ack(int seq, Vec3 server, long timeMs) {
        return MoveAckS2C.newBuilder().setInputSeq(seq).setServerLocation(server.toLocation())
                .setServerVelocity(new Vec3(4, 0, 0).toVelocity()).setServerTimeMs(timeMs).build();
    }

    @Test
    void 没有_137_即_fail_open_落点是上报位置() {
        JumpVerdict auto = MoveAssertions.judgeJump(ExpectJump.AUTO, null, 0, 2, REPORTED, NOW_MS);
        assertThat(auto.corrected()).isFalse();
        assertThat(auto.finalPosition()).isEqualTo(REPORTED);
        assertThat(auto.violations()).isEmpty();
        assertThat(MoveAssertions.judgeJump(ExpectJump.ACCEPT, null, 0, 2, REPORTED, NOW_MS).violations()).isEmpty();
        assertThat(MoveAssertions.judgeJump(ExpectJump.CORRECT, null, 0, 2, REPORTED, NOW_MS).violations())
                .singleElement().asString().contains("期望纠偏");
    }

    @Test
    void 合规的_137_落点是_server_location() {
        Vec3 server = x(24);
        JumpVerdict verdict = MoveAssertions.judgeJump(ExpectJump.AUTO, ack(2, server, NOW_MS + 5), 0, 2, REPORTED, NOW_MS);
        assertThat(verdict.corrected()).isTrue();
        assertThat(verdict.finalPosition()).isEqualTo(server);
        assertThat(verdict.violations()).isEmpty();
        assertThat(MoveAssertions.judgeJump(ExpectJump.CORRECT, ack(2, server, NOW_MS), 0, 2, REPORTED, NOW_MS).violations())
                .isEmpty();
        assertThat(MoveAssertions.judgeJump(ExpectJump.ACCEPT, ack(2, server, NOW_MS), 0, 2, REPORTED, NOW_MS).violations())
                .singleElement().asString().contains("fail-open");
    }

    @Test
    void 不合规的_137_逐条列出() {
        JumpVerdict verdict = MoveAssertions.judgeJump(ExpectJump.AUTO, ack(9, x(199.8), 12345), 5, 2, REPORTED, NOW_MS);
        assertThat(verdict.violations()).hasSize(4)
                .anySatisfy(v -> assertThat(v).contains("信封 id"))
                .anySatisfy(v -> assertThat(v).contains("input_seq"))
                .anySatisfy(v -> assertThat(v).contains("0.5"))
                .anySatisfy(v -> assertThat(v).contains("UTC"));
    }

    @Test
    void 缺_server_location_或非有限值() {
        MoveAckS2C missing = MoveAckS2C.newBuilder().setInputSeq(2).setServerTimeMs(NOW_MS).build();
        assertThat(MoveAssertions.judgeJump(ExpectJump.AUTO, missing, 0, 2, REPORTED, NOW_MS).violations())
                .singleElement().asString().contains("缺 server_location");
        MoveAckS2C nan = ack(2, new Vec3(Double.NaN, 0, 0), NOW_MS);
        assertThat(MoveAssertions.judgeJump(ExpectJump.AUTO, nan, 0, 2, REPORTED, NOW_MS).violations())
                .singleElement().asString().contains("非有限");
    }
}
