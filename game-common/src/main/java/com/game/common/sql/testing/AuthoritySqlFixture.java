package com.game.common.sql.testing;

import com.game.common.player.RouteAuthorityInitializer;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import javax.sql.DataSource;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** 只由隔离测试显式调用，无Spring bean和普通服务默认入口；沿用正式DDL、proto与注册摘要。 */
public final class AuthoritySqlFixture implements AutoCloseable {
    public record Options(String root,String adminUser,String adminPassword,String prefix,boolean allowMySql,Path receipts,byte[] physical,String primitive,byte[] qualification,List<Integer> zones){
        public Options{physical=physical.clone();qualification=qualification.clone();zones=List.copyOf(zones);}
        @Override public byte[] physical(){return physical.clone();}@Override public byte[] qualification(){return qualification.clone();}
        public static Options fromEnvironment(String root,String user,String password,String prefix,boolean allowMySql,List<Integer> zones)throws SQLException{
            try{return new Options(root,user,password,prefix,allowMySql,Path.of(required("MMORPG_SQL_FIXTURE_RECEIPT_DIR")),HexFormat.of().parseHex(required("MMORPG_SQL_FIXTURE_PHYSICAL_AUTHORITY_SHA256")),required("MMORPG_SQL_FIXTURE_LOCK_PRIMITIVE"),HexFormat.of().parseHex(required("MMORPG_SQL_FIXTURE_LOCK_QUALIFICATION_SHA256")),zones);}
            catch(RuntimeException invalid){throw new SQLException("fixture缺真实物理/I3资格和私有原件目录");}
        }
        private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalArgumentException(key);return v;}
    }
    @FunctionalInterface public interface SchemaBuilder{void create(Connection schema,String actualSchemaPrincipal)throws Exception;}

    /** 未发布阶段的同步工作；连接及全部派生JDBC对象只能在本次回调内使用。 */
    @FunctionalInterface public interface UnpublishedSchemaWork<T>{
        T run(Connection schema,String configuredSchemaPrincipal,String database)throws Exception;
    }
    /** 仅隔离测试owner创建全新随机库；不接管已有库，也不向普通服务提供默认入口。 */
    public static UnpublishedSchemaLease beginUnpublished(Options options)throws Exception{
        var fixture=new AuthoritySqlFixture(options);
        var lease=new UnpublishedSchemaLease(fixture);fixture.unpublishedOwner=lease;
        try{lease.openSchema();return lease;}
        catch(Exception|Error failure){lease.failUnknown(failure);throw failure;}
    }
    /** 显式命名空间只用于需要正式Spring库名校验的隔离测试；实际CREATE碰到已有库仍拒绝。 */
    public static UnpublishedSchemaLease beginUnpublished(Options options,com.game.common.GameNamespace namespace)throws Exception{
        Objects.requireNonNull(namespace,"隔离测试必须提供已校验的命名空间");
        var fixture=new AuthoritySqlFixture(options,namespace);
        var lease=new UnpublishedSchemaLease(fixture);fixture.unpublishedOwner=lease;
        try{lease.openSchema();return lease;}
        catch(Exception|Error failure){lease.failUnknown(failure);throw failure;}
    }
    /** 从同一正式player源建立HOME基表；不复制列结构，也不装载冲突的account域。 */
    public static UnpublishedSchemaLease beginUnpublishedWithPlayerBaseSchema(Options options,com.game.common.GameNamespace namespace)throws Exception{
        String playerDdl=readFixedPlayerSchema().get(FIXED_PLAYER_BASE_SCHEMA_INDEX);
        var lease=beginUnpublished(options,namespace);
        try{
            lease.withSchema((schema,configuredPrincipal,database)->{
                try(var statement=schema.createStatement()){
                    statement.setQueryTimeout(5);statement.execute(playerDdl);
                }
                return null;
            });
            return lease;
        }catch(Exception|Error failure){
            try{lease.close();}catch(Exception|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static final int FIXED_PLAYER_BASE_SCHEMA_INDEX=1;
    /** 只从固定正式八表源取player HOME真源；不加载account，避免覆盖Pb样本自己的同名表契约。 */
    public static UnpublishedSchemaLease beginUnpublishedWithPlayerBaseSchema(Options options)throws Exception{
        // readFixedPlayerSchema先核完整源摘要和八表顺序；不复制player列结构或接受外部DDL。
        String playerDdl=readFixedPlayerSchema().get(FIXED_PLAYER_BASE_SCHEMA_INDEX);
        var lease=beginUnpublished(options);
        try{
            lease.withSchema((schema,configuredPrincipal,database)->{
                try(var statement=schema.createStatement()){
                    statement.setQueryTimeout(5);statement.execute(playerDdl);
                }
                return null;
            });
            return lease;
        }catch(Exception|Error failure){
            try{lease.close();}catch(Exception|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    /**
     * 同一owner从全新库持有到显式发布的阶段能力。构造器私有，没有自报主体或NoOp许可入口。
     * 只有Schema账号接触未发布业务表；发布前撤销账号并确定关闭会话，之后才安装authority和NORMAL身份。
     */
    public static final class UnpublishedSchemaLease implements AutoCloseable{
        private enum Phase{HELD,PUBLISHING,PUBLISHED,FAILED_UNKNOWN,CLOSED}
        private final AuthoritySqlFixture fixture;
        private final String schemaUser,schemaPassword,configuredPrincipal;
        private Connection schema;
        private boolean schemaUserCreated,lastWorkSucceeded;
        private Phase phase=Phase.HELD;
        private SchemaScope activeWork;
        private final List<UnpublishedSchemaWork<Void>> completionChecks=new ArrayList<>();
        private UnpublishedSchemaLease(AuthoritySqlFixture fixture){
            this.fixture=fixture;
            schemaUser="ws_"+fixture.user.substring(3);
            schemaPassword=Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
            configuredPrincipal=schemaUser+"@%";
        }
        public String database(){return fixture.database;}
        public String schemaPrincipal(){return configuredPrincipal;}
        private void openSchema()throws Exception{
            synchronized(fixture){
                verifyUnpublishedOwner();
                try(var admin=fixture.admin(fixture.database);var statement=admin.createStatement()){
                    fixture.unknown=true;
                    try{statement.execute("CREATE USER "+literal(schemaUser)+"@'%' IDENTIFIED BY "+literal(schemaPassword));}
                    catch(SQLException unsafe){throw new SQLException("Schema账号创建结局未知，保留私有owner原件","08007",unsafe.getErrorCode());}
                    schemaUserCreated=true;fixture.unknown=false;
                    fixture.unknown=true;
                    statement.execute("GRANT CREATE,ALTER,INDEX ON "+name(fixture.database)+".* TO "+literal(schemaUser)+"@'%'");
                    fixture.unknown=false;
                }
                schema=DriverManager.getConnection(fixture.jdbcUrl(""),schemaUser,schemaPassword);
                AuthorityDataSource.initialize(schema,!fixture.options.allowMySql(),List.of());
                schema.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                schema.setNetworkTimeout(Runnable::run,5000);
                verifySchema();verifyUnpublishedOwner();
            }
        }
        private void requireHeld()throws SQLException{
            if(phase!=Phase.HELD||fixture.closed||fixture.unknown||!fixture.owned)
                throw new SQLException("未发布Schema阶段已撤销或结果未知");
        }
        private void verifySchema()throws SQLException{
            if(schema==null||schema.isClosed()||!schema.getAutoCommit())
                throw new SQLException("未发布Schema会话无效或遗留事务","08007");
            if(!configuredPrincipal.equals(currentUser(schema)))
                throw new SQLException("实际独立Schema主体与预置配置不同","08007");
            try(var statement=schema.createStatement();var rows=statement.executeQuery("SELECT DATABASE()")){
                if(!rows.next()||!fixture.database.equals(rows.getString(1))||rows.next())
                    throw new SQLException("未发布Schema会话具名库不同","08007");
            }
        }
        private void verifyUnpublishedOwner()throws Exception{
            if(fixture.closed||fixture.unknown||!fixture.owned||fixture.userCreated||fixture.boot!=null)
                throw new SQLException("未发布owner状态不符");
            if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(fixture.ownerFile,65536),fixture.ownerBytes()))
                throw new SQLException("未发布私有owner原件不同");
            try(var admin=fixture.admin(fixture.database)){
                try(var query=admin.prepareStatement("SELECT owner_nonce,physical_sha,normal_principal FROM __sql_fixture_owner WHERE id=1");var rows=query.executeQuery()){
                    if(!rows.next()||!MessageDigest.isEqual(fixture.nonce,rows.getBytes(1))
                            ||!MessageDigest.isEqual(fixture.options.physical(),rows.getBytes(2)))
                        throw new SQLException("未发布数据库归属不同");
                    byte[] normal=rows.getBytes(3);
                    if(normal==null||normal.length!=0||rows.next())throw new SQLException("未发布owner已发布或不唯一");
                }
                try(var query=admin.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=?")){
                    query.setString(1,fixture.database);
                    try(var rows=query.executeQuery()){
                        while(rows.next()){
                            String table=rows.getString(1).toLowerCase(Locale.ROOT);
                            if(CONTROL.contains(table)&&!table.equals("__sql_fixture_owner"))
                                throw new SQLException("未发布库出现authority控制对象，拒绝接纳");
                        }
                    }
                }
            }
        }
        private void grantExistingBusinessRows()throws Exception{
            try(var admin=fixture.admin(fixture.database);var statement=admin.createStatement();
                    var query=admin.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name")){
                query.setString(1,fixture.database);
                var tables=new ArrayList<String>();
                try(var rows=query.executeQuery()){while(rows.next())tables.add(rows.getString(1));}
                for(String table:tables)if(!CONTROL.contains(table.toLowerCase(Locale.ROOT))){
                    fixture.unknown=true;
                    statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+name(fixture.database)+"."+name(table)+" TO "+literal(schemaUser)+"@'%'");
                    fixture.unknown=false;
                }
            }
        }
        /** CREATE已成功且DDL会话无事务后，先补真实业务表权限，再允许调用方读取列和索引元数据。 */
        private void grantBusinessRowsAfterCreate()throws SQLException{
            try{
                requireHeld();verifySchema();verifyUnpublishedOwner();
                grantExistingBusinessRows();
                verifySchema();verifyUnpublishedOwner();
            }catch(Exception|Error failure){
                failUnknown(failure);
                if(failure instanceof SQLException sql)throw sql;
                if(failure instanceof RuntimeException runtime)throw runtime;
                if(failure instanceof Error error)throw error;
                throw new SQLException("CREATE后业务表权限刷新未确认","08007",failure);
            }
        }
        public <T>T withSchema(UnpublishedSchemaWork<T> work)throws Exception{
            Objects.requireNonNull(work,"Schema工作不能为空");
            synchronized(fixture){
                requireHeld();if(activeWork!=null)throw new SQLException("未发布Schema工作不能重入");
                try{verifyUnpublishedOwner();verifySchema();grantExistingBusinessRows();}
                catch(Exception|Error failure){failUnknown(failure);throw failure;}
                var scope=new SchemaScope(this);activeWork=scope;lastWorkSucceeded=false;
                T result=null;Throwable failure=null;
                try{result=work.run(scope.connection(),configuredPrincipal,fixture.database);}
                catch(Exception|Error caught){failure=caught;if(!(caught instanceof SQLException)||unknownOutcome(caught))failUnknown(caught);}
                finally{
                    try{scope.revoke();}catch(Exception|Error cleanup){if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);failUnknown(cleanup);}
                    finally{activeWork=null;}
                }
                if(phase==Phase.HELD)try{verifySchema();verifyUnpublishedOwner();}
                catch(Exception|Error invalid){if(failure==null)failure=invalid;else if(invalid!=failure)failure.addSuppressed(invalid);failUnknown(invalid);}
                if(failure==null&&phase!=Phase.HELD)failure=new SQLException("Schema结果未知，拒绝把回调捕获异常视为成功","08007");
                if(failure!=null){raise(failure);return null;}
                lastWorkSucceeded=true;return result;
            }
        }
        /** 真实结构变更在开始前登记完整只读验证；失败后seed或metadata成功不能清除目标。 */
        public <T>T withSchemaChange(UnpublishedSchemaWork<T> change,UnpublishedSchemaWork<Void> fullValidation)throws Exception{
            Objects.requireNonNull(change,"Schema变更不能为空");
            Objects.requireNonNull(fullValidation,"完整Schema验证不能为空");
            synchronized(fixture){
                requireHeld();if(activeWork!=null)throw new SQLException("未发布Schema变更不能重入");
                completionChecks.add(fullValidation);
                return withSchema(change);
            }
        }
        public AuthoritySqlFixture publish()throws Exception{
            synchronized(fixture){
                requireHeld();if(activeWork!=null)throw new SQLException("不能在Schema工作内重入发布");
                if(!lastWorkSucceeded)throw new SQLException("最后一次Schema工作未成功，须显式修复并重新执行");
                // 普通校验失败由withSchema保留HELD；在发布副作用的UNKNOWN收口之前执行。
                for(UnpublishedSchemaWork<Void> check:completionChecks)withSchema(check);
                try{
                    verifySchema();verifyUnpublishedOwner();phase=Phase.PUBLISHING;
                    closeSchema();dropConfirmedSchemaUser();
                    try(var admin=fixture.admin(fixture.database)){
                        try(var input=AuthoritySqlFixture.class.getResourceAsStream("/db/game-route-authority-schema.sql")){
                            if(input==null)throw new SQLException("缺同源正式authority DDL");
                            String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                            for(String sql:ddl.split(";"))if(!sql.isBlank())try(var statement=admin.createStatement()){
                                statement.setQueryTimeout(5);fixture.unknown=true;executeOwnerDdl(admin,statement,()->statement.execute(sql));fixture.unknown=false;
                            }
                        }
                        fixture.unknown=true;
                        RouteAuthorityInitializer.initialize(admin,UUID.randomUUID().toString(),fixture.options.zones(),currentUser(admin));
                        fixture.unknown=false;fixture.register(admin);
                    }
                    phase=Phase.PUBLISHED;return fixture;
                }catch(Exception|Error failure){failUnknown(failure);throw failure;}
            }
        }
        private void closeSchema()throws SQLException{
            if(schema!=null){
                Connection connection=schema;
                try{
                    connection.close();
                    if(!connection.isClosed())throw new SQLException("Schema会话关闭未确认","08007");
                    schema=null;
                }catch(SQLException|RuntimeException|Error failure){
                    try{connection.abort(Runnable::run);}catch(Exception|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
                }
            }
        }
        private void dropConfirmedSchemaUser()throws Exception{
            if(!schemaUserCreated)return;
            verifyUnpublishedOwner();
            try(var account=DriverManager.getConnection(fixture.jdbcUrl(""),schemaUser,schemaPassword)){
                if(!configuredPrincipal.equals(currentUser(account)))throw new SQLException("待撤销Schema账号归属不同");
            }
            try(var admin=fixture.admin("");var statement=admin.createStatement()){
                fixture.unknown=true;statement.execute("DROP USER "+literal(schemaUser)+"@'%'");
                schemaUserCreated=false;fixture.unknown=false;
            }
        }
        private void failUnknown(Throwable failure){
            phase=Phase.FAILED_UNKNOWN;fixture.unknown=true;lastWorkSucceeded=false;
            if(fixture.boot!=null)fixture.boot.seal();
            if(activeWork!=null)activeWork.active=false;
            try{closeSchema();}catch(Exception|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
        }
        private static boolean unknownOutcome(Throwable failure){
            Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
            var pending=new ArrayDeque<Throwable>();pending.add(failure);
            while(!pending.isEmpty()){
                Throwable cause=pending.removeFirst();if(!seen.add(cause))continue;
                if(cause instanceof Error||cause instanceof java.io.IOException||cause instanceof InterruptedException
                        ||cause instanceof SQLTimeoutException||cause instanceof SQLRecoverableException
                        ||cause instanceof SQLTransientConnectionException||cause instanceof SQLNonTransientConnectionException)return true;
                if(cause instanceof SQLException sql){String state=sql.getSQLState();
                    if(state!=null&&(state.startsWith("08")||state.startsWith("HYT")||state.startsWith("S1T")||state.equals("70100")))return true;
                    if(sql.getNextException()!=null)pending.addLast(sql.getNextException());
                }
                if(cause.getCause()!=null)pending.addLast(cause.getCause());
                for(Throwable suppressed:cause.getSuppressed())pending.addLast(suppressed);
            }
            return false;
        }
        private static void raise(Throwable failure)throws Exception{
            if(failure instanceof Exception exception)throw exception;
            if(failure instanceof Error error)throw error;
            throw new SQLException("Schema工作失败");
        }
        @Override public void close()throws Exception{
            synchronized(fixture){
                if(phase==Phase.CLOSED||phase==Phase.PUBLISHED)return;
                if(activeWork!=null)throw new SQLException("不能在Schema工作内重入关闭");
                if(phase==Phase.FAILED_UNKNOWN||fixture.unknown){
                    var failure=new SQLException("Schema结果未知，lease已撤销，保留owner原件及对象","08007");
                    try{closeSchema();}catch(Exception|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
                }
                requireHeld();
                try{verifySchema();verifyUnpublishedOwner();closeSchema();dropConfirmedSchemaUser();fixture.close();phase=Phase.CLOSED;}
                catch(Exception|Error failure){failUnknown(failure);throw failure;}
            }
        }
    }
    private UnpublishedSchemaLease unpublishedOwner;
    private boolean ownerSchemaWorkActive;
    private Throwable fixtureUnknownCause;
    private final Set<java.util.concurrent.ExecutorService> normalActorExecutors=Collections.newSetFromMap(new IdentityHashMap<>());
    /** 真实executor在提交首个normal actor前登记；不以Future已cancel代替线程确已终结。 */
    public synchronized void trackNormalActors(java.util.concurrent.ExecutorService executor)throws SQLException{
        Objects.requireNonNull(executor,"normal actor executor不能为空");
        if(closed||unknown||!owned||boot==null)throw new SQLException("normal actor缺已发布owned fixture");
        normalActorExecutors.add(executor);
    }
    /** owner-admin与normal-worker只共用这一不可逆撤销入口；绝不据此发放新授权。 */
    public synchronized void retainUnknownActorOutcome(Throwable failure){poisonFixture(Objects.requireNonNull(failure,"未知actor结局不能为空"));}
    public synchronized void requireNormalActorsTerminated()throws SQLException{
        for(var executor:normalActorExecutors)if(!executor.isTerminated()){
            var failure=new SQLException("normal actor未确认终结，保留owned原件和对象","08007");
            poisonFixture(failure);throw failure;
        }
    }
    /** 只供已发布隔离fixture的同步迁移/故障工作；连接及派生对象不逃离回调。 */
    @FunctionalInterface public interface OwnerSchemaWork<T>{T run(Connection owner)throws Exception;}
    public <T>T withOwnerSchema(OwnerSchemaWork<T> work)throws Exception{
        Objects.requireNonNull(work,"owner Schema工作不能为空");
        synchronized(this){
            if(closed||unknown||!owned||boot==null||ownerSchemaWorkActive
                    ||unpublishedOwner!=null&&unpublishedOwner.phase!=UnpublishedSchemaLease.Phase.PUBLISHED)
                throw new SQLException("owner Schema工作缺已发布的独占fixture或发生重入");
            ownerSchemaWorkActive=true;Connection owner=null;OwnerSchemaScope scope=new OwnerSchemaScope();T result=null;Throwable failure=null;
            try{
                try{owner=admin(database);verifyOwnerSchemaWork(owner);}
                catch(Exception|Error invalid){failure=invalid;poisonFixture(invalid);}
                if(failure==null){
                    try{result=work.run((Connection)scope.view(owner));}
                    catch(Exception|Error caught){failure=caught;if(isUnknownFixtureOutcome(caught))poisonFixture(caught);}
                    // 首个未知之后只能撤销及清理；不得再发归属查询或任何SQL来洗白。
                    if(!unknown&&scope.firstUnknown==null)try{verifyOwnerSchemaWork(owner);}
                    catch(Exception|Error invalid){failure=mergeFixtureFailure(failure,invalid);poisonFixture(invalid);}
                }
            }finally{
                try{scope.revoke();}
                catch(Exception|Error cleanup){failure=mergeFixtureFailure(failure,cleanup);poisonFixture(cleanup);}
                if(owner!=null)try{owner.close();}
                catch(Exception|Error cleanup){failure=mergeFixtureFailure(failure,cleanup);poisonFixture(cleanup);}
                ownerSchemaWorkActive=false;
            }
            if(unknown){
                if(failure==null)failure=new SQLException("owner Schema结果未知，禁止捕获异常后冒成功","08007",fixtureUnknownCause);
                else failure=mergeFixtureFailure(failure,fixtureUnknownCause);
            }
            if(failure!=null)UnpublishedSchemaLease.raise(failure);
            return result;
        }
    }
    /** 与原SchemaScope使用同一组JDBC视图/拒绝规则；这里只持有本次已发布owner的admin5能力。 */
    private final class OwnerSchemaScope{
        private final Thread thread=Thread.currentThread();
        private final IdentityHashMap<Object,Object> views=new IdentityHashMap<>();
        private final List<AutoCloseable> resources=new ArrayList<>();
        private boolean active=true;
        private Throwable firstUnknown;
        private void unknown(Throwable failure){firstUnknown=mergeFixtureFailure(firstUnknown,failure);poisonFixture(failure);}
        private void requireThread()throws SQLException{
            if(Thread.currentThread()!=thread)throw new OwnerSchemaRejected("owner Schema禁止跨线程SQL");
        }
        private void ensureActive()throws SQLException{
            if(!active||!ownerSchemaWorkActive||closed||unknown||firstUnknown!=null)
                throw new SQLException("owner Schema JDBC能力已撤销","08007",firstUnknown==null?fixtureUnknownCause:firstUnknown);
        }
        private Object view(Object target)throws SQLException{
            requireThread();ensureActive();if(target==null)return null;
            Object prior=views.get(target);if(prior!=null)return prior;
            Class<?> type=null;
            if(target instanceof Connection)type=Connection.class;
            else if(target instanceof CallableStatement)type=CallableStatement.class;
            else if(target instanceof PreparedStatement)type=PreparedStatement.class;
            else if(target instanceof Statement)type=Statement.class;
            else if(target instanceof ResultSet)type=ResultSet.class;
            else if(target instanceof DatabaseMetaData)type=DatabaseMetaData.class;
            else if(target instanceof ResultSetMetaData)type=ResultSetMetaData.class;
            else if(target instanceof ParameterMetaData)type=ParameterMetaData.class;
            else if(target instanceof java.sql.Array array){resources.add(array::free);throw new OwnerSchemaRejected("owner Schema不暴露数组driver对象");}
            else if(target instanceof Blob blob){resources.add(blob::free);throw new OwnerSchemaRejected("owner Schema不暴露LOB driver对象");}
            else if(target instanceof Clob clob){resources.add(clob::free);throw new OwnerSchemaRejected("owner Schema不暴露LOB driver对象");}
            else if(target instanceof SQLXML xml){resources.add(xml::free);throw new OwnerSchemaRejected("owner Schema不暴露XML driver对象");}
            else if(target instanceof InputStream stream){resources.add(stream);throw new OwnerSchemaRejected("owner Schema不暴露driver输入流");}
            else if(target instanceof Reader reader){resources.add(reader);throw new OwnerSchemaRejected("owner Schema不暴露driver字符流");}
            else if(target instanceof java.sql.Wrapper||target instanceof java.sql.Struct||target instanceof java.sql.Ref)
                throw new OwnerSchemaRejected("owner Schema不暴露其它driver对象");
            if(type==null)return target;
            if(target instanceof Statement statement){
                resources.add(statement);
                try{statement.setQueryTimeout(5);}
                catch(SQLException|RuntimeException|Error invalid){unknown(invalid);throw invalid;}
            }else if(target instanceof ResultSet rows)resources.add(rows);
            final Object delegate=target;
            Object proxy=java.lang.reflect.Proxy.newProxyInstance(AuthoritySqlFixture.class.getClassLoader(),new Class<?>[]{type},
                    (object,method,args)->invokeTarget(delegate,object,method,args));
            views.put(target,proxy);return proxy;
        }
        private Object invokeTarget(Object target,Object proxy,java.lang.reflect.Method method,Object[] args)throws Throwable{
            String name=method.getName();
            if(method.getDeclaringClass()==Object.class){
                if(name.equals("toString"))return "受控owner Schema JDBC视图";
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
            }
            // 必须在争抢fixture monitor之前拒绝，避免callback等待跨线程SQL时互相死锁。
            requireThread();
            synchronized(AuthoritySqlFixture.this){
                ensureActive();
                if(name.equals("unwrap"))throw new OwnerSchemaRejected("owner Schema禁止unwrap");
                if(name.equals("isWrapperFor"))return false;
                if(target instanceof Connection&&name.equals("abort")){
                    var failure=new SQLException("owner Schema会话被显式丢弃，结果未知","08007");unknown(failure);throw failure;
                }
                if(target instanceof Connection&&name.equals("setClientInfo"))throw new OwnerClientInfoRejected();
                if(target instanceof Connection&&(name.equals("close")||name.equals("setNetworkTimeout")
                        ||name.equals("setCatalog")||name.equals("setSchema")||name.equals("setAutoCommit")
                        ||name.equals("commit")||name.equals("rollback")||name.equals("setSavepoint")||name.equals("releaseSavepoint")
                        ||name.equals("beginRequest")||name.equals("endRequest")||name.equals("setTransactionIsolation")
                        ||name.equals("setReadOnly")||name.equals("setTypeMap")||name.equals("setHoldability")
                        ||name.equals("createArrayOf")||name.equals("createBlob")||name.equals("createClob")
                        ||name.equals("createNClob")||name.equals("createSQLXML")||name.equals("createStruct")))
                    throw new OwnerSchemaRejected("owner Schema生命周期/配置/事务仅由owner管理");
                if(target instanceof ResultSet&&(name.equals("getBlob")||name.equals("getClob")||name.equals("getNClob")
                        ||name.equals("getSQLXML")||name.equals("getArray")||name.equals("getRef")
                        ||name.equals("getBinaryStream")||name.equals("getAsciiStream")||name.equals("getCharacterStream")||name.equals("getNCharacterStream")))
                    throw new OwnerSchemaRejected("owner Schema不创建LOB/数组/流driver对象");
                if(target instanceof Statement&&name.equals("setQueryTimeout")&&((Integer)args[0]<=0||(Integer)args[0]>5))
                    throw new OwnerSchemaRejected("owner Schema不能扩大admin5语句期限");
                try{return view(method.invoke(target,args));}
                catch(java.lang.reflect.InvocationTargetException wrapped){
                    Throwable failure=wrapped.getCause();
                    if(name.equals("close")||name.equals("setQueryTimeout")||isUnknownFixtureOutcome(failure))unknown(failure);
                    throw failure;
                }catch(Exception|Error failure){
                    if(isUnknownFixtureOutcome(failure))unknown(failure);throw failure;
                }
            }
        }
        private void revoke()throws Exception{
            active=false;Throwable failure=null;
            for(int index=resources.size()-1;index>=0;index--)try{resources.get(index).close();}
            catch(Exception|Error cleanup){unknown(cleanup);failure=mergeFixtureFailure(failure,cleanup);}
            resources.clear();views.clear();if(failure!=null)UnpublishedSchemaLease.raise(failure);
        }
    }
    private static final class OwnerSchemaRejected extends SQLFeatureNotSupportedException{
        OwnerSchemaRejected(String message){super(message,"0A000");}
    }
    private static final class OwnerClientInfoRejected extends SQLClientInfoException{
        OwnerClientInfoRejected(){super("owner Schema禁止变更client info","0A000",Map.of());}
    }
    /** 只窄认明确拒绝；没有SQLState、vendor=0或通用HY000都不能当作确定结局。 */
    public static boolean isUnknownFixtureOutcome(Throwable failure){
        if(failure==null||Thread.currentThread().isInterrupted()||UnpublishedSchemaLease.unknownOutcome(failure))return true;
        var known=Set.of("23000/1062","42S21/1060","42S22/1054","42S02/1146","42000/1064","42000/1044","42000/1142","42000/1143");
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var pending=new ArrayDeque<Throwable>();pending.add(failure);boolean rejected=false;
        while(!pending.isEmpty()){
            Throwable cause=pending.removeFirst();if(!seen.add(cause))continue;
            if(cause instanceof java.util.concurrent.TimeoutException||cause instanceof java.util.concurrent.CancellationException)return true;
            if(cause instanceof OwnerSchemaRejected||cause instanceof OwnerClientInfoRejected)rejected=true;
            else if(cause instanceof SQLException sql){
                String state=sql.getSQLState();if(state==null||sql.getErrorCode()==0||!known.contains(state+"/"+sql.getErrorCode()))return true;
                rejected=true;if(sql.getNextException()!=null)pending.addLast(sql.getNextException());
            }else if(cause.getCause()==null&&cause.getSuppressed().length==0)return true;
            if(cause.getCause()!=null)pending.addLast(cause.getCause());
            for(Throwable suppressed:cause.getSuppressed())pending.addLast(suppressed);
        }
        return !rejected;
    }
    private synchronized void poisonFixture(Throwable failure){
        unknown=true;fixtureUnknownCause=mergeFixtureFailure(fixtureUnknownCause,Objects.requireNonNull(failure));if(boot!=null)boot.seal();
    }
    private static boolean containsFixtureFailure(Throwable root,Throwable wanted){
        if(root==null||wanted==null)return false;Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var pending=new ArrayDeque<Throwable>();pending.add(root);
        while(!pending.isEmpty()){
            Throwable current=pending.removeFirst();if(current==wanted)return true;if(!seen.add(current))continue;
            if(current.getCause()!=null)pending.addLast(current.getCause());for(Throwable suppressed:current.getSuppressed())pending.addLast(suppressed);
            if(current instanceof SQLException sql&&sql.getNextException()!=null)pending.addLast(sql.getNextException());
        }return false;
    }
    private static Throwable mergeFixtureFailure(Throwable primary,Throwable next){
        if(primary==null)return next;if(next==null||containsFixtureFailure(primary,next)||containsFixtureFailure(next,primary))return primary;
        primary.addSuppressed(next);return primary;
    }
    private void verifyOwnerSchemaWork(Connection owner)throws Exception{
        if(!owner.getAutoCommit()||!database.equals(owner.getCatalog())||Thread.currentThread().isInterrupted())
            throw new SQLException("owner Schema工作不能转移事务/数据库或忽略取消","08007");
        if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(ownerFile,65536),ownerBytes()))
            throw new SQLException("owner Schema工作私有归属原件不同","08007");
        if(activeNormalAccount==null)throw new SQLException("owner Schema工作缺当前NORMAL身份锚","08007");
        try(var q=owner.prepareStatement("SELECT owner_nonce,physical_sha,normal_principal FROM __sql_fixture_owner WHERE id=1")){
            q.setQueryTimeout(5);try(var r=q.executeQuery()){
                if(!r.next()||!MessageDigest.isEqual(nonce,r.getBytes(1))||!MessageDigest.isEqual(options.physical(),r.getBytes(2))
                        ||!MessageDigest.isEqual(activeNormalAccount.principal().getBytes(StandardCharsets.UTF_8),r.getBytes(3))||r.next())
                    throw new SQLException("owner Schema工作数据库归属不同","08007");
            }
        }
    }
    /** 未发布owner不能通过旧schema入口取得管理员连接；成功发布后才恢复既有fixture用途。 */
    public Connection schemaConnection()throws SQLException{
        synchronized(this){
            if(ownerSchemaWorkActive)throw new SQLException("owner Schema工作中不能另取裸连接");
            if(unpublishedOwner!=null&&unpublishedOwner.phase!=UnpublishedSchemaLease.Phase.PUBLISHED)
                throw new SQLException("未发布owner只能使用受管Schema阶段，禁止管理员连接旁路");
            return schemaConnectionInternal();
        }
    }
    /** 旧路径无lease时原注册体不变；新owner只在账号和会话均撤销后的PUBLISHING阶段抵达注册。 */
    private void register(Connection admin)throws Exception{
        synchronized(this){
            if(unpublishedOwner!=null&&(unpublishedOwner.phase!=UnpublishedSchemaLease.Phase.PUBLISHING
                    ||unpublishedOwner.schema!=null||unpublishedOwner.schemaUserCreated||unpublishedOwner.activeWork!=null))
                throw new SQLException("未发布Schema阶段尚未撤销，禁止REGISTER旁路");
            registerInternal(admin);
        }
    }
    /** 仅供本fixture的SQL-seam种子在真实旧run显式release后再登记；不是普通PUBLISHED注册入口。 */
    private void releaseSeededSqlSeamAndRegister(Connection admin,byte[] priorRun)throws Exception{
        if(unknown||closed||!owned||boot==null)throw new SQLException("SQL seam释放再登记不可用");
        if(unpublishedOwner!=null&&(unpublishedOwner.phase!=UnpublishedSchemaLease.Phase.PUBLISHED
                ||unpublishedOwner.schema!=null||unpublishedOwner.schemaUserCreated||unpublishedOwner.activeWork!=null))
            throw new SQLException("SQL seam释放再登记要求已发布且Schema阶段已完全撤销");
        if(!admin.getAutoCommit())throw new SQLException("SQL seam释放再登记不能继承事务");
        String old=uuidText(priorRun);
        if(!sqlSeamFreezes.containsKey(old))throw new SQLException("不能释放非本fixture控制种子");
        if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(ownerFile,65536),ownerBytes()))
            throw new SQLException("SQL seam释放再登记私有owner不同");
        try(var q=admin.prepareStatement("SELECT owner_nonce,physical_sha FROM __sql_fixture_owner WHERE id=1")){
            q.setQueryTimeout(5);
            try(var rows=q.executeQuery()){
                if(!rows.next()||!MessageDigest.isEqual(nonce,rows.getBytes(1))
                        ||!MessageDigest.isEqual(options.physical(),rows.getBytes(2))||rows.next())
                    throw new SQLException("SQL seam释放再登记数据库owner不同");
            }
        }
        try(var q=admin.prepareStatement("SELECT writable,freeze_run_id FROM route_meta WHERE id=1")){
            q.setQueryTimeout(5);
            try(var rows=q.executeQuery()){
                if(!rows.next()||rows.getInt(1)!=0||!Arrays.equals(priorRun,rows.getBytes(2))||rows.next())
                    throw new SQLException("SQL seam释放再登记不是同一冻结旧run");
            }
        }
        try(var q=admin.prepareStatement("SELECT state FROM zone_merge_run WHERE run_id=?")){
            q.setString(1,old);q.setQueryTimeout(5);
            try(var rows=q.executeQuery()){
                if(!rows.next()||rows.getInt(1)!=6||rows.next())throw new SQLException("前一域run尚未显式release");
            }
        }
        // 所有owner/phase/priorRun/state前置已完成，才进入原解冻和真实再登记顺序。
        try{
            unknown=true;
            update(admin,"UPDATE route_meta SET writable=1,writer_epoch=writer_epoch+1,freeze_run_id=? WHERE id=1 AND writable=0 AND freeze_run_id=?",new byte[0],priorRun);
            unknown=false;registerInternal(admin);
        }catch(Exception|Error failure){
            if(unpublishedOwner!=null)unpublishedOwner.failUnknown(failure);
            else{unknown=true;if(boot!=null)boot.seal();}
            throw failure;
        }
    }
    private static final long OWNER_DDL_TIMEOUT_NANOS=60_000_000_000L;
    /** 仅owner的具名DDL临时使用60秒；短读取和普通admin/NORMAL连接保留原期限。 */
    private static <T>T executeOwnerDdl(Connection connection,Statement statement,java.util.concurrent.Callable<T> command)throws Exception{
        long until=System.nanoTime()+OWNER_DDL_TIMEOUT_NANOS;
        final int priorNetwork,priorQuery;
        try{priorNetwork=connection.getNetworkTimeout();priorQuery=statement.getQueryTimeout();}
        catch(Exception invalid){throw new SQLException("owner DDL原期限读取未确认","08007",invalid);}
        T result=null;Throwable failure=null;boolean started=false;
        try{
            int millis=ownerDdlRemainingMillis(until);
            int seconds=Math.max(1,(millis+999)/1000);
            statement.setQueryTimeout(seconds);
            if(statement.getQueryTimeout()!=seconds)throw new SQLException("owner DDL语句期限安装未确认","08007");
            millis=ownerDdlRemainingMillis(until);
            connection.setNetworkTimeout(Runnable::run,millis);
            if(connection.getNetworkTimeout()!=millis)throw new SQLException("owner DDL网络期限安装未确认","08007");
            ownerDdlRemainingMillis(until);started=true;result=command.call();
            ownerDdlRemainingMillis(until);
        }catch(Exception|Error caught){
            failure=!started&&!(caught instanceof Error)?new SQLException("owner DDL期限安装未确认","08007",caught):caught;
        }finally{
            try{statement.setQueryTimeout(priorQuery);if(statement.getQueryTimeout()!=priorQuery)throw new SQLException("原语句期限不同");}
            catch(Exception|Error cleanup){
                var invalid=new SQLException("owner DDL原语句期限恢复未确认","08007",cleanup);
                if(failure==null)failure=invalid;else failure.addSuppressed(invalid);
            }
            try{connection.setNetworkTimeout(Runnable::run,priorNetwork);if(connection.getNetworkTimeout()!=priorNetwork)throw new SQLException("原网络期限不同");}
            catch(Exception|Error cleanup){
                var invalid=new SQLException("owner DDL原网络期限恢复未确认","08007",cleanup);
                if(failure==null)failure=invalid;else failure.addSuppressed(invalid);
            }
        }
        try{ownerDdlRemainingMillis(until);}
        catch(SQLTimeoutException expired){if(failure==null)failure=expired;else failure.addSuppressed(expired);}
        if(failure!=null){UnpublishedSchemaLease.raise(failure);return null;}
        return result;
    }
    private static int ownerDdlRemainingMillis(long until)throws SQLTimeoutException{
        long remaining=until-System.nanoTime();
        if(remaining<=0)throw new SQLTimeoutException("owner DDL用尽60秒期限，结局未知","HYT00");
        return (int)Math.max(1,remaining/1_000_000L);
    }
    /** 每次同步回调的可撤销JDBC视图；不向调用方泄漏driver对象或异步SQL入口。 */
    private static final class SchemaScope{
        private final UnpublishedSchemaLease lease;
        private final Thread thread=Thread.currentThread();
        private final IdentityHashMap<Object,Object> views=new IdentityHashMap<>();
        private final List<AutoCloseable> resources=new ArrayList<>();
        private final Set<Object> preparedCreates=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        private final Set<Object> preparedAlters=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        private boolean active=true;
        private SchemaScope(UnpublishedSchemaLease lease){this.lease=lease;}
        private Connection connection()throws SQLException{return (Connection)view(lease.schema);}
        private Object view(Object target)throws SQLException{
            if(target==null)return null;
            Object prior=views.get(target);if(prior!=null)return prior;
            Class<?> type=null;
            if(target instanceof Connection)type=Connection.class;
            else if(target instanceof CallableStatement)type=CallableStatement.class;
            else if(target instanceof PreparedStatement)type=PreparedStatement.class;
            else if(target instanceof Statement)type=Statement.class;
            else if(target instanceof ResultSet)type=ResultSet.class;
            else if(target instanceof DatabaseMetaData)type=DatabaseMetaData.class;
            else if(target instanceof ResultSetMetaData)type=ResultSetMetaData.class;
            else if(target instanceof ParameterMetaData)type=ParameterMetaData.class;
            else if(target instanceof java.sql.Array array){resources.add(array::free);throw new SQLFeatureNotSupportedException("Schema阶段不暴露数组driver对象");}
            else if(target instanceof Blob blob){resources.add(blob::free);throw new SQLFeatureNotSupportedException("Schema阶段不暴露LOB driver对象");}
            else if(target instanceof Clob clob){resources.add(clob::free);throw new SQLFeatureNotSupportedException("Schema阶段不暴露LOB driver对象");}
            else if(target instanceof SQLXML xml){resources.add(xml::free);throw new SQLFeatureNotSupportedException("Schema阶段不暴露XML driver对象");}
            else if(target instanceof java.sql.Wrapper||target instanceof java.sql.Struct)
                throw new SQLFeatureNotSupportedException("Schema阶段不暴露其它driver对象");
            if(type==null)return target;
            final Object delegate=target;
            Object proxy=java.lang.reflect.Proxy.newProxyInstance(AuthoritySqlFixture.class.getClassLoader(),new Class<?>[]{type},
                    (object,method,args)->invokeTarget(delegate,object,method,args));
            views.put(target,proxy);
            if(target instanceof Statement statement)resources.add(statement);
            else if(target instanceof ResultSet rows)resources.add(rows);
            return proxy;
        }
        private static boolean createsTable(String sql){
            return sql!=null&&sql.matches("(?is)^\\s*CREATE\\s+TABLE\\b.*");
        }
        private static boolean altersTable(String sql){
            return sql!=null&&sql.matches("(?is)^\\s*ALTER\\s+TABLE\\b.*");
        }
        private static Object invokeDdl(Object target,java.lang.reflect.Method method,Object[] args)throws Exception{
            try{return method.invoke(target,args);}
            catch(java.lang.reflect.InvocationTargetException wrapped){UnpublishedSchemaLease.raise(wrapped.getCause());return null;}
        }
        private Object invokeTarget(Object target,Object proxy,java.lang.reflect.Method method,Object[] args)throws Throwable{
            String name=method.getName();
            if(method.getDeclaringClass()==Object.class){
                if(name.equals("toString"))return "未发布Schema阶段JDBC视图";
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
            }
            if(Thread.currentThread()!=thread)throw new SQLException("Schema阶段禁止跨线程SQL");
            synchronized(lease.fixture){
                if(!active||Thread.currentThread()!=thread||lease.activeWork!=this||lease.phase!=UnpublishedSchemaLease.Phase.HELD)
                    throw new SQLException("Schema阶段JDBC能力已撤销或跨线程使用");
                if(name.equals("unwrap"))throw new SQLException("Schema阶段禁止unwrap");
                if(name.equals("isWrapperFor"))return false;
                if(target instanceof Connection&&name.equals("abort")){
                    lease.failUnknown(new SQLException("Schema会话被显式丢弃，lease已撤销","08007"));return null;
                }
                if(target instanceof Connection&&(name.equals("close")||name.equals("setNetworkTimeout")
                        ||name.equals("setCatalog")||name.equals("setSchema")||name.equals("setClientInfo")
                        ||name.equals("beginRequest")||name.equals("endRequest")||name.equals("setTransactionIsolation")
                        ||name.equals("setReadOnly")||name.equals("setTypeMap")||name.equals("setHoldability")
                        ||name.equals("createArrayOf")||name.equals("createBlob")||name.equals("createClob")
                        ||name.equals("createNClob")||name.equals("createSQLXML")||name.equals("createStruct")))
                    throw new SQLException("Schema会话生命周期仅由owner管理");
                if(target instanceof ResultSet&&(name.equals("getBlob")||name.equals("getClob")||name.equals("getNClob")
                        ||name.equals("getSQLXML")||name.equals("getArray")))
                    throw new SQLFeatureNotSupportedException("Schema阶段不创建LOB或数组driver对象");
                // CREATE仅在执行后补现表权限；ALTER只取DDL期限，不触发跨连接GRANT。
                boolean sqlArgument=args!=null&&args.length>0&&args[0] instanceof String;
                boolean createTable=sqlArgument?createsTable((String)args[0]):preparedCreates.contains(target);
                boolean alterTable=sqlArgument?altersTable((String)args[0]):preparedAlters.contains(target);
                if(target instanceof Statement&&name.equals("addBatch")&&(createTable||alterTable))
                    throw new SQLFeatureNotSupportedException("未发布Schema阶段不支持批量CREATE/ALTER TABLE，请逐条执行","0A000");
                boolean executedDdl=target instanceof Statement&&(createTable||alterTable)
                        &&(name.equals("execute")||name.equals("executeUpdate")||name.equals("executeLargeUpdate"));
                try{
                    Object result=executedDdl?executeOwnerDdl(lease.schema,(Statement)target,()->invokeDdl(target,method,args)):method.invoke(target,args);
                    if(target instanceof Connection&&result instanceof Statement statement){
                        preparedCreates.remove(statement);preparedAlters.remove(statement);
                        if((name.equals("prepareStatement")||name.equals("prepareCall"))&&sqlArgument){
                            if(createsTable((String)args[0]))preparedCreates.add(statement);
                            else if(altersTable((String)args[0]))preparedAlters.add(statement);
                        }
                    }
                    // 期限已在finally恢复；owner/GRANT仍受原短期限，不把60秒传给普通admin读取。
                    if(executedDdl&&createTable)lease.grantBusinessRowsAfterCreate();
                    return view(result);
                }
                catch(java.lang.reflect.InvocationTargetException wrapped){
                    Throwable failure=wrapped.getCause();if(!(failure instanceof SQLException)||UnpublishedSchemaLease.unknownOutcome(failure))lease.failUnknown(failure);throw failure;
                }
                catch(Exception|Error failure){
                    if(!(failure instanceof SQLException)||UnpublishedSchemaLease.unknownOutcome(failure))lease.failUnknown(failure);throw failure;
                }
            }
        }
        private void revoke()throws Exception{
            active=false;Throwable failure=null;
            for(int index=resources.size()-1;index>=0;index--)try{resources.get(index).close();}
            catch(Exception|Error cleanup){if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);}
            resources.clear();views.clear();preparedCreates.clear();preparedAlters.clear();if(failure!=null)UnpublishedSchemaLease.raise(failure);
        }
    }

    private static final Set<String> CONTROL=Set.of("route_meta","zone_merged_into","zone_merge_fence","writer_registry","writer_slot","writer_lifecycle_receipt","sql_writer_drain_run","sql_writer_freeze_ticket","sql_writer_maintenance_receipt","__sql_fixture_owner");
    private final Options options;private final String database,user,password;private final byte[] nonce;private final Path ownerFile;private boolean owned,userCreated,unknown,closed;private WriterAuthority boot;
    private record NormalAccount(String user,String password,String principal){}
    private final List<NormalAccount> normalAccounts=new ArrayList<>();
    private NormalAccount activeNormalAccount;
    private AuthoritySqlFixture(Options options)throws Exception{this(options,null);}
    private AuthoritySqlFixture(Options options,com.game.common.GameNamespace namespace)throws Exception{
        this.options=options;if(!options.root().matches("jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/?"))throw new SQLException("fixture只接显式数值loopback端口，不能带业务库或URL参数");
        int port=Integer.parseInt(options.root().replaceAll("/$","").substring(options.root().lastIndexOf(':')+1));if(port>65535||!options.prefix().matches("[a-z][a-z0-9_]{0,25}"))throw new SQLException("fixture目标/前缀非法");
        if(options.physical().length!=32||options.qualification().length!=32||Arrays.equals(options.physical(),new byte[32])||Arrays.equals(options.qualification(),new byte[32])||!Set.of("SELECT_ONLY","LOCK_TOUCH").contains(options.primitive()))throw new SQLException("fixture真实资格原件非法");
        WriterAuthority.requirePrivateDirectory(options.receipts());String suffix=HexFormat.of().formatHex(random(12));database=namespace==null?options.prefix()+"_it_"+suffix:namespace.value();user="wf_"+suffix;password=Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));nonce=random(32);ownerFile=options.receipts().resolve(namespace==null?database+".owner":database+"."+suffix+".owner");write(ownerFile,ownerBytes());
        try(var admin=admin("");var statement=admin.createStatement()){
            try(var rows=statement.executeQuery("SELECT VERSION()")){if(!rows.next()||!options.allowMySql()&&!rows.getString(1).toLowerCase(Locale.ROOT).contains("tidb"))throw new SQLException("fixture需要实际TiDB");}
            unknown=true;executeOwnerDdl(admin,statement,()->statement.execute("CREATE DATABASE "+name(database)+" CHARACTER SET utf8mb4"));unknown=false;
        }
        try(var admin=admin(database);var statement=admin.createStatement()){
            unknown=true;executeOwnerDdl(admin,statement,()->statement.execute("CREATE TABLE __sql_fixture_owner(id INT PRIMARY KEY,owner_nonce VARBINARY(32) NOT NULL,physical_sha VARBINARY(32) NOT NULL,normal_principal VARBINARY(288) NOT NULL)"));unknown=false;
            unknown=true;update(admin,"INSERT INTO __sql_fixture_owner VALUES(1,?,?,?)",nonce,options.physical(),new byte[0]);unknown=false;owned=true;
        }
    }
    public static AuthoritySqlFixture prepare(Options options,SchemaBuilder schema)throws Exception{
        return prepareInternal(options,schema,null);
    }
    /** 仅本批隔离测试：先验固定正式player源，再由本fixture建立正式控制表和player表。 */
    public static AuthoritySqlFixture prepareWithPlayerSchema(Options options,SchemaBuilder schema)throws Exception{
        return prepareInternal(options,schema,readFixedPlayerSchema());
    }
    private static AuthoritySqlFixture prepareInternal(Options options,SchemaBuilder schema,List<String> playerSchema)throws Exception{
        var fixture=new AuthoritySqlFixture(options);
        try(var admin=fixture.admin(fixture.database)){
            String principal=currentUser(admin);
            if(playerSchema==null){fixture.unknown=true;schema.create(admin,principal);fixture.unknown=false;}
            try(var input=AuthoritySqlFixture.class.getResourceAsStream("/db/game-route-authority-schema.sql")){
                if(input==null)throw new SQLException("缺同源正式authority DDL");String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                // 只执行本仓固定资源，不接受外部脚本或第二份控制表schema。
                for(String sql:ddl.split(";"))if(!sql.isBlank())try(var statement=admin.createStatement()){statement.setQueryTimeout(5);fixture.unknown=true;statement.execute(sql);fixture.unknown=false;}
            }
            if(playerSchema!=null){
                for(String sql:playerSchema)try(var statement=admin.createStatement()){statement.setQueryTimeout(5);fixture.unknown=true;statement.execute(sql);fixture.unknown=false;}
                fixture.unknown=true;schema.create(admin,principal);fixture.unknown=false;
            }
            fixture.unknown=true;RouteAuthorityInitializer.initialize(admin,UUID.randomUUID().toString(),options.zones(),principal);fixture.unknown=false;fixture.register(admin);return fixture;
        }catch(Exception failure){if(!fixture.unknown)try{fixture.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private static List<String> readFixedPlayerSchema()throws Exception{
        Path source=Path.of("").toAbsolutePath().normalize().resolveSibling("game-player-store").resolve("src/main/resources/db/game-player-schema.sql");
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new SQLException("缺本测试模块同级正式player DDL源");
        byte[] bytes=Files.readAllBytes(source);
        String normalized=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(normalized.startsWith("\uFEFF"))throw new SQLException("固定player DDL不接受UTF8 BOM");
        normalized=normalized.replace("\r\n","\n");
        if(normalized.indexOf('\r')>=0)throw new SQLException("固定player DDL含裸CR");
        if(!HexFormat.of().formatHex(sha(normalized.getBytes(StandardCharsets.UTF_8))).equalsIgnoreCase("7ECA35D7CC982A9FC705E8D9A8A2C5161E2A09AE71CFB803E5DAF869197B6534"))throw new SQLException("本批正式player DDL规范化SHA不符");
        // 仅CRLF转LF后的摘要和格式先验完成后才进入构造器；不重读或接受任意路径。
        return parseFixedPlayerSchema(normalized);
    }
    private static List<String> parseFixedPlayerSchema(String ddl)throws SQLException{
        var statements=new ArrayList<String>();var sql=new StringBuilder();boolean quoted=false,lineComment=false,lineBlank=true;
        for(int index=0;index<ddl.length();index++){
            char ch=ddl.charAt(index);char next=index+1<ddl.length()?ddl.charAt(index+1):0;
            if(lineComment){if(ch=='\n'){lineComment=false;lineBlank=true;sql.append(ch);}continue;}
            if(quoted){
                if(ch=='\\'||ch=='\r'||ch=='\n')throw new SQLException("固定player DDL字符串格式不受支持");
                sql.append(ch);if(ch=='\''){if(next=='\''){sql.append(next);index++;}else quoted=false;}continue;
            }
            if(ch=='\n'){lineBlank=true;sql.append(ch);continue;}
            if(ch=='-'&&next=='-'){
                if(!lineBlank||index+2>=ddl.length()||!Character.isWhitespace(ddl.charAt(index+2)))throw new SQLException("固定player DDL只支持当前全行注释");
                lineComment=true;index++;continue;
            }
            if(lineBlank&&ddl.regionMatches(true,index,"DELIMITER",0,9)&&(index+9==ddl.length()||Character.isWhitespace(ddl.charAt(index+9))))throw new SQLException("固定player DDL不支持DELIMITER指令");
            if(ch=='\''){quoted=true;lineBlank=false;sql.append(ch);continue;}
            if(ch==';'){
                String statement=sql.toString().trim();if(statement.isEmpty())throw new SQLException("固定player DDL含空语句");
                statements.add(statement);sql.setLength(0);lineBlank=false;continue;
            }
            if(ch=='"'||ch=='`'||ch=='#'||ch=='\\'||ch=='/'&&next=='*'||ch=='*'&&next=='/')throw new SQLException("固定player DDL含未知引号、转义或注释指令");
            if(!Character.isWhitespace(ch))lineBlank=false;sql.append(ch);
        }
        if(quoted||!sql.toString().isBlank())throw new SQLException("固定player DDL含未闭合字符串或缺分号语句");
        var tables=List.of("account","player","player_trade_freeze","player_trade_drain","player_trade_transfer","player_state","player_final_save","player_trade_admission_recovery");
        if(statements.size()!=tables.size())throw new SQLException("固定player DDL表语句数量不符");
        for(int index=0;index<tables.size();index++)if(!statements.get(index).matches("(?s)CREATE TABLE IF NOT EXISTS "+tables.get(index)+" \\(.*\\) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin"))throw new SQLException("固定player DDL含未知表或语句格式");
        return List.copyOf(statements);
    }
    public String database(){return database;}public synchronized String normalUser(){return activeNormalAccount==null?user:activeNormalAccount.user();}
    /** 只用于新池的显式装配；旧池仍绑定旧boot，不随SQL-seam再登记换绑。 */ public synchronized String normalPassword(){return activeNormalAccount==null?password:activeNormalAccount.password();}
    public String jdbcUrl(String parameters){if(!parameters.isEmpty()&&!parameters.startsWith("?"))throw new IllegalArgumentException("参数形状非法");return options.root().replaceAll("/$","")+"/"+database+parameters;}
    public synchronized WriterAuthority writerBoot(){if(boot==null||closed)throw new IllegalStateException("fixture主体未就绪或已关闭");return boot;}
    public synchronized DataSource normalDataSource(String parameters)throws SQLException{Properties p=new Properties();p.setProperty("user",normalUser());p.setProperty("password",normalPassword());return new AuthorityDataSource(jdbcUrl(parameters),p,writerBoot(),!options.allowMySql(),List.of());}
    /** 只供本夹具seed/故障注入，业务构造器必须接normalDataSource或普通池。 */
    public DataSource schemaDataSource(){return new DataSource(){
        public Connection getConnection()throws SQLException{return schemaConnection();}
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("fixture schema主体不能覆盖");}
        public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter writer)throws SQLException{throw new SQLException("fixture不输出SQL凭据");}
        public int getLoginTimeout(){return 5;}public void setLoginTimeout(int seconds)throws SQLException{throw new SQLException("fixture连接预算固定");}
        public java.util.logging.Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("fixture不允许unwrap");}public boolean isWrapperFor(Class<?> type){return false;}
    };}
    private Connection schemaConnectionInternal()throws SQLException{if(closed||unknown||!owned)throw new SQLException("fixture schema不可用");return admin(database);}
    private Connection admin(String db)throws SQLException{var connection=DriverManager.getConnection(options.root().replaceAll("/$","")+"/"+db,options.adminUser(),options.adminPassword());try{AuthorityDataSource.initialize(connection,!options.allowMySql(),List.of());connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setNetworkTimeout(Runnable::run,5000);return connection;}catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException cleanup){failure.addSuppressed(cleanup);}throw failure;}}
    private static String currentUser(Connection connection)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery("SELECT CURRENT_USER()")){if(!r.next())throw new SQLException("缺实际主体");String value=r.getString(1);if(value==null||value.isBlank()||r.next())throw new SQLException("实际主体不唯一");return value;}}
    private void registerInternal(Connection admin)throws Exception{
        // 正式registry保留退役主体的唯一性；每个新boot必须实际创建独立SQL账号。
        String nextUser=userCreated?"wf_"+HexFormat.of().formatHex(random(12)):user;
        String nextPassword=userCreated?Base64.getUrlEncoder().withoutPadding().encodeToString(random(32)):password;
        var account=new NormalAccount(nextUser,nextPassword,nextUser+"@%");
        // 固定具名意图先于CREATE：回包丢失时仍能精确找到待核账号；意图本身不证明CREATE成功。
        Path pendingAccount=options.receipts().resolve(database+".normal-account.intent");
        var accountIntent=new LinkedHashMap<String,Object>();accountIntent.put("format","SQL_FIXTURE_NORMAL_ACCOUNT_INTENT_V1");
        accountIntent.put("database",database);accountIntent.put("sqlUser",nextUser);accountIntent.put("sqlPrincipal",account.principal());
        accountIntent.put("ownerNonce",HexFormat.of().formatHex(nonce));accountIntent.put("physicalAuthoritySha256",HexFormat.of().formatHex(options.physical()));
        accountIntent.put("parentOwnerSha256",HexFormat.of().formatHex(sha(ownerBytes())));
        byte[] pendingAccountBytes=new ObjectMapper().writeValueAsBytes(accountIntent);write(pendingAccount,pendingAccountBytes);
        try(var statement=admin.createStatement()){
            unknown=true;try{statement.execute("CREATE USER "+literal(nextUser)+"@'%' IDENTIFIED BY "+literal(nextPassword));}
            catch(SQLException unsafe){throw new SQLException("fixture账号创建结局未知，保留私有owner原件","08007",unsafe);}
            normalAccounts.add(account);userCreated=true;unknown=false;
            List<String> tables=new ArrayList<>();try(var q=admin.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name")){q.setString(1,database);try(var rows=q.executeQuery()){while(rows.next())tables.add(rows.getString(1));}}
            for(String table:tables)if(!table.equals("__sql_fixture_owner")){unknown=true;statement.execute("GRANT "+((CONTROL.contains(table)||table.equals("maintenance_recovery_target"))?"SELECT":"SELECT,INSERT,UPDATE,DELETE")+" ON "+name(database)+"."+name(table)+" TO "+literal(nextUser)+"@'%'");unknown=false;}
            if(options.primitive().equals("LOCK_TOUCH")){unknown=true;statement.execute("GRANT UPDATE(lock_touch) ON "+name(database)+".writer_slot TO "+literal(nextUser)+"@'%'");unknown=false;}
        }
        unknown=true;String principal;try(var normal=DriverManager.getConnection(jdbcUrl(""),nextUser,nextPassword)){
            principal=currentUser(normal);if(!account.principal().equals(principal))throw new SQLException("fixture新boot的实际SQL主体不同","08007");
        }
        update(admin,"UPDATE __sql_fixture_owner SET normal_principal=? WHERE id=1 AND owner_nonce=?",principal.getBytes(StandardCharsets.UTF_8),nonce);unknown=false;
        String generation;long stamp,expectedEpoch,expectedCatalog;try(var s=admin.createStatement();var r=s.executeQuery("SELECT generation,writer_epoch,catalog_revision,writable FROM route_meta WHERE id=1")){if(!r.next()||r.getLong(2)<1||r.getLong(3)<1||r.getInt(4)!=1)throw new SQLException("fixture只能在明确可写meta注册新boot");generation=r.getString(1);expectedEpoch=r.getLong(2);expectedCatalog=r.getLong(3);}
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")){if(!r.next())throw new SQLException("缺数据库时刻");stamp=r.getLong(1);}
        var type=requestDescriptor();var writerType=type.findFieldByName("writer").getMessageType();byte[] key=random(16),instance=random(16),requestId=random(16);String deployment="fixture-"+UUID.randomUUID();
        var writer=DynamicMessage.newBuilder(writerType);set(writer,"writer_key",ByteString.copyFrom(key));set(writer,"generation",generation);set(writer,"instance_id",ByteString.copyFrom(instance));set(writer,"sql_principal",ByteString.copyFromUtf8(principal));enumeration(writer,"state","ACTIVE");
        var request=DynamicMessage.newBuilder(type);set(request,"request_id",ByteString.copyFrom(requestId));enumeration(request,"kind","REGISTER");set(request,"writer",writer.build());set(request,"expected_epoch",expectedEpoch);set(request,"expected_catalog_revision",expectedCatalog);set(request,"authority_schema",database);set(request,"protected_schemas",List.of(database));set(request,"deployment_digest",ByteString.copyFrom(options.physical()));set(request,"deployment_id",deployment);enumeration(request,"lock_primitive",options.primitive());set(request,"lock_qualification_sha256",ByteString.copyFrom(options.qualification()));
        byte[] digest=sha(canonical(request.build()));set(writer,"registration_digest",ByteString.copyFrom(digest));set(request,"writer",writer.build());byte[] original=canonical(request.build());
        admin.setAutoCommit(false);boolean committing=false;
        try{
            try(var q=admin.prepareStatement("SELECT writer_epoch,catalog_revision,writable FROM route_meta WHERE id=1 FOR UPDATE");var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=expectedEpoch||r.getLong(2)!=expectedCatalog||r.getInt(3)!=1)throw new SQLException("fixture fresh meta已变化");}
            update(admin,"INSERT INTO writer_registry(writer_key,generation,instance_id,sql_principal,registration_digest,state,registered_ms,retirement_request_id,retirement_digest,retired_ms) VALUES(?,?,?,?,?,1,?,?,?,0)",key,generation,instance,principal.getBytes(StandardCharsets.UTF_8),digest,stamp,new byte[0],new byte[0]);
            for(int slot=0;slot<64;slot++)update(admin,"INSERT INTO writer_slot(writer_key,slot,generation,writer_epoch,state,lock_touch) VALUES(?,?,?,?,1,0)",key,slot,generation,expectedEpoch);
            update(admin,"INSERT INTO writer_lifecycle_receipt(request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision) VALUES(?,1,?,?,?,?,?,?)",requestId,generation,digest,original,expectedEpoch,expectedEpoch,expectedCatalog+1);update(admin,"UPDATE route_meta SET catalog_revision=? WHERE id=1 AND catalog_revision=?",expectedCatalog+1,expectedCatalog);committing=true;unknown=true;admin.commit();unknown=false;admin.setAutoCommit(true);
        }catch(SQLException failure){if(committing){unknown=true;throw new SQLException("fixture REGISTER提交结局未知","08007");}try{admin.rollback();admin.setAutoCommit(true);}catch(SQLException cleanup){unknown=true;throw new SQLException("fixture REGISTER回滚结局未知","08007");}throw failure;}
        Map<String,Object> grant=new LinkedHashMap<>();grant.put("format","SQL_WRITER_BOOT_V1");grant.put("writerKey",b64(key));grant.put("instanceId",b64(instance));grant.put("generation",generation);grant.put("authoritySchema",database);grant.put("protectedSchemas",List.of(database));grant.put("sqlPrincipal",b64(principal.getBytes(StandardCharsets.UTF_8)));grant.put("registrationDigest",b64(digest));grant.put("deploymentId",deployment);grant.put("grantId",b64(random(16)));grant.put("grantedMs",Long.toUnsignedString(stamp));grant.put("permission","NORMAL");grant.put("physicalAuthoritySha256",b64(options.physical()));grant.put("lockPrimitive",options.primitive());grant.put("lockQualificationSha256",b64(options.qualification()));
        byte[] json=new ObjectMapper().writeValueAsBytes(grant);Path file=options.receipts().resolve(database+"-"+HexFormat.of().formatHex(random(8))+".grant.json");write(file,json);var environment=new HashMap<String,String>(System.getenv());environment.putAll(Map.of("MMORPG_SQL_WRITER_GRANT_FILE",file.toString(),"MMORPG_SQL_WRITER_GRANT_SHA256",HexFormat.of().formatHex(sha(json)),"MMORPG_SQL_WRITER_CONSUME_DIR",options.receipts().toString(),"MMORPG_SQL_DEPLOYMENT_ID",deployment,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256",HexFormat.of().formatHex(options.physical())));var nextBoot=WriterAuthority.consume(environment);
        try{
            if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(pendingAccount,65536),pendingAccountBytes))throw new SQLException("fixture新账号私有意图不同","08007");
            Files.delete(pendingAccount);
        }catch(Exception|Error failure){nextBoot.seal();unknown=true;throw failure;}
        boot=nextBoot;activeNormalAccount=account;
    }
    private final Map<String,DynamicMessage> sqlSeamFreezes=new HashMap<>();
    /** 仅释放本fixture已显式结束的SQL-seam种子并登记新boot；不证明P0-P9，也不换绑旧NORMAL池。 */
    public synchronized void releaseNormalFrozenSqlSeam(String operation)throws Exception{
        UUID uuid=UUID.fromString(operation);if(!uuid.toString().equals(operation)||uuid.equals(new UUID(0,0)))throw new IllegalArgumentException("SQL seam需要非零run");
        if(unknown||closed||!owned||boot==null)throw new SQLException("SQL seam释放夹具不可用");
        byte[] run=ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        boolean released=false;
        try(var admin=admin(database)){releaseSeededSqlSeamAndRegister(admin,run);released=true;}
        catch(Exception|Error failure){
            if(released){if(unpublishedOwner!=null)unpublishedOwner.failUnknown(failure);else{unknown=true;if(boot!=null)boot.seal();}}
            throw failure;
        }
    }
    /** 仅SQL域回归种子：不执行或证明Kafka/Redis/P0-P9；所有写只在本fixture双归属随机schema。 */
    public synchronized void seedNormalFrozenSqlSeam(String operation)throws Exception{
        UUID uuid=UUID.fromString(operation);if(!uuid.toString().equals(operation)||uuid.equals(new UUID(0,0)))throw new IllegalArgumentException("SQL seam需要非零run");
        byte[] run=ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        if(unknown||closed||!owned||boot==null)throw new SQLException("SQL seam夹具不可用");
        if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(ownerFile,65536),ownerBytes()))throw new SQLException("SQL seam私有owner不同");
        try(var admin=admin(database)){
            try(var q=admin.prepareStatement("SELECT owner_nonce,physical_sha FROM __sql_fixture_owner WHERE id=1");var r=q.executeQuery()){if(!r.next()||!MessageDigest.isEqual(nonce,r.getBytes(1))||!MessageDigest.isEqual(options.physical(),r.getBytes(2))||r.next())throw new SQLException("SQL seam数据库owner不同");}
            byte[] priorRun;int writable;try(var s=admin.createStatement();var r=s.executeQuery("SELECT writable,freeze_run_id FROM route_meta WHERE id=1")){if(!r.next())throw new SQLException("缺fixture meta");writable=r.getInt(1);priorRun=r.getBytes(2);}
            if(Arrays.equals(priorRun,run)){if(writable!=0||!sqlSeamFreezes.containsKey(operation))throw new SQLException("不能接管外部run");return;}
            if(priorRun.length!=0)releaseSeededSqlSeamAndRegister(admin,priorRun);
            byte[] key=boot.writerKey();admin.setAutoCommit(false);boolean committing=false;
            try{
                try(var q=admin.prepareStatement("SELECT slot,state FROM writer_slot WHERE writer_key=? ORDER BY slot FOR UPDATE")){q.setBytes(1,key);q.setQueryTimeout(5);try(var r=q.executeQuery()){int slot=0;while(r.next()){if(r.getInt(1)!=slot++||r.getInt(2)!=1)throw new SQLException("SQL seam原writer不是完整ACTIVE");}if(slot!=64)throw new SQLException("SQL seam缺slot");}}
                long epoch,revision;String generation;try(var q=admin.prepareStatement("SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id FROM route_meta WHERE id=1 FOR UPDATE");var r=q.executeQuery()){if(!r.next()||r.getInt(4)!=1||r.getBytes(5).length!=0)throw new SQLException("SQL seam当前meta不符");generation=r.getString(1);epoch=r.getLong(2);revision=r.getLong(3);}
                byte[] original;try(var q=admin.prepareStatement("SELECT request_proto FROM writer_lifecycle_receipt WHERE kind=1 AND request_digest=?")){q.setBytes(1,boot.registrationDigest());try(var r=q.executeQuery()){if(!r.next())throw new SQLException("SQL seam缺正式REGISTER");original=r.getBytes(1);if(r.next())throw new SQLException("REGISTER不唯一");}}
                var retire=DynamicMessage.parseFrom(requestDescriptor(),original).toBuilder();byte[] retireId=random(16);set(retire,"request_id",ByteString.copyFrom(retireId));enumeration(retire,"kind","RETIRE");set(retire,"expected_epoch",epoch);set(retire,"expected_catalog_revision",revision);set(retire,"maintenance_run_id",ByteString.copyFrom(run));byte[] retireBytes=canonical(retire.build()),retireDigest=sha(retireBytes);long stamp;
                try(var s=admin.createStatement();var r=s.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")){if(!r.next())throw new SQLException("缺SQL时刻");stamp=r.getLong(1);}
                try(var q=admin.prepareStatement("UPDATE writer_slot SET state=2 WHERE writer_key=? AND state=1")){q.setBytes(1,key);if(q.executeUpdate()!=64)throw new SQLException("SQL seam退役缺片");}
                update(admin,"UPDATE writer_registry SET state=2,retirement_request_id=?,retirement_digest=?,retired_ms=? WHERE writer_key=? AND state=1",retireId,retireDigest,stamp,key);
                update(admin,"INSERT INTO writer_lifecycle_receipt(request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision) VALUES(?,2,?,?,?,?,?,?)",retireId,generation,retireDigest,retireBytes,epoch,epoch,revision+1);
                var type=descriptor("SqlWriterMaintenanceReceipt");var plan=DynamicMessage.newBuilder(type.findNestedTypeByName("Plan"));set(plan,"physical_cluster_identity",ByteString.copyFrom(options.physical()));set(plan,"authority_schema",database);set(plan,"protected_schemas",List.of(database));set(plan,"active_writer_keys",List.of(ByteString.copyFrom(key)));set(plan,"before_epoch",epoch);set(plan,"catalog_revision",revision);set(plan,"run_id",ByteString.copyFrom(run));byte[] planBytes=canonical(plan.build()),manifest=sha(planBytes);
                var evidence=DynamicMessage.newBuilder(type.findNestedTypeByName("Evidence"));set(evidence,"operation_id",ByteString.copyFrom(run));set(evidence,"manifest_digest",ByteString.copyFrom(manifest));
                byte[] freezeId=random(16),ticketId=random(16);var binding=DynamicMessage.newBuilder(type.findNestedTypeByName("TicketEvidence"));set(binding,"ticket_id",ByteString.copyFrom(ticketId));set(binding,"authority_operation_id",ByteString.copyFrom(run));set(binding,"bound_request_id",ByteString.copyFrom(freezeId));set(evidence,"ticket",binding.build());
                var frozen=DynamicMessage.newBuilder(type);set(frozen,"request_id",ByteString.copyFrom(freezeId));set(frozen,"run_id",ByteString.copyFrom(run));set(frozen,"generation",generation);set(frozen,"before_epoch",epoch);set(frozen,"catalog_revision",revision+1);enumeration(frozen,"phase","FROZEN");set(frozen,"plan_proto",ByteString.copyFrom(planBytes));enumeration(frozen,"kind","FREEZE");enumeration(frozen,"run_phase","SQL_FROZEN");set(frozen,"attempt_revision",1L);set(frozen,"evidence",evidence.build());
                // 没有假冒application/非SQL观察：这是显式最小SQL-seam输入，完整控制验收不消费此种子。
                byte[] requestDigest=sha(canonical(frozen.build()));set(frozen,"request_digest",ByteString.copyFrom(requestDigest));set(frozen,"after_epoch",epoch+1);set(frozen,"committed_ms",stamp);byte[] receiptDigest=sha(canonical(frozen.build()));set(frozen,"receipt_digest",ByteString.copyFrom(receiptDigest));var receipt=frozen.build();
                update(admin,"INSERT INTO sql_writer_maintenance_receipt(request_id,request_digest,run_id,generation,before_epoch,after_epoch,catalog_revision,phase,plan_proto,committed_ms,kind,run_phase,attempt_revision,prior_receipt_id,prior_receipt_digest,evidence,receipt_digest) VALUES(?,?,?,?,?,?,?,1,?,?,1,7,1,?,?,?,?)",freezeId,requestDigest,run,generation,epoch,epoch+1,revision+1,planBytes,stamp,new byte[0],new byte[0],canonical(evidence.build()),receiptDigest);
                update(admin,"INSERT INTO sql_writer_drain_run(operation_id,request_digest,generation,attempt_revision,manifest_proto,manifest_digest,phase,latest_receipt_digest,frozen_request_id,updated_ms,latest_receipt_id) VALUES(?,?,?,1,?,?,7,?,?,?,?)",run,sha(planBytes),generation,planBytes,manifest,receiptDigest,freezeId,stamp,freezeId);
                update(admin,"INSERT INTO sql_writer_freeze_ticket(ticket_id,operation_id,attempt_revision,generation,manifest_digest,non_sql_receipt_digest,freeze_request_id,freeze_request_digest,not_before_ms,expires_ms,state,consumed_receipt_digest,revocation_digest,updated_ms,authorization_kind,grant_receipt_id,grant_receipt_digest) VALUES(?,?,1,?,?,?,?,?,?,?,2,?,?,?,1,?,?)",ticketId,run,generation,manifest,new byte[0],freezeId,requestDigest,stamp-1,stamp+60000,receiptDigest,new byte[0],stamp,new byte[0],new byte[0]);
                update(admin,"UPDATE route_meta SET writable=0,writer_epoch=?,catalog_revision=?,freeze_run_id=? WHERE id=1 AND writable=1 AND writer_epoch=? AND catalog_revision=?",epoch+1,revision+1,run,epoch,revision);
                committing=true;unknown=true;admin.commit();unknown=false;admin.setAutoCommit(true);boot.seal();sqlSeamFreezes.put(operation,receipt);
            }catch(Exception failure){if(committing){unknown=true;throw new SQLException("SQL seam提交未明，保留原件","08007");}try{admin.rollback();admin.setAutoCommit(true);}catch(SQLException cleanup){unknown=true;failure.addSuppressed(cleanup);}throw failure;}
        }
    }
    private static String uuidText(byte[] bytes)throws SQLException{if(bytes.length!=16)throw new SQLException("run长度非法");var value=ByteBuffer.wrap(bytes);return new UUID(value.getLong(),value.getLong()).toString();}
    private static Descriptors.Descriptor requestDescriptor()throws Exception{return descriptor("SqlWriterLifecycleReceipt").findNestedTypeByName("Request");}
    private static Descriptors.Descriptor descriptor(String type)throws Exception{
        try(var input=AuthoritySqlFixture.class.getResourceAsStream("/META-INF/sql-authority/route-authority.desc")){
            if(input==null)throw new SQLException("缺本仓正规descriptor");var set=DescriptorProtos.FileDescriptorSet.parseFrom(input);Map<String,DescriptorProtos.FileDescriptorProto> todo=new LinkedHashMap<>();Map<String,Descriptors.FileDescriptor> files=new HashMap<>();for(var file:set.getFileList())todo.put(file.getName(),file);
            while(!todo.isEmpty()){boolean changed=false;for(var file:new ArrayList<>(todo.values()))if(files.keySet().containsAll(file.getDependencyList())){files.put(file.getName(),Descriptors.FileDescriptor.buildFrom(file,file.getDependencyList().stream().map(files::get).toArray(Descriptors.FileDescriptor[]::new)));todo.remove(file.getName());changed=true;}if(!changed)throw new SQLException("descriptor导入不完整");}
            for(var file:files.values()){var receipt=file.findMessageTypeByName(type);if(receipt!=null)return receipt;}throw new SQLException("缺正式REGISTER消息");
        }
    }
    private static void set(DynamicMessage.Builder builder,String field,Object value)throws SQLException{var f=builder.getDescriptorForType().findFieldByName(field);if(f==null)throw new SQLException("正式字段缺失");builder.setField(f,value);}
    private static void enumeration(DynamicMessage.Builder builder,String field,String value)throws SQLException{var f=builder.getDescriptorForType().findFieldByName(field);if(f==null||f.getEnumType().findValueByName(value)==null)throw new SQLException("正式枚举缺失");builder.setField(f,f.getEnumType().findValueByName(value));}
    private static byte[] canonical(Message message)throws IOException{byte[] bytes=new byte[message.getSerializedSize()];var out=CodedOutputStream.newInstance(bytes);out.useDeterministicSerialization();message.writeTo(out);out.checkNoSpaceLeft();return bytes;}
    private static void update(Connection connection,String sql,Object... args)throws SQLException{try(var q=connection.prepareStatement(sql)){q.setQueryTimeout(5);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);if(q.executeUpdate()!=1)throw new SQLException("fixture原件写入不唯一");}}
    private static byte[] random(int size){byte[] bytes=new byte[size];new SecureRandom().nextBytes(bytes);return bytes;}private static byte[] sha(byte[] bytes)throws NoSuchAlgorithmException{return MessageDigest.getInstance("SHA-256").digest(bytes);}private static String b64(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}
    private static String name(String value){if(!value.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("标识符非法");return (char)96+value+(char)96;}private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    private byte[] ownerBytes(){return (database+"\n"+user+"\n"+HexFormat.of().formatHex(nonce)+"\n"+HexFormat.of().formatHex(options.physical())+"\n").getBytes(StandardCharsets.UTF_8);}
    private static void write(Path file,byte[] bytes)throws IOException{WriterAuthority.createPrivateControlFile(file,bytes);}

    @Override public synchronized void close()throws SQLException{
        if(ownerSchemaWorkActive)throw new SQLException("owner Schema工作中不能重入关闭","08007");
        requireNormalActorsTerminated();
        if(closed)return;if(boot!=null)boot.seal();if(unknown)throw new SQLException("fixture仍有UNKNOWN或缺归属标记，保留原件及对象","08007",fixtureUnknownCause);if(!owned)throw new SQLException("fixture仍有UNKNOWN或缺归属标记，保留原件及对象");
        try{
            if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(ownerFile,65536),ownerBytes()))throw new SQLException("fixture私有归属原件不同");
            try(var admin=admin(database);var q=admin.prepareStatement("SELECT owner_nonce,physical_sha,normal_principal FROM __sql_fixture_owner WHERE id=1");var r=q.executeQuery()){
                if(!r.next()||!MessageDigest.isEqual(nonce,r.getBytes(1))||!MessageDigest.isEqual(options.physical(),r.getBytes(2)))throw new SQLException("fixture schema归属不同");byte[] principal=r.getBytes(3);if(r.next())throw new SQLException("fixture归属不唯一");
                if(userCreated != !normalAccounts.isEmpty())throw new SQLException("fixture实际创建账号清单不符");
                if(userCreated&&!MessageDigest.isEqual(principal,normalAccounts.get(normalAccounts.size()-1).principal().getBytes(StandardCharsets.UTF_8)))throw new SQLException("fixture当前账号归属不同");
                for(var account:normalAccounts)try(var normal=DriverManager.getConnection(jdbcUrl(""),account.user(),account.password())){
                    if(!account.principal().equals(currentUser(normal)))throw new SQLException("fixture实际账号主体已变化");
                }
            }
            // 所有归属核验在先；仅删除本fixture实际CREATE的精确对象，不用IF EXISTS或仅凭前缀。
            try(var admin=admin("");var statement=admin.createStatement()){for(var account:normalAccounts){unknown=true;statement.execute("DROP USER "+literal(account.user())+"@'%'");unknown=false;}unknown=true;statement.execute("DROP DATABASE "+name(database));unknown=false;closed=true;}
        }catch(IOException invalid){throw new SQLException("fixture私有归属原件不可读，拒绝清理");}
    }
}
