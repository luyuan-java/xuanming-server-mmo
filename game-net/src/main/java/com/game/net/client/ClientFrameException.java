package com.game.net.client;

/** 客户端帧非法。收到即断开连接，不回包（与 C++ 一致）。 */
public final class ClientFrameException extends Exception {

    public enum Reason {
        INVALID_LENGTH,
        CHECKSUM,
        INVALID_NAME_LEN,
        UNKNOWN_TYPE,
        PARSE
    }

    private final Reason reason;

    public ClientFrameException(Reason reason, String detail) {
        super(reason + ": " + detail, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
