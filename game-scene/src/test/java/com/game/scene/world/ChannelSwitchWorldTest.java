package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static com.game.scene.world.TransferFixture.*;

import com.game.api.proto.ClientForward;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneInfoComp;
import com.game.proto.SceneInfoRequest;
import com.game.proto.SceneInfoS2C;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerRepository.HandOffAttempts;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProbeOutcome;
import com.game.scene.world.RemoteSwitchTargets.Selection;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 5.6 场景侧：目录生命周期围栏、同节点预检、SQL 成功边界与冷却写在途。 */
class ChannelSwitchWorldTest {
    private record Read(int config, Consumer<SceneChannelDirectory> callback) { }
    private record Write(long playerId, int seconds, Runnable done) { }
    private static final class Channels implements PlayerChannels {
        final Queue<Read> reads = new ArrayDeque<>();
        final Queue<Write> writes = new ArrayDeque<>();
        @Override public void directory(int config, Consumer<SceneChannelDirectory> callback) { reads.add(new Read(config, callback)); }
        @Override public void switched(long playerId, int seconds, Runnable done) { writes.add(new Write(playerId, seconds, done)); }
    }
    private final Channels channels = new Channels();
    private final TransferFixture f = new TransferFixture(TeamFollow.NONE, channels);
    private final ScenePlayer player = f.enter(11, 1001, f.scene1, 5);

    private void directory(boolean requested) {
        f.handler.onClientForward(LINK, ClientForward.newBuilder().setPlayerId(player.playerId()).setSessionId(11)
                .setMessageId(Contracts.IDS.sceneInfoC2S()).setBody(SceneInfoRequest.newBuilder()
                        .setWithChannelDirectory(requested).build().toByteString()).build());
    }

    @Test
    void 普通43零IO_显式目录成功或失败各推一条31() throws Exception {
        directory(false);
        assertThat(channels.reads).isEmpty();
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(31);
        var plain = SceneInfoS2C.parseFrom(f.sink.to(LINK, 11).getFirst().getSerializedMessage());
        assertThat(plain.getSceneInfoList()).containsExactly(f.scene1.info());
        assertThat(plain.hasChannelDirectory()).isFalse();
        f.sink.clear();
        directory(true);
        assertThat(f.sink.events()).isEmpty();
        Read read = channels.reads.remove(); assertThat(read.config()).isEqualTo(1);
        var expected = SceneChannelDirectory.newBuilder().setZoneId(7).setSceneConfigId(1).setSwitchEnabled(true).build();
        read.callback().accept(expected);
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(31);
        assertThat(SceneInfoS2C.parseFrom(f.sink.to(LINK, 11).getFirst().getSerializedMessage()).getChannelDirectory()).isEqualTo(expected);
        f.sink.clear(); directory(true); channels.reads.remove().callback().accept(null);
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(31);
        assertThat(SceneInfoS2C.parseFrom(f.sink.to(LINK, 11).getFirst().getSerializedMessage()).hasChannelDirectory()).isFalse();
    }

    @Test
    void 镜像里的43不读目录_源场景改变后旧目录回调丢弃() throws Exception {
        directory(true); Read old = channels.reads.remove();
        Scene mirror = f.world.addScene(SceneInfoComp.newBuilder().setSceneId(9900).setSceneConfigId(1)
                .setMirrorConfigId(1).build(), SceneKind.MIRROR, f.scene1.sceneId());
        f.world.switchScene(player, mirror); f.sink.clear();
        old.callback().accept(SceneChannelDirectory.getDefaultInstance());
        assertThat(f.sink.events()).isEmpty();
        directory(true);
        assertThat(channels.reads).isEmpty();
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(31);
        assertThat(SceneInfoS2C.parseFrom(f.sink.to(LINK, 11).getFirst().getSerializedMessage()).hasChannelDirectory()).isFalse();
    }

    @Test
    void 重连换实例后旧目录不发给新会话() {
        directory(true); Read old = channels.reads.remove();
        f.repo.putNewPlayer(1001, 6);
        f.world.onPlayerEnter(LINK, SceneWorldTest.enterFrame(21, 1001, f.scene1.sceneId(), 6)); f.repo.completeAll();
        f.sink.clear(); old.callback().accept(SceneChannelDirectory.getDefaultInstance());
        assertThat(f.sink.events()).isEmpty();
    }

