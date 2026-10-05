package com.game.scene.world;

import static com.game.scene.world.ChannelPlanApplyTest.A;
import static com.game.scene.world.ChannelPlanApplyTest.B;
import static com.game.scene.world.ChannelPlanApplyTest.C;
import static com.game.scene.world.ChannelPlanApplyTest.active;
import static com.game.scene.world.ChannelPlanApplyTest.draining;
import static com.game.scene.world.SceneWorldTest.assertLocation;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 排空与同节点改派、进场重定向（批次 5.1，scene-channels-spec §4.10.3、§4.10.4、§9.4 ChannelDrainTest / EnterRedirectTest）：
 * 改派下行顺序 51 → 79 → 21 → 47、同图保留坐标、位置记录与组队跟随钩子被调用；同图优先、没有就回落本节点默认大世界（落出生点）、
 * 都没有原地不动（blocked）；有在途进场不销毁、人数为 0 才销毁；加载完成时目标在排空 → 进兄弟频道。
 */
class ChannelDrainTest {

    private static final long LINK = 5;
    private static final long D = 0x8000_0000_0000_0A04L;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SimpleMeterRegistry meters;
    private SceneWorld world;
    /** 位置记录写口收到的「进入了哪个场景」（player_id → scene_id 按调用顺序）。 */
    private final List<long[]> entered = new ArrayList<>();
    /** 组队跟随钩子被调的玩家（按调用顺序）。 */
    private final List<Long> followChecks = new ArrayList<>();
    private long version;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        meters = new SimpleMeterRegistry();
        PlayerLocations locations = new PlayerLocations() {
            @Override
            public void entered(ScenePlayer player) {
                entered.add(new long[] {player.playerId(), player.scene().sceneId()});
            }

            @Override
            public void disconnected(ScenePlayer player) {
            }

            @Override
            public void loggedOut(ScenePlayer player) {
            }

            @Override
            public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
            }

