package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

class MallDatabaseConfigurationTest {
    @Test
    void 生产禁用TiDB拒启且每连接配置完整() {
        DataSourceProperties source = new DataSourceProperties();
        source.setUrl("jdbc:mysql://127.0.0.1:4000/mallit");
        for (String mode : new String[]{"prod", "production", "typo", ""}) {
            assertThatThrownBy(() -> MallDatabaseConfiguration.poolConfiguration(source, MallTestDatabase.properties(), mode))
                    .isInstanceOf(IllegalStateException.class);
        }
        MallProperties production = new MallProperties(true, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY, null, null);
        HikariConfig pool = MallDatabaseConfiguration.poolConfiguration(source, production, "prod");
        assertThat(pool.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
        assertThat(pool.getConnectionInitSql()).contains("pessimistic", "leader", "tidb_snapshot=''", "tidb_read_consistency='strict'", "tidb_read_staleness=0", "tidb_low_resolution_tso=OFF", "STRICT_TRANS_TABLES", "innodb_lock_wait_timeout=5");
        assertThat(pool.getDataSourceProperties()).containsEntry("connectTimeout", "3000").containsEntry("socketTimeout", "6000");
        source.setUrl("jdbc:h2:mem:isolated");
        assertThat(MallDatabaseConfiguration.poolConfiguration(source, MallTestDatabase.properties(), "test").getDataSourceProperties()).isEmpty();
    }

    @Test
    void 已有库版本唯一约束索引与不透明列不能静默降级() throws Exception {
        try (MallTestDatabase db = new MallTestDatabase()) {
            MallSchemaVerifier verifier = new MallSchemaVerifier(db.source, MallTestDatabase.properties());
            verifier.verify();
            db.jdbc.update("UPDATE commerce_schema_version SET storage_contract='incompatible'");
            assertThatThrownBy(verifier::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("版本");
        }
        try (MallTestDatabase db = new MallTestDatabase()) {
            db.jdbc.execute("ALTER TABLE oms_order DROP CONSTRAINT uk_mall_order_request");
            assertThatThrownBy(new MallSchemaVerifier(db.source, MallTestDatabase.properties())::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("唯一");
        }
        try (MallTestDatabase db = new MallTestDatabase()) {
            db.jdbc.execute("DROP INDEX idx_commerce_buyer_status");
            assertThatThrownBy(new MallSchemaVerifier(db.source, MallTestDatabase.properties())::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("索引");
        }
        try (MallTestDatabase db = new MallTestDatabase()) {
            db.jdbc.execute("ALTER TABLE commerce_request_nonce ALTER COLUMN nonce VARCHAR(128)");
            assertThatThrownBy(new MallSchemaVerifier(db.source, MallTestDatabase.properties())::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("VARBINARY");
        }
    }
}
