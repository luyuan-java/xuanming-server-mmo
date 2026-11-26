package com.game.scene.season;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.CatchupSeasonSealRequest;
import com.game.proto.EquipSeasonSealRequest;
import com.game.proto.GetSeasonStateRequest;
import com.game.proto.SetRetroHonorTargetRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/**
 * 赛季客户端入口（{@code SceneSeasonClientPlayer} 453–455、569）。
 */
public final class SeasonFeature implements SceneFeature {

    private static final String SERVICE = "SceneSeasonClientPlayer";

    private final SeasonService season;
    private final AttributeService attributes;
    private final int notifyPanel;

    public SeasonFeature(SeasonService season, AttributeService attributes, MessageIdRegistry registry) {
        this.season = season;
        this.attributes = attributes;
        this.notifyPanel = registry.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetSeasonState", GetSeasonStateRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(season.getState(call.player(), season.nowMillis())));
        r.on(SERVICE, "EquipSeasonSeal", EquipSeasonSealRequest.class, FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> {
                    var outcome = season.equipSeal(call.player(), request, season.nowMillis());
                    if (outcome.changed()) {
                        call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                                .setPanel(attributes.buildPanel(call.player())).build());
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "CatchupSeasonSeal", CatchupSeasonSealRequest.class, FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> {
                    var outcome = season.catchup(call.player(), request, season.nowMillis());
                    if (outcome.changed()) {
                        call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                                .setPanel(attributes.buildPanel(call.player())).build());
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "SetRetroHonorTarget", SetRetroHonorTargetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED,
                (call, request) -> {
                    var outcome = season.setRetroHonorTarget(call.player(), request, season.nowMillis());
                    if (outcome.changed()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
    }
}
