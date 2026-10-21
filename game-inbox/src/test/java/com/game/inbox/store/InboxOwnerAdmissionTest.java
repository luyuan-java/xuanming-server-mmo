package com.game.inbox.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.api.inbox.InboxCallContract;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerRouteAuthority;
import com.game.common.player.SceneAdmissionAuthority;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.inbox.gate.ConsumerGate;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.notify.Notifier;
import com.game.inbox.registry.InboxRegistry;
import com.game.proto.common.base.RoleAdmissionIdentity;
import com.game.proto.player_inbox.*;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 真实SQL事务回归；复用路由夹具的H2/隔离TiDB选择，原准入以SQL原件显式设置，不绕验签或缺表。 */
final class InboxOwnerAdmissionTest {
    private static final long NOW=1_761_000_000_000L;
    // 仅测试使用的公开固定假值，不是部署密钥。
    private static final String SECRET="inbox-scene-test-only-at-least-32-characters";
    private static final PlayerInboxDomain DOMAIN=PlayerInboxDomain.PLAYER_INBOX_DOMAIN_SCENE_GM_SANCTION;
    private static final PlayerInboxMessageType TYPE=PlayerInboxMessageType.PLAYER_INBOX_MSG_GM_BLOCK_CURRENCY_GAIN;
    private static Deadline budget(){return Deadline.after(3000);}
    private static RoleAdmissionIdentity identity(long revision,long epoch){return RoleAdmissionIdentity.newBuilder().setPlayerId(1)
            .setSceneInstanceId("fixture-scene-instance").setGeneration(epoch).setOwnerRevision(revision).setOwnerEpoch(epoch)
            .setSceneNodeId("5").setSceneId(101).setZoneId(7).setSessionId(19).setLoginGeneration(13).build();}

