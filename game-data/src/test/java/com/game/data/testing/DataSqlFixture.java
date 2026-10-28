package com.game.data.testing;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsTables;
import com.game.data.ops.fence.StrictClock;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerStore;
import java.util.function.LongSupplier;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.PlayerSnapshotRow;
import com.game.data.store.TransactionLogMapper;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * game-data 的 SQL 测试夹具：生产建表脚本（game-data-schema.sql + game-player-store 的 game-player-schema.sql）+ 运维作业表 + 生产 Mapper，
 * MyBatis 经 MyBatis-Spring 加入 Spring 事务（与生产同一种接法：TransactionTemplate 里 Mapper 与 pbmysql 用同一条连接）。
 *
 * <p>缺省 H2 的 MySQL 兼容模式；{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306} 时连真 MySQL（口令取 GAME_MYSQL_PASSWORD），
 * 每个夹具建一个一次性库 {@code game_data_it_<随机>}、结束时删掉，绝不碰 game；连接串与生产同口径（{@code useAffectedRows=true}）。
 * 运维作业表在真 MySQL 上走生产路径 {@link OpsTables#sync}；H2 管不了 information_schema / GET_LOCK，改用 pbmysql 生成的同一份 DDL
 * 去掉 H2 不认的字符集 / 表选项后直接建（列、主键、索引与生产一致）。
 */
public final class DataSqlFixture implements AutoCloseable {

    public static final String MYSQL_URL = System.getProperty("game.it.mysql");
    public static final String MYSQL_USER = System.getProperty("game.it.mysql.user", "root");
    public static final String MYSQL_PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");

    public final String database;
    public final DataSource dataSource;
    public final PlatformTransactionManager transactionManager;
    public final TransactionLogMapper txlog;
    public final PlayerSnapshotMapper snapshots;
    public final PersistedPlayerMapper players;
    /** game-player-store 的生产 Mapper（夺权 / 续约 / 释放 / 带围栏写 / 加锁读；批次 7.2b）。 */
    public final PlayerMapper playerMapper;
    public final PbMysql ops;
    private final AuthoritySqlFixture authority;
    private final H2Authority h2Authority;
    private final DataSource schemaSource;
    private final PlayerSnapshotMapper schemaSnapshots;

    private DataSqlFixture(String database, DataSource dataSource, PlatformTransactionManager transactionManager,
                           SqlSessionTemplate session, PbMysql ops,AuthoritySqlFixture authority,H2Authority h2Authority,DataSource schemaSource,PlayerSnapshotMapper schemaSnapshots) {
        this.authority=authority;
        this.h2Authority=h2Authority;
        this.schemaSource=schemaSource;
        this.schemaSnapshots=schemaSnapshots;
        this.database = database;
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.txlog = session.getMapper(TransactionLogMapper.class);
        this.snapshots = session.getMapper(PlayerSnapshotMapper.class);
        this.players = session.getMapper(PersistedPlayerMapper.class);
        this.playerMapper = session.getMapper(PlayerMapper.class);
        this.ops = ops;
    }

    /**
     * 与生产同口径的 {@link PlayerStore}（严格递增时钟、同一事务管理器）。不经 Spring 代理：普通写档由调用方事务包裹，
     * 带时限夺权与历史恢复则使用 PlayerStore 自有事务，不能套外层事务；测试不依赖 {@code @Transactional} 代理。
     */
    public PlayerStore playerStore(LongSupplier clockMs) {
        return new PlayerStore(playerMapper, new StrictClock(clockMs), transactionManager);
    }

    public static boolean mysql() {
        return MYSQL_URL != null;
    }

    private enum SchemaPreparation{LEGACY_PREPARE,UNPUBLISHED_OWNER}

    public static DataSqlFixture create()throws Exception{return create(null);}
    public static DataSqlFixture create(AuthoritySqlFixture.SchemaBuilder additionalSchema) throws Exception {
        return create(additionalSchema,SchemaPreparation.LEGACY_PREPARE);
    }
    /** 仅真实SQL合区回归：同一owner完成原fresh builder后立即发布，不暴露未发布lease。 */
    public static DataSqlFixture createUnpublished(AuthoritySqlFixture.SchemaBuilder additionalSchema)throws Exception{
        return create(additionalSchema,SchemaPreparation.UNPUBLISHED_OWNER);
    }
    private static DataSqlFixture create(AuthoritySqlFixture.SchemaBuilder additionalSchema,SchemaPreparation preparation)throws Exception{
        boolean strictTiDb=Boolean.getBoolean("game.it.require-tidb");
        if(strictTiDb&&!mysql())throw new IllegalStateException("强TiDB专项不能回退H2");
        if(preparation==SchemaPreparation.UNPUBLISHED_OWNER&&!mysql())throw new IllegalStateException("未发布owner夹具仅接受真实MySQL/TiDB");
        AuthoritySqlFixture authority=null;H2Authority h2Authority=null;JdbcDataSource memory=null;DataSource ds=null;String database;PbMysql ops=OpsTables.registry();
        try{
            if(mysql()){
                var options=AuthoritySqlFixture.Options.fromEnvironment(MYSQL_URL,MYSQL_USER,MYSQL_PASSWORD,"game_data",!strictTiDb,java.util.List.of(1,2,3,4,7,9,11));
                AuthoritySqlFixture.SchemaBuilder builder=(connection,principal)->{
                    new ResourceDatabasePopulator(new ClassPathResource("db/game-data-schema.sql"),new ClassPathResource("db/game-player-schema.sql"),new ClassPathResource("db/role-pending-fence-fixture.sql")).populate(connection);
                    OpsTables.registry().initializeFresh(connection,principal);if(additionalSchema!=null)additionalSchema.create(connection,principal);
                };
                if(preparation==SchemaPreparation.UNPUBLISHED_OWNER){
                    try(var lease=AuthoritySqlFixture.beginUnpublished(options)){
                        lease.withSchema((connection,principal,ownedDatabase)->{builder.create(connection,principal);return null;});
                        authority=lease.publish();
                    }
                }else authority=AuthoritySqlFixture.prepare(options,builder);
                database=authority.database();
                String params="?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true&connectTimeout=3000&socketTimeout=10000";
                if(strictTiDb){var pool=new DruidDataSource();ds=pool;pool.setUrl(authority.jdbcUrl(params));pool.setUsername(authority.normalUser());pool.setPassword(authority.normalPassword());pool.setMaxActive(4);pool.setMaxWait(1000);AuthorityDataSourcePolicy.protectDruid(pool,authority.writerBoot(),true);}
                else ds=authority.normalDataSource(params);
                OpsTables.sync(ds,Duration.ofMinutes(1));
            }else{
                database="game_data_it_"+UUID.randomUUID().toString().replace("-","").substring(0,12);JdbcDataSource h2=new JdbcDataSource();h2.setUser("sa");h2.setPassword("");memory=h2;h2.setURL("jdbc:h2:mem:"+database+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");ds=h2;
                new ResourceDatabasePopulator(new ClassPathResource("db/game-data-schema.sql"),new ClassPathResource("db/game-route-authority-schema.sql"),new ClassPathResource("db/game-player-schema.sql"),new ClassPathResource("db/role-pending-fence-fixture.sql")).execute(ds);
                try(var connection=ds.getConnection();var statement=connection.createStatement()){
                    String principal;try(var rows=statement.executeQuery("SELECT CURRENT_USER()")){if(!rows.next())throw new SQLException("缺H2 schema主体");principal=rows.getString(1);}
                    com.game.common.player.RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),java.util.List.of(1,2,3,4,7,9,11),principal);
                    for(TableSchema table:ops.tables())statement.execute(h2Ddl(table.createTableSql()));if(additionalSchema!=null)additionalSchema.create(connection,principal);
                }
                h2Authority=new H2Authority(h2);ds=h2Authority.normalSource();
            }
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(TransactionLogMapper.class);
            configuration.addMapper(PlayerSnapshotMapper.class);
            configuration.addMapper(PersistedPlayerMapper.class);
            configuration.addMapper(PlayerMapper.class);
            factory.setConfiguration(configuration);
            SqlSessionFactory sessions = factory.getObject();
            // 明确的schema种子接缝持有同一个raw DataSource；不能每次重新造匿名包装导致事务身份分离。
            DataSource schemaSource=authority==null?h2Authority.schemaSource():authority.schemaDataSource();
            var schemaFactory=new SqlSessionFactoryBean();schemaFactory.setDataSource(schemaSource);
            var schemaConfig=new org.apache.ibatis.session.Configuration();schemaConfig.setMapUnderscoreToCamelCase(true);
            schemaConfig.addMapper(PlayerSnapshotMapper.class);schemaFactory.setConfiguration(schemaConfig);
            var schemaSnapshots=new SqlSessionTemplate(schemaFactory.getObject()).getMapper(PlayerSnapshotMapper.class);
            return new DataSqlFixture(database, ds, new DataSourceTransactionManager(ds), new SqlSessionTemplate(sessions), ops,authority,h2Authority,schemaSource,schemaSnapshots);
        }catch(Exception failure){if(ds instanceof DruidDataSource pool)pool.close();if(authority!=null)try{authority.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}if(memory!=null)try{if(h2Authority!=null)h2Authority.seal();new JdbcTemplate(memory).execute("SHUTDOWN");}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}catch(Error failure){if(memory!=null)try{if(h2Authority!=null)h2Authority.seal();new JdbcTemplate(memory).execute("SHUTDOWN");}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    /** 仅H2状态机回归替身外部writer资格；不发grant，真实SQL/事务与Fence包装仍由生产代码执行。 */
    private static final class H2Authority implements AutoCloseable {
        private final JdbcDataSource schema;
        private final DataSource normal;
        private final com.game.common.sql.WriterAuthority authority;
        private final java.util.concurrent.atomic.AtomicBoolean sealed = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

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
            normal = new com.game.common.sql.AuthorityDataSource(schema.getURL(), credentials, authority, false, java.util.List.of());
        }

        private static void requireRawH2(Connection raw) throws java.sql.SQLException {
            if (org.mockito.Mockito.mockingDetails(raw).isMock() || raw.isClosed()
                    || !"H2".equals(raw.getMetaData().getDatabaseProductName())
                    || !"SA".equalsIgnoreCase(raw.getMetaData().getUserName())) {
                throw new java.sql.SQLException("H2外部writer接口替身仅接受真实SA物理连接");
            }
        }

        DataSource normalSource() { return normal; }
        DataSource schemaSource() { if(closed.get())throw new IllegalStateException("H2测试fixture已关闭");return schema; }
        void seal() { authority.seal(); }
        @Override public synchronized void close() throws java.sql.SQLException {
            if(closed.getAndSet(true))return;
            seal();
            try(Connection raw=schema.getConnection();var statement=raw.createStatement()){statement.execute("SHUTDOWN");}
        }
    }

    /** pbmysql 的 MySQL DDL → H2 MySQL 模式能执行的形式（只去掉键列的字符集 / 排序规则与表尾选项）。 */
    static String h2Ddl(String ddl) {
        return ddl.replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin", "")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    public OpsJobStore jobs() {
        return new OpsJobStore(dataSource, ops);
    }

    /** 仅SQL合区回归前置，不是完整P0-P9或真实非SQL证据。 */
    public void seedNormalFrozenSqlSeam(String run)throws Exception{if(authority==null)throw new SQLException("SQL seam冻结必须真实隔离fixture");authority.seedNormalFrozenSqlSeam(run);}
    public DataSource schemaDataSource(){if(h2Authority!=null)h2Authority.schemaSource();return schemaSource;}
    public Connection schemaConnection()throws SQLException{return schemaDataSource().getConnection();}
    /** 业务SQL观测使用normal，和tx()/MyBatis/PbMysql是同一个DataSource。 */
    public JdbcTemplate normalJdbc() {return new JdbcTemplate(dataSource);}
    /** 原件准备、故障注入和独立SQL观测；不得与tx()混称同一事务。 */
    public JdbcTemplate schemaJdbc() {return new JdbcTemplate(schemaDataSource());}
    /** 保留既有schema/seed调用面；成组种子必须显式使用schemaTx()。 */
    public JdbcTemplate jdbc() {return schemaJdbc();}
    public TransactionTemplate schemaTx() {return new TransactionTemplate(new DataSourceTransactionManager(schemaDataSource()));}
    /** 仅fixture造scene快照；业务snapshots Mapper仍使用normal。 */
    public void seedSnapshot(PlayerSnapshotRow row,long ingestedAt,String operator,String note) {
        schemaDataSource();schemaSnapshots.insertDirect(row,ingestedAt,operator,note);
    }

    /** 造一名玩家和非null空protobuf初始存档；与正常建角一样同事务存在。 */
    public void insertPlayer(long playerId, int zoneId, int level, int sceneConfigId, long ownerEpoch, boolean released,
                             long leaseUntil, long createdAt, long updatedAt) {
        // 仅seed使用显式raw schema事务；业务tx()/Mapper始终使用normal。
        schemaTx().executeWithoutResult(status -> {
        schemaJdbc().update("INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, level, "
                        + "scene_config_id, pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, 1, 1, ?, ?, 1.5, 2.5, -3.5, ?, ?, ?, ?, ?)",
                playerId, "acc" + playerId, zoneId, "name" + playerId, "name" + playerId, level, sceneConfigId, ownerEpoch,
                released ? 1 : 0, leaseUntil, createdAt, updatedAt);
        schemaJdbc().update("INSERT INTO player_state(player_id,data,saved_epoch,updated_at) VALUES(?,?,0,?)", playerId, new byte[0], updatedAt);
        });
    }

    public void putState(long playerId, byte[] data, long savedEpoch, long updatedAt) {
        jdbc().update("DELETE FROM player_state WHERE player_id = ?", playerId);
        jdbc().update("INSERT INTO player_state (player_id, data, saved_epoch, updated_at) VALUES (?, ?, ?, ?)", playerId,
                data, savedEpoch, updatedAt);
    }

    public int count(String table) {
        Integer n = jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    @Override public void close() throws SQLException {
        if(authority!=null){if(dataSource instanceof DruidDataSource pool)pool.close();authority.close();return;}
        h2Authority.close();
    }

}
