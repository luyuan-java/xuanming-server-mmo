package com.game.scene.mission;

import java.util.List;

/**
 * 一条推进任务的事实（基线 ConditionEvent）：类别 + 参数（击杀 = [怪物配置号]、等级 = [等级]、完成任务 = [任务号]）+ 量。
 *
 * @param amount       量（uint32，long 承载）
 * @param onlyMission  只推进这个任务（接取回填用；0 = 所有关注该类别的进行中任务）
 */
record MissionFact(int category, List<Integer> ids, long amount, int onlyMission) {

    MissionFact {
        ids = List.copyOf(ids);
    }

    static MissionFact of(int category, int id, long amount) {
        return new MissionFact(category, List.of(id), amount, 0);
    }

    MissionFact onlyFor(int missionId) {
        return new MissionFact(category, ids, amount, missionId);
    }
}
