package com.game.audit;

/** topic 与契约不符（分区数不对、配置改不过来）：不可自动恢复，进程拒绝启动 / 管线保持停用，需要人工处理（通常是升代次）。 */
public class AuditTopicContractException extends RuntimeException {

    public AuditTopicContractException(String message) {
        super(message);
    }
}
