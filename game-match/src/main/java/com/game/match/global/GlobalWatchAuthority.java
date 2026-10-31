package com.game.match.global;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.match.global.pb.*;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer;
import com.game.match.spectate.SpectateRules;
import com.game.match.spectate.SpectateStore;
import com.game.match.spectate.SpectateVisibilityPolicy;
import com.game.pbmysql.PbMysql;
import com.game.proto.AddObserverRequest;
import com.game.proto.RemoveObserverResponse;
import com.game.proto.QueryObserverResponse;
import com.game.proto.match.WatchBattleRequest;
import com.game.proto.match.WatchBattleResponse;
import com.google.protobuf.ByteString;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

/**
 * 观战原件与匹配票共享TiDB和P首锁；网络只在原Add提交以后发，取消未知不终结。
 *
 * <p>观战可见性在建房原件里冻结（{@code create_request} 的 {@code spectate_visibility}），同一笔事务写进成局的 {@code spectate_public} 列（{@code GlobalMatchStore.saveIntent}）：
 * 列表与随机选场在 SQL 里按这一列过滤（索引 {@code state,spectate_public,created_ms,battle_id}），不解码原件；按 id 读出的落点记录带上原件里的可见性交给调用方判定，
 * 原 Add 提交点再按原件复核一次。
 */
