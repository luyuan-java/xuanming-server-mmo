package com.game.gateway.login;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.game.common.zone.ZoneIdJsonDeserializer;

/**
 * {@code POST /api/login} 请求体（客户端契约，JSON 键 snake_case）：口令登录填 account + password；
 * 第三方 / 令牌登录填 auth_type + auth_token（account 忽略）。device_id 目前不进令牌（同基线：恒为空）。
 *
 * @param zoneId 要进的区（须在区服列表里）
 */
public record HttpLoginRequest(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) long zoneId, String account, String password, String authType, String authToken,
                               String deviceId) {
}
