package com.game.common.combatai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 数据驱动行为树（融合 BehaviorTree.CPP 响应式抢占 / Halt 与 behavior3go 静态树 + 实例黑板分离，
 * 纯 JDK 21，与 C++ {@code combat_ai::BehaviorTreeDef} / Go {@code combatai.BehaviorTreeDef} 对齐）。
 */
public final class BehaviorTree {

    public static final int INVALID_NODE = -1;

    private BehaviorTree() {
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        RUNNING
    }

    /** 整型键类型安全黑板：避免热路径字符串分配。 */
    public static final class Blackboard {
        private final Map<Integer, Object> entries = new HashMap<>();

        public void setLong(int key, long value) {
            entries.put(key, value);
        }

        public void setDouble(int key, double value) {
            entries.put(key, value);
        }

        public void setBoolean(int key, boolean value) {
            entries.put(key, value);
        }

        public long getLongOr(int key, long fallback) {
            Object v = entries.get(key);
            return v instanceof Long l ? l : fallback;
        }

        public double getDoubleOr(int key, double fallback) {
            Object v = entries.get(key);
            return v instanceof Double d ? d : fallback;
        }

        public boolean getBooleanOr(int key, boolean fallback) {
            Object v = entries.get(key);
            return v instanceof Boolean b ? b : fallback;
        }

        public boolean has(int key) {
            return entries.containsKey(key);
        }

        public void remove(int key) {
            entries.remove(key);
        }

        public void clear() {
            entries.clear();
        }
    }

    private static final class NodeMemory {
        int runningChild;
        int repeatCount;
        long cooldownReadyTick;
        boolean running;

        void reset() {
            runningChild = 0;
            repeatCount = 0;
            cooldownReadyTick = 0;
            running = false;
        }
    }

    /** 每个实体独享的运行态快照。 */
    public static final class State {
        private long tickCount;
        private NodeMemory[] memory;
        private final Blackboard blackboard = new Blackboard();

        State(int nodeCount) {
            this.memory = allocateMemory(nodeCount);
        }

        private static NodeMemory[] allocateMemory(int nodeCount) {
            NodeMemory[] arr = new NodeMemory[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                arr[i] = new NodeMemory();
            }
            return arr;
        }

        public long tickCount() {
            return tickCount;
        }

        public Blackboard blackboard() {
            return blackboard;
        }

        public void resetRuntime() {
            for (NodeMemory slot : memory) {
                slot.reset();
            }
        }
    }

    private enum NodeKind {
        SEQUENCE,
        SELECTOR,
        REACTIVE_SEQUENCE,
        REACTIVE_SELECTOR,
        PARALLEL,
        INVERTER,
        FORCE_SUCCESS,
        REPEAT,
        COOLDOWN,
        CONDITION,
        ACTION
    }

    private static final class NodeDef<C> {
        final NodeKind kind;
        final int childBegin;
        final int childCount;
        final int paramA;
        final int paramB;
        final BiPredicate<C, Blackboard> condition;
        final BiFunction<C, Blackboard, Status> action;
        final BiConsumer<C, Blackboard> onHalt;

        NodeDef(NodeKind kind, int childBegin, int childCount, int paramA, int paramB,
                BiPredicate<C, Blackboard> condition, BiFunction<C, Blackboard, Status> action,
                BiConsumer<C, Blackboard> onHalt) {
            this.kind = kind;
            this.childBegin = childBegin;
            this.childCount = childCount;
            this.paramA = paramA;
            this.paramB = paramB;
            this.condition = condition;
            this.action = action;
            this.onHalt = onHalt;
        }
    }

    /** 共享只读行为树拓扑定义。 */
    public static final class TreeDef<C> {
        private final int root;
        private final NodeDef<C>[] nodes;
        private final int[] childIndices;

        TreeDef(int root, NodeDef<C>[] nodes, int[] childIndices) {
            this.root = root;
            this.nodes = nodes;
            this.childIndices = childIndices;
        }

