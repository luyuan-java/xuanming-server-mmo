package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTicketPayload;
import com.game.proto.MessageContent;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.MatchMode;
import com.game.robot.client.BattleIds;
import com.game.robot.client.GateAssignment;
import com.game.robot.client.Received;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 跨区 1V1 场景的纯判据（spectate-spec §10.8）：区服列表、157 的请求、两张直连票指向同一个节点实例、两侧同一个终局、大厅上的 150、结果行。
 * 每条都给出「对的过、错的不过」两面。
 */
class BattleCrossZoneChecksTest {

    private static final BattleIds IDS = BattleIds.resolve(MessageIdRegistry.loadFromClasspath());
    private static final ObjectMapper JSON = new ObjectMapper();
    /** uint64 上半区的号。 */
    private static final long BATTLE = 0x9000_0000_0000_0001L;
    private static final long A = 0x8000_0000_0000_0001L;
    private static final long B = 0x8000_0000_0000_0002L;

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    // ---------------------------------------------------------------- Z0

    @Test
    void 区服列表_两个区都OPEN才过_缺区或维护都写明需要双zone切片() throws Exception {
        JsonNode both = json("{\"zones\":[{\"zone_id\":1,\"name\":\"一区\",\"status\":\"OPEN\",\"load_level\":\"SMOOTH\"},"
                + "{\"zone_id\":2,\"name\":\"二区\",\"status\":\"OPEN\"}]}");
        assertThat(BattleCrossZoneChecks.zonesProblem(both, 1, 2)).isNull();
        assertThat(BattleCrossZoneChecks.zonesProblem(both, 2, 1)).as("哪个区当 A 都行").isNull();

        JsonNode single = json("{\"zones\":[{\"zone_id\":1,\"status\":\"OPEN\"}]}");
        assertThat(BattleCrossZoneChecks.zonesProblem(single, 1, 2)).isEqualTo("区 2 不在区服列表里——本场景需要 XM_ZONES=2 的切片（两个区各有自己的 gate 与 scene）");
        // 单 zone 切片上区 2 被置成维护；没有 gate 的 OPEN 区经健康探测也显示 MAINTENANCE
        JsonNode maintenance = json("{\"zones\":[{\"zone_id\":1,\"status\":\"OPEN\"},{\"zone_id\":2,\"status\":\"MAINTENANCE\",\"maintenance_msg\":\"\"}]}");
        assertThat(BattleCrossZoneChecks.zonesProblem(maintenance, 1, 2)).startsWith("区 2 的状态是 MAINTENANCE，不是 OPEN").contains("XM_ZONES=2");
        assertThat(BattleCrossZoneChecks.zonesProblem(json("{\"zones\":[]}"), 1, 2)).contains("区 1 不在区服列表里", "区 2 不在区服列表里");
        assertThat(BattleCrossZoneChecks.zonesProblem(json("{}"), 1, 2)).contains("区 1 不在区服列表里");
        assertThat(BattleCrossZoneChecks.zonesProblem(json("{\"zones\":[{\"zone_id\":1,\"status\":\"OPEN\"},{\"zone_id\":2}]}"), 1, 2))
                .startsWith("区 2 的状态是 （空），不是 OPEN");
        // 区 12 不能被当成区 1 或区 2
        assertThat(BattleCrossZoneChecks.zonesProblem(json("{\"zones\":[{\"zone_id\":12,\"status\":\"OPEN\"},{\"zone_id\":1,\"status\":\"OPEN\"}]}"), 1, 2))
                .startsWith("区 2 不在区服列表里");
    }

    @Test
    void gate端点_ip加端口() {
        assertThat(BattleCrossZoneChecks.endpoint(new GateAssignment("127.0.0.1", 11010, new byte[0], new byte[0], 0))).isEqualTo("127.0.0.1:11010");
        assertThat(BattleCrossZoneChecks.endpoint(new GateAssignment("127.0.0.1", 11000, new byte[] {1}, new byte[] {2}, 9)))
                .isNotEqualTo(BattleCrossZoneChecks.endpoint(new GateAssignment("127.0.0.1", 11010, new byte[] {1}, new byte[] {2}, 9)));
    }

    // ---------------------------------------------------------------- Z3

    @Test
    void 排队请求_1V1_配置1_zone_id如实填本侧的区() {
        JoinQueueRequest request = BattleCrossZoneChecks.joinRequest(A, 2);
        assertThat(request.getMode()).isEqualTo(MatchMode.MATCH_MODE_1V1);
        assertThat(request.getBattleConfigId()).as("同基线 robot 与 Unity 客户端；与 battle-smoke 的 0 分开，两个场景并行也不会互相凑走对手").isEqualTo(1)
                .isEqualTo(BattleCrossZoneChecks.BATTLE_CONFIG).isNotEqualTo(BattleSmokeScenario.PVP_CONFIG);
        assertThat(request.getZoneId()).isEqualTo(2);
        assertThat(request.getPlayerId()).isEqualTo(A);
        assertThat(request.getMapConfigId()).isZero();
        assertThat(request.getPartyMemberIdsCount()).isZero();
    }

