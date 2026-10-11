package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.ZoneRedirect;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.MessageContent;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.proto.TravelToZoneRequest;
import com.game.proto.TravelToZoneResponse;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.FakeTeamChecks;
import com.game.scene.testing.FakeTravelTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingLocations;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨 zone 传送（批次 5.4，zone-travel-spec §5.5）单测的共用装配：开了跨 zone 传送（本 zone {@link #LOCAL_ZONE}，假在队检查、假选目标）与
 * 跨节点换图（假选目标，用来造 63 的在途），假仓库、记录全部出站 / 位置 / 快照；两张主世界地图各一个场景。替身缺省都挂起，测试逐步完成
 * （测试线程就是逻辑线程）。
 */
final class TravelFixture {

    static final long LINK = 1;
    static final int LOCAL_ZONE = 1;
    static final int TARGET_ZONE = 2;
    static final int LOCAL_NODE = 3;
    static final Duration TEAM_CHECK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(4);
    static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    static final int TIP = 23;
    /** 选目标解析出来的目标地图（目标 zone 的 2 号主世界）。 */
    static final int TARGET_MAP = 2;
    /** 票据过期时刻（Unix 秒）：时钟起点 + 300 s。 */
    static final long DEADLINE = ManualClock.EPOCH_MILLIS_START / 1000 + 300;
    /** 一份完整的选目标结果（目标 zone 的 1 号 gate、票据与签名是不透明字节）。 */
    static final ZoneRedirect REDIRECT = ZoneRedirect.newBuilder().setTargetZoneId(TARGET_ZONE).setGateNodeId(1)
            .setGateHost("10.0.0.2").setGatePort(11010).setTokenPayload(ByteString.copyFrom(new byte[] {8, 1, 16, 2, 24, 3}))
            .setTokenSignature(ByteString.copyFromUtf8("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
            .setTokenDeadline(DEADLINE).build();

    /** 一份快照：玩家、epoch、原因。 */
    record Snapshot(long playerId, long epoch, PlayerSnapshots.Cause cause) {
    }

    final RecordingSink sink = new RecordingSink();
    final FakePlayerRepository repo = new FakePlayerRepository();
    final FakeSwitchTargets switchTargets = new FakeSwitchTargets();
    final FakeTravelTargets targets = new FakeTravelTargets();
    final FakeTeamChecks teamChecks = new FakeTeamChecks();
    final RecordingLocations locations = new RecordingLocations();
    final ManualClock clock = new ManualClock();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<Snapshot> snapshots = new ArrayList<>();
    final SceneWorld world;
    final ClientRequestHandler handler;
    final Scene scene1;
    final Scene scene2;
    private long requestIds = 100;

    TravelFixture() {
        this(new FakeSceneTables(), true);
    }

    /** @param enabled false = 跨 zone 传送没装配（{@link ZoneTravel#DISABLED}） */
    TravelFixture(SceneTables tables, boolean enabled) {
        ZoneTravel travel = enabled
                ? new ZoneTravel(LOCAL_ZONE, targets, teamChecks, TEAM_CHECK_TIMEOUT, RESOLVE_TIMEOUT, TOMBSTONE_TTL)
                : ZoneTravel.DISABLED;
        world = new SceneWorld(tables, Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet, clock,
                new SceneMetrics(meters), PlayerInitializer.NONE,
                (save, cause) -> snapshots.add(new Snapshot(save.playerId(), save.ownerEpoch(), cause)), locations,
                TeamFollow.NONE, new CrossNodeSwitch(LOCAL_NODE, switchTargets, owned -> { }, RESOLVE_TIMEOUT, TOMBSTONE_TTL),
                SceneInstances.DISABLED, BattleHooks.NONE, travel);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
        scene1 = world.createScene(1);
        scene2 = world.createScene(2);
    }

    /** 新号进场（epoch 由调用方给），完成加载后清掉出站、位置与快照记录。 */
    ScenePlayer enter(int sessionId, long playerId, Scene scene, long epoch) {
        repo.putNewPlayer(playerId, epoch);
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, scene.sceneId(), epoch));
        repo.completeAll();
        ScenePlayer player = world.playerBySession(new SessionKey(LINK, sessionId));
        assertThat(player).isNotNull();
        sink.clear();
        locations.clear();
        snapshots.clear();
        return player;
    }

    /** 发一条 226；返回请求号。 */
    long travel(ScenePlayer player, int targetZoneId, int sceneConfigId) {
        long requestId = ++requestIds;
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(player.session().sessionId())
                .setPlayerId(player.playerId())
                .setMessageId(Contracts.IDS.travelToZone())
                .setBody(TravelToZoneRequest.newBuilder().setTargetZoneId(targetZoneId).setSceneConfigId(sceneConfigId).build()
                        .toByteString())
                .setRequestId(requestId)
                .build());
        return requestId;
    }

    /** 发一条 63；返回请求号。 */
    long enterScene(ScenePlayer player, long sceneId, int configId) {
        long requestId = ++requestIds;
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(player.session().sessionId())
                .setPlayerId(player.playerId())
                .setMessageId(Contracts.IDS.enterScene())
                .setBody(EnterSceneC2SRequest.newBuilder()
                        .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(sceneId)).build()
                        .toByteString())
                .setRequestId(requestId)
                .build());
        return requestId;
    }

    /** 让玩家进 PRECHECK：226 {目标 zone, 不带地图} 过了同步段，返回挂起的在队检查。 */
    FakeTeamChecks.PendingCheck precheck(ScenePlayer player) {
        travel(player, TARGET_ZONE, 0);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.PRECHECK);
        return teamChecks.take();
    }

    /** 让玩家进 RESOLVING：在队检查回「不在队」（应答 {0}），返回挂起的选目标请求。 */
    FakeTravelTargets.PendingTravel resolve(ScenePlayer player) {
        precheck(player).notInTeam();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        return targets.take();
    }

    /** 让玩家进 FREEZING：选目标回「选中」（{@link #TARGET_MAP}、{@link #REDIRECT}），返回挂起的交出。 */
    FakePlayerRepository.PendingHandOff freeze(ScenePlayer player) {
        resolve(player).chosen(TARGET_MAP, REDIRECT);
        assertThat(player.frozen()).isTrue();
        return repo.takeHandOff();
    }

    /** 让玩家进 5.2 的 RESOLVING：63 指定别的节点上的场景（应答 {0}），返回挂起的跨节点选目标。 */
    FakeSwitchTargets.PendingSelect resolveRemote(ScenePlayer player) {
        enterScene(player, 900_001, 0);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        return switchTargets.take();
    }

    /** 某会话收到的 226 应答（按到达顺序）。 */
    List<MessageContent> travelReplies(int sessionId) {
        List<MessageContent> out = new ArrayList<>();
        for (MessageContent m : sink.to(LINK, sessionId)) {
            if (m.getMessageId() == Contracts.IDS.travelToZone()) {
                out.add(m);
            }
        }
        return out;
    }

    /** 某会话收到的 226 应答的 tip 序列（按到达顺序）。 */
    List<Integer> travelTips(int sessionId) {
        return travelReplies(sessionId).stream().map(TravelFixture::travelTip).toList();
    }

    static int travelTip(MessageContent reply) {
        try {
            TravelToZoneResponse response = TravelToZoneResponse.parseFrom(reply.getSerializedMessage());
            assertThat(response.hasErrorMessage()).as("226 应答总是带 error_message").isTrue();
            assertThat(reply.hasErrorMessage()).as("拒绝码在应答体里，不在信封").isFalse();
            return response.getErrorMessage().getId();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    /** 某会话收到的 63 应答的 tip 序列。 */
    List<Integer> enterSceneTips(int sessionId) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : sink.to(LINK, sessionId)) {
            if (m.getMessageId() == Contracts.IDS.enterScene()) {
                try {
                    out.add(EnterSceneC2SResponse.parseFrom(m.getSerializedMessage()).getErrorMessage().getId());
                } catch (InvalidProtocolBufferException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    /** 某会话收到的 23 推送的 tip 序列。 */
    List<Integer> pushedTips(int sessionId) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : sink.to(LINK, sessionId)) {
            if (m.getMessageId() == TIP) {
                assertThat(m.getId()).as("23 是推送，信封 id 为 0").isZero();
                try {
                    out.add(TipInfoMessage.parseFrom(m.getSerializedMessage()).getId());
                } catch (InvalidProtocolBufferException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    double requests(String result) {
        return count("xm.scene.travel.requests", result);
    }

    /** 全部 {@code travel.requests} 之和（每条 226 恰好计一次）。 */
    double requestsTotal() {
        return meters.find("xm.scene.travel.requests").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    double resolves(String result) {
        return count("xm.scene.travel.resolves", result);
    }

    double placements(String result) {
        return count("xm.scene.travel.placements", result);
    }

    double transfers(String reason, String result) {
        return meters.get("xm.scene.transfers").tag("reason", reason).tag("result", result.toLowerCase(Locale.ROOT))
                .counter().count();
    }

    double battleGateRejects(String gate) {
        return meters.get("xm.scene.battle.gate.rejects").tag("gate", gate).counter().count();
    }

    double inFlight() {
        return meters.get("xm.scene.transfers.in.flight").gauge().value();
    }

    /** 指标 {@code xm.scene.travel.frames.pending}；断言时同时核对世界的读口与它一致。 */
    double framesPending() {
        double gauge = meters.get("xm.scene.travel.frames.pending").gauge().value();
        assertThat(gauge).as("指标与世界的读口一致").isEqualTo(world.travelFramesPending());
        return gauge;
    }

    private double count(String meter, String result) {
        return meters.get(meter).tag("result", result.toLowerCase(Locale.ROOT)).counter().count();
    }
}
