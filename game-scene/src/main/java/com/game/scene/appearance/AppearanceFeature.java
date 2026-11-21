package com.game.scene.appearance;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.CancelAppearanceTrialRequest;
import com.game.proto.GetAppearanceInfoRequest;
import com.game.proto.GetAppearanceInfoResponse;
import com.game.proto.StartAppearanceTrialRequest;
import com.game.proto.SwitchAppearanceRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 角色形象切换与 1 小时免费试用的客户端入口（{@code SceneAppearanceClientPlayer}）。
 *
 * <p>查询只读；写入口采用 {@link FreezePolicy#GATED} 由 {@link AppearanceService} 自行先做幂等查账再判冻结
 * （与 C++ {@code PlayerAppearanceSystem} 一致），不拦战斗中查看与切肤。
 */
public final class AppearanceFeature implements SceneFeature {

    public static final String SERVICE = "SceneAppearanceClientPlayer";

    private final AppearanceService service;

    public AppearanceFeature(AppearanceService service) {
        this.service = service;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetAppearanceInfo", GetAppearanceInfoRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, req) -> call.reply(GetAppearanceInfoResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(service.buildInfo(call.player()))
                        .build()));
        r.on(SERVICE, "SwitchAppearance", SwitchAppearanceRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.switchAppearance(call.world(), call.player(), req)));
        r.on(SERVICE, "StartAppearanceTrial", StartAppearanceTrialRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.startTrial(call.world(), call.player(), req)));
        r.on(SERVICE, "CancelAppearanceTrial", CancelAppearanceTrialRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.cancelTrial(call.world(), call.player())));
    }
}
