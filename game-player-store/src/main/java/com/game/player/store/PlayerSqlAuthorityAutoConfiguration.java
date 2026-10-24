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
                verifyTradeSchema(connection);
                verifyRouteSchema(connection);
            }
        };
    }
    static void verifyRouteSchema(java.sql.Connection connection)throws java.sql.SQLException{
        com.game.common.player.PlayerRouteAuthority.verifySchema(connection);
        for(String sql:java.util.List.of("SELECT id,generation,writable,fence_shards FROM route_meta WHERE 1=0","SELECT zone_id,shard,run_id,revision FROM zone_merge_fence WHERE 1=0")){
            try(var statement=connection.prepareStatement(sql);var rows=statement.executeQuery()){
                var metadata=rows.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)if(metadata.isNullable(i)!=java.sql.ResultSetMetaData.columnNoNulls)throw new IllegalStateException("SQL路由围栏列不能可空");
            }
        }
        // 读取小聚合，不从player补任何围栏；无效home/缺片/未知run拒启动。
        try(var statement=connection.prepareStatement("SELECT p.zone_id FROM (SELECT DISTINCT zone_id FROM player) p LEFT JOIN zone_merge_fence f ON f.zone_id=p.zone_id GROUP BY p.zone_id HAVING p.zone_id=0 OR COUNT(f.shard)<>64 OR MIN(f.shard)<>0 OR MAX(f.shard)<>63 OR MIN(f.revision)<=0 LIMIT 1")){statement.setQueryTimeout(3);try(var rows=statement.executeQuery()){if(rows.next())throw new IllegalStateException("真实player归属区缺少完整SQL围栏原件");}}
    }

    /** M15不能仅依赖CREATE IF NOT EXISTS，旧库必须显式迁移后才能启写者。 */
    static void verifyTradeSchema(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("SELECT owner_revision,trade_listing_id FROM player WHERE 1=0");
             var rows = statement.executeQuery()) {
            var columns = rows.getMetaData();
            for (int index = 1; index <= 2; index++) {
                if (columns.getColumnType(index) != java.sql.Types.BIGINT
                        || columns.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls)
                    throw new IllegalStateException("M15玩家归属修订及交易锁必须为BIGINT");
            }
        }
        for (var table : java.util.Map.of(
                "player_trade_freeze", "listing_id,player_id,seller_account,owner_revision,freeze_epoch,request_digest,creation_token,status,cancel_requested,created_ms,updated_ms",
                "player_trade_transfer", "tx_id,listing_id,player_id,seller_account,buyer_account,previous_revision,new_revision,request_digest,creation_token,status,created_ms",
                "player_trade_drain", "listing_id,player_id,owner_epoch,proof_mode,source_instance,payload_digest,created_ms").entrySet()) {
            try (var statement = connection.prepareStatement("SELECT " + table.getValue() + " FROM " + table.getKey() + " WHERE 1=0");
                 var rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                for (int index = 1; index <= columns.getColumnCount(); index++) {
                    String name = columns.getColumnName(index).toLowerCase(java.util.Locale.ROOT);
                    int type = columns.getColumnType(index);
                    boolean optionalDigest = table.getKey().equals("player_trade_drain") && name.equals("payload_digest");
                    if (optionalDigest && columns.isNullable(index) != java.sql.ResultSetMetaData.columnNullable)
                        throw new IllegalStateException("M15无准入证明必须允许空摘要");
                    if (!optionalDigest && columns.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls)
                        throw new IllegalStateException("M15交易收据列不能可空");
                    if ((name.endsWith("_account") || name.equals("creation_token") || name.equals("source_instance")) && type != java.sql.Types.VARBINARY)
                        throw new IllegalStateException("M15账号与创建标记必须使用VARBINARY");
                    if ((name.equals("request_digest") || name.equals("payload_digest")) && (type != java.sql.Types.BINARY || columns.getPrecision(index) != 32))
                        throw new IllegalStateException("M15请求摘要必须是BINARY(32)");
                    if (name.endsWith("_account") && columns.getPrecision(index) < 256)
                        throw new IllegalStateException("M15账号收据必须容纳64个UTF8码点");
                    if (name.equals("creation_token") && columns.getPrecision(index) != 36)
                        throw new IllegalStateException("M15创建标记必须容纳规范UUID");
                    if (name.equals("status") && (type != java.sql.Types.VARCHAR || columns.getPrecision(index) < 16))
                        throw new IllegalStateException("M15交易状态列形状非法");
                    if (name.equals("source_instance") && columns.getPrecision(index) < 64) throw new IllegalStateException("M15来源列过窄");
                    if (name.equals("proof_mode") && (type != java.sql.Types.VARCHAR || columns.getPrecision(index) < 32))
                        throw new IllegalStateException("M15排空模式列非法");
                    if (name.equals("cancel_requested") && type != java.sql.Types.TINYINT) throw new IllegalStateException("M15撤单门禁列非法");
                    if (!name.endsWith("_account") && !java.util.Set.of("creation_token", "request_digest", "status", "source_instance", "payload_digest", "proof_mode", "cancel_requested").contains(name)
                            && type != java.sql.Types.BIGINT) throw new IllegalStateException("M15交易标识和修订必须使用BIGINT");
                }
            }
            String actual = connection.getMetaData().storesUpperCaseIdentifiers() ? table.getKey().toUpperCase(java.util.Locale.ROOT) : table.getKey();
            var keys = new java.util.ArrayList<String>();
            String schema;
            try { schema = connection.getSchema(); }
            catch (java.sql.SQLFeatureNotSupportedException unsupported) { schema = null; }
            try (var rows = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), schema, actual)) {
                while (rows.next()) keys.add(rows.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            String expected = table.getKey().equals("player_trade_transfer") ? "tx_id" : "listing_id";
            if (!keys.equals(java.util.List.of(expected))) throw new IllegalStateException("M15交易收据缺少唯一主键");
            java.util.List<String> prefix = table.getKey().equals("player_trade_freeze") ? java.util.List.of("status", "listing_id")
                    : table.getKey().equals("player_trade_drain") ? java.util.List.of("player_id", "owner_epoch") : java.util.List.of();
            if (!prefix.isEmpty()) {
                var indexes = new java.util.HashMap<String, java.util.SortedMap<Short, String>>();
                try (var rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), schema, actual, false, false)) {
                    while (rows.next()) {
                        String name = rows.getString("INDEX_NAME"); String column = rows.getString("COLUMN_NAME");
                        if (name != null && column != null) indexes.computeIfAbsent(name, ignored -> new java.util.TreeMap<>())
                                .put(rows.getShort("ORDINAL_POSITION"), column.toLowerCase(java.util.Locale.ROOT));
                    }
                }
                if (indexes.values().stream().map(index -> java.util.List.copyOf(index.values()))
                        .noneMatch(columns -> columns.size() >= prefix.size() && columns.subList(0, prefix.size()).equals(prefix)))
                    throw new IllegalStateException("M15缺少有界排空扫描或旧E封闭索引");
            }
        }
        verifyAdmissionRecoverySchema(connection);
    }

    /** M19永久原件表不允许缺表、可空或以非唯一索引替代身份约束。 */
    static void verifyAdmissionRecoverySchema(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement=connection.prepareStatement("SELECT recovery_id,listing_id,player_id,old_epoch,proof_mode,request_digest,plan_bytes,status,created_ms FROM player_trade_admission_recovery WHERE 1=0");
             var rows=statement.executeQuery()) {
            var fields=rows.getMetaData();
            for(int i=1;i<=fields.getColumnCount();i++) {
                String name=fields.getColumnName(i).toLowerCase(java.util.Locale.ROOT);int type=fields.getColumnType(i);
                if(fields.isNullable(i)!=java.sql.ResultSetMetaData.columnNoNulls)throw new IllegalStateException("M19永久恢复原件列不能可空");
                boolean valid=switch(name) {
                    case "recovery_id" -> type==java.sql.Types.VARBINARY && fields.getPrecision(i)==36;
                    case "request_digest" -> type==java.sql.Types.BINARY && fields.getPrecision(i)==32;
                    case "plan_bytes" -> (type==java.sql.Types.LONGVARBINARY || type==java.sql.Types.BLOB) && fields.getPrecision(i)>=16_777_215;
                    case "status","proof_mode" -> type==java.sql.Types.VARCHAR && fields.getPrecision(i)>=32;
                    default -> type==java.sql.Types.BIGINT;
                };
                if(!valid)throw new IllegalStateException("M19永久恢复原件列类型或容量错误");
            }
        }
        var metadata=connection.getMetaData();String table=metadata.storesUpperCaseIdentifiers()?"PLAYER_TRADE_ADMISSION_RECOVERY":"player_trade_admission_recovery";
        String schema;try{schema=connection.getSchema();}catch(java.sql.SQLFeatureNotSupportedException unsupported){schema=null;}
        var primary=new java.util.TreeMap<Short,String>();
        try(var keys=metadata.getPrimaryKeys(connection.getCatalog(),schema,table)) {
            while(keys.next())primary.put(keys.getShort("KEY_SEQ"),keys.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        if(!java.util.List.copyOf(primary.values()).equals(java.util.List.of("recovery_id")))throw new IllegalStateException("M19恢复UUID必须为唯一主键");
        var unique=new java.util.HashMap<String,java.util.SortedMap<Short,String>>();
        try(var indexes=metadata.getIndexInfo(connection.getCatalog(),schema,table,true,false)) {
            while(indexes.next()) {
                String name=indexes.getString("INDEX_NAME"),column=indexes.getString("COLUMN_NAME");
                if(name!=null && column!=null && !indexes.getBoolean("NON_UNIQUE"))
                    unique.computeIfAbsent(name,ignored->new java.util.TreeMap<>()).put(indexes.getShort("ORDINAL_POSITION"),column.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if(unique.values().stream().noneMatch(index->java.util.List.copyOf(index.values()).equals(java.util.List.of("player_id","old_epoch"))))
            throw new IllegalStateException("M19每个原角色代必须只有一份永久恢复原件");
    }

    /** Mapper自动配置被game-data排除也必须执行；缺表或旧结构不能静默回退。 */
    static void verifyFinalSaveSchema(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement=connection.prepareStatement("""
                SELECT player_id,owner_epoch,save_seq,protocol_version,source_instance,status,
                       level,scene_config_id,pos_x,pos_y,pos_z,data,payload_digest,created_at,
                       next_attempt_at,attempt_count,last_error_code,applied_at,admission_proto
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
        FinalSaveColumn admission=columns.get("admission_proto");
        if(admission==null || admission.nullability()!=java.sql.DatabaseMetaData.columnNullable
                || (admission.type()!=java.sql.Types.LONGVARBINARY && admission.type()!=java.sql.Types.BLOB)
                || admission.size()<com.game.common.player.SceneAdmissionAuthority.MAX_ORIGINAL_BYTES)
            throw new IllegalStateException("player_final_save.admission_proto必须是可空的大二进制列，请执行完整原准入迁移");
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
