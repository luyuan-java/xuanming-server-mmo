package com.game.gateway.login;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code POST /api/account/delete} 与 {@code /api/account/delete/cancel} 应答（HTTP 恒 200，与权威主仓客户端契约一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpAccountDeletionResponse(
        int code,
        String message,
        int tip,
        Integer state,
        Long executeAfter,
        List<Blocker> blockers) {

    public record Blocker(long playerId, String reason) {
    }

    public static HttpAccountDeletionResponse answered(int code, String message, int tip,
                                                       int state, long executeAfter, List<Blocker> blockers) {
        return new HttpAccountDeletionResponse(code, message, tip, state, executeAfter,
                blockers == null || blockers.isEmpty() ? null : List.copyOf(blockers));
    }

    public static HttpAccountDeletionResponse error(int code, String message) {
        return new HttpAccountDeletionResponse(code, message, 0, null, null, null);
    }
}
