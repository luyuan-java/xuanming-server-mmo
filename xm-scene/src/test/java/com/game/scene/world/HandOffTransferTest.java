package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.TransferFixture.LINK;
import static com.game.scene.world.TransferFixture.REMOTE_SCENE;
import static com.game.scene.world.TransferFixture.TARGET_NODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerLeave;
import com.game.proto.ActorCreateS2C;
import com.game.proto.ActorDestroyS2C;
import com.game.proto.Rotation;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakePlayerRepository.PendingProbe;
import com.game.scene.testing.FakePlayerRepository.Release;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.RecordingSink.EnterResult;
import com.game.scene.testing.RecordingSink.Kicked;
import com.game.scene.testing.RecordingSink.Transfer;
import com.game.scene.world.PlayerRepository.HandOffAttempts;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.TransferFixture.LocationCall;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 冻结、交出与交出进场（批次 5.2，scene-handoff-spec §5.5「FREEZING 期间各事件」、§5.8、§10.3）：交出成功 / 各种失败、
 * 冻结中的离开 / 断链 / 接管 / 续约失去 / 在线存盘、停服、墓碑、帧没写出，以及目标节点上的交出进场。
 */
class HandOffTransferTest {

    private static final long E = 5;

    private TransferFixture f;
    private ScenePlayer a;
    private ScenePlayer b;

    @BeforeEach
    void setUp() {
        f = new TransferFixture();
        a = f.enter(11, 1001, f.scene1, E);
        b = f.enter(12, 1002, f.scene1, 1);
    }

    // ------------------------------------------------------------------ 冻结

    @Test
    void 冻结前停下_旁人下一同步帧收到停了的66_快照就是交出写的内容() {
        f.world.applyMove(a, new MoveInput(a.position(), Rotation.getDefaultInstance(), new Vec3(2, 0, 0), 1));
        f.world.step();
        f.world.step();
        f.sink.clear();

        PendingHandOff handOff = f.freeze(a);

        assertThat(a.velocity().isOrigin()).isTrue();
        assertThat(handOff.frozen()).isEqualTo(a.toSave());
        assertThat(handOff.frozen().ownerEpoch()).isEqualTo(E);
        f.world.step();
        f.world.step();
        assertThat(f.sink.messageIdsTo(LINK, 12)).as("冻结后不再外推，旁人收到速度 0 的 66").containsExactly(66);
        assertThat(f.inFlight()).isEqualTo(1);
    }

    @Test
    void 冻结中周期存盘与立即存盘都不提交_交出就是这个玩家唯一的写() {
        f.freeze(a);
        assertThat(a.wallet().add(0, 1).ok()).isTrue();

        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        for (int i = 0; i < 3; i++) {
            f.world.saveDuePlayers(1);
        }
        // 旁人 b（新号落到出生点，与库里不同）照常写；冻结中的 a 一笔也没有
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        assertThat(f.repo.takeProgress().save().playerId()).isEqualTo(1002);
    }

    // ------------------------------------------------------------------ 交出成功

