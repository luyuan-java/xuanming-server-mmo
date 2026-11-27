package com.game.scene.afkpass;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.BuyAfkPassRequest;
import com.game.proto.BuyAfkPassResponse;
import com.game.proto.GetAfkPassRequest;
import com.game.proto.GmSetAfkPassRequest;
import com.game.proto.GmSetAfkPassResponse;
import com.game.scene.attribute.AttributeService;
import com.game.scene.bag.BagFeature;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/** 注册挂机月卡客户端协议（{@code SceneAfkPassClientPlayer} 430–432）。 */
public final class AfkPassFeature implements SceneFeature {

    public static final String SERVICE = "SceneAfkPassClientPlayer";

    private final AfkPassService service;
    private final AttributeService attributes;
    private final BagFeature bags;
    private final int notifyPanel;

    public AfkPassFeature(AfkPassService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.attributes = null;
        this.bags = null;
        this.notifyPanel = 0;
    }

    public AfkPassFeature(AfkPassService service, AttributeService attributes, MessageIdRegistry registry) {
        this(service, attributes, registry, null);
    }

    public AfkPassFeature(AfkPassService service, AttributeService attributes, MessageIdRegistry registry, BagFeature bags) {
        this.service = Objects.requireNonNull(service, "service");
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        this.bags = bags;
        this.notifyPanel = Objects.requireNonNull(registry, "registry")
                .requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
    }

    private void pushPanel(PlayerCall call) {
        if (attributes != null) {
            call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                    .setPanel(attributes.buildPanel(call.player())).build());
        }
    }

    @Override
    public void register(Registrar registrar) {
        registrar.on(SERVICE, "GetAfkPass", GetAfkPassRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, req) -> call.reply(service.getInfo(call.player())));
        registrar.on(SERVICE, "BuyAfkPass", BuyAfkPassRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> {
                    BuyAfkPassResponse resp = service.buy(call.world(), call.player(), req, bags);
                    if (resp.getErrorMessage().getId() == 0 && !resp.getReplayed()) {
                        pushPanel(call);
                    }
                    call.reply(resp);
                });
        registrar.on(SERVICE, "GmSetAfkPass", GmSetAfkPassRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW,
                (call, req) -> {
                    GmSetAfkPassResponse resp = service.gmSet(call.world(), call.player(), req);
                    if (resp.getErrorMessage().getId() == 0) {
                        pushPanel(call);
                    }
                    call.reply(resp);
                });
    }
}
