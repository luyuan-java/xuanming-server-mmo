package com.game.gateway.assign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/queue-status} 请求体（同基线 QueueStatusRequest，JSON 键 snake_case）。
 *
 * @param queueToken assign-gate 回的排队令牌
 * @param zoneId     区（基线用来把轮询路由回签发令牌的 login 实例；Java 拿它做区服准入，没带就取令牌里的区）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueueStatusRequest(String queueToken, int zoneId) {
}
