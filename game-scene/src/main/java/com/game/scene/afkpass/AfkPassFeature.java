package com.game.scene.afkpass;

import com.game.proto.BuyAfkPassRequest;
import com.game.proto.GetAfkPassRequest;
import com.game.proto.GmSetAfkPassRequest;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/** 注册挂机月卡客户端协议（{@code SceneAfkPassClientPlayer} 430–432）。 */
public final class AfkPassFeature implements SceneFeature {

    public static final String SERVICE = "SceneAfkPassClientPlayer";

    private final AfkPassService service;

    public AfkPassFeature(AfkPassService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void register(Registrar registrar) {
        registrar.on(SERVICE, "GetAfkPass", GetAfkPassRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.getInfo(call.player())));
        registrar.on(SERVICE, "BuyAfkPass", BuyAfkPassRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.buy(call.world(), call.player(), req)));
        registrar.on(SERVICE, "GmSetAfkPass", GmSetAfkPassRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.gmSet(call.world(), call.player(), req)));
    }
}
