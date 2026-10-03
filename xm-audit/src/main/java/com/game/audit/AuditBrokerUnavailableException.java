package com.game.audit;

/** 连不上 Kafka / 管理操作超时：可恢复，调用方稍后重试（启动不因此失败）。 */
public class AuditBrokerUnavailableException extends RuntimeException {

    public AuditBrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
