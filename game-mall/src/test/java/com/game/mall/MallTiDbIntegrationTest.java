package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式启用的真实TiDB验证，随机namespace与库一一对应，仅销毁本类新建的专属库。 */
@EnabledIfSystemProperty(named = "game.mall.tidb-test", matches = "true")
class MallTiDbIntegrationTest {
    static final String DATABASE = "mallit" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    static String JDBC_BASE;
    static com.game.common.sql.testing.AuthoritySqlFixture fixture;
    static ConfigurableApplicationContext context;
    static HikariDataSource pool;
    static JdbcTemplate jdbc;
    static MallOrderStore first;
    static MallOrderStore second;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class OwnedAuthorityConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.game.common.sql.WriterAuthority mallOwnedWriterAuthority() { return fixture.writerBoot(); }
    }

    @BeforeAll
    static void start() throws Exception {
        assertThat(DATABASE).matches("mallit[a-z0-9]{20}");
        try {
            var options = MockPaymentTestDatabase.ownedOptions("mallit");
            JDBC_BASE = options.root().replaceAll("/$", "") + "/";
            fixture = MockPaymentTestDatabase.createOwned(options, MallTestDatabase.properties(true),
                    GameNamespace.of(DATABASE));
            SpringApplication app = new SpringApplication(MallApplication.class, OwnedAuthorityConfiguration.class);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            context = app.run("--game.namespace=" + DATABASE, "--game.run-mode=prod", "--game.mall.require-tidb=true",
                    "--game.mall.allowed-payment-channels=test-channel", "--server.address=127.0.0.1", "--server.port=0",
                    "--game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
                    "--game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
                    "--spring.datasource.url=" + fixture.jdbcUrl(""),
                    "--spring.datasource.username=" + fixture.normalUser(),
                    "--spring.datasource.password=" + fixture.normalPassword(), "--spring.sql.init.mode=never");
            assertThat(context.getBean(GameNamespace.class).value()).isEqualTo(DATABASE);
            pool = context.getBean(HikariDataSource.class);
            jdbc = context.getBean(JdbcTemplate.class);
            first = context.getBean(MallOrderStore.class);
            second = new MallOrderStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool),
                    Clock.systemUTC(), context.getBean(MallProperties.class));
        } catch (Exception | Error failure) {
            try { stop(); } catch (Exception | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (fixture != null) fixture.requireNormalActorsTerminated();
        Throwable failure = null;
        try { if (context != null) context.close(); }
        catch (RuntimeException | Error cleanup) {
            failure = cleanup;
            if (fixture != null) fixture.retainUnknownActorOutcome(cleanup);
        }
        finally { context = null; }
        try { if (fixture != null) fixture.close(); }
        catch (Exception | Error cleanup) {
            failure = MockPaymentTestDatabase.combineFailure(failure, cleanup);
        } finally { fixture = null; }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }
    @Test
    void 真TiDB装配核对namespace与每物理连接强读事务() throws Exception {
        DataSourceProperties properties = new DataSourceProperties();
        String snapshot = currentSnapshot();
        properties.setUrl(JDBC_BASE + DATABASE + "?sessionVariables=" + URLEncoder.encode(
                "tidb_read_consistency='weak',tidb_snapshot='" + snapshot + "'", StandardCharsets.UTF_8));
        properties.setUsername(pool.getUsername());
        properties.setPassword(pool.getPassword());
        try (var overridden = (HikariDataSource) new MallDatabaseConfiguration()
                .dataSource(properties, context.getBean(MallProperties.class), "prod")) {
            MockPaymentTestDatabase.protectPool(overridden, fixture);
            List<Connection> held = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    Connection connection = overridden.getConnection();
                    held.add(connection);
                    assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                    try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT DATABASE(),TIDB_VERSION(),@@session.tidb_txn_mode,@@session.tidb_replica_read,@@session.tidb_read_staleness,@@session.tidb_low_resolution_tso,@@session.sql_mode,@@session.innodb_lock_wait_timeout,@@session.tidb_read_consistency,@@session.tidb_snapshot")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).isEqualTo(DATABASE);
                        assertThat(row.getString(2)).isNotBlank();
                        assertThat(row.getString(3)).isEqualToIgnoringCase("pessimistic");
                        assertThat(row.getString(4)).isEqualToIgnoringCase("leader");
                        assertThat(row.getLong(5)).isZero();
                        assertThat(row.getString(6)).isIn("0", "OFF");
                        assertThat(row.getString(7)).contains("STRICT_TRANS_TABLES");
                        assertThat(row.getInt(8)).isEqualTo(5);
                        assertThat(row.getString(9)).isEqualTo("strict");
                        assertThat(row.getString(10)).isEmpty();
                    }
                }
            } finally { for (Connection connection : held) connection.close(); }
        }
        context.getBean(MallSchemaVerifier.class).verify();
        assertThat(pool.getDataSource()).isInstanceOf(com.game.common.sql.AuthorityDataSource.class);
        try (var connection = pool.getConnection(); var sql = connection.createStatement();
             var actual = sql.executeQuery("SELECT CURRENT_USER()")) {
            assertThat(actual.next()).isTrue();
            assertThat(actual.getString(1)).isEqualTo(fixture.normalUser() + "@%");
            assertThat(actual.next()).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"weak", "snapshot"})
    void 弱一致或历史快照会话必须拒绝启动(String setting) throws Exception {
        String snapshot = currentSnapshot();
        // 只在本owner的独立故障会话造弱读；NORMAL池仍保持正式强读与SQL门禁。
        fixture.withOwnerSchema(connection -> {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(5);
            var single = new SingleConnectionDataSource(connection, true);
            Throwable verificationFailure = null;
            try {
                sql.execute("weak".equals(setting) ? "SET SESSION tidb_read_consistency='weak'"
                        : "SET SESSION tidb_snapshot='" + snapshot + "'");
                assertThatThrownBy(new MallSchemaVerifier(single, context.getBean(MallProperties.class))::verify)
                        .isInstanceOf(IllegalStateException.class);
            } catch (Exception | Error failure) {
                verificationFailure = failure;
                throw failure;
            } finally {
                try { sql.execute("SET SESSION tidb_snapshot='',tidb_read_consistency='strict'"); }
                catch (Exception | Error cleanup) {
                    if (verificationFailure == null) throw cleanup;
                    if (cleanup != verificationFailure) verificationFailure.addSuppressed(cleanup);
                }
            }
        }
        return null;
        });
    }

    private static String currentSnapshot() {
        return new TransactionTemplate(new DataSourceTransactionManager(pool)).execute(status -> {
            jdbc.queryForList("SELECT nonce FROM commerce_request_nonce WHERE caller_role='probe' AND nonce=X'00'");
            return jdbc.queryForObject("SELECT CAST(TIDB_CURRENT_TSO() AS CHAR)", String.class);
        });
    }

    @Test
    void 两实例同请求并发只占一次且未付额度不会超发() throws Exception {
        sku(101, 50);
        var workers = Executors.newFixedThreadPool(8);
        List<Future<?>> actors = new ArrayList<>();
        Throwable actorFailure = null;
        try {
            fixture.trackNormalActors(workers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MallApi.Order>> replies = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                MallOrderStore service = i % 2 == 0 ? first : second;
                replies.add(submitTracked(workers, actors, () -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return service.create("18446744073709551615", new MallApi.CreateOrder("tidb-same-001", 101, 1, "fixture-account", "1")); }));
            }
            start.countDown();
            String id = replies.getFirst().get(15, TimeUnit.SECONDS).orderId();
            for (var reply : replies) assertThat(reply.get(15, TimeUnit.SECONDS).orderId()).isEqualTo(id);
            assertThat(stock(101, "lock_stock")).isEqualTo(1);
            CountDownLatch capStart = new CountDownLatch(1);
            List<Future<Boolean>> capped = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int index = i;
                capped.add(submitTracked(workers, actors, () -> { assertThat(capStart.await(5, TimeUnit.SECONDS)).isTrue(); try {
                    (index % 2 == 0 ? first : second).create("123", new MallApi.CreateOrder("tidb-cap-00" + index, 101, 1, "fixture-account", "1")); return true;
                } catch (MallException failure) { assertThat(failure.status()).isEqualTo(429); return false; } }));
            }
            capStart.countDown();
            int success = 0;
            for (var reply : capped) if (reply.get(15, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(5);
            assertThat(stock(101, "lock_stock")).isEqualTo(6);
        } catch (Exception | Error failure) {
            actorFailure = failure;
            if (MockPaymentTestDatabase.unknownActorFailure(failure)) fixture.retainUnknownActorOutcome(failure);
            throw failure;
        } finally { finishMallActors(workers, actors, actorFailure); }
    }

    @Test
    void 真事务付款取消竞争_收据重放_失败回滚() throws Exception {
        sku(102, 20);
        var workers = Executors.newFixedThreadPool(2);
        List<Future<?>> actors = new ArrayList<>();
        Throwable actorFailure = null;
        try {
            fixture.trackNormalActors(workers);
            for (int i = 0; i < 6; i++) {
                String buyer = Integer.toString(200 + i);
                MallApi.Order order = first.create(buyer, new MallApi.CreateOrder("tidb-race-00" + i, 102, 1, "fixture-account", "1"));
                CountDownLatch start = new CountDownLatch(1);
                String transaction = "tidb-race-pay-" + i;
                var paid = submitTracked(workers, actors, () -> compete(start, () -> first.recordTrustedPayment(payment(order, transaction))));
                var cancelled = submitTracked(workers, actors, () -> compete(start, () -> second.cancel(buyer, owned(order.orderId()))));
                start.countDown();
                assertThat(paid.get(15, TimeUnit.SECONDS)).isTrue();
                boolean cancelledFirst=cancelled.get(15,TimeUnit.SECONDS);
                MallApi.Order actual = first.get(buyer, owned(order.orderId()));
                int rows = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=?", Integer.class, order.orderId());
                assertThat(rows).isEqualTo(1);
                assertThat(actual.state()).isEqualTo(cancelledFirst?"REFUND_PENDING":"DELIVERY_PENDING");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent WHERE order_sn=?", Integer.class, order.orderId())).isEqualTo(cancelledFirst?0:1);
                assertThat(second.recordTrustedPayment(payment(order, transaction))).isEqualTo(actual);
            }
        } catch (Exception | Error failure) {
            actorFailure = failure;
            if (MockPaymentTestDatabase.unknownActorFailure(failure)) fixture.retainUnknownActorOutcome(failure);
            throw failure;
        } finally { finishMallActors(workers, actors, actorFailure); }
        assertThat(stock(102, "lock_stock")).isZero();
        MallApi.Order rollback = first.create("300", new MallApi.CreateOrder("tidb-rollback", 102, 1, "fixture-account", "1"));
        jdbc.update("INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms) VALUES (?,'300',102,1,'PENDING',0)", rollback.orderId());
        int before = stock(102, "stock");
        assertThatThrownBy(() -> first.recordTrustedPayment(payment(rollback, "tidb-rollback-pay"))).isInstanceOf(MallException.class);
        assertThat(first.get("300", owned(rollback.orderId())).state()).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=?", Integer.class, rollback.orderId())).isZero();
        assertThat(stock(102, "stock")).isEqualTo(before);
        assertThat(stock(102, "lock_stock")).isEqualTo(1);
        second.cancel("300", owned(rollback.orderId()));
    }

    @Test
    void 数据库TSO时窗与跨实例防重放实际生效() {
        MallNonceStore firstNonce = context.getBean(MallNonceStore.class);
        MallNonceStore otherNonce = new MallNonceStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool), context.getBean(MallProperties.class));
        long now = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        firstNonce.claim("gateway", nonce, now);
        String upper = "A" + nonce, lower = "a" + nonce;
        firstNonce.claim("gateway", upper, now); otherNonce.claim("gateway", lower, now);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_request_nonce WHERE caller_role='gateway' AND nonce IN (?,?)",
                Integer.class, upper.getBytes(StandardCharsets.US_ASCII), lower.getBytes(StandardCharsets.US_ASCII))).isEqualTo(2);
        assertThatThrownBy(() -> otherNonce.claim("gateway", nonce, now)).isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(409));
        assertThatThrownBy(() -> otherNonce.claim("gateway", UUID.randomUUID().toString(), now - 600_000)).isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(401));
        assertThatThrownBy(() -> otherNonce.claim("gateway", UUID.randomUUID().toString(), now + 600_000)).isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(401));
    }

    @Test
    void 交付意图冻结原SKU_可信持久回执可跨实例恢复_拒绝到账才走渠道退款() {
        sku(501,10);jdbc.update("UPDATE commerce_sku_grant SET count_per_unit=2 WHERE sku_id=501");
        var order=first.create("501",new MallApi.CreateOrder("delivery-tidb-001", 501, 2, "fixture-account", "1"));
        jdbc.update("UPDATE commerce_sku_grant SET count_per_unit=9,item_config_id=99 WHERE sku_id=501");
        first.recordTrustedPayment(payment(order,"delivery-tidb-pay"));
        var one=new DeliveryStore(jdbc,new DataSourceTransactionManager(pool),context.getBean(MallProperties.class));var two=new DeliveryStore(new JdbcTemplate(pool),new DataSourceTransactionManager(pool),context.getBean(MallProperties.class));
        var intent=one.poll(100).stream().filter(v->v.orderId().equals(order.orderId())).findFirst().orElseThrow();assertThat(intent.itemConfigId()).isEqualTo(10);assertThat(intent.itemCount()).isEqualTo(4);
        var receipt=new DeliveryApi.Receipt(order.orderId(),"9001","DELIVERED","a".repeat(64));one.receipt(receipt);two.receipt(receipt);assertThat(second.get("501",owned(order.orderId())).state()).isEqualTo("DELIVERED");
        assertThatThrownBy(()->two.receipt(new DeliveryApi.Receipt(order.orderId(),"9002","DELIVERED","a".repeat(64)))).isInstanceOf(MallException.class);
        var refund=first.create("502",new MallApi.CreateOrder("delivery-tidb-002", 501, 1, "fixture-account", "1"));first.recordTrustedPayment(payment(refund,"refund-tidb-pay"));one.receipt(new DeliveryApi.Receipt(refund.orderId(),"9003","REFUND_PENDING","b".repeat(64)));
        assertThatThrownBy(()->two.refunded(new MallApi.Payment(refund.orderId(),"test-channel","refund-tidb-001",1,"CNY"))).isInstanceOf(MallException.class);
        var channel=new MallApi.Payment(refund.orderId(),"test-channel","refund-tidb-001",refund.amountMinor(),refund.currency());one.refunded(channel);two.refunded(channel);assertThat(second.get("502",owned(refund.orderId())).state()).isEqualTo("REFUNDED");
    }

    @Test
    void 交付固定上界和同毫秒游标跨副本不漏后页_新增和终结下轮收敛() {
        var one = new DeliveryStore(jdbc, new DataSourceTransactionManager(pool), context.getBean(MallProperties.class));
        var two = new DeliveryStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool), context.getBean(MallProperties.class));
        String prefix = UUID.randomUUID().toString().substring(0, 24);
        long created = jdbc.queryForObject("SELECT COALESCE(MAX(created_ms),0)+10000 FROM commerce_delivery_intent", Long.class);
        List<String> owned = new ArrayList<>();
        try {
            for (int i = 1; i <= 25; i++) {
                String order = prefix + String.format("%012x", i);
                owned.add(order);
                seedDelivery(order, created);
            }
            String upper = owned.getLast();
            var initial = one.poll(new DeliveryApi.Poll(1, ""));
            assertThat(initial.entries()).hasSize(1);
            assertThat(initial.entries().getFirst().cursor()).endsWith(":" + created + ":" + upper);
            // 下界只排除本类其它用例的较早夹具；25 个同毫秒意图全部仍在窗口内。
            String before = "v1:" + (created - 1) + ":ffffffff-ffff-ffff-ffff-ffffffffffff:" + created + ":" + upper;
            var firstPage = one.poll(new DeliveryApi.Poll(20, before));
            assertThat(firstPage.entries()).extracting(entry -> entry.intent().orderId()).containsExactlyElementsOf(owned.subList(0, 20));
            assertThat(two.poll(new DeliveryApi.Poll(20, before))).isEqualTo(firstPage);
            String afterTwenty = firstPage.entries().getLast().cursor();
            assertThat(afterTwenty).isNotEmpty();
            var originalIntent = firstPage.entries().getFirst().intent();
            one.receipt(new DeliveryApi.Receipt(owned.get(2), "900123", "DELIVERED", "e".repeat(64)));

            String appended = prefix + "00000000001a";
            owned.add(appended);
            seedDelivery(appended, created + 1);
            String insertedBefore = prefix + "000000000000";
            owned.add(insertedBefore);
            seedDelivery(insertedBefore, created);
            var tail = two.poll(new DeliveryApi.Poll(20, afterTwenty));
            assertThat(tail.entries()).extracting(entry -> entry.intent().orderId()).containsExactlyElementsOf(owned.subList(20, 25));
            assertThat(tail.entries().getLast().cursor()).isEmpty();
            for (int i = 20; i < 25; i++)
                one.receipt(new DeliveryApi.Receipt(owned.get(i), "9100" + i, "DELIVERED", "f".repeat(64)));
            assertThat(two.poll(new DeliveryApi.Poll(20, afterTwenty)).entries()).isEmpty();
            assertThat(two.poll(new DeliveryApi.Poll(20, before)).entries().stream()
                    .map(DeliveryApi.Entry::intent).filter(intent -> intent.orderId().equals(originalIntent.orderId())).findFirst())
                    .contains(originalIntent);

            // 重启从首页重放；固定窗口结束后，后插入的旧键与新上界之外的意图都能再次扫到。
            var restarted = new DeliveryStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool), context.getBean(MallProperties.class));
            List<String> nextRound = new ArrayList<>();
            String cursor = "";
            int maxPages = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent WHERE status='PENDING'", Integer.class) / 20 + 2;
            for (int page = 0; page < maxPages; page++) {
                var polled = restarted.poll(new DeliveryApi.Poll(20, cursor));
                if (polled.entries().isEmpty())
                    break;
                for (var entry : polled.entries()) nextRound.add(entry.intent().orderId());
                cursor = polled.entries().getLast().cursor();
                if (cursor.isEmpty()) break;
            }
            assertThat(cursor).isEmpty();
            assertThat(nextRound).contains(appended, insertedBefore).doesNotContain(owned.get(2));
            assertThat(nextRound).doesNotContainAnyElementsOf(owned.subList(20, 25));
            assertThat(nextRound.stream().filter(owned::contains).toList()).doesNotHaveDuplicates().hasSize(21);
        } finally {
            for (String order : owned) {
                jdbc.update("DELETE FROM commerce_delivery_receipt WHERE order_sn=?", order);
                jdbc.update("DELETE FROM commerce_delivery_intent WHERE order_sn=?", order);
                jdbc.update("DELETE FROM commerce_order_grant WHERE order_sn=?", order);
                jdbc.update("DELETE FROM commerce_official_owner WHERE order_sn=?", order);
                jdbc.update("DELETE FROM oms_order WHERE order_sn=?", order);
            }
        }
    }

    @Test
    void 交付畸形游标和越界页长必须整体拒绝() {
        var delivery = new DeliveryStore(jdbc, new DataSourceTransactionManager(pool), context.getBean(MallProperties.class));
        String first = "00000000-0000-0000-0000-000000000001";
        String second = "00000000-0000-0000-0000-000000000002";
        for (String invalid : List.of(" ", "v2:1:" + first + ":2:" + second,
                "v1:-1:" + first + ":2:" + second, "v1:01:" + first + ":2:" + second,
                "v1:9223372036854775808:" + first + ":9223372036854775808:" + second,
                "v1:1:ABCDEF00-0000-0000-0000-000000000001:2:" + second,
                "v1:1:" + first + ":2:not-an-order", "v1:3:" + first + ":2:" + second,
                "v1:2:" + second + ":2:" + first, "v1:1:" + first + ":2:" + second + ":extra", "x".repeat(257))) {
            assertThatThrownBy(() -> delivery.poll(new DeliveryApi.Poll(20, invalid)))
                    .isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(400));
        }
        for (int limit : List.of(0, 101))
            assertThatThrownBy(() -> delivery.poll(new DeliveryApi.Poll(limit, "")))
                    .isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(400));
    }

    @Test
    void 不同买家竞争最后库存仅一笔成功_失败事务不留订单() throws Exception {
        sku(99001, 1);
        var childrenBefore = orderWriteCounts();
        var workers = Executors.newFixedThreadPool(2);
        List<Future<?>> actors = new ArrayList<>();
        Throwable actorFailure = null;
        try {
            fixture.trackNormalActors(workers);
            var start = new CountDownLatch(1);
            var left = submitTracked(workers, actors, () -> compete(start, () -> first.create("99001",
                    new MallApi.CreateOrder("last-stock-left", 99001, 1, "left-account", "1"))));
            var right = submitTracked(workers, actors, () -> compete(start, () -> second.create("99002",
                    new MallApi.CreateOrder("last-stock-right", 99001, 1, "right-account", "1"))));
            start.countDown();
            assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(stock(99001, "stock")).isEqualTo(1);
            assertThat(stock(99001, "lock_stock")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE sku_id=99001", Integer.class)).isEqualTo(1);
            String winner = jdbc.queryForObject("SELECT order_sn FROM oms_order WHERE sku_id=99001", String.class);
            boolean leftWon = left.get(0, TimeUnit.NANOSECONDS);
            String buyer = leftWon ? "99001" : "99002", account = leftWon ? "left-account" : "right-account";
            assertThat(jdbc.queryForObject("SELECT buyer_id FROM oms_order WHERE order_sn=?", String.class, winner)).isEqualTo(buyer);
            assertThat(jdbc.queryForObject("SELECT buyer_account FROM commerce_official_owner WHERE order_sn=?", byte[].class, winner))
                    .containsExactly(account.getBytes(StandardCharsets.UTF_8));
            assertThat(jdbc.queryForObject("SELECT buyer_owner_revision FROM commerce_official_owner WHERE order_sn=?", byte[].class, winner))
                    .containsExactly("1".getBytes(StandardCharsets.US_ASCII));
            var item = jdbc.queryForMap("SELECT product_id,product_sku_id,product_sku_code,product_quantity,product_price_minor FROM oms_order_item WHERE order_sn=?", winner);
            assertThat(((Number)item.get("product_id")).longValue()).isEqualTo(99001);
            assertThat(((Number)item.get("product_sku_id")).longValue()).isEqualTo(99001);
            assertThat(item.get("product_sku_code")).isEqualTo("tidb-99001");
            assertThat(((Number)item.get("product_quantity")).intValue()).isEqualTo(1);
            assertThat(((Number)item.get("product_price_minor")).longValue()).isEqualTo(1250);
            var grant = jdbc.queryForMap("SELECT item_config_id,count_per_unit,title FROM commerce_order_grant WHERE order_sn=?", winner);
            assertThat(((Number)grant.get("item_config_id")).intValue()).isEqualTo(10);
            assertThat(((Number)grant.get("count_per_unit")).intValue()).isEqualTo(1);
            assertThat(grant.get("title")).isEqualTo("玄铁");
            var childrenAfter = orderWriteCounts();
            for (String table : childrenBefore.keySet()) assertThat(childrenAfter.get(table)).as(table).isEqualTo(childrenBefore.get(table) + 1);
        } catch (Exception | Error failure) {
            actorFailure = failure;
            if (MockPaymentTestDatabase.unknownActorFailure(failure)) fixture.retainUnknownActorOutcome(failure);
            throw failure;
        } finally { finishMallActors(workers, actors, actorFailure); }
    }

    @Test
    void 无发物映射或交付数量越界拒绝且库存订单原子回滚() {
        sku(99002, 10);
        var childrenBefore = orderWriteCounts();
        jdbc.update("DELETE FROM commerce_sku_grant WHERE sku_id=99002");
        assertThatThrownBy(() -> first.create("99003", new MallApi.CreateOrder("no-grant-order", 99002, 1, "fixture-account", "1")))
                .isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(409));
        assertThat(stock(99002, "lock_stock")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE sku_id=99002", Integer.class)).isZero();
        assertThat(orderWriteCounts()).isEqualTo(childrenBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_buyer_guard WHERE buyer_id='99003'", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(99002,10,4096,'越界夹具')");
        assertThatThrownBy(() -> second.create("99003", new MallApi.CreateOrder("overflow-grant-order", 99002, 2, "fixture-account", "1")))
                .isInstanceOfSatisfying(MallException.class, failure -> assertThat(failure.status()).isEqualTo(409));
        assertThat(stock(99002, "stock")).isEqualTo(10);
        assertThat(stock(99002, "lock_stock")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE sku_id=99002", Integer.class)).isZero();
        assertThat(orderWriteCounts()).isEqualTo(childrenBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_buyer_guard WHERE buyer_id='99003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 真实签名HTTP经同ownerNORMAL提交订单和付款_未冒游戏到账() throws Exception {
        sku(99003, 3);
        var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        String buyer = "99004";
        String body = json.writeValueAsString(new MallApi.CreateOrder("real-tidb-http-order", 99003, 2, "fixture-account", "1"));
        var request = signedHttp("gateway", buyer, "/api/orders", body, UUID.randomUUID().toString());
        try (var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()) {
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var order = json.readTree(response.body());
        String id = order.get("orderId").asText();
        assertThat(order.get("buyerId").asText()).isEqualTo(buyer);
        assertThat(order.get("amountMinor").asLong()).isEqualTo(2500);
        assertThat(order.get("state").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        var replay = client.send(signedHttp("gateway", buyer, "/api/orders", body, UUID.randomUUID().toString()),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).get("orderId").asText()).isEqualTo(id);
        var payment = new MallApi.Payment(id, "test-channel", "real-tidb-http-payment", 2500, "CNY");
        var forbidden = client.send(signedHttp("gateway", buyer, "/internal/payments", json.writeValueAsString(payment), UUID.randomUUID().toString()),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(401);
        var paid = client.send(signedHttp("payment", "payment-adapter", "/internal/payments", json.writeValueAsString(payment), UUID.randomUUID().toString()),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(json.readTree(paid.body()).get("state").asText()).isEqualTo("DELIVERY_PENDING");
        assertThat(first.get(buyer, owned(id)).state()).isEqualTo("DELIVERY_PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order WHERE sku_id=99003", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent WHERE order_sn=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_receipt WHERE order_sn=?", Integer.class, id)).isZero();
        assertThat(stock(99003, "stock")).isEqualTo(1);
        assertThat(stock(99003, "lock_stock")).isZero();
        } catch (Exception | Error failure) {
            if (MockPaymentTestDatabase.unknownActorFailure(failure)) fixture.retainUnknownActorOutcome(failure);
            throw failure;
        }
    }

    /** 全表计数含孤儿子行；不以JOIN已回滚的父表掩盖错误残留。buyer_guard按正式契约独立预建。 */
    private static java.util.Map<String,Integer> orderWriteCounts() {
        var counts = new java.util.LinkedHashMap<String,Integer>();
        for (String table : List.of("oms_order", "commerce_official_owner", "oms_order_item", "commerce_order_grant"))
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        return java.util.Map.copyOf(counts);
    }

    private static <T> Future<T> submitTracked(java.util.concurrent.ExecutorService executor, List<Future<?>> actors,
                                             java.util.concurrent.Callable<T> command) {
        Future<T> future = executor.submit(command); actors.add(future); return future;
    }
    private static void finishMallActors(java.util.concurrent.ExecutorService executor, List<Future<?>> actors,
                                         Throwable primary) throws Exception {
        Throwable cleanup = null;
        boolean interrupted = Thread.interrupted() || primary instanceof InterruptedException;
        for (var future : actors) try { if (!future.isDone()) future.cancel(true); }
        catch (RuntimeException | Error failure) { cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
        try { executor.shutdownNow(); } catch (RuntimeException | Error failure) { cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
        try { assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("Mall actor须在原清理期限内终结").isTrue(); }
        catch (Exception | Error failure) { if (failure instanceof InterruptedException) interrupted = true; cleanup = MockPaymentTestDatabase.combineFailure(cleanup, failure); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (cleanup != null) {
            fixture.retainUnknownActorOutcome(cleanup);
            if (primary != null) { if (cleanup != primary) primary.addSuppressed(cleanup); }
            else MockPaymentTestDatabase.raiseFailure(cleanup);
        }
    }

    private static java.net.http.HttpRequest signedHttp(String role, String subject, String path, String body, String nonce) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String secret = role.equals("payment") ? MallTestDatabase.PAYMENT_KEY : MallTestDatabase.GATEWAY_KEY;
        String signature = MallAuthentication.sign(secret, role, subject, timestamp, nonce, "POST", path, body.getBytes(StandardCharsets.UTF_8));
        int port = ((org.springframework.boot.web.context.WebServerApplicationContext) context).getWebServer().getPort();
        return java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject).header("X-Commerce-Timestamp", timestamp)
                .header("X-Commerce-Nonce", nonce).header("X-Commerce-Signature", signature)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    }
    /** 仅为 SQL 扫描夹具写入已冻结的原意图，不声称经过真实付款或资产到账。 */
    private static void seedDelivery(String order, long created) {
        new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO oms_order(order_sn,buyer_id,request_id,sku_id,quantity,pay_amount,currency,status,create_time_ms,payment_deadline_ms)"
                    + " VALUES(?,'88001',?,88001,1,1250,'CNY',1,?,?)", order, order.getBytes(StandardCharsets.UTF_8), created, created + 60000);
            jdbc.update("INSERT INTO commerce_order_grant(order_sn,item_config_id,count_per_unit,title) VALUES(?,10,1,'玄铁')", order);
            jdbc.update("INSERT INTO commerce_official_owner(order_sn,buyer_account,buyer_owner_revision) VALUES(?,?,?)",
                order,"fixture-account".getBytes(StandardCharsets.UTF_8),"1".getBytes(StandardCharsets.US_ASCII));
            jdbc.update("INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms) VALUES(?,'88001',88001,1,'PENDING',?)", order, created);
        });
    }

    private static MallApi.OwnedOrder owned(String orderId) { return new MallApi.OwnedOrder(orderId,"fixture-account","1"); }

    private static void sku(long id, int stock) { jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (?,?,?,1250,'CNY',?,0,1)", id, id, "tidb-" + id, stock);jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(?,10,1,'玄铁')",id); }
    private static int stock(long id, String column) { return jdbc.queryForObject("SELECT " + column + " FROM pms_sku_stock WHERE id=?", Integer.class, id); }
    private static MallApi.Payment payment(MallApi.Order order, String transaction) { return new MallApi.Payment(order.orderId(), "test-channel", transaction, order.amountMinor(), order.currency()); }
    private static boolean compete(CountDownLatch start, Runnable operation) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try { operation.run(); return true; }
        catch (MallException failure) { assertThat(failure.status()).isEqualTo(409); return false; }
    }

}
