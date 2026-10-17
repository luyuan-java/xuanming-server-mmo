package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.MONSTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.port.BattleResultSink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleTicketRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GlobalResultCommitTest {
    private final RoomHarness h = new RoomHarness();
    private final List<BattleResultEvent> events = new ArrayList<>();
    private final List<CompletableFuture<Void>> acknowledgements = new ArrayList<>();
    private final List<com.google.protobuf.ByteString> originals=new ArrayList<>();

    GlobalResultCommitTest() {
        h.results = new BattleResultSink() {
            public void publish(BattleResultEvent event, Channel channel) {
                events.add(event); // 旧的无确认出口也记录，断言真正检查终局先可见的缺陷。
            }
            public CompletableFuture<Void> publishConfirmed(BattleResultEvent event, Channel channel) {
                events.add(event);
                CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
                acknowledgements.add(acknowledgement);
                return acknowledgement;
            }
            public CompletableFuture<Void> publishConfirmed(BattleResultEvent event,com.google.protobuf.ByteString original,Channel channel) {
                originals.add(original);return publishConfirmed(event,channel);
            }
        };
    }

    @Test
    void 全球终局未收到broker确认时不能通知胜负发结算或删房() {
        h.create(h.pve(42001, A).setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12"));
        var connection = h.connect(42001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.log.clear();
        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(42001)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());

        assertThat(h.service().room(42001)).as("broker 未确认不能删除已经算出胜负的房间").isNotNull();
        assertThat(h.log).as("终局帧和结算必须等待同一原结果的耐久确认").isEmpty();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getSettlementFormatVersion()).isEqualTo(1);
        assertThat(events.get(0).getSettlementsList()).hasSize(1);
        acknowledgements.get(0).complete(null);
        h.scheduler.runPending();
        assertThat(h.service().room(42001)).isNull();
        assertThat(connection.messageIds()).containsSubsequence(139, 150);
        assertThat(h.settlementsOut()).hasSize(1);
        assertThat(h.settlementsOut()).containsExactlyElementsOf(events.get(0).getSettlementsList());
        var end=h.outs(RoomHarness.Kind.FRAME).stream().filter(out->out.messageId()==150).findFirst().orElseThrow();
        assertThat(RoomHarness.parse(com.game.proto.BattleEndS2C.parser(),(com.game.proto.MessageContent)end.payload()).getSettlement())
                .isEqualTo(events.get(0).getSettlements(0));
    }

    private void finishPending(long battleId,boolean activity){
        var request=h.pve(battleId,A).setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12");
        if(activity)request.setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(11).setActivityId(3));
        h.create(request);h.connect(battleId,A,eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);h.log.clear();
        h.service().submit(A,SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE,MONSTER)).build());
    }
    @Test void 失败重投原字节且补拉不泄露尚未确认胜负(){
        finishPending(42002,true);
        assertThat(h.service().getState(A,GetBattleStateRequest.newBuilder().setBattleId(42002).build()).getOutcome())
                .isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        byte[] original=events.get(0).toByteArray();
        acknowledgements.get(0).completeExceptionally(new IllegalStateException("broker结局未知"));h.scheduler.runPending();
        h.scheduler.advance(999);assertThat(events).hasSize(1);assertThat(h.log).isEmpty();
        h.scheduler.advance(1);assertThat(events).hasSize(2);assertThat(events.get(1).toByteArray()).isEqualTo(original);
        assertThat(originals.get(1)).isSameAs(originals.get(0));
        acknowledgements.get(1).complete(null);h.scheduler.runPending();
        assertThat(h.service().room(42002)).isNull();assertThat(h.settlementsOut()).hasSize(1);
    }
    @Test void 待提交Destroy不改写原结局而停机只断连不宣布终态(){
        finishPending(42003,false);h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(42003).build());
        assertThat(h.service().room(42003)).isNotNull();assertThat(h.log).isEmpty();
        h.service().abortAll("shutdown");
        assertThat(h.service().room(42003)).isNull();assertThat(h.outs(RoomHarness.Kind.FRAME)).isEmpty();assertThat(h.settlementsOut()).isEmpty();
        acknowledgements.get(0).complete(null);h.scheduler.runPending();assertThat(h.settlementsOut()).isEmpty();
    }
    @Test void 整场截止DRAW同样等待broker确认(){
        h.create(h.pvp(42004,A,RoomHarness.B).setDeadlineMs(h.now()+1).setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12"));
        h.connect(42004,A,eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);h.log.clear();h.scheduler.advance(1);
        assertThat(h.service().room(42004)).isNotNull();assertThat(h.log).isEmpty();
        assertThat(events.get(0).getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        acknowledgements.get(0).complete(null);h.scheduler.runPending();assertThat(h.service().room(42004)).isNull();
    }
}
