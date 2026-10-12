package com.game.scene.world;

import static com.game.scene.world.ChannelPlanApplyTest.active;
import static com.game.scene.world.ChannelPlanApplyTest.draining;
import static com.game.scene.world.InstanceFixture.A;
import static com.game.scene.world.InstanceFixture.B;
import static com.game.scene.world.InstanceFixture.C;
import static com.game.scene.world.InstanceFixture.LOCAL_NODE;
import static com.game.scene.world.InstanceFixture.M;
import static com.game.scene.world.InstanceFixture.SAVED;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.ActorListCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SceneInfoS2C;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeInstanceIds;
import com.game.scene.testing.FakeSceneTables;
import com.game.api.proto.ClientForward;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 63 的镜像分支（批次 5.3，dungeon-mirror-spec §6.7、§6.8、§12.2 第 1–6、13、14 条）：先回 {0}、进 RESOLVING 向 scene-manager 取号、
 * 号回来后在本节点建镜像并换入（79 逐字段、旁人 51、坐标保留、位置记录、目录补发）；准入顺序与各同步拒绝（3014 / 3005 / 3008）；
 * 异步结局（1003 / 3023 / 号落别的节点）；陈旧结果；按号加入、离开；43；79 的字节级形态。
 */
class MirrorSceneTest {

    private static final int CHANGING_SCENE = 3014;
    private static final int PARAM_ERROR = 3005;
    private static final int IN_CURRENT_SCENE = 3008;
    private static final int ENTER_FAILED = 3023;
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

    // ------------------------------------------------------------------ 1 正常路径

