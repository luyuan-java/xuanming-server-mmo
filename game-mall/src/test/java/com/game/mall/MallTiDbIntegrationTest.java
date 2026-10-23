package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式启用的真实TiDB验证，随机namespace与库一一对应，仅销毁本类新建的专属库。 */
@EnabledIfSystemProperty(named = "game.mall.tidb-test", matches = "true")
class MallTiDbIntegrationTest {
    static final String DATABASE = "mallit" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    static final String JDBC_BASE = "jdbc:mysql://127.0.0.1:" + tiDbTestPort() + "/";
    static final String ADMIN_URL = JDBC_BASE + "?connectTimeout=3000&socketTimeout=6000";
    static ConfigurableApplicationContext context;
    static HikariDataSource pool;
    static JdbcTemplate jdbc;
    static MallOrderStore first;
    static MallOrderStore second;
    static boolean created;

    /** 只配置本机TiDB测试端口，非法数值必须在连接前失败。 */
    private static int tiDbTestPort() {
        int port = Integer.parseInt(System.getProperty("game.it.tidb.port", "4000"));
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("game.it.tidb.port必须在1..65535");
        return port;
    }
    @BeforeAll
    static void start() throws Exception {
        assertThat(DATABASE).matches("mallit[a-z0-9]{20}");
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, "root", ""); var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE `" + DATABASE + "`");
            created = true;
        }
        try {
            DriverManagerDataSource migration = new DriverManagerDataSource(JDBC_BASE + DATABASE
                    + "?connectTimeout=3000&socketTimeout=6000", "root", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__official_sku.sql")).execute(migration);
            SpringApplication app = new SpringApplication(MallApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            context = app.run("--game.namespace=" + DATABASE, "--game.run-mode=prod", "--game.mall.require-tidb=true",
                    "--game.mall.allowed-payment-channels=test-channel",
                    "--game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
                    "--game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
                    "--spring.datasource.url=" + JDBC_BASE + DATABASE,
                    "--spring.datasource.username=root", "--spring.datasource.password=", "--spring.sql.init.mode=never");
            assertThat(context.getBean(GameNamespace.class).value()).isEqualTo(DATABASE);
            pool = context.getBean(HikariDataSource.class);
            jdbc = context.getBean(JdbcTemplate.class);
            first = context.getBean(MallOrderStore.class);
            second = new MallOrderStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool),
                    Clock.systemUTC(), context.getBean(MallProperties.class));
        } catch (Exception | AssertionError failure) {
            stop();
            throw failure;
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (created) {
            if (!DATABASE.matches("mallit[a-z0-9]{20}")) throw new IllegalStateException("拒绝清理非本用例随机库");
            try (Connection admin = DriverManager.getConnection(ADMIN_URL, "root", ""); var sql = admin.createStatement()) {
                sql.execute("DROP DATABASE `" + DATABASE + "`");
            }
            created = false;
        }
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
    }

    @ParameterizedTest @ValueSource(strings = {"weak", "snapshot"})
    void 弱一致或历史快照会话必须拒绝启动(String setting) throws Exception {
        String snapshot = currentSnapshot();
        try (Connection connection = pool.getConnection(); var sql = connection.createStatement()) {
            sql.setQueryTimeout(5);
            var single = new SingleConnectionDataSource(connection, true);
            try {
                sql.execute("weak".equals(setting) ? "SET SESSION tidb_read_consistency='weak'"
                        : "SET SESSION tidb_snapshot='" + snapshot + "'");
                assertThatThrownBy(new MallSchemaVerifier(single, context.getBean(MallProperties.class))::verify)
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                sql.execute("SET SESSION tidb_snapshot='',tidb_read_consistency='strict'");
            }
        }
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
        try (var workers = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MallApi.Order>> replies = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                MallOrderStore service = i % 2 == 0 ? first : second;
                replies.add(workers.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return service.create("18446744073709551615", new MallApi.CreateOrder("tidb-same-001", 101, 1, "fixture-account", "1")); }));
            }
            start.countDown();
            String id = replies.getFirst().get(15, TimeUnit.SECONDS).orderId();
            for (var reply : replies) assertThat(reply.get(15, TimeUnit.SECONDS).orderId()).isEqualTo(id);
            assertThat(stock(101, "lock_stock")).isEqualTo(1);
            CountDownLatch capStart = new CountDownLatch(1);
            List<Future<Boolean>> capped = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int index = i;
                capped.add(workers.submit(() -> { assertThat(capStart.await(5, TimeUnit.SECONDS)).isTrue(); try {
                    (index % 2 == 0 ? first : second).create("123", new MallApi.CreateOrder("tidb-cap-00" + index, 101, 1, "fixture-account", "1")); return true;
                } catch (MallException failure) { assertThat(failure.status()).isEqualTo(429); return false; } }));
            }
            capStart.countDown();
            int success = 0;
            for (var reply : capped) if (reply.get(15, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(5);
            assertThat(stock(101, "lock_stock")).isEqualTo(6);
        }
    }

    @Test
    void 真事务付款取消竞争_收据重放_失败回滚() throws Exception {
        sku(102, 20);
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 6; i++) {
                String buyer = Integer.toString(200 + i);
                MallApi.Order order = first.create(buyer, new MallApi.CreateOrder("tidb-race-00" + i, 102, 1, "fixture-account", "1"));
                CountDownLatch start = new CountDownLatch(1);
                String transaction = "tidb-race-pay-" + i;
                var paid = workers.submit(() -> compete(start, () -> first.recordTrustedPayment(payment(order, transaction))));
                var cancelled = workers.submit(() -> compete(start, () -> second.cancel(buyer, owned(order.orderId()))));
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
        }
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
