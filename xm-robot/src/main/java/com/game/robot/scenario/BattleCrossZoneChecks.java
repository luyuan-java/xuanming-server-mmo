package com.game.robot.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.eBattleTicketRole;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GateAssignment;
import com.game.robot.client.Received;
import com.game.table.MatchErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨区 1V1 场景（{@link BattleCrossZoneScenario}，spectate-spec §10.8）的纯判据：不碰网络，输入是已解析的应答 / 推送，输出是问题描述
 * （没问题返回 null）。补的是基线 robot {@code battle_smoke_cross_zone_scenario.go} 的几处弱点（spectate-spec §2.5）：157 的回包要核对、
 * 两张直连票必须指向同一个节点实例、两侧的终局必须相同、大厅上的 150 与直连上的分开看。
 */
final class BattleCrossZoneChecks {

    /** 1V1 的 battle_config_id：同基线 robot 与 Unity 客户端（都固定 1）；1V1 不校验它，但它是队列键的一部分（spectate-spec Q19）。 */
    static final int BATTLE_CONFIG = 1;
    /** 16020「请先进入场景」：读不到在线的位置记录。跨区时出现它，多半是二区的 scene 没把位置写进共用的 Redis。 */
    static final int TIP_NOT_IN_SCENE = MatchErrorTip.match_error.kMatchNotInScene_VALUE;

    private BattleCrossZoneChecks() {
    }

    // ---------------------------------------------------------------- Z0：两个区都开着

    /**
     * {@code GET /api/server-list} 里两个区都在、显示状态都是 OPEN（显示状态 = 手工状态叠加健康探测：没有 gate 的区显示 MAINTENANCE）。
     *
     * @return null = 没问题
     */
    static String zonesProblem(JsonNode serverList, int zoneA, int zoneB) {
        List<String> problems = new ArrayList<>();
        for (int zone : List.of(zoneA, zoneB)) {
            String status = null;
            for (JsonNode item : serverList.path("zones")) {
                if (item.path("zone_id").asInt() == zone) {
                    status = item.path("status").asText("");
                }
            }
            if (status == null) {
                problems.add("区 " + zone + " 不在区服列表里");
            } else if (!status.equals("OPEN")) {
                problems.add("区 " + zone + " 的状态是 " + (status.isEmpty() ? "（空）" : status) + "，不是 OPEN");
            }
        }
        return problems.isEmpty() ? null : String.join("；", problems) + "——本场景需要 XM_ZONES=2 的切片（两个区各有自己的 gate 与 scene）";
    }

    /** assign-gate 给的 gate 端点（{@code ip:port}）。 */
    static String endpoint(GateAssignment gate) {
        return gate.gateIp() + ":" + gate.gatePort();
    }

    // ---------------------------------------------------------------- Z3：排队

    /**
     * 157：1V1、{@value #BATTLE_CONFIG} 号配置，{@code zone_id} 如实填本侧登录的区（同基线 robot；服务端不读它，zone 一律取位置记录）。
     */
    static JoinQueueRequest joinRequest(long playerId, int zoneId) {
        return JoinQueueRequest.newBuilder().setPlayerId(playerId).setMode(MatchMode.MATCH_MODE_1V1).setBattleConfigId(BATTLE_CONFIG)
                .setZoneId(zoneId).build();
    }

    /** 157 被拒时按码给的排查提示（拼在失败细节后面）；没有特别要说的为空串。 */
    static String joinHint(JoinQueueResponse response) {
        int code = response.getErrorCode();
        if (code == TIP_NOT_IN_SCENE) {
            return "（16020：match 读不到这名玩家在线的位置记录——位置要由他所在区的 scene 写进各区共用的 Redis）";
        }
        if (code == BattleSmokeChecks.TIP_ALREADY_QUEUED) {
            return "（16001：上一局的 ready 票据没有被自愈清掉，或上一遍 robot 的排队票还在）";
        }
        if (code == BattleSmokeChecks.TIP_IN_BATTLE) {
            return "（16000：战斗锁还在——上一局的结算没有回到这名玩家所在区的 scene）";
        }
        return "";
    }

    // ---------------------------------------------------------------- Z4：两张票指向同一间房

