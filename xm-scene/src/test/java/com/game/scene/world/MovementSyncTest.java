package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.SceneWorldTest.leave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.game.api.proto.ClientForward;
import com.game.player.store.state.Facing;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorListDestroyS2C;
import com.game.proto.ActorMoveS2C;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.Rotation;
import com.game.proto.SceneInfoRequest;
import com.game.proto.TeleportRequestC2S;
import com.game.proto.TeleportRequestC2SResponse;
import com.game.proto.TeleportS2C;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.testing.RecordingSink.Sent;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 移动与视野 / 属性同步的客户端可见行为，逐条对应契约文档的「Java 必须做到」清单：
 * {@code docs/reference/mmorpg-client-contract-movement.md} §10 与 {@code mmorpg-client-contract-aoi.md} §10。
 * 用手动时钟（{@link ManualClock}）、手动推帧（{@link SceneWorld#step()}）与记录出站的 {@link RecordingSink}，
 * 上行一律经 {@link ClientRequestHandler}（与线上同一条解析 / 派发路径）。
 */
class MovementSyncTest {

    private static final long LINK = 1;
    private static final SceneMessageIds IDS = Contracts.IDS;
    private static final Vec3 SPAWN = FakeSceneTables.SPAWN_1;
    private static final Rotation FACING = Rotation.newBuilder().setZ(90).build();

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private ManualClock clock;
    private SceneWorld world;
    private ClientRequestHandler handler;
    private Scene scene;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        clock = new ManualClock();
        AtomicLong ids = new AtomicLong(7000);
        FakeSceneTables tables = new FakeSceneTables();
        world = new SceneWorld(tables, IDS, sink, repo, ids::incrementAndGet, clock, SceneMetrics.noop());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS);
        scene = world.createScene(1);
    }

    // ------------------------------------------------------------------ 移动 §10：上行处理

    @Test
    void 移动三条上行永不回包_被拒也不回_客户端发来的S2C方向方法号静默忽略() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();

        moveStart(11, 1001, new Vec3(181, 200, 0), FACING, new Vec3(3, 0, 0), 1);
        moveSync(11, 1001, new Vec3(181.5, 200, 0), FACING, new Vec3(3, 0, 0), 2);
        moveSync(11, 1001, new Vec3(Double.NaN, 200, 0), FACING, new Vec3(3, 0, 0), 3);
        moveStop(11, 1001, new Vec3(182, 200, 0), FACING, 4);
        forward(11, 1001, id("NotifyMoveAck"), MoveAckS2C.newBuilder().setInputSeq(5).build(), 5);
        forward(11, 1001, id("NotifyActorMove"), ActorMoveS2C.newBuilder().setEntity(a.entity()).build(), 6);
        forward(11, 1001, id("NotifyActorMoveList"), ActorMoveS2C.getDefaultInstance(), 7);
        forward(11, 1001, id("NotifyTeleport"), TeleportS2C.newBuilder().setInputSeq(8).build(), 8);

        assertThat(sink.events()).as("没有任何回包，也没有 137（无纠偏）").isEmpty();
        assertThat(a.position()).isEqualTo(new Vec3(182, 200, 0));
    }

    @Test
    void 先位置后速度_朝向整体覆盖_没带朝向就是空朝向_MoveStop速度清零() {
        ScenePlayer a = enterNew(11, 1001);

        moveStart(11, 1001, new Vec3(181, 201, 0.5), FACING, new Vec3(3, 4, 0), 1);
        assertThat(a.position()).isEqualTo(new Vec3(181, 201, 0.5));
        assertThat(a.rotation()).isEqualTo(FACING);
        assertThat(a.velocity()).isEqualTo(new Vec3(3, 4, 0));
        assertThat(a.syncDirty()).isEqualTo(ScenePlayer.DIRTY_TRANSFORM | ScenePlayer.DIRTY_VELOCITY);

        moveSync(11, 1001, new Vec3(182, 202, 0.5), null, new Vec3(0, 5, 0), 2);
        assertThat(a.rotation()).as("基线无条件覆盖：请求没带 rotation 就是全零").isEqualTo(Rotation.getDefaultInstance());
        assertThat(a.velocity()).isEqualTo(new Vec3(0, 5, 0));

        moveStop(11, 1001, new Vec3(183, 202, 0.5), FACING, 3);
        assertThat(a.position()).isEqualTo(new Vec3(183, 202, 0.5));
        assertThat(a.rotation()).isEqualTo(FACING);
        assertThat(a.velocity()).isEqualTo(Vec3.ORIGIN);
    }

    @Test
    void 速度按三维模长等比截断到10米每秒_方向不变_未超原样() {
        ScenePlayer a = enterNew(11, 1001);

        moveStart(11, 1001, SPAWN, FACING, new Vec3(30, 0, 40), 1);
        assertThat(a.velocity()).as("|v| = 50，含 z 分量一起缩").isEqualTo(new Vec3(6, 0, 8));

        moveSync(11, 1001, SPAWN, FACING, new Vec3(6, 8, 0), 2);
        assertThat(a.velocity()).as("恰好 10 不截").isEqualTo(new Vec3(6, 8, 0));

        moveSync(11, 1001, SPAWN, FACING, new Vec3(0, 0, -25), 3);
        assertThat(a.velocity()).isEqualTo(new Vec3(0, 0, -10));

        Vec3 huge = MovementRules.clampSpeed(new Vec3(1e300, -1e300, 0));
        assertThat(huge.length()).as("模长平方溢出也不丢方向").isCloseTo(10, within(1e-9));
        assertThat(huge.x()).isCloseTo(10 / Math.sqrt(2), within(1e-9));
        assertThat(huge.y()).isCloseTo(-10 / Math.sqrt(2), within(1e-9));
    }

    @Test
    void 没有导航网格_额度内的上报位置原样接受_含高度_不发137() {
        ScenePlayer a = enterNew(11, 1001);
        sink.clear();

        Vec3 reported = new Vec3(185.5, 203.25, 7.75);
        moveSync(11, 1001, reported, FACING, new Vec3(1, 0, 0), 1);

        assertThat(a.position()).isEqualTo(reported);
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void 水平偏差超过半米才给本人发137_恰好半米不发_字段与信封() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();

        // 位移额度满格 24 m，时钟不走不回填：先走 23.5 m，剩 0.5 m。
        moveSync(11, 1001, new Vec3(203.5, 200, 0), FACING, Vec3.ORIGIN, 1);
        // 想走 1 m 只给 0.5 m：裁决 (204, 200)，偏差恰好 0.5 m，不发。
        moveSync(11, 1001, new Vec3(204.5, 200, 0), FACING, Vec3.ORIGIN, 2);
        assertThat(sink.events()).isEmpty();
        assertThat(a.position()).isEqualTo(new Vec3(204, 200, 0));

        // 额度 0：原地不动，偏差 6 m，回 137；速度按 10 m/s 截断。
        moveSync(11, 1001, new Vec3(210, 200, 0), FACING, new Vec3(20, 0, 0), 9);

        assertThat(sink.events()).hasSize(1);
        assertThat(sink.to(LINK, 12)).as("只发给本人").isEmpty();
        MessageContent ack = sink.to(LINK, 11).getFirst();
        assertThat(ack.getMessageId()).isEqualTo(137);
        assertThat(ack.getId()).isZero();
        assertThat(ack.hasErrorMessage()).isFalse();
        MoveAckS2C body = MoveAckS2C.parseFrom(ack.getSerializedMessage());
        assertThat(body.getInputSeq()).isEqualTo(9);
        assertThat(Vec3.fromLocation(body.getServerLocation())).isEqualTo(new Vec3(204, 200, 0));
        assertThat(body.hasServerVelocity()).isTrue();
        assertThat(Vec3.fromVelocity(body.getServerVelocity())).as("处理完这条输入之后的速度").isEqualTo(new Vec3(10, 0, 0));
        assertThat(body.getServerTimeMs()).isEqualTo(ManualClock.EPOCH_MILLIS_START);
        assertThat(a.position()).isEqualTo(new Vec3(204, 200, 0));
    }

    @Test
    void 非有限值整条丢弃_位置朝向速度脏位都不变_不回包() {
        ScenePlayer a = enterNew(11, 1001);
        moveStart(11, 1001, new Vec3(181, 200, 0), FACING, new Vec3(1, 0, 0), 1);
        a.clearDirty();
        sink.clear();

        moveSync(11, 1001, new Vec3(Double.NaN, 200, 0), Rotation.getDefaultInstance(), Vec3.ORIGIN, 2);
        moveSync(11, 1001, new Vec3(190, 200, 0), Rotation.getDefaultInstance(),
                new Vec3(Double.POSITIVE_INFINITY, 0, 0), 3);
        moveStop(11, 1001, new Vec3(190, 200, Double.NEGATIVE_INFINITY), Rotation.getDefaultInstance(), 4);
        moveStop(11, 1001, new Vec3(190, 200, 0), Rotation.newBuilder().setY(Double.NaN).build(), 5);

        assertThat(a.position()).isEqualTo(new Vec3(181, 200, 0));
        assertThat(a.rotation()).isEqualTo(FACING);
        assertThat(a.velocity()).isEqualTo(new Vec3(1, 0, 0));
        assertThat(a.syncDirty()).isZero();
        assertThat(sink.events()).isEmpty();
    }

    @Test
    void 回归_有限但极端的坐标_整条丢弃_位置与锚点不变_不发137_写回坐标有限() {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();

        // 旧实现：第一条原样接受（z = 1.7e308），第二条截断时高度插值溢出成 -Inf；第三条水平距离溢出。
        moveSync(11, 1001, new Vec3(180.5, 200, 1.7e308), FACING, Vec3.ORIGIN, 1);
        moveSync(11, 1001, new Vec3(980.5, 200, -1.7e308), FACING, Vec3.ORIGIN, 2);
        moveSync(11, 1001, new Vec3(1.5e308, 1.5e308, 0), FACING, Vec3.ORIGIN, 3);

        assertThat(a.position()).isEqualTo(SPAWN);
        assertThat(a.moveGuard().anchor()).isEqualTo(SPAWN);
        assertThat(a.syncDirty()).isZero();
        assertThat(sink.events()).as("整条丢弃：不回包、不发 137").isEmpty();

        world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(repo.saves()).singleElement()
                .satisfies(save -> assertThat(save.position()).isEqualTo(SPAWN));
    }

    @Test
    void 世界范围_恰好上限接受_超出一点整条丢弃() {
        ScenePlayer a = enterNew(11, 1001);
        Vec3 edge = new Vec3(180, 200, MovementRules.WORLD_LIMIT);

        moveSync(11, 1001, edge, FACING, Vec3.ORIGIN, 1);
        assertThat(a.position()).isEqualTo(edge);

        moveSync(11, 1001, new Vec3(180, 200, Math.nextUp(MovementRules.WORLD_LIMIT)), FACING, Vec3.ORIGIN, 2);
        moveSync(11, 1001, new Vec3(-Math.nextUp(MovementRules.WORLD_LIMIT), 200, 0), FACING, Vec3.ORIGIN, 3);
        assertThat(a.position()).isEqualTo(edge);
    }

    @Test
    void 回归_世界范围内的极端高度被截断_137的坐标有限_高度取上报值_写回有限() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        sink.clear();
        double limit = MovementRules.WORLD_LIMIT;

        moveSync(11, 1001, new Vec3(180, 200, limit), FACING, Vec3.ORIGIN, 1);
        // 水平 800 m 超额，额度满格 24 m；高度从 +1e7 到 -1e7，取上报值。
        moveSync(11, 1001, new Vec3(980, 200, -limit), FACING, Vec3.ORIGIN, 2);

        MoveAckS2C ack = MoveAckS2C.parseFrom(sink.to(LINK, 11).getFirst().getSerializedMessage());
        Vec3 server = Vec3.fromLocation(ack.getServerLocation());
        assertThat(server.isFinite()).isTrue();
        assertThat(server.x()).isCloseTo(180 + MoveGuard.CAPACITY, within(1e-9));
        assertThat(server.y()).isEqualTo(200);
        assertThat(server.z()).isEqualTo(-limit);
        assertThat(a.position()).isEqualTo(server);

        world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(repo.saves()).singleElement().satisfies(save -> assertThat(save.position()).isEqualTo(server));
    }

    @Test
    void 存档坐标超出世界范围_进场落到出生点() {
        ScenePlayer a = enterSaved(11, 1001, new Vec3(180, 200, 1.7e308));

        assertThat(a.position()).isEqualTo(SPAWN);
    }

    @Test
    void 场景拒绝非有限位置_先查后写_位置不变() {
        ScenePlayer a = enterNew(11, 1001);

        assertThatThrownBy(() -> scene.relocate(a, new Vec3(181, 200, Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.relocate(a, new Vec3(181, 200, Double.NEGATIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(a.position()).isEqualTo(SPAWN);
    }

    // ------------------------------------------------------------------ 移动 §10：外推与挂机

    @Test
    void 外推_每帧位置加速度乘0点05_三个分量都推() {
        ScenePlayer a = enterNew(11, 1001);
        moveStart(11, 1001, new Vec3(180, 200, 1), FACING, new Vec3(4, -2, 1), 1);

        world.step();
        assertThat(a.position().x()).isCloseTo(180.2, within(1e-9));
        assertThat(a.position().y()).isCloseTo(199.9, within(1e-9));
        assertThat(a.position().z()).isCloseTo(1.05, within(1e-9));

        steps(19);
        assertThat(a.position().x()).as("1 秒 20 帧走 4 m").isCloseTo(184, within(1e-9));
        assertThat(a.position().y()).isCloseTo(198, within(1e-9));
        assertThat(a.position().z()).isCloseTo(2, within(1e-9));

        moveStop(11, 1001, new Vec3(184, 198, 2), FACING, 2);
        steps(10);
        assertThat(a.position()).as("停下后不再推").isEqualTo(new Vec3(184, 198, 2));
    }

    @Test
    void 挂机_30秒600帧没有任何客户端消息停止外推_给观察者一条速度为零的66() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(0.1, 0, 0), 1);
        sink.clear();

        steps(MovementRules.AFK_FRAMES);
        assertThat(a.velocity()).as("第 0–599 帧都在推").isEqualTo(new Vec3(0.1, 0, 0));
        assertThat(a.position().x()).isCloseTo(183, within(1e-9));

        world.step();
        assertThat(a.velocity()).as("第 600 帧停推").isEqualTo(Vec3.ORIGIN);
        Vec3 stoppedAt = a.position();
        assertThat(stoppedAt.x()).isCloseTo(183, within(1e-9));
        ActorBaseAttributesS2C last = lastSyncTo(12);
        assertThat(last.getEntityId()).isEqualTo(1001L);
        assertThat(last.hasVelocity()).isTrue();
        assertThat(last.getVelocity().getSerializedSize()).as("存在且全零 = 停了").isZero();

        steps(40);
        assertThat(a.position()).isEqualTo(stoppedAt);
    }

    @Test
    void 任一客户端消息都刷新活跃帧_挂机判定顺延() {
        ScenePlayer a = enterNew(11, 1001);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(0.1, 0, 0), 1);

        steps(300); // 第 0–299 帧
        forward(11, 1001, IDS.sceneInfoC2S(), SceneInfoRequest.getDefaultInstance(), 1);
        steps(MovementRules.AFK_FRAMES); // 第 300–899 帧
        assertThat(a.velocity()).as("第 300 帧前收到 43（不是移动包），到第 899 帧仍在推").isEqualTo(new Vec3(0.1, 0, 0));

        world.step(); // 第 900 帧
        assertThat(a.velocity()).isEqualTo(Vec3.ORIGIN);
    }

    // ------------------------------------------------------------------ 属性同步 66

    @Test
    void 属性同步66_偶数帧每实体至多一条_只带脏字段_不发给自己_每条带entity_id() throws Exception {
        enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();

        moveStart(11, 1001, SPAWN, FACING, new Vec3(2, 0, 0), 1);
        world.step(); // 第 0 帧：同步
        List<ActorBaseAttributesS2C> toB = syncsTo(12);
        assertThat(toB).hasSize(1);
        ActorBaseAttributesS2C first = toB.getFirst();
        assertThat(first.getEntityId()).as("guid 口径，= player_id").isEqualTo(1001L);
        assertThat(first.hasTransform()).isTrue();
        assertThat(first.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(first.getTransform().hasScale()).isFalse();
        assertThat(Vec3.fromVelocity(first.getVelocity())).isEqualTo(new Vec3(2, 0, 0));
        assertThat(first.hasCombatStateFlags()).isFalse();

        world.step(); // 第 1 帧：奇数帧不同步
        assertThat(syncsTo(12)).hasSize(1);

        // 两帧之间两条输入：速度只在下一条 66 里带一次，带的是最新值。
        moveSync(11, 1001, new Vec3(180.2, 200, 0), FACING, new Vec3(3, 0, 0), 2);
        moveSync(11, 1001, new Vec3(180.3, 200, 0), FACING, new Vec3(4, 0, 0), 3);
        world.step(); // 第 2 帧
        assertThat(syncsTo(12)).hasSize(2);
        assertThat(Vec3.fromVelocity(syncsTo(12).get(1).getVelocity())).isEqualTo(new Vec3(4, 0, 0));

        steps(2); // 第 3、4 帧：在动，只带 transform
        ActorBaseAttributesS2C moving = syncsTo(12).get(2);
        assertThat(moving.hasTransform()).isTrue();
        assertThat(moving.hasVelocity()).as("速度没变就不带").isFalse();
        assertThat(syncsTo(12)).hasSize(3);

        assertThat(syncsTo(11)).as("不发给自己").isEmpty();
    }

    @Test
    void 停步的66字节形态_rotation与velocity存在且为空() {
        enterNew(11, 1001);
        enterNew(12, 1002);
        sink.clear();

        moveStop(11, 1001, SPAWN, null, 1);
        world.step();
        steps(3);

        List<MessageContent> toB = sink.to(LINK, 12);
        assertThat(toB).as("停下后只发一条，之后静默").singleElement()
                .satisfies(m -> assertThat(m.getMessageId()).isEqualTo(66))
                .satisfies(m -> assertThat(m.getId()).isZero());
        // 契约文档 movement §6.1 的示例字节，前面加上 entity_id = 1001（08 e9 07）。
        assertThat(HexFormat.of().formatHex(toB.getFirst().getSerializedMessage().toByteArray())).isEqualTo(
                "08e907"
                        + "1216" + "0a12" + "0900000000008066401100000000000069401200"
                        + "1a00");
    }

    @Test
    void 没人看得见时保留脏位_第一次有人看见时积压的字段一次发出_47在66之前() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        moveStop(11, 1001, new Vec3(181, 200, 0), FACING, 1);
        steps(4);
        assertThat(a.syncDirty()).isEqualTo(ScenePlayer.DIRTY_TRANSFORM | ScenePlayer.DIRTY_VELOCITY);

        enterNew(12, 1002);
        steps(1);

        List<MessageContent> toB = sink.to(LINK, 12);
        assertThat(toB).extracting(MessageContent::getMessageId).containsExactly(79, 21, 47, 66);
        ActorBaseAttributesS2C sync = ActorBaseAttributesS2C.parseFrom(toB.get(3).getSerializedMessage());
        assertThat(Vec3.fromProto(sync.getTransform().getLocation())).isEqualTo(new Vec3(181, 200, 0));
        assertThat(sync.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(sync.hasVelocity()).isTrue();
        assertThat(a.syncDirty()).isZero();
    }

    @Test
    void 新观察者_移动中的目标下一同步帧补上朝向与速度_进场与帧内进视野两条路径() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        enterSaved(13, 1003, new Vec3(185, 200, 0));
        moveStart(11, 1001, SPAWN, FACING, new Vec3(1, 0, 0), 1);
        steps(3); // 第 0–2 帧：速度已随第 0 帧的 66 发给 1003
        assertThat(a.syncDirty() & ScenePlayer.DIRTY_VELOCITY).isZero();

        // 进场路径：1002 进场看见移动中的 1001。
        enterNew(12, 1002);
        steps(2); // 第 3 帧奇数，第 4 帧同步
        List<MessageContent> toB = sink.to(LINK, 12);
        assertThat(toB).extracting(MessageContent::getMessageId).containsExactly(79, 21, 47, 66);
        ActorBaseAttributesS2C forB = ActorBaseAttributesS2C.parseFrom(toB.get(3).getSerializedMessage());
        assertThat(forB.getEntityId()).isEqualTo(1001L);
        assertThat(forB.getTransform().getRotation()).isEqualTo(FACING);
        assertThat(Vec3.fromVelocity(forB.getVelocity())).isEqualTo(new Vec3(1, 0, 0));

        // 帧内进视野路径：1004 从 19 m 外走进视野。
        ScenePlayer d = enterSaved(14, 1004, new Vec3(200, 200, 0));
        assertThat(sink.messageIdsTo(LINK, 14)).as("进场时谁都看不见").containsExactly(79, 21);
        moveStop(14, 1004, new Vec3(188, 200, 0), FACING, 1);
        steps(2); // 第 5 帧刷新视野发 47，第 6 帧同步
        List<MessageContent> toD = sink.to(LINK, 14);
        assertThat(toD).extracting(MessageContent::getMessageId).containsExactly(79, 21, 47, 66);
        assertThat(ActorListCreateS2C.parseFrom(toD.get(2).getSerializedMessage()).getActorListList())
                .extracting(actor -> actor.getGuid()).contains(1001L);
        ActorBaseAttributesS2C forD = ActorBaseAttributesS2C.parseFrom(toD.get(3).getSerializedMessage());
        assertThat(forD.getEntityId()).isEqualTo(1001L);
        assertThat(Vec3.fromVelocity(forD.getVelocity())).isEqualTo(new Vec3(1, 0, 0));
        assertThat(d.position()).isEqualTo(new Vec3(188, 200, 0));
    }

    // ------------------------------------------------------------------ 视野：进出通知

    @Test
    void 走近走远_双方各收一次47与64_47先于66_64之后不再有66_离场不给已看不见的人发51() throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterSaved(12, 1002, new Vec3(180, 215, 0));
        assertThat(sink.messageIdsTo(LINK, 11)).as("15 m 外进场，互不可见").containsExactly(79, 21);
        sink.clear();

        moveSync(12, 1002, new Vec3(180, 205, 0), FACING, Vec3.ORIGIN, 1);
        world.step(); // 第 0 帧
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(47, 66);
        assertThat(sink.messageIdsTo(LINK, 12)).as("静止、从没上报过朝向的 1001 没有要补的 66").containsExactly(47);
        assertThat(ActorListCreateS2C.parseFrom(sink.to(LINK, 11).get(0).getSerializedMessage()).getActorList(0)
                .getEntity()).isEqualTo(b.entity());
        assertThat(ActorListCreateS2C.parseFrom(sink.to(LINK, 12).get(0).getSerializedMessage()).getActorList(0)
                .getEntity()).isEqualTo(a.entity());

        clock.advanceMillis(2_000);
        moveSync(12, 1002, new Vec3(180, 225.5, 0), FACING, Vec3.ORIGIN, 2);
        steps(4); // 第 1 帧出视野（64），第 2、4 帧同步时 1001 已不在 1002 的观察者里
        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(47, 66, 64);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(47, 64);
        assertThat(ActorListDestroyS2C.parseFrom(sink.to(LINK, 11).get(2).getSerializedMessage()).getEntityList())
                .containsExactly(b.entity());
        assertThat(ActorListDestroyS2C.parseFrom(sink.to(LINK, 12).get(1).getSerializedMessage()).getEntityList())
                .containsExactly(a.entity());

        world.onPlayerLeave(LINK, leave(12, 1002));
        assertThat(sink.messageIdsTo(LINK, 11)).as("没有残影，也不收多余的 51").containsExactly(47, 66, 64);
    }

    @Test
    void 移动中走近_累计不足1米推迟重判_停下那一帧按精确位置进视野() {
        enterNew(11, 1001);
        ScenePlayer b = enterSaved(12, 1002, new Vec3(180, 210.3, 0));
        sink.clear();

        moveStart(12, 1002, new Vec3(180, 210.3, 0), FACING, new Vec3(0, -1, 0), 1);
        steps(8); // 每帧 0.05 m：走到约 9.9 m 处，累计才 0.4 m
        assertThat(b.position().y()).isCloseTo(209.9, within(1e-9));
        assertThat(sink.messageIdsTo(LINK, 11)).as("移动中不足 1 m，还没重判").isEmpty();
        assertThat(sink.messageIdsTo(LINK, 12)).isEmpty();

        moveStop(12, 1002, b.position(), FACING, 2);
        world.step();

        assertThat(sink.messageIdsTo(LINK, 11)).as("停下那一帧就重判：47，随后同步帧补 66").startsWith(47);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(47);
    }

    @Test
    void 滞回带内来回走不反复进出视野() {
        enterNew(11, 1001);
        enterSaved(12, 1002, new Vec3(185, 200, 0));
        sink.clear();

        for (int i = 0; i < 10; i++) {
            clock.advanceMillis(2_000);
            moveStop(12, 1002, new Vec3(i % 2 == 0 ? 199 : 189, 200, 0), FACING, i);
            steps(2);
        }

        assertThat(sink.messageIdsTo(LINK, 11)).as("19 m 与 9 m 之间来回：只有 66").containsOnly(66);
        assertThat(sink.messageIdsTo(LINK, 12)).isEmpty();
    }

    // ------------------------------------------------------------------ 离场 / 接管 / 停服

    @Test
    void 离场先停再写回_写回此刻位置含高度_之后不再外推_迟到的移动输入丢弃() throws Exception {
        List<Vec3> velocityAtSave = new ArrayList<>();
        ScenePlayer[] holder = new ScenePlayer[1];
        useRepository(save -> velocityAtSave.add(holder[0].velocity()));
        ScenePlayer a = enterNew(11, 1001);
        holder[0] = a;
        enterNew(12, 1002);
        moveStart(11, 1001, new Vec3(181, 200, 0.5), FACING, new Vec3(2, 0, 1), 1);
        steps(3);
        Vec3 atLeave = a.position();
        assertThat(atLeave.x()).isCloseTo(181.3, within(1e-9));
        assertThat(atLeave.z()).isCloseTo(0.65, within(1e-9));
        sink.clear();

        world.onPlayerLeave(LINK, leave(11, 1001));

        assertThat(velocityAtSave).as("写回时速度已清零").containsExactly(Vec3.ORIGIN);
        assertThat(repo.saves()).as("朝向随写回一起持久化").containsExactly(new PlayerSave(1001, 1, 1, 1, atLeave,
                PlayerState.newBuilder().setFacing(Facing.newBuilder().setZ(90)).build()));
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(ActorDestroyS2C.parseFrom(sink.to(LINK, 12).get(0).getSerializedMessage()).getEntity())
                .isEqualTo(a.entity());

        moveSync(11, 1001, new Vec3(190, 200, 0), FACING, new Vec3(5, 0, 0), 2);
        steps(10);
        assertThat(a.position()).isEqualTo(atLeave);
        assertThat(a.velocity()).isEqualTo(Vec3.ORIGIN);
        assertThat(sink.messageIdsTo(LINK, 12)).as("离场后不再有它的 66").containsExactly(51);
        assertThat(sink.to(LINK, 11)).isEmpty();
        assertThat(repo.saves()).hasSize(1);
    }

    @Test
    void 被接管与停服_同样先停再写回_之后的移动输入丢弃() {
        List<Vec3> velocityAtSave = new ArrayList<>();
        List<ScenePlayer> tracked = new ArrayList<>();
        useRepository(save -> {
            for (ScenePlayer player : tracked) {
                if (player.playerId() == save.playerId()) {
                    velocityAtSave.add(player.velocity());
                }
            }
        });
        ScenePlayer a = enterNew(11, 1001);
        ScenePlayer b = enterNew(12, 1002);
        tracked.add(a);
        tracked.add(b);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(3, 0, 0), 1);
        moveStart(12, 1002, SPAWN, FACING, new Vec3(0, 3, 0), 1);
        steps(2);

        world.onTakeoverRequested(1001, 1);
        moveSync(11, 1001, new Vec3(185, 200, 0), FACING, new Vec3(3, 0, 0), 2);
        Vec3 bBeforeShutdown = b.position();
        world.shutdown();
        steps(5);

        assertThat(velocityAtSave).containsExactly(Vec3.ORIGIN, Vec3.ORIGIN);
        assertThat(repo.saves()).extracting(PlayerSave::position).containsExactly(a.position(), bBeforeShutdown);
        assertThat(b.position()).isEqualTo(bBeforeShutdown);
        assertThat(a.position().x()).as("接管之后的 MoveSync 没有生效").isCloseTo(180.3, within(1e-9));
    }

    @Test
    void 换场景_速度清零_新场景的人看到的是静止的它() {
        Scene second = world.createScene(2);
        ScenePlayer a = enterNew(11, 1001);
        moveStart(11, 1001, SPAWN, FACING, new Vec3(3, 0, 0), 1);
        steps(1);

        world.switchScene(a, second);

        assertThat(a.velocity()).isEqualTo(Vec3.ORIGIN);
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_2);
        steps(4);
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_2);
    }

    // ------------------------------------------------------------------ 136 与「从不发」

    @Test
    void 瞬移请求136_回TeleportRequestC2SResponse_error_message存在且为1006_回显请求号_之后不发130()
            throws Exception {
        ScenePlayer a = enterNew(11, 1001);
        sink.clear();

        forward(11, 1001, id("TeleportRequest"), TeleportRequestC2S.newBuilder()
                .setTeleportTableId(3).setTargetLocation(new Vec3(500, 500, 0).toLocation()).build(), 55);
        steps(4);

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).singleElement().satisfies(reply -> {
            assertThat(reply.getMessageId()).isEqualTo(136);
            assertThat(reply.getId()).isEqualTo(55L);
            assertThat(reply.hasErrorMessage()).as("tip 在应答体里，不在信封上").isFalse();
        });
        TeleportRequestC2SResponse response = TeleportRequestC2SResponse.parseFrom(toA.getFirst().getSerializedMessage());
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(a.position()).isEqualTo(SPAWN);
    }

    @Test
    void 从不发133_135_130与65_55_82_68_75() {
        Set<Integer> never = Set.of(id("NotifyActorMove"), id("NotifyActorMoveList"), id("NotifyTeleport"),
                syncId("SyncAttribute2Frames"), syncId("SyncAttribute5Frames"), syncId("SyncAttribute10Frames"),
                syncId("SyncAttribute30Frames"), syncId("SyncAttribute60Frames"));
        assertThat(never).containsExactlyInAnyOrder(133, 135, 130, 65, 55, 82, 68, 75);
        Scene second = world.createScene(2);
        ScenePlayer a = enterNew(11, 1001);
        enterNew(12, 1002);
        enterSaved(13, 1003, new Vec3(150, 200, 0));

        moveStart(11, 1001, SPAWN, FACING, new Vec3(-9, 0, 0), 1);
        steps(80);
        moveStop(11, 1001, a.position(), FACING, 2);
        moveSync(12, 1002, new Vec3(200, 210, 0), FACING, new Vec3(0, 50, 0), 3);
        steps(MovementRules.AFK_FRAMES + 20);
        world.switchScene(a, second);
        world.onPlayerLeave(LINK, leave(13, 1003));
        steps(4);

        assertThat(sink.events()).isNotEmpty();
        for (Object event : sink.events()) {
            if (event instanceof Sent sent) {
                assertThat(never).doesNotContain(sent.content().getMessageId());
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 换成包一层的存储：写回时先回调 {@code onSave}（此时实例状态就是写回那一刻的状态），再记进 {@link #repo}。 */
    private void useRepository(Consumer<PlayerSave> onSave) {
        FakePlayerRepository inner = repo;
        PlayerRepository wrapped = new PlayerRepository() {
            @Override
            public void load(long playerId, Consumer<LoadResult> onLoaded) {
                inner.load(playerId, onLoaded);
            }

            @Override
            public void save(PlayerSave save) {
                onSave.accept(save);
                inner.save(save);
            }

            @Override
            public void release(long playerId, long ownerEpoch) {
                inner.release(playerId, ownerEpoch);
            }

            @Override
            public void saveProgress(PlayerSave save, Consumer<ProgressResult> onDone) {
                inner.saveProgress(save, onDone);
            }
        };
        AtomicLong ids = new AtomicLong(9000);
        FakeSceneTables tables = new FakeSceneTables();
        world = new SceneWorld(tables, IDS, sink, wrapped, ids::incrementAndGet, clock, SceneMetrics.noop());
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS);
        scene = world.createScene(1);
    }

    private ScenePlayer enterNew(int sessionId, long playerId) {
        repo.putNewPlayer(playerId, 1);
        return enter(sessionId, playerId);
    }

    private ScenePlayer enterSaved(int sessionId, long playerId, Vec3 position) {
        repo.putSavedPlayer(playerId, 1, 1, position);
        return enter(sessionId, playerId);
    }

    private ScenePlayer enter(int sessionId, long playerId) {
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, scene.sceneId(), 1));
        repo.completeAll();
        return world.playerBySession(new SessionKey(LINK, sessionId));
    }

    private void steps(long frames) {
        for (long i = 0; i < frames; i++) {
            world.step();
        }
    }

    private void moveStart(int sessionId, long playerId, Vec3 at, Rotation rotation, Vec3 velocity, int seq) {
        MoveStartC2S.Builder move = MoveStartC2S.newBuilder()
                .setStartLocation(at.toLocation()).setVelocity(velocity.toVelocity()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveStart(), move.build(), 0);
    }

    private void moveSync(int sessionId, long playerId, Vec3 at, Rotation rotation, Vec3 velocity, int seq) {
        MoveSyncC2S.Builder move = MoveSyncC2S.newBuilder()
                .setLocation(at.toLocation()).setVelocity(velocity.toVelocity()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveSync(), move.build(), 0);
    }

    private void moveStop(int sessionId, long playerId, Vec3 at, Rotation rotation, int seq) {
        MoveStopC2S.Builder move = MoveStopC2S.newBuilder().setEndLocation(at.toLocation()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveStop(), move.build(), 0);
    }

    private void forward(int sessionId, long playerId, int messageId, Message body, long requestId) {
        forward(sessionId, playerId, messageId, body.toByteString(), requestId);
    }

    private void forward(int sessionId, long playerId, int messageId, ByteString body, long requestId) {
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setMessageId(messageId)
                .setBody(body)
                .setRequestId(requestId)
                .build());
    }

    private List<ActorBaseAttributesS2C> syncsTo(int sessionId) {
        List<ActorBaseAttributesS2C> out = new ArrayList<>();
        for (MessageContent content : sink.to(LINK, sessionId)) {
            if (content.getMessageId() == IDS.syncBaseAttribute()) {
                try {
                    out.add(ActorBaseAttributesS2C.parseFrom(content.getSerializedMessage()));
                } catch (InvalidProtocolBufferException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    private ActorBaseAttributesS2C lastSyncTo(int sessionId) {
        List<ActorBaseAttributesS2C> syncs = syncsTo(sessionId);
        assertThat(syncs).isNotEmpty();
        return syncs.getLast();
    }

    private static int id(String movementMethod) {
        return Contracts.REGISTRY.requireId("SceneMovementClientPlayer", movementMethod);
    }

    private static int syncId(String syncMethod) {
        return Contracts.REGISTRY.requireId("ScenePlayerSync", syncMethod);
    }
}
