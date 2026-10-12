package com.game.scene.player;

import com.game.player.store.state.ActiveMission;
import com.game.player.store.state.MissionState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家的任务状态（只在场景逻辑线程上读写；基线 MissionsComp 的 scope 0）：进行中的任务（带每个条件格的进度）、已完成集合、
 * 可领奖集合。任务 id 是 uint32，一律按无符号排序。
 *
 * <p>另有两份<b>不持久化</b>的派生索引，由任务服务在加载时重建、接取 / 完成时维护：条件类别 → 关注它的进行中任务、
 * 已被占用的（任务类型, 子类型）。同基线 RebuildIndexes：只有表里有、且未完成的进行中任务才进索引（表里删掉的任务照样显示进行中，
 * 但不占类型、不再推进）。
 */
public final class PlayerMissions {

    /** 一个进行中的任务。 */
    public static final class Active {
        private final int missionId;
        private final long[] progress;
        private final long acceptedAtMs;
        private final UnknownFieldSet unknownFields;

        Active(int missionId, long[] progress, long acceptedAtMs, UnknownFieldSet unknownFields) {
            this.missionId = missionId;
            this.progress = progress;
            this.acceptedAtMs = acceptedAtMs;
            this.unknownFields = unknownFields;
        }

        public int missionId() {
            return missionId;
        }

        /** 条件格数（与表里的条件数不一致时任务永不推进、永不完成）。 */
        public int slots() {
            return progress.length;
        }

        /** 第 i 格进度（uint32，long 表示）。 */
        public long progress(int slot) {
            return progress[slot];
        }

        public void progress(int slot, long value) {
            progress[slot] = value;
        }

        public long acceptedAtMs() {
            return acceptedAtMs;
        }
    }

    private final TreeMap<Integer, Active> active = new TreeMap<>(Integer::compareUnsigned);
    private final TreeSet<Integer> completed = new TreeSet<>(Integer::compareUnsigned);
    private final TreeSet<Integer> claimable = new TreeSet<>(Integer::compareUnsigned);
    private final UnknownFieldSet unknownFields;
    /** 派生索引：条件类别 → 关注它的进行中任务。 */
    private final Map<Integer, TreeSet<Integer>> watchers = new HashMap<>();
    /** 派生索引：被进行中任务占用的（类型, 子类型）。 */
    private final Set<Long> occupiedTypes = new HashSet<>();

    private PlayerMissions(UnknownFieldSet unknownFields) {
        this.unknownFields = unknownFields;
    }

    public static PlayerMissions empty() {
        return new PlayerMissions(UnknownFieldSet.getDefaultInstance());
    }

    /** 从存档恢复（不校验、不抛异常；重复的进行中条目第一个为准，同基线）。索引由任务服务加载时重建。 */
    public static PlayerMissions restore(MissionState state) {
        PlayerMissions missions = new PlayerMissions(state.getUnknownFields());
        for (ActiveMission entry : state.getActiveList()) {
            long[] progress = new long[entry.getProgressCount()];
            for (int i = 0; i < progress.length; i++) {
                progress[i] = Integer.toUnsignedLong(entry.getProgress(i));
            }
            missions.active.putIfAbsent(entry.getMissionId(), new Active(entry.getMissionId(), progress,
                    entry.getAcceptedAtMs(), entry.getUnknownFields()));
        }
        missions.completed.addAll(state.getCompletedIdsList());
        missions.claimable.addAll(state.getClaimableIdsList());
        return missions;
    }

    // ------------------------------------------------------------------ 查询

    public Active active(int missionId) {
        return active.get(missionId);
    }

    public boolean isAccepted(int missionId) {
        return active.containsKey(missionId);
    }

    public boolean isComplete(int missionId) {
        return completed.contains(missionId);
    }

    public boolean isClaimable(int missionId) {
        return claimable.contains(missionId);
    }

    /** 进行中 / 已完成 / 可领奖的全部任务 id（无符号升序，去重）。 */
    public TreeSet<Integer> knownIds() {
        TreeSet<Integer> ids = new TreeSet<>(Integer::compareUnsigned);
        ids.addAll(active.keySet());
        ids.addAll(completed);
        ids.addAll(claimable);
        return ids;
    }

    /** 已完成的任务 id（无符号升序，只读视图）。 */
    public Set<Integer> completedIds() {
        return java.util.Collections.unmodifiableSet(completed);
    }

    /** 进行中的任务 id（无符号升序，快照）。 */
    public List<Integer> activeIds() {
        return new ArrayList<>(active.keySet());
    }

    /** 关注某条件类别的进行中任务（无符号升序，快照：遍历期间可以改状态）。 */
    public List<Integer> watchers(int category) {
        TreeSet<Integer> ids = watchers.get(category);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    public boolean typeOccupied(int missionType, int subType) {
        return occupiedTypes.contains(typeKey(missionType, subType));
    }

    // ------------------------------------------------------------------ 修改（任务服务调用）

    /** 清空派生索引（加载时重建前）。 */
    public void clearIndexes() {
        watchers.clear();
        occupiedTypes.clear();
    }

    /** 把一个进行中任务登记进索引。 */
    public void index(int missionId, int missionType, int subType, Set<Integer> categories) {
        occupiedTypes.add(typeKey(missionType, subType));
        for (int category : categories) {
            watchers.computeIfAbsent(category, c -> new TreeSet<>(Integer::compareUnsigned)).add(missionId);
        }
    }

    /** 接取：新建进行中条目（每个条件格进度 0）并登记索引。调用方已过闸。 */
    public void accept(int missionId, int conditionCount, long nowMs, int missionType, int subType,
                       Set<Integer> categories) {
        active.put(missionId, new Active(missionId, new long[conditionCount], nowMs, UnknownFieldSet.getDefaultInstance()));
        index(missionId, missionType, subType, categories);
    }

    /** 完成：移出进行中、注销索引（先腾出类型，后续的链式接取才能接同类型任务）、记已完成。 */
    public void complete(int missionId, int missionType, int subType, Set<Integer> categories) {
        active.remove(missionId);
        occupiedTypes.remove(typeKey(missionType, subType));
        for (int category : categories) {
            TreeSet<Integer> ids = watchers.get(category);
            if (ids != null) {
                ids.remove(missionId);
            }
        }
        completed.add(missionId);
    }

    public void setClaimable(int missionId) {
        claimable.add(missionId);
    }

    public void clearClaimable(int missionId) {
        claimable.remove(missionId);
    }

    // ------------------------------------------------------------------ 持久化

    /** 从没接过任务（没有任何条目与不认识的字段）：持久化时整段省略。 */
    public boolean isPristine() {
        return active.isEmpty() && completed.isEmpty() && claimable.isEmpty() && unknownFields.asMap().isEmpty();
    }

    /** 存档形态：三个列表都按任务 id 无符号升序。 */
    public MissionState toState() {
        MissionState.Builder state = MissionState.newBuilder().setUnknownFields(unknownFields);
        for (Active entry : active.values()) {
            ActiveMission.Builder mission = ActiveMission.newBuilder()
                    .setMissionId(entry.missionId)
                    .setAcceptedAtMs(entry.acceptedAtMs)
                    .setUnknownFields(entry.unknownFields);
            for (long value : entry.progress) {
                mission.addProgress((int) value);
            }
            state.addActive(mission);
        }
        state.addAllCompletedIds(completed);
        state.addAllClaimableIds(claimable);
        return state.build();
    }

    private static long typeKey(int missionType, int subType) {
        return (Integer.toUnsignedLong(missionType) << 32) | Integer.toUnsignedLong(subType);
    }
}
