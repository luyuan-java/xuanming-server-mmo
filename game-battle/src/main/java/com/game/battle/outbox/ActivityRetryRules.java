package com.game.battle.outbox;

import com.game.discovery.battle.BattleRedis;

/**
 * 活动结果重发的判定（纯函数，移植基线 {@code battle_result_activity.h:93-108}；scene-battle-spec §3.6、§7.17）：
 * 已销账 &gt; 用尽 &gt; 重发原字节。只在重发时计次（{@code room.cpp:2014}）：30 次重发后第 31 轮用尽。
 */
public final class ActivityRetryRules {

    /** 一轮的判定。 */
    public enum Decision {
        /** 持久副本已被消费方删掉：摘除。 */
        ACKED,
        /** 重发次数用尽：ERROR 并摘除（副本仍在 Redis，留给巡检器）。 */
        EXHAUSTED,
        /** 重发原字节。 */
        RESEND
    }

    private ActivityRetryRules() {
    }

    /**
     * @param stillPresent 持久副本还在（EXISTS = 1）
     * @param resentSoFar  已经重发过的次数
     */
    public static Decision classify(boolean stillPresent, int resentSoFar) {
        if (!stillPresent) {
            return Decision.ACKED;
        }
        if (resentSoFar >= BattleRedis.ACTIVITY_RETRY_MAX) {
            return Decision.EXHAUSTED;
        }
        return Decision.RESEND;
    }
}
