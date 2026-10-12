package com.game.scene.player;

/** 测试用：让 player 包外的测试摆出背包的边界状态（这些入口在生产代码里是包内可见）。 */
public final class BagTestAccess {

    private BagTestAccess() {
    }

    /**
     * 直接改一个实例的堆叠数（不经背包的增删规则）。用来造「数量为 0 的僵尸堆」：Java 的加载规整与抽取都不会留下它
     * （存档里数量 0 的条目被丢弃、抽空的实例当场回收），但读背包的代码（战斗快照的道具副本）仍按基线防着它。
     */
    public static void setStackSize(BagItem item, long size) {
        item.size(size);
    }
}
