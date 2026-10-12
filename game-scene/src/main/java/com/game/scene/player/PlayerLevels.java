package com.game.scene.player;

/**
 * 角色等级的合法范围（同 mmorpg {@code player_level_rules.h}）。上限是策划定的硬约束；没有经验系统前只有 GM 能改等级。
 * 「写等级」（GM 设等级）与「读存档等级」两个入口都经这里：只在写入口卡上限拦不住老存档 / 回档，读入口必须同样收口。
 */
public final class PlayerLevels {

    /** 等级上限（基线 kMaxLevel；加点公式的满投点数也按它现算）。 */
    public static final int MAX_LEVEL = 85;

    private PlayerLevels() {
    }

    /** 可写入的等级：1..{@link #MAX_LEVEL}（0 不是合法等级）。协议里是 uint32，高位置位读成负数同样不合法。 */
    public static boolean isValid(int level) {
        return level >= 1 && level <= MAX_LEVEL;
    }

    /**
     * 存档等级压回上限；0 原样返回（读取方按 1 处理，见 {@link #effective}）。存档列是 INT UNSIGNED，存储层按 long 读、
     * 超出 int 的值饱和到 {@code Integer.MAX_VALUE} 后交到这里，同样压回上限（同基线 uint32 口径）；负数分支只是防御。
     */
    public static int clampStored(int level) {
        return level < 0 || level > MAX_LEVEL ? MAX_LEVEL : level;
    }

    /** 参与计算的等级：0 按 1（基线 PlayerLevel()）。 */
    public static int effective(int level) {
        return level == 0 ? 1 : level;
    }
}
