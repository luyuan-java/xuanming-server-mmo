package com.game.login.token;

import java.util.Optional;

/**
 * access / refresh 令牌（同 mmorpg {@code go/login/internal/logic/pkg/token}）：
 * <ul>
 *   <li>令牌是 32 字节随机数的 base64url 无填充编码（43 字符）；access 有效 2h、refresh 有效 720h（可配）；</li>
 *   <li>口令 / 第三方登录成功签一对；access token 登录不签（不搅动令牌）；</li>
 *   <li>refresh 一次性：轮换时原子作废旧的并签新的一对；access 不随轮换作废。例外是重放宽限窗口（缺省 30s，0 关闭）：
 *       窗口内同一旧 refresh 再来、且它换出的新 refresh 还没用过，原样返回同一对、不再签发——回包丢失后客户端拿原 token 重试是安全的；</li>
 *   <li>每账号活跃 refresh 至多 32 个，超出淘汰最旧的（连同其令牌键）。</li>
 * </ul>
 * 实现可阻塞（Redis），只在 login 工作线程上调用；存储故障抛异常，调用方按各自的口径映射（登录不签令牌 / 认证失败 / 刷新失败）。
 */
public interface LoginTokens {

    /** 签一对新令牌。 */
    TokenPair issue(String account, String authType, String deviceId);

    /** access token 有效时给出它的数据；无效 / 过期 / 空串为空。 */
    Optional<TokenData> validateAccess(String accessToken);

    /**
     * 轮换：refresh token 有效时作废它并签新的一对；宽限窗口内重放已轮换的旧 refresh（且新 refresh 未用过）返回同一对；
     * 无效 / 过期 / 已被用过且不在窗口内 / 新 refresh 已用过 / 空串为空。
     */
    Optional<TokenPair> refresh(String refreshToken);
}
