package com.game.battle.room;

/**
 * 未成年人到点后 battle 不再提供战斗直连（对齐 C++ {@code cpp/nodes/battle/battle_minor_play_deadline.h} 与
 * {@code docs/design/real-name-anti-addiction.md} §5.4）。
 *
 * <p>纯函数，零状态、零 I/O：
 * <ul>
 *   <li>{@link #directCloseAtMs(long)}：直连服务截止时刻 = 截止时刻 − 提前量（5 秒，与 gate 关大厅连接同点）；</li>
 *   <li>{@link #reached(long, long)}：0 表示不限，非 0 且当前时间 ≥ 直连截止时刻即视为到点（与票据过期同刻翻转）；</li>
 *   <li>{@link #ticketExpireAtMs(long, long)}：票据期限取房间作废期限与直连截止时刻的较早者。</li>
 * </ul>
 */
public final class BattleMinorPlayDeadline {

    /** 提前停止直连服务的时间（毫秒），与 gate 关大厅连接同为 5 秒。 */
    public static final long CLOSE_LEAD_MS = 5_000L;

    private BattleMinorPlayDeadline() {
    }

    /** 直连服务截止时刻（无符号 Unix 毫秒）；不足提前量时取 0（已截止）。 */
    public static long directCloseAtMs(long playDeadlineMs) {
        return Long.compareUnsigned(playDeadlineMs, CLOSE_LEAD_MS) > 0 ? playDeadlineMs - CLOSE_LEAD_MS : 0L;
    }

    /** 未成年人是否已到点停止战斗直连。{@code playDeadlineMs == 0} 表示不限，永远返回 false。 */
    public static boolean reached(long playDeadlineMs, long nowMs) {
        return playDeadlineMs != 0L && Long.compareUnsigned(nowMs, directCloseAtMs(playDeadlineMs)) >= 0;
    }

    /** 签发直连票据的过期时刻：无限制取房间作废期限，有截止时刻则按直连截止时刻封顶（无符号最小值）。 */
    public static long ticketExpireAtMs(long roomDeadlineMs, long playDeadlineMs) {
        if (playDeadlineMs == 0L) {
            return roomDeadlineMs;
        }
        long closeAtMs = directCloseAtMs(playDeadlineMs);
        return Long.compareUnsigned(closeAtMs, roomDeadlineMs) < 0 ? closeAtMs : roomDeadlineMs;
    }
}
