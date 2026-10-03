package com.game.scene.mission;

import com.game.table.ActivityScheduleTable;
import com.game.table.ConditionTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.MissionTable;
import com.game.table.RewardTable;
import com.game.table.Rewardreward;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务测试的配表：正式表（与 mmorpg 逐字节一致）加一个编辑器——同基线测试临时改单例配表的做法，在正式表的基础上换行、删行。
 */
final class MissionFixtures {

    static final ConfigTables SHIPPED = load();

    private MissionFixtures() {
    }

    private static ConfigTables load() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        return ConfigTables.load(dir);
    }

    static MissionTables shippedTables() {
        return MissionTables.from(SHIPPED);
    }

    static Editor edit() {
        return new Editor();
    }

    /** 正式表里的任务行，可以改了再放回编辑器。 */
    static MissionTable.Builder mission(int id) {
        return SHIPPED.mission().get(id).toBuilder();
    }

    static ConditionTable.Builder condition(int id) {
        return SHIPPED.condition().get(id).toBuilder();
    }

    /** 一个全新的任务行（类型 / 子类型 / 条件 / 奖励自己填）。 */
    static MissionTable.Builder newMission(int id, int type, int subType, int rewardId, int... conditionIds) {
        MissionTable.Builder row = MissionTable.newBuilder().setId(id).setMissionType(type).setMissionSubType(subType)
                .setRewardId(rewardId);
        for (int condition : conditionIds) {
            row.addConditionId(condition);
        }
        return row;
    }

    static ConditionTable.Builder newCondition(int id, int category, int target, int... condition1) {
        ConditionTable.Builder row = ConditionTable.newBuilder().setId(id).setConditionCategory(category)
                .setTargetCount(target);
        for (int value : condition1) {
            row.addCondition1(value);
        }
        return row;
    }

    /** 奖励行：参数成对给（物品, 数量）。 */
    static RewardTable reward(int id, int... itemCountPairs) {
        RewardTable.Builder row = RewardTable.newBuilder().setId(id);
        for (int i = 0; i < itemCountPairs.length; i += 2) {
            row.addReward(Rewardreward.newBuilder().setRewardItem(itemCountPairs[i]).setRewardCount(itemCountPairs[i + 1]));
        }
        return row.build();
    }

    static ActivityScheduleTable schedule(int id, boolean enabled, long start, long end) {
        return ActivityScheduleTable.newBuilder().setId(id).setEnabled(enabled).setBaselineStartAtMs(start)
                .setBaselineEndAtMs(end).build();
    }

    static final class Editor {
        private final Map<Integer, MissionTable> missions = new LinkedHashMap<>();
        private final Map<Integer, ConditionTable> conditions = new LinkedHashMap<>();
        private final Map<Integer, RewardTable> rewards = new LinkedHashMap<>();
        private final Map<Integer, ActivityScheduleTable> schedules = new LinkedHashMap<>();
        private final List<DungeonTable> dungeons = new ArrayList<>(SHIPPED.dungeon().all());
        private final Set<Integer> monsters = new HashSet<>();
        private final Set<Integer> items = new HashSet<>();

        Editor() {
            SHIPPED.mission().all().forEach(row -> missions.put(row.getId(), row));
            SHIPPED.condition().all().forEach(row -> conditions.put(row.getId(), row));
            SHIPPED.reward().all().forEach(row -> rewards.put(row.getId(), row));
            SHIPPED.activitySchedule().all().forEach(row -> schedules.put(row.getId(), row));
            SHIPPED.monster().all().forEach(row -> monsters.add(row.getId()));
            SHIPPED.item().all().forEach(row -> items.add(row.getId()));
        }

        Editor mission(MissionTable.Builder row) {
            missions.put(row.getId(), row.build());
            return this;
        }

        Editor removeMission(int id) {
            missions.remove(id);
            return this;
        }

        Editor condition(ConditionTable.Builder row) {
            conditions.put(row.getId(), row.build());
            return this;
        }

        Editor removeCondition(int id) {
            conditions.remove(id);
            return this;
        }

        Editor reward(RewardTable row) {
            rewards.put(row.getId(), row);
            return this;
        }

        Editor removeReward(int id) {
            rewards.remove(id);
            return this;
        }

        Editor schedule(ActivityScheduleTable row) {
            schedules.put(row.getId(), row);
            return this;
        }

        /** 把活动任务的排期改成一直开放（规则层测试绕过窗口）。 */
        Editor openSchedule(int id) {
            return schedule(MissionFixtures.schedule(id, true, 1, Long.MAX_VALUE));
        }

        Editor removeSchedule(int id) {
            schedules.remove(id);
            return this;
        }

        /** 加一个副本让怪 3、4、5 也能打到（规则层测试绕过可达闸，同基线测试直接改表）。 */
        Editor reachAllMonsters() {
            dungeons.add(DungeonTable.newBuilder().setId(900).addMonster(3).addMonster(4).addMonster(5).build());
            return this;
        }

        Editor noDungeons() {
            dungeons.clear();
            return this;
        }

        Editor removeMonster(int id) {
            monsters.remove(id);
            return this;
        }

        Editor noMonsters() {
            monsters.clear();
            return this;
        }

        Editor removeItem(int id) {
            items.remove(id);
            return this;
        }

        MissionTables build() {
            return new MissionTables(List.copyOf(missions.values()), List.copyOf(conditions.values()),
                    List.copyOf(rewards.values()), List.copyOf(schedules.values()),
                    MissionTables.reachableMonsters(dungeons, monsters::contains),
                    !dungeons.isEmpty() && !monsters.isEmpty(), items::contains);
        }
    }
}
