package com.game.scene.pet;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.proto.AllocatePetPointsRequest;
import com.game.proto.AllocatePetPointsResponse;
import com.game.proto.AscendPetTierRequest;
import com.game.proto.AutoAllocatePetPointsRequest;
import com.game.proto.AutoAllocatePetPointsResponse;
import com.game.proto.GetPetListRequest;
import com.game.proto.GetPetEntriesRequest;
import com.game.proto.PreviewPetEntriesRequest;
import com.game.proto.SavePetEntriesRequest;
import com.game.proto.SwitchPetEntryPresetRequest;
import com.game.proto.GetPetShopRequest;
import com.game.proto.BuyPetRequest;
import com.game.proto.BuyPetResponse;
import com.game.scene.player.Wallet;
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
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.scene.player.SceneTextGuard;
import com.game.scene.world.BattlePolicy;
import com.game.scene.world.DeferredReply;
import com.game.scene.world.FreezePolicy;
import com.game.scene.world.PlayerCall;
import com.game.scene.world.SceneFeature;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 宝宝的客户端方法（{@code ScenePetClientPlayer}：181 列表、183 出战、185 收回、186 加点、182 洗点、188 自动加点、189 改名、
 * 187 GM 发放；184 列表变化推送）。规则在 {@link PetService}。成功回 {@code error_message{0}} + 全量列表（188 回建议、187 另带新宝宝号），
 * 失败只回 tip。187 是 GM 方法，运行模式闸在 gate 与 scene 分发入口各一道（architecture §4.4）。
 */
public final class PetFeature implements SceneFeature {

    private static final Logger log = LoggerFactory.getLogger(PetFeature.class);
    private static final String SERVICE = "ScenePetClientPlayer";

    private final PetService pets;
    private final int notifyPetListChanged;
    private final SceneTextGuard textGuard;
    private final Executor ioExecutor;
    private final Executor logicExecutor;
    /** 仅场景逻辑线程读写：按玩家去重在途宝宝改名内容审核，防止单玩家并发刷满审核线程池。 */
    private final Set<Long> renameInFlight = new HashSet<>();

    public PetFeature(PetService pets, MessageIdRegistry registry) {
        this(pets, registry, null, null, null);
    }

    public PetFeature(PetService pets, MessageIdRegistry registry, SceneTextGuard textGuard,
                      Executor ioExecutor, Executor logicExecutor) {
        this.pets = pets;
        this.notifyPetListChanged = registry.requireId(SERVICE, "NotifyPetListChanged");
        this.textGuard = textGuard;
        this.ioExecutor = ioExecutor;
        this.logicExecutor = logicExecutor;
    }