    @Test
    void 本节点显式选线先走manager_成功后才写冷却_写在途3014_完成后预检3023() {
        Scene second = f.world.createScene(1);
        f.enterScene(11, 1001, second.sceneId(), 1, 1);
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(63);
        assertThat(player.scene()).isSameAs(f.scene1);
        var select = f.targets.take();
        assertThat(select.clientChannelPick()).isTrue();
        assertThat(channels.writes).isEmpty();
        select.chosen(LOCAL_NODE, second.sceneId(), 1, 30);
        assertThat(player.scene()).isSameAs(second);
        assertThat(f.sink.messageIdsTo(LINK, 11)).containsExactly(63, 79, 21);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(player.frozen()).isFalse();
        Write write = channels.writes.remove();
        assertThat(write.playerId()).isEqualTo(1001); assertThat(write.seconds()).isEqualTo(30);
        f.sink.clear(); f.enterScene(11, 1001, f.scene1.sceneId(), 1, 2);
        assertThat(f.enterSceneReplies(11)).containsExactly(3014);
        assertThat(f.targets.pendingCount()).isZero();
        write.done().run();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        f.sink.clear(); f.enterScene(11, 1001, f.scene1.sceneId(), 1, 3);
        f.targets.take().complete(new Selection.Refused(1008));
        assertThat(f.enterSceneReplies(11)).containsExactly(0);
        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(f.sink.messageIdsTo(LINK, 11)).doesNotContain(79);
        assertThat(channels.writes).isEmpty();
    }

