package com.game.api;

import com.game.proto.data_service.GetPlayerChannelIdentityRequest;
import com.game.proto.data_service.GetPlayerChannelIdentityResponse;
import com.game.proto.login.AccountDeletionRequest;
import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.CancelAccountDeletionRequest;
import com.game.proto.login.LoginRequest;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenRequest;
import com.game.proto.login.RefreshTokenResponse;
import com.game.proto.login.SubmitRealNameRequest;
import com.game.proto.login.SubmitRealNameResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 无会话的账号登录与前置合规操作（Dubbo 服务，game-login 提供、game-gateway 调用）：客户端连 gate 之前经 HTTP 完成认证、
 * 刷新令牌、实名认证与账号注销申请/撤销（对应权威主仓 {@code LoginPreGate}）。
 * 参数与返回值直接用客户端契约里的 {@code loginpb} 消息。
 */
public interface AccountLoginService {

    /** 认证 → 取 / 建账号 → 签一对令牌（access token 登录不签）→ 角色列表 + 注销与防沉迷状态。 */
    CompletableFuture<LoginResponse> login(LoginRequest request);

    /** refresh token 轮换：成功回新的一对、旧 refresh 作废；无效 / 已用过回 2000。 */
    CompletableFuture<RefreshTokenResponse> refreshToken(RefreshTokenRequest request);

    /** 实名提交（凭 access_token 鉴权，姓名与身份证号只经 NPPA 核验，不落明文）。 */
    default CompletableFuture<SubmitRealNameResponse> submitRealName(SubmitRealNameRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("submitRealName not implemented"));
    }

    /** 申请账号注销（需重新凭据认证，进入冷静期并吊销令牌、踢线）。 */
    default CompletableFuture<AccountDeletionResponse> requestAccountDeletion(AccountDeletionRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("requestAccountDeletion not implemented"));
    }

    /** 撤销冷静期内的账号注销（凭 access_token 鉴权）。 */
    default CompletableFuture<AccountDeletionResponse> cancelAccountDeletion(CancelAccountDeletionRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("cancelAccountDeletion not implemented"));
    }

    /** 统计该区历史上已建角色总数（含已删除角色，删角不减；供网关按角色软上限自动暂停创角）。 */
    default CompletableFuture<Long> countZoneRoles(int zoneId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("countZoneRoles not implemented"));
    }

    /** 按角色号查询所属账号与登录渠道/openid（供 game-content-guard 登录后平台内容安全审核路由使用）。 */
    default CompletableFuture<GetPlayerChannelIdentityResponse> getPlayerChannelIdentity(GetPlayerChannelIdentityRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("getPlayerChannelIdentity not implemented"));
    }

    /**
     * 校验客户端 {@code access_token} 并返回对应账号名（供 game-gateway {@code /api/assign-gate} 鉴权）：
     * 有效返回非空账号名；格式不对/未知/过期/家族吊销返回空串 {@code ""}；令牌存储不可用时以异常结束 future（由网关区分 401 与 500）。
     */
    default CompletableFuture<String> resolveAccessTokenAccount(String accessToken) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("resolveAccessTokenAccount not implemented"));
    }
}