public final class GlobalWatchAuthority {
    /** {@link #list} 入参的上限。 */
    static final int LIST_MAX_ROWS = 100;
    /** 可观战的成局：已建房、公开、没过期（参数是过期分界）。 */
    private static final String WATCHABLE = "state=4 AND spectate_public=1 AND created_ms>=?";
    private final AuthorityJdbc sql;
    private final GlobalMatchStore publication;
    private final PbMysql db=new PbMysql();
    public GlobalWatchAuthority(DataSource source,GlobalMatchStore schema) {
        this.publication=Objects.requireNonNull(schema);sql=new AuthorityJdbc(source);db.register(GlobalWatchingRow.getDefaultInstance());db.register(GlobalTicketRow.getDefaultInstance());db.register(GlobalFormationRow.getDefaultInstance());
        db.register(GlobalWatchRequestRow.getDefaultInstance());db.register(GlobalWatchHeadRow.getDefaultInstance());
    }
    private static SessionContext original(GlobalWatchingRow row){return SessionContext.newBuilder().setPlayerId(row.getPlayerId()).setAccount(row.getOwnerAccount()).setOwnerRevision(row.getOwnerRevision()).build();}
    private <T>T tx(SessionContext actor,Deadline deadline,AuthorityJdbc.Work<T> work) {
        try{return publication.watchPublication(()->{try{return (actor==null?sql:sql.forActor(actor.getPlayerId(),actor.getAccount(),actor.getOwnerRevision())).tx(deadline,work);}catch(SQLException failure){throw new Deadline.DependencyException("观战提交未知，原件留存",failure);}});}
        catch(RuntimeException failure){throw new Deadline.DependencyException("全球观战SQL原件操作尚未确认",failure);}
    }
    private static long now(AuthorityJdbc.Session c)throws SQLException {
        Long stamp=c.one("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",row->row.getLong(1));
        if(stamp==null)throw new SQLException("缺少SQL观战时钟");return stamp;
    }
    private static void subject(SessionContext actor,long player){if(actor==null||actor.getPlayerId()!=player)throw new SecurityException("观战缺少原会话主体");}
    private GlobalWatchRequestRow history(AuthorityJdbc.Session c,SessionContext actor,String request)throws SQLException {
        var value=db.findOneByPkForUpdate(c.connection(),GlobalWatchRequestRow.newBuilder().setPlayerId(actor.getPlayerId()).setRequestId(request).build()).orElseThrow();
        if(!value.getOwnerAccount().equals(actor.getAccount())||value.getOwnerRevision()!=actor.getOwnerRevision())throw new SecurityException("观战UUID属于旧归属");return value;
    }
    private boolean latest(AuthorityJdbc.Session c,long player,String request)throws SQLException {
        return db.findOneByPkForUpdate(c.connection(),GlobalWatchHeadRow.newBuilder().setPlayerId(player).build()).map(v->v.getRequestId().equals(request)).orElse(false);
    }
    private void closed(AuthorityJdbc.Session c,GlobalWatchingRow current)throws SQLException {
        var receipt=history(c,original(current),current.getOperationId());
        if(!receipt.getOriginalAdd().equals(current.getAddRequest()))throw new SQLException("原观战永久Add与当前取消原件不同");
        db.updateByPk(c.connection(),receipt.toBuilder().setCancelled(true).build());
    }
    public record Request(GlobalWatchRequestRow original,boolean latest) {}
    public Request begin(SessionContext actor,WatchBattleRequest request,Deadline deadline) {
        subject(actor,actor.getPlayerId());GlobalMatchStore.validateRequestId(request.getRequestId());
        ByteString bytes=ByteString.copyFrom(com.game.common.token.NetworkProbes.bytes(request));
        return tx(actor,deadline,c->{
            var key=GlobalWatchRequestRow.newBuilder().setPlayerId(actor.getPlayerId()).setRequestId(request.getRequestId()).build();
            var prior=db.findOneByPkForUpdate(c.connection(),key);
            if(prior.isPresent()) {
                var original=history(c,actor,request.getRequestId());
                if(!original.getRequestPayload().equals(bytes))throw new SecurityException("观战UUID载荷发生变化");
                return new Request(original,latest(c,actor.getPlayerId(),request.getRequestId()));
            }
            publication.requireNewRoot();
            var original=key.toBuilder().setOwnerAccount(actor.getAccount()).setOwnerRevision(actor.getOwnerRevision()).setRequestPayload(bytes).setCreatedMs(now(c)).build();
            if(queued(c,actor.getPlayerId())){
                original=original.toBuilder().setResponsePayload(com.game.match.support.MatchTips.watchRejected(com.game.match.support.MatchTip.WATCH_QUEUED).toByteString()).build();
                db.insert(c.connection(),original);return new Request(original,false);
            }
            db.insert(c.connection(),original);
            db.upsert(c.connection(),GlobalWatchHeadRow.newBuilder().setPlayerId(actor.getPlayerId()).setRequestId(request.getRequestId()).build());
            return new Request(original,true);
        });
    }
    public long select(SessionContext actor,String request,long battle,Deadline deadline) {
        return tx(actor,deadline,c->{var prior=history(c,actor,request);
            if(!latest(c,actor.getPlayerId(),request))throw new SecurityException("观战请求已被后续UUID替代");
            if(prior.getBattleId()!=0)return prior.getBattleId();
            if(battle==0)throw new IllegalArgumentException("不能保存空观战选择");
            db.updateByPk(c.connection(),prior.toBuilder().setBattleId(battle).build());return battle;});
    }
    public WatchBattleResponse respond(SessionContext actor,String request,WatchBattleResponse response,Deadline deadline) {
        return tx(actor,deadline,c->receipt(c,actor,request,response));
    }
    /** 写永久回执（先写为准）：已有回执就读回首答。调用方已在持 P 锁的事务里。 */
    private WatchBattleResponse receipt(AuthorityJdbc.Session c,SessionContext actor,String request,WatchBattleResponse response)throws SQLException {
        var prior=history(c,actor,request);
        if(!prior.getResponsePayload().isEmpty()) {
            try{return WatchBattleResponse.parseFrom(prior.getResponsePayload());}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("观战回执损坏",bad);}
        }
        db.updateByPk(c.connection(),prior.toBuilder().setResponsePayload(response.toByteString()).build());return response;
    }
    private GlobalWatchingRow row(AuthorityJdbc.Session c,long player,boolean lock)throws SQLException {
        var key=GlobalWatchingRow.newBuilder().setPlayerId(player).build();
        return (lock?db.findOneByPkForUpdate(c.connection(),key):db.findOneByPk(c.connection(),key)).orElse(null);
    }
    private static void sameOwner(GlobalWatchingRow row,SessionContext actor) {
        if(row!=null&&row.getState()!=5&&(!row.getOwnerAccount().equals(actor.getAccount())||row.getOwnerRevision()!=actor.getOwnerRevision()))throw new SecurityException("原观战归属与会话不同");
    }
    private boolean queued(AuthorityJdbc.Session c,long player)throws SQLException {
        return com.game.common.player.MatchEntryAuthority.occupied(c.connection(),player,"",0,3);
    }
    public SpectateStore.Entry entry(SessionContext actor,long player,Deadline deadline) {
        subject(actor,player);return tx(actor,deadline,c->{boolean queued=queued(c,player);var row=row(c,player,true);sameOwner(row,actor);
            return new SpectateStore.Entry(queued,row==null||row.getState()==5?Optional.empty():Optional.of(row.getMark()));});
    }
    public SpectateStore.Acquire reserve(SessionContext actor,long player,String mark,Deadline deadline) {
        subject(actor,player);String operation=mark.substring(mark.indexOf(':')+1);GlobalMatchStore.validateRequestId(operation);
        return tx(actor,deadline,c->{
            var request=history(c,actor,operation);
            if(!latest(c,player,operation)||request.getCancelled()||!mark.equals(Long.toUnsignedString(request.getBattleId())+":"+operation)||!request.getResponsePayload().isEmpty())return SpectateStore.Acquire.BUSY;
            if(queued(c,player))return SpectateStore.Acquire.QUEUED;
            var previous=row(c,player,true);sameOwner(previous,actor);
            if(previous!=null&&previous.getMark().equals(mark))return previous.getState()==5?SpectateStore.Acquire.BUSY:SpectateStore.Acquire.OK;
            if(previous!=null&&previous.getState()!=5)return SpectateStore.Acquire.BUSY;
            long stamp=now(c);var reserved=GlobalWatchingRow.newBuilder().setPlayerId(player).setMark(mark).setOperationId(operation)
                    .setOwnerAccount(actor.getAccount()).setOwnerRevision(actor.getOwnerRevision()).setState(1).setCreatedMs(stamp)
                    .setRetryMs(stamp+30_000).setExpiresMs(stamp+MatchBudgets.WATCHING_TTL_SECONDS*1000L).build();
            db.upsert(c.connection(),reserved);return SpectateStore.Acquire.OK;
        });
    }
    public AddObserverRequest prepare(SessionContext actor,long player,String mark,BattlePlacement placement,AddObserverRequest request,Deadline deadline) {
        subject(actor,player);return tx(actor,deadline,c->{
            String operation=mark.substring(mark.indexOf(':')+1);
            if(!latest(c,player,operation))throw new SecurityException("观战原请求已被替代");
            var receipt=history(c,actor,operation);
            if(receipt.getCancelled()||!receipt.getResponsePayload().isEmpty())throw new SecurityException("原UUID已回复或取消，不能迟到发起Add");
            var current=row(c,player,true);sameOwner(current,actor);
            if(current!=null&&current.getMark().equals(mark)&&(current.getState()==2||current.getState()==3)) {
                if(!receipt.getOriginalAdd().equals(current.getAddRequest())||receipt.getOriginalAdd().isEmpty())throw new SQLException("原观战永久Add已被替换");
                try{return AddObserverRequest.parseFrom(current.getAddRequest());}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("原Add损坏",bad);}
            }
            if(current==null||!current.getMark().equals(mark)||current.getState()!=1||queued(c,player))throw new IllegalStateException("观战原保留已取消或已有匹配票");
            long battle=Long.parseUnsignedLong(mark.substring(0,mark.indexOf(':')));
            var formation=db.findOneByPk(c.connection(),GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            if(formation.getState()!=4||formation.getBattleId()!=request.getBattleId()||request.getObserverPlayerId()!=player
                    ||placement.getBattleId()!=formation.getBattleId()||!placement.getBattleInstanceId().equals(formation.getInstanceId())
                    ||!placement.getRpcHost().equals(formation.getHost())||placement.getRpcPort()!=formation.getPort())throw new SecurityException("观战原固定落点已改变");
            // 提交点按同一份建房原件复核可见性（入口已拒私密局，这里是许可 Add 前的最后一道）
            if(!SpectateVisibilityPolicy.createRequestPublic(formation.getCreateRequest()))throw new SecurityException("观战目标不是公开局");
            long stamp=now(c),admit=stamp+Math.max(1,Math.min(30_000,deadline.remainingMillis()));
            var original=request.toBuilder().setSpectatorOperationId(current.getOperationId()).setAdmitBeforeMs(admit).setTargetInstanceId(formation.getInstanceId()).build();
            if(!receipt.getOriginalAdd().isEmpty())throw new SQLException("原Add只能许可一次");
            db.updateByPk(c.connection(),receipt.toBuilder().setOriginalAdd(original.toByteString()).build());
            db.updateByPk(c.connection(),current.toBuilder().setState(2).setAddRequest(original.toByteString()).setPlacement(placement.toByteString())
                    .setAdmitBeforeMs(admit).setRetryMs(admit).build());return original;
        });
    }
    public void observed(SessionContext actor,long player,String mark,ObserverDialer.Outcome outcome,Deadline deadline) {
        subject(actor,player);tx(actor,deadline,c->{var current=row(c,player,true);sameOwner(current,actor);
            applyObserved(c,current,mark,outcome);return null;});
    }
    /**
     * battle 回「直连席已满」且中继可用：原件转待取消（恢复循环凭原 Remove 关闭）与 LOBBY 回执<b>同一笔事务</b>落库（mmorpg battle-spectate-tiered-fanout.md §15.6）。
     * 分两笔的话，中间失败时恢复循环会先把原件关掉（置 cancelled），同 UUID 重试只能得到「当前无法观战」，这次请求就再也转不了大厅档。
     * 打开中继与签凭证都在这之后做，成败只影响本次应答：重放按 LOBBY 回执重新准入。
     *
     * @param lobby 不带凭证的 LOBBY 回执（凭证有 TTL，不进回执）
     * @return 持久回执（并发先落的首答优先）
     */
    public WatchBattleResponse observedFull(SessionContext actor,long player,String mark,ObserverDialer.Outcome outcome,WatchBattleResponse lobby,Deadline deadline) {
        subject(actor,player);return tx(actor,deadline,c->{var current=row(c,player,true);sameOwner(current,actor);
            applyObserved(c,current,mark,outcome);
            return receipt(c,actor,mark.substring(mark.indexOf(':')+1),lobby);});
    }
    private void applyObserved(AuthorityJdbc.Session c,GlobalWatchingRow current,String mark,ObserverDialer.Outcome outcome)throws SQLException {
        if(current==null||!current.getMark().equals(mark)||current.getState()!=2)return;
        if(outcome instanceof ObserverDialer.Outcome.Unknown)return;
        boolean accepted=outcome instanceof ObserverDialer.Outcome.Replied replied&&replied.tipId()==0;
        db.updateByPk(c.connection(),current.toBuilder().setState(accepted?3:4).setRetryMs(now(c)+1000).build());
    }
    public boolean active(SessionContext actor,long player,String mark,Deadline deadline) {
        subject(actor,player);return tx(actor,deadline,c->{var current=row(c,player,true);sameOwner(current,actor);return current!=null&&current.getMark().equals(mark)&&current.getState()==3;});
    }
    public GlobalWatchingRow cancel(SessionContext actor,long player,String mark,Deadline deadline) {
        GlobalWatchingRow seen=read(player,deadline);if(seen==null||!seen.getMark().equals(mark)||seen.getState()==5)return null;
        SessionContext authority=actor==null?original(seen):actor;subject(authority,player);
        return tx(authority,deadline,c->{var current=row(c,player,true);sameOwner(current,authority);
            if(current==null||!current.getMark().equals(mark)||current.getState()==5)return null;
            if(current.getState()<1||current.getState()>4)throw new SQLException("原观战状态未知");
            // 从未许可发Add时可在原P/mark锁下终结，迟到prepare会看到closed而拒绝。
            var next=current.toBuilder().setState(current.getAddRequest().isEmpty()?5:4).setRetryMs(now(c)).build();if(next.getState()==5)closed(c,next);db.updateByPk(c.connection(),next);return next;});
    }
    public GlobalWatchingRow cancelForRequest(SessionContext actor,String request,String mark,Deadline deadline) {
        return tx(actor,deadline,c->{
            history(c,actor,request);if(!latest(c,actor.getPlayerId(),request))throw new SecurityException("旧观战请求不得清退新观战");
            var current=row(c,actor.getPlayerId(),true);sameOwner(current,actor);
            if(current==null||current.getState()==5||!current.getMark().equals(mark))return null;
            if(current.getState()<1||current.getState()>4)throw new SQLException("观战状态未知");
            var cancelled=current.toBuilder().setState(current.getAddRequest().isEmpty()?5:4).setRetryMs(now(c)).build();if(cancelled.getState()==5)closed(c,cancelled);db.updateByPk(c.connection(),cancelled);return cancelled;
        });
    }
    public GlobalWatchingRow cancelExit(com.game.proto.ObserverExitOriginal exit,Deadline deadline) {
        var actor=SessionContext.newBuilder().setPlayerId(exit.getPlayerId()).setAccount(exit.getOwnerAccount()).setOwnerRevision(exit.getOwnerRevision()).build();
        return tx(actor,deadline,c->{
            var receipt=db.findOneByPkForUpdate(c.connection(),GlobalWatchRequestRow.newBuilder().setPlayerId(exit.getPlayerId()).setRequestId(exit.getOperationId()).build());
            if(receipt.isPresent()&&receipt.get().getCancelled()) {
                if(!receipt.get().getOwnerAccount().equals(exit.getOwnerAccount())||receipt.get().getOwnerRevision()!=exit.getOwnerRevision()
                        ||!receipt.get().getOriginalAdd().equals(exit.hasAdd()?exit.getAdd().toByteString():ByteString.EMPTY))throw new SecurityException("退出取消回执未绑定原件");return null;
            }
            var current=row(c,exit.getPlayerId(),true);sameOwner(current,actor);
            // 原意图已在P锁内持久捕获并封闭旧head。恢复只处理原op，后续head不阻旧op正证明。
            // 绝不据当前新head补抓新的Add；原op已许可载荷必须与永久history一致。
            if(receipt.isEmpty()||!receipt.get().getOriginalAdd().equals(exit.hasAdd()?exit.getAdd().toByteString():ByteString.EMPTY))throw new SQLException("退出缺少永久原Add");
            if(!exit.hasAdd()&&(current==null||!current.getOperationId().equals(exit.getOperationId()))){
                db.updateByPk(c.connection(),receipt.get().toBuilder().setCancelled(true).build());return null;
            }
            if(current==null||current.getState()==5){
                if(exit.hasAdd())throw new SQLException("退出原Add缺少取消正证明");
                var pending=history(c,actor,exit.getOperationId());if(!pending.getOriginalAdd().isEmpty())throw new SQLException("未许可Add退出原件已变化");
                db.updateByPk(c.connection(),pending.toBuilder().setCancelled(true).build());return null;
            }
            if(!current.getOperationId().equals(exit.getOperationId())||!current.getAddRequest().equals(exit.hasAdd()?exit.getAdd().toByteString():ByteString.EMPTY))throw new SecurityException("退出只能处理已捕获的原观战操作");
            if(exit.hasAdd())try{
                var p=BattlePlacement.parseFrom(current.getPlacement());
                if(!p.getRpcHost().equals(exit.getRpcHost())||p.getRpcPort()!=exit.getRpcPort()||p.getBattleNodeId()!=exit.getNodeId()
                        ||!p.getBattleInstanceId().equals(exit.getInstanceId())||!exit.getInstanceId().equals(exit.getAdd().getTargetInstanceId()))throw new SQLException("退出固定RPC目标与原件不同");
            }catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("退出原落点损坏",bad);}
            var cancelled=current.toBuilder().setState(exit.hasAdd()?4:5).setRetryMs(now(c)).build();if(!exit.hasAdd())closed(c,cancelled);db.updateByPk(c.connection(),cancelled);return cancelled;
        });
    }
    public GlobalWatchingRow read(long player,Deadline deadline) {
        try{return sql.read(deadline,c->row(c,player,false));}catch(SQLException failure){throw new Deadline.DependencyException("读取原观战记录失败",failure);}
    }
    public SpectateStore.Snapshot formation(long battle,Deadline deadline) {
        return tx(null,deadline,c->{long stamp=now(c);var f=db.findOneByPk(c.connection(),GlobalFormationRow.newBuilder().setBattleId(battle).build());
            if(f.isEmpty()||f.get().getState()!=4)return new SpectateStore.Snapshot(false,new SpectateStore.Record.Absent(),stamp);
            return new SpectateStore.Snapshot(true,new SpectateStore.Record.Found(placement(f.get())),stamp);});
    }
    public static BattlePlacement placement(GlobalFormationRow f)throws SQLException {
        try {
            var p=BattlePlacement.newBuilder().setBattleId(f.getBattleId()).setMode(f.getMode()).setBattleConfigId(f.getConfigId()).setCreatedAtMs(f.getCreatedMs())
                .setDeadlineMs(f.getDeadlineMs()).setBattleNodeId(f.getNodeId()).setBattleInstanceId(f.getInstanceId()).setRpcHost(f.getHost()).setRpcPort(f.getPort()).setAttempt(1);
            var create=com.game.proto.CreateBattleRequest.parseFrom(f.getCreateRequest());
            create.getPlayersList().forEach(v->p.addPlayerNames(v.getPlayerName()));
            // 带上建房原件里冻结的可见性：163 按 id 观战与 164 列表据此拒私密局
            return p.setSpectateVisibility(create.getSpectateVisibilityValue()).build();
        }catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("SQL固定建房原件损坏",bad);}
    }
    public SpectateStore.Listed list(int limit,Deadline deadline) {
        if(limit<1||limit>LIST_MAX_ROWS)throw new IllegalArgumentException("观战列表超出上限");
        // 私密局不进列表：SQL 直接按冻结的可见性列过滤，私密局再多也不占位
        return tx(null,deadline,c->{long stamp=now(c);var rows=db.findAll(c.connection(),GlobalFormationRow.class,WATCHABLE+" ORDER BY created_ms DESC,battle_id DESC LIMIT "+limit,SpectateRules.staleCutoff(stamp));
            return new SpectateStore.Listed(rows.stream().map(f->new SpectateStore.Scored(Long.toUnsignedString(f.getBattleId()),f.getCreatedMs())).toList(),stamp);});
    }
    public SpectateStore.Pick pick(double random,Deadline deadline) {
        if(!(random>=0&&random<1))throw new IllegalArgumentException("观战随机数范围无效");
        return tx(null,deadline,c->{long stamp=now(c),cutoff=SpectateRules.staleCutoff(stamp);
            long count=c.count("SELECT COUNT(*) FROM match_global_formation WHERE "+WATCHABLE,cutoff);
            if(count==0)return new SpectateStore.Pick.None(stamp);
            long offset=Math.min((long)(random*count),count-1);
            var rows=db.findAll(c.connection(),GlobalFormationRow.class,WATCHABLE+" ORDER BY created_ms,battle_id LIMIT 1 OFFSET "+offset,cutoff);
            // 两条语句之间这一行可能刚好过了分界：按挑不到收场，客户端重试
            if(rows.isEmpty())return new SpectateStore.Pick.None(stamp);
            var f=rows.get(0);
            return new SpectateStore.Pick.Member(Long.toUnsignedString(f.getBattleId()),f.getCreatedMs(),stamp);});
    }
    /** 规模采样（{@code game_match_watchable_battles}）：只数公开局，与列表同口径。 */
    public long watchableCount(Deadline deadline) {return tx(null,deadline,c->c.count("SELECT COUNT(*) FROM match_global_formation WHERE "+WATCHABLE,SpectateRules.staleCutoff(now(c))));}
    public Map<Long,String> marks(List<Long> players,Deadline deadline) {
        if(players.size()>10)throw new IllegalArgumentException("观战原名单超过10人");
        try{return sql.read(deadline,c->{Map<Long,String> result=new LinkedHashMap<>();for(long player:new LinkedHashSet<>(players)){var row=row(c,player,false);if(row!=null&&row.getState()!=5)result.put(player,row.getMark());}return result;});}
        catch(SQLException failure){throw new Deadline.DependencyException("读取原观战名单失败",failure);}
    }
    public List<GlobalWatchingRow> pending(long afterMs,long afterPlayer,Deadline deadline) {
        return pending(afterMs,afterPlayer,Long.MAX_VALUE,-1L,deadline);
    }
    private static final Comparator<GlobalWatchingRow> WATCH_CURSOR=Comparator.comparingLong(GlobalWatchingRow::getRetryMs).thenComparing((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId()));
    public GlobalWatchingRow pendingTail(Deadline deadline){return tx(null,deadline,c->{
        List<GlobalWatchingRow> tails=new ArrayList<>();long stamp=now(c);
        for(int state=1;state<=4;state++)tails.addAll(db.findAll(c.connection(),GlobalWatchingRow.class,"state=? AND retry_ms<=? ORDER BY retry_ms DESC,player_id DESC LIMIT 1",state,stamp));
        return tails.stream().max(WATCH_CURSOR).orElse(null);
    });}
    public List<GlobalWatchingRow> pending(long afterMs,long afterPlayer,long upperMs,long upperPlayer,Deadline deadline) {
        return tx(null,deadline,c->{List<GlobalWatchingRow> bounded=new ArrayList<>();long stamp=now(c);
            // 每个状态的复合索引最多取8项，合并最多32项；不能IN四态后扫描全表排序再LIMIT。
            for(int state=1;state<=4;state++)bounded.addAll(db.findAll(c.connection(),GlobalWatchingRow.class,"state=? AND retry_ms<=? AND (retry_ms>? OR (retry_ms=? AND player_id>?)) AND (retry_ms<? OR (retry_ms=? AND player_id<=?)) ORDER BY retry_ms,player_id LIMIT 8",state,stamp,afterMs,afterMs,AuthorityJdbc.u64(afterPlayer),upperMs,upperMs,AuthorityJdbc.u64(upperPlayer)));
            return bounded.stream().sorted(WATCH_CURSOR).limit(8).toList();
        });
    }
    public GlobalWatchingRow lease(GlobalWatchingRow expected,Deadline deadline) {
        return tx(original(expected),deadline,c->{var current=row(c,expected.getPlayerId(),true);long stamp=now(c);
            if(current==null||!current.getMark().equals(expected.getMark())||current.getState()==5||current.getLeaseUntilMs()>stamp)return null;
            if(current.getState()==1&&current.getAddRequest().isEmpty()&&current.getRetryMs()<=stamp){closed(c,current);db.updateByPk(c.connection(),current.toBuilder().setState(5).build());return null;}
            if(current.getState()!=4&&current.getRetryMs()>stamp)return null;
            if(current.getAddRequest().isEmpty())throw new SQLException("原观战Add缺失，不能补当前载荷");
            var work=current.toBuilder().setState(current.getState()==3&&current.getExpiresMs()>stamp?3:4).setLeaseVersion(Math.addExact(current.getLeaseVersion(),1)).setLeaseUntilMs(stamp+5000).setRetryMs(stamp+5000).build();
            db.updateByPk(c.connection(),work);return work;});
    }
    /** 一次取消尝试的结局。 */
    public enum Settlement {
        /** 还不能关：原实例说观众还在，或者没有证明、也不能确定原实例已不在。 */
        PENDING,
        /** 拿到了原实例的取消正证明，已关闭。 */
        PROVEN,
        /** 没有证明，但成局已终态或已过期限 + 终局宽限（房间必已收尾），已关闭。 */
        FORMATION_ENDED,
        /** 没有证明，但目录里同节点号已是别的实例（房间随原进程没了），已关闭。 */
        INSTANCE_REPLACED
    }
    /**
     * 结算一次取消尝试：原 Remove 的正证明优先；拿不到证明时，原实例已不在也关闭（mmorpg battle-spectate-tiered-fanout.md §15.9）。原实例不在了就永远拿不到证明：
     * 实例不符的 Remove 回空应答，旧地址的 RPC 只会报错；不放宽的话这名玩家的观战、排队、交易冻结、维护排空都要等人工改库。放宽是安全的：观众对战斗状态零写权，
     * 不带证明关闭碰不到资产；原 Add 带 {@code target_instance_id}，新实例不接纳旧 op，旧 op 不会复活；实例其实还活着（丢了租约的僵尸）时最坏只是它多发几帧。
     * 原实例明确回 ACTIVE（{@code stillActive}）时不算不在。关闭点与判定在同一笔持 P 锁的事务里；{@code closed} 置 {@code cancelled}，排队与角色退出的 {@code provedClosed} 随之放行。
     *
     * @param instanceReplaced 目录里同节点号已是别的实例（查不到不算）。在事务外查好传进来：不在持 P 锁的事务里等 Redis
     */
    public Settlement settled(GlobalWatchingRow work,boolean stillActive,RemoveObserverResponse proof,boolean instanceReplaced,Deadline deadline) {
        return tx(original(work),deadline,c->{var current=row(c,work.getPlayerId(),true);long stamp=now(c);
            if(current==null||!current.getMark().equals(work.getMark())||current.getLeaseVersion()!=work.getLeaseVersion()||current.getLeaseUntilMs()<=stamp||(current.getState()!=4&&current.getState()!=3))
                throw new IllegalStateException("原观战取消租约已失效");
            AddObserverRequest add;
            try{add=AddObserverRequest.parseFrom(current.getAddRequest());}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("原Add损坏",bad);}
            boolean cancelled=proof!=null&&proof.getCancelled()&&proof.getSpectatorOperationId().equals(current.getOperationId())&&proof.getTargetInstanceId().equals(add.getTargetInstanceId());
            Settlement result=cancelled?Settlement.PROVEN:stillActive?Settlement.PENDING:sourceGone(c,add.getBattleId(),stamp,instanceReplaced);
            boolean close=result!=Settlement.PENDING;
            if(close)closed(c,current);
            db.updateByPk(c.connection(),current.toBuilder().setState(close?5:current.getState()).setLeaseUntilMs(0).setRetryMs(stamp+1000).build());return result;});
    }
    private Settlement sourceGone(AuthorityJdbc.Session c,long battle,long stamp,boolean instanceReplaced)throws SQLException {
        var formation=db.findOneByPk(c.connection(),GlobalFormationRow.newBuilder().setBattleId(battle).build());
        if(formation.isPresent()&&formationEnded(formation.get(),stamp))return Settlement.FORMATION_ENDED;
        return instanceReplaced?Settlement.INSTANCE_REPLACED:Settlement.PENDING;
    }
    /** 成局的房间必已不在：已确认中止（state 5），或已过权威期限 + 终局宽限（与 {@code GlobalMatchStore} 竞争 ABORT 同一分界）。期限 0 = 没有期限，证明不了什么。{@code stamp} 取 SQL 时钟。 */
    static boolean formationEnded(GlobalFormationRow formation,long stamp) {
        if(formation.getState()==5)return true;
        long limit=formation.getDeadlineMs();
        return limit>0&&stamp>=limit&&stamp-limit>=MatchBudgets.TERMINAL_OUTCOME_GRACE_MS;
    }

    /** 请求历史的保留期：同 UUID 重放窗口是秒级（客户端重试、大厅重连），战斗最长约 1 小时，7 天之后不会再有合法重放。代价：极旧的 UUID 重放会被当成新请求。 */
    static final long REQUEST_RETENTION_MS=7L*24*3600*1000;
    /** 保留期清理的翻页位置。 */
    public record RequestCursor(long createdMs,long player,String request) {
        public static final RequestCursor START=new RequestCursor(0,0,"");
    }
    /** @param next 下一页从哪开始（扫到末尾回到 {@link RequestCursor#START}） @param deleted 这一页删掉的行数 */
    public record RequestPurge(RequestCursor next,int deleted) {}
    /**
     * 这名玩家有捕获过退出原件、还没走完的准入（pending 票 state 0 = Join / direct；Join 意图未完结；Team 角色未释放）：退出原件存在意图与 launch 的 bytes 里，SQL 按行比不了，按玩家从严，
     * 这名玩家的历史一行都不删（{@code ObserverExitAuthority.provedClosed} 缺行会抛异常，这些准入就再也走不完）。
     */
    private static final String NO_PENDING_EXIT=
            " AND NOT EXISTS(SELECT 1 FROM match_global_ticket t WHERE t.player_id=match_global_watch_request.player_id AND t.state=0)"
            +" AND NOT EXISTS(SELECT 1 FROM match_global_join_intent j WHERE j.player_id=match_global_watch_request.player_id AND j.admission_prepared=1 AND j.admission_completed=0)"
            +" AND NOT EXISTS(SELECT 1 FROM team_launch_role r WHERE r.player_id=match_global_watch_request.player_id AND (r.state IS NULL OR r.state<>2))";
    /**
     * 按保留期删一页请求历史：创建超过 7 天、已有回执或已取消、不是请求头、不是当前观战原件、这名玩家没有待退出准入（{@link #NO_PENDING_EXIT}）的行。
     * 大厅档观众每个新 UUID 留一行，不删的话存储无界增长。
     *
     * <p>按 (created_ms, 主键) 翻页：删不掉的行（每名玩家的请求头）会一直留在索引前端，每轮从头取前 N 条的话后面的行永远轮不到。幂等，多副本同时跑无害。不持玩家锁也不会误删：
     * 退出捕获只捕获请求头与当前观战原件；请求头只会移到全新的 UUID，不是请求头、不是当前原件的行不会再变成它们；行被删后旧 UUID 重放重新插入的行 created_ms 是新的，DELETE 上的条件挡住它。
     *
     * @param limit 这一页最多看多少行
     */
    public RequestPurge purgeRequests(RequestCursor after,int limit,Deadline deadline) {
        return tx(null,deadline,c->{
            long cutoff=now(c)-REQUEST_RETENTION_MS;
            // created_ms>= 是给优化器的范围下界，元组比较才是精确的翻页条件
            var page=c.list("SELECT created_ms,player_id,request_id FROM match_global_watch_request WHERE created_ms<? AND created_ms>=? AND (created_ms,player_id,request_id)>(?,?,?) ORDER BY created_ms,player_id,request_id LIMIT "+limit,
                    row->new RequestCursor(row.getLong(1),AuthorityJdbc.u64(row,2),row.getString(3)),cutoff,after.createdMs(),after.createdMs(),AuthorityJdbc.u64(after.player()),after.request());
            if(page.isEmpty())return new RequestPurge(RequestCursor.START,0);
            var candidates=withoutHeadsAndCurrent(c,page);
            int deleted=candidates.isEmpty()?0:deleteRequests(c,candidates,cutoff);
            return new RequestPurge(page.size()<limit?RequestCursor.START:page.getLast(),deleted);
        });
    }
    /** 去掉请求头与当前观战原件。这两列是 MEDIUMTEXT（表缺省 unicode_ci），请求行的主键列是 0900_bin：在 Java 里比，不依赖 SQL 混用排序规则时的取舍。 */
    private static List<RequestCursor> withoutHeadsAndCurrent(AuthorityJdbc.Session c,List<RequestCursor> page)throws SQLException {
        var players=new LinkedHashSet<Long>();
        for(var row:page)players.add(row.player());
        var ids=new ArrayList<Object>();
        for(long player:players)ids.add(AuthorityJdbc.u64(player));
        String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        AuthorityJdbc.Row<String> key=row->AuthorityJdbc.u64(row,1)+":"+row.getString(2);
        var referenced=new HashSet<String>(c.list("SELECT player_id,request_id FROM match_global_watch_head WHERE player_id IN ("+in+")",key,ids.toArray()));
        referenced.addAll(c.list("SELECT player_id,operation_id FROM match_global_watching WHERE player_id IN ("+in+") AND (state IS NULL OR state<>5)",key,ids.toArray()));
        return page.stream().filter(row->!referenced.contains(row.player()+":"+row.request())).toList();
    }
    private static int deleteRequests(AuthorityJdbc.Session c,List<RequestCursor> rows,long cutoff)throws SQLException {
        var args=new ArrayList<Object>();
        for(var row:rows){args.add(AuthorityJdbc.u64(row.player()));args.add(row.request());}
        args.add(cutoff);
        return c.update("DELETE FROM match_global_watch_request WHERE (player_id,request_id) IN ("+String.join(",",Collections.nCopies(rows.size(),"(?,?)"))+") AND created_ms<? AND (cancelled=1 OR OCTET_LENGTH(response_payload)>0)"+NO_PENDING_EXIT,args.toArray());
    }
    public String reissue(SessionContext actor,long battle,Deadline deadline) {
        subject(actor,actor.getPlayerId());return tx(actor,deadline,c->{
            var ticket=db.findOneByPkForUpdate(c.connection(),GlobalTicketRow.newBuilder().setPlayerId(actor.getPlayerId()).build());
            if(ticket.isPresent()&&ticket.get().getState()==3&&ticket.get().getFormationId()==battle
                    &&ticket.get().getOwnerAccount().equals(actor.getAccount())&&ticket.get().getOwnerRevision()==actor.getOwnerRevision())return "";
            var watching=row(c,actor.getPlayerId(),true);sameOwner(watching,actor);
            if(watching==null||(watching.getState()!=2&&watching.getState()!=3)||watching.getAddRequest().isEmpty())throw new SecurityException("没有原参战或观战许可");
            if(!latest(c,actor.getPlayerId(),watching.getOperationId()))throw new SecurityException("原观战补票已被后续UUID替代");
            var receipt=history(c,actor,watching.getOperationId());
            if(receipt.getCancelled()||receipt.getBattleId()!=battle||!receipt.getOriginalAdd().equals(watching.getAddRequest()))throw new SecurityException("原观战永久请求与补票原件不同");
            try{var add=AddObserverRequest.parseFrom(watching.getAddRequest());if(add.getBattleId()!=battle)throw new SecurityException("补签战斗与原观战不同");return watching.getOperationId();}
            catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("补签原Add损坏",bad);}
        });
    }
}