    /**
     * 两名参战者各自收到的 177（spectate-spec §10.8 Z4）：同一个 battle_id、同一个 {@code host:port}（battle 是全局池，两人连同一个进程）；
     * 两张 {@code BattleTicketPayload} 的 {@code battle_node_id}、{@code battle_instance_id} 相同，{@code player_id} 各是自己，角色都是参战者。
     * 票里没有 zone——跨区靠的就是它只绑节点与实例。
     *
     * @return null = 没问题
     */
    static String ticketsProblem(BattleAssignedS2C ticketA, long playerA, BattleAssignedS2C ticketB, long playerB) {
        List<String> problems = new ArrayList<>();
        if (ticketA.getBattleId() == 0 || ticketA.getBattleId() != ticketB.getBattleId()) {
            problems.add("battle_id 不同或为 0：A " + uid(ticketA.getBattleId()) + "，B " + uid(ticketB.getBattleId()));
        }
        String endpointA = ticketA.getHost() + ":" + ticketA.getPort();
        String endpointB = ticketB.getHost() + ":" + ticketB.getPort();
        if (ticketA.getHost().isEmpty() || !endpointA.equals(endpointB)) {
            problems.add("直连地址不同或为空：A " + endpointA + "，B " + endpointB);
        }
        BattleTicketPayload payloadA;
        BattleTicketPayload payloadB;
        try {
            payloadA = BattleTicketPayload.parseFrom(ticketA.getTokenPayload());
            payloadB = BattleTicketPayload.parseFrom(ticketB.getTokenPayload());
        } catch (InvalidProtocolBufferException e) {
            problems.add("token_payload 解析不了：" + e.getMessage());
            return String.join("；", problems);
        }
        if (payloadA.getBattleNodeId() == 0 || payloadA.getBattleNodeId() != payloadB.getBattleNodeId()
                || payloadA.getBattleInstanceId().isEmpty() || !payloadA.getBattleInstanceId().equals(payloadB.getBattleInstanceId())) {
            problems.add("两张票不是同一个节点实例签的：A node=" + payloadA.getBattleNodeId() + " instance=" + payloadA.getBattleInstanceId()
                    + "，B node=" + payloadB.getBattleNodeId() + " instance=" + payloadB.getBattleInstanceId());
        }
        if (payloadA.getPlayerId() != playerA || payloadB.getPlayerId() != playerB) {
            problems.add("票的 player_id 不是各自本人：A 的票写着 " + uid(payloadA.getPlayerId()) + "，B 的票写着 " + uid(payloadB.getPlayerId()));
        }
        for (BattleAssignedS2C ticket : List.of(ticketA, ticketB)) {
            if (ticket.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT) {
                problems.add("role=" + ticket.getRole().getNumber() + "（期望 1 PARTICIPANT）");
            }
        }
        if (payloadA.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT
                || payloadB.getRole() != eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT
                || payloadA.getBattleId() != ticketA.getBattleId() || payloadB.getBattleId() != ticketB.getBattleId()) {
            problems.add("payload 的 battle_id / role 与外层对不上");
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    // ---------------------------------------------------------------- Z6：两侧同一个终局

    /**
     * 两侧直连上的 150：{@code outcome} 相同且是胜 / 负 / 平之一（{@code eBattleOutcome} 是按阵营的绝对值，不是「本人视角」：
     * 0 队赢了两边都是 SIDE_A_WIN）；各自的 settlement 指向本局。
     *
     * @return null = 没问题
     */
    static String outcomesProblem(BattleEndS2C endA, BattleEndS2C endB, long battleId) {
        List<String> problems = new ArrayList<>();
        if (endA.getOutcome() != endB.getOutcome()) {
            problems.add("两侧的终局不同：A " + endA.getOutcome() + "，B " + endB.getOutcome());
        }
        if (!BattleSmokeChecks.rated(endA.getOutcome())) {
            problems.add("终局 " + endA.getOutcome() + " 不是 SIDE_A_WIN / SIDE_B_WIN / DRAW 之一");
        }
        if (endA.getBattleId() != battleId || endB.getBattleId() != battleId) {
            problems.add("150 的 battle_id 对不上本局 " + uid(battleId) + "：A " + uid(endA.getBattleId()) + "，B " + uid(endB.getBattleId()));
        }
        if (endA.getSettlement().getTotalRounds() != endB.getSettlement().getTotalRounds()) {
            problems.add("两侧 settlement 的 total_rounds 不同：A " + endA.getSettlement().getTotalRounds() + "，B "
                    + endB.getSettlement().getTotalRounds());
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    // ---------------------------------------------------------------- Z7：大厅上的 150

    /** 大厅上这一局的 150（推送形状、battle_id 对上）；不是则为 null。scene 应用结算之后才推它，所以它证明结算回到了这名玩家所在区的 scene。 */
    static BattleEndS2C lobbyEnd(Received r, BattleIds ids, long battleId) {
        if (r.messageId() != ids.battleEnd() || r.requestId() != 0) {
            return null;
        }
        BattleEndS2C end = r.parseOrNull(BattleEndS2C.parser());
        return end != null && end.getBattleId() == battleId ? end : null;
    }

    /** 大厅连接上 139 的条数（回合帧只走直连，应当是 0）。 */
    static long lobbyTurns(List<Received> lobby, BattleIds ids) {
        return lobby.stream().filter(r -> r.messageId() == ids.turnResult()).count();
    }

    // ---------------------------------------------------------------- 结果行

    /**
     * 通过时的字段：前七个与基线 {@code CROSS_ZONE_MATCH_OK} 同名同序（{@code bsc.go:16-20}；回合帧只走直连，所以 {@code *_turns} 与
     * {@code *_direct_turns} 同值），后接 Java 加的观众与第二局。
     */
    static String okFields(long battleId, int zoneA, int zoneB, int aTurns, int bTurns, int observerZone, int spectateTurns, long secondBattleId) {
        return "battle_id=" + uid(battleId) + " zone_a=" + zoneA + " zone_b=" + zoneB + " a_turns=" + aTurns + " b_turns=" + bTurns
                + " a_direct_turns=" + aTurns + " b_direct_turns=" + bTurns + " observer_zone=" + observerZone + " c_spectate_turns=" + spectateTurns
                + " second_battle_id=" + uid(secondBattleId);
    }

    private static String uid(long id) {
        return Long.toUnsignedString(id);
    }
}
