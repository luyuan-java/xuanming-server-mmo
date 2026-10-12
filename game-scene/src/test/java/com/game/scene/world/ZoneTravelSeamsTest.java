package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientForward;
import com.game.discovery.team.TeamMembership;
import com.game.proto.MessageContent;
import com.game.proto.TravelToZoneRequest;
import com.game.proto.TravelToZoneResponse;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.team.TravelTeamChecks;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeTeamChecks;
import com.game.scene.testing.FakeTravelTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.ManualExecutor;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.TeamChecks.TeamCheck;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.4 先行件在 game-scene 里冻结的接缝：装配记录的校验、世界的新构造器层、位置写口的缺省实现、在队检查（先行件时是占位，
 * W1 起是真实现 {@link TravelTeamChecks}）、消息号，以及 226 注册之后<b>没装配跨 zone 传送</b>时的安全出口（同步 3027，不挂槽、不交出）。
 * 226 的状态机本身见 {@code ZoneTravelRequestTest} / {@code ZoneTravelHandOffTest}；在队检查的逐格用例见 {@code team/TravelTeamChecksTest}。
 */
class ZoneTravelSeamsTest {

    private static final Duration S2 = Duration.ofSeconds(2);
    private static final Duration S4 = Duration.ofSeconds(4);
    private static final Duration S30 = Duration.ofSeconds(30);

    // ------------------------------------------------------------------ ZoneTravel

