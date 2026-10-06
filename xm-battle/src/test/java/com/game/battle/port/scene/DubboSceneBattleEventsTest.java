package com.game.battle.port.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SceneNodeInfo;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.battle.testing.CallJournal;
import com.game.battle.testing.FakeSceneLocator;
import com.game.battle.testing.RecordingSceneTransport;
import com.game.battle.testing.RecordingSceneTransport.Sent;
import com.game.battle.testing.Scripted;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleRouting;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 确认事件的真实传输（scene-battle-spec §7.16、D28、§13.5；审计 OBX-3 与 OBX-13 的回归）：假目录 + 假定位器 + 记录型传输，共用一份带全局序号的
 * 调用记录；指标读 {@code xm.battle.scene.events{kind=confirm}}。
 *
 * <p>§13.5 表里「补发节奏仍由 6.2 的房间测试覆盖」：{@code room.ConfirmResendTest}（首发 + 17 次补发）。
 */
class DubboSceneBattleEventsTest {

    private static final long P = 9_001;
    private static final long B = 77_001;
    private static final long DEADLINE = 1_800_000_192_000L;
    private static final int ZONE = 1;
    private static final int NODE = 3;
    private static final String INSTANCE = "scene-prepared";

    /** 假 scene 目录：按 (zone, node) 给条目，记下每次查的键。 */
    private final class FakeDirectory {
        final Map<String, SceneNodeInfo> nodes = new HashMap<>();
        final Scripted<Optional<SceneNodeInfo>> finds = new Scripted<>();

        void put(SceneNodeInfo info) {
            nodes.put(info.getZoneId() + "/" + info.getNodeId(), info);
        }

        CompletableFuture<Optional<SceneNodeInfo>> find(int zoneId, int nodeId) {
            String key = zoneId + "/" + nodeId;
            journal.add("find:" + key);
            return finds.invoke("find:" + key, () -> Optional.ofNullable(nodes.get(key)));
        }
    }

    private final CallJournal journal = new CallJournal();
    private final FakeDirectory directory = new FakeDirectory();
    private final FakeSceneLocator locator = new FakeSceneLocator(journal);
    private final RecordingSceneTransport transport = new RecordingSceneTransport(journal);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final DubboSceneBattleEvents events = new DubboSceneBattleEvents(directory::find, locator::locate, transport::confirm, metrics);

    // ------------------------------------------------------------------ 夹具

    /** 快照路由：备战时玩家所在的 scene（zone 1、节点 3、实例 scene-prepared）。 */
    private static BattleRouting routing() {
        return routing(ZONE, NODE, INSTANCE);
    }

    private static BattleRouting routing(int zone, int node, String instance) {
        return BattleRouting.newBuilder().setZoneId(zone).setSceneNodeId(node).setSceneInstanceId(instance).setGateNodeId(2)
                .setGateInstanceId("gate-1").setSessionId(77).build();
    }

    private static SceneNodeInfo node(int zone, int node, String instance, String host, int port) {
        return SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId(instance).setRpcHost(host).setRpcPort(port).build();
    }

    /** 目录里备战节点的条目还是备战时的那个实例。 */
    private void directoryHasPreparedInstance() {
        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", 21100));
    }

    private void confirm() {
        events.confirm(routing(), P, B, DEADLINE);
    }

