package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import com.game.match.support.MatchTips;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakeObserverDialer.Call;
import com.game.match.testing.FakeObserverDialer.Kind;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketRedisFixture;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.ListWatchableBattlesResponse;
import com.game.proto.match.WatchBattleResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;

/**
 * 观战各流程<b>拼在真存储上</b>（批次 6.5 集成；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13）。
 *
 * <p>各工作包自己的测试是分开钉的：存储的脚本语义由 {@code SpectateStoreContract} 在内存替身与真 Redis 上各跑一遍；163（{@link WatchBattleService}）、
 * 164（{@link WatchableListService}）、开局钩子（{@link SpectateGatherHooks}）、清扫器（{@link SpectateSweeper}）的判定流程对着内存替身钉。
 * 这里把<b>真的流程对象接在真的 {@link RedissonSpectateStore}、真的落点存储、真的票据存储上</b>走几条整链路，钉的是两边对接的地方——
 * 标记值的形状与 TTL、剔除模式与 attempt 守护、Redis 自己的时间（建房窗口、过期分界）、成员与标记「按原串交回去」、
 * 复查经票据的只读口读到真票据。仍是替身的只有两样进程外的东西：战斗锁 / 在线目录（{@link FakePlayerStatus}）与对 battle 的直拨
 * （{@link FakeObserverDialer}；真的直拨与结局分类由 {@code ObserverRpcLoopbackTest} 配真 Triple 钉）。
 *
 * <p>玩家号、战斗号是夹具随机分配的一段（都 ≥ 2^63），索引用夹具自己的那把键（{@link SpectateRedisFixture}：生产键是全局的，
 * 同库里别的用例会往里公开战斗）；只删自己写的键。时间一律取 Redis 的 {@code TIME}，断言不依赖本机时钟与窄的墙钟窗口。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class SpectateFlowRedisIntegrationTest {

    private static final String QUEUED = "匹配中无法观战";
    private static final String NOT_FOUND = "该战斗不存在或已结束";

    private static RedissonClient redis;

    private SpectateRedisFixture fx;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final FakeObserverDialer observers = new FakeObserverDialer(events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    /** 随机选场依次用到的 r；用完之后恒为 0（= 未过期成员里分数最低的那个）。 */
    private final Deque<Double> randoms = new ArrayDeque<>();
    private WatchBattleService watches;
    private WatchableListService lists;
    private SpectateGatherHooks hooks;

    @BeforeAll
    static void connect() {
        redis = TicketRedisFixture.connect();
    }

    @AfterAll
    static void shutdown() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        fx = new SpectateRedisFixture(redis);
        watches = service(players);
        lists = new WatchableListService(fx.store, metrics);
        hooks = new SpectateGatherHooks(fx.store, observers, metrics);
    }

    @AfterEach
    void cleanup() {
        fx.cleanup();
    }

    // ================================================================ 摆状态

    /** 真存储 + 真落点存储 + 真票据存储（它就是票据的只读口）；nonce 是生产的随机 nonce；复查的两次读在调用线程上依次做。 */
    private WatchBattleService service(PlayerStatusReader statusReader) {
        return new WatchBattleService(fx.store, fx.placements, statusReader, fx.tickets.store, observers, metrics,
                () -> randoms.isEmpty() ? 0.0 : randoms.poll(), SpectateRules::newNonce, Runnable::run);
    }

    private static Deadline d() {
        return Deadline.after(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
    }

    private static String id(long unsigned) {
        return Long.toUnsignedString(unsigned);
    }

    private static SessionContext session(long playerId) {
        return SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-inst-1").setSessionId(7).setZoneId(1).setPlayerId(playerId)
                .setAccount("acc-" + id(playerId)).build();
    }

    private static BattlePlacement placement(long battleId, long createdAtMs, int attempt) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-a").setRpcHost("10.1.1.1")
                .setRpcPort(21200).setAttempt(attempt).setMode(1).setBattleConfigId(1).addPlayerNames("甲").addPlayerNames("乙")
                .setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build();
    }

    /** gather 的写法：先写落点（真写者），开局成功后经<b>真钩子</b>公开。返回落点。 */
    private BattlePlacement started(long battleId, long createdAtMs) {
        BattlePlacement placement = placement(battleId, createdAtMs, 1);
        fx.putPlacement(placement);
        hooks.onStarted(placement);
        assertThat(fx.index()).as("开局钩子把这一场公开了").containsEntry(SpectateRules.member(battleId), createdAtMs);
        return placement;
    }

    /** 一名在线、没锁、没票的观众（在线目录：zone 2 的 1 号 gate）。 */
    private long viewer(int n) {
        long playerId = fx.pid(n);
        players.presence(playerId, PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(2).setGateNodeId(1).setGateInstanceId("gate-inst-z2")
                .setSessionId(131073).setOwnerEpoch(1).build());
        return playerId;
    }

    private WatchBattleResponse watch(long playerId, long battleId) {
        return watches.watch(session(playerId), battleId, d());
    }

    // ================================================================ 断言

    private static void assertAccepted(WatchBattleResponse response, long battleId) {
        assertThat(response.hasErrorMessage()).as("成功不带 error_message").isFalse();
        assertThat(response.getBattleId()).isEqualTo(battleId);
    }

    private static void assertRejected(WatchBattleResponse response, int code, String text) {
        assertThat(response.getBattleId()).isZero();
        assertThat(response.getErrorMessage().getId()).isEqualTo(code);
        assertThat(response.getErrorMessage().getParametersList()).hasSize(1);
        assertThat(response.getErrorMessage().getParameters(0).getBytes(StandardCharsets.UTF_8)).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 这名玩家此刻的标记指向的战斗；断言它是 163 写的那种形状（{@code <battle_id>:<16 位十六进制 nonce>}）且带 360 s 以内的 TTL。 */
    private long markedBattle(long playerId) {
        Optional<String> raw = fx.markOf(playerId);
        assertThat(raw).as("player %s 有观战标记", id(playerId)).isPresent();
        Optional<SpectateRules.Mark> mark = SpectateRules.decodeMark(raw.get());
        assertThat(mark).as("标记值可解析: %s", raw.get()).isPresent();
        assertThat(raw.get()).matches(id(mark.get().battleId()) + ":[0-9a-f]{16}");
        assertThat(fx.markTtlMs(playerId)).as("标记的 TTL").isBetween(300_000L, 360_000L);
        return mark.get().battleId();
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

    private Map<String, Double> outcomes() {
        return counts("xm.match.watch.battle", "outcome");
    }

    private Map<String, Double> indexEvictions() {
        return counts("xm.match.watchable.index.evictions", "reason");
    }

    private Map<String, Double> anomalies() {
        return counts("xm.match.watchable.anomalies", "reason");
    }

    private double evictions(String reason, String result) {
        return meters.get("xm.match.spectate.evictions").tag("reason", reason).tag("result", result).counter().count();
    }

    private List<BattleWatchSummary> listed(int limit) {
        WatchableListService.Result result = lists.list(limit, d());
        assertThat(result).isInstanceOf(WatchableListService.Result.Listed.class);
        ListWatchableBattlesResponse response = ((WatchableListService.Result.Listed) result).response();
        return response.getBattlesList();
    }

    // ================================================================ 一场战斗从公开到观众被清退

    @Test
    void 开局公开_列表列出_指定观战写下标记并按落点登记观众_观众随后去参战时被开局钩子清退() {
        long now = fx.nowMs();
        long x = fx.battle(1);
        BattlePlacement placement = started(x, now - 5_000);
        long viewer = viewer(1);
        long other = fx.pid(2);

        // 164：摘要逐字段取自落点，created_at_ms 原样（客户端用它算已开局时长）
        assertThat(listed(0)).containsExactly(BattleWatchSummary.newBuilder().setBattleId(x).setModeValue(1).setBattleConfigId(1)
                .addPlayerNames("甲").addPlayerNames("乙").setCreatedAtMs(now - 5_000).build());

        // 163：标记先于登记写下；登记发往 Redis 里读出来的那条落点，路由取在线目录（zone 2 的 1 号 gate），不取会话上下文（zone 1）
        assertAccepted(watch(viewer, x), x);
        assertThat(markedBattle(viewer)).isEqualTo(x);
        assertThat(observers.calls).singleElement().satisfies(add -> {
            assertThat(add.kind()).isEqualTo(Kind.ADD);
            assertThat(add.placement()).as("从真 Redis 读回的落点与写入的逐字段相同").isEqualTo(placement);
            assertThat(add.request().getBattleId()).isEqualTo(x);
            assertThat(add.request().getObserverPlayerId()).isEqualTo(viewer);
            assertThat(add.request().getObserverName()).isEqualTo("acc-" + id(viewer));
            assertThat(add.request().getRouting().getZoneId()).isEqualTo(2);
            assertThat(add.request().getRouting().getGateNodeId()).isEqualTo(1);
            assertThat(add.request().getRouting().getGateInstanceId()).isEqualTo("gate-inst-z2");
            assertThat(add.request().getRouting().getSessionId()).isEqualTo(131073);
        });
        assertThat(outcomes()).containsExactly(Map.entry("ok", 1.0));

        // 观众进了一局的参战名单：钩子经真存储读到他的标记，按落点地址清退，再按读到的原值删标记；没有标记的成员不碰
        hooks.beforePrepare(List.of(other, viewer));

        assertThat(observers.removes()).singleElement().satisfies(remove -> {
            assertThat(remove.kind()).isEqualTo(Kind.REMOVE);
            assertThat(remove.placement()).isEqualTo(placement);
            assertThat(remove.observerId()).isEqualTo(viewer);
            assertThat(remove.reason()).isEqualTo("enter_gather");
            assertThat(remove.timeout()).as("每人至多 3 s").isLessThanOrEqualTo(Duration.ofMillis(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS));
        });
        assertThat(fx.markOf(viewer)).as("清退之后标记已删").isEmpty();
        assertThat(evictions("enter_gather", "removed")).isEqualTo(1.0);
        assertThat(fx.index()).as("清退观众不动这一场的公开状态").containsOnlyKeys(SpectateRules.member(x));
        assertThat(anomalies()).isEmpty();
    }

    // ================================================================ 重看同一场、换场

    @Test
    void 重看同一场只换标记不发Remove_换场先同步清退旧场再登记新场_标记始终只有一个() {
        long now = fx.nowMs();
        long x = fx.battle(1);
        long y = fx.battle(2);
        BattlePlacement placementX = started(x, now - 9_000);
        BattlePlacement placementY = started(y, now - 3_000);
        long viewer = viewer(1);

        assertAccepted(watch(viewer, x), x);
        String first = fx.markOf(viewer).orElseThrow();

        // 显式重看同一场：旧标记按原值删、不发 RemoveObserver，再抢一个新值（nonce 不同），登记请求的路由与第一次相同（battle 的幂等分支靠它）
        assertAccepted(watch(viewer, x), x);
        String second = fx.markOf(viewer).orElseThrow();
        assertThat(markedBattle(viewer)).isEqualTo(x);
        assertThat(second).as("每次抢标记一个新的 nonce").isNotEqualTo(first);
        assertThat(observers.calls).extracting(Call::kind).containsExactly(Kind.ADD, Kind.ADD);
        assertThat(observers.adds().get(1).request()).as("重看的登记请求与第一次逐字段相同").isEqualTo(observers.adds().get(0).request());

        // 换场：读旧场落点 → 同步 RemoveObserver(rewatch) → 删旧标记 → 抢新标记 → AddObserver
        assertAccepted(watch(viewer, y), y);
        assertThat(markedBattle(viewer)).isEqualTo(y);
        assertThat(events).containsExactly("observer.add:" + id(x) + ":" + id(viewer), "observer.add:" + id(x) + ":" + id(viewer),
                "observer.remove:" + id(x) + ":" + id(viewer) + ":rewatch", "observer.add:" + id(y) + ":" + id(viewer));
        assertThat(observers.removes()).singleElement().satisfies(remove -> assertThat(remove.placement()).isEqualTo(placementX));
        assertThat(observers.adds().get(2).placement()).isEqualTo(placementY);
        assertThat(evictions("rewatch", "removed")).as("重看同一场不计清退，换场计一次").isEqualTo(1.0);
        assertThat(outcomes()).containsExactly(Map.entry("ok", 3.0));
    }

    // ================================================================ 随机观战、脏成员、过期成员与清扫

    @Test
    void 随机观战挑到非法成员按原串剔除后重挑_过期成员挑不到也不顺手剔_留给清扫器摘_清扫只摘成员不动落点() {
        long now = fx.nowMs();
        long alive = fx.battle(1);
        long stale = fx.battle(2);
        started(alive, now - 60_000);
        // 一场 400 s 前开局、早该收尾的战斗：成员过期了，落点记录碰巧还在
        fx.putPlacement(placement(stale, now - 400_000, 1));
        fx.putMember(SpectateRules.member(stale), now - 400_000);
        // 带前导零的成员不是规范的 battle_id：按战斗号永远摘不掉它，只能按读到的原串摘
        fx.putMember("007", now - 1_000);
        long viewer = viewer(1);

        // 第一挑 r = 0.99 → 未过期成员里分数最高的 "007"（非法，剔除后重挑）；第二挑 r = 0 → alive
        randoms.add(0.99);
        randoms.add(0.0);
        assertAccepted(watch(viewer, 0), alive);

        assertThat(markedBattle(viewer)).isEqualTo(alive);
        assertThat(observers.adds()).singleElement().satisfies(add -> assertThat(add.battleId()).isEqualTo(alive));
        assertThat(fx.index()).as("非法成员按原串摘掉了；过期成员挑不到，选场也不剔它").containsOnlyKeys(SpectateRules.member(stale), SpectateRules.member(alive));
        assertThat(indexEvictions()).containsExactly(Map.entry("invalid_member", 1.0));

        // 清扫一轮（真存储、Redis 自己的时间）：只摘过期成员，落点靠自己的 TTL
        SpectateSweeper sweeper = new SpectateSweeper(fx.store, metrics, Duration.ofHours(1));
        sweeper.runRoundSafely();

        assertThat(fx.index()).containsOnlyKeys(SpectateRules.member(alive));
        assertThat(fx.placementExists(stale)).as("清扫只摘成员，不删落点").isTrue();
        assertThat(indexEvictions()).containsExactly(Map.entry("invalid_member", 1.0), Map.entry("sweep", 1.0));
        assertThat(meters.get("xm.match.watchable.battles").gauge().value()).as("采样到的是清扫之后的索引大小").isEqualTo(1.0);
    }

    // ================================================================ 已结束的战斗、正在建房的战斗

    @Test
    void 已公开的战斗battle回房间不存在_应答之前同步剔除成员与落点_紧接着的列表里就没有它_标记回滚() {
        long now = fx.nowMs();
        long ended = fx.battle(1);
        long alive = fx.battle(2);
        started(ended, now - 120_000);
        started(alive, now - 20_000);
        long viewer = viewer(1);
        long browser = viewer(2);
        observers.onAdd(ended, new Outcome.Replied(MatchTips.BATTLE_ROOM_NOT_FOUND));

        assertRejected(watch(viewer, ended), MatchTips.BATTLE_NOT_WATCHABLE, NOT_FOUND);

        // 163 回包时剔除已经做完（不是异步的）：别的玩家紧接着拉列表就看不到这一场（robot battle-smoke 的 S10 靠这一点）
        assertThat(fx.index()).containsOnlyKeys(SpectateRules.member(alive));
        assertThat(fx.placementExists(ended)).as("按 attempt 守护的剔除连落点一起删").isFalse();
        assertThat(listed(50)).extracting(BattleWatchSummary::getBattleId).containsExactly(alive);
        assertThat(fx.markOf(viewer)).as("明确的拒绝：标记按值回滚").isEmpty();
        assertThat(indexEvictions()).containsExactly(Map.entry("room_missing", 1.0));
        assertThat(outcomes()).containsExactly(Map.entry("not_found", 1.0));

        // 没有标记残留：同一名玩家马上可以去看另一场；浏览列表的人也能
        assertAccepted(watch(viewer, alive), alive);
        assertAccepted(watch(browser, alive), alive);
    }

    @Test
    void 落点刚预写还没公开_battle回房间不存在只是还没建好_落点与索引都不动_换节点改写后的落点不会被旧的attempt剔掉() {
        long now = fx.nowMs();
        long creating = fx.battle(1);
        long retried = fx.battle(2);
        // gather 在建房之前就预写了落点（attempt 1），还没开局成功，所以不在索引里
        fx.putPlacement(placement(creating, now, 1));
        long viewer = viewer(1);
        observers.onAdd(creating, new Outcome.Replied(MatchTips.BATTLE_ROOM_NOT_FOUND));

        assertRejected(watch(viewer, creating), MatchTips.BATTLE_NOT_WATCHABLE, NOT_FOUND);

        assertThat(fx.placementOf(creating)).as("在建房窗口内（Redis 时间距 created_at_ms 不到 22.2 s）且未公开：不剔除").contains(placement(creating, now, 1));
        assertThat(fx.index()).isEmpty();
        assertThat(fx.markOf(viewer)).isEmpty();
        assertThat(indexEvictions()).isEmpty();

        // 出了建房窗口的未公开落点（60 s 前预写）：163 读到的是 attempt 1；RPC 在途期间 gather 把它改写到了重试节点（attempt 2）。
        // battle（旧节点）回房间不存在 → 按 attempt 1 守护的剔除不成立，什么都不动
        BattlePlacement rewritten = placement(retried, now - 60_000, 2).toBuilder().setBattleNodeId(8).setBattleInstanceId("inst-b").build();
        fx.putPlacement(placement(retried, now - 60_000, 1));
        observers.onAdd(retried, new Outcome.Replied(MatchTips.BATTLE_ROOM_NOT_FOUND));
        observers.beforeAdd = call -> {
            if (call.battleId() == retried) {
                fx.putPlacement(rewritten);
            }
        };

        assertRejected(watch(viewer, retried), MatchTips.BATTLE_NOT_WATCHABLE, NOT_FOUND);

        assertThat(fx.placementOf(retried)).as("落点已是 attempt 2：旧 attempt 的剔除不动它").contains(rewritten);
        assertThat(indexEvictions()).as("存储回报没摘到，不计数").isEmpty();
        assertThat(fx.markOf(viewer)).isEmpty();
        assertThat(outcomes()).containsExactly(Map.entry("not_found", 2.0));
    }

    // ================================================================ 与真票据的互斥

    @Test
    void 持票不能观战_入口之后才建出的票在抢标记的脚本里被挡住_登记之后才建出的票由复查经票据只读口读到并自我清退() {
        long now = fx.nowMs();
        long x = fx.battle(1);
        BattlePlacement placement = started(x, now - 5_000);
        QueueRef queue = fx.tickets.queue(3, 1);

        // ① 入口就有票（真票据存储建的 queued 票）：16014，不碰 battle，不写标记
        long queued = viewer(1);
        fx.tickets.store.enqueue(queued, "t-entry", queue, 1, 150_000, 21_600_000, d());
        assertRejected(watch(queued, x), MatchTips.SPECTATE_WHILE_QUEUED, QUEUED);
        assertThat(observers.calls).isEmpty();
        assertThat(fx.markOf(queued)).isEmpty();

        // ② 入口检查时没有票，读在线目录期间票建出来了：「没有票 ∧ 没有标记 → 写标记」是一段脚本，抢标记时被挡住
        long racing = viewer(2);
        PlayerStatusReader enqueuesDuringPresence = new PlayerStatusReader() {
            @Override
            public boolean inBattle(long playerId, Deadline deadline) {
                return players.inBattle(playerId, deadline);
            }

            @Override
            public Optional<PlayerPresence> presence(long playerId, Deadline deadline) {
                fx.tickets.store.enqueue(playerId, "t-race", queue, 1, 150_000, 21_600_000, d());
                return players.presence(playerId, deadline);
            }

            @Override
            public HolderRead location(long playerId, Deadline deadline) {
                return players.location(playerId, deadline);
            }
        };
        assertRejected(service(enqueuesDuringPresence).watch(session(racing), x, d()), MatchTips.SPECTATE_WHILE_QUEUED, QUEUED);
        assertThat(observers.calls).as("抢标记时发现有票：不调 AddObserver").isEmpty();
        assertThat(fx.markOf(racing)).as("没有写下标记").isEmpty();

        // ③ 观众已登记之后票才建出来（即时开局的并发 gather）：复查经真的票据只读口读到 → 异步自我清退、按值删标记 → 16014
        long late = viewer(3);
        observers.beforeAdd = call -> fx.tickets.store.enqueue(late, "t-late", queue, 1, 150_000, 21_600_000, d());
        assertRejected(watch(late, x), MatchTips.SPECTATE_WHILE_QUEUED, QUEUED);
        assertThat(observers.calls).extracting(Call::kind).containsExactly(Kind.ADD, Kind.REMOVE_ASYNC);
        assertThat(observers.removes()).singleElement().satisfies(remove -> {
            assertThat(remove.placement()).isEqualTo(placement);
            assertThat(remove.observerId()).isEqualTo(late);
            assertThat(remove.reason()).isEqualTo("concurrent_queue");
        });
        assertThat(fx.markOf(late)).as("自我清退：标记按值删掉了").isEmpty();
        assertThat(evictions("concurrent_queue", "removed")).isEqualTo(1.0);
        assertThat(outcomes()).containsExactly(Map.entry("queued", 3.0));
        assertThat(fx.index()).as("互斥的拒绝不动索引").containsOnlyKeys(SpectateRules.member(x));
    }

    // ================================================================ 164 的懒剔除

    @Test
    void 列表逐条判定_非法成员_过期成员_落点不在的成员异步剔除_损坏的落点跳过不剔除_不回填() {
        long now = fx.nowMs();
        long good = fx.battle(1);
        long stale = fx.battle(2);
        long missing = fx.battle(3);
        long corrupt = fx.battle(4);
        long newest = fx.battle(5);
        started(good, now - 30_000);
        started(newest, now - 1_000);
        fx.putPlacement(placement(stale, now - 500_000, 1));
        fx.putMember(SpectateRules.member(stale), now - 500_000);
        fx.putMember(SpectateRules.member(missing), now - 20_000);
        fx.putRawPlacement(corrupt, "1", new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        fx.putMember(SpectateRules.member(corrupt), now - 10_000);
        fx.putMember("0", now - 2_000);

        // 六个成员、limit 50：只回两条好记录，按 created_at_ms 降序；短了不回填
        assertThat(listed(50)).extracting(BattleWatchSummary::getBattleId).containsExactly(newest, good);

        // 剔除在应答组好之后异步发出（一批、一次往返）：等它落到 Redis
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(fx.index())
                .containsOnlyKeys(SpectateRules.member(good), SpectateRules.member(newest), SpectateRules.member(corrupt)));
        assertThat(fx.placementExists(stale)).as("过期成员连同落点一起删").isFalse();
        assertThat(fx.placementExists(corrupt)).as("损坏的落点不剔除（删掉它会毁掉补签的定位）").isTrue();
        assertThat(fx.placementExists(good)).isTrue();
        assertThat(indexEvictions()).containsExactly(Map.entry("invalid_member", 1.0), Map.entry("missing_record", 1.0), Map.entry("stale", 1.0));
        assertThat(anomalies()).containsExactly(Map.entry("corrupt_record", 1.0));

        // 第二次（limit 1）：只取分数最高的一个成员，没有可剔除的了
        assertThat(listed(1)).as("limit 1：只回最新的一场").extracting(BattleWatchSummary::getBattleId).containsExactly(newest);
        assertThat(indexEvictions()).containsExactly(Map.entry("invalid_member", 1.0), Map.entry("missing_record", 1.0), Map.entry("stale", 1.0));
        assertThat(meters.get("xm.match.list.watchable").tag("result", "ok").counter().count()).isEqualTo(2.0);
    }

    // ================================================================ 开局钩子的各种标记

    @Test
    void 开局清退_脏标记按原串删不发RPC_旧场落点已不在不发RPC_公开只认最终的attempt() {
        long now = fx.nowMs();
        long x = fx.battle(1);
        long gone = fx.battle(2);
        BattlePlacement placement = started(x, now - 5_000);
        long watching = fx.pid(1);
        long dirty = fx.pid(2);
        long leftover = fx.pid(3);
        long clean = fx.pid(4);
        fx.putMark(watching, SpectateRules.encodeMark(x, SpectateRules.newNonce()));
        // 基线那种只有 battle_id 的旧值：解析不了，按读到的原串删
        fx.putMark(dirty, id(x));
        // 标记指向一场落点已经不在的战斗（早已收尾）：标记只是残留
        fx.putMark(leftover, SpectateRules.encodeMark(gone, SpectateRules.newNonce()));

        hooks.beforePrepare(List.of(clean, dirty, leftover, watching));

        assertThat(observers.calls).as("只有落点还在的那一名观众需要找 battle").singleElement().satisfies(remove -> {
            assertThat(remove.kind()).isEqualTo(Kind.REMOVE);
            assertThat(remove.placement()).isEqualTo(placement);
            assertThat(remove.observerId()).isEqualTo(watching);
        });
        assertThat(List.of(fx.markOf(watching), fx.markOf(dirty), fx.markOf(leftover), fx.markOf(clean))).as("三个标记都按原值删掉了").containsOnly(Optional.empty());
        assertThat(evictions("enter_gather", "removed")).isEqualTo(1.0);
        assertThat(evictions("enter_gather", "invalid_mark")).isEqualTo(1.0);
        assertThat(evictions("enter_gather", "no_record")).isEqualTo(1.0);
        assertThat(anomalies()).isEmpty();

        // 公开：钩子拿到的落点若不是 Redis 里最终的那一次（attempt 对不上），不进索引，只计一次异常
        long rewritten = fx.battle(3);
        fx.putPlacement(placement(rewritten, now - 2_000, 2));
        hooks.onStarted(placement(rewritten, now - 2_000, 1));
        assertThat(fx.index()).containsOnlyKeys(SpectateRules.member(x));
        assertThat(anomalies()).containsExactly(Map.entry("publish_failed", 1.0));
        hooks.onStarted(placement(rewritten, now - 2_000, 2));
        assertThat(fx.index()).containsOnlyKeys(SpectateRules.member(x), SpectateRules.member(rewritten));
        assertThat(anomalies()).containsExactly(Map.entry("publish_failed", 1.0));
    }

    // ================================================================ 虚拟线程：等 Redis 时不钉住载体线程

    /**
     * 163 跑在自己的虚拟线程上、开局钩子跑在 gather 的虚拟线程上：它们经真存储等 Redis 的每一下都必须是「挂起虚拟线程」，不能是
     * 「连载体线程一起占住」（在 {@code synchronized} 里或本地帧上等待）——载体线程只有 CPU 核数那么多，钉住几条就把别的 163 / gather 一起拖慢。
     *
     * <p>探测用 JFR 的 {@code jdk.VirtualThreadPinned} 事件（虚拟线程在被钉住的状态下停靠时发出），不用 {@code -Djdk.tracePinnedThreads}：
     * 后者只往「普通的 {@code PrintStream}」类型的 {@code System.out} 打印，surefire 换上的转发流收不到，会给出虚假的「没有钉住」。
     * 同一段录制里先放一个<b>对照</b>（在 {@code synchronized} 里等 future）：对照必须被抓到，否则「流程里没有」什么都证明不了。
     */
    @Test
    void 真存储上的163_开局钩子与164跑在虚拟线程上_等Redis时不钉住载体线程() throws Exception {
        long now = fx.nowMs();
        long x = fx.battle(1);
        long next = fx.battle(2);
        started(x, now - 5_000);
        BattlePlacement nextPlacement = placement(next, now - 1_000, 1);
        fx.putPlacement(nextPlacement);
        long viewer = viewer(1);
        long warm = viewer(2);
        // 生产装配：复查的两次读各起一条虚拟线程（match-spectate-recheck-*）
        WatchBattleService production = new WatchBattleService(fx.store, fx.placements, players, fx.tickets.store, observers, metrics);
        // 预热：同样的几条路径先在平台线程上各走一遍——类加载与一次性的初始化（它们会在类初始化锁里等）不算在被测的等待里
        assertAccepted(production.watch(session(warm), x, d()), x);
        assertAccepted(production.watch(session(warm), 0, d()), x);
        hooks.beforePrepare(List.of(warm));
        assertThat(listed(50)).hasSize(1);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path dump = Files.createTempFile("xm-match-spectate-pinned-", ".jfr");
        List<RecordedEvent> pinned;
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            recording.start();

            Thread control = Thread.ofVirtual().name(PIN_CONTROL_THREAD).start(() -> {
                synchronized (failure) {
                    try {
                        new CompletableFuture<Void>().get(100, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException expected) {
                        // 就是要它等满：这 100 ms 里虚拟线程被钉在载体线程上
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
            assertThat(control.join(Duration.ofSeconds(30))).as("对照线程在时限内结束").isTrue();

            Thread flow = Thread.ofVirtual().name(PIN_FLOW_THREAD).start(() -> {
                try {
                    // 163 指定观战：入口读 → 读落点 → 抢标记 → 登记 → 复查（两条虚拟线程，经票据只读口与战斗锁）
                    assertAccepted(production.watch(session(viewer), x, d()), x);
                    // 163 随机观战，身上带着上一条的标记：读旧场落点 → 清退 → 按值删标记 → 随机选场 → 再走一遍登记
                    assertAccepted(production.watch(session(viewer), 0, d()), x);
                    // 开局钩子：一次读全员标记 → 读落点 → 清退 → 按值删标记；开局后公开
                    hooks.beforePrepare(List.of(viewer));
                    hooks.onStarted(nextPlacement);
                    // 164：读索引 → 批读落点
                    assertThat(listed(50)).extracting(BattleWatchSummary::getBattleId).containsExactly(next, x);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            assertThat(flow.join(Duration.ofSeconds(60))).as("流程线程在时限内结束").isTrue();

            recording.stop();
            recording.dump(dump);
            pinned = RecordingFile.readAllEvents(dump).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.VirtualThreadPinned")).toList();
        } finally {
            Files.deleteIfExists(dump);
        }
        assertThat(failure.get()).as("虚拟线程里的流程本身要走通").isNull();
        assertThat(fx.markOf(viewer)).as("流程确实走到了底：观众被开局钩子清退").isEmpty();

        List<String> controlHits = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (RecordedEvent event : pinned) {
            String thread = event.getThread() == null ? "" : String.valueOf(event.getThread().getJavaName());
            if (thread.equals(PIN_CONTROL_THREAD)) {
                controlHits.add(thread);
            } else if (thread.equals(PIN_FLOW_THREAD) || thread.startsWith(WatchBattleService.RECHECK_THREAD_PREFIX)) {
                offenders.add(thread + " 被钉住 " + event.getDuration().toMillis() + " ms:\n" + stackOf(event));
            }
        }
        assertThat(controlHits).as("对照（synchronized 里等 future）必须被 JFR 抓到，否则这条用例什么都证明不了").isNotEmpty();
        assertThat(offenders).as("163 / 开局钩子 / 164 在虚拟线程上等真 Redis 时不得钉住载体线程").isEmpty();
    }

    private static final String PIN_CONTROL_THREAD = "it-spectate-pin-control";
    private static final String PIN_FLOW_THREAD = "it-spectate-pin-flow";

    private static String stackOf(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return "    （没有栈）";
        }
        StringBuilder out = new StringBuilder();
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            out.append("    at ").append(frame.getMethod().getType().getName()).append('.').append(frame.getMethod().getName())
                    .append(':').append(frame.getLineNumber()).append('\n');
        }
        return out.toString();
    }
}
