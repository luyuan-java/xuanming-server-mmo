package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 只守世界与收件箱钩子的持久化边界；不冒实际Inbox客户端或真实SQL集成。 */
class InboxFinalSaveLifecycleTest {
    private final FakePlayerRepository repository = new FakePlayerRepository();
    private final RecordingInbox hooks = new RecordingInbox();
    private final SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(),
            repository, new AtomicLong(9000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(),
            PlayerInitializer.NONE, PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE,
            CrossNodeSwitch.DISABLED, SceneInstances.DISABLED, BattleHooks.NONE, ZoneTravel.DISABLED, hooks);
    private final Scene scene = world.createScene(1);

    private ScenePlayer enter() {
        repository.put(new PlayerData(7, 1, 1, 1, "", 1, 1, new Vec3(180, 200, 0),
                PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(0)).build()));
        world.onPlayerEnter(1, SceneWorldTest.enterFrame(11, 7, scene.sceneId(), 1));
        repository.completeAll();
        assertThat(hooks.entered).isEqualTo(1);
        return world.playerById(7);
    }

    @Test
    void 离场接管凭据与迟到应用通知不提升收件箱资产确认基准() {
        repository.setAutoFinalRegistration(false);
        ScenePlayer player = enter();
        player.wallet().add(0, 77);
        world.onPlayerLeave(1, SceneWorldTest.leave(11, 7));
        var pending = repository.takeFinalRegistration();
        pending.complete(new PlayerRepository.FinalSaveRegistration.Durable(pending.receipt(), false));
        world.onFinalApplied(applied(pending.receipt()));

        assertThat(pending.save().state().getCurrency().getBalances(0)).isEqualTo(77);
        assertThat(hooks.leaving).hasSize(1);
        assertThat(hooks.leaving.getFirst().getCurrency().getBalances(0)).isZero();
        assertThat(player.persistedState().getCurrency().getBalances(0)).isZero();
        assertThat(hooks.persisted).isEmpty();
    }

    @Test
    void 应用通知先到再认接管凭据也不冒在线存盘回执() {
        repository.setAutoFinalRegistration(false);
        ScenePlayer player = enter();
        player.wallet().add(0, 55);
        world.onTakeoverRequested(7, 1);
        var pending = repository.takeFinalRegistration();
        world.onFinalApplied(applied(pending.receipt()));
        pending.complete(new PlayerRepository.FinalSaveRegistration.Durable(pending.receipt(), false));

        assertThat(hooks.leaving).hasSize(1);
        assertThat(hooks.persisted).isEmpty();
        assertThat(player.persistedState().getCurrency().getBalances(0)).isZero();
    }

    @Test
    void 停服对每个在场实例仅补发一次既有持久前缀() {
        repository.setAutoFinalRegistration(false);
        ScenePlayer player = enter();
        player.wallet().add(0, 33);
        assertThat(world.shutdown()).isEqualTo(1);
        assertThat(world.shutdown()).isZero();
        assertThat(repository.takeFinalRegistration().save().state().getCurrency().getBalances(0)).isEqualTo(33);
        assertThat(hooks.leaving).hasSize(1);
        assertThat(hooks.leaving.getFirst().getCurrency().getBalances(0)).isZero();
        assertThat(hooks.persisted).isEmpty();
    }

    @Test
    void 在线写被围栏拒绝的无写回移除不调用离场确认() {
        ScenePlayer player = enter();
        player.wallet().add(0, 9);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        repository.takeProgress().complete(PlayerRepository.ProgressResult.FENCED);

        assertThat(world.playerById(7)).isNull();
        assertThat(hooks.leaving).isEmpty();
        assertThat(hooks.persisted).isEmpty();
        assertThat(repository.saves()).isEmpty();
    }

    @Test
    void 只有在线成功回执先更新持久基准再通知收件箱() {
        ScenePlayer player = enter();
        player.wallet().add(0, 18);
        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        repository.takeProgress().complete(PlayerRepository.ProgressResult.FAILED);
        assertThat(hooks.persisted).isEmpty();
        assertThat(player.persistedState()).isNull();

        assertThat(world.requestSave(player)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        repository.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(hooks.persisted).hasSize(1);
        assertThat(hooks.persisted.getFirst().getCurrency().getBalances(0)).isEqualTo(18);
        assertThat(player.persistedState()).isEqualTo(hooks.persisted.getFirst());
    }

    private static PlayerStore.FinalSaveReceipt applied(PlayerRepository.FinalSaveReceipt receipt) {
        return new PlayerStore.FinalSaveReceipt(PlayerStore.FinalSaveStatus.APPLIED, receipt.playerId(),
                receipt.ownerEpoch(), receipt.saveSeq(), receipt.sourceInstance(), receipt.payloadDigest());
    }

    private static final class RecordingInbox implements InboxHooks {
        int entered;
        final List<PlayerState> leaving = new ArrayList<>();
        final List<PlayerState> persisted = new ArrayList<>();

        @Override public void onEntered(SceneWorld world, ScenePlayer player, ScenePlayer carried) { entered++; }
        @Override public void onPersisted(SceneWorld world, ScenePlayer player) { persisted.add(player.persistedState()); }
        @Override public void onLeaving(SceneWorld world, ScenePlayer player) { leaving.add(player.persistedState()); }
        @Override public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) { }
    }
}