    @Test
    void 未装配的ZoneTravel_不启用_时限取三个配置键的缺省() {
        ZoneTravel disabled = ZoneTravel.DISABLED;

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.localZoneId()).isZero();
        assertThat(disabled.targets()).isNull();
        assertThat(disabled.teamChecks()).isNull();
        assertThat(disabled.teamCheckTimeout()).isEqualTo(S2);
        assertThat(disabled.resolveTimeout()).isEqualTo(S4);
        assertThat(disabled.tombstoneTtl()).isEqualTo(S30);
    }

    @Test
    void 启用的ZoneTravel_必须有正的本zone号与在队检查_三个时限必须为正() {
        FakeTravelTargets targets = new FakeTravelTargets();
        FakeTeamChecks teamChecks = new FakeTeamChecks();

        ZoneTravel enabled = new ZoneTravel(2, targets, teamChecks, Duration.ofMillis(1500), S4, S30);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.localZoneId()).isEqualTo(2);
        assertThat(enabled.targets()).isSameAs(targets);
        assertThat(enabled.teamChecks()).isSameAs(teamChecks);
        assertThat(enabled.teamCheckTimeout()).isEqualTo(Duration.ofMillis(1500));

        assertThatThrownBy(() -> new ZoneTravel(0, targets, teamChecks, S2, S4, S30)).as("启用却没有本 zone 号")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneTravel(-1, targets, teamChecks, S2, S4, S30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneTravel(2, targets, null, S2, S4, S30)).as("启用却没有在队检查")
                .isInstanceOf(NullPointerException.class);
        for (Duration bad : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> new ZoneTravel(2, targets, teamChecks, bad, S4, S30))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ZoneTravel(2, targets, teamChecks, S2, bad, S30))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ZoneTravel(2, targets, teamChecks, S2, S4, bad))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ZoneTravel(0, null, null, bad, S4, S30)).as("不启用时时限照样要为正")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ZoneTravel(2, targets, teamChecks, null, S4, S30)).isInstanceOf(NullPointerException.class);
        assertThat(new ZoneTravel(0, null, null, S2, S4, S30)).as("不启用时本 zone 号可以是 0").isEqualTo(ZoneTravel.DISABLED);
    }

    // ------------------------------------------------------------------ SceneWorld 的新构造器层

    @Test
    void 世界的旧构造器_跨zone传送缺省不启用_新构造器带进来的装配原样可取_null拒绝() {
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld legacy = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED,
                SceneInstances.DISABLED, BattleHooks.NONE);
        assertThat(legacy.zoneTravel()).isSameAs(ZoneTravel.DISABLED);

        ZoneTravel travel = new ZoneTravel(3, new FakeTravelTargets(), new FakeTeamChecks(), S2, S4, S30);
        SceneWorld wired = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED,
                SceneInstances.DISABLED, BattleHooks.NONE, travel);
        assertThat(wired.zoneTravel()).isSameAs(travel);

        assertThatThrownBy(() -> new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED,
                SceneInstances.DISABLED, BattleHooks.NONE, null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ 位置写口的缺省实现

    /** 没有覆盖 {@code awaitingPlacement} 的写口（{@link PlayerLocations#NONE} 与只关心别的写口的替身；生产的 Redis 实现 W1 起已覆盖它）：不写，当场回「没写上」，恰好一次。 */
    @Test
    void 待落点写口的缺省实现_在调用栈内回false恰好一次() {
        PlayerLocations bare = new PlayerLocations() {
            @Override
            public void entered(ScenePlayer player) {
            }

            @Override
            public void disconnected(ScenePlayer player) {
            }

            @Override
            public void loggedOut(ScenePlayer player) {
            }

            @Override
            public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
            }

            @Override
            public void refresh(Collection<ScenePlayer> players) {
            }
        };
        ScenePlayer removed = WorldTestAccess.player(1001);
        long seqBefore = removed.locationSeq();

        for (PlayerLocations locations : List.of(bare, PlayerLocations.NONE)) {
            List<Boolean> results = new ArrayList<>();
            locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(300), results::add);
            assertThat(results).containsExactly(false);
        }
        assertThat(removed.locationSeq()).as("缺省实现不取位置序号（没有写）").isEqualTo(seqBefore);
    }

    // ------------------------------------------------------------------ 在队检查（W1 起是真实现 TravelTeamChecks）

    @Test
    void 在队检查_读一次成员关系_经逻辑执行器回结果恰好一次_不在调用栈内() {
        AtomicInteger reads = new AtomicInteger();
        ManualExecutor logic = new ManualExecutor();
        TravelTeamChecks checks = new TravelTeamChecks(playerId -> {
            reads.incrementAndGet();
            return CompletableFuture.completedFuture(TeamMembership.KEY_MISSING);
        }, logic);
        List<TeamCheck> results = new ArrayList<>();

        checks.check(1001, S2, results::add);

        assertThat(results).as("回调不在 check 的调用栈内").isEmpty();
        assertThat(logic.pending()).isEqualTo(1);
        logic.runAll();
        // 批次 5.4 W1 接上真实现（逐格用例在 team/TravelTeamChecksTest）：索引键缺失 = 无队
        assertThat(results).containsExactly(TeamCheck.NOT_IN_TEAM);
        assertThat(logic.pending()).as("恰好一次").isZero();
        assertThat(reads.get()).isEqualTo(1);
    }

    /**
     * 名字是先行件（占位实现）时起的，没有改。换上真实现之后，前半段<b>测不到</b>「逻辑线程已停时结果丢弃」：读口给的 future 永不完成，
     * 结果要等 {@code orTimeout} 到点才出来，这里紧接着断言的 {@code pending() == 0} 恒真（批次 5.4 W1 评审意见 2，暂缓，登记在
     * zone-travel-spec 实现记录「遗留」）。这一行为的有效覆盖在 {@code team/TravelTeamChecksTest}「逻辑线程已停_投递被拒_…」；
     * 本用例真正钉住的是后半段的参数校验（上限不为正、读口 / 执行器为 null）。
     */
    @Test
    void 在队检查的占位_逻辑线程已停时结果丢弃不抛_上限不为正是编程错误() {
        ManualExecutor logic = new ManualExecutor();
        TravelTeamChecks checks = new TravelTeamChecks(playerId -> new CompletableFuture<>(), logic);
        logic.rejectNewTasks(true);

        checks.check(1001, S2, result -> {
            throw new AssertionError("不该执行");
        });
        assertThat(logic.pending()).isZero();

        assertThatThrownBy(() -> checks.check(1001, Duration.ZERO, result -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TravelTeamChecks(null, logic)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TravelTeamChecks(playerId -> new CompletableFuture<>(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ 226：消息号在、处理器不在

    @Test
    void 消息号表带上226_按服务与方法名解析() {
        assertThat(Contracts.IDS.travelToZone())
                .isEqualTo(Contracts.REGISTRY.requireId("SceneSceneClientPlayer", "TravelToZone"));
        assertThat(Contracts.REGISTRY.byId(Contracts.IDS.travelToZone()).orElseThrow().requestPrototype())
                .isInstanceOf(TravelToZoneRequest.class);
        assertThat(Contracts.REGISTRY.byId(Contracts.IDS.travelToZone()).orElseThrow().responsePrototype())
                .isInstanceOf(TravelToZoneResponse.class);
    }


    /**
     * 226 已注册（批次 5.4）：冻结闸与战斗闸都放行（它自己判在途与战斗）。没装配跨 zone 传送（{@link ZoneTravel#DISABLED}，没有可达的
     * scene-manager）时同步回应答内 3027（基线 player_lifecycle.cpp:2870-2873）、回显请求号，不冻结、不挂槽、不碰存储，
     * 只计 {@code travel.requests{unavailable}}；其余新指标都不动。
     */
    @Test
    void 未装配跨zone传送_226同步回应答内3027_不挂槽不冻结不交出() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, new ManualClock(), new SceneMetrics(meters), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED,
                SceneInstances.DISABLED, BattleHooks.NONE, ZoneTravel.DISABLED);
        ClientRequestHandler handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
        Scene scene = world.createScene(1);
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(1, enterFrame(11, 1001, scene.sceneId(), 1));
        repo.completeAll();
        sink.clear();
        int travelToZone = Contracts.IDS.travelToZone();

        assertThat(handler.freezePolicy(travelToZone)).isEqualTo(FreezePolicy.ALLOW);
        assertThat(handler.battlePolicy(travelToZone)).isEqualTo(BattlePolicy.GATED);

        handler.onClientForward(1, ClientForward.newBuilder().setSessionId(11).setPlayerId(1001).setMessageId(travelToZone)
                .setBody(TravelToZoneRequest.newBuilder().setTargetZoneId(2).build().toByteString()).setRequestId(77).build());

        List<MessageContent> replies = sink.to(1, 11);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).getMessageId()).isEqualTo(travelToZone);
        assertThat(replies.get(0).getId()).isEqualTo(77L);
        assertThat(replies.get(0).hasErrorMessage()).as("拒绝码在应答体里，不是信封错误").isFalse();
        assertThat(TravelToZoneResponse.parseFrom(replies.get(0).getSerializedMessage()).getErrorMessage().getId())
                .isEqualTo(3027);
        ScenePlayer player = world.playerBySession(new SessionKey(1, 11));
        assertThat(player.switchPhase()).as("没有在途槽").isEqualTo(SwitchPhase.NONE);
        assertThat(player.frozen()).isFalse();
        assertThat(repo.pendingHandOffs()).isZero();
        assertThat(sink.redirects()).isEmpty();
        assertThat(meters.get("game.scene.travel.requests").tag("result", "unavailable").counter().count()).isEqualTo(1);
        assertThat(meters.find("game.scene.travel.requests").counters()).extracting(Counter::count).as("每条 226 恰好计一次")
                .containsOnly(0.0, 1.0).filteredOn(count -> count == 1.0).hasSize(1);
        for (String meter : List.of("game.scene.travel.resolves", "game.scene.travel.placements")) {
            assertThat(meters.find(meter).counters()).as(meter + " 已预注册").isNotEmpty()
                    .allSatisfy(counter -> assertThat(counter.count()).isZero());
        }
        assertThat(meters.find("game.scene.transfers").tag("reason", "travel").counters()).hasSize(9)
                .extracting(Counter::count).containsOnly(0.0);
        assertThat(meters.get("game.scene.travel.frames.pending").gauge().value()).isZero();
    }
}
