package com.game.scene.battle;

import com.game.scene.world.ScenePlayer;

/** 仅供 world 包测试构造战斗在途前置状态；实际备战、迟到确认与恢复入口另在各自测试验证。 */
public final class BattleFreezeTestAccess {
    private BattleFreezeTestAccess() { }

    public static void freeze(ScenePlayer player) {
        player.battle().setFreeze(new BattleFreeze(9001, 1, BattleFreeze.Phase.PREPARING, Long.MAX_VALUE,
                Long.MAX_VALUE, false));
    }

    public static void clear(ScenePlayer player) {
        player.battle().setFreeze(null);
    }
}
