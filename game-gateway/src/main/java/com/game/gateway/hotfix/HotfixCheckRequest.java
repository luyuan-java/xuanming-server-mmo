package com.game.gateway.hotfix;

/** POST /api/hotfix-check 请求；沿用网关全局 snake_case JSON 配置。 */
public record HotfixCheckRequest(String clientVersion, String platform) { }
