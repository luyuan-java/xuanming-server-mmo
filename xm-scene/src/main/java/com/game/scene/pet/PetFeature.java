package com.game.scene.pet;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AllocatePetPointsResponse;
import com.game.proto.AutoAllocatePetPointsRequest;
import com.game.proto.AutoAllocatePetPointsResponse;
import com.game.proto.GetPetListRequest;
import com.game.proto.GetPetListResponse;
import com.game.proto.GmGrantPetRequest;
import com.game.proto.GmGrantPetResponse;
import com.game.proto.PetListChangedS2C;
import com.game.proto.RecallPetRequest;
import com.game.proto.RecallPetResponse;
import com.game.proto.RenamePetRequest;
import com.game.proto.RenamePetResponse;
import com.game.proto.ResetPetPointsRequest;
import com.game.proto.ResetPetPointsResponse;
import com.game.proto.SummonPetRequest;
import com.game.proto.SummonPetResponse;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import java.util.Map;
import java.util.TreeMap;

/**
 * 宝宝的客户端方法（{@code ScenePetClientPlayer}：181 列表、183 出战、185 收回、186 加点、182 洗点、188 自动加点、189 改名、
 * 187 GM 发放；184 列表变化推送）。规则在 {@link PetService}。成功回 {@code error_message{0}} + 全量列表（188 回建议、187 另带新宝宝号），
 * 失败只回 tip。187 是 GM 方法，运行模式闸在 gate 与 scene 分发入口各一道（architecture §4.4）。
 */
public final class PetFeature implements SceneFeature {

    private static final String SERVICE = "ScenePetClientPlayer";

    private final PetService pets;
    private final int notifyPetListChanged;

    public PetFeature(PetService pets, MessageIdRegistry registry) {
        this.pets = pets;
        this.notifyPetListChanged = registry.requireId(SERVICE, "NotifyPetListChanged");
    }

    /**
     * 冻结策略（scene-handoff-spec §5.9）：181 只读；其余 GATED，由 {@link PetService} 的写前置在冻结中回 1005
     * （188 自动加点只算不落、不过写前置，同基线）。
     */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetPetList", GetPetListRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, (call, request) -> call.reply(
                GetPetListResponse.newBuilder().setErrorMessage(tip(0)).setPets(pets.buildList(call.player())).build()));
        r.on(SERVICE, "SummonPet", SummonPetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            int result = pets.summon(call.player(), request.getPetId());
            SummonPetResponse.Builder response = SummonPetResponse.newBuilder().setErrorMessage(tip(result));
            if (result == 0) {
                response.setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "RecallPet", RecallPetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            int result = pets.recall(call.player());
            RecallPetResponse.Builder response = RecallPetResponse.newBuilder().setErrorMessage(tip(result));
            if (result == 0) {
                response.setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "AllocatePetPoints", AllocatePetPointsRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, this::allocate);
        r.on(SERVICE, "ResetPetPoints", ResetPetPointsRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            int result = pets.reset(call.player(), request.getPetId());
            ResetPetPointsResponse.Builder response = ResetPetPointsResponse.newBuilder().setErrorMessage(tip(result));
            if (result == 0) {
                response.setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "AutoAllocatePetPoints", AutoAllocatePetPointsRequest.class, FreezePolicy.GATED, BattlePolicy.ALLOW, (call, request) -> {
            PetService.Suggestion suggestion = pets.autoAllocate(call.player(), request.getPetId());
            AutoAllocatePetPointsResponse.Builder response = AutoAllocatePetPointsResponse.newBuilder()
                    .setErrorMessage(tip(suggestion.tip()));
            if (suggestion.tip() == 0) {
                response.setPetId(request.getPetId());
                suggestion.suggested().forEach((dimension, points) -> response.putSuggested(dimension, (int) (long) points));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "RenamePet", RenamePetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            int result = pets.rename(call.player(), request.getPetId(), request.getName());
            RenamePetResponse.Builder response = RenamePetResponse.newBuilder().setErrorMessage(tip(result));
            if (result == 0) {
                response.setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
        r.on(SERVICE, "GmGrantPet", GmGrantPetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            PetService.Grant grant = pets.grant(call.player(), request.getPetTableId());
            GmGrantPetResponse.Builder response = GmGrantPetResponse.newBuilder().setErrorMessage(tip(grant.tip()));
            if (grant.tip() == 0) {
                response.setPetId(grant.petId()).setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
    }

    /** 186：宝宝号 0 或没带任何维度回 1005（同基线 handler 前置），其余交给服务。 */
    private void allocate(PlayerCall call, AllocatePetPointsRequest request) {
        int result;
        if (request.getPetId() == 0 || request.getAllocatedCount() == 0) {
            result = PetService.INVALID_PARAMETER;
        } else {
            Map<Integer, Long> target = new TreeMap<>(Integer::compareUnsigned);
            request.getAllocatedMap().forEach((dimension, points) -> target.put(dimension, Integer.toUnsignedLong(points)));
            result = pets.allocate(call.player(), request.getPetId(), target);
        }
        AllocatePetPointsResponse.Builder response = AllocatePetPointsResponse.newBuilder().setErrorMessage(tip(result));
        if (result == 0) {
            response.setPets(pets.buildList(call.player()));
        }
        call.reply(response.build());
    }

    /**
     * 主人等级变化后的连带（GM 175 成功、推 170 之后）：全部宝宝按「升级」重算并推 184（没有宝宝也推空列表，同基线）。
     */
    public void onOwnerLevelChanged(PlayerCall call) {
        pets.onOwnerLevelChanged(call.player());
        call.push(notifyPetListChanged, PetListChangedS2C.newBuilder().setPets(pets.buildList(call.player())).build());
    }
}
