package com.game.mall;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 在HTTP入口装配前核验真实存储契约；CREATE IF NOT EXISTS不能代替已有表的迁移验收。 */
final class MallSchemaVerifier {
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("commerce_schema_version", "version,storage_contract"),
            Map.entry("commerce_buyer_guard", "buyer_id"),
            Map.entry("pms_sku_stock", "id,product_id,sku_code,price_minor,currency,stock,lock_stock,published,catalog_revision"),
            Map.entry("commerce_admin_command", "actor,request_id,command_type,request_digest,result_json,created_ms"),
            Map.entry("oms_order", "id,order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms,payment_time_ms"),
            Map.entry("oms_order_item", "order_sn,product_id,product_sku_id,product_sku_code,product_quantity,product_price_minor"),
            Map.entry("commerce_payment_receipt", "channel,transaction_id,order_sn,amount_minor,currency"),
            Map.entry("commerce_delivery_intent", "order_sn,buyer_id,sku_id,quantity,status,created_ms"),
            Map.entry("commerce_refund_intent", "order_sn,status"),
            Map.entry("commerce_mock_transaction", "kind,order_sn,request_id,transaction_id,payment_transaction_id,amount_minor,currency,created_ms,applied"),
            Map.entry("commerce_consignment", "listing_id,seller_player_id,seller_account,market_zone,asset_kind,asset_currency_type,escrow_reference,escrow_proof,request_digest,title,price_fen,fee_bps,cooling_ms,notice_end_ms,sale_end_ms,state,current_order_sn,created_ms"),
            Map.entry("commerce_consignment_order", "order_sn,listing_id,buyer_account,request_id,seller_account,escrow_reference,escrow_proof,fee_bps,cooling_ms"),
            Map.entry("commerce_consignment_return", "listing_id,return_proof"),
            Map.entry("commerce_seller_guard", "account"),
            Map.entry("commerce_seller_income", "order_sn,account,gross_fen,fee_fen,net_fen,reserved_fen,paid_fen,available_ms"),
            Map.entry("commerce_withdrawal", "withdrawal_id,account,request_id,amount_fen,state,channel,transaction_id,created_ms"),
            Map.entry("commerce_withdrawal_allocation", "withdrawal_id,order_sn,amount_fen"),
            Map.entry("commerce_withdrawal_receipt", "withdrawal_id,channel,transaction_id,amount_fen,successful"),
            Map.entry("commerce_mock_payout", "withdrawal_id,request_id,transaction_id,amount_fen,successful,created_ms,applied"),
            Map.entry("commerce_request_nonce", "caller_role,nonce,expires_ms"));
    private final DataSource source;
    private final MallProperties properties;

    MallSchemaVerifier(DataSource source, MallProperties properties) {
        this.source = source;
        this.properties = properties;
    }

    public void verify() throws SQLException {
        try (Connection connection = source.getConnection()) {
            for(String table:List.of("commerce_sku_grant","commerce_order_grant","commerce_delivery_receipt","commerce_refund_intent","commerce_refund_receipt"))try(var s=connection.createStatement();var rows=s.executeQuery("SELECT * FROM "+table+" WHERE 1=0")){rows.getMetaData();}
            // 会话、存储版本与结构必须在同一条实际持有的连接上核验。
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.setQueryTimeout(5);
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("商城连接必须使用READ COMMITTED");
            }
            boolean h2 = connection.getMetaData().getDatabaseProductName().equals("H2");
            if (properties.requireTidb()) {
                // 专属函数可成功调用即可证明能力；其正文不保证含TiDB字样，不能用contains误拒真TiDB。
                String version = jdbc.queryForObject("SELECT TIDB_VERSION()", String.class);
                String mode = jdbc.queryForObject("SELECT @@session.tidb_txn_mode", String.class);
                String read = jdbc.queryForObject("SELECT @@session.tidb_replica_read", String.class);
                Long staleness = jdbc.queryForObject("SELECT @@session.tidb_read_staleness", Long.class);
                String lowResolution = jdbc.queryForObject("SELECT @@session.tidb_low_resolution_tso", String.class);
                String readConsistency = jdbc.queryForObject("SELECT @@session.tidb_read_consistency", String.class);
                String snapshot = jdbc.queryForObject("SELECT @@session.tidb_snapshot", String.class);
                if (version == null || version.isBlank() || !"pessimistic".equalsIgnoreCase(mode) || !"leader".equalsIgnoreCase(read)
                        || staleness == null || staleness != 0 || lowResolution == null
                        || !Set.of("0", "OFF").contains(lowResolution.toUpperCase(Locale.ROOT))
                        || !"strict".equalsIgnoreCase(readConsistency) || snapshot == null || !snapshot.isEmpty()) {
                    throw new IllegalStateException("商城生产数据库必须为TiDB悲观事务、leader读取");
                }
            }
            if (!h2) {
                String mode = jdbc.queryForObject("SELECT @@session.sql_mode", String.class);
                Set<String> enabled = mode == null ? Set.of() : Set.copyOf(List.of(mode.toUpperCase(Locale.ROOT).split(",")));
                if (!enabled.contains("STRICT_TRANS_TABLES") && !enabled.contains("STRICT_ALL_TABLES")) {
                    throw new IllegalStateException("商城数据库必须启用严格SQL模式");
                }
            }
            List<Version> versions = jdbc.query("SELECT version,storage_contract FROM commerce_schema_version",
                    (row, index) -> new Version(row.getInt(1), row.getString(2)));
            if (versions.size() != 1 || versions.getFirst().number() != 1
                    || !versions.getFirst().contract().equals("mall-game-official-sku-v1-minor-units")) {
                throw new IllegalStateException("商城存储版本不兼容，必须执行显式版本迁移");
            }
            for (var table : COLUMNS.entrySet()) {
                try (var statement = connection.prepareStatement("SELECT " + table.getValue() + " FROM " + table.getKey() + " WHERE 1=0")) {
                    statement.setQueryTimeout(5);
                    try (ResultSet rows = statement.executeQuery()) {
                        ResultSetMetaData metadata = rows.getMetaData();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String column = metadata.getColumnName(i).toLowerCase(Locale.ROOT);
                            if (Set.of("actor", "account", "buyer_account", "seller_account", "request_id", "channel", "transaction_id", "payment_transaction_id", "nonce").contains(column) && metadata.getColumnType(i) != Types.VARBINARY) {
                                throw new IllegalStateException("商城不透明标识必须使用VARBINARY：" + table.getKey() + "." + column);
                            }
                            if (Set.of("price_minor", "pay_amount", "amount_minor", "product_price_minor", "price_fen", "gross_fen", "fee_fen", "net_fen", "reserved_fen", "paid_fen", "amount_fen").contains(column) && metadata.getColumnType(i) != Types.BIGINT) {
                                throw new IllegalStateException("商城金额必须为最小单位BIGINT：" + table.getKey() + "." + column);
                            }
                        }
                    }
                }
            }
            unique(connection, "commerce_schema_version", List.of("version"));
            unique(connection, "commerce_buyer_guard", List.of("buyer_id"));
            unique(connection, "pms_sku_stock", List.of("id"));
            unique(connection, "oms_order", List.of("order_sn"));
            unique(connection, "oms_order", List.of("buyer_id", "request_id"));
            unique(connection, "oms_order_item", List.of("order_sn"));
            unique(connection, "commerce_payment_receipt", List.of("channel", "transaction_id"));
            unique(connection, "commerce_payment_receipt", List.of("order_sn"));
            unique(connection, "commerce_delivery_intent", List.of("order_sn"));
            if(indexes(connection,"commerce_delivery_intent",false).values().stream().noneMatch(cols->cols.size()>=3&&cols.subList(0,3).equals(List.of("status","created_ms","order_sn"))))throw new IllegalStateException("商城交付待办缺可恢复扫描索引");
            unique(connection, "commerce_request_nonce", List.of("caller_role", "nonce"));
            unique(connection, "commerce_admin_command", List.of("actor", "request_id"));
            unique(connection, "commerce_mock_transaction", List.of("kind", "order_sn"));
            unique(connection, "commerce_mock_transaction", List.of("kind", "request_id"));
            unique(connection, "commerce_mock_transaction", List.of("transaction_id"));
            unique(connection, "commerce_consignment", List.of("listing_id"));
            unique(connection, "commerce_consignment", List.of("escrow_reference"));
            unique(connection, "commerce_consignment_order", List.of("order_sn"));
            unique(connection, "commerce_consignment_order", List.of("buyer_account", "request_id"));
            unique(connection, "commerce_consignment_return", List.of("listing_id"));
            unique(connection, "commerce_seller_guard", List.of("account"));
            unique(connection, "commerce_seller_income", List.of("order_sn"));
            unique(connection, "commerce_withdrawal", List.of("withdrawal_id"));
            unique(connection, "commerce_withdrawal", List.of("account", "request_id"));
            unique(connection, "commerce_withdrawal_allocation", List.of("withdrawal_id", "order_sn"));
            unique(connection, "commerce_withdrawal_receipt", List.of("withdrawal_id"));
            unique(connection, "commerce_withdrawal_receipt", List.of("channel", "transaction_id"));
            unique(connection, "commerce_mock_payout", List.of("withdrawal_id"));
            unique(connection, "commerce_mock_payout", List.of("request_id"));
            unique(connection, "commerce_mock_payout", List.of("transaction_id"));
            prefixIndex(connection, "commerce_consignment", List.of("state", "listing_id"));
            prefixIndex(connection, "commerce_consignment_order", List.of("listing_id"));
            prefixIndex(connection, "commerce_seller_income", List.of("account", "order_sn"));
            prefixIndex(connection, "commerce_withdrawal", List.of("state", "withdrawal_id"));
            prefixIndex(connection, "commerce_mock_payout", List.of("applied", "withdrawal_id"));
            prefixIndex(connection, "commerce_mock_transaction", List.of("kind", "applied", "order_sn"));
            prefixIndex(connection, "commerce_refund_intent", List.of("status", "order_sn"));
            prefixIndex(connection, "commerce_request_nonce", List.of("expires_ms"));
            prefixIndex(connection, "oms_order", List.of("buyer_id", "status"));
        }
    }

    private static void unique(Connection connection, String table, List<String> columns) throws SQLException {
        if (!indexes(connection, table, true).containsValue(columns)) {
            throw new IllegalStateException("商城缺必要唯一约束：" + table + columns);
        }
    }

    private static void prefixIndex(Connection connection, String table, List<String> prefix) throws SQLException {
        if (indexes(connection, table, false).values().stream().noneMatch(columns -> columns.size() >= prefix.size()
                && columns.subList(0, prefix.size()).equals(prefix))) {
            throw new IllegalStateException("商城缺必要读取索引：" + table + prefix);
        }
    }

    private static Map<String, List<String>> indexes(Connection connection, String table, boolean unique) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String actual = metadata.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
        Map<String, SortedMap<Integer, String>> grouped = new HashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(), actual, unique, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    grouped.computeIfAbsent(name, ignored -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, List<String>> result = new HashMap<>();
        grouped.forEach((name, columns) -> result.put(name, List.copyOf(columns.values())));
        return result;
    }

    private record Version(int number, String contract) { }
}
