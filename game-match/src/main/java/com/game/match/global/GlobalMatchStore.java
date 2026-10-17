package com.game.match.global;

import com.game.common.deadline.Deadline;
import com.game.match.global.pb.*;
import com.game.pbmysql.PbMysql;
import com.google.protobuf.ByteString;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** TiDB 是票据、成局、撤销与创建意图唯一权威。事务锁序：玩家无符号升序，随后创建新成局行；恢复先锁成局再锁其专属票。 */
public final class GlobalMatchStore {
    private final DataSource source;
    private final PbMysql db = new PbMysql();
    private final GlobalMatchProperties config;

    public GlobalMatchStore(DataSource source, GlobalMatchProperties config) throws SQLException {
        this.source = source;
        this.config = config;
        db.register(GlobalProfileRow.getDefaultInstance());
        db.register(GlobalTicketRow.getDefaultInstance());
        db.register(GlobalJoinIntentRow.getDefaultInstance());
        db.register(GlobalDirectIntentRow.getDefaultInstance());
        db.register(GlobalFormationRow.getDefaultInstance());
        db.register(GlobalRankSourceRow.getDefaultInstance());
        db.register(GlobalRankOutboxRow.getDefaultInstance());
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
    }

    @FunctionalInterface private interface Work<T> { T apply(Connection c) throws Exception; }
    private <T> T tx(Work<T> work) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try { T result = work.apply(c); c.commit(); return result; }
            catch (Exception e) { try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); } throw e; }
        } catch (Exception e) { throw new Deadline.DependencyException("全球匹配 SQL 操作失败", e); }
    }
    private static long now(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")) {
            if (!r.next()) throw new SQLException("缺少数据库时间");
            return r.getLong(1);
        }
    }
    public long now() { return tx(GlobalMatchStore::now); }
    private GlobalTicketRow locked(Connection c, long player) throws SQLException {
        var key = GlobalTicketRow.newBuilder().setPlayerId(player).build();
        db.insertIgnore(c, key);
        var row = db.findOneByPkForUpdate(c, key).orElseThrow();
        if (row.getState() == 1 && row.getExpiresMs() <= now(c)) {
            row = row.toBuilder().setState(4).build();
            db.updateByPk(c, row);
        }
        return row;
    }
    public GlobalTicketRow ticket(long player) { return tx(c -> locked(c, player)); }
    public GlobalProfileRow profile(long player) { return tx(c -> db.findOneByPk(c,
            GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow()); }
    public void plan(long player, GlobalProbeProfile profile, long expiry) {
        tx(c -> { db.upsert(c, GlobalProfileRow.newBuilder().setPlayerId(player).setPayload(profile.toByteString()).setExpiresMs(expiry).build()); return null; });
    }
    public void commitProfile(long player, GlobalProbeProfile profile, long expiry) {
        tx(c -> {
            var old = db.findOneByPkForUpdate(c, GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow();
            var before = GlobalProbeProfile.parseFrom(old.getPayload());
            if (old.getExpiresMs() <= now(c) || !before.getPlanId().equals(profile.getPlanId())
                    || !before.getSessionBinding().equals(profile.getSessionBinding())) throw new IllegalStateException("探测计划已被替换");
            db.updateByPk(c, old.toBuilder().setPayload(profile.toByteString()).setExpiresMs(expiry).build());
            // 续测不重置票号与等待；只更新同一会话的排队票，旧会话回执不能覆盖重连后的票。
            var ticket = db.findOneByPkForUpdate(c, GlobalTicketRow.newBuilder().setPlayerId(player).build());
            if (ticket.isPresent() && ticket.get().getState() == 1
                    && GlobalProbeProfile.parseFrom(ticket.get().getProfile()).getSessionBinding().equals(profile.getSessionBinding()))
                db.updateByPk(c, ticket.get().toBuilder().setProfile(profile.toByteString()).setExpiresMs(expiry).build());
            return null;
        });
    }
    public GlobalTicketRow join(long player, String request, String plan, String binding, int mode, int configId, int size, int zone, long rating) {
        var intentKey = intentKey(player, request); // 非法 UUID 在获取 SQL 连接前明确拒绝。
        return tx(c -> {
            var old = locked(c, player);
            var intent = db.findOneByPk(c, intentKey);
            if (intent.isPresent()) {
                if (intent.get().getCancelled()) throw new IllegalArgumentException("匹配请求已经撤销");
                var replayed = GlobalTicketRow.parseFrom(intent.get().getTicketRecord());
                if (replayed.getTicket().isEmpty() || replayed.getMode() != mode || replayed.getConfigId() != configId || replayed.getTeamSize() != size)
                    throw new IllegalArgumentException("相同请求号内容不同或原票损坏");
                return old.getTicket().equals(replayed.getTicket()) ? old : replayed.toBuilder().setState(4).build();
            }
            if (old.getRequestId().equals(request)) {
                if (old.getMode() != mode || old.getConfigId() != configId || old.getTeamSize() != size) throw new IllegalArgumentException("相同请求号内容不同");
                db.insert(c, intentKey.toBuilder().setTicketRecord(old.toByteString()).build());
                return old;
            }
            if (old.getState() >= 1 && old.getState() <= 3) return old;
            var profile = db.findOneByPk(c, GlobalProfileRow.newBuilder().setPlayerId(player).build()).orElseThrow();
            var p = GlobalProbeProfile.parseFrom(profile.getPayload());
            long stamp = now(c);
            if (!p.getPlanId().equals(plan) || !p.getSessionBinding().equals(binding) || profile.getExpiresMs() <= stamp
                    || GlobalRegionPolicy.latencies(p, stamp, config.hardLatencyMs()).isEmpty()) throw new IllegalArgumentException("缺少可信 RTT");
            var row = GlobalTicketRow.newBuilder().setPlayerId(player).setTicket(UUID.randomUUID().toString()).setRequestId(request)
                    .setMode(mode).setConfigId(configId).setTeamSize(size).setState(1).setEnqueuedMs(stamp).setZoneId(zone)
                    .setRatingCenti(rating).setProfile(profile.getPayload()).setExpiresMs(profile.getExpiresMs()).build();
            db.updateByPk(c, row);
            db.insert(c, intentKey.toBuilder().setTicketRecord(row.toByteString()).build());
            return row;
        });
    }
    public boolean cancel(long player, String ticket) {
        return cancel(player, ticket, "");
    }
    /** 与 Join 先争同一玩家行锁；即使 Join 尚未落票，撤销也留下不可过期的 SQL 墓碑。 */
    public boolean cancel(long player, String ticket, String request) {
        if (player == 0) return false;
        if (!request.isEmpty()) intentKey(player,request);
        if (request.isEmpty() && (ticket == null || ticket.isEmpty())) throw new IllegalArgumentException("撤销需要原请求编号或票据");
        return tx(c -> {
            var old = locked(c, player);
            String target = request.isEmpty() && old.getTicket().equals(ticket) ? old.getRequestId() : request;
            if (!target.isEmpty()) {
                var key = intentKey(player, target);
                var previous = db.findOneByPk(c, key).orElse(key);
                db.upsert(c, previous.toBuilder().setCancelled(true).build());
            }
            if (old.getState() != 1 || (!target.isEmpty() && !old.getRequestId().equals(target))
                    || (ticket != null && !ticket.isEmpty() && !old.getTicket().equals(ticket))) return false;
            db.updateByPk(c, old.toBuilder().setState(4).build()); return true;
        });
    }
    public boolean requestCancelled(long player, String request) {
        if (request.isEmpty()) return false;
        var key=intentKey(player,request);
        return tx(c -> db.findOneByPk(c, key)
                .map(GlobalJoinIntentRow::getCancelled).orElse(false));
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
                "state=1 AND expires_ms>? AND (enqueued_ms>? OR (enqueued_ms=? AND player_id>?)) ORDER BY enqueued_ms,player_id LIMIT 256",
                now(c), afterMs, afterMs, PbMysql.uint64(afterPlayer)));
    }
    /** 整组校验/占用与创建成局行同一事务；取消与另一个副本成局竞争同一玩家行锁。 */
    public GlobalFormationRow claim(List<GlobalTicketRow> expected, GlobalFormationRow formation, boolean direct) {
        return tx(c -> claim(c,expected,formation,direct));
    }
    private GlobalFormationRow claim(Connection c,List<GlobalTicketRow> expected, GlobalFormationRow formation, boolean direct)throws Exception {
            List<GlobalTicketRow> sorted = expected.stream().sorted((a,b) -> Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList();
            Map<Long,GlobalTicketRow> locked = new HashMap<>();
            for (var target : sorted) locked.put(target.getPlayerId(), locked(c,target.getPlayerId()));
            long stamp = now(c);
            var members = GlobalFormationRow.newBuilder().mergeFrom(formation).clearMembers();
            for (var target : expected) {
                var row = locked.get(target.getPlayerId());
                if (direct) {
                    if (row.getState() >= 1 && row.getState() <= 3) throw new IllegalStateException("整组成员已有票据");
                    var p = db.findOneByPk(c, GlobalProfileRow.newBuilder().setPlayerId(target.getPlayerId()).build()).orElseThrow();
                    row = target.toBuilder().setTicket(target.getTicket().isEmpty()?UUID.randomUUID().toString():target.getTicket()).setState(1).setEnqueuedMs(stamp)
                            .setProfile(p.getPayload()).setExpiresMs(p.getExpiresMs()).build();
                } else if (row.getState() != 1 || !row.getTicket().equals(target.getTicket()) || row.getNotBeforeMs() > stamp)
                    throw new IllegalStateException("成局时票据已变更");
                if (row.getExpiresMs() <= stamp || !GlobalRegionPolicy.latencies(GlobalProbeProfile.parseFrom(row.getProfile()),stamp,config.hardLatencyMs()).containsKey(formation.getRegion()))
                    throw new IllegalStateException("成局时 RTT 已失效");
                db.updateByPk(c, row.toBuilder().setState(2).setFormationId(formation.getBattleId()).setSelectedRegion(formation.getRegion()).build());
                members.addMembers(GlobalMember.newBuilder().setPlayerId(row.getPlayerId()).setTicket(row.getTicket()));
            }
            var result = members.setState(1).setCreatedMs(stamp).setDeadlineMs(stamp+300_000).setRetryMs(stamp).build();
            db.insert(c,result);
            return result;
    }
    public GlobalDirectIntentRow direct(GlobalDirectIntentRow key) { return tx(c->db.findOneByPk(c,key).orElse(null)); }
    public GlobalFormationRow reserveDirect(GlobalDirectIntentRow key,ByteString payload,List<GlobalTicketRow> members,GlobalFormationRow formation) {
        if(key.getKind()<1||key.getKind()>3||key.getOwnerId()==0||(key.getRequestHigh()==0&&key.getRequestLow()==0)||payload.isEmpty()||members.isEmpty()||members.size()>10)throw new IllegalArgumentException("直接开局原意图无效");
        return tx(c->{
            db.insertIgnore(c,key.toBuilder().setPayload(payload).build());
            var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            if(!original.getPayload().equals(payload))throw new IllegalArgumentException("相同业务意图内容不同");
            if(original.getBattleId()!=0)return db.findOneByPk(c,GlobalFormationRow.newBuilder().setBattleId(original.getBattleId()).build()).orElseThrow();
            var result=claim(c,members,formation,true);
            db.updateByPk(c,original.toBuilder().setBattleId(result.getBattleId()).build());return result;
        });
    }
    public boolean declineDirect(GlobalDirectIntentRow key,ByteString payload) {
        if(key.getKind()!=3||key.getOwnerId()==0||(key.getRequestHigh()==0&&key.getRequestLow()==0)||payload.isEmpty())throw new IllegalArgumentException("拒战原意图无效");
        return tx(c->{db.insertIgnore(c,key.toBuilder().setPayload(payload).build());var original=db.findOneByPkForUpdate(c,key).orElseThrow();
            return original.getBattleId()==0&&original.getPayload().equals(payload);});
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
        return tx(c -> {
            var old = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(formation.getBattleId()).build()).orElseThrow();
            requireWork(c,old,formation);
            if (old.getState() == 1) {
                old = old.toBuilder().setCreateRequest(ByteString.copyFrom(request)).build();
                // prepared 全集合与创建意图同时持久化；最后只允许首个创建者写入。
                old = old.toBuilder().clearPrepared().addAllPrepared(formation.getPreparedList()).setState(2).build();
                db.updateByPk(c,old);
            }
            return old;
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
        long battle=work.getBattleId();
        tx(c -> {
            var f = db.findOneByPkForUpdate(c,GlobalFormationRow.newBuilder().setBattleId(battle).build()).orElseThrow();
            requireWork(c,f,work);
            for (var m : f.getMembersList().stream().sorted((a,b) -> Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList()) {
                var row = locked(c,m.getPlayerId());
                if (row.getFormationId() != battle || !row.getTicket().equals(m.getTicket())) throw new IllegalStateException("成局票据不变量损坏");
                var next = row.toBuilder().setState(success ? 3 : 4);
                if (!success && f.getRequeueOnFailure()) next.setState(1).setFormationId(0).setSelectedRegion(0).setNotBeforeMs(now(c)+1000);
                db.updateByPk(c,next.build());
            }
            db.updateByPk(c,f.toBuilder().setState(success ? 4 : 5).build()); return null;
        });
    }
    public void releaseReady(long player, String ticket, long battle) {
        tx(c -> { var row=locked(c,player); if(row.getState()==3 && row.getFormationId()==battle && row.getTicket().equals(ticket))
            db.updateByPk(c,row.toBuilder().setState(4).build()); return null; });
    }
}
