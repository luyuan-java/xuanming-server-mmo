package com.game.gateway.login;

/** {@code POST /api/real-name} 请求体（姓名与身份证号绝不进日志与 {@code toString}）。 */
public record HttpRealNameRequest(String accessToken, String name, String idNumber, long zoneId) {

    @Override
    public String toString() {
        return "HttpRealNameRequest[zoneId=" + zoneId + ", redacted]";
    }
}
