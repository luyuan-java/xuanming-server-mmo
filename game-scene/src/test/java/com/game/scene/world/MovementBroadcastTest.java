package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.leave;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveListS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.proto.Rotation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 规格 §11.2：真实移动派发 → 帧开头广播 → 视野补发；每条断言都按收件人与实体号验证。 */
class MovementBroadcastTest extends MovementFixture {

    @Test
    void T1_帧开头发送裁决值_完整字段_发送时刻_只给旁人_停步字段恒在() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        long actor = ActorCreateS2C.parseFrom(sink.to(LINK, 11).get(1).getSerializedMessage()).getEntity();
        sink.clear();
        Vec3 reported = new Vec3(181, 200, 0);
        moveStart(11, 1001, reported, FACING, new Vec3(30, 0, 0), 1);
        assertThat(sink.events()).isEmpty();
        assertThat(a.moveBroadcastPending()).isTrue();
        clock.advanceMillis(30);
        world.step();
        MessageContent frame = frames(12, 133).getFirst();
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(frame.getId()).isZero();
        ActorMoveS2C move = parse(frame);
        assertThat(move.getEntity()).isEqualTo(actor).isNotEqualTo(a.playerId());
        assertThat(move.hasTransform()).isTrue();
        assertThat(move.getTransform().hasLocation()).isTrue();
        assertThat(move.getTransform().getLocation()).isEqualTo(reported.toProto());
        assertThat(move.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(move.hasVelocity()).isTrue();
        assertThat(move.getVelocity()).isEqualTo(new Vec3(10, 0, 0).toVelocity());
        assertThat(move.getMoveState()).isEqualTo(1);
        assertThat(move.getServerTimeMs()).isEqualTo(clock.epochMillis());
        assertThat(frames(11, 133)).isEmpty();
        assertThat(a.moveBroadcastPending()).isFalse();
        assertThat(deliveries("broadcast")).isEqualTo(1);
        assertThat(a.position()).isEqualTo(new Vec3(181.5, 200, 0));

        sink.clear();
        moveStop(11, 1001, a.position(), FACING, 2);
        world.step();
        ActorMoveS2C stopped = parse(frames(12, 133).getFirst());
        assertThat(stopped.hasVelocity()).isTrue();
        assertThat(stopped.getVelocity().getSerializedSize()).isZero();
        assertThat(stopped.getMoveState()).isZero();
        assertThat(java.util.HexFormat.of().formatHex(stopped.toByteArray())).contains("1a00");
        assertThat(stopped.getAllFields().keySet()).noneMatch(f -> f.getName().equals("move_state"));
    }

