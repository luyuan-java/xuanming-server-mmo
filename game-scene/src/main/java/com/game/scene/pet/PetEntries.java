package com.game.scene.pet;

import com.game.player.store.state.PetEntryPresetState;
import com.game.proto.*;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.world.SceneMessageIds;
import com.game.table.PetEntryTable;
import com.game.table.PetTable;
import com.game.table.PetTierTable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 宝宝独立条目预算、3 套预设方案、灵阶加点与乐观并发回执。只在 scene 逻辑线程执行。 */
final class PetEntries {
    static final int MAX_PRESET_SLOTS = 3;
    static final int MAX_PRESET_TAG = 3;
    static final int KEEP_PRESET_TAG = -1; // 0xFFFFFFFFu
    static final int MAX_CULTIVATION_TIER = 5;

    private PetEntries() { }

    static boolean enabled(PetTables tables) {
        if (tables.entryVersion() == 0 || tables.entryBudget() == 0 || tables.entries().isEmpty()) return false;
        for (var row : tables.entries()) {
            long cap = Integer.toUnsignedLong(row.getCap()), rate = Integer.toUnsignedLong(row.getPerPointBps());
            if (row.getId() == 0 || cap == 0 || rate == 0 || rate > 10000 || cap > 10000 / rate || row.getChannel() < 1 || row.getChannel() > 9 ||
                    ((row.getChannel() == 5 || row.getChannel() == 6) && (row.getStateKind() < 1 || row.getStateKind() > 7))) return false;
        }
        return true;
    }

    static boolean validRequestId(String requestId) {
        return requestId != null && requestId.matches("[a-zA-Z0-9_-]{16,64}");
    }

    static boolean validPresetSlot(int slotId) {
        long u = unsigned(slotId);
        return u >= 1 && u <= MAX_PRESET_SLOTS;
    }

    static boolean validPresetTag(int tag) {
        return tag == KEEP_PRESET_TAG || unsigned(tag) <= MAX_PRESET_TAG;
    }

    static int effectivePresetSlot(int stored) {
        return validPresetSlot(stored) ? stored : 1;
    }

    static int effectiveTier(PetTables tables, Pet pet) {
        int tier = pet.cultivationTier();
        if (tier == 0) {
            PetTable species = tables.pet(pet.petTableId());
            if (species != null) tier = species.getCultivationTier();
        }
        return tier == 0 ? 1 : tier;
    }

    static long effectiveBudget(PetTables tables, Pet pet) {
        PetTierTable tierRow = tables.tier(effectiveTier(tables, pet));
        long bonus = tierRow != null ? unsigned(tierRow.getBonusEntryPoints()) : 0;
        return tables.entryBudget() + bonus;
    }

    private static Set<Integer> known(PetTables t) { return t.entries().stream().map(PetEntryTable::getId).collect(Collectors.toSet()); }
    private static long unsigned(int value) { return Integer.toUnsignedLong(value); }

    static int validate(PetTables tables, Pet pet, Map<Integer,Integer> target) {
        Map<Integer,PetEntryTable> rows = tables.entries().stream().collect(Collectors.toMap(PetEntryTable::getId, r -> r));
        long used = pet.entryAllocated().entrySet().stream().filter(e -> !rows.containsKey(e.getKey())).mapToLong(e -> unsigned(e.getValue())).sum();
        long directions = 0;
        for (var entry : target.entrySet()) {
            var row = rows.get(entry.getKey()); if (row == null) return PetService.DIMENSION_NOT_FOUND;
            long value = unsigned(entry.getValue()); if (value > unsigned(row.getCap())) return PetService.DIMENSION_CAP_EXCEEDED;
            used += value; if (value > 0) directions++;
        }
        if (used > effectiveBudget(tables, pet)) return PetService.POINTS_NOT_ENOUGH;
        if (tables.entryDirections() > 0 && directions > tables.entryDirections()) return PetService.INVALID_PARAMETER;
        return PetService.OK;
    }

    private static Map<Integer,Integer> replace(PetTables tables, Pet pet, Map<Integer,Integer> target) {
        Set<Integer> known = known(tables); Map<Integer,Integer> result = new HashMap<>();
        pet.entryAllocated().forEach((id,value) -> { if (!known.contains(id)) result.put(id,value); });
        target.forEach((id,value) -> { if (value != 0) result.put(id,value); }); return result;
    }

