package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import javax.sql.DataSource;

/** 每例独占随机库；显式 game.it.mysql=本机4000时，八项业务契约改连真实TiDB。 */
class CatalogAdminStoreTest {
    private static final String ACTOR = "catalog-admin";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC);

    @Test void 同请求并发与后续重放返回原响应_新增和库存差额各只提交一次() throws Exception {
        try (var f = new CatalogFixture()) {
            var replicas = List.of(admin(f), admin(f));
            var create = new CatalogAdminApi.Create("create-once-001", 1, terms(125, 10, 2, "旧条款"), 7);
            var created = parallel(f, 4, index -> replicas.get(index % 2).create(ACTOR, create));
            var first = created.getFirst();
            for (var response : created) { assertEquals(first, response); }
            assertEquals(1, first.skuId());
            assertEquals(1, first.skuId());
            assertEquals(1, first.revision());
            assertEquals(7, first.stock());
            assertEquals(1, f.count("pms_sku_stock"));
            assertEquals(1, f.count("commerce_sku_grant"));
            assertEquals(1, f.count("commerce_admin_command"));

            var increase = new CatalogAdminApi.AdjustStock("stock-once-001", 1, 1, 3);
            var increased = parallel(f, 4, index -> replicas.get(index % 2).adjustStock(ACTOR, increase));
            var original = increased.getFirst();
            for (var response : increased) { assertEquals(original, response); }
            assertEquals(2, original.revision());
            assertEquals(10, original.stock());
            assertEquals(2, f.count("commerce_admin_command"));
            assertEquals(new ObjectMapper().writeValueAsString(original), journalResult(f, increase.requestId()));

            var changed = replicas.getFirst().update(ACTOR,
                new CatalogAdminApi.Update("terms-later-001", 1, 2, terms(999, 20, 3, "新条款")));
            var latest = replicas.getLast().adjustStock(ACTOR,
                new CatalogAdminApi.AdjustStock("stock-later-001", 1, changed.revision(), -2));
            assertEquals(8, latest.stock());
            assertEquals(4, latest.revision());
            // 重启后的旧请求仍返回已提交快照，不能把当前修订或新价格拼进旧响应。
            var restarted = admin(f);
            assertEquals(original, restarted.adjustStock(ACTOR, increase));
            assertEquals(first, restarted.create(ACTOR, create));
            assertEquals(latest, sku(restarted, 1));
            assertEquals(4, f.count("commerce_admin_command"));
            assertEquals(0, f.count("oms_order"));
        }
    }

    @Test void 同主体请求号换载荷或命令种类拒绝_日志与业务原件不变() {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            var create = new CatalogAdminApi.Create("fixed-request-001", 1, terms(100, 10, 1, "原条款"), 5);
            store.create(ACTOR, create);
            var before = businessState(f);
            var commands = journalRows(f);
            refuses(409, () -> store.create(ACTOR,
                new CatalogAdminApi.Create(create.requestId(), 1, create.terms(), 6)));
            refuses(409, () -> store.create(ACTOR,
                new CatalogAdminApi.Create(create.requestId(), 1, terms(101, 10, 1, "原条款"), 5)));
            refuses(409, () -> store.adjustStock(ACTOR,
                new CatalogAdminApi.AdjustStock(create.requestId(), 1, 1, 1)));
            refuses(409, () -> store.update(ACTOR,
                new CatalogAdminApi.Update(create.requestId(), 1, 1, create.terms())));
            assertEquals(before, businessState(f));
            assertEquals(commands, journalRows(f));
            assertEquals(1, f.count("commerce_admin_command"));
        }
    }

    @Test void 两个管理副本竞争同修订只有一个提交_失败请求不遗留半完成日志() throws Exception {
        try (var f = new CatalogFixture()) {
            var replicas = List.of(admin(f), admin(f));
            replicas.getFirst().create(ACTOR,
                new CatalogAdminApi.Create("race-create-001", 1, terms(100, 10, 1, "初始"), 10));
            var commands = List.of(
                new CatalogAdminApi.Update("race-update-001", 1, 1, terms(200, 20, 2, "竞争甲")),
                new CatalogAdminApi.Update("race-update-002", 1, 1, terms(300, 30, 3, "竞争乙")));
            var responses = parallel(f, 2, index -> {
                try { return replicas.get(index).update(ACTOR, commands.get(index)); }
                catch (MallException conflict) { assertEquals(409, conflict.status()); return null; }
            });
            var winners = responses.stream().filter(value -> value != null).toList();
            assertEquals(1, winners.size());
            var winner = winners.getFirst();
            assertEquals(2, winner.revision());
            assertEquals(10, winner.stock());
            assertEquals(0, winner.lockedStock());
            assertEquals(winner, sku(admin(f), 1));
            assertEquals(2, f.count("commerce_admin_command"));
            int loser = responses.getFirst() == null ? 0 : 1;
            assertEquals(0, journalCount(f, commands.get(loser).requestId()));
            var retried = replicas.get(loser).update(ACTOR,
                new CatalogAdminApi.Update(commands.get(loser).requestId(), 1, 2, commands.get(loser).terms()));
            assertEquals(3, retried.revision());
            assertEquals(commands.get(loser).terms().priceMinor(), retried.priceMinor());
            assertEquals(3, f.count("commerce_admin_command"));
        }
    }

    @Test void 新价格和发物映射只影响新订单_保留旧冻结值及提交时库存预占() {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            store.create(ACTOR, new CatalogAdminApi.Create("freeze-create-001", 1, terms(125, 10, 2, "旧玄铁"), 20));
            var first = f.store.create("1", new MallApi.CreateOrder("frozen-order-001", 1, 2, "fixture-account", "1"));
            var frozenGrant = f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", first.orderId());
            var frozenItem = f.jdbc.queryForMap("SELECT * FROM oms_order_item WHERE order_sn=?", first.orderId());
            assertEquals(10, ((Number) frozenGrant.get("item_config_id")).intValue());
            assertEquals(2, ((Number) frozenGrant.get("count_per_unit")).intValue());
            assertEquals("旧玄铁", frozenGrant.get("title"));
            assertEquals(100, ((Number) frozenItem.get("product_id")).longValue());
            assertEquals("test-sku", frozenItem.get("product_sku_code"));
            assertEquals(125, ((Number) frozenItem.get("product_price_minor")).longValue());
            assertEquals(2, ((Number) frozenItem.get("product_quantity")).intValue());
            assertEquals(250, first.amountMinor());
            assertEquals("CNY", first.currency());
            assertEquals(10, ((Number) frozenGrant.get("item_config_id")).intValue());
            assertEquals(2, ((Number) frozenGrant.get("count_per_unit")).intValue());
            assertEquals("旧玄铁", frozenGrant.get("title"));
            assertEquals(100, ((Number) frozenItem.get("product_id")).longValue());
            assertEquals("test-sku", frozenItem.get("product_sku_code"));
            assertEquals(125, ((Number) frozenItem.get("product_price_minor")).longValue());
            assertEquals(2, ((Number) frozenItem.get("product_quantity")).intValue());
            assertEquals(1, sku(store, 1).revision(), "普通下单不增加管理修订");

            var nextTerms = new CatalogAdminApi.Terms(200, "new-sku", 999, "USD", true, 20, 3, "新玄铁");
            var changed = store.update(ACTOR, new CatalogAdminApi.Update("freeze-update-001", 1, 1, nextTerms));
            assertEquals(20, changed.stock());
            assertEquals(2, changed.lockedStock());
            assertEquals(2, changed.revision());
            assertEquals(first, f.store.get("1", new MallApi.OwnedOrder(first.orderId(), "fixture-account", "1")));
            assertEquals(frozenGrant, f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", first.orderId()));
            assertEquals(frozenItem, f.jdbc.queryForMap("SELECT * FROM oms_order_item WHERE order_sn=?", first.orderId()));

            var second = f.store.create("2", new MallApi.CreateOrder("future-order-001", 1, 1, "fixture-account", "1"));
            assertEquals(999, second.amountMinor());
            assertEquals("USD", second.currency());
            assertEquals(20, f.jdbc.queryForObject("SELECT item_config_id FROM commerce_order_grant WHERE order_sn=?", Integer.class, second.orderId()));
            assertEquals(3, f.jdbc.queryForObject("SELECT count_per_unit FROM commerce_order_grant WHERE order_sn=?", Integer.class, second.orderId()));
            var adjusted = store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("freeze-stock-001", 1, 2, -4));
            assertEquals(16, adjusted.stock());
            assertEquals(3, adjusted.lockedStock(), "差额调整不能抹掉两个原订单的预占");
            var paid = f.store.recordTrustedPayment(new MallApi.Payment(first.orderId(), "test", "frozen-pay-001", 250, "CNY"));
            assertEquals("DELIVERY_PENDING", paid.state());
            assertEquals(250, paid.amountMinor());
            assertEquals("CNY", paid.currency());
            assertEquals(14, sku(store, 1).stock());
            assertEquals(1, sku(store, 1).lockedStock());
            assertEquals(3, sku(store, 1).revision(), "付款扣库存不增加管理修订");
            assertEquals(frozenGrant, f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", first.orderId()));
            assertEquals(frozenItem, f.jdbc.queryForMap("SELECT * FROM oms_order_item WHERE order_sn=?", first.orderId()));
        }
    }

    @Test void 库存不能侵占当前预占或越过整数上界_失败回滚管理请求行() {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            store.create(ACTOR, new CatalogAdminApi.Create("bounds-create-001", 1, terms(100, 10, 1, "库存下界"), 5));
            f.store.create("1", new MallApi.CreateOrder("bounds-order-001", 1, 3, "fixture-account", "1"));
            var before = businessState(f);
            refuses(409, () -> store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-below-001", 1, 1, -3)));
            assertEquals(before, businessState(f));
            assertEquals(0, journalCount(f, "stock-below-001"));
            var atLock = store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-at-lock-001", 1, 1, -2));
            assertEquals(3, atLock.stock());
            assertEquals(3, atLock.lockedStock());
            var atLockState = businessState(f);
            refuses(409, () -> store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-below-002", 1, 2, -1)));
            assertEquals(atLockState, businessState(f));
            assertEquals(0, journalCount(f, "stock-below-002"));

            store.create(ACTOR, new CatalogAdminApi.Create("upper-create-001", 2,
                terms(MallOrderStore.MAX_AMOUNT_MINOR, 10, 4096, "整数上界"), Integer.MAX_VALUE - 1));
            var atMaximum = store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-at-max-001", 2, 1, 1));
            assertEquals(Integer.MAX_VALUE, atMaximum.stock());
            assertEquals(2, atMaximum.revision());
            var maximumState = businessState(f);
            refuses(409, () -> store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-over-max-001", 2, 2, 1)));
            refuses(409, () -> store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("stock-min-delta-001", 2, 2, Integer.MIN_VALUE)));
            refuses(400, () -> store.create(ACTOR, new CatalogAdminApi.Create("price-over-max-001", 3,
                terms(MallOrderStore.MAX_AMOUNT_MINOR + 1, 10, 1, "金额越界"), 1)));
            assertEquals(maximumState, businessState(f));
            for (String request : List.of("stock-over-max-001", "stock-min-delta-001", "price-over-max-001")) {
                assertEquals(0, journalCount(f, request));
            }
            assertEquals(4, f.count("commerce_admin_command"));
        }
    }

    @Test void 最后合法管理修订可以提交_耗尽或负修订拒绝且不留日志() {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            store.create(ACTOR, new CatalogAdminApi.Create("revision-create-001", 1, terms(100, 10, 1, "原条款"), 5));
            assertEquals(1, f.jdbc.update("UPDATE pms_sku_stock SET catalog_revision=? WHERE id=1", Long.MAX_VALUE - 1));
            var last = store.update(ACTOR,
                new CatalogAdminApi.Update("revision-last-001", 1, Long.MAX_VALUE - 1, terms(200, 20, 2, "最后修订")));
            assertEquals(Long.MAX_VALUE, last.revision());
            var before = businessState(f);
            var commands = journalRows(f);
            refuses(400, () -> store.update(ACTOR,
                new CatalogAdminApi.Update("revision-over-001", 1, Long.MAX_VALUE, terms(300, 30, 3, "不可提交"))));
            refuses(400, () -> store.adjustStock(ACTOR,
                new CatalogAdminApi.AdjustStock("revision-over-002", 1, Long.MAX_VALUE, 1)));
            refuses(400, () -> store.adjustStock(ACTOR,
                new CatalogAdminApi.AdjustStock("revision-negative-001", 1, -1, 1)));
            assertEquals(before, businessState(f));
            assertEquals(commands, journalRows(f));
            assertEquals(2, f.count("commerce_admin_command"));
        }
    }

    @Test void 坏响应日志一律返回服务不可用_不能接管重做或替换原件() throws Exception {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            store.create(ACTOR, new CatalogAdminApi.Create("journal-create-001", 1, terms(100, 10, 1, "原条款"), 10));
            var command = new CatalogAdminApi.AdjustStock("journal-stock-001", 1, 1, 1);
            var original = store.adjustStock(ACTOR, command);
            var json = new ObjectMapper();
            assertEquals(original, json.readValue(journalResult(f, command.requestId()), CatalogAdminApi.Sku.class));
            ObjectNode snapshot = json.valueToTree(original);
            var corruptions = List.of("{", "null", "{}", "pending:" + UUID.randomUUID(),
                snapshot + " junk", snapshot + " {}",
                snapshot.deepCopy().put("skuId", 2).toString(),
                snapshot.deepCopy().put("revision", 0).toString(),
                snapshot.deepCopy().put("stock", -1).toString(),
                snapshot.deepCopy().put("lockedStock", 12).toString(),
                snapshot.deepCopy().put("priceMinor", 0).toString(),
                snapshot.deepCopy().put("countPerUnit", 0).toString());
            var before = businessState(f);
            for (String corrupt : corruptions) {
                assertEquals(1, f.jdbc.update("UPDATE commerce_admin_command SET result_json=? WHERE actor=? AND request_id=?",
                    corrupt, ascii(ACTOR), ascii(command.requestId())));
                var journal = journalRows(f);
                refuses(503, () -> admin(f).adjustStock(ACTOR, command));
                assertEquals(corrupt, journalResult(f, command.requestId()));
                assertEquals(journal, journalRows(f));
                assertEquals(before, businessState(f));
                assertEquals(2, f.count("commerce_admin_command"));
            }
        }
    }

    @Test void 管理目录包含未发布及缺映射SKU_有界分页并可补齐新条款() {
        try (var f = new CatalogFixture()) {
            var store = admin(f);
            var hidden = new CatalogAdminApi.Terms(100, "hidden-sku", 125, "CNY", false, 10, 1, "未发布");
            store.create(ACTOR, new CatalogAdminApi.Create("hidden-create-001", 1, hidden, 0));
            f.jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published)"
                + " VALUES(2,200,'legacy-unmapped',250,'CNY',5,0,1)");
            var rows = store.list(new DeliveryApi.SkuPage(0, 100)).skus();
            assertEquals(2, rows.size());
            assertFalse(rows.getFirst().published());
            assertEquals(0, rows.getFirst().stock());
            var unmapped = rows.getLast();
            assertEquals(2, unmapped.skuId());
            assertEquals(0, unmapped.revision());
            assertEquals(0, unmapped.itemConfigId());
            assertNull(unmapped.title());
            assertTrue(new DeliveryStore(f.jdbc, f.manager).skus().skus().isEmpty(), "管理目录不能错用公开可售筛选");
            refuses(409, () -> store.adjustStock(ACTOR, new CatalogAdminApi.AdjustStock("unmapped-stock-001", 2, 0, 1)));
            assertEquals(unmapped, store.list(new DeliveryApi.SkuPage(1, 1)).skus().getFirst());
            assertEquals(0, journalCount(f, "unmapped-stock-001"));
            var repaired = store.update(ACTOR,
                new CatalogAdminApi.Update("mapping-repair-001", 2, 0, terms(250, 20, 2, "补齐映射")));
            assertEquals(5, repaired.stock());
            assertEquals(0, repaired.lockedStock());
            assertEquals(1, repaired.revision());
            assertEquals(List.of(repaired), store.list(new DeliveryApi.SkuPage(1, 1)).skus());
            assertTrue(store.list(new DeliveryApi.SkuPage(2, 100)).skus().isEmpty());
            refuses(400, () -> store.list(null));
            refuses(400, () -> store.list(new DeliveryApi.SkuPage(-1, 1)));
            refuses(400, () -> store.list(new DeliveryApi.SkuPage(0, 0)));
            refuses(400, () -> store.list(new DeliveryApi.SkuPage(0, 101)));
            assertEquals(2, f.count("commerce_admin_command"));
        }
    }

    @Test void 管理密钥可选但必须独立_缺失时只拒管理而旧网关仍可认证() throws Exception {
        String deliveryKey = "catalog-test-only-delivery-key-0003";
        for (String invalid : List.of("short", MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY, deliveryKey)) {
            assertThrows(IllegalArgumentException.class, () -> new MallProperties(false, MallTestDatabase.GATEWAY_KEY,
                MallTestDatabase.PAYMENT_KEY, null, null, deliveryKey, invalid, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new MallProperties(false, MallTestDatabase.GATEWAY_KEY,
            MallTestDatabase.PAYMENT_KEY, null, null, deliveryKey, "catalog-test-only-admin-key-000004", "bad/subject"));
        try (var f = new MallTestDatabase()) {
            var properties = MallTestDatabase.properties();
            assertNull(properties.adminSecret());
            assertEquals("mall-admin", properties.adminSubject());
            var filter = new MallAuthentication(properties,
                new MallNonceStore(f.jdbc, new DataSourceTransactionManager(f.source), properties));
            var blocked = signedMock("/internal/admin/skus/list", "admin", "mall-admin", "catalog-test-only-admin-key-000004");
            var response = new MockHttpServletResponse();
            var entered = new AtomicBoolean();
            filter.doFilter(blocked, response, (request, output) -> entered.set(true));
            assertEquals(401, response.getStatus());
            assertFalse(entered.get());
            var oldGateway = signedMock("/api/skus/list", "gateway", "1", MallTestDatabase.GATEWAY_KEY);
            filter.doFilter(oldGateway, new MockHttpServletResponse(), (request, output) -> entered.set(true));
            assertTrue(entered.get(), "未配置新管理密钥不能损坏旧角色服务");
            assertEquals(0, f.count("commerce_admin_command"));
        }
    }

    @Test void H2启动形状拒绝缺管理列_非二进制主体或缺幂等唯一键() throws Exception {
        try (var f = new MallTestDatabase()) {
            f.jdbc.execute("ALTER TABLE pms_sku_stock DROP COLUMN catalog_revision");
            assertThrows(SQLException.class, new MallSchemaVerifier(f.source, MallTestDatabase.properties())::verify);
        }
        try (var f = new MallTestDatabase()) {
            f.jdbc.execute("ALTER TABLE commerce_admin_command ALTER COLUMN actor VARCHAR(64)");
            var failure = assertThrows(IllegalStateException.class, new MallSchemaVerifier(f.source, MallTestDatabase.properties())::verify);
            assertTrue(failure.getMessage().contains("VARBINARY"));
        }
        try (var f = new MallTestDatabase()) {
            String primary = f.jdbc.queryForObject("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                + " WHERE TABLE_NAME='COMMERCE_ADMIN_COMMAND' AND CONSTRAINT_TYPE='PRIMARY KEY'", String.class);
            assertNotNull(primary);
            assertTrue(primary.matches("[A-Z0-9_]+"));
            f.jdbc.execute("ALTER TABLE commerce_admin_command DROP CONSTRAINT " + primary);
            var failure = assertThrows(IllegalStateException.class, new MallSchemaVerifier(f.source, MallTestDatabase.properties())::verify);
            assertTrue(failure.getMessage().contains("唯一"));
        }
    }

    @Test void H2旧V2结构执行V3后保留订单和库存_管理修订从零开始() throws Exception {
        try (var f = new MallTestDatabase()) {
            f.sku(1, 10);
            var order = f.store.create("1", new MallApi.CreateOrder("migration-order-001", 1, 2, "fixture-account", "1"));
            var frozen = f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", order.orderId());
            // 仅在本例随机H2库重建旧V2形状，再执行真实升级脚本；不碰其他测试或运行库。
            f.jdbc.execute("DROP TABLE commerce_admin_command");
            f.jdbc.execute("ALTER TABLE pms_sku_stock DROP COLUMN catalog_revision");
            var verifier = new MallSchemaVerifier(f.source, MallTestDatabase.properties());
            assertThrows(SQLException.class, verifier::verify);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__catalog_admin.sql")).execute(f.source);
            verifier.verify();
            assertEquals(order, f.store.get("1", new MallApi.OwnedOrder(order.orderId(), "fixture-account", "1")));
            assertEquals(frozen, f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", order.orderId()));
            assertEquals(10, f.stock(1, "stock"));
            assertEquals(2, f.stock(1, "lock_stock"));
            assertEquals(0L, f.jdbc.queryForObject("SELECT catalog_revision FROM pms_sku_stock WHERE id=1", Long.class).longValue());
            assertEquals(0, f.count("commerce_admin_command"));
            var store = new CatalogAdminStore(f.jdbc, new DataSourceTransactionManager(f.source), new ObjectMapper(), CLOCK);
            var updated = store.update(ACTOR,
                new CatalogAdminApi.Update("migration-update-001", 1, 0, terms(999, 20, 3, "升级后条款")));
            assertEquals(1, updated.revision());
            assertEquals(10, updated.stock());
            assertEquals(2, updated.lockedStock());
            assertEquals(order, f.store.get("1", new MallApi.OwnedOrder(order.orderId(), "fixture-account", "1")));
            assertEquals(frozen, f.jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", order.orderId()));
        }
    }

    private static MockHttpServletRequest signedMock(String path, String role, String subject, String key) {
        byte[] body = "{\"afterSkuId\":0,\"limit\":1}".getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContent(body);
        request.addHeader("X-Commerce-Subject", subject);
        request.addHeader("X-Commerce-Timestamp", timestamp);
        request.addHeader("X-Commerce-Nonce", nonce);
        request.addHeader("X-Commerce-Signature", MallAuthentication.sign(key, role, subject, timestamp, nonce, "POST", path, body));
        return request;
    }

    private static CatalogAdminStore admin(CatalogFixture f) {
        return new CatalogAdminStore(f.jdbc, f.manager, new ObjectMapper(), CLOCK);
    }

    private static CatalogAdminApi.Terms terms(long price, int item, int count, String title) {
        return new CatalogAdminApi.Terms(100, "test-sku", price, "CNY", true, item, count, title);
    }

    private static CatalogAdminApi.Sku sku(CatalogAdminStore store, long id) {
        var rows = store.list(new DeliveryApi.SkuPage(id - 1, 1)).skus();
        assertEquals(1, rows.size());
        assertEquals(id, rows.getFirst().skuId());
        return rows.getFirst();
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static int journalCount(CatalogFixture f, String request) {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_admin_command WHERE actor=? AND request_id=?",
            Integer.class, ascii(ACTOR), ascii(request));
    }

    private static String journalResult(CatalogFixture f, String request) {
        return f.jdbc.queryForObject("SELECT result_json FROM commerce_admin_command WHERE actor=? AND request_id=?",
            String.class, ascii(ACTOR), ascii(request));
    }

    private static List<Map<String, Object>> journalRows(CatalogFixture f) {
        return f.jdbc.queryForList("SELECT command_type,request_digest,result_json,created_ms FROM commerce_admin_command ORDER BY actor,request_id");
    }

    private static Map<String, List<Map<String, Object>>> businessState(CatalogFixture f) {
        // 避开VARBINARY请求键的Java数组对象身份；比较实际持久业务列和冻结合同。
        return Map.of(
            "sku", f.jdbc.queryForList("SELECT * FROM pms_sku_stock ORDER BY id"),
            "skuGrant", f.jdbc.queryForList("SELECT * FROM commerce_sku_grant ORDER BY sku_id"),
            "order", f.jdbc.queryForList("SELECT id,order_sn,buyer_id,sku_id,quantity,pay_amount,currency,status,"
                + "create_time_ms,payment_deadline_ms,payment_time_ms FROM oms_order ORDER BY id"),
            "item", f.jdbc.queryForList("SELECT * FROM oms_order_item ORDER BY order_sn"),
            "orderGrant", f.jdbc.queryForList("SELECT * FROM commerce_order_grant ORDER BY order_sn"));
    }

    private static void refuses(int status, Executable command) {
        assertEquals(status, assertThrows(MallException.class, command).status());
    }

    private static <T> List<T> parallel(CatalogFixture fixture, int count, IntFunction<T> command) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        List<T> results = new ArrayList<>();
        List<Executable> failures = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Throwable primary = null;
        try {
            fixture.trackActors(executor);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("并发起跑门超时"); }
                    return command.apply(index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "本例并发任务未全部就绪");
            start.countDown();
            for (var future : futures) {
                try { results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
                catch (ExecutionException failed) {
                    if (MockPaymentTestDatabase.unknownActorFailure(failed)) fixture.retainUnknownActors(failed);
                    Throwable cause = failed.getCause();
                    failures.add(() -> { throw cause; });
                } catch (TimeoutException timedOut) {
                    fixture.retainUnknownActors(timedOut);
                    future.cancel(true);
                    failures.add(() -> { throw timedOut; });
                }
            }
            assertAll("主线程收集全部并发断言与执行错误", failures);
            return results;
        } catch (Exception | Error failure) {
            primary = failure;
            if (MockPaymentTestDatabase.unknownActorFailure(failure)) fixture.retainUnknownActors(failure);
            throw failure;
        } finally {
            start.countDown();
            Throwable cleanup = null;
            boolean interrupted = Thread.interrupted() || primary instanceof InterruptedException;
            for (var future : futures) try { if (!future.isDone()) future.cancel(true); }
            catch (RuntimeException | Error failure) { cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
            try { executor.shutdownNow(); } catch (RuntimeException | Error failure) { cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
            try { assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "本例executor未在期限内清理"); }
            catch (Exception | Error failure) { if (failure instanceof InterruptedException) interrupted = true; cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (cleanup != null) {
                fixture.retainUnknownActors(cleanup);
                if (primary != null) { if (cleanup != primary) primary.addSuppressed(cleanup); }
                else MockPaymentTestDatabase.raiseFailure(cleanup);
            }
        }
    }

    /** H2原分支不变；真库复用同一owned producer和NORMAL physical接缝。 */
    private static final class CatalogFixture implements AutoCloseable {
        final MallTestDatabase h2;
        final MockPaymentTestDatabase owned;
        final DataSource source;
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final MallOrderStore store;
        private Throwable h2ActorUnknown;
        private final java.util.Set<java.util.concurrent.ExecutorService> h2Actors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        synchronized void trackActors(java.util.concurrent.ExecutorService executor) throws SQLException {
            if (owned != null) { owned.trackActors(executor); return; }
            if (h2ActorUnknown != null) throw new SQLException("目录H2 actor结局未知");
            h2Actors.add(executor);
        }
        synchronized void retainUnknownActors(Throwable failure) {
            if (owned != null) owned.retainUnknownActors(failure);
            else if (h2ActorUnknown == null) h2ActorUnknown = failure;
        }

        CatalogFixture() {
            if (System.getProperty("game.it.mysql") == null) {
                h2 = new MallTestDatabase();
                owned = null;
                source = h2.source;
                jdbc = h2.jdbc;
                manager = new DataSourceTransactionManager(source);
                store = h2.store;
                return;
            }
            h2 = null;
            owned = new MockPaymentTestDatabase("mallcatalog", MallTestDatabase.properties(true));
            source = owned.source;
            jdbc = owned.jdbc;
            manager = new DataSourceTransactionManager(source);
            store = new MallOrderStore(jdbc, manager, CLOCK, owned.props);
        }

        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

        @Override public synchronized void close() {
            if (h2 != null) {
                for (var executor : h2Actors) if (!executor.isTerminated()) retainUnknownActors(new IllegalStateException("目录H2 actor未终结"));
                if (h2ActorUnknown != null) throw new IllegalStateException("目录H2 actor结局未知，拒绝SHUTDOWN", h2ActorUnknown);
                h2.close(); return;
            }
            owned.close();
        }
    }
}
