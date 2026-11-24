package com.game.gateway.login;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code POST /api/real-name} 应答（HTTP 恒 200）：0 成功；401 access_token 无效；422 实名未通过（带 {@code tip}）；429 限流；500 故障。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpRealNameResponse(int code, String message, Integer tip, HttpAntiAddictionStatus antiAddiction) {

    public static final int CODE_OK = 0;
    public static final int CODE_REJECTED = 422;

    public static HttpRealNameResponse of(int code, String message, Integer tip, HttpAntiAddictionStatus antiAddiction) {
        return new HttpRealNameResponse(code, message, tip, antiAddiction);
    }
}
