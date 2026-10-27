package com.game.player.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerRouteAuthority;
import com.game.common.player.RouteAuthorityInitializer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式DDL、显式初始化与真实事务。缺省H2仅验证SQL行为；game.it.mysql真TiDB运行才提供TiDB锁证据。 */
class PlayerRouteAuthoritySqlTest {
    private static final Duration BUDGET=Duration.ofSeconds(5);
    @Test void 缺meta或缺任一围栏片均拒绝建角_不从player或缓存自动补原件()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.sql.update("DELETE FROM route_meta");assertThrows(RuntimeException.class,()->f.player(1,"seller"));assertEquals(0,f.count("player"));
            if(f.endpoint!=null)try(var connection=f.schemaConnection()){assertThrows(SQLException.class,()->RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(7),schemaPrincipal(connection)));}
        }
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.sql.update("DELETE FROM zone_merge_fence WHERE zone_id=7 AND shard=63");assertThrows(RuntimeException.class,()->f.player(1,"seller"));assertEquals(0,f.count("player"));assertEquals(63,f.sql.queryForObject("SELECT COUNT(*) FROM zone_merge_fence WHERE zone_id=7",Integer.class));
        }
    }
    @Test void 已开放世代缺片不能由初始化重跑悄悄补权_不同世代不能覆盖()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            String generation=f.sql.queryForObject("SELECT generation FROM route_meta WHERE id=1",String.class);
            try(var connection=f.schemaConnection()){if(f.endpoint==null)RouteAuthorityInitializer.initialize(connection,generation,List.of(7),schemaPrincipal(connection));else assertThrows(SQLException.class,()->RouteAuthorityInitializer.initialize(connection,generation,List.of(7),schemaPrincipal(connection)));assertThrows(SQLException.class,()->RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(7),schemaPrincipal(connection)));}
            f.sql.update("DELETE FROM zone_merge_fence WHERE zone_id=7 AND shard=63");try(var connection=f.schemaConnection()){assertThrows(SQLException.class,()->RouteAuthorityInitializer.initialize(connection,generation,List.of(7),schemaPrincipal(connection)));}
            assertEquals(63,f.sql.queryForObject("SELECT COUNT(*) FROM zone_merge_fence WHERE zone_id=7",Integer.class));
        }
    }
    @Test void 任意run原件永久挡住新冻结与home读取_不以TTL猜测终结()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");f.sql.update("UPDATE zone_merge_fence SET run_id=? WHERE zone_id=7 AND shard=17",UUID.randomUUID().toString());
            var failure=assertThrows(PlayerTradeStore.Failure.class,()->f.trade(5,true).beginFreeze(new PlayerTradeStore.FreezeRequest(1,1,"seller",1),BUDGET));assertEquals(PlayerTradeStore.Code.NOT_READY,failure.code());assertEquals(0,f.count("player_trade_freeze"));assertEquals(0,f.row(1).getTradeListingId());
            assertThrows(RuntimeException.class,()->new PlayerHomeZones(f.source).homeZones(List.of(1L),Deadline.after(3000)));
        }
    }
    @Test void SQLmeta冻结拒绝夺权和全员事务_资产与旧E不变()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");var before=f.row(1);f.sql.update("UPDATE route_meta SET writable=0 WHERE id=1");assertThrows(RuntimeException.class,()->f.players.claimOwnership(1,BUDGET));assertEquals(before.getOwnerEpoch(),f.sql.queryForObject("SELECT owner_epoch FROM player WHERE player_id=1",Long.class));assertEquals(before.getOwnerRevision(),f.sql.queryForObject("SELECT owner_revision FROM player WHERE player_id=1",Long.class));
        }
    }
    @Test void 恢复必须独立双上界_原账号修订与旧会话同时失效_不改home及存档()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");assertEquals(1,f.sql.update("UPDATE player_state SET data=?,saved_epoch=0,updated_at=1 WHERE player_id=?",new byte[]{8,1},1));
            assertThrows(IllegalArgumentException.class,()->f.players.recoverOwnershipAfterRestore(1,12,BUDGET));
            assertEquals(new PlayerStore.ClaimResult.Claimed(13),f.players.recoverOwnershipAfterRestore(1,12,8,BUDGET));assertEquals(9,f.row(1).getOwnerRevision());assertEquals(7,f.row(1).getZoneId());
            assertArrayEquals(new byte[]{8,1},f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class));
            assertEquals(new PlayerStore.ClaimResult.NotFound(),f.players.claimOwnership(1,"seller",1,BUDGET));
        }
    }
    @Test void 双上界缺失溢出及当前修订耗尽在任何写前拒绝()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            f.player(1,"seller");for(long invalid:List.of(-1L,Long.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->f.players.recoverOwnershipAfterRestore(1,1,invalid,BUDGET));
            f.sql.update("UPDATE player SET owner_revision=? WHERE player_id=1",Long.MAX_VALUE);assertThrows(IllegalStateException.class,()->f.players.recoverOwnershipAfterRestore(1,1,1,BUDGET));assertEquals(0,f.row(1).getOwnerEpoch());
        }
    }
    @Test void 全员跨zone反向输入及uint64高半区P并发不死锁()throws Exception{
        try(var f=new PlayerTradeStoreTest.Fixture()){
            long high=Long.MIN_VALUE+7;f.player(1,"seller");
            // H2的BIGINT UNSIGNED仍只有有符号64位；本例单独扩夹具列，保留真实高半区契约且不跳过。
            if(f.endpoint==null)f.sql.execute("ALTER TABLE player ALTER COLUMN player_id DECIMAL(20,0)");
            // 直接以uint64 JDBC绑定造高半区P，验证本路由锁接缝，不借MyBatis有符号Long映射假造覆盖。
            f.sql.update("INSERT INTO player(player_id,account,zone_id,name,name_key,class_id,gender,created_at,updated_at) VALUES(?,'buyer',2,'高号','高号',1,1,1,1)",PlayerRouteAuthority.u64(high));
            var barrier=new CyclicBarrier(2);try(var threads=Executors.newFixedThreadPool(2)){
                var a=threads.submit(()->{barrier.await();lock(f,List.of(high,1L));return true;});var b=threads.submit(()->{barrier.await();lock(f,List.of(1L,high));return true;});assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));
            }
            assertEquals(2,new PlayerHomeZones(f.source).homeZoneOf(high,Deadline.after(3000)));
        }
    }
    private static void lock(PlayerTradeStoreTest.Fixture f,List<Long> players){var tx=new TransactionTemplate(f.manager);tx.setTimeout(5);tx.execute(status->f.sql.execute((ConnectionCallback<Void>)connection->{PlayerRouteAuthority.lock(connection,players,3);return null;}));}
    private static String schemaPrincipal(java.sql.Connection connection)throws SQLException{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT CURRENT_USER()")){if(!rows.next())throw new SQLException("缺实际schema主体");return rows.getString(1);}}
}
