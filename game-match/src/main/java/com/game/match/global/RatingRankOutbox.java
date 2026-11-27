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
    private final java.util.concurrent.locks.ReentrantLock publication=new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean quiesced;
    private final DataSource source;
    private final PbMysql db=new PbMysql();
    private final GlobalRankProperties config;
    private final NodeRpcClients<RankAdminService> calls;
    private final NodeRpcClients.Target target;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("match-rank-outbox").daemon(true).factory());
    private volatile com.game.match.ranked.RankedStore rankedStore;
    private volatile com.game.common.workshard.WorkShard.Membership workShard;
    private volatile com.game.common.workshard.LoopLiveness liveness;
    public RatingRankOutbox(DataSource source,GlobalRankProperties config){
        if(config.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("评级投榜必须注入 RANK_PRODUCER_SECRET_MATCH");
        this.source=source;this.config=config;db.register(GlobalRankSourceRow.getDefaultInstance());db.register(GlobalRankOutboxRow.getDefaultInstance());
        target=new NodeRpcClients.Target(config.host(),config.port(),"");
        calls=new NodeRpcClients<>("game-match-rank-outbox",RankAdminService.class,DubboGroups.RANK,Duration.ofSeconds(3),"match-rank-connect");
    }
    public RatingRankOutbox withRankedStore(com.game.match.ranked.RankedStore rankedStore){this.rankedStore=rankedStore;return this;}
    /** 挂载全服 64 片 HRW 分工成员表（评级源按 source_epoch 分工、待摘榜发件箱按 player_id % 64 分片）。 */
    public RatingRankOutbox withWorkShard(com.game.common.workshard.WorkShard.Membership membership){this.workShard=membership;return this;}
    /** 挂载循环活性跟踪器（每轮兜底 Throwable 防静默停摆，有意关闭时摘除序列）。 */
    public RatingRankOutbox withLiveness(com.game.common.workshard.LoopLiveness liveness){this.liveness=liveness;return this;}
    private static byte[] digest(BattleResultEvent event){try{return MessageDigest.getInstance("SHA-256").digest(NetworkProbes.bytes(event));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public boolean alreadyApplied(Connection c,BattleResultEvent event)throws SQLException{
        var old=db.findOne(c,GlobalRankOutboxRow.class,"request_id=?",PbMysql.uint64(event.getBattleId()));
        if(old.isEmpty())return false;
        if(!MessageDigest.isEqual(old.get().getPayloadDigest().toByteArray(),digest(event)))throw new SQLException("同对局重复评分内容不同");
        return true;
    }
    /** 论剑排位赛季榜使用的独立 source_epoch 基址（与存量 epoch=1 永久榜隔离）。 */
    public static final long RANKED_SOURCE_EPOCH_BASE = 10_000L;

    /** 账号注销待摘评级榜事务发件箱表（与评分/排位行同事务写入，投榜循环异步调 RemoveRankEntry 摘除并立墓碑）。 */
    public static final String CREATE_RANK_REMOVAL_SQL =
            "CREATE TABLE IF NOT EXISTS match_rank_removal ("
                    + "source_epoch BIGINT UNSIGNED NOT NULL, "
                    + "player_id BIGINT UNSIGNED NOT NULL, "
                    + "created_ms BIGINT UNSIGNED NOT NULL, "
                    + "PRIMARY KEY (source_epoch, player_id))";

    public static void ensureRankRemovalTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(CREATE_RANK_REMOVAL_SQL);
        }
    }

    /**
     * 在删角事务内（删除 match_rating 与 match_ranked_player_season 之前）登记待摘评级榜记录：
     * 有普通评分行则登记 source_epoch=1，有论剑赛季行则逐季登记 {@link #rankedSourceEpoch}。
     */
    public static int queueRankRemovalsInTx(Connection c, long playerId, long nowMs) throws SQLException {
        if (playerId == 0L) {
            return 0;
        }
        List<Long> epochs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM match_rating WHERE player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    epochs.add(1L);
                }
            }
        } catch (SQLException e) {
            if (!isMissingTable(e)) {
                throw e;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id FROM match_ranked_player_season WHERE player_id=? ORDER BY season_id")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt(1);
                    if (sid > 0) {
                        epochs.add(rankedSourceEpoch(sid));
                    }
                }
            }
        } catch (SQLException e) {
            if (!isMissingTable(e)) {
                throw e;
            }
        }
        if (epochs.isEmpty()) {
            return 0;
        }
        int queued = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO match_rank_removal (source_epoch, player_id, created_ms) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE created_ms = created_ms")) {
            for (long epoch : epochs) {
                ps.setObject(1, PbMysql.uint64(epoch));
                ps.setObject(2, PbMysql.uint64(playerId));
                ps.setObject(3, PbMysql.uint64(nowMs));
                ps.executeUpdate();
                queued++;
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return 0;
            }
            throw e;
        }
        return queued;
    }

    private static boolean isMissingTable(SQLException e) {
        String state = e.getSQLState();
        return "42S02".equals(state) || "42102".equals(state);
    }

    public static long rankedSourceEpoch(int seasonId) {
        return RANKED_SOURCE_EPOCH_BASE + Integer.toUnsignedLong(seasonId);
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

    /** 论剑排位赛季投榜：仅写入本季已亮段（qualified=true）玩家，未参赛回归号绝不上新季榜。 */
    @Override public void appendRanked(Connection c,BattleResultEvent event,
                                       com.game.match.ranked.RankedStore.SeasonCommitResult commit,long stamp)throws SQLException {
        if (commit == null || commit.qualifiedScores().isEmpty()) {
            return;
        }
        long epoch = rankedSourceEpoch(commit.seasonId());
        var key = GlobalRankSourceRow.newBuilder().setSourceEpoch(epoch).build();
        db.insertIgnore(c, key);
        var guard = db.findOneByPkForUpdate(c, key).orElseThrow();
        if (guard.getStopped()) {
            throw new SQLException("排位赛季榜来源代次已停止 season=" + commit.seasonId());
        }
        long cursor = guard.getProducedCursor() + 1;
        var request = SubmitRankScoresRequest.newBuilder()
                .setGeneration(commit.rankGeneration())
                .setSourceEpoch(epoch)
                .setSourceCursor(cursor)
                .setRequestId(event.getBattleId())
                .setMode(RankSubmitMode.RANK_SUBMIT_SET);
        commit.qualifiedScores().keySet().stream().sorted(Long::compareUnsigned).forEach(player ->
                request.addEntries(RankScoreInput.newBuilder()
                        .setEntityId(player)
                        .setScore(commit.qualifiedScores().get(player))
                        .setAchievedMs(stamp)));
        db.insert(c, GlobalRankOutboxRow.newBuilder()
                .setSourceEpoch(epoch)
                .setSourceCursor(cursor)
                .setRequestId(event.getBattleId())
                .setEntries(ByteString.copyFrom(NetworkProbes.bytes(request.build())))
                .setPayloadDigest(ByteString.copyFrom(digest(event)))
                .build());
        db.updateByPk(c, guard.toBuilder().setProducedCursor(cursor).build());
    }
    public void start(){timer.scheduleWithFixedDelay(()->{
        Runnable round = () -> {try{publish();}catch(Exception e){log.warn("评级投榜等待恢复，未推进源游标: {}",e.getClass().getSimpleName());}};
        var live = this.liveness;
        if (live != null) { live.runGuarded(round); }
        else { try { round.run(); } catch (Throwable panic) { log.error("评级投榜循环单轮异常（循环继续）", panic); } }
    },0,2,TimeUnit.SECONDS);}
    public void publish()throws Exception{if(quiesced||!publication.tryLock())return;try{publishOwned();}finally{publication.unlock();}}
    private boolean ownsRankSource(long epoch) {
        var m = this.workShard;
        return m == null || m.owns("rank-source:" + Long.toUnsignedString(epoch));
    }
    private void publishOwned()throws Exception{
        if (ownsRankSource(1L)) {
            GlobalRankSourceRow guard;
            try(var c=source.getConnection()){guard=db.findOneByPk(c,GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build()).orElse(null);}
            if(guard!=null){
                long board=guard.getBoardId(),committed=guard.getCommittedCursor();
                if(board==0){
                    var request=OpenRankGenerationRequest.newBuilder().setBoard(RankBoardKey.newBuilder().setBoardType(1).setScope(RankScope.RANK_SCOPE_GLOBAL)
                            .setPeriod("all").setGeneration(1)).setProducer("match").setSourceEpoch(1).setRequestId(1).build();
                    var response=call(s->s.openRankGeneration(sign(RankAdminService.OPEN_METHOD,request)));
                    if(response.getErrorMessage().getId()!=0||response.getBoardId()==0)throw new IllegalStateException("评级榜代次打开被拒");
                    board=response.getBoardId();
                    mutate("UPDATE match_global_rank_source SET board_id=? WHERE source_epoch=1 AND board_id=0",board);
                }
                for(int sent=0;sent<16&&committed<guard.getProducedCursor();sent++){
                    GlobalRankOutboxRow out;
                    try(var c=source.getConnection()){out=db.findOneByPk(c,GlobalRankOutboxRow.newBuilder().setSourceEpoch(1).setSourceCursor(committed+1).build()).orElseThrow();}
                    var request=SubmitRankScoresRequest.parseFrom(out.getEntries()).toBuilder().setBoardId(board).build();
                    var response=call(s->s.submitRankScores(sign(RankAdminService.SUBMIT_METHOD,request)));
                    if(response.getErrorMessage().getId()!=0||response.getCommittedCursor()!=request.getSourceCursor())throw new IllegalStateException("评级榜尚未确认连续游标");
                    mutate("UPDATE match_global_rank_source SET committed_cursor=? WHERE source_epoch=1 AND committed_cursor=?",request.getSourceCursor(),committed);
                    committed=request.getSourceCursor();
                }
            }
        }
        publishPendingRankedSeasons();
        publishPendingRankRemovals();
    }

    /** 扫描 match_rank_removal 待摘榜行并向 game-rank 投递 RemoveRankEntry（摘除并立墓碑），成功后删发件箱行。 */
    void publishPendingRankRemovals() throws Exception {
        publishPendingRankRemovals(req -> {
            try {
                return call(s -> s.removeRankEntry(sign(RankAdminService.REMOVE_METHOD, req)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 同上，允许测试注入 RemoveRankEntry 发送器而不依赖 Dubbo。按 player_id % 64 分片，分片条件置于 LIMIT 之前。 */
    public int publishPendingRankRemovals(Function<RemoveRankEntryRequest, RankAdminResponse> sender) throws SQLException {
        var m = this.workShard;
        var filter = m == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : m.sqlFilter("match_rank_removal", "player_id");
        if (filter.isNone()) {
            return 0;
        }
        String querySql = filter.isAll()
                ? "SELECT source_epoch, player_id FROM match_rank_removal ORDER BY created_ms ASC, source_epoch ASC, player_id ASC LIMIT 16"
                : "SELECT source_epoch, player_id FROM match_rank_removal WHERE " + filter.sql()
                        + " ORDER BY created_ms ASC, source_epoch ASC, player_id ASC LIMIT 16";
        List<long[]> rows = new ArrayList<>();
        try (var c = source.getConnection();
             var ps = c.prepareStatement(querySql)) {
            ps.setQueryTimeout(3);
            if (!filter.isAll()) {
                for (int i = 0; i < filter.args().size(); i++) {
                    ps.setObject(i + 1, filter.args().get(i));
                }
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new long[]{
                            Long.parseUnsignedLong(rs.getString(1)),
                            Long.parseUnsignedLong(rs.getString(2))
                    });
                }
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return 0;
            }
            throw e;
        }
        int processed = 0;
        for (long[] row : rows) {
            long epoch = row[0];
            long playerId = row[1];
            GlobalRankSourceRow guard;
            try (var c = source.getConnection()) {
                guard = db.findOneByPk(c, GlobalRankSourceRow.newBuilder().setSourceEpoch(epoch).build()).orElse(null);
            }
            if (guard != null && guard.getBoardId() != 0L) {
                long generation = 1L;
                if (Long.compareUnsigned(epoch, RANKED_SOURCE_EPOCH_BASE) > 0 && rankedStore != null) {
                    int seasonId = (int) (epoch - RANKED_SOURCE_EPOCH_BASE);
                    var season = rankedStore.findSeason(seasonId).orElse(null);
                    if (season != null && season.getRankGeneration() > 0L) {
                        generation = season.getRankGeneration();
                    }
                }
                long reqId = deterministicRemovalRequestId(epoch, playerId);
                RemoveRankEntryRequest req = RemoveRankEntryRequest.newBuilder()
                        .setBoardId(guard.getBoardId())
                        .setGeneration(generation)
                        .setEntityId(playerId)
                        .setRequestId(reqId)
                        .build();
                RankAdminResponse resp = sender.apply(req);
                if (resp == null || resp.getErrorMessage().getId() != 0) {
                    throw new IllegalStateException("评级榜摘除被拒 epoch=" + Long.toUnsignedString(epoch));
                }
            }
            mutate("DELETE FROM match_rank_removal WHERE source_epoch=? AND player_id=?", epoch, playerId);
            processed++;
        }
        return processed;
    }

    private static long deterministicRemovalRequestId(long epoch, long playerId) {
        long mixed = (epoch * 0x9E3779B97F4A7C15L) ^ Long.rotateLeft(playerId, 17) ^ 0x52454D4F5645L;
        return mixed == 0L ? 1L : mixed;
    }
    /** 扫描所有未追平游标的排位赛季来源代次（source_epoch >= 10000）并按赛季投递至 game-rank。 */
    private void publishPendingRankedSeasons()throws Exception{
        var rs=rankedStore;
        if(rs==null)return;
        List<Long> epochs=new ArrayList<>();
        try(var c=source.getConnection();
            var ps=c.prepareStatement("SELECT source_epoch FROM match_global_rank_source WHERE source_epoch>=? AND committed_cursor<produced_cursor ORDER BY source_epoch LIMIT 16")){
            ps.setQueryTimeout(3);
            ps.setObject(1,PbMysql.uint64(RANKED_SOURCE_EPOCH_BASE));
            try(var rows=ps.executeQuery()){
                while(rows.next())epochs.add(Long.parseUnsignedLong(rows.getString(1)));
            }
        }
        for(long epoch:epochs){
            if(Long.compareUnsigned(epoch,RANKED_SOURCE_EPOCH_BASE)<=0)continue;
            if(!ownsRankSource(epoch))continue;
            int seasonId=(int)(epoch-RANKED_SOURCE_EPOCH_BASE);
            var season=rs.findSeason(seasonId).orElse(null);
            if(season==null)continue;
            publishRankedSeason(seasonId,season.getSeasonKey(),season.getRankGeneration());
        }
    }
    private void mutate(String sql,long...values)throws SQLException {
        try(var c=source.getConnection()){
            c.setAutoCommit(false);try{
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);
                try(var p=c.prepareStatement(sql)){p.setQueryTimeout(3);for(int i=0;i<values.length;i++)p.setObject(i+1,PbMysql.uint64(values[i]));p.executeUpdate();}c.commit();
            }catch(SQLException|RuntimeException failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }
    }
    /** 停止指定排位赛季的排行榜源写屏障并返回最终 produced_cursor。 */
    public long stopRankedSeasonSource(int seasonId) throws SQLException {
        long epoch = rankedSourceEpoch(seasonId);
        try (var c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                com.game.common.player.PlayerRouteAuthority.requireMeta(c, 3, true);
                var key = GlobalRankSourceRow.newBuilder().setSourceEpoch(epoch).build();
                db.insertIgnore(c, key);
                var row = db.findOneByPkForUpdate(c, key).orElseThrow();
                db.updateByPk(c, row.toBuilder().setStopped(true).build());
                c.commit();
                return row.getProducedCursor();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 推送指定论剑赛季的全部待发排位榜 outbox 批次（含首开赛季榜代次）。 */
    public void publishRankedSeason(int seasonId, String seasonKey, long rankGeneration) throws Exception {
        long epoch = rankedSourceEpoch(seasonId);
        GlobalRankSourceRow guard;
        try (var c = source.getConnection()) {
            guard = db.findOneByPk(c, GlobalRankSourceRow.newBuilder().setSourceEpoch(epoch).build()).orElse(null);
        }
        if (guard == null) {
            return;
        }
        long board = guard.getBoardId(), committed = guard.getCommittedCursor();
        if (board == 0) {
            var request = OpenRankGenerationRequest.newBuilder()
                    .setBoard(RankBoardKey.newBuilder()
                            .setBoardType(RankBoardType.RANK_BOARD_TYPE_MATCH_PVP_TEAM_VALUE)
                            .setScope(RankScope.RANK_SCOPE_GLOBAL)
                            .setPeriod(seasonKey)
                            .setGeneration(rankGeneration))
                    .setProducer("match")
                    .setSourceEpoch(epoch)
                    .setRequestId(epoch)
                    .build();
            var response = call(s -> s.openRankGeneration(sign(RankAdminService.OPEN_METHOD, request)));
            if (response.getErrorMessage().getId() != 0 || response.getBoardId() == 0) {
                throw new IllegalStateException("排位赛季榜代次打开被拒 season=" + seasonId);
            }
            board = response.getBoardId();
            mutate("UPDATE match_global_rank_source SET board_id=? WHERE source_epoch=? AND board_id=0", board, epoch);
        }
        for (int sent = 0; sent < 64 && committed < guard.getProducedCursor(); sent++) {
            GlobalRankOutboxRow out;
            try (var c = source.getConnection()) {
                out = db.findOneByPk(c, GlobalRankOutboxRow.newBuilder()
                        .setSourceEpoch(epoch).setSourceCursor(committed + 1).build()).orElseThrow();
            }
            var request = SubmitRankScoresRequest.parseFrom(out.getEntries()).toBuilder().setBoardId(board).build();
            var response = call(s -> s.submitRankScores(sign(RankAdminService.SUBMIT_METHOD, request)));
            if (response.getErrorMessage().getId() != 0 || response.getCommittedCursor() != request.getSourceCursor()) {
                throw new IllegalStateException("排位赛季榜尚未确认连续游标 season=" + seasonId);
            }
            mutate("UPDATE match_global_rank_source SET committed_cursor=? WHERE source_epoch=? AND committed_cursor=?",
                    request.getSourceCursor(), epoch, committed);
            committed = request.getSourceCursor();
        }
    }

    /** 显式封榜指定排位赛季（先确保停源与全部 outbox 已确认）。 */
    public FreezeRankGenerationResponse freezeRankedSeason(int seasonId, String seasonKey, long rankGeneration,
                                                           FreezeRankGenerationRequest template) throws Exception {
        long epoch = rankedSourceEpoch(seasonId);
        publishRankedSeason(seasonId, seasonKey, rankGeneration);
        GlobalRankSourceRow guard;
        try (var c = source.getConnection()) {
            guard = db.findOneByPk(c, GlobalRankSourceRow.newBuilder().setSourceEpoch(epoch).build()).orElseThrow();
        }
        if (!guard.getStopped() || guard.getCommittedCursor() != guard.getProducedCursor() || template.getRequestId() == 0) {
            throw new IllegalStateException("排位赛季榜源尚未停写或 outbox 未齐 season=" + seasonId);
        }
        var request = template.toBuilder()
                .setBoardId(guard.getBoardId())
                .setGeneration(rankGeneration)
                .setSourceEpoch(epoch)
                .setExpectedSourceCursor(guard.getProducedCursor())
                .build();
        return call(s -> s.freezeRankGeneration(sign(RankAdminService.FREEZE_METHOD, request)));
    }

    /** 这行锁与 append 相同；回包为真实最终游标，停写后不会再产生本代 outbox。 */
    public long stopSource()throws SQLException{
        try(var c=source.getConnection()){
            c.setAutoCommit(false);
            try{com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);var key=GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build();db.insertIgnore(c,key);
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
    /** 必须先停源，再等完整连续ACK游标；未知发布不推进committed，也不关闭relay。 */
    public boolean stopAndReport(long timeoutNanos)throws Exception {
        long until=System.nanoTime()+Math.max(0,timeoutNanos);
        if(!publication.tryLock(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
        try{
            try(var c=source.getConnection()){
                var sourceRow=db.findOneByPk(c,GlobalRankSourceRow.newBuilder().setSourceEpoch(1).build()).orElse(null);
                if(sourceRow!=null&&(!sourceRow.getStopped()||sourceRow.getCommittedCursor()!=sourceRow.getProducedCursor()))return false;
            }
            quiesced=true;timer.shutdown();
        }finally{publication.unlock();}
        if(!timer.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
        calls.close();return true;
    }
    @Override public void close(){if(liveness!=null){liveness.close();}timer.shutdownNow();try{timer.awaitTermination(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}calls.close();}
}
