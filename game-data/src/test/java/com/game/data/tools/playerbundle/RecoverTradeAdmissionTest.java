package com.game.data.tools.playerbundle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.testing.DataSqlFixture;
import com.game.player.store.PlayerRow;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerTradeStore;
import com.game.player.store.state.PlayerState;
import com.game.proto.common.base.RoleAdmissionIdentity;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** CLI专用维护目标/命名空间/计划原件门禁；成功路径仍调用正式SQL Store。 */
class RecoverTradeAdmissionTest {
    private static final String ID="10000000-0000-0000-0000-000000000001";
    private static final String INSTANCE="fixture-tidb-instance";
    private static final String NONCE="1234567890abcdef1234567890abcdef";
    private static final Duration BUDGET=Duration.ofSeconds(5);
    private final ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
    private static List<String> plan(Path out,String database) {
        return List.of("--out",out.toString(),"--expect-database",database,"--expect-instance",INSTANCE,
                "--maintenance-target-nonce",NONCE,"--zone-id","7",
                "--recovery-id",ID,"--listing-id","101","--player-id","1","--seller-account","seller",
                "--owner-revision","1","--old-epoch","1","--source-instance","old-source");
    }
    private static List<String> apply(Path out,Path plan,String sha,String database) {
        return List.of("--apply","--out",out.toString(),"--plan",plan.toString(),"--plan-sha256",sha,
                "--expect-database",database,"--expect-instance",INSTANCE,"--maintenance-target-nonce",NONCE,"--zone-id","7");
    }
    private static Map<String,String> env() {
        return env("game");
    }
    private static Map<String,String> env(String database) {
        return Map.of("GAME_TRADE_RECOVERY_JDBC","jdbc:mysql://127.0.0.1:24000/"+database,"GAME_MYSQL_USER","fixture",
                "GAME_MYSQL_PASSWORD","must-not-appear-in-output","GAME_NAMESPACE","game");
    }
    private int run(List<String> args,Map<String,String> env,RecoverTradeAdmission.Connections connection) {
        return RecoverTradeAdmission.run(args,env,Map.of(),new PrintStream(out),new PrintStream(err),connection,()->1_000_000L);
    }

    @Test void 默认只计划_apply必须显式完整SHA_重复未知参数均拒绝(@TempDir Path dir) {
        var args=RecoverTradeAdmission.parse(plan(dir.resolve("plan.json"),"game"));
        assertFalse(args.apply());assertEquals(ID,args.request().recoveryId());assertEquals(1,args.request().oldEpoch());
        var applied=RecoverTradeAdmission.parse(apply(dir.resolve("result.json"),dir.resolve("plan.json"),"a".repeat(64),"game"));
        assertTrue(applied.apply());assertNull(applied.request());
        var missing=new ArrayList<>(plan(dir.resolve("p"),"game"));missing.add("--apply");
        assertThrows(IllegalArgumentException.class,()->RecoverTradeAdmission.parse(missing));
        for(var suffix:List.of(List.of("--unknown","x"),List.of("--old-epoch","2"))) {
            var duplicate=new ArrayList<>(plan(dir.resolve("p"),"game"));duplicate.addAll(suffix);
            assertThrows(IllegalArgumentException.class,()->RecoverTradeAdmission.parse(duplicate));
        }
    }

    @Test void URL凭据参数与输出覆盖在打开SQL前拒绝_不输出秘密(@TempDir Path dir) throws Exception {
        var opens=new AtomicInteger();var connections=new RecoverTradeAdmission.Connections() {
            public DataSource open(String url,String user,String password) { opens.incrementAndGet();throw new AssertionError("不应连接"); }
        };
        var remote=new java.util.HashMap<>(env());remote.put("GAME_TRADE_RECOVERY_JDBC","jdbc:mysql://secret:password@10.2.3.4/game");
        assertEquals(1,run(plan(dir.resolve("p"),"game"),remote,connections));
        for(String url:List.of("jdbc:mysql://user:password@127.0.0.1/game", "jdbc:mysql://127.0.0.1/game?password=secret",
                "jdbc:mysql://127.0.0.1/game;password=secret", "jdbc:mysql://127.0.0.1/game#password=secret")) {
            var unsafe=new java.util.HashMap<>(env());unsafe.put("GAME_TRADE_RECOVERY_JDBC",url);
            assertEquals(1,run(plan(dir.resolve("unsafe"),"game"),unsafe,connections));
        }
        Path existing=dir.resolve("existing");Files.writeString(existing,"original");
        assertEquals(1,run(plan(existing,"game"),env(),connections));assertEquals("original",Files.readString(existing));
        assertEquals(0,opens.get());assertFalse(err.toString().contains("password"));assertFalse(err.toString().contains("10.2.3.4"));
    }

