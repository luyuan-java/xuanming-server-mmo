package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.SceneBattleService;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.SceneAssetLocator;
import com.game.match.gather.ScenePreparer.Endpoint;
import com.game.match.gather.ScenePreparer.Prepare;
import com.game.match.testing.FakeNodeCalls;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FakeSceneBattle;
import com.game.match.testing.FakeSceneNodes;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * gather 对 scene 的备战与取消（match-spec §9.6 第 3 步与 fail 行、§9.7.2 的状态映射；spectate-spec Z3）：定位只认在线的位置记录并按
 * (zone, 节点号) 找节点；各种应答的结局与「要不要补发取消」；取消发回备战时的端点、不重新定位。
 */
class ScenePreparerTest {

    private static final long BATTLE = 7_000_000_001L;
    private static final long A = 1001;
    private static final long B = 1002;

    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeSceneNodes sceneNodes = new FakeSceneNodes();
    private final FakeNodeCalls<SceneBattleService> calls = new FakeNodeCalls<>();
    private final FakeSceneBattle sceneA = new FakeSceneBattle("scene-inst-a");
    private ScenePreparer preparer;

    @BeforeEach
    void setUp() {
        sceneNodes.add(1, 7, "scene-inst-a", 21100);
        calls.register(sceneNodes.targetOf(1, 7), sceneA);
        players.online(A, 1, 7);
        preparer = new ScenePreparer(new SceneAssetLocator(players::holderAsync, sceneNodes, null), calls);
    }

    private static PrepareBattleRequest request(long playerId) {
        return PrepareBattleRequest.newBuilder().setPlayerId(playerId).setBattleId(BATTLE).setBattleNodeId(3).setDeadlineMs(900_000)
                .setPrepareDeadlineMs(642_000).build();
    }

    private static Prepare.Failed failed(Prepare result) {
        assertThat(result).isInstanceOf(Prepare.Failed.class);
        return (Prepare.Failed) result;
    }

