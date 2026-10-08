package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakeObserverDialer.Call;
import com.game.match.testing.FakeObserverDialer.Kind;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketReader;
import com.game.match.ticket.TicketState;
import com.game.proto.BattleRouting;
import com.game.proto.match.WatchBattleResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 163 WatchBattle 的判定表（spectate-spec §3.1 的 18 + 2 行、§3.2 的 9 条 {@code parameters[0]}、§4.4 的七步、§10.3 的对照表）。
 * 每一行都断言：tip 码、{@code parameters[0]} 逐字节、指标 outcome（每个请求恰好一个）、调没调 battle、观战标记的状态。
 * 用例名里的「第 n 行」是规格 §3.1 的行号；括号里的 {@code :行号} 是对照的基线用例（{@code spectate_test.go} / {@code watchbattle_create_window_test.go}）。
 *
 * <p>替身：内存版观战存储（与内存票据 / 落点存储、手拨的 Redis 时钟共享状态）、可脚本化的观众 RPC、内存里的战斗锁与在线目录。
 * 随机数与 nonce 都是给定值（nonce 依次是 {@code 0000000000000001}、{@code …0002}……），复查的两次读缺省在调用线程上依次做。
 */
class WatchBattleServiceTest {

    private static final long PLAYER = 1001;
    private static final long X = 7_000_000_077L;
    private static final long Y = 7_000_000_078L;
    private static final long Z = 7_000_000_079L;
    /** 超过有符号 64 位上限的 battle_id：标记、索引成员、日志一律按无符号十进制。 */
    private static final long HUGE = 0xF000_0000_0000_0001L;
    private static final long BUDGET_MS = MatchBudgets.DEFAULT_REQUEST_BUDGET_MS;

    private static final String NO_IDENTITY = "缺少玩家身份";
    private static final String BUSY = "服务器繁忙,请稍后再试";
    private static final String QUEUED = "匹配中无法观战";
    private static final String IN_BATTLE = "战斗尚未结束,无法观战";
    private static final String OFFLINE = "会话不在线,无法观战";
    private static final String ALREADY = "已在观战另一场战斗";
    private static final String NO_BATTLE = "当前没有可观战的战斗";
    private static final String NOT_FOUND = "该战斗不存在或已结束";
    private static final String NOT_WATCHABLE = "该战斗当前无法观战";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore spectate = new InMemorySpectateStore(clock, tickets, placements, events);
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeObserverDialer observers = new FakeObserverDialer(events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    /** 随机选场依次用到的 r；用完之后恒为 0（= 未过期成员里分数最低的那个）。 */
    private final Deque<Double> randoms = new ArrayDeque<>();
    private final AtomicInteger nonceSeq = new AtomicInteger();
    private final WatchBattleService service = service(spectate, players, Runnable::run);

    private WatchBattleService service(SpectateStore store, PlayerStatusReader statusReader, Executor recheckThreads) {
        return new WatchBattleService(store, placements, statusReader, tickets, observers, metrics,
                () -> randoms.isEmpty() ? 0.0 : randoms.poll(), () -> nonce(nonceSeq.incrementAndGet()), recheckThreads);
    }

    // ================================================================ 摆状态

    private static String nonce(int n) {
        return String.format("%016x", n);
    }

    /** 本测试里第 n 次抢标记写下的值。 */
    private static String markValue(long battleId, int n) {
        return SpectateRules.encodeMark(battleId, nonce(n));
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst-1").setSessionId(7).setZoneId(1).setPlayerId(playerId)
                .setAccount("acc-" + playerId).build();
    }

    private static BattlePlacement placement(long battleId, long createdAtMs) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-a").setRpcHost("10.1.1.1")
                .setRpcPort(21200).setAttempt(1).setMode(1).setBattleConfigId(1).addPlayerNames("甲").addPlayerNames("乙")
                .setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build();
    }

    /** 一场已开局并公开的战斗：落点在、在可观战索引里（分数 = 此刻的 Redis 时间 + {@code scoreOffsetMs}）。 */
    private BattlePlacement open(long battleId, long scoreOffsetMs) {
        BattlePlacement placement = placement(battleId, clock.peekMs() + scoreOffsetMs);
        placements.put(placement);
        spectate.putWatchable(battleId, placement.getCreatedAtMs());
        return placement;
    }

    private BattlePlacement open(long battleId) {
        return open(battleId, 0);
    }

    /** 观众在线（在线目录有条目）、没有锁、没有票。 */
    private void online() {
        players.online(PLAYER, 1, 7);
    }

    private void enqueue(long playerId) {
        tickets.enqueue(playerId, "ticket-" + playerId, new QueueRef(3, 0), 1, 150_000, 21_600_000, Deadline.after(5_000));
    }

    private WatchBattleResponse watch(long battleId) {
        return service.watch(session(PLAYER), battleId, Deadline.after(BUDGET_MS));
    }

    private WatchBattleResponse watch(long battleId, long budgetMs) {
        return service.watch(session(PLAYER), battleId, Deadline.after(budgetMs));
    }

    // ================================================================ 断言

