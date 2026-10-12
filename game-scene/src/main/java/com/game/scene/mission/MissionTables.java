package com.game.scene.mission;

import com.game.table.ActivityScheduleTable;
import com.game.table.CommonErrorTip;
import com.game.table.ConditionTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.MissionTable;
import com.game.table.Rewardreward;
import com.game.table.RewardTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务用到的配表视图（Mission / Condition / Reward / ActivitySchedule，外加 Dungeon × Monster 推出的可达怪物、Item 的存在性）。
 * 不可变，加载后任意线程可读。表快照不变，所以基线每次接取都要重算的静态闸（条件来源、可达怪物、奖励合法性）在这里一次算好；
 * 只有依赖玩家状态的判定（已接 / 已完成 / 类型占用）留给任务服务，错误码的先后与基线 CheckAccept 一致。
 *
 * <p>表是从 mmorpg 同步来的契约：加载时只对用不上的数据告警（悬空的后续任务、奖励里不存在的物品、挂在非活动任务上的排期、
 * 非法排期窗口），不拒绝启动；运行时照基线回 1002 / 1003。
 */
public final class MissionTables {

    private static final Logger log = LoggerFactory.getLogger(MissionTables.class);

    static final int OK = 0;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final long MAX_U32 = 0xFFFF_FFFFL;

    /** 条件类别：击杀（ids = [怪物配置号]）、等级（ids = [等级]，量 = 等级）、完成任务（ids = [任务号]）。只有这三类有事实来源。 */
    static final int CATEGORY_KILL = 1;
    static final int CATEGORY_LEVEL = 6;
    static final int CATEGORY_COMPLETE_MISSION = 8;
    /** 任务类型：活动任务（受排期窗口约束）。 */
    static final int TYPE_ACTIVITY = 2;

    /** Condition 行。 */
    public record ConditionDef(int id, int category, List<Integer> condition1, List<Integer> condition2,
                               List<Integer> condition3, List<Integer> condition4, long validDuration, int quantityType,
                               int targetCount, int comparisonOp) {

        static ConditionDef of(ConditionTable row) {
            return new ConditionDef(row.getId(), row.getConditionCategory(), List.copyOf(row.getCondition1List()),
                    List.copyOf(row.getCondition2List()), List.copyOf(row.getCondition3List()),
                    List.copyOf(row.getCondition4List()), row.getValidDuration(), row.getQuantityType(),
                    row.getTargetCount(), row.getComparisonOp());
        }

        /** 第 k 个（1–4）条件列表。 */
        List<Integer> slot(int k) {
            return switch (k) {
                case 1 -> condition1;
                case 2 -> condition2;
                case 3 -> condition3;
                default -> condition4;
            };
        }
    }

    /**
     * 任务的一个条件格。
     *
     * @param condition      表里没有为 null
     * @param targetOverride Mission.target_count[i]（&gt; 0 时覆盖条件的目标；0 = 用条件的）
     */
    public record Slot(int index, int conditionId, ConditionDef condition, int targetOverride) {
    }

    /**
     * 任务行与它的静态闸。
     *
     * @param earlyTip 查表后的第一道静态闸：没有条件或 condition_order &gt; 1 → 1002
     * @param lateTip  逐格静态闸（条件缺失 / 比较符 / 来源 / 可达怪物 / 计数方式……），在玩家状态闸之后判
     * @param schedule 活动排期（表里没有为 null）
     */
    public record MissionDef(int id, int type, int subType, int conditionOrder, boolean autoReward, int rewardId,
                             List<Slot> slots, List<Integer> nextMissionIds, int earlyTip, int lateTip,
                             ActivityScheduleTable schedule) {

        boolean ordered() {
            return conditionOrder == 1;
        }

        boolean activity() {
            return type == TYPE_ACTIVITY;
        }

        /** 关注的条件类别（表里有的条件）。 */
        Set<Integer> categories() {
            Set<Integer> out = new LinkedHashSet<>();
            for (Slot slot : slots) {
                if (slot.condition() != null) {
                    out.add(slot.condition().category());
                }
            }
            return out;
        }
    }

    /** 一个奖励的合并结果：{@code tip} 为 0 时 {@code items} 是物品配置号 → 数量（按配置号升序）。 */
    public record RewardDef(int tip, Map<Integer, Long> items) {
    }