        public boolean valid() {
            return root >= 0 && root < nodes.length;
        }

        public State createState() {
            return new State(nodes.length);
        }

        /** 推进一帧；多个实体共享同一份 {@link TreeDef}，各自持有独立 {@link State}。 */
        public Status tick(C ctx, State state) {
            if (!valid() || state == null) {
                return Status.FAILURE;
            }
            if (state.memory.length != nodes.length) {
                state.memory = State.allocateMemory(nodes.length);
            }
            state.tickCount++;
            return tickNode(root, ctx, state);
        }

        /** 外部强制中止整棵树（如怪物死亡 / 脱战重置 / 被强控）。 */
        public void haltAll(C ctx, State state) {
            if (!valid() || state == null || state.memory.length != nodes.length) {
                return;
            }
            haltNode(root, ctx, state);
        }

        private void haltNode(int nodeId, C ctx, State state) {
            NodeMemory mem = state.memory[nodeId];
            if (!mem.running) {
                return;
            }
            NodeDef<C> node = nodes[nodeId];
            for (int i = 0; i < node.childCount; i++) {
                haltNode(childIndices[node.childBegin + i], ctx, state);
            }
            if (node.kind == NodeKind.ACTION && node.onHalt != null) {
                node.onHalt.accept(ctx, state.blackboard);
            }
            mem.running = false;
            mem.runningChild = 0;
            mem.repeatCount = 0;
        }

