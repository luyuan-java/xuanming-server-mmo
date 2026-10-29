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
import com.game.proto.CreateBattleRequest;
import com.game.proto.match.BattleOutcomeDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GlobalResultCommitTest {
    private final RoomHarness h = new RoomHarness();
    private final List<BattleResultEvent> events = new ArrayList<>();
    private final List<CompletableFuture<Void>> acknowledgements = new ArrayList<>();
    private final List<com.google.protobuf.ByteString> originals=new ArrayList<>();
    private final List<CompletableFuture<BattleOutcomeDecision>> sqlDecisions = new ArrayList<>();
    private final List<CreateBattleRequest> sqlCreates = new ArrayList<>();
    private final List<BattleResultEvent> sqlEvents = new ArrayList<>();
    private final List<com.google.protobuf.ByteString> sqlOriginals = new ArrayList<>();

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
            public CompletableFuture<BattleOutcomeDecision> commitOutcome(CreateBattleRequest create, BattleResultEvent event,
                                                                          com.google.protobuf.ByteString original) {
                sqlCreates.add(create); sqlEvents.add(event); sqlOriginals.add(original);
                var decision = new CompletableFuture<BattleOutcomeDecision>(); sqlDecisions.add(decision); return decision;
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

    private CreateBattleRequest.Builder v1(long battleId) {
        return h.pve(battleId, A).setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12")
                .setTargetInstanceId(RoomHarness.INSTANCE).setOutcomeProtocolVersion(1);
    }

    private void resolveV1(long battleId) {
        h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER)).build());
    }

    @Test void 停服立即补交非在途原件_COMPLETE后排空且原字节不变(){
        h.create(v1(42201));h.connect(42201,A,eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);resolveV1(42201);
        sqlDecisions.getFirst().completeExceptionally(new IllegalStateException("COMMIT回包丢失"));h.scheduler.runPending();h.log.clear();
        var drained=h.service().beginShutdown("停服",java.time.Duration.ofSeconds(3));
        assertThat(drained).isNotDone();assertThat(sqlDecisions).hasSize(2);
        assertThat(sqlCreates.get(1)).isSameAs(sqlCreates.getFirst());assertThat(sqlOriginals.get(1)).isSameAs(sqlOriginals.getFirst());
        assertThat(sqlEvents.get(1)).isSameAs(sqlEvents.getFirst());
        sqlDecisions.get(1).complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE);h.scheduler.runPending();
        assertThat(drained.join().unconfirmed()).isEmpty();assertThat(h.service().room(42201)).isNull();
        h.scheduler.advance(30_000);assertThat(sqlDecisions).hasSize(2);assertThat(h.settlementsOut()).isEmpty();assertThat(events).isEmpty();
        assertThat(h.outs(RoomHarness.Kind.FRAME)).isEmpty();
    }

    @Test void 停服不打断在途提交_失败后继续原件重试_ABORT同样完成排空(){
        h.create(v1(42202));resolveV1(42202);h.log.clear();
        var drained=h.service().beginShutdown("停服",java.time.Duration.ofSeconds(3));
        assertThat(sqlDecisions).hasSize(1);assertThat(drained).isNotDone();
        sqlDecisions.getFirst().completeExceptionally(new IllegalStateException("网络断开不能证明回滚"));h.scheduler.runPending();
        h.scheduler.advance(999);assertThat(sqlDecisions).hasSize(1);
        h.scheduler.advance(1);assertThat(sqlDecisions).hasSize(2);assertThat(sqlOriginals.get(1)).isSameAs(sqlOriginals.getFirst());
        sqlDecisions.get(1).complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_ABORT);h.scheduler.runPending();
        assertThat(drained.join().unconfirmed()).isEmpty();assertThat(h.service().room(42202)).isNull();
        assertThat(h.outs(RoomHarness.Kind.FRAME)).isEmpty();assertThat(h.settlementsOut()).isEmpty();
    }

    @Test void 预算耗尽逐局保留UNKNOWN原身份_迟到回包不得产生公开终局(){
        h.create(v1(42203));h.create(v1(42204));resolveV1(42203);resolveV1(42204);h.log.clear();
        var drained=h.service().beginShutdown("停服",java.time.Duration.ofMillis(50));
        assertThat(h.service().attachDirect(42203,A,eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT,h.new FakeLink(A))).isEmpty();
        assertThat(h.service().issueBattleTicket(com.game.proto.IssueBattleTicketRequest.newBuilder().setBattleId(42203).setPlayerId(A).build()).hasErrorMessage()).isTrue();
        assertThat(h.service().addObserver(com.game.proto.AddObserverRequest.newBuilder().setBattleId(42203).setObserverPlayerId(RoomHarness.B).build()).hasErrorMessage()).isTrue();
        assertThat(h.service().beginShutdown("重复停服",java.time.Duration.ofSeconds(30))).isSameAs(drained);
        h.scheduler.advance(49);assertThat(drained).isNotDone();h.scheduler.advance(1);
        assertThat(drained.join().unconfirmed()).extracting(BattleRoomService.UnconfirmedOutcome::battleId)
                .containsExactlyInAnyOrder(42203L,42204L);
        assertThat(drained.join().unconfirmed()).allSatisfy(p->{assertThat(p.operationId()).isEqualTo("902df74e-3843-48e0-bd72-05ed824a4f12");
            assertThat(p.instanceId()).isEqualTo(RoomHarness.INSTANCE);assertThat(p.inFlight()).isTrue();});
        assertThat(h.service().room(42203)).isNotNull();
        sqlDecisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE);
        sqlDecisions.get(1).completeExceptionally(new IllegalStateException("退出后未知"));h.scheduler.runPending();h.scheduler.advance(30_000);
        assertThat(sqlDecisions).hasSize(2);assertThat(h.outs(RoomHarness.Kind.FRAME)).isEmpty();assertThat(h.settlementsOut()).isEmpty();
        h.service().abortAll("拆房");assertThat(h.service().roomCount()).isZero();
        assertThat(drained.join().unconfirmed()).hasSize(2);
    }

    @Test void v1SQLCOMPLETE前所有公开面保持非终态_COMPLETE后不走旧个人直投或Kafka出口() {
        var original = v1(42101).build();
        var response = h.create(original.toBuilder());
        assertThat(response.getAdoptedOutcomeProtocolVersion()).isEqualTo(1);
        assertThat(response.getTargetInstanceId()).isEqualTo(RoomHarness.INSTANCE);
        var direct = h.connect(42101, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT); h.log.clear();
        resolveV1(42101);
        assertThat(sqlCreates).containsExactly(original);
        assertThat(sqlEvents).hasSize(1);
        assertThat(h.service().getState(A, GetBattleStateRequest.newBuilder().setBattleId(42101).build()).getOutcome())
                .isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        var observer = h.addObserver(com.game.proto.AddObserverRequest.newBuilder().setBattleId(42101)
                .setObserverPlayerId(RoomHarness.B).setRouting(RoomHarness.observerRouting(RoomHarness.B, 222)).build());
        assertThat(observer.hasErrorMessage()).isFalse();
        var viewer = h.connect(42101, RoomHarness.B, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(h.service().getState(RoomHarness.B, GetBattleStateRequest.newBuilder().setBattleId(42101).build()).getOutcome())
                .isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(direct.messageIds()).doesNotContain(139, 150);
        assertThat(viewer.messageIds()).doesNotContain(139, 150);
        assertThat(h.settlementsOut()).isEmpty(); assertThat(events).isEmpty();
        sqlDecisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE); h.scheduler.runPending();
        assertThat(h.service().room(42101)).isNull();
        assertThat(direct.messageIds()).contains(139).doesNotContain(150);
        assertThat(h.settlementsOut()).isEmpty();
        assertThat(events).as("全局Kafka由Match同一SQL来源重发").isEmpty();
        assertThat(h.outs(RoomHarness.Kind.ACTIVITY_RESULT)).isEmpty();
    }

    @Test void v1提交失败重发完全相同来源_ABORT不发终局或个人效果() {
        h.create(v1(42102));
        var direct = h.connect(42102, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT); h.log.clear(); resolveV1(42102);
        sqlDecisions.getFirst().completeExceptionally(new IllegalStateException("SQL结局未知")); h.scheduler.runPending();
        h.scheduler.advance(1000);
        assertThat(sqlCreates).hasSize(2); assertThat(sqlCreates.get(1)).isSameAs(sqlCreates.getFirst());
        assertThat(sqlOriginals.get(1)).isSameAs(sqlOriginals.getFirst());
        assertThat(sqlEvents.get(1)).isSameAs(sqlEvents.getFirst());
        assertThat(direct.messageIds()).doesNotContain(139, 150); assertThat(h.settlementsOut()).isEmpty();
        sqlDecisions.get(1).complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_ABORT); h.scheduler.runPending();
        assertThat(h.service().room(42102)).isNull();
        assertThat(direct.messageIds()).doesNotContain(139, 150); assertThat(h.settlementsOut()).isEmpty(); assertThat(events).isEmpty();
    }

    @Test void v1待提交Destroy和停机不提前泄露终局_迟到COMPLETE不回旧个人出口() {
        h.create(v1(42103)); h.connect(42103, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT); h.log.clear(); resolveV1(42103);
        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(42103).build());
        assertThat(h.service().room(42103)).isNotNull(); assertThat(h.log).isEmpty();
        h.service().abortAll("停机");
        sqlDecisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE); h.scheduler.runPending();
        assertThat(h.service().room(42103)).isNull(); assertThat(h.outs(RoomHarness.Kind.FRAME)).isEmpty();
        assertThat(h.settlementsOut()).isEmpty(); assertThat(events).isEmpty();
    }

    @Test void v1截止DRAW也必须等SQLCOMPLETE_旧端口默认实现拒绝而不是假确认() {
        h.create(v1(42104).setDeadlineMs(h.now() + 1)); h.connect(42104, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT); h.log.clear();
        h.scheduler.advance(1);
        assertThat(sqlEvents.getFirst().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(h.log).isEmpty(); assertThat(h.service().room(42104)).isNotNull();
        BattleResultSink unsupported = (event, channel) -> { };
        assertThat(unsupported.commitOutcome(sqlCreates.getFirst(), sqlEvents.getFirst(), sqlOriginals.getFirst())).isCompletedExceptionally();
        sqlDecisions.getFirst().complete(BattleOutcomeDecision.BATTLE_OUTCOME_DECISION_COMPLETE); h.scheduler.runPending();
        assertThat(h.service().room(42104)).isNull(); assertThat(h.settlementsOut()).isEmpty();
    }

    @Test void 能力支持与真实adopted回执分开_旧房间不能被support1追认() {
        var ledger = new com.game.battle.rpc.CreationLedger(new com.game.battle.GlobalBattleProperties(true, 1, RoomHarness.SECRET), h.identity);
        var query = com.game.proto.QueryBattleCreationRequest.newBuilder().setBattleId(42105)
                .setPlacementOperationId("902df74e-3843-48e0-bd72-05ed824a4f12").setTargetInstanceId(RoomHarness.INSTANCE).build();
        var absent = ledger.query(query, false);
        assertThat(absent.getState()).isEqualTo(com.game.proto.BattleCreationState.BATTLE_CREATION_ABSENT);
        assertThat(absent.getTargetInstanceId()).isEqualTo(RoomHarness.INSTANCE);
        assertThat(absent.getSupportedOutcomeProtocolVersion()).isEqualTo(1);
        assertThat(absent.getAdoptedOutcomeProtocolVersion()).isZero();
        ledger.created(v1(42105).setOutcomeProtocolVersion(0).build());
        var old = ledger.query(query, true);
        assertThat(old.getState()).isEqualTo(com.game.proto.BattleCreationState.BATTLE_CREATION_PRESENT);
        assertThat(old.getSupportedOutcomeProtocolVersion()).isEqualTo(1); assertThat(old.getAdoptedOutcomeProtocolVersion()).isZero();
        ledger.created(v1(42105).build());
        assertThat(ledger.query(query, true).getAdoptedOutcomeProtocolVersion()).isEqualTo(1);
        var wrong = ledger.query(query.toBuilder().setTargetInstanceId("另一个执行实例").build(), true);
        assertThat(wrong.getState()).isEqualTo(com.game.proto.BattleCreationState.BATTLE_CREATION_UNKNOWN);
        assertThat(wrong.getSupportedOutcomeProtocolVersion()).isZero();
    }
}
