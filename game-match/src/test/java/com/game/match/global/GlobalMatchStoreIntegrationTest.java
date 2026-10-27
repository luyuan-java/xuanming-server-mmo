package com.game.match.global;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

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
@EnabledIfEnvironmentVariable(named="MATCH_IT_TIDB",matches=".+")
class GlobalMatchStoreIntegrationTest {
    private DruidDataSource data;
    private String database;
    private String jdbcRoot;
    private AuthoritySqlFixture fixture;
    private GlobalMatchStore store;
    @BeforeEach void open()throws Exception{
        jdbcRoot=com.game.match.rating.RatingTestDatabase.tidbRoot(System.getenv("MATCH_IT_TIDB"));
        fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(jdbcRoot,
                com.game.match.rating.RatingTestDatabase.MYSQL_USER,com.game.match.rating.RatingTestDatabase.MYSQL_PASSWORD,
                "jmatch",false,List.of(1,2)),(c,principal)->{
            GlobalMatchStore.registry().initializeFresh(c,principal);
            com.game.match.rating.MatchRatingTables.registry().initializeFresh(c,principal);
            try(var q=c.createStatement()){
            q.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,account VARCHAR(255) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            q.execute("CREATE TABLE team_launch(request_id VARCHAR(36) PRIMARY KEY,state INT UNSIGNED NOT NULL,active BOOLEAN NOT NULL DEFAULT TRUE,team_id BIGINT UNSIGNED NOT NULL DEFAULT 0,team_version BIGINT UNSIGNED NOT NULL DEFAULT 0,lock_token VARCHAR(36) NOT NULL DEFAULT '',actor_player_id BIGINT UNSIGNED NOT NULL DEFAULT 0,owner_account VARCHAR(255) NOT NULL DEFAULT '',owner_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,request_proto MEDIUMBLOB,record_proto MEDIUMBLOB,battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,updated_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,original_proto MEDIUMBLOB)");
            q.execute("CREATE TABLE team_authority(team_id BIGINT UNSIGNED PRIMARY KEY,version BIGINT UNSIGNED NOT NULL,record_proto MEDIUMBLOB,tombstone TINYINT NOT NULL DEFAULT 0,active_launch_id VARCHAR(36) NOT NULL)");
            q.execute("CREATE TABLE team_policy(id INT UNSIGNED PRIMARY KEY,contract MEDIUMBLOB,allow_cross_zone TINYINT NOT NULL DEFAULT 0,contract_digest MEDIUMBLOB)");
            q.execute("CREATE TABLE team_launch_role(player_id BIGINT UNSIGNED NOT NULL,request_id VARCHAR(36) NOT NULL,state INT UNSIGNED NOT NULL,owner_account VARCHAR(255) NOT NULL DEFAULT '',owner_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,PRIMARY KEY(player_id,request_id),INDEX by_request(request_id,player_id),INDEX by_state(player_id,state,request_id))");
            q.execute("CREATE TABLE guild_trial_launch(request_id BIGINT UNSIGNED PRIMARY KEY,battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,state INT UNSIGNED NOT NULL,original_proto MEDIUMBLOB,request_digest MEDIUMBLOB,created_ms BIGINT UNSIGNED NOT NULL DEFAULT 0)");
            q.execute("CREATE TABLE guild_trial_launch_role(player_id BIGINT UNSIGNED NOT NULL,request_id BIGINT UNSIGNED NOT NULL,account VARCHAR(255) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,state INT UNSIGNED NOT NULL,PRIMARY KEY(player_id,request_id),INDEX by_request(request_id,player_id),INDEX by_state(player_id,state,request_id))");
            q.execute("CREATE TABLE guild_activity_slot(player_id BIGINT UNSIGNED PRIMARY KEY,lobby_id BIGINT UNSIGNED,expires_ms BIGINT UNSIGNED)");
            q.execute("CREATE TABLE guild_trial_lobby(lobby_id BIGINT UNSIGNED PRIMARY KEY,state INT,view_proto MEDIUMBLOB,guild_id BIGINT UNSIGNED,activity_id INT UNSIGNED)");
            }
            c.setAutoCommit(false);com.game.common.team.TeamPolicyAuthority.ensure(c,false,3);c.commit();c.setAutoCommit(true);
        });
        database=fixture.database();
        data=new DruidDataSource();data.setUrl(fixture.jdbcUrl("?useSSL=false&socketTimeout=4000&connectTimeout=3000"));
        data.setUsername(fixture.normalUser());data.setPassword(fixture.normalPassword());data.setMaxActive(8);data.setMaxWait(3000);
        AuthorityDataSourcePolicy.protectDruid(data,fixture.writerBoot(),true);
        store=new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret()));
    }
    @AfterEach void close()throws Exception{if(data!=null)data.close();if(fixture!=null)fixture.close();}

    private void execute(String sql,Object...args)throws Exception{
        try(var c=fixture.schemaConnection();var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}
    }
    private boolean entryOccupied(long player,String ownTeam,long ownTrial)throws Exception{
        try(var c=data.getConnection()){
            c.setAutoCommit(false);com.game.common.player.PlayerRouteAuthority.lock(c,List.of(player),3);
            try{return com.game.common.player.MatchEntryAuthority.occupied(c,player,ownTeam,ownTrial,3);}finally{c.rollback();}
        }
    }
    @Test void expiredTrialSlotStillBlocksJoinUntilRealLobbyTerminal_OrphanAndUnknownReject()throws Exception{
        seedPlayer(1);execute("INSERT INTO guild_activity_slot VALUES(1,101,1)");
        execute("INSERT INTO guild_trial_lobby(lobby_id,state,guild_id,activity_id) VALUES(101,1,11,3)");
        assertTrue(entryOccupied(1,"",0));assertThrows(RuntimeException.class,()->join(1));
        execute("UPDATE guild_trial_lobby SET state=99 WHERE lobby_id=101");assertTrue(entryOccupied(1,"",0));
        execute("DELETE FROM guild_trial_lobby WHERE lobby_id=101");assertTrue(entryOccupied(1,"",0));
        execute("INSERT INTO guild_trial_lobby(lobby_id,state,guild_id,activity_id) VALUES(101,4,11,3)");
        assertFalse(entryOccupied(1,"",0));assertEquals(1,join(1).getState());
    }
    @Test void ownTrialExceptionRequiresPersistedOriginalAndExactRealLobbyScope()throws Exception{
        seedPlayer(1);seedPlayer(2);
        var original=com.game.proto.guild.GuildTrialLaunchOriginal.newBuilder().setRequestId(101).setGuildId(11).setActivityId(3)
            .setPeriodKey(1).setGuildPeriodKey(1).setDungeonId(7).setInitiatorPlayerId(2).setCreatedMs(1)
            .addRoster(com.game.proto.guild.GuildTrialRoleIdentity.newBuilder().setPlayerId(1).setAccount(actor(1).getAccount()).setOwnerRevision(1))
            .addRoster(com.game.proto.guild.GuildTrialRoleIdentity.newBuilder().setPlayerId(2).setAccount(actor(2).getAccount()).setOwnerRevision(1)).build();
        var view=com.game.proto.guild.GuildTrialLobbyView.newBuilder().setLobbyId(101).setInitiatorPlayerId(2)
            .addMemberPlayerIds(2).addMemberPlayerIds(1).setStateValue(2).setExpireAtMs(1).build();
        execute("INSERT INTO guild_activity_slot VALUES(1,101,1)");
        execute("INSERT INTO guild_trial_lobby VALUES(101,2,?,11,3)",view.toByteArray());
        assertThrows(SQLException.class,()->entryOccupied(1,"",101));
        byte[] raw=original.toByteArray();execute("INSERT INTO guild_trial_launch(request_id,state,original_proto,request_digest) VALUES(101,1,?,?)",raw,java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        assertFalse(entryOccupied(1,"",101));assertTrue(entryOccupied(1,"",102));
        execute("UPDATE guild_trial_lobby SET guild_id=12 WHERE lobby_id=101");assertTrue(entryOccupied(1,"",101));
        execute("UPDATE guild_trial_lobby SET guild_id=11,view_proto=? WHERE lobby_id=101",view.toBuilder().addMemberPlayerIds(3).build().toByteArray());assertTrue(entryOccupied(1,"",101));
        execute("UPDATE guild_trial_lobby SET view_proto=?,state=4 WHERE lobby_id=101",view.toBuilder().setStateValue(4).build().toByteArray());assertTrue(entryOccupied(1,"",101));
    }
    @Test void parentTeamTerminalAloneDoesNotReleaseActiveRole_OnlyCompleteKnownPair()throws Exception{
        seedPlayer(1);String id=UUID.randomUUID().toString();execute("INSERT INTO team_launch(request_id,state) VALUES(?,4)",id);
        execute("INSERT INTO team_launch_role(player_id,request_id,state) VALUES(1,?,1)",id);
        assertTrue(entryOccupied(1,"",0));assertTrue(entryOccupied(1,id,0));
        execute("UPDATE team_launch_role SET state=2");assertFalse(entryOccupied(1,"",0));
        execute("UPDATE team_launch SET state=5");assertFalse(entryOccupied(1,"",0));
        execute("UPDATE team_launch SET state=99");assertTrue(entryOccupied(1,"",0));
        execute("DELETE FROM team_launch");assertTrue(entryOccupied(1,"",0));
    }
    @Test void noOwnIntentSentinelCannotAuthorizeBlankOrZeroActiveLaunch()throws Exception{
        seedPlayer(1);execute("INSERT INTO team_launch(request_id,state) VALUES('',1)");execute("INSERT INTO team_launch_role(player_id,request_id,state) VALUES(1,'',1)");
        assertTrue(entryOccupied(1,"",0));execute("DELETE FROM team_launch_role");execute("DELETE FROM team_launch");
        execute("INSERT INTO guild_trial_launch(request_id,state) VALUES(0,1)");execute("INSERT INTO guild_trial_launch_role(player_id,request_id,account,owner_revision,state) VALUES(1,0,'account-1',1,1)");
        assertTrue(entryOccupied(1,"",0));
    }
    private GlobalTicketRow join(long player)throws Exception{
        return join(player,1,Map.of(1,30));
    }
    private static com.game.api.proto.SessionContext actor(long player){return com.game.api.proto.SessionContext.newBuilder().setPlayerId(player).setAccount("account-"+Long.toUnsignedString(player)).setOwnerRevision(1).build();}
    private static com.google.protobuf.ByteString decision(long challenge,long player,boolean accept){return RespondChallengeRequest.newBuilder().setChallengeId(challenge).setPlayerId(player).setAccept(accept).build().toByteString();}
    private void seedPlayer(long player)throws Exception {
        try(var c=fixture.schemaConnection();var q=c.prepareStatement("INSERT IGNORE INTO player(player_id,account,owner_revision) VALUES(?,?,1)")){
            q.setObject(1,com.game.pbmysql.PbMysql.uint64(player));q.setString(2,actor(player).getAccount());q.executeUpdate();
        }
    }
    private GlobalTicketRow join(long player,int config,Map<Integer,Integer> rtts)throws Exception{
        seedPlayer(player);
        long now=store.now();var p=GlobalProbeProfile.newBuilder().setPlanId("p"+player).setSessionBinding("s").setOwnerAccount(actor(player).getAccount()).setOwnerRevision(1);
        for(var rtt:rtts.entrySet())
        p.addReceiptPayloads(NetworkProbeReceipt.newBuilder().setGrant(NetworkProbeGrant.newBuilder().setPlanId(p.getPlanId()).setPlayerId(player)
                .setSessionBinding("s").setPhysicalRegion(rtt.getKey()).setExpiresAtMs(now+120_000)).setSampleCount(3).setRttMs(rtt.getValue()).setMeasuredAtMs(now).setExpiresAtMs(now+120_000).build().toByteString());
        store.plan(player,p.build(),now+120_000);
        return store.join(player,UUID.randomUUID().toString(),p.getPlanId(),"s",3,config,2,1,150_000);
    }
    private GlobalFormationRow formation(long id){return GlobalFormationRow.newBuilder().setBattleId(id).setOperationId("operation-"+id).setRegion(1)
            .setNodeId(1).setInstanceId("instance").setHost("127.0.0.1").setPort(21200).setMode(3).setConfigId(1).build();}
    @Test void immutableIntentAndAbortCannotBecomeReady()throws Exception{
        var a=join(1);var b=join(2);store.claim(List.of(a,b),formation(100),false);var f=store.acquireWork(100);
        assertFalse(store.cancel(1,a.getTicket()));assertEquals(2,store.ticket(1).getState());
        byte[] originalCreate=com.game.proto.CreateBattleRequest.newBuilder().setBattleId(100).setSeed(1).build().toByteArray();
        byte[] changedCreate=com.game.proto.CreateBattleRequest.newBuilder().setBattleId(100).setSeed(2).build().toByteArray();
        assertArrayEquals(originalCreate,store.saveIntent(f,originalCreate).getCreateRequest().toByteArray());
        assertArrayEquals(originalCreate,store.saveIntent(f,changedCreate).getCreateRequest().toByteArray());
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
        try(var c=fixture.schemaConnection();var q=c.prepareStatement("UPDATE match_global_ticket SET expires_ms=1 WHERE player_id=?")){
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
        seedPlayer(11);assertFalse(store.cancel(actor(11),"",request));
        assertTrue(store.requestCancelled(actor(11),request));
        assertThrows(RuntimeException.class,()->store.join(11,request,"late-plan","s",3,1,2,1,150_000));
        assertEquals(4,store.ticket(11).getState());
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
        var key=GlobalMatchStore.directUuidKey(1,21,UUID.randomUUID().toString()).toBuilder().setOwnerAccount(actor(21).getAccount()).setOwnerRevision(1).build();var payload=com.google.protobuf.ByteString.copyFromUtf8("original-client-request");
        var first=store.reserveDirect(key,payload,List.of(a,b),formation(510));
        assertEquals(2,store.ticket(21).getState());assertEquals(510,store.ticket(22).getFormationId());
        assertEquals(first.getBattleId(),store.reserveDirect(key,payload,List.of(a,b),formation(511)).getBattleId());
        assertThrows(RuntimeException.class,()->store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("other-request"),List.of(a,b),formation(512)));
        store.finish(store.acquireWork(510),false);assertEquals(5,store.reserveDirect(key,payload,List.of(a,b),formation(513)).getState());assertNull(store.formation(511));
    }
    @Test void declinedChallengeCannotBeAcceptedLaterAndLeavesNoHalfGroup()throws Exception{
        var a=join(31);var b=join(32);store.cancel(31,a.getTicket());store.cancel(32,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(32).setRequestLow(901).setOwnerAccount(actor(32).getAccount()).setOwnerRevision(1).build();
        assertTrue(store.declineDirect(key,com.google.protobuf.ByteString.copyFromUtf8("declined")));
        assertThrows(RuntimeException.class,()->store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("accepted"),List.of(a,b),formation(601)));
        assertEquals(4,store.ticket(31).getState());assertEquals(4,store.ticket(32).getState());assertNull(store.formation(601));
    }
    @Test void 活动取消先提交原件墓碑_迟到Start和重启重放均不能开局()throws Exception {
        var a=join(91);var b=join(92);store.cancel(91,a.getTicket());store.cancel(92,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(2).setOwnerId(7).setRequestLow(9001).build();
        var payload=com.google.protobuf.ByteString.copyFromUtf8("complete-original-start");
        var cancelled=store.cancelActivity(key,payload);assertTrue(cancelled.getCancelled());assertEquals(0,cancelled.getBattleId());
        assertEquals(cancelled,store.cancelActivity(key,payload));
        var restarted=new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret()));
        assertTrue(restarted.direct(key).getCancelled());
        assertThrows(RuntimeException.class,()->restarted.reserveDirect(key,payload,List.of(a,b),formation(901)));
        assertThrows(RuntimeException.class,()->store.cancelActivity(key,com.google.protobuf.ByteString.copyFromUtf8("changed-roster")));
        assertNull(store.formation(901));assertEquals(4,store.ticket(91).getState());assertEquals(4,store.ticket(92).getState());
        var next=key.toBuilder().setRequestLow(9002).build();var started=store.reserveDirect(next,payload,List.of(a,b),formation(902));
        var alreadyStarted=store.cancelActivity(next,payload);
        assertFalse(alreadyStarted.getCancelled());assertEquals(started.getBattleId(),alreadyStarted.getBattleId());
        assertEquals(1,store.formation(902).getState());assertEquals(2,store.ticket(91).getState());
    }
    @Test void 活动取消和成局争同一原意图主键_一个终局且无半组()throws Exception {
        var a=join(93);var b=join(94);store.cancel(93,a.getTicket());store.cancel(94,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(2).setOwnerId(7).setRequestLow(9003).build();
        var payload=com.google.protobuf.ByteString.copyFromUtf8("raced-original-start");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=pool.submit(()->{barrier.await();try{return store.reserveDirect(key,payload,List.of(a,b),formation(903));}catch(RuntimeException cancelled){return null;}});
            var cancel=pool.submit(()->{barrier.await();return store.cancelActivity(key,payload);});
            var formed=start.get(10,TimeUnit.SECONDS);var decision=cancel.get(10,TimeUnit.SECONDS);
            assertEquals(formed==null,decision.getCancelled());assertEquals(formed==null?0:903,decision.getBattleId());
            assertEquals(formed==null?4:2,store.ticket(93).getState());assertEquals(store.ticket(93).getState(),store.ticket(94).getState());
            assertEquals(formed==null,store.formation(903)==null);
        }
    }
    @Test void replicasCannotShareWorkOrCommitAfterLeaseExpires()throws Exception{
        var a=join(71);var b=join(72);store.claim(List.of(a,b),formation(701),false);var work=store.acquireWork(701);
        var other=new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret()));
        assertThrows(RuntimeException.class,()->other.acquireWork(701));
        store.prepared(work,GlobalPreparedEndpoint.newBuilder().setPlayerId(71).setHost("original-scene").setPort(1).build());
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE match_global_formation SET retry_ms=1 WHERE battle_id=701");}
        var stale=work.toBuilder().setRetryMs(1).build();var recovered=other.acquireWork(701);assertEquals(3,recovered.getState());
        assertThrows(RuntimeException.class,()->store.checkWork(stale));
        assertThrows(RuntimeException.class,()->store.prepared(stale,GlobalPreparedEndpoint.newBuilder().setPlayerId(72).build()));
        assertThrows(RuntimeException.class,()->store.saveIntent(stale,new byte[]{1}));
        assertThrows(RuntimeException.class,()->store.unknown(stale));assertThrows(RuntimeException.class,()->store.finish(stale,true));
        other.finish(recovered,false);assertEquals(4,store.ticket(71).getState());
    }
    @Test void acceptAndDeclineRaceHasOnlyOneDecisionAndNoHalfGroup()throws Exception{
        var a=join(81);var b=join(82);store.cancel(81,a.getTicket());store.cancel(82,b.getTicket());
        var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(82).setRequestLow(801).setOwnerAccount(actor(82).getAccount()).setOwnerRevision(1).build();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var yes=pool.submit(()->{barrier.await();try{store.reserveDirect(key,com.google.protobuf.ByteString.copyFromUtf8("accept"),List.of(a,b),formation(801));return true;}catch(RuntimeException lost){return false;}});
            var no=pool.submit(()->{barrier.await();try{return store.declineDirect(key,com.google.protobuf.ByteString.copyFromUtf8("decline"));}catch(RuntimeException lost){return false;}});
            boolean accepted=yes.get(10,TimeUnit.SECONDS);assertNotEquals(accepted,no.get(10,TimeUnit.SECONDS));
            assertEquals(store.ticket(81).getState(),store.ticket(82).getState());assertEquals(accepted?2:4,store.ticket(81).getState());
        }
    }
    @Test void 本区伙伴在旧256页之外仍可找到_其它lane不挤占资格()throws Exception {
        var anchor=join(1001);
        for(long player=1002;player<1263;player++)join(player,2,Map.of(1,25));
        var peer=join(2001);
        assertEquals(32,store.candidates(0,0).size());
        assertEquals(List.of(peer.getPlayerId()),store.peers(anchor,1,false,store.now()).stream().map(GlobalTicketRow::getPlayerId).toList());
        assertTrue(store.cancel(2001,peer.getTicket()));assertTrue(store.peers(anchor,1,false,store.now()).isEmpty());
    }
    @Test void 每名跨区伙伴均须独立等满_新票不能被老锚点带过()throws Exception {
        var anchor=join(2101,1,Map.of(1,20,2,100));var peer=join(2102,1,Map.of(1,100,2,20));long stamp=store.now();
        assertTrue(store.peers(anchor,1,false,stamp).isEmpty());assertTrue(store.peers(anchor,1,true,stamp).isEmpty());
        assertEquals(List.of(peer.getPlayerId()),store.peers(anchor,1,true,stamp+31_000).stream().map(GlobalTicketRow::getPlayerId).toList());
        assertThrows(RuntimeException.class,()->store.claim(List.of(anchor,peer),formation(2100),false));
        assertEquals(1,store.ticket(2101).getState());assertNull(store.formation(2100));
    }
    @Test void 选中后续测变化使整组成局重试_不能沿用旧首选区()throws Exception {
        var a=join(2201);var b=join(2202);
        var original=GlobalProbeProfile.parseFrom(store.profile(2202).getPayload());
        var updated=original.toBuilder().clearReceiptPayloads();long stamp=store.now();
        for(var region:Map.of(1,60,2,20).entrySet())updated.addReceiptPayloads(NetworkProbeReceipt.newBuilder()
                .setGrant(NetworkProbeGrant.newBuilder().setPlanId(original.getPlanId()).setPlayerId(2202).setSessionBinding(original.getSessionBinding()).setPhysicalRegion(region.getKey()).setExpiresAtMs(stamp+110_000))
                .setSampleCount(3).setRttMs(region.getValue()).setMeasuredAtMs(stamp).setExpiresAtMs(stamp+110_000).build().toByteString());
        store.commitProfile(2202,updated.build(),stamp+110_000);
        assertThrows(RuntimeException.class,()->store.claim(List.of(a,b),formation(2200),false));
        assertTrue(store.peers(a,1,false,store.now()).isEmpty());assertEquals(1,store.ticket(2201).getState());assertNull(store.formation(2200));
    }
    @Test void 原归属撤销墓碑在过户及ABA后均不可读取覆盖或复活()throws Exception {
        seedPlayer(2301);String request=UUID.randomUUID().toString();assertFalse(store.cancel(actor(2301),"",request));
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE player SET owner_revision=3 WHERE player_id=2301");}
        var current=actor(2301).toBuilder().setOwnerRevision(3).build();
        assertThrows(RuntimeException.class,()->store.requestCancelled(actor(2301),request));
        assertThrows(RuntimeException.class,()->store.requestCancelled(current,request));
        assertThrows(RuntimeException.class,()->store.cancel(current,"",request));
        assertThrows(RuntimeException.class,()->store.cancel(actor(2301),"",UUID.randomUUID().toString()));
        var profile=GlobalProbeProfile.newBuilder().setPlanId("new").setSessionBinding("new").setOwnerAccount(current.getAccount()).setOwnerRevision(3).build();
        assertThrows(RuntimeException.class,()->store.plan(2301,profile.toBuilder().setOwnerRevision(1).build(),store.now()+60_000));
        store.plan(2301,profile,store.now()+60_000);
        assertThrows(RuntimeException.class,()->store.join(current,request,"new","new",3,1,2,1,150_000));
    }
    @Test void 多副本策略及探测密钥不一致拒启_不能覆盖既有指纹()throws Exception {
        assertThrows(RuntimeException.class,()->new GlobalMatchStore(data,new GlobalMatchProperties(true,true,31,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret())));
        assertThrows(RuntimeException.class,()->new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,"other-fixture-only-probe-key-00000")));
        assertDoesNotThrow(()->new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret())));
    }
    @Test void 机房投影可重建且过期排队明确终止_不留下永久阻售占位()throws Exception {
        var a=join(2601);var b=join(2602);
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("DELETE FROM match_global_ticket_region");}
        assertTrue(store.peers(a,1,false,store.now()).isEmpty());assertTrue(store.repairRegions());
        assertEquals(List.of(b.getPlayerId()),store.peers(a,1,false,store.now()).stream().map(GlobalTicketRow::getPlayerId).toList());
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE match_global_ticket SET expires_ms=1 WHERE player_id=2602");}
        assertTrue(store.repairRegions());assertEquals(4,store.ticket(2602).getState());assertTrue(store.peers(a,1,false,store.now()).isEmpty());
        assertEquals(4,store.ticket(2699).getState());
    }
    @Test void SQL邀请重推复用原件_原身份失效或相反决定都不能改原结果()throws Exception {
        var a=join(2401);var b=join(2402);store.cancel(2401,a.getTicket());store.cancel(2402,b.getTicket());
        var invite=store.invite(actor(2401),2400,2402,1,60_000);
        assertEquals(invite,store.invite(actor(2401),2403,2402,1,60_000));
        var accepted=decision(2400,2402,true);
        var decision=store.decideChallenge(actor(2402),2400,accepted,true,List.of(a,b),formation(2404));
        assertEquals(4,decision.getState());assertEquals(0,decision.getBattleId());assertNull(store.formation(2404));
        assertEquals(0,store.ticket(2401).getState());assertEquals(0,store.ticket(2402).getState());
        assertEquals(decision,store.decideChallenge(actor(2402),2400,accepted,true,List.of(),null));
        var pending=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(2402).setRequestLow(2400).setOwnerAccount(actor(2402).getAccount()).setOwnerRevision(1).build();
        assertEquals(2404,store.completeDirect(pending).getBattleId());assertEquals(2,store.invitation(actor(2402),2400).getState());
        assertThrows(RuntimeException.class,()->store.decideChallenge(actor(2402),2400,decision(2400,2402,false),false,List.of(),null));
        assertThrows(RuntimeException.class,()->store.invitation(actor(2402).toBuilder().setOwnerRevision(2).build(),2400));
        assertEquals(2,store.ticket(2401).getState());assertEquals(2,store.ticket(2402).getState());
    }
    @Test void SQL邀请接受与拒绝争原双方锁_只有一个决定且无半组成局()throws Exception {
        var a=join(2501);var b=join(2502);store.cancel(2501,a.getTicket());store.cancel(2502,b.getTicket());store.invite(actor(2501),2500,2502,1,60_000);
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var yes=pool.submit(()->{barrier.await();try{store.decideChallenge(actor(2502),2500,decision(2500,2502,true),true,List.of(a,b),formation(2504));return true;}catch(RuntimeException lost){return false;}});
            var no=pool.submit(()->{barrier.await();try{store.decideChallenge(actor(2502),2500,decision(2500,2502,false),false,List.of(),null);return true;}catch(RuntimeException lost){return false;}});
            boolean accepted=yes.get(10,TimeUnit.SECONDS);assertNotEquals(accepted,no.get(10,TimeUnit.SECONDS));
            assertEquals(accepted?4:3,store.invitation(actor(2502),2500).getState());assertEquals(accepted?0:4,store.ticket(2501).getState());
            assertEquals(store.ticket(2501).getState(),store.ticket(2502).getState());assertNull(store.formation(2504));
            if(accepted){store.completeDirect(GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(2502).setRequestLow(2500).setOwnerAccount(actor(2502).getAccount()).setOwnerRevision(1).build());assertEquals(2504,store.invitation(actor(2502),2500).getBattleId());}
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

    private GlobalWatchAuthority watches(){return new GlobalWatchAuthority(data,store);}
    private WatchBattleRequest watchRequest(long battle){return WatchBattleRequest.newBuilder().setBattleId(battle).setRequestId(UUID.randomUUID().toString()).build();}
    private void watchable(long battle)throws Exception{
        var pb=new com.game.pbmysql.PbMysql();pb.register(GlobalFormationRow.getDefaultInstance());
        try(var c=fixture.schemaConnection()){pb.insert(c,formation(battle).toBuilder().setState(4).setCreatedMs(store.now()).setCreateRequest(com.game.proto.CreateBattleRequest.getDefaultInstance().toByteString()).build());}
    }
    @Test void watchUuidHistoryNeverClearsNewerOperationAndDoesNotRebindOwner()throws Exception{
        seedPlayer(901);var sql=watches();var first=watchRequest(800);var second=watchRequest(801);
        sql.begin(actor(901),first,com.game.common.deadline.Deadline.after(3000));
        sql.begin(actor(901),second,com.game.common.deadline.Deadline.after(3000));
        sql.select(actor(901),second.getRequestId(),801,com.game.common.deadline.Deadline.after(3000));
        String mark="801:"+second.getRequestId();
        assertEquals(com.game.match.spectate.SpectateStore.Acquire.OK,sql.reserve(actor(901),901,mark,com.game.common.deadline.Deadline.after(3000)));
        assertFalse(sql.begin(actor(901),first,com.game.common.deadline.Deadline.after(3000)).latest());
        assertThrows(RuntimeException.class,()->sql.cancelForRequest(actor(901),first.getRequestId(),mark,com.game.common.deadline.Deadline.after(3000)));
        assertEquals(mark,sql.read(901,com.game.common.deadline.Deadline.after(3000)).getMark());
        assertThrows(RuntimeException.class,()->sql.begin(actor(901),first.toBuilder().setBattleId(999).build(),com.game.common.deadline.Deadline.after(3000)));
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE player SET owner_revision=3 WHERE player_id=901");}
        assertThrows(RuntimeException.class,()->sql.begin(actor(901).toBuilder().setOwnerRevision(3).build(),first,com.game.common.deadline.Deadline.after(3000)));
    }
    @Test void watchCancelRequiresOriginalPositiveProofAndNeverExtendsOriginalAdd()throws Exception{
        seedPlayer(902);watchable(802);var sql=watches();var request=watchRequest(802);String mark="802:"+request.getRequestId();
        sql.begin(actor(902),request,com.game.common.deadline.Deadline.after(3000));sql.select(actor(902),request.getRequestId(),802,com.game.common.deadline.Deadline.after(3000));
        sql.reserve(actor(902),902,mark,com.game.common.deadline.Deadline.after(3000));
        var placement=((com.game.match.spectate.SpectateStore.Record.Found)sql.formation(802,com.game.common.deadline.Deadline.after(3000)).record()).placement();
        var base=com.game.proto.AddObserverRequest.newBuilder().setBattleId(802).setObserverPlayerId(902).setObserverName("公开角色名").build();
        var add=sql.prepare(actor(902),902,mark,placement,base,com.game.common.deadline.Deadline.after(3000));
        assertEquals(request.getRequestId(),add.getSpectatorOperationId());assertEquals("instance",add.getTargetInstanceId());
        assertEquals(add,sql.prepare(actor(902),902,mark,placement,base.toBuilder().setObserverName("迟到的新名字").build(),com.game.common.deadline.Deadline.after(3000)));
        var cancelled=sql.cancel(null,902,mark,com.game.common.deadline.Deadline.after(3000));
        var work=sql.lease(cancelled,com.game.common.deadline.Deadline.after(3000));
        sql.settled(work,false,com.game.proto.RemoveObserverResponse.newBuilder().setCancelled(true).setSpectatorOperationId(UUID.randomUUID().toString()).setTargetInstanceId("instance").build(),com.game.common.deadline.Deadline.after(3000));
        assertEquals(4,sql.read(902,com.game.common.deadline.Deadline.after(3000)).getState());
        work=sql.lease(sql.read(902,com.game.common.deadline.Deadline.after(3000)),com.game.common.deadline.Deadline.after(3000));
        sql.settled(work,false,com.game.proto.RemoveObserverResponse.newBuilder().setCancelled(true).setSpectatorOperationId(request.getRequestId()).setTargetInstanceId("instance").build(),com.game.common.deadline.Deadline.after(3000));
        assertEquals(5,sql.read(902,com.game.common.deadline.Deadline.after(3000)).getState());
        assertThrows(RuntimeException.class,()->sql.prepare(actor(902),902,mark,placement,base,com.game.common.deadline.Deadline.after(3000)));
        assertEquals(com.game.match.spectate.SpectateStore.Acquire.BUSY,sql.reserve(actor(902),902,mark,com.game.common.deadline.Deadline.after(3000)));
    }
    @Test void watchBeforeAddCancelIsDurableAndCachedReplyCannotChange()throws Exception{
        seedPlayer(903);var sql=watches();var request=watchRequest(803);String mark="803:"+request.getRequestId();
        sql.begin(actor(903),request,com.game.common.deadline.Deadline.after(3000));sql.select(actor(903),request.getRequestId(),803,com.game.common.deadline.Deadline.after(3000));
        sql.reserve(actor(903),903,mark,com.game.common.deadline.Deadline.after(3000));
        assertEquals(5,sql.cancel(null,903,mark,com.game.common.deadline.Deadline.after(3000)).getState());
        var response=com.game.match.support.MatchTips.watchRejected(com.game.match.support.MatchTip.BUSY);
        assertEquals(response,sql.respond(actor(903),request.getRequestId(),response,com.game.common.deadline.Deadline.after(3000)));
        assertEquals(response,sql.respond(actor(903),request.getRequestId(),com.game.match.support.MatchTips.watchAccepted(803),com.game.common.deadline.Deadline.after(3000)));
    }
    @Test void watchingAndQueueJoinShareOriginalPlayerCommitLock()throws Exception{
        var previous=join(904);assertTrue(store.cancel(actor(904),previous.getTicket(),previous.getRequestId()));
        var sql=watches();var request=watchRequest(804);String mark="804:"+request.getRequestId();
        sql.begin(actor(904),request,com.game.common.deadline.Deadline.after(3000));sql.select(actor(904),request.getRequestId(),804,com.game.common.deadline.Deadline.after(3000));
        var barrier=new CyclicBarrier(2);
        try(var workers=Executors.newFixedThreadPool(2)){
            var watching=workers.submit(()->{barrier.await();return sql.reserve(actor(904),904,mark,com.game.common.deadline.Deadline.after(3000))==com.game.match.spectate.SpectateStore.Acquire.OK;});
            var joining=workers.submit(()->{barrier.await();try{store.join(actor(904),UUID.randomUUID().toString(),"p904","s",3,1,2,1,150_000);return true;}catch(RuntimeException blocked){return false;}});
            assertNotEquals(watching.get(10,TimeUnit.SECONDS),joining.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void watchRecoveryKeysetProgressesPastMoreThanEightUnresolvedOwners()throws Exception{
        var sql=watches();for(long player=910;player<922;player++){
            seedPlayer(player);var request=watchRequest(810);sql.begin(actor(player),request,com.game.common.deadline.Deadline.after(3000));
            sql.select(actor(player),request.getRequestId(),810,com.game.common.deadline.Deadline.after(3000));
            sql.reserve(actor(player),player,"810:"+request.getRequestId(),com.game.common.deadline.Deadline.after(3000));
        }
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE match_global_watching SET retry_ms=1");q.executeUpdate("UPDATE player SET owner_revision=3 WHERE player_id<918");}
        var page=sql.pending(0,0,com.game.common.deadline.Deadline.after(3000));assertEquals(8,page.size());
        for(var row:page)assertThrows(RuntimeException.class,()->sql.lease(row,com.game.common.deadline.Deadline.after(3000)));
        var last=page.getLast();var next=sql.pending(last.getRetryMs(),last.getPlayerId(),com.game.common.deadline.Deadline.after(3000));assertEquals(4,next.size());
        assertEquals(918,next.getFirst().getPlayerId());
    }
    private com.game.common.deadline.Deadline budget(){return com.game.common.deadline.Deadline.after(3000);}
    private JoinQueueRequest joinRequest(long player){return JoinQueueRequest.newBuilder().setMode(MatchMode.MATCH_MODE_1V1).setBattleConfigId(1).setNetworkPlanId("p"+player).setRequestId(UUID.randomUUID().toString()).build();}
    @Test void 原Join首捕获关闭未许可Add_重放旧Watch不再获许可()throws Exception {
        var old=join(3001);store.cancel(actor(3001),old.getTicket(),old.getRequestId());var sql=watches();var watch=watchRequest(3300);String mark="3300:"+watch.getRequestId();
        sql.begin(actor(3001),watch,budget());sql.select(actor(3001),watch.getRequestId(),3300,budget());sql.reserve(actor(3001),3001,mark,budget());
        var request=joinRequest(3001);var original=store.prepareJoin(actor(3001),request,2,1,150000,"s");
        assertEquals(0,store.ticket(3001).getState());assertEquals(1,original.getObserverExitsCount());assertFalse(com.game.proto.ObserverExitOriginal.parseFrom(original.getObserverExits(0)).hasAdd());
        assertTrue(sql.begin(actor(3001),watch,budget()).original().getCancelled());assertEquals(5,sql.read(3001,budget()).getState());
        assertEquals(com.game.match.spectate.SpectateStore.Acquire.BUSY,sql.reserve(actor(3001),3001,mark,budget()));
        var newer=watchRequest(3301);assertTrue(sql.begin(actor(3001),newer,budget()).original().getResponsePayload().size()>0);
        var row=store.join(actor(3001),request.getRequestId(),request.getNetworkPlanId(),"s",3,1,2,1,150000);assertEquals(1,row.getState());
        assertEquals(original.getObserverExitsList(),store.prepareJoin(actor(3001),request,2,1,150000,"s").getObserverExitsList());
    }
    @Test void 原Join必须等固定Add取消正证明_探测失效后安全终结不补新profile()throws Exception {
        var old=join(3002);store.cancel(actor(3002),old.getTicket(),old.getRequestId());watchable(3302);var sql=watches();var watch=watchRequest(3302);String mark="3302:"+watch.getRequestId();
        sql.begin(actor(3002),watch,budget());sql.select(actor(3002),watch.getRequestId(),3302,budget());sql.reserve(actor(3002),3002,mark,budget());
        var placement=((com.game.match.spectate.SpectateStore.Record.Found)sql.formation(3302,budget()).record()).placement();
        var add=sql.prepare(actor(3002),3002,mark,placement,com.game.proto.AddObserverRequest.newBuilder().setBattleId(3302).setObserverPlayerId(3002).build(),budget());
        var request=joinRequest(3002);var original=store.prepareJoin(actor(3002),request,2,1,150000,"s");var exit=com.game.proto.ObserverExitOriginal.parseFrom(original.getObserverExits(0));assertEquals(add,exit.getAdd());
        assertThrows(RuntimeException.class,()->store.join(actor(3002),request.getRequestId(),request.getNetworkPlanId(),"s",3,1,2,1,150000));
        var pending=sql.cancelExit(exit,budget());var work=sql.lease(pending,budget());
        sql.settled(work,false,com.game.proto.RemoveObserverResponse.newBuilder().setCancelled(true).setSpectatorOperationId(watch.getRequestId()).setTargetInstanceId("instance").build(),budget());
        assertNull(sql.cancelExit(exit,budget()));
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE match_global_profile SET expires_ms=1 WHERE player_id=3002");}
        assertTrue(store.closeObsoleteJoin(original,budget()));assertEquals(4,store.ticket(3002).getState());assertTrue(store.requestCancelled(actor(3002),request.getRequestId()));
        assertThrows(RuntimeException.class,()->store.prepareJoin(actor(3002),request,2,1,150000,"s"));
    }
    @Test void Join恢复固定高水位推进第九项_新到达不延长本轮()throws Exception {
        for(long player=3101;player<=3109;player++){var old=join(player);store.cancel(actor(player),old.getTicket(),old.getRequestId());store.prepareJoin(actor(player),joinRequest(player),2,1,150000,"s");}
        var upper=store.pendingJoinUpper();var first=store.pendingJoins(0,0,0,upper);assertEquals(8,first.size());
        var late=join(3199);store.cancel(actor(3199),late.getTicket(),late.getRequestId());store.prepareJoin(actor(3199),joinRequest(3199),2,1,150000,"s");
        var last=first.getLast();var tail=store.pendingJoins(last.getPlayerId(),last.getRequestHigh(),last.getRequestLow(),upper);assertEquals(1,tail.size());assertEquals(3109,tail.getFirst().getPlayerId());
    }
    private record Party(com.game.proto.team.TeamLaunchOriginal original,List<GlobalTicketRow> members,GlobalDirectIntentRow key){}
    private Party party(long team,long...players)throws Exception{return partyAtHome(team,1,players);}
    private Party partyAtHome(long team,int zone,long...players)throws Exception {
        long stamp=store.now();String request=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();List<GlobalTicketRow> members=new ArrayList<>();
        var start=com.game.proto.team.StartTeamMatchRequest.newBuilder().setExpectedTeamId(team).setBattleConfigId(1).setTargetTeamSize(5).setRequestId(request).build();
        var original=com.game.proto.team.TeamLaunchOriginal.newBuilder().setTeamId(team).setTeamVersion(1).setLockToken(token).setRequest(start).setLockTtlSeconds(30).setEnqueuedMs(stamp).setNotBeforeMs(stamp);
        var record=com.game.proto.team.TeamRecord.newBuilder().setTeamId(team).setLeaderId(players[0]).setZoneId(zone).setCreatedAtMs(stamp).setMatchLockToken(token);
        for(int i=0;i<players.length;i++){long player=players[i];var old=join(player);store.cancel(actor(player),old.getTicket(),old.getRequestId());
            try(var c=fixture.schemaConnection();var q=c.prepareStatement("UPDATE player SET zone_id=? WHERE player_id=?")){q.setInt(1,zone);q.setLong(2,player);q.executeUpdate();}
            var role=com.game.proto.team.TeamOriginalRole.newBuilder().setPlayerId(player).setOwnerAccount(actor(player).getAccount()).setOwnerRevision(1).build();if(i==0)original.setActor(role);
            String ticket=UUID.randomUUID().toString();original.addMembers(com.game.proto.team.TeamLaunchMember.newBuilder().setOriginal(role).setRosterOrder(i+1).setTicket(ticket)).putZones(player,zone);
            record.addMembers(com.game.proto.team.TeamMemberRecord.newBuilder().setPlayerId(player).setZoneId(zone).setJoinedAtMs(stamp).setJoinSeq(i+1)).addMatchLockRoster(player);
            members.add(old.toBuilder().setMode(5).setTeamSize(5).setZoneId(zone).setTeamId(team).setTicket(ticket).setRequestId(request).clearFormationId().clearSelectedRegion().setState(1).build());
        }
        var launch=original.build();byte[] rec=record.build().toByteArray();
        try(var c=fixture.schemaConnection()){
            try(var q=c.prepareStatement("INSERT INTO team_authority(team_id,version,record_proto,tombstone,active_launch_id) VALUES(?,1,?,0,?)")){q.setLong(1,team);q.setBytes(2,rec);q.setString(3,request);q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO team_launch(request_id,state,team_id,team_version,lock_token,actor_player_id,owner_account,owner_revision,request_proto,record_proto,original_proto) VALUES(?,1,?,1,?,?,?,1,?,?,?)")){
                q.setString(1,request);q.setLong(2,team);q.setString(3,token);q.setLong(4,players[0]);q.setString(5,actor(players[0]).getAccount());q.setBytes(6,start.toByteArray());q.setBytes(7,rec);q.setBytes(8,launch.toByteArray());q.executeUpdate();}
            for(long player:players)try(var q=c.prepareStatement("INSERT INTO team_launch_role(player_id,request_id,state,owner_account,owner_revision) VALUES(?,?,1,?,1)")){q.setLong(1,player);q.setString(2,request);q.setString(3,actor(player).getAccount());q.executeUpdate();}
        }
        var key=GlobalMatchStore.directUuidKey(1,players[0],request).toBuilder().setOwnerAccount(actor(players[0]).getAccount()).setOwnerRevision(1).build();return new Party(launch,List.copyOf(members),key);
    }
    private GlobalQueueUnitRow queued(Party party){assertEquals(2,store.enqueueTeam(party.key(),party.original().toByteString(),party.members()).getState());return store.unitCandidates(0,"").stream().filter(u->u.getTeamId()==party.original().getTeamId()).findFirst().orElseThrow();}
    @Test void 两支完整小队三加二装满_失败只沿原F5整队重排不换UUID()throws Exception {
        var three=party(4001,4101,4102,4103);var two=party(4002,4201,4202);var a=queued(three);var b=queued(two);
        var peers=store.unitPeers(a,1,false,store.now(),budget());assertEquals(List.of(b.getUnitId()),peers.stream().map(GlobalQueueUnitRow::getUnitId).toList());
        var packed=GlobalQueueUnits.pack(a,peers);assertEquals(2,packed.size());var members=packed.stream().flatMap(u->u.getOriginalTicketsList().stream()).toList();
        var formed=store.claim(members,formation(4300).toBuilder().setMode(5).setRequeueOnFailure(true).build(),false);assertEquals(5,formed.getMembersCount());
        assertFalse(store.cancelUnit(actor(4102),a.getUnitId()));var work=store.acquireWork(4300);store.finish(work,false);assertEquals(5,store.formation(4300).getState());
        var again=store.unitCandidates(0,"");assertEquals(2,again.size());for(var unit:again){assertEquals(1,unit.getState());assertEquals(0,unit.getBattleId());assertEquals(0,store.direct(unit.getTeamId()==4001?three.key():two.key()).getBattleId());
            var before=unit.getTeamId()==4001?a:b;assertEquals(before.getEnqueuedMs(),unit.getEnqueuedMs());assertEquals(before.getOriginalTicketsList().stream().map(GlobalTicketRow::getTicket).toList(),unit.getOriginalTicketsList().stream().map(GlobalTicketRow::getTicket).toList());
            for(var row:unit.getOriginalTicketsList()){assertEquals(1,row.getState());assertEquals(0,row.getFormationId());assertTrue(row.getNotBeforeMs()>row.getEnqueuedMs());}}
    }
    @Test void 任一原成员撤队即整单取消_迟到原Start与旧UUID不复活()throws Exception {
        var group=party(4400,4401,4402);var unit=queued(group);assertTrue(store.cancelUnit(actor(4402),unit.getUnitId()));
        for(var member:group.members()){assertEquals(4,store.ticket(member.getPlayerId()).getState());assertTrue(store.requestCancelled(actor(member.getPlayerId()),group.original().getRequest().getRequestId()));}
        assertEquals(5,store.teamIntent(group.key(),group.original().getRequest().toByteString()).getState());assertEquals(5,store.teamReplay(group.key(),group.original().getRequest().toByteString(),group.original().toByteString()).getState());
        var early=party(4500,4501,4502);store.cancel(actor(4502),"",early.original().getRequest().getRequestId());assertEquals(5,store.enqueueTeam(early.key(),early.original().toByteString(),early.members()).getState());
        assertEquals(5,store.teamIntent(early.key(),early.original().getRequest().toByteString()).getState());assertTrue(store.direct(early.key()).getCancelled());
    }
    @Test void 禁止跨home时先SQL资格过滤再LIMIT_提交拒伪造混合home()throws Exception {
        var left=party(4600,4601,4602,4603);var right=partyAtHome(4700,2,4701,4702);
        var a=queued(left);var b=queued(right);assertEquals(1,a.getHomeZone());assertEquals(2,b.getHomeZone());assertTrue(store.unitPeers(a,1,false,store.now(),budget()).isEmpty());
        var all=new ArrayList<>(a.getOriginalTicketsList());all.addAll(b.getOriginalTicketsList());assertThrows(RuntimeException.class,()->store.claim(all,formation(4800).toBuilder().setMode(5).build(),false));assertNull(store.formation(4800));
        assertEquals(1,store.ticket(4601).getState());assertEquals(1,store.ticket(4701).getState());
    }

    @Test void 单人成员探测失效整队退出_不留下其余成员永久占用()throws Exception {
        var group=party(4900,4901,4902);queued(group);var healthy=queued(party(4910,4911,4912,4913));
        var profile=GlobalProbeProfile.parseFrom(store.profile(4901).getPayload());store.commitProfile(4901,profile,1);
        store.repairRegions();assertEquals(4,store.ticket(4901).getState());assertEquals(4,store.ticket(4902).getState());assertTrue(store.direct(group.key()).getCancelled());
        assertTrue(store.unitCandidates(0,"").stream().anyMatch(unit->unit.getUnitId().equals(healthy.getUnitId())));
    }
    @Test void 成局提交不能只取一支队的部分成员()throws Exception {
        var a=queued(party(4920,4921,4922,4923));var b=queued(party(4930,4931,4932));
        var partial=new ArrayList<>(a.getOriginalTicketsList().subList(0,2));partial.addAll(b.getOriginalTicketsList());
        assertThrows(RuntimeException.class,()->store.claim(partial,formation(4940).toBuilder().setMode(5).build(),false));assertNull(store.formation(4940));
        for(var row:a.getOriginalTicketsList())assertEquals(1,store.ticket(row.getPlayerId()).getState());for(var row:b.getOriginalTicketsList())assertEquals(1,store.ticket(row.getPlayerId()).getState());
    }
    @Test void 整队撤销与成局争全员P_只有一个胜者无半单()throws Exception {
        var a=queued(party(4950,4951,4952,4953));var b=queued(party(4960,4961,4962));var all=new ArrayList<>(a.getOriginalTicketsList());all.addAll(b.getOriginalTicketsList());var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var cancel=pool.submit(()->{barrier.await();return store.cancelUnit(actor(4952),a.getUnitId());});
            var claim=pool.submit(()->{barrier.await();try{store.claim(all,formation(4970).toBuilder().setMode(5).build(),false);return true;}catch(RuntimeException lost){return false;}});
            boolean cancelled=cancel.get(10,TimeUnit.SECONDS),claimed=claim.get(10,TimeUnit.SECONDS);assertNotEquals(cancelled,claimed);
            for(var row:a.getOriginalTicketsList())assertEquals(claimed?2:4,store.ticket(row.getPlayerId()).getState());for(var row:b.getOriginalTicketsList())assertEquals(claimed?2:1,store.ticket(row.getPlayerId()).getState());
        }
    }
    @Test void Team策略原件改变时旧Match实例及不一致新副本都拒绝()throws Exception {
        byte[] raw=com.game.common.team.TeamPolicyAuthority.canonicalContract(true);byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw);
        try(var c=fixture.schemaConnection();var q=c.prepareStatement("UPDATE team_policy SET contract=?,allow_cross_zone=1,contract_digest=? WHERE id=1")){q.setBytes(1,raw);q.setBytes(2,digest);assertEquals(1,q.executeUpdate());}
        assertThrows(RuntimeException.class,store::now);assertThrows(RuntimeException.class,()->new GlobalMatchStore(data,new GlobalMatchProperties(true,true,30,180,120,GlobalRegionPolicyTest.CONFIG.probeSecret())));
    }
    @Test void 挑战接受原pending不能借先前普通JoinUUID拆掉单个成员()throws Exception {
        var a=join(4981);var b=join(4982);store.cancel(actor(4981),a.getTicket(),a.getRequestId());store.cancel(actor(4982),b.getTicket(),b.getRequestId());store.invite(actor(4981),4980,4982,1,60000);
        store.decideChallenge(actor(4982),4980,decision(4980,4982,true),true,List.of(a,b),formation(4983));
        assertTrue(store.ticket(4981).getRequestId().isEmpty());assertFalse(store.cancel(actor(4981),a.getTicket(),""));assertEquals(0,store.ticket(4981).getState());assertEquals(0,store.ticket(4982).getState());
        store.completeDirect(GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(4982).setRequestLow(4980).setOwnerAccount(actor(4982).getAccount()).setOwnerRevision(1).build());assertEquals(2,store.ticket(4981).getState());
    }

    @Test void 原Team与Trial已提交而票尚未落时新Watch和Join仍被提交守卫挡住()throws Exception {
        var group=party(5100,5101,5102);var sql=watches();var watch=watchRequest(5109);
        assertFalse(sql.begin(actor(5101),watch,budget()).original().getResponsePayload().isEmpty());assertThrows(RuntimeException.class,()->store.prepareJoin(actor(5101),joinRequest(5101),2,1,150000,"s"));
        var old=join(5111);store.cancel(actor(5111),old.getTicket(),old.getRequestId());
        try(var c=fixture.schemaConnection();var q=c.createStatement()){
            q.executeUpdate("INSERT INTO guild_trial_launch(request_id,state) VALUES(5110,1)");q.executeUpdate("INSERT INTO guild_trial_launch_role(player_id,request_id,account,owner_revision,state) VALUES(5111,5110,'account-5111',1,1)");
        }
        assertFalse(sql.begin(actor(5111),watchRequest(5119),budget()).original().getResponsePayload().isEmpty());assertThrows(RuntimeException.class,()->store.prepareJoin(actor(5111),joinRequest(5111),2,1,150000,"s"));
        try(var c=fixture.schemaConnection();var q=c.createStatement()){
            // 正规终局同时提交父CLOSED和角色CLOSED；只有子投影2不能掩盖仍PREPARED的父原件。
            c.setAutoCommit(false);q.executeUpdate("UPDATE guild_trial_launch SET state=3 WHERE request_id=5110");
            q.executeUpdate("UPDATE guild_trial_launch_role SET state=2 WHERE player_id=5111");c.commit();
        }
        assertDoesNotThrow(()->store.prepareJoin(actor(5111),joinRequest(5111),2,1,150000,"s"));
    }

    @Test void 私有历练大厅即使TTL已过仍阻新Watch与Join_只有真实终局放行()throws Exception{
        var ticket=join(5121);store.cancel(actor(5121),ticket.getTicket(),ticket.getRequestId());
        execute("INSERT INTO guild_activity_slot VALUES(5121,5120,1)");
        execute("INSERT INTO guild_trial_lobby(lobby_id,state,guild_id,activity_id) VALUES(5120,1,11,3)");
        assertFalse(watches().begin(actor(5121),watchRequest(5129),budget()).original().getResponsePayload().isEmpty());
        assertThrows(RuntimeException.class,()->store.prepareJoin(actor(5121),joinRequest(5121),2,1,150000,"s"));
        execute("UPDATE guild_trial_lobby SET state=4 WHERE lobby_id=5120");
        assertDoesNotThrow(()->store.prepareJoin(actor(5121),joinRequest(5121),2,1,150000,"s"));
    }
    @Test void Team首次Match网络原件跨重试固定_换新profile只能安全终结原unit()throws Exception {
        var group=party(5200,5201,5202);var first=store.prepareTeam(group.key(),group.original().toByteString(),group.members(),GlobalFormationRow.getDefaultInstance());
        assertEquals(group.members(),first.getStartOriginal().getTicketsList());assertEquals(1,store.teamIntent(group.key(),group.original().getRequest().toByteString()).getState());
        var profile=GlobalProbeProfile.parseFrom(store.profile(5201).getPayload());store.plan(5201,profile.toBuilder().setPlanId("new-plan").build(),store.now()+120000);
        assertEquals(first,store.prepareTeam(group.key(),group.original().toByteString(),first.getStartOriginal().getTicketsList(),GlobalFormationRow.getDefaultInstance()));
        assertTrue(store.closeObsoleteTeam(first,budget()));assertEquals(5,store.teamIntent(group.key(),group.original().getRequest().toByteString()).getState());
        assertTrue(store.direct(group.key()).getCancelled());for(var member:group.members())assertEquals(4,store.ticket(member.getPlayerId()).getState());
    }
    @Test void 排队后home改变不能由修复任务覆盖原单位()throws Exception {
        var group=party(5300,5301,5302);var unit=queued(group);
        try(var c=fixture.schemaConnection();var q=c.createStatement()){q.executeUpdate("UPDATE player SET zone_id=2 WHERE player_id=5301");}
        assertThrows(RuntimeException.class,store::repairRegions);
        var raw=store.unitCandidates(0,"").stream().filter(row->row.getUnitId().equals(unit.getUnitId())).findFirst().orElseThrow();assertEquals(unit,raw);
        assertEquals(1,store.ticket(5301).getZoneId());
    }

}
