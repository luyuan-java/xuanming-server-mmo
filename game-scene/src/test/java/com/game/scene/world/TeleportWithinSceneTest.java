package com.game.scene.world;

import static com.game.scene.metrics.SceneMetrics.TeleportReason.ADMIN;
import static com.game.scene.metrics.SceneMetrics.TeleportReason.TEAM_REGROUP;
import static com.game.scene.metrics.SceneMetrics.TeleportResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ZoneRedirect;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveListS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.proto.Rotation;
import com.game.proto.SceneInfoComp;
import com.game.proto.TeleportS2C;
import com.game.scene.battle.BattleFreezeTestAccess;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.FakeSceneTables;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 规格 §11.3 V1–V11 与 §11.4 S4–S7；消息顺序、差集、落位副作用逐项钉住。 */
class TeleportWithinSceneTest extends MovementFixture {
    private static final Vec3 FAR = new Vec3(230, 200, 0);

    @Test
    void V1_远距离本人130销毁差集创建差集_旁人销毁或创建_指标与所有状态() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterNew(12, 1002);
        ScenePlayer c = enterSaved(13, 1003, FAR);
        moveStop(13, 1003, FAR, FACING, 1);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 2);
        assertThat(a.moveBroadcastPending()).isTrue();
        double entered = aoi("enter"), left = aoi("leave");
        sink.clear();
        assertThat(world.teleportWithinScene(a, FAR, null, ADMIN)).isEqualTo(TELEPORTED);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(130, 64, 47);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(64);
        assertThat(sink.messageIdsTo(LINK, 13)).containsExactly(21);
        assertTeleport(a, FAR, FACING, 3);
        assertThat(destroyed(11)).containsExactly(b.entity()).doesNotContain(a.entity());
        assertThat(destroyed(12)).containsExactly(a.entity());
        ActorCreateS2C seen = creates(11).getFirst();
        assertThat(seen.getEntity()).isEqualTo(c.entity()).isNotEqualTo(a.entity());
        assertThat(seen.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(seen.getTransform().getLocation()).isEqualTo(FAR.toProto());
        ActorCreateS2C recreated = ActorCreateS2C.parseFrom(sink.to(LINK, 13).getFirst().getSerializedMessage());
        assertThat(recreated.getEntity()).isEqualTo(a.entity());
        assertThat(recreated.getTransform().getLocation()).isEqualTo(FAR.toProto());
        assertThat(recreated.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(a.velocity()).isEqualTo(Vec3.ORIGIN);
        assertThat(a.moveGuard().anchor()).isEqualTo(FAR);
        assertThat(a.moveGuard().allowance()).isEqualTo(MoveGuard.CAPACITY);
        assertThat(a.moveBroadcastPending()).isFalse();
        assertThat(a.teleportSettle().targetX()).isEqualTo(FAR.x());
        assertThat(a.teleportSettle().targetY()).isEqualTo(FAR.y());
        assertThat(a.teleportSettle().deadlineNanos()).isEqualTo(clock.nanoTime() + 3_000_000_000L);
        assertThat(count("game.scene.teleports", "reason", "admin", "result", "teleported")).isEqualTo(1);
        assertThat(aoi("enter") - entered).isEqualTo(2);
        assertThat(aoi("leave") - left).isEqualTo(2);
        // V4：新观察者得到全量66；本人新看见的静止但有朝向目标同样保留既有补发语义。
        world.step();
        ActorBaseAttributesS2C forC = ActorBaseAttributesS2C.parseFrom(frames(13, 66).getFirst().getSerializedMessage());
        assertThat(forC.getEntityId()).isEqualTo(a.playerId());
        assertThat(forC.getTransform().getLocation()).isEqualTo(FAR.toProto());
        assertThat(forC.hasVelocity()).isTrue();
        assertThat(forC.getVelocity()).isEqualTo(Vec3.ORIGIN.toVelocity());
        ActorBaseAttributesS2C forA = ActorBaseAttributesS2C.parseFrom(frames(11, 66).getFirst().getSerializedMessage());
        assertThat(forA.getEntityId()).isEqualTo(c.playerId());
        assertThat(forA.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(frames(11, 133)).hasSize(1); // c 的原待发照常兑现，瞬移没有清它。
        assertThat(frames(12, 133)).isEmpty();
        assertThat(frames(13, 133)).isEmpty(); // V10：a 的待发已清。
    }

    @Test
    void V1_V11_落点移动目标47后135_不清目标待发_本人不收自己条目() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer c = enterSaved(13, 1003, FAR);
        moveStart(13, 1003, FAR, FACING, new Vec3(1, 0, 0), 1);
        sink.clear();
        double delivered = count("game.scene.actor.move.deliveries", "kind", "catch_up");
        world.teleportWithinScene(a, FAR, null, ADMIN);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(130, 47, 135);
        List<ActorMoveS2C> caught = ActorMoveListS2C.parseFrom(frames(11, 135).getFirst().getSerializedMessage()).getMovesList();
        assertThat(caught).hasSize(1);
        assertThat(caught.getFirst().getEntity()).isEqualTo(c.entity()).isNotEqualTo(a.entity());
        assertThat(caught.getFirst().getTransform().getLocation()).isEqualTo(FAR.toProto());
        assertThat(caught.getFirst().getTransform().getRotation()).isEqualTo(FACING);
        assertThat(caught.getFirst().getVelocity()).isEqualTo(new Vec3(1, 0, 0).toVelocity());
        assertThat(caught.getFirst().getMoveState()).isEqualTo(1);
        assertThat(caught.getFirst().getServerTimeMs()).isEqualTo(clock.epochMillis());
        assertThat(c.moveBroadcastPending()).isTrue();
        assertThat(count("game.scene.actor.move.deliveries", "kind", "catch_up") - delivered).isEqualTo(1);
        world.step();
        assertThat(frames(11, 133)).hasSize(1);
        assertThat(ActorMoveS2C.parseFrom(frames(11, 133).getFirst().getSerializedMessage())).isEqualTo(caught.getFirst());
        assertThat(frames(13, 133)).isEmpty();
    }

    @Test
    void V4_瞬移新观察者重置已清脏目标的全量66_偶数帧照旧() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer c = enterSaved(13, 1003, FAR);
        enterSaved(14, 1004, FAR);
        moveStart(13, 1003, FAR, FACING, new Vec3(1, 0, 0), 1);
        world.step();
        assertThat(c.syncDirty()).isZero();
        sink.clear();
        world.teleportWithinScene(a, FAR, null, ADMIN);
        assertThat(c.syncDirty()).isEqualTo(ScenePlayer.DIRTY_TRANSFORM | ScenePlayer.DIRTY_VELOCITY);
        world.step();
        assertThat(frames(11, 66)).isEmpty();
        world.step();
        assertThat(frames(11, 66)).hasSize(1);
        ActorBaseAttributesS2C sync = ActorBaseAttributesS2C.parseFrom(frames(11, 66).getFirst().getSerializedMessage());
        assertThat(sync.getEntityId()).isEqualTo(c.playerId());
        assertThat(sync.getTransform().getLocation()).isEqualTo(c.position().toProto());
        assertThat(sync.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(sync.hasVelocity()).isTrue();
        assertThat(sync.getVelocity()).isEqualTo(new Vec3(1, 0, 0).toVelocity());
    }

    @Test
    void V2_交集本人只130_旁人先64再21_滞回关系只销毁() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterNew(12, 1002);
        sink.clear();
        double entered = aoi("enter"), left = aoi("leave");
        world.teleportWithinScene(a, new Vec3(185, 200, 0), FACING, ADMIN);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(130);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(64, 21);
        assertThat(destroyed(12)).containsExactly(a.entity());
        assertThat(ActorCreateS2C.parseFrom(frames(12, 21).getFirst().getSerializedMessage()).getEntity()).isEqualTo(a.entity());
        assertThat(aoi("enter") - entered).isEqualTo(1);
        assertThat(aoi("leave") - left).isEqualTo(1);
        sink.clear();
        world.teleportWithinScene(a, new Vec3(195, 200, 0), null, ADMIN);
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(130, 64);
        assertThat(destroyed(11)).containsExactly(b.entity());
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(64);
        assertThat(world.mutuallyVisible(a, b)).isFalse();
    }

    @Test
    void V3_非法目标朝向冻结战斗已离场均不改状态且只计对应结局() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        State before = state(a);
        sink.clear();
        for (Vec3 bad : List.of(new Vec3(1e8, 0, 0), new Vec3(Double.NaN, 0, 0), new Vec3(0, Double.POSITIVE_INFINITY, 0))) {
            assertThat(world.teleportWithinScene(a, bad, null, ADMIN)).isEqualTo(INVALID_TARGET);
            assertThat(state(a)).isEqualTo(before);
        }
        assertThat(world.teleportWithinScene(a, FAR, Rotation.newBuilder().setY(Double.NaN).build(), ADMIN)).isEqualTo(INVALID_TARGET);
        WorldTestAccess.startFreezing(a);
        assertThat(world.teleportWithinScene(a, FAR, null, ADMIN)).isEqualTo(FROZEN);
        WorldTestAccess.clearSwitch(a);
        BattleFreezeTestAccess.freeze(a);
        assertThat(world.teleportWithinScene(a, FAR, null, ADMIN)).isEqualTo(IN_BATTLE);
        BattleFreezeTestAccess.clear(a);
        assertThat(state(a)).isEqualTo(before);
        assertThat(sink.events()).isEmpty();
        world.onTakeoverRequested(a.playerId(), a.ownerEpoch());
        sink.clear();
        State removed = state(a);
        assertThat(world.teleportWithinScene(a, FAR, null, ADMIN)).isEqualTo(NOT_HERE);
        assertThat(state(a)).isEqualTo(removed);
        assertThat(sink.events()).isEmpty();
        for (String result : new String[] {"frozen", "in_battle", "not_here"}) {
            assertThat(count("game.scene.teleports", "reason", "admin", "result", result)).isEqualTo(1);
        }
        assertThat(count("game.scene.teleports", "reason", "admin", "result", "invalid_target")).isEqualTo(4);
    }

    @Test
    void V3_帧内回调瞬移快速失败_失败后帧守卫释放() {
        AtomicInteger calls = new AtomicInteger();
        ClientSink callback = new ClientSink() {
            public void send(long link, List<Integer> sessions, MessageContent content) {
                sink.send(link, sessions, content);
                if (content.getMessageId() == 133) {
                    ScenePlayer p = world.playerById(1001);
                    State before = state(p);
                    assertThatThrownBy(() -> world.teleportWithinScene(p, FAR, null, ADMIN)).isInstanceOf(IllegalStateException.class);
                    assertThat(state(p)).isEqualTo(before);
                    calls.incrementAndGet();
                }
            }
            public void enterResult(long l, int s, long p, long e, int tip) { sink.enterResult(l, s, p, e, tip); }
            public void playerKicked(long l, int s, long p, long e, int tip) { sink.playerKicked(l, s, p, e, tip); }
            public boolean playerTransfer(long l, int s, long p, long f, long t, int n, long dest, Runnable failed) { return true; }
            public boolean playerRedirect(long l, int s, long p, long f, long t, ZoneRedirect r, Runnable failed) { return true; }
        };
        world = new SceneWorld(new FakeSceneTables(), IDS, callback, repo, new AtomicLong(8000)::incrementAndGet,
                clock, new SceneMetrics(meters));
        handler = new ClientRequestHandler(world, com.game.scene.testing.Contracts.REGISTRY, IDS);
        scene = world.createScene(1);
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        world.step();
        assertThat(calls).hasValue(1);
        assertThat(world.teleportWithinScene(a, FAR, null, ADMIN)).isEqualTo(TELEPORTED);
    }

    @Test
    void V6_V7_V8_刚进场与连续瞬移_选目标中允许_朝向三种形态与归队原因() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();
        world.teleportWithinScene(a, SPAWN, null, ADMIN);
        assertTeleport(a, SPAWN, null, 3);
        sink.clear();
        WorldTestAccess.startResolving(a);
        Vec3 next = new Vec3(181, 200, 0);
        assertThat(world.teleportWithinScene(a, next, FACING, TEAM_REGROUP)).isEqualTo(TELEPORTED);
        assertTeleport(a, next, FACING, 5);
        assertThat(ActorCreateS2C.parseFrom(frames(12, 21).getFirst().getSerializedMessage()).getTransform().getRotation()).isEqualTo(FACING);
        assertThat(a.rotation()).isEqualTo(FACING);
        assertThat(a.teleportSettle().targetX()).isEqualTo(next.x());
        assertThat(count("game.scene.teleport.settles", "result", "voided")).isEqualTo(1);
        sink.clear();
        world.teleportWithinScene(a, SPAWN, null, ADMIN);
        assertTeleport(a, SPAWN, FACING, 3);
        assertThat(count("game.scene.teleport.settles", "result", "voided")).isEqualTo(2);
    }

    @Test
    void V9_互见要求同场景且双向() {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterNew(12, 1002);
        assertThat(world.mutuallyVisible(a, b)).isTrue();
        world.switchScene(b, world.createScene(2));
        assertThat(world.mutuallyVisible(a, b)).isFalse();
        // a 的100项已满，后来者能看见 a，a 看不见后来者。
        for (int i = 0; i < ViewIndex.DEFAULT_CAPACITY; i++) enterNew(100 + i, 2000 + i);
        ScenePlayer later = enterNew(900, 9000);
        assertThat(scene.watching(later)).contains(a);
        assertThat(scene.watching(a)).doesNotContain(later);
        assertThat(world.mutuallyVisible(a, later)).isFalse();
    }

    @Test
    void Scene瞬移校验先于所有状态改动_不移动格子与兴趣表() {
        Scene s = new Scene(SceneInfoComp.newBuilder().setSceneId(90).setSceneConfigId(1).build());
        ScenePlayer a = ViewIndexTest.player(1, 0, 0, 0), b = ViewIndexTest.player(2, 1, 0, 0);
        s.add(a); s.add(b);
        assertThatThrownBy(() -> s.teleport(a, new Vec3(Double.NaN, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        ScenePlayer outsider = ViewIndexTest.player(1, 2, 0, 0);
        assertThatThrownBy(() -> s.teleport(outsider, FAR)).isInstanceOf(IllegalStateException.class);
        assertThat(a.position()).isEqualTo(Vec3.ORIGIN);
        assertThat(outsider.position()).isEqualTo(new Vec3(2, 0, 0));
        assertThat(s.watching(a)).containsExactly(b);
        assertThat(s.watchers(b)).containsExactly(a);
        assertThat(s.playerCount()).isEqualTo(2);
        ScenePlayer atOld = ViewIndexTest.player(3, 1, 0, 0), atBad = ViewIndexTest.player(4, 230, 200, 0);
        assertThat(s.add(atOld).seen()).contains(a);
        assertThat(s.add(atBad).seen()).doesNotContain(a);
    }

    @Test
    void S4_旧位置132和131整条丢弃_所有运动状态与额度不动_仍刷新活跃帧() {
        for (double distance : new double[] {10, 50}) {
            ScenePlayer a = enterNew(11, 1001);
            Vec3 target = new Vec3(SPAWN.x() + distance, SPAWN.y(), 0);
            ScenePlayer b = enterSaved(12, 1002, target);
            world.teleportWithinScene(a, target, FACING, ADMIN);
            world.step();
            State before = state(a);
            sink.clear();
            double staleBefore = count("game.scene.moves", "result", "teleport_stale");
            moveSync(11, 1001, SPAWN, Rotation.getDefaultInstance(), new Vec3(8, 1, 0), 1);
            assertThat(state(a)).isEqualTo(before);
            moveStop(11, 1001, SPAWN, Rotation.getDefaultInstance(), 2);
            assertThat(state(a)).isEqualTo(before);
            assertThat(a.lastActiveFrame()).isEqualTo(1);
            assertThat(sink.events()).isEmpty();
            assertThat(count("game.scene.moves", "result", "teleport_stale") - staleBefore).isEqualTo(2);
            world.onTakeoverRequested(a.playerId(), a.ownerEpoch());
            world.onTakeoverRequested(b.playerId(), b.ownerEpoch());
            // 不使用旧实例与旧帧号继续下一距离；下一轮使用新世界。
            setUp();
        }
    }

    @Test
    void S5_两米内落位后照常裁决_后续远报过MoveGuard() {
        ScenePlayer a = enterNew(11, 1001);
        world.teleportWithinScene(a, FAR, null, ADMIN);
        sink.clear();
        Vec3 settled = new Vec3(232, 200, 0);
        moveStart(11, 1001, settled, FACING, new Vec3(1, 0, 0), 1);
        assertThat(a.teleportSettle()).isNull();
        assertThat(a.position()).isEqualTo(settled);
        assertThat(a.moveGuard().anchor()).isEqualTo(settled);
        assertThat(a.moveGuard().allowance()).isEqualTo(22);
        assertThat(a.moveBroadcastPending()).isTrue();
        assertThat(count("game.scene.teleport.settles", "result", "settled")).isEqualTo(1);
        moveStop(11, 1001, new Vec3(300, 200, 0), FACING, 2);
        assertThat(a.position()).isEqualTo(new Vec3(254, 200, 0));
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(137);
    }

    @Test
    void S6_到期后十米静默撤销_五十米截断并137() throws Exception {
        for (double distance : new double[] {10, 50}) {
            ScenePlayer a = enterNew(11, 1001);
            Vec3 target = new Vec3(SPAWN.x() + distance, SPAWN.y(), 0);
            world.teleportWithinScene(a, target, null, ADMIN);
            clock.advanceMillis(3000);
            sink.clear();
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SceneWorld.class);
            var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            logs.start();
            logger.addAppender(logs);
            try {
                moveSync(11, 1001, SPAWN, FACING, Vec3.ORIGIN, 7);
                assertThat(logs.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList())
                        .singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                                .contains("瞬移落位等待超时", "player=1001", "目标=", "上报="));
            } finally {
                logger.detachAppender(logs);
                logs.stop();
            }
            assertThat(a.teleportSettle()).isNull();
            assertThat(count("game.scene.teleport.settles", "result", "expired")).isEqualTo(1);
            if (distance == 10) {
                assertThat(a.position()).isEqualTo(SPAWN);
                assertThat(sink.events()).isEmpty();
            } else {
                Vec3 clamped = new Vec3(206, 200, 0);
                assertThat(a.position()).isEqualTo(clamped);
                assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(137);
                MoveAckS2C ack = MoveAckS2C.parseFrom(sink.to(LINK, 11).getFirst().getSerializedMessage());
                assertThat(ack.getServerLocation()).isEqualTo(clamped.toLocation());
                assertThat(ack.getInputSeq()).isEqualTo(7);
                assertThat(count("game.scene.moves", "result", "corrected")).isEqualTo(1);
            }
            setUp();
        }
    }

    @Test
    void S7_非法上报先计INVALID_换图等待作废_新图上报照常() {
        ScenePlayer a = enterNew(11, 1001);
        world.teleportWithinScene(a, FAR, FACING, ADMIN);
        State before = state(a);
        sink.clear();
        moveSync(11, 1001, new Vec3(Double.NaN, 0, 0), FACING, Vec3.ORIGIN, 1);
        assertThat(state(a)).isEqualTo(before);
        assertThat(count("game.scene.moves", "result", "invalid")).isEqualTo(1);
        assertThat(count("game.scene.moves", "result", "teleport_stale")).isZero();
        assertThat(sink.events()).isEmpty();
        world.switchScene(a, world.createScene(2));
        assertThat(a.teleportSettle()).isNull();
        assertThat(a.moveBroadcastPending()).isFalse();
        assertThat(count("game.scene.teleport.settles", "result", "voided")).isEqualTo(1);
        sink.clear();
        Vec3 accepted = a.position().plusScaled(new Vec3(1, 0, 0), 10);
        moveStop(11, 1001, accepted, FACING, 2);
        assertThat(a.position()).isEqualTo(accepted);
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void 管理入口_tip逐项映射_找人拒绝也计数_朝向保持() throws Exception {
        assertThat(world.adminTeleport(0, FAR)).isEqualTo(1005);
        assertThat(world.adminTeleport(42, FAR)).isEqualTo(1004);
        ScenePlayer a = enterNew(11, 1001);
        moveStop(11, 1001, SPAWN, FACING, 1);
        assertThat(world.adminTeleport(1001, new Vec3(1e8, 0, 0))).isEqualTo(1005);
        WorldTestAccess.startFreezing(a);
        assertThat(world.adminTeleport(1001, FAR)).isEqualTo(1006);
        WorldTestAccess.clearSwitch(a);
        BattleFreezeTestAccess.freeze(a);
        assertThat(world.adminTeleport(1001, FAR)).isEqualTo(1006);
        BattleFreezeTestAccess.clear(a);
        sink.clear();
        assertThat(world.adminTeleport(1001, FAR)).isZero();
        assertTeleport(a, FAR, FACING, 3);
        assertThat(count("game.scene.teleports", "reason", "admin", "result", "invalid_target")).isEqualTo(2);
        for (String result : new String[] {"teleported", "not_here", "frozen", "in_battle"}) {
            assertThat(count("game.scene.teleports", "reason", "admin", "result", result)).isEqualTo(1);
        }
    }

    private State state(ScenePlayer p) {
        return new State(p.position(), p.rotation(), p.velocity(), p.syncDirty(), p.moveBroadcastPending(),
                p.moveGuard().anchor(), p.moveGuard().allowance(), p.teleportSettle());
    }
    private record State(Vec3 position, Rotation rotation, Vec3 velocity, int dirty, boolean pending,
                         Vec3 anchor, double allowance, Object settle) { }
    private List<MessageContent> frames(int session, int id) {
        return sink.to(LINK, session).stream().filter(m -> m.getMessageId() == id).toList();
    }
    private List<Long> destroyed(int session) throws Exception {
        return ActorListDestroyS2C.parseFrom(frames(session, 64).getFirst().getSerializedMessage()).getEntityList();
    }
    private List<ActorCreateS2C> creates(int session) throws Exception {
        return ActorListCreateS2C.parseFrom(frames(session, 47).getFirst().getSerializedMessage()).getActorListList();
    }
    private void assertTeleport(ScenePlayer p, Vec3 target, Rotation facing, int reason) throws Exception {
        MessageContent frame = frames(p.session().sessionId(), 130).getFirst();
        assertThat(frame.getId()).isZero();
        TeleportS2C tp = TeleportS2C.parseFrom(frame.getSerializedMessage());
        assertThat(tp.getEntity()).isEqualTo(p.entity()).isNotEqualTo(p.playerId());
        assertThat(tp.getTransform().hasLocation()).isTrue();
        assertThat(tp.getTransform().getLocation()).isEqualTo(target.toProto());
        assertThat(tp.getTransform().hasRotation()).isEqualTo(facing != null);
        if (facing != null) assertThat(tp.getTransform().getRotation()).isEqualTo(facing);
        assertThat(tp.getReason()).isEqualTo(reason);
        assertThat(tp.getInputSeq()).isZero();
        assertThat(tp.getAllFields().keySet()).noneMatch(f -> f.getName().equals("input_seq"));
    }
    private double aoi(String change) { return count("game.scene.aoi.changes", "change", change); }
    private double count(String name, String... tags) { return meters.get(name).tags(tags).counter().count(); }
}
