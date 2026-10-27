package com.game.data.tools;

import com.game.common.player.RouteAuthorityInitializer;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.player.store.state.*;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** 独立离线authority控制工具；normal服务不引用此类，也不读取operator/schema凭据。 */
public final class InitializeRouteAuthority {
    private static final BigInteger U64=BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private InitializeRouteAuthority(){}
    public static void main(String[] arguments)throws Exception{
        try{run(options(arguments),System.getenv());}
        catch(Exception failure){
            // JDBC错误正文可能含连接串或秘密；只输出类型与稳定错误轴。
            if(failure instanceof SQLException sql)System.err.println("authority操作失败："+failure.getClass().getSimpleName()+" SQLState="+sql.getSQLState()+" code="+sql.getErrorCode());
            else System.err.println("authority操作失败："+failure.getClass().getSimpleName());
            throw new IllegalStateException("authority操作未确认，保留原请求/原run，不自动重试");
        }
    }
    static void run(Map<String,String> args,Map<String,String> env)throws Exception{
        String command=need(args,"--command");
        if(command.equals("fresh-schema")){fresh(args,env);return;}
        Set<String> control=Set.of("register","retire","phase","ticket","freeze","release","verify-prepare");
        if(!control.contains(command))throw new IllegalArgumentException("未知authority子命令");
        try(Connection connection=open(env,false)){
            Authority authority=new Authority(connection,need(args,"--authority-schema"),env);
            if(command.equals("register")){register(authority,args,env);return;}
            if(command.equals("verify-prepare")){verifyPrepare(authority,args,env);return;}
            if(command.equals("retire")){
                var request=read(Path.of(need(args,"--request")),SqlWriterLifecycleReceipt.Request.newBuilder()).build();
                write(Path.of(need(args,"--output")),JsonFormat.printer().print(authority.retire(request)).getBytes(StandardCharsets.UTF_8));return;
            }
            var input=read(Path.of(need(args,"--input")),SqlWriterMaintenanceReceipt.newBuilder()).build();
            SqlWriterMaintenanceReceipt result=switch(command){
                case "phase"->authority.phase(input,args);
                case "ticket"->authority.ticket(input,args);
                case "freeze"->authority.freeze(input);
                case "release"->authority.release(input);
                default->throw new IllegalArgumentException();
            };
            write(Path.of(need(args,"--output")),JsonFormat.printer().print(result).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static Map<String,String> options(String[] args){
        if(args.length%2!=0)throw new IllegalArgumentException("每个双横参数必须有值");
        Set<String> allowed=Set.of("--command","--authority-schema","--request","--output","--input","--roster","--input-root","--runtime-config","--runtime-config-output","--boot-output","--deployment-id","--lock-qualification","--schema-file","--generation","--zones","--expect-database","--freeze-request","--authorization-kind","--not-before-ms","--expires-ms","--recovery-audit");
        Map<String,String> parsed=new LinkedHashMap<>();
        for(int i=0;i<args.length;i+=2)if(!allowed.contains(args[i])||parsed.putIfAbsent(args[i],args[i+1])!=null)throw new IllegalArgumentException("参数重复或形状非法");
        return parsed;
    }
    private static String need(Map<String,String> values,String name){String value=values.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("缺少必要参数/环境："+name);return value;}
    private static Connection open(Map<String,String> env,boolean schema)throws Exception{
        String prefix=schema?"MMORPG_SQL_SCHEMA_":"MMORPG_SQL_OPERATOR_";
        String url=need(env,prefix+"JDBC_URL");
        if(!url.startsWith("jdbc:mysql:")||url.matches("(?is).*[?&](user|password|token)=.*"))throw new IllegalArgumentException("JDBC地址不得夹带秘密");
        com.game.common.sql.AuthorityDataSource.validateUrl(url);
        Connection connection=DriverManager.getConnection(url,need(env,prefix+"USER"),need(env,prefix+"PASSWORD"));
        try{
            connection.setNetworkTimeout(Runnable::run,30_000);
            try(var statement=connection.createStatement()){statement.setQueryTimeout(3);statement.execute(StrongTiDbConnectionPolicy.SESSION_SQL);}
            StrongTiDbConnectionPolicy.verify(connection,true);
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT CURRENT_USER()")){
                if(!rows.next()||!need(env,prefix+"PRINCIPAL").equals(rows.getString(1))||rows.next())throw new SQLException("实际独立控制主体不符");
            }
            return connection;
        }catch(Throwable failure){connection.close();throw failure;}
    }
    private static <B extends Message.Builder>B read(Path path,B builder)throws Exception{return read(file(path.toAbsolutePath().normalize()),builder);}
    private static <B extends Message.Builder>B read(byte[] bytes,B builder)throws Exception{
        if(new String(bytes,StandardCharsets.UTF_8).stripLeading().startsWith("{"))JsonFormat.parser().merge(new String(bytes,StandardCharsets.UTF_8),builder);else builder.mergeFrom(bytes);return builder;
    }
    private static byte[] canonical(Message message)throws IOException{
        byte[] bytes=new byte[message.getSerializedSize()];var output=CodedOutputStream.newInstance(bytes);output.useDeterministicSerialization();message.writeTo(output);output.checkNoSpaceLeft();return bytes;
    }
    private static ByteString sha(byte[] bytes){try{return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void noUnknown(Message message)throws SQLException{
        if(!message.getUnknownFields().asMap().isEmpty())throw new SQLException("控制原件含未知字段，拒绝忽略未来语义");
        for(var entry:message.getAllFields().entrySet())if(entry.getKey().getJavaType()==Descriptors.FieldDescriptor.JavaType.MESSAGE){
            if(entry.getKey().isRepeated())for(Object value:(List<?>)entry.getValue())noUnknown((Message)value);else noUnknown((Message)entry.getValue());
        }
    }
    private static ByteString hash(Message message)throws IOException{return sha(canonical(message));}
    private static void size(ByteString value,int bytes){if(value.size()!=bytes||value.equals(ByteString.copyFrom(new byte[bytes])))throw new IllegalArgumentException("原件身份/摘要长度非法或为零");}
    private static void uuid(String value){if(!UUID.fromString(value).toString().equals(value)||value.equals("00000000-0000-0000-0000-000000000000"))throw new IllegalArgumentException("需要规范非零UUID世代");}
    private static byte[] file(Path file)throws IOException{
        return com.game.common.sql.WriterAuthority.readPrivateControlFile(file.toAbsolutePath().normalize(),16*1024*1024);
    }
    private static void write(Path destination,byte[] bytes)throws IOException{
        Path path=destination.toAbsolutePath().normalize();if(!Files.isDirectory(path.getParent(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path.getParent()))throw new IOException("私有输出目录必须预先创建");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(!MessageDigest.isEqual(file(path),bytes))throw new IOException("既有永久输出与原件不同");return;}
        com.game.common.sql.WriterAuthority.createPrivateControlFile(path,bytes);
    }
    private static void fresh(Map<String,String> args,Map<String,String> env)throws Exception{
        String expected=need(args,"--expect-database"),generation=need(args,"--generation");uuid(generation);
        if(!expected.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("数据库名非法");
        String ddl=new String(file(Path.of(need(args,"--schema-file"))),StandardCharsets.UTF_8);
        try(Connection connection=open(env,true)){
            if(!expected.equals(connection.getCatalog()))throw new SQLException("fresh目标数据库不符");
            try(var query=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?")){
                query.setString(1,expected);try(var rows=query.executeQuery()){if(!rows.next()||rows.getLong(1)!=0)throw new SQLException("fresh-schema只接空库；存量迁移缺证据保持HOLD");}
            }
            for(String statement:ddlStatements(ddl))try(var sql=connection.createStatement()){sql.setQueryTimeout(30);sql.execute(statement);}
            List<Integer> zones=Arrays.stream(need(args,"--zones").split(",")).map(Integer::parseUnsignedInt).toList();
            RouteAuthorityInitializer.initialize(connection,generation,zones);
        }
    }
    private static List<String> ddlStatements(String script)throws SQLException{
        List<String> result=new ArrayList<>();StringBuilder one=new StringBuilder();char quote=0;boolean line=false,block=false;
        for(int i=0;i<script.length();i++){
            char c=script.charAt(i),next=i+1<script.length()?script.charAt(i+1):0;
            if(line){if(c=='\n'||c=='\r'){line=false;one.append(' ');}continue;}
            if(block){if(c=='*'&&next=='/'){block=false;i++;one.append(' ');}continue;}
            if(quote!=0){one.append(c);if(c=='\\'&&i+1<script.length()){one.append(script.charAt(++i));continue;}if(c==quote){if(next==quote){one.append(next);i++;}else quote=0;}continue;}
            if(c=='#'||c=='-'&&next=='-'){line=true;if(c=='-')i++;continue;}
            if(c=='/'&&next=='*'){if(i+2<script.length()&&(script.charAt(i+2)=='!'||script.charAt(i+2)=='+'))throw new SQLException("fresh不接受可执行注释");block=true;i++;continue;}
            if(c=='\''||c=='"'||c==96){quote=c;one.append(c);continue;}
            if(c==';'){addDdl(result,one);one.setLength(0);}else one.append(c);
        }
        if(quote!=0||block)throw new SQLException("DDL原件未闭合");addDdl(result,one);if(result.isEmpty())throw new SQLException("缺少完整fresh DDL");return result;
    }
    private static void addDdl(List<String> statements,StringBuilder sql)throws SQLException{
        String text=sql.toString().strip();if(text.isEmpty())return;
        if(!text.matches("(?is)CREATE\\s+TABLE\\s+.*")||text.contains("."))throw new SQLException("fresh仅允许当前空库CREATE TABLE，不能借此执行迁移/控制SQL");statements.add(text);
    }
    private static void register(Authority authority,Map<String,String> args,Map<String,String> env)throws Exception{
        String deployment=need(args,"--deployment-id");if(deployment.length()>256||deployment.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("部署身份非法");
        ByteString qualification=sha(file(Path.of(need(args,"--lock-qualification"))));
        Path inputRoot=Path.of(need(args,"--input-root")).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if(!Files.isDirectory(inputRoot,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(inputRoot))throw new IOException("init目录非法");
        List<SqlWriterBootRoster.Entry> entries;
        if(args.containsKey("--roster")){
            var roster=read(Path.of(args.get("--roster")),SqlWriterBootRoster.newBuilder()).build();noUnknown(roster);
            if(!roster.getFormat().equals("SQL_WRITER_BOOT_ROSTER_V1")||roster.getEntriesCount()<1||roster.getEntriesCount()>1024)throw new IllegalArgumentException("有限roster形状非法");entries=roster.getEntriesList();
        }else{
            var request=read(Path.of(need(args,"--request")),SqlWriterLifecycleReceipt.Request.newBuilder()).build();
            Path runtime=Path.of(need(args,"--runtime-config")).toAbsolutePath().normalize();
            entries=List.of(SqlWriterBootRoster.Entry.newBuilder().setRequest(request).setRuntimeConfigPath(runtime.toString()).setRuntimeConfigSha256(sha(file(runtime))).build());
        }
        // 全部候选先核真实完整配置原bytes，不能在REGISTER之后才发现选中配置缺失。
        Map<SqlWriterBootRoster.Entry,byte[]> configurations=new IdentityHashMap<>();Set<ByteString> keys=new HashSet<>();
        for(var entry:entries){
            if(entry.getRuntimeEnvironmentCount()!=0)throw new IOException("J入口仅接受完整runtime配置，不能静默忽略env roster");
            Path candidate=Path.of(entry.getRuntimeConfigPath());Path resolved=(candidate.isAbsolute()?candidate:inputRoot.resolve(candidate)).normalize();
            if(!resolved.startsWith(inputRoot)||!resolved.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(inputRoot)||Files.isSymbolicLink(resolved))throw new IOException("runtime配置必须位于受限init目录");
            byte[] content=file(resolved);if(!sha(content).equals(entry.getRuntimeConfigSha256())||!keys.add(entry.getRequest().getWriter().getWriterKey()))throw new IOException("runtime原件摘要不同或roster重复key");configurations.put(entry,content);
        }
        SqlWriterLifecycleReceipt registered=null;SqlWriterBootRoster.Entry selected=null;
        for(var entry:entries){
            var bound=entry.getRequest().toBuilder().setDeploymentId(deployment).setWriter(entry.getRequest().getWriter().toBuilder().clearRegistrationDigest()).build();
            if(!bound.getLockQualificationSha256().equals(qualification))throw new IllegalArgumentException("真实I3资格原件摘要不符");
            if(authority.foreignConsumed(bound))continue; // 仅精确永久foreign消费可选next；任何UNKNOWN/其他冲突直接失败。
            registered=authority.register(bound);selected=entry;break;
        }
        if(registered==null)throw new SQLException("有限roster已用尽；必须补充新的perboot主体，不复用旧key","45000");
        var request=SqlWriterLifecycleReceipt.Request.parseFrom(registered.getRequestProto());
        ByteString physical=ByteString.copyFrom(HexFormat.of().parseHex(need(env,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256")));size(physical,32);
        ByteString grantId=sha(canonical(request)).substring(0,16);
        var grant=SqlWriterBootGrant.newBuilder().setFormat("SQL_WRITER_BOOT_V1").setWriterKey(request.getWriter().getWriterKey()).setInstanceId(request.getWriter().getInstanceId())
                .setGeneration(request.getWriter().getGeneration()).setAuthoritySchema(request.getAuthoritySchema()).addAllProtectedSchemas(request.getProtectedSchemasList())
                .setSqlPrincipal(request.getWriter().getSqlPrincipal()).setRegistrationDigest(registered.getRequestDigest()).setDeploymentId(request.getDeploymentId())
                .setGrantId(grantId).setGrantedMs(authority.registeredMs(request.getWriter().getWriterKey())).setPermission("NORMAL").setPhysicalAuthoritySha256(physical)
                .setMaintenanceRunId(request.getMaintenanceRunId()).setLockPrimitive(request.getLockPrimitive()).setLockQualificationSha256(request.getLockQualificationSha256()).build();
        byte[] encoded=JsonFormat.printer().print(grant).getBytes(StandardCharsets.UTF_8);Path bootOutput=Path.of(need(args,"--boot-output"));
        write(bootOutput,encoded);write(Path.of(bootOutput+".sha256"),HexFormat.of().formatHex(sha(encoded).toByteArray()).getBytes(StandardCharsets.US_ASCII));
        write(Path.of(need(args,"--runtime-config-output")),configurations.get(selected));
    }

    /** 只验证新boot可按prepare-only启动，不消费grant，也不冒充应用consumer就绪或source开放。 */
    private static void verifyPrepare(Authority authority,Map<String,String> args,Map<String,String> env)throws Exception{
        Path input=Path.of(need(args,"--input")).toAbsolutePath().normalize();
        checkPrepare(input.equals(Path.of(need(env,"MMORPG_SQL_WRITER_GRANT_FILE")).toAbsolutePath().normalize()),"grant路径不是当前选定原件");
        byte[] original=file(input);checkPrepare(HexFormat.of().formatHex(sha(original).toByteArray()).equalsIgnoreCase(need(env,"MMORPG_SQL_WRITER_GRANT_SHA256")),"grant原bytes摘要不符");
        var grant=read(input,SqlWriterBootGrant.newBuilder()).build();noUnknown(grant);
        checkPrepare(grant.getFormat().equals("SQL_WRITER_BOOT_V1")&&grant.getPermission().equals("NORMAL")&&grant.getDeploymentId().equals(need(env,"MMORPG_SQL_DEPLOYMENT_ID"))&&grant.getPhysicalAuthoritySha256().equals(ByteString.copyFrom(HexFormat.of().parseHex(need(env,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256")))),"新boot部署/物理原件不同");
        size(grant.getGrantId(),16);size(grant.getMaintenanceRunId(),16);Path consumed=Path.of(need(env,"MMORPG_SQL_WRITER_CONSUME_DIR")).toAbsolutePath().normalize();com.game.common.sql.WriterAuthority.requirePrivateDirectory(consumed);
        checkPrepare(Files.notExists(consumed.resolve("grant-"+HexFormat.of().formatHex(grant.getGrantId().toByteArray())+".consumed"),LinkOption.NOFOLLOW_LINKS),"grant已被消费或标记状态未知");
        authority.verifyPrepare(grant);
    }
    private static void checkPrepare(boolean condition,String failure)throws SQLException{if(!condition)throw new SQLException(failure,"45000");}
    /** 离线预检与提交共用的P0维护工具身份规则；真实原件SHA仍由提交路径核验。 */
    public static void validateMaintenancePlan(SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
        noUnknown(plan);checkPrepare(plan.getMaintenanceIssuersCount()<=1024,"维护工具目录超过有界容量");
        Set<ByteString> oldIssuers=new HashSet<>(),oldInstances=new HashSet<>(),issuers=new HashSet<>(),instances=new HashSet<>();
        for(var role:java.util.stream.Stream.concat(plan.getRootIssuersList().stream(),plan.getQueueMutatorsList().stream()).toList()){oldIssuers.add(role.getIssuerId());oldInstances.add(role.getInstanceId());}
        for(var role:plan.getMaintenanceIssuersList()){
            checkPrepare(!role.getIssuerId().isEmpty()&&role.getLogicalRole().equals("MAINTENANCE")&&role.getPhysicalWriterKey().isEmpty(),"维护工具必须是独立MAINTENANCE角色，不能冒充旧SQL writer");
            size(role.getInstanceId(),16);size(role.getPermanentRevivalFenceId(),16);
            checkPrepare(!oldIssuers.contains(role.getIssuerId())&&!oldInstances.contains(role.getInstanceId())&&issuers.add(role.getIssuerId())&&instances.add(role.getInstanceId()),"维护工具复用旧业务身份或重复boot");
            checkPrepare(role.getAcceptingClosedMs()==0&&role.getAcceptedSettledMs()==0&&role.getAcceptedFrontierDigest().isEmpty()&&role.getUnresolvedPublications()==0&&role.getSourceEvidenceCount()>0,"P0维护工具缺原件或预填未来闭合结论");
        }
    }
    /** 仅验证正式协议正文；SQL提交还核永久freeze与同bytes应用报告，预检成功不授予权限。 */
    public static void validateMaintenancePublication(SqlWriterMaintenanceReceipt.Plan plan,SqlWriterMaintenanceReceipt.ReleaseReadiness ready)throws Exception{
        validateMaintenancePlan(plan);noUnknown(ready);
        if(plan.getMaintenanceIssuersCount()==0){checkPrepare(!ready.hasMaintenancePublication(),"空维护工具目录不能附带闭合结论");return;}
        checkPrepare(ready.hasMaintenancePublication(),"缺少P0维护工具完整闭合原件");var evidence=ready.getMaintenancePublication();
        var inventory=SqlWriterMaintenanceReceipt.PublicationEvidence.newBuilder().addAllLogicalRoles(plan.getMaintenanceIssuersList()).build();
        checkPrepare(evidence.getIssuerInventoryDigest().equals(hash(inventory))&&evidence.getLogicalRolesCount()==plan.getMaintenanceIssuersCount()&&evidence.getSourceEvidenceCount()==plan.getMaintenanceIssuersCount(),"维护工具闭合目录摘要、报告数或role数不同");
        Map<String,SqlWriterMaintenanceReceipt.IssuerClosure> required=new HashMap<>();for(var role:plan.getMaintenanceIssuersList())required.put(Authority.roleKey(role),role);
        for(var role:evidence.getLogicalRolesList()){
            var original=required.remove(Authority.roleKey(role));checkPrepare(original!=null&&Authority.seed(role).equals(original)&&role.getAcceptingClosedMs()!=0&&role.getAcceptedSettledMs()!=0&&Long.compareUnsigned(role.getAcceptedSettledMs(),role.getAcceptingClosedMs())>=0&&role.getUnresolvedPublications()==0,"维护工具未真实cut/join或存在未知副作用");size(role.getAcceptedFrontierDigest(),32);
        }
        checkPrepare(required.isEmpty(),"遗漏P0维护工具闭合");
    }

    private static com.fasterxml.jackson.databind.JsonNode strictJson(byte[] raw)throws Exception{
        return new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
    }
    private static String metadataSchema(byte[] raw){try{var json=strictJson(raw);return json!=null&&json.isObject()&&json.path("schema").isTextual()?json.get("schema").textValue():"";}catch(Exception notMetadata){return "";}}
    private static com.fasterxml.jackson.databind.JsonNode metadata(byte[] raw,String schema,String... fields)throws Exception{
        var json=strictJson(raw);checkPrepare(json!=null&&json.isObject()&&json.path("schema").asText().equals(schema)&&json.size()==fields.length+1,"采样原件schema或字段全集不同");for(String field:fields)checkPrepare(json.has(field),"采样原件缺正式字段");return json;
    }
    private static long unsignedJson(com.fasterxml.jackson.databind.JsonNode json,String field)throws Exception{
        var number=json.get(field);checkPrepare(number!=null&&number.isIntegralNumber()&&number.bigIntegerValue().signum()>=0&&number.bigIntegerValue().compareTo(U64)<=0,"采样时间/计数不是uint64");return number.bigIntegerValue().longValue();
    }
    private static void metadataWindow(com.fasterxml.jackson.databind.JsonNode json)throws Exception{
        long start=unsignedJson(json,"observed_start_ms"),end=unsignedJson(json,"observed_end_ms");checkPrepare(start!=0&&Long.compareUnsigned(end,start)>=0&&Long.compareUnsigned(end-start,60000)<=0,"采样原件缺有界真实观察窗口");
    }
    private static ByteString encodedBytes(com.fasterxml.jackson.databind.JsonNode value)throws Exception{checkPrepare(value!=null&&value.isTextual(),"采样原件字节字段不是base64");byte[] decoded=Base64.getDecoder().decode(value.textValue());checkPrepare(Base64.getEncoder().encodeToString(decoded).equals(value.textValue()),"采样字节字段不是规范base64原件");return ByteString.copyFrom(decoded);}
    /** P0/P4使用锁外固定的真实JSON原件；nonce不是从Plan复制出的观察事实。 */
    public static Set<String> validateRedisObservationSource(byte[] raw,ByteString operation,SqlWriterMaintenanceReceipt.RedisTargetClosure target,boolean baseline,long windowStart,long windowEnd)throws Exception{
        size(operation,16);size(target.getMaintenanceNonce(),16);var fields=new ArrayList<>(List.of("role","address","db","server_run_id","observed_start_ms","observed_end_ms","nonce_key","nonce_before","nonce_after","nonce_pttl_before_ms","nonce_pttl_after_ms"));if(!baseline)fields.add("pages");
        var json=metadata(raw,baseline?"SQL_DRAIN_REDIS_BASELINE_V1":"SQL_DRAIN_REDIS_SCAN_V2",fields.toArray(String[]::new));metadataWindow(json);
        var db=json.get("db");checkPrepare(json.get("role").isTextual()&&json.get("role").textValue().equals(target.getRole())&&json.get("address").isTextual()&&json.get("address").textValue().equals(target.getAddress())&&db.isIntegralNumber()&&db.canConvertToInt()&&db.intValue()==target.getDb()&&encodedBytes(json.get("server_run_id")).equals(target.getServerRunId()),"Redis采样不是P0同物理目标/实例");
        String nonceKey="sql:writer:maintenance:"+HexFormat.of().formatHex(operation.toByteArray())+":nonce";
        checkPrepare(json.get("nonce_key").isTextual()&&json.get("nonce_key").textValue().equals(nonceKey)&&encodedBytes(json.get("nonce_before")).equals(target.getMaintenanceNonce())&&encodedBytes(json.get("nonce_after")).equals(target.getMaintenanceNonce()),"Redis实采nonce缺失、变化或属于另一run");
        for(String field:List.of("nonce_pttl_before_ms","nonce_pttl_after_ms")){var ttl=json.get(field);checkPrepare(ttl.isIntegralNumber()&&ttl.canConvertToLong()&&ttl.longValue()==-1,"Redis维护nonce必须实际存在且无TTL");}
        if(baseline)return Set.of();
        checkPrepare(Long.compareUnsigned(unsignedJson(json,"observed_start_ms"),windowStart)>=0&&Long.compareUnsigned(unsignedJson(json,"observed_end_ms"),windowEnd)<=0,"Redis实采窗口不在P4窗口内");
        var pages=json.get("pages");checkPrepare(pages.isArray()&&!pages.isEmpty()&&pages.size()<=16384,"Redis SCAN缺真实有界游标页");
        var patterns=List.of("kafka:retry:queue:*","kafka:dead:queue:*","kafka:retry:processing:*","kafka:retry:instances:*","kafka:retry:instance_mode:*","player:save_pending:*","player:{*}:save_barrier");
        Map<String,Long> cursors=new HashMap<>();Set<String> complete=new HashSet<>(),keys=new HashSet<>();
        for(var page:pages){checkPrepare(page.isObject()&&page.size()==4&&page.has("pattern")&&page.has("input_cursor")&&page.has("output_cursor")&&page.has("keys")&&page.get("pattern").isTextual(),"Redis SCAN页字段不同");String pattern=page.get("pattern").textValue();checkPrepare(patterns.contains(pattern)&&!complete.contains(pattern),"Redis SCAN未知pattern或提前结束后仍有页");long input=unsignedJson(page,"input_cursor"),output=unsignedJson(page,"output_cursor");checkPrepare(input==cursors.getOrDefault(pattern,0L),"Redis SCAN游标链中断");var values=page.get("keys");checkPrepare(values.isArray(),"Redis SCAN页缺keys数组");int wildcard=pattern.indexOf('*');String prefix=pattern.substring(0,wildcard),suffix=pattern.substring(wildcard+1);
            for(var key:values){checkPrepare(key.isTextual()&&key.textValue().startsWith(prefix)&&key.textValue().endsWith(suffix),"Redis SCAN key不匹配pattern");keys.add(key.textValue());checkPrepare(keys.size()<=16384,"Redis SCAN key超过16K预算");}cursors.put(pattern,output);if(output==0)complete.add(pattern);
        }
        checkPrepare(complete.equals(new HashSet<>(patterns)),"Redis SCAN未遍历全部pattern到cursor0");return Set.copyOf(keys);
    }
    public static void validateRedisBaseline(SqlWriterMaintenanceReceipt.Plan plan,List<byte[]> sources)throws Exception{
        Map<String,SqlWriterMaintenanceReceipt.RedisTargetClosure> expected=new HashMap<>();for(var target:plan.getRedisTargetsList())checkPrepare(expected.put(target.getAddress()+"/"+target.getDb(),target)==null,"P0重复Redis物理目标");checkPrepare(sources.size()==expected.size(),"P0必须每Redis物理目标恰好一个nonce基线");
        for(byte[] raw:sources){var json=strictJson(raw);checkPrepare(json!=null&&json.isObject()&&json.has("address")&&json.get("address").isTextual()&&json.has("db")&&json.get("db").isIntegralNumber()&&json.get("db").canConvertToInt(),"Redis基线缺物理目标");var target=expected.remove(json.get("address").textValue()+"/"+json.get("db").intValue());checkPrepare(target!=null,"Redis基线目标未知或重复");validateRedisObservationSource(raw,plan.getRunId(),target,true,0,0);}
        checkPrepare(expected.isEmpty(),"P0遗漏真实Redis nonce基线");
    }

    private static Set<String> jsonNames(com.fasterxml.jackson.databind.JsonNode values)throws Exception{
        checkPrepare(values!=null&&values.isArray(),"采样目录必须为数组");Set<String> names=new HashSet<>();for(var value:values)checkPrepare(value.isTextual()&&!value.textValue().isBlank()&&names.add(value.textValue()),"采样目录为空或重复");return names;
    }
    private static Set<String> jsonKeys(com.fasterxml.jackson.databind.JsonNode values)throws Exception{
        checkPrepare(values!=null&&values.isObject(),"采样配置必须为对象");Set<String> keys=new HashSet<>();values.fieldNames().forEachRemaining(keys::add);return keys;
    }
    private static void exactConfig(com.fasterxml.jackson.databind.JsonNode value,Set<String> fields)throws Exception{
        checkPrepare(jsonKeys(value).equals(fields),"有效保留配置缺项或出现未知字段");for(var item:value)checkPrepare(item.isTextual()&&!item.textValue().isBlank(),"有效保留配置未知");
    }
    // 与Go encoding/json对元数据的确定性编码一致：struct字段固定顺序，map按键排序，HTML和控制字符转义。
    private static String goJsonString(String value)throws Exception{
        StringBuilder out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32||c=='<'||c=='>'||c=='&'||c=='\u2028'||c=='\u2029')out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else if(Character.isHighSurrogate(c)){checkPrepare(i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)),"元数据字符串含非法Unicode");out.append(c).append(value.charAt(++i));}else{checkPrepare(!Character.isLowSurrogate(c),"元数据字符串含非法Unicode");out.append(c);}}}}return out.append('"').toString();
    }
    private static String goJson(com.fasterxml.jackson.databind.JsonNode value)throws Exception{
        if(value.isTextual())return goJsonString(value.textValue());if(value.isIntegralNumber())return value.bigIntegerValue().toString();if(value.isBoolean()||value.isNull())return value.toString();List<String> values=new ArrayList<>();if(value.isArray()){for(var item:value)values.add(goJson(item));return "["+String.join(",",values)+"]";}checkPrepare(value.isObject(),"元数据含不支持的数字类型");var names=new ArrayList<>(jsonKeys(value));Collections.sort(names);for(String name:names)values.add(goJsonString(name)+":"+goJson(value.get(name)));return "{"+String.join(",",values)+"}";
    }
    private static final List<String> CATALOG_FIELDS=List.of("schema","observed_start_ms","observed_end_ms","cluster_id","topic_partitions","topic_ids","topic_names","group_names","broker_configs","topic_configs");
    /** 公开纯函数用于跨语言原件回归；不访问文件或替调用者确认采样事实。 */
    public static ByteString stableKafkaCatalogDigest(byte[] raw)throws Exception{
        var json=metadata(raw,"SQL_DRAIN_KAFKA_CATALOG_V2",CATALOG_FIELDS.subList(1,CATALOG_FIELDS.size()).toArray(String[]::new));metadataWindow(json);List<String> values=new ArrayList<>();for(String field:CATALOG_FIELDS)values.add(goJsonString(field)+":"+(field.equals("observed_start_ms")||field.equals("observed_end_ms")?"0":goJson(json.get(field))));return sha(("{"+String.join(",",values)+"}").getBytes(StandardCharsets.UTF_8));
    }
    private static com.fasterxml.jackson.databind.JsonNode validateKafkaCatalog(byte[] raw,SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
        stableKafkaCatalogDigest(raw);var json=strictJson(raw);var topics=jsonNames(json.get("topic_names"));var groups=json.get("group_names").isNull()?Set.<String>of():jsonNames(json.get("group_names"));var brokers=json.get("broker_configs");checkPrepare(topics.size()>0&&topics.size()<=129&&groups.size()<=1024&&jsonKeys(brokers).size()>0&&jsonKeys(brokers).size()<=128&&jsonKeys(json.get("topic_ids")).equals(topics)&&jsonKeys(json.get("topic_partitions")).equals(topics)&&jsonKeys(json.get("topic_configs")).equals(topics),"Kafka物理目录/保留配置不完整");
        ByteString cluster=encodedBytes(json.get("cluster_id"));checkPrepare(!cluster.isEmpty(),"Kafka cluster身份未知");Set<String> expectedTopics=new HashSet<>(Set.of("__consumer_offsets")),expectedGroups=new HashSet<>();Map<String,Set<Integer>> subscriptions=new HashMap<>();
        Set<String> topicConfig=Set.of("retention.ms","retention.bytes","cleanup.policy","segment.ms","segment.bytes","delete.retention.ms","min.insync.replicas","unclean.leader.election.enable"),brokerConfig=Set.of("log.retention.ms","log.retention.bytes","log.segment.bytes","log.retention.check.interval.ms","unclean.leader.election.enable","address");
        for(String topic:topics){size(encodedBytes(json.get("topic_ids").get(topic)),16);long count=unsignedJson(json.get("topic_partitions"),topic);checkPrepare(count>0&&count<=4096,"Kafka实际partition数超界");exactConfig(json.get("topic_configs").get(topic),topicConfig);checkPrepare(topic.equals("__consumer_offsets")||json.get("topic_configs").get(topic).get("cleanup.policy").textValue().equals("delete"),"业务topic可压缩删除，不能证明完整排空");}
        for(String broker:jsonKeys(brokers)){checkPrepare(broker.matches("0|[1-9][0-9]{0,9}")&&Long.parseLong(broker)<=Integer.MAX_VALUE,"broker身份无效");exactConfig(brokers.get(broker),brokerConfig);checkPrepare(brokers.get(broker).get("address").textValue().matches("[^\\s]+:[1-9][0-9]{0,4}"),"broker地址无效");}
        for(var item:plan.getKafkaPartitionsList()){expectedTopics.add(item.getTopicName());expectedGroups.add(item.getGroup());checkPrepare(item.getClusterId().equals(cluster)&&item.getTopicId().equals(encodedBytes(json.get("topic_ids").get(item.getTopicName())))&&Integer.toUnsignedLong(item.getPartition())<unsignedJson(json.get("topic_partitions"),item.getTopicName()),"P0 group分区不属实际物理目录");checkPrepare(subscriptions.computeIfAbsent(item.getGroup()+"\u0000"+item.getTopicName(),ignored->new HashSet<>()).add(item.getPartition()),"P0 group分区重复");}
        for(var item:plan.getManualAssignmentsList()){expectedTopics.add(item.getTopicName());checkPrepare(item.getClusterId().equals(cluster)&&item.getTopicId().equals(encodedBytes(json.get("topic_ids").get(item.getTopicName())))&&Integer.toUnsignedLong(item.getPartitionCount())==unsignedJson(json.get("topic_partitions"),item.getTopicName()),"P0 manual分区不属实际物理目录");}
        checkPrepare(topics.equals(expectedTopics)&&groups.equals(expectedGroups),"P0遗漏历史topic/group或加入未知目录");for(var entry:subscriptions.entrySet()){String topic=entry.getKey().substring(entry.getKey().indexOf('\u0000')+1);checkPrepare(entry.getValue().size()==unsignedJson(json.get("topic_partitions"),topic),"P0 group订阅分区不是实际全集");}return json;
    }
    public static SqlWriterMaintenanceReceipt.ConsumerReadiness parseConsumerReadiness(byte[] raw)throws Exception{
        checkPrepare(raw.length>0&&raw.length<=16*1024*1024,"consumer报告为空或超过有界容量");String text=new String(raw,StandardCharsets.UTF_8).stripLeading();
        if(text.startsWith("{")){
            var json=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
            checkPrepare(json.isObject()&&json.has("groups")&&json.get("groups").isArray()&&json.size()==(json.has("manualAssignments")||json.has("manual_assignments")?2:1)&&!(json.has("manualAssignments")&&json.has("manual_assignments"))&&(!json.has("manualAssignments")||json.get("manualAssignments").isArray())&&(!json.has("manual_assignments")||json.get("manual_assignments").isArray()),"JSON consumer报告必须显式groups及可选manualAssignments数组，不能用通用空对象");
        }
        var report=read(raw,SqlWriterMaintenanceReceipt.ConsumerReadiness.newBuilder()).build();noUnknown(report);checkPrepare(report.getGroupsCount()<=128&&report.getManualAssignmentsCount()<=128,"consumer报告owner目录超界");for(var group:report.getGroupsList())checkPrepare(group.getExpectedPartitionsCount()<=16384&&group.getAssignedPartitionsCount()<=16384,"consumer group分区目录超界");for(var manual:report.getManualAssignmentsList())checkPrepare(manual.getAssignmentsCount()>0&&manual.getAssignmentsCount()<=16384&&manual.getSourcePinsCount()<=128,"manual正式报告缺assignment或目录超界");return report;
    }
    /** 正式消费者报告的组内聚合；历史已退役组不会被强制复活，实际配置目录必须在P0内。 */
    public static void validateConsumerReadiness(SqlWriterMaintenanceReceipt.Plan plan,ByteString manifest,List<SqlWriterMaintenanceReceipt.ConsumerReadiness> reports)throws Exception{
        Map<String,SqlWriterMaintenanceReceipt.KafkaPartitionClosure> original=new HashMap<>();for(var part:plan.getKafkaPartitionsList())checkPrepare(original.put(Authority.kafkaKey(part),part)==null,"P0 Kafka目录重复");
        Map<String,Map<String,SqlWriterMaintenanceReceipt.KafkaPartitionClosure>> expected=new HashMap<>();Map<String,Set<String>> assigned=new HashMap<>();Map<String,Integer> generations=new HashMap<>();Set<String> members=new HashSet<>();
        for(var report:reports){noUnknown(report);Set<String> bootGroups=new HashSet<>();for(var group:report.getGroupsList()){
            checkPrepare(group.getOperationId().equals(plan.getRunId())&&group.getManifestDigest().equals(manifest)&&!group.getIssuerId().isEmpty()&&group.getInstanceId().size()==16&&group.getGenerationId()>=0&&!group.getMemberId().isBlank()&&!group.getGroup().isBlank()&&group.getExpectedPartitionsCount()>0,"消费者run/manifest/代次/member或配置目录缺失");
            String key=Base64.getEncoder().encodeToString(group.getExpectedPartitions(0).getClusterId().toByteArray())+"/"+group.getGroup();checkPrepare(bootGroups.add(key)&&members.add(key+"/"+group.getMemberId()),"消费者同boot重复group或跨boot重复member");
            var prior=generations.putIfAbsent(key,group.getGenerationId());checkPrepare(prior==null||prior==group.getGenerationId(),"消费者同group代次不一致");
            Map<String,SqlWriterMaintenanceReceipt.KafkaPartitionClosure> configured=new HashMap<>();
            for(var part:group.getExpectedPartitionsList()){String partition=Authority.kafkaKey(part);checkPrepare(part.getGroup().equals(group.getGroup())&&part.getClusterId().equals(group.getExpectedPartitions(0).getClusterId())&&part.equals(original.get(partition))&&configured.put(partition,part)==null,"消费者固定配置目录不属于P0完整原件或重复");}
            var previous=expected.putIfAbsent(key,configured);checkPrepare(previous==null||previous.equals(configured),"同group副本固定配置目录不同");var owners=assigned.computeIfAbsent(key,ignored->new HashSet<>());
            for(var part:group.getAssignedPartitionsList()){
                String partition=Authority.kafkaKey(part);var baseline=configured.get(partition);checkPrepare(baseline!=null&&owners.add(partition),"assignment未知或跨boot重复");
                checkPrepare(part.toBuilder().setFinalEarliest(baseline.getFinalEarliest()).setFinalExclusiveEnd(baseline.getFinalExclusiveEnd()).setFinalCommitted(baseline.getFinalCommitted()).build().equals(baseline)&&part.getFinalEarliest()>=0&&part.getFinalEarliest()<=baseline.getProtectedCommittedFloor()&&part.getFinalExclusiveEnd()>=baseline.getProtectedCommittedFloor()&&part.getFinalCommitted()==part.getFinalExclusiveEnd(),"assignment历史边界或真实提交未闭合");
            }
        }}
        for(var group:expected.entrySet())checkPrepare(group.getValue().keySet().equals(assigned.get(group.getKey())),"消费者group分摊assignment遗漏实际配置分区");
    }
    private static String manualKey(SqlWriterMaintenanceReceipt.ManualConsumerAssignment item){return Base64.getEncoder().encodeToString(item.getIssuerId().toByteArray())+"/"+Base64.getEncoder().encodeToString(item.getInstanceId().toByteArray())+"/"+item.getConsumerId();}
    private static SqlWriterMaintenanceReceipt.ManualConsumerAssignment manualConfig(SqlWriterMaintenanceReceipt.ManualConsumerAssignment item){return item.toBuilder().clearInstanceId().clearTargetInstanceId().clearAssignmentRevision().clearStartOffset().clearPosition().clearEarliest().clearExclusiveEnd().clearObservedMs().build();}
    private static SqlWriterMaintenanceReceipt.ManualConsumerAssignment manualReplacementConfig(SqlWriterMaintenanceReceipt.ManualConsumerAssignment item){return manualConfig(item).toBuilder().clearConsumerId().clearPartition().clearTargetNodeId().build();}
    private static SqlWriterMaintenanceReceipt.ManualConsumerEvidence manualClosedProjection(SqlWriterMaintenanceReceipt.ManualConsumerEvidence report){var builder=report.toBuilder().clearSourcePins().clearAssignments();for(var assignment:report.getAssignmentsList())builder.addAssignments(assignment.toBuilder().clearObservedMs().clearEarliest().clearExclusiveEnd());return builder.build();}
    public static void validateManualPlan(SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
        checkPrepare(plan.getManualAssignmentsCount()<=16384,"manual目录超过有界容量");Set<String> seen=new HashSet<>();Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> topics=new HashMap<>();
        for(var item:plan.getManualAssignmentsList()){
            size(item.getIssuerId(),16);size(item.getInstanceId(),16);size(item.getTopicId(),16);size(item.getTargetInstanceId(),16);
            checkPrepare(!item.getConsumerId().isBlank()&&item.getConsumerIdBytes().size()<=256&&item.getConsumerId().chars().noneMatch(ch->ch==0||ch==10||ch==13)&&!item.getClusterId().isEmpty()&&!item.getTopicName().isBlank()&&item.getPartitionCount()!=0&&Integer.compareUnsigned(item.getPartitionCount(),4096)<=0&&Integer.compareUnsigned(item.getPartition(),item.getPartitionCount())<0&&item.getTargetNodeId()!=0&&Integer.remainderUnsigned(item.getTargetNodeId(),item.getPartitionCount())==item.getPartition()&&item.getTargetInstanceId().equals(item.getInstanceId())&&item.getAssignmentRevision()!=0&&item.getObservedMs()!=0&&item.getStartOffset()>=0&&item.getPosition()>=item.getStartOffset()&&item.getEarliest()>=0&&item.getEarliest()<=item.getPosition()&&item.getExclusiveEnd()>=item.getPosition()&&seen.add(manualKey(item)),"manual P0真实assignment/目标boot/位置未知或重复");
            checkPrepare(plan.getRootIssuersList().stream().anyMatch(role->role.getIssuerId().equals(item.getIssuerId())&&role.getInstanceId().equals(item.getInstanceId())),"manual owner不是P0 SOURCE issuer/boot");
            String topic=Base64.getEncoder().encodeToString(item.getClusterId().toByteArray())+"/"+item.getTopicName();var prior=topics.putIfAbsent(topic,item);checkPrepare(prior==null||prior.getTopicId().equals(item.getTopicId())&&prior.getPartitionCount()==item.getPartitionCount(),"manual同物理topic的UUID/分区数不一致");
            Set<Integer> groupPartitions=new HashSet<>();for(var part:plan.getKafkaPartitionsList())if(part.getClusterId().equals(item.getClusterId())&&part.getTopicName().equals(item.getTopicName())){checkPrepare(part.getTopicId().equals(item.getTopicId())&&Integer.compareUnsigned(part.getPartition(),item.getPartitionCount())<0,"group/manual物理topic身份不同");groupPartitions.add(part.getPartition());}
            checkPrepare(groupPartitions.isEmpty()||Integer.toUnsignedLong(item.getPartitionCount())==groupPartitions.size(),"共享group/manual物理topic目录缺分区");
        }
    }
    public static void validateManualClosure(SqlWriterMaintenanceReceipt.Plan plan,ByteString manifest,List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> reports,boolean preparing)throws Exception{
        validateManualPlan(plan);Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> expected=new HashMap<>();for(var item:plan.getManualAssignmentsList())expected.put(manualKey(item),item);Set<String> newBoots=new HashSet<>();
        for(var report:reports){noUnknown(report);checkPrepare(report.getOperationId().equals(plan.getRunId())&&report.getManifestDigest().equals(manifest)&&report.getAcceptingClosed()&&report.getCallbacksJoined()&&report.getAccepted()==report.getSettled()&&(!preparing||report.getAccepted()==0)&&report.getUnresolvedPublications()==0&&report.getAssignmentsCount()>0,"manual报告缺同run实际停收/join或仍有UNKNOWN");
            for(var actual:report.getAssignmentsList()){
                SqlWriterMaintenanceReceipt.ManualConsumerAssignment old=null;String key=null;
                for(var entry:expected.entrySet())if((preparing?manualReplacementConfig(entry.getValue()).equals(manualReplacementConfig(actual)):manualConfig(entry.getValue()).equals(manualConfig(actual)))){checkPrepare(old==null,"manual配置原件歧义");old=entry.getValue();key=entry.getKey();}
                checkPrepare(old!=null,"manual配置不在P0或重复报告");expected.remove(key);
                size(actual.getInstanceId(),16);checkPrepare(actual.getTargetInstanceId().equals(actual.getInstanceId())&&actual.getAssignmentRevision()!=0&&actual.getObservedMs()!=0&&actual.getEarliest()>=0&&actual.getPosition()>=0&&actual.getExclusiveEnd()>=actual.getPosition(),"manual实际目标或position/end未知");
                if(preparing)checkPrepare(!actual.getInstanceId().equals(old.getInstanceId())&&!actual.getConsumerId().isBlank()&&actual.getConsumerIdBytes().size()<=256&&actual.getConsumerId().chars().noneMatch(ch->ch==0||ch==10||ch==13)&&actual.getTargetNodeId()!=0&&Integer.remainderUnsigned(actual.getTargetNodeId(),actual.getPartitionCount())==actual.getPartition()&&actual.getStartOffset()==actual.getPosition()&&actual.getEarliest()<=actual.getPosition()&&newBoots.add(manualKey(actual)),"manual P8必须同配置新boot保留原绝对assign起点并暂停");
                else checkPrepare(actual.getPosition()==actual.getExclusiveEnd()&&actual.getInstanceId().equals(old.getInstanceId())&&actual.getAssignmentRevision()==old.getAssignmentRevision()&&actual.getStartOffset()==old.getStartOffset()&&actual.getPosition()>=old.getPosition()&&actual.getEarliest()>=old.getEarliest()&&actual.getEarliest()<=old.getPosition()&&Long.compareUnsigned(actual.getObservedMs(),old.getObservedMs())>=0,"manual旧boot身份/修订变化、历史丢失或位置回退");
            }
        }
        checkPrepare(expected.isEmpty(),"遗漏P0实际manual owner；同分区多个target boot不能合并");
    }

    /** 精确核对P4最终发布与leader同bytes正文，尾pin只允许这一份leader；不接受任意superset。 */
    public static List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> validateManualLeaderPublication(byte[] raw,SqlWriterMaintenanceReceipt.SourcePin leaderPin,List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> published,long windowStart,long windowEnd)throws Exception{
        checkPrepare(sha(raw).equals(leaderPin.getSha256()),"manual leader正文与固定SHA不同");var json=metadata(raw,"SQL_DRAIN_MANUAL_KAFKA_LEADER_V1","observed_start_ms","observed_end_ms","catalog_first_sha256","catalog_last_sha256","consumers");metadataWindow(json);long start=unsignedJson(json,"observed_start_ms"),end=unsignedJson(json,"observed_end_ms");checkPrepare(Long.compareUnsigned(start,windowStart)>=0&&Long.compareUnsigned(end,windowEnd)<=0&&json.get("consumers").isArray()&&json.get("consumers").size()==published.size()&&!published.isEmpty(),"manual leader窗口或boot全集不同");size(encodedBytes(json.get("catalog_first_sha256")),32);size(encodedBytes(json.get("catalog_last_sha256")),32);
        Map<String,SqlWriterMaintenanceReceipt.ManualConsumerEvidence> expected=new HashMap<>();for(var item:published){noUnknown(item);checkPrepare(item.getAssignmentsCount()>0&&item.getSourcePinsCount()==2,"manual P4必须恰好P3+leader两个pin");String key=Base64.getEncoder().encodeToString(item.getAssignments(0).getIssuerId().toByteArray())+"/"+Base64.getEncoder().encodeToString(item.getAssignments(0).getInstanceId().toByteArray());checkPrepare(expected.put(key,item)==null,"manual P4重复boot");}
        List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> result=new ArrayList<>();for(var node:json.get("consumers")){var item=read(node.toString().getBytes(StandardCharsets.UTF_8),SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder()).build();noUnknown(item);checkPrepare(item.getAssignmentsCount()>0&&item.getSourcePinsCount()==1,"leader每boot必须唯一P3原件");String key=Base64.getEncoder().encodeToString(item.getAssignments(0).getIssuerId().toByteArray())+"/"+Base64.getEncoder().encodeToString(item.getAssignments(0).getInstanceId().toByteArray());var actual=expected.remove(key);checkPrepare(actual!=null,"leader遗漏或重复发布boot");var comparison=actual.toBuilder().clearSourcePins();int removed=0;for(var pin:actual.getSourcePinsList())if(pin.equals(leaderPin))removed++;else comparison.addSourcePins(pin);checkPrepare(removed==1&&comparison.build().equals(item),"manual最终正文不是实际leader原件");for(var assignment:item.getAssignmentsList())checkPrepare(Long.compareUnsigned(assignment.getObservedMs(),start)>=0&&Long.compareUnsigned(assignment.getObservedMs(),end)<=0,"manual实际采样不在leader窗口");result.add(item);}
        checkPrepare(expected.isEmpty(),"leader遗漏发布boot");return List.copyOf(result);
    }
    /** P6单次同步命令owner。只有业务调用和全部资源关闭都成功后，调用方才可显式complete。 */
    public static final class MaintenanceSession {
        private final SqlWriterMaintenanceReceipt request,frozen;
        private final SqlWriterMaintenanceReceipt.Plan plan;
        private final SqlWriterMaintenanceReceipt.IssuerClosure seed;
        private final Map<String,String> env;private final Path directory;private final ByteString ticketId,runtimeDigest;private final long closedMs;private boolean terminal;
        private MaintenanceSession(SqlWriterMaintenanceReceipt request,SqlWriterMaintenanceReceipt.Plan plan,SqlWriterMaintenanceReceipt.IssuerClosure seed,Map<String,String> env,Path directory,ByteString ticketId,ByteString runtimeDigest,SqlWriterMaintenanceReceipt frozen){this.request=request;this.plan=plan;this.seed=seed;this.env=Map.copyOf(env);this.directory=directory;this.ticketId=ticketId;this.runtimeDigest=runtimeDigest;this.frozen=frozen;this.closedMs=System.currentTimeMillis();}
        public String authoritySchema(){return plan.getAuthoritySchema();}
        public String runId(){var bytes=ByteBuffer.wrap(request.getRunId().toByteArray());return new UUID(bytes.getLong(),bytes.getLong()).toString();}
        public synchronized void fail(){terminal=true;}
        public synchronized void complete(Message domainReceipt)throws Exception{
            checkPrepare(!terminal,"维护boot已结束，不能重放");terminal=true;
            var current=checkMaintenance(request,plan,ticketId,env);checkPrepare(current.equals(frozen),"维护完成时原freeze已改变");
            byte[] domain=canonical(domainReceipt);checkPrepare(domain.length>0&&domain.length<=16*1024*1024,"实际业务原件为空或超过界限");
            com.game.common.sql.WriterAuthority.createPrivateControlFile(directory.resolve("maintenance.domain.pb"),domain);
            var digest=MessageDigest.getInstance("SHA-256");digest.update(runtimeDigest.toByteArray());digest.update(frozen.getReceiptDigest().toByteArray());digest.update(sha(domain).toByteArray());digest.update(ByteBuffer.allocate(24).putLong(1).putLong(1).putLong(0).array());
            long settled=System.currentTimeMillis();checkPrepare(closedMs>0&&settled>=closedMs&&Long.compareUnsigned(closedMs,frozen.getCommittedMs())>=0,"维护实际关闭时刻早于freeze或时钟倒退");
            var closure=seed.toBuilder().setAcceptingClosedMs(closedMs).setAcceptedSettledMs(settled).setAcceptedFrontierDigest(ByteString.copyFrom(digest.digest())).setUnresolvedPublications(0).build();
            com.game.common.sql.WriterAuthority.createPrivateControlFile(directory.resolve("maintenance.issuer.json"),JsonFormat.printer().includingDefaultValueFields().print(closure).getBytes(StandardCharsets.UTF_8));
        }
    }
    public static MaintenanceSession startMaintenance(Map<String,String> values,Map<String,String> env)throws Exception{
        Path runtime=Path.of(need(values,"--maintenance-runtime")),directory=Path.of(need(values,"--maintenance-dir"));
        checkPrepare(runtime.isAbsolute()&&directory.isAbsolute()&&runtime.normalize().getParent().equals(directory.normalize()),"维护runtime与固定私有目录必须同目录绝对路径");
        String expected=need(values,"--maintenance-sha256"),ticket=need(values,"--ticket-id");checkPrepare(expected.matches("[0-9a-fA-F]{64}")&&ticket.matches("[0-9a-fA-F]{32}"),"维护runtime摘要或ticket格式非法");
        byte[] raw=com.game.common.sql.WriterAuthority.readPrivateControlFile(runtime,16*1024*1024);ByteString digest=sha(raw);checkPrepare(digest.equals(ByteString.copyFrom(HexFormat.of().parseHex(expected))),"维护runtime固定SHA不同");
        var request=read(raw,SqlWriterMaintenanceReceipt.newBuilder()).build();noUnknown(request);var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(request.getPlanProto());noUnknown(plan);validateMaintenancePlan(plan);
        checkPrepare(request.getRunPhase()==SqlWriterDrainRun.Phase.SQL_FROZEN&&request.getRunId().equals(plan.getRunId())&&request.getEvidence().getOperationId().equals(plan.getRunId())&&request.getEvidence().getManifestDigest().equals(sha(request.getPlanProto().toByteArray()))&&request.getEvidence().hasPublication()&&request.getEvidence().getPublication().getLogicalRolesCount()==1,"维护runtime缺同P0/run唯一正式seed");
        checkPrepare(plan.getPhysicalClusterIdentity().equals(ByteString.copyFrom(HexFormat.of().parseHex(need(env,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256")))),"维护runtime与固定物理authority身份不同");
        var seed=request.getEvidence().getPublication().getLogicalRoles(0);checkPrepare(plan.getMaintenanceIssuersList().contains(seed)&&seed.getLogicalRole().equals("MAINTENANCE")&&seed.getPhysicalWriterKey().isEmpty(),"维护seed不属于不可变P0目录");
        checkPrepare(seed.getSourceEvidenceList().stream().anyMatch(pin->Path.of(pin.getAbsolutePath()).isAbsolute()&&directory.normalize().equals(Path.of(pin.getAbsolutePath()).normalize().getParent())),"维护consume目录没有绑定P0固定source路径");
        try(var connection=open(env,false)){var authority=new Authority(connection,plan.getAuthoritySchema(),env);authority.intent(request);authority.preloadPins(plan);}
        var ticketId=ByteString.copyFrom(HexFormat.of().parseHex(ticket));size(ticketId,16);var frozen=checkMaintenance(request,plan,ticketId,env);
        com.game.common.sql.WriterAuthority.createPrivateControlFile(directory.resolve("boot-"+HexFormat.of().formatHex(seed.getInstanceId().toByteArray())+".consumed"),request.getEvidence().getManifestDigest().toByteArray());
        return new MaintenanceSession(request,plan,seed,env,directory,ticketId,digest,frozen);
    }
    private static SqlWriterMaintenanceReceipt checkMaintenance(SqlWriterMaintenanceReceipt request,SqlWriterMaintenanceReceipt.Plan plan,ByteString ticketId,Map<String,String> env)throws Exception{
        try(var connection=open(env,false)){var authority=new Authority(connection,plan.getAuthoritySchema(),env);return authority.tx(sql->{
            var meta=authority.meta(sql);var run=authority.run(sql,request.getRunId(),true);var ticket=authority.ticket(sql,ticketId);
            checkPrepare(run!=null&&run.getPhase()==SqlWriterDrainRun.Phase.SQL_FROZEN&&run.getManifestProto().equals(request.getPlanProto())&&run.getManifestDigest().equals(request.getEvidence().getManifestDigest())&&run.getAttemptRevision()==request.getAttemptRevision(),"维护原run/attempt/manifest不是当前FROZEN");
            checkPrepare(ticket!=null&&ticket.getState()==SqlWriterFreezeTicket.State.CONSUMED&&ticket.getAuthorizationKind()==SqlWriterFreezeTicket.AuthorizationKind.NORMAL_DRAIN_FREEZE&&ticket.getOperationId().equals(run.getOperationId())&&ticket.getAttemptRevision()==run.getAttemptRevision()&&ticket.getGeneration().equals(run.getGeneration())&&ticket.getManifestDigest().equals(run.getManifestDigest()),"维护必须同run已消费NORMAL ticket");
            var frozen=authority.receipt(sql,ticket.getFreezeRequestId());checkPrepare(frozen!=null&&frozen.getKind()==SqlWriterMaintenanceReceipt.Kind.FREEZE&&frozen.getPhase()==SqlWriterMaintenanceReceipt.Phase.FROZEN&&frozen.getRunPhase()==SqlWriterDrainRun.Phase.SQL_FROZEN&&frozen.getRunId().equals(run.getOperationId())&&frozen.getGeneration().equals(request.getGeneration())&&frozen.getPlanProto().equals(request.getPlanProto())&&frozen.getAttemptRevision()==run.getAttemptRevision()&&frozen.getRequestDigest().equals(ticket.getFreezeRequestDigest())&&frozen.getReceiptDigest().equals(ticket.getConsumedReceiptDigest())&&frozen.getRequestId().equals(run.getFrozenRequestId())&&frozen.getRequestId().equals(run.getLatestReceiptId())&&frozen.getReceiptDigest().equals(run.getLatestReceiptDigest()),"维护缺同run完整永久NORMAL freeze原件");
            checkPrepare(meta.writable()==0&&meta.run().equals(run.getOperationId())&&meta.generation().equals(frozen.getGeneration())&&meta.epoch()==frozen.getAfterEpoch()&&meta.catalog()==frozen.getCatalogRevision()&&request.getBeforeEpoch()==meta.epoch()&&request.getCatalogRevision()==meta.catalog(),"维护meta/E/catalog不同");
            checkPrepare(authority.foldedWriters(sql,plan,run,meta).isEmpty(),"NORMAL维护目录仍有旧ACTIVE writer");return frozen;
        });}
    }
    private static final class Authority {
        private final Connection connection;private final String schema;private final Map<String,String> env;
        Authority(Connection connection,String schema,Map<String,String> env){if(!schema.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("authority schema非法");this.connection=connection;this.schema=schema;this.env=env;}
        String table(String name){return (char)96+schema+(char)96+"."+name;}
        @FunctionalInterface interface Row<T>{T map(ResultSet row)throws Exception;}
        @FunctionalInterface interface Work<T>{T run(Sql sql)throws Exception;}
        <T>T tx(Work<T> work)throws Exception{
            if(!connection.getAutoCommit())throw new SQLException("控制入口不能继承事务");
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);connection.setAutoCommit(false);boolean committing=false;
            try{Sql sql=new Sql();T result=work.run(sql);sql.left();committing=true;connection.commit();connection.setAutoCommit(true);return result;}
            catch(Exception failure){
                if(committing){try{connection.abort(Runnable::run);}catch(Exception ignored){}throw new SQLNonTransientConnectionException("控制COMMIT结局未知，保留原request_id","08007");}
                try{connection.rollback();connection.setAutoCommit(true);}catch(Exception cleanup){try{connection.abort(Runnable::run);}catch(Exception ignored){}throw new SQLNonTransientConnectionException("控制回滚/复位结局未知","08007");}
                throw failure;
            }
        }
        final class Sql {
            final long until=System.nanoTime()+30_000_000_000L;
            int left()throws SQLTimeoutException{long n=until-System.nanoTime();if(n<=0||Thread.currentThread().isInterrupted())throw new SQLTimeoutException("authority事务预算耗尽");return (int)Math.max(1,Math.min(3,(n+999_999_999L)/1_000_000_000L));}
            PreparedStatement statement(String text,Object...args)throws SQLException{var query=connection.prepareStatement(text);query.setQueryTimeout(left());for(int i=0;i<args.length;i++)query.setObject(i+1,args[i] instanceof ByteString b?b.toByteArray():args[i]);return query;}
            <T>List<T> list(String text,Row<T> map,Object...args)throws Exception{try(var query=statement(text,args);var rows=query.executeQuery()){List<T> result=new ArrayList<>();while(rows.next())result.add(map.map(rows));return result;}}
            <T>T one(String text,Row<T> map,Object...args)throws Exception{var rows=list(text,map,args);if(rows.size()>1)throw new SQLException("控制原件唯一性损坏");return rows.isEmpty()?null:rows.getFirst();}
            int update(String text,Object...args)throws SQLException{try(var query=statement(text,args)){return query.executeUpdate();}}
            long now()throws Exception{return one("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",r->u64(r,1));}
        }
        private static long u64(ResultSet row,int column)throws SQLException{try{BigInteger number=new BigInteger(row.getString(column));if(number.signum()<0||number.compareTo(U64)>0)throw new ArithmeticException();return number.longValue();}catch(RuntimeException invalid){throw new SQLException("uint64原件损坏",invalid);}}
        private static BigInteger number(long value){return new BigInteger(Long.toUnsignedString(value));}
        private static long next(long value)throws SQLException{if(value==-1L||value==0)throw new SQLException("epoch/revision不能溢出或从零开始");return value+1;}
        private record Meta(String generation,long epoch,long catalog,int writable,ByteString run){}
        private record Slot(ByteString writer,int slot,String generation,long epoch,int state){}
        Meta meta(Sql sql)throws Exception{
            Meta value=sql.one("SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id,fence_shards FROM "+table("route_meta")+" WHERE id=1 FOR UPDATE",r->{if(r.getInt(6)!=64)throw new SQLException("J meta必须保留64片契约");return new Meta(r.getString(1),u64(r,2),u64(r,3),r.getInt(4),ByteString.copyFrom(r.getBytes(5)));});
            if(value==null||value.epoch()==0||value.catalog()==0||value.writable()<0||value.writable()>1||value.run().size()!=0&&value.run().size()!=16)throw new SQLException("route meta损坏");uuid(value.generation());return value;
        }
        List<Slot> slots(Sql sql,ByteString writer)throws Exception{
            var all=sql.list("SELECT writer_key,slot,generation,writer_epoch,state FROM "+table("writer_slot")+(writer==null?" ORDER BY writer_key,slot FOR UPDATE":" WHERE writer_key=? ORDER BY slot FOR UPDATE"),
                    r->new Slot(ByteString.copyFrom(r.getBytes(1)),r.getInt(2),r.getString(3),u64(r,4),r.getInt(5)),writer==null?new Object[0]:new Object[]{writer});
            ByteString prior=ByteString.EMPTY;int count=0,writers=0;for(var slot:all){if(!prior.equals(slot.writer())){if(!prior.isEmpty())check(count==64,"writer缺片");prior=slot.writer();count=0;if(++writers>1024)throw new SQLException("writer目录超过有界维护容量");}check(slot.slot()==count++&&slot.epoch()!=0&&List.of(1,2).contains(slot.state()),"writer slot损坏");}if(!prior.isEmpty())check(count==64,"writer缺片");Map<ByteString,Slot> first=new HashMap<>();for(var slot:all){var original=first.putIfAbsent(slot.writer(),slot);if(original!=null)check(original.state()==slot.state()&&original.epoch()==slot.epoch()&&original.generation().equals(slot.generation()),"同writer的64片原件不一致");}return all;
        }
        private static void check(boolean truth,String reason)throws SQLException{if(!truth)throw new SQLException(reason,"45000");}
        private void request(SqlWriterLifecycleReceipt.Request request,SqlWriterLifecycleReceipt.Kind kind)throws Exception{
            noUnknown(request);size(request.getRequestId(),16);if(request.getKind()!=kind)throw new IllegalArgumentException("lifecycle kind不符");
            var writer=request.getWriter();size(writer.getWriterKey(),16);size(writer.getInstanceId(),16);uuid(writer.getGeneration());
            if(writer.getSqlPrincipal().isEmpty()||writer.getSqlPrincipal().size()>288||!writer.getSqlPrincipal().isValidUtf8()||writer.getState()!=SqlWriterRegistry.State.ACTIVE
                    ||!schema.equals(request.getAuthoritySchema())||request.getExpectedEpoch()==0||request.getDeploymentId().isBlank())throw new IllegalArgumentException("lifecycle原件形状不符");
            size(request.getDeploymentDigest(),32);
            sortedSchemas(request.getProtectedSchemasList());if(!request.getProtectedSchemasList().contains(schema))throw new IllegalArgumentException("保护集合缺authority");
            if(request.getMaintenanceRunId().size()!=0)size(request.getMaintenanceRunId(),16);
            if(kind==SqlWriterLifecycleReceipt.Kind.REGISTER){size(request.getLockQualificationSha256(),32);if(request.getLockPrimitive()!=SqlWriterBootGrant.LockPrimitive.SELECT_ONLY&&request.getLockPrimitive()!=SqlWriterBootGrant.LockPrimitive.LOCK_TOUCH)throw new IllegalArgumentException("没有真实I3资格的锁原语");}
        }
        /** 只折叠本轮经批准的永久退役；P0目录与revision原件始终不变。 */
        private List<ByteString> foldedWriters(Sql sql,SqlWriterMaintenanceReceipt.Plan plan,SqlWriterDrainRun run,Meta meta)throws Exception{
            var expected=new ArrayList<>(plan.getActiveWriterKeysList());
            check(expected.size()<=1024&&new HashSet<>(expected).size()==expected.size(),"P0 writer目录重复/过大");
            var sorted=new ArrayList<>(expected);sorted.sort(ByteString.unsignedLexicographicalComparator());check(sorted.equals(expected),"P0 writer目录顺序非法");
            var delta=number(meta.catalog()).subtract(number(plan.getCatalogRevision()));
            check(delta.signum()>=0&&delta.compareTo(BigInteger.valueOf(expected.size()))<=0,"catalog变化超出本轮永久退役界限");
            var receipts=sql.list("SELECT request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision FROM "+table("writer_lifecycle_receipt")+" WHERE catalog_revision>? AND catalog_revision<=? ORDER BY catalog_revision,request_id LIMIT "+(expected.size()+1),Authority::lifecycleRow,number(plan.getCatalogRevision()),number(meta.catalog()));
            check(receipts.size()==delta.intValueExact(),"目录revision存在缺口、重复或未知改变");
            long revision=plan.getCatalogRevision();Set<ByteString> retired=new HashSet<>();
            for(var receipt:receipts){
                var request=SqlWriterLifecycleReceipt.Request.parseFrom(receipt.getRequestProto());noUnknown(request);request(request,SqlWriterLifecycleReceipt.Kind.RETIRE);
                check(receipt.getKind()==SqlWriterLifecycleReceipt.Kind.RETIRE&&receipt.getGeneration().equals(run.getGeneration())&&receipt.getRequestId().equals(request.getRequestId())
                        &&receipt.getRequestDigest().equals(hash(request))&&receipt.getCatalogRevision()==next(revision)&&request.getExpectedCatalogRevision()==revision
                        &&request.getExpectedEpoch()==plan.getBeforeEpoch()&&receipt.getPreviousEpoch()==plan.getBeforeEpoch()&&receipt.getResultingEpoch()==plan.getBeforeEpoch()
                        &&request.getMaintenanceRunId().equals(run.getOperationId())&&request.getWriter().getGeneration().equals(run.getGeneration())
                        &&expected.contains(request.getWriter().getWriterKey())&&retired.add(request.getWriter().getWriterKey()),"不是同轮连续永久RETIRE原件");
                var state=sql.one("SELECT state,retirement_request_id,retirement_digest,instance_id,sql_principal,registration_digest FROM "+table("writer_registry")+" WHERE writer_key=?",r->List.of(r.getInt(1),ByteString.copyFrom(r.getBytes(2)),ByteString.copyFrom(r.getBytes(3)),ByteString.copyFrom(r.getBytes(4)),ByteString.copyFrom(r.getBytes(5)),ByteString.copyFrom(r.getBytes(6))),request.getWriter().getWriterKey());
                check(state!=null&&state.get(0).equals(2)&&state.get(1).equals(receipt.getRequestId())&&state.get(2).equals(receipt.getRequestDigest())&&state.get(3).equals(request.getWriter().getInstanceId())&&state.get(4).equals(request.getWriter().getSqlPrincipal())&&state.get(5).equals(request.getWriter().getRegistrationDigest()),"已退役registry不再匹配永久原件");
                revision=receipt.getCatalogRevision();
            }
            expected.removeIf(retired::contains);check(expected.equals(sqlActiveWriters()),"当前ACTIVE目录不是P0减同轮已批准退役等集");return List.copyOf(expected);
        }
        private static SqlWriterLifecycleReceipt lifecycleRow(ResultSet r)throws SQLException{
            return SqlWriterLifecycleReceipt.newBuilder().setRequestId(ByteString.copyFrom(r.getBytes(1))).setKindValue(r.getInt(2)).setGeneration(r.getString(3)).setRequestDigest(ByteString.copyFrom(r.getBytes(4))).setRequestProto(ByteString.copyFrom(r.getBytes(5))).setPreviousEpoch(u64(r,6)).setResultingEpoch(u64(r,7)).setCatalogRevision(u64(r,8)).build();
        }
        private List<Slot> lockActiveSlots(Sql sql,List<ByteString> keys)throws Exception{
            List<Slot> result=new ArrayList<>();for(var key:keys){var owned=slots(sql,key);check(owned.size()==64&&owned.stream().allMatch(s->s.state()==1),"预读ACTIVE writer不再是完整活动64片");result.addAll(owned);}return result;
        }
        private void checkedActiveSlots(List<Slot> slots,List<ByteString> keys,Meta meta)throws Exception{
            check(slots.size()==keys.size()*64&&slots.stream().map(Slot::writer).distinct().toList().equals(keys),"锁住的ACTIVE集合与提交点目录不同");
            for(var slot:slots)check(slot.state()==1&&slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch(),"ACTIVE片原E/generation不符");
        }
        private void retireClosure(Sql sql,SqlWriterDrainRun run,SqlWriterLifecycleReceipt.Request input,Meta meta)throws Exception{
            check(run!=null&&run.getOperationId().equals(meta.run())&&run.getGeneration().equals(meta.generation())&&(run.getPhaseValue()==3||run.getPhaseValue()==4),"只允许P1或P3按本轮闭合原件退役");
            var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(run.getManifestProto());noUnknown(plan);check(sha(run.getManifestProto().toByteArray()).equals(run.getManifestDigest()),"P0原件摘要损坏");
            check(foldedWriters(sql,plan,run,meta).contains(input.getWriter().getWriterKey()),"退役目标不是本轮未退役writer");
            var roots=plan.getRootIssuersList().stream().filter(r->r.getPhysicalWriterKey().equals(input.getWriter().getWriterKey())).toList();
            var relays=plan.getQueueMutatorsList().stream().filter(r->r.getPhysicalWriterKey().equals(input.getWriter().getWriterKey())).toList();
            check(!roots.isEmpty()||!relays.isEmpty(),"P0没有该writer的完整逻辑角色");check(relays.isEmpty()||run.getPhaseValue()==4,"兼relay的writer不能在P1退役");
            for(var role:java.util.stream.Stream.concat(roots.stream(),relays.stream()).toList())check(role.getInstanceId().equals(input.getWriter().getInstanceId()),"P0角色不是被退役boot");
            var root=permanentClosure(sql,run,3,3);var relay=relays.isEmpty()?null:permanentClosure(sql,run,4,5);
            check(root!=null,"缺少同轮ROOTS_CLOSED永久事实");closedRoles(root.getEvidence().getPublication(),roots);
            if(!relays.isEmpty()){check(relay!=null,"缺少同轮RELAY_QUIESCED永久事实");closedRoles(relay.getEvidence().getPublication(),relays);}
        }
        private static void closedRoles(SqlWriterMaintenanceReceipt.PublicationEvidence evidence,List<SqlWriterMaintenanceReceipt.IssuerClosure> expected)throws Exception{
            Map<String,SqlWriterMaintenanceReceipt.IssuerClosure> actual=new HashMap<>();for(var role:evidence.getLogicalRolesList())check(actual.put(roleKey(role),role)==null,"永久闭合角色重复");
            for(var seed:expected){var role=actual.get(roleKey(seed));check(role!=null&&seed(role).equals(seed)&&role.getAcceptingClosedMs()!=0&&role.getAcceptedSettledMs()!=0&&Long.compareUnsigned(role.getAcceptedSettledMs(),role.getAcceptingClosedMs())>=0&&role.getUnresolvedPublications()==0,"目标boot仍有接单/未定publication");size(role.getAcceptedFrontierDigest(),32);}
        }
        private static void sortedSchemas(List<String> schemas){String previous="";if(schemas.isEmpty()||schemas.size()>1024)throw new IllegalArgumentException("保护集合为空/过大");for(String schema:schemas){if(!schema.matches("[A-Za-z0-9_]{1,64}")||previous.compareTo(schema)>=0)throw new IllegalArgumentException("保护schema必须有序且无重复");previous=schema;}}
        private static ByteString lifecycleDigest(SqlWriterLifecycleReceipt.Request request)throws IOException{return hash(request.toBuilder().setWriter(request.getWriter().toBuilder().clearRegistrationDigest()).build());}
        SqlWriterLifecycleReceipt lifecycle(Sql sql,ByteString id)throws Exception{
            var receipt=sql.one("SELECT request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision FROM "+table("writer_lifecycle_receipt")+" WHERE request_id=?",Authority::lifecycleRow,id);
            if(receipt==null)return null;var request=SqlWriterLifecycleReceipt.Request.parseFrom(receipt.getRequestProto());noUnknown(request);request(request,receipt.getKind());
            check(request.getRequestId().equals(receipt.getRequestId())&&request.getWriter().getGeneration().equals(receipt.getGeneration())&&receipt.getRequestDigest().equals(receipt.getKind()==SqlWriterLifecycleReceipt.Kind.REGISTER?lifecycleDigest(request):hash(request)),"永久lifecycle收据全文或摘要损坏");return receipt;
        }
        void lifecycleInsert(Sql sql,SqlWriterLifecycleReceipt receipt)throws Exception{check(sql.update("INSERT INTO "+table("writer_lifecycle_receipt")+"(request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision) VALUES(?,?,?,?,?,?,?,?)",receipt.getRequestId(),receipt.getKindValue(),receipt.getGeneration(),receipt.getRequestDigest(),receipt.getRequestProto(),number(receipt.getPreviousEpoch()),number(receipt.getResultingEpoch()),number(receipt.getCatalogRevision()))==1,"lifecycle永久收据插入失败");}
        boolean foreignConsumed(SqlWriterLifecycleReceipt.Request wanted)throws Exception{
            request(wanted,SqlWriterLifecycleReceipt.Kind.REGISTER);
            return tx(sql->{
                var rows=sql.list("SELECT writer_key,instance_id,sql_principal,registration_digest FROM "+table("writer_registry")+" WHERE writer_key=? OR instance_id=? OR sql_principal=?",
                        r->List.of(ByteString.copyFrom(r.getBytes(1)),ByteString.copyFrom(r.getBytes(2)),ByteString.copyFrom(r.getBytes(3)),ByteString.copyFrom(r.getBytes(4))),wanted.getWriter().getWriterKey(),wanted.getWriter().getInstanceId(),wanted.getWriter().getSqlPrincipal());
                if(rows.isEmpty())return false;check(rows.size()==1,"roster身份存在非foreign冲突");var row=rows.getFirst();
                check(row.get(0).equals(wanted.getWriter().getWriterKey())&&row.get(1).equals(wanted.getWriter().getInstanceId())&&row.get(2).equals(wanted.getWriter().getSqlPrincipal()),"roster key/instance/principal发生不同原件冲突");
                var originals=sql.list("SELECT request_proto FROM "+table("writer_lifecycle_receipt")+" WHERE kind=1 AND request_digest=?",r->SqlWriterLifecycleReceipt.Request.parseFrom(r.getBytes(1)),row.get(3));check(originals.size()==1,"REGISTER永久原文缺失或不唯一");
                var old=originals.getFirst();var normalizedOld=old.toBuilder().clearDeploymentId().setWriter(old.getWriter().toBuilder().clearRegistrationDigest()).build();
                var normalizedWanted=wanted.toBuilder().clearDeploymentId().setWriter(wanted.getWriter().toBuilder().clearRegistrationDigest()).build();
                check(normalizedOld.equals(normalizedWanted),"不是同一roster entry的永久消费，不能选next");
                if(old.getDeploymentId().equals(wanted.getDeploymentId()))return false;
                check(!old.getDeploymentId().isBlank()&&lifecycleDigest(old).equals(row.get(3)),"foreign注册原件摘要不符");return true;
            });
        }
        long registeredMs(ByteString writer)throws Exception{return tx(sql->{Long stamp=sql.one("SELECT registered_ms FROM "+table("writer_registry")+" WHERE writer_key=?",r->u64(r,1),writer);check(stamp!=null&&stamp!=0,"缺少永久注册时刻");return stamp;});}
        void verifyPrepare(SqlWriterBootGrant grant)throws Exception{
            size(grant.getWriterKey(),16);size(grant.getInstanceId(),16);size(grant.getRegistrationDigest(),32);size(grant.getLockQualificationSha256(),32);uuid(grant.getGeneration());check(schema.equals(grant.getAuthoritySchema()),"prepare authority schema不符");
            Sql sql=new Sql();var requestId=sql.one("SELECT request_id FROM "+table("writer_lifecycle_receipt")+" WHERE kind=1 AND request_digest=?",r->ByteString.copyFrom(r.getBytes(1)),grant.getRegistrationDigest());check(requestId!=null,"prepare缺永久REGISTER原件");var receipt=lifecycle(sql,requestId);
            var request=SqlWriterLifecycleReceipt.Request.parseFrom(receipt.getRequestProto());var writer=request.getWriter();
            check(writer.getWriterKey().equals(grant.getWriterKey())&&writer.getInstanceId().equals(grant.getInstanceId())&&writer.getGeneration().equals(grant.getGeneration())&&writer.getSqlPrincipal().equals(grant.getSqlPrincipal())&&writer.getRegistrationDigest().equals(grant.getRegistrationDigest())
                    &&request.getAuthoritySchema().equals(grant.getAuthoritySchema())&&request.getProtectedSchemasList().equals(grant.getProtectedSchemasList())&&request.getDeploymentId().equals(grant.getDeploymentId())&&request.getDeploymentDigest().equals(grant.getPhysicalAuthoritySha256())&&request.getMaintenanceRunId().equals(grant.getMaintenanceRunId())&&request.getLockPrimitive()==grant.getLockPrimitive()&&request.getLockQualificationSha256().equals(grant.getLockQualificationSha256()),"prepare grant与永久REGISTER全文不同");
            var meta=sql.one("SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id FROM "+table("route_meta")+" WHERE id=1",r->new Meta(r.getString(1),u64(r,2),u64(r,3),r.getInt(4),ByteString.copyFrom(r.getBytes(5))));
            var run=run(sql,grant.getMaintenanceRunId(),false);check(meta!=null&&run!=null&&meta.writable()==1&&meta.run().equals(run.getOperationId())&&run.getPhase()==SqlWriterDrainRun.Phase.SQL_RELEASED&&meta.generation().equals(grant.getGeneration())&&request.getExpectedEpoch()==meta.epoch()&&receipt.getResultingEpoch()==meta.epoch(),"新boot只能在同轮SQL_RELEASED按新E准备");
            var release=receipt(sql,run.getLatestReceiptId());check(release!=null&&release.getReceiptDigest().equals(run.getLatestReceiptDigest())&&release.getKind()==SqlWriterMaintenanceReceipt.Kind.RELEASE&&release.getPhase()==SqlWriterMaintenanceReceipt.Phase.RELEASED&&release.getAfterEpoch()==meta.epoch()&&release.getRunId().equals(run.getOperationId()),"prepare缺本轮release永久事实");
            var registry=sql.one("SELECT state,generation,instance_id,sql_principal,registration_digest FROM "+table("writer_registry")+" WHERE writer_key=?",r->List.of(r.getInt(1),r.getString(2),ByteString.copyFrom(r.getBytes(3)),ByteString.copyFrom(r.getBytes(4)),ByteString.copyFrom(r.getBytes(5))),grant.getWriterKey());check(registry!=null&&registry.get(0).equals(1)&&registry.get(1).equals(grant.getGeneration())&&registry.get(2).equals(grant.getInstanceId())&&registry.get(3).equals(grant.getSqlPrincipal())&&registry.get(4).equals(grant.getRegistrationDigest()),"prepare主体非当前ACTIVE原件");
            var slots=sql.list("SELECT slot,generation,writer_epoch,state FROM "+table("writer_slot")+" WHERE writer_key=? ORDER BY slot",r->new Slot(grant.getWriterKey(),r.getInt(1),r.getString(2),u64(r,3),r.getInt(4)),grant.getWriterKey());check(slots.size()==64,"prepare缺完整64片");for(int i=0;i<64;i++){var slot=slots.get(i);check(slot.slot()==i&&slot.state()==1&&slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch(),"prepare slot不匹配当前E/世代");}
        }
        SqlWriterLifecycleReceipt register(SqlWriterLifecycleReceipt.Request input)throws Exception{
            request(input,SqlWriterLifecycleReceipt.Kind.REGISTER);ByteString digest=lifecycleDigest(input);
            var request=input.toBuilder().setWriter(input.getWriter().toBuilder().setRegistrationDigest(digest)).build();
            return tx(sql->{
                var prior=lifecycle(sql,request.getRequestId());if(prior!=null){check(prior.getKind()==SqlWriterLifecycleReceipt.Kind.REGISTER&&prior.getRequestDigest().equals(digest),"request_id已用于不同原件");return prior;}
                Meta meta=meta(sql);check(meta.generation().equals(request.getWriter().getGeneration())&&meta.epoch()==request.getExpectedEpoch()&&(request.getExpectedCatalogRevision()==0||meta.catalog()==request.getExpectedCatalogRevision())&&meta.writable()==1,"REGISTER世代/E/catalog或可写状态不符");
                if(request.getMaintenanceRunId().isEmpty())check(meta.run().isEmpty(),"P0后不能给新key续P2/P3");
                else{check(meta.run().equals(request.getMaintenanceRunId()),"prepare-only注册不属于当前run");var run=run(sql,meta.run(),true);check(run!=null&&run.getPhase()==SqlWriterDrainRun.Phase.SQL_RELEASED,"fresh主体仅允许同run SQL_RELEASED prepare-only");}
                long stamp=sql.now(),catalog=next(meta.catalog());var writer=request.getWriter();
                check(sql.update("INSERT INTO "+table("writer_registry")+"(writer_key,generation,instance_id,sql_principal,registration_digest,state,registered_ms,retirement_request_id,retirement_digest,retired_ms) VALUES(?,?,?,?,?,1,?,?,?,0)",writer.getWriterKey(),meta.generation(),writer.getInstanceId(),writer.getSqlPrincipal(),digest,number(stamp),ByteString.EMPTY,ByteString.EMPTY)==1,"注册插入失败，旧身份不能复活");
                for(int slot=0;slot<64;slot++)check(sql.update("INSERT INTO "+table("writer_slot")+"(writer_key,slot,generation,writer_epoch,state,lock_touch) VALUES(?,?,?,?,1,0)",writer.getWriterKey(),slot,meta.generation(),number(meta.epoch()))==1,"新writer分片插入失败");
                check(sql.update("UPDATE "+table("route_meta")+" SET catalog_revision=? WHERE id=1 AND catalog_revision=?",number(catalog),number(meta.catalog()))==1,"catalog CAS失败");
                var receipt=SqlWriterLifecycleReceipt.newBuilder().setRequestId(request.getRequestId()).setKind(SqlWriterLifecycleReceipt.Kind.REGISTER).setGeneration(meta.generation()).setRequestDigest(digest).setRequestProto(ByteString.copyFrom(canonical(request))).setPreviousEpoch(meta.epoch()).setResultingEpoch(meta.epoch()).setCatalogRevision(catalog).build();lifecycleInsert(sql,receipt);return receipt;
            });
        }
        SqlWriterLifecycleReceipt retire(SqlWriterLifecycleReceipt.Request input)throws Exception{
            request(input,SqlWriterLifecycleReceipt.Kind.RETIRE);check(input.getExpectedCatalogRevision()!=0,"RETIRE必须固定catalog");size(input.getWriter().getRegistrationDigest(),32);ByteString digest=hash(input);
            var request=input;
            return tx(sql->{
                var prior=lifecycle(sql,input.getRequestId());if(prior!=null){check(prior.getKind()==SqlWriterLifecycleReceipt.Kind.RETIRE&&prior.getRequestDigest().equals(digest),"RETIRE request_id冲突");return prior;}
                var slots=slots(sql,input.getWriter().getWriterKey());check(slots.size()==64,"退休writer必须精确64片");Meta meta=meta(sql);
                check(meta.generation().equals(input.getWriter().getGeneration())&&meta.epoch()==input.getExpectedEpoch()&&meta.catalog()==input.getExpectedCatalogRevision()&&meta.writable()==1,"RETIRE原件/E/catalog不符或SQL仍冻结");
                if(!meta.run().isEmpty()){check(meta.run().equals(input.getMaintenanceRunId()),"只能同run operator退休旧writer");retireClosure(sql,run(sql,meta.run(),true),input,meta);}else check(input.getMaintenanceRunId().isEmpty(),"运行中的retire不能借另一run补权");
                var identity=sql.one("SELECT instance_id,sql_principal,state,registration_digest FROM "+table("writer_registry")+" WHERE writer_key=? FOR UPDATE",r->List.of(ByteString.copyFrom(r.getBytes(1)),ByteString.copyFrom(r.getBytes(2)),r.getInt(3),ByteString.copyFrom(r.getBytes(4))),input.getWriter().getWriterKey());
                check(identity!=null&&identity.get(0).equals(input.getWriter().getInstanceId())&&identity.get(1).equals(input.getWriter().getSqlPrincipal())&&identity.get(2).equals(1)&&identity.get(3).equals(input.getWriter().getRegistrationDigest()),"只能永久退休同ACTIVE原件");
                for(var slot:slots)check(slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch()&&slot.state()==1,"退休分片状态/E非法");
                check(sql.update("UPDATE "+table("writer_slot")+" SET state=2 WHERE writer_key=? AND state=1",input.getWriter().getWriterKey())==64,"退休64片未完整生效");long stamp=sql.now(),catalog=next(meta.catalog());
                check(sql.update("UPDATE "+table("writer_registry")+" SET state=2,retirement_request_id=?,retirement_digest=?,retired_ms=? WHERE writer_key=? AND state=1",input.getRequestId(),digest,number(stamp),input.getWriter().getWriterKey())==1,"退休registry未生效");
                check(sql.update("UPDATE "+table("route_meta")+" SET catalog_revision=? WHERE id=1 AND catalog_revision=?",number(catalog),number(meta.catalog()))==1,"退休catalog CAS失败");
                var receipt=SqlWriterLifecycleReceipt.newBuilder().setRequestId(input.getRequestId()).setKind(SqlWriterLifecycleReceipt.Kind.RETIRE).setGeneration(meta.generation()).setRequestDigest(digest).setRequestProto(ByteString.copyFrom(canonical(request))).setPreviousEpoch(meta.epoch()).setResultingEpoch(meta.epoch()).setCatalogRevision(catalog).build();lifecycleInsert(sql,receipt);return receipt;
            });
        }
        private static ByteString intentDigest(SqlWriterMaintenanceReceipt input)throws IOException{
            // 只排除服务端持久结局；其余完整请求正文（含前序、原票据身份）都由摘要绑定。
            return hash(input.toBuilder().clearRequestDigest().clearReceiptDigest().clearCommittedMs().build());
        }
        private void intent(SqlWriterMaintenanceReceipt input)throws Exception{
            noUnknown(input);size(input.getRequestId(),16);size(input.getRunId(),16);uuid(input.getGeneration());
            check(input.getAttemptRevision()!=0&&input.getBeforeEpoch()!=0&&input.getCatalogRevision()!=0&&input.getCommittedMs()==0&&input.getAfterEpoch()==0&&input.getReceiptDigest().isEmpty(),"维护请求不得伪填服务端结局");
            if(!input.getRequestDigest().isEmpty())check(input.getRequestDigest().equals(intentDigest(input)),"维护请求摘要不同");
            check(input.getPlanProto().size()>0&&input.getPlanProto().size()<=16*1024*1024,"缺少完整typed维护Plan原文");
        }
        SqlWriterMaintenanceReceipt receipt(Sql sql,ByteString request)throws Exception{
            var result=sql.one("SELECT request_id,request_digest,run_id,generation,before_epoch,after_epoch,catalog_revision,phase,plan_proto,committed_ms,kind,run_phase,attempt_revision,prior_receipt_id,prior_receipt_digest,evidence,receipt_digest FROM "+table("sql_writer_maintenance_receipt")+" WHERE request_id=?",r->SqlWriterMaintenanceReceipt.newBuilder()
                    .setRequestId(ByteString.copyFrom(r.getBytes(1))).setRequestDigest(ByteString.copyFrom(r.getBytes(2))).setRunId(ByteString.copyFrom(r.getBytes(3))).setGeneration(r.getString(4))
                    .setBeforeEpoch(u64(r,5)).setAfterEpoch(u64(r,6)).setCatalogRevision(u64(r,7)).setPhaseValue(r.getInt(8)).setPlanProto(ByteString.copyFrom(r.getBytes(9)))
                    .setCommittedMs(u64(r,10)).setKindValue(r.getInt(11)).setRunPhaseValue(r.getInt(12)).setAttemptRevision(u64(r,13)).setPriorReceiptId(ByteString.copyFrom(r.getBytes(14)))
                    .setPriorReceiptDigest(ByteString.copyFrom(r.getBytes(15))).setEvidence(SqlWriterMaintenanceReceipt.Evidence.parseFrom(r.getBytes(16))).setReceiptDigest(ByteString.copyFrom(r.getBytes(17))).build(),request);
            if(result!=null){noUnknown(result);check(hash(result.toBuilder().clearReceiptDigest().build()).equals(result.getReceiptDigest()),"永久维护收据原件摘要损坏");check(intentDigest(result.toBuilder().clearAfterEpoch().build()).equals(result.getRequestDigest()),"永久收据不能还原原始完整请求摘要");}return result;
        }
        SqlWriterDrainRun run(Sql sql,ByteString id,boolean lock)throws Exception{return sql.one("SELECT operation_id,request_digest,generation,attempt_revision,manifest_proto,manifest_digest,phase,latest_receipt_digest,frozen_request_id,updated_ms,latest_receipt_id FROM "+table("sql_writer_drain_run")+" WHERE operation_id=?"+(lock?" FOR UPDATE":""),r->SqlWriterDrainRun.newBuilder()
                .setOperationId(ByteString.copyFrom(r.getBytes(1))).setRequestDigest(ByteString.copyFrom(r.getBytes(2))).setGeneration(r.getString(3)).setAttemptRevision(u64(r,4)).setManifestProto(ByteString.copyFrom(r.getBytes(5)))
                .setManifestDigest(ByteString.copyFrom(r.getBytes(6))).setPhaseValue(r.getInt(7)).setLatestReceiptDigest(ByteString.copyFrom(r.getBytes(8))).setFrozenRequestId(ByteString.copyFrom(r.getBytes(9))).setUpdatedMs(u64(r,10)).setLatestReceiptId(ByteString.copyFrom(r.getBytes(11))).build(),id);}
        SqlWriterFreezeTicket ticket(Sql sql,ByteString id)throws Exception{return sql.one("SELECT ticket_id,operation_id,attempt_revision,generation,manifest_digest,non_sql_receipt_digest,freeze_request_id,freeze_request_digest,not_before_ms,expires_ms,state,consumed_receipt_digest,revocation_digest,updated_ms,authorization_kind,grant_receipt_id,grant_receipt_digest FROM "+table("sql_writer_freeze_ticket")+" WHERE ticket_id=? FOR UPDATE",r->SqlWriterFreezeTicket.newBuilder()
                .setTicketId(ByteString.copyFrom(r.getBytes(1))).setOperationId(ByteString.copyFrom(r.getBytes(2))).setAttemptRevision(u64(r,3)).setGeneration(r.getString(4)).setManifestDigest(ByteString.copyFrom(r.getBytes(5))).setNonSqlReceiptDigest(ByteString.copyFrom(r.getBytes(6)))
                .setFreezeRequestId(ByteString.copyFrom(r.getBytes(7))).setFreezeRequestDigest(ByteString.copyFrom(r.getBytes(8))).setNotBeforeMs(u64(r,9)).setExpiresMs(u64(r,10)).setStateValue(r.getInt(11))
                .setConsumedReceiptDigest(ByteString.copyFrom(r.getBytes(12))).setRevocationDigest(ByteString.copyFrom(r.getBytes(13))).setUpdatedMs(u64(r,14)).setAuthorizationKindValue(r.getInt(15)).setGrantReceiptId(ByteString.copyFrom(r.getBytes(16))).setGrantReceiptDigest(ByteString.copyFrom(r.getBytes(17))).build(),id);}
        private void insertReceipt(Sql sql,SqlWriterMaintenanceReceipt receipt)throws Exception{
            check(sql.update("INSERT INTO "+table("sql_writer_maintenance_receipt")+"(request_id,request_digest,run_id,generation,before_epoch,after_epoch,catalog_revision,phase,plan_proto,committed_ms,kind,run_phase,attempt_revision,prior_receipt_id,prior_receipt_digest,evidence,receipt_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    receipt.getRequestId(),receipt.getRequestDigest(),receipt.getRunId(),receipt.getGeneration(),number(receipt.getBeforeEpoch()),number(receipt.getAfterEpoch()),number(receipt.getCatalogRevision()),receipt.getPhaseValue(),receipt.getPlanProto(),number(receipt.getCommittedMs()),receipt.getKindValue(),receipt.getRunPhaseValue(),number(receipt.getAttemptRevision()),receipt.getPriorReceiptId(),receipt.getPriorReceiptDigest(),canonical(receipt.getEvidence()),receipt.getReceiptDigest())==1,"永久维护收据只能INSERT");
        }
        private SqlWriterMaintenanceReceipt outcome(SqlWriterMaintenanceReceipt input,Meta meta,long after,long now)throws Exception{
            var complete=input.toBuilder().setRequestDigest(intentDigest(input)).setBeforeEpoch(meta.epoch()).setAfterEpoch(after).setCatalogRevision(meta.catalog()).setCommittedMs(now).clearReceiptDigest().build();
            return complete.toBuilder().setReceiptDigest(hash(complete)).build();
        }
        private void append(Sql sql,SqlWriterDrainRun run,SqlWriterMaintenanceReceipt receipt,ByteString frozen)throws Exception{
            insertReceipt(sql,receipt);
            check(sql.update("UPDATE "+table("sql_writer_drain_run")+" SET phase=?,latest_receipt_digest=?,latest_receipt_id=?,frozen_request_id=?,updated_ms=? WHERE operation_id=? AND attempt_revision=? AND latest_receipt_digest=?",
                    receipt.getRunPhaseValue(),receipt.getReceiptDigest(),receipt.getRequestId(),frozen,number(receipt.getCommittedMs()),run.getOperationId(),number(run.getAttemptRevision()),run.getLatestReceiptDigest())==1,"维护run原件CAS失败");
        }
        private SqlWriterMaintenanceReceipt known(Sql sql,SqlWriterMaintenanceReceipt input)throws Exception{
            var old=receipt(sql,input.getRequestId());if(old!=null)check(old.getRequestDigest().equals(intentDigest(input))&&old.getRunId().equals(input.getRunId())&&old.getKind()==input.getKind(),"维护request_id已用于另一份原件");return old;
        }
        private final Map<String,ByteString> verifiedPins=new HashMap<>();
        private final Map<String,byte[]> pinnedReports=new HashMap<>();
        private void preloadEvidence(SqlWriterMaintenanceReceipt input)throws Exception{
            verifiedPins.clear();pinnedReports.clear();preloadPins(input.getEvidence());
            var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(input.getPlanProto());preloadPins(plan);
            for(var pin:plan.getSourcePinsList()){
                captureReport(pin);byte[] raw=pinnedReport(pin);
                if(metadataSchema(raw).equals("SQL_DRAIN_MANUAL_KAFKA_BASELINE_V1")){var json=metadata(raw,"SQL_DRAIN_MANUAL_KAFKA_BASELINE_V1","catalog_sha256","observed_start_ms","observed_end_ms","assignments","catalog_source");var nested=read(json.get("catalog_source").toString().getBytes(StandardCharsets.UTF_8),SqlWriterMaintenanceReceipt.SourcePin.newBuilder()).build();noUnknown(nested);preloadPins(nested);captureReport(nested);}
                if(Path.of(pin.getAbsolutePath()).getFileName().toString().equals("manual-baseline.report.json")){var manual=read(raw,SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder()).build();noUnknown(manual);preloadPins(manual);}
            }
            if(input.getEvidence().hasNonSqlClosure()){
                var closure=input.getEvidence().getNonSqlClosure();for(var target:closure.getRedisTargetsList())for(var pin:target.getScanSourceEvidenceList())captureReport(pin);List<SqlWriterMaintenanceReceipt.SourcePin> manualPins=new ArrayList<>(closure.getSourceEvidenceList());for(var manual:closure.getManualConsumersList())manualPins.addAll(manual.getSourcePinsList());
                for(var pin:manualPins){captureReport(pin);if(Path.of(pin.getAbsolutePath()).getFileName().toString().equals("manual-closure.report.json")){var report=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder()).build();noUnknown(report);preloadPins(report);}}
            }
            if(input.getEvidence().hasPublication())for(var pin:input.getEvidence().getPublication().getSourceEvidenceList())captureReport(pin);
            if(input.getEvidence().hasReleaseReadiness()){
                var ready=input.getEvidence().getReleaseReadiness();if(ready.hasMaintenancePublication())for(var pin:ready.getMaintenancePublication().getSourceEvidenceList())captureReport(pin);
                for(var pin:ready.getRecoveryAuditList())if(Path.of(pin.getAbsolutePath()).getFileName().toString().equals("maintenance.domain.pb"))captureReport(pin);
            }
            if(input.getEvidence().hasPublicationReadiness()){
                var publication=input.getEvidence().getPublicationReadiness();
                for(var ready:publication.getPreparedBootsList()){var reportPins=ready.getSourcePinsList().stream().filter(pin->pin.getSha256().equals(ready.getConsumerReadyFrontierDigest())).toList();check(reportPins.size()==1,"每prepared boot必须唯一pin实际正式consumer报告，非consumer也须空组报告");for(var pin:reportPins){captureReport(pin);preloadPins(parseConsumerReadiness(pinnedReport(pin)));}}
                for(var pin:publication.getSourceOpenAcksList()){
                    captureReport(pin);var ack=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.SourceOpenAck.newBuilder()).build();noUnknown(ack);
                    var ready=publication.getPreparedBootsList().stream().filter(value->value.getIssuerId().equals(ack.getIssuerId())&&value.getLogicalRole().equals(ack.getLogicalRole())).toList();
                    check(ready.size()==1,"开放ACK必须唯一对应固定prepared role");
                    var requestPin=openRequestPin(pin,ack,ready.get(0));preloadPins(ack);if(requestPin!=null)captureReport(requestPin);
                }
            }
        }
        private void preloadPins(Message message)throws Exception{
            if(message instanceof SqlWriterMaintenanceReceipt.SourcePin pin){
                Path path=Path.of(pin.getAbsolutePath());size(pin.getSha256(),32);check(path.isAbsolute()&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path),"source pin必须是绝对普通原件");String key=path.normalize().toString();
                var previous=verifiedPins.get(key);if(previous!=null){check(previous.equals(pin.getSha256()),"同路径source pin摘要冲突");return;}
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long total=0,until=System.nanoTime()+10_000_000_000L;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] block=new byte[65536];int n;while((n=stream.read(block))!=-1){total+=n;check(total<=1024L*1024*1024&&System.nanoTime()<until,"source原件超过大小/时间审核界限");hash.update(block,0,n);}}
                check(MessageDigest.isEqual(hash.digest(),pin.getSha256().toByteArray()),"source原件已漂移");verifiedPins.put(key,pin.getSha256());return;
            }
            for(var field:message.getAllFields().entrySet())if(field.getKey().getJavaType()==Descriptors.FieldDescriptor.JavaType.MESSAGE){if(field.getKey().isRepeated())for(Object item:(List<?>)field.getValue())preloadPins((Message)item);else preloadPins((Message)field.getValue());}
        }
        private void captureReport(SqlWriterMaintenanceReceipt.SourcePin pin)throws Exception{
            byte[] bytes=file(Path.of(pin.getAbsolutePath()));check(sha(bytes).equals(pin.getSha256()),"应用报告与固定SHA不同");pinnedReports.put(Path.of(pin.getAbsolutePath()).normalize().toString(),bytes);
        }
        private byte[] pinnedReport(SqlWriterMaintenanceReceipt.SourcePin pin)throws Exception{byte[] bytes=pinnedReports.get(Path.of(pin.getAbsolutePath()).normalize().toString());check(bytes!=null&&sha(bytes).equals(pin.getSha256()),"缺少锁外固定的同bytes应用报告");return bytes;}
        private void pins(List<SqlWriterMaintenanceReceipt.SourcePin> pins)throws Exception{
            check(!pins.isEmpty(),"缺少真实source evidence");Set<String> seen=new HashSet<>();
            for(var pin:pins){Path path=Path.of(pin.getAbsolutePath());String key=path.normalize().toString();size(pin.getSha256(),32);check(path.isAbsolute()&&seen.add(key)&&pin.getSha256().equals(verifiedPins.get(key)),"缺少锁外固定SHA校验的source原件");}
        }
        private void plan(SqlWriterMaintenanceReceipt.Plan plan,Meta meta,List<Slot> slots)throws Exception{
            size(plan.getRunId(),16);size(plan.getPhysicalClusterIdentity(),32);sortedSchemas(plan.getProtectedSchemasList());
            check(schema.equals(plan.getAuthoritySchema())&&plan.getProtectedSchemasList().contains(schema)&&plan.getBeforeEpoch()==meta.epoch()&&plan.getCatalogRevision()==meta.catalog(),"P0物理/schema/E/catalog原件不同");
            ByteString physical=ByteString.copyFrom(HexFormat.of().parseHex(need(env,"MMORPG_SQL_PHYSICAL_AUTHORITY_SHA256")));
            check(plan.getPhysicalClusterIdentity().equals(physical),"P0未绑定独立真实physical原件");
            var active=slots.stream().filter(slot->slot.state()==1).map(Slot::writer).distinct().toList();
            var registry=sqlActiveWriters();check(registry.equals(active),"ACTIVE registry与完整64片目录不同");
            check(active.equals(plan.getActiveWriterKeysList())&&!active.isEmpty(),"P0必须固定完整ACTIVE writer目录且有序");
            for(var slot:slots)if(slot.state()==1)check(slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch(),"ACTIVE片原E/generation不同");
            check(plan.getRootIssuersCount()>0&&plan.getQueueMutatorsCount()>0&&plan.getSourcePinsCount()>0&&plan.getLaunchersCount()>0&&(plan.getKafkaPartitionsCount()>0||plan.getManualAssignmentsCount()>0)&&plan.getRedisTargetsCount()>0,"P0完整历史inventory不得缺类");
            identities(plan.getRootIssuersList(),active);identities(plan.getQueueMutatorsList(),active);validateMaintenancePlan(plan);validateManualPlan(plan);manualBaseline(plan);identities(plan.getMaintenanceIssuersList(),List.of());pins(plan.getSourcePinsList());
            Set<ByteString> covered=new HashSet<>();for(var role:java.util.stream.Stream.concat(plan.getRootIssuersList().stream(),plan.getQueueMutatorsList().stream()).toList())if(!role.getPhysicalWriterKey().isEmpty())covered.add(role.getPhysicalWriterKey());check(covered.equals(new HashSet<>(active)),"P0每个ACTIVE writer必须完整归属root或relay角色");
            Set<ByteString> launchers=new HashSet<>();for(var launcher:plan.getLaunchersList()){check(!launcher.getLauncherId().isEmpty()&&launchers.add(launcher.getLauncherId()),"launcher身份重复");size(launcher.getPermanentFenceId(),16);pins(launcher.getSourcePinsList());}
            Set<String> partitions=new HashSet<>();for(var partition:plan.getKafkaPartitionsList()){check(!partition.getClusterId().isEmpty()&&partition.getTopicId().size()==16&&!partition.getTopicId().equals(ByteString.copyFrom(new byte[16]))&&!partition.getTopicName().isBlank()&&!partition.getGroup().isBlank()&&partition.getProtectedCommittedFloor()>=0&&partition.getInitialEarliest()>=0&&partition.getInitialEarliest()<=partition.getProtectedCommittedFloor(),"Kafka P0历史边界未知");check(partitions.add(kafkaKey(partition)),"Kafka历史inventory重复");}
            Set<String> targets=new HashSet<>();for(var target:plan.getRedisTargetsList()){check(!target.getRole().isBlank()&&!target.getAddress().isBlank()&&target.getDb()>=0&&target.getServerRunId().size()==40&&target.getServerRunId().toStringUtf8().matches("[0-9a-f]{40}")&&target.getMaintenanceNonce().size()==16&&target.getObservationsCount()>0&&targets.add(target.getAddress()+"/"+target.getDb()),"Redis真实目标/nonce缺失或重复");}
            List<byte[]> redisSources=new ArrayList<>();for(var pin:metadataPins(plan.getSourcePinsList(),"SQL_DRAIN_REDIS_BASELINE_V1"))redisSources.add(pinnedReport(pin));validateRedisBaseline(plan,redisSources);
        }
        private List<SqlWriterMaintenanceReceipt.SourcePin> metadataPins(List<SqlWriterMaintenanceReceipt.SourcePin> pins,String schema)throws Exception{
            List<SqlWriterMaintenanceReceipt.SourcePin> found=new ArrayList<>();for(var pin:pins)if(metadataSchema(pinnedReport(pin)).equals(schema))found.add(pin);return found;
        }
        private SqlWriterMaintenanceReceipt.SourcePin kafkaBaseline(SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
            var catalogs=metadataPins(plan.getSourcePinsList(),"SQL_DRAIN_KAFKA_CATALOG_V2");check(catalogs.size()==1,"P0必须唯一完整Kafka物理目录原件");var catalogPin=catalogs.get(0);validateKafkaCatalog(pinnedReport(catalogPin),plan);
            var baselines=metadataPins(plan.getSourcePinsList(),"SQL_DRAIN_KAFKA_BASELINE_V2");check(baselines.size()==1,"P0必须唯一真实C0/L0基线");var json=metadata(pinnedReport(baselines.get(0)),"SQL_DRAIN_KAFKA_BASELINE_V2","catalog_sha256","observed_ms","partitions");check(unsignedJson(json,"observed_ms")!=0&&encodedBytes(json.get("catalog_sha256")).equals(catalogPin.getSha256())&&json.get("partitions").isArray()&&json.get("partitions").size()==plan.getKafkaPartitionsCount(),"P0 C0/L0未绑定原物理目录或分区全集");
            Map<String,SqlWriterMaintenanceReceipt.KafkaPartitionClosure> expected=new HashMap<>();for(var item:plan.getKafkaPartitionsList())check(expected.put(kafkaKey(item),item)==null,"P0 Kafka分区重复");for(var node:json.get("partitions")){var actual=read(node.toString().getBytes(StandardCharsets.UTF_8),SqlWriterMaintenanceReceipt.KafkaPartitionClosure.newBuilder()).build();noUnknown(actual);var original=expected.remove(kafkaKey(actual));check(original!=null&&actual.getInitialEarliest()==original.getInitialEarliest()&&actual.getProtectedCommittedFloor()==original.getProtectedCommittedFloor()&&actual.getInitialEarliest()>=0&&actual.getProtectedCommittedFloor()>=actual.getInitialEarliest()&&actual.getFinalExclusiveEnd()>=actual.getProtectedCommittedFloor()&&(actual.getFinalCommitted()==actual.getProtectedCommittedFloor()||actual.getFinalCommitted()==-1&&actual.getInitialEarliest()==0&&actual.getProtectedCommittedFloor()==0&&actual.getFinalExclusiveEnd()==0),"P0 C0/L0不是实际原件或已有retention缺口");}check(expected.isEmpty(),"P0 Kafka基线遗漏分区");return catalogPin;
        }
        private SqlWriterMaintenanceReceipt.IssuerClosure manualSource(SqlWriterMaintenanceReceipt.Plan plan,SqlWriterMaintenanceReceipt.SourcePin pin,SqlWriterMaintenanceReceipt.ManualConsumerEvidence report,String basename)throws Exception{
            check(report.getAssignmentsCount()>0&&Path.of(pin.getAbsolutePath()).getFileName().toString().equals(basename),"manual原生报告缺实际assignment或固定文件名");var first=report.getAssignments(0);var sources=plan.getRootIssuersList().stream().filter(seed->seed.getIssuerId().equals(first.getIssuerId())&&seed.getInstanceId().equals(first.getInstanceId())).toList();check(sources.size()==1,"manual原生报告不是唯一P0 SOURCE");var source=sources.get(0);var sourcePins=new HashSet<>(source.getSourceEvidenceList());check(sourcePins.size()==source.getSourceEvidenceCount()&&report.getSourcePinsCount()==sourcePins.size()&&new HashSet<>(report.getSourcePinsList()).equals(sourcePins),"manual原生报告替换了P0固定SOURCE证据");Path directory=Path.of(pin.getAbsolutePath()).normalize().getParent();check(source.getSourceEvidenceList().stream().anyMatch(value->directory.equals(Path.of(value.getAbsolutePath()).normalize().getParent())),"manual原生报告离开P0固定私有目录");for(var item:report.getAssignmentsList())check(item.getIssuerId().equals(source.getIssuerId())&&item.getInstanceId().equals(source.getInstanceId()),"manual一个原件混入多个boot");pins(report.getSourcePinsList());return source;
        }
        private void manualBaseline(SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
            var mainCatalog=kafkaBaseline(plan);Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> expected=new HashMap<>();for(var item:plan.getManualAssignmentsList())expected.put(manualKey(item),item);Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> remaining=new HashMap<>(expected);
            for(var pin:plan.getSourcePinsList())if(Path.of(pin.getAbsolutePath()).getFileName().toString().equals("manual-baseline.report.json")){
                var report=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder()).build();noUnknown(report);check(report.getOperationId().equals(plan.getRunId())&&report.getManifestDigest().isEmpty(),"P0 manual原件必须同run且manifest空，不能摘要自引用");manualSource(plan,pin,report,"manual-baseline.report.json");for(var item:report.getAssignmentsList())check(item.equals(remaining.remove(manualKey(item))),"P0 manual目录不是同bytes实际baseline或重复");
            }
            check(remaining.isEmpty(),"P0遗漏manual实际baseline pin");var samples=metadataPins(plan.getSourcePinsList(),"SQL_DRAIN_MANUAL_KAFKA_BASELINE_V1");check(samples.size()==(expected.isEmpty()?0:1),"P0 manual leader基线缺失或重复");if(expected.isEmpty())return;
            var json=metadata(pinnedReport(samples.get(0)),"SQL_DRAIN_MANUAL_KAFKA_BASELINE_V1","catalog_sha256","observed_start_ms","observed_end_ms","assignments","catalog_source");metadataWindow(json);long start=unsignedJson(json,"observed_start_ms"),end=unsignedJson(json,"observed_end_ms");var catalogPin=read(json.get("catalog_source").toString().getBytes(StandardCharsets.UTF_8),SqlWriterMaintenanceReceipt.SourcePin.newBuilder()).build();noUnknown(catalogPin);pins(List.of(catalogPin));check(encodedBytes(json.get("catalog_sha256")).equals(catalogPin.getSha256())&&stableKafkaCatalogDigest(pinnedReport(catalogPin)).equals(stableKafkaCatalogDigest(pinnedReport(mainCatalog))),"manual/group P0物理目录不同");check(json.get("assignments").isArray()&&json.get("assignments").size()==expected.size(),"P0 manual leader分区全集不同");
            for(var node:json.get("assignments")){var actual=read(node.toString().getBytes(StandardCharsets.UTF_8),SqlWriterMaintenanceReceipt.ManualConsumerAssignment.newBuilder()).build();noUnknown(actual);var original=expected.remove(manualKey(actual));check(original!=null&&actual.getEarliest()>=original.getEarliest()&&actual.getEarliest()<=original.getPosition()&&actual.getExclusiveEnd()>=original.getExclusiveEnd()&&Long.compareUnsigned(actual.getObservedMs(),original.getObservedMs())>=0&&Long.compareUnsigned(actual.getObservedMs(),start)>=0&&Long.compareUnsigned(actual.getObservedMs(),end)<=0,"P0 manual leader位置/保留范围或观察窗口不符");check(actual.toBuilder().setEarliest(original.getEarliest()).setExclusiveEnd(original.getExclusiveEnd()).setObservedMs(original.getObservedMs()).build().equals(original),"P0 leader修改了原生position/配置");}check(expected.isEmpty(),"P0 manual leader遗漏分区");
        }
        private void kafkaClosureSources(SqlWriterMaintenanceReceipt.Plan plan,SqlWriterMaintenanceReceipt.NonSqlClosureEvidence closure)throws Exception{
            var original=kafkaBaseline(plan);ByteString digest=stableKafkaCatalogDigest(pinnedReport(original));check(digest.equals(closure.getHistoricalInventoryDigest()),"非SQL历史inventory摘要不是原完整Kafka稳定目录");var catalogs=metadataPins(closure.getSourceEvidenceList(),"SQL_DRAIN_KAFKA_CATALOG_V2");check(catalogs.size()==(plan.getManualAssignmentsCount()==0?2:4),"P4缺group/manual各自首尾实际目录");Set<String> seen=new HashSet<>();for(var pin:catalogs){var json=validateKafkaCatalog(pinnedReport(pin),plan);check(seen.add(Path.of(pin.getAbsolutePath()).normalize().toString())&&stableKafkaCatalogDigest(pinnedReport(pin)).equals(digest)&&Long.compareUnsigned(unsignedJson(json,"observed_start_ms"),closure.getObservedStartMs())>=0&&Long.compareUnsigned(unsignedJson(json,"observed_end_ms"),closure.getObservedEndMs())<=0,"P4物理目录/有效保留配置变化、重复或不在实际窗口");}
        }
        private void manualClosureSources(SqlWriterMaintenanceReceipt.Plan plan,SqlWriterMaintenanceReceipt.NonSqlClosureEvidence closure,ByteString manifest)throws Exception{
            kafkaClosureSources(plan,closure);var leaders=metadataPins(closure.getSourceEvidenceList(),"SQL_DRAIN_MANUAL_KAFKA_LEADER_V1");check(leaders.size()==(plan.getManualAssignmentsCount()==0?0:1),"P4 manual leader原件缺失或重复");if(plan.getManualAssignmentsCount()==0)return;var leaderPin=leaders.get(0);byte[] leaderRaw=pinnedReport(leaderPin);var leader=strictJson(leaderRaw);long start=unsignedJson(leader,"observed_start_ms");var sampled=validateManualLeaderPublication(leaderRaw,leaderPin,closure.getManualConsumersList(),closure.getObservedStartMs(),closure.getObservedEndMs());validateManualClosure(plan,manifest,sampled,false);
            Set<ByteString> catalogDigests=new HashSet<>();for(var pin:metadataPins(closure.getSourceEvidenceList(),"SQL_DRAIN_KAFKA_CATALOG_V2"))catalogDigests.add(pin.getSha256());check(catalogDigests.contains(encodedBytes(leader.get("catalog_first_sha256")))&&catalogDigests.contains(encodedBytes(leader.get("catalog_last_sha256"))),"manual leader未绑定实际首尾catalog原bytes");
            Set<String> files=new HashSet<>();Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> expected=new HashMap<>();for(var item:plan.getManualAssignmentsList())expected.put(manualKey(item),item);List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> originals=new ArrayList<>();
            for(var actual:sampled){var pin=actual.getSourcePins(0);check(closure.getSourceEvidenceList().contains(pin)&&files.add(Path.of(pin.getAbsolutePath()).normalize().toString()),"P3 pin未进入P4完整source目录或重复");var prior=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.ManualConsumerEvidence.newBuilder()).build();noUnknown(prior);manualSource(plan,pin,prior,"manual-closure.report.json");check(manualClosedProjection(actual).equals(manualClosedProjection(prior)),"P4 manual原身份/位置/start/revision/计数不同于实际P3报告");Map<String,SqlWriterMaintenanceReceipt.ManualConsumerAssignment> previous=new HashMap<>();for(var item:prior.getAssignmentsList())check(previous.put(manualKey(item),item)==null,"P3分区重复");for(var item:actual.getAssignmentsList()){var old=previous.remove(manualKey(item));var base=expected.remove(manualKey(item));check(old!=null&&base!=null&&Long.compareUnsigned(old.getObservedMs(),start)<=0&&item.getEarliest()>=old.getEarliest()&&item.getEarliest()<=base.getPosition()&&item.getPosition()==item.getExclusiveEnd(),"P3/P4位置、保留范围或时间窗口变化");}check(previous.isEmpty(),"P4遗漏P3分区");originals.add(prior);}
            check(expected.isEmpty(),"P4遗漏P0 manual owner");validateManualClosure(plan,manifest,originals,false);
        }
        private List<ByteString> sqlActiveWriters()throws SQLException{
            try(var query=connection.prepareStatement("SELECT writer_key FROM "+table("writer_registry")+" WHERE state=1 ORDER BY writer_key")){query.setQueryTimeout(3);try(var rows=query.executeQuery()){List<ByteString> values=new ArrayList<>();while(rows.next()){if(values.size()>=1024)throw new SQLException("ACTIVE writer目录过大");values.add(ByteString.copyFrom(rows.getBytes(1)));}return values;}}
        }
        private void identities(List<SqlWriterMaintenanceReceipt.IssuerClosure> roles,List<ByteString> writers)throws Exception{
            check(roles.size()<=1024,"单类逻辑角色超过有界维护容量");Set<String> identities=new HashSet<>();for(var role:roles){check(!role.getIssuerId().isEmpty()&&!role.getLogicalRole().isBlank()&&identities.add(roleKey(role)),"逻辑role/boot目录重复或缺失");size(role.getInstanceId(),16);size(role.getPermanentRevivalFenceId(),16);
                if(!role.getPhysicalWriterKey().isEmpty())check(writers.contains(role.getPhysicalWriterKey()),"role关联了P0之外SQL writer");pins(role.getSourceEvidenceList());
                check(role.getAcceptingClosedMs()==0&&role.getAcceptedSettledMs()==0&&role.getAcceptedFrontierDigest().isEmpty()&&role.getUnresolvedPublications()==0,"P0不能预填未来闭合结论");
            }
        }
        private static String roleKey(SqlWriterMaintenanceReceipt.IssuerClosure role){return Base64.getEncoder().encodeToString(role.getIssuerId().toByteArray())+"/"+Base64.getEncoder().encodeToString(role.getInstanceId().toByteArray())+"/"+role.getLogicalRole();}
        private static String kafkaKey(SqlWriterMaintenanceReceipt.KafkaPartitionClosure part){return part.getClusterId().toStringUtf8()+"/"+Base64.getEncoder().encodeToString(part.getTopicId().toByteArray())+"/"+part.getTopicName()+"/"+part.getGroup()+"/"+Integer.toUnsignedString(part.getPartition());}
        private static String redisKey(SqlWriterMaintenanceReceipt.RedisTargetClosure target){return target.getRole()+"/"+target.getAddress()+"/"+target.getDb();}
        private static SqlWriterMaintenanceReceipt.IssuerClosure seed(SqlWriterMaintenanceReceipt.IssuerClosure role){return role.toBuilder().clearAcceptingClosedMs().clearAcceptedSettledMs().clearAcceptedFrontierDigest().clearUnresolvedPublications().build();}
        private void publication(SqlWriterMaintenanceReceipt.PublicationEvidence evidence,List<SqlWriterMaintenanceReceipt.IssuerClosure> expected)throws Exception{
            var inventory=SqlWriterMaintenanceReceipt.PublicationEvidence.newBuilder().addAllLogicalRoles(expected).build();
            check(evidence.getIssuerInventoryDigest().equals(hash(inventory))&&evidence.getLogicalRolesCount()==expected.size(),"publication完整目录摘要不同");
            Map<String,SqlWriterMaintenanceReceipt.IssuerClosure> required=new LinkedHashMap<>();for(var role:expected)required.put(roleKey(role),role);
            for(var actual:evidence.getLogicalRolesList()){
                var original=required.remove(roleKey(actual));check(original!=null&&seed(actual).equals(original)&&actual.getAcceptingClosedMs()!=0&&Long.compareUnsigned(actual.getAcceptedSettledMs(),actual.getAcceptingClosedMs())>=0&&actual.getAcceptedSettledMs()!=0&&actual.getUnresolvedPublications()==0,"role未真实cut/settled，不能用TTL或进程gone替代");size(actual.getAcceptedFrontierDigest(),32);
            }
            check(required.isEmpty(),"publication遗漏P0角色");pins(evidence.getSourceEvidenceList());
            Map<String,SqlWriterMaintenanceReceipt.IssuerClosure> reports=new HashMap<>();
            for(var pin:evidence.getSourceEvidenceList()){
                var report=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.IssuerClosure.newBuilder()).build();noUnknown(report);
                check(reports.put(roleKey(report),report)==null,"重复真实application report");
            }
            check(reports.size()==evidence.getLogicalRolesCount(),"外层pin不是完整application报告等集");for(var actual:evidence.getLogicalRolesList())check(actual.equals(reports.get(roleKey(actual))),"logicalRoles与pinned真实application报告全文不同");
        }
        /** 旧attempt的根/relay只能沿本轮永久链复用；本实现从未给旧boot提供重开入口。 */
        private SqlWriterMaintenanceReceipt permanentClosure(Sql sql,SqlWriterDrainRun run,int phase,int kind)throws Exception{
            ByteString id=run.getLatestReceiptId(),digest=run.getLatestReceiptDigest();SqlWriterMaintenanceReceipt newer=null;
            for(int depth=0;depth<4096;depth++){
                var item=receipt(sql,id);check(item!=null&&item.getReceiptDigest().equals(digest)&&item.getRunId().equals(run.getOperationId())&&item.getGeneration().equals(run.getGeneration())&&item.getPlanProto().equals(run.getManifestProto())&&item.getAttemptRevision()!=0&&Long.compareUnsigned(item.getAttemptRevision(),run.getAttemptRevision())<=0,"永久闭合链跨run、manifest或未知attempt");
                if(newer!=null&&newer.getAttemptRevision()!=item.getAttemptRevision())check(newer.getKind()==SqlWriterMaintenanceReceipt.Kind.PHASE_EVENT&&newer.getRunPhaseValue()==4&&item.getRunPhaseValue()==11&&newer.getAttemptRevision()==next(item.getAttemptRevision()),"旧attempt闭合缺少ABORT到同cut重排空永久边");
                check(item.getRunPhaseValue()!=10&&item.getRunPhaseValue()!=9,"已开放/释放的boot不能复用旧cut");
                if(item.getRunPhaseValue()==phase&&item.getKindValue()==kind)return item;
                if(item.getRunPhaseValue()==1&&item.getKind()==SqlWriterMaintenanceReceipt.Kind.PHASE_EVENT)return null;
                check(depth<4095&&!item.getPriorReceiptId().isEmpty(),"永久链超出4096条审核界限或中断");newer=item;id=item.getPriorReceiptId();digest=item.getPriorReceiptDigest();
            }
            throw new SQLException("永久闭合链未到P0");
        }
        private SqlWriterMaintenanceReceipt referenced(Sql sql,ByteString id,ByteString digest,SqlWriterDrainRun run,int phase,int kind)throws Exception{
            size(id,16);size(digest,32);var receipt=receipt(sql,id);boolean permanent=phase==3&&kind==3||phase==4&&kind==5;
            check(receipt!=null&&receipt.getReceiptDigest().equals(digest)&&receipt.getRunId().equals(run.getOperationId())&&receipt.getGeneration().equals(run.getGeneration())&&receipt.getPlanProto().equals(run.getManifestProto())&&receipt.getRunPhaseValue()==phase&&receipt.getKindValue()==kind,"阶段引用不是同run/manifest永久原件");
            if(permanent){var actual=permanentClosure(sql,run,phase,kind);check(actual!=null&&actual.getRequestId().equals(id)&&actual.getReceiptDigest().equals(digest),"根/relay必须引用永久链上最新闭合，不能跨重开复用");}
            else check(receipt.getAttemptRevision()==run.getAttemptRevision(),"票据与非SQL事实必须属于当前attempt");return receipt;
        }
        private void nonSql(Sql sql,SqlWriterMaintenanceReceipt.NonSqlClosureEvidence evidence,SqlWriterDrainRun run,SqlWriterMaintenanceReceipt.Plan plan)throws Exception{
            referenced(sql,evidence.getRootClosedReceiptId(),evidence.getRootClosedReceiptDigest(),run,3,3);
            var relay=referenced(sql,evidence.getRelayQuiescedReceiptId(),evidence.getRelayQuiescedReceiptDigest(),run,4,5);
            check(relay.getEvidence().getPublication().getLogicalRolesList().equals(evidence.getQueueMutatorsList()),"P2 relay全文与真实QUIESCED原件不同");
            check(evidence.getHistoricalInventoryDigest().size()==32&&evidence.getObservedStartMs()!=0&&Long.compareUnsigned(evidence.getObservedEndMs(),evidence.getObservedStartMs())>=0,"非SQL闭合缺完整历史inventory或真实观察窗口");
            check(evidence.getKafkaPartitionsCount()==plan.getKafkaPartitionsCount()&&evidence.getRedisTargetsCount()==plan.getRedisTargetsCount(),"非SQL历史目标/分区遗漏");
            Map<String,SqlWriterMaintenanceReceipt.KafkaPartitionClosure> partitions=new HashMap<>();for(var original:plan.getKafkaPartitionsList())check(partitions.put(kafkaKey(original),original)==null,"Kafka P0重复");
            for(var actual:evidence.getKafkaPartitionsList()){
                var original=partitions.remove(kafkaKey(actual));check(original!=null&&actual.getProtectedCommittedFloor()==original.getProtectedCommittedFloor()&&actual.getInitialEarliest()==original.getInitialEarliest()
                        &&actual.getFinalEarliest()>=original.getInitialEarliest()&&actual.getFinalEarliest()<=actual.getProtectedCommittedFloor()&&actual.getFinalExclusiveEnd()>=actual.getProtectedCommittedFloor()&&actual.getFinalCommitted()==actual.getFinalExclusiveEnd(),"Kafka历史数据丢失、lag/未知提交或分区身份不同");
            }
            check(partitions.isEmpty(),"遗漏Kafka历史分区");
            Map<String,SqlWriterMaintenanceReceipt.RedisTargetClosure> targets=new HashMap<>();for(var target:plan.getRedisTargetsList())targets.put(redisKey(target),target);
            for(var actual:evidence.getRedisTargetsList()){
                var original=targets.remove(redisKey(actual));check(original!=null&&actual.getServerRunId().equals(original.getServerRunId())&&actual.getMaintenanceNonce().equals(original.getMaintenanceNonce())&&actual.getZonesList().equals(original.getZonesList()),"Redis物理目标/DB/runid/nonce或zone全集不同");
                pins(actual.getScanSourceEvidenceList());check(actual.getScanSourceEvidenceCount()==1,"P4 Redis目标必须唯一实际SCAN原件");var scanned=validateRedisObservationSource(pinnedReport(actual.getScanSourceEvidence(0)),plan.getRunId(),original,false,evidence.getObservedStartMs(),evidence.getObservedEndMs());Set<String> keys=new HashSet<>();
                for(var key:actual.getObservationsList()){check(keys.add(key.getKey())&&!key.getKind().isBlank()&&!key.getRedisType().isBlank(),"Redis key目录重复/类型未知");size(key.getDumpSha256(),32);
                    check(key.getCount()==0&&key.getMembersCount()==0&&key.getValuesCount()==0&&key.getPttlMs()==-2&&key.getRedisType().equals("none"),"Redis仍有受保护命令、实际类型或未知内容；不能按TTL猜排空");}
                check(actual.getObservationsCount()>0,"Redis不能提交空观察冒充完整64桶/动态scan");
                Set<String> expectedKeys=new HashSet<>();for(var key:original.getObservationsList())expectedKeys.add(key.getKey());expectedKeys.addAll(scanned);check(keys.equals(expectedKeys),"Redis观察不是P0固定目录与实际SCAN结果的精确并集");
            }
            check(targets.isEmpty(),"遗漏Redis物理目标");pins(evidence.getSourceEvidenceList());
            validateManualClosure(plan,run.getManifestDigest(),evidence.getManualConsumersList(),false);for(var manual:evidence.getManualConsumersList())pins(manual.getSourcePinsList());manualClosureSources(plan,evidence,run.getManifestDigest());
        }
        private List<ByteString> previewWriters(SqlWriterMaintenanceReceipt input)throws Exception{
            if(!connection.getAutoCommit())throw new SQLException("预读目录不能继承事务");
            if(input.getRunPhaseValue()==1||input.getRunPhaseValue()==10)return sqlActiveWriters();
            Sql sql=new Sql();var meta=sql.one("SELECT generation,writer_epoch,catalog_revision,writable,freeze_run_id FROM "+table("route_meta")+" WHERE id=1",r->new Meta(r.getString(1),u64(r,2),u64(r,3),r.getInt(4),ByteString.copyFrom(r.getBytes(5))));
            var run=run(sql,input.getRunId(),false);check(meta!=null&&run!=null&&meta.run().equals(input.getRunId())&&run.getManifestProto().equals(input.getPlanProto()),"缺少同轮固定目录预读原件");
            return foldedWriters(sql,SqlWriterMaintenanceReceipt.Plan.parseFrom(run.getManifestProto()),run,meta);
        }
        private void sameRunIdentity(SqlWriterMaintenanceReceipt input,SqlWriterDrainRun run,Meta meta)throws Exception{
            check(run!=null&&input.getRunId().equals(run.getOperationId())&&input.getAttemptRevision()==run.getAttemptRevision()&&input.getGeneration().equals(run.getGeneration())&&meta.generation().equals(run.getGeneration())&&meta.run().equals(run.getOperationId()),"run/attempt/meta原件不同");
            check(input.getBeforeEpoch()==meta.epoch()&&input.getCatalogRevision()==meta.catalog(),"维护E/catalog不符");
            check(input.getPlanProto().equals(run.getManifestProto())&&sha(run.getManifestProto().toByteArray()).equals(run.getManifestDigest()),"P0 manifest原bytes不可修改");
            check(input.getEvidence().getOperationId().equals(run.getOperationId())&&input.getEvidence().getManifestDigest().equals(run.getManifestDigest()),"Evidence未绑定固定manifest");
        }
        private void sameRun(SqlWriterMaintenanceReceipt input,SqlWriterDrainRun run,Meta meta)throws Exception{
            sameRunIdentity(input,run,meta);check(input.getPriorReceiptId().equals(run.getLatestReceiptId())&&input.getPriorReceiptDigest().equals(run.getLatestReceiptDigest()),"维护永久前序不是当前run最新事实");
        }
        private void catalogue(Sql sql,SqlWriterDrainRun run,Meta meta,List<Slot> locked)throws Exception{
            var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(run.getManifestProto());noUnknown(plan);
            long epoch=plan.getBeforeEpoch();if(run.getPhaseValue()==7||run.getPhaseValue()==8||run.getPhaseValue()==12)epoch=next(epoch);
            check(meta.epoch()==epoch,"目录快照E与本轮阶段不同");checkedActiveSlots(locked,foldedWriters(sql,plan,run,meta),meta);
        }
        private void releaseReadiness(Sql sql,SqlWriterMaintenanceReceipt.ReleaseReadiness ready,SqlWriterDrainRun run,Meta meta)throws Exception{
            check(ready.getRouteWatermarkFloorMs()==0,"J无R reconcile水位，不能接收另一实现的释放语义");
            var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(run.getManifestProto());validateMaintenancePublication(plan,ready);
            if(plan.getMaintenanceIssuersCount()>0)publication(ready.getMaintenancePublication(),plan.getMaintenanceIssuersList());
            var frozen=referenced(sql,ready.getOriginalFreezeReceiptId(),ready.getOriginalFreezeReceiptDigest(),run,7,1);
            check(frozen.getPhase()==SqlWriterMaintenanceReceipt.Phase.FROZEN&&frozen.getRequestId().equals(run.getFrozenRequestId())&&frozen.getPlanProto().equals(run.getManifestProto())&&frozen.getAfterEpoch()==meta.epoch(),"恢复审计必须引用本轮原NORMAL freeze");
            if(ready.hasMaintenancePublication())for(var role:ready.getMaintenancePublication().getLogicalRolesList())check(Long.compareUnsigned(role.getAcceptingClosedMs(),frozen.getCommittedMs())>=0,"维护工具闭合早于本轮原freeze，不能复用冻结前报告");
            var closed=referenced(sql,ready.getNonSqlClosedReceiptId(),ready.getNonSqlClosedReceiptDigest(),run,5,3);
            check(closed.getPlanProto().equals(run.getManifestProto())&&closed.getEvidence().hasNonSqlClosure(),"恢复审计缺原非SQL闭合事实");
            ByteString id=sql.one("SELECT ticket_id FROM "+table("sql_writer_freeze_ticket")+" WHERE freeze_request_id=?",r->ByteString.copyFrom(r.getBytes(1)),frozen.getRequestId());check(id!=null,"缺原正常冻结ticket");
            var ticket=ticket(sql,id);check(ticket.getState()==SqlWriterFreezeTicket.State.CONSUMED&&ticket.getAuthorizationKind()==SqlWriterFreezeTicket.AuthorizationKind.NORMAL_DRAIN_FREEZE&&ticket.getConsumedReceiptDigest().equals(frozen.getReceiptDigest())&&ticket.getNonSqlReceiptDigest().equals(closed.getReceiptDigest()),"紧急HOLD或不同闭合收据不能release");
            pins(ready.getRecoveryAuditList());
            if(plan.getMaintenanceIssuersCount()>0){
                var audits=ready.getRecoveryAuditList().stream().filter(pin->Path.of(pin.getAbsolutePath()).getFileName().toString().equals("maintenance.domain.pb")).toList();check(audits.size()==1,"当前J维护发行者须有唯一最终MergeZone域审核原件");
                var expected=read(pinnedReport(audits.get(0)),com.game.data.merge.pb.ZoneMergeRunRow.newBuilder()).build();noUnknown(expected);
                ByteBuffer operationBytes=ByteBuffer.wrap(run.getOperationId().toByteArray());String operation=new UUID(operationBytes.getLong(),operationBytes.getLong()).toString();
                check(expected.getRunId().equals(operation)&&expected.getGeneration().equals(run.getGeneration()),"域审核原件串run/generation");
                var actual=com.game.data.merge.ZoneMergeStore.auditReleased(connection,operation);check(actual.equals(expected),"pinned域终局与当前SQL实际业务审核不同");
            }

        }
        SqlWriterMaintenanceReceipt phase(SqlWriterMaintenanceReceipt input,Map<String,String> args)throws Exception{
            intent(input);if(input.getKind()==SqlWriterMaintenanceReceipt.Kind.TICKET_REVOKED)revokeTicket(new Sql(),input);var recorded=known(new Sql(),input);if(recorded!=null)return recorded;preloadEvidence(input);var expected=previewWriters(input);return tx(sql->{
                var old=known(sql,input);if(old!=null)return old;
                var locked=lockActiveSlots(sql,expected);Meta meta=meta(sql);var current=run(sql,input.getRunId(),true);
                if(input.getRunPhase()==SqlWriterDrainRun.Phase.PLAN_FIXED){
                    check(input.getKind()==SqlWriterMaintenanceReceipt.Kind.PHASE_EVENT&&input.getPhaseValue()==0&&input.getBeforeEpoch()==meta.epoch()&&input.getCatalogRevision()==meta.catalog()&&input.getGeneration().equals(meta.generation())&&meta.writable()==1,"P0请求不符");
                    var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(input.getPlanProto());plan(plan,meta,locked);check(plan.getRunId().equals(input.getRunId())&&plan.getRecoveryAuditDigest().isEmpty()&&plan.getPriorFreezeRequestId().isEmpty(),"P0不能预填未来freeze/recovery事实");
                    check(input.getEvidence().getOperationId().equals(input.getRunId())&&input.getEvidence().getManifestDigest().equals(sha(input.getPlanProto().toByteArray())),"P0 evidence绑定不同");
                    long now=sql.now();var result=outcome(input,meta,meta.epoch(),now);
                    check(current==null&&input.getAttemptRevision()==1&&input.getPriorReceiptId().isEmpty()&&input.getPriorReceiptDigest().isEmpty()&&meta.run().isEmpty(),"P0只能首次建立；重试不得重拍目录或改manifest");
                    check(sql.update("INSERT INTO "+table("sql_writer_drain_run")+"(operation_id,request_digest,generation,attempt_revision,manifest_proto,manifest_digest,phase,latest_receipt_digest,frozen_request_id,updated_ms,latest_receipt_id) VALUES(?,?,?,?,?,?,1,?,?,?,?)",
                            input.getRunId(),result.getRequestDigest(),meta.generation(),number(input.getAttemptRevision()),input.getPlanProto(),sha(input.getPlanProto().toByteArray()),result.getReceiptDigest(),ByteString.EMPTY,number(now),input.getRequestId())==1,"P0原件插入失败");
                    check(sql.update("UPDATE "+table("route_meta")+" SET freeze_run_id=? WHERE id=1 AND writable=1 AND OCTET_LENGTH(freeze_run_id)=0",input.getRunId())==1,"P0 reservation CAS失败");
                    insertReceipt(sql,result);return result;
                }
                if(current!=null&&current.getPhase()==SqlWriterDrainRun.Phase.ABORTED_CLOSED){
                    check(input.getKind()==SqlWriterMaintenanceReceipt.Kind.PHASE_EVENT&&input.getRunPhase()==SqlWriterDrainRun.Phase.DRAINING&&input.getPhaseValue()==0&&input.getAttemptRevision()==next(current.getAttemptRevision())&&meta.writable()==1,"只有同一永久cut可从ABORTED_CLOSED重排空");
                    sameRun(input.toBuilder().setAttemptRevision(current.getAttemptRevision()).build(),current,meta);catalogue(sql,current,meta,locked);
                    var root=permanentClosure(sql,current,3,3);check(root!=null,"尚无永久ROOTS_CLOSED，不能跳过真实根关闭进入重排空");
                    closedRoles(root.getEvidence().getPublication(),SqlWriterMaintenanceReceipt.Plan.parseFrom(current.getManifestProto()).getRootIssuersList());
                    var result=outcome(input,meta,meta.epoch(),sql.now());insertReceipt(sql,result);
                    check(sql.update("UPDATE "+table("sql_writer_drain_run")+" SET attempt_revision=?,phase=4,latest_receipt_digest=?,latest_receipt_id=?,updated_ms=? WHERE operation_id=? AND attempt_revision=? AND phase=11 AND latest_receipt_digest=?",
                            number(input.getAttemptRevision()),result.getReceiptDigest(),result.getRequestId(),number(result.getCommittedMs()),current.getOperationId(),number(current.getAttemptRevision()),current.getLatestReceiptDigest())==1,"同cut重排空CAS失败");return result;
                }

                sameRun(input,current,meta);if(current.getPhaseValue()!=9)catalogue(sql,current,meta,locked);else {check(meta.epoch()==next(next(SqlWriterMaintenanceReceipt.Plan.parseFrom(current.getManifestProto()).getBeforeEpoch())),"P9当前E必须等于P0 E加二");checkedActiveSlots(locked,sqlActiveWriters(),meta);}var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(current.getManifestProto());
                check(input.getPhaseValue()==0,"阶段fact不冒充FROZEN/RELEASED结局");
                int from=current.getPhaseValue(),to=input.getRunPhaseValue(),kind=input.getKindValue();
                if(kind==4){check(from==1&&to==1,"ROLE_CLOSED只能记录P0后真实source关闭");publication(input.getEvidence().getPublication(),plan.getRootIssuersList());}
                else if(kind==5){check(from==4&&to==4,"RELAY_QUIESCED只能在DRAINING记录");publication(input.getEvidence().getPublication(),plan.getQueueMutatorsList());}
                else if(kind==7){
                    check(from==6&&to==5&&input.getEvidence().hasTicket(),"票据撤销必须带精确ticket且退回同attempt NON_SQL_CLOSED");
                    var ticket=ticket(sql,input.getEvidence().getTicket().getTicketId());check(ticket!=null&&ticket.getOperationId().equals(current.getOperationId())&&ticket.getAttemptRevision()==current.getAttemptRevision()&&ticket.getState()==SqlWriterFreezeTicket.State.AVAILABLE,"仅可撤销未消费的同attempt ticket");ticketBinding(input.getEvidence().getTicket(),ticket);
                    var result=outcome(input,meta,meta.epoch(),sql.now());
                    check(sql.update("UPDATE "+table("sql_writer_freeze_ticket")+" SET state=3,revocation_digest=?,updated_ms=? WHERE ticket_id=? AND state=1",result.getReceiptDigest(),number(result.getCommittedMs()),ticket.getTicketId())==1,"ticket撤销CAS失败");append(sql,current,result,current.getFrozenRequestId());return result;
                }else{
                    check(kind==3,"阶段推进仅接受正式PHASE_EVENT");
                    if(to==3){check(from==1,"ROOTS_CLOSED前序非法");publication(input.getEvidence().getPublication(),plan.getRootIssuersList());}
                    else if(to==4)check(from==3&&!input.getEvidence().hasEmergency(),"DRAINING必须承接真实ROOTS_CLOSED");
                    else if(to==5){check(from==4&&input.getEvidence().hasNonSqlClosure(),"NON_SQL_CLOSED必须承接真实DRAINING");nonSql(sql,input.getEvidence().getNonSqlClosure(),current,plan);check(foldedWriters(sql,plan,current,meta).isEmpty(),"NORMAL闭合前P0全部SQL writer必须已按P1/P3永久退役");}
                    else if(to==8){
                        check(from==7&&meta.writable()==0&&input.getEvidence().hasReleaseReadiness(),"RELEASE_READY只能承接正常SQL_FROZEN及独立恢复审计证据");
                        releaseReadiness(sql,input.getEvidence().getReleaseReadiness(),current,meta);
                    }else if(to==11){check(from>=1&&from<=6,"已冻结/释放/紧急HOLD不能按普通abort解除");pins(input.getEvidence().getNonSqlClosure().getSourceEvidenceList());}
                    else if(to==10){
                        check(from==9&&meta.writable()==1&&input.getEvidence().hasPublicationReadiness(),"PUBLICATION_REOPENED只能承接SQL_RELEASED与实际开放ACK");
                        replacementPublication(sql,input.getEvidence().getPublicationReadiness(),plan,current);
                    }else if(to==from&&input.getEvidence().hasEmergency()){
                        check(from>=1&&from<=6&&!input.getEvidence().getEmergency().getReason().isBlank(),"显式危险事实不能伪装NORMAL阶段");pins(input.getEvidence().getEmergency().getSourceEvidenceList());
                    }else throw new SQLException("非法维护阶段跳转，保持原fence","45000");
                }
                var result=outcome(input,meta,meta.epoch(),sql.now());
                if(to==11)sql.update("UPDATE "+table("sql_writer_freeze_ticket")+" SET state=3,revocation_digest=?,updated_ms=? WHERE operation_id=? AND attempt_revision=? AND state=1",result.getReceiptDigest(),number(result.getCommittedMs()),current.getOperationId(),number(current.getAttemptRevision()));
                append(sql,current,result,current.getFrozenRequestId());
                if(to==10)check(sql.update("UPDATE "+table("route_meta")+" SET freeze_run_id=? WHERE id=1 AND writable=1 AND freeze_run_id=?",ByteString.EMPTY,current.getOperationId())==1,"publication终结reservation CAS失败");return result;
            });
        }
        private void replacementPublication(Sql sql,SqlWriterMaintenanceReceipt.PublicationReadiness evidence,SqlWriterMaintenanceReceipt.Plan plan,SqlWriterDrainRun run)throws Exception{
            var released=referenced(sql,evidence.getReleaseReceiptId(),evidence.getReleaseReceiptDigest(),run,9,2);
            check(released.getPhase()==SqlWriterMaintenanceReceipt.Phase.RELEASED&&released.getPlanProto().equals(run.getManifestProto())&&released.getRequestId().equals(run.getLatestReceiptId()),"P9必须承接本轮原release永久收据");
            check(evidence.getPreparedBootsCount()==plan.getRootIssuersCount(),"replacement root目录不完整");pins(evidence.getSourceOpenAcksList());
            Map<String,SqlWriterMaintenanceReceipt.IssuerClosure> originals=new HashMap<>();for(var role:plan.getRootIssuersList())check(originals.put(publicationKey(role.getIssuerId(),role.getLogicalRole()),role)==null,"P0同逻辑role重复");
            Map<String,SqlWriterMaintenanceReceipt.IssuerReadiness> prepared=new HashMap<>();
            for(var role:evidence.getPreparedBootsList()){
                String key=publicationKey(role.getIssuerId(),role.getLogicalRole());var old=originals.remove(key);size(role.getInstanceId(),16);
                check(old!=null&&!old.getInstanceId().equals(role.getInstanceId())&&role.getPreparedClosedMs()!=0&&Long.compareUnsigned(role.getPreparedClosedMs(),released.getCommittedMs())>=0&&prepared.put(key,role)==null,"P9必须release后新boot保持source关闭的真实prepared原件");
                size(role.getConsumerReadyFrontierDigest(),32);pins(role.getSourcePinsList());
                if(old.getPhysicalWriterKey().isEmpty())check(role.getPhysicalWriterKey().isEmpty()&&role.getRegistrationReceiptId().isEmpty()&&role.getRegistrationReceiptDigest().isEmpty(),"非SQL角色不能伪造SQL注册绑定");
                else{
                    check(!old.getPhysicalWriterKey().equals(role.getPhysicalWriterKey()),"P9不能复活P0旧writer");size(role.getPhysicalWriterKey(),16);
                    var registration=lifecycle(sql,role.getRegistrationReceiptId());
                    check(registration!=null&&registration.getKind()==SqlWriterLifecycleReceipt.Kind.REGISTER&&registration.getRequestDigest().equals(role.getRegistrationReceiptDigest())&&registration.getGeneration().equals(run.getGeneration())&&registration.getResultingEpoch()==released.getAfterEpoch(),"prepared注册不是本轮release后新主体");
                    var request=SqlWriterLifecycleReceipt.Request.parseFrom(registration.getRequestProto());
                    check(request.getMaintenanceRunId().equals(run.getOperationId())&&request.getWriter().getWriterKey().equals(role.getPhysicalWriterKey())&&request.getWriter().getInstanceId().equals(role.getInstanceId())&&request.getExpectedEpoch()==released.getAfterEpoch(),"prepared role与永久新注册全文不同");
                    var state=sql.one("SELECT state,instance_id,registration_digest FROM "+table("writer_registry")+" WHERE writer_key=?",r->List.of(r.getInt(1),ByteString.copyFrom(r.getBytes(2)),ByteString.copyFrom(r.getBytes(3))),role.getPhysicalWriterKey());
                    check(state!=null&&state.get(0).equals(1)&&state.get(1).equals(role.getInstanceId())&&state.get(2).equals(role.getRegistrationReceiptDigest()),"prepared主体不再是当前ACTIVE原件");
                }
            }
            check(originals.isEmpty(),"遗漏replacement逻辑role");
            List<SqlWriterMaintenanceReceipt.ConsumerReadiness> consumers=new ArrayList<>();
            for(var role:evidence.getPreparedBootsList())for(var pin:role.getSourcePinsList())if(pin.getSha256().equals(role.getConsumerReadyFrontierDigest())){
                var report=parseConsumerReadiness(pinnedReport(pin));
                for(var group:report.getGroupsList())check(group.getIssuerId().equals(role.getIssuerId())&&group.getInstanceId().equals(role.getInstanceId()),"消费者报告不是同prepared boot");
                for(var manual:report.getManualAssignmentsList()){pins(manual.getSourcePinsList());for(var assignment:manual.getAssignmentsList())check(assignment.getIssuerId().equals(role.getIssuerId())&&assignment.getInstanceId().equals(role.getInstanceId()),"manual报告不是同prepared SOURCE boot");}consumers.add(report);
            }
            validateConsumerReadiness(plan,run.getManifestDigest(),consumers);
            List<SqlWriterMaintenanceReceipt.ManualConsumerEvidence> manual=new ArrayList<>();for(var report:consumers)manual.addAll(report.getManualAssignmentsList());validateManualClosure(plan,run.getManifestDigest(),manual,true);

            for(var key:plan.getActiveWriterKeysList())check(Objects.equals(sql.one("SELECT state FROM "+table("writer_registry")+" WHERE writer_key=?",r->r.getInt(1),key),2),"P0旧writer未永久退休");
            Set<String> acknowledged=new HashSet<>();
            for(var pin:evidence.getSourceOpenAcksList()){
                var ack=read(pinnedReport(pin),SqlWriterMaintenanceReceipt.SourceOpenAck.newBuilder()).build();noUnknown(ack);
                String key=publicationKey(ack.getIssuerId(),ack.getLogicalRole());var ready=prepared.get(key);
                check(ready!=null&&acknowledged.add(key)&&ack.getInstanceId().equals(ready.getInstanceId())&&ack.getPhysicalWriterKey().equals(ready.getPhysicalWriterKey())
                        &&ack.getRegistrationReceiptId().equals(ready.getRegistrationReceiptId())&&ack.getRegistrationReceiptDigest().equals(ready.getRegistrationReceiptDigest())
                        &&ack.getOperationId().equals(run.getOperationId())&&ack.getManifestDigest().equals(run.getManifestDigest())&&ack.getOpenedMs()!=0
                        &&Long.compareUnsigned(ack.getOpenedMs(),ready.getPreparedClosedMs())>=0,"实际open ACK不匹配已关闭准备的新boot/run/manifest");
                size(ack.getOpenRequestId(),16);size(ack.getAcceptedFrontierDigest(),32);pins(ack.getSourcePinsList());
                var requestPin=openRequestPin(pin,ack,ready);
                if(requestPin!=null){
                    var request=read(pinnedReport(requestPin),SqlWriterMaintenanceReceipt.SourceOpenAck.newBuilder()).build();noUnknown(request);
                    var intent=ack.toBuilder().clearOpenedMs().clearAcceptedFrontierDigest().clearSourcePins().addAllSourcePins(ready.getSourcePinsList()).build();
                    check(request.equals(intent),"open-source.request全文不符同run/manifest/boot/role/request原intent或带结果字段");
                }
            }
            check(acknowledged.size()==prepared.size(),"缺少真实应用逐role开放ACK；不能以prepared冒充已开放");
        }
        /** J HTTP直接回传固定prepared pins；Go文件入口只允许同目录唯一open-source.request尾pin。 */
        private static SqlWriterMaintenanceReceipt.SourcePin openRequestPin(SqlWriterMaintenanceReceipt.SourcePin ackPin,SqlWriterMaintenanceReceipt.SourceOpenAck ack,SqlWriterMaintenanceReceipt.IssuerReadiness ready)throws Exception{
            var expected=ready.getSourcePinsList();var actual=ack.getSourcePinsList();
            if(actual.equals(expected))return null;
            check(actual.size()==expected.size()+1&&actual.subList(0,expected.size()).equals(expected),"open ACK source pins不是固定prepared前缀与唯一request");
            var request=actual.get(actual.size()-1);var ackPath=Path.of(ackPin.getAbsolutePath());var requestPath=Path.of(request.getAbsolutePath());
            check(ackPath.isAbsolute()&&requestPath.isAbsolute()&&ackPath.normalize().getParent()!=null&&requestPath.normalize().equals(ackPath.normalize().getParent().resolve("open-source.request")),"open request必须是ACK同目录的固定原件");
            size(request.getSha256(),32);return request;
        }
        private static String publicationKey(ByteString issuer,String role){return Base64.getEncoder().encodeToString(issuer.toByteArray())+"/"+role;}
        private static ByteString ticketRequestDigest(SqlWriterFreezeTicket ticket)throws IOException{
            return hash(ticket.toBuilder().clearGrantReceiptId().clearGrantReceiptDigest().clearUpdatedMs().clearConsumedReceiptDigest().clearRevocationDigest().build());
        }
        private static void ticketBinding(SqlWriterMaintenanceReceipt.TicketEvidence binding,SqlWriterFreezeTicket ticket)throws Exception{
            size(binding.getTicketId(),16);size(binding.getTicketRequestDigest(),32);
            check(binding.getTicketId().equals(ticket.getTicketId())&&binding.getAuthorityOperationId().equals(ticket.getOperationId())&&binding.getBoundRequestId().equals(ticket.getFreezeRequestId())&&binding.getBoundRequestDigest().equals(ticket.getFreezeRequestDigest())&&binding.getTicketRequestDigest().equals(ticketRequestDigest(ticket)),"ticket完整授权原件不符，不能更换窗口/类型/目标");
        }
        private SqlWriterFreezeTicket revokeTicket(Sql sql,SqlWriterMaintenanceReceipt input)throws Exception{
            check(input.getEvidence().hasTicket(),"撤票缺完整授权身份");var actual=ticket(sql,input.getEvidence().getTicket().getTicketId());
            check(actual!=null&&actual.getOperationId().equals(input.getRunId())&&actual.getAttemptRevision()==input.getAttemptRevision()&&actual.getGeneration().equals(input.getGeneration()),"撤票不能跨目标/run/attempt");
            // AVAILABLE是grant原请求的固定初态；消费/撤销状态只属于SQL结局，不从重试输入取得。
            ticketBinding(input.getEvidence().getTicket(),actual.toBuilder().setState(SqlWriterFreezeTicket.State.AVAILABLE).build());return actual;
        }
        private SqlWriterFreezeTicket freezeTicket(Sql sql,SqlWriterMaintenanceReceipt input)throws Exception{
            ByteString id=sql.one("SELECT ticket_id FROM "+table("sql_writer_freeze_ticket")+" WHERE freeze_request_id=?",r->ByteString.copyFrom(r.getBytes(1)),input.getRequestId());check(id!=null,"freeze没有精确绑定ticket");
            var actual=ticket(sql,id);check(actual!=null&&actual.getFreezeRequestId().equals(input.getRequestId())&&actual.getFreezeRequestDigest().equals(intentDigest(input))&&actual.getOperationId().equals(input.getRunId())&&actual.getAttemptRevision()==input.getAttemptRevision()&&actual.getGeneration().equals(input.getGeneration()),"freeze原请求与实际永久ticket不同");
            if(input.getKind()==SqlWriterMaintenanceReceipt.Kind.EMERGENCY_FREEZE&&input.getEvidence().getEmergency().hasAuthorization()){var binding=input.getEvidence().getEmergency().getAuthorization();check(binding.getTicketId().equals(id)&&binding.getAuthorityOperationId().equals(input.getRunId())&&binding.getBoundRequestId().equals(input.getRequestId())&&binding.getBoundRequestDigest().isEmpty()&&binding.getTicketRequestDigest().isEmpty(),"紧急freeze只携精确票据身份，不携自引用摘要");}return actual;
        }
        private SqlWriterFreezeTicket releaseTicket(Sql sql,SqlWriterMaintenanceReceipt input)throws Exception{
            check(input.getEvidence().hasReleaseReadiness(),"release必须直接绑定原freeze与恢复审计证据");var ready=input.getEvidence().getReleaseReadiness();
            var frozen=receipt(sql,ready.getOriginalFreezeReceiptId());check(frozen!=null&&frozen.getKind()==SqlWriterMaintenanceReceipt.Kind.FREEZE&&frozen.getPhase()==SqlWriterMaintenanceReceipt.Phase.FROZEN&&frozen.getReceiptDigest().equals(ready.getOriginalFreezeReceiptDigest())&&frozen.getRunId().equals(input.getRunId())&&frozen.getGeneration().equals(input.getGeneration())&&frozen.getAttemptRevision()==input.getAttemptRevision()&&frozen.getAfterEpoch()==input.getBeforeEpoch(),"release没有绑定本轮原NORMAL freeze结局");
            ByteString id=sql.one("SELECT ticket_id FROM "+table("sql_writer_freeze_ticket")+" WHERE freeze_request_id=?",r->ByteString.copyFrom(r.getBytes(1)),frozen.getRequestId());check(id!=null,"缺原freeze ticket");var actual=ticket(sql,id);
            check(actual!=null&&actual.getState()==SqlWriterFreezeTicket.State.CONSUMED&&actual.getAuthorizationKind()==SqlWriterFreezeTicket.AuthorizationKind.NORMAL_DRAIN_FREEZE&&actual.getConsumedReceiptDigest().equals(frozen.getReceiptDigest())&&actual.getFreezeRequestDigest().equals(frozen.getRequestDigest()),"release不能更换正常已消费ticket");return actual;
        }
        SqlWriterMaintenanceReceipt ticket(SqlWriterMaintenanceReceipt input,Map<String,String> args)throws Exception{
            intent(input);check(input.getKind()==SqlWriterMaintenanceReceipt.Kind.TICKET_GRANTED&&input.getPhaseValue()==0,"ticket请求必须正式授权kind");
            var future=read(Path.of(need(args,"--freeze-request")),SqlWriterMaintenanceReceipt.newBuilder()).build();intent(future);
            String authorization=need(args,"--authorization-kind");boolean emergency=authorization.equals("EMERGENCY_HOLD_FREEZE");check(emergency||authorization.equals("NORMAL_DRAIN_FREEZE"),"未知ticket授权类型");
            check(emergency?input.getEvidence().hasEmergency()&&input.getEvidence().getEmergency().hasAuthorization():input.getEvidence().hasTicket(),"ticket缺精确授权种类绑定");
            var binding=emergency?input.getEvidence().getEmergency().getAuthorization():input.getEvidence().getTicket();
            long notBefore=Long.parseUnsignedLong(need(args,"--not-before-ms")),expires=Long.parseUnsignedLong(need(args,"--expires-ms"));check(Long.compareUnsigned(expires,notBefore)>0,"ticket授权窗口无效");
            var wanted=SqlWriterFreezeTicket.newBuilder().setTicketId(binding.getTicketId()).setOperationId(input.getRunId()).setAttemptRevision(input.getAttemptRevision()).setGeneration(input.getGeneration()).setManifestDigest(input.getEvidence().getManifestDigest())
                    .setNonSqlReceiptDigest(emergency?ByteString.EMPTY:input.getPriorReceiptDigest()).setFreezeRequestId(future.getRequestId()).setFreezeRequestDigest(intentDigest(future)).setNotBeforeMs(notBefore).setExpiresMs(expires).setState(SqlWriterFreezeTicket.State.AVAILABLE).setAuthorizationKindValue(emergency?2:1).build();
            ticketBinding(binding,wanted);
            var recorded=known(new Sql(),input);if(recorded!=null)return recorded;preloadEvidence(input);preloadPins(future.getEvidence());var expected=previewWriters(input);return tx(sql->{
                var old=known(sql,input);if(old!=null)return old;var locked=lockActiveSlots(sql,expected);Meta meta=meta(sql);var run=run(sql,input.getRunId(),true);sameRun(input,run,meta);catalogue(sql,run,meta,locked);
                check(meta.writable()==1&&future.getRunId().equals(run.getOperationId())&&future.getAttemptRevision()==run.getAttemptRevision()&&future.getGeneration().equals(run.getGeneration())&&future.getBeforeEpoch()==meta.epoch()&&future.getCatalogRevision()==meta.catalog()&&future.getPlanProto().equals(run.getManifestProto()),"ticket必须绑定当前完整freeze原请求");
                if(emergency){check(run.getPhaseValue()>=1&&run.getPhaseValue()<=6&&input.getRunPhaseValue()==run.getPhaseValue()&&future.getKind()==SqlWriterMaintenanceReceipt.Kind.EMERGENCY_FREEZE&&future.getRunPhase()==SqlWriterDrainRun.Phase.EMERGENCY_FROZEN_HOLD&&future.getPhaseValue()==0&&future.getEvidence().hasEmergency(),"紧急授权必须显式HOLD，不能伪填NORMAL闭合");var danger=future.getEvidence().getEmergency();check(!danger.getReason().isBlank(),"缺少紧急风险说明");referenced(sql,danger.getDangerReceiptId(),danger.getDangerReceiptDigest(),run,run.getPhaseValue(),3);pins(danger.getSourceEvidenceList());}
                else check(run.getPhase()==SqlWriterDrainRun.Phase.NON_SQL_CLOSED&&input.getRunPhase()==SqlWriterDrainRun.Phase.FREEZE_AUTHORIZED&&future.getKind()==SqlWriterMaintenanceReceipt.Kind.FREEZE&&future.getRunPhase()==SqlWriterDrainRun.Phase.SQL_FROZEN&&future.getPhase()==SqlWriterMaintenanceReceipt.Phase.FROZEN,"NORMAL ticket必须来自真实NON_SQL_CLOSED");
                long now=sql.now();check(Long.compareUnsigned(expires,now)>0,"ticket已过授权窗口");var result=outcome(input,meta,meta.epoch(),now);
                check(sql.update("INSERT INTO "+table("sql_writer_freeze_ticket")+"(ticket_id,operation_id,attempt_revision,generation,manifest_digest,non_sql_receipt_digest,freeze_request_id,freeze_request_digest,not_before_ms,expires_ms,state,consumed_receipt_digest,revocation_digest,updated_ms,authorization_kind,grant_receipt_id,grant_receipt_digest) VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?)",
                        binding.getTicketId(),run.getOperationId(),number(run.getAttemptRevision()),run.getGeneration(),run.getManifestDigest(),wanted.getNonSqlReceiptDigest(),future.getRequestId(),intentDigest(future),number(notBefore),number(expires),ByteString.EMPTY,ByteString.EMPTY,number(now),emergency?2:1,result.getRequestId(),result.getReceiptDigest())==1,"同attempt ticket不可替换/复用");
                append(sql,run,result,run.getFrozenRequestId());return result;
            });
        }
        SqlWriterMaintenanceReceipt freeze(SqlWriterMaintenanceReceipt input)throws Exception{
            intent(input);boolean emergency=input.getKind()==SqlWriterMaintenanceReceipt.Kind.EMERGENCY_FREEZE;
            check(emergency||input.getKind()==SqlWriterMaintenanceReceipt.Kind.FREEZE,"freeze kind非法");freezeTicket(new Sql(),input);
            var recorded=known(new Sql(),input);if(recorded!=null)return recorded;preloadEvidence(input);var expected=previewWriters(input);return tx(sql->{
                var old=known(sql,input);if(old!=null)return old;var slots=lockActiveSlots(sql,expected);Meta meta=meta(sql);var run=run(sql,input.getRunId(),true);sameRunIdentity(input,run,meta);catalogue(sql,run,meta,slots);
                check(meta.writable()==1&&input.getRunPhaseValue()==(emergency?12:7)&&input.getPhaseValue()==(emergency?0:1),"冻结目标阶段不符");
                ByteString id=sql.one("SELECT ticket_id FROM "+table("sql_writer_freeze_ticket")+" WHERE freeze_request_id=?",r->ByteString.copyFrom(r.getBytes(1)),input.getRequestId());check(id!=null,"freeze没有精确绑定ticket");var ticket=ticket(sql,id);
                check(ticket.getState()==SqlWriterFreezeTicket.State.AVAILABLE&&ticket.getOperationId().equals(run.getOperationId())&&ticket.getAttemptRevision()==run.getAttemptRevision()&&ticket.getGeneration().equals(meta.generation())&&ticket.getManifestDigest().equals(run.getManifestDigest())&&ticket.getFreezeRequestDigest().equals(intentDigest(input))&&ticket.getAuthorizationKindValue()==(emergency?2:1),"ticket已消费/撤销、串run/attempt或授权种类不同");
                var grant=referenced(sql,ticket.getGrantReceiptId(),ticket.getGrantReceiptDigest(),run,emergency?run.getPhaseValue():6,6);
                check(run.getLatestReceiptId().equals(grant.getRequestId())&&run.getLatestReceiptDigest().equals(grant.getReceiptDigest())&&grant.getPriorReceiptId().equals(input.getPriorReceiptId())&&grant.getPriorReceiptDigest().equals(input.getPriorReceiptDigest()),"freeze必须经实际ticket关联最新grant及已知授权前序，禁止摘要自引用");
                long now=sql.now();check(Long.compareUnsigned(now,ticket.getNotBeforeMs())>=0&&Long.compareUnsigned(now,ticket.getExpiresMs())<0,"ticket不在授权窗口；不会自动解除publication fence");
                if(!emergency){
                    check(run.getPhase()==SqlWriterDrainRun.Phase.FREEZE_AUTHORIZED&&input.getEvidence().hasTicket()&&input.getEvidence().getTicket().getTicketId().equals(id)&&input.getEvidence().getTicket().getBoundRequestId().equals(input.getRequestId())&&input.getEvidence().getTicket().getBoundRequestDigest().isEmpty()&&input.getEvidence().getTicket().getTicketRequestDigest().isEmpty()&&input.getEvidence().getTicket().getAuthorityOperationId().equals(run.getOperationId()),"NORMAL freeze必须来自精确FREEZE_AUTHORIZED");
                    var nonSql=receipt(sql,grant.getPriorReceiptId());check(nonSql!=null&&nonSql.getReceiptDigest().equals(ticket.getNonSqlReceiptDigest())&&nonSql.getRunPhase()==SqlWriterDrainRun.Phase.NON_SQL_CLOSED&&nonSql.getAttemptRevision()==run.getAttemptRevision(),"缺少同attempt NON_SQL_CLOSED永久链");
                }else{check(input.getEvidence().hasEmergency()&&!input.getEvidence().getEmergency().getReason().isBlank(),"紧急freeze缺明确危险事实");pins(input.getEvidence().getEmergency().getSourceEvidenceList());}
                long after=next(meta.epoch());int active=0;for(var slot:slots)if(slot.state()==1){check(slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch(),"活动slot E/generation与meta不同");active++;}
                int advanced=0;for(var key:expected)advanced+=sql.update("UPDATE "+table("writer_slot")+" SET writer_epoch=? WHERE writer_key=? AND state=1 AND generation=? AND writer_epoch=?",number(after),key,meta.generation(),number(meta.epoch()));check(advanced==active,"freeze未原子推进全部ACTIVE片E");
                check(sql.update("UPDATE "+table("route_meta")+" SET writable=0,writer_epoch=? WHERE id=1 AND generation=? AND writable=1 AND writer_epoch=? AND freeze_run_id=?",number(after),meta.generation(),number(meta.epoch()),run.getOperationId())==1,"freeze meta CAS失败");
                var result=outcome(input,meta,after,now);
                check(sql.update("UPDATE "+table("sql_writer_freeze_ticket")+" SET state=2,consumed_receipt_digest=?,updated_ms=? WHERE ticket_id=? AND state=1",result.getReceiptDigest(),number(now),id)==1,"ticket消费CAS失败");append(sql,run,result,input.getRequestId());return result;
            });
        }
        SqlWriterMaintenanceReceipt release(SqlWriterMaintenanceReceipt input)throws Exception{
            intent(input);check(input.getKind()==SqlWriterMaintenanceReceipt.Kind.RELEASE&&input.getPhase()==SqlWriterMaintenanceReceipt.Phase.RELEASED&&input.getRunPhase()==SqlWriterDrainRun.Phase.SQL_RELEASED,"release目标类型非法");releaseTicket(new Sql(),input);
            var recorded=known(new Sql(),input);if(recorded!=null)return recorded;preloadEvidence(input);var expected=previewWriters(input);return tx(sql->{
                var old=known(sql,input);if(old!=null)return old;var slots=lockActiveSlots(sql,expected);Meta meta=meta(sql);var run=run(sql,input.getRunId(),true);sameRun(input,run,meta);catalogue(sql,run,meta,slots);
                check(run.getPhase()==SqlWriterDrainRun.Phase.RELEASE_READY&&meta.writable()==0,"只有正常RELEASE_READY可显式release");
                var ready=receipt(sql,run.getLatestReceiptId());check(ready!=null&&ready.getRunPhase()==SqlWriterDrainRun.Phase.RELEASE_READY&&ready.getReceiptDigest().equals(run.getLatestReceiptDigest())&&ready.getPlanProto().equals(input.getPlanProto()),"恢复审计必须读phase8永久原件，不能回填P0或临时更换");
                check(ready.getEvidence().hasReleaseReadiness()&&ready.getEvidence().getReleaseReadiness().equals(input.getEvidence().getReleaseReadiness()),"release必须逐字绑定phase8独立typed恢复审计原件");releaseReadiness(sql,ready.getEvidence().getReleaseReadiness(),run,meta);
                long after=next(meta.epoch());int active=0;for(var slot:slots)if(slot.state()==1){check(slot.generation().equals(meta.generation())&&slot.epoch()==meta.epoch(),"release活动片E不符");active++;}
                int advanced=0;for(var key:expected)advanced+=sql.update("UPDATE "+table("writer_slot")+" SET writer_epoch=? WHERE writer_key=? AND state=1 AND generation=? AND writer_epoch=?",number(after),key,meta.generation(),number(meta.epoch()));check(advanced==active,"release未完整推进ACTIVE E");
                check(sql.update("UPDATE "+table("route_meta")+" SET writable=1,writer_epoch=? WHERE id=1 AND writable=0 AND generation=? AND writer_epoch=? AND freeze_run_id=?",number(after),meta.generation(),number(meta.epoch()),run.getOperationId())==1,"release meta CAS失败");
                var result=outcome(input,meta,after,sql.now());append(sql,run,result,run.getFrozenRequestId());return result;
            });
        }


    }
}
