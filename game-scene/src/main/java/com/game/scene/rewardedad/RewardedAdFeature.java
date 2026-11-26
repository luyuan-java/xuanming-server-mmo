package com.game.scene.rewardedad;

import com.game.proto.BeginRewardedAdSessionRequest;
import com.game.proto.ClaimRewardedAdRequest;
import com.game.proto.GetRewardedAdInfoRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/**
 * 激励视频广告客户端入口（{@code SceneRewardedAdClientPlayer}）。
 */
public final class RewardedAdFeature implements SceneFeature {

    public static final String SERVICE = "SceneRewardedAdClientPlayer";

    private final RewardedAdService service;

    public RewardedAdFeature(RewardedAdService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetRewardedAdInfo", GetRewardedAdInfoRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.getInfo(call.player(), request, service.nowMillis())));
        r.on(SERVICE, "BeginRewardedAdSession", BeginRewardedAdSessionRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(service.beginSession(call.world(), call.player(), request, service.nowMillis())));
        r.on(SERVICE, "ClaimRewardedAd", ClaimRewardedAdRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(service.claim(call.world(), call.player(), request, service.nowMillis())));
    }
}