    static PetEntriesInfo info(PetTables tables, Pet pet) { return info(tables, pet, pet.entryAllocated()); }

    private static PetEntriesInfo info(PetTables tables, Pet pet, Map<Integer,Integer> values) {
        if (!enabled(tables)) return PetEntriesInfo.getDefaultInstance();
        long budget = effectiveBudget(tables, pet);
        int activeSlot = effectivePresetSlot(pet.activeEntryPresetSlot());
        Set<Integer> known = known(tables); Map<Integer,Integer> knownValues = new HashMap<>();
        values.forEach((id,value) -> { if (known.contains(id)) knownValues.put(id,value); });
        boolean effective = validate(tables, pet, knownValues) == PetService.OK;
        long used = values.values().stream().mapToLong(PetEntries::unsigned).sum();
        long unknown = values.entrySet().stream().filter(e -> !known.contains(e.getKey())).mapToLong(e -> unsigned(e.getValue())).sum();
        var out = PetEntriesInfo.newBuilder().setPetId(pet.petId()).setStateVersion(pet.entryStateVersion())
                .setConfigVersion(tables.entryVersion()).setTotalPoints((int) budget)
                .setRemainingPoints(effective && used <= budget ? (int) (budget - used) : 0)
                .setMaxDirections((int) tables.entryDirections()).setUnknownUsedPoints(unknown)
                .setEffective(effective).setLastRequestId(pet.entryLastRequestId())
                .setActivePresetSlot(activeSlot);
        var effects = PetCultivationEffects.newBuilder();
        tables.entries().stream()
                .sorted(Comparator.comparingLong((PetEntryTable r) -> unsigned(r.getSort()))
                        .thenComparingLong(r -> unsigned(r.getId())))
                .forEach(row -> {
                    int points = values.getOrDefault(row.getId(), 0); int bps = effective ? points * row.getPerPointBps() : 0;
                    out.addEntries(PetEntryInfo.newBuilder().setId(row.getId()).setName(row.getName()).setDesc(row.getDesc())
                            .setCategory(row.getCategory()).setChannel(row.getChannel()).setStateKind(row.getStateKind()).setCap(row.getCap())
                            .setPerPointBps(row.getPerPointBps()).setAllocated(points).setEffectiveBps(bps).setSort(row.getSort()).setIcon(row.getIcon()));
                    switch (row.getChannel()) {
                        case 1 -> effects.setPhysicalDamageBps(effects.getPhysicalDamageBps() + bps);
                        case 2 -> effects.setMagicDamageBps(effects.getMagicDamageBps() + bps);
                        case 3 -> effects.setOutgoingHealBps(effects.getOutgoingHealBps() + bps);
                        case 4 -> effects.setShieldCapacityBps(effects.getShieldCapacityBps() + bps);
                        case 5 -> effects.putAilmentHitBps(row.getStateKind(), effects.getAilmentHitBpsOrDefault(row.getStateKind(), 0) + bps);
                        case 6 -> effects.putAilmentResistBps(row.getStateKind(), effects.getAilmentResistBpsOrDefault(row.getStateKind(), 0) + bps);
                        case 7 -> effects.setPoisonDamageBps(effects.getPoisonDamageBps() + bps);
                        case 8 -> effects.setRetreatHitBps(effects.getRetreatHitBps() + bps);
                        case 9 -> effects.setRetreatResistBps(effects.getRetreatResistBps() + bps);
                        default -> throw new IllegalStateException("invalid pet entry channel");
                    }
                });
        for (int slot = 1; slot <= MAX_PRESET_SLOTS; slot++) {
            PetEntryPresetState stored = pet.entryPreset(slot);
            PetEntryPresetSummary.Builder summary = PetEntryPresetSummary.newBuilder()
                    .setSlotId(slot)
                    .setTag(stored != null ? stored.getTag() : 0);
            long presetUsed = 0;
            Map<Integer, Integer> source = slot == activeSlot ? values : (stored != null ? stored.getAllocatedMap() : Map.of());
            for (var e : source.entrySet()) {
                long pts = unsigned(e.getValue());
                if (pts > 0) {
                    summary.putAllocated(e.getKey(), e.getValue());
                    presetUsed += pts;
                }
            }
            summary.setUsedPoints((int) Math.min(presetUsed, 0xFFFF_FFFFL));
            out.addPresets(summary);
        }
        return out.setEffects(effects).build();
    }

