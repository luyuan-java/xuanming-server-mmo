package com.game.scene.equip;

import static com.game.scene.world.SceneMessageIds.tip;
import com.game.proto.*;
import com.game.contract.MessageIdRegistry;
import com.game.scene.attribute.AttributeService;
import com.game.scene.bag.BagFeature;
import com.game.scene.player.BagType;
import com.game.scene.world.*;

/** 装备客户端入口；GM 运行模式、协议解析、会话身份由既有分发器处理。 */
public final class EquipmentFeature implements SceneFeature {
    private static final String SERVICE = "SceneBagClientPlayer";
    private final EquipmentService equipment;
    private final EquipmentCraftService craft;
    private final BagFeature bags;
    private final AttributeService attributes;
    private final int notifyPanel;
    public EquipmentFeature(EquipmentService equipment, BagFeature bags, AttributeService attributes, MessageIdRegistry registry) {
        this(equipment, null, bags, attributes, registry);
    }
    public EquipmentFeature(EquipmentService equipment, EquipmentCraftService craft, BagFeature bags,
                            AttributeService attributes, MessageIdRegistry registry) {
        this.equipment = equipment; this.craft = craft; this.bags = bags; this.attributes = attributes;
        notifyPanel = registry.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
    }
    private void changed(PlayerCall call, EquipmentService.Outcome result) {
        if (result.tip() == 0 && result.attributesChanged())
            call.push(notifyPanel, AttributePanelChangedS2C.newBuilder().setPanel(attributes.buildPanel(call.player())).build());
    }
    @Override public void register(Registrar r) {
        r.on(SERVICE, "EquipItem", EquipItemRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.equip(call.player(), request.getItemId()); changed(call, result);
            var reply = EquipItemResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY)).setEquipment(bags.snapshot(call.player(), BagType.EQUIPMENT));
            call.reply(reply.build());
        });
        r.on(SERVICE, "UnequipItem", UnequipItemRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.unequip(call.player(), request.getItemId()); changed(call, result);
            var reply = UnequipItemResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY)).setEquipment(bags.snapshot(call.player(), BagType.EQUIPMENT));
            call.reply(reply.build());
        });
        r.on(SERVICE, "GmGrantItem", GmGrantItemRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.grant(call.player(), request.getConfigId(), request.getCount());
            var reply = GmGrantItemResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY));
            call.reply(reply.build());
        });
        r.on(SERVICE, "EnhanceEquip", EnhanceEquipRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.enhance(call.player(), request); changed(call, result);
            var reply = EnhanceEquipResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) {
                reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY))
                        .setEquipment(bags.snapshot(call.player(), BagType.EQUIPMENT))
                        .setSuccess(result.success())
                        .setEnhanceLevel(result.enhanceLevel())
                        .setReplayed(result.replayed())
                        .setEnhancePityCount(result.enhancePityCount())
                        .setEnhancePityLimit(result.enhancePityLimit());
            }
            call.reply(reply.build());
        });
        r.on(SERVICE, "InlayGem", InlayGemRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.inlay(call.player(), request.getItemId(), request.getSocket(), request.getGemConfigId()); changed(call, result);
            var reply = InlayGemResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY)).setEquipment(bags.snapshot(call.player(), BagType.EQUIPMENT));
            call.reply(reply.build());
        });
        r.on(SERVICE, "RemoveGem", RemoveGemRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var result = equipment.removeGem(call.player(), request.getItemId(), request.getSocket()); changed(call, result);
            var reply = RemoveGemResponse.newBuilder().setErrorMessage(tip(result.tip()));
            if (result.tip() == 0) reply.setBag(bags.snapshot(call.player(), BagType.INVENTORY)).setEquipment(bags.snapshot(call.player(), BagType.EQUIPMENT));
            call.reply(reply.build());
        });
        if (craft != null) {
            r.on(SERVICE, "GetEquipmentCraftRules", GetEquipmentCraftRulesRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                    (call, request) -> call.reply(craft.getRules(call.player())));
            r.on(SERVICE, "PreviewEquipmentCraft", PreviewEquipmentCraftRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                    (call, request) -> call.reply(craft.preview(call.player(), request, call.world())));
            r.on(SERVICE, "CommitEquipmentCraft", CommitEquipmentCraftRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                var result = craft.commit(call.player(), request, bags, call.world());
                if (result.mutated()) {
                    call.world().requestKeyProgressSave(call.player());
                }
                if (result.creditedProxyMaster() != null) {
                    call.world().requestKeyProgressSave(result.creditedProxyMaster());
                }
                if (result.attributesChanged()) {
                    call.push(notifyPanel, AttributePanelChangedS2C.newBuilder().setPanel(attributes.buildPanel(call.player())).build());
                }
                call.reply(result.response());
            });
            r.on(SERVICE, "GetEquipmentMaterialReward", GetEquipmentMaterialRewardRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                    (call, request) -> call.reply(craft.getMaterialReward(call.player(), craft.nowMillis())));
            r.on(SERVICE, "ClaimEquipmentMaterialReward", ClaimEquipmentMaterialRewardRequest.class, FreezePolicy.GATED, BattlePolicy.GATED,
                    (call, request) -> call.reply(craft.claimMaterialReward(call.player(), request, bags, craft.nowMillis(), call.world())));
        }
    }
}
