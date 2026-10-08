package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler.Reply;
import com.game.match.dispatch.MatchMethods;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 163 经<b>真的派发器</b>走一遍（spectate-spec §4.9、§4.11 给 match-spec §8.1 的 163 一行、§10.3）：处理器把 163 交给自己的执行器——
 * 跑在虚拟线程上、不占 {@code match-worker}；在途满了当场回 in-band 16004 并计 {@code overloaded}；请求体解析失败与未预期异常回信封 1003
 * （后者已抢到的标记按值释放）；身份只认会话；四条出口之后在途数都回到 0。判定表本身见 {@code WatchBattleServiceTest}。
 */
class WatchBattleHandlerTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final int WATCH_BATTLE = REGISTRY.requireId(MatchMethods.SERVICE, MatchMethods.WATCH_BATTLE);
    private static final long X = 7_000_000_163L;
    private static final String BUSY = "服务器繁忙,请稍后再试";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore spectate = new InMemorySpectateStore(clock, tickets, placements, events);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeObserverDialer observers = new FakeObserverDialer(events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final AtomicInteger nonceSeq = new AtomicInteger();
    private final WatchBattleService service = new WatchBattleService(spectate, placements, players, tickets, observers, metrics, () -> 0.0,
            () -> String.format("%016x", nonceSeq.incrementAndGet()), Runnable::run);
    /** 共用的工作池：163 一次都不该投到它上面。 */
    private final List<Runnable> sharedPool = new CopyOnWriteArrayList<>();
    private final Executor workers = sharedPool::add;
    private SpectateExecutor executor;

    @AfterEach
    void releaseEverything() {
        observers.releaseAdd();
        observers.releaseRemove();
        if (executor != null) {
            assertThat(executor.awaitIdle(Duration.ofSeconds(20))).as("用例结束时没有还在途的 163").isTrue();
        }
    }

    private MatchDispatcher dispatcher(int maxInflight, long budgetMillis) {
        executor = new SpectateExecutor(maxInflight);
        metrics.bindSpectateInflight(executor::inflight);
        return new MatchDispatcher(REGISTRY, List.of(new WatchBattleHandler(service, executor, metrics)), workers, metrics, budgetMillis);
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst-1").setSessionId((int) playerId).setZoneId(1)
                .setPlayerId(playerId).setAccount("acc-" + playerId).build();
    }

    private static ClientCall call(SessionContext session, ByteString body) {
        return ClientCall.newBuilder().setSession(session).setMessageId(WATCH_BATTLE).setBody(body).setRequestId(9).build();
    }

    private static ClientCall watch(long playerId, long battleId) {
        return call(session(playerId), WatchBattleRequest.newBuilder().setBattleId(battleId).build().toByteString());
    }

    private static ClientReply reply(CompletableFuture<ClientReply> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    private void online(long playerId) {
        players.online(playerId, 1, 7);
    }

    private BattlePlacement open(long battleId) {
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-a")
                .setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).setMode(1).setCreatedAtMs(clock.peekMs()).build();
        placements.put(placement);
        spectate.putWatchable(battleId, placement.getCreatedAtMs());
        return placement;
    }

    private static void assertInBand(ClientReply reply, int code, String text) throws Exception {
        assertThat(reply.getTipId()).as("in-band：信封的 tip_id 为 0").isZero();
        WatchBattleResponse response = WatchBattleResponse.parseFrom(reply.getBody());
        assertThat(response.getBattleId()).isZero();
        assertThat(response.getErrorMessage().getId()).isEqualTo(code);
        assertThat(response.getErrorMessage().getParametersList()).hasSize(1);
        assertThat(response.getErrorMessage().getParameters(0).getBytes(StandardCharsets.UTF_8)).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertEnvelope1003(ClientReply reply) {
        assertThat(reply.getTipId()).isEqualTo(1003);
        assertThat(reply.getTipParametersList()).as("信封不带 parameters").isEmpty();
        assertThat(reply.getBody().isEmpty()).as("没有 in-band 应答").isTrue();
    }

    private Map<String, Double> outcomes() {
        Map<String, Double> out = new TreeMap<>();
        for (Counter counter : meters.find("xm.match.watch.battle").counters()) {
            if (counter.count() != 0) {
                out.put(counter.getId().getTag("outcome"), counter.count());
            }
        }
        return out;
    }

    private double requests(String result) {
        return meters.get("xm.match.requests").tag("method", "WatchBattle").tag("result", result).timer().count();
    }

    private double inflightGauge() {
        return meters.get("xm.match.spectate.inflight").gauge().value();
    }

    private void awaitInflight(int expected) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (executor.inflight() != expected && System.nanoTime() < giveUp) {
            Thread.sleep(2);
        }
        assertThat(executor.inflight()).as("在途数").isEqualTo(expected);
        assertThat(inflightGauge()).as("xm_match_spectate_inflight 跟着在途数走").isEqualTo(expected);
    }

    // ================================================================ 执行器

    @Test
    void 处理器不是inline_带着自己的执行器_方法名是WatchBattle() {
        dispatcher(4, 4_500);
        WatchBattleHandler handler = new WatchBattleHandler(service, executor, metrics);

        assertThat(handler.method()).isEqualTo(MatchMethods.WATCH_BATTLE);
        assertThat(handler.inline()).as("163 要同步等 battle 的 RPC：不能在 Dubbo 线程上当场回").isFalse();
        assertThat(handler.executor()).as("每次都是同一个执行器").isSameAs(executor).isSameAs(handler.executor());
    }

    @Test
    void 一次163跑在自己的虚拟线程上_不占match_worker_派发当场返回_成功应答原样带回_在途回到0() throws Exception {
        online(1001);
        open(X);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        observers.beforeAdd = call -> ranOn.add(Thread.currentThread());
        observers.hangAdd();
        MatchDispatcher dispatcher = dispatcher(4, 4_500);

        CompletableFuture<ClientReply> future = dispatcher.dispatch(watch(1001, X));

        // AddObserver 还挂着：派发早已返回，调用线程（生产是 Dubbo 线程）没有被占住
        awaitInflight(1);
        assertThat(future).isNotDone();
        observers.releaseAdd();
        ClientReply reply = reply(future);

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody()).as("成功应答只有 battle_id").isEqualTo(WatchBattleResponse.newBuilder().setBattleId(X).build().toByteString());
        assertThat(ranOn).singleElement().satisfies(thread -> {
            assertThat(thread.isVirtual()).as("163 的处理流程在虚拟线程上").isTrue();
            assertThat(thread.getName()).startsWith("match-spectate-");
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
        assertThat(sharedPool).as("一次都没有投到共用的 match-worker").isEmpty();
        awaitInflight(0);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
        assertThat(requests("ok")).isEqualTo(1);
    }

    @Test
    void 不同会话的163并发_各占一条虚拟线程_互不等待() throws Exception {
        open(X);
        List<String> threads = new CopyOnWriteArrayList<>();
        observers.beforeAdd = call -> threads.add(Thread.currentThread().getName());
        observers.hangAdd();
        MatchDispatcher dispatcher = dispatcher(8, 4_500);
        List<CompletableFuture<ClientReply>> futures = new ArrayList<>();
        for (long player = 2001; player <= 2005; player++) {
            online(player);
            futures.add(dispatcher.dispatch(watch(player, X)));
        }

        awaitInflight(5);
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (threads.size() < 5 && System.nanoTime() < giveUp) {
            Thread.sleep(2);
        }
        assertThat(threads).as("五个请求都已各自走到 AddObserver：没有谁在等谁").hasSize(5).doesNotHaveDuplicates();
        observers.releaseAdd();

        for (CompletableFuture<ClientReply> future : futures) {
            assertThat(WatchBattleResponse.parseFrom(reply(future).getBody()).getBattleId()).isEqualTo(X);
        }
        awaitInflight(0);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 5.0));
    }

    // ================================================================ 过载

    @Test
    void 在途已满_当场回in_band16004服务器繁忙_计overloaded_没有进处理流程_占着许可的请求不受影响() throws Exception {
        online(1001);
        online(1002);
        open(X);
        observers.hangAdd();
        MatchDispatcher dispatcher = dispatcher(1, 4_500);
        CompletableFuture<ClientReply> first = dispatcher.dispatch(watch(1001, X));
        awaitInflight(1);

        CompletableFuture<ClientReply> second = dispatcher.dispatch(watch(1002, X));

        assertThat(second).as("拒收是当场的：不排队、不等许可").isDone();
        assertInBand(reply(second), 16004, BUSY);
        assertThat(spectate.calls).as("被拒的请求没有进处理流程：存储里没有它的任何调用").noneMatch(call -> call.contains("1002"));
        assertThat(spectate.markOf(1002)).as("标记从未写入").isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("overloaded", 1.0));
        assertThat(requests("overloaded")).isEqualTo(1);
        assertThat(executor.inflight()).as("被拒的那一次不占许可").isEqualTo(1);

        observers.releaseAdd();
        assertThat(WatchBattleResponse.parseFrom(reply(first).getBody()).getBattleId()).isEqualTo(X);
        awaitInflight(0);
        assertThat(outcomes()).isEqualTo(Map.of("overloaded", 1.0, "ok", 1.0));

        // 许可还回来之后，刚才被拒的玩家再发就能进来
        observers.releaseAdd();
        assertThat(WatchBattleResponse.parseFrom(reply(dispatcher.dispatch(watch(1002, X))).getBody()).getBattleId()).isEqualTo(X);
        awaitInflight(0);
    }

    @Test
    void 轮到执行时请求预算已用完_同样回in_band16004_计overloaded_不进处理流程() throws Exception {
        online(1001);
        open(X);
        MatchDispatcher dispatcher = dispatcher(4, 0);

        ClientReply reply = reply(dispatcher.dispatch(watch(1001, X)));

        assertInBand(reply, 16004, BUSY);
        assertThat(spectate.calls).isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("overloaded", 1.0));
        awaitInflight(0);
    }

    @Test
    void 过载应答逐字节_error_message带16004与一条半角逗号的说明_没有battle_id() throws Exception {
        dispatcher(1, 4_500);
        Reply overload = new WatchBattleHandler(service, executor, metrics).onOverload();

        assertThat(overload).isInstanceOf(Reply.Body.class);
        ByteString bytes = ((Reply.Body) overload).bytes();
        assertThat(bytes).isEqualTo(WatchBattleResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16004).addParameters("服务器繁忙,请稍后再试")).build().toByteString());
        assertThat(WatchBattleResponse.parseFrom(bytes).getErrorMessage().getParameters(0)).doesNotContain("，");
        assertThat(outcomes()).as("功能自己的出口计数在 onOverload 里记").isEqualTo(Map.of("overloaded", 1.0));
    }

    // ================================================================ 信封 1003

    @Test
    void 请求体解析失败_信封1003_没有in_band应答_不进处理流程_许可归还() throws Exception {
        online(1001);
        open(X);
        MatchDispatcher dispatcher = dispatcher(1, 4_500);

        ClientReply reply = reply(dispatcher.dispatch(call(session(1001), ByteString.copyFrom(new byte[] {0x10, (byte) 0xFF}))));

        assertEnvelope1003(reply);
        assertThat(spectate.calls).isEmpty();
        assertThat(outcomes()).as("解析失败在进处理流程之前：163 的出口计数不动").isEmpty();
        assertThat(requests("bad_request")).isEqualTo(1);
        awaitInflight(0);
        // 上限是 1：许可没还的话下一条会被拒成 16004
        assertThat(WatchBattleResponse.parseFrom(reply(dispatcher.dispatch(watch(1001, X))).getBody()).getBattleId()).isEqualTo(X);
    }

    @Test
    void J2_处理流程里的未预期异常_信封1003_已抢到的标记按值释放_许可归还_计internal() throws Exception {
        online(1001);
        open(X);
        observers.beforeAdd = call -> {
            throw new IllegalStateException("注入的 bug：AddObserver 途中抛出");
        };
        MatchDispatcher dispatcher = dispatcher(1, 4_500);

        CompletableFuture<ClientReply> future = dispatcher.dispatch(watch(1001, X));
        ClientReply reply = reply(future);

        assertEnvelope1003(reply);
        assertThat(future).as("future 永不异常完成").isNotCompletedExceptionally();
        assertThat(events).as("标记确实抢到过").contains("spectate.acquire:1001");
        assertThat(spectate.markOf(1001)).as("没有留给 TTL：按值释放了").isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
        assertThat(requests("error")).isEqualTo(1);
        awaitInflight(0);
        observers.beforeAdd = null;
        assertThat(WatchBattleResponse.parseFrom(reply(dispatcher.dispatch(watch(1001, X))).getBody()).getBattleId())
                .as("上限是 1：未预期异常那一条的许可已经归还").isEqualTo(X);
    }

    // ================================================================ 身份

    @Test
    void 身份只认会话_会话没有玩家时请求体里的player_id也不用_16004缺少玩家身份_W12() throws Exception {
        online(1001);
        open(X);
        MatchDispatcher dispatcher = dispatcher(2, 4_500);
        ByteString forged = WatchBattleRequest.newBuilder().setPlayerId(1001).setBattleId(X).build().toByteString();

        ClientReply reply = reply(dispatcher.dispatch(call(session(0), forged)));

        assertInBand(reply, 16004, "缺少玩家身份");
        assertThat(spectate.calls).as("基线这里会回落到请求体的 player_id（B-s9）").isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 身份只认会话_请求体里写着别人的player_id_登记的仍是会话里的玩家() throws Exception {
        online(1001);
        online(2002);
        open(X);
        MatchDispatcher dispatcher = dispatcher(2, 4_500);
        ByteString forged = WatchBattleRequest.newBuilder().setPlayerId(2002).setBattleId(X).build().toByteString();

        ClientReply reply = reply(dispatcher.dispatch(call(session(1001), forged)));

        assertThat(WatchBattleResponse.parseFrom(reply.getBody()).getBattleId()).isEqualTo(X);
        assertThat(observers.adds()).singleElement().satisfies(add -> {
            assertThat(add.observerId()).as(":739").isEqualTo(1001);
            assertThat(add.request().getObserverName()).isEqualTo("acc-1001");
        });
        assertThat(spectate.markOf(1001)).isPresent();
        assertThat(spectate.markOf(2002)).isEmpty();
        assertThat(players.reads).noneMatch(read -> read.endsWith(":2002"));
    }

    // ================================================================ 四条出口都归还许可

    @Test
    void 在途许可在正常_业务拒绝_超时_未预期异常四条出口都归还_在途数与指标都回到0() throws Exception {
        online(1001);
        open(X);
        // 上限 1：前一条只要没还许可，后一条就会被拒成 overloaded。请求预算 2.5 s：第 3 步要真的等到 AddObserver 的硬截止
        MatchDispatcher dispatcher = dispatcher(1, 2_500);

        // 1 业务拒绝（in-band）：指定一场不存在的战斗
        assertInBand(reply(dispatcher.dispatch(watch(1001, 424_242))), 16018, "该战斗不存在或已结束");
        awaitInflight(0);

        // 2 正常
        assertThat(WatchBattleResponse.parseFrom(reply(dispatcher.dispatch(watch(1001, X))).getBody()).getBattleId()).isEqualTo(X);
        awaitInflight(0);

        // 3 超时：AddObserver 挂到它的硬截止（请求截止 − 200 ms）才返回，结局不明 → 16018「当前无法观战」，标记保留
        observers.hangAdd();
        assertInBand(reply(dispatcher.dispatch(watch(1001, X))), 16018, "该战斗当前无法观战");
        observers.releaseAdd();
        awaitInflight(0);
        assertThat(spectate.markOf(1001)).as("结局不明：标记保留（W4）").isPresent();

        // 4 未预期异常
        observers.beforeAdd = call -> {
            throw new IllegalStateException("注入的 bug");
        };
        assertEnvelope1003(reply(dispatcher.dispatch(watch(1001, X))));
        awaitInflight(0);
        observers.beforeAdd = null;

        // 四条出口之后仍能受理
        assertThat(WatchBattleResponse.parseFrom(reply(dispatcher.dispatch(watch(1001, X))).getBody()).getBattleId()).isEqualTo(X);
        awaitInflight(0);
        assertThat(outcomes()).as("没有一条是因为许可没还而被拒的").isEqualTo(Map.of("ok", 2.0, "not_found", 1.0, "rejected", 1.0, "internal", 1.0));
        assertThat(executor.awaitIdle(Duration.ofSeconds(20))).isTrue();
    }

    @Test
    void 观众RPC的结局不影响许可_判死与没送达之后在途同样归零() throws Exception {
        online(1001);
        open(X);
        MatchDispatcher dispatcher = dispatcher(1, 4_500);

        observers.nextAdd(new Outcome.NotDelivered("连不上"));
        assertInBand(reply(dispatcher.dispatch(watch(1001, X))), 16018, "该战斗当前无法观战");
        awaitInflight(0);
        observers.nextAdd(new Outcome.Dead());
        assertInBand(reply(dispatcher.dispatch(watch(1001, X))), 16018, "该战斗不存在或已结束");
        awaitInflight(0);

        assertThat(outcomes()).isEqualTo(Map.of("rejected", 1.0, "not_found", 1.0));
    }

    @Test
    void 截止由派发器按受理时刻给_处理流程拿到的就是它() throws Exception {
        online(1001);
        open(X);
        List<Long> hardStops = new CopyOnWriteArrayList<>();
        observers.beforeAdd = call -> hardStops.add(call.hardStopRemainingMs());
        MatchDispatcher dispatcher = dispatcher(2, 4_500);

        reply(dispatcher.dispatch(watch(1001, X)));

        assertThat(hardStops).singleElement().satisfies(remaining -> assertThat(remaining)
                .as("AddObserver 的硬截止 = 受理时刻 + 4500 − 200：不会比它晚").isBetween(1L, 4_300L));
    }
}
