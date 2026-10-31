package com.game.match.global;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerSessionAuthority;
import com.game.api.proto.SessionContext;
import com.game.match.MatchProperties;
import com.game.match.global.pb.*;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.ByteString;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** TiDB是票据、邀请、成局和原请求的唯一权威。提交先unsigned全员P，再领域原件/票；finish先P再F及原票，单独工作租约只锁F。 */
public final class GlobalMatchStore {
    /**
     * 原deadline仍约束运行；只给终局提交留固定60秒，随后才可竞争永久ABORT。与 scene FIGHTING 冻结的到期判据共用
     * {@link com.game.api.match.MatchBudgets#TERMINAL_OUTCOME_GRACE_MS} 这一个常量（改它要连带核对 scene 的冻结与锁 TTL）。
     */
    static final long TERMINAL_OUTCOME_GRACE_MS = com.game.api.match.MatchBudgets.TERMINAL_OUTCOME_GRACE_MS;
    private final java.util.concurrent.locks.ReentrantReadWriteLock publication=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final java.util.concurrent.atomic.AtomicLong rootAccepted=new java.util.concurrent.atomic.AtomicLong(),rootSettled=new java.util.concurrent.atomic.AtomicLong();
    private final ThreadLocal<long[]> rootWork=new ThreadLocal<>();
    private volatile boolean rootsClosed,preparedOnly;
    private volatile String maintenanceOperation="";
    public void prepareOnly(){publication.writeLock().lock();try{if(!maintenanceOperation.isEmpty()||rootAccepted.get()!=0)throw new IllegalStateException("旧root不能成为新prepared boot");rootsClosed=true;preparedOnly=true;}finally{publication.writeLock().unlock();}}
    public void openPrepared(){publication.writeLock().lock();try{if(!preparedOnly||!maintenanceOperation.isEmpty()||rootAccepted.get()!=0||rootSettled.get()!=0)throw new IllegalStateException("只有全新prepared boot可一次开放");rootsClosed=false;preparedOnly=false;}finally{publication.writeLock().unlock();}}
    public javax.sql.DataSource maintenanceDataSource(){return source;}
    public void closeRoots(String run){
        if(!UUID.fromString(run).toString().equals(run))throw new IllegalArgumentException("维护run必须为规范UUID");
        publication.writeLock().lock();try{if(!maintenanceOperation.isEmpty()&&!maintenanceOperation.equals(run))throw new IllegalStateException("不能替换原维护run");maintenanceOperation=run;rootsClosed=true;}finally{publication.writeLock().unlock();}
    }
    void requireNewRoot(){if(rootsClosed)throw new java.util.concurrent.RejectedExecutionException("Match维护永久拒绝新root，已存在原请求仍由relay续跑");var work=rootWork.get();if(work==null)throw new IllegalStateException("新root不在同publication事务内");work[0]=Math.addExact(work[0],1);rootAccepted.incrementAndGet();}
    public boolean rootsClosed(){return rootsClosed;}
    /** 观战与匹配共用同一物理boot的publication切口，持锁直到提交/回滚完成。 */
    <T>T watchPublication(java.util.function.Supplier<T> transaction){
        publication.readLock().lock();boolean outer=rootWork.get()==null;if(outer)rootWork.set(new long[1]);
        try{T result=transaction.get();if(outer)rootSettled.addAndGet(rootWork.get()[0]);return result;}
        finally{if(outer)rootWork.remove();publication.readLock().unlock();}
    }
    public boolean rootsSettled(){return rootsClosed&&rootAccepted.get()==rootSettled.get();}
    public byte[] rootsFrontier(){if(!rootsSettled())throw new IllegalStateException("新root事务仍有未确认结局");return publicationFrontier();}
    public byte[] publicationFrontier(){try{return java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.ByteBuffer.allocate(16).putLong(rootAccepted.get()).putLong(rootSettled.get()).array());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private void rootRollbackConfirmed(){var work=rootWork.get();if(work!=null){rootSettled.addAndGet(work[0]);work[0]=0;}}
    public long maintenanceOutstanding(){return tx(c->{
        long total=0;
        for(String query:List.of("SELECT COUNT(*) FROM match_global_ticket WHERE state<>4",
                "SELECT COUNT(*) FROM match_global_queue_unit WHERE state<>4",
                "SELECT COUNT(*) FROM match_global_join_intent WHERE admission_prepared=1 AND admission_completed=0 AND cancelled=0",
                "SELECT COUNT(*) FROM match_global_direct_intent WHERE admission_prepared=1 AND admission_completed=0 AND cancelled=0",
                "SELECT COUNT(*) FROM match_global_formation f LEFT JOIN match_battle_completion t ON t.battle_id=f.battle_id WHERE f.state<4 OR (f.state=4 AND (t.battle_id IS NULL OR t.terminal_decision=0))",
                "SELECT COUNT(*) FROM match_global_challenge WHERE state NOT IN(2,3)",
                "SELECT COUNT(*) FROM match_global_watching WHERE state<>5")){
            try(var s=c.prepareStatement(query)){s.setQueryTimeout(3);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("排空计数缺失");total=Math.addExact(total,r.getLong(1));}}
        }
        return total;
    });}
    private final DataSource source;
    private final PbMysql db = registry();
    /** fresh-schema由独立schema主体调用，普通构造器仍只核已有结构。 */
    public static PbMysql registry(){var db=new PbMysql();
        db.register(GlobalProfileRow.getDefaultInstance());
        db.register(GlobalTicketRow.getDefaultInstance());
        db.register(GlobalJoinIntentRow.getDefaultInstance());
        db.register(GlobalDirectIntentRow.getDefaultInstance());
        db.register(GlobalFormationRow.getDefaultInstance());
        db.register(com.game.match.completion.pb.BattleCompletionRow.getDefaultInstance());
        db.register(com.game.match.completion.pb.BattlePlayerCompletionRow.getDefaultInstance());
        db.register(GlobalRankSourceRow.getDefaultInstance());
        db.register(GlobalRankOutboxRow.getDefaultInstance());
        db.register(GlobalTicketRegionRow.getDefaultInstance());
        db.register(GlobalPolicyRow.getDefaultInstance());
        db.register(GlobalChallengeRow.getDefaultInstance());
        db.register(GlobalWatchingRow.getDefaultInstance());
        db.register(GlobalWatchRequestRow.getDefaultInstance());
        db.register(GlobalWatchHeadRow.getDefaultInstance());
        return db;
    }
    private final GlobalMatchProperties config;
    private final MatchProperties.Rating rating;
    private final GlobalRegionIndex regions = new GlobalRegionIndex(db);
    private final GlobalQueueUnits units;
    private final com.game.common.team.TeamPolicyAuthority.Contract teamPolicy;

