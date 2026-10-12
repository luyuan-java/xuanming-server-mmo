package com.game.gateway.store;

/**
 * {@code announcement} 的一行。
 *
 * @param startTime 生效起点（Unix 秒），null = 立即
 * @param endTime   生效终点（Unix 秒，含），null = 不过期
 * @param createdAt 创建时刻（Unix 毫秒）
 */
public record AnnouncementRow(long id, String title, String content, String type, Long startTime, Long endTime,
                              long createdAt) {
}
