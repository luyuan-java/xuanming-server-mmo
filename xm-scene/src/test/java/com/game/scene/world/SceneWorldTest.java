package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneEntry;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.proto.MessageContent;
import com.game.proto.Vector3;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.ActorType;
import com.game.proto.EnterSceneS2C;
import com.game.proto.Rotation;
import com.game.proto.SceneInfoComp;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakePlayerRepository.Release;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.testing.RecordingSink.EnterResult;
import com.game.scene.testing.RecordingSink.Kicked;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SceneWorldTest {

    private static final long LINK = 1;
    private static final long OTHER_LINK = 2;
    private static final SceneMessageIds IDS = Contracts.IDS;
    private static final int ENTER_FAILED = 3023;
    private static final int KICKED = 2017;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private SceneWorld world;
    private Scene scene;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        clock = new ManualClock();
        meters = new SimpleMeterRegistry();
        AtomicLong ids = new AtomicLong(1000);
        world = new SceneWorld(new FakeSceneTables(), IDS, sink, repo, ids::incrementAndGet, clock,
                new SceneMetrics(meters));
        scene = world.createScene(1);
    }

    @Test
    void 消息号与契约文档一致() {
        assertThat(IDS.notifyEnterScene()).isEqualTo(79);
        assertThat(IDS.notifyActorCreate()).isEqualTo(21);
        assertThat(IDS.notifyActorListCreate()).isEqualTo(47);
        assertThat(IDS.notifyActorDestroy()).isEqualTo(51);
        assertThat(IDS.notifySceneInfo()).isEqualTo(31);
        assertThat(IDS.notifySkillUsed()).isEqualTo(70);
        assertThat(IDS.notifySkillInterrupted()).isEqualTo(33);
        assertThat(IDS.listSkills()).isEqualTo(77);
        assertThat(IDS.releaseSkill()).isEqualTo(84);
        assertThat(IDS.enterScene()).isEqualTo(63);
        assertThat(IDS.sceneInfoC2S()).isEqualTo(43);
        assertThat(IDS.notifyActorListDestroy()).isEqualTo(64);
        assertThat(IDS.syncBaseAttribute()).isEqualTo(66);
        assertThat(IDS.moveStart()).isEqualTo(134);
        assertThat(IDS.moveSync()).isEqualTo(132);
        assertThat(IDS.moveStop()).isEqualTo(131);
        assertThat(IDS.notifyMoveAck()).isEqualTo(137);
        assertThat(SceneWorld.KICKED_BY_ANOTHER).isEqualTo(KICKED);
    }

    @Test
    void 单人进场_先推79再推21_最后回进场结果并回显epoch() throws Exception {
        repo.putNewPlayer(1001, 7);

        enter(LINK, 11, 1001, scene.sceneId(), 7);

        List<MessageContent> received = sink.to(LINK, 11);
        assertThat(received).extracting(MessageContent::getMessageId).containsExactly(79, 21);
        assertThat(received).allSatisfy(m -> assertThat(m.getId()).isZero());

        EnterSceneS2C enterScene = EnterSceneS2C.parseFrom(received.get(0).getSerializedMessage());
        assertThat(enterScene.hasSceneInfo()).isTrue();
        SceneInfoComp info = enterScene.getSceneInfo();
        assertThat(info.getSceneConfigId()).isEqualTo(1);
        assertThat(info.getSceneId()).isEqualTo(scene.sceneId()).isNotZero();
        assertThat(info.getMirrorConfigId()).isZero();
        assertThat(info.getDungeonConfigId()).isZero();
        assertThat(info.getCreatorsCount()).isZero();

        ActorCreateS2C self = ActorCreateS2C.parseFrom(received.get(1).getSerializedMessage());
        assertThat(self.getEntity()).isNotZero().isNotEqualTo(1001L);
        assertThat(self.getGuid()).isEqualTo(1001L);
        assertThat(self.getActorType()).isEqualTo(ActorType.ACTOR_TYPE_PLAYER);
        assertThat(self.getConfigId()).isZero();
        assertThat(self.getClassId()).isEqualTo(3);
        assertThat(self.getGender()).isEqualTo(1);
        assertThat(self.getAppearanceId()).isEqualTo("look-1001");
        assertLocation(self, 180, 200, 0);
        assertThat(self.getTransform().hasRotation()).isFalse();
        assertThat(self.getTransform().hasScale()).isFalse();

        assertThat(sink.results()).containsExactly(new EnterResult(LINK, 11, 1001, 7, 0));
        assertThat(sink.events().getLast()).isInstanceOf(EnterResult.class);
        assertThat(scene.playerCount()).isEqualTo(1);
        assertThat(repo.releases()).as("进场成功不释放归属").isEmpty();
        assertThat(world.ownedPlayers()).containsExactly(new OwnedPlayer(1001, 7));
    }

    @Test
    void 第二人进场_收到含先到者的47_先到者收到后到者的21() throws Exception {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        long entityA = entityOf(LINK, 11);
        sink.clear();

        enter(LINK, 12, 1002, scene.sceneId(), 1);
        long entityB = entityOf(LINK, 12);

        List<MessageContent> toB = sink.to(LINK, 12);
        assertThat(toB).extracting(MessageContent::getMessageId).containsExactly(79, 21, 47);
        ActorListCreateS2C list = ActorListCreateS2C.parseFrom(toB.get(2).getSerializedMessage());
        assertThat(list.getActorListList()).singleElement().satisfies(actor -> {
            assertThat(actor.getGuid()).isEqualTo(1001L);
            assertThat(actor.getEntity()).isEqualTo(entityA);
            assertThat(actor.getActorType()).isEqualTo(ActorType.ACTOR_TYPE_PLAYER);
            assertLocation(actor, 180, 200, 0);
        });

        List<MessageContent> toA = sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(21);
        ActorCreateS2C newcomer = ActorCreateS2C.parseFrom(toA.get(0).getSerializedMessage());
        assertThat(newcomer.getGuid()).isEqualTo(1002L);
        assertThat(newcomer.getEntity()).isEqualTo(entityB).isNotEqualTo(entityA);

        assertThat(sink.results()).containsExactly(new EnterResult(LINK, 12, 1002, 1, 0));
    }

    @Test
    void 视野外的玩家互相不可见_同图存档坐标沿用() throws Exception {
        repo.putSavedPlayer(1001, 1, 1, new Vec3(100, 100, 0));
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        ActorCreateS2C selfA = ActorCreateS2C.parseFrom(sink.to(LINK, 11).get(1).getSerializedMessage());
        assertLocation(selfA, 100, 100, 0);
        sink.clear();

        enter(LINK, 12, 1002, scene.sceneId(), 1);

        assertThat(sink.messageIdsTo(LINK, 12)).containsExactly(79, 21);
        assertThat(sink.to(LINK, 11)).isEmpty();
    }

    @Test
    void 存档坐标属于别的地图_落到目标场景出生点() throws Exception {
        repo.putSavedPlayer(1001, 1, 2, new Vec3(5, 5, 5));

        enter(LINK, 11, 1001, scene.sceneId(), 1);

        ActorCreateS2C self = ActorCreateS2C.parseFrom(sink.to(LINK, 11).get(1).getSerializedMessage());
        assertLocation(self, 180, 200, 0);
    }

    @Test
    void 离场_视野内他人收到51_离场者收不到_并带epoch写回释放() throws Exception {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 4);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        enter(LINK, 12, 1002, scene.sceneId(), 4);
        long entityB = entityOf(LINK, 12);
        sink.clear();

        world.onPlayerLeave(LINK, leave(12, 1002));

        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(51);
        ActorDestroyS2C destroy = ActorDestroyS2C.parseFrom(sink.to(LINK, 11).get(0).getSerializedMessage());
        assertThat(destroy.getEntity()).isEqualTo(entityB);
        assertThat(sink.to(LINK, 12)).isEmpty();
        assertThat(repo.saves()).containsExactly(new PlayerSave(1002, 4, 1, 1, new Vec3(180, 200, 0)));
        assertThat(world.playerCount()).isEqualTo(1);
        assertThat(scene.playerCount()).isEqualTo(1);
    }

    @Test
    void 换图后离开_最终写回带着换到的地图与坐标() {
        Scene second = world.createScene(2);
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        world.switchScene(world.playerBySession(new SessionKey(LINK, 11)), second);

        world.onPlayerLeave(LINK, leave(11, 1001));

        // 这份写回落库并释放之后，下一次夺权（epoch 2）才能成功，新实例读到的就是它（PlayerStoreSqlTest 覆盖库侧顺序）。
        assertThat(repo.saves()).containsExactly(new PlayerSave(1001, 1, 1, 2, FakeSceneTables.SPAWN_2));
    }

    @Test
    void 库里epoch已更新_旧进场请求被拒_回显请求的epoch() throws Exception {
        repo.putNewPlayer(1001, 5);

        enter(LINK, 11, 1001, scene.sceneId(), 4);

        assertEnterFailed(11, 1001, 4);
    }

    @Test
    void 玩家不存在_进场失败() throws Exception {
        enter(LINK, 11, 1001, scene.sceneId(), 1);

        assertEnterFailed(11, 1001, 1);
    }

    @Test
    void 场景不在本节点_不加载直接失败_释放这次夺得的归属() throws Exception {
        repo.putNewPlayer(1001, 1);

        world.onPlayerEnter(LINK, enterFrame(11, 1001, 424242, 1));

        assertThat(repo.pendingLoads()).isZero();
        assertEnterFailed(11, 1001, 1);
    }

    @Test
    void 旧实例失去归属后新会话在本节点进场_沿用内存状态_踢掉旧会话() throws Exception {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1003, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        long oldEntity = entityOf(LINK, 11);
        enter(LINK, 13, 1003, scene.sceneId(), 1);
        // 旧实例续约失败、租约过期后被强制夺权（epoch 2）。
        repo.putNewPlayer(1001, 2);
        sink.clear();

        enter(LINK, 21, 1001, scene.sceneId(), 2);

        assertThat(sink.messageIdsTo(LINK, 21)).containsExactly(79, 21, 47);
        long newEntity = entityOf(LINK, 21);
        assertThat(newEntity).isNotZero().isNotEqualTo(oldEntity);
        // 视野内的旁观者先看到旧实体消失，再看到新实体出现。
        List<MessageContent> toWatcher = sink.to(LINK, 13);
        assertThat(toWatcher).extracting(MessageContent::getMessageId).containsExactly(51, 21);
        assertThat(ActorDestroyS2C.parseFrom(toWatcher.get(0).getSerializedMessage()).getEntity()).isEqualTo(oldEntity);
        assertThat(ActorCreateS2C.parseFrom(toWatcher.get(1).getSerializedMessage()).getEntity()).isEqualTo(newEntity);
        assertThat(sink.to(LINK, 11)).isEmpty();
        // 旧会话不再悬空：gate 收到踢出通知（23 {2017} 后断开）。
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, 1, KICKED));
        assertThat(sink.results()).containsExactly(new EnterResult(LINK, 21, 1001, 2, 0));
        // 旧实例的 epoch 已过期，不写回。
        assertThat(repo.saves()).isEmpty();

        // 旧会话迟到的离开被忽略，新会话上的玩家不受影响。
        world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(repo.saves()).isEmpty();
        assertThat(world.playerBySession(new SessionKey(LINK, 21))).isNotNull();
        assertThat(world.playerCount()).isEqualTo(2);
    }

    @Test
    void 旧实例失去归属期间别的节点写过库_以库为准_不沿用旧内存() {
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        ScenePlayer old = world.playerBySession(new SessionKey(LINK, 11));
        assertThat(old.wallet().add(0, 777).ok()).isTrue();
        // 别的节点夺权（epoch 2）并写过库（金币 50、换了图），之后又被这次进场夺权（epoch 3）
        PlayerState written = PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder()
                .addBalances(50).addBalances(0).addBalances(0)).build();
        repo.put(new PlayerData(1001, 3, 3, 1, "look-1001", 1, 1, new Vec3(30, 40, 0), written));

        enter(LINK, 21, 1001, scene.sceneId(), 3);

        ScenePlayer now = world.playerBySession(new SessionKey(LINK, 21));
        assertThat(now.wallet().balance(0)).isEqualTo(50);
        assertThat(now.position()).isEqualTo(new Vec3(30, 40, 0));
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, 1, KICKED));
        assertThat(repo.saves()).isEmpty();
    }

    @Test
    void 旧实例上次在线存盘结局不明_以库为准() {
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        ScenePlayer old = world.playerBySession(new SessionKey(LINK, 11));
        old.wallet().add(0, 777);
        assertThat(world.requestSave(old)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        repo.takeProgress().complete(PlayerRepository.ProgressResult.FAILED);
        repo.putNewPlayer(1001, 2);

        enter(LINK, 21, 1001, scene.sceneId(), 2);

        assertThat(world.playerBySession(new SessionKey(LINK, 21)).wallet().balance(0)).isZero();
    }

    @Test
    void 同一会话重复进场_不早退_照样重发79和21_不踢自己() {
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        sink.clear();

        enter(LINK, 11, 1001, scene.sceneId(), 1);

        assertThat(sink.messageIdsTo(LINK, 11)).containsExactly(79, 21);
        assertThat(sink.results()).containsExactly(new EnterResult(LINK, 11, 1001, 1, 0));
        assertThat(sink.kicks()).isEmpty();
        assertThat(world.playerCount()).isEqualTo(1);
        assertThat(scene.playerCount()).isEqualTo(1);
    }

    @Test
    void 加载乱序返回_旧epoch不能顶替本节点上更新的实例() throws Exception {
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));
        world.onPlayerEnter(LINK, enterFrame(12, 1001, scene.sceneId(), 2));
        FakePlayerRepository.PendingLoad older = repo.takeLoad();
        FakePlayerRepository.PendingLoad newer = repo.takeLoad();

        newer.complete(new PlayerRepository.LoadResult.Found(newPlayer(1001, 2)));
        // 旧请求的读发生在 login 自增 epoch 之前，读到的 epoch 与请求一致，但本节点已有 epoch 2 的实例。
        older.complete(new PlayerRepository.LoadResult.Found(newPlayer(1001, 1)));

        assertThat(sink.results()).containsExactly(
                new EnterResult(LINK, 12, 1001, 2, 0),
                new EnterResult(LINK, 11, 1001, 1, ENTER_FAILED));
        assertThat(world.playerBySession(new SessionKey(LINK, 12))).isNotNull();
        assertThat(world.playerBySession(new SessionKey(LINK, 11))).isNull();
    }

    @Test
    void 加载中离开_取消进场_不发任何消息_释放这次夺得的归属() {
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));

        world.onPlayerLeave(LINK, leave(11, 1001));
        repo.completeAll();

        assertThat(sink.events()).isEmpty();
        assertThat(world.playerCount()).isZero();
        assertThat(repo.saves()).isEmpty();
        assertThat(repo.releases()).containsExactly(new Release(1001, 1));
    }

    @Test
    void 同一会话加载中又来一次进场_被取代的那次释放归属() {
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 2));

        assertThat(repo.releases()).containsExactly(new Release(1001, 1));
    }

    @Test
    void 加载失败_进场失败() throws Exception {
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));

        repo.takeLoad().complete(new PlayerRepository.LoadResult.Failed(new IllegalStateException("db down")));

        assertEnterFailed(11, 1001, 1);
    }

    @Test
    void 链路断开_移除该链路全部玩家_其他链路上的旁观者收到51_加载中的进场释放归属() throws Exception {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        enter(OTHER_LINK, 21, 1002, scene.sceneId(), 1);
        world.onPlayerEnter(LINK, enterFrame(12, 1005, scene.sceneId(), 3));
        long entityA = entityOf(LINK, 11);
        sink.clear();

        world.onLinkClosed(LINK);

        assertThat(sink.messageIdsTo(OTHER_LINK, 21)).containsExactly(51);
        assertThat(ActorDestroyS2C.parseFrom(sink.to(OTHER_LINK, 21).get(0).getSerializedMessage()).getEntity())
                .isEqualTo(entityA);
        assertThat(repo.saves()).extracting(PlayerSave::playerId).containsExactly(1001L);
        assertThat(repo.releases()).containsExactly(new Release(1005, 3));
        assertThat(world.playerCount()).isEqualTo(1);

        world.onLinkClosed(LINK);
        assertThat(repo.saves()).hasSize(1);
        assertThat(repo.releases()).hasSize(1);
    }

    @Test
    void 停止接收新玩家后_进场失败() throws Exception {
        repo.putNewPlayer(1001, 1);
        world.stopAcceptingEnters();

        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));

        assertEnterFailed(11, 1001, 1);
    }

    @Test
    void 停服_写回全部在场玩家并清空_加载中的进场释放归属() {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 2);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        enter(LINK, 12, 1002, scene.sceneId(), 2);
        world.onPlayerEnter(LINK, enterFrame(13, 1003, scene.sceneId(), 4));
        sink.clear();

        int saved = world.shutdown();

        assertThat(saved).isEqualTo(2);
        assertThat(repo.saves()).extracting(PlayerSave::playerId).containsExactlyInAnyOrder(1001L, 1002L);
        assertThat(repo.releases()).containsExactly(new Release(1003, 4));
        assertThat(sink.events()).isEmpty();
        assertThat(world.playerCount()).isZero();
        assertThat(scene.playerCount()).isZero();
    }

    @Test
    void 目录快照带场景人数() {
        Scene second = world.createScene(2);
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);

        assertThat(world.sceneEntries()).containsExactly(
                SceneEntry.newBuilder().setSceneId(scene.sceneId()).setSceneConfigId(1).setPlayerCount(1).build(),
                SceneEntry.newBuilder().setSceneId(second.sceneId()).setSceneConfigId(2).setPlayerCount(0).build());
    }

    // ------------------------------------------------------------------ 归属：接管与失去

    @Test
    void 接管请求_持有该epoch的实例写回释放_旁观者收到51_通知gate踢掉旧会话() throws Exception {
        repo.putNewPlayer(1001, 3);
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 3);
        enter(LINK, 12, 1002, scene.sceneId(), 1);
        long entity = entityOf(LINK, 11);
        sink.clear();

        world.onTakeoverRequested(1001, 3);

        assertThat(repo.saves()).containsExactly(new PlayerSave(1001, 3, 1, 1, new Vec3(180, 200, 0)));
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, 3, KICKED));
        assertThat(ActorDestroyS2C.parseFrom(sink.to(LINK, 12).get(0).getSerializedMessage()).getEntity())
                .isEqualTo(entity);
        assertThat(world.playerBySession(new SessionKey(LINK, 11))).isNull();

        world.onTakeoverRequested(1001, 3);
        assertThat(repo.saves()).as("幂等").hasSize(1);
        assertThat(sink.kicks()).hasSize(1);
    }

    @Test
    void 接管请求_epoch不符的实例不动() {
        repo.putNewPlayer(1001, 3);
        enter(LINK, 11, 1001, scene.sceneId(), 3);
        sink.clear();

        world.onTakeoverRequested(1001, 2);
        world.onTakeoverRequested(1001, 4);

        assertThat(sink.events()).isEmpty();
        assertThat(repo.saves()).isEmpty();
        assertThat(world.playerCount()).isEqualTo(1);
    }

    @Test
    void 接管请求_加载中的进场取消并释放_踢掉会话_加载回来丢弃() {
        repo.putNewPlayer(1001, 3);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 3));

        world.onTakeoverRequested(1001, 3);
        repo.completeAll();

        assertThat(repo.releases()).containsExactly(new Release(1001, 3));
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, 3, KICKED));
        assertThat(sink.results()).isEmpty();
        assertThat(world.playerCount()).isZero();
    }

    @Test
    void 续约报告失去归属_实例移除不写回_踢掉会话_epoch不符的不动() {
        repo.putNewPlayer(1001, 3);
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 3);
        enter(LINK, 12, 1002, scene.sceneId(), 1);
        sink.clear();

        world.onOwnershipLost(List.of(new OwnedPlayer(1001, 3), new OwnedPlayer(1002, 9)));

        assertThat(repo.saves()).isEmpty();
        assertThat(sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, 3, KICKED));
        assertThat(world.playerCount()).isEqualTo(1);
        assertThat(world.ownedPlayers()).containsExactly(new OwnedPlayer(1002, 1));
    }

    // ------------------------------------------------------------------ 指标

    @Test
    void 指标_场景配置在线人数_同配置各频道合计_随进场换图离场停服更新_进场失败不计() {
        Scene channel2 = world.createScene(1);
        Scene map2 = world.createScene(2);
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);
        repo.putNewPlayer(1003, 1);
        assertThat(scenePlayers(1)).as("建场景即注册，初值 0").isZero();
        assertThat(scenePlayers(2)).isZero();

        enter(LINK, 11, 1001, scene.sceneId(), 1);
        enter(LINK, 12, 1002, channel2.sceneId(), 1);
        enter(LINK, 13, 1003, scene.sceneId(), 9);
        assertThat(scenePlayers(1)).as("两条频道合计；epoch 不符的进场失败不计").isEqualTo(2);

        world.switchScene(world.playerBySession(new SessionKey(LINK, 12)), map2);
        assertThat(scenePlayers(1)).isEqualTo(1);
        assertThat(scenePlayers(2)).isEqualTo(1);

        world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(scenePlayers(1)).isZero();

        world.shutdown();
        assertThat(scenePlayers(2)).isZero();
    }

    @Test
    void 指标_视野进出按观察者与目标一对计一次_离场者自己的列表静默清空不计() {
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        assertThat(aoiChanges("enter")).as("场景里没有别人").isZero();
        enter(LINK, 12, 1002, scene.sceneId(), 1);
        assertThat(aoiChanges("enter")).as("进场者的 47 一个条目 + 给先到者的 21").isEqualTo(2);

        ScenePlayer second = world.playerBySession(new SessionKey(LINK, 12));
        world.applyMove(second, standAt(new Vec3(180 + 23, 200, 0)));
        world.step();
        assertThat(aoiChanges("leave")).as("走出离开半径：双方各一条 64").isEqualTo(2);

        clock.advanceMillis(2_000);
        world.applyMove(second, standAt(FakeSceneTables.SPAWN_1));
        world.step();
        assertThat(aoiChanges("enter")).as("走回视野：双方各一条 47").isEqualTo(4);

        world.onPlayerLeave(LINK, leave(12, 1002));
        assertThat(aoiChanges("leave")).as("只有旁观者收到 51").isEqualTo(3);
    }

    @Test
    void 指标_移动裁决_原样接受_截断不纠偏_截断并纠偏_非有限值丢弃() {
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        ScenePlayer player = world.playerBySession(new SessionKey(LINK, 11));
        sink.clear();

        world.applyMove(player, standAt(new Vec3(181, 200, 0)));
        world.applyMove(player, standAt(new Vec3(203.8, 200, 0)));
        // 额度（24 m，时钟不走不回填）只剩 0.2 m：截到 204.0，偏差 0.4 m 不超过纠偏阈值。
        world.applyMove(player, standAt(new Vec3(204.4, 200, 0)));
        // 额度 0：原地不动，偏差远超阈值，回 137。
        world.applyMove(player, standAt(new Vec3(300, 200, 0)));
        world.applyMove(player, standAt(new Vec3(Double.NaN, 200, 0)));
        world.applyMove(player, standAt(new Vec3(204, 200, 1.7e308)));

        assertThat(moves("accepted")).isEqualTo(2);
        assertThat(moves("clamped")).isEqualTo(1);
        assertThat(moves("corrected")).isEqualTo(1);
        assertThat(moves("invalid")).as("非有限值、超出世界范围各一条").isEqualTo(2);
        assertThat(sink.messageIdsTo(LINK, 11)).as("只有纠偏那条回 137").containsExactly(137);
    }

    @Test
    void 指标_每帧记一次帧耗时_视野广播每帧一次_属性同步偶数帧一次() {
        world.step();
        world.step();
        world.step();

        assertThat(meters.get("xm.scene.tick").timer().count()).isEqualTo(3);
        assertThat(meters.get("xm.scene.broadcast").tag("kind", "view_changes").timer().count()).isEqualTo(3);
        assertThat(meters.get("xm.scene.broadcast").tag("kind", "attribute_sync").timer().count()).isEqualTo(2);
    }

    private double scenePlayers(int sceneConfigId) {
        return meters.get("xm.scene.players").tag("scene_config", Integer.toString(sceneConfigId)).gauge().value();
    }

    private double aoiChanges(String change) {
        return meters.get("xm.scene.aoi.changes").tag("change", change).counter().count();
    }

    private double moves(String result) {
        return meters.get("xm.scene.moves").tag("result", result).counter().count();
    }

    /** 站定在某处（速度 0）的移动上行。 */
    private static MoveInput standAt(Vec3 location) {
        return new MoveInput(location, Rotation.getDefaultInstance(), Vec3.ORIGIN, 1);
    }

    // ------------------------------------------------------------------ 工具

    private void enter(long linkId, int sessionId, long playerId, long sceneId, long epoch) {
        world.onPlayerEnter(linkId, enterFrame(sessionId, playerId, sceneId, epoch));
        repo.completeAll();
    }

    @Test
    void 玩家初始化钩子在进场景前调用_抛异常按进场失败处理并释放归属() {
        List<Long> initialized = new ArrayList<>();
        List<Long> snapshotted = new ArrayList<>();
        world = new SceneWorld(new FakeSceneTables(), IDS, sink, repo, new AtomicLong(1000)::incrementAndGet, clock,
                new SceneMetrics(meters), player -> {
                    initialized.add(player.playerId());
                    assertThat(player.scene()).as("钩子在进场景之前").isNull();
                    if (player.playerId() == 1002) {
                        throw new IllegalStateException("坏状态");
                    }
                }, (save, cause) -> snapshotted.add(save.playerId()));
        scene = world.createScene(1);
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 1);

        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));
        repo.completeAll();
        sink.clear();
        world.onPlayerEnter(LINK, enterFrame(12, 1002, scene.sceneId(), 1));
        repo.completeAll();

        assertThat(initialized).containsExactly(1001L, 1002L);
        assertThat(snapshotted).as("进场失败的不拍 LOGIN").containsExactly(1001L);
        assertThat(world.playerBySession(new SessionKey(LINK, 11))).isNotNull();
        assertEnterFailed(12, 1002, 1);
    }

    @Test
    void 快照钩子_进场成功记LOGIN_离场记LOGOUT且与写回是同一份_失去归属不记_停服逐人LOGOUT() {
        record Captured(PlayerSave save, PlayerSnapshots.Cause cause) {
        }
        List<Captured> captured = new ArrayList<>();
        world = new SceneWorld(new FakeSceneTables(), IDS, sink, repo, new AtomicLong(1000)::incrementAndGet, clock,
                new SceneMetrics(meters), PlayerInitializer.NONE, (save, cause) -> captured.add(new Captured(save, cause)));
        scene = world.createScene(1);
        repo.putNewPlayer(1001, 1);
        repo.putNewPlayer(1002, 2);
        repo.putNewPlayer(1003, 3);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        enter(LINK, 12, 1002, scene.sceneId(), 2);
        enter(LINK, 13, 1003, scene.sceneId(), 3);
        world.onPlayerEnter(LINK, enterFrame(14, 1004, scene.sceneId(), 4));
        repo.completeAll();

        assertThat(captured).extracting(c -> c.save().playerId(), Captured::cause).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1001L, PlayerSnapshots.Cause.LOGIN),
                org.assertj.core.groups.Tuple.tuple(1002L, PlayerSnapshots.Cause.LOGIN),
                org.assertj.core.groups.Tuple.tuple(1003L, PlayerSnapshots.Cause.LOGIN));
        captured.clear();

        world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).cause()).isEqualTo(PlayerSnapshots.Cause.LOGOUT);
        assertThat(captured.get(0).save()).isSameAs(repo.saves().get(repo.saves().size() - 1));

        world.onOwnershipLost(List.of(new OwnedPlayer(1002, 2)));
        assertThat(captured).hasSize(1);

        world.shutdown();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).cause()).isEqualTo(PlayerSnapshots.Cause.LOGOUT);
        assertThat(captured.get(1).save()).isSameAs(repo.saves().get(repo.saves().size() - 1));
        assertThat(captured.get(1).save().playerId()).isEqualTo(1003);
    }

    @Test
    void 快照钩子_接管旧实例的进场_LOGIN用旧实例的内存状态而不是库里的旧档_旧实例不记LOGOUT() {
        List<PlayerSave> logins = new ArrayList<>();
        List<PlayerSave> logouts = new ArrayList<>();
        world = new SceneWorld(new FakeSceneTables(), IDS, sink, repo, new AtomicLong(1000)::incrementAndGet, clock,
                new SceneMetrics(meters), PlayerInitializer.NONE,
                (save, cause) -> (cause == PlayerSnapshots.Cause.LOGIN ? logins : logouts).add(save));
        scene = world.createScene(1);
        repo.putNewPlayer(1001, 1);
        enter(LINK, 11, 1001, scene.sceneId(), 1);
        ScenePlayer old = world.playerBySession(new SessionKey(LINK, 11));
        assertThat(old.wallet().add(0, 777).ok()).isTrue();
        logins.clear();
        // 旧实例续约失败、租约过期后被强制夺权（epoch 2）：库里仍是没有金币的旧档
        repo.putNewPlayer(1001, 2);

        enter(LINK, 21, 1001, scene.sceneId(), 2);

        assertThat(logouts).isEmpty();
        assertThat(logins).hasSize(1);
        assertThat(logins.get(0).ownerEpoch()).isEqualTo(2);
        assertThat(logins.get(0).state().getCurrency().getBalances(0)).isEqualTo(777);
        assertThat(logins.get(0).state()).isEqualTo(old.persistentState());
    }

    @Test
    void 存档等级超上限_进场压回85() {
        repo.put(new PlayerData(1001, 1, 3, 1, "", 200, 0, Vec3.ORIGIN));
        repo.put(new PlayerData(1002, 1, 3, 1, "", Integer.MAX_VALUE, 0, Vec3.ORIGIN));
        world.onPlayerEnter(LINK, enterFrame(11, 1001, scene.sceneId(), 1));
        world.onPlayerEnter(LINK, enterFrame(12, 1002, scene.sceneId(), 1));
        repo.completeAll();

        assertThat(world.playerBySession(new SessionKey(LINK, 11)).level()).isEqualTo(85);
        assertThat(world.playerBySession(new SessionKey(LINK, 12)).level()).isEqualTo(85);
    }

    private long entityOf(long linkId, int sessionId) {
        return world.playerBySession(new SessionKey(linkId, sessionId)).entity();
    }

    /**
     * 失败只回 PlayerEnterResult{3023}（回显 epoch）；给客户端的 23 由 gate 发，scene 不直推（避免客户端收到两次）。
     * 这次进场夺得的归属被释放，客户端重试不必等租约过期。
     */
    private void assertEnterFailed(int sessionId, long playerId, long epoch) {
        assertThat(sink.to(LINK, sessionId)).isEmpty();
        assertThat(sink.results()).containsExactly(new EnterResult(LINK, sessionId, playerId, epoch, ENTER_FAILED));
        assertThat(world.playerBySession(new SessionKey(LINK, sessionId))).isNull();
        assertThat(repo.releases()).containsExactly(new Release(playerId, epoch));
    }

    private static PlayerData newPlayer(long playerId, long epoch) {
        return new PlayerData(playerId, epoch, 3, 1, "", 1, 0, Vec3.ORIGIN);
    }

    static PlayerEnter enterFrame(int sessionId, long playerId, long sceneId, long epoch) {
        return PlayerEnter.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setSceneId(sceneId)
                .setOwnerEpoch(epoch)
                .build();
    }

    static PlayerLeave leave(int sessionId, long playerId) {
        return PlayerLeave.newBuilder().setSessionId(sessionId).setPlayerId(playerId).setVoluntary(true).build();
    }

    static void assertLocation(ActorCreateS2C actor, double x, double y, double z) {
        assertThat(actor.hasTransform()).isTrue();
        Vector3 location = actor.getTransform().getLocation();
        assertThat(location.getX()).isEqualTo(x);
        assertThat(location.getY()).isEqualTo(y);
        assertThat(location.getZ()).isEqualTo(z);
    }
}