    private final List<MissionDef> missions;
    private final Map<Integer, MissionDef> byId;
    private final Map<Integer, RewardDef> rewards;

    MissionTables(List<MissionTable> missionRows, List<ConditionTable> conditionRows, List<RewardTable> rewardRows,
                  List<ActivityScheduleTable> scheduleRows, Set<Integer> reachableMonsters, boolean monsterTablesUsable,
                  IntPredicate itemExists) {
        Map<Integer, ConditionDef> conditions = new HashMap<>();
        for (ConditionTable row : conditionRows) {
            conditions.put(row.getId(), ConditionDef.of(row));
        }
        Map<Integer, ActivityScheduleTable> schedules = new HashMap<>();
        for (ActivityScheduleTable row : scheduleRows) {
            schedules.put(row.getId(), row);
        }
        this.rewards = buildRewards(rewardRows, itemExists);
        List<MissionDef> list = new ArrayList<>(missionRows.size());
        Map<Integer, MissionDef> index = new HashMap<>();
        for (MissionTable row : missionRows) {
            List<Slot> slots = new ArrayList<>(row.getConditionIdCount());
            for (int i = 0; i < row.getConditionIdCount(); i++) {
                int override = i < row.getTargetCountCount() ? row.getTargetCount(i) : 0;
                slots.add(new Slot(i, row.getConditionId(i), conditions.get(row.getConditionId(i)), override));
            }
            int early = slots.isEmpty() || Integer.compareUnsigned(row.getConditionOrder(), 1) > 0 ? INVALID_TABLE_DATA : OK;
            int late = lateTip(slots, reachableMonsters, monsterTablesUsable);
            MissionDef def = new MissionDef(row.getId(), row.getMissionType(), row.getMissionSubType(),
                    row.getConditionOrder(), row.getAutoReward() != 0, row.getRewardId(), List.copyOf(slots),
                    List.copyOf(row.getNextMissionIdList()), early, late, schedules.get(row.getId()));
            list.add(def);
            index.put(def.id(), def);
        }
        this.missions = List.copyOf(list);
        this.byId = Map.copyOf(index);
        warnUnusable(scheduleRows, itemExists);
    }

    public static MissionTables from(ConfigTables tables) {
        boolean usable = tables.dungeon().size() > 0 && tables.monster().size() > 0;
        return new MissionTables(tables.mission().all(), tables.condition().all(), tables.reward().all(),
                tables.activitySchedule().all(), reachableMonsters(tables.dungeon().all(), tables.monster()::contains),
                usable, tables.item()::contains);
    }

    /** 能打到的怪：副本里出现、且怪物表里有（0 跳过）。 */
    static Set<Integer> reachableMonsters(List<DungeonTable> dungeons, IntPredicate monsterExists) {
        Set<Integer> reachable = new HashSet<>();
        for (DungeonTable dungeon : dungeons) {
            for (int monster : dungeon.getMonsterList()) {
                if (monster != 0 && monsterExists.test(monster)) {
                    reachable.add(monster);
                }
            }
        }
        return Set.copyOf(reachable);
    }

    /** 全部任务，按表序。 */
    public List<MissionDef> missions() {
        return missions;
    }

    /** 任务行；表里没有为 null。 */
    public MissionDef mission(int missionId) {
        return byId.get(missionId);
    }

    /** 奖励（基线 BuildRewardItems）：0 = 成功且没有物品（接取闸放行；领奖另判 1002）。 */
    public RewardDef reward(int rewardId) {
        if (rewardId == 0) {
            return new RewardDef(OK, Map.of());
        }
        RewardDef reward = rewards.get(rewardId);
        return reward != null ? reward : new RewardDef(INVALID_TABLE_DATA, Map.of());
    }

    // ------------------------------------------------------------------ 静态闸

