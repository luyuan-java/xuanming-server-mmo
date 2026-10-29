package com.game.player.store;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.player.store.PlayerTradeStore.Code;
import com.game.player.store.PlayerTradeStore.Failure;
import com.game.player.store.PlayerTradeStore.FreezeRequest;
import com.game.player.store.PlayerTradeStore.Status;
import com.game.player.store.PlayerTradeStore.TransferRequest;
import com.game.player.store.state.PlayerState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 每例独立库；设置game.it.mysql时必须真实TiDB，不以H2/Skip冒充强读证据。 */
class PlayerTradeStoreTest {
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static FreezeRequest freeze(long listing, long player, String seller, long revision) {
        return new FreezeRequest(listing, player, seller, revision);
    }
    private static TransferRequest transfer(long tx, long listing, long player, String seller, String buyer, long revision) {
        return new TransferRequest(tx, listing, player, seller, buyer, revision);
    }

    @Test void 缺排空验证器不确认冻结_只释放或只过租约不算托管_取消后旧Lock不复冻() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            var request = freeze(101, 1, "seller", 1);
            var store = f.trade(5, false);
            assertEquals(Status.FREEZING, store.beginFreeze(request, BUDGET).status());
            assertEquals(101, f.row(1).getTradeListingId());
            assertEquals(store.beginFreeze(request, BUDGET), f.trade(5, false).beginFreeze(request, BUDGET));
            refuses(Code.NOT_READY, () -> store.confirmFreeze(request, BUDGET));
            f.clock.set(100_000);
            refuses(Code.NOT_READY, () -> store.confirmFreeze(request, BUDGET));
            refuses(Code.NOT_READY, () -> store.transfer(transfer(501, 101, 1, "seller", "buyer", 1), BUDGET));
            refuses(Code.CONFLICT, () -> store.beginFreeze(freeze(102, 1, "seller", 1), BUDGET));
            assertEquals(1, f.count("player_trade_freeze"));
            assertEquals(0, f.count("player_trade_transfer"));
            assertEquals(Status.UNLOCKED, store.cancelFreeze(request, BUDGET).status());
            assertEquals(Status.UNLOCKED, f.trade(5, false).beginFreeze(request, BUDGET).status());
            assertEquals(Status.UNLOCKED, store.confirmFreeze(request, BUDGET).status());
            assertEquals(0, f.row(1).getTradeListingId());
            assertEquals(Status.FREEZING, store.beginFreeze(freeze(103, 1, "seller", 1), BUDGET).status());
        }
    }

    @Test void 原过户请求永久重放_之后再次过户仍返回原修订_不复制存档或homeZone() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            byte[] bytes = PlayerState.newBuilder().setFacing(com.game.player.store.state.Facing.newBuilder().setX(0.25)).build().toByteArray();
            assertEquals(1, f.sql.update("UPDATE player_state SET data=?,saved_epoch=0,updated_at=1000 WHERE player_id=?", bytes, 1));
            var before = f.sql.queryForMap("SELECT zone_id,name,name_key,class_id,gender,appearance_id,level,scene_config_id,pos_x,pos_y,pos_z,owner_epoch FROM player WHERE player_id=1");
            var store = f.trade(5, true);
            var request = freeze(101, 1, "seller", 1);
            store.beginFreeze(request, BUDGET);
            store.confirmFreeze(request, BUDGET);
            var command = transfer(501, 101, 1, "seller", "buyer", 1);
            var original = store.transfer(command, BUDGET);
            assertEquals(1, original.previousRevision());
            assertEquals(2, original.newRevision());
            assertEquals("buyer", f.row(1).getAccount());
            assertEquals(0, f.row(1).getTradeListingId());
            assertEquals(before, f.sql.queryForMap("SELECT zone_id,name,name_key,class_id,gender,appearance_id,level,scene_config_id,pos_x,pos_y,pos_z,owner_epoch FROM player WHERE player_id=1"));
            assertArrayEquals(bytes, f.sql.queryForObject("SELECT data FROM player_state WHERE player_id=1", (rs, ignored) -> rs.getBytes(1)));
            assertEquals(original, f.trade(5, false).transfer(command, BUDGET), "重启后不依赖RAM或原验证器重做已提交过户");
            var second = freeze(102, 1, "buyer", 2);
            store.beginFreeze(second, BUDGET);
            store.confirmFreeze(second, BUDGET);
            assertEquals(3, store.transfer(transfer(502, 102, 1, "buyer", "seller", 2), BUDGET).newRevision());
            assertEquals(original, store.transfer(command, BUDGET));
            assertEquals(Status.TRANSFERRED, store.beginFreeze(request, BUDGET).status());
            assertEquals("seller", f.row(1).getAccount());
            assertEquals(3, f.row(1).getOwnerRevision());
            assertEquals(2, f.count("player_trade_transfer"));
        }
    }

    @Test void 买家满位不留成功收据_腾位后以原tx重试_同tx换载荷拒绝() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            f.player(2, "buyer");
            var store = f.trade(1, true);
            var listing = freeze(101, 1, "seller", 1);
            store.beginFreeze(listing, BUDGET);
            store.confirmFreeze(listing, BUDGET);
            var command = transfer(501, 101, 1, "seller", "buyer", 1);
            refuses(Code.FULL, () -> store.transfer(command, BUDGET));
            assertEquals(0, f.count("player_trade_transfer"));
            assertEquals(101, f.row(1).getTradeListingId());
            // 仅删除本例未上线的占位输入，模拟已由独立业务释放买家角色槽；不冒充生产删角入口。
            f.sql.update("DELETE FROM player WHERE player_id=2");
            var original = store.transfer(command, BUDGET);
            refuses(Code.CONFLICT, () -> store.transfer(transfer(501, 101, 1, "seller", "other", 1), BUDGET));
            assertEquals(original, store.transfer(command, BUDGET));
            assertEquals(1, f.count("player_trade_transfer"));
            assertEquals(2, f.row(1).getOwnerRevision());
        }
    }

    @Test void 独立副本同listing和tx并发只提交一次_双向过户沿同账号锁序完成() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            f.player(2, "buyer");
            var stores = List.of(f.trade(5, true), f.trade(5, true));
            var first = freeze(101, 1, "seller", 1);
            var freezes = parallel(4, index -> stores.get(index % 2).beginFreeze(first, BUDGET));
            for (var result : freezes) assertEquals(freezes.getFirst(), result);
            stores.getFirst().confirmFreeze(first, BUDGET);
            var second = freeze(102, 2, "buyer", 1);
            stores.getFirst().beginFreeze(second, BUDGET);
            stores.getFirst().confirmFreeze(second, BUDGET);
            var results = parallel(4, index -> index % 2 == 0
                    ? stores.get(index % 2).transfer(transfer(501, 101, 1, "seller", "buyer", 1), BUDGET)
                    : stores.get(index % 2).transfer(transfer(502, 102, 2, "buyer", "seller", 1), BUDGET));
            assertEquals(results.get(0), results.get(2));
            assertEquals(results.get(1), results.get(3));
            assertEquals("buyer", f.row(1).getAccount());
            assertEquals("seller", f.row(2).getAccount());
            assertEquals(2, f.row(1).getOwnerRevision());
            assertEquals(2, f.row(2).getOwnerRevision());
            assertEquals(2, f.count("player_trade_freeze"));
            assertEquals(2, f.count("player_trade_transfer"));
        }
    }

    @Test void 过户前旧账号与ABA旧修订不能夺权_冻结阻止租约过期夺权和新的OPEN准入() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            var old = f.row(1);
            var trades = f.trade(5, true);
            var first = freeze(101, 1, "seller", 1);
            trades.beginFreeze(first, BUDGET); trades.confirmFreeze(first, BUDGET);
            trades.transfer(transfer(501, 101, 1, "seller", "buyer", 1), BUDGET);
            assertInstanceOf(PlayerStore.ClaimResult.NotFound.class, f.players.claimOwnership(1, old.getAccount(), old.getOwnerRevision(), BUDGET));
            var second = freeze(102, 1, "buyer", 2);
            trades.beginFreeze(second, BUDGET); trades.confirmFreeze(second, BUDGET);
            trades.transfer(transfer(502, 102, 1, "buyer", "seller", 2), BUDGET);
            assertInstanceOf(PlayerStore.ClaimResult.NotFound.class, f.players.claimOwnership(1, "seller", 1, BUDGET));
            assertEquals(new PlayerStore.ClaimResult.Claimed(1), f.players.claimOwnership(1, "seller", 3, BUDGET));
            var third = freeze(103, 1, "seller", 3);
            trades.beginFreeze(third, BUDGET);
            f.clock.addAndGet(PlayerStore.OWNER_LEASE.toMillis() * 2);
            assertEquals(new PlayerStore.ClaimResult.Held(1), f.players.claimOwnership(1, BUDGET));
            assertEquals(new PlayerStore.ClaimResult.Held(1), f.players.recoverOwnershipAfterRestore(1, 5, 1, BUDGET));
            assertTrue(f.players.loadForScene(1, 1, "scene-a", BUDGET).isEmpty());
            assertEquals(0, f.count("player_final_save"));
            var row = f.row(1);
            assertThrows(IllegalStateException.class, () -> f.players.handOffOwnership(row, PlayerState.getDefaultInstance(),
                    f.clock.get() + 60_000, f.clock.get(), BUDGET));
            assertEquals(1, f.row(1).getOwnerEpoch());
        }
    }

    @Test void 既有半完成或损坏收据不接管_不因重放改变归属() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            var trades = f.trade(5, true);
            var request = freeze(101, 1, "seller", 1);
            trades.beginFreeze(request, BUDGET);
            refuses(Code.CONFLICT, () -> trades.beginFreeze(freeze(101, 1, "seller", 2), BUDGET));
            f.sql.update("UPDATE player_trade_freeze SET creation_token=? WHERE listing_id=101", new byte[]{1});
            refuses(Code.CORRUPT, () -> trades.beginFreeze(request, BUDGET));
            f.sql.update("UPDATE player_trade_freeze SET creation_token=? WHERE listing_id=101", UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            trades.confirmFreeze(request, BUDGET);
            var command = transfer(501, 101, 1, "seller", "buyer", 1);
            trades.transfer(command, BUDGET);
            f.sql.update("UPDATE player_trade_transfer SET status='PENDING',new_revision=0 WHERE tx_id=501");
            refuses(Code.CORRUPT, () -> trades.transfer(command, BUDGET));
            assertEquals("buyer", f.row(1).getAccount());
            assertEquals(2, f.row(1).getOwnerRevision());
            assertEquals(1, f.count("player_trade_transfer"));
        }
    }

    @Test void 非法参数无副作用_原账号不做trim或大小写转换() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller");
            var store = f.trade(5, false);
            for (var request : List.of(freeze(0, 1, "seller", 1), freeze(101, 0, "seller", 1),
                    freeze(101, 1, " seller", 1), freeze(101, 1, "seller ", 1), freeze(101, 1, "\ud800", 1), freeze(101, 1, "seller", 0))) {
                refuses(Code.INVALID, () -> store.beginFreeze(request, BUDGET));
            }
            refuses(Code.NOT_FOUND, () -> store.beginFreeze(freeze(101, 1, "SELLER", 1), BUDGET));
            refuses(Code.INVALID, () -> store.beginFreeze(freeze(101, 1, "seller", 1), Duration.ZERO));
            refuses(Code.INVALID, () -> store.transfer(transfer(501, 101, 1, "seller", "seller", 1), BUDGET));
            assertEquals(0, f.count("player_trade_freeze"));
            assertEquals(0, f.row(1).getTradeListingId());
        }
    }
    @Test void 修订上界不可新上架_高半区wire值也不能被借当前SQL替换() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller"); f.sql.update("UPDATE player SET owner_revision=? WHERE player_id=1", Long.MAX_VALUE);
            var store = f.trade(5, false);
            refuses(Code.CONFLICT, () -> store.beginFreeze(freeze(101,1,"seller",Long.MAX_VALUE),BUDGET));
            refuses(Code.INVALID, () -> store.beginFreeze(freeze(101,1,"seller",Long.MIN_VALUE),BUDGET));
            assertEquals(0,f.count("player_trade_freeze")); assertEquals(0,f.row(1).getTradeListingId());
        }
    }

    private static void refuses(Code code, Executable work) { assertEquals(code, assertThrows(Failure.class, work).code()); }
    @Test void 撤单先赢永久占据原商品号_迟到Begin不冻结_换载荷冲突() throws Exception {
        try (var f = new Fixture()) {
            f.player(1, "seller"); var store = f.trade(5, false); var request = freeze(101, 1, "seller", 1);
            assertEquals(Status.UNLOCKED, store.cancelFreeze(request, BUDGET).status());
            assertEquals(Status.UNLOCKED, store.beginFreeze(request, BUDGET).status());
            assertEquals(0, f.row(1).getTradeListingId()); assertEquals(1, f.count("player_trade_freeze"));
            refuses(Code.CONFLICT, () -> store.beginFreeze(freeze(101, 1, "seller", 2), BUDGET));
            assertEquals(0, f.row(1).getTradeListingId());
        }
    }
    private static <T> List<T> parallel(int count, IntFunction<T> command) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(); List<T> results = new ArrayList<>(); List<Executable> failures = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(executor.submit(() -> { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("起跑超时"); return command.apply(current); }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var future : futures) {
                try { results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
                catch (java.util.concurrent.ExecutionException error) { failures.add(() -> { throw error.getCause(); }); }
                catch (java.util.concurrent.TimeoutException error) { future.cancel(true); failures.add(() -> { throw error; }); }
            }
            assertAll("主线程接收所有并发失败", failures); return results;
        } finally {
            start.countDown(); futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 仅H2状态机回归替身外部writer资格；不发grant，真实SQL/事务与Fence包装仍由生产代码执行。 */
    static final class H2Authority {
        private final JdbcDataSource schema;
        private final DataSource normal;
        private final com.game.common.sql.WriterAuthority authority;
        private final java.util.concurrent.atomic.AtomicBoolean sealed = new java.util.concurrent.atomic.AtomicBoolean();

        H2Authority(JdbcDataSource schema) throws java.sql.SQLException {
            this.schema = schema;
            final String physicalUrl;
            try (Connection raw = schema.getConnection()) {
                requireRawH2(raw);
                physicalUrl = raw.getMetaData().getURL();
            }
            authority = org.mockito.Mockito.mock(com.game.common.sql.WriterAuthority.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("verifyPhysical") || method.equals("begin")) {
                    Connection raw = invocation.getArgument(0);
                    requireRawH2(raw);
                    if (sealed.get() || !raw.getAutoCommit() || !physicalUrl.equals(raw.getMetaData().getURL())) {
                        throw new java.sql.SQLException("H2外部writer接口替身拒绝已seal、旧事务或另一内存库连接");
                    }
                    // AuthorityConnection.finish仍对真实H2执行commit/rollback并恢复autoCommit。
                    if (method.equals("begin")) raw.setAutoCommit(false);
                    return null;
                }
                if (method.equals("seal")) { sealed.set(true); return null; }
                if (method.equals("isSealed")) return sealed.get();
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            var credentials = new java.util.Properties();
            credentials.setProperty("user", "sa"); credentials.setProperty("password", "");
            normal = new com.game.common.sql.AuthorityDataSource(schema.getURL(), credentials, authority, false, List.of());
        }

        private static void requireRawH2(Connection raw) throws java.sql.SQLException {
            if (org.mockito.Mockito.mockingDetails(raw).isMock() || raw.isClosed()
                    || !"H2".equals(raw.getMetaData().getDatabaseProductName())
                    || !"SA".equalsIgnoreCase(raw.getMetaData().getUserName())) {
                throw new java.sql.SQLException("H2外部writer接口替身仅接受真实SA物理连接");
            }
        }

        DataSource normalSource() { return normal; }
        DataSource schemaSource() { return schema; }
        void seal() { authority.seal(); }
    }

    static final class Fixture implements AutoCloseable {
        final String endpoint = System.getProperty("game.it.mysql");
        final String database;
        final DataSource source;
        AuthoritySqlFixture fixture;
        private JdbcDataSource memory;
        private H2Authority h2Authority;
        final JdbcTemplate sql;
        final DataSourceTransactionManager manager;
        final PlayerStore players;
        final PlayerMapper mapper;
        final AtomicLong clock = new AtomicLong(1_000_000);
        Fixture() throws Exception {
            this(null);
        }
        Fixture(AuthoritySqlFixture.SchemaBuilder additionalSchema) throws Exception {
            DruidDataSource pool = null;
            try {
                if (endpoint == null) {
                    database = "playertrade" + UUID.randomUUID().toString().replace("-", "");
                    var h2 = new JdbcDataSource();
                    h2.setUser("sa"); h2.setPassword(""); memory = h2;
                    h2.setURL("jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
                    h2Authority = new H2Authority(h2);
                    source = h2Authority.normalSource();
                } else {
                    fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(endpoint,
                            System.getProperty("game.it.mysql.user", "root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""),
                            "playertrade",false,List.of(1,2,3,7,9,11)),(connection,principal)->{
                        new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql"),
                                new ClassPathResource("db/role-pending-fence-fixture.sql")).populate(connection);
                        if(additionalSchema!=null)additionalSchema.create(connection,principal);
                    });
                    database = fixture.database();
                    pool = new DruidDataSource();
                    pool.setUrl(fixture.jdbcUrl("?connectTimeout=3000&socketTimeout=30000"));
                    pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());
                    pool.setMaxActive(8);pool.setMaxWait(3000);
                    AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);
                    source=pool;
                }
                if(fixture==null){
                    new ResourceDatabasePopulator(new ClassPathResource("db/game-route-authority-schema.sql"),new ClassPathResource("db/game-player-schema.sql"),
                            new ClassPathResource("db/role-pending-fence-fixture.sql")).execute(memory);
                    try(var connection=memory.getConnection();var statement=connection.createStatement();var identity=statement.executeQuery("SELECT CURRENT_USER()")){
                        if(!identity.next())throw new java.sql.SQLException("H2 schema主体缺失");
                        com.game.common.player.RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(1,2,3,7,9,11),identity.getString(1));
                        if(additionalSchema!=null)additionalSchema.create(connection,identity.getString(1));
                    }
                }
                try (var connection = source.getConnection()) {
                    if (endpoint != null) StrongTiDbConnectionPolicy.verify(connection, true);
                    PlayerSqlAuthorityAutoConfiguration.verifyTradeSchema(connection);
                }
                sql = new JdbcTemplate(fixture==null?h2Authority.schemaSource():fixture.schemaDataSource()); manager = new DataSourceTransactionManager(source);
                var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(PlayerMapper.class);
                var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
                mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlayerMapper.class);
                players = new PlayerStore(mapper, clock::get, manager);
                for (String account : List.of("seller", "buyer", "other")) players.ensureAccount(account);
            } catch (Exception | Error failure) {
                if (pool != null) pool.close();
                if(fixture!=null)try{fixture.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                if(memory!=null)try{if(h2Authority!=null)h2Authority.seal();new JdbcTemplate(memory).execute("SHUTDOWN");}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        PlayerTradeStore trade(int max, boolean fixtureProof) {
            // 只验证SQL状态机的受控替身；不是实际scene冻结、卸载或排空证明。
            return new PlayerTradeStore(source, manager, clock::get, max,
                fixtureProof ? (sql, receipt) -> true : null, (sql, request) -> {});
        }
        void player(long id, String account) {
            PlayerRow row = new PlayerRow(); row.setPlayerId(id); row.setAccount(account); row.setZoneId(7); row.setClassId(1);
            assertEquals(PlayerStore.CreateStatus.CREATED, players.createPlayerWithinCap(row, 5, List.of("角色" + id), BUDGET).status());
        }
        PlayerRow row(long player) { return players.findPlayer(player).orElseThrow(); }
        int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        Connection schemaConnection() throws java.sql.SQLException {return fixture==null?memory.getConnection():fixture.schemaConnection();}
        @Override public void close() throws Exception {
            if (source instanceof DruidDataSource pool) { pool.close(); fixture.close(); }
            else { h2Authority.seal(); sql.execute("SHUTDOWN"); }
        }
    }
}
