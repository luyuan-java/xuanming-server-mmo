package com.game.battle.room;

import com.game.proto.BattleActivityContext;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对局结果事件 {@code BattleResultEvent} 的组装（基线 {@code room.cpp:1137-1201} 与 {@code battle_result_activity.h:43-81}；
 * battle-node-spec §4.9）。纯函数，线程安全。
 *
 * <ul>
 *   <li>{@code winner_team_index}：只有 {@code SIDE_B_WIN} 时为 1，其余（含 DRAW）为 0；</li>
 *   <li>{@code teams}：按 team_index 无符号升序，队内 player_id 无符号升序；</li>
 *   <li>{@code total_rounds}：最后一名参战者结算里的值（大家都相同）；</li>
 *   <li>{@code activity_context}：只在 {@code kind ≠ NONE} 时回显，<b>不认识的 kind 值也按活动局处理</b>（宁可多落一次库）；</li>
 *   <li>{@code fled_player_ids} / {@code dead_player_ids}：所有对局都填，无符号升序、去重。</li>
 * </ul>
 */
final class BattleResultAssembler {

    private static final Comparator<BattleSettlementData> BY_PLAYER =
            (a, b) -> Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());

    private BattleResultAssembler() {
    }

    /** 带活动上下文的局（{@code kind} 的整数值 ≠ 0，含不认识的值）：走持久的活动结果通道。 */
    static boolean isActivity(BattleActivityContext activity) {
        return activity.getKindValue() != 0;
    }

    /**
     * @param settlements  每名参战者那份结算（outcome 已被节点覆盖；顺序不限，这里按 player_id 无符号升序处理）
     * @param finishedAtMs 组装时刻的 UTC 毫秒（注入的时钟）
     */
    static BattleResultEvent assemble(long battleId, int matchMode, int battleConfigId, BattleActivityContext activity,
                                      eBattleOutcome outcome, List<BattleSettlementData> settlements, long finishedAtMs) {
        List<BattleSettlementData> ordered = new ArrayList<>(settlements);
        ordered.sort(BY_PLAYER);

        Map<Integer, List<Long>> playersByTeam = new TreeMap<>(Integer::compareUnsigned);
        List<Long> fled = new ArrayList<>();
        List<Long> dead = new ArrayList<>();
        int totalRounds = 0;
        for (BattleSettlementData settlement : ordered) {
            playersByTeam.computeIfAbsent(settlement.getPlayerTeamIndex(), team -> new ArrayList<>()).add(settlement.getPlayerId());
            totalRounds = settlement.getTotalRounds();
            if (settlement.getFled()) {
                fled.add(settlement.getPlayerId());
            }
            if (settlement.getIsDead()) {
                dead.add(settlement.getPlayerId());
            }
        }

        BattleResultEvent.Builder event = BattleResultEvent.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(matchMode)
                .setBattleConfigId(battleConfigId)
                .setOutcome(outcome)
                .setWinnerTeamIndex(outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN ? 1 : 0);
        playersByTeam.forEach((team, players) ->
                event.addTeams(BattleResultTeam.newBuilder().setTeamIndex(team).addAllPlayerIds(players)));
        event.setTotalRounds(totalRounds).setFinishedAtMs(finishedAtMs);
        if (isActivity(activity)) {
            event.setActivityContext(activity);
        }
        event.addAllFledPlayerIds(sortedUnique(fled)).addAllDeadPlayerIds(sortedUnique(dead));
        return event.build();
    }

    private static List<Long> sortedUnique(List<Long> ids) {
        return ids.stream().distinct().sorted(Long::compareUnsigned).toList();
    }
}
