package com.game.scene.player;

/**
 * 每个玩家固定的四个背包（mmorpg BagType；编号即持久化与协议里的 bag_type，不许重编号）。
 *
 * <ul>
 *   <li>人物背包：扁平格，满了拒绝；</li>
 *   <li>仓库：扁平格，满了拒绝（基线没有写入方，只能从存档还原）；</li>
 *   <li>装备栏：具名槽，按部位（Item.equip_kind ↔ EquipSlot）分桶，永不淘汰、永不重排（基线没有写入方）；</li>
 *   <li>临时格：扁平格，满了按入包先后淘汰最早的实例（战斗掉落进主包失败后的余量）。</li>
 * </ul>
 */
public enum BagType {
    INVENTORY(0, 100, Layout.FLAT, false),
    WAREHOUSE(1, 200, Layout.FLAT, false),
    EQUIPMENT(2, 10, Layout.SLOTTED, false),
    TEMPORARY(3, 200, Layout.FLAT, true);

    /** 格子布局：扁平（一个实例一格，first-fit）或具名槽（槽号 = EquipSlot.id，按部位分桶）。 */
    public enum Layout {
        FLAT,
        SLOTTED
    }

    private static final BagType[] BY_CODE = values();

    private final int code;
    private final int defaultCapacity;
    private final Layout layout;
    private final boolean evictsOldest;

    BagType(int code, int defaultCapacity, Layout layout, boolean evictsOldest) {
        this.code = code;
        this.defaultCapacity = defaultCapacity;
        this.layout = layout;
        this.evictsOldest = evictsOldest;
    }

    public int code() {
        return code;
    }

    public int defaultCapacity() {
        return defaultCapacity;
    }

    public Layout layout() {
        return layout;
    }

    /** 满了时淘汰最早入包的实例腾位（只有临时格）。 */
    public boolean evictsOldest() {
        return evictsOldest;
    }

    /** 玩家可以整理（192 SortBag）：人物背包与仓库。 */
    public boolean sortable() {
        return this == INVENTORY || this == WAREHOUSE;
    }

    /** 整理时允许重排格子（扁平布局；装备栏的槽位有语义，永不重排）。 */
    public boolean compactable() {
        return layout == Layout.FLAT;
    }

    /** 协议 / 存档里的 bag_type（uint32，按无符号看）→ 背包；不认识的为 null。 */
    public static BagType ofCode(int code) {
        return Integer.compareUnsigned(code, BY_CODE.length) < 0 ? BY_CODE[code] : null;
    }
}