    private static final class Fixture implements AutoCloseable {
        final InboxRouteFenceTest.Fixture base=new InboxRouteFenceTest.Fixture();
        final AtomicLong clock=new AtomicLong(NOW);
        final InboxStore store;
        Fixture() throws Exception {
            try {
                for(String column:List.of("fence_epoch BIGINT NOT NULL DEFAULT 0","open_count INT NOT NULL DEFAULT 0",
                        "created_ms BIGINT NOT NULL DEFAULT 0","updated_ms BIGINT NOT NULL DEFAULT 0"))
                    base.sql("ALTER TABLE player_inbox_stream ADD COLUMN "+column);
                // 本文件仅声明服务消费投影；正式结构源仍为共享PB和player-store正式schema。
                base.sql("CREATE TABLE player_inbox_item(shard INT NOT NULL,player_id BIGINT NOT NULL,domain INT NOT NULL,seq BIGINT NOT NULL,"
                        +"msg_type INT NOT NULL,producer_kind INT NOT NULL,request_id BIGINT NOT NULL,content_sha256 BINARY(32) NOT NULL,payload BLOB NOT NULL,"
                        +"state INT,directive INT NOT NULL,void_reason INT NOT NULL,delivery_count INT NOT NULL,last_delivered_epoch BIGINT NOT NULL,last_delivered_ms BIGINT NOT NULL,"
                        +"outcome_reason INT NOT NULL,finalized_by INT NOT NULL,decided_epoch BIGINT NOT NULL,issued_at_ms BIGINT NOT NULL,created_ms BIGINT NOT NULL,updated_ms BIGINT NOT NULL,"
                        +"operator_name VARCHAR(64) NOT NULL,note VARCHAR(256) NOT NULL,owner_account VARCHAR(64),owner_revision BIGINT,PRIMARY KEY(shard,player_id,domain,seq),"
                        +"UNIQUE(shard,player_id,producer_kind,request_id),INDEX by_role(shard,player_id,state))");
                base.sql("CREATE TABLE player_final_save(player_id BIGINT NOT NULL,owner_epoch BIGINT NOT NULL,source_instance VARCHAR(64) NOT NULL,save_seq BIGINT NOT NULL,"
                        +"protocol_version INT NOT NULL,status VARCHAR(16) NOT NULL,admission_proto BLOB,PRIMARY KEY(player_id,owner_epoch))");
                base.sql("CREATE TABLE player_state(player_id BIGINT PRIMARY KEY,data BLOB NOT NULL)");
                store=new InboxStore(base.jdbc,InboxRegistry.load(),Set.of(TYPE),new JdbcPlayerTables(base.jdbc),ConsumerGate.ALWAYS_OPEN,
                        Notifier.NONE,new InboxMetrics(base.metrics),clock::get,new ServiceCallSignatures(Map.of(InboxCallContract.CALLER,SECRET),clock::get));
                register(identity(1,1));
            } catch(Exception | AssertionError failure) {
                try{base.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }
        void update(String sql,Object...args)throws SQLException{
            try(var connection=base.source.getConnection();var statement=connection.prepareStatement(sql)){
                statement.setQueryTimeout(3);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.executeUpdate();
            }
        }
        long value(String sql)throws SQLException{
            try(var connection=base.source.getConnection();var statement=connection.createStatement()){
                statement.setQueryTimeout(3);try(var rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getLong(1);}
            }
        }
        void register(RoleAdmissionIdentity original)throws SQLException{
            update("INSERT INTO player_final_save(player_id,owner_epoch,source_instance,save_seq,protocol_version,status,admission_proto) VALUES(1,?,?,1,1,'OPEN',?)",
                    original.getOwnerEpoch(),original.getSceneInstanceId(),SceneAdmissionAuthority.original(original));
        }
        InboxStore.EnqueueParams params(long request){return new InboxStore.EnqueueParams(1,TYPE,PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM,
                request,clock.get(),GmCurrencyGainPayload.newBuilder().setCurrencyType(1).build().toByteArray(),"fixture", "",null);}
        InboxStore.EnqueueResult enqueue(long request)throws SQLException{return store.enqueue(budget(),params(request));}
        player_inbox_item_row item(long request)throws SQLException{return base.jdbc.read(budget(),s->InboxStore.readItemByRequest(s,
                com.game.inbox.rules.InboxShards.rowShard(1),1,PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM,request));}
        FetchPlayerInboxRequest request(RoleAdmissionIdentity original){return FetchPlayerInboxRequest.newBuilder().setPlayerId(1).setDomain(DOMAIN)
                .setOwnerEpoch(original.getOwnerEpoch()).setCorrelationId(17).setMaxItems(16).setSceneNodeId(5).setAdmission(original).build();}
        FetchPlayerInboxResponse fetch(RoleAdmissionIdentity original)throws SQLException{return store.fetch(budget(),sign(InboxCallContract.FETCH,request(original)),"fixture");}
        AckPlayerInboxRequest ackRequest(RoleAdmissionIdentity original,long seq,long request){return AckPlayerInboxRequest.newBuilder().setPlayerId(1).setDomain(DOMAIN)
                .setOwnerEpoch(original.getOwnerEpoch()).setCorrelationId(18).setSceneNodeId(5).setAdmission(original)
                .addDecisions(PlayerInboxDecisionReport.newBuilder().setSeq(seq).setRequestId(request).setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED)).build();}
        AckPlayerInboxResponse ack(RoleAdmissionIdentity original,long seq,long request)throws SQLException{
            return store.ack(budget(),sign(InboxCallContract.ACK,ackRequest(original,seq,request)),"fixture");
        }
        <T extends com.google.protobuf.Message>T sign(String method,T request){return ServiceCallSignatures.sign(method,request,InboxCallContract.CALLER,SECRET,clock.get());}
        @Override public void close()throws SQLException{base.close();}
    }

    @Test void 新命令捕原owner_终态永久收据跨换主和签发窗仍不复活()throws Exception{
        try(var f=new Fixture()){
            var params=f.params(501);var first=f.store.enqueue(budget(),params);assertEquals(0,first.tip());assertTrue(first.created());
            assertEquals("fixture-owner",f.item(501).getOwnerAccount());assertEquals(1,f.item(501).getOwnerRevision());
            assertEquals(1,f.fetch(identity(1,1)).getItemsCount());assertEquals(PlayerInboxAckResult.PLAYER_INBOX_ACK_FINALIZED,f.ack(identity(1,1),first.seq(),501).getResults(0).getResult());
            var maintenance=new InboxMaintenanceStore(f.base.jdbc,Notifier.NONE,new InboxMetrics(f.base.metrics),f.clock::get);
            assertEquals(0,maintenance.sweepTerminal(Long.MAX_VALUE,500,()->false));assertNotNull(f.item(501));
            f.update("UPDATE player SET account='next-owner',owner_revision=2,owner_epoch=2");f.register(identity(2,2));
            f.clock.addAndGet(TimeUnit.DAYS.toMillis(10));var duplicate=f.store.enqueue(budget(),params);
            assertEquals(0,duplicate.tip());assertFalse(duplicate.created());assertEquals(first.seq(),duplicate.seq());assertEquals(1,f.item(501).getOwnerRevision());
            var second=f.enqueue(502);assertTrue(second.created());assertEquals("next-owner",f.item(502).getOwnerAccount());assertEquals(2,f.item(502).getOwnerRevision());
            assertThrows(SecurityException.class,()->f.fetch(identity(1,1)));assertThrows(SecurityException.class,()->f.ack(identity(1,1),second.seq(),502));
            assertEquals(0,f.item(502).getDeliveryCount());assertEquals(1,f.fetch(identity(2,2)).getItemsCount());assertEquals(1,f.item(502).getDeliveryCount());
            var conflict=new InboxStore.EnqueueParams(1,TYPE,PlayerInboxProducerKind.PLAYER_INBOX_PRODUCER_GM,501,params.issuedAtMs()+1,params.payload(),"fixture","",null);
            assertEquals(com.game.inbox.rules.InboxTips.IDEMPOTENCY_CONFLICT,f.store.enqueue(budget(),conflict).tip());
        }
    }

