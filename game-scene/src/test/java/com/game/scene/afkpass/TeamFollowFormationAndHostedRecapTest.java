package com.game.scene.afkpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.game.api.proto.ClientForward;
import com.game.api.proto.ExitGameReason;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.player.store.state.HostedRecapState;
import com.game.proto.AckHostedRecapRequest;
import com.game.proto.AckHostedRecapResponse;
import com.game.proto.AfkPassInfo;
import com.game.proto.SetAfkPassSettingsRequest;
import com.game.proto.SetAfkPassSettingsResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.battle.BattleFreezeTestAccess;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollowFormation;
import com.game.scene.team.TeamFollowService;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 组队跟随三阵型 + 离线托管收获简报（522/523）+ 离队自动回主城单测。 */
class TeamFollowFormationAndHostedRecapTest {

    @Test
    void threeFormationsAndWalkableWallFallback() {
        TeamFollowFormation echelon = new TeamFollowFormation(new Vec3(0, 0, 0), 3.0, TeamFollowFormation.Style.ECHELON);
        echelon.add(new TeamFollowFormation.Member(201L, 1, 0.0));
        echelon.add(new TeamFollowFormation.Member(202L, 2, 0.0));
        echelon.add(new TeamFollowFormation.Member(203L, 3, 0.0));
        for (int i = 1; i <= 12; i++) {
            echelon.tick(new Vec3(i, 0, 0), 0.2, (from, to) -> true);
        }
        Vec3 p0 = echelon.slotPosition(0, (from, to) -> true);
        Vec3 p1 = echelon.slotPosition(1, (from, to) -> true);
        Vec3 p2 = echelon.slotPosition(2, (from, to) -> true);
        assertThat(p0.x()).isCloseTo(9.0, within(0.1));
        assertThat(p1.x()).isCloseTo(9.0, within(0.1));
        assertThat(p0.y()).isCloseTo(2.0, within(0.1));
        assertThat(p1.y()).isCloseTo(-2.0, within(0.1));
        assertThat(p2.x()).isCloseTo(6.0, within(0.1));
        assertThat(p2.y()).isCloseTo(3.6, within(0.1));

        Vec3 halfFallback = echelon.slotPosition(0, (from, to) -> Math.abs(to.y()) <= 1.2);
        assertThat(halfFallback.y()).isCloseTo(1.0, within(0.1));
        Vec3 centerFallback = echelon.slotPosition(0, (from, to) -> Math.abs(to.y()) <= 0.1);
        assertThat(centerFallback.y()).isCloseTo(0.0, within(0.01));

        echelon.setStyle(TeamFollowFormation.Style.WUXING);
        assertThat(echelon.slotLag(0)).isCloseTo(2.55, within(1e-6));
        assertThat(echelon.slotLag(2)).isCloseTo(5.1, within(1e-6));
        assertThat(echelon.targetLateral(0)).isCloseTo(-2.4, within(1e-6));
        assertThat(echelon.targetLateral(2)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void hostedRecapLifecycleSettingsAckAndAutoReturnCity() throws Exception {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        long nowSec = clock.epochMillis() / 1000L;

        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        CurrencyService currency = new CurrencyService(AssetAudit.log(), GainAnomalyDetector.off(), metrics, clock);
        AfkPassTables tables = AfkPassTables.of(new AfkPassTables.Rule(30, 1, 300, 60, 72));
        AfkPassService afkPass = new AfkPassService(tables, currency, clock);
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(100)::incrementAndGet, clock, metrics, p -> {}, PlayerSnapshots.NONE)
                .withConnectionLostHold(true)
                .withAfkPassService(afkPass);
        Scene city = world.createScene(1);
        Scene wild = world.createScene(2);
        world.withDefaultMainCityConfigId(1);
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new AfkPassFeature(afkPass)));

