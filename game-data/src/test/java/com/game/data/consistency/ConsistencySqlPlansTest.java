package com.game.data.consistency;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.game.data.consistency.ConsistencyReport.*;

/** 真正执行分页/反连接SQL；只使用进程内H2，不读取game.it.mysql或任何真实凭据。 */
class ConsistencySqlPlansTest {
    @Test void mailCountsDistinctIdsAndNeverUsesRecipientExistence()throws Exception{
        try(var db=memory()) {
            execute(db,"CREATE TABLE mail_state(player_id BIGINT,mail_id BIGINT,claim_state INT,PRIMARY KEY(player_id,mail_id))",
                    "CREATE INDEX idx_mail_state_mail ON mail_state(mail_id,claim_state)",
                    "CREATE TABLE mail_message(mail_id BIGINT PRIMARY KEY)",
                    "INSERT INTO mail_state VALUES(10,101,0),(11,101,0),(12,101,0),(13,102,0),(999,103,0)",
                    "INSERT INTO mail_message VALUES(102),(103)");
            var result=service(db).run(request("mail.state_orphan")).results().getFirst();
            assertThat(result.status()).isEqualTo(Status.WARN);
            assertThat(result.scannedCount()).isEqualTo(3);assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.countExact()).isTrue();
        }
    }
    @Test void friendScansBothEndpointsAcrossAllThreeTablesAndEscalatesOnlyEdgeRatio()throws Exception{
        try(var db=memory()) {
            execute(db,"CREATE TABLE player(player_id BIGINT PRIMARY KEY)","INSERT INTO player VALUES(1),(2)",
                    "CREATE TABLE friend(player_id BIGINT,friend_player_id BIGINT,PRIMARY KEY(player_id,friend_player_id))",
                    "CREATE TABLE friend_request(from_player_id BIGINT,to_player_id BIGINT,PRIMARY KEY(from_player_id,to_player_id))",
                    "CREATE TABLE friend_block(player_id BIGINT,blocked_player_id BIGINT,PRIMARY KEY(player_id,blocked_player_id))",
                    "INSERT INTO friend VALUES(1,2),(2,1),(404,1)","INSERT INTO friend_request VALUES(1,405)",
                    "INSERT INTO friend_block VALUES(406,1)");
            var result=service(db).run(request("friend.orphan")).results().getFirst();
            assertThat(result.scannedCount()).isEqualTo(5);assertThat(result.confirmedBad()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.reasons()).contains("friend_orphan_ratio_at_least_5_percent");
        }
    }
    @Test
    void 好友稳定缺少反向边必须阻断_补齐后恢复正常() throws Exception {
        try (var db = memory()) {
            execute(db, "CREATE TABLE friend(player_id BIGINT,friend_player_id BIGINT,PRIMARY KEY(player_id,friend_player_id))",
                    "INSERT INTO friend VALUES(1,2),(2,1),(3,4)");
            var broken = service(db).run(request("friend.symmetry"));
            var result = broken.results().getFirst();
            assertThat(result.scannedCount()).isEqualTo(3);
            assertThat(result.confirmedBad()).isEqualTo(1);
            assertThat(result.countExact()).isTrue();
            assertThat(result.status()).isEqualTo(Status.BLOCK);
            assertThat(result.reasons()).containsExactly("reverse_friend_missing");
            assertThat(broken.exitCode()).isEqualTo(1);

            execute(db, "INSERT INTO friend VALUES(4,3)");
            var repaired = service(db).run(request("friend.symmetry"));
            assertThat(repaired.results().getFirst().scannedCount()).isEqualTo(4);
            assertThat(repaired.results().getFirst().confirmedBad()).isZero();
            assertThat(repaired.results().getFirst().status()).isEqualTo(Status.PASS);
            assertThat(repaired.exitCode()).isZero();
        }
    }
    @Test void stateEmptyBytesAreValidButNullMissingAndReverseOrphanAreNot()throws Exception{
        try(var db=memory()) {
            execute(db,"CREATE TABLE player(player_id BIGINT PRIMARY KEY)","INSERT INTO player VALUES(1),(2),(3)",
                    "CREATE TABLE player_state(player_id BIGINT PRIMARY KEY,data BLOB)",
                    "INSERT INTO player_state VALUES(1,X''),(2,NULL),(4,X'')");
            var result=service(db).run(request("player.state_orphan")).results().getFirst();
            assertThat(result.scannedCount()).isEqualTo(6);assertThat(result.confirmedBad()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(Status.BLOCK);
        }
    }
    @Test void closedAndMaintenanceZonesAreKnownWhileRetiredReferenceIsInvalid()throws Exception{
        try(var db=memory()) {
            execute(db,"CREATE TABLE guild(guild_id BIGINT PRIMARY KEY,zone_id INT)",
                    "CREATE TABLE zone_config(zone_id INT PRIMARY KEY,manual_status INT)",
                    "CREATE TABLE zone_merged_into(zone_id INT PRIMARY KEY,target_zone INT)",
                    "INSERT INTO zone_config VALUES(1,3),(2,4)","INSERT INTO zone_merged_into VALUES(2,1)",
                    "INSERT INTO guild VALUES(0,1),(1,2),(2,0),(3,99)");
            var result=service(db).run(request("zone.guild")).results().getFirst();
            assertThat(result.scannedCount()).isEqualTo(4);assertThat(result.confirmedBad()).isEqualTo(3);
            assertThat(result.reasons()).contains("retired_zone_reference","zone_zero","zone_missing");
        }
    }
    @Test void matchQueryKeepsMissingCompletionAndDoesNotFetchCreateBody(){
        var plan=ConsistencySqlPlans.forCheck("match.expired_unrecoverable").getFirst();
        assertThat(plan.schema().sql()).contains("LEFT JOIN match_battle_completion","OCTET_LENGTH(create_request)")
                .doesNotContain("INNER JOIN","d.create_request,","FOR UPDATE");
    }
    private static Connection memory()throws Exception{
        return DriverManager.getConnection("jdbc:h2:mem:consistency_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }
    private static void execute(Connection db,String... statements)throws Exception{
        try(var statement=db.createStatement()){for(String sql:statements)statement.execute(sql);}
    }
    private static ConsistencyService.Request request(String id){return new ConsistencyService.Request(List.of(id),1,100,60_000L,60_000L,false);}
    private static ConsistencyService service(Connection db){
        ConsistencySqlReader reader=(sql,parameters,limit,budget)->{
            var results=new ArrayList<Map<String,Object>>();
            try(var statement=db.prepareStatement(sql)) {
                for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
                try(var rows=statement.executeQuery()){
                    var metadata=rows.getMetaData();
                    while(rows.next()){
                        var row=new LinkedHashMap<String,Object>();
                        for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT),rows.getObject(i));
                        results.add(row);
                    }
                }
                return results;
            }catch(Exception failure){throw new AssertionError("H2执行固定巡检SQL失败",failure);}
        };
        var ns=GameNamespace.of("nstest");return new ConsistencyService(reader,null,ns,new RedisKeys(ns),budget->budget.check());
    }
}
