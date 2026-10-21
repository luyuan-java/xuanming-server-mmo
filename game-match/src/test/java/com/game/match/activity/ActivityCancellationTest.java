package com.game.match.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.deadline.Deadline;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.global.GlobalMatchService;
import com.game.match.global.GlobalMatchStore;
import com.game.match.global.pb.GlobalDirectIntentRow;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.*;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 对外Cancel入口验证完整原件与可信回包；真正PK互锁另由TiDB回归验证。 */
class ActivityCancellationTest {
    private static final String SECRET="activity-cancel-fixture-secret-0000000000001";
    private static final String METHOD=MatchInternalServiceImpl.CANCEL_METHOD;
    private final MatchProperties properties=new MatchProperties(null,null,null,null,null,null,null,null,null,null,null,null,null);
    private StartActivityBattleRequest original() {
        return StartActivityBattleRequest.newBuilder().setRequestId(701).setBattleConfigId(1).addMemberPlayerIds(11)
            .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(7).setActivityId(1).setPeriodKey(2).setGuildPeriodKey(3).setInitiatorPlayerId(11).setLaunchRequestId(701)).build();
    }
    private CancelActivityBattleRequest signed() {
        return ServiceCallSignatures.sign(METHOD,CancelActivityBattleRequest.newBuilder().setOriginal(original()).build(),
            "guild",SECRET,System.currentTimeMillis());
    }
    @Test void 篡改完整原Start或缺密钥拒绝_不进工作池和SQL() {
        var service=mock(ActivityBattleService.class);MatchWorkers workers=mock(MatchWorkers.class);
        var provider=new MatchInternalServiceImpl(service,workers,properties,SECRET);
        var changed=signed().toBuilder().setOriginal(original().toBuilder().setBattleConfigId(2)).build();
        assertThrows(CompletionException.class,()->provider.cancelActivityBattle(changed).join());
        assertThrows(CompletionException.class,()->new MatchInternalServiceImpl(service,workers,properties,null).cancelActivityBattle(signed()).join());
        verifyNoInteractions(service,workers);
    }
    @Test void 取消回包签match且绑定原摘要_队列拒收不伪造成功墓碑() {
        var service=mock(ActivityBattleService.class);var request=signed();
        when(service.cancel(eq(request),any())).thenReturn(CancelActivityBattleResponse.newBuilder().setRequestCancelled(true)
            .setRequestDigest(ServiceCallSignatures.digest(request)).build());
        var result=new MatchInternalServiceImpl(service,Runnable::run,properties,SECRET).cancelActivityBattle(request).join();
        var verifier=new ServiceCallSignatures(Map.of("match",SECRET),System::currentTimeMillis);
        assertEquals("match",verifier.verify(METHOD+"/reply",result));assertEquals(ServiceCallSignatures.digest(request),result.getRequestDigest());
        assertThrows(SecurityException.class,()->verifier.verify(METHOD+"/reply",result.toBuilder().setBattleId(999).build()));
        MatchWorkers full=task->{throw new RejectedExecutionException("fixture-full");};
        assertThrows(CompletionException.class,()->new MatchInternalServiceImpl(service,full,properties,SECRET).cancelActivityBattle(request).join());
        verify(service,times(1)).cancel(eq(request),any());
    }
    @Test void 已成局取消只返回原battle和真实state_取消墓碑的Start不可绕过预检重放() {
        var global=mock(GlobalMatchService.class);var store=mock(GlobalMatchStore.class);when(global.store()).thenReturn(store);
        var precheck=mock(com.game.match.precheck.MemberPrecheck.class);
        var metrics=mock(com.game.match.metrics.MatchMetrics.class);
        var service=new ActivityBattleService(precheck,mock(com.game.match.ticket.TicketStore.class),
            mock(com.game.match.gather.GatherLauncher.class),mock(com.game.match.id.MatchIds.class),metrics).withGlobal(global);
        var request=signed();var key=GlobalDirectIntentRow.newBuilder().setKind(2).setOwnerId(7).setRequestLow(701).build();
        when(store.cancelActivity(eq(key),eq(original().toByteString()))).thenReturn(key.toBuilder().setPayload(original().toByteString()).setBattleId(901).build());
        when(store.formation(901)).thenReturn(GlobalFormationRow.newBuilder().setBattleId(901).setState(3).build());
        var decision=service.cancel(request,Deadline.after(10000));assertFalse(decision.getRequestCancelled());
        assertEquals(901,decision.getBattleId());assertEquals(3,decision.getFormationState());
        when(store.direct(key)).thenReturn(key.toBuilder().setPayload(original().toByteString()).setCancelled(true).build());
        assertEquals(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL,service.start(original(),Deadline.after(10000)).getReject());
        verifyNoInteractions(precheck);verify(global,never()).launchCommitted(any());
        when(store.direct(key)).thenReturn(key.toBuilder().setPayload(original().toByteString()).setBattleId(901).build());
        var replay=service.start(original(),Deadline.after(10000));assertEquals(901,replay.getBattleId());assertEquals(3,replay.getFormationState());
        verifyNoInteractions(precheck);
    }
    @Test void 全球Start和Cancel必须绑定同一非零launch原件_不能借旧上下文绕过() {
        var global=mock(GlobalMatchService.class);
        var service=new ActivityBattleService(mock(com.game.match.precheck.MemberPrecheck.class),mock(com.game.match.ticket.TicketStore.class),
            mock(com.game.match.gather.GatherLauncher.class),mock(com.game.match.id.MatchIds.class),mock(com.game.match.metrics.MatchMetrics.class)).withGlobal(global);
        for(long launch:new long[]{0,702}) {
            var invalid=original().toBuilder().setActivityContext(original().getActivityContext().toBuilder().setLaunchRequestId(launch)).build();
            assertEquals(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT,service.start(invalid,Deadline.after(10000)).getReject());
            assertThrows(IllegalArgumentException.class,()->service.cancel(CancelActivityBattleRequest.newBuilder().setOriginal(invalid).build(),Deadline.after(10000)));
        }
        verifyNoInteractions(global);
    }
}
