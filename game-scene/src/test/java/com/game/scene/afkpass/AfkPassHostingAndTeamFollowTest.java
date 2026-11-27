package com.game.scene.afkpass;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.common.RunMode;
import com.game.proto.BuyAfkPassRequest;
import com.game.proto.BuyAfkPassResponse;
import com.game.proto.GetAfkPassRequest;
import com.game.proto.GetAfkPassResponse;
import com.game.proto.GmSetAfkPassRequest;
import com.game.proto.GmSetAfkPassResponse;
import com.game.proto.Location;
import com.game.proto.MessageContent;
import com.game.proto.MoveSyncC2S;
import com.game.proto.TeamFollowC2S;
import com.game.proto.TeamFollowStateS2C;
import com.game.proto.TeamFollowStopReason;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollowFormation;
import com.game.scene.team.TeamFollowTrail;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.Scene;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** 挂机月卡（430–432 + 投影自愈）、断线保留与离线托管、服务器驱动组队跟随综合单测。 */
class AfkPassHostingAndTeamFollowTest {

    private static final int CURRENCY_JADE = 1;

    @Test
    void 挂机月卡_查买幂等重放_报价变更拒_叠加超限拒_GM设清与Redis投影失败自愈() throws Exception {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);

        AtomicBoolean failRedis = new AtomicBoolean(true);
        AtomicInteger setAttempts = new AtomicInteger();
        AtomicInteger delAttempts = new AtomicInteger();
        AfkPassService.ProjectionSink sink = new AfkPassService.ProjectionSink() {
            @Override public CompletionStage<Boolean> set(long playerId, long expiresAtSec, Duration ttl) {
                setAttempts.incrementAndGet();
                return CompletableFuture.completedFuture(!failRedis.get());
            }
            @Override public CompletionStage<Boolean> delete(long playerId) {
                delAttempts.incrementAndGet();
                return CompletableFuture.completedFuture(!failRedis.get());
            }
        };

        AfkPassTables tables = AfkPassTables.of(new AfkPassTables.Rule(30, CURRENCY_JADE, 300, 60, 72));
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        CurrencyService currency = new CurrencyService(AssetAudit.log(), GainAnomalyDetector.off(), metrics, clock);
        AfkPassService afkPass = new AfkPassService(tables, currency, clock, sink, Runnable::run);