    public GlobalMatchStore(DataSource source, GlobalMatchProperties config) throws SQLException {
        this(source,config,new MatchProperties(null,null,null,null,null,null,null,null,null,null,null,null,null));
    }
    public GlobalMatchStore(DataSource source,GlobalMatchProperties config,MatchProperties properties)throws SQLException {
        this.source = source;
        this.config = config;
        this.rating=properties.rating();
        try(var c=source.getConnection()){this.teamPolicy=com.game.common.team.TeamPolicyAuthority.load(c,3);}
        this.units=new GlobalQueueUnits(db,config,teamPolicy.allowCrossZone());
        try (Connection c = source.getConnection()) {
            if (config.requireTiDb()) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT @@version,@@tidb_read_consistency,@@tidb_replica_read,@@tidb_txn_mode,@@tidb_read_staleness,@@tidb_snapshot,@@tidb_low_resolution_tso")) {
                    if (!r.next() || !r.getString(1).toLowerCase(Locale.ROOT).contains("tidb") || !r.getString(2).equals("strict")
                            || !r.getString(3).equals("leader") || !r.getString(4).equals("pessimistic") || r.getLong(5) != 0
                            || !r.getString(6).isEmpty() || r.getBoolean(7)) throw new SQLException("全球匹配物理连接强读门禁失败");
                }
            }
            db.syncAll(c);
        }
        tx(c->{
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(config.probeSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            var tolerance=rating.tolerance();
            var bytes=("global-match-policy-v1|"+config.waitSeconds()+"|"+config.hardLatencyMs()+"|"+config.profileTtlSeconds()+"|"
                    +rating.enabled()+"|"+tolerance.base()+"|"+tolerance.stepSeconds()+"|"+tolerance.stepDelta()+"|"+tolerance.max()+"|"+tolerance.maxWaitSeconds()
                    +"|"+new java.util.TreeMap<>(properties.pveTeamSizeByConfigId())+"|"+properties.challengeTtl().toMillis()
                    +"|"+java.util.HexFormat.of().formatHex(teamPolicy.original()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var policy=GlobalPolicyRow.newBuilder().setPolicyId(1).setFingerprint(ByteString.copyFrom(mac.doFinal(bytes))).build();
            db.insertIgnore(c,policy);
            if(!db.findOneByPkForUpdate(c,policy).orElseThrow().getFingerprint().equals(policy.getFingerprint()))
                throw new IllegalStateException("全球匹配副本策略或探测密钥不一致，须统一停服变更");
            return null;
        });
    }

    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws Exception; }
    private <T> T tx(Work<T> work) {return tx(Deadline.after(3000),work);}
    private <T>T tx(Deadline deadline,Work<T> work){
        return watchPublication(()->transact(deadline,work));
    }
    private <T> T transact(Deadline deadline,Work<T> work) {
        if(deadline.expired())throw new Deadline.DependencyException("全球SQL事务预算到期");
        try (Connection c = source.getConnection()) {
            int previousTimeout=c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(3000,deadline.remainingMillis())));
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            boolean committing=false;
            try { com.game.common.player.PlayerRouteAuthority.requireMeta(c,(int)Math.max(1,Math.min(3,(deadline.remainingMillis()+999)/1000)),true); if(!java.util.Arrays.equals(teamPolicy.original(),com.game.common.team.TeamPolicyAuthority.load(c,3).original()))throw new SQLException("Team策略世代已改变，旧Match实例必须退出"); T result = work.apply(c); if(deadline.expired())throw new SQLTimeoutException("全球SQL提交预算到期"); committing=true;c.commit(); return result; }
            catch (Exception e) { try { c.rollback();if(!committing)rootRollbackConfirmed(); } catch (SQLException rollback) { e.addSuppressed(rollback); } throw e; }
            finally {c.setNetworkTimeout(Runnable::run,previousTimeout);}
        } catch (Exception e) { throw new Deadline.DependencyException("全球匹配 SQL 操作失败", e); }
    }
    private static long now(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")) {
            if (!r.next()) throw new SQLException("缺少数据库时间");
            return r.getLong(1);
        }
    }
    public long now() { return tx(GlobalMatchStore::now); }
    private static void owner(Connection c,long player,String account,long revision)throws SQLException {
        PlayerSessionAuthority.lock(c,player,account,revision,3);
    }
    private static void actor(Connection c,SessionContext session)throws SQLException {
        if(session==null)throw new SecurityException("缺少原会话身份");
        owner(c,session.getPlayerId(),session.getAccount(),session.getOwnerRevision());
    }
    private GlobalProbeProfile originalProfile(Connection c,long player)throws Exception {
        return GlobalProbeProfile.parseFrom(db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow().getPayload());
    }
    private void owners(Connection c,List<GlobalTicketRow> rows)throws SQLException {
        if(rows.stream().map(GlobalTicketRow::getPlayerId).distinct().count()!=rows.size())throw new IllegalArgumentException("原名单重复");
        com.game.common.player.PlayerRouteAuthority.lock(c,rows.stream().map(GlobalTicketRow::getPlayerId).toList(),3);
        for(var row:rows.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList())
            owner(c,row.getPlayerId(),row.getOwnerAccount(),row.getOwnerRevision());
    }
    public void authorize(SessionContext session){tx(c->{actor(c,session);return null;});}
    private void noWatching(Connection c,long player)throws SQLException {
        var row=db.findOneByPkForUpdate(c,GlobalWatchingRow.newBuilder().setPlayerId(player).build());
        if(row.isPresent()&&row.get().getState()!=5)throw new IllegalStateException("原观战尚无安全取消证明");
    }
    public GlobalTicketRow ticket(SessionContext session){return tx(c->{actor(c,session);return locked(c,session.getPlayerId());});}
    private GlobalTicketRow locked(Connection c, long player) throws SQLException {
        var key = GlobalTicketRow.newBuilder().setPlayerId(player).build();
        db.insertIgnore(c, key.toBuilder().setState(4).build());
        var row = db.findOneByPkForUpdate(c, key).orElseThrow();
        if(row.getState()==0&&row.getTicket().isEmpty()&&row.getRequestId().isEmpty()&&row.getProfile().isEmpty()&&row.getFormationId()==0) {
            row=row.toBuilder().setState(4).build();db.updateByPk(c,row);
        }
        if (row.getState() == 1 && row.getExpiresMs() <= now(c) && row.getQueueUnitId().isEmpty()) {
            row = row.toBuilder().setState(4).build();
            db.updateByPk(c, row);
            regions.clear(c,player);
        }
        return row;
    }
    /** 内部咨询只读；缺票返回终止视图，不能在未知原身份时补写玩家占位。 */
    public GlobalTicketRow ticket(long player) { return tx(c -> db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(player).build()).orElse(GlobalTicketRow.newBuilder().setPlayerId(player).setState(4).build())); }
    public GlobalProfileRow profile(long player) { return tx(c -> db.findOneByPk(c,
            GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow()); }
    public void plan(long player, GlobalProbeProfile profile, long expiry) {
        tx(c -> { requireNewRoot();owner(c,player,profile.getOwnerAccount(),profile.getOwnerRevision());
            db.upsert(c, GlobalProfileRow.newBuilder().setPlayerId(player).setPayload(profile.toByteString()).setExpiresMs(expiry).build()); return null; });
    }
    public void commitProfile(long player, GlobalProbeProfile profile, long expiry) {
        tx(c -> {
            requireNewRoot();owner(c,player,profile.getOwnerAccount(),profile.getOwnerRevision());
            var old = db.findOneByPkForUpdate(c, GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow();
            var before = GlobalProbeProfile.parseFrom(old.getPayload());
            if (old.getExpiresMs() <= now(c) || !before.getPlanId().equals(profile.getPlanId())
                    || !before.getSessionBinding().equals(profile.getSessionBinding()) || !before.getOwnerAccount().equals(profile.getOwnerAccount())
                    || before.getOwnerRevision()!=profile.getOwnerRevision()) throw new IllegalStateException("探测计划已被替换");
            db.updateByPk(c, old.toBuilder().setPayload(profile.toByteString()).setExpiresMs(expiry).build());
            // 续测不重置票号与等待；只更新同一会话的排队票，旧会话回执不能覆盖重连后的票。
            var ticket = db.findOneByPkForUpdate(c, GlobalTicketRow.newBuilder().setPlayerId(player).build());
            if (ticket.isPresent() && ticket.get().getState() == 1
                    && GlobalProbeProfile.parseFrom(ticket.get().getProfile()).getSessionBinding().equals(profile.getSessionBinding())) {
                var updated=ticket.get().toBuilder().setProfile(profile.toByteString()).setExpiresMs(expiry)
                        .setPreferredRegion(GlobalRegionPolicy.preferred(profile,now(c))).build();
                db.updateByPk(c,updated);regions.replace(c,updated,now(c));units.refresh(c,updated.getQueueUnitId(),now(c));
            }
            return null;
        });
    }
    public GlobalTicketRow join(long player, String request, String plan, String binding, int mode, int configId, int size, int zone, long rating) {
        return join(null,player,request,plan,binding,mode,configId,size,zone,rating);
    }
    /** 前台原UUID先回放，不能被其后形成的战斗锁或离线状态改成另一笔新请求。 */
    public GlobalJoinIntentRow existingJoin(SessionContext session,com.game.proto.match.JoinQueueRequest request){
        var key=intentKey(session.getPlayerId(),request.getRequestId());return tx(c->{actor(c,session);var row=db.findOneByPk(c,key).orElse(null);if(row==null)return null;
            if(!row.getOwnerAccount().equals(session.getAccount())||row.getOwnerRevision()!=session.getOwnerRevision()||row.getCancelled())throw new SecurityException("原Join归属不同或已取消");
            if(row.getAdmissionPrepared()&&!row.getRequestPayload().equals(ByteString.copyFrom(com.game.common.token.NetworkProbes.bytes(request))))throw new SecurityException("原Join正文不同");return row;});
    }
    public GlobalJoinIntentRow prepareJoin(SessionContext session,com.game.proto.match.JoinQueueRequest request,int size,int zone,long rating,String binding) {
        var key=intentKey(session.getPlayerId(),request.getRequestId());var payload=ByteString.copyFrom(com.game.common.token.NetworkProbes.bytes(request));
        return tx(c->{actor(c,session);var prior=db.findOneByPkForUpdate(c,key);
            if(prior.isPresent()){
                var original=prior.get();if(!original.getOwnerAccount().equals(session.getAccount())||original.getOwnerRevision()!=session.getOwnerRevision())throw new SecurityException("原Join请求属于旧归属");
                if(original.getCancelled())throw new IllegalStateException("原Join请求已取消");
                if(original.getAdmissionPrepared()&&!original.getRequestPayload().equals(payload))throw new IllegalArgumentException("原Join正文不同");
                if(!original.getAdmissionPrepared()&&original.getTicketRecord().isEmpty())throw new SQLException("旧Join原件缺少准入和原票");return original;
            }
            requireNewRoot();var previous=locked(c,session.getPlayerId());
            if(previous.getState()!=4){
                if(!previous.getOwnerAccount().equals(session.getAccount())||previous.getOwnerRevision()!=session.getOwnerRevision())throw new SecurityException("当前占用票并非原会话归属");
                var receipt=key.toBuilder().setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision()).setRequestPayload(payload).setAdmissionPrepared(true).setAdmissionCompleted(true)
                    .setTicketRecord(previous.toByteString()).setResponsePayload(com.game.match.support.MatchTips.joinAlreadyQueued(previous.getTicket()).toByteString()).build();db.insert(c,receipt);return receipt;
            }
            com.game.common.player.MatchEntryAuthority.requireNoPendingLaunches(c,List.of(session.getPlayerId()),"",0,3);
            var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(session.getPlayerId()).build()).orElseThrow();var source=GlobalProbeProfile.parseFrom(profile.getPayload());long stamp=now(c);
            if(!source.getPlanId().equals(request.getNetworkPlanId())||!source.getSessionBinding().equals(binding)||!source.getOwnerAccount().equals(session.getAccount())||source.getOwnerRevision()!=session.getOwnerRevision()||profile.getExpiresMs()<=stamp||GlobalRegionPolicy.latencies(source,stamp,config.hardLatencyMs()).isEmpty())throw new SQLException("排队准入缺少原探测");
            var role=com.game.proto.ObserverExitOriginal.newBuilder().setPlayerId(session.getPlayerId()).setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision()).build();
            var exits=com.game.common.player.ObserverExitAuthority.capture(c,List.of(role),3);
            var pending=GlobalTicketRow.newBuilder().setPlayerId(session.getPlayerId()).setTicket(UUID.randomUUID().toString()).setRequestId(request.getRequestId()).setState(0).setMode(request.getModeValue())
                .setConfigId(request.getBattleConfigId()).setTeamSize(size).setZoneId(GlobalQueueUnits.home(c,session.getPlayerId())).setRatingCenti(rating).setEnqueuedMs(stamp).setProfile(profile.getPayload()).setExpiresMs(profile.getExpiresMs())
                .setPreferredRegion(GlobalRegionPolicy.preferred(source,stamp)).setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision()).build();db.updateByPk(c,pending);
            var original=key.toBuilder().setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision()).setRequestPayload(payload).setAdmissionPrepared(true)
                .setAdmissionTicket(pending.toByteString()).setSessionBinding(binding).addAllObserverExits(exits.stream().map(com.game.proto.ObserverExitOriginal::toByteString).toList()).build();db.insert(c,original);return original;
        });
    }
    public GlobalTicketRow join(SessionContext session,String request,String plan,String binding,int mode,int configId,int size,int zone,long rating) {
        return join(session,session.getPlayerId(),request,plan,binding,mode,configId,size,zone,rating);
    }
    public com.game.proto.match.JoinQueueResponse completeJoin(SessionContext actor,String request,com.game.proto.match.JoinQueueResponse response){return tx(c->{
        actor(c,actor);var key=intentKey(actor.getPlayerId(),request);var original=db.findOneByPkForUpdate(c,key).orElseThrow();
        if(!original.getOwnerAccount().equals(actor.getAccount())||original.getOwnerRevision()!=actor.getOwnerRevision()||original.getCancelled())throw new SecurityException("原Join回复身份不同或已取消");
        if(!original.getResponsePayload().isEmpty())return com.game.proto.match.JoinQueueResponse.parseFrom(original.getResponsePayload());
        db.updateByPk(c,original.toBuilder().setResponsePayload(response.toByteString()).setAdmissionCompleted(true).build());return response;});}
    public GlobalJoinIntentRow pendingJoinUpper(){return tx(c->{var rows=db.findAll(c,GlobalJoinIntentRow.class,"admission_prepared=1 AND admission_completed=0 AND cancelled=0 ORDER BY player_id DESC,request_high DESC,request_low DESC LIMIT 1");return rows.isEmpty()?null:rows.getFirst();});}
    public List<GlobalJoinIntentRow> pendingJoins(long player,long high,long low,GlobalJoinIntentRow upper){return tx(c->db.findAll(c,GlobalJoinIntentRow.class,
        "admission_prepared=1 AND admission_completed=0 AND cancelled=0 AND (player_id>? OR (player_id=? AND (request_high>? OR (request_high=? AND request_low>?)))) AND (player_id<? OR (player_id=? AND (request_high<? OR (request_high=? AND request_low<=?)))) ORDER BY player_id,request_high,request_low LIMIT 8",
        PbMysql.uint64(player),PbMysql.uint64(player),PbMysql.uint64(high),PbMysql.uint64(high),PbMysql.uint64(low),PbMysql.uint64(upper.getPlayerId()),PbMysql.uint64(upper.getPlayerId()),PbMysql.uint64(upper.getRequestHigh()),PbMysql.uint64(upper.getRequestHigh()),PbMysql.uint64(upper.getRequestLow())));}
    /** 只有原退出正证明已齐，才可安全终结失效的pending票；绝不换新profile延续旧请求。 */
    public boolean closeObsoleteJoin(GlobalJoinIntentRow original,Deadline deadline){return tx(deadline,c->{
        owner(c,original.getPlayerId(),original.getOwnerAccount(),original.getOwnerRevision());var current=db.findOneByPkForUpdate(c,original).orElseThrow();
        if(!current.getAdmissionTicket().equals(original.getAdmissionTicket())||!current.getRequestPayload().equals(original.getRequestPayload()))throw new SQLException("原Join准入原件已变");
        if(current.getAdmissionCompleted())return current.getCancelled();
        var pending=GlobalTicketRow.parseFrom(current.getAdmissionTicket());var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(current.getPlayerId()).build());
        boolean obsolete=current.getCancelled()||pending.getExpiresMs()<=now(c)||profile.isEmpty()||profile.get().getExpiresMs()<=now(c)||!profile.get().getPayload().equals(pending.getProfile());
        if(!obsolete)return false;
        for(var bytes:current.getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,com.game.proto.ObserverExitOriginal.parseFrom(bytes),3))throw new SQLException("失效Join仍缺退出正证明");
        var ticket=locked(c,current.getPlayerId());if(ticket.equals(pending))db.updateByPk(c,ticket.toBuilder().setState(4).build());else if(!current.getCancelled())throw new SQLException("失效Join原pending票已被替换");
        db.updateByPk(c,current.toBuilder().setCancelled(true).setAdmissionCompleted(true).setResponsePayload(com.game.match.support.MatchTips.joinRejected(com.game.match.support.MatchTip.BUSY).toByteString()).build());return true;
    });}
    private GlobalTicketRow join(SessionContext session,long player, String request, String plan, String binding, int mode, int configId, int size, int zone, long rating) {
        var intentKey = intentKey(player, request); // 非法 UUID 在获取 SQL 连接前明确拒绝。
        return tx(c -> {
            var identity=originalProfile(c,player);
            if(session!=null){actor(c,session);if(!identity.getOwnerAccount().equals(session.getAccount())||identity.getOwnerRevision()!=session.getOwnerRevision())throw new SecurityException("原探测身份已经失效");}
            else owner(c,player,identity.getOwnerAccount(),identity.getOwnerRevision());
            var old = locked(c, player);
            var intent = db.findOneByPk(c, intentKey);
            if (intent.isPresent()) {
                if(!intent.get().getOwnerAccount().equals(identity.getOwnerAccount())||intent.get().getOwnerRevision()!=identity.getOwnerRevision())
                    throw new SecurityException("旧归属的排队请求原件不能重放");
                if (intent.get().getCancelled()) throw new IllegalArgumentException("匹配请求已经撤销");
                if(!intent.get().getTicketRecord().isEmpty()){
                var replayed = GlobalTicketRow.parseFrom(intent.get().getTicketRecord());
                if (replayed.getTicket().isEmpty() || replayed.getMode() != mode || replayed.getConfigId() != configId || replayed.getTeamSize() != size)
                    throw new IllegalArgumentException("相同请求号内容不同或原票损坏");
                if(!replayed.getOwnerAccount().equals(identity.getOwnerAccount())||replayed.getOwnerRevision()!=identity.getOwnerRevision())
                    throw new SecurityException("旧归属的排队请求不能在新归属重放");
                return old.getTicket().equals(replayed.getTicket()) ? old : replayed.toBuilder().setState(4).build();
                }
                if(!intent.get().getAdmissionPrepared())throw new SQLException("排队原意图未准备且缺原票");
            }
            GlobalTicketRow staged=intent.isPresent()&&intent.get().getAdmissionPrepared()&&!intent.get().getAdmissionCompleted()&&!intent.get().getAdmissionTicket().isEmpty()?GlobalTicketRow.parseFrom(intent.get().getAdmissionTicket()):null;
            if(staged!=null&&(!staged.equals(old)||old.getState()!=0||!staged.getOwnerAccount().equals(identity.getOwnerAccount())||staged.getOwnerRevision()!=identity.getOwnerRevision()))throw new SecurityException("原Join pending票被替换");
            if (staged==null&&old.getRequestId().equals(request)) {
                if(!old.getOwnerAccount().equals(identity.getOwnerAccount())||old.getOwnerRevision()!=identity.getOwnerRevision())throw new SecurityException("旧归属排队票不能补为新原件");
                if (old.getMode() != mode || old.getConfigId() != configId || old.getTeamSize() != size) throw new IllegalArgumentException("相同请求号内容不同");
                db.upsert(c, intent.orElse(intentKey).toBuilder().setOwnerAccount(identity.getOwnerAccount()).setOwnerRevision(identity.getOwnerRevision()).setTicketRecord(old.toByteString()).build());
                return old;
            }
            if (staged==null&&old.getState()!=4) return old;
            noWatching(c,player);
            com.game.common.player.MatchEntryAuthority.requireNoPendingLaunches(c,List.of(player),"",0,3);
            if(staged!=null)for(var bytes:intent.orElseThrow().getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,com.game.proto.ObserverExitOriginal.parseFrom(bytes),3))throw new SQLException("原Join退出仍无永久取消证明");
            var profile = db.findOneByPk(c, GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow();
            var p = GlobalProbeProfile.parseFrom(profile.getPayload());
            long stamp = now(c);
            if (!p.getPlanId().equals(plan) || !p.getSessionBinding().equals(binding) || profile.getExpiresMs() <= stamp
                    || GlobalRegionPolicy.latencies(p, stamp, config.hardLatencyMs()).isEmpty()) throw new IllegalArgumentException("缺少可信 RTT");
            if(staged!=null&&!staged.getProfile().equals(profile.getPayload()))throw new SQLException("原Join探测原件被替换，不能补新profile");
            var row = staged!=null?staged.toBuilder().setState(1).build():GlobalTicketRow.newBuilder().setPlayerId(player).setTicket(UUID.randomUUID().toString()).setRequestId(request)
                    .setMode(mode).setConfigId(configId).setTeamSize(size).setState(1).setEnqueuedMs(stamp).setZoneId(GlobalQueueUnits.home(c,player))
                    .setRatingCenti(rating).setProfile(profile.getPayload()).setExpiresMs(profile.getExpiresMs())
                    .setPreferredRegion(GlobalRegionPolicy.preferred(p,stamp)).setOwnerAccount(p.getOwnerAccount()).setOwnerRevision(p.getOwnerRevision()).build();
            if(mode==com.game.proto.match.MatchMode.MATCH_MODE_PVE_TEAM_VALUE)row=row.toBuilder().setQueueUnitId(GlobalQueueUnits.soloId(row)).build();
            db.updateByPk(c, row);
            regions.replace(c,row,stamp);
            if(!row.getQueueUnitId().isEmpty())units.create(c,List.of(row),ByteString.EMPTY,stamp);
            db.upsert(c, intent.orElse(intentKey).toBuilder().setOwnerAccount(identity.getOwnerAccount()).setOwnerRevision(identity.getOwnerRevision()).setTicketRecord(row.toByteString()).setAdmissionCompleted(true).build());
            return row;
        });
    }
    public boolean cancel(long player, String ticket) {
        return cancel(player, ticket, "");
    }
    /** 与 Join 先争同一玩家行锁；即使 Join 尚未落票，撤销也留下不可过期的 SQL 墓碑。 */
    public boolean cancel(long player, String ticket, String request) {
        return cancel(null,player,ticket,request);
    }
    public boolean cancel(SessionContext session,String ticket,String request) {return cancel(session,session.getPlayerId(),ticket,request);}
    private boolean cancel(SessionContext session,long player,String ticket,String request) {
        if (player == 0) return false;
        if (!request.isEmpty()) intentKey(player,request);
        if (request.isEmpty() && (ticket == null || ticket.isEmpty())) throw new IllegalArgumentException("撤销需要原请求编号或票据");
        var seen=tx(c->db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(player).build()).orElse(null));
        if(seen!=null&&!seen.getQueueUnitId().isEmpty()&&((!request.isEmpty()&&request.equals(seen.getRequestId()))||(ticket!=null&&!ticket.isEmpty()&&ticket.equals(seen.getTicket()))))
            return cancelUnit(session,seen.getQueueUnitId());
        return tx(c -> {
            String account;long revision;
            if(session!=null){actor(c,session);account=session.getAccount();revision=session.getOwnerRevision();}
            else {var p=originalProfile(c,player);owner(c,player,p.getOwnerAccount(),p.getOwnerRevision());account=p.getOwnerAccount();revision=p.getOwnerRevision();}
            var old = locked(c, player);
            String target = request.isEmpty() && old.getTicket().equals(ticket) ? old.getRequestId() : request;
            if (!target.isEmpty()) {
                var key = intentKey(player, target);
                var original=db.findOneByPk(c,key);
                var previous = original.orElse(key.toBuilder().setOwnerAccount(account).setOwnerRevision(revision).build());
                if(!previous.getOwnerAccount().equals(account)||previous.getOwnerRevision()!=revision)throw new SecurityException("不能覆盖旧归属的排队撤销原件");
                db.upsert(c, previous.toBuilder().setCancelled(true).build());
            }
            if ((old.getState()!=1&&old.getState()!=0) || old.getState()==0&&old.getRequestId().isEmpty() || (!target.isEmpty() && !old.getRequestId().equals(target))
                    || (ticket != null && !ticket.isEmpty() && !old.getTicket().equals(ticket))) return false;
            db.updateByPk(c, old.toBuilder().setState(4).build());regions.clear(c,player); return true;
        });
    }
    public boolean cancelUnit(SessionContext session,String id) {
        var seen=tx(c->units.find(c,id,false));if(seen==null)return false;
        return tx(c->{
            if(session!=null&&seen.getOriginalTicketsList().stream().noneMatch(t->t.getPlayerId()==session.getPlayerId()&&t.getOwnerAccount().equals(session.getAccount())&&t.getOwnerRevision()==session.getOwnerRevision()))throw new SecurityException("撤队原角色不在原单位");
            if(seen.getState()==4){if(session!=null)actor(c,session);return true;}
            owners(c,seen.getOriginalTicketsList());
            if(!seen.getLaunchOriginal().isEmpty()){
                GlobalQueueUnits.lockTeams(c,List.of(seen));var launch=GlobalTeamLaunchAuthority.original(seen.getLaunchOriginal());
                GlobalTeamLaunchAuthority.lock(c,launch,seen.getLaunchOriginal(),seen.getOriginalTicketsList());
                return com.game.common.player.TeamQueueAuthority.cancel(c,launch,seen.getLaunchOriginal().toByteArray(),3)==com.game.common.player.TeamQueueAuthority.Result.CANCELLED;
            }
            var unit=units.find(c,id,true);if(unit==null||unit.getState()!=1)return false;
            if(unit.getOriginalTicketsCount()!=1)throw new SQLException("无Team原件的单位不得含多人");
            var original=unit.getOriginalTickets(0);var row=locked(c,original.getPlayerId());
            if(!row.getQueueUnitId().equals(id)||!row.getTicket().equals(original.getTicket())||row.getState()!=1)throw new SQLException("原单排单位票据已改变");
            var key=intentKey(row.getPlayerId(),row.getRequestId());var receipt=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!receipt.getOwnerAccount().equals(row.getOwnerAccount())||receipt.getOwnerRevision()!=row.getOwnerRevision())throw new SecurityException("原单排撤销回执身份不同");
            db.updateByPk(c,receipt.toBuilder().setCancelled(true).build());db.updateByPk(c,row.toBuilder().setState(4).build());regions.clear(c,row.getPlayerId());units.closed(c,unit,now(c));return true;
        });
    }
    public boolean requestCancelled(long player, String request) {
        return tx(c->{var identity=originalProfile(c,player);owner(c,player,identity.getOwnerAccount(),identity.getOwnerRevision());
            return requestCancelled(c,player,identity.getOwnerAccount(),identity.getOwnerRevision(),request);});
    }
    public boolean requestCancelled(SessionContext session,String request){return tx(c->{actor(c,session);return requestCancelled(c,session.getPlayerId(),session.getAccount(),session.getOwnerRevision(),request);});}
    private boolean requestCancelled(Connection c,long player,String account,long revision,String request)throws Exception {
        if(request.isEmpty())return false;
        var row=db.findOneByPk(c,intentKey(player,request));if(row.isEmpty())return false;
        if(!row.get().getOwnerAccount().equals(account)||row.get().getOwnerRevision()!=revision)throw new SecurityException("旧归属排队撤销原件不可读取");
        return row.get().getCancelled();
    }
    public static UUID validateRequestId(String request){
        var id=UUID.fromString(request);
        if((id.getMostSignificantBits()==0&&id.getLeastSignificantBits()==0)||!id.toString().equals(request))throw new IllegalArgumentException("匹配请求编号无效");
        return id;
    }
    private static GlobalJoinIntentRow intentKey(long player, String request) {
        var id=validateRequestId(request);
        return GlobalJoinIntentRow.newBuilder().setPlayerId(player).setRequestId(request)
                .setRequestHigh(id.getMostSignificantBits()).setRequestLow(id.getLeastSignificantBits()).build();
    }
    public List<GlobalTicketRow> candidates(long afterMs, long afterPlayer) {
        return tx(c -> db.findAll(c, GlobalTicketRow.class,
                "state=1 AND queue_unit_id='' AND expires_ms>? AND not_before_ms<=? AND (enqueued_ms>? OR (enqueued_ms=? AND player_id>?)) ORDER BY enqueued_ms,player_id LIMIT 32",
                now(c),now(c), afterMs, afterMs, PbMysql.uint64(afterPlayer)));
    }
    public List<GlobalTicketRow> readyCandidates(long afterMs,long afterPlayer) {
        return tx(c->db.findAll(c,GlobalTicketRow.class,"state=3 AND (enqueued_ms>? OR (enqueued_ms=? AND player_id>?)) ORDER BY enqueued_ms,player_id LIMIT 8",afterMs,afterMs,PbMysql.uint64(afterPlayer)));
    }
    public synchronized boolean repairRegions(){
        var unitProgress=tx(c->units.repair(c,now(c)));units.committed(unitProgress);
        for(String expired:unitProgress.expired())cancelUnit(null,expired);
        var progress=tx(c->regions.repair(c,now(c)));regions.committed(progress);return progress.complete()&&unitProgress.complete();
    }
    public List<GlobalTicketRow> peers(GlobalTicketRow anchor,int region,boolean cross,long stamp){
        return tx(c->regions.peers(c,anchor,region,cross,stamp,config,rating));
    }
    /** 整组校验/占用与创建成局行同一事务；取消与另一个副本成局竞争同一玩家行锁。 */
    public GlobalFormationRow claim(List<GlobalTicketRow> expected, GlobalFormationRow formation, boolean direct) {
        return tx(c -> claim(c,expected,formation,direct));
    }
    private GlobalFormationRow claim(Connection c,List<GlobalTicketRow> expected, GlobalFormationRow formation, boolean direct)throws Exception {
            owners(c,expected);
            for(var target:expected){noWatching(c,target.getPlayerId());if(target.getZoneId()==0||target.getZoneId()!=GlobalQueueUnits.home(c,target.getPlayerId()))throw new SQLException("原匹配票home与已锁路由不同，禁止补当前区");}
            if(!direct)for(var target:expected)if(target.getTeamId()==0)com.game.common.player.MatchEntryAuthority.requireNoPendingLaunches(c,List.of(target.getPlayerId()),"",0,3);
            if(!direct)units.claim(c,expected,formation);
            List<GlobalTicketRow> sorted = expected.stream().sorted((a,b) -> Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList();
            Map<Long,GlobalTicketRow> locked = new HashMap<>();
            for (var target : sorted) locked.put(target.getPlayerId(), locked(c,target.getPlayerId()));
            long stamp = now(c);
            var members = GlobalFormationRow.newBuilder().mergeFrom(formation).clearMembers();
            for (var target : expected) {
                var row = locked.get(target.getPlayerId());
                if (direct) {
                    if (row.getState()!=4) throw new IllegalStateException("整组成员已有票据");
                    var p = db.findOneByPk(c, GlobalProfileRow.newBuilder().setPlayerId(target.getPlayerId()).build()).orElseThrow();
                    var profile=GlobalProbeProfile.parseFrom(p.getPayload());
                    if(!profile.getOwnerAccount().equals(target.getOwnerAccount())||profile.getOwnerRevision()!=target.getOwnerRevision()
                            ||!p.getPayload().equals(target.getProfile()))throw new SecurityException("直接开局原探测身份已被替换");
                    row = target.toBuilder().setTicket(target.getTicket().isEmpty()?UUID.randomUUID().toString():target.getTicket()).setState(1).setEnqueuedMs(stamp)
                            .setProfile(p.getPayload()).setExpiresMs(p.getExpiresMs()).build();
                } else if (row.getState() != 1 || !row.getTicket().equals(target.getTicket()) || row.getNotBeforeMs() > stamp
                        ||!row.getProfile().equals(target.getProfile())||!row.getOwnerAccount().equals(target.getOwnerAccount())||row.getOwnerRevision()!=target.getOwnerRevision())
                    throw new IllegalStateException("成局时票据已变更");
                if (row.getExpiresMs() <= stamp || !GlobalRegionPolicy.latencies(GlobalProbeProfile.parseFrom(row.getProfile()),stamp,config.hardLatencyMs()).containsKey(formation.getRegion()))
                    throw new IllegalStateException("成局时 RTT 已失效");
                db.updateByPk(c, row.toBuilder().setState(2).setFormationId(formation.getBattleId()).setSelectedRegion(formation.getRegion()).build());
                regions.clear(c,row.getPlayerId());
                members.addMembers(GlobalMember.newBuilder().setPlayerId(row.getPlayerId()).setTicket(row.getTicket()));
            }
            if(!direct) {
                boolean local=expected.stream().allMatch(t->t.getPreferredRegion()==formation.getRegion());
                for(var target:expected) {
                    var actual=locked.get(target.getPlayerId());
                    int preferred=GlobalRegionPolicy.preferred(GlobalProbeProfile.parseFrom(actual.getProfile()),stamp);
                    if(preferred!=actual.getPreferredRegion()||(!local&&stamp-actual.getEnqueuedMs()<config.waitSeconds()*1000L))
                        throw new IllegalStateException("成局时本区优先或逐人跨区等待条件已变更");
                    if(!GlobalRegionPolicy.sameLane(expected.getFirst(),actual)||!GlobalRegionPolicy.ratingEligible(expected.getFirst(),actual,stamp,rating))
                        throw new IllegalStateException("成局时队列或评分资格已变更");
                }
            }
            var result = members.setState(1).setCreatedMs(stamp).setDeadlineMs(stamp+300_000).setRetryMs(stamp).build();
            db.insert(c,result);
            return result;
    }
    public GlobalDirectIntentRow direct(GlobalDirectIntentRow key) { return tx(c->{
        if(key.getKind()!=2)owner(c,key.getOwnerId(),key.getOwnerAccount(),key.getOwnerRevision());
        var row=db.findOneByPk(c,key).orElse(null);
        if(row!=null&&(!row.getOwnerAccount().equals(key.getOwnerAccount())||row.getOwnerRevision()!=key.getOwnerRevision()))throw new SecurityException("旧归属的开局意图不能重放");
        return row;
    }); }
    public GlobalDirectIntentRow prepareDirect(GlobalDirectIntentRow key,ByteString payload,List<GlobalTicketRow> members,GlobalFormationRow formation){return tx(c->prepareDirect(c,key,payload,members,formation));}
    private GlobalDirectIntentRow prepareDirect(Connection c,GlobalDirectIntentRow key,ByteString payload,List<GlobalTicketRow> members,GlobalFormationRow formation)throws Exception {
        owners(c,members);
        if(key.getKind()==2)GlobalActivityLaunchAuthority.lock(c,payload,members);
        if(key.getKind()!=2&&members.stream().noneMatch(t->t.getPlayerId()==key.getOwnerId()&&t.getOwnerAccount().equals(key.getOwnerAccount())&&t.getOwnerRevision()==key.getOwnerRevision()))throw new SecurityException("直接准入原发起者不在名单");
        var prior=db.findOneByPkForUpdate(c,key);
        if(prior.isPresent()){
            var original=prior.get();if(!original.getPayload().equals(payload)||!original.getOwnerAccount().equals(key.getOwnerAccount())||original.getOwnerRevision()!=key.getOwnerRevision()||original.getCancelled())throw new SecurityException("原direct请求已变更或取消");
            if(original.getBattleId()!=0||original.getAdmissionPrepared()&&original.hasStartOriginal())return original;
            throw new SQLException("旧direct缺原始准入原件，不能补当前名单");
        }
        requireNewRoot();com.game.common.player.MatchEntryAuthority.requireFree(c,members.stream().map(GlobalTicketRow::getPlayerId).toList(),"",key.getKind()==2?key.getRequestLow():0,3);
        List<GlobalTicketRow> pending=new ArrayList<>();long stamp=now(c);
        for(var member:members.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList())if(locked(c,member.getPlayerId()).getState()!=4)throw new SQLException("直接准入成员已有原票占用");
        for(var member:members){var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow();
            if(!profile.getPayload().equals(member.getProfile())||profile.getExpiresMs()<=stamp)throw new SQLException("直接准入原profile已变或过期");
            pending.add(member.toBuilder().setTicket(member.getTicket().isEmpty()?UUID.randomUUID().toString():member.getTicket()).clearRequestId().clearQueueUnitId().clearFormationId().clearSelectedRegion().setZoneId(GlobalQueueUnits.home(c,member.getPlayerId())).setState(0).setEnqueuedMs(stamp).build());
        }
        var roles=pending.stream().map(t->com.game.proto.ObserverExitOriginal.newBuilder().setPlayerId(t.getPlayerId()).setOwnerAccount(t.getOwnerAccount()).setOwnerRevision(t.getOwnerRevision()).build()).toList();
        var exits=com.game.common.player.ObserverExitAuthority.capture(c,roles,3);
        for(var row:pending)db.updateByPk(c,row);
        var original=key.toBuilder().setPayload(payload).setAdmissionPrepared(true).setStartOriginal(GlobalDirectStartOriginal.newBuilder().setFormation(formation).addAllTickets(pending))
            .addAllObserverExits(exits.stream().map(com.game.proto.ObserverExitOriginal::toByteString).toList()).build();db.insert(c,original);return original;
    }
    public GlobalFormationRow completeDirect(GlobalDirectIntentRow key){
        var seen=tx(c->db.findOneByPk(c,key).orElseThrow());
        if(!seen.hasStartOriginal()||!seen.getAdmissionPrepared())throw new IllegalStateException("直接准入缺永久原件");
        return tx(c->{var members=seen.getStartOriginal().getTicketsList();owners(c,members);for(var member:members)noWatching(c,member.getPlayerId());
            if(key.getKind()==2&&!seen.getCancelled())GlobalActivityLaunchAuthority.lock(c,seen.getPayload(),members);
            GlobalChallengeRow invite=null;if(key.getKind()==3){invite=db.findOneByPkForUpdate(c,GlobalChallengeRow.newBuilder().setChallengeId(key.getRequestLow()).build()).orElseThrow();if(invite.getState()!=4&&invite.getState()!=2)throw new SQLException("原挑战决定并非接受待续");}
            var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!original.getStartOriginal().equals(seen.getStartOriginal())||!original.getPayload().equals(seen.getPayload()))throw new SQLException("原direct准入已被替换");
            if(original.getBattleId()!=0)return db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(original.getBattleId()).build()).orElseThrow();
            for(var bytes:original.getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,com.game.proto.ObserverExitOriginal.parseFrom(bytes),3))throw new SQLException("原direct退出观战仍无正证明");
            long stamp=now(c);boolean aborted=original.getCancelled()||members.stream().anyMatch(t->t.getExpiresMs()<=stamp);
            for(var member:members){var p=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(member.getPlayerId()).build());if(p.isEmpty()||!p.get().getPayload().equals(member.getProfile())||p.get().getExpiresMs()<=stamp)aborted=true;}
            for(var member:members.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList()){
                var actual=locked(c,member.getPlayerId());if(!actual.equals(member)||actual.getState()!=0)throw new SQLException("原direct pending票已变");db.updateByPk(c,actual.toBuilder().setState(4).build());
            }
            if(aborted){db.updateByPk(c,original.toBuilder().setCancelled(true).setAdmissionCompleted(true).build());if(invite!=null)db.updateByPk(c,invite.toBuilder().setState(3).build());return GlobalFormationRow.newBuilder().setState(5).build();}
            var result=claim(c,members,original.getStartOriginal().getFormation(),true);db.updateByPk(c,original.toBuilder().setBattleId(result.getBattleId()).setAdmissionCompleted(true).build());
            if(invite!=null)db.updateByPk(c,invite.toBuilder().setState(2).setBattleId(result.getBattleId()).build());return result;
        });
    }
    public GlobalDirectIntentRow pendingDirectUpper(){return tx(c->{var rows=db.findAll(c,GlobalDirectIntentRow.class,"admission_prepared=1 AND admission_completed=0 AND battle_id=0 AND kind IN(2,3) ORDER BY kind DESC,owner_id DESC,request_high DESC,request_low DESC LIMIT 1");return rows.isEmpty()?null:rows.getFirst();});}
    public List<GlobalDirectIntentRow> pendingDirects(int kind,long owner,long high,long low,GlobalDirectIntentRow upper){return tx(c->db.findAll(c,GlobalDirectIntentRow.class,
        "admission_prepared=1 AND admission_completed=0 AND battle_id=0 AND kind IN(2,3) AND (kind>? OR (kind=? AND (owner_id>? OR (owner_id=? AND (request_high>? OR (request_high=? AND request_low>?)))))) AND (kind<? OR (kind=? AND (owner_id<? OR (owner_id=? AND (request_high<? OR (request_high=? AND request_low<=?)))))) ORDER BY kind,owner_id,request_high,request_low LIMIT 8",
        kind,kind,PbMysql.uint64(owner),PbMysql.uint64(owner),PbMysql.uint64(high),PbMysql.uint64(high),PbMysql.uint64(low),upper.getKind(),upper.getKind(),PbMysql.uint64(upper.getOwnerId()),PbMysql.uint64(upper.getOwnerId()),PbMysql.uint64(upper.getRequestHigh()),PbMysql.uint64(upper.getRequestHigh()),PbMysql.uint64(upper.getRequestLow())));}
    /** 新意图仅此处捕获目标归属；旧邀请/接受绝不再用当前归属补原件。 */
    private SessionContext newTarget(Connection c,long player)throws SQLException {
        try(var q=c.prepareStatement("SELECT account,owner_revision,trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
            q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(player));
            try(var row=q.executeQuery()) {
                if(!row.next()||!"0".equals(row.getString(3)))throw new SecurityException("切磋目标不存在或已经冻结");
                long revision=Long.parseUnsignedLong(row.getString(2));String account=row.getString(1);
                if(revision<=0||account==null||account.isEmpty())throw new SecurityException("目标归属原件缺失或超出Java修订范围");
                return SessionContext.newBuilder().setPlayerId(player).setAccount(account).setOwnerRevision(revision).build();
            }
        }
    }
    public GlobalChallengeRow invite(SessionContext session,long challenge,long target,int configId,long ttl) {
        if(challenge==0||target==0||target==session.getPlayerId()||ttl<=0)throw new IllegalArgumentException("切磋邀请参数无效");
        return tx(c->{
            SessionContext recipient;
            com.game.common.player.PlayerRouteAuthority.lock(c,List.of(target,session.getPlayerId()),3);
            if(Long.compareUnsigned(target,session.getPlayerId())<0){recipient=newTarget(c,target);actor(c,session);}
            else {actor(c,session);recipient=newTarget(c,target);}
            long stamp=now(c);
            var pending=db.findAll(c,GlobalChallengeRow.class,"target_id=? AND state NOT IN (2,3) AND (state<>1 OR expires_ms>?) ORDER BY expires_ms,challenge_id LIMIT 1 FOR UPDATE",PbMysql.uint64(target),stamp);
            if(!pending.isEmpty()) {
                var previous=pending.getFirst();
                if(previous.getState()!=1)throw new IllegalStateException("目标邀请状态未知");
                if(previous.getChallengerId()!=session.getPlayerId()||previous.getConfigId()!=configId
                        ||!previous.getChallengerAccount().equals(session.getAccount())||previous.getChallengerRevision()!=session.getOwnerRevision()
                        ||!previous.getTargetAccount().equals(recipient.getAccount())||previous.getTargetRevision()!=recipient.getOwnerRevision())
                    return null;
                return previous;
            }
            requireNewRoot();var row=GlobalChallengeRow.newBuilder().setChallengeId(challenge).setChallengerId(session.getPlayerId()).setTargetId(target).setConfigId(configId)
                    .setChallengerAccount(session.getAccount()).setChallengerRevision(session.getOwnerRevision())
                    .setTargetAccount(recipient.getAccount()).setTargetRevision(recipient.getOwnerRevision()).setCreatedMs(stamp).setExpiresMs(Math.addExact(stamp,ttl)).setState(1).build();
            db.insert(c,row);return row;
        });
    }
    private GlobalChallengeRow invitation(Connection c,SessionContext session,long challenge)throws Exception {
        var key=GlobalChallengeRow.newBuilder().setChallengeId(challenge).build();
        var seen=db.findOneByPk(c,key).orElseThrow(()->new IllegalArgumentException("切磋邀请不存在"));
        if(seen.getTargetId()!=session.getPlayerId()||!seen.getTargetAccount().equals(session.getAccount())||seen.getTargetRevision()!=session.getOwnerRevision())
            throw new SecurityException("原切磋目标身份已失效");
        owners(c,List.of(GlobalTicketRow.newBuilder().setPlayerId(seen.getChallengerId()).setOwnerAccount(seen.getChallengerAccount()).setOwnerRevision(seen.getChallengerRevision()).build(),
                GlobalTicketRow.newBuilder().setPlayerId(seen.getTargetId()).setOwnerAccount(seen.getTargetAccount()).setOwnerRevision(seen.getTargetRevision()).build()));
        var actual=db.findOneByPkForUpdate(c,key).orElseThrow();
        if(!seen.toBuilder().clearState().clearResponsePayload().clearBattleId().build().equals(actual.toBuilder().clearState().clearResponsePayload().clearBattleId().build()))
            throw new IllegalStateException("切磋原件被替换");
        return actual;
    }
    public GlobalChallengeRow invitation(SessionContext session,long challenge){return tx(c->invitation(c,session,challenge));}
    public GlobalChallengeRow decideChallenge(SessionContext session,long challenge,ByteString response,boolean accept,List<GlobalTicketRow> members,GlobalFormationRow formation) {
        if(response.isEmpty())throw new IllegalArgumentException("切磋决定原件为空");
        try {
            var request=com.game.proto.match.RespondChallengeRequest.parseFrom(response);
            if(request.getChallengeId()!=challenge||request.getPlayerId()!=session.getPlayerId()||request.getAccept()!=accept)
                throw new IllegalArgumentException("切磋决定正文与原身份参数不同");
        }catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new IllegalArgumentException("切磋决定正文损坏",invalid);}
        return tx(c->{
            var original=invitation(c,session,challenge);
            if(original.getState()!=1) {
                if((original.getState()!=2&&original.getState()!=3&&original.getState()!=4)||!original.getResponsePayload().equals(response))throw new IllegalArgumentException("切磋原邀请已经决定");
                return original;
            }
            if(original.getExpiresMs()<=now(c))throw new IllegalArgumentException("切磋邀请已过期");
            var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(original.getTargetId()).setRequestLow(challenge)
                    .setOwnerAccount(original.getTargetAccount()).setOwnerRevision(original.getTargetRevision()).build();
            GlobalChallengeRow result;
            if(accept) {
                if(members.size()!=2||formation==null||formation.getConfigId()!=original.getConfigId()
                        ||members.get(0).getPlayerId()!=original.getChallengerId()||members.get(1).getPlayerId()!=original.getTargetId()
                        ||!members.get(0).getOwnerAccount().equals(original.getChallengerAccount())||members.get(0).getOwnerRevision()!=original.getChallengerRevision()
                        ||!members.get(1).getOwnerAccount().equals(original.getTargetAccount())||members.get(1).getOwnerRevision()!=original.getTargetRevision())
                    throw new SecurityException("切磋名单与邀请原归属不同");
                prepareDirect(c,key,response,members,formation);
                result=original.toBuilder().setState(4).setResponsePayload(response).build();
            } else {
                db.insert(c,key.toBuilder().setPayload(response).build());
                result=original.toBuilder().setState(3).setResponsePayload(response).build();
            }
            db.updateByPk(c,result);return result;
        });
    }
    public GlobalFormationRow reserveDirect(GlobalDirectIntentRow key,ByteString payload,List<GlobalTicketRow> members,GlobalFormationRow formation) {
        if(key.getKind()<1||key.getKind()>3||key.getOwnerId()==0||(key.getRequestHigh()==0&&key.getRequestLow()==0)||payload.isEmpty()||members.isEmpty()||members.size()>10)throw new IllegalArgumentException("直接开局原意图无效");
        return tx(c->reserveDirect(c,key,payload,members,formation));
    }
    public GlobalFormationRow teamReplay(GlobalDirectIntentRow key,ByteString payload,ByteString launch) {
        return tx(c->{
            owner(c,key.getOwnerId(),key.getOwnerAccount(),key.getOwnerRevision());
            var originalLaunch=GlobalTeamLaunchAuthority.original(launch);GlobalTeamLaunchAuthority.bind(key,originalLaunch,payload);
            var queued=units.find(c,GlobalQueueUnits.teamId(originalLaunch),false);
            if(queued!=null){
                if(!queued.getLaunchOriginal().equals(launch)||!originalLaunch.getRequest().toByteString().equals(payload))throw new SecurityException("原整队单位载荷不同");
                return queued.getBattleId()==0?GlobalFormationRow.newBuilder().setState(queued.getState()==4?5:2).build():db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(queued.getBattleId()).build()).orElseThrow();
            }
            var prior=db.findOneByPk(c,key);if(prior.isEmpty())return null;
            if(!prior.get().getOwnerAccount().equals(key.getOwnerAccount())||prior.get().getOwnerRevision()!=key.getOwnerRevision()||!prior.get().getPayload().equals(payload))
                throw new SecurityException("原整队意图已改变");
            var original=GlobalTeamLaunchAuthority.original(launch);
            try(var q=c.prepareStatement("SELECT original_proto FROM team_launch WHERE request_id=?")) {
                q.setQueryTimeout(3);q.setString(1,original.getRequest().getRequestId());
                try(var row=q.executeQuery()){if(!row.next()||!launch.equals(ByteString.copyFrom(row.getBytes(1))))throw new SecurityException("整队重放原件与SQL不同");}
            }
            if(prior.get().getBattleId()==0){if(prior.get().getAdmissionPrepared()&&prior.get().hasStartOriginal()&&prior.get().getStartOriginal().getTeamLaunchOriginal().equals(launch))return null;throw new SQLException("原Team意图未完成且缺首次准入原件");}
            return db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(prior.get().getBattleId()).build()).orElseThrow();
        });
    }
    public GlobalDirectIntentRow prepareTeam(GlobalDirectIntentRow key,ByteString raw,List<GlobalTicketRow> members,GlobalFormationRow formation){
        var launch=GlobalTeamLaunchAuthority.original(raw);GlobalTeamLaunchAuthority.bind(key,launch,launch.getRequest().toByteString());return tx(c->{owners(c,members);
            if(GlobalTeamLaunchAuthority.lock(c,launch,raw,members)>=4)throw new SQLException("终局Team不能首次准备准入");
            var prior=db.findOneByPkForUpdate(c,key);
            if(prior.isPresent()){
                var original=prior.get();if(!original.getOwnerAccount().equals(key.getOwnerAccount())||original.getOwnerRevision()!=key.getOwnerRevision()||!original.getPayload().equals(launch.getRequest().toByteString())||!original.hasStartOriginal()||!original.getStartOriginal().getTeamLaunchOriginal().equals(raw))throw new SQLException("Team首次准入原件不同或缺失");return original;
            }
            requireNewRoot();for(var member:members){if(locked(c,member.getPlayerId()).getState()!=4)throw new SQLException("Team首次准入角色已有票");var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow();if(!profile.getPayload().equals(member.getProfile())||profile.getExpiresMs()<=now(c))throw new SQLException("Team首次准入网络原件已变");}
            var original=key.toBuilder().setPayload(launch.getRequest().toByteString()).setAdmissionPrepared(true).setStartOriginal(GlobalDirectStartOriginal.newBuilder().setTeamLaunchOriginal(raw).setFormation(formation).addAllTickets(members)).build();db.insert(c,original);return original;
        });
    }
    /** 退出原证明齐后才可安全终结失效Team；部分队伍沿共用永久unit取消，满队沿固定F5零建房原件。 */
    public boolean closeObsoleteTeam(GlobalDirectIntentRow prepared,Deadline deadline){return tx(deadline,c->{
        var start=prepared.getStartOriginal();var launch=GlobalTeamLaunchAuthority.original(start.getTeamLaunchOriginal());var members=start.getTicketsList();owners(c,members);
        if(GlobalTeamLaunchAuthority.lock(c,launch,start.getTeamLaunchOriginal(),members)>=4)return true;
        var original=db.findOneByPkForUpdate(c,prepared).orElseThrow();if(!original.getStartOriginal().equals(start))throw new SQLException("Team准入原件已经替换");if(original.getBattleId()!=0||original.getAdmissionCompleted())return false;
        for(var exit:launch.getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,exit,3))throw new SQLException("Team退出正证明仍未齐");
        long stamp=now(c);boolean obsolete=original.getCancelled();
        for(var member:members){var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(member.getPlayerId()).build());var receipt=db.findOneByPk(c,intentKey(member.getPlayerId(),launch.getRequest().getRequestId()));
            if(member.getExpiresMs()<=stamp||profile.isEmpty()||profile.get().getExpiresMs()<=stamp||!profile.get().getPayload().equals(member.getProfile())||receipt.isPresent()&&receipt.get().getCancelled())obsolete=true;
        }
        if(!obsolete)return false;
        if(launch.getRequest().getTargetTeamSize()>members.size()){
            if(com.game.common.player.TeamQueueAuthority.cancel(c,launch,start.getTeamLaunchOriginal().toByteArray(),3)!=com.game.common.player.TeamQueueAuthority.Result.CANCELLED)throw new SQLException("Team失效原unit仍不能证明安全取消");
        }else{
            var f=start.getFormation();if(f.getBattleId()==0||!f.getCreateRequest().isEmpty())throw new SQLException("失效满队缺少零建房的固定原件");
            var terminal=f.toBuilder().setState(5).setCreatedMs(stamp).setRetryMs(stamp).build();db.insert(c,terminal);
            db.updateByPk(c,original.toBuilder().setBattleId(f.getBattleId()).setCancelled(true).setAdmissionCompleted(true).build());
            try(var q=c.prepareStatement("UPDATE team_launch SET state=5,battle_id=?,updated_ms=? WHERE request_id=? AND original_proto=?")){q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(f.getBattleId()));q.setLong(2,stamp);q.setString(3,launch.getRequest().getRequestId());q.setBytes(4,start.getTeamLaunchOriginal().toByteArray());if(q.executeUpdate()!=1)throw new SQLException("失效Team原launch不同");}
        }return true;
    });}
    public GlobalFormationRow reserveTeam(GlobalDirectIntentRow key,ByteString payload,ByteString launch,List<GlobalTicketRow> members,GlobalFormationRow formation) {
        var original=GlobalTeamLaunchAuthority.original(launch);
        GlobalTeamLaunchAuthority.bind(key,original,payload);
        return tx(c->{owners(c,members);int state=GlobalTeamLaunchAuthority.lock(c,original,launch,members);
            if(state>=4&&db.findOneByPk(c,key).isEmpty())throw new IllegalStateException("已终局整队launch不能创建新Match意图");
            for(var exit:original.getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,exit,3))throw new SQLException("整队直开缺少永久退出正证明");
            return reserveDirect(c,key,payload,members,formation);});
    }
    public void validateTeamLaunch(ByteString launch,List<GlobalTicketRow> members){
        var original=GlobalTeamLaunchAuthority.original(launch);tx(c->{owners(c,members);if(GlobalTeamLaunchAuthority.lock(c,original,launch,members)>=4)throw new SQLException("整队原launch已终局");return null;});
    }
    public GlobalFormationRow enqueueTeam(GlobalDirectIntentRow key,ByteString launch,List<GlobalTicketRow> members) {
        var original=GlobalTeamLaunchAuthority.original(launch);GlobalTeamLaunchAuthority.bind(key,original,original.getRequest().toByteString());int target=original.getRequest().getTargetTeamSize();
        if(target<=members.size()||target>com.game.common.team.TeamPolicyContract.CAPACITY)throw new IllegalArgumentException("整队补位目标无效");
        return tx(c->{
            owners(c,members);for(var member:members)noWatching(c,member.getPlayerId());
            if(GlobalTeamLaunchAuthority.lock(c,original,launch,members)>=4)throw new IllegalStateException("终局launch不得重新排队");
            String unitId=GlobalQueueUnits.teamId(original);var prior=units.find(c,unitId,false);
            if(prior!=null){if(!prior.getLaunchOriginal().equals(launch))throw new SecurityException("整队单位原件不同");return GlobalFormationRow.newBuilder().setBattleId(prior.getBattleId()).setState(prior.getState()==4?5:prior.getState()==3?4:2).build();}
            requireNewRoot();db.insertIgnore(c,key.toBuilder().setPayload(original.getRequest().toByteString()).build());
            var intent=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(intent.getCancelled()||intent.getBattleId()!=0||!intent.getPayload().equals(original.getRequest().toByteString())||!intent.getOwnerAccount().equals(key.getOwnerAccount())||intent.getOwnerRevision()!=key.getOwnerRevision())throw new SecurityException("整队补位原意图已决定或归属不同");
            for(var member:members){var receipt=db.findOneByPk(c,intentKey(member.getPlayerId(),original.getRequest().getRequestId()));
                if(receipt.isPresent()){
                    if(!receipt.get().getOwnerAccount().equals(member.getOwnerAccount())||receipt.get().getOwnerRevision()!=member.getOwnerRevision())throw new SecurityException("整队原Join回执属于不同归属");
                    if(receipt.get().getCancelled()){
                        if(com.game.common.player.TeamQueueAuthority.cancel(c,original,launch.toByteArray(),3)!=com.game.common.player.TeamQueueAuthority.Result.CANCELLED)throw new SQLException("整队原取消尚无安全证明");
                        return GlobalFormationRow.newBuilder().setState(5).build();
                    }
                    throw new SQLException("同UUID已有其他不可覆盖的Join原回执");
                }
            }
            for(var bytes:original.getObserverExitsList())if(!com.game.common.player.ObserverExitAuthority.provedClosed(c,bytes,3))throw new SQLException("整队原退出证明未齐");
            long stamp=now(c);List<GlobalTicketRow> rows=new ArrayList<>();
            for(var member:members){
                var old=locked(c,member.getPlayerId());if(old.getState()!=4)throw new IllegalStateException("整队补位成员已有票据");
                var profile=db.findOneByPk(c,GlobalProfileRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow();
                if(profile.getExpiresMs()<=stamp||!profile.getPayload().equals(member.getProfile()))throw new IllegalStateException("整队补位探测原件已变");
                var row=member.toBuilder().setState(1).setTeamSize(target).setRequestId(original.getRequest().getRequestId()).setEnqueuedMs(stamp).setNotBeforeMs(stamp)
                    .setZoneId(original.getZonesOrThrow(member.getPlayerId())).setQueueUnitId(unitId).build();
                db.updateByPk(c,row);regions.replace(c,row,stamp);rows.add(row);
                db.insert(c,intentKey(row.getPlayerId(),row.getRequestId()).toBuilder().setOwnerAccount(row.getOwnerAccount()).setOwnerRevision(row.getOwnerRevision()).setTicketRecord(row.toByteString()).build());
            }
            units.create(c,rows,launch,stamp);
            db.updateByPk(c,intent.toBuilder().setAdmissionCompleted(true).build());
            try(var q=c.prepareStatement("UPDATE team_launch SET state=2,battle_id=0,updated_ms=? WHERE request_id=? AND original_proto=?")){q.setQueryTimeout(3);q.setLong(1,stamp);q.setString(2,original.getRequest().getRequestId());q.setBytes(3,launch.toByteArray());if(q.executeUpdate()!=1)throw new SQLException("整队排队原launch已改变");}
            return GlobalFormationRow.newBuilder().setState(2).build();
        });
    }
    public List<GlobalQueueUnitRow> unitCandidates(long afterMs,String afterId){return tx(c->units.anchors(c,afterMs,afterId));}
    public List<GlobalQueueUnitRow> unitPeers(GlobalQueueUnitRow anchor,int region,boolean cross,long stamp,Deadline deadline){return tx(deadline,c->units.peers(c,anchor,region,cross,stamp,deadline));}
    public com.game.proto.team.TeamMatchIntent teamIntent(GlobalDirectIntentRow key,ByteString payload){
        return tx(c->{owner(c,key.getOwnerId(),key.getOwnerAccount(),key.getOwnerRevision());
            var direct=db.findOneByPk(c,key);if(direct.isEmpty())return null;
            if(!direct.get().getOwnerAccount().equals(key.getOwnerAccount())||direct.get().getOwnerRevision()!=key.getOwnerRevision()||(!payload.isEmpty()&&!payload.equals(direct.get().getPayload())))throw new SecurityException("整队查询原意图不同");
            var request=com.game.proto.team.StartTeamMatchRequest.parseFrom(direct.get().getPayload());
            var unit=units.find(c,"t:"+Long.toUnsignedString(request.getExpectedTeamId())+":"+request.getRequestId(),false);
            if(unit==null){if(direct.get().getAdmissionPrepared()&&direct.get().getBattleId()==0)return com.game.proto.team.TeamMatchIntent.newBuilder().setRequestId(request.getRequestId()).setState(1).build();return null;}return com.game.proto.team.TeamMatchIntent.newBuilder().setRequestId(request.getRequestId()).setBattleId(unit.getBattleId()).setState(unit.getState()==1?2:unit.getState()==2?3:unit.getState()==3?4:5).build();});
    }
    private GlobalFormationRow reserveDirect(Connection c,GlobalDirectIntentRow key,ByteString payload,List<GlobalTicketRow> members,GlobalFormationRow formation)throws Exception {
            if(key.getKind()!=2&&members.stream().noneMatch(t->t.getPlayerId()==key.getOwnerId()&&t.getOwnerAccount().equals(key.getOwnerAccount())&&t.getOwnerRevision()==key.getOwnerRevision()))
                throw new SecurityException("开局发起原身份不在名单内");
            owners(c,members);
            if(key.getKind()!=2)owner(c,key.getOwnerId(),key.getOwnerAccount(),key.getOwnerRevision());
            db.insertIgnore(c,key.toBuilder().setPayload(payload).build());
            var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!original.getPayload().equals(payload))throw new IllegalArgumentException("相同业务意图内容不同");
            if(!original.getOwnerAccount().equals(key.getOwnerAccount())||original.getOwnerRevision()!=key.getOwnerRevision())throw new SecurityException("旧归属开局原意图不能重放");
            if(original.getCancelled())throw new IllegalArgumentException("活动原请求已持久撤销");
            if(original.getBattleId()!=0)return db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(original.getBattleId()).build()).orElseThrow();
            var result=claim(c,members,formation,true);
            db.updateByPk(c,original.toBuilder().setBattleId(result.getBattleId()).setAdmissionCompleted(true).build());return result;
    }
    /** 与reserveDirect争原PK；先取消留下完整原payload墓碑，已成局只能返回固定battle，不取消战斗。 */
    public GlobalDirectIntentRow cancelActivity(GlobalDirectIntentRow key,ByteString payload) {
        if(key.getKind()!=2||key.getOwnerId()==0||(key.getRequestHigh()==0&&key.getRequestLow()==0)||payload.isEmpty())
            throw new IllegalArgumentException("活动取消原件无效");
        return tx(c->{
            db.insertIgnore(c,key.toBuilder().setPayload(payload).build());
            var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!original.getPayload().equals(payload))throw new IllegalArgumentException("活动取消原请求内容不同");
            if(original.getBattleId()!=0) {
                if(original.getCancelled())throw new IllegalStateException("已成局活动存在矛盾的取消墓碑");
                return original;
            }
            var tombstone=original.toBuilder().setCancelled(true).build();db.updateByPk(c,tombstone);return tombstone;
        });
    }
    public boolean declineDirect(GlobalDirectIntentRow key,ByteString payload) {
        if(key.getKind()!=3||key.getOwnerId()==0||(key.getRequestHigh()==0&&key.getRequestLow()==0)||payload.isEmpty())throw new IllegalArgumentException("拒战原意图无效");
        return tx(c->{owner(c,key.getOwnerId(),key.getOwnerAccount(),key.getOwnerRevision());db.insertIgnore(c,key.toBuilder().setPayload(payload).build());var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            return original.getBattleId()==0&&original.getPayload().equals(payload)&&original.getOwnerAccount().equals(key.getOwnerAccount())&&original.getOwnerRevision()==key.getOwnerRevision();});
    }
    public static GlobalDirectIntentRow directUuidKey(int kind,long owner,String request) {
        var id=validateRequestId(request);
        return GlobalDirectIntentRow.newBuilder().setKind(kind).setOwnerId(owner).setRequestHigh(id.getMostSignificantBits()).setRequestLow(id.getLeastSignificantBits()).build();
    }
    public GlobalFormationRow formation(long battle) {
        return tx(c -> db.findOneByPk(c, GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElse(null));
    }
    public List<GlobalFormationRow> pending() {
        return tx(c -> db.findAll(c,GlobalFormationRow.class,"state IN (1,2,3) AND retry_ms<=? ORDER BY retry_ms LIMIT 32",now(c)));
    }
    /** retry_ms 是到期时间也是单调工作代次；不续租，所有出站准入截止须早于该代次。 */
    public GlobalFormationRow acquireWork(long battle) {
        return tx(c->{var f=db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();long stamp=now(c);
            if(f.getState()>=4||f.getRetryMs()>stamp)throw new IllegalStateException("成局工作租约被其他副本持有");
            var work=f.toBuilder().setRetryMs(stamp+120_000);
            if(f.getState()==1&&f.getRetryMs()!=f.getCreatedMs())work.setState(3);
            var result=work.build();db.updateByPk(c,result);return result;});
    }
    private void requireWork(Connection c,GlobalFormationRow actual,GlobalFormationRow work)throws Exception {
        if(actual.getState()>=4||work.getRetryMs()==0||actual.getRetryMs()!=work.getRetryMs()||actual.getRetryMs()<=now(c))
            throw new IllegalStateException("成局工作租约已经失效");
    }
    public void checkWork(GlobalFormationRow work){tx(c->{var f=db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(work.getBattleId()).build()).orElseThrow();requireWork(c,f,work);return null;});}
    public GlobalFormationRow saveIntent(GlobalFormationRow formation, byte[] request) {
        try {
            if(com.game.proto.CreateBattleRequest.parseFrom(request).getOutcomeProtocolVersion()!=0)
                throw new SecurityException("v1建房必须绑定真实Create前能力，不能走旧兼容入口");
        } catch(com.google.protobuf.InvalidProtocolBufferException bad) { throw new IllegalArgumentException("建房原件损坏",bad); }
        return tx(c -> {
            var old = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(formation.getBattleId()).build()).orElseThrow();
            requireWork(c,old,formation);
            if (old.getState() == 1) {
                // 观战可见性列与建房原件同一笔写入：列表与随机选场按这一列过滤，不解码原件
                old = old.toBuilder().setCreateRequest(ByteString.copyFrom(request))
                        .setSpectatePublic(com.game.match.spectate.SpectateVisibilityPolicy.createRequestPublic(ByteString.copyFrom(request))).build();
                // prepared 全集合与创建意图同时持久化；最后只允许首个创建者写入。
                old = old.toBuilder().clearPrepared().addAllPrepared(formation.getPreparedList()).setState(2).build();
                db.updateByPk(c,old);
            }
            return old;
        });
    }

    /** 原能力与完整v1正文在同一SQL提交，且早于任何Create。adopted是另一次真实回执，不用支持能力冒充。 */
    public GlobalFormationRow saveIntent(GlobalFormationRow formation,byte[] request,
            com.game.proto.QueryBattleCreationResponse preflight) {
        return tx(c->{
            var old=db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(formation.getBattleId()).build()).orElseThrow();
            requireWork(c,old,formation);
            var create=com.game.proto.CreateBattleRequest.parseFrom(request);
            validateCreateBinding(old,create);
            if(create.getOutcomeProtocolVersion()!=1||preflight==null||preflight.getSupportedOutcomeProtocolVersion()!=1
                    ||!preflight.getTargetInstanceId().equals(old.getInstanceId())
                    ||preflight.getState()!=com.game.proto.BattleCreationState.BATTLE_CREATION_ABSENT
                    ||create.getBattleId()!=old.getBattleId()||!create.getPlacementOperationId().equals(old.getOperationId())
                    ||!create.getTargetInstanceId().equals(old.getInstanceId())||create.getDeadlineMs()!=old.getDeadlineMs())
                throw new SecurityException("建房前能力、UUID或固定v1正文不同");
            if(old.getState()!=1)throw new IllegalStateException("原建房意图已经提交，不能替换前置能力");
            var terminal=com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(old.getBattleId())
                    .setFormatVersion(1).setPlacementOperationId(old.getOperationId()).setTargetInstanceId(old.getInstanceId())
                    .setOutcomeProtocolVersion(1).setPreflightSupportedVersion(1).setPreflightInstanceId(preflight.getTargetInstanceId()).build();
            db.insert(c,terminal);
            var saved=old.toBuilder().setCreateRequest(ByteString.copyFrom(request))
                    .setSpectatePublic(com.game.match.spectate.SpectateVisibilityPolicy.isPublic(create.getSpectateVisibility())).clearPrepared()
                    .addAllPrepared(formation.getPreparedList()).setState(2).build();
            db.updateByPk(c,saved);return saved;
        });
    }

    public com.game.match.completion.pb.BattleCompletionRow terminal(long battle) {
        return tx(c->db.findOneByPk(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build()).orElse(null));
    }
    public void adopted(GlobalFormationRow formation,int version,String instance) {
        tx(c->{
            var f=db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(formation.getBattleId()).build()).orElseThrow();
            var key=com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(f.getBattleId()).build();
            var t=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!instance.equals(f.getInstanceId())||t.getOutcomeProtocolVersion()!=1
                    ||!t.getPreflightInstanceId().equals(instance)||t.getPreflightSupportedVersion()!=1)
                throw new SecurityException("执行端回执原UUID或前置原件不同");
            if(t.getAdoptedOutcomeObserved()&&t.getAdoptedOutcomeProtocolVersion()!=version)
                throw new SecurityException("执行端实际adopted观察冲突，不得覆盖原回执");
            db.updateByPk(c,t.toBuilder().setAdoptedOutcomeObserved(true).setAdoptedOutcomeProtocolVersion(version).build());return null;
        });
        // 真实0先提交观察再拒绝，不让异常回滚把它伪装成“从未见到回包”。
        if(version!=1)throw new SecurityException("执行端未实际采用原v1协议");
    }

    /** 同unsigned全员P、原票、formation锁提交唯一终局；RPC原件没有Kafka位点。 */
    public com.game.match.completion.pb.BattleCompletionRow commitOutcome(com.game.proto.match.CommitBattleOutcomeRequest request) {
        return commitOutcome(request,Deadline.after(3000));
    }
    public com.game.match.completion.pb.BattleCompletionRow commitOutcome(com.game.proto.match.CommitBattleOutcomeRequest request,Deadline deadline) {
        var settlements=com.game.match.rating.BattleCompletionStore.validateTerminal(request.getRawResultPayload());
        return tx(deadline,c->{
            // 永久终局不可回退：晚到的原回包不再依赖当前owner/下一局票据，也不改变它们。
            var known=db.findOneByPk(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(request.getBattleId()).build());
            if(known.isPresent()&&known.get().getTerminalDecision()!=0) {
                var terminal=known.get();
                if(terminal.getOutcomeProtocolVersion()!=1||terminal.getPreflightSupportedVersion()!=1
                        ||terminal.getAdoptedOutcomeObserved()&&terminal.getAdoptedOutcomeProtocolVersion()!=1
                        ||terminal.getTerminalDecision()!=1&&terminal.getTerminalDecision()!=2
                        ||!terminal.getPlacementOperationId().equals(request.getPlacementOperationId())
                        ||!terminal.getTargetInstanceId().equals(request.getTargetInstanceId()))throw new SecurityException("终局重放原局身份不同");
                if(terminal.getTerminalDecision()==1&&(!terminal.getOriginal().equals(request.getRawResultPayload())
                        ||!terminal.getOriginalDigest().equals(com.game.match.rating.BattleCompletionStore.digest(request.getRawResultPayload()))))
                    throw new SecurityException("终局重放不是原字节");
                return terminal;
            }
            var seen=db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(request.getBattleId()).build()).orElseThrow();
            List<GlobalTicketRow> original=new ArrayList<>();
            for(var member:seen.getMembersList())original.add(db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow());
            owners(c,original);
            if(seen.getState()<4)units.beforeFinish(c,original);
            Map<Long,GlobalTicketRow> tickets=new HashMap<>();for(var t:original.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList())tickets.put(t.getPlayerId(),locked(c,t.getPlayerId()));
            var f=db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(seen.getBattleId()).build()).orElseThrow();
            var t=db.findOneByPkForUpdate(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(f.getBattleId()).build()).orElseThrow();
            if(!f.getOperationId().equals(request.getPlacementOperationId())||!f.getInstanceId().equals(request.getTargetInstanceId())
                    ||t.getOutcomeProtocolVersion()!=1||t.getPreflightSupportedVersion()!=1||!t.getPreflightInstanceId().equals(f.getInstanceId())
                    ||t.getAdoptedOutcomeObserved()&&t.getAdoptedOutcomeProtocolVersion()!=1)
                throw new SecurityException("终局原operation/实例/前置围栏不符");
            if(t.getTerminalDecision()==2)return t;
            if(t.getTerminalDecision()==1) {
                if(!t.getOriginal().equals(request.getRawResultPayload())||!t.getOriginalDigest().equals(com.game.match.rating.BattleCompletionStore.digest(request.getRawResultPayload())))
                    throw new SecurityException("终局重放不是原字节");
                return t;
            }
            if(f.getState()==5||f.getCreateRequest().isEmpty())throw new SecurityException("原成局不允许完成");
            var create=com.game.proto.CreateBattleRequest.parseFrom(f.getCreateRequest());
            validateCreateBinding(f,create);
            var event=com.game.proto.contracts.kafka.BattleResultEvent.parseFrom(request.getRawResultPayload());
            if(create.getOutcomeProtocolVersion()!=1||event.getBattleId()!=f.getBattleId()||event.getMatchMode()!=f.getMode()
                    ||event.getBattleConfigId()!=f.getConfigId()||!event.getActivityContext().equals(create.getActivityContext())
                    ||event.getFinishedAtMs()<create.getCreatedAtMs()||settlements.size()!=f.getMembersCount())throw new SecurityException("终局与固定建房原件不同");
            var teams=new HashMap<Long,Integer>();for(var member:create.getPlayersList())teams.put(member.getPlayerId(),member.getTeamIndex());
            long stamp=now(c);
            List<GlobalTicketRow> completedTickets=new ArrayList<>();
            for(var member:f.getMembersList()) {
                var ticket=tickets.get(member.getPlayerId());var value=settlements.get(member.getPlayerId());
                if(ticket==null||ticket.getFormationId()!=f.getBattleId()||!ticket.getTicket().equals(member.getTicket())
                        ||value==null||!Objects.equals(teams.get(member.getPlayerId()),value.getPlayerTeamIndex()))
                    throw new SecurityException("终局全员来源或原票身份不同");
                var raw=ByteString.copyFrom(com.game.common.token.NetworkProbes.bytes(value));
                db.insert(c,com.game.match.completion.pb.BattlePlayerCompletionRow.newBuilder().setBattleId(f.getBattleId()).setPlayerId(member.getPlayerId())
                        .setOriginal(raw).setOriginalDigest(com.game.match.rating.BattleCompletionStore.digest(raw))
                        .setOwnerAccount(ticket.getOwnerAccount()).setOwnerRevision(ticket.getOwnerRevision()).setIssuedAtMs(stamp).setRetryMs(stamp).build());
                var completedTicket=ticket.toBuilder().setState(3).build();
                db.updateByPk(c,completedTicket);completedTickets.add(completedTicket);
            }
            var completed=t.toBuilder().setTerminalDecision(1).setOriginal(request.getRawResultPayload())
                    .setOriginalDigest(com.game.match.rating.BattleCompletionStore.digest(request.getRawResultPayload()))
                    .setPlayerCount(settlements.size()).setIssuedAtMs(stamp).setSourceKind(2).setRetryMs(stamp).build();
            if(f.getState()<4)units.finished(c,f,completedTickets,true,stamp);
            db.updateByPk(c,completed);db.updateByPk(c,f.toBuilder().setState(4).build());return completed;
        });
    }

    /** Create提交与COMPLETE提交都核同一持久原件，不能让出站前校验替代终局事务校验。 */
    private static void validateCreateBinding(GlobalFormationRow formation,com.game.proto.CreateBattleRequest create)throws Exception {
        if(create.getBattleId()!=formation.getBattleId()||create.getBattleConfigId()!=formation.getConfigId()
                ||create.getMatchMode()!=formation.getMode()||!create.getPlacementOperationId().equals(formation.getOperationId())
                ||!create.getTargetInstanceId().equals(formation.getInstanceId())||create.getRequiredPhysicalRegion()!=formation.getRegion()
                ||create.getCreatedAtMs()<=0||create.getCreatedAtMs()<formation.getCreatedMs()
                ||create.getDeadlineMs()!=formation.getDeadlineMs()||create.getDeadlineMs()<=create.getCreatedAtMs()
                ||create.getPlayersCount()!=formation.getMembersCount()||create.getPlayersCount()==0
                ||!create.getActivityContext().equals(com.game.proto.BattleActivityContext.parseFrom(formation.getActivityContext())))
            throw new SecurityException("固定Create范围、时间或完整人数不同");
        Set<Long> unique=new HashSet<>();int teamA=0,teamB=0;
        var mode=com.game.proto.match.MatchMode.forNumber(formation.getMode());
        if(mode==null||mode==com.game.proto.match.MatchMode.MATCH_MODE_UNSPECIFIED
                ||mode==com.game.proto.match.MatchMode.MATCH_MODE_3V3)throw new SecurityException("固定Create模式不支持");
        for(int i=0;i<create.getPlayersCount();i++) {
            var player=create.getPlayers(i);
            if(player.getPlayerId()==0||!unique.add(player.getPlayerId())||player.getPlayerId()!=formation.getMembers(i).getPlayerId())
                throw new SecurityException("固定Create完整成员/顺序与formation不同");
            int team=player.getTeamIndex();
            if(mode==com.game.proto.match.MatchMode.MATCH_MODE_1V1||mode==com.game.proto.match.MatchMode.MATCH_MODE_PVP_CHALLENGE) {
                if(create.getPlayersCount()!=2||team!=i)throw new SecurityException("固定单挑队伍不同");
            } else if(mode!=com.game.proto.match.MatchMode.MATCH_MODE_5V5&&team!=0)throw new SecurityException("固定PVE队伍不同");
            if(team==0)teamA++;else if(team==1)teamB++;else throw new SecurityException("固定Create队号非法");
        }
        if(mode==com.game.proto.match.MatchMode.MATCH_MODE_5V5&&(teamA!=5||teamB!=5)
                ||mode==com.game.proto.match.MatchMode.MATCH_MODE_PVE_SOLO&&create.getPlayersCount()!=1)
            throw new SecurityException("固定Create模式人数不符");
    }

    /** 一轮固定上界，持续新成局不能让失败旧项永远等不到下一轮；候选不授予写权限。 */
    public record TerminalDeadlineBatch(long upperBattle,List<Long> battles) {}
    public TerminalDeadlineBatch terminalDeadlineCandidates(long afterBattle,long upperBattle) {
        return tx(c->{
            long stamp=now(c),cutoff=stamp>=TERMINAL_OUTCOME_GRACE_MS?stamp-TERMINAL_OUTCOME_GRACE_MS:0,upper=upperBattle;
            String candidates=" FROM match_global_formation f JOIN match_battle_completion t ON t.battle_id=f.battle_id "
                    +"WHERE f.state IN (2,3,4) AND f.deadline_ms>0 AND f.deadline_ms<=? AND t.terminal_decision=0";
            if(upper==0)try(var query=c.prepareStatement("SELECT f.battle_id"+candidates+" ORDER BY f.battle_id DESC LIMIT 1")) {
                query.setQueryTimeout(3);query.setLong(1,cutoff);
                try(var rows=query.executeQuery()){if(rows.next())upper=Long.parseUnsignedLong(rows.getString(1));}
            }
            if(upper==0)return new TerminalDeadlineBatch(0,List.of());
            List<Long> result=new ArrayList<>();
            try(var query=c.prepareStatement("SELECT f.battle_id"+candidates
                    +" AND f.battle_id>? AND f.battle_id<=? ORDER BY f.battle_id LIMIT 16")) {
                query.setQueryTimeout(3);query.setLong(1,cutoff);query.setObject(2,PbMysql.uint64(afterBattle));query.setObject(3,PbMysql.uint64(upper));
                try(var rows=query.executeQuery()){while(rows.next())result.add(Long.parseUnsignedLong(rows.getString(1)));}
            }
            return new TerminalDeadlineBatch(upper,List.copyOf(result));
        });
    }

    /** 截止只允许竞争持久ABORT；历史0和无Create前能力的行仍UNKNOWN。 */
    public int resolveTerminalAfterDeadline(long battle) {
        return tx(c->{
            var known=db.findOneByPk(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build());
            if(known.isPresent()&&known.get().getAdoptedOutcomeObserved()&&known.get().getAdoptedOutcomeProtocolVersion()!=1)
                throw new SecurityException("真实adopted非v1，不能由前置能力追认中止");
            if(known.isPresent()&&known.get().getTerminalDecision()!=0)return known.get().getTerminalDecision();
            var seen=db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            List<GlobalTicketRow> original=new ArrayList<>();for(var member:seen.getMembersList())original.add(db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow());
            owners(c,original);if(seen.getState()<4)units.beforeFinish(c,original);
            for(var p:original.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList())locked(c,p.getPlayerId());
            var f=db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            var maybe=db.findOneByPkForUpdate(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build());
            if(maybe.isEmpty())return 0;var t=maybe.get();if(t.getTerminalDecision()!=0)return t.getTerminalDecision();
            if(t.getAdoptedOutcomeObserved()&&t.getAdoptedOutcomeProtocolVersion()!=1)
                throw new SecurityException("真实adopted非v1，不能中止");
            // 两端均非负且先核stamp>=deadline，再作差，避免deadline+宽限溢出误中止。
            long stamp=now(c);
            if(t.getOutcomeProtocolVersion()!=1||t.getPreflightSupportedVersion()!=1||!t.getPreflightInstanceId().equals(f.getInstanceId())
                    ||!t.getPlacementOperationId().equals(f.getOperationId())||!t.getTargetInstanceId().equals(f.getInstanceId())
                    ||f.getState()<2||f.getState()>4||f.getCreateRequest().isEmpty()||f.getDeadlineMs()<=0
                    ||stamp<f.getDeadlineMs()||stamp-f.getDeadlineMs()<TERMINAL_OUTCOME_GRACE_MS)return 0;
            var create=com.game.proto.CreateBattleRequest.parseFrom(f.getCreateRequest());
            if(create.getOutcomeProtocolVersion()!=1)return 0;
            validateCreateBinding(f,create);
            db.updateByPk(c,t.toBuilder().setTerminalDecision(2).build());
            db.updateByPk(c,f.toBuilder().setState(5).build());
            List<GlobalTicketRow> abortedTickets=new ArrayList<>();
            for(var member:f.getMembersList()) {
                var p=locked(c,member.getPlayerId());if(p.getFormationId()!=battle||!p.getTicket().equals(member.getTicket()))throw new SecurityException("中止原票已变更");
                var aborted=p.toBuilder().setState(4).build();db.updateByPk(c,aborted);abortedTickets.add(aborted);
            }
            if(f.getState()<4)units.finished(c,f,abortedTickets,false,now(c));
            return 2;
        });
    }

    public void prepared(GlobalFormationRow work, GlobalPreparedEndpoint endpoint) {
        long battle=work.getBattleId();
        tx(c -> {
            var row = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            requireWork(c,row,work);
            if (row.getState() == 1 && row.getPreparedList().stream().noneMatch(e -> e.getPlayerId() == endpoint.getPlayerId()))
                db.updateByPk(c,row.toBuilder().addPrepared(endpoint).build());
            return null;
        });
    }
    public void unknown(GlobalFormationRow work) {
        long battle=work.getBattleId();
        tx(c -> {
            var row = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            requireWork(c,row,work);
            db.updateByPk(c,row.toBuilder().setState(3).build());
            return null;
        });
    }
    public void finish(GlobalFormationRow work, boolean success) {
        finish(work,success,false);
    }
    /** 没有发房原件的安全出口：同一事务围栏工作代次、账号原件及全部票据，绝不创建终局行。 */
    public GlobalFormationRow finishUncreated(GlobalFormationRow work){
        return finish(work,false,true);
    }
    private GlobalFormationRow finish(GlobalFormationRow work,boolean success,boolean uncreatedOnly){
        long battle=work.getBattleId();
        return tx(c -> {
            // COMPLETE可能早于Create回包。只读同一永久原件即可幂等返回，不能再锁/改已结束unit或下一局票据。
            var committed=db.findOneByPk(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build());
            if(uncreatedOnly){
                var original=db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
                requireUncreated(original,work,committed.isPresent());
                // SQL 已提交但回包丢失：只重读原失败，不锁/改之后的新票，更不能重开同 battle_id。
                if(original.getState()==5)return original;
            }
            if(committed.isPresent()&&committed.get().getAdoptedOutcomeObserved()&&committed.get().getAdoptedOutcomeProtocolVersion()!=1)
                throw new SecurityException("真实adopted非v1，不能完成或中止");
            if(committed.isPresent()&&committed.get().getTerminalDecision()!=0) {
                if(committed.get().getOutcomeProtocolVersion()!=1||committed.get().getPreflightSupportedVersion()!=1
                        ||committed.get().getTerminalDecision()!=1&&committed.get().getTerminalDecision()!=2)
                    throw new SecurityException("终局裁决不明，不能由迟到finish追认");
                var original=db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
                if(!original.getOperationId().equals(work.getOperationId())||!original.getInstanceId().equals(work.getInstanceId())
                        ||!committed.get().getPlacementOperationId().equals(work.getOperationId())
                        ||!committed.get().getTargetInstanceId().equals(work.getInstanceId()))throw new SecurityException("迟到finish原局身份不同");
                if(success!=(committed.get().getTerminalDecision()==1))throw new IllegalStateException("迟到finish与永久SQL终局相反");
                return original;
            }
            List<GlobalTicketRow> originals=new ArrayList<>();
            for(var member:work.getMembersList())originals.add(db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(member.getPlayerId()).build()).orElseThrow());
            owners(c,originals);
            var originalUnits=units.beforeFinish(c,originals);
            Map<Long,GlobalTicketRow> lockedTickets=new HashMap<>();for(var original:originals.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList())lockedTickets.put(original.getPlayerId(),locked(c,original.getPlayerId()));
            var f = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            var terminal=db.findOneByPkForUpdate(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build());
            if(uncreatedOnly)requireUncreated(f,work,terminal.isPresent());
            if(terminal.isPresent()&&terminal.get().getOutcomeProtocolVersion()==1
                    &&(terminal.get().getAdoptedOutcomeObserved()&&terminal.get().getAdoptedOutcomeProtocolVersion()!=1
                        ||success&&!terminal.get().getAdoptedOutcomeObserved()&&terminal.get().getTerminalDecision()!=1))
                throw new SecurityException("新终局缺少实际adopted或存在真实不兼容回执");
            if(terminal.isPresent()&&terminal.get().getTerminalDecision()!=0) {
                if(!terminal.get().getPlacementOperationId().equals(work.getOperationId())
                        ||!terminal.get().getTargetInstanceId().equals(work.getInstanceId())
                        ||terminal.get().getTerminalDecision()!=1&&terminal.get().getTerminalDecision()!=2)
                    throw new SecurityException("并发终局不是原finish所属原件");
                if(success!=(terminal.get().getTerminalDecision()==1))throw new IllegalStateException("迟到finish与永久SQL终局相反");
                return f;
            }
            if(!success&&terminal.isPresent()&&terminal.get().getOutcomeProtocolVersion()==1)
                throw new IllegalStateException("已有v1 Create但尚无SQL ABORT，保留原票且禁止重排");
            requireWork(c,f,work);
            long stamp=now(c);List<GlobalTicketRow> after=new ArrayList<>();
            for (var m : f.getMembersList().stream().sorted((a,b) -> Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList()) {
                var row = lockedTickets.get(m.getPlayerId());
                if (row.getFormationId() != battle || !row.getTicket().equals(m.getTicket())) throw new IllegalStateException("成局票据不变量损坏");
                var next = row.toBuilder().setState(success ? 3 : 4);
                var unit=originalUnits.get(row.getQueueUnitId());
                boolean canRequeue=unit==null||unit.getExpiresMs()>stamp&&(unit.getTeamId()==0||unit.getTargetSize()>unit.getMemberCount());
                if (!success && f.getRequeueOnFailure()&&canRequeue) next.setState(1).setFormationId(0).setSelectedRegion(0).setNotBeforeMs(stamp+1000);
                db.updateByPk(c,next.build());regions.replace(c,next.build(),stamp);after.add(next.build());
            }
            units.finished(c,f,after,success,stamp);
            var finished=f.toBuilder().setState(success ? 4 : 5).build();
            db.updateByPk(c,finished);return finished;
        });
    }
    private static void requireUncreated(GlobalFormationRow original,GlobalFormationRow work,boolean hasCompletion){
        if(!original.getOperationId().equals(work.getOperationId())||!original.getInstanceId().equals(work.getInstanceId())
                ||!original.getMembersList().equals(work.getMembersList())||!original.getCreateRequest().isEmpty()||hasCompletion)
            throw new SecurityException("无发房出口必须核同一成局原件为空且没有终局来源");
    }
    public void releaseReady(long player, String ticket, long battle) {
        tx(c -> { var original=db.findOneByPk(c,GlobalTicketRow.newBuilder().setPlayerId(player).build()).orElseThrow();
            owner(c,player,original.getOwnerAccount(),original.getOwnerRevision());var row=locked(c,player);
            var terminal=db.findOneByPk(c,com.game.match.completion.pb.BattleCompletionRow.newBuilder().setBattleId(battle).build());
            if(terminal.isPresent()&&terminal.get().getOutcomeProtocolVersion()==1&&terminal.get().getTerminalDecision()!=2) {
                var personal=db.findOneByPk(c,com.game.match.completion.pb.BattlePlayerCompletionRow.newBuilder().setBattleId(battle).setPlayerId(player).build());
                if(terminal.get().getTerminalDecision()!=1||personal.isEmpty()||!personal.get().getApplied())throw new IllegalStateException("Inbox未实际APPLIED，保留ready占用");
            }
            if(row.getState()==3 && row.getFormationId()==battle && row.getTicket().equals(ticket))
            db.updateByPk(c,row.toBuilder().setState(4).build()); return null; });
    }
}
