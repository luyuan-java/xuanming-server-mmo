package com.game.team.push;

import com.game.common.deadline.Deadline;

/** 提交后的场景提示，运行在独立有界工作池；失败不能改变已提交的组队回包。 */
@FunctionalInterface
public interface SceneTeamSignals {
    SceneTeamSignals NONE = (player, follow, deadline) -> { };
    void refresh(long playerId, boolean follow, Deadline deadline);
}
