package com.game.scene.travelpass;

import com.game.proto.ClaimTravelPassRewardRequest;
import com.game.proto.EquipTravelPassCosmeticRequest;
import com.game.proto.GetTravelPassStateRequest;
import com.game.proto.UnlockTravelPassPremiumRequest;
import com.game.scene.bag.BagFeature;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/**
 * 青云游历手记客户端入口（{@code SceneActivityClientPlayer} 526–529）。
 */
public final class TravelPassFeature implements SceneFeature {

    public static final String SERVICE = "SceneActivityClientPlayer";

    private final TravelPassService service;
    private final BagFeature bags;

    public TravelPassFeature(TravelPassService service, BagFeature bags) {
        this.service = Objects.requireNonNull(service, "service");
        this.bags = Objects.requireNonNull(bags, "bags");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetTravelPassState", GetTravelPassStateRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.getState(call.player(), service.nowMillis(), request.getPassId())));
        r.on(SERVICE, "UnlockTravelPassPremium", UnlockTravelPassPremiumRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(service.unlockPremium(call.world(), call.player(), request, service.nowMillis())));
        r.on(SERVICE, "ClaimTravelPassReward", ClaimTravelPassRewardRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(service.claimReward(call.world(), call.player(), request, bags, service.nowMillis())));
        r.on(SERVICE, "EquipTravelPassCosmetic", EquipTravelPassCosmeticRequest.class,
                FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.equipCosmetic(call.world(), call.player(), request, service.nowMillis())));
    }
}
