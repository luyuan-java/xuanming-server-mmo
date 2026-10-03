package com.game.scene.player;

import com.game.player.store.state.AttributeScheme;
import com.game.player.store.state.AttributeState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家的属性加点状态（只在场景逻辑线程上读写；规则在 {@code com.game.scene.attribute.AttributeService}，本类只存状态）。
 *
 * <p>落库的只有加点方案（{@code player_state.attribute}）；点数总量按等级与表实时换算，二级属性与当前气血 / 法力
 * 每次加载重算（{@link #derived()} 等不落库）。从没动过的状态（只有默认方案、没分配过点、没切换过）不写进存档，
 * 读回来仍是 {@link #empty()}——新号存档与以前一致，周期存盘的脏比对也不受影响。
 *
 * <p>当前气血 / 法力暂不持久化：Java 版还没有任何伤害来源，每次进场按上限回满（基线对新号 / 阵亡者同样回满），
 * 随死亡 / 复活批次（路线图 2.7）改为持久化。
 */
public final class PlayerAttributes {

    /** 默认方案（基线 kDefaultSchemeId / kDefaultSchemeName）。 */
    public static final int DEFAULT_SCHEME_ID = 1;
    public static final String DEFAULT_SCHEME_NAME = "方案一";

    /** 一套加点方案：维度号 → 已分配点（只存非 0，值按 uint32 的无符号值放在 long 里）。 */
    public static final class Scheme {

        private final int id;
        private String name;
        private final Map<Integer, Long> allocated;
        private final UnknownFieldSet unknownFields;

        private Scheme(int id, String name, Map<Integer, Long> allocated, UnknownFieldSet unknownFields) {
            this.id = id;
            this.name = name;
            this.allocated = allocated;
            this.unknownFields = unknownFields;
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }

        public void rename(String name) {
            this.name = name;
        }

        /** 某维度的已分配点（没分配过为 0）。 */
        public long allocated(int dimensionId) {
            return allocated.getOrDefault(dimensionId, 0L);
        }

        /** 设已分配点；0 即删除该键（保持「只存非 0」，免得 {103:0} 与 {} 被当成不同而白写一次存档）。 */
        public void setAllocated(int dimensionId, long points) {
            if (points == 0) {
                allocated.remove(dimensionId);
            } else {
                allocated.put(dimensionId, points);
            }
        }

        /** 只读视图（维度号 → 已分配点）。 */
        public Map<Integer, Long> allocatedView() {
            return Collections.unmodifiableMap(allocated);
        }

        boolean isDefault() {
            return id == DEFAULT_SCHEME_ID && DEFAULT_SCHEME_NAME.equals(name) && allocated.isEmpty()
                    && unknownFields.asMap().isEmpty();
        }
    }

    /**
     * 二级属性（基线 DerivedAttributesComp，向下取整后的整数）。
     */
    public record Derived(long maxHealth, long maxMana, long physicalAttack, long magicAttack, long speed,
                          long defense) {

        public static final Derived ZERO = new Derived(0, 0, 0, 0, 0, 0);
    }

    private final List<Scheme> schemes;
    private int activeSchemeId;
    private int nextSchemeId;
    /** 上次切换方案的 Unix 秒（uint64）。 */
    private long lastSwitchTime;
    /** 存档里本版本不认识的字段（更新版本写的）：原样带回，滚动升级 / 回滚时不被旧节点抹掉。 */
    private final UnknownFieldSet unknownFields;

    // ---- 不落库，每次加载重算
    private Derived derived = Derived.ZERO;
    private long health;
    private long mana;

    private PlayerAttributes(List<Scheme> schemes, int activeSchemeId, int nextSchemeId, long lastSwitchTime,
                             UnknownFieldSet unknownFields) {
        this.schemes = schemes;
        this.activeSchemeId = activeSchemeId;
        this.nextSchemeId = nextSchemeId;
        this.lastSwitchTime = lastSwitchTime;
        this.unknownFields = unknownFields;
        ensureDefaults();
    }

    /** 新号：一个默认方案、没分配过点。 */
    public static PlayerAttributes empty() {
        return new PlayerAttributes(new ArrayList<>(), 0, 0, 0, UnknownFieldSet.getDefaultInstance());
    }

    /** 从持久化数据恢复，并补齐缺省（基线 EnsureComp）。 */
    public static PlayerAttributes restore(AttributeState state) {
        List<Scheme> schemes = new ArrayList<>(state.getSchemesCount());
        for (AttributeScheme stored : state.getSchemesList()) {
            Map<Integer, Long> allocated = new HashMap<>();
            stored.getAllocatedMap().forEach((dimension, points) -> {
                if (points != 0) {
                    allocated.put(dimension, Integer.toUnsignedLong(points));
                }
            });
            schemes.add(new Scheme(stored.getSchemeId(), stored.getName(), allocated, stored.getUnknownFields()));
        }
        return new PlayerAttributes(schemes, state.getActiveSchemeId(), state.getNextSchemeId(),
                state.getLastSwitchTime(), state.getUnknownFields());
    }

    /**
     * 基线 EnsureComp：没有方案时补默认方案并让它生效；当前方案为 0 时取第一个；id 游标为 0 时取已有最大 id + 1。
     * 有方案、当前方案却指向不存在的非 0 id 时不修（同基线：之后写操作回「方案不存在」，二级属性按未分配算）。
     */
    private void ensureDefaults() {
        if (schemes.isEmpty() || activeSchemeId == 0) {
            if (schemes.isEmpty()) {
                schemes.add(new Scheme(DEFAULT_SCHEME_ID, DEFAULT_SCHEME_NAME, new HashMap<>(),
                        UnknownFieldSet.getDefaultInstance()));
            }
            activeSchemeId = schemes.get(0).id();
        }
        if (nextSchemeId == 0) {
            int maxId = 0;
            for (Scheme scheme : schemes) {
                if (Integer.compareUnsigned(scheme.id(), maxId) > 0) {
                    maxId = scheme.id();
                }
            }
            nextSchemeId = maxId + 1;
        }
    }

    /** 全部方案（按创建先后，只读）。 */
    public List<Scheme> schemes() {
        return Collections.unmodifiableList(schemes);
    }

    /** 按 id 找方案（同 id 有多个时取第一个，同基线）；没有为 null。 */
    public Scheme scheme(int schemeId) {
        for (Scheme scheme : schemes) {
            if (scheme.id() == schemeId) {
                return scheme;
            }
        }
        return null;
    }

    /** 当前生效方案；当前 id 指向不存在的方案时为 null。 */
    public Scheme activeScheme() {
        return scheme(activeSchemeId);
    }

    public int activeSchemeId() {
        return activeSchemeId;
    }

    public long lastSwitchTime() {
        return lastSwitchTime;
    }

    /** 切到另一个方案并记下切换时刻（Unix 秒）。 */
    public void switchTo(int schemeId, long nowSeconds) {
        this.activeSchemeId = schemeId;
        this.lastSwitchTime = nowSeconds;
    }

    /** 新开方案：id 取游标并自增，不切换当前方案。返回新方案 id。 */
    public int addScheme(String name) {
        int id = nextSchemeId++;
        schemes.add(new Scheme(id, name, new HashMap<>(), UnknownFieldSet.getDefaultInstance()));
        return id;
    }

    public Derived derived() {
        return derived;
    }

    public void setDerived(Derived derived) {
        this.derived = derived;
    }

    /** 当前气血（不落库）。 */
    public long health() {
        return health;
    }

    /** 当前法力（不落库）。 */
    public long mana() {
        return mana;
    }

    public void setHealth(long health) {
        this.health = health;
    }

    public void setMana(long mana) {
        this.mana = mana;
    }

    /** 从没动过：只有默认方案、没分配过点、没切换过、没有不认识的字段。持久化时可以省略整段，读回来仍是 {@link #empty()}。 */
    public boolean isPristine() {
        return schemes.size() == 1 && schemes.get(0).isDefault() && activeSchemeId == DEFAULT_SCHEME_ID
                && nextSchemeId == DEFAULT_SCHEME_ID + 1 && lastSwitchTime == 0 && unknownFields.asMap().isEmpty();
    }

    public AttributeState toState() {
        AttributeState.Builder state = AttributeState.newBuilder()
                .setActiveSchemeId(activeSchemeId)
                .setNextSchemeId(nextSchemeId)
                .setLastSwitchTime(lastSwitchTime)
                .setUnknownFields(unknownFields);
        for (Scheme scheme : schemes) {
            AttributeScheme.Builder stored = AttributeScheme.newBuilder()
                    .setSchemeId(scheme.id())
                    .setName(scheme.name())
                    .setUnknownFields(scheme.unknownFields);
            scheme.allocated.forEach((dimension, points) -> stored.putAllocated(dimension, (int) (long) points));
            state.addSchemes(stored);
        }
        return state.build();
    }
}
