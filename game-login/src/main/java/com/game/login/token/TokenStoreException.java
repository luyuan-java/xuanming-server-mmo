package com.game.login.token;

/**
 * 令牌存储出错（Redis 不可达 / 超时……）。消息里只有操作名与底层异常的类名、不带原异常：Redisson 的异常消息会带上命令参数，
 * 而令牌键里就是令牌本身，原样进日志等于泄露凭据。
 */
public final class TokenStoreException extends RuntimeException {

    public TokenStoreException(String operation, RuntimeException cause) {
        super("令牌存储" + operation + "失败: " + cause.getClass().getSimpleName());
    }
}
