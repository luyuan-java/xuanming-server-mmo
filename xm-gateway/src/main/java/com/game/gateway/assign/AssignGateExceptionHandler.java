package com.game.gateway.assign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 守住 assign-gate「恒 HTTP 200 + body.code」的契约：客户端只接受 HTTP 200，非 200 一律当成网络错误，
 * 拿不到可读的 {@code error}。这里把请求体错误与未预期异常也落成 {@link AssignGateResponse}，且都不签令牌（fail-closed）。
 */
@RestControllerAdvice(assignableTypes = AssignGateController.class)
public class AssignGateExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssignGateExceptionHandler.class);

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public AssignGateResponse badRequest(Exception e) {
        log.debug("assign-gate 请求体不可解析: {}", e.getMessage());
        return AssignGateResponse.rejected(AssignGateResponse.CODE_BAD_REQUEST, AssignGateResponse.ERR_BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public AssignGateResponse internalError(Exception e) {
        log.error("assign-gate 未预期异常", e);
        return AssignGateResponse.rejected(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_INTERNAL);
    }
}
