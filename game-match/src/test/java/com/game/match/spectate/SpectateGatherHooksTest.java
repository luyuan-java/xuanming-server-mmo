package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.match.MatchBudgets;
import com.game.match.gather.GatherHooks;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.FakeObserverDialer.Call;
import com.game.match.testing.FakeObserverDialer.Kind;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.proto.BattleRouting;
import com.game.proto.eSpectateVisibility;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 开局钩子（spectate-spec §4.6、§10.3 的 {@code SpectateGatherHooksTest} 一段）。
 * <ul>
 *   <li>{@code beforePrepare}：完整原件 → 原端点取消 → 取消正证明后按值删标记；后来的落点缺失、损坏或读失败不改原端点。
 *       读全员标记出错 → 全部跳过、标记保留；RPC 未知保留原件；每人一个截止，RPC + 删标记共用预算。</li>
 *   <li>{@code onStarted}：落点的 attempt 一致才登记进索引；登记不上只计数。</li>
 * </ul>
 * 对照基线 {@code spectate_test.go:448}（StopWatchingIfAnyPaths）、{@code :824}（WithCorruptRecordStillClearsMark），
 * {@code gather_spectate_index_test.go:244}（ZaddFailureIsBestEffort）。钩子接进开局管线之后的行为在 {@code gather.GatherHooksCallSiteTest}。
 *
 * <p>与时间有关的用例把时限收短（包内构造器），断言只取宽松的两个方向：「不早于时限」（等待不会提前返回）与一个远小于「卡死」的上界。
 */