    @Test
    void 排队被拒的排查提示_16020_16001_16000各有一句_别的码不多嘴() {
        assertThat(BattleCrossZoneChecks.TIP_NOT_IN_SCENE).isEqualTo(16020);
        assertThat(BattleCrossZoneChecks.joinHint(JoinQueueResponse.newBuilder().setErrorCode(16020).build())).contains("16020", "位置记录", "共用的 Redis");
        assertThat(BattleCrossZoneChecks.joinHint(JoinQueueResponse.newBuilder().setErrorCode(16001).build())).contains("16001", "ready 票据");
        assertThat(BattleCrossZoneChecks.joinHint(JoinQueueResponse.newBuilder().setErrorCode(16000).build())).contains("16000", "所在区的 scene");
        assertThat(BattleCrossZoneChecks.joinHint(JoinQueueResponse.newBuilder().setErrorCode(16004).build())).isEmpty();
        assertThat(BattleCrossZoneChecks.joinHint(JoinQueueResponse.getDefaultInstance())).isEmpty();
    }

    // ---------------------------------------------------------------- Z4

    private static BattleAssignedS2C ticket(long battleId, long playerId, int node, String instance, int port, eBattleTicketRole role) {
        return BattleAssignedS2C.newBuilder().setBattleId(battleId).setHost("127.0.0.1").setPort(port)
                .setTokenPayload(BattleTicketPayload.newBuilder().setBattleId(battleId).setPlayerId(playerId).setBattleNodeId(node)
                        .setBattleInstanceId(instance).setExpireAtMs(1).setRole(role).build().toByteString())
                .setTokenSignature(ByteString.copyFromUtf8("0123456789abcdef".repeat(4))).setExpireAtMs(1).setRole(role).build();
    }

