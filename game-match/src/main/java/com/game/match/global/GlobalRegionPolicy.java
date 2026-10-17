package com.game.match.global;

import com.game.api.proto.BattleNodeInfo;
import com.game.match.global.pb.*;
import com.game.proto.match.NetworkProbeReceipt;
import java.util.*;

/** 纯规则：所有人的可信 RTT 同时合格；本区优先，只有每一人都等够才进入跨区兜底。 */
public final class GlobalRegionPolicy {
    private GlobalRegionPolicy() { }
    public static Map<Integer,Integer> latencies(GlobalProbeProfile profile,long now,int hard) {
        Map<Integer,Integer> result=new TreeMap<>(Integer::compareUnsigned);
        for(var bytes:profile.getReceiptPayloadsList()) try {
            var r=NetworkProbeReceipt.parseFrom(bytes);
            if(r.getGrant().getPhysicalRegion()!=0 && r.getGrant().getPlanId().equals(profile.getPlanId())
                    && r.getGrant().getSessionBinding().equals(profile.getSessionBinding()) && r.getSampleCount()>=3
                    && r.getRttMs()>0 && r.getRttMs()<=hard && r.getExpiresAtMs()>now && r.getMeasuredAtMs()<=now
                    && r.getMeasuredAtMs()<=r.getGrant().getExpiresAtMs()) result.put(r.getGrant().getPhysicalRegion(),r.getRttMs());
        } catch(com.google.protobuf.InvalidProtocolBufferException e) { return Map.of(); }
        return result;
    }
    public static int preferred(Map<Integer,Integer> latencies, Set<Integer> available) {
        return latencies.entrySet().stream().filter(e->available.contains(e.getKey()))
                .min(Comparator.<Map.Entry<Integer,Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing((a,b)->Integer.compareUnsigned(a.getKey(),b.getKey())))
                .map(Map.Entry::getKey).orElse(0);
    }
    public static boolean sameLane(GlobalTicketRow a,GlobalTicketRow b) {
        return a.getMode()==b.getMode() && a.getConfigId()==b.getConfigId() && a.getTeamSize()==b.getTeamSize();
    }
    public record Choice(List<GlobalTicketRow> members,BattleNodeInfo node) { public Choice { members=List.copyOf(members); } }
    public static Choice choose(List<GlobalTicketRow> candidates,List<BattleNodeInfo> nodes,long now,GlobalMatchProperties config) {
        Map<Integer,BattleNodeInfo> byRegion=new TreeMap<>(Integer::compareUnsigned);
        for(var node:nodes) if(node.getAccepting()&&node.getPhysicalRegion()!=0&&!node.getInstanceId().isBlank()
                && !node.getRpcHost().isBlank()&&node.getRpcPort()!=0) byRegion.merge(node.getPhysicalRegion(),node,
                (a,b)->Integer.compareUnsigned(a.getRoomCount(),b.getRoomCount())<=0?a:b);
        Map<Long,Map<Integer,Integer>> rtts=new HashMap<>();
        for(var row:candidates) try {rtts.put(row.getPlayerId(),latencies(GlobalProbeProfile.parseFrom(row.getProfile()),now,config.hardLatencyMs()));}
        catch(com.google.protobuf.InvalidProtocolBufferException e){rtts.put(row.getPlayerId(),Map.of());}
        for(int pass=0;pass<2;pass++) for(var anchor:candidates.stream().limit(32).toList()) {
            if(anchor.getState()!=1||anchor.getNotBeforeMs()>now||anchor.getExpiresMs()<=now||anchor.getTeamSize()<1||anchor.getTeamSize()>10) continue;
            int preferred=preferred(rtts.getOrDefault(anchor.getPlayerId(),Map.of()),byRegion.keySet());
            for(var region:byRegion.keySet()) {
                if(pass==0&&region!=preferred)continue;
                if(!rtts.getOrDefault(anchor.getPlayerId(),Map.of()).containsKey(region)
                        ||(pass==1&&now-anchor.getEnqueuedMs()<config.waitSeconds()*1000L))continue;
                List<GlobalTicketRow> members=new ArrayList<>();
                members.add(anchor);
                for(var row:candidates) {
                    if(members.size()==anchor.getTeamSize())break;
                    if(row.getPlayerId()==anchor.getPlayerId())continue;
                    if(!sameLane(anchor,row)||row.getState()!=1||row.getNotBeforeMs()>now||row.getExpiresMs()<=now)continue;
                    var latency=rtts.getOrDefault(row.getPlayerId(),Map.of());
                    if(!latency.containsKey(region)||(pass==0&&preferred(latency,byRegion.keySet())!=region)
                            ||(pass==1&&now-row.getEnqueuedMs()<config.waitSeconds()*1000L))continue;
                    long tolerance=(100+Math.min(900,Math.max(0,(now-anchor.getEnqueuedMs())/1000)*10))*100;
                    if((anchor.getMode()==1||anchor.getMode()==3)&&Math.abs(row.getRatingCenti()-anchor.getRatingCenti())>tolerance)continue;
                    members.add(row);
                }
                if(members.size()==anchor.getTeamSize()&&members.stream().anyMatch(t->t.getPlayerId()==anchor.getPlayerId()))
                    return new Choice(members,byRegion.get(region));
            }
        }
        return null;
    }
}
