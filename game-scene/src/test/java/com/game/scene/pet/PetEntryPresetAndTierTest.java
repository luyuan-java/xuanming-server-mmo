package com.game.scene.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetEntryPresetState;
import com.game.player.store.state.PetState;
import com.game.proto.AscendPetTierRequest;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.SavePetEntriesRequest;
import com.game.proto.SwitchPetEntryPresetRequest;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.PetErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 宝宝 3 套条目方案切换（500）、五阶灵阶突破（501）与备战备用宝宝快照闭环。 */
class PetEntryPresetAndTierTest {

    @Test
    void 三套条目方案切换保留各自槽位_重放幂等且跨请求复用键拒绝() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);
        long petId = f.pets.grant(player, 1).petId();
        int configVersion = BattleFixture.CONFIG.petRule().all().getFirst().getEntryConfigVersion();

        // 在方案 1 保存 503=6 点。
        var saved1 = f.pets.saveEntries(player, SavePetEntriesRequest.newBuilder()
                .setPetId(petId).setStateVersion(1).setConfigVersion(configVersion)
                .putTarget(503, 6).setRequestId("preset-save-slot1-0001").build());
        assertThat(saved1.getErrorMessage().getId()).isZero();
        assertThat(saved1.getEntries().getActivePresetSlot()).isEqualTo(1);
        assertThat(saved1.getEntries().getPresetsList()).hasSize(3);
        assertThat(saved1.getEntries().getPresets(0).getUsedPoints()).isEqualTo(6);

        // 非法槽位与标签回 kPetEntryPresetInvalid。
        var badSlot = f.pets.switchEntryPreset(player, SwitchPetEntryPresetRequest.newBuilder()
                .setPetId(petId).setSlotId(4).setTag(1)
                .setStateVersion(saved1.getEntries().getStateVersion()).setConfigVersion(configVersion)
                .setRequestId("preset-switch-bad-0001").build());
        assertThat(badSlot.getErrorMessage().getId()).isEqualTo(PetErrorTip.pet_error.kPetEntryPresetInvalid_VALUE);

        // 切换至方案 2 并设置标签为「控场(2)」。
        var sw2Req = SwitchPetEntryPresetRequest.newBuilder()
                .setPetId(petId).setSlotId(2).setTag(2)
                .setStateVersion(saved1.getEntries().getStateVersion()).setConfigVersion(configVersion)
                .setRequestId("preset-switch-slot2-0001").build();
        var sw2 = f.pets.switchEntryPreset(player, sw2Req);
        assertThat(sw2.getErrorMessage().getId()).isZero();
        assertThat(sw2.getReplayed()).isFalse();
        assertThat(sw2.getEntries().getActivePresetSlot()).isEqualTo(2);
        assertThat(sw2.getEntries().getRemainingPoints()).isEqualTo(20);
        assertThat(sw2.getEntries().getPresets(1).getTag()).isEqualTo(2);

        // 同 request_id 原样重放幂等。
        var sw2Replay = f.pets.switchEntryPreset(player, sw2Req);
        assertThat(sw2Replay.getErrorMessage().getId()).isZero();
        assertThat(sw2Replay.getReplayed()).isTrue();
        assertThat(sw2Replay.getEntries().getStateVersion()).isEqualTo(sw2.getEntries().getStateVersion());

        // 同 request_id 拿去调 SavePetEntries（不同操作）必须拒绝。
        var crossReuse = f.pets.saveEntries(player, SavePetEntriesRequest.newBuilder()
                .setPetId(petId).setStateVersion(saved1.getEntries().getStateVersion())
                .setConfigVersion(configVersion).setRequestId("preset-switch-slot2-0001").build());
        assertThat(crossReuse.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 在方案 2 保存 501=4 点，再切回方案 1（保留原标签 -1）验证 503=6 完整保留。
        var saved2 = f.pets.saveEntries(player, SavePetEntriesRequest.newBuilder()
                .setPetId(petId).setStateVersion(sw2.getEntries().getStateVersion()).setConfigVersion(configVersion)
                .putTarget(501, 4).setRequestId("preset-save-slot2-0001").build());
        assertThat(saved2.getErrorMessage().getId()).isZero();

        var sw1 = f.pets.switchEntryPreset(player, SwitchPetEntryPresetRequest.newBuilder()
                .setPetId(petId).setSlotId(1).setTag(-1)
                .setStateVersion(saved2.getEntries().getStateVersion()).setConfigVersion(configVersion)
                .setRequestId("preset-switch-slot1-0001").build());
        assertThat(sw1.getErrorMessage().getId()).isZero();
        assertThat(sw1.getEntries().getActivePresetSlot()).isEqualTo(1);
        assertThat(sw1.getEntries().getRemainingPoints()).isEqualTo(14);
        assertThat(player.pets().find(petId).entryAllocated()).containsEntry(503, 6);

        // 存档往返后 3 套方案与重放元数据保持一致。
        PlayerPets restored = PlayerPets.restore(player.pets().toState());
        assertThat(restored.find(petId).activeEntryPresetSlot()).isEqualTo(1);
        assertThat(restored.find(petId).entryPreset(2).getTag()).isEqualTo(2);
        assertThat(restored.find(petId).entryPreset(2).getAllocatedMap()).containsEntry(501, 4);
    }

    @Test
    void 五阶灵阶突破原子扣材料和银两_扩展条目预算且重放幂等() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);
        var tier1 = BattleFixture.CONFIG.petTier().all().stream().filter(t -> t.getId() == 1).findFirst().orElseThrow();
        int minLevel = Math.max(60, tier1.getAscendMinPetLevel());
        assertThat(f.attributes.gmSetLevel(player, minLevel)).isZero();
        long petId = f.pets.grant(player, 1).petId();
        assertThat(f.currency.add(player, Wallet.GOLD, 1_000, Reason.GM_GRANT).ok()).isTrue();

        // 缺材料时预览提示 kPetTierMaterialNotEnough，发起突破拒绝且不扣银两。
        var previewBefore = f.pets.buildList(player).getPets(0).getNextTier();
        assertThat(previewBefore.getNextTier()).isEqualTo(2);
        assertThat(previewBefore.getCanAscend()).isFalse();
        assertThat(previewBefore.getBlockTipId()).isEqualTo(PetErrorTip.pet_error.kPetTierMaterialNotEnough_VALUE);

        var req = AscendPetTierRequest.newBuilder()
                .setPetId(petId).setExpectedTier(1).setClientRequestId(88001L).build();
        var lackMat = f.pets.ascendTier(player, req);
        assertThat(lackMat.getErrorMessage().getId()).isEqualTo(PetErrorTip.pet_error.kPetTierMaterialNotEnough_VALUE);
        assertThat(f.gold(player)).isEqualTo(1_000);

        // 补齐材料后预览可执行，突破成功：扣材料+银两，灵阶 1 -> 2，条目预算 20 -> 22。
        assertThat(f.bags.addItems(player, BagType.INVENTORY,
                java.util.Map.of(tier1.getAscendCostItemId(), Integer.toUnsignedLong(tier1.getAscendCostItemCount())),
                Reason.GM_GRANT, 0, "").tip()).isZero();
        assertThat(f.pets.buildList(player).getPets(0).getNextTier().getCanAscend()).isTrue();

        var ok = f.pets.ascendTier(player, req);
        assertThat(ok.getErrorMessage().getId()).isZero();
        assertThat(ok.getReplayed()).isFalse();
        assertThat(ok.getNewTier()).isEqualTo(2);
        assertThat(player.pets().find(petId).cultivationTier()).isEqualTo(2);
        assertThat(f.gold(player)).isEqualTo(1_000 - tier1.getAscendCostGold());
        assertThat(player.bags().bag(BagType.INVENTORY).total(tier1.getAscendCostItemId())).isZero();
        assertThat(f.pets.entries(player, petId).getEntries().getTotalPoints()).isEqualTo(22);

        // 同 client_request_id 幂等重放不重复扣费。
        var replay = f.pets.ascendTier(player, req);
        assertThat(replay.getErrorMessage().getId()).isZero();
        assertThat(replay.getReplayed()).isTrue();
        assertThat(replay.getNewTier()).isEqualTo(2);
        assertThat(f.gold(player)).isEqualTo(1_000 - tier1.getAscendCostGold());

        // 同 client_request_id 不同 expected_tier 拒绝。
        var mismatchReplay = f.pets.ascendTier(player, req.toBuilder().setExpectedTier(2).build());
        assertThat(mismatchReplay.getErrorMessage().getId()).isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);

        // 过期 expected_tier 拒绝；满阶 5 拒绝且无 next_tier。
        var staleTier = f.pets.ascendTier(player, AscendPetTierRequest.newBuilder()
                .setPetId(petId).setExpectedTier(1).setClientRequestId(88002L).build());
        assertThat(staleTier.getErrorMessage().getId()).isEqualTo(PetErrorTip.pet_error.kPetTierChanged_VALUE);

        player.pets().find(petId).setCultivationTier(5);
        assertThat(f.pets.buildList(player).getPets(0).hasNextTier()).isFalse();
        var maxReached = f.pets.ascendTier(player, AscendPetTierRequest.newBuilder()
                .setPetId(petId).setExpectedTier(5).setClientRequestId(88003L).build());
        assertThat(maxReached.getErrorMessage().getId()).isEqualTo(PetErrorTip.pet_error.kPetTierMaxReached_VALUE);
    }

    @Test
    void 备用宝宝快照排除首发并满状态生成_未知字段随预设往返保留() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);
        long pet1 = f.pets.grant(player, 1).petId();
        long pet2 = f.pets.grant(player, 1).petId();
        player.pets().find(pet2).setHealth(0); // 正式论剑备战 fullResources=true 允许满血满蓝出场
        List<BattlePetSnapshot> standby = f.pets.buildStandbyBattleSnapshots(player, pet1, 5, true);
        assertThat(standby).singleElement().satisfies(snap -> {
            assertThat(snap.getPetId()).isEqualTo(pet2);
            assertThat(snap.getBaseAttributes().getHealth()).isEqualTo(snap.getMaxHealth());
            assertThat(snap.getBaseAttributes().getMana()).isEqualTo(snap.getMaxMana());
        });

        UnknownFieldSet extra = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(777L).build()).build();
        PetState state = PetState.newBuilder()
                .addPets(PetEntry.newBuilder().setPetId(10L).setPetTableId(1).setLevel(1)
                        .addEntryPresets(PetEntryPresetState.newBuilder().setSlotId(1).setTag(2).setUnknownFields(extra)))
                .build();
        PetState roundTrip = PlayerPets.restore(state).toState();
        assertThat(roundTrip.getPets(0).getEntryPresets(0).getUnknownFields()).isEqualTo(extra);
    }
}
