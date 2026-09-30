package com.game.gateway.assign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/assign-gate} 请求体（客户端契约，JSON 键为 snake_case）。robot 首次请求正好是 {@code {"zone_id":1}}。
 *
 * <p>本批只用 {@code zone_id}。{@code account} / {@code device_id} / {@code queue_token} 是 mmorpg 契约里的可选字段
 * （限流冷却、排队审计、排队重入），照收不用：排队与限流不在本批，本版从不签发排队令牌，
 * 带着 {@code queue_token} 来的请求按首次请求处理。未知字段一律忽略。
 *
 * @param zoneId 目标区服；缺省为 0。mmorpg 里 0 表示「由服务端挑区」，本批未实现，按未知区服处理
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignGateRequest(int zoneId, String account, String deviceId, String queueToken) {
}