        repo.put(new PlayerData(5001L, 1L, 1, 1, "00_default", 10, 2, new Vec3(0, 0, 0)));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setPlayerId(5001L).setSessionId(11).setSceneId(wild.sceneId()).setOwnerEpoch(1L).build());
        repo.completeAll();
        ScenePlayer player = world.playerById(5001L);
        assertThat(player).isNotNull();
        player.setAfkPassExpiresAt(nowSec + 86_400L);

        int setSettingsId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "SetAfkPassSettings");
        int ackRecapId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "AckHostedRecap");
        assertThat(setSettingsId).isEqualTo(522);
        assertThat(ackRecapId).isEqualTo(523);

        // 522 开启离队自动回主城偏好
        sink.clear();
        forward(handler, 1L, 11, 5001L, setSettingsId, 1L, SetAfkPassSettingsRequest.newBuilder()
                .setAutoReturnCityOnTeamEnd(true).build().toByteString());
        SetAfkPassSettingsResponse setResp = SetAfkPassSettingsResponse.parseFrom(
                sink.to(1L, 11).getLast().getSerializedMessage());
        assertThat(setResp.getErrorMessage().getId()).isZero();
        assertThat(setResp.getInfo().getAutoReturnCityOnTeamEnd()).isTrue();
        while (repo.pendingProgress() > 0) {
            repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        }

        // 入队后意外断线进入离线托管 -> 开启收获简报
        world.applyTeamMembership(player, 88L, 1L, false);
        world.onPlayerLeave(1L, PlayerLeave.newBuilder()
                .setPlayerId(5001L).setSessionId(11).setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        assertThat(player.connectionLostHold()).isNotNull();
        assertThat(player.connectionLostHold().hostingStarted()).isTrue();
        long startedAt = player.hostedRecap().getStartedAt();
        assertThat(startedAt).isEqualTo(nowSec);
        while (repo.pendingProgress() > 0) {
            repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        }

        // 离线托管期间结算 2 场战斗（1 胜 1 负）
        clock.advanceMillis(30_000L);
        AfkPassService.recordHostedBattleSettlement(player, true, 1200L, 500L, 3, nowSec + 30L);
        AfkPassService.recordHostedBattleSettlement(player, false, 300L, 100L, 1, nowSec + 40L);
        AfkPassService.onHostingStarted(world, player, nowSec + 50L);
        assertThat(player.hostedRecap().getBattleCount()).isEqualTo(2);

        // C.2 离线托管离队 -> 战斗中延迟，离战解冻后自动回主城安全区
        BattleFreezeTestAccess.freeze(player);
        world.applyTeamMembership(player, 0L, 2L, false);
        assertThat(player.scene()).isSameAs(wild);
        assertThat(player.pendingAutoReturnCity()).isTrue();

        BattleFreezeTestAccess.clear(player);
        TeamFollowService follow = new TeamFollowService(
                pid -> CompletableFuture.completedFuture(new TeamMembership(0L, 2L, false, TeamInfo.getDefaultInstance())),
                Runnable::run, metrics);
        follow.onBattleFreezeCleared(world, player);
        assertThat(player.scene()).isSameAs(city);
        assertThat(player.pendingAutoReturnCity()).isFalse();

        // 玩家重连接回 -> 简报封账并置 pending_ack=true
        clock.advanceMillis(60_000L);
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setPlayerId(5001L).setSessionId(12).setSceneId(city.sceneId()).setOwnerEpoch(1L).build());
        AfkPassInfo info = afkPass.getInfo(player).getInfo();
        assertThat(info.hasHostedRecap()).isTrue();
        assertThat(info.getHostedRecap().getBattleCount()).isEqualTo(2);
        assertThat(info.getHostedRecap().getWinCount()).isEqualTo(1);
        assertThat(info.getHostedRecap().getExpGained()).isEqualTo(1500L);
        assertThat(info.getHostedRecap().getSilverGained()).isEqualTo(600L);
        assertThat(info.getHostedRecap().getItemCount()).isEqualTo(4);
        assertThat(info.getHostedRecap().getPendingAck()).isTrue();
        assertThat(info.getHostedRecap().getEndedAt()).isEqualTo(nowSec + 90L);
        while (repo.pendingProgress() > 0) {
            repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        }

        // 523 错 started_at 不误清；正确 started_at 清空简报且保留偏好开关
        sink.clear();
        forward(handler, 1L, 12, 5001L, ackRecapId, 2L, AckHostedRecapRequest.newBuilder()
                .setStartedAt(startedAt + 1L).build().toByteString());
        assertThat(player.hostedRecap().getPendingAck()).isTrue();

        sink.clear();
        forward(handler, 1L, 12, 5001L, ackRecapId, 3L, AckHostedRecapRequest.newBuilder()
                .setStartedAt(startedAt).build().toByteString());
        AckHostedRecapResponse ackResp = AckHostedRecapResponse.parseFrom(
                sink.to(1L, 12).getLast().getSerializedMessage());
        assertThat(ackResp.getErrorMessage().getId()).isZero();
        assertThat(ackResp.getInfo().hasHostedRecap()).isFalse();
        assertThat(ackResp.getInfo().getAutoReturnCityOnTeamEnd()).isTrue();

        HostedRecapState saved = player.hostedRecap();
        assertThat(saved.getAutoReturnCityOnTeamEnd()).isTrue();
        assertThat(saved.getPendingAck()).isFalse();
    }

    private static void forward(ClientRequestHandler handler, long linkId, int sessionId, long playerId,
                                int msgId, long reqId, ByteString body) {
        handler.onClientForward(linkId, ClientForward.newBuilder()
                .setSessionId(sessionId).setPlayerId(playerId).setMessageId(msgId)
                .setRequestId(reqId).setBody(body).build());
    }
}
