package com.game.login.token;

import java.util.Optional;

/**
 * access / refresh 令牌（同 mmorpg {@code go/login/internal/logic/pkg/token}）：
 * <ul>
 *   <li>令牌是 32 字节随机数的 base64url 无填充编码（43 字符）；access 有效 2h、refresh 有效 720h（可配）；</li>
 *   <li>口令 / 第三方登录成功签一对；access token 登录不签（不搅动令牌）；</li>
 *   <li>refresh 一次性：轮换时原子取走旧的，再签新的一对；access 不随轮换作废；</li>
 *   <li>每账号活跃 refresh 至多 32 个，超出淘汰最旧的（连同其令牌键）。</li>
 * </ul>
 * 实现可阻塞（Redis），只在 login 工作线程上调用；存储故障抛异常，调用方按各自的口径映射（登录不签令牌 / 认证失败 / 刷新失败）。
 */
public interface LoginTokens {

    /** 签一对新令牌。 */
    TokenPair issue(String account, String authType, String deviceId);

    /** access token 有效时给出它的数据；无效 / 过期 / 空串为空。 */
    Optional<TokenData> validateAccess(String accessToken);

    /** 轮换：refresh token 有效时作废它并签新的一对；无效 / 过期 / 已被用过 / 空串为空。 */
    Optional<TokenPair> refresh(String refreshToken);
}
