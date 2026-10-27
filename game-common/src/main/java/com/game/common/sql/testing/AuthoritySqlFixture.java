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
    private static final Set<String> CONTROL=Set.of("route_meta","zone_merged_into","zone_merge_fence","writer_registry","writer_slot","writer_lifecycle_receipt","sql_writer_drain_run","sql_writer_freeze_ticket","sql_writer_maintenance_receipt","__sql_fixture_owner");
    private final Options options;private final String database,user,password;private final byte[] nonce;private final Path ownerFile;private boolean owned,userCreated,unknown,closed;private WriterAuthority boot;
    private AuthoritySqlFixture(Options options)throws Exception{
        this.options=options;if(!options.root().matches("jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/?"))throw new SQLException("fixture只接显式数值loopback端口，不能带业务库或URL参数");
        int port=Integer.parseInt(options.root().replaceAll("/$","").substring(options.root().lastIndexOf(':')+1));if(port>65535||!options.prefix().matches("[a-z][a-z0-9_]{0,25}"))throw new SQLException("fixture目标/前缀非法");
        if(options.physical().length!=32||options.qualification().length!=32||Arrays.equals(options.physical(),new byte[32])||Arrays.equals(options.qualification(),new byte[32])||!Set.of("SELECT_ONLY","LOCK_TOUCH").contains(options.primitive()))throw new SQLException("fixture真实资格原件非法");
        WriterAuthority.requirePrivateDirectory(options.receipts());String suffix=HexFormat.of().formatHex(random(12));database=options.prefix()+"_it_"+suffix;user="wf_"+suffix;password=Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));nonce=random(32);ownerFile=options.receipts().resolve(database+".owner");write(ownerFile,ownerBytes());
        try(var admin=admin("");var statement=admin.createStatement()){
            try(var rows=statement.executeQuery("SELECT VERSION()")){if(!rows.next()||!options.allowMySql()&&!rows.getString(1).toLowerCase(Locale.ROOT).contains("tidb"))throw new SQLException("fixture需要实际TiDB");}
            unknown=true;statement.execute("CREATE DATABASE "+name(database)+" CHARACTER SET utf8mb4");unknown=false;
        }
        try(var admin=admin(database);var statement=admin.createStatement()){
            unknown=true;statement.execute("CREATE TABLE __sql_fixture_owner(id INT PRIMARY KEY,owner_nonce VARBINARY(32) NOT NULL,physical_sha VARBINARY(32) NOT NULL,normal_principal VARBINARY(288) NOT NULL)");unknown=false;
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
    public String database(){return database;}public String normalUser(){return user;}
    /** 只用于现有池的显式装配，调用者不得记录值。 */ public String normalPassword(){return password;}
    public String jdbcUrl(String parameters){if(!parameters.isEmpty()&&!parameters.startsWith("?"))throw new IllegalArgumentException("参数形状非法");return options.root().replaceAll("/$","")+"/"+database+parameters;}
    public WriterAuthority writerBoot(){if(boot==null||closed)throw new IllegalStateException("fixture主体未就绪或已关闭");return boot;}
    public DataSource normalDataSource(String parameters)throws SQLException{Properties p=new Properties();p.setProperty("user",user);p.setProperty("password",password);return new AuthorityDataSource(jdbcUrl(parameters),p,writerBoot(),!options.allowMySql(),List.of());}
    /** 只供本夹具seed/故障注入，业务构造器必须接normalDataSource或普通池。 */
    public DataSource schemaDataSource(){return new DataSource(){
        public Connection getConnection()throws SQLException{return schemaConnection();}
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("fixture schema主体不能覆盖");}
        public java.io.PrintWriter getLogWriter(){return null;}public void setLogWriter(java.io.PrintWriter writer)throws SQLException{throw new SQLException("fixture不输出SQL凭据");}
        public int getLoginTimeout(){return 5;}public void setLoginTimeout(int seconds)throws SQLException{throw new SQLException("fixture连接预算固定");}
        public java.util.logging.Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("fixture不允许unwrap");}public boolean isWrapperFor(Class<?> type){return false;}
    };}
    public Connection schemaConnection()throws SQLException{if(closed||unknown||!owned)throw new SQLException("fixture schema不可用");return admin(database);}
    private Connection admin(String db)throws SQLException{var connection=DriverManager.getConnection(options.root().replaceAll("/$","")+"/"+db,options.adminUser(),options.adminPassword());try{AuthorityDataSource.initialize(connection,!options.allowMySql(),List.of());connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setNetworkTimeout(Runnable::run,5000);return connection;}catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException cleanup){failure.addSuppressed(cleanup);}throw failure;}}
    private static String currentUser(Connection connection)throws SQLException{try(var s=connection.createStatement();var r=s.executeQuery("SELECT CURRENT_USER()")){if(!r.next())throw new SQLException("缺实际主体");String value=r.getString(1);if(value==null||value.isBlank()||r.next())throw new SQLException("实际主体不唯一");return value;}}
    private void register(Connection admin)throws Exception{
        try(var statement=admin.createStatement()){
            if(!userCreated){unknown=true;try{statement.execute("CREATE USER "+literal(user)+"@'%' IDENTIFIED BY "+literal(password));}catch(SQLException unsafe){throw new SQLException("fixture账号创建结局未知，保留私有owner原件","08007");}unknown=false;userCreated=true;}
            List<String> tables=new ArrayList<>();try(var q=admin.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name")){q.setString(1,database);try(var rows=q.executeQuery()){while(rows.next())tables.add(rows.getString(1));}}
            for(String table:tables)if(!table.equals("__sql_fixture_owner")){unknown=true;statement.execute("GRANT "+(CONTROL.contains(table)?"SELECT":"SELECT,INSERT,UPDATE,DELETE")+" ON "+name(database)+"."+name(table)+" TO "+literal(user)+"@'%'");unknown=false;}
            if(options.primitive().equals("LOCK_TOUCH")){unknown=true;statement.execute("GRANT UPDATE(lock_touch) ON "+name(database)+".writer_slot TO "+literal(user)+"@'%'");unknown=false;}
        }
        String principal;try(var normal=DriverManager.getConnection(jdbcUrl(""),user,password)){principal=currentUser(normal);}unknown=true;update(admin,"UPDATE __sql_fixture_owner SET normal_principal=? WHERE id=1 AND owner_nonce=?",principal.getBytes(StandardCharsets.UTF_8),nonce);unknown=false;
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
        byte[] json=new ObjectMapper().writeValueAsBytes(grant);Path file=options.receipts().resolve(database+"-"+HexFormat.of().formatHex(random(8))+".grant.json");write(file,json);var environment=new HashMap<String,String>(System.getenv());environment.putAll(Map.of("MMORPG_SQL_WRITER_GRANT_FILE",file.toString(),"MMORPG_SQL_WRITER_GRANT_SHA256",HexFormat.of().formatHex(sha(json)),"MMORPG_SQL_WRITER_CONSUME_DIR",options.receipts().toString(),"MMORPG_SQL_DEPLOYMENT_ID",deployment,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256",HexFormat.of().formatHex(options.physical())));boot=WriterAuthority.consume(environment);
    }
    private final Map<String,DynamicMessage> sqlSeamFreezes=new HashMap<>();
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
            if(priorRun.length!=0){String old=uuidText(priorRun);if(writable!=0||!sqlSeamFreezes.containsKey(old))throw new SQLException("不能释放非本fixture控制种子");
                try(var q=admin.prepareStatement("SELECT state FROM zone_merge_run WHERE run_id=?")){q.setString(1,old);try(var r=q.executeQuery()){if(!r.next()||r.getInt(1)!=6)throw new SQLException("前一域run尚未显式release");}}
                unknown=true;update(admin,"UPDATE route_meta SET writable=1,writer_epoch=writer_epoch+1,freeze_run_id=? WHERE id=1 AND writable=0 AND freeze_run_id=?",new byte[0],priorRun);unknown=false;register(admin);
            }
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

    @Override public void close()throws SQLException{
        if(closed)return;if(boot!=null)boot.seal();if(unknown||!owned)throw new SQLException("fixture仍有UNKNOWN或缺归属标记，保留原件及对象");
        try{
            if(!MessageDigest.isEqual(WriterAuthority.readPrivateControlFile(ownerFile,65536),ownerBytes()))throw new SQLException("fixture私有归属原件不同");
            try(var admin=admin(database);var q=admin.prepareStatement("SELECT owner_nonce,physical_sha,normal_principal FROM __sql_fixture_owner WHERE id=1");var r=q.executeQuery()){
                if(!r.next()||!MessageDigest.isEqual(nonce,r.getBytes(1))||!MessageDigest.isEqual(options.physical(),r.getBytes(2)))throw new SQLException("fixture schema归属不同");byte[] principal=r.getBytes(3);if(r.next())throw new SQLException("fixture归属不唯一");
                if(userCreated)try(var normal=DriverManager.getConnection(jdbcUrl(""),user,password)){if(!MessageDigest.isEqual(principal,currentUser(normal).getBytes(StandardCharsets.UTF_8)))throw new SQLException("fixture实际账号主体已变化");}
            }
            // 所有归属核验在先；仅删除本fixture实际CREATE的精确对象，不用IF EXISTS或仅凭前缀。
            try(var admin=admin("");var statement=admin.createStatement()){if(userCreated){unknown=true;statement.execute("DROP USER "+literal(user)+"@'%'");unknown=false;}unknown=true;statement.execute("DROP DATABASE "+name(database));unknown=false;closed=true;}
        }catch(IOException invalid){throw new SQLException("fixture私有归属原件不可读，拒绝清理");}
    }
}