    private static void assertRejected(WatchBattleResponse response, int code, String text) {
        assertThat(response.getBattleId()).as("失败时 battle_id = 0").isZero();
        assertThat(response.hasErrorMessage()).isTrue();
        assertThat(response.getErrorMessage().getId()).isEqualTo(code);
        assertThat(response.getErrorMessage().getParametersList()).as("parameters 恰好一条").hasSize(1);
        assertThat(response.getErrorMessage().getParameters(0).getBytes(StandardCharsets.UTF_8)).as("parameters[0] 逐字节（半角逗号）")
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertAccepted(WatchBattleResponse response, long battleId) {
        assertThat(response.hasErrorMessage()).as("成功不带 error_message").isFalse();
        assertThat(response.getBattleId()).isEqualTo(battleId);
        assertThat(response.toByteString()).as("成功应答只有 battle_id 一个字段")
                .isEqualTo(WatchBattleResponse.newBuilder().setBattleId(battleId).build().toByteString());
    }

    /** 非 0 的计数器：标签值 → 计数。 */
    private Map<String, Double> counts(String meter, String tag) {
        Map<String, Double> out = new TreeMap<>();
        for (Counter counter : meters.find(meter).counters()) {
            if (counter.count() != 0) {
                out.merge(counter.getId().getTag(tag), counter.count(), Double::sum);
            }
        }
        return out;
    }

    /** 163 的出口计数（只列非 0 的）：每个请求恰好一个，所以直接断言整张表。 */
    private Map<String, Double> outcomes() {
        return counts("xm.match.watch.battle", "outcome");
    }

    private double evictions(String reason, String result) {
        return meters.get("xm.match.spectate.evictions").tag("reason", reason).tag("result", result).counter().count();
    }

    private double evictionsTotal() {
        return meters.find("xm.match.spectate.evictions").counters().stream().mapToDouble(Counter::count).sum();
    }

    private Map<String, Double> indexEvictions() {
        return counts("xm.match.watchable.index.evictions", "reason");
    }

    private Map<String, Double> anomalies() {
        return counts("xm.match.watchable.anomalies", "reason");
    }

    private Optional<Long> markedBattle() {
        return spectate.markOf(PLAYER).flatMap(SpectateRules::decodeMark).map(SpectateRules.Mark::battleId);
    }

    private List<Kind> callKinds() {
        return observers.calls.stream().map(Call::kind).toList();
    }

    private List<String> lockReads() {
        return players.reads.stream().filter(read -> read.startsWith("lock:")).toList();
    }

    // ================================================================ 第 1 – 6 行：入口检查

    @Test
    void 第1行_会话没有绑定玩家_16004缺少玩家身份_什么都不读_不调battle() {
        open(X);

        WatchBattleResponse response = service.watch(session(0), X, Deadline.after(BUDGET_MS));

        assertRejected(response, 16004, NO_IDENTITY);
        assertThat(spectate.calls).as("没有身份就不碰存储（:512）").isEmpty();
        assertThat(players.reads).isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第2行_读票据与观战标记出错_16004服务器繁忙_不往下读锁() {
        online();
        open(X);
        spectate.faults.failNext("entry");

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(players.reads).as("入口脚本失败就此回包").isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第3行_持票_排队中_16014匹配中无法观战_先于锁检查_不调battle_不写标记() {
        online();
        open(X);
        enqueue(PLAYER);
        players.inBattle(PLAYER, true);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(players.reads).as("判定顺序：票据先于战斗锁——有票又有锁回的是 16014 不是 16015（:236）").isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第3行_matched与ready的票同样16014_开局后60秒的ready残留不自愈_BW1() {
        online();
        open(X);
        tickets.putTicket(PLAYER, new Ticket("t-matched", 1, 0, TicketState.MATCHED, clock.peekMs(), 1, "", 150_000, 0, 0, 0), 42_000);
        WatchBattleResponse matched = watch(X);
        tickets.putTicket(PLAYER, new Ticket("t-ready", 1, 0, TicketState.READY, clock.peekMs(), 1, "", 150_000, 0, 555, 0), 60_000);
        WatchBattleResponse ready = watch(0);

        assertRejected(matched, 16014, QUEUED);
        assertRejected(ready, 16014, QUEUED);
        assertThat(tickets.ticketOf(PLAYER)).as("163 不删票（不像 157 那样自愈 ready 残留）").isPresent();
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("queued", 2.0));

        // ready 票 60 s 到期之后才放行
        clock.advanceSeconds(61);
        assertAccepted(watch(X), X);
    }

    @Test
    void 第4行_读战斗锁出错_16004服务器繁忙() {
        online();
        open(X);
        players.failLock(PLAYER);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(players.reads).as("读锁失败就此回包，不去读在线目录").containsExactly("lock:1001");
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第5行_战斗锁存在_16015战斗尚未结束_不调battle_不写标记() {
        online();
        open(X);
        players.inBattle(PLAYER, true);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16015, IN_BATTLE);
        assertThat(observers.calls).as(":253").isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("in_battle", 1.0));
    }

    // ================================================================ 第 7 行：已有标记

    @Test
    void 第7行_脏标记_按读到的原串删_继续往下判_照常成功_不发RemoveObserver() {
        online();
        open(X);
        // 基线那种只有 battle_id 的旧值在 Java 也是脏值（:527）
        spectate.putMark(PLAYER, "not-a-battle-id");

        WatchBattleResponse response = watch(X);

        assertAccepted(response, X);
        assertThat(spectate.calls).as("按原串删").contains("release(1001,not-a-battle-id)");
        assertThat(callKinds()).as("脏标记不知道指向哪一场：不发 Remove").containsExactly(Kind.ADD);
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(evictions("rewatch", "invalid_mark")).isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第7行_基线形状的旧标记_只有battle_id没有nonce_也是脏值_不拿它当旧场去清退() {
        online();
        open(X);
        open(Y);
        spectate.putMark(PLAYER, Long.toUnsignedString(X));

        WatchBattleResponse response = watch(Y);

        assertAccepted(response, Y);
        assertThat(observers.removes()).isEmpty();
        assertThat(evictions("rewatch", "invalid_mark")).isEqualTo(1.0);
        assertThat(markedBattle()).contains(Y);
    }

    @Test
    void 第7行_换场_旧场落点在_同步RemoveObserver_rewatch_发往旧场落点_完成后才删旧标记再登记新场() {
        online();
        BattlePlacement old = open(X);
        BattlePlacement fresh = open(Y);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);

        // 预算给得宽（10 s），下面对「3 s」的断言才不受机器快慢影响；生产是 4.5 s，同一个公式
        WatchBattleResponse response = watch(Y, 10_000);

        assertAccepted(response, Y);
        assertThat(callKinds()).as("换场的 Remove 先于 Add（:410）").containsExactly(Kind.REMOVE, Kind.ADD);
        Call remove = observers.calls.get(0);
        assertThat(remove.placement()).as("直拨旧场落点记录里的地址").isEqualTo(old);
        assertThat(remove.observerId()).isEqualTo(PLAYER);
        assertThat(remove.reason()).isEqualTo("rewatch");
        assertThat(remove.timeout()).as("min(3 s, 剩余 − 1.2 s)：剩余充足时就是 3 s").isEqualTo(Duration.ofSeconds(3));
        assertThat(remove.hardStopRemainingMs()).as("硬截止 = 请求截止 − 1200 ms：不会比它晚").isBetween(3_000L, 10_000L - 1_200);
        assertThat(observers.calls.get(1).placement()).isEqualTo(fresh);
        assertThat(events).as("Remove → 按值删旧标记 → 抢新标记 → Add")
                .containsSubsequence("observer.remove:" + X + ":1001:rewatch", "spectate.release:1001", "spectate.acquire:1001",
                        "observer.add:" + Y + ":1001");
        assertThat(spectate.calls).contains("release(1001," + oldMark + ")");
        assertThat(spectate.markOf(PLAYER)).contains(markValue(Y, 1));
        assertThat(evictions("rewatch", "removed")).isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(1.0);
        assertThat(outcomes()).as("换场成功只计 ok：already_watching 不再把懒清退算进去（W14）").isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第7行_换场的Remove没返回之前_不发Add_不删旧标记_不抢新标记() throws Exception {
        online();
        open(X);
        open(Y);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);
        observers.hangRemove();

        CompletableFuture<WatchBattleResponse> watching = async(() -> watch(Y));
        awaitUntil(() -> observers.removes().size() == 1, "旧场的 Remove 已经发出");
        // 给「不等 Remove 就往下走」的写法留出抢跑的时间：正确的实现这期间什么都不会做
        Thread.sleep(200);

        assertThat(observers.adds()).as("Remove 还在途：随机模式可能重挑同一场，迟到的 Remove 会把刚登记的观众摘掉").isEmpty();
        assertThat(spectate.markOf(PLAYER)).as("旧标记还在").contains(oldMark);
        assertThat(watching).isNotDone();

        observers.releaseRemove();
        assertAccepted(watching.get(20, TimeUnit.SECONDS), Y);
        assertThat(callKinds()).containsExactly(Kind.REMOVE, Kind.ADD);
        assertThat(markedBattle()).contains(Y);
    }

    @Test
    void 第7行_随机模式有旧标记_一律先清退旧场_随后又挑中旧场就重新登记() {
        online();
        BattlePlacement only = open(X);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));

        WatchBattleResponse response = watch(0);

        assertAccepted(response, X);
        assertThat(callKinds()).as("随机模式不比较 battle_id：先 Remove(X)，再 Add(X)（基线 B-s11）").containsExactly(Kind.REMOVE, Kind.ADD);
        assertThat(observers.calls.get(0).reason()).isEqualTo("rewatch");
        assertThat(observers.calls.get(0).placement()).isEqualTo(only);
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(evictions("rewatch", "removed")).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第7行_显式重看同一场_只删旧标记_不发RemoveObserver_Add走battle的幂等分支_不计清退() {
        online();
        open(X);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);

        WatchBattleResponse response = watch(X);

        assertAccepted(response, X);
        assertThat(observers.removes()).as("发了 Remove 就会给仍活着的旧会话推一条假的 166（:431）").isEmpty();
        assertThat(callKinds()).containsExactly(Kind.ADD);
        assertThat(spectate.calls).as("旧标记按值删").contains("release(1001," + oldMark + ")");
        assertThat(spectate.markOf(PLAYER)).as("标记换成了本次请求的值").contains(markValue(X, 1));
        assertThat(evictionsTotal()).as("重看同一场不算清退").isZero();
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第7行_旧场已收尾没有落点_只删标记_不发RPC() {
        online();
        open(Y);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));

        WatchBattleResponse response = watch(Y);

