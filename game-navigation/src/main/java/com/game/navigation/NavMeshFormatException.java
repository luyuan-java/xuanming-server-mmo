package com.game.navigation;

import java.io.IOException;

/** 格式错误与配额失败均拒绝整个输入；offset 为文件绝对字节位置。 */
public final class NavMeshFormatException extends IOException {
    public enum Code { FORMAT, VERSION, LENGTH, LIMIT, NUMBER, REFERENCE, ADJACENCY, DUPLICATE }
    private final Code code;
    private final long offset;

    public NavMeshFormatException(Code code, long offset, String message) {
        super(code + " @" + offset + ": " + message);
        this.code = code;
        this.offset = offset;
    }
    public Code code() { return code; }
    public long offset() { return offset; }
}
