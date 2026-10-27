package com.game.team.store;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.proto.team.*;
import com.game.team.rules.*;
import com.google.protobuf.Message;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 真TiDB、每例随机独占库；不依赖Redis，不把静态阅读或测试源码当作运行结果。 */
class TeamSqlAuthorityTiDbTest {
    private String base;
    private AuthoritySqlFixture fixture;
    private DruidDataSource pool;
    private TeamStore store;
    private AuthorityJdbc sql;
    private static Deadline budget(){return Deadline.after(15_000);}
    @BeforeEach void setup()throws Exception{
        base=System.getenv("TEAM_TEST_JDBC");if(base==null||base.isBlank()){
            if("1".equals(System.getenv("TEAM_REQUIRE_TESTS")))fail("TEAM_TEST_JDBC必须配置且指向可建独占库的TiDB根URL");
            Assumptions.assumeTrue(false,"未配置独占TiDB");
        }
        fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(base,
                System.getProperty("game.it.mysql.user","root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),
                "team_authority",false,List.of(1)),(c,principal)->{
            TeamTables.registry().initializeFresh(c,principal);
            try(var s=c.createStatement()){
            s.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,account VARBINARY(191) NOT NULL,owner_revision BIGINT UNSIGNED NOT NULL,trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
            // Match表由其正式生产模块创建；本域只造清锁证明所需最小列，不在生产跨域建表。
            s.execute("CREATE TABLE match_global_direct_intent(kind INT,owner_id BIGINT UNSIGNED,request_high BIGINT UNSIGNED,request_low BIGINT UNSIGNED,battle_id BIGINT UNSIGNED,owner_account VARCHAR(191),owner_revision BIGINT UNSIGNED,payload BLOB,cancelled INT NOT NULL DEFAULT 0,PRIMARY KEY(kind,owner_id,request_high,request_low))");
            s.execute("CREATE TABLE match_global_formation(battle_id BIGINT UNSIGNED PRIMARY KEY,state INT,host VARCHAR(191),port INT,node_id INT,instance_id VARCHAR(36))");
            // 仅声明common取消协议实际访问的跨域列；完整Match生产建表/准入仍由Match自己的集成测试负责。
            s.execute("CREATE TABLE match_global_queue_unit(unit_id VARCHAR(191) PRIMARY KEY,state INT,mode INT,config_id INT,target_size INT,member_count INT,enqueued_ms BIGINT,not_before_ms BIGINT,expires_ms BIGINT,preferred_region INT,original_tickets BLOB,launch_original BLOB,team_id BIGINT UNSIGNED,battle_id BIGINT UNSIGNED,profile_digest BLOB)");
            s.execute("CREATE TABLE match_global_ticket(player_id BIGINT UNSIGNED PRIMARY KEY,ticket VARCHAR(191),queue_unit_id VARCHAR(191),state INT,owner_account VARCHAR(191),owner_revision BIGINT,formation_id BIGINT UNSIGNED,selected_region INT)");
            s.execute("CREATE TABLE match_global_ticket_region(player_id BIGINT UNSIGNED,region INT,PRIMARY KEY(player_id,region))");
            s.execute("CREATE TABLE match_global_queue_unit_region(unit_id VARCHAR(191),region INT,PRIMARY KEY(unit_id,region))");
            s.execute("CREATE TABLE match_global_join_intent(player_id BIGINT UNSIGNED,request_id VARCHAR(36),ticket_record BLOB,cancelled INT,request_high BIGINT UNSIGNED,request_low BIGINT UNSIGNED,owner_account VARCHAR(191),owner_revision BIGINT,PRIMARY KEY(player_id,request_high,request_low))");
            s.execute("CREATE TABLE match_global_watching(player_id BIGINT UNSIGNED PRIMARY KEY,operation_id VARCHAR(36),owner_account VARCHAR(191),owner_revision BIGINT,state INT,add_request BLOB)");
            s.execute("CREATE TABLE match_global_watch_head(player_id BIGINT UNSIGNED PRIMARY KEY,request_id VARCHAR(36))");
            s.execute("CREATE TABLE match_global_watch_request(player_id BIGINT UNSIGNED,request_id VARCHAR(36),owner_account VARCHAR(191),owner_revision BIGINT,cancelled INT,original_add BLOB,PRIMARY KEY(player_id,request_id))");
            s.execute("CREATE TABLE guild_trial_launch_role(player_id BIGINT UNSIGNED,request_id BIGINT UNSIGNED,state INT)");
            s.execute("CREATE TABLE guild_trial_launch(request_id BIGINT UNSIGNED PRIMARY KEY,state INT)");
            s.execute("CREATE TABLE guild_activity_slot(player_id BIGINT UNSIGNED PRIMARY KEY,lobby_id BIGINT UNSIGNED,expires_ms BIGINT UNSIGNED)");
            s.execute("CREATE TABLE guild_trial_lobby(lobby_id BIGINT UNSIGNED PRIMARY KEY,state INT,view_proto MEDIUMBLOB,guild_id BIGINT UNSIGNED,activity_id INT UNSIGNED)");
            }
        });
        pool=new DruidDataSource();pool.setUrl(fixture.jdbcUrl("?useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"));
        pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(16);pool.setMaxWait(5000);
        AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);
        TeamTables.sync(pool);TeamTables.ensurePolicy(pool,false);store=new TeamStore(pool);sql=new AuthorityJdbc(pool);
    }
    @AfterEach void cleanup()throws Exception{if(pool!=null)pool.close();if(fixture!=null)fixture.close();}
    private SessionContext actor(long p){return SessionContext.newBuilder().setPlayerId(p).setAccount("原账号 "+Long.toUnsignedString(p)).setOwnerRevision(7).build();}
    private void player(long p){sqlRun("INSERT INTO player(player_id,account,owner_revision,trade_listing_id) VALUES(?,?,7,0)",AuthorityJdbc.u64(p),actor(p).getAccount());}
    private void sqlRun(String text,Object... args){try(var c=fixture.schemaConnection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}catch(Exception e){throw new AssertionError(e);}}
    private long count(String table){try{return sql.read(budget(),c->c.count("SELECT COUNT(*) FROM "+table));}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") private static <T extends Message>T id(T value,String request){var b=value.toBuilder();var f=value.getDescriptorForType().findFieldByName("request_id");assertNotNull(f,"正式共享契约必须带稳定业务UUID");return (T)b.setField(f,request).build();}
    private static <T extends Message>T id(T value){return id(value,UUID.randomUUID().toString());}
    private TeamStore bound(long p,String method,Message request){return store.forActor(actor(p),method,request);}
    private MutateResult create(long p,long team){return bound(p,"CreateTeam",id(CreateTeamRequest.getDefaultInstance())).mutate(Bind.create(p,team),Op.create(p,team,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());}
    private MutateResult invite(long p,long team,long target){return bound(p,"InviteToTeam",id(InviteToTeamRequest.newBuilder().setExpectedTeamId(team).setTargetPlayerId(target).build())).mutate(Bind.caller(p,team),Op.invite(p,target,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());}

    @Test void Join票或Trial原launch先赢时新TeamStart不得提交任何半原件()throws Exception{
        player(1);create(1,10);var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());
        sqlRun("INSERT INTO match_global_ticket(player_id,state) VALUES(1,1)");
        assertThrows(RuntimeException.class,()->start.commitMatchLock(snap,1,UUID.randomUUID().toString(),List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30));assertEquals(0,count("team_launch"));assertEquals(0,count("team_launch_role"));
        sqlRun("UPDATE match_global_ticket SET state=4 WHERE player_id=1");sqlRun("INSERT INTO guild_trial_launch VALUES(91,1)");sqlRun("INSERT INTO guild_trial_launch_role VALUES(1,91,1)");
        assertThrows(RuntimeException.class,()->start.commitMatchLock(snap,1,UUID.randomUUID().toString(),List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30));assertEquals(0,count("team_launch"));assertTrue(store.read(1,10,budget()).record().getMatchLockToken().isEmpty());
    }

    @Test void 永久记录和epoch墓碑_进程重建与旧请求重放不能换名单或复活已解散队()throws Exception{
        player(1);var request=id(CreateTeamRequest.getDefaultInstance());var first=bound(1,"CreateTeam",request);
        var created=first.mutate(Bind.create(1,10),Op.create(1,10,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());assertEquals(Outcome.COMMITTED,created.outcome());assertEquals(1,created.commit().indexes().get(1L).epoch());
        var replay=new TeamStore(pool).forActor(actor(1),"CreateTeam",request).mutate(Bind.create(1,99),Op.create(1,99,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());assertEquals(10,replay.commit().teamId());assertEquals(1,count("team_authority"));
        var leave=id(LeaveTeamRequest.newBuilder().setExpectedTeamId(10).build());var result=bound(1,"LeaveTeam",leave).mutate(Bind.caller(1,10),Op.leave(1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());assertEquals(Outcome.COMMITTED,result.outcome());
        var free=store.readFree(1,budget()).snapshot();assertEquals(0,free.playerTeamId());assertEquals(2,free.playerEpoch());assertNull(store.read(1,10,budget()).record());assertEquals(1,count("team_authority"));
        assertEquals(10,first.replay(budget()).commit().teamId());assertEquals(0,store.readFree(1,budget()).snapshot().playerTeamId());
    }
    @Test void 私有Trial大厅即使TTL已过也阻首次Team开局_终局后才可提交原launch(){
        player(1);bound(1,"CreateTeam",id(CreateTeamRequest.getDefaultInstance())).mutate(Bind.create(1,10),Op.create(1,10,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());
        var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(5).build());
        var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());
        sqlRun("INSERT INTO guild_activity_slot VALUES(1,91,1)");sqlRun("INSERT INTO guild_trial_lobby(lobby_id,state,guild_id,activity_id) VALUES(91,1,11,3)");
        assertThrows(RuntimeException.class,()->start.commitMatchLock(snap,1,UUID.randomUUID().toString(),List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30));
        assertEquals(0,count("team_launch"));assertEquals(0,count("team_launch_role"));assertTrue(store.read(1,10,budget()).record().getMatchLockToken().isEmpty());
        sqlRun("UPDATE guild_trial_lobby SET state=4 WHERE lobby_id=91");
        assertDoesNotThrow(()->start.commitMatchLock(snap,1,UUID.randomUUID().toString(),List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30));
        assertEquals(1,count("team_launch"));
    }
    @Test void 原账号转让_ABA_冻结在任何队伍副作用前拒绝_旧UUID不能跨归属返回(){
        player(1);var request=id(CreateTeamRequest.getDefaultInstance());var old=bound(1,"CreateTeam",request);assertEquals(Outcome.COMMITTED,old.mutate(Bind.create(1,10),Op.create(1,10,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());
        for(String change:List.of("account='新主',owner_revision=8","account='原账号 1',owner_revision=9","account='原账号 1',owner_revision=7,trade_listing_id=101")){
            sqlRun("UPDATE player SET "+change+" WHERE player_id=1");assertThrows(RuntimeException.class,()->old.replay(budget()));
            assertThrows(RuntimeException.class,()->old.mutate(Bind.caller(1,10),Op.disband(1),SessionLoader.NONE,RuleConfig.DEFAULT,budget()));assertNotNull(store.read(1,10,budget()).record());assertEquals(1,count("team_action"));
        }
        sqlRun("UPDATE player SET account='新主',owner_revision=8,trade_listing_id=0 WHERE player_id=1");
        var next=store.forActor(actor(1).toBuilder().setAccount("新主").setOwnerRevision(8).build(),"CreateTeam",request);assertThrows(RuntimeException.class,()->next.replay(budget()));
    }
    @Test void 邀请保存私有原归属_新主人不得接受旧邀请且没有半入队(){
        player(1);player(2);create(1,10);assertEquals(Outcome.COMMITTED,invite(1,10,2).outcome());
        var before=store.read(1,10,budget()).record();assertEquals(actor(2).getAccount(),before.getInvites(0).getOwnerAccount());
        try{var consent=com.game.proto.team.TeamConsentOriginal.parseFrom(before.getInvites(0).getConsentOriginal());assertEquals(actor(1).getAccount(),consent.getInitiator().getOwnerAccount());assertEquals(actor(2).getAccount(),consent.getRecipient().getOwnerAccount());}catch(com.google.protobuf.InvalidProtocolBufferException e){fail(e);}
        sqlRun("UPDATE player SET account='新主',owner_revision=8 WHERE player_id=2");
        var request=id(RespondInviteRequest.newBuilder().setTeamId(10).setAccept(true).build());var next=store.forActor(actor(2).toBuilder().setAccount("新主").setOwnerRevision(8).build(),"RespondInvite",request);
        assertThrows(RuntimeException.class,()->next.mutate(Bind.target(2,10),Op.respondInvite(2,true),SessionLoader.NONE,RuleConfig.DEFAULT,budget()));assertEquals(before,store.read(1,10,budget()).record());assertEquals(0,store.readFree(2,budget()).snapshot().playerTeamId());
    }
    @Test void 申请同意_应邀加入_转让踢人解散都维护唯一成员与墓碑(){
        player(1);player(2);player(3);create(1,10);
        var apply=id(ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(1).build());assertEquals(Outcome.COMMITTED,bound(2,"ApplyJoinTeam",apply).mutate(Bind.target(2,10),Op.apply(2,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());assertEquals(1,count("team_application_authority"));
        var approve=id(HandleApplicationRequest.newBuilder().setExpectedTeamId(10).setApplicantId(2).setApprove(true).build());assertEquals(Outcome.COMMITTED,bound(1,"HandleApplication",approve).mutate(Bind.caller(1,10),Op.handleApplication(1,2,true),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());assertEquals(0,count("team_application_authority"));
        invite(1,10,3);var accept=id(RespondInviteRequest.newBuilder().setTeamId(10).setAccept(true).build());assertEquals(Outcome.COMMITTED,bound(3,"RespondInvite",accept).mutate(Bind.target(3,10),Op.respondInvite(3,true),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());assertEquals(0,count("team_invite_authority"));
        var transfer=id(TransferLeaderRequest.newBuilder().setExpectedTeamId(10).setTargetPlayerId(2).build());SessionLoader online=(ids,d)->Map.of(1L,SessionState.ONLINE,2L,SessionState.ONLINE,3L,SessionState.ONLINE);
        assertEquals(Outcome.COMMITTED,bound(1,"TransferLeader",transfer).mutate(Bind.caller(1,10),Op.transferLeader(1,2),online,RuleConfig.DEFAULT,budget()).outcome());assertEquals(2,store.read(1,10,budget()).record().getLeaderId());
        var kick=id(KickMemberRequest.newBuilder().setExpectedTeamId(10).setTargetPlayerId(3).build());assertEquals(Outcome.COMMITTED,bound(2,"KickMember",kick).mutate(Bind.caller(2,10),Op.kick(2,3),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());assertEquals(0,store.readFree(3,budget()).snapshot().playerTeamId());assertEquals(2,store.readFree(3,budget()).snapshot().playerEpoch());
        var disband=id(DisbandTeamRequest.newBuilder().setExpectedTeamId(10).build());assertEquals(Outcome.COMMITTED,bound(2,"DisbandTeam",disband).mutate(Bind.caller(2,10),Op.disband(2),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());assertNull(store.read(1,10,budget()).record());for(long p:List.of(1L,2L,3L))assertEquals(2,store.readFree(p,budget()).snapshot().playerEpoch());
    }
    @Test void 每目标最多十邀请_并发重放不越界且不能邀请已在队角色(){
        player(100);for(int p=1;p<=11;p++){player(p);create(p,10+p);var r=invite(p,10+p,100);assertEquals(p<=10?Outcome.COMMITTED:Outcome.REJECTED,r.outcome());if(p==11)assertEquals(TeamTips.INVITE_LIMIT,r.code());}
        assertEquals(10,store.listInvites(100,budget()).entries().size());assertEquals(TeamTips.MEMBER_IN_TEAM,invite(1,11,2).code());
    }
    @Test void 开局未知和TTL超时不解冻_原名单位置epoch可恢复_仅真实Match终局清锁()throws Exception{
        player(1);create(1,10);var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        var locked=start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+1000,SessionLoader.NONE,budget(),Map.of(1L,9),1);assertNotNull(locked.commit());
        // expiry只是预算；不等待墙钟，以纯规则显式使用远未来时间。
        assertTrue(TeamRules.matchLockActive(locked.commit().decision().record(),Long.MAX_VALUE));
        assertEquals(EndMatchStop.DEADLINE,start.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).stop());assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
        assertEquals(locked.commit().version(),new TeamStore(pool).forActor(actor(1),"StartTeamMatch",request).resumeLaunch(budget()).version());assertEquals(1,start.launchSubmission(budget()).getZonesOrThrow(1));assertEquals(1,start.launchSubmission(budget()).getIndexes(0).getEpoch());
        var original=com.game.proto.team.TeamLaunchOriginal.parseFrom(start.launchOriginal(budget()));assertEquals(1,original.getZonesOrThrow(1));assertEquals(actor(1).getAccount(),original.getActor().getOwnerAccount());assertEquals(1,original.getMembers(0).getRosterOrder());assertEquals(TeamStore.ticketId(request.getRequestId(),1),original.getMembers(0).getTicket());
        var uuid=UUID.fromString(request.getRequestId());sqlRun("INSERT INTO match_global_direct_intent(kind,owner_id,request_high,request_low,battle_id,owner_account,owner_revision,payload) VALUES(1,1,?,?,99,?,7,?)",AuthorityJdbc.u64(uuid.getMostSignificantBits()),AuthorityJdbc.u64(uuid.getLeastSignificantBits()),actor(1).getAccount(),request.toByteArray());
        for(int state:List.of(1,2,3,99)){sqlRun("DELETE FROM match_global_formation");sqlRun("INSERT INTO match_global_formation(battle_id,state) VALUES(99,?)",state);assertEquals(EndMatchStop.DEADLINE,start.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).stop());}
        sqlRun("UPDATE match_global_formation SET state=5 WHERE battle_id=99");assertNotNull(start.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).pinned().commit());assertTrue(store.read(1,10,budget()).record().getMatchLockToken().isEmpty());
    }
    @Test void 相反参与顺序含uint64高半区_全员P升序并发不死锁()throws Exception{
        long high=Long.MIN_VALUE+7;player(1);player(high);create(1,10);create(high,20);var barrier=new CyclicBarrier(2);
        try(var threads=Executors.newFixedThreadPool(2)){
            var a=threads.submit(()->{barrier.await();return invite(1,10,high);});var b=threads.submit(()->{barrier.await();return invite(high,20,1);});
            assertEquals(TeamTips.MEMBER_IN_TEAM,a.get(15,TimeUnit.SECONDS).code());assertEquals(TeamTips.MEMBER_IN_TEAM,b.get(15,TimeUnit.SECONDS).code());
        }
        assertEquals(0,count("team_invite_authority"));
    }
    @Test void 名册变化先取消原完整单位_早于迟到Start也留下永久墓碑()throws Exception{
        player(1);create(1,10);var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        assertNotNull(start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30).commit());
        var leave=id(LeaveTeamRequest.newBuilder().setExpectedTeamId(10).build());assertEquals(Outcome.COMMITTED,bound(1,"LeaveTeam",leave).mutate(Bind.caller(1,10),Op.leave(1),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).outcome());
        assertEquals(0,store.readFree(1,budget()).snapshot().playerTeamId());assertEquals(1,count("match_global_queue_unit"));assertEquals(1,count("match_global_join_intent"));
        sql.read(budget(),c->{assertEquals(4,c.<Integer>one("SELECT state FROM match_global_queue_unit",r->r.getInt(1)).intValue());assertEquals(1,c.<Integer>one("SELECT cancelled FROM match_global_direct_intent",r->r.getInt(1)).intValue());assertEquals(5,c.<Integer>one("SELECT state FROM team_launch",r->r.getInt(1)).intValue());assertEquals(2,c.<Integer>one("SELECT state FROM team_launch_role",r->r.getInt(1)).intValue());return null;});
        assertEquals(Outcome.COMMITTED,bound(1,"LeaveTeam",leave).replay(budget()).outcome());assertEquals(1,count("match_global_queue_unit"));
    }
    @Test void 队伍已被装箱则名册变化不能拆队_原SQL策略不允许后启动者覆盖()throws Exception{
        player(1);create(1,10);var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30);byte[] original=start.launchOriginal(budget()).toByteArray();
        sqlRun("INSERT INTO match_global_queue_unit(unit_id,state,team_id,battle_id,launch_original) VALUES(?,2,10,99,?)","t:10:"+request.getRequestId(),original);
        var leave=id(LeaveTeamRequest.newBuilder().setExpectedTeamId(10).build());assertEquals(TeamTips.IN_MATCH,bound(1,"LeaveTeam",leave).mutate(Bind.caller(1,10),Op.leave(1),SessionLoader.NONE,RuleConfig.DEFAULT,budget()).code());assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
        assertThrows(java.sql.SQLException.class,()->TeamTables.ensurePolicy(pool,true));assertEquals(1,count("team_policy"));
        try(var c=pool.getConnection()){assertFalse(com.game.common.team.TeamPolicyAuthority.load(c,3).allowCrossZone());}
        sqlRun("UPDATE team_policy SET allow_cross_zone=1 WHERE id=1");
        try(var c=pool.getConnection()){assertThrows(java.sql.SQLException.class,()->com.game.common.team.TeamPolicyAuthority.load(c,3));}
        assertThrows(java.sql.SQLException.class,()->TeamTables.ensurePolicy(pool,false));
    }
    @Test void 不以旧作废formation清除重新排队的当前单位锁()throws Exception{
        player(1);create(1,10);var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30);var uuid=UUID.fromString(request.getRequestId());
        sqlRun("INSERT INTO match_global_direct_intent(kind,owner_id,request_high,request_low,battle_id,owner_account,owner_revision,payload) VALUES(1,1,?,?,99,?,7,?)",AuthorityJdbc.u64(uuid.getMostSignificantBits()),AuthorityJdbc.u64(uuid.getLeastSignificantBits()),actor(1).getAccount(),request.toByteArray());sqlRun("INSERT INTO match_global_formation(battle_id,state) VALUES(99,5)");
        sqlRun("INSERT INTO match_global_queue_unit(unit_id,state,team_id,battle_id,launch_original) VALUES(?,1,10,0,?)","t:10:"+request.getRequestId(),start.launchOriginal(budget()).toByteArray());
        assertEquals(EndMatchStop.DEADLINE,start.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).stop());assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
    }
    @Test void 首次原Start固定捕获观战退出并封head_重放不抓后来原件()throws Exception{
        player(1);create(1,10);String operation=UUID.randomUUID().toString();
        sqlRun("INSERT INTO match_global_watching VALUES(1,?,?,7,1,?)",operation,actor(1).getAccount(),new byte[0]);sqlRun("INSERT INTO match_global_watch_head VALUES(1,?)",operation);sqlRun("INSERT INTO match_global_watch_request VALUES(1,?,?,7,0,?)",operation,actor(1).getAccount(),new byte[0]);
        var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        var committed=start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30);assertNotNull(committed.commit());var bytes=start.launchOriginal(budget());var original=com.game.proto.team.TeamLaunchOriginal.parseFrom(bytes);assertEquals(1,original.getObserverExitsCount());assertEquals(operation,original.getObserverExits(0).getHeadRequestId());assertFalse(original.getObserverExits(0).hasAdd());
        sql.read(budget(),c->{assertEquals(1,c.<Integer>one("SELECT cancelled FROM match_global_watch_request WHERE player_id=1",r->r.getInt(1)).intValue());assertEquals(5,c.<Integer>one("SELECT state FROM match_global_watching WHERE player_id=1",r->r.getInt(1)).intValue());return null;});
        sqlRun("UPDATE match_global_watch_head SET request_id=? WHERE player_id=1",UUID.randomUUID().toString());
        assertNotNull(start.resumeLaunch(budget()));assertEquals(bytes,start.launchOriginal(budget()));assertNotNull(start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30).commit());assertEquals(bytes,start.launchOriginal(budget()));
    }
    @Test void 已活动观众原Add与固定实例RPC目标随首次launch原子保存()throws Exception{
        player(1);create(1,10);String operation=UUID.randomUUID().toString(),instance=UUID.randomUUID().toString();
        var add=com.game.proto.AddObserverRequest.newBuilder().setBattleId(77).setObserverPlayerId(1).setSpectatorOperationId(operation).setTargetInstanceId(instance).setAdmitBeforeMs(1).build();
        sqlRun("INSERT INTO match_global_formation VALUES(77,4,'127.0.0.1',20889,9,?)",instance);sqlRun("INSERT INTO match_global_watching VALUES(1,?,?,7,3,?)",operation,actor(1).getAccount(),add.toByteArray());sqlRun("INSERT INTO match_global_watch_head VALUES(1,?)",operation);sqlRun("INSERT INTO match_global_watch_request VALUES(1,?,?,7,0,?)",operation,actor(1).getAccount(),add.toByteArray());
        var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).build());var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());start.commitMatchLock(snap,1,UUID.randomUUID().toString(),List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30);
        var exit=com.game.proto.team.TeamLaunchOriginal.parseFrom(start.launchOriginal(budget())).getObserverExits(0);assertEquals(add,exit.getAdd());assertEquals(instance,exit.getInstanceId());assertEquals("127.0.0.1",exit.getRpcHost());assertEquals(20889,exit.getRpcPort());assertEquals(9,exit.getNodeId());assertEquals(actor(1).getAccount(),exit.getOwnerAccount());assertEquals(7,exit.getOwnerRevision());
    }
    @Test void 场景权威读取不依赖Redis旧投影_SQL原件缺失必须失败而非放行为无队()throws Exception{
        player(1);create(1,10);var reader=new com.game.discovery.team.TeamMembershipReader(pool,Runnable::run);
        var membership=reader.readAsync(1).get(5,TimeUnit.SECONDS);assertEquals(10,membership.teamId());assertEquals(1,membership.epoch());assertEquals(List.of(1L),membership.info().getMembersList());
        sqlRun("UPDATE team_authority SET tombstone=1 WHERE team_id=10");assertThrows(ExecutionException.class,()->reader.readAsync(1).get(5,TimeUnit.SECONDS));
        sqlRun("UPDATE team_member_authority SET team_id=0,epoch=2 WHERE player_id=1");var free=reader.readAsync(1).get(5,TimeUnit.SECONDS);assertEquals(0,free.teamId());assertEquals(2,free.epoch());assertFalse(free.keyMissing());
    }
    @Test void 固定UUID不能换方法_失败不改旧队伍或追加回执()throws Exception{
        player(1);String request=UUID.randomUUID().toString();var create=id(CreateTeamRequest.getDefaultInstance(),request);var original=bound(1,"CreateTeam",create);original.mutate(Bind.create(1,10),Op.create(1,10,1),SessionLoader.NONE,RuleConfig.DEFAULT,budget());
        var leave=id(LeaveTeamRequest.newBuilder().setExpectedTeamId(10).build(),request);assertThrows(RuntimeException.class,()->bound(1,"LeaveTeam",leave).replay(budget()));assertEquals(1,count("team_action"));assertNotNull(store.read(1,10,budget()).record());
    }

    @Test void 历史开局显示允许不同查询正文和未绑定读取_损坏UUID名册与归属必拒绝()throws Exception{
        player(1);create(1,10);
        var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());
        var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        var locked=start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30);
        assertNotNull(locked.commit());var bytes=start.launchOriginal(budget());var original=TeamLaunchOriginal.parseFrom(bytes);
        sqlRun("INSERT INTO match_global_queue_unit(unit_id,state,target_size,enqueued_ms,config_id,launch_original,team_id,battle_id) VALUES(?,1,?,?,?,?,10,0)",
                "t:10:"+request.getRequestId(),3,original.getEnqueuedMs(),7,bytes.toByteArray());
        var view=TeamView.newBuilder().setTeamId(10).setVersion(locked.commit().version()).addMembers(TeamMemberView.newBuilder().setPlayerId(1)).build();
        var query=bound(1,"GetMyTeam",GetMyTeamRequest.getDefaultInstance());
        var projected=query.projectMatchView(view,budget());var unbound=store.projectMatchView(view,budget());
        assertEquals(projected,unbound);assertTrue(projected.getMatchFillSupported());assertEquals(TeamMatchState.TEAM_MATCH_STATE_QUEUED,projected.getMatchState());
        assertEquals(3,projected.getMatchTargetSize());assertEquals(original.getEnqueuedMs(),projected.getMatchQueuedAtMs());assertEquals(7,projected.getMatchBattleConfigId());
        var changed=id(request.toBuilder().setBattleConfigId(99).build(),request.getRequestId());
        assertThrows(RuntimeException.class,()->bound(1,"StartTeamMatch",changed).resumeLaunch(budget()),"当前Start相同UUID仍不得换完整正文");
        var wrongUUID=original.toBuilder().setRequest(original.getRequest().toBuilder().setRequestId(UUID.randomUUID().toString())).build();
        var wrongRoster=original.toBuilder().setMembers(0,original.getMembers(0).toBuilder().setRosterOrder(2)).build();
        var wrongActor=original.toBuilder().setActor(original.getActor().toBuilder().setOwnerRevision(8)).build();
        for(var damaged:List.of(wrongUUID,wrongRoster,wrongActor)){
            sqlRun("UPDATE team_launch SET original_proto=? WHERE request_id=?",damaged.toByteArray(),request.getRequestId());
            sqlRun("UPDATE match_global_queue_unit SET launch_original=? WHERE unit_id=?",damaged.toByteArray(),"t:10:"+request.getRequestId());
            try{
                assertThrows(RuntimeException.class,()->query.projectMatchView(view,budget()));
                assertThrows(RuntimeException.class,()->store.projectMatchView(view,budget()));
                assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
                assertEquals(1,count("team_launch"));assertEquals(0,count("match_global_direct_intent"));assertEquals(0,count("match_global_join_intent"));
            }finally{
                sqlRun("UPDATE team_launch SET original_proto=? WHERE request_id=?",bytes.toByteArray(),request.getRequestId());
                sqlRun("UPDATE match_global_queue_unit SET launch_original=? WHERE unit_id=?",bytes.toByteArray(),"t:10:"+request.getRequestId());
            }
        }
        assertEquals(projected,query.projectMatchView(view,budget()));assertEquals(bytes,start.launchOriginal(budget()));
    }
    @Test void 内部清锁允许不同当前请求正文_仍须原永久取消证明且拒绝损坏原件()throws Exception{
        player(1);create(1,10);
        var request=id(StartTeamMatchRequest.newBuilder().setExpectedTeamId(10).setBattleConfigId(7).setTargetTeamSize(3).build());
        var start=bound(1,"StartTeamMatch",request);var snap=start.read(1,10,budget());String token=UUID.randomUUID().toString();
        assertNotNull(start.commitMatchLock(snap,1,token,List.of(1L),snap.nowMs()+30_000,SessionLoader.NONE,budget(),Map.of(1L,1),30).commit());
        var bytes=start.launchOriginal(budget());var original=TeamLaunchOriginal.parseFrom(bytes);
        var requestNow=id(LeaveTeamRequest.newBuilder().setExpectedTeamId(10).build());assertNotEquals(request.getRequestId(),requestNow.getRequestId());
        var internal=bound(1,"LeaveTeam",requestNow);
        assertEquals(EndMatchStop.DEADLINE,internal.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).stop(),"没有SQL终局证明不能因不同当前请求解除原锁");
        assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
        sql.tx(budget(),c->{
            com.game.common.player.PlayerSessionAuthority.lock(c.connection(),1,actor(1).getAccount(),actor(1).getOwnerRevision(),3);
            c.one("SELECT version FROM team_authority WHERE team_id=10 FOR UPDATE",r->r.getLong(1));
            assertEquals(com.game.common.player.TeamQueueAuthority.Result.CANCELLED,
                    com.game.common.player.TeamQueueAuthority.cancel(c.connection(),original,bytes.toByteArray(),3));
            return null;
        });
        var wrongActor=original.toBuilder().setActor(original.getActor().toBuilder().setOwnerRevision(8)).build();
        sqlRun("UPDATE team_launch SET original_proto=? WHERE request_id=?",wrongActor.toByteArray(),request.getRequestId());
        sqlRun("UPDATE match_global_queue_unit SET launch_original=? WHERE unit_id=?",wrongActor.toByteArray(),"t:10:"+request.getRequestId());
        try{
            assertThrows(RuntimeException.class,()->internal.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()));
            assertEquals(token,store.read(1,10,budget()).record().getMatchLockToken());
        }finally{
            sqlRun("UPDATE team_launch SET original_proto=? WHERE request_id=?",bytes.toByteArray(),request.getRequestId());
            sqlRun("UPDATE match_global_queue_unit SET launch_original=? WHERE unit_id=?",bytes.toByteArray(),"t:10:"+request.getRequestId());
        }
        assertNotNull(internal.releaseMatchLockOnce(10,token,SessionLoader.NONE,budget()).pinned().commit());
        assertTrue(store.read(1,10,budget()).record().getMatchLockToken().isEmpty());assertEquals(bytes,start.launchOriginal(budget()));
        sql.read(budget(),c->{
            assertEquals(1,c.<Integer>one("SELECT COUNT(*) FROM match_global_direct_intent WHERE cancelled=1 AND battle_id=0",r->r.getInt(1)).intValue());
            assertEquals(4,c.<Integer>one("SELECT state FROM match_global_queue_unit",r->r.getInt(1)).intValue());
            assertEquals(0,c.<Integer>one("SELECT COUNT(*) FROM team_launch WHERE request_id=?",r->r.getInt(1),requestNow.getRequestId()).intValue());
            return null;
        });
    }
}
