package com.game.scene.world;

import static com.game.scene.world.ChannelPlanApplyTest.active;
import static com.game.scene.world.ChannelPlanApplyTest.draining;
import static com.game.scene.world.InstanceFixture.A;
import static com.game.scene.world.InstanceFixture.B;
import static com.game.scene.world.InstanceFixture.C;
import static com.game.scene.world.InstanceFixture.LINK;
import static com.game.scene.world.InstanceFixture.LOCAL_NODE;
import static com.game.scene.world.InstanceFixture.M;
import static com.game.scene.world.InstanceFixture.SAVED;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.SceneEntry;
import com.game.proto.SceneInfoComp;
import com.game.scene.testing.FakeInstanceIds;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.RecordingSink.EnterResult;
import com.game.scene.world.PlayerRepository.LoadResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 实例的生命周期（批次 5.3，dungeon-mirror-spec §6.8、§6.10、§6.11、§6.13、§12.2 第 8–11 条）：建实例的校验；按种类的空置超时 → 回收宽限
 * （目录报 draining）→ 宽限满且仍空、没有在途进场才销毁；宽限内在途进场到达即复活；源频道销毁时镜像级联（同图改派、坐标保留）；
 * dev 管理口建副本与显式销毁；随机交错下「有人或有在途进场的实例从不被销毁」。时钟是手动的，每秒维护由测试显式推进。
 */
class InstanceLifecycleTest {

    private static final int ENTER_FAILED = 3023;
    private static final int PARAM_ERROR = 3005;
    private static final int NOT_FOUND = 3000;
    private static final int SERVICE_UNAVAILABLE = 1003;

    private InstanceFixture f;
    private ScenePlayer a;
    private ScenePlayer b;

    @BeforeEach
    void setUp() {
        f = new InstanceFixture();
        a = f.enter(11, 1001, A);
        b = f.enter(12, 1002, A);
    }

    /** a 建镜像后回源频道：镜像空了，计时从此刻起。 */
    private Scene emptyMirror() {
        Scene mirror = f.createMirror(a);
        f.enterScene(a, 0, A, 0, 0, 2);
        assertThat(mirror.playerCount()).isZero();
        assertThat(mirror.emptySinceKnown()).isTrue();
        return mirror;
    }

    private SceneEntry entryOf(long sceneId) {
        return f.world.sceneEntries().stream().filter(e -> e.getSceneId() == sceneId).findFirst().orElse(null);
    }

