package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ExitGameReason;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.discovery.proto.TeamInfo;
import com.game.discovery.team.TeamMembership;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollowService;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 批次 4T-2B 场景侧对账覆盖：
 * 1) ExitGameReason 断线保留 + 关键存盘 + 登录身份判据（同登录重复进场不摘保留、同号更高代次接回）+ 异步月卡探针/解析；
 * 2) 跨节点组队跟随（同 zone 静默交接不推错、跨 zone 拒绝、离线托管走托管落点、普通 25s 保留不换图、队长离开等待 90s 唤醒）；
 * 3) 离线托管随排空/疏散搬迁 + 源端 5s/20s 有界重试 + 目标端 5s/20s/60s 权威核对重试 + 退出中托管落点不取消退出；
 * 4) 托管在场心跳（20s/60s）与归位信息 + OwnerTakeover keep_held=true 不打扰存活实体。
 */
class Batch4T2BSceneParityTest {

    @Test
    void 离场原因_关键存盘_登录身份判据与异步月卡探针解析() {
        assertThat(ConnectionLostHold.parseAfkPass(null)).isZero();
        assertThat(ConnectionLostHold.parseAfkPass("")).isZero();
        assertThat(ConnectionLostHold.parseAfkPass("-5")).isZero();
        assertThat(ConnectionLostHold.parseAfkPass("abc")).isZero();
        assertThat(ConnectionLostHold.parseAfkPass("1800001000")).isEqualTo(1_800_001_000L);

        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        long nowSec = clock.epochMillis() / 1000L;
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        AtomicReference<CompletableFuture<OptionalLong>> probeReply = new AtomicReference<>();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(100)::incrementAndGet, clock, new SceneMetrics(new SimpleMeterRegistry()),
                p -> {}, PlayerSnapshots.NONE)
                .withConnectionLostHold(true)
                .withAfkPassProbe(playerId -> {
                    CompletableFuture<OptionalLong> f = new CompletableFuture<>();
                    probeReply.set(f);
                    return f;
                });
        Scene scene = world.createScene(1);

