package com.game.match.rating;

import static org.junit.jupiter.api.Assertions.*;

import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.google.protobuf.ByteString;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 同一份可观察契约分别跑 H2 与显式启用的真实 TiDB。 */
abstract class BattleCompletionStoreCases {
    DataSource data;
    BattleCompletionStore store;

    static BattleResultEvent complete(long battle) {
        var a=BattleSettlementData.newBuilder().setBattleId(battle).setPlayerId(1).setPlayerTeamIndex(0)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setTotalRounds(3).setGoldGain(999).build();
        var b=a.toBuilder().setPlayerId(2).setPlayerTeamIndex(1).setGoldGain(7).build();
        return BattleResultEvent.newBuilder().setBattleId(battle).setMatchMode(3)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setTotalRounds(3).setSettlementFormatVersion(1)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(1))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(2))
                .addSettlements(a).addSettlements(b).build();
    }
    static BattleResultConsumer.Received received(BattleResultEvent event) {
        return new BattleResultConsumer.Received(event,event.toByteString(),"fixture-battle-results",2,91);
    }
    private int count(String table)throws SQLException {
        try(var c=data.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+table)) {
            assertTrue(r.next());return r.getInt(1);
        }
    }
    @Test void 原始收到字节摘要与所有个人来源同事务保存且重复不覆盖首个位点()throws Exception {
        var event=complete(701);var first=received(event);
        assertEquals(BattleCompletionStore.Outcome.ARCHIVED,store.archive(first));
        assertEquals(BattleCompletionStore.Outcome.DUPLICATE,store.archive(new BattleResultConsumer.Received(event,event.toByteString(),"another-copy",1,800)));
        assertEquals(1,count("match_battle_completion"));assertEquals(2,count("match_battle_player_completion"));
        try(var c=data.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT original,original_digest,source_topic,source_partition,source_offset,player_count FROM match_battle_completion WHERE battle_id=701")) {
            assertTrue(r.next());assertArrayEquals(first.original().toByteArray(),r.getBytes(1));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(first.original().toByteArray()),r.getBytes(2));
            assertEquals(first.topic(),r.getString(3));assertEquals(2,r.getInt(4));assertEquals(91,r.getLong(5));assertEquals(2,r.getInt(6));
        }
        try(var c=data.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT original,original_digest FROM match_battle_player_completion ORDER BY player_id")) {
            for(var settlement:event.getSettlementsList()) {
                assertTrue(r.next());assertArrayEquals(settlement.toByteArray(),r.getBytes(1));
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(r.getBytes(1)),r.getBytes(2));
            }
            assertFalse(r.next());
        }
    }
    @Test void 同battle不同原件和相同解析但不同wire均拒绝()throws Exception {
        var event=complete(702);store.archive(received(event));
        assertThrows(RuntimeException.class,()->store.archive(received(event.toBuilder().setFinishedAtMs(123).build())));
        byte[] duplicateField=new byte[]{8,(byte)0xBE,5}; // 末尾再次写同一个 battle_id=702，解析语义相同。
        ByteString raw=event.toByteString().concat(ByteString.copyFrom(duplicateField));
        assertEquals(event,BattleResultEvent.parseFrom(raw));
        assertThrows(RuntimeException.class,()->store.archive(new BattleResultConsumer.Received(event,raw,"fixture-battle-results",2,92)));
        assertEquals(1,count("match_battle_completion"));assertEquals(2,count("match_battle_player_completion"));
    }
    @Test void 个人来源写入失败回滚整局()throws Exception {
        var inserts=new java.util.concurrent.atomic.AtomicInteger();
        DataSource failing=proxy(DataSource.class,data,(method,args,result)->{
            if(!method.getName().equals("getConnection"))return result;
            return proxy(java.sql.Connection.class,(java.sql.Connection)result,(connectionMethod,connectionArgs,prepared)->{
                if(!connectionMethod.getName().equals("prepareStatement")||!((String)connectionArgs[0]).contains("INSERT INTO `match_battle_player_completion`"))return prepared;
                return proxy(java.sql.PreparedStatement.class,(java.sql.PreparedStatement)prepared,(statementMethod,statementArgs,updated)->updated,
                        statementMethod->{if(statementMethod.getName().equals("executeUpdate")&&inserts.incrementAndGet()==2)throw new SQLException("第二名个人来源写入故障");});
            });
        });
        assertThrows(RuntimeException.class,()->new BattleCompletionStore(failing).archive(received(complete(703))));
        assertEquals(2,inserts.get());
        assertEquals(0,count("match_battle_completion"));assertEquals(0,count("match_battle_player_completion"));
    }

    @FunctionalInterface private interface After { Object apply(java.lang.reflect.Method method,Object[] args,Object result)throws Throwable; }
    @FunctionalInterface private interface Before { void apply(java.lang.reflect.Method method)throws Throwable; }
    private static <T>T proxy(Class<T> type,T delegate,After after) { return proxy(type,delegate,after,method->{}); }
    private static <T>T proxy(Class<T> type,T delegate,After after,Before before) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,method,args)->{
            before.apply(method);
            try { return after.apply(method,args,method.invoke(delegate,args)); }
            catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }));
    }
    @Test void 并发重复只保留一份完整来源()throws Exception {
        var start=new CyclicBarrier(4);var outcomes=new ArrayList<BattleCompletionStore.Outcome>();
        try(var threads=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<java.util.concurrent.Future<BattleCompletionStore.Outcome>>();
            for(int i=0;i<4;i++)tasks.add(threads.submit(()->{start.await();return store.archive(received(complete(704)));}));
            for(var task:tasks)outcomes.add(task.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,outcomes.stream().filter(value->value==BattleCompletionStore.Outcome.ARCHIVED).count());
        assertEquals(3,outcomes.stream().filter(value->value==BattleCompletionStore.Outcome.DUPLICATE).count());
        assertEquals(1,count("match_battle_completion"));assertEquals(2,count("match_battle_player_completion"));
    }
    @Test void 历史格式零不伪造完整来源但夹带个人载荷拒绝()throws Exception {
        var legacy=complete(705).toBuilder().clearSettlements().setSettlementFormatVersion(0).build();
        assertEquals(BattleCompletionStore.Outcome.LEGACY,store.archive(received(legacy)));
        assertThrows(IllegalArgumentException.class,()->store.archive(received(complete(705).toBuilder().setSettlementFormatVersion(0).build())));
        assertEquals(0,count("match_battle_completion"));assertEquals(0,count("match_battle_player_completion"));
    }
    @Test void 未知版本与来源超过共同上限拒绝且零写入()throws Exception {
        assertThrows(IllegalArgumentException.class,()->store.archive(received(complete(706).toBuilder().setSettlementFormatVersion(2).build())));
        var event=complete(706);var oversized=ByteString.copyFrom(new byte[BattleCompletionStore.MAX_SOURCE_BYTES+1]);
        assertThrows(IllegalArgumentException.class,()->store.archive(new BattleResultConsumer.Received(event,oversized,"fixture-battle-results",1,0)));
        assertEquals(0,count("match_battle_completion"));
    }
    @Test void 成员队伍个人对局胜负回合及覆盖必须全部一致()throws Exception {
        var event=complete(707);var first=event.getSettlements(0);
        var invalid=List.of(event.toBuilder().removeSettlements(1).build(),event.toBuilder().addSettlements(first).build(),
                event.toBuilder().setSettlements(0,first.toBuilder().setPlayerId(9)).build(),
                event.toBuilder().setSettlements(0,first.toBuilder().setBattleId(999)).build(),
                event.toBuilder().setSettlements(0,first.toBuilder().setOutcome(eBattleOutcome.BATTLE_OUTCOME_DRAW)).build(),
                event.toBuilder().setSettlements(0,first.toBuilder().setPlayerTeamIndex(1)).build(),
                event.toBuilder().setSettlements(0,first.toBuilder().setTotalRounds(4)).build(),
                event.toBuilder().addTeams(event.getTeams(0)).build(),
                event.toBuilder().setTeams(1,event.getTeams(1).toBuilder().addPlayerIds(1)).build());
        for(var source:invalid)assertThrows(IllegalArgumentException.class,()->store.archive(received(source)));
        assertEquals(0,count("match_battle_completion"));assertEquals(0,count("match_battle_player_completion"));
    }
    @Test void 已存个人来源丢失或损坏不能把重复当作成功()throws Exception {
        var event=complete(708);store.archive(received(event));
        try(var c=data.getConnection();var q=c.createStatement()) {q.executeUpdate("DELETE FROM match_battle_player_completion WHERE player_id=2");}
        assertThrows(RuntimeException.class,()->store.archive(received(event)));
    }
    @Test void 评分关闭及不计分也先归档且来源失败不调用评分()throws Exception {
        var rated=new java.util.concurrent.atomic.AtomicInteger();
        var disabled=RatingConfiguration.sourceHandler(store,false,event->rated.incrementAndGet());
        disabled.handle(received(complete(709)));assertEquals(0,rated.get());assertEquals(1,count("match_battle_completion"));
        var enabled=RatingConfiguration.sourceHandler(store,true,event->rated.incrementAndGet());
        enabled.handle(received(complete(710).toBuilder().setMatchMode(4).build()));assertEquals(1,rated.get());
        assertThrows(IllegalArgumentException.class,()->enabled.handle(received(complete(711).toBuilder().setSettlementFormatVersion(3).build())));
        assertEquals(1,rated.get());assertEquals(2,count("match_battle_completion"));
    }
    @Test void 事件与原件不一致拒绝且不能用无原件调用绕过()throws Exception {
        var event=complete(712);assertThrows(IllegalArgumentException.class,()->store.archive(new BattleResultConsumer.Received(event,
                complete(713).toByteString(),"fixture-battle-results",0,0)));
        assertThrows(IllegalArgumentException.class,()->RatingConfiguration.sourceHandler(store,false,ignored->{}).handle(event));
        assertEquals(0,count("match_battle_completion"));
    }
}
