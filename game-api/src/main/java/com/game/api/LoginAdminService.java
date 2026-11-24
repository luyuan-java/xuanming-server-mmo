package com.game.api;

import com.game.proto.login.AccountDeletionResponse;
import com.game.proto.login.ResetAccountRealNameRequest;
import com.game.proto.login.ResetAccountRealNameResponse;
import com.game.proto.login.ScheduleAccountDeletionRequest;
import java.util.concurrent.CompletableFuture;

/**
 * 登录服内部管理与合规只读接口（Dubbo group = {@link DubboGroups#LOGIN}）：
 * <ul>
 *   <li>客服重置实名（对齐 {@code LoginAdmin.ResetAccountRealName}）；</li>
 *   <li>客服代办注销（对齐 {@code LoginAdmin.ScheduleAccountDeletion}）；</li>
 *   <li>支付服务按角色号查询账号实名 {@code pi}（供 {@code game-payment} 未成年人充值限额闸门使用）。</li>
 * </ul>
 */
public interface LoginAdminService {

    /** 客服重置账号实名（清空 {@code real_name_pi} 并删除待查提交）。 */
    CompletableFuture<ResetAccountRealNameResponse> resetAccountRealName(ResetAccountRealNameRequest request);

    /** 客服代办注销（跳过自助帮主拦阻，回执记 {@code source=2}）。 */
    CompletableFuture<AccountDeletionResponse> scheduleAccountDeletion(ScheduleAccountDeletionRequest request);

    /** 查询角色所属账号的实名 {@code pi}（未实名或不存在返回空串）。 */
    CompletableFuture<String> realNamePiOfPlayer(long playerId);
}