    /** 逐格静态闸（基线 CheckAccept 的条件循环），第一个不过的格子决定错误码。 */
    private static int lateTip(List<Slot> slots, Set<Integer> reachable, boolean monsterTablesUsable) {
        for (Slot slot : slots) {
            ConditionDef c = slot.condition();
            if (c == null) {
                return INVALID_TABLE_DATA;
            }
            long target = ConditionRules.effectiveTarget(c, slot.targetOverride());
            int op = c.comparisonOp();
            if ((op != 0 && op != 1 && op != 4) || target == 0) {
                return INVALID_TABLE_DATA;
            }
            int category = c.category();
            if ((category != CATEGORY_KILL && category != CATEGORY_LEVEL && category != CATEGORY_COMPLETE_MISSION)
                    || c.validDuration() != 0) {
                return SERVICE_UNAVAILABLE;
            }
            if (!c.condition2().isEmpty() || !c.condition3().isEmpty() || !c.condition4().isEmpty()) {
                return SERVICE_UNAVAILABLE;
            }
            if (category == CATEGORY_KILL && !hasReachableMonster(c, reachable, monsterTablesUsable)) {
                return SERVICE_UNAVAILABLE;
            }
            if (Integer.compareUnsigned(c.quantityType(), 1) > 0 || (c.quantityType() == 1 && category != CATEGORY_LEVEL)) {
                return SERVICE_UNAVAILABLE;
            }
            if (op == 1 && target == MAX_U32) {
                return INVALID_TABLE_DATA;
            }
        }
        return OK;
    }

    /** 击杀条件要有能打到的怪（副本里出现、且怪物表里有）：条件列表为空 = 任何可达怪物都算；否则列表里任一可达即可。 */
    private static boolean hasReachableMonster(ConditionDef c, Set<Integer> reachable, boolean monsterTablesUsable) {
        if (!monsterTablesUsable) {
            return false;
        }
        if (c.condition1().isEmpty()) {
            return !reachable.isEmpty();
        }
        for (int monster : c.condition1()) {
            if (reachable.contains(monster)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, RewardDef> buildRewards(List<RewardTable> rows, IntPredicate itemExists) {
        Map<Integer, RewardDef> out = new HashMap<>();
        for (RewardTable row : rows) {
            out.put(row.getId(), buildReward(row, itemExists));
        }
        return out;
    }

    /** 同基线 BuildRewardItems：任一条物品或数量为 0、物品表里没有、同物品累加超 uint32、合并后为空 → 1002。 */
    private static RewardDef buildReward(RewardTable row, IntPredicate itemExists) {
        TreeMap<Integer, Long> items = new TreeMap<>(Integer::compareUnsigned);
        for (Rewardreward entry : row.getRewardList()) {
            if (entry.getRewardItem() == 0 || entry.getRewardCount() == 0 || !itemExists.test(entry.getRewardItem())) {
                return new RewardDef(INVALID_TABLE_DATA, Map.of());
            }
            long sum = items.getOrDefault(entry.getRewardItem(), 0L) + Integer.toUnsignedLong(entry.getRewardCount());
            if (sum > MAX_U32) {
                return new RewardDef(INVALID_TABLE_DATA, Map.of());
            }
            items.put(entry.getRewardItem(), sum);
        }
        if (items.isEmpty()) {
            return new RewardDef(INVALID_TABLE_DATA, Map.of());
        }
        return new RewardDef(OK, Collections.unmodifiableMap(new LinkedHashMap<>(items)));
    }

    private void warnUnusable(List<ActivityScheduleTable> scheduleRows, IntPredicate itemExists) {
        for (MissionDef def : missions) {
            for (int next : def.nextMissionIds()) {
                if (next != 0 && !byId.containsKey(next)) {
                    log.warn("任务的后续任务表里没有 mission={} next={}", Integer.toUnsignedString(def.id()),
                            Integer.toUnsignedString(next));
                }
            }
        }
        rewards.forEach((id, reward) -> {
            if (reward.tip() != OK) {
                log.warn("奖励配置不可用（物品为 0 / 数量为 0 / 物品表里没有 / 累加溢出 / 空），领这个奖励一律回 1002 reward={}",
                        Integer.toUnsignedString(id));
            }
        });
        for (ActivityScheduleTable schedule : scheduleRows) {
            MissionDef def = byId.get(schedule.getId());
            if (def != null && !def.activity()) {
                log.warn("活动排期挂在非活动任务上，不生效 mission={}", Integer.toUnsignedString(schedule.getId()));
            } else if (ActivitySchedules.windowInvalid(schedule)) {
                log.warn("活动排期窗口非法，活动列表里显示配置异常 mission={} enabled={} start={} end={}",
                        Integer.toUnsignedString(schedule.getId()), schedule.getEnabled(),
                        Long.toUnsignedString(schedule.getBaselineStartAtMs()),
                        Long.toUnsignedString(schedule.getBaselineEndAtMs()));
            }
        }
    }
}
