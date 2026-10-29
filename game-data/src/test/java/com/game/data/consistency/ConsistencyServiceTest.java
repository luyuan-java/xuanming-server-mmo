package com.game.data.consistency;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.game.data.consistency.ConsistencyReport.*;

class ConsistencyServiceTest {
    private final GameNamespace namespace=GameNamespace.of("nstest");
    private final RedisKeys keys=new RedisKeys(namespace);
    private static Map<String,Object> key(long id){return Map.of("k1",id,"k2",0);}
    private static Map<String,Object> player(long id,long zone,boolean missing){
        return Map.of("k1",id,"k2",0,"zone_id",zone,"missing",missing,"retired",false,"db_now",100_000L);
    }
    private ConsistencyService service(ConsistencySqlReader sql,ConsistencyRedisReader redis){
        return new ConsistencyService(sql,redis,namespace,keys,budget->budget.check());
    }
    private static ConsistencyService.Request request(String id,int page,int limit){
        return new ConsistencyService.Request(List.of(id),page,limit,60_000L,60_000L,false);
    }
    static final class Scripted implements ConsistencySqlReader {
        final ArrayDeque<Object> results=new ArrayDeque<>();final List<String> statements=new ArrayList<>();
        Scripted(Object... values){results.addAll(List.of(values));}
        @Override @SuppressWarnings("unchecked") public List<Map<String,Object>> query(String sql,List<Object> parameters,int limit,ConsistencyBudget budget){
            statements.add(sql);Object result=results.removeFirst();
            if(result instanceof RuntimeException failure)throw failure;
            return (List<Map<String,Object>>)result;
        }
    }
    @Test void missingDependencyCannotPassAnEmptySourceTable(){
        var sql=new Scripted(new ConsistencyBudget.Failure("schema_unavailable"));
        var report=service(sql,null).run(request("zone.player",2,10));
        assertThat(report.exitCode()).isEqualTo(3);
        assertThat(report.results().getFirst().coverage()).isEqualTo(Coverage.NONE);
        assertThat(sql.statements.getFirst()).contains("WHERE 1=0","LEFT JOIN zone_config","LEFT JOIN zone_merged_into");
    }
    @Test void cleanFullPageBeforeDisconnectRetainsCoverage(){
        var sql=new Scripted(List.of(),List.of(key(3)),List.of(player(0,1,false),player(1,1,false)),
                new ConsistencyBudget.Failure("query_failed"));
        var result=service(sql,null).run(request("zone.player",2,10)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(result.countExact()).isFalse();
        assertThat(result.cursorHash()).matches("[0-9a-f]{64}");
    }
    @Test void confirmedFindingSurvivesLaterFailureAndPreservesUnknown(){
        var bad=player(1,0,true);
        var sql=new Scripted(List.of(),List.of(key(3)),List.of(bad),List.of(bad),new ConsistencyBudget.Failure("query_failed"));
        var report=service(sql,null).run(request("zone.player",1,10));var result=report.results().getFirst();
        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(Status.BLOCK);
        assertThat(result.confirmedBad()).isEqualTo(1);
        assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(result.reasons()).contains("zone_zero","query_failed");
    }
    @Test void failedConfirmationIsNotTreatedAsVanishedRow(){
        var sql=new Scripted(List.of(),List.of(key(1)),List.of(player(1,0,true)),new ConsistencyBudget.Failure("query_failed"));
        var result=service(sql,null).run(request("zone.player",2,10)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.confirmedBad()).isZero();assertThat(result.transientCount()).isZero();
    }
    @Test void changingBadIdentityCannotTurnPass(){
        var sql=new Scripted(List.of(),List.of(key(1)),List.of(player(1,0,true)),List.of(player(1,8,true)));
        var result=service(sql,null).run(request("zone.player",2,10)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.reasons()).contains("concurrent_change");
    }
    @Test void concurrentRepairIsTransientWithoutFalseFinding(){
        var sql=new Scripted(List.of(),List.of(key(1)),List.of(player(1,0,true)),List.of(player(1,1,false)));
        var result=service(sql,null).run(request("zone.player",2,10)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.transientCount()).isEqualTo(1);assertThat(result.confirmedBad()).isZero();
    }
    @Test void boundedScanCannotClaimFullCoverage(){
        var sql=new Scripted(List.of(),List.of(key(3)),List.of(player(1,1,false)));
        var result=service(sql,null).run(request("zone.player",1,1)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);assertThat(result.reasons()).contains("partial_coverage");
    }
    @Test void stableBytesCompareContentAndIgnoreOnlyObservationTime(){
        assertThat(ConsistencyService.stable(Map.of("original",new byte[]{1,2},"db_now",1),Map.of("original",new byte[]{1,2},"db_now",2))).isTrue();
        assertThat(ConsistencyService.stable(Map.of("original",new byte[]{1,2}),Map.of("original",new byte[]{1,3}))).isFalse();
    }
    @Test void unsignedCursorKeepsHighHalfAndIncludesZeroOnFirstPage(){
        var plan=ConsistencySqlPlans.forCheck("zone.player").getFirst();
        var upper=new ConsistencySqlPlans.Key(new BigDecimal("18446744073709551615"),BigDecimal.ZERO);
        var first=plan.page(null,upper,2);var next=plan.page(new ConsistencySqlPlans.Key(new BigDecimal("9223372036854775808"),BigDecimal.ZERO),upper,2);
        assertThat(first.sql()).doesNotContain("player_id>?");
        assertThat(first.parameters().getFirst()).isEqualTo(upper.first());
        assertThat(next.parameters().getFirst()).isEqualTo(new BigDecimal("9223372036854775808"));
    }
    @Test void unsupportedRegisteredChecksAreReportedAndUnknownIdsRejected(){
        var report=service(null,null).run(request("retire.original",1,10));
        assertThat(report.results().getFirst().reasons()).containsExactly("not_implemented");
        assertThat(report.executed()).isEmpty();assertThat(report.omitted()).contains("zone.player");
        assertThatThrownBy(()->service(null,null).run(request("sql.injected",1,10))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void saltsChangeBetweenRunsAndNoRawEntityLeaks(){
        var bad=player(123456789,0,true);
        var sql=new Scripted(List.of(),List.of(key(123456789)),List.of(bad),List.of(bad),List.of(),List.of(key(123456789)),List.of(bad),List.of(bad));
        var runner=service(sql,null);var a=runner.run(request("zone.player",2,10));var b=runner.run(request("zone.player",2,10));
        assertThat(a.results().getFirst().samples().getFirst().identityHash()).isNotEqualTo(b.results().getFirst().samples().getFirst().identityHash());
        assertThat(a.toString()).doesNotContain("123456789");
    }
    @Test void physicalOrphanIsFoundEvenIfRankIndexIsEmpty(){
        var redis=mock(ConsistencyRedisReader.class);
        when(redis.completeTopology()).thenReturn(true);
        when(redis.type(eq(keys.guildRankLock()),any())).thenReturn("none");
        when(redis.type(eq(keys.guildRankZone(99)),any())).thenReturn("zset");
        when(redis.scanRankZones(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of()));
        when(redis.scanRankKeys(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of(keys.guildRankZone(99),keys.guildRankZone(99))));
        Map<String,Object> row=Map.of("known",false,"retired",false,"db_now",100_000L);
        var sql=new Scripted(List.of(),List.of(row),List.of(row),List.of(row),List.of(row));
        var result=service(sql,redis).run(request("zone.guild_rank",10,20)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.WARN);assertThat(result.confirmedBad()).isEqualTo(1);
    }
    @Test void clusterAndRedisErrorsRemainUnknown(){
        var redis=mock(ConsistencyRedisReader.class);when(redis.completeTopology()).thenReturn(false);
        var runner=service(new Scripted(List.of(),List.of()),redis);
        assertThat(runner.run(request("zone.guild_rank",1,2)).results().getFirst().reasons()).containsExactly("unsupported_topology");
        when(redis.completeTopology()).thenReturn(true);when(redis.type(anyString(),any())).thenThrow(new ConsistencyBudget.Failure("redis_read_failed"));
        assertThat(runner.run(request("zone.guild_rank",1,2)).results().getFirst().status()).isEqualTo(Status.UNKNOWN);
    }
    @Test void rankTypeRepairWithRemainingZoneOrphanCannotTurnPass(){
        var redis=mock(ConsistencyRedisReader.class);
        when(redis.completeTopology()).thenReturn(true);
        when(redis.type(eq(keys.guildRankLock()),any())).thenReturn("none");
        // 首次的初读/判定均为string；复核的初读/判定变为zset，SQL区引用始终缺失。
        when(redis.type(eq(keys.guildRankZone(99)),any())).thenReturn("string","string","zset","zset");
        when(redis.scanRankZones(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of("99")));
        when(redis.scanRankKeys(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of(keys.guildRankZone(99))));
        Map<String,Object> row=Map.of("known",false,"retired",false,"db_now",100_000L);
        var sql=new Scripted(List.of(),List.of(row),List.of(row),List.of(row),List.of(row));
        var report=service(sql,redis).run(request("zone.guild_rank",10,20));
        var result=report.results().getFirst();
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(result.countExact()).isFalse();
        assertThat(result.reasons()).containsExactly("concurrent_change");
        assertThat(result.confirmedBad()).isZero();assertThat(result.transientCount()).isZero();
        assertThat(report.exitCode()).isEqualTo(3);
    }
    @Test void emptyRankStillRequiresSuccessfulSqlIdentityAndSchemaProof(){
        for(String reason:List.of("query_failed","namespace_mismatch","schema_unavailable")) {
            var redis=emptyRank();
            var sql=new Scripted(new ConsistencyBudget.Failure(reason));
            var report=service(sql,redis).run(request("zone.guild_rank",10,20));
            var result=report.results().getFirst();
            assertThat(result.status()).isEqualTo(Status.UNKNOWN);
            assertThat(result.coverage()).isEqualTo(Coverage.NONE);
            assertThat(result.countExact()).isFalse();
            assertThat(result.reasons()).containsExactly(reason);
            assertThat(report.exitCode()).isEqualTo(3);
            assertThat(sql.statements).hasSize(1);
            assertThat(sql.statements.getFirst()).contains("zone_config","zone_merged_into","WHERE 1=0","LIMIT 1");
            verifyNoInteractions(redis);
        }
    }
    @Test void emptyRankPassesOnlyAfterSqlProofAndBothScansComplete(){
        var redis=emptyRank();var sql=new Scripted(List.of());
        var result=service(sql,redis).run(request("zone.guild_rank",10,20)).results().getFirst();
        assertThat(result.status()).isEqualTo(Status.PASS);
        assertThat(result.coverage()).isEqualTo(Coverage.COMPLETE);
        assertThat(result.countExact()).isTrue();assertThat(result.scannedCount()).isZero();
        assertThat(sql.statements).hasSize(1);
        verify(redis).scanRankZones(eq("0"),eq(10),any());
        verify(redis).scanRankKeys(eq("0"),eq(10),any());
    }
    private ConsistencyRedisReader emptyRank(){
        var redis=mock(ConsistencyRedisReader.class);
        when(redis.completeTopology()).thenReturn(true);
        when(redis.type(eq(keys.guildRankLock()),any())).thenReturn("none");
        when(redis.scanRankZones(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of()));
        when(redis.scanRankKeys(eq("0"),anyInt(),any())).thenReturn(new ConsistencyRedisReader.Page("0",List.of()));
        return redis;
    }
}
