package com.game.data.consume;

/** 仅查询 topic 元数据、保留范围及组位点；接口没有提交、重置、建 topic 或删除操作。 */
public interface GroupOffsetReader extends AutoCloseable {
    AuditOffsetSnapshot read(AuditOffsetSnapshot.Target target, AuditProbeBudget budget);

    /** 取消未完成的请求；必须可与 read 并发调用，不等待外部 I/O。 */
    @Override void close();
}
