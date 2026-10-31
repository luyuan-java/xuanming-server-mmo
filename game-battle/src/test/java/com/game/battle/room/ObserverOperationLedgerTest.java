package com.game.battle.room;

import static org.junit.jupiter.api.Assertions.*;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleRouting;
import com.game.proto.RemoveObserverRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 原操作账本的确定时钟回归；没有真实睡眠或网络。 */
class ObserverOperationLedgerTest {
    private static final String INSTANCE="原实例";
    private final AtomicLong clock=new AtomicLong(1_000_000);
    private AddObserverRequest add(long player){return AddObserverRequest.newBuilder().setBattleId(7).setObserverPlayerId(player).setObserverName("原名字")
            .setRouting(BattleRouting.newBuilder().setGateInstanceId("原gate").setSessionId(9)).setSpectatorOperationId(UUID.randomUUID().toString()).setAdmitBeforeMs(clock.get()+30_000).setTargetInstanceId(INSTANCE).build();}
    private static RemoveObserverRequest remove(AddObserverRequest add){return RemoveObserverRequest.newBuilder().setBattleId(add.getBattleId()).setObserverPlayerId(add.getObserverPlayerId()).setSpectatorOperationId(add.getSpectatorOperationId()).setAdmitBeforeMs(add.getAdmitBeforeMs()).setTargetInstanceId(add.getTargetInstanceId()).build();}
    @Test void 先取消再Add_原完整内容只能绑定一次_改房角色截止实例均拒绝(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,3);var request=add(1);assertTrue(ledger.cancel(remove(request)));assertFalse(ledger.begin(request));
        assertThrows(IllegalArgumentException.class,()->ledger.begin(request.toBuilder().setObserverName("篡改").build()));
        assertThrows(IllegalArgumentException.class,()->ledger.cancel(remove(request).toBuilder().setBattleId(8).build()));
        assertThrows(IllegalArgumentException.class,()->ledger.cancel(remove(request).toBuilder().setObserverPlayerId(2).build()));
        assertThrows(IllegalArgumentException.class,()->ledger.cancel(remove(request).toBuilder().setAdmitBeforeMs(request.getAdmitBeforeMs()+1).build()));
        assertThrows(IllegalArgumentException.class,()->ledger.cancel(remove(request).toBuilder().setTargetInstanceId("重启实例").build()));
    }
    @Test void UUID缺失零号别名_过去和超过30秒的Add均拒绝(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,3);var request=add(1);
        for(String operation:new String[]{"","not-uuid","00000000-0000-0000-0000-000000000000","1-1-1-1-1"})assertThrows(IllegalArgumentException.class,()->ledger.begin(request.toBuilder().setSpectatorOperationId(operation).build()));
        assertThrows(IllegalArgumentException.class,()->ledger.begin(request.toBuilder().setAdmitBeforeMs(clock.get()).build()));
        assertThrows(IllegalArgumentException.class,()->ledger.begin(request.toBuilder().setAdmitBeforeMs(clock.get()+30_001).build()));
        assertEquals(0,ledger.size());
    }
    @Test void 满取消表不能淘汰有效墓碑_过期后仅清非活动项_时钟回退不重开窗口(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,1);var first=add(1);ledger.cancel(remove(first));assertThrows(ObserverOperationLedger.Full.class,()->ledger.cancel(remove(add(2))));assertFalse(ledger.begin(first));
        clock.addAndGet(30_001);assertEquals(0,ledger.size());clock.addAndGet(-20_000);assertThrows(IllegalArgumentException.class,()->ledger.begin(first));
    }
    @Test void 活动操作过原截止仍保留可取消_不能靠清过期项丢失房内身份(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,1);var original=add(1);assertTrue(ledger.begin(original));ledger.activate(original.getSpectatorOperationId());clock.addAndGet(30_001);assertEquals(1,ledger.size());
        assertTrue(ledger.cancel(remove(original)));assertThrows(ObserverOperationLedger.Full.class,()->ledger.begin(add(2)));ledger.detached(original.getSpectatorOperationId());assertEquals(0,ledger.size());assertTrue(ledger.begin(add(2)));
    }
    @Test void 首次拒绝码先写为准_取消不抹掉_被Remove封闭的没有记录_未登记的操作不能记拒绝(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,3);var request=add(1);String operation=request.getSpectatorOperationId();
        assertTrue(ledger.begin(request));assertEquals(0,ledger.firstRejection(operation));
        ledger.reject(operation,16021);assertFalse(ledger.begin(request),"拒绝即终态");assertEquals(16021,ledger.firstRejection(operation));
        ledger.reject(operation,1003);assertEquals(16021,ledger.firstRejection(operation),"先写为准");
        assertTrue(ledger.cancel(remove(request)));assertEquals(16021,ledger.firstRejection(operation),"恢复循环补发的 Remove 不抹掉首次结论");
        var removedFirst=add(2);assertTrue(ledger.cancel(remove(removedFirst)));assertFalse(ledger.begin(removedFirst));assertEquals(0,ledger.firstRejection(removedFirst.getSpectatorOperationId()));
        assertThrows(IllegalStateException.class,()->ledger.reject(UUID.randomUUID().toString(),1003));
    }
    @Test void 过期堆每次最多处理128项_不会在一个请求遍扫全部取消表(){
        var ledger=new ObserverOperationLedger(INSTANCE,clock::get,130);for(int p=1;p<=130;p++)ledger.cancel(remove(add(p)));clock.addAndGet(30_001);
        assertEquals(2,ledger.size());assertEquals(0,ledger.size());
    }
}
