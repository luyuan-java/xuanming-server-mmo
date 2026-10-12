package com.game.gateway.assign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/assign-gate} 请求体（客户端契约，JSON 键为 snake_case）。robot 首次请求正好是 {@code {"zone_id":1}}。
 *
 * <p>{@code queue_token}：登录排队打开时按令牌轮询（排队关闭时照收不用，按首次请求处理）；{@code account} / {@code device_id}
 * 是 mmorpg 契约里的可选字段（限流的账号冷却随 3.4c；排队不按账号绕过，见 AssignGateService），照收。未知字段一律忽略。
 *
 * @param zoneId 目标区服；缺省为 0。mmorpg 里 0 表示「由服务端挑区」，本批未实现，按未知区服处理
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignGateRequest(int zoneId, String account, String deviceId, String queueToken) {
}
