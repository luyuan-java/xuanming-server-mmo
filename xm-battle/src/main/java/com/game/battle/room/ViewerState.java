package com.game.battle.room;

import com.game.proto.BattleStartS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.SpectateStateS2C;
import com.game.proto.TurnResultS2C;
import java.util.Objects;

/**
 * 已按某个收信人视角裁剪过的战斗状态（battle-node-spec §5.6、§7.6）。只能由 {@link BattleViews} 构造（构造器包私有），
 * 组装 143 / 139 / 140 / 161 / 158 的代码只接受本类型：引擎的全知 {@code BattleStateS2C} 没有路径直接进推送或应答，
 * 「忘了裁剪」就编译不过。不可变。
 */
final class ViewerState {

    private final BattleStateS2C state;

    ViewerState(BattleStateS2C state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 裁剪后的状态（140 的应答体，或嵌进下面几种推送）。 */
    BattleStateS2C state() {
        return state;
    }

    /** 143 {@code BattleStartS2C{battle_id, state}}。 */
    BattleStartS2C toBattleStart(long battleId) {
        return BattleStartS2C.newBuilder().setBattleId(battleId).setState(state).build();
    }

    /** 139 / 158 {@code TurnResultS2C}：事件流与出手序取 {@code fullResult} 的，state 换成本视角的。 */
    TurnResultS2C toTurnResult(TurnResultS2C fullResult) {
        return fullResult.toBuilder().setState(state).build();
    }

    /** 161 {@code SpectateStateS2C{state, observer_count}}。 */
    SpectateStateS2C toSpectateState(int observerCount) {
        return SpectateStateS2C.newBuilder().setState(state).setObserverCount(observerCount).build();
    }
}
