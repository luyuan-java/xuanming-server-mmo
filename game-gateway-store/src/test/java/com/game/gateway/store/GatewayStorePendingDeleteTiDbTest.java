package com.game.gateway.store;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.player.PlayerRouteAuthority;
import com.game.common.sql.testing.AuthoritySqlFixture;
import java.math.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;

/** TiDB专用真实锁回归；3秒仅为harness成功观察门槛，底层JDBC保留NORMAL3/admin5原预算。 */
class GatewayStorePendingDeleteTiDbTest {
    private static final int ZONE=17;
    private static final String ACCOUNT="pending-delete-account",PARAMS="?connectTimeout=3000&socketTimeout=3000";
    private static final long NOW=1_800_000_000_000L;
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void 真TiDB_区服pendingDelete_两个生产writer沿围栏等待并唯一最后写()throws Exception{run(Kind.ZONE,false,1);}
    @Test void 真TiDB_白名单pendingDelete_两个生产writer等待自然键并唯一最后备注()throws Exception{run(Kind.WHITELIST,false,1);}
    @Test void 真TiDB_区服相同route前缀普通INSERT负控只容一个writer()throws Exception{run(Kind.ZONE,true,1);}
    @Test void 真TiDB_白名单复合主键普通INSERT五轮负控只容一份()throws Exception{run(Kind.WHITELIST,true,5);}
    private static void run(Kind kind,boolean plain,int rounds)throws Exception{
        for(int round=0;round<rounds;round++)try(var h=new Harness(kind)){h.compete(plain);}
    }
    private enum Kind{
        ZONE("zone_config",List.of("zone_id")),WHITELIST("zone_whitelist",List.of("zone_id","account"));
        final String table;final List<String> primary;Kind(String table,List<String> primary){this.table=table;this.primary=primary;}
    }
    private static ZoneRow zone(String name){return new ZoneRow(ZONE,name,0,5000,"",null,false,0,0,0);}
    private static String key(Kind k){return k==Kind.ZONE?"zone_id=?":"zone_id=? AND account=?";}
    private static void bindKey(PreparedStatement q,Kind k)throws SQLException{q.setInt(1,ZONE);if(k==Kind.WHITELIST)q.setString(2,ACCOUNT);}
    private static String decimal(String value)throws SQLException{
        if(value==null||!value.matches("[0-9]{1,20}"))throw new SQLException("owned标识格式未知","0A000");
        var n=new BigInteger(value);if(n.signum()<=0||n.bitLength()>64)throw new SQLException("owned标识范围未知","0A000");return n.toString();
    }
    private static void bindIds(PreparedStatement q,int offset,List<String> ids)throws SQLException{for(int i=0;i<ids.size();i++)q.setBigDecimal(offset+i,new BigDecimal(ids.get(i)));}
    private static String connectionId(Connection c,Budget b,int querySeconds)throws SQLException{
        try(var q=c.prepareStatement("SELECT CONNECTION_ID()")){b.check();q.setQueryTimeout(querySeconds);try(var r=q.executeQuery()){
            if(!r.next())throw new SQLException("缺owned连接标识");String id=decimal(r.getString(1));if(r.next())throw new SQLException("owned连接不唯一");b.check();return id;
        }}
    }
    /** 3秒仅为单调成功观察门槛；不承诺底层BEGIN、route64和commit在此期限终止。 */
    private static final class Budget{
        private volatile long until;
        void arm(){if(until!=0)throw new IllegalStateException("争用预算不能续时");until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);}
        boolean armed(){return until!=0;}
        long nanos()throws SQLTimeoutException{long n=until-System.nanoTime();if(until==0||n<=0||Thread.currentThread().isInterrupted())throw new SQLTimeoutException("争用截止或取消","HYT00");return n;}
        void check()throws SQLTimeoutException{nanos();}
        void pause()throws Exception{TimeUnit.NANOSECONDS.sleep(Math.min(nanos(),TimeUnit.MILLISECONDS.toNanos(5)));check();}
        long leftOrZero(){return until==0?0:Math.max(0,until-System.nanoTime());}
    }
    /** 固定真实AuthorityConnection，不包Connection；TM与MyBatis共用同一DataSource键。 */
    private static final class Pinned extends AbstractDataSource implements SmartDataSource,AutoCloseable{
        final Connection c;final Budget budget;private Thread thread;private boolean closed;
        Pinned(Connection c,Budget b){this.c=c;budget=b;}
        @Override public synchronized Connection getConnection()throws SQLException{
            if(closed)throw new SQLException("NORMAL固定会话已关闭");
            if(budget.armed()){if(thread==null)thread=Thread.currentThread();else if(thread!=Thread.currentThread())throw new SQLException("NORMAL会话禁止跨worker");budget.check();}if(c.getNetworkTimeout()!=3000)throw new SQLException("NORMAL原网络上限不为3秒","08007");return c;
        }
        @Override public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("固定NORMAL不能覆盖主体");}
        @Override public boolean shouldClose(Connection ignored){return false;}
        @Override public synchronized void close()throws SQLException{if(!closed){c.close();closed=true;}}
    }
    private record SqlError(String type,String state,int vendor,int suppressed){}
    private record ReadBack(long ordinal,String role,Object value){}
    private record Done(String role,Object value,boolean success,String failureType,List<SqlError> errors){}
    private record Edge(String waiter,String holder,String table,String lockKind,String keyIdentity){}
    private record KeyMatch(String lockKind,String keyIdentity){}
    private static final class Trace{
        final AtomicInteger attempts=new AtomicInteger();final List<SqlError> errors=new ArrayList<>();final Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        void add(Throwable failure){var q=new ArrayDeque<Throwable>();q.add(failure);while(!q.isEmpty()){
            Throwable t=q.removeFirst();if(!seen.add(t))continue;
            if(t instanceof SQLException s){errors.add(new SqlError(t.getClass().getName(),s.getSQLState(),s.getErrorCode(),s.getSuppressed().length));if(s.getNextException()!=null)q.add(s.getNextException());}
            if(t.getCause()!=null)q.add(t.getCause());Collections.addAll(q,t.getSuppressed());
        }}
    }
    private static SQLException safe(String stage,Throwable failure){SQLException sql=failure instanceof SQLException s?s:null;return new SQLException(stage+"；type="+failure.getClass().getSimpleName(),sql==null?"08007":sql.getSQLState(),sql==null?0:sql.getErrorCode());}
    private static GatewayStore store(DataSource ds,TransactionOperations tx)throws Exception{
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);var sessions=factory.getObject();if(sessions==null)throw new IllegalStateException("缺MyBatis工厂");
        sessions.getConfiguration().setDefaultStatementTimeout(3);sessions.getConfiguration().addMapper(GatewayStoreMapper.class);
        return new GatewayStore(new SqlSessionTemplate(sessions).getMapper(GatewayStoreMapper.class),tx,()->NOW,ds);
    }
    private static final class Worker{
        final String role;final Harness h;final Pinned source;final Trace trace=new Trace();final TransactionOperations tx;final GatewayStore store;
        Worker(String role,Harness h,Connection c)throws Exception{
            this.role=role;this.h=h;source=new Pinned(c,h.budget);var real=new TransactionTemplate(new DataSourceTransactionManager(source));real.setTimeout(3);
            tx=new TransactionOperations(){@Override public <T>T execute(TransactionCallback<T> action){
                trace.attempts.incrementAndGet();
                try{T value=real.execute(status->{Connection connection=DataSourceUtils.getConnection(source);
                    try{
                        h.budget.check();String id=connectionId(connection,h.budget,3),prior=h.sessions.putIfAbsent(role,id);
                        if(prior!=null&&!prior.equals(id))throw new SQLException("NORMAL登记被替换","08007");if(prior==null)h.registered.countDown();
                        T result=action.doInTransaction(status);h.readbacks.add(new ReadBack(h.order.incrementAndGet(),role,result));h.budget.check();h.budget.check();return result;
                    }catch(SQLException failure){throw new IllegalStateException("NORMAL观察截止或登记失败",failure);}finally{DataSourceUtils.releaseConnection(connection,source);}
                });if(!source.c.getAutoCommit())throw new SQLException("Spring事务清理未复原","08007");h.budget.check();return value;
                }catch(SQLException failure){trace.add(failure);h.observe(failure);throw new IllegalStateException("NORMAL截止",failure);}
                catch(RuntimeException|Error failure){trace.add(failure);h.observe(failure);throw failure;}
            }};
            try{store=store(source,tx);}catch(Exception|Error failure){h.observe(failure);try{source.close();}catch(Exception|Error cleanup){h.retain(cleanup);if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;}
        }
        Done execute(boolean plain){Object value=null;Throwable failure=null;
            try{if(!plain)value=h.kind==Kind.ZONE?store.upsertZone(zone(role)):store.addWhitelist(ZONE,ACCOUNT,role);
                else value=tx.execute(status->{Connection c=DataSourceUtils.getConnection(source);try{
                    if(h.kind==Kind.ZONE)PlayerRouteAuthority.lockConfigurationZone(c,ZONE,3);
                    String sql=h.kind==Kind.ZONE?"INSERT INTO zone_config(zone_id,name,created_at,updated_at) VALUES(?,?,?,?)":"INSERT INTO zone_whitelist(zone_id,account,note) VALUES(?,?,?)";
                    try(var insert=c.prepareStatement(sql)){h.budget.check();insert.setQueryTimeout(3);insert.setInt(1,ZONE);if(h.kind==Kind.ZONE){insert.setString(2,role);insert.setLong(3,NOW);insert.setLong(4,NOW);}else{insert.setString(2,ACCOUNT);insert.setString(3,role);}assertEquals(1,insert.executeUpdate());}
                    return h.kind==Kind.ZONE?store.zone(ZONE).orElseThrow():store.whitelist(ZONE).stream().filter(r->r.account().equals(ACCOUNT)).findFirst().orElseThrow();
                }catch(SQLException e){throw new IllegalStateException("普通INSERT负控失败",e);}finally{DataSourceUtils.releaseConnection(c,source);}});
            }catch(Exception|Error caught){failure=caught;trace.add(caught);h.observe(caught);}
            try{source.close();}catch(Exception|Error cleanup){trace.add(cleanup);h.retain(cleanup);if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
            return new Done(role,value,failure==null,failure==null?null:failure.getClass().getName(),List.copyOf(trace.errors));
        }
    }
    private static void raise(Throwable failure)throws Exception{if(failure instanceof Exception e)throw e;if(failure instanceof Error e)throw e;}
    private static Throwable combine(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    private static final class Harness implements AutoCloseable{
        final Kind kind;final Budget budget=new Budget();final AtomicBoolean unsafe=new AtomicBoolean();
        final ConcurrentMap<String,String> sessions=new ConcurrentHashMap<>();final CountDownLatch registered=new CountDownLatch(2);
        final Queue<ReadBack> readbacks=new ConcurrentLinkedQueue<>();final AtomicLong order=new AtomicLong();
        final Map<String,Shape> shapes=new HashMap<>();
        AuthoritySqlFixture fixture;Connection holder,observer;Pinned inspectionSource;GatewayStore inspection;Worker first,second;
        boolean holderReleased=true,workersTerminated=true;ExecutorService threads;
        /** unsafe只汇总本harness；清理资格由同一fixture的不可逆UNKNOWN和实际executor决定。 */
        void retain(Throwable failure){unsafe.set(true);if(fixture!=null)fixture.retainUnknownActorOutcome(failure);}
        void observe(Throwable failure){if(AuthoritySqlFixture.isUnknownFixtureOutcome(failure))retain(failure);}
        Harness(Kind kind)throws Exception{
            this.kind=kind;
            try{
                String root=System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));if(root==null||root.isBlank())throw new IllegalStateException("缺真实Gateway TiDB配置");
                var options=AuthoritySqlFixture.Options.fromEnvironment(root,System.getProperty("game.it.mysql.user","root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),"jgw_pending",false,List.of(ZONE));
                try(var lease=AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(options)){
                    lease.withSchema((c,principal,database)->{new ResourceDatabasePopulator(new ClassPathResource("db/game-gateway-schema.sql")).populate(c);return null;});fixture=lease.publish();
                }
                DataSource normal=fixture.normalDataSource(PARAMS);var seedTx=new TransactionTemplate(new DataSourceTransactionManager(normal));seedTx.setTimeout(3);
                GatewayStore seeder=store(normal,seedTx);if(kind==Kind.ZONE)seeder.upsertZone(zone("seed"));else seeder.addWhitelist(ZONE,ACCOUNT,"seed");
                holder=fixture.schemaConnection();observer=fixture.schemaConnection();assertEquals(fixture.database(),holder.getCatalog());assertEquals(fixture.database(),observer.getCatalog());
                shapes.put(kind.table,Shape.read(observer,fixture.database(),kind.table,kind.primary));
                if(kind==Kind.ZONE)shapes.put("zone_merge_fence",Shape.read(observer,fixture.database(),"zone_merge_fence",List.of("zone_id","shard")));
                inspectionSource=new Pinned(normal.getConnection(),budget);inspection=store(inspectionSource,new TransactionTemplate(new DataSourceTransactionManager(inspectionSource)));
                first=new Worker("writer1",this,normal.getConnection());second=new Worker("writer2",this,normal.getConnection());
            }catch(Exception|Error failure){observe(failure);try{close();}catch(Exception|Error cleanup){retain(cleanup);if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;}
        }
        void delete(Budget active)throws SQLException{
            holder.setAutoCommit(false);holderReleased=false;
            try(var q=holder.prepareStatement("DELETE FROM "+kind.table+" WHERE "+key(kind))){active.check();q.setQueryTimeout(5);bindKey(q,kind);assertEquals(1,q.executeUpdate());active.check();}
        }
        void compete(boolean plain)throws Exception{
            Throwable failure=null;Future<Done> a=null,b=null;budget.arm();
            try{
                delete(budget);sessions.put("holder",connectionId(holder,budget,5));threads=Executors.newFixedThreadPool(2);workersTerminated=false;fixture.trackNormalActors(threads);
                a=threads.submit(()->first.execute(plain));b=threads.submit(()->second.execute(plain));
                assertTrue(registered.await(budget.nanos(),TimeUnit.NANOSECONDS),"两个owned NORMAL事务必须登记");
                List<Edge> observed=awaitGraph(a,b);
                System.out.println("gateway_pending_owned_graph kind="+kind+" plain="+plain+" consistent_samples=2 edges="+observed);
                budget.check();holder.commit();holderReleased=true;budget.check();
                Done left=a.get(budget.nanos(),TimeUnit.NANOSECONDS),right=b.get(budget.nanos(),TimeUnit.NANOSECONDS);budget.check();
                assertEquals(1,first.trace.attempts.get());assertEquals(1,second.trace.attempts.get());
                if(!plain){
                    assertTrue(left.success(),()->"writer1失败 type="+left.failureType()+" SQL="+left.errors());assertTrue(right.success(),()->"writer2失败 type="+right.failureType()+" SQL="+right.errors());
                    assertEquals(List.of(),left.errors());assertEquals(List.of(),right.errors());assertOwnValue(left);assertOwnValue(right);
                }else{
                    assertEquals(1,(left.success()?1:0)+(right.success()?1:0));Done winner=left.success()?left:right,loser=left.success()?right:left;
                    assertOwnValue(winner);assertEquals(List.of(),winner.errors());assertEquals(1,loser.errors().size());SqlError duplicate=loser.errors().get(0);
                    assertEquals("23000",duplicate.state());assertEquals(1062,duplicate.vendor());assertEquals(0,duplicate.suppressed());assertTrue(duplicate.type().endsWith("SQLIntegrityConstraintViolationException"));
                }
                List<ReadBack> completed=readbacks.stream().sorted(Comparator.comparingLong(ReadBack::ordinal)).toList();assertEquals(plain?1:2,completed.size());Object finalRow;
                if(kind==Kind.ZONE){assertEquals(1,inspection.zones().size());finalRow=inspection.zone(ZONE).orElseThrow();assertNotEquals("seed",((ZoneRow)finalRow).name());}
                else{List<WhitelistRow> rows=inspection.whitelist(ZONE);assertEquals(1,rows.size());finalRow=rows.get(0);assertNotEquals("seed",((WhitelistRow)finalRow).note());}
                assertEquals(completed.get(completed.size()-1).value(),finalRow);budget.check();
            }catch(Exception|Error caught){failure=caught;observe(caught);}
            finally{
                try{if(!holderReleased){holder.rollback();holderReleased=true;}}catch(Exception|Error e){retain(e);failure=combine(failure,safe("释放DELETE holder未确认",e));}
                for(Future<Done> actor:Arrays.asList(a,b))if(actor!=null)try{actor.cancel(true);}catch(Exception|Error e){retain(e);failure=combine(failure,e);}
                if(threads!=null)try{threads.shutdownNow();workersTerminated=threads.isTerminated()||threads.awaitTermination(budget.leftOrZero(),TimeUnit.NANOSECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();retain(e);failure=combine(failure,e);}catch(Exception|Error e){retain(e);failure=combine(failure,e);}
                if(!workersTerminated){var pending=new AssertionError("争用截止内worker未终结；保留owned原件，不宣称清理完成");retain(pending);failure=combine(failure,pending);}
            }
            raise(failure);
        }
        void assertOwnValue(Done done){if(kind==Kind.ZONE)assertEquals(done.role(),((ZoneRow)done.value()).name());else assertEquals(done.role(),((WhitelistRow)done.value()).note());}
        List<Edge> awaitGraph(Future<Done> a,Future<Done> b)throws Exception{
            Map<String,String> priorTransactions=null;List<Edge> priorEdges=null;
            while(true){
                budget.check();if(a.isDone()||b.isDone())throw new AssertionError("writer在两waiter真实证据前结束");Map<String,String> before=transactions();
                if(before.size()==3){List<Edge> edges=edges(before);Map<String,String> after=transactions();
                    if(before.equals(after)&&validGraph(edges)){if(before.equals(priorTransactions)&&edges.equals(priorEdges))return edges;priorTransactions=before;priorEdges=edges;}
                    else{priorTransactions=null;priorEdges=null;}
                }else{priorTransactions=null;priorEdges=null;}
                budget.pause();
            }
        }
        Map<String,String> transactions()throws SQLException{
            List<String> roles=List.of("holder","writer1","writer2");var bySession=new HashMap<String,String>();
            for(String role:roles){String id=sessions.get(role);if(id==null)throw new SQLException("owned连接未登记");if(bySession.put(id,role)!=null)throw new SQLException("owned连接重复");}
            Map<String,String> result=new TreeMap<>();
            try(var q=observer.prepareStatement("SELECT ID,SESSION_ID,STATE FROM information_schema.TIDB_TRX WHERE SESSION_ID IN (?,?,?)")){
                budget.check();q.setQueryTimeout(5);bindIds(q,1,roles.stream().map(sessions::get).toList());
                try(var r=q.executeQuery()){while(r.next()){
                    String role=bySession.get(decimal(r.getString("SESSION_ID")));if(role==null)throw new SQLException("事务视图返回非owned连接","08007");
                    String id=decimal(r.getString("ID"));if(result.put(role,id)!=null)throw new SQLException("同owned连接事务不唯一","08007");if(r.getString("STATE")==null)throw new SQLException("TiDB事务状态缺失","0A000");
                }}budget.check();return result;
            }catch(SQLException e){observe(e);throw safe("owned TIDB_TRX观测未就绪",e);}
        }
        List<Edge> edges(Map<String,String> transactions)throws Exception{
            var byTransaction=new HashMap<String,String>();for(var entry:transactions.entrySet())if(byTransaction.put(entry.getValue(),entry.getKey())!=null)throw new SQLException("owned事务标识重复","08007");
            List<String> ids=List.of(transactions.get("holder"),transactions.get("writer1"),transactions.get("writer2"));List<Edge> result=new ArrayList<>();
            try(var q=observer.prepareStatement("SELECT TRX_ID,CURRENT_HOLDING_TRX_ID,KEY_INFO FROM information_schema.DATA_LOCK_WAITS WHERE TRX_ID IN (?,?,?) AND CURRENT_HOLDING_TRX_ID IN (?,?,?)")){
                budget.check();q.setQueryTimeout(5);bindIds(q,1,ids);bindIds(q,4,ids);
                try(var r=q.executeQuery()){while(r.next()){
                    String waiter=byTransaction.get(decimal(r.getString("TRX_ID"))),holding=byTransaction.get(decimal(r.getString("CURRENT_HOLDING_TRX_ID")));
                    if(waiter==null||holding==null||waiter.equals(holding))throw new SQLException("等待视图返回非owned边","08007");
                    String raw=r.getString("KEY_INFO");if(raw==null||raw.length()>16384)throw new SQLException("KEY_INFO形状未知","0A000");
                    JsonNode info;try{info=JSON.readTree(raw);}catch(Exception invalid){throw new SQLException("KEY_INFO不是已校准JSON","0A000");}
                    if(!fixture.database().equals(info.path("db_name").asText()))throw new SQLException("owned边数据库不同","08007");
                    String table=info.path("table_name").asText();Shape shape=shapes.get(table);if(shape==null)throw new SQLException("等待边不在owned正式目标或route前缀","08007");
                    KeyMatch matched=shape.validate(info);result.add(new Edge(waiter,holding,table,matched.lockKind(),matched.keyIdentity()));if(result.size()>2)throw new SQLException("owned边超过本三事务边界","08007");
                }}budget.check();
            }catch(SQLException e){observe(e);throw safe("owned DATA_LOCK_WAITS观测未就绪",e);}
            result.sort(Comparator.comparing(Edge::waiter).thenComparing(Edge::holder).thenComparing(Edge::table).thenComparing(Edge::keyIdentity));return List.copyOf(result);
        }
        boolean validGraph(List<Edge> edges){
            if(edges.size()!=2||edges.stream().anyMatch(e->e.waiter().equals("holder")))return false;
            if(!edges.stream().map(Edge::waiter).collect(java.util.stream.Collectors.toSet()).equals(Set.of("writer1","writer2")))return false;
            if(kind==Kind.WHITELIST){
                if(edges.stream().anyMatch(e->!e.table().equals(kind.table)))return false;
                for(String writer:List.of("writer1","writer2")){String current=writer;Set<String> seen=new HashSet<>();while(!current.equals("holder")){if(!seen.add(current))return false;String node=current;Edge edge=edges.stream().filter(e->e.waiter().equals(node)).findFirst().orElse(null);if(edge==null)return false;current=edge.holder();}}return true;
            }
            Edge business=edges.stream().filter(e->e.holder().equals("holder")&&e.table().equals("zone_config")).findFirst().orElse(null);
            return business!=null&&edges.stream().anyMatch(e->!e.waiter().equals(business.waiter())&&e.holder().equals(business.waiter())&&e.table().equals("zone_merge_fence"));
        }
        @Override public void close()throws Exception{
            if(!workersTerminated)retain(new SQLException("normal actor尚未确认终结","08007"));
            Throwable failure=null;
            for(Connection c:new Connection[]{holder,observer})if(c!=null){try{c.setNetworkTimeout(Runnable::run,5000);if(c.getNetworkTimeout()!=5000)throw new SQLException("owner清理期限恢复未确认","08007");if(!c.getAutoCommit())c.rollback();}catch(Exception|Error e){retain(e);failure=combine(failure,safe("owner回滚未确认",e));}finally{try{c.close();}catch(Exception|Error e){retain(e);failure=combine(failure,safe("owner关闭未确认",e));}}}
            for(Pinned source:new Pinned[]{inspectionSource,workersTerminated&&first!=null?first.source:null,workersTerminated&&second!=null?second.source:null})if(source!=null)try{source.close();}catch(Exception|Error e){retain(e);failure=combine(failure,safe("NORMAL关闭未确认",e));}
            if(fixture!=null){
                try{fixture.requireNormalActorsTerminated();}catch(Exception|Error e){retain(e);failure=combine(failure,safe("normal actor终结未确认",e));}
                if(unsafe.get())System.out.println("gateway_pending_owned_cleanup retained=true outcome=UNKNOWN");
                else try{fixture.close();}catch(Exception|Error e){retain(e);failure=combine(failure,safe("精确fixture清理未确认",e));}
            }
            if(unsafe.get())failure=combine(failure,new SQLException("SQL或worker结局未明，保留owned原件供合格helper核验","08007"));raise(failure);
        }
    }
    /** v8.5.8 keydecoder：只接受本owned正式PK的精确int/brace或PRIMARY string-array。 */
    private record Shape(String table,List<String> primary,boolean clustered,Set<List<String>> tuples,
                         Map<String,List<String>> integerHandles,Map<String,List<String>> commonHandles){
        static Shape read(Connection owner,String database,String table,List<String> expected)throws Exception{
            Map<Short,String> primary=new TreeMap<>();try(var r=owner.getMetaData().getPrimaryKeys(database,null,table)){while(r.next())primary.put(r.getShort("KEY_SEQ"),r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}assertEquals(expected,List.copyOf(primary.values()));
            String ddl;try(var q=owner.createStatement()){q.setQueryTimeout(5);try(var r=q.executeQuery("SHOW CREATE TABLE `"+database+"`.`"+table+"`")){if(!r.next())throw new SQLException("缺owned表形状");ddl=r.getString(2);if(r.next())throw new SQLException("owned表形状不唯一");}}
            String upper=ddl.toUpperCase(Locale.ROOT);boolean clustered;
            if(upper.contains("NONCLUSTERED"))clustered=false;else if(upper.contains("CLUSTERED"))clustered=true;else throw new SQLException("TiDB clustered形状尚未校准","0A000");
            Set<List<String>> tuples=new HashSet<>();
            if(table.equals("zone_config"))tuples.add(List.of(Integer.toString(ZONE)));
            else if(table.equals("zone_whitelist"))tuples.add(List.of(Integer.toString(ZONE),ACCOUNT));
            else if(table.equals("zone_merge_fence"))for(int shard=0;shard<64;shard++)tuples.add(List.of(Integer.toString(ZONE),Integer.toString(shard)));
            else throw new SQLException("不是本fixture正式目标表","08007");
            Map<String,List<String>> ints=new HashMap<>(),common=new HashMap<>();
            if(clustered){
                if(expected.size()==1)for(List<String> tuple:tuples)ints.put(tuple.get(0),tuple);
                else for(List<String> tuple:tuples)common.put("{"+String.join(", ",tuple)+"}",tuple);
            }else{
                String predicate=table.equals("zone_whitelist")?"zone_id=? AND account=?":"zone_id=?";
                try(var q=owner.prepareStatement("SELECT _tidb_rowid,"+String.join(",",expected)+" FROM "+table+" WHERE "+predicate)){
                    q.setQueryTimeout(5);q.setInt(1,ZONE);if(table.equals("zone_whitelist"))q.setString(2,ACCOUNT);
                    try(var r=q.executeQuery()){while(r.next()){
                        String handle=integer(r.getString(1));List<String> tuple=new ArrayList<>();for(int i=0;i<expected.size();i++){String value=r.getString(2+i);if(value==null)throw new SQLException("owned PK为空","08007");tuple.add(value);}tuple=List.copyOf(tuple);
                        if(!tuples.contains(tuple)||ints.put(handle,tuple)!=null)throw new SQLException("rowid未精确关联owned正式PK","08007");
                    }}
                }
                if(ints.size()!=tuples.size()||!new HashSet<>(ints.values()).equals(tuples))throw new SQLException("owned正式PK映射不完整","08007");
            }
            return new Shape(table,List.copyOf(expected),clustered,Set.copyOf(tuples),Map.copyOf(ints),Map.copyOf(common));
        }
        private static String integer(String value)throws SQLException{
            if(value==null||!value.matches("-?(0|[1-9][0-9]{0,18})"))throw new SQLException("int handle不是已核十进制","0A000");
            try{return Long.toString(Long.parseLong(value));}catch(NumberFormatException invalid){throw new SQLException("int handle越界","0A000");}
        }
        private static String identity(JsonNode info,String table,List<String> tuple)throws SQLException{
            String tableId=decimal(info.path("table_id").asText());StringBuilder value=new StringBuilder(tableId).append('|').append(table).append('|');
            for(String column:tuple)value.append(column.length()).append(':').append(column).append('|');
            try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("缺标准SHA-256",impossible);}
        }
        KeyMatch validate(JsonNode info)throws SQLException{
            if(info.path("partition_handle").asBoolean(false)||!info.path("partition_name").asText().isEmpty()||info.path("partition_id").asLong(0)!=0)
                throw new SQLException("正式非分区PK不接受partition handle","08007");
            JsonNode index=info.get("index_name");
            if(index!=null&&!index.isNull()&&!index.asText().isEmpty()){
                if(!index.isTextual()||!index.textValue().equals("PRIMARY"))throw new SQLException("等待不是owned正式PRIMARY索引","08007");
                if(!info.path("handle_type").asText().isEmpty()||!info.path("handle_value").asText().isEmpty())throw new SQLException("index与record形状混淆","0A000");
                JsonNode values=info.get("index_values");if(values==null||!values.isArray()||values.size()!=primary.size())throw new SQLException("PRIMARY index_values列数未知","0A000");
                List<String> tuple=new ArrayList<>();for(JsonNode value:values){if(!value.isTextual())throw new SQLException("v8.5.8 index_values不是string数组","0A000");tuple.add(value.textValue());}tuple=List.copyOf(tuple);
                if(!tuples.contains(tuple))throw new SQLException("PRIMARY值不是本owned自然键或64片","08007");
                return new KeyMatch("PRIMARY_INDEX",identity(info,table,tuple));
            }
            if(info.hasNonNull("index_values"))throw new SQLException("record不应携带index_values","0A000");
            JsonNode value=info.get("handle_value");if(value==null||!value.isTextual())throw new SQLException("v8.5.8 handle_value不是string","0A000");
            List<String> tuple;String type=info.path("handle_type").asText();
            if(type.equals("int")){
                tuple=integerHandles.get(integer(value.textValue()));if(tuple==null)throw new SQLException("int handle不是本owned正式PK映射","08007");
                return new KeyMatch(clustered?"CLUSTERED_PRIMARY_RECORD":"OWNED_PK_MAPPED_RECORD",identity(info,table,tuple));
            }
            if(type.equals("common")&&clustered){
                tuple=commonHandles.get(value.textValue());if(tuple==null)throw new SQLException("common handle不是v8.5.8本owned精确brace元组","08007");
                return new KeyMatch("CLUSTERED_PRIMARY_COMMON_RECORD",identity(info,table,tuple));
            }
            throw new SQLException("KEY_INFO不是已核owned正式PK","0A000");
        }
    }
}
