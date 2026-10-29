package com.game.data.consume;

/** 失败只暴露有界原因码，不把 broker 地址、认证配置或任意异常文本放进管理应答与指标。 */
public final class AuditProbeFailure extends RuntimeException {
    public enum Reason { BUDGET, INTERRUPTED, BROKER, INVALID_METADATA, INVALID_OFFSETS, TOPIC_CHANGED, OFFSET_OVERFLOW }
    private final Reason reason;
    public AuditProbeFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
