package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.leave;
import static com.game.scene.world.TransferFixture.LINK;
import static com.game.scene.world.TransferFixture.LOCAL_NODE;
import static com.game.scene.world.TransferFixture.REMOTE_SCENE;
import static com.game.scene.world.TransferFixture.TARGET_NODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.scene.testing.FakeSwitchTargets.PendingSelect;
import com.game.scene.world.RemoteSwitchTargets.Selection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 63 的远端去向（批次 5.2，scene-handoff-spec §5.5 第 1–4 步、§10.3「63 分支」）：先回 {0}、RESOLVING 不冻结、选目标结果的各行
 * （失败 1003 / 拒绝 3023 / 本节点同步换 / 就是当前场景 / 别的节点冻结）、令牌过期丢弃、在途 3014。
 */
class RemoteSwitchTest {

    private TransferFixture f;
    private ScenePlayer a;
    private ScenePlayer b;

    @BeforeEach
    void setUp() {
        f = new TransferFixture();
        a = f.enter(11, 1001, f.scene1, 5);
        b = f.enter(12, 1002, f.scene1, 1);
    }

    @Test
    void 本节点内的去向照旧同步换_不请scene_manager() {
        f.enterScene(11, 1001, 0, 2, 7);

        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(63, 79, 21);
        assertThat(f.enterSceneReplies(11)).containsExactly(0);
        assertThat(a.scene()).isSameAs(f.scene2);
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 指定别的节点上的scene_id_先回0_进RESOLVING_请scene_manager选目标() {
        f.enterScene(11, 1001, REMOTE_SCENE, 2, 9);

        List<MessageContent> toA = f.sink.to(LINK, 11);
        assertThat(toA).extracting(MessageContent::getMessageId).containsExactly(63);
        assertThat(toA.get(0).getId()).isEqualTo(9L);
        assertThat(f.enterSceneReplies(11)).containsExactly(0);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(a.frozen()).as("RESOLVING 不冻结").isFalse();
        PendingSelect select = f.targets.take();
        assertThat(select.playerId()).isEqualTo(1001);
        assertThat(select.fromSceneId()).isEqualTo(f.scene1.sceneId());
        assertThat(select.wantSceneId()).isEqualTo(REMOTE_SCENE);
        assertThat(select.wantSceneConfigId()).isEqualTo(2);
        assertThat(f.sink.to(LINK, 12)).as("旁人什么都没收到").isEmpty();
    }

    @Test
    void 只带地图且本节点没有该图的承载中频道_走远端_排除当前场景() {
        f.scene1.setDraining(true);

        f.enterScene(11, 1001, 0, 1, 3);

        assertThat(f.enterSceneReplies(11)).containsExactly(0);
        PendingSelect select = f.targets.take();
        assertThat(select.wantSceneId()).isZero();
        assertThat(select.wantSceneConfigId()).isEqualTo(1);
        assertThat(select.fromSceneId()).as("scene-manager 据此不再选它").isEqualTo(f.scene1.sceneId());
    }

    @Test
    void 只带非主世界地图_本节点没有也不请scene_manager_同步回3023() {
        f.enterScene(11, 1001, 0, 9, 3);

        assertThat(f.enterSceneReplies(11)).containsExactly(3023);
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 跨节点换图没装配时_远端去向照51回3023() {
        SceneWorld plain = new SceneWorld(new com.game.scene.testing.FakeSceneTables(), com.game.scene.testing.Contracts.IDS,
                f.sink, f.repo, new java.util.concurrent.atomic.AtomicLong(7000)::incrementAndGet, f.clock,
                com.game.scene.metrics.SceneMetrics.noop());
        Scene s = plain.createScene(1);

        assertThat(plain.resolveSwitchTarget(s, REMOTE_SCENE, 0)).isEqualTo(new SwitchTarget.Reject(3023));
        s.setDraining(true);
        assertThat(plain.resolveSwitchTarget(s, 0, 1)).isEqualTo(new SwitchTarget.Reject(3023));
    }

    @Test
    void 选目标中再发63回3014_本节点内的去向也不换() {
        f.resolveRemote(a);
        f.sink.clear();

        f.enterScene(11, 1001, 0, 2, 2);

        assertThat(f.enterSceneReplies(11)).containsExactly(3014);
        assertThat(a.scene()).isSameAs(f.scene1);
        assertThat(f.targets.pendingCount()).as("不再发第二次选目标").isZero();
    }

    @Test
    void 冻结中再发63回3014() {
        f.freeze(a);
        f.sink.clear();

        f.enterScene(11, 1001, REMOTE_SCENE, 0, 4);

        assertThat(f.enterSceneReplies(11)).containsExactly(3014);
        assertThat(f.targets.pendingCount()).isZero();
    }

    @Test
    void 选目标调用失败_回NONE_推23的1003_之后可再发63() {
        PendingSelect select = f.resolveRemote(a);
        f.sink.clear();

        select.complete(new Selection.Failed("连不上"));

        assertThat(f.pushedTips(11)).containsExactly(1003);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("error")).isEqualTo(1);
        f.enterScene(11, 1001, REMOTE_SCENE, 0, 5);
        assertThat(f.enterSceneReplies(11)).containsExactly(0);
    }

    @Test
    void scene_manager拒绝_回NONE_推23的3023() {
        f.resolveRemote(a).complete(new Selection.Refused(3000));

        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("rejected")).isEqualTo(1);
        assertThat(f.repo.pendingHandOffs()).isZero();
    }

