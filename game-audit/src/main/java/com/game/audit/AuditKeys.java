package com.game.audit;

/** 审计消息的分区键。 */
public final class AuditKeys {

    private AuditKeys() {
    }

    /**
     * 流水的分区键：扣减方玩家号优先，否则获得方（同 mmorpg transaction_log_system.cpp）；无符号十进制。
     * 同一玩家的流水因此进同一分区、按发送顺序落库。两边都是 0（系统对系统）时键为 "0"。
     */
    public static String transactionKey(long fromPlayer, long toPlayer) {
        return Long.toUnsignedString(fromPlayer != 0 ? fromPlayer : toPlayer);
    }

    /** 以单个玩家为主体的消息（快照）的分区键。 */
    public static String playerKey(long playerId) {
        return Long.toUnsignedString(playerId);
    }
}
