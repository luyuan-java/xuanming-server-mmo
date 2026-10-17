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

    public BattleCompletionStore(DataSource source) { this.source=java.util.Objects.requireNonNull(source); }

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
                boolean inserted=db.insertIgnore(c,candidate);
                BattleCompletionRow stored=db.findOneByPkForUpdate(c,BattleCompletionRow.newBuilder()
                        .setBattleId(event.getBattleId()).build()).orElseThrow();
                if(stored.getFormatVersion()!=1||stored.getPlayerCount()!=candidate.getPlayerCount()
                        ||!stored.getOriginal().equals(candidate.getOriginal())
                        ||!stored.getOriginalDigest().equals(candidate.getOriginalDigest()))
                    throw new SQLException("同一对局的完成来源原件不同或已损坏");
                for(var entry:settlements.entrySet()) {
                    ByteString original=entry.getValue().toByteString();
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

    private static Map<Long,BattleSettlementData> validate(BattleResultConsumer.Received received) {
        BattleResultEvent event=received.event();
        if(received.original().isEmpty()||received.original().size()>MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("完成来源原件为空或过大");
        try {
            if(!BattleResultEvent.parseFrom(received.original()).equals(event))throw new IllegalArgumentException("完成来源与解析事件不一致");
        } catch(InvalidProtocolBufferException malformed) { throw new IllegalArgumentException("完成来源原件损坏",malformed); }
        int version=event.getSettlementFormatVersion();
        if(version==0) {
            if(event.getSettlementsCount()!=0)throw new IllegalArgumentException("旧结果不能冒充完整个人来源");
            return Map.of();
        }
        if(version!=1)throw new IllegalArgumentException("不支持的结算来源版本");
        if(event.getBattleId()==0||event.getOutcomeValue()<1||event.getOutcomeValue()>3
                ||event.getSettlementsCount()<1||event.getSettlementsCount()>10)
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

    private static ByteString digest(ByteString original) {
        try { return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(original.toByteArray())); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