    private static RoleAdmissionIdentity identity(long epoch,String source) {
        return RoleAdmissionIdentity.newBuilder().setPlayerId(1).setOwnerEpoch(epoch).setOwnerRevision(1)
                .setSceneInstanceId(source).setGeneration(7).setSceneNodeId("node").setSceneId(41).setZoneId(7)
                .setSessionId(2).setLoginGeneration(3).build();
    }
    private static void setup(DataSqlFixture f) { setup(f,true); }
    private static void setup(DataSqlFixture f,boolean admitted) {
        var targetSql=new JdbcTemplate(f.dataSource);
        targetSql.execute("CREATE TABLE maintenance_recovery_target(purpose VARBINARY(16) NOT NULL,zone_id INT UNSIGNED NOT NULL,schema_name VARBINARY(64) NOT NULL,target_nonce VARBINARY(32) NOT NULL,physical_addr VARBINARY(255) NOT NULL,PRIMARY KEY(purpose,zone_id,schema_name))");
        targetSql.update("INSERT INTO maintenance_recovery_target VALUES(?,?,?,?,?)","admission".getBytes(java.nio.charset.StandardCharsets.US_ASCII),7,
                f.database.getBytes(java.nio.charset.StandardCharsets.US_ASCII),NONCE.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "127.0.0.1:24000".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var clock=new AtomicLong(1_000_000);var players=f.playerStore(clock::get);
        players.ensureAccount("seller");var row=new PlayerRow();row.setPlayerId(1);row.setAccount("seller");row.setZoneId(7);row.setClassId(1);
        assertEquals(PlayerStore.CreateStatus.CREATED,players.createPlayerWithinCap(row,5,List.of("旧OPEN角色"),BUDGET).status());
        assertEquals(new PlayerStore.ClaimResult.Claimed(1),players.claimOwnership(1,BUDGET));
        assertTrue(players.loadForScene(identity(1,"old-source"),"seller",BUDGET).isPresent());
        clock.addAndGet(PlayerStore.OWNER_LEASE.toMillis()*2);
        assertEquals(new PlayerStore.ClaimResult.Claimed(2),players.claimOwnership(1,BUDGET));
        if(admitted) {
            assertTrue(players.loadForScene(identity(2,"current-source"),"seller",BUDGET).isPresent());
        }
        var trades=new PlayerTradeStore(f.dataSource,f.transactionManager,clock::get,5,PlayerTradeStore.sqlFreezeVerifier(),(sql,request)->{});
        var freeze=new PlayerTradeStore.FreezeRequest(101,1,"seller",1);trades.beginFreeze(freeze,BUDGET);
        if(!admitted)return;
        row=players.findPlayer(1).orElseThrow();
        assertEquals(PlayerStore.FinalSaveStatus.ACCEPTED,players.registerFinalSave(row,PlayerState.getDefaultInstance().toByteArray(),"current-source",BUDGET).status());
        trades.recordSceneDrain(new PlayerTradeStore.DrainTask(freeze,2,"current-source"),BUDGET);
        assertEquals(PlayerStore.FinalSaveStatus.APPLIED,players.completeFinalSave(1,2,BUDGET).status());
    }
    private static RecoverTradeAdmission.Connections connection(DataSqlFixture f,String instance,AtomicInteger closed) {
        return new RecoverTradeAdmission.Connections() {
            public DataSource open(String url,String user,String password) { return f.dataSource; }
            public String identity(java.sql.Connection ignored) { return instance; }
            public void verify(java.sql.Connection c) throws Exception {
                if(DataSqlFixture.mysql())com.game.discovery.sql.StrongTiDbConnectionPolicy.verify(c,true);
                else assertEquals("H2",c.getMetaData().getDatabaseProductName(),"仅内存夹具不冒真TiDB会话");
            }
            public void close(DataSource ignored) { closed.incrementAndGet(); }
        };
    }

    @Test void 正式Store默认计划零写_显式apply永久收据_同计划可重查(@TempDir Path dir) throws Exception {
        try(var f=DataSqlFixture.create()) {
            setup(f);var sql=new JdbcTemplate(f.dataSource);var closed=new AtomicInteger();var c=connection(f,INSTANCE,closed);
            Path p=dir.resolve("plan.json");assertEquals(0,run(plan(p,f.database),env(f.database),c));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_trade_admission_recovery",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE owner_epoch=1",Integer.class));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
            assertEquals(0,run(apply(dir.resolve("applied.json"),p,digest,f.database),env(f.database),c));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE owner_epoch=1",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM player_trade_admission_recovery",Integer.class));
            assertEquals(0,run(apply(dir.resolve("replayed.json"),p,digest,f.database),env(f.database),c));
            var json=new ObjectMapper().readTree(Files.readAllBytes(dir.resolve("replayed.json")));
            assertEquals("RETIRED_BY_SQL_SUCCESSOR",json.path("receipt").path("status").asText());
            assertEquals(3,closed.get());assertFalse(out.toString().contains("must-not-appear"));assertFalse(Files.readString(p).contains("password"));
        }
    }

    @Test void 错实例_错库_篡改计划SHA均不退休原OPEN(@TempDir Path dir) throws Exception {
        try(var f=DataSqlFixture.create()) {
            setup(f);var sql=new JdbcTemplate(f.dataSource);var closed=new AtomicInteger();Path p=dir.resolve("plan.json");
            assertEquals(1,run(plan(dir.resolve("wrong-instance"),f.database),env(f.database),connection(f,"wrong-instance",closed)));
            assertEquals(1,run(plan(dir.resolve("wrong-database"),"wrong_database"),env(f.database),connection(f,INSTANCE,closed)));
            assertEquals(0,run(plan(p,f.database),env(f.database),connection(f,INSTANCE,closed)));
            assertEquals(1,run(apply(dir.resolve("result.json"),p,"0".repeat(64),f.database),env(f.database),connection(f,INSTANCE,closed)));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_trade_admission_recovery",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE owner_epoch=1",Integer.class));
            assertEquals(101L,sql.queryForObject("SELECT trade_listing_id FROM player WHERE player_id=1",Long.class).longValue());
            assertFalse(err.toString().contains("must-not-appear"));assertFalse(Files.exists(dir.resolve("result.json")));
        }
    }

    @Test void 无current入场的CLI计划固定SQL_NO_ADMISSION_显式apply只封闭未使用E不造APPLIED(@TempDir Path dir) throws Exception {
        try(var f=DataSqlFixture.create()) {
            setup(f,false);var sql=new JdbcTemplate(f.dataSource);var c=connection(f,INSTANCE,new AtomicInteger());
            byte[] state=sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class);
            long saved=sql.queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=1",Long.class);
            Path plan=dir.resolve("unadmitted-plan.json");assertEquals(0,run(plan(plan,f.database),env(f.database),c));
            var body=new ObjectMapper().readTree(Files.readAllBytes(plan));
            assertEquals("SQL_NO_ADMISSION",body.path("original").path("proofMode").asText());
            assertTrue(body.path("original").path("sealUnusedEpoch").asBoolean());
            assertEquals(0,body.path("original").path("remainingAdmissions").asInt());
            assertEquals(0,sql.queryForObject("SELECT owner_released FROM player WHERE player_id=1",Integer.class));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_trade_drain",Integer.class));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(plan)));
            Path result=dir.resolve("unadmitted-result.json");assertEquals(0,run(apply(result,plan,hash,f.database),env(f.database),c));
            var receipt=new ObjectMapper().readTree(Files.readAllBytes(result)).path("receipt");
            assertEquals("SQL_NO_ADMISSION",receipt.path("proofMode").asText());assertTrue(receipt.path("unadmittedEpochSealed").asBoolean());
            assertEquals("SQL_NO_ADMISSION",sql.queryForObject("SELECT proof_mode FROM player_trade_admission_recovery",String.class));
            assertEquals("SQL_NO_ADMISSION",sql.queryForObject("SELECT proof_mode FROM player_trade_drain",String.class));
            assertEquals(1,sql.queryForObject("SELECT owner_released FROM player WHERE player_id=1",Integer.class));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_final_save",Integer.class));
            assertArrayEquals(state,sql.queryForObject("SELECT data FROM player_state WHERE player_id=1",byte[].class));
            assertEquals(saved,sql.queryForObject("SELECT saved_epoch FROM player_state WHERE player_id=1",Long.class).longValue());
        }
    }

    @Test void 非回环精确维护目标可计划_改nonce与namespace均在首效果前拒绝(@TempDir Path dir) throws Exception {
        try(var f=DataSqlFixture.create()) {
            setup(f);var sql=new JdbcTemplate(f.dataSource);
            String address="maintenance-db.example:3306";
            sql.update("UPDATE maintenance_recovery_target SET physical_addr=?",address.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            var remote=new java.util.HashMap<>(env(f.database));remote.put("GAME_TRADE_RECOVERY_JDBC","jdbc:mysql://"+address+"/"+f.database);
            var c=connection(f,INSTANCE,new AtomicInteger());Path p=dir.resolve("remote-plan.json");
            assertEquals(0,run(plan(p,f.database),remote,c));
            var body=new ObjectMapper().readTree(Files.readAllBytes(p));
            assertEquals("player-trade-open-recovery-plan-v3",body.path("schema").asText());
            assertEquals(address,body.path("target").path("physicalAddress").asText());
            assertEquals(NONCE,body.path("target").path("nonce").asText());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
            var changed=new ArrayList<>(apply(dir.resolve("wrong-nonce"),p,hash,f.database));changed.set(changed.indexOf(NONCE),"b".repeat(32));
            assertEquals(1,run(changed,remote,c));
            var otherNamespace=new java.util.HashMap<>(remote);otherNamespace.put("GAME_NAMESPACE","another");
            assertEquals(1,run(apply(dir.resolve("wrong-namespace"),p,hash,f.database),otherNamespace,c));
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM player_trade_admission_recovery",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE owner_epoch=1",Integer.class));
            assertFalse(err.toString().contains("must-not-appear"));
        }
    }

    @Test void 只有debug标记不授维护权_事务连接换实例拒绝_旧计划不能绕过新闸(@TempDir Path dir) throws Exception {
        try(var f=DataSqlFixture.create()) {
            setup(f);var sql=new JdbcTemplate(f.dataSource);Path p=dir.resolve("plan.json");
            sql.execute("DROP TABLE maintenance_recovery_target");
            sql.execute("CREATE TABLE debug_import_local_target(marker VARBINARY(32))");
            sql.update("INSERT INTO debug_import_local_target VALUES(?)",NONCE.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(1,run(plan(p,f.database),env(f.database),connection(f,INSTANCE,new AtomicInteger())));
            assertFalse(Files.exists(p));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM player_final_save WHERE owner_epoch=1",Integer.class));
        }
        try(var f=DataSqlFixture.create()) {
            setup(f);var reads=new AtomicInteger();var c=new RecoverTradeAdmission.Connections() {
                public DataSource open(String url,String user,String password){return f.dataSource;}
                public String identity(java.sql.Connection ignored){return reads.incrementAndGet()==1?INSTANCE:"different-instance";}
                public void verify(java.sql.Connection ignored){}
                public void close(DataSource ignored){}
            };
            assertEquals(1,run(plan(dir.resolve("changed-instance"),f.database),env(f.database),c));
            assertEquals(2,reads.get(),"初始探针不能替代恢复事务内的同连接实例核验");
            assertEquals(0,new JdbcTemplate(f.dataSource).queryForObject("SELECT COUNT(*) FROM player_trade_admission_recovery",Integer.class));
        }
        for(String url:List.of("jdbc:mysql://maintenance-db.example/game","jdbc:mysql://maintenance-db.example:03306/game",
                "jdbc:mysql://maintenance-db.example:3306/game?useSSL=false","jdbc:mysql://a:3306,b:3306/game",
                "jdbc:mysql://user:pass@maintenance-db.example:3306/game")) {
            assertNull(RecoverTradeAdmission.recoveryJdbcAddress(url,"game"));
        }
        assertEquals("[2001:db8::1]:3306",RecoverTradeAdmission.recoveryJdbcAddress("jdbc:mysql://[2001:db8::1]:3306/game","game"));
    }
}
