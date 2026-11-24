package com.game.scene.rename;

import com.game.proto.RenamePlayerRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 角色改名客户端入口（{@code SceneClientPlayerCommon.RenamePlayer} 428）。
 *
 * <p>冻结策略采用 {@link FreezePolicy#GATED}（由 {@link PlayerRenameService} 回 {@code kRoleRenameFrozen} 2054）；
 * 战斗策略为 {@link BattlePolicy#ALLOW}（与 C++ {@code PlayerRenameSystem} 一致）。
 */
public final class PlayerRenameFeature implements SceneFeature {

    public static final String SERVICE = "SceneClientPlayerCommon";

    private final PlayerRenameService service;

    public PlayerRenameFeature(PlayerRenameService service) {
        this.service = service;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "RenamePlayer", RenamePlayerRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.requestRename(call.world(), call.player(), req)));
    }
}
