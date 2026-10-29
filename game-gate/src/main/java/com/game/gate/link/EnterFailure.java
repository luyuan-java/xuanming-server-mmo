package com.game.gate.link;

/** 仅描述链路已确认没有调用 write 的进场失败；已写或结果未知不进入此分类。 */
public enum EnterFailure {
    NODE_MISSING,
    CONNECT_UNAVAILABLE,
    PERMANENT;

    public boolean retryable() { return this != PERMANENT; }
}