    @Test
    void T2_纠偏先给本人137_旁人133使用截断点() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();
        moveSync(11, 1001, new Vec3(230, 200, 0), FACING, Vec3.ORIGIN, 7);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(137);
        assertThat(sink.to(LINK, 12)).isEmpty();
        Vec3 clipped = a.position();
        assertThat(clipped).isEqualTo(new Vec3(204, 200, 0));
        assertThat(MoveAckS2C.parseFrom(sink.to(LINK, 11).getFirst().getSerializedMessage()).getServerLocation())
                .isEqualTo(clipped.toLocation());
        world.step();
        assertThat(parse(frames(12, 133).getFirst()).getTransform().getLocation()).isEqualTo(clipped.toProto());
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(133, 64);
    }

    @Test
    void T3_同帧三条合并_跨帧各一条_T4_外推不广播() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        moveSync(11, 1001, new Vec3(181, 200, 0), FACING, new Vec3(2, 0, 0), 2);
        moveStop(11, 1001, new Vec3(182, 200, 0), FACING, 3);
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(parse(frames(12, 133).getFirst()).getTransform().getLocation()).isEqualTo(a.position().toProto());
        assertThat(parse(frames(12, 133).getFirst()).getMoveState()).isZero();
        for (int n = 0; n < 3; n++) {
            moveSync(11, 1001, a.position(), FACING, new Vec3(1, 0, 0), n + 4);
            world.step();
        }
        assertThat(frames(12, 133)).hasSize(4);
        sink.clear();
        double before = deliveries("broadcast");
        moveStart(11, 1001, a.position(), FACING, new Vec3(1, 0, 0), 9);
        steps(10);
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(frames(12, 66)).hasSize(5);
        assertThat(deliveries("broadcast") - before).isEqualTo(1);
    }

    @Test
    void T5_非法与冻结输入不挂待发不广播() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();
        moveSync(11, 1001, new Vec3(Double.NaN, 200, 0), FACING, Vec3.ORIGIN, 1);
        moveSync(11, 1001, new Vec3(1e8, 200, 0), FACING, Vec3.ORIGIN, 2);
        WorldTestAccess.startFreezing(a);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 3);
        moveStop(11, 1001, SPAWN, FACING, 4);
        assertThat(a.moveBroadcastPending()).isFalse();
        steps(3);
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void T6_挂机当帧停步_输入待发被停步吃掉_静止待发仍兑现() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(0.1, 0, 0), 1);
        sink.clear();
        steps(MovementRules.AFK_FRAMES);
        assertThat(frames(12, 133)).hasSize(1);
        world.step();
        assertThat(frames(12, 133)).hasSize(2);
        assertThat(parse(frames(12, 133).getLast()).getVelocity()).isEqualTo(Vec3.ORIGIN.toVelocity());
        steps(4);
        assertThat(frames(12, 133)).hasSize(2);

        sink.clear();
        moveStart(11, 1001, a.position(), FACING, new Vec3(1, 0, 0), 2);
        world.haltForBattle(a);
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(parse(frames(12, 133).getFirst()).getMoveState()).isZero();
        assertThat(a.moveBroadcastPending()).isFalse();
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
        sink.clear();
        moveStop(11, 1001, a.position(), FACING, 3);
        world.haltForBattle(a);
        assertThat(frames(12, 133)).isEmpty();
        assertThat(a.moveBroadcastPending()).isTrue();
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
    }

    @Test
    void T7_带待发离场只销毁没有后续133() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        assertThat(a.moveBroadcastPending()).isTrue();
        sink.clear();
        world.onPlayerLeave(LINK, leave(11, 1001));
        steps(2);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(51);
    }

    @Test
    void T7_带待发接管只销毁没有后续133() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 2);
        assertThat(a.moveBroadcastPending()).isTrue();
        sink.clear();
        world.onTakeoverRequested(1001, 1);
        steps(2);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(51);
    }

    @Test
    void T7_带待发换图旧场51新场21无积压_停服无任何下行() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        Scene second = world.createScene(2);
        ScenePlayer c = enterNew(13, 1003);
        world.switchScene(c, second);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 3);
        assertThat(a.moveBroadcastPending()).isTrue();
        sink.clear();
        world.switchScene(a, second);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(sink.messageIdsTo(LINK, 13)).containsExactly(21);
        ActorCreateS2C created = ActorCreateS2C.parseFrom(sink.to(LINK, 13).getFirst().getSerializedMessage());
        assertThat(created.getEntity()).isEqualTo(a.entity());
        assertThat(created.getTransform().getLocation()).isEqualTo(a.position().toProto());
        assertThat(created.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(a.moveBroadcastPending()).isFalse();
        steps(3);
        assertThat(frames(12, 133)).isEmpty();
        assertThat(frames(13, 133)).isEmpty();
        moveStart(11, 1001, a.position(), FACING, new Vec3(1, 0, 0), 4);
        assertThat(a.moveBroadcastPending()).isTrue();
        sink.clear();
        world.shutdown();
        steps(3);
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void T8_帧内先47补135再64_已离开者不再收到133() throws Exception {
        ScenePlayer observer = enterNew(11, 1001);
        ScenePlayer lost = enterSaved(12, 1002, new Vec3(175, 200, 0));
        ScenePlayer fresh = enterSaved(13, 1003, new Vec3(205, 200, 0));
        moveStart(13, 1003, fresh.position(), FACING, new Vec3(1, 0, 0), 1);
        moveStop(11, 1001, new Vec3(200, 200, 0), FACING, 2);
        sink.clear();
        world.step();
        assertThat(sink.messageIdsTo(LINK, 11)).startsWith(47, 135, 64);
        assertThat(ActorListCreateS2C.parseFrom(frames(11, 47).getFirst().getSerializedMessage()).getActorListList())
                .extracting(ActorCreateS2C::getEntity).containsExactly(fresh.entity());
        assertThat(list(frames(11, 135).getFirst())).extracting(ActorMoveS2C::getEntity).containsExactly(fresh.entity());
        assertThat(ActorListDestroyS2C.parseFrom(frames(11, 64).getFirst().getSerializedMessage()).getEntityList())
                .containsExactly(lost.entity());
        assertThat(sink.messageIdsTo(LINK, 12)).startsWith(133, 64);
        moveStop(11, 1001, observer.position(), FACING, 3);
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
    }

    @Test
    void T9_没人看见仍清标记_新观察者只收当前135_T10_补发只含移动者_T11_不清目标待发() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        world.step();
        assertThat(a.moveBroadcastPending()).isFalse();
        assertThat(deliveries("broadcast")).isZero();
        ScenePlayer stationary = enterNew(14, 1004);
        sink.clear();
        Vec3 current = a.position();
        enterNew(12, 1002);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(79, 21, 47, 135);
        List<ActorMoveS2C> caught = list(frames(12, 135).getFirst());
        assertThat(caught).hasSize(1);
        assertThat(caught.getFirst().getEntity()).isEqualTo(a.entity()).isNotEqualTo(stationary.entity());
        assertThat(caught.getFirst().getTransform().getLocation()).isEqualTo(current.toProto());
        assertThat(caught.getFirst().getTransform().getRotation()).isEqualTo(FACING);
        assertThat(caught.getFirst().getVelocity()).isEqualTo(new Vec3(1, 0, 0).toVelocity());
        assertThat(caught.getFirst().getServerTimeMs()).isEqualTo(clock.epochMillis());
        assertThat(frames(11, 133)).isEmpty();
        assertThat(frames(11, 135)).isEmpty();
        assertThat(frames(12, 135).getFirst().getId()).isZero();
        double before = deliveries("catch_up");
        moveSync(11, 1001, a.position(), FACING, new Vec3(1, 0, 0), 2);
        enterNew(13, 1003);
        assertThat(a.moveBroadcastPending()).isTrue();
        assertThat(deliveries("catch_up") - before).isEqualTo(1);
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(frames(13, 133)).hasSize(1);
        assertThat(parse(frames(12, 133).getFirst()).getEntity()).isEqualTo(a.entity());
        assertThat(parse(frames(13, 133).getFirst())).isEqualTo(parse(frames(12, 133).getFirst()));
    }

    @Test
    void T6_冻结中的观察者不被广播过滤_冻结中的静止待发仍兑现() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterNew(12, 1002);
        WorldTestAccess.startFreezing(b);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        sink.clear();
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(parse(frames(12, 133).getFirst()).getEntity()).isEqualTo(a.entity());
        WorldTestAccess.clearSwitch(b);
        moveStop(11, 1001, a.position(), FACING, 2);
        WorldTestAccess.startFreezing(a);
        sink.clear();
        world.step();
        assertThat(frames(12, 133)).hasSize(1);
        assertThat(parse(frames(12, 133).getFirst()).getVelocity()).isEqualTo(Vec3.ORIGIN.toVelocity());
        assertThat(a.moveBroadcastPending()).isFalse();
    }

    @Test
    void T10_静止朝向随47与本人旁人21恢复_不补135() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        moveStop(11, 1001, SPAWN, FACING, 1);
        steps(2);
        enterNew(12, 1002);
        ActorCreateS2C initial = ActorListCreateS2C.parseFrom(frames(12, 47).getFirst().getSerializedMessage()).getActorList(0);
        assertThat(initial.getEntity()).isEqualTo(a.entity());
        assertThat(initial.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(frames(12, 135)).isEmpty();
        repo.put(new PlayerData(1003, 1, 1, 0, "", 1, 1, SPAWN,
                PlayerState.newBuilder().setFacing(Facing.newBuilder().setZ(90)).build()));
        sink.clear();
        ScenePlayer c = enter(13, 1003);
        for (int session : new int[] {11, 12, 13}) {
            ActorCreateS2C created = ActorCreateS2C.parseFrom(frames(session, 21).getFirst().getSerializedMessage());
            assertThat(created.getEntity()).isEqualTo(c.entity());
            assertThat(created.getTransform().getRotation()).isEqualTo(FACING);
            assertThat(frames(session, 133)).isEmpty();
            assertThat(frames(session, 135)).isEmpty();
        }
    }

    List<MessageContent> frames(int session, int id) {
        return sink.to(LINK, session).stream().filter(m -> m.getMessageId() == id).toList();
    }

    private static ActorMoveS2C parse(MessageContent frame) throws Exception {
        return ActorMoveS2C.parseFrom(frame.getSerializedMessage());
    }

    static List<ActorMoveS2C> list(MessageContent frame) throws Exception {
        return ActorMoveListS2C.parseFrom(frame.getSerializedMessage()).getMovesList();
    }

    double deliveries(String kind) {
        return meters.get("game.scene.actor.move.deliveries").tag("kind", kind).counter().count();
    }
}
