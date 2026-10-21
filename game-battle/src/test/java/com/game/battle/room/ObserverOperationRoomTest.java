package com.game.battle.room;

import static org.junit.jupiter.api.Assertions.*;
import static com.game.battle.room.RoomHarness.*;
import com.game.proto.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 真房间入口覆盖取消原件、迟到回包和房间结束；原件由测试显式构造，不在被测服务补齐。 */
class ObserverOperationRoomTest {
    private final RoomHarness h=new RoomHarness();
    private AddObserverRequest original(long battle,long player){return AddObserverRequest.newBuilder().setBattleId(battle).setObserverPlayerId(player).setObserverName("原观众")
            .setRouting(observerRouting(player,900)).setSpectatorOperationId(UUID.randomUUID().toString()).setAdmitBeforeMs(h.now()+30_000).setTargetInstanceId(INSTANCE).build();}
    private static RemoveObserverRequest remove(AddObserverRequest a){return RemoveObserverRequest.newBuilder().setBattleId(a.getBattleId()).setObserverPlayerId(a.getObserverPlayerId())
            .setSpectatorOperationId(a.getSpectatorOperationId()).setAdmitBeforeMs(a.getAdmitBeforeMs()).setTargetInstanceId(a.getTargetInstanceId()).build();}
    @Test void 房间不存在也必须先留墓碑_迟到Add不能在之后建出的房中登记(){
        var request=original(9911,C);var proof=h.service().removeObserver(remove(request));assertTrue(proof.getCancelled());assertEquals(request.getSpectatorOperationId(),proof.getSpectatorOperationId());assertEquals(INSTANCE,proof.getTargetInstanceId());
        h.create(h.pvp(9911,A,B));assertTrue(h.service().addObserver(request).hasErrorMessage());assertFalse(h.service().room(9911).isObserver(C));
    }
    @Test void 同actor新op不顶旧op_旧Remove不移新op_同UUID任何原文改变拒绝(){
        h.create(h.pvp(9912,A,B));var first=original(9912,C);assertFalse(h.service().addObserver(first).hasErrorMessage());
        assertTrue(h.service().addObserver(first.toBuilder().setObserverName("改变").build()).hasErrorMessage());var competing=original(9912,C);assertTrue(h.service().addObserver(competing).hasErrorMessage());assertEquals(first.getSpectatorOperationId(),h.service().room(9912).observerOperations.get(C));
        assertTrue(h.service().removeObserver(remove(first)).getCancelled());var next=original(9912,C);assertFalse(h.service().addObserver(next).hasErrorMessage());
        assertTrue(h.service().removeObserver(remove(first)).getCancelled());assertTrue(h.service().room(9912).isObserver(C));assertEquals(next.getSpectatorOperationId(),h.service().room(9912).observerOperations.get(C));assertTrue(h.service().addObserver(first).hasErrorMessage());
    }
    @Test void StopWatch及房间结束都先取消原操作_旧Add不能复活_错误实例不回成功(){
        h.create(h.pvp(9913,A,B));var request=original(9913,C);assertFalse(h.service().addObserver(request).hasErrorMessage());
        assertFalse(h.service().removeObserver(remove(request).toBuilder().setTargetInstanceId("重启实例").build()).getCancelled());assertTrue(h.service().room(9913).isObserver(C));
        assertFalse(h.service().stopWatch(C,StopWatchBattleRequest.newBuilder().setBattleId(9913).build()).hasErrorMessage());assertTrue(h.service().addObserver(request).hasErrorMessage());
        var next=original(9913,C);assertFalse(h.service().addObserver(next).hasErrorMessage());h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(9913).build());h.create(h.pvp(9913,A,B));assertTrue(h.service().addObserver(next).hasErrorMessage());assertFalse(h.service().room(9913).isObserver(C));
    }
    @Test void Remove超过原准入截止仍须清当前同操作_缺原字段不得伪造取消证明(){
        h.create(h.pvp(9914,A,B));var request=original(9914,C);assertFalse(h.service().addObserver(request).hasErrorMessage());h.scheduler.advance(30_001);
        assertTrue(h.service().room(9914).isObserver(C));assertFalse(h.service().removeObserver(RemoveObserverRequest.newBuilder().setBattleId(9914).setObserverPlayerId(C).build()).getCancelled());assertTrue(h.service().room(9914).isObserver(C));
        assertTrue(h.service().removeObserver(remove(request)).getCancelled());assertFalse(h.service().room(9914).isObserver(C));assertTrue(h.service().addObserver(request).hasErrorMessage());
    }
    @Test void 旧观战票不可挂同角色新操作_补签必须提交原op和固定实例()throws Exception{
        h.create(h.pvp(9915,A,B));var original=original(9915,C);assertFalse(h.service().addObserver(original).hasErrorMessage());
        var issue=IssueBattleTicketRequest.newBuilder().setBattleId(9915).setPlayerId(C).setSpectatorOperationId(original.getSpectatorOperationId()).setTargetInstanceId(INSTANCE).build();
        var oldTicket=h.service().issueBattleTicket(issue);assertFalse(oldTicket.hasErrorMessage());var oldPayload=BattleTicketPayload.parseFrom(oldTicket.getAssignment().getTokenPayload());assertEquals(original.getSpectatorOperationId(),oldPayload.getSpectatorOperationId());
        assertTrue(h.service().removeObserver(remove(original)).getCancelled());var next=original(9915,C);assertFalse(h.service().addObserver(next).hasErrorMessage());
        var link=h.new FakeLink(C);assertTrue(h.service().attachDirect(oldPayload,link).isEmpty());assertTrue(h.service().attachDirect(9915,C,eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER,link).isEmpty());assertNull(h.service().room(9915).directOf(C));
        assertTrue(h.service().issueBattleTicket(issue).hasErrorMessage());assertTrue(h.service().issueBattleTicket(issue.toBuilder().clearSpectatorOperationId().build()).hasErrorMessage());
        var nextIssue=issue.toBuilder().setSpectatorOperationId(next.getSpectatorOperationId());assertTrue(h.service().issueBattleTicket(nextIssue.clone().setTargetInstanceId("别的实例").build()).hasErrorMessage());
        var fresh=h.service().issueBattleTicket(nextIssue.build());assertFalse(fresh.hasErrorMessage());assertTrue(h.service().attachDirect(BattleTicketPayload.parseFrom(fresh.getAssignment().getTokenPayload()),link).isPresent());
        assertTrue(h.service().issueBattleTicket(issue.toBuilder().setPlayerId(A).build()).hasErrorMessage());
    }
    @Test void QueryObserver固定实例原操作才有结论_ABSENT不等于取消墓碑(){
        var original=original(9916,C);var query=QueryObserverRequest.newBuilder().setBattleId(9916).setObserverPlayerId(C).setSpectatorOperationId(original.getSpectatorOperationId()).setTargetInstanceId(INSTANCE).build();
        assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_UNKNOWN,h.service().queryObserver(query.toBuilder().setTargetInstanceId("另一个实例").build()).getState());assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_UNKNOWN,h.service().queryObserver(query.toBuilder().clearSpectatorOperationId().build()).getState());
        assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_ABSENT,h.service().queryObserver(query).getState());h.create(h.pvp(9916,A,B));assertFalse(h.service().addObserver(original).hasErrorMessage());assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_ACTIVE,h.service().queryObserver(query).getState());
        h.service().stopWatch(C,StopWatchBattleRequest.newBuilder().setBattleId(9916).build());var absent=h.service().queryObserver(query);assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_ABSENT,absent.getState());assertEquals(INSTANCE,absent.getTargetInstanceId());assertEquals(original.getSpectatorOperationId(),absent.getSpectatorOperationId());assertTrue(h.service().removeObserver(remove(original)).getCancelled());assertTrue(h.service().addObserver(original).hasErrorMessage());
    }
    @Test void 关闭尚未执行的旧直连不能使用同角色新槽_同op重连也按连接身份隔离()throws Exception{
        h.create(h.pvp(9917,A,B));var first=original(9917,C);h.service().addObserver(first);
        var issued=h.service().issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(9917).setPlayerId(C).setSpectatorOperationId(first.getSpectatorOperationId()).setTargetInstanceId(INSTANCE).build());
        var payload=BattleTicketPayload.parseFrom(issued.getAssignment().getTokenPayload());var old=h.new FakeLink(C);var fresh=h.new FakeLink(C);
        assertTrue(h.service().attachDirect(payload,old).isPresent());assertTrue(h.service().currentDirect(payload,old));
        assertTrue(h.service().attachDirect(payload,fresh).isPresent());assertFalse(h.service().currentDirect(payload,old));assertTrue(h.service().currentDirect(payload,fresh));
        h.service().removeObserver(remove(first));assertFalse(h.service().currentDirect(payload,fresh));var next=original(9917,C);h.service().addObserver(next);
        assertFalse(h.service().currentDirect(payload,old));assertFalse(h.service().currentDirect(payload,fresh));assertTrue(h.service().room(9917).isObserver(C));
    }
}
