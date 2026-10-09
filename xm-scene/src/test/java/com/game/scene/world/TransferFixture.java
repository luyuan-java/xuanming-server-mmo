package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.PlayerEnter;
import com.game.proto.EnterSceneC2SRequest;
import com.game.proto.EnterSceneC2SResponse;
import com.game.proto.MessageContent;
import com.game.proto.SceneInfoComp;
import com.game.proto.TipInfoMessage;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.team.TeamFollow;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨节点换图（批次 5.2）单测的共用装配：开了跨节点换图的世界（假选目标、假仓库、记录全部出站 / 位置 / 快照 / 立即续约），
 * 本节点号 {@link #LOCAL_NODE}，两张主世界地图各一个场景。
 */
final class TransferFixture {

    static final long LINK = 1;
    static final int LOCAL_NODE = 3;
    static final int TARGET_NODE = 4;
    /** 别的节点上的场景号（本节点没有）。 */
    static final long REMOTE_SCENE = 900_001;
    static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(4);
    static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    static final int TIP = 23;

    /** 一次位置记录写：动作、玩家、epoch、写序号、场景号。 */
    record LocationCall(String action, long playerId, long epoch, long seq, long sceneId) {
    }

    /** 一份快照：玩家、epoch、原因。 */
    record Snapshot(long playerId, long epoch, PlayerSnapshots.Cause cause) {
    }

    final RecordingSink sink = new RecordingSink();
    final FakePlayerRepository repo = new FakePlayerRepository();
    final FakeSwitchTargets targets = new FakeSwitchTargets();
    final ManualClock clock = new ManualClock();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<OwnedPlayer> renewed = new ArrayList<>();
    final List<Snapshot> snapshots = new ArrayList<>();
    final List<LocationCall> locations = new ArrayList<>();
    final SceneWorld world;
    final ClientRequestHandler handler;
    final Scene scene1;
    final Scene scene2;

    TransferFixture() {
        this(TeamFollow.NONE);
    }

    TransferFixture(TeamFollow teamFollow) {
        PlayerLocations recording = new PlayerLocations() {
            @Override
            public void entered(ScenePlayer player) {
                locations.add(call("entered", player));
            }

            @Override
            public void disconnected(ScenePlayer player) {
                locations.add(call("disconnected", player));
            }

            @Override
            public void loggedOut(ScenePlayer player) {
                locations.add(call("loggedOut", player));
            }

            @Override
            public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
                locations.add(new LocationCall("loggedOutWhileLoading", playerId, ownerEpoch, 1, 0));
            }

            @Override
            public void refresh(Collection<ScenePlayer> players) {
            }
        };
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(5000)::incrementAndGet,
                clock, new SceneMetrics(meters), PlayerInitializer.NONE,
                (save, cause) -> snapshots.add(new Snapshot(save.playerId(), save.ownerEpoch(), cause)), recording,
                teamFollow, new CrossNodeSwitch(LOCAL_NODE, targets, renewed::add, RESOLVE_TIMEOUT, TOMBSTONE_TTL));
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
        scene1 = world.createScene(1);
        scene2 = world.createScene(2);
    }

    private static LocationCall call(String action, ScenePlayer player) {
        // 与 RedisPlayerLocations 一样：除续期外每次写都取下一个序号
        return new LocationCall(action, player.playerId(), player.ownerEpoch(), player.nextLocationSeq(),
                player.scene().sceneId());
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

    /** 交出进场（目标节点视角）：gate 改绑后发来的 PlayerEnter{transfer = true}。 */
    void transferEnter(int sessionId, long playerId, long sceneId, long epoch) {
        world.onPlayerEnter(LINK, PlayerEnter.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setSceneId(sceneId)
                .setOwnerEpoch(epoch)
                .setTransfer(true)
                .build());
    }

    void enterScene(int sessionId, long playerId, long sceneId, int configId, long requestId) {
        EnterSceneC2SRequest request = EnterSceneC2SRequest.newBuilder()
                .setSceneInfo(SceneInfoComp.newBuilder().setSceneConfigId(configId).setSceneId(sceneId))
                .build();
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setMessageId(Contracts.IDS.enterScene())
                .setBody(request.toByteString())
                .setRequestId(requestId)
                .build());
    }

    /** 让玩家进 RESOLVING：63 指定别的节点上的场景（应答 {0}），返回挂起的选目标请求。 */
    FakeSwitchTargets.PendingSelect resolveRemote(ScenePlayer player) {
        enterScene(player.session().sessionId(), player.playerId(), REMOTE_SCENE, 0, 1);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);
        return targets.take();
    }

    /** 让玩家进 FREEZING：选目标回「别的节点」，返回挂起的交出。 */
    FakePlayerRepository.PendingHandOff freeze(ScenePlayer player) {
        resolveRemote(player).chosen(TARGET_NODE, REMOTE_SCENE, 2);
        assertThat(player.frozen()).isTrue();
        return repo.takeHandOff();
    }

    /** 某会话收到的 63 应答的 tip 序列。 */
    List<Integer> enterSceneReplies(int sessionId) {
        List<Integer> out = new ArrayList<>();
        for (MessageContent m : sink.to(LINK, sessionId)) {
            if (m.getMessageId() == Contracts.IDS.enterScene()) {
                out.add(parse(() -> EnterSceneC2SResponse.parseFrom(m.getSerializedMessage()).getErrorMessage().getId()));
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
                out.add(parse(() -> TipInfoMessage.parseFrom(m.getSerializedMessage()).getId()));
            }
        }
        return out;
    }

    double count(String meter, String result) {
        return meters.get(meter).tag("result", result.toLowerCase(Locale.ROOT)).counter().count();
    }

    double resolves(String result) {
        return count("xm.scene.switch.resolves", result);
    }

    /**
     * 5.2 跨节点换图的交出结局：批次 5.4 起 {@code xm.scene.transfers} 多了 {@code reason} 标签，这条路径一律计在 {@code reason = player}。
     * 显式带上它（而不是对两个 reason 求和）：计错到 {@code travel} 下面时这里读到 0、用例会红。
     */
    double transfers(String result) {
        return transfers("player", result);
    }

    double transfers(String reason, String result) {
        return meters.get("xm.scene.transfers").tag("reason", reason).tag("result", result.toLowerCase(Locale.ROOT))
                .counter().count();
    }

    double inFlight() {
        return meters.get("xm.scene.transfers.in.flight").gauge().value();
    }

    private interface ParseCall {
        int get() throws InvalidProtocolBufferException;
    }

    private static int parse(ParseCall call) {
        try {
            return call.get();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
