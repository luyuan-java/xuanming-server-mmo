package com.game.scene.merchant;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.proto.BuyMysteryMerchantGoodsRequest;
import com.game.proto.GetMysteryMerchantInfoRequest;
import com.game.proto.GetMysteryMerchantInfoResponse;
import com.game.scene.bag.BagFeature;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 神秘商人客户端入口（{@code SceneMysteryMerchantClientPlayer} 314 / 315）。
 */
public final class MysteryMerchantFeature implements SceneFeature {

    private static final String SERVICE = "SceneMysteryMerchantClientPlayer";

    private final MysteryMerchantService merchant;
    private final BagFeature bags;

    public MysteryMerchantFeature(MysteryMerchantService merchant, BagFeature bags) {
        this.merchant = merchant;
        this.bags = bags;
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetMysteryMerchantInfo", GetMysteryMerchantInfoRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(GetMysteryMerchantInfoResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(merchant.buildInfo(call.player(), merchant.nowMillis()))
                        .build()));
        r.on(SERVICE, "BuyMysteryMerchantGoods", BuyMysteryMerchantGoodsRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(merchant.buy(call.player(), request, bags, merchant.nowMillis())));
    }
}
