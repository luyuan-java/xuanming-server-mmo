package com.game.scene.team;

import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;

/**
 * 进场后的组队跟随钩子（team-spec §6.10，D7 / D9）。{@link SceneWorld} 在两处进入场景之后调用：进场（登录 / 重连 / 顶号）
 * 与本节点内换场景（玩家自己换图、被跟随换图），与基线 C++ {@code PlayerTeamSystem::OnEnteredScene} 的调用点等价
 * （cpp/libs/services/scene/player/system/player_team.h:114-117）。
 *
 * <p>只在场景逻辑线程上调用；实现必须不阻塞（异步读、结果投递回逻辑线程）、不抛异常（失败只记日志 / 指标：
 * 跟随只是便利，读不到就不跟）。
 */
public interface TeamFollow {

    /**
     * {@code player} 刚进入了它现在所在的场景。
     *
     * @param world 调用方自己（钩子不持有它：异步结果回到逻辑线程后用它核对实例、找队长、换场景）
     */
    void onEnteredScene(SceneWorld world, ScenePlayer player);

    /**
     * {@code player} 的回合制战斗冻结刚解除（解冻且删锁完成、或销账放了锁；基线 {@code OnBattleFreezeCleared → RefreshAndFollow}，
     * {@code team.cpp:139-142}）：补一次「只跟随、不扇出」的检查。缺省什么都不做。
     */
    default void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
    }

    /** 不跟随（测试与不接 Redis 的装配）。 */
    TeamFollow NONE = (world, player) -> {
    };
}
