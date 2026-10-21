package com.game.match.global;

import com.game.match.global.pb.*;
import com.game.pbmysql.PbMysql;
import com.game.match.MatchProperties;
import com.game.match.matcher.Tolerance;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 派生机房可达索引。写原票的事务同时更新；修复游标仅影响可用性，不产生授权。 */
final class GlobalRegionIndex {
    private final PbMysql db;
    private long repairMs, repairPlayer;
    private long highMs,highPlayer;
    private boolean repaired,scanning;
    GlobalRegionIndex(PbMysql db) { this.db=db; }

    void replace(Connection c,GlobalTicketRow row,long now)throws Exception {
        var entries=entries(row,now);clear(c,row.getPlayerId());
        for(var entry:entries)db.insert(c,entry);
    }
    private List<GlobalTicketRegionRow> entries(GlobalTicketRow row,long now)throws Exception {
        if(row.getState()!=1||row.getExpiresMs()<=now)return List.of();
        var profile=GlobalProbeProfile.parseFrom(row.getProfile());
        var rtts=GlobalRegionPolicy.latencies(profile,now,Integer.MAX_VALUE);
        if(rtts.size()>16)throw new SQLException("原票回执超过16机房");
        List<GlobalTicketRegionRow> entries=new ArrayList<>();
        for(var entry:rtts.entrySet())entries.add(GlobalTicketRegionRow.newBuilder().setPlayerId(row.getPlayerId())
                .setPhysicalRegion(entry.getKey()).setTicket(row.getTicket()).setMode(row.getMode()).setConfigId(row.getConfigId())
                .setTeamSize(row.getTeamSize()).setPreferredRegion(row.getPreferredRegion()).setEnqueuedMs(row.getEnqueuedMs())
                .setRatingCenti(row.getRatingCenti()).setRttMs(entry.getValue()).setExpiresMs(row.getExpiresMs()).build());
        return entries;
    }
    private void repairRow(Connection c,GlobalTicketRow row,long now)throws Exception {
        var expected=entries(row,now);
        var actual=db.findAll(c,GlobalTicketRegionRow.class,"player_id=? LIMIT 17",PbMysql.uint64(row.getPlayerId()));
        if(actual.size()==expected.size()&&new HashSet<>(actual).equals(new HashSet<>(expected)))return;
        clear(c,row.getPlayerId());for(var entry:expected)db.insert(c,entry);
    }
    void clear(Connection c,long player)throws SQLException {
        try(var q=c.prepareStatement("DELETE FROM match_global_ticket_region WHERE player_id=?")) {
            q.setQueryTimeout(3);
            q.setObject(1,PbMysql.uint64(player));q.executeUpdate();
        }
    }
    /** 每轮只修32原票、删64过期项。首轮全表修复未完成前，不把缺投影当成本区不足。 */
    record Progress(long ms,long player,long highMs,long highPlayer,boolean scanning,boolean complete) { }
    Progress repair(Connection c,long now)throws Exception {
        long nextMs=repairMs,nextPlayer=repairPlayer;
        long upperMs=highMs,upperPlayer=highPlayer;
        if(!scanning) {
            var tail=db.findAll(c,GlobalTicketRow.class,"state=1 ORDER BY enqueued_ms DESC,player_id DESC LIMIT 1");
            upperMs=tail.isEmpty()?0:tail.getFirst().getEnqueuedMs();upperPlayer=tail.isEmpty()?0:tail.getFirst().getPlayerId();
        }
        var rows=db.findAll(c,GlobalTicketRow.class,"state=1 AND (enqueued_ms>? OR (enqueued_ms=? AND player_id>?)) "
                        +"AND (enqueued_ms<? OR (enqueued_ms=? AND player_id<=?)) ORDER BY enqueued_ms,player_id LIMIT 32",
                repairMs,repairMs,PbMysql.uint64(repairPlayer),upperMs,upperMs,PbMysql.uint64(upperPlayer));
        Map<Long,GlobalTicketRow> locked=new HashMap<>();
        for(long player:rows.stream().map(GlobalTicketRow::getPlayerId).sorted(Long::compareUnsigned).toList())locked.put(player,db.findOneByPkForUpdate(c,GlobalTicketRow.newBuilder().setPlayerId(player).build()).orElseThrow());
        for(var candidate:rows) {
            var row=locked.get(candidate.getPlayerId());
            if(row.getState()==1&&row.getExpiresMs()<=now&&row.getQueueUnitId().isEmpty()){row=row.toBuilder().setState(4).build();db.updateByPk(c,row);}
            if(row.getState()==1&&row.getQueueUnitId().isEmpty()) {
                int preferred=GlobalRegionPolicy.preferred(GlobalProbeProfile.parseFrom(row.getProfile()),now);
                if(preferred!=row.getPreferredRegion()){row=row.toBuilder().setPreferredRegion(preferred).build();db.updateByPk(c,row);}
            }
            repairRow(c,row,now);nextMs=candidate.getEnqueuedMs();nextPlayer=candidate.getPlayerId();
        }
        boolean complete=repaired||rows.size()<32;
        if(rows.size()<32){nextMs=0;nextPlayer=0;}
        try(var q=c.prepareStatement("DELETE FROM match_global_ticket_region WHERE expires_ms<=? LIMIT 64")){q.setLong(1,now);q.executeUpdate();}
        return new Progress(nextMs,nextPlayer,upperMs,upperPlayer,rows.size()==32,complete);
    }
    void committed(Progress progress){repairMs=progress.ms();repairPlayer=progress.player();highMs=progress.highMs();highPlayer=progress.highPlayer();scanning=progress.scanning();repaired=progress.complete();}
    List<GlobalTicketRow> peers(Connection c,GlobalTicketRow anchor,int region,boolean cross,long now,GlobalMatchProperties config,MatchProperties.Rating rating)throws SQLException {
        if(anchor.getTeamSize()<=1)return List.of();
        // 从机房/lane索引扫描，先做全部资格过滤再LIMIT。随后按至多9个ID读取正规PB原票。
        String sql="SELECT t.player_id FROM match_global_ticket_region r JOIN match_global_ticket t ON t.player_id=r.player_id "
                +"AND t.ticket=r.ticket AND t.mode=r.mode AND t.config_id=r.config_id AND t.team_size=r.team_size "
                +"AND t.preferred_region=r.preferred_region AND t.enqueued_ms=r.enqueued_ms AND t.rating_centi=r.rating_centi AND t.expires_ms=r.expires_ms "
                +"WHERE r.physical_region=? AND r.mode=? AND r.config_id=? AND r.team_size=? AND r.rtt_ms BETWEEN 1 AND ? "
                +"AND t.state=1 AND r.expires_ms>? AND t.not_before_ms<=? AND r.player_id<>? ";
        if(anchor.getQueueUnitId().isEmpty())sql+="AND t.queue_unit_id='' ";
        List<Object> args=new ArrayList<>(List.of(Integer.toUnsignedLong(region),Integer.toUnsignedLong(anchor.getMode()),Integer.toUnsignedLong(anchor.getConfigId()),
                Integer.toUnsignedLong(anchor.getTeamSize()),config.hardLatencyMs(),now,now,PbMysql.uint64(anchor.getPlayerId())));
        if(cross){sql+="AND r.enqueued_ms<=? ";args.add(Math.max(0,now-config.waitSeconds()*1000L));}
        else {sql+="AND r.preferred_region=? ";args.add(Integer.toUnsignedLong(region));}
        long tolerance=GlobalRegionPolicy.ratingTolerance(anchor,now,rating);
        if(tolerance!=Tolerance.UNBOUNDED) {
            sql+="AND r.rating_centi BETWEEN ? AND ? ";
            args.add(anchor.getRatingCenti()<Long.MIN_VALUE+tolerance?Long.MIN_VALUE:anchor.getRatingCenti()-tolerance);
            args.add(anchor.getRatingCenti()>Long.MAX_VALUE-tolerance?Long.MAX_VALUE:anchor.getRatingCenti()+tolerance);
        }
        sql+="ORDER BY r.enqueued_ms,r.player_id LIMIT "+(anchor.getTeamSize()-1);
        List<Long> players=new ArrayList<>();
        try(var q=c.prepareStatement(sql)) {
            q.setQueryTimeout(3);
            for(int i=0;i<args.size();i++)q.setObject(i+1,args.get(i));
            try(var rows=q.executeQuery()){while(rows.next())players.add(Long.parseUnsignedLong(rows.getString(1)));}
        }
        var result=db.findAllByKvIn(c,GlobalTicketRow.class,"player_id",players).stream()
                .sorted(Comparator.comparingLong(GlobalTicketRow::getEnqueuedMs).thenComparing((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId()))).toList();
        if(result.size()!=players.size())throw new SQLException("机房候选在读取期间被替换");
        for(var row:result) {
            try {
                var profile=GlobalProbeProfile.parseFrom(row.getProfile());
                if(row.getState()!=1||row.getExpiresMs()<=now||row.getNotBeforeMs()>now||!GlobalRegionPolicy.sameLane(anchor,row)
                        ||!GlobalRegionPolicy.latencies(profile,now,config.hardLatencyMs()).containsKey(region)
                        ||(!cross&&GlobalRegionPolicy.preferred(profile,now)!=region)
                        ||(cross&&row.getEnqueuedMs()>now-config.waitSeconds()*1000L)||!GlobalRegionPolicy.ratingEligible(anchor,row,now,rating))
                    throw new SQLException("机房候选在读取期间发生变化，本轮重读，不能误判本区不足");
            }catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("机房候选原profile损坏",invalid);}
        }
        return result;
    }
}
