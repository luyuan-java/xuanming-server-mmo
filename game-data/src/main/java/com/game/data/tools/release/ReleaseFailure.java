package com.game.data.tools.release;

/** 错误只含固定代码与受控字段名；不沿异常链输出 URL、配置值、口令或 JSON 原文。 */
public final class ReleaseFailure extends RuntimeException {
    private final String code;
    ReleaseFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
    static void require(boolean condition, String code) {
        if (!condition) throw new ReleaseFailure(code);
    }
}
