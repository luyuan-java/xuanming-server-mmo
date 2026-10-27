package com.game.data.merge;

import com.game.common.player.PlayerRouteAuthority;
import com.game.data.merge.pb.ZoneMergeEntityOriginal;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.player.store.RolePendingFence;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.CodedOutputStream;
import com.game.player.store.state.SqlWriterMaintenanceReceipt;
import com.game.player.store.state.SqlWriterLifecycleReceipt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/**
 * 单库合区维护的完整提交面。调用者只推进一个有界阶段，不拼装内部锁序。
 * 只在同run consumed NORMAL票据和永久SQL FROZEN之后推进域维护；不发全局冻结或重开权限。
 * 每步验证NORMAL空ACTIVE集后沿meta→drain run→ticket→两区围栏→目录→P→域run/items；异常不解除全局冻结。
 * 没有租约解冻、后台自动放行、Redis事实或原owner补权；阻塞JDBC只在运维工作线程/CLI调用。
 */
public final class ZoneMergeStore {
    public static final int BATCH=16;
    private final DataSource source;private final String operatorPrincipal;
    public ZoneMergeStore(DataSource source){this(source,System.getenv("MMORPG_SQL_OPERATOR_PRINCIPAL"));}
    public ZoneMergeStore(DataSource source,String operatorPrincipal){this.source=Objects.requireNonNull(source);this.operatorPrincipal=operatorPrincipal;}
    @FunctionalInterface private interface Work<T>{T run(Sql sql)throws SQLException;}
    @FunctionalInterface private interface Row<T>{T map(ResultSet row)throws SQLException;}
    private <T>T tx(Work<T> work)throws SQLException{
        try(var c=source.getConnection()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            int network=c.getNetworkTimeout();c.setNetworkTimeout(Runnable::run,30_000);
            try{var sql=new Sql(c);T result=work.run(sql);sql.left();c.commit();return result;}
            catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
            finally{if(!c.isClosed())c.setNetworkTimeout(Runnable::run,network);}
        }
    }
    private static final class Sql {
        final Connection c;final long until=System.nanoTime()+30_000_000_000L;
        Sql(Connection c){this.c=c;}
        int left()throws SQLTimeoutException{long n=until-System.nanoTime();if(n<=0||Thread.currentThread().isInterrupted())throw new SQLTimeoutException("合区批次预算耗尽，保留原冻结");return (int)Math.max(1,(n+999_999_999)/1_000_000_000);}
        PreparedStatement statement(String query,Object...args)throws SQLException{var p=c.prepareStatement(query);p.setQueryTimeout(left());for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}
        <T>List<T> list(String query,Row<T> mapper,Object...args)throws SQLException{try(var p=statement(query,args);var r=p.executeQuery()){var values=new ArrayList<T>();while(r.next())values.add(mapper.map(r));return values;}}
        <T>T one(String query,Row<T> mapper,Object...args)throws SQLException{var all=list(query,mapper,args);if(all.size()>1)throw new SQLException("维护原件唯一键损坏");return all.isEmpty()?null:all.getFirst();}
        int update(String query,Object...args)throws SQLException{try(var p=statement(query,args)){return p.executeUpdate();}}
        long count(String query,Object...args)throws SQLException{return Objects.requireNonNull(one(query,r->r.getLong(1),args));}
        long now()throws SQLException{return count("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)");}
    }
    private static Object id(long value){return PlayerRouteAuthority.u64(value);}
    /** 显式维护入口先只读核所有依赖列；缺域/缺迁移不能先冻住全服再当作空业务。 */
    public static void verifySchema(Connection c)throws SQLException{
        long until=System.nanoTime()+30_000_000_000L;
        var columns=new LinkedHashMap<String,String>();
        columns.put("route_meta","id,generation,writable,fence_shards,writer_epoch,catalog_revision,freeze_run_id");columns.put("zone_merge_fence","zone_id,shard,run_id,revision");columns.put("zone_merged_into","zone_id,target_zone,run_id,revision");
        columns.put("zone_merge_run","run_id,mode,source_zone,target_zone,generation,original_run_id,state,phase,cursor_id,item_count,plan_hash,requested_by,approved_by,released_by,created_ms,updated_ms,expected_players,initial_hash");
        columns.put("zone_merge_item","run_id,kind,entity_id,original_proto,state,after_digest,after_epoch");columns.put("zone_config","zone_id,manual_status,maintenance_msg,updated_at");
        columns.put("player","player_id,account,zone_id,owner_revision,owner_epoch,owner_released,owner_lease_until,trade_listing_id");columns.put("player_final_save","player_id,status");
        columns.put("guild","guild_id,leader_id,zone_id");columns.put("trade_listing","listing_id,seller_player_id,market_zone,status");
        columns.put("team_member_authority","player_id,team_id");columns.put("team_launch_role","player_id,state");columns.put("team_invite_authority","invitee_id,initiator_player_id,expires_ms");columns.put("team_application_authority","player_id,recipient_player_id,expires_ms");
        columns.put("guild_trial_launch_role","player_id,state");columns.put("guild_activity_reward_owed","reward_id,player_id,guild_id,status");columns.put("guild_asset_op","op_id,player_id,guild_id,status");columns.put("guild_trial_lobby","lobby_id,guild_id,state");columns.put("guild_trial_battle","guild_id,state");
        columns.put("guild_activity_slot","player_id,lobby_id,expires_ms");
        columns.put("mail_asset_op","op_id,player_id,status");columns.put("match_global_ticket","player_id,state");columns.put("match_global_watching","player_id,state");columns.put("match_global_challenge","challenger_id,target_id,state,expires_ms");
        columns.put("trade_commerce","commerce_id,seller_player_id,buyer_player_id,status");columns.put("trade_asset_op","player_id,status,receipt_ack_pending,business_consumed");columns.put("player_trade_freeze","player_id,status");columns.put("player_trade_transfer","player_id,status");columns.put("player_inbox_item","player_id,state");
        for(var entry:columns.entrySet()){
            long remaining=until-System.nanoTime();if(remaining<=0||Thread.currentThread().isInterrupted())throw new SQLTimeoutException("维护结构核验预算耗尽");
            try(var q=c.prepareStatement("SELECT "+entry.getValue()+" FROM "+entry.getKey()+" WHERE 1=0")){q.setQueryTimeout((int)Math.max(1,Math.min(3,(remaining+999_999_999)/1_000_000_000)));try(var ignored=q.executeQuery()){}}
        }
    }
    private static long unsigned(ResultSet r,String column)throws SQLException{String value=r.getString(column);if(value==null)throw new SQLException("维护原件缺少整数");try{return Long.parseUnsignedLong(value);}catch(NumberFormatException invalid){throw new SQLException("维护原件整数越界");}}
    private static int zone(ResultSet r,String column)throws SQLException{long value=unsigned(r,column);require(value!=0&&Long.compareUnsigned(value,0xffff_ffffL)<=0,"维护区号原件越界");return (int)value;}
    private static String uuid(String value){if(value==null||!UUID.fromString(value).toString().equals(value)||value.equals("00000000-0000-0000-0000-000000000000"))throw new IllegalArgumentException("需要规范非零UUID");return value;}
    private static String operator(String value){if(value==null||value.isBlank()||value.length()>64||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("审批操作者无效");return value;}
    private static void zones(int a,int b){if(a==0||b==0||a==b)throw new IllegalArgumentException("源目标必须为两个不同非零uint32区号");}
    private static void require(boolean condition,String message)throws SQLException{if(!condition)throw new SQLException(message);}
    private static byte[] digest(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static byte[] chain(byte[] previous,byte[] value){try{var out=new ByteArrayOutputStream();out.write(previous);out.write(value);return digest(out.toByteArray());}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}}
    private static byte[] rowDigest(ResultSet row)throws SQLException{
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);var fields=row.getMetaData();
            for(int i=1;i<=fields.getColumnCount();i++){out.writeUTF(fields.getColumnName(i));out.writeInt(fields.getColumnType(i));byte[] value=row.getBytes(i);out.writeInt(value==null?-1:value.length);if(value!=null)out.write(value);}out.flush();return digest(bytes.toByteArray());
        }catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    private static ZoneMergeRunRow runRow(ResultSet r)throws SQLException{return ZoneMergeRunRow.newBuilder().setRunId(r.getString("run_id")).setMode(r.getInt("mode")).setSourceZone(zone(r,"source_zone")).setTargetZone(zone(r,"target_zone"))
        .setGeneration(r.getString("generation")).setOriginalRunId(r.getString("original_run_id")).setState(r.getInt("state")).setPhase(r.getInt("phase")).setCursorId(unsigned(r,"cursor_id")).setItemCount(unsigned(r,"item_count"))
        .setPlanHash(ByteString.copyFrom(r.getBytes("plan_hash"))).setRequestedBy(r.getString("requested_by")).setApprovedBy(r.getString("approved_by")).setReleasedBy(r.getString("released_by"))
        .setCreatedMs(unsigned(r,"created_ms")).setUpdatedMs(unsigned(r,"updated_ms")).setExpectedPlayers(unsigned(r,"expected_players")).setInitialHash(ByteString.copyFrom(r.getBytes("initial_hash"))).build();}
    private static ZoneMergeRunRow run(Sql sql,String key,boolean lock)throws SQLException{return sql.one("SELECT * FROM zone_merge_run WHERE run_id=?"+(lock?" FOR UPDATE":""),ZoneMergeStore::runRow,key);}
    private static String meta(Sql sql,int writable)throws SQLException{
        String generation=sql.one("SELECT generation,writable,fence_shards FROM route_meta WHERE id=1 FOR UPDATE",r->{require(r.getInt(2)==writable&&!r.wasNull()&&r.getInt(3)==64,"路由meta状态/片数不符");try{return uuid(r.getString(1));}catch(RuntimeException invalid){throw new SQLException("路由世代无效");}});
        require(generation!=null,"缺少独立初始化的路由meta");return generation;
    }
    private static void directory(Sql sql,int from,int to)throws SQLException{
        for(long z:new TreeSet<>(List.of(Integer.toUnsignedLong(from),Integer.toUnsignedLong(to)))){
            Integer status=sql.one("SELECT manual_status FROM zone_config WHERE zone_id=? FOR UPDATE",r->r.getObject(1)==null?null:r.getInt(1),z);
            require(status!=null&&(status==1||status==2),"源目标目录必须真实存在且处于维护或关闭状态");
        }
    }
    private static void fences(Sql sql,ZoneMergeRunRow run,boolean acquiring)throws SQLException{
        for(long zone:new TreeSet<>(List.of(Integer.toUnsignedLong(run.getSourceZone()),Integer.toUnsignedLong(run.getTargetZone())))){
            var shards=sql.list("SELECT shard,run_id,revision FROM zone_merge_fence WHERE zone_id=? ORDER BY shard FOR UPDATE",r->{String expected=acquiring?"":run.getRunId();require(expected.equals(r.getString(2))&&r.getLong(3)>0,"合区围栏被其它原run持有或损坏");return r.getInt(1);},zone);
            require(shards.size()==64,"合区围栏缺片，不能自动播种");for(int i=0;i<64;i++)require(shards.get(i)==i,"合区围栏分片损坏");
        }
    }
    private static byte[] runBytes(String key){var id=UUID.fromString(uuid(key));return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    /** 只认同run已经消费的NORMAL授权与永久FROZEN结局；本工具不发票、不消费票、不冻结全局SQL。 */
    private String frozenAuthority(Sql sql,String key)throws SQLException {
        String principal=operatorPrincipal;
        require(principal!=null&&!principal.isBlank(),"合服需要独立operator主体");
        require(principal.equals(sql.one("SELECT CURRENT_USER()",r->r.getString(1))),"实际operator主体不符");
        byte[] runId=runBytes(key);
        // NORMAL在P5前已将P0 writer永久退役；空ACTIVE集无需也禁止锁旧REVOKED片。
        Object[] meta=sql.one("SELECT generation,writable,writer_epoch,catalog_revision,freeze_run_id FROM route_meta WHERE id=1 FOR UPDATE",
                r->new Object[]{r.getString(1),r.getInt(2),r.getString(3),r.getString(4),r.getBytes(5)});
        require(meta!=null&&(Integer)meta[1]==0&&Arrays.equals(runId,(byte[])meta[4]),"全局SQL未永久冻结于同run");
        String generation=(String)meta[0];uuid(generation);
        require(sql.count("SELECT COUNT(*) FROM writer_registry WHERE state=1")==0&&sql.count("SELECT COUNT(*) FROM writer_slot WHERE state=1")==0,"NORMAL维护必须保留全部旧writer已永久退役的空ACTIVE集");
        Object[] run=sql.one("SELECT generation,attempt_revision,phase,frozen_request_id,manifest_digest,manifest_proto FROM sql_writer_drain_run WHERE operation_id=? FOR UPDATE",
                r->new Object[]{r.getString(1),r.getString(2),r.getInt(3),r.getBytes(4),r.getBytes(5),r.getBytes(6)},runId);
        require(run!=null&&generation.equals(run[0])&&List.of(7,8).contains((Integer)run[2])&&((byte[])run[3]).length==16,"同run未处于SQL_FROZEN/RELEASE_READY");
        Object[] ticket=sql.one("SELECT generation,state,authorization_kind,freeze_request_id,consumed_receipt_digest,freeze_request_digest,manifest_digest FROM sql_writer_freeze_ticket WHERE operation_id=? AND attempt_revision=? FOR UPDATE",
                r->new Object[]{r.getString(1),r.getInt(2),r.getInt(3),r.getBytes(4),r.getBytes(5),r.getBytes(6),r.getBytes(7)},runId,run[1]);
        require(ticket!=null&&generation.equals(ticket[0])&&(Integer)ticket[1]==2&&(Integer)ticket[2]==1
                &&Arrays.equals((byte[])ticket[3],(byte[])run[3])&&Arrays.equals((byte[])ticket[6],(byte[])run[4]),"必须同attempt consumed NORMAL票据；紧急HOLD不授权合服");
        var frozen=sql.one("SELECT request_id,request_digest,run_id,generation,before_epoch,after_epoch,catalog_revision,phase,plan_proto,committed_ms,kind,run_phase,attempt_revision,prior_receipt_id,prior_receipt_digest,evidence,receipt_digest FROM sql_writer_maintenance_receipt WHERE request_id=?",ZoneMergeStore::maintenanceReceipt,run[3]);
        require(frozen!=null&&Arrays.equals(runId,frozen.getRunId().toByteArray())&&generation.equals(frozen.getGeneration())&&meta[2].equals(Long.toUnsignedString(frozen.getAfterEpoch()))&&meta[3].equals(Long.toUnsignedString(frozen.getCatalogRevision()))
                &&frozen.getPhaseValue()==1&&frozen.getKindValue()==1&&frozen.getRunPhaseValue()==7&&run[1].equals(Long.toUnsignedString(frozen.getAttemptRevision()))
                &&Arrays.equals((byte[])ticket[4],frozen.getReceiptDigest().toByteArray())&&Arrays.equals((byte[])ticket[5],frozen.getRequestDigest().toByteArray())
                &&Arrays.equals((byte[])run[5],frozen.getPlanProto().toByteArray())&&Arrays.equals((byte[])run[4],digest((byte[])run[5])),"缺少与meta/consumed票据完全绑定的永久FROZEN收据");
        verifyRetiredCatalogue(sql,frozen);
        return generation;
    }
    private static byte[] canonical(Message message)throws SQLException{
        try{byte[] bytes=new byte[message.getSerializedSize()];var out=CodedOutputStream.newInstance(bytes);out.useDeterministicSerialization();message.writeTo(out);out.checkNoSpaceLeft();return bytes;}catch(java.io.IOException bad){throw new SQLException("正式控制原件无法编码",bad);}
    }
    private static void noUnknown(Message message)throws SQLException{
        require(message.getUnknownFields().asMap().isEmpty(),"控制原件含未知字段");for(var entry:message.getAllFields().entrySet())if(entry.getKey().getJavaType()==com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE){if(entry.getKey().isRepeated())for(Object value:(List<?>)entry.getValue())noUnknown((Message)value);else noUnknown((Message)entry.getValue());}
    }
    private static SqlWriterMaintenanceReceipt maintenanceReceipt(ResultSet row)throws SQLException{
        try{var result=SqlWriterMaintenanceReceipt.newBuilder().setRequestId(ByteString.copyFrom(row.getBytes(1))).setRequestDigest(ByteString.copyFrom(row.getBytes(2))).setRunId(ByteString.copyFrom(row.getBytes(3))).setGeneration(row.getString(4))
            .setBeforeEpoch(Long.parseUnsignedLong(row.getString(5))).setAfterEpoch(Long.parseUnsignedLong(row.getString(6))).setCatalogRevision(Long.parseUnsignedLong(row.getString(7))).setPhaseValue(row.getInt(8)).setPlanProto(ByteString.copyFrom(row.getBytes(9)))
            .setCommittedMs(Long.parseUnsignedLong(row.getString(10))).setKindValue(row.getInt(11)).setRunPhaseValue(row.getInt(12)).setAttemptRevision(Long.parseUnsignedLong(row.getString(13))).setPriorReceiptId(ByteString.copyFrom(row.getBytes(14))).setPriorReceiptDigest(ByteString.copyFrom(row.getBytes(15)))
            .setEvidence(SqlWriterMaintenanceReceipt.Evidence.parseFrom(row.getBytes(16))).setReceiptDigest(ByteString.copyFrom(row.getBytes(17))).build();noUnknown(result);
            require(MessageDigest.isEqual(digest(canonical(result.toBuilder().clearReceiptDigest().build())),result.getReceiptDigest().toByteArray()),"永久FROZEN全文摘要损坏");require(MessageDigest.isEqual(digest(canonical(result.toBuilder().clearRequestDigest().clearReceiptDigest().clearCommittedMs().clearAfterEpoch().build())),result.getRequestDigest().toByteArray()),"永久FROZEN不能还原原请求摘要");return result;
        }catch(java.io.IOException|IllegalArgumentException invalid){throw new SQLException("永久FROZEN原件损坏",invalid);}
    }
    private static void verifyRetiredCatalogue(Sql sql,SqlWriterMaintenanceReceipt frozen)throws SQLException{
        try{var plan=SqlWriterMaintenanceReceipt.Plan.parseFrom(frozen.getPlanProto());noUnknown(plan);var remaining=new HashSet<>(plan.getActiveWriterKeysList());
            require(remaining.size()>0&&remaining.size()<=1024&&remaining.size()==plan.getActiveWriterKeysCount(),"P0 writer目录非法");
            var difference=new java.math.BigInteger(Long.toUnsignedString(frozen.getCatalogRevision())).subtract(new java.math.BigInteger(Long.toUnsignedString(plan.getCatalogRevision())));
            require(difference.equals(java.math.BigInteger.valueOf(remaining.size()))&&frozen.getBeforeEpoch()==plan.getBeforeEpoch()&&frozen.getAfterEpoch()==plan.getBeforeEpoch()+1&&plan.getBeforeEpoch()!=-1L,"NORMAL冻结目录/E不是P0全部合法退役后的结局");
            var revisions=sql.list("SELECT request_id,kind,generation,request_digest,request_proto,previous_epoch,resulting_epoch,catalog_revision FROM writer_lifecycle_receipt WHERE catalog_revision>? AND catalog_revision<=? ORDER BY catalog_revision,request_id LIMIT "+(remaining.size()+1),r->new Object[]{r.getBytes(1),r.getInt(2),r.getString(3),r.getBytes(4),r.getBytes(5),r.getString(6),r.getString(7),r.getString(8)},new java.math.BigInteger(Long.toUnsignedString(plan.getCatalogRevision())),new java.math.BigInteger(Long.toUnsignedString(frozen.getCatalogRevision())));
            require(revisions.size()==remaining.size(),"永久RETIRE序列缺失/重复");long revision=plan.getCatalogRevision();
            for(var item:revisions){var request=SqlWriterLifecycleReceipt.Request.parseFrom((byte[])item[4]);noUnknown(request);require((Integer)item[1]==2&&request.getKindValue()==2&&frozen.getGeneration().equals(item[2])&&frozen.getGeneration().equals(request.getWriter().getGeneration())
                &&Arrays.equals((byte[])item[0],request.getRequestId().toByteArray())&&Arrays.equals((byte[])item[3],digest(canonical(request)))&&request.getMaintenanceRunId().equals(frozen.getRunId())&&request.getExpectedCatalogRevision()==revision&&request.getExpectedEpoch()==plan.getBeforeEpoch()
                &&item[5].equals(Long.toUnsignedString(plan.getBeforeEpoch()))&&item[6].equals(Long.toUnsignedString(plan.getBeforeEpoch()))&&item[7].equals(Long.toUnsignedString(++revision))&&remaining.remove(request.getWriter().getWriterKey()),"不是连续同run全文RETIRE原件");
                require(sql.count("SELECT COUNT(*) FROM writer_registry WHERE writer_key=? AND state=2 AND retirement_request_id=? AND retirement_digest=? AND instance_id=? AND registration_digest=?",request.getWriter().getWriterKey().toByteArray(),item[0],item[3],request.getWriter().getInstanceId().toByteArray(),request.getWriter().getRegistrationDigest().toByteArray())==1,"旧writer退休原件不符");
            }
            require(remaining.isEmpty(),"仍有P0 writer未永久退休");
        }catch(java.io.IOException|IllegalArgumentException invalid){throw new SQLException("P0/RETIRE正式原件损坏",invalid);}
    }
    private ZoneMergeRunRow held(Sql sql,String key)throws SQLException{
        String generation=frozenAuthority(sql,key);var hint=run(sql,key,false);require(hint!=null&&hint.getState()!=6,"维护原run不存在或已显式释放");
        require(generation.equals(hint.getGeneration()),"路由世代已改变，不能从历史run补权");fences(sql,hint,false);directory(sql,hint.getSourceZone(),hint.getTargetZone());return hint;
    }
    /** 显式全服Freeze；调用前应先停准入并真实排空所有scene。不是服务自动维护开关。 */
    public ZoneMergeRunRow freeze(String key,int from,int to,long expectedSourcePlayers,String requestedBy)throws SQLException{return freeze(key,from,to,expectedSourcePlayers,requestedBy,"");}
    public ZoneMergeRunRow freeze(String key,int from,int to,long expectedSourcePlayers,String requestedBy,String originalKey)throws SQLException{
        uuid(key);zones(from,to);operator(requestedBy);if(expectedSourcePlayers<0)throw new IllegalArgumentException("源玩家计数必须显式且非负");if(!originalKey.isEmpty())uuid(originalKey);
        return tx(sql->{
            verifySchema(sql.c);
            String generation=frozenAuthority(sql,key);
            var prior=run(sql,key,false);if(prior!=null){require(prior.getSourceZone()==from&&prior.getTargetZone()==to&&prior.getExpectedPlayers()==expectedSourcePlayers&&prior.getOriginalRunId().equals(originalKey)&&prior.getRequestedBy().equals(requestedBy),"run UUID被另一份维护意图使用");return prior;}
            int mode=originalKey.isEmpty()?1:2;
            var initial=ZoneMergeRunRow.newBuilder().setRunId(key).setMode(mode).setSourceZone(from).setTargetZone(to).setGeneration(generation).setOriginalRunId(originalKey).setExpectedPlayers(expectedSourcePlayers).setRequestedBy(requestedBy).build();
            fences(sql,initial,true);directory(sql,from,to);
            require(sql.count("SELECT COUNT(*) FROM player WHERE owner_released IS NULL OR owner_released<>1")==0,"全服仍有未真实最终释放的scene写者，租约过期不能替代排空");
            require(sql.count("SELECT COUNT(*) FROM player_final_save WHERE status IS NULL OR BINARY status NOT IN ('OPEN','APPLIED')")==0,"全服仍有未提交最终写回或未知状态");
            if(mode==1){require(sql.count("SELECT COUNT(*) FROM zone_merged_into WHERE zone_id IN(?,?)",Integer.toUnsignedLong(from),Integer.toUnsignedLong(to))==0,"源或目标已经退役");require(sql.count("SELECT COUNT(*) FROM player WHERE zone_id=?",Integer.toUnsignedLong(from))==expectedSourcePlayers,"源玩家数量与明确预期不符");}
            else{var original=run(sql,originalKey,false);require(original!=null&&original.getMode()==1&&original.getState()==6&&original.getSourceZone()==to&&original.getTargetZone()==from,"撤销必须引用已完成并释放的正向原run");
                require(sql.count("SELECT COUNT(*) FROM zone_merged_into WHERE zone_id=? AND target_zone=? AND run_id=?",Integer.toUnsignedLong(to),Integer.toUnsignedLong(from),originalKey)==1,"源退役marker与原run不符");
                require(sql.count("SELECT COUNT(*) FROM zone_merged_into WHERE zone_id=?",Integer.toUnsignedLong(from))==0,"目标已发生后续合区，不能撤销旧run");
                require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND kind=1",originalKey)==expectedSourcePlayers,"撤销玩家数量与原清单不符");}
            long now=sql.now();byte[] seed=digest(initial.toByteArray());
            sql.update("INSERT INTO zone_merge_run(run_id,mode,source_zone,target_zone,generation,original_run_id,state,phase,cursor_id,item_count,plan_hash,requested_by,approved_by,released_by,created_ms,updated_ms,expected_players,initial_hash) VALUES(?,?,?,?,?,?,1,1,0,0,?,?,'','',?,?,?,?)",key,mode,Integer.toUnsignedLong(from),Integer.toUnsignedLong(to),generation,originalKey,seed,requestedBy,now,now,expectedSourcePlayers,seed);
            require(sql.update("UPDATE zone_merge_fence SET run_id=?,revision=revision+1 WHERE zone_id IN(?,?) AND run_id='' AND revision<9223372036854775807",key,Integer.toUnsignedLong(from),Integer.toUnsignedLong(to))==128,"冻结围栏CAS失败");return run(sql,key,false);
        });
    }
    public ZoneMergeRunRow read(String key)throws SQLException{uuid(key);return tx(sql->{var result=run(sql,key,false);require(result!=null,"维护原run不存在");return result;});}
    private static void lockPeople(Sql sql,Collection<Long> players)throws SQLException{
        var ids=new TreeSet<Long>(Long::compareUnsigned);ids.addAll(players);for(long player:ids)require(sql.one("SELECT player_id FROM player WHERE player_id=? FOR UPDATE",r->unsigned(r,"player_id"),id(player))!=null,"计划角色已经缺失");
    }
    /** P7只读原事务；manual、unknown、迟到收款/奖励/邮件/观战/组队/邀请均阻挡，不执行取消补偿。 */
    private static void idle(Sql sql,long player)throws SQLException{
        require(sql.count("SELECT COUNT(*) FROM player WHERE player_id=? AND owner_released=1 AND trade_listing_id=0 AND owner_revision>0 AND owner_epoch<9223372036854775807",id(player))==1,"角色尚未真实排空、冻结或owner/E无效");
        require(!RolePendingFence.blocked(sql.c,player),"角色存在队伍/邀请/历练/奖励/邮件/匹配/观战挂起条件");
        require(sql.count("SELECT COUNT(*) FROM player_final_save WHERE player_id=? AND (status IS NULL OR BINARY status NOT IN ('OPEN','APPLIED'))",id(player))==0,"角色最终写回未终结");
        require(!RolePendingFence.economicBlockedForHomeMove(sql.c,player),"角色经济合同/资产/寄售原件未安全终结");
    }
    private static String table(int kind){return kind<=2?"player":kind==3||kind==5?"guild":"trade_listing";}
    private static String pk(int kind){return kind<=2?"player_id":kind==3||kind==5?"guild_id":"listing_id";}
    private static String zoneColumn(int kind){return kind==4||kind==6?"market_zone":"zone_id";}
    private static ZoneMergeEntityOriginal snapshot(Sql sql,int kind,long entity,int to)throws SQLException{
        var row=sql.one("SELECT * FROM "+table(kind)+" WHERE "+pk(kind)+"=? FOR UPDATE",r->ZoneMergeEntityOriginal.newBuilder().setKind(kind).setEntityId(entity).setFromZone(zone(r,zoneColumn(kind))).setToZone(to).setRowDigest(ByteString.copyFrom(rowDigest(r)))
            .setAuthorityPlayerId(kind<=2?entity:unsigned(r,kind==3||kind==5?"leader_id":"seller_player_id")).build(),id(entity));
        if(row==null)return null;
        return sql.one("SELECT account,owner_revision,owner_epoch FROM player WHERE player_id=?",r->row.toBuilder().setOwnerAccount(r.getString(1)).setOwnerRevision(unsigned(r,"owner_revision")).setOwnerEpoch(unsigned(r,"owner_epoch")).build(),id(row.getAuthorityPlayerId()));
    }
    private static ZoneMergeEntityOriginal decode(byte[] raw)throws SQLException{
        try{if(raw==null||raw.length>4096)throw new SQLException("维护清单原PB大小无效");var r=ZoneMergeEntityOriginal.parseFrom(raw);
            require(r.getKind()>=1&&r.getKind()<=6&&r.getEntityId()!=0&&r.getAuthorityPlayerId()!=0&&r.getFromZone()!=0&&r.getToZone()!=0&&!r.getOwnerAccount().isEmpty()&&r.getOwnerRevision()>0&&r.getOwnerEpoch()>=0&&r.getRowDigest().size()==32,"维护清单原身份/枚举损坏");return r;
        }catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("维护清单原PB损坏",invalid);}
    }
    private static byte[] originalAfter(Sql sql,String original,int kind,long entity)throws SQLException{return sql.one("SELECT after_digest FROM zone_merge_item WHERE run_id=? AND kind=? AND entity_id=? AND state=2",r->r.getBytes(1),original,kind,id(entity));}
    private static void reverseOriginal(Sql sql,ZoneMergeRunRow run,ZoneMergeEntityOriginal current)throws SQLException{
        var before=sql.one("SELECT original_proto,after_epoch,after_digest FROM zone_merge_item WHERE run_id=? AND kind=? AND entity_id=? AND state=2",r->decode(r.getBytes(1)).toBuilder().setOwnerEpoch(unsigned(r,"after_epoch")).setRowDigest(ByteString.copyFrom(r.getBytes(3))).build(),run.getOriginalRunId(),current.getKind(),id(current.getEntityId()));
        require(before!=null&&before.getAuthorityPlayerId()==current.getAuthorityPlayerId()&&before.getOwnerAccount().equals(current.getOwnerAccount())&&before.getOwnerRevision()==current.getOwnerRevision()&&before.getOwnerEpoch()==current.getOwnerEpoch()&&before.getRowDigest().equals(current.getRowDigest()),"原合区实体/原owner/E已经变化，拒绝撤销");
    }
    /** 每次至多16实体；返回phase4/state2才表示完整计划。phase内按实体uint64主键，不以数组下标表达顺序。 */
    public ZoneMergeRunRow plan(String key)throws SQLException{uuid(key);return tx(sql->{var hint=held(sql,key);require(hint.getState()==1||hint.getState()==2,"原run不在计划阶段");if(hint.getState()==2)return hint;
        int phase=hint.getPhase();String table=phase==1?"player":phase==2?"guild":"trade_listing",pk=phase==1?"player_id":phase==2?"guild_id":"listing_id",zone=phase==3?"market_zone":"zone_id";
        var entities=sql.list("SELECT "+pk+" FROM "+table+" WHERE "+zone+" IN(?,?) AND "+pk+">? ORDER BY "+pk+" LIMIT "+BATCH,r->unsigned(r,pk),Integer.toUnsignedLong(hint.getSourceZone()),Integer.toUnsignedLong(hint.getTargetZone()),id(hint.getCursorId()));
        var people=new ArrayList<Long>();if(phase==1)people.addAll(entities);else for(long entity:entities){String actor=phase==2?"leader_id":"seller_player_id";Long owner=sql.one("SELECT "+actor+" FROM "+table+" WHERE "+pk+"=?",r->unsigned(r,actor),id(entity));require(owner!=null&&owner!=0,"实体缺少真实领导/卖家角色，不能用目录猜原owner");people.add(owner);}
        lockPeople(sql,people);var current=run(sql,key,true);require(current.equals(hint),"计划游标已变化");byte[] hash=current.getPlanHash().toByteArray();long count=current.getItemCount();
        for(int i=0;i<entities.size();i++){long entity=entities.get(i);idle(sql,people.get(i));int move=phase==1?1:phase==2?3:4,guard=phase==1?2:phase==2?5:6;
            var row=snapshot(sql,move,entity,current.getTargetZone());require(row!=null,"计划实体已缺失");boolean moving=current.getMode()==1?row.getFromZone()==current.getSourceZone():originalAfter(sql,current.getOriginalRunId(),move,entity)!=null;
            if(current.getMode()==2&&moving)reverseOriginal(sql,current,row);
            int kind=moving?move:guard;row=row.toBuilder().setKind(kind).setToZone(moving?current.getTargetZone():row.getFromZone()).build();
            if(phase==2){require(sql.count("SELECT COUNT(*) FROM guild_trial_lobby WHERE guild_id=? AND (state IS NULL OR state<>4)",id(entity))==0,"帮会仍有未终局历练大厅");require(sql.count("SELECT COUNT(*) FROM guild_trial_battle WHERE guild_id=? AND (state IS NULL OR state NOT IN(2,4))",id(entity))==0,"帮会仍有未终局历练对局");
                require(sql.count("SELECT COUNT(*) FROM guild_asset_op WHERE guild_id=? AND (status IS NULL OR status NOT IN(2,3,4))",id(entity))==0,"帮会仍有未终结资产操作");require(sql.count("SELECT COUNT(*) FROM guild_activity_reward_owed WHERE guild_id=? AND (status IS NULL OR status<>1)",id(entity))==0,"帮会仍有待发奖励");}
            if(phase==3)require(sql.count("SELECT COUNT(*) FROM trade_listing WHERE listing_id=? AND status IN(2,4,6,7)",id(entity))==1,"商品托管/订单/退款或未知状态未终结");
            byte[] raw=row.toByteArray();sql.update("INSERT INTO zone_merge_item(run_id,kind,entity_id,original_proto,state,after_digest,after_epoch) VALUES(?,?,?,?,1,?,0)",key,kind,id(entity),raw,new byte[0]);hash=chain(hash,raw);count++;
        }
        long cursor=entities.isEmpty()?0:entities.getLast();int next=entities.size()<BATCH?phase+1:phase;if(next!=phase)cursor=0;
        if(next==4){require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND kind=1",key)==current.getExpectedPlayers(),"完整计划迁移人数与原意图不符");if(current.getMode()==2)for(int kind:List.of(1,3,4))require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND kind=?",key,kind)==sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND kind=?",current.getOriginalRunId(),kind),"撤销计划缺少原迁入实体");}
        sql.update("UPDATE zone_merge_run SET phase=?,cursor_id=?,item_count=?,plan_hash=?,state=?,updated_ms=? WHERE run_id=?",next,id(cursor),count,hash,next==4?2:1,sql.now(),key);return run(sql,key,false);
    });}
    private static void approvedHash(ZoneMergeRunRow row,String hex)throws SQLException{require(hex!=null&&hex.matches("[0-9a-f]{64}")&&MessageDigest.isEqual(HexFormat.of().parseHex(hex),row.getPlanHash().toByteArray()),"审批hash必须精确匹配永久完整计划");}
    public ZoneMergeRunRow approve(String key,String hash,String approvedBy)throws SQLException{uuid(key);operator(approvedBy);return tx(sql->{held(sql,key);var r=run(sql,key,true);approvedHash(r,hash);require(r.getState()==2||r.getState()==3,"计划不完整或已进入另一阶段");if(r.getState()==3){require(r.getApprovedBy().equals(approvedBy),"审批原件不能改写");return r;}sql.update("UPDATE zone_merge_run SET state=3,approved_by=?,updated_ms=? WHERE run_id=?",approvedBy,sql.now(),key);return run(sql,key,false);});}
    /** 一批实体CAS和item状态同事务；提交未知时重读item，绝不换清单或审批hash。 */
    public ZoneMergeRunRow apply(String key,String hash)throws SQLException{uuid(key);return tx(sql->{var hint=held(sql,key);approvedHash(hint,hash);require(List.of(3,4,5).contains(hint.getState()),"原run尚未完整审批");if(hint.getState()==5)return hint;
        var originals=sql.list("SELECT original_proto FROM zone_merge_item WHERE run_id=? AND state=1 ORDER BY kind,entity_id LIMIT "+BATCH,r->decode(r.getBytes(1)),key);
        var people=new ArrayList<Long>();for(var original:originals){if(original.getKind()<=2)people.add(original.getEntityId());else{String actor=original.getKind()==3||original.getKind()==5?"leader_id":"seller_player_id";Long owner=sql.one("SELECT "+actor+" FROM "+table(original.getKind())+" WHERE "+pk(original.getKind())+"=?",r->unsigned(r,actor),id(original.getEntityId()));require(owner!=null&&owner!=0,"计划实体的原角色缺失");people.add(owner);}}
        lockPeople(sql,people);var run=run(sql,key,true);require(run.equals(hint),"原run阶段并发变化");
        for(int index=0;index<originals.size();index++){var original=originals.get(index);int kind=original.getKind();long entity=original.getEntityId();idle(sql,people.get(index));
            var stored=sql.one("SELECT original_proto,state FROM zone_merge_item WHERE run_id=? AND kind=? AND entity_id=? FOR UPDATE",r->{require(r.getInt(2)==1,"计划条目状态已变化");return decode(r.getBytes(1));},key,kind,id(entity));require(original.equals(stored),"维护清单原件发生变化");
            var current=snapshot(sql,kind,entity,original.getToZone());long expectedEpoch=original.getOwnerEpoch();
            if(kind>2){Long movedEpoch=sql.one("SELECT after_epoch FROM zone_merge_item WHERE run_id=? AND kind=1 AND entity_id=? AND state=2",r->unsigned(r,"after_epoch"),key,id(original.getAuthorityPlayerId()));if(movedEpoch!=null)expectedEpoch=movedEpoch;}
            require(current!=null&&current.equals(original.toBuilder().setOwnerEpoch(expectedEpoch).build()),"实体与审批原件或原owner不符，保留冻结");
            if(kind==1){require(!original.getOwnerAccount().isEmpty()&&original.getOwnerRevision()>0&&original.getOwnerEpoch()>=0&&original.getOwnerEpoch()<Long.MAX_VALUE,"原owner或E不能推进");
                require(sql.update("UPDATE player SET zone_id=?,owner_epoch=owner_epoch+1,owner_released=1,owner_lease_until=0 WHERE player_id=? AND zone_id=? AND BINARY account=? AND owner_revision=? AND owner_epoch=? AND owner_released=1 AND trade_listing_id=0",Integer.toUnsignedLong(original.getToZone()),id(entity),Integer.toUnsignedLong(original.getFromZone()),original.getOwnerAccount(),id(original.getOwnerRevision()),id(original.getOwnerEpoch()))==1,"角色归属CAS失败");
            }else if(kind==3||kind==4)require(sql.update("UPDATE "+table(kind)+" SET "+zoneColumn(kind)+"=? WHERE "+pk(kind)+"=? AND "+zoneColumn(kind)+"=?",Integer.toUnsignedLong(original.getToZone()),id(entity),Integer.toUnsignedLong(original.getFromZone()))==1,"实体归属CAS失败");
            var after=snapshot(sql,kind,entity,original.getToZone());require(sql.update("UPDATE zone_merge_item SET state=2,after_digest=?,after_epoch=? WHERE run_id=? AND kind=? AND entity_id=? AND state=1",after.getRowDigest().toByteArray(),id(after.getOwnerEpoch()),key,kind,id(entity))==1,"维护条目提交CAS失败");
        }
        sql.update("UPDATE zone_merge_run SET state=4,updated_ms=? WHERE run_id=?",sql.now(),key);return run(sql,key,false);
    });}
    /** 全部实体已提交才建立/撤销精确marker。仍保留meta0与围栏，等待独立显式Release。 */
    public ZoneMergeRunRow finish(String key,String hash)throws SQLException{uuid(key);return tx(sql->{held(sql,key);var r=run(sql,key,true);approvedHash(r,hash);require(r.getState()==4||r.getState()==5,"原run尚未执行");if(r.getState()==5)return r;require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND (state IS NULL OR state<>2)",key)==0,"清单尚未完成，必须续跑同一批次");
        verifyItems(sql,r);if(r.getMode()==1){require(sql.count("SELECT COUNT(*) FROM player WHERE zone_id=?",Integer.toUnsignedLong(r.getSourceZone()))==0&&sql.count("SELECT COUNT(*) FROM guild WHERE zone_id=?",Integer.toUnsignedLong(r.getSourceZone()))==0&&sql.count("SELECT COUNT(*) FROM trade_listing WHERE market_zone=?",Integer.toUnsignedLong(r.getSourceZone()))==0,"源区出现清单外实体，不能静默补抓");
            sql.update("INSERT INTO zone_merged_into(zone_id,target_zone,run_id,revision) VALUES(?,?,?,1)",Integer.toUnsignedLong(r.getSourceZone()),Integer.toUnsignedLong(r.getTargetZone()),key);
            require(sql.update("UPDATE zone_config SET manual_status=2,maintenance_msg=?,updated_at=? WHERE zone_id=? AND manual_status IN(1,2)","本区已合并至 "+Integer.toUnsignedString(r.getTargetZone())+" 区，请选择目标区登录",sql.now(),Integer.toUnsignedLong(r.getSourceZone()))==1,"源区目录退役CAS失败");
        }else{require(sql.update("DELETE FROM zone_merged_into WHERE zone_id=? AND target_zone=? AND run_id=?",Integer.toUnsignedLong(r.getTargetZone()),Integer.toUnsignedLong(r.getSourceZone()),r.getOriginalRunId())==1,"撤销marker原件不符");
            require(sql.update("UPDATE zone_config SET manual_status=1,maintenance_msg='',updated_at=? WHERE zone_id=? AND manual_status IN(1,2)",sql.now(),Integer.toUnsignedLong(r.getTargetZone()))==1,"撤销后源目录必须仍处维护，不能自动开服");}
        sql.update("UPDATE zone_merge_run SET state=5,updated_ms=? WHERE run_id=?",sql.now(),key);return run(sql,key,false);
    });}
    // finish/release使用聚合联表复核，不无界攒实体；所有语句及提交共用30秒预算，超时保留冻结。
    private static void verifyItems(Sql sql,ZoneMergeRunRow r)throws SQLException{
        require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=?",r.getRunId())==r.getItemCount(),"清单数量不符");
        // 每个归属目标与迁后E由SQL联表复核，原件指纹在apply每批已经核验；冻结期间普通写者均被meta0挡住。
        require(sql.count("SELECT COUNT(*) FROM zone_merge_item i LEFT JOIN player p ON p.player_id=i.entity_id WHERE i.run_id=? AND i.kind=1 AND (p.player_id IS NULL OR p.zone_id<>? OR p.owner_epoch<>i.after_epoch OR p.owner_released<>1)",r.getRunId(),Integer.toUnsignedLong(r.getTargetZone()))==0,"迁后玩家/E不符");
        require(sql.count("SELECT COUNT(*) FROM zone_merge_item i LEFT JOIN guild g ON g.guild_id=i.entity_id WHERE i.run_id=? AND i.kind=3 AND (g.guild_id IS NULL OR g.zone_id<>?)",r.getRunId(),Integer.toUnsignedLong(r.getTargetZone()))==0,"迁后帮会归属不符");
        require(sql.count("SELECT COUNT(*) FROM zone_merge_item i LEFT JOIN trade_listing l ON l.listing_id=i.entity_id WHERE i.run_id=? AND i.kind=4 AND (l.listing_id IS NULL OR l.market_zone<>?)",r.getRunId(),Integer.toUnsignedLong(r.getTargetZone()))==0,"迁后商品归属不符");
    }
    /** 尚未写任何实体的计划可显式放弃；仍需Release带hash审核，绝不自动清冻。 */
    public ZoneMergeRunRow abort(String key,String hash,String approvedBy)throws SQLException{uuid(key);operator(approvedBy);return tx(sql->{held(sql,key);var r=run(sql,key,true);approvedHash(r,hash);require(List.of(1,2,3,7).contains(r.getState())&&sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND state<>1",key)==0,"已写实体不得放弃，必须续跑原run后按原件撤销");if(r.getState()!=7)sql.update("UPDATE zone_merge_run SET state=7,approved_by=?,updated_ms=? WHERE run_id=?",approvedBy,sql.now(),key);return run(sql,key,false);});}
    /** P7/P8同一维护事务复核真实域终局；不发控制授权、不清围栏、不执行补偿。 */
    public static ZoneMergeRunRow auditReleased(Connection connection,String key)throws SQLException{
        uuid(key);require(!connection.getAutoCommit(),"合区审核必须在控制器已有事务中");var sql=new Sql(connection);String generation=meta(sql,0);
        byte[] operation=runBytes(key);byte[] actual=sql.one("SELECT freeze_run_id FROM route_meta WHERE id=1",r->r.getBytes(1));require(Arrays.equals(operation,actual),"域审核不是同一冻结run");
        var run=run(sql,key,true);require(run!=null&&run.getState()==6&&run.getGeneration().equals(generation)&&!run.getReleasedBy().isBlank(),"域run尚无真实显式release终局");
        require(sql.count("SELECT COUNT(*) FROM zone_merge_fence WHERE zone_id IN(?,?) AND run_id=''",Integer.toUnsignedLong(run.getSourceZone()),Integer.toUnsignedLong(run.getTargetZone()))==128,"两区完整围栏尚未按域终局清理");
        require(sql.count("SELECT COUNT(*) FROM player WHERE owner_released IS NULL OR owner_released<>1")==0,"域审核存在未释放玩家写者");
        require(sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=?",key)==run.getItemCount()&&sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND (state IS NULL OR state NOT IN(1,2))",key)==0,"域清单数量或状态不同");
        long done=sql.count("SELECT COUNT(*) FROM zone_merge_item WHERE run_id=? AND state=2",key);require(done==0||done==run.getItemCount(),"域清单仅部分提交");
        long merged=sql.count("SELECT COUNT(*) FROM zone_merged_into WHERE zone_id=? AND target_zone=? AND run_id=?",Integer.toUnsignedLong(run.getSourceZone()),Integer.toUnsignedLong(run.getTargetZone()),key);
        long original=run.getMode()==2?sql.count("SELECT COUNT(*) FROM zone_merged_into WHERE zone_id=? AND target_zone=? AND run_id=?",Integer.toUnsignedLong(run.getTargetZone()),Integer.toUnsignedLong(run.getSourceZone()),run.getOriginalRunId()):0;
        boolean completed=done>0||run.getItemCount()==0&&(run.getMode()==1&&merged==1||run.getMode()==2&&original==0);
        require(run.getMode()==1||run.getMode()==2,"域维护模式未知");
        if(completed){
            require(done==run.getItemCount(),"已完成域清单未全部提交");verifyItems(sql,run);
            if(run.getMode()==1){require(merged==1&&sql.count("SELECT COUNT(*) FROM zone_config WHERE zone_id=? AND manual_status=2",Integer.toUnsignedLong(run.getSourceZone()))==1,"合区目录终局不同");require(sql.count("SELECT COUNT(*) FROM player WHERE zone_id=?",Integer.toUnsignedLong(run.getSourceZone()))==0&&sql.count("SELECT COUNT(*) FROM guild WHERE zone_id=?",Integer.toUnsignedLong(run.getSourceZone()))==0&&sql.count("SELECT COUNT(*) FROM trade_listing WHERE market_zone=?",Integer.toUnsignedLong(run.getSourceZone()))==0,"源区仍有清单外实体");}
            else require(original==0&&sql.count("SELECT COUNT(*) FROM zone_config WHERE zone_id=? AND manual_status=1",Integer.toUnsignedLong(run.getTargetZone()))==1,"撤销目录未恢复到维护态");
        }else require(done==0&&(run.getMode()==1?merged==0:original==1),"安全放弃的原目录被改变");
        return run;
    }
    /** 只解除本域围栏并固定域完成收据；全局SQL保持FROZEN，由唯一operator后续显式release。 */
    public ZoneMergeRunRow release(String key,String hash,String generation,String releasedBy)throws SQLException{uuid(key);uuid(generation);operator(releasedBy);return tx(sql->{
        frozenAuthority(sql,key);var receipt=run(sql,key,false);
        if(receipt!=null&&receipt.getState()==6){approvedHash(receipt,hash);require(receipt.getGeneration().equals(generation)&&receipt.getReleasedBy().equals(releasedBy),"解冻原回执与重试参数不同");return receipt;}
        var hint=held(sql,key);require(hint.getGeneration().equals(generation),"解冻必须携带外部保留的原路由世代");var r=run(sql,key,true);approvedHash(r,hash);require(r.getState()==5||r.getState()==7,"未完成或未安全放弃的run禁止解冻");if(r.getState()==5)verifyItems(sql,r);
        require(sql.count("SELECT COUNT(*) FROM player WHERE owner_released IS NULL OR owner_released<>1")==0,"全服出现未释放写者，拒绝解冻");
        require(sql.update("UPDATE zone_merge_fence SET run_id='',revision=revision+1 WHERE zone_id IN(?,?) AND run_id=? AND revision<9223372036854775807",Integer.toUnsignedLong(r.getSourceZone()),Integer.toUnsignedLong(r.getTargetZone()),key)==128,"解冻围栏原件CAS失败");
        sql.update("UPDATE zone_merge_run SET state=6,released_by=?,updated_ms=? WHERE run_id=?",releasedBy,sql.now(),key);return run(sql,key,false);
    });}
}