    @Test
    void 结果在本节点另一个场景_同步换场景发79() throws Exception {
        PendingSelect select = f.resolveRemote(a);
        f.sink.clear();

        select.chosen(LOCAL_NODE, f.scene2.sceneId(), 2);

        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(79, 21);
        assertThat(EnterSceneS2C.parseFrom(f.sink.to(LINK, 11).get(0).getSerializedMessage()).getSceneInfo().getSceneId())
                .isEqualTo(f.scene2.sceneId());
        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(a.scene()).isSameAs(f.scene2);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("local")).isEqualTo(1);
        assertThat(f.repo.pendingHandOffs()).as("本节点内不交出、epoch 不变").isZero();
        assertThat(a.ownerEpoch()).isEqualTo(5);
    }

    @Test
    void 结果就是当前场景_什么都不发() {
        PendingSelect select = f.resolveRemote(a);
        f.sink.clear();

        select.chosen(LOCAL_NODE, f.scene1.sceneId(), 1);

        assertThat(f.sink.events()).isEmpty();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("same")).isEqualTo(1);
    }

    @Test
    void 结果是本节点排空中的场景_推3023留原地() {
        Scene draining = f.world.createScene(2);
        draining.setDraining(true);
        PendingSelect select = f.resolveRemote(a);
        f.sink.clear();

        select.chosen(LOCAL_NODE, draining.sceneId(), 2);

        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(a.scene()).isSameAs(f.scene1);
        assertThat(f.resolves("rejected")).isEqualTo(1);
    }

    @Test
    void 结果指向本节点却不在本节点_目录过时_推3023不交给自己() {
        PendingSelect select = f.resolveRemote(a);
        f.sink.clear();

        select.chosen(LOCAL_NODE, 777_777, 2);

        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.resolves("rejected")).isEqualTo(1);
    }

    @Test
    void 应答残缺_节点号或场景号为0_按调用失败推1003() {
        f.resolveRemote(a).chosen(0, REMOTE_SCENE, 2);
        assertThat(f.pushedTips(11)).containsExactly(1003);
        f.sink.clear();

        f.resolveRemote(a).chosen(TARGET_NODE, 0, 2);
        assertThat(f.pushedTips(11)).containsExactly(1003);
        assertThat(f.resolves("error")).isEqualTo(2);
        assertThat(f.repo.pendingHandOffs()).isZero();
    }

    @Test
    void 结果在别的节点_冻结并提交交出() {
        f.resolveRemote(a).chosen(TARGET_NODE, REMOTE_SCENE, 2);

        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.FREEZING);
        assertThat(a.frozen()).isTrue();
        assertThat(f.resolves("remote")).isEqualTo(1);
        assertThat(f.repo.takeHandOff().frozen()).isEqualTo(a.toSave());
        assertThat(f.inFlight()).isEqualTo(1);
    }

    @Test
    void RESOLVING期间离场_实例照常写回移除_迟到的结果按过期丢弃() {
        PendingSelect select = f.resolveRemote(a);

        f.world.onPlayerLeave(LINK, leave(11, 1001));
        assertThat(f.repo.saves()).extracting(PlayerSave::playerId).containsExactly(1001L);
        select.chosen(TARGET_NODE, REMOTE_SCENE, 2);

        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.resolves("stale")).isEqualTo(1);
    }

    @Test
    void RESOLVING期间重新进场_旧实例的结果按过期丢弃_不碰新实例() {
        PendingSelect select = f.resolveRemote(a);
        f.repo.putNewPlayer(1001, 6);
        f.world.onPlayerEnter(LINK, SceneWorldTest.enterFrame(21, 1001, f.scene1.sceneId(), 6));
        f.repo.completeAll();
        ScenePlayer again = f.world.playerById(1001);
        assertThat(again).isNotSameAs(a);

        select.chosen(TARGET_NODE, REMOTE_SCENE, 2);

        assertThat(f.resolves("stale")).isEqualTo(1);
        assertThat(again.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.repo.pendingHandOffs()).isZero();
    }

    @Test
    void RESOLVING槽过了期限_再发63不再被挡_旧结果按过期丢弃() {
        PendingSelect old = f.resolveRemote(a);
        f.clock.advanceMillis(TransferFixture.RESOLVE_TIMEOUT.toMillis() + 999);
        f.sink.clear();
        f.enterScene(11, 1001, REMOTE_SCENE, 0, 2);
        assertThat(f.enterSceneReplies(11)).as("兜底超时 + 1 s 之内仍在途").containsExactly(3014);

        f.clock.advanceMillis(1);
        f.sink.clear();
        f.enterScene(11, 1001, REMOTE_SCENE, 0, 3);
        assertThat(f.enterSceneReplies(11)).containsExactly(0);
        PendingSelect fresh = f.targets.take();

        old.chosen(TARGET_NODE, REMOTE_SCENE, 2);
        assertThat(f.resolves("stale")).isEqualTo(1);
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        fresh.chosen(TARGET_NODE, REMOTE_SCENE, 2);
        assertThat(a.frozen()).isTrue();
        assertThat(f.repo.pendingHandOffs()).isEqualTo(1);
    }

    @Test
    void RESOLVING不冻结_在线存盘与立即存盘照常() {
        f.resolveRemote(a);
        assertThat(a.wallet().add(0, 10).ok()).isTrue();

        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
    }

    @Test
    void 选目标指标只计经scene_manager的那部分() {
        f.enterScene(11, 1001, 0, 2, 7);
        for (String result : new String[] {"local", "remote", "same", "rejected", "error", "stale"}) {
            assertThat(f.resolves(result)).as(result).isZero();
        }
    }

    @Test
    void 旁人b不受a的换图影响() {
        f.resolveRemote(a).complete(new Selection.Refused(3005));
        assertThat(f.sink.to(LINK, 12)).isEmpty();
        assertThat(b.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }
}