    /** 只应答备战的假 scene：应答由测试给定。 */
    private static SceneBattleService replying(Function<SceneBattleCall, SceneBattleReply> reply) {
        return new SceneBattleService() {
            @Override
            public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
                return CompletableFuture.completedFuture(reply.apply(call));
            }

            @Override
            public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
                return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build());
            }

            @Override
            public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    // ================================================================ 定位

    @Test
    void 位置在线且目录有节点_备战成功_请求原样送到该节点_带目录里的实例号_超时3秒() {
        Prepare result = preparer.prepare(request(A));

        assertThat(result).isInstanceOf(Prepare.Ok.class);
        Prepare.Ok ok = (Prepare.Ok) result;
        assertThat(ok.endpoint()).isEqualTo(new Endpoint(1, 7, new NodeRpcClients.Target("127.0.0.1", 21100, "scene-inst-a")));
        assertThat(ok.response().getSnapshot().getPlayerId()).isEqualTo(A);
        assertThat(ok.response().getTableFingerprint()).isEqualTo("fp-1");
        assertThat(calls.calls).singleElement().satisfies(call -> {
            assertThat(call.target().address()).isEqualTo("127.0.0.1:21100");
            assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(3));
        });
        assertThat(sceneA.calls).singleElement().satisfies(call -> {
            assertThat(call.describe()).isEqualTo("prepare:1001");
            assertThat(call.targetInstanceId()).isEqualTo("scene-inst-a");
            assertThat(call.prepare()).isEqualTo(request(A));
        });
        assertThat(sceneA.frozen()).containsEntry(A, BATTLE);
        assertThat(players.reads).as("定位只读位置记录，不读在线目录").containsExactly("location:1001");
        assertThat(sceneNodes.lookups).containsExactly("1:7");
    }

    @Test
    void 位置是重连租约_登出墓碑_不存在_都是no_location_不发任何调用_不补发取消() {
        players.location(A, LocationStatus.RECONNECT_LEASE);
        players.location(B, LocationStatus.LOGGED_OUT);

        for (long playerId : new long[] {A, B, 1003}) {
            Prepare.Failed result = failed(preparer.prepare(request(playerId)));

            assertThat(result.outcome()).as("player %d", playerId).isEqualTo(GatherOutcome.NO_LOCATION);
            assertThat(result.endpoint()).isNull();
            assertThat(result.cancelNeeded()).isFalse();
        }
        assertThat(calls.calls).isEmpty();
        assertThat(sceneNodes.lookups).as("没有在线的位置记录就不查目录").isEmpty();
    }

    @Test
    void 读位置失败_同样按no_location_该玩家是肇事者_照搬基线() {
        players.failLocation(A);

        Prepare.Failed result = failed(preparer.prepare(request(A)));

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(result.detail()).contains("定位失败");
        assertThat(calls.calls).isEmpty();
    }

    @Test
    void 目录里没有这个节点_目录读失败_节点不提供战斗入口_都是no_location() {
        players.online(B, 1, 8);            // 目录里没有 1:8
        players.online(1003, 3, 7);         // zone 3 的目录读失败
        sceneNodes.failZone(3);
        players.online(1004, 1, 9);         // 1:9 的 rpc_port = 0
        sceneNodes.set(SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(9).setInstanceId("scene-inst-old").setRpcHost("127.0.0.1").build());

        for (long playerId : new long[] {B, 1003, 1004}) {
            Prepare.Failed result = failed(preparer.prepare(request(playerId)));

            assertThat(result.outcome()).as("player %d", playerId).isEqualTo(GatherOutcome.NO_LOCATION);
            assertThat(result.cancelNeeded()).isFalse();
        }
        assertThat(calls.calls).isEmpty();
        assertThat(sceneNodes.lookups).containsExactly("1:8", "3:7", "1:9");
    }

    @Test
    void 跨zone同节点号不串_各自按位置记录的zone找到自己的节点() {
        FakeSceneBattle sceneB = new FakeSceneBattle("scene-inst-b");
        sceneNodes.add(2, 7, "scene-inst-b", 21101);
        calls.register(sceneNodes.targetOf(2, 7), sceneB);
        players.online(B, 2, 7);

        Prepare.Ok first = (Prepare.Ok) preparer.prepare(request(A));
        Prepare.Ok second = (Prepare.Ok) preparer.prepare(request(B));

        assertThat(first.endpoint().zoneId()).isEqualTo(1);
        assertThat(first.endpoint().target().address()).isEqualTo("127.0.0.1:21100");
        assertThat(second.endpoint().zoneId()).isEqualTo(2);
        assertThat(second.endpoint().target()).isEqualTo(new NodeRpcClients.Target("127.0.0.1", 21101, "scene-inst-b"));
        assertThat(sceneNodes.lookups).containsExactly("1:7", "2:7");
        assertThat(sceneA.frozen()).containsOnlyKeys(A);
        assertThat(sceneB.frozen()).containsOnlyKeys(B);
    }

    /**
     * 同号节点跨 zone 碰撞下的取消（spectate-spec §2.8、Z3）：备战之后两人的位置记录对调，各自指向「另一个 zone 的同号节点」。
     * 取消若按位置重新解析，会发到对方那台（实例号对得上、玩家却不在那里）——所以必须发回备战时记下的端点，端点里带着 zone。
     */
    @Test
    void 跨zone同节点号_取消各发回备战时的端点_位置已指向另一个zone的同号节点也不串() {
        FakeSceneBattle sceneB = new FakeSceneBattle("scene-inst-b");
        sceneNodes.add(2, 7, "scene-inst-b", 21101);
        calls.register(sceneNodes.targetOf(2, 7), sceneB);
        players.online(B, 2, 7);
        Prepare.Ok first = (Prepare.Ok) preparer.prepare(request(A));
        Prepare.Ok second = (Prepare.Ok) preparer.prepare(request(B));
        players.location(A, 2, 7);
        players.location(B, 1, 7);
        int readsBefore = players.reads.size();
        int lookupsBefore = sceneNodes.lookups.size();

        assertThat(preparer.cancel(A, BATTLE, first.endpoint())).isTrue();
        assertThat(preparer.cancel(B, BATTLE, second.endpoint())).isTrue();

        assertThat(sceneA.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "cancel:1001");
        assertThat(sceneB.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1002", "cancel:1002");
        assertThat(sceneA.frozen()).as("A 在 zone 1 的 7 号上解冻").isEmpty();
        assertThat(sceneB.frozen()).as("B 在 zone 2 的 7 号上解冻").isEmpty();
        assertThat(calls.remembered).extracting(c -> c.target().address()).containsExactly("127.0.0.1:21100", "127.0.0.1:21101");
        assertThat(players.reads).as("取消不读位置记录").hasSize(readsBefore);
        assertThat(sceneNodes.lookups).as("取消不查 scene 目录").hasSize(lookupsBefore);
        assertThat(first.endpoint()).as("节点号相同、zone 不同的两个端点不是同一个").isNotEqualTo(second.endpoint());
        assertThat(second.endpoint().toString()).as("日志里的端点带 zone：同号节点靠它分辨").isEqualTo("z2/n7@127.0.0.1:21101#scene-inst-b");
    }

    /** 目录读口出了岔子、对 zone 2 的查询回了 zone 1 的同号条目：定位器按「条目与键不符」判故障，请求不发出，不会把 B 冻结在别的 zone 的节点上。 */
    @Test
    void 目录对zone2的查询回了zone1的同号条目_按定位故障_no_location_不发调用() {
        SceneAssetLocator.SceneNodeLookup crossed = (zoneId, nodeId) -> sceneNodes.findAsync(1, nodeId);
        ScenePreparer confused = new ScenePreparer(new SceneAssetLocator(players::holderAsync, crossed, null), calls);
        players.online(B, 2, 7);

        Prepare.Failed result = failed(confused.prepare(request(B)));

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(result.detail()).contains("与键不符");
        assertThat(result.cancelNeeded()).isFalse();
        assertThat(calls.calls).isEmpty();
        assertThat(sceneA.frozen()).as("zone 1 的 7 号没有冻结 zone 2 的玩家").isEmpty();
    }

    // ================================================================ 应答的映射

    @Test
    void scene明确拒绝_tip非0_是肇事者_不补发取消() {
        sceneA.prepareTip(A, 1006);

        Prepare.Failed result = failed(preparer.prepare(request(A)));

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(result.cancelNeeded()).as("scene 保证拒绝不留冻结痕迹").isFalse();
        assertThat(result.endpoint()).isNotNull();
        assertThat(result.detail()).contains("1006");
    }

    @Test
    void 实例不符回NOT_HERE_过载回OVERLOADED_都是肇事者_不补发取消() {
        // 目录过时：1:8 登记的实例不是那个地址上真正的进程
        sceneNodes.add(1, 8, "scene-inst-stale", 21108);
        calls.register(sceneNodes.targetOf(1, 8), new FakeSceneBattle("scene-inst-restarted"));
        players.online(B, 1, 8);
        sceneA.prepareStatus(A, SceneBattleStatus.SCENE_BATTLE_OVERLOADED);

        Prepare.Failed notHere = failed(preparer.prepare(request(B)));
        Prepare.Failed overloaded = failed(preparer.prepare(request(A)));

        for (Prepare.Failed result : new Prepare.Failed[] {notHere, overloaded}) {
            assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
            assertThat(result.cancelNeeded()).as("零副作用").isFalse();
        }
        assertThat(notHere.detail()).contains("NOT_HERE");
        assertThat(overloaded.detail()).contains("OVERLOADED");
    }

    @Test
    void 状态缺失_传输失败_都是结局不明_要补发取消_端点是备战时的那个() {
        sceneA.prepareStatus(A, SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED);
        players.online(B, 1, 7);
        sceneA.prepareFails(B, () -> new IllegalStateException("连接断开"), true);

        Prepare.Failed unspecified = failed(preparer.prepare(request(A)));
        Prepare.Failed transport = failed(preparer.prepare(request(B)));

        for (Prepare.Failed result : new Prepare.Failed[] {unspecified, transport}) {
            assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
            assertThat(result.cancelNeeded()).isTrue();
            assertThat(result.endpoint().target().address()).isEqualTo("127.0.0.1:21100");
        }
        assertThat(transport.detail()).contains("连接断开");
    }

    @Test
    void DEFERRED这类备战不该出现的状态_按结局不明_要补发取消() {
        sceneA.prepareStatus(A, SceneBattleStatus.SCENE_BATTLE_DEFERRED);

        assertThat(failed(preparer.prepare(request(A))).cancelNeeded()).isTrue();
    }

    @Test
    void 备战永不应答_到点按结局不明收场_不等到天荒地老() {
        ScenePreparer quick = new ScenePreparer(new SceneAssetLocator(players::holderAsync, sceneNodes, null), calls, Duration.ofMillis(80),
                Duration.ofMillis(80));
        sceneA.prepareHangs(A);

        long started = System.nanoTime();
        Prepare.Failed result = failed(quick.prepare(request(A)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(result.cancelNeeded()).as("超时 ≠ 失败：请求可能已在 scene 生效").isTrue();
        assertThat(result.detail()).contains("超时");
        assertThat(elapsedMs).isBetween(70L, 3_000L);
        assertThat(sceneA.frozen()).as("假 scene 上他确实已经冻结了").containsKey(A);
    }

    @Test
    void 出站口报超时_同样是结局不明() {
        calls.timeout(sceneNodes.targetOf(1, 7));

        Prepare.Failed result = failed(preparer.prepare(request(A)));

        assertThat(result.cancelNeeded()).isTrue();
        assertThat(result.detail()).contains(TimeoutException.class.getSimpleName());
    }

    @Test
    void 错误码为0却没有快照_快照不是本人_应答体解析不了_都按结局不明_要补发取消() {
        sceneA.prepareWithoutSnapshot(A);
        sceneNodes.add(1, 8, "scene-inst-wrong", 21108);
        calls.register(sceneNodes.targetOf(1, 8), replying(call -> SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                .setBody(PrepareBattleResponse.newBuilder().setSnapshot(BattlePlayerSnapshot.newBuilder().setPlayerId(9999)).build().toByteString())
                .build()));
        players.online(B, 1, 8);
        sceneNodes.add(1, 9, "scene-inst-garbage", 21109);
        calls.register(sceneNodes.targetOf(1, 9), replying(call -> SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                .setBody(ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF})).build()));
        players.online(1003, 1, 9);

        for (long playerId : new long[] {A, B, 1003}) {
            Prepare.Failed result = failed(preparer.prepare(request(playerId)));

            assertThat(result.outcome()).as("player %d", playerId).isEqualTo(GatherOutcome.PREPARE_FAILED);
            assertThat(result.cancelNeeded()).as("player %d", playerId).isTrue();
        }
    }

    // ================================================================ 取消

    @Test
    void 取消发回备战时的端点_位置已变成重连租约也照发_不重新定位() {
        Prepare.Ok ok = (Prepare.Ok) preparer.prepare(request(A));
        players.disconnected(A);
        int readsBefore = players.reads.size();
        int lookupsBefore = sceneNodes.lookups.size();

        boolean handled = preparer.cancel(A, BATTLE, ok.endpoint());

        assertThat(handled).isTrue();
        assertThat(sceneA.frozen()).isEmpty();
        assertThat(sceneA.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "cancel:1001");
        FakeSceneBattle.Call cancel = sceneA.calls.get(1);
        assertThat(cancel.cancel().getPlayerId()).isEqualTo(A);
        assertThat(cancel.cancel().getBattleId()).isEqualTo(BATTLE);
        assertThat(cancel.targetInstanceId()).isEqualTo("scene-inst-a");
        assertThat(calls.calls.get(1).timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(players.reads).as("取消不读位置记录").hasSize(readsBefore);
        assertThat(sceneNodes.lookups).as("取消不查 scene 目录").hasSize(lookupsBefore);
    }

    /**
     * 备战的目标刚从目录读出来（实例号是现在的），取消的端点是先前记下的（scene 原地重启之后实例号就过时了）。客户端缓存按实例号重建引用：
     * 取消拿过时的实例号去重建，会把别的 gather 正在用的新引用顶掉。所以取消走「记下来的目标」那个口，备战走普通的口。
     */
    @Test
    void 备战经目录给的目标发_取消经记下来的目标发_不因过时的实例号顶掉现有的客户端() {
        Prepare.Ok ok = (Prepare.Ok) preparer.prepare(request(A));
        assertThat(calls.remembered).as("备战不是记下来的目标").isEmpty();

        preparer.cancel(A, BATTLE, ok.endpoint());

        assertThat(calls.remembered).singleElement().satisfies(call -> {
            assertThat(call.target()).isEqualTo(ok.endpoint().target());
            assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(3));
        });
        assertThat(calls.calls).hasSize(2);
    }

    @Test
    void 取消的传输失败_NOT_HERE_连不上_都只回false_不抛() {
        Prepare.Ok ok = (Prepare.Ok) preparer.prepare(request(A));
        sceneA.cancelFails(A, () -> new IllegalStateException("断连"));
        assertThat(preparer.cancel(A, BATTLE, ok.endpoint())).isFalse();

        sceneA.cancelStatus(A, SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(preparer.cancel(A, BATTLE, ok.endpoint())).isFalse();

        calls.unreachable(ok.endpoint().target());
        assertThat(preparer.cancel(A, BATTLE, ok.endpoint())).isFalse();
        assertThat(sceneA.frozen()).as("取消没成功：冻结留给 scene 的 reaper").containsKey(A);
    }

    @Test
    void 取消永不应答_到点只记日志返回() {
        SceneBattleService hanging = new SceneBattleService() {
            @Override
            public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
                throw new UnsupportedOperationException();
            }
        };
        NodeRpcClients.Target target = new NodeRpcClients.Target("127.0.0.1", 21150, "scene-inst-slow");
        calls.register(target, hanging);
        ScenePreparer quick = new ScenePreparer(new SceneAssetLocator(players::holderAsync, sceneNodes, null), calls, Duration.ofMillis(80),
                Duration.ofMillis(80));

        long started = System.nanoTime();
        boolean handled = quick.cancel(A, BATTLE, new Endpoint(1, 15, target));

        assertThat(handled).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(70L, 3_000L);
    }
}
