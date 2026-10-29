package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.TransferFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.battle.BattleFreezeTestAccess;
import com.game.scene.team.TeamFollow;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 节点疏散经真实世界状态机、选点与交接回调推进；测试不把选点当作所有权已转移。 */
class NodeEvacuationTest {
    private static final Duration BUDGET = Duration.ofSeconds(15);

    @Test void 本地同图优先而远端同图优先于本地默认图() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene2, 5);
        f.scene2.beginDrain(DrainCause.PLAN, f.clock.nanoTime());
        f.world.drainStep();
        var selected = f.targets.take();
        assertThat(selected.wantSceneConfigId()).isEqualTo(2);
        assertThat(selected.fallbackConfigId()).as("先保留本地默认图作第二步").isZero();
        assertThat(selected.reason()).isEqualTo(SwitchReason.EVACUATE);
        assertThat(selected.excludeSourceNode()).isTrue();
        assertThat(selected.clientChannelPick()).isFalse();
        assertThat(player.scene()).isSameAs(f.scene2);
        assertThat(player.frozen()).isFalse();
        selected.complete(new RemoteSwitchTargets.Selection.Refused(3000));
        assertThat(player.scene()).isSameAs(f.scene1);
        assertThat(f.pushedTips(11)).isEmpty();
        assertThat(f.repo.pendingHandOffs()).isZero();
    }

    @Test void 同节点同图不用SQL且不受此前远端退避阻挡() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.scene1.beginDrain(DrainCause.PLAN, f.clock.nanoTime());
        f.world.drainStep();
        f.targets.take().complete(new RemoteSwitchTargets.Selection.Failed("暂不可达"));
        var sibling = f.world.createScene(1);
        f.world.drainStep();
        assertThat(player.scene()).isSameAs(sibling);
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(f.repo.pendingHandOffs()).isZero();
    }

    @Test void 节点疏散排除所有本地场景并等SQL确认才通知gate() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene2, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        var selected = f.targets.take();
        assertThat(selected.wantSceneConfigId()).isEqualTo(2);
        assertThat(selected.fallbackConfigId()).isEqualTo(1);
        assertThat(selected.excludeSourceNode()).isTrue();
        assertThat(f.sink.transfers()).isEmpty();
        selected.chosen(TARGET_NODE, REMOTE_SCENE, 2, 60);
        assertThat(player.frozen()).isTrue();
        assertThat(f.world.nodeEvacuated()).isFalse();
        assertThat(f.sink.transfers()).isEmpty();
        assertThat(player.switching().channelCooldownSeconds()).as("服务器改派不写玩家切线冷却").isZero();
        f.repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.HandedOff(6));
        assertThat(f.sink.transfers()).singleElement().satisfies(transfer -> {
            assertThat(transfer.fromEpoch()).isEqualTo(5);
            assertThat(transfer.toEpoch()).isEqualTo(6);
            assertThat(transfer.targetNodeId()).isEqualTo(TARGET_NODE);
        });
        assertThat(f.world.playerCount()).isZero();
        assertThat(f.world.nodeEvacuated()).isTrue();
        assertThat(f.transfers("evacuate", "handed_off")).isEqualTo(1);
    }

    @Test void 原节点同号的新进程目标也拒绝且静默退避() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.CONFLICT, BUDGET);
        f.targets.take().chosen(LOCAL_NODE, REMOTE_SCENE, 1);
        assertThat(player.frozen()).isFalse();
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.pushedTips(11)).isEmpty();
        f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.clock.advanceMillis(1000);
        f.world.drainStep();
        assertThat(f.targets.pendingCount()).isEqualTo(1);
    }

    @Test void 选点失败指数退避且取消排空后的迟到结果不冻结() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        f.targets.take().complete(new RemoteSwitchTargets.Selection.Failed("短暂失败"));
        f.clock.advanceMillis(999); f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.clock.advanceMillis(1); f.world.drainStep();
        f.targets.take().complete(new RemoteSwitchTargets.Selection.Refused(3000));
        f.clock.advanceMillis(1999); f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.clock.advanceMillis(1); f.world.drainStep();
        var late = f.targets.take();
        assertThat(f.world.endNodeEvacuation()).isTrue();
        late.chosen(TARGET_NODE, REMOTE_SCENE, 1);
        assertThat(player.frozen()).isFalse();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(f.pushedTips(11)).isEmpty();
    }

    @Test void 并发上限同时包括选点和SQL冻结_完成后立即补位() {
        var f = new TransferFixture(TeamFollow.NONE, PlayerChannels.NONE,
                new EvacuationPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        f.enter(12, 1002, f.scene1, 5);
        f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        assertThat(f.targets.pendingCount()).isEqualTo(1);
        var first = f.targets.take();
        assertThat(first.playerId()).as("按无符号玩家号确定顺序").isEqualTo(1001);
        first.chosen(TARGET_NODE, REMOTE_SCENE, 1);
        f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.LeaseTooShort());
        assertThat(f.targets.take().playerId()).isEqualTo(1002);
        assertThat(f.pushedTips(11)).isEmpty();
    }

    @Test void 战斗和已有换图均跳过_存储高水位禁止新增冻结() {
        var f = new TransferFixture();
        var fighting = f.enter(11, 1001, f.scene1, 5);
        var switching = f.enter(12, 1002, f.scene1, 5);
        f.resolveRemote(switching);
        BattleFreezeTestAccess.freeze(fighting);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        assertThat(f.targets.pendingCount()).isZero();
        BattleFreezeTestAccess.clear(fighting);
        f.repo.setEvacuationCapacityAvailable(false);
        f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.repo.setEvacuationCapacityAvailable(true);
        f.world.drainStep();
        assertThat(f.targets.take().playerId()).isEqualTo(1001);
    }

    @Test void 进场加载取消与新进场拒绝_迟到加载不能复活() {
        var f = new TransferFixture();
        f.repo.putNewPlayer(1001, 5);
        f.world.onPlayerEnter(LINK, enterFrame(11, 1001, f.scene1.sceneId(), 5));
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        assertThat(f.sink.results()).singleElement().satisfies(reply -> assertThat(reply.tipId()).isEqualTo(3023));
        f.repo.completeAll();
        assertThat(f.world.playerCount()).isZero();
        f.world.onPlayerEnter(LINK, enterFrame(12, 1002, f.scene2.sceneId(), 6));
        assertThat(f.sink.results()).hasSize(2).allSatisfy(reply -> assertThat(reply.tipId()).isEqualTo(3023));
        assertThat(f.world.nodeEvacuated()).isTrue();
    }

    @Test void 疏散时不按新计划建场景_撤销只恢复最新计划() {
        var f = new TransferFixture();
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        var channel = ChannelPlanApplyTest.active(ChannelPlanApplyTest.A, 1);
        f.world.applyChannelPlan(10, java.util.List.of(channel));
        assertThat(f.world.sceneById(channel.getSceneId())).isNull();
        assertThat(f.world.endNodeEvacuation()).isTrue();
        assertThat(f.world.sceneById(channel.getSceneId())).isNotNull();
        assertThat(f.world.sceneById(channel.getSceneId()).draining()).isFalse();
        assertThat(f.world.appliedPlanVersion()).isEqualTo(10);
    }

    @Test void 待冲刷帧阻止宣称排空_冲突升级不延长期限也不能撤销() {
        var f = new TransferFixture();
        f.sink.setPendingTransferWrites(1);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.SHUTDOWN, BUDGET);
        long deadline = f.world.nodeEvacuation().deadlineNanos();
        f.clock.advanceMillis(5000);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.CONFLICT, BUDGET);
        assertThat(f.world.nodeEvacuation().deadlineNanos()).isEqualTo(deadline);
        assertThat(f.world.nodeEvacuation().cause()).isEqualTo(NodeEvacuation.Cause.CONFLICT);
        assertThat(f.world.endNodeEvacuation()).isFalse();
        assertThat(f.world.nodeEvacuated()).isFalse();
        f.sink.setPendingTransferWrites(0);
        assertThat(f.world.nodeEvacuated()).isTrue();
        f.clock.advanceMillis(10000);
        assertThat(f.world.nodeEvacuation().expired(f.clock.nanoTime())).isTrue();
    }
}
