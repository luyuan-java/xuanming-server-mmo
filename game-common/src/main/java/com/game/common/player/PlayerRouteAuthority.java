package com.game.common.player;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 单库Java路由提交围栏。home唯一事实仍为player.zone_id，不从Redis或备份自动补权。
 * 所有调用在领域事务开始处先锁完整角色集合：meta共享锁→zone/64片共享锁→P无符号升序。
 * 维护写者必须先独占同meta/64片再碰P；围栏没有TTL自动解除。预读home只决定锁集合，P锁后必须逐一重核。
 */
public final class PlayerRouteAuthority {
    public static final int FENCE_SHARDS=64;
    public static final int MAX_PLAYERS=1024;
    private PlayerRouteAuthority(){}

    public static Object u64(long player){return player<0?new BigInteger(Long.toUnsignedString(player)):player;}
    /** 启动只读核验，不建行、不把旧缓存变成SQL原件。 */
    public static void verifySchema(Connection connection)throws SQLException{
        requireMeta(connection,3,false);
        for(var definition:Map.of("route_meta",List.of("id","generation","writable","fence_shards","writer_epoch","catalog_revision","freeze_run_id"),"zone_merge_fence",List.of("zone_id","shard","run_id","revision"),"zone_merged_into",List.of("zone_id","target_zone","run_id","revision")).entrySet()){
            try(var statement=connection.prepareStatement("SELECT "+String.join(",",definition.getValue())+" FROM "+definition.getKey()+" WHERE 1=0")){statement.setQueryTimeout(3);try(var rows=statement.executeQuery()){
                var fields=rows.getMetaData();for(int i=1;i<=fields.getColumnCount();i++){
                    String name=definition.getValue().get(i-1);int type=fields.getColumnType(i);
                    if(fields.isNullable(i)!=java.sql.ResultSetMetaData.columnNoNulls||type!=(name.equals("generation")||name.equals("run_id")?java.sql.Types.VARCHAR:name.equals("freeze_run_id")?java.sql.Types.VARBINARY:java.util.Set.of("revision","writer_epoch","catalog_revision").contains(name)?java.sql.Types.BIGINT:java.sql.Types.INTEGER))throw new SQLException("路由围栏正式列形状不符");
                    if((name.equals("generation")||name.equals("run_id"))&&fields.getPrecision(i)<36)throw new SQLException("路由原件列不能容纳UUID");
                }
            }}
            var keys=new TreeMap<Short,String>();String schema;try{schema=connection.getSchema();}catch(java.sql.SQLFeatureNotSupportedException unsupported){schema=null;}
            String table=connection.getMetaData().storesUpperCaseIdentifiers()?definition.getKey().toUpperCase(java.util.Locale.ROOT):definition.getKey();
            try(var rows=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),schema,table)){while(rows.next())keys.put(rows.getShort("KEY_SEQ"),rows.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));}
            if(!List.copyOf(keys.values()).equals(definition.getKey().equals("route_meta")?List.of("id"):definition.getKey().equals("zone_merged_into")?List.of("zone_id"):List.of("zone_id","shard")))throw new SQLException("SQL路由围栏主键不符");
        }
    }
    public static void lock(Connection connection,Collection<Long> players,int timeout)throws SQLException{
        lock(connection,players,List.of(),timeout);
    }
    /** extraZones只用于新建或显式导入目标home；绝不代替已有角色的SQLhome。 */
    public static void lock(Connection connection,Collection<Long> players,Collection<Integer> extraZones,int timeout)throws SQLException{
        if(connection.getAutoCommit()||timeout<=0||players.isEmpty()||players.size()>MAX_PLAYERS)throw new SQLException("路由提交缺少有界事务或完整角色集合");
        var ordered=new TreeSet<Long>(Long::compareUnsigned);ordered.addAll(players);
        if(ordered.contains(0L))throw new SQLException("角色零号不能获取路由权威");
        com.game.common.sql.AuthorityConnection.require(connection);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(timeout);
        requireMeta(connection,left(until),true);
        var homes=new TreeMap<Long,Long>(Long::compareUnsigned);var zones=new TreeSet<Long>();
        for(int zone:extraZones){long value=Integer.toUnsignedLong(zone);if(value==0)throw new SQLException("目标归属区不能为零");zones.add(value);}
        for(long player:ordered){Long home=home(connection,player,left(until),false);homes.put(player,home);if(home!=null){if(home==0||home>0xffff_ffffL)throw new SQLException("SQL角色归属区损坏");zones.add(home);}}
        for(long zone:zones)lockZone(connection,zone,left(until));
        for(var entry:homes.entrySet()){
            Long current=home(connection,entry.getKey(),left(until),true);
            if(!java.util.Objects.equals(entry.getValue(),current))throw new SQLException("归属区锁集合已经变化，须重新开始完整领域事务","40001",9007);
        }
        left(until);
    }
    private static int left(long until)throws java.sql.SQLTimeoutException{long nanos=until-System.nanoTime();if(nanos<=0||Thread.currentThread().isInterrupted())throw new java.sql.SQLTimeoutException("路由事务预算耗尽或已取消");return (int)Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos+999_999_999L));}
    public static void requireMeta(Connection connection,int timeout,boolean shared)throws SQLException{
        try(var statement=connection.prepareStatement("SELECT generation,writable,fence_shards FROM route_meta WHERE id=1")){
            statement.setQueryTimeout(timeout);
            try(var rows=statement.executeQuery()){
                if(!rows.next())throw new SQLException("缺少显式初始化的SQL路由meta，禁止从缓存推断归属");
                String generation=rows.getString(1);String writable=rows.getString(2);int shards=rows.getInt(3);
                try{if(!UUID.fromString(generation).toString().equals(generation)||generation.equals("00000000-0000-0000-0000-000000000000"))throw new IllegalArgumentException();}
                catch(RuntimeException invalid){throw new SQLException("SQL路由世代原件无效");}
                if(!"1".equals(writable)||shards!=FENCE_SHARDS)throw new SQLException("SQL路由写者冻结或围栏分片契约不符");
            }
        }
    }
    private static Long home(Connection connection,long player,int timeout,boolean lock)throws SQLException{
        try(var statement=connection.prepareStatement("SELECT zone_id FROM player WHERE player_id=?"+(lock?" FOR UPDATE":""))){
            statement.setQueryTimeout(timeout);statement.setObject(1,u64(player));try(var rows=statement.executeQuery()){
                if(!rows.next())return null;long value=rows.getLong(1);if(rows.wasNull())throw new SQLException("SQL角色缺少home原件");return value;
            }
        }
    }
    private static void lockZone(Connection connection,long zone,int timeout)throws SQLException{
        if(retired(connection,zone,timeout))throw new SQLException("源区已经退役，不能按旧区号提交");
        try(var statement=connection.prepareStatement("SELECT shard,run_id,revision FROM zone_merge_fence WHERE zone_id=? ORDER BY shard"+sharedLock(connection))){
            statement.setQueryTimeout(timeout);statement.setLong(1,zone);try(var rows=statement.executeQuery()){
                int count=0;while(rows.next()){
                    if(rows.getInt(1)!=count++||rows.getString(2)==null||!rows.getString(2).isEmpty()||rows.getLong(3)<=0)throw new SQLException("SQL合区围栏生效或原件损坏");
                }
                if(count!=FENCE_SHARDS)throw new SQLException("SQL合区围栏缺片，必须经显式初始化后启写者");
            }
        }
    }
    // TiDB没有真实行共享锁，显式取排他锁；不能启用noop丢掉提交围栏。
    // 因此TiDB同一meta的写事务会串行；保锁序和超时，吞吐须另外测量，不能声称水平扩容不受影响。
    private static String sharedLock(Connection connection)throws SQLException{
        var metadata=connection.getMetaData();String product=metadata.getDatabaseProductName();
        if("H2".equals(product))return " FOR UPDATE";
        String version=metadata.getDatabaseProductVersion();
        if(version==null||version.isBlank())throw new SQLException("无法确认路由围栏数据库版本");
        if("TiDB".equalsIgnoreCase(product))return " FOR UPDATE";
        if(!"MySQL".equalsIgnoreCase(product))throw new SQLException("路由围栏不支持未知数据库产品");
        return version.toLowerCase(java.util.Locale.ROOT).contains("tidb")?" FOR UPDATE":" LOCK IN SHARE MODE";
    }
    /** 纯预检读侧；提交仍必须调用lock，缺片/未知状态均封锁。 */
    public static boolean fenced(Connection connection,int zone,int timeout)throws SQLException{
        requireMeta(connection,timeout,false);if(zone==0)return true;
        if(retired(connection,Integer.toUnsignedLong(zone),timeout))return true;
        try(var statement=connection.prepareStatement("SELECT shard,run_id,revision FROM zone_merge_fence WHERE zone_id=? ORDER BY shard")){
            statement.setQueryTimeout(timeout);statement.setLong(1,Integer.toUnsignedLong(zone));try(var rows=statement.executeQuery()){
                int count=0;while(rows.next())if(rows.getInt(1)!=count++||rows.getString(2)==null||!rows.getString(2).isEmpty()||rows.getLong(3)<=0)return true;return count!=FENCE_SHARDS;
            }
        }
    }
    private static boolean retired(Connection connection,long zone,int timeout)throws SQLException{
        try(var statement=connection.prepareStatement("SELECT zone_id FROM zone_merged_into WHERE zone_id=?")){statement.setQueryTimeout(timeout);statement.setLong(1,zone);try(var rows=statement.executeQuery()){return rows.next();}}
    }
    /** 目录维护写也共用meta→zone前缀；不播种缺片，不允许重开已合走区。 */
    public static void lockConfigurationZone(Connection connection,int zone,int timeout)throws SQLException{
        if(connection.getAutoCommit()||zone==0)throw new SQLException("目录写缺少正规zone事务");requireMeta(connection,timeout,true);lockZone(connection,Integer.toUnsignedLong(zone),timeout);
    }
}
