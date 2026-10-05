package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.testing.FakeBattleData;
import com.game.common.token.BattleTickets;
import com.game.proto.BattleActorState;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleRouting;
import com.game.proto.BattleStartS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.MessageContent;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CreateBattle 的判定顺序与插表后的副作用顺序（基线 {@code room.cpp:486-632}；battle-node-spec §4.3、§13.2）。 */
class CreateBattleTest {

    private final RoomHarness h = new RoomHarness();

    private double creates(String result) {
        return h.counter("xm.battle.room.creates", "result", result);
    }

    private CreateBattleResponse createRaw(CreateBattleRequest.Builder request) {
        return h.service().createBattle(request.build(), RoomOrigin.MATCH);
    }

    private void assertNoSideEffects() {
        assertThat(h.log).as("零出站").isEmpty();
        assertThat(h.scheduler.scheduledCount()).as("零计时器").isZero();
        assertThat(h.service().roomCount()).as("没有插表").isZero();
    }

    @Test
    void 成功_出站严格按player_id升序_每人177与143同一批_随后确认() {
        // 请求里故意倒序：B 在 team 0，A 在 team 1
        CreateBattleResponse response = h.create(h.pvp(1001, B, A));

        assertThat(response.getBattleId()).isEqualTo(1001);
        assertThat(h.trace()).containsExactly("lobby:5001:177,143", "confirm:5001", "lobby:5002:177,143", "confirm:5002");
        assertThat(h.service().roomCount()).as("onCreated 恰好一次").isEqualTo(1);
        assertThat(h.scheduler.scheduledCount()).as("整场期限 + 回合 + 确认补发").isEqualTo(3);
        assertThat(creates("ok")).isEqualTo(1);
        assertThat(h.counter("xm.battle.tickets", "path", "create", "result", "ok")).isEqualTo(2);
    }

