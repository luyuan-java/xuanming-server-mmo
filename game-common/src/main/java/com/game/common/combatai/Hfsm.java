package com.game.common.combatai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 分层有限状态机（移植 HFSM2 核心思想，纯 JDK 21，与 C++ {@code combat_ai::Hfsm} / Go {@code combatai.Hfsm} 对齐）。
 *
 * <ol>
 *   <li>状态以扁平数组存放，按整型 stateId 索引。</li>
 *   <li>自动下潜初始子态（{@code initialChild}）：切到父态自动落至叶子初始态。</li>
 *   <li>LCA（最近公共祖先）切换：只自底向上退出 A → LCA 之下，再自顶向下进入 LCA 之下 → B，共享父态不重复退出重入。</li>
 *   <li>事件冒泡（{@link #dispatch}）：从当前叶子态向父态传递，首个 Handled / TransitionTo 截断冒泡。</li>
 *   <li>O(1) 活跃祖先查询：64 位 {@code long} 位图快速路径，超过 64 个状态自动退化到祖先链遍历。</li>
 * </ol>
 */
public final class Hfsm<C, E> {

    public static final int INVALID_STATE = -1;
    public static final int MAX_HIERARCHY_DEPTH = 16;
    public static final int MAX_PENDING_TRANSITIONS = 16;

    @FunctionalInterface
    public interface UpdateFn<C> {
        /** 返回下一目标状态 id；返回 {@link #INVALID_STATE} 表示保持当前状态。 */
        int update(C ctx, int deltaMs);
    }

    public record EventOutcome(boolean handled, int transitionTarget) {
        private static final EventOutcome IGNORED = new EventOutcome(false, INVALID_STATE);
        private static final EventOutcome HANDLED = new EventOutcome(true, INVALID_STATE);

        public static EventOutcome ignored() {
            return IGNORED;
        }

        /** 已处理、不迁移（名字避开记录分量 {@code handled()} 的访问器）。 */
        public static EventOutcome consumed() {
            return HANDLED;
        }

        public static EventOutcome transitionTo(int target) {
            return new EventOutcome(true, target);
        }

        public boolean hasTransition() {
            return transitionTarget != INVALID_STATE;
        }
    }

    public static final class StateConfig<C, E> {
        private int parent = INVALID_STATE;
        private int initialChild = INVALID_STATE;
        private Consumer<C> onEnter;
        private UpdateFn<C> onUpdate;
        private Consumer<C> onExit;
        private BiFunction<E, C, EventOutcome> onEvent;

        public static <C, E> StateConfig<C, E> create() {
            return new StateConfig<>();
        }

        public StateConfig<C, E> parent(int parent) {
            this.parent = parent;
            return this;
        }

        public StateConfig<C, E> initialChild(int initialChild) {
            this.initialChild = initialChild;
            return this;
        }

        public StateConfig<C, E> onEnter(Consumer<C> onEnter) {
            this.onEnter = onEnter;
            return this;
        }

        public StateConfig<C, E> onUpdate(UpdateFn<C> onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        public StateConfig<C, E> onExit(Consumer<C> onExit) {
            this.onExit = onExit;
            return this;
        }

        public StateConfig<C, E> onEvent(BiFunction<E, C, EventOutcome> onEvent) {
            this.onEvent = onEvent;
            return this;
        }
    }

    private static final class StateSlot<C, E> {
        boolean registered;
        StateConfig<C, E> config;
    }

    private final List<StateSlot<C, E>> states = new ArrayList<>();
    private int currentLeaf = INVALID_STATE;
    private long activeMask;
    private boolean started;
    private boolean inTransition;
    private final int[] pendingTargets = new int[MAX_PENDING_TRANSITIONS];
    private int pendingHead;
    private int pendingCount;

    /** 注册状态定义（允许按任意顺序注册父子态，{@link #start} 时统一做环路与深度校验）。 */
    public boolean addState(int id, StateConfig<C, E> config) {
        if (id < 0 || config == null) {
            return false;
        }
        while (states.size() <= id) {
            states.add(new StateSlot<>());
        }
        StateSlot<C, E> slot = states.get(id);
        slot.registered = true;
        slot.config = config;
        return true;
    }

    /** 启动状态机：校验层级无环后，自顶向下进入 {@code initialState} 及其叶子初始子态。 */
    public boolean start(int initialState, C ctx) {
        if (!validateHierarchy() || !hasState(initialState)) {
            return false;
        }
        int targetLeaf = resolveLeaf(initialState);
        if (targetLeaf == INVALID_STATE) {
            return false;
        }

        started = true;
        currentLeaf = INVALID_STATE;
        activeMask = 0L;

        int[] enterPath = new int[MAX_HIERARCHY_DEPTH];
        int enterLen = buildRootToNodePath(targetLeaf, enterPath);
        if (enterLen == 0) {
            started = false;
            return false;
        }

        inTransition = true;
        try {
            for (int i = 0; i < enterLen; i++) {
                currentLeaf = enterPath[i];
                rebuildActiveMask();
                Consumer<C> fn = states.get(enterPath[i]).config.onEnter;
                if (fn != null) {
                    fn.accept(ctx);
                }
            }
        } finally {
            inTransition = false;
        }
        drainPendingTransitions(ctx);
        return true;
    }

    /** 按自顶向下顺序更新当前活跃链（父态先于子态），任意一层返回目标态即触发 LCA 切换。 */
    public void update(C ctx, int deltaMs) {
        if (!started || currentLeaf == INVALID_STATE) {
            return;
        }
        int[] path = new int[MAX_HIERARCHY_DEPTH];
        int len = buildRootToNodePath(currentLeaf, path);
        for (int i = 0; i < len; i++) {
            int stateId = path[i];
            UpdateFn<C> updateFn = states.get(stateId).config.onUpdate;
            if (updateFn != null) {
                int next = updateFn.update(ctx, deltaMs);
                if (next != INVALID_STATE) {
                    transitionTo(next, ctx);
                    return;
                }
                if (!isInState(stateId)) {
                    return;
                }
            }
        }
    }

    /** 事件冒泡：从当前叶子态逐级向父态派发，首个 Handled / TransitionTo 终止冒泡。 */
    public boolean dispatch(E event, C ctx) {
        if (!started || currentLeaf == INVALID_STATE) {
            return false;
        }
        int cursor = currentLeaf;
        int steps = 0;
        while (cursor != INVALID_STATE && steps < MAX_HIERARCHY_DEPTH) {
            steps++;
            StateConfig<C, E> cfg = states.get(cursor).config;
            int parent = cfg.parent;
            if (cfg.onEvent != null) {
                EventOutcome outcome = cfg.onEvent.apply(event, ctx);
                if (outcome != null) {
                    if (outcome.hasTransition()) {
                        transitionTo(outcome.transitionTarget(), ctx);
                        return true;
                    }
                    if (outcome.handled()) {
                        return true;
                    }
                }
            }
            cursor = parent;
        }
        return false;
    }

    /** LCA 切换：支持目标为父态（自动下潜至叶子初始态）或叶子态。 */
    public boolean transitionTo(int target, C ctx) {
        if (!started || !hasState(target)) {
            return false;
        }
        if (inTransition) {
            if (pendingCount >= MAX_PENDING_TRANSITIONS) {
                return false;
            }
            pendingTargets[(pendingHead + pendingCount) % MAX_PENDING_TRANSITIONS] = target;
            pendingCount++;
            return true;
        }

        boolean ok = executeTransition(target, ctx);
        drainPendingTransitions(ctx);
        return ok;
    }

    public boolean started() {
        return started;
    }

    public int currentLeaf() {
        return currentLeaf;
    }

    /** O(1) 查询是否正处于某状态（包含其任意祖先态）。 */
    public boolean isInState(int state) {
        if (!started || currentLeaf == INVALID_STATE || !hasState(state)) {
            return false;
        }
        if (state < 64) {
            return (activeMask & (1L << state)) != 0L;
        }
        int cursor = currentLeaf;
        int steps = 0;
        while (cursor != INVALID_STATE && steps < MAX_HIERARCHY_DEPTH) {
            if (cursor == state) {
                return true;
            }
            cursor = states.get(cursor).config.parent;
            steps++;
        }
        return false;
    }

    private boolean hasState(int id) {
        return id >= 0 && id < states.size() && states.get(id).registered;
    }

    private boolean validateHierarchy() {
        for (int i = 0; i < states.size(); i++) {
            StateSlot<C, E> slot = states.get(i);
            if (!slot.registered) {
                continue;
            }
            int cursor = slot.config.parent;
            int depth = 1;
            while (cursor != INVALID_STATE) {
                if (!hasState(cursor) || cursor == i || depth >= MAX_HIERARCHY_DEPTH) {
                    return false;
                }
                cursor = states.get(cursor).config.parent;
                depth++;
            }
            int child = slot.config.initialChild;
            if (child != INVALID_STATE) {
                if (!hasState(child) || states.get(child).config.parent != i) {
                    return false;
                }
            }
        }
        return true;
    }

    private int resolveLeaf(int state) {
        int cursor = state;
        int depth = 0;
        while (hasState(cursor) && states.get(cursor).config.initialChild != INVALID_STATE) {
            if (++depth >= MAX_HIERARCHY_DEPTH) {
                return INVALID_STATE;
            }
            cursor = states.get(cursor).config.initialChild;
        }
        return hasState(cursor) ? cursor : INVALID_STATE;
    }

    private int buildRootToNodePath(int node, int[] out) {
        int[] rev = new int[MAX_HIERARCHY_DEPTH];
        int count = 0;
        int cursor = node;
        while (cursor != INVALID_STATE) {
            if (!hasState(cursor) || count >= MAX_HIERARCHY_DEPTH) {
                return 0;
            }
            rev[count++] = cursor;
            cursor = states.get(cursor).config.parent;
        }
        for (int i = 0; i < count; i++) {
            out[i] = rev[count - 1 - i];
        }
        return count;
    }

    private void rebuildActiveMask() {
        long mask = 0L;
        int cursor = currentLeaf;
        int steps = 0;
        while (cursor != INVALID_STATE && steps < MAX_HIERARCHY_DEPTH) {
            if (cursor < 64) {
                mask |= (1L << cursor);
            }
            cursor = states.get(cursor).config.parent;
            steps++;
        }
        activeMask = mask;
    }

    private boolean executeTransition(int target, C ctx) {
        int targetLeaf = resolveLeaf(target);
        if (targetLeaf == INVALID_STATE) {
            return false;
        }
        int[] fromPath = new int[MAX_HIERARCHY_DEPTH];
        int[] toPath = new int[MAX_HIERARCHY_DEPTH];
        int fromLen = buildRootToNodePath(currentLeaf, fromPath);
        int toLen = buildRootToNodePath(targetLeaf, toPath);
        if (fromLen == 0 || toLen == 0) {
            return false;
        }

        int lcaPrefix = 0;
        while (lcaPrefix < fromLen && lcaPrefix < toLen && fromPath[lcaPrefix] == toPath[lcaPrefix]) {
            lcaPrefix++;
        }
        if (lcaPrefix == fromLen && lcaPrefix == toLen) {
            lcaPrefix = fromLen - 1;
        }

        inTransition = true;
        try {
            // 1) 自底向上退出旧分支
            for (int i = fromLen; i > lcaPrefix; i--) {
                int exiting = fromPath[i - 1];
                Consumer<C> exitFn = states.get(exiting).config.onExit;
                if (exitFn != null) {
                    exitFn.accept(ctx);
                }
                currentLeaf = i >= 2 ? fromPath[i - 2] : INVALID_STATE;
                rebuildActiveMask();
            }
            // 2) 自顶向下进入新分支
            for (int i = lcaPrefix; i < toLen; i++) {
                int entering = toPath[i];
                currentLeaf = entering;
                rebuildActiveMask();
                Consumer<C> enterFn = states.get(entering).config.onEnter;
                if (enterFn != null) {
                    enterFn.accept(ctx);
                }
            }
        } finally {
            inTransition = false;
        }
        return true;
    }

    private void drainPendingTransitions(C ctx) {
        int drained = 0;
        while (pendingCount > 0 && drained < MAX_PENDING_TRANSITIONS) {
            int next = pendingTargets[pendingHead];
            pendingHead = (pendingHead + 1) % MAX_PENDING_TRANSITIONS;
            pendingCount--;
            drained++;
            executeTransition(next, ctx);
        }
    }
}
