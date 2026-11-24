package com.game.gateway.login;

/** {@code POST /api/account/delete} 请求体（凭据与授权码绝不进日志与 {@code toString}）。 */
public record HttpAccountDeletionRequest(long zoneId, String account, String password, String authType,
                                         String authToken, String authorizationCode) {

    @Override
    public String toString() {
        return "HttpAccountDeletionRequest[zoneId=" + zoneId + ", redacted]";
    }
}