        private Status tickNode(int nodeId, C ctx, State state) {
            NodeDef<C> node = nodes[nodeId];
            NodeMemory mem = state.memory[nodeId];
            Status result = Status.FAILURE;

            switch (node.kind) {
                case CONDITION -> result = (node.condition != null && node.condition.test(ctx, state.blackboard))
                        ? Status.SUCCESS : Status.FAILURE;

                case ACTION -> result = node.action != null ? node.action.apply(ctx, state.blackboard) : Status.FAILURE;

                case SEQUENCE -> {
                    int start = mem.running ? mem.runningChild : 0;
                    result = Status.SUCCESS;
                    for (int i = start; i < node.childCount; i++) {
                        Status childStatus = tickNode(childIndices[node.childBegin + i], ctx, state);
                        if (childStatus == Status.RUNNING) {
                            mem.runningChild = i;
                            result = Status.RUNNING;
                            break;
                        }
                        if (childStatus == Status.FAILURE) {
                            mem.runningChild = 0;
                            result = Status.FAILURE;
                            break;
                        }
                    }
                    if (result != Status.RUNNING) {
                        mem.runningChild = 0;
                    }
                }

                case SELECTOR -> {
                    int start = mem.running ? mem.runningChild : 0;
                    result = Status.FAILURE;
                    for (int i = start; i < node.childCount; i++) {
                        Status childStatus = tickNode(childIndices[node.childBegin + i], ctx, state);
                        if (childStatus == Status.RUNNING) {
                            mem.runningChild = i;
                            result = Status.RUNNING;
                            break;
                        }
                        if (childStatus == Status.SUCCESS) {
                            mem.runningChild = 0;
                            result = Status.SUCCESS;
                            break;
                        }
                    }
                    if (result != Status.RUNNING) {
                        mem.runningChild = 0;
                    }
                }

                case REACTIVE_SEQUENCE -> {
                    result = Status.SUCCESS;
                    for (int i = 0; i < node.childCount; i++) {
                        Status childStatus = tickNode(childIndices[node.childBegin + i], ctx, state);
                        if (childStatus != Status.SUCCESS) {
                            for (int j = i + 1; j < node.childCount; j++) {
                                haltNode(childIndices[node.childBegin + j], ctx, state);
                            }
                            result = childStatus;
                            break;
                        }
                    }
                }

                case REACTIVE_SELECTOR -> {
                    result = Status.FAILURE;
                    for (int i = 0; i < node.childCount; i++) {
                        Status childStatus = tickNode(childIndices[node.childBegin + i], ctx, state);
                        if (childStatus != Status.FAILURE) {
                            for (int j = i + 1; j < node.childCount; j++) {
                                haltNode(childIndices[node.childBegin + j], ctx, state);
                            }
                            result = childStatus;
                            break;
                        }
                    }
                }

                case PARALLEL -> {
                    int successCount = 0;
                    int failureCount = 0;
                    int successNeed = node.paramA == 0 ? node.childCount : node.paramA;
                    int failureNeed = node.paramB == 0 ? 1 : node.paramB;
                    for (int i = 0; i < node.childCount; i++) {
                        Status childStatus = tickNode(childIndices[node.childBegin + i], ctx, state);
                        if (childStatus == Status.SUCCESS) {
                            successCount++;
                        } else if (childStatus == Status.FAILURE) {
                            failureCount++;
                        }
                    }
                    if (successCount >= successNeed) {
                        result = Status.SUCCESS;
                    } else if (failureCount >= failureNeed) {
                        result = Status.FAILURE;
                    } else {
                        result = Status.RUNNING;
                    }
                    if (result != Status.RUNNING) {
                        for (int i = 0; i < node.childCount; i++) {
                            haltNode(childIndices[node.childBegin + i], ctx, state);
                        }
                    }
                }

                case INVERTER -> {
                    if (node.childCount == 1) {
                        Status childStatus = tickNode(childIndices[node.childBegin], ctx, state);
                        result = childStatus == Status.RUNNING ? Status.RUNNING
                                : (childStatus == Status.SUCCESS ? Status.FAILURE : Status.SUCCESS);
                    }
                }

                case FORCE_SUCCESS -> {
                    if (node.childCount == 1) {
                        Status childStatus = tickNode(childIndices[node.childBegin], ctx, state);
                        result = childStatus == Status.RUNNING ? Status.RUNNING : Status.SUCCESS;
                    }
                }

                case REPEAT -> {
                    if (node.childCount == 1) {
                        int child = childIndices[node.childBegin];
                        result = Status.SUCCESS;
                        while (node.paramA == 0 || mem.repeatCount < node.paramA) {
                            Status childStatus = tickNode(child, ctx, state);
                            if (childStatus == Status.RUNNING) {
                                result = Status.RUNNING;
                                break;
                            }
                            if (childStatus == Status.FAILURE) {
                                mem.repeatCount = 0;
                                result = Status.FAILURE;
                                break;
                            }
                            mem.repeatCount++;
                            if (node.paramA == 0) {
                                result = Status.RUNNING;
                                break;
                            }
                        }
                        if (result != Status.RUNNING) {
                            mem.repeatCount = 0;
                        }
                    }
                }

                case COOLDOWN -> {
                    if (node.childCount == 1) {
                        if (!mem.running && state.tickCount < mem.cooldownReadyTick) {
                            result = Status.FAILURE;
                            break;
                        }
                        result = tickNode(childIndices[node.childBegin], ctx, state);
                        if (result == Status.SUCCESS) {
                            mem.cooldownReadyTick = state.tickCount + Integer.toUnsignedLong(node.paramA);
                        }
                    }
                }
            }

            mem.running = result == Status.RUNNING;
            return result;
        }
    }

    /** 构树器：{@link #build(int)} 阶段扁平化子节点表并做三色 DFS 环路检测（fail-closed）。 */
    public static final class Builder<C> {
        private static final class DraftNode<C> {
            final NodeKind kind;
            int paramA;
            int paramB;
            final int[] children;
            BiPredicate<C, Blackboard> condition;
            BiFunction<C, Blackboard, Status> action;
            BiConsumer<C, Blackboard> onHalt;

            DraftNode(NodeKind kind, int[] children) {
                this.kind = kind;
                this.children = children;
            }
        }

        private final List<DraftNode<C>> drafts = new ArrayList<>();

