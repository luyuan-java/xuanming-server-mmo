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

/** 真TiDB隔离库：不把H2行锁当作维护并发证明。跨域最小投影只测本域P7查询，不替代各域生命周期回归。 */
@EnabledIfSystemProperty(named="game.it.require-tidb",matches="true")
class ZoneMergeSqlAuthorityTiDbTest {
    DataSqlFixture db;ZoneMergeStore store;
    @BeforeEach void setup()throws Exception{
        db=DataSqlFixture.create();ZoneMergeTables.sync(db.dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/game-gateway-schema.sql")).execute(db.dataSource);
        for(String ddl:List.of(
            "ALTER TABLE guild_activity_reward_owed ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE guild_asset_op ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE guild_trial_lobby ADD COLUMN guild_id BIGINT UNSIGNED",
            "ALTER TABLE trade_listing ADD COLUMN market_zone INT UNSIGNED",
            "ALTER TABLE trade_listing ADD COLUMN price_fen BIGINT UNSIGNED",
            "CREATE TABLE guild(guild_id BIGINT UNSIGNED PRIMARY KEY,leader_id BIGINT UNSIGNED,zone_id INT UNSIGNED,score BIGINT)",
            "CREATE TABLE guild_trial_battle(guild_id BIGINT UNSIGNED,state INT)"))db.jdbc().execute(ddl);
        db.insertPlayer(1,1,9,1001,10,true,0,100,100);db.insertPlayer(2,2,9,1001,30,true,0,100,100);
        db.putState(1,new byte[]{8,7,6},10,100);db.putState(2,new byte[]{5,4},30,100);
        db.jdbc().update("INSERT INTO zone_config(zone_id,name,manual_status,capacity,maintenance_msg,recommended,sort_order,created_at,updated_at) VALUES(1,'一区',1,1,'',0,0,1,1),(2,'二区',1,1,'',0,0,1,1)");
        db.jdbc().update("INSERT INTO guild VALUES(10,1,1,5),(20,2,2,3)");db.jdbc().update("INSERT INTO trade_listing(listing_id,seller_player_id,market_zone,status,price_fen) VALUES(100,1,1,2,999),(200,2,2,2,888)");store=new ZoneMergeStore(db.dataSource);
    }
    @AfterEach void close()throws Exception{if(db!=null)db.close();}
    long n(String query,Object...args){return db.jdbc().queryForObject(query,Long.class,args);}
    String hash(ZoneMergeRunRow row){return HexFormat.of().formatHex(row.getPlanHash().toByteArray());}
    ZoneMergeRunRow plan(String key)throws Exception{ZoneMergeRunRow r=store.read(key);for(int i=0;i<20&&r.getState()==1;i++)r=store.plan(key);assertThat(r.getState()).isEqualTo(2);return r;}
    ZoneMergeRunRow complete(String key,ZoneMergeRunRow plan)throws Exception{
        String h=hash(plan);store.approve(key,h,"reviewer");for(int i=0;i<20&&n("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND state=1",key)>0;i++)store.apply(key,h);
        var result=store.finish(key,h);assertThat(result.getState()).isEqualTo(5);return result;
    }
    @Test void 正反合区仅改原清单home并推进E_业务快照与owner不回退_完成不自动解冻()throws Exception{
        String key=UUID.randomUUID().toString();var frozen=store.freeze(key,1,2,1,"operator");assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_fence WHERE run_id=?",key)).isEqualTo(128);
        var planned=plan(key);assertThat(planned.getItemCount()).isEqualTo(6);assertThatThrownBy(()->store.approve(key,"0".repeat(64),"reviewer")).isInstanceOf(SQLException.class);
        var finished=complete(key,planned);assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(2);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(11);assertThat(n("SELECT owner_revision FROM player WHERE player_id=1")).isEqualTo(1);
        assertThat(db.jdbc().queryForObject("SELECT account FROM player WHERE player_id=1",String.class)).isEqualTo("acc1");assertThat(db.jdbc().queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class)).isEqualTo(new byte[]{8,7,6});
        assertThat(n("SELECT zone_id FROM guild WHERE guild_id=10")).isEqualTo(2);assertThat(n("SELECT market_zone FROM trade_listing WHERE listing_id=100")).isEqualTo(2);assertThat(n("SELECT writable FROM route_meta")).isZero();
        try(var c=db.dataSource.getConnection()){c.setAutoCommit(false);assertThatThrownBy(()->PlayerRouteAuthority.lock(c,List.of(1L),3)).isInstanceOf(SQLException.class);c.rollback();}
        String h=hash(finished);assertThat(store.release(key,h,frozen.getGeneration(),"reviewer").getState()).isEqualTo(6);assertThat(store.release(key,h,frozen.getGeneration(),"reviewer").getState()).isEqualTo(6);
        try(var c=db.dataSource.getConnection()){assertThat(PlayerRouteAuthority.fenced(c,1,3)).isTrue();assertThat(PlayerRouteAuthority.fenced(c,2,3)).isFalse();}
        String undo=UUID.randomUUID().toString();var reverse=store.freeze(undo,2,1,1,"operator",key);var reversePlan=plan(undo);complete(undo,reversePlan);store.release(undo,hash(reversePlan),reverse.getGeneration(),"reviewer");
        assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(12);assertThat(n("SELECT zone_id FROM player WHERE player_id=2")).isEqualTo(2);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=2")).isEqualTo(30);assertThat(n("SELECT COUNT(*) FROM zone_merged_into")).isZero();assertThat(n("SELECT manual_status FROM zone_config WHERE zone_id=1")).isEqualTo(1);assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isEqualTo(2);
    }
    @Test void 缺表和缺片维护态未排空都不能冻结_不以TTL或空结果补权()throws Exception{
        String key=UUID.randomUUID().toString();db.jdbc().update("UPDATE player SET owner_released=0,owner_lease_until=1 WHERE player_id=2");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isEqualTo(1);
        db.jdbc().update("UPDATE player SET owner_released=1 WHERE player_id=2");db.jdbc().update("DELETE FROM zone_merge_fence WHERE zone_id=1 AND shard=63");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT COUNT(*) FROM zone_merge_run")).isZero();
        db.jdbc().update("INSERT INTO zone_merge_fence VALUES(1,63,'',1)");db.jdbc().execute("DROP TABLE mail_asset_op");assertThatThrownBy(()->store.freeze(key,1,2,1,"operator")).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isEqualTo(1);
    }
    @Test void manual未知状态和观战原件均挡计划_失败仍冻_无写实体可按原hash显式中止()throws Exception{
        String key=UUID.randomUUID().toString();store.freeze(key,1,2,1,"operator");
        for(int state:new int[]{0,1,5,9,10,99}){db.jdbc().update("INSERT INTO trade_commerce VALUES(91,1,0,?)",state);assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);assertThat(n("SELECT item_count FROM zone_merge_run WHERE run_id=?",key)).isZero();db.jdbc().update("DELETE FROM trade_commerce");}
        db.jdbc().update("INSERT INTO match_global_watching VALUES(1,4)");assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();db.jdbc().update("UPDATE match_global_watching SET state=5");
        db.jdbc().update("INSERT INTO player_inbox_item(shard,player_id,state) VALUES(?,1,99)",com.game.common.inbox.InboxShards.rowShard(1));assertThatThrownBy(()->store.plan(key)).isInstanceOf(SQLException.class);db.jdbc().update("DELETE FROM player_inbox_item");
        var r=store.read(key);store.abort(key,hash(r),"reviewer");assertThat(n("SELECT writable FROM route_meta")).isZero();store.release(key,hash(r),r.getGeneration(),"reviewer");assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);
    }
    @Test void 已审批后换账号修订或字节均在实体写前拒绝_原批次可恢复不换清单()throws Exception{
        String key=UUID.randomUUID().toString();store.freeze(key,1,2,1,"operator");var p=plan(key);store.approve(key,hash(p),"reviewer");
        db.jdbc().update("UPDATE player SET account='other',owner_revision=2 WHERE player_id=1");assertThatThrownBy(()->store.apply(key,hash(p))).isInstanceOf(SQLException.class);assertThat(n("SELECT COUNT(*) FROM zone_merge_item WHERE state=2")).isZero();assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(1);
        db.jdbc().update("UPDATE player SET account='acc1',owner_revision=1 WHERE player_id=1");db.jdbc().update("UPDATE trade_listing SET price_fen=1000 WHERE listing_id=100");assertThatThrownBy(()->store.apply(key,hash(p))).isInstanceOf(SQLException.class);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(10);assertThat(n("SELECT COUNT(*) FROM zone_merge_item WHERE state=2")).isZero();
        db.jdbc().update("UPDATE trade_listing SET price_fen=999 WHERE listing_id=100");complete(key,p);assertThat(n("SELECT owner_epoch FROM player WHERE player_id=1")).isEqualTo(11);
    }
    @Test void 迁入后新写者或ABA都拒绝撤销_旧E和旧run不能复活()throws Exception{
        String key=UUID.randomUUID().toString();var first=store.freeze(key,1,2,1,"operator");var p=plan(key);complete(key,p);store.release(key,hash(p),first.getGeneration(),"reviewer");
        db.jdbc().update("UPDATE player SET owner_epoch=12,owner_revision=3 WHERE player_id=1");String undo=UUID.randomUUID().toString();store.freeze(undo,2,1,1,"operator",key);assertThatThrownBy(()->store.plan(undo)).isInstanceOf(SQLException.class);assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT zone_id FROM player WHERE player_id=1")).isEqualTo(2);
        assertThatThrownBy(()->store.freeze(undo,1,2,1,"operator",key)).isInstanceOf(SQLException.class);assertThatThrownBy(()->store.release(key,hash(p),first.getGeneration(),"other-reviewer")).isInstanceOf(SQLException.class);
    }
    @Test void meta共享事务未结束时Freeze不能先胜_提交后全局写者均被挡()throws Exception{
        String key=UUID.randomUUID().toString();var executor=Executors.newSingleThreadExecutor();
        try(var held=db.dataSource.getConnection()){
            held.setAutoCommit(false);PlayerRouteAuthority.lock(held,List.of(2L,1L),3);var entered=new CountDownLatch(1);Future<ZoneMergeRunRow> freeze=executor.submit(()->{entered.countDown();return store.freeze(key,1,2,1,"operator");});assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->freeze.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);held.commit();assertThat(freeze.get(5,TimeUnit.SECONDS).getState()).isEqualTo(1);
            try(var writer=db.dataSource.getConnection()){writer.setAutoCommit(false);assertThatThrownBy(()->PlayerRouteAuthority.lock(writer,List.of(2L),3)).isInstanceOf(SQLException.class);writer.rollback();}
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void 热退休只接受finish未release的同世代原run_原hash闭区与全服已释放缺一即拒绝()throws Exception{
        String key=UUID.randomUUID().toString();var frozen=store.freeze(key,1,2,1,"operator");var planned=plan(key);
        var called=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->store.withRetirementProof(key,hash(planned),frozen.getGeneration(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        var done=complete(key,planned);
        assertThatThrownBy(()->store.withRetirementProof(key,"f".repeat(64),frozen.getGeneration(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->store.withRetirementProof(key,hash(done),UUID.randomUUID().toString(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        db.jdbc().update("UPDATE zone_config SET manual_status=1 WHERE zone_id=1");
        assertThatThrownBy(()->store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        db.jdbc().update("UPDATE zone_config SET manual_status=2 WHERE zone_id=1");
        db.jdbc().update("UPDATE player SET owner_released=0,owner_lease_until=1 WHERE player_id=2");
        assertThatThrownBy(()->store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        db.jdbc().update("UPDATE player SET owner_released=1 WHERE player_id=2");
        assertThat(called).hasValue(0);
        int observed=store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->{budget.check();return proof.getSourceZone();});assertThat(observed).isEqualTo(1);
        store.release(key,hash(done),frozen.getGeneration(),"reviewer");
        assertThatThrownBy(()->store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->called.incrementAndGet())).isInstanceOf(SQLException.class);
        assertThat(called).hasValue(0);
    }
    @Test void 热维护回调失败保持原冻结_不自动release()throws Exception {
        String key=UUID.randomUUID().toString();var frozen=store.freeze(key,1,2,1,"operator");var done=complete(key,plan(key));
        assertThatThrownBy(()->store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->{throw new SQLException("Redis outcome unknown");})).hasMessageContaining("Redis outcome unknown");
        assertThat(n("SELECT writable FROM route_meta")).isZero();assertThat(n("SELECT COUNT(*) FROM zone_merge_fence WHERE run_id=?",key)).isEqualTo(128);
        assertThat(store.read(key).getState()).isEqualTo(5);
    }
    @Test void 热维护回调期间release必须等待原SQL围栏()throws Exception {
        String key=UUID.randomUUID().toString();var frozen=store.freeze(key,1,2,1,"operator");var done=complete(key,plan(key));
        var executor=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<Future<ZoneMergeRunRow>>();
        try {
            store.withRetirementProof(key,hash(done),frozen.getGeneration(),(proof,budget)->{
                var entered=new CountDownLatch(1);
                Future<ZoneMergeRunRow> release=executor.submit(()->{entered.countDown();return store.release(key,hash(done),frozen.getGeneration(),"reviewer");});
                pending.set(release);
                try {assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->release.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}
                return true;
            });
            assertThat(pending.get().get(5,TimeUnit.SECONDS).getState()).isEqualTo(6);
        }
        finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
}
