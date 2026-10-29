package com.game.data.merge;

import static org.assertj.core.api.Assertions.*;
import com.game.common.player.PlayerRouteAuthority;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.data.testing.DataSqlFixture;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 真TiDB隔离库的SQL seam：控制阶段是本fixture按正式proto种入的前置，绝不证明真实Kafka/Redis或完整P0-P9。域断言与真实NORMAL阻写仍执行。 */
@EnabledIfSystemProperty(named="game.it.require-tidb",matches="true")
class ZoneMergeSqlAuthorityTiDbTest {
    DataSqlFixture db;ZoneMergeStore store;
    @BeforeEach void setup()throws Exception{
        db=DataSqlFixture.createUnpublished((schema,principal)->{ZoneMergeTables.registry().initializeFresh(schema,principal);
        new ResourceDatabasePopulator(new ClassPathResource("db/game-gateway-schema.sql")).populate(schema);
        for(String ddl:List.of(
            "ALTER TABLE guild_activity_reward_owed ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE guild_asset_op ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE guild_trial_lobby ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE trade_listing ADD COLUMN market_zone INT UNSIGNED",
            "ALTER TABLE trade_listing ADD COLUMN price_fen BIGINT UNSIGNED",
            "CREATE TABLE guild(guild_id BIGINT UNSIGNED PRIMARY KEY,leader_id BIGINT UNSIGNED,zone_id INT UNSIGNED,score BIGINT)",
            "CREATE TABLE guild_trial_battle(guild_id BIGINT UNSIGNED,state INT)"))try(var statement=schema.createStatement()){statement.execute(ddl);}});
        db.insertPlayer(1,1,9,1001,10,true,0,100,100);db.insertPlayer(2,2,9,1001,30,true,0,100,100);
        db.putState(1,new byte[]{8,7,6},10,100);db.putState(2,new byte[]{5,4},30,100);
        db.jdbc().update("INSERT INTO zone_config(zone_id,name,manual_status,capacity,maintenance_msg,recommended,sort_order,created_at,updated_at) VALUES(1,'一区',1,1,'',0,0,1,1),(2,'二区',1,1,'',0,0,1,1)");
        db.jdbc().update("INSERT INTO guild VALUES(10,1,1,5),(20,2,2,3)");db.jdbc().update("INSERT INTO trade_listing(listing_id,seller_player_id,market_zone,status,price_fen) VALUES(100,1,1,2,999),(200,2,2,2,888)");try(var connection=db.schemaConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT CURRENT_USER()")){assertThat(rows.next()).isTrue();store=new ZoneMergeStore(db.schemaDataSource(),rows.getString(1));}
    }
    ZoneMergeRunRow freeze(String key,int from,int to,long expected,String operator)throws Exception{return freeze(key,from,to,expected,operator,"");}
    ZoneMergeRunRow freeze(String key,int from,int to,long expected,String operator,String original)throws Exception{db.seedNormalFrozenSqlSeam(key);return store.freeze(key,from,to,expected,operator,original);}
    @AfterEach void close()throws Exception{if(db!=null)db.close();}
    long n(String query,Object...args){return db.jdbc().queryForObject(query,Long.class,args);}
    String hash(ZoneMergeRunRow row){return HexFormat.of().formatHex(row.getPlanHash().toByteArray());}
    ZoneMergeRunRow plan(String key)throws Exception{ZoneMergeRunRow r=store.read(key);for(int i=0;i<20&&r.getState()==1;i++)r=store.plan(key);assertThat(r.getState()).isEqualTo(2);return r;}
    ZoneMergeRunRow complete(String key,ZoneMergeRunRow plan)throws Exception{
        String h=hash(plan);store.approve(key,h,"reviewer");for(int i=0;i<20&&n("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND state=1",key)>0;i++)store.apply(key,h);
        var result=store.finish(key,h);assertThat(result.getState()).isEqualTo(5);return result;
    }
    private void assertSqlFreezeRound(String run,String generation,long beforeEpoch,long afterEpoch){
        assertThat(n("SELECT writer_epoch FROM route_meta WHERE id=1")).isEqualTo(afterEpoch);
        assertThat(n("SELECT writable FROM route_meta WHERE id=1")).isZero();
        assertThat(n("SELECT COUNT(*) FROM route_meta m JOIN sql_writer_maintenance_receipt r ON r.run_id=m.freeze_run_id AND r.generation=m.generation AND r.after_epoch=m.writer_epoch AND r.catalog_revision=m.catalog_revision JOIN sql_writer_drain_run d ON d.operation_id=r.run_id AND d.frozen_request_id=r.request_id AND d.latest_receipt_id=r.request_id AND d.latest_receipt_digest=r.receipt_digest WHERE m.id=1 AND d.phase=7 AND r.run_id=UNHEX(REPLACE(?,'-','')) AND r.generation=? AND r.kind=1 AND r.phase=1 AND r.run_phase=7 AND r.before_epoch=? AND r.after_epoch=?",run,generation,beforeEpoch,afterEpoch)).isEqualTo(1);
    }
    @Test void 正反合区仅改原清单home并推进E_业务快照与owner不回退_完成不自动解冻()throws Exception{
        String key=UUID.randomUUID().toString();var frozen=freeze(key,1,2,1,"operator");assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_fence WHERE run_id=?",key)).isEqualTo(128);
        assertSqlFreezeRound(key,frozen.getGeneration(),1,2);
        assertThat(n("SELECT COUNT(*) FROM writer_registry")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM writer_lifecycle_receipt WHERE kind=1 AND previous_epoch=1 AND resulting_epoch=1")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM writer_slot WHERE state=2 AND writer_epoch=1")).isEqualTo(64);
        var planned=plan(key);assertThat(planned.getItemCount()).isEqualTo(6);assertThatThrownBy(()->store.approve(key,"0".repeat(64),"reviewer")).isInstanceOf(SQLException.class);
        var finished=complete(key,planned);assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(2);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(11);assertThat(n("SELECT owner_revision FROM player WHERE player_id=1")).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT account FROM player WHERE player_id=1",String.class)).isEqualTo("acc1");assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class)).isEqualTo(new byte[]{8,7,6});
        assertThat(n("SELECT zone_id FROM guild WHERE guild_id=10")).isEqualTo(2);assertThat(n("SELECT market_zone FROM trade_listing WHERE listing_id=100")).isEqualTo(2);assertThat(n("SELECT writable FROM route_meta")).isZero();
        assertThatThrownBy(()->{try(var c=db.dataSource.getConnection()){c.setAutoCommit(false);PlayerRouteAuthority.lock(c,List.of(1L),3);c.rollback();}}).isInstanceOf(SQLException.class);
        String h=hash(finished);assertThat(store.release(key,h,frozen.getGeneration(),"reviewer").getState()).isEqualTo(6);assertThat(store.release(key,h,frozen.getGeneration(),"reviewer").getState()).isEqualTo(6);
        // 正式域release只释放本域，不能冒作全局SQL release。
        assertThat(n("SELECT writer_epoch FROM route_meta WHERE id=1")).isEqualTo(2);
        assertThat(n("SELECT writable FROM route_meta WHERE id=1")).isZero();
        for(int zone:new int[]{1,2})assertThatThrownBy(()->{try(var c=db.schemaConnection()){PlayerRouteAuthority.fenced(c,zone,3);}}).isInstanceOf(SQLException.class).hasMessage("SQL路由写者冻结或围栏分片契约不符");
        db.releaseNormalFrozenSqlSeam(key);
        assertThat(n("SELECT writer_epoch FROM route_meta WHERE id=1")).isEqualTo(3);
        assertThat(n("SELECT writable FROM route_meta WHERE id=1")).isEqualTo(1);
        try(var c=db.schemaConnection()){assertThat(PlayerRouteAuthority.fenced(c,1,3)).isTrue();assertThat(PlayerRouteAuthority.fenced(c,2,3)).isFalse();}
        String undo=UUID.randomUUID().toString();var reverse=freeze(undo,2,1,1,"operator",key);
        assertSqlFreezeRound(undo,reverse.getGeneration(),3,4);
        assertThat(n("SELECT COUNT(*) FROM writer_registry")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM writer_registry WHERE state=2")).isEqualTo(2);
        assertThat(n("SELECT COUNT(DISTINCT instance_id) FROM writer_registry")).isEqualTo(2);
        assertThat(n("SELECT COUNT(DISTINCT sql_principal) FROM writer_registry")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM writer_lifecycle_receipt WHERE kind=1")).isEqualTo(2);
        assertThat(n("SELECT COUNT(*) FROM writer_lifecycle_receipt WHERE kind=1 AND previous_epoch=3 AND resulting_epoch=3")).isEqualTo(1);
        assertThat(n("SELECT COUNT(*) FROM writer_slot WHERE state=2 AND writer_epoch=1")).isEqualTo(64);
        assertThat(n("SELECT COUNT(*) FROM writer_slot WHERE state=2 AND writer_epoch=3")).isEqualTo(64);
        assertThat(db.jdbc().queryForList("SELECT COUNT(*) FROM writer_slot GROUP BY writer_key",Long.class)).containsExactlyInAnyOrder(64L,64L);
        var reversePlan=plan(undo);complete(undo,reversePlan);store.release(undo,hash(reversePlan),reverse.getGeneration(),"reviewer");
        assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(12);assertThat(n("SELECT zone_id FROM player WHERE player_id=2")).isEqualTo(2);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=2")).isEqualTo(30);assertThat(n("SELECT COUNT(*) FROM zone_merged_into")).isZero();assertThat(n("SELECT manual_status FROM zone_config WHERE zone_id=1")).isEqualTo(1);assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isEqualTo(2);
    }
    @Test void 缺表和缺片维护态未排空都不能冻结_不以TTL或空结果补权()throws Exception{
        String key=UUID.randomUUID().toString();db.jdbc().update("UPDATE player SET owner_released=0,owner_lease_until=1 WHERE player_id=2");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isEqualTo(1);
        db.jdbc().update("UPDATE player SET owner_released=1 WHERE player_id=2");db.jdbc().update("DELETE FROM zone_merge_fence WHERE zone_id=1 AND shard=63");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();
        db.jdbc().update("INSERT INTO zone_merge_fence VALUES(1,63,'',1)");db.jdbc().execute("DROP TABLE mail_asset_op");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isEqualTo(1);
    }
    @Test void manual未知状态和观战原件均挡计划_失败仍冻_无写实体可按原hash显式中止()throws Exception{
        String key=UUID.randomUUID().toString();freeze(key,1,2,1,"operator");
        for(int state:new int[]{0,1,5,9,10,99}){db.jdbc().update("INSERT INTO trade_commerce VALUES(91,1,0,?)",state);assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);assertThat(n("SELECT item_count FROM zone_merge_run WHERE run_id=?",key)).isZero();db.jdbc().update("DELETE FROM trade_commerce");}
        db.jdbc().update("INSERT INTO match_global_watching VALUES(1,4)");assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();db.jdbc().update("UPDATE match_global_watching SET state=5");
        db.jdbc().update("INSERT INTO player_inbox_item(shard,player_id,state) VALUES(?,1,99)",com.game.common.inbox.InboxShards.rowShard(1));assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);db.jdbc().update("DELETE FROM player_inbox_item");
        var r=store.read(key);store.abort(key,hash(r),"reviewer");assertThat(n("SELECT writable FROM route_meta")).isZero();store.release(key,hash(r),r.getGeneration(),"reviewer");assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);
    }
    @Test void 已审批后换账号修订或字节均在实体写前拒绝_原批次可恢复不换清单()throws Exception{
        String key=UUID.randomUUID().toString();freeze(key,1,2,1,"operator");var p=plan(key);store.approve(key,hash(p),"reviewer");
        db.jdbc().update("UPDATE player SET account='other',owner_revision=2 WHERE player_id=1");assertThatThrownBy(()->store.apply(key,hash(p))).isInstanceOf(SQLException.class);assertThat(n("SELECT COUNT(*) FROM zone_merge_item WHERE state=2")).isZero();assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);
        db.jdbc().update("UPDATE player SET account='acc1',owner_revision=1 WHERE player_id=1");db.jdbc().update("UPDATE trade_listing SET price_fen=1000 WHERE listing_id=100");assertThatThrownBy(()->store.apply(key,hash(p))).isInstanceOf(SQLException.class);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(10);assertThat(n("SELECT COUNT(*) FROM zone_merge_item WHERE state=2")).isZero();
        db.jdbc().update("UPDATE trade_listing SET price_fen=999 WHERE listing_id=100");complete(key,p);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(11);
    }
    @Test void 迁入后新写者或ABA都拒绝撤销_旧E和旧run不能复活()throws Exception{
        String key=UUID.randomUUID().toString();var first=freeze(key,1,2,1,"operator");var p=plan(key);complete(key,p);store.release(key,hash(p),first.getGeneration(),"reviewer");
        db.jdbc().update("UPDATE player SET owner_epoch=12,owner_revision=3 WHERE player_id=1");String undo=UUID.randomUUID().toString();freeze(undo,2,1,1,"operator",key);assertThatThrownBy(()->store.plan(undo)).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(2);
        assertThatThrownBy(()->freeze(undo,1,2,1,"operator",key)).isInstanceOf(SQLException.class);assertThatThrownBy(()->store.release(key,hash(p),first.getGeneration(),"other-reviewer")).isInstanceOf(SQLException.class);
    }
    @Test void meta共享事务未结束时Freeze不能先胜_提交后全局写者均被挡()throws Exception{
        String key=UUID.randomUUID().toString();var executor=Executors.newSingleThreadExecutor();
        try(var held=db.dataSource.getConnection()){
            held.setAutoCommit(false);PlayerRouteAuthority.lock(held,List.of(2L,1L),3);var entered=new CountDownLatch(1);Future<ZoneMergeRunRow> freeze=executor.submit(()->{entered.countDown();return freeze(key,1,2,1,"operator");});assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->freeze.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);held.commit();assertThat(freeze.get(5,TimeUnit.SECONDS).getState()).isEqualTo(1);
            assertThatThrownBy(()->{try(var writer=db.dataSource.getConnection()){writer.setAutoCommit(false);PlayerRouteAuthority.lock(writer,List.of(2L),3);writer.rollback();}}).isInstanceOf(SQLException.class);
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void SQLseam错run或票据绑定均拒绝_域工具不能补全局授权()throws Exception{
        String run=UUID.randomUUID().toString();db.seedNormalFrozenSqlSeam(run);assertThatThrownBy(()->store.freeze(UUID.randomUUID().toString(),1,2,1,"operator")).isInstanceOf(SQLException.class);
        byte[] bound=db.jdbc().queryForObject("SELECT freeze_request_digest FROM sql_writer_freeze_ticket",byte[].class);db.jdbc().update("UPDATE sql_writer_freeze_ticket SET freeze_request_digest=?",new byte[32]);
        assertThatThrownBy(()->store.freeze(run,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();assertThat(n("SELECT writable FROM route_meta")).isZero();
        db.jdbc().update("UPDATE sql_writer_freeze_ticket SET freeze_request_digest=?",bound);assertThat(store.freeze(run,1,2,1,"operator").getState()).isEqualTo(1);
    }
    @Test void 已冻结后域前置损坏仍保留原全局freeze()throws Exception{
        String run=UUID.randomUUID().toString();db.seedNormalFrozenSqlSeam(run);
        db.jdbc().update("UPDATE player SET owner_released=0,owner_lease_until=1 WHERE player_id=2");assertThatThrownBy(()->store.freeze(run,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();
        db.jdbc().update("UPDATE player SET owner_released=1 WHERE player_id=2");db.jdbc().update("DELETE FROM zone_merge_fence WHERE zone_id=1 AND shard=63");assertThatThrownBy(()->store.freeze(run,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();
        db.jdbc().update("INSERT INTO zone_merge_fence VALUES(1,63,'',1)");db.jdbc().execute("DROP TABLE mail_asset_op");assertThatThrownBy(()->store.freeze(run,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();
    }
    @Test void P7域审核读真实终局且发现迁后归属损坏()throws Exception{
        String key=UUID.randomUUID().toString();freeze(key,1,2,1,"operator");var planned=plan(key);complete(key,planned);var released=store.release(key,hash(planned),planned.getGeneration(),"reviewer");
        try(var connection=db.schemaConnection()){connection.setAutoCommit(false);assertThat(ZoneMergeStore.auditReleased(connection,key)).isEqualTo(released);connection.rollback();}
        db.jdbc().update("UPDATE player SET zone_id=1 WHERE player_id=1");
        try(var connection=db.schemaConnection()){connection.setAutoCommit(false);assertThatThrownBy(()->ZoneMergeStore.auditReleased(connection,key)).isInstanceOf(SQLException.class);connection.rollback();}
        assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT state FROM zone_merge_run WHERE run_id=?",key)).isEqualTo(6);
    }

}

/** 纯协议预检：默认Surefire会发现本类；不声称完成真实维护进程join或SQL授权。 */
class MaintenancePublicationContractTest {
    private static com.google.protobuf.ByteString bytes(int n,int value){byte[] bytes=new byte[n];java.util.Arrays.fill(bytes,(byte)value);return com.google.protobuf.ByteString.copyFrom(bytes);}
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.SourcePin pin(){return com.game.player.store.state.SqlWriterMaintenanceReceipt.SourcePin.newBuilder().setAbsolutePath(java.nio.file.Path.of("maintenance-report.pb").toAbsolutePath().toString()).setSha256(bytes(32,7)).build();}
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerClosure tool(){return com.game.player.store.state.SqlWriterMaintenanceReceipt.IssuerClosure.newBuilder().setIssuerId(bytes(16,1)).setInstanceId(bytes(16,2)).setLogicalRole("MAINTENANCE").setPermanentRevivalFenceId(bytes(16,3)).addSourceEvidence(pin()).build();}
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan manifest(){return com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan.newBuilder().addMaintenanceIssuers(tool()).build();}
    private static com.google.protobuf.ByteString digest(com.google.protobuf.Message message)throws Exception{byte[] raw=new byte[message.getSerializedSize()];var out=com.google.protobuf.CodedOutputStream.newInstance(raw);out.useDeterministicSerialization();message.writeTo(out);out.checkNoSpaceLeft();return com.google.protobuf.ByteString.copyFrom(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.ReleaseReadiness ready()throws Exception{
        var seed=com.game.player.store.state.SqlWriterMaintenanceReceipt.PublicationEvidence.newBuilder().addLogicalRoles(tool()).build();
        var closed=tool().toBuilder().setAcceptingClosedMs(10).setAcceptedSettledMs(11).setAcceptedFrontierDigest(bytes(32,4)).build();
        return com.game.player.store.state.SqlWriterMaintenanceReceipt.ReleaseReadiness.newBuilder().setMaintenancePublication(seed.toBuilder().clearLogicalRoles().addLogicalRoles(closed).setIssuerInventoryDigest(digest(seed)).addSourceEvidence(pin())).build();
    }
    @Test void 空目录只能缺省_不能预填空闭合(){
        var empty=com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan.getDefaultInstance();var absent=com.game.player.store.state.SqlWriterMaintenanceReceipt.ReleaseReadiness.getDefaultInstance();
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(empty,absent)).doesNotThrowAnyException();
        var present=absent.toBuilder().setMaintenancePublication(com.game.player.store.state.SqlWriterMaintenanceReceipt.PublicationEvidence.getDefaultInstance()).build();
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(empty,present)).isInstanceOf(SQLException.class);
    }
    @Test void 完整维护闭合通过_缺省报告拒绝()throws Exception{
        var ready=ready();assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(manifest(),ready)).doesNotThrowAnyException();
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(manifest(),ready.toBuilder().clearMaintenancePublication().build())).isInstanceOf(SQLException.class);
    }
    @Test void 维护工具不能复用旧业务issuer或instance(){
        for(var role:List.of(tool().toBuilder().setLogicalRole("SOURCE").build(),tool().toBuilder().setIssuerId(bytes(16,9)).setLogicalRole("RELAY").build())){
            var plan=manifest().toBuilder().addRootIssuers(role).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePlan(plan)).isInstanceOf(SQLException.class);
        }
    }
    @Test void 维护角色目录拒绝writer复用_错role_重复boot_未来结果(){
        for(var role:List.of(tool().toBuilder().setPhysicalWriterKey(bytes(16,5)).build(),tool().toBuilder().setLogicalRole("SOURCE").build(),tool().toBuilder().setAcceptingClosedMs(1).build())){
            var plan=manifest().toBuilder().clearMaintenanceIssuers().addMaintenanceIssuers(role).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePlan(plan)).isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePlan(manifest().toBuilder().addMaintenanceIssuers(tool()).build())).isInstanceOf(SQLException.class);
    }
    @Test void 未join_未知副作用_换boot_错目录摘要均拒绝()throws Exception{
        var ready=ready();var closed=ready.getMaintenancePublication().getLogicalRoles(0);
        for(var bad:List.of(closed.toBuilder().setAcceptedSettledMs(0).build(),closed.toBuilder().setAcceptedSettledMs(9).build(),closed.toBuilder().setUnresolvedPublications(1).build(),closed.toBuilder().setInstanceId(bytes(16,8)).build())){
            var mutated=ready.toBuilder().setMaintenancePublication(ready.getMaintenancePublication().toBuilder().setLogicalRoles(0,bad)).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(manifest(),mutated)).isInstanceOf(SQLException.class);
        }
        var wrong=ready.toBuilder().setMaintenancePublication(ready.getMaintenancePublication().toBuilder().setIssuerInventoryDigest(bytes(32,8))).build();
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateMaintenancePublication(manifest(),wrong)).isInstanceOf(SQLException.class);
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan consumerPlan(){
        var plan=com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan.newBuilder().setRunId(bytes(16,20));for(int partition=0;partition<2;partition++)plan.addKafkaPartitions(com.game.player.store.state.SqlWriterMaintenanceReceipt.KafkaPartitionClosure.newBuilder().setClusterId(bytes(16,21)).setTopicId(bytes(16,22)).setTopicName("results").setGroup("ratings").setPartition(partition));return plan.build();
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.ConsumerReadiness consumer(int partition,int generation){
        var plan=consumerPlan();var group=com.game.player.store.state.SqlWriterMaintenanceReceipt.ConsumerGroupReadiness.newBuilder().setOperationId(plan.getRunId()).setManifestDigest(bytes(32,23)).setIssuerId(bytes(16,30+partition)).setInstanceId(bytes(16,40+partition)).setGroup("ratings").setGenerationId(generation).setMemberId("member-"+partition).addAllExpectedPartitions(plan.getKafkaPartitionsList()).addAssignedPartitions(plan.getKafkaPartitions(partition));
        return com.game.player.store.state.SqlWriterMaintenanceReceipt.ConsumerReadiness.newBuilder().addGroups(group).build();
    }
    @Test void 两副本各持子集聚合为全集_历史退役组无需复活()throws Exception{
        var plan=consumerPlan().toBuilder().addKafkaPartitions(consumerPlan().getKafkaPartitions(0).toBuilder().setGroup("retired-history")).build();
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateConsumerReadiness(plan,bytes(32,23),List.of(consumer(0,7),consumer(1,7),com.game.player.store.state.SqlWriterMaintenanceReceipt.ConsumerReadiness.getDefaultInstance()))).doesNotThrowAnyException();
    }
    @Test void 分区遗漏_重复_owner代次漂移或配置偷删均拒绝(){
        for(var reports:List.of(List.of(consumer(0,7)),List.of(consumer(0,7),consumer(0,7)),List.of(consumer(0,7),consumer(1,8))))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateConsumerReadiness(consumerPlan(),bytes(32,23),reports)).isInstanceOf(SQLException.class);
        var original=consumer(1,7);var changed=original.toBuilder().setGroups(0,original.getGroups(0).toBuilder().clearExpectedPartitions().addExpectedPartitions(consumerPlan().getKafkaPartitions(1))).build();
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateConsumerReadiness(consumerPlan(),bytes(32,23),List.of(consumer(0,7),changed))).isInstanceOf(SQLException.class);
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.RedisTargetClosure redisTarget(){
        return com.game.player.store.state.SqlWriterMaintenanceReceipt.RedisTargetClosure.newBuilder().setRole("mapping").setAddress("127.0.0.1:6379").setDb(2).setServerRunId(com.google.protobuf.ByteString.copyFromUtf8("a".repeat(40))).setMaintenanceNonce(bytes(16,7)).build();
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode redisSource(boolean baseline){
        var target=redisTarget();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var json=mapper.createObjectNode().put("schema",baseline?"SQL_DRAIN_REDIS_BASELINE_V1":"SQL_DRAIN_REDIS_SCAN_V2").put("role",target.getRole()).put("address",target.getAddress()).put("db",target.getDb()).put("server_run_id",java.util.Base64.getEncoder().encodeToString(target.getServerRunId().toByteArray())).put("observed_start_ms",100).put("observed_end_ms",110).put("nonce_key","sql:writer:maintenance:"+java.util.HexFormat.of().formatHex(bytes(16,20).toByteArray())+":nonce").put("nonce_before",java.util.Base64.getEncoder().encodeToString(target.getMaintenanceNonce().toByteArray())).put("nonce_after",java.util.Base64.getEncoder().encodeToString(target.getMaintenanceNonce().toByteArray())).put("nonce_pttl_before_ms",-1).put("nonce_pttl_after_ms",-1);
        if(!baseline){var pages=json.putArray("pages");for(String pattern:List.of("kafka:retry:queue:*","kafka:dead:queue:*","kafka:retry:processing:*","kafka:retry:instances:*","kafka:retry:instance_mode:*","player:save_pending:*","player:{*}:save_barrier"))pages.addObject().put("pattern",pattern).put("input_cursor",0).put("output_cursor",0).putArray("keys");}return json;
    }
    private static byte[] redisRaw(com.fasterxml.jackson.databind.JsonNode json)throws Exception{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(json);}
    @Test void Redis每物理目标唯一真实nonce基线_不要求P0扫描页()throws Exception{
        var plan=com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan.newBuilder().setRunId(bytes(16,20)).addRedisTargets(redisTarget()).build();byte[] raw=redisRaw(redisSource(true));
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateRedisBaseline(plan,List.of(raw))).doesNotThrowAnyException();
        assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisBaseline(plan,List.of())).isInstanceOf(SQLException.class);assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisBaseline(plan,List.of(raw,raw))).isInstanceOf(SQLException.class);
        var duplicate=plan.toBuilder().addRedisTargets(redisTarget().toBuilder().setRole("scene")).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisBaseline(duplicate,List.of(raw,raw))).isInstanceOf(SQLException.class);
        var pages=redisSource(true);pages.putArray("pages");assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisBaseline(plan,List.of(redisRaw(pages)))).isInstanceOf(SQLException.class);
    }
    @Test void Redis基线和P4均拒nonce缺失变化过期及错run()throws Exception{
        for(boolean baseline:List.of(true,false)){
            var original=redisSource(baseline);var missing=original.deepCopy();missing.remove("nonce_before");var changed=original.deepCopy().put("nonce_after",java.util.Base64.getEncoder().encodeToString(bytes(16,8).toByteArray()));var wrongRun=original.deepCopy().put("nonce_key","sql:writer:maintenance:"+"00".repeat(16)+":nonce");var ttl=original.deepCopy().put("nonce_pttl_before_ms",0);var absent=original.deepCopy().put("nonce_pttl_after_ms",-2);var wrongInstance=original.deepCopy().put("server_run_id",java.util.Base64.getEncoder().encodeToString("b".repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8)));var unknown=original.deepCopy().put("unknown",true);var hexBytes=original.deepCopy().put("nonce_after",java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().formatHex(bytes(16,7).toByteArray()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for(var bad:List.of(missing,changed,wrongRun,ttl,absent,wrongInstance,unknown,hexBytes))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisObservationSource(redisRaw(bad),bytes(16,20),redisTarget(),baseline,90,120)).isInstanceOf(SQLException.class);
        }
    }
    @Test void RedisP4实采游标全集绑定原nonce与窗口_允许SCAN重复key()throws Exception{
        var scan=redisSource(false);var pages=(com.fasterxml.jackson.databind.node.ArrayNode)scan.get("pages");((com.fasterxml.jackson.databind.node.ArrayNode)pages.get(0).get("keys")).add("kafka:retry:queue:commands").add("kafka:retry:queue:commands");
        assertThat(com.game.data.tools.InitializeRouteAuthority.validateRedisObservationSource(redisRaw(scan),bytes(16,20),redisTarget(),false,90,120)).containsExactly("kafka:retry:queue:commands");
        var partial=scan.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)partial.get("pages")).remove(6);var cursor=scan.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)cursor.get("pages").get(0)).put("input_cursor",1);var unknown=scan.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unknown.get("pages").get(0)).put("unknown",true);var outOfWindow=scan.deepCopy().put("observed_end_ms",121);
        for(var bad:List.of(partial,cursor,unknown,outOfWindow))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateRedisObservationSource(redisRaw(bad),bytes(16,20),redisTarget(),false,90,120)).isInstanceOf(SQLException.class);
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan manualPlan(){
        var plan=com.game.player.store.state.SqlWriterMaintenanceReceipt.Plan.newBuilder().setRunId(bytes(16,20));for(int node:List.of(1,3)){
            var issuer=bytes(16,50+node);var instance=bytes(16,60+node);plan.addRootIssuers(tool().toBuilder().setIssuerId(issuer).setInstanceId(instance).setLogicalRole("SOURCE"));
            plan.addManualAssignments(com.game.player.store.state.SqlWriterMaintenanceReceipt.ManualConsumerAssignment.newBuilder().setIssuerId(issuer).setInstanceId(instance).setConsumerId("command-"+node).setClusterId(bytes(16,21)).setTopicId(bytes(16,22)).setTopicName("commands").setPartitionCount(2).setPartition(1).setTargetNodeId(node).setTargetInstanceId(instance).setAssignmentRevision(2).setStartOffset(4).setPosition(5).setEarliest(0).setExclusiveEnd(10).setObservedMs(100));
        }return plan.build();
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.ManualConsumerEvidence manualReport(boolean preparing){
        var plan=manualPlan();var report=com.game.player.store.state.SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder().setOperationId(plan.getRunId()).setManifestDigest(bytes(32,23)).setAcceptingClosed(true).setCallbacksJoined(true).setAccepted(2).setSettled(2).addSourcePins(pin());
        for(int i=0;i<2;i++){var actual=plan.getManualAssignments(i).toBuilder().setPosition(10).setObservedMs(200);if(preparing){int node=2+2*i;actual.setInstanceId(bytes(16,80+i)).setTargetInstanceId(bytes(16,80+i)).setTargetNodeId(node).setPartition(0).setConsumerId("command-"+node).setAssignmentRevision(1).setStartOffset(15).setPosition(15).setExclusiveEnd(15).setObservedMs(300);}report.addAssignments(actual);}
        if(preparing)report.setAccepted(0).setSettled(0);return report.build();
    }
    @Test void 同一手动分区允许多个目标boot_旧boot仍须完整闭合()throws Exception{
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateManualPlan(manualPlan())).doesNotThrowAnyException();assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(manualReport(false)),false)).doesNotThrowAnyException();
        var partial=manualReport(false).toBuilder().removeAssignments(1).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(partial),false)).isInstanceOf(SQLException.class);
    }
    @Test void 手动新boot允许真实新nodeID并按取模分配_拒绝旧UUID和跳读(){
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(manualReport(true)),true)).doesNotThrowAnyException();var ready=manualReport(true);
        for(var bad:List.of(ready.getAssignments(0).toBuilder().setPartition(1).build(),ready.getAssignments(0).toBuilder().setStartOffset(16).build(),ready.getAssignments(0).toBuilder().setInstanceId(manualPlan().getManualAssignments(0).getInstanceId()).setTargetInstanceId(manualPlan().getManualAssignments(0).getInstanceId()).build())){
            var changed=ready.toBuilder().setAssignments(0,bad).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(changed),true)).isInstanceOf(SQLException.class);
        }
    }
    @Test void 手动P8暂停后新增尾部合法_不能跳到新尾或接受尾部倒退(){
        var original=manualReport(true);var paused=original.toBuilder().setAssignments(0,original.getAssignments(0).toBuilder().setExclusiveEnd(18)).build();
        assertThatCode(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(paused),true)).doesNotThrowAnyException();
        assertThat(paused.getAssignments(0).getStartOffset()).isEqualTo(15);assertThat(paused.getAssignments(0).getPosition()).isEqualTo(15);
        for(var bad:List.of(paused.getAssignments(0).toBuilder().setExclusiveEnd(14).build(),paused.getAssignments(0).toBuilder().setPosition(18).build(),paused.getAssignments(0).toBuilder().setEarliest(16).build())){
            var changed=paused.toBuilder().setAssignments(0,bad).build();assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(changed),true)).isInstanceOf(SQLException.class);
        }
    }
    @Test void 手动P4拒绝增尾_历史截断_未join和UNKNOWN(){
        var closed=manualReport(false);for(var bad:List.of(closed.toBuilder().setCallbacksJoined(false).build(),closed.toBuilder().setUnresolvedPublications(1).build(),closed.toBuilder().setAssignments(0,closed.getAssignments(0).toBuilder().setExclusiveEnd(11)).build(),closed.toBuilder().setAssignments(0,closed.getAssignments(0).toBuilder().setEarliest(6)).build()))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualClosure(manualPlan(),bytes(32,23),List.of(bad),false)).isInstanceOf(SQLException.class);
    }

    private static byte[] leaderBytes(List<com.game.player.store.state.SqlWriterMaintenanceReceipt.ManualConsumerEvidence> reports)throws Exception{
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var json=mapper.createObjectNode().put("schema","SQL_DRAIN_MANUAL_KAFKA_LEADER_V1").put("observed_start_ms",190).put("observed_end_ms",210).put("catalog_first_sha256",java.util.Base64.getEncoder().encodeToString(bytes(32,90).toByteArray())).put("catalog_last_sha256",java.util.Base64.getEncoder().encodeToString(bytes(32,91).toByteArray()));var consumers=json.putArray("consumers");for(var report:reports)consumers.add(mapper.readTree(com.google.protobuf.util.JsonFormat.printer().preservingProtoFieldNames().print(report)));return mapper.writeValueAsBytes(json);
    }
    private static com.game.player.store.state.SqlWriterMaintenanceReceipt.SourcePin rawPin(byte[] raw)throws Exception{return pin().toBuilder().setAbsolutePath(java.nio.file.Path.of("manual-leader.json").toAbsolutePath().toString()).setSha256(com.google.protobuf.ByteString.copyFrom(java.security.MessageDigest.getInstance("SHA-256").digest(raw))).build();}
    @Test void Go目录摘要固定向量_时间变化不改摘要而配置变化会改()throws Exception{
        byte[] raw="{\"schema\":\"SQL_DRAIN_KAFKA_CATALOG_V2\",\"observed_start_ms\":100,\"observed_end_ms\":110,\"cluster_id\":\"Yw==\",\"topic_partitions\":{\"z\":2,\"a\":1},\"topic_ids\":{\"z\":\"eg==\",\"a\":\"YQ==\"},\"topic_names\":[\"a\",\"z\"],\"group_names\":[\"g\"],\"broker_configs\":{\"1\":{\"z\":\"<&>\\n\",\"a\":\"值\"}},\"topic_configs\":{\"z\":{\"x\":\"y\"},\"a\":{\"x\":\"z\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);var digest=com.game.data.tools.InitializeRouteAuthority.stableKafkaCatalogDigest(raw);assertThat(java.util.HexFormat.of().formatHex(digest.toByteArray())).isEqualTo("79b42451b737b20024a84862bbb1b172dce904cf5fdcdf79f2627c0552ca7fea");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(raw);value.put("observed_start_ms",200).put("observed_end_ms",220);assertThat(com.game.data.tools.InitializeRouteAuthority.stableKafkaCatalogDigest(mapper.writeValueAsBytes(value))).isEqualTo(digest);((com.fasterxml.jackson.databind.node.ObjectNode)value.get("topic_partitions")).put("z",3);assertThat(com.game.data.tools.InitializeRouteAuthority.stableKafkaCatalogDigest(mapper.writeValueAsBytes(value))).isNotEqualTo(digest);
        value.put("unknown",true);assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.stableKafkaCatalogDigest(mapper.writeValueAsBytes(value))).isInstanceOf(SQLException.class);
    }
    @Test void 正式consumer原件兼容跨语言字段名和binary_拒绝空对象与未知嵌套()throws Exception{
        var parser=com.game.data.tools.InitializeRouteAuthority.parseConsumerReadiness("{\"groups\":[],\"manual_assignments\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThat(parser).isEqualTo(com.game.player.store.state.SqlWriterMaintenanceReceipt.ConsumerReadiness.getDefaultInstance());assertThat(com.game.data.tools.InitializeRouteAuthority.parseConsumerReadiness("{\"groups\":[],\"manualAssignments\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(parser);
        var report=consumer(0,7);assertThat(com.game.data.tools.InitializeRouteAuthority.parseConsumerReadiness(report.toByteArray())).isEqualTo(report);
        for(String bad:List.of("{}","{\"groups\":[],\"manualAssignments\":[],\"manual_assignments\":[]}","{\"groups\":[{\"unknown\":true}]}"))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.parseConsumerReadiness(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(Exception.class);assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.parseConsumerReadiness(new byte[0])).isInstanceOf(SQLException.class);
    }
    @Test void manualLeader原件精确绑定_拒绝换正文和任意附加pin()throws Exception{
        var sampled=manualReport(false);byte[] raw=leaderBytes(List.of(sampled));var leader=rawPin(raw);var published=sampled.toBuilder().addSourcePins(leader).build();assertThat(com.game.data.tools.InitializeRouteAuthority.validateManualLeaderPublication(raw,leader,List.of(published),180,220)).containsExactly(sampled);
        for(var bad:List.of(published.toBuilder().setAccepted(3).build(),published.toBuilder().addSourcePins(pin()).build(),published.toBuilder().setSourcePins(0,leader).build()))assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualLeaderPublication(raw,leader,List.of(bad),180,220)).isInstanceOf(SQLException.class);
        byte[] changed=java.util.Arrays.copyOf(raw,raw.length+1);changed[changed.length-1]=' ';assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualLeaderPublication(changed,leader,List.of(published),180,220)).isInstanceOf(SQLException.class);assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualLeaderPublication(raw,leader,List.of(published),200,220)).isInstanceOf(SQLException.class);
    }
    @Test void manualLeader拒绝不在真实窗口的assignment()throws Exception{
        var sampled=manualReport(false).toBuilder().setAssignments(0,manualReport(false).getAssignments(0).toBuilder().setObservedMs(211)).build();byte[] raw=leaderBytes(List.of(sampled));var leader=rawPin(raw);assertThatThrownBy(()->com.game.data.tools.InitializeRouteAuthority.validateManualLeaderPublication(raw,leader,List.of(sampled.toBuilder().addSourcePins(leader).build()),180,220)).isInstanceOf(SQLException.class);
    }
}
