package com.game.match.testing;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer;
import com.game.proto.AddObserverRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link ObserverDialer} 的测试替身：给 163（{@code WatchBattleService}）与开局钩子（{@code SpectateGatherHooks}）的组件测试用——
 * 记下每次调用（先后次序、发往哪条落点、超时、硬截止的剩余、reason、完整的登记请求），四种结局可以脚本化，也能让一次调用挂起。
 * 不发任何 RPC；真的直拨与结局分类由 {@code DefaultObserverDialer} 配真 Triple 回环去测。
 *
 * <pre>
 * FakeObserverDialer dialer = new FakeObserverDialer(events);         // events：与 InMemorySpectateStore 等共用的事件序列（可省）
 * // 缺省：add / remove / removeAsync 都调通（Replied(0)）
 * dialer.nextAdd(new Outcome.Replied(1004));                           // 下一次 add 的结局（先进先出，用完回到缺省）
 * dialer.onAdd(77, new Outcome.Dead());                                // 发往 77 号战斗的 add 一律是这个结局（先于队列；clearAdd(77) 取消）
 * dialer.nextRemove(new Outcome.Unknown("超时"));                       // remove 与 removeAsync 共用一个队列
 * dialer.beforeAdd = call -> placements.put(rewritten);                // 测试缝：add 记下调用之后、给出结局之前（模拟 RPC 在途期间世界变了）
 * dialer.hangAdd();                                                    // 之后的 add 挂起：等到 releaseAdd() 或「超时与硬截止里先到的那个」，到点回 Unknown
 * dialer.hangRemoveAsync();                                            // 之后 removeAsync 的 future 不完成，直到 releaseRemoveAsync()
 * assertThat(dialer.calls).extracting(Call::kind).containsExactly(Kind.REMOVE, Kind.ADD);     // 换场：Remove 先于 Add
 * assertThat(dialer.adds()).singleElement().satisfies(c -> assertThat(c.request().getRouting().getZoneId()).isEqualTo(2));
 * assertThat(events).containsSubsequence("observer.remove:76:1001:rewatch", "observer.add:77:1001");
 * </pre>
 * 同真实现的约定：硬截止进来时已过 → 不算发出，回 {@code NotDelivered}（照样记进 {@link #calls}，{@code hardStopRemainingMs = 0}）。永不抛异常。线程安全。
 */
public final class FakeObserverDialer implements ObserverDialer {

    /** 调用的种类。 */
    public enum Kind { ADD, REMOVE, REMOVE_ASYNC }

    /**
     * 一次调用。
     *
     * @param placement           发往的落点记录
     * @param observerId          观众（add 取自请求的 {@code observer_player_id}）
     * @param reason              清退的原因；add 为 null
     * @param request             add 的完整请求；清退为 null
     * @param timeout             调用方给的超时；removeAsync 为 null
     * @param hardStopRemainingMs 调用那一刻硬截止还剩多少毫秒；removeAsync 为 -1
     */
    public record Call(Kind kind, BattlePlacement placement, long observerId, String reason, AddObserverRequest request, Duration timeout,
                       long hardStopRemainingMs) {

        public long battleId() {
            return placement.getBattleId();
        }
    }

    /** 每次调用，按发起顺序（三种都记在这里）。 */
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** 事件序列：{@code "observer.add:<battle>:<pid>"} / {@code "observer.remove:<battle>:<pid>:<reason>"} / {@code "observer.removeAsync:<battle>:<pid>:<reason>"}。 */
    public final List<String> events;
    /** 测试缝：add 记下调用之后、给出结局之前在调用线程上调一次（可以去改落点 / 索引 / 票据）。 */
    public volatile Consumer<Call> beforeAdd;
    /** 测试缝：同步的 remove 记下调用之后、给出结局之前调一次。 */
    public volatile Consumer<Call> beforeRemove;

    private final Deque<Outcome> addScript = new ArrayDeque<>();
    private final Deque<Outcome> removeScript = new ArrayDeque<>();
    private final Map<Long, Outcome> addByBattle = new ConcurrentHashMap<>();
    private final Map<Long, Outcome> removeByBattle = new ConcurrentHashMap<>();
    private final List<Pending> pendingAsync = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch addGate;
    private volatile CountDownLatch removeGate;
    private volatile boolean asyncHung;

    /** 一个挂着的 removeAsync：future 与它发起时按脚本定好的结局。 */
    private record Pending(CompletableFuture<Outcome> future, Outcome outcome) {
    }

    public FakeObserverDialer() {
        this(new CopyOnWriteArrayList<>());
    }

    /** @param events 与别的替身共用的事件序列 */
    public FakeObserverDialer(List<String> events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    // ---------------------------------------------------------------- 脚本

    /** 下一次 add 的结局（可以连调：先进先出；用完回到缺省的 {@code Replied(0)}）。 */
    public synchronized FakeObserverDialer nextAdd(Outcome... outcomes) {
        addScript.addAll(List.of(outcomes));
        return this;
    }

    /** 下一次清退（remove 或 removeAsync，按发起顺序）的结局。 */
    public synchronized FakeObserverDialer nextRemove(Outcome... outcomes) {
        removeScript.addAll(List.of(outcomes));
        return this;
    }

    /** 发往这场战斗的 add 一律是这个结局（优先于 {@link #nextAdd} 的队列，直到 {@link #clearAdd}）。 */
    public FakeObserverDialer onAdd(long battleId, Outcome outcome) {
        addByBattle.put(battleId, outcome);
        return this;
    }

    public FakeObserverDialer clearAdd(long battleId) {
        addByBattle.remove(battleId);
        return this;
    }

    /** 发往这场战斗的清退一律是这个结局（优先于 {@link #nextRemove} 的队列，直到 {@link #clearRemove}）。 */
    public FakeObserverDialer onRemove(long battleId, Outcome outcome) {
        removeByBattle.put(battleId, outcome);
        return this;
    }

    public FakeObserverDialer clearRemove(long battleId) {
        removeByBattle.remove(battleId);
        return this;
    }

    /** 之后的 add 挂起：等到 {@link #releaseAdd} 才给出结局；等到「超时与硬截止里先到的那个」还没放行就回 {@code Unknown}（像一次真的超时）。 */
    public FakeObserverDialer hangAdd() {
        addGate = new CountDownLatch(1);
        return this;
    }

    public FakeObserverDialer releaseAdd() {
        CountDownLatch gate = addGate;
        addGate = null;
        if (gate != null) {
            gate.countDown();
        }
        return this;
    }

    /** 之后同步的 remove 挂起（语义同 {@link #hangAdd}）。 */
    public FakeObserverDialer hangRemove() {
        removeGate = new CountDownLatch(1);
        return this;
    }

    public FakeObserverDialer releaseRemove() {
        CountDownLatch gate = removeGate;
        removeGate = null;
        if (gate != null) {
            gate.countDown();
        }
        return this;
    }

    /** 之后 removeAsync 返回的 future 不完成，直到 {@link #releaseRemoveAsync}（调用本身照样立刻返回）。 */
    public FakeObserverDialer hangRemoveAsync() {
        asyncHung = true;
        return this;
    }

    /** 完成全部挂着的 removeAsync（结局在各自发起时已按脚本定好），之后的 removeAsync 恢复成立即完成。 */
    public FakeObserverDialer releaseRemoveAsync() {
        asyncHung = false;
        for (Pending pending : List.copyOf(pendingAsync)) {
            pendingAsync.remove(pending);
            pending.future().complete(pending.outcome());
        }
        return this;
    }

    // ---------------------------------------------------------------- 看调用

    /** 全部 add 调用，按发起顺序。 */
    public List<Call> adds() {
        return calls.stream().filter(call -> call.kind() == Kind.ADD).toList();
    }

    /** 全部清退调用（同步的与异步的），按发起顺序。 */
    public List<Call> removes() {
        return calls.stream().filter(call -> call.kind() != Kind.ADD).toList();
    }

    // ---------------------------------------------------------------- ObserverDialer

    @Override
    public Outcome add(BattlePlacement placement, AddObserverRequest request, Duration timeout, Deadline hardStop) {
        Call call = new Call(Kind.ADD, placement, request.getObserverPlayerId(), null, request, timeout, hardStop.remainingMillis());
        calls.add(call);
        events.add("observer.add:" + Long.toUnsignedString(placement.getBattleId()) + ":" + Long.toUnsignedString(request.getObserverPlayerId()));
        if (hardStop.expired()) {
            return new Outcome.NotDelivered("硬截止已到，没有发出调用");
        }
        Consumer<Call> hook = beforeAdd;
        if (hook != null) {
            hook.accept(call);
        }
        if (!passGate(addGate, timeout, hardStop)) {
            return new Outcome.Unknown("挂起的 add 等到了超时 / 硬截止");
        }
        return decide(addByBattle, addScript, placement.getBattleId());
    }

    @Override
    public Outcome remove(BattlePlacement placement, long observerId, String reason, Duration timeout, Deadline hardStop) {
        Call call = new Call(Kind.REMOVE, placement, observerId, reason, null, timeout, hardStop.remainingMillis());
        calls.add(call);
        events.add("observer.remove:" + Long.toUnsignedString(placement.getBattleId()) + ":" + Long.toUnsignedString(observerId) + ":" + reason);
        if (hardStop.expired()) {
            return new Outcome.NotDelivered("硬截止已到，没有发出调用");
        }
        Consumer<Call> hook = beforeRemove;
        if (hook != null) {
            hook.accept(call);
        }
        if (!passGate(removeGate, timeout, hardStop)) {
            return new Outcome.Unknown("挂起的 remove 等到了超时 / 硬截止");
        }
        return decide(removeByBattle, removeScript, placement.getBattleId());
    }

    @Override
    public CompletableFuture<Outcome> removeAsync(BattlePlacement placement, long observerId, String reason) {
        calls.add(new Call(Kind.REMOVE_ASYNC, placement, observerId, reason, null, null, -1));
        events.add("observer.removeAsync:" + Long.toUnsignedString(placement.getBattleId()) + ":" + Long.toUnsignedString(observerId) + ":" + reason);
        Outcome outcome = decide(removeByBattle, removeScript, placement.getBattleId());
        if (!asyncHung) {
            return CompletableFuture.completedFuture(outcome);
        }
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        pendingAsync.add(new Pending(future, outcome));
        return future;
    }

    // ---------------------------------------------------------------- 内部

    private synchronized Outcome decide(Map<Long, Outcome> byBattle, Deque<Outcome> script, long battleId) {
        Outcome fixed = byBattle.get(battleId);
        if (fixed != null) {
            return fixed;
        }
        Outcome scripted = script.poll();
        return scripted != null ? scripted : new Outcome.Replied(0);
    }

    /** @return true = 没挂起或已放行；false = 等到超时 / 硬截止还没放行 */
    private static boolean passGate(CountDownLatch gate, Duration timeout, Deadline hardStop) {
        if (gate == null) {
            return true;
        }
        long waitNanos = Math.max(1, Math.min(timeout == null ? 0 : timeout.toNanos(), hardStop.remainingNanos()));
        try {
            return gate.await(waitNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