    /**
     * 冻结策略（scene-handoff-spec §5.9）：181 只读；其余 GATED，由 {@link PetService} 的写前置在冻结中回 1005
     * （188 自动加点只算不落、不过写前置，同基线）。
     */
    @Override
    public void register(Registrar r) {
        r.on(SERVICE, "GetPetEntries", GetPetEntriesRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(pets.entries(call.player(), request.getPetId())));
        r.on(SERVICE, "PreviewPetEntries", PreviewPetEntriesRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(pets.previewEntries(call.player(), request)));
        r.on(SERVICE, "SavePetEntries", SavePetEntriesRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var response = pets.saveEntries(call.player(), request);
            if (response.getErrorMessage().getId() == 0 && !response.getReplayed()) call.world().requestSave(call.player());
            call.reply(response);
        });
        r.on(SERVICE, "SwitchPetEntryPreset", SwitchPetEntryPresetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var response = pets.switchEntryPreset(call.player(), request);
            if (response.getErrorMessage().getId() == 0 && !response.getReplayed()) call.world().requestSave(call.player());
            call.reply(response);
        });
        r.on(SERVICE, "AscendPetTier", AscendPetTierRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            var response = pets.ascendTier(call.player(), request);
            if (response.getErrorMessage().getId() == 0 && !response.getReplayed()) call.world().requestSave(call.player());
            call.reply(response);
        });
        r.on(SERVICE, "GetPetList", GetPetListRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW, (call, request) -> call.reply(
                GetPetListResponse.newBuilder().setErrorMessage(tip(0)).setPets(pets.buildList(call.player())).build()));
        r.on(SERVICE, "GetPetShop", GetPetShopRequest.class, FreezePolicy.READ_ONLY, BattlePolicy.ALLOW,
                (call, request) -> call.reply(pets.shop(call.player())));
        r.on(SERVICE, "BuyPet", BuyPetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            PetService.Grant grant = pets.buy(call.player(), request.getPetTableId(), request.getPurchaseSequence(), request.getPriceGold());
            var response = BuyPetResponse.newBuilder().setErrorMessage(tip(grant.tip()))
                    .setGold(call.player().wallet().balance(Wallet.GOLD))
                    .setNextPurchaseSequence(pets.nextPurchaseSequence(call.player()));
            if (grant.tip() == 0) {
                response.setPetId(grant.petId()).setPets(pets.buildList(call.player()));
                call.world().requestSave(call.player());
            }
            call.reply(response.build());
        });
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
        r.on(SERVICE, "RenamePet", RenamePetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, this::renamePet);
        r.on(SERVICE, "GmGrantPet", GmGrantPetRequest.class, FreezePolicy.GATED, BattlePolicy.GATED, (call, request) -> {
            PetService.Grant grant = pets.grant(call.player(), request.getPetTableId());
            GmGrantPetResponse.Builder response = GmGrantPetResponse.newBuilder().setErrorMessage(tip(grant.tip()));
            if (grant.tip() == 0) {
                response.setPetId(grant.petId()).setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
        });
    }

    private void renamePet(PlayerCall call, RenamePetRequest request) {
        if (textGuard == null || ioExecutor == null || logicExecutor == null) {
            int result = pets.rename(call.player(), request.getPetId(), request.getName());
            RenamePetResponse.Builder response = RenamePetResponse.newBuilder().setErrorMessage(tip(result));
            if (result == 0) {
                response.setPets(pets.buildList(call.player()));
            }
            call.reply(response.build());
            return;
        }
        ScenePlayer player = call.player();
        int pre = pets.checkRename(player, request.getPetId(), request.getName());
        if (pre != 0) {
            call.reply(RenamePetResponse.newBuilder().setErrorMessage(tip(pre)).build());
            return;
        }
        long playerId = player.playerId();
        if (!renameInFlight.add(playerId)) {
            call.reply(RenamePetResponse.newBuilder().setErrorMessage(tip(PetService.SERVICE_UNAVAILABLE)).build());
            return;
        }
        DeferredReply deferred = call.defer();
        SceneWorld world = call.world();
        long ownerEpoch = player.ownerEpoch();
        long petId = request.getPetId();
        String name = request.getName();
        try {
            ioExecutor.execute(() -> {
                CheckTextResponse resp = null;
                try {
                    resp = textGuard.check(TextScene.TEXT_SCENE_PET_NAME, playerId, name);
                } catch (RuntimeException e) {
                    log.warn("Pet rename content guard failed player={}: {}",
                            Long.toUnsignedString(playerId), e.getMessage());
                }
                CheckTextResponse finalResp = resp;
                try {
                    logicExecutor.execute(() -> {
                        try {
                            ScenePlayer current = world.playerById(playerId);
                            if (current == null || current != player || current.ownerEpoch() != ownerEpoch) {
                                deferred.cancel();
                                return;
                            }
                            if (finalResp == null) {
                                deferred.reply(RenamePetResponse.newBuilder().setErrorMessage(tip(PetService.SERVICE_UNAVAILABLE)).build());
                                return;
                            }
                            if (finalResp.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
                                deferred.reply(RenamePetResponse.newBuilder().setErrorMessage(tip(PetService.NAME_SENSITIVE)).build());
                                return;
                            }
                            int result = pets.rename(current, petId, name);
                            RenamePetResponse.Builder response = RenamePetResponse.newBuilder().setErrorMessage(tip(result));
                            if (result == 0) {
                                response.setPets(pets.buildList(current));
                            }
                            deferred.reply(response.build());
                        } finally {
                            renameInFlight.remove(playerId);
                        }
                    });
                } catch (RejectedExecutionException stopped) {
                    log.debug("Pet rename callback dropped because scene logic loop stopped player={}",
                            Long.toUnsignedString(playerId));
                }
            });
        } catch (RejectedExecutionException full) {
            renameInFlight.remove(playerId);
            log.warn("Pet rename content guard executor full player={}", Long.toUnsignedString(playerId));
            deferred.reply(RenamePetResponse.newBuilder().setErrorMessage(tip(PetService.SERVICE_UNAVAILABLE)).build());
        }
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