        // 1. LOGOUT / LEASE_EXPIRED 跳过保留直接退出
        enter(world, repo, 1001L, 1L, 101, scene.sceneId(), 1L, 10L);
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(101).setPlayerId(1001L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_LEASE_EXPIRED).build());
        assertThat(world.playerById(1001L)).isNull();

        // 2. DISCONNECTED 进入保留 + 触发关键存盘 + 异步探针回传有效月卡 -> 启动离线托管
        enter(world, repo, 1002L, 1L, 102, scene.sceneId(), 2L, 50L);
        world.playerById(1002L).setLevel(12); // 脏状态以验证进入保留触发存盘
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(102).setPlayerId(1002L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        assertThat(world.playerById(1002L)).isNotNull();
        assertThat(repo.pendingProgress()).as("进入断线保留必须发起一次关键进度存盘").isEqualTo(1);
        repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        probeReply.get().complete(OptionalLong.of(nowSec + 600L));
        assertThat(world.playerById(1002L).connectionLostHold().hostingStarted()).isTrue();

        // 3. 同一 (session=102, login_gen=50) 重复进场：不摘保留、不重发入场通知
        sink.clear();
        world.onPlayerEnter(1L, PlayerEnter.newBuilder().setSessionId(102).setPlayerId(1002L)
                .setSceneId(scene.sceneId()).setOwnerEpoch(2L).setLoginGeneration(50L).build());
        assertThat(world.playerById(1002L).connectionLostHold()).as("同登录重复进场不得摘除保留").isNotNull();
        assertThat(sink.results()).isEmpty();

        // 4. 相同 session=102 但 login_gen=51 更大（如 gate 重启后会话号从头复用）-> 视为新登录，无损接回
        world.onPlayerEnter(1L, PlayerEnter.newBuilder().setSessionId(102).setPlayerId(1002L)
                .setSceneId(scene.sceneId()).setOwnerEpoch(2L).setLoginGeneration(51L).build());
        assertThat(world.playerById(1002L).connectionLostHold()).isNull();
        assertThat(world.playerById(1002L).loginGeneration()).isEqualTo(51L);
        assertThat(sink.results()).hasSize(1);
    }

    @Test
    void 跨节点跟随_离线托管走托管落点_普通25s保留不换图_跨zone不跟_队长离场等待唤醒() {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        long nowSec = clock.epochMillis() / 1000L;
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        FakeSwitchTargets targets = new FakeSwitchTargets();

        AtomicReference<Optional<TeamFollowService.LeaderLocation>> leaderLoc =
                new AtomicReference<>(Optional.of(new TeamFollowService.LeaderLocation(1, 20, 8888L, 1)));
        TeamInfo team = TeamInfo.newBuilder().setTeamId(77L).setLeaderId(2001L)
                .addMembers(2001L).addMembers(2002L).addMembers(2003L).addMembers(2004L).build();
        TeamFollowService followSvc = new TeamFollowService(
                pid -> CompletableFuture.completedFuture(new TeamMembership(77L, 1L, false, team)),
                Runnable::run, new SceneMetrics(new SimpleMeterRegistry()))
                .withCrossNodeFollow(1, leaderId -> CompletableFuture.completedFuture(leaderLoc.get()));

        List<HostedPlacementController.PlacementRequest> hostedDispatches = new ArrayList<>();
        HostedPlacementController hostedCtrl = new HostedPlacementController(
                req -> {
                    hostedDispatches.add(req);
                    return CompletableFuture.completedFuture(HostedPlacementController.DispatchOutcome.ACCEPTED);
                },
                null, null, HostedPlacementController.HomingSink.NONE);

        CrossNodeSwitch crossNode = new CrossNodeSwitch(10, targets, p -> {}, Duration.ofSeconds(3), Duration.ofSeconds(30),
                PlayerChannels.NONE, EvacuationPolicy.DEFAULT);
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(200)::incrementAndGet, clock, new SceneMetrics(new SimpleMeterRegistry()),
                p -> {}, PlayerSnapshots.NONE, PlayerLocations.NONE, followSvc, crossNode)
                .withConnectionLostHold(true)
                .withHostedPlacement(hostedCtrl, 1, "scene-10");
        Scene scene = world.createScene(1);

        // 1. 在线队员 2002：队长在同 zone 另一节点 20 -> 发起跨节点交接；若交接未提交原地解冻，静默不推客户端错误码
        enter(world, repo, 2002L, 1L, 202, scene.sceneId(), 1L, 100L);
        assertThat(repo.pendingHandOffs()).isEqualTo(1);
        sink.clear();
        repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.LeaseTooShort());
        assertThat(sink.to(1L, 202)).as("服务端发起的跨节点跟随失败不得推客户端错提示").isEmpty();

        // 2. 跨 zone (zone=2 != 1) 队长：绝不发起跨节点跟随
        leaderLoc.set(Optional.of(new TeamFollowService.LeaderLocation(2, 20, 8888L, 1)));
        followSvc.onRefresh(world, world.playerById(2002L), true);
        assertThat(repo.pendingHandOffs()).isZero();

        // 3. 回到同 zone：离线普通 25s 保留队员 2003（无月卡）绝不换图；离线已确认挂机托管队员 2004 走托管落点搬迁
        leaderLoc.set(Optional.of(new TeamFollowService.LeaderLocation(1, 20, 8888L, 1)));
        enter(world, repo, 2003L, 1L, 203, scene.sceneId(), 1L, 101L);
        repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.LeaseTooShort()); // 清理刚进场时的在线跟随
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(203).setPlayerId(2003L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        while (repo.pendingProgress() > 0) repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        followSvc.onRefresh(world, world.playerById(2003L), true);
        assertThat(repo.pendingHandOffs()).as("普通 25s 断线保留不得跨场景跟随").isZero();

        enter(world, repo, 2004L, 1L, 204, scene.sceneId(), 1L, 102L);
        repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.LeaseTooShort());
        world.playerById(2004L).setAfkPassExpiresAt(nowSec + 600L);
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(204).setPlayerId(2004L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        while (repo.pendingProgress() > 0) repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(repo.pendingHandOffs()).isEqualTo(1);
        repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.HandedOff(2L));
        assertThat(hostedDispatches).singleElement().satisfies(req -> {
            assertThat(req.playerId()).isEqualTo(2004L);
            assertThat(req.targetNodeId()).isEqualTo(20);
            assertThat(req.targetSceneId()).isEqualTo(8888L);
            assertThat(req.ownerEpoch()).isEqualTo(2L);
            assertThat(req.loginGeneration()).isEqualTo(102L);
        });
        assertThat(sink.transfers()).isEmpty();
        assertThat(sink.kicks()).isEmpty();

        // 4. 托管落点在目标节点重建实体：无客户端推送、自动重建断线保留与月卡托管
        repo.put(new PlayerData(2004L, 2L, 1, 1, "00_default", 10, 1, new Vec3(10, 10, 0),
                null, "托管队员", 0L, nowSec + 600L, 0L));
        sink.clear();
        world.onPlayerEnter(0L, PlayerEnter.newBuilder()
                .setSessionId(204).setPlayerId(2004L).setSceneId(scene.sceneId()).setOwnerEpoch(2L)
                .setLoginGeneration(102L).setHostedPlacement(true).build());
        repo.completeAll();
        ScenePlayer reentered = world.playerById(2004L);
        assertThat(reentered).isNotNull();
        assertThat(reentered.session()).isNull();
        assertThat(reentered.connectionLostHold()).isNotNull();
        assertThat(reentered.connectionLostHold().hostingStarted()).isTrue();
        assertThat(sink.results()).isEmpty();

        // 5. 队长离开节点解散队形 -> 原随从进入 90s 等待队列并在队长去留明确时立即被唤醒重查
        leaderLoc.set(Optional.empty());
        enter(world, repo, 2001L, 1L, 201, scene.sceneId(), 1L, 105L);
        ScenePlayer leader = world.playerById(2001L);
        ScenePlayer f2002 = world.playerById(2002L);
        world.applyTeamMembership(leader, 77L, 2L, true);
        world.applyTeamMembership(f2002, 77L, 2L, false);
        world.teamFollowCoordinator().setFollowIntent(world, f2002, true);
        assertThat(world.teamFollowCoordinator().tryAttach(world, f2002, leader, true)).isTrue();
        leaderLoc.set(Optional.of(new TeamFollowService.LeaderLocation(1, 20, 8888L, 1)));
        // 队长离开节点 -> 立即唤醒原随从 2002 重跑跟随检查并发起跨节点交接
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(201).setPlayerId(2001L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_LOGOUT).build());
        assertThat(repo.pendingHandOffs()).as("队长离场后原随从应被立即唤醒并跨节点跟随").isEqualTo(1);
    }

    @Test
    void 排空搬迁托管角色_源端与目标端有界重试_心跳归位与keepHeld不打扰活实体() {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        long nowSec = clock.epochMillis() / 1000L;
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        FakeSwitchTargets targets = new FakeSwitchTargets();

        AtomicInteger sourceDispatchCount = new AtomicInteger();
        AtomicBoolean sourceFail = new AtomicBoolean(true);
        AtomicReference<Optional<HostedPlacementController.AuthoritativePlacement>> authPlacement = new AtomicReference<>();
        AtomicInteger targetCreateAttempts = new AtomicInteger();
        AtomicBoolean targetCreateSucceeds = new AtomicBoolean(false);
        List<String> heartbeats = new ArrayList<>();
        List<String> homings = new ArrayList<>();

        HostedPlacementController ctrl = new HostedPlacementController(
                req -> {
                    sourceDispatchCount.incrementAndGet();
                    return CompletableFuture.completedFuture(sourceFail.get()
                            ? HostedPlacementController.DispatchOutcome.RETRYABLE_PRE_WRITE
                            : HostedPlacementController.DispatchOutcome.ACCEPTED);
                },
                pid -> authPlacement.get(),
                req -> {
                    targetCreateAttempts.incrementAndGet();
                    return targetCreateSucceeds.get();
                },
                new HostedPlacementController.HomingSink() {
                    @Override public void renewPresence(long playerId, String holderNode, long ttlSeconds) {
                        heartbeats.add(playerId + "@" + holderNode + ":" + ttlSeconds);
                    }
                    @Override public void writeHoming(long playerId, int zoneId, long ownerRevision,
                                                      int lostSessionId, long loginGeneration, long expiresAtSec, long ttlSeconds) {
                        homings.add(playerId + ":z" + zoneId + ":s" + lostSessionId + ":g" + loginGeneration);
                    }
                    @Override public void clearPresence(long playerId) { }
                });

        CrossNodeSwitch crossNode = new CrossNodeSwitch(10, targets, p -> {}, Duration.ofSeconds(3), Duration.ofSeconds(30),
                PlayerChannels.NONE, EvacuationPolicy.DEFAULT);
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(300)::incrementAndGet, clock, new SceneMetrics(new SimpleMeterRegistry()),
                p -> {}, PlayerSnapshots.NONE, PlayerLocations.NONE, com.game.scene.team.TeamFollow.NONE, crossNode)
                .withConnectionLostHold(true)
                .withHostedPlacement(ctrl, 3, "scene-10");
        Scene scene = world.createScene(1);

        // 1. 托管角色 3001 进入断线托管 -> 立即写心跳与归位元数据；keep_held=true 探测绝不打扰存活实体
        enter(world, repo, 3001L, 1L, 301, scene.sceneId(), 1L, 500L);
        world.playerById(3001L).setAfkPassExpiresAt(nowSec + 600L);
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(301).setPlayerId(3001L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);
        assertThat(heartbeats).contains("3001@scene-10:60");
        assertThat(homings).contains("3001:z3:s301:g500");

        world.onTakeoverRequested(3001L, 1L, true);
        assertThat(world.playerById(3001L)).as("keep_held=true 命中存活实体绝不能移除或踢线").isNotNull();
        assertThat(sink.kicks()).isEmpty();

        // 20s 周期续心跳
        clock.advanceMillis(20_000L);
        world.sweepConnectionLostHolds();
        assertThat(heartbeats.size()).isGreaterThanOrEqualTo(2);

        // 2. 普通 25s 未确认保留角色 3002 + 托管角色 3001 在节点计划排空时分流：
        // 3002 直接存盘退出；3001 走托管落点搬走、绝不踢线，失败时按 5s / 20s 源端有界重试
        enter(world, repo, 3002L, 1L, 302, scene.sceneId(), 1L, 501L);
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(302).setPlayerId(3002L)
                .setReason(ExitGameReason.EXIT_GAME_REASON_DISCONNECTED).build());
        while (repo.pendingProgress() > 0) repo.takeProgress().complete(PlayerRepository.ProgressResult.SAVED);

        world.beginNodeEvacuation(NodeEvacuation.Cause.SHUTDOWN, Duration.ofSeconds(20));
        assertThat(world.playerById(3002L)).as("普通断线保留随排空直接存盘退出").isNull();
        assertThat(targets.pendingCount()).isEqualTo(1);
        targets.take().chosen(20, 9001L, 1);
        repo.takeHandOff().complete(new PlayerRepository.HandOffOutcome.HandedOff(2L));
        assertThat(sourceDispatchCount.get()).isEqualTo(1);
        assertThat(ctrl.pendingSourceRetries()).isEqualTo(1);
        assertThat(sink.kicks()).isEmpty();

        // 5s 第 1 次重试仍失败，20s 第 2 次重试成功
        clock.advanceMillis(5_000L);
        ctrl.tick(clock.nanoTime(), "scene-10");
        assertThat(sourceDispatchCount.get()).isEqualTo(2);
        sourceFail.set(false);
        clock.advanceMillis(20_000L);
        ctrl.tick(clock.nanoTime(), "scene-10");
        assertThat(sourceDispatchCount.get()).isEqualTo(3);
        assertThat(ctrl.pendingSourceRetries()).isZero();

        // 3. 目标端有界重试（5s / 20s / 60s，每次先核对权威落点，任一项变化即停）
        HostedPlacementController.PlacementRequest targetReq = new HostedPlacementController.PlacementRequest(
                4001L, 10, scene.sceneId(), 1, 5L, 700L, 401, 1L, 0L, "acc");
        authPlacement.set(Optional.of(new HostedPlacementController.AuthoritativePlacement(
                10, scene.sceneId(), 401, 700L, 5L)));
        ctrl.scheduleTargetRetry(10, targetReq, clock.nanoTime());
        clock.advanceMillis(5_000L);
        ctrl.tick(clock.nanoTime(), "scene-10");
        assertThat(targetCreateAttempts.get()).isEqualTo(1);
        // 权威落点换了归属代次 -> 20s 到达时立即结清停止、不再尝试建人
        authPlacement.set(Optional.of(new HostedPlacementController.AuthoritativePlacement(
                10, scene.sceneId(), 401, 700L, 6L)));
        clock.advanceMillis(20_000L);
        ctrl.tick(clock.nanoTime(), "scene-10");
        assertThat(targetCreateAttempts.get()).isEqualTo(1);
        assertThat(ctrl.pendingTargetRetries()).isZero();
    }

    private static void enter(SceneWorld world, FakePlayerRepository repo, long playerId, long linkId,
                              int sessionId, long sceneId, long epoch, long loginGen) {
        repo.put(new PlayerData(playerId, epoch, 1, 1, "00_default", 10, 1, new Vec3(10, 10, 0)));
        world.onPlayerEnter(linkId, PlayerEnter.newBuilder()
                .setSessionId(sessionId).setPlayerId(playerId).setSceneId(sceneId)
                .setOwnerEpoch(epoch).setLoginGeneration(loginGen).build());
        repo.completeAll();
    }
}
