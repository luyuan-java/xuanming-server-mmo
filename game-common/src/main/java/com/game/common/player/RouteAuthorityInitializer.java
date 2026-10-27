package com.game.common.player;

import com.game.common.player.PlayerRouteAuthority;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.TreeSet;
import java.util.UUID;

/** 明确维护命令的初始化，不在任何服务启动或缺行恢复中调用。只从同库player真源及操作者给定zone建围栏。 */
public final class RouteAuthorityInitializer {
    private RouteAuthorityInitializer(){}
    public static void initialize(Connection connection,String generation,Collection<Integer> configuredZones)throws SQLException{
        initialize(connection,generation,configuredZones,System.getenv("MMORPG_SQL_SCHEMA_PRINCIPAL"));
    }
    /** 显式离线schema装配可注入独立主体；实际连接身份仍须读回一致，normal不得持有该凭据。 */
    public static void initialize(Connection connection,String generation,Collection<Integer> configuredZones,String schemaPrincipal)throws SQLException{
        UUID id=UUID.fromString(generation);if(!id.toString().equals(generation)||id.equals(new UUID(0,0)))throw new IllegalArgumentException("初始化需要新的非零规范世代UUID");
        if(schemaPrincipal==null||schemaPrincipal.isBlank())throw new SQLException("初始化仅允许独立fresh-prestart schema主体");
        try(var s=connection.createStatement();var r=s.executeQuery("SELECT CURRENT_USER()")){if(!r.next()||!schemaPrincipal.equals(r.getString(1)))throw new SQLException("实际schema主体不符");}
        for(String table:java.util.List.of("writer_registry","sql_writer_drain_run"))try(var s=connection.createStatement();var r=s.executeQuery("SELECT 1 FROM "+table+" LIMIT 1")){if(r.next())throw new SQLException("已有writer或维护run，拒绝fresh初始化");}
        if(!connection.getAutoCommit())throw new SQLException("路由初始化必须使用独立连接事务");
        connection.setAutoCommit(false);
        try{
            boolean existed;
            try(var statement=connection.prepareStatement("SELECT id FROM route_meta WHERE id=1")){statement.setQueryTimeout(5);try(var rows=statement.executeQuery()){existed=rows.next();}}
            try(var statement=connection.prepareStatement("INSERT INTO route_meta(id,generation,writable,fence_shards,writer_epoch,catalog_revision,freeze_run_id) VALUES(1,?,0,64,1,1,?) ON DUPLICATE KEY UPDATE id=id")){statement.setQueryTimeout(5);statement.setString(1,generation);statement.setBytes(2,new byte[0]);statement.executeUpdate();}
            boolean alreadyWritable;
            try(var statement=connection.prepareStatement("SELECT generation,writable,fence_shards FROM route_meta WHERE id=1 FOR UPDATE")){statement.setQueryTimeout(5);try(var rows=statement.executeQuery()){
                if(!rows.next()||!generation.equals(rows.getString(1))||!java.util.Set.of("0","1").contains(rows.getString(2))||rows.getInt(3)!=PlayerRouteAuthority.FENCE_SHARDS)throw new SQLException("路由世代已有不同原件，禁止自动修复或替换");
                alreadyWritable="1".equals(rows.getString(2));
                if(existed&&!alreadyWritable)throw new SQLException("已有世代被冻结；初始化不能代替经审核的解冻或全库恢复流程");
            }}
            var zones=new TreeSet<Long>();for(int zone:configuredZones){long value=Integer.toUnsignedLong(zone);if(value==0)throw new SQLException("配置zone不能为零");zones.add(value);}
            // SQLplayer是唯一home来源。坏行必须显式修复，不从Redis、导入包或进程zone猜权。
            try(var statement=connection.prepareStatement("SELECT DISTINCT zone_id FROM player ORDER BY zone_id")){statement.setQueryTimeout(5);try(var rows=statement.executeQuery()){while(rows.next()){long zone=rows.getLong(1);if(rows.wasNull()||zone==0||zone>0xffff_ffffL)throw new SQLException("真实player存在无效home，初始化拒绝");zones.add(zone);}}}
            if(zones.isEmpty())throw new SQLException("空库初始化也必须显式指定至少一个可建角zone");
            if(!alreadyWritable)for(long zone:zones)for(int shard=0;shard<PlayerRouteAuthority.FENCE_SHARDS;shard++){
                try(var statement=connection.prepareStatement("INSERT INTO zone_merge_fence(zone_id,shard,run_id,revision) VALUES(?,?,'',1) ON DUPLICATE KEY UPDATE zone_id=zone_id")){statement.setQueryTimeout(5);statement.setLong(1,zone);statement.setInt(2,shard);statement.executeUpdate();}
            }
            try(var statement=connection.prepareStatement("SELECT zone_id,shard,run_id,revision FROM zone_merge_fence ORDER BY zone_id,shard FOR UPDATE")){statement.setQueryTimeout(5);try(var rows=statement.executeQuery()){
                var seen=new TreeSet<Long>();long current=0;int count=0;while(rows.next()){long zone=rows.getLong(1);seen.add(zone);if(zone!=current){if(current!=0&&count!=PlayerRouteAuthority.FENCE_SHARDS)throw new SQLException("围栏原件缺片");current=zone;count=0;}if(zone==0||rows.getInt(2)!=count++||!"".equals(rows.getString(3))||rows.getLong(4)<=0)throw new SQLException("已有合区原件或围栏损坏，初始化拒绝解除");}if(count!=PlayerRouteAuthority.FENCE_SHARDS||!seen.containsAll(zones))throw new SQLException("围栏原件缺片或已初始化库缺新zone；禁止静默补权");
            }}
            try(var statement=connection.prepareStatement("UPDATE route_meta SET writable=1 WHERE id=1 AND generation=? AND fence_shards=64")){statement.setQueryTimeout(5);statement.setString(1,generation);statement.executeUpdate();}
            connection.commit();
        }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);connection.close();}throw failure;}
        finally{if(!connection.isClosed())connection.setAutoCommit(true);}
    }
}
