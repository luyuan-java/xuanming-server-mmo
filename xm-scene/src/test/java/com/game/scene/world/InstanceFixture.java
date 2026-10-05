package com.game.scene.world;

import static com.game.scene.world.ChannelPlanApplyTest.active;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.EnterSceneS2C;
import com.game.proto.MessageContent;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeInstanceIds;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 镜像 / 副本实例（批次 5.3）单测的共用装配：开了实例取号的世界（假取号 {@link FakeInstanceIds}、假仓库、手动时钟、记录全部出站 /
 * 位置记录 / 目录补发次数），本节点号 {@link #LOCAL_NODE}；按频道计划铺三条主世界频道：{@link #A}、{@link #B}（图 1 = 默认大世界）、
 * {@link #C}（图 2）。玩家都是老号，存档在图 1 的 {@link #SAVED}（与出生点不同，好区分「坐标保留」与「落出生点」）。
 */
final class InstanceFixture {

    static final long LINK = 1;
    static final int LOCAL_NODE = 3;
    /** 主世界频道号（≥ 2^63：一律按无符号处理）。 */
    static final long A = 0x8000_0000_0000_0A01L;
    static final long B = 0x8000_0000_0000_0A02L;
    static final long C = 0x8000_0000_0000_0A03L;
    /** Mirror 表第一行（假配表：1、2）。 */
    static final int M = 1;
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(4);
    static final Vec3 SAVED = new Vec3(190, 210, 0);
    static final int TIP = 23;

    /** 世界的装配参数（缺省同生产缺省）。 */
    record Settings(Duration mirrorIdle, Duration idle, Duration grace, int maxPerNode, int maxPerCreator,
                    boolean idsEnabled, boolean crossNode, TeamFollow teamFollow) {

        static final Settings DEFAULT = new Settings(Duration.ofSeconds(30), Duration.ofSeconds(300),
                Duration.ofSeconds(30), 200, 3, true, false, TeamFollow.NONE);

        Settings caps(int node, int creator) {
            return new Settings(mirrorIdle, idle, grace, node, creator, idsEnabled, crossNode, teamFollow);
        }

        Settings timeouts(Duration mirror, Duration instance) {
            return new Settings(mirror, instance, grace, maxPerNode, maxPerCreator, idsEnabled, crossNode, teamFollow);
        }

        Settings withoutIds() {
            return new Settings(mirrorIdle, idle, grace, maxPerNode, maxPerCreator, false, crossNode, teamFollow);
        }

        Settings withCrossNode() {
            return new Settings(mirrorIdle, idle, grace, maxPerNode, maxPerCreator, idsEnabled, true, teamFollow);
        }

        Settings follow(TeamFollow follow) {
            return new Settings(mirrorIdle, idle, grace, maxPerNode, maxPerCreator, idsEnabled, crossNode, follow);
        }
    }

    /** 一次位置记录：玩家、场景号、场景的配置号。 */
    record Location(long playerId, long sceneId, int sceneConfigId) {
    }

    final RecordingSink sink = new RecordingSink();
    final FakePlayerRepository repo = new FakePlayerRepository();
    final FakeInstanceIds ids = new FakeInstanceIds();
    final FakeSwitchTargets targets = new FakeSwitchTargets();
    final ManualClock clock = new ManualClock();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicInteger directoryPublishes = new AtomicInteger();
    /** 进场时的玩家初始化（缺省什么都不做）；用例换成抛异常的模拟存档损坏等进场失败。 */
    final java.util.concurrent.atomic.AtomicReference<PlayerInitializer> initializer =
            new java.util.concurrent.atomic.AtomicReference<>(PlayerInitializer.NONE);
    final List<Location> locations = new ArrayList<>();
    final SceneWorld world;
    final ClientRequestHandler handler;
    private final AtomicLong instanceIds = new AtomicLong(0x8000_0000_0000_1000L);
    private long planVersion;

    InstanceFixture() {
        this(Settings.DEFAULT);
    }

    InstanceFixture(Settings settings) {
        PlayerLocations recording = new PlayerLocations() {
            @Override
            public void entered(ScenePlayer player) {
                locations.add(new Location(player.playerId(), player.scene().sceneId(), player.scene().configId()));
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
        CrossNodeSwitch crossNode = settings.crossNode()
                ? new CrossNodeSwitch(LOCAL_NODE, targets, owned -> { }, RESOLVE_TIMEOUT, Duration.ofSeconds(30))
                : CrossNodeSwitch.DISABLED;
        SceneInstances instances = new SceneInstances(LOCAL_NODE, settings.idsEnabled() ? ids : null,
                settings.mirrorIdle(), settings.idle(), settings.grace(), settings.maxPerNode(), settings.maxPerCreator(),
                RESOLVE_TIMEOUT, directoryPublishes::incrementAndGet);
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                clock, new SceneMetrics(meters), p -> initializer.get().initialize(p), PlayerSnapshots.NONE, recording,
                settings.teamFollow(), crossNode, instances);
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
        plan(active(A, 1), active(B, 1), active(C, 2));
        directoryPublishes.set(0);
    }

    // ------------------------------------------------------------------ 频道计划

    /** 应用下一版频道计划（只含给出的记录）。 */
    void plan(com.game.api.proto.WorldChannel... records) {
        assertThat(world.applyChannelPlan(++planVersion, List.of(records))).isTrue();
    }

    Scene scene(long sceneId) {
        return world.sceneById(sceneId);
    }

    // ------------------------------------------------------------------ 进场 / 离场

    /** 老号进场（存档在图 1 的 {@link #SAVED}），完成加载后清掉出站与位置记录。 */
    ScenePlayer enter(int sessionId, long playerId, long sceneId) {
        repo.putSavedPlayer(playerId, 1, 1, SAVED);
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, sceneId, 1));
        repo.completeAll();
        ScenePlayer player = world.playerBySession(new SessionKey(LINK, sessionId));
        assertThat(player).isNotNull();
        assertThat(player.scene().sceneId()).isEqualTo(sceneId);
        sink.clear();
        locations.clear();
        return player;
    }

    void leave(ScenePlayer player) {
        world.onPlayerLeave(LINK, SceneWorldTest.leave(player.session().sessionId(), player.playerId()));
        assertThat(world.playerById(player.playerId())).isNull();
    }

    // ------------------------------------------------------------------ 63

    void enterScene(ScenePlayer player, int configId, long sceneId, int mirrorConfigId, int dungeonConfigId,
                    long requestId) {
        EnterSceneC2SRequest request = EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(sceneId)
                        .setMirrorConfigId(mirrorConfigId).setDungeonConfigId(dungeonConfigId))
                .build();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(player.session().sessionId())
                .setPlayerId(player.playerId())
                .setMessageId(Contracts.IDS.enterScene())
                .setBody(request.toByteString())
                .setRequestId(requestId)
                .build());
    }

    /** 63 {mirror_config_id = M, scene_id = 0}。 */
    void requestMirror(ScenePlayer player, int mirrorConfigId) {
        enterScene(player, 0, 0, mirrorConfigId, 0, 7);
    }

    long nextInstanceId() {
        return instanceIds.incrementAndGet();
    }

    /** 走完整条镜像链：63 → 取号 → 发本节点的新号 → 建好并换入。返回建好的镜像。 */
    Scene createMirror(ScenePlayer creator) {
        requestMirror(creator, M);
        FakeInstanceIds.PendingCreate create = ids.take();
        long id = nextInstanceId();
        create.issued(LOCAL_NODE, id);
        Scene mirror = world.sceneById(id);
        assertThat(mirror).isNotNull();
        assertThat(creator.scene()).isSameAs(mirror);
        return mirror;
    }

    /** dev 管理口建副本（取号立即发本节点的新号），返回应答。 */
    CreateDungeonInstanceResponse createDungeon(int dungeonConfigId) {
        CompletableFuture<CreateDungeonInstanceResponse> out = new CompletableFuture<>();
        world.createDungeon(dungeonConfigId, out);
        if (!out.isDone()) {
            ids.take().issued(LOCAL_NODE, nextInstanceId());
        }
        assertThat(out).as("结果回来了").isCompleted();
        return out.join();
    }

    // ------------------------------------------------------------------ 时间

    /** 推进时钟后跑一次每秒维护。 */
    void tick(Duration elapsed) {
        clock.advanceMillis(elapsed.toMillis());
        world.maintainScenes();
    }

    /** 按 1 s 一拍推进 {@code seconds} 拍（每拍都跑维护，与生产节拍一致）。 */
    void tickSeconds(int seconds) {
        for (int i = 0; i < seconds; i++) {
            tick(Duration.ofSeconds(1));
        }
    }

    // ------------------------------------------------------------------ 出站

    List<Integer> messageIds(ScenePlayer player) {
        return sink.messageIdsTo(LINK, player.session().sessionId());
    }

    List<MessageContent> to(ScenePlayer player) {
        return sink.to(LINK, player.session().sessionId());
    }

    /** 63 应答的 tip 序列。 */
    List<Integer> replies(ScenePlayer player) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : to(player)) {
            if (m.getMessageId() == Contracts.IDS.enterScene()) {
                out.add(parse(() -> EnterSceneC2SResponse.parseFrom(m.getSerializedMessage()).getErrorMessage().getId()));
            }
        }
        return out;
    }

    /** 23 推送的 tip 序列。 */
    List<Integer> pushedTips(ScenePlayer player) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : to(player)) {
            if (m.getMessageId() == TIP) {
                out.add(parse(() -> TipInfoMessage.parseFrom(m.getSerializedMessage()).getId()));
            }
        }
        return out;
    }

    /** 收到的 79 的 scene_info（按顺序）。 */
    List<SceneInfoComp> enterNotices(ScenePlayer player) {
        List<SceneInfoComp> out = new ArrayList<>();
        for (MessageContent m : to(player)) {
            if (m.getMessageId() == Contracts.IDS.notifyEnterScene()) {
                out.add(parseInfo(m));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 指标

    double lifecycle(String kind, String event) {
        return meters.get("xm.scene.instance.lifecycle").tag("kind", kind).tag("event", event).counter().count();
    }

    double requests(String result) {
        return meters.get("xm.scene.mirror.requests").tag("result", result).counter().count();
    }

    double resolves(String result) {
        return meters.get("xm.scene.mirror.resolves").tag("result", result).counter().count();
    }

    double instances(String kind, String state) {
        return meters.get("xm.scene.instances").tag("kind", kind).tag("state", state).gauge().value();
    }

    double channels(String state) {
        return meters.get("xm.scene.channels").tag("state", state).gauge().value();
    }

    double players(int sceneConfigId) {
        return meters.get("xm.scene.players").tag("scene_config", Integer.toString(sceneConfigId)).gauge().value();
    }

    // ------------------------------------------------------------------ 解析

    interface ParseCall<T> {
        T get() throws InvalidProtocolBufferException;
    }

    static <T> T parse(ParseCall<T> call) {
        try {
            return call.get();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    static SceneInfoComp parseInfo(MessageContent m) {
        return parse(() -> EnterSceneS2C.parseFrom(m.getSerializedMessage()).getSceneInfo());
    }
}