class SpectateGatherHooksTest {

    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final String NONCE = "0123456789abcdef";
    private static final long SHORT_BUDGET_MS = 200;
    /** 「没有卡死」的上界：远大于收短后的时限，远小于生产的 3 s × 人数。 */
    private static final long NOT_STUCK_MS = 2_500;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements, events);
    private final FakeObserverDialer dialer = new FakeObserverDialer(events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final SpectateGatherHooks hooks = new SpectateGatherHooks(store, dialer, metrics);

    private SpectateGatherHooks shortHooks() {
        return new SpectateGatherHooks(store, dialer, metrics, SHORT_BUDGET_MS, SHORT_BUDGET_MS);
    }

    /** 公开局的落点（开局钩子只公开落点里冻结为 PUBLIC 的局，不按 mode 重算）。 */
    private static BattlePlacement placement(long battleId, int attempt) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-7").setRpcHost("10.0.0.7")
                .setRpcPort(21207).setAttempt(attempt).setMode(3).addPlayerNames("旧甲").addPlayerNames("旧乙").setCreatedAtMs(T0 - 1_234)
                .setDeadlineMs(T0 + 298_766).setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE).build();
    }

    /** 摆一场在打的旧战斗（落点在）并让这名玩家带着它的观战标记；返回标记的值。 */
    private String watching(long playerId, long battleId) {
        placements.put(placement(battleId, 1));
        return mark(playerId, battleId);
    }

    /** 只摆标记（不摆落点）。 */
    private String mark(long playerId, long battleId) {
        String value = original(playerId, battleId).mark();
        store.putMark(playerId, value);
        return value;
    }

    private static LocalObserverOperation original(long playerId, long battleId) {
        return LocalObserverOperation.create(placement(battleId, 1), playerId,
                BattleRouting.newBuilder().setGateNodeId(1).setGateInstanceId("gate-test").setSessionId(7).build(),
                "观众", java.util.UUID.randomUUID().toString(), T0);
    }

    private double evictions(String result) {
        return meters.get("game.match.spectate.evictions").tags("reason", "enter_gather", "result", result).counter().count();
    }

    private double allEvictions() {
        return meters.find("game.match.spectate.evictions").counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private double anomalies(String reason) {
        return meters.get("game.match.watchable.anomalies").tags("reason", reason).counter().count();
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    // ================================================================ beforePrepare：清退的四种形态

    @Test
    void 正在观战且那一场还在_按落点记录的地址发RemoveObserver_reason是enter_gather_然后按值删标记() {
        String value = watching(7301, 880110);

        hooks.beforePrepare(List.of(7301L, 7302L));

        assertThat(dialer.calls).singleElement().satisfies(call -> {
            assertThat(call.kind()).as("同步清退：等它返回（或到点）才处理下一人、才开始备战").isEqualTo(Kind.REMOVE);
            assertThat(call.placement()).as("发往读到的那条落点记录：地址与实例都取自它，不查目录").isEqualTo(placement(880110, 1));
            assertThat(call.observerId()).isEqualTo(7301L);
            assertThat(call.reason()).isEqualTo("enter_gather");
            assertThat(call.timeout()).as("超时 = min(3 s, 这名成员的剩余)").isBetween(Duration.ofMillis(1_000), Duration.ofMillis(3_000));
            assertThat(call.hardStopRemainingMs()).as("硬截止就是这名成员的 3 s 截止").isBetween(1_000L, 3_000L);
        });
        assertThat(store.markOf(7301)).isEmpty();
        assertThat(store.calls).as("一次读全员标记；原件自带端点；删标记按读到的原值").containsExactly("marksOf([7301, 7302])",
                "release(7301," + value + ")");
        assertThat(events).as("按原件清退、获证明后删标记").containsExactly("observer.remove:880110:7301:enter_gather",
                "spectate.release:7301");
        assertThat(placements.stored(880110)).as("清退不动那一场的落点与索引").contains(placement(880110, 1));
        assertThat(evictions("removed")).isEqualTo(1.0);
        assertThat(allEvictions()).isEqualTo(1.0);
    }

    @Test
    void 落点不在_仍按原件固定端点取消_获证明后删标记() {
        String value = mark(7302, 880111);

        hooks.beforePrepare(List.of(7302L));

        assertThat(dialer.calls).singleElement().satisfies(call -> assertThat(call.placement()).isEqualTo(placement(880111, 1)));
        assertThat(store.markOf(7302)).isEmpty();
        assertThat(store.calls).containsExactly("marksOf([7302])", "release(7302," + value + ")");
        assertThat(evictions("removed")).isEqualTo(1.0);
        assertThat(allEvictions()).isEqualTo(1.0);
    }

    @Test
    void 标记值非法_不读落点不找battle_按读到的原串删掉() {
        store.putMark(7303, "oops");
        store.putMark(7304, "880112"); // 基线那种只有 battle_id 的旧值：Java 的标记带 nonce，它也是脏值
        placements.put(placement(880112, 1));

        hooks.beforePrepare(List.of(7303L, 7304L));

        assertThat(dialer.calls).isEmpty();
        assertThat(store.markCount()).isZero();
        assertThat(store.calls).containsExactly("marksOf([7303, 7304])", "release(7303,oops)", "release(7304,880112)");
        assertThat(evictions("invalid_mark")).isEqualTo(2.0);
    }

    @Test
    void 未来版本与损坏原件均保留_不发无原件取消() {
        store.putMark(7303, "observer-v2:future:payload");
        store.putMark(7304, "observer-v1:broken");

        hooks.beforePrepare(List.of(7303L, 7304L));

        assertThat(dialer.calls).isEmpty();
        assertThat(store.markOf(7303)).contains("observer-v2:future:payload");
        assertThat(store.markOf(7304)).contains("observer-v1:broken");
        assertThat(store.calls).containsExactly("marksOf([7303, 7304])");
        assertThat(evictions("invalid_mark")).isEqualTo(2.0);
    }

    @Test
    void 没在观战的成员_只有开头那一次读标记_没有任何副作用() {
        placements.put(placement(880113, 1));
        store.putMark(9999, SpectateRules.encodeMark(880113, NONCE)); // 名单之外的人在看：与本次开局无关

        hooks.beforePrepare(List.of(7305L, 7306L));

        assertThat(store.calls).containsExactly("marksOf([7305, 7306])");
        assertThat(dialer.calls).isEmpty();
        assertThat(store.markOf(9999)).as("不动别人的标记").isPresent();
        assertThat(allEvictions()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void 空名单_什么都不做() {
        hooks.beforePrepare(List.of());

        assertThat(store.calls).isEmpty();
        assertThat(dialer.calls).isEmpty();
    }

    // ================================================================ beforePrepare：读失败

    @Test
    void 落点存储读失败不影响按原件取消_确认后删标记() {
        String value = watching(7310, 880120);
        store.faults.failNext("read");

        hooks.beforePrepare(List.of(7310L));

        assertThat(dialer.calls).as("不依赖后来的落点读取").hasSize(1);
        assertThat(store.markOf(7310)).as("原件取消已确认").isEmpty();
        assertThat(store.calls).containsExactly("marksOf([7310])", "release(7310," + value + ")");
        assertThat(evictions("removed")).isEqualTo(1.0);
        assertThat(allEvictions()).isEqualTo(1.0);
    }

    @Test
    void 落点记录损坏_只按持久原件寻址取消_损坏落点不动() {
        watching(7311, 880300);
        placements.corrupt(880300);

        hooks.beforePrepare(List.of(7311L));

        assertThat(dialer.calls).as("不读损坏记录，使用标记内原端点").singleElement()
                .satisfies(call -> assertThat(call.placement()).isEqualTo(placement(880300, 1)));
        assertThat(store.markOf(7311)).isEmpty();
        assertThat(placements.corrupted(880300)).as("清退不删落点").isTrue();
        assertThat(evictions("removed")).isEqualTo(1.0);
    }

    @Test
    void 读全员标记出错_全部跳过_标记原样保留_不找battle_计mark_read_failed() {
        String first = watching(7320, 880130);
        String second = watching(7321, 880131);
        store.faults.failNext("marksOf");

        assertThatCode(() -> hooks.beforePrepare(List.of(7320L, 7321L))).doesNotThrowAnyException();

        assertThat(dialer.calls).isEmpty();
        assertThat(store.markOf(7320)).contains(first);
        assertThat(store.markOf(7321)).contains(second);
        assertThat(store.calls).as("不逐人重读、不删任何东西").containsExactly("marksOf([7320, 7321])");
        assertThat(anomalies("mark_read_failed")).isEqualTo(1.0);
        assertThat(allEvictions()).as("没有处理任何一条标记").isZero();
    }

    // ================================================================ beforePrepare：RPC 的结局

    @Test
    void RemoveObserver的各种结局_都不抛_只有完整取消证明才删标记() {
        Map<Outcome, String> expected = Map.of(
                new Outcome.Replied(0), "removed",
                new Outcome.Replied(1004), "rpc_failed", // 非零业务码不能替代完整取消证明
                new Outcome.Dead(), "rpc_failed", // 目录判死不能替代原操作的取消证明
                new Outcome.NotDelivered("连不上"), "rpc_failed",
                new Outcome.Unknown("超时"), "rpc_failed");
        long playerId = 7400;
        for (Map.Entry<Outcome, String> entry : expected.entrySet()) {
            playerId++;
            long battleId = 880_000 + playerId;
            String mark = watching(playerId, battleId);
            dialer.nextRemove(entry.getKey());
            double before = evictions(entry.getValue());
            int callsBefore = dialer.calls.size();
            long pid = playerId;

            assertThatCode(() -> hooks.beforePrepare(List.of(pid))).as("%s", entry.getKey()).doesNotThrowAnyException();

            assertThat(dialer.calls).as("%s：只发一次，不重试", entry.getKey()).hasSize(callsBefore + 1);
            assertThat(store.markOf(pid)).as("%s：仅证明取消后删除", entry.getKey())
                    .isEqualTo(entry.getKey() instanceof Outcome.Replied replied && replied.tipId() == 0
                            ? java.util.Optional.empty() : java.util.Optional.of(mark));
            assertThat(evictions(entry.getValue()) - before).as("%s → %s", entry.getKey(), entry.getValue()).isEqualTo(1.0);
            assertThat(placements.stored(battleId)).as("%s：不剔除那一场", entry.getKey()).isPresent();
        }
        assertThat(allEvictions()).as("每条标记恰好记一个结局").isEqualTo(5.0);
    }

    @Test
    void 多名成员都在观战_按名单顺序逐人串行_前一人删完标记才轮到后一人() {
        watching(7003, 880140);
        watching(7001, 880141);
        watching(7002, 880140); // 与 7003 看同一场

        hooks.beforePrepare(List.of(7003L, 7004L, 7001L, 7002L));

        assertThat(dialer.calls).extracting(Call::observerId).containsExactly(7003L, 7001L, 7002L);
        assertThat(dialer.calls).extracting(Call::battleId).containsExactly(880140L, 880141L, 880140L);
        assertThat(events).containsExactly(
                "observer.remove:880140:7003:enter_gather", "spectate.release:7003",
                "observer.remove:880141:7001:enter_gather", "spectate.release:7001",
                "observer.remove:880140:7002:enter_gather", "spectate.release:7002");
        assertThat(store.calls.get(0)).as("全员的标记一次读出，不是每人一条").isEqualTo("marksOf([7003, 7004, 7001, 7002])");
        assertThat(store.calls.stream().filter(call -> call.startsWith("marksOf")).count()).isEqualTo(1);
        assertThat(store.markCount()).isZero();
        assertThat(evictions("removed")).isEqualTo(3.0);
    }

    @Test
    void 名单里同一个人出现两次_只清退一次() {
        watching(7010, 880150);

        hooks.beforePrepare(List.of(7010L, 7010L));

        assertThat(dialer.calls).hasSize(1);
        assertThat(evictions("removed")).isEqualTo(1.0);
    }

    // ================================================================ beforePrepare：删标记

    @Test
    void 删标记按读到的值_清退期间并发的163刚抢到的新标记不会被删() {
        String old = watching(7330, 880160);
        String fresh = original(7330, 880161).mark();
        dialer.beforeRemove = call -> store.putMark(7330, fresh); // 清退在途时，同一玩家的另一条 163 换了标记

        hooks.beforePrepare(List.of(7330L));

        assertThat(store.calls).contains("release(7330," + old + ")");
        assertThat(store.markOf(7330)).as("W3：只删自己读到的那个值").contains(fresh);
    }

    @Test
    void 同步删标记失败_再异步尽力删一次() {
        String value = watching(7340, 880170);
        store.faults.failNext("release");

        assertThatCode(() -> hooks.beforePrepare(List.of(7340L))).doesNotThrowAnyException();

        assertThat(store.calls).containsExactly("marksOf([7340])", "release(7340," + value + ")", "releaseAsync(7340," + value + ")");
        assertThat(store.markOf(7340)).isEmpty();
        assertThat(evictions("removed")).as("清退本身调通了：结局不受删标记影响").isEqualTo(1.0);
    }

    @Test
    void 同步删标记已生效但应答丢了_结局不明_补发的异步删除无害() {
        String value = watching(7341, 880171);
        store.faults.failNext("release:after");

        hooks.beforePrepare(List.of(7341L));

        assertThat(store.calls).contains("release(7341," + value + ")", "releaseAsync(7341," + value + ")");
        assertThat(store.markOf(7341)).isEmpty();
        assertThat(events.stream().filter(event -> event.equals("spectate.release:7341")).count()).as("真正删掉只发生一次").isEqualTo(1);
    }

    // ================================================================ beforePrepare：时间

    @Test
    void 每人的时限是公式里的3秒_开头读标记与开局后登记各1秒() {
        assertThat(SpectateGatherHooks.PER_MEMBER_BUDGET_MS).as("matched TTL 公式里每人给清退留的那一项，不能改大")
                .isEqualTo(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(SpectateGatherHooks.SMALL_OP_BUDGET_MS).as("算在公式给 Redis 小操作留的 10 s 余量里").isEqualTo(1_000)
                .isLessThan(MatchBudgets.MATCHED_TTL_MARGIN_SECONDS * 1_000L);
        for (int n = 1; n <= MatchBudgets.MAX_GATHER_PLAYERS; n++) {
            long worstHookMs = SpectateGatherHooks.SMALL_OP_BUDGET_MS + n * SpectateGatherHooks.PER_MEMBER_BUDGET_MS;
            long budgetedMs = n * MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS + MatchBudgets.MATCHED_TTL_MARGIN_SECONDS * 1_000L;
            assertThat(worstHookMs).as("%d 人全在观战时钩子的最坏耗时不超过公式里的「每人 3 s + 余量」", n).isLessThan(budgetedMs);
        }
        assertThat(SpectateGatherHooks.MIN_SYNC_RELEASE_MS).as("只是「不值得再同步等一次 Redis」的门槛：远小于每人的时限")
                .isLessThan(SpectateGatherHooks.PER_MEMBER_BUDGET_MS / 10);
    }

    @Test
    void 这名成员的时限所剩无几时_删标记不再同步等Redis_直接走异步() {
        // RPC 耗掉了时限里除最后 30 ms 之外的全部：剩余不足门槛（50 ms）
        String value = watching(7355, 880185);
        dialer.beforeRemove = call -> sleep(SHORT_BUDGET_MS - 30);

        shortHooks().beforePrepare(List.of(7355L));

        assertThat(store.calls).containsExactly("marksOf([7355])", "releaseAsync(7355," + value + ")");
        assertThat(store.markOf(7355)).isEmpty();
        assertThat(evictions("removed")).as("RPC 是调通了的").isEqualTo(1.0);
    }

    @Test
    void 取消使用持久原件_不再消耗读取后续落点的预算() {
        // 让读落点慢 300 ms：事件序列在存储读的那一刻记，借它拖时间
        List<String> slowEvents = new CopyOnWriteArrayList<>() {
            @Override
            public boolean add(String event) {
                if (event.startsWith("spectate.read:")) {
                    sleep(300);
                }
                return super.add(event);
            }
        };
        InMemoryPlacementStore slowPlacements = new InMemoryPlacementStore(slowEvents);
        InMemorySpectateStore slowStore = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), slowPlacements, slowEvents);
        slowPlacements.put(placement(880180, 1));
        slowStore.putMark(7350, original(7350, 880180).mark());

        new SpectateGatherHooks(slowStore, dialer, metrics).beforePrepare(List.of(7350L));

        assertThat(dialer.calls).singleElement().satisfies(call -> {
            assertThat(call.timeout()).as("RPC 使用每人的 3 秒预算，不等待无关落点读取").isLessThanOrEqualTo(Duration.ofMillis(3_000))
                    .isGreaterThan(Duration.ofMillis(500));
            assertThat(call.hardStopRemainingMs()).isLessThanOrEqualTo(3_000L).isGreaterThan(500L);
        });
        assertThat(slowEvents).noneMatch(event -> event.startsWith("spectate.read:"));
    }

    @Test
    void RemoveObserver迟迟不回_到时限保留原件_下一人照常清退() {
        String first = watching(7360, 880190);
        String second = watching(7361, 880191);
        dialer.hangRemove();
        dialer.beforeRemove = call -> {
            if (call.observerId() == 7361L) {
                dialer.releaseRemove(); // 只让第一人的那一次挂住
            }
        };
        long started = System.nanoTime();

        assertThatCode(() -> shortHooks().beforePrepare(List.of(7360L, 7361L))).doesNotThrowAnyException();

        long took = elapsedMs(started);
        assertThat(took).as("第一人等满了他的时限（%d ms），没有提前放弃", SHORT_BUDGET_MS).isGreaterThanOrEqualTo(SHORT_BUDGET_MS - 20);
        assertThat(took).as("也没有被挂住的 RPC 拖死").isLessThan(NOT_STUCK_MS);
        assertThat(dialer.calls).extracting(Call::observerId).as("第二人没有被第一人的超时连累").containsExactly(7360L, 7361L);
        assertThat(dialer.calls.get(0).timeout()).isLessThanOrEqualTo(Duration.ofMillis(SHORT_BUDGET_MS));
        assertThat(dialer.calls.get(0).hardStopRemainingMs()).isLessThanOrEqualTo(SHORT_BUDGET_MS);
        assertThat(store.calls).as("第一人未获证明，不删除其原件；第二人正常取消").startsWith("marksOf([7360, 7361])");
        assertThat(store.calls).hasSize(2);
        // 第二人的时限是他自己的、从轮到他时起算：正常是同步删；这台机器若恰好卡了一下把他的 200 ms 也耗掉，走异步同样正确
        assertThat(store.calls.get(1)).isIn("release(7361," + second + ")", "releaseAsync(7361," + second + ")");
        assertThat(store.markCount()).isEqualTo(1);
        assertThat(store.markOf(7360)).contains(first);
        assertThat(evictions("rpc_failed")).isEqualTo(1.0);
        assertThat(evictions("removed")).isEqualTo(1.0);
    }

    @Test
    void 落点读取挂起不影响持久原件取消_确认后删标记() {
        String value = watching(7370, 880200);
        store.hang("read");
        long started = System.nanoTime();

        assertThatCode(() -> shortHooks().beforePrepare(List.of(7370L))).doesNotThrowAnyException();

        long took = elapsedMs(started);
        store.resume("read");
        assertThat(took).isGreaterThanOrEqualTo(0).isLessThan(NOT_STUCK_MS);
        assertThat(dialer.calls).as("不触发挂起的落点读取").hasSize(1);
        assertThat(store.calls).containsExactly("marksOf([7370])", "release(7370," + value + ")");
        assertThat(store.markOf(7370)).isEmpty();
        assertThat(evictions("removed")).isEqualTo(1.0);
    }

    @Test
    void 开头读标记迟迟不回_至多等小操作的时限_然后全部跳过() {
        String value = watching(7380, 880210);
        store.hang("marksOf");
        long started = System.nanoTime();

        assertThatCode(() -> shortHooks().beforePrepare(List.of(7380L))).doesNotThrowAnyException();

        long took = elapsedMs(started);
        store.resume("marksOf");
        assertThat(took).isGreaterThanOrEqualTo(SHORT_BUDGET_MS - 20).isLessThan(NOT_STUCK_MS);
        assertThat(dialer.calls).isEmpty();
        assertThat(store.markOf(7380)).contains(value);
        assertThat(anomalies("mark_read_failed")).isEqualTo(1.0);
    }

    // ================================================================ beforePrepare：永不阻断开局

    @Test
    void 协作者违约抛出任何东西_连同Error_钩子都不往外抛() {
        watching(7390, 880220);
        watching(7391, 880221);

        store.faults.failNext("marksOf", new IllegalStateException("存储的 bug"));
        assertThatCode(() -> hooks.beforePrepare(List.of(7390L))).doesNotThrowAnyException();

        dialer.beforeRemove = call -> {
            throw new AssertionError("直拨器里抛了 Error");
        };
        assertThatCode(() -> hooks.beforePrepare(List.of(7391L))).doesNotThrowAnyException();

        assertThatCode(() -> hooks.beforePrepare(null)).doesNotThrowAnyException();
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatCode(() -> hooks.beforePrepare(withNull)).doesNotThrowAnyException();
    }

    @Test
    void 直拨器异常保留原件而不连累后人_落点读故障不参与取消() {
        // 第一人：读落点时存储抛了约定之外的异常；第二人：直拨器抛异常；第三人：一切正常
        watching(7392, 880222);
        watching(7393, 880223);
        watching(7394, 880224);
        store.faults.failNext("read", new IllegalArgumentException("存储的 bug"));
        dialer.beforeRemove = call -> {
            if (call.observerId() == 7393L) {
                throw new IllegalStateException("直拨器的 bug");
            }
        };

        assertThatCode(() -> hooks.beforePrepare(List.of(7392L, 7393L, 7394L))).doesNotThrowAnyException();

        assertThat(dialer.calls).extracting(Call::observerId).as("三人均按原件寻址").containsExactly(7392L, 7393L, 7394L);
        assertThat(store.markCount()).as("只保留取消未知的第二人原件").isEqualTo(1);
        assertThat(evictions("read_failed")).isZero();
        assertThat(evictions("rpc_failed")).isEqualTo(1.0);
        assertThat(evictions("removed")).isEqualTo(2.0);
        assertThat(allEvictions()).isEqualTo(3.0);
    }

    @Test
    void 删标记时存储违约抛运行时异常_同样改走异步_后面的人照常() {
        String first = watching(7395, 880225);
        watching(7396, 880226);
        store.faults.failNext("release", new IllegalStateException("存储的 bug"));

        assertThatCode(() -> hooks.beforePrepare(List.of(7395L, 7396L))).doesNotThrowAnyException();

        assertThat(store.calls).contains("releaseAsync(7395," + first + ")");
        assertThat(store.markCount()).isZero();
        assertThat(evictions("removed")).isEqualTo(2.0);
    }

    // ================================================================ onStarted

    @Test
    void 开局成功后登记进可观战索引_成员是battle_id_分数是落点的created_at_ms() {
        BattlePlacement started = placement(0xF000_0000_0000_0002L, 1);
        placements.put(started);

        hooks.onStarted(started);

        assertThat(store.watchable()).containsExactly("17293822569102704642");
        assertThat(store.scoreOf("17293822569102704642")).hasValue(T0 - 1_234);
        assertThat(store.calls).containsExactly("publish(17293822569102704642#1)");
        assertThat(anomalies("publish_failed")).isZero();
    }

    @Test
    void 换节点重试后的落点_按attempt为2登记() {
        BattlePlacement rewritten = placement(880230, 2);
        placements.put(rewritten);

        hooks.onStarted(rewritten);

        assertThat(store.isWatchable(880230)).isTrue();
        assertThat(store.calls).containsExactly("publish(880230#2)");
    }

    @Test
    void 可见性闸_私密局与漏填可见性的局只留落点_不登记可观战索引_不计publish_failed() {
        BattlePlacement hidden = placement(880235, 1).toBuilder().setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE_VALUE).build();
        BattlePlacement legacy = placement(880236, 1).toBuilder().clearSpectateVisibility().build();
        placements.put(hidden);
        placements.put(legacy);

        assertThatCode(() -> hooks.onStarted(hidden)).doesNotThrowAnyException();
        assertThatCode(() -> hooks.onStarted(legacy)).doesNotThrowAnyException();

        assertThat(store.watchable()).as("列表与随机观战都挑不到它").isEmpty();
        assertThat(store.calls).as("根本不发登记").noneMatch(call -> call.startsWith("publish("));
        assertThat(placements.stored(880235)).as("落点照留：179 补签靠它定位 battle").contains(hidden);
        assertThat(placements.stored(880236)).contains(legacy);
        assertThat(anomalies("publish_failed")).as("不公开是正常出口，不是登记失败").isZero();
    }

    @Test
    void 落点已不是这一次的attempt_不登记_计publish_failed() {
        placements.put(placement(880240, 2)); // 库里是换节点之后的记录
        BattlePlacement older = placement(880240, 1);

        assertThatCode(() -> hooks.onStarted(older)).doesNotThrowAnyException();

        assertThat(store.isWatchable(880240)).as("索引里出现的成员，其落点必须是最终那一次写的").isFalse();
        assertThat(anomalies("publish_failed")).isEqualTo(1.0);
    }

    @Test
    void 落点记录不在_不登记_计publish_failed() {
        assertThatCode(() -> hooks.onStarted(placement(880250, 1))).doesNotThrowAnyException();

        assertThat(store.watchable()).isEmpty();
        assertThat(anomalies("publish_failed")).isEqualTo(1.0);
    }

    @Test
    void 登记出错只计数_不抛_这一场只是不进列表() {
        BattlePlacement started = placement(880260, 1);
        placements.put(started);
        store.faults.failNext("publish");

        assertThatCode(() -> hooks.onStarted(started)).doesNotThrowAnyException();

        assertThat(store.watchable()).isEmpty();
        assertThat(placements.stored(880260)).as("补签定位靠的落点不受影响").contains(started);
        assertThat(anomalies("publish_failed")).isEqualTo(1.0);
        assertThat(store.calls).as("不重试").containsExactly("publish(880260#1)");
    }

    @Test
    void 登记迟迟不回_至多等小操作的时限() {
        BattlePlacement started = placement(880270, 1);
        placements.put(started);
        store.hang("publish");
        long startedNanos = System.nanoTime();

        assertThatCode(() -> shortHooks().onStarted(started)).doesNotThrowAnyException();

        long took = elapsedMs(startedNanos);
        store.resume("publish");
        assertThat(took).isGreaterThanOrEqualTo(SHORT_BUDGET_MS - 20).isLessThan(NOT_STUCK_MS);
        assertThat(anomalies("publish_failed")).isEqualTo(1.0);
    }

    @Test
    void 登记时存储违约抛出别的异常_或落点为null_同样不抛并计publish_failed() {
        BattlePlacement started = placement(880280, 1);
        placements.put(started);
        store.faults.failNext("publish", new IllegalStateException("存储的 bug"));

        assertThatCode(() -> hooks.onStarted(started)).doesNotThrowAnyException();
        assertThatCode(() -> hooks.onStarted(null)).doesNotThrowAnyException();

        assertThat(anomalies("publish_failed")).isEqualTo(2.0);
    }

    // ================================================================ 形状

    @Test
    void 它是开局管线的GatherHooks_时限必须为正_协作者不能缺() {
        assertThat(hooks).isInstanceOf(GatherHooks.class);
        assertThatThrownBy(() -> new SpectateGatherHooks(null, dialer, metrics)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateGatherHooks(store, null, metrics)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateGatherHooks(store, dialer, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateGatherHooks(store, dialer, metrics, 0, 1_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpectateGatherHooks(store, dialer, metrics, 3_000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
