package com.game.scene.skill;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.UpgradeSkillRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;

/** 修习入口显式声明写闸；拒绝也经过服务，回完整技能书。 */
public final class ClassSkillFeature implements SceneFeature {
    private final ClassSkillService skills;
    private final AttributeService attributes;
    private final int notifyPanel;

    public ClassSkillFeature(ClassSkillService skills, AttributeService attributes, MessageIdRegistry registry) {
        this.skills = skills;
        this.attributes = attributes;
        notifyPanel = registry.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
    }

    @Override public void register(Registrar r) {
        r.on("SceneSkillClientPlayer", "UpgradeSkill", UpgradeSkillRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var result = skills.upgrade(call.player(), request);
                    if (result.getUpgradedCount() != 0
                            && !call.player().skillCatalog().row(request.getSkillTableId()).getPassiveStat().isEmpty()) {
                        call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                                .setPanel(attributes.buildPanel(call.player())).build());
                    }
                    call.reply(result);
                });
    }
}
