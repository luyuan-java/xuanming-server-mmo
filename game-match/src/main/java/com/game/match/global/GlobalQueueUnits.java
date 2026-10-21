package com.game.match.global;

import com.game.match.global.pb.*;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.ByteString;
import java.sql.*;
import java.util.*;

/** PVE整队与单排共用单位，大小桶先完整资格过滤再LIMIT，最多25候选做精确装箱。 */
final class GlobalQueueUnits {
    private final PbMysql db;
    private final GlobalMatchProperties config;
    private final boolean allowCrossZone;
    private long repairMs,upperMs;
    private String repairId="",upperId="";
    private boolean scanning,repaired;
    GlobalQueueUnits(PbMysql db,GlobalMatchProperties config,boolean allowCrossZone){this.db=db;this.config=config;this.allowCrossZone=allowCrossZone;db.register(GlobalQueueUnitRow.getDefaultInstance());db.register(GlobalQueueUnitRegionRow.getDefaultInstance());}
    static String soloId(GlobalTicketRow row){return "s:"+Long.toUnsignedString(row.getPlayerId())+":"+row.getTicket();}
    static String teamId(com.game.proto.team.TeamLaunchOriginal original){return "t:"+Long.toUnsignedString(original.getTeamId())+":"+original.getRequest().getRequestId();}
    GlobalQueueUnitRow find(Connection c,String id,boolean lock)throws SQLException {
        var key=GlobalQueueUnitRow.newBuilder().setUnitId(id).build();return (lock?db.findOneByPkForUpdate(c,key):db.findOneByPk(c,key)).orElse(null);
    }
    private List<GlobalTicketRow> current(Connection c,GlobalQueueUnitRow unit)throws SQLException {
        List<GlobalTicketRow> result=new ArrayList<>();for(var original:unit.getOriginalTicketsList()){
            var row=db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(original.getPlayerId()).build()).orElseThrow();
            if(!row.getQueueUnitId().equals(unit.getUnitId())||!row.getTicket().equals(original.getTicket())||!row.getOwnerAccount().equals(original.getOwnerAccount())||row.getOwnerRevision()!=original.getOwnerRevision())throw new SQLException("完整匹配单位原票或归属不同");result.add(row);
        }return result;
    }
    private GlobalQueueUnitRow shape(Connection c,GlobalQueueUnitRow base,List<GlobalTicketRow> rows)throws Exception {
        if(rows.isEmpty()||rows.size()>5||rows.size()!=base.getMemberCount()||rows.size()>base.getTargetSize())throw new SQLException("完整匹配单位人数无效");
        long expiry=Long.MAX_VALUE,notBefore=0;int preferred=rows.getFirst().getPreferredRegion(),home=rows.getFirst().getZoneId();
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        for(var row:rows){
            if(row.getMode()!=base.getMode()||row.getConfigId()!=base.getConfigId()||row.getTeamSize()!=base.getTargetSize()||!row.getQueueUnitId().equals(base.getUnitId()))throw new SQLException("匹配单位lane或票据关联不同");
            if(row.getZoneId()==0||row.getZoneId()!=home(c,row.getPlayerId()))throw new SQLException("匹配单位原home与已锁路由不同，不能重写旧投影");
            if(home!=row.getZoneId())home=0;
            expiry=Math.min(expiry,row.getExpiresMs());notBefore=Math.max(notBefore,row.getNotBeforeMs());if(row.getPreferredRegion()!=preferred)preferred=0;
            byte[] bytes=com.game.common.token.NetworkProbes.bytes(row);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
        }
        if(!allowCrossZone&&home==0)throw new SQLException("Team策略禁止混合home的完整匹配单位");
        return base.toBuilder().setHomeZone(home).clearOriginalTickets().addAllOriginalTickets(rows).setExpiresMs(expiry).setNotBeforeMs(notBefore).setPreferredRegion(preferred).setProfileDigest(ByteString.copyFrom(hash.digest())).build();
    }
    static int home(Connection c,long player)throws SQLException {
        try(var q=c.prepareStatement("SELECT zone_id FROM player WHERE player_id=?")){q.setQueryTimeout(1);q.setObject(1,PbMysql.uint64(player));try(var row=q.executeQuery()){
            if(!row.next())throw new SQLException("匹配单位缺少真实home");long zone=row.getLong(1);if(row.wasNull()||zone==0||zone>0xffff_ffffL)throw new SQLException("匹配单位真实home无效");return (int)zone;
        }}
    }
    void create(Connection c,List<GlobalTicketRow> rows,ByteString launch,long stamp)throws Exception {
        var first=rows.getFirst();var base=GlobalQueueUnitRow.newBuilder().setUnitId(first.getQueueUnitId()).setState(1).setMode(first.getMode()).setConfigId(first.getConfigId())
            .setTargetSize(first.getTeamSize()).setMemberCount(rows.size()).setEnqueuedMs(first.getEnqueuedMs()).setTeamId(first.getTeamId()).setLaunchOriginal(launch).build();
        var unit=shape(c,base,rows);db.insert(c,unit);replace(c,unit,stamp);
    }
    void refresh(Connection c,String id,long stamp)throws Exception {
        if(id.isEmpty())return;var unit=find(c,id,true);if(unit==null)throw new SQLException("原票缺少完整匹配单位");
        if(unit.getState()!=1)return;var rows=current(c,unit);if(rows.stream().anyMatch(row->row.getState()!=1))throw new SQLException("排队单位存在半单票据状态");
        var updated=shape(c,unit,rows);db.updateByPk(c,updated);replace(c,updated,stamp);
    }
    private void clear(Connection c,String id)throws SQLException {try(var q=c.prepareStatement("DELETE FROM match_global_queue_unit_region WHERE unit_id=?")){q.setQueryTimeout(3);q.setString(1,id);q.executeUpdate();}}
    private void replace(Connection c,GlobalQueueUnitRow unit,long stamp)throws Exception {
        clear(c,unit.getUnitId());for(var entry:projections(unit,stamp))db.insert(c,entry);
    }
    private List<GlobalQueueUnitRegionRow> projections(GlobalQueueUnitRow unit,long stamp)throws Exception {
        if(unit.getState()!=1||unit.getExpiresMs()<=stamp)return List.of();
        Map<Integer,Integer> intersection=null;
        for(var ticket:unit.getOriginalTicketsList()){
            var rtts=GlobalRegionPolicy.latencies(GlobalProbeProfile.parseFrom(ticket.getProfile()),stamp,Integer.MAX_VALUE);
            if(rtts.size()>16)throw new SQLException("单位原profile超过16机房");
            if(intersection==null)intersection=new TreeMap<>(rtts);
            else {var target=intersection;target.keySet().removeIf(region->!rtts.containsKey(region));target.replaceAll((region,rtt)->Math.max(rtt,rtts.get(region)));}
        }
        if(intersection==null)return List.of();List<GlobalQueueUnitRegionRow> result=new ArrayList<>();
        for(var region:intersection.entrySet())result.add(GlobalQueueUnitRegionRow.newBuilder().setUnitId(unit.getUnitId()).setRegion(region.getKey()).setMode(unit.getMode()).setConfigId(unit.getConfigId())
            .setTargetSize(unit.getTargetSize()).setMemberCount(unit.getMemberCount()).setPreferredRegion(unit.getPreferredRegion()).setEnqueuedMs(unit.getEnqueuedMs())
            .setNotBeforeMs(unit.getNotBeforeMs()).setExpiresMs(unit.getExpiresMs()).setMaxRttMs(region.getValue()).setProfileDigest(unit.getProfileDigest()).setHomeZone(unit.getHomeZone()).build());return result;
    }
    record Repair(long afterMs,String afterId,long upperMs,String upperId,boolean scanning,boolean complete,List<String> expired){}
    Repair repair(Connection c,long stamp)throws Exception {
        long highMs=upperMs;String highId=upperId;
        if(!scanning){var tail=db.findAll(c,GlobalQueueUnitRow.class,"state=1 ORDER BY enqueued_ms DESC,unit_id DESC LIMIT 1");highMs=tail.isEmpty()?0:tail.getFirst().getEnqueuedMs();highId=tail.isEmpty()?"":tail.getFirst().getUnitId();}
        var rows=db.findAll(c,GlobalQueueUnitRow.class,"state=1 AND (enqueued_ms>? OR (enqueued_ms=? AND unit_id>?)) AND (enqueued_ms<? OR (enqueued_ms=? AND unit_id<=?)) ORDER BY enqueued_ms,unit_id LIMIT 32",repairMs,repairMs,repairId,highMs,highMs,highId);
        Map<String,GlobalQueueUnitRow> locked=new HashMap<>();for(String id:rows.stream().map(GlobalQueueUnitRow::getUnitId).sorted().toList())locked.put(id,find(c,id,true));
        List<String> expired=new ArrayList<>();long nextMs=repairMs;String nextId=repairId;
        for(var candidate:rows){var unit=locked.get(candidate.getUnitId());nextMs=candidate.getEnqueuedMs();nextId=candidate.getUnitId();if(unit==null)throw new SQLException("单位修复原件缺失");
            if(unit.getState()==1){
                if(unit.getExpiresMs()<=stamp&&expired.size()<2)expired.add(unit.getUnitId());
                if(!shape(c,unit,current(c,unit)).equals(unit))throw new SQLException("单位与原票投影不一致，禁止覆盖原件");
            }
            var expected=projections(unit,stamp);var actual=db.findAll(c,GlobalQueueUnitRegionRow.class,"unit_id=? LIMIT 17",unit.getUnitId());
            if(!new HashSet<>(actual).equals(new HashSet<>(expected))){clear(c,unit.getUnitId());for(var entry:expected)db.insert(c,entry);}
        }
        try(var q=c.prepareStatement("DELETE FROM match_global_queue_unit_region WHERE expires_ms<=? LIMIT 64")){q.setQueryTimeout(1);q.setLong(1,stamp);q.executeUpdate();}
        if(rows.size()<32){nextMs=0;nextId="";}
        return new Repair(nextMs,nextId,highMs,highId,rows.size()==32,repaired||rows.size()<32,List.copyOf(expired));
    }
    void committed(Repair p){repairMs=p.afterMs();repairId=p.afterId();upperMs=p.upperMs();upperId=p.upperId();scanning=p.scanning();repaired=p.complete();}
    List<GlobalQueueUnitRow> anchors(Connection c,long afterMs,String afterId)throws SQLException {
        return db.findAll(c,GlobalQueueUnitRow.class,"state=1 AND (enqueued_ms>? OR (enqueued_ms=? AND unit_id>?)) ORDER BY enqueued_ms,unit_id LIMIT 32",afterMs,afterMs,afterId);
    }
    List<GlobalQueueUnitRow> peers(Connection c,GlobalQueueUnitRow anchor,int region,boolean cross,long stamp,com.game.common.deadline.Deadline deadline)throws SQLException {
        List<GlobalQueueUnitRow> peers=new ArrayList<>();int remaining=anchor.getTargetSize()-anchor.getMemberCount();
        for(int size=1;size<=remaining;size++){
            if(deadline.expired())throw new SQLTimeoutException("整队候选共享预算到期");
            c.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(1000,deadline.remainingMillis())));
            String query="SELECT u.unit_id FROM match_global_queue_unit_region r JOIN match_global_queue_unit u ON u.unit_id=r.unit_id AND u.profile_digest=r.profile_digest "
                +"AND u.mode=r.mode AND u.config_id=r.config_id AND u.target_size=r.target_size AND u.member_count=r.member_count AND u.enqueued_ms=r.enqueued_ms "
                +"AND u.not_before_ms=r.not_before_ms AND u.expires_ms=r.expires_ms AND u.preferred_region=r.preferred_region AND u.home_zone=r.home_zone "
                +"WHERE r.region=? AND r.mode=? AND r.config_id=? AND r.target_size=? AND r.member_count=? AND r.max_rtt_ms BETWEEN 1 AND ? "
                +"AND u.state=1 AND r.expires_ms>? AND r.not_before_ms<=? AND u.unit_id<>? ";
            if(cross)query+="AND r.enqueued_ms<=? ";else query+="AND r.preferred_region=? ";
            if(!allowCrossZone){if(anchor.getHomeZone()==0)throw new SQLException("禁跨home的锚点缺真实home");query+="AND r.home_zone=? ";}
            query+="ORDER BY r.enqueued_ms,r.unit_id LIMIT 5";
            try(var q=c.prepareStatement(query)){q.setQueryTimeout(1);q.setLong(1,Integer.toUnsignedLong(region));q.setLong(2,Integer.toUnsignedLong(anchor.getMode()));q.setLong(3,Integer.toUnsignedLong(anchor.getConfigId()));q.setInt(4,anchor.getTargetSize());q.setInt(5,size);q.setInt(6,config.hardLatencyMs());
                q.setLong(7,stamp);q.setLong(8,stamp);q.setString(9,anchor.getUnitId());if(cross)q.setLong(10,Math.max(0,stamp-config.waitSeconds()*1000L));else q.setInt(10,region);if(!allowCrossZone)q.setLong(11,Integer.toUnsignedLong(anchor.getHomeZone()));
                try(var rows=q.executeQuery()){while(rows.next()){if(deadline.expired())throw new SQLTimeoutException("整队候选共享预算到期");c.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(1000,deadline.remainingMillis())));var unit=find(c,rows.getString(1),false);if(unit==null)throw new SQLException("候选单位在读取间消失");peers.add(unit);}}
            }
        }return peers;
    }
    static List<GlobalQueueUnitRow> pack(GlobalQueueUnitRow anchor,List<GlobalQueueUnitRow> candidates){
        int target=anchor.getTargetSize(),left=target-anchor.getMemberCount();
        if(target<1||target>5||left<0||candidates.size()>25)throw new IllegalArgumentException("单位精确装箱超出预算");
        List<List<GlobalQueueUnitRow>> dp=new ArrayList<>(Collections.nCopies(left+1,null));dp.set(0,List.of());
        for(var candidate:candidates){int count=candidate.getMemberCount();if(count<1||count>left)continue;
            for(int size=left;size>=count;size--)if(dp.get(size)==null&&dp.get(size-count)!=null){var picked=new ArrayList<>(dp.get(size-count));picked.add(candidate);dp.set(size,List.copyOf(picked));}}
        if(dp.get(left)==null)return List.of();var result=new ArrayList<GlobalQueueUnitRow>();result.add(anchor);result.addAll(dp.get(left));return List.copyOf(result);
    }
    List<GlobalTicketRow> members(List<GlobalQueueUnitRow> units){return units.stream().flatMap(u->u.getOriginalTicketsList().stream()).toList();}
    /** 调用方已完成全员route/P；先全Team，再原launch，再单位，最后才可拿票/F。 */
    void claim(Connection c,List<GlobalTicketRow> expected,GlobalFormationRow formation)throws Exception {
        var ids=expected.stream().map(GlobalTicketRow::getQueueUnitId).filter(id->!id.isEmpty()).distinct().sorted().toList();if(ids.isEmpty())return;
        if(expected.stream().anyMatch(t->t.getQueueUnitId().isEmpty()))throw new SQLException("PVE单位不能与无单位的旧票混合成局");
        List<GlobalQueueUnitRow> units=new ArrayList<>();for(String id:ids){var unit=find(c,id,false);if(unit==null)throw new SQLException("原单位缺失");units.add(unit);}
        if(!allowCrossZone){int expectedHome=home(c,expected.getFirst().getPlayerId());for(var member:expected)if(home(c,member.getPlayerId())!=expectedHome)throw new SQLException("最终补位整队home集合违反Team策略");for(var unit:units)if(unit.getHomeZone()!=expectedHome)throw new SQLException("单位home投影与全P真源不同");}
        lockTeams(c,units);
        for(var unit:units.stream().filter(u->!u.getLaunchOriginal().isEmpty()).sorted(Comparator.comparing(u->GlobalTeamLaunchAuthority.original(u.getLaunchOriginal()).getRequest().getRequestId())).toList())
            if(GlobalTeamLaunchAuthority.lock(c,GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal()),unit.getLaunchOriginal(),unit.getOriginalTicketsList())>=4)throw new SQLException("已终局队伍不能再次成局");
        lockDirects(c,units);
        int count=0;long stamp=now(c);
        for(var seen:units){var unit=find(c,seen.getUnitId(),true);if(unit==null||unit.getState()!=1||!unit.equals(seen)||unit.getNotBeforeMs()>stamp||unit.getExpiresMs()<=stamp)throw new SQLException("原单位已变化或过期");
            var actual=expected.stream().filter(t->t.getQueueUnitId().equals(unit.getUnitId())).toList();
            if(!actual.equals(unit.getOriginalTicketsList())||actual.size()!=unit.getMemberCount()||unit.getMode()!=formation.getMode()||unit.getConfigId()!=formation.getConfigId())throw new SQLException("禁止拆队、换序或改变原单位");
            count+=actual.size();db.updateByPk(c,unit.toBuilder().setState(2).setBattleId(formation.getBattleId()).build());clear(c,unit.getUnitId());teamState(c,unit,3,formation.getBattleId(),stamp);
            if(!unit.getLaunchOriginal().isEmpty())directBattle(c,unit,formation.getBattleId());
        }
        if(count!=expected.size()||count!=units.getFirst().getTargetSize())throw new SQLException("完整单位未精确凑满目标人数");
    }
    private static long now(Connection c)throws SQLException {try(var q=c.createStatement();var r=q.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")){if(!r.next())throw new SQLException("缺SQL单位时钟");return r.getLong(1);}}
    static void lockTeams(Connection c,List<GlobalQueueUnitRow> units)throws SQLException {
        for(long team:units.stream().map(GlobalQueueUnitRow::getTeamId).filter(id->id!=0).distinct().sorted(Long::compareUnsigned).toList())try(var q=c.prepareStatement("SELECT team_id FROM team_authority WHERE team_id=? FOR UPDATE")){q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(team));try(var r=q.executeQuery()){if(!r.next())throw new SQLException("完整单位缺Team原件");}}
    }
    private void directBattle(Connection c,GlobalQueueUnitRow unit,long battle)throws Exception {
        var launch=GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal());var actor=launch.getActor();var key=GlobalMatchStore.directUuidKey(1,actor.getPlayerId(),launch.getRequest().getRequestId());
        var original=db.findOneByPkForUpdate(c,key).orElseThrow();if(original.getBattleId()!=0||original.getCancelled()||!original.getPayload().equals(launch.getRequest().toByteString())||!original.getOwnerAccount().equals(actor.getOwnerAccount())||original.getOwnerRevision()!=actor.getOwnerRevision())throw new SQLException("整队单位direct原意图已改变");
        db.updateByPk(c,original.toBuilder().setBattleId(battle).build());
    }
    private void lockDirects(Connection c,List<GlobalQueueUnitRow> units)throws Exception {
        for(var unit:units.stream().filter(u->!u.getLaunchOriginal().isEmpty()).sorted(Comparator.comparing(GlobalQueueUnitRow::getUnitId)).toList()){
            var launch=GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal());var key=GlobalMatchStore.directUuidKey(1,launch.getActor().getPlayerId(),launch.getRequest().getRequestId());
            var original=db.findOneByPkForUpdate(c,key).orElseThrow();if(!original.getPayload().equals(launch.getRequest().toByteString())||!original.getOwnerAccount().equals(launch.getActor().getOwnerAccount())||original.getOwnerRevision()!=launch.getActor().getOwnerRevision())throw new SQLException("原整队direct载荷变化");
        }
    }
    Map<String,GlobalQueueUnitRow> beforeFinish(Connection c,List<GlobalTicketRow> tickets)throws Exception {
        List<GlobalQueueUnitRow> units=new ArrayList<>();for(String id:tickets.stream().map(GlobalTicketRow::getQueueUnitId).filter(v->!v.isEmpty()).distinct().sorted().toList()){
            var unit=find(c,id,false);if(unit==null)throw new SQLException("终局缺原匹配单位");units.add(unit);
        }
        lockTeams(c,units);
        for(var unit:units.stream().filter(u->!u.getLaunchOriginal().isEmpty()).sorted(Comparator.comparing(u->GlobalTeamLaunchAuthority.original(u.getLaunchOriginal()).getRequest().getRequestId())).toList())
            GlobalTeamLaunchAuthority.lock(c,GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal()),unit.getLaunchOriginal(),unit.getOriginalTicketsList());
        lockDirects(c,units);Map<String,GlobalQueueUnitRow> result=new HashMap<>();for(var unit:units)result.put(unit.getUnitId(),find(c,unit.getUnitId(),true));return result;
    }
    private static void teamState(Connection c,GlobalQueueUnitRow unit,int state,long battle,long stamp)throws Exception {
        if(unit.getLaunchOriginal().isEmpty())return;var launch=GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal());
        try(var q=c.prepareStatement("UPDATE team_launch SET state=?,battle_id=?,updated_ms=? WHERE request_id=? AND original_proto=?")){q.setQueryTimeout(3);q.setInt(1,state);q.setObject(2,PbMysql.uint64(battle));q.setLong(3,stamp);q.setString(4,launch.getRequest().getRequestId());q.setBytes(5,unit.getLaunchOriginal().toByteArray());if(q.executeUpdate()!=1)throw new SQLException("整队单位原launch字节发生变化");}
    }
    void closed(Connection c,GlobalQueueUnitRow unit,long stamp)throws Exception {
        db.updateByPk(c,unit.toBuilder().setState(4).build());clear(c,unit.getUnitId());teamState(c,unit,5,unit.getBattleId(),stamp);
    }
    void finished(Connection c,GlobalFormationRow f,List<GlobalTicketRow> after,boolean success,long stamp)throws Exception {
        for(String id:after.stream().map(GlobalTicketRow::getQueueUnitId).filter(v->!v.isEmpty()).distinct().sorted().toList()){
            var unit=find(c,id,true);if(unit==null||unit.getState()!=2||unit.getBattleId()!=f.getBattleId())throw new SQLException("终局单位并非原formation");
            Map<Long,GlobalTicketRow> changed=new HashMap<>();after.stream().filter(t->t.getQueueUnitId().equals(id)).forEach(t->changed.put(t.getPlayerId(),t));
            var rows=unit.getOriginalTicketsList().stream().map(t->changed.get(t.getPlayerId())).toList();if(rows.stream().anyMatch(Objects::isNull)||changed.size()!=unit.getMemberCount())throw new SQLException("终局不能只处理部分单位");
            int state=success?3:rows.stream().allMatch(t->t.getState()==1)?1:4;
            var next=shape(c,unit.toBuilder().setState(state).setBattleId(state==1?0:f.getBattleId()).build(),rows);db.updateByPk(c,next);replace(c,next,stamp);teamState(c,next,state==1?2:success?4:5,next.getBattleId(),stamp);
            if(state==1&&!unit.getLaunchOriginal().isEmpty()){
                var launch=GlobalTeamLaunchAuthority.original(unit.getLaunchOriginal());var key=GlobalMatchStore.directUuidKey(1,launch.getActor().getPlayerId(),launch.getRequest().getRequestId());var original=db.findOneByPkForUpdate(c,key).orElseThrow();
                if(original.getBattleId()!=f.getBattleId())throw new SQLException("重排direct并非原失败formation");db.updateByPk(c,original.toBuilder().setBattleId(0).build());
            }
        }
    }
}
