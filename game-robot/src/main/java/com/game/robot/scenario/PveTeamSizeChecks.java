package com.game.robot.scenario;

import com.game.proto.BattleStateS2C;
import com.game.proto.match.*;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** PVE人数档的客户端判据；拒绝、撤销墓碑与实际战场人数分别验证，不以无推送代替SQL撤销证明。 */
final class PveTeamSizeChecks {
    static final String OVER_LIMIT_TEXT = "该副本不支持这个队伍人数";
    private PveTeamSizeChecks() {}

    static JoinQueueRequest request(long player, int size, String request, String plan) {
        var original = UUID.fromString(request);
        if (player == 0 || !original.toString().equals(request)
                || original.getMostSignificantBits() == 0 && original.getLeastSignificantBits() == 0 || plan.isBlank())
            throw new IllegalArgumentException("PVE排队缺少本人、规范UUID或网络原计划");
        return JoinQueueRequest.newBuilder().setPlayerId(player).setMode(MatchMode.MATCH_MODE_PVE_TEAM).setBattleConfigId(1)
                .setTeamSize(size).setRequestId(request).setNetworkPlanId(plan).build();
    }
    static String overLimitProblem(JoinQueueResponse response) {
        return response.getErrorCode() == BattleSmokeChecks.TIP_MODE_NOT_OPEN && response.getErrorMessage().getId() == BattleSmokeChecks.TIP_MODE_NOT_OPEN
                && response.getErrorMessage().getParametersList().equals(List.of(OVER_LIMIT_TEXT)) && response.getQueueTicket().isEmpty()
                ? "" : "人数超限必须回16002及准确文案且不发票：" + BattleSmokeChecks.describe(response);
    }
    static String cancelledProblem(GetQueueStatusResponse response) {
        return response.getState() == QueueState.QUEUE_STATE_NOT_QUEUED && response.getRequestCancelled()
                && response.getQueueTicket().isEmpty() && response.getBattleId() == 0
                ? "" : "原Join撤销尚未证明：state=" + response.getState() + " tombstone=" + response.getRequestCancelled();
    }
    static String formationProblem(BattleStateS2C state, List<Long> players, int monsters) {
        var wanted = new HashSet<>(players);
        var found = new HashSet<Long>();
        var allIds = new HashSet<Long>();
        int count = 0;
        for (var actor : state.getActorsList()) {
            if (actor.getActorId() == 0 || !allIds.add(actor.getActorId())) return "战场单位ID为空或重复";
            switch (actor.getActorTypeValue()) {
                case 1 -> {
                    if (!wanted.contains(actor.getActorId()) || actor.getTeamIndex() != 0 || !found.add(actor.getActorId()))
                        return "己方玩家不是本次精确名单";
                }
                case 2 -> {
                    if (actor.getTeamIndex() != 1 || actor.getMonsterTableId() == 0) return "怪物队伍或模板不合法";
                    count++;
                }
                case 3 -> {
                    if (!wanted.contains(actor.getOwnerPlayerId()) || actor.getTeamIndex() != 0) return "宝宝不属于本次参战玩家";
                }
                default -> { return "战场存在未知类型单位"; }
            }
        }
        if (wanted.size() != players.size() || !wanted.equals(found)) return "己方人数或名单不完整";
        return count == monsters ? "" : "怪物数=" + count + "，期望=" + monsters;
    }
}
