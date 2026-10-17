package com.game.mall;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 每个用例独占的H2数据库，执行生产SQL原文；不接触现有MySQL/TiDB或其他测试数据。 */
final class MallTestDatabase implements AutoCloseable {
    static final String GATEWAY_KEY = "mall-test-only-gateway-key-0000001";
    static final String PAYMENT_KEY = "mall-test-only-payment-key-0000002";
    final JdbcDataSource source = new JdbcDataSource();
    final JdbcTemplate jdbc;
    final MallOrderStore store;

    MallTestDatabase() {
        source.setURL("jdbc:h2:mem:mall_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        source.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__official_sku.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        store = new MallOrderStore(jdbc, new DataSourceTransactionManager(source),
                Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC), properties());
    }

    static MallProperties properties() {
        return new MallProperties(false, GATEWAY_KEY, PAYMENT_KEY, null, null);
    }

    void sku(long id, int stock) {
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (?,?,?,1250,'CNY',?,0,1)",
                id, id, "sku-" + id, stock);
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(?,10,1,'玄铁')",id);
    }

    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int stock(long sku, String column) { return jdbc.queryForObject("SELECT " + column + " FROM pms_sku_stock WHERE id=?", Integer.class, sku); }

    @Override
    public void close() { jdbc.execute("SHUTDOWN"); }
}