    @Test
    void 交出成功_旁人收到51_本人收不到79_发PlayerTransfer_不写回不拍快照不写位置() throws Exception {
        long entityA = a.entity();
        PendingHandOff handOff = f.freeze(a);
        f.sink.clear();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(ActorDestroyS2C.parseFrom(f.sink.to(LINK, 12).get(0).getSerializedMessage()).getEntity())
                .isEqualTo(entityA);
        assertThat(f.sink.to(LINK, 11)).as("源节点不给本人发任何东西").isEmpty();
        assertThat(f.sink.transfers()).singleElement().satisfies(t -> {
            assertThat(t.linkId()).isEqualTo(LINK);
            assertThat(t.sessionId()).isEqualTo(11);
            assertThat(t.playerId()).isEqualTo(1001);
            assertThat(t.fromEpoch()).isEqualTo(E);
            assertThat(t.toEpoch()).isEqualTo(E + 1);
            assertThat(t.targetNodeId()).isEqualTo(TARGET_NODE);
            assertThat(t.targetSceneId()).isEqualTo(REMOTE_SCENE);
        });
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.snapshots).isEmpty();
        assertThat(f.locations).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.world.ownedPlayers()).containsExactly(new OwnedPlayer(1002, 1));
        assertThat(f.scene1.playerCount()).isEqualTo(1);
        assertThat(f.transfers("handed_off")).isEqualTo(1);
        assertThat(f.inFlight()).isZero();
        assertThat(f.meters.get("xm.scene.transfer.freeze").timer().count()).isEqualTo(1);
        assertThat(f.meters.get("xm.scene.transfer.post.freeze.mutations").counter().count()).isZero();
    }

    @Test
    void 冻结期间状态被改过_交出后计post_freeze_mutation() {
        PendingHandOff handOff = f.freeze(a);
        // 绕过冻结闸直接改（漏掉的闸）
        assertThat(a.wallet().add(0, 99).ok()).isTrue();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.meters.get("xm.scene.transfer.post.freeze.mutations").counter().count()).isEqualTo(1);
        assertThat(f.sink.transfers()).as("照样交出：冻结快照已落库").hasSize(1);
    }

    @Test
    void 帧写不出_链路已断_源节点释放E加1_位置以E和序号加1转重连租约() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.sink.setTransferWritable(false);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.locations).containsExactly(
                new LocationCall("disconnected", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.transfers("link_gone")).isEqualTo(1);
        assertThat(f.transfers("handed_off")).isZero();
        assertThat(f.world.playerById(1001)).isNull();
    }

    @Test
    void 帧交给链路后异步写失败_释放E加1并写重连租约_只写一次() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));
        Transfer transfer = f.sink.transfers().get(0);

        transfer.onWriteFailed().run();
        transfer.onWriteFailed().run();

        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1), new Release(1001, E + 1));
        assertThat(f.locations).extracting(LocationCall::action).containsExactly("disconnected");
        // 墓碑已作废：之后交叉到达的 leave 不再写位置
        f.world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001).setVoluntary(true).build());
        assertThat(f.locations).hasSize(1);
    }

    // ------------------------------------------------------------------ 交出失败

    @Test
    void 租约不足_原地解冻_推3023_之后可写() {
        PendingHandOff handOff = f.freeze(a);
        f.sink.clear();

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(a.frozen()).isFalse();
        assertThat(a.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.sink.messageIdsTo(LINK, 12)).as("旁人看不到任何变化").isEmpty();
        assertThat(f.transfers("lease_too_short")).isEqualTo(1);
        assertThat(f.inFlight()).isZero();
        assertThat(a.wallet().add(0, 5).ok()).isTrue();
        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
    }

    @Test
    void 交出被围栏拒_已失去归属_移除不写回_踢2017() {
        PendingHandOff handOff = f.freeze(a);

        handOff.complete(new HandOffOutcome.Fenced());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, 2017));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(f.transfers("fenced")).isEqualTo(1);
    }

    @Test
    void 结局不明_提交探测_探测没提交_原地解冻推3023() {
        PendingHandOff handOff = f.freeze(a);
        HandOffOutcome.Failed failed = new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L)));
        handOff.complete(failed);
        assertThat(a.frozen()).as("探测期间仍冻结").isTrue();
        PendingProbe probe = f.repo.takeProbe();
        assertThat(probe.failed()).isSameAs(failed);
        f.sink.clear();

        probe.complete(new ProbeOutcome.NotCommitted());

        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(a.frozen()).isFalse();
        assertThat(f.transfers("aborted_in_place")).isEqualTo(1);
    }

    @Test
    void 结局不明_探测认出已提交_照成功处理发PlayerTransfer() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L))));

        f.repo.takeProbe().complete(new ProbeOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).singleElement().extracting(Transfer::toEpoch).isEqualTo(E + 1);
        assertThat(f.transfers("handed_off")).isEqualTo(1);
    }

    @Test
    void 结局不明_探测判定不了_移除不写回不释放_踢3023() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.Failed(1001, E, new HandOffAttempts(1, List.of(42L))));

        f.repo.takeProbe().complete(new ProbeOutcome.Lost());

        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, 3023));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.transfers("lost_unknown")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 冻结中的事件

    @Test
    void 冻结中主动离开_只记下_交出成功后释放E加1并写登出墓碑_不发PlayerTransfer() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.sink.clear();

        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        assertThat(f.world.playerById(1001)).as("实例保持到结局回来").isSameAs(a);
        assertThat(f.sink.events()).isEmpty();
        assertThat(f.repo.saves()).as("不提交第二笔写").isEmpty();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.sink.messageIdsTo(LINK, 12)).containsExactly(51);
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.repo.saves()).isEmpty();
        assertThat(f.locations).containsExactly(
                new LocationCall("loggedOut", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.transfers("left")).isEqualTo(1);
    }

    @Test
    void 冻结中断线_交出没提交_照现有流程写回释放E并转重连租约_不推tip() {
        PendingHandOff handOff = f.freeze(a);
        f.world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001).build());
        f.sink.clear();

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.repo.saves()).extracting(PlayerSave::ownerEpoch).containsExactly(E);
        assertThat(f.locations).extracting(LocationCall::action).containsExactly("disconnected");
        assertThat(f.pushedTips(11)).isEmpty();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.transfers("lease_too_short")).isEqualTo(1);
    }

    @Test
    void 冻结中所在链路断开_按断线离开记下_交出成功后释放E加1并转重连租约() {
        PendingHandOff handOff = f.freeze(a);

        f.world.onLinkClosed(LINK);
        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.world.playerById(1002)).as("同链路上没冻结的照常移除").isNull();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.locations).extracting(LocationCall::action, LocationCall::playerId)
                .contains(org.assertj.core.groups.Tuple.tuple("disconnected", 1001L));
        assertThat(f.transfers("left")).isEqualTo(1);
    }

    @Test
    void 冻结中主动离开后所在链路又断开_仍按主动登出_交出成功后释放E加1并写登出墓碑() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        // gate 发完 PlayerLeave{voluntary} 之后链路断开（gate 重启 / 网络）：断链不能把已记下的主动登出降级成断线
        f.world.onLinkClosed(LINK);
        assertThat(f.world.playerById(1001)).as("实例保持到结局回来").isSameAs(a);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.locations).filteredOn(call -> call.playerId() == 1001)
                .as("写登出墓碑，不是重连租约（否则 30 s 内再进游戏被路由回旧场景旧坐标）")
                .containsExactly(new LocationCall("loggedOut", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.transfers("left")).isEqualTo(1);
    }

    @Test
    void 冻结中主动离开后所在链路又断开_交出没提交_写回释放E_仍写登出墓碑() {
        PendingHandOff handOff = f.freeze(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        f.world.onLinkClosed(LINK);

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.repo.saves()).filteredOn(save -> save.playerId() == 1001).extracting(PlayerSave::ownerEpoch)
                .containsExactly(E);
        assertThat(f.locations).filteredOn(call -> call.playerId() == 1001).extracting(LocationCall::action)
                .containsExactly("loggedOut");
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.transfers("lease_too_short")).isEqualTo(1);
    }

    @Test
    void 冻结中被请求让出E_交出成功后释放E加1并踢2017() {
        PendingHandOff handOff = f.freeze(a);

        f.world.onTakeoverRequested(1001, E);
        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.sink.kicks()).isEmpty();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, 2017));
        assertThat(f.transfers("taken_over")).isEqualTo(1);
    }

    @Test
    void 冻结中被请求让出E_交出没提交_写回释放E并踢2017() {
        PendingHandOff handOff = f.freeze(a);
        f.world.onTakeoverRequested(1001, E);

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(f.repo.saves()).extracting(PlayerSave::ownerEpoch).containsExactly(E);
        assertThat(f.sink.kicks()).containsExactly(new Kicked(LINK, 11, 1001, E, 2017));
        assertThat(f.pushedTips(11)).isEmpty();
    }

    @Test
    void 让出请求E加1在源节点什么也碰不到() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));
        f.sink.clear();

        f.world.onTakeoverRequested(1001, E + 1);

        assertThat(f.sink.events()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
    }

    @Test
    void 冻结中续约报失去E_忽略_交给交出结局() {
        f.freeze(a);

        f.world.onOwnershipLost(List.of(new OwnedPlayer(1001, E)));

        assertThat(f.world.playerById(1001)).isSameAs(a);
        assertThat(f.sink.kicks()).isEmpty();
        assertThat(a.frozen()).isTrue();
    }

    @Test
    void 冻结前在途的在线存盘回来被围栏拒_冻结中不踢人() {
        assertThat(a.wallet().add(0, 3).ok()).isTrue();
        assertThat(f.world.requestSave(a)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        PendingHandOff handOff = f.freeze(a);

        f.repo.takeProgress().complete(ProgressResult.FENCED);

        assertThat(f.sink.kicks()).isEmpty();
        assertThat(f.world.playerById(1001)).isSameAs(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));
        assertThat(f.sink.transfers()).hasSize(1);
    }

    @Test
    void 停服时冻结中_照常提交写回E_之后交出成功则释放E加1() {
        PendingHandOff handOff = f.freeze(a);

        f.world.shutdown();
        assertThat(f.repo.saves()).extracting(PlayerSave::playerId).containsExactlyInAnyOrder(1001L, 1002L);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.transfers("left")).isEqualTo(1);
        assertThat(f.inFlight()).isZero();
    }

    @Test
    void 停服时冻结中_写回先提交_交出被围栏拒_什么都不再做() {
        PendingHandOff handOff = f.freeze(a);
        f.world.shutdown();
        f.sink.clear();

        handOff.complete(new HandOffOutcome.Fenced());

        assertThat(f.sink.events()).isEmpty();
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.transfers("fenced")).isEqualTo(1);
    }

    @Test
    void 冻结中的玩家不被排空改派_交出后场景空了即销毁() {
        PendingHandOff handOff = f.freeze(a);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(12, 1002));
        Scene other = f.world.createScene(1);
        f.scene1.setDraining(true);

        f.world.drainStep();

        assertThat(a.scene()).isSameAs(f.scene1);
        assertThat(f.count("xm.scene.channel.relocations", "switching")).isEqualTo(1);
        assertThat(f.world.sceneById(f.scene1.sceneId())).as("还有人，不销毁").isNotNull();

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));
        f.world.drainStep();
        assertThat(f.world.sceneById(f.scene1.sceneId())).isNull();
        assertThat(other.playerCount()).isZero();
    }

    @Test
    void 冻结中同会话换角色进场_旧角色按离开写回_交出成功后只释放E加1() {
        PendingHandOff handOff = f.freeze(a);
        f.repo.putNewPlayer(2001, 1);
        f.world.onPlayerEnter(LINK, enterFrame(11, 2001, f.scene1.sceneId(), 1));
        f.repo.completeAll();
        assertThat(f.repo.saves()).extracting(PlayerSave::playerId).containsExactly(1001L);

        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
        assertThat(f.world.playerBySession(new SessionKey(LINK, 11)).playerId()).isEqualTo(2001);
    }

    // ------------------------------------------------------------------ 墓碑

    @Test
    void 交叉到达的离开_按墓碑以E和序号加1写位置_不释放E加1_只用一次() {
        long seqBefore = a.locationSeq();
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));

        assertThat(f.locations).containsExactly(
                new LocationCall("loggedOut", 1001, E, seqBefore + 1, f.scene1.sceneId()));
        assertThat(f.repo.releases()).as("墓碑绝不释放 E+1").isEmpty();
        assertThat(f.repo.saves()).isEmpty();
    }

    @Test
    void 交叉到达的断线离开_写重连租约_之后异步写失败不再重写位置但仍释放E加1() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        f.world.onPlayerLeave(LINK, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(1001).build());
        f.sink.transfers().get(0).onWriteFailed().run();

        assertThat(f.locations).extracting(LocationCall::action).containsExactly("disconnected");
        assertThat(f.repo.releases()).containsExactly(new Release(1001, E + 1));
    }

    @Test
    void 墓碑过期或玩家对不上_不写位置() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 9999));
        assertThat(f.locations).isEmpty();
        f.clock.advanceMillis(TransferFixture.TOMBSTONE_TTL.toMillis());
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        assertThat(f.locations).isEmpty();
    }

    @Test
    void 墓碑所在链路断开后_不再命中() {
        PendingHandOff handOff = f.freeze(a);
        handOff.complete(new HandOffOutcome.HandedOff(E + 1));

        f.world.onLinkClosed(LINK);
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));

        assertThat(f.locations).extracting(LocationCall::playerId).doesNotContain(1001L);
    }

    // ------------------------------------------------------------------ 目标节点：交出进场

    @Test
    void 交出进场_不拍LOGIN快照_立即续约一次_计ok_位置以E加1从序号1写() {
        f.repo.putSavedPlayer(3001, 8, 1, new Vec3(120, 130, 0));

        f.transferEnter(31, 3001, f.scene1.sceneId(), 8);
        f.repo.completeAll();

        assertThat(f.sink.messageIdsTo(LINK, 31)).startsWith(79, 21);
        assertThat(f.sink.results()).containsExactly(new EnterResult(LINK, 31, 3001, 8, 0));
        assertThat(f.snapshots).as("D8：交出进场不拍 LOGIN").isEmpty();
        assertThat(f.renewed).containsExactly(new OwnedPlayer(3001, 8));
        assertThat(f.locations).containsExactly(new LocationCall("entered", 3001, 8, 1, f.scene1.sceneId()));
        assertThat(f.count("xm.scene.transfer.enters", "ok")).isEqualTo(1);
        assertThat(f.world.ownedPlayers()).contains(new OwnedPlayer(3001, 8));
    }

    @Test
    void 交出进场_同图保留坐标_换图落出生点() throws Exception {
        f.repo.putSavedPlayer(3001, 8, 1, new Vec3(120, 130, 0));
        f.repo.putSavedPlayer(3002, 8, 1, new Vec3(120, 130, 0));

        f.transferEnter(31, 3001, f.scene1.sceneId(), 8);
        f.transferEnter(32, 3002, f.scene2.sceneId(), 8);
        f.repo.completeAll();

        SceneWorldTest.assertLocation(ActorCreateS2C.parseFrom(f.sink.to(LINK, 31).get(1).getSerializedMessage()),
                120, 130, 0);
        Vec3 spawn = FakeSceneTables.SPAWN_2;
        SceneWorldTest.assertLocation(ActorCreateS2C.parseFrom(f.sink.to(LINK, 32).get(1).getSerializedMessage()),
                spawn.x(), spawn.y(), spawn.z());
    }

    @Test
    void 交出进场_库里epoch不符_回3023并释放_计failed_不续约() {
        f.repo.putNewPlayer(3001, 9);

        f.transferEnter(31, 3001, f.scene1.sceneId(), 8);
        f.repo.completeAll();

        assertThat(f.sink.results()).containsExactly(new EnterResult(LINK, 31, 3001, 8, 3023));
        assertThat(f.repo.releases()).containsExactly(new Release(3001, 8));
        assertThat(f.count("xm.scene.transfer.enters", "failed")).isEqualTo(1);
        assertThat(f.renewed).isEmpty();
    }

    @Test
    void 普通进场_照旧拍LOGIN快照_不立即续约_不计交出进场() {
        f.repo.putNewPlayer(3001, 8);
        f.world.onPlayerEnter(LINK, enterFrame(31, 3001, f.scene1.sceneId(), 8));
        f.repo.completeAll();

        assertThat(f.snapshots).extracting(TransferFixture.Snapshot::cause).containsExactly(PlayerSnapshots.Cause.LOGIN);
        assertThat(f.renewed).isEmpty();
        assertThat(f.count("xm.scene.transfer.enters", "ok")).isZero();
    }
}