    /** 新的老号对某场景发起进场（加载挂起，不完成）。 */
    private void startEnter(int sessionId, long playerId, long sceneId) {
        f.repo.putSavedPlayer(playerId, 1, 1, SAVED);
        f.world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, sceneId, 1));
    }

    // ------------------------------------------------------------------ 8 空闲回收

    @Test
    void 镜像空置差1毫秒满30秒仍承载_满了进入宽限并补发目录_宽限差1毫秒保留_满了销毁() {
        Scene mirror = emptyMirror();
        long id = mirror.sceneId();
        f.tick(Duration.ofMillis(29_999));
        assertThat(mirror.draining()).isFalse();
        int published = f.directoryPublishes.get();

        f.tick(Duration.ofMillis(1));

        assertThat(mirror.draining()).isTrue();
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
        assertThat(f.directoryPublishes.get()).as("进入宽限立即补发目录").isGreaterThan(published);
        assertThat(entryOf(id).getDraining()).as("目录报 draining：scene-manager 不再把新玩家导向它").isTrue();
        assertThat(f.lifecycle("mirror", "reclaim_started")).isEqualTo(1);
        assertThat(f.instances("mirror", "reclaiming")).isEqualTo(1);
        assertThat(f.instances("mirror", "active")).isZero();

        f.tick(Duration.ofMillis(29_999));
        assertThat(f.scene(id)).as("宽限差 1 毫秒").isSameAs(mirror);

        f.tick(Duration.ofMillis(1));
        assertThat(f.scene(id)).isNull();
        assertThat(entryOf(id)).as("销毁后目录里没有它").isNull();
        assertThat(f.lifecycle("mirror", "destroyed_idle")).isEqualTo(1);
        assertThat(f.instances("mirror", "reclaiming")).isZero();
        assertThat(f.instances("mirror", "active")).isZero();
    }

    @Test
    void 计时从最后一人离开起_中途有人进入清零() {
        Scene mirror = f.createMirror(a);
        f.enterScene(b, 0, mirror.sceneId(), 0, 0, 1);
        f.tickSeconds(20);
        f.enterScene(a, 0, A, 0, 0, 2);   // 还剩 b：不计时
        f.tickSeconds(20);
        assertThat(mirror.emptySinceKnown()).isFalse();
        f.enterScene(b, 0, A, 0, 0, 3);   // 最后一人离开：从此刻起
        f.tickSeconds(29);
        assertThat(mirror.draining()).isFalse();
        f.enterScene(a, 0, mirror.sceneId(), 0, 0, 4);   // 有人进入：清零
        f.tickSeconds(10);
        f.enterScene(a, 0, A, 0, 0, 5);
        f.tickSeconds(29);
        assertThat(mirror.draining()).as("重新从 a 第二次离开起算").isFalse();

        f.tickSeconds(1);

        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
    }

    @Test
    void 在途进场挡住计时与销毁_宽限内到达复活并进入() {
        Scene mirror = emptyMirror();
        f.tickSeconds(30);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
        startEnter(21, 1003, mirror.sceneId());   // 登录重连 / 5.2 交出：scene-manager 分配时它还在
        f.tickSeconds(60);
        assertThat(f.scene(mirror.sceneId())).as("在途进场挡住销毁").isSameAs(mirror);
        int published = f.directoryPublishes.get();

        f.repo.completeAll();

        ScenePlayer c = f.world.playerById(1003);
        assertThat(c.scene()).isSameAs(mirror);
        assertThat(mirror.draining()).isFalse();
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.NONE);
        assertThat(f.lifecycle("mirror", "revived")).isEqualTo(1);
        assertThat(f.directoryPublishes.get()).as("复活立即补发目录").isGreaterThan(published);
        assertThat(entryOf(mirror.sceneId()).getDraining()).isFalse();
        assertThat(f.instances("mirror", "active")).isEqualTo(1);
        assertThat(f.instances("mirror", "reclaiming")).isZero();
        assertThat(f.enterNotices(c)).extracting(SceneInfoComp::getSceneId).containsExactly(mirror.sceneId());
        assertThat(c.position()).isEqualTo(SAVED);
    }

    @Test
    void 宽限内在途进场初始化失败_不复活_实例照常回收() {
        Scene mirror = emptyMirror();
        f.tickSeconds(30);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
        startEnter(21, 1003, mirror.sceneId());
        f.initializer.set(p -> {
            throw new IllegalStateException("存档损坏");
        });

        f.repo.completeAll();

        assertThat(f.world.playerById(1003)).as("进场失败").isNull();
        assertThat(mirror.drainCause()).as("初始化失败不复活：空实例不被反复重连续命（评审意见）").isEqualTo(DrainCause.IDLE);
        assertThat(f.lifecycle("mirror", "revived")).isZero();
        f.initializer.set(PlayerInitializer.NONE);
        f.tickSeconds(31);
        assertThat(f.scene(mirror.sceneId())).as("宽限满了照常销毁").isNull();
    }

    @Test
    void 在途进场挡住空置计时() {
        Scene mirror = emptyMirror();
        f.tickSeconds(10);
        startEnter(21, 1003, mirror.sceneId());

        f.tickSeconds(120);

        assertThat(mirror.draining()).isFalse();
        assertThat(mirror.emptySinceKnown()).isFalse();
    }

    @Test
    void 宽限之后才到的进场_场景已不在_进场失败3023() {
        Scene mirror = emptyMirror();
        f.tickSeconds(60);
        assertThat(f.scene(mirror.sceneId())).isNull();

        startEnter(21, 1003, mirror.sceneId());

        assertThat(f.sink.results()).containsExactly(new EnterResult(LINK, 21, 1003, 1, ENTER_FAILED));
        assertThat(f.world.playerById(1003)).isNull();
    }

    @Test
    void 断线后30秒内重连_回到原镜像() {
        Scene mirror = f.createMirror(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001).toBuilder().setVoluntary(false).build());
        assertThat(mirror.playerCount()).isZero();
        f.tickSeconds(20);

        startEnter(31, 1001, mirror.sceneId());
        f.repo.completeAll();

        ScenePlayer back = f.world.playerById(1001);
        assertThat(back.scene()).isSameAs(mirror);
        assertThat(back.position()).isEqualTo(SAVED);
        assertThat(mirror.draining()).isFalse();
    }

    @Test
    void 宽限中发现有人_纵深防御按复活处理() {
        Scene mirror = emptyMirror();
        f.tickSeconds(30);
        f.world.switchScene(b, mirror);   // 绕过 63 / 跟随的闸直接换入（正常不会出现）

        f.tick(Duration.ofSeconds(1));

        assertThat(mirror.draining()).isFalse();
        assertThat(f.lifecycle("mirror", "revived")).isEqualTo(1);
        f.tickSeconds(60);
        assertThat(f.scene(mirror.sceneId())).as("有人不回收").isSameAs(mirror);
    }

    @Test
    void 镜像超时为0回落副本超时() {
        InstanceFixture g = new InstanceFixture(InstanceFixture.Settings.DEFAULT.timeouts(Duration.ZERO,
                Duration.ofSeconds(300)));
        ScenePlayer x = g.enter(11, 1001, A);
        Scene mirror = g.createMirror(x);
        g.enterScene(x, 0, A, 0, 0, 2);

        g.tickSeconds(299);
        assertThat(mirror.draining()).isFalse();
        g.tickSeconds(1);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
    }

    @Test
    void 两种超时都为0_永不回收() {
        InstanceFixture g = new InstanceFixture(InstanceFixture.Settings.DEFAULT.timeouts(Duration.ZERO, Duration.ZERO));
        ScenePlayer x = g.enter(11, 1001, A);
        Scene mirror = g.createMirror(x);
        g.enterScene(x, 0, A, 0, 0, 2);
        long dungeon = g.createDungeon(1).getSceneId();

        g.tick(Duration.ofHours(2));

        assertThat(mirror.draining()).isFalse();
        assertThat(g.scene(dungeon).draining()).isFalse();
    }

    @Test
    void 副本按300秒回收_建好没人进也计时() {
        long id = f.createDungeon(1).getSceneId();
        Scene dungeon = f.scene(id);
        assertThat(dungeon.emptySinceKnown()).isTrue();

        f.tick(Duration.ofMillis(299_999));
        assertThat(dungeon.draining()).isFalse();
        f.tick(Duration.ofMillis(1));
        assertThat(dungeon.drainCause()).isEqualTo(DrainCause.IDLE);
        assertThat(f.lifecycle("dungeon", "reclaim_started")).isEqualTo(1);
        f.tick(Duration.ofSeconds(30));
        assertThat(f.scene(id)).isNull();
        assertThat(f.lifecycle("dungeon", "destroyed_idle")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 9 随机交错

    @Test
    void 随机交错2000轮_有人或有在途进场的实例从不被销毁_目录与指标与本地一致() {
        SplittableRandom random = new SplittableRandom(20_261_005L);
        InstanceFixture g = new InstanceFixture(InstanceFixture.Settings.DEFAULT.caps(200, 100));
        List<ScenePlayer> players = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            players.add(g.enter(100 + i, 5000 + i, i % 2 == 0 ? A : B));
        }
        record Pending(int session, long playerId, long sceneId) {
        }
        List<Pending> pendings = new ArrayList<>();
        int nextSession = 1000;
        long nextPlayer = 9000;
        for (int round = 0; round < 2000; round++) {
            List<Long> instanceIds = g.world.sceneEntries().stream()
                    .filter(e -> e.getKind() != ChannelKind.CHANNEL_KIND_WORLD).map(SceneEntry::getSceneId).toList();
            int op = random.nextInt(8);
            ScenePlayer p = players.get(random.nextInt(players.size()));
            switch (op) {
                case 0 -> {   // 建镜像（不在主世界频道里会被 3005 挡住）
                    g.requestMirror(p, M);
                    if (g.ids.pendingCount() > 0) {
                        g.ids.take().issued(LOCAL_NODE, g.nextInstanceId());
                    }
                }
                case 1 -> {   // 按号进实例（宽限 / 排空中的回 3023）
                    if (!instanceIds.isEmpty()) {
                        g.enterScene(p, 0, instanceIds.get(random.nextInt(instanceIds.size())), 0, 0, round);
                    }
                }
                case 2 -> g.enterScene(p, 0, random.nextBoolean() ? A : B, 0, 0, round);   // 回主世界频道
                case 3 -> {   // 在途进场开始（指向某个还在的实例）
                    if (!instanceIds.isEmpty()) {
                        long target = instanceIds.get(random.nextInt(instanceIds.size()));
                        int session = nextSession++;
                        long pid = nextPlayer++;
                        g.repo.putSavedPlayer(pid, 1, 1, SAVED);
                        g.world.onPlayerEnter(LINK, enterFrame(session, pid, target, 1));
                        pendings.add(new Pending(session, pid, target));
                    }
                }
                case 4 -> {   // 在途进场到达：进去后马上走（不留在玩家池里）
                    if (!pendings.isEmpty()) {
                        Pending done = pendings.remove(0);
                        FakePlayerRepository.PendingLoad load = g.repo.takeLoad();
                        assertThat(load.playerId()).isEqualTo(done.playerId());
                        load.complete(new LoadResult.Found(g.repo.putSavedPlayer(done.playerId(), 1, 1, SAVED)));
                        ScenePlayer arrived = g.world.playerById(done.playerId());
                        assertThat(arrived).as("第 %d 轮：在途进场的目标没被销毁", round).isNotNull();
                        if (random.nextBoolean()) {
                            g.world.onPlayerLeave(LINK, SceneWorldTest.leave(done.session(), done.playerId()));
                        } else {
                            players.add(arrived);
                        }
                    }
                }
                case 5 -> {   // 在途进场取消（加载中离开）
                    if (!pendings.isEmpty()) {
                        Pending cancelled = pendings.remove(0);
                        g.world.onPlayerLeave(LINK, SceneWorldTest.leave(cancelled.session(), cancelled.playerId()));
                        g.repo.takeLoad().complete(new LoadResult.NotFound());
                    }
                }
                default -> g.tick(Duration.ofMillis(random.nextInt(8_000)));
            }
            // 不变量
            for (ScenePlayer q : players) {
                assertThat(g.world.sceneById(q.scene().sceneId())).as("第 %d 轮：有人的场景没被销毁", round)
                        .isSameAs(q.scene());
            }
            for (Pending pending : pendings) {
                assertThat(g.world.sceneById(pending.sceneId())).as("第 %d 轮：有在途进场的场景没被销毁", round).isNotNull();
            }
            Set<Long> entries = g.world.sceneEntries().stream().map(SceneEntry::getSceneId).collect(Collectors.toSet());
            int active = 0;
            int reclaiming = 0;
            int draining = 0;
            Set<Long> instances = new HashSet<>();
            for (SceneEntry e : g.world.sceneEntries()) {
                Scene s = g.world.sceneById(e.getSceneId());
                assertThat(s).as("第 %d 轮：目录里的场景都在本地", round).isNotNull();
                if (!s.kind().isInstance()) {
                    continue;
                }
                instances.add(s.sceneId());
                if (!s.draining()) {
                    active++;
                } else if (s.drainCause() == DrainCause.IDLE) {
                    reclaiming++;
                } else {
                    draining++;
                }
            }
            assertThat(entries).hasSize(g.world.sceneEntries().size());
            assertThat(instances).hasSize(g.world.instanceCount());
            assertThat(g.instances("mirror", "active") + g.instances("dungeon", "active"))
                    .as("第 %d 轮：xm_scene_instances 与本地一致", round).isEqualTo(active);
            assertThat(g.instances("mirror", "reclaiming")).as("第 %d 轮", round).isEqualTo(reclaiming);
            assertThat(g.instances("mirror", "draining")).as("第 %d 轮", round).isEqualTo(draining);
        }
        assertThat(g.lifecycle("mirror", "created")).as("随机过程里确实建过镜像").isPositive();
        assertThat(g.lifecycle("mirror", "destroyed_idle")).as("也确实回收过").isPositive();
        assertThat(g.lifecycle("mirror", "created"))
                .as("勾稽：建的 = 销毁的 + 还在的").isEqualTo(g.lifecycle("mirror", "destroyed_idle") + g.world.instanceCount());
    }

    // ------------------------------------------------------------------ 10 级联

    @Test
    void 源频道被销毁_镜像转级联_居民同图改派坐标保留_空了销毁_无tip() {
        Scene mirror = f.createMirror(a);
        f.enterScene(b, 0, mirror.sceneId(), 0, 0, 1);
        f.sink.clear();

        f.plan(draining(A, 1), active(B, 1), active(C, 2));   // A 空了，当场销毁

        assertThat(f.scene(A)).isNull();
        assertThat(f.scene(mirror.sceneId())).as("同一次推进里改派并销毁").isNull();
        assertThat(a.scene()).isSameAs(f.scene(B));
        assertThat(b.scene()).isSameAs(f.scene(B));
        assertThat(a.position()).isEqualTo(SAVED);
        assertThat(f.pushedTips(a)).isEmpty();
        assertThat(f.enterNotices(a)).extracting(SceneInfoComp::getSceneId).containsExactly(B);
        assertThat(f.lifecycle("mirror", "cascade_started")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "destroyed_cascade")).isEqualTo(1);
        assertThat(entryOf(mirror.sceneId())).isNull();
    }

    @Test
    void 空的回收宽限中的镜像_源销毁时同样转级联销毁() {
        Scene mirror = emptyMirror();
        f.tickSeconds(30);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
        f.enterScene(a, 0, B, 0, 0, 3);
        f.enterScene(b, 0, B, 0, 0, 4);

        f.plan(draining(A, 1), active(B, 1), active(C, 2));

        assertThat(f.scene(mirror.sceneId())).isNull();
        assertThat(f.lifecycle("mirror", "destroyed_cascade")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "destroyed_idle")).isZero();
    }

    @Test
    void 孤儿图的源_级联改派落默认大世界出生点() {
        ScenePlayer c = f.enter(13, 1003, C);
        Scene mirror = f.createMirror(c);
        assertThat(mirror.configId()).isEqualTo(2);

        f.plan(active(A, 1), active(B, 1));   // 图 2 整个从计划里消失：C 计划外 → 排空 → 空了销毁 → 镜像级联

        assertThat(f.scene(mirror.sceneId())).isNull();
        assertThat(c.scene().configId()).isEqualTo(1);
        assertThat(c.scene().isWorldChannel()).isTrue();
        assertThat(c.position()).isEqualTo(FakeSceneTables.SPAWN_1);
    }

    @Test
    void 源排空后又改回承载中_镜像不动() {
        Scene mirror = f.createMirror(a);
        startEnter(21, 1003, A);   // 在途进场挡住 A 的销毁
        f.plan(draining(A, 1), active(B, 1), active(C, 2));
        assertThat(f.scene(A).draining()).isTrue();

        f.plan(active(A, 1), active(B, 1), active(C, 2));
        f.tickSeconds(2);

        assertThat(f.scene(A).draining()).isFalse();
        assertThat(mirror.draining()).isFalse();
        assertThat(a.scene()).isSameAs(mirror);
        assertThat(f.lifecycle("mirror", "cascade_started")).isZero();
    }

    @Test
    void 兜底检查_源不在本地的镜像也级联() {
        Scene orphan = f.world.addScene(InstanceSpec.mirror(0x8000_0000_0000_2001L, 1, 0x8000_0000_0000_DEADL, M, 1001)
                .toInfo(), SceneKind.MIRROR, 0x8000_0000_0000_DEADL);
        f.world.switchScene(a, orphan);

        f.tick(Duration.ofSeconds(1));

        assertThat(f.scene(orphan.sceneId())).isNull();
        assertThat(a.scene().isWorldChannel()).isTrue();
        assertThat(a.scene().configId()).isEqualTo(1);
        assertThat(a.position()).isEqualTo(SAVED);
        assertThat(f.lifecycle("mirror", "cascade_started")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "destroyed_cascade")).isEqualTo(1);
    }

    @Test
    void 级联中有冻结的居民_跳过下一秒再试() {
        Scene mirror = f.createMirror(a);
        WorldTestAccess.startFreezing(a);
        f.plan(draining(A, 1), active(B, 1), active(C, 2));
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.CASCADE);
        assertThat(f.scene(mirror.sceneId())).as("冻结中的不改派，镜像留着").isSameAs(mirror);

        WorldTestAccess.clearSwitch(a);
        f.tick(Duration.ofSeconds(1));

        assertThat(a.scene()).isSameAs(f.scene(B));
        assertThat(f.scene(mirror.sceneId())).isNull();
    }

    // ------------------------------------------------------------------ 建实例的校验

    @Test
    void 建实例拒绝_号或地图为0_本地重号_源不在或不是主世界或在排空_地图与源不同_副本地图不符_停止接客() {
        assertThat(f.world.createInstance(InstanceSpec.mirror(0, 1, A, M, 1001))).isNull();
        assertThat(f.world.createInstance(InstanceSpec.mirror(f.nextInstanceId(), 0, A, M, 1001))).isNull();
        assertThat(f.world.createInstance(InstanceSpec.mirror(B, 1, A, M, 1001))).as("与频道同号").isNull();
        assertThat(f.world.createInstance(InstanceSpec.mirror(f.nextInstanceId(), 1, 0x8000_0000_0000_0FFFL, M, 1001)))
                .as("源不在本地").isNull();
        assertThat(f.world.createInstance(InstanceSpec.mirror(f.nextInstanceId(), 2, A, M, 1001))).as("地图与源不同").isNull();
        long dungeon = f.createDungeon(1).getSceneId();
        assertThat(f.world.createInstance(InstanceSpec.mirror(f.nextInstanceId(), 17, dungeon, M, 1001)))
                .as("源是副本").isNull();
        assertThat(f.world.createInstance(InstanceSpec.dungeon(f.nextInstanceId(), 18, 1))).as("副本地图不符").isNull();
        assertThat(f.world.createInstance(InstanceSpec.dungeon(f.nextInstanceId(), 17, 9))).as("Dungeon 表没有").isNull();
        f.scene(C).setDraining(true);
        assertThat(f.world.createInstance(InstanceSpec.mirror(f.nextInstanceId(), 2, C, M, 1001))).as("源在排空").isNull();
        assertThat(f.lifecycle("mirror", "rejected")).isEqualTo(7);
        assertThat(f.lifecycle("dungeon", "rejected")).isEqualTo(2);

        f.world.stopAcceptingEnters();
        assertThat(f.world.createInstance(InstanceSpec.dungeon(f.nextInstanceId(), 17, 1))).as("停止接客").isNull();
        assertThat(f.world.instanceCount()).isEqualTo(1);
    }

    @Test
    void 建实例达到每节点上限即拒() {
        InstanceFixture capped = new InstanceFixture(InstanceFixture.Settings.DEFAULT.caps(2, 3));
        assertThat(capped.createDungeon(1).getTipId()).isZero();
        assertThat(capped.createDungeon(2).getTipId()).isZero();

        CreateDungeonInstanceResponse third = capped.createDungeon(3);

        assertThat(third.getTipId()).isEqualTo(ENTER_FAILED);
        assertThat(capped.world.instanceCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 11 副本与 dev 管理口

    @Test
    void 管理口建副本_回号与地图_79带dungeon号_creators空_进入落副本出生点_目录种类DUNGEON() {
        CreateDungeonInstanceResponse created = f.createDungeon(1);

        assertThat(created.getTipId()).isZero();
        assertThat(created.getSceneConfigId()).isEqualTo(17);
        assertThat(created.getSceneNodeId()).isEqualTo(LOCAL_NODE);
        long id = created.getSceneId();
        assertThat(entryOf(id)).isEqualTo(SceneEntry.newBuilder().setSceneId(id).setSceneConfigId(17)
                .setKind(ChannelKind.CHANNEL_KIND_DUNGEON).build());
        assertThat(f.lifecycle("dungeon", "created")).isEqualTo(1);
        assertThat(f.instances("dungeon", "active")).isEqualTo(1);

        f.enterScene(a, 0, id, 0, 0, 3);

        assertThat(f.replies(a)).containsExactly(0);
        assertThat(f.enterNotices(a)).containsExactly(SceneInfoComp.newBuilder().setSceneConfigId(17).setSceneId(id)
                .setDungeonConfigId(1).build());
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_DUNGEON);
        assertThat(a.toSave().sceneConfigId()).as("存盘的地图是副本地图（下次登录由 scene-manager 回落默认主世界）").isEqualTo(17);
        assertThat(f.players(17)).isEqualTo(1);
    }

    @Test
    void 副本里只带副本地图回3023_只带世界地图回那张图的频道出生点() {
        long id = f.createDungeon(1).getSceneId();
        f.enterScene(a, 0, id, 0, 0, 3);
        f.sink.clear();

        f.enterScene(a, 17, 0, 0, 0, 4);
        assertThat(f.replies(a)).containsExactly(ENTER_FAILED);
        assertThat(a.scene().sceneId()).isEqualTo(id);

        f.enterScene(a, 1, 0, 0, 0, 5);
        assertThat(a.scene().isWorldChannel()).isTrue();
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_1);
    }

    @Test
    void 副本里离线_存盘地图是副本地图_下次按默认主世界进场落出生点() {
        long id = f.createDungeon(1).getSceneId();
        f.enterScene(a, 0, id, 0, 0, 3);
        f.leave(a);
        PlayerSave saved = f.repo.saves().get(f.repo.saves().size() - 1);
        assertThat(saved.playerId()).isEqualTo(1001);
        assertThat(saved.sceneConfigId()).as("写回的是副本地图（§5.6、§6.14，不新增持久化字段）").isEqualTo(17);
        assertThat(saved.position()).isEqualTo(FakeSceneTables.SPAWN_DUNGEON);

        // 17 不是世界地图：scene-manager 按地图选时回落默认主世界（SceneAssigner；PARITY ⑦）。地图变了，落图 1 的出生点
        f.repo.putSavedPlayer(1001, 2, 17, saved.position());
        f.world.onPlayerEnter(LINK, enterFrame(31, 1001, A, 2));
        f.repo.completeAll();

        ScenePlayer back = f.world.playerById(1001);
        assertThat(back.scene()).isSameAs(f.scene(A));
        assertThat(back.position()).isEqualTo(FakeSceneTables.SPAWN_1);
    }

    /** 发起一次管理口建副本，返回结果 future（取号在途时未完成）。 */
    private static CompletableFuture<CreateDungeonInstanceResponse> startDungeon(InstanceFixture fixture,
                                                                               int dungeonConfigId) {
        CompletableFuture<CreateDungeonInstanceResponse> out = new CompletableFuture<>();
        fixture.world.createDungeon(dungeonConfigId, out);
        return out;
    }

    @Test
    void 管理口建副本_表里没有回3005_取号失败1003_拒绝或号在别的节点3023_没装配1003() {
        assertThat(startDungeon(f, 9).join().getTipId()).isEqualTo(PARAM_ERROR);
        assertThat(startDungeon(f, 0).join().getTipId()).isEqualTo(PARAM_ERROR);
        assertThat(f.ids.pendingCount()).isZero();

        CompletableFuture<CreateDungeonInstanceResponse> out = startDungeon(f, 1);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        assertThat(create.request()).isEqualTo(new InstanceIds.Request(0, SceneKind.DUNGEON, 0, 17, 0, 1));
        assertThat(out).isNotDone();
        create.complete(new InstanceIds.Result.Failed("超时"));
        assertThat(out.join().getTipId()).isEqualTo(SERVICE_UNAVAILABLE);

        out = startDungeon(f, 1);
        f.ids.take().complete(new InstanceIds.Result.Refused(3005));
        assertThat(out.join().getTipId()).isEqualTo(ENTER_FAILED);

        out = startDungeon(f, 1);
        f.ids.take().issued(LOCAL_NODE + 1, f.nextInstanceId());
        assertThat(out.join().getTipId()).isEqualTo(ENTER_FAILED);
        assertThat(f.world.instanceCount()).isZero();

        InstanceFixture noIds = new InstanceFixture(InstanceFixture.Settings.DEFAULT.withoutIds());
        assertThat(startDungeon(noIds, 1).join().getTipId()).isEqualTo(SERVICE_UNAVAILABLE);
    }

    @Test
    void 管理口建副本_调用方开始前已放弃_不取号() {
        CompletableFuture<CreateDungeonInstanceResponse> out = new CompletableFuture<>();
        out.cancel(false);   // 管理口等超时：逻辑线程积压，任务到这时 HTTP 线程已回了 1003

        f.world.createDungeon(1, out);

        assertThat(f.ids.pendingCount()).as("号都不取").isZero();
        assertThat(f.world.instanceCount()).isZero();
    }

    @Test
    void 管理口建副本_号回来时调用方已放弃_不建_不补发目录() {
        CompletableFuture<CreateDungeonInstanceResponse> out = startDungeon(f, 1);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        out.cancel(false);   // 取号在途时 HTTP 线程超时放弃
        f.directoryPublishes.set(0);

        long id = f.nextInstanceId();
        create.issued(LOCAL_NODE, id);

        assertThat(f.scene(id)).as("回了 1003 就不能再建出副本（D3 不留幽灵）").isNull();
        assertThat(f.world.instanceCount()).isZero();
        assertThat(f.lifecycle("dungeon", "created")).isZero();
        assertThat(f.directoryPublishes.get()).isZero();
    }

    @Test
    void 管理口建副本_放弃恰好落在建好与交回之间_当场销毁_计destroyed_admin() {
        // complete 之前撤掉：模拟 HTTP 线程的 cancel 恰好赶在逻辑线程「检查放弃 → 建 → 交回」的建与交之间
        CompletableFuture<CreateDungeonInstanceResponse> out = new CompletableFuture<>() {
            @Override
            public boolean complete(CreateDungeonInstanceResponse value) {
                cancel(false);
                return super.complete(value);
            }
        };
        f.world.createDungeon(1, out);
        long id = f.nextInstanceId();

        f.ids.take().issued(LOCAL_NODE, id);

        assertThat(out).isCancelled();
        assertThat(f.scene(id)).as("交不回去的副本当场销毁").isNull();
        assertThat(f.world.instanceCount()).isZero();
        assertThat(f.lifecycle("dungeon", "created")).isEqualTo(1);
        assertThat(f.lifecycle("dungeon", "destroyed_admin")).isEqualTo(1);
        assertThat(entryOf(id)).as("目录里也没有").isNull();
    }

    @Test
    void 管理口销毁有人的副本_改派默认大世界出生点_无tip_重复销毁回3000() {
        long id = f.createDungeon(1).getSceneId();
        f.enterScene(a, 0, id, 0, 0, 3);
        f.enterScene(b, 0, id, 0, 0, 4);
        f.sink.clear();

        assertThat(f.world.destroyInstance(id)).isZero();

        assertThat(f.scene(id)).isNull();
        assertThat(a.scene().configId()).isEqualTo(1);
        assertThat(b.scene().configId()).isEqualTo(1);
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_1);
        assertThat(f.pushedTips(a)).isEmpty();
        assertThat(f.enterNotices(a)).hasSize(1);
        assertThat(f.lifecycle("dungeon", "destroyed_admin")).isEqualTo(1);
        assertThat(f.world.destroyInstance(id)).isEqualTo(NOT_FOUND);
    }

    @Test
    void 管理口销毁镜像_居民同图改派坐标保留() {
        Scene mirror = f.createMirror(a);

        assertThat(f.world.destroyInstance(mirror.sceneId())).isZero();

        assertThat(f.scene(mirror.sceneId())).isNull();
        assertThat(a.scene().isWorldChannel()).isTrue();
        assertThat(a.scene().configId()).isEqualTo(1);
        assertThat(a.position()).isEqualTo(SAVED);
        assertThat(f.lifecycle("mirror", "destroyed_admin")).isEqualTo(1);
    }

    @Test
    void 管理口销毁_主世界频道或0回3005_不存在回3000_排空中的再销毁回0() {
        assertThat(f.world.destroyInstance(A)).isEqualTo(PARAM_ERROR);
        assertThat(f.scene(A).draining()).isFalse();
        assertThat(f.world.destroyInstance(0)).isEqualTo(PARAM_ERROR);
        assertThat(f.world.destroyInstance(0x8000_0000_0000_0FFFL)).isEqualTo(NOT_FOUND);

        Scene mirror = f.createMirror(a);
        WorldTestAccess.startFreezing(a);   // 冻结中的居民不改派：实例留在排空中
        assertThat(f.world.destroyInstance(mirror.sceneId())).isZero();
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.ADMIN);
        assertThat(f.world.destroyInstance(mirror.sceneId())).as("幂等").isZero();
        assertThat(f.instances("mirror", "draining")).isEqualTo(1);
    }

    @Test
    void 管理口销毁回收宽限中的实例_不再给复活机会() {
        Scene mirror = emptyMirror();
        f.tickSeconds(30);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);

        assertThat(f.world.destroyInstance(mirror.sceneId())).isZero();

        assertThat(f.scene(mirror.sceneId())).isNull();
        assertThat(f.lifecycle("mirror", "destroyed_admin")).isEqualTo(1);
    }
}
