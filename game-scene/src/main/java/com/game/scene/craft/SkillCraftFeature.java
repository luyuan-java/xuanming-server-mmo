package com.game.scene.craft;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AckCraftProxyDigestRequest;
import com.game.proto.AttributePanelChangedS2C;
import com.game.proto.CraftSkillBookRequest;
import com.game.proto.DismantleSkillBookRequest;
import com.game.proto.GetSkillCraftingStateRequest;
import com.game.proto.LearnSkillBookRequest;
import com.game.proto.ListCraftProxyStallsRequest;
import com.game.proto.PetListChangedS2C;
import com.game.proto.SetCraftProxyStallRequest;
import com.game.scene.attribute.AttributeService;
import com.game.scene.pet.PetService;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.SceneFeature;
import java.util.Objects;

/**
 * 分卷宗永久技能制书、研习、拆页与主城宗师原子代打客户端入口（{@code SceneSkillClientPlayer} 459–464）。
 */
public final class SkillCraftFeature implements SceneFeature {

    private static final String SERVICE = "SceneSkillClientPlayer";

    private final SkillCraftService service;
    private final AttributeService attributes;
    private final PetService pets;
    private final int notifyPanel;
    private final int notifyPets;

    public SkillCraftFeature(SkillCraftService service, AttributeService attributes, PetService pets,
                             MessageIdRegistry registry) {
        this.service = Objects.requireNonNull(service);
        this.attributes = Objects.requireNonNull(attributes);
        this.pets = pets;
        this.notifyPanel = registry.requireId("SceneAttributeClientPlayer", "NotifyAttributePanelChanged");
        this.notifyPets = registry.requireId("ScenePetClientPlayer", "NotifyPetListChanged");
    }

    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetSkillCraftingState", GetSkillCraftingStateRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, (call, request) -> {
                    var outcome = service.getStateWithDrain(call.player());
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "CraftSkillBook", CraftSkillBookRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var outcome = service.craftSkillBook(call.player(), request, call.world());
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    if (outcome.creditedProxyMaster() != null) {
                        call.world().requestKeyProgressSave(outcome.creditedProxyMaster());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "LearnSkillBook", LearnSkillBookRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var outcome = service.learnSkillBook(call.player(), request);
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    if (outcome.pushPanel()) {
                        call.push(notifyPanel, AttributePanelChangedS2C.newBuilder()
                                .setPanel(attributes.buildPanel(call.player())).build());
                    }
                    if (outcome.pushPets() && pets != null) {
                        call.push(notifyPets, PetListChangedS2C.newBuilder()
                                .setPets(pets.buildList(call.player())).build());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "DismantleSkillBook", DismantleSkillBookRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var outcome = service.dismantleSkillBook(call.player(), request);
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "SetCraftProxyStall", SetCraftProxyStallRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var outcome = service.setProxyStall(call.player(), request);
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
        r.on(SERVICE, "ListCraftProxyStalls", ListCraftProxyStallsRequest.class,
                FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(service.listProxyStalls(call.world())));
        r.on(SERVICE, "AckCraftProxyDigest", AckCraftProxyDigestRequest.class,
                FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
                    var outcome = service.ackProxyDigest(call.player());
                    if (outcome.mutated()) {
                        call.world().requestKeyProgressSave(call.player());
                    }
                    call.reply(outcome.response());
                });
    }
}
