package com.game.data.tools.playerbundle;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.NamespaceDatabaseCheck;
import com.game.discovery.namespace.GameNamespaceBinder;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.player.store.PlayerTradeStore;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 本机受控维护：默认只计划，只有完整计划SHA及同一目标的显式apply才会提交。
 * SQL业务判断全部委托PlayerTradeStore，不碰Redis、不伪造Scene ACK或APPLIED。
 */
public final class RecoverTradeAdmission {
    private static final String SCHEMA="player-trade-open-recovery-plan-v2";
    private static final Duration BUDGET=Duration.ofSeconds(10);
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private RecoverTradeAdmission() {}

    record Plan(String schema,String namespace,String instance,String database,PlayerTradeStore.RecoveryPlan original) {}
    record Applied(String schema,String planSha256,String namespace,String instance,String database,
                   PlayerTradeStore.RecoveryReceipt receipt) {}
    record Args(boolean apply,Path out,Path plan,String sha,String database,String instance,
                PlayerTradeStore.RecoveryRequest request) {}

    /** 连接接缝只隔离目标/会话与生命周期；SQL提交仍使用生产Store。 */
    interface Connections {
        DataSource open(String url,String user,String password) throws Exception;
        default String identity(Connection connection) { return InstanceIdentity.mysql(connection); }
        default void verify(Connection connection) throws Exception { StrongTiDbConnectionPolicy.verify(connection,true); }
        default void close(DataSource source) throws Exception { if(source instanceof AutoCloseable closeable)closeable.close(); }
    }
    private static final Connections REAL=new Connections() {
        public DataSource open(String url,String user,String password) throws Exception {
            var pool=new DruidDataSource();
            try {
                pool.setUrl(url);pool.setUsername(user);pool.setPassword(password);
                pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(1);pool.setMaxWait(3000);
                pool.setConnectTimeout(3000);pool.setSocketTimeout(10_000);
                new StrongTiDbConnectionPolicy("旧OPEN维护恢复",true).postProcessBeforeInitialization(pool,"recovery");
                pool.init();return pool;
            } catch(Exception | Error failure) { pool.close();throw failure; }
        }
    };
    public static void main(String[] args) {
        System.exit(run(List.of(args),System.getenv(),System.getProperties(),System.out,System.err,REAL,System::currentTimeMillis));
    }

