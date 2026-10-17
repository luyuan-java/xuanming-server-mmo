package com.game.match.global;

import com.game.api.DubboGroups;
import com.game.api.RankAdminService;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.token.NetworkProbes;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.global.pb.*;
import com.game.match.rating.RatingStore;
import com.game.pbmysql.PbMysql;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.rank.*;
import com.google.protobuf.ByteString;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 评级源的可靠投榜：业务事务保留完整批次，发送只从已提交游标顺发。 */
public final class RatingRankOutbox implements RatingStore.CommitHook,AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(RatingRankOutbox.class);
    private final DataSource source;
    private final PbMysql db=new PbMysql();
    private final GlobalRankProperties config;
    private final NodeRpcClients<RankAdminService> calls;
    private final NodeRpcClients.Target target;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("match-rank-outbox").daemon(true).factory());
    public RatingRankOutbox(DataSource source,GlobalRankProperties config){
        if(config.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("评级投榜必须注入 RANK_PRODUCER_SECRET_MATCH");
        this.source=source;this.config=config;db.register(GlobalRankSourceRow.getDefaultInstance());db.register(GlobalRankOutboxRow.getDefaultInstance());
        target=new NodeRpcClients.Target(config.host(),config.port(),"");
        calls=new NodeRpcClients<>("game-match-rank-outbox",RankAdminService.class,DubboGroups.RANK,Duration.ofSeconds(3),"match-rank-connect");
    }
    private static byte[] digest(BattleResultEvent event){try{return MessageDigest.getInstance("SHA-256").digest(NetworkProbes.bytes(event));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public boolean alreadyApplied(Connection c,BattleResultEvent event)throws SQLException{
        var old=db.findOne(c,GlobalRankOutboxRow.class,"request_id=?",PbMysql.uint64(event.getBattleId()));
        if(old.isEmpty())return false;
        if(!MessageDigest.isEqual(old.get().getPayloadDigest().toByteArray(),digest(event)))throw new SQLException("同对局重复评分内容不同");
        return true;
    }
    @Override public void append(Connection c,BattleResultEvent event,Map<Long,Long> next,long stamp)throws SQLException{
        var key=GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build();db.insertIgnore(c,key);
        var guard=db.findOneByPkForUpdate(c,key).orElseThrow();
        if(guard.getStopped())throw new SQLException("评级代次已停止，必须显式协调下一代");
        long cursor=guard.getProducedCursor()+1;
        var request=SubmitRankScoresRequest.newBuilder().setGeneration(1).setSourceEpoch(1).setSourceCursor(cursor)
                .setRequestId(event.getBattleId()).setMode(RankSubmitMode.RANK_SUBMIT_SET);
        next.keySet().stream().sorted(Long::compareUnsigned).forEach(player->request.addEntries(RankScoreInput.newBuilder()
                .setEntityId(player).setScore(next.get(player)).setAchievedMs(stamp)));
        db.insert(c,GlobalRankOutboxRow.newBuilder().setSourceEpoch(1).setSourceCursor(cursor).setRequestId(event.getBattleId())
                .setEntries(ByteString.copyFrom(NetworkProbes.bytes(request.build()))).setPayloadDigest(ByteString.copyFrom(digest(event))).build());
        db.updateByPk(c,guard.toBuilder().setProducedCursor(cursor).build());
    }
    public void start(){timer.scheduleWithFixedDelay(()->{try{publish();}catch(Exception e){log.warn("评级投榜等待恢复，未推进源游标: {}",e.getClass().getSimpleName());}},0,2,TimeUnit.SECONDS);}
    public void publish()throws Exception{
        GlobalRankSourceRow guard;
        try(var c=source.getConnection()){guard=db.findOneByPk(c,GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build()).orElse(null);}
        if(guard==null)return;
        long board=guard.getBoardId(),committed=guard.getCommittedCursor();
        if(board==0){
            var request=OpenRankGenerationRequest.newBuilder().setBoard(RankBoardKey.newBuilder().setBoardType(1).setScope(RankScope.RANK_SCOPE_GLOBAL)
                    .setPeriod("all").setGeneration(1)).setProducer("match").setSourceEpoch(1).setRequestId(1).build();
            var response=call(s->s.openRankGeneration(sign(RankAdminService.OPEN_METHOD,request)));
            if(response.getErrorMessage().getId()!=0||response.getBoardId()==0)throw new IllegalStateException("评级榜代次打开被拒");
            board=response.getBoardId();
            try(var c=source.getConnection();var p=c.prepareStatement("UPDATE match_global_rank_source SET board_id=? WHERE source_epoch=1 AND board_id=0")){
                p.setObject(1,PbMysql.uint64(board));p.executeUpdate();}
        }
        for(int sent=0;sent<16&&committed<guard.getProducedCursor();sent++){
            GlobalRankOutboxRow out;
            try(var c=source.getConnection()){out=db.findOneByPk(c,GlobalRankOutboxRow.newBuilder().setSourceEpoch(1).setSourceCursor(committed+1).build()).orElseThrow();}
            var request=SubmitRankScoresRequest.parseFrom(out.getEntries()).toBuilder().setBoardId(board).build();
            var response=call(s->s.submitRankScores(sign(RankAdminService.SUBMIT_METHOD,request)));
            if(response.getErrorMessage().getId()!=0||response.getCommittedCursor()!=request.getSourceCursor())throw new IllegalStateException("评级榜尚未确认连续游标");
            try(var c=source.getConnection();var p=c.prepareStatement("UPDATE match_global_rank_source SET committed_cursor=? WHERE source_epoch=1 AND committed_cursor=?")){
                p.setObject(1,PbMysql.uint64(request.getSourceCursor()));p.setObject(2,PbMysql.uint64(committed));p.executeUpdate();}
            committed=request.getSourceCursor();
        }
    }
    /** 这行锁与 append 相同；回包为真实最终游标，停写后不会再产生本代 outbox。 */
    public long stopSource()throws SQLException{
        try(var c=source.getConnection()){
            c.setAutoCommit(false);
            try{var key=GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build();db.insertIgnore(c,key);
                var row=db.findOneByPkForUpdate(c,key).orElseThrow();db.updateByPk(c,row.toBuilder().setStopped(true).build());c.commit();return row.getProducedCursor();}
            catch(Exception e){c.rollback();throw e;}
        }
    }
    /** 显式封榜入口：只能提交 SQL 已停写且全部投递确认的代次。报错后重试保持 request_id/rewards 不变。 */
    public FreezeRankGenerationResponse freeze(FreezeRankGenerationRequest template)throws Exception{
        GlobalRankSourceRow guard;try(var c=source.getConnection()){guard=db.findOneByPk(c,GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build()).orElseThrow();}
        if(!guard.getStopped()||guard.getCommittedCursor()!=guard.getProducedCursor()||template.getRequestId()==0)
            throw new IllegalStateException("评级源尚未完成停写屏障或 outbox 未齐");
        var request=template.toBuilder().setBoardId(guard.getBoardId()).setGeneration(1).setSourceEpoch(1).setExpectedSourceCursor(guard.getProducedCursor()).build();
        return call(s->s.freezeRankGeneration(sign(RankAdminService.FREEZE_METHOD,request)));
    }
    private <T extends com.google.protobuf.Message>T sign(String method,T request){return ServiceCallSignatures.sign(method,request,"match",config.secret(),System.currentTimeMillis());}
    private <R>R call(Function<RankAdminService,CompletableFuture<R>> action)throws Exception{return calls.call(target,Duration.ofSeconds(3),action).get(3250,TimeUnit.MILLISECONDS);}
    @Override public void close(){timer.shutdownNow();try{timer.awaitTermination(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}calls.close();}
}