    @Test void 签名绑定整请求与方法_每个admission字段均需SQL原字节()throws Exception{
        try(var f=new Fixture()){
            var item=f.enqueue(601);var original=identity(1,1);var signed=f.sign(InboxCallContract.FETCH,f.request(original));
            assertThrows(SecurityException.class,()->f.store.fetch(budget(),f.request(original),"fixture"));
            assertThrows(SecurityException.class,()->f.store.fetch(budget(),signed.toBuilder().setMaxItems(1).build(),"fixture"));
            assertThrows(SecurityException.class,()->f.store.fetch(budget(),f.sign(InboxCallContract.ACK,f.request(original)),"fixture"));
            for(var altered:List.of(original.toBuilder().setGeneration(2).build(),original.toBuilder().setLoginGeneration(14).build(),
                    original.toBuilder().setSceneNodeId("6").build(),original.toBuilder().setSceneId(102).build(),original.toBuilder().setZoneId(8).build(),
                    original.toBuilder().setSessionId(20).build(),original.toBuilder().setSceneInstanceId("other-instance").build(),
                    original.toBuilder().setOwnerRevision(2).build(),original.toBuilder().setOwnerEpoch(2).build(),
                    original.toBuilder().setPlayerId(2).build(),original.toBuilder().setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                            .addField(99,com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()).build())){
                assertThrows(SecurityException.class,()->f.fetch(altered));assertEquals(0,f.item(601).getDeliveryCount());
            }
            f.update("UPDATE player_final_save SET admission_proto=NULL");assertThrows(SecurityException.class,()->f.fetch(original));
            f.update("UPDATE player_final_save SET admission_proto=?",SceneAdmissionAuthority.original(original));
            for(String state:List.of("PENDING","APPLIED","open","UNKNOWN")){
                f.update("UPDATE player_final_save SET status=?",state);assertThrows(SecurityException.class,()->f.fetch(original));
                assertThrows(SecurityException.class,()->f.ack(original,item.seq(),601));assertEquals(0,f.value("SELECT fence_epoch FROM player_inbox_stream"));
            }
            f.update("UPDATE player_final_save SET status='OPEN'");
            for(String change:List.of("save_seq=2","protocol_version=2","source_instance='other-instance'")){
                f.update("UPDATE player_final_save SET "+change);assertThrows(SecurityException.class,()->f.fetch(original));
                f.update("UPDATE player_final_save SET save_seq=1,protocol_version=1,source_instance='fixture-scene-instance'");
            }
            f.update("UPDATE player SET owner_released=1");assertThrows(SecurityException.class,()->f.fetch(original));f.update("UPDATE player SET owner_released=0");
            assertEquals(1,f.fetch(original).getItemsCount());
        }
    }

    @Test void 未终局旧owner和未知状态不得投递确认新入箱或运维改写()throws Exception{
        try(var f=new Fixture()){
            var first=f.enqueue(701);f.update("UPDATE player SET account='next-owner',owner_revision=2,owner_epoch=2");f.register(identity(2,2));
            assertThrows(SQLException.class,()->f.fetch(identity(2,2)));assertThrows(SQLException.class,()->f.enqueue(702));
            assertThrows(SQLException.class,()->f.ack(identity(2,2),first.seq(),701));assertEquals(1,f.item(701).getState());assertEquals(0,f.item(701).getDeliveryCount());
            var admin=new InboxAdminStore(f.base.jdbc,InboxRegistry.load(),new JdbcPlayerTables(f.base.jdbc),new InboxMetrics(f.base.metrics),f.clock::get);
            assertThrows(SQLException.class,()->admin.voidItem(budget(),new InboxAdminStore.VoidParams(1,DOMAIN,first.seq(),PlayerInboxVoidReason.PLAYER_INBOX_VOID_CANCELLED,
                    "fixture","audit","fixture",PlayerInboxVoidMode.PLAYER_INBOX_VOID_MODE_NORMAL)));
            f.update("UPDATE player SET account='fixture-owner',owner_revision=1,owner_epoch=1");f.update("UPDATE player_inbox_item SET state=99");
            assertThrows(SQLException.class,()->f.fetch(identity(1,1)));assertThrows(SQLException.class,()->f.enqueue(703));
            assertThrows(SQLException.class,()->f.store.enqueue(budget(),f.params(701)));
            try(var c=f.base.source.getConnection()){assertThrows(SQLException.class,()->InboxTables.checkOwnerOriginals(c));}
            f.update("UPDATE player_inbox_item SET state=1,owner_revision=NULL");
            try(var c=f.base.source.getConnection()){assertThrows(SQLException.class,()->InboxTables.checkOwnerOriginals(c));}
        }
    }

