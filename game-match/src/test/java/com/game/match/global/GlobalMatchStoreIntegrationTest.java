package com.game.match.global;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.druid.pool.DruidDataSource;
import com.game.match.global.pb.*;
import com.game.proto.match.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 每个测试自建随机库，只连接本机专用 TiDB；不清理共享 Redis/业务库。 */
@EnabledIfEnvironmentVariable(named="MATCH_IT_TIDB",matches="127\\.0\\.0\\.1:4000")
class GlobalMatchStoreIntegrationTest {
    private DruidDataSource data;
    private String database;
    private GlobalMatchStore store;
    @BeforeEach void open()throws Exception{
        database="jmatchit_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/","root","");var s=c.createStatement()){s.execute("CREATE DATABASE `"+database+"`");}
        data=new DruidDataSource();data.setUrl("jdbc:mysql://127.0.0.1:4000/"+database+"?useSSL=false&socketTimeout=4000&connectTimeout=3000");
        data.setUsername("root");data.setPassword("");data.setMaxActive(8);data.setMaxWait(3000);
        data.setConnectionInitSqls(List.of("SET SESSION tidb_snapshot='',tidb_read_staleness=0,tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED'"));
        store=new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret()));
    }
    @AfterEach void close()throws Exception{if(data!=null)data.close();if(database!=null)try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:4000/","root","");var s=c.createStatement()){s.execute("DROP DATABASE `"+database+"`");}}
    private GlobalTicketRow join(long player)throws Exception{
        long now=store.now();var p=GlobalProbeProfile.newBuilder().setPlanId("p"+player).setSessionBinding("s");
        p.addReceiptPayloads(NetworkProbeReceipt.newBuilder().setGrant(NetworkProbeGrant.newBuilder().setPlanId(p.getPlanId()).setPlayerId(player)
                .setSessionBinding("s").setPhysicalRegion(1).setExpiresAtMs(now+120_000)).setSampleCount(3).setRttMs(30).setMeasuredAtMs(now).setExpiresAtMs(now+120_000).build().toByteString());
        store.plan(player,p.build(),now+120_000);
        return store.join(player,UUID.randomUUID().toString(),p.getPlanId(),"s",3,1,2,1,150_000);
    }
    private GlobalFormationRow formation(long id){return GlobalFormationRow.newBuilder().setBattleId(id).setOperationId("operation-"+id).setRegion(1)
            .setNodeId(1).setInstanceId("instance").setHost("127.0.0.1").setPort(21200).setMode(3).setConfigId(1).build();}
    @Test void immutableIntentAndAbortCannotBecomeReady()throws Exception{
        var a=join(1);var b=join(2);store.claim(List.of(a,b),formation(100),false);var f=store.acquireWork(100);
        assertFalse(store.cancel(1,a.getTicket()));assertEquals(2,store.ticket(1).getState());
        assertArrayEquals(new byte[]{1,2},store.saveIntent(f,new byte[]{1,2}).getCreateRequest().toByteArray());
        assertArrayEquals(new byte[]{1,2},store.saveIntent(f,new byte[]{8,9}).getCreateRequest().toByteArray());
        store.unknown(f);assertEquals(3,store.formation(100).getState());assertEquals(2,store.ticket(1).getState());
        store.finish(f,false);assertThrows(RuntimeException.class,()->store.finish(f,true));assertEquals(5,store.formation(100).getState());assertEquals(4,store.ticket(1).getState());
    }
    @Test void cancelVersusClaimHasOneWinnerAndNoHalfGroup()throws Exception{
        var a=join(1);var b=join(2);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var cancel=pool.submit(()->{barrier.await();return store.cancel(1,a.getTicket());});
            var claim=pool.submit(()->{barrier.await();try{store.claim(List.of(a,b),formation(101),false);return true;}catch(RuntimeException e){return false;}});
            boolean cancelled=cancel.get(10,TimeUnit.SECONDS),claimed=claim.get(10,TimeUnit.SECONDS);
            assertNotEquals(cancelled,claimed);
            assertEquals(claimed?2:1,store.ticket(2).getState());
            assertEquals(claimed?2:4,store.ticket(1).getState());
        }
    }
    @Test void failedClaimRollsBackWholeParty()throws Exception{
        var a=join(1);var b=join(2);assertTrue(store.cancel(2,b.getTicket()));
        assertThrows(RuntimeException.class,()->store.claim(List.of(a,b),formation(102),false));
        assertEquals(1,store.ticket(1).getState());assertNull(store.formation(102));
    }
    @Test void claimRechecksTicketExpiryAndRollsBackWholeParty()throws Exception{
        var a=join(1);var b=join(2);
        // 候选原票与机房回执仍有效，SQL 票据已到期；提交点不能沿用扫描时的时效判断。
        try(var c=data.getConnection();var q=c.prepareStatement("UPDATE match_global_ticket SET expires_ms=1 WHERE player_id=?")){
            q.setLong(1,b.getPlayerId());assertEquals(1,q.executeUpdate());
        }
        assertThrows(RuntimeException.class,()->store.claim(List.of(a,b),formation(103),false));
        // ticket() 会终结过期票；只读 SQL 避免观察动作自己改变待验证状态。
        for(var original:List.of(a,b))try(var c=data.getConnection();var q=c.prepareStatement("SELECT state,formation_id,ticket FROM match_global_ticket WHERE player_id=?")){
            q.setLong(1,original.getPlayerId());try(var row=q.executeQuery()){
                assertTrue(row.next());assertEquals(1,row.getInt(1));assertEquals(0,row.getLong(2));assertEquals(original.getTicket(),row.getString(3));
            }
        }
        assertNull(store.formation(103));
    }
    @Test void cancellingUnknownJoinFencesLateArrivalWithoutTouchingNewRequest()throws Exception{
        String request=UUID.randomUUID().toString();
        assertFalse(store.cancel(11,"",request));
        assertTrue(store.requestCancelled(11,request));
        assertThrows(RuntimeException.class,()->store.join(11,request,"late-plan","s",3,1,2,1,150_000));
        assertEquals(0,store.ticket(11).getState());
        var newer=join(11);
        assertFalse(store.cancel(11,"",request));
        assertEquals(newer.getTicket(),store.ticket(11).getTicket());
        assertEquals(1,store.ticket(11).getState());
        assertTrue(store.cancel(11,newer.getTicket()));
        assertThrows(RuntimeException.class,()->store.join(11,newer.getRequestId(),"p11","s",3,1,2,1,150_000));
    }
    @Test void lateJoinAndUnknownCancellationUseOneSqlLock()throws Exception{
        var seed=join(12);assertTrue(store.cancel(12,seed.getTicket()));
        String request=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var join=pool.submit(()->{barrier.await();try{return store.join(12,request,"p12","s",3,1,2,1,150_000);}catch(RuntimeException denied){return null;}});
            var cancel=pool.submit(()->{barrier.await();return store.cancel(12,"",request);});
            join.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
        }
        assertTrue(store.requestCancelled(12,request));
        assertEquals(4,store.ticket(12).getState());
        assertThrows(RuntimeException.class,()->store.join(12,request,"p12","s",3,1,2,1,150_000));
    }
    @Test void directBusinessIntentAndAllMembersCommitTogetherAndReplayOriginal()throws Exception{
        var a=join(21);var b=join(22);assertTrue(store.cancel(21,a.getTicket()));assertTrue(store.cancel(22,b.getTicket()));
        var key=GlobalMatchStore.directUuidKey(1,21,UUID.randomUUID().toString());var payload=com.google.protobuf.ByteString.copyFromUtf8("original-client-request");
        var first=store.reserveDirect(key,payload,List.of(a,b),formation(510));
        assertEquals(2,store.ticket(21).getState());assertEquals(510,store.ticket(22).getFormationId());
        assertEquals(first.getBattleId(),store.reserveDirect(key,payload,List.of(a,b),formation(511)).getBattleId());
        assertThrows(RuntimeException.class,()->store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("other-request"),List.of(a,b),formation(512)));
        store.finish(store.acquireWork(510),false);assertEquals(5,store.reserveDirect(key,payload,List.of(a,b),formation(513)).getState());assertNull(store.formation(511));
    }
    @Test void declinedChallengeCannotBeAcceptedLaterAndLeavesNoHalfGroup()throws Exception{
        var a=join(31);var b=join(32);store.cancel(31,a.getTicket());store.cancel(32,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(32).setRequestLow(901).build();
        assertTrue(store.declineDirect(key,com.google.protobuf.ByteString.copyFromUtf8("declined")));
        assertThrows(RuntimeException.class,()->store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("accepted"),List.of(a,b),formation(601)));
        assertEquals(4,store.ticket(31).getState());assertEquals(4,store.ticket(32).getState());assertNull(store.formation(601));
    }
    @Test void replicasCannotShareWorkOrCommitAfterLeaseExpires()throws Exception{
        var a=join(71);var b=join(72);store.claim(List.of(a,b),formation(701),false);var work=store.acquireWork(701);
        var other=new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret()));
        assertThrows(RuntimeException.class,()->other.acquireWork(701));
        store.prepared(work,GlobalPreparedEndpoint.newBuilder().setPlayerId(71).setHost("original-scene").setPort(1).build());
        try(var c=data.getConnection();var q=c.createStatement()){q.executeUpdate("UPDATE match_global_formation SET retry_ms=1 WHERE battle_id=701");}
        var stale=work.toBuilder().setRetryMs(1).build();var recovered=other.acquireWork(701);assertEquals(3,recovered.getState());
        assertThrows(RuntimeException.class,()->store.checkWork(stale));
        assertThrows(RuntimeException.class,()->store.prepared(stale,GlobalPreparedEndpoint.newBuilder().setPlayerId(72).build()));
        assertThrows(RuntimeException.class,()->store.saveIntent(stale,new byte[]{1}));
        assertThrows(RuntimeException.class,()->store.unknown(stale));assertThrows(RuntimeException.class,()->store.finish(stale,true));
        other.finish(recovered,false);assertEquals(4,store.ticket(71).getState());
    }
    @Test void acceptAndDeclineRaceHasOnlyOneDecisionAndNoHalfGroup()throws Exception{
        var a=join(81);var b=join(82);store.cancel(81,a.getTicket());store.cancel(82,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(82).setRequestLow(801).build();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var yes=pool.submit(()->{barrier.await();try{store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("accept"),List.of(a,b),formation(801));return true;}catch(RuntimeException lost){return false;}});
            var no=pool.submit(()->{barrier.await();try{return store.declineDirect(key,com.google.protobuf.ByteString.copyFromUtf8("decline"));}catch(RuntimeException lost){return false;}});
            boolean accepted=yes.get(10,TimeUnit.SECONDS);assertNotEquals(accepted,no.get(10,TimeUnit.SECONDS));
            assertEquals(store.ticket(81).getState(),store.ticket(82).getState());assertEquals(accepted?2:4,store.ticket(81).getState());
        }
    }
    @Test void ratingAndOutboxCommitTogetherAndStopIsTrueBarrier()throws Exception{
        com.game.match.rating.MatchRatingTables.sync(data,java.time.Duration.ofSeconds(30));
        var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try(var outbox=new RatingRankOutbox(data,new GlobalRankProperties("127.0.0.1",20917,"fixture-only-rank-producer-key-0000"))){
            var metrics=new com.game.match.metrics.MatchMetrics(meters,new com.game.match.metrics.MetricLabels(id->false));
            var ratings=new com.game.match.rating.RatingStore(com.game.match.rating.RatingStore.connections(data),id->30,metrics,System::currentTimeMillis).withCommitHook(outbox);
            var event=com.game.proto.contracts.kafka.BattleResultEvent.newBuilder().setBattleId(900).setMatchMode(3).setOutcome(com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                    .addTeams(com.game.proto.contracts.kafka.BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(1))
                    .addTeams(com.game.proto.contracts.kafka.BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(2)).build();
            assertEquals(com.game.match.rating.RatingStore.Outcome.APPLIED,ratings.apply(event).outcome());
            assertEquals(151_600,ratings.findOrFresh(1).ratingCenti());
            assertEquals(com.game.match.rating.RatingStore.Outcome.DUPLICATE,ratings.apply(event).outcome());
            assertThrows(RuntimeException.class,()->ratings.apply(event.toBuilder().setBattleConfigId(7).build()));
            assertEquals(1,outbox.stopSource());
            assertThrows(RuntimeException.class,()->ratings.apply(event.toBuilder().setBattleId(901).build()));
            assertEquals(151_600,ratings.findOrFresh(1).ratingCenti());
            try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM match_global_rank_outbox")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
            try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM match_rating_applied WHERE battle_id=901")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
        }finally{meters.close();}
    }
}