    private static BattleAssignedS2C participant(long battleId, long playerId, int node, String instance, int port) {
        return ticket(battleId, playerId, node, instance, port, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
    }

    @Test
    void 两张直连票_同一局同一地址同一节点实例_各自本人_才算两人进了同一间房() {
        BattleAssignedS2C a = participant(BATTLE, A, 1, "i-1", 12000);
        BattleAssignedS2C b = participant(BATTLE, B, 1, "i-1", 12000);
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, b, B)).isNull();

        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, participant(BATTLE + 1, B, 1, "i-1", 12000), B))
                .contains("battle_id 不同或为 0：A 10376293541461622785，B 10376293541461622786");
        assertThat(BattleCrossZoneChecks.ticketsProblem(participant(0, A, 1, "i-1", 12000), A, participant(0, B, 1, "i-1", 12000), B))
                .contains("battle_id 不同或为 0");
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, participant(BATTLE, B, 1, "i-1", 12001), B))
                .isEqualTo("直连地址不同或为空：A 127.0.0.1:12000，B 127.0.0.1:12001");
        // 同号节点换了实例、节点号不同：都不是「同一个进程」
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, participant(BATTLE, B, 1, "i-2", 12000), B))
                .isEqualTo("两张票不是同一个节点实例签的：A node=1 instance=i-1，B node=1 instance=i-2");
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, participant(BATTLE, B, 2, "i-1", 12000), B)).contains("不是同一个节点实例签的");
        assertThat(BattleCrossZoneChecks.ticketsProblem(participant(BATTLE, A, 0, "", 12000), A, participant(BATTLE, B, 0, "", 12000), B))
                .as("节点号 0 / 实例为空：没签上").contains("不是同一个节点实例签的");
        // 两张票写的是同一个人、票发反了
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, participant(BATTLE, A, 1, "i-1", 12000), B))
                .isEqualTo("票的 player_id 不是各自本人：A 的票写着 9223372036854775809，B 的票写着 9223372036854775809");
        assertThat(BattleCrossZoneChecks.ticketsProblem(b, A, a, B)).contains("不是各自本人");
        // 角色
        assertThat(BattleCrossZoneChecks.ticketsProblem(a, A, ticket(BATTLE, B, 1, "i-1", 12000, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER), B))
                .contains("role=2（期望 1 PARTICIPANT）", "payload 的 battle_id / role 与外层对不上");
        // payload 坏了
        assertThat(BattleCrossZoneChecks.ticketsProblem(a.toBuilder().setTokenPayload(ByteString.copyFrom(new byte[] {(byte) 0xff})).build(), A, b, B))
                .contains("token_payload 解析不了");
        assertThat(BattleCrossZoneChecks.ticketsProblem(a.toBuilder().clearHost().build(), A, b.toBuilder().clearHost().build(), B))
                .contains("直连地址不同或为空");
    }

    // ---------------------------------------------------------------- Z6

    private static BattleEndS2C end(long battleId, eBattleOutcome outcome, int rounds) {
        return BattleEndS2C.newBuilder().setBattleId(battleId).setOutcome(outcome)
                .setSettlement(BattleSettlementData.newBuilder().setBattleId(battleId).setOutcome(outcome).setTotalRounds(rounds)).build();
    }

    @Test
    void 两侧的终局_相同且是胜负平之一_outcome按阵营不按本人视角() {
        eBattleOutcome aWin = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, aWin, 12), end(BATTLE, aWin, 12), BATTLE)).isNull();
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_DRAW, 30),
                end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_DRAW, 30), BATTLE)).isNull();
        // 一边「我赢了」一边「我输了」这种本人视角的写法：两边的值不同
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, aWin, 12), end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 12), BATTLE))
                .isEqualTo("两侧的终局不同：A BATTLE_OUTCOME_SIDE_A_WIN，B BATTLE_OUTCOME_SIDE_B_WIN");
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_ONGOING, 1),
                end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_ONGOING, 1), BATTLE)).isEqualTo("终局 BATTLE_OUTCOME_ONGOING 不是 SIDE_A_WIN / SIDE_B_WIN / DRAW 之一");
        // 把上一局迟到的 150 当成了本局的
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, aWin, 12), end(BATTLE - 1, aWin, 12), BATTLE))
                .startsWith("150 的 battle_id 对不上本局 10376293541461622785：A 10376293541461622785，B 10376293541461622784");
        assertThat(BattleCrossZoneChecks.outcomesProblem(end(BATTLE, aWin, 12), end(BATTLE, aWin, 13), BATTLE))
                .isEqualTo("两侧 settlement 的 total_rounds 不同：A 12，B 13");
    }

    // ---------------------------------------------------------------- Z7

    private static Received lobby(int index, int messageId, long requestId, Message body) {
        return new Received(index, 0, MessageContent.newBuilder().setMessageId(messageId).setId(requestId).setSerializedMessage(body.toByteString())
                .build());
    }

    @Test
    void 大厅上的150_只认推送形状且是本局的_上一局迟到的那条不算() {
        BattleEndS2C mine = end(BATTLE, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 12);
        assertThat(BattleCrossZoneChecks.lobbyEnd(lobby(0, IDS.battleEnd(), 0, mine), IDS, BATTLE)).isEqualTo(mine);
        assertThat(BattleCrossZoneChecks.lobbyEnd(lobby(0, IDS.battleEnd(), 0, mine), IDS, BATTLE + 1)).as("第二局不认第一局迟到的 150").isNull();
        assertThat(BattleCrossZoneChecks.lobbyEnd(lobby(0, IDS.battleEnd(), 5, mine), IDS, BATTLE)).as("应答形状").isNull();
        assertThat(BattleCrossZoneChecks.lobbyEnd(lobby(0, IDS.battleStart(), 0, mine), IDS, BATTLE)).isNull();
        Received garbage = new Received(0, 0, MessageContent.newBuilder().setMessageId(IDS.battleEnd())
                .setSerializedMessage(ByteString.copyFrom(new byte[] {(byte) 0xff, (byte) 0xff})).build());
        assertThat(BattleCrossZoneChecks.lobbyEnd(garbage, IDS, BATTLE)).isNull();
    }

    @Test
    void 大厅上的139条数() {
        List<Received> inbox = List.of(lobby(0, IDS.battleAssigned(), 0, BattleAssignedS2C.getDefaultInstance()),
                lobby(1, IDS.battleEnd(), 0, BattleEndS2C.getDefaultInstance()));
        assertThat(BattleCrossZoneChecks.lobbyTurns(inbox, IDS)).isZero();
        assertThat(BattleCrossZoneChecks.lobbyTurns(List.of(lobby(0, IDS.turnResult(), 0, TurnResultS2C.getDefaultInstance()),
                lobby(1, IDS.spectateTurnResult(), 0, TurnResultS2C.getDefaultInstance())), IDS)).as("只数 139，158 不是它").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 结果行

    @Test
    void 结果行的字段_前七个与基线同名同序_后接观众与第二局_战斗号按无符号十进制() {
        assertThat(BattleCrossZoneChecks.okFields(BATTLE, 1, 2, 21, 20, 2, 19, BATTLE + 1))
                .isEqualTo("battle_id=10376293541461622785 zone_a=1 zone_b=2 a_turns=21 b_turns=20 a_direct_turns=21 b_direct_turns=20 "
                        + "observer_zone=2 c_spectate_turns=19 second_battle_id=10376293541461622786");
        assertThat(BattleCrossZoneChecks.okFields(0, 1, 2, 0, 0, 2, 0, 0)).doesNotContain("-").endsWith(" second_battle_id=0");
        assertThat(BattleCrossZoneScenario.MARKER).isEqualTo("CROSS_ZONE_MATCH");
    }
}