    static int run(List<String> argv,Map<String,String> env,Map<?,?> properties,PrintStream out,PrintStream err,
                   Connections connections,LongSupplier millis) {
        DataSource source=null;
        try {
            Args args=parse(argv);
            if(PlayerBundleExport.checkOutputPath(args.out())!=null)throw new IllegalArgumentException();
            String url=env.get("GAME_TRADE_RECOVERY_JDBC"),user=env.get("GAME_MYSQL_USER");
            // URL只指本地库；秘密一律取独立环境，超时由本工具固定，避免驱动错误日志带出URL凭证。
            if(!localJdbcUrl(url) || user==null || user.isBlank())
                throw new IllegalArgumentException();
            var namespace=GameNamespaceBinder.bind(properties,env).namespace();
            Plan approved=null;
            if(args.apply()) {
                if(Files.isSymbolicLink(args.plan()) || Files.size(args.plan())>256*1024)throw new IllegalArgumentException();
                byte[] bytes=Files.readAllBytes(args.plan());
                if(!sha(bytes).equals(args.sha()))throw new IllegalArgumentException();
                approved=JSON.readValue(bytes,Plan.class);
                if(!SCHEMA.equals(approved.schema()) || !namespace.value().equals(approved.namespace())
                        || !args.instance().equals(approved.instance()) || !args.database().equals(approved.database())
                        || approved.original()==null || !args.database().equals(approved.original().database()))
                    throw new IllegalArgumentException();
            }
            source=connections.open(url,user,env.getOrDefault("GAME_MYSQL_PASSWORD",""));
            try(var connection=source.getConnection()) {
                connections.verify(connection);
                if(NamespaceDatabaseCheck.verify(connection,args.database(),namespace,"--expect-database")!=null
                        || !args.instance().equals(connections.identity(connection)))throw new SecurityException();
            }
            var store=new PlayerTradeStore(source,new DataSourceTransactionManager(source),millis,5,
                    PlayerTradeStore.sqlFreezeVerifier());
            byte[] result;
            if(args.apply()) {
                var receipt=store.applyOpenRecovery(approved.original(),BUDGET);
                result=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Applied(
                        "player-trade-open-recovery-applied-v2",args.sha(),namespace.value(),args.instance(),args.database(),receipt));
            } else {
                var original=store.planOpenRecovery(args.request(),BUDGET);
                if(!args.database().equals(original.database()))throw new SecurityException();
                result=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Plan(
                        SCHEMA,namespace.value(),args.instance(),args.database(),original));
            }
            connections.close(source);source=null;
            PlayerBundleExport.writeOwnerOnly(args.out(),result);
            out.println(args.apply()?"旧OPEN已按原件退休；结果不是APPLIED证明":"仅已生成只读计划；未退休OPEN、未清交易锁");
            out.println("artifact_sha256="+sha(result));return 0;
        } catch(Exception failure) {
            // 只输出受控分类；数据库异常可能含SQL、连接串或口令，禁止输出message/stack。
            err.println("旧OPEN恢复未完成，保留原计划并只按同原件重查；异常类型="+failure.getClass().getSimpleName());return 1;
        } finally {
            if(source!=null)try{connections.close(source);}catch(Exception failure){err.println("连接关闭失败："+failure.getClass().getSimpleName());}
        }
    }
    static Args parse(List<String> argv) {
        boolean apply=false;var values=new HashMap<String,String>();
        for(int i=0;i<argv.size();i++) {
            String key=argv.get(i);
            if(key.equals("--apply")) { if(apply)throw new IllegalArgumentException();apply=true;continue; }
            if(!Set.of("--out","--plan","--plan-sha256","--expect-database","--expect-instance","--recovery-id",
                    "--listing-id","--player-id","--seller-account","--owner-revision","--old-epoch","--source-instance").contains(key)
                    || i+1==argv.size() || values.put(key,argv.get(++i))!=null)throw new IllegalArgumentException();
        }
        String database=required(values,"--expect-database"),instance=required(values,"--expect-instance");
        if(!database.matches("[A-Za-z0-9_]{1,64}") || instance.isBlank() || instance.length()>1024)throw new IllegalArgumentException();
        Path out=Path.of(required(values,"--out"));
        if(apply) {
            if(values.size()!=5)throw new IllegalArgumentException();
            String sha=required(values,"--plan-sha256").toLowerCase(java.util.Locale.ROOT);
            if(!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            return new Args(true,out,Path.of(required(values,"--plan")),sha,database,instance,null);
        }
        if(values.size()!=10)throw new IllegalArgumentException();
        var freeze=new PlayerTradeStore.FreezeRequest(number(values,"--listing-id"),number(values,"--player-id"),
                required(values,"--seller-account"),number(values,"--owner-revision"));
        return new Args(false,out,null,null,database,instance,new PlayerTradeStore.RecoveryRequest(
                required(values,"--recovery-id"),freeze,number(values,"--old-epoch"),required(values,"--source-instance")));
    }
    /** URL只承载端点和库名；不让URL凭证/驱动参数进入连接池错误日志。 */
    private static boolean localJdbcUrl(String url) {
        if(url==null || !url.startsWith("jdbc:mysql://") || LocalTargets.checkJdbcUrl(url,Set.of())!=null)return false;
        var target=java.net.URI.create(url.substring("jdbc:".length()));
        return target.getRawUserInfo()==null && target.getRawQuery()==null && target.getRawFragment()==null
                && target.getRawPath()!=null && target.getRawPath().matches("/[A-Za-z0-9_]{1,64}");
    }
    private static String required(Map<String,String> values,String key) {
        String value=values.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException();return value;
    }
    private static long number(Map<String,String> values,String key) {
        long value=Long.parseLong(required(values,key));if(value<=0)throw new IllegalArgumentException();return value;
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
