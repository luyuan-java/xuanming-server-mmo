package com.game.scene.world;

import static com.game.scene.world.TransferFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.team.TeamFollow;
import com.game.scene.testing.FakePlayerRepository.Release;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 独立审查补充：运维开关不重置数据库健康结论，交出在途仍由原事务收尾。 */
class NodeEvacuationLifecycleTest {
    private static final Duration BUDGET = Duration.ofSeconds(15);

    @Test
    void 交出结局不明后的存储闸不能被撤标再打标绕过() {
        var f = new TransferFixture(TeamFollow.NONE, PlayerChannels.NONE,
                new EvacuationPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        f.enter(11, 1001, f.scene1, 5);
        var remaining = f.enter(12, 1002, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        f.targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 1);
        f.repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.Failed(1001, 5,
                new PlayerRepository.HandOffAttempts(1, List.of(42L))));
        f.repo.takeProbe().complete(new PlayerRepository.ProbeOutcome.Lost());
        assertThat(f.world.playerById(1001)).isNull();
        assertThat(f.targets.pendingCount()).isZero();
        assertThat(f.world.endNodeEvacuation()).isTrue();
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        assertThat(f.targets.pendingCount()).as("数据库结局不明的30s闸属于节点健康，不能随ADMIN撤销清除").isZero();
        assertThat(remaining.frozen()).isFalse();
        assertThat(f.pushedTips(12)).isEmpty();
        f.clock.advanceMillis(29_999);
        f.world.drainStep();
        assertThat(f.targets.pendingCount()).isZero();
        f.clock.advanceMillis(1);
        f.world.drainStep();
        assertThat(f.targets.take().playerId()).isEqualTo(1002);
    }

    @Test
    void 撤销ADMIN不取消已冻结交出_成功后仍只发一次新epoch改绑() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        f.targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 1);
        var transaction = f.repo.takeHandOff();
        assertThat(player.frozen()).isTrue();
        assertThat(f.world.endNodeEvacuation()).isTrue();
        assertThat(player.frozen()).isTrue();
        assertThat(f.world.nodeEvacuated()).isFalse();
        assertThat(f.sink.transfers()).isEmpty();
        transaction.complete(new PlayerRepository.HandOffOutcome.HandedOff(6));
        assertThat(f.sink.transfers()).singleElement().satisfies(t -> assertThat(t.toEpoch()).isEqualTo(6));
        assertThat(f.repo.releases()).isEmpty();
        assertThat(f.world.nodeEvacuated()).isTrue();
        assertThat(f.targets.pendingCount()).isZero();
    }

    @Test
    void 每次探测确认未提交会中断连续失败计数_三次离散故障不关全局闸() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        for (int attempt = 0; attempt < 3; attempt++) {
            f.targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 1);
            f.repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.Failed(1001, 5,
                    new PlayerRepository.HandOffAttempts(1, List.of(42L))));
            assertThat(player.frozen()).isTrue();
            f.repo.takeProbe().complete(new PlayerRepository.ProbeOutcome.NotCommitted());
            assertThat(player.frozen()).isFalse();
            assertThat(f.pushedTips(11)).isEmpty();
            f.clock.advanceMillis(1000L << attempt);
            f.world.drainStep();
            assertThat(f.targets.pendingCount()).as("第%s次后只受玩家退避限制", attempt + 1).isEqualTo(1);
        }
        assertThat(f.sink.kicks()).isEmpty();
        assertThat(f.world.playerById(1001)).isSameAs(player);
    }

    @Test
    void 已过期选点迟到不能清除新槽或抢先冻结_新槽结果只交出一次() {
        var f = new TransferFixture();
        var player = f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, BUDGET);
        var old = f.targets.take();
        f.clock.advanceMillis(5000);
        f.world.drainStep();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        f.clock.advanceMillis(1000);
        f.world.drainStep();
        var fresh = f.targets.take();
        var slot = player.switching();
        old.chosen(TARGET_NODE, REMOTE_SCENE, 1);
        assertThat(player.switching()).isSameAs(slot);
        assertThat(player.frozen()).isFalse();
        assertThat(f.repo.pendingHandOffs()).isZero();
        fresh.chosen(TARGET_NODE, REMOTE_SCENE, 1);
        assertThat(f.repo.pendingHandOffs()).isEqualTo(1);
        f.repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.HandedOff(6));
        assertThat(f.sink.transfers()).hasSize(1);
        assertThat(f.world.nodeEvacuated()).isTrue();
    }

    @Test
    void 停服移出冻结实体后迟到交出必须释放新epoch且不能发改绑() {
        var f = new TransferFixture();
        f.enter(11, 1001, f.scene1, 5);
        f.world.beginNodeEvacuation(NodeEvacuation.Cause.SHUTDOWN, BUDGET);
        f.targets.take().chosen(TARGET_NODE, REMOTE_SCENE, 1);
        var transaction = f.repo.takeHandOff();
        f.world.shutdown();
        assertThat(f.world.playerCount()).isZero();
        assertThat(f.world.nodeEvacuated()).isFalse();
        transaction.complete(new PlayerRepository.HandOffOutcome.HandedOff(6));
        assertThat(f.sink.transfers()).isEmpty();
        assertThat(f.repo.releases()).containsExactly(new Release(1001, 6));
        assertThat(f.world.nodeEvacuated()).isTrue();
    }

    @Test
    void 管理升级停服与冲突不延长截止_纳秒回绕仍正确() {
        long now = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        var admin = NodeEvacuation.begin(NodeEvacuation.Cause.ADMIN, now, BUDGET);
        var stop = admin.upgrade(NodeEvacuation.Cause.SHUTDOWN, now, BUDGET);
        var conflict = stop.upgrade(NodeEvacuation.Cause.CONFLICT, now + Duration.ofSeconds(5).toNanos(), BUDGET);
        assertThat(conflict.deadlineNanos()).isEqualTo(stop.deadlineNanos());
        assertThat(conflict.upgrade(NodeEvacuation.Cause.ADMIN, now, BUDGET)).isSameAs(conflict);
        assertThat(conflict.upgrade(NodeEvacuation.Cause.SHUTDOWN, now, BUDGET)).isSameAs(conflict);
        assertThat(conflict.expired(stop.deadlineNanos() - 1)).isFalse();
        assertThat(conflict.expired(stop.deadlineNanos())).isTrue();
        assertThat(admin.expired(stop.deadlineNanos())).isFalse();
    }
}
