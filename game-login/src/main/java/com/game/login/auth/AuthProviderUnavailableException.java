package com.game.login.auth;

/**
 * 「不是玩家凭证的问题」的三方认证失败（同 mmorpg {@code auth.ErrProviderUnavailable}）：网络 / 超时、渠道非 2xx、
 * 应答过大或不可解析、渠道系统繁忙或配额超限、本服务的 AppId / AppSecret / 出口 IP 被渠道拒绝、拿不到必需的 unionid。
 *
 * <p>{@link LoginAuthenticator} 把它原样抛给登录主流程，登录回故障码 {@code kLoginAuthProviderUnavailable}，
 * 不并进「凭证无效」的 2000。消息只带固定说明与渠道错误码，不带 URL / 凭据 / 应答原文，可以进日志。
 * 继承 {@link IllegalStateException}，原先按「外部系统出错」处理它的调用方语义不变。
 */
public final class AuthProviderUnavailableException extends IllegalStateException {

    public AuthProviderUnavailableException(String message) {
        super(message);
    }
}
