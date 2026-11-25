package com.game.scene.festivalbeast;

import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.FestivalBeastActivityTable;
import com.game.table.FestivalBeastTierTable;
import com.game.table.PetTable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 瑞兽迎春活动用到的不可变配表视图（FestivalBeastActivity / FestivalBeastTier / Dungeon / Pet）。
 */
public final class FestivalBeastTables {

    private final List<FestivalBeastActivityTable> activities;
    private final Map<Integer, FestivalBeastActivityTable> activityById;
    private final List<FestivalBeastTierTable> tiers;
    private final Map<Integer, DungeonTable> dungeons;
    private final Map<Integer, PetTable> pets;

    public FestivalBeastTables(List<FestivalBeastActivityTable> activities,
                               List<FestivalBeastTierTable> tiers,
                               Map<Integer, DungeonTable> dungeons,
                               Map<Integer, PetTable> pets) {
        this.activities = activities.stream()
                .sorted(Comparator.comparingLong(a -> Integer.toUnsignedLong(a.getId())))
                .toList();
        Map<Integer, FestivalBeastActivityTable> byId = new HashMap<>();
        for (FestivalBeastActivityTable row : this.activities) {
            byId.put(row.getId(), row);
        }
        this.activityById = Map.copyOf(byId);
        this.tiers = tiers.stream()
                .sorted(Comparator.comparingLong((FestivalBeastTierTable t) -> Integer.toUnsignedLong(t.getMinLevel()))
                        .thenComparingLong(t -> Integer.toUnsignedLong(t.getId())))
                .toList();
        this.dungeons = Map.copyOf(dungeons);
        this.pets = Map.copyOf(pets);
    }

    public static FestivalBeastTables from(ConfigTables tables) {
        Map<Integer, DungeonTable> dungeons = new HashMap<>();
        for (DungeonTable row : tables.dungeon().all()) {
            dungeons.put(row.getId(), row);
        }
        Map<Integer, PetTable> pets = new HashMap<>();
        for (PetTable row : tables.pet().all()) {
            pets.put(row.getId(), row);
        }
        return new FestivalBeastTables(
                List.copyOf(tables.festivalBeastActivity().all()),
                List.copyOf(tables.festivalBeastTier().all()),
                dungeons,
                pets);
    }

    public List<FestivalBeastActivityTable> activities() {
        return activities;
    }

    public FestivalBeastActivityTable activity(int id) {
        return activityById.get(id);
    }

    public List<FestivalBeastTierTable> tiers() {
        return tiers;
    }

    public DungeonTable dungeon(int id) {
        return dungeons.get(id);
    }

    public PetTable pet(int id) {
        return pets.get(id);
    }
}
