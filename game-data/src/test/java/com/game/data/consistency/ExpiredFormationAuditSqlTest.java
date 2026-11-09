package com.game.data.consistency;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 实际执行固定分页 SQL，不能只比较实现字符串就宣布高位游标与候选范围正确。 */
class ExpiredFormationAuditSqlTest {
    @Test void candidatesSkipTerminalHistoryAndKeepMissingCompletionAndUnsignedOrdering() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:audit_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = db.createStatement()) {
                statement.execute("CREATE TABLE match_global_formation(battle_id DECIMAL(20,0) PRIMARY KEY,operation_id VARCHAR,instance_id VARCHAR,state INT,deadline_ms BIGINT,create_request VARBINARY)");
                statement.execute("CREATE TABLE match_battle_completion(battle_id DECIMAL(20,0) PRIMARY KEY,terminal_decision INT,placement_operation_id VARCHAR,target_instance_id VARCHAR,outcome_protocol_version INT,preflight_supported_version INT,preflight_instance_id VARCHAR,adopted_outcome_protocol_version INT,adopted_outcome_observed BOOLEAN)");
                statement.execute("INSERT INTO match_global_formation VALUES"
                        + "(1,'o','i',5,1,X'01'),(2,'o','i',1,1,X'01'),(3,'o','i',3,0,X'01'),"
                        + "(4,'o','i',3,9223372036854775807,X'01'),(5,'o','i',3,1,X''),(6,'o','i',4,1,X'01'),"
                        + "(9223372036854775808,'o','i',3,1,X'01'),(18446744073709551615,'o','i',4,1,X'01')");
                statement.execute("INSERT INTO match_battle_completion(battle_id,terminal_decision) VALUES(6,1)");
            }
            var upper = ConsistencySqlPlans.key(query(db, ConsistencySqlPlans.expiredUpper()).getFirst());
            assertThat(upper.first()).isEqualByComparingTo("18446744073709551615");
            var first = query(db, ConsistencySqlPlans.expiredPage(null, upper, 1));
            assertThat(first).hasSize(1);
            var key = ConsistencySqlPlans.key(first.getFirst());
            assertThat(key.first()).isEqualByComparingTo("9223372036854775808");
            assertThat(ConsistencySqlPlans.flag(first.getFirst(), "completion_missing")).isTrue();
            assertThat(ConsistencySqlPlans.finding(ConsistencySqlPlans.forCheck("match.expired_unrecoverable").getFirst(), first.getFirst()).reason())
                    .isEqualTo("completion_missing");
            var second = query(db, ConsistencySqlPlans.expiredPage(key, upper, 1));
            assertThat(second).hasSize(1);
            assertThat(ConsistencySqlPlans.key(second.getFirst())).isEqualTo(upper);
            assertThat(query(db, ConsistencySqlPlans.expiredPage(upper, upper, 1))).isEmpty();
        }
    }

    @Test void plansRemainReadOnlyAndPayloadBodyIsNeverSelected() {
        var upper = new ConsistencySqlPlans.Key(new BigDecimal("18446744073709551615"), BigDecimal.ZERO);
        for (var plan : List.of(ConsistencySqlPlans.expiredUpper(), ConsistencySqlPlans.expiredPage(null, upper, 128))) {
            assertThat(plan.sql()).startsWith("SELECT ").contains("state IN (2,3,4)", "deadline_ms>0", "-60000")
                    .doesNotContain("FOR UPDATE", "d.create_request", "UPDATE ", "DELETE ", "INSERT ");
        }
        assertThatThrownBy(() -> ConsistencySqlPlans.expiredPage(null, upper, 257)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsistencySqlPlans.expiredPage(null, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    private static List<Map<String, Object>> query(Connection db, ConsistencySqlPlans.Query query) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        try (var statement = db.prepareStatement(H2ConsistencySql.forDatabase(db, query.sql()))) {
            for (int i = 0; i < query.parameters().size(); i++) statement.setObject(i + 1, query.parameters().get(i));
            try (var rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                while (rows.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= columns.getColumnCount(); i++) row.put(columns.getColumnLabel(i), rows.getObject(i));
                    result.add(row);
                }
            }
        }
        return result;
    }
}
