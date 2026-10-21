package com.game.data.merge;

import com.game.common.player.PlayerRouteAuthority;
import com.game.data.merge.pb.ZoneMergeEntityOriginal;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.player.store.RolePendingFence;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/**
 * 单库合区维护的完整提交面。调用者只推进一个有界阶段，不拼装内部锁序。
 * 显式Freeze持metaX排空正常事务；整个namespace短维护，直到人工带原计划hash调用Release。
 * 每步metaX→两区64片X→目录→全P无符号升序→run/items→实体；异常回滚且永久冻结不变。
 * 没有租约解冻、后台自动放行、Redis事实或原owner补权；阻塞JDBC只在运维工作线程/CLI调用。
 */
public final class ZoneMergeStore {
    public static final int BATCH=16;
    private final DataSource source;
    public ZoneMergeStore(DataSource source){this.source=Objects.requireNonNull(source);}
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
        columns.put("route_meta","id,generation,writable,fence_shards");columns.put("zone_merge_fence","zone_id,shard,run_id,revision");columns.put("zone_merged_into","zone_id,target_zone,run_id,revision");
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
    private static ZoneMergeRunRow held(Sql sql,String key)throws SQLException{
        String generation=meta(sql,0);var hint=run(sql,key,false);require(hint!=null&&hint.getState()!=6,"维护原run不存在或已显式释放");
        require(generation.equals(hint.getGeneration()),"路由世代已改变，不能从历史run补权");fences(sql,hint,false);directory(sql,hint.getSourceZone(),hint.getTargetZone());return hint;
    }
    /** 显式全服Freeze；调用前应先停准入并真实排空所有scene。不是服务自动维护开关。 */
    public ZoneMergeRunRow freeze(String key,int from,int to,long expectedSourcePlayers,String requestedBy)throws SQLException{return freeze(key,from,to,expectedSourcePlayers,requestedBy,"");}
    public ZoneMergeRunRow freeze(String key,int from,int to,long expectedSourcePlayers,String requestedBy,String originalKey)throws SQLException{
        uuid(key);zones(from,to);operator(requestedBy);if(expectedSourcePlayers<0)throw new IllegalArgumentException("源玩家计数必须显式且非负");if(!originalKey.isEmpty())uuid(originalKey);
        return tx(sql->{
            verifySchema(sql.c);
            // meta必须是锁序第一把。相同UUID只回原原件，不改参数、不重新抓取清单。
            Integer state=sql.one("SELECT writable FROM route_meta WHERE id=1 FOR UPDATE",r->r.getObject(1)==null?null:r.getInt(1));require(state!=null,"缺少显式初始化meta");
            var prior=run(sql,key,false);if(prior!=null){require(prior.getSourceZone()==from&&prior.getTargetZone()==to&&prior.getExpectedPlayers()==expectedSourcePlayers&&prior.getOriginalRunId().equals(originalKey)&&prior.getRequestedBy().equals(requestedBy),"run UUID被另一份维护意图使用");return prior;}
            String generation=meta(sql,1);int mode=originalKey.isEmpty()?1:2;
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
            require(sql.update("UPDATE route_meta SET writable=0 WHERE id=1 AND writable=1")==1,"冻结meta CAS失败");
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
    /** 唯一显式解冻入口；不是启动初始化、超时回收或服务后台动作。 */
    public ZoneMergeRunRow release(String key,String hash,String generation,String releasedBy)throws SQLException{uuid(key);uuid(generation);operator(releasedBy);return tx(sql->{
        require(sql.one("SELECT id FROM route_meta WHERE id=1 FOR UPDATE",row->row.getInt(1))!=null,"缺少路由meta");var receipt=run(sql,key,false);
        if(receipt!=null&&receipt.getState()==6){approvedHash(receipt,hash);require(receipt.getGeneration().equals(generation)&&receipt.getReleasedBy().equals(releasedBy),"解冻原回执与重试参数不同");return receipt;}
        var hint=held(sql,key);require(hint.getGeneration().equals(generation),"解冻必须携带外部保留的原路由世代");var r=run(sql,key,true);approvedHash(r,hash);require(r.getState()==5||r.getState()==7,"未完成或未安全放弃的run禁止解冻");if(r.getState()==5)verifyItems(sql,r);
        require(sql.count("SELECT COUNT(*) FROM player WHERE owner_released IS NULL OR owner_released<>1")==0,"全服出现未释放写者，拒绝解冻");
        require(sql.update("UPDATE zone_merge_fence SET run_id='',revision=revision+1 WHERE zone_id IN(?,?) AND run_id=? AND revision<9223372036854775807",Integer.toUnsignedLong(r.getSourceZone()),Integer.toUnsignedLong(r.getTargetZone()),key)==128,"解冻围栏原件CAS失败");
        require(sql.update("UPDATE route_meta SET writable=1 WHERE id=1 AND generation=? AND writable=0",generation)==1,"解冻meta世代CAS失败");sql.update("UPDATE zone_merge_run SET state=6,released_by=?,updated_ms=? WHERE run_id=?",releasedBy,sql.now(),key);return run(sql,key,false);
    });}
}
