package com.game.api;

import com.game.proto.rolefence.ApplyRoleFenceRequest;
import com.game.proto.rolefence.ApplyRoleFenceResponse;
import com.game.proto.rolefence.PrepareRoleFreezeRequest;
import com.game.proto.rolefence.PrepareRoleFreezeResponse;
import com.game.proto.rolefence.PurgeRemovedRoleRequest;
import com.game.proto.rolefence.PurgeRemovedRoleResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 跨域角色护栏参与者契约（对齐 {@code proto/role_fence/role_fence.proto}）。
 * 供独立库或跨服务参与者接入两阶段角色冻结预检、护栏广播与删角收尾清理。
 */
public interface RoleFenceParticipantService {

    /** 预检并（非 dry_run 时）锁定本域角色护栏。 */
    CompletableFuture<PrepareRoleFreezeResponse> prepareRoleFreeze(PrepareRoleFreezeRequest request);

    /** 幂等广播护栏状态（按 version 单调覆盖）。 */
    CompletableFuture<ApplyRoleFenceResponse> applyRoleFence(ApplyRoleFenceRequest request);

    /** 删角收尾清理本域玩家个人数据（仅接受墓碑态 account=""）。 */
    CompletableFuture<PurgeRemovedRoleResponse> purgeRemovedRole(PurgeRemovedRoleRequest request);
}
