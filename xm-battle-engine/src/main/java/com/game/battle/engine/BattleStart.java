package com.game.battle.engine;

import java.util.Objects;

/**
 * {@link TurnBattleEngine#start} 的结果（替代基线 {@code bool Initialize}，规格 D1）：要么得到一个已初始化、已播种的引擎，
 * 要么得到拒绝原因与说明，失败时不产生引擎实例，也就不存在基线那种「初始化失败后残留半填状态」的对象。
 */
public sealed interface BattleStart permits BattleStart.Started, BattleStart.Rejected {

    /** 开局成功：引擎已完成全部初始化步骤（规格 §1.4），可以直接收行动。 */
    record Started(TurnBattleEngine engine) implements BattleStart {
        public Started {
            Objects.requireNonNull(engine, "engine");
        }
    }

    /** 开局被拒：{@code detail} 带出 battle_id、team、人数、pet_id 等，供节点打日志。 */
    record Rejected(InitRejection reason, String detail) implements BattleStart {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
