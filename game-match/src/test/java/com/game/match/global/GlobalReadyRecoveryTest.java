package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.game.api.BattleNodeService;
import com.game.proto.*;
import com.game.api.rpc.NodeRpcClients;
import com.game.match.global.pb.*;
import com.game.match.id.MatchIds;
import com.game.match.port.NodeCalls;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GlobalReadyRecoveryTest {
    private static GlobalTicketRow ticket(long player,long battle){return GlobalTicketRow.newBuilder().setPlayerId(player).setTicket("ticket-"+player).setFormationId(battle).setEnqueuedMs(player).setState(3).build();}
    private static GlobalFormationRow formation(GlobalTicketRow ticket){return GlobalFormationRow.newBuilder().setBattleId(ticket.getFormationId()).setState(4)
            .setHost("original-battle").setPort(21200).setInstanceId("original-instance").setOperationId("original-operation")
            .addMembers(GlobalMember.newBuilder().setPlayerId(ticket.getPlayerId()).setTicket(ticket.getTicket())).build();}
    private static NodeCalls<BattleNodeService> calls(BattleNodeService battle) {
        return new NodeCalls<>() {
            @Override public <R> CompletableFuture<R> call(NodeRpcClients.Target target,Duration timeout,Function<BattleNodeService,CompletableFuture<R>> invoke) {
                assertEquals("original-battle",target.host());assertEquals("original-instance",target.instanceId());return invoke.apply(battle);
            }
        };
    }
    @Test void 固定执行端缺席须先落原取消墓碑才释放_活房和未知均保留() {
        for(var state:List.of(BattleCreationState.BATTLE_CREATION_PRESENT,BattleCreationState.BATTLE_CREATION_UNKNOWN,
                BattleCreationState.BATTLE_CREATION_ABSENT,BattleCreationState.BATTLE_CREATION_ABORTED)) {
            var store=mock(GlobalMatchStore.class);var battle=mock(BattleNodeService.class);var ticket=ticket(1,101);var f=formation(ticket);
            var actual=new java.util.concurrent.atomic.AtomicReference<>(state);
            when(store.formation(101)).thenReturn(f);
            when(battle.queryBattleCreation(any())).thenAnswer(call->{QueryBattleCreationRequest request=call.getArgument(0);
                assertEquals("original-operation",request.getPlacementOperationId());assertEquals("original-instance",request.getTargetInstanceId());
                return CompletableFuture.completedFuture(QueryBattleCreationResponse.newBuilder().setState(actual.get()).build());});
            when(battle.destroyBattle(any())).thenAnswer(call->{DestroyBattleRequest request=call.getArgument(0);
                assertEquals("original-operation",request.getPlacementOperationId());assertEquals("original-instance",request.getTargetInstanceId());
                assertEquals("global_match_abort_if_absent",request.getReason());actual.set(BattleCreationState.BATTLE_CREATION_ABORTED);return CompletableFuture.completedFuture(Empty.getDefaultInstance());});
            try(var service=new GlobalMatchService(store,GlobalRegionPolicyTest.CONFIG,null,null,null,null,null,calls(battle),null,null,null)) {
                boolean ended=state==BattleCreationState.BATTLE_CREATION_ABSENT||state==BattleCreationState.BATTLE_CREATION_ABORTED;
                assertEquals(ended,service.releaseIfEnded(ticket));
                verify(store,times(ended?1:0)).releaseReady(1,"ticket-1",101);
                verify(battle,times(state==BattleCreationState.BATTLE_CREATION_ABSENT?1:0)).destroyBattle(any());
            }
        }
    }
    @Test void ABSENT但取消回包后仍无ABORTED正证明_不能提前释放() {
        var store=mock(GlobalMatchStore.class);var battle=mock(BattleNodeService.class);var ticket=ticket(1,101);
        when(store.formation(101)).thenReturn(formation(ticket));
        when(battle.queryBattleCreation(any())).thenReturn(CompletableFuture.completedFuture(QueryBattleCreationResponse.newBuilder().setState(BattleCreationState.BATTLE_CREATION_ABSENT).build()));
        when(battle.destroyBattle(any())).thenReturn(CompletableFuture.completedFuture(Empty.getDefaultInstance()));
        try(var service=new GlobalMatchService(store,GlobalRegionPolicyTest.CONFIG,null,null,null,null,null,calls(battle),null,null,null)) {
            assertFalse(service.releaseIfEnded(ticket));verify(battle).destroyBattle(any());verify(store,never()).releaseReady(anyLong(),anyString(),anyLong());
        }
    }
    @Test void 前两张长期活房或未知票不挡后续离线结束票()throws Exception {
        var store=mock(GlobalMatchStore.class);var battle=mock(BattleNodeService.class);var ids=mock(MatchIds.class);
        var first=ticket(1,101);var second=ticket(2,102);var third=ticket(3,103);
        when(store.readyCandidates(0,0)).thenReturn(List.of(first,second,third));when(store.readyCandidates(2,2)).thenReturn(List.of(third));
        when(store.formation(101)).thenReturn(formation(first));when(store.formation(102)).thenReturn(formation(second));when(store.formation(103)).thenReturn(formation(third));
        var checked=new CountDownLatch(2);var released=new CountDownLatch(1);
        when(battle.queryBattleCreation(any())).thenAnswer(call->{QueryBattleCreationRequest request=call.getArgument(0);long id=request.getBattleId();
            if(id!=103)checked.countDown();return CompletableFuture.completedFuture(QueryBattleCreationResponse.newBuilder().setState(id==101?
                    BattleCreationState.BATTLE_CREATION_PRESENT:id==102?BattleCreationState.BATTLE_CREATION_UNKNOWN:BattleCreationState.BATTLE_CREATION_ABORTED).build());});
        doAnswer(call->{released.countDown();return null;}).when(store).releaseReady(3,"ticket-3",103);
        try(var service=new GlobalMatchService(store,GlobalRegionPolicyTest.CONFIG,null,null,ids,null,null,calls(battle),null,null,null)) {
            service.round();assertTrue(checked.await(3,TimeUnit.SECONDS));service.round();assertTrue(released.await(3,TimeUnit.SECONDS));
            verify(store,never()).releaseReady(1,"ticket-1",101);verify(store,never()).releaseReady(2,"ticket-2",102);
            verify(store).releaseReady(3,"ticket-3",103);
        }
    }
}