    @Test
    void 正常路径_先回0再取号_号回来建镜像换入_79逐字段_旁人51_坐标保留_位置记录与目录补发() {
        long entityA = a.entity();

        f.requestMirror(a, M);

        assertThat(f.messageIds(a)).as("只有应答，79 在号回来之后").containsExactly(63);
        assertThat(f.replies(a)).containsExactly(0);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(a.frozen()).as("取号不冻结").isFalse();
        FakeInstanceIds.PendingCreate create = f.ids.take();
        assertThat(create.request()).isEqualTo(new InstanceIds.Request(1001, SceneKind.MIRROR, A, 1, M, 0));
        assertThat(f.requests("accepted")).isEqualTo(1);
        f.sink.clear();

        long id = f.nextInstanceId();
        create.issued(LOCAL_NODE, id);

        Scene mirror = f.scene(id);
        assertThat(mirror.kind()).isEqualTo(SceneKind.MIRROR);
        assertThat(mirror.sourceSceneId()).isEqualTo(A);
        assertThat(a.scene()).isSameAs(mirror);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.messageIds(a)).containsExactly(79, 21);
        assertThat(f.enterNotices(a)).containsExactly(SceneInfoComp.newBuilder().setSceneConfigId(1).setSceneId(id)
                .setMirrorConfigId(M).putCreators(1001, true).build());
        ActorCreateS2C self = InstanceFixture.parse(() -> ActorCreateS2C.parseFrom(f.to(a).get(1).getSerializedMessage()));
        SceneWorldTest.assertLocation(self, SAVED.x(), SAVED.y(), SAVED.z());
        assertThat(a.position()).as("同图保留坐标").isEqualTo(SAVED);
        assertThat(f.messageIds(b)).as("源场景旁人收到 51").containsExactly(Contracts.IDS.notifyActorDestroy());
        ActorDestroyS2C gone = InstanceFixture.parse(() -> ActorDestroyS2C.parseFrom(f.to(b).get(0).getSerializedMessage()));
        assertThat(gone.getEntity()).isEqualTo(entityA);
        assertThat(f.locations).containsExactly(new InstanceFixture.Location(1001, id, 1));
        assertThat(f.directoryPublishes.get()).as("建好立即补发目录").isPositive();
        assertThat(f.world.sceneEntries()).contains(SceneEntry.newBuilder().setSceneId(id).setSceneConfigId(1)
                .setPlayerCount(1).setKind(ChannelKind.CHANNEL_KIND_MIRROR).setSourceSceneId(A).build());
        assertThat(f.resolves("created")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "created")).isEqualTo(1);
        assertThat(f.instances("mirror", "active")).isEqualTo(1);
        assertThat(f.channels("active")).as("镜像不计频道").isEqualTo(3);
        assertThat(f.players(1)).as("镜像居民计入源地图").isEqualTo(2);
    }

    @Test
    void 镜像里有旁人时_换入者收到含旁人的47_旁人收到换入者的21() {
        Scene mirror = f.createMirror(a);
        f.sink.clear();

        f.enterScene(b, 0, mirror.sceneId(), M, 0, 3);

        assertThat(f.replies(b)).containsExactly(0);
        assertThat(f.messageIds(b)).containsExactly(63, 79, 21, Contracts.IDS.notifyActorListCreate());
        assertThat(f.enterNotices(b).get(0).getCreatorsMap()).as("后加入的人看到的 creators 仍只有创建者")
                .isEqualTo(Map.of(1001L, true));
        ActorListCreateS2C list = InstanceFixture.parse(
                () -> ActorListCreateS2C.parseFrom(f.to(b).get(3).getSerializedMessage()));
        assertThat(list.getActorListList()).extracting(ActorCreateS2C::getEntity).containsExactly(a.entity());
        assertThat(f.messageIds(a)).containsExactly(21);
        assertThat(b.scene()).isSameAs(mirror);
    }

    // ------------------------------------------------------------------ 2 准入顺序与同步拒绝

    @Test
    void 取号中再发63回3014_不再取第二次号() {
        f.requestMirror(a, M);
        f.sink.clear();

        f.requestMirror(a, M);
        f.enterScene(a, 2, 0, 0, 0, 8);

        assertThat(f.replies(a)).containsExactly(CHANGING_SCENE, CHANGING_SCENE);
        assertThat(f.ids.pendingCount()).isEqualTo(1);
        assertThat(a.scene()).isSameAs(f.scene(A));
    }

    @Test
    void 冻结中发镜像回3014() {
        WorldTestAccess.startFreezing(a);

        f.requestMirror(a, M);

        assertThat(f.replies(a)).containsExactly(CHANGING_SCENE);
        assertThat(f.ids.pendingCount()).isZero();
        assertThat(f.requests("accepted")).as("3014 在镜像分支之前，不计镜像请求").isZero();
    }

    @Test
    void 三个号全0或只带dungeon回3005_镜像号带当前场景号回3008_镜像号与场景号0不回3008() {
        f.enterScene(a, 0, 0, 0, 0, 1);
        f.enterScene(a, 0, 0, 0, 1, 2);
        f.enterScene(a, 0, A, M, 0, 3);

        assertThat(f.replies(a)).containsExactly(PARAM_ERROR, PARAM_ERROR, IN_CURRENT_SCENE);
        assertThat(f.ids.pendingCount()).isZero();

        f.requestMirror(a, M);
        assertThat(f.replies(a)).last().isEqualTo(0);
        assertThat(f.ids.pendingCount()).isEqualTo(1);
    }

    @Test
    void 镜像分支忽略请求里的地图_镜像地图取源场景的() {
        f.enterScene(a, 2, 0, M, 3, 4);

        assertThat(f.replies(a)).containsExactly(0);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        assertThat(create.request().sceneConfigId()).isEqualTo(1);
        assertThat(create.request().dungeonConfigId()).isZero();
        long id = f.nextInstanceId();
        create.issued(LOCAL_NODE, id);
        assertThat(f.scene(id).configId()).isEqualTo(1);
    }

    @Test
    void 镜像号不在Mirror表回3005_含2的31次方以上的值_不发79() {
        f.requestMirror(a, 999);
        f.requestMirror(a, 0x8000_0000);
        f.requestMirror(a, -1);

        assertThat(f.replies(a)).containsExactly(PARAM_ERROR, PARAM_ERROR, PARAM_ERROR);
        assertThat(f.messageIds(a)).containsExactly(63, 63, 63);
        assertThat(f.ids.pendingCount()).isZero();
        assertThat(f.requests("bad_mirror_config")).isEqualTo(3);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 在镜像里再发镜像回3005_镜像的镜像() {
        f.createMirror(a);
        f.sink.clear();

        f.requestMirror(a, M);

        assertThat(f.replies(a)).containsExactly(PARAM_ERROR);
        assertThat(f.messageIds(a)).doesNotContain(79);
        assertThat(f.requests("bad_source")).isEqualTo(1);
    }

    @Test
    void 副本不能当镜像源_回3005() {
        long dungeon = f.createDungeon(1).getSceneId();
        f.enterScene(a, 0, dungeon, 0, 0, 1);
        assertThat(a.scene().kind()).isEqualTo(SceneKind.DUNGEON);
        f.sink.clear();

        f.requestMirror(a, M);

        assertThat(f.replies(a)).containsExactly(PARAM_ERROR);
        assertThat(f.requests("bad_source")).isEqualTo(1);
        assertThat(f.ids.pendingCount()).isZero();
    }

    @Test
    void 当前频道在排空回3005() {
        ScenePlayer stuck = a;
        f.scene(A).setDraining(true);   // 计划排空（这里不推进改派：直接看同步校验）

        f.requestMirror(stuck, M);

        assertThat(f.replies(stuck)).containsExactly(PARAM_ERROR);
        assertThat(f.requests("source_draining")).isEqualTo(1);
    }

    @Test
    void 本节点停止接客回3005() {
        f.world.stopAcceptingEnters();

        f.requestMirror(a, M);

        assertThat(f.replies(a)).containsExactly(PARAM_ERROR);
        assertThat(f.requests("not_accepting")).isEqualTo(1);
    }

    @Test
    void 本节点实例数达上限回3005() {
        InstanceFixture capped = new InstanceFixture(InstanceFixture.Settings.DEFAULT.caps(1, 3));
        ScenePlayer x = capped.enter(11, 1001, A);
        ScenePlayer y = capped.enter(12, 1002, A);
        capped.createMirror(x);
        capped.sink.clear();

        capped.requestMirror(y, M);

        assertThat(capped.replies(y)).containsExactly(PARAM_ERROR);
        assertThat(capped.requests("node_cap")).isEqualTo(1);
        assertThat(capped.ids.pendingCount()).isZero();
    }

    @Test
    void 本人创建的实例数达上限回3005_别人照常() {
        InstanceFixture capped = new InstanceFixture(InstanceFixture.Settings.DEFAULT.caps(200, 1));
        ScenePlayer x = capped.enter(11, 1001, A);
        ScenePlayer y = capped.enter(12, 1002, A);
        capped.createMirror(x);
        capped.enterScene(x, 0, A, 0, 0, 2);   // 回源频道，镜像还在（空置计时中）
        capped.sink.clear();

        capped.requestMirror(x, M);
        capped.requestMirror(y, M);

        assertThat(capped.replies(x)).containsExactly(PARAM_ERROR);
        assertThat(capped.replies(y)).containsExactly(0);
        assertThat(capped.requests("creator_cap")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3 异步结局

    @Test
    void 取号调用失败_回NONE_推23的1003_不建_之后可再发() {
        f.requestMirror(a, M);
        f.sink.clear();

        f.ids.take().complete(new InstanceIds.Result.Failed("连不上"));

        assertThat(f.pushedTips(a)).containsExactly(SERVICE_UNAVAILABLE);
        assertThat(f.messageIds(a)).doesNotContain(79);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.instanceCount()).isZero();
        assertThat(f.resolves("error")).isEqualTo(1);
        f.requestMirror(a, M);
        assertThat(f.replies(a)).containsExactly(0);
    }

    @Test
    void scene_manager拒绝_推23的3023_不建() {
        f.requestMirror(a, M);
        f.sink.clear();

        f.ids.take().complete(new InstanceIds.Result.Refused(3005));

        assertThat(f.pushedTips(a)).containsExactly(ENTER_FAILED);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.instanceCount()).isZero();
        assertThat(f.resolves("rejected")).isEqualTo(1);
    }

    @Test
    void 号落在别的节点_推23的3023_不当成本地实例() {
        f.requestMirror(a, M);
        f.sink.clear();

        long id = f.nextInstanceId();
        f.ids.take().issued(LOCAL_NODE + 1, id);

        assertThat(f.pushedTips(a)).containsExactly(ENTER_FAILED);
        assertThat(f.scene(id)).isNull();
        assertThat(a.scene()).isSameAs(f.scene(A));
        assertThat(f.resolves("wrong_node")).isEqualTo(1);
    }

    @Test
    void 没装配取号_应答0之后推23的1003_D9() {
        InstanceFixture noIds = new InstanceFixture(InstanceFixture.Settings.DEFAULT.withoutIds());
        ScenePlayer x = noIds.enter(11, 1001, A);

        noIds.requestMirror(x, M);

        assertThat(noIds.messageIds(x)).containsExactly(63, InstanceFixture.TIP);
        assertThat(noIds.replies(x)).containsExactly(0);
        assertThat(noIds.pushedTips(x)).containsExactly(SERVICE_UNAVAILABLE);
        assertThat(x.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(noIds.resolves("error")).isEqualTo(1);
    }

    @Test
    void 取号结果迟迟不回_在途槽过期后63照常受理_迟到的结果丢弃() {
        f.requestMirror(a, M);
        FakeInstanceIds.PendingCreate first = f.ids.take();
        f.clock.advanceMillis(InstanceFixture.RESOLVE_TIMEOUT.toMillis() + 1_001);
        f.sink.clear();

        f.requestMirror(a, M);
        assertThat(f.replies(a)).containsExactly(0);
        FakeInstanceIds.PendingCreate second = f.ids.take();

        first.issued(LOCAL_NODE, f.nextInstanceId());
        assertThat(f.resolves("stale")).isEqualTo(1);
        assertThat(f.world.instanceCount()).as("迟到的号作废，不留幽灵镜像").isZero();

        long id = f.nextInstanceId();
        second.issued(LOCAL_NODE, id);
        assertThat(a.scene()).isSameAs(f.scene(id));
    }

    // ------------------------------------------------------------------ 4 陈旧结果

    @Test
    void 号回来前玩家已离开_丢弃不建() {
        f.requestMirror(a, M);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        f.leave(a);

        long id = f.nextInstanceId();
        create.issued(LOCAL_NODE, id);

        assertThat(f.scene(id)).isNull();
        assertThat(f.resolves("stale")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "created")).isZero();
    }

    @Test
    void 号回来前玩家被频道排空改派走_推23的3023_不建_D6() {
        f.requestMirror(a, M);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        f.plan(draining(A, 1), active(B, 1), active(C, 2));   // A 转排空，两人同节点改派到 B（RESOLVING 不跳过）
        assertThat(a.scene()).isSameAs(f.scene(B));
        f.sink.clear();

        long id = f.nextInstanceId();
        create.issued(LOCAL_NODE, id);

        assertThat(f.pushedTips(a)).containsExactly(ENTER_FAILED);
        assertThat(f.scene(id)).isNull();
        assertThat(a.scene()).isSameAs(f.scene(B));
        assertThat(f.resolves("source_moved")).isEqualTo(1);
    }

    @Test
    void 号回来前节点停止接客_推23的3023_不建() {
        f.requestMirror(a, M);
        FakeInstanceIds.PendingCreate create = f.ids.take();
        f.world.stopAcceptingEnters();
        f.sink.clear();

        create.issued(LOCAL_NODE, f.nextInstanceId());

        assertThat(f.pushedTips(a)).containsExactly(ENTER_FAILED);
        assertThat(f.world.instanceCount()).isZero();
        assertThat(f.resolves("source_moved")).isEqualTo(1);
    }

    @Test
    void 号本地已存在_推23的3023_记拒建_不覆盖() {
        f.requestMirror(a, M);
        f.sink.clear();

        f.ids.take().issued(LOCAL_NODE, B);

        assertThat(f.pushedTips(a)).containsExactly(ENTER_FAILED);
        assertThat(f.scene(B).kind()).isEqualTo(SceneKind.WORLD);
        assertThat(f.resolves("create_rejected")).isEqualTo(1);
        assertThat(f.lifecycle("mirror", "rejected")).isEqualTo(1);
        assertThat(a.scene()).isSameAs(f.scene(A));
    }

    // ------------------------------------------------------------------ 5 按号加入

    @Test
    void 按号加入时带的地图不符回3005() {
        Scene mirror = f.createMirror(a);

        f.enterScene(b, 2, mirror.sceneId(), 0, 0, 5);

        assertThat(f.replies(b)).containsExactly(PARAM_ERROR);
        assertThat(b.scene()).isSameAs(f.scene(A));
    }

    @Test
    void 回收宽限中的镜像不能按号进入_回3023() {
        Scene mirror = f.createMirror(a);
        f.enterScene(a, 0, A, 0, 0, 2);
        f.tickSeconds(31);
        assertThat(mirror.drainCause()).isEqualTo(DrainCause.IDLE);
        f.sink.clear();

        f.enterScene(b, 0, mirror.sceneId(), M, 0, 6);

        assertThat(f.replies(b)).containsExactly(ENTER_FAILED);
        assertThat(b.scene()).isSameAs(f.scene(A));
    }

    // ------------------------------------------------------------------ 6 离开

    @Test
    void 镜像里只带当前地图_进主世界频道_人数并列也不留_坐标保留() {
        Scene mirror = f.createMirror(a);
        f.sink.clear();

        f.enterScene(a, 1, 0, 0, 0, 2);

        assertThat(f.replies(a)).containsExactly(0);
        assertThat(a.scene()).as("A 有 B 一人、B 频道 0 人：人数最少的主世界频道").isSameAs(f.scene(B));
        assertThat(f.enterNotices(a).get(0).getMirrorConfigId()).isZero();
        assertThat(a.position()).isEqualTo(SAVED);
        assertThat(mirror.playerCount()).isZero();
    }

    @Test
    void 镜像里只带别的地图_落那张图的出生点() {
        f.createMirror(a);

        f.enterScene(a, 2, 0, 0, 0, 2);

        assertThat(a.scene()).isSameAs(f.scene(C));
        assertThat(a.position()).isEqualTo(FakeSceneTables.SPAWN_2);
    }

    @Test
    void 镜像里指定源频道号_回源频道() {
        f.createMirror(a);

        f.enterScene(a, 0, A, 0, 0, 2);

        assertThat(a.scene()).isSameAs(f.scene(A));
        assertThat(a.position()).isEqualTo(SAVED);
    }

    // ------------------------------------------------------------------ 13、14

    @Test
    void 在镜像里请求43_推的31是镜像的info() {
        Scene mirror = f.createMirror(a);
        f.sink.clear();

        f.handler.onClientForward(InstanceFixture.LINK, ClientForward.newBuilder()
                .setSessionId(a.session().sessionId())
                .setPlayerId(a.playerId())
                .setMessageId(Contracts.IDS.sceneInfoC2S())
                .setBody(SceneInfoRequest.getDefaultInstance().toByteString())
                .setRequestId(9)
                .build());

        List<MessageContent> toA = f.to(a);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(Contracts.IDS.notifySceneInfo());
        SceneInfoS2C push = InstanceFixture.parse(() -> SceneInfoS2C.parseFrom(toA.get(0).getSerializedMessage()));
        assertThat(push.getSceneInfoList()).containsExactly(mirror.info());
        assertThat(push.getSceneInfo(0).getMirrorConfigId()).isEqualTo(M);
        assertThat(push.getSceneInfo(0).getCreatorsMap()).isEqualTo(Map.of(1001L, true));
    }

    @Test
    void 镜像79的字节与基线一致_creators写true_dungeon为0不上线() {
        SceneInfoComp info = InstanceSpec.mirror(0x0102030405060708L, 1, A, 1, 42).toInfo();

        byte[] bytes = EnterSceneS2C.newBuilder().setSceneInfo(info).build().toByteArray();

        assertThat(HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes))
                .isEqualTo("0A 14 08 01 10 88 8E 98 A8 C0 E0 80 81 01 18 01 2A 04 08 2A 10 01");
    }

    @Test
    void 副本info_地图为Dungeon地图_dungeon号_mirror为0_creators空() {
        SceneInfoComp info = InstanceSpec.dungeon(77, 17, 1).toInfo();

        assertThat(info).isEqualTo(SceneInfoComp.newBuilder().setSceneConfigId(17).setSceneId(77).setDungeonConfigId(1)
                .build());
        assertThat(info.getCreatorsCount()).isZero();
    }

    @Test
    void 号回来建好后_计时从变空起_创建者换入即清零() {
        Scene mirror = f.createMirror(a);

        assertThat(mirror.emptySinceKnown()).as("创建者已在里面").isFalse();
        f.tick(Duration.ofSeconds(1));
        assertThat(mirror.emptySinceKnown()).isFalse();
    }
}
