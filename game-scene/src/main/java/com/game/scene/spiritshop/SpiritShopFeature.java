package com.game.scene.spiritshop;

import com.game.proto.BuySpiritShopGoodsRequest;
import com.game.proto.GetSpiritShopInfoRequest;
import com.game.scene.bag.BagFeature;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/**
 * 官方灵石百宝阁客户端入口（{@code SceneSpiritShopClientPlayer} 509 / 510）。
 */
public final class SpiritShopFeature implements SceneFeature {

    public static final String SERVICE = "SceneSpiritShopClientPlayer";

    private final SpiritShopService shop;
    private final BagFeature bags;

    public SpiritShopFeature(SpiritShopService shop, BagFeature bags) {
        this.shop = Objects.requireNonNull(shop, "shop");
        this.bags = Objects.requireNonNull(bags, "bags");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetSpiritShopInfo", GetSpiritShopInfoRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(shop.getInfo(call.player(), shop.nowMillis())));
        r.on(SERVICE, "BuySpiritShopGoods", BuySpiritShopGoodsRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> call.reply(shop.buy(call.world(), call.player(), request, bags, shop.nowMillis())));
    }
}
