package com.game.player.store;

import com.game.common.RunMode;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/** 玩家写库与证明读取共用的强读门禁；独立于 Mapper 自动装配，game-data 自建 PlayerStore 也必须经过。 */
@AutoConfiguration
public class PlayerSqlAuthorityAutoConfiguration {
    static boolean requireTiDb(Environment environment) {
        boolean require = environment.getProperty("game.player-store.require-tidb", Boolean.class, true);
        if (!require && RunMode.parse(environment.getProperty("game.run-mode")) == RunMode.PROD) {
            throw new IllegalStateException("生产玩家权威库不能关闭 TiDB 强读；旧 MySQL 只允许明确 dev/test");
        }
        return require;
    }

    @Bean
    static StrongTiDbConnectionPolicy playerStoreConnectionPolicy(Environment environment) {
        return new StrongTiDbConnectionPolicy("player-store", requireTiDb(environment));
    }

    @Bean
    @DependsOnDatabaseInitialization
    InitializingBean playerSqlAuthority(DataSource source, Environment environment) {
        boolean require = requireTiDb(environment);
        return () -> {
            try (var connection = source.getConnection()) {
                StrongTiDbConnectionPolicy.verify(connection, require);
                verifyFinalSaveSchema(connection);
            }
        };
    }

    /** Mapper自动配置被game-data排除也必须执行；缺表或旧结构不能静默回退。 */
    static void verifyFinalSaveSchema(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement=connection.prepareStatement("""
                SELECT player_id,owner_epoch,save_seq,protocol_version,source_instance,status,
                       level,scene_config_id,pos_x,pos_y,pos_z,data,payload_digest,created_at,
                       next_attempt_at,attempt_count,last_error_code,applied_at
                  FROM player_final_save WHERE 1=0
                """); var ignored=statement.executeQuery()) {
            // 只验证投影，不读取任何玩家payload。
        }
        var metadata=connection.getMetaData();
        String table=metadata.storesUpperCaseIdentifiers()?"PLAYER_FINAL_SAVE":"player_final_save";
        String schema;
        try {
            schema=connection.getSchema();
        } catch(java.sql.SQLFeatureNotSupportedException unsupported) {
            // MySQL旧驱动不区分schema/catalog；仅这个明确的不支持允许使用catalog限定。
            schema=null;
        }
        var columns=new java.util.HashMap<String,FinalSaveColumn>();
        try(var fields=metadata.getColumns(connection.getCatalog(),schema,table,null)) {
            while(fields.next()) {
                String name=fields.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT);
                if(columns.put(name,new FinalSaveColumn(fields.getInt("DATA_TYPE"),fields.getLong("COLUMN_SIZE"),
                        fields.getInt("NULLABLE")))!=null) {
                    throw new IllegalStateException("player_final_save列元数据不唯一，请检查M14目标schema");
                }
            }
        }
        for(String name:java.util.List.of("player_id","owner_epoch","save_seq","created_at","next_attempt_at","attempt_count")) {
            requireFinalColumn(columns,name,java.sql.Types.BIGINT,false);
        }
        requireFinalColumn(columns,"applied_at",java.sql.Types.BIGINT,true);
        requireFinalColumn(columns,"protocol_version",java.sql.Types.INTEGER,false);
        for(String name:java.util.List.of("level","scene_config_id")) {
            requireFinalColumn(columns,name,java.sql.Types.INTEGER,true);
        }
        for(String name:java.util.List.of("pos_x","pos_y","pos_z")) {
            requireFinalColumn(columns,name,java.sql.Types.DOUBLE,true);
        }
        requireFinalWidth(requireFinalColumn(columns,"source_instance",java.sql.Types.VARCHAR,false),64,"source_instance");
        requireFinalWidth(requireFinalColumn(columns,"status",java.sql.Types.VARCHAR,false),16,"status");
        requireFinalWidth(requireFinalColumn(columns,"last_error_code",java.sql.Types.VARCHAR,false),32,"last_error_code");
        requireFinalWidth(requireFinalColumn(columns,"payload_digest",java.sql.Types.BINARY,true),32,"payload_digest");
        FinalSaveColumn data=columns.get("data");
        // Connector/J将MEDIUMBLOB报告为LONGVARBINARY，H2 MySQL兼容模式报告BLOB。
        // 两者都必须支持完整16MiB冻结快照，且允许OPEN不携带payload。
        if(data==null || data.nullability()!=java.sql.DatabaseMetaData.columnNullable
                || (data.type()!=java.sql.Types.LONGVARBINARY && data.type()!=java.sql.Types.BLOB)
                || data.size()<16_777_215L) {
            throw new IllegalStateException("player_final_save.data必须是可空的大二进制列，请执行M14迁移");
        }
        var primary=new java.util.TreeMap<Short,String>();
        try(var keys=metadata.getPrimaryKeys(connection.getCatalog(),schema,table)) {
            while(keys.next()) primary.put(keys.getShort("KEY_SEQ"),keys.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        if(!java.util.List.copyOf(primary.values()).equals(java.util.List.of("player_id","owner_epoch"))) {
            throw new IllegalStateException("player_final_save必须以player_id/owner_epoch唯一锁定一代终态，请执行M14迁移");
        }
        var dueIndex=new java.util.TreeMap<Short,String>();
        var retiredIndex=new java.util.TreeMap<Short,String>();
        try(var indexes=metadata.getIndexInfo(connection.getCatalog(),schema,table,false,false)) {
            while(indexes.next()) {
                String name=indexes.getString("INDEX_NAME");
                if(name!=null && name.equalsIgnoreCase("idx_final_save_due")) {
                    short ordinal=indexes.getShort("ORDINAL_POSITION");
                    String column=indexes.getString("COLUMN_NAME");
                    if(ordinal<=0 || column==null || !indexes.getBoolean("NON_UNIQUE")
                            || dueIndex.put(ordinal,column.toLowerCase(java.util.Locale.ROOT))!=null) {
                        throw new IllegalStateException("player_final_save补偿索引元数据不兼容，请执行M14迁移");
                    }
                }
                if(name!=null && name.equalsIgnoreCase("idx_final_save_retired")) {
                    short ordinal=indexes.getShort("ORDINAL_POSITION");
                    String column=indexes.getString("COLUMN_NAME");
                    if(ordinal<=0 || column==null || !indexes.getBoolean("NON_UNIQUE")
                            || retiredIndex.put(ordinal,column.toLowerCase(java.util.Locale.ROOT))!=null)
                        throw new IllegalStateException("player_final_save清理索引元数据不兼容，请执行M14迁移");
                }
            }
        }
        if(!java.util.List.copyOf(dueIndex.values()).equals(java.util.List.of("status","next_attempt_at","player_id","owner_epoch"))) {
            throw new IllegalStateException("player_final_save补偿索引必须按status/next_attempt_at/player_id/owner_epoch排序，请执行M14迁移");
        }
        if(!java.util.List.copyOf(retiredIndex.values()).equals(java.util.List.of("status","created_at","player_id","owner_epoch")))
            throw new IllegalStateException("player_final_save清理索引列序不兼容，请执行M14迁移");
    }

    private record FinalSaveColumn(int type,long size,int nullability) { }

    private static FinalSaveColumn requireFinalColumn(java.util.Map<String,FinalSaveColumn> columns,String name,
                                                     int type,boolean nullable) {
        FinalSaveColumn column=columns.get(name);
        int expected=nullable?java.sql.DatabaseMetaData.columnNullable:java.sql.DatabaseMetaData.columnNoNulls;
        if(column==null || column.type()!=type || column.nullability()!=expected) {
            throw new IllegalStateException("player_final_save列类型或可空性不兼容: "+name+"，请执行M14迁移");
        }
        return column;
    }

    private static void requireFinalWidth(FinalSaveColumn column,long width,String name) {
        if(column.size()!=width) {
            throw new IllegalStateException("player_final_save列宽不兼容: "+name+"，请执行M14迁移");
        }
    }
}
