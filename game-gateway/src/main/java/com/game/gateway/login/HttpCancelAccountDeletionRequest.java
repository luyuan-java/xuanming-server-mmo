package com.game.gateway.login;

/** {@code POST /api/account/delete/cancel} 请求体（令牌绝不进日志与 {@code toString}）。 */
public record HttpCancelAccountDeletionRequest(long zoneId, String accessToken) {

    @Override
    public String toString() {
        return "HttpCancelAccountDeletionRequest[zoneId=" + zoneId + ", redacted]";
    }
}
