package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.ActorMoveListS2C;
import com.game.proto.MessageContent;
import com.game.proto.TeleportS2C;
import com.game.contract.MessageIdRegistry;
import com.game.robot.client.MessageIds;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
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
    private static final MessageIds IDS = MessageIds.resolve(MessageIdRegistry.loadFromClasspath());
    private static final long ENTITY = 901;
    private static final long PEER = 902;
    private static final Vec3 FACE = new Vec3(0, 0, 90);

    private static Transform transform(Vec3 at) {
        return Transform.newBuilder().setLocation(Vector3.newBuilder().setX(at.x()).setY(at.y()).setZ(at.z()))
                .setRotation(FACE.toRotation()).build();
    }

    private static ActorMoveS2C actorMove(Vec3 at, Vec3 velocity) {
        return ActorMoveS2C.newBuilder().setEntity(ENTITY).setTransform(transform(at)).setVelocity(velocity.toVelocity())
                .setMoveState(velocity.isZero() ? 0 : 1).setServerTimeMs(NOW_MS).build();
    }

    private static Received frame(int index, long ms, int id, Message message) {
        return new Received(index, ms * MS, MessageContent.newBuilder().setMessageId(id).setSerializedMessage(message.toByteString()).build());
    }

    private static MoveAssertions.ObsMove moving(int index, long ms, Vec3 at, Vec3 velocity) {
        return new MoveAssertions.ObsMove(index, ms * MS, 0, IDS.notifyActorMove(), actorMove(at, velocity));
    }

    private static TeleportS2C teleport(Vec3 target) {
        return TeleportS2C.newBuilder().setEntity(ENTITY).setTransform(transform(target)).setReason(3).build();
    }

    @Test
    void 移动下行解析_135展开保留顺序与信封_空表无条目() throws Exception {
        ActorMoveS2C first = actorMove(P0, new Vec3(4, 0, 0));
        ActorMoveS2C second = first.toBuilder().setEntity(PEER).build();
        List<MoveAssertions.ObsMove> seen = MoveAssertions.moves(List.of(
                frame(0, 10, IDS.notifyActorMove(), first),
                frame(1, 20, IDS.notifyActorMoveList(), ActorMoveListS2C.newBuilder().addMoves(first).addMoves(second).build()),
                frame(2, 30, IDS.notifyActorMoveList(), ActorMoveListS2C.getDefaultInstance()),
                frame(3, 40, IDS.notifyMoveAck(), MoveAckS2C.getDefaultInstance())), IDS);
        assertThat(seen).extracting(o -> o.move().getEntity()).containsExactly(ENTITY, ENTITY, PEER);
        assertThat(seen).extracting(MoveAssertions.ObsMove::index).containsExactly(0, 1, 1);
        assertThat(seen.getLast().receivedNanos()).isEqualTo(20 * MS);
        assertThat(seen).allSatisfy(o -> assertThat(o.envelopeId()).isZero());
    }

    @Test
    void 移动下行坏包不能被静默忽略() {
        for (int id : List.of(IDS.notifyActorMove(), IDS.notifyActorMoveList())) {
            Received broken = new Received(0, 0, MessageContent.newBuilder().setMessageId(id)
                    .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff})).build());
            assertThatThrownBy(() -> MoveAssertions.moves(List.of(broken), IDS)).isInstanceOf(RobotException.class);
        }
    }

    @Test
    void actor按实体号归属_不接受player_id替代() {
        assertThat(MoveAssertions.actorOwner(ENTITY, ENTITY, PEER)).isEqualTo(Owner.MOVER);
        assertThat(MoveAssertions.actorOwner(PEER, ENTITY, PEER)).isEqualTo(Owner.RECEIVER);
        assertThat(MoveAssertions.actorOwner(123, ENTITY, PEER)).isEqualTo(Owner.OTHER);
        assertThat(MoveAssertions.actorOwner(0, ENTITY, PEER)).isEqualTo(Owner.UNATTRIBUTED);
    }

    @Test
    void actor配对容忍同帧合并_保留实际输入用于逐位比较() {
        List<SentInput> inputs = List.of(input(1, InputKind.START, 0, 4, 0), input(2, InputKind.SYNC, 1, 5, 10),
                input(3, InputKind.STOP, 2, 0, 300));
        List<MoveAssertions.ActorInputMatch> matched = MoveAssertions.matchActorInputs(inputs,
                List.of(moving(0, 20, x(1), new Vec3(5, 0, 0)), moving(1, 350, x(2), Vec3.ZERO)));
        assertThat(matched).extracting(m -> m.effective().seq()).containsExactly(2, 2, 3);
        assertThat(matched).extracting(m -> m.observed().index()).containsExactly(0, 0, 1);
        assertThat(matched.getFirst().latencyMillis()).isEqualTo(20);
        assertThat(MoveAssertions.actorMoveProblems(matched.getFirst().observed(), ENTITY, matched.getFirst().effective(), FACE, NOW_MS))
                .isEmpty();
    }

    @Test
    void actor配对不借未来输入_字段缺席或旧观察不能匹配() {
        List<SentInput> inputs = List.of(input(1, InputKind.START, 0, 4, 100), input(2, InputKind.SYNC, 1, 5, 300));
        assertThat(MoveAssertions.matchActorInputs(inputs, List.of(moving(0, 200, x(1), new Vec3(5, 0, 0)))))
                .allSatisfy(m -> assertThat(m.observed()).isNull());
        assertThat(MoveAssertions.matchActorInputs(inputs, List.of(moving(0, 50, P0, new Vec3(4, 0, 0)))))
                .allSatisfy(m -> assertThat(m.observed()).isNull());
        MoveAssertions.ObsMove noVelocity = new MoveAssertions.ObsMove(1, 200 * MS, 0, IDS.notifyActorMove(),
                actorMove(P0, new Vec3(4, 0, 0)).toBuilder().clearVelocity().build());
        assertThat(MoveAssertions.matchActorInputs(inputs, List.of(noVelocity))).allSatisfy(m -> assertThat(m.observed()).isNull());
    }

    @Test
    void actor逐字段断言抓住实体位置朝向速度状态时间与信封错误() {
        SentInput input = input(1, InputKind.START, 0, 30, 0);
        ActorMoveS2C good = actorMove(P0, new Vec3(10, 0, 0));
        assertThat(MoveAssertions.actorMoveProblems(new MoveAssertions.ObsMove(0, MS, 0, IDS.notifyActorMove(), good),
                ENTITY, input, FACE, NOW_MS)).isEmpty();
        List<ActorMoveS2C> bad = List.of(good.toBuilder().setEntity(123).build(),
                good.toBuilder().setTransform(good.getTransform().toBuilder().clearLocation()).build(),
                good.toBuilder().setTransform(transform(new Vec3(Math.nextUp(P0.x()), P0.y(), P0.z()))).build(),
                good.toBuilder().setTransform(good.getTransform().toBuilder().clearRotation()).build(),
                good.toBuilder().clearVelocity().build(), good.toBuilder().setVelocity(new Vec3(30, 0, 0).toVelocity()).build(),
                good.toBuilder().setMoveState(0).build(), good.toBuilder().setServerTimeMs(0).build());
        for (ActorMoveS2C invalid : bad) {
            assertThat(MoveAssertions.actorMoveProblems(new MoveAssertions.ObsMove(0, MS, 0, IDS.notifyActorMove(), invalid),
                    ENTITY, input, FACE, NOW_MS)).isNotEmpty();
        }
        assertThat(MoveAssertions.actorMoveProblems(new MoveAssertions.ObsMove(0, MS, 7, IDS.notifyActorMove(), good),
                ENTITY, input, FACE, NOW_MS)).contains("133/135信封id非0");
    }

    @Test
    void actor停止要求速度消息存在与state0_逐位区分负零() {
        SentInput stop = input(2, InputKind.STOP, 0, 0, 0);
        MoveAssertions.ObsMove good = moving(0, 1, P0, Vec3.ZERO);
        assertThat(MoveAssertions.actorMoveProblems(good, ENTITY, stop, FACE, NOW_MS)).isEmpty();
        assertThat(MoveAssertions.sameBits(Vec3.ZERO, new Vec3(-0.0, 0, 0))).isFalse();
        assertThat(MoveAssertions.actorMoveProblems(new MoveAssertions.ObsMove(0, MS, 0, IDS.notifyActorMove(),
                good.move().toBuilder().setMoveState(1).build()), ENTITY, stop, FACE, NOW_MS)).contains("move_state应为0");
    }

    @Test
    void 瞬移字段必须有位置朝向_实体reason_inputseq和信封正确() throws Exception {
        TeleportS2C good = teleport(x(50));
        assertThat(MoveAssertions.teleportProblems(frame(0, 1, IDS.notifyTeleport(), good), ENTITY, x(50), FACE, 3)).isEmpty();
        for (TeleportS2C invalid : List.of(good.toBuilder().setEntity(123).build(), good.toBuilder().setReason(5).build(),
                good.toBuilder().setInputSeq(1).build(), good.toBuilder().clearTransform().build(),
                good.toBuilder().setTransform(good.getTransform().toBuilder().clearLocation()).build(),
                good.toBuilder().setTransform(good.getTransform().toBuilder().clearRotation()).build(),
                good.toBuilder().setTransform(transform(x(49))).build())) {
            assertThat(MoveAssertions.teleportProblems(frame(0, 1, IDS.notifyTeleport(), invalid), ENTITY, x(50), FACE, 3))
                    .isNotEmpty();
        }
        Received nonzero = frame(0, 1, IDS.notifyTeleport(), good);
        assertThat(MoveAssertions.teleportProblems(new Received(0, MS, nonzero.content().toBuilder().setId(7).build()),
                ENTITY, x(50), FACE, 3)).contains("130信封id非0");
    }

    private static List<Received> outgoingA() {
        return List.of(frame(0, 1, IDS.notifyTeleport(), teleport(x(50))),
                frame(1, 2, IDS.notifyActorListDestroy(), ActorListDestroyS2C.newBuilder().addEntity(PEER).build()));
    }

    private static List<Received> outgoingB() {
        return List.of(frame(0, 2, IDS.notifyActorListDestroy(), ActorListDestroyS2C.newBuilder().addEntity(ENTITY).build()));
    }

    @Test
    void 瞬移去程_先130再64差集_旁人只64_忽略66旁证() throws Exception {
        assertThat(MoveAssertions.teleportSequenceProblems(outgoingA(), outgoingB(), IDS, ENTITY, PEER, x(50), FACE, false)).isEmpty();
        List<Received> with66 = new java.util.ArrayList<>(outgoingA());
        with66.add(frame(2, 3, IDS.syncBaseAttribute(), ActorBaseAttributesS2C.getDefaultInstance()));
        assertThat(MoveAssertions.teleportSequenceProblems(with66, outgoingB(), IDS, ENTITY, PEER, x(50), FACE, false)).isEmpty();
    }

    @Test
    void 瞬移去程序列抓住缺包乱序自毁和重复130() throws Exception {
        List<List<Received>> invalid = List.of(List.of(outgoingA().getFirst()),
                List.of(frame(0, 1, IDS.notifyActorListDestroy(), ActorListDestroyS2C.newBuilder().addEntity(PEER).build()),
                        frame(1, 2, IDS.notifyTeleport(), teleport(x(50)))),
                List.of(outgoingA().getFirst(), frame(1, 2, IDS.notifyActorListDestroy(),
                        ActorListDestroyS2C.newBuilder().addEntity(ENTITY).addEntity(PEER).build())),
                List.of(outgoingA().getFirst(), outgoingA().getLast(), frame(2, 3, IDS.notifyTeleport(), teleport(x(50)))));
        for (List<Received> frames : invalid) {
            assertThat(MoveAssertions.teleportSequenceProblems(frames, outgoingB(), IDS, ENTITY, PEER, x(50), FACE, false)).isNotEmpty();
        }
        assertThat(MoveAssertions.teleportSequenceProblems(outgoingA(), List.of(), IDS, ENTITY, PEER, x(50), FACE, false)).isNotEmpty();
    }

    private static List<Received> returningA() {
        return List.of(frame(0, 1, IDS.notifyTeleport(), teleport(P0)), frame(1, 2, IDS.notifyActorListCreate(),
                ActorListCreateS2C.newBuilder().addActorList(ActorCreateS2C.newBuilder().setEntity(PEER).setTransform(transform(P0))).build()));
    }

    private static List<Received> returningB() {
        return List.of(frame(0, 2, IDS.notifyActorCreate(), ActorCreateS2C.newBuilder().setEntity(ENTITY).setTransform(transform(P0)).build()));
    }

    @Test
    void 瞬移回程_130后47_旁人21位置朝向正确且无移动条目() throws Exception {
        assertThat(MoveAssertions.teleportSequenceProblems(returningA(), returningB(), IDS, ENTITY, PEER, P0, FACE, true)).isEmpty();
        List<Received> wrong = List.of(frame(0, 2, IDS.notifyActorCreate(), ActorCreateS2C.newBuilder().setEntity(ENTITY)
                .setTransform(transform(x(1))).build()));
        assertThat(MoveAssertions.teleportSequenceProblems(returningA(), wrong, IDS, ENTITY, PEER, P0, FACE, true)).isNotEmpty();
    }

    @Test
    void 瞬移回程拒绝本人重建或自己的移动_旁人不得收130和静止135() throws Exception {
        for (Received invalid : List.of(frame(2, 3, IDS.notifyActorCreate(), ActorCreateS2C.newBuilder().setEntity(ENTITY).build()),
                frame(2, 3, IDS.notifyActorDestroy(), ActorDestroyS2C.newBuilder().setEntity(ENTITY).build()),
                frame(2, 3, IDS.notifyEnterScene(), EnterSceneS2C.getDefaultInstance()),
                frame(2, 3, IDS.notifyActorMove(), actorMove(P0, Vec3.ZERO)),
                frame(2, 3, IDS.notifyActorListCreate(), ActorListCreateS2C.newBuilder()
                        .addActorList(ActorCreateS2C.newBuilder().setEntity(ENTITY)).build()))) {
            List<Received> badA = new java.util.ArrayList<>(returningA());
            badA.add(invalid);
            assertThat(MoveAssertions.teleportSequenceProblems(badA, returningB(), IDS, ENTITY, PEER, P0, FACE, true)).isNotEmpty();
        }
        for (Received invalid : List.of(frame(1, 3, IDS.notifyTeleport(), teleport(P0)),
                frame(1, 3, IDS.notifyActorMoveList(), ActorMoveListS2C.newBuilder().addMoves(actorMove(P0, Vec3.ZERO)).build()))) {
            List<Received> badB = new java.util.ArrayList<>(returningB());
            badB.add(invalid);
            assertThat(MoveAssertions.teleportSequenceProblems(returningA(), badB, IDS, ENTITY, PEER, P0, FACE, true)).isNotEmpty();
        }
    }

    @Test
    void 挂机断言仅接受起步与约30秒9米处的停止() {
        MoveAssertions.ObsMove start = moving(0, 50, P0, new Vec3(0.3, 0, 0));
        MoveAssertions.ObsMove stop = moving(1, 30_050, x(8.985), Vec3.ZERO);
        assertThat(MoveAssertions.afkProblems(List.of(start, stop), ENTITY, P0, FACE, 0, 37_000, NOW_MS)).isEmpty();
        assertThat(MoveAssertions.afkProblems(List.of(start), ENTITY, P0, FACE, 0, 37_000, NOW_MS)).isNotEmpty();
        assertThat(MoveAssertions.afkProblems(List.of(start, moving(1, 1000, x(0.3), Vec3.ZERO)),
                ENTITY, P0, FACE, 0, 37_000, NOW_MS)).contains("AFK停止位置不在约9米处");
        assertThat(MoveAssertions.afkProblems(List.of(start, moving(1, 15_000, x(4.5), new Vec3(0.3, 0, 0)), stop),
                ENTITY, P0, FACE, 0, 37_000, NOW_MS)).contains("AFK的133应恰好两条，实际3");
    }

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
