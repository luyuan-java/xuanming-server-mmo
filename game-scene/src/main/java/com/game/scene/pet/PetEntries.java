package com.game.scene.pet;

import com.game.proto.*;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.world.SceneMessageIds;
import com.game.table.PetEntryTable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 宝宝独立条目预算、权威预览和乐观并发回执。只在 scene 逻辑线程执行。 */
final class PetEntries {
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
    // 当前 Java 引擎尚未移植现代 Buff 执行器，只开放已实际消费的物/法直伤。
    static boolean available(PetEntryTable row) { return row.getChannel() == 1 || row.getChannel() == 2; }
    private static Set<Integer> known(PetTables t) { return t.entries().stream().map(PetEntryTable::getId).collect(Collectors.toSet()); }
    private static long unsigned(int value) { return Integer.toUnsignedLong(value); }
    static int validate(PetTables tables, Pet pet, Map<Integer,Integer> target) {
        Map<Integer,PetEntryTable> rows = tables.entries().stream().collect(Collectors.toMap(PetEntryTable::getId, r -> r));
        long used = pet.entryAllocated().entrySet().stream().filter(e -> !rows.containsKey(e.getKey())).mapToLong(e -> unsigned(e.getValue())).sum();
        long directions = 0;
        for (var entry : target.entrySet()) {
            var row = rows.get(entry.getKey()); if (row == null) return PetService.DIMENSION_NOT_FOUND;
            long value = unsigned(entry.getValue()); if ((!available(row) && value > 0) || value > unsigned(row.getCap())) return PetService.DIMENSION_CAP_EXCEEDED;
            used += value; if (value > 0) directions++;
        }
        if (used > tables.entryBudget()) return PetService.POINTS_NOT_ENOUGH;
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
        Set<Integer> known = known(tables); Map<Integer,Integer> knownValues = new HashMap<>();
        values.forEach((id,value) -> { if (known.contains(id)) knownValues.put(id,value); });
        boolean effective = validate(tables, pet, knownValues) == PetService.OK;
        long used = values.values().stream().mapToLong(PetEntries::unsigned).sum();
        long unknown = values.entrySet().stream().filter(e -> !known.contains(e.getKey())).mapToLong(e -> unsigned(e.getValue())).sum();
        var out = PetEntriesInfo.newBuilder().setPetId(pet.petId()).setStateVersion(pet.entryStateVersion())
                .setConfigVersion(tables.entryVersion()).setTotalPoints((int)tables.entryBudget())
                .setRemainingPoints(effective && used <= tables.entryBudget() ? (int)(tables.entryBudget()-used) : 0)
                .setMaxDirections((int)tables.entryDirections()).setUnknownUsedPoints(unknown).setEffective(effective).setLastRequestId(pet.entryLastRequestId());
        var effects = PetCultivationEffects.newBuilder();
        tables.entries().stream().sorted(Comparator.comparingLong(r -> unsigned(r.getSort()))).forEach(row -> {
            int points = values.getOrDefault(row.getId(),0); int bps = effective && available(row) ? points*row.getPerPointBps() : 0;
            out.addEntries(PetEntryInfo.newBuilder().setId(row.getId()).setName(row.getName()).setDesc(available(row) ? row.getDesc() : "当前 Java 战斗引擎尚未接入此方向，不能投入；已有点可移出。" + row.getDesc())
                    .setCategory(row.getCategory()).setChannel(row.getChannel()).setStateKind(row.getStateKind()).setCap(available(row) ? row.getCap() : 0)
                    .setPerPointBps(available(row) ? row.getPerPointBps() : 0).setAllocated(points).setEffectiveBps(bps).setSort(row.getSort()).setIcon(row.getIcon()));
            switch (row.getChannel()) {
                case 1 -> effects.setPhysicalDamageBps(effects.getPhysicalDamageBps()+bps);
                case 2 -> effects.setMagicDamageBps(effects.getMagicDamageBps()+bps);
                case 3 -> effects.setOutgoingHealBps(effects.getOutgoingHealBps()+bps);
                case 4 -> effects.setShieldCapacityBps(effects.getShieldCapacityBps()+bps);
                case 5 -> effects.putAilmentHitBps(row.getStateKind(), effects.getAilmentHitBpsOrDefault(row.getStateKind(),0)+bps);
                case 6 -> effects.putAilmentResistBps(row.getStateKind(), effects.getAilmentResistBpsOrDefault(row.getStateKind(),0)+bps);
                case 7 -> effects.setPoisonDamageBps(effects.getPoisonDamageBps()+bps);
                case 8 -> effects.setRetreatHitBps(effects.getRetreatHitBps()+bps);
                case 9 -> effects.setRetreatResistBps(effects.getRetreatResistBps()+bps);
                default -> throw new IllegalStateException("invalid pet entry channel");
            }
        });
        return out.setEffects(effects).build();
    }
    static PreviewPetEntriesResponse preview(PetTables tables, Pet pet, PreviewPetEntriesRequest request) {
        int error = !enabled(tables) ? PetService.INVALID_TABLE_DATA :
                request.getStateVersion() != pet.entryStateVersion() || request.getConfigVersion() != tables.entryVersion() ? PetService.INVALID_PARAMETER :
                        validate(tables,pet,request.getTargetMap());
        return PreviewPetEntriesResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(error))
                .setEntries(error == 0 ? info(tables,pet,replace(tables,pet,request.getTargetMap())) : info(tables,pet)).build();
    }
    static SavePetEntriesResponse save(PetTables tables, Pet pet, SavePetEntriesRequest request) {
        int error = 0; boolean replay = false;
        if (!request.getRequestId().matches("[a-zA-Z0-9_-]{16,64}")) error = PetService.INVALID_PARAMETER;
        else if (pet.entryLastRequestId().equals(request.getRequestId())) {
            if (pet.entryLastBaseVersion()!=request.getStateVersion() || pet.entryLastConfigVersion()!=request.getConfigVersion() || !pet.entryLastTarget().equals(request.getTargetMap())) error=PetService.INVALID_PARAMETER;
            else replay=true;
        } else if (!enabled(tables)) error=PetService.INVALID_TABLE_DATA;
        else if (request.getStateVersion()!=pet.entryStateVersion() || request.getConfigVersion()!=tables.entryVersion() || pet.entryStateVersion()==-1L) error=PetService.INVALID_PARAMETER;
        else error=validate(tables,pet,request.getTargetMap());
        if (error==0 && !replay) pet.commitEntries(replace(tables,pet,request.getTargetMap()),pet.entryStateVersion()+1,
                request.getRequestId(),request.getStateVersion(),request.getConfigVersion(),request.getTargetMap());
        return SavePetEntriesResponse.newBuilder().setErrorMessage(SceneMessageIds.tip(error)).setRequestId(request.getRequestId())
                .setReplayed(replay).setEntries(info(tables,pet)).build();
    }
}