            @Override
            public void refresh(Collection<ScenePlayer> players) {
            }
        };
        TeamFollow follow = (w, player) -> followChecks.add(player.playerId());
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(1000)::incrementAndGet,
                new ManualClock(), new SceneMetrics(meters), PlayerInitializer.NONE, PlayerSnapshots.NONE, locations,
                follow);
    }

    private void plan(com.game.api.proto.WorldChannel... records) {
        assertThat(world.applyChannelPlan(++version, List.of(records))).isTrue();
    }

    private void enter(int session, long playerId, long sceneId, Vec3 at) {
        repo.putSavedPlayer(playerId, 1, 1, at);
        world.onPlayerEnter(LINK, enterFrame(session, playerId, sceneId, 1));
        repo.completeAll();
    }

    private long entityOf(long playerId) {
        return world.playerById(playerId).entity();
    }

    @Test
    void 同图改派_旁人收到51_本人79_21_47_坐标保留_位置记录与组队跟随被调用_空了销毁() throws Exception {
        Vec3 here = new Vec3(100, 100, 0);
        plan(active(A, 1), active(B, 1));
        enter(11, 1001, A, here);
        enter(13, 1003, A, here);
        enter(12, 1002, B, here);
        long e1 = entityOf(1001);
        long e2 = entityOf(1002);
        long e3 = entityOf(1003);
        sink.clear();
        entered.clear();
        followChecks.clear();

        plan(draining(A, 1), active(B, 1));

        // 1001 先走（进场序）：1003 收到它的 51；1001 收到 B 的 79、自己的 21、看得见的 1002 的 47；1002 收到 1001 的 21
        List<MessageContent> to1001 = sink.to(LINK, 11);
        assertThat(to1001).extracting(MessageContent::getMessageId).startsWith(79, 21, 47);
        assertThat(EnterSceneS2C.parseFrom(to1001.get(0).getSerializedMessage()).getSceneInfo().getSceneId()).isEqualTo(B);
        ActorCreateS2C self = ActorCreateS2C.parseFrom(to1001.get(1).getSerializedMessage());
        assertThat(self.getEntity()).isEqualTo(e1);
        assertLocation(self, 100, 100, 0);
        assertThat(ActorListCreateS2C.parseFrom(to1001.get(2).getSerializedMessage()).getActorListList())
                .extracting(ActorCreateS2C::getEntity).containsExactly(e2);

        List<MessageContent> to1003 = sink.to(LINK, 13);
        assertThat(to1003).extracting(MessageContent::getMessageId).containsExactly(51, 79, 21, 47);
        assertThat(ActorDestroyS2C.parseFrom(to1003.get(0).getSerializedMessage()).getEntity()).isEqualTo(e1);
        assertThat(ActorListCreateS2C.parseFrom(to1003.get(3).getSerializedMessage()).getActorListList())
                .extracting(ActorCreateS2C::getEntity).containsExactlyInAnyOrder(e1, e2);
        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(21, 21);

        assertThat(entered).extracting(e -> e[0], e -> e[1]).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1001L, B), org.assertj.core.groups.Tuple.tuple(1003L, B));
        assertThat(followChecks).containsExactly(1001L, 1003L);
        assertThat(world.sceneById(A)).isNull();
        assertThat(world.sceneById(B).playerCount()).isEqualTo(3);
        assertThat(world.playerById(1003).entity()).isEqualTo(e3);
        assertThat(repo.saves()).as("同进程改派：不存盘、不动归属").isEmpty();
        assertThat(repo.releases()).isEmpty();
        assertThat(relocations("same_map")).isEqualTo(2);
    }

    @Test
    void 同图有多个兄弟_按人数摊开_并列取scene_id小的() {
        plan(active(A, 1), active(B, 1), active(C, 1));
        enter(11, 1001, A, Vec3.ORIGIN);
        enter(12, 1002, A, Vec3.ORIGIN);
        enter(13, 1003, A, Vec3.ORIGIN);

        plan(draining(A, 1), active(B, 1), active(C, 1));

        // B、C 都空，并列取 scene_id 小的 B；B 有 1 人后 C 更空；再并列取 B
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(B);
        assertThat(world.playerById(1002).scene().sceneId()).isEqualTo(C);
        assertThat(world.playerById(1003).scene().sceneId()).isEqualTo(B);
    }

    @Test
    void 同图没有承载中的频道_回落本节点默认大世界_落出生点() throws Exception {
        plan(active(A, 1), active(C, 2));
        repo.putSavedPlayer(1001, 1, 2, new Vec3(5, 5, 0));
        world.onPlayerEnter(LINK, enterFrame(11, 1001, C, 1));
        repo.completeAll();
        sink.clear();

        plan(active(A, 1), draining(C, 2));   // 图 2 的唯一频道被排空（如孤儿图）

        List<MessageContent> to1001 = sink.to(LINK, 11);
        assertThat(to1001).extracting(MessageContent::getMessageId).containsExactly(79, 21);
        assertThat(EnterSceneS2C.parseFrom(to1001.get(0).getSerializedMessage()).getSceneInfo().getSceneId()).isEqualTo(A);
        assertLocation(ActorCreateS2C.parseFrom(to1001.get(1).getSerializedMessage()), 180, 200, 0);
        assertThat(world.sceneById(C)).isNull();
        assertThat(relocations("default_world")).isEqualTo(1);
    }

    @Test
    void 两者都没有_原地不动计blocked_之后有了兄弟就改派() {
        plan(active(A, 1));
        enter(11, 1001, A, Vec3.ORIGIN);
        enter(12, 1002, A, Vec3.ORIGIN);

        plan(draining(A, 1));   // 默认大世界就是图 1，本节点没有别的图 1 频道
        world.drainStep();

        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(A);
        assertThat(world.sceneById(A)).as("有人不销毁").isNotNull();
        assertThat(relocations("blocked")).as("两次推进 × 两个人").isEqualTo(4);

        plan(draining(A, 1), active(B, 1));

        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(B);
        assertThat(world.playerById(1002).scene().sceneId()).isEqualTo(B);
        assertThat(world.sceneById(A)).isNull();
        assertThat(relocations("same_map")).isEqualTo(2);
    }

    @Test
    void 在途进场挡住销毁_加载完成时目标在排空_改进兄弟频道_79给兄弟的号() throws Exception {
        plan(active(A, 1), active(B, 1));
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, A, 1));   // 分配时目录还没报排空

        plan(draining(A, 1), active(B, 1));
        assertThat(world.sceneById(A)).as("没人但有在途进场：不销毁").isNotNull();
        world.drainStep();
        assertThat(world.sceneById(A)).isNotNull();

        repo.completeAll();

        List<MessageContent> to1001 = sink.to(LINK, 11);
        assertThat(to1001).extracting(MessageContent::getMessageId).containsExactly(79, 21);
        assertThat(EnterSceneS2C.parseFrom(to1001.get(0).getSerializedMessage()).getSceneInfo().getSceneId()).isEqualTo(B);
        assertThat(sink.results()).extracting(r -> r.tipId()).containsExactly(0);
        assertThat(entered).extracting(e -> e[1]).containsExactly(B);
        assertThat(relocations("enter_redirect")).isEqualTo(1);

        world.drainStep();
        assertThat(world.sceneById(A)).isNull();
    }

    @Test
    void 加载完成时目标在排空且没有兄弟_进排空中的这个_随后有兄弟就被改派() {
        plan(active(A, 1));
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, A, 1));
        plan(draining(A, 1));

        repo.completeAll();
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(A);

        plan(draining(A, 1), active(D, 1));
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(D);
        assertThat(world.sceneById(A)).isNull();
    }

    @Test
    void 目标频道已排空销毁_进场回3023() {
        plan(active(A, 1), active(B, 1));
        plan(draining(A, 1), active(B, 1));   // A 空着：立即销毁
        repo.putNewPlayer(1001, 1);

        world.onPlayerEnter(LINK, enterFrame(11, 1001, A, 1));

        assertThat(sink.results()).extracting(r -> r.tipId()).containsExactly(3023);
    }

    private double relocations(String result) {
        return meters.get("xm.scene.channel.relocations").tag("result", result).counter().count();
    }
}
