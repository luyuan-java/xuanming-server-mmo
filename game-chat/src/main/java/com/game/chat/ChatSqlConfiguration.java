package com.game.chat;

import com.game.chat.store.ChatRoleAuthority;
import com.game.chat.store.JdbcChatStore;
import com.game.common.RunMode;
import com.game.common.player.PlayerRouteAuthority;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 只消费同库正式player/route与聊天DDL；缺失时拒启，不从缓存初始化任何归属。 */
@Configuration(proxyBeanMethods = false)
public class ChatSqlConfiguration {
    static boolean requireTiDb(Environment environment) {
        boolean require = environment.getProperty("game.chat.require-tidb", Boolean.class, true);
        if (!require && RunMode.parse(environment.getProperty("game.run-mode")) == RunMode.PROD) {
            throw new IllegalStateException("生产聊天服务不能关闭TiDB强读");
        }
        return require;
    }

    @Bean static StrongTiDbConnectionPolicy chatConnectionPolicy(Environment environment) {
        return new StrongTiDbConnectionPolicy("chat", requireTiDb(environment));
    }

    @Bean(destroyMethod = "close") ChatRoleAuthority chatRoleAuthority(DataSource source) {
        return new ChatRoleAuthority(source);
    }

    @Bean JdbcChatStore jdbcChatStore(DataSource source, ChatRoleAuthority authority, ChatProperties properties) {
        return new JdbcChatStore(source, authority, properties);
    }

    @Bean @DependsOnDatabaseInitialization
    InitializingBean chatSqlReady(DataSource source, JdbcChatStore store, Environment environment) {
        boolean require = requireTiDb(environment);
        return () -> {
            try (Connection connection = source.getConnection()) {
                StrongTiDbConnectionPolicy.verify(connection, require);
                PlayerRouteAuthority.verifySchema(connection);
                verifySchema(connection);
            }
            store.initializePolicy();
        };
    }

