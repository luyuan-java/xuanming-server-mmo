package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SettlementDisposition;
import com.game.battle.outbox.OutboxMetrics.Delivery;
import com.game.battle.outbox.OutboxMetrics.SettlementEvent;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.testing.CallJournal;
import com.game.battle.testing.FakeSceneLocator;
import com.game.battle.testing.FakeSettlementStore;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.RecordingSceneTransport;
import com.game.battle.testing.RecordingSceneTransport.Sent;
import com.game.battle.testing.Scripted;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * battle 侧结算发件箱（scene-battle-spec §7.15、§13.5；审计 OBX-1 及 OBX-7 / 8 / 9 / 10 的回归）：手动调度器（虚拟时间）+ 带内存模型的假 Redis 端口 +
 * 假定位器 + 记录型传输，四者共用一份带全局序号的调用记录（{@link CallJournal}），按序号断言先后。
 *
 * <p>§13.5 表里「{@code DEV} 房间不调端口、{@code DEV_GATHER} 照调」在房间层：{@code room.ResultRoutingTest} 的
 * {@code dev房间照常推150_但永不投递结算与结果事件} 与 {@code dev_gather房间照常确认照常结算_但不投递结果事件}（发件箱自己不知道房间来源）。
 *
 * <p><b>OBX-7（孤儿记录）在这里只有发件箱一侧的两条回归</b>：{@code 落库时这一局已销账_脚本回负1_…} 与 {@code 一局走完再重入_…}，钉的是
 * 「落库回 {@code -1} → 计 {@code already_settled}、不登记、不投递」。「销账之后才落地的 {@code STORE_SETTLEMENT} 写不进去」是
 * {@code BattleRedis} 里 {@code STORE_SETTLEMENT} / {@code ACK} 两段 Lua 的性质，假 Redis 端口只是照着它手写的模型（模型自测见
 * {@code FakeSettlementStoreTest}），所以本类不为它设用例；它的证据只在真 Redis 上：{@code OutboxRedisIntegrationTest} 的
 * {@code 落库结局不明_投一次后scene销账_那条落库这时才落地_被墓碑挡下_没有孤儿记录} 与 xm-discovery 的 {@code BattleRedisScriptsIntegrationTest}
 * ——两者都要 {@code -Dxm.it.redis=…}，缺省的 {@code ./mvnw test} 跳过。
 *
 * <p><b>线程所有权</b>（D25：名单、计次、定时器只在 {@code battle-outbox} 上读写，Redisson / Dubbo 的回调投递回它）：悬着的往返一律经
 * {@link #handBack} 放行——它断言放行那一刻什么都没生效、结局只是在发件箱线程上排了一个任务；另有一条真线程用例按线程名断言各端口调用
 * 都发生在发件箱线程上（评审 R63B-3）。投递应答是例外：只计数、可以在任意线程上（D16），不经 {@code handBack}。
 */
class SettlementOutboxTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long P = 9_001;
    private static final long B = 77_001;
    private static final int ZONE = 1;
    private static final long ROUND_MS = BattleRedis.SETTLEMENT_RETRY_INTERVAL.toMillis();

    private final ManualBattleScheduler scheduler = new ManualBattleScheduler(T0);
    private final CallJournal journal = new CallJournal();
    private final FakeSettlementStore store = new FakeSettlementStore(journal);
    private final FakeSceneLocator locator = new FakeSceneLocator(journal);
    private final RecordingSceneTransport transport = new RecordingSceneTransport(journal);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);
    private final SettlementOutbox outbox = new SettlementOutbox(scheduler, store, locator::locate, transport::applySettlement, metrics,
            scheduler::nowMs);

    // ------------------------------------------------------------------ 夹具

    /** 快照路由：开局时的 scene 节点（与之后定位到的都不同——它只进日志，不决定投递目标）。 */
    private static BattleRouting routing() {
        return BattleRouting.newBuilder().setZoneId(ZONE).setSceneNodeId(9).setSceneInstanceId("scene-at-start").setGateNodeId(2)
                .setGateInstanceId("gate-1").setSessionId(77).build();
    }

    private static BattleSettlementData settlement(long playerId, long battleId) {
        return BattleSettlementData.newBuilder().setBattleId(battleId).setPlayerId(playerId).setGoldGain(100).setExpGain(7)
                .setTotalRounds(3).build();
    }

    /** 落库 / 投递的字节：{@code BattleSettlementEvent{settlement}}。 */
    private static byte[] wire(long playerId, long battleId) {
        return BattleSettlementEvent.newBuilder().setSettlement(settlement(playerId, battleId)).build().toByteArray();
    }

    private static String key(long playerId, long battleId) {
        return FakeSettlementStore.key(playerId, battleId);
    }

    /** 房间调一次结算端口，并跑完发件箱线程上排着的任务（悬着的假端口调用除外）。 */
    private void dispatch(long playerId, long battleId) {
        outbox.dispatch(routing(), playerId, settlement(playerId, battleId));
        scheduler.runPending();
    }

    /** 过一轮重投间隔（10 s）。 */
    private void round() {
        scheduler.advance(ROUND_MS);
    }

    private void rounds(int n) {
        for (int i = 0; i < n; i++) {
            round();
        }
    }

    /** 计过数的发件箱事件（取值 → 次数；没计过的不出现），用来断言「只计了这些」。 */
    private Map<String, Integer> events() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (SettlementEvent event : SettlementEvent.values()) {
            String label = event.name().toLowerCase(Locale.ROOT);
            int count = (int) registry.get(OutboxMetrics.SETTLEMENT_OUTBOX).tag("event", label).counter().count();
            if (count != 0) {
                out.put(label, count);
            }
        }
        return out;
    }

    /** 计过数的投递应答（取值 → 次数）。 */
    private Map<String, Integer> deliveries() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Delivery result : Delivery.values()) {
            String label = result.name().toLowerCase(Locale.ROOT);
            int count = (int) registry.get(OutboxMetrics.SETTLEMENT_DELIVERY).tag("result", label).counter().count();
            if (count != 0) {
                out.put(label, count);
            }
        }
        return out;
    }

    private double entriesGauge() {
        return registry.get(OutboxMetrics.SETTLEMENT_OUTBOX_ENTRIES).gauge().value();
    }

    private List<Integer> attemptsSent() {
        return transport.sent.stream().map(s -> s.call().getAttempt()).toList();
    }

    /** 被测类发起的端口调用（按发生顺序；不含假件自己记的「落库结局交回」）。 */
    private List<String> portCalls() {
        return journal.entries().stream().filter(e -> !e.startsWith("store-done:")).toList();
    }

    /**
     * 放行一次悬着的往返（落库 / 探测 / 定位 / 已取代判定）并钉住线程所有权（D25）：它的结局<b>只是在发件箱线程上排了一个任务</b>——放行的那一刻
     * （完成 future 的线程，生产上是 Redisson 的回调线程）端口调用、名单与计次、计数、定时器都没动，跑到那个任务才生效。把任何一跳从
     * 「投递回发件箱线程」改成在回调线程上直接处理，都会在这里失败。返回放行交回的值；返回时那个任务（及它引出的后续任务）已跑完。
     */
    private <T> T handBack(Scripted.Pending<T> pending) {
        assertThat(scheduler.pendingTasks()).as("放行之前发件箱线程上没有排着的任务").isZero();
        List<String> calls = portCalls();
        List<String> registered = outbox.describe();
        Map<String, Integer> counted = events();
        Map<String, Integer> answered = deliveries();
        int timers = scheduler.scheduledCount();

        T value = pending.release();

        assertThat(scheduler.pendingTasks()).as("结局交回发件箱线程：排了恰好一个任务（%s）", pending.label()).isEqualTo(1);
        assertThat(portCalls()).as("回调线程上不碰任何端口（%s）", pending.label()).isEqualTo(calls);
        assertThat(outbox.describe()).as("回调线程上不改名单与计次（%s）", pending.label()).isEqualTo(registered);
        assertThat(events()).as("回调线程上不计发件箱事件（%s）", pending.label()).isEqualTo(counted);
        assertThat(deliveries()).isEqualTo(answered);
        assertThat(scheduler.scheduledCount()).as("回调线程上不开表不停表（%s）", pending.label()).isEqualTo(timers);
        scheduler.runPending();
        return value;
    }

    /** 真线程用例：等到第 {@code n} 次悬着的调用出现（它是在发件箱线程上发起的）。 */
    private static <T> Scripted.Pending<T> awaitHeld(Scripted<T> scripted, int n) {
        await().atMost(Duration.ofSeconds(10)).until(() -> scripted.pending.size() >= n);
        return scripted.pending.get(n - 1);
    }

    // ================================================================== 落库与首投

    @Test
    void dispatch只把任务交给发件箱线程_调用线程上不碰任何端口() {
        locator.online(P, ZONE, 3, "scene-3");

        outbox.dispatch(routing(), P, settlement(P, B));

        assertThat(journal.entries()).as("房间所在的逻辑线程上零 I/O").isEmpty();
        assertThat(scheduler.pendingTasks()).isEqualTo(1);
        assertThat(outbox.inFlight()).as("在途的落库 + 首投").isEqualTo(1);
    }

    @Test
    void 空的结算在入口挡住_不抛出_不占在途计数_不交给发件箱线程() {
        locator.online(P, ZONE, 3, "scene-3");

        outbox.dispatch(routing(), P, null);

        assertThat(scheduler.pendingTasks()).as("没有任务交给发件箱线程（否则空指针抛在那条线程上）").isZero();
        assertThat(outbox.inFlight()).as("在途计数不动：停机排空不会为它白等").isZero();
        assertThat(journal.entries()).isEmpty();
    }

    @Test
    void 投递一定排在落库结局之后_落库没回来时零定位零投递不登记() {
        locator.online(P, ZONE, 3, "scene-3");
        store.stores.hold();

        dispatch(P, B);

        assertThat(journal.entries()).containsExactly("store:" + key(P, B));
        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(outbox.inFlight()).isEqualTo(1);

        assertThat(handBack(store.stores.last())).as("落库后的字段数").isEqualTo(1L);

        assertThat(journal.entries()).as("全局序号：落库发起 → 落库结局 → 定位 → 投递").containsExactly(
                "store:" + key(P, B), "store-done:" + key(P, B) + "=1", "locate:" + P, "deliver:" + key(P, B) + "@scene-3#0");
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#0");
        assertThat(scheduler.scheduledCount()).as("第一条入队时开表").isEqualTo(1);
        assertThat(outbox.inFlight()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1));
        assertThat(deliveries()).containsOnly(entry("applied", 1));
        assertThat(entriesGauge()).isEqualTo(1);
    }

    @Test
    void 首投_发往定位到的节点与实例_不是快照路由_attempt为0_body与落库字节相同() {
        SceneAssetEndpoint located = locator.online(P, ZONE, 3, "scene-3");

        dispatch(P, B);

        Sent sent = transport.sent.get(0);
        assertThat(sent.method()).isEqualTo(RecordingSceneTransport.APPLY_SETTLEMENT);
        assertThat(sent.endpoint()).isEqualTo(located);
        SceneBattleCall call = sent.call();
        assertThat(call.getTargetInstanceId()).as("目录里的实例，不是快照路由的 scene-at-start").isEqualTo("scene-3");
        assertThat(call.getPlayerId()).isEqualTo(P);
        assertThat(call.getAttempt()).isZero();
        assertThat(call.getBody().toByteArray()).isEqualTo(wire(P, B)).isEqualTo(store.offered(P, B)).isEqualTo(store.record(P, B));
    }

    @Test
    void 落库失败_只投一次_不登记_计not_durable_之后没有任何重投与探测() {
        locator.online(P, ZONE, 3, "scene-3");
        store.stores.failWith(new TimeoutException("Redis 响应超时"));

        dispatch(P, B);

        assertThat(journal.entries()).as("投递仍排在落库的结局（明确降级）之后").containsExactly(
                "store:" + key(P, B), "store-done:" + key(P, B) + "=error", "locate:" + P, "deliver:" + key(P, B) + "@scene-3#0");
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).as("没登记就不开表").isZero();
        assertThat(outbox.inFlight()).isZero();
        assertThat(events()).containsOnly(entry("not_durable", 1), entry("delivered", 1));
        assertThat(entriesGauge()).isZero();

        scheduler.advance(10 * ROUND_MS);
        assertThat(transport.sent).as("只投一次").hasSize(1);
        assertThat(journal.count("exists:")).isZero();
    }

    @Test
    void Redis不可用_落库端口同步抛异常_同样只投一次不登记() {
        locator.online(P, ZONE, 3, "scene-3");
        store.stores.throwing(new IllegalStateException("Redis 不可用"));

        dispatch(P, B);

        assertThat(attemptsSent()).containsExactly(0);
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(outbox.inFlight()).isZero();
        assertThat(events()).containsOnly(entry("not_durable", 1), entry("delivered", 1));
    }

    @Test
    void 落库失败且定位不到_什么都发不出去() {
        store.stores.failWith(new TimeoutException("Redis 响应超时"));

        dispatch(P, B);
        assertThat(events()).as("NoHolder：离线").containsOnly(entry("not_durable", 1));

        locator.broken(P + 1, "读位置记录失败");
        dispatch(P + 1, B);
        assertThat(events()).as("定位出错").containsOnly(entry("not_durable", 2), entry("locate_error", 1));

        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isZero();
        assertThat(outbox.inFlight()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 落库没有结论_空结果或空future_不当落库成功_按not_durable只投一次() {
        locator.online(P, ZONE, 3, "scene-3");

        store.stores.replyWith(null);
        dispatch(P, B);
        store.stores.returnNullFuture();
        dispatch(P, B + 1);

        assertThat(events()).containsOnly(entry("not_durable", 2), entry("delivered", 2));
        assertThat(outbox.size()).isZero();
        assertThat(outbox.inFlight()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 序列化失败_什么都不发_不落库不定位不投递() {
        locator.online(P, ZONE, 3, "scene-3");
        BattleSettlementData broken = mock(BattleSettlementData.class);
        when(broken.getBattleId()).thenReturn(B);
        when(broken.getSerializedSize()).thenThrow(new IllegalStateException("序列化失败（测试）"));

        outbox.dispatch(routing(), P, broken);
        scheduler.runPending();

        assertThat(journal.entries()).isEmpty();
        assertThat(events()).containsOnly(entry("serialize_failed", 1));
        assertThat(outbox.size()).isZero();
        assertThat(outbox.inFlight()).as("在途计数要归还，否则停机排空会白等").isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 落库时这一局已销账_脚本回负1_计already_settled_不登记不投递不开表() {
        locator.online(P, ZONE, 3, "scene-3");
        store.sceneAck(P, B);

        dispatch(P, B);

        assertThat(journal.entries()).containsExactly("store:" + key(P, B), "store-done:" + key(P, B) + "=-1");
        assertThat(events()).as("不计 stored、不计 acked").containsOnly(entry("already_settled", 1));
        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isZero();
        assertThat(outbox.inFlight()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(store.hasRecord(P, B)).as("记录不复活").isFalse();
    }

    @Test
    void 一局走完再重入_落库_投递_销账_摘除_重放的落库被墓碑挡下不再发奖() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.sceneAck(P, B);
        round();
        assertThat(outbox.size()).isZero();

        dispatch(P, B);

        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("acked", 1), entry("already_settled", 1));
        assertThat(transport.sent).as("第二次没有投递").hasSize(1);
        assertThat(store.hasRecord(P, B)).isFalse();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 字段数超过告警阈值_计fields_overflow_照常登记投递_恰好等于阈值不计() {
        locator.online(P, ZONE, 3, "scene-3");
        for (int i = 1; i < BattleRedis.SETTLEMENT_FIELDS_WARN; i++) {
            store.applyStore(P, B + 1000 + i, new byte[] {1});
        }

        dispatch(P, B);
        assertThat(store.fieldCount(P)).isEqualTo(16);
        assertThat(events()).as("16 个字段：不超").containsOnly(entry("stored", 1), entry("delivered", 1));

        dispatch(P, B + 1);
        assertThat(store.fieldCount(P)).isEqualTo(17);
        assertThat(events()).as("17 个字段：超了，stored 之外另计").containsOnly(entry("stored", 2), entry("delivered", 2),
                entry("fields_overflow", 1));
        assertThat(outbox.size()).isEqualTo(2);
        assertThat(attemptsSent()).containsExactly(0, 0);
    }

    @Test
    void 首投解析不到_NoHolder_不投但登记_在途归零_不计locate_error() {
        locator.offline(P, ResolveResult.LEASE);

        dispatch(P, B);

        assertThat(journal.entries()).containsExactly("store:" + key(P, B), "store-done:" + key(P, B) + "=1", "locate:" + P);
        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 0));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(outbox.inFlight()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1));
    }

    @Test
    void 首投定位出错_故障_异常完成_同步抛出_空结果_空future_都不投但登记_计locate_error() {
        locator.broken(P, "读 scene 节点目录失败");
        dispatch(P, B);
        locator.online(P, ZONE, 3, "scene-3");
        locator.locates.failWith(new IllegalStateException("定位出错"));
        dispatch(P, B + 1);
        locator.locates.throwing(new IllegalStateException("定位端口抛异常"));
        dispatch(P, B + 2);
        locator.locates.replyWith(null);
        dispatch(P, B + 3);
        locator.locates.returnNullFuture();
        dispatch(P, B + 4);

        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isEqualTo(5);
        assertThat(outbox.attemptsByBattle().values()).as("首投不计次").containsOnly(0);
        assertThat(outbox.inFlight()).isZero();
        assertThat(events()).containsOnly(entry("stored", 5), entry("locate_error", 5));
        assertThat(scheduler.scheduledCount()).as("五条共用一个表").isEqualTo(1);
    }

    @Test
    void 首投的应答没回来之前算在途_回来才归零() {
        locator.online(P, ZONE, 3, "scene-3");
        transport.replies.hold();

        dispatch(P, B);
        assertThat(outbox.inFlight()).isEqualTo(1);
        assertThat(outbox.size()).as("先登记、后首投").isEqualTo(1);

        transport.replies.last().release();
        assertThat(outbox.inFlight()).isZero();
        assertThat(deliveries()).containsOnly(entry("applied", 1));
    }

    @Test
    void 传输同步抛异常或返回空future_按传输失败计_在途归零_条目照常登记() {
        locator.online(P, ZONE, 3, "scene-3");

        transport.replies.throwing(new IllegalStateException("客户端缓存已关闭"));
        dispatch(P, B);
        transport.replies.returnNullFuture();
        dispatch(P, B + 1);

        assertThat(deliveries()).containsOnly(entry("transport_error", 2));
        assertThat(events()).containsOnly(entry("stored", 2), entry("delivered", 2));
        assertThat(outbox.inFlight()).isZero();
        assertThat(outbox.size()).isEqualTo(2);
    }

    // ================================================================== 每 10 s 一轮

    @Test
    void 探测_记录不在_摘除计acked_本轮不定位不重投_名单空了当场停表() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.sceneAck(P, B);
        journal.clear();

        round();

        assertThat(journal.entries()).containsExactly("exists:" + key(P, B));
        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("acked", 1));
        assertThat(scheduler.scheduledCount()).as("销账回调跑完就停表，不多空转一轮（OBX-8）").isZero();
        assertThat(entriesGauge()).isZero();

        round();
        assertThat(journal.entries()).as("表已停，没有下一轮").containsExactly("exists:" + key(P, B));
    }

    @Test
    void 探测出错_本轮跳过_不计次_不定位_条目保留_恢复后从attempt1接着重投() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.probes.failWith(new IllegalStateException("READONLY You can't write against a read only replica"));
        journal.clear();

        rounds(3);

        assertThat(journal.entries()).containsExactly("exists:" + key(P, B), "exists:" + key(P, B), "exists:" + key(P, B));
        assertThat(outbox.attemptsByBattle()).as("出错不计次（D15）").containsOnly(entry(B, 0));
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("probe_error", 3));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        store.probes.auto();
        round();
        assertThat(attemptsSent()).containsExactly(0, 1);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 1));
    }

    @Test
    void 探测没有结论_空结果_同步抛出_空future_都归入probe_error_不当已销账() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);

        store.probes.replyWith(null);
        round();
        assertThat(outbox.size()).as("null 不是「不在」（OBX-9）").isEqualTo(1);
        assertThat(events()).doesNotContainKey("acked").containsEntry("probe_error", 1);

        store.probes.throwing(new IllegalStateException("探测端口抛异常"));
        round();
        store.probes.returnNullFuture();
        round();

        assertThat(outbox.size()).isEqualTo(1);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 0));
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("probe_error", 3));
        assertThat(transport.sent).as("没有结论的轮不重投").hasSize(1);
    }

    @Test
    void 定位出错_本轮跳过_不计次_恢复后照常重投() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);

        locator.broken(P, "读位置记录失败");
        round();
        locator.online(P, ZONE, 3, "scene-3");
        locator.locates.failWith(new IllegalStateException("定位出错"));
        round();
        locator.locates.replyWith(null);
        round();

        assertThat(outbox.attemptsByBattle()).as("定位出错不计次").containsOnly(entry(B, 0));
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("locate_error", 3));
        assertThat(transport.sent).hasSize(1);
        assertThat(journal.count("superseded?:")).as("出错不是「无目标」，不跑已取代判定").isZero();

        locator.locates.auto();
        round();
        assertThat(attemptsSent()).containsExactly(0, 1);
    }

    @Test
    void Found_按重新定位的节点与实例重投_attempt递增_body仍是落库的原字节() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        SceneAssetEndpoint moved = locator.online(P, ZONE, 4, "scene-4");
        journal.clear();

        round();

        assertThat(journal.entries()).containsExactly("exists:" + key(P, B), "locate:" + P, "deliver:" + key(P, B) + "@scene-4#1");
        Sent resent = transport.last();
        assertThat(resent.endpoint()).as("绝不复用上一次的目标").isEqualTo(moved);
        assertThat(resent.call().getTargetInstanceId()).isEqualTo("scene-4");
        assertThat(resent.call().getPlayerId()).isEqualTo(P);
        assertThat(resent.call().getAttempt()).isEqualTo(1);
        assertThat(resent.call().getBody().toByteArray()).isEqualTo(wire(P, B));
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#1");
        assertThat(events()).as("一次重投同时计 resend 与 delivered（delivered = 发出次数）")
                .containsOnly(entry("stored", 1), entry("delivered", 2), entry("resend", 1));

        round();
        assertThat(attemptsSent()).containsExactly(0, 1, 2);
    }

    @Test
    void NoHolder_计skip并计次_另跑已取代判定_返回2_摘除计superseded_当场停表() {
        dispatch(P, B);
        store.lock(P, B + 1);
        journal.clear();

        round();

        assertThat(journal.entries()).containsExactly("exists:" + key(P, B), "locate:" + P, "superseded?:" + key(P, B));
        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 1), entry("superseded", 1));
        assertThat(store.hasRecord(P, B)).as("脚本删了记录").isFalse();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void NoHolder_已取代判定返回3_记录在两跳之间被销账_摘除计acked() {
        dispatch(P, B);
        store.supersedes.hold();
        round();
        assertThat(outbox.size()).isEqualTo(1);

        store.sceneAck(P, B);
        assertThat(handBack(store.supersedes.last())).isEqualTo(3L);

        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 1), entry("acked", 1));
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void NoHolder_已取代判定返回0或1_条目保留_跳过的轮照样计次() {
        dispatch(P, B);

        round();
        assertThat(outbox.attemptsByBattle()).as("锁不在 → 0").containsOnly(entry(B, 1));
        store.lock(P, B);
        round();
        assertThat(outbox.attemptsByBattle()).as("锁仍是本局 → 1").containsOnly(entry(B, 2));

        assertThat(outbox.size()).isEqualTo(1);
        assertThat(journal.count("superseded?:")).isEqualTo(2);
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 2));
        assertThat(store.hasRecord(P, B)).isTrue();
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
    }

    @Test
    void NoHolder_已取代判定出错或没有结论_条目保留_出错计probe_error() {
        dispatch(P, B);

        store.supersedes.failWith(new IllegalStateException("脚本出错"));
        round();
        store.supersedes.replyWith(null);
        round();
        store.supersedes.throwing(new IllegalStateException("端口抛异常"));
        round();

        assertThat(outbox.size()).isEqualTo(1);
        assertThat(outbox.attemptsByBattle()).as("这一轮已按无目标计过次").containsOnly(entry(B, 3));
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 3), entry("probe_error", 2));
        assertThat(store.hasRecord(P, B)).isTrue();
    }

    @Test
    void 第12轮仍重投_第13轮有目标_exhausted并摘除_记录留在Redis() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);

        for (int i = 1; i <= 12; i++) {
            round();
            assertThat(transport.sent).as("第 %d 轮仍重投", i).hasSize(1 + i);
            assertThat(transport.last().call().getAttempt()).isEqualTo(i);
        }
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 12));
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 13), entry("resend", 12));
        journal.clear();

        round();

        assertThat(journal.entries()).as("第 13 轮：探测、定位，但不投").containsExactly("exists:" + key(P, B), "locate:" + P);
        assertThat(attemptsSent()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 13), entry("resend", 12), entry("exhausted", 1));
        assertThat(deliveries()).as("应用成功的应答从不让发件箱提前停（D16）").containsOnly(entry("applied", 13));
        assertThat(store.hasRecord(P, B)).as("留给 scene 的 rescue / 进场恢复").isTrue();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(scheduler.nowMs() - T0).as("入队后约 130 s").isEqualTo(13 * ROUND_MS);
    }

    @Test
    void 全程无目标_12轮跳过_第13轮_exhausted_offline并摘除_这一轮不再跑已取代判定() {
        dispatch(P, B);

        rounds(12);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 12));
        assertThat(journal.count("superseded?:")).isEqualTo(12);
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 12));

        round();

        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 12), entry("exhausted_offline", 1));
        assertThat(journal.count("superseded?:")).isEqualTo(12);
        assertThat(transport.sent).isEmpty();
        assertThat(store.hasRecord(P, B)).isTrue();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 跳过的轮与重投的轮合计12轮_第13轮按当时有没有目标分exhausted与exhausted_offline() {
        dispatch(P, B);
        rounds(5);
        locator.online(P, ZONE, 3, "scene-3");
        rounds(7);
        assertThat(attemptsSent()).as("跳过的 5 轮也计次：第一次重投就是 attempt 6").containsExactly(6, 7, 8, 9, 10, 11, 12);

        round();

        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 5), entry("resend", 7), entry("delivered", 7),
                entry("exhausted", 1));
        assertThat(outbox.size()).isZero();
    }

    // §13.1「最后一轮已销账算成功」（已销账排在用尽之前）在发件箱这一层的两条（评审 R63C-1）。生产路径上 classify 的 stillOurs 恒为 true
    // （探测那一跳已确认，同基线 room.cpp:1805），纯函数那几条 classify(false, …) 的断言管不到这里：保证这条次序的是 onProbe「先探测、不在就摘」，
    // 次数已满也一样。把它改成「次数满了就按用尽处理、不看探测结果」，下面两条都会多出定位并计成 exhausted / exhausted_offline（假告警）

    @Test
    void 跑满12轮重投之后记录恰好被销账_第13轮只探测_算acked不算exhausted() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        rounds(12);
        assertThat(outbox.describe()).as("次数已满：下一轮还在就是用尽").containsExactly(key(P, B) + "#12");
        assertThat(attemptsSent()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        store.sceneAck(P, B);
        journal.clear();

        round();

        assertThat(journal.entries()).as("第 13 轮：探测到记录不在就摘，不定位、不投").containsExactly("exists:" + key(P, B));
        assertThat(events()).as("已销账排在用尽之前：没有 exhausted（ERROR + 告警）")
                .containsOnly(entry("stored", 1), entry("delivered", 13), entry("resend", 12), entry("acked", 1));
        assertThat(transport.sent).hasSize(13);
        assertThat(outbox.size()).isZero();
        assertThat(entriesGauge()).isZero();
        assertThat(scheduler.scheduledCount()).as("名单空了当场停表").isZero();
        assertThat(scheduler.nowMs() - T0).isEqualTo(13 * ROUND_MS);
    }

    @Test
    void 全程无目标跳满12轮之后记录恰好被销账_第13轮只探测_算acked不算exhausted_offline() {
        dispatch(P, B);
        rounds(12);
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#12");
        assertThat(journal.count("superseded?:")).isEqualTo(12);
        store.sceneAck(P, B);
        journal.clear();

        round();

        assertThat(journal.entries()).as("第 13 轮：只探测，不定位、不跑已取代判定").containsExactly("exists:" + key(P, B));
        assertThat(events()).containsOnly(entry("stored", 1), entry("skip_no_target", 12), entry("acked", 1));
        assertThat(transport.sent).isEmpty();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 每轮探测都出错_从不计次_登记超过OUTBOX_MAX_AGE_按用尽摘除计expired() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.probes.failWith(new IllegalStateException("WRONGTYPE"));

        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis());
        assertThat(outbox.size()).as("恰好 10 min 还不算超龄").isEqualTo(1);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 0));
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("probe_error", 60));

        round();

        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1), entry("probe_error", 60), entry("expired", 1));
        assertThat(journal.count("exists:")).as("超龄那一轮不再探测").isEqualTo(60);
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(entriesGauge()).isZero();
    }

    @Test
    void 定位一直出错_同样在超过OUTBOX_MAX_AGE后expired() {
        locator.broken(P, "位置记录损坏");
        dispatch(P, B);

        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis() + ROUND_MS);

        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("locate_error", 61), entry("expired", 1));
        assertThat(transport.sent).isEmpty();
        assertThat(store.hasRecord(P, B)).as("记录仍在 Redis").isTrue();
    }

    @Test
    void 探测在途时下一轮不发第二次探测_不计次_回来之后照常往下走() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.probes.hold();

        rounds(3);

        assertThat(journal.count("exists:")).as("probing 防重叠").isEqualTo(1);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 0));
        assertThat(transport.sent).hasSize(1);

        assertThat(handBack(store.probes.last())).as("记录还在").isTrue();
        assertThat(attemptsSent()).containsExactly(0, 1);

        round();
        assertThat(journal.count("exists:")).as("上一轮的往返回来了，这一轮重新探测").isEqualTo(2);
    }

    @Test
    void 定位与已取代判定在途时同样不重叠() {
        dispatch(P, B);
        locator.locates.hold();
        rounds(2);
        assertThat(journal.count("exists:")).as("定位悬着").isEqualTo(1);
        assertThat(journal.count("locate:")).as("首投一次 + 本轮一次").isEqualTo(2);

        store.supersedes.hold();
        handBack(locator.locates.last());
        rounds(2);
        assertThat(journal.count("exists:")).as("已取代判定悬着").isEqualTo(1);
        assertThat(journal.count("superseded?:")).isEqualTo(1);

        assertThat(handBack(store.supersedes.last())).as("锁不在：条目保留").isEqualTo(0L);
        locator.locates.auto();
        round();
        assertThat(journal.count("exists:")).isEqualTo(2);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 2));
    }

    @Test
    void 定时器_第一条入队时开_多条共用一个_名单空了才停_再入队重新开() {
        locator.online(P, ZONE, 3, "scene-3");
        assertThat(scheduler.scheduledCount()).isZero();

        dispatch(P, B);
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        dispatch(P, B + 1);
        dispatch(P + 1, B);
        assertThat(scheduler.scheduledCount()).as("三条共用一个表").isEqualTo(1);
        assertThat(entriesGauge()).isEqualTo(3);

        store.sceneAck(P, B);
        store.sceneAck(P + 1, B);
        round();
        assertThat(outbox.describe()).containsExactly(key(P, B + 1) + "#1");
        assertThat(scheduler.scheduledCount()).as("还有一条，不停").isEqualTo(1);

        store.sceneAck(P, B + 1);
        round();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();

        dispatch(P, B + 2);
        assertThat(scheduler.scheduledCount()).as("再入队重新开表").isEqualTo(1);
        round();
        assertThat(attemptsSent()).as("新表照常驱动重投").endsWith(0, 1);
    }

    static Stream<Arguments> 投递应答() {
        return Stream.of(
                Arguments.of(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_APPLIED), null, "applied"),
                Arguments.of(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED), null, "already_applied"),
                Arguments.of(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_DISCARDED), null, "discarded"),
                Arguments.of(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_DEFERRED), null, "deferred"),
                Arguments.of(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_NOT_HERE), null, "not_here"),
                Arguments.of(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_OVERLOADED), null, "overloaded"),
                Arguments.of(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_HANDLED), null, "transport_error"),
                Arguments.of(SceneBattleReply.getDefaultInstance(), null, "transport_error"),
                Arguments.of(null, null, "transport_error"),
                Arguments.of(null, new TimeoutException("调用超时"), "transport_error"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("投递应答")
    void 投递应答只计数_不改名单_下一轮照样重投(SceneBattleReply reply, Throwable transportError, String expected) {
        locator.online(P, ZONE, 3, "scene-3");
        if (transportError != null) {
            transport.replies.failWith(transportError);
        } else {
            transport.replies.replyWith(reply);
        }

        dispatch(P, B);

        assertThat(deliveries()).containsOnly(entry(expected, 1));
        assertThat(outbox.describe()).as("应答不改名单（D16）").containsExactly(key(P, B) + "#0");
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 1));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(outbox.inFlight()).isZero();

        round();
        assertThat(attemptsSent()).as("「已应用」不等于「可以停止重投」：停不停只看记录还在不在").containsExactly(0, 1);
        assertThat(deliveries()).containsOnly(entry(expected, 2));
    }

    @Test
    void 应答到计数的映射() {
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_APPLIED), null))
                .isEqualTo(Delivery.APPLIED);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED), null))
                .isEqualTo(Delivery.ALREADY_APPLIED);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_DISCARDED), null))
                .isEqualTo(Delivery.DISCARDED);
        assertThat(SettlementOutbox.deliveryResult(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                .setSettlementValue(99).build(), null)).as("不认识的处置").isEqualTo(Delivery.TRANSPORT_ERROR);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_DEFERRED), null))
                .isEqualTo(Delivery.DEFERRED);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_NOT_HERE), null))
                .isEqualTo(Delivery.NOT_HERE);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.status(SceneBattleStatus.SCENE_BATTLE_OVERLOADED), null))
                .isEqualTo(Delivery.OVERLOADED);
        assertThat(SettlementOutbox.deliveryResult(SceneBattleReply.newBuilder().setStatusValue(99).build(), null))
                .as("不认识的状态").isEqualTo(Delivery.TRANSPORT_ERROR);
        assertThat(SettlementOutbox.deliveryResult(RecordingSceneTransport.settlement(SettlementDisposition.SETTLEMENT_APPLIED),
                new IllegalStateException("有应答也有异常时按异常"))).isEqualTo(Delivery.TRANSPORT_ERROR);
    }

    @Test
    void 同一局重入_旧条目悬着的探测回来时被身份比较丢弃_不摘新条目() {
        dispatch(P, B);
        store.probes.hold();
        round();
        assertThat(journal.count("exists:")).isEqualTo(1);

        store.probes.auto();
        dispatch(P, B);
        assertThat(outbox.size()).as("同一 (battle, player) 只有一条，新的顶替旧的").isEqualTo(1);

        store.probes.pending.get(0).complete(false);
        assertThat(scheduler.pendingTasks()).as("旧探测的结局同样先交回发件箱线程，身份比较在那里做").isEqualTo(1);
        scheduler.runPending();

        assertThat(outbox.size()).as("旧条目的「记录不在」不能摘掉新条目").isEqualTo(1);
        assertThat(events()).containsOnly(entry("stored", 2));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        round();
        assertThat(journal.count("exists:")).as("新条目没有被旧回调改成探测中").isEqualTo(2);
        assertThat(outbox.attemptsByBattle()).containsOnly(entry(B, 1));
    }

    @Test
    void 多名玩家各自落库各自定位各自投递_互不影响() {
        locator.online(P, ZONE, 3, "scene-3");
        locator.online(P + 1, ZONE, 4, "scene-4");

        dispatch(P, B);
        dispatch(P + 1, B);
        dispatch(P + 2, B);

        assertThat(journal.starting("deliver:")).containsExactly("deliver:" + key(P, B) + "@scene-3#0",
                "deliver:" + key(P + 1, B) + "@scene-4#0");
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#0", key(P + 1, B) + "#0", key(P + 2, B) + "#0");
        assertThat(store.record(P + 1, B)).isEqualTo(wire(P + 1, B));

        store.sceneAck(P, B);
        round();
        assertThat(outbox.describe()).containsExactly(key(P + 1, B) + "#1", key(P + 2, B) + "#1");
        assertThat(journal.starting("deliver:")).endsWith("deliver:" + key(P + 1, B) + "@scene-4#1");
    }

    // ================================================================== 同号节点跨 zone（spectate-spec §2.7 的 Z7、§2.8）

    /**
     * 两个 zone 各有一台 1 号 scene 的台子：假定位器换成<b>真的</b> {@link SceneAssetLocator}（位置记录与 scene 目录用内存表，记下每次查目录的
     * {@code zone/节点号}），发件箱其余部分照旧。钉的是「结算的投递目标 = 按位置记录的 (zone, 节点号) 查出来的那台」：快照路由里的节点号不参与寻址；
     * 只按节点号找会投到另一个 zone 的同号节点，实例过滤让它<b>丢而不是错</b>，症状是那名玩家的战斗锁停到 TTL。
     */
    private final class TwoZones {
        final Map<Long, HolderRead> holders = new HashMap<>();
        final Map<String, SceneNodeInfo> nodes = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        final SettlementOutbox outbox;

        TwoZones() {
            nodes.put("1/1", node(1, "scene-z1", "10.0.1.1", 21100));
            nodes.put("2/1", node(2, "scene-z2", "10.0.2.1", 21110));
            SceneAssetLocator real = new SceneAssetLocator(
                    id -> CompletableFuture.completedFuture(holders.getOrDefault(id, new HolderRead(LocationStatus.MISSING, null, null))),
                    (zoneId, nodeId) -> {
                        lookups.add(zoneId + "/" + nodeId);
                        return CompletableFuture.completedFuture(Optional.ofNullable(nodes.get(zoneId + "/" + nodeId)));
                    }, null);
            outbox = new SettlementOutbox(scheduler, store, real::resolveAsync, transport::applySettlement, metrics, scheduler::nowMs);
        }

        /** 位置记录：在线，在某个 zone 的 1 号 scene 上。 */
        void online(long playerId, int zoneId) {
            holders.put(playerId, new HolderRead(LocationStatus.ONLINE, PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(zoneId)
                    .setSceneNodeId(1).setSceneId(1001).setOwnerEpoch(5).build(), null));
        }

        /** 房间调一次结算端口：快照路由一律写「zone 1 的 1 号」（开局时的位置；之后玩家在哪由位置记录说了算）。 */
        void dispatch(long playerId) {
            outbox.dispatch(BattleRouting.newBuilder().setZoneId(1).setSceneNodeId(1).setSceneInstanceId("scene-z1").setGateNodeId(1)
                    .setGateInstanceId("gate-z1").setSessionId((1 << 17) | 1).build(), playerId, settlement(playerId, B));
            scheduler.runPending();
        }

        private static SceneNodeInfo node(int zoneId, String instance, String host, int port) {
            return SceneNodeInfo.newBuilder().setZoneId(zoneId).setNodeId(1).setInstanceId(instance).setRpcHost(host).setRpcPort(port).build();
        }
    }

    private static final SceneAssetEndpoint Z1_NODE_1 = new SceneAssetEndpoint(1, 1, "scene-z1", "10.0.1.1", 21100);
    private static final SceneAssetEndpoint Z2_NODE_1 = new SceneAssetEndpoint(2, 1, "scene-z2", "10.0.2.1", 21110);

    @Test
    void 两个zone各有1号scene_首投发往位置记录所在zone的那台_快照路由里的节点号不参与寻址() {
        TwoZones zones = new TwoZones();
        zones.online(P, 1);
        zones.online(P + 1, 2);

        zones.dispatch(P);
        zones.dispatch(P + 1);

        assertThat(zones.lookups).as("各按自己位置记录的 zone 查目录").containsExactly("1/1", "2/1");
        assertThat(transport.sent).extracting(Sent::endpoint).containsExactly(Z1_NODE_1, Z2_NODE_1);
        assertThat(transport.sent).extracting(s -> s.call().getTargetInstanceId()).containsExactly("scene-z1", "scene-z2");
        assertThat(transport.sent).extracting(s -> s.call().getPlayerId()).containsExactly(P, P + 1);
        assertThat(journal.starting("deliver:")).containsExactly("deliver:" + key(P, B) + "@scene-z1#0", "deliver:" + key(P + 1, B) + "@scene-z2#0");
    }

    @Test
    void 重投时玩家已换到另一个zone的同号节点_按新的位置记录投过去_没动的那位照旧() {
        TwoZones zones = new TwoZones();
        zones.online(P, 1);
        zones.online(P + 1, 2);
        zones.dispatch(P);
        zones.dispatch(P + 1);
        // 节点号没变（都是 1），zone 变了：P + 1 从 zone 2 的 1 号换到了 zone 1 的 1 号
        zones.online(P + 1, 1);
        zones.lookups.clear();
        int before = transport.sent.size();

        round();

        assertThat(zones.lookups).containsExactly("1/1", "1/1");
        List<Sent> resent = transport.sent.subList(before, transport.sent.size());
        assertThat(resent).extracting(Sent::endpoint).containsExactly(Z1_NODE_1, Z1_NODE_1);
        assertThat(resent).extracting(s -> s.call().getPlayerId()).containsExactly(P, P + 1);
        assertThat(resent).extracting(s -> s.call().getTargetInstanceId()).as("目标实例跟着定位结果走，不是上一次的 scene-z2")
                .containsExactly("scene-z1", "scene-z1");
        assertThat(resent).extracting(s -> s.call().getAttempt()).containsExactly(1, 1);
    }

    @Test
    void zone2的1号从目录消失_zone1的1号还在_zone2玩家这一轮无目标_不投给zone1的同号节点() {
        TwoZones zones = new TwoZones();
        zones.online(P, 2);
        zones.nodes.remove("2/1");

        zones.dispatch(P);

        assertThat(zones.lookups).containsExactly("2/1");
        assertThat(transport.sent).as("首投解析不到：这次不投，照常登记").isEmpty();
        assertThat(zones.outbox.describe()).containsExactly(key(P, B) + "#0");

        round();

        assertThat(zones.lookups).as("重投那一轮查的仍是 zone 2").containsExactly("2/1", "2/1");
        assertThat(transport.sent).as("zone 1 的 1 号不是他的持有者").isEmpty();
        assertThat(journal.starting("superseded?:")).as("无目标的轮另跑已取代判定").containsExactly("superseded?:" + key(P, B));
        assertThat(zones.outbox.describe()).containsExactly(key(P, B) + "#1");
    }

    @Test
    void 大号id按无符号十进制进调用() {
        long player = -5L;
        long battle = Long.MIN_VALUE + 7;
        locator.online(player, ZONE, 3, "scene-3");

        dispatch(player, battle);

        assertThat(journal.entries()).contains("store:18446744073709551611/9223372036854775815",
                "deliver:18446744073709551611/9223372036854775815@scene-3#0");
        assertThat(transport.last().call().getPlayerId()).isEqualTo(player);
        assertThat(outbox.attemptsByBattle()).containsOnlyKeys(battle);
    }

    // ================================================================== 线程所有权（D25）

    @Test
    void 五跳异步结局_落库_首投定位_探测_重投定位_已取代判定_都先交回发件箱线程_跑到才生效() {
        locator.online(P, ZONE, 3, "scene-3");
        store.stores.hold();
        locator.locates.hold();
        store.probes.hold();
        store.supersedes.hold();

        dispatch(P, B);
        // ① 落库的结局 → 登记、开表、发起首投定位
        handBack(store.stores.last());
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#0");
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(journal.count("locate:")).isEqualTo(1);
        assertThat(transport.sent).isEmpty();
        // ② 首投定位的结局 → 投递
        handBack(locator.locates.last());
        assertThat(attemptsSent()).containsExactly(0);
        assertThat(outbox.inFlight()).isZero();

        round();
        assertThat(journal.count("exists:")).isEqualTo(1);
        // ③ 探测的结局（记录还在）→ 发起重投定位
        handBack(store.probes.last());
        assertThat(journal.count("locate:")).isEqualTo(2);
        assertThat(outbox.describe()).as("定位没回来之前不计次").containsExactly(key(P, B) + "#0");
        // ④ 重投定位的结局 → 计次、重投
        handBack(locator.locates.last());
        assertThat(attemptsSent()).containsExactly(0, 1);
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#1");

        locator.offline(P);
        store.lock(P, B + 1);
        round();
        handBack(store.probes.last());
        handBack(locator.locates.last());
        assertThat(journal.count("superseded?:")).as("无目标：计次并发起已取代判定").isEqualTo(1);
        assertThat(outbox.describe()).containsExactly(key(P, B) + "#2");
        // ⑤ 已取代判定的结局（脚本那边已删了记录）→ 摘除、停表
        assertThat(handBack(store.supersedes.last())).isEqualTo(2L);
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 2), entry("resend", 1), entry("skip_no_target", 1),
                entry("superseded", 1));
        assertThat(deliveries()).containsOnly(entry("applied", 2));
    }

    @Test
    void 真发件箱线程_各跳的结局都由别的线程交回_落库探测定位投递已取代判定仍全部在发件箱线程上发起() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop(new DefaultThreadFactory("test-battle-outbox"));
        try {
            SettlementOutbox real = new SettlementOutbox(new EventLoopBattleScheduler(loop), store, locator::locate,
                    transport::applySettlement, metrics, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            Duration patience = Duration.ofSeconds(10);
            locator.online(P, ZONE, 3, "scene-3");
            store.stores.hold();
            locator.locates.hold();
            store.probes.hold();
            store.supersedes.hold();

            // 首投：落库与定位的结局都在测试线程上交回（生产上是 Redisson 的回调线程）
            real.dispatch(routing(), P, settlement(P, B));
            awaitHeld(store.stores, 1).release();
            awaitHeld(locator.locates, 1).release();
            await().atMost(patience).until(() -> transport.sent.size() == 1 && real.inFlight() == 0);
            // 一轮重投：探测 → 定位 → 投递
            loop.execute(real::tick);
            awaitHeld(store.probes, 1).release();
            awaitHeld(locator.locates, 2).release();
            await().atMost(patience).until(() -> transport.sent.size() == 2);
            // 又一轮：玩家离线、锁已被下一局持有 → 探测 → 定位 → 已取代判定 → 摘除
            locator.offline(P);
            store.lock(P, B + 1);
            loop.execute(real::tick);
            awaitHeld(store.probes, 2).release();
            awaitHeld(locator.locates, 3).release();
            awaitHeld(store.supersedes, 1).release();
            await().atMost(patience).until(() -> loop.submit((Callable<Integer>) real::size).get(5, TimeUnit.SECONDS) == 0);

            assertThat(journal.entries()).containsExactly("store:" + key(P, B), "store-done:" + key(P, B) + "=1", "locate:" + P,
                    "deliver:" + key(P, B) + "@scene-3#0", "exists:" + key(P, B), "locate:" + P, "deliver:" + key(P, B) + "@scene-3#1",
                    "exists:" + key(P, B), "locate:" + P, "superseded?:" + key(P, B));
            assertThat(journal.threadsOf("store-done:")).as("结局确实是在别的线程（测试线程）上交回的")
                    .containsExactly(Thread.currentThread().getName());
            for (String port : List.of("store:", "locate:", "deliver:", "exists:", "superseded?:")) {
                assertThat(journal.threadsOf(port)).as("%s 只在发件箱线程上发起（结局交回之后的下一步没有留在回调线程上）", port).isNotEmpty()
                        .allSatisfy(thread -> assertThat(thread).startsWith("test-battle-outbox"));
            }
            assertThat(events()).containsOnly(entry("stored", 1), entry("delivered", 2), entry("resend", 1), entry("skip_no_target", 1),
                    entry("superseded", 1));
        } finally {
            loop.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    // ================================================================== 停机

    @Test
    void 停机_在途落库没回来_最多等drainTimeout_然后丢弃名单并停表_之后的dispatch只打日志() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);
        store.stores.hold();
        dispatch(P, B + 1);
        assertThat(outbox.inFlight()).isEqualTo(1);
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        long started = System.nanoTime();
        outbox.drainAndClose(Duration.ofMillis(300));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        scheduler.runPending();

        assertThat(waitedMs).as("等满了上限，但不多等").isBetween(300L, 2_500L);
        assertThat(outbox.size()).as("内存名单丢弃（记录留在 Redis）").isZero();
        assertThat(store.hasRecord(P, B)).isTrue();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(entriesGauge()).isZero();

        journal.clear();
        dispatch(P, B + 2);
        assertThat(journal.entries()).as("已关闭：不落库、不投递").isEmpty();
        assertThat(outbox.inFlight()).as("没有新增在途").isEqualTo(1);

        // 迟到的落库结局：记录已持久，照常首投一次，但不再登记、不再开表
        assertThat(handBack(store.stores.last())).isEqualTo(2L);
        assertThat(journal.entries()).containsExactly("store-done:" + key(P, B + 1) + "=2", "locate:" + P,
                "deliver:" + key(P, B + 1) + "@scene-3#0");
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(outbox.inFlight()).isZero();
    }

    @Test
    void 停机_没有在途_立即返回() {
        locator.online(P, ZONE, 3, "scene-3");
        dispatch(P, B);

        long started = System.nanoTime();
        outbox.drainAndClose(Duration.ofSeconds(5));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        scheduler.runPending();

        assertThat(waitedMs).isLessThan(1_000);
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 停机_在途的落库与首投一回来就提前返回_真发件箱线程() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            SettlementOutbox real = new SettlementOutbox(new EventLoopBattleScheduler(loop), store, locator::locate,
                    transport::applySettlement, metrics, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            locator.online(P, ZONE, 3, "scene-3");
            store.stores.hold();
            real.dispatch(routing(), P, settlement(P, B));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (store.stores.pending.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(store.stores.pending).as("落库已在发件箱线程上发起").hasSize(1);
            assertThat(real.inFlight()).isEqualTo(1);
            // 起点取在安排放行之前：放行最早也在 200 ms 之后，所以「等到了」时耗时一定 ≥ 200 ms
            long started = System.nanoTime();
            CompletableFuture<Void> late = CompletableFuture.runAsync(() -> store.stores.last().release(),
                    CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));

            real.drainAndClose(Duration.ofSeconds(10));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            late.get(5, TimeUnit.SECONDS);
            assertThat(waitedMs).as("等到了在途的那一份（不是立即返回），也没有等满 10 s").isBetween(200L, 5_000L);
            assertThat(real.inFlight()).isZero();
            assertThat(journal.entries()).as("落库与首投都在关闭之前完成").containsExactly("store:" + key(P, B),
                    "store-done:" + key(P, B) + "=1", "locate:" + P, "deliver:" + key(P, B) + "@scene-3#0");
            assertThat(loop.submit((Callable<Integer>) real::size).get(5, TimeUnit.SECONDS)).as("随后丢弃内存名单").isZero();
        } finally {
            loop.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 发件箱线程已停_dispatch不抛异常_在途计数回退() {
        scheduler.shutdown();

        assertThatCode(() -> outbox.dispatch(routing(), P, settlement(P, B))).doesNotThrowAnyException();

        assertThat(outbox.inFlight()).isZero();
        assertThat(journal.entries()).isEmpty();
    }

    @Test
    void 发件箱线程已停_迟到的异步结局被丢弃_停机不抛异常() {
        locator.online(P, ZONE, 3, "scene-3");
        store.stores.hold();
        dispatch(P, B);
        scheduler.shutdown();

        assertThatCode(() -> store.stores.last().release()).doesNotThrowAnyException();
        assertThatCode(() -> outbox.drainAndClose(Duration.ZERO)).doesNotThrowAnyException();

        assertThat(transport.sent).isEmpty();
        assertThat(journal.count("locate:")).isZero();
        assertThatCode(() -> outbox.dispatch(routing(), P, settlement(P, B + 1))).doesNotThrowAnyException();
        assertThat(journal.count("store:")).as("关闭之后不再落库").isEqualTo(1);
    }
}
