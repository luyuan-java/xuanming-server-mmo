package com.game.common.combatai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 事件驱动有限状态机（完整移植 Go {@code looplab/fsm} 核心设计，纯 JDK 21，与 C++ {@code combat_ai::Fsm} / Go {@code combatai.Fsm} 对齐）。
 *
 * <ol>
 *   <li>声明式多源转移表（{@link #addEvent} / {@link #addEvents}）：一条规则将多个源状态映射到同一目标状态，另支持 {@link #addAnyTransition}。</li>
 *   <li>完整 5 阶段回调管线：BeforeEvent / BeforeAnyEvent（可 Cancel）→ LeaveState / LeaveAnyState / OnLeave（可 Cancel / Async）
 *       → 状态提交 + 规则 onTransition → OnEnter / EnterState / EnterAnyState → AfterEvent / AfterAnyEvent。</li>
 *   <li>异步两阶段迁移（{@link EventView#async()} + {@link #commitPendingTransition} / {@link #cancelPendingTransition}），
 *       挂起期间新触发 fail-closed 返回 {@link Status#IN_PROGRESS}。</li>
 *   <li>自省与可视化：{@link #canTrigger} / {@link #availableTransitions} / {@link #exportMermaid}（{@code stateDiagram-v2}）。</li>
 *   <li>重入保护：同步回调内再调 {@link #trigger} 进入有界延迟队列（上限 16）顺序消化，避免递归破坏状态不变量。</li>
 * </ol>
 */
public final class Fsm<S, E, C> {

    public static final int MAX_DEFERRED_EVENTS = 16;

    public enum Status {
        /** 同步转移完成。 */
        OK,
        /** Leave 回调调用了 {@link EventView#async()}，等待提交或取消。 */
        ASYNC_PENDING,
        /** 无匹配转移或 Guard 未通过。 */
        NO_TRANSITION,
        /** Before 或 Leave 回调调用了 {@link EventView#cancel(int)}。 */
        CANCELED,
        /** 已有挂起的异步转移未收尾，拒绝并发新事件（fail-closed）。 */
        IN_PROGRESS,
        /** 回调内重入队列已满（fail-closed）。 */
        QUEUE_FULL
    }

    public record Result(Status status, int cancelError) {
        public boolean ok() {
            return status == Status.OK;
        }

        public boolean pendingAsync() {
            return status == Status.ASYNC_PENDING;
        }
    }

    @FunctionalInterface
    public interface TransitionHook<S, E, C> {
        void accept(S from, S to, E event, C ctx);
    }

    /** 回调阶段可读写的事件视图（按阶段收紧 Cancel / Async 权限）。 */
    public static final class EventView<S, E> {
        private final E event;
        private final S src;
        private final S dst;
        private boolean allowCancel;
        private boolean allowAsync;
        private boolean canceled;
        private boolean async;
        private int cancelError;

        EventView(E event, S src, S dst) {
            this.event = event;
            this.src = src;
            this.dst = dst;
        }

        public E event() {
            return event;
        }

        public S src() {
            return src;
        }

        public S dst() {
            return dst;
        }

        /** 在 Before* 或 Leave* 阶段中止本次转移（fail-closed，状态不改变，不进入 Enter / After）。 */
        public void cancel() {
            cancel(0);
        }

        public void cancel(int errorCode) {
            if (!allowCancel) {
                return;
            }
            canceled = true;
            cancelError = errorCode;
            async = false;
        }

        /** 在 Leave* 阶段声明异步挂起，等待外部完成后调用 {@link Fsm#commitPendingTransition}。 */
        public void async() {
            if (!allowAsync || canceled) {
                return;
            }
            async = true;
        }

        public boolean canceled() {
            return canceled;
        }

        public int cancelError() {
            return cancelError;
        }

        public boolean isAsync() {
            return async;
        }

        void setPhasePermissions(boolean allowCancel, boolean allowAsync) {
            this.allowCancel = allowCancel;
            this.allowAsync = allowAsync;
        }
    }

    /** {@code looplab/fsm} 风格的声明式事件描述：一条描述将多个源状态映射到同一目标状态。 */
    public record EventDesc<S, E, C>(E event, List<S> srcs, S dst,
                                     Predicate<C> guard, TransitionHook<S, E, C> onTransition) {
        public EventDesc {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(srcs, "srcs");
            Objects.requireNonNull(dst, "dst");
            srcs = List.copyOf(srcs);
        }

        public static <S, E, C> EventDesc<S, E, C> of(E event, List<S> srcs, S dst) {
            return new EventDesc<>(event, srcs, dst, null, null);
        }

        public static <S, E, C> EventDesc<S, E, C> of(E event, List<S> srcs, S dst,
                                                      Predicate<C> guard, TransitionHook<S, E, C> onTransition) {
            return new EventDesc<>(event, srcs, dst, guard, onTransition);
        }
    }

    private record TransitionKey<S, E>(S from, E event) {
    }

    private record TransitionRule<S, E, C>(S from, E event, S to,
                                           Predicate<C> guard, TransitionHook<S, E, C> onTransition) {
    }

    private record AnyTransitionRule<S, E, C>(S to, Predicate<C> guard,
                                              TransitionHook<S, E, C> onTransition, boolean allowSelf) {
    }

    private record MatchResult<S>(boolean matched, boolean anyRule, int ruleIndex, S to) {
        private static final MatchResult<?> NONE = new MatchResult<>(false, false, -1, null);

        @SuppressWarnings("unchecked")
        static <S> MatchResult<S> none() {
            return (MatchResult<S>) NONE;
        }
    }

    private record PendingAsync<S, E>(S from, E event, MatchResult<S> match) {
    }

    private S current;
    private boolean inTransition;
    private PendingAsync<S, E> pendingAsync;
    private final Object[] deferredQueue = new Object[MAX_DEFERRED_EVENTS];
    private int deferredHead;
    private int deferredCount;

    private final Map<TransitionKey<S, E>, List<TransitionRule<S, E, C>>> transitions = new LinkedHashMap<>();
    private final Map<E, List<AnyTransitionRule<S, E, C>>> anyTransitions = new LinkedHashMap<>();
    private final Set<E> eventOrder = new LinkedHashSet<>();

    private final Map<E, BiConsumer<EventView<S, E>, C>> onBeforeEvent = new HashMap<>();
    private BiConsumer<EventView<S, E>, C> onBeforeAnyEvent;
    private final Map<S, Consumer<C>> onLeave = new HashMap<>();
    private final Map<S, BiConsumer<EventView<S, E>, C>> onLeaveEvent = new HashMap<>();
    private BiConsumer<EventView<S, E>, C> onLeaveAnyState;
    private final Map<S, Consumer<C>> onEnter = new HashMap<>();
    private final Map<S, BiConsumer<EventView<S, E>, C>> onEnterEvent = new HashMap<>();
    private BiConsumer<EventView<S, E>, C> onEnterAnyState;
    private final Map<E, BiConsumer<EventView<S, E>, C>> onAfterEvent = new HashMap<>();
    private BiConsumer<EventView<S, E>, C> onAfterAnyEvent;

    public Fsm(S initialState) {
        this.current = Objects.requireNonNull(initialState, "initialState");
    }

    public void addTransition(S from, E event, S to) {
        addTransition(from, event, to, null, null);
    }

    /** 注册显式转移规则。同一 (from, event) 允许配多条不同 Guard，按注册顺序取首条命中。 */
    public void addTransition(S from, E event, S to, Predicate<C> guard, TransitionHook<S, E, C> onTransition) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(to, "to");
        TransitionKey<S, E> key = new TransitionKey<>(from, event);
        transitions.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new TransitionRule<>(from, event, to, guard, onTransition));
        eventOrder.add(event);
    }

    public void addEvent(EventDesc<S, E, C> desc) {
        Objects.requireNonNull(desc, "desc");
        for (S src : desc.srcs()) {
            addTransition(src, desc.event(), desc.dst(), desc.guard(), desc.onTransition());
        }
    }

    @SafeVarargs
    public final void addEvents(EventDesc<S, E, C>... descs) {
        for (EventDesc<S, E, C> desc : descs) {
            addEvent(desc);
        }
    }

    public void addEvents(List<EventDesc<S, E, C>> descs) {
        for (EventDesc<S, E, C> desc : descs) {
            addEvent(desc);
        }
    }

    public void addAnyTransition(E event, S to) {
        addAnyTransition(event, to, null, null, false);
    }

    /** 注册任意态可响应的全局转移（优先级低于显式规则；{@code allowSelf=false} 时已在目标态则忽略）。 */
    public void addAnyTransition(E event, S to, Predicate<C> guard,
                                 TransitionHook<S, E, C> onTransition, boolean allowSelf) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(to, "to");
        anyTransitions.computeIfAbsent(event, ignored -> new ArrayList<>())
                .add(new AnyTransitionRule<>(to, guard, onTransition, allowSelf));
        eventOrder.add(event);
    }

    // ---- 5 阶段回调注册 ----

    public void onBeforeEvent(E event, BiConsumer<EventView<S, E>, C> hook) {
        onBeforeEvent.put(Objects.requireNonNull(event, "event"), hook);
    }

    public void onBeforeAnyEvent(BiConsumer<EventView<S, E>, C> hook) {
        onBeforeAnyEvent = hook;
    }

    public void onLeave(S state, Consumer<C> hook) {
        onLeave.put(Objects.requireNonNull(state, "state"), hook);
    }

    public void onLeaveState(S state, BiConsumer<EventView<S, E>, C> hook) {
        onLeaveEvent.put(Objects.requireNonNull(state, "state"), hook);
    }

    public void onLeaveAnyState(BiConsumer<EventView<S, E>, C> hook) {
        onLeaveAnyState = hook;
    }

    public void onEnter(S state, Consumer<C> hook) {
        onEnter.put(Objects.requireNonNull(state, "state"), hook);
    }

    public void onEnterState(S state, BiConsumer<EventView<S, E>, C> hook) {
        onEnterEvent.put(Objects.requireNonNull(state, "state"), hook);
    }

    public void onEnterAnyState(BiConsumer<EventView<S, E>, C> hook) {
        onEnterAnyState = hook;
    }

    public void onAfterEvent(E event, BiConsumer<EventView<S, E>, C> hook) {
        onAfterEvent.put(Objects.requireNonNull(event, "event"), hook);
    }

    public void onAfterAnyEvent(BiConsumer<EventView<S, E>, C> hook) {
        onAfterAnyEvent = hook;
    }

    // ---- 查询与可视化 ----

    public boolean canTrigger(E event, C ctx) {
        if (pendingAsync != null) {
            return false;
        }
        return findMatch(current, event, ctx).matched();
    }

    /** 返回当前状态下所有可触发的事件（按事件首次注册顺序，完全确定性）。 */
    public List<E> availableTransitions(C ctx) {
        if (pendingAsync != null) {
            return List.of();
        }
        List<E> result = new ArrayList<>();
        for (E event : eventOrder) {
            if (findMatch(current, event, ctx).matched()) {
                result.add(event);
            }
        }
        return result;
    }

    /** 导出 Mermaid {@code stateDiagram-v2} 流程图文本。 */
    public String exportMermaid(Function<S, String> stateName, Function<E, String> eventName) {
        Objects.requireNonNull(stateName, "stateName");
        Objects.requireNonNull(eventName, "eventName");
        StringBuilder sb = new StringBuilder("stateDiagram-v2\n");
        for (List<TransitionRule<S, E, C>> list : transitions.values()) {
            for (TransitionRule<S, E, C> rule : list) {
                sb.append("    ")
                        .append(stateName.apply(rule.from()))
                        .append(" --> ")
                        .append(stateName.apply(rule.to()))
                        .append(" : ")
                        .append(eventName.apply(rule.event()))
                        .append('\n');
            }
        }
        for (Map.Entry<E, List<AnyTransitionRule<S, E, C>>> entry : anyTransitions.entrySet()) {
            String evName = eventName.apply(entry.getKey());
            for (AnyTransitionRule<S, E, C> rule : entry.getValue()) {
                sb.append("    [*] --> ")
                        .append(stateName.apply(rule.to()))
                        .append(" : ")
                        .append(evName)
                        .append(" (any)\n");
            }
        }
        return sb.toString();
    }

    // ---- 触发与异步收尾 ----

    public Result triggerEx(E event, C ctx) {
        Objects.requireNonNull(event, "event");
        if (pendingAsync != null) {
            return new Result(Status.IN_PROGRESS, 0);
        }
        if (inTransition) {
            if (deferredCount >= MAX_DEFERRED_EVENTS) {
                return new Result(Status.QUEUE_FULL, 0);
            }
            deferredQueue[(deferredHead + deferredCount) % MAX_DEFERRED_EVENTS] = event;
            deferredCount++;
            return new Result(Status.OK, 0);
        }

        Result first = executeSingle(event, ctx);
        if (first.pendingAsync()) {
            return first;
        }
        drainDeferredQueue(ctx);
        return first;
    }

    public boolean trigger(E event, C ctx) {
        Result res = triggerEx(event, ctx);
        return res.ok() || res.pendingAsync();
    }

    public boolean hasPendingTransition() {
        return pendingAsync != null;
    }

    /** 完成先前由 {@link EventView#async()} 挂起的迁移：Commit → OnTransition → Enter* → After* → 消化重入队列。 */
    public Result commitPendingTransition(C ctx) {
        if (pendingAsync == null) {
            return new Result(Status.NO_TRANSITION, 0);
        }
        if (inTransition) {
            return new Result(Status.IN_PROGRESS, 0);
        }
        PendingAsync<S, E> pending = pendingAsync;
        pendingAsync = null;

        finishCommitAndAfter(pending.from(), pending.match(), pending.event(), ctx);
        drainDeferredQueue(ctx);
        return new Result(Status.OK, 0);
    }

    /** 放弃先前挂起的异步迁移，保持原状态不变。 */
    public boolean cancelPendingTransition() {
        if (pendingAsync == null) {
            return false;
        }
        pendingAsync = null;
        clearDeferredQueue();
        return true;
    }

    public void reset(S state) {
        this.current = Objects.requireNonNull(state, "state");
        this.inTransition = false;
        this.pendingAsync = null;
        clearDeferredQueue();
    }

    public S current() {
        return current;
    }

    public boolean is(S state) {
        return current.equals(state);
    }

    private void clearDeferredQueue() {
        java.util.Arrays.fill(deferredQueue, null);
        deferredHead = 0;
        deferredCount = 0;
    }

    private MatchResult<S> findMatch(S from, E event, C ctx) {
        List<TransitionRule<S, E, C>> specific = transitions.get(new TransitionKey<>(from, event));
        if (specific != null) {
            for (int i = 0; i < specific.size(); i++) {
                TransitionRule<S, E, C> rule = specific.get(i);
                if (rule.guard() == null || rule.guard().test(ctx)) {
                    return new MatchResult<>(true, false, i, rule.to());
                }
            }
        }
        List<AnyTransitionRule<S, E, C>> globals = anyTransitions.get(event);
        if (globals != null) {
            for (int i = 0; i < globals.size(); i++) {
                AnyTransitionRule<S, E, C> rule = globals.get(i);
                if (!rule.allowSelf() && rule.to().equals(from)) {
                    continue;
                }
                if (rule.guard() == null || rule.guard().test(ctx)) {
                    return new MatchResult<>(true, true, i, rule.to());
                }
            }
        }
        return MatchResult.none();
    }

    private Result executeSingle(E event, C ctx) {
        S from = current;
        MatchResult<S> match = findMatch(from, event, ctx);
        if (!match.matched()) {
            return new Result(Status.NO_TRANSITION, 0);
        }

        EventView<S, E> ev = new EventView<>(event, from, match.to());
        inTransition = true;
        try {
            // 阶段 1：BeforeEvent -> BeforeAnyEvent（允许 Cancel，不允许 Async）
            ev.setPhasePermissions(true, false);
            BiConsumer<EventView<S, E>, C> beforeHook = onBeforeEvent.get(event);
            if (beforeHook != null) {
                beforeHook.accept(ev, ctx);
                if (ev.canceled()) {
                    return new Result(Status.CANCELED, ev.cancelError());
                }
            }
            if (onBeforeAnyEvent != null) {
                onBeforeAnyEvent.accept(ev, ctx);
                if (ev.canceled()) {
                    return new Result(Status.CANCELED, ev.cancelError());
                }
            }

            // 阶段 2：LeaveState -> LeaveAnyState -> OnLeave（允许 Cancel 与 Async）
            ev.setPhasePermissions(true, true);
            BiConsumer<EventView<S, E>, C> leaveEventHook = onLeaveEvent.get(from);
            if (leaveEventHook != null) {
                leaveEventHook.accept(ev, ctx);
                if (ev.canceled()) {
                    return new Result(Status.CANCELED, ev.cancelError());
                }
            }
            if (onLeaveAnyState != null) {
                onLeaveAnyState.accept(ev, ctx);
                if (ev.canceled()) {
                    return new Result(Status.CANCELED, ev.cancelError());
                }
            }
            Consumer<C> simpleLeave = onLeave.get(from);
            if (simpleLeave != null) {
                simpleLeave.accept(ctx);
            }

            if (ev.isAsync()) {
                pendingAsync = new PendingAsync<>(from, event, match);
                return new Result(Status.ASYNC_PENDING, 0);
            }
        } finally {
            inTransition = false;
        }

        // 阶段 3~5：Commit -> Enter -> After
        finishCommitAndAfter(from, match, event, ctx);
        return new Result(Status.OK, 0);
    }

    private void finishCommitAndAfter(S from, MatchResult<S> match, E event, C ctx) {
        EventView<S, E> ev = new EventView<>(event, from, match.to());
        ev.setPhasePermissions(false, false);

        inTransition = true;
        try {
            // 阶段 3：提交新状态 + 规则转移回调
            current = match.to();
            if (match.anyRule()) {
                TransitionHook<S, E, C> hook = anyTransitions.get(event).get(match.ruleIndex()).onTransition();
                if (hook != null) {
                    hook.accept(from, match.to(), event, ctx);
                }
            } else {
                TransitionHook<S, E, C> hook = transitions.get(new TransitionKey<>(from, event))
                        .get(match.ruleIndex()).onTransition();
                if (hook != null) {
                    hook.accept(from, match.to(), event, ctx);
                }
            }

            // 阶段 4：OnEnter -> EnterState -> EnterAnyState
            Consumer<C> simpleEnter = onEnter.get(match.to());
            if (simpleEnter != null) {
                simpleEnter.accept(ctx);
            }
            BiConsumer<EventView<S, E>, C> enterEventHook = onEnterEvent.get(match.to());
            if (enterEventHook != null) {
                enterEventHook.accept(ev, ctx);
            }
            if (onEnterAnyState != null) {
                onEnterAnyState.accept(ev, ctx);
            }

            // 阶段 5：AfterEvent -> AfterAnyEvent
            BiConsumer<EventView<S, E>, C> afterHook = onAfterEvent.get(event);
            if (afterHook != null) {
                afterHook.accept(ev, ctx);
            }
            if (onAfterAnyEvent != null) {
                onAfterAnyEvent.accept(ev, ctx);
            }
        } finally {
            inTransition = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void drainDeferredQueue(C ctx) {
        int drained = 0;
        while (deferredCount > 0 && drained < MAX_DEFERRED_EVENTS && pendingAsync == null) {
            E next = (E) deferredQueue[deferredHead];
            deferredQueue[deferredHead] = null;
            deferredHead = (deferredHead + 1) % MAX_DEFERRED_EVENTS;
            deferredCount--;
            drained++;
            if (executeSingle(next, ctx).pendingAsync()) {
                break;
            }
        }
    }
}