    @Test
    void 选线预检拒绝不换图不写冷却_选目标期间源场景改变也不执行旧选线() {
        f.enterScene(11, 1001, f.scene2.sceneId(), 1, 1);
        f.targets.take().complete(new Selection.Refused(3023));
        assertThat(player.scene()).isSameAs(f.scene1); assertThat(channels.writes).isEmpty();
        assertThat(f.pushedTips(11)).containsExactly(3023);
        f.sink.clear(); f.enterScene(11, 1001, REMOTE_SCENE, 1, 2);
        var stale = f.targets.take(); f.world.switchScene(player, f.scene2); f.sink.clear();
        stale.chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        assertThat(f.repo.pendingHandOffs()).isZero(); assertThat(channels.writes).isEmpty();
        assertThat(f.pushedTips(11)).containsExactly(3023);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 本节点目标应答缺节点也算3028_不换图不写冷却() {
        Scene second = f.world.createScene(1);
        f.enterScene(11, 1001, second.sceneId(), 1, 1);
        f.targets.take().chosen(0, second.sceneId(), 1, 30);
        assertThat(player.scene()).isSameAs(f.scene1);
        assertThat(f.pushedTips(11)).containsExactly(3028);
        assertThat(channels.writes).isEmpty();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
    }

    @Test
    void 本机冷却回调迟到_不能清除新会话的新换图槽() {
        Scene second = f.world.createScene(1);
        f.enterScene(11, 1001, second.sceneId(), 1, 1);
        f.targets.take().chosen(LOCAL_NODE, second.sceneId(), 1, 30);
        Write stale = channels.writes.remove();
        ScenePlayer fresh = f.enter(21, 1001, second, 6);
        // 自动按图选择维持原有路径；旧回调仍不得清掉新实例已有的新槽。
        f.world.beginRemoteSwitch(fresh, REMOTE_SCENE, 1);
        assertThat(f.targets.take().clientChannelPick()).isFalse();
        PlayerSwitch active = fresh.switching();
        stale.done().run();
        assertThat(f.world.playerById(1001)).isSameAs(fresh);
        assertThat(fresh.switching()).isSameAs(active);
        assertThat(fresh.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        assertThat(channels.writes).isEmpty();
    }

    @Test
    void 本机切线冷却写在途重连_新会话3014且不选第二次_写完后走共享冷却预检() {
        Scene second = f.world.createScene(1);
        f.enterScene(11, 1001, second.sceneId(), 1, 1);
        f.targets.take().chosen(LOCAL_NODE, second.sceneId(), 1, 30);
        Write pending = channels.writes.remove();
        ScenePlayer fresh = f.enter(21, 1001, second, 6);
        assertThat(fresh.switching()).isNull();
        f.enterScene(21, 1001, f.scene1.sceneId(), 1, 2);
        assertThat(f.enterSceneReplies(21)).containsExactly(3014);
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(fresh.scene()).isSameAs(second);
        pending.done().run();
        f.sink.clear();
        f.enterScene(21, 1001, f.scene1.sceneId(), 1, 3);
        var select = f.targets.take();
        assertThat(select.clientChannelPick()).isTrue();
        select.complete(new Selection.Refused(1008));
        assertThat(f.enterSceneReplies(21)).containsExactly(0);
        assertThat(f.pushedTips(21)).containsExactly(3023);
        assertThat(f.sink.messageIdsTo(LINK, 21)).doesNotContain(79);
        assertThat(channels.writes).isEmpty();
    }

    @Test
    void 本机冷却短闸可过期_更早写回调不能删后一次切线的新短闸() {
        Scene second = f.world.createScene(1);
        f.enterScene(11, 1001, second.sceneId(), 1, 1);
        f.targets.take().chosen(LOCAL_NODE, second.sceneId(), 1, 30);
        Write stale = channels.writes.remove();
        f.clock.advanceMillis(4001);
        f.enter(21, 1001, second, 6);
        Scene third = f.world.createScene(1);
        f.enterScene(21, 1001, third.sceneId(), 1, 2);
        f.targets.take().chosen(LOCAL_NODE, third.sceneId(), 1, 30);
        Write pending = channels.writes.remove();
        f.enter(31, 1001, third, 7);
        stale.done().run();
        f.enterScene(31, 1001, f.scene1.sceneId(), 1, 3);
        assertThat(f.enterSceneReplies(31)).containsExactly(3014);
        assertThat(f.targets.pendingCount()).isZero();
        pending.done().run();
        f.sink.clear();
        f.enterScene(31, 1001, f.scene1.sceneId(), 1, 4);
        assertThat(f.enterSceneReplies(31)).containsExactly(0);
        assertThat(f.targets.pendingCount()).isEqualTo(1);
    }

    @Test
    void 只带地图与按id进镜像不走手动预检_不写冷却() {
        f.enterScene(11, 1001, 0, 2, 1);
        assertThat(player.scene()).isSameAs(f.scene2); assertThat(f.targets.pendingCount()).isZero();
        Scene mirror = f.world.addScene(SceneInfoComp.newBuilder().setSceneId(9900).setSceneConfigId(2)
                .setMirrorConfigId(1).build(), SceneKind.MIRROR, f.scene2.sceneId());
        f.enterScene(11, 1001, mirror.sceneId(), 2, 2);
        assertThat(player.scene()).isSameAs(mirror); assertThat(f.targets.pendingCount()).isZero();
        assertThat(channels.writes).isEmpty();
    }

    @Test
    void 跨节点先SQL提交_再写全局冷却_最后通知gate_写在途继续冻结() {
        f.resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        assertThat(channels.writes).isEmpty();
        f.repo.takeHandOff().complete(new HandOffOutcome.HandedOff(6));
        assertThat(player.frozen()).isTrue(); assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.inFlight()).isEqualTo(1);
        f.sink.clear(); f.enterScene(11, 1001, REMOTE_SCENE, 1, 2);
        assertThat(f.enterSceneReplies(11)).containsExactly(3014);
        channels.writes.remove().done().run();
        assertThat(f.sink.transfers()).singleElement().satisfies(t -> assertThat(t.toEpoch()).isEqualTo(6));
        assertThat(f.world.playerById(1001)).isNull(); assertThat(f.inFlight()).isZero();
        assertThat(channels.writes).isEmpty();
    }

    @Test
    void SQL未提交不占冷却_探测确认成功才写() {
        f.resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        f.repo.takeHandOff().complete(new HandOffOutcome.LeaseTooShort());
        assertThat(channels.writes).isEmpty(); assertThat(f.sink.transfers()).isEmpty();
        f.resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        f.repo.takeHandOff().complete(new HandOffOutcome.Failed(1001, 5, new HandOffAttempts(1, List.of(42L))));
        assertThat(channels.writes).isEmpty();
        f.repo.takeProbe().complete(new ProbeOutcome.HandedOff(6));
        assertThat(channels.writes).hasSize(1); assertThat(f.sink.transfers()).isEmpty();
        channels.writes.remove().done().run(); assertThat(f.sink.transfers()).hasSize(1);
    }

    @Test
    void 冷却写在途会话离开_完成后按原交接规则释放新epoch_不发gate() {
        f.resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        f.repo.takeHandOff().complete(new HandOffOutcome.HandedOff(6));
        f.world.onPlayerLeave(LINK, SceneWorldTest.leave(11, 1001));
        channels.writes.remove().done().run();
        assertThat(f.sink.transfers()).isEmpty(); assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.repo.releases()).anySatisfy(r -> { assertThat(r.playerId()).isEqualTo(1001); assertThat(r.ownerEpoch()).isEqualTo(6); });
        assertThat(f.inFlight()).isZero();
    }

    @Test
    void SQL交出后冷却仍在途_停服摘除实体_续接只释放新epoch不发gate() {
        f.resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        f.repo.takeHandOff().complete(new HandOffOutcome.HandedOff(6));
        Write pending = channels.writes.remove();
        f.world.shutdown();
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.inFlight()).isEqualTo(1);
        pending.done().run();
        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new com.game.scene.testing.FakePlayerRepository.Release(1001, 6));
        assertThat(f.inFlight()).isZero();
    }

    @Test
    void 非客户端交接即使应答夹带冷却也不写() {
        f.world.beginRemoteSwitch(player, REMOTE_SCENE, 1);
        var select = f.targets.take(); assertThat(select.clientChannelPick()).isFalse();
        select.chosen(TARGET_NODE, REMOTE_SCENE, 1, 30);
        f.repo.takeHandOff().complete(new HandOffOutcome.HandedOff(6));
        assertThat(channels.writes).isEmpty(); assertThat(f.sink.transfers()).hasSize(1);
    }
}
