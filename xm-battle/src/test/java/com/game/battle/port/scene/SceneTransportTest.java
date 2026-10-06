package com.game.battle.port.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.SceneNodeInfo;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.outbox.OutboxMetrics;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.proto.PlayerLocation;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RMapCache;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link SceneTransport} 的装配（审计 OBX-11 的接线部分；内部不可注入，这里用桩掉的 Redisson + 真的直连客户端缓存间接验证）：
 * 每次发调用都把目标节点登记给清扫（确认、结算投递两条路各有用例——它们是两条各自的 lambda）；清扫按 zone 读目录，节点不在了就把它的
 * 客户端从缓存里<b>真的</b>销毁，读目录失败时不动；关闭传输时停掉清扫线程。scene 一侧没有人监听（端口是刚关掉的本机端口），调用本身按传输失败计，不影响这里要看的缓存与登记。
 *
 * <p><b>停机次序</b>（§13.5「停机时等在途落库与首投」的装配层；评审 R63B-1）：{@link SceneTransport#close} 先按
 * {@code xm.battle.outbox-drain-timeout} 有界排空结算发件箱、后停 {@code battle-outbox}。脚本调用桩成<b>悬着的</b> future（落库发出去了、回复没到），
 * 由测试决定它什么时候回来：排空不等、先停线程后排空、整段删掉排空、不按配置取上限，这几种接线错误各有断言拦着。发件箱自己的排空语义在
 * {@code SettlementOutboxTest} 的「停机」一组。
 */
class SceneTransportTest {

    private static final int ZONE = 1;
    private static final int NODE = 3;
    private static final String INSTANCE = "scene-a";
    private static final long PLAYER = 9_001;
    private static final long BATTLE = 77_001;

    /** 一次脚本调用：KEYS 与还没到的回复（由测试完成）。 */
    private record ScriptCall(List<Object> keys, CompletableFuture<Object> reply) {
    }

    private final RedissonClient redis = mock(RedissonClient.class);
    /** 经 {@code BattleRedis} 发出的脚本调用（按发起顺序）；回复一律悬着。 */
    private final List<ScriptCall> scriptCalls = new CopyOnWriteArrayList<>();
    /** 重新装配的传输把结算发件箱的计数记在这里（缺省装配的那一个不计）。 */
    private final SimpleMeterRegistry outboxRegistry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    private final RMapCache<String, byte[]> sceneDirectory = mock(RMapCache.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private volatile Collection<byte[]> listing = List.of();
    private volatile RuntimeException listingFailure;
    private SceneNodeInfo node;
    private SceneTransport transport;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void assemble() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        node = SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(NODE).setInstanceId(INSTANCE).setRpcHost("127.0.0.1")
                .setRpcPort(port).build();
        when(redis.getMapCache(eq(RedisKeys.nodeDirectory(NodeTypes.SCENE, ZONE)), any(Codec.class))).thenReturn((RMapCache) sceneDirectory);
        when(sceneDirectory.getAsync(Integer.toString(NODE))).thenAnswer(call -> new CompletableFutureWrapper<>(node.toByteArray()));
        when(sceneDirectory.readAllValues()).thenAnswer(call -> {
            RuntimeException failure = listingFailure;
            if (failure != null) {
                throw failure;
            }
            return listing;
        });
        RScript scripts = mock(RScript.class, call -> {
            if (!"evalAsync".equals(call.getMethod().getName())) {
                return Mockito.RETURNS_DEFAULTS.answer(call);
            }
            // BattleRedis：evalAsync(mode, script, returnType, keys, args...)
            CompletableFuture<Object> reply = new CompletableFuture<>();
            scriptCalls.add(new ScriptCall(List.copyOf(call.<List<Object>>getArgument(3)), reply));
            return new CompletableFutureWrapper<>(reply);
        });
        when(redis.getScript(any(Codec.class))).thenReturn(scripts);
        transport = new SceneTransport(redis, new BattleMetrics(registry), OutboxMetrics.noop(), SceneTransportProperties.defaults(),
                (event, channel) -> { });
    }

    /** 换一组配置重新装配（先关掉缺省装配的那一个）；结算发件箱的计数进 {@link #outboxRegistry}。 */
    private void reassemble(SceneTransportProperties props) {
        transport.close();
        transport = new SceneTransport(redis, new BattleMetrics(registry), new OutboxMetrics(outboxRegistry), props,
                (event, channel) -> { });
    }

    /** 房间交出一份结算，并等到发件箱线程把落库脚本发出去（回复悬着）。 */
    private ScriptCall dispatchSettlementAndAwaitStore() {
        transport.settlementSink().dispatch(
                BattleRouting.newBuilder().setZoneId(ZONE).setSceneNodeId(NODE).setSceneInstanceId(INSTANCE).build(), PLAYER,
                BattleSettlementData.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).setGoldGain(10).build());
        await().atMost(Duration.ofSeconds(5)).until(() -> scriptCalls.size() == 1);
        ScriptCall store = scriptCalls.get(0);
        assertThat(store.keys()).as("落库脚本的两个键：待结算记录 + 这一局的已销账墓碑")
                .containsExactly(RedisKeys.battleSettlements(PLAYER), RedisKeys.battleSettled(PLAYER, BATTLE));
        assertThat(transport.settlements().inFlight()).isEqualTo(1);
        return store;
    }

    private double outboxEvents(String event) {
        return outboxRegistry.get("xm.battle.settlement.outbox").tag("event", event).counter().count();
    }

    @AfterEach
    void close() {
        transport.close();
    }

    private void confirm() {
        transport.sceneEvents().confirm(BattleRouting.newBuilder().setZoneId(ZONE).setSceneNodeId(NODE).setSceneInstanceId(INSTANCE)
                .build(), 9_001, 77_001, 1_800_000_192_000L);
    }

    private double confirms(String result) {
        return registry.get("xm.battle.scene.events").tag("kind", "confirm").tag("result", result).counter().count();
    }

    @Test
    void 发过调用的节点被登记_节点还在目录里或读目录失败时清扫都不动它() {
        assertThat(transport.cachedClients()).isZero();
        assertThat(transport.sweeper().trackedCount()).isZero();
        assertThat(transport.sweeper().running()).as("装配时就起清扫线程").isTrue();

        confirm();
        confirm();

        assertThat(confirms("sent")).as("目录实例相符，按快照路由直发").isEqualTo(2);
        assertThat(transport.cachedClients()).as("同一地址只建一个客户端").isEqualTo(1);
        assertThat(transport.sweeper().trackedCount()).isEqualTo(1);

        listing = List.of(node.toByteArray());
        assertThat(transport.sweeper().sweep()).as("节点还在").isZero();
        assertThat(transport.cachedClients()).isEqualTo(1);

        listingFailure = new IllegalStateException("Redis 不可用（测试）");
        assertThat(transport.sweeper().sweep()).as("读目录失败").isZero();
        assertThat(transport.cachedClients()).as("拿不到完整目录就不动，免得误毁仍在用的引用").isEqualTo(1);
        assertThat(transport.sweeper().trackedCount()).isEqualTo(1);
    }

    @Test
    void 节点换了实例或从目录消失_清扫把它的直连客户端从缓存里销毁() {
        confirm();
        assertThat(transport.cachedClients()).isEqualTo(1);
        listing = List.of(node.toBuilder().setInstanceId("scene-b").build().toByteArray());

        assertThat(transport.sweeper().sweep()).as("同地址换了实例").isEqualTo(1);
        assertThat(transport.cachedClients()).as("客户端真的被销毁，不再对没人监听的地址重连").isZero();
        assertThat(transport.sweeper().trackedCount()).isZero();

        confirm();
        assertThat(transport.cachedClients()).as("再发调用就重新建、重新登记").isEqualTo(1);
        assertThat(transport.sweeper().trackedCount()).isEqualTo(1);
        listing = List.of();

        assertThat(transport.sweeper().sweep()).as("节点从目录消失").isEqualTo(1);
        assertThat(transport.cachedClients()).isZero();
        assertThat(transport.sweeper().sweep()).as("扫过的不重复销毁").isZero();
    }

    @Test
    void 关闭传输_停掉清扫线程_重复关闭无害_之后的确认按传输失败计() {
        assertThat(transport.sweeper().running()).isTrue();

        transport.close();

        assertThat(transport.sweeper().running()).isFalse();
        assertThatCode(transport::close).doesNotThrowAnyException();
        assertThatCode(this::confirm).as("关闭之后的确认不抛异常").doesNotThrowAnyException();
        assertThat(confirms("sent")).isEqualTo(1);
        assertThat(confirms("error")).as("客户端缓存已关闭 = 传输失败").isEqualTo(1);
        assertThat(transport.cachedClients()).as("关闭之后不再建客户端").isZero();
    }

    @Test
    void 结算投递同样把目标节点登记给清扫_节点从目录消失后清扫销毁它的直连客户端() {
        // 评审 R63C-3：确认与结算是两条各自的 lambda，都得经 SceneTransport.call 登记。只收过结算重投的节点（玩家离线后在别的节点上线，
        // 重投按新位置定位）若不登记，它的直连客户端就永远扫不到。本用例全程不发确认，登记只可能来自结算投递
        reassemble(SceneTransportProperties.defaults());
        assertThat(transport.cachedClients()).isZero();
        assertThat(transport.sweeper().trackedCount()).isZero();
        ScriptCall store = dispatchSettlementAndAwaitStore();

        store.reply().complete(1L);
        await().atMost(Duration.ofSeconds(5)).until(() -> scriptCalls.size() == 2);
        ScriptCall locate = scriptCalls.get(1);
        assertThat(locate.keys()).as("首投的定位：读这名玩家的位置记录").containsExactly(RedisKeys.playerLocation(PLAYER));
        assertThat(transport.sweeper().trackedCount()).as("还没投递，没有登记").isZero();
        // 在线位置记录：玩家由 (zone, node) 持有；目录里这个节点号就是 scene-a（装配时桩好的 getAsync）
        locate.reply().complete(Arrays.asList("o".getBytes(StandardCharsets.US_ASCII),
                PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(ZONE).setSceneNodeId(NODE).build().toByteArray()));

        // delivered 在发调用之前计、登记在发调用之后做，所以等的是登记本身
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(transport.sweeper().trackedCount())
                .as("结算投递的目标节点登记给了清扫").isEqualTo(1));
        assertThat(outboxEvents("delivered")).as("按定位到的节点发出了 applySettlement").isEqualTo(1);
        assertThat(outboxEvents("locate_error")).isZero();
        assertThat(confirms("sent")).as("没有发过确认").isZero();
        assertThat(transport.cachedClients()).as("为它建了直连客户端").isEqualTo(1);

        listing = List.of(node.toByteArray());
        assertThat(transport.sweeper().sweep()).as("节点还在").isZero();
        assertThat(transport.cachedClients()).isEqualTo(1);
        listing = List.of();

        assertThat(transport.sweeper().sweep()).as("节点从目录消失").isEqualTo(1);
        assertThat(transport.cachedClients()).as("只收过结算的节点，它的客户端同样被销毁").isZero();
        assertThat(transport.sweeper().trackedCount()).isZero();
    }

    /** 在另一条线程上关闭传输；返回的 future 在 {@code close()} 返回时完成。 */
    private CompletableFuture<Void> closeOnAnotherThread() {
        CompletableFuture<Void> closing = new CompletableFuture<>();
        SceneTransport closed = transport;
        Thread.ofPlatform().name("test-transport-closer").daemon(true).start(() -> {
            try {
                closed.close();
                closing.complete(null);
            } catch (Throwable t) {
                closing.completeExceptionally(t);
            }
        });
        return closing;
    }

    @Test
    void 关闭传输_先等在途的落库与首投都回来_它们的结局在发件箱线程还活着时处理完_然后才停线程() {
        reassemble(new SceneTransportProperties(null, Duration.ofSeconds(15)));
        ScriptCall store = dispatchSettlementAndAwaitStore();
        CompletableFuture<Void> closing = closeOnAnotherThread();

        assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS)).as("落库的回复还没到：关闭在有界排空里等它，没有直接往下关")
                .isInstanceOf(TimeoutException.class);
        assertThat(transport.sweeper().running()).as("排空是第一步，还没轮到停清扫线程").isTrue();
        assertThat(outboxEvents("stored")).isZero();
        assertThat(transport.settlements().inFlight()).isEqualTo(1);

        store.reply().complete(1L);

        // 落库的结局交回了还活着的发件箱线程（先排空、后停线程；反过来这份结局会被丢弃）：计 stored，并发起首投的定位——读位置记录的脚本，同样悬着
        await().atMost(Duration.ofSeconds(5)).until(() -> scriptCalls.size() == 2);
        ScriptCall locate = scriptCalls.get(1);
        assertThat(locate.keys()).as("首投的定位：读这名玩家的位置记录").containsExactly(RedisKeys.playerLocation(PLAYER));
        assertThat(outboxEvents("stored")).isEqualTo(1);
        assertThatThrownBy(() -> closing.get(200, TimeUnit.MILLISECONDS)).as("首投还在途（在途 = 落库 + 首投）：继续等")
                .isInstanceOf(TimeoutException.class);
        assertThat(transport.settlements().inFlight()).isEqualTo(1);

        // 位置记录不存在：玩家离线，首投这次不投（离线玩家的常态）
        locate.reply().complete(Arrays.asList(null, null));

        assertThatCode(() -> closing.get(5, TimeUnit.SECONDS)).as("在途的一回来就往下关，不等满 15 s").doesNotThrowAnyException();
        assertThat(transport.settlements().inFlight()).as("在途归零才放行").isZero();
        assertThat(outboxEvents("stored")).isEqualTo(1);
        assertThat(outboxEvents("not_durable")).isZero();
        assertThat(outboxEvents("locate_error")).as("离线不是定位故障").isZero();
        assertThat(outboxEvents("delivered")).isZero();
        assertThat(transport.sweeper().running()).isFalse();
        assertThat(scriptCalls).as("关闭之后没有再发脚本（没有探测、没有重投）").hasSize(2);
    }

    @Test
    void 排空上限配成0_关闭不等在途的落库_迟到的结局被丢弃不抛异常() {
        reassemble(new SceneTransportProperties(null, Duration.ZERO));
        ScriptCall store = dispatchSettlementAndAwaitStore();

        long started = System.nanoTime();
        transport.close();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(tookMs).as("排空上限取自 xm.battle.outbox-drain-timeout：0 = 停机不等（没有按缺省的 3 s 等）").isLessThan(2_000);
        assertThat(transport.settlements().inFlight()).as("那份落库没等到").isEqualTo(1);
        assertThat(transport.sweeper().running()).isFalse();

        assertThatCode(() -> store.reply().complete(1L)).as("发件箱线程已停，迟到的结局只是丢弃").doesNotThrowAnyException();
        assertThat(outboxEvents("stored")).isZero();
        assertThat(outboxEvents("not_durable")).isZero();
        assertThat(scriptCalls).as("没有人再去定位").hasSize(1);
    }
}
