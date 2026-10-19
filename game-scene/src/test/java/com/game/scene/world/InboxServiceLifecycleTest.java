package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.PlayerStore;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.game.proto.player_inbox.*;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.currency.CurrencyService;
import com.game.scene.inbox.InboxConsumers;
import com.game.scene.inbox.InboxService;
import com.game.scene.inbox.InboxTransport;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 真实消费状态机与 World/存盘回调组合；Fake 存储不冒实际 SQL，真实 SQL 另由 AssetArrivalTiDbTest 覆盖。 */
class InboxServiceLifecycleTest {
    private static final int DOMAIN = PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION_VALUE;
    private static final PlayerInboxMessageType BLOCK = PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_CURRENCY_GAIN;
    private final ManualClock clock = new ManualClock();
    private final FakePlayerRepository repository = new FakePlayerRepository();
    private final Transport transport = new Transport();
    private final AtomicInteger executions = new AtomicInteger();
    private final InboxConsumers production = InboxConsumers.gmSanction(new CurrencyService(AssetAudit.log(),
            GainAnomalyDetector.off(), SceneMetrics.noop(), clock));
    private final InboxConsumers counted = InboxConsumers.of(Map.of(BLOCK, (player, payload) -> {
        executions.incrementAndGet();
        return production.executor(BLOCK.getNumber(), DOMAIN).execute(player, payload);
    }));
    private final InboxService inbox = new InboxService(counted, transport, clock, new InboxService.Settings(true, 30), () -> 9, () -> 500);
    private final SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repository,
            new AtomicLong(9000)::incrementAndGet, clock, SceneMetrics.noop(), InboxService::checkLedgerOnLoad,
            PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED, SceneInstances.DISABLED,
            BattleHooks.NONE, ZoneTravel.DISABLED, inbox);
    private final Scene scene = world.createScene(1);

    InboxServiceLifecycleTest() { inbox.attach(world); }

    private ScenePlayer enter(long epoch, int session, PlayerState state) {
        repository.put(new PlayerData(7, epoch, 1, 1, "", 1, 1, new Vec3(180, 200, 0), state));
        world.onPlayerEnter(1, SceneWorldTest.enterFrame(session, 7, scene.sceneId(), epoch));
        repository.completeAll();
        return world.playerById(7);
    }

    private ScenePlayer enter() {
        return enter(1, 11, PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(0)).build());
    }

    private Fetch fetch() { inbox.tick(); return transport.fetches.getLast(); }

    @Test
    void 实际执行后必须等SAVED_失败和纯时间流逝都不能确认() {
        ScenePlayer player = enter();
        fetch().reply(item());
        assertThat(player.wallet().isBlocked(0)).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(transport.acks).isEmpty();
        repository.takeProgress().complete(PlayerRepository.ProgressResult.FAILED);
        clock.advanceMillis(5001);
        inbox.tick();
        assertThat(transport.acks).isEmpty();
        repository.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(transport.acks).hasSize(1);
        assertThat(transport.acks.getFirst().request().getOwnerEpoch()).isEqualTo(1);
        assertThat(player.persistedState().getCurrency().getBlockedTypesList()).containsExactly(0);
    }

    @Test
    void 最终登记PENDING及迟到APPLIED不冒SQL资产回执_新主按实际已落库账本回放不重做() {
        repository.setAutoFinalRegistration(false);
        ScenePlayer player = enter();
        fetch().reply(item());
        var pendingProgress = repository.takeProgress();
        world.onPlayerLeave(1, SceneWorldTest.leave(11, 7));
        var finalSave = repository.takeFinalRegistration();
        finalSave.complete(new PlayerRepository.FinalSaveRegistration.Durable(finalSave.receipt(), false));
        var receipt = finalSave.receipt();
        world.onFinalApplied(new PlayerStore.FinalSaveReceipt(PlayerStore.FinalSaveStatus.APPLIED, receipt.playerId(),
                receipt.ownerEpoch(), receipt.saveSeq(), receipt.sourceInstance(), receipt.payloadDigest()));
        pendingProgress.complete(PlayerRepository.ProgressResult.FENCED);
        inbox.tick();
        assertThat(transport.acks).isEmpty();
        assertThat(player.persistedState().getCurrency().getBlockedTypesList()).isEmpty();
        ScenePlayer next = enter(2, 22, finalSave.save().state()); // 模拟 TX2 已提交之后的加载；真实提交另测。
        fetch().reply(item());
        assertThat(executions).hasValue(1);
        assertThat(next.wallet().isBlocked(0)).isTrue();
        assertThat(transport.acks).hasSize(1);
        assertThat(transport.acks.getFirst().request().getOwnerEpoch()).isEqualTo(2);
    }

    @Test
    void 同epoch重连转交在途拉取_重复旧回复不再次执行() {
        ScenePlayer old = enter();
        Fetch pending = fetch();
        ScenePlayer current = enter(1, 22, old.persistedState());
        assertThat(current).isNotSameAs(old);
        pending.reply(item());
        pending.reply(item());
        assertThat(executions).hasValue(1);
        assertThat(current.wallet().isBlocked(0)).isTrue();
        repository.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(transport.acks).hasSize(1);
    }

    @Test
    void 新epoch拒绝旧拉取回复_只处理当前关联号() {
        enter();
        Fetch old = fetch();
        enter(2, 22, PlayerState.getDefaultInstance());
        old.reply(item());
        assertThat(executions).hasValue(0);
        assertThat(transport.acks).isEmpty();
        fetch().reply(item());
        assertThat(executions).hasValue(1);
        repository.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(transport.acks.getFirst().request().getOwnerEpoch()).isEqualTo(2);
    }

    @Test
    void SQL围栏拒写移除旧主_迟到拉取和存盘结果不确认() {
        enter();
        Fetch old = fetch();
        old.reply(item());
        repository.takeProgress().complete(PlayerRepository.ProgressResult.FENCED);
        old.reply(item());
        inbox.tick();
        assertThat(world.playerById(7)).isNull();
        assertThat(executions).hasValue(1);
        assertThat(transport.acks).isEmpty();
    }

    @Test
    void 拉取运输失败不生效也不确认_退避后可恢复() {
        ScenePlayer player = enter();
        Fetch first = fetch();
        first.callback().accept(null, new IllegalStateException("测试运输不可达"));
        assertThat(player.wallet().isBlocked(0)).isFalse();
        assertThat(transport.acks).isEmpty();
        clock.advanceMillis(2001);
        fetch().reply(item());
        assertThat(executions).hasValue(1);
        assertThat(transport.acks).isEmpty();
    }

    @Test
    void 停服保留未落库结局为PENDING_不因离场回调确认() {
        repository.setAutoFinalRegistration(false);
        enter();
        fetch().reply(item());
        assertThat(world.shutdown()).isEqualTo(1);
        assertThat(world.shutdown()).isZero();
        inbox.tick();
        assertThat(repository.takeFinalRegistration().save().state().getInboxLedger().getCursorsCount()).isEqualTo(1);
        assertThat(transport.acks).isEmpty();
    }

    @Test
    void 显式关闭保留真实服务但不消费_注册表坏也不宣告消费能力() {
        var disabled = new InboxService(production, transport, clock, new InboxService.Settings(false, 30), () -> 9, () -> 500);
        disabled.attach(world);
        disabled.onEntered(world, enter(), null);
        disabled.tick();
        assertThat(disabled.consumerEnabled()).isFalse();
        assertThat(transport.fetches).isEmpty();
        var broken = new InboxService(InboxConsumers.of(Map.of(PlayerInboxMessageType.PLAYER_INBOX_MSG_UNSPECIFIED,
                (player, payload) -> new InboxConsumers.Applied())), transport, clock,
                new InboxService.Settings(true, 30), () -> 9, () -> 500);
        assertThat(broken.consumerEnabled()).isFalse();
    }

    private static PlayerInboxItem item() {
        return PlayerInboxItem.newBuilder().setSeq(1).setRequestId(101).setMsgType(BLOCK)
                .setPayload(GmCurrencyGainPayload.newBuilder().setCurrencyType(0).build().toByteString()).build();
    }

    private record Fetch(FetchPlayerInboxRequest request, BiConsumer<FetchPlayerInboxResponse, Throwable> callback) {
        void reply(PlayerInboxItem item) {
            callback.accept(FetchPlayerInboxResponse.newBuilder().setPlayerId(request.getPlayerId()).setDomain(request.getDomain())
                    .setCorrelationId(request.getCorrelationId()).setFence(PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_MATCH)
                    .addItems(item).build(), null);
        }
    }
    private record Ack(AckPlayerInboxRequest request, BiConsumer<AckPlayerInboxResponse, Throwable> callback) { }
    private static final class Transport implements InboxTransport {
        final List<Fetch> fetches = new ArrayList<>();
        final List<Ack> acks = new ArrayList<>();
        @Override public void fetch(FetchPlayerInboxRequest r, BiConsumer<FetchPlayerInboxResponse, Throwable> done) { fetches.add(new Fetch(r, done)); }
        @Override public void ack(AckPlayerInboxRequest r, BiConsumer<AckPlayerInboxResponse, Throwable> done) { acks.add(new Ack(r, done)); }
    }
}