        private int push(DraftNode<C> node) {
            int id = drafts.size();
            drafts.add(node);
            return id;
        }

        private int addComposite(NodeKind kind, int... children) {
            return push(new DraftNode<>(kind, children.clone()));
        }

        private int addDecorator(NodeKind kind, int child, int paramA) {
            DraftNode<C> node = new DraftNode<>(kind, new int[] {child});
            node.paramA = paramA;
            return push(node);
        }

        public int sequence(int... children) {
            return addComposite(NodeKind.SEQUENCE, children);
        }

        public int selector(int... children) {
            return addComposite(NodeKind.SELECTOR, children);
        }

        public int reactiveSequence(int... children) {
            return addComposite(NodeKind.REACTIVE_SEQUENCE, children);
        }

        public int reactiveSelector(int... children) {
            return addComposite(NodeKind.REACTIVE_SELECTOR, children);
        }

        public int parallel(int successThreshold, int failureThreshold, int... children) {
            int id = addComposite(NodeKind.PARALLEL, children);
            drafts.get(id).paramA = successThreshold;
            drafts.get(id).paramB = failureThreshold;
            return id;
        }

        public int inverter(int child) {
            return addDecorator(NodeKind.INVERTER, child, 0);
        }

        public int forceSuccess(int child) {
            return addDecorator(NodeKind.FORCE_SUCCESS, child, 0);
        }

        public int repeat(int maxCount, int child) {
            return addDecorator(NodeKind.REPEAT, child, maxCount);
        }

        public int cooldown(int cooldownTicks, int child) {
            return addDecorator(NodeKind.COOLDOWN, child, cooldownTicks);
        }

        public int condition(BiPredicate<C, Blackboard> fn) {
            DraftNode<C> node = new DraftNode<>(NodeKind.CONDITION, new int[0]);
            node.condition = fn;
            return push(node);
        }

        public int action(BiFunction<C, Blackboard, Status> tickFn) {
            return action(tickFn, null);
        }

        public int action(BiFunction<C, Blackboard, Status> tickFn, BiConsumer<C, Blackboard> haltFn) {
            DraftNode<C> node = new DraftNode<>(NodeKind.ACTION, new int[0]);
            node.action = tickFn;
            node.onHalt = haltFn;
            return push(node);
        }

        /** 编译为不可变扁平树；根节点非法或存在环路时返回空（fail-closed）。 */
        @SuppressWarnings("unchecked")
        public Optional<TreeDef<C>> build(int root) {
            if (root < 0 || root >= drafts.size()) {
                return Optional.empty();
            }
            byte[] color = new byte[drafts.size()];
            if (!validateAcyclic(root, color)) {
                return Optional.empty();
            }

            int totalChildren = 0;
            for (DraftNode<C> draft : drafts) {
                totalChildren += draft.children.length;
            }
            NodeDef<C>[] nodes = (NodeDef<C>[]) new NodeDef<?>[drafts.size()];
            int[] childIndices = new int[totalChildren];
            int cursor = 0;
            for (int i = 0; i < drafts.size(); i++) {
                DraftNode<C> d = drafts.get(i);
                int begin = cursor;
                System.arraycopy(d.children, 0, childIndices, cursor, d.children.length);
                cursor += d.children.length;
                nodes[i] = new NodeDef<>(d.kind, begin, d.children.length, d.paramA, d.paramB,
                        d.condition, d.action, d.onHalt);
            }
            return Optional.of(new TreeDef<>(root, nodes, childIndices));
        }

        private boolean validateAcyclic(int id, byte[] color) {
            if (id < 0 || id >= drafts.size()) {
                return false;
            }
            if (color[id] == 1) {
                return false;
            }
            if (color[id] == 2) {
                return true;
            }
            color[id] = 1;
            for (int child : drafts.get(id).children) {
                if (!validateAcyclic(child, color)) {
                    return false;
                }
            }
            color[id] = 2;
            return true;
        }
    }
}
