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
        h.create(h.pvp(9914,A,B));var request=original(9914,C);assertFalse(h.service().addObserver(request).hasErrorMessage());
        // 建起直连：观众在场，不受登记宽限回收影响，这里只验 Remove 越过准入截止的语义
        h.connect(9914,C,eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);h.scheduler.advance(30_001);
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

    // ---------------------------------------------------------------- 观众席宽限回收与满员墓碑

    private double reclaimed(String reason){return h.counter("game.battle.observer.reclaimed","reason",reason);}

    @Test void 未建直连到期回收_Query回ABSENT_同UUID不复活(){
        h.create(h.pvp(9918,A,B));var request=original(9918,C);assertFalse(h.service().addObserver(request).hasErrorMessage());
        var query=QueryObserverRequest.newBuilder().setBattleId(9918).setObserverPlayerId(C).setSpectatorOperationId(request.getSpectatorOperationId()).setTargetInstanceId(INSTANCE).build();
        h.scheduler.advance(RoomConstants.OBSERVER_ATTACH_GRACE_MS-1);
        assertTrue(h.service().room(9918).isObserver(C),"宽限内座位保留");assertEquals(0.0,reclaimed("attach_timeout"));
        h.scheduler.advance(1);
        assertFalse(h.service().room(9918).isObserver(C));assertNull(h.service().room(9918).observerOperations.get(C));
        assertEquals(1.0,reclaimed("attach_timeout"));
        assertEquals(ObserverAdmissionState.OBSERVER_ADMISSION_ABSENT,h.service().queryObserver(query).getState());
        assertTrue(h.service().addObserver(request).hasErrorMessage(),"同UUID迟到的Add不能复活");assertFalse(h.service().room(9918).isObserver(C));
        assertTrue(h.service().removeObserver(remove(request)).getCancelled(),"match 恢复补发的原 Remove 照样拿到取消正证明");
    }
    @Test void 断开后宽限内重连不回收(){
        h.create(h.pvp(9919,A,B));h.service().addObserver(original(9919,C));
        var first=h.connect(9919,C,eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.scheduler.advance(RoomConstants.OBSERVER_ATTACH_GRACE_MS);
        assertTrue(h.service().room(9919).isObserver(C),"挂接即撤销登记宽限");
        first.live=false;h.service().detachDirect(9919,C,first);
        h.scheduler.advance(RoomConstants.OBSERVER_RECONNECT_GRACE_MS-1);
        assertTrue(h.service().room(9919).isObserver(C),"断线宽限内座位保留");
        var again=h.connect(9919,C,eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.scheduler.advance(RoomConstants.OBSERVER_RECONNECT_GRACE_MS*2);
        assertTrue(h.service().room(9919).isObserver(C));assertSame(again,h.service().room(9919).directOf(C));assertNull(again.closedWith);
        assertEquals(0.0,reclaimed("attach_timeout"));assertEquals(0.0,reclaimed("reconnect_timeout"));
    }
    @Test void 宽限到期不推166_计reclaimed(){
        h.create(h.pvp(9920,A,B));h.service().addObserver(original(9920,C));
        var link=h.connect(9920,C,eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        link.live=false;h.service().detachDirect(9920,C,link);h.clearLog();
        h.scheduler.advance(RoomConstants.OBSERVER_RECONNECT_GRACE_MS);
        assertFalse(h.service().room(9920).isObserver(C));assertNull(h.service().room(9920).directOf(C));
        assertTrue(h.trace().stream().noneMatch(line->line.endsWith(":166")),"回收不推 166");
        assertEquals(0.0,h.counter("game.battle.pushes","category","battle_frame","route","dropped","message","NotifySpectateEnd"),"连丢弃的 166 也没有构造");
        assertEquals(1.0,reclaimed("reconnect_timeout"));assertEquals(0.0,reclaimed("attach_timeout"));
        assertNull(link.closedWith,"对端已断开，服务端不再关它");
    }
    @Test void Stop或Remove后旧计时器不误摘新操作(){
        h.create(h.pvp(9921,A,B));h.service().addObserver(original(9921,C));
        h.scheduler.advance(10_000);
        assertFalse(h.service().stopWatch(C,StopWatchBattleRequest.newBuilder().setBattleId(9921).build()).hasErrorMessage());
        var second=original(9921,C);assertFalse(h.service().addObserver(second).hasErrorMessage());
        h.scheduler.advance(RoomConstants.OBSERVER_ATTACH_GRACE_MS-10_000);
        assertTrue(h.service().room(9921).isObserver(C),"越过第一个操作的原到期点：StopWatch 已撤它的计时器");
        assertEquals(second.getSpectatorOperationId(),h.service().room(9921).observerOperations.get(C));
        assertTrue(h.service().removeObserver(remove(second)).getCancelled());
        var third=original(9921,C);assertFalse(h.service().addObserver(third).hasErrorMessage());
        h.scheduler.advance(10_000);
        assertTrue(h.service().room(9921).isObserver(C),"越过第二个操作的原到期点：Remove 已撤它的计时器");
        assertEquals(third.getSpectatorOperationId(),h.service().room(9921).observerOperations.get(C));assertEquals(0.0,reclaimed("attach_timeout"));
        h.scheduler.advance(RoomConstants.OBSERVER_ATTACH_GRACE_MS);
        assertFalse(h.service().room(9921).isObserver(C),"只有当前操作自己的宽限到期才回收");assertEquals(1.0,reclaimed("attach_timeout"));
    }
    @Test void 满员拒绝保留墓碑_有空位后同op迟到的Add也不准入(){
        h.create(h.pvp(9922,A,B));
        for(long observer=6000;observer<6000+RoomConstants.MAX_OBSERVERS_PER_ROOM;observer++)assertFalse(h.service().addObserver(original(9922,observer)).hasErrorMessage());
        var late=original(9922,C);
        assertEquals(BattleRoomServiceImpl.SPECTATE_FULL,h.service().addObserver(late).getErrorMessage().getId());
        assertFalse(h.service().stopWatch(6000,StopWatchBattleRequest.newBuilder().setBattleId(9922).build()).hasErrorMessage());
        assertTrue(h.service().addObserver(late).hasErrorMessage(),"拒绝即终态：同 op 不能翻成准入");assertFalse(h.service().room(9922).isObserver(C));
        assertTrue(h.service().removeObserver(remove(late)).getCancelled());
        assertFalse(h.service().addObserver(original(9922,C)).hasErrorMessage(),"墓碑只封原操作：新操作有空位照常准入");
    }
}
