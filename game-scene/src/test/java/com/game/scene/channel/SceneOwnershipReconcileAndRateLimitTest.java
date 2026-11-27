package com.game.scene.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.scene.battle.BattleFixture;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.Scene;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SceneOwnershipReconcileAndRateLimitTest {

    @Test
    void reconcilesAndDrainsEvenWhenLeaseLostOrNodeIdTakenOverAndTracksOwnHolderKnown() {
        WorldChannelStore store = Mockito.mock(WorldChannelStore.class);
        WorldChannel mine = WorldChannel.newBuilder()
                .setSceneId(9001L).setSceneConfigId(1).setNodeId(7).setState(ChannelState.CHANNEL_ACTIVE).build();
        Mockito.when(store.planVersion(1)).thenReturn(1L, 2L, 3L);
        Mockito.when(store.readPlan(1)).thenReturn(
                new WorldPlan(1L, List.of(mine)),
                new WorldPlan(2L, List.of(mine)),
                new WorldPlan(3L, List.of(mine)));

        AtomicBoolean leaseValid = new AtomicBoolean(true);
        AtomicBoolean holderMine = new AtomicBoolean(true);
        List<List<WorldChannel>> applied = new ArrayList<>();

        var follower = new ChannelPlanFollower(store, 1, 7, leaseValid::get, () -> 1L,
                (ver, channels, epoch) -> applied.add(channels),
                () -> { }, new SceneMetrics(new SimpleMeterRegistry()), epoch -> { })
                .withOwnHolderCheck(holderMine::get);

        // 1. 正常持有租约且为自身持有者 -> 拥有场景 9001，ownHolderKnown=true
        assertThat(follower.syncOnce()).isTrue();
        assertThat(follower.isOwnHolderKnown()).isTrue();
        assertThat(applied.getLast()).hasSize(1);

        // 2. 失联/租约失效期间 -> 照常读控制面并将本地频道列表收空（排空不归本进程的场景，绝不新建）
        leaseValid.set(false);
        follower.pollOnce();
        assertThat(applied.getLast()).isEmpty();

        // 3. 节点号被新进程接走 -> 即便本地租约尚在也强制全量排空
        leaseValid.set(true);
        holderMine.set(false);
        follower.pollOnce();
        assertThat(applied.getLast()).isEmpty();
    }

    @Test
    void drainStepRateLimitsResidentMovementsPerRoundWhileEmptyScenesDestroyImmediately() {
        BattleFixture f = new BattleFixture();
        long drainingWithPlayers = 5001L;
        Scene targetActive = f.world.createScene(1);
        Scene emptyDraining = f.world.createScene(1);

        // 在 5001 放入 5 名玩家，设置每轮排空住户限速 = 2
        for (int i = 0; i < 5; i++) {
            f.enter(10 + i, 1001L + i);
        }
        f.world.setMaxDrainResidentsPerRound(2);

        // 应用计划只保留 targetActive，将 5001 与 emptyDraining 置为排空
        WorldChannel keep = WorldChannel.newBuilder()
                .setSceneId(targetActive.sceneId()).setSceneConfigId(1).setNodeId(1).setState(ChannelState.CHANNEL_ACTIVE).build();
        f.world.applyChannelPlan(10L, List.of(keep));

        // applyChannelPlan 内部推进了第 1 轮：空场景立刻销毁，5001 仅迁走 2 名住户（剩 3 名）
        assertThat(sceneIds(f)).doesNotContain(emptyDraining.sceneId());
        assertThat(countOn(f, drainingWithPlayers)).isEqualTo(3);

        // 第 2 轮再迁走 2 名（剩 1 名）
        f.world.drainStep();
        assertThat(countOn(f, drainingWithPlayers)).isEqualTo(1);

        // 第 3 轮迁走最后 1 名并销毁原场景
        assertThat(f.world.drainStep()).isEqualTo(1);
        assertThat(sceneIds(f)).doesNotContain(drainingWithPlayers);
        assertThat(targetActive.playerCount()).isEqualTo(5);
    }

    private static List<Long> sceneIds(BattleFixture f) {
        return f.world.sceneEntries().stream().map(SceneEntry::getSceneId).toList();
    }

    private static int countOn(BattleFixture f, long sceneId) {
        return f.world.sceneEntries().stream()
                .filter(e -> e.getSceneId() == sceneId)
                .mapToInt(SceneEntry::getPlayerCount)
                .findFirst()
                .orElse(-1);
    }
}
