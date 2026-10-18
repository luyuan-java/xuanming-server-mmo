package com.game.scene.world;

import static org.assertj.core.api.Assertions.*;

import com.game.api.proto.PlayerEnter;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 专门控制回调顺序的接口回归；真实 SQL 到账由 asset-arrival profile 另验。 */
class SameEpochProgressCarryTest {
    private final FakePlayerRepository repository = new FakePlayerRepository();
    private final SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(),
            repository, new AtomicLong(9000)::incrementAndGet, new ManualClock(), SceneMetrics.noop());
    private final Scene scene = world.createScene(1);
    private final CurrencyService currency = new CurrencyService(new RecordingAssetAudit());

    private ScenePlayer enter(long epoch, int session) {
        repository.put(new PlayerData(7, epoch, 1, 1, "", 1, 1, new Vec3(180, 200, 0),
                PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(0)).build()));
        world.onPlayerEnter(1, PlayerEnter.newBuilder().setPlayerId(7).setOwnerEpoch(epoch)
                .setSessionId(session).setSceneId(scene.sceneId()).build());
        repository.completeAll();
        return world.playerById(7);
    }

    @Test
    void 同epoch重建继承旧写槽_成功后保存重建期间新增修改() {
        var before = enter(1, 11);
        currency.add(before, 0, 10, AssetAudit.Reason.MAIL_ATTACHMENT);
        assertThat(world.requestSave(before)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        var oldWrite = repository.takeProgress();
        var current = enter(1, 12);
        currency.add(current, 0, 20, AssetAudit.Reason.MAIL_ATTACHMENT);
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        assertThat(repository.pendingProgress()).isZero();
        oldWrite.complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(current.persistedState().getCurrency().getBalances(0)).isEqualTo(10);
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        var next = repository.takeProgress();
        assertThat(next.save().state().getCurrency().getBalances(0)).isEqualTo(30);
        next.complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.UNCHANGED);
    }

    @Test
    void 同epoch旧回调失败作废当前实例基准_新修改完整重写() {
        var before = enter(1, 11);
        currency.add(before, 0, 10, AssetAudit.Reason.MAIL_ATTACHMENT);
        world.requestSave(before);
        var oldWrite = repository.takeProgress();
        var current = enter(1, 12);
        currency.add(current, 0, 20, AssetAudit.Reason.MAIL_ATTACHMENT);
        oldWrite.complete(PlayerRepository.ProgressResult.FAILED);
        assertThat(current.persistedState()).isNull();
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        assertThat(repository.takeProgress().save().state().getCurrency().getBalances(0)).isEqualTo(30);
    }

    @Test
    void 不同epoch旧回调不能清新写槽或覆盖新基准() {
        var before = enter(1, 11);
        currency.add(before, 0, 10, AssetAudit.Reason.MAIL_ATTACHMENT);
        world.requestSave(before);
        var oldWrite = repository.takeProgress();
        var current = enter(2, 12);
        currency.add(current, 0, 20, AssetAudit.Reason.MAIL_ATTACHMENT);
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.WRITTEN);
        var newWrite = repository.takeProgress();
        oldWrite.complete(PlayerRepository.ProgressResult.FAILED);
        assertThat(world.playerById(7)).isSameAs(current);
        assertThat(current.persistedState()).isNotNull();
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.IN_FLIGHT);
        newWrite.complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(world.requestSave(current)).isEqualTo(SceneWorld.SaveRequest.UNCHANGED);
    }
}
