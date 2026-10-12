package com.game.match.ticket;

/**
 * 对一张票据的引用：玩家 + 票号。票据存储的 CAS 写（置 ready、续期、删票、回队首、弹组）都按它核对「玩家此刻的票仍是这一张」。
 *
 * @param playerId 玩家号（非 0）
 * @param ticketId 票号（非空）
 */
public record TicketRef(long playerId, String ticketId) {

    public TicketRef {
        if (playerId == 0) {
            throw new IllegalArgumentException("票据引用的玩家号不能为 0");
        }
        if (ticketId == null || ticketId.isEmpty()) {
            throw new IllegalArgumentException("票据引用的票号不能为空");
        }
    }
}
