package com.game.gateway.serverlist;

import java.util.List;

/** {@code GET /api/server-list} 应答：{@code {"zones":[...]}}，顺序即配置顺序。 */
public record ServerListResponse(List<ZoneInfo> zones) {
}
