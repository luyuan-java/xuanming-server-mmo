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
    static final String ADMIN_URL = "jdbc:mysql://127.0.0.1:4000/?connectTimeout=3000&socketTimeout=6000";
    static ConfigurableApplicationContext context;
    static HikariDataSource pool;
    static JdbcTemplate jdbc;
    static MallOrderStore first;
    static MallOrderStore second;
    static boolean created;

    @BeforeAll
    static void start() throws Exception {
        assertThat(DATABASE).matches("mallit[a-z0-9]{20}");
        try (Connection admin = DriverManager.getConnection(ADMIN_URL, "root", ""); var sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE `" + DATABASE + "`");
            created = true;
        }
        try {
            DriverManagerDataSource migration = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:4000/" + DATABASE
                    + "?connectTimeout=3000&socketTimeout=6000", "root", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__official_sku.sql")).execute(migration);
            SpringApplication app = new SpringApplication(MallApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            context = app.run("--game.namespace=" + DATABASE, "--game.run-mode=prod", "--game.mall.require-tidb=true",
                    "--game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
                    "--game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
                    "--spring.datasource.url=jdbc:mysql://127.0.0.1:4000/" + DATABASE,
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
        properties.setUrl("jdbc:mysql://127.0.0.1:4000/" + DATABASE + "?sessionVariables=" + URLEncoder.encode(
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
                replies.add(workers.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return service.create("18446744073709551615", new MallApi.CreateOrder("tidb-same-001", 101, 1)); }));
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
                    (index % 2 == 0 ? first : second).create("123", new MallApi.CreateOrder("tidb-cap-00" + index, 101, 1)); return true;
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
                MallApi.Order order = first.create(buyer, new MallApi.CreateOrder("tidb-race-00" + i, 102, 1));
                CountDownLatch start = new CountDownLatch(1);
                String transaction = "tidb-race-pay-" + i;
                var paid = workers.submit(() -> compete(start, () -> first.recordTrustedPayment(payment(order, transaction))));
                var cancelled = workers.submit(() -> compete(start, () -> second.cancel(buyer, order.orderId())));
                start.countDown();
                assertThat(paid.get(15, TimeUnit.SECONDS) ^ cancelled.get(15, TimeUnit.SECONDS)).isTrue();
                MallApi.Order actual = first.get(buyer, order.orderId());
                int rows = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=?", Integer.class, order.orderId());
                assertThat(rows).isEqualTo(actual.state().equals("DELIVERY_PENDING") ? 1 : 0);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent WHERE order_sn=?", Integer.class, order.orderId())).isEqualTo(rows);
                if (rows == 1) assertThat(second.recordTrustedPayment(payment(order, transaction))).isEqualTo(actual);
                else assertThat(second.cancel(buyer, order.orderId())).isEqualTo(actual);
            }
        }
        assertThat(stock(102, "lock_stock")).isZero();
        MallApi.Order rollback = first.create("300", new MallApi.CreateOrder("tidb-rollback", 102, 1));
        jdbc.update("INSERT INTO commerce_delivery_intent(order_sn,buyer_id,sku_id,quantity,status,created_ms) VALUES (?,'300',102,1,'PENDING',0)", rollback.orderId());
        int before = stock(102, "stock");
        assertThatThrownBy(() -> first.recordTrustedPayment(payment(rollback, "tidb-rollback-pay"))).isInstanceOf(MallException.class);
        assertThat(first.get("300", rollback.orderId()).state()).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt WHERE order_sn=?", Integer.class, rollback.orderId())).isZero();
        assertThat(stock(102, "stock")).isEqualTo(before);
        assertThat(stock(102, "lock_stock")).isEqualTo(1);
        second.cancel("300", rollback.orderId());
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

    private static void sku(long id, int stock) { jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (?,?,?,1250,'CNY',?,0,1)", id, id, "tidb-" + id, stock); }
    private static int stock(long id, String column) { return jdbc.queryForObject("SELECT " + column + " FROM pms_sku_stock WHERE id=?", Integer.class, id); }
    private static MallApi.Payment payment(MallApi.Order order, String transaction) { return new MallApi.Payment(order.orderId(), "test-channel", transaction, order.amountMinor(), order.currency()); }
    private static boolean compete(CountDownLatch start, Runnable operation) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try { operation.run(); return true; }
        catch (MallException failure) { assertThat(failure.status()).isEqualTo(409); return false; }
    }
}