        assertAccepted(response, Y);
        assertThat(observers.removes()).isEmpty();
        assertThat(evictions("rewatch", "no_record")).isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(1.0);
        assertThat(markedBattle()).contains(Y);
    }

    @Test
    void 第7行_读旧场落点出错或记录损坏_只记日志_不发RPC_标记照删_继续登记新场() {
        online();
        open(X);
        open(Y);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));
        placements.readFailed = true;

        WatchBattleResponse readFailed = watch(Y);

        assertAccepted(readFailed, Y);
        assertThat(observers.removes()).as("读不出旧场的地址：不发 RemoveObserver").isEmpty();
        assertThat(evictions("rewatch", "read_failed")).isEqualTo(1.0);

        // 旧场的记录损坏（:824）：同样只删标记
        placements.readFailed = false;
        placements.corrupt(Y);
        open(Z);
        WatchBattleResponse corrupt = watch(Z);

        assertAccepted(corrupt, Z);
        assertThat(observers.removes()).isEmpty();
        assertThat(evictions("rewatch", "read_failed")).isEqualTo(2.0);
        assertThat(markedBattle()).contains(Z);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 2.0));
    }

    @Test
    void 第7行_旧场的Remove没调通_只记日志与指标_旧标记照删_新场照常登记() {
        online();
        open(X);
        open(Y);
        open(Z);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));
        observers.nextRemove(new Outcome.Unknown("超时"), new Outcome.NotDelivered("连不上"), new Outcome.Dead());

        assertAccepted(watch(Y), Y);
        assertAccepted(watch(Z), Z);
        assertAccepted(watch(X), X);

        assertThat(callKinds()).containsExactly(Kind.REMOVE, Kind.ADD, Kind.REMOVE, Kind.ADD, Kind.REMOVE, Kind.ADD);
        assertThat(evictions("rewatch", "rpc_failed")).as("超时与没送达").isEqualTo(2.0);
        assertThat(evictions("rewatch", "no_record")).as("旧场所在的进程已被接手：视同那一场不在了").isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(3.0);
        assertThat(markedBattle()).contains(X);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 3.0));
    }

    @Test
    void J行_换场前剩余预算不足2_2秒_16004_什么都不做_旧标记原样保留_玩家仍在看旧场() {
        online();
        open(X);
        open(Y);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);

        WatchBattleResponse response = watch(Y, 1_500);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("不发 Remove，更不发 Add").isEmpty();
        assertThat(spectate.markOf(PLAYER)).as("旧标记原样").contains(oldMark);
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("release"));
        assertThat(evictionsTotal()).isZero();
        assertThat(outcomes()).as("预算不足计 internal，不是 overloaded").isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 预算不足以换场_但旧场已收尾不需要发Remove_照常删标记往下走() {
        online();
        open(Y);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));

        // 2.1 s < 2.2 s，但没有旧场的落点：不用发 Remove，只剩 Add 这一跳（需要 ≥ 1 s）
        WatchBattleResponse response = watch(Y, 2_100);

        assertAccepted(response, Y);
        assertThat(callKinds()).containsExactly(Kind.ADD);
        assertThat(evictions("rewatch", "no_record")).isEqualTo(1.0);
    }

    @Test
    void 第7行_换场时删旧标记失败_16004_不带着没删掉的旧标记往下走_另尽力异步再删一次() {
        online();
        open(X);
        open(Y);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);
        spectate.faults.failNext("release");

        WatchBattleResponse response = watch(Y);

        assertRejected(response, 16004, BUSY);
        assertThat(callKinds()).as("旧场已同步清退；往下走的话抢占会被自己没删掉的旧标记挡成一条误导的 16016").containsExactly(Kind.REMOVE);
        assertThat(spectate.calls).as("旧场的名单里已经没有他：标记只是残留，再尽力删一次").contains("releaseAsync(1001," + oldMark + ")");
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("acquire"));
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第7行_重看同一场时删旧标记失败_16004_旧标记原样留着_不补发异步删除() {
        online();
        open(X);
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);
        spectate.faults.failNext("release");

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("重看同一场不发 Remove，也没有走到 Add").isEmpty();
        assertThat(spectate.calls).as("他仍登记在 X：标记删掉的话开局清退就摘不到他了（基线在同样的故障下标记也还在）")
                .noneMatch(call -> call.startsWith("releaseAsync"));
        assertThat(spectate.markOf(PLAYER)).contains(oldMark);
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("acquire"));
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 判定顺序_旧标记的清退先于在线检查_不在线的玩家旧场照样被清退再回16019() {
        open(X);
        open(Y);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));
        // 没有调 online()：在线目录没有条目

        WatchBattleResponse response = watch(Y);

        assertRejected(response, 16019, OFFLINE);
        assertThat(callKinds()).as("第 7 行在第 8、9 行之前").containsExactly(Kind.REMOVE);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(players.reads).containsExactly("lock:1001", "presence:1001");
        assertThat(outcomes()).isEqualTo(Map.of("offline", 1.0));
    }

    // ================================================================ 第 8 – 10 行：在线目录

    @Test
    void 第8行_读在线目录出错_16004服务器繁忙() {
        online();
        open(X);
        players.failPresence(PLAYER);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第9行_在线目录没有条目_16019会话不在线_没进游戏与断线等重连都是() {
        open(X);

        WatchBattleResponse neverOnline = watch(X);
        players.online(PLAYER, 1, 7);
        players.disconnected(PLAYER);
        WatchBattleResponse disconnected = watch(0);

        assertRejected(neverOnline, 16019, OFFLINE);
        assertRejected(disconnected, 16019, OFFLINE);
        assertThat(observers.calls).as(":268、:716").isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).as("不在线就不选场").noneMatch(call -> call.startsWith("read") || call.startsWith("pickRandom"));
        assertThat(outcomes()).isEqualTo(Map.of("offline", 2.0));
    }

    @Test
    void 第10行_在线目录条目缺gate实例_16004服务器繁忙() {
        open(X);
        players.presence(PLAYER, PlayerPresence.newBuilder().setPlayerId(PLAYER).setZoneId(1).setGateNodeId(1).setSessionId(7).build());

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("没有 gate 实例的路由 battle 会回 1005；这里先拦住（:543）").isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    // ================================================================ 第 15 行：成功

    @Test
    void 第15行_成功_只带battle_id_路由四个字段都取在线目录_scene字段为空_observer_name是账号_标记先于登记() {
        // 会话上下文里是 zone 1，在线目录里是 zone 2 的 1 号 gate：路由取在线目录（W11；两个 zone 都有 1 号 gate，只凭节点号会推错）
        players.presence(PLAYER, PlayerPresence.newBuilder().setPlayerId(PLAYER).setZoneId(2).setGateNodeId(1).setGateInstanceId("gate-inst-z2")
                .setSessionId(4242).setOwnerEpoch(9).build());
        BattlePlacement placement = open(X);
        AtomicReference<Optional<String>> markAtAdd = new AtomicReference<>();
        observers.beforeAdd = call -> markAtAdd.set(spectate.markOf(PLAYER));

        // 预算给得宽（10 s），下面对「3 s」的断言才不受机器快慢影响；生产是 4.5 s，同一个公式
        WatchBattleResponse response = watch(X, 10_000);

        assertAccepted(response, X);
        Call add = observers.calls.get(0);
        assertThat(observers.calls).hasSize(1);
        assertThat(add.kind()).isEqualTo(Kind.ADD);
        assertThat(add.placement()).as("发往落点记录").isEqualTo(placement);
        assertThat(add.request().getBattleId()).isEqualTo(X);
        assertThat(add.request().getObserverPlayerId()).isEqualTo(PLAYER);
        assertThat(add.request().getObserverName()).as("observer_name = SessionContext.account").isEqualTo("acc-1001");
        assertThat(add.request().getRouting()).as(":294").isEqualTo(BattleRouting.newBuilder().setSessionId(4242).setGateNodeId(1)
                .setGateInstanceId("gate-inst-z2").setZoneId(2).build());
        assertThat(add.request().getRouting().getSceneNodeId()).as("观众没有 scene 侧的状态：收不到结算").isZero();
        assertThat(add.request().getRouting().getSceneInstanceId()).isEmpty();
        assertThat(add.timeout()).as("min(3 s, 剩余 − 0.2 s)：剩余充足时就是 3 s").isEqualTo(Duration.ofSeconds(3));
        assertThat(add.hardStopRemainingMs()).as("硬截止 = 请求截止 − 200 ms：不会比它晚").isBetween(3_000L, 10_000L - 200);

        assertThat(markAtAdd.get()).as("标记先于 AddObserver 写入：之后才开始的 gather 一定读得到").contains(markValue(X, 1));
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(spectate.markTtlMs(PLAYER)).as("标记 TTL 360 s").isEqualTo(360_000);
        assertThat(events).containsSubsequence("spectate.entry:1001", "spectate.read:" + X, "spectate.acquire:1001", "observer.add:" + X + ":1001");
        assertThat(players.reads).as("入口读锁 → 读在线目录 → 登记后复查读锁（只此一次）").containsExactly("lock:1001", "presence:1001", "lock:1001");
        assertThat(tickets.calls).as("复查读一次票据").containsExactly("read(1001)");
        assertThat(placements.stored(X)).contains(placement);
        assertThat(spectate.isWatchable(X)).isTrue();
        assertThat(evictionsTotal()).isZero();
        assertThat(indexEvictions()).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第15行_随机模式_应答回填实际挑中的那一场() {
        online();
        open(X);

        WatchBattleResponse response = watch(0);

        assertAccepted(response, X);
        assertThat(markedBattle()).contains(X);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void battle_id超过有符号上限_标记与索引成员按无符号十进制_指定与随机都成() {
        online();
        open(HUGE);

        WatchBattleResponse explicit = watch(HUGE);
        WatchBattleResponse random = watch(0);

        assertAccepted(explicit, HUGE);
        assertAccepted(random, HUGE);
        assertThat(spectate.markOf(PLAYER)).hasValueSatisfying(mark -> assertThat(mark).startsWith("17293822569102704641:"));
        assertThat(observers.adds()).extracting(call -> call.request().getBattleId()).containsExactly(HUGE, HUGE);
    }

    @Test
    void Add的超时按剩余预算收短_min3秒与剩余减200毫秒() {
        online();
        open(X);

        WatchBattleResponse response = watch(X, 3_000);

        assertAccepted(response, X);
        Call add = observers.adds().get(0);
        assertThat(add.timeout().toMillis()).as("3 s 的预算：超时 = 剩余 − 200 ms（至多 2.8 s），不是整 3 s").isBetween(800L, 2_800L);
        assertThat(add.hardStopRemainingMs()).isBetween(800L, 2_800L);
    }

    // ================================================================ 第 11 行：选场循环里的依赖故障

    @Test
    void 第11行_随机选场出错_16004() {
        online();
        open(X);
        spectate.faults.failNext("pickRandom");

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第11行_原子读失败_16004_落点与索引都不动_W6() {
        online();
        BattlePlacement placement = open(X);
        spectate.faults.failNext("read");

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("基线这时按「未公开」继续（:112）；Java 的读是一段脚本，失败整体回 16004").isEmpty();
        assertThat(placements.stored(X)).contains(placement);
        assertThat(spectate.isWatchable(X)).isTrue();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第11行_落点记录损坏_16004_不剔除_计anomalies_随机模式挑中它同样当场16004_BW9() {
        online();
        open(X);
        placements.corrupt(X);

        WatchBattleResponse explicit = watch(X);
        WatchBattleResponse random = watch(0);

        assertRejected(explicit, 16004, BUSY);
        assertRejected(random, 16004, BUSY);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.isWatchable(X)).as("损坏的记录不剔除：删掉它会毁掉补签的定位").isTrue();
        assertThat(placements.corrupted(X)).isTrue();
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(anomalies()).isEqualTo(Map.of("corrupt_record", 2.0));
        assertThat(outcomes()).isEqualTo(Map.of("internal", 2.0));
    }

    @Test
    void 第11行_抢标记出错_什么都没写_16004_不调AddObserver() {
        online();
        open(X);
        spectate.faults.failNext("acquire");

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).as("结局不明：按本次的值尽力异步释放").contains("releaseAsync(1001," + markValue(X, 1) + ")");
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void 第11行_抢标记结局不明_标记其实已写下_按本次的值尽力释放_不留一个没人认领的标记() {
        online();
        open(X);
        spectate.faults.failNext("acquire:after");

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16004, BUSY);
        assertThat(events).as("标记写下了，随后被按值删掉").containsSubsequence("spectate.acquire:1001", "spectate.release:1001");
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    // ================================================================ 第 12 行：指定场没有落点

    @Test
    void 第12行_指定场没有落点_索引里有残留成员_只摘成员_永不删落点_16018不存在或已结束_不调battle() {
        online();
        spectate.putWatchable(X, clock.peekMs());

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(observers.calls).as("记录都没有就不该打 battle（:565、:175）").isEmpty();
        assertThat(spectate.calls).as("用的是「落点仍不存在才摘成员」的模式").contains("evict(Missing[battleId=" + X + "])");
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict(Dead") || call.startsWith("evict(Stale"));
        assertThat(spectate.isWatchable(X)).isFalse();
        assertThat(events).as("不删任何落点").noneMatch(event -> event.startsWith("placement."));
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(indexEvictions()).isEqualTo(Map.of("missing_record", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第12行_指定场没有落点也不在索引里_什么都不写_16018不存在或已结束() {
        online();

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.calls).as("未公开：不发任何剔除，免得误摘随后才公开的成员").noneMatch(call -> call.startsWith("evict"));
        assertThat(indexEvictions()).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第12行_读到没有落点之后gather才预写了记录_那条记录与索引成员都不受影响() {
        online();
        spectate.putWatchable(X, clock.peekMs());
        BattlePlacement prewritten = placement(X, clock.peekMs());
        // 读到「没有落点」之后、剔除到达存储之前，gather 预写了这一场的记录（一场正在建房的战斗，:175）
        HookedStore hooked = new HookedStore(spectate);
        hooked.beforeEvict = () -> placements.put(prewritten);
        WatchBattleService racing = service(hooked, players, Runnable::run);

        WatchBattleResponse response = racing.watch(session(PLAYER), X, Deadline.after(BUDGET_MS));

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(spectate.calls).as("剔除用的是「落点仍不存在才摘成员」的模式，条件在存储里原子判定").contains("evict(Missing[battleId=" + X + "])");
        assertThat(placements.stored(X)).as("读之后才预写的记录不能删").contains(prewritten);
        assertThat(spectate.isWatchable(X)).as("落点已经在了：成员也留着").isTrue();
        assertThat(events).noneMatch(event -> event.startsWith("placement.evict") || event.startsWith("placement.delete"));
        assertThat(observers.calls).isEmpty();
        assertThat(indexEvictions()).as("存储回报没有摘到东西：不计数").isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    /** 原样转发的观战存储，只在剔除到达存储之前多调一次钩子（模拟读与剔除之间别的写者动了数据）。 */
    private static final class HookedStore implements SpectateStore {
        private final SpectateStore delegate;
        volatile Runnable beforeEvict;

        HookedStore(SpectateStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Entry entry(long playerId, Deadline d) {
            return delegate.entry(playerId, d);
        }

        @Override
        public Acquire acquire(long playerId, String markValue, Deadline d) {
            return delegate.acquire(playerId, markValue, d);
        }

        @Override
        public boolean release(long playerId, String markValue, Deadline d) {
            return delegate.release(playerId, markValue, d);
        }

        @Override
        public void releaseAsync(long playerId, String markValue) {
            delegate.releaseAsync(playerId, markValue);
        }

        @Override
        public Map<Long, String> marksOf(List<Long> playerIds, Deadline d) {
            return delegate.marksOf(playerIds, d);
        }

        @Override
        public Snapshot read(long battleId, Deadline d) {
            return delegate.read(battleId, d);
        }

        @Override
        public Pick pickRandom(double r, Deadline d) {
            return delegate.pickRandom(r, d);
        }

        @Override
        public boolean evict(Eviction e, Deadline d) {
            Runnable hook = beforeEvict;
            if (hook != null) {
                hook.run();
            }
            return delegate.evict(e, d);
        }

        @Override
        public void evictAsync(List<Eviction> batch) {
            delegate.evictAsync(batch);
        }

        @Override
        public boolean publish(BattlePlacement placement, Deadline d) {
            return delegate.publish(placement, d);
        }

        @Override
        public Listed list(int limit, Deadline d) {
            return delegate.list(limit, d);
        }

        @Override
        public Map<Long, Record> readPlacements(List<Long> battleIds, Deadline d) {
            return delegate.readPlacements(battleIds, d);
        }

        @Override
        public long sweep(Deadline d) {
            return delegate.sweep(d);
        }

        @Override
        public long watchableCount(Deadline d) {
            return delegate.watchableCount(d);
        }
    }

    // ================================================================ 第 13 行：抢标记

    @Test
    void 第13行_抢标记时被并发的另一条163占着_16016已在观战另一场战斗_不调battle_对方的标记不动() {
        online();
        open(X);
        String theirs = SpectateRules.encodeMark(Y, "bbbbbbbbbbbbbbbb");
        // 入口检查之后、抢占之前，同一玩家的另一条 163 抢先写了标记（基线 beforeAcquireWatchingHook，:694）
        spectate.beforeAcquire = playerId -> spectate.putMark(playerId, theirs);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16016, ALREADY);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.markOf(PLAYER)).as("按值删：对方刚抢到的标记不归本次请求删（W3）").contains(theirs);
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("release"));
        assertThat(outcomes()).as("already_watching 只在 16016 时计数（W14）").isEqualTo(Map.of("already_watching", 1.0));
    }

    @Test
    void 第13行_W2_入口检查之后才建出票据_抢标记回有票_16014_不调AddObserver_没有留下标记() {
        online();
        open(X);
        spectate.beforeAcquire = this::enqueue;

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(observers.calls).as("基线这里会先登记观众、推一条 177，再靠复查清退").isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).as("先按本次的值释放一次，再回 16014").contains("release(1001," + markValue(X, 1) + ")");
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第13行_抢标记回有票但首轮其实已写入标记_命令被重发_按本次的值释放之后才回16014() {
        online();
        open(X);
        // 模拟 Redis 客户端重发：首轮已经写下本次的标记，两轮之间建出了票据，第二轮看到的是「有票」
        spectate.beforeAcquire = playerId -> {
            spectate.putMark(playerId, markValue(X, 1));
            enqueue(playerId);
        };

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(spectate.markOf(PLAYER)).as("首轮写下的标记不能残留").isEmpty();
        assertThat(events).contains("spectate.release:1001");
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第13行_重看同一场_删掉旧标记之后才建出票据_抢标记回有票_补一条自我清退_否则开局清退摘不到仍登记着的他() {
        online();
        BattlePlacement placement = open(X);
        // 玩家正登记在 X 的观众名单里，旧标记指着它
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);
        // 入口读到「无票、有旧标记」→ 同场分支只删标记、不发 Remove → 这之后 157 才建出票据
        spectate.beforeAcquire = this::enqueue;

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).as("不调 AddObserver；但旧标记已删、观众登记还在，开局清退只认标记——补一条自我清退（基线在同一交错下靠复查摘掉他）")
                .containsExactly(Kind.REMOVE_ASYNC);
        Call evict = observers.calls.get(0);
        assertThat(evict.placement()).as("发往这一场的落点").isEqualTo(placement);
        assertThat(evict.observerId()).isEqualTo(PLAYER);
        assertThat(evict.reason()).isEqualTo("concurrent_queue");
        assertThat(spectate.markOf(PLAYER)).as("新旧标记都不留").isEmpty();
        assertThat(spectate.calls).as("旧标记按值删；本次的值也按值释放一次（命令被重发时首轮可能已写入）")
                .contains("release(1001," + oldMark + ")", "release(1001," + markValue(X, 1) + ")");
        assertThat(events).as("入口先删旧标记，抢占回有票之后才自我清退")
                .containsSubsequence("spectate.release:1001", "observer.removeAsync:" + X + ":1001:concurrent_queue");
        assertThat(evictions("concurrent_queue", "removed")).isEqualTo(1.0);
        assertThat(evictionsTotal()).as("重看同一场本身不算清退；只有这一条自我清退").isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第13行_重看同一场时补发的自我清退没调通_只记指标_应答与标记不变() {
        online();
        open(X);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));
        spectate.beforeAcquire = this::enqueue;
        observers.nextRemove(new Outcome.Unknown("超时"));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).containsExactly(Kind.REMOVE_ASYNC);
        assertThat(evictions("concurrent_queue", "rpc_failed")).isEqualTo(1.0);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第13行_换场时抢标记回有票_旧场已在入口同步清退_不再补发自我清退() {
        online();
        BattlePlacement old = open(X);
        open(Y);
        spectate.putMark(PLAYER, SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa"));
        spectate.beforeAcquire = this::enqueue;

        WatchBattleResponse response = watch(Y);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).as("旧场 X 在入口已同步 Remove；新场 Y 从没登记过，没有什么可清退的").containsExactly(Kind.REMOVE);
        assertThat(observers.calls.get(0).placement()).isEqualTo(old);
        assertThat(observers.calls.get(0).reason()).isEqualTo("rewatch");
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(evictions("rewatch", "removed")).isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 抢标记被重放_命中自己首轮写下的值_回ok_照常登记_不误报16016() {
        online();
        open(X);
        spectate.beforeAcquire = playerId -> spectate.putMark(playerId, markValue(X, 1));

        WatchBattleResponse response = watch(X);

        assertAccepted(response, X);
        assertThat(callKinds()).containsExactly(Kind.ADD);
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    // ================================================================ 第 14 行：登记成功之后的复查

    @Test
    void 第14行_登记期间建出了票据_复查命中_异步自我清退_按值删标记_16014() {
        online();
        BattlePlacement placement = open(X);
        // AddObserver 在途期间并发的排队 / 即时开局建出了票据（:386）
        observers.beforeAdd = call -> enqueue(PLAYER);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
        Call evict = observers.calls.get(1);
        assertThat(evict.placement()).isEqualTo(placement);
        assertThat(evict.observerId()).isEqualTo(PLAYER);
        assertThat(evict.reason()).isEqualTo("concurrent_queue");
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).contains("release(1001," + markValue(X, 1) + ")");
        assertThat(spectate.isWatchable(X)).as("索引不动").isTrue();
        assertThat(evictions("concurrent_queue", "removed")).isEqualTo(1.0);
        assertThat(evictionsTotal()).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第14行_复查命中战斗锁_同样回16014_不是16015_BW2() {
        online();
        open(X);
        observers.beforeAdd = call -> players.inBattle(PLAYER, true);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).as(":763").containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(evictions("concurrent_queue", "removed")).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第14行_复查读锁出错_按有锁_自我清退_16014_BW2() {
        online();
        open(X);
        observers.beforeAdd = call -> players.failLock(PLAYER);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).as("读锁失败的方向是「有锁」：宁可多清退一个观众").containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第14行_复查读票出错_只记日志按无票_照常成功_标记保留() {
        online();
        open(X);
        tickets.faults.failNext("read");

        WatchBattleResponse response = watch(X);

        assertAccepted(response, X);
        assertThat(tickets.calls).as("复查确实读过票据（那一次失败了）").containsExactly("read(1001)");
        assertThat(callKinds()).as("读票失败的方向是「无票」：复查是尽力收窄，不引入新的失败面").containsExactly(Kind.ADD);
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第14行_复查读票出错而战斗锁在_仍然自我清退() {
        online();
        open(X);
        tickets.faults.failNext("read");
        observers.beforeAdd = call -> players.inBattle(PLAYER, true);

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
    }

    @Test
    void 第14行_自我清退的Remove发出即返回_应答不等它_它的结局稍后才记进指标() {
        online();
        open(X);
        observers.beforeAdd = call -> enqueue(PLAYER);
        observers.hangRemoveAsync();

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(callKinds()).containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
        assertThat(spectate.markOf(PLAYER)).as("标记不等 Remove 的结果就删").isEmpty();
        assertThat(evictionsTotal()).as("Remove 还没有结局").isZero();

        observers.releaseRemoveAsync();

        assertThat(evictions("concurrent_queue", "removed")).isEqualTo(1.0);
        assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
    }

    @Test
    void 第14行_自我清退的Remove没调通_只记指标_应答不变() {
        online();
        open(X);
        observers.beforeAdd = call -> enqueue(PLAYER);
        observers.nextRemove(new Outcome.Unknown("超时"));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(evictions("concurrent_queue", "rpc_failed")).isEqualTo(1.0);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
    }

    @Test
    void 第14行_复查的读等到请求预算耗尽_读锁按失败即有锁_自我清退_16014_标记改走尽力的异步释放() throws Exception {
        online();
        open(X);
        // 第 2 次读锁（复查）挂住不返回：复查只等到请求截止
        StallingLock stalling = new StallingLock(players, 2);
        WatchBattleService parallel = service(spectate, stalling, Executors.newVirtualThreadPerTaskExecutor());
        try {
            long startedNanos = System.nanoTime();
            WatchBattleResponse response = parallel.watch(session(PLAYER), X, Deadline.after(2_500));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertRejected(response, 16014, QUEUED);
            assertThat(elapsedMillis).as("等到请求截止（2.5 s）才放弃，不会更早；上界只防无限等").isBetween(2_000L, 30_000L);
            assertThat(callKinds()).containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
            assertThat(spectate.calls).as("截止已过：不再同步等 Redis，尽力异步释放").contains("releaseAsync(1001," + markValue(X, 1) + ")");
            assertThat(spectate.calls).noneMatch(call -> call.startsWith("release("));
            assertThat(spectate.markOf(PLAYER)).isEmpty();
            assertThat(outcomes()).isEqualTo(Map.of("queued", 1.0));
        } finally {
            stalling.release();
        }
    }

    /** 第 {@code stallFrom} 次起读锁挂住（直到测试放行）的包装：模拟一次迟迟不返回的 Redis 读。 */
    private static final class StallingLock implements PlayerStatusReader {
        private final PlayerStatusReader delegate;
        private final int stallFrom;
        private final AtomicInteger lockReads = new AtomicInteger();
        private final CountDownLatch gate = new CountDownLatch(1);

        StallingLock(PlayerStatusReader delegate, int stallFrom) {
            this.delegate = delegate;
            this.stallFrom = stallFrom;
        }

        void release() {
            gate.countDown();
        }

        @Override
        public boolean inBattle(long playerId, Deadline d) {
            if (lockReads.incrementAndGet() >= stallFrom) {
                try {
                    gate.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.inBattle(playerId, d);
        }

        @Override
        public Optional<PlayerPresence> presence(long playerId, Deadline d) {
            return delegate.presence(playerId, d);
        }

        @Override
        public HolderRead location(long playerId, Deadline d) {
            return delegate.location(playerId, d);
        }
    }

    @Test
    void 复查时锁尚不存在_照常成功_只查一次不做二次复查_规格7_1第16条的既定结局() {
        online();
        open(X);

        WatchBattleResponse response = watch(X);
        // 切磋的备战晚于复查才写锁：复查读票、读锁都落空，玩家同时是 X 的观众与新局的参战者（两版相同）
        players.inBattle(PLAYER, true);

        assertAccepted(response, X);
        assertThat(lockReads()).as("入口一次、复查一次；没有「顺手」加的二次复查").hasSize(2);
        assertThat(tickets.calls).containsExactly("read(1001)");
        assertThat(observers.removes()).isEmpty();
        assertThat(spectate.markOf(PLAYER)).as("标记还在：下一次 163 或开局清退会摘掉这名观众").contains(markValue(X, 1));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 复查的两次读可以并行_各自在自己的线程上_结果与串行相同() {
        online();
        open(X);
        WatchBattleService parallel = service(spectate, players, Executors.newVirtualThreadPerTaskExecutor());

        WatchBattleResponse ok = parallel.watch(session(PLAYER), X, Deadline.after(BUDGET_MS));
        observers.beforeAdd = call -> enqueue(PLAYER);
        WatchBattleResponse hit = parallel.watch(session(PLAYER), X, Deadline.after(BUDGET_MS));

        assertAccepted(ok, X);
        assertRejected(hit, 16014, QUEUED);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0, "queued", 1.0));
    }

    /**
     * 规格 §4.4 / A.1 的 F9：复查<b>并行</b>再读票据与锁（Add 之后可能只剩约 0.2 s 预算，串行读会把读锁挤到超时而误清退）。
     * 两道闸互相等对方：读票要看到「复查的读锁已经发出」才返回，读锁要看到「读票已经发出」才返回——先读完一个再读另一个的写法
     * （不论哪个在前、在不在调用线程上）必有一道闸等不到。闸至多等 5 s、请求预算 30 s，不靠窄时间窗。
     */
    @Test
    void 复查的两次读是并行发出的_一次读还没回来另一次已经在读_串行的写法过不了这两道闸() {
        online();
        open(X);
        CountDownLatch ticketStarted = new CountDownLatch(1);
        CountDownLatch lockStarted = new CountDownLatch(1);
        AtomicReference<Boolean> ticketReadSawLockRead = new AtomicReference<>();
        AtomicReference<Boolean> lockReadSawTicketRead = new AtomicReference<>();
        AtomicInteger ticketReadCount = new AtomicInteger();
        AtomicInteger lockReadCount = new AtomicInteger();
        TicketReader gatedTickets = new TicketReader() {
            @Override
            public Optional<Ticket> read(long playerId, Deadline d) {
                ticketReadCount.incrementAndGet();
                ticketStarted.countDown();
                ticketReadSawLockRead.set(awaitGate(lockStarted));
                return tickets.read(playerId, d);
            }

            @Override
            public Map<Long, Ticket> readAll(Collection<Long> playerIds, Deadline d) {
                return tickets.readAll(playerIds, d);
            }

            @Override
            public Status status(long playerId, Deadline d) {
                return tickets.status(playerId, d);
            }
        };
        PlayerStatusReader gatedPlayers = new PlayerStatusReader() {
            @Override
            public boolean inBattle(long playerId, Deadline d) {
                if (lockReadCount.incrementAndGet() == 2) { // 第 1 次是入口检查，第 2 次才是复查
                    lockStarted.countDown();
                    lockReadSawTicketRead.set(awaitGate(ticketStarted));
                }
                return players.inBattle(playerId, d);
            }

            @Override
            public Optional<PlayerPresence> presence(long playerId, Deadline d) {
                return players.presence(playerId, d);
            }

            @Override
            public HolderRead location(long playerId, Deadline d) {
                return players.location(playerId, d);
            }
        };
        WatchBattleService parallel = new WatchBattleService(spectate, placements, gatedPlayers, gatedTickets, observers, metrics, () -> 0.0,
                () -> nonce(nonceSeq.incrementAndGet()), Executors.newVirtualThreadPerTaskExecutor());

        WatchBattleResponse response = parallel.watch(session(PLAYER), X, Deadline.after(30_000));

        assertAccepted(response, X);
        assertThat(ticketReadSawLockRead.get()).as("读票还没返回时，复查的读锁已经发出").isTrue();
        assertThat(lockReadSawTicketRead.get()).as("读锁还没返回时，读票已经发出").isTrue();
        assertThat(ticketReadCount).as("复查读一次票据").hasValue(1);
        assertThat(lockReadCount).as("入口一次、复查一次").hasValue(2);
        assertThat(callKinds()).as("两样都没命中：不自我清退").containsExactly(Kind.ADD);
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 1));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    /** 等一把闸，至多 5 s；被中断按没等到。 */
    private static boolean awaitGate(CountDownLatch gate) {
        try {
            return gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ================================================================ 第 16 行：房间不存在

    @Test
    void 第16行_指定场已公开_battle回1004_剔除落点与索引_回滚标记_16018不存在或已结束() {
        online();
        open(X);
        observers.nextAdd(new Outcome.Replied(1004));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(callKinds()).as(":326").containsExactly(Kind.ADD);
        assertThat(spectate.calls).as("按读到的 attempt 守护").contains("evict(Dead[battleId=" + X + ", attempt=1])");
        assertThat(placements.stored(X)).isEmpty();
        assertThat(spectate.isWatchable(X)).isFalse();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(events).as("先回滚标记，再剔除").containsSubsequence("spectate.release:1001", "spectate.evict:" + X);
        assertThat(indexEvictions()).isEqualTo(Map.of("room_missing", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第16行_随机模式唯一一场已收尾_剔除后第二轮没有可看的_16017() {
        online();
        open(X);
        observers.onAdd(X, new Outcome.Replied(1004));

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16017, NO_BATTLE);
        assertThat(observers.adds()).as("剔除之后第二轮挑不到任何场（:347）").hasSize(1);
        assertThat(placements.stored(X)).isEmpty();
        assertThat(spectate.watchable()).isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(indexEvictions()).isEqualTo(Map.of("room_missing", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("no_battle", 1.0));
    }

    @Test
    void 第16行_随机模式第一场已收尾_剔除后换第二场成功_应答与标记都是第二场() {
        online();
        open(X, -1_000); // 分数更低：r = 0 时先挑中它
        BattlePlacement second = open(Y);
        observers.onAdd(X, new Outcome.Replied(1004));

        WatchBattleResponse response = watch(0);

        assertAccepted(response, Y);
        assertThat(observers.adds()).extracting(Call::battleId).as(":605").containsExactly(X, Y);
        assertThat(observers.adds().get(1).placement()).isEqualTo(second);
        assertThat(spectate.watchable()).containsExactly(Long.toUnsignedString(Y));
        assertThat(spectate.markOf(PLAYER)).as("第一轮的标记已回滚；现在的标记是第二轮抢的").contains(markValue(Y, 2));
        assertThat(events).as("第一轮：抢标记 → Add(X) → 回滚 → 剔除；第二轮：抢标记 → Add(Y)").containsSubsequence(
                "spectate.acquire:1001", "observer.add:" + X + ":1001", "spectate.release:1001", "spectate.evict:" + X,
                "spectate.acquire:1001", "observer.add:" + Y + ":1001");
        assertThat(outcomes()).as("一次请求只记一个出口").isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第16行_随机模式最多两轮_两轮都挑中已收尾的场_即使还有别的场也回16017_BW7() {
        online();
        open(X, -2_000);
        open(Y, -1_000);
        open(Z);
        observers.onAdd(X, new Outcome.Replied(1004)).onAdd(Y, new Outcome.Replied(1004));

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16017, NO_BATTLE);
        assertThat(observers.adds()).extracting(Call::battleId).containsExactly(X, Y);
        assertThat(spectate.watchable()).as("第三场没有轮到").containsExactly(Long.toUnsignedString(Z));
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(indexEvictions()).isEqualTo(Map.of("room_missing", 2.0));
        assertThat(outcomes()).isEqualTo(Map.of("no_battle", 1.0));
    }

    @Test
    void 随机模式挑中没有落点的成员_只摘成员_换下一场() {
        online();
        spectate.putWatchable(X, clock.peekMs() - 1_000);
        open(Y);

        WatchBattleResponse response = watch(0);

        assertAccepted(response, Y);
        assertThat(observers.adds()).extracting(Call::battleId).as("没有落点的成员不打 battle（:640）").containsExactly(Y);
        assertThat(spectate.calls).contains("evict(Missing[battleId=" + X + "])");
        assertThat(spectate.watchable()).containsExactly(Long.toUnsignedString(Y));
        assertThat(indexEvictions()).isEqualTo(Map.of("missing_record", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    static Stream<Arguments> 建房窗口的各种情形() {
        long window = MatchBudgets.GATHER_CREATE_STAGE_WORST_MS;
        return Stream.of(
                Arguments.of("未公开且刚写入：可能在建，不剔除", false, 0L, false, true),
                Arguments.of("未公开、接近窗口上沿：仍不剔除", false, -(window - 2_000), false, true),
                Arguments.of("未公开、恰在窗口上沿：仍不剔除", false, -window, false, true),
                Arguments.of("created_at 晚于读的时刻（时钟偏差）：按窗口内处理", false, 5_000L, false, true),
                Arguments.of("未公开但出了窗口：gather 必已放弃，懒剔除", false, -(window + 1_000), false, false),
                Arguments.of("created_at_ms 为 0（不该出现的旧记录）：按窗口外处理，懒剔除", false, 0L, true, false),
                Arguments.of("已公开：房间在公开前就已建成，窗口内也懒剔除", true, 0L, false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("建房窗口的各种情形")
    void 第16行_battle回1004时的建房窗口判定(String name, boolean published, long createdOffsetMs, boolean zeroCreatedAt, boolean keep) {
        online();
        long createdAtMs = zeroCreatedAt ? 0 : clock.peekMs() + createdOffsetMs;
        BattlePlacement placement = placement(X, createdAtMs);
        placements.put(placement);
        if (published) {
            spectate.putWatchable(X, createdAtMs);
        }
        observers.nextAdd(new Outcome.Replied(1004));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(observers.adds()).as("两种情况对客户端都是「不存在」（:55）").hasSize(1);
        assertThat(spectate.markOf(PLAYER)).as("AddObserver 失败必须回滚标记").isEmpty();
        assertThat(placements.stored(X).isPresent()).as("落点是否保留").isEqualTo(keep);
        assertThat(spectate.isWatchable(X)).as("未公开的场本就不在索引里；已公开的被懒剔除").isFalse();
        assertThat(indexEvictions()).isEqualTo(keep ? Map.of() : Map.of("room_missing", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第16行_RPC在途期间开局成功并公开_判定以读落点那一刻为准_落点与索引成员都保留() {
        online();
        BattlePlacement placement = placement(X, clock.peekMs());
        placements.put(placement);
        // 读落点时未公开；AddObserver 在途期间 gather 开局成功并公开，回包仍是 1004（请求先于建房到达 battle，:96）
        observers.beforeAdd = call -> spectate.putWatchable(X, placement.getCreatedAtMs());
        observers.nextAdd(new Outcome.Replied(1004));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(placements.stored(X)).as("开局补写的记录必须保留（丢票补签靠它定位节点）").contains(placement);
        assertThat(spectate.isWatchable(X)).as("回包之后再查索引会看到「已公开」并把它删掉——判定输入必须取在读落点那一刻（W6）").isTrue();
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
        assertThat(spectate.markOf(PLAYER)).isEmpty();
    }

    @Test
    void 第16行_落点在读之后被改写到重试节点并公开_窗口内不剔除_记录仍指向重试节点() {
        online();
        long createdAtMs = clock.peekMs();
        placements.put(placement(X, createdAtMs));
        BattlePlacement rewritten = placement(X, createdAtMs).toBuilder().setBattleNodeId(8).setBattleInstanceId("inst-retry").setAttempt(2).build();
        // 读落点之后、发 AddObserver 之前：gather 把记录改写到重试节点、在那里建房并公开（:142）
        spectate.beforeAcquire = playerId -> {
            placements.put(rewritten);
            spectate.putWatchable(X, createdAtMs);
        };
        observers.nextAdd(new Outcome.Replied(1004));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(observers.adds()).singleElement().satisfies(add -> assertThat(add.placement().getBattleNodeId())
                .as("按读到的旧记录打到首选节点").isEqualTo(7));
        assertThat(placements.stored(X)).as("重试节点上进行中战斗的记录必须保留").contains(rewritten);
        assertThat(spectate.isWatchable(X)).isTrue();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
    }

    @Test
    void 第16行_W7_出了建房窗口才收到1004_但落点已被改写成新的attempt_剔除按attempt守护_什么都不动() {
        online();
        long createdAtMs = clock.peekMs() - MatchBudgets.GATHER_CREATE_STAGE_WORST_MS - 5_000;
        placements.put(placement(X, createdAtMs));
        BattlePlacement rewritten = placement(X, createdAtMs).toBuilder().setBattleNodeId(8).setBattleInstanceId("inst-retry").setAttempt(2).build();
        observers.beforeAdd = call -> {
            placements.put(rewritten);
            spectate.putWatchable(X, createdAtMs);
        };
        observers.nextAdd(new Outcome.Replied(1004));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(spectate.calls).as("窗口判定说可以剔除，剔除带着读到的 attempt = 1").contains("evict(Dead[battleId=" + X + ", attempt=1])");
        assertThat(placements.stored(X)).as("落点的 attempt 已是 2：被改写过就不动（纵深防御）").contains(rewritten);
        assertThat(spectate.isWatchable(X)).isTrue();
        assertThat(indexEvictions()).as("存储回报没有摘到东西：不计数").isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第16行_剔除失败只记日志_应答照旧_随机模式照样进下一轮() {
        online();
        open(X, -1_000);
        open(Y);
        observers.onAdd(X, new Outcome.Replied(1004));
        spectate.faults.failNext("evict");
        // X 没摘掉还在索引里：第一轮 r = 0 挑中 X，第二轮给一个靠后的 r 挑中 Y
        randoms.add(0.0);
        randoms.add(0.99);

        WatchBattleResponse response = watch(0);

        assertAccepted(response, Y);
        assertThat(spectate.isWatchable(X)).as("这一次没摘掉，留给清扫与之后的 163 / 164").isTrue();
        assertThat(placements.stored(X)).isPresent();
        assertThat(indexEvictions()).isEmpty();
        assertThat(observers.adds()).extracting(Call::battleId).containsExactly(X, Y);
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 第16行_随机换场前回滚本轮标记失败_16004_不带着没删掉的标记进下一轮() {
        online();
        open(X, -1_000);
        open(Y);
        observers.onAdd(X, new Outcome.Replied(1004));
        spectate.faults.failNext("release");

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.adds()).as("下一轮的抢占会被自己没删掉的标记挡成一条误导的 16016：不进下一轮").extracting(Call::battleId).containsExactly(X);
        assertThat(spectate.calls).as("同步释放失败之后尽力异步再发一次").contains("releaseAsync(1001," + markValue(X, 1) + ")");
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    // ================================================================ W5：直拨判死视同 1004；没送达 / 结局不明

    @Test
    void W5_AddObserver判死_指定场已公开_视同1004_剔除_16018不存在或已结束() {
        online();
        open(X);
        observers.nextAdd(new Outcome.Dead());

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(placements.stored(X)).isEmpty();
        assertThat(spectate.isWatchable(X)).isFalse();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(indexEvictions()).as("依据是直拨判死").isEqualTo(Map.of("dead_node", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void W5_AddObserver判死_随机模式_剔除死节点上的场_换下一场() {
        online();
        open(X, -1_000);
        open(Y);
        observers.onAdd(X, new Outcome.Dead());

        WatchBattleResponse response = watch(0);

        assertAccepted(response, Y);
        assertThat(observers.adds()).extracting(Call::battleId).containsExactly(X, Y);
        assertThat(spectate.watchable()).as("死节点上的场不再干扰随机观战").containsExactly(Long.toUnsignedString(Y));
        assertThat(indexEvictions()).isEqualTo(Map.of("dead_node", 1.0));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void W5_AddObserver判死_但指定场未公开且在建房窗口内_不剔除_与1004同走窗口判定() {
        online();
        BattlePlacement placement = placement(X, clock.peekMs() - 3_000);
        placements.put(placement);
        observers.nextAdd(new Outcome.Dead());

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_FOUND);
        assertThat(placements.stored(X)).as("首选节点刚死、gather 可能正在改写到重试节点：记录不能删").contains(placement);
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(indexEvictions()).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("not_found", 1.0));
    }

    @Test
    void 第17行_AddObserver确定没送达_回滚标记_16018当前无法观战_索引与落点不动_随机也不换场() {
        online();
        BattlePlacement first = open(X, -1_000);
        open(Y);
        observers.nextAdd(new Outcome.NotDelivered("建连失败；目录=ABSENT"));

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16018, NOT_WATCHABLE);
        assertThat(observers.adds()).as("号没被接手的死节点与基线相同：当前无法观战、不剔除、不换场（:367）").extracting(Call::battleId).containsExactly(X);
        assertThat(spectate.markOf(PLAYER)).as("battle 一定没有登记：回滚").isEmpty();
        assertThat(placements.stored(X)).contains(first);
        assertThat(spectate.watchable()).hasSize(2);
        assertThat(indexEvictions()).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("rejected", 1.0));
    }

    @Test
    void 第17行_W4_AddObserver结局不明_保留标记_不补发Remove_16018当前无法观战() {
        online();
        open(X);
        observers.nextAdd(new Outcome.Unknown("TIMEOUT: 本地等待超时"));

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16018, NOT_WATCHABLE);
        assertThat(spectate.markOf(PLAYER)).as("battle 可能已登记这名观众：标记留着，开局清退才摘得到他").contains(markValue(X, 1));
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("release"));
        assertThat(callKinds()).as("不补发 RemoveObserver：它可能先于在途的 Add 到达").containsExactly(Kind.ADD);
        assertThat(spectate.isWatchable(X)).isTrue();
        assertThat(indexEvictions()).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("rejected", 1.0));
    }

    @Test
    void W4_结局不明之后再发163同一场_只删标记_不发Remove_Add走幂等_成功() {
        online();
        open(X);
        observers.nextAdd(new Outcome.Unknown("超时"));
        assertRejected(watch(X), 16018, NOT_WATCHABLE);

        WatchBattleResponse again = watch(X);

        assertAccepted(again, X);
        assertThat(callKinds()).as("两次都只有 Add").containsExactly(Kind.ADD, Kind.ADD);
        assertThat(spectate.calls).as("上一次留下的标记按值删").contains("release(1001," + markValue(X, 1) + ")");
        assertThat(spectate.markOf(PLAYER)).contains(markValue(X, 2));
        assertThat(evictionsTotal()).isZero();
        assertThat(outcomes()).isEqualTo(Map.of("rejected", 1.0, "ok", 1.0));
    }

    @ParameterizedTest(name = "battle 回 {0}")
    @MethodSource("battle的其它拒绝码")
    void 第17行_battle的其它拒绝_当场16018当前无法观战_回滚标记_索引不动_随机模式也不换场_BW6(int tip) {
        online();
        open(X, -1_000);
        open(Y);
        observers.nextAdd(new Outcome.Replied(tip));

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16018, NOT_WATCHABLE);
        assertThat(observers.adds()).as("不重试、不换场（:661、:898）").extracting(Call::battleId).containsExactly(X);
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.watchable()).hasSize(2);
        assertThat(placements.stored(X)).isPresent();
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
        assertThat(outcomes()).isEqualTo(Map.of("rejected", 1.0));
    }

    static Stream<Arguments> battle的其它拒绝码() {
        // 1005 观众是参战者 / 参数；1008 观众已满；1003 签不出票；外加一个没见过的码：只有 1004 是「房间不在」
        return Stream.of(Arguments.of(1005), Arguments.of(1008), Arguments.of(1003), Arguments.of(9999));
    }

    // ================================================================ 第 18 行与随机选场

    @Test
    void 第18行_随机模式没有可观战的战斗_16017_不调battle_不写标记() {
        online();

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16017, NO_BATTLE);
        assertThat(observers.calls).as(":280").isEmpty();
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(spectate.calls).filteredOn(call -> call.startsWith("pickRandom")).as("挑不到就跳出循环，不进第二轮").hasSize(1);
        assertThat(outcomes()).isEqualTo(Map.of("no_battle", 1.0));
    }

    @Test
    void 第18行_索引里只有过期成员_挑不中它_16017_不现场剔除_W8() {
        online();
        BattlePlacement stale = open(X, -(MatchBudgets.SPECTATE_STALE_MS + 1_000));

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16017, NO_BATTLE);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.isWatchable(X)).as("过期成员留给清扫；读路径只是挑不中它").isTrue();
        assertThat(placements.stored(X)).contains(stale);
        assertThat(spectate.calls).noneMatch(call -> call.startsWith("evict"));
    }

    @Test
    void 随机选场_非法成员按原串剔除后重挑_挑到合法的就用() {
        online();
        spectate.putWatchable("abc", clock.peekMs() - 3_000);
        spectate.putWatchable("007", clock.peekMs() - 2_000);
        open(X);

        WatchBattleResponse response = watch(0);

        assertAccepted(response, X);
        assertThat(spectate.calls).as("按原串摘：\"007\" 当成 7 号战斗去摘的话永远摘不掉它")
                .contains("evict(Invalid[member=abc])", "evict(Invalid[member=007])");
        assertThat(spectate.watchable()).containsExactly(Long.toUnsignedString(X));
        assertThat(indexEvictions()).isEqualTo(Map.of("invalid_member", 2.0));
        assertThat(outcomes()).isEqualTo(Map.of("ok", 1.0));
    }

    @Test
    void 随机选场_一轮至多三挑_三挑都是非法成员就算挑不到_16017() {
        online();
        spectate.putWatchable("abc", clock.peekMs() - 4_000);
        spectate.putWatchable("-5", clock.peekMs() - 3_000);
        spectate.putWatchable("1 2", clock.peekMs() - 2_000);
        open(X);

        WatchBattleResponse response = watch(0);

        assertRejected(response, 16017, NO_BATTLE);
        assertThat(spectate.calls).filteredOn(call -> call.startsWith("pickRandom")).as(":839").hasSize(3);
        assertThat(observers.calls).isEmpty();
        assertThat(spectate.watchable()).as("三个非法成员都摘了，合法的那一场还在").containsExactly(Long.toUnsignedString(X));
        assertThat(indexEvictions()).isEqualTo(Map.of("invalid_member", 3.0));
        assertThat(outcomes()).isEqualTo(Map.of("no_battle", 1.0));
    }

    @Test
    void 随机选场_r由服务给存储_按未过期成员的次序取() {
        online();
        open(X, -2_000);
        open(Y, -1_000);
        open(Z);
        randoms.add(0.5);

        WatchBattleResponse response = watch(0);

        assertAccepted(response, Y);
        assertThat(spectate.calls).contains("pickRandom(0.5)");
    }

    // ================================================================ J 行与 J2

    @Test
    void J行_Add之前剩余预算不足1秒_回滚标记_16004_不发AddObserver() {
        online();
        open(X);

        WatchBattleResponse response = watch(X, 600);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("不去发一个注定超时的调用").isEmpty();
        assertThat(events).as("标记抢到了，随即按值回滚").containsSubsequence("spectate.acquire:1001", "spectate.release:1001");
        assertThat(spectate.markOf(PLAYER)).isEmpty();
        assertThat(outcomes()).as("预算不足计 internal").isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void J行_重看同一场_Add之前剩余预算不足1秒_16004_保留刚抢到的标记_仍登记着的他才摘得到() {
        online();
        open(X);
        // 玩家正登记在 X 的观众名单里；同场分支在入口只删旧标记、不发 Remove
        String oldMark = SpectateRules.encodeMark(X, "aaaaaaaaaaaaaaaa");
        spectate.putMark(PLAYER, oldMark);

        // 900 ms < 1 s：必定走 J 行；又留足时间让入口的几步内存操作在截止之前做完
        WatchBattleResponse response = watch(X, 900);

        assertRejected(response, 16004, BUSY);
        assertThat(observers.calls).as("不发 AddObserver，也不发 Remove（那会给仍活着的旧会话推一条假的 166）").isEmpty();
        assertThat(spectate.markOf(PLAYER)).as("回滚的话就成了「在名单、无标记」，开局清退摘不到他：标记留着（W4 的口径）").contains(markValue(X, 1));
        assertThat(spectate.calls).filteredOn(call -> call.startsWith("release")).as("只按值删了旧标记，本次的值没有释放")
                .containsExactly("release(1001," + oldMark + ")");
        assertThat(evictionsTotal()).isZero();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void J2_登记之前出了未预期的异常_原样抛给派发器_已抢到的标记按值尽力释放_计internal() {
        online();
        open(X);
        observers.beforeAdd = call -> {
            throw new IllegalStateException("注入的 bug：AddObserver 途中抛出");
        };

        assertThatThrownBy(() -> watch(X)).isInstanceOf(IllegalStateException.class).hasMessageContaining("注入的 bug");

        assertThat(events).contains("spectate.acquire:1001");
        assertThat(spectate.calls).contains("releaseAsync(1001," + markValue(X, 1) + ")");
        assertThat(spectate.markOf(PLAYER)).as("已抢到的标记不留给 TTL").isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    @Test
    void J2_还没抢标记就出了未预期的异常_照样抛出_没有可释放的标记_计internal() {
        online();
        open(X);
        spectate.faults.failNext("entry", new IllegalStateException("注入的 bug：不是依赖异常"));

        assertThatThrownBy(() -> watch(X)).isInstanceOf(IllegalStateException.class);

        assertThat(spectate.calls).noneMatch(call -> call.startsWith("release"));
        assertThat(observers.calls).isEmpty();
        assertThat(outcomes()).isEqualTo(Map.of("internal", 1.0));
    }

    // ================================================================ W3：按值删

    static Stream<Arguments> 会回滚标记的四条出口() {
        return Stream.of(
                Arguments.of("battle 回 1004", new Outcome.Replied(1004), 16018),
                Arguments.of("battle 明确拒绝", new Outcome.Replied(1008), 16018),
                Arguments.of("直拨判死", new Outcome.Dead(), 16018),
                Arguments.of("确定没送达", new Outcome.NotDelivered("连不上"), 16018));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("会回滚标记的四条出口")
    void W3_回滚只删自己写下的值_标记期间换成了别的请求的值_不被本次的回滚删掉(String name, Outcome outcome, int code) {
        online();
        open(X);
        String theirs = SpectateRules.encodeMark(X, "cccccccccccccccc");
        // AddObserver 在途期间：本次的标记没了（被开局清退按值删掉），同一玩家的另一条 163 抢到了新的标记
        observers.beforeAdd = call -> spectate.putMark(PLAYER, theirs);
        observers.nextAdd(outcome);

        WatchBattleResponse response = watch(X);

        assertThat(response.getErrorMessage().getId()).isEqualTo(code);
        assertThat(spectate.calls).as("回滚带的是本次的值").contains("release(1001," + markValue(X, 1) + ")");
        assertThat(spectate.markOf(PLAYER)).as("无条件 DEL 会把对方刚抢到的标记删掉（基线 wb.go:237）").contains(theirs);
    }

    @Test
    void W3_复查命中后的自我清退同样按值删_别的请求的标记不动() {
        online();
        open(X);
        String theirs = SpectateRules.encodeMark(Y, "cccccccccccccccc");
        observers.beforeAdd = call -> {
            spectate.putMark(PLAYER, theirs);
            enqueue(PLAYER);
        };

        WatchBattleResponse response = watch(X);

        assertRejected(response, 16014, QUEUED);
        assertThat(spectate.markOf(PLAYER)).contains(theirs);
    }

    // ================================================================ 小工具

    private static <T> CompletableFuture<T> async(Supplier<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofPlatform().daemon(true).name("test-watch").start(() -> {
            try {
                future.complete(body.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < giveUp) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
