package com.game.match.rating;

import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.completion.pb.BattlePlayerCompletionRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/** 完成来源 Phase A：原件与全员个人来源同事务归档，不负责应用资产或推进独立业务游标。 */
public final class BattleCompletionStore {
    public enum Outcome { ARCHIVED, DUPLICATE, LEGACY }
    static final int MAX_SOURCE_BYTES=960*1024;
    private final DataSource source;
    private final PbMysql db=registry();

    @FunctionalInterface interface SqlClock { long now(Connection connection)throws SQLException; }
    private final SqlClock clock;
    public BattleCompletionStore(DataSource source) { this(source,BattleCompletionStore::databaseNowMillis); }
    /** 同包测试显式推进数据库时钟，避免退避回归依赖真实等待；正式构造始终使用同连接数据库时间。 */
    BattleCompletionStore(DataSource source,SqlClock clock) {
        this.source=java.util.Objects.requireNonNull(source);this.clock=java.util.Objects.requireNonNull(clock);
    }

    public static PbMysql registry() {
        PbMysql db=new PbMysql();
        db.register(BattleCompletionRow.getDefaultInstance());
        db.register(BattlePlayerCompletionRow.getDefaultInstance());
        return db;
    }

    /** 与现有评分建表同口径，只放宽本条 DDL 连接的网络预算。 */
    public static void sync(DataSource source) throws SQLException {
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(true);
            int oldTimeout=c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run,(int)Duration.ofMinutes(2).toMillis());
            try { registry().syncAll(c); }
            finally { if(!c.isClosed())c.setNetworkTimeout(Runnable::run,oldTimeout); }
        }
    }

    public Outcome archive(BattleResultConsumer.Received received) {
        Map<Long,BattleSettlementData> settlements=validate(received);
        if(received.event().getSettlementFormatVersion()==0)return Outcome.LEGACY;
        BattleResultEvent event=received.event();
        BattleCompletionRow candidate=BattleCompletionRow.newBuilder().setBattleId(event.getBattleId())
                .setFormatVersion(1).setOriginal(received.original()).setOriginalDigest(digest(received.original()))
                .setSourceTopic(received.topic()).setSourcePartition(received.partition()).setSourceOffset(received.offset())
                .setPlayerCount(settlements.size()).build();
        try(Connection c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);
                boolean inserted=insertCompletion(c,candidate);
                BattleCompletionRow stored=db.findOneByPkForUpdate(c,BattleCompletionRow.newBuilder()
                        .setBattleId(event.getBattleId()).build()).orElseThrow();
                if(stored.getFormatVersion()!=1||stored.getPlayerCount()!=candidate.getPlayerCount()
                        ||!stored.getOriginal().equals(candidate.getOriginal())
                        ||!stored.getOriginalDigest().equals(candidate.getOriginalDigest()))
                    throw new SQLException("同一对局的完成来源原件不同或已损坏");
                for(var entry:settlements.entrySet()) {
                    ByteString original=ByteString.copyFrom(com.game.common.token.NetworkProbes.bytes(entry.getValue()));
                    var player=BattlePlayerCompletionRow.newBuilder().setBattleId(event.getBattleId()).setPlayerId(entry.getKey())
                            .setOriginal(original).setOriginalDigest(digest(original)).build();
                    if(inserted)db.insert(c,player);
                    else {
                        var old=db.findOneByPk(c,BattlePlayerCompletionRow.newBuilder().setBattleId(event.getBattleId())
                                .setPlayerId(entry.getKey()).build()).orElseThrow(()->new SQLException("个人来源归档缺失"));
                        if(!old.getOriginal().equals(original)||!old.getOriginalDigest().equals(player.getOriginalDigest()))
                            throw new SQLException("个人完成来源原件不同或已损坏");
                    }
                }
                try(var query=c.prepareStatement("SELECT COUNT(*) FROM match_battle_player_completion WHERE battle_id=?")) {
                    query.setObject(1,PbMysql.uint64(event.getBattleId()));query.setQueryTimeout(3);
                    try(var result=query.executeQuery()) {
                        if(!result.next()||result.getInt(1)!=settlements.size())throw new SQLException("个人来源归档人数与原件不符");
                    }
                }
                c.commit();return inserted?Outcome.ARCHIVED:Outcome.DUPLICATE;
            } catch(Exception failed) {
                try { c.rollback(); } catch(SQLException rollback) { failed.addSuppressed(rollback); }
                throw failed;
            }
        } catch(SQLException failed) { throw new IllegalStateException("战斗完成来源归档失败，保留消费位点",failed); }
    }

    private boolean insertCompletion(Connection c,BattleCompletionRow candidate)throws SQLException {
        try { db.insert(c,candidate);return true; }
        catch(SQLException conflict) {
            // 与评分存储沿用同一重复键判据：生产 MySQL/TiDB 1062，测试 H2 SQLState 23505。
            if(RatingSqlErrors.isDuplicateKey(conflict))return false;
            throw conflict;
        }
    }

    /** 游标只用于公平扫描，不授予写权限；每次确认仍锁定原件。失败成员不会挡住后续成员。 */
    public java.util.List<BattleCompletionRow> pendingResults(long after) {
        try(Connection c=source.getConnection()) {
            return db.findAll(c,BattleCompletionRow.class,
                    "terminal_decision=1 AND result_published=0 AND battle_id>? ORDER BY battle_id LIMIT 16",PbMysql.uint64(after));
        } catch(SQLException failure) { throw new IllegalStateException("全局结果待投来源读取失败",failure); }
    }
    public java.util.List<BattlePlayerCompletionRow> pendingPlayers(long battle,long player) {
        try(Connection c=source.getConnection()) {
            return db.findAll(c,BattlePlayerCompletionRow.class,
                    "applied=0 AND owner_revision>0 AND (battle_id>? OR (battle_id=? AND player_id>?)) ORDER BY battle_id,player_id LIMIT 16",
                    PbMysql.uint64(battle),PbMysql.uint64(battle),PbMysql.uint64(player));
        } catch(SQLException failure) { throw new IllegalStateException("个人永久来源待投读取失败",failure); }
    }
    /** 两类各保留8个名额；本轮固定最大battle，持续新流不能阻止旧失败项回头。 */
    public record PendingInboxBatch(long upperBattle,java.util.List<BattlePlayerCompletionRow> sources) { }
    public PendingInboxBatch pendingInbox(boolean first,long battle,long player,long upperBattle) {
        String stage=first?"inbox_seq=0":"inbox_seq>0";
        try(Connection c=source.getConnection()) {
            long stamp=nowMillis(c),upper=upperBattle;
            if(upper==0)try(var query=c.prepareStatement("SELECT MAX(battle_id) FROM match_battle_player_completion WHERE applied=0 AND owner_revision>0 AND "+stage+" AND retry_ms<=?")) {
                query.setQueryTimeout(3);query.setLong(1,stamp);
                try(var rows=query.executeQuery()){if(rows.next()&&rows.getString(1)!=null)upper=Long.parseUnsignedLong(rows.getString(1));}
            }
            if(upper==0)return new PendingInboxBatch(0,java.util.List.of());
            var rows=db.findAll(c,BattlePlayerCompletionRow.class,
                    "applied=0 AND owner_revision>0 AND "+stage+" AND retry_ms<=? AND battle_id<=? AND (battle_id>? OR (battle_id=? AND player_id>?)) ORDER BY battle_id,player_id LIMIT 8",
                    stamp,PbMysql.uint64(upper),PbMysql.uint64(battle),PbMysql.uint64(battle),PbMysql.uint64(player));
            return new PendingInboxBatch(upper,rows);
        } catch(SQLException failure){throw new IllegalStateException("个人来源到期分批读取失败",failure);}
    }
    /** 在原route/P/个人行锁内先登记下一次到期；并发副本及旧扫描不得重复领取同次RPC。 */
    public boolean claimInboxAttempt(BattlePlayerCompletionRow expected) {
        try(Connection c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);
                com.game.common.player.PlayerSessionAuthority.lock(c,expected.getPlayerId(),expected.getOwnerAccount(),expected.getOwnerRevision(),3);
                var row=db.findOneByPkForUpdate(c,BattlePlayerCompletionRow.newBuilder().setBattleId(expected.getBattleId()).setPlayerId(expected.getPlayerId()).build()).orElseThrow();
                if(!row.getOriginal().equals(expected.getOriginal())||!row.getOriginalDigest().equals(expected.getOriginalDigest())
                        ||!row.getOwnerAccount().equals(expected.getOwnerAccount())||row.getOwnerRevision()!=expected.getOwnerRevision()
                        ||row.getIssuedAtMs()!=expected.getIssuedAtMs())throw new SQLException("个人投递领取原件不同");
                long stamp=nowMillis(c);
                if(row.getApplied()||row.getInboxSeq()!=expected.getInboxSeq()||row.getRetryMs()!=expected.getRetryMs()
                        ||Long.compareUnsigned(row.getRetryMs(),stamp)>0){c.commit();return false;}
                // 首投2秒、永久seq后只Lookup并退避15秒；失联/异常也不在500ms轮询中热重发。
                long delay=row.getInboxSeq()==0?2_000L:15_000L;
                long next=stamp>Long.MAX_VALUE-delay?Long.MAX_VALUE:stamp+delay;
                try(var update=c.prepareStatement("UPDATE match_battle_player_completion SET retry_ms=? WHERE battle_id=? AND player_id=? AND applied=0 AND inbox_seq=? AND retry_ms=?")) {
                    update.setQueryTimeout(3);update.setLong(1,next);update.setObject(2,PbMysql.uint64(row.getBattleId()));update.setObject(3,PbMysql.uint64(row.getPlayerId()));
                    update.setObject(4,PbMysql.uint64(row.getInboxSeq()));update.setObject(5,PbMysql.uint64(row.getRetryMs()));
                    boolean claimed=update.executeUpdate()==1;c.commit();return claimed;
                }
            } catch(Exception failure){try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        } catch(SQLException failure){throw new IllegalStateException("个人来源投递退避领取失败",failure);}
    }
    private long nowMillis(Connection c)throws SQLException { return clock.now(c); }
    private static long databaseNowMillis(Connection c)throws SQLException {
        try(var query=c.prepareStatement("SELECT CURRENT_TIMESTAMP(3)")) {
            query.setQueryTimeout(3);
            try(var rows=query.executeQuery()){
                if(!rows.next()||rows.getTimestamp(1)==null)throw new SQLException("缺少数据库时间");
                long stamp=rows.getTimestamp(1).getTime();if(stamp<0)throw new SQLException("数据库时间无效");return stamp;
            }
        }
    }
    public void resultPublished(BattleCompletionRow expected) {
        try(Connection c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);
                var row=db.findOneByPkForUpdate(c,BattleCompletionRow.newBuilder().setBattleId(expected.getBattleId()).build()).orElseThrow();
                if(row.getTerminalDecision()!=1||!row.getOriginal().equals(expected.getOriginal())
                        ||!row.getOriginalDigest().equals(expected.getOriginalDigest()))throw new SQLException("全局结果确认原件不同");
                db.updateByPk(c,row.toBuilder().setResultPublished(true).build());c.commit();
            } catch(Exception failure) { try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure; }
        } catch(SQLException failure) { throw new IllegalStateException("全局结果已投回执提交未知，须原字节重放",failure); }
    }
    public void inboxReceipt(BattlePlayerCompletionRow expected,long seq,boolean applied) {
        if(seq==0||expected.getInboxSeq()!=0&&expected.getInboxSeq()!=seq)throw new SecurityException("个人永久seq被替换");
        try(Connection c=source.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {
                com.game.common.player.PlayerRouteAuthority.requireMeta(c,3,true);
                com.game.common.player.PlayerSessionAuthority.lock(c,expected.getPlayerId(),expected.getOwnerAccount(),expected.getOwnerRevision(),3);
                var key=BattlePlayerCompletionRow.newBuilder().setBattleId(expected.getBattleId()).setPlayerId(expected.getPlayerId()).build();
                var row=db.findOneByPkForUpdate(c,key).orElseThrow();
                if(!row.getOriginal().equals(expected.getOriginal())||!row.getOriginalDigest().equals(expected.getOriginalDigest())
                        ||!row.getOwnerAccount().equals(expected.getOwnerAccount())||row.getOwnerRevision()!=expected.getOwnerRevision()
                        ||row.getIssuedAtMs()!=expected.getIssuedAtMs()||row.getInboxSeq()!=0&&row.getInboxSeq()!=seq)
                    throw new SQLException("个人待投原件或永久seq不同");
                db.updateByPk(c,row.toBuilder().setInboxSeq(seq).setApplied(row.getApplied()||applied).build());c.commit();
            } catch(Exception failure) { try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure; }
        } catch(SQLException failure) { throw new IllegalStateException("个人Inbox回执提交未知，保留来源重读",failure); }
    }

    private static Map<Long,BattleSettlementData> validate(BattleResultConsumer.Received received) {
        return validateOriginal(received.event(), received.original());
    }

    /** 只有新执行来源递归拒绝未知资产字段；旧Kafka归档仍逐字保存原件。 */
    public static Map<Long,BattleSettlementData> validateTerminal(ByteString original) {
        final BattleResultEvent event;
        try { event=BattleResultEvent.parseFrom(original); }
        catch(InvalidProtocolBufferException bad) { throw new IllegalArgumentException("终局原件损坏",bad); }
        if(event.getSettlementFormatVersion()!=1 || unknown(event))throw new IllegalArgumentException("终局版本或嵌套资产语义未知");
        var values=validateOriginal(event,original);
        for(var value:values.values())if(value.getSerializedSize()>16*1024)throw new IllegalArgumentException("个人终局超过Inbox固定16KiB上限");
        java.util.Set<Long> fled=new java.util.TreeSet<>(Long::compareUnsigned),dead=new java.util.TreeSet<>(Long::compareUnsigned);
        for(var value:values.values()) {
            if(value.getFled())fled.add(value.getPlayerId());
            if(value.getIsDead())dead.add(value.getPlayerId());
        }
        if(!event.getFledPlayerIdsList().equals(new java.util.ArrayList<>(fled))
                ||!event.getDeadPlayerIdsList().equals(new java.util.ArrayList<>(dead)))
            throw new IllegalArgumentException("全局逃跑/阵亡名单与逐人原件不同或非唯一升序");
        return values;
    }
    private static boolean unknown(com.google.protobuf.Message message) {
        if(!message.getUnknownFields().asMap().isEmpty())return true;
        for(var field:message.getAllFields().entrySet()) {
            if(field.getKey().getJavaType()!=com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE)continue;
            if(field.getKey().isRepeated()) {
                for(Object value:(java.util.List<?>)field.getValue())if(unknown((com.google.protobuf.Message)value))return true;
            } else if(unknown((com.google.protobuf.Message)field.getValue()))return true;
        }
        return false;
    }
    private static Map<Long,BattleSettlementData> validateOriginal(BattleResultEvent event,ByteString original) {
        if(original.isEmpty()||original.size()>MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("完成来源原件为空或过大");
        try {
            if(!BattleResultEvent.parseFrom(original).equals(event))throw new IllegalArgumentException("完成来源与解析事件不一致");
        } catch(InvalidProtocolBufferException malformed) { throw new IllegalArgumentException("完成来源原件损坏",malformed); }
        int version=event.getSettlementFormatVersion();
        if(version==0) {
            if(event.getSettlementsCount()!=0)throw new IllegalArgumentException("旧结果不能冒充完整个人来源");
            return Map.of();
        }
        if(version!=1)throw new IllegalArgumentException("不支持的结算来源版本");
        if(event.getBattleId()==0||event.getOutcomeValue()<1||event.getOutcomeValue()>3
                ||event.getSettlementsCount()<1)
            throw new IllegalArgumentException("完成来源对局、终局或人数无效");
        Map<Long,Integer> roster=new HashMap<>();java.util.Set<Integer> teams=new java.util.HashSet<>();
        for(var team:event.getTeamsList()) {
            if(!teams.add(team.getTeamIndex())||team.getPlayerIdsCount()==0)throw new IllegalArgumentException("完成来源队伍重复或为空");
            for(long player:team.getPlayerIdsList())
                if(player==0||roster.putIfAbsent(player,team.getTeamIndex())!=null)throw new IllegalArgumentException("完成来源成员重复或为零");
        }
        Map<Long,BattleSettlementData> out=new LinkedHashMap<>();
        for(var settlement:event.getSettlementsList()) {
            Integer team=roster.get(settlement.getPlayerId());
            if(team==null||team!=settlement.getPlayerTeamIndex()||settlement.getBattleId()!=event.getBattleId()
                    ||settlement.getOutcomeValue()!=event.getOutcomeValue()||settlement.getTotalRounds()!=event.getTotalRounds()
                    ||out.putIfAbsent(settlement.getPlayerId(),settlement)!=null)
                throw new IllegalArgumentException("个人完成来源与成员、队伍、终局或回合不一致");
        }
        if(out.size()!=roster.size())throw new IllegalArgumentException("完成来源没有完整覆盖成员");
        return out;
    }

    public static ByteString digest(ByteString original) {
        try { return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(original.toByteArray())); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
