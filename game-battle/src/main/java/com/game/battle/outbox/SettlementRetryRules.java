package com.game.battle.outbox;

import com.game.discovery.battle.BattleRedis;

/**
 * 结算重投的判定（纯函数，移植基线 {@code settlement_outbox.h:55-74} 的 {@code ClassifyRetry}；scene-battle-spec §3.4、§7.15）。
 * 判定顺序「不再是我们的 → 已销账」>「次数用尽」>「解析不到位置 → 本轮无目标」>「重投」。用<b>本轮之前</b>的次数判定、判定<b>之后</b>才加一
 * （skip 轮也计次，{@code room.cpp:1810-1811}）：attemptsSoFar = 0…11 共 12 轮重投（或跳过），第 13 轮判用尽。
 */
public final class SettlementRetryRules {

    /** 一轮的判定。 */
    public enum Decision {
        /** 记录已不在（scene 已销账 / 已被取代）：摘除。 */
        DONE,
        /** 次数用尽：摘除，记录留给 scene 的进场恢复 / rescue。 */
        EXHAUSTED,
        /** 这一轮解析不到持有者：不投，另跑 {@code ACK_IF_SUPERSEDED}。 */
        SKIP_NO_TARGET,
        /** 按重新解析的目标重投。 */
        RESEND
    }

    private SettlementRetryRules() {
    }

    /**
     * @param stillOurs     探测到记录仍是本局的（HEXISTS 为真）
     * @param attemptsSoFar 本轮之前已经过的轮数
     * @param hasTarget     这一轮解析到了持有者
     */
    public static Decision classify(boolean stillOurs, int attemptsSoFar, boolean hasTarget) {
        if (!stillOurs) {
            return Decision.DONE;
        }
        if (attemptsSoFar >= BattleRedis.SETTLEMENT_RETRY_MAX) {
            return Decision.EXHAUSTED;
        }
        if (!hasTarget) {
            return Decision.SKIP_NO_TARGET;
        }
        return Decision.RESEND;
    }
}
