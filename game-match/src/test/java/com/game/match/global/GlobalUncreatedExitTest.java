package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.api.BattleNodeService;
import com.game.api.rpc.NodeRpcClients;
import com.game.match.gather.ScenePreparer;
import com.game.match.global.pb.GlobalFormationRow;
import com.game.match.global.pb.GlobalPreparedEndpoint;
import com.game.match.port.NodeCalls;
import com.game.proto.CreateBattleRequest;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GlobalUncreatedExitTest {
    private final GlobalMatchStore store=mock(GlobalMatchStore.class);
    private final ScenePreparer scenes=mock(ScenePreparer.class);
    @SuppressWarnings("unchecked")
    private final NodeCalls<BattleNodeService> calls=mock(NodeCalls.class);
    private GlobalFormationRow work(){return GlobalFormationRow.newBuilder().setBattleId(81).setOperationId("原操作")
            .setInstanceId("已经消失的原实例").setHost("original").setPort(21200).setState(3).setRetryMs(17).build();}
    private GlobalMatchService service(){return new GlobalMatchService(store,GlobalRegionPolicyTest.CONFIG,null,null,null,null,scenes,calls,null,null,null);}

    @Test void 没有Create时按SQL已核原件退出_不依赖消失节点或Redis_只取消持久原端点()throws Exception{
        var work=work();var endpoint=GlobalPreparedEndpoint.newBuilder().setPlayerId(7).setZoneId(2).setNodeId(9)
                .setHost("prepared-scene").setPort(21100).setInstanceId("prepared-instance").build();
        when(store.finishUncreated(work)).thenReturn(work.toBuilder().setState(5).addPrepared(endpoint).build());
        try(var service=service()){service.abort(work);service.abort(work);}
        verify(store,times(2)).finishUncreated(work);verifyNoInteractions(calls);
        verify(scenes,times(2)).cancel(7,81,new ScenePreparer.Endpoint(2,9,new NodeRpcClients.Target("prepared-scene",21100,"prepared-instance")));
        verify(store,never()).finish(any(),anyBoolean());
    }

    @Test void 空内存快照但SQL原件存在或租约失效_不得取消玩家或调用目标节点(){
        var work=work();when(store.finishUncreated(work)).thenThrow(new SecurityException("SQL 已有原Create或work失租"));
        try(var service=service()){assertThrows(SecurityException.class,()->service.abort(work));}
        verifyNoInteractions(calls,scenes);verify(store,never()).finish(any(),anyBoolean());
    }

    @Test void 已有Create且固定实例不可达仍未知_不能使用无原件出口(){
        var work=work().toBuilder().setCreateRequest(CreateBattleRequest.newBuilder().setBattleId(81).setOutcomeProtocolVersion(1).build().toByteString()).build();
        when(calls.callRemembered(any(),any(),any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("固定实例已消失")));
        try(var service=service()){assertThrows(Exception.class,()->service.abort(work));}
        verify(store).checkWork(work);verify(store,never()).finishUncreated(any());verify(store,never()).finish(any(),anyBoolean());
        verifyNoInteractions(scenes);
    }
}