    static PreviewPetEntriesResponse preview(PetTables tables, Pet pet, PreviewPetEntriesRequest request) {
        int error = !enabled(tables) ? PetService.INVALID_TABLE_DATA :
                request.getStateVersion() != pet.entryStateVersion() || request.getConfigVersion() != tables.entryVersion() ? PetService.INVALID_PARAMETER :
                        validate(tables, pet, request.getTargetMap());
        var out = PreviewPetEntriesResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(error));
        if (enabled(tables)) {
            out.setEntries(error == 0 ? info(tables, pet, replace(tables, pet, request.getTargetMap())) : info(tables, pet));
        }
        return out.build();
    }

    static SavePetEntriesResponse save(PetTables tables, Pet pet, SavePetEntriesRequest request) {
        int error = 0; boolean replay = false;
        if (!validRequestId(request.getRequestId())) error = PetService.INVALID_PARAMETER;
        else if (pet.entryLastRequestId().equals(request.getRequestId())) {
            if (pet.entryLastPresetSlot() != 0 || pet.entryLastBaseVersion() != request.getStateVersion()
                    || pet.entryLastConfigVersion() != request.getConfigVersion()
                    || !pet.entryLastTarget().equals(request.getTargetMap())) error = PetService.INVALID_PARAMETER;
            else replay = true;
        } else if (!enabled(tables)) error = PetService.INVALID_TABLE_DATA;
        else if (request.getStateVersion() != pet.entryStateVersion() || request.getConfigVersion() != tables.entryVersion() || pet.entryStateVersion() == -1L) error = PetService.INVALID_PARAMETER;
        else error = validate(tables, pet, request.getTargetMap());
        if (error == 0 && !replay) {
            int activeSlot = effectivePresetSlot(pet.activeEntryPresetSlot());
            pet.commitEntriesWithPreset(activeSlot, replace(tables, pet, request.getTargetMap()),
                    pet.entryStateVersion() + 1, request.getRequestId(), request.getStateVersion(),
                    request.getConfigVersion(), request.getTargetMap());
        }
        var out = SavePetEntriesResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(error))
                .setRequestId(request.getRequestId()).setReplayed(replay);
        if (enabled(tables)) out.setEntries(info(tables, pet));
        return out.build();
    }

    static SwitchPetEntryPresetResponse switchPreset(PetTables tables, Pet pet, SwitchPetEntryPresetRequest request) {
        int error = 0; boolean replay = false;
        if (!validRequestId(request.getRequestId())) {
            error = PetService.INVALID_PARAMETER;
        } else if (!validPresetSlot(request.getSlotId()) || !validPresetTag(request.getTag())) {
            error = PetService.ENTRY_PRESET_INVALID;
        } else if (pet.entryLastRequestId().equals(request.getRequestId())) {
            boolean same = pet.entryLastPresetSlot() == request.getSlotId()
                    && pet.entryLastPresetTag() == request.getTag()
                    && pet.entryLastBaseVersion() == request.getStateVersion()
                    && pet.entryLastConfigVersion() == request.getConfigVersion();
            if (!same) error = PetService.INVALID_PARAMETER;
            else replay = true;
        } else if (!enabled(tables)) {
            error = PetService.INVALID_TABLE_DATA;
        } else if (request.getConfigVersion() != tables.entryVersion()
                || request.getStateVersion() != pet.entryStateVersion()
                || pet.entryStateVersion() == -1L) {
            error = PetService.INVALID_PARAMETER;
        }
        if (error == 0 && !replay) {
            int currentSlot = effectivePresetSlot(pet.activeEntryPresetSlot());
            pet.commitEntryPresetSwitch(currentSlot, request.getSlotId(), request.getTag(), KEEP_PRESET_TAG,
                    pet.entryStateVersion() + 1, request.getRequestId(), request.getStateVersion(),
                    request.getConfigVersion());
        }
        var out = SwitchPetEntryPresetResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(error))
                .setRequestId(request.getRequestId()).setReplayed(replay);
        if (enabled(tables)) out.setEntries(info(tables, pet));
        return out.build();
    }
}
