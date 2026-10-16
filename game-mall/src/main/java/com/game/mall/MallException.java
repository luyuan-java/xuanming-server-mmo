package com.game.mall;

/** 可公开的业务拒绝；数据库或密钥等内部异常不转换成此异常泄露给调用方。 */
final class MallException extends RuntimeException {
    private final int status;

    MallException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
