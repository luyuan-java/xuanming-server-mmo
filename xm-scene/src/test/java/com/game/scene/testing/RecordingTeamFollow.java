package com.game.scene.testing;

import com.game.scene.team.TeamFollow;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录型组队跟随钩子：只记下两个入口各被谁触发过（按发生顺序），不读成员关系、不换场景。
 * 断言「战斗冻结解除后补一次跟随」的时机用 {@link #freezeCleared}（删锁完成之前不触发、完成后触发、实例已换不触发）。
 */
public final class RecordingTeamFollow implements TeamFollow {

    /** 进场 / 换场景钩子被触发的玩家（player_id，按顺序，可重复）。 */
    public final List<Long> entered = new ArrayList<>();
    /** {@code onBattleFreezeCleared} 被触发的玩家（player_id，按顺序，可重复）。 */
    public final List<Long> freezeCleared = new ArrayList<>();

    @Override
    public void onEnteredScene(SceneWorld world, ScenePlayer player) {
        entered.add(player.playerId());
    }

    @Override
    public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
        freezeCleared.add(player.playerId());
    }

    public void clear() {
        entered.clear();
        freezeCleared.clear();
    }
}
