package com.game.scene.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.proto.MessageContent;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.ReleaseSkillResponse;
import com.game.proto.SkillInterruptedS2C;
import com.game.proto.SkillUsedS2C;
import com.game.proto.Vector3;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.skill.SkillTables.StateCell;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 84 ReleaseSkill 走真实分发，配表用正式表（玩家技能 1 / 2 / 13：1 指定目标、前摇 1s 后摇 1s、可打断、冷却 500ms；
 * 2 范围、无前摇无冷却；13 认不出的目标方式、前摇 0.3s、冷却 2s）。时间用手动时钟推进。
 * A（1001）与 B（1002）在场景 1 出生点（互相看得见），C（1003）在场景 2。
 */
class SkillFeatureTest {

    private static final long LINK = 1;
    private static final int A = 11;
    private static final int B = 12;
    private static final int C = 13;
    private static final int NOTIFY_USED = 70;
    private static final int NOTIFY_INTERRUPTED = 33;
    private static final int RELEASE = 84;
    private static SkillTables shipped;

    private final RecordingSink sink = new RecordingSink();
    private final FakePlayerRepository repo = new FakePlayerRepository();
    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SceneWorld world;
    private ClientRequestHandler handler;
    private Scene scene1;
    private Scene scene2;

    @BeforeAll
    static void loadTables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        shipped = SkillTables.from(ConfigTables.load(dir));
    }

    private void start(SkillTables tables) {
        FakeSceneTables sceneTables = new FakeSceneTables();
        world = new SceneWorld(sceneTables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet, clock,
                SceneMetrics.noop(), player -> { }, PlayerSnapshots.NONE);
        SkillService service = new SkillService(tables, clock, new SceneMetrics(meters), Contracts.IDS);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new SkillFeature(service)));
        scene1 = world.createScene(1);
        scene2 = world.createScene(2);
        enter(A, 1001, scene1);
        enter(B, 1002, scene1);
        enter(C, 1003, scene2);
        sink.clear();
    }

    private void start() {
        start(shipped);
    }

    private void enter(int session, long playerId, Scene scene) {
        repo.putNewPlayer(playerId, 1);
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(session).setPlayerId(playerId).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
    }

    private ScenePlayer player(int session) {
        return WorldTestAccess.player(world, LINK, session);
    }

    private long entity(int session) {
        return player(session).entity();
    }

    /** A 放技能，返回应答的提示码。 */
    private int release(int skill, long target) throws Exception {
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(A)
                .setPlayerId(1001)
                .setMessageId(RELEASE)
                .setBody(ReleaseSkillRequest.newBuilder().setSkillTableId(skill).setTargetId(target)
                        .setPosition(Vector3.newBuilder().setX(1).setY(2).setZ(3)).build().toByteString())
                .setRequestId(9)
                .build());
        MessageContent reply = sink.to(LINK, A).getLast();
        assertThat(reply.getMessageId()).isEqualTo(RELEASE);
        ReleaseSkillResponse response = ReleaseSkillResponse.parseFrom(reply.getSerializedMessage());
        assertThat(response.hasErrorMessage()).isTrue();
        return response.getErrorMessage().getId();
    }

    private double counter(String result) {
        Counter c = meters.find("xm.scene.skill.releases").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    // ------------------------------------------------------------------ 校验

    @Test
    void 技能不存在或未拥有回1001_不广播() throws Exception {
        start();
        assertThat(release(5, entity(B))).isEqualTo(1001);
        assertThat(release(99, entity(B))).isEqualTo(1001);
        assertThat(release(0, entity(B))).isEqualTo(1001);
        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(RELEASE, RELEASE, RELEASE);
        assertThat(sink.messageIdsTo(LINK, B)).isEmpty();
        assertThat(counter("unknown_skill")).isEqualTo(3);
    }

    @Test
    void 目标号为0一律7001_指定目标要是本节点上的玩家_其余方式只要非0() throws Exception {
        start();
        for (int skill : new int[] {1, 2, 13}) {
            assertThat(release(skill, 0)).as("skill %d", skill).isEqualTo(7001);
        }
        assertThat(release(1, 424242)).as("指定目标不存在").isEqualTo(7001);
        assertThat(sink.messageIdsTo(LINK, B)).as("被拒不广播").isEmpty();
        assertThat(player(A).skillState().actionStates()).as("早于状态检查被拒不加战斗状态").isEmpty();
        assertThat(counter("invalid_target")).isEqualTo(4);

        assertThat(release(2, 424242)).as("范围技能只看非 0").isZero();
        assertThat(release(13, 424242)).as("认不出的目标方式只看非 0").isZero();
        clock.advanceMillis(400);
        assertThat(release(1, entity(C))).as("别的场景里的玩家也算（节点内，同基线）").isZero();
        clock.advanceMillis(2_100);
        assertThat(release(1, entity(A))).as("自己也是合法目标").isZero();
    }

    @Test
    void 成功先广播70给自己与看得见的人再回应答_加上战斗状态() throws Exception {
        start();

        assertThat(release(1, entity(B))).isZero();

        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(NOTIFY_USED, RELEASE);
        assertThat(sink.messageIdsTo(LINK, B)).containsExactly(NOTIFY_USED);
        assertThat(sink.messageIdsTo(LINK, C)).as("另一个场景看不见").isEmpty();
        SkillUsedS2C used = SkillUsedS2C.parseFrom(sink.to(LINK, B).getFirst().getSerializedMessage());
        assertThat(used.getEntity()).isEqualTo(entity(A));
        assertThat(used.getTargetEntityList()).containsExactly(entity(B));
        assertThat(used.getSkillTableId()).isEqualTo(1);
        assertThat(used.getPosition()).isEqualTo(Vector3.newBuilder().setX(1).setY(2).setZ(3).build());
        assertThat(player(A).skillState().actionStates()).containsExactly(SkillService.STATE_COMBAT);
        assertThat(counter("ok")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 施法阶段与冷却

    @Test
    void 前摇中_不可打断的回7000_可打断的推33再成功_新施法从头计() throws Exception {
        start();
        assertThat(release(1, entity(B))).isZero();
        clock.advanceMillis(400);
        assertThat(release(2, entity(B))).as("技能 2 不可打断").isEqualTo(7000);
        assertThat(release(13, entity(B))).isEqualTo(7000);
        clock.advanceMillis(300);
        sink.clear();

        assertThat(release(1, entity(B))).as("700ms：冷却 500ms 已过，前摇 1s 还没完，技能 1 可打断").isZero();

        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(NOTIFY_INTERRUPTED, NOTIFY_USED, RELEASE);
        assertThat(sink.messageIdsTo(LINK, B)).containsExactly(NOTIFY_INTERRUPTED, NOTIFY_USED);
        SkillInterruptedS2C interrupted = SkillInterruptedS2C.parseFrom(
                sink.to(LINK, B).getFirst().getSerializedMessage());
        assertThat(interrupted).isEqualTo(SkillInterruptedS2C.newBuilder()
                .setEntity(entity(A)).setSkillTableId(1).build());
        assertThat(meters.find("xm.scene.skill.interrupts").counter().count()).isEqualTo(1);
        assertThat(counter("uninterruptible")).isEqualTo(2);

        clock.advanceMillis(1_400);
        assertThat(release(13, entity(B))).as("2100ms：旧施法（0 起，前摇 + 后摇 2s）早已结束；新施法从 700ms 起算，后摇到 2700ms")
                .isEqualTo(7000);
        clock.advanceMillis(600);
        assertThat(release(13, entity(B))).as("700 + 1s 前摇 + 1s 后摇 = 2700ms 空闲").isZero();
    }

    @Test
    void 打断先于状态表的拒绝_33已发出旧施法已取消() throws Exception {
        Map<Integer, List<StateCell>> action = new HashMap<>();
        action.put(SkillService.ACTION_USE_SKILL, List.of(new StateCell(SkillRules.ACTION_MUTEX, 7777)));
        start(withActionStates(action));
        assertThat(release(1, entity(B))).as("没有任何状态：加上战斗状态，进入前摇").isZero();
        clock.advanceMillis(700);
        sink.clear();

        assertThat(release(1, entity(B))).as("可打断：先推 33、取消旧施法，再被行为互斥拒绝").isEqualTo(7777);

        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(NOTIFY_INTERRUPTED, RELEASE);
        assertThat(sink.messageIdsTo(LINK, B)).containsExactly(NOTIFY_INTERRUPTED);
        assertThat(player(A).skillState().cast()).isNull();
        assertThat(meters.find("xm.scene.skill.interrupts").counter().count()).isEqualTo(1);
        assertThat(release(2, entity(B))).as("旧施法已取消：技能 2 不再被前摇挡，只被互斥挡").isEqualTo(7777);
    }

    @Test
    void 前摇结束进入后摇_后摇里同样不可打断7000_到点结束() throws Exception {
        start();
        assertThat(release(1, entity(B))).isZero();
        clock.advanceMillis(1_000);
        assertThat(release(2, entity(B))).as("前摇刚结束、后摇 1s 开始").isEqualTo(7000);
        clock.advanceMillis(999);
        assertThat(release(13, entity(B))).as("后摇最后 1ms").isEqualTo(7000);
        clock.advanceMillis(1);
        sink.clear();
        assertThat(release(13, entity(B))).as("前摇 + 后摇 = 2s 后空闲").isZero();
        assertThat(sink.messageIdsTo(LINK, A)).doesNotContain(NOTIFY_INTERRUPTED);
    }

    @Test
    void 后摇中技能1可打断_推33() throws Exception {
        start();
        assertThat(release(1, entity(B))).isZero();
        clock.advanceMillis(1_500);
        sink.clear();
        assertThat(release(1, entity(B))).isZero();
        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(NOTIFY_INTERRUPTED, NOTIFY_USED, RELEASE);
    }

    @Test
    void 冷却先于施法阶段判_冷却组按表时长() throws Exception {
        start();
        assertThat(release(1, entity(B))).isZero();
        clock.advanceMillis(300);
        sink.clear();
        assertThat(release(1, entity(B))).as("冷却 500ms 内：7003，不推 33").isEqualTo(7003);
        assertThat(sink.messageIdsTo(LINK, A)).containsExactly(RELEASE);

        clock.advanceMillis(2_000);
        assertThat(release(13, entity(B))).isZero();
        clock.advanceMillis(400);
        assertThat(release(13, entity(B))).as("技能 13 冷却 2s：前摇 0.3s 已过，仍在冷却").isEqualTo(7003);
        clock.advanceMillis(1_599);
        assertThat(release(13, entity(B))).isEqualTo(7003);
        clock.advanceMillis(1);
        assertThat(release(13, entity(B))).isZero();
        assertThat(counter("cooldown")).isEqualTo(3);
    }

    @Test
    void 技能2没有前摇没有冷却_连放都成功() throws Exception {
        start();
        assertThat(release(2, entity(B))).isZero();
        assertThat(release(2, entity(B))).isZero();
        assertThat(release(13, entity(B))).isZero();
    }

    @Test
    void 被拒的施法不记冷却不改阶段() throws Exception {
        start();
        assertThat(release(13, 0)).isEqualTo(7001);
        assertThat(release(13, entity(B))).as("7001 没记冷却").isZero();
    }

    @Test
    void 运行态不持久化_重新进场是新的() throws Exception {
        start();
        assertThat(release(1, entity(B))).isZero();
        world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(A).setPlayerId(1001).setVoluntary(true)
                .build());
        enter(A, 1001, scene1);
        sink.clear();
        assertThat(release(2, entity(B))).isZero();
        assertThat(release(1, entity(B))).as("冷却也清空了").isZero();
    }

    // ------------------------------------------------------------------ 状态表

    @Test
    void 行为互斥表拒绝_回表里的提示码_不广播不记冷却_提示码0按配置错1002() throws Exception {
        Map<Integer, List<StateCell>> action = new HashMap<>();
        action.put(SkillService.ACTION_USE_SKILL, List.of(new StateCell(SkillRules.ACTION_MUTEX, 7777)));
        start(withActionStates(action));

        assertThat(release(2, entity(B))).as("没有任何状态时直接加上战斗状态").isZero();
        assertThat(release(2, entity(B))).as("战斗状态与放技能互斥").isEqualTo(7777);
        assertThat(counter("state_rejected")).isEqualTo(1);

        action.put(SkillService.ACTION_USE_SKILL, List.of(new StateCell(SkillRules.ACTION_MUTEX, 0)));
        start(withActionStates(action));
        assertThat(release(2, entity(B))).isZero();
        sink.clear();
        assertThat(release(2, entity(B))).isEqualTo(1002);
        assertThat(sink.messageIdsTo(LINK, B)).isEmpty();
    }

    private static SkillTables withActionStates(Map<Integer, List<StateCell>> action) {
        Map<Integer, SkillTables.SkillDef> skills = new HashMap<>();
        for (int id : new int[] {1, 2, 13}) {
            skills.put(id, shipped.skill(id));
        }
        return new SkillTables(skills, Map.of(1, 500_000_000L, 5, 2_000_000_000L), action, Map.of(), Map.of());
    }
}
