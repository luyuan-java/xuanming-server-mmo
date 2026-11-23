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
    private final int notifyPets;
    private final com.game.scene.pet.PetService pets;

    public ClassSkillFeature(ClassSkillService skills, AttributeService attributes, MessageIdRegistry registry) {
        this(skills, attributes, registry, null);
    }

    public ClassSkillFeature(ClassSkillService skills, AttributeService attributes, MessageIdRegistry registry,
            com.game.scene.pet.PetService pets) {
        this.skills = skills;
        this.attributes = attributes;
        this.pets = pets;
        notifyPets = registry.requireId("ScenePetClientPlayer", "NotifyPetListChanged");
        notifyPanel = registry.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
    }

    @Override public void register(Registrar r) {
        r.on("SceneSkillClientPlayer", "UpgradeSkill", UpgradeSkillRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var result = skills.upgrade(call.player(), request);
                    if (result.getUpgradedCount() != 0) call.world().requestSave(call.player());
                    if (result.getUpgradedCount() != 0 && request.getPetId() != 0 && pets != null) {
                        call.push(notifyPets, com.game.proto.PetListChangedS2C.newBuilder().setPets(pets.buildList(call.player())).build());
                    } else if (result.getUpgradedCount() != 0
                            && ClassSkillCatalog.passive(call.player().skillCatalog().row(request.getSkillTableId()))) {
                        call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                                .setPanel(attributes.buildPanel(call.player())).build());
                    }
                    call.reply(result);
                });
    }
}