    @Test void 空流和无流也不得绕过原准入_合法原件只返回无流()throws Exception{
        try(var f=new Fixture()){
            f.update("DELETE FROM player_inbox_stream");f.update("UPDATE player_final_save SET admission_proto=NULL");
            assertThrows(SecurityException.class,()->f.fetch(identity(1,1)));assertThrows(SecurityException.class,()->f.ack(identity(1,1),1,801));
            f.update("UPDATE player_final_save SET admission_proto=?",SceneAdmissionAuthority.original(identity(1,1)));
            assertEquals(PlayerInboxFenceVerdict.PLAYER_INBOX_FENCE_NO_STREAM,f.fetch(identity(1,1)).getFence());
            assertEquals(PlayerInboxAckResult.PLAYER_INBOX_ACK_UNKNOWN_SEQ,f.ack(identity(1,1),1,801).getResults(0).getResult());
        }
    }

    @Test void 强制作废使用同P真实账本_不能采用事务外旧证明()throws Exception{
        try(var f=new Fixture()){
            var first=f.enqueue(851);assertEquals(1,f.fetch(identity(1,1)).getItemsCount());
            var decision=com.game.player.store.state.InboxDecisionState.newBuilder().setSeq(first.seq()).setRequestId(851)
                    .setMsgType(TYPE.getNumber()).setOutcome(PlayerInboxOutcome.PLAYER_INBOX_OUTCOME_APPLIED_VALUE).build();
            var cursor=com.game.player.store.state.InboxDomainCursorState.newBuilder().setDomain(DOMAIN.getNumber())
                    .setDecidedThroughSeq(first.seq()).addRecent(decision).build();
            var actual=com.game.player.store.state.InboxLedgerState.newBuilder().addCursors(cursor).build();
            var stale=actual.toBuilder().setCursors(0,cursor.toBuilder().setRecent(0,decision.toBuilder().setRequestId(999))).build();
            f.update("INSERT INTO player_state VALUES(1,?)",com.game.player.store.state.PlayerState.newBuilder().setInboxLedger(actual).build().toByteArray());
            LedgerReader oldSnapshot=(d,p)->new LedgerReader.Read(true,stale);
            var admin=new InboxAdminStore(f.base.jdbc,InboxRegistry.load(),oldSnapshot,new InboxMetrics(f.base.metrics),f.clock::get);
            var response=admin.voidItem(budget(),new InboxAdminStore.VoidParams(1,DOMAIN,first.seq(),PlayerInboxVoidReason.PLAYER_INBOX_VOID_CANCELLED,
                    "fixture","audit-proof","fixture",PlayerInboxVoidMode.PLAYER_INBOX_VOID_MODE_FORCE_MISMATCH));
            assertEquals(PlayerInboxForceVoidVerdict.PLAYER_INBOX_FORCE_VOID_REQUEST_IN_LEDGER,response.getForceVerdict());
            assertEquals(1,f.item(851).getState());assertEquals(1,f.value("SELECT open_count FROM player_inbox_stream"));
        }
    }

    @Test void 读取请求后换主_等待P的旧Fetch必须在任何投递写之前拒绝()throws Exception{
        try(var f=new Fixture();var threads=Executors.newSingleThreadExecutor();var connection=f.base.source.getConnection()){
            f.enqueue(901);var signed=f.sign(InboxCallContract.FETCH,f.request(identity(1,1)));connection.setAutoCommit(false);
            PlayerRouteAuthority.lock(connection,List.of(1L),3);var begun=new CountDownLatch(1);
            var response=threads.submit(()->{begun.countDown();return f.store.fetch(budget(),signed,"fixture");});assertTrue(begun.await(1,TimeUnit.SECONDS));
            try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE player SET account='next-owner',owner_revision=2,owner_epoch=2");}
            connection.commit();var failed=assertThrows(ExecutionException.class,()->response.get(5,TimeUnit.SECONDS));assertInstanceOf(SecurityException.class,failed.getCause());
            assertEquals(0,f.item(901).getDeliveryCount());assertEquals(0,f.value("SELECT fence_epoch FROM player_inbox_stream"));
        }
    }
}
