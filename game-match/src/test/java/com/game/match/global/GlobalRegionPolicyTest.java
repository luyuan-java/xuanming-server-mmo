package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import com.game.api.proto.BattleNodeInfo;
import com.game.match.global.pb.*;
import com.game.proto.match.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GlobalRegionPolicyTest {
    static final GlobalMatchProperties CONFIG=new GlobalMatchProperties(true,false,30,180,120,"fixture-only-probe-key-00000000000");
    static GlobalTicketRow ticket(long player,int size,long queued,Map<Integer,Integer> rtts){
        var profile=GlobalProbeProfile.newBuilder().setPlanId("plan").setSessionBinding("bound");
        rtts.forEach((region,rtt)->profile.addReceiptPayloads(NetworkProbeReceipt.newBuilder()
                .setGrant(NetworkProbeGrant.newBuilder().setPlayerId(player).setPlanId("plan").setSessionBinding("bound").setPhysicalRegion(region).setExpiresAtMs(500_000))
                .setMeasuredAtMs(1000).setExpiresAtMs(500_000).setSampleCount(3).setRttMs(rtt).build().toByteString()));
        return GlobalTicketRow.newBuilder().setPlayerId(player).setState(1).setMode(3).setConfigId(1).setTeamSize(size).setEnqueuedMs(queued)
                .setExpiresMs(500_000).setRatingCenti(150_000).setProfile(profile.build().toByteString()).setTicket("ticket-"+player).build();
    }
    static BattleNodeInfo node(int region){return BattleNodeInfo.newBuilder().setNodeId(region).setInstanceId("instance-"+region)
            .setPhysicalRegion(region).setRpcHost("127.0.0.1").setRpcPort(21200+region).setAccepting(true).build();}
    @Test void localFirstEvenWhenOlderCrossRegionCanFill(){
        var a=ticket(1,2,1000,Map.of(1,20,2,100));var b=ticket(2,2,1000,Map.of(1,100,2,20));var c=ticket(3,2,1000,Map.of(1,30,2,120));
        var result=GlobalRegionPolicy.choose(List.of(a,b,c),List.of(node(1),node(2)),40_000,CONFIG);
        assertNotNull(result);assertEquals(1,result.node().getPhysicalRegion());assertEquals(List.of(1L,3L),result.members().stream().map(GlobalTicketRow::getPlayerId).toList());
    }
    @Test void everyMemberMustWaitAndMeetHardLimit(){
        var a=ticket(1,2,1000,Map.of(1,20,2,100));var b=ticket(2,2,20_000,Map.of(1,100,2,20));
        assertNull(GlobalRegionPolicy.choose(List.of(a,b),List.of(node(1),node(2)),40_000,CONFIG));
        assertNotNull(GlobalRegionPolicy.choose(List.of(a,b),List.of(node(1),node(2)),60_000,CONFIG));
        var high=ticket(2,2,1000,Map.of(1,181,2,20));var missing=ticket(1,2,1000,Map.of(1,20));
        assertNull(GlobalRegionPolicy.choose(List.of(missing,high),List.of(node(1),node(2)),60_000,CONFIG));
        assertNull(GlobalRegionPolicy.choose(List.of(a,ticket(2,2,1000,Map.of())),List.of(node(1)),60_000,CONFIG));
    }
    @Test void soloDoesNotAccidentallyCollectSecondPlayer(){
        var result=GlobalRegionPolicy.choose(List.of(ticket(1,1,1000,Map.of(1,20)),ticket(2,1,1000,Map.of(1,20))),List.of(node(1)),2000,CONFIG);
        assertEquals(1,result.members().size());
    }
}
