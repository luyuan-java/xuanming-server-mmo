package com.game.api;

import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 无会话的账号登录（Dubbo 服务，xm-login 提供、xm-gateway 调用）：客户端连 gate 之前经 HTTP 先完成认证、拿令牌
 * （对应 mmorpg Java Gateway {@code POST /api/login} / {@code /api/refresh-token} → go login 的 Login / RefreshToken）。
 * 参数与返回值直接用客户端契约里的 {@code loginpb} 消息（字段语义同 TCP 48 / 127）。
 *
 * <p>业务失败写进应答体的 {@code error_message}（认证失败 2000、登录在途 2005……），future 正常完成；future 异常完成 = 调用失败
 * （login 过载 / 取建账号出错），gateway 按「服务不可用」回。不查设备数、不绑会话：之后客户端在 gate 上用
 * {@code auth_type="access_token"} 再登录一次完成绑定。
 */
public interface AccountLoginService {

    /** 认证 → 取 / 建账号 → 签一对令牌（access token 登录不签）→ 角色列表。 */
    CompletableFuture<LoginResponse> login(LoginRequest request);

    /** refresh token 轮换：成功回新的一对、旧 refresh 作废；无效 / 已用过回 2000。 */
    CompletableFuture<RefreshTokenResponse> refreshToken(RefreshTokenRequest request);
}