    @Test
    void 开局包_回合1_截止为now加6秒_本人道具_他人冷却为空() throws Exception {
        h.create(h.pvp(1002, A, B));

        List<MessageContent> frames = h.lobbyFrames(A);
        assertThat(frames).extracting(MessageContent::getMessageId).containsExactly(177, 143);
        assertThat(frames).allSatisfy(f -> assertThat(f.getId()).as("推送形状 id = 0").isZero());

        BattleAssignedS2C assigned = RoomHarness.parse(BattleAssignedS2C.parser(), frames.get(0));
        assertThat(assigned.getBattleId()).isEqualTo(1002);
        assertThat(assigned.getHost()).isEqualTo(RoomHarness.HOST);
        assertThat(assigned.getPort()).isEqualTo(RoomHarness.PORT);
        assertThat(assigned.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(assigned.getExpireAtMs()).isEqualTo(h.now() + 300_000);
        assertThat(assigned.getTokenSignature().toString(StandardCharsets.US_ASCII)).matches("^[0-9a-f]{64}$");
        BattleTicketPayload payload = BattleTicketPayload.parseFrom(assigned.getTokenPayload());
        assertThat(payload.getPlayerId()).isEqualTo(A);
        assertThat(payload.getBattleNodeId()).isEqualTo(RoomHarness.NODE_ID);
        assertThat(payload.getBattleInstanceId()).isEqualTo(RoomHarness.INSTANCE);
        assertThat(h.tickets.signatureMatches(assigned.getTokenPayload(), assigned.getTokenSignature())).isTrue();

        BattleStartS2C start = RoomHarness.parse(BattleStartS2C.parser(), frames.get(1));
        assertThat(start.getBattleId()).isEqualTo(1002);
        assertThat(start.getState().getBattleId()).isEqualTo(1002);
        assertThat(start.getState().getRoundIndex()).isEqualTo(1);
        assertThat(start.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(start.getState().getActionDeadlineMs()).as("第一回合恒为 6 s").isEqualTo(h.now() + 6000);
        assertThat(start.getState().getPendingActorIdsList()).containsExactly(A, B);
        assertThat(start.getState().getSelfItemsList()).singleElement()
                .satisfies(item -> assertThat(item.getItemTableId()).isEqualTo(FakeBattleData.ITEM_POTION));
        for (BattleActorState actor : start.getState().getActorsList()) {
            if (actor.getActorId() != A) {
                assertThat(actor.getSkillCooldownRoundsMap()).isEmpty();
            }
        }
    }

    @Test
    void 幂等_内容不同也回OK_零新出站_不重装计时器_hooks不触发() {
        h.create(h.pveTeam(1003, A, B));
        int timers = h.scheduler.scheduledCount();
        h.clearLog();

        CreateBattleResponse again = createRaw(h.pve(1003, C).setSeed(999));

        assertThat(again.getBattleId()).isEqualTo(1003);
        assertThat(again.hasErrorMessage()).isFalse();
        assertThat(h.log).isEmpty();
        assertThat(h.scheduler.scheduledCount()).isEqualTo(timers);
        assertThat(h.service().roomCount()).isEqualTo(1);
        assertThat(creates("idempotent")).isEqualTo(1);
    }

    @Test
    void battle_id为0或没有玩家回1005() {
        CreateBattleResponse zeroId = createRaw(h.pve(0, A));
        CreateBattleResponse noPlayers = createRaw(h.pve(1004, A).clearPlayers());

        assertThat(zeroId.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(zeroId.getBattleId()).isZero();
        assertThat(noPlayers.getErrorMessage().getId()).isEqualTo(1005);
        assertThat(noPlayers.getBattleId()).as("每条路径都回填 battle_id").isEqualTo(1004);
        assertNoSideEffects();
        assertThat(creates("invalid")).isEqualTo(2);
    }

    @Test
    void 缺gate或scene实例回1005_且排在指纹之前() {
        h.fingerprintMode = FingerprintMode.ENFORCE;
        CreateBattleRequest.Builder noGate = h.pve(1005, A).setTableFingerprint("other");
        noGate.getPlayersBuilder(0).setRouting(RoomHarness.routing(A).toBuilder().clearGateInstanceId());
        CreateBattleRequest.Builder noScene = h.pveTeam(1006, A, B);
        noScene.getPlayersBuilder(1).setRouting(RoomHarness.routing(B).toBuilder().clearSceneInstanceId());

        assertThat(createRaw(noGate).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(createRaw(noScene).getErrorMessage().getId()).isEqualTo(1005);
        assertNoSideEffects();
        assertThat(h.counter("xm.battle.fingerprint.mismatch", "mode", "enforce")).as("没走到指纹闸").isZero();
    }

    @Test
    void enforce指纹不符回1006_文案逐字() {
        h.fingerprintMode = FingerprintMode.ENFORCE;

        CreateBattleResponse response = createRaw(h.pve(1007, A).setTableFingerprint("other"));

        assertThat(response.getErrorMessage().getId()).isEqualTo(1006);
        assertThat(response.getErrorMessage().getParametersList())
                .containsExactly("battle table fingerprint mismatch: node=" + RoomHarness.NODE_FINGERPRINT + " request=other");
        assertNoSideEffects();
        assertThat(creates("fingerprint_reject")).isEqualTo(1);
    }

    @Test
    void warn指纹不符照常开局() {
        h.create(h.pve(1008, A).setTableFingerprint("other"));

        assertThat(h.service().roomCount()).isEqualTo(1);
        assertThat(h.counter("xm.battle.fingerprint.mismatch", "mode", "warn")).isEqualTo(1);
    }

    @Test
    void 引擎拒绝回1002() {
        // 只有一边（两人都在 team 0 的 PVP）
        CreateBattleRequest.Builder oneSided = h.pvp(1009, A, B);
        oneSided.getPlayersBuilder(1).setTeamIndex(0);
        // 队伍超编：team 0 六人
        CreateBattleRequest.Builder oversize = h.pveTeam(1010, A, B, C, D, 5005, 5006);

        assertThat(createRaw(oneSided).getErrorMessage().getId()).isEqualTo(1002);
        assertThat(createRaw(oversize).getErrorMessage().getId()).isEqualTo(1002);
        assertNoSideEffects();
        assertThat(creates("engine_reject")).isEqualTo(2);
    }

    @Test
    void 签票失败回1003_零副作用() {
        BattleTickets broken = mock(BattleTickets.class);
        when(broken.sign(any())).thenReturn(ByteString.copyFromUtf8("x")).thenThrow(new IllegalStateException("测试：JCA 故障"));
        h.tickets = broken;

        CreateBattleResponse response = createRaw(h.pveTeam(1011, A, B));

        assertThat(response.getErrorMessage().getId()).isEqualTo(1003);
        assertThat(response.getBattleId()).isEqualTo(1011);
        assertNoSideEffects();
        assertThat(creates("ticket_failed")).isEqualTo(1);
        assertThat(h.counter("xm.battle.tickets", "path", "create", "result", "failed")).isEqualTo(1);
    }

    @Test
    void 期限为0或已过去时取now加192秒_票据与确认同值() throws Exception {
        h.create(h.pve(1012, A).setDeadlineMs(0));
        h.create(h.pve(1013, B).setDeadlineMs(h.now()));
        h.create(h.pve(1014, C).setDeadlineMs(h.now() + 50_000));

        long fallback = h.now() + 192_000;
        assertThat(expireOf(A)).isEqualTo(fallback);
        assertThat(expireOf(B)).as("等于 now 也算已过去").isEqualTo(fallback);
        assertThat(expireOf(C)).isEqualTo(h.now() + 50_000);
        assertThat(h.outs(RoomHarness.Kind.CONFIRM)).extracting(RoomHarness.Out::payload)
                .containsExactly(fallback, fallback, h.now() + 50_000);
        assertThat(h.service().room(1012).deadlineMs).isEqualTo(fallback);
    }

    private long expireOf(long playerId) throws Exception {
        BattleAssignedS2C assigned = RoomHarness.parse(BattleAssignedS2C.parser(), h.lobbyFrames(playerId).get(0));
        long payloadExpire = BattleTicketPayload.parseFrom(assigned.getTokenPayload()).getExpireAtMs();
        assertThat(payloadExpire).isEqualTo(assigned.getExpireAtMs());
        return payloadExpire;
    }

    @Test
    void 快照路由原样抄进名单() {
        h.create(h.pve(1015, A));

        BattleRouting routing = h.service().room(1015).routingByPlayer.get(A);
        assertThat(routing).isEqualTo(RoomHarness.routing(A));
    }
}