    /** 计过数的确认结局（取值 → 次数；没计过的不出现）。 */
    private Map<String, Integer> confirms() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (SceneEventResult result : SceneEventResult.values()) {
            String label = result.name().toLowerCase(Locale.ROOT);
            int count = (int) registry.get("xm.battle.scene.events").tag("kind", "confirm").tag("result", label).counter().count();
            if (count != 0) {
                out.put(label, count);
            }
        }
        return out;
    }

    private double settlementEvents() {
        return registry.get("xm.battle.scene.events").tag("kind", "settlement").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ================================================================== 正常路径

    @Test
    void 目录实例相符_发往目录里的地址_目标是快照实例_body是确认事件_计sent_不问定位器() throws Exception {
        directoryHasPreparedInstance();

        confirm();

        assertThat(journal.entries()).containsExactly("find:1/3", "confirm:" + P + "/" + B + "@" + INSTANCE);
        Sent sent = transport.sent.get(0);
        assertThat(sent.method()).isEqualTo(RecordingSceneTransport.CONFIRM_BATTLE);
        assertThat(sent.endpoint()).isEqualTo(new SceneAssetEndpoint(ZONE, NODE, INSTANCE, "10.0.0.3", 21100));
        SceneBattleCall call = sent.call();
        assertThat(call.getTargetInstanceId()).isEqualTo(INSTANCE);
        assertThat(call.getPlayerId()).isEqualTo(P);
        assertThat(call.getAttempt()).isZero();
        assertThat(BattleConfirmedEvent.parseFrom(call.getBody())).isEqualTo(BattleConfirmedEvent.newBuilder().setBattleId(B)
                .setPlayerId(P).setDeadlineMs(DEADLINE).build());
        assertThat(confirms()).containsOnly(entry("sent", 1));
        assertThat(settlementEvents()).as("不碰结算的计数").isZero();
    }

    @Test
    void 正常发出_不等结果_目录与应答都悬着时confirm立即返回() {
        directoryHasPreparedInstance();
        directory.finds.hold();
        transport.replies.hold();

        confirm();
        assertThat(transport.sent).as("目录还没回来").isEmpty();
        assertThat(confirms()).isEmpty();

        directory.finds.last().release();
        assertThat(transport.sent).hasSize(1);
        assertThat(confirms()).as("发出即计 sent，不等应答").containsOnly(entry("sent", 1));

        transport.replies.last().release();
        assertThat(confirms()).as("HANDLED 不另计").containsOnly(entry("sent", 1));
    }

    @Test
    void 大号id原样进确认事件() throws Exception {
        directoryHasPreparedInstance();

        events.confirm(routing(), -5L, Long.MIN_VALUE + 7, 0);

        BattleConfirmedEvent body = BattleConfirmedEvent.parseFrom(transport.last().call().getBody());
        assertThat(body.getPlayerId()).isEqualTo(-5L);
        assertThat(body.getBattleId()).isEqualTo(Long.MIN_VALUE + 7);
        assertThat(body.getDeadlineMs()).isZero();
        assertThat(transport.last().call().getPlayerId()).isEqualTo(-5L);
    }

    // ================================================================== 回落到定位器（D28）

    @Test
    void 实例不符_问定位器_Found_发往定位到的实例与地址_计rerouted不计sent() throws Exception {
        directory.put(node(ZONE, NODE, "scene-restarted", "10.0.0.3", 21100));
        SceneAssetEndpoint now = locator.online(P, ZONE, 4, "scene-4");

        confirm();

        assertThat(journal.entries()).containsExactly("find:1/3", "locate:" + P, "confirm:" + P + "/" + B + "@scene-4");
        Sent sent = transport.sent.get(0);
        assertThat(sent.endpoint()).isEqualTo(now);
        assertThat(sent.call().getTargetInstanceId()).as("定位到的实例，不是快照实例").isEqualTo("scene-4");
        assertThat(BattleConfirmedEvent.parseFrom(sent.call().getBody()).getDeadlineMs()).isEqualTo(DEADLINE);
        assertThat(confirms()).containsOnly(entry("rerouted", 1));
    }

    @Test
    void 实例不符_玩家已在同节点号的新实例上_改投给新实例() {
        directory.put(node(ZONE, NODE, "scene-restarted", "10.0.0.3", 21100));
        locator.online(P, ZONE, NODE, "scene-restarted");

        confirm();

        assertThat(transport.last().call().getTargetInstanceId()).isEqualTo("scene-restarted");
        assertThat(confirms()).containsOnly(entry("rerouted", 1));
    }

    @Test
    void 目录无此节点_目录读失败_目录同步抛异常_目录返回空future_都回落到定位器() {
        locator.online(P, ZONE, 4, "scene-4");

        confirm();
        directory.finds.failWith(new TimeoutException("Redis 响应超时"));
        confirm();
        directory.finds.throwing(new IllegalStateException("目录端口抛异常"));
        confirm();
        directory.finds.returnNullFuture();
        confirm();
        directory.finds.replyWith(null);
        confirm();

        assertThat(journal.count("find:1/3")).isEqualTo(5);
        assertThat(journal.count("locate:")).isEqualTo(5);
        assertThat(transport.sent).hasSize(5).allSatisfy(s -> assertThat(s.call().getTargetInstanceId()).isEqualTo("scene-4"));
        assertThat(confirms()).containsOnly(entry("rerouted", 5));
    }

    @Test
    void 定位不到_NoHolder_故障_异常完成_同步抛出_空future_都不发_计skipped() {
        directory.put(node(ZONE, NODE, "scene-restarted", "10.0.0.3", 21100));

        confirm();
        locator.offline(P, ResolveResult.LEASE);
        confirm();
        locator.broken(P, "读位置记录失败");
        confirm();
        locator.locates.failWith(new IllegalStateException("定位出错"));
        confirm();
        locator.locates.throwing(new IllegalStateException("定位端口抛异常"));
        confirm();
        locator.locates.returnNullFuture();
        confirm();
        locator.locates.replyWith(null);
        confirm();

        assertThat(transport.sent).isEmpty();
        assertThat(confirms()).containsOnly(entry("skipped", 7));
    }

    @Test
    void 快照路由缺zone或节点或实例_不读目录_直接走定位器() {
        directoryHasPreparedInstance();
        locator.online(P, ZONE, 4, "scene-4");

        events.confirm(routing(0, NODE, INSTANCE), P, B, DEADLINE);
        events.confirm(routing(ZONE, 0, INSTANCE), P, B, DEADLINE);
        events.confirm(routing(ZONE, NODE, ""), P, B, DEADLINE);

        assertThat(journal.count("find:")).as("不读目录").isZero();
        assertThat(journal.count("locate:")).isEqualTo(3);
        assertThat(confirms()).containsOnly(entry("rerouted", 3));

        events.confirm(routing(ZONE, NODE, ""), P + 1, B, DEADLINE);
        assertThat(confirms()).as("定位不到就不发").containsOnly(entry("rerouted", 3), entry("skipped", 1));
    }

    @Test
    void 目录条目不提供直连地址_端口为0或host为空_按实例不符回落() {
        locator.online(P, ZONE, 4, "scene-4");

        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", 0));
        confirm();
        directory.put(node(ZONE, NODE, INSTANCE, " ", 21100));
        confirm();

        assertThat(transport.sent).hasSize(2).allSatisfy(s -> assertThat(s.call().getTargetInstanceId()).isEqualTo("scene-4"));
        assertThat(confirms()).containsOnly(entry("rerouted", 2));
    }

    @Test
    void zone2的1号节点_不命中zone1的1号节点() {
        directory.put(node(1, 1, "scene-z1n1", "10.0.1.1", 21100));
        locator.online(P, 2, 5, "scene-z2n5");

        events.confirm(routing(2, 1, "scene-z1n1"), P, B, DEADLINE);

        assertThat(journal.entries()).as("按快照路由的 zone 查目录，不串 zone").containsExactly("find:2/1", "locate:" + P,
                "confirm:" + P + "/" + B + "@scene-z2n5");
        assertThat(transport.last().endpoint().host()).isNotEqualTo("10.0.1.1");
        assertThat(confirms()).containsOnly(entry("rerouted", 1));
    }

    // ================================================================== 目录条目损坏（OBX-13）

    @Test
    void 目录条目端口越界_不吞异常_按实例不符回落_玩家还在这个节点上时定位器回故障_计skipped() {
        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", 70_000));
        locator.broken(P, "scene 节点目录条目的 rpc_port 越界: 70000");

        assertThatCode(this::confirm).doesNotThrowAnyException();

        assertThat(journal.entries()).containsExactly("find:1/3", "locate:" + P);
        assertThat(transport.sent).isEmpty();
        assertThat(confirms()).as("这次确认必须有一个计数结局，不能既没发也没计").containsOnly(entry("skipped", 1));
    }

    @Test
    void 目录条目端口按int读为负_同样回落_玩家已换节点时改投() {
        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", -1));
        locator.online(P, ZONE, 4, "scene-4");

        confirm();

        assertThat(transport.sent).singleElement().satisfies(s -> assertThat(s.call().getTargetInstanceId()).isEqualTo("scene-4"));
        assertThat(confirms()).containsOnly(entry("rerouted", 1));
    }

    @Test
    void 端口取边界值65535照发_65536回落() {
        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", 65_535));
        confirm();
        assertThat(transport.last().endpoint().port()).isEqualTo(65_535);
        assertThat(confirms()).containsOnly(entry("sent", 1));

        directory.put(node(ZONE, NODE, INSTANCE, "10.0.0.3", 65_536));
        confirm();
        assertThat(confirms()).containsOnly(entry("sent", 1), entry("skipped", 1));
    }

    @Test
    void 回调里的意外不吞_定位器给出没有地址的Found_计error() {
        directory.put(node(ZONE, NODE, "scene-restarted", "10.0.0.3", 21100));
        locator.resolveTo(P, new Found(PlayerLocation.getDefaultInstance(), null));

        assertThatCode(this::confirm).doesNotThrowAnyException();

        assertThat(transport.sent).isEmpty();
        assertThat(confirms()).as("既没发成也不能悄悄消失").containsOnly(entry("error", 1));
    }

    // ================================================================== 应答与传输失败

    @Test
    void 应答NOT_HERE_在sent之外另计not_here() {
        directoryHasPreparedInstance();
        transport.respond(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_NOT_HERE));

        confirm();

        assertThat(confirms()).containsOnly(entry("sent", 1), entry("not_here", 1));
    }

    @Test
    void 传输失败_UNSPECIFIED_空应答_在sent之外另计error() {
        directoryHasPreparedInstance();

        transport.replies.failWith(new TimeoutException("调用超时"));
        confirm();
        transport.replies.replyWith(SceneBattleReply.getDefaultInstance());
        confirm();
        transport.replies.replyWith(null);
        confirm();

        assertThat(confirms()).containsOnly(entry("sent", 3), entry("error", 3));
    }

    @Test
    void 传输同步抛异常或返回空future_不外泄_计error() {
        directoryHasPreparedInstance();

        transport.replies.throwing(new IllegalStateException("客户端缓存已关闭"));
        assertThatCode(this::confirm).doesNotThrowAnyException();
        transport.replies.returnNullFuture();
        assertThatCode(this::confirm).doesNotThrowAnyException();

        assertThat(confirms()).containsOnly(entry("sent", 2), entry("error", 2));
    }

    @Test
    void 应答HANDLED_DEFERRED_OVERLOADED_只有发出时的那一次计数() {
        directoryHasPreparedInstance();

        transport.respond(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_HANDLED));
        confirm();
        transport.respond(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_DEFERRED));
        confirm();
        transport.respond(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_OVERLOADED));
        confirm();

        assertThat(confirms()).containsOnly(entry("sent", 3));
    }

    @Test
    void 改投之后的应答同样计_rerouted加not_here或error() {
        locator.online(P, ZONE, 4, "scene-4");
        transport.respond(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_NOT_HERE));
        confirm();
        transport.replies.failWith(new TimeoutException("调用超时"));
        confirm();

        assertThat(confirms()).containsOnly(entry("rerouted", 2), entry("not_here", 1), entry("error", 1));
    }
}