        RecordingSink clientSink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = newWorld(clock, clientSink, repo, metrics).withAfkPassService(afkPass);
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new AfkPassFeature(afkPass)));

        Scene scene = world.createScene(1);
        enterPlayer(world, repo, 1001L, 1, 101, scene.sceneId(), 1L);
        ScenePlayer p = world.playerById(1001L);
        currency.add(p, CURRENCY_JADE, 1000, AssetAudit.Reason.GM_GRANT, 0);

        // 进场加载时发起过一次 DEL 投影（到期 0）但失败未确认
        assertThat(delAttempts.get()).isEqualTo(1);
        assertThat(p.hasUnconfirmedAfkPassProjection()).isTrue();

        int getId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "GetAfkPass");
        int buyId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "BuyAfkPass");
        int gmId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "GmSetAfkPass");

        clientSink.clear();
        forward(handler, 1, 101, 1001L, getId, 1, GetAfkPassRequest.getDefaultInstance().toByteString());
        GetAfkPassResponse info = GetAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101));
        assertThat(info.getErrorMessage().getId()).isZero();
        assertThat(info.getInfo().getPrice()).isEqualTo(300L);
        assertThat(info.getInfo().getBalance()).isEqualTo(1000L);

        // 2. 431 报价不符 -> 32000，不扣钱
        forward(handler, 1, 101, 1001L, buyId, 2, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(555L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(299L)
                .build().toByteString());
        BuyAfkPassResponse badPrice = BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101));
        assertThat(badPrice.getErrorMessage().getId()).isEqualTo(AfkPassService.PRICE_CHANGED);
        assertThat(p.wallet().balance(CURRENCY_JADE)).isEqualTo(1000L);

        // 3. 431 正常开通 30 天 -> 扣 300 灵石、触发关键存盘（存盘提交前不发 Redis 投影，提交后发 SET 且因 Redis 失败保持未确认）
        forward(handler, 1, 101, 1001L, buyId, 3, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(555L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        BuyAfkPassResponse bought = BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101));
        assertThat(bought.getErrorMessage().getId()).isZero();
        assertThat(bought.getReplayed()).isFalse();
        assertThat(bought.getInfo().getExpiresAt()).isEqualTo(clock.epochMillis() / 1000L + 30L * 86_400L);
        assertThat(p.wallet().balance(CURRENCY_JADE)).isEqualTo(700L);
        assertThat(setAttempts.get()).isZero();
        repo.takeProgress().complete(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        assertThat(setAttempts.get()).isEqualTo(1);
        assertThat(p.hasUnconfirmedAfkPassProjection()).isTrue();

        // 4. 相同 client_request_id=555 重放 -> replayed=true，不重复扣费
        forward(handler, 1, 101, 1001L, buyId, 4, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(555L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        BuyAfkPassResponse replayed = BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101));
        assertThat(replayed.getErrorMessage().getId()).isZero();
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(p.wallet().balance(CURRENCY_JADE)).isEqualTo(700L);

        // 5. Redis 恢复 -> 心跳刷新成功自愈确认；续费 30 天到 60 天，再买超 60 天上限 -> 32002
        failRedis.set(false);
        afkPass.refreshProjection(p);
        assertThat(p.hasUnconfirmedAfkPassProjection()).isFalse();

        forward(handler, 1, 101, 1001L, buyId, 5, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(556L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        assertThat(BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101)).getErrorMessage().getId()).isZero();
        repo.takeProgress().complete(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);

        forward(handler, 1, 101, 1001L, buyId, 6, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(557L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        assertThat(BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101)).getErrorMessage().getId())
                .isEqualTo(AfkPassService.STACK_LIMIT);

        // 6. 432 GM 设为 0（清除）-> 存盘提交后触发 DEL 投影并确认
        int delsBefore = delAttempts.get();
        forward(handler, 1, 101, 1001L, gmId, 7,
                GmSetAfkPassRequest.newBuilder().setRemainingSeconds(0).build().toByteString());
        GmSetAfkPassResponse cleared = GmSetAfkPassResponse.parseFrom(lastBody(clientSink, 1, 101));
        assertThat(cleared.getErrorMessage().getId()).isZero();
        assertThat(cleared.getInfo().getExpiresAt()).isZero();
        repo.takeProgress().complete(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        assertThat(delAttempts.get()).isGreaterThan(delsBefore);
    }

    @Test
    void 购买与GM修改挂机月卡实时推送属性面板变更_重放不重复推送() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.isDirectory(java.nio.file.Path.of("../config-data/tables"))
                ? java.nio.file.Path.of("../config-data/tables") : java.nio.file.Path.of("config-data/tables");
        com.game.table.ConfigTables config = com.game.table.ConfigTables.load(dir);

        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        CurrencyService currency = new CurrencyService(AssetAudit.log(), GainAnomalyDetector.off(), metrics, clock);
        com.game.scene.attribute.AttributeService attributes = new com.game.scene.attribute.AttributeService(
                com.game.scene.attribute.AttributeTables.from(config), clock, currency);
        AfkPassTables tables = AfkPassTables.of(new AfkPassTables.Rule(30, CURRENCY_JADE, 300, 60, 72));
        AfkPassService afkPass = new AfkPassService(tables, currency, clock);

        RecordingSink clientSink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        AtomicLong ids = new AtomicLong(500);
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, clientSink, repo, ids::incrementAndGet,
                clock, metrics, attributes::initializeOnLoad, PlayerSnapshots.NONE).withAfkPassService(afkPass);
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV,
                List.of(new AfkPassFeature(afkPass, attributes, Contracts.REGISTRY)));

        Scene scene = world.createScene(1);
        enterPlayer(world, repo, 4001L, 1, 401, scene.sceneId(), 1L);
        ScenePlayer p = world.playerById(4001L);
        currency.add(p, CURRENCY_JADE, 1000, AssetAudit.Reason.GM_GRANT, 0);
        int baseMaxSchemes = attributes.buildPanel(p).getMaxSchemes();
        assertThat(baseMaxSchemes).isLessThan(4);

        int buyId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "BuyAfkPass");
        int gmId = Contracts.REGISTRY.requireId(AfkPassFeature.SERVICE, "GmSetAfkPass");
        int notifyPanelId = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");

        // 首次购买：推送属性面板变更（maxSchemes 提升至 4）
        clientSink.clear();
        forward(handler, 1, 401, 4001L, buyId, 1, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(901L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        List<MessageContent> afterBuy = clientSink.to(1, 401);
        List<MessageContent> buyPanels = afterBuy.stream().filter(m -> m.getMessageId() == notifyPanelId).toList();
        assertThat(buyPanels).hasSize(1);
        assertThat(com.game.proto.AttributePanelChangedS2C.parseFrom(buyPanels.getFirst().getSerializedMessage())
                .getPanel().getMaxSchemes()).isEqualTo(4);
        assertThat(BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 401)).getReplayed()).isFalse();

        // 幂等重放：不重复推送属性面板
        clientSink.clear();
        forward(handler, 1, 401, 4001L, buyId, 2, BuyAfkPassRequest.newBuilder()
                .setClientRequestId(901L).setExpectedCurrencyType(CURRENCY_JADE).setExpectedPrice(300L)
                .build().toByteString());
        assertThat(clientSink.to(1, 401).stream().filter(m -> m.getMessageId() == notifyPanelId).toList()).isEmpty();
        assertThat(BuyAfkPassResponse.parseFrom(lastBody(clientSink, 1, 401)).getReplayed()).isTrue();

        // GM 清零：推送属性面板变更（maxSchemes 回到基线）
        clientSink.clear();
        forward(handler, 1, 401, 4001L, gmId, 3,
                GmSetAfkPassRequest.newBuilder().setRemainingSeconds(0).build().toByteString());
        List<MessageContent> gmPanels = clientSink.to(1, 401).stream()
                .filter(m -> m.getMessageId() == notifyPanelId).toList();
        assertThat(gmPanels).hasSize(1);
        assertThat(com.game.proto.AttributePanelChangedS2C.parseFrom(gmPanels.getFirst().getSerializedMessage())
                .getPanel().getMaxSchemes()).isEqualTo(baseMaxSchemes);
    }

    @Test
    void 断线保留25秒退出_有挂机月卡自动离线托管并支持无损接回与到期退场() {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = newWorld(clock, sink, repo, new SceneMetrics(new SimpleMeterRegistry()))
                .withConnectionLostHold(true);
        Scene scene = world.createScene(1);

        // 玩家 A 无月卡：断线进入 25s 保留，20s 仍在场，25s 巡检退场
        enterPlayer(world, repo, 2001L, 1, 201, scene.sceneId(), 1L);
        world.onPlayerLeave(1, PlayerLeave.newBuilder().setSessionId(201).setPlayerId(2001L).setVoluntary(false).build());
        assertThat(world.playerById(2001L)).isNotNull();
        assertThat(world.playerById(2001L).session()).isNull();

        clock.advanceMillis(20_000L);
        assertThat(world.sweepConnectionLostHolds()).isZero();
        assertThat(world.playerById(2001L)).isNotNull();

        clock.advanceMillis(5_000L);
        assertThat(world.sweepConnectionLostHolds()).isEqualTo(1);
        assertThat(world.playerById(2001L)).isNull();

        // 玩家 B 有月卡（120 秒）：断线自动进入离线托管并挂起跟随意图；40 秒后仍在场（越过 25s 普通窗口），同代重连无损接回
        enterPlayer(world, repo, 2002L, 1, 202, scene.sceneId(), 1L);
        world.playerById(2002L).setAfkPassExpiresAt(clock.epochMillis() / 1000L + 120L);
        world.onPlayerLeave(1, PlayerLeave.newBuilder().setSessionId(202).setPlayerId(2002L).setVoluntary(false).build());
        assertThat(world.playerById(2002L).connectionLostHold().hostingStarted()).isTrue();
        assertThat(world.teamFollowCoordinator().wantsFollow(2002L)).isTrue();

        clock.advanceMillis(40_000L);
        assertThat(world.sweepConnectionLostHolds()).isZero();
        assertThat(world.playerById(2002L)).isNotNull();

        // 重连接回：新会话 299 接管原实例，免读库
        sink.clear();
        world.onPlayerEnter(1, PlayerEnter.newBuilder().setSessionId(299).setPlayerId(2002L)
                .setSceneId(scene.sceneId()).setOwnerEpoch(1L).build());
        assertThat(world.playerById(2002L).connectionLostHold()).isNull();
        assertThat(world.playerById(2002L).session().sessionId()).isEqualTo(299);
        assertThat(sink.results()).extracting(RecordingSink.EnterResult::sessionId).contains(299);

        // 再次断线后推进时钟越过到期 + 15s 宽限 -> 自动退场
        world.onPlayerLeave(1, PlayerLeave.newBuilder().setSessionId(299).setPlayerId(2002L).setVoluntary(false).build());
        clock.advanceMillis(100_000L);
        assertThat(world.sweepConnectionLostHolds()).isEqualTo(1);
        assertThat(world.playerById(2002L)).isNull();
    }

    @Test
    void 服务器驱动组队跟随_沿队长轨迹前进_折返回收不截断随从_暂离立即回收空队形() throws Exception {
        // 1. 纯折线折返回收与保护里程验证
        TeamFollowTrail trail = new TeamFollowTrail(new Vec3(0, 0, 0));
        trail.feed(new Vec3(2, 0, 0), -100, TeamFollowTrail.WalkableCheck.ALWAYS);
        trail.feed(new Vec3(4, 0, 0), -100, TeamFollowTrail.WalkableCheck.ALWAYS);
        trail.feed(new Vec3(4, 2, 0), -100, TeamFollowTrail.WalkableCheck.ALWAYS);
        trail.feed(new Vec3(2, 2, 0), -100, TeamFollowTrail.WalkableCheck.ALWAYS);
        double odoBefore = trail.headOdo();
        // 队长折返到 (0.5, 0, 0)，靠近起点 (0, 0, 0) -> 回收环路缩短轨迹
        trail.feed(new Vec3(0.5, 0, 0), -100, TeamFollowTrail.WalkableCheck.ALWAYS);
        assertThat(trail.headOdo()).isLessThan(odoBefore);

        // 2. 场景内队长 + 随从实测：TeamFollowC2S{follow=true} -> 队形驱动、丢弃随从客户端移动上报、follow=false 立即回收空队形
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = newWorld(clock, sink, repo, new SceneMetrics(new SimpleMeterRegistry()));
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS, RunMode.DEV, List.of());

        Scene scene = world.createScene(1);
        enterPlayer(world, repo, 3001L, 1, 301, scene.sceneId(), 1L);
        enterPlayer(world, repo, 3002L, 1, 302, scene.sceneId(), 1L);
        ScenePlayer leader = world.playerById(3001L);
        ScenePlayer follower = world.playerById(3002L);
        world.applyTeamMembership(leader, 88L, 1L);
        world.applyTeamMembership(follower, 88L, 1L);

        // 随从发送 TeamFollowC2S{follow=true} 并接入队长队形
        forward(handler, 1, 302, 3002L, Contracts.IDS.teamFollow(), 0,
                TeamFollowC2S.newBuilder().setFollow(true).build().toByteString());
        assertThat(world.teamFollowCoordinator().tryAttach(world, follower, leader, true)).isTrue();
        assertThat(world.teamFollowCoordinator().isDriven(follower)).isTrue();
        assertThat(world.teamFollowCoordinator().activeFormationCount()).isEqualTo(1);
        TeamFollowStateS2C attachedMsg = TeamFollowStateS2C.parseFrom(lastBody(sink, 1, 302));
        assertThat(attachedMsg.getDriven()).isTrue();
        assertThat(attachedMsg.getLeaderEntity()).isEqualTo(leader.entity());
        assertThat(attachedMsg.getSlot()).isEqualTo(1);

        // 驱动期间随从发客户端移动上报 -> 被静默丢弃（位置不动）
        Vec3 posBeforeClientMove = follower.position();
        forward(handler, 1, 302, 3002L, Contracts.IDS.moveSync(), 0, MoveSyncC2S.newBuilder()
                .setInputSeq(1)
                .setLocation(Location.newBuilder().setX(posBeforeClientMove.x() + 1.0)
                        .setY(posBeforeClientMove.y()).setZ(posBeforeClientMove.z()).build())
                .build().toByteString());
        assertThat(follower.position()).isEqualTo(posBeforeClientMove);

        // 队长向前走 5 米，每 tick 推进队形 -> 随从自动沿队长轨迹跟进
        Vec3 leaderStart = leader.position();
        for (int i = 1; i <= 20; i++) {
            world.relocateTeamFollower(leader, new Vec3(leaderStart.x() + i * 0.25, leaderStart.y(), leaderStart.z()));
            clock.advanceMillis(50L);
            world.step();
        }
        assertThat(follower.position().x()).isGreaterThan(leaderStart.x() + 2.5);
        assertThat(leader.position().x() - follower.position().x())
                .isCloseTo(TeamFollowFormation.DEFAULT_SPACING, Offset.offset(0.5));

        // 随从发 TeamFollowC2S{follow=false} -> 停止驱动、推 LEFT_FOLLOW、且队长空队形立即回收为 0
        forward(handler, 1, 302, 3002L, Contracts.IDS.teamFollow(), 0,
                TeamFollowC2S.newBuilder().setFollow(false).build().toByteString());
        assertThat(world.teamFollowCoordinator().isDriven(follower)).isFalse();
        assertThat(world.teamFollowCoordinator().activeFormationCount()).isZero();
        TeamFollowStateS2C detachedMsg = TeamFollowStateS2C.parseFrom(lastBody(sink, 1, 302));
        assertThat(detachedMsg.getDriven()).isFalse();
        assertThat(detachedMsg.getReason()).isEqualTo(TeamFollowStopReason.TEAM_FOLLOW_STOP_REASON_LEFT_FOLLOW);
    }

    @Test
    void 未成年人防沉迷到点强制立即退场且离线托管不越过截止时刻() {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_800_000_000_000L);
        long nowSec = clock.epochMillis() / 1000L;
        long deadlineMs = clock.epochMillis() + 120_000L; // 2 分钟后 21:00 截止

        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = newWorld(clock, sink, repo, new SceneMetrics(new SimpleMeterRegistry()))
                .withConnectionLostHold(true);
        Scene scene = world.createScene(1);

        // 1. 持有 30 天有效挂机月卡的未成年人，收到 gate 到点踢线的 immediate_exit=true -> 跳过断线保留与离线托管直接退场
        repo.put(new PlayerData(5001L, 1L, 1, 1, "00_default", 10, 1, new Vec3(10, 10, 0)));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(501).setPlayerId(5001L).setSceneId(scene.sceneId()).setOwnerEpoch(1L)
                .setMinorPlayDeadlineMs(deadlineMs).build());
        repo.completeAll();
        ScenePlayer p1 = world.playerById(5001L);
        p1.setAfkPassExpiresAt(nowSec + 30L * 86_400L);

        world.onPlayerLeave(1L, PlayerLeave.newBuilder()
                .setSessionId(501).setPlayerId(5001L).setVoluntary(false).setImmediateExit(true).build());
        assertThat(world.playerById(5001L)).as("immediate_exit 必须直接移出场景").isNull();

        // 2. 持有 30 天月卡的未成年人在截止前普通断线 -> 先进入离线托管，到 21:00 截止时刻巡检立刻退出
        repo.put(new PlayerData(5002L, 2L, 1, 1, "00_default", 10, 1, new Vec3(12, 10, 0)));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(502).setPlayerId(5002L).setSceneId(scene.sceneId()).setOwnerEpoch(2L)
                .setMinorPlayDeadlineMs(deadlineMs).build());
        repo.completeAll();
        ScenePlayer p2 = world.playerById(5002L);
        p2.setAfkPassExpiresAt(nowSec + 30L * 86_400L);

        world.onPlayerLeave(1L, PlayerLeave.newBuilder()
                .setSessionId(502).setPlayerId(5002L).setVoluntary(false).build());
        assertThat(world.playerById(5002L)).isNotNull();
        assertThat(p2.connectionLostHold()).isNotNull();
        assertThat(p2.connectionLostHold().hostingStarted()).isTrue();

        // 截止前 60 秒仍在托管
        clock.advanceMillis(60_000L);
        assertThat(world.sweepConnectionLostHolds()).isZero();
        assertThat(world.playerById(5002L)).isNotNull();

        // 越过 21:00 截止时刻 -> 巡检立刻退出，绝不继续离线托管
        clock.advanceMillis(60_000L);
        assertThat(world.sweepConnectionLostHolds()).isEqualTo(1);
        assertThat(world.playerById(5002L)).isNull();
    }

    private static SceneWorld newWorld(ManualClock clock, RecordingSink sink, FakePlayerRepository repo,
                                       SceneMetrics metrics) {
        AtomicLong ids = new AtomicLong(100);
        return new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, ids::incrementAndGet,
                clock, metrics, p -> { }, PlayerSnapshots.NONE);
    }

    private static void enterPlayer(SceneWorld world, FakePlayerRepository repo, long playerId, long linkId,
                                    int sessionId, long sceneId, long epoch) {
        repo.put(new PlayerData(playerId, epoch, 1, 1, "00_default", 10, 1, new Vec3(10, 10, 0)));
        world.onPlayerEnter(linkId, PlayerEnter.newBuilder()
                .setSessionId(sessionId).setPlayerId(playerId).setSceneId(sceneId).setOwnerEpoch(epoch).build());
        repo.completeAll();
    }

    private static void forward(ClientRequestHandler handler, long linkId, int sessionId, long playerId,
                                int msgId, long reqId, ByteString body) {
        handler.onClientForward(linkId, ClientForward.newBuilder()
                .setSessionId(sessionId).setPlayerId(playerId).setMessageId(msgId)
                .setRequestId(reqId).setBody(body).build());
    }

    private static ByteString lastBody(RecordingSink sink, long linkId, int sessionId) {
        List<MessageContent> list = sink.to(linkId, sessionId);
        return list.getLast().getSerializedMessage();
    }
}
