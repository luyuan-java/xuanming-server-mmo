package com.game.login.token;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * access / refresh 令牌（同 mmorpg {@code go/login/internal/logic/pkg/token}）：
 * <ul>
 *   <li>令牌是 32 字节（16 字节家族号 + 16 字节随机数）的 base64url 无填充编码（43 字符）；access 有效 2h、refresh 有效 720h（可配）；</li>
 *   <li>口令 / 第三方登录成功签一对，开一个新<b>家族</b>（这次登录及其全部轮换后继）；access token 登录不签（不搅动令牌）；</li>
 *   <li>refresh 一次性：轮换时原子作废旧的并签新的一对，家族不变；access 不随轮换作废。例外是重放宽限窗口（缺省 30s，0 关闭）：
 *       窗口内同一旧 refresh 再来、且它换出的新 refresh 还没用过，原样返回同一对、不再签发——回包丢失后客户端拿原 token 重试是安全的；</li>
 *   <li>窗口外再出现的已轮换旧 refresh 视为泄漏信号：吊销它所在的整个家族，该家族的 refresh 与 access 即刻失效，同账号其他登录不受影响
 *       （可配成只观测）；</li>
 *   <li>每账号活家族至多 32 个，超出吊销最久没轮换的。</li>
 * </ul>
 * 实现可阻塞（Redis），只在 login 工作线程上调用；存储故障抛异常，调用方按各自的口径映射（登录不签令牌 / 认证失败 / 刷新失败）。
 */
public interface LoginTokens {

    /** 签一对新令牌。 */
    TokenPair issue(String account, String authType, String deviceId);

    /** access token 有效时给出它的数据；无效 / 过期 / 空串为空。 */
    Optional<TokenData> validateAccess(String accessToken);

    /**
     * 轮换，不设截止（HTTP {@code /api/refresh-token}：不走 gate 的会话 lane，没有「下一条请求」要保序）。
     * 语义见 {@link #refresh(String, BooleanSupplier)}。
     */
    default Optional<TokenPair> refresh(String refreshToken) {
        return refresh(refreshToken, () -> false);
    }

    /**
     * 轮换：refresh token 有效时作废它并签新的一对；宽限窗口内重放已轮换的旧 refresh（且新 refresh 未用过、家族仍在）返回同一对；
     * 无效 / 过期 / 已被用过且不在窗口内（此时吊销其家族）/ 新 refresh 已用过 / 家族已吊销 / 空串为空。
     *
     * <p>{@code pastDeadline}：读到旧 refresh 之后、发出轮换（作废与签发是同一步原子写）之前看一次，为真就什么都不写，
     * 抛 {@link RotationAbandoned}，旧 refresh 仍然有效。客户端路径传请求截止：截止之后 gate 随时会按超时放行同一会话的下一条请求，
     * 这时再轮换会晚于它落地（mmorpg client-request-ordering.md §6.1，同 {@code token.go} Refresh 提交前查 ctx）。
     * 重放判定（旧 refresh 已不在时）不看截止；它只读，唯一的写是检测到重用时吊销家族（漏掉这一次就再也等不到下一次检测）。
     */
    Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline);

    /** 轮换在写之前因请求截止放弃：什么都没写，旧 refresh 仍然有效，调用方应回可重试的错误（不是「令牌无效」）。 */
    final class RotationAbandoned extends RuntimeException {

        public RotationAbandoned(String message) {
            super(message, null, false, false);
        }
    }
}