    static void verifySchema(Connection connection) throws SQLException {
        record TableProbe(String name, String sql) {}
        for (TableProbe probe : List.of(
                new TableProbe("player", "SELECT player_id,account,owner_revision,trade_listing_id,zone_id FROM player WHERE 1=0"),
                new TableProbe("chat_send_authority", "SELECT player_id,request_id,owner_account,owner_revision,"
                        + "request_digest,request_payload,message_payload,stream_key,state,accepted_ms,result_tip FROM chat_send_authority WHERE 1=0"),
                new TableProbe("chat_policy", "SELECT id,original_contract FROM chat_policy WHERE 1=0"),
                new TableProbe("chat_world_retraction", "SELECT sender_player_id,accepted_ms,retracted_ms,reason FROM chat_world_retraction WHERE 1=0"),
                new TableProbe("chat_report", "SELECT reporter_player_id,channel,target_sender_player_id,send_time_ms,reason,detail,reported_ms FROM chat_report WHERE 1=0"),
                new TableProbe("chat_repeat_event", "SELECT content_hash,accepted_ms,sender_player_id FROM chat_repeat_event WHERE 1=0"),
                new TableProbe("chat_private_daily_target", "SELECT sender_player_id,day_ymd,target_player_id FROM chat_private_daily_target WHERE 1=0"),
                new TableProbe("chat_platform_recheck", "SELECT sender_player_id,accepted_ms,raw_content FROM chat_platform_recheck WHERE 1=0"),
                new TableProbe("chat_platform_recheck_claim", "SELECT sender_player_id,accepted_ms,attempt,claimed_ms FROM chat_platform_recheck_claim WHERE 1=0"),
                new TableProbe("chat_platform_recheck_done", "SELECT sender_player_id,accepted_ms,outcome,done_ms FROM chat_platform_recheck_done WHERE 1=0"))) {
            try (var statement = connection.prepareStatement(probe.sql())) {
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery()) {
                    var fields = rows.getMetaData();
                    for (int index = 1; index <= fields.getColumnCount(); index++) {
                        if (fields.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls) {
                            throw new SQLException("聊天权威投影不允许可空字段");
                        }
                        String field = fields.getColumnLabel(index).toLowerCase(Locale.ROOT);
                        int type = fields.getColumnType(index);
                        boolean binaryReason = probe.name().equals("chat_world_retraction") && field.equals("reason");
                        if (List.of("request_id", "owner_account", "request_digest", "stream_key", "detail", "content_hash", "raw_content").contains(field) || binaryReason) {
                            int minimum = switch (field) {
                                case "request_id" -> 64;
                                case "owner_account" -> 764;
                                case "request_digest", "content_hash" -> 32;
                                case "reason" -> 128;
                                case "detail" -> 255;
                                case "raw_content" -> 4096;
                                default -> 191;
                            };
                            boolean fixedHash = field.equals("request_digest") || field.equals("content_hash");
                            if (type != (fixedHash ? java.sql.Types.BINARY : java.sql.Types.VARBINARY)
                                    || (fixedHash ? fields.getPrecision(index) != 32 : fields.getPrecision(index) < minimum)) {
                                throw new SQLException("聊天原件二进制列形状不符");
                            }
                        } else if (field.equals("account")) {
                            if (type != java.sql.Types.VARCHAR || fields.getPrecision(index) < 191) {
                                throw new SQLException("聊天账号投影必须保持原字符串容量");
                            }
                        } else if (List.of("request_payload", "message_payload", "original_contract").contains(field)) {
                            if (!List.of(java.sql.Types.BLOB, java.sql.Types.LONGVARBINARY, java.sql.Types.VARBINARY).contains(type)
                                    || fields.getPrecision(index) < 65_536) throw new SQLException("聊天PB原件列不足");
                        } else if (List.of("player_id", "owner_revision", "trade_listing_id", "accepted_ms",
                                "sender_player_id", "retracted_ms", "reporter_player_id", "target_sender_player_id",
                                "send_time_ms", "reported_ms", "target_player_id", "claimed_ms", "done_ms").contains(field)) {
                            // H2的高uint64夹具用DECIMAL20；生产由真实TiDB会话门禁另验。
                            if (type != java.sql.Types.BIGINT && !(type == java.sql.Types.DECIMAL && fields.getPrecision(index) >= 20
                                    && fields.getScale(index) == 0)) throw new SQLException("聊天角色/时间整数列形状不符");
                        } else if (List.of("state", "result_tip", "id", "zone_id", "channel", "reason", "day_ymd", "attempt", "outcome").contains(field)
                                && type != java.sql.Types.INTEGER) throw new SQLException("聊天整数列形状不符");
                    }
                }
            }
        }
        DatabaseMetaData metadata = connection.getMetaData();
        String schema;
        try { schema = connection.getSchema(); }
        catch (java.sql.SQLFeatureNotSupportedException unsupported) { schema = null; }
        for (var entry : Map.of(
                "player", List.of("player_id"),
                "chat_send_authority", List.of("player_id", "request_id"),
                "chat_policy", List.of("id"),
                "chat_world_retraction", List.of("sender_player_id", "accepted_ms"),
                "chat_report", List.of("reporter_player_id", "channel", "target_sender_player_id", "send_time_ms"),
                "chat_repeat_event", List.of("content_hash", "accepted_ms", "sender_player_id"),
                "chat_private_daily_target", List.of("sender_player_id", "day_ymd", "target_player_id"),
                "chat_platform_recheck", List.of("sender_player_id", "accepted_ms"),
                "chat_platform_recheck_claim", List.of("sender_player_id", "accepted_ms", "attempt"),
                "chat_platform_recheck_done", List.of("sender_player_id", "accepted_ms")).entrySet()) {
            String table = metadata.storesUpperCaseIdentifiers() ? entry.getKey().toUpperCase(Locale.ROOT) : entry.getKey();
            var keys = new TreeMap<Short, String>();
            try (var rows = metadata.getPrimaryKeys(connection.getCatalog(), schema, table)) {
                while (rows.next()) keys.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if (!List.copyOf(keys.values()).equals(entry.getValue())) throw new SQLException("聊天原件缺正式唯一主键");
        }
        requireIndexes(connection, metadata, schema, "chat_send_authority",
                List.of(List.of("stream_key", "state", "accepted_ms", "player_id", "request_id"),
                        List.of("player_id", "state", "accepted_ms")));
        requireIndexes(connection, metadata, schema, "chat_world_retraction",
                List.of(List.of("accepted_ms", "sender_player_id")));
        requireIndexes(connection, metadata, schema, "chat_report",
                List.of(List.of("target_sender_player_id", "send_time_ms")));
        requireIndexes(connection, metadata, schema, "chat_platform_recheck",
                List.of(List.of("accepted_ms", "sender_player_id")));
    }

    private static void requireIndexes(Connection connection, DatabaseMetaData metadata, String schema,
            String tableName, List<List<String>> expected) throws SQLException {
        String table = metadata.storesUpperCaseIdentifiers() ? tableName.toUpperCase(Locale.ROOT) : tableName;
        var indexes = new HashMap<String, TreeMap<Short, String>>();
        try (var rows = metadata.getIndexInfo(connection.getCatalog(), schema, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) indexes.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .put(rows.getShort("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        var actual = new ArrayList<List<String>>();
        for (var columns : indexes.values()) actual.add(List.copyOf(columns.values()));
        for (List<String> want : expected) {
            if (!actual.contains(want)) {
                throw new SQLException("聊天缺少正式窗口/限速有界索引");
            }
        }
    }
}
