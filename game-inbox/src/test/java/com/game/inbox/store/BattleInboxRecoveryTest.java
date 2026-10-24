package com.game.inbox.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.api.inbox.InboxCallContract;
import com.game.common.deadline.Deadline;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.inbox.gate.ConsumerGate;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.notify.Notifier;
import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.rules.InboxShards;
import com.game.inbox.rules.InboxTips;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleSettlementData;
import com.game.proto.common.base.RoleAdmissionIdentity;
import com.game.proto.player_inbox.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 默认H2，沿原路由夹具可原样切专用TiDB；不使用Redis来猜永久来源是否已执行。 */
final class BattleInboxRecoveryTest {
    private static final long NOW=1_761_000_000_000L;
    private static final String SECRET="battle-inbox-test-only-at-least-32-characters";
    private static final PlayerInboxDomain DOMAIN=PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_BATTLE_SETTLEMENT;
    private static final PlayerInboxMessageType TYPE=PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT;
    private static Deadline budget(){return Deadline.after(3000);}
    private static RoleAdmissionIdentity identity(){return RoleAdmissionIdentity.newBuilder().setPlayerId(1).setOwnerRevision(1)
            .setOwnerEpoch(1).setGeneration(1).setSceneInstanceId("battle-inbox-scene").setSceneNodeId("5")
            .setSceneId(101).setZoneId(7).setSessionId(19).setLoginGeneration(13).build();}
    private static BattleSettlementInboxRequest request(long battle){
        var value=BattleSettlementData.newBuilder().setBattleId(battle).setPlayerId(1).setOutcomeValue(1).build();
        return BattleSettlementInboxRequest.newBuilder().setPlayerId(1).setBattleId(battle).setExpectedOwnerAccount("fixture-owner")
                .setExpectedOwnerRevision(1).setIssuedAtMs(NOW).setSettlementPayload(value.toByteString()).build();
    }
    private static final class Fixture implements AutoCloseable {
        final InboxRouteFenceTest.Fixture base=new InboxRouteFenceTest.Fixture();
        final AtomicLong clock=new AtomicLong(NOW);
        final InboxStore store;
        Fixture()throws Exception{
            try{
                for(String column:List.of("fence_epoch BIGINT NOT NULL DEFAULT 0","open_count INT NOT NULL DEFAULT 0",
                        "created_ms BIGINT NOT NULL DEFAULT 0","updated_ms BIGINT NOT NULL DEFAULT 0"))base.sql("ALTER TABLE player_inbox_stream ADD COLUMN "+column);
                base.sql("CREATE TABLE player_inbox_item(shard INT NOT NULL,player_id BIGINT NOT NULL,domain INT NOT NULL,seq BIGINT NOT NULL,"
                        +"msg_type INT NOT NULL,producer_kind INT NOT NULL,request_id BIGINT NOT NULL,content_sha256 BINARY(32) NOT NULL,payload BLOB NOT NULL,"
                        +"state INT,directive INT NOT NULL,void_reason INT NOT NULL,delivery_count INT NOT NULL,last_delivered_epoch BIGINT NOT NULL,last_delivered_ms BIGINT NOT NULL,"
                        +"outcome_reason INT NOT NULL,finalized_by INT NOT NULL,decided_epoch BIGINT NOT NULL,issued_at_ms BIGINT NOT NULL,created_ms BIGINT NOT NULL,updated_ms BIGINT NOT NULL,"
                        +"operator_name VARCHAR(64) NOT NULL,note VARCHAR(256) NOT NULL,owner_account VARCHAR(64),owner_revision BIGINT,PRIMARY KEY(shard,player_id,domain,seq),"
                        +"UNIQUE(shard,player_id,producer_kind,request_id),INDEX by_role(shard,player_id,state))");
                base.sql("CREATE TABLE player_final_save(player_id BIGINT NOT NULL,owner_epoch BIGINT NOT NULL,source_instance VARCHAR(64) NOT NULL,save_seq BIGINT NOT NULL,"
                        +"protocol_version INT NOT NULL,status VARCHAR(16) NOT NULL,admission_proto BLOB,PRIMARY KEY(player_id,owner_epoch))");
                base.sql("CREATE TABLE player_state(player_id BIGINT PRIMARY KEY,data BLOB NOT NULL)");
                try(var c=base.source.getConnection();var q=c.prepareStatement("INSERT INTO player_final_save VALUES(1,1,'battle-inbox-scene',1,1,'OPEN',?)")){
                    q.setBytes(1,SceneAdmissionAuthority.original(identity()));assertEquals(1,q.executeUpdate());
                }
                store=new InboxStore(base.jdbc,InboxRegistry.load(),Set.of(),new JdbcPlayerTables(base.jdbc),ConsumerGate.ALWAYS_OPEN,
                        Notifier.NONE,new InboxMetrics(base.metrics),clock::get,new ServiceCallSignatures(Map.of(InboxCallContract.CALLER,SECRET),clock::get));
            }catch(Exception|AssertionError failure){try{base.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void sql(String text,Object...args)throws Exception{
            try(var c=base.source.getConnection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.setQueryTimeout(3);q.executeUpdate();}
        }
        player_inbox_item_row item(long battle)throws Exception{return base.jdbc.read(budget(),s->InboxStore.readItemByRequest(s,
                InboxShards.rowShard(1),1,PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_MATCH,battle));}
        FetchPlayerInboxResponse fetch()throws Exception{
            var request=FetchPlayerInboxRequest.newBuilder().setPlayerId(1).setDomain(DOMAIN).setOwnerEpoch(1).setCorrelationId(1)
                    .setSceneNodeId(5).setAdmission(identity()).build();
            return store.fetch(budget(),ServiceCallSignatures.sign(InboxCallContract.FETCH,request,InboxCallContract.CALLER,SECRET,clock.get()),"fixture");
        }
        AckPlayerInboxResponse ack(long seq,long battle,PlayerInboxOutcome outcome)throws Exception{
            var request=AckPlayerInboxRequest.newBuilder().setPlayerId(1).setDomain(DOMAIN).setOwnerEpoch(1).setCorrelationId(2)
                    .setSceneNodeId(5).setAdmission(identity()).addDecisions(PlayerInboxDecisionReport.newBuilder().setSeq(seq)
                            .setRequestId(battle).setOutcome(outcome)).build();
            return store.ack(budget(),ServiceCallSignatures.sign(InboxCallContract.ACK,request,InboxCallContract.CALLER,SECRET,clock.get()),"fixture");
        }
        @Override public void close()throws SQLException{base.close();}
    }
    @Test void 专用域单待办且不要求离开原战斗_递归未知资产拒绝(){
        var registry=InboxRegistry.load();var domain=registry.domain(DOMAIN).orElseThrow();
        assertEquals(1,domain.getMaxPending());assertFalse(domain.getRequiresOutOfBattle());
        var unknown=UnknownFieldSet.newBuilder().addField(999,UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        var value=BattleSettlementData.newBuilder().setBattleId(91).setPlayerId(1).setOutcomeValue(1)
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(1).setCount(1).setUnknownFields(unknown)).build();
        assertNotEquals(0,registry.validatePayload(TYPE,Set.of(TYPE),value.toByteArray()).tip());
    }
    @Test void 固定原件十日后仍只同seq_实际APPLIED才可终结()throws Exception{
        try(var f=new Fixture()){
            var original=request(91);var first=f.store.battleSettlement(budget(),original,true);
            assertTrue(first.getSeq()!=0);assertEquals(PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING,first.getState());
            assertEquals(1,f.fetch().getItemsCount());
            assertThrows(SQLException.class,()->f.ack(first.getSeq(),91,PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_UNKNOWN));
            assertEquals(PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING_VALUE,f.item(91).getState());
            assertEquals(PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED,f.ack(first.getSeq(),91,PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED).getResults(0).getResult());
            f.clock.addAndGet(TimeUnit.DAYS.toMillis(10));
            var replay=f.store.battleSettlement(budget(),original,true);assertEquals(first.getSeq(),replay.getSeq());
            assertEquals(PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED,replay.getState());assertEquals(first.getContentSha256(),replay.getContentSha256());
            assertEquals(replay,f.store.battleSettlement(budget(),original,false));
            assertEquals(0,new InboxMaintenanceStore(f.base.jdbc,Notifier.NONE,new InboxMetrics(f.base.metrics),f.clock::get).sweepTerminal(Long.MAX_VALUE,100,()->false));
            assertNotNull(f.item(91));
        }
    }
    @Test void 待办一不放入下一局_普通及强制作废均不能消除奖励()throws Exception{
        try(var f=new Fixture()){
            var first=f.store.battleSettlement(budget(),request(91),true);
            var blocked=f.store.battleSettlement(budget(),request(92),true);assertEquals(InboxTips.FULL,blocked.getErrorMessage().getId());assertNull(f.item(92));
            var admin=new InboxAdminStore(f.base.jdbc,InboxRegistry.load(),new JdbcPlayerTables(f.base.jdbc),new InboxMetrics(f.base.metrics),f.clock::get);
            for(var mode:List.of(PlayerInboxVoidMode.PLAYER_INBOX_VOID_MODE_NORMAL,PlayerInboxVoidMode.PLAYER_INBOX_VOID_MODE_FORCE_MISMATCH)){
                var result=admin.voidItem(budget(),new InboxAdminStore.VoidParams(1,DOMAIN,first.getSeq(),PlayerInboxVoidReason.PLAYER_INBOX_VOID_CANCELLED,"fixture","audit","fixture",mode));
                assertEquals(InboxTips.ITEM_STATE_CONFLICT,result.getErrorMessage().getId());assertEquals(1,f.item(91).getState());
            }
        }
    }
    @Test void 原owner及issuedAt或正文不同绝不借当前目录补权()throws Exception{
        try(var f=new Fixture()){
            var original=request(91);var first=f.store.battleSettlement(budget(),original,true);
            for(var changed:List.of(original.toBuilder().setExpectedOwnerAccount("other").build(),original.toBuilder().setExpectedOwnerRevision(2).build(),
                    original.toBuilder().setIssuedAtMs(NOW+1).build(),original.toBuilder().setSettlementPayload(ByteString.copyFromUtf8("broken")).build()))
                assertThrows(Exception.class,()->f.store.battleSettlement(budget(),changed,true));
            f.sql("UPDATE player SET account='other',owner_revision=2");
            assertThrows(SecurityException.class,()->f.store.battleSettlement(budget(),request(92),true));
            assertEquals(first.getSeq(),f.item(91).getSeq());assertNull(f.item(92));
        }
    }
    @Test void 普通GM入口不能冒Match专用生产者()throws Exception{
        try(var f=new Fixture()){
            var p=new InboxStore.EnqueueParams(1,TYPE,PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_MATCH,91,NOW,
                    request(91).getSettlementPayload().toByteArray(),"match","",null);
            assertEquals(InboxTips.INVALID_PARAMETER,f.store.enqueue(budget(),p).tip());assertNull(f.item(91));
        }
    }
}
