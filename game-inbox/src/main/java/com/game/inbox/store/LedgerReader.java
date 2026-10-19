package com.game.inbox.store;

import com.game.common.deadline.Deadline;
import com.game.player.store.state.InboxLedgerState;
import java.sql.SQLException;

/**
 * 读一名玩家<b>已落盘</b>的收件箱消费账本（{@code player_state.inbox_ledger}；强制作废的证明与两个对账入口共用，设计 §7.9、§9.3）。
 * Java 的「已落盘」就是 player_state 带 owner_epoch 围栏写提交成功，所以直接读权威 SQL，没有 mmorpg 那层「Redis blob 才是 durable」的区别
 * （mmorpg 经 data_service {@code GetPlayerInboxLedger} 读 zone Redis 里的 blob，残余风险见其设计 §7.10，Java 版不存在）。
 * 实现必须可并发调用（定时对账以有界并发读）。
 */
@FunctionalInterface
public interface LedgerReader {

    /**
     * @param found  false = 没有这名玩家的 player_state 行（读不到存档：结论未知，不比对）
     * @param ledger found 时非 null；从没消费过收件箱时是空账本
     */
    record Read(boolean found, InboxLedgerState ledger) {

        public static Read absent() {
            return new Read(false, null);
        }
    }

    /** 读失败 / 解析失败抛 {@link SQLException}（结论未知，调用方不得据此判「无差异」或「证明成立」）。 */
    Read read(Deadline budget, long playerId) throws SQLException;
}
